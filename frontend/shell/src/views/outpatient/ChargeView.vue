<template>
  <div class="charge-page">
    <el-card>
      <template #header>
        待收费队列
        <el-button link type="primary" style="float: right" @click="loadWorklist">刷新</el-button>
      </template>
      <el-table :data="worklist" highlight-current-row height="360" @current-change="openDetail">
        <el-table-column prop="patientNo" label="患者号" width="110" />
        <el-table-column prop="patientName" label="姓名" width="100" />
        <el-table-column prop="regNo" label="号序" width="70" />
        <el-table-column prop="visitDate" label="就诊日期" />
      </el-table>
    </el-card>

    <el-card v-if="detail">
      <template #header>
        <b>{{ detail.patientName }}</b>（{{ detail.patientNo }}）待收费明细
      </template>
      <el-table :data="detail.orders" size="small">
        <el-table-column prop="itemName" label="项目" />
        <el-table-column prop="spec" label="规格" width="130" />
        <el-table-column prop="qty" label="数量" width="60" />
        <el-table-column prop="unitPrice" label="单价" width="80" />
        <el-table-column prop="amount" label="金额" width="90" />
      </el-table>
      <div class="settle-bar">
        <span class="total">合计：¥{{ detail.total }}</span>
        <el-select v-model="payMethod" style="width: 120px">
          <el-option label="现金" value="CASH" />
          <el-option label="微信" value="WECHAT" />
          <el-option label="支付宝" value="ALIPAY" />
          <el-option label="医保" value="YB" />
        </el-select>
        <el-button type="primary" :loading="settling" @click="settle">收 费</el-button>
      </div>
    </el-card>

    <el-card class="wide">
      <template #header>
        最近结算单
        <el-button link type="primary" style="float: right" @click="loadRecent">刷新</el-button>
      </template>
      <el-table :data="recent" size="small" height="260">
        <el-table-column prop="chargeNo" label="结算单号" width="170" />
        <el-table-column prop="patientName" label="姓名" width="90" />
        <el-table-column prop="totalAmount" label="金额" width="90" />
        <el-table-column label="方式" width="80">
          <template #default="{ row }">
            {{ { CASH: '现金', WECHAT: '微信', ALIPAY: '支付宝', YB: '医保' }[row.payMethod as string] }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'PAID' ? 'success' : 'info'" size="small">
              {{ row.status === 'PAID' ? '已支付' : '已退费' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PAID'" link type="danger" @click="refund(row)">退费</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const worklist = ref<Record<string, unknown>[]>([])
const detail = ref<Record<string, unknown> | null>(null)
const recent = ref<Record<string, unknown>[]>([])
const payMethod = ref('CASH')
const settling = ref(false)

async function loadWorklist() {
  const resp = await client.get('/outpatient/charges/worklist')
  worklist.value = resp.data.data
  detail.value = null
}

async function openDetail(row: Record<string, unknown> | null) {
  if (!row) return
  const resp = await client.get('/outpatient/charges/unpaid', { params: { registrationId: row.registrationId } })
  detail.value = resp.data.data
}

async function settle() {
  if (!detail.value) return
  settling.value = true
  try {
    const resp = await client.post('/outpatient/charges/settle', {
      registrationId: detail.value.registrationId,
      payMethod: payMethod.value,
    })
    ElMessage.success(`收费成功，结算单号 ${resp.data.data.chargeNo}，金额 ¥${resp.data.data.totalAmount}`)
    await Promise.all([loadWorklist(), loadRecent()])
  } finally {
    settling.value = false
  }
}

async function loadRecent() {
  const resp = await client.get('/outpatient/charges/recent')
  recent.value = resp.data.data
}

async function refund(row: Record<string, unknown>) {
  await ElMessageBox.confirm(`确认退费 ${row.chargeNo}（¥${row.totalAmount}）？`, '退费确认')
  await client.post(`/outpatient/charges/${row.chargeId}/refund`)
  ElMessage.success('已退费，相关项目回到待收费')
  await Promise.all([loadWorklist(), loadRecent()])
}

onMounted(() => {
  loadWorklist()
  loadRecent()
})
</script>

<style scoped>
.charge-page {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.wide { grid-column: span 2; }
.settle-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: flex-end;
  margin-top: 12px;
}
.total { font-size: 18px; font-weight: 600; color: #e6482e; }
</style>
