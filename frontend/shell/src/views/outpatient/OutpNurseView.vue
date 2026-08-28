<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="输液执行" name="infusion">
        <h4>待建输液单（静脉用药）
          <el-button link type="primary" size="small" @click="loadCandidates">刷新</el-button>
        </h4>
        <el-table :data="candidates" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="item_name" label="药品" show-overflow-tooltip />
          <el-table-column prop="usage_route" label="用法" width="100" />
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button link type="primary" size="small" :loading="createInfusionBusy === row.order_id" @click="createInfusion(row)">生成输液单</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4>输液单</h4>
        <el-table :data="infusions" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="item_name" label="药品" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'DONE' ? 'success' : row.status === 'RUNNING' ? 'warning' : 'info'"
                      size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="checks" label="巡视次数" width="90" />
          <el-table-column label="操作" width="230">
            <template #default="{ row }">
              <el-button v-if="row.status === 'PENDING'" link type="primary" size="small"
                         :loading="startInfusionBusy === row.id" @click="startInfusion(row)">
                开始</el-button>
              <template v-if="row.status === 'RUNNING'">
                <el-button link type="warning" size="small" :loading="addCheckBusy === row.id" @click="addCheck(row)">巡视</el-button>
                <el-button link type="success" size="small" :loading="finishInfusionBusy === row.id" @click="finishInfusion(row)">结束</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="皮试登记" name="skin">
        <el-form inline size="small">
          <el-form-item><el-input v-model="skin.registrationId" placeholder="挂号ID" style="width: 120px" /></el-form-item>
          <el-form-item><el-input v-model="skin.drugName" placeholder="皮试药品（如 青霉素）" style="width: 200px" /></el-form-item>
          <el-button type="primary" size="small" :loading="createSkinTestLoading" @click="createSkinTest">登记皮试</el-button>
        </el-form>
        <el-table :data="skinTests" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="drug_name" label="药品" />
          <el-table-column label="结果" width="180">
            <template #default="{ row }">
              <template v-if="row.result === 'PENDING'">
                <el-button link type="success" size="small" :loading="recordResultBusy === row.id" @click="recordResult(row, 'NEG')">阴性(-)</el-button>
                <el-button link type="danger" size="small" :loading="recordResultBusy === row.id" @click="recordResult(row, 'POS')">阳性(+)</el-button>
              </template>
              <el-tag v-else :type="row.result === 'NEG' ? 'success' : 'danger'" size="small">
                {{ row.result === 'NEG' ? '阴性' : '阳性' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="tested_at" label="判读时间" width="170" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('infusion')
const candidates = ref<Record<string, unknown>[]>([])
const infusions = ref<Record<string, unknown>[]>([])
const skinTests = ref<Record<string, unknown>[]>([])
const skin = reactive({ registrationId: '', drugName: '' })
const createSkinTestLoading = ref(false)
const createInfusionBusy = ref<unknown>(null)
const startInfusionBusy = ref<unknown>(null)
const addCheckBusy = ref<unknown>(null)
const finishInfusionBusy = ref<unknown>(null)
const recordResultBusy = ref<unknown>(null)

async function loadCandidates() { candidates.value = (await client.get('/outpatient/nurse/infusion-candidates')).data.data }
async function loadInfusions() { infusions.value = (await client.get('/outpatient/nurse/infusions')).data.data }
async function loadSkinTests() { skinTests.value = (await client.get('/outpatient/nurse/skin-tests')).data.data }

async function createInfusion(row: Record<string, unknown>) {
  createInfusionBusy.value = row.order_id
  try {
    await client.post('/outpatient/nurse/infusions', null, { params: { orderId: row.order_id } })
    await Promise.all([loadCandidates(), loadInfusions()])
  } finally {
    createInfusionBusy.value = null
  }
}
async function startInfusion(row: Record<string, unknown>) {
  startInfusionBusy.value = row.id
  try {
    await client.put(`/outpatient/nurse/infusions/${row.id}/start`)
    ElMessage.success('已开始输液')
    await loadInfusions()
  } finally {
    startInfusionBusy.value = null
  }
}
async function addCheck(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('巡视情况', '输液巡视', { inputValue: '滴速正常，无不良反应' }).catch(() => null)
  if (!res) return
  const { value } = res
  addCheckBusy.value = row.id
  try {
    await client.post(`/outpatient/nurse/infusions/${row.id}/checks`, null, { params: { note: value } })
    await loadInfusions()
  } finally {
    addCheckBusy.value = null
  }
}
async function finishInfusion(row: Record<string, unknown>) {
  finishInfusionBusy.value = row.id
  try {
    await client.put(`/outpatient/nurse/infusions/${row.id}/finish`)
    await loadInfusions()
  } finally {
    finishInfusionBusy.value = null
  }
}
async function createSkinTest() {
  if (!skin.registrationId || !skin.drugName) { ElMessage.warning('请填写挂号ID和皮试药品'); return }
  createSkinTestLoading.value = true
  try {
    await client.post('/outpatient/nurse/skin-tests', { registrationId: Number(skin.registrationId), drugName: skin.drugName })
    ElMessage.success('已登记，请判读结果')
    await loadSkinTests()
  } finally {
    createSkinTestLoading.value = false
  }
}
async function recordResult(row: Record<string, unknown>, result: string) {
  recordResultBusy.value = row.id
  try {
    await client.put(`/outpatient/nurse/skin-tests/${row.id}/result`, null, { params: { result } })
    await loadSkinTests()
  } finally {
    recordResultBusy.value = null
  }
}

onMounted(async () => { await Promise.all([loadCandidates(), loadInfusions(), loadSkinTests()]) })
</script>
