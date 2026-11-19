<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api/admin'
import { useAuthStore } from '../stores/auth'
import { money, toCents } from '../domain/rules'
import type { ConfigSnapshot } from '../domain/types'

const loading = ref(false)
const auth = useAuthStore()
const saving = ref(false)
const history = ref<ConfigSnapshot[]>([])
const form = reactive({
  version: '', fee: '4.00', foodRate: 10, deliveryRate: 20, regularBatch: 30, peakBatch: 20,
  serviceStart: '10:30', serviceEnd: '20:30',
  peakPeriods: [] as Array<{ start: string; end: string }>, acceptTimeout: 30, reminderInterval: 15,
  effectiveAt: '', operator: '',
})
const preview = computed(() => {
  const fee = /^\d+(\.\d{0,2})?$/.test(form.fee) ? Math.round(Number(form.fee) * 100) : 0
  const foodCommission = Math.round(2000 * form.foodRate / 100)
  const deliveryCommission = Math.round(fee * form.deliveryRate / 100)
  return {
    fee: money(fee), merchant: money(2000 - foodCommission),
    rider: money(fee - deliveryCommission), platform: money(foodCommission + deliveryCommission),
    total: money(2000 + fee),
  }
})
const scheduleError = computed(() => {
  if (!form.serviceStart || !form.serviceEnd || form.serviceStart >= form.serviceEnd) return '配送服务开始时间须早于结束时间'
  const periods = [...form.peakPeriods].sort((a, b) => a.start.localeCompare(b.start))
  for (let i = 0; i < periods.length; i++) {
    const period = periods[i]!
    if (!period.start || !period.end || period.start >= period.end) return '请补全高峰时段，开始时间须早于结束时间'
    if (period.start < form.serviceStart || period.end > form.serviceEnd) return '高峰时段须在配送服务时间内'
    if (i > 0 && period.start < periods[i - 1]!.end) return '高峰时段不能重叠'
  }
  return ''
})

async function load() {
  loading.value = true
  try {
    const result = await adminApi.workspace<{ config: ConfigSnapshot; history: ConfigSnapshot[] }>('config')
    setForm(result.config)
    history.value = result.history
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '配置读取失败。') }
  finally { loading.value = false }
}
function setForm(config: ConfigSnapshot) {
  const periods = config.peakPeriods ?? (config.peakStart < config.peakEnd
    ? [{ start: config.peakStart, end: config.peakEnd }] : [])
  Object.assign(form, { ...config, peakPeriods: periods.map(period => ({ ...period })), fee: (config.feeCents / 100).toFixed(2) })
}
function reset() { void load(); ElMessage.info('已恢复最近保存的配置，本次未保存修改已取消。') }
async function save() {
  try {
    const feeCents = toCents(form.fee)
    if (scheduleError.value) {
      ElMessage.warning(scheduleError.value)
      return
    }
    await ElMessageBox.confirm('保存后生成新的运营配置版本。历史订单继续使用下单时的规则快照，不会回算。', '确认保存运营配置', { confirmButtonText: '确认保存', cancelButtonText: '继续编辑' })
    saving.value = true
    await adminApi.action('config.save', { patch: { ...form, feeCents } })
    ElMessage.success('运营配置已保存，新版本仅用于后续业务。')
    await load()
  } catch (error) {
    if (error instanceof Error && !['cancel', 'close'].includes(error.message)) ElMessage.error(error.message)
  } finally { saving.value = false }
}
onMounted(() => { void load() })
</script>

<template>
  <div class="page-heading">
    <h1>运营配置</h1>
  </div>
  <div class="config-layout operations-config" v-loading="loading">
    <section class="panel-card config-form-panel">
      <div class="section-heading"><h2>配送规则</h2></div>
      <el-form label-position="top" class="config-grid" :disabled="!auth.can('config.edit') || saving">
        <div class="fee-fields">
        <el-form-item label="每笔商户拆单固定配送费"><el-input v-model="form.fee" inputmode="decimal"><template #prepend>¥</template><template #append>元 / 单</template></el-input></el-form-item>
        <el-form-item label="餐品金额平台抽成"><el-input-number v-model="form.foodRate" :min="0" :max="100" :precision="2" controls-position="right"><template #suffix>%</template></el-input-number></el-form-item>
        <el-form-item label="配送费平台抽成"><el-input-number v-model="form.deliveryRate" :min="0" :max="100" :precision="2" controls-position="right"><template #suffix>%</template></el-input-number></el-form-item>
        </div>
        <div class="config-subhead">配送时段与发车批次</div>
        <el-form-item label="配送服务时段"><div class="time-range"><el-time-select v-model="form.serviceStart" start="00:00" step="00:15" end="23:45" /><span>至</span><el-time-select v-model="form.serviceEnd" start="00:00" step="00:15" end="23:45" /></div></el-form-item>
        <el-form-item label="常规批次间隔"><el-input-number v-model="form.regularBatch" :min="1" :max="1440" controls-position="right"><template #suffix>分钟</template></el-input-number></el-form-item>
        <section class="peak-section" aria-labelledby="peak-title">
          <div class="peak-heading">
            <h3 id="peak-title">高峰时段</h3>
            <div class="peak-controls">
              <el-form-item label="高峰批次间隔" label-position="left" class="peak-interval">
                <el-input-number v-model="form.peakBatch" :min="1" :max="1440" controls-position="right">
                  <template #suffix>分钟</template>
                </el-input-number>
              </el-form-item>
              <el-button v-if="auth.can('config.edit')" type="primary" plain
                :disabled="saving || form.peakPeriods.length >= 48" @click="form.peakPeriods.push({ start: '', end: '' })">
                <el-icon><Plus /></el-icon>新增时段
              </el-button>
            </div>
          </div>
          <div class="peak-periods">
            <div v-for="(period, index) in form.peakPeriods" :key="index" class="peak-period-row">
              <div class="time-range">
                <el-time-select v-model="period.start" start="00:00" step="00:15" end="23:45"
                  placeholder="开始时间" :aria-label="`高峰时段 ${index + 1} 开始时间`" />
                <span>至</span>
                <el-time-select v-model="period.end" start="00:00" step="00:15" end="23:45"
                  placeholder="结束时间" :aria-label="`高峰时段 ${index + 1} 结束时间`" />
              </div>
              <el-button v-if="auth.can('config.edit')" class="peak-remove" text :disabled="saving" :aria-label="`删除高峰时段 ${index + 1}`"
                @click="form.peakPeriods.splice(index, 1)">删除</el-button>
            </div>
            <p v-if="!form.peakPeriods.length" class="peak-empty">未设置高峰时段，全天按常规批次配送</p>
          </div>
          <p v-if="scheduleError" role="alert" class="schedule-error">{{ scheduleError }}</p>
        </section>
        <div class="config-subhead">商户接单与提醒</div>
        <el-form-item label="支付后接单超时"><el-input-number v-model="form.acceptTimeout" :min="1" :max="1440" controls-position="right"><template #suffix>分钟</template></el-input-number></el-form-item>
        <el-form-item label="管理员提醒间隔"><el-input-number v-model="form.reminderInterval" :min="1" :max="1440" controls-position="right"><template #suffix>分钟</template></el-input-number></el-form-item>
      </el-form>
      <div class="config-actions"><el-button :disabled="saving" @click="reset">取消修改</el-button><el-button v-if="auth.can('config.edit')" type="primary" :loading="saving" :disabled="loading || !!scheduleError" @click="save">保存并生成新版本</el-button></div>
    </section>
    <aside class="config-aside">
      <section class="panel-card rule-preview">
        <h3>金额分配预览</h3><p>以餐品 ¥20.00、配送费 {{ preview.fee }} 为例</p>
        <div class="preview-row"><span>商户应得</span><strong>{{ preview.merchant }}</strong></div>
        <div class="preview-row"><span>骑手应得</span><strong>{{ preview.rider }}</strong></div>
        <div class="preview-row"><span>平台收入</span><strong>{{ preview.platform }}</strong></div>
        <div class="preview-total"><span>合计用户实付</span><strong>{{ preview.total }}</strong></div>
      </section>
      <section class="panel-card history-panel">
        <div class="section-heading"><div><h3>配置变更记录</h3></div><el-icon><Clock /></el-icon></div>
        <el-empty v-if="!history.length" description="暂无变更记录" :image-size="70" />
        <div v-for="item in history" :key="item.version" class="history-item"><div><strong>{{ item.version }}</strong><el-tag size="small" type="info" effect="light">历史版本</el-tag></div><p>{{ item.operator }} · {{ new Date(item.effectiveAt).toLocaleString('zh-CN') }}</p><small>配送费 ¥{{ (item.feeCents / 100).toFixed(2) }} · 餐品抽成 {{ item.foodRate }}% · 配送抽成 {{ item.deliveryRate }}%</small><p class="history-peaks">高峰 {{ item.peakPeriods?.map(period => `${period.start}–${period.end}`).join('、') || '未设置' }}</p></div>
      </section>
    </aside>
  </div>
</template>

<style scoped>
.operations-config { grid-template-columns: minmax(0, 1fr) minmax(280px, 340px); gap: 20px; }
.fee-fields { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.fee-fields :deep(.el-input-group__prepend), .fee-fields :deep(.el-input-group__append) { padding: 0 10px; }
.peak-section { grid-column: 1 / -1; min-width: 0; margin: 4px 0 8px; border: 1px solid var(--line); border-radius: 10px; }
.peak-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px 16px; padding: 10px 14px; border-bottom: 1px solid var(--line); border-radius: 9px 9px 0 0; background: #f6f8fb; }
.peak-heading h3 { margin: 0; font-size: 14px; color: var(--text); }
.peak-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
.peak-controls .peak-interval { align-items: center; margin: 0; }
.peak-interval :deep(.el-form-item__label) { padding-right: 8px; }
.peak-interval :deep(.el-input-number) { width: 122px; }
.peak-controls :deep(.el-icon) { margin-right: 5px; }
.peak-period-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 14px; }
.peak-period-row + .peak-period-row { border-top: 1px solid var(--line); }
.peak-period-row .time-range { width: min(100%, 360px); min-width: 0; }
.peak-remove { flex-shrink: 0; min-width: 44px; color: var(--muted); }
.peak-remove:not(.is-disabled):is(:hover, :focus-visible) { color: var(--el-color-danger-dark-2); background: var(--el-color-danger-light-9); }
.peak-empty { margin: 0; padding: 14px; color: var(--muted); font-size: 13px; }
.schedule-error { margin: 0; padding: 0 14px 10px; color: var(--el-color-danger); font-size: 12px; }
.history-peaks { line-height: 1.7; overflow-wrap: anywhere; }
.config-actions { display: flex; justify-content: flex-end; gap: 12px; }
@media (max-width: 1200px) {
  .operations-config { grid-template-columns: minmax(0, 1fr); }
  .operations-config .config-aside { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
}
@media (max-width: 760px) {
  .fee-fields { grid-template-columns: minmax(0, 1fr); gap: 0; }
  .peak-heading, .peak-period-row { padding-inline: 10px; }
  .peak-period-row, .peak-period-row .time-range { gap: 6px; }
  .peak-remove { padding-inline: 4px; min-height: 44px; }
  .operations-config .config-aside { grid-template-columns: minmax(0, 1fr); }
  .config-actions { flex-wrap: wrap; }
}
@media (max-width: 380px) {
  .peak-period-row { flex-wrap: wrap; justify-content: flex-end; }
  .peak-period-row .time-range { flex-basis: 100%; }
}
</style>
