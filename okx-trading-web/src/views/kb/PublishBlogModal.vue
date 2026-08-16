<template>
  <a-modal
    :open="open"
    :title="updating ? '更新到博客' : '发布到博客'"
    :ok-text="updating ? '更新' : '发布'"
    cancel-text="取消"
    :confirm-loading="publishing"
    :ok-button-props="{ disabled: loading || !!loadError || !canPublish }"
    width="520px"
    destroy-on-close
    @ok="onOk"
    @cancel="emit('update:open', false)"
  >
    <div class="blog-modal">
      <p class="lead">
        将「{{ noteTitle || '未命名笔记' }}」同步到已关联的博客。笔记仍保存在知识库；正文中的图片和附件会上传到该博客公开存储。
      </p>

      <a-spin :spinning="loading">
        <div v-if="!canPublish" class="need-bind">
          <p class="err">{{ bindHint || '请先关联博客账户' }}</p>
          <template v-if="binding && binding.target !== 'platform'">
            <div class="field">
              <div class="label">博客站点</div>
              <a-input v-model:value="bindBaseUrl" placeholder="https://your-halo.example" allow-clear />
            </div>
            <div class="field">
              <div class="label">个人令牌</div>
              <a-input-password v-model:value="bindToken" placeholder="pat_…" />
            </div>
            <a-button type="primary" :loading="bindingSaving" block @click="saveBind">
              保存关联
            </a-button>
            <div class="hint muted">在 Halo 用户中心创建个人令牌（需文章 / 附件 / 分类标签权限）。工具台不代开博客账号。</div>
          </template>
        </div>
        <div v-else-if="loadError" class="err">{{ loadError }}</div>
        <template v-else>
          <div v-if="siteLabel" class="media-tip">发文目标：{{ siteLabel }}</div>
          <div class="field">
            <div class="label">分类</div>
            <a-select
              v-model:value="categoryNames"
              mode="tags"
              allow-clear
              show-search
              option-filter-prop="label"
              placeholder="选择或输入 Halo 分类"
              style="width: 100%"
              :options="categoryOptions"
            />
            <div class="hint muted">选项来自 Halo 站点；输入新名称将创建分类</div>
          </div>

          <div class="field">
            <div class="label">标签</div>
            <a-select
              v-model:value="tagNames"
              mode="tags"
              allow-clear
              show-search
              placeholder="选择或输入新标签"
              style="width: 100%"
              :options="tagOptions"
            />
            <div class="hint muted">输入新名称将在 Halo 创建标签</div>
          </div>

          <div v-if="mediaCount > 0" class="media-tip">
            将上传 {{ mediaCount }} 个知识库图片/附件到博客；第一张图片会作为封面。
          </div>
          <div v-else class="media-tip muted">本文没有知识库附件。</div>
        </template>
      </a-spin>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  kbApi,
  type BlogPublishOptions,
  type HaloBinding,
  type KbNoteItem
} from '@/api/kb.api'

const props = defineProps<{
  open: boolean
  noteId: string | null
  noteTitle?: string
  updating?: boolean
  defaultTagNames?: string[]
  defaultCategoryName?: string | null
}>()

const emit = defineEmits<{
  'update:open': [v: boolean]
  published: [note: KbNoteItem]
}>()

const loading = ref(false)
const publishing = ref(false)
const bindingSaving = ref(false)
const loadError = ref('')
const options = ref<BlogPublishOptions | null>(null)
const binding = ref<HaloBinding | null>(null)
const bindBaseUrl = ref('')
const bindToken = ref('')
const categoryNames = ref<string[]>([])
const tagNames = ref<string[]>([])

const canPublish = computed(() => !!binding.value?.bound)
const bindHint = computed(() => binding.value?.hint || '')
const siteLabel = computed(() => {
  const b = binding.value
  if (!b?.bound) return ''
  if (b.target === 'platform') return `平台博客 ${b.siteUrl || ''}`
  const user = b.haloUsername ? `（${b.haloUsername}）` : ''
  return `${b.publicUrl || b.siteUrl || ''}${user}`
})

const categoryOptions = computed(() =>
  (options.value?.categories || []).map((c) => ({
    value: c.displayName,
    label: c.displayName
  }))
)

const tagOptions = computed(() =>
  (options.value?.tags || []).map((t) => ({
    value: t.displayName,
    label: t.displayName
  }))
)

const mediaCount = computed(() => options.value?.mediaCount ?? 0)

async function load() {
  if (!props.noteId) return
  loading.value = true
  loadError.value = ''
  binding.value = null
  try {
    const bindRes = await kbApi.getHaloBinding()
    binding.value = bindRes.data
    if (!bindRes.data?.bound) {
      return
    }
    const res = await kbApi.getBlogPublishOptions(props.noteId)
    const data = res.data
    options.value = data
    if (data.published && (data.selectedCategoryNames?.length || data.selectedTagNames?.length)) {
      categoryNames.value = [...(data.selectedCategoryNames || [])]
      tagNames.value = [...(data.selectedTagNames || [])]
    } else {
      const haloCat = new Set((data.categories || []).map((c) => c.displayName))
      const fallbackCat = props.defaultCategoryName?.trim()
      categoryNames.value =
        fallbackCat && haloCat.has(fallbackCat) ? [fallbackCat] : []
      tagNames.value = [...(props.defaultTagNames || [])]
    }
  } catch (e: any) {
    loadError.value = e?.message || '加载博客分类/标签失败'
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.open, props.noteId] as const,
  ([open, id]) => {
    if (open && id) {
      categoryNames.value = []
      tagNames.value = []
      options.value = null
      bindBaseUrl.value = ''
      bindToken.value = ''
      void load()
    }
  }
)

async function saveBind() {
  const baseUrl = bindBaseUrl.value.trim()
  const token = bindToken.value.trim()
  if (!baseUrl || !token) {
    message.warning('请填写站点地址和个人令牌')
    return
  }
  bindingSaving.value = true
  try {
    const res = await kbApi.saveHaloBinding({ baseUrl, token })
    binding.value = res.data
    message.success('已关联博客')
    if (res.data.bound) {
      await load()
    }
  } catch (e: any) {
    message.error(e?.message || '关联失败')
  } finally {
    bindingSaving.value = false
  }
}

async function onOk() {
  if (!props.noteId || publishing.value || loading.value || loadError.value || !canPublish.value) return
  publishing.value = true
  try {
    const res = await kbApi.publishNoteToBlog(props.noteId, {
      categoryNames: categoryNames.value,
      tagNames: tagNames.value
    })
    const note = res.data
    if (note?.unresolvedMedia) {
      message.warning('已发布，但仍有附件未能转到博客，读者可能看不到部分文件')
    } else {
      message.success(props.updating ? '已同步到博客' : '已发布到博客')
    }
    emit('published', note)
    emit('update:open', false)
    if (note?.haloPermalink) {
      window.open(note.haloPermalink, '_blank', 'noopener')
    }
  } catch (e: any) {
    message.error(e?.message || '发布失败')
  } finally {
    publishing.value = false
  }
}
</script>

<style scoped lang="scss">
.blog-modal {
  padding: 4px 0 8px;
}

.lead {
  margin: 0 0 16px;
  font-size: 13.5px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.field {
  margin-bottom: 14px;
}

.label {
  font-weight: 650;
  font-size: 13px;
  margin-bottom: 6px;
}

.hint {
  font-size: 12px;
  margin-top: 6px;
}

.media-tip {
  font-size: 12.5px;
  padding: 8px 12px;
  border-radius: 10px;
  background: var(--surface-2);
  border: 1px solid var(--border-color);
}

.need-bind {
  margin-bottom: 8px;
}

.err {
  color: #cf1322;
  font-size: 13px;
  padding: 8px 0;
}

.muted {
  color: var(--text-secondary);
}
</style>
