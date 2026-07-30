package com.dwcode.okxbot.kb.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.kb.dto.PublicNoteResponse;
import com.dwcode.okxbot.kb.service.KbShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开分享阅读（无需登录）。
 */
@RestController
@RequestMapping("/api/v1/kb/public/s")
@RequiredArgsConstructor
public class KbPublicShareController {

    private final KbShareService shareService;

    @GetMapping("/{token}")
    public ApiResult<PublicNoteResponse> get(@PathVariable String token) {
        return ApiResult.ok(shareService.getPublicByToken(token));
    }

    @GetMapping("/{token}/files/{fileId}/content")
    public ResponseEntity<InputStreamResource> file(
            @PathVariable String token,
            @PathVariable Long fileId) {
        return shareService.streamPublicFile(token, fileId);
    }
}
