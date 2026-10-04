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
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  overflow: hidden;
  background: linear-gradient(160deg, #f2fbf8 0%, #eaf6fb 55%, #f4f9ff 100%);
}

.login-shell::before,
.login-shell::after {
  content: '';
  position: absolute;
  border-radius: 50%;
  filter: blur(10px);
  opacity: .55;
}

.login-shell::before {
  width: 420px;
  height: 420px;
  top: -140px;
  right: -100px;
  background: radial-gradient(circle, #cdeee6 0%, rgba(205, 238, 230, 0) 70%);
}

.login-shell::after {
  width: 360px;
  height: 360px;
  bottom: -120px;
  left: -80px;
  background: radial-gradient(circle, #d6ebfb 0%, rgba(214, 235, 251, 0) 70%);
}

.login-card {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 400px;
  padding: 36px 32px 28px;
  border-radius: 20px;
  background: rgba(255, 255, 255, .94);
  border: 1px solid #e6f1ee;
  box-shadow: 0 20px 48px rgba(63, 140, 122, .12);
  color: #2f4a48;
  backdrop-filter: blur(6px);
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 26px;
}

.login-brand-mark {
  width: 44px;
  height: 44px;
  border-radius: 13px;
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 20px;
  color: #ffffff;
  background: linear-gradient(135deg, #3fb59f, #6fd3bd);
  box-shadow: 0 10px 22px rgba(63, 181, 159, .28);
}

.login-brand strong {
  display: block;
  font-size: 17px;
  letter-spacing: .3px;
  color: #2f4a48;
}

.login-brand small {
  color: #8aa5a1;
  font-size: 12px;
}

.login-card h1 {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 600;
  color: #2f4a48;
}

.login-subtitle {
  margin: 0 0 20px;
  color: #8aa5a1;
  font-size: 13px;
}

.login-alert {
  margin-bottom: 16px;
  border-radius: 10px;
}

.login-card :deep(.el-input__wrapper) {
  border-radius: 11px;
  background: #f7fbfa;
  box-shadow: 0 0 0 1px #e6f1ee inset;
}

.login-card :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #3fb59f inset;
}

.login-submit {
  width: 100%;
  margin-top: 6px;
  border-radius: 11px;
  background: linear-gradient(135deg, #3fb59f, #56c7ae);
  border: none;
  font-weight: 600;
  letter-spacing: .5px;
}

.login-submit:hover {
  background: linear-gradient(135deg, #37a48f, #4fbfa7);
}

.login-footer {
  margin: 18px 0 0;
  text-align: center;
  color: #a3b7b3;
  font-size: 12px;
}
</style>