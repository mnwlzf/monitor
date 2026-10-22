package com.monitor.platform.bot.key;

import com.monitor.platform.adapter.sub2api.Sub2ApiAdapter;
import com.monitor.platform.adapter.sub2api.model.Sub2GroupsResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2ProfileResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2KeysResponse;
import com.monitor.platform.bot.identity.Sub2ApiUserCache;
import com.monitor.platform.collector.application.AccountCredentialService;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理员建密钥工具：五要素缺一不可、分组按「厂商 + 名称」匹配、成功后回完整密钥与限制。
 */
class Sub2KeyToolsTest {

    private final PlatformRepository platformRepository = mock(PlatformRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AccountCredentialService credentialService = mock(AccountCredentialService.class);
    private final Sub2ApiAdapter sub2ApiAdapter = mock(Sub2ApiAdapter.class);
    private final Sub2ApiUserCache userCache = mock(Sub2ApiUserCache.class);

    private Sub2KeyTools tools;

    @BeforeEach
    void setUp() {
        tools = new Sub2KeyTools(platformRepository, accountRepository, credentialService, userCache, sub2ApiAdapter);

        PlatformEntity platform = new PlatformEntity();
        platform.setId(1);
        platform.setPlatformName("自己");
        platform.setUrl("https://sub2.example.com");
        platform.setPoolMonitoringEnabled(true);

        AccountEntity account = new AccountEntity();
        account.setId(7);
        account.setEmail("admin@example.com");
        account.setAuthType(AccountCredentialService.PASSWORD);

        when(platformRepository.findEnabled()).thenReturn(List.of(platform));
        when(accountRepository.findEnabledByPlatformId(1)).thenReturn(List.of(account));
        when(credentialService.resolvePassword(7)).thenReturn("pw");
        when(userCache.isAdmin("admin@example.com")).thenReturn(true);
        Sub2ProfileResponse.UserProfile profileData = mock(Sub2ProfileResponse.UserProfile.class);
        when(profileData.role()).thenReturn("admin");
        Sub2ProfileResponse profile = mock(Sub2ProfileResponse.class);
        when(profile.data()).thenReturn(profileData);
        when(sub2ApiAdapter.fetchProfile("https://sub2.example.com", "admin@example.com", "pw"))
                .thenReturn(profile);
    }

    @Test
    void shouldAskForAllMissingParamsWithoutCallingSub2Api() {
        String reply = tools.createSub2ApiKey(null, " ", "", null, null, null);

        assertThat(reply)
                .contains("还缺少以下参数")
                .contains("密钥厂商")
                .contains("名称")
                .contains("分组")
                .contains("额度限制")
                .contains("有效期");
        verifyNoInteractions(sub2ApiAdapter);
    }

    @Test
    void shouldCreateKeyAndReturnFullKeyWithLimits() {
        Sub2GroupsResponse.Group group = group(32L, "pro", "openai", 0.3);
        Sub2KeysResponse.KeyItem created = createdKey();
        when(sub2ApiAdapter.fetchAvailableGroups("https://sub2.example.com", "admin@example.com", "pw"))
                .thenReturn(groups(group));
        when(sub2ApiAdapter.createKey("https://sub2.example.com", "admin@example.com", "pw",
                "my-key", 32L, 10.0, 30, null))
                .thenReturn(created);

        String reply = tools.createSub2ApiKey("openai", "my-key", "pro", 10.0, 30, null);

        assertThat(reply)
                .contains("密钥创建成功")
                .contains("厂商：openai")
                .contains("名称：my-key")
                .contains("分组：pro")
                .contains("额度限制：$10")
                .contains("有效期：30 天")
                .contains("密钥：sk-created-123");
        verify(sub2ApiAdapter).createKey("https://sub2.example.com", "admin@example.com", "pw",
                "my-key", 32L, 10.0, 30, null);
    }

    @Test
    void shouldSelectSub2AdminAccountNotFirstEnabledAccount() {
        AccountEntity monitorAccount = new AccountEntity();
        monitorAccount.setId(8);
        monitorAccount.setEmail("monitor-admin@example.com");
        monitorAccount.setAuthType(AccountCredentialService.PASSWORD);
        AccountEntity sub2Admin = new AccountEntity();
        sub2Admin.setId(9);
        sub2Admin.setEmail("system-admin@example.com");
        sub2Admin.setAuthType(AccountCredentialService.PASSWORD);

        when(accountRepository.findEnabledByPlatformId(1))
                .thenReturn(List.of(monitorAccount, sub2Admin));
        when(credentialService.resolvePassword(8)).thenReturn("monitor-pw");
        when(credentialService.resolvePassword(9)).thenReturn("admin-pw");
        when(userCache.isAdmin("monitor-admin@example.com")).thenReturn(false);
        when(userCache.isAdmin("system-admin@example.com")).thenReturn(true);
        Sub2ProfileResponse monitorProfile = profile("user");
        Sub2ProfileResponse adminProfile = profile("admin");
        when(sub2ApiAdapter.fetchProfile("https://sub2.example.com",
                "monitor-admin@example.com", "monitor-pw"))
                .thenReturn(monitorProfile);
        when(sub2ApiAdapter.fetchProfile("https://sub2.example.com",
                "system-admin@example.com", "admin-pw"))
                .thenReturn(adminProfile);

        Sub2GroupsResponse.Group group = group(32L, "pro", "openai", 0.3);
        when(sub2ApiAdapter.fetchAvailableGroups("https://sub2.example.com",
                "system-admin@example.com", "admin-pw")).thenReturn(groups(group));
        Sub2KeysResponse.KeyItem created = createdKey();
        when(sub2ApiAdapter.createKey("https://sub2.example.com", "system-admin@example.com",
                "admin-pw", "my-key", 32L, 10.0, 30, null)).thenReturn(created);

        String reply = tools.createSub2ApiKey("openai", "my-key", "pro", 10.0, 30, null);

        assertThat(reply).contains("密钥创建成功");
        verify(sub2ApiAdapter).createKey("https://sub2.example.com", "system-admin@example.com",
                "admin-pw", "my-key", 32L, 10.0, 30, null);
    }

    @Test
    void shouldShowActualVendorWhenGroupNameExistsUnderAnotherVendor() {
        Sub2GroupsResponse.Group group = group(32L, "pro", "anthropic", 0.3);
        when(sub2ApiAdapter.fetchAvailableGroups(anyString(), anyString(), any()))
                .thenReturn(groups(group));

        String reply = tools.createSub2ApiKey("openai", "my-key", "pro", 0.0, 0, null);

        assertThat(reply)
                .contains("没找到厂商「openai」")
                .contains("同名分组实际厂商：anthropic");
        verify(sub2ApiAdapter, never()).createKey(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldSupportUnlimitedQuotaAndPermanentExpiry() {
        Sub2GroupsResponse.Group group = group(32L, "pro", "openai", 0.3);
        Sub2KeysResponse.KeyItem created = createdKey();
        when(sub2ApiAdapter.fetchAvailableGroups(anyString(), anyString(), any()))
                .thenReturn(groups(group));
        when(sub2ApiAdapter.createKey(anyString(), anyString(), any(), anyString(), anyLong(),
                anyDouble(), any(), any()))
                .thenReturn(created);

        String reply = tools.createSub2ApiKey("openai", "my-key", "pro", 0.0, 0, null);

        assertThat(reply)
                .contains("额度限制：不限（$0）")
                .contains("有效期：永久");
        verify(sub2ApiAdapter).createKey("https://sub2.example.com", "admin@example.com", "pw",
                "my-key", 32L, 0.0, null, null);
    }

    @Test
    void shouldCreateKeyWithExactExpiryTime() {
        Sub2GroupsResponse.Group group = group(32L, "pro", "openai", 0.3);
        Sub2KeysResponse.KeyItem created = createdKey();
        OffsetDateTime expected = OffsetDateTime.parse("2099-10-22T10:11:12+08:00");
        when(sub2ApiAdapter.fetchAvailableGroups(anyString(), anyString(), any()))
                .thenReturn(groups(group));
        when(sub2ApiAdapter.createKey("https://sub2.example.com", "admin@example.com", "pw",
                "my-key", 32L, 10.0, null, expected)).thenReturn(created);

        String reply = tools.createSub2ApiKey("openai", "my-key", "pro", 10.0, null,
                "2099-10-22 10:11:12");

        assertThat(reply)
                .contains("有效期：2099-10-22 10:11:12（Asia/Shanghai）")
                .contains("密钥：sk-created-123");
        verify(sub2ApiAdapter).createKey("https://sub2.example.com", "admin@example.com", "pw",
                "my-key", 32L, 10.0, null, expected);
    }

    @Test
    void shouldRejectInvalidExpiryFormat() {
        String reply = tools.createSub2ApiKey("openai", "my-key", "pro", 10.0, null,
                "2099/10/22 10:11:12");

        assertThat(reply).contains("有效期格式错误", "yyyy-MM-dd HH:mm:ss");
        verifyNoInteractions(sub2ApiAdapter);
    }

    @Test
    void shouldRejectBothExpiryModes() {
        String reply = tools.createSub2ApiKey("openai", "my-key", "pro", 10.0, 30,
                "2099-10-22 10:11:12");

        assertThat(reply).contains("只能二选一");
        verifyNoInteractions(sub2ApiAdapter);
    }

    @Test
    void shouldRejectPastExpiryTime() {
        String reply = tools.createSub2ApiKey("openai", "my-key", "pro", 10.0, null,
                "2000-01-01 00:00:00");

        assertThat(reply).contains("必须晚于当前时间");
        verifyNoInteractions(sub2ApiAdapter);
    }

    @Test
    void shouldRejectNegativeQuotaOrExpiry() {
        assertThat(tools.createSub2ApiKey("openai", "my-key", "pro", -1.0, 30, null))
                .contains("额度限制不能为负数");
        assertThat(tools.createSub2ApiKey("openai", "my-key", "pro", 1.0, -1, null))
                .contains("有效期天数不能为负数");
        verifyNoInteractions(sub2ApiAdapter);
    }

    private static Sub2ProfileResponse profile(String role) {
        Sub2ProfileResponse.UserProfile data = mock(Sub2ProfileResponse.UserProfile.class);
        when(data.role()).thenReturn(role);
        Sub2ProfileResponse profile = mock(Sub2ProfileResponse.class);
        when(profile.data()).thenReturn(data);
        return profile;
    }

    private static Sub2GroupsResponse groups(Sub2GroupsResponse.Group... groups) {
        return new Sub2GroupsResponse(0, "ok", List.of(groups));
    }

    private static Sub2GroupsResponse.Group group(long id, String name, String platform, double rate) {
        Sub2GroupsResponse.Group group = mock(Sub2GroupsResponse.Group.class);
        when(group.id()).thenReturn(id);
        when(group.name()).thenReturn(name);
        when(group.platform()).thenReturn(platform);
        when(group.rateMultiplier()).thenReturn(rate);
        return group;
    }

    private static Sub2KeysResponse.KeyItem createdKey() {
        Sub2KeysResponse.KeyItem key = mock(Sub2KeysResponse.KeyItem.class);
        when(key.key()).thenReturn("sk-created-123");
        when(key.name()).thenReturn("my-key");
        when(key.status()).thenReturn("active");
        when(key.expiresAt()).thenReturn("2026-11-20T10:00:00+08:00");
        return key;
    }
}
