<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { adminApi, type MiniProgramUser } from '../api/admin'

const props = defineProps<{ role: 'merchant' | 'rider' }>()
const model = defineModel<string>({ required: true })
const accounts = ref<MiniProgramUser[]>([])
const loading = ref(false)
const error = ref('')
let request: AbortController | undefined

async function search(keyword = '') {
  request?.abort()
  const current = new AbortController()
  request = current
  loading.value = true
  error.value = ''
  try {
    const result = await adminApi.users({ role: 'user', keyword: keyword.trim(), page: 1, pageSize: 20 }, current.signal)
    if (!current.signal.aborted) accounts.value = result.rows
  } catch (cause) {
    if (current.signal.aborted) return
    accounts.value = []
    error.value = cause instanceof Error ? cause.message : '小程序账号查询失败'
  } finally {
    if (!current.signal.aborted) loading.value = false
  }
}

function onOpen(open: boolean) {
  if (open && !loading.value && !accounts.value.length) void search()
}

function conflict(account: MiniProgramUser) {
  if (props.role === 'rider') return account.riderId ? '已关联骑手 ' + account.riderName : ''
  if (account.merchantId) return '已关联商户 ' + account.merchantName
  return account.merchantStatus === 'PENDING_REVIEW' ? '有待审入驻申请，请从申请中关联' : ''
}

function label(account: MiniProgramUser) {
  return [account.nickname || '同学', '用户编号 ' + account.userNumber, conflict(account)].filter(Boolean).join(' · ')
}

onBeforeUnmount(() => request?.abort())
</script>

<template>
  <el-select v-model="model" aria-label="小程序账号" placeholder="搜索用户编号或昵称" filterable remote clearable
    :remote-method="search" :loading="loading" :no-data-text="error || '没有匹配的账号，请确认对方已登录用户端'"
    @visible-change="onOpen">
    <el-option v-for="account in accounts" :key="account.id" :label="label(account)"
      :value="account.userNumber || ''" :disabled="!account.userNumber || !!conflict(account)" />
  </el-select>
</template>
