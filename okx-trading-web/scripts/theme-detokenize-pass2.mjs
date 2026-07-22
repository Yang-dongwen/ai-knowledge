/**
 * 二次清理遗漏硬编码色
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const src = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src')

const map = [
  ['#dc2626', 'var(--danger-strong)'],
  ['#FDBA74', 'var(--warning-border)'],
  ['#fdba74', 'var(--warning-border)'],
  ['#FAFBFC', 'var(--surface-2)'],
  ['#fafbfc', 'var(--surface-2)'],
  ['#f6f8fa', 'var(--surface-2)'],
  ['#F5F7FA', 'var(--surface-muted)'],
  ['#f5f7fa', 'var(--surface-muted)'],
  ['#ddd', 'var(--border-color)'],
  ['#eee', 'var(--border-subtle)'],
  ['#666', 'var(--text-secondary)'],
  ['#f5f3ff', 'var(--indigo-bg)'],
  ['#ddd6fe', 'var(--indigo-border)'],
  ['#6d28d9', 'var(--indigo-text)'],
  ['#8b5cf6', 'var(--indigo-soft)'],
  ['#fed7aa', 'var(--warning-border)'],
  ['#c2410c', 'var(--warning-text)'],
  ['#1677ff', 'var(--info-color)'],
  ['#13c2c2', 'var(--success-color)'],
  ['#722ed1', 'var(--indigo-text)'],
  ['#0958d9', 'var(--info-text)'],
  ['#eef2ff', 'var(--indigo-bg)'],
  ['#e6f4ff', 'var(--info-bg)'],
  ['#f0f5ff', 'var(--info-bg)'],
  ['#93c5fd', 'var(--info-border)'],
  ['#1e40af', 'var(--info-text)'],
  ['#eef6ff', 'var(--info-bg)'],
  ['#60a5fa', 'var(--info-strong)'],
  ['#38bdf8', 'var(--info-strong)'],
  ['#34d399', 'var(--success-strong)'],
  ['#000000', 'var(--stage-bg)'],
  ['#000', 'var(--stage-bg)'],
  ['#fef08a', 'var(--warning-border)'],
  ['#fb7185', 'var(--danger-strong)'],
  ['#0ea5e9', 'var(--info-color)'],
  ['#1e1b4b', 'var(--stage-bg-elevated)'],
  ['#1a1a22', 'var(--stage-bg-elevated)'],
  ['#888888', 'var(--text-muted)'],
  ['#888', 'var(--text-muted)'],
  ['#1f2937', 'var(--text-primary)'],
  ['#6b7280', 'var(--text-muted)'],
  ['#b45309', 'var(--warning-text)'],
  ['#fffbeb', 'var(--warning-bg)'],
  ['#f3f4f6', 'var(--surface-3)']
].sort((a, b) => b[0].length - a[0].length)

const files = [
  'views/ai-chat/index.vue',
  'views/home/index.vue',
  'views/member/index.vue',
  'views/video-extract/index.vue',
  'views/video-generate/aigen-ui.scss',
  'views/video-generate/index.vue',
  'views/auth/auth-shared.scss',
  'layouts/BasicLayout.vue',
  'components/ProfileCardModal.vue',
  'views/image-generate/index.vue',
  'views/member/recharge.vue',
  'views/user-manage/index.vue'
]

for (const rel of files) {
  const p = path.join(src, rel)
  if (!fs.existsSync(p)) continue
  let t = fs.readFileSync(p, 'utf8')
  let c = 0
  for (const [hex, tok] of map) {
    const re = new RegExp(hex.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi')
    const m = t.match(re)
    if (m) {
      c += m.length
      t = t.replace(re, tok)
    }
  }
  if (c) {
    fs.writeFileSync(p, t)
    console.log(c, rel)
  }
}

// home script accents
const homePath = path.join(src, 'views/home/index.vue')
let home = fs.readFileSync(homePath, 'utf8')
home = home
  .replaceAll("accent: '#1f2937'", "accent: 'var(--primary-color)'")
  .replaceAll("accentSoft: '#f3f4f6'", "accentSoft: 'var(--surface-3)'")
  .replaceAll("accent: '#b45309'", "accent: 'var(--warning-text)'")
  .replaceAll("accentSoft: '#fffbeb'", "accentSoft: 'var(--warning-bg)'")
  // if pass2 already replaced hex to var(--text-primary) etc in script
  .replaceAll("accent: 'var(--text-primary)'", "accent: 'var(--primary-color)'")
  .replaceAll("accentSoft: 'var(--surface-3)'", "accentSoft: 'var(--surface-3)'")
  .replaceAll("accent: 'var(--warning-text)'", "accent: 'var(--warning-text)'")
  .replaceAll("accentSoft: 'var(--warning-bg)'", "accentSoft: 'var(--warning-bg)'")
fs.writeFileSync(homePath, home)
console.log('home accents normalized')

// remaining scan
function walk(d, out = []) {
  for (const n of fs.readdirSync(d)) {
    const p = path.join(d, n)
    const st = fs.statSync(p)
    if (st.isDirectory()) walk(p, out)
    else if (/\.(vue|scss)$/.test(n) && !['tokens.scss', 'variables.scss'].includes(n)) out.push(p)
  }
  return out
}
console.log('--- remaining ---')
for (const f of walk(src)) {
  const t = fs.readFileSync(f, 'utf8')
  const m = t.match(/#[0-9a-fA-F]{3,8}/g)
  if (m) console.log(m.length, path.relative(src, f), [...new Set(m)].join(' '))
}
