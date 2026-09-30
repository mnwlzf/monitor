<template>
  <section class="monitor-page">
    <div class="monitor-page-heading">
      <div><p class="monitor-eyebrow">ACCOUNTS</p><h1>账号管理</h1><p>余额、额度、采集状态和下次执行时间。</p></div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增账号</el-button>
    </div>

    <el-card shadow="never" class="monitor-table-panel">
      <el-table :data="accounts" stripe>
        <el-table-column label="账号" min-width="190">
          <template #default="{ row }"><strong>{{ row.displayName }}</strong><small class="monitor-table-sub">{{ row.loginName }}</small></template>
        </el-table-column>
        <el-table-column label="平台" min-width="150">
          <template #default="{ row }"><el-tag :type="row.platformType === 'newapi' ? 'primary' : 'success'" effect="light">{{ row.platformName }}</el-tag></template>
        </el-table-column>
        <el-table-column label="余额" width="120">
          <template #default="{ row }"><span class="monitor-number positive">${{ row.balance.toFixed(2) }}</span></template>
        </el-table-column>
        <el-table-column label="已用额度" width="140">
          <template #default="{ row }">{{ formatNumber(row.usedQuota) }}</template>
        </el-table-column>
        <el-table-column label="采集状态" width="120">
          <template #default="{ row }"><el-tag :type="statusType(row.lastCollectStatus)" effect="light">{{ row.lastCollectStatus }}</el-tag></template>
        </el-table-column>
        <el-table-column label="最近采集" min-width="150">
          <template #default="{ row }">{{ row.lastCollectedAt ? formatDate(row.lastCollectedAt) : '暂无' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="emit('collect', row as Account)">采集</el-button>
            <el-button size="small" type="primary" plain @click="openEdit(row as Account)">编辑</el-button>
            <el-popconfirm title="确认删除该账号？" @confirm="remove(row as Account)">
              <template #reference><el-button size="small" type="danger" plain>删除</el-button></template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="showForm" :title="editingAccount ? '编辑采集账号' : '新增采集账号'" width="640px" destroy-on-close>
      <el-form label-position="top" class="monitor-form-grid">
        <el-form-item label="平台类型" required>
          <el-select v-model="form.platformType" :disabled="Boolean(editingAccount)" style="width: 100%">
            <el-option label="New API" value="newapi" />
            <el-option label="Sub2API" value="sub2api" />
          </el-select>
        </el-form-item>
        <el-form-item label="平台名称">
          <el-input v-model="form.platformName" :disabled="Boolean(editingAccount)" placeholder="例如：云眠 New API" />
        </el-form-item>
        <el-form-item label="Base URL" required class="monitor-form-full">
          <el-input v-model="form.baseUrl" :disabled="Boolean(editingAccount)" placeholder="https://example.com" />
        </el-form-item>
        <el-form-item label="登录账号" required>
          <el-input v-model="form.loginName" placeholder="邮箱或用户名" />
        </el-form-item>
        <el-form-item :label="editingAccount ? '登录密码（留空不修改）' : '登录密码'" :required="!editingAccount">
          <el-input v-model="form.password" type="password" show-password placeholder="登录密码" />
        </el-form-item>
        <el-form-item label="显示名称" class="monitor-form-full">
          <el-input v-model="form.displayName" placeholder="例如：主账号" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="showForm = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存账号</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { createAccountRecord, deleteAccountRecord, updateAccountRecord, type CreateAccountInput, type UpdateAccountInput } from '../api/accounts'
import type { Account } from '../types'

defineProps<{ accounts: Account[] }>()
const emit = defineEmits<{ collect: [account: Account]; saved: [account: Account]; deleted: [accountId: number] }>()

const showForm = ref(false)
const saving = ref(false)
const editingAccount = ref<Account | null>(null)
const form = reactive<CreateAccountInput>({
  platformType: 'newapi',
  platformName: '',
  baseUrl: '',
  displayName: '',
  loginName: '',
  password: '',
})

function openCreate() {
  editingAccount.value = null
  Object.assign(form, { platformType: 'newapi', platformName: '', baseUrl: '', displayName: '', loginName: '', password: '' })
  showForm.value = true
}

function openEdit(account: Account) {
  editingAccount.value = account
  Object.assign(form, {
    platformType: account.platformType,
    platformName: account.platformName,
    baseUrl: '',
    displayName: account.displayName,
    loginName: account.loginName,
    password: '',
  })
  showForm.value = true
}

async function submit() {
  if (!form.loginName || (!editingAccount.value && !form.password) || (!editingAccount.value && !form.baseUrl)) {
    ElMessage.warning('请填写必填项')
    return
  }

  saving.value = true
  try {
    if (editingAccount.value) {
      const input: UpdateAccountInput = {
        displayName: form.displayName,
        loginName: form.loginName,
        password: form.password,
        authType: 'PASSWORD',
      }
      const updated = await updateAccountRecord(editingAccount.value, input)
      emit('saved', updated)
      ElMessage.success('账号已更新')
    } else {
      const created = await createAccountRecord(form)
      emit('saved', created)
      ElMessage.success('账号已创建')
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

function statusType(status: Account['lastCollectStatus']) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'primary'
  return 'warning'
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('zh-CN').format(value)
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>