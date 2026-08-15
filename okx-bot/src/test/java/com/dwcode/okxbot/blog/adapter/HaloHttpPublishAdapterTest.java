package com.dwcode.okxbot.blog.adapter;

import com.dwcode.okxbot.blog.config.HaloProperties;
import com.dwcode.okxbot.blog.port.HaloAttachment;
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

        HaloPublishResult r = adapter.publish(new HaloPublishCommand("Hi", "hi", "# hi", "markdown", null,
                java.util.List.of(), java.util.List.of(), "https://blog.example.com/upload/a.png"));
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
        assertTrue(createBody.contains("https://blog.example.com/upload/a.png"));

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
                new HaloPublishCommand("New", "new", "<p>x</p>", "HTML", "post-abc",
                        java.util.List.of(), java.util.List.of()));
        assertEquals("post-abc", r.postName());
        assertEquals("https://blog.example.com/archives/new", r.publicUrl());

        assertEquals("GET", server.takeRequest().getMethod());
        assertEquals("PUT", server.takeRequest().getMethod());
        RecordedRequest draftGet = server.takeRequest();
        assertTrue(draftGet.getPath().contains("/draft"));
        assertEquals("PUT", server.takeRequest().getMethod());
    }

    @Test
    void createWithCategoryAndTag() throws Exception {
        String cats = """
                {"items":[{"metadata":{"name":"cat-1"},"spec":{"displayName":"技术"}}]}
                """;
        String tags = """
                {"items":[]}
                """;
        String createdTag = """
                {"metadata":{"name":"tag-new"},"spec":{"displayName":"Java"}}
                """;
        String created = """
                {"metadata":{"name":"post-abc"},"spec":{"title":"Hi"},"status":{}}
                """;
        String published = """
                {"metadata":{"name":"post-abc"},"spec":{"title":"Hi"},"status":{"permalink":"/archives/hi"}}
                """;
        server.enqueue(new MockResponse().setBody(cats).addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody(tags).addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody(createdTag).addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody(created).addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody(published).addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody(published).addHeader("Content-Type", "application/json"));

        adapter.publish(new HaloPublishCommand("Hi", "hi", "# hi", "markdown", null,
                java.util.List.of("技术"), java.util.List.of("Java")));

        assertTrue(server.takeRequest().getPath().contains("/categories"));
        assertTrue(server.takeRequest().getPath().contains("/tags"));
        RecordedRequest createTag = server.takeRequest();
        assertEquals("POST", createTag.getMethod());
        assertTrue(createTag.getPath().contains("/tags"));
        RecordedRequest create = server.takeRequest();
        String createBody = create.getBody().readUtf8();
        assertTrue(createBody.contains("cat-1"));
        assertTrue(createBody.contains("tag-new"));
    }

    @Test
    void uploadAttachment() throws Exception {
        String att = """
                {"metadata":{"name":"att-1"},"status":{"permalink":"/upload/a.png"}}
                """;
        server.enqueue(new MockResponse().setBody(att).addHeader("Content-Type", "application/json"));
        HaloAttachment r = adapter.upload("img".getBytes(), "a.png", "image/png");
        assertEquals("att-1", r.name());
        assertEquals("https://blog.example.com/upload/a.png", r.permalink());
        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertTrue(req.getPath().contains("/attachments"));
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
