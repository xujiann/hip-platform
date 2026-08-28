<template>
  <el-card>
    <h4>扫码支付台（Mock 聚合支付通道）</h4>
    <el-form inline size="small">
      <el-form-item><el-input v-model="regId" placeholder="挂号ID" style="width: 120px" /></el-form-item>
      <el-form-item>
        <el-select v-model="channel" style="width: 110px">
          <el-option label="微信" value="WECHAT" /><el-option label="支付宝" value="ALIPAY" />
        </el-select>
      </el-form-item>
      <el-button type="primary" size="small" :loading="creating" @click="createOrder">生成支付码</el-button>
    </el-form>

    <el-alert v-if="current" type="success" :closable="false" style="margin: 8px 0">
      <b>{{ current.payNo }}</b> 应收 ¥{{ current.amount }}
      <div style="word-break: break-all">{{ current.qrContent }}</div>
    </el-alert>

    <el-table :data="orders" size="small" border>
      <el-table-column prop="pay_no" label="支付单号" width="140" />
      <el-table-column prop="patient_name" label="患者" width="100" />
      <el-table-column prop="amount" label="金额" width="90" />
      <el-table-column label="渠道" width="80">
        <template #default="{ row }">{{ row.channel === 'WECHAT' ? '微信' : '支付宝' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'SUCCESS' ? 'success' : row.status === 'PENDING' ? 'warning' : 'info'"
                  size="small">{{ { SUCCESS: '已支付', PENDING: '待支付', CANCELLED: '已作废' }[row.status as string] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="charge_id" label="结算单ID" width="90" />
      <el-table-column prop="created_at" label="出码时间" width="170" />
      <el-table-column label="操作" width="190">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button link type="success" size="small" :loading="busyPayNo === row.pay_no"
                       @click="mockScan(row)">模拟扫码支付</el-button>
            <el-button v-if="auth.hasPerm('outp:pay:cancel')" link type="danger" size="small"
                       :loading="busyPayNo === row.pay_no" @click="cancel(row)">作废</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const regId = ref('')
const channel = ref('WECHAT')
const current = ref<{ payNo: string; amount: unknown; qrContent: string } | null>(null)
const orders = ref<Record<string, unknown>[]>([])
const creating = ref(false)
// 表格行内操作（模拟扫码/作废）按 pay_no 单独置忙，避免一个 loading 把整列按钮都锁死
const busyPayNo = ref('')

async function load() { orders.value = (await client.get('/pay/orders')).data.data }

async function createOrder() {
  if (!regId.value) { ElMessage.warning('请填写挂号ID'); return }
  creating.value = true
  try {
    const resp = await client.post('/pay/orders', { registrationId: Number(regId.value), channel: channel.value })
    current.value = resp.data.data
    await load()
  } finally {
    creating.value = false
  }
}
async function mockScan(row: Record<string, unknown>) {
  busyPayNo.value = String(row.pay_no)
  try {
    const resp = await client.post(`/pay/orders/${row.pay_no}/mock-scan`)
    ElMessage.success(`支付成功，已自动结算 ${resp.data.data.chargeNo}`)
    current.value = null
    await load()
  } finally {
    busyPayNo.value = ''
  }
}
async function cancel(row: Record<string, unknown>) {
  busyPayNo.value = String(row.pay_no)
  try {
    await client.put(`/pay/orders/${row.pay_no}/cancel`)
    await load()
  } finally {
    busyPayNo.value = ''
  }
}

onMounted(load)
</script>
