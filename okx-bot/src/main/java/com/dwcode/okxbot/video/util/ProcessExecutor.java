package com.dwcode.okxbot.video.util;

import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
     * <p>
     * 兼容脏 PATH：如 {@code :\Program Files\Git\cmd}（缺盘符）会导致
     * {@code Illegal char <:> at index 0}，必须跳过而非整段解析失败。
     */
    String resolveExecutable(String command) {
        if (command == null || command.isBlank()) {
            return command;
        }
        String cmd = command.trim();

        try {
            Path asPath = Path.of(cmd);
            if (asPath.isAbsolute() && Files.isRegularFile(asPath)) {
                return asPath.toAbsolutePath().normalize().toString();
            }
            if (Files.isRegularFile(asPath)) {
                return asPath.toAbsolutePath().normalize().toString();
            }
        } catch (InvalidPathException e) {
            log.debug("命令不是合法路径，按 PATH 查找: {} — {}", cmd, e.getMessage());
        }

        // 相对名：在 PATH 中查找，Windows 补全 .exe/.cmd/.bat
        List<String> candidates = new ArrayList<>();
        candidates.add(cmd);
        if (IS_WINDOWS) {
            String lower = cmd.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".exe") && !lower.endsWith(".cmd") && !lower.endsWith(".bat")) {
                candidates.add(cmd + ".exe");
                candidates.add(cmd + ".cmd");
                candidates.add(cmd + ".bat");
            }
        }

        String pathEnv = System.getenv("PATH");
        List<String> found = new ArrayList<>();
        if (pathEnv != null && !pathEnv.isBlank()) {
            // Windows 用 ;，Unix 用 :
            String[] dirs = pathEnv.split(IS_WINDOWS ? ";" : "[:;]");
            for (String dir : dirs) {
                if (dir == null) {
                    continue;
                }
                String d = dir.trim();
                if (d.isEmpty() || !isValidPathDir(d)) {
                    continue;
                }
                // 跳过 Windows Store 占位 python（常无真实解释器）
                if (IS_WINDOWS && d.toLowerCase(Locale.ROOT).contains("\\windowsapps")) {
                    continue;
                }
                for (String name : candidates) {
                    try {
                        Path candidate = Path.of(d, name);
                        if (Files.isRegularFile(candidate)) {
                            found.add(candidate.toAbsolutePath().normalize().toString());
                        }
                    } catch (InvalidPathException ex) {
                        log.debug("跳过非法 PATH 项: dir={}, name={} — {}", d, name, ex.getMessage());
                    }
                }
            }
        }

        if (!found.isEmpty()) {
            String best = pickPreferredExecutable(cmd, found);
            log.info("已解析外部命令: {} -> {}", cmd, best);
            return best;
        }

        // 找不到仍返回原值，由 ProcessBuilder 再试一次（便于报错信息一致）
        return cmd;
    }

    /**
     * 多条 PATH 命中时择优：避开 WindowsApps，python 偏好正式安装目录。
     */
    static String pickPreferredExecutable(String commandName, List<String> found) {
        if (found.size() == 1) {
            return found.get(0);
        }
        String base = commandName == null ? "" : commandName.toLowerCase(Locale.ROOT);
        boolean lookingPython = base.equals("python") || base.equals("python3") || base.equals("py")
                || base.startsWith("python.");
        boolean lookingEdge = base.contains("edge-tts");
        String best = found.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < found.size(); i++) {
            String f = found.get(i);
            int score = 0;
            String lower = f.toLowerCase(Locale.ROOT);
            if (lower.contains("\\windowsapps\\") || lower.contains("/windowsapps/")) {
                score -= 100;
            }
            if (lookingPython) {
                if (lower.contains("\\programs\\python") || lower.contains("/programs/python")) {
                    score += 50;
                }
                if (lower.contains("\\appdata\\local\\programs\\python")) {
                    score += 30;
                }
                if (lower.matches(".*python\\d+.*")) {
                    score += 20;
                }
            }
            if (lookingEdge && (lower.contains("\\scripts\\") || lower.contains("/scripts/"))) {
                score += 40;
            }
            // PATH 靠前略优先
            score -= i;
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        return best;
    }

    /**
     * 过滤损坏的 PATH 段。例：{@code :\Program Files\Git\cmd} 以冒号开头，Java Path 会抛异常。
     */
    static boolean isValidPathDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return false;
        }
        String d = dir.trim();
        // 缺盘符的 Windows 路径
        if (d.startsWith(":\\") || d.startsWith(":/")) {
            return false;
        }
        // 单独的冒号或奇怪片段
        if (":".equals(d) || d.startsWith("::")) {
            return false;
        }
        try {
            Path p = Path.of(d);
            // 不必已存在，但必须是合法路径语法
            return p.getNameCount() >= 0;
        } catch (InvalidPathException e) {
            return false;
        }
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
