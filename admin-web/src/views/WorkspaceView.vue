<script setup lang="ts">
import { ElCollapse, ElCollapseItem, ElRadio, ElRadioGroup } from 'element-plus'
import AccountPicker from '../components/AccountPicker.vue'
import PricingFields from '../components/PricingFields.vue'
import { formatPricing, pricingFields, pricingForm, pricingPayload, type SettlementSnapshot } from '../domain/pricing'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElImage, ElMessage, ElMessageBox, ElUpload, type UploadInstance, type UploadProps } from 'element-plus'
import { Search, Upload } from '@element-plus/icons-vue'
import { adminApi, AdminApiError, type MerchantMember, type MerchantPrinter } from '../api/admin'
import ListPagination from '../components/ListPagination.vue'
import RowDragHandle from '../components/RowDragHandle.vue'
import { useRowReorder } from '../domain/useRowReorder'
import { useListPagination } from '../domain/useListPagination'
import { useAuthStore } from '../stores/auth'
import { money } from '../domain/rules'
import { encodePhoto, isPhotoFile } from '../domain/photo'
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
const orderMerchantFilter = ref('')
const finance = ref<Record<FinanceSection, Row[]>>({ accounts: [], ledger: [], debts: [], journal: [] })
let loadRequest = 0
const drawer = ref(false)
const printerDialog = ref(false)
const pricingTarget = ref<{ id: string; name: string }>()
const pricingLoading = ref('')
const pricingSaving = ref(false)
const pricingEnabled = ref(false)
const pricingVersion = ref(0)
const merchantPricing = ref(pricingForm())
const merchantPricingValid = computed(() => pricingPayload(merchantPricing.value))
const pricingDialog = computed({
  get: () => !!pricingTarget.value,
  set: open => { if (!open) pricingTarget.value = undefined },
})
const merchantDialog = ref(false)
const merchantSaving = ref(false)
const merchantChangingId = ref('')
const merchantEditingId = ref('')
const merchantCanOpen = ref(true)
const merchantForm = reactive({ name: '', contact: '', phone: '', address: '', open: false, userNumber: '' })
const merchantImageUpload = ref<UploadInstance>()
const selectedMerchantImage = ref<File>()
const selectedMerchantImageUrl = ref('')
const currentMerchantImageUrl = ref('')
const merchantImageError = ref('')
let merchantImageRequest: AbortController | undefined
const riderDialog = ref(false)
const riderSaving = ref(false)
const riderChangingId = ref('')
const riderEditingId = ref('')
const riderForm = reactive({ name: '', phone: '', gender: 'UNDISCLOSED', availability: 'MIDDAY_AND_EVENING', availableTime: '', userNumber: '' })
const bindingTarget = ref<{ id: string; name: string; role: 'merchant' | 'rider' }>()
const bindingUserNumber = ref('')
const bindingSaving = ref(false)
const bindingDialog = computed({
  get: () => !!bindingTarget.value,
  set: open => { if (!open) bindingTarget.value = undefined },
})
const applicationTarget = ref<Row>()
const applicationChoice = ref<'existing' | 'create'>()
const applicationStoreId = ref('')
const applicationSaving = ref(false)
const applicationDialog = computed({
  get: () => !!applicationTarget.value,
  set: open => { if (!open) applicationTarget.value = undefined },
})
const riderGenders = ['MALE', 'FEMALE', 'UNDISCLOSED']
const riderAvailability = ['MIDDAY', 'EVENING', 'MIDDAY_AND_EVENING', 'CUSTOM']
const printerLoadingId = ref('')
const printerSaving = ref(false)
const retryingWithdrawalId = ref('')
const printerTarget = ref<{ id: string; name: string; saved: MerchantPrinter }>()
const printerForm = reactive({ sn: '', pkey: '' })
const printerNeedsPkey = computed(() => !printerTarget.value?.saved.pkeySet || printerForm.sn.trim() !== printerTarget.value.saved.sn)
const printerChanged = computed(() => printerForm.sn.trim() !== printerTarget.value?.saved.sn || !!printerForm.pkey)
const snapshots = ref<SettlementSnapshot[]>([])
const snapshotsLoading = ref(false)
const snapshotsError = ref('')
let snapshotRequest = 0
const accountEventLabels: Record<string, string> = {
  settlement_credit: '商户结算入账',
  rider_settlement_credit: '骑手结算入账',
  rider_refund_debit: '商户承担骑手费用',
  rider_refund_advance: '平台垫付骑手费用',
  debt_recover: '欠款扣回',
  debt_receive: '平台收回垫付',
  withdraw_freeze: '提现冻结',
  withdraw_unfreeze: '审核拒绝解冻',
  withdraw_paid: '提现出款',
  transfer_failed_unfreeze: '转账失败解冻',
  transfer_success: '转账成功',
}
const snapshotSources = { DEFAULT: '平台默认', MERCHANT: '商户独立', LEGACY: '原成交规则' }
const activeRow = ref<Row | null>(null)
const merchantMembers = ref<MerchantMember[]>([])
const merchantMembersLoading = ref(false)
const merchantMembersError = ref('')
const merchantMembersVersion = ref(0)
const merchantMembersId = computed(() => drawer.value && section.value === 'merchants'
  && auth.can('user.view') && activeRow.value && isStore(activeRow.value) ? String(activeRow.value.id) : '')
watch([merchantMembersId, merchantMembersVersion], async ([id], _, onCleanup) => {
  merchantMembers.value = []
  merchantMembersError.value = ''
  merchantMembersLoading.value = !!id
  if (!id) return
  const request = new AbortController()
  onCleanup(() => request.abort())
  try {
    const result = await adminApi.merchantMembers(id, request.signal)
    if (!request.signal.aborted) merchantMembers.value = result.rows
  } catch (error) {
    if (!request.signal.aborted) merchantMembersError.value = error instanceof Error ? error.message : '账号列表加载失败'
  } finally {
    if (!request.signal.aborted) merchantMembersLoading.value = false
  }
})
const pickupQrOrderId = computed(() => drawer.value && section.value === 'orders'
  && activeRow.value?.fulfillStatus === 'AWAITING_RIDER' ? String(activeRow.value.id) : '')
const pickupQrUrl = ref('')
const pickupQrError = ref('')
watch(pickupQrOrderId, async (id, _, onCleanup) => {
  pickupQrUrl.value = ''
  pickupQrError.value = ''
  if (!id) return

  const request = new AbortController()
  let url = ''
  onCleanup(() => {
    request.abort()
    if (url) URL.revokeObjectURL(url)
  })
  try {
    const image = await adminApi.orderPickupQr(id, request.signal)
    if (request.signal.aborted) return
    url = URL.createObjectURL(image)
    pickupQrUrl.value = url
  } catch (error) {
    if (request.signal.aborted) return
    pickupQrError.value = error instanceof AdminApiError && error.status === 404
      ? '此订单当前无可用的领取二维码，请刷新订单列表。'
      : '领取二维码加载失败，请重新打开详情。'
  }
})
const merchantPhoto = ref('')
const photoError = ref('')
let photoRequest = 0
const keyNames: Record<string, string> = {
  originalWithdrawalId: '原提现编号', listed: '上架状态', enabled: '接单状态',
  id: '编号', bindingId: '小程序账号', bindingCount: '小程序账号', applicantIdentityId: '申请人商户账号 ID', name: '名称', contact: '联系人', phone: '联系方式', address: '地址', auditStatus: '审核状态', auditReason: '审核说明', businessStatus: '营业状态', joinedAt: '登记时间',
  gender: '性别', availableTime: '可送餐时间', deliveries: '配送单数', balanceCents: '可提现余额', paymentId: '合并支付单号', merchantId: '商户编号', merchant: '商户', rider: '骑手', items: '商品摘要', foodCents: '餐品金额', deliveryCents: '配送费', paidCents: '用户实付', refundedCents: '累计退款', foodRefundedCents: '累计退餐品', paymentStatus: '支付状态', fulfillStatus: '履约状态', refundStatus: '售后状态', settlementStatus: '结算状态', accepted: '商户已接单', pendingRefund: '存在处理中退款', locking: '结算锁定', paymentCount: '支付单退款次数', paidAt: '支付时间', completedAt: '完成时间', closedAt: '关闭时间', intervene: '待管理员介入', interveneReason: '介入原因', coordinationNotes: '协调记录',
  reason: '说明', type: '类型', amountCents: '金额', status: '处理状态', channelStatus: '渠道状态', createdAt: '创建时间', operator: '操作人', applicant: '申请主体', applicantType: '主体类型', time: '时间', subject: '账务主体', foodAmount: '餐品金额', deliveryAmount: '配送费', paidAmount: '实付金额', refundAmount: '退款金额', settlement: '结算状态', riderDue: '骑手应得', merchantBalance: '商户可扣余额', platformAdvance: '平台垫付', recovered: '已扣回', note: '说明',
}

const title = computed(() => String(route.meta.title || '订单列表'))
const columnSets: Record<string, Array<{ key: string; label: string; minWidth?: number }>> = {
  merchants: [
    { key: 'id', label: '商户编号', minWidth: 100 }, { key: 'name', label: '商户名称', minWidth: 140 },
    { key: 'contact', label: '联系人', minWidth: 100 }, { key: 'phone', label: '联系方式', minWidth: 130 },
    { key: 'auditStatus', label: '审核状态', minWidth: 105 }, { key: 'businessStatus', label: '营业状态', minWidth: 100 },
    { key: 'listed', label: '上架状态', minWidth: 95 }, { key: 'bindingCount', label: '小程序账号', minWidth: 105 },
    { key: 'joinedAt', label: '入驻时间', minWidth: 150 },
  ],
  riders: [
    { key: 'id', label: '骑手编号', minWidth: 100 }, { key: 'name', label: '姓名', minWidth: 110 },
    { key: 'phone', label: '联系方式', minWidth: 130 }, { key: 'gender', label: '性别', minWidth: 70 },
    { key: 'availableTime', label: '可送餐时间', minWidth: 220 }, { key: 'auditStatus', label: '审核状态', minWidth: 105 },
    { key: 'enabled', label: '接单状态', minWidth: 100 }, { key: 'bindingId', label: '小程序账号', minWidth: 105 },
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
  if (Number(row.missingMerchantRecipient) === 1 && Number(row.noTransfer) === 1
      && ['PENDING_REVIEW', 'PENDING_PAYOUT'].includes(status)) {
    return { ...row, reviewable, reason: '历史申请缺少收款人，请退回资金后由原申请人重新提交' }
  }
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

function canReturnLegacyWithdrawal(row: Row) {
  return auth.can('withdrawal.review') && row.status === 'PENDING_PAYOUT'
    && Number(row.missingMerchantRecipient) === 1 && Number(row.noTransfer) === 1
}

function canRetryWithdrawal(row: Row) {
  return auth.can('withdrawal.review') && ['original', 'new'].includes(row.retryMode)
}

const statusFields: Record<string, string> = { merchants: 'auditStatus', riders: 'auditStatus', orders: 'fulfillStatus', aftersales: 'status', withdrawals: 'status' }
const statusKey = computed(() => statusFields[section.value] || 'status')
const statusOptions = computed(() => [...new Set(currentRows.value.map((row) => String(row[statusKey.value] ?? '')).filter(Boolean))])
const orderMerchantOptions = computed(() => {
  if (section.value !== 'orders') return []
  const merchants = new Map<string, string>()
  for (const row of rows.value) {
    if (row.merchantId && !merchants.has(row.merchantId)) {
      merchants.set(row.merchantId, row.merchant || row.merchantId)
    }
  }
  const nameCounts = new Map<string, number>()
  for (const name of merchants.values()) nameCounts.set(name, (nameCounts.get(name) ?? 0) + 1)
  return Array.from(merchants, ([id, name]) => ({
    id,
    label: (nameCounts.get(name) ?? 0) > 1 ? `${name}（${id}）` : name,
  })).sort((a, b) => a.label.localeCompare(b.label, 'zh-CN'))
})
const filteredRows = computed(() => currentRows.value.filter((row) => {
  if (section.value === 'orders' && orderMerchantFilter.value && row.merchantId !== orderMerchantFilter.value) return false
  const text = Object.keys(row).map((key) => displayValue(row, key)).filter((value) => typeof value === 'string' || typeof value === 'number').join(' ').toLowerCase()
  return (!keyword.value || text.includes(keyword.value.toLowerCase())) && (!statusFilter.value || row[statusKey.value] === statusFilter.value)
}))
const { page, pageSize, total, pageRows } = useListPagination(() => filteredRows.value)
watch([keyword, statusFilter, orderMerchantFilter, section, financeSection], () => { page.value = 1 })

function isStore(row: Row) {
  return !['PROFILE_REVISION', 'ENROLLMENT_APPLICATION'].includes(row.businessStatus)
}

function comparable(value: string) {
  return value.trim().replace(/\s+/g, '').toLowerCase()
}

function looksRelated(row: Row, fields: { name: string; address: string; phone: string }) {
  return (['name', 'address', 'phone'] as const).some(key =>
    fields[key].trim() && comparable(fields[key]) === comparable(String(row[key] || '')))
}

const merchantMatches = computed(() => merchantEditingId.value ? [] : rows.value.filter(row =>
  (isStore(row) || row.businessStatus === 'ENROLLMENT_APPLICATION') && looksRelated(row, merchantForm)))
const availableStores = computed(() => {
  const draft = applicationTarget.value
  if (!draft) return []
  return rows.value.filter(row => isStore(row) && row.auditStatus === 'APPROVED')
    .map(row => ({ row, suggested: looksRelated(row, { name: draft.name, address: draft.address, phone: draft.phone }) }))
    .sort((left, right) => Number(right.suggested) - Number(left.suggested))
})

const canSortMerchants = computed(() => section.value === 'merchants' && auth.can('merchant.manage')
  && !keyword.value.trim() && !statusFilter.value)
const merchantPageIds = computed(() => pageRows.value.filter(isStore).map(row => String(row.id)))
const merchantReorder = useRowReorder(
  () => canSortMerchants.value && !loading.value ? merchantPageIds.value : [],
  order => adminApi.action('merchant.reorder', { ...order }),
  load,
)
const { saving: merchantOrderSaving, targetId: merchantDropTarget } = merchantReorder

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
  merchantDialog.value = false
  riderDialog.value = false
  bindingDialog.value = false
  applicationDialog.value = false
  printerForm.pkey = ''
  keyword.value = ''
  statusFilter.value = ''
  orderMerchantFilter.value = ''
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
  if (key === 'bindingCount') return !isStore(row) ? '—' : Number(value) ? '已绑定 ' + value + ' 个' : '待绑定'
  if (key === 'bindingId') return value ? '已绑定' : '待绑定'
  if (key === 'enabled') return riderEnabled(row) ? '启用中' : '已停用'
  if (key === 'availableTime') return row.availability === 'CUSTOM' ? value : stateLabel(row.availability)
  if (key === 'channelStatus') return value === 'LEGACY_DETAIL' ? row.channelDetail : stateLabel(value)
  if (key === 'interveneReason') return row.interveneCode ? interventionLabel(row.interveneCode) : value
  if (key === 'operator' && row.operatorCode) return value || stateLabel(row.operatorCode)
  if (['paidCents', 'amountCents', 'balanceCents', 'frozenCents', 'availableDelta', 'frozenDelta'].includes(key)) return money(Number(value || 0))
  if (key === 'eventType' && typeof value === 'string' && Object.hasOwn(accountEventLabels, value)) {
    return accountEventLabels[value]
  }
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

onUnmounted(() => {
  clearPhoto()
  clearMerchantEditorImages()
})

async function openDetails(row: Row) {
  clearPhoto()
  activeRow.value = row
  drawer.value = true
  const snapshot = ++snapshotRequest
  snapshots.value = []
  snapshotsError.value = ''
  snapshotsLoading.value = section.value === 'orders'
  if (snapshotsLoading.value) {
    try {
      const data = await adminApi.settlementSnapshots(String(row.id))
      if (snapshot === snapshotRequest) snapshots.value = data
    } catch (error) {
      if (snapshot === snapshotRequest) snapshotsError.value = error instanceof Error ? error.message : '快照加载失败'
    } finally {
      if (snapshot === snapshotRequest) snapshotsLoading.value = false
    }
  }
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
  if (key === 'listed') return merchantListingLabel(row)
  if (key === 'bindingId') return displayValue(row, key)
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
  orderMerchantFilter.value = ''
  page.value = 1
}
async function audit(row: Row, subject: string, decision: 'approve' | 'reject') {
  if (decision === 'approve' && row.businessStatus === 'ENROLLMENT_APPLICATION') {
    applicationChoice.value = undefined
    applicationStoreId.value = ''
    applicationTarget.value = row
    return
  }
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
async function approveApplication() {
  const row = applicationTarget.value
  if (!row || applicationSaving.value || !applicationChoice.value) return
  applicationSaving.value = true
  try {
    await adminApi.action('merchant.audit', { id: row.id, decision: 'approve',
      storeChoice: applicationChoice.value, merchantId: applicationStoreId.value })
    applicationDialog.value = false
    drawer.value = false
    ElMessage.success('入驻审核通过，微信账号已关联')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '审核失败')
  } finally {
    applicationSaving.value = false
  }
}

function openBinding(row: Row) {
  if (section.value !== 'merchants' && section.value !== 'riders') return
  bindingUserNumber.value = ''
  bindingTarget.value = { id: String(row.id), name: row.name, role: section.value === 'merchants' ? 'merchant' : 'rider' }
}

async function refreshBindingDetails(id: string, role: 'merchant' | 'rider') {
  if (role === 'rider') drawer.value = false
  await load()
  if (role === 'merchant' && merchantMembersId.value === id) {
    activeRow.value = rows.value.find(row => row.id === id) || activeRow.value
    merchantMembersVersion.value++
  }
}

async function saveBinding() {
  const target = bindingTarget.value
  if (!target || !bindingUserNumber.value || bindingSaving.value) return
  bindingSaving.value = true
  try {
    await adminApi.action(target.role + '.bind', { id: target.id, userNumber: bindingUserNumber.value })
    bindingDialog.value = false
    ElMessage.success('小程序账号已关联')
    await refreshBindingDetails(target.id, target.role)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '关联失败')
  } finally {
    bindingSaving.value = false
  }
}

async function unbind(row: Row, member?: MerchantMember) {
  const role = member ? 'merchant' : 'rider'
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      role === 'rider' ? '解除后原微信将失去档案访问权限。重新绑定后需提交学生证审核。'
        : '解除后该微信将失去商户权限。该成员有未结束的提现时，请先处理提现。',
      '解除 ' + (member?.nickname || member?.identityId || row.name) + ' 的小程序账号关联',
      { inputType: 'textarea', inputValidator: value => !!value?.trim() && value.trim().length <= 500 || '请填写 1 至 500 字的解绑原因',
        confirmButtonText: '解除关联', cancelButtonText: '取消', type: 'warning' },
    )
    reason = result.value
  } catch { return }
  try {
    await adminApi.action(role + '.unbind', { id: row.id, bindingId: member ? member.identityId : row.bindingId, reason })
    ElMessage.success('已解除关联')
    await refreshBindingDetails(String(row.id), role)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '解除关联失败')
  }
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
  if (!canReviewWithdrawal(row) || (decision === 'approve' && Number(row.missingMerchantRecipient) === 1)) return
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
async function returnLegacyWithdrawal(row: Row) {
  if (!canReturnLegacyWithdrawal(row) || retryingWithdrawalId.value) return
  retryingWithdrawalId.value = row.id
  try {
    const { value } = await ElMessageBox.prompt('退回后将解冻该笔金额，由原申请人重新提交。', '退回历史提现', {
      inputType: 'textarea',
      inputValidator: value => !!value?.trim() && value.trim().length <= 500 || '请填写 1 至 500 字的退回原因',
      confirmButtonText: '退回并解冻', cancelButtonText: '取消', type: 'warning',
    })
    await adminApi.action('withdrawal.return-unidentified', { id: row.id, reason: value })
    ElMessage.success('已退回申请并解冻资金')
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
function merchantListed(row: Row) {
  return row.listed === true || Number(row.listed) === 1
}

function merchantListingLabel(row: Row) {
  if (!isStore(row)) return '—'
  return merchantListed(row) && row.auditStatus === 'APPROVED' ? '已上架' : '已下架'
}

function riderEnabled(row: Row) {
  return row.enabled === true || Number(row.enabled) === 1
}

function openRiderForm(row?: Row) {
  riderEditingId.value = row?.id || ''
  Object.assign(riderForm, {
    name: row?.name || '', phone: row?.phone || '', gender: row?.gender || 'UNDISCLOSED',
    availability: row?.availability || 'MIDDAY_AND_EVENING', availableTime: row?.availableTime || '', userNumber: '',
  })
  riderDialog.value = true
}

async function saveRider() {
  if (riderSaving.value) return
  if (!riderForm.name || !riderForm.phone || (riderForm.availability === 'CUSTOM' && !riderForm.availableTime)) {
    ElMessage.warning('请填写姓名、手机号和可送餐时间')
    return
  }
  riderSaving.value = true
  try {
    await adminApi.action(riderEditingId.value ? 'rider.update' : 'rider.create', { ...riderForm, id: riderEditingId.value })
    riderDialog.value = false
    ElMessage.success('骑手已保存')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '骑手保存失败')
  } finally {
    riderSaving.value = false
  }
}

async function changeRiderStatus(row: Row) {
  if (riderChangingId.value) return
  const enabled = riderEnabled(row)
  riderChangingId.value = row.id
  try {
    if (enabled) {
      await ElMessageBox.confirm('停用后骑手不能领取新订单，已领取的订单仍可完成。', '停用骑手 ' + row.name, {
        confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning',
      })
    }
    await adminApi.action(enabled ? 'rider.disable' : 'rider.enable', { id: row.id })
    ElMessage.success(enabled ? '骑手已停用' : '骑手已启用')
    await load()
  } catch (error) {
    if (error instanceof Error) ElMessage.error(error.message)
  } finally {
    riderChangingId.value = ''
  }
}

async function openPricing(row: Row) {
  if (pricingLoading.value) return
  const id = String(row.id)
  pricingLoading.value = id
  try {
    const data = await adminApi.merchantPricing(id)
    if (section.value !== 'merchants') return
    pricingEnabled.value = data.enabled
    pricingVersion.value = data.version
    merchantPricing.value = pricingForm(data.pricing)
    pricingTarget.value = { id, name: String(row.name) }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '商户规则读取失败')
  } finally {
    pricingLoading.value = ''
  }
}

async function savePricing() {
  const pricing = merchantPricingValid.value
  if (!pricing || !pricingTarget.value || pricingSaving.value) return
  pricingSaving.value = true
  try {
    await adminApi.action('merchant.save-pricing', {
      id: pricingTarget.value.id, enabled: pricingEnabled.value, version: pricingVersion.value, pricing,
    })
    pricingDialog.value = false
    ElMessage.success('该商户的计费规则已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '商户规则保存失败')
  } finally {
    pricingSaving.value = false
  }
}

function clearSelectedMerchantImage() {
  if (selectedMerchantImageUrl.value) URL.revokeObjectURL(selectedMerchantImageUrl.value)
  selectedMerchantImageUrl.value = ''
  selectedMerchantImage.value = undefined
  merchantImageError.value = ''
}

function clearMerchantEditorImages() {
  merchantImageRequest?.abort()
  merchantImageRequest = undefined
  clearSelectedMerchantImage()
  if (currentMerchantImageUrl.value) URL.revokeObjectURL(currentMerchantImageUrl.value)
  currentMerchantImageUrl.value = ''
}

const selectMerchantImage: UploadProps['onChange'] = ({ raw: file }) => {
  merchantImageUpload.value?.clearFiles()
  if (!file || merchantSaving.value || !merchantDialog.value) return
  if (!isPhotoFile(file)) {
    merchantImageError.value = '请选择不超过 1 MB 的 JPEG 或 PNG 图片'
    return
  }
  clearSelectedMerchantImage()
  selectedMerchantImage.value = file
  selectedMerchantImageUrl.value = URL.createObjectURL(file)
}

async function loadMerchantEditorPhoto(id: string) {
  const request = new AbortController()
  merchantImageRequest = request
  try {
    const blob = await adminApi.merchantPhoto(id, request.signal)
    if (!request.signal.aborted) currentMerchantImageUrl.value = URL.createObjectURL(blob)
  } catch {
    if (!request.signal.aborted && !selectedMerchantImage.value) {
      merchantImageError.value = '原图暂时无法加载，可以重新选择图片'
    }
  }
}

function openMerchantForm(row?: Row) {
  clearMerchantEditorImages()
  merchantEditingId.value = row?.id || ''
  merchantCanOpen.value = !row || row.auditStatus === 'APPROVED'
  Object.assign(merchantForm, {
    name: row?.name || '', contact: row?.contact || '', phone: row?.phone || '',
    address: row?.address || '', open: row?.businessStatus === 'STORE_OPEN', userNumber: '',
  })
  merchantDialog.value = true
  if (row?.hasPhoto) void loadMerchantEditorPhoto(String(row.id))
}

async function saveMerchant() {
  if (merchantSaving.value) return
  if ([merchantForm.name, merchantForm.contact, merchantForm.phone, merchantForm.address].some(value => !value.trim())) {
    ElMessage.warning('请填写商户名称、联系人、联系电话和店铺地址')
    return
  }
  merchantSaving.value = true
  try {
    const image = selectedMerchantImage.value ? await encodePhoto(selectedMerchantImage.value) : undefined
    await adminApi.action(merchantEditingId.value ? 'merchant.update' : 'merchant.create', {
      ...merchantForm, id: merchantEditingId.value, image,
    })
    merchantDialog.value = false
    ElMessage.success('商户已保存')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '商户保存失败')
  } finally {
    merchantSaving.value = false
  }
}

async function changeMerchant(row: Row, remove = false) {
  if (merchantChangingId.value) return
  const listed = !merchantListed(row)
  const label = remove ? '删除' : listed ? '上架' : '下架'
  merchantChangingId.value = row.id
  try {
    if (remove || !listed) {
      await ElMessageBox.confirm(remove ? '商户将从列表和用户端移除，历史订单和账务记录仍可查询。'
        : '下架后，用户端将隐藏该商户及其商品。', label + '商户 ' + row.name, {
        confirmButtonText: label, cancelButtonText: '取消', type: 'warning',
      })
    }
    await adminApi.action(remove ? 'merchant.delete' : 'merchant.availability', { id: row.id, listed })
    ElMessage.success('商户已' + label)
    await load()
  } catch (error) {
    if (error instanceof Error) ElMessage.error(error.message)
  } finally {
    merchantChangingId.value = ''
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
    <el-button v-if="section === 'merchants' && auth.can('merchant.manage')" type="primary" @click="openMerchantForm()">新增商户</el-button>
    <el-button v-if="section === 'riders' && auth.can('rider.manage')" type="primary" @click="openRiderForm()">新增骑手</el-button>
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
        <el-select v-if="section === 'orders'" v-model="orderMerchantFilter" clearable filterable
          placeholder="全部商户" aria-label="按商户筛选订单" class="order-merchant-select">
          <el-option v-for="merchant in orderMerchantOptions" :key="merchant.id" :label="merchant.label" :value="merchant.id" />
        </el-select>
        <el-select v-model="statusFilter" clearable placeholder="全部状态" aria-label="筛选状态" class="status-select"><el-option v-for="status in statusOptions" :key="status" :label="stateLabel(status)" :value="status" /></el-select>
        <el-button @click="searchList"><el-icon><Search /></el-icon>查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </div>
    <el-table :data="pageRows" row-key="id" v-loading="loading || merchantOrderSaving" stripe class="data-table"
      @dragover="merchantReorder.over" @drop="merchantReorder.drop">
      <el-table-column v-if="canSortMerchants" label="排序" width="64" align="center">
        <template #default="{ row }">
          <RowDragHandle v-if="isStore(row)" :id="row.id" :label="row.name"
            :disabled="loading || merchantOrderSaving || merchantPageIds.length < 2" :target="merchantDropTarget === row.id"
            @start="merchantReorder.start($event, row.id)" @end="merchantReorder.end"
            @move="merchantReorder.keydown($event, row.id)" />
        </template>
      </el-table-column>
      <el-table-column v-if="section === 'orders'" label="介入提醒" width="125" fixed="left"><template #default="scope"><el-tag v-if="scope.row.intervene" type="warning" effect="light" round>待管理员介入</el-tag><span v-else class="muted">—</span></template></el-table-column>
      <el-table-column v-for="column in columns" :key="column.key" :prop="column.key" :label="column.label" :min-width="column.minWidth || 110" show-overflow-tooltip>
        <template #default="scope">
          <el-tag v-if="column.key === 'listed' && scope.row.businessStatus !== 'PROFILE_REVISION'"
            :type="merchantListed(scope.row) && scope.row.auditStatus === 'APPROVED' ? 'success' : 'info'" effect="light" round>
            {{ merchantListingLabel(scope.row) }}
          </el-tag>
          <span v-else-if="column.key === 'listed'">—</span>
          <el-tag v-else-if="column.key === 'enabled'" :type="riderEnabled(scope.row) ? 'success' : 'info'" effect="light" round>
            {{ displayValue(scope.row, column.key) }}
          </el-tag>
          <el-tag v-else-if="['auditStatus','businessStatus','fulfillStatus','refundStatus','settlementStatus','status'].includes(column.key)" :type="statusType(String(scope.row[column.key]))" effect="light" round>{{ displayValue(scope.row, column.key) }}</el-tag>
          <span v-else-if="column.key === 'amountCents' || column.key === 'paidCents' || column.key === 'balanceCents'">{{ money(Number(scope.row[column.key] || 0)) }}</span>
          <span v-else>{{ displayValue(scope.row, column.key) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" :width="section === 'riders' && auth.can('rider.manage') ? 310 : section === 'merchants' && auth.can('merchant.manage') ? 395 : section === 'merchants' && auth.can('merchant.printer.edit') ? 275 : section === 'merchants' || section === 'riders' ? 220 : section === 'aftersales' ? 190 : section === 'withdrawals' ? 250 : 170" fixed="right">
        <template #default="scope">
          <template v-if="section === 'merchants' || section === 'riders'">
            <el-button link type="primary" @click="runRowAction(scope.row)">{{ rowActionText(scope.row) }}</el-button>
            <el-button v-if="section === 'merchants' && auth.can('merchant.printer.edit') && isStore(scope.row)"
              link type="primary" :loading="printerLoadingId === scope.row.id" @click="openPrinter(scope.row)">打印机设置</el-button>
            <template v-if="section === 'merchants' && auth.can('merchant.manage') && isStore(scope.row)">
              <el-button link type="primary" @click="openMerchantForm(scope.row)">编辑</el-button>
              <el-button link type="primary" :loading="pricingLoading === scope.row.id" @click="openPricing(scope.row)">计费规则</el-button>
              <el-button v-if="scope.row.auditStatus === 'APPROVED'" link :type="merchantListed(scope.row) ? 'warning' : 'success'"
                :disabled="merchantChangingId === scope.row.id" @click="changeMerchant(scope.row)">
                {{ merchantListed(scope.row) ? '下架' : '上架' }}
              </el-button>
              <el-button link type="danger" :disabled="merchantChangingId === scope.row.id"
                @click="changeMerchant(scope.row, true)">删除</el-button>
            </template>
            <template v-if="section === 'riders' && auth.can('rider.manage')">
              <el-button link type="primary" @click="openRiderForm(scope.row)">编辑</el-button>
              <el-button link :type="riderEnabled(scope.row) ? 'danger' : 'success'" :loading="riderChangingId === scope.row.id"
                @click="changeRiderStatus(scope.row)">{{ riderEnabled(scope.row) ? '停用' : '启用' }}</el-button>
            </template>
            <template v-if="scope.row.auditStatus === 'PENDING_REVIEW' && auth.can(section === 'merchants' ? 'merchant.audit' : 'rider.audit')"><el-button link type="success" @click="audit(scope.row, section === 'merchants' ? (scope.row.businessStatus === 'PROFILE_REVISION' ? '商户资料变更' : '商户入驻') : '骑手入驻', 'approve')">通过</el-button><el-button link type="danger" @click="audit(scope.row, section === 'merchants' ? (scope.row.businessStatus === 'PROFILE_REVISION' ? '商户资料变更' : '商户入驻') : '骑手入驻', 'reject')">驳回</el-button></template>
          </template>
          <template v-else-if="section === 'orders'"><el-button link type="primary" @click="handlePrimary(scope.row)">订单详情</el-button><el-button v-if="scope.row.intervene && auth.can('order.coordinate')" link type="warning" @click="coordinate(scope.row as unknown as Order)">记录协调</el-button></template>
          <template v-else-if="section === 'aftersales'"><el-button link type="primary" @click="openDetails(scope.row)">详情</el-button></template>
          <template v-else-if="section === 'withdrawals'">
            <el-button link type="primary" @click="openDetails(scope.row)">详情</el-button>
            <template v-if="canReviewWithdrawal(scope.row)">
              <el-button v-if="Number(scope.row.missingMerchantRecipient) !== 1" link type="success"
                @click="reviewWithdrawal(scope.row, 'approve')">通过</el-button>
              <el-button link type="danger" @click="reviewWithdrawal(scope.row, 'reject')">拒绝</el-button>
            </template>
            <el-button v-if="canReturnLegacyWithdrawal(scope.row)" link type="warning"
              :loading="retryingWithdrawalId === scope.row.id" @click="returnLegacyWithdrawal(scope.row)">退回历史申请</el-button>
            <el-button v-if="canRetryWithdrawal(scope.row)" link type="primary"
              :loading="retryingWithdrawalId === scope.row.id" @click="retryWithdrawal(scope.row)">重试打款</el-button>
          </template>
        </template>
      </el-table-column>
      <template #empty><el-empty description="没有符合条件的记录" /></template>
    </el-table>
    <ListPagination v-model:page="page" v-model:page-size="pageSize" :total="total" :disabled="loading || merchantOrderSaving" />
  </div>

  <el-dialog v-model="bindingDialog" :title="'关联小程序账号 · ' + bindingTarget?.name" width="min(480px, 94vw)"
    :close-on-click-modal="!bindingSaving" :show-close="!bindingSaving">
    <el-form v-if="bindingTarget" label-position="top" :disabled="bindingSaving" @submit.prevent="saveBinding">
      <el-form-item label="小程序账号">
        <AccountPicker v-model="bindingUserNumber" :role="bindingTarget.role" />
      </el-form-item>
      <div class="form-actions">
        <el-button @click="bindingDialog = false">取消</el-button>
        <el-button native-type="submit" type="primary" :loading="bindingSaving" :disabled="!bindingUserNumber">确认关联</el-button>
      </div>
    </el-form>
  </el-dialog>

  <el-dialog v-model="applicationDialog" :title="'审核商户入驻 · ' + applicationTarget?.name" width="min(520px, 94vw)"
    :close-on-click-modal="!applicationSaving" :show-close="!applicationSaving">
    <el-form v-if="applicationTarget" label-position="top" :disabled="applicationSaving" @submit.prevent="approveApplication">
      <p>{{ applicationTarget.address }} · {{ applicationTarget.contact }} · {{ applicationTarget.phone }}</p>
      <el-form-item label="通过后的处理方式">
        <el-radio-group v-model="applicationChoice" aria-label="通过后的处理方式">
          <el-radio value="existing">关联已有商户</el-radio>
          <el-radio value="create">确认新店并建档</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="applicationChoice === 'existing'" label="选择商户" required>
        <el-select v-model="applicationStoreId" filterable placeholder="搜索商户名称、地址或编号" aria-label="选择商户">
          <el-option v-for="{ row, suggested } in availableStores" :key="row.id" :value="row.id"
            :label="[row.name, row.address, row.id, suggested ? '可能匹配' : ''].filter(Boolean).join(' · ')" />
        </el-select>
      </el-form-item>
      <div v-if="applicationChoice === 'create' && availableStores.some(store => store.suggested)" class="muted">
        系统发现可能匹配的已有档案，请核对后确认是否为新店。
      </div>
      <div class="form-actions">
        <el-button @click="applicationDialog = false">取消</el-button>
        <el-button native-type="submit" type="primary" :loading="applicationSaving"
          :disabled="!applicationChoice || (applicationChoice === 'existing' && !applicationStoreId)">确认通过</el-button>
      </div>
    </el-form>
  </el-dialog>

  <el-dialog v-model="riderDialog" :title="riderEditingId ? '编辑骑手' : '新增骑手'" width="min(500px, 94vw)"
    :close-on-click-modal="!riderSaving" :close-on-press-escape="!riderSaving" :show-close="!riderSaving">
    <el-form label-position="top" :disabled="riderSaving" @submit.prevent="saveRider">
      <el-form-item label="姓名" required><el-input v-model.trim="riderForm.name" maxlength="120" /></el-form-item>
      <el-form-item label="手机号" required><el-input v-model.trim="riderForm.phone" type="tel" maxlength="32" /></el-form-item>
      <el-form-item label="性别">
        <el-select v-model="riderForm.gender">
          <el-option v-for="value in riderGenders" :key="value" :label="stateLabel(value)" :value="value" />
        </el-select>
      </el-form-item>
      <el-form-item label="可送餐时间" required>
        <el-select v-model="riderForm.availability">
          <el-option v-for="value in riderAvailability" :key="value" :label="stateLabel(value)" :value="value" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="riderForm.availability === 'CUSTOM'" label="自定义时段" required>
        <el-input v-model.trim="riderForm.availableTime" placeholder="例如 11:00-13:30" maxlength="160" />
      </el-form-item>
      <el-form-item v-if="!riderEditingId && auth.can('user.view')" label="关联小程序账号">
        <AccountPicker v-model="riderForm.userNumber" role="rider" />
        <div class="muted">可留空后补绑。骑手关联后需提交学生证审核。</div>
      </el-form-item>
      <div class="form-actions">
        <el-button @click="riderDialog = false">取消</el-button>
        <el-button native-type="submit" type="primary" :loading="riderSaving">保存</el-button>
      </div>
    </el-form>
  </el-dialog>

  <el-dialog v-model="pricingDialog" :title="pricingTarget?.name + ' · 计费规则'" width="min(510px, 94vw)"
    :close-on-click-modal="!pricingSaving" :close-on-press-escape="!pricingSaving" :show-close="!pricingSaving">
    <el-form label-position="top" :disabled="pricingSaving" @submit.prevent="savePricing">
      <el-form-item label="启用商户独立规则">
        <el-switch v-model="pricingEnabled" active-text="使用本店规则" inactive-text="使用平台默认"
          aria-label="启用商户独立规则" />
      </el-form-item>
      <PricingFields :form="merchantPricing" />
      <div class="form-actions">
        <el-button @click="pricingDialog = false">取消</el-button>
        <el-button native-type="submit" type="primary" :loading="pricingSaving" :disabled="!merchantPricingValid">保存</el-button>
      </div>
    </el-form>
  </el-dialog>

  <el-dialog v-model="merchantDialog" :title="merchantEditingId ? '编辑商户' : '新增商户'" width="min(540px, 94vw)"
    :close-on-click-modal="!merchantSaving" :close-on-press-escape="!merchantSaving" :show-close="!merchantSaving"
    @close="clearMerchantEditorImages">
    <el-form label-position="top" :disabled="merchantSaving" @submit.prevent="saveMerchant">
      <el-form-item label="商户名称" required><el-input v-model.trim="merchantForm.name" maxlength="120" /></el-form-item>
      <el-form-item label="联系人" required><el-input v-model.trim="merchantForm.contact" maxlength="80" /></el-form-item>
      <el-form-item label="联系电话" required><el-input v-model.trim="merchantForm.phone" type="tel" maxlength="32" /></el-form-item>
      <el-form-item label="店铺地址" required><el-input v-model.trim="merchantForm.address" maxlength="255" /></el-form-item>
      <div v-if="merchantMatches.length" class="detail-section">
        <div class="muted">发现可能对应的商户档案或入驻申请，请先核对。</div>
        <div v-for="match in merchantMatches" :key="match.id">
          <el-button link type="primary" @click="merchantDialog = false; openDetails(match)">
            {{ isStore(match) ? '已有档案' : '待审申请' }} · {{ match.name }} · {{ match.address }}
          </el-button>
        </div>
      </div>
      <el-form-item v-if="!merchantEditingId && auth.can('user.view')" label="关联小程序账号">
        <AccountPicker v-model="merchantForm.userNumber" role="merchant" />
        <div class="muted">可留空后补绑。已有入驻申请时，从待审申请中关联。</div>
      </el-form-item>
      <el-form-item v-if="merchantCanOpen" label="营业状态">
        <el-switch v-model="merchantForm.open" active-text="营业中" inactive-text="休息中" />
      </el-form-item>
      <el-form-item label="商户图片">
        <div class="merchant-image-field">
          <ElUpload ref="merchantImageUpload" drag accept="image/jpeg,image/png" :auto-upload="false"
            :show-file-list="false" :disabled="merchantSaving" :on-change="selectMerchantImage">
            <el-icon class="el-icon--upload"><Upload /></el-icon>
            <div class="el-upload__text">
              拖拽图片到此处，或<em>{{ selectedMerchantImageUrl || currentMerchantImageUrl ? '点击更换' : '点击选择' }}</em>
            </div>
            <template #tip>
              <p v-if="merchantImageError" class="el-upload__tip merchant-image-error" role="alert">{{ merchantImageError }}</p>
              <p v-else class="el-upload__tip">JPEG 或 PNG，最大 1 MB</p>
            </template>
          </ElUpload>
          <div v-if="selectedMerchantImageUrl || currentMerchantImageUrl" class="merchant-image-selection">
            <ElImage class="merchant-image-preview" :src="selectedMerchantImageUrl || currentMerchantImageUrl" fit="cover"
              :alt="selectedMerchantImage ? '待保存的商户图片' : '当前商户图片'"
              :preview-src-list="[selectedMerchantImageUrl || currentMerchantImageUrl]" preview-teleported />
            <el-button v-if="selectedMerchantImage" link type="primary" :disabled="merchantSaving" @click="clearSelectedMerchantImage">
              撤销所选图片
            </el-button>
          </div>
        </div>
      </el-form-item>
      <div class="form-actions">
        <el-button @click="merchantDialog = false">取消</el-button>
        <el-button native-type="submit" type="primary" :loading="merchantSaving">保存</el-button>
      </div>
    </el-form>
  </el-dialog>

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
      <section v-if="pickupQrOrderId" class="detail-section">
        <h3>骑手领取二维码</h3>
        <img v-if="pickupQrUrl" :src="pickupQrUrl" class="pickup-qr" width="240" height="240"
          alt="打印小票上的骑手领取二维码">
        <p v-else-if="pickupQrError" class="muted" role="alert">{{ pickupQrError }}</p>
        <p v-else class="muted" role="status">正在加载二维码…</p>
      </section>
      <div v-if="section === 'riders'" class="detail-section">
        <h3>学生身份材料</h3>
        <img v-if="merchantPhoto" :src="merchantPhoto" alt="骑手提交的学生证照片"
          style="display:block;max-width:100%;max-height:280px;object-fit:contain">
        <p v-else class="muted">{{ photoError || (activeRow.hasPhoto ? '正在加载照片…' : '暂无学生证照片') }}</p>
      </div>
      <div class="detail-grid">
        <template v-for="[key] in Object.entries(activeRow)" :key="key"><div v-if="!(section === 'merchants' && key === 'orderCount') && !['coordinationNotes','snapshot','hasPhoto','bindingId','bindingCount','missingMerchantRecipient','noTransfer','reviewable','retryMode','retryRequested','availability','interveneCode','operatorCode','channelDetail'].includes(key)" class="detail-item"><span>{{ keyLabel(key) }}</span><strong>{{ detailValue(activeRow, key) }}</strong></div></template>
      </div>
      <div v-if="section === 'withdrawals' && canReturnLegacyWithdrawal(activeRow)" class="detail-section">
        <el-button type="warning" :loading="retryingWithdrawalId === activeRow.id"
          @click="returnLegacyWithdrawal(activeRow)">退回历史申请</el-button>
      </div>
      <div v-if="section === 'withdrawals' && canRetryWithdrawal(activeRow)" class="detail-section">
        <el-button type="primary" :loading="retryingWithdrawalId === activeRow.id"
          @click="retryWithdrawal(activeRow)">重试打款</el-button>
      </div>
      <template v-if="section === 'orders'">
        <div class="detail-section"><h3>合并支付单关联</h3><div v-for="linked in relatedOrders.filter((order) => order.paymentId === activeRow?.paymentId)" :key="linked.id" class="linked-order"><strong>{{ linked.id }} · {{ linked.merchant }}</strong><span>{{ stateLabel(linked.fulfillStatus) }} · {{ money(linked.paidCents) }}</span></div></div>
        <div v-if="activeRow.intervene && auth.can('order.coordinate')" class="detail-section"><el-button type="primary" plain @click="coordinateFromDrawer">记录协调备注</el-button></div>
        <section class="detail-section" v-loading="snapshotsLoading">
          <h3>结算快照</h3>
          <p v-if="snapshotsError" role="alert">{{ snapshotsError }}</p>
          <p v-else-if="!snapshotsLoading && !snapshots.length" class="muted">暂无结算快照</p>
          <el-collapse v-else>
            <el-collapse-item v-for="entry in snapshots" :key="entry.eventType" :name="entry.eventType">
              <template #title>{{ displayValue(entry, 'eventType') }} · {{ money(entry.amountCents) }}</template>
              <div class="detail-grid">
                <div class="detail-item"><span>规则</span><strong>{{ snapshotSources[entry.ruleSource] }} · 版本 {{ entry.ruleVersion }}</strong></div>
                <div class="detail-item"><span>入账时间</span><strong>{{ new Date(entry.settledAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}</strong></div>
                <div v-if="entry.settlementWaitMinutes != null" class="detail-item">
                  <span>结算等待时长</span><strong>{{ entry.settlementWaitMinutes }} 分钟</strong>
                </div>
                <div v-if="entry.eligibleAt != null" class="detail-item">
                  <span>达到结算时间</span><strong>{{ new Date(entry.eligibleAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}</strong>
                </div>
                <div v-for="field in pricingFields" :key="field.key" class="detail-item">
                  <span>{{ field.label }}</span><strong>{{ formatPricing(entry.rules[field.key]) }}</strong>
                </div>
                <div class="detail-item"><span>餐品结算基数</span><strong>{{ money(entry.foodBaseCents) }}</strong></div>
                <div class="detail-item"><span>原配送费</span><strong>{{ money(entry.originalDeliveryCents) }}</strong></div>
                <div class="detail-item"><span>退款后配送费</span><strong>{{ money(entry.deliveryBaseCents) }}</strong></div>
                <div class="detail-item"><span>餐品已退</span><strong>{{ money(entry.foodRefundedCents) }}</strong></div>
                <div class="detail-item"><span>配送费已退</span><strong>{{ money(entry.deliveryRefundedCents) }}</strong></div>
                <div class="detail-item"><span>实际餐品抽成</span><strong>{{ money(entry.foodCommissionCents) }}</strong></div>
                <div class="detail-item"><span>实际配送费抽成</span><strong>{{ money(entry.deliveryCommissionCents) }}</strong></div>
                <div class="detail-item"><span>骑手应得</span><strong>{{ money(entry.riderDueCents) }}</strong></div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </section>
        <div class="detail-section"><h3>协调记录</h3><p v-for="note in activeRow.coordinationNotes || []" :key="note.id">
          {{ note.time }} · {{ note.actorCode ? stateLabel(note.actorCode) : note.actor }} ·
          {{ note.previousStatus && note.nextStatus ? stateLabel(note.previousStatus) + ' → ' + stateLabel(note.nextStatus) : note.note }}
        </p><p v-if="!activeRow.coordinationNotes?.length" class="muted">暂无协调备注</p></div>
      </template>
      <div v-if="section === 'merchants' && isStore(activeRow)" class="detail-section">
        <h3>已绑定小程序账号</h3>
        <p class="muted">{{ displayValue(activeRow, 'bindingCount') }}</p>
        <template v-if="auth.can('user.view')">
          <div v-loading="merchantMembersLoading">
            <p v-if="merchantMembersError" role="alert">{{ merchantMembersError }}
              <el-button link type="primary" @click="merchantMembersVersion++">重新加载</el-button>
            </p>
            <div v-for="member in merchantMembers" :key="member.identityId" class="linked-order">
              <div>
                <strong>{{ member.nickname || '小程序账号' }}</strong>
                <div class="muted">
                  {{ member.userNumber ? '用户编号 ' + member.userNumber + ' · ' : '' }}商户账号 ID {{ member.identityId }}
                </div>
              </div>
              <el-button v-if="auth.can('merchant.manage')" link type="danger"
                @click="unbind(activeRow, member)">解除关联</el-button>
            </div>
          </div>
          <el-button v-if="auth.can('merchant.manage')" link type="primary" @click="openBinding(activeRow)">添加账号</el-button>
        </template>
      </div>
      <div v-if="section === 'riders'" class="detail-section">
        <h3>小程序账号</h3>
        <span>{{ activeRow.bindingId ? '已绑定' : '待绑定' }}</span>
        <template v-if="auth.can('user.view') && auth.can('rider.manage')">
          <el-button v-if="activeRow.bindingId" link type="danger" @click="unbind(activeRow)">解除关联</el-button>
          <el-button v-else link type="primary" @click="openBinding(activeRow)">关联账号</el-button>
        </template>
      </div>
      <div v-if="section === 'merchants' && activeRow.businessStatus === 'ENROLLMENT_APPLICATION' && auth.can('merchant.audit')" class="detail-section">
        <el-button type="success" @click="audit(activeRow, '商户入驻', 'approve')">审核通过</el-button>
        <el-button type="danger" plain @click="audit(activeRow, '商户入驻', 'reject')">驳回申请</el-button>
      </div>
      <div v-if="section === 'merchants' && isStore(activeRow) && auth.can('order.view')" class="detail-section">
        <el-button link type="primary" @click="router.push('/orders')">进入订单列表</el-button>
      </div>
      <template v-if="section === 'riders'">
        <div class="detail-section"><h3>配送记录</h3><div v-for="linked in relatedOrders.filter((order) => order.rider === activeRow?.name).slice(0, 5)" :key="linked.id" class="linked-order"><strong>{{ linked.id }} · {{ linked.merchant }}</strong><span>{{ stateLabel(linked.fulfillStatus) }}</span></div><p v-if="!relatedOrders.some((order) => order.rider === activeRow?.name)" class="muted">暂无已分配的配送订单</p></div>
      </template>
    </template>
  </el-drawer>

</template>


<style scoped>
.pickup-qr { display: block; max-width: 100%; height: auto; }
.order-merchant-select { width: 190px; max-width: 100%; }
.order-merchant-select :deep(.el-select__wrapper.is-focused .el-select__input:focus-visible) { outline: none; }
.merchant-image-field { width: 100%; }
.merchant-image-field :deep(.el-upload-dragger) { padding: 12px; }
.merchant-image-field :deep(.el-icon--upload) { margin: 0 0 6px; font-size: 30px; }
.merchant-image-selection { display: flex; align-items: center; gap: 12px; margin-top: 12px; }
.merchant-image-preview { flex: 0 0 72px; width: 72px; height: 72px; border-radius: 8px; }
.merchant-image-error { color: var(--el-color-danger); }
</style>
