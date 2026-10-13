<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div>
        <p class="admin-eyebrow">CHANGES</p>
        <h2>变更记录</h2>
        <p>渠道新增、减少、倍率和状态变化。</p>
      </div>
      <el-tag effect="plain">{{ displayRows.length }} 行 · {{ filteredChanges.length }} 个变化</el-tag>
    </div>

    <el-card shadow="never" class="admin-card admin-toolbar-card">
      <div class="admin-toolbar">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索账号、渠道 / 密钥、说明或平台" />
        <el-select v-model="platformFilter" clearable placeholder="全部平台" class="admin-toolbar-select">
          <el-option v-for="platform in platforms" :key="platform.id" :label="platform.name" :value="platform.id" />
        </el-select>
        <el-select v-model="typeFilter" clearable placeholder="全部类型" class="admin-toolbar-select">
          <el-option v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-select v-model="severityFilter" clearable placeholder="全部级别" class="admin-toolbar-select">
          <el-option label="正常" value="INFO" />
          <el-option label="警告" value="WARNING" />
          <el-option label="严重" value="CRITICAL" />
        </el-select>
        <el-checkbox v-model="onlyInUse" label="只看使用中的密钥变更" />
        <el-button v-if="hasActiveFilter" link type="primary" :icon="RefreshLeft" @click="resetFilters">重置筛选</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="admin-card admin-table-card">
      <el-table
        v-if="displayRows.length"
        :data="pagedRows"
        row-key="key"
        stripe
        class="admin-table"
        :row-class-name="changeRowClass"
      >
        <el-table-column label="时间" width="150" fixed>
          <template #default="{ row }">
            <span class="admin-table-stack">{{ formatDate(row.change.detectedAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="平台" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="row.change.platformType === 'newapi' ? 'primary' : 'success'" effect="plain">
              {{ row.change.platformName || '未知平台' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="类型" min-width="170">
          <template #default="{ row }">
            <el-tag :type="severityType(row.change.severity)" effect="light">{{ changeLabel(row.change.type) }}</el-tag>
            <el-tag v-if="row.change.inUse" size="small" type="danger" effect="dark" class="admin-change-inuse-tag">使用中</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="本平台账号" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.impact?.accountName || '—' }}</template>
        </el-table-column>

        <el-table-column label="所属分组" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.impact?.groupName || '—' }}</template>
        </el-table-column>

        <el-table-column label="分组倍率" width="100" align="right">
          <template #default="{ row }">{{ row.impact ? formatRatio(row.impact.groupRatio) : '—' }}</template>
        </el-table-column>

        <el-table-column label="账号倍率" width="100" align="right">
          <template #default="{ row }">{{ row.impact ? formatRatio(row.impact.accountRatio) : '—' }}</template>
        </el-table-column>

        <el-table-column label="结算倍率" width="110" align="right">
          <template #default="{ row }">
            <strong v-if="row.impact">{{ formatRatio(row.impact.effectiveRatio) }}</strong>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="上游倍率（原 → 新）" min-width="150">
          <template #default="{ row }">
            <span v-if="row.impact">{{ formatRatio(row.impact.oldRatio) }} → {{ formatRatio(row.impact.newRatio) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="涨价幅度" width="100" align="right">
          <template #default="{ row }">
            <span v-if="row.impact?.increasePercent != null">{{ formatRatio(row.impact.increasePercent) }}%</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="是否受影响" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.impact" size="small" :type="row.impact.affected ? 'danger' : 'info'" effect="plain">
              {{ row.impact.affected ? '是（倒挂）' : '否' }}
            </el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="提醒级别" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.impact" size="small" :type="severityType(row.impact.level)" effect="plain">
              {{ impactLevelLabel(row.impact.level) }}
            </el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="上游渠道 / 密钥" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <strong>{{ row.change.entityName || row.change.entity || '—' }}</strong>
            <div v-if="entitySubtitle(row.change)" class="admin-table-sub muted">{{ entitySubtitle(row.change) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="说明" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.change.message || '—' }}</span>
            <el-button
              v-if="hasRawDetail(row.change)"
              link
              type="primary"
              size="small"
              class="admin-change-detail-link"
              @click="openChangeDetail(row.change)"
            >
              查看详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else description="暂无变更记录" />

      <div v-if="displayRows.length > pageSize" class="admin-pagination">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          :total="displayRows.length"
          layout="total, sizes, prev, pager, next, jumper"
          background
        />
      </div>
    </el-card>

    <el-dialog v-model="showChangeDetail" title="变更详情" width="720px" destroy-on-close append-to-body>
      <template v-if="changeDetail">
        <div class="admin-change-detail-meta">
          <el-tag size="small" effect="plain">{{ changeLabel(changeDetail.type) }}</el-tag>
          <span class="muted">{{ changeDetail.platformName || '未知平台' }} · {{ formatDate(changeDetail.detectedAt) }}</span>
        </div>
        <p class="admin-change-detail-target">
          本平台 Key：{{ changeDetail.localKeyNames || '—' }} ·
          上游 Key：{{ changeDetail.upstreamKeyNames || '—' }} ·
          上游账号：{{ changeDetail.accountName || '—' }} ·
          分组：{{ changeDetail.entityName || changeDetail.entity || '—' }}
          <template v-if="changeDetail.groupRatio != null"> · 分组倍率：{{ formatRatio(changeDetail.groupRatio) }}</template>
        </p>
        <p class="admin-change-detail-message">{{ changeDetail.message || '—' }}</p>
        <div v-if="changeDetail.impacts?.length" class="admin-change-detail-impact">
          <span class="muted">对我平台的影响（上游新倍率 vs 分组倍率）</span>
          <table class="admin-change-impact-table">
            <thead>
              <tr>
                <th>本平台账号</th>
                <th>所属分组</th>
                <th>分组倍率</th>
                <th>账号倍率</th>
                <th>结算倍率</th>
                <th>上游倍率（原 → 新）</th>
                <th>涨价幅度</th>
                <th>是否受影响</th>
                <th>提醒级别</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="impact in changeDetail.impacts" :key="`${impact.externalAccountId}-${impact.groupName}`" :class="{ 'is-affected': impact.affected }">
                <td>{{ impact.accountName || '—' }}</td>
                <td>{{ impact.groupName || '—' }}</td>
                <td>{{ formatRatio(impact.groupRatio) }}</td>
                <td>{{ formatRatio(impact.accountRatio) }}</td>
                <td>{{ formatRatio(impact.effectiveRatio) }}</td>
                <td>{{ formatRatio(impact.oldRatio) }} → {{ formatRatio(impact.newRatio) }}</td>
                <td>{{ impact.increasePercent == null ? '—' : `${formatRatio(impact.increasePercent)}%` }}</td>
                <td>{{ impact.affected ? '是（倒挂）' : '否' }}</td>
                <td>{{ impactLevelLabel(impact.level) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="changeDetail.field" class="admin-change-detail-field">字段：{{ changeDetail.field }}</div>
        <div class="admin-change-detail-block">
          <span class="muted">变更前</span>
          <pre class="admin-change-detail-pre">{{ prettyValue(changeDetail.oldValue) }}</pre>
        </div>
        <div class="admin-change-detail-block">
          <span class="muted">变更后</span>
          <pre class="admin-change-detail-pre">{{ prettyValue(changeDetail.newValue) }}</pre>
        </div>
      </template>
      <template #footer>
        <el-button type="primary" @click="showChangeDetail = false">关闭</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RefreshLeft, Search } from '@element-plus/icons-vue'
import type { ChangeEvent, ChangeImpact, Platform } from '../types'

const props = defineProps<{ changes: ChangeEvent[]; platforms: Platform[] }>()

const keyword = ref('')
const platformFilter = ref<number | null>(null)
const typeFilter = ref('')
const severityFilter = ref('')
const onlyInUse = ref(false)

const page = ref(1)
const pageSize = ref(20)

/** 表格行：一个「变化 × 影响分组」= 一行，完全不做合并。 */
interface ChangeRow {
  key: string
  change: ChangeEvent
  impact: ChangeImpact | null
}

/** 是否处于筛选中（用于显示「重置筛选」）。 */
const hasActiveFilter = computed(() =>
  Boolean(keyword.value || platformFilter.value || typeFilter.value || severityFilter.value || onlyInUse.value))

function resetFilters() {
  keyword.value = ''
  platformFilter.value = null
  typeFilter.value = ''
  severityFilter.value = ''
  onlyInUse.value = false
}
const showChangeDetail = ref(false)
const changeDetail = ref<ChangeEvent | null>(null)

const typeOptions = [
  { label: '渠道新增', value: 'GROUP_ADDED' },
  { label: '渠道下线', value: 'GROUP_REMOVED' },
  { label: '渠道更新', value: 'GROUP_UPDATED' },
  { label: '倍率变化', value: 'RATE_CHANGED' },
  { label: '基础倍率变化', value: 'BASE_RATE_CHANGED' },
  { label: '状态变化', value: 'STATUS_CHANGED' },
  { label: '密钥新增', value: 'API_KEY_ADDED' },
  { label: '密钥失效', value: 'API_KEY_REMOVED' },
  { label: '密钥轮换', value: 'API_KEY_ROTATED' },
  { label: '密钥更新', value: 'API_KEY_UPDATED' },
]

const filteredChanges = computed(() => props.changes.filter(change => {
  const impactText = (change.impacts ?? []).map(impact => `${impact.accountName} ${impact.groupName}`).join(' ')
  const text = `${change.accountName} ${change.upstreamKeyNames} ${change.localKeyNames} ${change.entityName} ${change.entity} ${change.field} ${change.message} ${change.type} ${change.platformName} ${impactText}`.toLowerCase()
  const keywordMatched = !keyword.value || text.includes(keyword.value.trim().toLowerCase())
  const platformMatched = !platformFilter.value || change.platformId === platformFilter.value
  const typeMatched = !typeFilter.value || change.type === typeFilter.value
  const severityMatched = !severityFilter.value || change.severity === severityFilter.value
  const inUseMatched = !onlyInUse.value || change.inUse
  return keywordMatched && platformMatched && typeMatched && severityMatched && inUseMatched
}))

/**
 * 展开成一行一个变化：渠道倍率变化会按「账号 × 分组」拆成多行，
 * 没有影响评估的事件（如密钥变更）保持一行。
 */
const displayRows = computed<ChangeRow[]>(() => {
  const rows: ChangeRow[] = []
  for (const change of filteredChanges.value) {
    const impacts = change.impacts ?? []
    if (!impacts.length) {
      rows.push({ key: change.id, change, impact: null })
      continue
    }
    impacts.forEach((impact, index) => {
      rows.push({
        key: `${change.id}-${impact.externalAccountId ?? 'x'}-${impact.groupName}-${index}`,
        change,
        impact,
      })
    })
  }
  return rows
})

/** 正在使用密钥的变更整行高亮，便于快速定位。 */
function changeRowClass({ row }: { row: ChangeRow }) {
  return row.change.inUse ? 'admin-change-in-use-row' : ''
}

/** 当前页展示的行。 */
const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return displayRows.value.slice(start, start + pageSize.value)
})

/** 筛选条件变化时回到第一页。 */
watch([keyword, platformFilter, typeFilter, severityFilter, onlyInUse, pageSize], () => {
  page.value = 1
})

/** 数据减少（刷新/筛选）后把页码收回，避免停留在空页。 */
watch(() => displayRows.value.length, () => {
  const maxPage = Math.max(1, Math.ceil(displayRows.value.length / pageSize.value))
  if (page.value > maxPage) page.value = maxPage
})

function changeLabel(type: string) {
  return ({
    GROUP_ADDED: '渠道新增',
    GROUP_REMOVED: '渠道下线',
    RATE_CHANGED: '倍率变化',
    BASE_RATE_CHANGED: '基础倍率变化',
    STATUS_CHANGED: '状态变化',
    GROUP_UPDATED: '渠道更新',
    API_KEY_ADDED: '密钥新增',
    API_KEY_REMOVED: '密钥失效',
    API_KEY_ROTATED: '密钥轮换',
    API_KEY_UPDATED: '密钥更新',
  } as Record<string, string>)[type] || type
}

function asChange(row: unknown): ChangeEvent { return row as ChangeEvent }

/** 变更字段的中文名：渠道与密钥的字段含义不同，分开映射避免误读。 */
const channelFieldLabels: Record<string, string> = {
  ratio: '倍率',
  base_ratio: '基础倍率',
  status: '状态',
  name: '渠道名称',
  platform: '上游平台',
  description: '描述',
  is_active: '启用状态',
}

const apiKeyFieldLabels: Record<string, string> = {
  name: '密钥名称',
  status: '密钥状态',
  group: '所属分组',
  key: '密钥明文',
  is_active: '启用状态',
}

/**
 * 表格副标题：字段中文名 + 上游 ID。
 * 名字已经显示在主行；这里保留 ID 便于和上游后台对号入座。
 */
function entitySubtitle(change: ChangeEvent) {
  const parts: string[] = []
  const field = fieldLabel(change)
  if (field) parts.push(field)
  if (change.entity && change.entity !== change.entityName) parts.push(`ID ${change.entity}`)
  return parts.join(' · ')
}

function fieldLabel(change: ChangeEvent) {
  if (!change.field) return ''
  const labels = change.type.startsWith('API_KEY_') ? apiKeyFieldLabels : channelFieldLabels
  return labels[change.field] ?? change.field
}

/** 变更前后值是否为上游响应体等大块 JSON，需要折叠展示。 */
function isRawPayload(value: string | null | undefined) {
  const text = (value ?? '').trim()
  if (!text || text === 'null') return false
  if (text.length > 60) return true
  return (text.startsWith('{') && text.endsWith('}')) || (text.startsWith('[') && text.endsWith(']'))
}

const changeSummaryLabels: Record<string, string> = {
  GROUP_ADDED: '新增渠道',
  API_KEY_ADDED: '新增密钥',
  API_KEY_REMOVED: '密钥失效',
  API_KEY_ROTATED: '密钥轮换',
  API_KEY_UPDATED: '密钥更新',
}

/**
 * 变化列的摘要文案；返回 null 表示两侧都是普通标量，按 old → new 展示。
 * 新增类事件的上游响应体会塞满整列，这里只给结论，明细放进详情弹窗；
 * 轮换类事件前后值都是 null，没有可对比内容，同样用摘要代替。
 */
function changeSummary(change: ChangeEvent): string | null {
  const raw = isRawPayload(change.oldValue) || isRawPayload(change.newValue)
  const empty = isEmptyValue(change.oldValue) && isEmptyValue(change.newValue)
  if (!raw && !empty) return null
  return changeSummaryLabels[change.type] ?? (raw ? '内容已更新' : null)
}

function isEmptyValue(value: string | null | undefined) {
  const text = (value ?? '').trim()
  return !text || text === 'null'
}

function hasRawDetail(change: ChangeEvent) {
  return isRawPayload(change.oldValue) || isRawPayload(change.newValue)
}

/** 普通标量对比：空值与 JSON null 统一显示为破折号。 */
function displayChangeValue(value: string | null | undefined) {
  const text = (value ?? '').trim()
  return !text || text === 'null' ? '—' : text
}

/** 详情弹窗里的值：能解析为 JSON 的做格式化，否则原样展示；密钥字段一律脱敏。 */
function prettyValue(value: string | null | undefined) {
  const text = (value ?? '').trim()
  if (!text || text === 'null') return '—'
  try {
    return JSON.stringify(maskSecrets(JSON.parse(text)), null, 2)
  } catch {
    return maskSecretsInText(text)
  }
}

/** 递归脱敏 JSON 里的密钥字段，避免详情弹窗展示明文 key。 */
function maskSecrets(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(maskSecrets)
  }
  if (value && typeof value === 'object') {
    const result: Record<string, unknown> = {}
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      result[key] = key === 'key' ? maskKeyValue(item) : maskSecrets(item)
    }
    return result
  }
  return value
}

function maskKeyValue(value: unknown) {
  if (typeof value !== 'string' || !value) return value
  if (value.includes('*') || value.length <= 12) return value
  return `${value.slice(0, 6)}****${value.slice(-4)}`
}

/** 非 JSON 文本里的 sk-xxx 也做兜底脱敏。 */
function maskSecretsInText(text: string) {
  return text.replace(/sk-[A-Za-z0-9_-]{16,}/g, match => `${match.slice(0, 6)}****${match.slice(-4)}`)
}

function openChangeDetail(change: ChangeEvent) {
  changeDetail.value = change
  showChangeDetail.value = true
}

/** 影响评估的提醒级别文案。 */
function impactLevelLabel(level: ChangeImpact['level']) {
  if (level === 'CRITICAL') return '严重'
  if (level === 'WARNING') return '警告'
  return '信息'
}

function severityType(severity: ChangeEvent['severity']) {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'WARNING') return 'warning'
  return 'success'
}

/** 倍率去掉无意义的尾零：1.50000000 → 1.5。 */
function formatRatio(value: number | null) {
  return value == null ? '' : String(Number(value))
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped>
.admin-change-inuse-tag {
  margin-left: 6px;
}

.admin-change-detail-link {
  margin-left: 6px;
}

.admin-change-detail-impact {
  margin-top: 12px;
  font-size: 13px;
}

.admin-change-impact-table {
  width: 100%;
  margin-top: 6px;
  border-collapse: collapse;
  font-size: 12px;
}

.admin-change-impact-table th,
.admin-change-impact-table td {
  padding: 6px 8px;
  border: 1px solid #ebeef5;
  text-align: left;
}

.admin-change-impact-table tr.is-affected td {
  background-color: #fff1f0;
  color: #c45656;
}

/* 详情弹窗顶部的「本平台 Key · 上游 Key · 分组」一行 */
.admin-change-detail-target {
  margin: 10px 0 0;
  font-size: 13px;
  color: #303133;
}

/* 正在使用密钥的变更整行高亮（覆盖 stripe 交替底色） */
:deep(.admin-change-in-use-row) > td.el-table__cell {
  background-color: #fff1f0 !important;
}

:deep(.admin-change-in-use-row) > td.el-table__cell:first-child {
  box-shadow: inset 3px 0 0 #f56c6c;
}
</style>
