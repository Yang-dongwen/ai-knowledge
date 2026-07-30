<template>
  <a-modal
    :open="open"
    title="分享文档"
    :footer="null"
    width="520px"
    destroy-on-close
    @cancel="emit('update:open', false)"
  >
    <div class="share-modal">
      <p class="lead">
        开启后将生成<strong>只读链接</strong>，任何人无需登录即可查看本文（不可编辑）。
      </p>

      <div class="row switch-row">
        <div>
          <div class="label">公开分享</div>
          <div class="hint muted">关闭后原链接立即失效</div>
        </div>
        <a-switch
          :checked="enabled"
          :loading="loading"
          checked-children="开"
          un-checked-children="关"
          @change="onToggle"
        />
      </div>

      <div v-if="enabled && shareUrl" class="link-block">
        <div class="label">分享链接</div>
        <div class="link-row">
          <a-input :value="shareUrl" readonly class="link-input" />
          <a-button type="primary" @click="copyLink">复制</a-button>
        </div>
        <div class="link-actions">
          <a-button type="link" size="small" @click="openLink">新窗口打开</a-button>
          <a-popconfirm
            title="重置后旧链接将失效，确定？"
            ok-text="重置"
            cancel-text="取消"
            @confirm="onRotate"
          >
            <a-button type="link" size="small" danger :loading="loading">重置链接</a-button>
          </a-popconfirm>
        </div>
      </div>

      <div v-else class="off-tip muted">
        开启分享后，可复制链接发给他人阅读。
      </div>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { kbApi, type KbShareStatus } from '@/api/kb.api'

const props = defineProps<{
  open: boolean
  noteId: string | null
}>()

const emit = defineEmits<{
  'update:open': [v: boolean]
}>()

const loading = ref(false)
const status = ref<KbShareStatus | null>(null)

const enabled = computed(() => !!status.value?.enabled)
const shareUrl = computed(() => {
  const path = status.value?.sharePath
  if (!path) return ''
  return `${window.location.origin}${path}`
})

async function load() {
  if (!props.noteId) return
  loading.value = true
  try {
    const res = await kbApi.getShareStatus(props.noteId)
    status.value = res.data
  } catch (e: any) {
    message.error(e?.message || '加载分享状态失败')
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.open, props.noteId] as const,
  ([open, id]) => {
    if (open && id) void load()
    if (!open) status.value = null
  }
)

async function onToggle(checked: boolean | string | number) {
  if (!props.noteId) return
  const on = !!checked
  loading.value = true
  try {
    const res = on
      ? await kbApi.enableShare(props.noteId)
      : await kbApi.disableShare(props.noteId)
    status.value = res.data
    message.success(on ? '已开启公开分享' : '已关闭分享')
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    loading.value = false
  }
}

async function onRotate() {
  if (!props.noteId) return
  loading.value = true
  try {
    const res = await kbApi.rotateShare(props.noteId)
    status.value = res.data
    message.success('链接已重置')
  } catch (e: any) {
    message.error(e?.message || '重置失败')
  } finally {
    loading.value = false
  }
}

async function copyLink() {
  if (!shareUrl.value) return
  try {
    await navigator.clipboard.writeText(shareUrl.value)
    message.success('已复制到剪贴板')
  } catch {
    message.warning('复制失败，请手动选择链接')
  }
}

function openLink() {
  if (shareUrl.value) window.open(shareUrl.value, '_blank')
}
</script>

<style scoped lang="scss">
.share-modal {
  padding: 4px 0 8px;
}

.lead {
  margin: 0 0 18px;
  font-size: 13.5px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.switch-row {
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--surface-2);
  border: 1px solid var(--border-color);
  margin-bottom: 16px;
}

.label {
  font-weight: 650;
  font-size: 14px;
  margin-bottom: 2px;
}

.hint {
  font-size: 12px;
}

.link-block {
  margin-top: 4px;
}

.link-row {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.link-input {
  flex: 1;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.link-actions {
  display: flex;
  gap: 4px;
  margin-top: 4px;
}

.off-tip {
  font-size: 13px;
  padding: 8px 0;
}

.muted {
  color: var(--text-secondary);
}
</style>
