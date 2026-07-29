#!/usr/bin/env python3
"""从 deploy/env/app.env 生成 okx-bot/.../application-local.yml（gitignore，不提交）。"""
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
ENV_PATH = ROOT / "deploy" / "env" / "app.env"
ENV_LEGACY = ROOT / "deploy" / "app.env"
OUT_PATH = ROOT / "okx-bot" / "src" / "main" / "resources" / "application-local.yml"


def load_env(p: Path) -> dict:
    m = {}
    if not p.exists():
        return m
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        m[k.strip()] = v.strip()
    return m


def g(m, k, d=""):
    return m.get(k) or d


def main():
    m = load_env(ENV_PATH)
    if not m:
        m = load_env(ENV_LEGACY)
        if m:
            print("warn: using legacy", ENV_LEGACY, "→ prefer", ENV_PATH)
    if not m:
        print("warn: no", ENV_PATH, "; AI/R2 fields will be empty")

    ytdlp = shutil.which("yt-dlp") or "yt-dlp"
    ffmpeg = shutil.which("ffmpeg") or "ffmpeg"
    if ytdlp != "yt-dlp":
        ytdlp = ytdlp.replace("\\", "/")
    if ffmpeg != "ffmpeg":
        ffmpeg = ffmpeg.replace("\\", "/")

    local = f"""# 本地开发 — 真实配置，不提交 Git（.gitignore）
# IDE / 启动: spring.profiles.active=local
# R2 前缀 local，与 ec2 区分
# 生成: python deploy/scripts/gen_profile_yml.py

server:
  port: 8080

spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/okx_bot?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: 123456

auth:
  jwt:
    secret: okx-bot-jwt-secret-change-me-in-production-2026-secure-key
    expire-seconds: 7200
    issuer: okx-bot
  mail:
    console-mode: true
    from: noreply@example.com
  admin:
    seed-enabled: true
    email: admin@okx-bot.local
    password: Admin@123456
    nickname: 超级管理员

pay:
  enabled: true
  public-base-url: http://127.0.0.1:8080
  mock-enabled: true

ai:
  default-provider: nvidia
  providers:
    openai:
      name: OpenAI
      base-url: https://api.openai.com/v1
      api-key: "{g(m, 'AI_OPENAI_API_KEY')}"
      models: []
    deepseek:
      name: DeepSeek
      base-url: https://api.deepseek.com/v1
      api-key: "{g(m, 'AI_DEEPSEEK_API_KEY')}"
      models: []
    nvidia:
      name: NVIDIA NIM
      base-url: https://integrate.api.nvidia.com/v1
      api-key: "{g(m, 'AI_NVIDIA_API_KEY')}"
      models: []

storage:
  provider: r2
  env-prefix: local
  serve-mode: presign
  local:
    root: ./data/_objects
  scratch:
    root: ./data/_scratch
    ttl-hours: 24
  cleanup:
    scratch-on-success: true
    scratch-on-failure: true
    r2-on-failure: true
  r2:
    account-id: {g(m, 'R2_ACCOUNT_ID')}
    bucket: {g(m, 'R2_BUCKET')}
    access-key-id: {g(m, 'R2_ACCESS_KEY_ID')}
    secret-access-key: {g(m, 'R2_SECRET_ACCESS_KEY')}
    endpoint: {g(m, 'R2_ENDPOINT')}
    region: auto
    path-style: true
    presign-ttl-seconds: 900
    public-base-url: ""
    multipart-threshold-bytes: 104857600
    multipart-part-size-bytes: 8388608

video:
  work-dir: ./data/video
  yt-dlp-path: {ytdlp}
  ffmpeg-path: {ffmpeg}
  cleanup-media: false
  whisper:
    base-url: http://127.0.0.1:8000
    model: small
    managed:
      enabled: true
      working-dir: ./whisper-service

aigen:
  work-dir: ./data/aigen
  remotion:
    manage-process: true
    project-dir: ../aigen-remotion

imggen:
  work-dir: ./data/imggen

logging:
  level:
    root: INFO
    com.dwcode.okxbot: DEBUG
"""

    OUT_PATH.write_text(local, encoding="utf-8")
    print("wrote", OUT_PATH, "(gitignored)")
    print("env source:", ENV_PATH if ENV_PATH.exists() else ENV_LEGACY)
    print("ec2 config: okx-bot/src/main/resources/application-ec2.yml")


if __name__ == "__main__":
    main()
