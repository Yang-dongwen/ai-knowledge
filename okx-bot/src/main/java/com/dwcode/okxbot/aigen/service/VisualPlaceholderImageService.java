package com.dwcode.okxbot.aigen.service;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 出图失败 / mock 时生成渐变占位图。
 */
@Service
public class VisualPlaceholderImageService {

    public Path writeGradientJpeg(Path file, int width, int height, String label, int colorSeed) throws Exception {
        Files.createDirectories(file.getParent());
        int w = Math.max(64, width);
        int h = Math.max(64, height);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            Color c1 = new Color(20 + (colorSeed * 37) % 80, 24 + (colorSeed * 53) % 90, 48 + (colorSeed * 17) % 120);
            Color c2 = new Color(180 + (colorSeed * 11) % 60, 90 + (colorSeed * 23) % 80, 40 + (colorSeed * 7) % 50);
            GradientPaint gp = new GradientPaint(0, 0, c1, w, h, c2);
            g.setPaint(gp);
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(255, 255, 255, 200));
            g.setFont(new Font("SansSerif", Font.BOLD, Math.max(28, w / 18)));
            String text = label != null && !label.isBlank() ? label : "Visual";
            if (text.length() > 28) {
                text = text.substring(0, 28) + "…";
            }
            FontMetrics fm = g.getFontMetrics();
            int tx = Math.max(24, (w - fm.stringWidth(text)) / 2);
            int ty = h / 2;
            g.drawString(text, tx, ty);
        } finally {
            g.dispose();
        }
        ImageIO.write(img, "jpg", file.toFile());
        return file;
    }
}
