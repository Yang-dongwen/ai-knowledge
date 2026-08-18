package com.dwcode.okxbot.kb.pdf;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.config.KbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 用本机 Edge/Chrome 无头打印 HTML → PDF（Skia，与参考简历同一引擎）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbChromePdfRenderer {

    private static final String CSS_PATH = "kb/pdf/typora-vue-print.css";
    private static final String FONT_DIR = "kb/pdf/fonts/";

    private final KbProperties properties;

    public byte[] render(String title, String bodyHtml) {
        String chrome = KbChromeLocator.resolve(properties.getPdf().getChromePath());
        if (chrome == null) {
            throw new BusinessException(503, "未找到 Edge/Chrome，无法导出 PDF。请安装 Microsoft Edge 或 Google Chrome 后重试。");
        }
        String css = readClasspathText(CSS_PATH);
        String html = KbVuePdfDocument.wrap(title, bodyHtml, css);
        Path dir = null;
        try {
            dir = Files.createTempDirectory("kb-pdf-");
            Path htmlFile = dir.resolve("index.html");
            Path pdfFile = dir.resolve("out.pdf");
            Files.writeString(htmlFile, html, StandardCharsets.UTF_8);
            copyFonts(dir);
            runChrome(chrome, htmlFile, pdfFile, dir);
            if (!Files.isRegularFile(pdfFile) || Files.size(pdfFile) < 32) {
                throw new BusinessException(500, "PDF 生成失败（输出为空）");
            }
            return Files.readAllBytes(pdfFile);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("kb pdf render failed: {}", e.toString());
            throw new BusinessException(500, "PDF 生成失败: " + e.getMessage());
        } finally {
            deleteQuietly(dir);
        }
    }

    List<String> command(String chrome, Path htmlFile, Path pdfFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(chrome);
        cmd.add("--headless=new");
        cmd.add("--disable-gpu");
        cmd.add("--disable-extensions");
        cmd.add("--hide-scrollbars");
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        cmd.add("--no-pdf-header-footer");
        cmd.add("--virtual-time-budget=8000");
        if (properties.getPdf().isNoSandbox()) {
            cmd.add("--no-sandbox");
            cmd.add("--disable-dev-shm-usage");
        }
        cmd.add("--print-to-pdf=" + pdfFile.toAbsolutePath());
        cmd.add(htmlFile.toAbsolutePath().toString());
        return cmd;
    }

    private void runChrome(String chrome, Path htmlFile, Path pdfFile, Path dir) throws Exception {
        List<String> cmd = command(chrome, htmlFile, pdfFile);
        log.info("kb pdf chrome={}", chrome);
        Path logFile = dir.resolve("chrome.log");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        Process process = pb.start();
        int timeout = Math.max(properties.getPdf().getTimeoutSeconds(), 10);
        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new BusinessException(504, "PDF 导出超时");
        }
        if (process.exitValue() != 0) {
            String logOut = Files.isRegularFile(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
            log.warn("kb pdf chrome exit={} log={}", process.exitValue(), truncate(logOut));
            throw new BusinessException(500, "PDF 生成失败（浏览器退出码 " + process.exitValue() + "）");
        }
    }

    private void copyFonts(Path dir) throws IOException {
        for (String name : KbVuePdfDocument.FONT_FILES) {
            ClassPathResource res = new ClassPathResource(FONT_DIR + name);
            if (!res.exists()) {
                throw new BusinessException(500, "缺少 PDF 字体: " + name);
            }
            try (InputStream in = res.getInputStream()) {
                Files.copy(in, dir.resolve(name));
            }
        }
    }

    private static String readClasspathText(String path) {
        ClassPathResource res = new ClassPathResource(path);
        if (!res.exists()) {
            throw new BusinessException(500, "缺少 PDF 样式: " + path);
        }
        try (InputStream in = res.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(500, "读取 PDF 样式失败");
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // temp
                }
            });
        } catch (IOException ignored) {
            // temp
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() > 800 ? t.substring(0, 800) : t;
    }
}
