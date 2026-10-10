package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.BotMessageArchiveStats;
import com.monitor.platform.api.dto.BotMessageResponse;
import com.monitor.platform.bot.archive.BotMessageArchiveAdminService;
import com.monitor.platform.common.response.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 机器人消息存档查询。
 *
 * <p>仅 ADMIN 可访问（见 {@code SecurityConfig} 的 {@code /api/v1/settings/**} 规则）——
 * 存档里有所有人的聊天内容，不能开放给普通用户。</p>
 */
@RestController
@RequestMapping("/api/v1/settings/bot/archive")
public class BotMessageArchiveController {

    private final BotMessageArchiveAdminService adminService;

    public BotMessageArchiveController(BotMessageArchiveAdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * 查询存档，时间倒序。
     *
     * @param keyword 内容模糊匹配
     * @param userId  只看某个 QQ
     * @param groupId 只看某个群
     * @param from    起始时间（ISO-8601）
     * @param to      结束时间（ISO-8601）
     * @param limit   返回条数上限
     */
    @GetMapping
    public ApiResponse<List<BotMessageResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.of(adminService.search(keyword, userId, groupId, from, to, limit), null);
    }

    /** 存档概况：是否开启、总条数、最早一条、保留天数。 */
    @GetMapping("/stats")
    public ApiResponse<BotMessageArchiveStats> stats() {
        return ApiResponse.of(adminService.stats(), null);
    }
}