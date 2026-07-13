import React from "react";
import { Composition, getInputProps } from "remotion";
import { KnowledgeCards, defaultStoryboard } from "./templates/KnowledgeCards";
import {
  InsightCompare,
  defaultInsightStoryboard,
} from "./templates/InsightCompare";
import type { Storyboard } from "./types";

const calcMeta = ({ props }: { props: Storyboard }) => {
  const meta = props?.meta;
  return {
    durationInFrames: Math.max(30, meta?.durationInFrames || 300),
    fps: meta?.fps || 30,
    width: meta?.width || 1080,
    height: meta?.height || 1920,
  };
};

function asStoryboard(props: unknown): Storyboard {
  return (props as Storyboard) || defaultStoryboard;
}

export const RemotionRoot: React.FC = () => {
  const previewProps = asStoryboard(getInputProps());

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
        calculateMetadata={async ({ props }) =>
          calcMeta({ props: asStoryboard(props) })
        }
      />
      <Composition
        id="InsightCompare"
        component={InsightCompare as unknown as React.FC}
        durationInFrames={defaultInsightStoryboard.meta.durationInFrames}
        fps={defaultInsightStoryboard.meta.fps}
        width={defaultInsightStoryboard.meta.width}
        height={defaultInsightStoryboard.meta.height}
        defaultProps={defaultInsightStoryboard}
        calculateMetadata={async ({ props }) =>
          calcMeta({ props: asStoryboard(props) })
        }
      />
    </>
  );
};
