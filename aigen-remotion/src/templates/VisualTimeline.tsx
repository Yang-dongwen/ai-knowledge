import React from "react";
import {
  AbsoluteFill,
  Audio,
  Img,
  Sequence,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

export type VisualShot = {
  id: string;
  durationInFrames?: number;
  startFrame?: number;
  audioSrc?: string;
  audioUrl?: string;
  visual?: {
    type?: string;
    assetUrl?: string;
    assetPath?: string;
    prompt?: string;
  };
  motion?: { type?: string; params?: Record<string, unknown> };
  transition?: { type?: string; durationFrames?: number };
  overlay?: {
    layout?: string;
    title?: string;
    subtitle?: string;
    bullets?: string[];
  };
};

export type ShotlistProps = {
  version?: string;
  meta?: {
    title?: string;
    fps?: number;
    width?: number;
    height?: number;
    durationInFrames?: number;
    stylePreset?: string;
  };
  audio?: {
    mode?: string;
    bgmUrl?: string;
    bgmSrc?: string;
  };
  shots?: VisualShot[];
};

const defaultShotlist: ShotlistProps = {
  version: "vt-1.0",
  meta: {
    title: "Visual Timeline",
    fps: 30,
    width: 1080,
    height: 1920,
    durationInFrames: 300,
    stylePreset: "cinematic-dark",
  },
  audio: { mode: "none" },
  shots: [
    {
      id: "shot-1",
      durationInFrames: 90,
      startFrame: 0,
      motion: { type: "ken_burns" },
      overlay: { layout: "hook-center", title: "Visual Timeline", subtitle: "画面优先" },
    },
    {
      id: "shot-2",
      durationInFrames: 90,
      startFrame: 90,
      motion: { type: "pan_left" },
      overlay: { layout: "lower-third", title: "镜头二" },
    },
    {
      id: "shot-3",
      durationInFrames: 120,
      startFrame: 180,
      motion: { type: "zoom_out" },
      overlay: {
        layout: "bullets-right",
        title: "要点",
        bullets: ["画面为主", "音频可选", "单镜可重生"],
      },
    },
  ],
};

function gradientCss(seed: number): string {
  const a = 30 + (seed * 47) % 80;
  const b = 40 + (seed * 31) % 100;
  return `linear-gradient(145deg, hsl(${a}, 55%, 18%), hsl(${b}, 60%, 32%))`;
}

function motionTransform(motion: string, frame: number, dur: number): string {
  const m = (motion || "ken_burns").toLowerCase();
  if (m === "static") {
    return "scale(1)";
  }
  if (m === "zoom_in" || m === "ken_burns") {
    const scale = interpolate(frame, [0, dur], [1, 1.14], { extrapolateRight: "clamp" });
    return `scale(${scale})`;
  }
  if (m === "zoom_out") {
    const scale = interpolate(frame, [0, dur], [1.14, 1], { extrapolateRight: "clamp" });
    return `scale(${scale})`;
  }
  if (m === "pan_left") {
    const x = interpolate(frame, [0, dur], [4, -4], { extrapolateRight: "clamp" });
    const scale = interpolate(frame, [0, dur], [1.08, 1.08], { extrapolateRight: "clamp" });
    return `scale(${scale}) translateX(${x}%)`;
  }
  if (m === "pan_right") {
    const x = interpolate(frame, [0, dur], [-4, 4], { extrapolateRight: "clamp" });
    const scale = 1.08;
    return `scale(${scale}) translateX(${x}%)`;
  }
  const scale = interpolate(frame, [0, dur], [1, 1.12], { extrapolateRight: "clamp" });
  return `scale(${scale})`;
}

const ShotLayer: React.FC<{ shot: VisualShot; index: number }> = ({ shot, index }) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  const dur = shot.durationInFrames || durationInFrames;
  const motion = shot.motion?.type || "ken_burns";
  const transform = motionTransform(motion, frame, dur);

  const fadeFrames = Math.min(12, Math.floor(dur / 4));
  const opacity = interpolate(
    frame,
    [0, fadeFrames, Math.max(fadeFrames + 1, dur - fadeFrames), dur],
    [0, 1, 1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" }
  );

  const src = shot.visual?.assetUrl;
  const layout = (shot.overlay?.layout || "hook-center").toLowerCase();
  const title = shot.overlay?.title;
  const subtitle = shot.overlay?.subtitle;
  const bullets = shot.overlay?.bullets || [];
  const voice = shot.audioUrl;

  return (
    <AbsoluteFill style={{ opacity }}>
      {src ? (
        <AbsoluteFill style={{ transform, overflow: "hidden" }}>
          <Img
            src={src}
            style={{ width: "100%", height: "100%", objectFit: "cover" }}
          />
        </AbsoluteFill>
      ) : (
        <AbsoluteFill
          style={{
            background: gradientCss(index + 1),
            transform,
          }}
        />
      )}
      <AbsoluteFill
        style={{
          background:
            "linear-gradient(180deg, rgba(0,0,0,0.35) 0%, rgba(0,0,0,0.1) 40%, rgba(0,0,0,0.55) 100%)",
        }}
      />
      {layout !== "none" && (
        <AbsoluteFill style={{ padding: 64, justifyContent: "center" }}>
          {layout === "hook-center" && (
            <div style={{ textAlign: "center", alignSelf: "center" }}>
              {title && (
                <div
                  style={{
                    color: "#fff",
                    fontSize: 64,
                    fontWeight: 800,
                    lineHeight: 1.2,
                    textShadow: "0 4px 24px rgba(0,0,0,0.6)",
                  }}
                >
                  {title}
                </div>
              )}
              {subtitle && (
                <div
                  style={{
                    color: "rgba(255,255,255,0.9)",
                    fontSize: 32,
                    marginTop: 20,
                    textShadow: "0 2px 12px rgba(0,0,0,0.5)",
                  }}
                >
                  {subtitle}
                </div>
              )}
            </div>
          )}
          {layout === "lower-third" && (
            <div style={{ alignSelf: "flex-end", width: "100%", marginBottom: 80 }}>
              <div
                style={{
                  background: "rgba(0,0,0,0.55)",
                  borderLeft: "6px solid #38bdf8",
                  padding: "20px 28px",
                  borderRadius: 8,
                }}
              >
                {title && (
                  <div style={{ color: "#fff", fontSize: 40, fontWeight: 700 }}>{title}</div>
                )}
                {subtitle && (
                  <div style={{ color: "rgba(255,255,255,0.85)", fontSize: 26, marginTop: 8 }}>
                    {subtitle}
                  </div>
                )}
              </div>
            </div>
          )}
          {layout === "bullets-right" && (
            <div style={{ alignSelf: "center", marginLeft: "auto", maxWidth: "55%" }}>
              {title && (
                <div style={{ color: "#7dd3fc", fontSize: 28, marginBottom: 16, fontWeight: 600 }}>
                  {title}
                </div>
              )}
              {bullets.map((b, i) => (
                <div
                  key={i}
                  style={{
                    color: "#fff",
                    fontSize: 30,
                    marginBottom: 12,
                    textShadow: "0 2px 10px rgba(0,0,0,0.5)",
                  }}
                >
                  • {b}
                </div>
              ))}
            </div>
          )}
          {layout === "caption" && title && (
            <div
              style={{
                alignSelf: "flex-end",
                width: "100%",
                textAlign: "center",
                marginBottom: 100,
                color: "#fff",
                fontSize: 34,
                fontWeight: 600,
                textShadow: "0 2px 14px rgba(0,0,0,0.7)",
              }}
            >
              {title}
            </div>
          )}
        </AbsoluteFill>
      )}
      {voice ? <Audio src={voice} volume={1} /> : null}
    </AbsoluteFill>
  );
};

export const VisualTimeline: React.FC<ShotlistProps> = (props) => {
  const data = props?.shots?.length ? props : defaultShotlist;
  const shots = data.shots || [];
  const bgm = data.audio?.bgmUrl;

  let cursor = 0;
  const sequences = shots.map((shot, index) => {
    const start = shot.startFrame != null ? shot.startFrame : cursor;
    const dur = Math.max(1, shot.durationInFrames || 90);
    cursor = start + dur;
    return (
      <Sequence key={shot.id || index} from={start} durationInFrames={dur} name={shot.id}>
        <ShotLayer shot={shot} index={index} />
      </Sequence>
    );
  });

  return (
    <AbsoluteFill style={{ backgroundColor: "#0a0a0f" }}>
      {sequences}
      {bgm ? <Audio src={bgm} volume={0.28} /> : null}
    </AbsoluteFill>
  );
};

export { defaultShotlist };
