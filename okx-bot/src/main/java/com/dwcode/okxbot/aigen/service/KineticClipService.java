package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.shot.ShotDto;
import com.dwcode.okxbot.video.config.VideoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 将静图转为「真动态」短视频片段（FFmpeg zoompan），避免全片 Ken Burns 幻灯片观感。
 * <p>
 * 不依赖外部图生视频 API；使用本机 ffmpeg（与 video 模块同源路径）。
 * 失败时返回 false，调用方可继续用静图 + Remotion 运镜兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KineticClipService {

    private final AigenProperties aigenProperties;
    private final VideoProperties videoProperties;

    public boolean isEnabled() {
        return aigenProperties.getVisual() != null && aigenProperties.getVisual().isKineticClips();
    }

    /**
     * 由静图生成镜头 mp4。
     *
     * @return 成功生成的 mp4 相对路径（相对 workDir）；失败 null
     */
    public String generateClip(Path workDir,
                               Path stillImage,
                               ShotDto shot,
                               int width,
                               int height,
                               int seedIndex) {
        if (!isEnabled() || stillImage == null || !Files.isRegularFile(stillImage)) {
            return null;
        }
        String ffmpeg = resolveFfmpeg();
        if (ffmpeg == null) {
            log.warn("kinetic-clips 已开启但未找到 ffmpeg，跳过动效片段");
            return null;
        }

        String shotId = shot.getId() != null ? shot.getId() : ("shot-" + seedIndex);
        Path outDir = workDir.resolve("assets").resolve("visual");
        try {
            Files.createDirectories(outDir);
        } catch (Exception e) {
            log.warn("创建 visual 目录失败: {}", e.getMessage());
            return null;
        }
        Path outMp4 = outDir.resolve(shotId + ".mp4");

        double sec = shot.getDurationSec() != null ? shot.getDurationSec() : 3.5;
        sec = Math.min(12.0, Math.max(2.0, sec));
        // TTS 拉长后镜可能很长：动效片段最长 8s，Remotion 会 loop/hold
        double clipSec = Math.min(8.0, sec);
        int fps = Math.max(12, Math.min(30, aigenProperties.getVisual().getKineticFps()));
        int frames = Math.max(fps * 2, (int) Math.round(clipSec * fps));
        int w = Math.max(640, width);
        int h = Math.max(640, height);
        // 偶数分辨率（libx264）
        w = w % 2 == 0 ? w : w + 1;
        h = h % 2 == 0 ? h : h + 1;

        String motion = resolveMotion(shot, seedIndex);
        double intensity = readIntensity(shot);
        String zoompan = buildZoompanExpr(motion, intensity, frames, w, h, fps);

        // scale+crop 铺满，再 zoompan 出真实像素运动
        String vf = String.format(Locale.ROOT,
                "scale=%d:%d:force_original_aspect_ratio=increase,"
                        + "crop=%d:%d,"
                        + "%s",
                w, h, w, h, zoompan);

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-loop");
        cmd.add("1");
        cmd.add("-i");
        cmd.add(stillImage.toAbsolutePath().normalize().toString());
        cmd.add("-vf");
        cmd.add(vf);
        cmd.add("-t");
        cmd.add(String.format(Locale.ROOT, "%.3f", clipSec));
        cmd.add("-r");
        cmd.add(String.valueOf(fps));
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add(aigenProperties.getVisual().getKineticPreset());
        cmd.add("-crf");
        cmd.add(String.valueOf(Math.max(16, Math.min(28, aigenProperties.getVisual().getKineticCrf()))));
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add("-an");
        cmd.add(outMp4.toAbsolutePath().normalize().toString());

        long t0 = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (var is = p.getInputStream()) {
                out = new String(is.readAllBytes());
            }
            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("kinetic ffmpeg 超时 shotId={}", shotId);
                return null;
            }
            if (p.exitValue() != 0 || !Files.isRegularFile(outMp4) || Files.size(outMp4) < 1024) {
                log.warn("kinetic ffmpeg 失败 shotId={} code={} err={}",
                        shotId, p.exitValue(), truncate(out, 300));
                return null;
            }
            log.info("kinetic 片段完成: shot={} motion={} {}x{} {}s {}ms -> {}",
                    shotId, motion, w, h, String.format(Locale.ROOT, "%.1f", clipSec),
                    System.currentTimeMillis() - t0, outMp4.getFileName());
            return "assets/visual/" + shotId + ".mp4";
        } catch (Exception e) {
            log.warn("kinetic 生成异常 shotId={}: {}", shotId, e.getMessage());
            return null;
        }
    }

    private String resolveMotion(ShotDto shot, int seedIndex) {
        String t = shot.getMotion() != null && shot.getMotion().getType() != null
                ? shot.getMotion().getType().trim().toLowerCase(Locale.ROOT)
                : "auto";
        if (t.isBlank() || "auto".equals(t)) {
            String[] auto = {
                    "drift", "punch_in", "pan_left", "rise", "whip",
                    "orbit", "zoom_in", "pan_right", "fall", "punch_out"
            };
            return auto[Math.floorMod(seedIndex - 1, auto.length)];
        }
        return t;
    }

    private double readIntensity(ShotDto shot) {
        if (shot.getMotion() == null || shot.getMotion().getParams() == null) {
            return 0.65;
        }
        Object v = shot.getMotion().getParams().get("intensity");
        if (v instanceof Number n) {
            return Math.min(1.2, Math.max(0.25, n.doubleValue()));
        }
        if (v != null) {
            try {
                return Math.min(1.2, Math.max(0.25, Double.parseDouble(v.toString())));
            } catch (Exception ignored) {
                // fallthrough
            }
        }
        return 0.65;
    }

    /**
     * zoompan 表达式：输出真实像素级推拉/平移，感官强于 CSS transform。
     */
    private String buildZoompanExpr(String motion, double intensity, int frames, int w, int h, int fps) {
        // 速度随 intensity；z 上限 1.15～1.45
        double zMax = 1.12 + 0.28 * intensity;
        double zStep = (zMax - 1.0) / Math.max(1, frames);
        // zoompan 里 on 从 0 开始
        String zIn = String.format(Locale.ROOT, "min(1.0+%.6f*on,%.4f)", zStep, zMax);
        String zOut = String.format(Locale.ROOT, "if(eq(on,0),%.4f,max(1.0,%.4f-%.6f*on))",
                zMax, zMax, zStep);
        String zHold = String.format(Locale.ROOT, "%.4f", 1.0 + 0.12 * intensity);

        String xCenter = "iw/2-(iw/zoom/2)";
        String yCenter = "ih/2-(ih/zoom/2)";
        // 平移幅度
        String xLeftToRight = " (iw-iw/zoom)*on/" + Math.max(1, frames - 1);
        String xRightToLeft = " (iw-iw/zoom)*(1-on/" + Math.max(1, frames - 1) + ")";
        String yTopToBottom = " (ih-ih/zoom)*on/" + Math.max(1, frames - 1);
        String yBottomToTop = " (ih-ih/zoom)*(1-on/" + Math.max(1, frames - 1) + ")";
        // 漂移：近似正弦（zoompan 无 sin 时用折线）
        int mid = Math.max(1, frames / 2);
        String xDrift = "if(lt(on," + mid + "),(iw-iw/zoom)*on/" + mid
                + ",(iw-iw/zoom)*(1-(on-" + mid + ")/" + Math.max(1, frames - mid) + "))";
        String yDrift = "if(lt(on," + mid + "),(ih-ih/zoom)*0.35*on/" + mid
                + ",(ih-ih/zoom)*0.35*(1-(on-" + mid + ")/" + Math.max(1, frames - mid) + "))";

        String z;
        String x;
        String y;
        switch (motion) {
            case "static" -> {
                z = "1.05";
                x = xCenter;
                y = yCenter;
            }
            case "zoom_out", "punch_out" -> {
                z = zOut;
                x = xCenter;
                y = yCenter;
            }
            case "pan_left" -> {
                z = zHold;
                x = xRightToLeft;
                y = yCenter;
            }
            case "pan_right" -> {
                z = zHold;
                x = xLeftToRight;
                y = yCenter;
            }
            case "whip" -> {
                // 快速横扫 + 略推近
                z = zIn;
                x = xLeftToRight;
                y = yCenter;
            }
            case "rise" -> {
                z = zIn;
                x = xCenter;
                y = yBottomToTop;
            }
            case "fall" -> {
                z = zIn;
                x = xCenter;
                y = yTopToBottom;
            }
            case "drift", "orbit" -> {
                z = zIn;
                x = xDrift;
                y = yDrift;
            }
            case "tilt", "shake" -> {
                // 推近 + 斜向轻微位移
                z = zIn;
                x = xLeftToRight;
                y = yTopToBottom;
            }
            case "punch_in", "zoom_in", "ken_burns" -> {
                z = zIn;
                x = xCenter;
                y = yCenter;
            }
            default -> {
                z = zIn;
                x = xCenter;
                y = yCenter;
            }
        }

        return String.format(Locale.ROOT,
                "zoompan=z='%s':x='%s':y='%s':d=%d:s=%dx%d:fps=%d",
                z, x, y, frames, w, h, fps);
    }

    private String resolveFfmpeg() {
        String p = aigenProperties.getTts() != null ? aigenProperties.getTts().getFfmpegPath() : null;
        if (p == null || p.isBlank()) {
            p = videoProperties != null ? videoProperties.getFfmpegPath() : null;
        }
        if (p == null || p.isBlank()) {
            return null;
        }
        Path path = Path.of(p);
        if (!Files.isRegularFile(path) && !p.equalsIgnoreCase("ffmpeg")) {
            // 允许 PATH 中的 ffmpeg 命令名
            if (!p.contains("/") && !p.contains("\\")) {
                return p;
            }
            log.warn("ffmpeg 路径不存在: {}", p);
            return null;
        }
        return path.toAbsolutePath().normalize().toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
