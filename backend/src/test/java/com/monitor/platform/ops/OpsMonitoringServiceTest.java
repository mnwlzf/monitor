package com.monitor.platform.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.channel.Sub2ChannelStatusReader;
import com.monitor.platform.bot.channel.Sub2ChannelStatusReader.ChannelStatusRow;
import com.monitor.platform.bot.error.Sub2ErrorReader;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.pool.RateChangeImpactService;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpsMonitoringServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void aggregatesAllReadOnlyMonitoringSources() {
        Sub2ChannelStatusReader channels = mock(Sub2ChannelStatusReader.class);
        Sub2ErrorReader errors = mock(Sub2ErrorReader.class);
        Sub2ProxyReader proxies = mock(Sub2ProxyReader.class);
        PlatformRepository platforms = mock(PlatformRepository.class);
        PoolAccountRepository accounts = mock(PoolAccountRepository.class);
        UpstreamChangeEventRepository changes = mock(UpstreamChangeEventRepository.class);
        RateChangeImpactService rates = mock(RateChangeImpactService.class);

        when(channels.isAvailable()).thenReturn(true);
        when(channels.byPlatformGroup(any(), any())).thenReturn(List.of(
                channel("openai", 1, "主分组", 100, 30),
                channel("openai", 2, "备用分组", 100, 40)));
        when(errors.isAvailable()).thenReturn(true);
        when(errors.topUsers(any(), any(), any(), anyInt())).thenReturn(List.of(
                new Sub2ErrorReader.UserGroup(7L, "user@example.com", 25, 0, OffsetDateTime.now(ZONE))));
        when(proxies.isAvailable()).thenReturn(true);
        when(proxies.list()).thenReturn(List.of(new Sub2ProxyReader.ProxyRow(
                9L, "香港出口", "socks5", "active", OffsetDateTime.now(ZONE).minusDays(1),
                "direct", null, 7)));
        when(platforms.findAll()).thenReturn(List.of());
        when(changes.findInUseGroupRateEventsSince(any(), anyInt())).thenReturn(List.of());

        OpsMonitoringService service = new OpsMonitoringService(channels, errors, proxies, platforms,
                accounts, changes, rates, new ObjectMapper());

        String overview = service.overviewReport().toPlainText();
        assertThat(overview)
                .contains("智能运维监控总览")
                .contains("异常渠道")
                .contains("代理异常")
                .contains("用户异常")
                .contains("疑似 Provider 级异常");

        assertThat(service.collectIncidentSignals())
                .extracting(OpsMonitoringService.MonitoringSignal::type)
                .contains("CHANNEL_HEALTH", "PROXY_HEALTH", "USER_ANOMALY");
    }

    private static ChannelStatusRow channel(String platform, long groupId, String name,
                                            long success, long failed) {
        return new ChannelStatusRow(platform, groupId, name, success, failed,
                1000, 500, 100, 400, 1200, 2);
    }
}
