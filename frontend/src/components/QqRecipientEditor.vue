<template>
  <div>
    <div v-if="canWrite !== false" class="qq-recipient-add">
      <el-select v-model="form.targetType" class="qq-recipient-type">
        <el-option label="群" value="GROUP" />
        <el-option label="私聊" value="PRIVATE" />
      </el-select>
      <el-input
        v-model="form.targetId"
        :placeholder="form.targetType === 'GROUP' ? '群号，例如 123456789' : 'QQ 号，例如 10001'"
        @keyup.enter="add"
      />
      <el-input v-model="form.name" placeholder="名称（可选）" @keyup.enter="add" />
      <el-button type="primary" :loading="adding" @click="add">添加接收人</el-button>
    </div>

    <el-table :data="recipients" v-loading="loading" empty-text="暂未配置 QQ 消息接收人" size="small">
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.targetType === 'GROUP' ? 'primary' : 'success'" effect="plain">
            {{ targetTypeLabel(row.targetType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="群号 / QQ 号" min-width="170">
        <template #default="{ row }">{{ row.targetId }}</template>
      </el-table-column>
      <el-table-column label="名称" min-width="150">
        <template #default="{ row }">{{ row.name || '—' }}</template>
      </el-table-column>
      <el-table-column v-if="canWrite !== false" label="操作" width="100" align="right">
        <template #default="{ row }">
          <el-popconfirm title="确认删除该接收人？" @confirm="remove(asRecipient(row))">
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
  addQqRecipient,
  deleteQqRecipient,
  listQqRecipients,
  type QqRecipientInput,
} from '../api/settings'
import type { MailScene, QqRecipient, QqTargetType } from '../types'

const props = defineProps<{ scene: MailScene; canWrite?: boolean }>()

const recipients = ref<QqRecipient[]>([])
const loading = ref(false)
const adding = ref(false)
const form = reactive<{ targetType: QqTargetType; targetId: string; name: string }>({
  targetType: 'GROUP',
  targetId: '',
  name: '',
})

function targetTypeLabel(type: QqTargetType) {
  return type === 'GROUP' ? '群' : '私聊'
}

function asRecipient(row: unknown): QqRecipient {
  return row as QqRecipient
}

async function load() {
  loading.value = true
  try {
    recipients.value = await listQqRecipients(props.scene)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载 QQ 消息接收人失败')
  } finally {
    loading.value = false
  }
}

async function add() {
  const targetId = Number(form.targetId)
  if (!form.targetId || !Number.isSafeInteger(targetId) || targetId <= 0) {
    ElMessage.warning('请填写正整数群号 / QQ 号')
    return
  }
  adding.value = true
  try {
    const input: QqRecipientInput = {
      scene: props.scene,
      targetType: form.targetType,
      targetId,
      name: form.name,
    }
    recipients.value.push(await addQqRecipient(input))
    form.targetId = ''
    form.name = ''
    ElMessage.success('QQ 消息接收人已添加')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '添加 QQ 消息接收人失败')
  } finally {
    adding.value = false
  }
}

async function remove(recipient: QqRecipient) {
  try {
    await deleteQqRecipient(recipient.id)
    recipients.value = recipients.value.filter(item => item.id !== recipient.id)
    ElMessage.success('QQ 消息接收人已删除')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除 QQ 消息接收人失败')
  }
}

onMounted(load)
</script>

<style scoped>
.qq-recipient-add {
  display: flex;
  gap: 12px;
  margin-bottom: 14px;
}

.qq-recipient-type {
  width: 120px;
  flex: none;
}
</style>
