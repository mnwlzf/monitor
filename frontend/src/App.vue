<template>
  <div class="monitor-shell">
    <aside class="monitor-sidebar">
      <div class="monitor-brand">
        <span>M</span>
        <div><strong>Monitor</strong><small>Upstream Console</small></div>
      </div>
      <nav class="monitor-nav">
        <button v-for="item in navItems" :key="item.id" :class="{ active: currentPage === item.id }" @click="currentPage = item.id">
          <span>{{ item.icon }}</span>{{ item.label }}
        </button>
      </nav>
      <div class="monitor-sidebar-foot">
        <span class="monitor-status online">系统运行中</span>
        <small>最近刷新 {{ lastUpdated }}</small>
      </div>
    </aside>

    <main class="monitor-main">
      <header class="monitor-topbar">
        <div>
          <strong>{{ activeNav.label }}</strong>
          <span>数据采集与上游监控</span>
        </div>
        <div class="monitor-topbar-actions">
          <span class="monitor-sync-state"><i></i>自动采集已开启</span>
          <button class="monitor-refresh" @click="refresh">刷新数据</button>
        </div>
      </header>

      <OverviewView
        v-if="currentPage === 'overview'"
        :accounts="accountList"
        :platforms="platforms"
        :channels="channels"
        :changes="changes"
        :series="selectedSeries"
        :selected-account-id="selectedAccountId"
        @select-account="selectedAccountId = $event"
      />
      <PlatformsView v-else-if="currentPage === 'platforms'" :platforms="platforms" />
      <AccountsView v-else-if="currentPage === 'accounts'" :accounts="accountList" @collect="collectAccount" @created="handleAccountCreated" />
      <ChangesView v-else :changes="changes" />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import OverviewView from './views/OverviewView.vue'
import PlatformsView from './views/PlatformsView.vue'
import AccountsView from './views/AccountsView.vue'
import ChangesView from './views/ChangesView.vue'
import { accounts as mockAccounts, channels, changes, metricSeries, platforms } from './data/mock'
import type { Account } from './types'

type PageKey = 'overview' | 'platforms' | 'accounts' | 'changes'

const navItems: Array<{ id: PageKey; label: string; icon: string }> = [
  { id: 'overview', label: '总览', icon: '◒' },
  { id: 'platforms', label: '平台', icon: '▣' },
  { id: 'accounts', label: '账号', icon: '◎' },
  { id: 'changes', label: '变更记录', icon: '↯' },
]

const currentPage = ref<PageKey>('overview')
const selectedAccountId = ref(mockAccounts[0]?.id ?? 0)
const accountList = ref<Account[]>(mockAccounts.map(account => ({ ...account })))
const lastUpdated = ref(formatTime(new Date()))

const activeNav = computed(() => navItems.find(item => item.id === currentPage.value) ?? navItems[0])
const selectedSeries = computed(() => metricSeries[selectedAccountId.value] ?? [])

function refresh() {
  lastUpdated.value = formatTime(new Date())
}

function handleAccountCreated(account: Account) {
  accountList.value.unshift(account)
  selectedAccountId.value = account.id
  lastUpdated.value = formatTime(new Date())
}

function collectAccount(account: Account) {
  const target = accountList.value.find(item => item.id === account.id)
  if (!target || target.lastCollectStatus === 'RUNNING') return
  target.lastCollectStatus = 'RUNNING'
  window.setTimeout(() => {
    target.lastCollectStatus = 'SUCCESS'
    target.lastCollectedAt = new Date().toISOString()
    target.nextCollectAt = new Date(Date.now() + 10 * 60 * 1000).toISOString()
    lastUpdated.value = formatTime(new Date())
  }, 1200)
}

function formatTime(value: Date) {
  return value.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
</script>