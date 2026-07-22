/**
 * 将业务样式中的硬编码色值替换为 CSS 变量。
 * 仅处理 .scss/.css 全文，以及 .vue 的 <style> 块（不改 script）。
 * 运行：node scripts/theme-detokenize.mjs
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const srcRoot = path.resolve(__dirname, '../src')

/** 语义优先；同一 hex 只映射一次 */
const COLOR_MAP = [
  // 语义状态
  ['#ecfdf5', 'var(--success-bg)'],
  ['#a7f3d0', 'var(--success-border)'],
  ['#047857', 'var(--success-text)'],
  ['#065f46', 'var(--success-text)'],
  ['#10b981', 'var(--success-strong)'],
  ['#22c55e', 'var(--success-strong)'],
  ['#16a34a', 'var(--success-color)'],
  ['#15803d', 'var(--success-text)'],
  ['#059669', 'var(--success-color)'],
  ['#0f766e', 'var(--success-text)'],
  ['#ccfbf1', 'var(--success-border)'],
  ['#f0fdfa', 'var(--success-bg)'],
  ['#dcfce7', 'var(--success-bg)'],
  ['#86efac', 'var(--success-border)'],
  ['#f0fdf4', 'var(--success-bg)'],

  ['#fffbeb', 'var(--warning-bg)'],
  ['#fef3c7', 'var(--warning-bg)'],
  ['#fde68a', 'var(--warning-border)'],
  ['#fcd34d', 'var(--warning-border)'],
  ['#fbbf24', 'var(--warning-strong)'],
  ['#f59e0b', 'var(--warning-strong)'],
  ['#d97706', 'var(--warning-color)'],
  ['#b45309', 'var(--warning-text)'],
  ['#92400e', 'var(--warning-text-deep)'],
  ['#a16207', 'var(--warning-text-mid)'],

  ['#fef2f2', 'var(--danger-bg)'],
  ['#fee2e2', 'var(--danger-bg)'],
  ['#fecaca', 'var(--danger-border)'],
  ['#fecdd3', 'var(--danger-soft-border)'],
  ['#fda4af', 'var(--danger-soft-border-hover)'],
  ['#fca5a5', 'var(--danger-border)'],
  ['#e11d48', 'var(--danger-strong)'],
  ['#be123c', 'var(--danger-hover)'],
  ['#9f1239', 'var(--danger-hover)'],
  ['#b91c1c', 'var(--danger-text)'],
  ['#ef4444', 'var(--danger-strong)'],

  ['#eff6ff', 'var(--info-bg)'],
  ['#ebf5ff', 'var(--info-bg)'],
  ['#bfdbfe', 'var(--info-border)'],
  ['#1d4ed8', 'var(--info-text)'],
  ['#2563eb', 'var(--info-color)'],
  ['#3b82f6', 'var(--info-strong)'],
  ['#1e3a5f', 'var(--info-text)'],

  ['#e0e7ff', 'var(--indigo-bg)'],
  ['#c7d2fe', 'var(--indigo-border)'],
  ['#4338ca', 'var(--indigo-text)'],
  ['#818cf8', 'var(--indigo-soft)'],
  ['#a5b4fc', 'var(--indigo-mid)'],

  // 舞台
  ['#0b1220', 'var(--stage-bg)'],
  ['#020617', 'var(--stage-bg)'],

  // 表面
  ['#f8f9fb', 'var(--page-bg)'],
  ['#f5f6f8', 'var(--page-bg)'],
  ['#eef0f4', 'var(--page-bg)'],
  ['#eef0f3', 'var(--page-bg)'],
  ['#eef2f7', 'var(--surface-muted)'],
  ['#f8fafc', 'var(--surface-hover)'],
  ['#f9fafb', 'var(--btn-default-hover)'],
  ['#fafafa', 'var(--surface-2)'],
  ['#f3f4f6', 'var(--surface-3)'],
  ['#f1f5f9', 'var(--surface-muted)'],
  ['#ffffff', 'var(--surface-1)'],
  ['#fff', 'var(--surface-1)'],

  // 边框
  ['#e5e7eb', 'var(--border-color)'],
  ['#e2e8f0', 'var(--border-soft)'],
  ['#d1d5db', 'var(--border-strong)'],
  ['#cbd5e1', 'var(--border-strong)'],
  ['#f0f0f0', 'var(--border-subtle)'],

  // 文字 / 品牌
  ['#0f172a', 'var(--text-primary)'],
  ['#111827', 'var(--primary-strong)'],
  ['#1f2937', 'var(--primary-color)'],
  ['#030712', 'var(--btn-primary-active)'],
  ['#374151', 'var(--soft-accent-text)'],
  ['#4b5563', 'var(--soft-accent-text)'],
  ['#475569', 'var(--text-secondary)'],
  ['#64748b', 'var(--text-secondary)'],
  ['#6b7280', 'var(--text-muted)'],
  ['#94a3b8', 'var(--text-muted)'],
  ['#9ca3af', 'var(--text-tertiary)'],
  ['#334155', 'var(--stage-border)']
]

const seen = new Set()
const uniqueMap = []
for (const [hex, token] of COLOR_MAP) {
  const k = hex.toLowerCase()
  if (seen.has(k)) continue
  seen.add(k)
  uniqueMap.push([k, token])
}
uniqueMap.sort((a, b) => b[0].length - a[0].length)

const RGBA_MAP = [
  ['rgba(255, 255, 255, 0.78)', 'var(--glass-bg)'],
  ['rgba(255,255,255,0.78)', 'var(--glass-bg)'],
  ['rgba(255, 255, 255, 0.88)', 'var(--glass-bg-strong)'],
  ['rgba(255,255,255,0.88)', 'var(--glass-bg-strong)'],
  ['rgba(255, 255, 255, 0.7)', 'var(--header-bg)'],
  ['rgba(255,255,255,0.7)', 'var(--header-bg)'],
  ['rgba(226, 232, 240, 0.72)', 'var(--header-border)'],
  ['rgba(15, 23, 42, 0.04)', 'var(--grid-line)'],
  ['rgba(15, 23, 42, 0.035)', 'var(--grid-line)'],
  ['rgba(15, 23, 42, 0.08)', 'var(--focus-ring)'],
  ['rgba(15, 23, 42, 0.05)', 'color-mix(in srgb, var(--text-primary) 5%, transparent)'],
  ['rgba(15, 23, 42, 0.06)', 'color-mix(in srgb, var(--text-primary) 6%, transparent)'],
  ['rgba(15, 23, 42, 0.12)', 'color-mix(in srgb, var(--text-primary) 12%, transparent)'],
  ['rgba(15, 23, 42, 0.03)', 'color-mix(in srgb, var(--text-primary) 3%, transparent)'],
  ['rgba(248, 250, 252, 0.95)', 'var(--nav-hover-bg)'],
  ['rgba(226, 232, 240, 0.9)', 'var(--nav-hover-border)'],
  ['rgba(34, 197, 94, 0.45)', 'color-mix(in srgb, var(--success-strong) 45%, transparent)']
]

const SKIP_DIRS = new Set(['node_modules', 'dist'])
const TARGET_EXT = new Set(['.vue', '.scss', '.css'])
const SKIP_FILES = new Set(['tokens.scss', 'variables.scss', 'App.vue'])

function walk(dir, out = []) {
  for (const name of fs.readdirSync(dir)) {
    if (SKIP_DIRS.has(name)) continue
    const p = path.join(dir, name)
    const st = fs.statSync(p)
    if (st.isDirectory()) walk(p, out)
    else if (TARGET_EXT.has(path.extname(name)) && !SKIP_FILES.has(name)) out.push(p)
  }
  return out
}

function replaceInCss(css) {
  let next = css
  let count = 0

  for (const [from, to] of RGBA_MAP) {
    const re = new RegExp(from.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi')
    const matches = next.match(re)
    if (matches) {
      count += matches.length
      next = next.replace(re, to)
    }
  }

  for (const [hex, token] of uniqueMap) {
    const re = new RegExp(hex.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi')
    const matches = next.match(re)
    if (matches) {
      count += matches.length
      next = next.replace(re, token)
    }
  }

  return { next, count }
}

function processVue(content) {
  let count = 0
  const next = content.replace(/<style\b[^>]*>([\s\S]*?)<\/style>/gi, (full, css) => {
    const r = replaceInCss(css)
    count += r.count
    return full.replace(css, r.next)
  })
  return { next, count }
}

function processFile(file, raw) {
  if (file.endsWith('.vue')) return processVue(raw)
  return replaceInCss(raw)
}

const files = walk(srcRoot)
let total = 0
const report = []

for (const file of files) {
  const raw = fs.readFileSync(file, 'utf8')
  const { next, count } = processFile(file, raw)
  if (count > 0 && next !== raw) {
    fs.writeFileSync(file, next, 'utf8')
    total += count
    report.push({ file: path.relative(srcRoot, file), count })
  }
}

report.sort((a, b) => b.count - a.count)
console.log(`Replaced ~${total} color occurrences in ${report.length} files`)
for (const r of report) console.log(`  ${r.count}\t${r.file}`)
