package com.dwcode.okxbot.article.agent;

import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.enums.ArticleSupportLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleFetchPolicyTest {

    @Test
    void pasteOnlyWithoutPasteBlocks() {
        ArticleFetchPolicy.Snapshot s = snap(false, true, false, true, false, ArticleSupportLevel.PASTE_ONLY);
        ArticleFetchPolicy.PasteBlock b = ArticleFetchPolicy.blockWithoutPaste(s);
        assertNotNull(b);
        assertEquals(ArticleErrorCode.PLATFORM_PASTE_ONLY, b.errorCode());
        assertFalse(ArticleFetchPolicy.skipFetch(s));
    }

    @Test
    void pasteOnlyWithPasteSkipsFetch() {
        ArticleFetchPolicy.Snapshot s = snap(true, true, false, true, false, ArticleSupportLevel.PASTE_ONLY);
        assertNull(ArticleFetchPolicy.blockWithoutPaste(s));
        assertTrue(ArticleFetchPolicy.skipFetch(s));
    }

    @Test
    void forcePasteWithPasteSkipsFetch() {
        ArticleFetchPolicy.Snapshot s = snap(true, true, true, true, false, ArticleSupportLevel.FULL);
        assertTrue(ArticleFetchPolicy.skipFetch(s));
    }

    @Test
    void pasteResumeSkipsFetch() {
        ArticleFetchPolicy.Snapshot s = snap(true, true, false, true, true, ArticleSupportLevel.FULL);
        assertTrue(ArticleFetchPolicy.skipFetch(s));
    }

    @Test
    void pasteWithoutUrlSkipsFetch() {
        ArticleFetchPolicy.Snapshot s = snap(true, false, false, true, false, ArticleSupportLevel.FULL);
        assertTrue(ArticleFetchPolicy.skipFetch(s));
    }

    @Test
    void urlWithoutPasteDoesNotSkip() {
        ArticleFetchPolicy.Snapshot s = snap(false, true, false, true, false, ArticleSupportLevel.FULL);
        assertFalse(ArticleFetchPolicy.skipFetch(s));
        assertNull(ArticleFetchPolicy.blockWithoutPaste(s));
    }

    @Test
    void failureUsesPasteWhenAvailable() {
        ArticleFetchPolicy.Snapshot s = snap(true, true, false, true, false, ArticleSupportLevel.FULL);
        assertEquals(ArticleFetchPolicy.OnFailure.USE_PASTE, ArticleFetchPolicy.onSourceFailure(s));
    }

    @Test
    void failureNeedsPasteWhenAllowed() {
        ArticleFetchPolicy.Snapshot s = snap(false, true, false, true, false, ArticleSupportLevel.FULL);
        assertEquals(ArticleFetchPolicy.OnFailure.NEEDS_PASTE, ArticleFetchPolicy.onSourceFailure(s));
    }

    @Test
    void failureFailsWhenFallbackDisabled() {
        ArticleFetchPolicy.Snapshot s = snap(false, true, false, false, false, ArticleSupportLevel.FULL);
        assertEquals(ArticleFetchPolicy.OnFailure.FAIL, ArticleFetchPolicy.onSourceFailure(s));
    }

    @Test
    void snapshotFromEntity() {
        ArticleTaskEntity t = new ArticleTaskEntity();
        t.setPasteText("hello");
        t.setSourceUrl("https://example.com");
        t.setForcePasteOnly(0);
        t.setAllowPasteFallback(null);
        t.setSupportLevel("FULL");
        ArticleFetchPolicy.Snapshot s = ArticleFetchPolicy.Snapshot.of(t, false);
        assertTrue(s.hasPaste());
        assertTrue(s.hasUrl());
        assertTrue(s.allowFallback());
        assertFalse(s.forcePaste());
        assertEquals(ArticleSupportLevel.FULL, s.supportLevel());
    }

    private static ArticleFetchPolicy.Snapshot snap(boolean paste, boolean url, boolean force,
                                                    boolean fallback, boolean resume,
                                                    ArticleSupportLevel sl) {
        return new ArticleFetchPolicy.Snapshot(paste, url, force, fallback, resume, sl);
    }
}
