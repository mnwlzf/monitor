package com.monitor.platform.mail;

/**
 * 邮件事件场景。
 *
 * <p>不同事件使用各自的收件人列表，通过该场景区分。</p>
 */
public enum MailScene {

    /** 余额不足提醒。 */
    BALANCE_ALERT,

    /** 每日余额消耗报表。 */
    DAILY_REPORT,

    /** 正在使用的密钥发生变更提醒。 */
    API_KEY_CHANGE,

    /** 正在使用的渠道发生<b>倍率变化</b>（实时提醒，直接关系成本）。 */
    CHANNEL_ALERT,

    /** 非在用渠道的变更汇总（每天一封）。 */
    CHANNEL_DIGEST
}