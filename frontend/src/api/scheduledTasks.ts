import { apiRequest } from './client'
import type { ScheduledTask, ScheduledTaskHandler } from '../types'

export interface ScheduledTaskInput {
  taskName: string
  taskCode: string
  cronExpression: string
  timezone: string
  enabled: boolean
  description: string | null
}

interface ScheduledTaskDto {
  id: string | number
  taskName: string
  taskCode: string
  handlerName: string
  cronExpression: string
  timezone: string
  enabled: boolean
  description: string | null
  lastRunAt: string | null
  lastRunStatus: string | null
  lastRunMessage: string | null
}

function mapScheduledTask(row: ScheduledTaskDto): ScheduledTask {
  return {
    id: Number(row.id),
    taskName: row.taskName,
    taskCode: row.taskCode,
    handlerName: row.handlerName,
    cronExpression: row.cronExpression,
    timezone: row.timezone,
    enabled: row.enabled,
    description: row.description ?? null,
    lastRunAt: row.lastRunAt ?? null,
    lastRunStatus: row.lastRunStatus === "RUNNING" || row.lastRunStatus === "SUCCESS" || row.lastRunStatus === "FAILED" ? row.lastRunStatus : null,
    lastRunMessage: row.lastRunMessage ?? null,
  }
}

export async function listScheduledTasks(): Promise<ScheduledTask[]> {
  const rows = await apiRequest<ScheduledTaskDto[]>('/api/v1/scheduled-tasks')
  return rows.map(mapScheduledTask)
}

export async function listScheduledTaskHandlers(): Promise<ScheduledTaskHandler[]> {
  return apiRequest<ScheduledTaskHandler[]>('/api/v1/scheduled-tasks/handlers')
}

export async function createScheduledTask(input: ScheduledTaskInput): Promise<ScheduledTask> {
  const row = await apiRequest<ScheduledTaskDto>('/api/v1/scheduled-tasks', {
    method: 'POST',
    body: JSON.stringify(input),
  })
  return mapScheduledTask(row)
}

export async function updateScheduledTask(id: number, input: ScheduledTaskInput): Promise<ScheduledTask> {
  const row = await apiRequest<ScheduledTaskDto>(`/api/v1/scheduled-tasks/${id}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  })
  return mapScheduledTask(row)
}

export async function deleteScheduledTask(id: number): Promise<void> {
  await apiRequest<void>(`/api/v1/scheduled-tasks/${id}`, { method: 'DELETE' })
}

export async function triggerScheduledTask(id: number): Promise<void> {
  await apiRequest<void>(`/api/v1/scheduled-tasks/${id}/trigger`, { method: 'POST' })
}
