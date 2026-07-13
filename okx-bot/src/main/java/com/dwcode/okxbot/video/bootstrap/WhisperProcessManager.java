package com.dwcode.okxbot.video.bootstrap;

import com.dwcode.okxbot.video.config.VideoProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 随 Spring Boot 生命周期管理本地 Whisper 微服务进程：
 * 启动时若服务未就绪则拉起 {@code whisper-service/main.py}，关闭时停止本进程拉起的子进程。
 * <p>
 * 若启动前 health 已可用，则视为外部已启动，关闭时不杀进程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhisperProcessManager {

    private final VideoProperties videoProperties;

    private final AtomicBoolean startedByUs = new AtomicBoolean(false);
    private volatile Process process;
    private volatile Thread logPump;

    @EventListener(ApplicationReadyEvent.class)
    @Order(50)
    public void onApplicationReady() {
        VideoProperties.Whisper whisper = videoProperties.getWhisper();
        VideoProperties.WhisperManaged managed = whisper.getManaged();
        if (managed == null || !managed.isEnabled()) {
            log.info("Whisper 托管启动已关闭（video.whisper.managed.enabled=false）");
            return;
        }

        String healthUrl = healthUrl(whisper);
        if (isHealthy(healthUrl)) {
            log.info("Whisper 服务已在运行，跳过托管启动: {}", healthUrl);
            return;
        }

        try {
            startProcess(whisper, managed);
            waitUntilHealthy(healthUrl, managed.getStartupTimeoutSeconds());
            log.info("Whisper 服务已就绪: {}", healthUrl);
        } catch (Exception e) {
            log.error("Whisper 托管启动失败: {}", e.getMessage(), e);
            stopProcessQuietly();
            if (managed.isFailIfUnavailable()) {
                throw new IllegalStateException("Whisper 服务启动失败: " + e.getMessage(), e);
            }
            log.warn("将继续启动应用；转录功能在 Whisper 不可用时会失败。可手动启动 whisper-service 或检查配置。");
        }
    }

    @PreDestroy
    public void onShutdown() {
        VideoProperties.WhisperManaged managed = videoProperties.getWhisper().getManaged();
        if (managed == null || !managed.isEnabled()) {
            return;
        }
        if (!startedByUs.get()) {
            log.debug("Whisper 非本进程启动，关闭时不停止");
            return;
        }
        log.info("正在停止由应用托管的 Whisper 服务…");
        stopProcessQuietly(managed.getStopTimeoutSeconds());
    }

    private void startProcess(VideoProperties.Whisper whisper, VideoProperties.WhisperManaged managed)
            throws IOException {
        Path workDir = resolveWorkDir(managed.getWorkingDir());
        if (!Files.isDirectory(workDir)) {
            throw new IOException("whisper 工作目录不存在: " + workDir.toAbsolutePath()
                    + "（请确认 video.whisper.managed.working-dir）");
        }
        Path mainPy = workDir.resolve("main.py");
        if (!Files.isRegularFile(mainPy)) {
            throw new IOException("未找到 main.py: " + mainPy.toAbsolutePath());
        }

        String python = resolvePython(managed.getPythonPath(), workDir);
        int port = resolvePort(whisper.getBaseUrl(), managed.getPort());
        String host = managed.getHost() == null || managed.getHost().isBlank()
                ? "0.0.0.0" : managed.getHost().trim();

        // 默认：python main.py（读 WHISPER_HOST / WHISPER_PORT）
        // 若配置了 extraArgs，改用 uvicorn 模块便于透传参数
        List<String> cmd = new ArrayList<>();
        boolean useUvicornModule = managed.getExtraArgs() != null && !managed.getExtraArgs().isEmpty();
        if (useUvicornModule) {
            cmd.add(python);
            cmd.add("-m");
            cmd.add("uvicorn");
            cmd.add("main:app");
            cmd.add("--host");
            cmd.add(host);
            cmd.add("--port");
            cmd.add(String.valueOf(port));
            cmd.addAll(managed.getExtraArgs());
        } else {
            cmd.add(python);
            cmd.add(mainPy.toAbsolutePath().toString());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        putEnv(env, "WHISPER_MODEL", firstNonBlank(managed.getModel(), whisper.getModel(), "small"));
        putEnv(env, "WHISPER_DEVICE", firstNonBlank(managed.getDevice(), "cpu"));
        putEnv(env, "WHISPER_COMPUTE", firstNonBlank(managed.getCompute(), "int8"));
        putEnv(env, "WHISPER_PRELOAD", managed.isPreload() ? "1" : "0");
        putEnv(env, "WHISPER_HOST", host);
        putEnv(env, "WHISPER_PORT", String.valueOf(port));
        if (managed.getEnv() != null) {
            managed.getEnv().forEach((k, v) -> {
                if (k != null && v != null) {
                    env.put(k, v);
                }
            });
        }

        log.info("启动 Whisper: workDir={}, cmd={}", workDir.toAbsolutePath(), String.join(" ", cmd));
        Process p = pb.start();
        this.process = p;
        startedByUs.set(true);
        startLogPump(p);

        if (!p.isAlive()) {
            throw new IOException("Whisper 进程立即退出，exitCode=" + p.exitValue());
        }
    }

    private void startLogPump(Process p) {
        logPump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[whisper] {}", line);
                }
            } catch (IOException e) {
                if (p.isAlive()) {
                    log.debug("Whisper 日志流结束: {}", e.getMessage());
                }
            }
        }, "whisper-log-pump");
        logPump.setDaemon(true);
        logPump.start();
    }

    private void waitUntilHealthy(String healthUrl, int timeoutSeconds) throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + Math.max(5, timeoutSeconds) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Process p = this.process;
            if (p != null && !p.isAlive()) {
                throw new IOException("Whisper 进程已退出，exitCode=" + p.exitValue());
            }
            if (isHealthy(healthUrl)) {
                return;
            }
            Thread.sleep(1500L);
        }
        throw new IOException("等待 Whisper 健康检查超时（" + timeoutSeconds + "s）: " + healthUrl);
    }

    private void stopProcessQuietly() {
        stopProcessQuietly(15);
    }

    private boolean isHealthy(String healthUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = URI.create(healthUrl).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void stopProcessQuietly(int stopTimeoutSeconds) {
        Process p = this.process;
        if (p == null) {
            return;
        }
        try {
            long pid = -1;
            try {
                pid = p.pid();
            } catch (Exception ignored) {
                // ignore
            }
            // 先尝试优雅结束
            p.destroy();
            boolean exited = p.waitFor(Math.max(3, stopTimeoutSeconds), TimeUnit.SECONDS);
            if (!exited) {
                log.warn("Whisper 未在 {}s 内退出，强制结束", stopTimeoutSeconds);
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            // Windows 上可能残留子进程：对整棵进程树再杀一次
            if (pid > 0 && isWindows()) {
                killWindowsProcessTree(pid);
            }
            log.info("Whisper 进程已停止{}", pid > 0 ? " (pid=" + pid + ")" : "");
        } catch (Exception e) {
            log.warn("停止 Whisper 异常: {}", e.getMessage());
            try {
                p.destroyForcibly();
            } catch (Exception ignored) {
                // ignore
            }
        } finally {
            this.process = null;
            startedByUs.set(false);
            if (logPump != null) {
                try {
                    logPump.interrupt();
                } catch (Exception ignored) {
                    // ignore
                }
                logPump = null;
            }
        }
    }

    private static void killWindowsProcessTree(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "cmd", "/c", "taskkill", "/PID", String.valueOf(pid), "/T", "/F");
            pb.redirectErrorStream(true);
            Process k = pb.start();
            k.waitFor(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            // best-effort
        }
    }

    private static Path resolveWorkDir(String configured) {
        String raw = configured == null || configured.isBlank() ? "./whisper-service" : configured.trim();
        Path p = Paths.get(raw);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir")).resolve(p).normalize();
        }
        return p;
    }

    private static String resolvePython(String configured, Path workDir) throws IOException {
        if (configured != null && !configured.isBlank()) {
            Path cp = Paths.get(configured.trim());
            if (Files.isRegularFile(cp) || Files.isExecutable(cp)) {
                return cp.toAbsolutePath().toString();
            }
            // 可能是 PATH 中的命令名
            return configured.trim();
        }
        Path win = workDir.resolve(".venv/Scripts/python.exe");
        if (Files.isRegularFile(win)) {
            return win.toAbsolutePath().toString();
        }
        Path unix = workDir.resolve(".venv/bin/python");
        if (Files.isRegularFile(unix)) {
            return unix.toAbsolutePath().toString();
        }
        Path unix3 = workDir.resolve(".venv/bin/python3");
        if (Files.isRegularFile(unix3)) {
            return unix3.toAbsolutePath().toString();
        }
        // 回退：系统 python
        return isWindows() ? "python" : "python3";
    }

    private static int resolvePort(String baseUrl, Integer configuredPort) {
        if (configuredPort != null && configuredPort > 0) {
            return configuredPort;
        }
        try {
            URI uri = URI.create(baseUrl == null ? "http://127.0.0.1:8000" : baseUrl.trim());
            int p = uri.getPort();
            return p > 0 ? p : 8000;
        } catch (Exception e) {
            return 8000;
        }
    }

    private static String healthUrl(VideoProperties.Whisper whisper) {
        String base = whisper.getBaseUrl() == null ? "http://127.0.0.1:8000" : whisper.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = whisper.getManaged() != null && whisper.getManaged().getHealthPath() != null
                ? whisper.getManaged().getHealthPath().trim()
                : "/health";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path;
    }

    private static void putEnv(Map<String, String> env, String key, String value) {
        if (value != null && !value.isBlank()) {
            env.put(key, value);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }
}
