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
        对话、视频提取、文章提取、视频生成、文生图 — 一站式 AI 工具台。知识库见顶栏独立入口。
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
      <div class="tip-card tip-card-click" role="button" tabindex="0" @click="go('member')" @keydown.enter="go('member')">
        <div class="tip-kicker">会员</div>
        <div class="tip-text">开通会员解锁后续权益；点击进入会员中心完成 Mock 充值联调。</div>
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
  ThunderboltOutlined,
  CrownOutlined,
  FileTextOutlined
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
    accent: 'var(--primary-color)',
    accentSoft: 'var(--surface-3)',
    icon: markRaw(RobotOutlined)
  },
  {
    key: 'video-extract',
    title: '视频提取',
    desc: '粘贴链接即可转录、总结、二创脚本与画面理解。',
    tags: ['Whisper', 'Omni'],
    accent: 'var(--primary-color)',
    accentSoft: 'var(--surface-3)',
    icon: markRaw(VideoCameraOutlined)
  },
  {
    key: 'video-generate',
    title: 'AI 视频生成',
    desc: '一句话生成分镜、画面与口播，自动合成短片。',
    tags: ['分镜', 'TTS'],
    accent: 'var(--primary-color)',
    accentSoft: 'var(--surface-3)',
    icon: markRaw(ThunderboltOutlined)
  },
  {
    key: 'image-generate',
    title: 'AI 文生图',
    desc: '提示词润色后调用 FLUX，多比例批量出图。',
    tags: ['FLUX', '润色'],
    accent: 'var(--primary-color)',
    accentSoft: 'var(--surface-3)',
    icon: markRaw(PictureOutlined)
  },
  {
    key: 'article-extract',
    title: '文章提取',
    desc: '粘贴新闻链接或正文，提取核心要点并二次创作。',
    tags: ['抓取', '二创'],
    accent: 'var(--primary-color)',
    accentSoft: 'var(--surface-3)',
    icon: markRaw(FileTextOutlined)
  },
  {
    key: 'member',
    title: '会员中心',
    desc: '查看会员状态，Mock 支付开通 / 续费会员。',
    tags: ['充值', 'MEMBER'],
    accent: 'var(--warning-text)',
    accentSoft: 'var(--warning-bg)',
    icon: markRaw(CrownOutlined)
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
  padding: 32px 28px 28px;
  border-radius: 16px;
  border: 1px solid var(--border-color);
  background: var(--surface-1);
  box-shadow:
    0 1px 2px color-mix(in srgb, var(--text-primary) 3%, transparent),
    0 8px 24px var(--grid-line);
  overflow: hidden;
  margin-bottom: 20px;
  animation: hero-in 0.45s ease both;

  &::before,
  &::after {
    display: none;
  }
}

.hero-badge {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 11px;
  border-radius: 999px;
  background: var(--surface-3);
  border: 1px solid var(--border-color);
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 14px;
}

.pulse-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--success-strong);
  box-shadow: 0 0 0 0 color-mix(in srgb, var(--success-strong) 45%, transparent);
  animation: pulse-ring 1.8s ease-out infinite;
}

.hero-title {
  position: relative;
  z-index: 1;
  margin: 0;
  font-size: clamp(26px, 3.6vw, 32px);
  font-weight: 750;
  letter-spacing: -0.03em;
  line-height: 1.25;
  color: var(--text-primary);

  em {
    font-style: normal;
    color: var(--primary-color);
    border-bottom: 2px solid var(--border-color);
    padding-bottom: 1px;
  }
}

.hero-sub {
  position: relative;
  z-index: 1;
  margin: 12px 0 0;
  max-width: 560px;
  font-size: 14.5px;
  line-height: 1.65;
  color: var(--text-secondary);
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
  height: 42px !important;
  border-radius: 12px !important;
  padding-inline: 18px !important;
  font-weight: 560 !important;
  background: var(--btn-primary-bg) !important;
  border: none !important;
  box-shadow: none !important;
  color: var(--btn-primary-text) !important;

  &:hover {
    background: var(--btn-primary-hover) !important;
    color: var(--btn-primary-text) !important;
  }
}

.cta-ghost {
  height: 42px !important;
  border-radius: 12px !important;
  padding-inline: 16px !important;
  border-color: var(--border-color) !important;
  background: var(--surface-1) !important;
  color: var(--text-secondary) !important;
  font-weight: 500 !important;
  box-shadow: none !important;

  &:hover {
    border-color: var(--border-strong) !important;
    color: var(--text-primary) !important;
    background: var(--surface-hover) !important;
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
  gap: 14px;
  text-align: left;
  padding: 18px 18px 16px;
  border-radius: 14px;
  border: 1px solid var(--border-color);
  background: var(--surface-1);
  box-shadow:
    0 1px 2px color-mix(in srgb, var(--text-primary) 3%, transparent),
    0 6px 18px color-mix(in srgb, var(--text-primary) 3%, transparent);
  cursor: pointer;
  overflow: hidden;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
  animation: card-in 0.45s ease both;
  animation-delay: var(--delay);

  &:hover {
    border-color: var(--border-strong);
    background: var(--surface-2);
    box-shadow:
      0 1px 2px var(--grid-line),
      0 10px 24px color-mix(in srgb, var(--text-primary) 6%, transparent);

    .card-arrow {
      transform: translateX(3px);
      color: var(--primary-strong);
    }
  }

  &:active {
    background: var(--surface-3);
  }
}

.card-glow {
  display: none;
}

.card-icon {
  position: relative;
  z-index: 1;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-size: 20px;
  color: var(--text-primary);
  background: var(--surface-3);
  border: 1px solid var(--border-color);
  flex-shrink: 0;
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
  color: var(--text-primary);
  letter-spacing: -0.02em;
}

.card-arrow {
  color: var(--text-muted);
  font-size: 16px;
  transition: transform 0.2s ease, color 0.2s ease;
}

.card-desc {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--text-secondary);
}

.card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.tag {
  font-size: 11px;
  font-weight: 550;
  padding: 2px 8px;
  border-radius: 6px;
  color: var(--soft-accent-text);
  background: var(--surface-3);
  border: 1px solid var(--border-color);
}

.tips-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;

  @media (max-width: 900px) {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .home-portal {
    padding-bottom: 12px;
  }

  .hero {
    padding: 20px 16px 18px;
    margin-bottom: 14px;
  }

  .hero-title {
    font-size: 22px;
  }

  .hero-sub {
    font-size: 13.5px;
  }

  .hero-actions {
    flex-direction: column;
    align-items: stretch;
    margin-top: 16px;

    .ant-btn {
      width: 100%;
    }
  }

  .tool-grid {
    gap: 10px;
  }

  .tool-card {
    padding: 14px;
  }
}

.tip-card {
  padding: 14px 16px;
  border-radius: 12px;
  background: var(--surface-1);
  border: 1px solid var(--border-color);
  animation: card-in 0.45s ease both;
  animation-delay: 0.2s;
}

.tip-card-click {
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;

  &:hover {
    border-color: var(--border-strong);
    box-shadow: 0 4px 12px color-mix(in srgb, var(--text-primary) 6%, transparent);
  }
}

.tip-kicker {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--text-secondary);
  margin-bottom: 6px;
}

.tip-text {
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--text-secondary);
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
    box-shadow: 0 0 0 0 color-mix(in srgb, var(--success-strong) 45%, transparent);
  }
  70% {
    box-shadow: 0 0 0 8px transparent;
  }
  100% {
    box-shadow: 0 0 0 0 transparent;
  }
}

</style>
