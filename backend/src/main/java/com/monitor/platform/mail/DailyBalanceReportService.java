package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.AccountUsageDashboardSnapshotRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.AccountUsageDashboardSnapshotEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 每日余额消耗报表服务。
 *
 * <p>每天凌晨发送前一天的余额消耗报表：按平台分组，列出该平台下每个启用账号的
 * 昨日消耗与当前余额，并给出平台合计。收件人沿用页面维护的余额提醒收件人。</p>
 *
 * <p>消耗口径：用量看板快照的累计实际消耗（{@code total_actual_cost}，为空时取
 * {@code total_cost}）在当天首次与最后一次的差值；New API 没有当日字段，Sub2API 的
 * 累计值同样适用该算法。差值为负（额度重置/换套餐）时视为无法计算，展示为「—」。</p>
 *
 * <p>该方法不会抛出异常：发信失败只记录日志，避免影响定时任务状态。</p>
 */
@Service
public class DailyBalanceReportService {

    private static final Logger log = LoggerFactory.getLogger(DailyBalanceReportService.class);
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");

    private final PlatformRepository platformRepository;
    private final AccountRepository accountRepository;
    private final AccountUsageDashboardSnapshotRepository usageDashboardRepository;
    private final MailRecipientRepository mailRecipientRepository;
    private final SmtpConfigProvider smtpConfigProvider;
    private final MailService mailService;

    public DailyBalanceReportService(PlatformRepository platformRepository,
                                     AccountRepository accountRepository,
                                     AccountUsageDashboardSnapshotRepository usageDashboardRepository,
                                     MailRecipientRepository mailRecipientRepository,
                                     SmtpConfigProvider smtpConfigProvider,
                                     MailService mailService) {
        this.platformRepository = platformRepository;
        this.accountRepository = accountRepository;
        this.usageDashboardRepository = usageDashboardRepository;
        this.mailRecipientRepository = mailRecipientRepository;
        this.smtpConfigProvider = smtpConfigProvider;
        this.mailService = mailService;
    }

    /**
     * 生成并发送前一天的余额消耗报表。
     */
    public void sendDailyReport() {
        try {
            LocalDate reportDate = LocalDate.now(REPORT_ZONE).minusDays(1);
            OffsetDateTime from = reportDate.atStartOfDay(REPORT_ZONE).toOffsetDateTime();
            OffsetDateTime to = reportDate.plusDays(1).atStartOfDay(REPORT_ZONE).toOffsetDateTime();

            List<PlatformReport> reports = buildReports(from, to);
            if (reports.isEmpty()) {
                log.info("没有可统计的平台或账号，跳过余额日报: date={}", reportDate);
                return;
            }

            List<MailRecipientEntity> recipients = mailRecipientRepository.findByScene(MailScene.DAILY_REPORT);
            if (recipients.isEmpty()) {
                log.info("未配置收件人，跳过余额日报: date={}", reportDate);
                return;
            }

            SmtpConfig smtpConfig = smtpConfigProvider.load();
            if (!smtpConfig.isConfigured()) {
                log.warn("SMTP 未配置，跳过余额日报: date={}", reportDate);
                return;
            }

            List<String> toAddresses = recipients.stream().map(MailRecipientEntity::getEmail).toList();
            mailService.send(smtpConfig, toAddresses, buildSubject(reportDate), buildBody(reportDate, reports));
            log.info("余额日报已发送: date={}, recipients={}, platformCount={}",
                    reportDate, toAddresses, reports.size());
        } catch (Exception ex) {
            log.error("余额日报发送失败", ex);
        }
    }

    /**
     * 汇总各启用平台及其启用账号的昨日消耗与当前余额。
     */
    private List<PlatformReport> buildReports(OffsetDateTime from, OffsetDateTime to) {
        List<PlatformEntity> platforms = platformRepository.findEnabled();
        Map<Integer, List<AccountEntity>> accountsByPlatform = new LinkedHashMap<>();
        List<Integer> accountIds = new ArrayList<>();
        for (PlatformEntity platform : platforms) {
            List<AccountEntity> accounts = accountRepository.findEnabledByPlatformId(platform.getId());
            accountsByPlatform.put(platform.getId(), accounts);
            for (AccountEntity account : accounts) {
                accountIds.add(account.getId());
            }
        }
        if (accountIds.isEmpty()) {
            return List.of();
        }

        Map<Integer, BigDecimal> consumption = calculateConsumption(accountIds, from, to);
        Map<Integer, BigDecimal> balances = usageDashboardRepository.findLatestByAccounts(accountIds).stream()
                .filter(snapshot -> snapshot.getAccountId() != null)
                .collect(Collectors.toMap(
                        AccountUsageDashboardSnapshotEntity::getAccountId,
                        AccountUsageDashboardSnapshotEntity::getBalance,
                        (left, right) -> left));

        List<PlatformReport> reports = new ArrayList<>();
        for (PlatformEntity platform : platforms) {
            List<AccountEntity> platformAccounts = accountsByPlatform.getOrDefault(platform.getId(), List.of());
            if (platformAccounts.isEmpty()) {
                // 平台下没有启用账号，报表无内容，跳过
                continue;
            }
            List<AccountReport> rows = new ArrayList<>();
            BigDecimal subtotal = BigDecimal.ZERO;
            boolean hasValue = false;
            for (AccountEntity account : platformAccounts) {
                BigDecimal value = consumption.get(account.getId());
                if (value != null) {
                    subtotal = subtotal.add(value);
                    hasValue = true;
                }
                rows.add(new AccountReport(displayName(account), value, balances.get(account.getId())));
            }
            reports.add(new PlatformReport(platform.getPlatformName(), rows, hasValue ? subtotal : null));
        }
        return reports;
    }

    /**
     * 按账号计算当天累计实际消耗的差值。
     */
    private Map<Integer, BigDecimal> calculateConsumption(List<Integer> accountIds,
                                                          OffsetDateTime from,
                                                          OffsetDateTime to) {
        List<AccountUsageDashboardSnapshotEntity> snapshots =
                usageDashboardRepository.findByAccounts(accountIds, from, to);
        Map<Integer, BigDecimal> first = new HashMap<>();
        Map<Integer, BigDecimal> last = new HashMap<>();
        for (AccountUsageDashboardSnapshotEntity snapshot : snapshots) {
            BigDecimal cost = costOf(snapshot);
            if (snapshot.getAccountId() == null || cost == null) {
                continue;
            }
            first.putIfAbsent(snapshot.getAccountId(), cost);
            last.put(snapshot.getAccountId(), cost);
        }

        Map<Integer, BigDecimal> result = new HashMap<>();
        for (Map.Entry<Integer, BigDecimal> entry : last.entrySet()) {
            BigDecimal start = first.get(entry.getKey());
            if (start == null) {
                continue;
            }
            BigDecimal delta = entry.getValue().subtract(start);
            if (delta.signum() < 0) {
                // 额度重置或换套餐导致累计值回退，跳过避免误导
                continue;
            }
            result.put(entry.getKey(), delta);
        }
        return result;
    }

    private BigDecimal costOf(AccountUsageDashboardSnapshotEntity snapshot) {
        return snapshot.getTotalActualCost() != null ? snapshot.getTotalActualCost() : snapshot.getTotalCost();
    }

    private String displayName(AccountEntity account) {
        if (account.getDisplayName() != null && !account.getDisplayName().isBlank()) {
            return account.getDisplayName();
        }
        if (account.getUsername() != null && !account.getUsername().isBlank()) {
            return account.getUsername();
        }
        return "账号 " + account.getId();
    }

    private String buildSubject(LocalDate reportDate) {
        return "[Monitor] 昨日余额消耗报表（" + reportDate + "）";
    }

    private String buildBody(LocalDate reportDate, List<PlatformReport> reports) {
        StringBuilder html = new StringBuilder();
        html.append("<p>").append(reportDate).append(" 各平台、各账号的余额消耗如下（单位：USD）。</p>");
        html.append("<table cellpadding=\"8\" cellspacing=\"0\" border=\"1\" ")
                .append("style=\"border-collapse:collapse;font-size:13px\">");
        html.append("<tr style=\"background:#f5f5f5\">")
                .append("<th align=\"left\">平台</th>")
                .append("<th align=\"left\">账号</th>")
                .append("<th align=\"right\">昨日消耗</th>")
                .append("<th align=\"right\">当前余额</th>")
                .append("</tr>");
        for (PlatformReport platform : reports) {
            for (AccountReport account : platform.accounts()) {
                html.append("<tr>")
                        .append("<td>").append(escape(platform.platformName())).append("</td>")
                        .append("<td>").append(escape(account.accountName())).append("</td>")
                        .append("<td align=\"right\">").append(money(account.consumption(), 4)).append("</td>")
                        .append("<td align=\"right\">").append(money(account.balance(), 2)).append("</td>")
                        .append("</tr>");
            }
            html.append("<tr style=\"background:#fafafa;font-weight:600\">")
                    .append("<td colspan=\"2\">").append(escape(platform.platformName())).append(" 合计</td>")
                    .append("<td align=\"right\">").append(money(platform.total(), 4)).append("</td>")
                    .append("<td></td>")
                    .append("</tr>");
        }
        html.append("</table>");
        html.append("<p style=\"color:#888;font-size:12px\">本邮件由 Monitor 每日余额消耗报表任务自动发送。</p>");
        return html.toString();
    }

    private String money(BigDecimal value, int scale) {
        if (value == null) {
            return "—";
        }
        return "$" + value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }

    /** 单个账号的昨日消耗与当前余额。 */
    private record AccountReport(String accountName, BigDecimal consumption, BigDecimal balance) {
    }

    /** 单个平台的报表数据。 */
    private record PlatformReport(String platformName, List<AccountReport> accounts, BigDecimal total) {
    }
}