package com.monitor.platform.bot;

import com.monitor.platform.api.dto.BotSettingsRequest;
import com.monitor.platform.api.dto.BotSettingsResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 页面可配置的机器人设置：保存后立即生效、白名单为空表示不限制、越界参数被夹紧。
 */
class BotSettingsServiceTest {

    private BotSettingsEntity existing() {
        BotSettingsEntity entity = new BotSettingsEntity();
        entity.setId(1);
        entity.setEnabled(true);
        entity.setAllowedGroups("111");
        entity.setAllowedUsers("999");
        entity.setRequireMention(true);
        entity.setCommandPrefix("/");
        entity.setMaxReplyLength(900);
        entity.setMemoryWindow(10);
        return entity;
    }

    @Test
    void shouldPersistAndRefreshSnapshotImmediately() {
        BotSettingsRepository repository = mock(BotSettingsRepository.class);
        when(repository.find()).thenReturn(existing());
        BotSettingsService service = new BotSettingsService(repository, new QqBotProperties());

        BotSettingsResponse saved = service.save(new BotSettingsRequest(
                true, List.of("785724309"), List.of("2696775653"), true, "/", 900, 10));

        assertEquals(Set.of("785724309"), saved.allowedGroups());
        // 内存快照立即刷新：不需要重启容器
        assertTrue(service.current().isGroupAllowed(785724309L));
        assertFalse(service.current().isGroupAllowed(123L));
        assertTrue(service.current().isUserAllowed(2696775653L));
        verify(repository).save(any());
    }

    @Test
    void shouldTreatBlankWhitelistAsUnrestricted() {
        BotSettingsRepository repository = mock(BotSettingsRepository.class);
        when(repository.find()).thenReturn(existing());
        BotSettingsService service = new BotSettingsService(repository, new QqBotProperties());

        service.save(new BotSettingsRequest(true, List.of(), List.of(), true, "/", 900, 10));

        assertTrue(service.current().isGroupAllowed(1L));
        assertTrue(service.current().isUserAllowed(2L));
    }

    @Test
    void shouldClampAndDefaultOutOfRangeValues() {
        BotSettingsRepository repository = mock(BotSettingsRepository.class);
        when(repository.find()).thenReturn(existing());
        BotSettingsService service = new BotSettingsService(repository, new QqBotProperties());

        BotSettingsResponse saved = service.save(new BotSettingsRequest(
                true, List.of(" 111 ", "", "222"), null, null, "   ", 999999, 1));

        assertEquals(Set.of("111", "222"), saved.allowedGroups());
        assertEquals("/", saved.commandPrefix());
        assertEquals(4000, saved.maxReplyLength());
        assertEquals(2, saved.memoryWindow());
        assertTrue(saved.requireMention());
    }

    @Test
    void shouldSeedFromEnvironmentWhenRowMissing() {
        BotSettingsRepository repository = mock(BotSettingsRepository.class);
        when(repository.find()).thenReturn(null);
        QqBotProperties properties = new QqBotProperties();
        properties.setAllowedGroups(List.of("111"));
        properties.setAllowedUsers(List.of("222"));

        BotSettingsService service = new BotSettingsService(repository, properties);
        service.reload();

        assertTrue(service.current().isGroupAllowed(111L));
        assertFalse(service.current().isGroupAllowed(999L));
        verify(repository).save(any());
    }
}