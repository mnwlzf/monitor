package com.monitor.platform.bot;

import com.monitor.platform.api.dto.BotPersonaRequest;
import com.monitor.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotPersonaServiceTest {

    private BotPersonaEntity persona(long id, String name, boolean enabled, boolean defaultPersona) {
        BotPersonaEntity entity = new BotPersonaEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDescription(name + " 说明");
        entity.setCharacterPrompt(name + " 角色设定");
        entity.setStylePrompt(name + " 表达风格");
        entity.setEnabled(enabled);
        entity.setDefaultPersona(defaultPersona);
        entity.setVersion(1);
        return entity;
    }

    @Test
    void shouldResolveBoundPersonaAndFallbackToDefault() {
        BotPersonaRepository repository = mock(BotPersonaRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                persona(1L, "默认", true, true),
                persona(2L, "客服", true, false),
                persona(3L, "已禁用", false, false)));
        BotPersonaService service = new BotPersonaService(repository);
        service.init();

        assertThat(service.resolve(2L).id()).isEqualTo(2L);
        assertThat(service.resolve(3L).id()).isEqualTo(1L);
        assertThat(service.resolve(null).id()).isEqualTo(1L);
        assertThat(service.resolve(999L).id()).isEqualTo(1L);
    }

    @Test
    void shouldIncrementVersionWhenPersonaChanges() {
        BotPersonaRepository repository = mock(BotPersonaRepository.class);
        BotPersonaEntity existing = persona(2L, "客服", true, false);
        when(repository.findById(2L)).thenReturn(existing);
        when(repository.findAll()).thenReturn(List.of(existing));
        BotPersonaService service = new BotPersonaService(repository);

        service.update(2L, new BotPersonaRequest(
                "客服升级版", "说明", "新的角色设定", "新的表达风格", true));

        assertThat(existing.getVersion()).isEqualTo(2);
        verify(repository).update(existing);
    }

    @Test
    void shouldRejectDeletingBoundPersona() {
        BotPersonaRepository repository = mock(BotPersonaRepository.class);
        when(repository.findById(2L)).thenReturn(persona(2L, "客服", true, false));
        when(repository.countGroupBindings(2L)).thenReturn(2);
        BotPersonaService service = new BotPersonaService(repository);

        assertThrows(BusinessException.class, () -> service.delete(2L));
    }
}
