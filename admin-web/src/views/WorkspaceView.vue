<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { adminApi } from '../api/admin'
import { useAuthStore } from '../stores/auth'
import { money } from '../domain/rules'
import type { Order, SectionKey } from '../domain/types'

type Row = Record<string, any>
const route = useRoute()
const auth = useAuthStore()
const router = useRouter()
const section = computed(() => String(route.name || 'orders') as SectionKey)
const headings: Record<string, { title: string; description: string }> = {
  merchants: { title: '商户管理', description: '审核入驻资料，查看营业状态与关联订单。' },
  riders: { title: '骑手管理', description: '核验学生骑手资料，查看配送记录与审核历史。' },
  orders: { title: '订单管理', description: '按商户拆单跟进履约进度，定位待管理员介入事项。' },
  aftersales: { title: '售后管理', description: '查看退款记录与渠道状态。' },
  finance: { title: '财务对账与欠款', description: '核对商户与骑手收入、退款调整、结算状态及欠款去向。' },
  withdrawals: { title: '提现审核', description: '审核商户与骑手申请，跟进打款状态。' },
}
const heading = computed(() => headings[section.value] || headings.orders)
const loading = ref(false)
const rows = ref<Row[]>([])
const relatedOrders = ref<Order[]>([])
const keyword = ref('')
const statusFilter = ref('')
const activeFinanceTab = ref('accounts')
const finance = ref<{ accounts: Row[]; ledger: Row[]; debts: Row[]; journal: Row[] }>({ accounts: [], ledger: [], debts: [], journal: [] })
const drawer = ref(false)
const activeRow = ref<Row | null>(null)
const keyNames: Record<string, string> = {
  id: '编号', name: '名称', contact: '联系人', phone: '联系方式', address: '地址', auditStatus: '审核状态', auditReason: '审核说明', businessStatus: '营业状态', orderCount: '订单数', joinedAt: '登记时间',
  gender: '性别', availableTime: '可送餐时间', deliveries: '配送单数', balanceCents: '可提现余额', paymentId: '合并支付单号', merchantId: '商户编号', merchant: '商户', rider: '骑手', items: '商品摘要', foodCents: '餐品金额', deliveryCents: '配送费', paidCents: '用户实付', refundedCents: '累计退款', foodRefundedCents: '累计退餐品', paymentStatus: '支付状态', fulfillStatus: '履约状态', refundStatus: '售后状态', settlementStatus: '结算状态', accepted: '商户已接单', pendingRefund: '存在处理中退款', locking: '结算锁定', paymentCount: '支付单退款次数', paidAt: '支付时间', completedAt: '完成时间', closedAt: '关闭时间', intervene: '待管理员介入', interveneReason: '介入原因', coordinationNotes: '协调记录',
  reason: '说明', type: '类型', amountCents: '金额', status: '处理状态', channelStatus: '渠道状态', createdAt: '创建时间', operator: '操作人', applicant: '申请主体', applicantType: '主体类型', time: '时间', subject: '账务主体', foodAmount: '餐品金额', deliveryAmount: '配送费', paidAmount: '实付金额', refundAmount: '退款金额', settlement: '结算状态', riderDue: '骑手应得', merchantBalance: '商户可扣余额', platformAdvance: '平台垫付', recovered: '已扣回', note: '说明',
}

const title = computed(() => heading.value.title)
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
  if (activeFinanceTab.value === 'accounts') return [
    { key: 'id', label: '主体编号' }, { key: 'name', label: '主体名称', minWidth: 160 }, { key: 'type', label: '主体类型' },
    { key: 'balanceCents', label: '可提现余额' }, { key: 'frozenCents', label: '冻结余额' }, { key: 'status', label: '账户状态' },
  ]
  if (activeFinanceTab.value === 'ledger') return [
    { key: 'id', label: '流水编号', minWidth: 145 }, { key: 'orderId', label: '商户订单号' }, { key: 'subject', label: '账务主体' },
    { key: 'type', label: '流水类型' }, { key: 'foodAmount', label: '餐品金额' }, { key: 'deliveryAmount', label: '配送费' },
    { key: 'paidAmount', label: '用户实付' }, { key: 'refundAmount', label: '累计退款' }, { key: 'settlement', label: '结算状态' },
  ]
  if (activeFinanceTab.value === 'journal') return [
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

const currentRows = computed(() => section.value === 'finance'
  ? activeFinanceTab.value === 'accounts' ? finance.value.accounts : activeFinanceTab.value === 'ledger' ? finance.value.ledger : activeFinanceTab.value === 'journal' ? finance.value.journal : finance.value.debts
  : rows.value)
const statusFields: Record<string, string> = { merchants: 'auditStatus', riders: 'auditStatus', orders: 'fulfillStatus', aftersales: 'status', withdrawals: 'status' }
const statusKey = computed(() => statusFields[section.value] || 'status')
const statusOptions = computed(() => [...new Set(currentRows.value.map((row) => String(row[statusKey.value] ?? '')).filter(Boolean))])
const filteredRows = computed(() => currentRows.value.filter((row) => {
  const text = Object.values(row).filter((value) => typeof value === 'string' || typeof value === 'number').join(' ').toLowerCase()
  return (!keyword.value || text.includes(keyword.value.toLowerCase())) && (!statusFilter.value || row[statusKey.value] === statusFilter.value)
}))

async function load() {
  loading.value = true
  try {
    if (section.value === 'finance') {
      finance.value = await adminApi.workspace('finance') as typeof finance.value
    } else {
      const data = await adminApi.workspace<any>(section.value)
      rows.value = data.rows || []
      relatedOrders.value = data.orders || data.rows || []
    }
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '加载失败。') }
  finally { loading.value = false }
}
onMounted(() => { void load() })
watch(section, () => { keyword.value = ''; statusFilter.value = ''; activeFinanceTab.value = 'accounts'; load() })

function displayValue(row: Row, key: string) {
  const value = row[key]
  if (['paidCents', 'amountCents', 'balanceCents', 'frozenCents', 'availableDelta', 'frozenDelta'].includes(key)) return money(Number(value || 0))
  if (key === 'eventType') return ({ debt_recover: '欠款扣回', debt_receive: '平台收回垫付', withdraw_freeze: '提现冻结', withdraw_unfreeze: '审核拒绝解冻', transfer_failed_unfreeze: '转账失败解冻', transfer_success: '转账成功' } as Record<string,string>)[value] || value
  return value === undefined || value === null || value === '' ? '—' : value
}
function statusType(value: string) {
  if (['已通过', '已完成', '退款成功', '线下已处理', '可提现', '打款成功', '营业中'].includes(value)) return 'success'
  if (['待审核', '待支付', '待商户处理', '退款处理中', '结果未知', '协商中', '待结算', '打款中', '待打款', '售后暂缓'].includes(value)) return 'warning'
  if (['已驳回', '退款失败', '审核拒绝', '打款失败', '已关闭', '休息中'].includes(value)) return 'danger'
  return 'info'
}
function openDetails(row: Row) { activeRow.value = row; drawer.value = true }
function keyLabel(key: string) { return keyNames[key] || key }
function detailValue(value: unknown, key: string) {
  if (typeof value === 'number' && /Cents$/.test(key)) return money(value)
  if (value && typeof value === 'object') return JSON.stringify(value)
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (value === 0) return '0'
  return value || '—'
}
function resetFilters() { keyword.value = ''; statusFilter.value = '' }
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
  let reason = ''
  try {
    if (decision === 'reject') {
      const response = await ElMessageBox.prompt('拒绝申请后立即解冻申请金额。', `拒绝提现 ${row.id}`, { inputPlaceholder: '填写原因', inputValidator: (value) => !!value.trim() || '原因必填', confirmButtonText: '确认拒绝', cancelButtonText: '取消' })
      reason = response.value
    } else {
      await ElMessageBox.confirm('审核通过后进入待打款，实际转账需等待微信渠道接入。', `审核提现 ${row.id}`, { confirmButtonText: '确认通过', cancelButtonText: '取消' })
    }
    await adminApi.action('withdrawal.review', { id: row.id, decision, reason })
    ElMessage.success(decision === 'approve' ? '审核通过，等待渠道打款。' : '已拒绝并解冻资金。')
    await load()
  } catch(error) { if(error instanceof Error && !['cancel', 'close'].includes(error.message)) ElMessage.error(error.message) }
}
function handlePrimary(row: Row) {
  if (section.value === 'merchants' || section.value === 'riders') openDetails(row)
  else if (section.value === 'orders') openDetails(row)
  else if (section.value === 'aftersales') openDetails(row)
  else if (section.value === 'withdrawals') openDetails(row)
}
function rowActionText(row: Row) {
  if (section.value === 'merchants') return row.auditStatus === '待审核' ? '审核资料' : '查看资料'
  if (section.value === 'riders') return row.auditStatus === '待审核' ? '审核资料' : '查看资料'
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
      <div class="panel-toolbar finance-tabs">
        <el-tabs v-model="activeFinanceTab"><el-tab-pane label="商户 / 骑手余额" name="accounts" /><el-tab-pane label="订单账务" name="ledger" /><el-tab-pane label="账户流水" name="journal" /><el-tab-pane label="欠款与平台垫付" name="debts" /></el-tabs>
        <el-input v-model="keyword" clearable placeholder="搜索主体、订单或流水号" aria-label="搜索主体、订单或流水号" class="toolbar-search" :prefix-icon="Search" />
      </div>
      <el-table :data="filteredRows" row-key="id" v-loading="loading" stripe class="data-table">
        <el-table-column v-for="column in columns" :key="column.key" :prop="column.key" :label="column.label" :min-width="column.minWidth || 110" show-overflow-tooltip>
          <template #default="scope"><el-tag v-if="column.key === 'settlement' || column.key === 'status'" :type="statusType(String(scope.row[column.key]))" effect="light" round>{{ scope.row[column.key] }}</el-tag><span v-else>{{ displayValue(scope.row, column.key) }}</span></template>
        </el-table-column>
        <el-table-column v-if="activeFinanceTab === 'debts'" label="操作" width="220" fixed="right"><template #default="scope"><el-button link type="primary" @click="openDetails(scope.row)">查看去向</el-button></template></el-table-column>
        <template #empty><el-empty description="没有符合条件的记录" /></template>
      </el-table>
    </div>
  </template>

  <div v-else class="panel-card workbench-panel">
    <div class="panel-toolbar">
      <div class="filter-set">
        <el-input v-model="keyword" clearable placeholder="搜索编号、名称或说明" aria-label="搜索编号、名称或说明" class="toolbar-search" :prefix-icon="Search" @keyup.enter="load" />
        <el-select v-model="statusFilter" clearable placeholder="全部状态" aria-label="筛选状态" class="status-select"><el-option v-for="status in statusOptions" :key="status" :label="status" :value="status" /></el-select>
        <el-button @click="load"><el-icon><Search /></el-icon>查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </div>
    <el-table :data="filteredRows" row-key="id" v-loading="loading" stripe class="data-table">
      <el-table-column v-if="section === 'orders'" label="介入提醒" width="125" fixed="left"><template #default="scope"><el-tag v-if="scope.row.intervene" type="warning" effect="light" round>待管理员介入</el-tag><span v-else class="muted">—</span></template></el-table-column>
      <el-table-column v-for="column in columns" :key="column.key" :prop="column.key" :label="column.label" :min-width="column.minWidth || 110" show-overflow-tooltip>
        <template #default="scope">
          <el-tag v-if="['auditStatus','businessStatus','fulfillStatus','refundStatus','settlementStatus','status'].includes(column.key)" :type="statusType(String(scope.row[column.key]))" effect="light" round>{{ scope.row[column.key] || '无售后' }}</el-tag>
          <span v-else-if="column.key === 'amountCents' || column.key === 'paidCents' || column.key === 'balanceCents'">{{ money(Number(scope.row[column.key] || 0)) }}</span>
          <span v-else>{{ displayValue(scope.row, column.key) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" :width="section === 'merchants' || section === 'riders' ? 220 : section === 'aftersales' ? 190 : section === 'withdrawals' ? 250 : 170" fixed="right">
        <template #default="scope">
          <template v-if="section === 'merchants' || section === 'riders'">
            <el-button link type="primary" @click="runRowAction(scope.row)">{{ rowActionText(scope.row) }}</el-button>
            <template v-if="scope.row.auditStatus === '待审核' && auth.can(section === 'merchants' ? 'merchant.audit' : 'rider.audit')"><el-button link type="success" @click="audit(scope.row, section === 'merchants' ? '商户入驻' : '骑手入驻', 'approve')">通过</el-button><el-button link type="danger" @click="audit(scope.row, section === 'merchants' ? '商户入驻' : '骑手入驻', 'reject')">驳回</el-button></template>
          </template>
          <template v-else-if="section === 'orders'"><el-button link type="primary" @click="handlePrimary(scope.row)">订单详情</el-button><el-button v-if="scope.row.intervene && auth.can('order.coordinate')" link type="warning" @click="coordinate(scope.row as unknown as Order)">记录协调</el-button></template>
          <template v-else-if="section === 'aftersales'"><el-button link type="primary" @click="openDetails(scope.row)">详情</el-button></template>
          <template v-else-if="section === 'withdrawals'"><el-button link type="primary" @click="openDetails(scope.row)">详情</el-button><template v-if="scope.row.status === '待审核' && auth.can('withdrawal.review')"><el-button link type="success" @click="reviewWithdrawal(scope.row, 'approve')">通过</el-button><el-button link type="danger" @click="reviewWithdrawal(scope.row, 'reject')">拒绝</el-button></template></template>
        </template>
      </el-table-column>
      <template #empty><el-empty description="没有符合条件的记录" /></template>
    </el-table>
  </div>

  <el-drawer v-model="drawer" :title="activeRow?.name || activeRow?.id || '记录详情'" size="min(560px, 94vw)" destroy-on-close>
    <template v-if="activeRow">
      <div class="drawer-intro"><el-tag :type="statusType(String(activeRow.auditStatus || activeRow.status || activeRow.fulfillStatus || 'info'))" effect="light" round>{{ activeRow.auditStatus || activeRow.status || activeRow.fulfillStatus || '信息记录' }}</el-tag></div>
      <div v-if="section === 'riders'" class="student-card-preview"><div class="id-placeholder"><el-icon><Avatar /></el-icon><span>证件待接入</span></div><div><strong>学生身份材料</strong></div></div>
      <div class="detail-grid">
        <template v-for="[key] in Object.entries(activeRow)" :key="key"><div v-if="!['coordinationNotes','snapshot'].includes(key)" class="detail-item"><span>{{ keyLabel(key) }}</span><strong>{{ detailValue(activeRow[key], key) }}</strong></div></template>
      </div>
      <template v-if="section === 'orders'">
        <div class="detail-section"><h3>合并支付单关联</h3><div v-for="linked in relatedOrders.filter((order) => order.paymentId === activeRow?.paymentId)" :key="linked.id" class="linked-order"><strong>{{ linked.id }} · {{ linked.merchant }}</strong><span>{{ linked.fulfillStatus }} · {{ money(linked.paidCents) }}</span></div></div>
        <div v-if="activeRow.intervene && auth.can('order.coordinate')" class="detail-section"><el-button type="primary" plain @click="coordinateFromDrawer">记录协调备注</el-button></div>
        <div class="detail-section"><h3>协调记录</h3><p v-for="note in activeRow.coordinationNotes || []" :key="note">{{ note }}</p><p v-if="!activeRow.coordinationNotes?.length" class="muted">暂无协调备注</p></div>
      </template>
      <template v-if="section === 'merchants'">
        <div class="detail-section"><h3>本店订单关联</h3><div v-for="linked in relatedOrders.filter((order) => order.merchant === activeRow?.name).slice(0, 4)" :key="linked.id" class="linked-order"><strong>{{ linked.id }} · {{ linked.fulfillStatus }}</strong><span>{{ money(linked.paidCents) }}</span></div><p v-if="!relatedOrders.some((order) => order.merchant === activeRow?.name)" class="muted">暂无关联订单</p><el-button link type="primary" @click="router.push('/orders')">进入订单管理</el-button></div>
      </template>
      <template v-if="section === 'riders'">
        <div class="detail-section"><h3>配送记录</h3><div v-for="linked in relatedOrders.filter((order) => order.rider === activeRow?.name).slice(0, 5)" :key="linked.id" class="linked-order"><strong>{{ linked.id }} · {{ linked.merchant }}</strong><span>{{ linked.fulfillStatus }}</span></div><p v-if="!relatedOrders.some((order) => order.rider === activeRow?.name)" class="muted">暂无已分配的配送订单</p></div>
      </template>
    </template>
  </el-drawer>

</template>
