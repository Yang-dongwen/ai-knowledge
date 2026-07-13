package com.dwcode.okxbot.imggen.adapter.mock;

import com.dwcode.okxbot.imggen.config.ImgGenProperties;
import com.dwcode.okxbot.imggen.port.ImageAsset;
import com.dwcode.okxbot.imggen.port.ImageGenCommand;
import com.dwcode.okxbot.imggen.port.ImageGenPort;
import com.dwcode.okxbot.imggen.port.ImageGenResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 本地渐变假图，用于无 key / 联调骨架。
 */
@Slf4j
@RequiredArgsConstructor
public class MockImageGenAdapter implements ImageGenPort {

    private final ImgGenProperties properties;

    @Override
    public ImageGenResult generate(ImageGenCommand cmd) throws Exception {
        long t0 = System.currentTimeMillis();
        if (properties.getMockStepDelayMs() > 0) {
            Thread.sleep(properties.getMockStepDelayMs());
        }
        Path outDir = cmd.getOutputsDir();
        Files.createDirectories(outDir);

        List<ImageAsset> assets = new ArrayList<>();
        long baseSeed = cmd.getSeed() != null ? cmd.getSeed() : ThreadLocalRandom.current().nextLong(0, 1_000_000);
        for (int i = 1; i <= Math.max(1, cmd.getN()); i++) {
            long seed = baseSeed + i - 1;
            String name = String.format("img-%02d.png", i);
            Path file = outDir.resolve(name);
            writeGradientPng(file, cmd.getWidth(), cmd.getHeight(), seed, cmd.getPrompt());
            assets.add(ImageAsset.builder()
                    .index(i)
                    .relativePath("outputs/" + name)
                    .width(cmd.getWidth())
                    .height(cmd.getHeight())
                    .seed(seed)
                    .build());
        }
        return ImageGenResult.builder()
                .images(assets)
                .providerLatencyMs(System.currentTimeMillis() - t0)
                .providerRequestId("mock")
                .rawMetaJson("{\"mock\":true}")
                .build();
    }

    private static void writeGradientPng(Path file, int w, int h, long seed, String prompt) throws Exception {
        BufferedImage img = new BufferedImage(Math.max(64, w), Math.max(64, h), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            int r1 = (int) (seed % 180) + 40;
            int g1 = (int) ((seed / 7) % 180) + 40;
            int b1 = (int) ((seed / 13) % 180) + 40;
            GradientPaint paint = new GradientPaint(0, 0, new Color(r1, g1, b1),
                    w, h, new Color(255 - r1 / 2, 255 - g1 / 2, 255 - b1 / 2));
            g.setPaint(paint);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(18, w / 28)));
            g.drawString("Mock Image", 24, 48);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(12, w / 48)));
            String text = prompt == null ? "" : prompt;
            if (text.length() > 80) {
                text = text.substring(0, 80) + "…";
            }
            g.drawString(text, 24, 80);
            g.drawString("seed=" + seed, 24, 108);
        } finally {
            g.dispose();
        }
        ImageIO.write(img, "png", file.toFile());
    }
}
