<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElAlert, ElCheckbox, ElCheckboxGroup, ElMessage, ElMessageBox } from 'element-plus'
import { adminApi, type AdminAccessSettings, type AdminAuditEntry, type Administrator, type AdminRole } from '../api/admin'
import ListPagination from '../components/ListPagination.vue'
import { useListPagination } from '../domain/useListPagination'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const tab = ref<'accounts' | 'roles' | 'audit'>('accounts')
const settings = ref<AdminAccessSettings>()
const accounts = computed(() => settings.value?.users ?? [])
const roles = computed(() => settings.value?.roles ?? [])
const roleNames = computed(() => new Map(roles.value.map(role => [role.code, role.name])))
const loading = ref(false)
const loadError = ref('')
const keyword = ref('')
const shownAccounts = computed(() => {
  const term = keyword.value.trim().toLowerCase()
  return accounts.value.filter(account => account.username.toLowerCase().includes(term))
})
const { page, pageSize, total, pageRows } = useListPagination(() => shownAccounts.value)
watch(keyword, () => { page.value = 1 })

const audit = ref<AdminAuditEntry[]>([])
const auditLoading = ref(false)
const auditError = ref('')
const actionLabels: Record<string, string> = {
  'admin.create': '创建管理员',
  'admin.access': '调整角色或账号状态',
  'admin.password': '修改本人密码',
  'admin.password-reset': '重置管理员密码',
  'admin.role.create': '创建角色',
  'admin.role.update': '调整角色',
  'admin.role.delete': '删除角色',
}
const dateFormat = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium', hour12: false })

const accountDialog = ref(false)
const editingAccount = ref<Administrator>()
const accountDraft = reactive({ username: '', password: '', roleCodes: [] as string[], enabled: true })
const targetPassword = ref<Administrator>()
const passwordDialog = ref(false)
const passwordDraft = ref('')
const roleDialog = ref(false)
const editingRole = ref<AdminRole>()
const roleDraft = reactive({ name: '', description: '', permissions: [] as string[] })
const dialogError = ref('')
const saving = ref(false)
const lockedRole = computed(() => editingRole.value?.editable === false)
const enabledSupers = computed(() => accounts.value.filter(account => account.enabled && account.roleCodes.includes('super')).length)
const lastSuper = computed(() => editingAccount.value?.enabled &&
  editingAccount.value.roleCodes.includes('super') && enabledSupers.value === 1)
const groups = [
  { label: '概览与用户', modules: ['overview', 'user'] },
  { label: '商户与商品', modules: ['merchant', 'product', 'food-category'] },
  { label: '骑手管理', modules: ['rider'] },
  { label: '订单与售后', modules: ['order', 'refund'] },
  { label: '资金管理', modules: ['finance', 'withdrawal'] },
  { label: '运营与系统设置', modules: ['config', 'home-banner', 'wechat', 'admin'] },
]
const permissionGroups = computed(() => {
  const permissions = (settings.value?.permissions ?? []).filter(permission => lockedRole.value || permission.code !== 'admin.manage')
  const assigned = (code: string) => groups.some(group => group.modules.includes(code.split('.')[0]))
  return [
    ...groups.map(group => ({
      label: group.label,
      permissions: permissions.filter(permission => group.modules.includes(permission.code.split('.')[0])),
    })),
    { label: '其他权限', permissions: permissions.filter(permission => !assigned(permission.code)) },
  ].filter(group => group.permissions.length)
})
const requiredViews = computed(() => {
  const lookup = settings.value?.requiredView ?? {}
  return new Set(roleDraft.permissions.flatMap(code => lookup[code] ? [lookup[code]] : []))
})

async function load() {
  loading.value = true
  loadError.value = ''
  try { settings.value = await adminApi.administrators() }
  catch (error) { loadError.value = error instanceof Error ? error.message : '加载管理员与角色失败' }
  finally { loading.value = false }
}

async function loadAudit() {
  auditLoading.value = true
  auditError.value = ''
  try { audit.value = await adminApi.administratorAudit() }
  catch (error) { auditError.value = error instanceof Error ? error.message : '加载操作记录失败' }
  finally { auditLoading.value = false }
}
watch(tab, value => { if (value === 'audit') void loadAudit() })
onMounted(load)

function openAccount(account?: Administrator) {
  editingAccount.value = account
  Object.assign(accountDraft, {
    username: account?.username ?? '', password: '', roleCodes: [...(account?.roleCodes ?? [])], enabled: account?.enabled ?? true,
  })
  dialogError.value = ''
  accountDialog.value = true
}

function openRole(role?: AdminRole) {
  editingRole.value = role
  Object.assign(roleDraft, { name: role?.name ?? '', description: role?.description ?? '', permissions: [...(role?.permissions ?? [])] })
  dialogError.value = ''
  roleDialog.value = true
}

function openPassword(account: Administrator) {
  targetPassword.value = account
  passwordDraft.value = ''
  dialogError.value = ''
  passwordDialog.value = true
}

function normalizePermissions() {
  roleDraft.permissions = [...new Set([...roleDraft.permissions, ...requiredViews.value])]
}

async function submit(action: () => Promise<unknown>, success: () => void) {
  if (saving.value) return
  saving.value = true
  dialogError.value = ''
  try { await action(); success() }
  catch (error) { dialogError.value = error instanceof Error ? error.message : '保存失败，请重试。' }
  finally { saving.value = false }
}

function validatePassword(password: string) {
  if (password.length < 12 || new TextEncoder().encode(password).length > 72) {
    throw new Error('密码至少 12 个字符，UTF-8 编码不超过 72 字节。')
  }
}

async function saveAccount() {
  const account = editingAccount.value
  const { roleCodes, enabled, username, password } = accountDraft
  await submit(async () => {
    if (!roleCodes.length) throw new Error('请为管理员选择至少一个角色。')
    if (account) {
      if (lastSuper.value && (!enabled || !roleCodes.includes('super'))) {
        throw new Error('必须保留至少一名启用的超级管理员。')
      }
      await adminApi.updateAdministrator(account.id, { roleCodes, enabled })
    } else {
      if (!/^[A-Za-z0-9_.-]{3,64}$/.test(username)) {
        throw new Error('账号须为 3–64 位字母、数字、点、下划线或短横线。')
      }
      validatePassword(password)
      await adminApi.createAdministrator({ username, password, roleCodes })
    }
  }, () => {
    accountDialog.value = false
    ElMessage.success(account ? '管理员权限已保存' : '管理员已添加')
    void load()
  })
}

async function savePassword() {
  const account = targetPassword.value
  if (!account) return
  await submit(async () => {
    validatePassword(passwordDraft.value)
    await adminApi.resetAdministratorPassword(account.id, passwordDraft.value)
  }, () => {
    passwordDialog.value = false
    ElMessage.success('密码已重置')
  })
}

async function saveRole() {
  await submit(async () => {
    if (!roleDraft.name.trim()) throw new Error('请输入角色名称。')
    if (!roleDraft.permissions.length) throw new Error('请选择至少一项权限。')
    normalizePermissions()
    await adminApi.saveRole({ ...roleDraft }, editingRole.value?.code)
  }, () => {
    roleDialog.value = false
    ElMessage.success('角色已保存')
    void load()
  })
}

async function deleteRole(role: AdminRole) {
  try {
    await ElMessageBox.confirm(`确定删除「${role.name}」？`, '删除角色', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch { return }
  try {
    await adminApi.deleteRole(role.code)
    ElMessage.success('角色已删除')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除角色失败')
  }
}
</script>

<template>
  <div class="page-heading"><h1>管理员与角色</h1></div>
  <section class="panel-card access-panel" aria-label="后台权限设置">
    <el-tabs v-model="tab">
      <el-tab-pane label="管理员" name="accounts">
        <div class="access-toolbar">
          <el-input v-model="keyword" clearable aria-label="搜索管理员账号" placeholder="搜索管理员账号" />
          <el-button type="primary" :disabled="!settings" @click="openAccount()">添加管理员</el-button>
        </div>
        <el-alert v-if="loadError" type="error" :closable="false" :title="loadError" show-icon class="access-error">
          <el-button link type="primary" @click="load">重新加载</el-button>
        </el-alert>
        <el-table :data="pageRows" v-loading="loading" row-key="id" empty-text="暂无匹配的管理员">
          <el-table-column label="管理员账号" min-width="140">
            <template #default="{ row }">
              <strong>{{ row.username }}</strong>
              <span v-if="row.username === auth.name" class="access-muted">（本人）</span>
            </template>
          </el-table-column>
          <el-table-column label="角色" min-width="220">
            <template #default="{ row }">
              <div v-if="row.roleCodes.length" class="role-tags">
                <el-tag v-for="code in row.roleCodes" :key="code" :type="code === 'super' ? 'primary' : 'info'" size="small">
                  {{ roleNames.get(code) ?? code }}
                </el-tag>
              </div>
              <span v-else class="access-muted">未分配</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="92">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="186">
            <template #default="{ row }">
              <el-button link type="primary" @click="openAccount(row as Administrator)">编辑权限</el-button>
              <el-button v-if="row.username !== auth.name" link @click="openPassword(row as Administrator)">重置密码</el-button>
            </template>
          </el-table-column>
        </el-table>
        <ListPagination v-model:page="page" v-model:page-size="pageSize" :total="total" :disabled="loading" />
      </el-tab-pane>

      <el-tab-pane label="角色与权限" name="roles">
        <div class="access-toolbar roles-toolbar">
          <el-button type="primary" :disabled="!settings" @click="openRole()">新建角色</el-button>
        </div>
        <el-alert v-if="loadError" type="error" :closable="false" :title="loadError" show-icon class="access-error">
          <el-button link type="primary" @click="load">重新加载</el-button>
        </el-alert>
        <el-table :data="roles" v-loading="loading" row-key="code" empty-text="暂无角色">
          <el-table-column label="角色名称" min-width="180">
            <template #default="{ row }">
              <div class="role-tags">
                <strong>{{ row.name }}</strong>
                <el-tag v-if="row.builtIn" type="info" size="small">内置</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="200" />
          <el-table-column label="权限" width="100">
            <template #default="{ row }">{{ row.editable ? `${row.permissions.length} 项` : '全部' }}</template>
          </el-table-column>
          <el-table-column label="管理员" width="90">
            <template #default="{ row }">{{ row.memberCount }} 人</template>
          </el-table-column>
          <el-table-column label="操作" width="154">
            <template #default="{ row }">
              <el-button link type="primary" @click="openRole(row as AdminRole)">{{ row.editable ? '编辑权限' : '查看权限' }}</el-button>
              <el-button v-if="!row.builtIn" link type="danger" :disabled="row.memberCount > 0"
                :title="row.memberCount ? '请先解除全部管理员关联' : undefined" @click="deleteRole(row as AdminRole)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="操作记录" name="audit" lazy>
        <div class="access-toolbar">
          <span class="access-muted">最近 100 条账号与角色变更</span>
          <el-button :loading="auditLoading" @click="loadAudit">刷新</el-button>
        </div>
        <el-alert v-if="auditError" type="error" :closable="false" :title="auditError" show-icon class="access-error" />
        <el-table :data="audit" v-loading="auditLoading" row-key="id" empty-text="暂无操作记录">
          <el-table-column label="时间" width="190">
            <template #default="{ row }">{{ dateFormat.format(new Date(row.createdAt)) }}</template>
          </el-table-column>
          <el-table-column prop="actor" label="操作人" min-width="120" />
          <el-table-column label="操作" min-width="155">
            <template #default="{ row }">{{ actionLabels[row.action] ?? row.action }}</template>
          </el-table-column>
          <el-table-column prop="target" label="目标账号或角色" min-width="160" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </section>

  <el-dialog v-model="accountDialog" :title="editingAccount ? `编辑权限 · ${editingAccount.username}` : '添加管理员'"
    width="min(480px, 94vw)" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving"
    @closed="accountDraft.password = ''">
    <el-form label-position="top" @submit.prevent="saveAccount">
      <template v-if="!editingAccount">
        <el-form-item label="管理员账号" required>
          <el-input v-model.trim="accountDraft.username" maxlength="64" autocomplete="off" />
        </el-form-item>
        <el-form-item label="初始密码（至少 12 个字符）" required>
          <el-input v-model="accountDraft.password" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </template>
      <el-form-item label="角色" required>
        <el-select v-model="accountDraft.roleCodes" multiple placement="top-start" placeholder="选择一个或多个角色">
          <el-option v-for="role in roles" :key="role.code" :label="role.name" :value="role.code"
            :disabled="lastSuper && role.code === 'super'" />
        </el-select>
      </el-form-item>
      <template v-if="editingAccount">
        <el-form-item label="账号状态">
          <el-switch v-model="accountDraft.enabled" :disabled="lastSuper" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <p class="access-muted">{{ lastSuper ? '此账号是当前唯一启用的超级管理员。' : '权限或状态变更后，该账号需要重新登录。' }}</p>
      </template>
      <p v-if="dialogError" class="access-error-text" role="alert">{{ dialogError }}</p>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="accountDialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveAccount">{{ editingAccount ? '保存' : '添加' }}</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="passwordDialog" :title="`重置密码 · ${targetPassword?.username ?? ''}`" width="min(460px, 94vw)"
    :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" @closed="passwordDraft = ''">
    <el-form label-position="top" @submit.prevent="savePassword">
      <p class="access-muted">重置后，该账号的所有登录会话将失效。</p>
      <el-form-item label="新密码（至少 12 个字符）" required>
        <el-input v-model="passwordDraft" type="password" show-password autocomplete="new-password" />
      </el-form-item>
      <p v-if="dialogError" class="access-error-text" role="alert">{{ dialogError }}</p>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="passwordDialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="savePassword">重置密码</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="roleDialog" :title="lockedRole ? '超级管理员权限' : editingRole ? `编辑角色 · ${editingRole.name}` : '新建角色'"
    width="min(720px, 94vw)" align-center :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving">
    <el-form label-position="top" @submit.prevent="saveRole">
      <template v-if="!lockedRole">
        <el-form-item label="角色名称" required>
          <el-input v-model.trim="roleDraft.name" maxlength="60" />
        </el-form-item>
        <el-form-item label="角色说明">
          <el-input v-model.trim="roleDraft.description" maxlength="200" type="textarea" :rows="2" />
        </el-form-item>
      </template>
      <p class="access-muted">
        {{ lockedRole ? '此角色由系统维护，拥有后台全部权限。' : '选中操作权限时会自动勾选对应的查看权限。' }}
      </p>
      <el-checkbox-group v-model="roleDraft.permissions" class="permission-grid" aria-label="角色权限"
        :disabled="lockedRole" @change="normalizePermissions">
        <fieldset v-for="group in permissionGroups" :key="group.label">
          <legend>{{ group.label }}</legend>
          <el-checkbox v-for="permission in group.permissions" :key="permission.code" :value="permission.code"
            :disabled="lockedRole || requiredViews.has(permission.code)">{{ permission.description }}</el-checkbox>
        </fieldset>
      </el-checkbox-group>
      <p v-if="dialogError" class="access-error-text" role="alert">{{ dialogError }}</p>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="roleDialog = false">{{ lockedRole ? '关闭' : '取消' }}</el-button>
      <el-button v-if="!lockedRole" type="primary" :loading="saving" @click="saveRole">保存角色</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.access-panel { padding: 0 18px 12px; }
.access-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin: 4px 0 16px; }
.access-toolbar .el-input { max-width: 280px; }
.roles-toolbar { justify-content: flex-end; }
.role-tags { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; padding: 2px 0; }
.access-muted { color: #5e6e82; font-size: 13px; }
.access-error { margin-bottom: 12px; }
.access-error-text { color: var(--el-color-danger-dark-2, #a92538); margin: 12px 0 0; }
.permission-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; max-height: min(45vh, 420px); overflow: auto; padding: 3px; }
.permission-grid fieldset { display: flex; flex-direction: column; min-width: 0; margin: 0; border: 1px solid var(--el-border-color); border-radius: 8px; padding: 4px 12px 10px; }
.permission-grid legend { color: var(--el-text-color-primary); font-size: 13px; font-weight: 600; line-height: 1.4; padding: 0 4px; }
.permission-grid .el-checkbox { margin-right: 0; }
@media (max-width: 600px) {
  .access-panel { padding: 0 12px 8px; }
  .permission-grid { grid-template-columns: 1fr; }
}
</style>
