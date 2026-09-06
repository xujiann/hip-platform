<template>
  <!-- ============ 工位四：诊断（阅片 → 首次报告 → 初诊复诊双签 → 签发 → 补充报告） ============ -->
  <el-form inline size="small">
    <el-form-item label="范围">
      <el-select v-model="query.scope" style="width: 250px" @change="load">
        <el-option value="stained" label="待诊断（已有染色切片）" />
        <el-option value="all" label="全部（已核收未诊断，含无切片记录的存量标本）" />
      </el-select>
    </el-form-item>
    <el-form-item label="类别">
      <el-select v-model="query.specimenType" clearable placeholder="全部" style="width: 120px">
        <el-option v-for="t in SPECIMEN_TYPES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
    </el-form-item>
    <el-form-item label="仅加急">
      <el-switch v-model="query.urgentOnly" />
    </el-form-item>
    <el-form-item label="关键词">
      <el-input v-model="query.keyword" clearable placeholder="条码 / 病理号 / 患者 / 患者号"
                style="width: 200px" @keyup.enter="load" />
    </el-form-item>
    <el-form-item>
      <el-button type="primary" :loading="loading" @click="load">查询</el-button>
    </el-form-item>
    <el-form-item>
      <el-input v-model="openById" size="small" placeholder="按标本ID直接打开" style="width: 170px"
                @keyup.enter="openReportById" />
    </el-form-item>
  </el-form>

  <el-alert v-if="note" type="info" :closable="false" class="cav" :title="note" />
  <el-alert v-if="truncated" type="warning" show-icon :closable="false" class="cav"
            :title="`命中超过 ${limit} 条，仅显示前 ${limit} 条（不做翻页）；请收窄条件`" />

  <el-table :data="rows" v-loading="loading" size="small" border stripe max-height="440">
    <el-table-column label="病理号 / 条码" width="180">
      <template #default="{ row }">
        <span class="code">{{ fmt(row.path_no) }}</span><br>
        <span class="code muted">{{ fmt(row.barcode) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="患者" width="140">
      <template #default="{ row }">
        {{ fmt(row.patient_name) }} <span class="muted">{{ fmt(row.patient_no) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="类别" width="100">
      <template #default="{ row }">
        {{ typeName(row.specimen_type) }}
        <el-tag v-if="row.urgent === true" size="small" type="danger">急</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="临床诊断" min-width="150" show-overflow-tooltip>
      <template #default="{ row }">{{ fmt(row.clinical_diagnosis) }}</template>
    </el-table-column>
    <el-table-column label="蜡块 / 切片 / 已染色" width="140">
      <template #default="{ row }">
        {{ num(row.block_count) }} / {{ num(row.slide_count) }} / {{ num(row.stained_slide_count) }}
      </template>
    </el-table-column>
    <el-table-column label="待执行特检" width="100">
      <template #default="{ row }">
        <el-tag v-if="num(row.pending_tech_count) > 0" size="small" type="warning">
          {{ num(row.pending_tech_count) }} 项</el-tag>
        <span v-else class="muted">—</span>
      </template>
    </el-table-column>
    <el-table-column label="签收时刻" width="140">
      <template #default="{ row }">{{ fmtTime(row.received_at) }}</template>
    </el-table-column>
    <el-table-column label="距签收(小时)" width="110">
      <template #default="{ row }">{{ fmt(row.hours_since_received) }}</template>
    </el-table-column>
    <el-table-column label="操作" width="110" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" size="small" @click="openReport(Number(row.id))">
          阅片 / 报告</el-button>
      </template>
    </el-table-column>
    <template #empty>该范围内无标本</template>
  </el-table>

  <!-- ============ 报告工作面 ============ -->
  <el-drawer v-model="drawer" size="80%" :title="drawerTitle">
    <div v-loading="reportLoading">
      <el-descriptions :column="4" border size="small" class="cav">
        <el-descriptions-item label="病理号">
          <span class="code">{{ fmt(specimen.path_no) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="条码">
          <span class="code">{{ fmt(specimen.barcode) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="患者">
          {{ fmt(specimen.patient_name) }} {{ fmt(specimen.patient_no) }}
        </el-descriptions-item>
        <el-descriptions-item label="来源">{{ sourceName(specimen.source) }}</el-descriptions-item>
        <el-descriptions-item label="类别">{{ typeName(specimen.specimen_type) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          {{ statusName(specimen.status) }}
          <el-tag v-if="specimen.rejected_at" size="small" type="danger">已拒收</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="取材部位">{{ fmt(specimen.sampling_site) }}</el-descriptions-item>
        <el-descriptions-item label="临床诊断">{{ fmt(specimen.clinical_diagnosis) }}</el-descriptions-item>
      </el-descriptions>

      <!-- ---- 双签状态：先给判据与提示，不等提交被拒 ---- -->
      <el-alert :type="signAlertType" show-icon :closable="false" class="cav" :title="signAlertTitle">
        <div>
          双签 gate（emr.gate.pathology.doublesign）= <b>{{ fmt(doubleSignGate) }}</b>：
          off 不校验 / warn 未双签也放行签发（默认）/ block 未双签不得签发。
          复诊人不得与初诊人为同一人这一条<b>与 gate 无关、永远生效</b>——一个人签两次不叫双签。
        </div>
      </el-alert>

      <div class="bar">
        <el-tooltip :disabled="!diagnoseBlockedReason" :content="diagnoseBlockedReason">
          <span>
            <el-button type="primary" size="small" :disabled="!!diagnoseBlockedReason"
                       @click="openDiagnose">书写首次报告</el-button>
          </span>
        </el-tooltip>
        <el-tooltip :disabled="!firstSignBlockedReason" :content="firstSignBlockedReason">
          <span>
            <el-button type="primary" size="small" :disabled="!!firstSignBlockedReason"
                       :loading="saving" @click="firstSign">初诊签名</el-button>
          </span>
        </el-tooltip>
        <el-tooltip :disabled="!secondSignBlockedReason" :content="secondSignBlockedReason">
          <span>
            <el-button type="primary" size="small" :disabled="!!secondSignBlockedReason"
                       :loading="saving" @click="secondSign">复诊签名</el-button>
          </span>
        </el-tooltip>
        <el-tooltip :disabled="!issueBlockedReason" :content="issueBlockedReason">
          <span>
            <el-button type="success" size="small" :disabled="!!issueBlockedReason"
                       :loading="saving" @click="issue">正式签发</el-button>
          </span>
        </el-tooltip>
        <el-button size="small" :disabled="!primary.diagnosis" @click="supplementDialog = true">
          出补充报告</el-button>
        <el-button size="small" @click="techDialog = true">下达特检技术医嘱</el-button>
        <el-button link type="primary" size="small" @click="loadReport">刷新</el-button>
      </div>

      <el-tabs v-model="reportTab">
        <!-- ---------------- 报告 ---------------- -->
        <el-tab-pane name="report" label="报告">
          <el-alert type="warning" show-icon :closable="false" class="cav"
                    title="补充报告是「追加」不是「修改」：下方原报告存于 path_specimen，本页出补充报告一条 update 都不会对它执行。覆盖原报告会让「当时医生看到的是什么」永久不可考。" />

          <el-card shadow="never" class="primary-card">
            <template #header>
              <el-tag type="info" size="small">原报告 · 首次报告（seqNo 0）</el-tag>
              <span class="lock">🔒 补充报告不会改动以下任何一个字</span>
              <span style="float: right" class="muted">
                写完诊断：{{ fmtTime(primary.diagnosedAt) }}
                正式签发：{{ fmtTime(primary.reportIssuedAt) }}
              </span>
            </template>
            <template v-if="hasPrimary">
              <div class="sec"><b>大体所见</b><pre>{{ fmt(primary.grossFinding) }}</pre></div>
              <div class="sec"><b>镜下所见</b><pre>{{ fmt(primary.microFinding) }}</pre></div>
              <div class="sec"><b>病理诊断</b><pre>{{ fmt(primary.diagnosis) }}</pre></div>
              <el-descriptions :column="3" border size="small">
                <el-descriptions-item label="诊断医师">
                  {{ fmt(primary.pathologistName) }}</el-descriptions-item>
                <el-descriptions-item label="初诊签名">
                  {{ fmt(primary.firstSignerName) }}　{{ fmtTime(primary.firstSignedAt) }}
                </el-descriptions-item>
                <el-descriptions-item label="复诊签名">
                  {{ fmt(primary.secondSignerName) }}　{{ fmtTime(primary.secondSignedAt) }}
                </el-descriptions-item>
              </el-descriptions>
            </template>
            <el-empty v-else description="尚未书写首次病理诊断（未写诊断不能签名、不能签发、不能出补充报告）"
                      :image-size="60" />
          </el-card>

          <h4>补充报告（{{ supplements.length }} 份，按出具时间序追加）</h4>
          <el-timeline v-if="supplements.length">
            <el-timeline-item v-for="s in supplements" :key="Number(s.id)"
                              :timestamp="fmtTime(s.signed_at)" placement="top">
              <el-card shadow="never">
                <template #header>
                  <el-tag size="small" type="warning">补充 #{{ fmt(s.seq_no) }}（追加文书）</el-tag>
                  <span class="muted" style="margin-left: 8px">签名人：{{ fmt(s.signer_name) }}</span>
                </template>
                <div v-if="s.reason" class="muted">补充原因：{{ s.reason }}</div>
                <pre>{{ fmt(s.content) }}</pre>
              </el-card>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无补充报告" :image-size="50" />
          <el-alert v-if="supTruncated" type="warning" :closable="false" class="cav"
                    title="补充报告条数超过本次上限，仅显示前若干份" />
        </el-tab-pane>

        <!-- ---------------- 流转 ---------------- -->
        <el-tab-pane name="process" :label="`流转节点（${processRows.length}）`">
          <el-alert type="info" :closable="false" class="cav"
                    title="没打点的环节根本不出现在列表里，而不是显示为 0——行的缺席本身就是「这个环节没在系统里打点」的信号。" />
          <el-table :data="processRows" size="small" border max-height="420">
            <el-table-column label="环节" width="110">
              <template #default="{ row }">{{ nodeName(row.node) }}</template>
            </el-table-column>
            <el-table-column label="时刻" width="150">
              <template #default="{ row }">{{ fmtTime(row.occurred_at) }}</template>
            </el-table-column>
            <el-table-column label="操作人" width="110">
              <template #default="{ row }">{{ fmt(row.operator_name) }}</template>
            </el-table-column>
            <el-table-column label="备注" show-overflow-tooltip>
              <template #default="{ row }">{{ fmt(row.remark) }}</template>
            </el-table-column>
            <template #empty>该标本尚无流转打点</template>
          </el-table>
        </el-tab-pane>

        <!-- ---------------- 特检技术医嘱 ---------------- -->
        <el-tab-pane name="tech" :label="`特检技术医嘱（${techRows.length}）`">
          <el-alert type="info" :closable="false" class="cav"
                    title="取消特检医嘱不留原因：path_tech_order 没有 cancel_reason 列，本版不把取消原因塞进 reason（那会覆盖下达时的原因，让「当初为什么要做这个免疫组化」永久丢失）。" />
          <el-table :data="techRows" size="small" border max-height="380">
            <el-table-column label="类型" width="110">
              <template #default="{ row }">{{ techName(row.tech_type) }}</template>
            </el-table-column>
            <el-table-column label="项目" width="130">
              <template #default="{ row }">{{ fmt(row.tech_item) }}</template>
            </el-table-column>
            <el-table-column label="蜡块" width="150">
              <template #default="{ row }">{{ fmt(row.block_code) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="techStatusTag(String(row.status))">
                  {{ techStatusName(String(row.status)) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="开单" width="200">
              <template #default="{ row }">
                {{ fmt(row.ordered_by_name) }}　{{ fmtTime(row.ordered_at) }}
              </template>
            </el-table-column>
            <el-table-column label="完成" width="200">
              <template #default="{ row }">
                {{ fmt(row.done_by_name) }}　{{ fmtTime(row.done_at) }}
              </template>
            </el-table-column>
            <el-table-column label="原因" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ fmt(row.reason) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" :disabled="row.status !== 'ORDERED'"
                           @click="techDone(row)">完成</el-button>
                <el-button link type="danger" size="small" :disabled="row.status !== 'ORDERED'"
                           @click="techCancel(row)">取消</el-button>
              </template>
            </el-table-column>
            <template #empty>该标本无特检技术医嘱</template>
          </el-table>
        </el-tab-pane>

        <!-- ---------------- 既往病理 ---------------- -->
        <el-tab-pane name="prior" label="既往病理（对比诊断）">
          <el-alert v-if="priorResolved === false" type="error" show-icon :closable="false" class="cav"
                    :title="priorNote || '无法从该标本解析出患者，本次未能检索既往病理——这不等于该患者没有既往病理'" />
          <el-table v-else :data="priorRows" v-loading="priorLoading" size="small" border max-height="420">
            <el-table-column label="病理号" width="140">
              <template #default="{ row }"><span class="code">{{ fmt(row.path_no) }}</span></template>
            </el-table-column>
            <el-table-column label="类别" width="90">
              <template #default="{ row }">{{ typeName(row.specimen_type) }}</template>
            </el-table-column>
            <el-table-column label="取材部位" width="120">
              <template #default="{ row }">{{ fmt(row.sampling_site) }}</template>
            </el-table-column>
            <el-table-column label="病理诊断" min-width="240" show-overflow-tooltip>
              <template #default="{ row }">{{ fmt(row.diagnosis) }}</template>
            </el-table-column>
            <el-table-column label="补充报告" width="90">
              <template #default="{ row }">
                <el-tag v-if="num(row.supplement_count) > 0" size="small" type="warning">
                  {{ num(row.supplement_count) }} 份</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="签发时刻" width="150">
              <template #default="{ row }">{{ fmtTime(row.report_issued_at) }}</template>
            </el-table-column>
            <template #empty>该患者无其它已出结果的病理标本</template>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-drawer>

  <!-- ============ 首次报告书写 ============ -->
  <el-dialog v-model="diagnoseDialog" title="书写首次病理报告" width="680px" top="6vh">
    <el-alert type="warning" show-icon :closable="false" class="cav"
              title="本操作走既有 PUT /api/pathology/specimens/{barcode}/diagnose，会以本次内容整体写入大体所见 / 镜下所见 / 诊断三列，因此取材时已写入的大体所见已预填在下方——请在此基础上补写，清空它就等于把取材记录抹掉。保存后标本状态变为「已诊断」，本版无修订入口，更正请出补充报告。" />
    <el-form label-width="90px" size="small">
      <el-form-item label="大体所见">
        <el-input v-model="diagnoseForm.grossFinding" type="textarea" :rows="3" />
      </el-form-item>
      <el-form-item label="镜下所见">
        <el-input v-model="diagnoseForm.microFinding" type="textarea" :rows="4" />
      </el-form-item>
      <el-form-item label="病理诊断" required>
        <el-input v-model="diagnoseForm.diagnosis" type="textarea" :rows="3"
                  placeholder="不能为空（4552）" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="diagnoseDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitDiagnose">保存</el-button>
    </template>
  </el-dialog>

  <!-- ============ 补充报告 ============ -->
  <el-dialog v-model="supplementDialog" title="出具补充报告（追加，不修改原报告）" width="680px">
    <el-alert type="warning" show-icon :closable="false" class="cav"
              title="补充报告是新增一份文书，按序号留全历史：原报告的大体所见 / 镜下所见 / 诊断三列一个字都不会被改。免疫组化回报后的补充诊断、会诊意见都走这里。" />
    <el-form label-width="90px" size="small">
      <el-form-item label="补充内容" required>
        <el-input v-model="supplementForm.content" type="textarea" :rows="5"
                  placeholder="如：免疫组化结果 CK7(+)、TTF-1(+)…，结合形态学，符合……" />
      </el-form-item>
      <el-form-item label="补充原因">
        <el-input v-model="supplementForm.reason" maxlength="255" show-word-limit
                  placeholder="如：免疫组化回报 / 外院会诊意见" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="supplementDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitSupplement">
        追加补充报告</el-button>
    </template>
  </el-dialog>

  <!-- ============ 特检技术医嘱 ============ -->
  <el-dialog v-model="techDialog" title="下达特检技术医嘱" width="560px">
    <el-form label-width="90px" size="small">
      <el-form-item label="技术类型" required>
        <el-select v-model="techForm.techType" style="width: 220px">
          <el-option v-for="t in techTypes" :key="String(t.value)" :value="String(t.value)"
                     :label="String(t.label)" />
        </el-select>
        <span v-if="techItemRequired" class="muted" style="margin-left: 8px">该类型必须指明具体项目</span>
      </el-form-item>
      <el-form-item label="具体项目" :required="techItemRequired">
        <el-input v-model="techForm.techItem" maxlength="64" show-word-limit
                  placeholder="如 CK7、Ki-67、PAS；深切/重切/补取材可留空" />
      </el-form-item>
      <el-form-item label="蜡块">
        <el-select v-model="techForm.blockId" clearable placeholder="可空（补取材没有对应蜡块）"
                   style="width: 100%">
          <el-option v-for="b in specimenBlocks" :key="Number(b.id)" :value="Number(b.id)"
                     :label="`${b.block_code}　${b.tissue_desc ?? ''}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="原因">
        <el-input v-model="techForm.reason" type="textarea" :rows="2" maxlength="255" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="techDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitTech">下达</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 工位四：诊断与报告。对接 {@code PathologyReportController}（/api/pathology/report）
 * 与既有 {@code PUT /api/pathology/specimens/{barcode}/diagnose}（首次报告的写入口）。
 *
 * <p><b>双签的两条 UI 纪律</b>：
 * <ul>
 *   <li>复诊人不得与初诊人为同一人（后端 5263 会拒）——本页在按钮上<b>提前禁用并说明原因</b>，
 *       而不是让人点下去再吃一个红字。判据用 /auth/me 的当前用户 id 与报告全景里的
 *       firstSignerId 比对，与后端同一条判据。</li>
 *   <li>补充报告是<b>追加</b>不是修改：原报告单独一张卡片、带锁标记，
 *       补充报告在时间线里逐份追加，一眼能看出原报告一字未动。</li>
 * </ul>
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../../api/client'
import { useAuthStore } from '../../../stores/auth'
import {
  SPECIMEN_TYPES, fmt, fmtTime, nodeName, num, sourceName, statusName, typeName, type Row,
} from './format'

const emit = defineEmits<{ (e: 'changed'): void }>()

const auth = useAuthStore()
const meId = computed<number | null>(() => (auth.user ? Number(auth.user.id) : null))

/* ---------------- 阅片工作列表 ---------------- */
const rows = ref<Row[]>([])
const loading = ref(false)
const truncated = ref(false)
const limit = ref(100)
const note = ref('')
const openById = ref('')
const query = reactive({ scope: 'stained', specimenType: '', urgentOnly: false, keyword: '' })

async function load() {
  loading.value = true
  try {
    const d = (await client.get('/pathology/report/worklist', {
      params: {
        scope: query.scope,
        specimenType: query.specimenType || undefined,
        urgentOnly: query.urgentOnly || undefined,
        keyword: query.keyword || undefined,
      },
    })).data.data as Row
    rows.value = (d.items ?? []) as Row[]
    truncated.value = d.truncated === true
    limit.value = num(d.limit) || 100
    note.value = String(d.note ?? '')
  } finally {
    loading.value = false
  }
}

/* ---------------- 报告全景 ---------------- */
const drawer = ref(false)
const reportTab = ref('report')
const reportLoading = ref(false)
const saving = ref(false)
const specimenId = ref(0)
const specimen = ref<Row>({})
const primary = ref<Row>({})
const hasPrimary = ref(false)
const supplements = ref<Row[]>([])
const supTruncated = ref(false)
const processRows = ref<Row[]>([])
const doubleSignGate = ref('')

const drawerTitle = computed(
  () => `病理报告 — ${fmt(specimen.value.path_no)}　${fmt(specimen.value.patient_name)}`,
)

function openReport(id: number) {
  specimenId.value = id
  reportTab.value = 'report'
  drawer.value = true
  void loadReport()
  void loadTech()
  void loadPrior()
  void loadSpecimenBlocks()
}

function openReportById() {
  const id = Number(openById.value)
  if (!id) {
    ElMessage.warning('请输入标本 ID')
    return
  }
  openReport(id)
}

async function loadReport() {
  reportLoading.value = true
  try {
    const d = (await client.get(`/pathology/report/${specimenId.value}/reports`)).data.data as Row
    specimen.value = (d.specimen ?? {}) as Row
    primary.value = (d.primary ?? {}) as Row
    hasPrimary.value = d.hasPrimary === true
    supplements.value = (d.supplements ?? []) as Row[]
    supTruncated.value = d.truncated === true
    processRows.value = (d.process ?? []) as Row[]
    doubleSignGate.value = String(d.doubleSignGate ?? '')
  } finally {
    reportLoading.value = false
  }
}

/* ---------------- 双签前置判定（与后端同一条判据，提前提示而不是等提交被拒） ---------------- */
const firstSignerId = computed<number | null>(
  () => (primary.value.firstSignerId == null ? null : Number(primary.value.firstSignerId)),
)

/**
 * 首次报告只能写在「已核收」状态的标本上——既有 {@code diagnose} 端点的 where 条件是
 * {@code status = 'RECEIVED' and rejected_at is null}，写完状态即变「已诊断」。
 * <b>本版没有修订首次报告的端点</b>，所以按钮在已诊断的标本上直接禁用并说明「更正走补充报告」，
 * 而不是摆一个点下去必然吃 4553 的「修订」入口。
 */
const diagnoseBlockedReason = computed(() => {
  if (!specimenId.value) return '未选择标本'
  if (specimen.value.rejected_at) return '该标本已拒收，不能出报告'
  if (specimen.value.report_issued_at) return '报告已签发，更正请出补充报告'
  if (specimen.value.status === 'DIAGNOSED' || hasPrimary.value) {
    return '该标本已写过首次诊断：本版没有修订首次报告的端点（覆盖原报告会让「当时医生看到的是什么」不可考），更正请出补充报告'
  }
  if (specimen.value.status !== 'RECEIVED') {
    return `写首次报告要求标本状态为「已核收」，当前为「${statusName(specimen.value.status)}」`
  }
  return ''
})

const firstSignBlockedReason = computed(() => {
  if (!specimenId.value) return '未选择标本'
  if (specimen.value.rejected_at) return '该标本已拒收，不能签名'
  if (specimen.value.report_issued_at) return '报告已签发，不能再补签名（如需更正请出补充报告）'
  if (!hasPrimary.value) return '尚未书写病理诊断，不能初诊签名'
  if (primary.value.firstSignedAt) return '已完成初诊签名，不可重复签名'
  return ''
})

const secondSignBlockedReason = computed(() => {
  if (!specimenId.value) return '未选择标本'
  if (specimen.value.rejected_at) return '该标本已拒收，不能签名'
  if (specimen.value.report_issued_at) return '报告已签发，不能再补签名（如需更正请出补充报告）'
  if (!primary.value.firstSignedAt) return '尚未完成初诊签名，不能复诊签名'
  if (primary.value.secondSignedAt) return '已完成复诊签名，不可重复签名'
  if (firstSignerId.value == null) return '初诊签名人未知，无从核验复诊人与初诊人是否同一，不能复诊签名'
  if (meId.value != null && firstSignerId.value === meId.value) {
    return '复诊签名人不能与初诊签名人为同一人（一个人签两次不构成双签）——请由上级医师登录复签'
  }
  return ''
})

const missingSigns = computed<string[]>(() => {
  const miss: string[] = []
  if (!primary.value.firstSignedAt) miss.push('初诊签名')
  if (!primary.value.secondSignedAt) miss.push('复诊签名')
  return miss
})

const issueBlockedReason = computed(() => {
  if (!specimenId.value) return '未选择标本'
  if (specimen.value.rejected_at) return '该标本已拒收，不能签发报告'
  if (specimen.value.report_issued_at) return `报告已于 ${fmtTime(specimen.value.report_issued_at)} 签发，不可重复签发`
  if (!hasPrimary.value) return '尚未书写病理诊断，不能签发报告'
  if (missingSigns.value.length && doubleSignGate.value === 'block') {
    return `gate=block：未完成双签不得签发（缺 ${missingSigns.value.join('、')}）`
  }
  return ''
})

const signAlertType = computed<'success' | 'warning' | 'error'>(() => {
  if (specimen.value.rejected_at) return 'error'
  return missingSigns.value.length === 0 ? 'success' : 'warning'
})

const signAlertTitle = computed(() => {
  if (!hasPrimary.value) return '尚未书写病理诊断：初诊签名、正式签发、补充报告三条路都走不通'
  if (missingSigns.value.length === 0) {
    return `双签完整：初诊 ${fmt(primary.value.firstSignerName)}，复诊 ${fmt(primary.value.secondSignerName)}`
  }
  return `双签未完成：缺 ${missingSigns.value.join('、')}`
})

async function firstSign() {
  saving.value = true
  try {
    await client.put(`/pathology/report/${specimenId.value}/first-sign`, null)
    ElMessage.success('初诊签名完成')
    await loadReport()
    emit('changed')
  } finally {
    saving.value = false
  }
}

async function secondSign() {
  saving.value = true
  try {
    await client.put(`/pathology/report/${specimenId.value}/second-sign`, null)
    ElMessage.success('复诊签名完成')
    await loadReport()
    emit('changed')
  } finally {
    saving.value = false
  }
}

async function issue() {
  if (missingSigns.value.length) {
    const ok = await ElMessageBox.confirm(
      `本次签发缺 ${missingSigns.value.join('、')}（gate=${doubleSignGate.value}）。`
      + '放行不等于没发生过：缺签情况会写进 ISSUE 流转节点，事后可追。确认签发？',
      '未完成双签即签发', { type: 'warning' },
    ).catch(() => null)
    if (!ok) return
  }
  saving.value = true
  try {
    const d = (await client.put(`/pathology/report/${specimenId.value}/issue`, null)).data.data as Row
    const warnings = (d.warnings ?? []) as string[]
    ElMessage.success(`报告已签发（${fmtTime(d.reportIssuedAt)}）`)
    if (warnings.length) ElMessage.warning(warnings.join('；'))
    await loadReport()
    await load()
    emit('changed')
  } finally {
    saving.value = false
  }
}

/* ---------------- 首次报告书写（走既有 diagnose 端点） ---------------- */
const diagnoseDialog = ref(false)
const diagnoseForm = reactive({ grossFinding: '', microFinding: '', diagnosis: '' })

function openDiagnose() {
  // 预填现有三列：既有端点是整体覆盖写，不预填就会把取材时写入的大体所见清空
  diagnoseForm.grossFinding = String(primary.value.grossFinding ?? '')
  diagnoseForm.microFinding = String(primary.value.microFinding ?? '')
  diagnoseForm.diagnosis = String(primary.value.diagnosis ?? '')
  diagnoseDialog.value = true
}

async function submitDiagnose() {
  if (!diagnoseForm.diagnosis.trim()) {
    ElMessage.warning('病理诊断不能为空')
    return
  }
  saving.value = true
  try {
    await client.put(`/pathology/specimens/${String(specimen.value.barcode)}/diagnose`, {
      grossFinding: diagnoseForm.grossFinding || null,
      microFinding: diagnoseForm.microFinding || null,
      diagnosis: diagnoseForm.diagnosis,
    })
    ElMessage.success('已保存首次报告')
    diagnoseDialog.value = false
    await loadReport()
    await load()
    emit('changed')
  } finally {
    saving.value = false
  }
}

/* ---------------- 补充报告 ---------------- */
const supplementDialog = ref(false)
const supplementForm = reactive({ content: '', reason: '' })

async function submitSupplement() {
  if (!supplementForm.content.trim()) {
    ElMessage.warning('补充报告内容不能为空')
    return
  }
  saving.value = true
  try {
    const d = (await client.post(`/pathology/report/${specimenId.value}/supplement`, {
      content: supplementForm.content,
      reason: supplementForm.reason || undefined,
    })).data.data as Row
    ElMessage.success(`已追加补充报告 #${fmt(d.seqNo)}（原报告未被修改）`)
    supplementDialog.value = false
    supplementForm.content = ''
    supplementForm.reason = ''
    await loadReport()
    emit('changed')
  } finally {
    saving.value = false
  }
}

/* ---------------- 特检技术医嘱 ---------------- */
const techDialog = ref(false)
const techRows = ref<Row[]>([])
const techTypes = ref<Row[]>([])
const specimenBlocks = ref<Row[]>([])
const techForm = reactive({
  techType: '', techItem: '', blockId: undefined as number | undefined, reason: '',
})

const techItemRequired = computed(
  () => techTypes.value.find((t) => String(t.value) === techForm.techType)?.itemRequired === true,
)

function techName(v: unknown): string {
  const hit = techTypes.value.find((t) => String(t.value) === String(v))
  return hit ? String(hit.label) : String(v ?? '—')
}

function techStatusName(v: string) {
  return ({ ORDERED: '待执行', DONE: '已完成', CANCELLED: '已取消' } as Record<string, string>)[v] ?? v
}

function techStatusTag(v: string): 'warning' | 'success' | 'info' {
  return v === 'ORDERED' ? 'warning' : v === 'DONE' ? 'success' : 'info'
}

async function loadTechDict() {
  const d = (await client.get('/pathology/report/tech-orders/dict')).data.data as Row
  techTypes.value = (d.techTypes ?? []) as Row[]
  if (!techForm.techType && techTypes.value.length) techForm.techType = String(techTypes.value[0].value)
}

async function loadTech() {
  const d = (await client.get('/pathology/report/tech-orders', {
    params: { specimenId: specimenId.value },
  })).data.data as Row
  techRows.value = (d.items ?? []) as Row[]
}

async function loadSpecimenBlocks() {
  const d = (await client.get('/pathology/process/blocks', {
    params: { specimenId: specimenId.value },
  })).data.data as Row
  specimenBlocks.value = (d.items ?? []) as Row[]
}

async function submitTech() {
  if (!techForm.techType) {
    ElMessage.warning('请选择技术类型')
    return
  }
  if (techItemRequired.value && !techForm.techItem.trim()) {
    ElMessage.warning('该技术类型必须指明具体项目（如 CK7、Ki-67、PAS），否则技师无从执行')
    return
  }
  saving.value = true
  try {
    await client.post('/pathology/report/tech-orders', {
      specimenId: specimenId.value,
      blockId: techForm.blockId,
      techType: techForm.techType,
      techItem: techForm.techItem || undefined,
      reason: techForm.reason || undefined,
    })
    ElMessage.success('已下达特检技术医嘱')
    techDialog.value = false
    techForm.techItem = ''
    techForm.reason = ''
    techForm.blockId = undefined
    await loadTech()
    emit('changed')
  } finally {
    saving.value = false
  }
}

async function techDone(row: Row) {
  await client.put(`/pathology/report/tech-orders/${Number(row.id)}/done`, null)
  ElMessage.success('已标记完成')
  await loadTech()
}

async function techCancel(row: Row) {
  const ok = await ElMessageBox.confirm(
    '取消原因无处存放（path_tech_order 无 cancel_reason 列），本版不留痕也不覆盖下达原因。确认取消该医嘱？',
    '取消特检技术医嘱', { type: 'warning' },
  ).catch(() => null)
  if (!ok) return
  await client.put(`/pathology/report/tech-orders/${Number(row.id)}/cancel`, null)
  ElMessage.success('已取消')
  await loadTech()
}

/* ---------------- 既往病理 ---------------- */
const priorRows = ref<Row[]>([])
const priorLoading = ref(false)
const priorResolved = ref<boolean | null>(null)
const priorNote = ref('')

async function loadPrior() {
  priorLoading.value = true
  try {
    const d = (await client.get(`/pathology/report/${specimenId.value}/prior`)).data.data as Row
    priorRows.value = (d.items ?? []) as Row[]
    priorResolved.value = d.patientResolved === true
    priorNote.value = String(d.note ?? '')
  } finally {
    priorLoading.value = false
  }
}

defineExpose({ reload: load })

void load()
void loadTechDict()
</script>

<style scoped>
.cav { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.code { font-family: Consolas, Monaco, monospace; }
.bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.primary-card { border: 1px solid var(--el-color-info-light-5); }
.lock { margin-left: 8px; color: var(--el-color-info); font-size: 12px; }
.sec { margin-bottom: 10px; }
.sec pre {
  margin: 4px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
}
h4 { margin: 14px 0 8px; }
</style>
