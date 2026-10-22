<template>
  <el-card>
    <template #header>
      医技执行队列（检验 / 检查 / 治疗）
      <el-button link type="primary" style="float: right" @click="load">刷新</el-button>
    </template>
    <el-empty v-if="!worklist.length" description="暂无待执行项目" />
    <el-table v-else :data="worklist" border stripe>
      <el-table-column prop="groupNo" label="申请单号" width="150" />
      <el-table-column prop="patientNo" label="患者号" width="110" />
      <el-table-column prop="patientName" label="姓名" width="90" />
      <el-table-column label="类别" width="70">
        <template #default="{ row }">
          {{ { LAB: '检验', EXAM: '检查', TREAT: '治疗' }[row.orderType as string] }}
        </template>
      </el-table-column>
      <el-table-column prop="itemName" label="项目" />
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button v-if="canExecute" type="primary" size="small" :disabled="!queueReady" @click="openExecute(row)">执行/出报告</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :model-value="dialogVisible" @update:model-value="setDialogVisible" :title="`${current?.patientName} · ${current?.itemName}`" width="520px">
      <el-input v-model="resultText" type="textarea" :rows="6" :maxlength="2000" show-word-limit placeholder="录入结果/报告（可留空，直接标记完成）" />
      <template #footer>
        <el-button @click="setDialogVisible(false)">取消</el-button>
        <el-button type="primary" :loading="executing" :disabled="!canExecute || !queueReady" @click="execute">完成执行</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client, { type BizError } from '../../api/client'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const canExecute = computed(() => auth.user?.roles.some(role => ['ADMIN', 'TECHNICIAN', 'NURSE'].includes(role)) ?? false)
const worklist = ref<Record<string, unknown>[]>([])
const dialogVisible = ref(false)
const current = ref<Record<string, unknown> | null>(null)
const resultText = ref('')
const executing = ref(false)
const queueReady = ref(false)
let alive = true
let readSequence = 0
let dialogSequence = 0

async function load() {
  const sequence = ++readSequence
  queueReady.value = false
  worklist.value = []
  if (!alive || !canExecute.value) return
  try {
    const resp = await client.get('/outpatient/exec/worklist')
    if (!alive || sequence !== readSequence || !canExecute.value) return
    worklist.value = resp.data.data
    queueReady.value = true
  } catch {
    // The shared client displays the error; an unsuccessful refresh leaves no actionable stale queue.
  }
}

function setDialogVisible(visible: boolean) {
  dialogVisible.value = visible
  if (!visible) {
    dialogSequence++
    current.value = null
    resultText.value = ''
  }
}

function openExecute(row: Record<string, unknown>) {
  if (!alive || !canExecute.value || !queueReady.value || !worklist.value.some(item => item.orderId === row.orderId)) return
  dialogSequence++
  current.value = row
  resultText.value = ''
  dialogVisible.value = true
}

async function execute() {
  if (!alive || !canExecute.value || executing.value || !queueReady.value || !dialogVisible.value || !current.value) return
  const orderId = current.value.orderId
  if (!worklist.value.some(item => item.orderId === orderId)) return
  const sequence = dialogSequence
  const submittedResult = resultText.value
  executing.value = true
  // A read started before this write must not restore the old queue after it completes.
  readSequence++
  try {
    await client.post(`/outpatient/exec/${orderId}`, { resultText: submittedResult })
    if (!alive) return
    if (sequence === dialogSequence) {
      ElMessage.success('已完成执行')
      setDialogVisible(false)
    }
    await load()
  } catch (error) {
    if (!alive) return
    const code = (error as BizError).bizCode
    if (code === 1005) {
      setDialogVisible(false)
      readSequence++
      worklist.value = []
      queueReady.value = false
    } else {
      if ([7001, 7002, 7003, 7004].includes(Number(code)) && sequence === dialogSequence) setDialogVisible(false)
      await load()
    }
  } finally {
    executing.value = false
  }
}

onMounted(load)
onUnmounted(() => {
  alive = false
  readSequence++
  dialogSequence++
  worklist.value = []
  queueReady.value = false
})
</script>
