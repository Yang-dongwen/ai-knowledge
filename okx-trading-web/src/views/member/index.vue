<template>
  <div class="member-page">
    <section class="hero page-card">
      <div class="hero-top">
        <div>
          <div class="kicker">会员中心</div>
          <h1 class="title">解锁会员权益</h1>
          <p class="sub">
            开通会员后可享受后续配额与优先能力（权益差异持续开放）。当前支持 Mock 支付联调，真收款通道待商户资质就绪。
          </p>
        </div>
        <div class="status-chip" :class="statusClass">
          <span class="dot" />
          {{ statusText }}
        </div>
      </div>

      <div class="status-grid">
        <div class="stat">
          <div class="stat-label">当前角色</div>
          <div class="stat-value">{{ roleText }}</div>
        </div>
        <div class="stat">
          <div class="stat-label">会员状态</div>
          <div class="stat-value">{{ auth.isMemberActive ? '有效' : '未开通 / 已过期' }}</div>
        </div>
        <div class="stat">
          <div class="stat-label">到期时间</div>
          <div class="stat-value mono">{{ expireText }}</div>
        </div>
      </div>

      <div class="hero-actions">
        <a-button
          v-if="!auth.isSuperAdmin"
          type="primary"
          size="large"
          @click="goRecharge"
        >
          {{ auth.isMemberActive ? '续费会员' : '立即开通' }}
        </a-button>
        <a-alert
          v-else
          type="info"
          show-icon
          message="超级管理员无需购买会员"
          description="超管已具备全部管理权限，系统禁止为超管创建支付订单。"
        />
        <a-button size="large" @click="refresh">刷新状态</a-button>
      </div>
    </section>

    <section class="page-card">
      <div class="section-head">
        <h2>套餐一览</h2>
        <span class="hint">价格以服务端为准 · 单位人民币</span>
      </div>
      <a-spin :spinning="plansLoading">
        <div v-if="plans.length" class="plan-grid">
          <div
            v-for="p in plans"
            :key="p.id"
            class="plan-card"
            :class="{ recommend: p.code === 'quarter' }"
          >
            <div v-if="p.code === 'quarter'" class="badge">推荐</div>
            <div class="plan-name">{{ p.name }}</div>
            <div class="plan-price">
              <span class="yen">¥</span>
              <span class="num">{{ p.priceYuan }}</span>
              <span v-if="p.originalPriceYuan" class="origin">¥{{ p.originalPriceYuan }}</span>
            </div>
            <div class="plan-days">{{ p.durationDays }} 天</div>
            <p class="plan-desc">{{ p.description || '会员时长套餐' }}</p>
            <a-button
              v-if="!auth.isSuperAdmin"
              type="primary"
              block
              @click="goRecharge(p.id)"
            >
              选择此套餐
            </a-button>
          </div>
        </div>
        <a-empty v-else-if="!plansLoading" description="暂无上架套餐" />
      </a-spin>
    </section>

    <section class="page-card">
      <div class="section-head">
        <h2>我的订单</h2>
        <a-button type="link" :loading="ordersLoading" @click="loadOrders">刷新</a-button>
      </div>
      <a-table
        :columns="orderColumns"
        :data-source="orders"
        :loading="ordersLoading"
        :pagination="false"
        row-key="orderNo"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <a-tag :color="statusColor(record.status)">{{ record.status }}</a-tag>
          </template>
          <template v-else-if="column.key === 'amount'">
            ¥{{ record.amountYuan }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button
              v-if="record.status === 'PAYING' || record.status === 'CREATED'"
              type="link"
              size="small"
              @click="goRecharge(record.planId, record.orderNo)"
            >
              继续支付
            </a-button>
            <span v-else class="mono muted">{{ record.orderNo }}</span>
          </template>
        </template>
      </a-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useAuthStore } from '@/stores/auth.store'
import { roleLabel } from '@/api/auth.api'
import { memberApi, payApi, type MemberPlan, type PayOrder } from '@/api/member.api'

const router = useRouter()
const auth = useAuthStore()

const plansLoading = ref(false)
const ordersLoading = ref(false)
const plans = ref<MemberPlan[]>([])
const orders = ref<PayOrder[]>([])

const roleText = computed(() =>
  roleLabel(auth.user?.role, auth.user?.roleLabel)
)

const expireText = computed(() => {
  const exp = auth.user?.memberExpireAt
  if (!exp) return '—'
  if (auth.isMemberActive) return exp
  return `${exp}（已过期）`
})

const statusText = computed(() => {
  if (auth.isSuperAdmin) return '超级管理员'
  if (auth.isMemberActive) return '会员有效'
  return '普通用户'
})

const statusClass = computed(() => {
  if (auth.isSuperAdmin) return 'admin'
  if (auth.isMemberActive) return 'active'
  return 'idle'
})

const orderColumns = [
  { title: '订单号', dataIndex: 'orderNo', key: 'orderNo', ellipsis: true },
  { title: '套餐', dataIndex: 'planName', key: 'planName', width: 100 },
  { title: '渠道', dataIndex: 'channel', key: 'channel', width: 90 },
  { title: '金额', key: 'amount', width: 100 },
  { title: '状态', key: 'status', width: 110 },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170 },
  { title: '操作', key: 'action', width: 120 }
]

function statusColor(s: string) {
  if (s === 'SUCCESS') return 'success'
  if (s === 'PAYING' || s === 'CREATED') return 'processing'
  if (s === 'CLOSED') return 'default'
  if (s === 'FAILED') return 'error'
  return 'default'
}

function goRecharge(planId?: string, orderNo?: string) {
  router.push({
    path: '/member/recharge',
    query: {
      ...(planId ? { planId } : {}),
      ...(orderNo ? { orderNo } : {})
    }
  })
}

async function refresh() {
  await auth.fetchMe()
  message.success('已刷新会员状态')
}

async function loadPlans() {
  plansLoading.value = true
  try {
    const res = await memberApi.listPlans()
    plans.value = res.data || []
  } catch {
    plans.value = []
  } finally {
    plansLoading.value = false
  }
}

async function loadOrders() {
  ordersLoading.value = true
  try {
    const res = await payApi.listOrders(0, 20)
    orders.value = res.data || []
  } catch {
    orders.value = []
  } finally {
    ordersLoading.value = false
  }
}

onMounted(async () => {
  await auth.fetchMe().catch(() => undefined)
  await Promise.all([loadPlans(), loadOrders()])
})
</script>

<style scoped lang="scss">
.member-page {
  max-width: 1080px;
  margin: 0 auto;
  padding-bottom: 32px;
}

.hero {
  padding: 28px 28px 24px;
}

.hero-top {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 20px;
}

.kicker {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  letter-spacing: 0.04em;
  text-transform: uppercase;
  margin-bottom: 8px;
}

.title {
  font-size: 26px;
  font-weight: 700;
  color: var(--primary-strong);
  margin: 0 0 8px;
}

.sub {
  margin: 0;
  max-width: 560px;
  color: var(--text-secondary);
  line-height: 1.6;
}

.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  border: 1px solid var(--border-color);
  background: var(--surface-hover);
  white-space: nowrap;

  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #9ca3af;
  }

  &.active {
    background: #ecfdf5;
    border-color: #a7f3d0;
    color: #047857;
    .dot { background: #10b981; }
  }

  &.admin {
    background: #f5f3ff;
    border-color: #ddd6fe;
    color: #6d28d9;
    .dot { background: #8b5cf6; }
  }
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.stat {
  padding: 14px 16px;
  border-radius: 12px;
  background: var(--surface-hover);
  border: 1px solid #f3f4f6;
}

.stat-label {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 6px;
}

.stat-value {
  font-size: 15px;
  font-weight: 600;
  color: var(--primary-strong);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}

.muted {
  color: var(--text-muted);
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 16px;

  h2 {
    margin: 0;
    font-size: 17px;
    font-weight: 650;
  }

  .hint {
    font-size: 12px;
    color: var(--text-muted);
  }
}

.plan-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.plan-card {
  position: relative;
  padding: 20px 18px 16px;
  border-radius: 14px;
  border: 1px solid var(--border-color);
  background: var(--surface-1);
  transition: border-color 0.15s, box-shadow 0.15s;

  &:hover {
    border-color: var(--border-strong);
    box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
  }

  &.recommend {
    border-color: var(--primary-strong);
    box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
  }
}

.badge {
  position: absolute;
  top: 12px;
  right: 12px;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  background: #111827;
  color: #fff;
}

.plan-name {
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 10px;
}

.plan-price {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin-bottom: 4px;

  .yen {
    font-size: 16px;
    font-weight: 600;
  }

  .num {
    font-size: 32px;
    font-weight: 750;
    letter-spacing: -0.02em;
  }

  .origin {
    margin-left: 6px;
    font-size: 13px;
    color: var(--text-muted);
    text-decoration: line-through;
  }
}

.plan-days {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 10px;
}

.plan-desc {
  font-size: 13px;
  color: var(--text-secondary);
  min-height: 40px;
  margin: 0 0 14px;
}

@media (max-width: 900px) {
  .status-grid,
  .plan-grid {
    grid-template-columns: 1fr;
  }

  .hero-top {
    flex-direction: column;
  }
}
</style>
