<template>
  <section class="admin-page ops-page">
    <el-card shadow="never" class="admin-card" v-loading="loading">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>智能运维 · 事故中心</h3>
            <p>把 Sub2API 错误、账号状态和变更关联成事故；低风险动作先生成计划，管理员确认后执行。</p>
          </div>
          <div class="ops-actions">
            <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
            <el-button v-if="canWrite" type="primary" :loading="refreshing" @click="refreshIncidents">立即巡检</el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="当前为最小闭环：事故自动巡检 + 自愈计划 + 确认执行 + 回读验证。自动执行策略暂不开放。"
      />

      <el-row :gutter="16" class="ops-summary">
        <el-col :xs="24" :sm="8">
          <div class="ops-summary-card">
            <span>活跃事故</span>
            <strong>{{ incidents.length }}</strong>
          </div>
        </el-col>
        <el-col :xs="24" :sm="8">
          <div class="ops-summary-card">
            <span>待确认计划</span>
            <strong>{{ pendingPlanCount }}</strong>
          </div>
        </el-col>
        <el-col :xs="24" :sm="8">
          <div class="ops-summary-card">
            <span>已执行计划</span>
            <strong>{{ executedPlanCount }}</strong>
          </div>
        </el-col>
      </el-row>

      <el-table :data="incidents" size="small" class="ops-table" empty-text="暂无活跃事故">
        <el-table-column label="编号" width="88">
          <template #default="{ row }">#{{ row.id }}</template>
        </el-table-column>
        <el-table-column label="级别" width="78">
          <template #default="{ row }">
            <el-tag :type="severityType(row.severity)" size="small" effect="dark">{{ row.severity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="故障" min-width="180" show-overflow-tooltip />
        <el-table-column prop="impactSummary" label="影响" min-width="230" show-overflow-tooltip />
        <el-table-column label="最近信号" width="145">
          <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canWrite" link type="warning" @click="createPlan(row)">生成自愈计划</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-divider content-position="left">最近自愈计划</el-divider>
      <el-table :data="plans" size="small" class="ops-table" empty-text="暂无自愈计划">
        <el-table-column label="计划" width="150">
          <template #default="{ row }">
            <div class="ops-plan-code">#{{ row.id }}</div>
            <small>{{ row.planCode }}</small>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="120">
          <template #default="{ row }">{{ actionLabel(row.actionType) }}</template>
        </el-table-column>
        <el-table-column label="目标" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.targetName || '—' }}</template>
        </el-table-column>
        <el-table-column label="风险" width="78">
          <template #default="{ row }">
            <el-tag :type="riskType(row.riskLevel)" size="small" effect="plain">{{ row.riskLevel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="planStatusType(row.status)" size="small" effect="plain">{{ planStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="确认码" width="110">
          <template #default="{ row }">
            <code v-if="row.status === 'PENDING_APPROVAL'">{{ row.confirmationCode }}</code>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="145">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canWrite && row.status === 'PENDING_APPROVAL'"
              link
              type="danger"
              @click="executePlan(row)"
            >确认执行</el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="detailVisible" title="事故详情" width="780px">
      <template v-if="selectedIncident">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="编号">#{{ selectedIncident.id }}</el-descriptions-item>
          <el-descriptions-item label="级别">
            <el-tag :type="severityType(selectedIncident.severity)" size="small" effect="dark">
              {{ selectedIncident.severity }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="故障" :span="2">{{ selectedIncident.title }}</el-descriptions-item>
          <el-descriptions-item label="影响" :span="2">{{ selectedIncident.impactSummary || '—' }}</el-descriptions-item>
          <el-descriptions-item label="首次发现">{{ formatTime(selectedIncident.firstSeenAt) }}</el-descriptions-item>
          <el-descriptions-item label="最近信号">{{ formatTime(selectedIncident.lastSeenAt) }}</el-descriptions-item>
          <el-descriptions-item label="指纹" :span="2">{{ selectedIncident.rootCauseCode || '—' }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="ops-detail-heading">事故时间线</h4>
        <el-timeline v-if="events.length">
          <el-timeline-item
            v-for="event in events"
            :key="event.id"
            :timestamp="formatTime(event.createdAt)"
            placement="top"
          >
            <div class="ops-event-line">
              <el-tag size="small" effect="plain">{{ event.eventType }}</el-tag>
              <span>{{ event.content }}</span>
            </div>
            <small v-if="event.actor" class="muted">操作人：{{ event.actor }}</small>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无时间线" :image-size="60" />
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="canWrite && selectedIncident" type="warning" @click="createPlan(selectedIncident)">
          生成自愈计划
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="planVisible" title="自愈计划" width="620px">
      <template v-if="selectedPlan">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="该操作不会立即执行。请核对目标、影响和回滚说明后，使用确认码执行。"
        />
        <el-descriptions :column="1" border size="small" class="ops-plan-detail">
          <el-descriptions-item label="计划">{{ selectedPlan.planCode }}</el-descriptions-item>
          <el-descriptions-item label="动作">{{ actionLabel(selectedPlan.actionType) }}</el-descriptions-item>
          <el-descriptions-item label="目标">{{ selectedPlan.targetName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="风险">{{ selectedPlan.riskLevel }}</el-descriptions-item>
          <el-descriptions-item label="影响">{{ selectedPlan.summary || '—' }}</el-descriptions-item>
          <el-descriptions-item label="预期">{{ selectedPlan.expectedEffect || '—' }}</el-descriptions-item>
          <el-descriptions-item label="确认码">
            <code class="ops-confirm-code">{{ selectedPlan.confirmationCode }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="有效期">{{ formatTime(selectedPlan.expiresAt) }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer>
        <el-button @click="planVisible = false">关闭</el-button>
        <el-button
          v-if="canWrite && selectedPlan && selectedPlan.status === 'PENDING_APPROVAL'"
          type="danger"
          @click="executePlan(selectedPlan)"
        >确认执行</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  createOpsPlan,
  executeOpsPlan,
  listOpsIncidentEvents,
  listOpsIncidents,
  listOpsPlans,
  refreshOpsIncidents,
  type OpsActionPlan,
  type OpsIncident,
  type OpsIncidentEvent,
} from '../api/ops'

const props = withDefaults(defineProps<{ canWrite?: boolean }>(), { canWrite: false })

const loading = ref(false)
const refreshing = ref(false)
const incidents = ref<OpsIncident[]>([])
const plans = ref<OpsActionPlan[]>([])
const events = ref<OpsIncidentEvent[]>([])
const selectedIncident = ref<OpsIncident | null>(null)
const selectedPlan = ref<OpsActionPlan | null>(null)
const detailVisible = ref(false)
const planVisible = ref(false)

const pendingPlanCount = computed(() => plans.value.filter(plan => plan.status === 'PENDING_APPROVAL').length)
const executedPlanCount = computed(() => plans.value.filter(plan => plan.status === 'EXECUTED').length)

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  try {
    const [incidentList, planList] = await Promise.all([listOpsIncidents(), listOpsPlans()])
    incidents.value = incidentList
    plans.value = planList
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载智能运维数据失败')
  } finally {
    loading.value = false
  }
}

async function refreshIncidents() {
  refreshing.value = true
  try {
    const count = await refreshOpsIncidents()
    await loadAll()
    ElMessage.success(`巡检完成，更新 ${count} 个错误指纹`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '巡检失败')
  } finally {
    refreshing.value = false
  }
}

async function openDetail(row: any) {
  const incident = row as OpsIncident
  selectedIncident.value = incident
  detailVisible.value = true
  events.value = []
  try {
    events.value = await listOpsIncidentEvents(incident.id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载事故时间线失败')
  }
}

async function createPlan(row: any) {
  const incident = row as OpsIncident
  try {
    const plan = await createOpsPlan(incident.id)
    selectedPlan.value = plan
    planVisible.value = true
    await loadAll()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '生成自愈计划失败')
  }
}

async function executePlan(row: any) {
  const plan = row as OpsActionPlan
  try {
    const result = await ElMessageBox.prompt(
      `将执行「${actionLabel(plan.actionType)}」，目标：${plan.targetName || '当前事故'}。请输入确认码。`,
      '确认执行自愈计划',
      {
        confirmButtonText: '执行',
        cancelButtonText: '取消',
        inputValue: plan.confirmationCode,
        inputPlaceholder: '输入确认码',
        inputPattern: /^[A-Za-z0-9]{6,20}$/,
        inputErrorMessage: '确认码格式不正确',
      },
    )
    const response = await executeOpsPlan(plan.id, result.value)
    await loadAll()
    if (response.success) {
      ElMessage.success(response.message)
      planVisible.value = false
    } else {
      ElMessage.error(response.message)
    }
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '执行自愈计划失败')
  }
}

function severityType(severity: string) {
  if (severity === 'P0') return 'danger'
  if (severity === 'P1') return 'warning'
  if (severity === 'P2') return 'primary'
  return 'info'
}

function riskType(risk: string) {
  if (risk === 'HIGH') return 'danger'
  if (risk === 'MEDIUM') return 'warning'
  return 'success'
}

function planStatusType(status: string) {
  if (status === 'EXECUTED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'PENDING_APPROVAL') return 'warning'
  return 'info'
}

function planStatusLabel(status: string) {
  return {
    PENDING_APPROVAL: '待确认',
    EXECUTING: '执行中',
    EXECUTED: '已执行',
    FAILED: '失败',
    EXPIRED: '已过期',
    CANCELLED: '已取消',
  }[status] || status
}

function actionLabel(action: string) {
  return {
    CLEAR_RATE_LIMIT: '清理账号限流',
    CLEAR_ERROR: '清理账号错误',
    REFRESH_ACCOUNT: '刷新账号凭证',
    RECOVER_ACCOUNT: '恢复账号状态',
    PAUSE_ACCOUNT: '暂停账号调度',
    OBSERVE: '仅观察',
  }[action] || action
}

function formatTime(value: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.ops-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.ops-summary {
  margin: 16px 0;
}

.ops-summary-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
  border: 1px solid #e3ebe8;
  border-radius: 12px;
  background: #fbfdfc;
}

.ops-summary-card span {
  color: #66756f;
  font-size: 13px;
}

.ops-summary-card strong {
  font-size: 28px;
  color: #19352c;
}

.ops-table {
  margin-top: 12px;
}

.ops-plan-code {
  font-weight: 600;
}

.ops-plan-code small {
  color: #8a9893;
}

.ops-confirm-code {
  padding: 2px 8px;
  border-radius: 6px;
  background: #fff4e5;
  color: #b45309;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 1px;
}

.ops-plan-detail {
  margin-top: 16px;
}

.ops-detail-heading {
  margin: 20px 0 12px;
}

.ops-event-line {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>