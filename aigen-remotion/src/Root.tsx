import React from "react";
import { Composition, getInputProps } from "remotion";
import { KnowledgeCards, defaultStoryboard } from "./templates/KnowledgeCards";
import type { Storyboard } from "./types";

const calcMeta = ({ props }: { props: Storyboard }) => {
  const meta = props?.meta ?? defaultStoryboard.meta;
  return {
    durationInFrames: Math.max(30, meta.durationInFrames || 300),
    fps: meta.fps || 30,
    width: meta.width || 1080,
    height: meta.height || 1920,
  };
};

export const RemotionRoot: React.FC = () => {
  // Studio 预览时可用默认 props；HTTP 渲染通过 inputProps 注入
  const previewProps = (getInputProps() as Storyboard) || defaultStoryboard;

  return (
    <>
      <Composition
        id="KnowledgeCards"
        component={KnowledgeCards as unknown as React.FC}
        durationInFrames={previewProps.meta?.durationInFrames || 300}
        fps={previewProps.meta?.fps || 30}
        width={previewProps.meta?.width || 1080}
        height={previewProps.meta?.height || 1920}
        defaultProps={defaultStoryboard}
        calculateMetadata={async ({ props }) => calcMeta({ props: props as Storyboard })}
      />
    </>
  );
};
