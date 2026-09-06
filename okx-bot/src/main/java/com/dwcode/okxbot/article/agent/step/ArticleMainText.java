package com.dwcode.okxbot.article.agent.step;

import com.dwcode.okxbot.article.agent.ArticleFetchPolicy;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.service.ArticleStorageService;

import java.nio.file.Path;

public final class ArticleMainText {

    public static final int DB_CHARS = 4000;

    private ArticleMainText() {
    }

    public static void apply(ArticleTaskEntity task, Path workDir, String main,
                             ArticleStorageService storageService) {
        int fullLen = main.length();
        task.setMainTextChars(fullLen);
        String forDb = main.length() > DB_CHARS ? main.substring(0, DB_CHARS) : main;
        task.setMainText(forDb);
        storageService.writeText(workDir, "main.txt", main);
        if (task.getTitle() == null || task.getTitle().isBlank()) {
            task.setTitle(deriveTitle(main));
        }
    }

    public static String deriveTitle(String main) {
        if (main == null || main.isBlank()) {
            return "未命名文章";
        }
        String t = main.trim().replaceAll("\\s+", " ");
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    public static String nullTo(String s, String d) {
        return s == null || s.isBlank() ? d : s;
    }

    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    public static boolean usable(com.dwcode.okxbot.article.port.MainTextDocument doc) {
        return doc != null && !doc.isUnusable() && ArticleFetchPolicy.hasText(doc.getMainText());
    }
}
