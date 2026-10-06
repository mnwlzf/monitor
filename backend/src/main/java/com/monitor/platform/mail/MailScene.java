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
    DAILY_REPORT
}