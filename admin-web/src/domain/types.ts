export type SectionKey = 'merchants' | 'riders' | 'orders' | 'aftersales' | 'finance' | 'withdrawals' | 'config' | 'wechat'
export type AuditStatus = '待审核' | '已通过' | '已驳回'
export type RefundStatus = '待商户处理' | '协商中' | '退款处理中' | '结果未知' | '退款成功' | '退款失败' | '线下已处理' | '拒绝退款'
export type AppType = 'user' | 'merchant' | 'rider'

export interface Merchant {
  id: string
  name: string
  contact: string
  phone: string
  address: string
  auditStatus: AuditStatus
  businessStatus: '营业中' | '休息中'
  orderCount: number
  joinedAt: string
  auditReason?: string
}

export interface Rider {
  id: string
  name: string
  phone: string
  gender: '男' | '女'
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
  paymentStatus: '支付成功' | '支付关闭' | '未支付'
  fulfillStatus: string
  refundStatus: RefundStatus | '无售后'
  settlementStatus: '待结算' | '售后暂缓' | '可提现'
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
  coordinationNotes: string[]
}

export interface RefundRecord {
  id: string
  orderId: string
  paymentId: string
  reason: string
  type: '全额' | '部分' | '线下'
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
  applicantType: '商户' | '骑手'
  amountCents: number
  status: '待审核' | '审核拒绝' | '打款中' | '打款成功' | '打款失败'
  createdAt: string
  reason?: string
}

export interface ConfigSnapshot {
  version: string
  feeCents: number
  foodRate: number
  deliveryRate: number
  regularBatch: number
  peakBatch: number
  serviceStart: string
  serviceEnd: string
  peakStart: string
  peakEnd: string
  acceptTimeout: number
  reminderInterval: number
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
