import React from "react";
import { Composition, getInputProps } from "remotion";
import { KnowledgeCards, defaultStoryboard } from "./templates/KnowledgeCards";
import {
  InsightCompare,
  defaultInsightStoryboard,
} from "./templates/InsightCompare";
import {
  VisualTimeline,
  defaultShotlist,
  type ShotlistProps,
} from "./templates/VisualTimeline";
import type { Storyboard } from "./types";

const calcMetaStoryboard = ({ props }: { props: Storyboard }) => {
  const meta = props?.meta;
  return {
    durationInFrames: Math.max(30, meta?.durationInFrames || 300),
    fps: meta?.fps || 30,
    width: meta?.width || 1080,
    height: meta?.height || 1920,
  };
};

const calcMetaShotlist = ({ props }: { props: ShotlistProps }) => {
  const meta = props?.meta;
  let duration = meta?.durationInFrames || 0;
  if (!duration && props?.shots?.length) {
    duration = props.shots.reduce(
      (sum, s) => sum + Math.max(1, s.durationInFrames || 90),
      0
    );
  }
  return {
    durationInFrames: Math.max(30, duration || 300),
    fps: meta?.fps || 30,
    width: meta?.width || 1080,
    height: meta?.height || 1920,
  };
};

function asStoryboard(props: unknown): Storyboard {
  return (props as Storyboard) || defaultStoryboard;
}

function asShotlist(props: unknown): ShotlistProps {
  const p = props as ShotlistProps;
  if (p?.shots?.length) {
    return p;
  }
  return defaultShotlist;
}

export const RemotionRoot: React.FC = () => {
  const previewProps = asStoryboard(getInputProps());
  const previewShotlist = asShotlist(getInputProps());

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
          calcMetaStoryboard({ props: asStoryboard(props) })
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
          calcMetaStoryboard({ props: asStoryboard(props) })
        }
      />
      <Composition
        id="VisualTimeline"
        component={VisualTimeline as unknown as React.FC}
        durationInFrames={previewShotlist.meta?.durationInFrames || 300}
        fps={previewShotlist.meta?.fps || 30}
        width={previewShotlist.meta?.width || 1080}
        height={previewShotlist.meta?.height || 1920}
        defaultProps={defaultShotlist}
        calculateMetadata={async ({ props }) =>
          calcMetaShotlist({ props: asShotlist(props) })
        }
      />
    </>
  );
};
