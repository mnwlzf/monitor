package com.monitor.platform.bot.key;

import com.monitor.platform.adapter.sub2api.Sub2ApiAdapter;
import com.monitor.platform.adapter.sub2api.model.Sub2GroupsResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2KeysResponse;
import com.monitor.platform.collector.application.AccountCredentialService;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private Sub2KeyTools tools;

    @BeforeEach
    void setUp() {
        tools = new Sub2KeyTools(platformRepository, accountRepository, credentialService, sub2ApiAdapter);

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
    }

    @Test
    void shouldAskForAllMissingParamsWithoutCallingSub2Api() {
        String reply = tools.createSub2ApiKey(null, " ", "", null, null);

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
                "my-key", 32L, 10.0, 30))
                .thenReturn(created);

        String reply = tools.createSub2ApiKey("openai", "my-key", "pro", 10.0, 30);

        assertThat(reply)
                .contains("密钥创建成功")
                .contains("厂商：openai")
                .contains("名称：my-key")
                .contains("分组：pro")
                .contains("额度限制：$10")
                .contains("有效期：30 天")
                .contains("密钥：sk-created-123");
        verify(sub2ApiAdapter).createKey("https://sub2.example.com", "admin@example.com", "pw",
                "my-key", 32L, 10.0, 30);
    }

    @Test
    void shouldShowActualVendorWhenGroupNameExistsUnderAnotherVendor() {
        Sub2GroupsResponse.Group group = group(32L, "pro", "anthropic", 0.3);
        when(sub2ApiAdapter.fetchAvailableGroups(anyString(), anyString(), any()))
                .thenReturn(groups(group));

        String reply = tools.createSub2ApiKey("openai", "my-key", "pro", 0.0, 0);

        assertThat(reply)
                .contains("没找到厂商「openai」")
                .contains("同名分组实际厂商：anthropic");
        verify(sub2ApiAdapter, never()).createKey(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldSupportUnlimitedQuotaAndPermanentExpiry() {
        Sub2GroupsResponse.Group group = group(32L, "pro", "openai", 0.3);
        Sub2KeysResponse.KeyItem created = createdKey();
        when(sub2ApiAdapter.fetchAvailableGroups(anyString(), anyString(), any()))
                .thenReturn(groups(group));
        when(sub2ApiAdapter.createKey(anyString(), anyString(), any(), anyString(), anyLong(),
                anyDouble(), any()))
                .thenReturn(created);

        String reply = tools.createSub2ApiKey("openai", "my-key", "pro", 0.0, 0);

        assertThat(reply)
                .contains("额度限制：不限（$0）")
                .contains("有效期：永久");
        verify(sub2ApiAdapter).createKey("https://sub2.example.com", "admin@example.com", "pw",
                "my-key", 32L, 0.0, null);
    }

    @Test
    void shouldRejectNegativeQuotaOrExpiry() {
        assertThat(tools.createSub2ApiKey("openai", "my-key", "pro", -1.0, 30))
                .contains("额度限制不能为负数");
        assertThat(tools.createSub2ApiKey("openai", "my-key", "pro", 1.0, -1))
                .contains("有效期天数不能为负数");
        verifyNoInteractions(sub2ApiAdapter);
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
