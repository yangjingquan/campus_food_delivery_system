<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { adminApi, type MerchantPrinter } from '../api/admin'
import ListPagination from '../components/ListPagination.vue'
import { useListPagination } from '../domain/useListPagination'
import { useAuthStore } from '../stores/auth'
import { money } from '../domain/rules'
import { interventionLabel, stateLabel } from '../domain/businessStates'
import type { FinanceSection } from '../router'
import type { Order, SectionKey } from '../domain/types'

type Row = Record<string, any>
const route = useRoute()
const auth = useAuthStore()
const router = useRouter()
const financeSection = computed(() => route.meta.financeSection as FinanceSection | undefined)
const section = computed(() => financeSection.value ? 'finance' : String(route.name || 'orders') as SectionKey)
const loading = ref(false)
const rows = ref<Row[]>([])
const relatedOrders = ref<Order[]>([])
const keyword = ref('')
const statusFilter = ref('')
const finance = ref<Record<FinanceSection, Row[]>>({ accounts: [], ledger: [], debts: [], journal: [] })
let loadRequest = 0
const drawer = ref(false)
const printerDialog = ref(false)
const printerLoadingId = ref('')
const printerSaving = ref(false)
const retryingWithdrawalId = ref('')
const printerTarget = ref<{ id: string; name: string; saved: MerchantPrinter }>()
const printerForm = reactive({ sn: '', pkey: '' })
const printerNeedsPkey = computed(() => !printerTarget.value?.saved.pkeySet || printerForm.sn.trim() !== printerTarget.value.saved.sn)
const printerChanged = computed(() => printerForm.sn.trim() !== printerTarget.value?.saved.sn || !!printerForm.pkey)
const activeRow = ref<Row | null>(null)
const merchantPhoto = ref('')
const photoError = ref('')
let photoRequest = 0
const keyNames: Record<string, string> = {
  originalWithdrawalId: '原提现编号',
  id: '编号', name: '名称', contact: '联系人', phone: '联系方式', address: '地址', auditStatus: '审核状态', auditReason: '审核说明', businessStatus: '营业状态', orderCount: '订单数', joinedAt: '登记时间',
  gender: '性别', availableTime: '可送餐时间', deliveries: '配送单数', balanceCents: '可提现余额', paymentId: '合并支付单号', merchantId: '商户编号', merchant: '商户', rider: '骑手', items: '商品摘要', foodCents: '餐品金额', deliveryCents: '配送费', paidCents: '用户实付', refundedCents: '累计退款', foodRefundedCents: '累计退餐品', paymentStatus: '支付状态', fulfillStatus: '履约状态', refundStatus: '售后状态', settlementStatus: '结算状态', accepted: '商户已接单', pendingRefund: '存在处理中退款', locking: '结算锁定', paymentCount: '支付单退款次数', paidAt: '支付时间', completedAt: '完成时间', closedAt: '关闭时间', intervene: '待管理员介入', interveneReason: '介入原因', coordinationNotes: '协调记录',
  reason: '说明', type: '类型', amountCents: '金额', status: '处理状态', channelStatus: '渠道状态', createdAt: '创建时间', operator: '操作人', applicant: '申请主体', applicantType: '主体类型', time: '时间', subject: '账务主体', foodAmount: '餐品金额', deliveryAmount: '配送费', paidAmount: '实付金额', refundAmount: '退款金额', settlement: '结算状态', riderDue: '骑手应得', merchantBalance: '商户可扣余额', platformAdvance: '平台垫付', recovered: '已扣回', note: '说明',
}

const title = computed(() => String(route.meta.title || '订单列表'))
const columnSets: Record<string, Array<{ key: string; label: string; minWidth?: number }>> = {
  merchants: [
    { key: 'id', label: '商户编号', minWidth: 100 }, { key: 'name', label: '商户名称', minWidth: 140 },
    { key: 'contact', label: '联系人', minWidth: 100 }, { key: 'phone', label: '联系方式', minWidth: 130 },
    { key: 'auditStatus', label: '审核状态', minWidth: 105 }, { key: 'businessStatus', label: '营业状态', minWidth: 100 },
    { key: 'orderCount', label: '订单数', minWidth: 85 }, { key: 'joinedAt', label: '入驻时间', minWidth: 150 },
  ],
  riders: [
    { key: 'id', label: '骑手编号', minWidth: 100 }, { key: 'name', label: '姓名', minWidth: 110 },
    { key: 'phone', label: '联系方式', minWidth: 130 }, { key: 'gender', label: '性别', minWidth: 70 },
    { key: 'availableTime', label: '可送餐时间', minWidth: 220 }, { key: 'auditStatus', label: '审核状态', minWidth: 105 },
    { key: 'deliveries', label: '配送单数', minWidth: 90 }, { key: 'balanceCents', label: '可提现余额', minWidth: 115 },
  ],
  orders: [
    { key: 'id', label: '商户订单号', minWidth: 120 }, { key: 'paymentId', label: '支付记录单号', minWidth: 175 },
    { key: 'merchant', label: '商户', minWidth: 120 }, { key: 'items', label: '商品摘要', minWidth: 180 },
    { key: 'fulfillStatus', label: '履约状态', minWidth: 150 }, { key: 'refundStatus', label: '售后状态', minWidth: 120 },
    { key: 'paidCents', label: '用户实付', minWidth: 100 }, { key: 'settlementStatus', label: '结算状态', minWidth: 105 },
  ],
  aftersales: [
    { key: 'id', label: '退款单号', minWidth: 145 }, { key: 'orderId', label: '商户订单号', minWidth: 120 },
    { key: 'paymentId', label: '原支付单号', minWidth: 175 }, { key: 'reason', label: '申请与协商说明', minWidth: 230 },
    { key: 'amountCents', label: '申请金额', minWidth: 100 }, { key: 'status', label: '售后状态', minWidth: 110 },
    { key: 'channelStatus', label: '渠道状态', minWidth: 200 },
  ],
  withdrawals: [
    { key: 'id', label: '申请编号', minWidth: 110 }, { key: 'applicant', label: '申请主体', minWidth: 130 },
    { key: 'applicantType', label: '主体类型', minWidth: 90 }, { key: 'amountCents', label: '申请金额', minWidth: 110 },
    { key: 'status', label: '处理状态', minWidth: 110 }, { key: 'createdAt', label: '申请时间', minWidth: 150 },
    { key: 'reason', label: '处理说明', minWidth: 260 },
  ],
}
const columns = computed(() => {
  if (section.value !== 'finance') return columnSets[section.value] || []
  if (financeSection.value === 'accounts') return [
    { key: 'id', label: '主体编号' }, { key: 'name', label: '主体名称', minWidth: 160 }, { key: 'type', label: '主体类型' },
    { key: 'balanceCents', label: '可提现余额' }, { key: 'frozenCents', label: '冻结余额' }, { key: 'status', label: '账户状态' },
  ]
  if (financeSection.value === 'ledger') return [
    { key: 'id', label: '流水编号', minWidth: 145 }, { key: 'orderId', label: '商户订单号' }, { key: 'subject', label: '账务主体' },
    { key: 'type', label: '流水类型' }, { key: 'foodAmount', label: '餐品金额' }, { key: 'deliveryAmount', label: '配送费' },
    { key: 'paidAmount', label: '用户实付' }, { key: 'refundAmount', label: '累计退款' }, { key: 'settlement', label: '结算状态' },
  ]
  if (financeSection.value === 'journal') return [
    { key: 'id', label: '流水号' }, { key: 'accountId', label: '账户编号' }, { key: 'name', label: '账户名称' },
    { key: 'eventType', label: '变动类型' }, { key: 'referenceId', label: '流水关联', minWidth: 180 }, { key: 'requestKey', label: '申请幂等键', minWidth: 160 }, { key: 'debtId', label: '欠款编号' },
    { key: 'availableDelta', label: '可用余额变动' }, { key: 'frozenDelta', label: '冻结余额变动' }, { key: 'time', label: '时间', minWidth: 160 },
  ]
  return [
    { key: 'id', label: '欠款记录' }, { key: 'orderId', label: '订单编号' }, { key: 'merchant', label: '商户' },
    { key: 'rider', label: '骑手' }, { key: 'riderDue', label: '骑手应得' }, { key: 'merchantBalance', label: '商户可扣余额' },
    { key: 'platformAdvance', label: '平台垫付' }, { key: 'recovered', label: '已扣回' }, { key: 'note', label: '说明' },
  ]
})

const currentRows = computed(() => financeSection.value ? finance.value[financeSection.value] : rows.value)

function withdrawalDisplayRow(row: Row): Row {
  const status = row.status
  const reviewable = Number(row.reviewable) === 1
  if (row.retryRequested === true || Number(row.retryRequested) === 1) {
    return { ...row, status, reviewable, reason: '重试已提交，等待微信查单' }
  }
  if (row.retryMode === 'original') {
    return { ...row, status, reviewable, reason: '微信运营账户余额不足，充值到账后可重试' }
  }
  if (status === 'PENDING_REVIEW' && row.reviewable != null && Number(row.reviewable) === 0) {
    return { ...row, status, reviewable, reason: '已有审核或转账记录，请核对原记录' }
  }
  return { ...row, status, reviewable }
}

function canReviewWithdrawal(row: Row) {
  return auth.can('withdrawal.review') && row.status === 'PENDING_REVIEW' && row.reviewable === true
}

function canRetryWithdrawal(row: Row) {
  return auth.can('withdrawal.review') && ['original', 'new'].includes(row.retryMode)
}

const statusFields: Record<string, string> = { merchants: 'auditStatus', riders: 'auditStatus', orders: 'fulfillStatus', aftersales: 'status', withdrawals: 'status' }
const statusKey = computed(() => statusFields[section.value] || 'status')
const statusOptions = computed(() => [...new Set(currentRows.value.map((row) => String(row[statusKey.value] ?? '')).filter(Boolean))])
const filteredRows = computed(() => currentRows.value.filter((row) => {
  const text = Object.keys(row).map((key) => displayValue(row, key)).filter((value) => typeof value === 'string' || typeof value === 'number').join(' ').toLowerCase()
  return (!keyword.value || text.includes(keyword.value.toLowerCase())) && (!statusFilter.value || row[statusKey.value] === statusFilter.value)
}))
const { page, pageSize, total, pageRows } = useListPagination(() => filteredRows.value)
watch([keyword, statusFilter, section, financeSection], () => { page.value = 1 })

async function load() {
  const request = ++loadRequest
  loading.value = true
  try {
    if (section.value === 'finance') {
      const data = await adminApi.workspace<typeof finance.value>('finance')
      if (request === loadRequest) finance.value = data
    } else {
      const data = await adminApi.workspace<{ rows?: Row[]; orders?: Order[] }>(section.value)
      if (request !== loadRequest) return
      const fetchedRows = data.rows || []
      rows.value = section.value === 'withdrawals' ? fetchedRows.map(withdrawalDisplayRow) : fetchedRows
      relatedOrders.value = data.orders || (data.rows as Order[] | undefined) || []
    }
  } catch (error) {
    if (request === loadRequest) ElMessage.error(error instanceof Error ? error.message : '加载失败。')
  } finally {
    if (request === loadRequest) loading.value = false
  }
}
onMounted(() => { void load() })
watch([section, financeSection], ([nextSection], [previousSection]) => {
  drawer.value = false
  printerDialog.value = false
  printerForm.pkey = ''
  keyword.value = ''
  statusFilter.value = ''
  if (nextSection !== previousSection) {
    rows.value = []
    relatedOrders.value = []
    void load()
  }
})

async function openPrinter(row: Row) {
  if (printerLoadingId.value || !auth.can('merchant.printer.edit')) return
  printerLoadingId.value = row.id
  try {
    const saved = await adminApi.merchantPrinter(row.id)
    if (section.value !== 'merchants') return
    printerTarget.value = { id: row.id, name: row.name, saved }
    printerForm.sn = saved.sn
    printerForm.pkey = ''
    printerDialog.value = true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取打印机设置失败')
  } finally {
    printerLoadingId.value = ''
  }
}

async function savePrinter() {
  if (!printerTarget.value || printerSaving.value || !printerChanged.value || !auth.can('merchant.printer.edit')) return
  const sn = printerForm.sn.trim()
  if (!sn) return ElMessage.warning('请输入打印机 SN')
  if (printerNeedsPkey.value && !printerForm.pkey.trim()) return ElMessage.warning('请输入打印机 PKey')
  printerSaving.value = true
  try {
    await adminApi.saveMerchantPrinter(printerTarget.value.id, sn, printerForm.pkey)
    printerDialog.value = false
    ElMessage.success('打印机设置已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存打印机设置失败')
  } finally {
    printerSaving.value = false
  }
}

const stateFields = new Set(['auditStatus', 'businessStatus', 'paymentStatus', 'fulfillStatus', 'refundStatus',
  'settlementStatus', 'status', 'settlement', 'type', 'applicantType', 'gender', 'availability'])

function displayValue(row: Row, key: string) {
  const value = row[key]
  if (key === 'settlement' && value === 'LEGACY_DETAIL') return row.settlementDetail || '—'
  if (stateFields.has(key)) return stateLabel(value)
  if (key === 'availableTime') return row.availability === 'CUSTOM' ? value : stateLabel(row.availability)
  if (key === 'channelStatus') return value === 'LEGACY_DETAIL' ? row.channelDetail : stateLabel(value)
  if (key === 'interveneReason') return row.interveneCode ? interventionLabel(row.interveneCode) : value
  if (key === 'operator' && row.operatorCode) return value || stateLabel(row.operatorCode)
  if (['paidCents', 'amountCents', 'balanceCents', 'frozenCents', 'availableDelta', 'frozenDelta'].includes(key)) return money(Number(value || 0))
  if (key === 'eventType') return ({ debt_recover: '欠款扣回', debt_receive: '平台收回垫付', withdraw_freeze: '提现冻结', withdraw_unfreeze: '审核拒绝解冻', transfer_failed_unfreeze: '转账失败解冻', transfer_success: '转账成功' } as Record<string,string>)[value] || value
  return value === undefined || value === null || value === '' ? '—' : value
}
function statusType(value: string) {
  if (['APPROVED', 'COMPLETED', 'REFUND_SUCCEEDED', 'OFFLINE_RESOLVED', 'WITHDRAWABLE', 'PAID', 'STORE_OPEN'].includes(value)) return 'success'
  if (['PENDING_REVIEW', 'PENDING_PAYMENT', 'AWAITING_MERCHANT_DECISION', 'REFUND_PROCESSING', 'REFUND_RESULT_UNKNOWN', 'NEGOTIATING', 'PENDING_SETTLEMENT', 'TRANSFERRING', 'PENDING_PAYOUT', 'SETTLEMENT_HELD'].includes(value)) return 'warning'
  if (['REJECTED', 'REFUND_FAILED', 'REVIEW_REJECTED', 'PAYOUT_FAILED', 'CLOSED', 'STORE_CLOSED'].includes(value)) return 'danger'
  return 'info'
}
function clearPhoto() {
  photoRequest++
  if (merchantPhoto.value) URL.revokeObjectURL(merchantPhoto.value)
  merchantPhoto.value = ''
  photoError.value = ''
}

onUnmounted(clearPhoto)

async function openDetails(row: Row) {
  clearPhoto()
  activeRow.value = row
  drawer.value = true
  if (!['merchants', 'riders'].includes(section.value) || !row.hasPhoto) return
  const request = photoRequest
  try {
    const blob = section.value === 'riders'
      ? await adminApi.riderStudentCard(String(row.id))
      : await adminApi.merchantPhoto(String(row.id))
    if (request === photoRequest) merchantPhoto.value = URL.createObjectURL(blob)
  } catch {
    if (request === photoRequest) photoError.value = '图片加载失败'
  }
}
function keyLabel(key: string) { return keyNames[key] || key }
function detailValue(row: Row, key: string) {
  const value = row[key]
  if (stateFields.has(key) || ['availableTime', 'channelStatus', 'interveneReason', 'operator'].includes(key)) {
    return displayValue(row, key) || '—'
  }
  if (typeof value === 'number' && /Cents$/.test(key)) return money(value)
  if (value && typeof value === 'object') return JSON.stringify(value)
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (value === 0) return '0'
  return value || '—'
}
function searchList() {
  page.value = 1
  void load()
}
function resetFilters() {
  keyword.value = ''
  statusFilter.value = ''
  page.value = 1
}
async function audit(row: Row, subject: string, decision: 'approve' | 'reject') {
  let reason = ''
  if (decision === 'reject') {
    try {
      const result = await ElMessageBox.prompt('请填写驳回原因，便于申请方了解需要补充的资料。', `驳回${subject}`, { inputPlaceholder: '填写具体原因', inputValidator: (value) => !!value.trim() || '驳回原因必填', confirmButtonText: '确认驳回', cancelButtonText: '取消', inputType: 'textarea' })
      reason = result.value
    } catch { return }
  } else {
    try { await ElMessageBox.confirm(`确认通过 ${row.name} 的${subject}申请？`, `审核${subject}`, { confirmButtonText: '确认通过', cancelButtonText: '取消', type: 'success' }) }
    catch { return }
  }
  try {
    await adminApi.action(`${section.value === 'merchants' ? 'merchant' : 'rider'}.audit`, { id: row.id, decision, reason })
    ElMessage.success(decision === 'approve' ? '审核已通过。' : '已记录驳回原因。')
    await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '审核失败。') }
}
async function coordinate(row: Order) {
  try {
    const result = await ElMessageBox.prompt('记录已联系商户、骑手或用户的协调情况。此备注不修改履约事实。', `协调订单 ${row.id}`, { inputPlaceholder: '填写联系对象与处理结果', inputValidator: (value) => !!value.trim() || '请填写协调备注', inputType: 'textarea', confirmButtonText: '保存备注', cancelButtonText: '取消' })
    await adminApi.action('order.coordinate', { id: row.id, note: result.value })
    ElMessage.success('已记录协调备注，订单配送状态未改变。')
    await load()
  } catch (error) {
    if (error instanceof Error && !['cancel', 'close'].includes(error.message)) ElMessage.error(error.message)
  }
}
function coordinateFromDrawer() { if (activeRow.value) coordinate(activeRow.value as unknown as Order) }
async function reviewWithdrawal(row: Row, decision: 'approve' | 'reject') {
  if (!canReviewWithdrawal(row)) return
  let reason = ''
  try {
    if (decision === 'reject') {
      const response = await ElMessageBox.prompt('拒绝申请后立即解冻申请金额。', `拒绝提现 ${row.id}`, { inputPlaceholder: '填写原因', inputValidator: (value) => !!value.trim() || '原因必填', confirmButtonText: '确认拒绝', cancelButtonText: '取消' })
      reason = response.value
    } else {
      await ElMessageBox.confirm('审核通过后进入待打款，资金继续冻结，等待渠道处理。', `审核提现 ${row.id}`, { confirmButtonText: '确认通过', cancelButtonText: '取消' })
    }
    await adminApi.action('withdrawal.review', { id: row.id, decision, reason })
    ElMessage.success(decision === 'approve' ? '审核通过，等待渠道打款。' : '已拒绝并解冻资金。')
    await load()
  } catch(error) { if(error instanceof Error && !['cancel', 'close'].includes(error.message)) ElMessage.error(error.message) }
}
async function retryWithdrawal(row: Row) {
  if (!canRetryWithdrawal(row) || retryingWithdrawalId.value) return
  retryingWithdrawalId.value = row.id
  try {
    const message = row.retryMode === 'original'
      ? '请确认微信运营账户已充值到账。提交后将先查询微信原单，再决定是否按原单重试。'
      : '微信原单已结束。提交后将重新冻结申请金额，并建立关联的重试申请。'
    await ElMessageBox.confirm(message, '重试打款 ' + money(row.amountCents), {
      confirmButtonText: '确认重试', cancelButtonText: '取消',
    })
    await adminApi.action('withdrawal.retry', { id: row.id })
    ElMessage.success('已提交打款重试')
    await load()
    if (activeRow.value?.id === row.id) {
      activeRow.value = rows.value.find(item => item.id === row.id) ?? null
    }
  } catch (error) {
    if (error instanceof Error && !['cancel', 'close'].includes(error.message)) ElMessage.error(error.message)
  } finally {
    retryingWithdrawalId.value = ''
  }
}
function handlePrimary(row: Row) {
  if (section.value === 'merchants' || section.value === 'riders') openDetails(row)
  else if (section.value === 'orders') openDetails(row)
  else if (section.value === 'aftersales') openDetails(row)
  else if (section.value === 'withdrawals') openDetails(row)
}
function rowActionText(row: Row) {
  if (section.value === 'merchants') return row.auditStatus === 'PENDING_REVIEW' ? '审核资料' : '查看资料'
  if (section.value === 'riders') return row.auditStatus === 'PENDING_REVIEW' ? '审核资料' : '查看资料'
  if (section.value === 'orders') return '订单详情'
  if (section.value === 'aftersales') return '售后详情'
  return '查看详情'
}
function runRowAction(row: Row) {
  handlePrimary(row)
}
</script>

<template>
  <div class="page-heading">
    <h1>{{ title }}</h1>
    <el-tag v-if="section === 'orders'" type="warning" effect="light" round>待管理员介入 {{ rows.filter((row) => row.intervene).length }} 项</el-tag>

  </div>

  <template v-if="section === 'finance'">
    <div class="panel-card workbench-panel">
      <div class="panel-toolbar finance-toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索主体、订单或流水号" aria-label="搜索主体、订单或流水号" class="toolbar-search" :prefix-icon="Search" />
      </div>
      <el-table :key="financeSection" :data="pageRows" row-key="id" v-loading="loading" stripe class="data-table">
        <el-table-column v-for="column in columns" :key="column.key" :prop="column.key" :label="column.label" :min-width="column.minWidth || 110" show-overflow-tooltip>
          <template #default="scope"><el-tag v-if="column.key === 'settlement' || column.key === 'status'" :type="statusType(String(scope.row[column.key]))" effect="light" round>{{ displayValue(scope.row, column.key) }}</el-tag><span v-else>{{ displayValue(scope.row, column.key) }}</span></template>
        </el-table-column>
        <el-table-column v-if="financeSection === 'debts'" label="操作" width="220" fixed="right"><template #default="scope"><el-button link type="primary" @click="openDetails(scope.row)">查看去向</el-button></template></el-table-column>
        <template #empty><el-empty description="没有符合条件的记录" /></template>
      </el-table>
      <ListPagination v-model:page="page" v-model:page-size="pageSize" :total="total" :disabled="loading" />
    </div>
  </template>

  <div v-else class="panel-card workbench-panel">
    <div class="panel-toolbar">
      <div class="filter-set">
        <el-input v-model="keyword" clearable placeholder="搜索编号、名称或说明" aria-label="搜索编号、名称或说明" class="toolbar-search" :prefix-icon="Search" @keyup.enter="searchList" />
        <el-select v-model="statusFilter" clearable placeholder="全部状态" aria-label="筛选状态" class="status-select"><el-option v-for="status in statusOptions" :key="status" :label="stateLabel(status)" :value="status" /></el-select>
        <el-button @click="searchList"><el-icon><Search /></el-icon>查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </div>
    <el-table :data="pageRows" row-key="id" v-loading="loading" stripe class="data-table">
      <el-table-column v-if="section === 'orders'" label="介入提醒" width="125" fixed="left"><template #default="scope"><el-tag v-if="scope.row.intervene" type="warning" effect="light" round>待管理员介入</el-tag><span v-else class="muted">—</span></template></el-table-column>
      <el-table-column v-for="column in columns" :key="column.key" :prop="column.key" :label="column.label" :min-width="column.minWidth || 110" show-overflow-tooltip>
        <template #default="scope">
          <el-tag v-if="['auditStatus','businessStatus','fulfillStatus','refundStatus','settlementStatus','status'].includes(column.key)" :type="statusType(String(scope.row[column.key]))" effect="light" round>{{ displayValue(scope.row, column.key) }}</el-tag>
          <span v-else-if="column.key === 'amountCents' || column.key === 'paidCents' || column.key === 'balanceCents'">{{ money(Number(scope.row[column.key] || 0)) }}</span>
          <span v-else>{{ displayValue(scope.row, column.key) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" :width="section === 'merchants' && auth.can('merchant.printer.edit') ? 275 : section === 'merchants' || section === 'riders' ? 220 : section === 'aftersales' ? 190 : section === 'withdrawals' ? 250 : 170" fixed="right">
        <template #default="scope">
          <template v-if="section === 'merchants' || section === 'riders'">
            <el-button link type="primary" @click="runRowAction(scope.row)">{{ rowActionText(scope.row) }}</el-button>
            <el-button v-if="section === 'merchants' && auth.can('merchant.printer.edit') && !String(scope.row.id).startsWith('revision:')"
              link type="primary" :loading="printerLoadingId === scope.row.id" @click="openPrinter(scope.row)">打印机设置</el-button>
            <template v-if="scope.row.auditStatus === 'PENDING_REVIEW' && auth.can(section === 'merchants' ? 'merchant.audit' : 'rider.audit')"><el-button link type="success" @click="audit(scope.row, section === 'merchants' ? (scope.row.businessStatus === 'PROFILE_REVISION' ? '商户资料变更' : '商户入驻') : '骑手入驻', 'approve')">通过</el-button><el-button link type="danger" @click="audit(scope.row, section === 'merchants' ? (scope.row.businessStatus === 'PROFILE_REVISION' ? '商户资料变更' : '商户入驻') : '骑手入驻', 'reject')">驳回</el-button></template>
          </template>
          <template v-else-if="section === 'orders'"><el-button link type="primary" @click="handlePrimary(scope.row)">订单详情</el-button><el-button v-if="scope.row.intervene && auth.can('order.coordinate')" link type="warning" @click="coordinate(scope.row as unknown as Order)">记录协调</el-button></template>
          <template v-else-if="section === 'aftersales'"><el-button link type="primary" @click="openDetails(scope.row)">详情</el-button></template>
          <template v-else-if="section === 'withdrawals'">
            <el-button link type="primary" @click="openDetails(scope.row)">详情</el-button>
            <template v-if="canReviewWithdrawal(scope.row)">
              <el-button link type="success" @click="reviewWithdrawal(scope.row, 'approve')">通过</el-button>
              <el-button link type="danger" @click="reviewWithdrawal(scope.row, 'reject')">拒绝</el-button>
            </template>
            <el-button v-if="canRetryWithdrawal(scope.row)" link type="primary"
              :loading="retryingWithdrawalId === scope.row.id" @click="retryWithdrawal(scope.row)">重试打款</el-button>
          </template>
        </template>
      </el-table-column>
      <template #empty><el-empty description="没有符合条件的记录" /></template>
    </el-table>
    <ListPagination v-model:page="page" v-model:page-size="pageSize" :total="total" :disabled="loading" />
  </div>

  <el-dialog v-model="printerDialog" title="打印机设置" width="min(480px, 94vw)" destroy-on-close
    :close-on-click-modal="false" :close-on-press-escape="!printerSaving" :show-close="!printerSaving"
    @close="printerForm.pkey = ''">
    <el-form v-if="printerTarget" label-position="top" :disabled="printerSaving" @submit.prevent="savePrinter">
      <p class="muted">{{ printerTarget.name }} · {{ printerTarget.id }}</p>
      <el-form-item label="打印机 SN" required>
        <el-input v-model="printerForm.sn" maxlength="128" autocomplete="off" placeholder="请输入设备 SN" />
      </el-form-item>
      <el-form-item label="打印机 PKey" :required="printerNeedsPkey">
        <el-input v-model="printerForm.pkey" type="password" maxlength="512" autocomplete="new-password"
          :placeholder="printerNeedsPkey ? '请输入设备 PKey' : '留空保留已保存的 PKey'" />
      </el-form-item>
      <div class="form-actions">
        <el-button :disabled="printerSaving" @click="printerDialog = false">取消</el-button>
        <el-button native-type="submit" type="primary" :disabled="!printerChanged" :loading="printerSaving">保存设置</el-button>
      </div>
    </el-form>
  </el-dialog>

  <el-drawer v-model="drawer" :title="activeRow?.name || activeRow?.id || '记录详情'" size="min(560px, 94vw)" destroy-on-close @close="clearPhoto">
    <template v-if="activeRow">
      <div v-if="merchantPhoto || photoError" class="detail-section">
        <img v-if="merchantPhoto" :src="merchantPhoto" alt="待审核或当前店铺图片" style="display:block;max-width:100%;max-height:240px;object-fit:contain">
        <p v-else class="muted">{{ photoError }}</p>
      </div>
      <div class="drawer-intro"><el-tag :type="statusType(String(activeRow.auditStatus || activeRow.status || activeRow.fulfillStatus || 'info'))" effect="light" round>{{ stateLabel(activeRow.auditStatus || activeRow.status || activeRow.fulfillStatus || '信息记录') }}</el-tag></div>
      <div v-if="section === 'riders'" class="detail-section">
        <h3>学生身份材料</h3>
        <img v-if="merchantPhoto" :src="merchantPhoto" alt="骑手提交的学生证照片"
          style="display:block;max-width:100%;max-height:280px;object-fit:contain">
        <p v-else class="muted">{{ photoError || (activeRow.hasPhoto ? '正在加载照片…' : '暂无学生证照片') }}</p>
      </div>
      <div class="detail-grid">
        <template v-for="[key] in Object.entries(activeRow)" :key="key"><div v-if="!['coordinationNotes','snapshot','hasPhoto','reviewable','retryMode','retryRequested','availability','interveneCode','operatorCode','channelDetail'].includes(key)" class="detail-item"><span>{{ keyLabel(key) }}</span><strong>{{ detailValue(activeRow, key) }}</strong></div></template>
      </div>
      <div v-if="section === 'withdrawals' && canRetryWithdrawal(activeRow)" class="detail-section">
        <el-button type="primary" :loading="retryingWithdrawalId === activeRow.id"
          @click="retryWithdrawal(activeRow)">重试打款</el-button>
      </div>
      <template v-if="section === 'orders'">
        <div class="detail-section"><h3>合并支付单关联</h3><div v-for="linked in relatedOrders.filter((order) => order.paymentId === activeRow?.paymentId)" :key="linked.id" class="linked-order"><strong>{{ linked.id }} · {{ linked.merchant }}</strong><span>{{ stateLabel(linked.fulfillStatus) }} · {{ money(linked.paidCents) }}</span></div></div>
        <div v-if="activeRow.intervene && auth.can('order.coordinate')" class="detail-section"><el-button type="primary" plain @click="coordinateFromDrawer">记录协调备注</el-button></div>
        <div class="detail-section"><h3>协调记录</h3><p v-for="note in activeRow.coordinationNotes || []" :key="note.id">
          {{ note.time }} · {{ note.actorCode ? stateLabel(note.actorCode) : note.actor }} ·
          {{ note.previousStatus && note.nextStatus ? stateLabel(note.previousStatus) + ' → ' + stateLabel(note.nextStatus) : note.note }}
        </p><p v-if="!activeRow.coordinationNotes?.length" class="muted">暂无协调备注</p></div>
      </template>
      <template v-if="section === 'merchants'">
        <div class="detail-section"><h3>本店订单关联</h3><div v-for="linked in relatedOrders.filter((order) => order.merchant === activeRow?.name).slice(0, 4)" :key="linked.id" class="linked-order"><strong>{{ linked.id }} · {{ stateLabel(linked.fulfillStatus) }}</strong><span>{{ money(linked.paidCents) }}</span></div><p v-if="!relatedOrders.some((order) => order.merchant === activeRow?.name)" class="muted">暂无关联订单</p><el-button v-if="auth.can('order.view')" link type="primary" @click="router.push('/orders')">进入订单列表</el-button></div>
      </template>
      <template v-if="section === 'riders'">
        <div class="detail-section"><h3>配送记录</h3><div v-for="linked in relatedOrders.filter((order) => order.rider === activeRow?.name).slice(0, 5)" :key="linked.id" class="linked-order"><strong>{{ linked.id }} · {{ linked.merchant }}</strong><span>{{ stateLabel(linked.fulfillStatus) }}</span></div><p v-if="!relatedOrders.some((order) => order.rider === activeRow?.name)" class="muted">暂无已分配的配送订单</p></div>
      </template>
    </template>
  </el-drawer>

</template>
