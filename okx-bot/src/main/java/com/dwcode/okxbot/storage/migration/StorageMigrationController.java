package com.dwcode.okxbot.storage.migration;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.common.response.ApiResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 旧本地媒体 → 对象存储迁移（仅 SUPER_ADMIN，见 SecurityConfig /api/admin/**）。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/storage")
@RequiredArgsConstructor
public class StorageMigrationController {

    private final LegacyMediaMigrationService migrationService;
    private final ObjectMapper objectMapper;

    /**
     * 执行迁移。默认 dryRun=true，请先 dry-run 再 deleteLocal。
     *
     * <p>手动读 body 再按 JSON 解析，兼容 PowerShell 默认的
     * {@code Content-Type: text/plain;charset=UTF-8}，以及无 body / application/json。
     *
     * <pre>
     * POST /api/admin/storage/migrate
     * { "dryRun": true, "modules": ["video"], "limitPerModule": 10 }
     * { "dryRun": false, "deleteLocal": false, "modules": ["video","aigen","imggen"] }
     * </pre>
     */
    @PostMapping("/migrate")
    public ApiResult<MigrationReport> migrate(HttpServletRequest httpRequest) throws IOException {
        MigrationRequest request = parseRequest(readBody(httpRequest));
        boolean dryRun = request.getDryRun() == null || request.getDryRun();
        boolean deleteLocal = Boolean.TRUE.equals(request.getDeleteLocal());
        int limit = request.getLimitPerModule() != null ? request.getLimitPerModule() : 0;

        log.info("管理员触发媒体迁移: dryRun={}, deleteLocal={}, modules={}, limit={}, taskId={}",
                dryRun, deleteLocal, request.getModules(), limit, request.getTaskId());

        MigrationOptions options = MigrationOptions.builder()
                .modules(request.getModules())
                .dryRun(dryRun)
                .deleteLocal(deleteLocal)
                .limitPerModule(limit)
                .taskId(request.getTaskId())
                .build();
        return ApiResult.ok(migrationService.migrate(options));
    }

    private static String readBody(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readAllBytes();
        if (bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private MigrationRequest parseRequest(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return new MigrationRequest();
        }
        try {
            MigrationRequest req = objectMapper.readValue(rawBody.trim(), MigrationRequest.class);
            return req != null ? req : new MigrationRequest();
        } catch (Exception e) {
            throw new BusinessException(400,
                    "请求体不是合法 JSON。请设置 Content-Type: application/json，body 示例: "
                            + "{\"dryRun\":true,\"modules\":[\"video\"],\"limitPerModule\":5}");
        }
    }
}
