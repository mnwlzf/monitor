<template>
  <section class="monitor-page">
    <div class="monitor-page-heading">
      <div><p class="monitor-eyebrow">CHANGES</p><h1>变更记录</h1><p>渠道新增、减少、倍率和状态变化。</p></div>
    </div>
    <article class="monitor-panel">
      <div class="monitor-timeline">
        <div v-for="change in changes" :key="change.id" class="monitor-timeline-item">
          <span class="monitor-timeline-dot" :class="change.severity.toLowerCase()"></span>
          <div class="monitor-timeline-copy">
            <div><strong>{{ changeLabel(change.type) }}</strong><code>{{ change.entity }}</code></div>
            <p>{{ change.message }}</p>
            <small>{{ formatDate(change.detectedAt) }}</small>
          </div>
          <div class="monitor-diff"><span>{{ change.oldValue }}</span><b>→</b><em>{{ change.newValue }}</em></div>
        </div>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import type { ChangeEvent } from '../types'

defineProps<{ changes: ChangeEvent[] }>()

function changeLabel(type: string) {
  return ({ GROUP_ADDED: '渠道新增', GROUP_REMOVED: '渠道下线', RATE_CHANGED: '倍率变化', BASE_RATE_CHANGED: '基础倍率变化', STATUS_CHANGED: '状态变化', GROUP_UPDATED: '渠道更新' } as Record<string, string>)[type] || type
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN')
}
</script>