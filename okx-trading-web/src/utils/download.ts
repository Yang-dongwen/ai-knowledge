/**
 * 浏览器原生下载：立刻弹出系统下载栏，不把文件读进 JS 堆。
 * 同源用 download 属性；跨域预签名靠响应头 Content-Disposition。
 */
export function triggerNativeDownload(url: string, filename?: string): void {
  if (!url) {
    throw new Error('下载地址为空')
  }
  const a = document.createElement('a')
  a.href = url
  a.rel = 'noopener'
  if (filename) {
    a.download = filename
  }
  if (!isSameOrigin(url)) {
    a.target = '_blank'
  }
  document.body.appendChild(a)
  a.click()
  a.remove()
}

function isSameOrigin(url: string): boolean {
  if (url.startsWith('/') && !url.startsWith('//')) return true
  try {
    return new URL(url, window.location.origin).origin === window.location.origin
  } catch {
    return true
  }
}

export function safeDownloadName(raw: string | undefined | null, fallback: string, ext?: string): string {
  let name = (raw || fallback)
    .replace(/[\\/:*?"<>|]+/g, '_')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 80)
  if (!name) name = fallback
  if (ext) {
    const lower = name.toLowerCase()
    const e = ext.startsWith('.') ? ext.toLowerCase() : `.${ext.toLowerCase()}`
    if (!lower.endsWith(e)) name += e
  }
  return name
}
