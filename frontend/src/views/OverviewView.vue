<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div>
        <p class="admin-eyebrow">OVERVIEW</p>
        <h2>运行总览</h2>
        <p>账号余额、额度消耗、平台健康度和渠道变更的整体状态。</p>
      </div>
      <el-select :model-value="selectedAccountId" class="admin-account-picker" placeholder="选择观察账号" @change="emit('select-account', Number($event))">
        <el-option v-for="account in accounts" :key="account.id" :label="account.displayName" :value="account.id" />
      </el-select>
    </div>

    <el-row :gutter="16" class="admin-metric-grid">
      <el-col :xs="12" :sm="6"><MetricCard label="平台数量" :value="String(platforms.length)" hint="已接入上游平台" tone="neutral" /></el-col>
      <el-col :xs="12" :sm="6"><MetricCard label="账号数量" :value="String(accounts.length)" hint="已纳管采集账号" tone="positive" /></el-col>
      <el-col :xs="12" :sm="6"><MetricCard label="当前余额" :value="formatMoney(totalBalance)" hint="所有账号余额合计" tone="positive" /></el-col>
      <el-col :xs="12" :sm="6"><MetricCard label="异常账号" :value="String(failedAccounts)" hint="最近采集失败账号" tone="danger" /></el-col>
    </el-row>

    <el-row :gutter="16" class="admin-grid-main">
      <el-col :xs="24" :xl="16">
        <el-card shadow="never" class="admin-card admin-chart-card">
          <template #header>
            <div class="admin-card-header">
              <div><h3>余额与消耗趋势</h3><p>按采集时间展示账号余额和已用额度</p></div>
              <el-tag type="success" effect="light" round>实时快照</el-tag>
            </div>
          </template>
          <LineChart v-if="series.length" :points="series" />
          <el-empty v-else description="暂无账号指标数据" :image-size="90" />
        </el-card>
      </el-col>

      <el-col :xs="24" :xl="8">
        <el-card shadow="never" class="admin-card admin-side-card">
          <template #header>
            <div class="admin-card-header"><div><h3>平台健康度</h3><p>账号与采集状态概览</p></div></div>
          </template>
          <div v-if="platforms.length" class="admin-platform-health">
            <div v-for="platform in platforms" :key="platform.id" class="admin-health-row">
              <div class="admin-health-title">
                <span class="admin-health-dot" :class="{ online: platform.status }"></span>
                <strong>{{ platform.name }}</strong>
                <el-tag size="small" :type="platform.type === 'newapi' ? 'primary' : 'success'" effect="plain">{{ platform.type }}</el-tag>
              </div>
              <el-progress :percentage="accountPercent(platform.id)" :stroke-width="8" :show-text="false" />
              <small>{{ platform.accountCount }} 个账号 · {{ platform.lastCollectedAt ? formatDate(platform.lastCollectedAt) : '暂无采集' }}</small>
            </div>
          </div>
          <el-empty v-else description="暂无平台数据" :image-size="70" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="admin-grid-bottom">
      <el-col :xs="24" :xl="15">
        <el-card shadow="never" class="admin-card">
          <template #header>
            <div class="admin-card-header">
              <div><h3>账号概览</h3><p>余额、额度和最近采集状态</p></div>
              <el-tag effect="plain">{{ accounts.length }} 个账号</el-tag>
            </div>
          </template>
          <div v-if="accounts.length" class="admin-account-list"><div v-for="account in accounts.slice(0, 6)" :key="account.id" class="admin-account-row"><div class="admin-account-cell identity"><span class="admin-account-avatar">{{ account.displayName.slice(0, 1) }}</span><div><strong>{{ account.displayName }}</strong><small>{{ account.platformName }}</small></div></div><div class="admin-account-cell"><span>余额</span><strong class="admin-number">${{ Number(account.balance).toFixed(2) }}</strong></div><div class="admin-account-cell"><span>{{ secondaryMetric(account).label }}</span><strong>{{ secondaryMetric(account).value }}</strong></div><el-tag :type="statusType(account.lastCollectStatus)" effect="light">{{ account.lastCollectStatus }}</el-tag><time>{{ account.lastCollectedAt ? formatDate(account.lastCollectedAt) : '暂无采集' }}</time></div></div>
          <el-empty v-else description="暂无账号数据" :image-size="80" />
        </el-card>
      </el-col>

      <el-col :xs="24" :xl="9">
        <el-card shadow="never" class="admin-card">
          <template #header>
            <div class="admin-card-header"><div><h3>最近变更</h3><p>渠道与倍率变化记录</p></div></div>
          </template>
          <el-timeline v-if="changes.length" class="admin-timeline">
            <el-timeline-item
              v-for="change in changes.slice(0, 5)"
              :key="change.id"
              :timestamp="formatDate(change.detectedAt)"
              :type="change.severity === 'WARNING' ? 'warning' : change.severity === 'CRITICAL' ? 'danger' : 'success'"
            >
              <div class="admin-change-item"><strong>{{ change.entity }}</strong><p>{{ change.message }}</p><code>{{ change.oldValue }} → {{ change.newValue }}</code></div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无变更记录" :image-size="80" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="admin-grid-bottom">
      <el-col :xs="24">
        <el-card shadow="never" class="admin-card">
          <template #header>
            <div class="admin-card-header">
              <div><h3>用量看板</h3><p>统一读取各平台最新快照，展开行可查看全部平台特有指标；平台未提供的指标显示为 —</p></div>
              <el-tag effect="plain">{{ usageDashboards.length }} 条快照</el-tag>
            </div>
          </template>
          <el-table v-if="usageDashboards.length" :data="usageDashboards" size="small" style="width: 100%">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="admin-usage-detail">
                  <div v-if="platformStatRows(row).length" class="admin-usage-section">
                    <h4>按平台统计明细</h4>
                    <div class="admin-usage-platforms">
                      <div v-for="(stat, index) in platformStatRows(row)" :key="index" class="admin-usage-platform">
                        <strong>{{ platformLabel(stat) }}</strong>
                        <span>累计请求 {{ formatMetricNumber(asNumber(stat.total_requests)) }}</span>
                        <span>累计 Token {{ formatMetricNumber(asNumber(stat.total_tokens)) }}</span>
                        <span>累计实际成本 {{ formatMetricMoney(asNumber(stat.total_actual_cost)) }}</span>
                        <span>今日请求 {{ formatMetricNumber(asNumber(stat.today_requests)) }}</span>
                        <span>今日 Token {{ formatMetricNumber(asNumber(stat.today_tokens)) }}</span>
                        <span>今日实际成本 {{ formatMetricMoney(asNumber(stat.today_actual_cost)) }}</span>
                      </div>
                    </div>
                  </div>
                  <div class="admin-usage-section">
                    <h4>公共指标</h4>
                    <div class="admin-usage-metrics">
                      <div class="admin-usage-metric"><span>可用余额</span><strong>{{ formatMetricMoney(row.balance) }}</strong></div>
                      <div class="admin-usage-metric"><span>冻结余额</span><strong>{{ formatMetricMoney(row.frozenBalance) }}</strong></div>
                      <div class="admin-usage-metric"><span>累计请求</span><strong>{{ formatMetricNumber(row.totalRequests) }}</strong></div>
                      <div class="admin-usage-metric"><span>累计 Token</span><strong>{{ formatMetricNumber(row.totalTokens) }}</strong></div>
                      <div class="admin-usage-metric"><span>累计消耗</span><strong>{{ formatMetricMoney(row.totalCost) }}</strong></div>
                      <div class="admin-usage-metric"><span>累计实际成本</span><strong>{{ formatMetricMoney(row.totalActualCost) }}</strong></div>
                      <div class="admin-usage-metric"><span>采集时间</span><strong>{{ row.collectedAt ? formatDate(row.collectedAt) : '—' }}</strong></div>
                    </div>
                  </div>
                  <div class="admin-usage-section">
                    <h4>平台特有指标</h4>
                    <div v-if="metricEntries(row).length" class="admin-usage-metrics">
                      <div v-for="entry in metricEntries(row)" :key="entry.key" class="admin-usage-metric">
                        <span>{{ entry.label }}</span>
                        <strong>{{ entry.value }}</strong>
                      </div>
                    </div>
                    <p v-else class="admin-usage-empty">该平台未提供额外指标</p>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="账号" min-width="180">
              <template #default="{ row }">
                <div class="admin-usage-account">
                  <strong>{{ row.displayName }}</strong>
                  <el-tag size="small" :type="row.platformType === 'newapi' ? 'primary' : 'success'" effect="plain">{{ row.platformType }}</el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="余额" min-width="110" align="right">
              <template #default="{ row }">{{ formatMetricMoney(row.balance) }}</template>
            </el-table-column>
            <el-table-column label="累计请求" min-width="110" align="right">
              <template #default="{ row }">{{ formatMetricNumber(row.totalRequests) }}</template>
            </el-table-column>
            <el-table-column label="累计 Token" min-width="120" align="right">
              <template #default="{ row }">{{ formatMetricNumber(row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column label="累计成本" min-width="110" align="right">
              <template #default="{ row }">{{ formatMetricMoney(row.totalCost) }}</template>
            </el-table-column>
            <el-table-column label="今日消耗" min-width="110" align="right">
              <template #default="{ row }">{{ formatMetricMoney(metricValue(row, 'today_actual_cost')) }}</template>
            </el-table-column>
            <el-table-column label="今日请求" min-width="110" align="right">
              <template #default="{ row }">{{ formatMetricNumber(metricValue(row, 'today_requests')) }}</template>
            </el-table-column>
            <el-table-column label="今日 Token" min-width="120" align="right">
              <template #default="{ row }">{{ formatMetricNumber(metricValue(row, 'today_tokens')) }}</template>
            </el-table-column>
            <el-table-column label="密钥 (活跃/总)" min-width="130" align="right">
              <template #default="{ row }">{{ formatKeyCount(row) }}</template>
            </el-table-column>
            <el-table-column label="采集时间" min-width="150">
              <template #default="{ row }">{{ row.collectedAt ? formatDate(row.collectedAt) : '—' }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="暂无用量看板数据" :image-size="80" />
        </el-card>
      </el-col>
    </el-row>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MetricCard from '../components/MetricCard.vue'
import LineChart from '../components/LineChart.vue'
import type { Account, ChangeEvent, Channel, MetricPoint, Platform, UsageDashboard } from '../types'

const props = defineProps<{
  accounts: Account[]
  platforms: Platform[]
  channels: Channel[]
  changes: ChangeEvent[]
  series: MetricPoint[]
  usageDashboards: UsageDashboard[]
  selectedAccountId: number
}>()
const emit = defineEmits<{ 'select-account': [id: number] }>()

const totalBalance = computed(() => props.accounts.reduce((sum, account) => sum + Number(account.balance || 0), 0))
const failedAccounts = computed(() => props.accounts.filter(account => account.lastCollectStatus === 'FAILED').length)

function accountPercent(platformId: number) {
  if (!props.accounts.length) return 0
  return Math.round((props.accounts.filter(account => account.platformId === platformId).length / props.accounts.length) * 100)
}

function formatMoney(value: number) {
  return `$${value.toFixed(2)}`
}

/**
 * 用量指标允许为空：平台未提供时显示占位符，而不是补成 0。
 */
function formatMetricNumber(value: number | null | undefined) {
  return value == null ? '—' : Number(value).toLocaleString()
}

function formatMetricMoney(value: number | null | undefined) {
  return value == null ? '—' : `$${Number(value).toFixed(2)}`
}

/**
 * 账号概览的第二个指标按平台取口径：sub2api 没有额度概念，改用累计消耗。
 */
function secondaryMetric(account: Account) {
  if (account.platformType === 'sub2api') {
    const usage = props.usageDashboards.find(item => item.accountId === account.id)
    return { label: '累计消耗', value: formatMetricMoney(usage?.totalCost) }
  }
  return { label: '已用额度', value: Number(account.usedQuota || 0).toLocaleString() }
}

function metricValue(row: { metrics?: Record<string, unknown> | null }, key: string): number | null {
  const value = row.metrics?.[key]
  return typeof value === 'number' ? value : null
}

function asNumber(value: unknown): number | null {
  return typeof value === 'number' ? value : null
}

/**
 * 平台特有指标的中文标签，未登记的键回退为原始字段名。
 */
const metricLabels: Record<string, string> = {
  total_api_keys: '密钥总数',
  active_api_keys: '活跃密钥',
  total_input_tokens: '累计输入 Token',
  total_output_tokens: '累计输出 Token',
  total_cache_creation_tokens: '累计缓存写入 Token',
  total_cache_read_tokens: '累计缓存读取 Token',
  today_requests: '今日请求',
  today_input_tokens: '今日输入 Token',
  today_output_tokens: '今日输出 Token',
  today_cache_creation_tokens: '今日缓存写入 Token',
  today_cache_read_tokens: '今日缓存读取 Token',
  today_tokens: '今日 Token',
  today_cost: '今日成本',
  today_actual_cost: '今日实际成本',
  average_duration_ms: '平均耗时 (ms)',
  rpm: 'RPM',
  tpm: 'TPM',
  quota: '剩余额度（原始）',
  used_quota: '已用额度（原始）',
  aff_quota: '邀请额度',
  aff_history_quota: '历史邀请额度',
  quota_unit: '额度单位',
  quota_per_usd: '每 USD 额度',
}

/**
 * 展开行展示的完整平台指标，过滤掉上游未返回的空值。
 */
function metricEntries(row: { metrics?: Record<string, unknown> | null }) {
  const metrics = row.metrics ?? {}
  return Object.entries(metrics)
    .filter(([, value]) => value !== null && value !== undefined)
    .map(([key, value]) => ({
      key,
      label: metricLabels[key] ?? key,
      value: formatMetricValue(key, value),
    }))
}

function formatMetricValue(key: string, value: unknown): string {
  if (typeof value !== 'number') return String(value)
  if (key.includes('cost')) return `$${value.toFixed(4)}`
  if (key === 'rpm' || key === 'tpm' || key === 'average_duration_ms') {
    return value.toLocaleString(undefined, { maximumFractionDigits: 2 })
  }
  return value.toLocaleString()
}

function platformStatRows(row: { platformStats?: Array<Record<string, unknown>> | null }) {
  return row.platformStats ?? []
}

function platformLabel(stat: Record<string, unknown>) {
  return typeof stat.platform === 'string' && stat.platform ? stat.platform : '未知平台'
}

/**
 * 密钥数以「活跃 / 总数」展示，newapi 未提供时显示占位符。
 */
function formatKeyCount(row: { metrics?: Record<string, unknown> | null }) {
  const total = metricValue(row, 'total_api_keys')
  const active = metricValue(row, 'active_api_keys')
  if (total == null && active == null) return '—'
  return `${active ?? '—'} / ${total ?? '—'}`
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function statusType(status: Account['lastCollectStatus']) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'primary'
  return 'info'
}
</script>