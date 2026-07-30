package com.dwcode.okxbot.kb.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.kb.dto.FileBindRequest;
import com.dwcode.okxbot.kb.dto.FileResponse;
import com.dwcode.okxbot.kb.service.KbFileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库附件上传 / 列表 / 在线内容流。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/kb/files")
@RequiredArgsConstructor
public class KbFileController {

    private final KbFileService fileService;

    @PostMapping
    public ApiResult<FileResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "noteId", required = false) Long noteId) {
        return ApiResult.ok(fileService.upload(file, noteId));
    }

    @GetMapping
    public ApiResult<List<FileResponse>> list(@RequestParam Long noteId) {
        return ApiResult.ok(fileService.listByNote(noteId));
    }

    @GetMapping("/{id}")
    public ApiResult<FileResponse> meta(@PathVariable Long id) {
        return ApiResult.ok(fileService.getMeta(id));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean download) {
        return fileService.streamContent(id, download);
    }

    @PostMapping("/{id}/bind")
    public ApiResult<FileResponse> bind(@PathVariable Long id,
                                        @Valid @RequestBody FileBindRequest request) {
        return ApiResult.ok(fileService.bind(id, request.getNoteId()));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        fileService.delete(id);
        return ApiResult.ok();
    }
}
