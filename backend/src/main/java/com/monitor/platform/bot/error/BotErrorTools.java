package com.monitor.platform.bot.error;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 管理员错误排障工具。只挂给管理员人设，普通用户拿不到。
 */
@Component
public class BotErrorTools {

    private final BotErrorService service;

    public BotErrorTools(BotErrorService service) {
        this.service = service;
    }

    @Tool(description = "仅管理员可用：诊断指定时间窗内的错误情况。"
            + "返回错误数、错误率、429/529、401/403、5xx、主要错误类型和问题账号，并给出建议。"
            + "range 支持 5m/15m/30m/1h/6h/24h/7d/30d，默认 1h。")
    public String diagnoseErrors(String range) {
        return service.diagnoseReport(range).toPlainText();
    }

    @Tool(description = "仅管理员可用：查询最近的错误列表。"
            + "不需要请求 ID，可以按时间窗、状态码、5xx、限流、配额或关键词筛选。"
            + "range 支持 5m/15m/30m/1h/6h/24h/7d/30d，默认 1h。"
            + "filter 示例：429、401、5xx、限流、配额、账号名、模型名。")
    public String recentErrors(String range,
                               @ToolParam(required = false, description = "状态码/5xx/限流/配额/关键词") String filter) {
        return service.recentReport(join(range, filter)).toPlainText();
    }

    @Tool(description = "仅管理员可用：查询错误最多的账号排行。"
            + "range 默认 1h，limit 为 1-20。")
    public String topErrorAccounts(String range,
                                   @ToolParam(required = false, description = "返回条数 1-20，默认 10") Integer limit) {
        return service.accountReport(join(range, limit == null ? null : String.valueOf(limit))).toPlainText();
    }

    @Tool(description = "仅管理员可用：查询指定平台用户的最近错误。"
            + "user 可以是邮箱或用户 ID；range 默认 1h。")
    public String errorsByUser(String user,
                               @ToolParam(required = false, description = "时间窗，默认 1h") String range) {
        return service.userReport(user, range).toPlainText();
    }

    @Tool(description = "仅管理员可用：查询入口拒绝统计，包括无效 API Key、Key 禁用、IP 限制、用户禁用、分组禁用或未授权。"
            + "这些请求通常不会出现在普通错误列表里。range 默认 1h。")
    public String ingressRejections(String range) {
        return service.ingressReport(range).toPlainText();
    }

    @Tool(description = "仅管理员可用：按时间窗、日志级别和关键词查询系统日志，不需要请求 ID。"
            + "level 可填 warn/error 等；keyword 可填 529、upstream、模型名或组件名。range 默认 1h。")
    public String recentSystemLogs(String range,
                                   @ToolParam(required = false, description = "日志级别，如 warn/error") String level,
                                   @ToolParam(required = false, description = "关键词，如 529、upstream、模型名") String keyword) {
        return service.systemLogReport(join(join(range, level), keyword)).toPlainText();
    }

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + " " + second;
    }
}
