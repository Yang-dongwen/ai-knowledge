/**
 * AI 视频渲染 HTTP 服务
 *
 * - POST /render
 * - GET  /health   { ok, audioHttp: true, audioMix: true }
 * - GET  /media/** 任务目录静态文件（Remotion Audio 用 http）
 *
 * 成片配音策略：
 * 1) Remotion 渲染画面（可选带 Audio，部分环境会混成静音轨）
 * 2) 渲染结束后用 FFmpeg 按分镜时间轴把 TTS 文件真正 mux 进 MP4（保证有声）
 */
import express from "express";
import path from "path";
import fs from "fs";
import { spawn } from "child_process";
import { fileURLToPath } from "url";
import { bundle } from "@remotion/bundler";
import { renderMedia, selectComposition } from "@remotion/renderer";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(__dirname, "..");
const PORT = Number(process.env.PORT || 3100);
const HOST = process.env.HOST || "127.0.0.1";
const MAX_CONCURRENT = Number(process.env.MAX_CONCURRENT_RENDERS || 1);
const RENDER_TOKEN = process.env.AIGEN_RENDER_TOKEN || "";
const FFMPEG_BIN = process.env.FFMPEG_PATH || process.env.FFMPEG || "ffmpeg";
let MEDIA_ROOT = process.env.ALLOWED_WORK_ROOT
  ? path.resolve(process.env.ALLOWED_WORK_ROOT)
  : null;

let bundleLocation = null;
let activeRenders = 0;
let mediaMounted = false;

async function ensureBundle() {
  if (bundleLocation) return bundleLocation;
  console.log("[aigen-remotion] bundling project…");
  bundleLocation = await bundle({
    entryPoint: path.join(PROJECT_ROOT, "src", "index.ts"),
    webpackOverride: (config) => config,
  });
  console.log("[aigen-remotion] bundle ready:", bundleLocation);
  return bundleLocation;
}

function ensureMediaStatic(root) {
  if (!root) return;
  const resolved = path.resolve(root);
  if (!fs.existsSync(resolved)) {
    fs.mkdirSync(resolved, { recursive: true });
  }
  if (!mediaMounted || MEDIA_ROOT !== resolved) {
    MEDIA_ROOT = resolved;
    app.use(
      "/media",
      express.static(MEDIA_ROOT, { fallthrough: true, index: false, maxAge: 0 })
    );
    mediaMounted = true;
    console.log("[aigen-remotion] /media ->", MEDIA_ROOT);
  }
}

function assertSafePath(workDir, outputFile) {
  const wd = path.resolve(workDir);
  const out = path.resolve(outputFile);
  if (MEDIA_ROOT) {
    const rootN = MEDIA_ROOT.toLowerCase();
    if (!wd.toLowerCase().startsWith(rootN)) {
      throw new Error("workDir outside MEDIA_ROOT: " + wd);
    }
    if (!out.toLowerCase().startsWith(rootN)) {
      throw new Error("outputFile outside MEDIA_ROOT: " + out);
    }
  }
  if (!fs.existsSync(wd)) {
    throw new Error("workDir does not exist: " + wd);
  }
  return { wd, out };
}

/**
 * 把所有音频 src 改成 http://127.0.0.1:PORT/media/{taskRel}/...
 */
function rewriteAudioToHttp(inputProps, workDir) {
  const props = JSON.parse(JSON.stringify(inputProps || {}));
  const wd = path.resolve(workDir);
  const mediaRoot = MEDIA_ROOT || path.dirname(wd);
  const relTask = path.relative(mediaRoot, wd).split(path.sep).join("/");
  const baseUrl = `http://127.0.0.1:${PORT}/media/${relTask}`.replace(/\/+$/, "");

  delete props.workDir;

  if (!props.audio) props.audio = {};
  if (!Array.isArray(props.audio.tracks)) props.audio.tracks = [];

  props.audio.tracks = props.audio.tracks.map((t) => {
    if (!t || t.mock) {
      return { ...t, src: undefined, absSrc: undefined };
    }
    let rel = (t.src || "").trim();
    if (rel && (path.isAbsolute(rel) || /^[A-Za-z]:[\\/]/.test(rel))) {
      rel = path.relative(wd, rel).split(path.sep).join("/");
    }
    if ((!rel || rel.startsWith("..")) && t.absSrc) {
      try {
        rel = path.relative(wd, t.absSrc).split(path.sep).join("/");
      } catch {
        rel = "";
      }
    }
    if (
      !rel ||
      rel.includes("..") ||
      rel.toLowerCase().endsWith(".txt") ||
      rel.toLowerCase().includes("mock")
    ) {
      return { sceneId: t.sceneId, mock: true, durationMs: t.durationMs };
    }
    const full = path.resolve(wd, rel);
    if (!fs.existsSync(full)) {
      console.warn("[aigen-remotion] audio file missing:", full);
      return { sceneId: t.sceneId, mock: true, durationMs: t.durationMs };
    }
    const httpSrc = `${baseUrl}/${rel.split(path.sep).join("/")}`.replace(
      /([^:]\/)\/+/g,
      "$1"
    );
    return {
      sceneId: t.sceneId,
      durationMs: t.durationMs,
      mock: false,
      src: httpSrc,
      _localPath: full,
    };
  });

  console.log(
    "[aigen-remotion] rewritten audio:",
    JSON.stringify(
      props.audio.tracks.map((t) => ({
        sceneId: t.sceneId,
        mock: t.mock,
        src: t.src,
      }))
    )
  );
  return props;
}

function resolveLocalAudioFile(workDir, track) {
  if (!track || track.mock) return null;
  if (track._localPath && fs.existsSync(track._localPath)) {
    return track._localPath;
  }
  let rel = (track.src || "").trim();
  if (!rel) return null;
  if (rel.startsWith("http://") || rel.startsWith("https://")) {
    const m = rel.match(/\/media\/[^/]+\/(.+)$/i);
    if (!m) return null;
    rel = m[1];
  }
  if (path.isAbsolute(rel) || /^[A-Za-z]:[\\/]/.test(rel)) {
    return fs.existsSync(rel) ? rel : null;
  }
  rel = rel.replace(/\\/g, "/").replace(/^\//, "");
  if (rel.includes("..") || rel.toLowerCase().endsWith(".txt")) return null;
  const full = path.resolve(workDir, rel);
  return fs.existsSync(full) ? full : null;
}

function runFfmpeg(args) {
  return new Promise((resolve, reject) => {
    console.log("[aigen-remotion] ffmpeg", FFMPEG_BIN, args.join(" "));
    const p = spawn(FFMPEG_BIN, args, { windowsHide: true });
    let err = "";
    p.stderr.on("data", (d) => {
      err += d.toString();
    });
    p.on("error", (e) => reject(e));
    p.on("close", (code) => {
      if (code === 0) resolve();
      else reject(new Error(`ffmpeg exit ${code}: ${err.slice(-1500)}`));
    });
  });
}

/**
 * 用 FFmpeg 把分镜 TTS 按 startFrame 混入成片，覆盖 Remotion 可能产出的静音轨。
 * 这是成片「有声」的可靠路径。
 */
async function muxNarrationWithFfmpeg(videoPath, workDir, storyboard) {
  const fps = Number(storyboard?.meta?.fps) || 30;
  const scenes = Array.isArray(storyboard?.scenes) ? storyboard.scenes : [];
  const tracks = Array.isArray(storyboard?.audio?.tracks)
    ? storyboard.audio.tracks
    : [];

  const clips = [];
  for (const t of tracks) {
    const file = resolveLocalAudioFile(workDir, t);
    if (!file) continue;
    const scene = scenes.find((s) => s.id === t.sceneId);
    const startFrame = scene ? Number(scene.startFrame) || 0 : 0;
    const delayMs = Math.max(0, Math.round((startFrame * 1000) / fps));
    clips.push({ file, delayMs, sceneId: t.sceneId });
  }

  if (!clips.length) {
    console.warn(
      "[aigen-remotion] no local narration files for ffmpeg mix — keep remotion audio as-is"
    );
    return { mixed: false, clipCount: 0 };
  }

  const tmpOut = videoPath + ".narration.mp4";

  // 各旁白：统一 48k stereo，按场景 start 延迟，再 amix
  const filters = [];
  const labels = [];
  for (let i = 0; i < clips.length; i++) {
    const d = clips[i].delayMs;
    filters.push(
      `[${i + 1}:a]aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,` +
        `adelay=${d}|${d}:all=1,volume=1.2[a${i}]`
    );
    labels.push(`[a${i}]`);
  }
  filters.push(
    `${labels.join("")}amix=inputs=${clips.length}:duration=longest:dropout_transition=0:normalize=0[aout]`
  );

  const args = ["-y", "-i", videoPath];
  for (const c of clips) args.push("-i", c.file);
  args.push(
    "-filter_complex",
    filters.join(";"),
    "-map",
    "0:v:0",
    "-map",
    "[aout]",
    "-c:v",
    "copy",
    "-c:a",
    "aac",
    "-b:a",
    "192k",
    "-movflags",
    "+faststart",
    tmpOut
  );

  try {
    await runFfmpeg(args);
  } catch (e) {
    // 清理半成品
    try {
      if (fs.existsSync(tmpOut)) fs.unlinkSync(tmpOut);
    } catch {
      /* ignore */
    }
    throw e;
  }

  // 原子替换
  const bak = videoPath + ".silent.bak.mp4";
  try {
    if (fs.existsSync(bak)) fs.unlinkSync(bak);
    fs.renameSync(videoPath, bak);
    fs.renameSync(tmpOut, videoPath);
    try {
      fs.unlinkSync(bak);
    } catch {
      /* keep bak if delete fails */
    }
  } catch (e) {
    // 回滚
    try {
      if (fs.existsSync(bak) && !fs.existsSync(videoPath)) {
        fs.renameSync(bak, videoPath);
      }
    } catch {
      /* ignore */
    }
    throw e;
  }

  console.log(
    `[aigen-remotion] ffmpeg mixed ${clips.length} clips into ${path.basename(videoPath)}`
  );
  return { mixed: true, clipCount: clips.length };
}

const app = express();
app.use(express.json({ limit: "8mb" }));

if (MEDIA_ROOT) {
  ensureMediaStatic(MEDIA_ROOT);
}

app.get("/health", (_req, res) => {
  res.json({
    ok: true,
    audioHttp: true,
    audioMix: true,
    version: "3",
    ffmpeg: FFMPEG_BIN,
    activeRenders,
    maxConcurrent: MAX_CONCURRENT,
    bundleReady: Boolean(bundleLocation),
    mediaRoot: MEDIA_ROOT,
  });
});

app.post("/render", async (req, res) => {
  if (RENDER_TOKEN) {
    const token = req.header("X-Aigen-Render-Token") || "";
    if (token !== RENDER_TOKEN) {
      return res.status(401).json({ success: false, error: "unauthorized" });
    }
  }
  if (activeRenders >= MAX_CONCURRENT) {
    return res.status(429).json({ success: false, error: "render queue full" });
  }

  const {
    jobId,
    compositionId = "KnowledgeCards",
    inputProps,
    workDir,
    outputFile,
    codec = "h264",
    crf = 18,
  } = req.body || {};

  if (!jobId || !inputProps || !workDir || !outputFile) {
    return res.status(400).json({
      success: false,
      error: "jobId, inputProps, workDir, outputFile required",
    });
  }

  activeRenders += 1;
  const t0 = Date.now();
  try {
    const { wd, out } = assertSafePath(workDir, outputFile);
    fs.mkdirSync(path.dirname(out), { recursive: true });

    if (!MEDIA_ROOT) {
      ensureMediaStatic(path.dirname(wd));
    } else {
      ensureMediaStatic(MEDIA_ROOT);
    }

    // 保留原始 props 给 ffmpeg 混音（含相对路径）
    const originalProps = JSON.parse(JSON.stringify(inputProps || {}));
    const props = rewriteAudioToHttp(inputProps, wd);

    for (const t of props.audio?.tracks || []) {
      const s = (t.src || "").toString();
      if (s && !s.startsWith("http://") && !s.startsWith("https://") && !t.mock) {
        throw new Error(
          "internal: audio src must be http(s), got: " + s + " (scene=" + t.sceneId + ")"
        );
      }
    }

    const serveUrl = await ensureBundle();
    const composition = await selectComposition({
      serveUrl,
      id: compositionId,
      inputProps: props,
    });

    await renderMedia({
      composition,
      serveUrl,
      codec: codec === "h264" ? "h264" : codec,
      outputLocation: out,
      inputProps: props,
      crf: Number(crf) || 18,
      // 允许 headless 拉本机 media；仍以 ffmpeg 混音兜底
      chromiumOptions: {
        disableWebSecurity: true,
      },
    });

    if (!fs.existsSync(out)) {
      throw new Error("output file missing after render");
    }

    // 关键：FFmpeg 按时间轴混入 TTS，修复「有音轨但无声」
    let mixInfo = { mixed: false, clipCount: 0 };
    try {
      mixInfo = await muxNarrationWithFfmpeg(out, wd, originalProps);
    } catch (mixErr) {
      console.error(
        "[aigen-remotion] ffmpeg audio mix failed (video kept):",
        mixErr?.message || mixErr
      );
      // 不整单失败：至少有画面；前端可提示无声
    }

    console.log(
      `[aigen-remotion] done jobId=${jobId} ms=${Date.now() - t0} out=${out} mix=${JSON.stringify(mixInfo)}`
    );
    res.json({
      success: true,
      outputFile: out,
      durationMs: Date.now() - t0,
      audioMix: mixInfo,
    });
  } catch (e) {
    console.error(`[aigen-remotion] fail jobId=${jobId}`, e);
    res.status(500).json({
      success: false,
      error: e?.message || String(e),
      durationMs: Date.now() - t0,
    });
  } finally {
    activeRenders = Math.max(0, activeRenders - 1);
  }
});

app.listen(PORT, HOST, () => {
  console.log(
    `[aigen-remotion] listening http://${HOST}:${PORT} (audioHttp=true audioMix=true ffmpeg=${FFMPEG_BIN})`
  );
  ensureBundle().catch((e) => console.warn("preload bundle failed:", e.message));
});
