<template>
  <div class="empty-state" :class="[`tone-${tone}`, { compact }]">
    <div class="empty-art" aria-hidden="true">
      <!-- 插画：按场景切换 -->
      <svg v-if="scene === 'tasks'" viewBox="0 0 160 120" class="art-svg">
        <defs>
          <linearGradient id="es-g1" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="#818cf8" />
            <stop offset="100%" stop-color="#c084fc" />
          </linearGradient>
          <linearGradient id="es-g2" x1="0" y1="1" x2="1" y2="0">
            <stop offset="0%" stop-color="#38bdf8" stop-opacity="0.35" />
            <stop offset="100%" stop-color="#a78bfa" stop-opacity="0.15" />
          </linearGradient>
        </defs>
        <ellipse cx="80" cy="98" rx="48" ry="8" fill="url(#es-g2)" />
        <rect x="38" y="28" width="84" height="58" rx="14" fill="url(#es-g1)" opacity="0.18" />
        <rect x="48" y="36" width="64" height="42" rx="12" fill="url(#es-g1)" opacity="0.9" />
        <rect x="58" y="46" width="28" height="6" rx="3" fill="#fff" opacity="0.85" />
        <rect x="58" y="58" width="40" height="5" rx="2.5" fill="#fff" opacity="0.55" />
        <circle cx="118" cy="34" r="10" fill="#a5b4fc" opacity="0.9" />
        <path d="M114 34h8M118 30v8" stroke="#fff" stroke-width="1.8" stroke-linecap="round" />
      </svg>

      <svg v-else-if="scene === 'chat'" viewBox="0 0 160 120" class="art-svg">
        <defs>
          <linearGradient id="es-c1" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="#6366f1" />
            <stop offset="100%" stop-color="#a855f7" />
          </linearGradient>
        </defs>
        <ellipse cx="80" cy="100" rx="46" ry="7" fill="#c7d2fe" opacity="0.35" />
        <path
          d="M36 34c0-10 12-18 36-18s36 8 36 18v18c0 10-12 18-36 18h-6l-14 12 4-14c-12-2-20-8-20-16V34z"
          fill="url(#es-c1)"
          opacity="0.92"
        />
        <circle cx="62" cy="48" r="3.5" fill="#fff" />
        <circle cx="78" cy="48" r="3.5" fill="#fff" />
        <circle cx="94" cy="48" r="3.5" fill="#fff" />
      </svg>

      <svg v-else-if="scene === 'detail'" viewBox="0 0 160 120" class="art-svg">
        <defs>
          <linearGradient id="es-d1" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="#67e8f9" />
            <stop offset="100%" stop-color="#818cf8" />
          </linearGradient>
        </defs>
        <ellipse cx="80" cy="98" rx="44" ry="7" fill="#a5b4fc" opacity="0.28" />
        <rect x="42" y="26" width="76" height="56" rx="16" fill="url(#es-d1)" opacity="0.2" />
        <rect x="52" y="34" width="56" height="40" rx="12" fill="url(#es-d1)" opacity="0.88" />
        <path d="M64 54l10 10 18-20" fill="none" stroke="#fff" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round" />
      </svg>

      <svg v-else viewBox="0 0 160 120" class="art-svg">
        <defs>
          <linearGradient id="es-g0" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="#818cf8" />
            <stop offset="100%" stop-color="#c084fc" />
          </linearGradient>
        </defs>
        <circle cx="80" cy="52" r="28" fill="url(#es-g0)" opacity="0.9" />
        <circle cx="80" cy="52" r="14" fill="#fff" opacity="0.25" />
        <ellipse cx="80" cy="96" rx="40" ry="7" fill="#c7d2fe" opacity="0.35" />
      </svg>
    </div>

    <div class="empty-title">{{ title }}</div>
    <div v-if="description" class="empty-desc">{{ description }}</div>
    <div v-if="$slots.action" class="empty-action">
      <slot name="action" />
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    /** 场景插画 */
    scene?: 'tasks' | 'chat' | 'detail' | 'default'
    title: string
    description?: string
    tone?: 'default' | 'soft'
    compact?: boolean
  }>(),
  {
    scene: 'default',
    tone: 'default',
    compact: false
  }
)
</script>

<style lang="scss" scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 36px 20px;
  border-radius: 18px;
  background: linear-gradient(160deg, rgba(238, 242, 255, 0.45), rgba(245, 243, 255, 0.25));
  border: 1px dashed rgba(199, 210, 254, 0.7);

  &.tone-soft {
    background: transparent;
    border: none;
    padding: 20px 12px;
  }

  &.compact {
    padding: 18px 10px;

    .art-svg {
      width: 88px;
      height: 66px;
    }

    .empty-title {
      font-size: 13px;
    }
  }
}

.empty-art {
  margin-bottom: 10px;
  animation: empty-float 4.5s ease-in-out infinite;
}

.art-svg {
  width: 128px;
  height: 96px;
  display: block;
}

.empty-title {
  font-size: 14px;
  font-weight: 650;
  color: var(--text-primary);
  letter-spacing: -0.01em;
}

.empty-desc {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--text-secondary);
  line-height: 1.55;
  max-width: 280px;
}

.empty-action {
  margin-top: 14px;
}

@keyframes empty-float {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-5px);
  }
}
</style>
