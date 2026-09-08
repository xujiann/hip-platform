<template>
  <el-card>
    <div class="toolbar">
      <h3>摆药与预调剂 · 待摆药队列 / 摆药单 / 发药窗口</h3>
      <span>
        <el-tag v-if="gates" size="small" :type="gateType(gates[GATE_LINE])" style="margin-right: 6px">
          逐项确认 gate = {{ gates[GATE_LINE] }}
        </el-tag>
        <el-tag v-if="gates" size="small" :type="gateType(gates[GATE_WINDOW])" style="margin-right: 10px">
          窗口分配 gate = {{ gates[GATE_WINDOW] }}
        </el-tag>
        <el-button link type="primary" @click="reload">刷新</el-button>
      </span>
    </div>

    <!-- 本页的边界必须写在页面上：药师看到「已发药」会以为库存已扣，那是另一步 -->
    <el-alert type="info" show-icon :closable="false" class="caveat"
              title="摆药单是发药之前的作业记录：不扣库存、不改医嘱状态"
              description="真正的扣库存与置「已发药」仍由发药核对页完成。本页的「标记已发药」只是把这张作业单收口，好让该挂号下一次能建新单。" />

    <el-tabs v-model="tab" v-loading="loading" @tab-change="onTabChange">
      <!-- ===================== 待摆药队列 ===================== -->
      <el-tab-pane name="queue" :label="`待摆药队列（${queueRows.length}）`">
        <el-alert v-if="queueTruncated" type="warning" show-icon :closable="false" class="caveat"
                  :title="`命中超过 ${queueLimit} 条，仅显示前 ${queueLimit} 条（后端不做翻页）`" />
        <el-table :data="queueRows" size="small" border stripe max-height="480">
          <el-table-column label="加急" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.urgent === true" size="small" type="danger">加急</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="patient_no" label="患者号" width="120" />
          <el-table-column prop="patient_name" label="姓名" width="100" />
          <el-table-column prop="sex" label="性别" width="70" />
          <el-table-column label="就诊日" width="110">
            <template #default="{ row }">{{ fmt(row.visit_date) }}</template>
          </el-table-column>
          <el-table-column label="品种数" width="90" align="right">
            <template #default="{ row }">{{ num(row.item_count) }}</template>
          </el-table-column>
          <el-table-column label="总量" width="90" align="right">
            <template #default="{ row }">{{ num(row.total_qty) }}</template>
          </el-table-column>
          <el-table-column label="已等待" width="110" align="right">
            <template #default="{ row }">
              <span :class="num(row.minutes_since_created) > 30 ? 'warn-text' : ''">
                {{ num(row.minutes_since_created) }} 分钟
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openCreate(row)">建摆药单</el-button>
            </template>
          </el-table-column>
          <template #empty>队列为空：没有已收费待发且未被在途单收走的药品医嘱</template>
        </el-table>
      </el-tab-pane>

      <!-- ===================== 摆药单检索 ===================== -->
      <el-tab-pane name="orders" :label="`摆药单（${orderRows.length}）`">
        <el-form inline size="small">
          <el-form-item label="状态">
            <el-select v-model="statusFilter" multiple collapse-tags clearable placeholder="全部状态"
                       style="width: 240px" @change="loadOrders">
              <el-option v-for="s in STATUSES" :key="s.value" :value="s.value" :label="s.label" />
            </el-select>
          </el-form-item>
          <el-form-item label="窗口">
            <el-select v-model="windowFilter" clearable placeholder="全部窗口" style="width: 160px"
                       @change="loadOrders">
              <el-option v-for="w in windows" :key="String(w.code)" :value="String(w.code)"
                         :label="`${w.code} ${w.name}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="建单日">
            <el-date-picker v-model="range" type="daterange" unlink-panels value-format="YYYY-MM-DD"
                            range-separator="至" start-placeholder="起" end-placeholder="止"
                            style="width: 240px" @change="loadOrders" />
          </el-form-item>
          <el-form-item label="关键词">
            <el-input v-model="keyword" clearable placeholder="单号 / 姓名 / 患者号" style="width: 180px"
                      @change="loadOrders" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="small" @click="loadOrders">查询</el-button>
          </el-form-item>
        </el-form>
        <el-alert v-if="ordersTruncated" type="warning" show-icon :closable="false" class="caveat"
                  :title="`命中超过 ${ordersLimit} 条，仅显示前 ${ordersLimit} 条（后端不做翻页），请缩小条件`" />
        <el-table :data="orderRows" size="small" border stripe max-height="440">
          <el-table-column prop="pick_no" label="摆药单号" width="150" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTagType(String(row.status))">
                {{ statusName(String(row.status)) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="patient_no" label="患者号" width="120" />
          <el-table-column label="窗口" width="140">
            <template #default="{ row }">
              <span v-if="row.window_code">{{ row.window_code }} {{ row.window_name }}</span>
              <el-tag v-else size="small" type="warning">未分配</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="摆药进度" width="120">
            <template #default="{ row }">
              {{ num(row.picked_line_count) }} / {{ num(row.line_count) }}
            </template>
          </el-table-column>
          <el-table-column label="加急" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.urgent === true" size="small" type="danger">加急</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="建单时间" width="160">
            <template #default="{ row }">{{ fmt(row.created_at) }}</template>
          </el-table-column>
          <el-table-column prop="cancel_reason" label="作废原因" min-width="140" show-overflow-tooltip />
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDetail(Number(row.id))">打开</el-button>
            </template>
          </el-table-column>
          <template #empty>无符合条件的摆药单</template>
        </el-table>
      </el-tab-pane>

      <!-- ===================== 发药窗口 ===================== -->
      <el-tab-pane name="windows" :label="`发药窗口（${windows.length}）`">
        <div class="toolbar">
          <el-checkbox v-model="includeDisabled" size="small" @change="loadWindows">含已停用窗口</el-checkbox>
          <el-button type="primary" size="small" @click="openWindow()">新增/修改窗口</el-button>
        </div>
        <el-alert type="info" :closable="false" show-icon class="caveat"
                  title="窗口只停用不删除"
                  description="历史摆药单记着「当时在哪个窗口配的」，删掉窗口会让那件事永久不可考；停用后不能再被分配，历史单照样显示得出来。窗口维护限管理员。" />
        <el-table :data="windows" size="small" border stripe max-height="440">
          <el-table-column prop="code" label="编码" width="120" />
          <el-table-column prop="name" label="名称" min-width="160" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled === true ? 'success' : 'info'">
                {{ row.enabled === true ? '启用' : '已停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="在途单" width="90" align="right">
            <template #default="{ row }">{{ num(row.live_count) }}</template>
          </el-table-column>
          <el-table-column label="已配好待取" width="110" align="right">
            <template #default="{ row }">{{ num(row.prepared_count) }}</template>
          </el-table-column>
          <el-table-column prop="sort_no" label="排序" width="80" align="right" />
          <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openWindow(row)">修改</el-button>
            </template>
          </el-table-column>
          <template #empty>尚未维护发药窗口</template>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <!-- ===================== 摆药单详情抽屉 ===================== -->
  <el-drawer v-model="detailDrawer" size="72%" :title="`摆药单 ${detail?.pick_no ?? ''}`">
    <template v-if="detail">
      <!-- prepared 返回的 warnings 必须显示：warn 档没报错，问题只在这个数组里 -->
      <el-alert v-for="(w, i) in preparedWarnings" :key="i" type="warning" show-icon :closable="false"
                class="caveat" :title="w" />
      <el-descriptions :column="4" border size="small" class="caveat">
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="statusTagType(String(detail.status))">{{ detail.statusName }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="患者">{{ patientLabel }}</el-descriptions-item>
        <el-descriptions-item label="窗口">
          <span v-if="detail.window_code">{{ detail.window_code }}</span>
          <el-tag v-else size="small" type="warning">未分配</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="加急">{{ detail.urgent === true ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="建单时间">{{ fmt(detail.created_at) }}</el-descriptions-item>
        <el-descriptions-item label="开始摆药">{{ fmt(detail.picking_at) }}</el-descriptions-item>
        <el-descriptions-item label="配好时间">{{ fmt(detail.prepared_at) }}</el-descriptions-item>
        <el-descriptions-item label="收口时间">{{ fmt(detail.dispensed_at) }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.cancel_reason" label="作废原因" :span="4">
          <span class="warn-text">{{ detail.cancel_reason }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div class="toolbar">
        <span>
          <el-button v-if="detail.status === 'PENDING'" type="primary" size="small"
                     :loading="saving" @click="act('start')">开始摆药</el-button>
          <el-button v-if="detail.status === 'PICKING'" type="success" size="small"
                     :loading="saving" @click="act('prepared')">预调剂完成</el-button>
          <el-button v-if="detail.status === 'PREPARED'" type="warning" size="small"
                     :loading="saving" @click="act('dispensed')">标记已发药（收口作业单）</el-button>
          <el-button v-if="isLive" size="small" @click="openAssignWindow">分配 / 改派窗口</el-button>
          <el-button v-if="isLive" type="danger" size="small" @click="openCancel">作废</el-button>
        </span>
        <span class="muted">
          已确认 {{ pickedCount }} / {{ activeLines.length }} 行
        </span>
      </div>

      <el-table :data="detail.lines as Row[]" size="small" border stripe max-height="calc(100vh - 400px)">
        <el-table-column prop="item_name" label="药品" min-width="170" show-overflow-tooltip />
        <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip />
        <el-table-column label="应摆" width="90" align="right">
          <template #default="{ row }">{{ num(row.qty) }} {{ row.unit }}</template>
        </el-table-column>
        <el-table-column label="实摆" width="100" align="right">
          <template #default="{ row }">
            <span v-if="row.picked_qty === null || row.picked_qty === undefined" class="muted">未确认</span>
            <span v-else :class="num(row.picked_qty) < num(row.qty) ? 'warn-text' : ''">
              {{ num(row.picked_qty) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="usage_route" label="用法" width="100" />
        <el-table-column prop="frequency" label="频次" width="90" />
        <el-table-column label="用量" width="90" align="right">
          <template #default="{ row }">{{ row.dose_per_time ?? '—' }}</template>
        </el-table-column>
        <!-- 医嘱当前状态：识别陈旧单（建单后患者退了费，这一行就不该再摆） -->
        <el-table-column label="医嘱状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.order_status === 'CHARGED' ? 'info' : 'warning'">
              {{ row.order_status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="占用" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.released === true" size="small" type="info">已释放</el-tag>
            <span v-else class="muted">在途</span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <template v-if="detail && detail.status === 'PICKING' && row.released !== true">
              <el-button v-if="row.picked_at == null" link type="primary" size="small"
                         @click="openPickLine(row)">确认摆药</el-button>
              <el-button v-else link type="warning" size="small"
                         @click="unpickLine(row)">撤销确认</el-button>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <template #empty>本单无明细</template>
      </el-table>
    </template>
  </el-drawer>

  <!-- ===================== 建摆药单 ===================== -->
  <el-dialog v-model="createDialog" title="建摆药单" width="480px">
    <el-alert type="info" :closable="false" show-icon class="caveat"
              title="单据粒度是挂号，不是处方"
              description="该挂号下全部尚未被在途单收走的已收费药品医嘱汇成一张单——患者一次就诊开了三张处方，药师是一次性配好交给他。" />
    <el-form label-width="110px" size="small">
      <el-form-item label="患者">{{ createForm.patientLabel }}</el-form-item>
      <el-form-item label="品种/总量">{{ createForm.itemCount }} 种 / {{ createForm.totalQty }}</el-form-item>
      <el-form-item label="发药窗口">
        <el-select v-model="createForm.windowCode" clearable placeholder="留空由后端按最少负载自动分配"
                   style="width: 100%">
          <el-option v-for="w in enabledWindows" :key="String(w.code)" :value="String(w.code)"
                     :label="`${w.code} ${w.name}（在途 ${num(w.live_count)}）`" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="createDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitCreate">建单</el-button>
    </template>
  </el-dialog>

  <!-- ===================== 逐项确认摆药 ===================== -->
  <el-dialog v-model="pickLineDialog" title="确认摆药（逐项）" width="460px">
    <el-form label-width="110px" size="small">
      <el-form-item label="药品">{{ pickLineForm.itemName }}</el-form-item>
      <el-form-item label="应摆数量">{{ pickLineForm.ordered }}</el-form-item>
      <el-form-item label="实摆数量" required>
        <el-input-number v-model="pickLineForm.pickedQty" :min="1" :max="pickLineForm.ordered" />
        <div class="muted">缺货可少于应摆量（部分摆出），不允许多于应摆量。</div>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="pickLineForm.remark" placeholder="如：库存仅剩 6 盒，余 4 盒待补" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="pickLineDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitPickLine">确认</el-button>
    </template>
  </el-dialog>

  <!-- ===================== 分配窗口 / 作废 ===================== -->
  <el-dialog v-model="assignDialog" title="分配 / 改派发药窗口" width="440px">
    <el-select v-model="assignWindowCode" clearable placeholder="选择窗口（清空则撤销分配）" style="width: 100%">
      <el-option v-for="w in enabledWindows" :key="String(w.code)" :value="String(w.code)"
                 :label="`${w.code} ${w.name}（在途 ${num(w.live_count)}）`" />
    </el-select>
    <template #footer>
      <el-button size="small" @click="assignDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitAssign">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="cancelDialog" title="作废摆药单（原因必填）" width="440px">
    <el-alert type="warning" :closable="false" show-icon class="caveat"
              title="作废不删记录：「建了多少张、废了多少张」是药房的质控分母" />
    <el-input v-model="cancelReason" type="textarea" :rows="3" placeholder="作废原因" />
    <template #footer>
      <el-button size="small" @click="cancelDialog = false">取消</el-button>
      <el-button type="danger" size="small" :loading="saving" @click="submitCancel">确认作废</el-button>
    </template>
  </el-dialog>

  <!-- ===================== 窗口维护 ===================== -->
  <el-dialog v-model="windowDialog" title="发药窗口维护（限管理员）" width="460px">
    <el-form label-width="90px" size="small">
      <el-form-item label="编码" required>
        <el-input v-model="windowForm.code" :disabled="windowForm.editing"
                  placeholder="字母、数字、下划线、连字符，≤16 字" />
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="windowForm.name" placeholder="如：西药一窗" />
      </el-form-item>
      <el-form-item label="启用"><el-switch v-model="windowForm.enabled" /></el-form-item>
      <el-form-item label="排序">
        <el-input-number v-model="windowForm.sortNo" :min="0" :max="999" />
      </el-form-item>
      <el-form-item label="备注"><el-input v-model="windowForm.remark" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="windowDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitWindow">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../../api/client'
import { fmtDateTime } from '../../../utils/date'

type Row = Record<string, unknown>

const BASE = '/outpatient/pharmacy/picking'
const GATE_LINE = 'pharm.gate.picking.line_confirm'
const GATE_WINDOW = 'pharm.gate.picking.window'

const STATUSES = [
  { value: 'PENDING', label: '待摆药' },
  { value: 'PICKING', label: '摆药中' },
  { value: 'PREPARED', label: '已配好' },
  { value: 'DISPENSED', label: '已发药' },
  { value: 'CANCELLED', label: '已作废' },
]

const tab = ref('queue')
const loading = ref(false)
const saving = ref(false)

const queueRows = ref<Row[]>([])
const queueTruncated = ref(false)
const queueLimit = ref(100)

const orderRows = ref<Row[]>([])
const ordersTruncated = ref(false)
const ordersLimit = ref(100)
const statusFilter = ref<string[]>(['PENDING', 'PICKING', 'PREPARED'])
const windowFilter = ref('')
const range = ref<[string, string] | null>(null)
const keyword = ref('')

const windows = ref<Row[]>([])
const includeDisabled = ref(false)
const enabledWindows = computed(() => windows.value.filter((w) => w.enabled === true))

const gates = ref<Record<string, string> | null>(null)

function num(v: unknown): number {
  return Number(v ?? 0)
}

function fmt(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—'
  const s = String(v)
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s) ? fmtDateTime(s) : s
}

function statusName(s: string): string {
  return STATUSES.find((x) => x.value === s)?.label ?? s
}

function statusTagType(s: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  return s === 'PREPARED' ? 'success' : s === 'PICKING' ? 'primary'
    : s === 'CANCELLED' ? 'danger' : s === 'DISPENSED' ? 'info' : 'warning'
}

function gateType(g: unknown): 'success' | 'warning' | 'danger' | 'info' {
  return g === 'block' ? 'danger' : g === 'warn' ? 'warning' : 'info'
}

async function loadGates() {
  gates.value = (await client.get(`${BASE}/gates`)).data.data as Record<string, string>
}

async function loadQueue() {
  const d = (await client.get(`${BASE}/queue`, { params: { limit: 100 } })).data.data as Row
  queueRows.value = (d.rows ?? []) as Row[]
  queueTruncated.value = d.truncated === true
  queueLimit.value = num(d.limit) || 100
}

async function loadOrders() {
  const d = (await client.get(`${BASE}/orders`, {
    params: {
      status: statusFilter.value.length ? statusFilter.value : undefined,
      windowCode: windowFilter.value || undefined,
      from: range.value?.[0],
      to: range.value?.[1],
      keyword: keyword.value || undefined,
      limit: 100,
    },
  })).data.data as Row
  orderRows.value = (d.rows ?? []) as Row[]
  ordersTruncated.value = d.truncated === true
  ordersLimit.value = num(d.limit) || 100
}

async function loadWindows() {
  windows.value = (await client.get(`${BASE}/windows`, {
    params: { includeDisabled: includeDisabled.value },
  })).data.data as Row[]
}

async function onTabChange() {
  await reload()
}

async function reload() {
  loading.value = true
  try {
    await loadWindows()
    if (tab.value === 'queue') await loadQueue()
    else if (tab.value === 'orders') await loadOrders()
  } finally {
    loading.value = false
  }
}

/* ---------------- 详情与流转 ---------------- */
const detailDrawer = ref(false)
const detail = ref<Row | null>(null)
const preparedWarnings = ref<string[]>([])

const activeLines = computed<Row[]>(() =>
  ((detail.value?.lines ?? []) as Row[]).filter((l) => l.released !== true))
const pickedCount = computed(() => activeLines.value.filter((l) => l.picked_at != null).length)
const isLive = computed(() =>
  ['PENDING', 'PICKING', 'PREPARED'].includes(String(detail.value?.status ?? '')))
const patientLabel = computed(() => {
  const row = orderRows.value.find((o) => Number(o.id) === Number(detail.value?.id))
  return row ? `${row.patient_name}（${row.patient_no}）` : `挂号 ${detail.value?.registration_id ?? ''}`
})

function setDetail(d: Row, keepWarnings = false) {
  detail.value = d
  if (!keepWarnings) preparedWarnings.value = (d.warnings ?? []) as string[]
}

async function openDetail(id: number) {
  preparedWarnings.value = []
  detail.value = (await client.get(`${BASE}/orders/${id}`)).data.data as Row
  detailDrawer.value = true
}

async function act(kind: 'start' | 'prepared' | 'dispensed') {
  if (!detail.value) return
  const id = Number(detail.value.id)
  saving.value = true
  try {
    const d = (await client.post(`${BASE}/orders/${id}/${kind}`)).data.data as Row
    setDetail(d)
    if (kind === 'prepared') {
      const warns = preparedWarnings.value
      if (warns.length) {
        // warn 档已放行，但放行了什么必须让人看见——不是弹一句「成功」就完事
        await ElMessageBox.alert(warns.map((w) => `· ${w}`).join('\n'),
          `已置为「已配好」，但有 ${warns.length} 条告警被 warn 档放行`,
          { type: 'warning', confirmButtonText: '我已知悉' })
      } else {
        ElMessage.success('已置为「已配好」')
      }
    } else {
      ElMessage.success('已更新')
    }
    await reload()
  } finally {
    saving.value = false
  }
}

/* ---------------- 建单 ---------------- */
const createDialog = ref(false)
const createForm = reactive({
  registrationId: 0, patientLabel: '', itemCount: 0, totalQty: 0, windowCode: '',
})

function openCreate(row: Row) {
  Object.assign(createForm, {
    registrationId: Number(row.registration_id),
    patientLabel: `${row.patient_name}（${row.patient_no}）`,
    itemCount: num(row.item_count),
    totalQty: num(row.total_qty),
    windowCode: '',
  })
  createDialog.value = true
}

async function submitCreate() {
  saving.value = true
  try {
    const d = (await client.post(`${BASE}/orders`, {
      registrationId: createForm.registrationId,
      windowCode: createForm.windowCode || undefined,
    })).data.data as Row
    ElMessage.success(`已建单 ${d.pick_no}`)
    createDialog.value = false
    setDetail(d)
    detailDrawer.value = true
    await reload()
  } finally {
    saving.value = false
  }
}

/* ---------------- 逐项确认 ---------------- */
const pickLineDialog = ref(false)
const pickLineForm = reactive({ lineId: 0, itemName: '', ordered: 1, pickedQty: 1, remark: '' })

function openPickLine(row: Row) {
  Object.assign(pickLineForm, {
    lineId: Number(row.id),
    itemName: String(row.item_name ?? ''),
    ordered: num(row.qty),
    pickedQty: num(row.qty),
    remark: '',
  })
  pickLineDialog.value = true
}

async function submitPickLine() {
  if (!detail.value) return
  saving.value = true
  try {
    const d = (await client.put(
      `${BASE}/orders/${Number(detail.value.id)}/lines/${pickLineForm.lineId}/pick`,
      { pickedQty: pickLineForm.pickedQty, remark: pickLineForm.remark || undefined },
    )).data.data as Row
    setDetail(d, true)
    pickLineDialog.value = false
    ElMessage.success('已确认')
  } finally {
    saving.value = false
  }
}

async function unpickLine(row: Row) {
  if (!detail.value) return
  const d = (await client.delete(
    `${BASE}/orders/${Number(detail.value.id)}/lines/${Number(row.id)}/pick`,
  )).data.data as Row
  setDetail(d, true)
  ElMessage.success('已撤销')
}

/* ---------------- 窗口分配 / 作废 ---------------- */
const assignDialog = ref(false)
const assignWindowCode = ref('')
const cancelDialog = ref(false)
const cancelReason = ref('')

function openAssignWindow() {
  assignWindowCode.value = String(detail.value?.window_code ?? '')
  assignDialog.value = true
}

async function submitAssign() {
  if (!detail.value) return
  saving.value = true
  try {
    const d = (await client.put(`${BASE}/orders/${Number(detail.value.id)}/window`,
      { windowCode: assignWindowCode.value || null })).data.data as Row
    setDetail(d, true)
    assignDialog.value = false
    ElMessage.success('已保存')
    await reload()
  } finally {
    saving.value = false
  }
}

function openCancel() {
  cancelReason.value = ''
  cancelDialog.value = true
}

async function submitCancel() {
  if (!detail.value) return
  if (!cancelReason.value.trim()) {
    ElMessage.warning('作废原因必填')
    return
  }
  saving.value = true
  try {
    const d = (await client.post(`${BASE}/orders/${Number(detail.value.id)}/cancel`,
      { reason: cancelReason.value })).data.data as Row
    setDetail(d, true)
    cancelDialog.value = false
    ElMessage.success('已作废')
    await reload()
  } finally {
    saving.value = false
  }
}

/* ---------------- 窗口字典维护 ---------------- */
const windowDialog = ref(false)
const windowForm = reactive({
  editing: false, code: '', name: '', enabled: true,
  sortNo: 0 as number | undefined, remark: '',
})

function openWindow(row?: Row) {
  Object.assign(windowForm, {
    editing: !!row,
    code: row ? String(row.code) : '',
    name: row ? String(row.name ?? '') : '',
    enabled: row ? row.enabled === true : true,
    sortNo: row ? num(row.sort_no) : 0,
    remark: row ? String(row.remark ?? '') : '',
  })
  windowDialog.value = true
}

async function submitWindow() {
  if (!windowForm.code.trim() || !windowForm.name.trim()) {
    ElMessage.warning('窗口编码与名称均为必填')
    return
  }
  saving.value = true
  try {
    await client.post(`${BASE}/windows`, {
      code: windowForm.code,
      name: windowForm.name,
      enabled: windowForm.enabled,
      sortNo: windowForm.sortNo,
      remark: windowForm.remark || undefined,
    })
    ElMessage.success('已保存')
    windowDialog.value = false
    await loadWindows()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await loadGates()
  await reload()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
.caveat { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.warn-text { color: var(--el-color-danger); }
</style>
