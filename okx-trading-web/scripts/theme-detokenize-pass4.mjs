import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const src = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src')
const file = path.join(src, 'views/video-extract/index.vue')
let t = fs.readFileSync(file, 'utf8')

const map = [
  ['rgba(22, 119, 255, 0.2)', 'color-mix(in srgb, var(--info-color) 20%, transparent)'],
  ['rgba(22, 119, 255, 0.12)', 'color-mix(in srgb, var(--info-color) 12%, transparent)'],
  ['rgba(22, 119, 255, 0.18)', 'color-mix(in srgb, var(--info-color) 18%, transparent)'],
  ['rgba(22, 119, 255, 0.92)', 'color-mix(in srgb, var(--info-color) 92%, transparent)'],
  ['rgba(22, 119, 255, 0.35)', 'color-mix(in srgb, var(--info-color) 35%, transparent)'],
  ['rgba(22, 119, 255, 1)', 'var(--info-color)'],
  ['rgba(19, 194, 194, 0.25)', 'color-mix(in srgb, var(--success-color) 25%, transparent)'],
  ['rgba(19, 194, 194, 0.12)', 'color-mix(in srgb, var(--success-color) 12%, transparent)'],
  ['rgba(19, 194, 194, 0.03)', 'color-mix(in srgb, var(--success-color) 3%, transparent)'],
  ['rgba(114, 46, 209, 0.22)', 'color-mix(in srgb, var(--indigo-text) 22%, transparent)'],
  ['rgba(114, 46, 209, 0.12)', 'color-mix(in srgb, var(--indigo-text) 12%, transparent)'],
  ['rgba(114, 46, 209, 0.1)', 'color-mix(in srgb, var(--indigo-text) 10%, transparent)'],
  ['rgba(114, 46, 209, 0.03)', 'color-mix(in srgb, var(--indigo-text) 3%, transparent)'],
  ['rgba(0, 0, 0, 0.25)', 'color-mix(in srgb, #000 25%, transparent)'],
  ['rgba(0, 0, 0, 0.04)', 'color-mix(in srgb, #000 4%, transparent)'],
  ['rgba(0, 0, 0, 0.35)', 'color-mix(in srgb, #000 35%, transparent)'],
  ['rgba(255, 255, 255, 0.96)', 'color-mix(in srgb, var(--surface-1) 96%, transparent)'],
  ['rgba(255, 255, 255, 0.9)', 'color-mix(in srgb, var(--surface-1) 90%, transparent)'],
  ['rgba(248, 250, 252, 0.65)', 'color-mix(in srgb, var(--surface-hover) 65%, transparent)'],
  ['rgba(15, 23, 42, 0.72)', 'color-mix(in srgb, var(--text-primary) 72%, transparent)']
]

let c = 0
for (const [from, to] of map) {
  const n = t.split(from).length - 1
  if (n) {
    c += n
    t = t.split(from).join(to)
  }
}
fs.writeFileSync(file, t)
console.log('replaced', c)

const left = t.match(/rgba?\([^)]+\)/g) || []
const uniq = [...new Set(left)].filter((x) => !x.includes('var(') && !x.includes('color-mix'))
console.log('left', uniq)
