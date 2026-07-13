export type SceneType =
  | "title"
  | "bullets"
  | "outro"
  | "hook"
  | "compare"
  | "insight"
  | "metric"
  | string;

export interface SceneProps {
  title?: string;
  subtitle?: string;
  heading?: string;
  items?: string[];
  cta?: string;
  /** 眉题 / 小标签 */
  eyebrow?: string;
  /** 左右对比 */
  leftLabel?: string;
  rightLabel?: string;
  leftItems?: string[];
  rightItems?: string[];
  /** 大数字 */
  value?: string;
  unit?: string;
  label?: string;
  hint?: string;
}

export interface Scene {
  id: string;
  type: SceneType;
  startFrame: number;
  durationInFrames: number;
  narration?: string;
  props?: SceneProps;
}

export interface Subtitle {
  startMs: number;
  endMs: number;
  text: string;
}

export interface AudioTrack {
  sceneId: string;
  src?: string;
  /** 渲染时由 Java 注入的绝对路径 */
  absSrc?: string;
  durationMs?: number;
  mock?: boolean;
}

export interface Storyboard {
  version?: string;
  /** 任务工作目录（渲染时注入，用于解析相对音频路径） */
  workDir?: string;
  meta: {
    title?: string;
    language?: string;
    templateId?: string;
    fps: number;
    width: number;
    height: number;
    durationInFrames: number;
  };
  style?: {
    theme?: string;
    primaryColor?: string;
  };
  scenes: Scene[];
  audio?: {
    voiceId?: string;
    tracks?: AudioTrack[];
  };
  subtitles?: Subtitle[];
}
