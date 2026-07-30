/**
 * 目录树虚拟滚动 + 拖拽幽灵图辅助。
 */

export const TREE_ROW_HEIGHT = 32
export const TREE_OVERSCAN = 8

export interface VirtualRange {
  start: number
  end: number
  offsetY: number
  totalHeight: number
}

/** 根据滚动位置计算可见区间（含 overscan） */
export function calcVirtualRange(
  scrollTop: number,
  viewportHeight: number,
  itemCount: number,
  rowHeight = TREE_ROW_HEIGHT,
  overscan = TREE_OVERSCAN
): VirtualRange {
  const totalHeight = Math.max(0, itemCount * rowHeight)
  if (itemCount <= 0 || viewportHeight <= 0) {
    return { start: 0, end: 0, offsetY: 0, totalHeight }
  }
  const visible = Math.ceil(viewportHeight / rowHeight) + 1
  let start = Math.floor(scrollTop / rowHeight) - overscan
  if (start < 0) start = 0
  let end = start + visible + overscan * 2
  if (end > itemCount) end = itemCount
  // 保证 start 不越过 end
  if (start > end) start = Math.max(0, end - visible)
  return {
    start,
    end,
    offsetY: start * rowHeight,
    totalHeight
  }
}

/** 把某一行滚到可视区内 */
export function scrollIndexIntoView(
  el: HTMLElement | null | undefined,
  index: number,
  rowHeight = TREE_ROW_HEIGHT
) {
  if (!el || index < 0) return
  const top = index * rowHeight
  const bottom = top + rowHeight
  const viewTop = el.scrollTop
  const viewBottom = viewTop + el.clientHeight
  if (top < viewTop) {
    el.scrollTop = top
  } else if (bottom > viewBottom) {
    el.scrollTop = bottom - el.clientHeight
  }
}

export type DragGhostKind = 'folder' | 'note'

let ghostEl: HTMLDivElement | null = null

/**
 * 创建拖拽幽灵节点，并 setDragImage。
 * 返回 cleanup（dragend 时调用）。
 */
export function applyDragGhost(
  e: DragEvent,
  opts: { name: string; kind: DragGhostKind; pinned?: boolean }
): () => void {
  cleanupDragGhost()
  const dt = e.dataTransfer
  if (!dt) return () => undefined

  const el = document.createElement('div')
  el.className = 'kb-drag-ghost'
  el.setAttribute('aria-hidden', 'true')

  const icon = document.createElement('span')
  icon.className = 'kb-drag-ghost-icon'
  icon.textContent = opts.kind === 'folder' ? '📁' : opts.pinned ? '📌' : '📄'

  const label = document.createElement('span')
  label.className = 'kb-drag-ghost-label'
  const name = (opts.name || '未命名').trim() || '未命名'
  label.textContent = name.length > 28 ? name.slice(0, 28) + '…' : name

  const badge = document.createElement('span')
  badge.className = 'kb-drag-ghost-badge'
  badge.textContent = opts.kind === 'folder' ? '文件夹' : '文档'

  el.appendChild(icon)
  el.appendChild(label)
  el.appendChild(badge)

  // 离屏但可见（部分浏览器要求）
  Object.assign(el.style, {
    position: 'fixed',
    top: '-1000px',
    left: '-1000px',
    zIndex: '99999',
    pointerEvents: 'none',
    display: 'inline-flex',
    alignItems: 'center',
    gap: '8px',
    maxWidth: '280px',
    padding: '8px 12px',
    borderRadius: '10px',
    background: 'var(--surface-1, #fff)',
    color: 'var(--text-primary, #1c1917)',
    border: '1px solid var(--border-color, #e7e5e4)',
    boxShadow: '0 8px 24px rgba(0,0,0,0.18)',
    fontSize: '13px',
    fontFamily: 'system-ui, -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
    fontWeight: '600',
    lineHeight: '1.2',
    whiteSpace: 'nowrap'
  } as CSSStyleDeclaration)

  icon.style.fontSize = '16px'
  icon.style.flexShrink = '0'
  label.style.overflow = 'hidden'
  label.style.textOverflow = 'ellipsis'
  label.style.flex = '1'
  label.style.minWidth = '0'
  badge.style.fontSize = '11px'
  badge.style.fontWeight = '500'
  badge.style.opacity = '0.65'
  badge.style.flexShrink = '0'
  badge.style.padding = '2px 6px'
  badge.style.borderRadius = '999px'
  badge.style.background =
    opts.kind === 'folder' ? 'rgba(217,119,6,0.12)' : 'rgba(15,118,110,0.12)'
  badge.style.color = opts.kind === 'folder' ? '#d97706' : '#0f766e'

  document.body.appendChild(el)
  ghostEl = el

  // 偏移：光标略偏左上，像抓起卡片
  const offsetX = 16
  const offsetY = 14
  try {
    dt.setDragImage(el, offsetX, offsetY)
    dt.effectAllowed = 'move'
  } catch {
    /* 部分环境不支持 setDragImage */
  }

  return cleanupDragGhost
}

export function cleanupDragGhost() {
  if (ghostEl && ghostEl.parentNode) {
    ghostEl.parentNode.removeChild(ghostEl)
  }
  ghostEl = null
}
