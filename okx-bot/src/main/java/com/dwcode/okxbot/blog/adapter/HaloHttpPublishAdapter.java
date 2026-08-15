package com.dwcode.okxbot.blog.adapter;

import com.dwcode.okxbot.blog.SlugUtil;
import com.dwcode.okxbot.blog.config.HaloProperties;
import com.dwcode.okxbot.blog.port.HaloAttachment;
import com.dwcode.okxbot.blog.port.HaloPostTerms;
import com.dwcode.okxbot.blog.port.HaloPublishCommand;
import com.dwcode.okxbot.blog.port.HaloPublishPort;
import com.dwcode.okxbot.blog.port.HaloPublishResult;
import com.dwcode.okxbot.blog.port.HaloTerm;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Halo UC 文章 API。路径对标 vscode-extension-halo。
 */
@Slf4j
public class HaloHttpPublishAdapter implements HaloPublishPort {

    static final String CONTENT_JSON = "content.halo.run/content-json";
    static final String POSTS = "/apis/uc.api.content.halo.run/v1alpha1/posts";
    static final String CATEGORIES = "/apis/content.halo.run/v1alpha1/categories";
    static final String TAGS = "/apis/content.halo.run/v1alpha1/tags";
    static final String UC_ATTACH = "/apis/uc.api.storage.halo.run/v1alpha1/attachments";
    static final String UC_ATTACH_UPLOAD = "/apis/uc.api.storage.halo.run/v1alpha1/attachments/-/upload";
    static final String CONSOLE_ATTACH_UPLOAD = "/apis/api.console.halo.run/v1alpha1/attachments/upload";
    static final String STORAGE_ATTACH = "/apis/storage.halo.run/v1alpha1/attachments";

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
        rf.setReadTimeout(180_000);
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

    @Override
    public List<HaloTerm> listCategories() {
        requireConfigured();
        return listTerms(CATEGORIES);
    }

    @Override
    public List<HaloTerm> listTags() {
        requireConfigured();
        return listTerms(TAGS);
    }

    @Override
    public HaloPostTerms getPostTerms(String postName) {
        requireConfigured();
        if (!StringUtils.hasText(postName)) {
            return HaloPostTerms.empty();
        }
        try {
            JsonNode post = exchange("GET", POSTS + "/" + postName, null);
            JsonNode spec = post.path("spec");
            return new HaloPostTerms(
                    displayNames(listTerms(CATEGORIES), stringList(spec.path("categories"))),
                    displayNames(listTerms(TAGS), stringList(spec.path("tags"))));
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return HaloPostTerms.empty();
            }
            throw wrapHalo(e, "读取博客分类/标签失败");
        }
    }

    @Override
    public HaloAttachment upload(byte[] bytes, String filename, String contentType) {
        requireConfigured();
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(400, "附件内容为空");
        }
        String name = sanitizeUploadName(filename);
        try {
            JsonNode att = uploadViaUc(bytes, name, contentType);
            String attName = text(att, "metadata", "name");
            String permalink = waitPermalink(att, attName);
            if (!StringUtils.hasText(permalink)) {
                throw new BusinessException(502, "Halo 附件未返回公开地址: " + name);
            }
            return new HaloAttachment(attName, joinPublic(permalink));
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw wrapHalo(e, "Halo 上传附件失败");
        } catch (Exception e) {
            log.warn("Halo 上传附件异常: {}", e.getMessage());
            throw new BusinessException(502, "Halo 上传附件失败: " + e.getMessage());
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
        spec.put("cover", command.cover() == null ? "" : command.cover());
        writeStringArray(spec.putArray("categories"),
                command.categoryNames() == null ? List.of() : resolveCategoryNames(command.categoryNames()));
        writeStringArray(spec.putArray("tags"),
                command.tagNames() == null ? List.of() : resolveTagNames(command.tagNames()));
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
        if (command.categoryNames() != null) {
            writeStringArray(spec.putArray("categories"), resolveCategoryNames(command.categoryNames()));
        }
        if (command.tagNames() != null) {
            writeStringArray(spec.putArray("tags"), resolveTagNames(command.tagNames()));
        }
        if (StringUtils.hasText(command.cover())) {
            spec.put("cover", command.cover());
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

    private List<String> resolveCategoryNames(List<String> displayNames) {
        if (displayNames == null || displayNames.isEmpty()) {
            return List.of();
        }
        return resolveTerms(displayNames, listTerms(CATEGORIES), true);
    }

    private List<String> resolveTagNames(List<String> displayNames) {
        if (displayNames == null || displayNames.isEmpty()) {
            return List.of();
        }
        return resolveTerms(displayNames, listTerms(TAGS), false);
    }

    private List<String> resolveTerms(List<String> displayNames, List<HaloTerm> existing, boolean category) {
        if (displayNames == null || displayNames.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        List<HaloTerm> all = new ArrayList<>(existing);
        for (String raw : displayNames) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String display = raw.trim();
            HaloTerm found = findByDisplay(all, display);
            if (found == null) {
                found = category ? createCategory(display, all.size()) : createTag(display);
                all.add(found);
            }
            names.add(found.name());
        }
        return names;
    }

    private HaloTerm findByDisplay(List<HaloTerm> terms, String display) {
        for (HaloTerm t : terms) {
            if (display.equals(t.displayName())) {
                return t;
            }
        }
        return null;
    }

    private HaloTerm createCategory(String displayName, int priority) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("apiVersion", "content.halo.run/v1alpha1");
        body.put("kind", "Category");
        ObjectNode metadata = body.putObject("metadata");
        metadata.put("name", "");
        metadata.put("generateName", "category-");
        ObjectNode spec = body.putObject("spec");
        spec.put("displayName", displayName);
        spec.put("slug", SlugUtil.fromTitle(displayName, "category-" + UUID.randomUUID().toString().substring(0, 8)));
        spec.put("description", "");
        spec.put("cover", "");
        spec.put("template", "");
        spec.put("priority", priority);
        spec.putArray("children");
        JsonNode created = exchange("POST", CATEGORIES, body);
        String name = text(created, "metadata", "name");
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(502, "Halo 创建分类未返回 name: " + displayName);
        }
        return new HaloTerm(name, displayName);
    }

    private HaloTerm createTag(String displayName) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("apiVersion", "content.halo.run/v1alpha1");
        body.put("kind", "Tag");
        ObjectNode metadata = body.putObject("metadata");
        metadata.put("name", "");
        metadata.put("generateName", "tag-");
        ObjectNode spec = body.putObject("spec");
        spec.put("displayName", displayName);
        spec.put("slug", SlugUtil.fromTitle(displayName, "tag-" + UUID.randomUUID().toString().substring(0, 8)));
        spec.put("color", "#ffffff");
        spec.put("cover", "");
        JsonNode created = exchange("POST", TAGS, body);
        String name = text(created, "metadata", "name");
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(502, "Halo 创建标签未返回 name: " + displayName);
        }
        return new HaloTerm(name, displayName);
    }

    private List<HaloTerm> listTerms(String path) {
        JsonNode root = exchange("GET", path + "?page=0&size=0", null);
        List<HaloTerm> out = new ArrayList<>();
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            return out;
        }
        for (JsonNode item : items) {
            String name = text(item, "metadata", "name");
            String display = text(item, "spec", "displayName");
            if (StringUtils.hasText(name)) {
                out.add(new HaloTerm(name, StringUtils.hasText(display) ? display : name));
            }
        }
        return out;
    }

    private static List<String> displayNames(List<HaloTerm> terms, List<String> metadataNames) {
        List<String> out = new ArrayList<>();
        for (String n : metadataNames) {
            String display = n;
            for (HaloTerm t : terms) {
                if (n.equals(t.name())) {
                    display = t.displayName();
                    break;
                }
            }
            out.add(display);
        }
        return out;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            String s = n.asText("");
            if (StringUtils.hasText(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static void writeStringArray(ArrayNode arr, List<String> values) {
        if (values == null) {
            return;
        }
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                arr.add(v);
            }
        }
    }

    private JsonNode uploadViaUc(byte[] bytes, String filename, String contentType) {
        MultiValueMap<String, Object> form = multipartFile(bytes, filename, contentType);
        try {
            return postMultipart(UC_ATTACH + "?waitForPermalink=true", form);
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 404 || code == 405) {
                log.info("Halo UC attachments POST 不可用，改 upload 接口");
                return postMultipart(UC_ATTACH_UPLOAD, form);
            }
            if (code == 403 || code == 401) {
                log.info("Halo UC 附件无权限，尝试 Console 上传");
                MultiValueMap<String, Object> console = multipartFile(bytes, filename, contentType);
                console.add("policyName", "default-policy");
                return postMultipart(CONSOLE_ATTACH_UPLOAD, console);
            }
            throw e;
        }
    }

    private JsonNode postMultipart(String path, MultiValueMap<String, Object> form) {
        String raw = client.post()
                .uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(String.class);
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new BusinessException(502, "Halo 附件响应不是 JSON");
        }
    }

    private static MultiValueMap<String, Object> multipartFile(byte[] bytes, String filename, String contentType) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        form.add("file", resource);
        return form;
    }

    private String waitPermalink(JsonNode att, String name) {
        String permalink = text(att, "status", "permalink");
        if (StringUtils.hasText(permalink) || !StringUtils.hasText(name)) {
            return permalink;
        }
        for (int i = 0; i < 8; i++) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return permalink;
            }
            try {
                JsonNode fresh = exchange("GET", STORAGE_ATTACH + "/" + name, null);
                permalink = text(fresh, "status", "permalink");
                if (StringUtils.hasText(permalink)) {
                    return permalink;
                }
            } catch (Exception ignored) {
                // 再试
            }
        }
        return permalink;
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new BusinessException(503, DisabledHaloPublishAdapter.MESSAGE);
        }
    }

    private BusinessException wrapHalo(RestClientResponseException e, String prefix) {
        log.warn("Halo HTTP {} {}: {}", e.getStatusCode().value(), e.getStatusText(),
                abbreviate(e.getResponseBodyAsString()));
        if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
            return new BusinessException(502, "Halo 鉴权失败，请检查 HALO_PAT 权限（发文 / 附件 / 分类标签）");
        }
        return new BusinessException(502, prefix + ": HTTP " + e.getStatusCode().value());
    }

    private static String sanitizeUploadName(String filename) {
        String n = filename == null ? "" : filename.replace('\\', '/');
        if (n.contains("/")) {
            n = n.substring(n.lastIndexOf('/') + 1);
        }
        n = n.trim();
        if (n.isEmpty()) {
            n = "file.bin";
        }
        if (n.length() > 120) {
            n = n.substring(n.length() - 120);
        }
        return n;
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
