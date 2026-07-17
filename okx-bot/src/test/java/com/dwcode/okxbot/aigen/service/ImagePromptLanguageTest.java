package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImagePromptLanguageTest {

    @Test
    void chinesePromptIsNotEnglishDominant() {
        AigenTaskEntity t = new AigenTaskEntity();
        t.setLanguage("zh");
        t.setPrompt("生成以太坊加密货币的历史进程");
        assertFalse(VisualShotAssetService.isEnglishDominant(t, "电影感画面"));
    }

    @Test
    void englishPromptIsEnglishDominant() {
        AigenTaskEntity t = new AigenTaskEntity();
        t.setLanguage("en");
        t.setPrompt("Generate a video about Ethereum history");
        assertTrue(VisualShotAssetService.isEnglishDominant(t, "cinematic shot"));
    }

    @Test
    void mixedCjkPrefersChinese() {
        AigenTaskEntity t = new AigenTaskEntity();
        t.setPrompt("以太坊 Ethereum history process");
        assertFalse(VisualShotAssetService.isEnglishDominant(t, ""));
    }
}
