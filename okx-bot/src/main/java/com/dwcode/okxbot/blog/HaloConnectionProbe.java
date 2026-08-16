package com.dwcode.okxbot.blog;

import com.dwcode.okxbot.blog.adapter.HaloHttpPublishAdapter;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 用 PAT 探活 Halo，并尽量读出当前用户名。
 */
public final class HaloConnectionProbe {

    public record Result(String username) {
    }

    private HaloConnectionProbe() {
    }

    public static Result probe(String baseUrl, String token, ObjectMapper mapper) {
        String base = HaloHttpPublishAdapter.trimSlash(baseUrl);
        if (!StringUtils.hasText(base) || !StringUtils.hasText(token)) {
            throw new BusinessException(400, "站点地址和令牌不能为空");
        }
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setProxy(Proxy.NO_PROXY);
        rf.setConnectTimeout(10_000);
        rf.setReadTimeout(20_000);
        RestClient client = RestClient.builder()
                .baseUrl(base)
                .requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + token.trim())
                .build();
        try {
            client.get()
                    .uri("/apis/uc.api.content.halo.run/v1alpha1/posts?page=0&size=1")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                throw new BusinessException(400, "站点或令牌无效（鉴权失败）");
            }
            throw new BusinessException(400, "无法连接博客站点: HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            throw new BusinessException(400, "无法连接博客站点: " + e.getMessage());
        }
        String name = readUsername(client, mapper);
        if (!StringUtils.hasText(name)) {
            name = subjectFromPat(token, mapper);
        }
        if (!StringUtils.hasText(name)) {
            name = "ok";
        }
        return new Result(name);
    }

    private static String readUsername(RestClient client, ObjectMapper mapper) {
        try {
            String raw = client.get()
                    .uri("/apis/api.console.halo.run/v1alpha1/users/-")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            if (!StringUtils.hasText(raw)) {
                return "";
            }
            JsonNode n = mapper.readTree(raw);
            String display = text(n, "spec", "displayName");
            if (StringUtils.hasText(display)) {
                return display;
            }
            return text(n, "metadata", "name");
        } catch (Exception ignored) {
            return "";
        }
    }

    static String subjectFromPat(String token, ObjectMapper mapper) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return "";
            }
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return mapper.readTree(json).path("sub").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String text(JsonNode node, String a, String b) {
        JsonNode n = node.path(a).path(b);
        return n.isMissingNode() || n.isNull() ? "" : n.asText("");
    }
}
