package com.dwcode.okxbot.kb.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.kb.dto.CategoryCreateRequest;
import com.dwcode.okxbot.kb.dto.CategoryResponse;
import com.dwcode.okxbot.kb.dto.CategoryUpdateRequest;
import com.dwcode.okxbot.kb.service.KbCategoryService;
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
 * 知识库分类 API。
 */
@RestController
@RequestMapping("/api/v1/kb/categories")
@RequiredArgsConstructor
public class KbCategoryController {

    private final KbCategoryService categoryService;

    @GetMapping
    public ApiResult<List<CategoryResponse>> listTree() {
        return ApiResult.ok(categoryService.listTree());
    }

    @PostMapping
    public ApiResult<CategoryResponse> create(@Valid @RequestBody CategoryCreateRequest request) {
        return ApiResult.ok(categoryService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResult<CategoryResponse> update(@PathVariable Long id,
                                              @Valid @RequestBody CategoryUpdateRequest request) {
        return ApiResult.ok(categoryService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ApiResult.ok();
    }
}
