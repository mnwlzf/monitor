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
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索渠道或说明" />
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
            <div class="admin-diff-line">
              <code class="admin-diff-old">{{ row.oldValue }}</code>
              <span class="admin-diff-arrow">→</span>
              <code class="admin-diff-new">{{ row.newValue }}</code>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="message" label="说明" min-width="220" show-overflow-tooltip />
      </el-table>

      <el-empty v-else description="暂无变更记录" />
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import type { ChangeEvent } from '../types'

const props = defineProps<{ changes: ChangeEvent[] }>()

const keyword = ref('')
const typeFilter = ref('')
const severityFilter = ref('')

const typeOptions = [
  { label: '渠道新增', value: 'GROUP_ADDED' },
  { label: '渠道下线', value: 'GROUP_REMOVED' },
  { label: '渠道更新', value: 'GROUP_UPDATED' },
  { label: '倍率变化', value: 'RATE_CHANGED' },
  { label: '基础倍率变化', value: 'BASE_RATE_CHANGED' },
  { label: '状态变化', value: 'STATUS_CHANGED' },
]

const filteredChanges = computed(() => props.changes.filter(change => {
  const text = `${change.entity} ${change.field} ${change.message} ${change.type}`.toLowerCase()
  const keywordMatched = !keyword.value || text.includes(keyword.value.trim().toLowerCase())
  const typeMatched = !typeFilter.value || change.type === typeFilter.value
  const severityMatched = !severityFilter.value || change.severity === severityFilter.value
  return keywordMatched && typeMatched && severityMatched
}))

function changeLabel(type: string) {
  return ({ GROUP_ADDED: '渠道新增', GROUP_REMOVED: '渠道下线', RATE_CHANGED: '倍率变化', BASE_RATE_CHANGED: '基础倍率变化', STATUS_CHANGED: '状态变化', GROUP_UPDATED: '渠道更新' } as Record<string, string>)[type] || type
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