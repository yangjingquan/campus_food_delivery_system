import { defineStore } from 'pinia'
import { ref } from 'vue'
import { AdminApiError, adminApi, http, type AdminSessionUser } from '../api/admin'

export const useAuthStore = defineStore('auth', () => {
  const authenticated = ref(false)
  const name = ref('管理员')
  const role = ref('管理员')
  const permissions = ref<string[]>([])
  let restored = false
  let restoring: Promise<void> | undefined
  function can(permission: string) { return permissions.value.includes(permission) }

  function setUser(user: AdminSessionUser) {
    name.value = user.name
    role.value = user.role
    permissions.value = user.permissions
    authenticated.value = true
  }

  function clear() {
    authenticated.value = false
    name.value = '管理员'
    role.value = '管理员'
    permissions.value = []
  }

  async function restore() {
    if (restored) return
    restoring ??= adminApi.session().then(setUser).catch((error) => {
      if (!(error instanceof AdminApiError && error.status === 401)) throw error
    }).finally(() => {
      restored = true
      restoring = undefined
    })
    await restoring
  }

  async function login(username: string, password: string) {
    const result = await adminApi.login(username, password)
    setUser(result.user)
  }

  async function logout() {
    await adminApi.logout()
    clear()
  }

  http.interceptors.response.use(undefined, (error) => {
    if (error.response?.status === 401 && error.config?.url !== '/auth/login') clear()
    return Promise.reject(error)
  })

  return { authenticated, name, role, permissions, can, restore, login, logout, clear }
})
