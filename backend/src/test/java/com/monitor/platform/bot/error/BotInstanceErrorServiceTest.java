package com.monitor.platform.bot.error;

import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.pool.PoolAdminKeyResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotInstanceErrorServiceTest {

    private final PlatformRepository platformRepository = mock(PlatformRepository.class);
    private final PoolAdminKeyResolver adminKeyResolver = mock(PoolAdminKeyResolver.class);
    private final Sub2OpsAdminClient client = mock(Sub2OpsAdminClient.class);
    private final BotInstanceErrorService service = new BotInstanceErrorService(
            platformRepository, adminKeyResolver, client);

    @Test
    void shouldCompareMultipleInstances() {
        PlatformEntity one = platform("one");
        PlatformEntity two = platform("two");
        when(platformRepository.findAll()).thenReturn(List.of(one, two));
        when(adminKeyResolver.resolve(one)).thenReturn(Optional.of("key-one"));
        when(adminKeyResolver.resolve(two)).thenReturn(Optional.of("key-two"));
        when(client.fetchOverview(eq("https://one.example"), eq("key-one"), any(), any()))
                .thenReturn(new Sub2OpsOverview(90, 1000, 20, 10, 2, 3, 1, 5, 0.02, 0.01, 0.98));
        when(client.fetchOverview(eq("https://two.example"), eq("key-two"), any(), any()))
                .thenReturn(new Sub2OpsOverview(40, 500, 100, 80, 10, 40, 10, 20, 0.20, 0.15, 0.80));

        BotReport report = service.report("1h");

        assertThat(report.title()).contains("多实例错误诊断");
        assertThat(report.toPlainText())
                .contains("one")
                .contains("two")
                .contains("异常")
                .contains("整体错误率");
    }

    private static PlatformEntity platform(String name) {
        PlatformEntity platform = new PlatformEntity();
        platform.setId(name.equals("one") ? 1 : 2);
        platform.setPlatformName(name);
        platform.setPlatformType("sub2api");
        platform.setUrl("https://" + name + ".example");
        platform.setStatus(true);
        platform.setAdminKeyEncryptedPayload("encrypted");
        return platform;
    }
}
