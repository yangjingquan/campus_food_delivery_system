<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { ECharts } from 'echarts/core'
import { ElMessage } from 'element-plus'
import { adminApi } from '../api/admin'
import type { OverviewData } from '../domain/types'
import { money } from '../domain/rules'

const router = useRouter()
const chartHost = ref<HTMLDivElement>()
const loading = ref(true)
const data = ref<OverviewData>({ orderCount: 0, paidCents: 0, deliveringCount: 0, interveneCount: 0, todoMerchants: 0, todoRiders: 0, failedRefunds: 0, todoWithdrawals: 0, trend: [] })
let chart: ECharts | undefined
let chartLibrary: Promise<typeof import('echarts/core')> | undefined
const todoTotal = computed(() => data.value.todoMerchants + data.value.todoRiders + data.value.interveneCount + data.value.failedRefunds + data.value.todoWithdrawals)

async function load() {
  loading.value = true
  try { data.value = await adminApi.overview() as OverviewData }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '概览数据加载失败。') }
  finally { loading.value = false; await nextTick(); await paintChart() }
}
async function paintChart() {
  if (!chartHost.value) return
  chartLibrary ??= Promise.all([
    import('echarts/core'), import('echarts/charts'), import('echarts/components'), import('echarts/renderers'),
  ]).then(([echarts, charts, components, renderers]) => {
    echarts.use([charts.LineChart, components.GridComponent, components.TooltipComponent, renderers.CanvasRenderer])
    return echarts
  })
  const echarts = await chartLibrary
  chart?.dispose()
  chart = echarts.init(chartHost.value)
  chart.setOption({
    animation: false,
    grid: { left: 42, right: 18, top: 22, bottom: 30 },
    tooltip: { trigger: 'axis', backgroundColor: '#16263b', borderWidth: 0, textStyle: { color: '#fff' } },
    xAxis: { type: 'category', boundaryGap: false, data: data.value.trend.map((item) => item.label), axisLine: { lineStyle: { color: '#e6ebf2' } }, axisTick: { show: false }, axisLabel: { color: '#8794a7' } },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#edf1f6' } }, axisLabel: { color: '#9aa6b6' }, axisLine: { show: false }, axisTick: { show: false } },
    series: [{ type: 'line', smooth: 0.25, data: data.value.trend.map((item) => item.value), symbol: 'circle', symbolSize: 7, itemStyle: { color: '#4f83e4', borderColor: '#fff', borderWidth: 2 }, lineStyle: { color: '#4f83e4', width: 3 }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(79,131,228,.20)' }, { offset: 1, color: 'rgba(79,131,228,0)' }]) } }],
  })
}
function open(path: string) { void router.push(path) }
onMounted(() => { void load(); window.addEventListener('resize', resizeChart) })
function resizeChart() { chart?.resize() }
onBeforeUnmount(() => { window.removeEventListener('resize', resizeChart); chart?.dispose() })
</script>

<template>
  <div class="page-heading dashboard-heading"><h1>运营概览</h1></div>
  <div v-loading="loading" class="dashboard-body">
    <div class="metric-grid">
      <button class="metric-card" type="button" @click="open('/orders')"><div class="metric-top"><span>订单总数</span><i class="metric-icon blue"><el-icon><Tickets /></el-icon></i></div><strong>{{ data.orderCount }}</strong></button>
      <button class="metric-card" type="button" @click="open('/finance')"><div class="metric-top"><span>支付总额</span><i class="metric-icon green"><el-icon><Money /></el-icon></i></div><strong>{{ money(data.paidCents) }}</strong></button>
      <button class="metric-card" type="button" @click="open('/orders')"><div class="metric-top"><span>配送中与待领取</span><i class="metric-icon violet"><el-icon><Bicycle /></el-icon></i></div><strong>{{ data.deliveringCount }}</strong></button>
      <button class="metric-card attention" type="button" @click="open('/orders')"><div class="metric-top"><span>待管理员介入</span><i class="metric-icon amber"><el-icon><Clock /></el-icon></i></div><strong>{{ data.interveneCount }}</strong></button>
    </div>

    <div class="dashboard-grid">
      <section class="panel-card chart-panel">
        <div class="panel-title-row"><div><h2>订单趋势</h2><span>近 7 日</span></div><el-tag type="primary" effect="light" round><span class="chart-dot"></span>每日订单</el-tag></div>
        <div ref="chartHost" class="trend-chart" aria-label="最近七日订单趋势图"></div>
      </section>
      <section class="panel-card todo-panel">
        <div class="panel-title-row"><div><h2>待办事项</h2><span>{{ todoTotal }} 项需要跟进</span></div><el-button link type="primary" @click="open('/orders')">进入处理<el-icon><ArrowRight /></el-icon></el-button></div>
        <div class="todo-grid">
          <button class="todo-cell" type="button" @click="open('/merchants')"><span>商户入驻</span><strong>{{ data.todoMerchants }}</strong></button>
          <button class="todo-cell" type="button" @click="open('/riders')"><span>骑手入驻</span><strong>{{ data.todoRiders }}</strong></button>
          <button class="todo-cell highlighted" type="button" @click="open('/orders')"><span>异常介入</span><strong>{{ data.interveneCount }}</strong></button>
          <button class="todo-cell" type="button" @click="open('/withdrawals')"><span>提现审核</span><strong>{{ data.todoWithdrawals }}</strong></button>
        </div>
        <button class="failed-refunds-link" type="button" @click="open('/aftersales')"><span>查看退款失败处理</span><strong>{{ data.failedRefunds }} 项 <el-icon><ArrowRight /></el-icon></strong></button>
      </section>
    </div>


  </div>
</template>
