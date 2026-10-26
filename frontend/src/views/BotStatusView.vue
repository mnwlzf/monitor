<template>
  <section class="admin-page">
    <el-card shadow="never" class="admin-card" v-loading="loading">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>机器人状态</h3>
            <p>
              机器人现在在干什么：开关、大模型、OneBot 连接、收发计数，以及<strong>最近哪些消息没被回复、为什么</strong>。
              计数与最近事件是进程内统计，重启后清零；要看历史消息用「消息存档」页。
            </p>
          </div>
          <div class="status-actions">
            <el-switch v-model="autoRefresh" active-text="自动刷新" />
            <el-button :icon="Refresh" :loading="loading" @click="refreshAll">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

      <template v-if="status">
        <div class="status-cards">
          <div class="status-card">
            <div class="status-card-title">总开关</div>
            <el-tag :type="status.enabled ? 'success' : 'info'" effect="plain">
              {{ status.enabled ? '已开启' : '已关闭' }}
            </el-tag>
            <div class="status-card-sub">已运行 {{ formatUptime(status.uptimeSeconds) }}</div>
            <div class="status-card-sub">启动于 {{ formatTime(status.startedAt) }}</div>
          </div>

          <div class="status-card">
            <div class="status-card-title">大模型</div>
            <el-tag :type="status.llm.available ? 'success' : 'danger'" effect="plain">
              {{ status.llm.available ? '可用' : '不可用' }}
            </el-tag>
            <div class="status-card-sub">{{ status.llm.model || '未配置' }}</div>
            <div class="status-card-sub">{{ status.llm.baseUrl || '' }}</div>
          </div>

          <div class="status-card">
            <div class="status-card-title">OneBot / NapCat</div>
            <el-tag :type="onebotTagType" effect="plain">{{ onebotText }}</el-tag>
            <div class="status-card-sub">
              {{ status.onebot.nickname || '—' }}{{ status.onebot.selfId ? ` (${status.onebot.selfId})` : '' }}
            </div>
            <div class="status-card-sub">{{ status.onebot.baseUrl || '未配置地址' }}</div>
          </div>

          <div class="status-card">
            <div class="status-card-title">生图</div>
            <el-tag :type="status.imageGen.usable ? 'success' : 'info'" effect="plain">
              {{ status.imageGen.usable ? '可用' : '不可用' }}
            </el-tag>
            <div class="status-card-sub">{{ status.imageGen.model || '未配置' }}</div>
            <div class="status-card-sub">限频 {{ status.imageGen.maxPerMinute }} 张 / 分钟</div>
          </div>
        </div>

        <div class="status-section napcat-section">
          <div class="napcat-heading">
            <div>
              <h4>NapCat 登录与实时日志</h4>
              <p>直接查看 NapCat 的实时运行日志；掉线后无需 SSH 隧道，在这里扫码即可重新登录。</p>
            </div>
            <div class="napcat-heading-actions">
              <el-tag :type="napcatStatusTagType" effect="plain">{{ napcatStatusText }}</el-tag>
              <el-button
                size="small"
                :loading="napcatRefreshing"
                :disabled="!napcatStatus?.configured || napcatStatus?.login"
                @click="refreshNapCatQrCode"
              >刷新二维码</el-button>
            </div>
          </div>

          <el-alert v-if="napcatError" type="error" :closable="false" show-icon :title="napcatError" />

          <div v-if="napcatStatus" class="napcat-login-panel">
            <div class="napcat-login-meta">
              <el-alert
                v-if="napcatStatus.error"
                type="error"
                :closable="false"
                show-icon
                :title="napcatStatus.error"
              />
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="QQ 登录">
                  <el-tag :type="napcatStatus.login ? 'success' : 'warning'" effect="plain" size="small">
                    {{ napcatStatus.login ? '已登录' : '未登录' }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="登录阶段">{{ napcatPhaseLabel }}</el-descriptions-item>
                <el-descriptions-item label="WebUI">
                  {{ napcatStatus.configured ? (napcatStatus.reachable ? '可达' : '不可达') : '未配置' }}
                </el-descriptions-item>
                <el-descriptions-item label="最近检查">{{ formatTime(napcatStatus.checkedAt) }}</el-descriptions-item>
              </el-descriptions>
              <el-alert
                v-if="napcatStatus.loginError"
                type="warning"
                :closable="false"
                show-icon
                :title="napcatStatus.loginError"
              />
            </div>

            <div v-if="!napcatStatus.login" class="napcat-qr-wrap">
              <div v-if="qrDataUrl" class="napcat-qr-box">
                <img :src="qrDataUrl" alt="QQ 登录二维码" class="napcat-qr-image" />
              </div>
              <el-empty v-else description="等待 NapCat 生成二维码" :image-size="72" />
              <div class="napcat-qr-tip">
                <p>使用手机 QQ 扫码，并在手机上确认登录。二维码过期时点右上角「刷新二维码」。</p>
                <el-input
                  v-if="napcatStatus.qrCodeUrl"
                  :model-value="napcatStatus.qrCodeUrl"
                  readonly
                  size="small"
                  class="napcat-qr-url"
                >
                  <template #append>
                    <el-button @click="copyQrUrl">复制链接</el-button>
                  </template>
                </el-input>
              </div>
            </div>
            <div v-else class="napcat-online">
              <el-tag type="success" effect="dark" size="large">QQ 在线，无需扫码</el-tag>
              <span>如果 QQ 掉线，页面会自动显示新的登录二维码。</span>
            </div>
          </div>

          <div class="napcat-log-toolbar">
            <el-select v-model="logLevel" size="small" class="napcat-level-select">
              <el-option label="全部级别" value="all" />
              <el-option label="ERROR" value="error" />
              <el-option label="WARN" value="warn" />
              <el-option label="INFO" value="info" />
              <el-option label="DEBUG" value="debug" />
            </el-select>
            <el-input v-model="logKeyword" size="small" clearable placeholder="搜索日志..." class="napcat-log-search" />
            <el-switch v-model="autoScroll" active-text="自动滚动" />
            <span class="napcat-log-meta">
              {{ filteredLogs.length }} / {{ napcatLogs?.lines.length ?? 0 }} 行
              <template v-if="napcatLogs?.updatedAt"> · {{ formatTime(napcatLogs.updatedAt) }}</template>
            </span>
          </div>
          <div ref="logViewport" class="napcat-log-console">
            <el-empty v-if="filteredLogs.length === 0" description="暂无日志" :image-size="60" />
            <div v-for="(line, index) in filteredLogs" :key="`${line.time}-${index}`" class="napcat-log-line">
              <span class="napcat-log-time">{{ line.time }}</span>
              <span class="napcat-log-level" :class="`napcat-level-${line.level.toLowerCase()}`">{{ line.level.toUpperCase() }}</span>
              <span class="napcat-log-message">{{ line.message }}</span>
            </div>
          </div>
          <div v-if="napcatLogError" class="napcat-log-error">{{ napcatLogError }}</div>
          <div v-else-if="napcatLogs && !napcatLogs.connected" class="napcat-log-error">
            {{ napcatLogs.error || '正在连接 NapCat 日志流…' }}
          </div>
        </div>

        <el-descriptions :column="4" border size="small" class="status-counters">
          <el-descriptions-item label="收到">{{ status.counters.inbound }}</el-descriptions-item>
          <el-descriptions-item label="已回复">{{ status.counters.replied }}</el-descriptions-item>
          <el-descriptions-item label="未回复">
            <span :class="{ 'status-warn': status.counters.dropped > 0 }">{{ status.counters.dropped }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="出错">
            <span :class="{ 'status-danger': status.counters.failed > 0 }">{{ status.counters.failed }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="最近收到" :span="2">{{ formatTime(status.counters.lastInboundAt) }}</el-descriptions-item>
          <el-descriptions-item label="最近回复" :span="2">{{ formatTime(status.counters.lastOutboundAt) }}</el-descriptions-item>
        </el-descriptions>

        <div class="status-section">
          <h4>
            正在处理
            <el-tag v-if="status.activeTurns.length" type="warning" effect="plain" size="small">
              {{ status.activeTurns.length }} 条
            </el-tag>
          </h4>
          <el-empty v-if="status.activeTurns.length === 0" description="当前空闲" :image-size="60" />
          <div v-else class="turn-grid">
            <div v-for="(turn, index) in status.activeTurns" :key="index" class="turn-card">
              <div class="turn-card-head">
                <span class="turn-target">{{ turn.groupId ?? '私聊' }} · {{ turn.userId ?? '—' }}</span>
                <el-tag size="small" type="warning" effect="plain">{{ channelLabel(turn.channel) }}</el-tag>
              </div>
              <div class="turn-card-body">{{ turn.text || '（无文字内容）' }}</div>
              <div class="turn-card-foot">
                <span>开始 {{ formatTime(turn.at) }}</span>
                <span class="turn-elapsed">已用 {{ formatMs(elapsedSince(turn.at)) }}</span>
              </div>
            </div>
          </div>
        </div>

        <div class="status-section">
          <h4>最近对话</h4>
          <el-empty v-if="status.recentTurns.length === 0" description="暂无" :image-size="60" />
          <el-table v-else :data="status.recentTurns" size="small" border>
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ formatTime(row.at) }}</template>
            </el-table-column>
            <el-table-column label="通道" width="110">
              <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
            </el-table-column>
            <el-table-column label="群 / QQ" width="180">
              <template #default="{ row }">{{ row.groupId ?? '私聊' }} / {{ row.userId ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="用户说" prop="question" show-overflow-tooltip />
            <el-table-column label="机器人答" prop="answer" show-overflow-tooltip />
            <el-table-column label="耗时" width="90" align="right">
              <template #default="{ row }">{{ formatMs(row.elapsedMs) }}</template>
            </el-table-column>
          </el-table>
        </div>

        <div class="status-section">
          <h4>未回复原因分布</h4>
          <el-empty v-if="reasonEntries.length === 0" description="本次启动以来没有被丢弃的消息" :image-size="60" />
          <div v-else class="status-tags">
            <el-tag v-for="[reason, count] in reasonEntries" :key="reason" type="warning" effect="plain">
              {{ reason }} × {{ count }}
            </el-tag>
          </div>
        </div>

        <div class="status-section">
          <h4>最近未回复</h4>
          <el-empty v-if="status.recentDrops.length === 0" description="暂无" :image-size="60" />
          <el-table v-else :data="status.recentDrops" size="small" border>
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ formatTime(row.at) }}</template>
            </el-table-column>
            <el-table-column label="通道" width="120">
              <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
            </el-table-column>
            <el-table-column label="来源群" width="140">
              <template #default="{ row }">{{ row.groupId ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="QQ" width="140">
              <template #default="{ row }">{{ row.userId ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="原因" prop="reason" />
          </el-table>
        </div>

        <div class="status-section">
          <h4>最近错误</h4>
          <el-empty v-if="status.recentErrors.length === 0" description="暂无错误" :image-size="60" />
          <el-table v-else :data="status.recentErrors" size="small" border>
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ formatTime(row.at) }}</template>
            </el-table-column>
            <el-table-column label="QQ" width="140">
              <template #default="{ row }">{{ row.userId ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="错误" prop="message" />
          </el-table>
        </div>
      </template>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
import { getBotStatus, getNapCatLogs, getNapCatStatus, refreshNapCatQrCode as requestNapCatQrRefresh } from '../api/settings'
import type { BotStatus, NapCatLogs, NapCatStatus } from '../types'

/** 自动刷新间隔（毫秒）。状态页每次刷新会顺带探活一次 OneBot，5 秒足够且不打扰。 */
const REFRESH_INTERVAL = 5000

const status = ref<BotStatus | null>(null)
/** 每秒跳动一次，让「正在处理」的耗时实时走。 */
const now = ref(Date.now())
let clock: number | undefined
const loading = ref(false)
const error = ref('')
const autoRefresh = ref(true)
let timer: number | undefined
let napcatLogTimer: number | undefined

const napcatStatus = ref<NapCatStatus | null>(null)
const napcatLogs = ref<NapCatLogs | null>(null)
const napcatError = ref('')
const napcatLogError = ref('')
const napcatRefreshing = ref(false)
const qrDataUrl = ref('')
const logLevel = ref('all')
const logKeyword = ref('')
const autoScroll = ref(true)
const logViewport = ref<HTMLElement | null>(null)
let logRequestPending = false

const onebotText = computed(() => {
  const onebot = status.value?.onebot
  if (!onebot) return '—'
  if (!onebot.configured) return '未配置'
  if (!onebot.reachable) return '连不上'
  return onebot.online ? '在线' : '离线'
})

const onebotTagType = computed(() => {
  const onebot = status.value?.onebot
  if (!onebot || !onebot.configured || !onebot.reachable) return 'danger'
  return onebot.online ? 'success' : 'warning'
})

const reasonEntries = computed(() => Object.entries(status.value?.dropReasons ?? {}))

const napcatStatusText = computed(() => {
  const current = napcatStatus.value
  if (!current) return '未加载'
  if (!current.configured) return '未配置'
  if (!current.reachable) return '不可达'
  if (current.login) return '已登录'
  if (current.offline) return '离线'
  return '等待登录'
})

const napcatStatusTagType = computed(() => {
  const current = napcatStatus.value
  if (!current || !current.configured || !current.reachable) return 'danger'
  if (current.login) return 'success'
  return 'warning'
})

const napcatPhaseLabel = computed(() => {
  const phase = napcatStatus.value?.loginPhase ?? ''
  const labels: Record<string, string> = {
    ready: '已登录',
    waiting_qrcode: '等待扫码',
    qrcode_scanned: '已扫码，等待确认',
    initializing: '正在启动',
    reconnecting: '正在重连',
    offline: '离线',
  }
  return labels[phase] || phase || '—'
})

const filteredLogs = computed(() => {
  const lines = napcatLogs.value?.lines ?? []
  const level = logLevel.value
  const keyword = logKeyword.value.trim().toLowerCase()
  return lines.filter((line) => {
    if (level !== 'all' && line.level.toLowerCase() !== level) return false
    if (keyword && !`${line.time} ${line.level} ${line.message}`.toLowerCase().includes(keyword)) return false
    return true
  })
})

async function load() {
  const showLoading = !status.value
  if (showLoading) loading.value = true
  try {
    status.value = await getBotStatus()
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    if (showLoading) loading.value = false
  }
  await loadNapCatStatus()
}

async function loadNapCatStatus() {
  try {
    napcatStatus.value = await getNapCatStatus()
    napcatError.value = ''
  } catch (e) {
    napcatError.value = e instanceof Error ? e.message : String(e)
  }
}

async function loadNapCatLogs() {
  if (logRequestPending || document.visibilityState === 'hidden') return
  logRequestPending = true
  try {
    const previousUpdatedAt = napcatLogs.value?.updatedAt
    const next = await getNapCatLogs(500)
    const changed = next.updatedAt !== previousUpdatedAt
    napcatLogs.value = next
    napcatLogError.value = ''
    if (changed && autoScroll.value) await scrollLogsToBottom()
  } catch (e) {
    napcatLogError.value = e instanceof Error ? e.message : String(e)
  } finally {
    logRequestPending = false
  }
}

async function refreshAll() {
  loading.value = true
  try {
    await Promise.all([load(), loadNapCatLogs()])
  } finally {
    loading.value = false
  }
}

async function refreshNapCatQrCode() {
  napcatRefreshing.value = true
  try {
    napcatStatus.value = await requestNapCatQrRefresh()
    napcatError.value = ''
    ElMessage.success('已请求 NapCat 刷新二维码')
  } catch (e) {
    napcatError.value = e instanceof Error ? e.message : String(e)
  } finally {
    napcatRefreshing.value = false
  }
}

async function scrollLogsToBottom() {
  await nextTick()
  const viewport = logViewport.value
  if (viewport) viewport.scrollTop = viewport.scrollHeight
}

async function copyQrUrl() {
  const url = napcatStatus.value?.qrCodeUrl
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('二维码链接已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择链接')
  }
}

async function renderQrCode(url: string | null | undefined) {
  if (!url || napcatStatus.value?.login) {
    qrDataUrl.value = ''
    return
  }
  try {
    qrDataUrl.value = await QRCode.toDataURL(url, {
      width: 280,
      margin: 1,
      errorCorrectionLevel: 'M',
      color: { dark: '#111827', light: '#ffffff' },
    })
  } catch (e) {
    qrDataUrl.value = ''
    napcatError.value = e instanceof Error ? `二维码生成失败：${e.message}` : '二维码生成失败'
  }
}

watch(() => napcatStatus.value?.qrCodeUrl, renderQrCode)
watch([logLevel, logKeyword], () => {
  if (autoScroll.value) void scrollLogsToBottom()
})

function startTimer() {
  stopTimer()
  if (autoRefresh.value) {
    timer = window.setInterval(load, REFRESH_INTERVAL)
    napcatLogTimer = window.setInterval(loadNapCatLogs, 2000)
  }
}

function stopTimer() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
  if (napcatLogTimer !== undefined) {
    window.clearInterval(napcatLogTimer)
    napcatLogTimer = undefined
  }
}

watch(autoRefresh, startTimer)

onMounted(() => {
  load()
  loadNapCatLogs()
  startTimer()
  clock = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  stopTimer()
  if (clock !== undefined) {
    window.clearInterval(clock)
    clock = undefined
  }
})

function channelLabel(channel: string) {
  if (channel === 'GROUP') return '群聊'
  if (channel === 'TEMP_SESSION') return '群临时会话'
  if (channel === 'PRIVATE') return '好友私聊'
  return channel
}

function formatTime(value: string | null | undefined) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

/** 从「开始时间」到现在过了多久（毫秒）。 */
function elapsedSince(at: string) {
  const started = new Date(at).getTime()
  if (Number.isNaN(started)) return 0
  return Math.max(0, now.value - started)
}

function formatMs(ms: number) {
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(1)} s`
}

function formatUptime(seconds: number) {
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时 ${minutes % 60} 分`
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`
}
</script>

<style scoped>
.status-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.status-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px 14px;
}

.status-card-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}

.status-card-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 6px;
  word-break: break-all;
}

.status-counters {
  margin-bottom: 8px;
}

.status-section {
  margin-top: 20px;
}

.status-section h4 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  font-size: 14px;
}

/* 正在处理：卡片流水线，左右并排、上下排列，能同时铺很多个 */
.turn-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.turn-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-left: 3px solid var(--el-color-warning);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
}

.turn-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.turn-target {
  font-size: 13px;
  font-weight: 600;
  word-break: break-all;
}

.turn-card-body {
  font-size: 13px;
  line-height: 1.5;
  max-height: 4.5em;
  overflow: hidden;
  word-break: break-all;
}

.turn-card-foot {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.turn-elapsed {
  color: var(--el-color-warning);
  font-weight: 600;
}

.status-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.status-warn {
  color: var(--el-color-warning);
  font-weight: 600;
}

.status-danger {
  color: var(--el-color-danger);
  font-weight: 600;
}

.napcat-section {
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-blank);
}

.napcat-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.napcat-heading h4 {
  margin-bottom: 4px;
}

.napcat-heading p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.napcat-heading-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.napcat-login-panel {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(280px, 0.8fr);
  gap: 16px;
  align-items: start;
  margin-top: 14px;
}

.napcat-login-meta {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.napcat-qr-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.napcat-qr-box {
  padding: 8px;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 2px 12px rgb(0 0 0 / 8%);
}

.napcat-qr-image {
  display: block;
  width: 220px;
  height: 220px;
  object-fit: contain;
}

.napcat-qr-tip {
  width: 100%;
}

.napcat-qr-tip p {
  margin: 0 0 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
  text-align: center;
}

.napcat-qr-url :deep(.el-input__inner) {
  font-size: 11px;
}

.napcat-online {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 180px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  border: 1px dashed var(--el-border-color-lighter);
  border-radius: 8px;
}

.napcat-log-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin: 18px 0 8px;
}

.napcat-level-select {
  width: 120px;
}

.napcat-log-search {
  width: 230px;
}

.napcat-log-meta {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.napcat-log-console {
  height: 420px;
  overflow: auto;
  padding: 10px 12px;
  border-radius: 8px;
  background: #111827;
  color: #d1d5db;
  font-family: ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  line-height: 1.55;
}

.napcat-log-line {
  display: grid;
  grid-template-columns: 142px 58px minmax(0, 1fr);
  gap: 8px;
  padding: 2px 0;
  border-bottom: 1px solid rgb(255 255 255 / 4%);
}

.napcat-log-time {
  color: #6b7280;
  white-space: nowrap;
}

.napcat-log-level {
  font-weight: 700;
  white-space: nowrap;
}

.napcat-level-error {
  color: #f87171;
}

.napcat-level-warn {
  color: #fbbf24;
}

.napcat-level-info {
  color: #60a5fa;
}

.napcat-level-debug {
  color: #9ca3af;
}

.napcat-log-message {
  min-width: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.napcat-log-error {
  margin-top: 8px;
  color: var(--el-color-warning);
  font-size: 12px;
}

@media (max-width: 900px) {
  .napcat-heading,
  .napcat-login-panel {
    grid-template-columns: 1fr;
    flex-direction: column;
  }

  .napcat-heading-actions {
    align-self: stretch;
    justify-content: space-between;
  }

  .napcat-log-line {
    grid-template-columns: 1fr auto;
  }

  .napcat-log-message {
    grid-column: 1 / -1;
  }
}
</style>
