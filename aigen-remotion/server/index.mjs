/**
 * AI 视频渲染 HTTP 服务
 *
 * - POST /render
 * - GET  /health   { ok, audioHttp: true }  // Java 用 audioHttp 识别新版本
 * - GET  /media/** 任务目录静态文件（Audio 必须用 http，不能 file://）
 */
import express from "express";
import path from "path";
import fs from "fs";
import { fileURLToPath } from "url";
import { bundle } from "@remotion/bundler";
import { renderMedia, selectComposition } from "@remotion/renderer";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(__dirname, "..");
const PORT = Number(process.env.PORT || 3100);
const HOST = process.env.HOST || "127.0.0.1";
const MAX_CONCURRENT = Number(process.env.MAX_CONCURRENT_RENDERS || 1);
const RENDER_TOKEN = process.env.AIGEN_RENDER_TOKEN || "";
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
 * Remotion <Audio> 只能拉 http(s)，file:// 与本地盘符路径都会失败。
 */
function rewriteAudioToHttp(inputProps, workDir) {
  const props = JSON.parse(JSON.stringify(inputProps || {}));
  const wd = path.resolve(workDir);
  const mediaRoot = MEDIA_ROOT || path.dirname(wd);
  const relTask = path.relative(mediaRoot, wd).split(path.sep).join("/");
  const baseUrl = `http://127.0.0.1:${PORT}/media/${relTask}`.replace(/\/+$/, "");

  // 去掉 workDir，避免模板拼本地路径
  delete props.workDir;

  if (!props.audio) props.audio = {};
  if (!Array.isArray(props.audio.tracks)) props.audio.tracks = [];

  props.audio.tracks = props.audio.tracks.map((t) => {
    if (!t || t.mock) {
      return { ...t, src: undefined, absSrc: undefined };
    }
    let rel = (t.src || "").trim();
    // 绝对路径 → 相对 task 目录
    if (rel && (path.isAbsolute(rel) || /^[A-Za-z]:[\\/]/.test(rel))) {
      rel = path.relative(wd, rel).split(path.sep).join("/");
    }
    // absSrc 也清掉
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
    };
  });

  console.log(
    "[aigen-remotion] rewritten audio:",
    JSON.stringify(props.audio.tracks)
  );
  return props;
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
    version: "2",
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

    // 确保静态目录覆盖 workDir
    if (!MEDIA_ROOT) {
      ensureMediaStatic(path.dirname(wd));
    } else {
      ensureMediaStatic(MEDIA_ROOT);
    }

    const props = rewriteAudioToHttp(inputProps, wd);

    // 二次校验：禁止再出现盘符或 file://
    for (const t of props.audio?.tracks || []) {
      const s = (t.src || "").toString();
      if (s && !s.startsWith("http://") && !s.startsWith("https://")) {
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
      chromiumOptions: {
        disableWebSecurity: false,
      },
    });

    if (!fs.existsSync(out)) {
      throw new Error("output file missing after render");
    }

    console.log(
      `[aigen-remotion] done jobId=${jobId} ms=${Date.now() - t0} out=${out}`
    );
    res.json({
      success: true,
      outputFile: out,
      durationMs: Date.now() - t0,
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
  console.log(`[aigen-remotion] listening http://${HOST}:${PORT} (audioHttp=true)`);
  ensureBundle().catch((e) => console.warn("preload bundle failed:", e.message));
});
