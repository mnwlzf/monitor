package com.monitor.platform.bot.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片段的解析与下载。
 *
 * <p>下载用 JDK 内置 HttpServer 起本地服务，不打外网。</p>
 */
class BotImageFetcherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 只有文件头会被 sniff 用到，后面内容随意。 */
    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
    };

    private JsonNode message(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void shouldExtractUrlFromImageSegment() throws Exception {
        List<BotImageFetcher.ImageRef> refs = BotImageFetcher.extract(message("""
                [{"type":"image","data":{"file":"abc.jpg","url":"https://example.com/a.jpg"}}]
                """));

        assertEquals(1, refs.size());
        assertEquals("https://example.com/a.jpg", refs.get(0).url());
    }

    /** 有些实现只给 file，且 file 本身就是链接。 */
    @Test
    void shouldFallBackToFileWhenItIsHttpUrl() throws Exception {
        List<BotImageFetcher.ImageRef> refs = BotImageFetcher.extract(message("""
                [{"type":"image","data":{"file":"http://cdn.example.com/b.png"}}]
                """));

        assertEquals(1, refs.size());
        assertEquals("http://cdn.example.com/b.png", refs.get(0).url());
    }

    /** file 是本地路径（NapCat 容器内）时取不到，跳过这一张。 */
    @Test
    void shouldSkipLocalPathImage() throws Exception {
        List<BotImageFetcher.ImageRef> refs = BotImageFetcher.extract(message("""
                [{"type":"image","data":{"file":"/root/.config/QQ/abc.jpg"}}]
                """));

        assertTrue(refs.isEmpty());
    }

    @Test
    void shouldOnlyExtractImageSegments() throws Exception {
        List<BotImageFetcher.ImageRef> refs = BotImageFetcher.extract(message("""
                [{"type":"text","data":{"text":"看看这个"}},
                 {"type":"image","data":{"url":"https://example.com/a.jpg"}},
                 {"type":"face","data":{"id":"1"}}]
                """));

        assertEquals(1, refs.size());
    }

    @Test
    void shouldReturnEmptyForNonArrayMessage() throws Exception {
        assertTrue(BotImageFetcher.extract(message("\"[CQ:image,file=a.jpg]\"")).isEmpty());
        assertTrue(BotImageFetcher.extract(null).isEmpty());
    }

    @Test
    void shouldDownloadAndDetectTypeByMagicBytes() throws Exception {
        HttpServer server = startServer(PNG, "application/octet-stream");
        try {
            BotImageFetcher fetcher = new BotImageFetcher(new BotVisionProperties());
            List<BotImageFetcher.FetchedImage> images = fetcher.fetch(
                    List.of(new BotImageFetcher.ImageRef(url(server))));

            assertEquals(1, images.size());
            // Content-Type 是 octet-stream，应靠文件头识别成 PNG
            assertEquals(MediaType.IMAGE_PNG, images.get(0).mediaType());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectOversizedImage() throws Exception {
        HttpServer server = startServer(PNG, "image/png");
        try {
            BotVisionProperties properties = new BotVisionProperties();
            properties.setMaxImageBytes(4);
            BotImageFetcher fetcher = new BotImageFetcher(properties);

            assertTrue(fetcher.fetch(List.of(new BotImageFetcher.ImageRef(url(server)))).isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSkipDownloadWhenDisabled() {
        BotVisionProperties properties = new BotVisionProperties();
        properties.setEnabled(false);
        BotImageFetcher fetcher = new BotImageFetcher(properties);

        assertTrue(fetcher.fetch(List.of(new BotImageFetcher.ImageRef("https://example.com/a.jpg"))).isEmpty());
    }

    /** 单条消息最多处理 N 张。 */
    @Test
    void shouldLimitImagesPerMessage() throws Exception {
        HttpServer server = startServer(PNG, "image/png");
        try {
            BotVisionProperties properties = new BotVisionProperties();
            properties.setMaxImagesPerMessage(1);
            BotImageFetcher fetcher = new BotImageFetcher(properties);

            List<BotImageFetcher.FetchedImage> images = fetcher.fetch(List.of(
                    new BotImageFetcher.ImageRef(url(server)),
                    new BotImageFetcher.ImageRef(url(server))));

            assertEquals(1, images.size());
        } finally {
            server.stop(0);
        }
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/image";
    }

    private static HttpServer startServer(byte[] body, String contentType) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @SuppressWarnings("unused")
    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}