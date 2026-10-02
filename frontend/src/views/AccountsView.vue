<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div>
        <p class="admin-eyebrow">ACCOUNTS</p>
        <h2>账号管理</h2>
        <p>账号余额、额度消耗、凭证状态和采集健康度。</p>
      </div>
      <div class="admin-page-heading-actions">
        <el-tooltip content="请先在上方选择要添加账号的平台" placement="bottom" :disabled="Boolean(activePlatform)">
          <span><el-button type="primary" :icon="Plus" :disabled="!activePlatform" @click="openCreate()">添加账号</el-button></span>
        </el-tooltip>
        <el-button :icon="Monitor" @click="emit('manage-platforms')">平台管理</el-button>
      </div>
    </div>

    <!-- 第一步：选择平台（平台在「平台管理」单独添加） -->
    <el-card shadow="never" class="admin-card admin-platform-selector-card">
      <div class="admin-platform-selector-head">
        <div>
          <h3>第一步 · 选择平台</h3>
          <p>平台在上方「平台管理」中单独添加；选中平台后即可为其挂载一个或多个账号。</p>
        </div>
        <el-button size="small" :icon="Plus" @click="emit('manage-platforms')">新增平台</el-button>
      </div>
      <div v-if="platforms.length" class="admin-platform-selector">
        <button
          type="button"
          class="admin-platform-chip"
          :class="{ active: selectedPlatformId === 0 }"
          @click="selectPlatform(0)"
        >
          <span class="admin-platform-chip-name">全部平台</span>
          <span class="admin-platform-chip-meta">{{ accounts.length }} 个账号</span>
        </button>
        <button
          v-for="platform in platforms"
          :key="platform.id"
          type="button"
          class="admin-platform-chip"
          :class="{ active: selectedPlatformId === platform.id }"
          @click="selectPlatform(platform.id)"
        >
          <span class="admin-platform-chip-name">{{ platform.name }}</span>
          <span class="admin-platform-chip-meta">
            <el-tag size="small" :type="platform.type === 'newapi' ? 'primary' : 'success'" effect="plain">{{ typeLabel(platform.type) }}</el-tag>
            {{ countAccounts(platform.id) }} 个账号
          </span>
        </button>
      </div>
      <el-empty v-else description="还没有平台，请先到「平台管理」添加 Sub2API 或 New API 平台" :image-size="70" />
    </el-card>

    <!-- 第二步：对所选平台添加账号 -->
    <el-card v-if="activePlatform" shadow="never" class="admin-card admin-platform-focus-card">
      <div class="admin-platform-focus">
        <div class="admin-platform-focus-main">
          <span class="admin-platform-icon" :class="activePlatform.type">{{ activePlatform.type === 'newapi' ? 'NA' : 'S2' }}</span>
          <div class="admin-platform-focus-text">
            <h3>{{ activePlatform.name }}</h3>
            <el-text truncated class="admin-url">{{ activePlatform.url }}</el-text>
          </div>
        </div>
        <div class="admin-platform-focus-actions">
          <span class="admin-platform-focus-count">{{ countAccounts(activePlatform.id) }} 个账号</span>
          <el-button type="primary" :icon="Plus" @click="openCreate()">为该平台添加账号</el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="admin-card admin-toolbar-card">
      <div class="admin-toolbar admin-toolbar-compact">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索账号或平台" />
        <el-select v-model="statusFilter" clearable placeholder="全部状态">
          <el-option label="采集成功" value="SUCCESS" />
          <el-option label="采集失败" value="FAILED" />
          <el-option label="采集中" value="RUNNING" />
          <el-option label="未知状态" value="UNKNOWN" />
        </el-select>
        <el-button :icon="Refresh" @click="emit('refresh')">刷新</el-button>
      </div>
    </el-card>

    <section v-if="filteredAccounts.length" class="admin-account-rows">
      <article v-for="account in filteredAccounts" :key="account.id" class="admin-account-entry">
        <div class="admin-account-col identity">
          <span class="admin-account-avatar">{{ account.displayName.slice(0, 1) }}</span>
          <div class="admin-account-col-text">
            <strong>{{ account.displayName }}</strong>
            <small>{{ account.loginName || '未填写登录账号' }}</small>
            <div class="admin-account-badges">
              <el-tag size="small" :type="account.platformType === 'newapi' ? 'primary' : 'success'" effect="plain">{{ account.platformName }}</el-tag>
              <el-tag size="small" type="info" effect="plain">{{ credentialLabel(account.credentialStatus) }}</el-tag>
            </div>
          </div>
        </div>

        <div class="admin-account-col">
          <span>余额</span>
          <strong class="positive">{{ formatMoney(account.balance) }}</strong>
          <small>{{ account.platformType === 'sub2api' ? `累计消耗 ${formatMoney(usageOf(account)?.totalCost)}` : `剩余额度 ${formatNumberOrDash(account.quota)}` }}</small>
        </div>

        <div class="admin-account-col usage">
          <template v-if="account.platformType === 'sub2api'">
            <span>今日消耗 / Token</span>
            <strong>{{ formatMoney(metricNumber(usageOf(account), 'today_actual_cost')) }} · {{ formatNumberOrDash(metricNumber(usageOf(account), 'today_tokens')) }}</strong>
            <small>累计请求 {{ formatNumberOrDash(usageOf(account)?.totalRequests) }}</small>
          </template>
          <template v-else>
            <span>额度消耗 {{ usagePercent(account) }}%</span>
            <el-progress :percentage="usagePercent(account)" :stroke-width="8" :show-text="false" :status="usagePercent(account) > 80 ? 'exception' : 'success'" />
            <small>已用 {{ formatNumberOrDash(account.usedQuota) }} · 请求 {{ formatNumberOrDash(account.requestCount) }}</small>
          </template>
        </div>

        <div class="admin-account-col status">
          <span>采集状态</span>
          <el-tag :type="statusType(account.lastCollectStatus)" effect="light" round>{{ statusLabel(account.lastCollectStatus) }}</el-tag>
        </div>

        <div class="admin-account-col timing">
          <span>采集时间</span>
          <strong>最近 {{ account.lastCollectedAt ? formatDate(account.lastCollectedAt) : '暂无' }}</strong>
          <small>下次 {{ account.nextCollectAt ? formatDate(account.nextCollectAt) : '待调度' }}</small>
        </div>

        <div class="admin-account-col actions">
          <el-button size="small" :loading="account.lastCollectStatus === 'RUNNING'" @click="emit('collect', account)">立即采集</el-button>
          <el-button size="small" type="primary" plain @click="openEdit(account)">编辑</el-button>
          <el-popconfirm title="确认删除该账号？" @confirm="remove(account)">
            <template #reference><el-button size="small" type="danger" plain>删除</el-button></template>
          </el-popconfirm>
        </div>
      </article>
    </section>
    <el-card v-else shadow="never" class="admin-card"><el-empty :description="emptyText" /></el-card>

    <el-dialog v-model="showForm" :title="editingAccount ? '编辑采集账号' : '新增采集账号'" width="640px" destroy-on-close>
      <el-form label-position="top" class="admin-form-grid">
        <el-form-item label="所属平台" required class="admin-form-full">
          <el-select
            v-model="form.platformId"
            :disabled="Boolean(editingAccount)"
            :placeholder="platforms.length ? '选择账号所属平台' : '暂无可用平台'"
            style="width: 100%"
          >
            <el-option v-for="platform in platforms" :key="platform.id" :label="`${platform.name}（${platform.type === 'newapi' ? 'New API' : 'Sub2API'}）`" :value="platform.id" />
          </el-select>
          <p class="admin-form-hint">{{ platformHint }}</p>
        </el-form-item>
        <el-form-item label="登录账号" required>
          <el-input v-model="form.loginName" placeholder="邮箱或用户名" />
        </el-form-item>
        <el-form-item :label="editingAccount ? '登录密码（留空不修改）' : '登录密码'" :required="!editingAccount">
          <el-input v-model="form.password" type="password" show-password placeholder="登录密码" />
        </el-form-item>
        <el-form-item label="显示名称" class="admin-form-full">
          <el-input v-model="form.displayName" placeholder="留空则使用登录账号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showForm = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!editingAccount && !platforms.length" @click="submit">保存账号</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Monitor, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { createAccountRecord, deleteAccountRecord, updateAccountRecord, type CreateAccountInput, type UpdateAccountInput } from '../api/accounts'
import type { Account, Platform, UsageDashboard } from '../types'

const props = defineProps<{ accounts: Account[]; platforms: Platform[]; usageDashboards: UsageDashboard[] }>()
const emit = defineEmits<{ collect: [account: Account]; saved: [account: Account]; deleted: [accountId: number]; refresh: []; 'manage-platforms': [] }>()

const keyword = ref('')
const selectedPlatformId = ref(0)
const statusFilter = ref('')
const showForm = ref(false)
const saving = ref(false)
const editingAccount = ref<Account | null>(null)
const form = reactive<{ platformId: number | null } & CreateAccountInput>({ platformId: null, displayName: '', loginName: '', password: '' })

const selectedPlatform = computed(() => props.platforms.find(platform => platform.id === form.platformId) ?? null)
const platformHint = computed(() => {
  if (editingAccount.value) return '平台在创建账号后不可变更；需要换平台请删除后重新添加。'
  if (!props.platforms.length) return '还没有平台实例，请先到「平台管理」创建 Sub2API 或 New API 平台。'
  const target = selectedPlatform.value
  if (!target) return '同一平台可以添加多个账号，按平台登录凭证分别填写。'
  return `「${target.name}」当前已有 ${countAccounts(target.id)} 个账号，该平台可继续添加多个账号。`
})

/** 当前聚焦的平台；为 null 表示「全部平台」。 */
const activePlatform = computed(() => props.platforms.find(platform => platform.id === selectedPlatformId.value) ?? null)

const emptyText = computed(() => {
  if (!props.platforms.length) return '还没有平台，请先到「平台管理」添加平台'
  if (activePlatform.value) return `平台「${activePlatform.value.name}」下还没有账号，点击上方「为该平台添加账号」`
  return '暂无账号数据'
})

const filteredAccounts = computed(() => props.accounts.filter(account => {
  const keywordMatched = !keyword.value || `${account.displayName} ${account.loginName} ${account.platformName}`.toLowerCase().includes(keyword.value.toLowerCase())
  const platformMatched = !selectedPlatformId.value || account.platformId === selectedPlatformId.value
  const statusMatched = !statusFilter.value || account.lastCollectStatus === statusFilter.value
  return keywordMatched && platformMatched && statusMatched
}))

/**
 * 切换平台：只筛选展示，并同步新增账号表单的预选平台。
 * 平台本身不在此处创建，保证「平台单独添加」的流程。
 */
function selectPlatform(platformId: number) {
  selectedPlatformId.value = platformId
  if (platformId) form.platformId = platformId
}

function typeLabel(type: Platform['type']) {
  return type === 'newapi' ? 'New API' : 'Sub2API'
}

function countAccounts(platformId: number) {
  return props.accounts.filter(account => account.platformId === platformId).length
}

/**
 * 打开新增账号表单，可指定预选平台。
 * 供「平台管理 → 添加账号」跳转时复用，避免重复跳到空白表单。
 */
function openCreateForm(platformId?: number) {
  if (platformId) selectedPlatformId.value = platformId
  openCreate(platformId)
}

function openCreate(platformId?: number) {
  editingAccount.value = null
  Object.assign(form, {
    platformId: platformId ?? selectedPlatformId.value ?? props.platforms[0]?.id ?? null,
    displayName: '',
    loginName: '',
    password: '',
  })
  showForm.value = true
}

defineExpose({ openCreateForm })

watch(() => props.platforms, list => {
  if (selectedPlatformId.value && !list.some(platform => platform.id === selectedPlatformId.value)) {
    selectedPlatformId.value = 0
  }
}, { deep: true })

function openEdit(account: Account) {
  editingAccount.value = account
  Object.assign(form, {
    platformId: account.platformId,
    displayName: account.displayName,
    loginName: account.loginName,
    password: '',
  })
  showForm.value = true
}

async function submit() {
  if (editingAccount.value) {
    if (!form.loginName) {
      ElMessage.warning('请填写登录账号')
      return
    }
  } else if (!form.platformId || !form.loginName || !form.password) {
    ElMessage.warning('请选择所属平台并填写登录账号与密码')
    return
  }

  saving.value = true
  try {
    if (editingAccount.value) {
      const input: UpdateAccountInput = { displayName: form.displayName, loginName: form.loginName, password: form.password, authType: 'PASSWORD' }
      emit('saved', await updateAccountRecord(editingAccount.value, input))
      ElMessage.success('账号已更新')
    } else {
      const platform = props.platforms.find(item => item.id === form.platformId)
      if (!platform) {
        ElMessage.error('所选平台不存在，请刷新后重试')
        return
      }
      emit('saved', await createAccountRecord(platform, form))
      ElMessage.success(`账号已添加到「${platform.name}」`)
    }
    showForm.value = false
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '账号保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(account: Account) {
  try {
    await deleteAccountRecord(account)
    emit('deleted', account.id)
    ElMessage.success('账号已删除')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '账号删除失败')
  }
}

const usageMap = computed(() => new Map(props.usageDashboards.map(item => [item.accountId, item])))

function usageOf(account: Account): UsageDashboard | undefined {
  return usageMap.value.get(account.id)
}

function metricNumber(usage: UsageDashboard | undefined, key: string): number | null {
  const value = usage?.metrics?.[key]
  return typeof value === 'number' ? value : null
}

function formatMoney(value: number | null | undefined) {
  return value == null ? '—' : `$${Number(value).toFixed(2)}`
}

function formatNumberOrDash(value: number | null | undefined) {
  return value == null ? '—' : new Intl.NumberFormat('zh-CN').format(Number(value))
}

/**
 * 额度消耗率仅在 newapi 口径下有意义：sub2api 不返回额度字段，界面改为展示用量快照。
 */
function usagePercent(account: Account) {
  const total = Number(account.quota || 0) + Number(account.usedQuota || 0)
  if (!total) return 0
  return Math.min(100, Math.round((Number(account.usedQuota || 0) / total) * 100))
}

function statusType(status: Account['lastCollectStatus']) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'primary'
  return 'info'
}

const statusLabels: Record<Account['lastCollectStatus'], string> = {
  SUCCESS: '采集成功',
  FAILED: '采集失败',
  RUNNING: '采集中',
  PARTIAL: '部分成功',
  UNKNOWN: '未知状态',
}

function statusLabel(status: Account['lastCollectStatus']) {
  return statusLabels[status] ?? status
}

function credentialLabel(status: Account['credentialStatus']) {
  if (status === 'VALID') return '凭证有效'
  if (status === 'INVALID') return '凭证失效'
  return '凭证未知'
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>