import axios from 'axios'
import type { SectionKey } from '../domain/types'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/admin/v1',
  timeout: 12_000,
  headers: { 'Content-Type': 'application/json' },
})

async function unwrap<T>(promise: Promise<{ data: T }>): Promise<T> {
  try {
    const response = await promise
    return response.data
  } catch (error) {
    const message = axios.isAxiosError(error) ? (error.response?.data?.message || error.message) : error instanceof Error ? error.message : '请求失败，请稍后重试。'
    throw new Error(message)
  }
}

export const adminApi = {
  login(username: string, password: string) {
    return unwrap<{ token: string; user: { name: string; role: string; permissions: string[] } }>(http.post('/auth/login', { username, password }))
  },
  logout() { return unwrap<{ ok: boolean }>(http.post('/auth/logout')) },
  changePassword(oldPassword: string, newPassword: string) { return unwrap<{ok: boolean}>(http.post('/auth/password', { oldPassword, newPassword })) },
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
  merchantPhoto(id: string) {
    return unwrap<Blob>(http.get(`/merchants/${encodeURIComponent(id)}/photo`, { responseType: 'blob' }))
  },
  products(params: { keyword?: string; merchantId?: string; availability?: string; page?: number; pageSize?: number }) {
    return unwrap<{ rows: Array<Record<string, unknown>>; total: number; page: number; pageSize: number }>(http.get('/products', { params }))
  },
  productPhoto(id: string) {
    return unwrap<Blob>(http.get(`/products/${encodeURIComponent(id)}/photo`, { responseType: 'blob' }))
  },
  action<T = unknown>(type: string, payload: Record<string, unknown>) {
    return unwrap<T>(http.post('/actions', { type, payload }))
  },
}
