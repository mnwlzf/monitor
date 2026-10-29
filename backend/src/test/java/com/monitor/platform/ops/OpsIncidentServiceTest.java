package com.monitor.platform.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.error.Sub2ErrorReader;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorRow;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.ops.client.Sub2OpsActionClient;
import com.monitor.platform.ops.client.Sub2OpsActionClient.AccountState;
import com.monitor.platform.pool.PoolAdminKeyResolver;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsIncidentServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCreateSafeRateLimitPlanForSingleAccount() throws Exception {
        Sub2ErrorReader reader = mock(Sub2ErrorReader.class);
        OpsIncidentRepository incidents = mock(OpsIncidentRepository.class);
        OpsIncidentEventRepository events = mock(OpsIncidentEventRepository.class);
        OpsActionPlanRepository plans = mock(OpsActionPlanRepository.class);
        OpsActionExecutionRepository executions = mock(OpsActionExecutionRepository.class);
        Sub2OpsActionClient actionClient = mock(Sub2OpsActionClient.class);
        PlatformRepository platforms = mock(PlatformRepository.class);
        PoolAdminKeyResolver keys = mock(PoolAdminKeyResolver.class);

        OpsIncidentEntity incident = incident(1L, "account_auth", "upstream", 429);
        when(incidents.findById(1L)).thenReturn(Optional.of(incident));
        ErrorRow row = row(123L, "account-a", "openai", "account_auth", "upstream", 429);
        when(reader.recent(any(), any(), any(), eq(300))).thenReturn(List.of(row));

        OpsIncidentService service = new OpsIncidentService(reader, incidents, events, plans, executions,
                actionClient, platforms, keys, mapper, mock(OpsIncidentNotifier.class), mock(OpsMonitoringService.class));

        OpsActionPlanEntity plan = service.createPlan(1L, "admin@example.com");

        assertThat(plan.getActionType()).isEqualTo("CLEAR_RATE_LIMIT");
        assertThat(plan.getTargetId()).isEqualTo(123L);
        assertThat(plan.getRiskLevel()).isEqualTo("LOW");
        assertThat(plan.getStatus()).isEqualTo("PENDING_APPROVAL");
        verify(plans).insert(any(OpsActionPlanEntity.class));
        verify(events).append(eq(1L), eq("PLAN_CREATED"), eq("admin@example.com"), anyString());
    }

    @Test
    void shouldExecuteConfirmedPlanAndVerifyAccountState() throws Exception {
        Sub2ErrorReader reader = mock(Sub2ErrorReader.class);
        OpsIncidentRepository incidents = mock(OpsIncidentRepository.class);
        OpsIncidentEventRepository events = mock(OpsIncidentEventRepository.class);
        OpsActionPlanRepository plans = mock(OpsActionPlanRepository.class);
        OpsActionExecutionRepository executions = mock(OpsActionExecutionRepository.class);
        Sub2OpsActionClient actionClient = mock(Sub2OpsActionClient.class);
        PlatformRepository platforms = mock(PlatformRepository.class);
        PoolAdminKeyResolver keys = mock(PoolAdminKeyResolver.class);

        OpsIncidentEntity incident = incident(2L, "account_auth", "upstream", 429);
        when(incidents.findById(2L)).thenReturn(Optional.of(incident));
        OpsActionPlanEntity plan = new OpsActionPlanEntity();
        plan.setId(10L);
        plan.setIncidentId(2L);
        plan.setPlanCode("OPTEST");
        plan.setActionType("CLEAR_RATE_LIMIT");
        plan.setTargetType("ACCOUNT");
        plan.setTargetId(123L);
        plan.setTargetName("account-a");
        plan.setRiskLevel("LOW");
        plan.setStatus("PENDING_APPROVAL");
        plan.setConfirmationCode("ABC12345");
        plan.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        when(plans.findById(10L)).thenReturn(Optional.of(plan));
        when(plans.claimForExecution(eq(10L), eq("ABC12345"), eq("admin@example.com"), any(), any())).thenReturn(true);

        PlatformEntity platform = new PlatformEntity();
        platform.setUrl("https://sub2.example.com");
        platform.setPoolMonitoringEnabled(true);
        platform.setStatus(true);
        when(platforms.findAll()).thenReturn(List.of(platform));
        when(keys.resolve(platform)).thenReturn(Optional.of("admin-key"));

        AccountState before = new AccountState(123L, "account-a", "openai", "active", true,
                "rate limited", OffsetDateTime.now().plusMinutes(3), null, null, null);
        AccountState after = new AccountState(123L, "account-a", "openai", "active", true,
                null, null, null, null, null);
        when(actionClient.fetchAccount("https://sub2.example.com", "admin-key", 123L)).thenReturn(before);
        when(actionClient.clearRateLimit("https://sub2.example.com", "admin-key", 123L)).thenReturn(after);

        OpsIncidentService service = new OpsIncidentService(reader, incidents, events, plans, executions,
                actionClient, platforms, keys, mapper, mock(OpsIncidentNotifier.class), mock(OpsMonitoringService.class));

        OpsIncidentService.ExecutionResult result = service.executePlan(10L, "ABC12345", "admin@example.com");

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("回读验证通过");
        verify(actionClient).clearRateLimit("https://sub2.example.com", "admin-key", 123L);
        verify(executions).insert(any(OpsActionExecutionEntity.class));
        verify(events).append(eq(2L), eq("PLAN_EXECUTED"), eq("admin@example.com"), anyString());
    }

    @Test
    void shouldCollectMonitoringSignalsEvenWhenErrorSourceIsUnavailable() {
        Sub2ErrorReader reader = mock(Sub2ErrorReader.class);
        OpsIncidentRepository incidents = mock(OpsIncidentRepository.class);
        OpsIncidentEventRepository events = mock(OpsIncidentEventRepository.class);
        OpsActionPlanRepository plans = mock(OpsActionPlanRepository.class);
        OpsActionExecutionRepository executions = mock(OpsActionExecutionRepository.class);
        OpsMonitoringService monitoring = mock(OpsMonitoringService.class);
        OpsIncidentNotifier notifier = mock(OpsIncidentNotifier.class);

        when(reader.isAvailable()).thenReturn(false);
        when(monitoring.hasReadableSource()).thenReturn(true);
        when(monitoring.collectIncidentSignals()).thenReturn(List.of(
                new OpsMonitoringService.MonitoringSignal("PROXY_HEALTH", "proxy:9",
                        "代理健康异常：香港出口", "P1", "PROXY", "9", "代理已过期", "{}")));

        OpsIncidentService service = new OpsIncidentService(reader, incidents, events, plans, executions,
                mock(Sub2OpsActionClient.class), mock(PlatformRepository.class),
                mock(PoolAdminKeyResolver.class), mapper, notifier, monitoring);

        assertThat(service.refreshIncidents()).isEqualTo(1);
        verify(incidents).insert(any(OpsIncidentEntity.class));
        verify(notifier).notifyNewIncident(any(OpsIncidentEntity.class));
        verify(incidents).resolveStale(any(), any(), any());
    }

    private static OpsIncidentEntity incident(long id, String phase, String type, int status) throws Exception {
        OpsIncidentEntity incident = new OpsIncidentEntity();
        incident.setId(id);
        incident.setTitle("账号限流（429）");
        incident.setSeverity("P1");
        incident.setStatus("ACTIVE");
        incident.setRootCauseCode(phase + " / " + type + " / " + status);
        incident.setImpactSummary("近 30 分钟 10 次，影响 1 用户 / 1 账号");
        incident.setEvidenceJson(new ObjectMapper().writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("phase", phase);
            put("type", type);
            put("statusCode", status);
            put("providerErrorCode", "");
            put("count", 10);
        }}));
        incident.setFirstSeenAt(OffsetDateTime.now().minusMinutes(5));
        incident.setLastSeenAt(OffsetDateTime.now());
        return incident;
    }

    private static ErrorRow row(long accountId, String accountName, String platform,
                                String phase, String type, int status) {
        return new ErrorRow(
                1L, OffsetDateTime.now(), phase, type, "warning", status, status,
                platform, "gpt-test", "gpt-test", null, "rate limited",
                1L, "user@example.com", "active", null, null,
                2L, "key-a", "active", false, null, null, null,
                accountId, accountName, "active", true,
                OffsetDateTime.now().minusMinutes(1), null, null, null, null, "rate limited",
                3L, "group-a", "active", false, null, null, true,
                "/v1/chat/completions", "https://upstream.example.com", "1.2.3.4", "test", false,
                false, false, 100, 20L, "req-1", "client-1");
    }
}
