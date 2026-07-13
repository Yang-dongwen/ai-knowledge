"""
本地 faster-whisper 转录服务（OpenAI 兼容接口）。

启动：
  pip install -r requirements.txt
  # CPU 推荐 small/base；有 NVIDIA GPU 可 medium/large-v3 + cuda
  set WHISPER_MODEL=small
  set WHISPER_DEVICE=cpu
  set WHISPER_COMPUTE=int8
  uvicorn main:app --host 0.0.0.0 --port 8000

接口：
  POST /v1/audio/transcriptions  — multipart file + model + language + response_format
  GET  /health
"""

from __future__ import annotations

import os
import tempfile
from typing import Optional

from fastapi import FastAPI, File, Form, UploadFile, HTTPException
from fastapi.responses import JSONResponse

app = FastAPI(title="Whisper Transcription Service", version="0.2.0")

# 懒加载模型，避免启动即占满内存
_model = None
_model_name: Optional[str] = None
_device: Optional[str] = None
_compute: Optional[str] = None

# 默认 small：CPU 上比 medium 快约 3~5 倍，中文仍可用
WHISPER_MODEL = os.getenv("WHISPER_MODEL", "small")
WHISPER_DEVICE = os.getenv("WHISPER_DEVICE", "auto")  # auto | cuda | cpu
WHISPER_COMPUTE = os.getenv("WHISPER_COMPUTE", "auto")  # auto | float16 | int8
# 速度相关（CPU 建议 beam=1）
WHISPER_BEAM_SIZE = int(os.getenv("WHISPER_BEAM_SIZE", "1"))
WHISPER_VAD = os.getenv("WHISPER_VAD", "1") not in ("0", "false", "False")
WHISPER_CPU_THREADS = int(os.getenv("WHISPER_CPU_THREADS", "0"))  # 0=自动
WHISPER_NUM_WORKERS = int(os.getenv("WHISPER_NUM_WORKERS", "1"))
# 启动时预加载，避免首请求额外等模型下载/加载
WHISPER_PRELOAD = os.getenv("WHISPER_PRELOAD", "1") not in ("0", "false", "False")


def _resolve_device_compute():
    device = WHISPER_DEVICE
    compute_type = WHISPER_COMPUTE
    if device == "auto":
        try:
            import ctranslate2

            # faster-whisper 用 ctranslate2，不一定依赖 torch
            cuda_count = 0
            try:
                cuda_count = ctranslate2.get_cuda_device_count()
            except Exception:
                cuda_count = 0
            device = "cuda" if cuda_count > 0 else "cpu"
        except Exception:
            device = "cpu"
    if compute_type == "auto":
        compute_type = "float16" if device == "cuda" else "int8"
    return device, compute_type


def get_model(name: str):
    global _model, _model_name, _device, _compute
    if _model is not None and _model_name == name:
        return _model

    from faster_whisper import WhisperModel

    device, compute_type = _resolve_device_compute()
    kwargs = {
        "device": device,
        "compute_type": compute_type,
        "num_workers": max(1, WHISPER_NUM_WORKERS),
    }
    if device == "cpu" and WHISPER_CPU_THREADS > 0:
        kwargs["cpu_threads"] = WHISPER_CPU_THREADS

    print(f"[whisper] loading model={name} device={device} compute_type={compute_type} "
          f"beam={WHISPER_BEAM_SIZE} vad={WHISPER_VAD} threads={kwargs.get('cpu_threads', 'auto')}")
    _model = WhisperModel(name, **kwargs)
    _model_name = name
    _device = device
    _compute = compute_type
    return _model


@app.on_event("startup")
def on_startup():
    if WHISPER_PRELOAD:
        try:
            get_model(WHISPER_MODEL)
            print(f"[whisper] preloaded model={WHISPER_MODEL}")
        except Exception as e:
            print(f"[whisper] preload failed (will load on first request): {e}")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "model": WHISPER_MODEL,
        "loaded": _model is not None,
        "device": _device,
        "compute_type": _compute,
        "beam_size": WHISPER_BEAM_SIZE,
        "vad": WHISPER_VAD,
    }


@app.post("/v1/audio/transcriptions")
async def transcribe(
    file: UploadFile = File(...),
    model: str = Form(default=None),
    language: Optional[str] = Form(default=None),
    response_format: str = Form(default="verbose_json"),
    timestamp_granularities: Optional[str] = Form(default=None),
    beam_size: Optional[int] = Form(default=None),
):
    """
    OpenAI 兼容转录接口。
    返回 verbose_json：text + segments[{id,start,end,text}] + duration + language
    """
    suffix = os.path.splitext(file.filename or "audio.mp3")[1] or ".mp3"
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
            content = await file.read()
            tmp.write(content)
            tmp_path = tmp.name
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"failed to save upload: {e}") from e

    try:
        model_name = (model or "").strip() or WHISPER_MODEL
        whisper = get_model(model_name)
        lang = language if language and language.strip() else None
        bs = beam_size if beam_size and beam_size > 0 else WHISPER_BEAM_SIZE

        segments_iter, info = whisper.transcribe(
            tmp_path,
            language=lang,
            vad_filter=WHISPER_VAD,
            beam_size=bs,
            # 略提速：不强制依赖前文上下文（准确率影响通常很小）
            condition_on_previous_text=False,
        )

        segments = []
        full_text_parts = []
        for i, seg in enumerate(segments_iter):
            text = (seg.text or "").strip()
            segments.append(
                {
                    "id": i,
                    "start": float(seg.start),
                    "end": float(seg.end),
                    "text": text,
                }
            )
            if text:
                full_text_parts.append(text)

        result = {
            "text": " ".join(full_text_parts),
            "language": getattr(info, "language", lang),
            "duration": float(getattr(info, "duration", 0.0) or 0.0),
            "segments": segments,
        }

        if response_format == "text":
            return JSONResponse(content={"text": result["text"]})
        return JSONResponse(content=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"transcription failed: {e}") from e
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


if __name__ == "__main__":
    import uvicorn

    # 可由环境变量覆盖（Spring Boot 托管启动时也会设 WHISPER_*）
    host = os.getenv("WHISPER_HOST", "0.0.0.0")
    port = int(os.getenv("WHISPER_PORT", "8000"))
    # port 必须是 int；传字符串会导致 uvicorn 启动日志 TypeError
    uvicorn.run(app, host=host, port=port)
