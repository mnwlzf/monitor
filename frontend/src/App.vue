<template>
  <main class="app-shell">
    <section v-if="!authenticated" class="login-page">
      <div class="login-panel">
        <div class="brand-lockup"><span class="brand-mark">M</span><div><strong>Monitor</strong><span>Admin Console</span></div></div>
        <p class="eyebrow">ADMIN CONSOLE</p>
        <h1>Sign in</h1>
        <form class="login-form" @submit.prevent="login">
          <label>Username<input v-model.trim="credentials.username" required autocomplete="username"></label>
          <label>Password<input v-model="credentials.password" required type="password" autocomplete="current-password"></label>
          <p v-if="error" class="form-error">{{ error }}</p>
          <button class="button button-primary button-block" :disabled="busy">{{ busy ? 'Signing in...' : 'Sign in' }}</button>
        </form>
      </div>
    </section>

    <section v-else class="workspace">
      <header class="topbar"><strong>Monitor</strong><button class="button button-ghost" @click="logout">Sign out</button></header>
      <div class="workspace-body">
        <aside class="sidebar">
          <div class="sidebar-heading"><h2>Upstream instances</h2></div>
          <button v-for="instance in instances" :key="instance.id" class="instance-item" :class="{ active: instance.id === selectedInstanceId }" @click="selectInstance(instance.id)">
            <span class="instance-copy"><strong>{{ instance.name }}</strong><small>{{ instance.baseUrl }}</small></span>
          </button>
          <p v-if="!instances.length" class="mini-empty">No upstream instances</p>
        </aside>
        <main class="main-content">
          <h1>{{ selectedInstance?.name || 'Monitoring workspace' }}</h1>
          <section v-if="accounts.length" class="panel account-panel">
            <div class="panel-header"><h2>Accounts</h2><button class="button button-secondary" @click="loadAccounts">Refresh</button></div>
            <div class="account-table-wrap"><table class="account-table"><thead><tr><th>Account</th><th>Status</th><th>Action</th></tr></thead><tbody>
              <tr v-for="account in accounts" :key="account.id" :class="{ selected: account.id === selectedAccountId }" @click="selectAccount(account.id)">
                <td><strong>{{ account.displayName }}</strong><small>{{ account.loginName }}</small></td><td>{{ account.authStatus }}</td><td><button class="text-button" @click.stop="enqueue(account)">Collect</button></td>
              </tr>
            </tbody></table></div>
          </section>
          <section v-if="selectedAccount" class="detail-grid">
            <article class="panel task-panel"><div class="panel-header"><div><h2>Collection tasks</h2><p>Task status and failure details</p></div><button class="button button-ghost" @click="loadTasks">Refresh</button></div>
              <div v-if="activeTask" class="task-current"><span class="task-status" :class="taskClass(activeTask.status)">{{ activeTask.status }}</span><strong>{{ activeTask.id }}</strong><small>Attempt {{ activeTask.attempt }} 路 {{ formatDate(activeTask.updatedAt) }}</small><div v-if="activeTask.errorCode" class="task-error"><strong>{{ activeTask.errorCode }}</strong><p>{{ activeTask.errorMessage || 'No detailed error message' }}</p></div></div>
              <div v-else class="mini-empty">No task selected</div>
              <div v-for="task in tasks" :key="task.id" class="task-row"><span class="task-status" :class="taskClass(task.status)">{{ task.status }}</span><span class="mono">{{ task.id.slice(0, 8) }}…</span><small>{{ formatDate(task.createdAt) }}</small><span v-if="task.errorCode" class="task-error-inline">{{ task.errorCode }}: {{ task.errorMessage || 'No details' }}</span></div>
            </article>
          </section>
          <p v-if="error" class="notice error">{{ error }}</p>
        </main>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
type Instance = { id: string; name: string; baseUrl: string; platform: string }
type Account = { id: string; instanceId: string; displayName: string; loginName: string; authStatus: string }
type Task = { id: string; instanceId: string; accountId: string; status: string; attempt: number; errorCode: string | null; errorMessage: string | null; createdAt: string; updatedAt: string }
type Response<T> = { data: T }
const authenticated = ref(false); const busy = ref(false); const error = ref(''); const credentials = ref({ username: '', password: '' }); const instances = ref<Instance[]>([]); const accounts = ref<Account[]>([]); const tasks = ref<Task[]>([]); const selectedInstanceId = ref<string | null>(null); const selectedAccountId = ref<string | null>(null); const activeTask = ref<Task | null>(null)
const selectedInstance = computed(() => instances.value.find(item => item.id === selectedInstanceId.value)); const selectedAccount = computed(() => accounts.value.find(item => item.id === selectedAccountId.value))
function csrf() { const item = document.cookie.split('; ').find(value => value.startsWith('XSRF-TOKEN=')); return item ? decodeURIComponent(item.substring(10)) : null }
async function request<T>(url: string, init: RequestInit = {}) { const method = (init.method || 'GET').toUpperCase(); if (method !== 'GET' && !url.endsWith('/auth/login') && !csrf()) await fetch('/api/v1/auth/csrf', { credentials: 'include' }); const headers = new Headers(init.headers); headers.set('Accept', 'application/json'); if (init.body) headers.set('Content-Type', 'application/json'); if (method !== 'GET' && !url.endsWith('/auth/login') && csrf()) headers.set('X-XSRF-TOKEN', csrf()!); const response = await fetch(url, { ...init, headers, credentials: 'include' }); const body = await response.json().catch(() => null) as Response<T> & { message?: string }; if (!response.ok) throw new Error(body?.message || `Request failed (${response.status})`); return body.data }
async function login() { busy.value = true; error.value = ''; try { await request('/api/v1/auth/login', { method: 'POST', body: JSON.stringify(credentials.value) }); authenticated.value = true; await loadInstances() } catch (exception) { error.value = exception instanceof Error ? exception.message : 'Login failed' } finally { busy.value = false } }
async function logout() { await request('/api/v1/auth/logout', { method: 'POST' }).catch(() => undefined); authenticated.value = false }
async function loadInstances() { instances.value = await request<Instance[]>('/api/v1/upstream/instances'); selectedInstanceId.value = instances.value[0]?.id || null; await loadAccounts() }
async function selectInstance(id: string) { selectedInstanceId.value = id; selectedAccountId.value = null; await loadAccounts() }
async function loadAccounts() { if (!selectedInstanceId.value) return; accounts.value = await request<Account[]>(`/api/v1/upstream/instances/${selectedInstanceId.value}/accounts`); selectedAccountId.value = accounts.value[0]?.id || null; await loadTasks() }
async function selectAccount(id: string) { selectedAccountId.value = id; await loadTasks() }
async function enqueue(account: Account) { const task = await request<Task>(`/api/v1/upstream/instances/${account.instanceId}/accounts/${account.id}/tasks`, { method: 'POST', headers: { 'Idempotency-Key': `manual-${account.id}-${Date.now()}` }, body: JSON.stringify({ capabilities: [] }) }); activeTask.value = task; await poll(task) }
async function loadTasks() { if (!selectedAccount.value) return; tasks.value = await request<Task[]>(`/api/v1/upstream/instances/${selectedAccount.value.instanceId}/accounts/${selectedAccount.value.id}/tasks?limit=10`); activeTask.value = tasks.value[0] || null }
async function poll(task: Task) { for (let attempt = 0; attempt < 20; attempt++) { await new Promise(resolve => setTimeout(resolve, 1000)); const latest = await request<Task>(`/api/v1/upstream/instances/${task.instanceId}/accounts/${task.accountId}/tasks/${task.id}`); activeTask.value = latest; if (['SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED', 'SKIPPED_LOCKED', 'CANCELLED'].includes(latest.status)) { await loadTasks(); return } } }
function taskClass(status: string) { return { running: status === 'RUNNING' || status === 'QUEUED', success: status === 'SUCCEEDED', partial: status === 'PARTIAL_SUCCESS', failed: ['FAILED', 'CANCELLED'].includes(status), waiting: ['RETRY_WAIT', 'SKIPPED_LOCKED'].includes(status) } }
function formatDate(value: string) { return new Date(value).toLocaleString() }
onMounted(() => { loadInstances().then(() => { authenticated.value = true }).catch(() => { authenticated.value = false }) })
</script>
