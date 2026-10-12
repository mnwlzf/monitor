<template>
  <section class="admin-page">
    <el-card shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>消息存档</h3>
            <p>
              记录「谁在什么时候说了什么、机器人回了什么、当时是什么身份」，用于事后查证。
              下面按<strong>会话</strong>把消息串成一段完整对话，时间正序，读起来是连贯的。
            </p>
          </div>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>

      <el-alert
        v-if="stats && !stats.enabled"
        type="warning"
        :closable="false"
        show-icon
        title="消息存档当前已关闭"
        description="把 MONITOR_BOT_ARCHIVE_ENABLED 设为 true 后重启即可开启。"
      />

      <el-descriptions v-if="stats" :column="4" border size="small" class="archive-summary">
        <el-descriptions-item label="已存档">{{ stats.total }} 条</el-descriptions-item>
        <el-descriptions-item label="最早记录">{{ formatTime(stats.earliest) }}</el-descriptions-item>
        <el-descriptions-item label="保留天数">
          {{ stats.retentionDays > 0 ? `${stats.retentionDays} 天` : '永久保留' }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="stats.enabled ? 'success' : 'info'" size="small" effect="plain">
            {{ stats.enabled ? '运行中' : '已关闭' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <div class="archive-filters">
        <el-input
          v-model="query.keyword"
          placeholder="搜索消息内容"
          clearable
          style="width: 240px"
          @keyup.enter="load"
        />
        <el-input v-model="query.userId" placeholder="QQ 号" clearable style="width: 140px" />
        <el-input v-model="query.groupId" placeholder="群号" clearable style="width: 140px" />
        <el-date-picker
          v-model="range"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 360px"
        />
        <el-button type="primary" @click="load">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <el-skeleton v-if="loading && conversations.length === 0" :rows="6" animated />

      <el-empty v-else-if="conversations.length === 0" description="没有符合条件的记录" />

      <div v-else class="conversation-list" v-loading="loading">
        <section v-for="conv in conversations" :key="conv.key" class="conversation">
          <header class="conversation-head">
            <div class="conversation-title">
              <el-tag :type="conv.tagType" size="small" effect="plain">{{ conv.channelLabel }}</el-tag>
              <span class="conversation-name">{{ conv.title }}</span>
            </div>
            <div class="conversation-meta">
              <span>{{ conv.messages.length }} 条</span>
              <span>{{ formatTime(conv.messages[0].createdAt) }} ~ {{ formatTime(conv.lastAt) }}</span>
            </div>
          </header>

          <div class="chat-stream">
            <div
              v-for="m in conv.messages"
              :key="m.id"
              class="chat-row"
              :class="m.direction === 'IN' ? 'chat-in' : 'chat-out'"
            >
              <div class="chat-avatar">{{ m.direction === 'IN' ? shortId(m.userId) : '🤖' }}</div>
              <div class="chat-main">
                <div class="chat-head">
                  <span class="chat-name">{{ m.direction === 'IN' ? `QQ ${m.userId}` : '机器人' }}</span>
                  <el-tag
                    v-if="m.direction === 'IN'"
                    :type="roleTag(m.senderRole)"
                    size="small"
                    effect="plain"
                  >{{ roleLabel(m.senderRole) }}</el-tag>
                  <el-tag v-else size="small" effect="plain">{{ personaLabel(m.persona) }}</el-tag>
                  <el-tag v-if="m.contentKind === 'IMAGE'" size="small" type="warning" effect="plain">图片</el-tag>
                  <el-tag v-else-if="m.contentKind === 'COMMAND'" size="small" effect="plain">命令</el-tag>
                  <span class="chat-time">{{ formatTime(m.createdAt) }}</span>
                </div>
                <div class="chat-bubble">{{ m.content }}</div>
              </div>
            </div>
          </div>
        </section>
      </div>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { getBotArchiveStats, searchBotMessages } from '../api/settings'
import type { BotMessage, BotMessageArchiveStats } from '../types'

const loading = ref(false)
const messages = ref<BotMessage[]>([])
const stats = ref<BotMessageArchiveStats | null>(null)
const range = ref<[Date, Date] | null>(null)

const query = reactive({
  keyword: '',
  userId: '',
  groupId: '',
})

interface Conversation {
  key: string
  channelLabel: string
  tagType: 'primary' | 'warning' | 'info'
  title: string
  lastAt: string
  messages: BotMessage[]
}

/**
 * 把扁平的消息列表按会话分组，组内按时间正序。
 *
 * <p>接口按 id 倒序返回（最新在前），直接铺成表格看不出「一问一答」的连贯性，
 * 所以这里翻成正序、再按会话切开。</p>
 */
const conversations = computed<Conversation[]>(() => {
  const buckets = new Map<string, BotMessage[]>()
  const ordered = [...messages.value].sort((a, b) => a.id - b.id)
  for (const message of ordered) {
    const key = conversationKey(message)
    const bucket = buckets.get(key)
    if (bucket) {
      bucket.push(message)
    } else {
      buckets.set(key, [message])
    }
  }
  return [...buckets.entries()]
    .map(([key, list]) => toConversation(key, list))
    // 会话之间：最近有消息的排前面
    .sort((a, b) => b.messages[b.messages.length - 1].id - a.messages[a.messages.length - 1].id)
})

function conversationKey(message: BotMessage): string {
  if (message.messageType === 'group') return `group-${message.groupId}`
  // 有群号 = 群临时会话；没有 = 好友私聊
  if (message.groupId != null) return `temp-${message.groupId}-${message.userId}`
  return `user-${message.userId}`
}

function toConversation(key: string, list: BotMessage[]): Conversation {
  const first = list[0]
  let channelLabel = '好友私聊'
  let tagType: Conversation['tagType'] = 'info'
  let title = `QQ ${first.userId}`

  if (first.messageType === 'group') {
    channelLabel = '群聊'
    tagType = 'primary'
    const speakers = new Set(list.filter(m => m.direction === 'IN').map(m => m.userId))
    title = `群 ${first.groupId}（${speakers.size} 人发言）`
  } else if (first.groupId != null) {
    channelLabel = '群临时会话'
    tagType = 'warning'
    title = `群 ${first.groupId} · QQ ${first.userId}`
  }

  return {
    key,
    channelLabel,
    tagType,
    title,
    lastAt: list[list.length - 1].createdAt,
    messages: list,
  }
}

function toNumber(value: string): number | undefined {
  const trimmed = value.trim()
  if (!trimmed) return undefined
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : undefined
}

async function load() {
  loading.value = true
  try {
    const [list, overview] = await Promise.all([
      searchBotMessages({
        keyword: query.keyword.trim() || undefined,
        userId: toNumber(query.userId),
        groupId: toNumber(query.groupId),
        from: range.value?.[0]?.toISOString(),
        to: range.value?.[1]?.toISOString(),
        limit: 300,
      }),
      getBotArchiveStats(),
    ])
    messages.value = list
    stats.value = overview
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载消息存档失败')
  } finally {
    loading.value = false
  }
}

function reset() {
  query.keyword = ''
  query.userId = ''
  query.groupId = ''
  range.value = null
  load()
}

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}

/** 头像里放 QQ 后四位，太长会把气泡挤变形。 */
function shortId(userId: number | null): string {
  const text = String(userId ?? '?')
  return text.length <= 4 ? text : text.slice(-4)
}

function roleLabel(role: string | null): string {
  if (role === 'ADMIN') return '管理员'
  if (role === 'USER') return '平台用户'
  return '陌生人'
}

function roleTag(role: string | null): 'success' | 'warning' | 'info' {
  if (role === 'ADMIN') return 'success'
  if (role === 'USER') return 'warning'
  return 'info'
}

function personaLabel(persona: string | null): string {
  if (persona === 'MONITOR_ADMIN') return '监控助手（管理员）'
  if (persona === 'MONITOR_USER') return '监控助手（普通用户）'
  return '普通聊天'
}

onMounted(load)
</script>

<style scoped>
.archive-summary {
  margin-bottom: 14px;
}

.archive-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 16px;
}

.conversation-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.conversation {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  overflow: hidden;
}

.conversation-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 14px;
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.conversation-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.conversation-name {
  font-size: 14px;
  font-weight: 600;
}

.conversation-meta {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.chat-stream {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
}

.chat-row {
  display: flex;
  gap: 10px;
  max-width: 82%;
}

.chat-in {
  align-self: flex-start;
}

.chat-out {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.chat-avatar {
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
}

.chat-main {
  min-width: 0;
}

.chat-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.chat-out .chat-head {
  justify-content: flex-end;
}

.chat-name {
  font-weight: 600;
  color: var(--el-text-color-regular);
}

.chat-time {
  color: var(--el-text-color-placeholder);
}

.chat-bubble {
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--el-fill-color-light);
}

.chat-out .chat-bubble {
  background: var(--el-color-primary-light-9);
}
</style>
