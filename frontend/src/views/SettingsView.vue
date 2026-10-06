<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div>
        <p class="admin-eyebrow">SYSTEM SETTINGS</p>
        <h2>系统设置</h2>
        <p>配置 SMTP 邮件服务，用于发送验证码与监控通知。</p>
      </div>
    </div>

    <el-card shadow="never" class="admin-card" v-loading="loading">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>SMTP 设置</h3>
            <p>配置用于发送通知邮件的邮件服务</p>
          </div>
          <el-button :loading="testing" :disabled="canWrite === false" @click="testConnection">测试连接</el-button>
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

      <div v-if="canWrite !== false" class="settings-actions">
        <el-button type="primary" :loading="saving" @click="save">保存设置</el-button>
      </div>
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
    </el-card>

    <el-card shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>收件人</h3>
            <p>余额提醒邮件的接收人，可配置一个或多个</p>
          </div>
        </div>
      </template>

      <div v-if="canWrite !== false" class="settings-recipient-add">
        <el-input v-model="recipientForm.email" placeholder="someone@example.com" />
        <el-input v-model="recipientForm.name" placeholder="名称（可选）" />
        <el-button type="primary" :loading="addingRecipient" @click="addRecipient">添加收件人</el-button>
      </div>

      <el-table :data="recipients" v-loading="loadingRecipients" empty-text="暂未配置收件人" size="small">
        <el-table-column prop="email" label="邮箱" min-width="240" />
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }">{{ row.name || '—' }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite !== false" label="操作" width="100" align="right">
          <template #default="{ row }">
            <el-popconfirm title="确认删除该收件人？" @confirm="removeRecipient(asRecipient(row))">
              <template #reference><el-button size="small" type="danger" plain>删除</el-button></template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  addMailRecipient,
  deleteMailRecipient,
  getMailSettings,
  getNotificationSettings,
  listMailRecipients,
  saveMailSettings,
  saveNotificationSettings,
  sendTestMail,
  testMailConnection,
  type MailRecipientInput,
  type MailSettingsInput,
  type NotificationSettingsInput,
} from '../api/settings'
import type { MailRecipient } from '../types'

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

const notification = reactive<NotificationSettingsInput>({ balanceAlertEnabled: true, balanceThreshold: 5, alertIntervalMinutes: 360 })
const recipients = ref<MailRecipient[]>([])
const recipientForm = reactive<MailRecipientInput>({ email: '', name: '' })
const savingNotification = ref(false)
const loadingRecipients = ref(false)
const addingRecipient = ref(false)

function asRecipient(row: unknown): MailRecipient {
  return row as MailRecipient
}

async function loadNotification() {
  loadingRecipients.value = true
  try {
    const [settings, list] = await Promise.all([getNotificationSettings(), listMailRecipients()])
    Object.assign(notification, {
      balanceAlertEnabled: settings.balanceAlertEnabled,
      balanceThreshold: settings.balanceThreshold ?? 5,
      alertIntervalMinutes: settings.alertIntervalMinutes ?? 360,
    })
    recipients.value = list
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载通知设置失败')
  } finally {
    loadingRecipients.value = false
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

async function addRecipient() {
  if (!recipientForm.email) {
    ElMessage.warning('请填写收件人邮箱')
    return
  }
  addingRecipient.value = true
  try {
    recipients.value.push(await addMailRecipient({ email: recipientForm.email, name: recipientForm.name }))
    recipientForm.email = ''
    recipientForm.name = ''
    ElMessage.success('收件人已添加')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '添加收件人失败')
  } finally {
    addingRecipient.value = false
  }
}

async function removeRecipient(recipient: MailRecipient) {
  try {
    await deleteMailRecipient(recipient.id)
    recipients.value = recipients.value.filter(item => item.id !== recipient.id)
    ElMessage.success('收件人已删除')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除收件人失败')
  }
}

onMounted(() => {
  load()
  loadNotification()
})
</script>

<style scoped>
.settings-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 4px;
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

.settings-recipient-add {
  display: flex;
  gap: 12px;
  margin-bottom: 14px;
}
</style>