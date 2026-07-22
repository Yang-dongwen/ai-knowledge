package com.dwcode.okxbot.article.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleTaskStatusTest {

    @Test
    void needsPasteIsTerminalWaitingUserNotRunning() {
        ArticleTaskStatus s = ArticleTaskStatus.NEEDS_PASTE;
        assertTrue(s.isTerminal());
        assertTrue(s.isWaitingUser());
        assertFalse(s.isRunning());
    }

    @Test
    void runningStates() {
        assertTrue(ArticleTaskStatus.FETCHING.isRunning());
        assertTrue(ArticleTaskStatus.LLM_CORE.isRunning());
        assertFalse(ArticleTaskStatus.PENDING.isRunning());
        assertFalse(ArticleTaskStatus.SUCCESS.isRunning());
    }

    @Test
    void terminalStates() {
        assertTrue(ArticleTaskStatus.SUCCESS.isTerminal());
        assertTrue(ArticleTaskStatus.FAILED.isTerminal());
        assertTrue(ArticleTaskStatus.CANCELLED.isTerminal());
        assertTrue(ArticleTaskStatus.PAUSED.isTerminal());
        assertFalse(ArticleTaskStatus.PENDING.isTerminal());
        assertFalse(ArticleTaskStatus.RESOLVING.isTerminal());
    }
}
