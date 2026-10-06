import { apiRequest } from './client'
import type { MailRecipient, MailScene, MailSettings, NotificationSettings } from '../types'

/** 保存 SMTP 设置的表单载荷；password 留空表示保留已保存的密码。 */
export interface MailSettingsInput {
  enabled: boolean
  host: string
  port: number
  username: string
  password: string
  from: string
  fromName: string
  useTls: boolean
}

/** 读取当前 SMTP 设置（不含密码明文）。 */
export async function getMailSettings(): Promise<MailSettings> {
  return apiRequest<MailSettings>('/api/v1/settings/mail')
}

/** 保存 SMTP 设置。 */
export async function saveMailSettings(input: MailSettingsInput): Promise<MailSettings> {
  return apiRequest<MailSettings>('/api/v1/settings/mail', {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}

/** 测试 SMTP 连接；传入表单值时用表单值，否则用已保存值。 */
export async function testMailConnection(input?: MailSettingsInput): Promise<void> {
  await apiRequest<void>('/api/v1/settings/mail/test', {
    method: 'POST',
    body: JSON.stringify(input ?? {}),
  })
}

/** 发送测试邮件。 */
export async function sendTestMail(to: string, settings?: MailSettingsInput): Promise<void> {
  await apiRequest<void>('/api/v1/settings/mail/test-email', {
    method: 'POST',
    body: JSON.stringify({ to, settings: settings ?? null }),
  })
}

/** 保存余额提醒设置的表单载荷。 */
export interface NotificationSettingsInput {
  balanceAlertEnabled: boolean
  balanceThreshold: number
  alertIntervalMinutes: number
}

/** 新增收件人的表单载荷。 */
export interface MailRecipientInput {
  scene: MailScene
  email: string
  name: string
}

/** 读取余额提醒设置。 */
export async function getNotificationSettings(): Promise<NotificationSettings> {
  return apiRequest<NotificationSettings>('/api/v1/settings/notification')
}

/** 保存余额提醒设置。 */
export async function saveNotificationSettings(input: NotificationSettingsInput): Promise<NotificationSettings> {
  return apiRequest<NotificationSettings>('/api/v1/settings/notification', {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}

/** 查询指定事件场景的收件人。 */
export async function listMailRecipients(scene: MailScene): Promise<MailRecipient[]> {
  return apiRequest<MailRecipient[]>(`/api/v1/settings/notification/recipients?scene=${scene}`)
}

/** 新增指定事件的收件人。 */
export async function addMailRecipient(input: MailRecipientInput): Promise<MailRecipient> {
  return apiRequest<MailRecipient>('/api/v1/settings/notification/recipients', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

/** 删除余额提醒收件人。 */
export async function deleteMailRecipient(id: number): Promise<void> {
  await apiRequest<void>(`/api/v1/settings/notification/recipients/${id}`, { method: 'DELETE' })
}