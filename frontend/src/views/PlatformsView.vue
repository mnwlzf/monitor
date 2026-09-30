<template>
  <section class="monitor-page">
    <div class="monitor-page-heading">
      <div><p class="monitor-eyebrow">PLATFORMS</p><h1>平台管理</h1><p>平台地址、适配器类型和采集状态。</p></div>
    </div>
    <div class="monitor-platform-grid">
      <article v-for="platform in platforms" :key="platform.id" class="monitor-platform-card">
        <div class="monitor-platform-head">
          <span class="monitor-platform-icon" :class="platform.type">{{ platform.type === 'newapi' ? 'NA' : 'S2' }}</span>
          <span class="monitor-status" :class="{ online: platform.status }">{{ platform.status ? '启用' : '停用' }}</span>
        </div>
        <h2>{{ platform.name }}</h2>
        <code>{{ platform.url }}</code>
        <dl>
          <div><dt>适配器</dt><dd>{{ platform.type }}</dd></div>
          <div><dt>账号数</dt><dd>{{ platform.accountCount }}</dd></div>
          <div><dt>最近采集</dt><dd>{{ platform.lastCollectedAt ? formatDate(platform.lastCollectedAt) : '暂无' }}</dd></div>
        </dl>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { Platform } from '../types'

defineProps<{ platforms: Platform[] }>()

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>