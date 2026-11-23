import { money } from './rules'
import type { ConfigSnapshot } from './types'

export const maxFixedAmount = 21474836.47

export type PricingMode = 'FIXED' | 'PERCENTAGE'
export type PricingRule = { mode: PricingMode; value: number }
export type PricingDraft = { mode: PricingMode; value: number | undefined }
export const pricingFields = [
  { key: 'fee', label: '配送费' },
  { key: 'food', label: '餐品平台抽成' },
  { key: 'delivery', label: '配送费平台抽成' },
] as const

export type Pricing = Record<typeof pricingFields[number]['key'], PricingRule>
export type PricingForm = Record<typeof pricingFields[number]['key'], PricingDraft>

export function configPricing(config: Pick<ConfigSnapshot, 'pricing' | 'feeCents' | 'foodRate' | 'deliveryRate'>): Pricing {
  return config.pricing ?? {
    fee: { mode: 'FIXED', value: config.feeCents },
    food: { mode: 'PERCENTAGE', value: Math.round(config.foodRate * 100) },
    delivery: { mode: 'PERCENTAGE', value: Math.round(config.deliveryRate * 100) },
  }
}

export function pricingForm(pricing?: Pricing): PricingForm {
  return {
    fee: { mode: pricing?.fee.mode ?? 'FIXED', value: pricing && pricing.fee.value / 100 },
    food: { mode: pricing?.food.mode ?? 'PERCENTAGE', value: pricing && pricing.food.value / 100 },
    delivery: { mode: pricing?.delivery.mode ?? 'PERCENTAGE', value: pricing && pricing.delivery.value / 100 },
  }
}

export function pricingPayload(form: PricingForm): Pricing | undefined {
  const entries = pricingFields.map(({ key }) => {
    const { mode, value } = form[key]
    if (value == null || !Number.isFinite(value) || value < 0 || value > (mode === 'FIXED' ? maxFixedAmount : 100)) return
    return [key, { mode, value: Math.round(value * 100) }] as const
  })
  if (entries.includes(undefined)) return
  return Object.fromEntries(entries.filter(entry => entry !== undefined)) as Pricing
}

export function formatPricing(rule: PricingRule) {
  return rule.mode === 'FIXED' ? money(rule.value) : (rule.value / 100).toFixed(2) + '%'
}

export function pricingAmount(rule: PricingRule, base: number) {
  return rule.mode === 'FIXED' ? rule.value : Math.round(base * rule.value / 10000)
}


export interface SettlementSnapshot {
  eventType: string
  accountId: string
  ruleSource: 'DEFAULT' | 'MERCHANT' | 'LEGACY'
  ruleVersion: number
  rules: Pricing
  foodBaseCents: number
  deliveryBaseCents: number
  originalDeliveryCents: number
  foodRefundedCents: number
  deliveryRefundedCents: number
  foodCommissionCents: number
  deliveryCommissionCents: number
  riderDueCents: number
  amountCents: number
  settledAt: number
}
