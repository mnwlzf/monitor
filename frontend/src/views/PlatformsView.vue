<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div>
        <p class="admin-eyebrow">PLATFORMS</p>
        <h2>平台管理</h2>
        <p>上游平台实例、适配器类型、账号规模与采集状态。一个平台可挂载多个账号。</p>
      </div>
      <el-button v-if="canWrite !== false" type="primary" :icon="Plus" @click="openCreate">新增平台</el-button>
    </div>

    <el-row :gutter="16" class="admin-metric-grid">
      <el-col :xs="12" :sm="6"><MetricCard label="平台总数" :value="String(platforms.length)" hint="全部上游平台" tone="neutral" /></el-col>
      <el-col :xs="12" :sm="6"><MetricCard label="启用平台" :value="String(enabledCount)" hint="当前启用采集" tone="positive" /></el-col>
      <el-col :xs="12" :sm="6"><MetricCard label="账号总数" :value="String(accounts.length)" hint="平台下账号合计" tone="positive" /></el-col>
      <el-col :xs="12" :sm="6"><MetricCard label="New API / Sub2" :value="newApiCount + ' / ' + sub2Count" hint="适配器类型分布" tone="neutral" /></el-col>
    </el-row>

    <el-card shadow="never" class="admin-card admin-toolbar-card">
      <div class="admin-toolbar admin-toolbar-compact">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索平台名称或地址" />
        <el-select v-model="typeFilter" clearable placeholder="全部类型">
          <el-option label="Sub2API" value="sub2api" />
          <el-option label="New API" value="newapi" />
        </el-select>
        <el-checkbox v-model="onlyAbnormal" label="仅看有异常账号的平台" />
      </div>
    </el-card>

    <section v-if="filteredPlatforms.length" class="admin-platform-rows">
      <article v-for="platform in filteredPlatforms" :key="platform.id" class="admin-platform-entry">
        <div class="admin-platform-identity">
          <span class="admin-platform-icon" :class="platform.type">{{ platform.type === 'newapi' ? 'NA' : 'S2' }}</span>
          <div class="admin-platform-identity-text">
            <div class="admin-platform-name-row">
              <strong>{{ platform.name }}</strong>
              <el-tag size="small" :type="platform.type === 'newapi' ? 'primary' : 'success'" effect="plain">{{ typeLabel(platform.type) }}</el-tag>
              <el-tag size="small" :type="platform.status ? 'success' : 'info'" effect="light">{{ platform.status ? '启用' : '停用' }}</el-tag>
              <el-tag v-if="platform.poolMonitoringEnabled" size="small" type="warning" effect="dark">号池源</el-tag>
            </div>
            <small class="admin-platform-url" :title="platform.url">{{ platform.url }}</small>
          </div>
        </div>

        <div class="admin-platform-cell">
          <span>账号规模</span>
          <strong>{{ platform.accountCount }} 个账号</strong>
          <small>正常 {{ healthyCount(platform.id) }} · 异常 {{ failedCount(platform.id) }}</small>
          <div v-if="accountsOf(platform.id).length" class="admin-platform-account-chips">
            <span v-for="account in accountsOf(platform.id).slice(0, 3)" :key="account.id" class="admin-platform-account-chip">{{ account.displayName }}</span>
            <span v-if="accountsOf(platform.id).length > 3" class="admin-platform-account-chip more">+{{ accountsOf(platform.id).length - 3 }}</span>
          </div>
        </div>

        <div class="admin-platform-cell admin-platform-cost">
          <div class="admin-platform-cost-item">
            <span>账号余额合计</span>
            <strong class="positive">{{ formatMoney(balanceTotal(platform.id)) }}</strong>
          </div>
          <div class="admin-platform-cost-item">
            <span>{{ costMeta(platform).label }}</span>
            <strong class="cost">{{ costMeta(platform).value }}</strong>
          </div>
        </div>

        <div class="admin-platform-cell">
          <span>最近采集</span>
          <strong>{{ platform.lastCollectedAt ? formatDate(platform.lastCollectedAt) : '暂无采集' }}</strong>
          <small>{{ platform.accountCount ? '共 ' + platform.accountCount + ' 个账号参与采集' : '尚未挂载账号' }}</small>
        </div>

        <div class="admin-platform-actions">
          <el-button size="small" @click="emit('view-accounts', platform)">查看账号</el-button>
          <el-button v-if="canWrite !== false" size="small" type="primary" plain @click="emit('add-account', platform)">添加账号</el-button>
          <el-button v-if="canWrite !== false" size="small" @click="openEdit(platform)">编辑</el-button>
          <el-popconfirm
            v-if="canWrite !== false && platform.status"
            title="停用后该平台下所有账号都不再参与采集，确认停用？"
            width="260"
            @confirm="toggleStatus(platform)"
          >
            <template #reference><el-button size="small" type="warning" plain>停用</el-button></template>
          </el-popconfirm>
          <el-button
            v-else-if="canWrite !== false"
            size="small"
            type="success"
            plain
            @click="toggleStatus(platform)"
          >启用</el-button>
          <el-popconfirm
            :title="platform.accountCount ? '该平台下还有 ' + platform.accountCount + ' 个账号，需先删除账号后才能删除平台。' : '确认删除该平台？'"
            :confirm-button-text="platform.accountCount ? '知道了' : '删除'"
            :show-cancel-button="!platform.accountCount"
            width="260"
            @confirm="platform.accountCount ? undefined : remove(platform)"
          >
            <template #reference><el-button size="small" type="danger" plain>删除</el-button></template>
          </el-popconfirm>
        </div>
      </article>
    </section>
    <el-card v-else shadow="never" class="admin-card">
      <el-empty :description="platforms.length ? '没有符合筛选条件的平台' : '暂无平台数据'" :image-size="80" />
    </el-card>

    <el-dialog v-model="showForm" :title="editingPlatform ? '编辑平台实例' : '新增平台实例'" width="560px" destroy-on-close>
      <el-form ref="platformFormRef" :model="form" :rules="formRules" label-position="top">
        <el-form-item label="平台类型" required>
          <el-radio-group v-model="form.type">
            <el-radio-button value="sub2api">Sub2API</el-radio-button>
            <el-radio-button value="newapi">New API</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="平台名称" prop="name" required>
          <el-input v-model="form.name" placeholder="例如：云眠 New API" />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl" required>
          <el-input v-model="form.baseUrl" placeholder="https://example.com" />
        </el-form-item>
        <el-form-item v-if="form.type === 'sub2api'" label="号池监控源">
          <el-switch v-model="form.poolMonitoringEnabled" />
          <p class="admin-form-hint">
            只有<b>你自己搭建的 Sub2API</b>才打开：它提供管理员只读接口，用来监控号池账号与用量。
            其它 Sub2API / New API 只是它的上游，保持关闭即可，不需要管理员密钥。
          </p>
        </el-form-item>
        <el-form-item v-if="form.type === 'sub2api' && form.poolMonitoringEnabled" label="Sub2API 管理员密钥">
          <el-input
            v-model="form.adminKey"
            type="password"
            show-password
            clearable
            :placeholder="editingPlatform?.adminKeyConfigured ? '已配置，留空表示不修改' : '管理员只读密钥（x-api-key）'"
          />
          <p class="admin-form-hint">
            仅用于只读调用号池账号与用量接口，密钥会加密保存且不会回显；
            号池监控的缓存命中率、首 Token 耗时等指标都依赖它。
          </p>
          <el-checkbox v-if="editingPlatform?.adminKeyConfigured" v-model="form.clearAdminKey">
            清除已保存的管理员密钥
          </el-checkbox>
        </el-form-item>
        <p class="admin-form-hint">
          <template v-if="editingPlatform">保存后该平台下 {{ editingPlatform.accountCount }} 个账号将按新的类型与 Base URL 采集。</template>
          <template v-else>平台创建完成后，可在「账号管理」或此处的「添加账号」为该平台挂载一个或多个采集账号。</template>
        </p>
      </el-form>
      <template #footer>
        <el-button @click="showForm = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{ editingPlatform ? '保存修改' : '保存平台' }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import MetricCard from '../components/MetricCard.vue'
import { createPlatformRecord, deletePlatformRecord, updatePlatformRecord, type CreatePlatformInput } from '../api/accounts'
import type { Account, Platform, PlatformType, UsageDashboard } from '../types'

const props = defineProps<{ platforms: Platform[]; accounts: Account[]; usageDashboards: UsageDashboard[]; canWrite?: boolean }>()
const emit = defineEmits<{
  saved: [platform: Platform]
  updated: [platform: Platform]
  deleted: [platformId: number]
  'add-account': [platform: Platform]
  'view-accounts': [platform: Platform]
}>()

const keyword = ref('')
const typeFilter = ref('')
const onlyAbnormal = ref(false)
const showForm = ref(false)
const saving = ref(false)
const editingPlatform = ref<Platform | null>(null)
const platformFormRef = ref<FormInstance | null>(null)

const form = reactive<CreatePlatformInput & { clearAdminKey: boolean }>({
  name: '',
  baseUrl: '',
  type: 'sub2api',
  adminKey: '',
  poolMonitoringEnabled: false,
  clearAdminKey: false,
})

const formRules: FormRules = {
  name: [{ required: true, message: '请填写平台名称', trigger: 'blur' }],
  baseUrl: [
    { required: true, message: '请填写 Base URL', trigger: 'blur' },
    { pattern: /^https?:\/\/.+/i, message: 'Base URL 必须以 http:// 或 https:// 开头', trigger: 'blur' },
  ],
}

const enabledCount = computed(() => props.platforms.filter(platform => platform.status).length)
const newApiCount = computed(() => props.platforms.filter(platform => platform.type === 'newapi').length)
const sub2Count = computed(() => props.platforms.filter(platform => platform.type === 'sub2api').length)

const filteredPlatforms = computed(() => props.platforms.filter(platform => {
  const text = (platform.name + ' ' + platform.url).toLowerCase()
  const keywordMatched = !keyword.value || text.includes(keyword.value.trim().toLowerCase())
  const typeMatched = !typeFilter.value || platform.type === typeFilter.value
  const abnormalMatched = !onlyAbnormal.value || failedCount(platform.id) > 0
  return keywordMatched && typeMatched && abnormalMatched
}))

function typeLabel(type: PlatformType) {
  return type === 'newapi' ? 'New API' : 'Sub2API'
}

function accountsOf(platformId: number) {
  return props.accounts.filter(account => account.platformId === platformId)
}

function healthyCount(platformId: number) {
  return accountsOf(platformId).filter(account => account.lastCollectStatus === 'SUCCESS').length
}

function failedCount(platformId: number) {
  return accountsOf(platformId).filter(account => account.lastCollectStatus === 'FAILED' || account.lastCollectStatus === 'PARTIAL').length
}

function balanceTotal(platformId: number): number | null {
  const rows = accountsOf(platformId)
  if (!rows.length) return null
  return rows.reduce((sum, account) => sum + Number(account.balance || 0), 0)
}

/**
 * 平台消耗口径随平台类型：
 * - Sub2API 上游返回 today_actual_cost，直接汇总为今日消耗；
 * - New API 只有累计 used_quota，展示累计消耗，今日增量需按快照差值单独统计。
 */
function costMeta(platform: Platform): { label: string; value: string } {
  const accountIds = new Set(accountsOf(platform.id).map(account => account.id))
  // 两类平台的今日消耗都由后端统一写入 metrics.today_actual_cost：
  // Sub2API 直接来自上游，New API 由当天累计消耗差值推算。
  const values = props.usageDashboards
    .filter(item => accountIds.has(item.accountId))
    .map(item => item.metrics?.today_actual_cost)
    .filter((value): value is number => typeof value === 'number')
  return { label: '今日消耗', value: values.length ? formatMoney(values.reduce((sum, value) => sum + value, 0)) : '—' }
}

function formatMoney(value: number | null | undefined) {
  return value == null ? '—' : '$' + Number(value).toFixed(2)
}

function openCreate() {
  editingPlatform.value = null
  Object.assign(form, { name: '', baseUrl: '', type: 'sub2api', adminKey: '', poolMonitoringEnabled: false, clearAdminKey: false })
  showForm.value = true
}

function openEdit(platform: Platform) {
  editingPlatform.value = platform
  Object.assign(form, { name: platform.name, baseUrl: platform.url, type: platform.type, adminKey: '', poolMonitoringEnabled: platform.poolMonitoringEnabled === true, clearAdminKey: false })
  showForm.value = true
}

async function submit() {
  const formEl = platformFormRef.value
  if (!formEl) return
  // 表单校验失败时字段下方会出现红字提示，这里不再弹 toast
  const valid = await formEl.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    if (editingPlatform.value) {
      emit('updated', await updatePlatformRecord(editingPlatform.value, form))
      ElMessage.success('平台已更新')
    } else {
      emit('saved', await createPlatformRecord(form))
      ElMessage.success('平台「' + form.name + '」已创建，可继续为其添加账号')
    }
    showForm.value = false
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '平台保存失败')
  } finally {
    saving.value = false
  }
}

/** 启用/停用平台：停用后该平台下所有账号都不再参与采集。 */
async function toggleStatus(platform: Platform) {
  const next = !platform.status
  try {
    emit('updated', await updatePlatformRecord(platform, { status: next }))
    ElMessage.success(next
      ? `平台「${platform.name}」已启用`
      : `平台「${platform.name}」已停用，其下账号将不再参与采集`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '平台状态更新失败')
  }
}

async function remove(platform: Platform) {
  try {
    await deletePlatformRecord(platform)
    emit('deleted', platform.id)
    ElMessage.success('平台「' + platform.name + '」已删除')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '平台删除失败')
  }
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>