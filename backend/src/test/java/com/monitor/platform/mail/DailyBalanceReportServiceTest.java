package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.AccountUsageDashboardSnapshotRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.AccountUsageDashboardSnapshotEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日余额消耗报表测试：按账号计算累计消耗差值并发送邮件。
 */
class DailyBalanceReportServiceTest {

    private final MailService mailService = mock(MailService.class);
    private final MailRecipientRepository mailRecipientRepository = mock(MailRecipientRepository.class);
    private final SmtpConfigProvider smtpConfigProvider = mock(SmtpConfigProvider.class);
    private final AccountUsageDashboardSnapshotRepository usageDashboardRepository =
            mock(AccountUsageDashboardSnapshotRepository.class);
    private DailyBalanceReportService service;

    @BeforeEach
    void setUp() {
        PlatformRepository platformRepository = mock(PlatformRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);

        service = new DailyBalanceReportService(
                platformRepository, accountRepository, usageDashboardRepository,
                mailRecipientRepository, smtpConfigProvider, mailService);

        PlatformEntity platform = new PlatformEntity();
        platform.setId(1);
        platform.setPlatformName("测试平台");
        platform.setPlatformType("newapi");
        platform.setStatus(true);

        AccountEntity account = new AccountEntity();
        account.setId(7);
        account.setPlatformId(1);
        account.setDisplayName("账号A");
        account.setStatus(true);

        MailRecipientEntity recipient = new MailRecipientEntity();
        recipient.setEmail("ops@example.com");

        SmtpConfig smtpConfig = new SmtpConfig(true, "smtp.example.com", 587, "user", "pw",
                "monitor@example.com", "Monitor", false);

        when(platformRepository.findEnabled()).thenReturn(List.of(platform));
        when(accountRepository.findEnabledByPlatformId(1)).thenReturn(List.of(account));
        when(mailRecipientRepository.findByScene(MailScene.DAILY_REPORT)).thenReturn(List.of(recipient));
        when(smtpConfigProvider.load()).thenReturn(smtpConfig);
    }

    @Test
    void sendsReportWithDailyConsumption() {
        AccountUsageDashboardSnapshotEntity start = snapshot(new BigDecimal("10.0000"), new BigDecimal("45.50"));
        AccountUsageDashboardSnapshotEntity end = snapshot(new BigDecimal("13.0000"), new BigDecimal("42.50"));
        when(usageDashboardRepository.findByAccounts(anyList(), any(), any())).thenReturn(List.of(start, end));
        when(usageDashboardRepository.findLatestByAccounts(anyList())).thenReturn(List.of(end));

        service.sendDailyReport();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(any(SmtpConfig.class), anyList(), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("测试平台")
                .contains("账号A")
                .contains("$3.0000")
                .contains("$42.50");
    }

    @Test
    void skipsWhenNoRecipients() {
        when(mailRecipientRepository.findByScene(MailScene.DAILY_REPORT)).thenReturn(List.of());

        service.sendDailyReport();

        verify(mailService, never()).send(any(SmtpConfig.class), anyList(), anyString(), anyString());
    }

    private AccountUsageDashboardSnapshotEntity snapshot(BigDecimal totalActualCost, BigDecimal balance) {
        AccountUsageDashboardSnapshotEntity snapshot = new AccountUsageDashboardSnapshotEntity();
        snapshot.setAccountId(7);
        snapshot.setPlatformType("newapi");
        snapshot.setTotalActualCost(totalActualCost);
        snapshot.setBalance(balance);
        return snapshot;
    }
}