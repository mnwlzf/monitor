<template>
  <div>
    <div v-if="canWrite !== false" class="recipient-add">
      <el-input v-model="form.email" placeholder="someone@example.com" @keyup.enter="add" />
      <el-input v-model="form.name" placeholder="名称（可选）" @keyup.enter="add" />
      <el-button type="primary" :loading="adding" @click="add">添加收件人</el-button>
    </div>

    <el-table :data="recipients" v-loading="loading" empty-text="暂未配置收件人" size="small">
      <el-table-column prop="email" label="邮箱" min-width="240" />
      <el-table-column label="名称" min-width="160">
        <template #default="{ row }">{{ row.name || '—' }}</template>
      </el-table-column>
      <el-table-column v-if="canWrite !== false" label="操作" width="100" align="right">
        <template #default="{ row }">
          <el-popconfirm title="确认删除该收件人？" @confirm="remove(asRecipient(row))">
            <template #reference><el-button size="small" type="danger" plain>删除</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  addMailRecipient,
  deleteMailRecipient,
  listMailRecipients,
  type MailRecipientInput,
} from '../api/settings'
import type { MailRecipient, MailScene } from '../types'

const props = defineProps<{ scene: MailScene; canWrite?: boolean }>()

const recipients = ref<MailRecipient[]>([])
const loading = ref(false)
const adding = ref(false)
const form = reactive<{ email: string; name: string }>({ email: '', name: '' })

function asRecipient(row: unknown): MailRecipient {
  return row as MailRecipient
}

async function load() {
  loading.value = true
  try {
    recipients.value = await listMailRecipients(props.scene)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载收件人失败')
  } finally {
    loading.value = false
  }
}

async function add() {
  if (!form.email) {
    ElMessage.warning('请填写收件人邮箱')
    return
  }
  adding.value = true
  try {
    const input: MailRecipientInput = { scene: props.scene, email: form.email, name: form.name }
    recipients.value.push(await addMailRecipient(input))
    form.email = ''
    form.name = ''
    ElMessage.success('收件人已添加')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '添加收件人失败')
  } finally {
    adding.value = false
  }
}

async function remove(recipient: MailRecipient) {
  try {
    await deleteMailRecipient(recipient.id)
    recipients.value = recipients.value.filter(item => item.id !== recipient.id)
    ElMessage.success('收件人已删除')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除收件人失败')
  }
}

onMounted(load)
</script>

<style scoped>
.recipient-add {
  display: flex;
  gap: 12px;
  margin-bottom: 14px;
}
</style>