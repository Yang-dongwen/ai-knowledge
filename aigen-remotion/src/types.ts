export type SceneType = "title" | "bullets" | "outro" | string;

export interface SceneProps {
  title?: string;
  subtitle?: string;
  heading?: string;
  items?: string[];
  cta?: string;
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
