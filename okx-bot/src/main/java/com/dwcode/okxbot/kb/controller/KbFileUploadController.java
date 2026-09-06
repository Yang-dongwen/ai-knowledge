package com.dwcode.okxbot.kb.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.kb.dto.FileResponse;
import com.dwcode.okxbot.kb.dto.FileUploadInitRequest;
import com.dwcode.okxbot.kb.dto.FileUploadPartResponse;
import com.dwcode.okxbot.kb.dto.FileUploadSessionResponse;
import com.dwcode.okxbot.kb.service.KbFileUploadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 知识库附件分片上传。
 */
@Validated
@RestController
@RequestMapping("/api/v1/kb/files/uploads")
@RequiredArgsConstructor
public class KbFileUploadController {

    private final KbFileUploadService uploadService;

    @PostMapping
    public ApiResult<FileUploadSessionResponse> init(@Valid @RequestBody FileUploadInitRequest request) {
        return ApiResult.ok(uploadService.init(request));
    }

    @GetMapping
    public ApiResult<List<FileUploadSessionResponse>> list(
            @RequestParam(required = false) Long noteId,
            @RequestParam(defaultValue = "false") boolean unbound) {
        return ApiResult.ok(uploadService.listPending(noteId, unbound));
    }

    @GetMapping("/{uploadId}")
    public ApiResult<FileUploadSessionResponse> get(@PathVariable Long uploadId) {
        return ApiResult.ok(uploadService.get(uploadId));
    }

    @PutMapping(
            value = "/{uploadId}/parts/{partNumber}",
            consumes = {MediaType.APPLICATION_OCTET_STREAM_VALUE, MediaType.ALL_VALUE})
    public ApiResult<FileUploadPartResponse> putPart(
            @PathVariable Long uploadId,
            @PathVariable @Min(1) int partNumber,
            HttpServletRequest request) throws IOException {
        Long contentLength = request.getContentLengthLong() >= 0 ? request.getContentLengthLong() : null;
        return ApiResult.ok(uploadService.putPart(uploadId, partNumber, request.getInputStream(), contentLength));
    }

    @PostMapping("/{uploadId}/complete")
    public ApiResult<FileResponse> complete(@PathVariable Long uploadId) {
        return ApiResult.ok(uploadService.complete(uploadId));
    }

    @DeleteMapping("/{uploadId}")
    public ApiResult<Void> abort(@PathVariable Long uploadId) {
        uploadService.abort(uploadId);
        return ApiResult.ok();
    }
}
