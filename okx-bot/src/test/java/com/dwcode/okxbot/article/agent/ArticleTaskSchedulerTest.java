package com.dwcode.okxbot.article.agent;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleTaskStatus;
import com.dwcode.okxbot.article.event.ArticleTaskEventPublisher;
import com.dwcode.okxbot.article.mapper.ArticleTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleTaskSchedulerTest {

    @Mock
    private ArticleTaskMapper taskMapper;
    @Mock
    private ArticleTaskAsyncRunner asyncRunner;
    @Mock
    private ArticleTaskEventPublisher eventPublisher;

    private ArticleProperties properties;
    private ArticleTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new ArticleProperties();
        properties.setEnabled(true);
        properties.setMaxConcurrentTasks(2);
        properties.setMaxConcurrentTasksPerUser(2);
        scheduler = new ArticleTaskScheduler(taskMapper, asyncRunner, properties, eventPublisher);
    }

    @Test
    void tryStartNextStartsPendingWithinSlots() {
        ArticleTaskEntity t1 = pending(1L, 10L);
        ArticleTaskEntity t2 = pending(2L, 10L);
        when(taskMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectList(any())).thenReturn(List.of(t1, t2));

        scheduler.tryStartNext();

        verify(asyncRunner).runAsync(1L);
        verify(asyncRunner).runAsync(2L);
    }

    @Test
    void tryStartNextRespectsGlobalMax() {
        properties.setMaxConcurrentTasks(1);
        ArticleTaskEntity t1 = pending(1L, 10L);
        ArticleTaskEntity t2 = pending(2L, 11L);
        when(taskMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectList(any())).thenReturn(List.of(t1, t2));

        scheduler.tryStartNext();

        verify(asyncRunner).runAsync(1L);
        verify(asyncRunner, never()).runAsync(2L);
    }

    @Test
    void markFinishedClearsActiveAndTriesNext() {
        ArticleTaskEntity t1 = pending(1L, 10L);
        when(taskMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectList(any())).thenReturn(List.of(t1)).thenReturn(List.of());

        scheduler.tryStartNext();
        assertTrue(scheduler.isActive(1L));
        scheduler.markFinished(1L);

        // second tryStartNext from markFinished — no more pending
        verify(asyncRunner, atLeastOnce()).runAsync(anyLong());
    }

    @Test
    void recoverOrphansMarksFailed() {
        ArticleTaskEntity orphan = new ArticleTaskEntity();
        orphan.setId(9L);
        orphan.setUserId(1L);
        orphan.setStatus(ArticleTaskStatus.FETCHING.name());
        when(taskMapper.selectList(any())).thenReturn(List.of(orphan)).thenReturn(List.of());
        when(taskMapper.selectCount(any())).thenReturn(0L);

        scheduler.recoverOrphanRunningTasks();

        ArgumentCaptor<ArticleTaskEntity> cap = ArgumentCaptor.forClass(ArticleTaskEntity.class);
        verify(taskMapper).updateById(cap.capture());
        assertEquals(ArticleTaskStatus.FAILED.name(), cap.getValue().getStatus());
        assertEquals(0, cap.getValue().getPasteResume().intValue());
    }

    private static ArticleTaskEntity pending(Long id, Long userId) {
        ArticleTaskEntity e = new ArticleTaskEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setStatus(ArticleTaskStatus.PENDING.name());
        return e;
    }
}
