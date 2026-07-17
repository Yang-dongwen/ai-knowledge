import React from "react";
import {
  AbsoluteFill,
  Audio,
  Img,
  OffthreadVideo,
  Sequence,
  interpolate,
  spring,
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
    /** 动效视频相对路径（后端合成时优先写入 assetUrl） */
    videoPath?: string;
    prompt?: string;
  };
  motion?: {
    type?: string;
    params?: Record<string, unknown>;
  };
  transition?: {
    type?: string;
    durationFrames?: number;
  };
  overlay?: {
    layout?: string;
    title?: string;
    subtitle?: string;
    bullets?: string[];
    position?: string;
    style?: string;
    textAnim?: string;
  };
  notes?: string;
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

const FONT =
  'system-ui, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif';

const AUTO_MOTIONS = [
  "drift",
  "punch_in",
  "orbit",
  "rise",
  "whip",
  "ken_burns",
  "tilt",
  "punch_out",
] as const;

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
      motion: { type: "drift", params: { intensity: 0.7 } },
      overlay: {
        layout: "none",
        title: "",
        textAnim: "pop",
      },
    },
    {
      id: "shot-2",
      durationInFrames: 90,
      startFrame: 90,
      motion: { type: "punch_in", params: { intensity: 0.8 } },
      overlay: {
        layout: "free",
        title: "自由动效",
        subtitle: "非模板填空",
        position: "bottom",
        style: "cinematic",
        textAnim: "slide_up",
      },
    },
    {
      id: "shot-3",
      durationInFrames: 120,
      startFrame: 180,
      motion: { type: "orbit", params: { intensity: 0.65 } },
      overlay: {
        layout: "big-word",
        title: "IMAGINE",
        style: "neon",
        textAnim: "glitch",
      },
    },
  ],
};

function num(v: unknown, fallback: number): number {
  if (typeof v === "number" && Number.isFinite(v)) {
    return v;
  }
  if (typeof v === "string" && v.trim() !== "" && Number.isFinite(Number(v))) {
    return Number(v);
  }
  return fallback;
}

function resolveMotionType(type: string | undefined, index: number): string {
  const t = (type || "auto").toLowerCase();
  if (t === "auto" || t === "") {
    return AUTO_MOTIONS[index % AUTO_MOTIONS.length];
  }
  return t;
}

type MotionResult = {
  transform: string;
  filter?: string;
};

/**
 * 自由运镜引擎：由 type + params 驱动，不绑死固定模板动作。
 */
function computeMotion(
  type: string,
  frame: number,
  dur: number,
  params: Record<string, unknown> | undefined,
  index: number
): MotionResult {
  const p = params || {};
  const intensity = Math.min(1.2, Math.max(0.15, num(p.intensity, 0.55 + (index % 4) * 0.08)));
  const t = Math.max(1, dur);
  const progress = Math.min(1, Math.max(0, frame / t));
  // smoothstep for organic feel
  const ease = progress * progress * (3 - 2 * progress);
  const inv = 1 - ease;

  const scaleFrom = num(p.scaleFrom, NaN);
  const scaleTo = num(p.scaleTo, NaN);
  const xFrom = num(p.xFrom, NaN);
  const xTo = num(p.xTo, NaN);
  const yFrom = num(p.yFrom, NaN);
  const yTo = num(p.yTo, NaN);
  const rotFrom = num(p.rotateFrom, NaN);
  const rotTo = num(p.rotateTo, NaN);

  let scale = 1;
  let x = 0;
  let y = 0;
  let rotate = 0;
  let filter = "";

  const hasCustom =
    Number.isFinite(scaleFrom) ||
    Number.isFinite(scaleTo) ||
    Number.isFinite(xFrom) ||
    Number.isFinite(xTo) ||
    Number.isFinite(yFrom) ||
    Number.isFinite(yTo) ||
    Number.isFinite(rotFrom) ||
    Number.isFinite(rotTo);

  if (hasCustom) {
    const sf = Number.isFinite(scaleFrom) ? scaleFrom : 1;
    const st = Number.isFinite(scaleTo) ? scaleTo : 1 + 0.12 * intensity;
    const xf = Number.isFinite(xFrom) ? xFrom : 0;
    const xt = Number.isFinite(xTo) ? xTo : 0;
    const yf = Number.isFinite(yFrom) ? yFrom : 0;
    const yt = Number.isFinite(yTo) ? yTo : 0;
    const rf = Number.isFinite(rotFrom) ? rotFrom : 0;
    const rt = Number.isFinite(rotTo) ? rotTo : 0;
    scale = sf + (st - sf) * ease;
    x = xf + (xt - xf) * ease;
    y = yf + (yt - yf) * ease;
    rotate = rf + (rt - rf) * ease;
  } else {
    switch (type) {
      case "static":
        scale = 1.02;
        break;
      case "zoom_in":
      case "ken_burns":
        scale = interpolate(ease, [0, 1], [1, 1 + 0.16 * intensity]);
        x = interpolate(ease, [0, 1], [0, -1.5 * intensity]);
        y = interpolate(ease, [0, 1], [0, 1.2 * intensity]);
        break;
      case "zoom_out":
        scale = interpolate(ease, [0, 1], [1 + 0.18 * intensity, 1]);
        break;
      case "pan_left":
        scale = 1.1 + 0.04 * intensity;
        x = interpolate(ease, [0, 1], [5 * intensity, -5 * intensity]);
        break;
      case "pan_right":
        scale = 1.1 + 0.04 * intensity;
        x = interpolate(ease, [0, 1], [-5 * intensity, 5 * intensity]);
        break;
      case "punch_in": {
        // 前 30% 快速推近，后段微漂
        const punch = spring({
          frame,
          fps: 30,
          config: { damping: 14, stiffness: 120, mass: 0.7 },
          durationInFrames: Math.min(t, 28),
        });
        scale = 1 + 0.22 * intensity * punch + 0.03 * intensity * ease;
        y = -1.5 * intensity * punch;
        break;
      }
      case "punch_out":
        scale = interpolate(ease, [0, 1], [1 + 0.2 * intensity, 1]);
        y = interpolate(ease, [0, 1], [-2 * intensity, 0]);
        break;
      case "whip": {
        const whipEase = Math.min(1, progress * 1.6);
        const we = whipEase * whipEase * (3 - 2 * whipEase);
        x = interpolate(we, [0, 1], [12 * intensity, -2 * intensity]);
        scale = 1.08 + 0.06 * intensity * inv;
        filter = `blur(${Math.max(0, 4 * intensity * (1 - we))}px)`;
        break;
      }
      case "drift":
        scale = 1.08 + 0.04 * intensity;
        x = Math.sin(progress * Math.PI * 2) * 2.2 * intensity;
        y = Math.cos(progress * Math.PI) * 1.6 * intensity;
        rotate = Math.sin(progress * Math.PI) * 1.2 * intensity;
        break;
      case "shake": {
        const amp = 1.4 * intensity * (1 - progress * 0.5);
        x = Math.sin(frame * 1.7) * amp;
        y = Math.cos(frame * 2.1) * amp * 0.7;
        scale = 1.06;
        break;
      }
      case "orbit":
        scale = 1.12 + 0.04 * intensity;
        x = Math.sin(progress * Math.PI * 2) * 4 * intensity;
        y = Math.cos(progress * Math.PI * 2) * 2.5 * intensity;
        rotate = Math.sin(progress * Math.PI * 2) * 2 * intensity;
        break;
      case "tilt":
        scale = 1.1;
        rotate = interpolate(ease, [0, 1], [-3.5 * intensity, 3.5 * intensity]);
        y = interpolate(ease, [0, 1], [1 * intensity, -1 * intensity]);
        break;
      case "rise":
        scale = interpolate(ease, [0, 1], [1.14, 1.05]);
        y = interpolate(ease, [0, 1], [6 * intensity, -2 * intensity]);
        break;
      case "fall":
        scale = interpolate(ease, [0, 1], [1.05, 1.14]);
        y = interpolate(ease, [0, 1], [-3 * intensity, 5 * intensity]);
        break;
      default:
        scale = interpolate(ease, [0, 1], [1, 1 + 0.12 * intensity]);
        x = interpolate(ease, [0, 1], [0, -2 * intensity]);
        break;
    }
  }

  return {
    transform: `translate(${x}%, ${y}%) scale(${scale}) rotate(${rotate}deg)`,
    filter: filter || undefined,
  };
}

function gradeForPreset(preset?: string): {
  overlay: string;
  vignette: string;
  saturate: number;
  contrast: number;
} {
  const p = (preset || "cinematic-dark").toLowerCase();
  if (p.includes("vibrant") || p.includes("social")) {
    return {
      overlay:
        "linear-gradient(180deg, rgba(255,80,120,0.12) 0%, rgba(0,0,0,0.05) 40%, rgba(20,20,60,0.45) 100%)",
      vignette:
        "radial-gradient(ellipse at center, transparent 45%, rgba(0,0,0,0.45) 100%)",
      saturate: 1.15,
      contrast: 1.08,
    };
  }
  if (p.includes("soft") || p.includes("light")) {
    return {
      overlay:
        "linear-gradient(180deg, rgba(255,255,255,0.08) 0%, rgba(0,0,0,0.05) 50%, rgba(0,0,0,0.35) 100%)",
      vignette:
        "radial-gradient(ellipse at center, transparent 55%, rgba(0,0,0,0.28) 100%)",
      saturate: 1.02,
      contrast: 1.02,
    };
  }
  // cinematic-dark default
  return {
    overlay:
      "linear-gradient(180deg, rgba(0,0,0,0.4) 0%, rgba(0,0,0,0.08) 42%, rgba(0,0,0,0.58) 100%)",
    vignette:
      "radial-gradient(ellipse at center, transparent 40%, rgba(0,0,0,0.55) 100%)",
    saturate: 1.06,
    contrast: 1.1,
  };
}

function transitionOpacity(
  frame: number,
  dur: number,
  type: string | undefined,
  fadeFrames: number
): number {
  const tt = (type || "crossfade").toLowerCase();
  if (tt === "hard_cut") {
    return 1;
  }
  if (tt === "flash") {
    const flashIn = interpolate(frame, [0, 3, 8], [0, 1.2, 1], {
      extrapolateRight: "clamp",
    });
    const flashOut = interpolate(frame, [dur - 6, dur], [1, 0], {
      extrapolateLeft: "clamp",
      extrapolateRight: "clamp",
    });
    return Math.min(flashIn, flashOut);
  }
  if (tt === "dip_black" || tt === "dip_white") {
    const edge = Math.max(4, Math.floor(fadeFrames * 1.2));
    return interpolate(
      frame,
      [0, edge, Math.max(edge + 1, dur - edge), dur],
      [0, 1, 1, 0],
      { extrapolateLeft: "clamp", extrapolateRight: "clamp" }
    );
  }
  // crossfade / wipe* 用透明度近似（wipe 叠加 clip 另做）
  return interpolate(
    frame,
    [0, fadeFrames, Math.max(fadeFrames + 1, dur - fadeFrames), dur],
    [0, 1, 1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" }
  );
}

function wipeClip(
  frame: number,
  dur: number,
  type: string | undefined
): string | undefined {
  const tt = (type || "").toLowerCase();
  if (tt !== "wipe_left" && tt !== "wipe_right") {
    return undefined;
  }
  const edge = Math.min(18, Math.floor(dur / 3));
  const p = interpolate(frame, [0, edge], [0, 100], {
    extrapolateRight: "clamp",
  });
  if (tt === "wipe_left") {
    return `inset(0 ${100 - p}% 0 0)`;
  }
  return `inset(0 0 0 ${100 - p}%)`;
}

type TextStyle = {
  color: string;
  fontSize: number;
  fontWeight: number;
  letterSpacing?: string;
  textShadow: string;
  subColor: string;
  subSize: number;
};

function resolveTextStyle(
  style: string | undefined,
  layout: string,
  height: number
): TextStyle {
  const s = (style || "cinematic").toLowerCase();
  const base = height >= 1600 ? 1 : height >= 1000 ? 0.85 : 0.7;
  if (s === "neon") {
    return {
      color: "#e0f2fe",
      fontSize: (layout === "big-word" ? 96 : 48) * base,
      fontWeight: 800,
      letterSpacing: "0.06em",
      textShadow:
        "0 0 12px #38bdf8, 0 0 28px #818cf8, 0 4px 20px rgba(0,0,0,0.6)",
      subColor: "rgba(186,230,253,0.9)",
      subSize: 28 * base,
    };
  }
  if (s === "soft") {
    return {
      color: "rgba(255,255,255,0.95)",
      fontSize: (layout === "big-word" ? 72 : 40) * base,
      fontWeight: 500,
      textShadow: "0 2px 18px rgba(0,0,0,0.45)",
      subColor: "rgba(255,255,255,0.75)",
      subSize: 24 * base,
    };
  }
  if (s === "bold-impact") {
    return {
      color: "#fff",
      fontSize: (layout === "big-word" ? 100 : 56) * base,
      fontWeight: 900,
      letterSpacing: "-0.02em",
      textShadow: "0 6px 28px rgba(0,0,0,0.75)",
      subColor: "rgba(255,255,255,0.9)",
      subSize: 30 * base,
    };
  }
  if (s === "minimal") {
    return {
      color: "#f8fafc",
      fontSize: (layout === "big-word" ? 64 : 34) * base,
      fontWeight: 400,
      letterSpacing: "0.12em",
      textShadow: "0 2px 10px rgba(0,0,0,0.4)",
      subColor: "rgba(248,250,252,0.7)",
      subSize: 20 * base,
    };
  }
  // cinematic
  return {
    color: "#fff",
    fontSize: (layout === "big-word" ? 88 : 46) * base,
    fontWeight: 700,
    textShadow: "0 4px 24px rgba(0,0,0,0.65)",
    subColor: "rgba(255,255,255,0.88)",
    subSize: 26 * base,
  };
}

function textEntrance(
  frame: number,
  fps: number,
  anim: string | undefined
): React.CSSProperties {
  const a = (anim || "pop").toLowerCase();
  if (a === "none") {
    return { opacity: 1 };
  }
  if (a === "fade") {
    return {
      opacity: interpolate(frame, [0, 16], [0, 1], { extrapolateRight: "clamp" }),
    };
  }
  if (a === "slide_up") {
    const y = interpolate(frame, [0, 18], [40, 0], { extrapolateRight: "clamp" });
    const op = interpolate(frame, [0, 14], [0, 1], { extrapolateRight: "clamp" });
    return { opacity: op, transform: `translateY(${y}px)` };
  }
  if (a === "slide_left") {
    const x = interpolate(frame, [0, 18], [48, 0], { extrapolateRight: "clamp" });
    const op = interpolate(frame, [0, 14], [0, 1], { extrapolateRight: "clamp" });
    return { opacity: op, transform: `translateX(${x}px)` };
  }
  if (a === "typewriter") {
    return {
      opacity: interpolate(frame, [0, 8], [0, 1], { extrapolateRight: "clamp" }),
      clipPath: `inset(0 ${Math.max(0, 100 - (frame / 20) * 100)}% 0 0)`,
    };
  }
  if (a === "glitch") {
    const jx = frame % 7 === 0 ? 3 : frame % 5 === 0 ? -2 : 0;
    const jy = frame % 11 === 0 ? -2 : 0;
    const op = interpolate(frame, [0, 10], [0, 1], { extrapolateRight: "clamp" });
    return {
      opacity: op,
      transform: `translate(${jx}px, ${jy}px)`,
      filter: frame % 13 === 0 ? "hue-rotate(30deg)" : undefined,
    };
  }
  // pop
  const s = spring({
    frame,
    fps,
    config: { damping: 12, stiffness: 160, mass: 0.6 },
  });
  return {
    opacity: interpolate(s, [0, 1], [0, 1]),
    transform: `scale(${interpolate(s, [0, 1], [0.86, 1])})`,
  };
}

function positionStyle(position?: string): React.CSSProperties {
  const p = (position || "center").toLowerCase();
  const base: React.CSSProperties = {
    position: "absolute",
    display: "flex",
    flexDirection: "column",
    padding: 48,
    maxWidth: "88%",
  };
  switch (p) {
    case "top":
      return { ...base, top: 72, left: "50%", transform: "translateX(-50%)", alignItems: "center", textAlign: "center" };
    case "bottom":
      return { ...base, bottom: 96, left: "50%", transform: "translateX(-50%)", alignItems: "center", textAlign: "center" };
    case "left":
      return { ...base, left: 48, top: "50%", transform: "translateY(-50%)", alignItems: "flex-start", textAlign: "left" };
    case "right":
      return { ...base, right: 48, top: "50%", transform: "translateY(-50%)", alignItems: "flex-end", textAlign: "right" };
    case "lower-left":
      return { ...base, left: 48, bottom: 100, alignItems: "flex-start", textAlign: "left" };
    case "lower-right":
      return { ...base, right: 48, bottom: 100, alignItems: "flex-end", textAlign: "right" };
    default:
      return { ...base, left: "50%", top: "50%", transform: "translate(-50%, -50%)", alignItems: "center", textAlign: "center" };
  }
}

function gradientCss(seed: number): string {
  const a = 30 + (seed * 47) % 80;
  const b = 40 + (seed * 31) % 100;
  return `linear-gradient(145deg, hsl(${a}, 55%, 14%), hsl(${b}, 60%, 28%))`;
}

function isVideoAsset(url?: string, path?: string, type?: string): boolean {
  const t = (type || "").toLowerCase();
  if (t === "ai_video") {
    return true;
  }
  const s = `${url || ""} ${path || ""}`.toLowerCase();
  return /\.(mp4|webm|mov)(\?|$)/.test(s);
}

/** 浮动尘埃/光点层：增强感官，不依赖模板 */
const AtmosphereLayer: React.FC<{ frame: number; index: number }> = ({
  frame,
  index,
}) => {
  const dots = Array.from({ length: 14 }, (_, i) => {
    const seed = index * 17 + i * 31;
    const x = (seed * 47) % 100;
    const yBase = (seed * 29) % 100;
    const drift = Math.sin((frame + seed) * 0.04) * 4;
    const y = (yBase + frame * (0.08 + (i % 5) * 0.02) + drift) % 110;
    const size = 2 + (i % 4);
    const op = 0.08 + (i % 5) * 0.03;
    return { x, y, size, op };
  });
  return (
    <AbsoluteFill style={{ pointerEvents: "none", overflow: "hidden" }}>
      {dots.map((d, i) => (
        <div
          key={i}
          style={{
            position: "absolute",
            left: `${d.x}%`,
            top: `${d.y}%`,
            width: d.size,
            height: d.size,
            borderRadius: "50%",
            background: "rgba(255,255,255,0.85)",
            opacity: d.op,
            boxShadow: "0 0 6px rgba(255,255,255,0.35)",
          }}
        />
      ))}
      {/* 呼吸光晕 */}
      <div
        style={{
          position: "absolute",
          inset: "-10%",
          background: `radial-gradient(ellipse at ${50 + Math.sin(frame * 0.03) * 10}% ${40 + Math.cos(frame * 0.025) * 8}%, rgba(255,255,255,0.07) 0%, transparent 55%)`,
          opacity: 0.7,
        }}
      />
    </AbsoluteFill>
  );
};

const ShotLayer: React.FC<{
  shot: VisualShot;
  index: number;
  stylePreset?: string;
}> = ({ shot, index, stylePreset }) => {
  const frame = useCurrentFrame();
  const { durationInFrames, fps, height } = useVideoConfig();
  const dur = shot.durationInFrames || durationInFrames;
  const motionType = resolveMotionType(shot.motion?.type, index);
  const motion = computeMotion(motionType, frame, dur, shot.motion?.params, index);
  const fadeFrames = Math.min(
    14,
    Math.max(6, shot.transition?.durationFrames || 10)
  );
  const opacity = transitionOpacity(frame, dur, shot.transition?.type, fadeFrames);
  const clip = wipeClip(frame, dur, shot.transition?.type);
  const grade = gradeForPreset(stylePreset);

  const src = shot.visual?.assetUrl;
  const asVideo = isVideoAsset(src, shot.visual?.assetPath, shot.visual?.type);
  // 已是真动态视频时，CSS 运镜只做极轻二次增强，避免双重晕动
  const videoMotionBoost = asVideo
    ? {
        transform: `scale(${1 + 0.02 * Math.sin(frame * 0.02)})`,
        filter: `saturate(${grade.saturate}) contrast(${grade.contrast})`,
      }
    : {
        transform: motion.transform,
        filter: [motion.filter, `saturate(${grade.saturate}) contrast(${grade.contrast})`]
          .filter(Boolean)
          .join(" "),
      };

  const layout = (shot.overlay?.layout || "none").toLowerCase();
  const title = shot.overlay?.title;
  const subtitle = shot.overlay?.subtitle;
  const bullets = shot.overlay?.bullets || [];
  const voice = shot.audioUrl;
  const textStyle = resolveTextStyle(shot.overlay?.style, layout, height);
  const entrance = textEntrance(frame, fps, shot.overlay?.textAnim);
  const showText =
    layout !== "none" &&
    ((title && title.trim()) ||
      (subtitle && subtitle.trim()) ||
      bullets.length > 0);

  // dip_white 用白闪层
  const dipWhite =
    (shot.transition?.type || "").toLowerCase() === "dip_white"
      ? interpolate(
          frame,
          [0, 4, 10, dur - 10, dur - 4, dur],
          [1, 0, 0, 0, 0, 1],
          { extrapolateLeft: "clamp", extrapolateRight: "clamp" }
        )
      : 0;

  return (
    <AbsoluteFill style={{ opacity, clipPath: clip, fontFamily: FONT }}>
      {src ? (
        <AbsoluteFill
          style={{
            ...videoMotionBoost,
            overflow: "hidden",
          }}
        >
          {asVideo ? (
            <OffthreadVideo
              src={src}
              muted
              volume={0}
              style={{ width: "100%", height: "100%", objectFit: "cover" }}
              // 镜头比片段长时：播完后定格末帧（Remotion 默认行为接近 hold）
              acceptableTimeShiftInSeconds={0.4}
            />
          ) : (
            <Img
              src={src}
              style={{ width: "100%", height: "100%", objectFit: "cover" }}
            />
          )}
        </AbsoluteFill>
      ) : (
        <AbsoluteFill
          style={{
            background: gradientCss(index + 1),
            transform: motion.transform,
          }}
        />
      )}

      {/* 电影调色 + 暗角 */}
      <AbsoluteFill style={{ background: grade.overlay, pointerEvents: "none" }} />
      <AbsoluteFill style={{ background: grade.vignette, pointerEvents: "none" }} />

      {/* 氛围尘埃 / 光晕 */}
      <AtmosphereLayer frame={frame} index={index} />

      {/* 轻微胶片颗粒感（CSS 噪声近似） */}
      <AbsoluteFill
        style={{
          opacity: 0.055,
          backgroundImage:
            "repeating-radial-gradient(circle at 20% 30%, #fff 0 0.5px, transparent 0.6px 3px)",
          backgroundSize: "120px 120px",
          mixBlendMode: "overlay",
          pointerEvents: "none",
        }}
      />

      {showText && layout === "lower-third" && (
        <AbsoluteFill style={{ justifyContent: "flex-end", padding: 64 }}>
          <div style={{ ...entrance, marginBottom: 72, width: "100%" }}>
            <div
              style={{
                background: "rgba(0,0,0,0.5)",
                borderLeft: "5px solid #38bdf8",
                padding: "18px 26px",
                borderRadius: 10,
                backdropFilter: "blur(6px)",
              }}
            >
              {title ? (
                <div
                  style={{
                    color: textStyle.color,
                    fontSize: textStyle.fontSize * 0.85,
                    fontWeight: textStyle.fontWeight,
                    textShadow: textStyle.textShadow,
                  }}
                >
                  {title}
                </div>
              ) : null}
              {subtitle ? (
                <div
                  style={{
                    color: textStyle.subColor,
                    fontSize: textStyle.subSize,
                    marginTop: 8,
                  }}
                >
                  {subtitle}
                </div>
              ) : null}
            </div>
          </div>
        </AbsoluteFill>
      )}

      {showText && layout === "bullets-right" && (
        <AbsoluteFill style={{ padding: 64, justifyContent: "center" }}>
          <div
            style={{
              ...entrance,
              alignSelf: "center",
              marginLeft: "auto",
              maxWidth: "55%",
            }}
          >
            {title ? (
              <div
                style={{
                  color: "#7dd3fc",
                  fontSize: textStyle.subSize,
                  marginBottom: 14,
                  fontWeight: 600,
                }}
              >
                {title}
              </div>
            ) : null}
            {bullets.map((b, i) => (
              <div
                key={i}
                style={{
                  color: textStyle.color,
                  fontSize: textStyle.fontSize * 0.55,
                  marginBottom: 10,
                  textShadow: textStyle.textShadow,
                  opacity: interpolate(frame, [8 + i * 4, 16 + i * 4], [0, 1], {
                    extrapolateRight: "clamp",
                  }),
                  transform: `translateX(${interpolate(
                    frame,
                    [8 + i * 4, 16 + i * 4],
                    [16, 0],
                    { extrapolateRight: "clamp" }
                  )}px)`,
                }}
              >
                • {b}
              </div>
            ))}
          </div>
        </AbsoluteFill>
      )}

      {showText && layout === "caption" && title && (
        <AbsoluteFill style={{ justifyContent: "flex-end", alignItems: "center" }}>
          <div
            style={{
              ...entrance,
              marginBottom: 110,
              color: textStyle.color,
              fontSize: textStyle.fontSize * 0.7,
              fontWeight: 600,
              textShadow: textStyle.textShadow,
              textAlign: "center",
              maxWidth: "85%",
            }}
          >
            {title}
          </div>
        </AbsoluteFill>
      )}

      {showText && layout === "corner" && (
        <div style={positionStyle("lower-left")}>
          <div style={entrance}>
            {title ? (
              <div
                style={{
                  color: textStyle.color,
                  fontSize: textStyle.fontSize * 0.55,
                  fontWeight: 600,
                  textShadow: textStyle.textShadow,
                  borderBottom: "2px solid rgba(56,189,248,0.8)",
                  paddingBottom: 6,
                }}
              >
                {title}
              </div>
            ) : null}
            {subtitle ? (
              <div
                style={{
                  color: textStyle.subColor,
                  fontSize: textStyle.subSize * 0.9,
                  marginTop: 8,
                }}
              >
                {subtitle}
              </div>
            ) : null}
          </div>
        </div>
      )}

      {showText &&
        (layout === "hook-center" ||
          layout === "free" ||
          layout === "big-word" ||
          layout === "") && (
          <div
            style={positionStyle(
              layout === "hook-center"
                ? "center"
                : layout === "big-word"
                  ? "center"
                  : shot.overlay?.position
            )}
          >
            <div style={entrance}>
              {title ? (
                <div
                  style={{
                    color: textStyle.color,
                    fontSize: textStyle.fontSize,
                    fontWeight: textStyle.fontWeight,
                    lineHeight: 1.15,
                    letterSpacing: textStyle.letterSpacing,
                    textShadow: textStyle.textShadow,
                    maxWidth: "100%",
                  }}
                >
                  {title}
                </div>
              ) : null}
              {subtitle ? (
                <div
                  style={{
                    color: textStyle.subColor,
                    fontSize: textStyle.subSize,
                    marginTop: 16,
                    textShadow: "0 2px 12px rgba(0,0,0,0.5)",
                  }}
                >
                  {subtitle}
                </div>
              ) : null}
              {bullets.length > 0 ? (
                <div style={{ marginTop: 18 }}>
                  {bullets.map((b, i) => (
                    <div
                      key={i}
                      style={{
                        color: textStyle.subColor,
                        fontSize: textStyle.subSize,
                        marginBottom: 6,
                      }}
                    >
                      {b}
                    </div>
                  ))}
                </div>
              ) : null}
            </div>
          </div>
        )}

      {dipWhite > 0.01 ? (
        <AbsoluteFill
          style={{
            backgroundColor: "#fff",
            opacity: dipWhite,
            pointerEvents: "none",
          }}
        />
      ) : null}

      {voice ? <Audio src={voice} volume={1} /> : null}
    </AbsoluteFill>
  );
};

export const VisualTimeline: React.FC<ShotlistProps> = (props) => {
  const data = props?.shots?.length ? props : defaultShotlist;
  const shots = data.shots || [];
  const bgm = data.audio?.bgmUrl;
  const stylePreset = data.meta?.stylePreset;

  let cursor = 0;
  const sequences = shots.map((shot, index) => {
    const start = shot.startFrame != null ? shot.startFrame : cursor;
    const dur = Math.max(1, shot.durationInFrames || 90);
    cursor = start + dur;
    return (
      <Sequence key={shot.id || index} from={start} durationInFrames={dur} name={shot.id}>
        <ShotLayer shot={shot} index={index} stylePreset={stylePreset} />
      </Sequence>
    );
  });

  return (
    <AbsoluteFill style={{ backgroundColor: "#050508" }}>
      {sequences}
      {bgm ? <Audio src={bgm} volume={0.26} /> : null}
    </AbsoluteFill>
  );
};

export { defaultShotlist };
