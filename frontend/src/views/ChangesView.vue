<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div><p class="admin-eyebrow">CHANGES</p><h2>变更记录</h2><p>渠道新增、减少、倍率和状态变化。</p></div>
      <el-tag effect="plain">{{ changes.length }} 条记录</el-tag>
    </div>

    <el-card shadow="never" class="admin-card">
      <el-timeline v-if="changes.length" class="admin-event-timeline"><el-timeline-item v-for="change in changes" :key="change.id" :timestamp="formatDate(change.detectedAt)" :type="severityType(change.severity)" placement="top"><el-card shadow="never" class="admin-event-card"><div class="admin-event-head"><el-tag :type="severityType(change.severity)" effect="light">{{ changeLabel(change.type) }}</el-tag><strong>{{ change.entity }}</strong><span v-if="change.field">{{ change.field }}</span></div><p>{{ change.message }}</p><code class="admin-diff">{{ change.oldValue }} → {{ change.newValue }}</code></el-card></el-timeline-item></el-timeline>
      <el-empty v-else description="暂无变更记录" />
    </el-card>
  </section>
</template>

<script setup lang="ts">
import type { ChangeEvent } from '../types'

defineProps<{ changes: ChangeEvent[] }>()

function changeLabel(type: string) {
  return ({ GROUP_ADDED: '渠道新增', GROUP_REMOVED: '渠道下线', RATE_CHANGED: '倍率变化', BASE_RATE_CHANGED: '基础倍率变化', STATUS_CHANGED: '状态变化', GROUP_UPDATED: '渠道更新' } as Record<string, string>)[type] || type
}

function severityType(severity: ChangeEvent['severity']) {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'WARNING') return 'warning'
  return 'success'
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN')
}
</script>