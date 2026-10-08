<template>
  <section class="pool-view">
    <div class="pool-toolbar admin-card">
      <div class="pool-toolbar-filters">
        <span class="pool-toolbar-label">监控平台</span>
        <el-select v-model="selectedPlatformId" placeholder="选择 Sub2API 平台" style="width: 260px" @change="reload">
          <el-option v-for="platform in sub2Platforms" :key="platform.id" :label="platform.name" :value="platform.id" />
        </el-select>

        <el-radio-group v-model="range" @change="reload">
          <el-radio-button value="1d">近 1 天</el-radio-button>
          <el-radio-button value="7d">近 7 天</el-radio-button>
          <el-radio-button value="30d">近 30 天</el-radio-button>
          <el-radio-button value="90d">近 90 天</el-radio-button>
        </el-radio-group>
      </div>

      <div class="pool-toolbar-actions">
        <el-button :loading="loading" @click="reload">刷新</el-button>
        <el-button v-if="canWrite" type="primary" :loading="syncing" @click="triggerSync">
          立即采集
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="!sub2Platforms.length"
      type="info"
      show-icon
      :closable="false"
      title="暂无 Sub2API 平台"
      description="号池监控依赖 Sub2API 平台的管理员密钥，请先在「平台管理」新建 Sub2API 平台并填写管理员密钥。"
    />
    <el-alert
      v-else-if="selectedPlatform && !selectedPlatform.adminKeyConfigured"
      type="warning"
      show-icon
      :closable="false"
      title="该平台尚未配置 Sub2API 管理员密钥"
      description="请到「平台管理」编辑该平台并填写管理员密钥，否则无法同步号池账号与用量明细。"
    />

    <template v-if="sub2Platforms.length">
      <div class="pool-metrics">
        <MetricCard label="号池账号" :value="String(accounts.length)" hint="当前平台下的号池账号数" />
        <MetricCard
          label="平均缓存命中率"
          :value="formatPercent(averageHitRate)"
          hint="缓存读取 / (输入 + 缓存读取 + 缓存写入)"
          :tone="hitRateTone(averageHitRate)"
        />
        <MetricCard
          label="平均首 Token"
          :value="formatMs(averageFirstToken)"
          :hint="`按样本加权 · 合计 ${formatNumber(totalFirstTokenSamples)} 个样本`"
        />
        <MetricCard label="P95 首 Token" :value="formatMs(maxP95FirstToken)" hint="取各账号 P95 最大值" :tone="maxP95FirstToken && maxP95FirstToken > 30000 ? 'warning' : 'neutral'" />
        <MetricCard label="平均耗时" :value="formatMs(averageDuration)" hint="请求平均总耗时" />
        <MetricCard label="请求总数" :value="formatNumber(totalRequests)" hint="所选时间窗内" />
        <MetricCard label="实际成本" :value="formatCost(totalActualCost)" hint="所选时间窗内" />
      </div>

      <el-card shadow="never" class="admin-card pool-table-card">
        <el-table
          v-loading="loading"
          :data="accounts"
          row-key="externalAccountId"
          size="small"
          class="pool-table"
          @row-click="openDetail"
        >
          <el-table-column label="号池账号" min-width="200" fixed>
            <template #default="{ row }">
              <div class="pool-account-cell">
                <strong>{{ row.name || ('账号 ' + row.externalAccountId) }}</strong>
                <small>{{ row.platform || '-' }} · ID {{ row.externalAccountId }}</small>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="健康状态" width="130">
            <template #default="{ row }">
              <el-tag :type="healthTag(asAccount(row)).type" size="small" effect="light">{{ healthTag(asAccount(row)).label }}</el-tag>
            </template>
          </el-table-column>

          <el-table-column label="绑定密钥" min-width="180">
            <template #default="{ row }">
              <span v-if="row.boundKeyId" class="pool-key-bound">{{ row.boundKeyName || ('#' + row.boundKeyId) }}</span>
              <el-tag v-else type="warning" size="small" effect="plain">未绑定</el-tag>
              <small v-if="row.boundKeyMasked" class="pool-key-masked">{{ row.boundKeyMasked }}</small>
            </template>
          </el-table-column>

          <el-table-column label="缓存命中率" width="150" sortable :sort-by="'cacheHitRate'">
            <template #default="{ row }">
              <div class="pool-rate-cell">
                <strong :class="hitRateClass(row.cacheHitRate)">{{ formatPercent(row.cacheHitRate) }}</strong>
                <small>{{ formatNumber(row.cacheReadTokens) }} / {{ formatNumber(row.inputTokens + row.cacheReadTokens + row.cacheCreationTokens) }}</small>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="首 Token（平均 / P95）" width="190">
            <template #default="{ row }">
              <div class="pool-sample-cell">
                <span>{{ formatMs(row.avgFirstTokenMs) }} / {{ formatMs(row.p95FirstTokenMs) }}</span>
                <el-tag :type="sampleTag(row.firstTokenSamples).type" size="small" effect="plain">
                  {{ sampleTag(row.firstTokenSamples).label }}
                </el-tag>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="平均耗时" width="120">
            <template #default="{ row }">{{ formatMs(row.avgDurationMs) }}</template>
          </el-table-column>

          <el-table-column label="请求数" width="100" sortable :sort-by="'requests'">
            <template #default="{ row }">{{ formatNumber(row.requests) }}</template>
          </el-table-column>

          <el-table-column label="Token 结构（输入/输出/缓存读）" width="230">
            <template #default="{ row }">
              <div class="pool-token-cell">
                <span>{{ formatNumber(row.inputTokens) }} / {{ formatNumber(row.outputTokens) }} / {{ formatNumber(row.cacheReadTokens) }}</span>
                <div class="pool-token-bar">
                  <span
                    v-for="part in tokenParts(asAccount(row))"
                    :key="part.label"
                    class="pool-token-bar-seg"
                    :style="{ width: part.width, background: part.color }"
                    :title="`${part.label}: ${formatNumber(part.value)}`"
                  />
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="实际成本" width="120" sortable :sort-by="'totalActualCost'">
            <template #default="{ row }">{{ formatCost(row.totalActualCost) }}</template>
          </el-table-column>

          <el-table-column label="最近使用" width="150">
            <template #default="{ row }">{{ formatDateTime(row.lastUsedAt) }}</template>
          </el-table-column>

          <el-table-column label="采样水位" width="150">
            <template #default="{ row }">{{ formatDateTime(row.lastSampleAt) }}</template>
          </el-table-column>

          <el-table-column label="错误" min-width="200">
            <template #default="{ row }">
              <span v-if="row.lastSyncError" class="pool-error-text" :title="row.lastSyncError">{{ row.lastSyncError }}</span>
              <span v-else-if="row.errorMessage" class="pool-error-text" :title="row.errorMessage">{{ row.errorMessage }}</span>
              <span v-else class="pool-muted">—</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="openDetail(asAccount(row))">详情</el-button>
              <el-button v-if="canWrite" link type="primary" @click.stop="openBind(asAccount(row))">绑定</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && !accounts.length" description="暂无号池账号，请先执行一次「立即采集」" :image-size="80" />
      </el-card>
    </template>

    <!-- 号池账号详情抽屉 -->
    <el-drawer v-model="detailVisible" :title="detailTitle" size="70%" destroy-on-close>
      <div v-if="activeAccount" v-loading="detailLoading" class="pool-detail">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="号池账号">{{ activeAccount.name || activeAccount.externalAccountId }}</el-descriptions-item>
          <el-descriptions-item label="健康状态">
            <el-tag :type="healthTag(activeAccount).type" size="small" effect="light">{{ healthTag(activeAccount).label }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="绑定密钥">
            <span v-if="activeAccount.boundKeyId">{{ activeAccount.boundKeyName || ('#' + activeAccount.boundKeyId) }}</span>
            <el-tag v-else type="warning" size="small" effect="plain">未绑定</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="并发 / 优先级">
            {{ activeAccount.concurrency ?? '—' }} / {{ activeAccount.priority ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="倍率">{{ activeAccount.rateMultiplier ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="最近使用">{{ formatDateTime(activeAccount.lastUsedAt) }}</el-descriptions-item>
          <el-descriptions-item label="限流恢复">{{ formatDateTime(activeAccount.rateLimitResetAt) }}</el-descriptions-item>
          <el-descriptions-item label="临时不可调度">{{ formatDateTime(activeAccount.tempUnschedulableUntil) }}</el-descriptions-item>
          <el-descriptions-item label="采样水位">{{ formatDateTime(activeAccount.lastSampleAt) }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="activeAccount.errorMessage || activeAccount.lastSyncError"
          class="pool-detail-alert"
          type="error"
          show-icon
          :closable="false"
          title="采集或上游返回了错误"
          :description="activeAccount.lastSyncError || activeAccount.errorMessage || ''"
        />

        <el-alert
          v-if="activeAccount.tempUnschedulableReason"
          class="pool-detail-alert"
          type="warning"
          show-icon
          :closable="false"
          title="临时不可调度原因"
          :description="activeAccount.tempUnschedulableReason"
        />

        <div class="pool-detail-grid">
          <MetricCard label="缓存命中率" :value="formatPercent(activeAccount.cacheHitRate)" :tone="hitRateTone(activeAccount.cacheHitRate)" />
          <MetricCard label="首 Token 平均" :value="formatMs(activeAccount.avgFirstTokenMs)" :hint="`样本 ${activeAccount.firstTokenSamples}`" />
          <MetricCard label="首 Token P95" :value="formatMs(activeAccount.p95FirstTokenMs)" />
          <MetricCard label="平均耗时" :value="formatMs(activeAccount.avgDurationMs)" />
          <MetricCard label="请求数" :value="formatNumber(activeAccount.requests)" />
          <MetricCard label="实际成本" :value="formatCost(activeAccount.totalActualCost)" />
        </div>

        <el-card shadow="never" class="pool-chart-card">
          <template #header>
            <div class="pool-chart-header">
              <span>缓存命中率趋势</span>
              <el-radio-group v-model="granularity" size="small" @change="loadDetail(activeAccount)">
                <el-radio-button value="hour">按小时</el-radio-button>
                <el-radio-button value="day">按天</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <EChart :option="hitRateOption" height="260px" />
        </el-card>

        <el-card shadow="never" class="pool-chart-card">
          <template #header><span>首 Token 耗时趋势（平均 / P95）</span></template>
          <EChart :option="latencyOption" height="260px" />
        </el-card>

        <el-card shadow="never" class="pool-chart-card">
          <template #header><span>请求量与成本趋势</span></template>
          <EChart :option="volumeOption" height="260px" />
        </el-card>

        <el-card shadow="never" class="pool-chart-card">
          <template #header><span>按模型明细</span></template>
          <el-table :data="models" size="small">
            <el-table-column label="模型" prop="model" min-width="160" />
            <el-table-column label="请求数" width="90">
              <template #default="{ row }">{{ formatNumber(row.requests) }}</template>
            </el-table-column>
            <el-table-column label="缓存命中率" width="120">
              <template #default="{ row }">{{ formatPercent(row.cacheHitRate) }}</template>
            </el-table-column>
            <el-table-column label="首 Token 平均" width="130">
              <template #default="{ row }">{{ formatMs(row.avgFirstTokenMs) }}</template>
            </el-table-column>
            <el-table-column label="首 Token P95" width="130">
              <template #default="{ row }">{{ formatMs(row.p95FirstTokenMs) }}</template>
            </el-table-column>
            <el-table-column label="平均耗时" width="120">
              <template #default="{ row }">{{ formatMs(row.avgDurationMs) }}</template>
            </el-table-column>
            <el-table-column label="实际成本" width="120">
              <template #default="{ row }">{{ formatCost(row.totalActualCost) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!models.length" description="该时间窗内暂无按模型明细" :image-size="60" />
        </el-card>
      </div>
    </el-drawer>

    <!-- 手动绑定密钥 -->
    <el-dialog v-model="bindVisible" title="绑定本地密钥" width="460px">
      <p class="admin-form-hint">
        号池账号「{{ activeAccount?.name || activeAccount?.externalAccountId }}」对应本项目中的哪个上游 Key？
        绑定后即可把该号池账号的调用指标关联回本地上游账号。
      </p>
      <el-select v-model="bindKeyId" placeholder="选择本地密钥（留空表示解除绑定）" clearable filterable style="width: 100%">
        <el-option
          v-for="key in bindableKeys"
          :key="key.id"
          :label="`${key.keyName || key.externalKeyId || key.id} · ${key.keyMasked || ''}`"
          :value="key.id"
        />
      </el-select>
      <template #footer>
        <el-button @click="bindVisible = false">取消</el-button>
        <el-button type="primary" :loading="binding" @click="submitBind">保存绑定</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { EChartsCoreOption } from 'echarts/core'
import EChart from '../components/EChart.vue'
import MetricCard from '../components/MetricCard.vue'
import { bindPoolAccount, listPoolAccounts, listPoolModels, listPoolSeries, syncPoolPlatform } from '../api/pool'
import { listApiKeyRecords } from '../api/accounts'
import type { ApiKey, Platform, PoolAccount, PoolGranularity, PoolModelMetrics, PoolRange, PoolSeriesPoint } from '../types'

const props = defineProps<{ platforms: Platform[]; canWrite?: boolean }>()

const sub2Platforms = computed(() => props.platforms.filter(platform => platform.type === 'sub2api'))

const selectedPlatformId = ref<number | null>(null)
const range = ref<PoolRange>('7d')
const granularity = ref<PoolGranularity>('hour')

const accounts = ref<PoolAccount[]>([])
const series = ref<PoolSeriesPoint[]>([])
const models = ref<PoolModelMetrics[]>([])
const bindableKeys = ref<ApiKey[]>([])

const loading = ref(false)
const syncing = ref(false)
const detailLoading = ref(false)
const binding = ref(false)

const detailVisible = ref(false)
const bindVisible = ref(false)
const activeAccount = ref<PoolAccount | null>(null)
const bindKeyId = ref<number | null>(null)

const selectedPlatform = computed(() => sub2Platforms.value.find(item => item.id === selectedPlatformId.value) ?? null)

const detailTitle = computed(() => activeAccount.value
  ? `${activeAccount.value.name || activeAccount.value.externalAccountId} · 号池详情`
  : '号池详情')

// ---- 汇总指标 ----
const totalRequests = computed(() => accounts.value.reduce((sum, item) => sum + item.requests, 0))
const totalActualCost = computed(() => accounts.value.reduce((sum, item) => sum + item.totalActualCost, 0))
const totalFirstTokenSamples = computed(() => accounts.value.reduce((sum, item) => sum + item.firstTokenSamples, 0))

const averageFirstToken = computed(() => {
  const samples = accounts.value.filter(item => item.avgFirstTokenMs != null && item.firstTokenSamples > 0)
  const sampleCount = samples.reduce((sum, item) => sum + item.firstTokenSamples, 0)
  if (!sampleCount) return null
  return samples.reduce((sum, item) => sum + (item.avgFirstTokenMs as number) * item.firstTokenSamples, 0) / sampleCount
})

const averageDuration = computed(() => {
  const rows = accounts.value.filter(item => item.avgDurationMs != null && item.requests > 0)
  const weight = rows.reduce((sum, item) => sum + item.requests, 0)
  if (!weight) return null
  return rows.reduce((sum, item) => sum + (item.avgDurationMs as number) * item.requests, 0) / weight
})

const maxP95FirstToken = computed(() => {
  const values = accounts.value.map(item => item.p95FirstTokenMs).filter((value): value is number => value != null)
  return values.length ? Math.max(...values) : null
})

const averageHitRate = computed(() => {
  const input = accounts.value.reduce((sum, item) => sum + item.inputTokens, 0)
  const read = accounts.value.reduce((sum, item) => sum + item.cacheReadTokens, 0)
  const creation = accounts.value.reduce((sum, item) => sum + item.cacheCreationTokens, 0)
  const denominator = input + read + creation
  return denominator > 0 ? read / denominator : null
})

// ---- 图表 ----
const buckets = computed(() => series.value.map(point => formatBucket(point.bucket)))

const hitRateOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'axis', valueFormatter: (value: number) => formatPercent(value) },
  grid: { left: 56, right: 20, top: 20, bottom: 40 },
  xAxis: { type: 'category', data: buckets.value },
  yAxis: { type: 'value', min: 0, max: 1, axisLabel: { formatter: percentAxis } },
  series: [{
    name: '缓存命中率',
    type: 'line',
    smooth: true,
    showSymbol: false,
    connectNulls: true,
    areaStyle: { opacity: 0.12 },
    data: series.value.map(point => point.cacheHitRate),
  }],
}))

const latencyOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'axis', valueFormatter: (value: number) => formatMs(value) },
  legend: { data: ['平均首 Token', 'P95 首 Token', '平均耗时'] },
  grid: { left: 64, right: 20, top: 40, bottom: 40 },
  xAxis: { type: 'category', data: buckets.value },
  yAxis: { type: 'value', axisLabel: { formatter: (value: number) => `${Math.round(value)}ms` } },
  series: [
    { name: '平均首 Token', type: 'line', smooth: true, showSymbol: false, connectNulls: true, data: series.value.map(point => point.avgFirstTokenMs) },
    { name: 'P95 首 Token', type: 'line', smooth: true, showSymbol: false, connectNulls: true, data: series.value.map(point => point.p95FirstTokenMs) },
    { name: '平均耗时', type: 'line', smooth: true, showSymbol: false, connectNulls: true, data: series.value.map(point => point.avgDurationMs) },
  ],
}))

const volumeOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['请求数', '实际成本'] },
  grid: { left: 64, right: 64, top: 40, bottom: 40 },
  xAxis: { type: 'category', data: buckets.value },
  yAxis: [
    { type: 'value', name: '请求数', axisLabel: { formatter: (value: number) => formatNumber(value) } },
    { type: 'value', name: '成本', axisLabel: { formatter: (value: number) => `$${Number(value).toFixed(2)}` } },
  ],
  series: [
    { name: '请求数', type: 'bar', data: series.value.map(point => point.requests) },
    { name: '实际成本', type: 'line', smooth: true, showSymbol: false, yAxisIndex: 1, data: series.value.map(point => point.totalActualCost) },
  ],
}))

// ---- 数据加载 ----
async function reload() {
  if (!selectedPlatformId.value) {
    accounts.value = []
    return
  }
  loading.value = true
  try {
    accounts.value = await listPoolAccounts(selectedPlatformId.value, range.value)
  } catch (error) {
    accounts.value = []
    ElMessage.error(error instanceof Error ? error.message : '号池数据加载失败')
  } finally {
    loading.value = false
  }
}

async function triggerSync() {
  if (!selectedPlatformId.value) return
  syncing.value = true
  try {
    await syncPoolPlatform(selectedPlatformId.value)
    ElMessage.success('已触发号池采集，稍后自动刷新')
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '号池采集失败')
  } finally {
    syncing.value = false
  }
}

async function openDetail(row: PoolAccount) {
  activeAccount.value = row
  detailVisible.value = true
  await loadDetail(row)
}

async function loadDetail(row: PoolAccount) {
  if (!selectedPlatformId.value) return
  detailLoading.value = true
  try {
    const [seriesRows, modelRows] = await Promise.all([
      listPoolSeries(selectedPlatformId.value, row.externalAccountId, range.value, granularity.value),
      listPoolModels(selectedPlatformId.value, row.externalAccountId, range.value),
    ])
    series.value = seriesRows
    models.value = modelRows
  } catch (error) {
    series.value = []
    models.value = []
    ElMessage.error(error instanceof Error ? error.message : '号池明细加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function openBind(row: PoolAccount) {
  activeAccount.value = row
  bindKeyId.value = row.boundKeyId
  if (!bindableKeys.value.length && selectedPlatform.value) {
    try {
      bindableKeys.value = await listApiKeyRecords(selectedPlatform.value)
    } catch {
      bindableKeys.value = []
    }
  }
  bindVisible.value = true
}

async function submitBind() {
  if (!selectedPlatformId.value || !activeAccount.value) return
  binding.value = true
  try {
    await bindPoolAccount(selectedPlatformId.value, activeAccount.value.externalAccountId, bindKeyId.value)
    ElMessage.success('绑定已保存')
    bindVisible.value = false
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '绑定失败')
  } finally {
    binding.value = false
  }
}

// ---- 展示辅助 ----
/** el-table 的插槽行类型为 DefaultRow，这里统一收敛为 PoolAccount。 */
function asAccount(row: unknown): PoolAccount {
  return row as PoolAccount
}

function healthTag(row: PoolAccount): { label: string; type: 'success' | 'warning' | 'danger' | 'info' } {
  if (row.status && row.status !== 'active') return { label: '已停用', type: 'info' }
  if (row.rateLimitResetAt && new Date(row.rateLimitResetAt).getTime() > Date.now()) return { label: '限流中', type: 'danger' }
  if (row.tempUnschedulableUntil && new Date(row.tempUnschedulableUntil).getTime() > Date.now()) return { label: '临时不可调度', type: 'warning' }
  if (row.schedulable === false) return { label: '不可调度', type: 'warning' }
  if (row.errorMessage || row.lastSyncError) return { label: '异常', type: 'danger' }
  return { label: '正常', type: 'success' }
}

function sampleTag(samples: number): { label: string; type: 'success' | 'warning' | 'danger' } {
  if (samples >= 50) return { label: `样本 ${samples}`, type: 'success' }
  if (samples >= 20) return { label: '样本偏少', type: 'warning' }
  return { label: '样本不足', type: 'danger' }
}

function hitRateTone(value: number | null): 'positive' | 'warning' | 'danger' | 'neutral' {
  if (value == null) return 'neutral'
  if (value >= 0.6) return 'positive'
  if (value >= 0.3) return 'warning'
  return 'danger'
}

function hitRateClass(value: number | null): string {
  if (value == null) return 'pool-muted'
  if (value >= 0.6) return 'pool-good'
  if (value >= 0.3) return 'pool-warn'
  return 'pool-bad'
}

function tokenParts(row: PoolAccount): Array<{ label: string; value: number; width: string; color: string }> {
  const input = row.inputTokens
  const output = row.outputTokens
  const read = row.cacheReadTokens
  const total = input + output + read || 1
  return [
    { label: '输入', value: input, width: `${(input / total) * 100}%`, color: '#409eff' },
    { label: '输出', value: output, width: `${(output / total) * 100}%`, color: '#67c23a' },
    { label: '缓存读', value: read, width: `${(read / total) * 100}%`, color: '#e6a23c' },
  ]
}

function formatPercent(value: number | null | undefined): string {
  return value == null ? '—' : `${(value * 100).toFixed(1)}%`
}

function percentAxis(value: number): string {
  return `${Math.round(value * 100)}%`
}

function formatMs(value: number | null | undefined): string {
  if (value == null) return '—'
  const ms = Math.round(value)
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`
}

function formatNumber(value: number | null | undefined): string {
  if (value == null) return '—'
  return Number(value).toLocaleString('en-US')
}

function formatCost(value: number | null | undefined): string {
  return value == null ? '—' : `$${Number(value).toFixed(4)}`
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function formatBucket(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return granularity.value === 'day'
    ? date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
    : date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit' })
}

watch(sub2Platforms, list => {
  if (!list.length) {
    selectedPlatformId.value = null
    return
  }
  if (!selectedPlatformId.value || !list.some(item => item.id === selectedPlatformId.value)) {
    selectedPlatformId.value = list[0].id
    void reload()
  }
}, { immediate: true })


</script>