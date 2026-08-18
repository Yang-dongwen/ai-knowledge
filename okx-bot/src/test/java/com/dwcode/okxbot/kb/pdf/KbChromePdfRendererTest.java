package com.dwcode.okxbot.kb.pdf;

import com.dwcode.okxbot.kb.config.KbProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KbChromePdfRendererTest {

    static boolean chromeAvailable() {
        return KbChromeLocator.resolve(null) != null;
    }

    @Test
    @EnabledIf("chromeAvailable")
    void renderProducesPdf() {
        KbChromePdfRenderer renderer = new KbChromePdfRenderer(new KbProperties());
        byte[] pdf = renderer.render("测试文档", "<h1>标题</h1><p>正文 Vue</p>");
        assertTrue(pdf.length > 200, "pdf too small: " + pdf.length);
        assertTrue(pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F');
    }

    @Test
    void commandIncludesPrintToPdf() {
        KbChromePdfRenderer renderer = new KbChromePdfRenderer(new KbProperties());
        var cmd = renderer.command("msedge.exe", Path.of("index.html"), Path.of("out.pdf"));
        assertTrue(cmd.contains("--headless=new"));
        assertTrue(cmd.stream().anyMatch(s -> s.startsWith("--print-to-pdf=")));
        assertTrue(cmd.get(0).equals("msedge.exe"));
    }
}
