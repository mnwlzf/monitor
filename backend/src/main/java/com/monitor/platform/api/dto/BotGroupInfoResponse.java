package com.monitor.platform.api.dto;

import com.monitor.platform.bot.onebot.OneBotClient;

/** OneBot 返回的群号与群名称。 */
public record BotGroupInfoResponse(long groupId, String groupName) {

    public static BotGroupInfoResponse from(OneBotClient.GroupInfo info) {
        return new BotGroupInfoResponse(info.groupId(), info.groupName());
    }
}
