# AI 视频生成 — 语音（TTS）接入方案

**日期**：2026-07-13  
**目标**：分镜 `narration` → 真实音频 → Remotion 成片带声  

---

## 方案摘要

```
AssetStep
  → TtsPort.synthesize(每场 narration)
  → 写入 data/aigen/{taskId}/assets/audio/{sceneId}.mp3|.wav
  → 用真实 durationMs 重排 startFrame / durationInFrames / subtitles
  → Remotion 按场景 Sequence 播放对应音频
```

| 实现 | 说明 | 适用 |
|------|------|------|
| **EdgeTtsProvider** | `edge-tts` / `python -m edge_tts` | 推荐，中文自然 |
| **WindowsSapiTtsProvider** | PowerShell System.Speech → WAV | 无 Python 的 Windows 兜底 |
| **MockTtsProvider** | 仅估时长 | `provider=mock` 或调试 |

配置 `aigen.tts.provider`：

- `auto`（默认）：Edge 可用则 Edge，否则 Windows，否则报错  
- `edge` / `windows` / `mock`：强制指定  

`aigen.steps.asset=real` 时启用真实 TTS。

---

## 安全与健壮

- 输出路径仍经 `resolveAsset`（仅 `assets/`）  
- 文本长度限制（与 narration 500 字一致）  
- 超时 `tts.timeout-seconds`  
- 单 scene 失败整步 FAILED（可后续改为单场 mock 兜底）  
- 时长用 ffprobe（若有 ffmpeg）测量，失败则用文件大小估算  

---

## 依赖

```bash
# 推荐（中文效果好）
pip install edge-tts
# 验证
edge-tts --version
```

可选：系统已装 FFmpeg（项目 video 模块已有）用于测时长。
