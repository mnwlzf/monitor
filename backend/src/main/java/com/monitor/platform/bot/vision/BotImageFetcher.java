package com.monitor.platform.bot.vision;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 把用户发的图片取下来，交给模型识别。
 *
 * <p>OneBot v11 的图片段形如
 * {@code {"type":"image","data":{"file":"xxx.jpg","url":"https://..."}}}。
 * NapCat 一般会直接给 {@code url}；拿不到就退回看 {@code file} 是不是 http(s) 链接。</p>
 *
 * <p>下载有硬上限：超过大小、类型不是图片、或下载失败都只跳过这一张并记日志 ——
 * 图片理解失败不能影响文字部分正常回答。</p>
 */
@Component
public class BotImageFetcher {

    private static final Logger log = LoggerFactory.getLogger(BotImageFetcher.class);

    /** OneBot 图片段里的图片地址。 */
    public record ImageRef(String url) {
    }

    /** 下载好的图片。 */
    public record FetchedImage(MediaType mediaType, byte[] bytes) {
    }

    private final BotVisionProperties properties;
    private final HttpClient httpClient;

    public BotImageFetcher(BotVisionProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 从 OneBot 消息段里抽出图片引用（按出现顺序）。 */
    public static List<ImageRef> extract(JsonNode message) {
        if (message == null || !message.isArray()) {
            return List.of();
        }
        List<ImageRef> refs = new ArrayList<>();
        for (JsonNode segment : message) {
            if (!"image".equals(segment.path("type").asText())) {
                continue;
            }
            JsonNode data = segment.path("data");
            String url = httpUrl(data.path("url").asText(null));
            if (url == null) {
                // 有些实现只给 file，且 file 本身就是 http(s) 链接
                url = httpUrl(data.path("file").asText(null));
            }
            if (url != null) {
                refs.add(new ImageRef(url));
            }
        }
        return refs;
    }

    /**
     * 依次下载图片。
     *
     * @return 下载成功的图片；失败的单张会被跳过
     */
    public List<FetchedImage> fetch(List<ImageRef> refs) {
        if (!properties.isEnabled() || refs == null || refs.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, properties.getMaxImagesPerMessage());
        List<FetchedImage> images = new ArrayList<>();
        for (ImageRef ref : refs) {
            if (images.size() >= limit) {
                log.debug("图片数量超过上限 {}，其余忽略", limit);
                break;
            }
            FetchedImage image = download(ref);
            if (image != null) {
                images.add(image);
            }
        }
        return images;
    }

    private FetchedImage download(ImageRef ref) {
        long maxBytes = Math.min(properties.getMaxImageBytes(), Integer.MAX_VALUE - 8);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ref.url()))
                    .timeout(properties.getTimeout())
                    .header("User-Agent", "monitor-bot/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                log.warn("下载图片失败: status={}, url={}", response.statusCode(), ref.url());
                return null;
            }
            // 先看 Content-Length，能提前拒绝就不用读了
            long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (declared > maxBytes) {
                log.warn("图片超过大小上限（{} > {} 字节），跳过", declared, maxBytes);
                closeQuietly(response.body());
                return null;
            }

            byte[] bytes;
            try (InputStream in = response.body()) {
                bytes = in.readNBytes((int) maxBytes + 1);
            }
            if (bytes.length > maxBytes) {
                log.warn("图片超过大小上限（>{} 字节），跳过: {}", maxBytes, ref.url());
                return null;
            }
            if (bytes.length == 0) {
                return null;
            }

            MediaType mediaType = resolveMediaType(response.headers().firstValue("content-type").orElse(null), bytes);
            if (mediaType == null) {
                log.warn("无法识别的图片类型，跳过: {}", ref.url());
                return null;
            }
            return new FetchedImage(mediaType, bytes);
        } catch (Exception ex) {
            log.warn("下载图片异常: url={}, reason={}", ref.url(), ex.getMessage());
            return null;
        }
    }

    /** 优先用响应头里的类型；缺失或不可信时按文件头判断。 */
    private static MediaType resolveMediaType(String contentType, byte[] bytes) {
        MediaType sniffed = sniff(bytes);
        if (contentType != null) {
            try {
                MediaType parsed = MediaType.parseMediaType(contentType);
                if (parsed.isCompatibleWith(MediaType.IMAGE_JPEG)
                        || parsed.isCompatibleWith(MediaType.IMAGE_PNG)
                        || parsed.isCompatibleWith(MediaType.IMAGE_GIF)
                        || "image/webp".equalsIgnoreCase(parsed.getType() + "/" + parsed.getSubtype())) {
                    // 响应头说是图片，但仍以文件头为准（有些图床 Content-Type 是 octet-stream）
                    return sniffed != null ? sniffed : parsed;
                }
            } catch (Exception ignored) {
                // 头不合法就靠嗅探
            }
        }
        return sniffed;
    }

    /** 按文件头识别常见图片格式。 */
    private static MediaType sniff(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return MediaType.IMAGE_JPEG;
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return MediaType.IMAGE_PNG;
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return MediaType.IMAGE_GIF;
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return MediaType.parseMediaType("image/webp");
        }
        return null;
    }

    private static String httpUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("http://") || trimmed.startsWith("https://") ? trimmed : null;
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (Exception ignored) {
            // 忽略
        }
    }
}