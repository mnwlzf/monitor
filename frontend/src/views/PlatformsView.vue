<template>
  <section class="monitor-page">
    <div class="monitor-page-heading">
      <div><p class="monitor-eyebrow">PLATFORMS</p><h1>平台管理</h1><p>平台地址、适配器类型和采集状态。</p></div>
    </div>

    <el-row :gutter="16">
      <el-col v-for="platform in platforms" :key="platform.id" :xs="24" :md="12" :xl="8">
        <el-card shadow="hover" class="monitor-platform-card">
          <div class="monitor-platform-head">
            <span class="monitor-platform-icon" :class="platform.type">{{ platform.type === 'newapi' ? 'NA' : 'S2' }}</span>
            <el-tag :type="platform.status ? 'success' : 'info'" effect="light" round>{{ platform.status ? '启用' : '停用' }}</el-tag>
          </div>
          <h2>{{ platform.name }}</h2>
          <el-text class="monitor-url" truncated>{{ platform.url }}</el-text>
          <el-descriptions :column="1" border class="monitor-platform-descriptions">
            <el-descriptions-item label="适配器">{{ platform.type }}</el-descriptions-item>
            <el-descriptions-item label="账号数">{{ platform.accountCount }}</el-descriptions-item>
            <el-descriptions-item label="最近采集">{{ platform.lastCollectedAt ? formatDate(platform.lastCollectedAt) : '暂无' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </section>
</template>

<script setup lang="ts">
import type { Platform } from '../types'

defineProps<{ platforms: Platform[] }>()

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>