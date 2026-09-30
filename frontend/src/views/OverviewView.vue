<template>
  <section class="monitor-page">
    <div class="monitor-page-heading">
      <div>
        <p class="monitor-eyebrow">OVERVIEW</p>
        <h1>运行总览</h1>
        <p>账号余额、额度消耗和渠道变更的整体状态。</p>
      </div>
      <div class="monitor-account-picker">
        <label>观察账号</label>
        <select :value="selectedAccountId" @change="emit('select-account', Number(($event.target as HTMLSelectElement).value))">
          <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.displayName }}</option>
        </select>
      </div>
    </div>

    <div class="monitor-metric-grid">
      <MetricCard label="平台数量" :value="String(platforms.length)" hint="启用中的上游平台" tone="neutral" />
      <MetricCard label="账号数量" :value="String(accounts.length)" hint="已纳管采集账号" tone="positive" />
      <MetricCard label="当前余额" :value="formatMoney(totalBalance)" hint="所有账号折算余额" tone="positive" />
      <MetricCard label="今日变更" :value="String(changes.length)" hint="渠道和倍率变更事件" tone="warning" />
    </div>

    <div class="monitor-grid-two">
      <article class="monitor-panel monitor-panel-large">
        <header class="monitor-panel-header">
          <div><h2>余额与消耗趋势</h2><p>最近 7 个采集周期</p></div>
          <span class="monitor-live-dot">实时快照</span>
        </header>
        <LineChart :points="series" />
      </article>
      <article class="monitor-panel">
        <header class="monitor-panel-header">
          <div><h2>渠道倍率</h2><p>当前生效倍率对比</p></div>
        </header>
        <RatioBars :channels="channels.slice(0, 6)" />
      </article>
    </div>

    <article class="monitor-panel">
      <header class="monitor-panel-header">
        <div><h2>最近变更</h2><p>渠道新增、减少和倍率变化</p></div>
      </header>
      <div class="monitor-change-list">
        <div v-for="change in changes.slice(0, 5)" :key="change.id" class="monitor-change-item">
          <span class="monitor-change-icon" :class="change.severity.toLowerCase()"></span>
          <div><strong>{{ change.entity }}</strong><p>{{ change.message }}</p></div>
          <code>{{ change.oldValue }} → {{ change.newValue }}</code>
          <time>{{ formatDate(change.detectedAt) }}</time>
        </div>
      </div>
    </article>
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