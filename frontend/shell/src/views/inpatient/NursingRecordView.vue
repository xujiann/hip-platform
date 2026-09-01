<template>
  <el-card>
    <div class="toolbar">
      <h3>护理文书 · 护理记录单 / 日常巡视</h3>
      <el-button link type="primary" @click="reload">刷新</el-button>
    </div>

    <el-form inline size="small">
      <el-form-item label="患者">
        <el-select v-model="admissionId" filterable placeholder="选择在院患者" style="width: 280px"
                   @change="reload">
          <el-option v-for="b in board" :key="Number(b.admission_id)" :value="Number(b.admission_id)"
                     :label="`${b.ward_name} ${b.bed_no}床 ${b.patient_name}（${b.admission_no}）`" />
        </el-select>
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="kind" clearable placeholder="全部" style="width: 130px" @change="loadRecords">
          <el-option v-for="k in KINDS" :key="k.value" :label="k.label" :value="k.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="起">
        <el-date-picker v-model="from" type="date" value-format="YYYY-MM-DD" style="width: 140px"
                        @change="loadRecords" />
      </el-form-item>
      <el-form-item label="止">
        <el-date-picker v-model="to" type="date" value-format="YYYY-MM-DD" style="width: 140px"
                        @change="loadRecords" />
      </el-form-item>
      <el-button type="primary" size="small" :disabled="!admissionId" @click="openAdd">新增记录</el-button>
      <el-button size="small" :disabled="!admissionId" @click="doPrint">打印记录单</el-button>
    </el-form>

    <el-alert v-if="gate" :closable="false" show-icon style="margin-bottom: 8px"
              :type="gate.complete ? 'success' : 'warning'"
              :title="gate.complete ? '护理文书齐备' : `护理文书提示：${(gate.missing as string[]).join('、')}`"
              :description="`护理观察 ${gate.observe_count} 条 / 日常巡视 ${gate.rounds_count} 条 / 护理措施 ${gate.measure_count} 条`
                            + `　｜　挡点开关 emr.gate.nursing.record=${gate.gate}（默认 off：只提示不拦出院与归档）`" />

    <el-table :data="records" v-loading="loading" size="small" border stripe>
      <el-table-column prop="record_time" label="护理时间" width="170" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="kindTagType(String(row.record_kind))">
            {{ kindName(String(row.record_kind)) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="observation" label="病情观察" show-overflow-tooltip />
      <el-table-column prop="measure" label="护理措施" show-overflow-tooltip />
      <el-table-column prop="effect" label="效果评价" show-overflow-tooltip width="160" />
      <el-table-column prop="nurse_name" label="护士" width="90" />
      <el-table-column label="签名" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.signed ? 'success' : 'info'">
            {{ row.signed ? '已签名' : '未签名' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <template v-if="!row.signed">
            <el-button link type="primary" size="small" :loading="busyId === row.id"
                       @click="openEdit(row)">修改</el-button>
            <el-button link type="success" size="small" :loading="busyId === row.id"
                       @click="sign(row)">签名</el-button>
          </template>
          <span v-else class="frozen">已冻结</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="form.id ? '修改护理记录' : '新增护理记录'" width="560px">
      <el-form label-width="90px" size="small">
        <el-form-item label="记录类型">
          <el-select v-model="form.recordKind" style="width: 160px">
            <el-option v-for="k in KINDS" :key="k.value" :label="k.label" :value="k.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="护理时间">
          <el-date-picker v-model="form.recordTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
                          placeholder="不填为当前时间（夜班补录请显式填写）" style="width: 100%" />
        </el-form-item>
        <el-form-item label="病情观察">
          <el-input v-model="form.observation" type="textarea" :rows="3"
                    placeholder="如：神志清、T38.2℃、伤口敷料干燥无渗出" />
        </el-form-item>
        <el-form-item label="护理措施">
          <el-input v-model="form.measure" type="textarea" :rows="3"
                    placeholder="如：物理降温、协助翻身、口腔护理、管道固定并标识" />
        </el-form-item>
        <el-form-item label="效果评价">
          <el-input v-model="form.effect" placeholder="如：30 分钟后复测 T37.4℃" />
        </el-form-item>
        <el-form-item label="措施码">
          <el-input v-model="form.measureCode" placeholder="可空（实施期接院内护理措施字典）" />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon
                title="病情观察与护理措施至少填一项；保存后可签名，签名即冻结不可修改。" />
      <template #footer>
        <el-button size="small" @click="dialog = false">取消</el-button>
        <el-button type="primary" size="small" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

/** 巡视与护理观察同表按类型区分（后端 nur_record.record_kind），前端只做展示映射 */
const KINDS = [
  { value: 'OBSERVE', label: '护理观察' },
  { value: 'ROUNDS', label: '日常巡视' },
  { value: 'MEASURE', label: '护理措施' },
]

const route = useRoute()
const board = ref<Record<string, unknown>[]>([])
const records = ref<Record<string, unknown>[]>([])
const gate = ref<Record<string, unknown> | null>(null)
const admissionId = ref<number | undefined>(
  route.query.admissionId ? Number(route.query.admissionId) : undefined,
)
const kind = ref('')
const from = ref('')
const to = ref('')
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const busyId = ref<unknown>(null)
const form = reactive({
  id: 0, recordKind: 'OBSERVE', recordTime: '', observation: '', measure: '',
  effect: '', measureCode: '',
})

function kindName(v: string) {
  return KINDS.find((k) => k.value === v)?.label ?? v
}
function kindTagType(v: string) {
  return v === 'ROUNDS' ? 'warning' : v === 'MEASURE' ? 'success' : 'primary'
}

async function loadBoard() {
  board.value = (await client.get('/inpatient/nursing/board')).data.data
  if (!admissionId.value && board.value.length) {
    admissionId.value = Number(board.value[0].admission_id)
  }
}

async function loadRecords() {
  if (!admissionId.value) return
  loading.value = true
  try {
    const params: Record<string, unknown> = { admissionId: admissionId.value }
    if (kind.value) params.kind = kind.value
    if (from.value) params.from = from.value
    if (to.value) params.to = to.value
    records.value = (await client.get('/nursing/records', { params })).data.data
    gate.value = (await client.get('/nursing/records/gate-check',
      { params: { admissionId: admissionId.value } })).data.data
  } finally {
    loading.value = false
  }
}

async function reload() {
  await loadBoard()
  await loadRecords()
}

function openAdd() {
  Object.assign(form, {
    id: 0, recordKind: 'OBSERVE', recordTime: '', observation: '', measure: '',
    effect: '', measureCode: '',
  })
  dialog.value = true
}

function openEdit(row: Record<string, unknown>) {
  Object.assign(form, {
    id: Number(row.id),
    recordKind: String(row.record_kind),
    recordTime: '',
    observation: String(row.observation ?? ''),
    measure: String(row.measure ?? ''),
    effect: String(row.effect ?? ''),
    measureCode: String(row.measure_code ?? ''),
  })
  dialog.value = true
}

async function save() {
  if (!form.observation.trim() && !form.measure.trim()) {
    ElMessage.warning('病情观察与护理措施至少填一项')
    return
  }
  saving.value = true
  try {
    const body = {
      admissionId: admissionId.value,
      recordKind: form.recordKind,
      recordTime: form.recordTime || undefined,
      observation: form.observation,
      measure: form.measure,
      effect: form.effect,
      measureCode: form.measureCode,
    }
    if (form.id) await client.put(`/nursing/records/${form.id}`, body)
    else await client.post('/nursing/records', body)
    ElMessage.success('已保存')
    dialog.value = false
    await loadRecords()
  } finally {
    saving.value = false
  }
}

async function sign(row: Record<string, unknown>) {
  busyId.value = row.id
  try {
    await client.post(`/nursing/records/${row.id}/sign`)
    ElMessage.success('已签名（签名后不可修改）')
    await loadRecords()
  } finally {
    busyId.value = null
  }
}

function doPrint() {
  const q = new URLSearchParams({ type: 'nur-record', id: String(admissionId.value) })
  if (from.value) q.set('from', from.value)
  if (to.value) q.set('to', to.value)
  if (kind.value) q.set('kind', kind.value)
  window.open(`/print?${q.toString()}`, '_blank')
}

onMounted(reload)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
.frozen { color: #909399; font-size: 12px; }
</style>
