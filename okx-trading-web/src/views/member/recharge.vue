<template>
  <div class="recharge-page">
    <div class="top-bar">
      <a-button type="text" @click="router.push('/member')">
        ← 返回会员中心
      </a-button>
    </div>

    <a-alert
      v-if="auth.isSuperAdmin"
      type="warning"
      show-icon
      message="超级管理员无需购买会员"
      description="系统禁止为超管创建支付订单。您可返回会员中心查看说明。"
      class="mb-16"
    />

    <div v-else class="layout">
      <!-- 左侧：选套餐 / 渠道 -->
      <section class="page-card step-panel">
        <div class="step-title">1. 选择套餐</div>
        <a-spin :spinning="plansLoading">
          <div class="plan-list">
            <button
              v-for="p in plans"
              :key="p.id"
              type="button"
              class="plan-option"
              :class="{ active: selectedPlanId === p.id }"
              :disabled="paying || polling"
              @click="selectedPlanId = p.id"
            >
              <div class="po-left">
                <div class="po-name">{{ p.name }}</div>
                <div class="po-meta">{{ p.durationDays }} 天 · {{ p.description || '会员' }}</div>
              </div>
              <div class="po-price">¥{{ p.priceYuan }}</div>
            </button>
          </div>
        </a-spin>

        <div class="step-title mt-20">2. 支付方式</div>
        <div class="channel-list">
          <button
            v-if="mockPayAllowed"
            type="button"
            class="channel-option"
            :class="{ active: channel === 'mock' }"
            :disabled="paying || polling"
            @click="channel = 'mock'"
          >
            <div class="ch-title">Mock 模拟支付</div>
            <div class="ch-desc">仅开发环境 · 需服务端 pay.mock-enabled=true</div>
          </button>
          <button
            type="button"
            class="channel-option"
            :class="{ active: channel === 'alipay' }"
            :disabled="paying || polling"
            @click="channel = 'alipay'"
          >
            <div class="ch-title">支付宝</div>
            <div class="ch-desc">直连已接入 · 需服务端 pay.alipay.enabled=true 与密钥</div>
          </button>
          <!-- 微信支付渠道未就绪：不展示入口，避免误导 -->
        </div>
        <a-alert
          v-if="channel === 'alipay'"
          type="info"
          show-icon
          class="mt-12"
          message="支付宝需服务端开启并配置密钥"
          description="未进件或未配置密钥时，创建订单会失败。生产环境已默认关闭 Mock 模拟支付。"
        />

        <a-button
          type="primary"
          size="large"
          block
          class="mt-20"
          :loading="paying"
          :disabled="!selectedPlanId || !!currentOrder && isOpen(currentOrder)"
          @click="createOrder"
        >
          {{ currentOrder && isOpen(currentOrder) ? '已有待支付订单' : '创建支付订单' }}
        </a-button>
      </section>

      <!-- 右侧：支付 / 结果 -->
      <section class="page-card pay-panel">
        <template v-if="!currentOrder">
          <a-empty description="选择套餐后创建订单，将在此完成支付" />
        </template>

        <template v-else-if="currentOrder.status === 'SUCCESS'">
          <div class="success-box">
            <div class="success-icon">✓</div>
            <h2>支付成功</h2>
            <p>会员已开通 / 续费，请保存订单号以便客服查询。</p>
            <div class="kv">
              <div><span>订单号</span><code>{{ currentOrder.orderNo }}</code></div>
              <div><span>套餐</span><b>{{ currentOrder.planName }}</b></div>
              <div><span>金额</span><b>¥{{ currentOrder.amountYuan }}</b></div>
              <div><span>到期时间</span><b>{{ auth.user?.memberExpireAt || '—' }}</b></div>
            </div>
            <div class="success-actions">
              <a-button type="primary" @click="router.push('/member')">返回会员中心</a-button>
              <a-button @click="resetAndBuyAgain">再买一单</a-button>
            </div>
          </div>
        </template>

        <template v-else-if="currentOrder.status === 'CLOSED' || currentOrder.status === 'FAILED'">
          <a-result
            status="warning"
            :title="currentOrder.status === 'CLOSED' ? '订单已关闭' : '订单失败'"
            :sub-title="`订单号 ${currentOrder.orderNo}，请重新下单`"
          >
            <template #extra>
              <a-button type="primary" @click="resetAndBuyAgain">重新下单</a-button>
            </template>
          </a-result>
        </template>

        <template v-else>
          <div class="paying-box">
            <div class="step-title">待支付</div>
            <div class="order-summary">
              <div class="os-row">
                <span>订单号</span>
                <code class="copyable" @click="copyOrderNo">{{ currentOrder.orderNo }}</code>
              </div>
              <div class="os-row">
                <span>套餐</span>
                <b>{{ currentOrder.planName }} · {{ currentOrder.durationDays }} 天</b>
              </div>
              <div class="os-row">
                <span>金额</span>
                <b class="price">¥{{ currentOrder.amountYuan }}</b>
              </div>
              <div class="os-row">
                <span>状态</span>
                <a-tag color="processing">{{ currentOrder.status }}</a-tag>
              </div>
              <div class="os-row">
                <span>过期</span>
                <span>{{ currentOrder.expireAt || '—' }}</span>
              </div>
            </div>

            <!-- Mock -->
            <div v-if="currentOrder.channel === 'mock'" class="mock-pay">
              <div class="mock-hint">当前为 Mock 通道：点击下方按钮模拟支付成功</div>
              <a-button
                type="primary"
                size="large"
                block
                :loading="confirming"
                @click="doMockConfirm"
              >
                确认模拟支付
              </a-button>
            </div>

            <!-- 真实扫码 / H5（预留） -->
            <div v-else class="real-pay">
              <div v-if="currentOrder.payMode === 'H5_URL' && currentOrder.payUrl" class="h5-box">
                <a-button type="primary" size="large" block @click="openPayUrl">
                  打开支付页面
                </a-button>
              </div>
              <div v-else-if="currentOrder.qrCodeUrl" class="qr-box">
                <img
                  v-if="qrDataUrl"
                  class="qr-img"
                  :src="qrDataUrl"
                  alt="支付二维码"
                />
                <a-spin v-else />
                <p>请使用对应 App 扫码支付</p>
              </div>
              <a-empty v-else description="等待支付凭证" />
            </div>

            <div class="poll-hint">
              <a-spin v-if="polling" size="small" />
              <span>{{ pollHint }}</span>
            </div>

            <div class="pay-actions">
              <a-button :disabled="confirming" @click="cancelOrder">取消订单</a-button>
              <a-button :loading="refreshing" @click="refreshOrder">刷新状态</a-button>
            </div>
          </div>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useAuthStore } from '@/stores/auth.store'
import { memberApi, payApi, type MemberPlan, type PayOrder } from '@/api/member.api'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const plansLoading = ref(false)
const paying = ref(false)
const confirming = ref(false)
const refreshing = ref(false)
const polling = ref(false)
const plans = ref<MemberPlan[]>([])
const selectedPlanId = ref<string>('')
/** 生产构建不展示 Mock；本地 dev 仍可联调（后端 local profile 默认 mock=true） */
const mockPayAllowed = import.meta.env.DEV === true
const channel = ref(mockPayAllowed ? 'mock' : 'alipay')
const currentOrder = ref<PayOrder | null>(null)
const qrDataUrl = ref('')

let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollStartedAt = 0
const POLL_MAX_MS = 5 * 60 * 1000

const pollHint = computed(() => {
  if (!currentOrder.value || !isOpen(currentOrder.value)) return ''
  if (polling.value) return '正在轮询支付结果…'
  if (currentOrder.value.channel === 'mock' && mockPayAllowed) {
    return '可点击「确认模拟支付」或等待自动轮询'
  }
  return '请完成扫码/跳转支付，或点击「刷新状态」'
})

function isOpen(o: PayOrder) {
  return o.status === 'CREATED' || o.status === 'PAYING'
}

function isTerminal(o: PayOrder) {
  return o.status === 'SUCCESS' || o.status === 'CLOSED' || o.status === 'FAILED'
}

function clientType(): string {
  const ua = navigator.userAgent || ''
  if (/Mobile|Android|iPhone|iPad/i.test(ua)) return 'H5'
  return 'PC'
}

async function buildLocalQr(content: string) {
  qrDataUrl.value = ''
  if (!content) return
  try {
    const QRCode = (await import('qrcode')).default
    qrDataUrl.value = await QRCode.toDataURL(content, {
      width: 220,
      margin: 2,
      errorCorrectionLevel: 'M'
    })
  } catch (e) {
    console.warn('本地生成二维码失败', e)
    message.error('二维码生成失败，请刷新状态或复制支付链接')
  }
}

watch(
  () => currentOrder.value?.qrCodeUrl,
  (url) => {
    if (url) void buildLocalQr(url)
    else qrDataUrl.value = ''
  }
)

async function loadPlans() {
  plansLoading.value = true
  try {
    const res = await memberApi.listPlans()
    plans.value = res.data || []
    const q = route.query.planId as string | undefined
    if (q && plans.value.some((p) => p.id === q)) {
      selectedPlanId.value = q
    } else if (plans.value.length && !selectedPlanId.value) {
      selectedPlanId.value = plans.value[0].id
    }
  } finally {
    plansLoading.value = false
  }
}

async function loadExistingOrder(orderNo: string) {
  try {
    const res = await payApi.getOrder(orderNo)
    currentOrder.value = res.data
    if (res.data?.planId) selectedPlanId.value = res.data.planId
    if (res.data?.channel) channel.value = res.data.channel
    if (res.data && isOpen(res.data)) startPolling()
    if (res.data?.status === 'SUCCESS') await auth.fetchMe()
  } catch {
    message.error('订单不存在或无权查看')
  }
}

async function createOrder() {
  if (!selectedPlanId.value) {
    message.warning('请选择套餐')
    return
  }
  paying.value = true
  try {
    const res = await payApi.createOrder({
      planId: selectedPlanId.value,
      channel: channel.value,
      clientType: clientType()
    })
    currentOrder.value = res.data
    if (res.data.idempotentReuse) {
      message.info('已复用未完成订单')
    } else {
      message.success('订单已创建')
    }
    // H5 真通道：自动跳转
    if (res.data.payMode === 'H5_URL' && res.data.payUrl) {
      window.location.href = res.data.payUrl
      return
    }
    if (isOpen(res.data)) startPolling()
  } catch {
    // request 已 toast
  } finally {
    paying.value = false
  }
}

async function doMockConfirm() {
  if (!currentOrder.value) return
  confirming.value = true
  try {
    const res = await payApi.mockConfirm(currentOrder.value.orderNo)
    currentOrder.value = res.data
    stopPolling()
    await auth.fetchMe()
    message.success('模拟支付成功，会员已开通')
  } catch {
    // toast
  } finally {
    confirming.value = false
  }
}

async function refreshOrder() {
  if (!currentOrder.value) return
  refreshing.value = true
  try {
    const res = await payApi.getOrder(currentOrder.value.orderNo)
    currentOrder.value = res.data
    if (res.data.status === 'SUCCESS') {
      stopPolling()
      await auth.fetchMe()
      message.success('支付成功')
    }
  } finally {
    refreshing.value = false
  }
}

async function cancelOrder() {
  if (!currentOrder.value) return
  try {
    const res = await payApi.cancelOrder(currentOrder.value.orderNo)
    currentOrder.value = res.data
    stopPolling()
    message.success('订单已取消')
  } catch {
    // toast
  }
}

function openPayUrl() {
  if (currentOrder.value?.payUrl) {
    window.location.href = currentOrder.value.payUrl
  }
}

function copyOrderNo() {
  const no = currentOrder.value?.orderNo
  if (!no) return
  navigator.clipboard?.writeText(no).then(
    () => message.success('订单号已复制'),
    () => message.info(no)
  )
}

function resetAndBuyAgain() {
  stopPolling()
  currentOrder.value = null
}

function stopPolling() {
  polling.value = false
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function scheduleNextPoll() {
  if (!polling.value || !currentOrder.value || !isOpen(currentOrder.value)) {
    stopPolling()
    return
  }
  if (Date.now() - pollStartedAt > POLL_MAX_MS) {
    stopPolling()
    message.warning('支付等待超时，可点击刷新状态或重新下单')
    return
  }
  const delay = document.hidden ? 5000 : 1500
  pollTimer = setTimeout(async () => {
    try {
      const res = await payApi.getOrder(currentOrder.value!.orderNo)
      currentOrder.value = res.data
      if (res.data.status === 'SUCCESS') {
        stopPolling()
        await auth.fetchMe()
        message.success('支付成功，会员已更新')
        return
      }
      if (isTerminal(res.data)) {
        stopPolling()
        return
      }
    } catch {
      // ignore transient
    }
    scheduleNextPoll()
  }, delay)
}

function startPolling() {
  stopPolling()
  polling.value = true
  pollStartedAt = Date.now()
  scheduleNextPoll()
}

function onVisibility() {
  // 从后台回前台时立刻拉一次
  if (!document.hidden && polling.value && currentOrder.value && isOpen(currentOrder.value)) {
    refreshOrder()
  }
}

onMounted(async () => {
  document.addEventListener('visibilitychange', onVisibility)
  await auth.fetchMe().catch(() => undefined)
  if (auth.isSuperAdmin) return
  await loadPlans()
  const orderNo = route.query.orderNo as string | undefined
  if (orderNo) {
    await loadExistingOrder(orderNo)
  }
})

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisibility)
  stopPolling()
})

watch(
  () => route.query.orderNo,
  (no) => {
    if (typeof no === 'string' && no && no !== currentOrder.value?.orderNo) {
      loadExistingOrder(no)
    }
  }
)
</script>

<style scoped lang="scss">
.recharge-page {
  max-width: 1080px;
  margin: 0 auto;
  padding-bottom: 32px;
}

.top-bar {
  margin-bottom: 8px;
}

.mb-16 {
  margin-bottom: 16px;
}

.mt-20 {
  margin-top: 20px;
}

.mt-12 {
  margin-top: 12px;
}

.layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  align-items: start;
}

.step-title {
  font-size: 15px;
  font-weight: 650;
  color: var(--primary-strong);
  margin-bottom: 12px;
}

.plan-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.plan-option {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  width: 100%;
  text-align: left;
  padding: 14px 16px;
  border-radius: 12px;
  border: 1px solid var(--border-color);
  background: var(--surface-1);
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;

  &:hover:not(:disabled) {
    border-color: var(--text-muted);
  }

  &.active {
    border-color: var(--primary-strong);
    box-shadow: 0 0 0 1px var(--primary-strong);
  }

  &:disabled {
    opacity: 0.65;
    cursor: not-allowed;
  }
}

.po-name {
  font-weight: 650;
  color: var(--primary-strong);
}

.po-meta {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
}

.po-price {
  font-size: 18px;
  font-weight: 750;
  color: var(--primary-strong);
  white-space: nowrap;
}

.channel-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.channel-option {
  text-align: left;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid var(--border-color);
  background: var(--surface-1);
  cursor: pointer;

  &.active {
    border-color: var(--primary-strong);
    box-shadow: 0 0 0 1px var(--primary-strong);
  }

  &.disabled,
  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
    background: var(--surface-hover);
  }
}

.ch-title {
  font-weight: 600;
  font-size: 14px;
}

.ch-desc {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
}

.pay-panel {
  min-height: 360px;
}

.order-summary {
  border-radius: 12px;
  background: var(--surface-hover);
  border: 1px solid var(--surface-3);
  padding: 12px 14px;
  margin-bottom: 16px;
}

.os-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  font-size: 13px;
  border-bottom: 1px dashed var(--border-color);

  &:last-child {
    border-bottom: none;
  }

  span:first-child {
    color: var(--text-muted);
  }

  .price {
    font-size: 18px;
  }

  code {
    font-size: 12px;
    background: var(--surface-1);
    padding: 2px 6px;
    border-radius: 6px;
    border: 1px solid var(--border-color);
  }

  .copyable {
    cursor: pointer;
  }
}

.mock-pay {
  margin-bottom: 16px;
}

.mock-hint {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--warning-bg);
  border: 1px solid var(--warning-border);
}

.qr-box {
  text-align: center;
  margin-bottom: 16px;

  .qr-img {
    width: 220px;
    height: 220px;
    border-radius: 12px;
    border: 1px solid var(--border-color);
    background: var(--surface-1);
  }

  p {
    margin-top: 10px;
    color: var(--text-secondary);
    font-size: 13px;
  }
}

.poll-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-muted);
  min-height: 24px;
  margin-bottom: 12px;
}

.pay-actions {
  display: flex;
  gap: 10px;
}

.success-box {
  text-align: center;
  padding: 12px 8px 4px;
}

.success-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 12px;
  border-radius: 50%;
  background: var(--success-bg);
  color: var(--success-color);
  font-size: 28px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.success-box h2 {
  margin: 0 0 8px;
  font-size: 20px;
}

.success-box > p {
  color: var(--text-secondary);
  margin: 0 0 16px;
}

.kv {
  text-align: left;
  background: var(--surface-hover);
  border-radius: 12px;
  padding: 12px 14px;
  margin-bottom: 16px;

  > div {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    padding: 8px 0;
    border-bottom: 1px dashed var(--border-color);
    font-size: 13px;

    &:last-child {
      border-bottom: none;
    }

    span {
      color: var(--text-muted);
    }

    code {
      font-size: 12px;
    }
  }
}

.success-actions {
  display: flex;
  justify-content: center;
  gap: 10px;
}

@media (max-width: 860px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
