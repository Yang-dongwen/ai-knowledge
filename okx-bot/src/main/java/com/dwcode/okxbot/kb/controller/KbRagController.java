package com.dwcode.okxbot.kb.controller;

import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.rag.index.KbIndexWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/kb/rag")
@RequiredArgsConstructor
public class KbRagController {

    private final KbIndexWorker indexWorker;

    @PostMapping("/reindex")
    public ApiResult<Map<String, Integer>> reindex() {
        Long userId = SecurityUtils.requireCurrentUserId();
        int n = indexWorker.rebuildUser(userId);
        return ApiResult.ok(Map.of("enqueued", n));
    }
}
