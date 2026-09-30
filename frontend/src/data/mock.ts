import type { Account, ChangeEvent, Channel, MetricPoint, Platform } from '../types'

export const platforms: Platform[] = [
  { id: 1, name: '云眠 New API', type: 'newapi', url: 'https://yunmian.tech', status: true, accountCount: 2, lastCollectedAt: '2026-09-30T11:52:00+08:00' },
  { id: 2, name: 'Codex Sub2API', type: 'sub2api', url: 'https://codex.trovebox.online', status: true, accountCount: 1, lastCollectedAt: '2026-09-30T11:51:00+08:00' },
  { id: 3, name: '备用 Sub2API', type: 'sub2api', url: 'https://backup.example.com', status: false, accountCount: 0, lastCollectedAt: null },
]

export const accounts: Account[] = [
  {
    id: 647,
    platformId: 1,
    platformName: '云眠 New API',
    platformType: 'newapi',
    displayName: '主账号',
    loginName: '2696775653@qq.com',
    balance: 2.934282,
    frozenBalance: 0,
    quota: 1467141,
    usedQuota: 70135359,
    quotaUnit: 'USD',
    requestCount: 33374,
    credentialStatus: 'VALID',
    lastCollectStatus: 'SUCCESS',
    lastCollectedAt: '2026-09-30T11:52:00+08:00',
    nextCollectAt: '2026-09-30T12:02:00+08:00',
  },
  {
    id: 648,
    platformId: 1,
    platformName: '云眠 New API',
    platformType: 'newapi',
    displayName: '测试账号',
    loginName: 'monitor-test@example.com',
    balance: 8.42,
    frozenBalance: 0,
    quota: 4210000,
    usedQuota: 12800000,
    quotaUnit: 'USD',
    requestCount: 8241,
    credentialStatus: 'VALID',
    lastCollectStatus: 'SUCCESS',
    lastCollectedAt: '2026-09-30T11:50:00+08:00',
    nextCollectAt: '2026-09-30T12:00:00+08:00',
  },
  {
    id: 101,
    platformId: 2,
    platformName: 'Codex Sub2API',
    platformType: 'sub2api',
    displayName: 'Codex 主账号',
    loginName: '2696775653@qq.com',
    balance: 36.8,
    frozenBalance: 1.2,
    quota: 0,
    usedQuota: 0,
    quotaUnit: 'USD',
    requestCount: 0,
    credentialStatus: 'VALID',
    lastCollectStatus: 'SUCCESS',
    lastCollectedAt: '2026-09-30T11:51:00+08:00',
    nextCollectAt: '2026-09-30T12:01:00+08:00',
  },
]

export const metricSeries: Record<number, MetricPoint[]> = {
  647: [
    { time: '09-24', balance: 6.8, usedQuota: 54.2 },
    { time: '09-25', balance: 6.1, usedQuota: 58.8 },
    { time: '09-26', balance: 5.4, usedQuota: 63.5 },
    { time: '09-27', balance: 4.9, usedQuota: 68.1 },
    { time: '09-28', balance: 4.2, usedQuota: 72.7 },
    { time: '09-29', balance: 3.6, usedQuota: 76.4 },
    { time: '09-30', balance: 2.93, usedQuota: 79.9 },
  ],
  648: [
    { time: '09-24', balance: 14.2, usedQuota: 8.1 },
    { time: '09-25', balance: 13.4, usedQuota: 9.2 },
    { time: '09-26', balance: 12.1, usedQuota: 10.7 },
    { time: '09-27', balance: 10.9, usedQuota: 12.3 },
    { time: '09-28', balance: 10.2, usedQuota: 13.1 },
    { time: '09-29', balance: 9.4, usedQuota: 14.6 },
    { time: '09-30', balance: 8.42, usedQuota: 15.8 },
  ],
  101: [
    { time: '09-24', balance: 52.4, usedQuota: 18.4 },
    { time: '09-25', balance: 49.8, usedQuota: 22.1 },
    { time: '09-26', balance: 46.2, usedQuota: 27.8 },
    { time: '09-27', balance: 43.9, usedQuota: 32.4 },
    { time: '09-28', balance: 41.1, usedQuota: 38.9 },
    { time: '09-29', balance: 38.7, usedQuota: 44.1 },
    { time: '09-30', balance: 36.8, usedQuota: 49.6 },
  ],
}

export const channels: Channel[] = [
  { id: '12', name: '稳定分组(动态倍率)', platform: 'openai', ratio: 0.16, baseRatio: null, status: 'active', accountName: 'Codex 主账号' },
  { id: '19', name: 'Claude Kiro', platform: 'anthropic', ratio: 0.1, baseRatio: null, status: 'active', accountName: 'Codex 主账号' },
  { id: '22', name: 'ClaudeCode MAX20', platform: 'anthropic', ratio: 1.25, baseRatio: null, status: 'active', accountName: 'Codex 主账号' },
  { id: 'codex-特价', name: 'codex-特价', platform: 'openai', ratio: 0.12, baseRatio: 0.12, status: 'ACTIVE', accountName: '主账号' },
  { id: 'codex-pro-旗舰', name: 'codex-pro-旗舰', platform: 'openai', ratio: 0.25, baseRatio: 0.25, status: 'ACTIVE', accountName: '主账号' },
  { id: '国产-旗舰', name: '国产-旗舰', platform: 'openai', ratio: 0.7, baseRatio: 0.7, status: 'ACTIVE', accountName: '主账号' },
  { id: 'gemini-平价', name: 'gemini-平价', platform: 'gemini', ratio: 0.3, baseRatio: 0.3, status: 'ACTIVE', accountName: '主账号' },
]

export const changes: ChangeEvent[] = [
  { id: 'evt-1', type: 'RATE_CHANGED', entity: 'codex-特价', field: 'ratio', oldValue: '0.15', newValue: '0.12', severity: 'INFO', detectedAt: '2026-09-30T11:52:00+08:00', message: '倍率下降，成本降低' },
  { id: 'evt-2', type: 'GROUP_ADDED', entity: 'gpt-image-2 原生4K', field: '', oldValue: 'null', newValue: '2.1', severity: 'INFO', detectedAt: '2026-09-30T11:50:00+08:00', message: '发现新渠道' },
  { id: 'evt-3', type: 'STATUS_CHANGED', entity: 'Claude Kiro', field: 'status', oldValue: 'inactive', newValue: 'active', severity: 'WARNING', detectedAt: '2026-09-30T11:48:00+08:00', message: '渠道恢复可用' },
  { id: 'evt-4', type: 'GROUP_REMOVED', entity: '视频-测试', field: 'is_active', oldValue: 'true', newValue: 'false', severity: 'WARNING', detectedAt: '2026-09-30T11:45:00+08:00', message: '渠道已从上游返回列表消失' },
  { id: 'evt-5', type: 'BASE_RATE_CHANGED', entity: '稳定分组(动态倍率)', field: 'base_ratio', oldValue: '0.18', newValue: '0.16', severity: 'INFO', detectedAt: '2026-09-30T11:42:00+08:00', message: '基础倍率变化' },
]