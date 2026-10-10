package com.monitor.platform.bot;

import com.monitor.platform.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自定义管理员：邮箱规范化、重复校验、内存快照刷新。
 *
 * <p>Sub2API 只允许一个管理员，所以这份名单是多人协作的实际入口，
 * 规范化与去重必须严格，否则会出现「加了但认不出来」。</p>
 */
class BotAdminServiceTest {

    private BotAdminRepository repository;
    private BotAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(BotAdminRepository.class);
        service = new BotAdminService(repository);
    }

    private static BotAdminEntity entity(long id, String email) {
        BotAdminEntity entity = new BotAdminEntity();
        entity.setId(id);
        entity.setEmail(email);
        return entity;
    }

    @Test
    void shouldMatchAdminEmailCaseInsensitively() {
        when(repository.findAll()).thenReturn(List.of(entity(1L, "Ops@Example.com")));
        service.reload();

        assertTrue(service.isAdmin("ops@example.com"));
        assertTrue(service.isAdmin("  OPS@EXAMPLE.COM "));
        assertFalse(service.isAdmin("other@example.com"));
    }

    @Test
    void shouldStoreNormalizedEmailOnAdd() {
        when(repository.findAll()).thenReturn(List.of());
        service.reload();
        // 第一次调用是查重（不存在），第二次是插入后回读
        when(repository.findByEmail("ops@example.com")).thenReturn(null, entity(1L, "ops@example.com"));

        service.add("  Ops@Example.com ", "  运维 A  ");

        ArgumentCaptor<BotAdminEntity> saved = ArgumentCaptor.forClass(BotAdminEntity.class);
        verify(repository).insert(saved.capture());
        assertEquals("ops@example.com", saved.getValue().getEmail());
        assertEquals("运维 A", saved.getValue().getRemark());
    }

    @Test
    void shouldRejectDuplicateAdmin() {
        // 查重时已存在 → 必须拒绝，不能再插一条
        when(repository.findByEmail("ops@example.com")).thenReturn(entity(1L, "ops@example.com"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.add("OPS@example.com", null));
        assertTrue(ex.getMessage().contains("已经是自定义管理员"));
    }

    @Test
    void shouldRejectMalformedEmail() {
        assertThrows(BusinessException.class, () -> service.add("not-an-email", null));
        assertThrows(BusinessException.class, () -> service.add("  ", null));
    }

    @Test
    void shouldRefreshSnapshotAfterRemove() {
        when(repository.findAll()).thenReturn(List.of(entity(1L, "ops@example.com")));
        service.reload();
        assertTrue(service.isAdmin("ops@example.com"));

        when(repository.delete(1L)).thenReturn(true);
        when(repository.findAll()).thenReturn(List.of());
        service.remove(1L);

        assertFalse(service.isAdmin("ops@example.com"));
        assertEquals(0, service.count());
    }

    @Test
    void shouldFailRemoveWhenNothingDeleted() {
        when(repository.delete(9L)).thenReturn(false);
        assertThrows(BusinessException.class, () -> service.remove(9L));
    }

    /** 数据库读不到时不能让机器人整体不可用，退化成空名单即可。 */
    @Test
    void shouldDegradeToEmptyOnLoadFailure() {
        when(repository.findAll()).thenThrow(new IllegalStateException("db down"));
        service.reload();

        assertEquals(0, service.count());
        assertFalse(service.isAdmin("ops@example.com"));
    }
}