<template>
  <div class="inp-doctor">
    <el-card class="list">
      <template #header>在院患者</template>
      <el-table :data="admissions" highlight-current-row height="calc(100vh - 220px)" @current-change="open">
        <el-table-column prop="bedNo" label="床" width="50" />
        <el-table-column prop="patientName" label="姓名" width="80" />
        <el-table-column prop="admitDiagName" label="诊断" show-overflow-tooltip />
      </el-table>
    </el-card>

    <el-card v-if="current" class="workspace">
      <template #header>
        <b>{{ current.patientName }}</b>（{{ current.admissionNo }} · {{ current.wardName }} {{ current.bedNo }}床）
        <span class="fees">费用 ¥{{ totalAmount }} / 押金 ¥{{ depositAmount }}</span>
      </template>
      <el-tabs v-model="tab">
        <el-tab-pane label="医嘱" name="orders">
      <div class="add-row">
        <el-select v-model="drugId" filterable remote :remote-method="searchDrugs" placeholder="药品" style="width: 240px">
          <el-option v-for="d in drugOptions" :key="d.id as number"
                     :label="`${d.name}（¥${d.price}，存${d.stock}）`" :value="d.id as number" />
        </el-select>
        <el-input v-model="dose" placeholder="单次量" style="width: 80px" />
        <el-select v-model="freq" style="width: 80px">
          <el-option v-for="f in ['qd', 'bid', 'tid', 'q8h', 'st']" :key="f" :label="f" :value="f" />
        </el-select>
        <el-select v-model="route" style="width: 90px">
          <el-option v-for="u in ['口服', '静滴', '肌注']" :key="u" :label="u" :value="u" />
        </el-select>
        <el-input-number v-model="qty" :min="1" style="width: 90px" />
        <el-button type="primary" @click="addDrug">开药</el-button>
        <el-select v-model="itemId" filterable remote :remote-method="searchItems" placeholder="检查/检验/治疗"
                   style="width: 220px">
          <el-option v-for="c in itemOptions" :key="c.id as number" :label="`${c.name}（¥${c.price}）`"
                     :value="c.id as number" />
        </el-select>
        <el-button type="primary" @click="addItem">开申请</el-button>
      </div>
      <el-table :data="orders" size="small" height="calc(100vh - 330px)">
        <el-table-column prop="groupNo" label="医嘱号" width="140" />
        <el-table-column label="类型" width="60">
          <template #default="{ row }">{{ { DRUG: '药', LAB: '验', EXAM: '查', TREAT: '治' }[row.orderType as string] }}</template>
        </el-table-column>
        <el-table-column prop="itemName" label="项目" />
        <el-table-column label="用法" width="150">
          <template #default="{ row }">
            <span v-if="row.orderType === 'DRUG'">{{ row.usageRoute }} {{ row.dosePerTime }} {{ row.frequency }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="qty" label="量" width="50" />
        <el-table-column prop="amount" label="金额" width="80" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="{ CREATED: 'warning', EXECUTED: 'success', CANCELLED: 'info' }[row.status as string]">
              {{ { CREATED: '未执行', EXECUTED: '已执行', CANCELLED: '作废' }[row.status as string] }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
        </el-tab-pane>

        <el-tab-pane label="病历" name="records">
          <el-form inline>
            <el-form-item>
              <el-select v-model="recordType" style="width: 130px">
                <el-option label="入院记录" value="ADMISSION" />
                <el-option label="病程记录" value="PROGRESS" />
                <el-option label="出院小结" value="DISCHARGE" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-input v-model="recordTitle" placeholder="标题（可空）" style="width: 180px" />
            </el-form-item>
          </el-form>
          <el-input v-model="recordContent" type="textarea" :rows="4" placeholder="病历内容" />
          <el-button type="primary" style="margin-top: 8px" @click="addRecord">保存记录</el-button>
          <el-timeline style="margin-top: 16px">
            <el-timeline-item v-for="r in records" :key="r.id as number"
                              :timestamp="`${String(r.createdAt).slice(0, 16).replace('T', ' ')} · ${recordTypeNames[r.recordType as string]}`">
              <b>{{ r.title }}</b>
              <p class="record-content">{{ r.content }}</p>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>

        <el-tab-pane label="体征" name="vitals">
          <VitalsChart :vitals="vitals" />
          <el-table :data="vitals" size="small" height="calc(100vh - 560px)">
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ String(row.measuredAt).slice(0, 16).replace('T', ' ') }}</template>
            </el-table-column>
            <el-table-column prop="temperature" label="体温℃" width="80" />
            <el-table-column prop="pulse" label="脉搏" width="70" />
            <el-table-column prop="respiration" label="呼吸" width="70" />
            <el-table-column label="血压" width="100">
              <template #default="{ row }">
                <span v-if="row.sbp">{{ row.sbp }}/{{ row.dbp }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="spo2" label="SpO₂%" width="80" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
    <el-empty v-else class="workspace" description="选择在院患者" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import VitalsChart from '../../components/VitalsChart.vue'

const admissions = ref<Record<string, unknown>[]>([])
const current = ref<Record<string, unknown> | null>(null)
const orders = ref<Record<string, unknown>[]>([])
const totalAmount = ref(0)
const depositAmount = ref(0)
const drugOptions = ref<Record<string, unknown>[]>([])
const itemOptions = ref<Record<string, unknown>[]>([])
const drugId = ref<number | null>(null)
const itemId = ref<number | null>(null)
const dose = ref('1粒')
const freq = ref('bid')
const route = ref('口服')
const qty = ref(1)
const tab = ref('orders')
const records = ref<Record<string, unknown>[]>([])
const vitals = ref<Record<string, unknown>[]>([])
const recordType = ref('PROGRESS')
const recordTitle = ref('')
const recordContent = ref('')
const recordTypeNames: Record<string, string> = { ADMISSION: '入院记录', PROGRESS: '病程记录', DISCHARGE: '出院小结' }

async function loadList() {
  const resp = await client.get('/inpatient/admissions')
  admissions.value = resp.data.data
}

async function open(row: Record<string, unknown> | null) {
  current.value = row
  if (!row) return
  const [ws, rec, vit] = await Promise.all([
    client.get(`/inpatient/admissions/${row.id}/workspace`),
    client.get(`/inpatient/admissions/${row.id}/records`),
    client.get(`/inpatient/admissions/${row.id}/vitals`),
  ])
  orders.value = ws.data.data.orders
  totalAmount.value = ws.data.data.totalAmount
  depositAmount.value = ws.data.data.depositAmount
  records.value = rec.data.data
  vitals.value = vit.data.data
}

async function addRecord() {
  if (!current.value || !recordContent.value) {
    ElMessage.warning('请填写病历内容')
    return
  }
  await client.post(`/inpatient/admissions/${current.value.id}/records`, {
    recordType: recordType.value,
    title: recordTitle.value || recordTypeNames[recordType.value],
    content: recordContent.value,
  })
  ElMessage.success('病历已保存')
  recordContent.value = ''
  recordTitle.value = ''
  await open(current.value)
}

async function searchDrugs(kw: string) {
  const resp = await client.get('/masterdata/drugs', { params: { keyword: kw } })
  drugOptions.value = resp.data.data
}

async function searchItems(kw: string) {
  const resp = await client.get('/masterdata/charge-items', { params: { keyword: kw } })
  itemOptions.value = resp.data.data
}

async function addDrug() {
  if (!current.value || !drugId.value) return
  await client.post(`/inpatient/admissions/${current.value.id}/orders`, {
    lines: [{ orderType: 'DRUG', itemId: drugId.value, qty: qty.value, usageRoute: route.value, frequency: freq.value, dosePerTime: dose.value }],
  })
  ElMessage.success('医嘱已开立')
  drugId.value = null
  await open(current.value)
}

async function addItem() {
  if (!current.value || !itemId.value) return
  const item = itemOptions.value.find((c) => c.id === itemId.value)
  await client.post(`/inpatient/admissions/${current.value.id}/orders`, {
    lines: [{ orderType: item?.category ?? 'TREAT', itemId: itemId.value, qty: 1 }],
  })
  ElMessage.success('申请已开立')
  itemId.value = null
  await open(current.value)
}

onMounted(loadList)
</script>

<style scoped>
.inp-doctor { display: grid; grid-template-columns: 300px 1fr; gap: 12px; }
.add-row { display: flex; gap: 6px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.fees { float: right; color: #e6482e; font-size: 13px; }
.record-content { white-space: pre-wrap; color: #555; margin: 4px 0 0; }
</style>
