<template>
  <el-card>
    <el-tabs v-model="tab" @tab-change="onTab">
      <!-- ① 微生物药敏 -->
      <el-tab-pane label="微生物药敏" name="micro">
        <el-table :data="microSamples" size="small" border>
          <el-table-column prop="barcode" label="条码" width="130" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="item_name" label="项目" />
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openMicro(row)">录培养/药敏</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!microSamples.length" description="无待录微生物标本（需已核收）" :image-size="60" />
      </el-tab-pane>

      <!-- ② 室内质控 IQC -->
      <el-tab-pane label="室内质控 IQC" name="qc">
        <el-form :model="qc" inline size="small" style="margin-bottom:8px">
          <el-form-item label="项目"><el-input v-model="qc.itemCode" style="width:90px" /></el-form-item>
          <el-form-item label="水平"><el-input v-model="qc.level" style="width:70px" placeholder="L1/L2" /></el-form-item>
          <el-form-item label="批号"><el-input v-model="qc.lotNo" style="width:90px" /></el-form-item>
          <el-form-item label="靶值"><el-input-number v-model="qc.targetValue" :controls="false" style="width:90px" /></el-form-item>
          <el-form-item label="SD"><el-input-number v-model="qc.sd" :controls="false" :min="0" style="width:80px" /></el-form-item>
          <el-form-item label="实测"><el-input-number v-model="qc.measuredValue" :controls="false" style="width:90px" /></el-form-item>
          <el-button type="primary" size="small" @click="submitQc">录质控</el-button>
        </el-form>
        <el-alert v-if="qcResult" :type="qcResult.inControl ? 'success' : 'error'" :closable="false" show-icon
                  :title="`z=${qcResult.zScore}　${qcResult.inControl ? '在控' : '失控'}${qcResult.rule ? '（' + qcResult.rule + '）' : ''}`" />
        <h4 style="margin-top:12px">各质控项目最新状态</h4>
        <el-table :data="qcLatest" size="small" border>
          <el-table-column prop="item_code" label="项目" width="90" />
          <el-table-column prop="level" label="水平" width="70" />
          <el-table-column prop="lot_no" label="批号" width="90" />
          <el-table-column prop="measured_value" label="实测" width="90" />
          <el-table-column prop="z_score" label="z" width="70" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="row.in_control ? 'success' : 'danger'" size="small">
                {{ row.in_control ? '在控' : '失控' }}{{ row.rule_broken ? '·' + row.rule_broken : '' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ③ TAT 周转 -->
      <el-tab-pane label="TAT 周转" name="tat">
        <el-form inline size="small" style="margin-bottom:8px">
          <el-form-item label="日期范围">
            <el-date-picker v-model="tatRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="起" end-placeholder="止" @change="loadTat" />
          </el-form-item>
        </el-form>
        <el-descriptions v-if="tat" :column="2" border size="small">
          <el-descriptions-item label="已发布标本数">{{ tat.total }}</el-descriptions-item>
          <el-descriptions-item label="超时件数（>{{ tat.limitMinutes }}分）">{{ tat.overtime }}</el-descriptions-item>
          <el-descriptions-item label="采样→核收(均分)">{{ tat.collect_to_receive_min ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="核收→发布(均分)">{{ tat.receive_to_publish_min ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="总 TAT(均分)">{{ tat.total_tat_min ?? '—' }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="microDialog" :title="`微生物药敏：${microCur?.item_name}`" width="680px">
      <el-form :model="microForm" inline size="small">
        <el-form-item label="标本"><el-input v-model="microForm.specimen" style="width:90px" placeholder="痰/血/尿" /></el-form-item>
        <el-form-item label="菌种"><el-input v-model="microForm.organism" style="width:150px" /></el-form-item>
        <el-form-item label="革兰"><el-select v-model="microForm.gram" style="width:80px"><el-option label="阳性" value="POS" /><el-option label="阴性" value="NEG" /></el-select></el-form-item>
        <el-form-item label="菌落"><el-input v-model="microForm.colonyCount" style="width:90px" /></el-form-item>
      </el-form>
      <el-table :data="microForm.ast" size="small" style="margin-top:6px">
        <el-table-column label="抗菌药"><template #default="{ row }"><el-input v-model="row.antibiotic" size="small" /></template></el-table-column>
        <el-table-column label="方法" width="90"><template #default="{ row }"><el-input v-model="row.method" size="small" placeholder="KB/MIC" /></template></el-table-column>
        <el-table-column label="MIC/直径" width="100"><template #default="{ row }"><el-input v-model="row.micValue" size="small" /></template></el-table-column>
        <el-table-column label="SIR" width="90">
          <template #default="{ row }"><el-select v-model="row.sir" size="small"><el-option v-for="s in ['S','I','R']" :key="s" :label="s" :value="s" /></el-select></template>
        </el-table-column>
      </el-table>
      <el-button size="small" style="margin-top:6px" @click="microForm.ast.push({ antibiotic:'', method:'', micValue:'', sir:'S' })">加一行药敏</el-button>
      <template #footer>
        <el-button @click="microDialog = false">取消</el-button>
        <el-button type="primary" @click="submitMicro" :loading="microLoading">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const tab = ref('micro')
const microSamples = ref<Record<string, unknown>[]>([])
const qcLatest = ref<Record<string, unknown>[]>([])
const qc = reactive({ itemCode: '', level: 'L1', lotNo: '', targetValue: undefined as number | undefined, sd: undefined as number | undefined, measuredValue: undefined as number | undefined })
const qcResult = ref<{ inControl: boolean; zScore: number; rule: string } | null>(null)
const tatRange = ref<[string, string] | null>(null)
const tat = ref<Record<string, unknown> | null>(null)

const microDialog = ref(false)
const microLoading = ref(false)
const microCur = ref<Record<string, unknown> | null>(null)
const microForm = reactive({ specimen: '', organism: '', gram: 'NEG', colonyCount: '', ast: [] as Record<string, string>[] })

async function loadMicro() { microSamples.value = (await client.get('/lis/micro/samples')).data.data }
async function loadQcLatest() { qcLatest.value = (await client.get('/lis/qc/latest')).data.data }
async function loadTat() {
  const params: Record<string, unknown> = { from: tatRange.value?.[0], to: tatRange.value?.[1] }
  tat.value = (await client.get('/lis/tat', { params })).data.data
}
function onTab() {
  if (tab.value === 'micro') loadMicro()
  else if (tab.value === 'qc') loadQcLatest()
  else loadTat()
}

function openMicro(row: Record<string, unknown>) {
  microCur.value = row
  Object.assign(microForm, { specimen: '', organism: '', gram: 'NEG', colonyCount: '', ast: [{ antibiotic: '', method: '', micValue: '', sir: 'S' }] })
  microDialog.value = true
}
async function submitMicro() {
  if (!microForm.organism.trim()) { ElMessage.warning('菌种名必填'); return }
  microLoading.value = true
  try {
    await client.post(`/lis/micro/${microCur.value!.barcode}`, { ...microForm, ast: microForm.ast.filter(a => a.antibiotic) })
    ElMessage.success('已保存微生物药敏')
    microDialog.value = false
    await loadMicro()
  } finally { microLoading.value = false }
}

async function submitQc() {
  if (!qc.itemCode || !qc.lotNo || qc.targetValue == null || qc.sd == null || qc.measuredValue == null) { ElMessage.warning('请填齐项目/批号/靶值/SD/实测'); return }
  qcResult.value = (await client.post('/lis/qc', qc)).data.data
  await loadQcLatest()
}

onMounted(loadMicro)
</script>
