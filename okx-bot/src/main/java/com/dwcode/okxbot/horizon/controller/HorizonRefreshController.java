package com.dwcode.okxbot.horizon.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.horizon.dto.HorizonRefreshStatus;
import com.dwcode.okxbot.horizon.service.HorizonCliRunner;
import com.dwcode.okxbot.horizon.service.HorizonRefreshService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 超管手动触发小时刷新；登录用户可看状态。
 */
@RestController
@RequestMapping("/api/v1/horizon")
@RequiredArgsConstructor
public class HorizonRefreshController {

    private final HorizonRefreshService refreshService;

    @GetMapping("/refresh/status")
    public ApiResult<HorizonRefreshStatus> status() {
        return ApiResult.ok(refreshService.status());
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<HorizonRefreshStatus> refresh() {
        return ApiResult.ok(refreshService.refresh(true, HorizonCliRunner.STARTUP_HOURS));
    }

    @PostMapping("/publish")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<HorizonRefreshStatus> publish(@RequestParam(required = false) String date) {
        return ApiResult.ok(refreshService.publishToBlog(date));
    }
}
