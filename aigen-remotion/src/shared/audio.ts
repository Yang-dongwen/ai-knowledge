import type { AudioTrack } from "../types";

/**
 * Remotion Audio 只能拉取 http(s)（不能 file:// 或盘符路径）。
 * 本地路径兜底：…/aigen/{taskId}/assets/audio/xx → http://127.0.0.1:3100/media/{taskId}/assets/audio/xx
 */
export function resolvePlayableAudio(track: AudioTrack | undefined): string | null {
  if (!track || track.mock) return null;
  let src = (track.src || track.absSrc || "").trim();
  if (!src) return null;
  const lower = src.toLowerCase();
  if (lower.endsWith(".txt") || lower.includes(".mock")) return null;
  if (lower.startsWith("http://") || lower.startsWith("https://")) {
    return src;
  }
  src = src.replace(/^file:\/\//i, "").replace(/^\/([A-Za-z]:)/, "$1");
  const norm = src.replace(/\\/g, "/");
  const m = norm.match(/\/aigen\/([^/]+)\/(assets\/audio\/[^?]+)$/i);
  if (m) {
    return `http://127.0.0.1:3100/media/${m[1]}/${m[2]}`;
  }
  return null;
}
