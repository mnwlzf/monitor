package com.monitor.platform.api.dto;

import java.time.Instant;

/**
 * NapCat 登录状态。
 *
 * @param configured 是否读到 WebUI token 配置
 * @param reachable  WebUI / NapCat 是否可达
 * @param login      QQ 是否已登录
 * @param offline    QQ 是否离线
 * @param loginPhase NapCat 登录阶段
 * @param qrCodeUrl  离线时的扫码 URL
 * @param loginError NapCat 返回的登录错误
 * @param error      Monitor 访问 NapCat 时的错误
 * @param checkedAt  本次检查时间
 */
public record NapCatStatusResponse(
        boolean configured,
        boolean reachable,
        boolean login,
        boolean offline,
        String loginPhase,
        String qrCodeUrl,
        String loginError,
        String error,
        Instant checkedAt
) {
}