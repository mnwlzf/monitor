<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div>
        <p class="admin-eyebrow">SCHEDULED TASKS</p>
        <h2>定时任务</h2>
        <p>页面管理任务类型、Cron 表达式和启用状态，修改后即时生效，无需重启。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增任务</el-button>
    </div>

    <el-card shadow="never" class="admin-card">
      <el-table :data="tasks" v-loading="loading" stripe empty-text="暂无定时任务">
        <el-table-column prop="taskName" label="任务名称" min-width="160" />
        <el-table-column prop="handlerName" label="任务类型" min-width="140" />
        <el-table-column prop="cronExpression" label="Cron 表达式" min-width="160" />
        <el-table-column prop="timezone" label="时区" width="130" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近执行" min-width="200">
          <template #default="{ row }">
            <div class="admin-task-run">
              <span v-if="row.lastRunAt">{{ formatDate(row.lastRunAt) }}</span>
              <span v-else class="muted">—</span>
              <el-tag v-if="row.lastRunStatus" size="small" :type="statusType(row.lastRunStatus)">
                {{ row.lastRunStatus }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :icon="VideoPlay" @click="trigger(asTask(row))">执行</el-button>
            <el-button size="small" @click="openEdit(asTask(row))">编辑</el-button>
            <el-button size="small" type="primary" plain @click="toggle(asTask(row))">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <el-popconfirm title="确认删除该任务？" @confirm="remove(asTask(row))">
              <template #reference>
                <el-button size="small" type="danger" plain :icon="Delete">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="showForm" :title="editing ? '编辑定时任务' : '新增定时任务'" width="520px">
      <el-form label-width="100px">
        <el-form-item label="任务名称" required>
          <el-input v-model="form.taskName" placeholder="例如：全量账号采集" />
        </el-form-item>
        <el-form-item label="任务类型" required>
          <el-select v-model="form.taskCode" placeholder="选择任务处理器" style="width: 100%">
            <el-option v-for="handler in handlers" :key="handler.code" :label="handler.name" :value="handler.code" />
          </el-select>
          <small class="muted">{{ selectedHandlerDescription }}</small>
        </el-form-item>
        <el-form-item label="Cron 表达式" required>
          <el-input v-model="form.cronExpression" placeholder="0 */10 * * * ?" />
          <small class="muted">秒级六段格式，例如每 10 分钟：0 */10 * * * ?</small>
        </el-form-item>
        <el-form-item label="时区">
          <el-input v-model="form.timezone" placeholder="Asia/Shanghai" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showForm = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Delete, Plus, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { createScheduledTask, deleteScheduledTask, listScheduledTaskHandlers, listScheduledTasks, triggerScheduledTask, updateScheduledTask, type ScheduledTaskInput } from '../api/scheduledTasks'
import type { ScheduledTask, ScheduledTaskHandler } from '../types'

const tasks = ref<ScheduledTask[]>([])
const handlers = ref<ScheduledTaskHandler[]>([])
const loading = ref(false)
const saving = ref(false)
const showForm = ref(false)
const editing = ref<ScheduledTask | null>(null)
const form = reactive<ScheduledTaskInput>({
  taskName: '',
  taskCode: '',
  cronExpression: '',
  timezone: 'Asia/Shanghai',
  enabled: true,
  description: null,
})

function asTask(row: unknown): ScheduledTask {
  return row as ScheduledTask
}

const selectedHandlerDescription = computed(() => {
  const handler = handlers.value.find(item => item.code === form.taskCode)
  return handler ? handler.description : ''
})

function openCreate() {
  editing.value = null
  Object.assign(form, {
    taskName: '',
    taskCode: handlers.value[0]?.code ?? '',
    cronExpression: '',
    timezone: 'Asia/Shanghai',
    enabled: true,
    description: null,
  })
  showForm.value = true
}

function openEdit(task: ScheduledTask) {
  editing.value = task
  Object.assign(form, {
    taskName: task.taskName,
    taskCode: task.taskCode,
    cronExpression: task.cronExpression,
    timezone: task.timezone,
    enabled: task.enabled,
    description: task.description,
  })
  showForm.value = true
}

async function submit() {
  if (!form.taskName || !form.taskCode || !form.cronExpression) {
    ElMessage.warning('请填写任务名称、类型和 Cron 表达式')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      const updated = await updateScheduledTask(editing.value.id, form)
      const index = tasks.value.findIndex(item => item.id === updated.id)
      if (index >= 0) tasks.value[index] = updated
      ElMessage.success('任务已更新')
    } else {
      tasks.value.push(await createScheduledTask(form))
      ElMessage.success('任务已创建')
    }
    showForm.value = false
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggle(task: ScheduledTask) {
  try {
    const updated = await updateScheduledTask(task.id, {
      taskName: task.taskName,
      taskCode: task.taskCode,
      cronExpression: task.cronExpression,
      timezone: task.timezone,
      enabled: !task.enabled,
      description: task.description,
    })
    const index = tasks.value.findIndex(item => item.id === task.id)
    if (index >= 0) tasks.value[index] = updated
    ElMessage.success(updated.enabled ? '任务已启用' : '任务已停用')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  }
}

async function remove(task: ScheduledTask) {
  try {
    await deleteScheduledTask(task.id)
    tasks.value = tasks.value.filter(item => item.id !== task.id)
    ElMessage.success('任务「' + task.taskName + '」已删除')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

async function trigger(task: ScheduledTask) {
  try {
    await triggerScheduledTask(task.id)
    ElMessage.success('任务「' + task.taskName + '」已触发')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '触发失败')
  }
}

async function load() {
  loading.value = true
  try {
    const [taskRows, handlerRows] = await Promise.all([listScheduledTasks(), listScheduledTaskHandlers()])
    tasks.value = taskRows
    handlers.value = handlerRows
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function statusType(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'warning'
  return 'info'
}

function formatDate(value: string) {
  const date = new Date(value)
  return date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>