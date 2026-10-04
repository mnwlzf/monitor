<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div>
        <p class="admin-eyebrow">CHANGES</p>
        <h2>变更记录</h2>
        <p>渠道新增、减少、倍率和状态变化。</p>
      </div>
      <el-tag effect="plain">{{ filteredChanges.length }} 条记录</el-tag>
    </div>

    <el-card shadow="never" class="admin-card admin-toolbar-card">
      <div class="admin-toolbar">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索渠道、说明或平台" />
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
      </div>
    </el-card>

    <el-card shadow="never" class="admin-card admin-table-card">
      <el-table v-if="filteredChanges.length" :data="filteredChanges" row-key="id" stripe class="admin-table">
        <el-table-column label="时间" width="160" fixed>
          <template #default="{ row }">
            <span class="admin-table-stack">{{ formatDate(row.detectedAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="平台" min-width="160">
          <template #default="{ row }">
            <el-tag size="small" :type="row.platformType === 'newapi' ? 'primary' : 'success'" effect="plain">{{ row.platformName || '未知平台' }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="类型" min-width="140">
          <template #default="{ row }">
            <el-tag :type="severityType(row.severity)" effect="light">{{ changeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="entity" label="渠道" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <strong>{{ row.entity || '—' }}</strong>
            <div v-if="row.field" class="admin-table-sub muted">{{ row.field }}</div>
          </template>
        </el-table-column>

        <el-table-column label="变化" min-width="280">
          <template #default="{ row }">
            <div v-if="changeSummary(asChange(row))" class="admin-change-summary">
              <span class="admin-change-chip">{{ changeSummary(asChange(row)) }}</span>
              <el-button v-if="hasRawDetail(asChange(row))" link type="primary" size="small" @click="openChangeDetail(asChange(row))">查看详情</el-button>
            </div>
            <div v-else class="admin-diff-line">
              <code class="admin-diff-old">{{ displayChangeValue(row.oldValue) }}</code>
              <span class="admin-diff-arrow">→</span>
              <code class="admin-diff-new">{{ displayChangeValue(row.newValue) }}</code>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="message" label="说明" min-width="220" show-overflow-tooltip />
      </el-table>

      <el-empty v-else description="暂无变更记录" />
    </el-card>

    <el-dialog v-model="showChangeDetail" title="变更详情" width="720px" destroy-on-close append-to-body>
      <template v-if="changeDetail">
        <div class="admin-change-detail-meta">
          <el-tag size="small" effect="plain">{{ changeLabel(changeDetail.type) }}</el-tag>
          <span class="muted">{{ changeDetail.platformName || '未知平台' }} · {{ formatDate(changeDetail.detectedAt) }}</span>
        </div>
        <p class="admin-change-detail-message">{{ changeDetail.message || '—' }}</p>
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
import { computed, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import type { ChangeEvent, Platform } from '../types'

const props = defineProps<{ changes: ChangeEvent[]; platforms: Platform[] }>()

const keyword = ref('')
const platformFilter = ref<number | null>(null)
const typeFilter = ref('')
const severityFilter = ref('')
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
  const text = `${change.entity} ${change.field} ${change.message} ${change.type} ${change.platformName}`.toLowerCase()
  const keywordMatched = !keyword.value || text.includes(keyword.value.trim().toLowerCase())
  const platformMatched = !platformFilter.value || change.platformId === platformFilter.value
  const typeMatched = !typeFilter.value || change.type === typeFilter.value
  const severityMatched = !severityFilter.value || change.severity === severityFilter.value
  return keywordMatched && platformMatched && typeMatched && severityMatched
}))

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

/** 详情弹窗里的值：能解析为 JSON 的做格式化，否则原样展示。 */
function prettyValue(value: string | null | undefined) {
  const text = (value ?? '').trim()
  if (!text || text === 'null') return '—'
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

function openChangeDetail(change: ChangeEvent) {
  changeDetail.value = change
  showChangeDetail.value = true
}

function severityType(severity: ChangeEvent['severity']) {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'WARNING') return 'warning'
  return 'success'
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>
