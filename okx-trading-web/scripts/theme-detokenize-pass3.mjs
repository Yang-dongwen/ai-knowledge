/**
 * 将残留 rgba 硬编码替换为 color-mix / token
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const src = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src')

const map = [
  ['rgba(251, 191, 36, 0.35)', 'color-mix(in srgb, var(--warning-strong) 35%, transparent)'],
  ['rgba(251, 191, 36, 0.28)', 'color-mix(in srgb, var(--warning-strong) 28%, transparent)'],
  ['rgba(251, 191, 36, 0.22)', 'color-mix(in srgb, var(--warning-strong) 22%, transparent)'],
  ['rgba(255, 255, 255, 0.55)', 'color-mix(in srgb, var(--surface-1) 55%, transparent)'],
  ['rgba(255, 255, 255, 0.02)', 'color-mix(in srgb, var(--surface-1) 2%, transparent)'],
  ['rgba(0, 0, 0, 0.06)', 'color-mix(in srgb, #000 6%, transparent)'],
  ['rgba(0, 0, 0, 0.55)', 'var(--overlay-mask)'],
  ['rgba(0, 0, 0, 0.5)', 'var(--overlay-mask)'],
  ['rgba(22, 119, 255, 0.08)', 'color-mix(in srgb, var(--info-color) 8%, transparent)'],
  ['rgba(22, 119, 255, 0.04)', 'color-mix(in srgb, var(--info-color) 4%, transparent)'],
  ['rgba(22, 119, 255, 0.45)', 'color-mix(in srgb, var(--info-color) 45%, transparent)'],
  ['rgba(22, 119, 255, 0.1)', 'color-mix(in srgb, var(--info-color) 10%, transparent)'],
  ['rgba(22, 119, 255, 0.03)', 'color-mix(in srgb, var(--info-color) 3%, transparent)'],
  ['rgba(22, 163, 74, 0.08)', 'color-mix(in srgb, var(--success-color) 8%, transparent)'],
  ['rgba(16, 185, 129, 0.1)', 'color-mix(in srgb, var(--success-strong) 10%, transparent)'],
  ['rgba(16, 185, 129, 0.25)', 'color-mix(in srgb, var(--success-strong) 25%, transparent)'],
  ['rgba(16, 185, 129, 0.22)', 'color-mix(in srgb, var(--success-strong) 22%, transparent)'],
  ['rgba(114, 46, 209, 0.18)', 'color-mix(in srgb, var(--indigo-text) 18%, transparent)'],
  ['rgba(114, 46, 209, 0.05)', 'color-mix(in srgb, var(--indigo-text) 5%, transparent)'],
  ['rgba(17, 24, 39, 0.08)', 'var(--focus-ring)'],
  ['rgba(6, 95, 70, 0.1)', 'color-mix(in srgb, var(--success-text) 10%, transparent)'],
  ['rgba(30, 58, 95, 0.12)', 'color-mix(in srgb, var(--info-text) 12%, transparent)'],
  ['rgba(226, 232, 240, 0.95)', 'color-mix(in srgb, var(--border-soft) 95%, transparent)'],
  ['rgba(59, 130, 246, 0.2)', 'color-mix(in srgb, var(--info-strong) 20%, transparent)'],
  ['rgba(148, 163, 184, 0.2)', 'color-mix(in srgb, var(--text-muted) 20%, transparent)'],
  ['rgba(79, 70, 229, 0.25)', 'color-mix(in srgb, var(--indigo-soft) 25%, transparent)']
]

function walk(d, out = []) {
  for (const n of fs.readdirSync(d)) {
    const p = path.join(d, n)
    const st = fs.statSync(p)
    if (st.isDirectory()) walk(p, out)
    else if (/\.(vue|scss)$/.test(n) && !['tokens.scss', 'variables.scss'].includes(n)) out.push(p)
  }
  return out
}

for (const f of walk(src)) {
  let t = fs.readFileSync(f, 'utf8')
  let c = 0
  for (const [from, to] of map) {
    if (t.includes(from)) {
      const parts = t.split(from)
      c += parts.length - 1
      t = parts.join(to)
    }
  }
  if (c) {
    fs.writeFileSync(f, t)
    console.log(c, path.relative(src, f))
  }
}

console.log('--- remaining rgba (non-token) ---')
for (const f of walk(src)) {
  const t = fs.readFileSync(f, 'utf8')
  const m = t.match(/rgba?\([^)]+\)/g)
  if (m) {
    const uniq = [...new Set(m)].filter((x) => !x.includes('var('))
    if (uniq.length) console.log(path.relative(src, f), uniq.join(' | '))
  }
}
