package com.monitor.platform.bot;

import com.monitor.platform.api.dto.BotSettingsRequest;
import com.monitor.platform.api.dto.BotSettingsResponse;
import com.monitor.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 页面可配置的机器人设置：保存后立即生效、白名单为空表示不限制、越界参数被夹紧、
 * 「指定群」必须是「启用群」的子集。
 */
class BotSettingsServiceTest {

    private BotSettingsEntity existing() {
        BotSettingsEntity entity = new BotSettingsEntity();
        entity.setId(1);
        entity.setEnabled(true);
        entity.setAllowedGroups("111");
        entity.setAllowedUsers("999");
        entity.setPlatformGroups("111");
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
                true, List.of("785724309"), List.of("2696775653"), List.of("785724309"), true, "/", 900, 10, false, false, 15, 5, 1.5, 30));

        assertEquals(Set.of("785724309"), saved.allowedGroups());
        assertEquals(Set.of("785724309"), saved.platformGroups());
        // 内存快照立即刷新：不需要重启容器
        assertTrue(service.current().isGroupAllowed(785724309L));
        assertFalse(service.current().isGroupAllowed(123L));
        assertTrue(service.current().isUserAllowed(2696775653L));
        assertTrue(service.current().isPlatformGroup(785724309L));
        verify(repository).save(any());
    }

    @Test
    void shouldTreatBlankWhitelistAsUnrestricted() {
        BotSettingsRepository repository = mock(BotSettingsRepository.class);
        when(repository.find()).thenReturn(existing());
        BotSettingsService service = new BotSettingsService(repository, new QqBotProperties());

        service.save(new BotSettingsRequest(true, List.of(), List.of(), List.of(), true, "/", 900, 10, false, false, 15, 5, 1.5, 30));

        assertTrue(service.current().isGroupAllowed(1L));
        assertTrue(service.current().isUserAllowed(2L));
        // 指定群留空表示一个都不给（与白名单语义相反）
        assertFalse(service.current().isPlatformGroup(1L));
    }

    @Test
    void shouldClampAndDefaultOutOfRangeValues() {
        BotSettingsRepository repository = mock(BotSettingsRepository.class);
        when(repository.find()).thenReturn(existing());
        BotSettingsService service = new BotSettingsService(repository, new QqBotProperties());

        BotSettingsResponse saved = service.save(new BotSettingsRequest(
                true, List.of(" 111 ", "", "222"), null, null, null, "   ", 999999, 1, null, null, null, null, null, null));

        assertEquals(Set.of("111", "222"), saved.allowedGroups());
        assertEquals(Set.of(), saved.platformGroups());
        assertEquals("/", saved.commandPrefix());
        assertEquals(4000, saved.maxReplyLength());
        assertEquals(2, saved.memoryWindow());
        assertTrue(saved.requireMention());
        assertFalse(saved.errorAlertEnabled());
        assertEquals(15, saved.errorAlertWindowMinutes());
        assertEquals(5, saved.errorAlertMinCount());
        assertEquals(1.5, saved.errorAlertSpikeRatio());
        assertEquals(30, saved.errorAlertCooldownMinutes());
    }

    /** 指定群必须是启用群的子集，越界直接拒绝保存。 */
    @Test
    void shouldRejectPlatformGroupOutsideAllowedGroups() {
        BotSettingsRepository repository = mock(BotSettingsRepository.class);
        when(repository.find()).thenReturn(existing());
        BotSettingsService service = new BotSettingsService(repository, new QqBotProperties());

        assertThrows(BusinessException.class, () -> service.save(new BotSettingsRequest(
                true, List.of("111"), List.of(), List.of("111", "999"), true, "/", 900, 10, false, false, 15, 5, 1.5, 30)));
    }

    /** 页面开关：普通人是否可用联网搜索，保存后内存快照立即生效。 */
    @Test
    void shouldPersistWebSearchForNonAdminFlag() {
        BotSettingsRepository repository = mock(BotSettingsRepository.class);
        when(repository.find()).thenReturn(existing());
        BotSettingsService service = new BotSettingsService(repository, new QqBotProperties());

        BotSettingsResponse saved = service.save(new BotSettingsRequest(
                true, List.of("111"), List.of(), List.of(), true, "/", 900, 10, true, false, 15, 5, 1.5, 30));

        assertTrue(saved.webSearchForNonAdmin());
        assertTrue(service.current().webSearchForNonAdmin());
    }

    @Test
    void shouldPersistErrorAlertSettingsFromPage() {
        BotSettingsRepository repository = mock(BotSettingsRepository.class);
        when(repository.find()).thenReturn(existing());
        BotSettingsService service = new BotSettingsService(repository, new QqBotProperties());

        BotSettingsResponse saved = service.save(new BotSettingsRequest(
                true, List.of("111"), List.of(), List.of(), true, "/", 900, 10, false,
                true, 30, 10, 2.0, 60));

        assertTrue(saved.errorAlertEnabled());
        assertEquals(30, saved.errorAlertWindowMinutes());
        assertEquals(10, saved.errorAlertMinCount());
        assertEquals(2.0, saved.errorAlertSpikeRatio());
        assertEquals(60, saved.errorAlertCooldownMinutes());
        assertTrue(service.current().errorAlertEnabled());
        assertEquals(30, service.current().errorAlertWindowMinutes());
    }

    @Test
    void shouldPersistVoiceSettingsFromPage() {
        BotSettingsRepository repository = mock(BotSettingsRepository.class);
        when(repository.find()).thenReturn(existing());
        BotSettingsService service = new BotSettingsService(repository, new QqBotProperties());

        BotSettingsResponse saved = service.save(new BotSettingsRequest(
                true, List.of("111"), List.of(), List.of(), true, "/", 900, 10, false,
                false, 15, 5, 1.5, 30, "fnlp/MOSS-TTSD-v0.5:alex", "温柔亲切"));

        assertEquals("fnlp/MOSS-TTSD-v0.5:alex", saved.defaultVoice());
        assertEquals("温柔亲切", saved.defaultVoiceStyle());
        assertEquals("fnlp/MOSS-TTSD-v0.5:alex", service.current().defaultVoice());
        assertEquals("温柔亲切", service.current().defaultVoiceStyle());
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
        // 首次初始化时指定群为空：不给任何群开平台功能
        assertFalse(service.current().isPlatformGroup(111L));
        verify(repository).save(any());
    }
}
