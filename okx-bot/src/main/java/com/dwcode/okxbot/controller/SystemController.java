package com.dwcode.okxbot.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.system.service.SystemStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 系统控制接口。
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemStateService systemStateService;

    @GetMapping("/status")
    public ApiResult<Map<String, String>> getStatus() {
        String status = systemStateService.getSystemStatus();
        return ApiResult.ok(Map.of("status", status));
    }

    @PostMapping("/stop")
    public ApiResult<Void> stop() {
        systemStateService.stopTrading();
        return ApiResult.ok();
    }

    @PostMapping("/resume")
    public ApiResult<Void> resume() {
        systemStateService.resumeTrading();
        return ApiResult.ok();
    }
}
