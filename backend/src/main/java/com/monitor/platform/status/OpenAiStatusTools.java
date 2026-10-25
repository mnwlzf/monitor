package com.monitor.platform.status;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 给 QQ 机器人自然语言问答使用的 OpenAI 状态工具。
 *
 * <p>只返回公开的官方状态，不包含任何本平台数据，因此管理员与指定群内的平台用户都可使用。</p>
 */
public class OpenAiStatusTools {

    private final OpenAiStatusService statusService;

    public OpenAiStatusTools(OpenAiStatusService statusService) {
        this.statusService = statusService;
    }

    @Tool(description = "查询 OpenAI 官方服务状态，返回中文查询时间、总体状态、异常组件和进行中事件。查询时间表示本次实时读取状态页的北京时间。"
            + "用户问 OpenAI / ChatGPT / Codex 服务是否正常时调用。")
    public String openaiStatus() {
        return statusService.statusReport();
    }
}