package com.dwcode.okxbot.kb.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.kb.dto.ShareStatusResponse;
import com.dwcode.okxbot.kb.service.KbShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 笔记分享管理（需登录）。
 */
@RestController
@RequestMapping("/api/v1/kb/notes/{noteId}/share")
@RequiredArgsConstructor
public class KbShareController {

    private final KbShareService shareService;

    @GetMapping
    public ApiResult<ShareStatusResponse> status(@PathVariable Long noteId) {
        return ApiResult.ok(shareService.status(noteId));
    }

    /** 开启分享（无 token 则生成） */
    @PostMapping
    public ApiResult<ShareStatusResponse> enable(@PathVariable Long noteId) {
        return ApiResult.ok(shareService.enable(noteId));
    }

    /** 关闭分享（保留 token，再次开启可复用或再 rotate） */
    @DeleteMapping
    public ApiResult<ShareStatusResponse> disable(@PathVariable Long noteId) {
        return ApiResult.ok(shareService.disable(noteId));
    }

    /** 重置链接（旧链接立即失效） */
    @PostMapping("/rotate")
    public ApiResult<ShareStatusResponse> rotate(@PathVariable Long noteId) {
        return ApiResult.ok(shareService.rotate(noteId));
    }
}
