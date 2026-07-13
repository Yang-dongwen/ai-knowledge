package com.dwcode.okxbot.aigen.adapter.render;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可选托管 aigen-remotion Node 进程：
 * <ul>
 *   <li>随 Spring Boot 启动自动拉起</li>
 *   <li>渲染前 ensureRunning（按需补拉）</li>
 *   <li>应用关闭时一并停止（仅停止本管理器拉起的子进程）</li>
 * </ul>
 * 若端口上已有健康服务（手动启动），则不重复拉起，关闭时也不杀外部进程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemotionProcessManager {

    private final AigenProperties aigenProperties;
    private final VideoProperties videoProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Object lock = new Object();
    private Process process;
    private volatile boolean startedByUs;
    private final AtomicBoolean logPumpStarted = new AtomicBoolean(false);

    private final OkHttpClient healthClient = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build();

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        AigenProperties.Remotion cfg = aigenProperties.getRemotion();
        if (!shouldManage()) {
            log.debug("Remotion 进程托管未启用（manage-process=false 或渲染为 mock）");
            return;
        }
        if (!cfg.isAutoStartOnBoot()) {
            log.info("Remotion 托管已开启，但 auto-start-on-boot=false，将在首次渲染时按需启动");
            return;
        }
        try {
            // 启动时强制刷新：避免旧 Node 进程仍占 3100 且无 audioHttp 能力
            ensureRunning(true);
        } catch (Exception e) {
            log.warn("启动时拉起 aigen-remotion 失败（首次渲染时会重试）: {}", e.getMessage());
        }
    }

    public void ensureRunning() {
        ensureRunning(false);
    }

    /**
     * @param forceRestart 为 true 时，若健康但不支持 audioHttp，或本管理器未托管，则杀端口重建
     */
    public void ensureRunning(boolean forceRestart) {
        if (!shouldManage()) {
            if (!isHealthy()) {
                throw new BusinessException(
                        "渲染服务不可用: " + healthUrl()
                                + "。请手动启动 aigen-remotion，或设置 aigen.remotion.manage-process=true");
            }
            if (!supportsAudioHttp()) {
                throw new BusinessException(
                        "渲染服务版本过旧（无 audioHttp）。请停掉 3100 端口旧进程后重启 okx-bot，"
                                + "或手动重新 npm run render-server");
            }
            return;
        }
        synchronized (lock) {
            boolean healthy = isHealthy();
            boolean audioOk = healthy && supportsAudioHttp();
            if (healthy && audioOk && !forceRestart) {
                return;
            }
            if (healthy && (!audioOk || forceRestart)) {
                log.warn("检测到需刷新的 remotion（audioHttp={} force={}），正在重启…",
                        audioOk, forceRestart);
                stopManagedProcess();
                killPortListeners(parsePort(aigenProperties.getRemotion().getBaseUrl()));
                // 等端口释放
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (process != null && !process.isAlive()) {
                process = null;
                startedByUs = false;
            }
            if (process == null || !process.isAlive()) {
                startProcess();
            }
            waitUntilHealthy();
            if (!supportsAudioHttp()) {
                throw new BusinessException(
                        "新启动的 remotion 仍无 audioHttp 标记，请确认 aigen-remotion/server/index.mjs 已更新");
            }
        }
    }

    public boolean isHealthy() {
        return fetchHealthJson() != null;
    }

    /** 新版 health 带 audioHttp=true */
    public boolean supportsAudioHttp() {
        try {
            JsonNode n = fetchHealthJson();
            return n != null && n.path("audioHttp").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode fetchHealthJson() {
        String url = healthUrl();
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = healthClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                return null;
            }
            return objectMapper.readTree(resp.body().string());
        } catch (Exception e) {
            return null;
        }
    }

    private void stopManagedProcess() {
        if (process != null && process.isAlive()) {
            try {
                process.destroy();
                process.waitFor(5, TimeUnit.SECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (Exception e) {
                log.debug("stop managed remotion: {}", e.getMessage());
            }
        }
        process = null;
        startedByUs = false;
    }

    /** 尽量释放端口（Windows taskkill / 本进程 destroy） */
    private void killPortListeners(int port) {
        try {
            if (isWindows()) {
                ProcessBuilder pb = new ProcessBuilder(
                        "cmd.exe", "/c",
                        "for /f \"tokens=5\" %a in ('netstat -ano ^| findstr :" + port
                                + " ^| findstr LISTENING') do taskkill /F /PID %a"
                );
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor(8, TimeUnit.SECONDS);
                log.info("已尝试释放端口 {}", port);
            } else {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                        "fuser -k " + port + "/tcp || true");
                pb.start().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("释放端口 {} 失败（可手动结束 node 进程）: {}", port, e.getMessage());
        }
    }

    private boolean shouldManage() {
        if (aigenProperties.isMockPipeline()) {
            return false;
        }
        if ("mock".equalsIgnoreCase(aigenProperties.getSteps().getRender())) {
            return false;
        }
        return aigenProperties.getRemotion().isManageProcess();
    }

    private void startProcess() {
        AigenProperties.Remotion cfg = aigenProperties.getRemotion();
        Path projectDir = resolveProjectDir(cfg.getProjectDir());
        Path entry = projectDir.resolve("server").resolve("index.mjs");
        if (!Files.isRegularFile(entry)) {
            throw new BusinessException(
                    "找不到 aigen-remotion 入口: " + entry
                            + "。请检查 aigen.remotion.project-dir，并先在该目录执行 npm install");
        }

        String node = resolveNodeExecutable(cfg.getNodePath());
        List<String> cmd = new ArrayList<>();
        if (isWindows()) {
            // 保证能解析 node.cmd / 环境 PATH
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add(node);
            cmd.add("server/index.mjs");
        } else {
            cmd.add(node);
            cmd.add("server/index.mjs");
        }

        int port = parsePort(cfg.getBaseUrl());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("PORT", String.valueOf(port));
        pb.environment().put("HOST", "127.0.0.1");
        if (cfg.getRenderToken() != null && !cfg.getRenderToken().isBlank()) {
            pb.environment().put("AIGEN_RENDER_TOKEN", cfg.getRenderToken());
        }
        // 任务目录在 work-dir/{taskId} 下，允许根即 aigen.work-dir
        Path workRoot = Path.of(aigenProperties.getWorkDir()).toAbsolutePath().normalize();
        pb.environment().put("ALLOWED_WORK_ROOT", workRoot.toString());
        // 成片旁白混音依赖 ffmpeg（修复 Remotion 静音轨）
        String ffmpeg = resolveFfmpegPath();
        if (ffmpeg != null) {
            pb.environment().put("FFMPEG_PATH", ffmpeg);
            log.info("aigen-remotion 将使用 FFMPEG_PATH={}", ffmpeg);
        } else {
            log.warn("未配置 ffmpeg 路径，成片可能仍无旁白声（请配置 video.ffmpeg-path）");
        }

        log.info("正在启动 aigen-remotion: dir={}, cmd={}, port={}", projectDir, cmd, port);
        try {
            process = pb.start();
            startedByUs = true;
            pumpLogs(process);
        } catch (Exception e) {
            process = null;
            startedByUs = false;
            throw new BusinessException("启动 aigen-remotion 失败: " + e.getMessage()
                    + "。请确认已安装 Node.js，并在 aigen-remotion 目录执行过 npm install");
        }
    }

    private void waitUntilHealthy() {
        AigenProperties.Remotion cfg = aigenProperties.getRemotion();
        int timeoutSec = Math.max(10, cfg.getStartupTimeoutSeconds());
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (process != null && !process.isAlive()) {
                int code = process.exitValue();
                process = null;
                startedByUs = false;
                throw new BusinessException(
                        "aigen-remotion 进程启动后立即退出，exitCode=" + code
                                + "。请检查 Node 依赖与端口占用，或查看日志中 [aigen-remotion] 输出");
            }
            if (isHealthy()) {
                log.info("aigen-remotion 已就绪: {}", healthUrl());
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("等待 aigen-remotion 就绪时被中断");
            }
        }
        throw new BusinessException(
                "等待 aigen-remotion 就绪超时（" + timeoutSec + "s）: " + healthUrl()
                        + "。首次启动可能较慢（webpack bundle），可增大 aigen.remotion.startup-timeout-seconds");
    }

    private void pumpLogs(Process p) {
        if (!logPumpStarted.compareAndSet(false, true) && process != p) {
            // 允许多次重启时再开泵
            logPumpStarted.set(true);
        }
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.info("[aigen-remotion] {}", line);
                }
            } catch (Exception e) {
                log.debug("remotion 日志泵结束: {}", e.getMessage());
            } finally {
                logPumpStarted.set(false);
            }
        }, "aigen-remotion-log");
        t.setDaemon(true);
        t.start();
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lock) {
            if (!startedByUs || process == null) {
                log.debug("无需停止 aigen-remotion（非本进程托管）");
                return;
            }
            if (!process.isAlive()) {
                process = null;
                startedByUs = false;
                return;
            }
            log.info("正在停止托管的 aigen-remotion 进程 (pid={})…", process.pid());
            try {
                process.destroy();
                boolean exited = process.waitFor(8, TimeUnit.SECONDS);
                if (!exited && process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(3, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.warn("停止 aigen-remotion 异常: {}", e.getMessage());
                try {
                    process.destroyForcibly();
                } catch (Exception ignored) {
                    // ignore
                }
            } finally {
                process = null;
                startedByUs = false;
                log.info("aigen-remotion 托管进程已停止");
            }
        }
    }

    private Path resolveProjectDir(String configured) {
        String raw = configured != null && !configured.isBlank()
                ? configured
                : "../aigen-remotion";
        Path p = Path.of(raw);
        if (!p.isAbsolute()) {
            // 相对 okx-bot 工作目录（通常为模块根或仓库根）
            p = Path.of("").toAbsolutePath().resolve(raw).normalize();
            Path alt = Path.of("").toAbsolutePath().getParent() != null
                    ? Path.of("").toAbsolutePath().getParent().resolve("aigen-remotion").normalize()
                    : null;
            if (!Files.isDirectory(p) && alt != null && Files.isDirectory(alt)) {
                p = alt;
            }
            // 再试：仓库根 / aigen-remotion（从 okx-bot 子目录启动）
            Path fromOkxBot = Path.of("").toAbsolutePath().resolve("..").resolve("aigen-remotion").normalize();
            if (!Files.isDirectory(p) && Files.isDirectory(fromOkxBot)) {
                p = fromOkxBot;
            }
        }
        return p.toAbsolutePath().normalize();
    }

    private static String resolveNodeExecutable(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return "node";
    }

    /**
     * 优先 aigen.tts.ffmpeg-path，其次 video.ffmpeg-path。
     */
    private String resolveFfmpegPath() {
        String p = aigenProperties.getTts() != null ? aigenProperties.getTts().getFfmpegPath() : null;
        if (p == null || p.isBlank()) {
            p = videoProperties != null ? videoProperties.getFfmpegPath() : null;
        }
        if (p == null || p.isBlank()) {
            return null;
        }
        Path path = Path.of(p.trim());
        if (Files.isRegularFile(path)) {
            return path.toAbsolutePath().normalize().toString();
        }
        return p.trim();
    }

    private String healthUrl() {
        String base = aigenProperties.getRemotion().getBaseUrl();
        if (base == null || base.isBlank()) {
            base = "http://127.0.0.1:3100";
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/health";
    }

    private static int parsePort(String baseUrl) {
        try {
            String u = baseUrl != null ? baseUrl : "http://127.0.0.1:3100";
            int idx = u.lastIndexOf(':');
            if (idx > 0) {
                String portPart = u.substring(idx + 1).replaceAll("/.*", "");
                return Integer.parseInt(portPart);
            }
        } catch (Exception ignored) {
            // fallthrough
        }
        return 3100;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }
}
