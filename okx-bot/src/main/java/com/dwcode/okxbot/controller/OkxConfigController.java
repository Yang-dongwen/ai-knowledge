package com.dwcode.okxbot.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.okx.dto.OkxConfigRequest;
import com.dwcode.okxbot.okx.dto.OkxConfigResponse;
import com.dwcode.okxbot.okx.service.OkxConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * OKX 配置接口。
 */
@RestController
@RequestMapping("/api/okx")
@RequiredArgsConstructor
public class OkxConfigController {

    private final OkxConfigService okxConfigService;

    @PostMapping("/config")
    public ApiResult<Void> saveConfig(@Valid @RequestBody OkxConfigRequest request) {
        okxConfigService.saveOkxConfig(request);
        return ApiResult.ok();
    }

    @GetMapping("/config")
    public ApiResult<OkxConfigResponse> getConfig() {
        return ApiResult.ok(okxConfigService.getOkxConfig());
    }

    @PostMapping("/test-connection")
    public ApiResult<String> testConnection() {
        String result = okxConfigService.testConnection();
        return ApiResult.ok(result);
    }

    @GetMapping("/balance")
    public ApiResult<JsonNode> getBalance() {
        return ApiResult.ok(okxConfigService.queryBalance());
    }
}
