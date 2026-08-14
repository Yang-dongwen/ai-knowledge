package com.dwcode.okxbot.blog.adapter;

import com.dwcode.okxbot.blog.config.HaloProperties;
import com.dwcode.okxbot.blog.port.HaloPublishCommand;
import com.dwcode.okxbot.blog.port.HaloPublishPort;
import com.dwcode.okxbot.blog.port.HaloPublishResult;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.Proxy;
import java.util.UUID;

/**
 * Halo UC 文章 API。路径对标 vscode-extension-halo。
 */
@Slf4j
public class HaloHttpPublishAdapter implements HaloPublishPort {

    static final String CONTENT_JSON = "content.halo.run/content-json";
    static final String POSTS = "/apis/uc.api.content.halo.run/v1alpha1/posts";

    private final HaloProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public HaloHttpPublishAdapter(HaloProperties properties, ObjectMapper objectMapper) {
        // 直连：本机 Clash 等系统代理会劫持 8090/localhost 导致假 502
        this(properties, objectMapper, buildClient(properties));
    }

    private static RestClient buildClient(HaloProperties properties) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setProxy(Proxy.NO_PROXY);
        rf.setConnectTimeout(15_000);
        rf.setReadTimeout(60_000);
        return RestClient.builder()
                .baseUrl(trimSlash(properties.getBaseUrl()))
                .requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + properties.getToken())
                .build();
    }

    HaloHttpPublishAdapter(HaloProperties properties, ObjectMapper objectMapper, RestClient client) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = client;
    }

    @Override
    public HaloPublishResult publish(HaloPublishCommand command) {
        if (!properties.isConfigured()) {
            throw new BusinessException(503, DisabledHaloPublishAdapter.MESSAGE);
        }
        try {
            String contentJson = contentJson(command);
            JsonNode post;
            if (StringUtils.hasText(command.existingPostName())) {
                post = updateExisting(command, contentJson);
            } else {
                post = createNew(command, contentJson);
            }
            String name = text(post, "metadata", "name");
            if (!StringUtils.hasText(name)) {
                throw new BusinessException(502, "Halo 未返回文章 name");
            }
            if (properties.isPublishOnCreate()) {
                publishPost(name);
            }
            post = exchange("GET", POSTS + "/" + name, null);
            String permalink = text(post, "status", "permalink");
            return new HaloPublishResult(name, joinPublic(permalink), permalink);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            log.warn("Halo HTTP {} {}: {}", e.getStatusCode().value(), e.getStatusText(),
                    abbreviate(e.getResponseBodyAsString()));
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                throw new BusinessException(502, "Halo 鉴权失败，请检查 HALO_PAT 权限");
            }
            throw new BusinessException(502, "Halo 发文失败: HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("Halo 发文异常: {}", e.getMessage());
            throw new BusinessException(502, "Halo 发文失败: " + e.getMessage());
        }
    }

    private JsonNode createNew(HaloPublishCommand command, String contentJson) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("apiVersion", "content.halo.run/v1alpha1");
        body.put("kind", "Post");
        ObjectNode metadata = body.putObject("metadata");
        metadata.put("name", UUID.randomUUID().toString());
        ObjectNode anns = metadata.putObject("annotations");
        anns.put(CONTENT_JSON, contentJson);
        ObjectNode spec = body.putObject("spec");
        spec.put("title", command.title() == null ? "未命名" : command.title());
        spec.put("slug", command.slug());
        spec.put("allowComment", true);
        spec.put("deleted", false);
        spec.put("pinned", false);
        spec.put("priority", 0);
        spec.put("publish", false);
        spec.put("visible", "PUBLIC");
        spec.putArray("categories");
        spec.putArray("tags");
        spec.putArray("htmlMetas");
        ObjectNode excerpt = spec.putObject("excerpt");
        excerpt.put("autoGenerate", true);
        excerpt.put("raw", "");
        return exchange("POST", POSTS, body);
    }

    private JsonNode updateExisting(HaloPublishCommand command, String contentJson) {
        String name = command.existingPostName();
        JsonNode post = exchange("GET", POSTS + "/" + name, null);
        ObjectNode spec = (ObjectNode) post.path("spec");
        if (spec.isMissingNode() || spec.isNull()) {
            throw new BusinessException(502, "Halo 文章缺少 spec: " + name);
        }
        spec.put("title", command.title() == null ? "未命名" : command.title());
        if (StringUtils.hasText(command.slug())) {
            spec.put("slug", command.slug());
        }
        exchange("PUT", POSTS + "/" + name, post);

        JsonNode draft = exchange("GET", POSTS + "/" + name + "/draft?patched=true", null);
        ObjectNode meta = (ObjectNode) draft.path("metadata");
        ObjectNode anns = meta.has("annotations") && meta.get("annotations").isObject()
                ? (ObjectNode) meta.get("annotations")
                : meta.putObject("annotations");
        anns.put(CONTENT_JSON, contentJson);
        exchange("PUT", POSTS + "/" + name + "/draft", draft);
        return post;
    }

    private String contentJson(HaloPublishCommand command) throws Exception {
        String raw = command.raw() == null ? "" : command.raw();
        boolean markdown = "markdown".equalsIgnoreCase(command.rawType());
        // Halo 主题渲染读 content（HTML）；raw 为源码。markdown 时 content 必须是渲染结果。
        String html = markdown ? com.dwcode.okxbot.blog.MarkdownToHtml.render(raw) : raw;
        ObjectNode content = objectMapper.createObjectNode();
        content.put("rawType", markdown ? "markdown" : "HTML");
        content.put("raw", raw);
        content.put("content", html);
        return objectMapper.writeValueAsString(content);
    }

    private void publishPost(String name) {
        try {
            doPublish(name);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 409) {
                log.warn("Halo HTTP {} {}: {}", e.getStatusCode().value(), e.getStatusText(),
                        abbreviate(e.getResponseBodyAsString()));
                throw new BusinessException(502, "Halo 发文失败: HTTP " + e.getStatusCode().value());
            }
            // 已发布时再 publish 会 409：先 unpublish 再 publish，把最新 draft 推到前台
            log.info("Halo publish 409, republish name={}", name);
            try {
                client.method(org.springframework.http.HttpMethod.PUT)
                        .uri(POSTS + "/" + name + "/unpublish")
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .toBodilessEntity();
                doPublish(name);
            } catch (RestClientResponseException e2) {
                log.warn("Halo republish HTTP {} {}: {}", e2.getStatusCode().value(), e2.getStatusText(),
                        abbreviate(e2.getResponseBodyAsString()));
                throw new BusinessException(502, "Halo 发文失败: HTTP " + e2.getStatusCode().value());
            }
        }
    }

    private void doPublish(String name) {
        client.method(org.springframework.http.HttpMethod.PUT)
                .uri(POSTS + "/" + name + "/publish")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toBodilessEntity();
    }

    private JsonNode exchange(String method, String path, JsonNode body) {
        RestClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path)
                .accept(MediaType.APPLICATION_JSON);
        String raw;
        if (body != null) {
            raw = spec.contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        } else {
            raw = spec.retrieve().body(String.class);
        }
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new BusinessException(502, "Halo 响应不是 JSON");
        }
    }

    private String joinPublic(String permalink) {
        if (!StringUtils.hasText(permalink)) {
            return trimSlash(properties.getPublicBaseUrl());
        }
        if (permalink.startsWith("http://") || permalink.startsWith("https://")) {
            return permalink;
        }
        String base = trimSlash(properties.getPublicBaseUrl());
        return permalink.startsWith("/") ? base + permalink : base + "/" + permalink;
    }

    private static String text(JsonNode node, String... path) {
        JsonNode cur = node;
        for (String p : path) {
            if (cur == null || cur.isMissingNode()) {
                return "";
            }
            cur = cur.path(p);
        }
        return cur.isMissingNode() || cur.isNull() ? "" : cur.asText("");
    }

    static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300);
    }
}
