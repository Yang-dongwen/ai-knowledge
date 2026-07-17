<template>
  <div class="home-portal">
    <section class="hero">
      <div class="hero-badge">
        <span class="pulse-dot" />
        AI Workspace · 今日可立即创作
      </div>
      <h1 class="hero-title">
        用 AI 把灵感
        <em>变成作品</em>
      </h1>
      <p class="hero-sub">
        对话、视频提取、视频生成、文生图 — 一站式工具台。圆润科技感界面，专注创作流。
      </p>
      <div class="hero-actions">
        <a-button type="primary" size="large" class="cta-main" @click="go('ai-chat')">
          <template #icon><RobotOutlined /></template>
          开始对话
        </a-button>
        <a-button size="large" class="cta-ghost" @click="go('video-extract')">
          <template #icon><VideoCameraOutlined /></template>
          提取视频要点
        </a-button>
      </div>
    </section>

    <section class="tool-grid">
      <button
        v-for="(card, i) in toolCards"
        :key="card.key"
        type="button"
        class="tool-card"
        :style="{ '--delay': `${i * 70}ms`, '--accent': card.accent, '--accent-soft': card.accentSoft }"
        @click="go(card.key)"
      >
        <div class="card-glow" />
        <div class="card-icon">
          <component :is="card.icon" />
        </div>
        <div class="card-body">
          <div class="card-title-row">
            <span class="card-title">{{ card.title }}</span>
            <span class="card-arrow">→</span>
          </div>
          <p class="card-desc">{{ card.desc }}</p>
          <div class="card-tags">
            <span v-for="t in card.tags" :key="t" class="tag">{{ t }}</span>
          </div>
        </div>
      </button>
    </section>

    <section class="tips-row">
      <div class="tip-card">
        <div class="tip-kicker">提示</div>
        <div class="tip-text">混合/仅画面提取时，请在模型管理配置 video_omni 视频理解模型。</div>
      </div>
      <div class="tip-card">
        <div class="tip-kicker">体验</div>
        <div class="tip-text">从工作台一键进入各 AI 工具，创作流程更顺手。</div>
      </div>
      <div class="tip-card">
        <div class="tip-kicker">创作</div>
        <div class="tip-text">文生图支持输入框一键润色，确认后再下发生成任务。</div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { markRaw, type Component } from 'vue'
import { useRouter } from 'vue-router'
import {
  RobotOutlined,
  VideoCameraOutlined,
  PictureOutlined,
  ThunderboltOutlined
} from '@ant-design/icons-vue'

const router = useRouter()

interface ToolCard {
  key: string
  title: string
  desc: string
  tags: string[]
  accent: string
  accentSoft: string
  icon: Component
}

const toolCards: ToolCard[] = [
  {
    key: 'ai-chat',
    title: 'AI 对话',
    desc: '多模型自由切换，连续会话与灵感头脑风暴。',
    tags: ['Chat', '多模型'],
    accent: '#7C3AED',
    accentSoft: 'rgba(124, 58, 237, 0.12)',
    icon: markRaw(RobotOutlined)
  },
  {
    key: 'video-extract',
    title: '视频提取',
    desc: '粘贴链接即可转录、总结、二创脚本与画面理解。',
    tags: ['Whisper', 'Omni'],
    accent: '#4F46E5',
    accentSoft: 'rgba(79, 70, 229, 0.12)',
    icon: markRaw(VideoCameraOutlined)
  },
  {
    key: 'video-generate',
    title: 'AI 视频生成',
    desc: '一句话生成分镜、画面与口播，自动合成短片。',
    tags: ['分镜', 'TTS'],
    accent: '#2563EB',
    accentSoft: 'rgba(37, 99, 235, 0.12)',
    icon: markRaw(ThunderboltOutlined)
  },
  {
    key: 'image-generate',
    title: 'AI 文生图',
    desc: '提示词润色后调用 FLUX，多比例批量出图。',
    tags: ['FLUX', '润色'],
    accent: '#059669',
    accentSoft: 'rgba(5, 150, 105, 0.12)',
    icon: markRaw(PictureOutlined)
  }
]

function go(key: string) {
  router.push(`/${key}`)
}
</script>

<style lang="scss" scoped>
.home-portal {
  max-width: 1100px;
  margin: 0 auto;
  padding-bottom: 28px;
}

.hero {
  position: relative;
  padding: 36px 32px 32px;
  border-radius: 28px;
  border: 1px solid rgba(199, 210, 254, 0.65);
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.94), rgba(245, 243, 255, 0.88) 55%, rgba(238, 242, 255, 0.9));
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.03),
    0 20px 50px rgba(99, 102, 241, 0.12);
  overflow: hidden;
  margin-bottom: 22px;
  animation: hero-in 0.55s ease both;

  &::before {
    content: '';
    position: absolute;
    right: -80px;
    top: -90px;
    width: 320px;
    height: 320px;
    border-radius: 50%;
    background: radial-gradient(circle, rgba(129, 140, 248, 0.35), transparent 68%);
    pointer-events: none;
  }

  &::after {
    content: '';
    position: absolute;
    left: -40px;
    bottom: -70px;
    width: 220px;
    height: 220px;
    border-radius: 50%;
    background: radial-gradient(circle, rgba(192, 132, 252, 0.22), transparent 70%);
    pointer-events: none;
  }
}

.hero-badge {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid rgba(199, 210, 254, 0.8);
  font-size: 12px;
  font-weight: 600;
  color: #4f46e5;
  margin-bottom: 16px;
}

.pulse-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.45);
  animation: pulse-ring 1.8s ease-out infinite;
}

.hero-title {
  position: relative;
  z-index: 1;
  margin: 0;
  font-size: clamp(28px, 4vw, 36px);
  font-weight: 800;
  letter-spacing: -0.035em;
  line-height: 1.2;
  color: #0f172a;

  em {
    font-style: normal;
    background: linear-gradient(120deg, #4f46e5, #7c3aed 50%, #a855f7);
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
  }
}

.hero-sub {
  position: relative;
  z-index: 1;
  margin: 12px 0 0;
  max-width: 560px;
  font-size: 14.5px;
  line-height: 1.65;
  color: #64748b;
}

.hero-actions {
  position: relative;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 22px;
}

.cta-main {
  height: 46px !important;
  border-radius: 999px !important;
  padding-inline: 22px !important;
  font-weight: 650 !important;
}

.cta-ghost {
  height: 46px !important;
  border-radius: 999px !important;
  padding-inline: 18px !important;
  border-color: #e2e8f0 !important;
  background: rgba(255, 255, 255, 0.75) !important;
  color: #334155 !important;
  font-weight: 560 !important;

  &:hover {
    border-color: #c7d2fe !important;
    color: #4f46e5 !important;
  }
}

.tool-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 18px;

  @media (max-width: 720px) {
    grid-template-columns: 1fr;
  }
}

.tool-card {
  position: relative;
  display: flex;
  gap: 16px;
  text-align: left;
  padding: 20px 20px 18px;
  border-radius: 22px;
  border: 1px solid rgba(226, 232, 240, 0.95);
  background: linear-gradient(165deg, rgba(255, 255, 255, 0.94), rgba(255, 255, 255, 0.86));
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.03),
    0 12px 32px rgba(99, 102, 241, 0.06);
  cursor: pointer;
  overflow: hidden;
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
  animation: card-in 0.55s ease both;
  animation-delay: var(--delay);

  &:hover {
    transform: translateY(-4px);
    border-color: color-mix(in srgb, var(--accent) 35%, #e2e8f0);
    box-shadow:
      0 8px 12px rgba(15, 23, 42, 0.04),
      0 22px 48px rgba(99, 102, 241, 0.14);

    .card-glow {
      opacity: 1;
    }

    .card-arrow {
      transform: translateX(4px);
      color: var(--accent);
    }

    .card-icon {
      transform: scale(1.05);
    }
  }

  &:active {
    transform: translateY(-1px);
  }
}

.card-glow {
  position: absolute;
  right: -30%;
  top: -40%;
  width: 70%;
  height: 90%;
  border-radius: 50%;
  background: radial-gradient(circle, var(--accent-soft), transparent 70%);
  opacity: 0.55;
  transition: opacity 0.25s ease;
  pointer-events: none;
}

.card-icon {
  position: relative;
  z-index: 1;
  width: 48px;
  height: 48px;
  border-radius: 16px;
  display: grid;
  place-items: center;
  font-size: 22px;
  color: var(--accent);
  background: var(--accent-soft);
  border: 1px solid color-mix(in srgb, var(--accent) 18%, transparent);
  flex-shrink: 0;
  transition: transform 0.2s ease;
}

.card-body {
  position: relative;
  z-index: 1;
  min-width: 0;
  flex: 1;
}

.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-title {
  font-size: 16px;
  font-weight: 750;
  color: #0f172a;
  letter-spacing: -0.02em;
}

.card-arrow {
  color: #94a3b8;
  font-size: 16px;
  transition: transform 0.2s ease, color 0.2s ease;
}

.card-desc {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.55;
  color: #64748b;
}

.card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.tag {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 9px;
  border-radius: 999px;
  color: var(--accent);
  background: var(--accent-soft);
}

.tips-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;

  @media (max-width: 900px) {
    grid-template-columns: 1fr;
  }
}

.tip-card {
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid rgba(226, 232, 240, 0.9);
  backdrop-filter: blur(8px);
  animation: card-in 0.55s ease both;
  animation-delay: 0.28s;
}

.tip-kicker {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: #818cf8;
  margin-bottom: 6px;
}

.tip-text {
  font-size: 12.5px;
  line-height: 1.55;
  color: #64748b;
}

@keyframes hero-in {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

@keyframes pulse-ring {
  0% {
    box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.45);
  }
  70% {
    box-shadow: 0 0 0 8px rgba(34, 197, 94, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(34, 197, 94, 0);
  }
}
</style>
