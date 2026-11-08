import type { Order } from './types'

export function toCents(value: string | number): number {
  const input = String(value).trim()
  if (!/^\d+(\.\d{1,2})?$/.test(input)) throw new Error('请输入非负金额，最多两位小数。')
  const [whole, fraction = ''] = input.split('.')
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
  if (!Number.isSafeInteger(cents)) throw new Error('金额超出可处理范围。')
  return cents
}

export function money(cents: number): string {
  return `¥${(cents / 100).toFixed(2)}`
}

export interface RefundContext {
  order: Order
  paymentRefundedCents: number
  paymentPaidCents: number
  paymentRefundCount: number
  paymentLastRefundAt?: number
  now: number
}

export function validateRefund(
  context: RefundContext,
  kind: '全额' | '部分',
  amountCents: number,
): string {
  const { order, now } = context
  if (order.locking) return '该订单正在结算，本次退款未执行，未成功原因已记录。'
  if (order.pendingRefund || ['退款处理中', '退款失败', '结果未知'].includes(order.refundStatus)) {
    return '现有退款仍在处理中或待核实，请先处理原退款单，不可重复发起。'
  }
  const deadline = order.completedAt ?? order.closedAt
  const newRequestAllowed = !deadline || now - deadline <= 24 * 60 * 60 * 1000
  if (!order.pendingRefund && !newRequestAllowed) return '已超过新售后申请时限；时限内已受理的申请仍可继续处理。'
  if (!Number.isSafeInteger(amountCents) || amountCents <= 0) return '退款金额须大于 0，且精确到分。'
  if (amountCents > order.paidCents - order.refundedCents) return '超过该拆单剩余可退实付金额。'
  if (kind === '部分' && amountCents > order.foodCents - order.foodRefundedCents) {
    return '部分退款仅退餐品金额，不能超过剩余可退餐品金额。'
  }
  if (kind === '全额' && (order.refundedCents > 0 || amountCents !== order.paidCents)) {
    return '全额退款仅支持首次退回本单实付；已有部分退款请继续申请部分退款。'
  }
  if (amountCents > context.paymentPaidCents - context.paymentRefundedCents) return '超过关联支付单剩余可退实付金额。'
  if (kind === '部分' && context.paymentRefundCount >= 50) return '关联支付单已达到附件演示限制的 50 次部分退款，请协调处理。'
  if (kind === '部分' && context.paymentLastRefundAt && now - context.paymentLastRefundAt < 60_000) {
    return '关联支付单退款申请须至少间隔 1 分钟（附件演示限制）。'
  }
  return ''
}

export function settlementStatus(order: Order, now: number): Order['settlementStatus'] {
  if (order.pendingRefund || ['待商户处理', '协商中', '退款处理中', '退款失败', '结果未知'].includes(order.refundStatus)) return '售后暂缓'
  const readyFrom = order.lastRefundAt ?? order.completedAt
  return readyFrom && now - readyFrom >= 24 * 60 * 60 * 1000 ? '可提现' : '待结算'
}

export function validateOperationsConfig(config: {
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
}): string {
  if (!Number.isInteger(config.feeCents) || config.feeCents < 0 || config.feeCents > 100_000) return '固定配送费须在 ¥0.00 至 ¥1,000.00 之间。'
  if (![config.foodRate, config.deliveryRate].every((rate) => Number.isFinite(rate) && rate >= 0 && rate <= 100)) return '抽成比例须在 0% 至 100% 之间。'
  if (![config.regularBatch, config.peakBatch, config.acceptTimeout, config.reminderInterval].every((value) => Number.isInteger(value) && value >= 1 && value <= 1440)) {
    return '批次、接单超时与提醒间隔须为 1 至 1440 分钟的整数。'
  }
  const time = (value: string) => /^([01]\d|2[0-3]):[0-5]\d$/.test(value)
  const values = [config.serviceStart, config.serviceEnd, config.peakStart, config.peakEnd]
  if (!values.every(time) || config.serviceStart >= config.serviceEnd || config.peakStart >= config.peakEnd || config.peakStart < config.serviceStart || config.peakEnd > config.serviceEnd) {
    return '请检查服务与高峰时段；当前演示不支持跨日时段。'
  }
  return ''
}
