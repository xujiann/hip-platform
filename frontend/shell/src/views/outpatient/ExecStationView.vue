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
          <el-button type="primary" size="small" @click="openExecute(row)">执行/出报告</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="`${current?.patientName} · ${current?.itemName}`" width="520px">
      <el-input v-model="resultText" type="textarea" :rows="6" placeholder="录入结果/报告（可留空，直接标记完成）" />
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="executing" @click="execute">完成执行</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const worklist = ref<Record<string, unknown>[]>([])
const dialogVisible = ref(false)
const current = ref<Record<string, unknown> | null>(null)
const resultText = ref('')
const executing = ref(false)

async function load() {
  const resp = await client.get('/outpatient/exec/worklist')
  worklist.value = resp.data.data
}

function openExecute(row: Record<string, unknown>) {
  current.value = row
  resultText.value = ''
  dialogVisible.value = true
}

async function execute() {
  if (!current.value) return
  executing.value = true
  try {
    await client.post(`/outpatient/exec/${current.value.orderId}`, { resultText: resultText.value })
    ElMessage.success('已完成执行')
    dialogVisible.value = false
    await load()
  } finally {
    executing.value = false
  }
}

onMounted(load)
</script>
