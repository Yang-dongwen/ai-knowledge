package com.dwcode.okxbot.blog.adapter;

import com.dwcode.okxbot.blog.config.HaloProperties;
import com.dwcode.okxbot.blog.port.HaloPublishCommand;
import com.dwcode.okxbot.blog.port.HaloPublishResult;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HaloHttpPublishAdapterTest {

    private MockWebServer server;
    private HaloProperties properties;
    private HaloHttpPublishAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        properties = new HaloProperties();
        properties.setEnabled(true);
        properties.setToken("pat_test");
        properties.setBaseUrl(server.url("/").toString());
        properties.setPublicBaseUrl("https://blog.example.com");
        properties.setPublishOnCreate(true);
        adapter = new HaloHttpPublishAdapter(properties, new ObjectMapper(),
                RestClient.builder().baseUrl(HaloHttpPublishAdapter.trimSlash(properties.getBaseUrl()))
                        .defaultHeader("Authorization", "Bearer pat_test")
                        .build());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void createThenPublish() throws Exception {
        String created = """
                {"metadata":{"name":"post-abc"},"spec":{"title":"Hi"},"status":{}}
                """;
        String published = """
                {"metadata":{"name":"post-abc"},"spec":{"title":"Hi"},"status":{"permalink":"/archives/hi"}}
                """;
        server.enqueue(new MockResponse().setBody(created).addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody(published).addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody(published).addHeader("Content-Type", "application/json"));

        HaloPublishResult r = adapter.publish(new HaloPublishCommand("Hi", "hi", "# hi", "markdown", null));
        assertEquals("post-abc", r.postName());
        assertEquals("https://blog.example.com/archives/hi", r.publicUrl());

        RecordedRequest create = server.takeRequest();
        assertEquals("POST", create.getMethod());
        assertTrue(create.getPath().endsWith("/apis/uc.api.content.halo.run/v1alpha1/posts"));
        assertEquals("Bearer pat_test", create.getHeader("Authorization"));
        String createBody = create.getBody().readUtf8();
        assertTrue(createBody.contains("content.halo.run/content-json"));
        // content 字段应为渲染后的 HTML，而不是裸 markdown
        assertTrue(createBody.contains("<h1>") || createBody.contains("\\u003ch1\\u003e"));

        RecordedRequest pub = server.takeRequest();
        assertEquals("PUT", pub.getMethod());
        assertTrue(pub.getPath().contains("/posts/post-abc/publish"));
    }

    @Test
    void updateExisting() throws Exception {
        String existing = """
                {"metadata":{"name":"post-abc","annotations":{}},"spec":{"title":"Old","slug":"old"}}
                """;
        String draft = """
                {"metadata":{"name":"snap","annotations":{}},"spec":{}}
                """;
        String published = """
                {"metadata":{"name":"post-abc"},"status":{"permalink":"/archives/new"}}
                """;
        server.enqueue(new MockResponse().setBody(existing).addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody(existing).addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody(draft).addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody(draft).addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody(published).addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody(published).addHeader("Content-Type", "application/json"));

        HaloPublishResult r = adapter.publish(
                new HaloPublishCommand("New", "new", "<p>x</p>", "HTML", "post-abc"));
        assertEquals("post-abc", r.postName());
        assertEquals("https://blog.example.com/archives/new", r.publicUrl());

        assertEquals("GET", server.takeRequest().getMethod());
        assertEquals("PUT", server.takeRequest().getMethod());
        RecordedRequest draftGet = server.takeRequest();
        assertTrue(draftGet.getPath().contains("/draft"));
        assertEquals("PUT", server.takeRequest().getMethod());
    }

    @Test
    void unauthorized() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("no"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.publish(new HaloPublishCommand("T", "t", "b", "markdown", null)));
        assertEquals(502, ex.getCode());
        assertTrue(ex.getMessage().contains("鉴权"));
    }

    @Test
    void disabled() {
        DisabledHaloPublishAdapter off = new DisabledHaloPublishAdapter();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> off.publish(new HaloPublishCommand("T", "t", "b", "markdown", null)));
        assertEquals(503, ex.getCode());
    }
}
