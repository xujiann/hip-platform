<template>
  <el-card v-loading="loading">
    <template #header>
      退费审批台（大额退费须授权人审批后方可执行）
      <el-button link type="primary" style="float: right" @click="load">刷新</el-button>
    </template>
    <el-table :data="rows" size="small" border>
      <el-table-column prop="charge_no" label="结算单号" width="180" />
      <el-table-column prop="applied_by_name" label="申请人" width="120" />
      <el-table-column label="金额" width="110">
        <template #default="{ row }">¥{{ row.amount }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="申请原因" min-width="220" show-overflow-tooltip />
      <el-table-column prop="applied_at" label="申请时间" width="180" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button link type="success" :loading="busyId === row.id" @click="decide(row, true)">通过</el-button>
          <el-button link type="danger" :loading="busyId === row.id" @click="decide(row, false)">驳回</el-button>
        </template>
      </el-table-column>
      <template #empty>暂无待审批的退费申请</template>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const rows = ref<Record<string, unknown>[]>([])
const loading = ref(false)
// 一行只有通过/驳回二选一在途，共用一个 busyId（按审批单 id）即可
const busyId = ref<unknown>(null)

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/outpatient/charges/refund-approvals/pending')).data.data
  } finally {
    loading.value = false
  }
}

async function decide(row: Record<string, unknown>, approved: boolean) {
  const res = await ElMessageBox.prompt(
    approved ? '审批意见（可留空）' : '驳回原因',
    approved ? '通过退费审批' : '驳回退费审批',
    { inputValue: approved ? '同意退费' : '', confirmButtonText: '确定', cancelButtonText: '取消' },
  ).catch(() => null)
  if (!res) return   // 用户取消，避免 unhandled rejection
  busyId.value = row.id
  try {
    await client.post(`/outpatient/charges/refund-approvals/${row.id}/decide`,
      { approved, note: res.value })
    ElMessage.success(approved ? '已通过，收费员可执行退费' : '已驳回该退费申请')
    await load()
  } finally {
    busyId.value = null
  }
}

onMounted(load)
</script>
