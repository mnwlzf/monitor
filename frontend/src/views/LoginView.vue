<template>
  <div class="login-shell">
    <div class="login-card">
      <div class="login-brand">
        <span class="login-brand-mark">M</span>
        <div>
          <strong>Monitor</strong>
          <small>Upstream Control Center</small>
        </div>
      </div>

      <h1>登录</h1>
      <p class="login-subtitle">请使用管理员账号登录后访问监控系统</p>

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
        class="login-alert"
      />

      <el-form @submit.prevent>
        <el-form-item>
          <el-input
            v-model="username"
            size="large"
            placeholder="登录名"
            :prefix-icon="User"
            autocomplete="username"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="password"
            size="large"
            type="password"
            placeholder="密码"
            :prefix-icon="Lock"
            show-password
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button
          type="primary"
          size="large"
          class="login-submit"
          :loading="loading"
          @click="submit"
        >
          登录
        </el-button>
      </el-form>

      <p class="login-footer">登录状态默认保持 8 小时</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Lock, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { login } from '../api/auth'
import { ApiError } from '../api/client'
import type { CurrentUser } from '../types'

const emit = defineEmits<{ 'logged-in': [user: CurrentUser] }>()

const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMessage = ref('')

async function submit() {
  if (loading.value) return
  if (!username.value || !password.value) {
    errorMessage.value = '请输入登录名和密码'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const user = await login(username.value, password.value)
    ElMessage.success(`欢迎回来，${user.username}`)
    emit('logged-in', user)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-shell {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: radial-gradient(circle at 20% 20%, #1f3a5f 0%, #0f172a 45%, #070d17 100%);
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: 36px 32px 28px;
  border-radius: 18px;
  background: rgba(15, 23, 42, 0.92);
  border: 1px solid rgba(148, 163, 184, 0.18);
  box-shadow: 0 24px 60px rgba(2, 6, 23, 0.55);
  color: #e2e8f0;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 28px;
}

.login-brand-mark {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 20px;
  color: #0b1220;
  background: linear-gradient(135deg, #60a5fa, #34d399);
}

.login-brand strong {
  display: block;
  font-size: 17px;
  letter-spacing: 0.4px;
}

.login-brand small {
  color: #94a3b8;
  font-size: 12px;
}

.login-card h1 {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 600;
}

.login-subtitle {
  margin: 0 0 20px;
  color: #94a3b8;
  font-size: 13px;
}

.login-alert {
  margin-bottom: 16px;
}

.login-submit {
  width: 100%;
  margin-top: 4px;
}

.login-footer {
  margin: 18px 0 0;
  text-align: center;
  color: #64748b;
  font-size: 12px;
}
</style>