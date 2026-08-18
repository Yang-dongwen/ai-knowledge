package com.dwcode.okxbot.horizon.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.horizon.dto.HorizonDigestBrief;
import com.dwcode.okxbot.horizon.dto.HorizonDigestRequest;
import com.dwcode.okxbot.horizon.dto.HorizonDigestResponse;
import com.dwcode.okxbot.horizon.dto.HorizonDigestView;
import com.dwcode.okxbot.horizon.service.HorizonFeedService;
import com.dwcode.okxbot.horizon.service.HorizonIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * Horizon 跑完日报后的 webhook 入口。无 JWT，验共享 token。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/horizon")
@RequiredArgsConstructor
public class HorizonDigestController {

    public static final String TOKEN_HEADER = "X-Horizon-Token";

    private final HorizonIngestService ingestService;
    private final HorizonFeedService feedService;

    /** 登录用户可读：最新一篇日报（无稿时 data=null） */
    @GetMapping("/latest")
    public ApiResult<HorizonDigestView> latest(
            @RequestParam(defaultValue = "zh") String lang,
            @RequestParam(required = false) String date,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResult.ok(ingestService.latest(lang, date));
    }

    /** Halo Cosolar「资讯」订阅：公开 RSS，无 JWT。 */
    @GetMapping(value = "/feed.xml", produces = MediaType.APPLICATION_RSS_XML_VALUE)
    public ResponseEntity<String> feed(@RequestParam(defaultValue = "zh") String lang) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_RSS_XML)
                .body(feedService.rss(lang));
    }

    /** 登录用户可读：近日日报列表（不含正文） */
    @GetMapping("/recent")
    public ApiResult<List<HorizonDigestBrief>> recent(
            @RequestParam(defaultValue = "zh") String lang,
            @RequestParam(defaultValue = "7") int limit,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResult.ok(ingestService.recent(lang, limit));
    }

    @PostMapping("/digest")
    public ApiResult<HorizonDigestResponse> digest(
            @RequestHeader(value = TOKEN_HEADER, required = false) String horizonToken,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) HorizonDigestRequest request) {
        String token = firstNonBlank(horizonToken, bearer(authorization));
        HorizonDigestResponse resp = ingestService.ingest(token, request);
        log.info("horizon digest ingested noteId={} created={} published={}",
                resp.getNoteId(), resp.isCreated(), resp.isPublished());
        return ApiResult.ok(resp);
    }

    private static String bearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String t = authorization.substring(7).trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b;
    }
}
