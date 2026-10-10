<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div>
        <p class="admin-eyebrow">SYSTEM SETTINGS</p>
        <h2>系统设置</h2>
        <p>配置 SMTP 邮件服务、余额提醒与各事件的邮件接收人。</p>
      </div>
    </div>

    <el-card shadow="never" class="admin-card" v-loading="loading">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>SMTP 设置</h3>
            <p>配置用于发送通知邮件的邮件服务</p>
          </div>
          <div class="settings-header-actions">
            <el-button :loading="testing" :disabled="canWrite === false" @click="testConnection">测试连接</el-button>
            <el-button v-if="canWrite !== false" type="primary" :loading="saving" @click="save">保存设置</el-button>
          </div>
        </div>
      </template>

      <el-form label-position="top">
        <div class="admin-form-grid">
          <el-form-item label="SMTP 主机">
            <el-input v-model="form.host" placeholder="smtp.qq.com" :disabled="canWrite === false" />
          </el-form-item>
          <el-form-item label="SMTP 端口">
            <el-input-number
              v-model="form.port"
              :min="1"
              :max="65535"
              controls-position="right"
              style="width: 100%"
              :disabled="canWrite === false"
            />
          </el-form-item>
          <el-form-item label="SMTP 用户名">
            <el-input v-model="form.username" placeholder="noreply@example.com" :disabled="canWrite === false" />
          </el-form-item>
          <el-form-item label="SMTP 密码">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              :placeholder="passwordPlaceholder"
              :disabled="canWrite === false"
            />
            <small class="admin-form-hint">{{ passwordHint }}</small>
          </el-form-item>
          <el-form-item label="发件人邮箱">
            <el-input v-model="form.from" placeholder="noreply@example.com" :disabled="canWrite === false" />
          </el-form-item>
          <el-form-item label="发件人名称">
            <el-input v-model="form.fromName" placeholder="Monitor" :disabled="canWrite === false" />
          </el-form-item>
          <div class="admin-form-full settings-toggle-row">
            <div>
              <div class="settings-toggle-title">使用 TLS</div>
              <small class="admin-form-hint">为 SMTP 连接启用隐式 TLS（通常为 465 端口）；关闭后按机会式 STARTTLS（587/25）连接。</small>
            </div>
            <el-switch v-model="form.useTls" :disabled="canWrite === false" />
          </div>
          <div class="admin-form-full settings-toggle-row">
            <div>
              <div class="settings-toggle-title">启用邮件通知</div>
              <small class="admin-form-hint">关闭后不会发送任何通知邮件，但仍可在本页测试配置。</small>
            </div>
            <el-switch v-model="form.enabled" :disabled="canWrite === false" />
          </div>
        </div>
      </el-form>
    </el-card>

    <el-card shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>发送测试邮件</h3>
            <p>发送测试邮件以验证 SMTP 配置</p>
          </div>
        </div>
      </template>

      <div class="settings-test-email">
        <el-input
          v-model="testRecipient"
          placeholder="test@example.com"
          :disabled="canWrite === false"
          @keyup.enter="sendTestEmail"
        />
        <el-button type="primary" plain :loading="sending" :disabled="canWrite === false" @click="sendTestEmail">
          发送测试邮件
        </el-button>
      </div>
      <small class="admin-form-hint">使用上方当前填写的配置（未保存也可）发送一封测试邮件。</small>
    </el-card>

    <el-card shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>余额不足提醒</h3>
            <p>余额采集完成后，平台账号余额合计低于阈值时发送邮件提醒</p>
          </div>
          <el-button v-if="canWrite !== false" type="primary" :loading="savingNotification" @click="saveNotification">保存</el-button>
        </div>
      </template>

      <el-form label-position="top">
        <div class="admin-form-grid">
          <el-form-item label="启用余额提醒">
            <el-switch v-model="notification.balanceAlertEnabled" :disabled="canWrite === false" />
            <small class="admin-form-hint">关闭后不再发送余额提醒邮件。</small>
          </el-form-item>
          <el-form-item label="余额阈值（USD）">
            <el-input-number
              v-model="notification.balanceThreshold"
              :min="0"
              :step="1"
              :precision="2"
              controls-position="right"
              style="width: 100%"
              :disabled="canWrite === false"
            />
            <small class="admin-form-hint">平台账号余额合计低于该值时提醒，默认 5。</small>
          </el-form-item>
          <el-form-item label="提醒间隔（分钟）">
            <el-input-number
              v-model="notification.alertIntervalMinutes"
              :min="1"
              :step="60"
              controls-position="right"
              style="width: 100%"
              :disabled="canWrite === false"
            />
            <small class="admin-form-hint">余额持续低于阈值时，每隔该时长重复提醒一次，默认 360 分钟（6 小时）。</small>
          </el-form-item>
        </div>
      </el-form>

      <el-divider content-position="left">该事件的收件人</el-divider>
      <RecipientEditor scene="BALANCE_ALERT" :can-write="canWrite" />
    </el-card>

    <el-card shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>每日余额消耗报表</h3>
            <p>每天凌晨发送前一天各平台、各账号的余额消耗报表</p>
          </div>
        </div>
      </template>

      <small class="admin-form-hint">发送时间与启停在「定时任务」页配置（任务：每日余额消耗报表）。</small>

      <el-divider content-position="left">该事件的收件人</el-divider>
      <RecipientEditor scene="DAILY_REPORT" :can-write="canWrite" />
    </el-card>

    <el-card shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>密钥变更提醒</h3>
            <p>正在使用（启用）的密钥发生变更时发送邮件提醒</p>
          </div>
        </div>
      </template>

      <small class="admin-form-hint">由「API Key 采集」任务在采集完成后触发，只提醒本轮新产生的变更。</small>

      <el-divider content-position="left">该事件的收件人</el-divider>
      <RecipientEditor scene="API_KEY_CHANGE" :can-write="canWrite" />
    </el-card>
    <el-card shadow="never" class="admin-card" v-loading="botLoading">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>QQ 机器人</h3>
            <p>白名单与行为参数保存在数据库里，改完立即生效，不需要重建容器</p>
          </div>
        </div>
      </template>

      <el-form label-width="150px" :disabled="!canWrite">
        <el-form-item label="启用">
          <el-switch v-model="botForm.enabled" />
          <span class="admin-form-hint" style="margin-left: 10px">
            关闭后机器人收到消息也不响应；服务端总开关仍是环境变量 MONITOR_BOT_ENABLED
          </span>
        </el-form-item>

        <el-form-item label="允许的群号">
          <el-select
            v-model="botForm.allowedGroups"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="输入群号后回车；留空表示不限制"
            style="width: 100%"
          >
            <el-option v-for="item in botForm.allowedGroups" :key="item" :label="item" :value="item" />
          </el-select>
          <small class="admin-form-hint">只有这些群里 @机器人 才会响应</small>
        </el-form-item>

        <el-form-item label="允许的私聊 QQ">
          <el-select
            v-model="botForm.allowedUsers"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="输入 QQ 号后回车；留空表示不限制"
            style="width: 100%"
          >
            <el-option v-for="item in botForm.allowedUsers" :key="item" :label="item" :value="item" />
          </el-select>
          <small class="admin-form-hint">只有这些 QQ 私聊机器人才会响应</small>
        </el-form-item>

        <el-form-item label="群里需 @机器人">
          <el-switch v-model="botForm.requireMention" />
          <span class="admin-form-hint" style="margin-left: 10px">关掉后群里任意消息都会触发，容易刷屏</span>
        </el-form-item>

        <el-form-item label="命令前缀">
          <el-input v-model="botForm.commandPrefix" style="width: 120px" maxlength="8" />
          <span class="admin-form-hint" style="margin-left: 10px">例如 / 表示 /help、/号池</span>
        </el-form-item>

        <el-form-item label="回复最大长度">
          <el-input-number v-model="botForm.maxReplyLength" :min="50" :max="4000" :step="50" />
          <span class="admin-form-hint" style="margin-left: 10px">超长会被截断（QQ 对消息长度有限制）</span>
        </el-form-item>

        <el-form-item label="会话记忆条数">
          <el-input-number v-model="botForm.memoryWindow" :min="2" :max="50" />
          <span class="admin-form-hint" style="margin-left: 10px">保留最近几条对话，用于「那 30 天呢？」这类追问</span>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="botSaving" @click="saveBotSettingsForm">保存</el-button>
          <span v-if="botUpdatedAt" class="admin-form-hint" style="margin-left: 12px">上次保存：{{ botUpdatedAt }}</span>
        </el-form-item>
      </el-form>
    </el-card>

  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import RecipientEditor from '../components/RecipientEditor.vue'
import {
  getBotSettings,
  getMailSettings,
  getNotificationSettings,
  saveBotSettings,
  saveMailSettings,
  saveNotificationSettings,
  sendTestMail,
  testMailConnection,
  type BotSettingsInput,
  type MailSettingsInput,
  type NotificationSettingsInput,
} from '../api/settings'
import type { BotSettings } from '../types'

defineProps<{ canWrite?: boolean }>()

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const sending = ref(false)
const testRecipient = ref('')
const passwordConfigured = ref(false)

const form = reactive<MailSettingsInput>({
  enabled: false,
  host: '',
  port: 587,
  username: '',
  password: '',
  from: '',
  fromName: 'Monitor',
  useTls: false,
})

const passwordPlaceholder = computed(() => (passwordConfigured.value ? '••••••••' : '请输入 SMTP 密码或授权码'))
const passwordHint = computed(() =>
  passwordConfigured.value ? '密码已配置，留空以保留当前值。' : '部分邮箱需使用授权码而非登录密码。',
)

function payload(): MailSettingsInput {
  return { ...form }
}

async function load() {
  loading.value = true
  try {
    const settings = await getMailSettings()
    passwordConfigured.value = settings.passwordConfigured
    Object.assign(form, {
      enabled: settings.enabled,
      host: settings.host ?? '',
      port: settings.port ?? 587,
      username: settings.username ?? '',
      password: '',
      from: settings.from ?? '',
      fromName: settings.fromName ?? 'Monitor',
      useTls: settings.useTls,
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载 SMTP 设置失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const settings = await saveMailSettings(payload())
    passwordConfigured.value = settings.passwordConfigured
    form.password = ''
    ElMessage.success('SMTP 设置已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function testConnection() {
  testing.value = true
  try {
    await testMailConnection(payload())
    ElMessage.success('SMTP 连接成功')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'SMTP 连接失败')
  } finally {
    testing.value = false
  }
}

async function sendTestEmail() {
  if (!testRecipient.value) {
    ElMessage.warning('请填写收件人邮箱')
    return
  }
  sending.value = true
  try {
    await sendTestMail(testRecipient.value, payload())
    ElMessage.success('测试邮件已发送')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试邮件发送失败')
  } finally {
    sending.value = false
  }
}

const notification = reactive<NotificationSettingsInput>({
  balanceAlertEnabled: true,
  balanceThreshold: 5,
  alertIntervalMinutes: 360,
})
const savingNotification = ref(false)

async function loadNotification() {
  try {
    const settings = await getNotificationSettings()
    Object.assign(notification, {
      balanceAlertEnabled: settings.balanceAlertEnabled,
      balanceThreshold: settings.balanceThreshold ?? 5,
      alertIntervalMinutes: settings.alertIntervalMinutes ?? 360,
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载通知设置失败')
  }
}

async function saveNotification() {
  savingNotification.value = true
  try {
    const settings = await saveNotificationSettings({ ...notification })
    Object.assign(notification, {
      balanceAlertEnabled: settings.balanceAlertEnabled,
      balanceThreshold: settings.balanceThreshold ?? 5,
      alertIntervalMinutes: settings.alertIntervalMinutes ?? 360,
    })
    ElMessage.success('余额提醒设置已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    savingNotification.value = false
  }
}

const botLoading = ref(false)
const botSaving = ref(false)
const botUpdatedAt = ref('')
const botForm = reactive<BotSettingsInput>({
  enabled: true,
  allowedGroups: [],
  allowedUsers: [],
  requireMention: true,
  commandPrefix: '/',
  maxReplyLength: 900,
  memoryWindow: 10,
})

function applyBotSettings(settings: BotSettings) {
  Object.assign(botForm, {
    enabled: settings.enabled,
    allowedGroups: [...(settings.allowedGroups ?? [])],
    allowedUsers: [...(settings.allowedUsers ?? [])],
    requireMention: settings.requireMention,
    commandPrefix: settings.commandPrefix || '/',
    maxReplyLength: settings.maxReplyLength || 900,
    memoryWindow: settings.memoryWindow || 10,
  })
  botUpdatedAt.value = settings.updatedAt ? new Date(settings.updatedAt).toLocaleString('zh-CN') : ''
}

async function loadBot() {
  botLoading.value = true
  try {
    applyBotSettings(await getBotSettings())
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载机器人设置失败')
  } finally {
    botLoading.value = false
  }
}

async function saveBotSettingsForm() {
  botSaving.value = true
  try {
    applyBotSettings(await saveBotSettings({ ...botForm }))
    ElMessage.success('机器人设置已保存，立即生效')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    botSaving.value = false
  }
}
onMounted(() => {
  load()
  loadNotification()
  loadBot()
})
</script>

<style scoped>
.settings-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.settings-toggle-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.settings-toggle-title {
  margin-bottom: 4px;
  color: #303133;
  font-size: 14px;
}

.settings-test-email {
  display: flex;
  gap: 12px;
  align-items: center;
}
</style>