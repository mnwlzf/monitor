<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div>
        <p class="admin-eyebrow">CHANNELS</p>
        <h2>渠道监控</h2>
        <p>按平台实例查看渠道倍率、账号归属和当前状态。</p>
      </div>
      <el-tag effect="plain">{{ uniqueChannelCount }} 个渠道</el-tag>
    </div>

    <el-card shadow="never" class="admin-card admin-toolbar-card">
      <div class="admin-toolbar">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索渠道名称或账号" />
        <el-select v-model="instanceFilter" clearable placeholder="全部平台实例" class="admin-toolbar-select">
          <el-option v-for="name in platformNames" :key="name" :label="name" :value="name" />
        </el-select>
        <el-select v-model="providerFilter" clearable placeholder="全部上游类型" class="admin-toolbar-select">
          <el-option v-for="provider in providers" :key="provider" :label="provider" :value="provider" />
        </el-select>
        <el-select v-model="statusFilter" clearable placeholder="全部状态" class="admin-toolbar-select">
          <el-option label="可用" value="active" />
          <el-option label="停用" value="inactive" />
        </el-select>
      </div>
      <div class="admin-summary-line">
        <span>筛选后 <strong>{{ filteredRows.length }}</strong> 个渠道</span>
        <span class="muted">已按「渠道名 + 上游类型」去重</span>
      </div>
    </el-card>

    <el-card shadow="never" class="admin-card admin-table-card">
      <el-table
        v-if="filteredRows.length"
        :data="filteredRows"
        row-key="id"
        stripe
        class="admin-table"
        :default-sort="{ prop: 'ratio', order: 'descending' }"
      >
        <el-table-column prop="name" label="渠道名称" min-width="220" fixed show-overflow-tooltip>
          <template #default="{ row }">
            <strong>{{ row.name }}</strong>
            <div class="admin-table-sub muted">ID {{ row.id }}</div>
          </template>
        </el-table-column>

        <el-table-column label="平台实例" min-width="170">
          <template #default="{ row }">
            <el-tag size="small" :type="row.platformType === 'newapi' ? 'primary' : 'success'" effect="plain">{{ row.platformName }}</el-tag>
            <div class="admin-table-sub muted">{{ row.accountName }}</div>
          </template>
        </el-table-column>

        <el-table-column prop="platform" label="上游类型" min-width="130" sortable />

        <el-table-column prop="ratio" label="当前倍率" min-width="120" sortable align="right">
          <template #default="{ row }">
            <strong class="admin-num">{{ row.ratio.toFixed(2) }}</strong>
          </template>
        </el-table-column>

        <el-table-column prop="baseRatio" label="基础倍率" min-width="120" sortable align="right">
          <template #default="{ row }">
            <span class="admin-num">{{ row.baseRatio == null ? '—' : row.baseRatio.toFixed(2) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="倍率差" min-width="110" align="right">
          <template #default="{ row }">
            <span v-if="row.baseRatio == null" class="muted">—</span>
            <span v-else class="admin-num" :class="ratioDeltaClass(asChannel(row))">{{ ratioDelta(asChannel(row)) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" min-width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status.toLowerCase() === 'active' ? 'success' : 'info'" effect="light">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else description="暂无渠道数据" />
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import type { Channel } from '../types'

const props = defineProps<{ channels: Channel[] }>()
const keyword = ref('')
const instanceFilter = ref('')
const providerFilter = ref('')
const statusFilter = ref('')

const platformNames = computed(() => [...new Set(props.channels.map(channel => channel.platformName).filter(Boolean))])
const providers = computed(() => [...new Set(props.channels.map(channel => channel.platform).filter(Boolean))])

/** 同一平台下多个账号会采到相同渠道，按「渠道名 + 上游类型」去重后只展示一次。 */
const dedupedChannels = computed(() => {
  const seen = new Set<string>()
  const rows: Channel[] = []
  for (const channel of props.channels) {
    const key = `${channel.platformId}::${channel.name}::${channel.platform}`
    if (seen.has(key)) continue
    seen.add(key)
    rows.push(channel)
  }
  return rows
})

const filteredRows = computed(() => dedupedChannels.value.filter(channel => {
  const text = `${channel.name} ${channel.accountName} ${channel.platformName}`.toLowerCase()
  const keywordMatched = !keyword.value || text.includes(keyword.value.trim().toLowerCase())
  const instanceMatched = !instanceFilter.value || channel.platformName === instanceFilter.value
  const providerMatched = !providerFilter.value || channel.platform === providerFilter.value
  const statusMatched = !statusFilter.value || channel.status.toLowerCase() === statusFilter.value
  return keywordMatched && instanceMatched && providerMatched && statusMatched
}))

const uniqueChannelCount = computed(() => dedupedChannels.value.length)

function asChannel(row: unknown): Channel { return row as Channel }

function ratioDelta(channel: Channel): string {
  if (channel.baseRatio == null) return '—'
  const delta = channel.ratio - channel.baseRatio
  return `${delta > 0 ? '+' : ''}${delta.toFixed(2)}`
}

function ratioDeltaClass(channel: Channel): string {
  if (channel.baseRatio == null) return ''
  const delta = channel.ratio - channel.baseRatio
  if (delta > 0) return 'warn'
  if (delta < 0) return 'ok'
  return ''
}
</script>