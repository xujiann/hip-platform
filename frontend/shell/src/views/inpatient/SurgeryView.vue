<template>
  <el-card>
    <div class="toolbar">
      <h3>手术麻醉</h3>
      <el-button type="primary" size="small" @click="dialogVisible = true">手术申请</el-button>
    </div>

    <!--
      口径诚实标注（与 v41 床位效率趋势、v42 archived_at 同做法）：
      手术间与四个时间点自 v46 起才采集，历史台次这些列必然为空，**平台不做任何回填推算**。
      写在页面上而不是只写在文档里——看板上一台"没有开台时间"的旧手术不是数据丢了，是当时就没采。
    -->
    <el-alert type="info" :closable="false" show-icon class="notice"
              title="手术间 / 入室·开台·结束·出室 / 手术级别 / ASA / 切口等级 / 手术类别自本版起采集；历史台次这些字段为空，平台不用排台时间等旧字段回填推算，相关质控指标只统计已采集的台次。" />

    <el-tabs v-model="tab">
      <!-- ============ 1397★：以手术间为核心维度的排程视图 ============ -->
      <el-tab-pane label="手术间排程" name="board">
        <div class="bar">
          <el-date-picker v-model="boardDate" type="date" value-format="YYYY-MM-DD" size="small"
                          :clearable="false" style="width: 150px" @change="loadBoard" />
          <el-input v-model="boardRoom" placeholder="手术间（可选）" size="small" clearable
                    style="width: 160px" @change="loadBoard" />
          <el-button size="small" @click="loadBoard">刷新</el-button>
        </div>

        <el-empty v-if="board.length === 0" description="当日无手术台次" />
        <el-card v-for="bucket in board" :key="bucket.roomNo" shadow="never" class="room">
          <template #header>
            <span class="room-name">{{ bucket.roomName }}</span>
            <el-tag size="small" type="info">{{ bucket.count }} 台</el-tag>
          </template>
          <el-table :data="bucket.surgeries" size="small" border>
            <el-table-column prop="bed_no" label="床号" width="70" />
            <el-table-column prop="patient_name" label="患者" width="90" />
            <el-table-column prop="procedure_name" label="术式" min-width="130" />
            <el-table-column label="类别/级别" width="105">
              <template #default="{ row }">{{ row.surgeryKindName || '—' }} / {{ row.surgery_level || '—' }}</template>
            </el-table-column>
            <el-table-column label="ASA/切口" width="100">
              <template #default="{ row }">{{ row.asa_grade || '—' }} / {{ row.incision_type || '—' }}</template>
            </el-table-column>
            <el-table-column label="入室→开台→结束→出室" width="215">
              <template #default="{ row }">
                <span class="tp">{{ hm(row.in_room_at) }} → {{ hm(row.start_at) }} → {{ hm(row.end_at) }} → {{ hm(row.out_room_at) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="时长" width="70">
              <template #default="{ row }">{{ row.durationMin == null ? '—' : row.durationMin + '分' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="140">
              <template #default="{ row }">
                <el-tag :type="statusTag(String(row.status))" size="small">{{ statusName(String(row.status)) }}</el-tag>
                <el-tag v-if="row.status !== 'CANCELLED'" size="small" type="info" class="phase">
                  {{ PHASE[String(row.phase)] }}
                </el-tag>
                <el-tooltip v-else :content="`${row.cancelStageName || ''}取消：${row.cancel_reason || ''}`">
                  <el-tag size="small" type="danger" class="phase">{{ row.cancelStageName }}</el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="250" fixed="right">
              <template #default="{ row }">
                <el-dropdown v-if="actionable(row)" size="small" @command="(c: string) => punch(row, c)">
                  <el-button link type="primary" size="small" :loading="busyId === row.id">打点</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item v-for="s in TP_STAGES" :key="s.code" :command="s.code">{{ s.name }}</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <el-button v-if="row.status !== 'CANCELLED'" link size="small" @click="openEdit(row)">术中信息</el-button>
                <el-button v-if="actionable(row)" link type="success" size="small" @click="complete(row)">术后记录</el-button>
                <el-button v-if="actionable(row)" link type="danger" size="small" @click="openCancel(row)">取消</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ============ 近期台次：沿用 v17 起的既有列表端点，返回分量一个未加 ============ -->
      <el-tab-pane label="近期台次" name="list">
        <el-table :data="records" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="170" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="procedure_name" label="术式" min-width="140" />
          <el-table-column prop="anesthesia_type" label="麻醉方式" width="110" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusTag(String(row.status))" size="small">{{ statusName(String(row.status)) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-dropdown v-if="actionable(row)" size="small" @command="(c: string) => punch(row, c)">
                <el-button link type="primary" size="small" :loading="busyId === row.id">打点</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-for="s in TP_STAGES" :key="s.code" :command="s.code">{{ s.name }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-button v-if="row.status !== 'CANCELLED'" link size="small" @click="openEdit(row)">术中信息</el-button>
              <el-button v-if="actionable(row)" link type="success" size="small" @click="complete(row)">术后记录</el-button>
              <el-button v-if="actionable(row)" link type="danger" size="small" @click="openCancel(row)">取消</el-button>
              <el-tooltip v-if="row.status === 'DONE'" :content="`术中：${row.op_note} / 麻醉：${row.anes_note}`">
                <el-button link size="small">查看记录</el-button>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 手术申请（既有流程，一字未改） ============ -->
    <el-dialog v-model="dialogVisible" title="手术申请" width="460px">
      <el-form label-width="90px">
        <el-form-item label="在院患者">
          <el-select v-model="form.admissionId" style="width: 100%">
            <el-option v-for="a in admissions" :key="a.id as number"
                       :label="`${a.bedNo}床 ${a.patientName}`" :value="a.id as number" />
          </el-select>
        </el-form-item>
        <el-form-item label="术式"><el-input v-model="form.procedureName" /></el-form-item>
        <el-form-item label="麻醉方式">
          <el-select v-model="form.anesthesiaType" style="width: 100%">
            <el-option v-for="t in ['全身麻醉', '椎管内麻醉', '局部麻醉', '神经阻滞']" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="排台时间">
          <el-date-picker v-model="form.scheduledAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saveLoading" @click="save">提交</el-button>
      </template>
    </el-dialog>

    <!-- ============ 术中信息维护 + 时间点补录/更正 ============ -->
    <el-dialog v-model="editVisible" title="术中信息与时间点" width="560px">
      <el-form label-width="120px" size="small">
        <el-form-item label="患者 / 术式"><span>{{ edit.patient }} · {{ edit.procedure }}</span></el-form-item>
        <el-form-item label="手术间">
          <el-input v-model="edit.roomNo" maxlength="16" placeholder="如 OR-01" />
        </el-form-item>
        <el-form-item label="手术级别">
          <el-select v-model="edit.surgeryLevel" clearable style="width: 100%">
            <el-option v-for="v in dict.surgeryLevels" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>
        <el-form-item label="ASA 分级">
          <el-select v-model="edit.asaGrade" clearable style="width: 100%">
            <el-option v-for="v in dict.asaGrades" :key="v" :label="`ASA ${v} 级`" :value="v" />
          </el-select>
        </el-form-item>
        <el-form-item label="切口等级">
          <el-select v-model="edit.incisionType" clearable style="width: 100%">
            <el-option v-for="v in dict.incisionTypes" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>
        <el-form-item label="手术类别">
          <el-select v-model="edit.surgeryKind" clearable style="width: 100%">
            <el-option v-for="o in dict.surgeryKinds" :key="o.code" :label="o.name" :value="o.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="非计划再次手术">
          <el-switch v-model="edit.unplannedReop" />
        </el-form-item>
        <el-divider content-position="left">时间点（留空即未采集，平台不推算）</el-divider>
        <el-form-item v-for="s in TP_STAGES" :key="s.code" :label="s.name">
          <el-date-picker v-model="edit.tp[s.code]" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                          style="width: 100%" placeholder="未采集" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editLoading" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 1426★ 取消手术：四阶段 + 原因，取消不删记录 ============ -->
    <el-dialog v-model="cancelVisible" title="取消手术" width="460px">
      <el-alert type="warning" :closable="false" show-icon class="notice"
                title="取消不会删除该台手术记录——取消率与取消阶段构成要按阶段分别计数。已完成的手术不可取消。" />
      <el-form label-width="90px" size="small">
        <el-form-item label="取消阶段">
          <el-select v-model="cancelForm.stage" style="width: 100%">
            <el-option v-for="o in dict.cancelStages" :key="o.code" :label="o.name" :value="o.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="取消原因">
          <el-input v-model="cancelForm.reason" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cancelVisible = false">返回</el-button>
        <el-button type="danger" :loading="cancelLoading" @click="submitCancel">确认取消</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

type Row = Record<string, unknown>
interface CodeName { code: string; name: string }
interface Bucket { roomNo: string; roomName: string; count: number; surgeries: Row[] }

/** 四个时间点。写死在前端是**兜底**：/dict 拿不到时打点按钮仍可用，不留死按钮 */
const TP_STAGES: CodeName[] = [
  { code: 'IN_ROOM', name: '入室' },
  { code: 'START', name: '开台' },
  { code: 'END', name: '结束' },
  { code: 'OUT_ROOM', name: '出室' },
]
/** 由四个时间点**派生**的进行阶段（后端 SurgeryService.phaseOf）——刻意不落 status，见后端类注释 */
const PHASE: Record<string, string> = {
  WAITING: '待入室', IN_ROOM: '已入室', OPERATING: '手术中', CLOSED: '已结束', OUT: '已出室',
}

const tab = ref('board')
const records = ref<Row[]>([])
const admissions = ref<Row[]>([])
const board = ref<Bucket[]>([])
const boardDate = ref(localDate(new Date()))
const boardRoom = ref('')

const dict = reactive({
  surgeryLevels: [] as string[],
  asaGrades: [] as string[],
  incisionTypes: [] as string[],
  surgeryKinds: [] as CodeName[],
  cancelStages: [] as CodeName[],
})

const dialogVisible = ref(false)
const form = reactive({ admissionId: null as number | null, procedureName: '', anesthesiaType: '全身麻醉', scheduledAt: '' })
const saveLoading = ref(false)
const busyId = ref<unknown>(null)

const editVisible = ref(false)
const editLoading = ref(false)
const edit = reactive({
  id: 0, patient: '', procedure: '', roomNo: '', surgeryLevel: '', asaGrade: '',
  incisionType: '', surgeryKind: '', unplannedReop: false,
  tp: {} as Record<string, string>,
  /** 打开时的快照：保存时**只提交改动过的时间点**，避免把没碰过的值原样回写一遍 */
  tp0: {} as Record<string, string>,
})

const cancelVisible = ref(false)
const cancelLoading = ref(false)
const cancelForm = reactive({ id: 0, stage: 'SCHEDULE', reason: '' })

/** 已完成/已取消的台次不再打点、不再术后记录、不再取消（后端同样拦，前端只是不给死按钮） */
function actionable(row: Row): boolean {
  return row.status !== 'DONE' && row.status !== 'CANCELLED'
}

const pad = (n: number) => String(n).padStart(2, '0')

/** 本地日历日（不用 toISOString——那是 UTC，北京时间 00:00-08:00 会退一天） */
function localDate(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 后端 timestamptz → el-date-picker 的 `YYYY-MM-DDTHH:mm:ss` 本地字面量 */
function localIso(v: unknown): string {
  if (!v) return ''
  const d = new Date(String(v))
  if (Number.isNaN(d.getTime())) return ''
  return `${localDate(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function hm(v: unknown): string {
  if (!v) return '—'
  const d = new Date(String(v))
  return Number.isNaN(d.getTime()) ? '—' : `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function statusName(s: string): string {
  return ({ REQUESTED: '已申请', SCHEDULED: '已排台', DONE: '已完成', CANCELLED: '已取消' } as Record<string, string>)[s] ?? s
}
function statusTag(s: string): 'success' | 'warning' | 'info' {
  if (s === 'DONE') return 'success'
  if (s === 'CANCELLED') return 'info'
  return 'warning'
}

async function loadDict() {
  const d = (await client.get('/inpatient/surgeries/dict')).data.data as Record<string, unknown>
  dict.surgeryLevels = (d.surgeryLevels as string[]) ?? []
  dict.asaGrades = (d.asaGrades as string[]) ?? []
  dict.incisionTypes = (d.incisionTypes as string[]) ?? []
  dict.surgeryKinds = (d.surgeryKinds as CodeName[]) ?? []
  dict.cancelStages = (d.cancelStages as CodeName[]) ?? []
}

async function loadBoard() {
  board.value = (await client.get('/inpatient/surgeries/room-board', {
    params: { date: boardDate.value, roomNo: boardRoom.value || undefined },
  })).data.data
}

async function load() {
  records.value = (await client.get('/inpatient/surgeries')).data.data
  admissions.value = (await client.get('/inpatient/admissions')).data.data
  await loadBoard()
}

async function save() {
  if (!form.admissionId || !form.procedureName) {
    ElMessage.warning('患者与术式必填')
    return
  }
  saveLoading.value = true
  try {
    await client.post('/inpatient/surgeries', form)
    ElMessage.success('已提交')
    dialogVisible.value = false
    await load()
  } finally { saveLoading.value = false }
}

/** 打点：不传时间即以当前时刻登记；先后颠倒由后端 4902 拦下（拦截器统一弹红字） */
async function punch(row: Row, stage: string) {
  busyId.value = row.id
  try {
    await client.put(`/inpatient/surgeries/${row.id}/timepoint`, { stage })
    ElMessage.success(`已登记${TP_STAGES.find((s) => s.code === stage)?.name ?? ''}时间`)
    await load()
  } catch { /* 业务码红字由拦截器统一弹出 */ } finally { busyId.value = null }
}

const TP_COLUMN: Record<string, string> = {
  IN_ROOM: 'in_room_at', START: 'start_at', END: 'end_at', OUT_ROOM: 'out_room_at',
}

async function openEdit(row: Row) {
  const d = (await client.get(`/inpatient/surgeries/${row.id}/detail`)).data.data as Record<string, unknown>
  edit.id = Number(d.id)
  edit.patient = String(d.patient_name ?? '')
  edit.procedure = String(d.procedure_name ?? '')
  edit.roomNo = d.room_no ? String(d.room_no) : ''
  edit.surgeryLevel = d.surgery_level ? String(d.surgery_level) : ''
  edit.asaGrade = d.asa_grade ? String(d.asa_grade) : ''
  edit.incisionType = d.incision_type ? String(d.incision_type) : ''
  edit.surgeryKind = d.surgery_kind ? String(d.surgery_kind) : ''
  edit.unplannedReop = d.is_unplanned_reop === true
  edit.tp = {}
  edit.tp0 = {}
  for (const s of TP_STAGES) {
    const v = localIso(d[TP_COLUMN[s.code]])
    edit.tp[s.code] = v
    edit.tp0[s.code] = v
  }
  editVisible.value = true
}

async function saveEdit() {
  editLoading.value = true
  try {
    // 时间点按**正序**逐个提交（入室→开台→结束→出室）：正序时"新值与库中已有值的先后关系"
    // 与最终态一致，倒序提交会在中途误触 4902。真正的先后颠倒仍照样被后端拦下。
    for (const s of TP_STAGES) {
      const v = edit.tp[s.code]
      if (v && v !== edit.tp0[s.code]) {
        await client.put(`/inpatient/surgeries/${edit.id}/timepoint`, { stage: s.code, at: v })
      }
    }
    await client.put(`/inpatient/surgeries/${edit.id}/op-info`, {
      roomNo: edit.roomNo || null,
      surgeryLevel: edit.surgeryLevel || null,
      asaGrade: edit.asaGrade || null,
      incisionType: edit.incisionType || null,
      surgeryKind: edit.surgeryKind || null,
      unplannedReop: edit.unplannedReop,
    })
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
  } catch { /* 业务码红字由拦截器统一弹出 */ } finally { editLoading.value = false }
}

function openCancel(row: Row) {
  cancelForm.id = Number(row.id)
  // 预选与当前状态相符的阶段：只申请未排台 → 申请阶段，已排台 → 排程阶段（仍可改）
  cancelForm.stage = row.status === 'REQUESTED' ? 'APPLY' : 'SCHEDULE'
  cancelForm.reason = ''
  cancelVisible.value = true
}

async function submitCancel() {
  if (!cancelForm.reason.trim()) {
    ElMessage.warning('取消原因必填')
    return
  }
  cancelLoading.value = true
  try {
    await client.put(`/inpatient/surgeries/${cancelForm.id}/cancel`,
      { stage: cancelForm.stage, reason: cancelForm.reason.trim() })
    ElMessage.success('已取消该台手术（记录保留，计入取消阶段统计）')
    cancelVisible.value = false
    await load()
  } catch { /* 业务码红字由拦截器统一弹出 */ } finally { cancelLoading.value = false }
}

async function complete(row: Row) {
  const res1 = await ElMessageBox.prompt('术中记录', '术后记录', { inputValue: '手术顺利，出血约 50ml' }).catch(() => null)
  if (!res1) return
  const opNote = res1.value
  const res2 = await ElMessageBox.prompt('麻醉记录', '术后记录', { inputValue: '麻醉平稳，苏醒完全' }).catch(() => null)
  if (!res2) return
  const anesNote = res2.value
  busyId.value = row.id
  try {
    await client.put(`/inpatient/surgeries/${row.id}/complete`, { opNote, anesNote })
    await load()
  } finally { busyId.value = null }
}

onMounted(async () => {
  await loadDict()
  await load()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
.notice { margin-bottom: 12px; }
.bar { display: flex; gap: 8px; margin-bottom: 12px; }
.room { margin-bottom: 12px; }
.room-name { font-weight: 600; margin-right: 8px; }
.tp { font-family: Consolas, Menlo, monospace; }
.phase { margin-left: 4px; }
</style>
