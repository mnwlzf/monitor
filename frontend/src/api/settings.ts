import { apiRequest } from './client'
import type { BotAdmin, BotIdentityOverview, BotMessage, BotMessageArchiveStats, BotSettings, MailRecipient, MailScene, MailSettings, NotificationSettings } from '../types'

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

/** 保存 QQ 机器人设置的表单载荷。 */
export interface BotSettingsInput {
  enabled: boolean
  allowedGroups: string[]
  allowedUsers: string[]
  /** 平台功能群（指定群），必须是 allowedGroups 的子集。 */
  platformGroups: string[]
  requireMention: boolean
  commandPrefix: string
  maxReplyLength: number
  memoryWindow: number
}

/** 读取 QQ 机器人设置。 */
export async function getBotSettings(): Promise<BotSettings> {
  return apiRequest<BotSettings>('/api/v1/settings/bot')
}

/** 保存 QQ 机器人设置；保存后立即生效，不需要重启。 */
export async function saveBotSettings(input: BotSettingsInput): Promise<BotSettings> {
  return apiRequest<BotSettings>('/api/v1/settings/bot', {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}
/** 读取身份识别总览（Sub2API 用户缓存情况 + 自定义管理员名单）。 */
export async function getBotIdentity(): Promise<BotIdentityOverview> {
  return apiRequest<BotIdentityOverview>('/api/v1/settings/bot/identity')
}

/** 立即从 Sub2API 只读库同步一次平台用户。 */
export async function syncBotIdentity(): Promise<BotIdentityOverview> {
  return apiRequest<BotIdentityOverview>('/api/v1/settings/bot/identity/sync', { method: 'POST' })
}

/** 新增自定义管理员。 */
export async function addBotAdmin(email: string, remark: string): Promise<BotAdmin> {
  return apiRequest<BotAdmin>('/api/v1/settings/bot/identity/admins', {
    method: 'POST',
    body: JSON.stringify({ email, remark }),
  })
}

/** 删除自定义管理员。 */
export async function deleteBotAdmin(id: number): Promise<void> {
  await apiRequest<void>(`/api/v1/settings/bot/identity/admins/${id}`, { method: 'DELETE' })
}
/** 消息存档查询条件。 */
export interface BotMessageQuery {
  keyword?: string
  userId?: number
  groupId?: number
  from?: string
  to?: string
  limit?: number
}

/** 查询机器人消息存档，时间倒序。 */
export async function searchBotMessages(query: BotMessageQuery): Promise<BotMessage[]> {
  const params = new URLSearchParams()
  if (query.keyword) params.set('keyword', query.keyword)
  if (query.userId != null) params.set('userId', String(query.userId))
  if (query.groupId != null) params.set('groupId', String(query.groupId))
  if (query.from) params.set('from', query.from)
  if (query.to) params.set('to', query.to)
  if (query.limit != null) params.set('limit', String(query.limit))
  const suffix = params.toString()
  return apiRequest<BotMessage[]>(`/api/v1/settings/bot/archive${suffix ? `?${suffix}` : ''}`)
}

/** 读取消息存档概况。 */
export async function getBotArchiveStats(): Promise<BotMessageArchiveStats> {
  return apiRequest<BotMessageArchiveStats>('/api/v1/settings/bot/archive/stats')
}