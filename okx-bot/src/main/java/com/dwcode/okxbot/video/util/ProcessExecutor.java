package com.dwcode.okxbot.video.util;

import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 外部进程执行工具（yt-dlp / FFmpeg 等）。
 *
 * 职责：统一超时、日志、退出码校验，避免业务层直接操作 ProcessBuilder。
 * Windows 下会自动解析 PATH / 补全 .exe，减轻 IDE 与 CMD 环境不一致问题。
 */
@Slf4j
@Component
public class ProcessExecutor {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /**
     * 执行外部命令。
     *
     * @param command        完整命令参数列表（含可执行文件）
     * @param timeoutSeconds 超时秒数
     * @return 标准输出 + 标准错误合并文本
     */
    public String execute(List<String> command, int timeoutSeconds) {
        if (command == null || command.isEmpty()) {
            throw new BusinessException("外部命令为空");
        }

        List<String> resolved = new ArrayList<>(command);
        String executable = resolveExecutable(resolved.get(0));
        resolved.set(0, executable);

        log.info("执行外部命令: {}", String.join(" ", resolved));
        ProcessBuilder pb = new ProcessBuilder(resolved);
        pb.redirectErrorStream(true);

        Process process = null;
        try {
            process = pb.start();
            String output = readFully(process);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException("外部命令执行超时（" + timeoutSeconds + "s）: " + executable);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("外部命令失败 exitCode={}, output={}", exitCode, truncate(output, 2000));
                throw new BusinessException("外部命令失败（exit=" + exitCode + "）: " + executable
                        + " — " + truncate(output, 500));
            }

            log.debug("外部命令成功: {}, outputLen={}", executable, output.length());
            return output;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException("无法启动外部命令 " + executable
                    + "，请确认已安装并在 application.yml 配置 video.yt-dlp-path / video.ffmpeg-path 绝对路径: "
                    + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new BusinessException("外部命令被中断: " + executable);
        }
    }

    /**
     * 检查可执行文件是否可用（--version）。
     */
    public boolean isAvailable(String executable) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(executable);
            cmd.add("--version");
            execute(cmd, 15);
            return true;
        } catch (Exception e) {
            log.warn("可执行文件不可用: {} — {}", executable, e.getMessage());
            return false;
        }
    }

    /**
     * 将命令名解析为可执行绝对路径（若已是绝对路径且存在则直接返回）。
     * <p>
     * 典型场景：CMD 能跑 yt-dlp，但 IDEA 启动的 JVM 未刷新 PATH → CreateProcess error=2。
     */
    String resolveExecutable(String command) {
        if (command == null || command.isBlank()) {
            return command;
        }

        Path asPath = Path.of(command);
        if (asPath.isAbsolute() && Files.isRegularFile(asPath)) {
            return asPath.toAbsolutePath().normalize().toString();
        }
        if (Files.isRegularFile(asPath)) {
            return asPath.toAbsolutePath().normalize().toString();
        }

        // 相对名：在 PATH 中查找，Windows 补全 .exe/.cmd/.bat
        List<String> candidates = new ArrayList<>();
        candidates.add(command);
        if (IS_WINDOWS) {
            String lower = command.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".exe") && !lower.endsWith(".cmd") && !lower.endsWith(".bat")) {
                candidates.add(command + ".exe");
                candidates.add(command + ".cmd");
                candidates.add(command + ".bat");
            }
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isBlank()) {
            String[] dirs = pathEnv.split(IS_WINDOWS ? ";" : ":");
            for (String dir : dirs) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                for (String name : candidates) {
                    Path candidate = Path.of(dir.trim(), name);
                    if (Files.isRegularFile(candidate)) {
                        String resolved = candidate.toAbsolutePath().normalize().toString();
                        log.info("已解析外部命令: {} -> {}", command, resolved);
                        return resolved;
                    }
                }
            }
        }

        // 找不到仍返回原值，由 ProcessBuilder 再试一次（便于报错信息一致）
        return command;
    }

    private String readFully(Process process) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (log.isDebugEnabled()) {
                    log.debug("[proc] {}", line);
                }
            }
        }
        return sb.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
