<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div>
        <p class="admin-eyebrow">ACCOUNTS</p>
        <h2>账号管理</h2>
        <p>账号余额、额度消耗、凭证状态和采集健康度。</p>
      </div>
      <div class="admin-page-heading-actions">
        <el-tooltip content="请先在左侧选择要添加账号的平台" placement="bottom" :disabled="Boolean(selectedPlatformId)">
          <span v-if="canWrite !== false">
            <el-button type="primary" :icon="Plus" :disabled="!platforms.length" @click="openCreate()">添加账号</el-button>
          </span>
        </el-tooltip>
        <el-button :icon="Monitor" @click="emit('manage-platforms')">平台管理</el-button>
      </div>
    </div>

    <el-card shadow="never" class="admin-card admin-toolbar-card">
      <div class="admin-toolbar admin-toolbar-compact">
        <el-select v-model="selectedPlatformId" clearable placeholder="全部平台" class="admin-toolbar-select" @change="onPlatformChange">
          <el-option v-for="platform in platforms" :key="platform.id" :label="platform.name" :value="platform.id" />
        </el-select>
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索账号、登录名或平台" />
        <el-select v-model="statusFilter" clearable placeholder="全部状态" class="admin-toolbar-select">
          <el-option label="采集成功" value="SUCCESS" />
          <el-option label="采集失败" value="FAILED" />
          <el-option label="采集中" value="RUNNING" />
          <el-option label="未知状态" value="UNKNOWN" />
        </el-select>
        <el-button :icon="Refresh" @click="emit('refresh')">刷新</el-button>
      </div>
      <div class="admin-summary-line">
        <span>共 <strong>{{ filteredAccounts.length }}</strong> 个账号</span>
        <span class="ok">成功 {{ successCount }}</span>
        <span class="bad" v-if="failedCount">失败 {{ failedCount }}</span>
        <span v-if="selectedPlatformId" class="muted">已按平台筛选</span>
      </div>
    </el-card>

    <el-card shadow="never" class="admin-card admin-table-card">
      <el-table
        v-if="filteredAccounts.length"
        :data="sortedAccounts"
        row-key="id"
        stripe
        class="admin-table"
        :row-class-name="accountRowClass"
        @sort-change="onSortChange"
      >
        <el-table-column label="账号" min-width="200" fixed>
          <template #default="{ row }">
            <div class="admin-table-identity">
              <span class="admin-account-avatar">{{ row.displayName.slice(0, 1) }}</span>
              <div class="admin-table-identity-text">
                <strong>{{ row.displayName }}</strong>
                <small>{{ row.loginName || '未填写登录账号' }}</small>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="平台" min-width="200">
          <template #default="{ row }">
            <div class="admin-platform-cell-head">
              <el-tag size="small" :type="row.platformType === 'newapi' ? 'primary' : 'success'" effect="plain">{{ row.platformName }}</el-tag>
              <span v-if="groupOf(asAccount(row)).total > 1" class="admin-platform-group-count">
                {{ groupOf(asAccount(row)).index }}/{{ groupOf(asAccount(row)).total }}
              </span>
            </div>
            <div v-if="platformUrl(asAccount(row))" class="admin-platform-link">
              <a
                :href="platformUrl(asAccount(row))"
                target="_blank"
                rel="noopener noreferrer"
                :title="platformUrl(asAccount(row))"
              >{{ platformHost(asAccount(row)) }}</a>
            </div>
            <div class="admin-table-sub">
              <el-tag size="small" :type="row.credentialStatus === 'VALID' ? 'success' : row.credentialStatus === 'INVALID' ? 'danger' : 'info'" effect="light">
                {{ credentialLabel(row.credentialStatus) }}
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="balance" label="余额" min-width="110" sortable="custom" align="right">
          <template #default="{ row }">
            <strong class="admin-num positive">{{ formatMoney(row.balance) }}</strong>
          </template>
        </el-table-column>

        <el-table-column label="额度消耗" min-width="160">
          <template #default="{ row }">
            <template v-if="row.platformType === 'newapi' && (row.quota || row.usedQuota)">
              <el-progress :percentage="usagePercent(asAccount(row))" :stroke-width="6" :show-text="false" />
              <small class="admin-table-sub">{{ formatNumberOrDash(row.usedQuota) }} / {{ formatNumberOrDash(Number(row.quota || 0) + Number(row.usedQuota || 0)) }}</small>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="今日消耗" min-width="110" align="right">
          <template #default="{ row }">
            <strong class="admin-num cost">{{ formatMoney(metricNumber(usageOf(asAccount(row)), 'today_actual_cost')) }}</strong>
          </template>
        </el-table-column>

        <el-table-column prop="requestCount" label="请求数" min-width="110" sortable align="right">
          <template #default="{ row }">
            <span class="admin-num">{{ formatNumberOrDash(row.requestCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="采集状态" min-width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.lastCollectStatus)" effect="light" round>{{ statusLabel(row.lastCollectStatus) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="采集时间" min-width="170">
          <template #default="{ row }">
            <div class="admin-table-stack">
              <span>最近 {{ row.lastCollectedAt ? formatDate(row.lastCollectedAt) : '暂无' }}</span>
              <small>下次 {{ row.nextCollectAt ? formatDate(row.nextCollectAt) : '待调度' }}</small>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canWrite !== false" size="small" :loading="row.lastCollectStatus === 'RUNNING'" @click="emit('collect', asAccount(row))">采集</el-button>
            <el-button size="small" @click="openKeys(asAccount(row))">密钥{{ keyCount(asAccount(row)) ? `(${keyCount(asAccount(row))})` : '' }}</el-button>
            <el-button v-if="canWrite !== false" size="small" type="primary" plain @click="openEdit(asAccount(row))">编辑</el-button>
            <el-popconfirm v-if="canWrite !== false" title="确认删除该账号？" @confirm="remove(asAccount(row))">
              <template #reference><el-button size="small" type="danger" plain>删除</el-button></template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else :description="emptyText">
        <el-button v-if="!platforms.length" type="primary" @click="emit('manage-platforms')">去添加平台</el-button>
        <el-button v-else-if="canWrite !== false" type="primary" @click="openCreate()">添加账号</el-button>
      </el-empty>
    </el-card>

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

    <el-drawer v-model="showKeys" :title="keysTitle" size="900px" destroy-on-close>
      <el-table :data="currentKeys" row-key="id" stripe class="admin-table" empty-text="暂无密钥数据">
        <el-table-column label="名称" min-width="150">
          <template #default="{ row }">
            <strong>{{ row.keyName || '未命名' }}</strong>
            <div class="admin-table-sub muted">{{ row.keyMasked || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="keyStatusType(row.status)" effect="light">{{ keyStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分组" min-width="130">
          <template #default="{ row }">
            <span>{{ row.groupName || '—' }}</span>
            <div v-if="row.groupPlatform" class="admin-table-sub muted">{{ row.groupPlatform }}</div>
          </template>
        </el-table-column>
        <el-table-column label="用量" min-width="210">
          <template #default="{ row }">
            <div class="admin-quota-cell">
              <!-- Sub2API：列表额度恒为 0，用量取自 api-keys-usage，total 为近 30 天口径。 -->
              <template v-if="row.platformType === 'sub2api'">
                <div class="admin-quota-row is-accent">
                  <span class="admin-quota-label">今日</span>
                  <span class="admin-quota-value">{{ formatMoney4(keyMetric(asApiKey(row), 'today_actual_cost')) }}</span>
                </div>
                <div class="admin-quota-row">
                  <span class="admin-quota-label">30日</span>
                  <span class="admin-quota-value">{{ formatMoney4(row.usedQuota) }}</span>
                </div>
              </template>
              <!-- New API：used_quota 为累计已用额度；unlimited_quota 只影响剩余额度展示。 -->
              <template v-else>
                <div class="admin-quota-row is-accent">
                  <span class="admin-quota-label">已用</span>
                  <span class="admin-quota-value">{{ formatMoney4(row.usedQuota) }}</span>
                </div>
                <div class="admin-quota-row">
                  <span class="admin-quota-label">剩余</span>
                  <span v-if="row.unlimitedQuota" class="admin-quota-value is-unlimited">不限额度</span>
                  <span v-else class="admin-quota-value">{{ formatMoney4(row.remainQuota) }}</span>
                </div>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最近使用" min-width="150">
          <template #default="{ row }">
            <span>{{ row.lastUsedAt ? formatDate(row.lastUsedAt) : '—' }}</span>
            <div class="admin-table-sub muted">{{ row.expiresAt ? `过期 ${formatDate(row.expiresAt)}` : '永不过期' }}</div>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite !== false" label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :loading="revealingId === row.id" @click="revealKey(asApiKey(row))">查看明文</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="showSecret" title="API 密钥明文" width="560px" destroy-on-close append-to-body>
      <p class="admin-key-secret-tip">完整密钥（仅管理员可见），请妥善保管，避免泄露。</p>
      <div class="admin-key-secret">
        <span v-if="revealedKeyLabel" class="admin-key-secret-label">{{ revealedKeyLabel }}</span>
        <code>{{ revealedKey }}</code>
      </div>
      <template #footer>
        <el-button @click="copyRevealedKey">复制</el-button>
        <el-button type="primary" @click="showSecret = false">关闭</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Monitor, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { createAccountRecord, deleteAccountRecord, revealApiKeyRecord, updateAccountRecord, type CreateAccountInput, type UpdateAccountInput } from '../api/accounts'
import type { Account, ApiKey, Platform, UsageDashboard } from '../types'

const props = defineProps<{ accounts: Account[]; platforms: Platform[]; usageDashboards: UsageDashboard[]; apiKeys: ApiKey[]; canWrite?: boolean }>()
const emit = defineEmits<{ collect: [account: Account]; saved: [account: Account]; deleted: [accountId: number]; refresh: []; 'manage-platforms': [] }>()

const keyword = ref('')
const selectedPlatformId = ref<number | null>(null)
const statusFilter = ref('')
const balanceSort = ref<'ascending' | 'descending' | null>(null)
const showForm = ref(false)
const saving = ref(false)
const editingAccount = ref<Account | null>(null)
const form = reactive<{ platformId: number | null } & CreateAccountInput>({ platformId: null, displayName: '', loginName: '', password: '' })

const showKeys = ref(false)
const keysAccount = ref<Account | null>(null)
const revealedKey = ref('')
const revealedKeyLabel = ref('')
const showSecret = ref(false)
const revealingId = ref<number | null>(null)

const currentKeys = computed(() => keysAccount.value
  ? props.apiKeys.filter(key => key.accountId === keysAccount.value?.id)
  : [])
const keysTitle = computed(() => keysAccount.value ? `${keysAccount.value.displayName} · API 密钥` : 'API 密钥')

function keyCount(account: Account) {
  return props.apiKeys.filter(key => key.accountId === account.id).length
}

function openKeys(account: Account) {
  keysAccount.value = account
  revealedKey.value = ''
  revealedKeyLabel.value = ''
  showSecret.value = false
  showKeys.value = true
}

async function revealKey(apiKey: ApiKey) {
  revealingId.value = apiKey.id
  try {
    revealedKey.value = await revealApiKeyRecord(apiKey)
    revealedKeyLabel.value = `${apiKey.keyName || '未命名'} · ${apiKey.keyMasked || '—'}`
    showSecret.value = true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取明文密钥失败')
  } finally {
    revealingId.value = null
  }
}

/** 复制明文密钥，便于直接粘贴到上游平台。 */
async function copyRevealedKey() {
  if (!revealedKey.value) return
  try {
    await navigator.clipboard.writeText(revealedKey.value)
    ElMessage.success('密钥已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择复制')
  }
}

function asApiKey(row: unknown): ApiKey { return row as ApiKey }

function keyStatusType(status: string) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'EXPIRED' || status === 'EXHAUSTED') return 'warning'
  if (status === 'DISABLED') return 'danger'
  return 'info'
}

function keyStatusLabel(status: string) {
  return ({ ACTIVE: '启用', DISABLED: '禁用', EXPIRED: '已过期', EXHAUSTED: '已耗尽', UNKNOWN: '未知' } as Record<string, string>)[status] || status
}

function keyMetric(apiKey: ApiKey, key: string): number | null {
  const value = apiKey.metrics?.[key]
  return typeof value === 'number' ? value : null
}

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

const filteredAccounts = computed(() => props.accounts.filter(account => {
  const keywordMatched = !keyword.value || `${account.displayName} ${account.loginName} ${account.platformName}`.toLowerCase().includes(keyword.value.toLowerCase())
  const platformMatched = !selectedPlatformId.value || account.platformId === selectedPlatformId.value
  const statusMatched = !statusFilter.value || account.lastCollectStatus === statusFilter.value
  return keywordMatched && platformMatched && statusMatched
}))

/** 平台在平台列表中的顺序，作为账号分组排序依据。 */
const platformRank = computed(() => new Map(props.platforms.map((platform, index) => [platform.id, index])))

/** 组内排序键：优先登录账号，其次显示名称。 */
function accountSortKey(account: Account) {
  return (account.loginName || account.displayName || '').toLowerCase()
}

/**
 * 账号列表：先按平台分组，保证同一平台账号相邻；组内默认按账号排序。
 * 点击「余额」排序时只在平台组内按余额排列，不会打散平台分组。
 */
const sortedAccounts = computed(() => [...filteredAccounts.value].sort((a, b) => {
  const rankA = platformRank.value.get(a.platformId) ?? Number.MAX_SAFE_INTEGER
  const rankB = platformRank.value.get(b.platformId) ?? Number.MAX_SAFE_INTEGER
  if (rankA !== rankB) return rankA - rankB
  if (balanceSort.value) {
    const diff = Number(a.balance ?? 0) - Number(b.balance ?? 0)
    if (diff) return balanceSort.value === 'ascending' ? diff : -diff
  }
  return accountSortKey(a).localeCompare(accountSortKey(b), 'zh-Hans-CN')
}))

/** 余额列启用 custom 排序，交由 computed 在平台组内排序。 */
function onSortChange({ prop, order }: { prop: string | null; order: 'ascending' | 'descending' | null }) {
  balanceSort.value = prop === 'balance' && order ? order : null
}

/** 每个账号在其所属平台分组内的位置，用于展示「第 n/共 m 个」。 */
const platformGroupMeta = computed(() => {
  const totals = new Map<number, number>()
  for (const account of sortedAccounts.value) {
    totals.set(account.platformId, (totals.get(account.platformId) ?? 0) + 1)
  }
  const seen = new Map<number, number>()
  const meta = new Map<number, { index: number; total: number; first: boolean }>()
  for (const account of sortedAccounts.value) {
    const index = (seen.get(account.platformId) ?? 0) + 1
    seen.set(account.platformId, index)
    const total = totals.get(account.platformId) ?? 1
    meta.set(account.id, { index, total, first: index === 1 })
  }
  return meta
})

function groupOf(account: Account) {
  return platformGroupMeta.value.get(account.id) ?? { index: 1, total: 1, first: true }
}

/** 平台分组首行加分隔线，强化「同一平台账号相邻」的视觉边界。 */
function accountRowClass({ row, rowIndex }: { row: Account; rowIndex: number }) {
  return rowIndex > 0 && platformGroupMeta.value.get(row.id)?.first ? 'is-platform-start' : ''
}

const successCount = computed(() => filteredAccounts.value.filter(account => account.lastCollectStatus === 'SUCCESS').length)
const failedCount = computed(() => filteredAccounts.value.filter(account => account.lastCollectStatus === 'FAILED' || account.lastCollectStatus === 'PARTIAL').length)

const emptyText = computed(() => {
  if (!props.platforms.length) return '还没有平台，请先到「平台管理」添加平台'
  if (keyword.value || statusFilter.value || selectedPlatformId.value) return '当前筛选条件下没有账号'
  return '暂无账号数据'
})

/** 切换平台筛选，并同步新增表单的预选平台。 */
function onPlatformChange(platformId: number | null) {
  if (platformId) form.platformId = platformId
}

function typeLabel(type: Platform['type']) {
  return type === 'newapi' ? 'New API' : 'Sub2API'
}

function countAccounts(platformId: number) {
  return props.accounts.filter(account => account.platformId === platformId).length
}

/** 账号所属平台的访问地址，便于跳转到上游查询。 */
function platformUrl(account: Account): string {
  return props.platforms.find(platform => platform.id === account.platformId)?.url ?? ''
}

/** 展示用主机名，去掉协议与结尾斜杠。 */
function platformHost(account: Account): string {
  return platformUrl(account).replace(/^https?:\/\//, '').replace(/\/+$/, '')
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
    selectedPlatformId.value = null
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

function asAccount(row: unknown): Account { return row as Account }

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

/** 密钥用量保留四位小数，避免小额消耗被四舍五入成 0.00。 */
function formatMoney4(value: number | null | undefined) {
  return value == null ? '—' : `$${Number(value).toFixed(4)}`
}

function formatNumberOrDash(value: number | null | undefined) {
  return value == null ? '—' : new Intl.NumberFormat('zh-CN').format(Number(value))
}

/** 额度消耗率仅在 newapi 口径下有意义。 */
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
