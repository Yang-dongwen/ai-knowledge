package com.dwcode.okxbot.blog.controller;

import com.dwcode.okxbot.blog.dto.HaloBindingRequest;
import com.dwcode.okxbot.blog.dto.HaloBindingResponse;
import com.dwcode.okxbot.blog.service.HaloBindingService;
import com.dwcode.okxbot.common.response.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/kb/blog/binding")
@RequiredArgsConstructor
public class HaloBindingController {

    private final HaloBindingService bindingService;

    @GetMapping
    public ApiResult<HaloBindingResponse> get() {
        return ApiResult.ok(bindingService.current());
    }

    @PutMapping
    public ApiResult<HaloBindingResponse> save(@Valid @RequestBody HaloBindingRequest request) {
        return ApiResult.ok(bindingService.save(request));
    }

    @DeleteMapping
    public ApiResult<Void> delete() {
        bindingService.delete();
        return ApiResult.ok();
    }
}
