<template>
  <section class="pool-view">
    <div class="pool-toolbar admin-card">
      <div class="pool-toolbar-filters">
        <span class="pool-toolbar-label">监控平台</span>
        <el-select v-model="selectedPlatformId" placeholder="选择 Sub2API 平台" style="width: 260px" @change="reload">
          <el-option v-for="platform in poolSources" :key="platform.id" :label="platform.name" :value="platform.id" />
        </el-select>

        <el-radio-group v-model="range" @change="reload">
          <el-radio-button value="1d">近 1 天</el-radio-button>
          <el-radio-button value="7d">近 7 天</el-radio-button>
          <el-radio-button value="30d">近 30 天</el-radio-button>
          <el-radio-button value="90d">近 90 天</el-radio-button>
        </el-radio-group>

        <span class="pool-toolbar-label">账号平台</span>
        <el-select
          v-model="platformFilter"
          multiple
          clearable
          collapse-tags
          :max-collapse-tags="2"
          placeholder="全部平台"
          style="width: 220px"
        >
          <el-option v-for="name in platformOptions" :key="name" :label="name" :value="name" />
        </el-select>
        <el-button v-if="platformFilter.length" link type="primary" @click="platformFilter = []">清除筛选</el-button>
        <span class="pool-toolbar-count">
          账号 {{ filteredAccounts.length }} / {{ accounts.length }}
        </span>
      </div>

      <div class="pool-toolbar-actions">
        <el-tooltip v-if="ingestStatus" :content="ingestTooltip" placement="bottom-end">
          <el-tag :type="ingestTag.type" size="small" effect="plain">{{ ingestTag.label }}</el-tag>
        </el-tooltip>
        <el-button :loading="loading" @click="reload">刷新</el-button>
        <el-button v-if="canWrite" type="primary" :loading="syncing" @click="triggerSync">
          立即采集
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="!poolSources.length"
      type="info"
      show-icon
      :closable="false"
      title="暂无 Sub2API 平台"
      description="请到「平台管理」把你自建的 Sub2API 平台标记为「号池监控源」并填写管理员密钥；其余上游平台不需要配置。"
    />
    <el-alert
      v-else-if="selectedPlatform && !selectedPlatform.adminKeyConfigured"
      type="warning"
      show-icon
      :closable="false"
      title="该平台尚未配置 Sub2API 管理员密钥"
      description="请到「平台管理」编辑该平台并填写管理员密钥，否则无法同步号池账号与用量明细。"
    />

    <template v-if="poolSources.length">
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
          :data="filteredAccounts"
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
        <el-empty
          v-if="!loading && !filteredAccounts.length"
          :description="accounts.length ? '没有符合筛选条件的账号' : '暂无号池账号，请先执行一次「立即采集」'"
          :image-size="80"
        />
      </el-card>

      <el-card shadow="never" class="admin-card pool-table-card">
        <template #header>
          <div class="pool-chart-header">
            <div>
              <h3>多时间窗缓存率对比</h3>
              <p class="admin-form-hint">行 = 号池账号，列 = 时间窗；角标为请求数，低于 {{ matrixMinimumSample }} 条标注「样本不足」。分钟级窗口依赖「号池明细增量（直连库）」任务，未配置时只会长期为空。</p>
            </div>
            <el-select
              v-model="selectedWindows"
              multiple
              collapse-tags
              :max-collapse-tags="3"
              size="small"
              placeholder="选择时间窗"
              style="width: 300px"
              @change="loadMatrix"
            >
              <el-option
                v-for="option in WINDOW_OPTIONS"
                :key="option.key"
                :label="option.label"
                :value="option.key"
                :disabled="!selectedWindows.includes(option.key) && selectedWindows.length >= MAX_WINDOWS"
              />
            </el-select>
          </div>
        </template>
        <el-table v-loading="matrixLoading" :data="filteredMatrixRows" size="small" border>
          <el-table-column label="号池账号" min-width="220" fixed>
            <template #default="{ row }">
              <div class="pool-account-cell">
                <strong>{{ row.name || ('账号 ' + row.externalAccountId) }}</strong>
                <small>{{ row.platform || '-' }}<template v-if="row.boundKeyName"> · {{ row.boundKeyName }} {{ row.boundKeyMasked }}</template></small>
                <small v-if="!row.boundKeyName" class="pool-muted">未绑定本地密钥</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column
            v-for="window in matrixWindows"
            :key="window.key"
            :label="window.label"
            width="126"
            align="right"
          >
            <template #default="{ row }">
              <div class="pool-matrix-cell" :title="matrixCellTip(cellOf(row, window.key))">
                <strong :class="hitRateClass(cellOf(row, window.key)?.cacheHitRate ?? null)">
                  {{ formatPercent(cellOf(row, window.key)?.cacheHitRate ?? null) }}
                </strong>
                <small :class="{ 'pool-matrix-thin': isThinSample(cellOf(row, window.key)) }">
                  {{ cellOf(row, window.key)?.requests ?? 0 }} 条
                </small>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!matrixLoading && !filteredMatrixRows.length" :description="matrixRows.length ? '没有符合筛选条件的账号' : '暂无数据'" :image-size="60" />
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
              <div class="pool-chart-controls">
                <el-select v-model="detailRange" size="small" style="width: 118px" @change="onDetailRangeChange">
                  <el-option v-for="option in DETAIL_RANGE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
                </el-select>
                <el-radio-group v-model="granularity" size="small" @change="loadDetail(activeAccount)">
                  <el-radio-button value="minute" :disabled="!minuteAvailable">按分钟</el-radio-button>
                  <el-radio-button value="hour">按小时</el-radio-button>
                  <el-radio-button value="day">按天</el-radio-button>
                </el-radio-group>
              </div>
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
import { bindPoolAccount, getPoolIngestStatus, listPoolAccounts, listPoolCacheRates, listPoolModels, listPoolSeries, syncPoolPlatform } from '../api/pool'
import { listApiKeyRecords } from '../api/accounts'
import type { ApiKey, Platform, PoolAccount, PoolCacheRateCell, PoolCacheRateWindow, PoolGranularity, PoolIngestStatus, PoolModelMetrics, PoolRange, PoolSeriesPoint } from '../types'

const props = defineProps<{ platforms: Platform[]; canWrite?: boolean }>()

/**
 * 号池监控源：只有「用户自己搭建并显式标记的 Sub2API」才需要管理员密钥。
 * 其余 Sub2API / New API 只是它的上游，没有管理员只读接口。
 */
const poolSources = computed(() => props.platforms.filter(platform => platform.poolMonitoringEnabled === true))

const selectedPlatformId = ref<number | null>(null)
const range = ref<PoolRange>('7d')
const granularity = ref<PoolGranularity>('minute')

const accounts = ref<PoolAccount[]>([])
const series = ref<PoolSeriesPoint[]>([])
const models = ref<PoolModelMetrics[]>([])
const bindableKeys = ref<ApiKey[]>([])
/** 直连库增量采集状态：分钟级窗口有没有数据，全看这里。 */
const ingestStatus = ref<PoolIngestStatus | null>(null)

/** 多时间窗对比：可选时间窗（与后端 preset 保持一致）。 */
const WINDOW_OPTIONS: Array<{ key: string; label: string }> = [
  { key: '1m', label: '近 1 分钟' },
  { key: '5m', label: '近 5 分钟' },
  { key: '15m', label: '近 15 分钟' },
  { key: '30m', label: '近 30 分钟' },
  { key: '1h', label: '近 1 小时' },
  { key: '6h', label: '近 6 小时' },
  { key: '12h', label: '近 12 小时' },
  { key: '24h', label: '近 24 小时' },
  { key: '7d', label: '近 7 天' },
  { key: '30d', label: '近 30 天' },
  { key: '90d', label: '近 90 天' },
]
/** 与后端 MAX_WINDOWS 保持一致。 */
const MAX_WINDOWS = 6

/** 多时间窗对比的一行（cells 转成 map，模板里取值更直接）。 */
interface MatrixRowView {
  externalAccountId: number
  name: string | null
  platform: string | null
  boundKeyName: string | null
  boundKeyMasked: string | null
  cells: Record<string, PoolCacheRateCell>
}

/** 账号平台筛选（openai / anthropic / grok ...），空数组表示全部。 */
const platformFilter = ref<string[]>([])

const selectedWindows = ref<string[]>(['5m', '15m', '1h', '24h', '7d'])
const matrixWindows = ref<PoolCacheRateWindow[]>([])
const matrixRows = ref<MatrixRowView[]>([])
const matrixLoading = ref(false)
/** 后端给出的「样本不足」阈值，避免前后端各写一份魔法数字。 */
const matrixMinimumSample = ref(20)

/** 详情抽屉自己的时间窗与粒度：分钟级排查和长窗口趋势互不干扰。 */
const detailRange = ref<PoolRange>('6h')
const DETAIL_RANGE_OPTIONS: Array<{ value: PoolRange; label: string }> = [
  { value: '1h', label: '近 1 小时' },
  { value: '6h', label: '近 6 小时' },
  { value: '12h', label: '近 12 小时' },
  { value: '1d', label: '近 24 小时' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
]
/** 分钟粒度只对 ≤24 小时窗口开放（与后端 resolveGranularity 的降级规则一致）。 */
const minuteAvailable = computed(() => ['1h', '6h', '12h', '24h', '1d'].includes(detailRange.value))

const loading = ref(false)
const syncing = ref(false)
const detailLoading = ref(false)
const binding = ref(false)

const detailVisible = ref(false)
const bindVisible = ref(false)
const activeAccount = ref<PoolAccount | null>(null)
const bindKeyId = ref<number | null>(null)

const selectedPlatform = computed(() => poolSources.value.find(item => item.id === selectedPlatformId.value) ?? null)

/** 直连增量状态标签：滞后越小越健康，未配置/未初始化时明确提示分钟级窗口会是空的。 */
const ingestTag = computed<{ label: string; type: 'success' | 'warning' | 'danger' | 'info' }>(() => {
  const status = ingestStatus.value
  if (!status) return { label: '直连增量未知', type: 'info' }
  if (!status.enabled) return { label: '直连增量未启用', type: 'info' }
  if (!status.configured) return { label: '直连增量未配置完整', type: 'warning' }
  if (!status.lastUsageLogId) return { label: '直连增量未初始化', type: 'warning' }
  if (status.lagSeconds == null) return { label: '直连增量已启用', type: 'info' }
  const label = `直连增量 · 滞后 ${formatLag(status.lagSeconds)}`
  if (status.lagSeconds <= 120) return { label, type: 'success' }
  if (status.lagSeconds <= 900) return { label, type: 'warning' }
  return { label, type: 'danger' }
})

const ingestTooltip = computed(() => {
  const status = ingestStatus.value
  if (!status) return '直连库增量采集状态未知'
  return [
    `启用：${status.enabled ? '是' : '否'}`,
    `配置完整：${status.configured ? '是' : '否'}`,
    `密码：${status.passwordConfigured ? '已配置' : '未配置'}`,
    `游标 usage_logs.id：${status.lastUsageLogId || '未初始化'}`,
    `游标推进：${formatDateTime(status.lastRunAt)}`,
    `最新明细：${formatDateTime(status.latestSampleAt)}`,
  ].join(' ｜ ')
})

/** 可选平台：来自当前已加载的号池账号。 */
const platformOptions = computed(() => {
  const names = new Set<string>()
  for (const account of accounts.value) {
    if (account.platform) names.add(account.platform)
  }
  return [...names].sort()
})

/** 账号列表（按平台筛选后）。 */
const filteredAccounts = computed(() => platformFilter.value.length
  ? accounts.value.filter(account => account.platform != null && platformFilter.value.includes(account.platform))
  : accounts.value)

/** 多时间窗矩阵（按平台筛选后，与账号列表保持一致）。 */
const filteredMatrixRows = computed(() => platformFilter.value.length
  ? matrixRows.value.filter(row => row.platform != null && platformFilter.value.includes(row.platform))
  : matrixRows.value)

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

/**
 * 时间类目轴。
 *
 * <p>桶数量多时把标签旋转并交给 ECharts 自动隐藏重叠项，避免挤成一团；
 * 数据点很少时不旋转，保证可读。</p>
 */
function timeCategoryAxis() {
  return {
    type: 'category',
    data: buckets.value,
    boundaryGap: false,
    axisLabel: {
      hideOverlap: true,
      rotate: buckets.value.length > 8 ? 30 : 0,
      fontSize: 10,
    },
  }
}

/** tooltip 统一 confined，避免贴着容器边缘时被裁掉看不全。 */
function axisTooltip(valueFormatter: (value: number) => string) {
  return {
    trigger: 'axis',
    confine: true,
    valueFormatter,
  }
}

const hitRateOption = computed<EChartsCoreOption>(() => ({
  tooltip: axisTooltip((value: number) => formatPercent(value)),
  grid: { left: 56, right: 24, top: 24, bottom: 56 },
  xAxis: timeCategoryAxis(),
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
  tooltip: axisTooltip((value: number) => formatMs(value)),
  legend: { data: ['平均首 Token', 'P95 首 Token', '平均耗时'], top: 0, left: 'center', itemGap: 12, textStyle: { fontSize: 11 } },
  grid: { left: 72, right: 24, top: 52, bottom: 56 },
  xAxis: timeCategoryAxis(),
  yAxis: { type: 'value', axisLabel: { formatter: (value: number) => `${Math.round(value)}ms` } },
  series: [
    { name: '平均首 Token', type: 'line', smooth: true, showSymbol: false, connectNulls: true, data: series.value.map(point => point.avgFirstTokenMs) },
    { name: 'P95 首 Token', type: 'line', smooth: true, showSymbol: false, connectNulls: true, data: series.value.map(point => point.p95FirstTokenMs) },
    { name: '平均耗时', type: 'line', smooth: true, showSymbol: false, connectNulls: true, data: series.value.map(point => point.avgDurationMs) },
  ],
}))

const volumeOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'axis', confine: true },
  legend: { data: ['请求数', '实际成本'], top: 0, left: 'center', itemGap: 12, textStyle: { fontSize: 11 } },
  grid: { left: 64, right: 72, top: 52, bottom: 56 },
  xAxis: timeCategoryAxis(),
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
    matrixWindows.value = []
    matrixRows.value = []
    ingestStatus.value = null
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
  void loadMatrix()
  void loadIngestStatus()
}

/**
 * 拉取多时间窗缓存率对比矩阵。
 *
 * 数据来源是本地已落库的逐请求明细：配置了直连库时由「号池明细增量（直连库）」任务每 30 秒写入一批，
 * 未配置时退回「号池明细采集」任务的 10 分钟一轮，分钟级窗口会因此长期为空。
 */
async function loadMatrix() {
  if (!selectedPlatformId.value) {
    matrixWindows.value = []
    matrixRows.value = []
    return
  }
  if (!selectedWindows.value.length) {
    selectedWindows.value = ['24h']
  }
  matrixLoading.value = true
  try {
    const matrix = await listPoolCacheRates(selectedPlatformId.value, selectedWindows.value)
    matrixWindows.value = matrix.windows
    matrixMinimumSample.value = matrix.minimumSample || 20
    matrixRows.value = matrix.accounts.map(row => {
      const cells: Record<string, PoolCacheRateCell> = {}
      for (const cell of row.cells) cells[cell.key] = cell
      return {
        externalAccountId: row.externalAccountId,
        name: row.name,
        platform: row.platform,
        boundKeyName: row.boundKeyName,
        boundKeyMasked: row.boundKeyMasked,
        cells,
      }
    })
  } catch (error) {
    matrixWindows.value = []
    matrixRows.value = []
    ElMessage.error(error instanceof Error ? error.message : '缓存率对比加载失败')
  } finally {
    matrixLoading.value = false
  }
}

/** 拉取直连增量状态：失败不打扰用户，只是把标签置为「未知」。 */
async function loadIngestStatus() {
  if (!selectedPlatformId.value) {
    ingestStatus.value = null
    return
  }
  try {
    ingestStatus.value = await getPoolIngestStatus(selectedPlatformId.value)
  } catch {
    ingestStatus.value = null
  }
}

/** 取某行某时间窗的单元格（el-table 插槽行类型为 DefaultRow）。 */
function cellOf(row: unknown, key: string): PoolCacheRateCell | null {
  const target = row as MatrixRowView
  return target?.cells?.[key] ?? null
}

/** 样本数低于后端阈值才标注，避免前端硬编码的阈值和后端口径漂移。 */
function isThinSample(cell: PoolCacheRateCell | null | undefined): boolean {
  if (!cell || cell.requests <= 0) return false
  return cell.requests < matrixMinimumSample.value
}

/** 矩阵单元格悬停说明：口径（分子/分母）+ 样本量，避免只看百分比产生误读。 */
function matrixCellTip(cell: PoolCacheRateCell | null): string {
  if (!cell || cell.requests === 0) return '该时间窗内没有请求'
  const lines = [
    `${cell.requests} 条请求`,
    `缓存读取 ${formatNumber(cell.cacheRateNumerator)} / 输入+缓存 ${formatNumber(cell.cacheRateDenominator)}`,
  ]
  if (isThinSample(cell)) lines.push(`样本不足 ${matrixMinimumSample.value} 条，仅供参考`)
  return lines.join('\n')
}

/** 切换详情时间窗：长窗口下分钟粒度会自动降级为小时（与后端一致）。 */
function onDetailRangeChange() {
  if (!minuteAvailable.value && granularity.value === 'minute') {
    granularity.value = 'hour'
  }
  if (activeAccount.value) void loadDetail(activeAccount.value)
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
      listPoolSeries(selectedPlatformId.value, row.externalAccountId, detailRange.value, granularity.value),
      listPoolModels(selectedPlatformId.value, row.externalAccountId, detailRange.value),
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

/** 直连增量的滞后用紧凑单位展示（秒 / 分 / 小时 / 天）。 */
function formatLag(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`
  if (seconds < 86400) return `${(seconds / 3600).toFixed(1)}h`
  return `${Math.floor(seconds / 86400)}d`
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
  if (granularity.value === 'day') {
    return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
  }
  if (granularity.value === 'minute') {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit' })
}

// 换平台/账号列表更新后，清掉已经不存在的筛选项，避免筛选把自己筛空
watch(platformOptions, options => {
  if (!platformFilter.value.length) return
  const kept = platformFilter.value.filter(name => options.includes(name))
  if (kept.length !== platformFilter.value.length) platformFilter.value = kept
})

watch(poolSources, list => {
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