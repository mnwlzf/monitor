package com.monitor.platform.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.error.Sub2ErrorReader;
import com.monitor.platform.bot.error.Sub2ErrorReader.AccountGroup;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorFilter;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorFingerprint;
import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorRow;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.common.time.BusinessTime;
import com.monitor.platform.ops.client.Sub2OpsActionClient;
import com.monitor.platform.ops.client.Sub2OpsActionClient.AccountState;
import com.monitor.platform.pool.PoolAdminKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 方向 9 最小闭环：事故巡检、自愈计划、确认执行。 */
@Service
public class OpsIncidentService {

    private static final Logger log = LoggerFactory.getLogger(OpsIncidentService.class);
    private static final ZoneId ZONE = BusinessTime.ZONE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final Duration LOOKBACK = Duration.ofMinutes(30);
    private static final Duration PLAN_TTL = Duration.ofMinutes(10);
    private static final int MIN_FINGERPRINT_COUNT = 3;

    private final Sub2ErrorReader errorReader;
    private final OpsIncidentRepository incidentRepository;
    private final OpsIncidentEventRepository eventRepository;
    private final OpsActionPlanRepository planRepository;
    private final OpsActionExecutionRepository executionRepository;
    private final Sub2OpsActionClient actionClient;
    private final PlatformRepository platformRepository;
    private final PoolAdminKeyResolver adminKeyResolver;
    private final ObjectMapper objectMapper;
    private final OpsIncidentNotifier notifier;
    private final OpsMonitoringService monitoringService;

    public OpsIncidentService(Sub2ErrorReader errorReader,
                              OpsIncidentRepository incidentRepository,
                              OpsIncidentEventRepository eventRepository,
                              OpsActionPlanRepository planRepository,
                              OpsActionExecutionRepository executionRepository,
                              Sub2OpsActionClient actionClient,
                              PlatformRepository platformRepository,
                              PoolAdminKeyResolver adminKeyResolver,
                              ObjectMapper objectMapper,
                              OpsIncidentNotifier notifier,
                              OpsMonitoringService monitoringService) {
        this.errorReader = errorReader;
        this.incidentRepository = incidentRepository;
        this.eventRepository = eventRepository;
        this.planRepository = planRepository;
        this.executionRepository = executionRepository;
        this.actionClient = actionClient;
        this.platformRepository = platformRepository;
        this.adminKeyResolver = adminKeyResolver;
        this.objectMapper = objectMapper;
        this.notifier = notifier;
        this.monitoringService = monitoringService;
    }

    /** 定时任务入口：把错误指纹转成事故，并关闭 30 分钟没有新信号的旧事故。 */
    public int refreshIncidents() {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        OffsetDateTime from = now.minus(LOOKBACK);
        int changed = 0;
        boolean errorSourceAvailable = errorReader.isAvailable();
        boolean monitoringSourceAvailable = monitoringService.hasReadableSource();
        if (errorSourceAvailable) {
            try {
                List<ErrorFingerprint> fingerprints = errorReader.topFingerprints(
                        from, now, ErrorFilter.everything(null), 20);
                for (ErrorFingerprint fingerprint : fingerprints) {
                    if (fingerprint.count() < MIN_FINGERPRINT_COUNT) {
                        continue;
                    }
                    upsertIncident(fingerprint, now);
                    changed++;
                }
            } catch (RuntimeException ex) {
                log.warn("读取错误指纹事故信号失败: {}", ex.getMessage());
            }
        }
        try {
            for (OpsMonitoringService.MonitoringSignal signal : monitoringService.collectIncidentSignals()) {
                upsertMonitoringSignal(signal, now);
                changed++;
            }
        } catch (RuntimeException ex) {
            log.warn("读取多源监控事故信号失败: {}", ex.getMessage());
        }
        if (errorSourceAvailable || monitoringSourceAvailable) {
            try {
                int resolved = incidentRepository.resolveStale(from, now, now);
                planRepository.expireStale(now, now);
                if (resolved > 0) {
                    log.info("智能运维事故巡检关闭过期事故: {}", resolved);
                }
            } catch (RuntimeException ex) {
                log.warn("关闭过期智能运维事故失败: {}", ex.getMessage());
            }
        }
        return changed;
    }

    public List<OpsIncidentEntity> listIncidents(String status, int limit) {
        return incidentRepository.recent(status, limit);
    }

    public Optional<OpsIncidentEntity> findIncident(Long id) {
        return incidentRepository.findById(id);
    }

    public List<OpsIncidentEventEntity> incidentEvents(Long id, int limit) {
        return eventRepository.recent(id, limit);
    }

    public List<OpsActionPlanEntity> recentPlans(int limit) {
        return planRepository.recent(limit);
    }

    public Optional<OpsActionPlanEntity> findPlan(Long id) {
        return planRepository.findById(id);
    }

    /** 生成自愈计划。只写本地计划，不调用 Sub2API 写接口。 */
    public OpsActionPlanEntity createPlan(Long incidentId, String actor) {
        OpsIncidentEntity incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> BusinessException.of("事故不存在: " + incidentId));
        Evidence evidence = evidence(incident);
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        List<ErrorRow> rows = matchingRows(evidence, now);
        Map<Long, AccountGroup> accounts = affectedAccounts(rows);
        String actionType = actionType(evidence, accounts.size());
        Long targetId = accounts.size() == 1 ? accounts.keySet().iterator().next() : null;
        if ("CLEAR_RATE_LIMIT".equals(actionType) && activeRateLimit(rows, targetId, now)) {
            actionType = "OBSERVE";
        }
        AccountGroup target = targetId == null ? null : accounts.get(targetId);
        String targetName = target == null ? null : target.accountName();

        OpsActionPlanEntity plan = new OpsActionPlanEntity();
        plan.setIncidentId(incidentId);
        plan.setPlanCode("OP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT));
        plan.setActionType(actionType);
        plan.setTargetType(targetId == null ? "INCIDENT" : "ACCOUNT");
        plan.setTargetId(targetId);
        plan.setTargetName(targetName);
        plan.setRiskLevel(riskLevel(actionType));
        plan.setStatus("PENDING_APPROVAL");
        plan.setSummary(summary(incident, actionType, targetName, accounts.size()));
        plan.setExpectedEffect(expectedEffect(actionType));
        plan.setRollbackPlanJson(json(Map.of("note", rollbackNote(actionType))));
        plan.setBeforeJson(beforeSnapshot(rows, targetId));
        plan.setConfirmationCode(UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT));
        plan.setExpiresAt(now.plus(PLAN_TTL));
        plan.setCreatedBy(actor == null ? "unknown" : actor);
        planRepository.insert(plan);
        eventRepository.append(incidentId, "PLAN_CREATED", actor,
                "生成自愈计划 " + plan.getPlanCode() + "：" + plan.getSummary());
        return plan;
    }

    /** 管理员确认后执行计划；确认码一次性使用。 */
    public ExecutionResult executePlan(Long planId, String confirmationCode, String actor) {
        OpsActionPlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> BusinessException.of("自愈计划不存在: " + planId));
        if (!"PENDING_APPROVAL".equals(plan.getStatus())) {
            return new ExecutionResult(false, "计划当前状态为 " + plan.getStatus() + "，不能执行。", plan);
        }
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        if (plan.getExpiresAt() == null || !plan.getExpiresAt().isAfter(now)) {
            return new ExecutionResult(false, "确认码已过期，请重新生成自愈计划。", plan);
        }
        String code = confirmationCode == null ? "" : confirmationCode.trim().toUpperCase(Locale.ROOT);
        if (code.isBlank() || !code.equalsIgnoreCase(plan.getConfirmationCode())) {
            return new ExecutionResult(false, "确认码不正确。", plan);
        }
        boolean claimed = planRepository.claimForExecution(planId, code,
                actor == null ? "unknown" : actor, now, now);
        if (!claimed) {
            return new ExecutionResult(false, "计划已被执行或确认码已失效。", plan);
        }

        OpsActionExecutionEntity execution = new OpsActionExecutionEntity();
        execution.setPlanId(planId);
        execution.setStatus("RUNNING");
        execution.setRequestJson(json(Map.of(
                "action", plan.getActionType(),
                "targetType", String.valueOf(plan.getTargetType()),
                "targetId", String.valueOf(plan.getTargetId()))));
        executionRepository.insert(execution);

        try {
            Map<String, Object> result = executeAction(plan);
            String responseJson = json(result);
            boolean verified = Boolean.TRUE.equals(result.get("verified"));
            executionRepository.markFinished(execution.getId(), "SUCCESS", responseJson, null, OffsetDateTime.now(ZONE));
            planRepository.markExecuted(planId,
                    json(result.get("before")),
                    responseJson, OffsetDateTime.now(ZONE), OffsetDateTime.now(ZONE));
            incidentRepository.updateStatus(plan.getIncidentId(), "MITIGATING", OffsetDateTime.now(ZONE));
            eventRepository.append(plan.getIncidentId(), "PLAN_EXECUTED", actor,
                    "执行自愈计划 " + plan.getPlanCode() + "，验证=" + verified);
            OpsActionPlanEntity updated = planRepository.findById(planId).orElse(plan);
            return new ExecutionResult(true,
                    verified ? "动作已执行并回读验证通过。" : "动作已执行，但回读状态仍需观察。", updated);
        } catch (RuntimeException ex) {
            String message = safeMessage(ex);
            executionRepository.markFinished(execution.getId(), "FAILED", null, message, OffsetDateTime.now(ZONE));
            planRepository.markFailed(planId, json(Map.of("error", message)), OffsetDateTime.now(ZONE));
            eventRepository.append(plan.getIncidentId(), "PLAN_FAILED", actor,
                    "执行自愈计划 " + plan.getPlanCode() + " 失败：" + message);
            OpsActionPlanEntity updated = planRepository.findById(planId).orElse(plan);
            return new ExecutionResult(false, "执行失败：" + message, updated);
        }
    }

    public BotReport incidentListReport(int limit) {
        List<OpsIncidentEntity> incidents = listIncidents("OPEN", limit);
        List<BotReport.Row> rows = new ArrayList<>();
        for (OpsIncidentEntity incident : incidents) {
            rows.add(BotReport.Row.of(
                    "#" + incident.getId(),
                    incident.getSeverity(),
                    incident.getStatus(),
                    incident.getTitle(),
                    nz(incident.getImpactSummary()),
                    time(incident.getLastSeenAt())));
        }
        List<BotReport.Block> blocks = new ArrayList<>();
        if (rows.isEmpty()) {
            blocks.add(BotReport.paragraph("当前事故", List.of("没有活跃事故。")));
        } else {
            blocks.add(new BotReport.Block(null, List.of(
                    BotReport.Col.left("编号"),
                    BotReport.Col.left("级别"),
                    BotReport.Col.left("状态"),
                    BotReport.Col.left("故障"),
                    BotReport.Col.left("影响"),
                    BotReport.Col.left("最近")), rows, null));
        }
        return BotReport.of("智能运维事故", blocks,
                List.of("查看详情：/事故 #编号", "生成自愈计划：/自愈 #编号"));
    }

    public BotReport incidentDetailReport(Long id) {
        OpsIncidentEntity incident = incidentRepository.findById(id)
                .orElseThrow(() -> BusinessException.of("事故不存在: " + id));
        List<OpsIncidentEventEntity> events = eventRepository.recent(id, 10);
        List<BotReport.Row> rows = new ArrayList<>();
        rows.add(BotReport.Row.of("编号", "#" + incident.getId()));
        rows.add(BotReport.Row.of("级别", incident.getSeverity()));
        rows.add(BotReport.Row.of("状态", incident.getStatus()));
        rows.add(BotReport.Row.of("首次发现", time(incident.getFirstSeenAt())));
        rows.add(BotReport.Row.of("最近信号", time(incident.getLastSeenAt())));
        rows.add(BotReport.Row.of("影响", nz(incident.getImpactSummary())));
        rows.add(BotReport.Row.of("指纹", nz(incident.getRootCauseCode())));
        List<BotReport.Block> blocks = new ArrayList<>();
        blocks.add(new BotReport.Block(null, List.of(
                BotReport.Col.left("项目"), BotReport.Col.left("值")), rows, null));
        if (!events.isEmpty()) {
            List<BotReport.Row> timeline = new ArrayList<>();
            for (OpsIncidentEventEntity event : events) {
                timeline.add(BotReport.Row.of(time(event.getCreatedAt()),
                        event.getEventType(), nz(event.getActor()), nz(event.getContent())));
            }
            blocks.add(new BotReport.Block("最近时间线", List.of(
                    BotReport.Col.left("时间"), BotReport.Col.left("类型"),
                    BotReport.Col.left("操作人"), BotReport.Col.left("内容")), timeline, null));
        }
        return BotReport.of("事故 #" + id + " · " + incident.getTitle(), blocks,
                List.of("生成计划：/自愈 #" + id));
    }

    public BotReport planReport(Long incidentId, String actor) {
        return planReport(createPlan(incidentId, actor));
    }

    public BotReport planReport(OpsActionPlanEntity plan) {
        List<String> lines = List.of(
                "计划：" + plan.getPlanCode(),
                "动作：" + actionLabel(plan.getActionType()),
                "目标：" + nz(plan.getTargetName()),
                "风险：" + plan.getRiskLevel(),
                "影响：" + nz(plan.getSummary()),
                "确认码：" + plan.getConfirmationCode(),
                "有效期：" + time(plan.getExpiresAt()),
                "执行：/确认自愈 " + plan.getConfirmationCode());
        return BotReport.of("自愈计划 #" + plan.getId(), List.of(BotReport.paragraph(null, lines)), List.of());
    }

    public BotReport planListReport(int limit) {
        List<OpsActionPlanEntity> plans = recentPlans(limit);
        List<BotReport.Row> rows = new ArrayList<>();
        for (OpsActionPlanEntity plan : plans) {
            rows.add(BotReport.Row.of(
                    "#" + plan.getId(),
                    plan.getPlanCode(),
                    actionLabel(plan.getActionType()),
                    nz(plan.getTargetName()),
                    plan.getRiskLevel(),
                    plan.getStatus(),
                    time(plan.getCreatedAt())));
        }
        List<BotReport.Block> blocks = new ArrayList<>();
        if (rows.isEmpty()) {
            blocks.add(BotReport.paragraph("自愈记录", List.of("还没有自愈计划。")));
        } else {
            blocks.add(new BotReport.Block(null, List.of(
                    BotReport.Col.left("编号"), BotReport.Col.left("计划码"),
                    BotReport.Col.left("动作"), BotReport.Col.left("目标"),
                    BotReport.Col.left("风险"), BotReport.Col.left("状态"),
                    BotReport.Col.left("创建")), rows, null));
        }
        return BotReport.of("自愈记录", blocks, List.of());
    }

    public BotReport executePlanReport(String code, String actor) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return BotReport.fromMarkdown("请提供确认码，例如：/确认自愈 A1B2C3D4");
        }
        OpsActionPlanEntity target = recentPlans(200).stream()
                .filter(plan -> normalized.equalsIgnoreCase(plan.getConfirmationCode()))
                .findFirst().orElse(null);
        if (target == null) {
            return BotReport.fromMarkdown("没有找到对应的自愈计划，或确认码已过期。");
        }
        ExecutionResult result = executePlan(target.getId(), normalized, actor);
        return BotReport.fromMarkdown((result.success() ? "✅ " : "❌ ") + result.message()
                + "\n计划：" + result.plan().getPlanCode()
                + "\n动作：" + actionLabel(result.plan().getActionType())
                + "\n状态：" + result.plan().getStatus());
    }

    private void upsertMonitoringSignal(OpsMonitoringService.MonitoringSignal signal, OffsetDateTime now) {
        OpsIncidentEntity existing = incidentRepository.findActiveByFingerprint(signal.fingerprint()).orElse(null);
        if (existing == null) {
            OpsIncidentEntity incident = new OpsIncidentEntity();
            incident.setFingerprint(signal.fingerprint());
            incident.setTitle(signal.title());
            incident.setSeverity(signal.severity());
            incident.setStatus("ACTIVE");
            incident.setScopeType(signal.scopeType());
            incident.setScopeId(signal.scopeId());
            incident.setRootCauseCode(signal.type() + " / " + signal.scopeId());
            incident.setImpactSummary(signal.impactSummary());
            incident.setEvidenceJson(signal.evidenceJson());
            incident.setFirstSeenAt(now);
            incident.setLastSeenAt(now);
            incidentRepository.insert(incident);
            eventRepository.append(incident.getId(), "MONITORING_INCIDENT_OPENED", "ops-incident-refresh",
                    "发现监控事故：" + signal.title() + "，" + signal.impactSummary());
            notifier.notifyNewIncident(incident);
            return;
        }
        incidentRepository.updateObserved(existing.getId(), signal.title(), signal.severity(),
                signal.impactSummary(), signal.evidenceJson(), now, now);
        eventRepository.append(existing.getId(), "MONITORING_SIGNAL_UPDATED", "ops-incident-refresh",
                signal.impactSummary());
    }

    private void upsertIncident(ErrorFingerprint fingerprint, OffsetDateTime now) {
        String key = fingerprintKey(fingerprint);
        String title = title(fingerprint);
        String severity = severity(fingerprint);
        String impact = "近 30 分钟 " + fingerprint.count() + " 次，影响 "
                + fingerprint.affectedUsers() + " 用户 / " + fingerprint.affectedAccounts() + " 账号";
        String evidenceJson = json(evidenceMap(fingerprint));
        OpsIncidentEntity existing = incidentRepository.findActiveByFingerprint(key).orElse(null);
        if (existing == null) {
            OpsIncidentEntity incident = new OpsIncidentEntity();
            incident.setFingerprint(key);
            incident.setTitle(title);
            incident.setSeverity(severity);
            incident.setStatus("ACTIVE");
            incident.setScopeType("ERROR_FINGERPRINT");
            incident.setScopeId(key);
            incident.setRootCauseCode(fingerprintLabel(fingerprint));
            incident.setImpactSummary(impact);
            incident.setEvidenceJson(evidenceJson);
            incident.setFirstSeenAt(fingerprint.firstSeen() == null ? now : fingerprint.firstSeen());
            incident.setLastSeenAt(fingerprint.lastSeen() == null ? now : fingerprint.lastSeen());
            incidentRepository.insert(incident);
            eventRepository.append(incident.getId(), "INCIDENT_OPENED", "ops-incident-refresh",
                    "发现故障：" + title + "，" + impact);
            notifier.notifyNewIncident(incident);
            return;
        }
        incidentRepository.updateObserved(existing.getId(), title, severity, impact, evidenceJson,
                fingerprint.lastSeen() == null ? now : fingerprint.lastSeen(), now);
        eventRepository.append(existing.getId(), "SIGNAL_UPDATED", "ops-incident-refresh", impact);
    }

    private Map<String, Object> executeAction(OpsActionPlanEntity plan) {
        if ("OBSERVE".equals(plan.getActionType())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "OBSERVE");
            result.put("verified", true);
            result.put("message", "当前故障不适合自动修改，已保留观察计划。");
            return result;
        }
        if (plan.getTargetId() == null) {
            throw BusinessException.of("计划没有明确的目标账号");
        }
        Connection connection = connection();
        AccountState before = actionClient.fetchAccount(connection.baseUrl(), connection.adminKey(), plan.getTargetId());
        AccountState after = switch (plan.getActionType()) {
            case "CLEAR_RATE_LIMIT" -> actionClient.clearRateLimit(connection.baseUrl(), connection.adminKey(), plan.getTargetId());
            case "CLEAR_ERROR" -> actionClient.clearError(connection.baseUrl(), connection.adminKey(), plan.getTargetId());
            case "REFRESH_ACCOUNT" -> actionClient.refresh(connection.baseUrl(), connection.adminKey(), plan.getTargetId());
            case "RECOVER_ACCOUNT" -> actionClient.recoverState(connection.baseUrl(), connection.adminKey(), plan.getTargetId());
            case "PAUSE_ACCOUNT" -> actionClient.setSchedulable(connection.baseUrl(), connection.adminKey(), plan.getTargetId(), false);
            default -> throw BusinessException.of("不支持的自愈动作：" + plan.getActionType());
        };
        boolean verified = verify(plan.getActionType(), after);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", plan.getActionType());
        result.put("verified", verified);
        result.put("before", stateMap(before));
        result.put("after", stateMap(after));
        return result;
    }

    private boolean verify(String actionType, AccountState after) {
        if (after == null) {
            return false;
        }
        return switch (actionType) {
            case "CLEAR_RATE_LIMIT" -> after.rateLimitResetAt() == null
                    || !after.rateLimitResetAt().isAfter(OffsetDateTime.now(ZONE));
            case "CLEAR_ERROR" -> after.errorMessage() == null || after.errorMessage().isBlank();
            case "PAUSE_ACCOUNT" -> Boolean.FALSE.equals(after.schedulable());
            default -> true;
        };
    }

    private List<ErrorRow> matchingRows(Evidence evidence, OffsetDateTime now) {
        try {
            return errorReader.recent(now.minus(LOOKBACK), now, ErrorFilter.everything(null), 300)
                    .stream()
                    .filter(row -> eq(row.phase(), evidence.phase()))
                    .filter(row -> eq(row.type(), evidence.type()))
                    .filter(row -> row.statusCode() == evidence.statusCode())
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private Map<Long, AccountGroup> affectedAccounts(List<ErrorRow> rows) {
        Map<Long, AccountGroup> result = new LinkedHashMap<>();
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (ErrorRow row : rows) {
            if (row.accountId() == null) {
                continue;
            }
            counts.merge(row.accountId(), 1L, Long::sum);
            result.putIfAbsent(row.accountId(), new AccountGroup(
                    row.accountId(), nz(row.accountName()), nz(row.platform()),
                    counts.get(row.accountId()), 0, 0, 0, row.createdAt()));
        }
        return result;
    }

    private boolean activeRateLimit(List<ErrorRow> rows, Long targetId, OffsetDateTime now) {
        if (targetId == null) {
            return true;
        }
        return rows.stream()
                .filter(row -> targetId.equals(row.accountId()))
                .map(ErrorRow::accountRateLimitResetAt)
                .anyMatch(resetAt -> resetAt != null && resetAt.isAfter(now));
    }

    private String actionType(Evidence evidence, int accountCount) {
        if (accountCount != 1) {
            return "OBSERVE";
        }
        int status = evidence.statusCode();
        if (status == 429) {
            return "CLEAR_RATE_LIMIT";
        }
        if (status == 401 || status == 403 || "account_auth".equalsIgnoreCase(evidence.phase())) {
            return "REFRESH_ACCOUNT";
        }
        if (status >= 500 || "upstream".equalsIgnoreCase(evidence.phase())
                || "network".equalsIgnoreCase(evidence.phase())) {
            return "CLEAR_ERROR";
        }
        return "OBSERVE";
    }

    private String riskLevel(String actionType) {
        return switch (actionType) {
            case "REFRESH_ACCOUNT", "PAUSE_ACCOUNT", "RECOVER_ACCOUNT" -> "MEDIUM";
            case "CLEAR_RATE_LIMIT", "CLEAR_ERROR", "OBSERVE" -> "LOW";
            default -> "HIGH";
        };
    }

    private String summary(OpsIncidentEntity incident, String actionType,
                           String targetName, int accountCount) {
        String target = targetName == null ? "当前故障" : "账号 " + targetName;
        if ("OBSERVE".equals(actionType)) {
            return "事故 #" + incident.getId() + " 影响 " + accountCount
                    + " 个账号，先观察，不自动修改 Sub2API。";
        }
        return "事故 #" + incident.getId() + " → " + actionLabel(actionType) + "：" + target;
    }

    private String expectedEffect(String actionType) {
        return switch (actionType) {
            case "CLEAR_RATE_LIMIT" -> "清理已过期的限流状态，使账号重新参与调度。";
            case "CLEAR_ERROR" -> "清理账号错误状态，减少错误状态对调度的影响。";
            case "REFRESH_ACCOUNT" -> "刷新 OAuth 凭证；若上游仍返回认证失败，需要人工重新授权。";
            case "RECOVER_ACCOUNT" -> "恢复账号运行状态。";
            case "PAUSE_ACCOUNT" -> "暂停账号调度，避免继续影响请求。";
            default -> "仅保留事故观察，不修改 Sub2API。";
        };
    }

    private String rollbackNote(String actionType) {
        return switch (actionType) {
            case "PAUSE_ACCOUNT" -> "可通过 Sub2API 重新设置 schedulable=true 恢复。";
            case "OBSERVE" -> "无写操作。";
            default -> "该动作清理的是瞬时状态，无直接回滚值；失败时保留现场并人工处理。";
        };
    }

    private String beforeSnapshot(List<ErrorRow> rows, Long targetId) {
        if (targetId == null) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("accountId", "multiple-or-unknown");
            map.put("errorRows", rows.size());
            return json(map);
        }
        ErrorRow row = rows.stream().filter(r -> targetId.equals(r.accountId())).findFirst().orElse(null);
        if (row == null) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("accountId", targetId);
            return json(map);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("accountId", targetId);
        map.put("accountName", row.accountName());
        map.put("accountStatus", row.accountStatus());
        map.put("schedulable", row.accountSchedulable());
        map.put("errorMessage", row.accountErrorMessage());
        map.put("rateLimitResetAt", row.accountRateLimitResetAt() == null ? null : row.accountRateLimitResetAt().toString());
        map.put("overloadUntil", row.accountOverloadUntil() == null ? null : row.accountOverloadUntil().toString());
        map.put("tempUnschedulableUntil", row.accountTempUnschedulableUntil() == null ? null : row.accountTempUnschedulableUntil().toString());
        return json(map);
    }

    private Map<String, Object> stateMap(AccountState state) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (state == null) {
            return map;
        }
        map.put("id", state.id());
        map.put("name", state.name());
        map.put("platform", state.platform());
        map.put("status", state.status());
        map.put("schedulable", state.schedulable());
        map.put("errorMessage", state.errorMessage());
        map.put("rateLimitResetAt", state.rateLimitResetAt() == null ? null : state.rateLimitResetAt().toString());
        map.put("overloadUntil", state.overloadUntil() == null ? null : state.overloadUntil().toString());
        map.put("tempUnschedulableUntil", state.tempUnschedulableUntil() == null ? null : state.tempUnschedulableUntil().toString());
        return map;
    }

    private Evidence evidence(OpsIncidentEntity incident) {
        try {
            JsonNode node = objectMapper.readTree(incident.getEvidenceJson());
            return new Evidence(
                    node.path("phase").asText(null),
                    node.path("type").asText(null),
                    node.path("statusCode").asInt(0),
                    node.path("providerErrorCode").asText(null),
                    node.path("count").asLong(0));
        } catch (Exception ex) {
            return new Evidence(null, null, 0, null, 0);
        }
    }

    private Map<String, Object> evidenceMap(ErrorFingerprint fingerprint) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("phase", fingerprint.phase());
        map.put("type", fingerprint.type());
        map.put("statusCode", fingerprint.statusCode());
        map.put("providerErrorCode", fingerprint.providerErrorCode());
        map.put("count", fingerprint.count());
        map.put("affectedUsers", fingerprint.affectedUsers());
        map.put("affectedAccounts", fingerprint.affectedAccounts());
        map.put("firstSeen", fingerprint.firstSeen() == null ? null : fingerprint.firstSeen().toString());
        map.put("lastSeen", fingerprint.lastSeen() == null ? null : fingerprint.lastSeen().toString());
        map.put("sampleMessage", fingerprint.sampleMessage());
        return map;
    }

    private String title(ErrorFingerprint fingerprint) {
        if (fingerprint.statusCode() == 429) {
            return "账号限流（429）";
        }
        if (fingerprint.statusCode() == 401 || fingerprint.statusCode() == 403) {
            return "账号认证失败（" + fingerprint.statusCode() + "）";
        }
        if (fingerprint.statusCode() >= 500) {
            return "上游服务错误（" + fingerprint.statusCode() + "）";
        }
        String phase = fingerprint.phase() == null ? "未知阶段" : fingerprint.phase();
        String type = fingerprint.type() == null ? "未知类型" : fingerprint.type();
        return phase + " / " + type;
    }

    private String severity(ErrorFingerprint fingerprint) {
        if (fingerprint.statusCode() == 401 || fingerprint.statusCode() == 403
                || fingerprint.statusCode() == 429 || fingerprint.statusCode() == 529) {
            return "P1";
        }
        if (fingerprint.statusCode() >= 500
                || "account_auth".equalsIgnoreCase(fingerprint.phase())
                || "upstream".equalsIgnoreCase(fingerprint.phase())
                || "network".equalsIgnoreCase(fingerprint.phase())) {
            return "P1";
        }
        return "P2";
    }

    private String fingerprintLabel(ErrorFingerprint fingerprint) {
        return nz(fingerprint.phase()) + " / " + nz(fingerprint.type()) + " / "
                + fingerprint.statusCode() + " / " + nz(fingerprint.providerErrorCode());
    }

    private String fingerprintKey(ErrorFingerprint fingerprint) {
        String raw = nz(fingerprint.phase()) + "|" + nz(fingerprint.type()) + "|"
                + fingerprint.statusCode() + "|" + nz(fingerprint.providerErrorCode());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "err:" + HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception ex) {
            return "err:" + Integer.toHexString(raw.hashCode());
        }
    }

    private Connection connection() {
        PlatformEntity platform = platformRepository.findAll().stream()
                .filter(item -> Boolean.TRUE.equals(item.getPoolMonitoringEnabled()))
                .filter(item -> Boolean.TRUE.equals(item.getStatus()))
                .findFirst().orElse(null);
        if (platform == null) {
            throw BusinessException.of("没有找到可用的 Sub2API 号池监控源");
        }
        String adminKey = adminKeyResolver.resolve(platform).orElse(null);
        if (adminKey == null || adminKey.isBlank()) {
            throw BusinessException.of("Sub2API 管理员密钥未配置，不能执行自愈动作");
        }
        return new Connection(platform.getUrl(), adminKey);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String actionLabel(String actionType) {
        return switch (actionType == null ? "" : actionType) {
            case "CLEAR_RATE_LIMIT" -> "清理账号限流";
            case "CLEAR_ERROR" -> "清理账号错误";
            case "REFRESH_ACCOUNT" -> "刷新账号凭证";
            case "RECOVER_ACCOUNT" -> "恢复账号状态";
            case "PAUSE_ACCOUNT" -> "暂停账号调度";
            default -> "仅观察";
        };
    }

    private static String time(OffsetDateTime value) {
        return value == null ? "—" : value.atZoneSameInstant(ZONE).format(TIME);
    }

    private static String nz(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static boolean eq(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private record Evidence(String phase, String type, int statusCode,
                            String providerErrorCode, long count) {
    }

    private record Connection(String baseUrl, String adminKey) {
    }

    public record ExecutionResult(boolean success, String message, OpsActionPlanEntity plan) {
    }
}
