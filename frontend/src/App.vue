<template>
  <LoginView v-if="authReady && !currentUser" @logged-in="handleLoggedIn" />
  <div v-else-if="!authReady" class="admin-boot">正在校验登录状态…</div>
  <el-container v-else class="admin-shell">
    <el-aside width="236px" class="admin-sidebar">
      <div class="admin-brand">
        <span class="admin-brand-mark">M</span>
        <div>
          <strong>Monitor</strong>
          <small>Upstream Control Center</small>
        </div>
      </div>

      <el-menu :default-active="currentPage" class="admin-menu" @select="selectPage">
        <el-menu-item v-for="item in visibleNavItems" :key="item.id" :index="item.id">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>

      <div class="admin-sidebar-status">
        <div class="admin-status-line"><i></i><span>采集服务运行中</span></div>
        <small>最近刷新 {{ lastUpdated }}</small>
      </div>
    </el-aside>

    <el-container class="admin-workspace">
      <el-header class="admin-header">
        <div class="admin-header-title">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>监控中心</el-breadcrumb-item>
            <el-breadcrumb-item>{{ activeNav.label }}</el-breadcrumb-item>
          </el-breadcrumb>
          <h1>{{ activeNav.label }}</h1>
          <p>{{ activeNav.description }}</p>
        </div>
        <div class="admin-header-actions">
          <el-tag type="success" effect="dark" round>自动采集已开启</el-tag>
          <el-button :icon="Refresh" @click="refresh">刷新数据</el-button>
          <el-tag v-if="currentUser" :type="isAdmin ? 'warning' : 'info'" effect="plain" round>
            {{ currentUser.username }} · {{ isAdmin ? '管理员' : '只读' }}
          </el-tag>
          <el-button :icon="SwitchButton" @click="handleLogout">退出</el-button>
        </div>
      </el-header>

      <el-main class="admin-main">
        <OverviewView
          v-if="currentPage === 'overview'"
          :accounts="accountList"
          :platforms="platformList"
          :channels="channels"
          :changes="changes"
          :series="selectedSeries"
          :usage-dashboards="usageDashboards"
          :selected-account-id="selectedAccountId"
          @select-account="selectedAccountId = $event"
        />
        <PlatformsView
          v-else-if="currentPage === 'platforms'"
          :platforms="platformList"
          :accounts="accountList"
          :usage-dashboards="usageDashboards"
          :can-write="isAdmin"
          @saved="handlePlatformSaved"
          @updated="handlePlatformUpdated"
          @deleted="handlePlatformDeleted"
          @add-account="startAddAccount"
          @view-accounts="startViewAccounts"
        />
        <AccountsView
          v-else-if="currentPage === 'accounts'"
          ref="accountsViewRef"
          :accounts="accountList"
          :platforms="platformList"
          :usage-dashboards="usageDashboards"
          :api-keys="apiKeys"
          :can-write="isAdmin"
          @collect="collectAccount"
          @saved="handleAccountSaved"
          @deleted="handleAccountDeleted"
          @refresh="refresh"
          @manage-platforms="selectPage('platforms')"
        />
        <ChannelsView v-else-if="currentPage === 'channels'" :channels="channels" />
        <ScheduledTasksView v-else-if="currentPage === 'schedules'" :can-write="isAdmin" />
        <SettingsView v-else-if="currentPage === 'settings'" :can-write="isAdmin" />
        <ChangesView v-else :changes="changes" :platforms="platformList" />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, markRaw, nextTick, onMounted, ref, type Component } from 'vue'
import { Bell, Connection, DataAnalysis, Monitor, Refresh, Setting, SwitchButton, Timer, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import OverviewView from './views/OverviewView.vue'
import PlatformsView from './views/PlatformsView.vue'
import AccountsView from './views/AccountsView.vue'
import ChangesView from './views/ChangesView.vue'
import ChannelsView from './views/ChannelsView.vue'
import ScheduledTasksView from './views/ScheduledTasksView.vue'
import SettingsView from './views/SettingsView.vue'
import LoginView from './views/LoginView.vue'
import { collectAccountRecord, listAccountRecords, listApiKeyRecords, listChangeRecords, listGroupRecords, listPlatformRecords, listUsageDashboardRecords } from './api/accounts'
import { fetchCurrentUser, logout as logoutRequest } from './api/auth'
import { isUnauthorized } from './api/client'
import type { Account, ApiKey, ChangeEvent, Channel, CurrentUser, MetricPoint, Platform, UsageDashboard } from './types'

type PageKey = 'overview' | 'platforms' | 'accounts' | 'channels' | 'changes' | 'schedules' | 'settings'

const navItems: Array<{ id: PageKey; label: string; description: string; icon: Component; adminOnly?: boolean }> = [
  { id: 'overview', label: '运行总览', description: '账号、余额、额度与渠道变化全景', icon: markRaw(DataAnalysis) },
  { id: 'platforms', label: '平台管理', description: '上游平台实例与采集配置', icon: markRaw(Monitor) },
  { id: 'accounts', label: '账号管理', description: '账号凭证、余额与采集状态', icon: markRaw(User) },
  { id: 'channels', label: '渠道监控', description: '渠道倍率、平台归属和当前状态', icon: markRaw(Connection) },
  { id: 'schedules', label: '定时任务', description: '页面管理任务类型、Cron 表达式和启用状态', icon: markRaw(Timer) },
  { id: 'changes', label: '变更记录', description: '渠道新增、减少、倍率和状态变化', icon: markRaw(Bell) },
  { id: 'settings', label: '系统设置', description: 'SMTP 邮件通知与系统配置', icon: markRaw(Setting), adminOnly: true },
]

const authReady = ref(false)
const currentUser = ref<CurrentUser | null>(null)
const currentPage = ref<PageKey>('overview')
const selectedAccountId = ref(0)
const accountsViewRef = ref<{ openCreateForm: (platformId?: number) => void } | null>(null)
const platformList = ref<Platform[]>([])
const accountList = ref<Account[]>([])
const channels = ref<Channel[]>([])
const changes = ref<ChangeEvent[]>([])
const metricSeries = ref<Record<number, MetricPoint[]>>({})
const usageDashboards = ref<UsageDashboard[]>([])
const apiKeys = ref<ApiKey[]>([])
const lastUpdated = ref(formatTime(new Date()))

const isAdmin = computed(() => currentUser.value?.admin === true)
const visibleNavItems = computed(() => navItems.filter(item => !item.adminOnly || isAdmin.value))
const activeNav = computed(() => navItems.find(item => item.id === currentPage.value) ?? navItems[0])
const selectedSeries = computed(() => metricSeries.value[selectedAccountId.value] ?? [])

function selectPage(index: string) {
  currentPage.value = index as PageKey
}

async function bootstrap() {
  try {
    currentUser.value = await fetchCurrentUser()
    await loadRemoteData()
  } catch (error) {
    if (!isUnauthorized(error)) {
      ElMessage.error(error instanceof Error ? error.message : '登录状态校验失败')
    }
    currentUser.value = null
  } finally {
    authReady.value = true
  }
}

async function handleLoggedIn(user: CurrentUser) {
  currentUser.value = user
  await loadRemoteData()
}

async function handleLogout() {
  try {
    await logoutRequest()
  } catch {
    // 退出失败也清理本地状态，强制回到登录页
  }
  currentUser.value = null
  platformList.value = []
  accountList.value = []
  channels.value = []
  changes.value = []
  usageDashboards.value = []
  selectedAccountId.value = 0
  ElMessage.success('已退出登录')
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
  const accountCountByPlatform = accountList.value.reduce<Record<number, number>>((acc, account) => {
    acc[account.platformId] = (acc[account.platformId] ?? 0) + 1
    return acc
  }, {})
  platformList.value = platformList.value.map(platform => ({
    ...platform,
    accountCount: accountCountByPlatform[platform.id] ?? 0,
    lastCollectedAt: accountList.value
      .filter(account => account.platformId === platform.id && account.lastCollectedAt)
      .map(account => account.lastCollectedAt as string)
      .sort()
      .at(-1) ?? null,
  }))
    const accountMap = new Map(accountList.value.map(account => [account.id, account]))
    const groupGroups = await Promise.all(platformList.value.map(platform => listGroupRecords(platform, accountMap)))
    channels.value = groupGroups.flat()
    const changeGroups = await Promise.all(platformList.value.map(platform => listChangeRecords(platform)))
    changes.value = changeGroups.flat().sort((a, b) => new Date(b.detectedAt).getTime() - new Date(a.detectedAt).getTime())
    const usageGroups = await Promise.all(platformList.value.map(platform => listUsageDashboardRecords(platform)))
    usageDashboards.value = usageGroups.flat()
    const apiKeyGroups = await Promise.all(platformList.value.map(platform => listApiKeyRecords(platform)))
    apiKeys.value = apiKeyGroups.flat()
    selectedAccountId.value = accountList.value[0]?.id ?? 0
    lastUpdated.value = formatTime(new Date())
  } catch (error) {
    if (isUnauthorized(error)) {
      currentUser.value = null
      return
    }
    platformList.value = []
    accountList.value = []
    usageDashboards.value = []
    selectedAccountId.value = 0
    ElMessage.error(error instanceof Error ? error.message : '后端数据加载失败')
  }
}

function handlePlatformSaved(platform: Platform) {
  // 提示文案由平台管理页统一给出，这里只同步本地状态，避免重复弹窗。
  platformList.value = [...platformList.value, platform]
}

function handlePlatformUpdated(platform: Platform) {
  const index = platformList.value.findIndex(item => item.id === platform.id)
  if (index >= 0) {
    platformList.value[index] = {
      ...platform,
      accountCount: platformList.value[index].accountCount,
      lastCollectedAt: platformList.value[index].lastCollectedAt,
    }
  } else {
    platformList.value = [...platformList.value, platform]
  }
  // 平台类型/URL 变更会同步到其下账号，账号列表需同步刷新冗余字段。
  accountList.value = accountList.value.map(account => account.platformId === platform.id
    ? { ...account, platformName: platform.name, platformType: platform.type }
    : account)
  // 变更记录冗余了平台名称，重命名后同步刷新，避免展示旧名称。
  changes.value = changes.value.map(change => change.platformId === platform.id
    ? { ...change, platformName: platform.name, platformType: platform.type }
    : change)
}

function handlePlatformDeleted(platformId: number) {
  // 提示文案由平台管理页统一给出，这里只同步本地状态，避免重复弹窗。
  platformList.value = platformList.value.filter(platform => platform.id !== platformId)
}

/**
 * 平台管理页发起「添加账号」：切到账号管理并预选该平台，
 * 保证账号始终挂在已有平台下，而不是顺带新建平台。
 */
function startAddAccount(platform: Platform) {
  currentPage.value = 'accounts'
  nextTick(() => accountsViewRef.value?.openCreateForm(platform.id))
}

function startViewAccounts(platform: Platform) {
  currentPage.value = 'accounts'
  nextTick(() => accountsViewRef.value?.openCreateForm(platform.id))
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

async function collectAccount(account: Account) {
  const target = accountList.value.find(item => item.id === account.id)
  if (!target || target.lastCollectStatus === 'RUNNING') return
  target.lastCollectStatus = 'RUNNING'
  ElMessage.info(`账号「${target.displayName}」已开始采集`)
  try {
    await collectAccountRecord(target)
    ElMessage.success(`账号「${target.displayName}」采集完成`)
    await loadRemoteData()
  } catch (error) {
    target.lastCollectStatus = 'FAILED'
    ElMessage.error(error instanceof Error ? error.message : '账号采集失败')
  }
}

function formatTime(value: Date) {
  return value.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

onMounted(bootstrap)
</script>
