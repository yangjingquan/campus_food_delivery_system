import type { Pricing } from './pricing'

export type SectionKey = 'merchants' | 'riders' | 'orders' | 'aftersales' | 'finance' | 'withdrawals' | 'config' | 'wechat'
export type AuditStatus = 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED'
export type RefundStatus = 'NO_AFTER_SALE' | 'AFTER_SALE_ENDED' | 'REFUND_SUCCEEDED' | 'OFFLINE_RESOLVED' | 'REFUND_FAILED' | 'PLATFORM_REVIEW' | 'AWAITING_MERCHANT_DECISION' | 'REFUND_PROCESSING' | 'REFUND_VERIFYING' | 'REFUND_QUEUED' | 'REFUND_REJECTED' | 'NEGOTIATING'
export type AppType = 'user' | 'merchant' | 'rider'

export interface Merchant {
  id: string
  name: string
  contact: string
  phone: string
  address: string
  auditStatus: AuditStatus
  businessStatus: 'STORE_CLOSED' | 'STORE_OPEN' | 'PROFILE_REVISION'
  orderCount: number
  joinedAt: string
  auditReason?: string
}

export interface Rider {
  id: string
  name: string
  phone: string
  gender: 'MALE' | 'FEMALE' | 'UNDISCLOSED'
  availability: 'MIDDAY' | 'EVENING' | 'MIDDAY_AND_EVENING' | 'CUSTOM'
  availableTime: string
  auditStatus: AuditStatus
  deliveries: number
  balanceCents: number
  joinedAt: string
  auditReason?: string
}

export interface Order {
  id: string
  paymentId: string
  merchantId: string
  merchant: string
  rider: string
  items: string
  foodCents: number
  deliveryCents: number
  paidCents: number
  refundedCents: number
  foodRefundedCents: number
  paymentStatus: 'UNPAID' | 'PAYMENT_SUCCEEDED' | 'MOCK_PAYMENT_SUCCEEDED' | 'CLOSED'
  fulfillStatus: 'PENDING_PAYMENT' | 'AWAITING_MERCHANT' | 'PREPARING_FOOD' | 'AWAITING_RIDER' | 'AWAITING_PICKUP' | 'DELIVERING' | 'COMPLETED' | 'CLOSED'
  refundStatus: RefundStatus
  settlementStatus: 'PENDING_PAYMENT' | 'PENDING_SETTLEMENT' | 'WITHDRAWABLE' | 'SETTLED' | 'WITHDRAWN' | 'CLOSED' | 'SETTLEMENT_HELD' | 'PENDING_CREDIT'
  accepted: boolean
  pendingRefund: boolean
  locking: boolean
  paymentCount: number
  paymentLastRefundAt?: number
  completedAt?: number
  closedAt?: number
  paidAt: number
  lastRefundAt?: number
  intervene: boolean
  interveneReason?: string
  interveneCode?: string
  coordinationNotes: Array<{
    id: number
    time: string
    actor: string
    actorCode?: string
    note: string
    previousStatus?: string
    nextStatus?: string
  }>
}

export interface RefundRecord {
  id: string
  orderId: string
  paymentId: string
  reason: string
  type: 'full' | 'partial'
  amountCents: number
  status: RefundStatus
  channelStatus: string
  createdAt: number
  operator: string
  channelReason?: string
  checkedAt?: number
  offline?: {
    channel: string
    proof: string
    operator: string
    actualAt: number
  }
}

export interface Withdrawal {
  id: string
  applicant: string
  applicantType: 'MERCHANT' | 'RIDER'
  amountCents: number
  status: 'PENDING_REVIEW' | 'REVIEW_REJECTED' | 'PENDING_PAYOUT' | 'TRANSFERRING' | 'AWAITING_CONFIRMATION' | 'PAID' | 'PAYOUT_FAILED'
  createdAt: string
  reason?: string
}

export interface ConfigSnapshot {
  version: string
  pricing?: Pricing | null
  feeCents: number
  foodRate: number
  deliveryRate: number
  regularBatch: number
  peakBatch: number
  serviceStart: string
  serviceEnd: string
  peakStart: string
  peakEnd: string
  peakPeriods: Array<{ start: string; end: string }>
  acceptTimeout: number
  reminderInterval: number
  merchantSettlementWaitMinutes?: number
  riderSettlementWaitMinutes?: number
  effectiveAt: string
  operator: string
}

export interface MaterialFixture {
  hasValue: boolean
  fixtureId: string
  format: 'valid' | 'invalid' | 'missing'
  type: string
  byteLength?: number
}

export interface MiniProgramConfig {
  appId: string
  secret: MaterialFixture
  modifiedAt?: string
}

export interface WechatPaymentConfig {
  merchantNo: string
  serialNo: string
  publicKeyId: string
  apiV3Key: MaterialFixture
  merchantPrivateKey: MaterialFixture
  wechatPublicKey: MaterialFixture
  paymentCallback: string
  refundCallback: string
  transferCallback: string
}

export interface WechatVersion {
  id: string
  action: string
  time: string
  operator: string
  fields: string[]
  enabled: boolean
  snapshot?: {
    apps: WechatSettings['apps']
    payment: WechatPaymentConfig
    enabled: boolean
  }
}

export interface WechatSettings {
  apps: Record<AppType, MiniProgramConfig>
  payment: WechatPaymentConfig
  enabled: boolean
  modifiedAt: string
  permissions: { view: boolean; edit: boolean; toggle: boolean }
  versions: WechatVersion[]
  audit: Array<{ time: string; action: string; result: string; fields: string[] }>
}

export interface DemoState {
  merchants: Merchant[]
  riders: Rider[]
  orders: Order[]
  refunds: RefundRecord[]
  withdrawals: Withdrawal[]
  config: ConfigSnapshot
  configHistory: ConfigSnapshot[]
  wechat: WechatSettings
}

export interface OverviewData {
  orderCount: number
  paidCents: number
  deliveringCount: number
  interveneCount: number
  todoMerchants: number
  todoRiders: number
  failedRefunds: number
  todoWithdrawals: number
  trend: Array<{ label: string; value: number }>
}
