package com.monitor.platform.bot;

import org.springframework.ai.tool.annotation.Tool;

/** 供大模型调用的邮箱绑定工具，QQ 号由当前请求绑定，不接受模型传入其他用户身份。 */
public class BotEmailBindingTools {

    private final BotEmailBindingService bindingService;
    private final Long qq;

    public BotEmailBindingTools(BotEmailBindingService bindingService, Long qq) {
        this.bindingService = bindingService;
        this.qq = qq;
    }

    @Tool(description = "当用户明确要求绑定或关联平台邮箱时调用。email 必须是用户提供的完整邮箱地址。"
            + "工具会向该邮箱发送 5 分钟有效的验证码。")
    public String bindEmail(String email) {
        return bindingService.requestBinding(qq, email);
    }

    @Tool(description = "当用户明确提供 6 位邮箱验证码时调用，用于完成当前 QQ 的邮箱绑定。")
    public String verifyEmailCode(String code) {
        return bindingService.verify(qq, code);
    }

    @Tool(description = "仅当用户明确要求解除当前 QQ 的邮箱绑定时调用。")
    public String unbindEmail() {
        return bindingService.unbind(qq);
    }
}
