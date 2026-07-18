import request from './request'

export interface MemberPlan {
  id: string
  code: string
  name: string
  description?: string
  durationDays: number
  priceCents: number
  priceYuan: string
  originalPriceCents?: number | null
  originalPriceYuan?: string | null
  currency?: string
  sortOrder?: number
}

export interface MemberStatus {
  role: string
  roleLabel: string
  memberActive: boolean
  memberExpireAt?: string | null
}

export interface PayOrder {
  orderNo: string
  channel: string
  status: string
  amountCents: number
  amountYuan: string
  planId?: string
  planCode?: string
  planName?: string
  durationDays?: number
  payMode?: string | null
  qrCodeUrl?: string | null
  payUrl?: string | null
  fulfilled?: number
  tradeNo?: string | null
  expireAt?: string | null
  paidAt?: string | null
  createdAt?: string | null
  idempotentReuse?: boolean | null
}

export interface CreatePayOrderBody {
  planId: string
  channel: string
  clientType?: string
}

export const memberApi = {
  listPlans() {
    return request.get('/member/plans') as Promise<{ data: MemberPlan[] }>
  },
  status() {
    return request.get('/member/status') as Promise<{ data: MemberStatus }>
  }
}

export const payApi = {
  createOrder(body: CreatePayOrderBody) {
    return request.post('/pay/orders', body) as Promise<{ data: PayOrder }>
  },
  getOrder(orderNo: string) {
    return request.get(`/pay/orders/${encodeURIComponent(orderNo)}`) as Promise<{ data: PayOrder }>
  },
  listOrders(page = 0, size = 20) {
    return request.get('/pay/orders', { params: { page, size } }) as Promise<{ data: PayOrder[] }>
  },
  cancelOrder(orderNo: string) {
    return request.post(`/pay/orders/${encodeURIComponent(orderNo)}/cancel`) as Promise<{ data: PayOrder }>
  },
  mockConfirm(orderNo: string) {
    return request.post('/pay/mock/confirm', { orderNo }) as Promise<{ data: PayOrder }>
  }
}
