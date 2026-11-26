import axios from 'axios'
import type { RowOrder } from '../domain/useRowReorder'
import type { SectionKey } from '../domain/types'
import type { Pricing, SettlementSnapshot } from '../domain/pricing'

export interface FoodCategory {
  id: number
  name: string
  productCount: number
}

export interface HomeBanner {
  id: number
  name: string
  linkType: 'page' | 'external'
  linkUrl: string
  sortOrder: number
}

export interface MerchantPrinter {
  sn: string
  pkeySet: boolean
}

export interface ProductWrite {
  merchantId: string
  name: string
  category: string
  description: string
  priceCents: number
  stock: number
  previousStock: number
  image: string
}

export interface AdminSessionUser {
  name: string
  role: string
  permissions: string[]
}

export interface MiniProgramUser {
  id: string
  nickname: string | null
  userNumber: string | null
  merchantId: string | null
  riderId: string | null
  merchantName: string | null
  merchantStatus: string | null
  riderName: string | null
  riderStatus: string | null
}

export type MiniProgramRole = 'user' | 'merchant' | 'rider'

export class AdminApiError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message)
  }
}

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/admin/v1',
  timeout: 12_000,
  headers: { 'Content-Type': 'application/json', 'X-Admin-Session': '1' },
})

async function unwrap<T>(promise: Promise<{ data: T }>): Promise<T> {
  try {
    const response = await promise
    return response.data
  } catch (error) {
    const message = axios.isAxiosError(error) ? (error.response?.data?.message || error.message) : error instanceof Error ? error.message : '请求失败，请稍后重试。'
    throw new AdminApiError(message, axios.isAxiosError(error) ? error.response?.status : undefined)
  }
}

export const adminApi = {
  login(username: string, password: string) {
    return unwrap<{ user: AdminSessionUser }>(http.post('/auth/login', { username, password }))
  },
  session() { return unwrap<AdminSessionUser>(http.get('/auth/session')) },
  logout() { return unwrap<{ ok: boolean }>(http.post('/auth/logout')) },
  changePassword(oldPassword: string, newPassword: string) { return unwrap<{ok: boolean}>(http.post('/auth/password', { oldPassword, newPassword })) },
  users(params: { role: MiniProgramRole | ''; keyword: string; page: number; pageSize: number }, signal: AbortSignal) {
    return unwrap<{ rows: MiniProgramUser[]; total: number; page: number; pageSize: number }>(
      http.get('/users', { params, signal }),
    )
  },
  administrators() { return unwrap<{ users: Array<{id: number; username: string; enabled: boolean; roles: string}>; roles: Array<{code: string; name: string}> }>(http.get('/administrators')) },
  createAdministrator(data: { username: string; password: string; role: string }) { return unwrap<{ok: boolean}>(http.post('/administrators', data)) },
  updateAdministrator(id: number, data: { role: string; enabled: boolean }) { return unwrap<{ok: boolean}>(http.put(`/administrators/${id}/access`, data)) },
  resetAdministratorPassword(id: number, password: string) { return unwrap<{ok: boolean}>(http.put(`/administrators/${id}/password`, { password })) },
  overview() {
    return unwrap(http.get('/overview'))
  },
  workspace<T = unknown>(section: SectionKey) {
    return unwrap<T>(http.get('/workspace', { params: { section } }))
  },
  orderPickupQr(id: string, signal: AbortSignal) {
    return unwrap<Blob>(http.get(`/orders/${encodeURIComponent(id)}/pickup-qr`, { responseType: 'blob', signal }))
  },
  async settlementSnapshots(id: string) {
    type Row = Omit<SettlementSnapshot, 'rules'> & { ruleDetails: string }
    const { rows } = await unwrap<{ rows: Row[] }>(http.get(`/orders/${encodeURIComponent(id)}/settlement-snapshots`))
    return rows.map(({ ruleDetails, ...row }) => ({ ...row, rules: JSON.parse(ruleDetails) as Pricing }))
  },
  merchantPhoto(id: string, signal?: AbortSignal) {
    return unwrap<Blob>(http.get(`/merchants/${encodeURIComponent(id)}/photo`, { responseType: 'blob', signal }))
  },
  merchantPricing(id: string) {
    return unwrap<{ version: number; enabled: boolean; pricing?: Pricing }>(
      http.get(`/merchants/${encodeURIComponent(id)}/pricing`))
  },
  merchantPrinter(id: string) {
    return unwrap<MerchantPrinter>(http.get(`/merchants/${encodeURIComponent(id)}/printer`))
  },
  saveMerchantPrinter(id: string, sn: string, pkey: string) {
    return unwrap<MerchantPrinter>(http.post('/actions', {
      type: 'merchant.save-printer', payload: { id, sn, pkey },
    }))
  },
  riderStudentCard(id: string) {
    return unwrap<Blob>(http.get(`/riders/${encodeURIComponent(id)}/student-card`, { responseType: 'blob' }))
  },
  products(params: { keyword?: string; merchantId?: string; availability?: string; page?: number; pageSize?: number }) {
    return unwrap<{ rows: Array<Record<string, unknown>>; total: number; page: number; pageSize: number }>(http.get('/products', { params }))
  },
  reorderProducts(merchantId: string, order: RowOrder) {
    return unwrap<void>(http.put(`/products/merchants/${encodeURIComponent(merchantId)}/order`, order))
  },
  productPhoto(id: string, signal?: AbortSignal) {
    return unwrap<Blob>(http.get(`/products/${encodeURIComponent(id)}/photo`, { responseType: 'blob', signal }))
  },
  saveProduct(data: ProductWrite, id?: string) {
    return unwrap<void>(id
      ? http.put(`/products/${encodeURIComponent(id)}`, data)
      : http.post('/products', data))
  },
  setProductAvailability(id: string, available: boolean) {
    return unwrap<void>(http.put(`/products/${encodeURIComponent(id)}/availability`, { available }))
  },
  deleteProduct(id: string) {
    return unwrap<void>(http.delete(`/products/${encodeURIComponent(id)}`))
  },
  foodCategories() {
    return unwrap<FoodCategory[]>(http.get('/food-categories'))
  },
  saveFoodCategory(name: string, id?: number) {
    return unwrap<void>(id === undefined
      ? http.post('/food-categories', { name })
      : http.put(`/food-categories/${id}`, { name }))
  },
  deleteFoodCategory(id: number) {
    return unwrap<void>(http.delete(`/food-categories/${id}`))
  },
  homeBanners() {
    return unwrap<HomeBanner[]>(http.get('/home-banners'))
  },
  homeBannerImage(id: number) {
    return unwrap<Blob>(http.get(`/home-banners/${id}/image`, { responseType: 'blob' }))
  },
  saveHomeBanner(body: FormData, id?: number) {
    return unwrap<void>(id === undefined
      ? http.postForm('/home-banners', body)
      : http.putForm(`/home-banners/${id}`, body))
  },
  deleteHomeBanner(id: number) {
    return unwrap<void>(http.delete(`/home-banners/${id}`))
  },
  action<T = unknown>(type: string, payload: Record<string, unknown>) {
    return unwrap<T>(http.post('/actions', { type, payload }))
  },
}
