package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.BotGroupInfoResponse;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 通过 OneBot 读取机器人所在群的信息，仅 ADMIN 可访问。 */
@RestController
@RequestMapping("/api/v1/settings/bot/groups")
public class BotGroupInfoController {

    private final OneBotClient oneBotClient;

    public BotGroupInfoController(OneBotClient oneBotClient) {
        this.oneBotClient = oneBotClient;
    }

    @GetMapping
    public ApiResponse<List<BotGroupInfoResponse>> list() {
        return ApiResponse.of(oneBotClient.fetchGroupList().stream()
                .map(BotGroupInfoResponse::from).toList(), null);
    }

    @GetMapping("/{groupId}")
    public ApiResponse<BotGroupInfoResponse> get(@PathVariable long groupId) {
        OneBotClient.GroupInfo info = oneBotClient.fetchGroupInfo(groupId);
        return ApiResponse.of(info == null ? null : BotGroupInfoResponse.from(info), null);
    }
}
