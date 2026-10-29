import { apiRequest } from './client'

export type OpsIncident = {
  id: number
  title: string
  severity: string
  status: string
  scopeType: string
  scopeId: string | null
  rootCauseCode: string | null
  impactSummary: string | null
  evidenceJson: string | null
  firstSeenAt: string | null
  lastSeenAt: string | null
  resolvedAt: string | null
  createdAt: string | null
  updatedAt: string | null
}

export type OpsIncidentEvent = {
  id: number
  eventType: string
  actor: string | null
  content: string | null
  createdAt: string | null
}

export type OpsActionPlan = {
  id: number
  incidentId: number
  planCode: string
  actionType: string
  targetType: string | null
  targetId: number | null
  targetName: string | null
  riskLevel: string
  status: string
  summary: string | null
  expectedEffect: string | null
  beforeJson: string | null
  afterJson: string | null
  confirmationCode: string
  expiresAt: string | null
  createdBy: string | null
  approvedBy: string | null
  approvedAt: string | null
  executedAt: string | null
  createdAt: string | null
}

export type OpsActionExecution = {
  success: boolean
  message: string
  plan: OpsActionPlan
}

export function listOpsIncidents(status = 'OPEN', limit = 50): Promise<OpsIncident[]> {
  return apiRequest(`/api/v1/ops/incidents?status=${encodeURIComponent(status)}&limit=${limit}`)
}

export function getOpsIncident(id: number): Promise<OpsIncident> {
  return apiRequest(`/api/v1/ops/incidents/${id}`)
}

export function listOpsIncidentEvents(id: number, limit = 50): Promise<OpsIncidentEvent[]> {
  return apiRequest(`/api/v1/ops/incidents/${id}/events?limit=${limit}`)
}

export function refreshOpsIncidents(): Promise<number> {
  return apiRequest('/api/v1/ops/incidents/refresh', { method: 'POST' })
}

export function createOpsPlan(incidentId: number): Promise<OpsActionPlan> {
  return apiRequest(`/api/v1/ops/incidents/${incidentId}/plans`, { method: 'POST' })
}

export function listOpsPlans(limit = 50): Promise<OpsActionPlan[]> {
  return apiRequest(`/api/v1/ops/plans?limit=${limit}`)
}

export function executeOpsPlan(id: number, confirmationCode: string): Promise<OpsActionExecution> {
  return apiRequest(`/api/v1/ops/plans/${id}/execute`, {
    method: 'POST',
    body: JSON.stringify({ confirmationCode }),
  })
}