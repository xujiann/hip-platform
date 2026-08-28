<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="待采样" name="pending">
        <el-table :data="pending" size="small" border>
          <el-table-column prop="group_no" label="申请单号" width="150" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="item_name" label="项目" />
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button link type="primary" size="small" :loading="busyId === row.order_id" @click="collect(row)">采样打码</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="标本流转" name="samples">
        <el-table :data="samples" size="small" border>
          <el-table-column prop="barcode" label="条码" width="140" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="item_name" label="项目" />
          <el-table-column label="替检" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.substitute" type="danger" size="small">替检：{{ row.substitute_name }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ statusNames[row.status as string] }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-button v-if="row.status === 'COLLECTED'" link type="primary" size="small" :loading="busyId === row.id" @click="receive(row)">核收</el-button>
              <el-button v-if="row.status === 'RECEIVED'" link type="success" size="small" @click="openResult(row)">录结果并发布</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dialogVisible" :title="`结果录入：${current?.item_name}`" width="640px">
      <el-table :data="results" size="small">
        <el-table-column label="项目"><template #default="{ row }"><el-input v-model="row.name" size="small" /></template></el-table-column>
        <el-table-column label="结果" width="100"><template #default="{ row }"><el-input v-model="row.value" size="small" /></template></el-table-column>
        <el-table-column label="单位" width="90"><template #default="{ row }"><el-input v-model="row.unit" size="small" /></template></el-table-column>
        <el-table-column label="参考" width="100"><template #default="{ row }"><el-input v-model="row.refRange" size="small" /></template></el-table-column>
        <el-table-column label="标志" width="90">
          <template #default="{ row }">
            <el-select v-model="row.flag" size="small">
              <el-option v-for="f in ['N', 'H', 'L', 'HH', 'LL']" :key="f" :label="f" :value="f" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" style="margin-top: 8px" @click="results.push({ code: '', name: '', value: '', unit: '', refRange: '', flag: 'N' })">
        加一行
      </el-button>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="success" @click="publish" :loading="publishLoading">审核发布</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('pending')
const statusNames: Record<string, string> = { COLLECTED: '已采样', RECEIVED: '已核收', PUBLISHED: '已发布' }
const pending = ref<Record<string, unknown>[]>([])
const samples = ref<Record<string, unknown>[]>([])
const dialogVisible = ref(false)
const current = ref<Record<string, unknown> | null>(null)
const results = ref<Record<string, string>[]>([])
const busyId = ref<unknown>(null)
const publishLoading = ref(false)

async function load() {
  pending.value = (await client.get('/lis/pending')).data.data
  samples.value = (await client.get('/lis/samples')).data.data
}

async function collect(row: Record<string, unknown>) {
  // 三十九期：替检参数化——留空为本人，填写则登记替检人（分检页红色醒目提示）
  const res = await ElMessageBox.prompt('替检人（本人采样请留空）', '采样打码',
    { inputValue: '', inputPlaceholder: '如：家属 张某' }).catch(() => null)
  if (!res) return
  const { value } = res
  busyId.value = row.order_id
  try {
    const resp = await client.post('/lis/samples', null,
      { params: { orderId: row.order_id, substituteName: value || undefined } })
    ElMessage.success(`条码 ${resp.data.data.barcode}${value ? '（替检已标识）' : ''}`)
    await load()
  } finally { busyId.value = null }
}

async function receive(row: Record<string, unknown>) {
  busyId.value = row.id
  try {
    await client.put(`/lis/samples/${row.barcode}/receive`)
    await load()
  } finally { busyId.value = null }
}

function openResult(row: Record<string, unknown>) {
  current.value = row
  results.value = [{ code: '', name: String(row.item_name), value: '', unit: '', refRange: '', flag: 'N' }]
  dialogVisible.value = true
}

async function publish() {
  if (!current.value) return
  publishLoading.value = true
  try {
    await client.post(`/lis/samples/${current.value.barcode}/publish`, { results: results.value })
    ElMessage.success('已发布，医嘱自动执行')
    dialogVisible.value = false
    await load()
  } finally { publishLoading.value = false }
}

onMounted(load)
</script>
