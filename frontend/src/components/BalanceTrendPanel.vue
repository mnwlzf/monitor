<template>
  <div class="balance-panel">
    <div class="balance-panel-header">
      <div>
        <strong>{{ account.displayName }}</strong>
        <small>{{ account.platformName }} · {{ account.platformType === 'newapi' ? 'New API' : 'Sub2API' }}</small>
      </div>
      <div class="balance-panel-actions">
        <el-radio-group v-model="range">
          <el-radio-button v-for="item in rangeOptions" :key="item.value" :value="item.value">
            {{ item.label }}
          </el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-row :gutter="16" class="admin-metric-grid">
      <el-col :xs="12" :sm="6">
        <MetricCard label="当前余额" :value="formatMoney(latestBalance)" hint="最新一次采集" tone="positive" />
      </el-col>
      <el-col :xs="12" :sm="6">
        <MetricCard label="区间最高" :value="formatMoney(maxBalance)" :hint="rangeLabel" tone="neutral" />
      </el-col>
      <el-col :xs="12" :sm="6">
        <MetricCard label="区间最低" :value="formatMoney(minBalance)" :hint="rangeLabel" tone="danger" />
      </el-col>
      <el-col :xs="12" :sm="6">
        <MetricCard
          label="区间变化"
          :value="formatDelta(balanceDelta)"
          hint="末值 − 首值"
          :tone="balanceDelta != null && balanceDelta < 0 ? 'danger' : 'positive'"
        />
      </el-col>
    </el-row>

    <el-card shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>余额趋势</h3>
            <p>{{ rangeLabel }} · 单位 USD</p>
          </div>
          <el-tag effect="plain">{{ points.length }} 个数据点</el-tag>
        </div>
      </template>
      <EChart v-if="points.length" :option="balanceOption" height="340px" />
      <el-empty v-else description="该时间维度暂无指标数据" :image-size="90" />
    </el-card>

    <el-card v-if="hasUsageData" shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>已用额度 / 请求数趋势</h3>
            <p>New API 专属指标，额度为上游原始单位</p>
          </div>
        </div>
      </template>
      <EChart :option="usageOption" height="300px" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { EChartsCoreOption } from 'echarts/core'
import EChart from './EChart.vue'
import MetricCard from './MetricCard.vue'
import { listAccountMetricRecords } from '../api/accounts'
import type { Account, AccountMetricPoint, MetricRange } from '../types'

const props = defineProps<{ account: Account }>()

const rangeOptions: Array<{ label: string; value: MetricRange }> = [
  { label: '24 小时', value: '1d' },
  { label: '7 天', value: '7d' },
  { label: '30 天', value: '30d' },
  { label: '90 天', value: '90d' },
]

const range = ref<MetricRange>('7d')
const points = ref<AccountMetricPoint[]>([])
const loading = ref(false)

const rangeLabel = computed(() =>
  rangeOptions.find(item => item.value === range.value)?.label ?? range.value)

function pick(selector: (point: AccountMetricPoint) => number | null): number[] {
  return points.value.map(selector).filter((value): value is number => value != null && Number.isFinite(value))
}

const balanceValues = computed(() => pick(point => point.balance))
const latestBalance = computed(() => balanceValues.value.at(-1) ?? null)
const maxBalance = computed(() => (balanceValues.value.length ? Math.max(...balanceValues.value) : null))
const minBalance = computed(() => (balanceValues.value.length ? Math.min(...balanceValues.value) : null))
const balanceDelta = computed(() => {
  const values = balanceValues.value
  return values.length >= 2 ? values[values.length - 1] - values[0] : null
})

const hasUsageData = computed(() =>
  points.value.some(point => point.usedQuota != null || point.requestCount != null))

const balanceOption = computed<EChartsCoreOption>(() => {
  const frozen = points.value.map(point => point.frozenBalance)
  const hasFrozen = frozen.some(value => value != null)
  return {
    animationDuration: 500,
    color: ['#1f7a69', '#5574a8'],
    legend: {
      show: hasFrozen,
      data: ['余额', '冻结余额'],
      top: 0,
      textStyle: { color: '#7b8b87', fontSize: 11 },
    },
    grid: { left: 60, right: 26, top: 40, bottom: 44 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(24, 51, 47, .94)',
      borderWidth: 0,
      textStyle: { color: '#fff', fontSize: 11 },
    },
    dataZoom: [{ type: 'inside' }],
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: points.value.map(point => formatAxisTime(point.collectedAt)),
      axisLine: { lineStyle: { color: '#dfe9e5' } },
      axisTick: { show: false },
      axisLabel: { color: '#8d9a97', fontSize: 10 },
    },
    yAxis: {
      type: 'value',
      scale: true,
      axisLabel: { color: '#8d9a97', fontSize: 10, formatter: '${value}' },
      splitLine: { lineStyle: { color: '#edf1ef' } },
    },
    series: [
      {
        name: '余额',
        type: 'line',
        smooth: true,
        showSymbol: points.value.length <= 60,
        symbolSize: 6,
        data: points.value.map(point => point.balance),
        lineStyle: { width: 3 },
        areaStyle: { color: 'rgba(31, 122, 105, .10)' },
      },
      ...(hasFrozen
        ? [{
            name: '冻结余额',
            type: 'line' as const,
            smooth: true,
            showSymbol: false,
            data: frozen,
            lineStyle: { width: 2, type: 'dashed' as const },
          }]
        : []),
    ],
  }
})

const usageOption = computed<EChartsCoreOption>(() => ({
  animationDuration: 500,
  color: ['#5574a8', '#d98b3a'],
  legend: {
    data: ['已用额度', '请求数'],
    top: 0,
    textStyle: { color: '#7b8b87', fontSize: 11 },
  },
  grid: { left: 74, right: 74, top: 40, bottom: 44 },
  tooltip: {
    trigger: 'axis',
    backgroundColor: 'rgba(24, 51, 47, .94)',
    borderWidth: 0,
    textStyle: { color: '#fff', fontSize: 11 },
  },
  dataZoom: [{ type: 'inside' }],
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: points.value.map(point => formatAxisTime(point.collectedAt)),
    axisLine: { lineStyle: { color: '#dfe9e5' } },
    axisTick: { show: false },
    axisLabel: { color: '#8d9a97', fontSize: 10 },
  },
  yAxis: [
    {
      type: 'value',
      name: '已用额度',
      scale: true,
      nameTextStyle: { color: '#8d9a97', fontSize: 10 },
      axisLabel: { color: '#8d9a97', fontSize: 10 },
      splitLine: { lineStyle: { color: '#edf1ef' } },
    },
    {
      type: 'value',
      name: '请求数',
      scale: true,
      nameTextStyle: { color: '#8d9a97', fontSize: 10 },
      axisLabel: { color: '#8d9a97', fontSize: 10 },
      splitLine: { show: false },
    },
  ],
  series: [
    {
      name: '已用额度',
      type: 'line',
      smooth: true,
      showSymbol: false,
      yAxisIndex: 0,
      data: points.value.map(point => point.usedQuota),
    },
    {
      name: '请求数',
      type: 'line',
      smooth: true,
      showSymbol: false,
      yAxisIndex: 1,
      connectNulls: true,
      data: points.value.map(point => point.requestCount),
    },
  ],
}))

async function load() {
  loading.value = true
  try {
    points.value = await listAccountMetricRecords(props.account, range.value)
  } catch (error) {
    points.value = []
    ElMessage.error(error instanceof Error ? error.message : '加载余额指标失败')
  } finally {
    loading.value = false
  }
}

function formatAxisTime(value: string) {
  const date = new Date(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  if (range.value === '1d') return `${pad(date.getHours())}:${pad(date.getMinutes())}`
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function formatMoney(value: number | null | undefined) {
  return value == null ? '—' : '$' + Number(value).toFixed(2)
}

function formatDelta(value: number | null) {
  if (value == null) return '—'
  return (value >= 0 ? '+' : '−') + '$' + Math.abs(value).toFixed(2)
}

watch(() => [props.account.id, range.value], load)
onMounted(load)
</script>

<style scoped>
.balance-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.balance-panel-header strong {
  display: block;
  font-size: 15px;
}

.balance-panel-header small {
  color: #7b8b87;
  font-size: 11px;
}

.balance-panel-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
}
</style>