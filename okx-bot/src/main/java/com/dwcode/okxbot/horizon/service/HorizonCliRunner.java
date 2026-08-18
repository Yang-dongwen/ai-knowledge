package com.dwcode.okxbot.horizon.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 在上一级 Horizon 仓库执行 {@code uv run horizon}。
 */
@Slf4j
@Component
public class HorizonCliRunner {

    public static final int HOURLY_HOURS = 24;
    public static final int STARTUP_HOURS = 24;
    static final int TIMEOUT_SECONDS = 1200;

    public Path resolveCliDir() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path cursor = cwd;
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path[] candidates = {
                    cursor.resolve("Horizon"),
                    cursor.getParent() != null ? cursor.getParent().resolve("Horizon") : null
            };
            for (Path raw : candidates) {
                if (raw != null && isHorizonRoot(raw.normalize())) {
                    return raw.normalize();
                }
            }
            cursor = cursor.getParent();
        }
        throw new BusinessException(503, "未找到 Horizon 仓库（从 " + cwd + " 向上找）");
    }

    static boolean isHorizonRoot(Path dir) {
        return Files.isDirectory(dir)
                && Files.isRegularFile(dir.resolve("pyproject.toml"))
                && Files.isDirectory(dir.resolve("src"));
    }

    List<String> command(int hours) {
        List<String> cmd = new ArrayList<>();
        if (isWindows()) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add("uv");
        cmd.add("run");
        cmd.add("horizon");
        cmd.add("--hours");
        cmd.add(String.valueOf(Math.max(hours, 1)));
        cmd.add("--log-level");
        cmd.add("INFO");
        return cmd;
    }

    public int run() throws Exception {
        return run(HOURLY_HOURS);
    }

    public int run(int hours) throws Exception {
        Path dir = resolveCliDir();
        List<String> cmd = command(hours);
        log.info("horizon cli start cwd={} cmd={}", dir, cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        Thread drain = new Thread(() -> drain(process.getInputStream()), "horizon-cli-drain");
        drain.setDaemon(true);
        drain.start();
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new BusinessException(504, "Horizon 运行超时（" + TIMEOUT_SECONDS + "s）");
        }
        drain.join(5_000);
        int code = process.exitValue();
        log.info("horizon cli exit={} cwd={}", code, dir);
        if (code != 0) {
            throw new BusinessException(502, "Horizon 退出码 " + code);
        }
        return code;
    }

    private static void drain(InputStream in) {
        try (in) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // 进程结束时流关闭
        }
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\' || System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
