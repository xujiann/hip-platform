<template>
  <el-card>
    <div class="toolbar">
      <h3>术中记录 · 管路 / 输血 / 术中事件</h3>
      <el-button link type="primary" @click="reload">刷新</el-button>
    </div>

    <el-form inline size="small">
      <el-form-item label="手术">
        <el-select v-model="surgeryId" filterable placeholder="选择手术" style="width: 420px"
                   @change="loadSummary">
          <el-option v-for="s in surgeries" :key="Number(s.id)" :value="Number(s.id)"
                     :label="`${s.patient_name}（${s.admission_no}）　${s.procedure_name}　${statusName(String(s.status))}`" />
        </el-select>
      </el-form-item>
    </el-form>

    <el-alert v-if="surgeryId" :closable="false" show-icon type="info" style="margin-bottom: 10px"
              :title="`管路 ${num(tubeSummary.total)} 条（未拔除 ${num(tubeSummary.unremoved)} 条）`
                      + `　｜　术中输血 ${num(transfusionSummary.records)} 条 / 共 ${num(transfusionSummary.total_ml)}ml`
                      + `（自体血 ${num(transfusionSummary.auto_ml)}ml、非自体血 ${num(transfusionSummary.non_auto_ml)}ml）`
                      + `　｜　术中事件 ${events.length} 条`"
              description="自体血以「是否自体血」标志统计，与血制品类型分开：自体洗涤红细胞按「红细胞 + 勾选自体血」录入同样计入自体血。" />

    <el-tabs v-model="tab" v-loading="loading">
      <!-- ===== 管路 ===== -->
      <el-tab-pane name="tube" :label="`管路（${tubes.length}）`">
        <el-button type="primary" size="small" :disabled="!surgeryId" @click="openTube()">新增管路</el-button>
        <el-table :data="tubes" size="small" border stripe style="margin-top: 8px">
          <el-table-column prop="tube_type" label="管路类型" width="130" />
          <el-table-column prop="position" label="放置位置" width="150" />
          <el-table-column label="深度(cm)" width="90">
            <template #default="{ row }">{{ row.depth_cm ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="inserted_at" label="置管时间" width="180" />
          <el-table-column label="拔除时间" width="180">
            <template #default="{ row }">
              <el-tag v-if="!row.removed_at" size="small" type="warning">未拔除</el-tag>
              <span v-else>{{ row.removed_at }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="operator_name" label="记录人" width="90" />
          <el-table-column prop="remark" label="备注" show-overflow-tooltip />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openTube(row)">修改</el-button>
              <el-button link type="danger" size="small" @click="removeRow('tubes', row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>暂无管路记录</template>
        </el-table>
      </el-tab-pane>

      <!-- ===== 术中输血 ===== -->
      <el-tab-pane name="transfusion" :label="`术中输血（${transfusions.length}）`">
        <el-button type="primary" size="small" :disabled="!surgeryId" @click="openTransfusion()">新增输血</el-button>
        <el-table :data="transfusions" size="small" border stripe style="margin-top: 8px">
          <el-table-column prop="product_name" label="血制品" width="110" />
          <el-table-column label="是否自体血" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.is_auto ? 'success' : 'info'">
                {{ row.is_auto ? '自体血' : '异体血' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="volume_ml" label="输血量(ml)" width="110" />
          <el-table-column prop="transfused_at" label="输注时间" width="180" />
          <el-table-column prop="operator_name" label="记录人" width="90" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openTransfusion(row)">修改</el-button>
              <el-button link type="danger" size="small" @click="removeRow('transfusions', row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>暂无术中输血记录</template>
        </el-table>
      </el-tab-pane>

      <!-- ===== 术中事件 ===== -->
      <el-tab-pane name="event" :label="`术中事件（${events.length}）`">
        <el-button type="primary" size="small" :disabled="!surgeryId" @click="openEvent()">新增事件</el-button>
        <el-table :data="events" size="small" border stripe style="margin-top: 8px">
          <el-table-column prop="event_name" label="事件类型" width="130" />
          <el-table-column prop="event_time" label="发生时间" width="180" />
          <el-table-column label="计划性" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.planned === true" size="small" type="success">计划</el-tag>
              <el-tag v-else-if="row.planned === false" size="small" type="danger">非计划</el-tag>
              <span v-else class="muted">未区分</span>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="明细" show-overflow-tooltip />
          <el-table-column prop="operator_name" label="记录人" width="90" />
          <el-table-column prop="remark" label="备注" show-overflow-tooltip width="160" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEvent(row)">修改</el-button>
              <el-button link type="danger" size="small" @click="removeRow('events', row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>暂无术中事件记录</template>
        </el-table>
        <el-table v-if="eventSummary.length" :data="eventSummary" size="small" border
                  style="margin-top: 10px; max-width: 620px">
          <el-table-column prop="event_name" label="事件类型汇总" width="150" />
          <el-table-column prop="total" label="合计" width="80" />
          <el-table-column prop="planned_count" label="计划" width="80" />
          <el-table-column prop="unplanned_count" label="非计划" width="80" />
          <el-table-column prop="unspecified_count" label="未区分" width="80" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- ===== 管路弹窗 ===== -->
    <el-dialog v-model="tubeDialog" :title="tubeForm.id ? '修改管路记录' : '新增管路记录'" width="520px">
      <el-form label-width="90px" size="small">
        <el-form-item label="管路类型">
          <el-select v-model="tubeForm.tubeType" style="width: 100%">
            <el-option v-for="t in dict.tubeTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="放置位置">
          <el-input v-model="tubeForm.position" placeholder="如：右颈内静脉 / 左桡动脉 / 腹腔" />
        </el-form-item>
        <el-form-item label="插入深度">
          <el-input-number v-model="tubeForm.depthCm" :min="0" :max="200" :precision="1" :step="0.5" />
          <span class="muted" style="margin-left: 8px">厘米（尿管等无深度可留空）</span>
        </el-form-item>
        <el-form-item label="置管时间">
          <el-date-picker v-model="tubeForm.insertedAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
                          placeholder="不填为当前时间（术前病房已置的管子请显式填写）" style="width: 100%" />
        </el-form-item>
        <el-form-item label="拔除时间">
          <el-date-picker v-model="tubeForm.removedAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
                          placeholder="留空表示尚未拔除" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="tubeForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="tubeDialog = false">取消</el-button>
        <el-button type="primary" size="small" :loading="saving" @click="saveTube">保存</el-button>
      </template>
    </el-dialog>

    <!-- ===== 输血弹窗 ===== -->
    <el-dialog v-model="transfusionDialog"
               :title="transfusionForm.id ? '修改术中输血' : '新增术中输血'" width="520px">
      <el-form label-width="90px" size="small">
        <el-form-item label="血制品">
          <el-select v-model="transfusionForm.productType" style="width: 100%" @change="onProductChange">
            <el-option v-for="p in dict.productTypes" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="是否自体血">
          <el-switch v-model="transfusionForm.isAuto" :disabled="transfusionForm.productType === 'AUTO'" />
          <span class="muted" style="margin-left: 8px">
            选「自体血」时强制为是；自体洗涤红细胞请选「红细胞」并在此打开
          </span>
        </el-form-item>
        <el-form-item label="输血量">
          <el-input-number v-model="transfusionForm.volumeMl" :min="1" :max="20000" :step="50" />
          <span class="muted" style="margin-left: 8px">毫升（须大于 0）</span>
        </el-form-item>
        <el-form-item label="输注时间">
          <el-date-picker v-model="transfusionForm.transfusedAt" type="datetime"
                          value-format="YYYY-MM-DD HH:mm:ss" placeholder="不填为当前时间" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="transfusionDialog = false">取消</el-button>
        <el-button type="primary" size="small" :loading="saving" @click="saveTransfusion">保存</el-button>
      </template>
    </el-dialog>

    <!-- ===== 事件弹窗 ===== -->
    <el-dialog v-model="eventDialog" :title="eventForm.id ? '修改术中事件' : '新增术中事件'" width="520px">
      <el-form label-width="90px" size="small">
        <el-form-item label="事件类型">
          <el-select v-model="eventForm.eventType" style="width: 100%">
            <el-option v-for="e in dict.eventTypes" :key="e.value" :label="e.label" :value="e.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="发生时间">
          <el-date-picker v-model="eventForm.eventTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
                          placeholder="不填为当前时间" style="width: 100%" />
        </el-form-item>
        <el-form-item label="计划性">
          <el-select v-model="eventForm.plannedFlag" style="width: 160px">
            <el-option label="计划" value="Y" />
            <el-option label="非计划" value="N" />
            <el-option label="未区分" value="" />
          </el-select>
        </el-form-item>
        <el-form-item label="明细">
          <el-input v-model="eventForm.detail" type="textarea" :rows="2"
                    placeholder="如：右桡动脉穿刺置管 / 颈外静脉穿刺置管 / 转入外科 ICU" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="eventForm.remark" /></el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon
                title="转入 ICU / 苏醒室、拔管与再次插管须区分计划与非计划；有创操作与抢救可留「未区分」。事件时间不得早于入室时间。" />
      <template #footer>
        <el-button size="small" @click="eventDialog = false">取消</el-button>
        <el-button type="primary" size="small" :loading="saving" @click="saveEvent">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

type Row = Record<string, unknown>

interface Dict {
  tubeTypes: string[]
  productTypes: { value: string; label: string; auto: boolean }[]
  eventTypes: { value: string; label: string; plannedRelevant: boolean; inRoomOnly: boolean }[]
}

const route = useRoute()
const surgeries = ref<Row[]>([])
const surgeryId = ref<number | undefined>(
  route.query.surgeryId ? Number(route.query.surgeryId) : undefined,
)
const dict = ref<Dict>({ tubeTypes: [], productTypes: [], eventTypes: [] })
const tubes = ref<Row[]>([])
const transfusions = ref<Row[]>([])
const events = ref<Row[]>([])
const eventSummary = ref<Row[]>([])
const tubeSummary = ref<Row>({})
const transfusionSummary = ref<Row>({})
const tab = ref('tube')
const loading = ref(false)
const saving = ref(false)

const tubeDialog = ref(false)
const transfusionDialog = ref(false)
const eventDialog = ref(false)

const tubeForm = reactive({
  id: 0, tubeType: '', position: '', depthCm: undefined as number | undefined,
  insertedAt: '', removedAt: '', remark: '',
})
const transfusionForm = reactive({
  id: 0, productType: 'RBC', isAuto: false, volumeMl: 200 as number | undefined, transfusedAt: '',
})
/** plannedFlag 三态：'Y' 计划 / 'N' 非计划 / '' 未区分（后端 planned 为可空布尔） */
const eventForm = reactive({
  id: 0, eventType: '', eventTime: '', plannedFlag: '', detail: '', remark: '',
})

function num(v: unknown) {
  return Number(v ?? 0)
}

function statusName(v: string) {
  return ({ REQUESTED: '已申请', SCHEDULED: '已排台', DONE: '已完成', CANCELLED: '已取消' } as Record<string, string>)[v] ?? v
}

async function loadSurgeries() {
  surgeries.value = (await client.get('/inpatient/surgeries')).data.data
  if (!surgeryId.value && surgeries.value.length) surgeryId.value = Number(surgeries.value[0].id)
}

async function loadDict() {
  dict.value = (await client.get('/surgery/intraop/dict')).data.data as Dict
  if (!tubeForm.tubeType && dict.value.tubeTypes.length) tubeForm.tubeType = dict.value.tubeTypes[0]
  if (!eventForm.eventType && dict.value.eventTypes.length) eventForm.eventType = dict.value.eventTypes[0].value
}

async function loadSummary() {
  if (!surgeryId.value) return
  loading.value = true
  try {
    const d = (await client.get('/surgery/intraop/summary',
      { params: { surgeryId: surgeryId.value } })).data.data as Record<string, unknown>
    tubes.value = (d.tubes ?? []) as Row[]
    transfusions.value = (d.transfusions ?? []) as Row[]
    events.value = (d.events ?? []) as Row[]
    eventSummary.value = (d.eventSummary ?? []) as Row[]
    tubeSummary.value = (d.tubeSummary ?? {}) as Row
    transfusionSummary.value = (d.transfusionSummary ?? {}) as Row
  } finally {
    loading.value = false
  }
}

async function reload() {
  await loadSurgeries()
  await loadDict()
  await loadSummary()
}

// ===== 管路 =====

function openTube(row?: Row) {
  Object.assign(tubeForm, {
    id: row ? Number(row.id) : 0,
    tubeType: row ? String(row.tube_type) : (dict.value.tubeTypes[0] ?? ''),
    position: row ? String(row.position ?? '') : '',
    depthCm: row && row.depth_cm != null ? Number(row.depth_cm) : undefined,
    insertedAt: '',
    removedAt: '',
    remark: row ? String(row.remark ?? '') : '',
  })
  tubeDialog.value = true
}

async function saveTube() {
  if (!tubeForm.tubeType) {
    ElMessage.warning('请选择管路类型')
    return
  }
  saving.value = true
  try {
    const body = {
      surgeryId: surgeryId.value,
      tubeType: tubeForm.tubeType,
      position: tubeForm.position,
      depthCm: tubeForm.depthCm,
      insertedAt: tubeForm.insertedAt || undefined,
      removedAt: tubeForm.removedAt || undefined,
      remark: tubeForm.remark,
    }
    if (tubeForm.id) await client.put(`/surgery/intraop/tubes/${tubeForm.id}`, body)
    else await client.post('/surgery/intraop/tubes', body)
    ElMessage.success('已保存')
    tubeDialog.value = false
    await loadSummary()
  } finally {
    saving.value = false
  }
}

// ===== 输血 =====

function onProductChange(v: string) {
  if (v === 'AUTO') transfusionForm.isAuto = true
}

function openTransfusion(row?: Row) {
  Object.assign(transfusionForm, {
    id: row ? Number(row.id) : 0,
    productType: row ? String(row.product_type) : 'RBC',
    isAuto: row ? row.is_auto === true : false,
    volumeMl: row ? Number(row.volume_ml) : 200,
    transfusedAt: '',
  })
  transfusionDialog.value = true
}

async function saveTransfusion() {
  if (!transfusionForm.volumeMl || transfusionForm.volumeMl <= 0) {
    ElMessage.warning('输血量须大于 0')
    return
  }
  saving.value = true
  try {
    const body = {
      surgeryId: surgeryId.value,
      productType: transfusionForm.productType,
      volumeMl: transfusionForm.volumeMl,
      isAuto: transfusionForm.isAuto,
      transfusedAt: transfusionForm.transfusedAt || undefined,
    }
    if (transfusionForm.id) await client.put(`/surgery/intraop/transfusions/${transfusionForm.id}`, body)
    else await client.post('/surgery/intraop/transfusions', body)
    ElMessage.success('已保存')
    transfusionDialog.value = false
    await loadSummary()
  } finally {
    saving.value = false
  }
}

// ===== 事件 =====

function openEvent(row?: Row) {
  Object.assign(eventForm, {
    id: row ? Number(row.id) : 0,
    eventType: row ? String(row.event_type) : (dict.value.eventTypes[0]?.value ?? ''),
    eventTime: '',
    plannedFlag: row == null || row.planned == null ? '' : (row.planned === true ? 'Y' : 'N'),
    detail: row ? String(row.detail ?? '') : '',
    remark: row ? String(row.remark ?? '') : '',
  })
  eventDialog.value = true
}

async function saveEvent() {
  if (!eventForm.eventType) {
    ElMessage.warning('请选择事件类型')
    return
  }
  saving.value = true
  try {
    const body = {
      surgeryId: surgeryId.value,
      eventType: eventForm.eventType,
      eventTime: eventForm.eventTime || undefined,
      planned: eventForm.plannedFlag === 'Y' ? true : eventForm.plannedFlag === 'N' ? false : null,
      detail: eventForm.detail,
      remark: eventForm.remark,
    }
    if (eventForm.id) await client.put(`/surgery/intraop/events/${eventForm.id}`, body)
    else await client.post('/surgery/intraop/events', body)
    ElMessage.success('已保存')
    eventDialog.value = false
    await loadSummary()
  } finally {
    saving.value = false
  }
}

// ===== 删除（三类共用）=====

async function removeRow(kind: 'tubes' | 'transfusions' | 'events', row: Row) {
  const label = { tubes: '管路记录', transfusions: '输血记录', events: '术中事件' }[kind]
  const ok = await ElMessageBox.confirm(`确认删除该${label}？`, '删除确认', { type: 'warning' })
    .catch(() => null)
  if (!ok) return
  await client.delete(`/surgery/intraop/${kind}/${Number(row.id)}`)
  ElMessage.success('已删除')
  await loadSummary()
}

onMounted(reload)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
.muted { color: #909399; font-size: 12px; }
</style>
