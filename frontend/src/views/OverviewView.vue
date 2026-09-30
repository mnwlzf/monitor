<template>
  <section class="monitor-page">
    <div class="monitor-page-heading">
      <div>
        <p class="monitor-eyebrow">OVERVIEW</p>
        <h1>运行总览</h1>
        <p>账号余额、额度消耗和渠道变更的整体状态。</p>
      </div>
      <el-select :model-value="selectedAccountId" class="monitor-account-picker" @change="emit('select-account', Number($event))">
        <template #prefix>观察账号</template>
        <el-option v-for="account in accounts" :key="account.id" :label="account.displayName" :value="account.id" />
      </el-select>
    </div>

    <el-row :gutter="16" class="monitor-metric-grid">
      <el-col :xs="12" :sm="6"><MetricCard label="平台数量" :value="String(platforms.length)" hint="启用中的上游平台" tone="neutral" /></el-col>
      <el-col :xs="12" :sm="6"><MetricCard label="账号数量" :value="String(accounts.length)" hint="已纳管采集账号" tone="positive" /></el-col>
      <el-col :xs="12" :sm="6"><MetricCard label="当前余额" :value="formatMoney(totalBalance)" hint="所有账号折算余额" tone="positive" /></el-col>
      <el-col :xs="12" :sm="6"><MetricCard label="今日变更" :value="String(changes.length)" hint="渠道和倍率变更事件" tone="warning" /></el-col>
    </el-row>

    <el-row :gutter="18" class="monitor-grid-two">
      <el-col :xs="24" :lg="16">
        <el-card shadow="never" class="monitor-panel monitor-panel-large">
          <template #header>
            <div class="monitor-panel-header">
              <div><h2>余额与消耗趋势</h2><p>最近 7 个采集周期</p></div>
              <el-tag type="success" effect="light" round>实时快照</el-tag>
            </div>
          </template>
          <LineChart :points="series" />
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="8">
        <el-card shadow="never" class="monitor-panel">
          <template #header>
            <div class="monitor-panel-header"><div><h2>渠道倍率</h2><p>当前生效倍率对比</p></div></div>
          </template>
          <RatioBars :channels="channels.slice(0, 6)" />
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="monitor-panel">
      <template #header>
        <div class="monitor-panel-header"><div><h2>最近变更</h2><p>渠道新增、减少和倍率变化</p></div></div>
      </template>
      <el-timeline class="monitor-timeline">
        <el-timeline-item
          v-for="change in changes.slice(0, 5)"
          :key="change.id"
          :timestamp="formatDate(change.detectedAt)"
          :type="change.severity === 'WARNING' ? 'warning' : change.severity === 'CRITICAL' ? 'danger' : 'success'"
        >
          <div class="monitor-change-item">
            <div><strong>{{ change.entity }}</strong><p>{{ change.message }}</p></div>
            <code>{{ change.oldValue }} → {{ change.newValue }}</code>
          </div>
        </el-timeline-item>
      </el-timeline>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MetricCard from '../components/MetricCard.vue'
import LineChart from '../components/LineChart.vue'
import RatioBars from '../components/RatioBars.vue'
import type { Account, ChangeEvent, Channel, MetricPoint, Platform } from '../types'

const props = defineProps<{
  accounts: Account[]
  platforms: Platform[]
  channels: Channel[]
  changes: ChangeEvent[]
  series: MetricPoint[]
  selectedAccountId: number
}>()
const emit = defineEmits<{ 'select-account': [id: number] }>()

const totalBalance = computed(() => props.accounts.reduce((sum, account) => sum + account.balance, 0))

function formatMoney(value: number) {
  return `$${value.toFixed(2)}`
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>