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
  serviceStart: '10:30', serviceEnd: '20:30', peakStart: '11:30', peakEnd: '13:30', acceptTimeout: 30, reminderInterval: 15,
  effectiveAt: '', operator: '',
})
const preview = computed(() => ({
  merchant: money(2000 - Math.round(2000 * form.foodRate / 100)),
  rider: money(400 - Math.round(400 * form.deliveryRate / 100)),
  platform: money(Math.round(2000 * form.foodRate / 100) + Math.round(400 * form.deliveryRate / 100)),
}))

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
  Object.assign(form, { ...config, fee: (config.feeCents / 100).toFixed(2) })
}
function reset() { void load(); ElMessage.info('已恢复最近保存的配置，本次未保存修改已取消。') }
async function save() {
  try {
    const feeCents = toCents(form.fee)
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
  <div class="config-layout" v-loading="loading">
    <section class="panel-card config-form-panel">
      <div class="section-heading"><h2>配送规则</h2></div>
      <el-form label-position="top" class="config-grid" :disabled="!auth.can('config.edit')">
        <el-form-item label="每笔商户拆单固定配送费"><el-input v-model="form.fee" inputmode="decimal"><template #prepend>¥</template><template #append>元 / 单</template></el-input></el-form-item>
        <el-form-item label="餐品金额平台抽成"><el-input-number v-model="form.foodRate" :min="0" :max="100" :precision="2" controls-position="right"><template #suffix>%</template></el-input-number></el-form-item>
        <el-form-item label="配送费平台抽成"><el-input-number v-model="form.deliveryRate" :min="0" :max="100" :precision="2" controls-position="right"><template #suffix>%</template></el-input-number></el-form-item>
        <div class="config-subhead">配送时段与发车批次</div>
        <el-form-item label="配送服务时段"><div class="time-range"><el-time-select v-model="form.serviceStart" start="00:00" step="00:15" end="23:45" /><span>至</span><el-time-select v-model="form.serviceEnd" start="00:00" step="00:15" end="23:45" /></div></el-form-item>
        <el-form-item label="常规批次间隔"><el-input-number v-model="form.regularBatch" :min="1" :max="1440" controls-position="right"><template #suffix>分钟</template></el-input-number></el-form-item>
        <el-form-item label="高峰时段"><div class="time-range"><el-time-select v-model="form.peakStart" start="00:00" step="00:15" end="23:45" /><span>至</span><el-time-select v-model="form.peakEnd" start="00:00" step="00:15" end="23:45" /></div></el-form-item>
        <el-form-item label="高峰批次间隔"><el-input-number v-model="form.peakBatch" :min="1" :max="1440" controls-position="right"><template #suffix>分钟</template></el-input-number></el-form-item>
        <div class="config-subhead">商户接单与提醒</div>
        <el-form-item label="支付后接单超时"><el-input-number v-model="form.acceptTimeout" :min="1" :max="1440" controls-position="right"><template #suffix>分钟</template></el-input-number></el-form-item>
        <el-form-item label="管理员提醒间隔"><el-input-number v-model="form.reminderInterval" :min="1" :max="1440" controls-position="right"><template #suffix>分钟</template></el-input-number></el-form-item>
      </el-form>
      <div class="config-actions"><el-button @click="reset">取消修改</el-button><el-button v-if="auth.can('config.edit')" type="primary" :loading="saving" @click="save">保存并生成新版本</el-button></div>
    </section>
    <aside class="config-aside">
      <section class="panel-card rule-preview">
        <h3>金额分配预览</h3><p>以餐品 ¥20.00、配送费 ¥4.00 为例</p>
        <div class="preview-row"><span>商户应得</span><strong>{{ preview.merchant }}</strong></div>
        <div class="preview-row"><span>骑手应得</span><strong>{{ preview.rider }}</strong></div>
        <div class="preview-row"><span>平台收入</span><strong>{{ preview.platform }}</strong></div>
        <div class="preview-total"><span>合计用户实付</span><strong>¥24.00</strong></div>
      </section>
      <section class="panel-card history-panel">
        <div class="section-heading"><div><h3>配置变更记录</h3></div><el-icon><Clock /></el-icon></div>
        <el-empty v-if="!history.length" description="暂无变更记录" :image-size="70" />
        <div v-for="item in history" :key="item.version" class="history-item"><div><strong>{{ item.version }}</strong><el-tag size="small" type="info" effect="light">历史版本</el-tag></div><p>{{ item.operator }} · {{ new Date(item.effectiveAt).toLocaleString('zh-CN') }}</p><small>配送费 ¥{{ (item.feeCents / 100).toFixed(2) }} · 餐品抽成 {{ item.foodRate }}% · 配送抽成 {{ item.deliveryRate }}%</small></div>
      </section>
    </aside>
  </div>
</template>
