package com.monitor.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.api.dto.AccountMetricPointResponse;
import com.monitor.platform.api.dto.AccountUsageDashboardResponse;
import com.monitor.platform.api.dto.CreatePlatformRequest;
import com.monitor.platform.api.dto.PlatformResponse;
import com.monitor.platform.api.dto.UpdatePlatformRequest;
import com.monitor.platform.collector.application.AccountCredentialService;
import com.monitor.platform.collector.repository.AccountMetricSnapshotRepository;
import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.AccountUsageDashboardSnapshotRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.UpstreamGroupRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.AccountMetricSnapshotEntity;
import com.monitor.platform.collector.repository.entity.AccountUsageDashboardSnapshotEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.collector.security.CredentialCipher;
import com.monitor.platform.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台实例新增 / 修改 / 删除的业务规则测试。
 */
@ExtendWith(MockitoExtension.class)
class UpstreamAdminServicePlatformTest {

    @Mock private PlatformRepository platformRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private AccountMetricSnapshotRepository metricSnapshotRepository;
    @Mock private AccountUsageDashboardSnapshotRepository usageDashboardRepository;
    @Mock private UpstreamGroupRepository groupRepository;
    @Mock private UpstreamChangeEventRepository changeEventRepository;
    @Mock private AccountApiKeyRepository apiKeyRepository;
    @Mock private AccountCredentialService credentialService;
    @Mock private CredentialCipher credentialCipher;

    private UpstreamAdminService service;

    @BeforeEach
    void setUp() {
        service = new UpstreamAdminService(
                platformRepository,
                accountRepository,
                metricSnapshotRepository,
                usageDashboardRepository,
                groupRepository,
                changeEventRepository,
                apiKeyRepository,
                credentialService,
                credentialCipher,
                new ObjectMapper()
        );
    }

    private PlatformEntity platform(int id, String name, String url, String type) {
        PlatformEntity entity = new PlatformEntity();
        entity.setId(id);
        entity.setPlatformName(name);
        entity.setUrl(url);
        entity.setPlatformType(type);
        entity.setStatus(true);
        entity.setSettings("{}");
        return entity;
    }

    @Test
    void shouldCreatePlatform() {
        when(platformRepository.findByName("云眠")).thenReturn(Optional.empty());

        PlatformResponse response = service.createPlatform(
                new CreatePlatformRequest("云眠", "https://a.example.com", "sub2api", null));

        assertEquals("云眠", response.name());
        assertEquals("https://a.example.com", response.baseUrl());
        assertEquals("sub2api", response.platform());
        assertEquals(Boolean.TRUE, response.status());
        verify(platformRepository).save(ArgumentMatchers.any(PlatformEntity.class));
    }

    @Test
    void shouldRejectDuplicatePlatformNameOnCreate() {
        when(platformRepository.findByName("云眠")).thenReturn(Optional.of(platform(1, "云眠", "https://a", "sub2api")));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createPlatform(
                new CreatePlatformRequest("云眠", "https://b.example.com", "newapi", null)));

        assertEquals("平台名称已存在: 云眠", ex.getMessage());
        verify(platformRepository, never()).save(ArgumentMatchers.any(PlatformEntity.class));
    }

    @Test
    void shouldUpdatePlatformAndSyncAccountTypeAndUrl() {
        PlatformEntity entity = platform(1, "旧名", "https://old.example.com", "sub2api");
        AccountEntity account = new AccountEntity();
        account.setId(10);
        account.setPlatformId(1);
        account.setPlatform("sub2api");
        account.setUrl("https://old.example.com");

        when(platformRepository.findById(1)).thenReturn(Optional.of(entity));
        when(platformRepository.findByName("新名")).thenReturn(Optional.empty());
        when(accountRepository.findByPlatformId(1)).thenReturn(List.of(account));

        PlatformResponse response = service.updatePlatform(1,
                new UpdatePlatformRequest("新名", "https://new.example.com", "newapi", null, null, null));

        assertEquals("新名", response.name());
        assertEquals("https://new.example.com", response.baseUrl());
        assertEquals("newapi", response.platform());
        // 平台下账号的冗余字段需要同步，否则采集仍会走旧地址与旧适配器。
        assertEquals("newapi", account.getPlatform());
        assertEquals("https://new.example.com", account.getUrl());
        verify(accountRepository).save(account);
    }

    @Test
    void shouldKeepUnsetFieldsWhenUpdatingPlatform() {
        PlatformEntity entity = platform(1, "原名", "https://old.example.com", "sub2api");
        when(platformRepository.findById(1)).thenReturn(Optional.of(entity));
        when(accountRepository.findByPlatformId(1)).thenReturn(List.of());

        PlatformResponse response = service.updatePlatform(1,
                new UpdatePlatformRequest(null, null, null, false, null, null));

        assertEquals("原名", response.name());
        assertEquals("https://old.example.com", response.baseUrl());
        assertEquals("sub2api", response.platform());
        assertEquals(Boolean.FALSE, response.status());
    }

    @Test
    void shouldRejectRenameToAnotherPlatformName() {
        PlatformEntity entity = platform(1, "甲", "https://a", "sub2api");
        when(platformRepository.findById(1)).thenReturn(Optional.of(entity));
        when(platformRepository.findByName("乙")).thenReturn(Optional.of(platform(2, "乙", "https://b", "newapi")));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updatePlatform(1,
                new UpdatePlatformRequest("乙", null, null, null, null, null)));

        assertEquals("平台名称已存在: 乙", ex.getMessage());
    }

    @Test
    void shouldRejectUpdateWhenPlatformMissing() {
        when(platformRepository.findById(99)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updatePlatform(99,
                new UpdatePlatformRequest("任意", null, null, null, null, null)));

        assertEquals("平台不存在: 99", ex.getMessage());
    }

    @Test
    void shouldDeleteEmptyPlatform() {
        when(platformRepository.findById(1)).thenReturn(Optional.of(platform(1, "空平台", "https://a", "sub2api")));
        when(accountRepository.findByPlatformId(1)).thenReturn(List.of());

        service.deletePlatform(1);

        verify(platformRepository).softDelete(1);
    }

    private AccountUsageDashboardSnapshotEntity usageSnapshot(Integer accountId, String type,
                                                             BigDecimal totalCost, OffsetDateTime at) {
        AccountUsageDashboardSnapshotEntity entity = new AccountUsageDashboardSnapshotEntity();
        entity.setId(1L);
        entity.setAccountId(accountId);
        entity.setPlatformType(type);
        entity.setTotalCost(totalCost);
        // 累计实际成本需保持原语义，这里与累计消耗区分开以便断言不被覆盖。
        entity.setTotalActualCost(totalCost);
        entity.setMetrics("{}");
        entity.setPlatformStats("[]");
        entity.setCollectedAt(at);
        return entity;
    }

    @Test
    void shouldDeriveNewApiTodayCostFromDailyAccumulatedUsageDelta() {
        AccountEntity account = new AccountEntity();
        account.setId(10);
        account.setPlatformId(1);
        account.setDisplayName("newapi-account");
        account.setPlatform("newapi");

        OffsetDateTime now = OffsetDateTime.now();
        AccountUsageDashboardSnapshotEntity latest = usageSnapshot(
                10, "newapi", new BigDecimal("140.70"), now);
        when(platformRepository.findById(1))
                .thenReturn(Optional.of(platform(1, "云眠", "https://a", "newapi")));
        when(accountRepository.findByPlatformId(1)).thenReturn(List.of(account));
        when(usageDashboardRepository.findLatestByAccounts(ArgumentMatchers.anyList()))
                .thenReturn(List.of(latest));
        // 当天首末两条累计消耗：300.00 -> 300.80，差值为今日消耗 0.80。
        when(usageDashboardRepository.findByAccounts(ArgumentMatchers.anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(List.of(
                usageSnapshot(10, "newapi", new BigDecimal("300.00"), now.minusHours(6)),
                usageSnapshot(10, "newapi", new BigDecimal("300.80"), now.minusHours(1))));

        List<AccountUsageDashboardResponse> rows = service.listUsageDashboard(1);

        assertEquals(1, rows.size());
        // 累计实际成本保持原语义，不被今日值覆盖。
        assertEquals(new BigDecimal("140.70"), rows.get(0).totalActualCost());
        assertEquals(new BigDecimal("0.80"),
                rows.get(0).metrics().get("today_actual_cost").decimalValue());
    }

    @Test
    void shouldSkipNewApiTodayCostWhenAccumulatedUsageDecreases() {
        AccountEntity account = new AccountEntity();
        account.setId(10);
        account.setPlatformId(1);
        account.setDisplayName("newapi-account");
        account.setPlatform("newapi");

        OffsetDateTime now = OffsetDateTime.now();
        when(platformRepository.findById(1))
                .thenReturn(Optional.of(platform(1, "云眠", "https://a", "newapi")));
        when(accountRepository.findByPlatformId(1)).thenReturn(List.of(account));
        when(usageDashboardRepository.findLatestByAccounts(ArgumentMatchers.anyList()))
                .thenReturn(List.of(usageSnapshot(10, "newapi", new BigDecimal("10.00"), now)));
        // 额度被重置导致累计值下降时不展示，避免负的今日消耗。
        when(usageDashboardRepository.findByAccounts(ArgumentMatchers.anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(List.of(
                usageSnapshot(10, "newapi", new BigDecimal("300.00"), now.minusHours(6)),
                usageSnapshot(10, "newapi", new BigDecimal("10.00"), now.minusHours(1))));

        List<AccountUsageDashboardResponse> rows = service.listUsageDashboard(1);

        assertEquals(1, rows.size());
        assertFalse(rows.get(0).metrics().has("today_actual_cost"));
    }

    @Test
    void shouldRejectDeleteWhenPlatformStillHasAccounts() {
        when(platformRepository.findById(1)).thenReturn(Optional.of(platform(1, "有账号", "https://a", "sub2api")));
        when(accountRepository.findByPlatformId(1)).thenReturn(List.of(new AccountEntity(), new AccountEntity()));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.deletePlatform(1));

        assertEquals("平台下仍有 2 个账号，请先删除账号再删除平台", ex.getMessage());
        verify(platformRepository, never()).softDelete(ArgumentMatchers.anyInt());
    }

    @Test
    void shouldRevealAccountPassword() {
        AccountEntity account = new AccountEntity();
        account.setId(10);
        account.setPlatformId(1);
        account.setUsername("user@example.com");
        when(accountRepository.findById(10)).thenReturn(Optional.of(account));
        when(credentialService.resolvePassword(10)).thenReturn("s3cret");

        assertEquals("s3cret", service.revealAccountPassword(1, 10, "admin"));
    }

    @Test
    void shouldRejectRevealWhenAccountHasNoCredential() {
        AccountEntity account = new AccountEntity();
        account.setId(10);
        account.setPlatformId(1);
        when(accountRepository.findById(10)).thenReturn(Optional.of(account));
        when(credentialService.resolvePassword(10))
                .thenThrow(new IllegalStateException("账号未配置可用密码凭证: 10"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.revealAccountPassword(1, 10, "admin"));

        assertEquals("该账号没有可用的密码凭证，无法查看明文", ex.getMessage());
    }

    @Test
    void shouldRejectRevealWhenAccountBelongsToAnotherPlatform() {
        AccountEntity account = new AccountEntity();
        account.setId(10);
        account.setPlatformId(2);
        when(accountRepository.findById(10)).thenReturn(Optional.of(account));

        assertThrows(BusinessException.class, () -> service.revealAccountPassword(1, 10, "admin"));
        verify(credentialService, never()).resolvePassword(ArgumentMatchers.anyInt());
    }
    @Test
    void shouldReturnAccountMetricSeriesWithinRequestedRange() {
        AccountEntity account = new AccountEntity();
        account.setId(7);
        account.setPlatformId(1);
        when(accountRepository.findById(7)).thenReturn(Optional.of(account));

        AccountMetricSnapshotEntity point = new AccountMetricSnapshotEntity();
        point.setAccountId(7);
        point.setBalance(new BigDecimal("12.34"));
        point.setUsedQuota(new BigDecimal("100"));
        point.setCollectedAt(OffsetDateTime.now());
        when(metricSnapshotRepository.findByAccount(
                ArgumentMatchers.eq(7), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq(10000)))
                .thenReturn(List.of(point));

        List<AccountMetricPointResponse> result = service.listAccountMetrics(1, 7, "30d");

        assertEquals(1, result.size());
        assertEquals(new BigDecimal("12.34"), result.get(0).balance());

        ArgumentCaptor<OffsetDateTime> fromCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(metricSnapshotRepository).findByAccount(
                ArgumentMatchers.eq(7), fromCaptor.capture(), ArgumentMatchers.any(), ArgumentMatchers.eq(10000));
        assertTrue(fromCaptor.getValue().isBefore(OffsetDateTime.now().minusDays(29)),
                "30d 维度应查询最近 30 天的数据");
    }

    @Test
    void shouldDownsampleLargeAccountMetricSeries() {
        AccountEntity account = new AccountEntity();
        account.setId(7);
        account.setPlatformId(1);
        when(accountRepository.findById(7)).thenReturn(Optional.of(account));

        List<AccountMetricSnapshotEntity> points = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            AccountMetricSnapshotEntity point = new AccountMetricSnapshotEntity();
            point.setAccountId(7);
            point.setBalance(BigDecimal.valueOf(i));
            point.setCollectedAt(OffsetDateTime.now().minusMinutes(1000 - i));
            points.add(point);
        }
        when(metricSnapshotRepository.findByAccount(
                ArgumentMatchers.eq(7), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq(10000)))
                .thenReturn(points);

        List<AccountMetricPointResponse> result = service.listAccountMetrics(1, 7, "90d");

        assertTrue(result.size() <= 401, "抽稀后点数应不超过上限");
        assertTrue(result.size() < points.size(), "大数据量应被抽稀");
        assertEquals(points.get(0).getCollectedAt(), result.get(0).collectedAt());
        assertEquals(points.get(999).getCollectedAt(), result.get(result.size() - 1).collectedAt());
    }
}
