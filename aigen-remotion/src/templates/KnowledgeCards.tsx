import React from "react";
import {
  AbsoluteFill,
  Audio,
  Sequence,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import type { AudioTrack, Scene, Storyboard, Subtitle } from "../types";

/**
 * Remotion Audio 只能拉取 http(s)（不能 file:// 或盘符路径）。
 * 本地路径兜底：…/aigen/{taskId}/assets/audio/xx → http://127.0.0.1:3100/media/{taskId}/assets/audio/xx
 */
function resolvePlayableAudio(track: AudioTrack | undefined): string | null {
  if (!track || track.mock) return null;
  let src = (track.src || track.absSrc || "").trim();
  if (!src) return null;
  const lower = src.toLowerCase();
  if (lower.endsWith(".txt") || lower.includes(".mock")) return null;
  if (lower.startsWith("http://") || lower.startsWith("https://")) {
    return src;
  }
  // file:///D:/... 或 D:\... 或 D:/...
  src = src.replace(/^file:\/\//i, "").replace(/^\/([A-Za-z]:)/, "$1");
  const norm = src.replace(/\\/g, "/");
  const m = norm.match(/\/aigen\/([^/]+)\/(assets\/audio\/[^?]+)$/i);
  if (m) {
    return `http://127.0.0.1:3100/media/${m[1]}/${m[2]}`;
  }
  return null;
}

export const defaultStoryboard: Storyboard = {
  version: "1.0",
  meta: {
    title: "Demo",
    language: "zh",
    templateId: "knowledge-cards",
    fps: 30,
    width: 1080,
    height: 1920,
    durationInFrames: 300,
  },
  style: { theme: "tech-dark", primaryColor: "#6366F1" },
  scenes: [
    {
      id: "s1",
      type: "title",
      startFrame: 0,
      durationInFrames: 90,
      narration: "欢迎使用 AI 视频生成",
      props: { title: "AI 视频生成", subtitle: "Knowledge Cards" },
    },
    {
      id: "s2",
      type: "bullets",
      startFrame: 90,
      durationInFrames: 150,
      narration: "要点展示",
      props: {
        heading: "核心要点",
        items: ["提示词规划分镜", "自动渲染成片", "网页在线播放"],
      },
    },
    {
      id: "s3",
      type: "outro",
      startFrame: 240,
      durationInFrames: 60,
      narration: "感谢观看",
      props: { title: "感谢观看", cta: "点赞收藏" },
    },
  ],
  subtitles: [],
};

const bg = (primary: string) =>
  `linear-gradient(160deg, #0f172a 0%, #1e1b4b 45%, ${primary}33 100%)`;

const TitleScene: React.FC<{ scene: Scene; primary: string }> = ({ scene, primary }) => {
  const frame = useCurrentFrame();
  const opacity = interpolate(frame, [0, 15], [0, 1], { extrapolateRight: "clamp" });
  const y = interpolate(frame, [0, 20], [40, 0], { extrapolateRight: "clamp" });
  return (
    <AbsoluteFill
      style={{
        background: bg(primary),
        justifyContent: "center",
        alignItems: "center",
        padding: 80,
      }}
    >
      <div style={{ opacity, transform: `translateY(${y}px)`, textAlign: "center" }}>
        <div
          style={{
            fontSize: 72,
            fontWeight: 800,
            color: "#fff",
            lineHeight: 1.2,
            textShadow: "0 8px 32px rgba(0,0,0,0.35)",
          }}
        >
          {scene.props?.title || "标题"}
        </div>
        {scene.props?.subtitle ? (
          <div style={{ marginTop: 28, fontSize: 36, color: "#c7d2fe", fontWeight: 500 }}>
            {scene.props.subtitle}
          </div>
        ) : null}
      </div>
    </AbsoluteFill>
  );
};

const BulletsScene: React.FC<{ scene: Scene; primary: string }> = ({ scene, primary }) => {
  const frame = useCurrentFrame();
  const items = scene.props?.items || [];
  return (
    <AbsoluteFill
      style={{
        background: bg(primary),
        justifyContent: "center",
        padding: "100px 80px",
      }}
    >
      <div style={{ fontSize: 42, color: "#a5b4fc", fontWeight: 600, marginBottom: 40 }}>
        {scene.props?.heading || "要点"}
      </div>
      {items.map((item, i) => {
        const start = 8 + i * 12;
        const opacity = interpolate(frame, [start, start + 12], [0, 1], {
          extrapolateLeft: "clamp",
          extrapolateRight: "clamp",
        });
        const x = interpolate(frame, [start, start + 12], [30, 0], {
          extrapolateLeft: "clamp",
          extrapolateRight: "clamp",
        });
        return (
          <div
            key={i}
            style={{
              opacity,
              transform: `translateX(${x}px)`,
              display: "flex",
              alignItems: "center",
              gap: 20,
              marginBottom: 28,
            }}
          >
            <div
              style={{
                width: 16,
                height: 16,
                borderRadius: 999,
                background: primary,
                flexShrink: 0,
              }}
            />
            <div style={{ fontSize: 40, color: "#f8fafc", fontWeight: 600, lineHeight: 1.35 }}>
              {item}
            </div>
          </div>
        );
      })}
    </AbsoluteFill>
  );
};

const OutroScene: React.FC<{ scene: Scene; primary: string }> = ({ scene, primary }) => {
  const frame = useCurrentFrame();
  const scale = interpolate(frame, [0, 20], [0.9, 1], { extrapolateRight: "clamp" });
  const opacity = interpolate(frame, [0, 12], [0, 1], { extrapolateRight: "clamp" });
  return (
    <AbsoluteFill
      style={{
        background: bg(primary),
        justifyContent: "center",
        alignItems: "center",
      }}
    >
      <div style={{ opacity, transform: `scale(${scale})`, textAlign: "center" }}>
        <div style={{ fontSize: 64, fontWeight: 800, color: "#fff" }}>
          {scene.props?.title || "感谢观看"}
        </div>
        {scene.props?.cta ? (
          <div
            style={{
              marginTop: 36,
              display: "inline-block",
              padding: "16px 36px",
              borderRadius: 999,
              background: primary,
              color: "#fff",
              fontSize: 32,
              fontWeight: 700,
            }}
          >
            {scene.props.cta}
          </div>
        ) : null}
      </div>
    </AbsoluteFill>
  );
};

const SubtitleBar: React.FC<{ subtitles: Subtitle[] }> = ({ subtitles }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const ms = (frame / fps) * 1000;
  const current = subtitles.find((s) => ms >= s.startMs && ms <= s.endMs);
  if (!current?.text) return null;
  return (
    <AbsoluteFill style={{ justifyContent: "flex-end", alignItems: "center", paddingBottom: 120 }}>
      <div
        style={{
          maxWidth: "86%",
          background: "rgba(15,23,42,0.72)",
          color: "#fff",
          fontSize: 32,
          lineHeight: 1.4,
          padding: "14px 28px",
          borderRadius: 16,
          textAlign: "center",
        }}
      >
        {current.text}
      </div>
    </AbsoluteFill>
  );
};

export const KnowledgeCards: React.FC<Storyboard> = (props) => {
  const data = props?.meta ? props : defaultStoryboard;
  const primary = data.style?.primaryColor || "#6366F1";
  const scenes = data.scenes || [];
  const subtitles = data.subtitles || [];
  const tracks = data.audio?.tracks || [];

  return (
    <AbsoluteFill style={{ backgroundColor: "#0f172a", fontFamily: "system-ui, sans-serif" }}>
      {scenes.map((scene) => {
        const track = tracks.find((t) => t.sceneId === scene.id);
        const audioSrc = resolvePlayableAudio(track);
        return (
          <Sequence
            key={scene.id}
            from={scene.startFrame || 0}
            durationInFrames={Math.max(1, scene.durationInFrames || 30)}
            name={scene.id}
          >
            {scene.type === "title" ? (
              <TitleScene scene={scene} primary={primary} />
            ) : scene.type === "outro" ? (
              <OutroScene scene={scene} primary={primary} />
            ) : (
              <BulletsScene scene={scene} primary={primary} />
            )}
            {audioSrc ? <Audio src={audioSrc} /> : null}
          </Sequence>
        );
      })}
      <SubtitleBar subtitles={subtitles} />
    </AbsoluteFill>
  );
};
