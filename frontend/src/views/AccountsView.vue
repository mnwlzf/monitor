<template>
  <section class="monitor-page">
    <div class="monitor-page-heading">
      <div><p class="monitor-eyebrow">ACCOUNTS</p><h1>账号管理</h1><p>余额、额度、采集状态和下次执行时间。</p></div>
      <button class="monitor-primary" @click="showForm = true">新增账号</button>
    </div>

    <article class="monitor-panel monitor-table-panel">
      <div class="monitor-table-wrap">
        <table class="monitor-table">
          <thead><tr><th>账号</th><th>平台</th><th>余额</th><th>已用额度</th><th>采集状态</th><th>最近采集</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="account in accounts" :key="account.id">
              <td><strong>{{ account.displayName }}</strong><small>{{ account.loginName }}</small></td>
              <td><span class="monitor-platform-tag" :class="account.platformType">{{ account.platformName }}</span></td>
              <td class="monitor-number positive">${{ account.balance.toFixed(2) }}</td>
              <td class="monitor-number">{{ formatNumber(account.usedQuota) }}</td>
              <td><span class="monitor-status" :class="statusClass(account.lastCollectStatus)">{{ account.lastCollectStatus }}</span></td>
              <td>{{ account.lastCollectedAt ? formatDate(account.lastCollectedAt) : '暂无' }}</td>
              <td><button class="monitor-action" @click="emit('collect', account)">立即采集</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <div v-if="showForm" class="monitor-modal-backdrop" @click.self="closeForm">
      <form class="monitor-modal" @submit.prevent="submit">
        <header>
          <div><p class="monitor-eyebrow">NEW ACCOUNT</p><h2>新增采集账号</h2></div>
          <button type="button" class="monitor-modal-close" @click="closeForm">×</button>
        </header>

        <div class="monitor-form-grid">
          <label>
            平台类型
            <select v-model="form.platformType" required>
              <option value="newapi">New API</option>
              <option value="sub2api">Sub2API</option>
            </select>
          </label>
          <label>
            平台名称
            <input v-model.trim="form.platformName" placeholder="例如：云眠 New API">
          </label>
          <label class="monitor-form-full">
            Base URL
            <input v-model.trim="form.baseUrl" required type="url" placeholder="https://example.com">
          </label>
          <label>
            登录账号
            <input v-model.trim="form.loginName" required autocomplete="username" placeholder="邮箱或用户名">
          </label>
          <label>
            登录密码
            <input v-model="form.password" required type="password" autocomplete="new-password" placeholder="登录密码">
          </label>
          <label class="monitor-form-full">
            显示名称
            <input v-model.trim="form.displayName" placeholder="例如：主账号">
          </label>
        </div>

        <p v-if="formError" class="monitor-form-error">{{ formError }}</p>

        <footer>
          <button type="button" class="monitor-secondary" @click="closeForm">取消</button>
          <button type="submit" class="monitor-primary" :disabled="saving">{{ saving ? '保存中...' : '保存账号' }}</button>
        </footer>
      </form>
    </div>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { createAccountRecord, type CreateAccountInput } from '../api/accounts'
import type { Account } from '../types'

defineProps<{ accounts: Account[] }>()
const emit = defineEmits<{ collect: [account: Account]; created: [account: Account] }>()

const showForm = ref(false)
const saving = ref(false)
const formError = ref('')
const form = reactive<CreateAccountInput>({
  platformType: 'newapi',
  platformName: '',
  baseUrl: '',
  displayName: '',
  loginName: '',
  password: '',
})

function closeForm() {
  if (saving.value) return
  showForm.value = false
  formError.value = ''
}

async function submit() {
  formError.value = ''
  saving.value = true
  try {
    const account = await createAccountRecord(form)
    emit('created', account)
    showForm.value = false
    Object.assign(form, { platformType: 'newapi', platformName: '', baseUrl: '', displayName: '', loginName: '', password: '' })
  } catch (error) {
    formError.value = error instanceof Error ? error.message : '账号保存失败'
  } finally {
    saving.value = false
  }
}

function statusClass(status: Account['lastCollectStatus']) {
  return { success: status === 'SUCCESS', failed: status === 'FAILED', running: status === 'RUNNING' }
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('zh-CN').format(value)
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>