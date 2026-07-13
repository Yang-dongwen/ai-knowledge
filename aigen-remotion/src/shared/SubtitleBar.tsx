import React from "react";
import { AbsoluteFill, useCurrentFrame, useVideoConfig } from "remotion";
import type { Subtitle } from "../types";

export const SubtitleBar: React.FC<{ subtitles: Subtitle[] }> = ({ subtitles }) => {
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
          background: "rgba(15,23,42,0.78)",
          color: "#fff",
          fontSize: 30,
          lineHeight: 1.4,
          padding: "14px 28px",
          borderRadius: 16,
          textAlign: "center",
          fontWeight: 500,
          boxShadow: "0 8px 28px rgba(0,0,0,0.25)",
        }}
      >
        {current.text}
      </div>
    </AbsoluteFill>
  );
};
