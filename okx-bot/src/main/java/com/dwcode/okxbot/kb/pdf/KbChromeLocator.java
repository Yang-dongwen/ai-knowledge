package com.dwcode.okxbot.kb.pdf;

import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 查找本机 Edge / Chrome / Chromium，供无头打印 PDF。
 */
public final class KbChromeLocator {

    private KbChromeLocator() {
    }

    public static String resolve(String configured) {
        if (StringUtils.hasText(configured)) {
            Path p = Path.of(configured.trim());
            if (isBrowser(p)) {
                return p.toAbsolutePath().normalize().toString();
            }
        }
        for (Path p : candidates()) {
            if (isBrowser(p)) {
                return p.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        if (isWindows()) {
            add(out, System.getenv("PROGRAMFILES"),
                    "Microsoft/Edge/Application/msedge.exe",
                    "Google/Chrome/Application/chrome.exe");
            add(out, System.getenv("PROGRAMFILES(X86)"),
                    "Microsoft/Edge/Application/msedge.exe",
                    "Google/Chrome/Application/chrome.exe");
            add(out, System.getenv("LOCALAPPDATA"),
                    "Google/Chrome/Application/chrome.exe",
                    "Microsoft/Edge/Application/msedge.exe");
        } else {
            out.add(Path.of("/usr/bin/google-chrome"));
            out.add(Path.of("/usr/bin/google-chrome-stable"));
            out.add(Path.of("/usr/bin/chromium"));
            out.add(Path.of("/usr/bin/chromium-browser"));
            out.add(Path.of("/usr/bin/microsoft-edge"));
            out.add(Path.of("/snap/bin/chromium"));
        }
        return out;
    }

    private static void add(List<Path> out, String root, String... rels) {
        if (!StringUtils.hasText(root)) {
            return;
        }
        Path base = Path.of(root);
        for (String rel : rels) {
            out.add(base.resolve(rel));
        }
    }

    static boolean isBrowser(Path p) {
        return p != null && Files.exists(p) && !Files.isDirectory(p);
    }

    static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }
}
