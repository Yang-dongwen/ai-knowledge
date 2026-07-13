import React from "react";
import {
  AbsoluteFill,
  Audio,
  Sequence,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import { resolvePlayableAudio } from "../shared/audio";
import { SubtitleBar } from "../shared/SubtitleBar";
import type { Scene, Storyboard } from "../types";

const FONT =
  'system-ui, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif';

/** 对比模板默认分镜（Studio 预览 / golden） */
export const defaultInsightStoryboard: Storyboard = {
  version: "1.0",
  meta: {
    title: "先场景，后模型",
    language: "zh",
    templateId: "insight-compare",
    fps: 30,
    width: 1080,
    height: 1920,
    durationInFrames: 720,
  },
  style: { theme: "compare-duo", primaryColor: "#0ea5e9" },
  scenes: [
    {
      id: "s1",
      type: "hook",
      startFrame: 0,
      durationInFrames: 150,
      narration: "很多人学 AI，一上来就追最新模型，结果三个月还是不会落地。",
      props: {
        eyebrow: "常见误区",
        title: "先追模型，还是先追场景？",
        subtitle: "差的是路径，不是智商",
      },
    },
    {
      id: "s2",
      type: "compare",
      startFrame: 150,
      durationInFrames: 210,
      narration: "左边是堆概念，右边是拿一个真实任务跑通最小闭环。",
      props: {
        heading: "两种学习路径",
        leftLabel: "无效路径",
        rightLabel: "有效路径",
        leftItems: ["只收藏教程", "频繁换模型", "没有验收标准"],
        rightItems: ["锁定一个场景", "最小可运行版本", "用结果迭代"],
      },
    },
    {
      id: "s3",
      type: "metric",
      startFrame: 360,
      durationInFrames: 150,
      narration: "把范围收窄以后，两周就能做出第一个能演示的版本。",
      props: {
        value: "14",
        unit: "天",
        label: "从零到可演示",
        hint: "范围足够小的时候",
      },
    },
    {
      id: "s4",
      type: "insight",
      startFrame: 510,
      durationInFrames: 120,
      narration: "记住三件事：场景优先、闭环优先、结果优先。",
      props: {
        heading: "落地三原则",
        items: ["先选场景", "最小闭环", "用结果迭代"],
      },
    },
    {
      id: "s5",
      type: "outro",
      startFrame: 630,
      durationInFrames: 90,
      narration: "先选场景，再选工具。关注我，下期拆一条可复制清单。",
      props: { title: "先场景，后模型", cta: "关注 · 下期清单" },
    },
  ],
  subtitles: [],
  audio: { tracks: [] },
};

const LEFT = "#94a3b8";
const RIGHT = "#22c55e";
const BG = "#0b1220";
const CARD = "rgba(255,255,255,0.06)";
const BORDER = "rgba(255,255,255,0.1)";

function useEnter(delay = 0) {
  const frame = useCurrentFrame();
  const opacity = interpolate(frame, [delay, delay + 14], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const y = interpolate(frame, [delay, delay + 16], [28, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  return { opacity, y, frame };
}

const Shell: React.FC<{
  children: React.ReactNode;
  primary: string;
  accentTop?: boolean;
}> = ({ children, primary, accentTop }) => (
  <AbsoluteFill
    style={{
      background: `radial-gradient(120% 80% at 50% 0%, ${primary}22 0%, transparent 55%),
        linear-gradient(165deg, #0b1220 0%, #111827 48%, #0f172a 100%)`,
      fontFamily: FONT,
      color: "#f8fafc",
      padding: "96px 64px 160px",
      display: "flex",
      flexDirection: "column",
    }}
  >
    {accentTop ? (
      <div
        style={{
          position: "absolute",
          top: 0,
          left: 0,
          right: 0,
          height: 6,
          background: `linear-gradient(90deg, ${LEFT}, ${primary}, ${RIGHT})`,
        }}
      />
    ) : null}
    {children}
  </AbsoluteFill>
);

const HookScene: React.FC<{ scene: Scene; primary: string }> = ({
  scene,
  primary,
}) => {
  const eyebrow = useEnter(0);
  const title = useEnter(8);
  const sub = useEnter(18);
  const p = scene.props || {};
  return (
    <Shell primary={primary} accentTop>
      <div style={{ flex: 1, display: "flex", flexDirection: "column", justifyContent: "center" }}>
        {p.eyebrow ? (
          <div
            style={{
              opacity: eyebrow.opacity,
              transform: `translateY(${eyebrow.y}px)`,
              alignSelf: "flex-start",
              padding: "10px 20px",
              borderRadius: 999,
              background: `${primary}33`,
              border: `1px solid ${primary}66`,
              color: "#e0f2fe",
              fontSize: 26,
              fontWeight: 700,
              letterSpacing: 1,
              marginBottom: 36,
            }}
          >
            {p.eyebrow}
          </div>
        ) : null}
        <div
          style={{
            opacity: title.opacity,
            transform: `translateY(${title.y}px)`,
            fontSize: 64,
            fontWeight: 800,
            lineHeight: 1.25,
            letterSpacing: -0.5,
            textShadow: "0 12px 40px rgba(0,0,0,0.35)",
          }}
        >
          {p.title || "关键问题"}
        </div>
        {p.subtitle ? (
          <div
            style={{
              opacity: sub.opacity,
              transform: `translateY(${sub.y}px)`,
              marginTop: 28,
              fontSize: 34,
              color: "#94a3b8",
              fontWeight: 500,
              lineHeight: 1.45,
              maxWidth: "95%",
            }}
          >
            {p.subtitle}
          </div>
        ) : null}
      </div>
    </Shell>
  );
};

const CompareScene: React.FC<{ scene: Scene; primary: string }> = ({
  scene,
  primary,
}) => {
  const frame = useCurrentFrame();
  const p = scene.props || {};
  const leftItems = p.leftItems || [];
  const rightItems = p.rightItems || [];
  const head = useEnter(0);
  const leftIn = interpolate(frame, [10, 26], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const leftX = interpolate(frame, [10, 26], [-40, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const rightIn = interpolate(frame, [16, 32], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const rightX = interpolate(frame, [16, 32], [40, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });

  const Col: React.FC<{
    label: string;
    items: string[];
    color: string;
    opacity: number;
    x: number;
    side: "left" | "right";
  }> = ({ label, items, color, opacity, x, side }) => (
    <div
      style={{
        flex: 1,
        opacity,
        transform: `translateX(${x}px)`,
        background: CARD,
        border: `1px solid ${BORDER}`,
        borderTop: `4px solid ${color}`,
        borderRadius: 24,
        padding: "28px 22px",
        minHeight: 420,
      }}
    >
      <div
        style={{
          fontSize: 26,
          fontWeight: 800,
          color,
          marginBottom: 22,
          letterSpacing: 0.5,
        }}
      >
        {label}
      </div>
      {items.map((item, i) => {
        const start = 28 + i * 8;
        const op = interpolate(frame, [start, start + 10], [0, 1], {
          extrapolateLeft: "clamp",
          extrapolateRight: "clamp",
        });
        return (
          <div
            key={`${side}-${i}`}
            style={{
              opacity: op,
              display: "flex",
              gap: 12,
              alignItems: "flex-start",
              marginBottom: 18,
            }}
          >
            <div
              style={{
                width: 10,
                height: 10,
                borderRadius: 999,
                background: color,
                marginTop: 12,
                flexShrink: 0,
              }}
            />
            <div style={{ fontSize: 30, fontWeight: 600, lineHeight: 1.35, color: "#f1f5f9" }}>
              {item}
            </div>
          </div>
        );
      })}
    </div>
  );

  return (
    <Shell primary={primary}>
      <div
        style={{
          opacity: head.opacity,
          transform: `translateY(${head.y}px)`,
          fontSize: 36,
          fontWeight: 700,
          color: "#cbd5e1",
          marginBottom: 32,
        }}
      >
        {p.heading || "对比"}
      </div>
      <div style={{ display: "flex", gap: 20, flex: 1, alignItems: "stretch" }}>
        <Col
          label={p.leftLabel || "A"}
          items={leftItems}
          color={LEFT}
          opacity={leftIn}
          x={leftX}
          side="left"
        />
        <Col
          label={p.rightLabel || "B"}
          items={rightItems}
          color={RIGHT}
          opacity={rightIn}
          x={rightX}
          side="right"
        />
      </div>
    </Shell>
  );
};

const MetricScene: React.FC<{ scene: Scene; primary: string }> = ({
  scene,
  primary,
}) => {
  const frame = useCurrentFrame();
  const p = scene.props || {};
  const scale = interpolate(frame, [0, 18], [0.88, 1], {
    extrapolateRight: "clamp",
  });
  const opacity = interpolate(frame, [0, 12], [0, 1], {
    extrapolateRight: "clamp",
  });
  const hintOp = interpolate(frame, [18, 30], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  return (
    <Shell primary={primary}>
      <div
        style={{
          flex: 1,
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          alignItems: "center",
          textAlign: "center",
          opacity,
          transform: `scale(${scale})`,
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "baseline",
            gap: 12,
            color: primary,
          }}
        >
          <span style={{ fontSize: 140, fontWeight: 900, lineHeight: 1, letterSpacing: -2 }}>
            {p.value || "—"}
          </span>
          {p.unit ? (
            <span style={{ fontSize: 48, fontWeight: 700, color: "#e2e8f0" }}>{p.unit}</span>
          ) : null}
        </div>
        <div
          style={{
            marginTop: 28,
            fontSize: 40,
            fontWeight: 700,
            color: "#f8fafc",
          }}
        >
          {p.label || p.title || "关键指标"}
        </div>
        {p.hint ? (
          <div
            style={{
              opacity: hintOp,
              marginTop: 20,
              fontSize: 28,
              color: "#94a3b8",
              fontWeight: 500,
            }}
          >
            {p.hint}
          </div>
        ) : null}
      </div>
    </Shell>
  );
};

const InsightScene: React.FC<{ scene: Scene; primary: string }> = ({
  scene,
  primary,
}) => {
  const frame = useCurrentFrame();
  const p = scene.props || {};
  const items = (p.items || []).slice(0, 3);
  const head = useEnter(0);
  return (
    <Shell primary={primary}>
      <div
        style={{
          opacity: head.opacity,
          transform: `translateY(${head.y}px)`,
          fontSize: 38,
          fontWeight: 700,
          color: "#cbd5e1",
          marginBottom: 40,
        }}
      >
        {p.heading || "核心洞察"}
      </div>
      {items.map((item, i) => {
        const start = 10 + i * 12;
        const op = interpolate(frame, [start, start + 12], [0, 1], {
          extrapolateLeft: "clamp",
          extrapolateRight: "clamp",
        });
        const x = interpolate(frame, [start, start + 12], [24, 0], {
          extrapolateLeft: "clamp",
          extrapolateRight: "clamp",
        });
        return (
          <div
            key={i}
            style={{
              opacity: op,
              transform: `translateX(${x}px)`,
              display: "flex",
              alignItems: "center",
              gap: 20,
              marginBottom: 24,
              padding: "22px 26px",
              borderRadius: 20,
              background: CARD,
              border: `1px solid ${BORDER}`,
            }}
          >
            <div
              style={{
                width: 44,
                height: 44,
                borderRadius: 12,
                background: `${primary}33`,
                color: primary,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                fontWeight: 800,
                fontSize: 22,
                flexShrink: 0,
              }}
            >
              {i + 1}
            </div>
            <div style={{ fontSize: 34, fontWeight: 600, lineHeight: 1.35 }}>{item}</div>
          </div>
        );
      })}
    </Shell>
  );
};

const OutroScene: React.FC<{ scene: Scene; primary: string }> = ({
  scene,
  primary,
}) => {
  const frame = useCurrentFrame();
  const p = scene.props || {};
  const scale = interpolate(frame, [0, 18], [0.92, 1], {
    extrapolateRight: "clamp",
  });
  const opacity = interpolate(frame, [0, 12], [0, 1], {
    extrapolateRight: "clamp",
  });
  const ctaOp = interpolate(frame, [16, 28], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  return (
    <Shell primary={primary} accentTop>
      <div
        style={{
          flex: 1,
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          alignItems: "center",
          textAlign: "center",
          opacity,
          transform: `scale(${scale})`,
        }}
      >
        <div style={{ fontSize: 56, fontWeight: 800, lineHeight: 1.3, maxWidth: "92%" }}>
          {p.title || "感谢观看"}
        </div>
        {p.cta ? (
          <div
            style={{
              opacity: ctaOp,
              marginTop: 40,
              padding: "18px 40px",
              borderRadius: 999,
              background: `linear-gradient(90deg, ${primary}, ${RIGHT})`,
              color: "#fff",
              fontSize: 32,
              fontWeight: 800,
              boxShadow: `0 12px 40px ${primary}55`,
            }}
          >
            {p.cta}
          </div>
        ) : null}
      </div>
    </Shell>
  );
};

function renderScene(scene: Scene, primary: string) {
  switch (scene.type) {
    case "hook":
      return <HookScene scene={scene} primary={primary} />;
    case "compare":
      return <CompareScene scene={scene} primary={primary} />;
    case "metric":
      return <MetricScene scene={scene} primary={primary} />;
    case "insight":
      return <InsightScene scene={scene} primary={primary} />;
    case "outro":
      return <OutroScene scene={scene} primary={primary} />;
    // 兼容误规划：映射到近似布局
    case "title":
      return (
        <HookScene
          scene={{
            ...scene,
            props: {
              eyebrow: scene.props?.eyebrow || "开场",
              title: scene.props?.title,
              subtitle: scene.props?.subtitle,
            },
          }}
          primary={primary}
        />
      );
    case "bullets":
      return (
        <InsightScene
          scene={{
            ...scene,
            props: {
              heading: scene.props?.heading || scene.props?.title,
              items: scene.props?.items,
            },
          }}
          primary={primary}
        />
      );
    default:
      return <InsightScene scene={scene} primary={primary} />;
  }
}

export const InsightCompare: React.FC<Storyboard> = (props) => {
  const data = props?.meta ? props : defaultInsightStoryboard;
  const primary = data.style?.primaryColor || "#0ea5e9";
  const scenes = data.scenes || [];
  const subtitles = data.subtitles || [];
  const tracks = data.audio?.tracks || [];
  useVideoConfig();

  return (
    <AbsoluteFill style={{ backgroundColor: BG, fontFamily: FONT }}>
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
            {renderScene(scene, primary)}
            {audioSrc ? <Audio src={audioSrc} volume={1} /> : null}
          </Sequence>
        );
      })}
      <SubtitleBar subtitles={subtitles} />
    </AbsoluteFill>
  );
};
