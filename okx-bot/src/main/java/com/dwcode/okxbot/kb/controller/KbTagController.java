package com.dwcode.okxbot.kb.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.kb.dto.TagCreateRequest;
import com.dwcode.okxbot.kb.dto.TagResponse;
import com.dwcode.okxbot.kb.dto.TagUpdateRequest;
import com.dwcode.okxbot.kb.service.KbTagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库标签 API。
 */
@RestController
@RequestMapping("/api/v1/kb/tags")
@RequiredArgsConstructor
public class KbTagController {

    private final KbTagService tagService;

    @GetMapping
    public ApiResult<List<TagResponse>> list() {
        return ApiResult.ok(tagService.list());
    }

    @PostMapping
    public ApiResult<TagResponse> create(@Valid @RequestBody TagCreateRequest request) {
        return ApiResult.ok(tagService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResult<TagResponse> update(@PathVariable Long id,
                                         @Valid @RequestBody TagUpdateRequest request) {
        return ApiResult.ok(tagService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return ApiResult.ok();
    }
}
