import { apiRequest } from './client'
import type { MailSettings } from '../types'

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