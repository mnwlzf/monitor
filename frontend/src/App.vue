<template>
  <el-container class="monitor-shell">
    <el-aside width="230px" class="monitor-sidebar">
      <div class="monitor-brand">
        <span>M</span>
        <div><strong>Monitor</strong><small>Upstream Console</small></div>
      </div>

      <el-menu :default-active="currentPage" class="monitor-menu" @select="selectPage">
        <el-menu-item v-for="item in navItems" :key="item.id" :index="item.id">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>

      <div class="monitor-sidebar-foot">
        <el-tag type="success" effect="light" round>系统运行中</el-tag>
        <small>最近刷新 {{ lastUpdated }}</small>
      </div>
    </el-aside>

    <el-container>
      <el-header class="monitor-topbar">
        <div>
          <h2>{{ activeNav.label }}</h2>
          <p>数据采集与上游监控</p>
        </div>
        <div class="monitor-topbar-actions">
          <el-tag type="success" effect="plain" round>自动采集已开启</el-tag>
          <el-button :icon="Refresh" @click="refresh">刷新数据</el-button>
        </div>
      </el-header>

      <el-main class="monitor-main">
        <OverviewView
          v-if="currentPage === 'overview'"
          :accounts="accountList"
          :platforms="platformList"
          :channels="channels"
          :changes="changes"
          :series="selectedSeries"
          :selected-account-id="selectedAccountId"
          @select-account="selectedAccountId = $event"
        />
        <PlatformsView v-else-if="currentPage === 'platforms'" :platforms="platformList" />
        <AccountsView v-else-if="currentPage === 'accounts'" :accounts="accountList" @collect="collectAccount" @saved="handleAccountSaved" @deleted="handleAccountDeleted" />
        <ChangesView v-else :changes="changes" />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, markRaw, onMounted, ref, type Component } from 'vue'
import { DataAnalysis, Monitor, Refresh, User, Bell } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import OverviewView from './views/OverviewView.vue'
import PlatformsView from './views/PlatformsView.vue'
import AccountsView from './views/AccountsView.vue'
import ChangesView from './views/ChangesView.vue'
import { listAccountRecords, listPlatformRecords } from './api/accounts'
import { accounts as mockAccounts, channels, changes, metricSeries, platforms as mockPlatforms } from './data/mock'
import type { Account, Platform } from './types'

type PageKey = 'overview' | 'platforms' | 'accounts' | 'changes'

const navItems: Array<{ id: PageKey; label: string; icon: Component }> = [
  { id: 'overview', label: '总览', icon: markRaw(DataAnalysis) },
  { id: 'platforms', label: '平台', icon: markRaw(Monitor) },
  { id: 'accounts', label: '账号', icon: markRaw(User) },
  { id: 'changes', label: '变更记录', icon: markRaw(Bell) },
]

const currentPage = ref<PageKey>('overview')
const selectedAccountId = ref(0)
const platformList = ref<Platform[]>([])
const accountList = ref<Account[]>([])
const lastUpdated = ref(formatTime(new Date()))

const activeNav = computed(() => navItems.find(item => item.id === currentPage.value) ?? navItems[0])
const selectedSeries = computed(() => {
  const series = metricSeries[selectedAccountId.value]
  if (series) return series
  const fallbackId = accountList.value[0]?.id
  return fallbackId ? metricSeries[fallbackId] ?? [] : []
})

function selectPage(index: string) {
  currentPage.value = index as PageKey
}

async function refresh() {
  await loadRemoteData()
  ElMessage.success('数据已刷新')
}

async function loadRemoteData() {
  try {
    platformList.value = await listPlatformRecords()
    const accountGroups = await Promise.all(platformList.value.map(platform => listAccountRecords(platform)))
    accountList.value = accountGroups.flat()
    selectedAccountId.value = accountList.value[0]?.id ?? 0
    lastUpdated.value = formatTime(new Date())
  } catch (error) {
    platformList.value = mockPlatforms.map(platform => ({ ...platform }))
    accountList.value = mockAccounts.map(account => ({ ...account }))
    selectedAccountId.value = accountList.value[0]?.id ?? 0
    ElMessage.warning(error instanceof Error ? `${error.message}，已切换演示数据` : '后端数据加载失败，已切换演示数据')
  }
}

function handleAccountSaved(account: Account) {
  const index = accountList.value.findIndex(item => item.id === account.id)
  if (index >= 0) accountList.value[index] = account
  else accountList.value.unshift(account)
  selectedAccountId.value = account.id
  lastUpdated.value = formatTime(new Date())
}

function handleAccountDeleted(accountId: number) {
  accountList.value = accountList.value.filter(account => account.id !== accountId)
  if (selectedAccountId.value === accountId) selectedAccountId.value = accountList.value[0]?.id ?? 0
  lastUpdated.value = formatTime(new Date())
}

function collectAccount(account: Account) {
  const target = accountList.value.find(item => item.id === account.id)
  if (!target || target.lastCollectStatus === 'RUNNING') return
  target.lastCollectStatus = 'RUNNING'
  ElMessage.info(`账号「${target.displayName}」已开始采集`)
  window.setTimeout(() => {
    target.lastCollectStatus = 'SUCCESS'
    target.lastCollectedAt = new Date().toISOString()
    target.nextCollectAt = new Date(Date.now() + 10 * 60 * 1000).toISOString()
    lastUpdated.value = formatTime(new Date())
    ElMessage.success(`账号「${target.displayName}」采集完成`)
  }, 1200)
}

function formatTime(value: Date) {
  return value.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

onMounted(loadRemoteData)
</script>