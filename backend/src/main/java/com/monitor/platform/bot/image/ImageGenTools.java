package com.monitor.platform.bot.image;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 生图工具：<strong>按请求构造</strong>，把生成的图片攒进本次消息的 {@link GeneratedImages}。
 *
 * <p>与其它工具（{@code BotUserTools} / {@code BotChannelStatusTools}）一样是每请求一个实例；
 * 本例里没有「以谁的身份」的差别（所有人都能用），但按请求构造仍然是必要的 ——
 * 图片要归属到触发它的那条消息上，不能串到别人的回复里。</p>
 */
public class ImageGenTools {

    private final ImageGenService service;
    private final GeneratedImages generated;

    public ImageGenTools(ImageGenService service, GeneratedImages generated) {
        this.service = service;
        this.generated = generated;
    }

    @Tool(description = "根据一句中文描述生成一张图片并发给用户。当用户说「画一张…」「生成图片」「来张图」时调用。"
            + "prompt 用中文写清楚主体、场景与风格；一次只生成一张。"
            + "受全局限频约束：一分钟最多三张，超了要告诉用户稍后再试。")
    public String generateImage(String prompt) {
        ImageGenService.Result result = service.generate(prompt);
        if (!result.ok()) {
            return result.error();
        }
        generated.add(result.image());
        return "图片已经生成好了，会随这条回复一起发给用户。"
                + "你只需要用一句话简短回应用户，不要描述图片内容、不要输出链接或 Markdown。";
    }
}
