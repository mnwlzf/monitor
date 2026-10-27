package com.monitor.platform.bot;

import com.monitor.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotForbiddenWordServiceTest {

    @Test
    void shouldMatchContainedWordsIgnoringCaseAndFullWidth() {
        BotForbiddenWordRepository repository = mock(BotForbiddenWordRepository.class);
        when(repository.findAll()).thenReturn(List.of(word("bad")));
        BotForbiddenWordService service = new BotForbiddenWordService(repository);
        service.reload();

        assertTrue(service.containsForbiddenWord("这里包含 BAD 内容"));
        assertTrue(service.containsForbiddenWord("这里包含 ＢＡＤ 内容"));
        assertFalse(service.containsForbiddenWord("正常消息"));
        assertFalse(service.containsForbiddenWord(""));
    }

    @Test
    void shouldTrimBeforeAddingAndRefreshSnapshot() {
        BotForbiddenWordRepository repository = mock(BotForbiddenWordRepository.class);
        BotForbiddenWordEntity persisted = word("bad");
        when(repository.findByWord("bad")).thenReturn(null, persisted);
        when(repository.findAll()).thenReturn(List.of(persisted));
        BotForbiddenWordService service = new BotForbiddenWordService(repository);

        BotForbiddenWordEntity added = service.add("  bad  ");

        assertEquals("bad", added.getWord());
        assertTrue(service.containsForbiddenWord("xx bad yy"));
        verify(repository).insert(org.mockito.ArgumentMatchers.argThat(entity -> "bad".equals(entity.getWord())));
    }

    @Test
    void shouldRejectDuplicateWord() {
        BotForbiddenWordRepository repository = mock(BotForbiddenWordRepository.class);
        when(repository.findByWord("BAD")).thenReturn(word("bad"));
        BotForbiddenWordService service = new BotForbiddenWordService(repository);

        BusinessException error = assertThrows(BusinessException.class, () -> service.add("BAD"));

        assertTrue(error.getMessage().contains("已存在"));
        verify(repository, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRejectBlankWord() {
        BotForbiddenWordRepository repository = mock(BotForbiddenWordRepository.class);
        BotForbiddenWordService service = new BotForbiddenWordService(repository);

        assertThrows(BusinessException.class, () -> service.add("   "));
        verify(repository, never()).findByWord(anyString());
    }

    private static BotForbiddenWordEntity word(String value) {
        BotForbiddenWordEntity entity = new BotForbiddenWordEntity();
        entity.setWord(value);
        return entity;
    }
}
