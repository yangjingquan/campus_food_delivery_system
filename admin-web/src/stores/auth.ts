import { defineStore } from 'pinia'
import { ref } from 'vue'
import { adminApi, http } from '../api/admin'

export const useAuthStore = defineStore('auth', () => {
  const token = ref('')
  const name = ref('管理员')
  const role = ref('管理员')
  const permissions = ref<string[]>([])
  function can(permission: string) { return permissions.value.includes(permission) }

  async function login(username: string, password: string) {
    const result = await adminApi.login(username, password)
    token.value = result.token
    http.defaults.headers.common.Authorization = `Bearer ${result.token}`
    name.value = result.user.name
    role.value = result.user.role
    permissions.value = result.user.permissions
  }

  function logout() {
    void adminApi.logout().catch(() => {})
    token.value = ''
    permissions.value = []
    delete http.defaults.headers.common.Authorization
  }
  return { token, name, role, permissions, can, login, logout }
})
