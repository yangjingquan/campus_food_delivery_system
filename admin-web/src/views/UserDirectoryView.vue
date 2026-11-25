<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { adminApi, type MiniProgramRole, type MiniProgramUser } from '../api/admin'
import ListPagination from '../components/ListPagination.vue'
import { stateLabel } from '../domain/businessStates'

const roles = [
  { id: 'user', label: '用户端', field: 'nickname' },
  { id: 'merchant', label: '商户端', field: 'merchantStatus' },
  { id: 'rider', label: '骑手端', field: 'riderStatus' },
] as const

const affiliations = [
  { name: 'merchantName', status: 'merchantStatus', label: '商户名称' },
  { name: 'riderName', status: 'riderStatus', label: '骑手姓名' },
] as const

const role = ref<MiniProgramRole | ''>('')
const keyword = ref('')
const filters = ref({ role: role.value, keyword: '' })
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const rows = ref<MiniProgramUser[]>([])
const loading = ref(false)
const error = ref('')
const narrowViewport = window.matchMedia('(max-width: 680px)')
const compact = ref(narrowViewport.matches)
let request: AbortController | undefined

const users = computed(() => rows.value.map(row => ({
  ...row,
  roles: roles.filter(({ field }) => row[field] != null),
})))
const emptyDescription = computed(() => error.value
  || (filters.value.role || filters.value.keyword ? '没有符合条件的用户' : '暂无小程序用户'))

function search() {
  page.value = 1
  filters.value = { role: role.value, keyword: keyword.value.trim() }
}

function reset() {
  role.value = ''
  keyword.value = ''
  search()
}

function statusLabel(status: string | null) {
  if (!status) return '—'
  return status === 'REMOVED' ? '已移除' : stateLabel(status)
}

async function load() {
  request?.abort()
  const current = new AbortController()
  request = current
  loading.value = true
  error.value = ''
  try {
    const result = await adminApi.users({ ...filters.value, page: page.value, pageSize: pageSize.value }, current.signal)
    if (current.signal.aborted) return
    rows.value = result.rows
    total.value = result.total
    page.value = result.page
  } catch (cause) {
    if (current.signal.aborted) return
    rows.value = []
    total.value = 0
    error.value = cause instanceof Error ? cause.message : '用户列表读取失败'
  } finally {
    if (!current.signal.aborted) loading.value = false
  }
}

watch([filters, page, pageSize], load, { immediate: true })

function updateViewport(event: MediaQueryListEvent) {
  compact.value = event.matches
}

onMounted(() => narrowViewport.addEventListener('change', updateViewport))
onBeforeUnmount(() => {
  narrowViewport.removeEventListener('change', updateViewport)
  request?.abort()
})
</script>

<template>
  <div class="page-heading">
    <h1>用户列表</h1>
  </div>

  <section class="panel-card workbench-panel" aria-label="小程序用户">
    <div class="panel-toolbar">
      <form class="directory-filters" @submit.prevent="search">
        <el-input v-model="keyword" class="directory-search" :prefix-icon="Search" clearable maxlength="120"
          placeholder="搜索昵称、商户名称或骑手姓名" aria-label="搜索昵称、商户名称或骑手姓名" />
        <el-select v-model="role" class="directory-role" placeholder="全部角色" aria-label="按角色筛选">
          <el-option label="全部角色" value="" />
          <el-option v-for="option in roles" :key="option.id" :label="option.label" :value="option.id" />
        </el-select>
        <div class="directory-actions">
          <el-button type="primary" native-type="submit">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </div>
      </form>
    </div>

    <el-table v-if="!compact" :data="users" v-loading="loading" row-key="id" stripe class="data-table directory-table">
      <el-table-column label="用户昵称" min-width="136">
        <template #default="{ row }">
          <span v-if="row.nickname != null" class="directory-name">{{ row.nickname }}</span>
          <span v-else class="directory-muted">未登录用户端</span>
        </template>
      </el-table-column>
      <el-table-column label="角色" min-width="152">
        <template #default="{ row }">
          <div class="directory-tags">
            <span v-for="tag in row.roles" :key="tag.id" class="directory-tag" :class="'directory-tag-' + tag.id">{{ tag.label }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-for="item in affiliations" :key="item.name" :label="item.label" min-width="140">
        <template #default="{ row }">
          <div v-if="row[item.status] != null" class="directory-affiliation">
            <span v-if="row[item.name]" class="directory-name">{{ row[item.name] }}</span>
            <small class="directory-muted">{{ statusLabel(row[item.status]) }}</small>
          </div>
          <span v-else class="directory-muted">—</span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="emptyDescription">
          <el-button v-if="error" @click="load">重新加载</el-button>
        </el-empty>
      </template>
    </el-table>
    <div v-else v-loading="loading" class="directory-mobile">
      <ul v-if="users.length" class="directory-items">
        <li v-for="user in users" :key="user.id">
          <div :class="user.nickname == null ? 'directory-muted' : 'directory-name'">
            {{ user.nickname ?? '未登录用户端' }}
          </div>
          <div class="directory-tags">
            <span v-for="tag in user.roles" :key="tag.id" class="directory-tag" :class="'directory-tag-' + tag.id">{{ tag.label }}</span>
          </div>
          <dl v-if="user.merchantStatus || user.riderStatus" class="directory-details">
            <template v-for="item in affiliations" :key="item.name">
              <div v-if="user[item.status]" class="directory-detail">
                <dt>{{ item.label }}</dt>
                <dd>
                  <span v-if="user[item.name]" class="directory-name">{{ user[item.name] }}</span>
                  <small class="directory-muted">{{ statusLabel(user[item.status]) }}</small>
                </dd>
              </div>
            </template>
          </dl>
        </li>
      </ul>
      <el-empty v-else :description="emptyDescription">
        <el-button v-if="error" @click="load">重新加载</el-button>
      </el-empty>
    </div>
    <ListPagination v-if="!error" v-model:page="page" v-model:page-size="pageSize" :total="total"
      :disabled="loading" label="用户列表分页" />
  </section>
</template>

<style scoped>
.directory-filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  width: 100%;
  gap: 10px;
}
.directory-search { width: min(320px, 100%); }
.directory-role { width: 132px; }
.directory-actions { display: flex; gap: 8px; }
.directory-actions .el-button + .el-button { margin-left: 0; }
.data-table.directory-table { min-width: 0; }
.directory-tags { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 6px; }
.directory-tag {
  padding: 3px 8px;
  border-radius: 5px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
  white-space: nowrap;
}
.directory-tag-user {
  color: #37536f;
  background: #edf4fb;
}
.directory-tag-merchant {
  color: #146046;
  background: #e8f5ee;
}
.directory-tag-rider {
  color: #825012;
  background: #fff4e5;
}
.directory-affiliation { display: grid; gap: 2px; }
.directory-name { color: #24354b; font-weight: 600; overflow-wrap: anywhere; }
.directory-muted { color: #5d6f86; }
@media (max-width: 680px) {
  .directory-items { margin: 0; padding: 0; list-style: none; }
  .directory-items > li { display: grid; gap: 8px; padding: 14px 12px; }
  .directory-items > li + li { border-top: 1px solid var(--line); }
  .directory-details { display: grid; gap: 6px; margin: 0; font-size: 13px; }
  .directory-detail { display: grid; grid-template-columns: 5em minmax(0, 1fr); gap: 8px; }
  .directory-detail dt { color: #5d6f86; }
  .directory-detail dd { display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 7px; margin: 0; }
}

@media (max-width: 480px) {
  .directory-search { width: 100%; }
}
</style>
