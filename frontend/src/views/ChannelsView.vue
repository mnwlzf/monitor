<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div><p class="admin-eyebrow">CHANNELS</p><h2>渠道监控</h2><p>按平台实例查看渠道倍率、账号归属和当前状态。</p></div>
      <el-tag effect="plain">{{ channels.length }} 个渠道</el-tag>
    </div>

    <el-card shadow="never" class="admin-card admin-toolbar-card">
      <div class="admin-toolbar">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索渠道名称" />
        <el-select v-model="instanceFilter" clearable placeholder="全部平台实例">
          <el-option v-for="name in platformNames" :key="name" :label="name" :value="name" />
        </el-select>
        <el-select v-model="providerFilter" clearable placeholder="全部上游类型">
          <el-option v-for="provider in providers" :key="provider" :label="provider" :value="provider" />
        </el-select>
        <el-select v-model="statusFilter" clearable placeholder="全部状态">
          <el-option label="可用" value="active" />
          <el-option label="停用" value="inactive" />
        </el-select>
      </div>
    </el-card>

    <div v-if="groupedChannels.length" class="admin-channel-groups">
      <section v-for="group in groupedChannels" :key="group.platformId" class="admin-channel-group">
        <div class="admin-channel-group-head">
          <el-tag size="small" :type="group.platformType === 'newapi' ? 'primary' : 'success'" effect="dark">{{ group.platformType }}</el-tag>
          <strong>{{ group.platformName }}</strong>
          <span>{{ group.channels.length }} 个渠道</span>
        </div>
        <div class="admin-channel-grid">
          <el-card v-for="channel in group.channels" :key="channel.id" shadow="hover" class="admin-channel-card">
            <div class="admin-channel-head">
              <div><h3>{{ channel.name }}</h3><p>{{ channel.accountName }}</p></div>
              <el-tag size="small" :type="channel.status.toLowerCase() === 'active' ? 'success' : 'info'" effect="light">{{ channel.status }}</el-tag>
            </div>
            <div class="admin-channel-context">
              <span>{{ channel.platformName }}</span>
              <i></i>
              <span>{{ channel.platform || 'unknown' }}</span>
            </div>
            <div class="admin-channel-ratio">
              <div><span>当前</span><strong>{{ channel.ratio.toFixed(2) }}</strong></div>
              <div><span>基础</span><strong>{{ channel.baseRatio == null ? '—' : channel.baseRatio.toFixed(2) }}</strong></div>
            </div>
            <div class="admin-channel-foot"><span>ID {{ channel.id }}</span><span>{{ channel.accountName }}</span></div>
          </el-card>
        </div>
      </section>
    </div>
    <el-card v-else shadow="never" class="admin-card"><el-empty description="暂无渠道数据" /></el-card>
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
const filteredChannels = computed(() => props.channels.filter(channel => {
  const keywordMatched = !keyword.value || channel.name.toLowerCase().includes(keyword.value.toLowerCase())
  const instanceMatched = !instanceFilter.value || channel.platformName === instanceFilter.value
  const providerMatched = !providerFilter.value || channel.platform === providerFilter.value
  const statusMatched = !statusFilter.value || channel.status.toLowerCase() === statusFilter.value
  return keywordMatched && instanceMatched && providerMatched && statusMatched
}))
const groupedChannels = computed(() => {
  const groups = new Map<string, { platformId: number; platformName: string; platformType: Channel['platformType']; channels: Channel[] }>()
  for (const channel of filteredChannels.value) {
    const key = String(channel.platformId)
    if (!groups.has(key)) groups.set(key, { platformId: channel.platformId, platformName: channel.platformName, platformType: channel.platformType, channels: [] })
    groups.get(key)!.channels.push(channel)
  }
  return [...groups.values()]
})
</script>
