<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div><p class="admin-eyebrow">PLATFORMS</p><h2>平台管理</h2><p>上游平台实例、适配器类型、账号规模和采集状态。一个平台可挂载多个账号。</p></div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增平台</el-button>
    </div>

    <el-row :gutter="16" class="admin-metric-grid">
      <el-col :xs="12" :sm="6"><MetricCard label="平台总数" :value="String(platforms.length)" hint="全部上游平台" tone="neutral" /></el-col>
      <el-col :xs="12" :sm="6"><MetricCard label="启用平台" :value="String(enabledCount)" hint="当前启用采集" tone="positive" /></el-col>
      <el-col :xs="12" :sm="6"><MetricCard label="账号总数" :value="String(accounts.length)" hint="平台下账号合计" tone="positive" /></el-col>
      <el-col :xs="12" :sm="6"><MetricCard label="New API / Sub2" :value="`${newApiCount} / ${sub2Count}`" hint="适配器类型分布" tone="neutral" /></el-col>
    </el-row>

    <el-row v-if="platforms.length" :gutter="16">
      <el-col v-for="platform in platforms" :key="platform.id" :xs="24" :md="12" :xl="8">
        <el-card shadow="hover" class="admin-card admin-platform-card">
          <div class="admin-platform-head">
            <span class="admin-platform-icon" :class="platform.type">{{ platform.type === 'newapi' ? 'NA' : 'S2' }}</span>
            <el-tag :type="platform.status ? 'success' : 'info'" effect="light" round>{{ platform.status ? '启用' : '停用' }}</el-tag>
          </div>
          <h3>{{ platform.name }}</h3>
          <el-text class="admin-url" truncated>{{ platform.url }}</el-text>
          <div class="admin-platform-stats">
            <div><strong>{{ platform.accountCount }}</strong><span>账号数</span></div>
            <div><strong>{{ platform.type }}</strong><span>适配器</span></div>
            <div><strong>{{ platform.lastCollectedAt ? formatDate(platform.lastCollectedAt) : '暂无' }}</strong><span>最近采集</span></div>
          </div>
          <el-progress :percentage="platform.accountCount ? 100 : 0" :stroke-width="7" :show-text="false" />
          <div class="admin-platform-actions">
            <el-button size="small" @click="emit('view-accounts', platform)">查看账号</el-button>
            <el-button size="small" type="primary" plain @click="emit('add-account', platform)">添加账号</el-button>
            <el-button size="small" @click="openEdit(platform)">编辑</el-button>
            <el-popconfirm
              :title="platform.accountCount ? `该平台下还有 ${platform.accountCount} 个账号，需先删除账号后才能删除平台。` : '确认删除该平台？'"
              :confirm-button-text="platform.accountCount ? '知道了' : '删除'"
              :show-cancel-button="!platform.accountCount"
              width="260"
              @confirm="platform.accountCount ? undefined : remove(platform)"
            >
              <template #reference><el-button size="small" type="danger" plain>删除</el-button></template>
            </el-popconfirm>
          </div>
        </el-card>
      </el-col>
    </el-row>
    <el-card v-else shadow="never" class="admin-card"><el-empty description="暂无平台数据" /></el-card>

    <el-dialog v-model="showForm" :title="editingPlatform ? '编辑平台实例' : '新增平台实例'" width="560px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="平台类型" required>
          <el-radio-group v-model="form.type">
            <el-radio-button value="sub2api">Sub2API</el-radio-button>
            <el-radio-button value="newapi">New API</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="平台名称" required>
          <el-input v-model="form.name" placeholder="例如：云眠 New API" />
        </el-form-item>
        <el-form-item label="Base URL" required>
          <el-input v-model="form.baseUrl" placeholder="https://example.com" />
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
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import MetricCard from '../components/MetricCard.vue'
import { createPlatformRecord, deletePlatformRecord, updatePlatformRecord, type CreatePlatformInput } from '../api/accounts'
import type { Account, Platform } from '../types'

const props = defineProps<{ platforms: Platform[]; accounts: Account[] }>()
const emit = defineEmits<{
  saved: [platform: Platform]
  updated: [platform: Platform]
  deleted: [platformId: number]
  'add-account': [platform: Platform]
  'view-accounts': [platform: Platform]
}>()

const showForm = ref(false)
const saving = ref(false)
const editingPlatform = ref<Platform | null>(null)
const form = reactive<CreatePlatformInput>({ name: '', baseUrl: '', type: 'sub2api' })

const enabledCount = computed(() => props.platforms.filter(platform => platform.status).length)
const newApiCount = computed(() => props.platforms.filter(platform => platform.type === 'newapi').length)
const sub2Count = computed(() => props.platforms.filter(platform => platform.type === 'sub2api').length)

function openCreate() {
  editingPlatform.value = null
  Object.assign(form, { name: '', baseUrl: '', type: 'sub2api' })
  showForm.value = true
}

function openEdit(platform: Platform) {
  editingPlatform.value = platform
  Object.assign(form, { name: platform.name, baseUrl: platform.url, type: platform.type })
  showForm.value = true
}

async function submit() {
  if (!form.name || !form.baseUrl) {
    ElMessage.warning('请填写平台名称与 Base URL')
    return
  }
  if (!/^https?:\/\/.+/i.test(form.baseUrl)) {
    ElMessage.warning('Base URL 必须以 http:// 或 https:// 开头')
    return
  }
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

async function remove(platform: Platform) {
  try {
    await deletePlatformRecord(platform)
    emit('deleted', platform.id)
    ElMessage.success(`平台「${platform.name}」已删除`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '平台删除失败')
  }
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>