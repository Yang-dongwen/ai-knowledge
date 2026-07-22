package com.dwcode.okxbot.article.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 加载 classpath:article/prompts/* 模板。
 */
@Slf4j
@Component
public class ArticlePromptLoader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String load(String name) {
        return cache.computeIfAbsent(name, this::read);
    }

    /**
     * 简单 {{key}} 替换。
     */
    public String render(String name, Map<String, String> vars) {
        String tpl = load(name);
        if (vars == null || vars.isEmpty()) {
            return tpl;
        }
        String out = tpl;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
        }
        return out;
    }

    private String read(String name) {
        String path = "article/prompts/" + name;
        try {
            ClassPathResource res = new ClassPathResource(path);
            try (InputStream in = res.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("加载 prompt 失败 {}: {}", path, e.getMessage());
            return "";
        }
    }
}
