<template>
  <section class="admin-page">
    <el-card shadow="never" class="admin-card" v-loading="loading">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>消息存档</h3>
            <p>
              记录「谁在什么时候说了什么、机器人回了什么、当时是什么身份」，用于事后查证。
              存档异步写入，不影响回复速度。
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

      <el-table :data="messages" size="small" class="archive-table" v-loading="loading">
        <template #empty>没有符合条件的记录</template>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="方向" width="80">
          <template #default="{ row }">
            <el-tag :type="row.direction === 'IN' ? 'info' : 'success'" size="small" effect="plain">
              {{ row.direction === 'IN' ? '收到' : '回复' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="170">
          <template #default="{ row }">
            <span v-if="row.messageType === 'group'">群 {{ row.groupId }} · QQ {{ row.userId }}</span>
            <span v-else>私聊 · QQ {{ row.userId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="身份" width="90">
          <template #default="{ row }">
            <el-tag :type="roleTag(row.senderRole)" size="small" effect="plain">
              {{ roleLabel(row.senderRole) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="420">
          <template #default="{ row }">
            <div class="archive-content">{{ row.content }}</div>
            <div class="archive-meta">
              <el-tag v-if="row.contentKind === 'IMAGE'" size="small" type="warning" effect="plain">图片</el-tag>
              <el-tag v-else-if="row.contentKind === 'COMMAND'" size="small" effect="plain">命令</el-tag>
              <span v-if="row.persona" class="archive-persona">人设 {{ personaLabel(row.persona) }}</span>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
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
  return value ? new Date(value).toLocaleString('zh-CN') : '-'
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
  margin-bottom: 14px;
}

.archive-table {
  width: 100%;
}

.archive-content {
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 160px;
  overflow-y: auto;
  line-height: 1.6;
}

.archive-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.archive-persona {
  color: #909399;
  font-size: 12px;
}
</style>