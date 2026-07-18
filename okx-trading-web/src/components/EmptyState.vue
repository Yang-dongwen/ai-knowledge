<template>
  <div class="empty-state" :class="[`tone-${tone}`, { compact }]">
    <div class="empty-art" aria-hidden="true">
      <svg v-if="scene === 'tasks'" viewBox="0 0 160 120" class="art-svg">
        <ellipse cx="80" cy="98" rx="48" ry="8" fill="#e5e7eb" opacity="0.9" />
        <rect x="38" y="28" width="84" height="58" rx="14" fill="#f3f4f6" />
        <rect x="48" y="36" width="64" height="42" rx="12" fill="#1f2937" opacity="0.9" />
        <rect x="58" y="46" width="28" height="6" rx="3" fill="#fff" opacity="0.9" />
        <rect x="58" y="58" width="40" height="5" rx="2.5" fill="#fff" opacity="0.45" />
        <circle cx="118" cy="34" r="10" fill="#374151" />
        <path d="M114 34h8M118 30v8" stroke="#fff" stroke-width="1.8" stroke-linecap="round" />
      </svg>

      <svg v-else-if="scene === 'chat'" viewBox="0 0 160 120" class="art-svg">
        <ellipse cx="80" cy="100" rx="46" ry="7" fill="#e5e7eb" />
        <path
          d="M36 34c0-10 12-18 36-18s36 8 36 18v18c0 10-12 18-36 18h-6l-14 12 4-14c-12-2-20-8-20-16V34z"
          fill="#1f2937"
          opacity="0.92"
        />
        <circle cx="62" cy="48" r="3.5" fill="#fff" />
        <circle cx="78" cy="48" r="3.5" fill="#fff" />
        <circle cx="94" cy="48" r="3.5" fill="#fff" />
      </svg>

      <svg v-else-if="scene === 'detail'" viewBox="0 0 160 120" class="art-svg">
        <ellipse cx="80" cy="98" rx="44" ry="7" fill="#e5e7eb" />
        <rect x="42" y="26" width="76" height="56" rx="16" fill="#f3f4f6" />
        <rect x="52" y="34" width="56" height="40" rx="12" fill="#1f2937" opacity="0.9" />
        <path
          d="M64 54l10 10 18-20"
          fill="none"
          stroke="#fff"
          stroke-width="3.2"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>

      <svg v-else viewBox="0 0 160 120" class="art-svg">
        <circle cx="80" cy="52" r="28" fill="#1f2937" opacity="0.92" />
        <circle cx="80" cy="52" r="14" fill="#fff" opacity="0.18" />
        <ellipse cx="80" cy="96" rx="40" ry="7" fill="#e5e7eb" />
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
  padding: 32px 20px;
  border-radius: 12px;
  background: var(--surface-2);
  border: 1px dashed #e5e7eb;

  &.tone-soft {
    background: transparent;
    border: none;
    padding: 18px 12px;
  }

  &.compact {
    padding: 16px 10px;

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
  width: 120px;
  height: 90px;
  display: block;
}

.empty-title {
  font-size: 14px;
  font-weight: 600;
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
    transform: translateY(-4px);
  }
}

</style>
