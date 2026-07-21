<template>
  <div class="video-extract-page">
    <!-- 顶部：提交区 -->
    <div class="submit-hero page-card">
      <div class="hero-text">
        <div class="hero-title-row">
          <h2 class="page-title">视频核心内容提取</h2>
          <a-tooltip :title="liveChannelTip">
            <span class="live-badge" :class="liveChannel">
              <span class="live-dot" />
              <span class="live-label">{{ liveChannelLabel }}</span>
              <span v-if="lastEventHint" class="live-hint">{{ lastEventHint }}</span>
            </span>
          </a-tooltip>
        </div>
        <p class="page-subtitle">
          粘贴抖音 / B站 / YouTube 链接：下载 · 转录 · 可选画面理解 · AI 提炼要点与二创
        </p>
      </div>

      <div class="submit-row">
        <a-input
          v-model:value="urlInput"
          size="large"
          allow-clear
          class="url-input"
          placeholder="粘贴视频链接，例如 https://www.bilibili.com/video/BVxxxx"
          @pressEnter="handleSubmit"
        >
          <template #prefix>
            <LinkOutlined class="input-prefix-icon" />
          </template>
        </a-input>
        <a-button
          type="primary"
          size="large"
          class="submit-btn"
          :loading="submitting"
          :disabled="!canSubmit"
          @click="handleSubmit"
        >
          <template #icon><ThunderboltOutlined /></template>
          开始提取
        </a-button>
      </div>

      <!-- 理解模式：独立一行，驱动下方 LLM / 画面模型 / 输出选项联动 -->
      <div class="mode-section">
        <div class="mode-section-head">
          <div class="mode-section-title">
            <span class="mode-section-kicker">理解模式</span>
            <span class="mode-section-sub">选择流水线深度，会联动下方模型与输出选项</span>
          </div>
          <div class="mode-section-meta">
            <a-tag class="mode-active-tag" color="processing">
              当前 · {{ modeCardLabel(options.understandingMode) }}
            </a-tag>
            <a-button size="small" class="cookie-btn" @click="openCookieModal">
              <template #icon><KeyOutlined /></template>
              抖音 Cookie
              <a-badge
                v-if="cookieStatus?.configured"
                status="success"
                :offset="[4, -2]"
                style="margin-left: 4px"
              />
            </a-button>
          </div>
        </div>
        <div class="mode-card-grid" role="radiogroup" aria-label="理解模式">
          <button
            v-for="m in modeCards"
            :key="m.value"
            type="button"
            class="mode-card"
            :class="{ active: options.understandingMode === m.value, [m.tone]: true }"
            role="radio"
            :aria-checked="options.understandingMode === m.value"
            @click="options.understandingMode = m.value"
          >
            <div class="mode-card-top">
              <span class="mode-card-icon">{{ m.icon }}</span>
              <span class="mode-card-check" v-if="options.understandingMode === m.value">✓</span>
            </div>
            <div class="mode-card-name">{{ m.label }}</div>
            <div class="mode-card-desc">{{ m.desc }}</div>
            <div class="mode-card-flow">
              <span v-for="(step, i) in m.flow" :key="step" class="flow-chip">
                <template v-if="i > 0"><span class="flow-arrow">→</span></template>{{ step }}
              </span>
            </div>
            <div class="mode-card-need">
              <span v-for="tag in m.needs" :key="tag" class="need-chip">{{ tag }}</span>
            </div>
          </button>
        </div>
        <p class="mode-section-hint">{{ modeHintText }}</p>
      </div>

      <!-- 总结用 Chat 模型（仅下载模式不需要） -->
      <div class="model-row" v-if="!isDownloadOnly">
        <div class="model-pick">
          <span class="opt-label">总结 LLM</span>
          <a-select
            v-model:value="selectedModelKey"
            class="model-select"
            size="large"
            show-search
            :options="modelSelectOptions"
            :filter-option="filterModelOption"
            placeholder="选择用于总结的 Chat 模型"
            :loading="modelsLoading"
            @change="onModelChange"
          />
          <a-button
            size="large"
            class="test-btn"
            :loading="testingModel"
            :disabled="!selectedModelKey"
            @click="handleTestModel"
          >
            <template #icon><ExperimentOutlined /></template>
            测试可用性
          </a-button>
          <a-button
            v-if="auth.isSuperAdmin"
            size="large"
            class="manage-btn"
            @click="modelManageOpen = true"
          >
            <template #icon><SettingOutlined /></template>
            Chat 模型
          </a-button>
        </div>
        <div class="model-status" v-if="selectedModelKey">
          <a-tag v-if="testingModel" color="processing">测试中…</a-tag>
          <a-tag v-else-if="currentModelTestStatus === 'ok'" color="success">
            ✓ 可用{{ lastTestLatency != null ? ` · ${lastTestLatency}ms` : '' }}
          </a-tag>
          <a-tag v-else-if="currentModelTestStatus === 'fail'" color="error">不可用</a-tag>
          <a-tag v-else color="default">未测试（可选，可直接创建）</a-tag>
          <span v-if="testErrorMsg && currentModelTestStatus === 'fail'" class="test-err" :title="testErrorMsg">
            {{ testErrorMsg }}
          </span>
        </div>
        <div class="model-status muted" v-else-if="!modelsLoading && availableProviders.length === 0">
          <a-tag color="warning">
            暂无 Chat 模型
            <template v-if="auth.isSuperAdmin"> — 请打开「Chat 模型」添加，并确认 yml 中配置了 api-key</template>
            <template v-else> — 请联系超级管理员配置模型</template>
          </a-tag>
        </div>
      </div>

      <!-- 带画面理解时：必须选择视频理解模型（库表 video_omni，不写死后端） -->
      <div v-if="needsOmniModel" class="model-row model-row-omni">
        <div class="model-pick">
          <span class="opt-label">视频理解模型</span>
          <a-select
            v-model:value="selectedOmniKey"
            class="model-select"
            size="large"
            show-search
            :options="omniSelectOptions"
            :filter-option="filterModelOption"
            placeholder="选择视频多模态理解模型"
            :loading="omniModelsLoading"
          />
          <a-button
            size="large"
            class="test-btn"
            :loading="testingOmni"
            :disabled="!selectedOmniKey"
            @click="handleTestOmni"
          >
            <template #icon><ExperimentOutlined /></template>
            测试可用性
          </a-button>
          <a-button
            v-if="auth.isSuperAdmin"
            size="large"
            class="manage-btn"
            @click="omniModelManageOpen = true"
          >
            <template #icon><SettingOutlined /></template>
            理解模型
          </a-button>
        </div>
        <div class="model-status" v-if="selectedOmniKey">
          <a-tag v-if="testingOmni" color="processing">测试中…</a-tag>
          <a-tag v-else-if="omniTestStatus === 'ok'" color="success">
            ✓ 可用{{ omniTestLatency != null ? ` · ${omniTestLatency}ms` : '' }}
          </a-tag>
          <a-tag v-else-if="omniTestStatus === 'fail'" color="error">不可用</a-tag>
          <a-tag v-else color="purple">混合/仅画面将使用此模型</a-tag>
        </div>
        <div class="model-status muted" v-else-if="!omniModelsLoading && !omniProviders.length">
          <a-tag color="warning">
            暂无视频理解模型
            <template v-if="auth.isSuperAdmin"> — 请打开「理解模型」添加 capability=video_omni</template>
            <template v-else> — 请联系超级管理员配置</template>
          </a-tag>
        </div>
      </div>

      <!-- 次要选项：语言 / 产物 / 平台（与模式解耦，仅下载时部分禁用） -->
      <div class="options-row options-row-secondary">
        <a-space wrap :size="16">
          <span class="opt-label">语言</span>
          <a-radio-group
            v-model:value="options.language"
            size="small"
            button-style="solid"
            :disabled="isDownloadOnly"
          >
            <a-radio-button value="zh">中文</a-radio-button>
            <a-radio-button value="en">English</a-radio-button>
          </a-radio-group>
          <a-divider type="vertical" />
          <a-checkbox v-model:checked="options.extractMindMap" :disabled="isDownloadOnly">思维导图</a-checkbox>
          <a-checkbox v-model:checked="options.generateRepurposeScript" :disabled="isDownloadOnly">二创脚本</a-checkbox>
        </a-space>
        <div class="platform-hints">
          <span class="hint-chip">抖音</span>
          <span class="hint-chip">B站</span>
          <span class="hint-chip">YouTube</span>
          <span class="hint-chip">小红书</span>
        </div>
      </div>

      <!-- 抖音 Cookie 上传弹窗 -->
      <a-modal
        v-model:open="cookieModalOpen"
        title="抖音 Cookie"
        :ok-text="'保存 Cookie'"
        cancel-text="关闭"
        :confirm-loading="cookieSaving"
        :ok-button-props="{ disabled: !cookieHeaderInput.trim() }"
        @ok="saveCookie"
      >
        <p class="cookie-hint">
          从浏览器开发者工具复制 Cookie 请求头字符串（整段即可），用于 yt-dlp 下载抖音等受限内容。不会在状态接口回显明文。
        </p>
        <div class="cookie-status-box" v-if="cookieStatus">
          <a-tag :color="cookieStatus.configured ? 'success' : 'default'">
            {{ cookieStatus.configured ? '已配置' : '未配置' }}
          </a-tag>
          <span class="muted" v-if="cookieStatus.cookieCount != null">
            {{ cookieStatus.cookieCount }} 条
          </span>
          <span class="muted" v-if="cookieStatus.lastModifiedAt">
            更新于 {{ cookieStatus.lastModifiedAt }}
          </span>
          <span class="muted" v-if="cookieStatus.hint">{{ cookieStatus.hint }}</span>
        </div>
        <a-textarea
          v-model:value="cookieHeaderInput"
          :rows="6"
          placeholder="粘贴 Cookie: xxx=yyy; ... 或纯 name=value; 列表"
          allow-clear
          style="margin-top: 10px"
        />
        <template #footer>
          <a-button
            danger
            :disabled="!cookieStatus?.configured"
            :loading="cookieClearing"
            @click="handleClearCookie"
          >
            清除 Cookie
          </a-button>
          <a-button @click="cookieModalOpen = false">关闭</a-button>
          <a-button
            type="primary"
            :loading="cookieSaving"
            :disabled="!cookieHeaderInput.trim()"
            @click="saveCookie"
          >
            保存 Cookie
          </a-button>
        </template>
      </a-modal>

      <ModelManageModal
        v-if="auth.isSuperAdmin"
        v-model:open="modelManageOpen"
        capability="chat"
        @changed="onModelsChanged"
      />
      <ModelManageModal
        v-if="auth.isSuperAdmin"
        v-model:open="omniModelManageOpen"
        capability="video_omni"
        @changed="onOmniModelsChanged"
      />
    </div>

    <!-- 主体：左列表 + 右详情 -->
    <div class="workspace">
      <!-- 左侧任务列表 -->
      <aside class="task-panel page-card">
        <div class="panel-header">
          <span class="panel-title">历史任务</span>
          <a-button type="text" size="small" :loading="listLoading" @click="loadTasks">
            <template #icon><ReloadOutlined /></template>
          </a-button>
        </div>

        <div class="task-list" v-if="tasks.length">
          <div
            v-for="task in tasks"
            :key="task.taskId"
            class="task-item"
            :class="{ active: selectedId === task.taskId }"
            @click="selectTask(task.taskId)"
          >
            <div class="task-item-top">
              <a-tag :color="statusMeta(task.status, task).color" class="status-tag">
                {{ statusMeta(task.status, task).label }}
              </a-tag>
              <div class="task-item-actions">
                <span class="task-time">{{ shortTime(task.createdAt) }}</span>
                <a-tooltip :title="pauseButtonTip(task)">
                  <a-button
                    v-if="canPause(task)"
                    type="text"
                    size="small"
                    class="task-pause-btn"
                    :loading="pausingId === task.taskId"
                    @click.stop="handlePause(task)"
                  >
                    <template #icon><PauseCircleOutlined /></template>
                  </a-button>
                </a-tooltip>
                <a-button
                  v-if="canRetry(task)"
                  type="text"
                  size="small"
                  class="task-retry-btn"
                  :loading="retryingId === task.taskId"
                  title="重试（可改模型）"
                  @click.stop="openRetryModal(task)"
                >
                  <template #icon><RedoOutlined /></template>
                </a-button>
                <a-button
                  type="text"
                  size="small"
                  danger
                  class="task-del-btn"
                  :loading="deletingId === task.taskId"
                  @click.stop="confirmDelete(task)"
                >
                  <template #icon><DeleteOutlined /></template>
                </a-button>
              </div>
            </div>
            <div class="task-title" :title="task.title || task.url">
              {{ task.title || '处理中…' }}
            </div>
            <div class="task-meta">
              <span class="platform-badge" v-if="task.platform">{{ platformLabel(task.platform) }}</span>
              <span class="platform-badge llm-badge" v-if="task.llmModel" :title="task.llmModel">
                {{ shortModelName(task.llmModel) }}
              </span>
              <span class="task-step" v-if="isRunning(task.status) || isPauseDraining(task)">
                {{ task.currentStep }}
              </span>
              <span class="task-cost" v-else-if="task.totalDurationMs != null" :title="timingTooltip(task)">
                耗时 {{ formatMs(task.totalDurationMs) }}
              </span>
              <span class="task-dur" v-else-if="task.durationSeconds">{{ formatDuration(task.durationSeconds) }}</span>
            </div>
          </div>
        </div>
        <EmptyState
          v-else
          scene="tasks"
          compact
          tone="soft"
          title="还没有任务"
          description="粘贴链接，一键提取核心内容"
        />

        <div class="list-footer" v-if="taskTotal > tasks.length">
          <a-button type="link" size="small" :loading="listLoading" @click="loadMore">加载更多</a-button>
        </div>
      </aside>

      <!-- 右侧详情 -->
      <main class="detail-panel page-card">
        <template v-if="!selectedId">
          <div class="empty-detail">
            <EmptyState
              scene="detail"
              title="选择或创建一个任务"
              description="提交链接后，可在此查看进度、要点、转录时间轴与原始视频"
            />
            <div class="pipeline-preview">
              <div class="pipe-step" v-for="s in pipelineSteps" :key="s.key">
                <div class="pipe-icon">{{ s.icon }}</div>
                <div class="pipe-name">{{ s.name }}</div>
              </div>
            </div>
          </div>
        </template>

        <template v-else-if="detailLoading && !detail">
          <div class="detail-loading">
            <a-spin size="large" tip="加载任务详情…" />
          </div>
        </template>

        <template v-else-if="detail">
          <!-- 标题栏 -->
          <div class="detail-header">
            <div class="detail-header-main">
              <a-tag :color="statusMeta(detail.status, detail).color">
                {{ statusMeta(detail.status, detail).label }}
              </a-tag>
              <h3 class="detail-title">{{ detail.title || '未命名视频' }}</h3>
              <div class="detail-sub">
                <span v-if="detail.platform" class="platform-badge">{{ platformLabel(detail.platform) }}</span>
                <span v-if="detail.llmModel" class="platform-badge llm-badge" :title="detail.llmProvider || ''">
                  总结:{{ shortModelName(detail.llmModel) }}
                </span>
                <span
                  v-if="detail.omniModel"
                  class="platform-badge llm-badge"
                  :title="detail.omniProvider || ''"
                >
                  理解:{{ shortModelName(detail.omniModel) }}
                </span>
                <span v-if="detail.durationSeconds">时长 {{ formatDuration(detail.durationSeconds) }}</span>
                <span v-if="detail.createdAt">创建于 {{ detail.createdAt }}</span>
                <a v-if="detail.url" :href="detail.url" target="_blank" rel="noopener" class="source-link">
                  打开源链接 <ExportOutlined />
                </a>
              </div>
            </div>
            <a-space>
              <a-button type="text" :loading="detailLoading" @click="refreshDetail">
                <template #icon><ReloadOutlined /></template>
              </a-button>
              <a-button
                v-if="detail.videoAvailable"
                type="primary"
                :loading="videoDownloading"
                @click="downloadTaskVideo(detail)"
              >
                <template #icon><DownloadOutlined /></template>
                下载视频
              </a-button>
              <a-tooltip v-if="canPause(detail)" :title="pauseButtonTip(detail)">
                <a-button :loading="pausingId === detail.taskId" @click="handlePause(detail)">
                  <template #icon><PauseCircleOutlined /></template>
                  暂停
                </a-button>
              </a-tooltip>
              <a-button
                v-if="canRetry(detail)"
                type="primary"
                ghost
                :loading="retryingId === detail.taskId"
                @click="openRetryModal(detail)"
              >
                <template #icon><RedoOutlined /></template>
                重试
              </a-button>
              <a-button
                type="text"
                danger
                :loading="deletingId === detail.taskId"
                @click="confirmDelete(detail)"
              >
                <template #icon><DeleteOutlined /></template>
                删除
              </a-button>
            </a-space>
          </div>

          <!-- 进度条（处理中） -->
          <div class="progress-block" v-if="isRunning(detail.status)">
            <a-steps
              :current="statusStepIndex(detail.status, detail)"
              size="small"
              :items="stepItemsWithTiming(detail)"
            />
            <div class="progress-hint">
              <a-spin size="small" />
              <span>{{ detail.currentStep || '处理中…' }} · {{ progressHintExtra(detail) }}</span>
            </div>
            <a-alert
              type="info"
              show-icon
              class="pause-policy-hint"
              message="暂停说明"
              description="暂停仅在步骤边界生效（下载 / 转录 / 画面理解 / 总结之间）。当前步骤会跑完后再中断；单次 Omni 请求无法中途强杀。"
            />
            <div class="timing-strip" v-if="hasAnyStepTiming(detail) || detail.status === 'UNDERSTANDING'">
              <span class="timing-chip" v-if="detail.downloadDurationMs != null">
                下载 <b>{{ formatMs(detail.downloadDurationMs) }}</b>
              </span>
              <span class="timing-chip" v-if="detail.transcribeDurationMs != null">
                转录 <b>{{ formatMs(detail.transcribeDurationMs) }}</b>
              </span>
              <span
                class="timing-chip timing-chip-understand"
                v-if="detail.understandDurationMs != null || detail.status === 'UNDERSTANDING'"
              >
                画面
                <b v-if="detail.understandDurationMs != null">{{ formatMs(detail.understandDurationMs) }}</b>
                <b v-else class="timing-running">进行中…</b>
              </span>
              <span class="timing-chip" v-if="detail.summarizeDurationMs != null">
                总结 <b>{{ formatMs(detail.summarizeDurationMs) }}</b>
              </span>
            </div>
          </div>

          <!-- 已点暂停、等待当前步骤跑完 -->
          <div class="progress-block pause-draining" v-else-if="isPauseDraining(detail)">
            <a-alert
              type="warning"
              show-icon
              class="pause-policy-hint"
              message="已请求暂停 · 等待当前步骤结束"
              :description="pauseDrainingDesc(detail)"
            />
            <div class="progress-hint">
              <a-spin size="small" />
              <span>{{ detail.currentStep || '暂停中，等待当前步骤结束…' }}</span>
            </div>
          </div>

          <!-- 步骤耗时（成功 / 失败均可展示已完成步骤） -->
          <div
            class="timing-panel"
            v-if="!isRunning(detail.status) && hasAnyStepTiming(detail)"
          >
            <div class="timing-panel-title">
              <span>执行耗时</span>
              <span class="timing-total" v-if="detail.totalDurationMs != null">
                总计 {{ formatMs(detail.totalDurationMs) }}
              </span>
            </div>
            <div class="timing-bars">
              <div
                class="timing-bar-row"
                v-for="row in timingRows(detail)"
                :key="row.key"
              >
                <div class="timing-bar-label">
                  <span>{{ row.label }}</span>
                  <span class="timing-bar-ms">{{ formatMs(row.ms) }}</span>
                </div>
                <div class="timing-bar-track">
                  <div
                    class="timing-bar-fill"
                    :class="row.key"
                    :style="{ width: timingBarWidth(row.ms, detail) }"
                  />
                </div>
              </div>
            </div>
            <div class="timing-meta" v-if="detail.startedAt || detail.finishedAt">
              <span v-if="detail.startedAt">开始 {{ detail.startedAt }}</span>
              <span v-if="detail.finishedAt">结束 {{ detail.finishedAt }}</span>
            </div>
          </div>

          <!-- 暂停完成提示（非「等待步骤结束」中） -->
          <a-alert
            v-if="detail.status === 'PAUSED' && !isPauseDraining(detail)"
            type="warning"
            show-icon
            class="fail-alert"
            message="任务已暂停"
          >
            <template #description>
              <div class="fail-desc">
                <span>
                  已在步骤边界中断（暂停不会强制中断进行中的下载 / 转录 / 总结）。
                  可「重试」从流水线重新排队，或先处理其它任务。
                </span>
                <a-button
                  type="primary"
                  size="small"
                  :loading="retryingId === detail.taskId"
                  @click="openRetryModal(detail)"
                >
                  <template #icon><RedoOutlined /></template>
                  重试
                </a-button>
              </div>
            </template>
          </a-alert>

          <!-- 失败 -->
          <a-alert
            v-if="detail.status === 'FAILED'"
            type="error"
            show-icon
            class="fail-alert"
            :message="detail.errorMessage || '任务失败'"
          >
            <template #description>
              <div class="fail-desc">
                <span>可检查链接、Whisper 与 AI Key，并重新选择模型后重试完整流水线。</span>
                <a-button
                  type="primary"
                  size="small"
                  danger
                  :loading="retryingId === detail.taskId"
                  @click="openRetryModal(detail)"
                >
                  <template #icon><RedoOutlined /></template>
                  重试
                </a-button>
              </div>
            </template>
          </a-alert>

          <!-- 重试弹窗：可重新配置 LLM（仅下载无需 LLM） -->
          <a-modal
            v-model:open="retryModalOpen"
            :title="retryTarget?.status === 'SUCCESS' ? '重新提取' : '重试任务'"
            :ok-text="retryTarget?.status === 'SUCCESS' ? '开始重新提取' : '开始重试'"
            cancel-text="取消"
            :confirm-loading="retryingId === retryTarget?.taskId"
            @ok="submitRetry"
          >
            <p class="retry-hint">
              将重新执行流水线。仅下载模式无需 LLM；其它模式可更换总结 LLM；若使用画面理解，请选择视频理解模型。
            </p>
            <div class="retry-url" v-if="retryTarget">
              <span class="muted">链接</span>
              <div class="url-text">{{ retryTarget.url }}</div>
            </div>
            <div class="retry-model-row" style="margin-bottom: 12px">
              <span class="opt-label">理解模式</span>
              <a-radio-group
                v-model:value="retryUnderstandingMode"
                size="small"
                button-style="solid"
                style="margin-top: 6px; display: block"
              >
                <a-radio-button value="download_only">仅下载</a-radio-button>
                <a-radio-button value="audio_only">仅音频</a-radio-button>
                <a-radio-button value="hybrid">混合</a-radio-button>
                <a-radio-button value="omni_only">仅画面</a-radio-button>
              </a-radio-group>
            </div>
            <div class="retry-model-row" v-if="!isRetryDownloadOnly">
              <span class="opt-label">总结 LLM</span>
              <a-select
                v-model:value="retryModelKey"
                class="model-select"
                show-search
                :options="modelSelectOptions"
                :filter-option="filterModelOption"
                placeholder="选择 Chat 模型"
                style="width: 100%; margin-top: 6px"
              />
            </div>
            <div class="retry-model-row" v-if="retryNeedsOmni" style="margin-top: 12px">
              <span class="opt-label">视频理解模型</span>
              <a-select
                v-model:value="retryOmniKey"
                class="model-select"
                show-search
                :options="omniSelectOptions"
                :filter-option="filterModelOption"
                placeholder="选择 video_omni 模型"
                style="width: 100%; margin-top: 6px"
              />
            </div>
            <div class="retry-actions" v-if="!isRetryDownloadOnly">
              <a-button
                size="small"
                :loading="retryTesting"
                :disabled="!retryModelKey"
                @click="testRetryModel"
              >
                测试总结模型
              </a-button>
              <a-tag v-if="retryTestOk" color="success">✓ 可用</a-tag>
              <a-tag v-else-if="retryTestFail" color="error">不可用</a-tag>
            </div>
          </a-modal>

          <!-- 成功结果：有 result，或仅下载模式下 videoAvailable -->
          <template v-if="detail.status === 'SUCCESS' && (detail.result || detail.videoAvailable)">
            <a-alert
              v-if="detail.degraded || detail.result?.degraded || detail.result?.summary?.degraded"
              type="warning"
              show-icon
              class="fail-alert"
              message="已降级为纯音频总结"
              :description="detail.degradeReason || detail.result?.degradeReason || detail.result?.summary?.degradeReason || '画面理解失败，已使用字幕总结'"
            />
            <a-tabs v-model:activeKey="activeTab" class="result-tabs">
              <!-- 概览 -->
              <a-tab-pane key="overview" tab="概览">
                <div class="overview-grid">
                  <div class="video-panel" v-if="detail.videoAvailable">
                    <div class="video-box">
                      <!-- 不用 a-spin 包 video：嵌套高度算不准时竖屏会把控件裁出可视区，表现为「有画面点不了播放」 -->
                      <div v-if="videoBlobLoading" class="video-loading-mask">
                        <a-spin tip="准备播放…" />
                      </div>
                      <template v-else-if="videoObjectUrl">
                        <video
                          ref="videoRef"
                          class="video-player"
                          controls
                          playsinline
                          webkit-playsinline
                          preload="metadata"
                          :src="videoObjectUrl"
                          @loadedmetadata="onVideoLoaded"
                          @loadeddata="onVideoLoaded"
                          @play="videoPlaying = true"
                          @pause="videoPlaying = false"
                          @ended="videoPlaying = false"
                          @error="onVideoElementError"
                        />
                        <!-- 自定义播放层：竖屏时原生控件易被裁切，保留可点按钮 -->
                        <button
                          v-show="!videoPlaying"
                          type="button"
                          class="video-play-btn"
                          aria-label="播放"
                          @click.stop.prevent="playVideo"
                        >
                          ▶ 播放
                        </button>
                      </template>
                      <div v-else class="video-placeholder-inner">
                        <VideoCameraOutlined />
                        <span>{{ videoBlobError || '正在准备播放…' }}</span>
                        <a-button
                          v-if="videoBlobError && detail.taskId"
                          size="small"
                          type="link"
                          @click="loadVideoBlob(detail.taskId, true)"
                        >
                          重试
                        </a-button>
                      </div>
                    </div>
                    <div class="video-toolbar">
                      <a-button
                        type="primary"
                        size="small"
                        :loading="videoDownloading"
                        :disabled="!detail.videoAvailable"
                        @click="downloadTaskVideo(detail)"
                      >
                        <template #icon><DownloadOutlined /></template>
                        下载视频
                      </a-button>
                      <span class="muted video-toolbar-hint">保存为本地 MP4（浏览器可播编码）</span>
                    </div>
                  </div>
                  <div class="video-box video-placeholder" v-else>
                    <VideoCameraOutlined />
                    <span>视频文件不可用（可能已清理）</span>
                  </div>

                  <div class="overview-side">
                    <div class="mini-card">
                      <div class="mini-label">核心要点</div>
                      <div class="mini-value">{{ detail.result?.summary?.keyPoints?.length || 0 }}</div>
                    </div>
                    <div class="mini-card">
                      <div class="mini-label">章节</div>
                      <div class="mini-value">{{ detail.result?.summary?.chapters?.length || 0 }}</div>
                    </div>
                    <div class="mini-card">
                      <div class="mini-label">字幕段</div>
                      <div class="mini-value">{{ detail.result?.transcription?.segments?.length || 0 }}</div>
                    </div>
                    <div class="quick-keypoints" v-if="detail.result?.summary?.keyPoints?.length">
                      <div class="qk-title">速览要点</div>
                      <div
                        v-for="(kp, i) in detail.result.summary.keyPoints.slice(0, 5)"
                        :key="i"
                        class="qk-item"
                        @click="seekToTimestamp(kp.timestamp)"
                      >
                        <span class="ts">{{ kp.timestamp }}</span>
                        <span class="pt">{{ kp.point }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </a-tab-pane>

              <!-- 画面理解 -->
              <a-tab-pane key="visual" tab="画面">
                <div v-if="detail.result?.summary?.visualSummary || detail.result?.summary?.visualKeyPoints?.length">
                  <div class="ch-summary" v-if="detail.result?.summary?.visualSummary" style="margin-bottom: 12px">
                    {{ detail.result.summary.visualSummary }}
                  </div>
                  <a-tag v-if="detail.result?.summary?.partialVisual" color="orange">画面为稀疏采样（partial）</a-tag>
                  <div class="kp-list" v-if="detail.result?.summary?.visualKeyPoints?.length" style="margin-top: 12px">
                    <div
                      v-for="(kp, i) in detail.result.summary.visualKeyPoints"
                      :key="'vk' + i"
                      class="kp-card"
                      @click="seekToTimestamp(kp.timestamp)"
                    >
                      <div class="kp-index">{{ i + 1 }}</div>
                      <div class="kp-body">
                        <a-tag color="geekblue" class="ts-tag">{{ kp.timestamp || '--' }}</a-tag>
                        <div class="kp-text">{{ kp.point }}</div>
                      </div>
                    </div>
                  </div>
                  <div v-if="detail.result?.summary?.onScreenTexts?.length" style="margin-top: 16px">
                    <div class="qk-title">屏幕文字 OCR</div>
                    <div v-for="(t, i) in detail.result.summary.onScreenTexts" :key="'ocr' + i" class="qk-item">
                      {{ t }}
                    </div>
                  </div>
                  <div v-if="detail.result?.summary?.scenes?.length" style="margin-top: 16px">
                    <div class="qk-title">场景</div>
                    <div v-for="(s, i) in detail.result.summary.scenes" :key="'sc' + i" class="qk-item">
                      {{ s }}
                    </div>
                  </div>
                </div>
                <a-empty v-else description="本次任务无画面理解结果（可能为「仅音频」或「仅下载」模式）" />
              </a-tab-pane>

              <!-- 核心要点 -->
              <a-tab-pane key="keypoints" tab="核心要点">
                <div class="kp-list" v-if="detail.result?.summary?.keyPoints?.length">
                  <div
                    v-for="(kp, i) in detail.result.summary.keyPoints"
                    :key="i"
                    class="kp-card"
                    @click="seekToTimestamp(kp.timestamp)"
                  >
                    <div class="kp-index">{{ i + 1 }}</div>
                    <div class="kp-body">
                      <a-tag color="blue" class="ts-tag">{{ kp.timestamp || '--' }}</a-tag>
                      <div class="kp-text">{{ kp.point }}</div>
                    </div>
                  </div>
                </div>
                <a-empty v-else description="暂无要点" />
              </a-tab-pane>

              <!-- 章节 -->
              <a-tab-pane key="chapters" tab="章节大纲">
                <a-timeline v-if="detail.result?.summary?.chapters?.length">
                  <a-timeline-item v-for="(ch, i) in detail.result.summary.chapters" :key="i" color="blue">
                    <div class="chapter-item" @click="seekToTimestamp(ch.timestamp)">
                      <div class="ch-head">
                        <span class="ch-title">{{ ch.title }}</span>
                        <a-tag>{{ ch.timestamp || '--' }}</a-tag>
                      </div>
                      <div class="ch-summary">{{ ch.summary }}</div>
                    </div>
                  </a-timeline-item>
                </a-timeline>
                <a-empty v-else description="暂无章节" />
              </a-tab-pane>

              <!-- 思维导图 -->
              <a-tab-pane key="mindmap" tab="思维导图">
                <div class="md-toolbar" v-if="detail.result?.summary?.mindMapMarkdown">
                  <a-button size="small" @click="copyText(detail.result.summary.mindMapMarkdown!)">
                    <template #icon><CopyOutlined /></template>
                    复制 Markdown
                  </a-button>
                </div>
                <div
                  v-if="detail.result?.summary?.mindMapMarkdown"
                  class="md-body"
                  v-html="renderMarkdown(detail.result.summary.mindMapMarkdown)"
                />
                <a-empty v-else description="未生成思维导图（提交时可能关闭了该选项）" />
              </a-tab-pane>

              <!-- 二创脚本 -->
              <a-tab-pane key="repurpose" tab="二创脚本">
                <div class="md-toolbar" v-if="detail.result?.summary?.repurposeScript">
                  <a-button type="primary" size="small" @click="copyText(detail.result.summary.repurposeScript!)">
                    <template #icon><CopyOutlined /></template>
                    复制文案
                  </a-button>
                </div>
                <div
                  v-if="detail.result?.summary?.repurposeScript"
                  class="script-box"
                >{{ detail.result.summary.repurposeScript }}</div>
                <a-empty v-else description="未生成二创脚本" />
              </a-tab-pane>

              <!-- 转录 -->
              <a-tab-pane key="transcript" tab="全文转录">
                <div class="transcript-toolbar">
                  <a-input-search
                    v-model:value="transcriptQuery"
                    placeholder="搜索字幕…"
                    allow-clear
                    style="max-width: 280px"
                  />
                  <a-button
                    size="small"
                    :disabled="!detail.result?.transcription?.text"
                    @click="copyText(detail.result?.transcription?.text || '')"
                  >
                    <template #icon><CopyOutlined /></template>
                    复制全文
                  </a-button>
                </div>
                <div class="segment-list" v-if="filteredSegments.length">
                  <div
                    v-for="seg in filteredSegments"
                    :key="seg.id"
                    class="seg-row"
                    :class="{ active: activeSegId === seg.id }"
                    @click="seekToSeconds(seg.start)"
                  >
                    <span class="seg-time">{{ formatSeconds(seg.start) }}</span>
                    <span class="seg-text" v-html="highlightQuery(seg.text)" />
                  </div>
                </div>
                <a-empty v-else description="暂无转录内容" />
              </a-tab-pane>
            </a-tabs>
          </template>

          <!-- 处理中但尚无结果 -->
          <div v-else-if="isRunning(detail.status)" class="waiting-card">
            <div class="waiting-illustration">⏳</div>
            <p>流水线运行中：下载 → 转录 → AI 总结</p>
            <p class="muted">列表会自动刷新，完成后结果会出现在这里</p>
          </div>
        </template>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  LinkOutlined,
  ThunderboltOutlined,
  ReloadOutlined,
  VideoCameraOutlined,
  ExportOutlined,
  CopyOutlined,
  DeleteOutlined,
  ExclamationCircleOutlined,
  ExperimentOutlined,
  SettingOutlined,
  RedoOutlined,
  PauseCircleOutlined,
  KeyOutlined,
  DownloadOutlined
} from '@ant-design/icons-vue'
import { createVNode } from 'vue'
import MarkdownIt from 'markdown-it'
import { videoApi, type VideoCookieStatus } from '@/api/video.api'
import { connectVideoTaskEvents, type VideoTaskSseEvent } from '@/api/video.events'
import type { VideoTaskItem, TranscriptionSegment, AiProvider } from '@/types/api'
import ModelManageModal from './ModelManageModal.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth.store'

const auth = useAuthStore()

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

const urlInput = ref('')
const submitting = ref(false)
const options = reactive({
  language: 'zh',
  extractMindMap: true,
  generateRepurposeScript: true,
  /** download_only | audio_only | hybrid | omni_only */
  understandingMode: 'audio_only'
})

/** 抖音 Cookie 弹窗 */
const cookieModalOpen = ref(false)
const cookieHeaderInput = ref('')
const cookieStatus = ref<VideoCookieStatus | null>(null)
const cookieSaving = ref(false)
const cookieClearing = ref(false)

/** JWT 拉取视频后的本地 object URL（勿直接用 videoStreamUrl 作 src） */
const videoObjectUrl = ref('')
const videoBlobLoading = ref(false)
const videoBlobError = ref('')
const videoBlobTaskId = ref('')
/** 是否已在播放（用于隐藏中央「播放」按钮） */
const videoPlaying = ref(false)
/** 正在触发浏览器下载本地文件 */
const videoDownloading = ref(false)

/** provider::modelId — 总结 Chat */
const selectedModelKey = ref('')
const availableProviders = ref<AiProvider[]>([])
const modelsLoading = ref(false)
const modelManageOpen = ref(false)
const testingModel = ref(false)
/** 测试通过的 modelKey 集合 */
const testedOkKeys = ref<Set<string>>(new Set())
const testedFailKeys = ref<Set<string>>(new Set())
const testErrorMsg = ref('')
const lastTestLatency = ref<number | null>(null)

/** provider::modelId — 视频理解 video_omni */
const selectedOmniKey = ref('')
const omniProviders = ref<AiProvider[]>([])
const omniModelsLoading = ref(false)
const omniModelManageOpen = ref(false)
const testingOmni = ref(false)
const omniTestedOkKeys = ref<Set<string>>(new Set())
const omniTestedFailKeys = ref<Set<string>>(new Set())
const omniTestLatency = ref<number | null>(null)

const tasks = ref<VideoTaskItem[]>([])
const taskTotal = ref(0)
const listPage = ref(0)
const listLoading = ref(false)
const selectedId = ref('')
const detail = ref<VideoTaskItem | null>(null)
const detailLoading = ref(false)
const activeTab = ref('overview')
const transcriptQuery = ref('')
const activeSegId = ref<number | null>(null)
const videoRef = ref<HTMLVideoElement | null>(null)
const deletingId = ref('')
const retryingId = ref('')
const pausingId = ref('')
const retryModalOpen = ref(false)
const retryTarget = ref<VideoTaskItem | null>(null)
const retryModelKey = ref('')
const retryOmniKey = ref('')
const retryUnderstandingMode = ref('audio_only')
const retryTesting = ref(false)
const retryTestOk = ref(false)
const retryTestFail = ref(false)

let pollTimer: ReturnType<typeof setTimeout> | null = null

const isDownloadOnly = computed(() => options.understandingMode === 'download_only')

const isRetryDownloadOnly = computed(() => retryUnderstandingMode.value === 'download_only')

const needsOmniModel = computed(
  () => options.understandingMode === 'hybrid' || options.understandingMode === 'omni_only'
)

/** 理解模式卡片：独立一行展示，文案说明与下方控件联动 */
const modeCards = [
  {
    value: 'download_only',
    label: '仅下载',
    icon: '⬇️',
    tone: 'tone-download',
    desc: '只拉视频到本地，不转录、不总结',
    flow: ['下载', '完成'],
    needs: ['无需 LLM']
  },
  {
    value: 'audio_only',
    label: '仅音频',
    icon: '🎙️',
    tone: 'tone-audio',
    desc: '口播/讲解类：字幕 + 要点 + 二创',
    flow: ['下载', '转录', '总结'],
    needs: ['总结 LLM']
  },
  {
    value: 'hybrid',
    label: '混合理解',
    icon: '🎬',
    tone: 'tone-hybrid',
    desc: '语音与画面一起看，信息更完整',
    flow: ['下载', '转录', '画面', '总结'],
    needs: ['总结 LLM', '视频理解模型']
  },
  {
    value: 'omni_only',
    label: '仅画面',
    icon: '👁️',
    tone: 'tone-omni',
    desc: '演示/PPT/无口播：主看画面与字幕',
    flow: ['下载', '画面', '总结'],
    needs: ['总结 LLM', '视频理解模型']
  }
] as const

function modeCardLabel(mode?: string) {
  return modeCards.find((m) => m.value === mode)?.label || mode || '—'
}

const modeHintText = computed(() => {
  switch (options.understandingMode) {
    case 'download_only':
      return '仅下载：下方无需选择模型；可配置抖音 Cookie 后提交链接。'
    case 'audio_only':
      return '仅音频：请选择总结 LLM；思维导图 / 二创脚本可选。'
    case 'hybrid':
      return '混合模式：请同时选择总结 LLM 与视频理解模型。'
    case 'omni_only':
      return '仅画面：请选择总结 LLM 与视频理解模型；不跑 Whisper 转录。'
    default:
      return ''
  }
})

const retryNeedsOmni = computed(
  () =>
    retryUnderstandingMode.value === 'hybrid' || retryUnderstandingMode.value === 'omni_only'
)

const modelSelectOptions = computed(() =>
  availableProviders.value.map((p) => ({
    label: p.name,
    options: (p.models || []).map((m) => ({
      label: m.name || m.id,
      value: `${p.key}::${m.id}`,
      title: m.id
    }))
  }))
)

const omniSelectOptions = computed(() =>
  omniProviders.value.map((p) => ({
    label: p.name,
    options: (p.models || []).map((m) => ({
      label: m.name || m.id,
      value: `${p.key}::${m.id}`,
      title: m.id
    }))
  }))
)

const currentModelTestStatus = computed<'ok' | 'fail' | 'none'>(() => {
  if (!selectedModelKey.value) return 'none'
  if (testedOkKeys.value.has(selectedModelKey.value)) return 'ok'
  if (testedFailKeys.value.has(selectedModelKey.value)) return 'fail'
  return 'none'
})

const omniTestStatus = computed<'ok' | 'fail' | 'none'>(() => {
  if (!selectedOmniKey.value) return 'none'
  if (omniTestedOkKeys.value.has(selectedOmniKey.value)) return 'ok'
  if (omniTestedFailKeys.value.has(selectedOmniKey.value)) return 'fail'
  return 'none'
})

const canSubmit = computed(() => {
  if (!urlInput.value.trim() || submitting.value) return false
  if (isDownloadOnly.value) return true
  if (!selectedModelKey.value) return false
  if (needsOmniModel.value && !selectedOmniKey.value) return false
  return true
})

function parseModelKey(key: string): { provider: string; model: string } | null {
  const i = key.indexOf('::')
  if (i <= 0) return null
  return { provider: key.slice(0, i), model: key.slice(i + 2) }
}

function shortModelName(id?: string | null) {
  if (!id) return ''
  const parts = id.split('/')
  return parts[parts.length - 1] || id
}

function filterModelOption(input: string, option: any) {
  const q = (input || '').toLowerCase()
  const label = String(option?.label || '').toLowerCase()
  const value = String(option?.value || '').toLowerCase()
  return label.includes(q) || value.includes(q)
}

function onModelChange() {
  testErrorMsg.value = ''
  lastTestLatency.value = null
}

function pickDefaultKey(
  providers: AiProvider[],
  current: string
): { key: string; cleared: boolean } {
  if (current) {
    const still = providers.some((p) =>
      (p.models || []).some((m) => `${p.key}::${m.id}` === current)
    )
    if (still) return { key: current, cleared: false }
    return { key: '', cleared: true }
  }
  if (providers.length && providers[0].models?.length) {
    return { key: `${providers[0].key}::${providers[0].models![0].id}`, cleared: false }
  }
  return { key: '', cleared: false }
}

async function loadModels() {
  modelsLoading.value = true
  try {
    const res = await videoApi.listModels('chat')
    availableProviders.value = res.data || []
    const picked = pickDefaultKey(availableProviders.value, selectedModelKey.value)
    selectedModelKey.value = picked.key
    if (picked.cleared) {
      testedOkKeys.value = new Set()
      testedFailKeys.value = new Set()
    }
  } catch {
    availableProviders.value = []
  } finally {
    modelsLoading.value = false
  }
}

async function loadOmniModels() {
  omniModelsLoading.value = true
  try {
    const res = await videoApi.listModels('video_omni')
    omniProviders.value = res.data || []
    const picked = pickDefaultKey(omniProviders.value, selectedOmniKey.value)
    selectedOmniKey.value = picked.key
    if (picked.cleared) {
      omniTestedOkKeys.value = new Set()
      omniTestedFailKeys.value = new Set()
    }
  } catch {
    omniProviders.value = []
  } finally {
    omniModelsLoading.value = false
  }
}

async function onModelsChanged() {
  await loadModels()
}

async function onOmniModelsChanged() {
  await loadOmniModels()
}

watch(
  () => options.understandingMode,
  (mode) => {
    if ((mode === 'hybrid' || mode === 'omni_only') && !omniProviders.value.length) {
      void loadOmniModels()
    }
  }
)

async function handleTestOmni() {
  const parsed = parseModelKey(selectedOmniKey.value)
  if (!parsed) {
    message.warning('请先选择视频理解模型')
    return
  }
  testingOmni.value = true
  omniTestLatency.value = null
  try {
    const res = await videoApi.testModel({
      provider: parsed.provider,
      model: parsed.model
    })
    const result = res.data
    omniTestLatency.value = result.latencyMs ?? null
    if (result.available) {
      omniTestedOkKeys.value = new Set([...omniTestedOkKeys.value, selectedOmniKey.value])
      const nextFail = new Set(omniTestedFailKeys.value)
      nextFail.delete(selectedOmniKey.value)
      omniTestedFailKeys.value = nextFail
      message.success(`视频理解模型可用${result.latencyMs != null ? `（${result.latencyMs}ms）` : ''}`)
    } else {
      omniTestedFailKeys.value = new Set([...omniTestedFailKeys.value, selectedOmniKey.value])
      const nextOk = new Set(omniTestedOkKeys.value)
      nextOk.delete(selectedOmniKey.value)
      omniTestedOkKeys.value = nextOk
      message.error(result.errorMessage || '模型不可用')
    }
  } catch (e: any) {
    omniTestedFailKeys.value = new Set([...omniTestedFailKeys.value, selectedOmniKey.value])
    message.error(e?.message || '测试请求失败')
  } finally {
    testingOmni.value = false
  }
}

async function handleTestModel() {
  const parsed = parseModelKey(selectedModelKey.value)
  if (!parsed) {
    message.warning('请先选择模型')
    return
  }
  testingModel.value = true
  testErrorMsg.value = ''
  lastTestLatency.value = null
  try {
    const res = await videoApi.testModel({
      provider: parsed.provider,
      model: parsed.model
    })
    const result = res.data
    lastTestLatency.value = result.latencyMs ?? null
    if (result.available) {
      testedOkKeys.value = new Set([...testedOkKeys.value, selectedModelKey.value])
      const nextFail = new Set(testedFailKeys.value)
      nextFail.delete(selectedModelKey.value)
      testedFailKeys.value = nextFail
      message.success(`模型可用${result.latencyMs != null ? `（${result.latencyMs}ms）` : ''}`)
    } else {
      testedFailKeys.value = new Set([...testedFailKeys.value, selectedModelKey.value])
      const nextOk = new Set(testedOkKeys.value)
      nextOk.delete(selectedModelKey.value)
      testedOkKeys.value = nextOk
      testErrorMsg.value = result.errorMessage || '模型不可用'
      message.error(testErrorMsg.value)
    }
  } catch (e: any) {
    testedFailKeys.value = new Set([...testedFailKeys.value, selectedModelKey.value])
    const nextOk = new Set(testedOkKeys.value)
    nextOk.delete(selectedModelKey.value)
    testedOkKeys.value = nextOk
    // axios 超时 code 为 ECONNABORTED；统一提示 10s 不可用
    const isTimeout =
      e?.code === 'ECONNABORTED' ||
      String(e?.message || '').toLowerCase().includes('timeout')
    testErrorMsg.value = isTimeout
      ? '超过 10 秒无响应，判定不可用'
      : e?.message || '测试请求失败'
    message.error(testErrorMsg.value)
  } finally {
    testingModel.value = false
  }
}

const pipelineSteps = [
  { key: 'PENDING', name: '排队', icon: '①' },
  { key: 'DOWNLOADING', name: '下载', icon: '②' },
  { key: 'TRANSCRIBING', name: '转录', icon: '③' },
  { key: 'UNDERSTANDING', name: '画面', icon: '④' },
  { key: 'SUMMARIZING', name: '总结', icon: '⑤' },
  { key: 'SUCCESS', name: '完成', icon: '⑥' }
]

const STATUS_MAP: Record<string, { label: string; color: string }> = {
  PENDING: { label: '排队中', color: 'default' },
  DOWNLOADING: { label: '下载中', color: 'processing' },
  TRANSCRIBING: { label: '转录中', color: 'purple' },
  UNDERSTANDING: { label: '理解画面中', color: 'geekblue' },
  SUMMARIZING: { label: '总结中', color: 'cyan' },
  SUCCESS: { label: '已完成', color: 'success' },
  FAILED: { label: '失败', color: 'error' },
  PAUSED: { label: '已暂停', color: 'warning' }
}

/** 暂停仅在步骤边界生效 */
const PAUSE_STEP_BOUNDARY_TIP =
  '暂停仅在步骤边界生效：当前「下载 / 转录 / 画面理解 / 总结」会先跑完，再中断并释放并发槽；单次 Omni 请求无法中途强杀。'

function statusMeta(status?: string, task?: VideoTaskItem | null) {
  if (task && isPauseDraining(task)) {
    return { label: '暂停中', color: 'warning' }
  }
  return STATUS_MAP[status || ''] || { label: status || '未知', color: 'default' }
}

function isRunning(status?: string) {
  return [
    'PENDING',
    'DOWNLOADING',
    'TRANSCRIBING',
    'UNDERSTANDING',
    'SUMMARIZING'
  ].includes(status || '')
}

/** 已点暂停，但当前步骤可能仍在跑（等待边界生效） */
function isPauseDraining(task?: VideoTaskItem | null) {
  if (!task || task.status !== 'PAUSED') return false
  const step = task.currentStep || ''
  return step.includes('暂停中') || step.includes('等待当前步骤')
}

function canPause(task?: VideoTaskItem | null) {
  if (!task) return false
  return isRunning(task.status)
}

function pauseButtonTip(task?: VideoTaskItem | null) {
  if (!task) return PAUSE_STEP_BOUNDARY_TIP
  if (task.status === 'PENDING') {
    return '排队中暂停：立即取消排队，不会开始执行'
  }
  return PAUSE_STEP_BOUNDARY_TIP
}

function pauseDrainingDesc(task?: VideoTaskItem | null) {
  return (
    (task?.currentStep ? `${task.currentStep}。` : '') +
    '暂停只在步骤边界生效，请稍候；完成后可「重试」继续。其它排队任务可先被调度。'
  )
}

/** 列表/详情需要继续轮询的任务（含暂停等待收尾） */
function needsPoll(task?: VideoTaskItem | null) {
  if (!task) return false
  return isRunning(task.status) || isPauseDraining(task)
}

/** 按任务理解模式裁剪步骤：仅下载仅 PENDING/DOWNLOADING/SUCCESS；仅音频不展示「画面」；仅画面不展示「转录」 */
function pipelineStepsFor(task?: VideoTaskItem | null) {
  const mode = (task?.understandingMode || task?.result?.understandingMode || '').toLowerCase()
  if (mode === 'download_only' || mode === 'download' || mode === 'video_only') {
    return pipelineSteps.filter((s) =>
      ['PENDING', 'DOWNLOADING', 'SUCCESS'].includes(s.key)
    )
  }
  return pipelineSteps.filter((s) => {
    if (s.key === 'UNDERSTANDING') {
      // 未知模式时，有画面耗时或正处于 UNDERSTANDING 则展示
      if (mode === 'audio_only') return false
      if (mode === 'hybrid' || mode === 'omni_only') return true
      return (
        task?.status === 'UNDERSTANDING' ||
        task?.understandDurationMs != null ||
        !!task?.result?.summary?.multimodal
      )
    }
    if (s.key === 'TRANSCRIBING') {
      if (mode === 'omni_only') return false
      return true
    }
    if (s.key === 'SUMMARIZING') {
      if (mode === 'download_only') return false
      return true
    }
    return true
  })
}

function statusStepIndex(status?: string, task?: VideoTaskItem | null) {
  const order = pipelineStepsFor(task).map((s) => s.key)
  const i = order.indexOf(status || '')
  return i >= 0 ? i : 0
}

function progressHintExtra(task?: VideoTaskItem | null) {
  if (!task) return '请稍候'
  if (task.status === 'UNDERSTANDING') {
    return '画面理解可能较久（Omni 分片调用），请稍候'
  }
  if (task.status === 'TRANSCRIBING') {
    return '转录可能需要几分钟，请稍候'
  }
  if (task.status === 'DOWNLOADING') {
    return '下载与合并可能需要几分钟，请稍候'
  }
  if (task.status === 'SUMMARIZING') {
    return '正在生成章节 / 导图 / 二创…'
  }
  return '请稍候'
}

function platformLabel(p?: string | null) {
  const map: Record<string, string> = {
    douyin: '抖音',
    bilibili: 'B站',
    youtube: 'YouTube',
    xiaohongshu: '小红书',
    other: '其他'
  }
  return map[p || ''] || p || ''
}

function formatDuration(sec?: number | null) {
  if (sec == null || isNaN(sec)) return '--'
  const s = Math.floor(sec)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const r = s % 60
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`
  return `${m}:${String(r).padStart(2, '0')}`
}

/** 毫秒耗时可读化：1.2s / 1m 05s / 1h 02m */
function formatMs(ms?: number | null) {
  if (ms == null || isNaN(ms) || ms < 0) return '--'
  if (ms < 1000) return `${Math.round(ms)}ms`
  const sec = ms / 1000
  if (sec < 60) return `${sec.toFixed(sec < 10 ? 1 : 0)}s`
  const totalSec = Math.floor(sec)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  if (h > 0) return `${h}h ${m}m ${s}s`
  return `${m}m ${String(s).padStart(2, '0')}s`
}

function hasAnyStepTiming(task: VideoTaskItem) {
  return (
    task.downloadDurationMs != null ||
    task.transcribeDurationMs != null ||
    task.understandDurationMs != null ||
    task.summarizeDurationMs != null ||
    task.totalDurationMs != null
  )
}

function timingRows(task: VideoTaskItem) {
  const rows: { key: string; label: string; ms: number }[] = []
  if (task.downloadDurationMs != null) {
    rows.push({ key: 'download', label: '下载', ms: task.downloadDurationMs })
  }
  if (task.transcribeDurationMs != null) {
    rows.push({ key: 'transcribe', label: '转录', ms: task.transcribeDurationMs })
  }
  if (task.understandDurationMs != null) {
    rows.push({ key: 'understand', label: '画面', ms: task.understandDurationMs })
  }
  if (task.summarizeDurationMs != null) {
    rows.push({ key: 'summarize', label: '总结', ms: task.summarizeDurationMs })
  }
  return rows
}

function timingBarWidth(ms: number, task: VideoTaskItem) {
  const rows = timingRows(task)
  const max = Math.max(...rows.map((r) => r.ms), 1)
  const pct = Math.max(6, Math.round((ms / max) * 100))
  return `${pct}%`
}

function timingTooltip(task: VideoTaskItem) {
  const parts: string[] = []
  if (task.downloadDurationMs != null) parts.push(`下载 ${formatMs(task.downloadDurationMs)}`)
  if (task.transcribeDurationMs != null) parts.push(`转录 ${formatMs(task.transcribeDurationMs)}`)
  if (task.understandDurationMs != null) parts.push(`画面 ${formatMs(task.understandDurationMs)}`)
  if (task.summarizeDurationMs != null) parts.push(`总结 ${formatMs(task.summarizeDurationMs)}`)
  if (task.totalDurationMs != null) parts.push(`合计 ${formatMs(task.totalDurationMs)}`)
  return parts.join(' · ') || ''
}

function stepItemsWithTiming(task: VideoTaskItem) {
  const map: Record<string, number | null | undefined> = {
    DOWNLOADING: task.downloadDurationMs,
    TRANSCRIBING: task.transcribeDurationMs,
    UNDERSTANDING: task.understandDurationMs,
    SUMMARIZING: task.summarizeDurationMs
  }
  return pipelineStepsFor(task).map((s) => {
    const ms = map[s.key]
    if (ms != null) {
      return { title: `${s.name} ${formatMs(ms)}` }
    }
    // 当前进行中的步骤标出「进行中」，避免灰色像未启用
    if (task.status === s.key && s.key !== 'PENDING') {
      return { title: `${s.name} · 进行中` }
    }
    return { title: s.name }
  })
}

function formatSeconds(sec: number) {
  return formatDuration(sec)
}

function shortTime(t?: string | null) {
  if (!t) return ''
  // yyyy-MM-dd HH:mm:ss → MM-dd HH:mm
  const m = t.match(/(\d{2})-(\d{2})\s+(\d{2}:\d{2})/)
  return m ? `${m[1]}-${m[2]} ${m[3]}` : t
}

function renderMarkdown(text: string) {
  return md.render(text || '')
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制到剪贴板')
  } catch {
    message.error('复制失败，请手动选择文本')
  }
}

const filteredSegments = computed(() => {
  const segs = detail.value?.result?.transcription?.segments || []
  const q = transcriptQuery.value.trim().toLowerCase()
  if (!q) return segs
  return segs.filter((s) => (s.text || '').toLowerCase().includes(q))
})

function highlightQuery(text: string) {
  const q = transcriptQuery.value.trim()
  if (!q) return escapeHtml(text)
  const safe = escapeHtml(text)
  const re = new RegExp(`(${escapeRegExp(escapeHtml(q))})`, 'gi')
  return safe.replace(re, '<mark>$1</mark>')
}

function escapeHtml(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function escapeRegExp(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** 解析 mm:ss / hh:mm:ss */
function parseTimestamp(ts?: string): number | null {
  if (!ts) return null
  const parts = ts.trim().split(':').map((x) => parseInt(x, 10))
  if (parts.some((n) => isNaN(n))) return null
  if (parts.length === 2) return parts[0] * 60 + parts[1]
  if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2]
  return null
}

function seekToTimestamp(ts?: string) {
  const sec = parseTimestamp(ts)
  if (sec != null) seekToSeconds(sec)
}

function seekToSeconds(sec: number) {
  activeTab.value = 'overview'
  nextTick(() => {
    const el = videoRef.value
    if (el) {
      el.currentTime = Math.max(0, sec)
      el.play().catch(() => {/* autoplay may block */})
    } else {
      // 视频不可用时切到转录并高亮
      activeTab.value = 'transcript'
    }
    // 高亮最近 segment
    const segs = detail.value?.result?.transcription?.segments || []
    let best: TranscriptionSegment | null = null
    for (const s of segs) {
      if (s.start <= sec) best = s
      else break
    }
    activeSegId.value = best?.id ?? null
  })
}

async function handleSubmit() {
  const url = urlInput.value.trim()
  if (!url) {
    message.warning('请粘贴视频链接')
    return
  }
  const downloadOnly = isDownloadOnly.value
  let parsed: { provider: string; model: string } | null = null
  let omniParsed: { provider: string; model: string } | null = null
  if (!downloadOnly) {
    parsed = parseModelKey(selectedModelKey.value)
    if (!parsed) {
      message.warning('请选择总结用 Chat 模型')
      return
    }
    if (needsOmniModel.value) {
      omniParsed = parseModelKey(selectedOmniKey.value)
      if (!omniParsed) {
        message.warning('混合/仅画面模式请选择视频理解模型')
        return
      }
    }
  }
  submitting.value = true
  try {
    const res = await videoApi.process({
      url,
      options: downloadOnly
        ? {
            understandingMode: 'download_only',
            extractMindMap: false,
            generateRepurposeScript: false
          }
        : {
            language: options.language,
            extractMindMap: options.extractMindMap,
            generateRepurposeScript: options.generateRepurposeScript,
            llmProvider: parsed!.provider,
            llmModel: parsed!.model,
            understandingMode: options.understandingMode,
            omniProvider: omniParsed?.provider,
            omniModel: omniParsed?.model
          }
    })
    message.success(downloadOnly ? '下载任务已提交' : '任务已提交，后台处理中')
    urlInput.value = ''
    await loadTasks(true)
    if (res.data?.taskId) {
      await selectTask(res.data.taskId)
    }
  } catch {
    // 错误已由 request 拦截器提示
  } finally {
    submitting.value = false
  }
}

async function loadTasks(reset = false) {
  if (reset) listPage.value = 0
  listLoading.value = true
  try {
    const res = await videoApi.listTasks(listPage.value, 20)
    const page = res.data
    taskTotal.value = page.total
    if (reset || listPage.value === 0) {
      tasks.value = page.items || []
    } else {
      const ids = new Set(tasks.value.map((t) => t.taskId))
      for (const item of page.items || []) {
        if (!ids.has(item.taskId)) tasks.value.push(item)
      }
    }
  } catch {
    // ignore
  } finally {
    listLoading.value = false
  }
}

async function loadMore() {
  listPage.value += 1
  await loadTasks(false)
}

async function selectTask(taskId: string) {
  if (selectedId.value !== taskId) {
    revokeVideoObjectUrl()
  }
  selectedId.value = taskId
  activeTab.value = 'overview'
  transcriptQuery.value = ''
  // 媒体加载由 detail watch 统一触发，避免与 refreshDetail 内/watch 重复打 media-url
  await refreshDetail()
}

function revokeVideoObjectUrl() {
  if (videoObjectUrl.value?.startsWith('blob:')) {
    URL.revokeObjectURL(videoObjectUrl.value)
  }
  videoObjectUrl.value = ''
  videoBlobTaskId.value = ''
  videoBlobError.value = ''
  videoPlaying.value = false
}

/** 同一 taskId 进行中的 media-url 请求（防并发双发） */
let videoUrlInflight: { taskId: string; promise: Promise<void> } | null = null

/**
 * PR5：优先 R2 预签名直链赋给 &lt;video src&gt;（流量不经后端）；
 * 失败回退同源代理 + access_token（仍支持 Range 边下边播）。
 */
async function loadVideoBlob(taskId: string, force = false) {
  if (!taskId) return
  if (!force && videoBlobTaskId.value === taskId && videoObjectUrl.value) return
  if (!force && videoUrlInflight?.taskId === taskId) {
    await videoUrlInflight.promise
    return
  }

  const run = (async () => {
    videoBlobLoading.value = true
    videoBlobError.value = ''
    videoPlaying.value = false
    try {
      if (videoObjectUrl.value?.startsWith('blob:')) {
        URL.revokeObjectURL(videoObjectUrl.value)
      }
      videoObjectUrl.value = ''
      videoBlobTaskId.value = ''
      let url: string
      try {
        const r = await videoApi.resolvePlayUrl(taskId)
        url = r.url
      } catch {
        url = videoApi.videoStreamUrl(taskId)
      }
      if (selectedId.value !== taskId) return
      videoObjectUrl.value = url
      videoBlobTaskId.value = taskId
      await nextTick()
      const el = videoRef.value
      if (el) {
        el.load()
      }
    } catch (e: any) {
      if (selectedId.value === taskId) {
        videoBlobError.value = e?.message || '视频加载失败'
        message.error(videoBlobError.value)
      }
    } finally {
      if (selectedId.value === taskId) {
        videoBlobLoading.value = false
      }
    }
  })()

  videoUrlInflight = { taskId, promise: run }
  try {
    await run
  } finally {
    if (videoUrlInflight?.taskId === taskId) {
      videoUrlInflight = null
    }
  }
}

async function maybeLoadVideoBlob() {
  const d = detail.value
  if (!d?.taskId || !d.videoAvailable) return
  await loadVideoBlob(d.taskId, false)
}

function onVideoLoaded() {
  // 元数据就绪；竖屏也会完整落在 video-box 内
  videoPlaying.value = false
}

async function playVideo() {
  const el = videoRef.value
  if (!el) return
  try {
    el.muted = false
    el.volume = 1
    await el.play()
    videoPlaying.value = true
  } catch (e: any) {
    // 自动播放策略：尝试静音播放
    try {
      el.muted = true
      await el.play()
      videoPlaying.value = true
      message.info('已静音播放（浏览器限制自动有声播放）')
    } catch (e2: any) {
      message.error(e2?.message || e?.message || '播放失败')
    }
  }
}

/**
 * 下载任务视频到本地（带 JWT；优先复用已加载的 blob URL）。
 */
async function downloadTaskVideo(task?: VideoTaskItem | null) {
  const t = task || detail.value
  if (!t?.taskId || !t.videoAvailable) {
    message.warning('当前任务没有可下载的视频文件')
    return
  }
  if (videoDownloading.value) return
  videoDownloading.value = true
  try {
    // 播放用流地址；另存为需整文件拉取（带 JWT）
    const blob = await videoApi.fetchVideoBlob(t.taskId)
    const objectUrl = URL.createObjectURL(blob)
    const safeTitle = (t.title || `video-${t.taskId}`)
      .replace(/[\\/:*?"<>|]+/g, '_')
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, 80)
    const a = document.createElement('a')
    a.href = objectUrl
    a.download = `${safeTitle || t.taskId}.mp4`
    a.rel = 'noopener'
    document.body.appendChild(a)
    a.click()
    a.remove()
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000)
    message.success('已开始下载视频')
  } catch (e: any) {
    message.error(e?.message || '下载视频失败')
  } finally {
    videoDownloading.value = false
  }
}

function onVideoElementError() {
  videoBlobError.value =
    '视频解码失败（若为抖音 HEVC，请确认后端已生成 video.browser.mp4）。可点重试。'
  videoPlaying.value = false
  if (videoObjectUrl.value?.startsWith('blob:')) {
    URL.revokeObjectURL(videoObjectUrl.value)
  }
  videoObjectUrl.value = ''
  videoBlobTaskId.value = ''
}

async function loadCookieStatus() {
  try {
    const res = await videoApi.cookieStatus('douyin')
    cookieStatus.value = res.data || null
  } catch {
    cookieStatus.value = null
  }
}

function openCookieModal() {
  cookieModalOpen.value = true
  cookieHeaderInput.value = ''
  void loadCookieStatus()
}

async function saveCookie() {
  const header = cookieHeaderInput.value.trim()
  if (!header) {
    message.warning('请粘贴 Cookie 请求头')
    return Promise.reject()
  }
  cookieSaving.value = true
  try {
    const res = await videoApi.uploadCookie({
      cookieHeader: header,
      platform: 'douyin'
    })
    cookieStatus.value = res.data || null
    cookieHeaderInput.value = ''
    message.success('Cookie 已保存')
    cookieModalOpen.value = false
  } catch {
    return Promise.reject()
  } finally {
    cookieSaving.value = false
  }
}

async function handleClearCookie() {
  cookieClearing.value = true
  try {
    await videoApi.clearCookie('douyin')
    cookieStatus.value = null
    cookieHeaderInput.value = ''
    message.success('Cookie 已清除')
    await loadCookieStatus()
  } catch {
    // ignore
  } finally {
    cookieClearing.value = false
  }
}

/** 暂停进行中/排队中任务 */
async function handlePause(task: VideoTaskItem) {
  if (!canPause(task)) {
    message.warning('仅排队中或进行中的任务可暂停')
    return
  }

  const isPending = task.status === 'PENDING'
  Modal.confirm({
    title: isPending ? '暂停排队任务' : '暂停进行中的任务',
    content: isPending
      ? '任务尚未开始执行，将立即取消排队并标记为已暂停。'
      : PAUSE_STEP_BOUNDARY_TIP + '是否继续？',
    okText: isPending ? '立即暂停' : '请求暂停',
    cancelText: '取消',
    onOk: () => doPause(task)
  })
}

async function doPause(task: VideoTaskItem) {
  pausingId.value = task.taskId
  try {
    const res = await videoApi.pauseTask(task.taskId)
    message.success(
      task.status === 'PENDING'
        ? '已暂停排队任务'
        : '已请求暂停：当前步骤结束后中断，并调度其它排队任务'
    )
    const idx = tasks.value.findIndex((t) => t.taskId === task.taskId)
    if (idx >= 0 && res.data) {
      tasks.value[idx] = { ...tasks.value[idx], ...res.data }
    }
    if (selectedId.value === task.taskId) {
      await refreshDetail()
    }
    await loadTasks(true)
  } catch {
    // ignore
  } finally {
    pausingId.value = ''
  }
}

/** 失败 / 暂停 / 成功 可重试（可改 LLM） */
function canRetry(task?: VideoTaskItem | null) {
  if (!task?.status) return false
  return ['FAILED', 'PAUSED', 'SUCCESS'].includes(String(task.status).toUpperCase())
}

/** 打开重试弹窗（可改总结 LLM / 理解模式 / 视频理解模型） */
function openRetryModal(task: VideoTaskItem) {
  if (!canRetry(task)) {
    message.warning('仅失败、已暂停或已成功的任务可重试')
    return
  }
  retryTarget.value = task
  if (task.llmProvider && task.llmModel) {
    retryModelKey.value = `${task.llmProvider}::${task.llmModel}`
  } else {
    retryModelKey.value = selectedModelKey.value || ''
  }
  retryUnderstandingMode.value =
    (task.understandingMode as string) || options.understandingMode || 'audio_only'
  if (task.omniProvider && task.omniModel) {
    retryOmniKey.value = `${task.omniProvider}::${task.omniModel}`
  } else {
    retryOmniKey.value = selectedOmniKey.value || ''
  }
  retryTestOk.value = false
  retryTestFail.value = false
  retryModalOpen.value = true
  void loadModels()
  if (retryUnderstandingMode.value === 'hybrid' || retryUnderstandingMode.value === 'omni_only') {
    void loadOmniModels()
  }
}

async function testRetryModel() {
  const parsed = parseModelKey(retryModelKey.value)
  if (!parsed) {
    message.warning('请先选择模型')
    return
  }
  retryTesting.value = true
  retryTestOk.value = false
  retryTestFail.value = false
  try {
    const res = await videoApi.testModel({
      provider: parsed.provider,
      model: parsed.model
    })
    if (res.data?.available) {
      retryTestOk.value = true
      message.success('模型可用')
    } else {
      retryTestFail.value = true
      message.error(res.data?.errorMessage || '模型不可用')
    }
  } catch (e: any) {
    retryTestFail.value = true
    const isTimeout =
      e?.code === 'ECONNABORTED' ||
      String(e?.message || '').toLowerCase().includes('timeout')
    message.error(isTimeout ? '超过 10 秒无响应，判定不可用' : e?.message || '测试失败')
  } finally {
    retryTesting.value = false
  }
}

/**
 * 确认重试：可带新总结 LLM / 理解模式 / 视频理解模型；download_only 不要求 LLM。
 */
async function submitRetry() {
  const task = retryTarget.value
  if (!task) return Promise.reject()
  const downloadOnly = isRetryDownloadOnly.value
  let parsed: { provider: string; model: string } | null = null
  let omniParsed: { provider: string; model: string } | null = null
  if (!downloadOnly) {
    parsed = parseModelKey(retryModelKey.value)
    if (!parsed) {
      message.warning('请选择总结用 Chat 模型')
      return Promise.reject()
    }
    if (retryNeedsOmni.value) {
      omniParsed = parseModelKey(retryOmniKey.value)
      if (!omniParsed) {
        message.warning('画面理解模式请选择视频理解模型')
        return Promise.reject()
      }
    }
  }
  retryingId.value = task.taskId
  try {
    const body = downloadOnly
      ? { understandingMode: 'download_only' }
      : {
          llmProvider: parsed!.provider,
          llmModel: parsed!.model,
          understandingMode: retryUnderstandingMode.value,
          omniProvider: omniParsed?.provider,
          omniModel: omniParsed?.model
        }
    const res = await videoApi.retryTask(task.taskId, body)
    message.success(
      task.status === 'SUCCESS' ? '已重新排队，开始重新提取' : '已重新排队，开始重试'
    )
    retryModalOpen.value = false
    const idx = tasks.value.findIndex((t) => t.taskId === task.taskId)
    if (idx >= 0 && res.data) {
      tasks.value[idx] = { ...tasks.value[idx], ...res.data, result: undefined }
    }
    if (selectedId.value === task.taskId) {
      revokeVideoObjectUrl()
      detail.value = {
        ...(res.data || task),
        result: undefined,
        status: res.data?.status || 'PENDING',
        currentStep: res.data?.currentStep || '重试排队中',
        errorMessage: null,
        downloadDurationMs: null,
        transcribeDurationMs: null,
        summarizeDurationMs: null,
        totalDurationMs: null,
        finishedAt: null,
        llmProvider: downloadOnly ? null : parsed!.provider,
        llmModel: downloadOnly ? null : parsed!.model,
        understandingMode: retryUnderstandingMode.value,
        omniProvider: downloadOnly ? null : omniParsed?.provider ?? null,
        omniModel: downloadOnly ? null : omniParsed?.model ?? null
      }
      await refreshDetail()
    }
    await loadTasks(true)
  } catch {
    return Promise.reject()
  } finally {
    retryingId.value = ''
  }
}

/**
 * 删除确认：清理数据库记录 + 本地视频/音频/JSON。
 */
function confirmDelete(task: VideoTaskItem) {
  const title = task.title || '未命名视频'
  const runningHint = isRunning(task.status)
    ? '该任务仍在处理中，删除后将无法查看进度与结果。'
    : '此操作不可恢复。'

  Modal.confirm({
    title: '确认删除该任务？',
    icon: createVNode(ExclamationCircleOutlined),
    content: createVNode('div', { class: 'video-delete-confirm' }, [
      createVNode('p', { style: 'margin:0 0 8px;color:#1f2937;font-weight:500' }, title),
      createVNode(
        'p',
        { style: 'margin:0;color:#6b7280;font-size:13px;line-height:1.6' },
        `${runningHint}将同时删除：数据库记录、本地视频/音频、转录与摘要文件。`
      )
    ]),
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    centered: true,
    async onOk() {
      await doDelete(task.taskId)
    }
  })
}

async function doDelete(taskId: string) {
  deletingId.value = taskId
  try {
    await videoApi.deleteTask(taskId)
    message.success('任务及相关文件已删除')
    tasks.value = tasks.value.filter((t) => t.taskId !== taskId)
    taskTotal.value = Math.max(0, taskTotal.value - 1)
    if (selectedId.value === taskId) {
      revokeVideoObjectUrl()
      selectedId.value = ''
      detail.value = null
    }
  } catch {
    // 错误已由 request 拦截器提示
  } finally {
    deletingId.value = ''
  }
}

async function refreshDetail() {
  if (!selectedId.value) return
  detailLoading.value = true
  try {
    const res = await videoApi.getTask(selectedId.value)
    detail.value = res.data
    // 同步列表中的状态与耗时
    const idx = tasks.value.findIndex((t) => t.taskId === selectedId.value)
    if (idx >= 0 && res.data) {
      tasks.value[idx] = {
        ...tasks.value[idx],
        status: res.data.status,
        title: res.data.title,
        currentStep: res.data.currentStep,
        durationSeconds: res.data.durationSeconds,
        videoAvailable: res.data.videoAvailable,
        errorMessage: res.data.errorMessage,
        finishedAt: res.data.finishedAt,
        startedAt: res.data.startedAt,
        downloadDurationMs: res.data.downloadDurationMs,
        transcribeDurationMs: res.data.transcribeDurationMs,
        summarizeDurationMs: res.data.summarizeDurationMs,
        totalDurationMs: res.data.totalDurationMs,
        understandingMode: res.data.understandingMode
      }
    }
    // 不在这里 load 视频：detail 赋值会触发下方 watch，避免 media-url 打两遍
  } catch {
    // ignore
  } finally {
    detailLoading.value = false
  }
}

/** SSE 已连接时不轮询；断线则智能轮询兜底 */
const sseConnected = ref(false)
/** 是否正在发起/重连 SSE（尚未 onOpen） */
const sseConnecting = ref(false)
/** 智能轮询是否在跑（用于角标，需 ref 才能刷新 UI） */
const pollingActive = ref(false)
/** 最近一次有效业务事件（不含 ping） */
const lastSseEventAt = ref<number | null>(null)
const lastSseEventType = ref('')
/** 角标「xs前」每秒刷新 */
const liveTick = ref(0)
let liveHintTimer: ReturnType<typeof setInterval> | null = null
let sseHandle: { close: () => void } | null = null
let pollIntervalMs = 3000
let visibilityHandler: (() => void) | null = null

/** idle | connecting | sse | poll */
const liveChannel = computed(() => {
  if (sseConnected.value) return 'sse'
  if (pollingActive.value) return 'poll'
  if (sseConnecting.value) return 'connecting'
  return 'idle'
})

const liveChannelLabel = computed(() => {
  switch (liveChannel.value) {
    case 'sse':
      return '实时 · SSE'
    case 'poll':
      return '实时 · 轮询'
    case 'connecting':
      return '连接中…'
    default:
      return '待机'
  }
})

const lastEventHint = computed(() => {
  void liveTick.value
  if (!lastSseEventAt.value || !lastSseEventType.value) return ''
  const sec = Math.max(0, Math.round((Date.now() - lastSseEventAt.value) / 1000))
  const when = sec < 5 ? '刚刚' : sec < 60 ? `${sec}s前` : `${Math.floor(sec / 60)}m前`
  return `${lastSseEventType.value} · ${when}`
})

const liveChannelTip = computed(() => {
  const base =
    '状态通道：SSE=服务端推送长连接（Network 里叫 events，类型常是 fetch，不是 EventStream）；轮询=断线时兜底拉 tasks；待机=无活跃任务且未连上推送。'
  if (liveChannel.value === 'sse') {
    return `${base}\n当前：SSE 已连接，任务状态由推送更新。Edge 请在 Network → 筛选「全部」搜 events，看一条一直 Pending 的请求。`
  }
  if (liveChannel.value === 'poll') {
    return `${base}\n当前：SSE 未连通，正在智能轮询 /v1/video/tasks。`
  }
  if (liveChannel.value === 'connecting') {
    return `${base}\n当前：正在连接 /api/v1/video/events …`
  }
  return `${base}\n当前：待机（无进行中任务时也可能不轮询）。`
})

function startLiveHintClock() {
  if (liveHintTimer) return
  liveHintTimer = setInterval(() => {
    liveTick.value++
  }, 1000)
}
function stopLiveHintClock() {
  if (liveHintTimer) {
    clearInterval(liveHintTimer)
    liveHintTimer = null
  }
}

function mergeTaskPatch(taskId: string, patch: Partial<VideoTaskItem>) {
  const idx = tasks.value.findIndex((t) => t.taskId === taskId)
  if (idx >= 0) {
    tasks.value[idx] = { ...tasks.value[idx], ...patch }
  } else if (patch.status || patch.url) {
    // 新任务（created）插到顶部
    tasks.value.unshift({
      taskId,
      status: patch.status || 'PENDING',
      url: patch.url || '',
      ...patch
    } as VideoTaskItem)
    taskTotal.value += 1
  }
  if (selectedId.value === taskId && detail.value) {
    detail.value = { ...detail.value, ...patch }
  } else if (selectedId.value === taskId && !detail.value && patch) {
    detail.value = { taskId, status: patch.status || 'PENDING', url: patch.url || '', ...patch } as VideoTaskItem
  }
}

async function handleSseEvent(ev: VideoTaskSseEvent) {
  if (ev.type === 'ping') return
  if (ev.type === 'connected') {
    sseConnected.value = true
    sseConnecting.value = false
    lastSseEventType.value = 'connected'
    lastSseEventAt.value = Date.now()
    return
  }

  lastSseEventType.value = String(ev.type || 'event')
  lastSseEventAt.value = Date.now()

  if (ev.type === 'task.deleted') {
    const id = String(ev.taskId || ev.data?.taskId || '')
    if (!id) return
    tasks.value = tasks.value.filter((t) => t.taskId !== id)
    taskTotal.value = Math.max(0, taskTotal.value - 1)
    if (selectedId.value === id) {
      selectedId.value = ''
      detail.value = null
    }
    return
  }

  const data = (ev.data || {}) as Partial<VideoTaskItem> & { taskId?: string }
  const taskId = String(ev.taskId || data.taskId || '')
  if (!taskId) return

  const patch: Partial<VideoTaskItem> = {
    taskId,
    status: data.status as VideoTaskItem['status'],
    url: data.url as string | undefined,
    title: data.title as string | null | undefined,
    platform: data.platform as string | null | undefined,
    llmProvider: data.llmProvider as string | null | undefined,
    llmModel: data.llmModel as string | null | undefined,
    currentStep: data.currentStep as string | null | undefined,
    errorMessage: data.errorMessage as string | null | undefined,
    durationSeconds: data.durationSeconds as number | null | undefined,
    videoAvailable: data.videoAvailable as boolean | null | undefined,
    createdAt: data.createdAt as string | null | undefined,
    startedAt: data.startedAt as string | null | undefined,
    finishedAt: data.finishedAt as string | null | undefined,
    downloadDurationMs: data.downloadDurationMs as number | null | undefined,
    transcribeDurationMs: data.transcribeDurationMs as number | null | undefined,
    summarizeDurationMs: data.summarizeDurationMs as number | null | undefined,
    totalDurationMs: data.totalDurationMs as number | null | undefined
  }
  // 去掉 undefined，避免覆盖已有字段
  Object.keys(patch).forEach((k) => {
    if ((patch as any)[k] === undefined) delete (patch as any)[k]
  })

  mergeTaskPatch(taskId, patch)

  // 终态成功：拉完整 result（含摘要/转录）
  if (selectedId.value === taskId && data.status === 'SUCCESS') {
    await refreshDetail()
  }
}

function startSse() {
  stopSse()
  sseConnecting.value = true
  sseHandle = connectVideoTaskEvents({
    onOpen: () => {
      // HTTP 流已建立；真正业务以 connected 事件为准，这里先标为已连
      sseConnected.value = true
      sseConnecting.value = false
      stopPolling()
    },
    onEvent: (ev) => {
      void handleSseEvent(ev)
    },
    onError: () => {
      sseConnected.value = false
      sseConnecting.value = false
      ensurePolling()
    },
    onClose: () => {
      sseConnected.value = false
      sseConnecting.value = false
    }
  })
}

function stopSse() {
  sseHandle?.close()
  sseHandle = null
  sseConnected.value = false
  sseConnecting.value = false
}

function computePollInterval(): number {
  if (typeof document !== 'undefined' && document.hidden) return 15000
  const active = tasks.value.filter((t) => needsPoll(t))
  if (!active.length) return 8000
  if (active.some((t) => t.status === 'SUMMARIZING' || isPauseDraining(t))) return 2000
  if (active.some((t) => t.status === 'DOWNLOADING' || t.status === 'TRANSCRIBING')) return 3000
  return 5000 // PENDING 等
}

function ensurePolling() {
  if (sseConnected.value) {
    stopPolling()
    return
  }
  const hasActive =
    tasks.value.some((t) => needsPoll(t)) || !!(detail.value && needsPoll(detail.value))
  if (!hasActive) {
    stopPolling()
    return
  }
  if (!pollTimer) {
    startPolling()
  }
}

function startPolling() {
  stopPolling()
  pollingActive.value = true
  const tick = async () => {
    if (sseConnected.value) {
      stopPolling()
      return
    }
    const hasActive =
      tasks.value.some((t) => needsPoll(t)) || !!(detail.value && needsPoll(detail.value))
    if (!hasActive) {
      stopPolling()
      return
    }
    try {
      const res = await videoApi.listTasks(0, 20)
      const map = new Map((res.data.items || []).map((t) => [t.taskId, t]))
      tasks.value = tasks.value.map((t) => map.get(t.taskId) || t)
      for (const item of res.data.items || []) {
        if (!tasks.value.find((t) => t.taskId === item.taskId)) {
          tasks.value.unshift(item)
        }
      }
      lastSseEventType.value = 'poll.tasks'
      lastSseEventAt.value = Date.now()
    } catch {
      /* ignore */
    }
    if (detail.value && needsPoll(detail.value)) {
      await refreshDetail()
    }
    pollIntervalMs = computePollInterval()
    pollTimer = setTimeout(() => {
      pollTimer = null
      void tick()
    }, pollIntervalMs)
  }
  void tick()
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  pollingActive.value = false
}

watch(selectedId, (id, prev) => {
  activeSegId.value = null
  if (id !== prev) {
    revokeVideoObjectUrl()
  }
})

// 详情就绪 / 视频可用时加载播放地址（唯一自动触发点，避免 media-url 重复）
watch(
  () => [detail.value?.taskId, detail.value?.videoAvailable, detail.value?.status] as const,
  ([taskId, available], prev) => {
    if (!taskId || !available) return
    // 同任务且仅其它字段抖动时，loadVideoBlob 内部会 short-circuit
    const sameTask = prev && prev[0] === taskId && prev[1] === available
    if (sameTask && videoBlobTaskId.value === taskId && videoObjectUrl.value) return
    void maybeLoadVideoBlob()
  }
)

// 任务列表变化时：SSE 断线则维护轮询
watch(
  () => tasks.value.map((t) => `${t.taskId}:${t.status}:${t.currentStep}`).join('|'),
  () => ensurePolling()
)

onMounted(async () => {
  await Promise.all([loadModels(), loadOmniModels(), loadTasks(true), loadCookieStatus()])
  startSse()
  ensurePolling()
  startLiveHintClock()
  visibilityHandler = () => {
    if (!document.hidden && !sseConnected.value) {
      ensurePolling()
    }
  }
  document.addEventListener('visibilitychange', visibilityHandler)
})

onUnmounted(() => {
  stopPolling()
  stopSse()
  stopLiveHintClock()
  revokeVideoObjectUrl()
  if (visibilityHandler) {
    document.removeEventListener('visibilitychange', visibilityHandler)
    visibilityHandler = null
  }
})
</script>

<style lang="scss" scoped>
.video-extract-page {
  max-width: 1400px;
  margin: 0 auto;
  padding-bottom: 12px;
}

.submit-hero {
  margin-bottom: 18px;
  padding: 24px 26px 20px;
  border-radius: 16px;
  background: var(--surface-1);
  border: 1px solid var(--border-color);
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.03),
    0 8px 24px rgba(15, 23, 42, 0.04);
  position: relative;
  overflow: hidden;

  &::after,
  &::before {
    display: none;
  }

  .hero-text {
    margin-bottom: 16px;
  }

  .hero-title-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px 14px;
    margin-bottom: 4px;

    .page-title {
      margin-bottom: 0;
    }
  }

  .live-badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    max-width: 100%;
    padding: 3px 10px 3px 8px;
    border-radius: 999px;
    font-size: 12px;
    line-height: 1.4;
    border: 1px solid transparent;
    cursor: help;
    user-select: none;
    white-space: nowrap;

    .live-dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      flex-shrink: 0;
      background: #9ca3af;
    }

    .live-label {
      font-weight: 600;
      color: var(--text-primary);
    }

    .live-hint {
      color: var(--text-muted);
      font-weight: 400;
      max-width: 160px;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    &.sse {
      background: #ecfdf5;
      border-color: #a7f3d0;
      .live-dot {
        background: #10b981;
        box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.25);
        animation: live-pulse 1.6s ease-in-out infinite;
      }
      .live-label {
        color: #047857;
      }
    }

    &.poll {
      background: #fff7ed;
      border-color: #fed7aa;
      .live-dot {
        background: #f59e0b;
        animation: live-pulse 1.6s ease-in-out infinite;
      }
      .live-label {
        color: #c2410c;
      }
    }

    &.connecting {
      background: #eff6ff;
      border-color: #bfdbfe;
      .live-dot {
        background: #3b82f6;
        animation: live-pulse 0.9s ease-in-out infinite;
      }
      .live-label {
        color: #1d4ed8;
      }
    }

    &.idle {
      background: var(--surface-hover);
      border-color: var(--border-color);
      .live-dot {
        background: #9ca3af;
      }
      .live-label {
        color: var(--text-secondary);
      }
    }
  }

  @keyframes live-pulse {
    0%,
    100% {
      opacity: 1;
      transform: scale(1);
    }
    50% {
      opacity: 0.55;
      transform: scale(0.85);
    }
  }

  .submit-row {
    display: flex;
    gap: 12px;
    align-items: center;

    .url-input {
      flex: 1;

      :deep(.ant-input-affix-wrapper),
      :deep(input) {
        border-radius: 14px;
        min-height: 46px;
      }
    }

    .submit-btn {
      height: 42px;
      border-radius: 12px;
      padding: 0 20px;
      font-weight: 560;
      border: none;
      background: #1f2937;
      color: #fff;
      box-shadow: none;

      &:hover:not(:disabled) {
        background: #111827;
        filter: none;
        transform: none;
      }
    }

    .input-prefix-icon {
      color: var(--text-muted);
    }
  }

  .model-row {
    position: relative;
    z-index: 1;
    margin-top: 14px;
    padding: 14px 16px;
    background: var(--surface-2);
    border: 1px solid var(--border-color);
    border-radius: 12px;
    box-shadow: none;

    /* 视频理解模型：与上方总结 LLM 拉开间距，避免贴在一起 */
    &.model-row-omni {
      margin-top: 22px;
      padding-top: 18px;
      border-color: rgba(114, 46, 209, 0.18);
      background: linear-gradient(
        180deg,
        rgba(114, 46, 209, 0.05) 0%,
        var(--surface-2) 48%
      );
    }

    .model-pick {
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;

      .opt-label {
        color: var(--text-secondary);
        font-size: 13px;
        flex-shrink: 0;
      }

      .model-select {
        flex: 1;
        min-width: 260px;
        max-width: 520px;
      }

      .test-btn,
      .manage-btn {
        border-radius: 10px;
      }
    }

    .model-status {
      margin-top: 10px;
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
      font-size: 12px;

      &.muted {
        color: var(--text-muted);
      }

      .test-err {
        color: var(--danger-color);
        max-width: 100%;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }
  }

  .mode-section {
    margin-top: 18px;
    padding: 14px 14px 12px;
    border-radius: 14px;
    border: 1px solid var(--border-color);
    background: linear-gradient(180deg, rgba(22, 119, 255, 0.04) 0%, rgba(255, 255, 255, 0.02) 100%);
  }

  .mode-section-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
    margin-bottom: 12px;
  }

  .mode-section-title {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .mode-section-kicker {
    font-size: 14px;
    font-weight: 700;
    color: var(--text-primary);
    letter-spacing: 0.02em;
  }

  .mode-section-sub {
    font-size: 12px;
    color: var(--text-muted);
  }

  .mode-section-meta {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  .mode-active-tag {
    border-radius: 999px;
  }

  .mode-card-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 10px;

    @media (max-width: 1100px) {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    @media (max-width: 560px) {
      grid-template-columns: 1fr;
    }
  }

  .mode-card {
    appearance: none;
    -webkit-appearance: none;
    text-align: left;
    cursor: pointer;
    border: 1px solid var(--border-color);
    border-radius: 12px;
    background: var(--surface-1, #fff);
    padding: 12px 12px 10px;
    transition:
      border-color 0.15s ease,
      box-shadow 0.15s ease,
      transform 0.15s ease,
      background 0.15s ease;
    min-height: 148px;
    display: flex;
    flex-direction: column;
    gap: 6px;
    color: inherit;
    font: inherit;

    &:hover {
      border-color: rgba(22, 119, 255, 0.45);
      transform: translateY(-1px);
      box-shadow: 0 6px 16px rgba(15, 23, 42, 0.06);
    }

    &.active {
      border-color: #1677ff;
      background: linear-gradient(165deg, rgba(22, 119, 255, 0.1) 0%, rgba(22, 119, 255, 0.03) 100%);
      box-shadow:
        0 0 0 1px rgba(22, 119, 255, 0.2),
        0 8px 18px rgba(22, 119, 255, 0.12);
    }

    &.tone-download.active {
      border-color: #13c2c2;
      box-shadow:
        0 0 0 1px rgba(19, 194, 194, 0.25),
        0 8px 18px rgba(19, 194, 194, 0.12);
      background: linear-gradient(165deg, rgba(19, 194, 194, 0.12) 0%, rgba(19, 194, 194, 0.03) 100%);
    }

    &.tone-hybrid.active,
    &.tone-omni.active {
      border-color: #722ed1;
      box-shadow:
        0 0 0 1px rgba(114, 46, 209, 0.22),
        0 8px 18px rgba(114, 46, 209, 0.12);
      background: linear-gradient(165deg, rgba(114, 46, 209, 0.1) 0%, rgba(114, 46, 209, 0.03) 100%);
    }
  }

  .mode-card-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .mode-card-icon {
    font-size: 18px;
    line-height: 1;
  }

  .mode-card-check {
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background: #1677ff;
    color: #fff;
    font-size: 12px;
    font-weight: 700;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }

  .mode-card.tone-download.active .mode-card-check {
    background: #13c2c2;
  }

  .mode-card.tone-hybrid.active .mode-card-check,
  .mode-card.tone-omni.active .mode-card-check {
    background: #722ed1;
  }

  .mode-card-name {
    font-size: 14px;
    font-weight: 700;
    color: var(--text-primary);
  }

  .mode-card-desc {
    font-size: 12px;
    line-height: 1.45;
    color: var(--text-secondary);
    flex: 1;
  }

  .mode-card-flow {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 2px 0;
    margin-top: 2px;
  }

  .flow-chip {
    font-size: 11px;
    color: var(--text-muted);
    font-weight: 500;
  }

  .flow-arrow {
    margin: 0 3px;
    color: rgba(0, 0, 0, 0.25);
  }

  .mode-card-need {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin-top: 2px;
  }

  .need-chip {
    font-size: 11px;
    padding: 1px 7px;
    border-radius: 999px;
    background: rgba(0, 0, 0, 0.04);
    color: var(--text-secondary);
    border: 1px solid rgba(0, 0, 0, 0.06);
  }

  .mode-card.active .need-chip {
    background: rgba(22, 119, 255, 0.08);
    border-color: rgba(22, 119, 255, 0.18);
    color: #0958d9;
  }

  .mode-section-hint {
    margin: 10px 2px 0;
    font-size: 12px;
    color: var(--text-secondary);
    line-height: 1.5;
  }

  .options-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: 14px;
    flex-wrap: wrap;
    gap: 10px;

    .opt-label {
      color: var(--text-secondary);
      font-size: 13px;
    }

    &.options-row-secondary {
      margin-top: 18px;
      padding-top: 14px;
      border-top: 1px dashed var(--border-color);
    }
  }

  .platform-hints {
    display: flex;
    gap: 6px;
    align-items: center;
    flex-wrap: wrap;

    .hint-chip {
      font-size: 12px;
      color: var(--primary-color);
      background: rgba(22, 119, 255, 0.08);
      border-radius: 999px;
      padding: 2px 10px;
    }
  }
}

.workspace {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: 18px;
  min-height: calc(100vh - 280px);
}

.task-panel {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
  max-height: calc(100vh - 220px);
  border-radius: 16px !important;
  border: 1px solid rgba(226, 232, 240, 0.9) !important;
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.03),
    0 8px 24px rgba(15, 23, 42, 0.04) !important;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(255, 255, 255, 0.9)) !important;
  backdrop-filter: blur(10px);

  .panel-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14px 16px;
    border-bottom: 1px solid var(--border-color);

    .panel-title {
      font-weight: 600;
      font-size: 14px;
    }
  }

  .task-list {
    flex: 1;
    overflow-y: auto;
    padding: 8px;
  }

  .list-footer {
    text-align: center;
    padding: 4px 0 10px;
    border-top: 1px solid var(--border-color);
  }
}

.task-item {
  padding: 12px 14px;
  border-radius: 14px;
  cursor: pointer;
  transition: all 0.16s ease;
  border: 1px solid transparent;
  margin-bottom: 6px;
  background: rgba(248, 250, 252, 0.65);

  &:hover {
    background: var(--surface-3);
    border-color: var(--border-color);
  }

  &.active {
    background: var(--surface-3);
    border-color: var(--border-strong);
    box-shadow: none;
  }

  .task-item-top {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 6px;

    .status-tag {
      margin: 0;
      font-size: 12px;
    }

    .task-item-actions {
      display: flex;
      align-items: center;
      gap: 2px;
    }

    .task-time {
      font-size: 11px;
      color: var(--text-muted);
    }

    .task-del-btn,
    .task-retry-btn,
    .task-pause-btn {
      opacity: 0;
      transition: opacity 0.15s;
      width: 24px;
      height: 24px;
      min-width: 24px;
      padding: 0;
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }

    .task-retry-btn {
      color: var(--primary-color);
    }

    .task-pause-btn {
      color: var(--warning-color, #f59e0b);
    }
  }

  &:hover .task-del-btn,
  &:hover .task-retry-btn,
  &:hover .task-pause-btn,
  &.active .task-del-btn,
  &.active .task-retry-btn,
  &.active .task-pause-btn {
    opacity: 1;
  }

  .task-title {
    font-size: 13px;
    font-weight: 500;
    color: var(--text-primary);
    line-height: 1.4;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    margin-bottom: 6px;
  }

  .task-meta {
    display: flex;
    gap: 8px;
    align-items: center;
    font-size: 12px;
    color: var(--text-secondary);

    .task-step {
      color: var(--primary-color);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }
}

.platform-badge {
  display: inline-block;
  font-size: 11px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--surface-3);
  color: var(--text-secondary);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  &.llm-badge {
    background: #eef2ff;
    color: var(--primary-color);
  }
}

.detail-panel {
  min-height: 520px;
  max-height: calc(100vh - 220px);
  overflow-y: auto;
  padding: 22px 24px;
  border-radius: 16px !important;
  border: 1px solid rgba(226, 232, 240, 0.9) !important;
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.03),
    0 8px 24px rgba(15, 23, 42, 0.04) !important;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(255, 255, 255, 0.9)) !important;
  backdrop-filter: blur(10px);
}

.empty-detail {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 24px 40px;
  text-align: center;
  color: var(--text-secondary);
  gap: 0;

  /* EmptyState 组件与下方流水线方框拉开距离 */
  :deep(.empty-state),
  > .empty-state,
  > *:first-child {
    margin-bottom: 0;
  }

  .empty-visual {
    width: 72px;
    height: 72px;
    border-radius: 20px;
    background: linear-gradient(135deg, #e6f4ff, #f0f5ff);
    color: var(--primary-color);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 32px;
    margin-bottom: 16px;
  }

  h3 {
    color: var(--text-primary);
    margin-bottom: 8px;
  }

  p {
    max-width: 360px;
    margin-bottom: 0;
  }

  .pipeline-preview {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
    justify-content: center;
    margin-top: 36px;
    padding-top: 28px;
    border-top: 1px dashed var(--border-color);
    width: 100%;
    max-width: 520px;

    .pipe-step {
      width: 72px;
      padding: 12px 8px;
      background: var(--surface-hover);
      border: 1px solid var(--border-color);
      border-radius: 12px;

      .pipe-icon {
        font-size: 16px;
        margin-bottom: 4px;
        color: var(--primary-color);
      }

      .pipe-name {
        font-size: 12px;
        color: var(--text-secondary);
      }
    }
  }
}

.detail-loading {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 320px;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;

  .detail-title {
    margin: 8px 0 6px;
    font-size: 18px;
    font-weight: 600;
    color: var(--text-primary);
    line-height: 1.35;
  }

  .detail-sub {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    font-size: 12px;
    color: var(--text-secondary);
    align-items: center;

    .source-link {
      color: var(--primary-color);
    }
  }
}

.pause-policy-hint {
  margin-top: 12px;
  border-radius: 10px;
}

.progress-block.pause-draining {
  .progress-hint {
    margin-top: 10px;
  }
}

.progress-block {
  background: var(--surface-hover);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 16px 18px;
  margin-bottom: 16px;

  .progress-hint {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 14px;
    font-size: 13px;
    color: var(--text-secondary);
  }

  .timing-strip {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
  }
}

.timing-chip {
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--surface-1);
  border: 1px solid var(--border-color);
  border-radius: 999px;
  padding: 2px 10px;

  b {
    color: var(--primary-color);
    font-weight: 600;
    margin-left: 2px;
  }

  &.timing-chip-understand {
    border-color: #93c5fd;
    background: #eff6ff;
    color: #1d4ed8;

    b {
      color: #1e40af;
    }

    .timing-running {
      color: #2563eb;
      font-weight: 600;
      animation: pulse-soft 1.2s ease-in-out infinite;
    }
  }
}

@keyframes pulse-soft {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.55;
  }
}

.timing-panel {
  background: linear-gradient(135deg, #f8fafc 0%, #eef6ff 100%);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 14px 16px;
  margin-bottom: 16px;

  .timing-panel-title {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 13px;
    font-weight: 600;
    color: var(--text-primary);
    margin-bottom: 12px;

    .timing-total {
      font-weight: 600;
      color: var(--primary-color);
      font-size: 13px;
    }
  }

  .timing-bars {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .timing-bar-row {
    .timing-bar-label {
      display: flex;
      justify-content: space-between;
      font-size: 12px;
      color: var(--text-secondary);
      margin-bottom: 4px;

      .timing-bar-ms {
        font-variant-numeric: tabular-nums;
        color: var(--text-primary);
        font-weight: 500;
      }
    }

    .timing-bar-track {
      height: 8px;
      background: #e5e7eb;
      border-radius: 999px;
      overflow: hidden;
    }

    .timing-bar-fill {
      height: 100%;
      border-radius: 999px;
      transition: width 0.35s ease;

      &.download {
        background: linear-gradient(90deg, #60a5fa, #2563eb);
      }

      &.transcribe {
        background: #1f2937;
      }

      &.understand {
        background: linear-gradient(90deg, #38bdf8, #2563eb);
      }

      &.summarize {
        background: linear-gradient(90deg, #34d399, #059669);
      }
    }
  }

  .timing-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    margin-top: 12px;
    font-size: 11px;
    color: var(--text-muted);
  }
}

.task-cost {
  color: var(--primary-color);
  font-variant-numeric: tabular-nums;
}

.fail-alert {
  margin-bottom: 16px;

  .fail-desc {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-top: 4px;
  }
}

.retry-hint {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 12px;
}

.retry-url {
  margin-bottom: 12px;

  .muted {
    font-size: 12px;
    color: var(--text-muted);
  }

  .url-text {
    font-size: 12px;
    word-break: break-all;
    color: var(--text-primary);
    margin-top: 4px;
  }
}

.retry-model-row {
  margin-bottom: 10px;

  .opt-label {
    font-size: 13px;
    color: var(--text-secondary);
  }
}

.retry-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.waiting-card {
  text-align: center;
  padding: 40px 16px;
  color: var(--text-secondary);

  .waiting-illustration {
    font-size: 36px;
    margin-bottom: 12px;
  }

  .muted {
    font-size: 12px;
    color: var(--text-muted);
    margin-top: 6px;
  }
}

.result-tabs {
  :deep(.ant-tabs-nav) {
    margin-bottom: 16px;
  }
}

.overview-grid {
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: 16px;

  @media (max-width: 1100px) {
    grid-template-columns: 1fr;
  }
}

.video-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.video-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;

  .video-toolbar-hint {
    font-size: 12px;
  }
}

.video-box {
  border-radius: 12px;
  overflow: hidden;
  background: #0f172a;
  aspect-ratio: 16 / 9;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;

  /* 绝对铺满：竖屏视频不会把 controls 撑到 overflow 裁切区外 */
  .video-player {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    background: #000;
    object-fit: contain;
    z-index: 1;
    pointer-events: auto;
  }

  .video-loading-mask {
    position: absolute;
    inset: 0;
    z-index: 3;
    display: flex;
    align-items: center;
    justify-content: center;
    background: rgba(15, 23, 42, 0.72);
    color: #fff;
  }

  .video-play-btn {
    position: absolute;
    left: 50%;
    top: 50%;
    transform: translate(-50%, -50%);
    z-index: 2;
    border: none;
    border-radius: 999px;
    padding: 12px 22px;
    font-size: 15px;
    font-weight: 600;
    cursor: pointer;
    color: #fff;
    background: rgba(22, 119, 255, 0.92);
    box-shadow: 0 6px 20px rgba(0, 0, 0, 0.35);
    pointer-events: auto;

    &:hover {
      background: rgba(22, 119, 255, 1);
    }
  }

  .video-placeholder-inner {
    position: relative;
    z-index: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8px;
    color: var(--text-muted);
    font-size: 13px;
    padding: 16px;

    .anticon {
      font-size: 28px;
    }
  }

  &.video-placeholder {
    flex-direction: column;
    gap: 8px;
    color: var(--text-muted);
    font-size: 13px;

    .anticon {
      font-size: 28px;
    }
  }
}

.cookie-btn {
  border-radius: 999px;
}

.cookie-hint {
  margin: 0 0 10px;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.cookie-status-box {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.overview-side {
  display: flex;
  flex-direction: column;
  gap: 10px;

  .mini-card {
    background: var(--surface-hover);
    border: 1px solid var(--border-color);
    border-radius: 10px;
    padding: 12px 14px;
    display: flex;
    justify-content: space-between;
    align-items: center;

    .mini-label {
      font-size: 13px;
      color: var(--text-secondary);
    }

    .mini-value {
      font-size: 20px;
      font-weight: 600;
      color: var(--primary-color);
    }
  }

  .quick-keypoints {
    flex: 1;
    border: 1px solid var(--border-color);
    border-radius: 10px;
    padding: 12px;
    overflow: auto;
    max-height: 260px;

    .qk-title {
      font-size: 12px;
      font-weight: 600;
      color: var(--text-secondary);
      margin-bottom: 8px;
    }

    .qk-item {
      display: flex;
      gap: 8px;
      padding: 8px;
      border-radius: 8px;
      cursor: pointer;
      font-size: 13px;
      transition: background 0.15s;

      &:hover {
        background: #ebf5ff;
      }

      .ts {
        flex-shrink: 0;
        color: var(--primary-color);
        font-variant-numeric: tabular-nums;
        font-size: 12px;
      }

      .pt {
        color: var(--text-primary);
        line-height: 1.4;
      }
    }
  }
}

.kp-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.kp-card {
  display: flex;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--border-color);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.15s;
  background: var(--surface-1);

  &:hover {
    border-color: rgba(22, 119, 255, 0.35);
    box-shadow: 0 4px 12px rgba(22, 119, 255, 0.08);
    transform: translateY(-1px);
  }

  .kp-index {
    width: 28px;
    height: 28px;
    border-radius: 8px;
    background: #ebf5ff;
    color: var(--primary-color);
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 600;
    font-size: 13px;
    flex-shrink: 0;
  }

  .kp-body {
    flex: 1;

    .ts-tag {
      margin-bottom: 6px;
    }
  }

  .kp-text {
    font-size: 14px;
    line-height: 1.55;
    color: var(--text-primary);
  }
}

.chapter-item {
  cursor: pointer;
  padding: 4px 0 8px;

  .ch-head {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 4px;

    .ch-title {
      font-weight: 600;
      color: var(--text-primary);
    }
  }

  .ch-summary {
    color: var(--text-secondary);
    font-size: 13px;
    line-height: 1.55;
  }

  &:hover .ch-title {
    color: var(--primary-color);
  }
}

.md-toolbar,
.transcript-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 12px;
}

.md-body {
  background: var(--surface-2);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 16px 20px;
  line-height: 1.7;

  :deep(h1),
  :deep(h2),
  :deep(h3) {
    margin: 12px 0 8px;
    font-weight: 600;
  }

  :deep(ul),
  :deep(ol) {
    padding-left: 20px;
  }

  :deep(code) {
    background: var(--surface-muted);
    padding: 1px 6px;
    border-radius: 4px;
    font-size: 12px;
  }

  :deep(pre) {
    background: #0f172a;
    color: #e2e8f0;
    padding: 12px;
    border-radius: 8px;
    overflow: auto;
  }
}

.script-box {
  white-space: pre-wrap;
  background: linear-gradient(180deg, #fffbeb, #ffffff);
  border: 1px solid #fde68a;
  border-radius: 12px;
  padding: 18px 20px;
  line-height: 1.7;
  font-size: 14px;
  color: var(--text-primary);
}

.segment-list {
  max-height: 520px;
  overflow-y: auto;
  border: 1px solid var(--border-color);
  border-radius: 12px;
}

.seg-row {
  display: flex;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid #f3f4f6;
  cursor: pointer;
  transition: background 0.12s;

  &:last-child {
    border-bottom: none;
  }

  &:hover,
  &.active {
    background: #ebf5ff;
  }

  .seg-time {
    flex-shrink: 0;
    width: 56px;
    font-size: 12px;
    font-variant-numeric: tabular-nums;
    color: var(--primary-color);
    font-weight: 500;
    padding-top: 2px;
  }

  .seg-text {
    flex: 1;
    font-size: 13px;
    line-height: 1.55;
    color: var(--text-primary);

    :deep(mark) {
      background: #fef08a;
      padding: 0 2px;
      border-radius: 2px;
    }
  }
}

@media (max-width: 900px) {
  .workspace {
    grid-template-columns: 1fr;
  }

  .task-panel {
    max-height: 280px;
  }

  .detail-panel {
    max-height: none;
  }
}

</style>