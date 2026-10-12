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
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
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
          <el-table v-else :data="status.activeTurns" size="small" border>
            <el-table-column label="开始于" width="160">
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
            <el-table-column label="正在处理的内容" prop="text" show-overflow-tooltip />
          </el-table>
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
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getBotStatus } from '../api/settings'
import type { BotStatus } from '../types'

/** 自动刷新间隔（毫秒）。状态页每次刷新会顺带探活一次 OneBot，5 秒足够且不打扰。 */
const REFRESH_INTERVAL = 5000

const status = ref<BotStatus | null>(null)
const loading = ref(false)
const error = ref('')
const autoRefresh = ref(true)
let timer: number | undefined

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

async function load() {
  loading.value = true
  try {
    status.value = await getBotStatus()
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function startTimer() {
  stopTimer()
  if (autoRefresh.value) timer = window.setInterval(load, REFRESH_INTERVAL)
}

function stopTimer() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

watch(autoRefresh, startTimer)

onMounted(() => {
  load()
  startTimer()
})

onUnmounted(stopTimer)

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
  margin: 0 0 10px;
  font-size: 14px;
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
</style>
