<template>
  <el-card>
    <template #header>
      <span style="font-weight: 600">患者过敏档 · 原文与结构化并排</span>
      <el-select v-model="patientId" filterable remote clearable :remote-method="searchPatients"
                 :loading="searching" placeholder="按姓名/患者号搜索患者"
                 style="width: 300px; margin-left: 12px" @change="load">
        <el-option v-for="p in patients" :key="Number(p.id)" :value="Number(p.id)"
                   :label="`${p.name}（${p.patientNo}）`" />
      </el-select>
      <el-input-number v-model="manualId" :min="1" :controls="false" size="small"
                       placeholder="或直接输入患者ID" style="width: 150px; margin-left: 8px" />
      <el-button size="small" style="margin-left: 6px" @click="useManualId">按ID打开</el-button>
      <el-button type="primary" size="small" :loading="loading" style="margin-left: 8px"
                 :disabled="!patientId" @click="load">刷新</el-button>
    </template>

    <el-alert v-if="searchDenied" type="info" show-icon :closable="false" class="caveat"
              :title="`患者检索接口对当前角色不开放（${searchDenied}）——请直接填患者 ID，或从「过敏史人工核对工作台」点「过敏档」进来。`" />

    <cdss-gate-bar />

    <el-empty v-if="!body" description="请先选择患者" />

    <template v-else>
      <!-- ============ 覆盖度：这一段决定「未发现过敏禁忌」这句话成不成立 ============ -->
      <el-alert v-if="coverage" :type="coverage.trustworthy ? 'success' : 'error'" show-icon :closable="false"
                class="caveat"
                :title="coverage.trustworthy
                  ? '本患者过敏审查覆盖完整：原文已人工核对，且全部生效过敏原都有院内药品映射。'
                  : '本患者过敏审查覆盖不全——此时「未发现过敏禁忌」这句话不成立，请人工阅读原文后判断。'">
        <ul class="gaps">
          <li v-if="coverage.unreviewedText">
            有自由文本过敏史<b>尚未人工核对</b>：本次审查未覆盖其中任何内容
            <el-button link type="primary" size="small" @click="goReview">去核对工作台</el-button>
          </li>
          <li v-else-if="coverage.latestTextResolution === 'UNCLEAR'">
            自由文本过敏史经人工核对结论为<b>「无法判定」</b>——不等于无过敏，尚未转成结构化过敏原
          </li>
          <li v-if="unmapped.length">
            有 {{ unmapped.length }} 个已确认过敏原<b>未映射到任何院内药品</b>（{{ unmapped.map((u) => u.name).join('、') }}），
            它们<b>永远不会</b>在开单审查中命中，请药剂科补全映射
          </li>
          <li v-if="coverage.trustworthy">无已知覆盖缺口。</li>
        </ul>
      </el-alert>

      <el-descriptions :column="4" border size="small" class="caveat">
        <el-descriptions-item label="患者">{{ body.patientName }}（ID {{ body.patientId }}）</el-descriptions-item>
        <el-descriptions-item label="生效结构化过敏原">{{ num(coverage?.structuredActiveCount) }}</el-descriptions-item>
        <el-descriptions-item label="原文是否非空">{{ coverage?.hasFreeText ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="最近核对结论">
          {{ coverage?.latestTextResolution ? enumText(RESOLUTION_TEXT, coverage.latestTextResolution) : '—' }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="section-title">过敏史原文（empi_patient.allergy_history，本模块只读不写）</div>
      <div class="raw-box">{{ body.allergyHistoryText || '（空）' }}</div>
      <div class="muted" style="margin-bottom: 12px">
        原文与结构化记录<b>并排</b>显示，刻意不做「有结构化就藏起原文」的收敛：提取得对不对、有没有漏，只有对着原文才看得出来。
      </div>

      <!-- ============ 结构化过敏记录 ============ -->
      <div class="section-title">
        结构化过敏记录
        <el-button type="primary" size="small" style="margin-left: 10px" @click="openAdd">登记一条</el-button>
      </div>
      <el-table :data="allergies" size="small" border stripe max-height="320">
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'danger' : 'info'">
              {{ row.status === 'ACTIVE' ? '生效' : '已撤销' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="allergen_name" label="过敏原" width="150" />
        <el-table-column label="严重度" width="80">
          <template #default="{ row }">{{ enumText(SEVERITY_TEXT, row.severity) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="150">
          <template #default="{ row }">{{ enumText(SOURCE_TEXT, row.source) }}</template>
        </el-table-column>
        <el-table-column prop="manifestation" label="表现" min-width="120" show-overflow-tooltip />
        <el-table-column label="映射药品数" width="105">
          <template #default="{ row }">
            <span :class="{ zero: Number(row.mapped_drug_count) === 0 }">{{ num(row.mapped_drug_count) }}</span>
            <el-tooltip v-if="Number(row.mapped_drug_count) === 0"
                        content="该过敏原未映射任何院内药品，开单审查永远不会命中它">
              <el-tag type="warning" size="small" style="margin-left: 4px">拦不住</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="confirmed_by_name" label="确认人" width="90" />
        <el-table-column label="确认时刻" width="150">
          <template #default="{ row }">{{ fmt(row.confirmed_at) }}</template>
        </el-table-column>
        <el-table-column label="原文快照" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.source_text ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="revoke_reason" label="撤销原因" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'ACTIVE'" link type="danger" size="small"
                       @click="revoke(row)">撤销</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无结构化过敏记录</template>
      </el-table>
      <div class="muted">没有物理删除：删掉一条过敏记录直接放开了一次拦截，这个动作不该是无痕的。撤销须填原因。</div>

      <!-- ============ 人工核对历史 ============ -->
      <div class="section-title">自由文本核对历史（含 NO_ALLERGY 与 UNCLEAR，两者分开留痕）</div>
      <el-table :data="textReviews" size="small" border stripe max-height="240">
        <el-table-column label="结论" width="220">
          <template #default="{ row }">
            <el-tag size="small" :type="row.resolution === 'UNCLEAR' ? 'warning' : 'info'">
              {{ enumText(RESOLUTION_TEXT, row.resolution) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当时原文快照" min-width="260">
          <template #default="{ row }"><span class="raw">{{ row.source_text }}</span></template>
        </el-table-column>
        <el-table-column prop="created_count" label="登记条数" width="90" />
        <el-table-column prop="reviewed_by_name" label="核对人" width="90" />
        <el-table-column label="核对时刻" width="150">
          <template #default="{ row }">{{ fmt(row.reviewed_at) }}</template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="150" show-overflow-tooltip />
        <template #empty>该患者尚无人工核对记录</template>
      </el-table>

      <!-- ============ 开单审查预演 ============ -->
      <div class="section-title">开单过敏审查预演（只读预演：不留痕、不拦截，供维护映射的人自查）</div>
      <el-select v-model="previewDrugIds" multiple filterable remote :remote-method="searchDrugs"
                 :loading="drugLoading" placeholder="选择本次拟开药品（可多选）" style="width: 460px">
        <el-option v-for="d in drugs" :key="Number(d.id)" :value="Number(d.id)"
                   :label="`${d.name}${d.spec ? ' ' + d.spec : ''}`" />
      </el-select>
      <el-button type="primary" size="small" style="margin-left: 8px"
                 :loading="previewing" :disabled="previewDrugIds.length === 0" @click="doPreview">预演</el-button>
      <template v-if="preview">
        <el-alert :type="preview.blocked ? 'error' : (hits.length ? 'warning' : 'info')" show-icon :closable="false"
                  class="caveat"
                  :title="preview.blocked
                    ? `按当前 gate=${preview.gate}，本组药品会被【拦截】`
                    : `按当前 gate=${preview.gate}，本组药品不会被拦截（命中 ${hits.length} 条提示）`" />
        <el-table v-if="hits.length" :data="hits" size="small" border stripe>
          <el-table-column label="命中类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.hitType === 'DIRECT' ? 'danger' : 'warning'">
                {{ row.hitType === 'DIRECT' ? '直接命中' : '交叉风险' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="drugName" label="药品" width="140" />
          <el-table-column prop="allergenName" label="过敏原" width="120" />
          <el-table-column label="是否可拦" width="90">
            <template #default="{ row }">{{ row.blocking ? '可拦' : '恒不拦' }}</template>
          </el-table-column>
          <el-table-column prop="message" label="提示原文" min-width="320" />
        </el-table>
        <!-- warnings 里既有命中提示也有覆盖缺口提示：不显示等于这一版白做 -->
        <el-alert v-for="(w, i) in (preview.warnings as string[])" :key="i" type="warning" :closable="false"
                  class="caveat" :title="w" />
        <el-empty v-if="hits.length === 0" :image-size="50"
                  description="未命中任何显式映射——请连同上方覆盖度一起看：目录空/映射空时，这里本就什么也拦不住" />
      </template>
      <el-alert v-if="body.note" type="info" :closable="false" class="caveat" :title="body.note" />
    </template>
  </el-card>

  <!-- ============ 登记结构化过敏记录 ============ -->
  <el-dialog v-model="addDialog" title="登记结构化过敏记录" width="560px">
    <el-alert type="warning" :closable="false" class="caveat"
              title="这条记录会拦处方：确认人取当前登录人，不可匿名。过敏原目录由药剂科维护，此处只做选择。" />
    <el-form label-width="90px" size="small">
      <el-form-item label="过敏原">
        <el-select v-model="addForm.allergenId" filterable clearable placeholder="搜索并选择" style="width: 100%">
          <el-option v-for="a in allergens" :key="Number(a.id)" :value="Number(a.id)"
                     :label="`${a.code}　${a.name}`" :disabled="a.enabled !== true">
            <span>{{ a.code }}　{{ a.name }}</span>
            <span class="muted" style="margin-left: 8px">
              映射药品 {{ num(a.mapped_drug_count) }}<template v-if="a.enabled !== true">　已停用</template>
            </span>
          </el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="严重度">
        <el-select v-model="addForm.severity" style="width: 100%">
          <el-option v-for="s in SEVERITIES" :key="s" :value="s" :label="enumText(SEVERITY_TEXT, s)" />
        </el-select>
      </el-form-item>
      <el-form-item label="来源">
        <el-select v-model="addForm.source" style="width: 100%">
          <el-option v-for="s in SOURCES" :key="s" :value="s" :label="enumText(SOURCE_TEXT, s)" />
        </el-select>
      </el-form-item>
      <el-form-item label="表现"><el-input v-model="addForm.manifestation" maxlength="200" /></el-form-item>
      <el-form-item label="备注"><el-input v-model="addForm.note" maxlength="255" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="addDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="saveAllergy">登记</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../../api/client'
import CdssGateBar from './CdssGateBar.vue'
import {
  RESOLUTION_TEXT, SEVERITY_TEXT, SOURCE_TEXT, enumText, fmt, num, type Row,
} from './cdssCommon'

const SEVERITIES = ['UNKNOWN', 'MILD', 'MODERATE', 'SEVERE']
const SOURCES = ['SELF_REPORT', 'CLINICAL', 'TEST', 'TEXT_REVIEW', 'OTHER']

const route = useRoute()
const router = useRouter()
const patientId = ref<number | undefined>(
  route.query.patientId ? Number(route.query.patientId) : undefined,
)
const manualId = ref<number | undefined>(undefined)
const patients = ref<Row[]>([])
const searching = ref(false)
const searchDenied = ref('')
const body = ref<Record<string, unknown> | null>(null)
const loading = ref(false)
const saving = ref(false)
const allergens = ref<Row[]>([])

const coverage = computed(() => (body.value?.coverage ?? null) as Record<string, unknown> | null)
const allergies = computed(() => (body.value?.allergies ?? []) as Row[])
const textReviews = computed(() => (body.value?.textReviews ?? []) as Row[])
const unmapped = computed(() => (coverage.value?.unmappedAllergens ?? []) as { name: string }[])

async function searchPatients(kw: string) {
  if (!kw) return
  searching.value = true
  try {
    patients.value = (await client.get('/patients', { params: { keyword: kw, page: 0, size: 10 } }))
      .data.data.records as Row[]
    searchDenied.value = ''
  } catch (e) {
    const err = e as { response?: { status?: number } }
    searchDenied.value = err?.response?.status === 403 ? 'HTTP 403 无权限' : '检索失败'
  } finally {
    searching.value = false
  }
}

function useManualId() {
  if (!manualId.value) {
    ElMessage.warning('请输入患者 ID')
    return
  }
  patientId.value = manualId.value
  void load()
}

async function load() {
  if (!patientId.value) return
  loading.value = true
  preview.value = null
  try {
    body.value = (await client.get(`/cdss/allergy/patients/${patientId.value}`)).data.data
  } finally {
    loading.value = false
  }
}

async function loadAllergens() {
  allergens.value = ((await client.get('/cdss/allergy/rules', { params: { limit: 200 } }))
    .data.data.allergens ?? []) as Row[]
}

function goReview() {
  void router.push('/cdss/allergy-review')
}

/* ---------------- 登记 / 撤销 ---------------- */
const addDialog = ref(false)
const addForm = reactive({
  allergenId: undefined as number | undefined,
  severity: 'UNKNOWN', source: 'CLINICAL', manifestation: '', note: '',
})

function openAdd() {
  Object.assign(addForm, { allergenId: undefined, severity: 'UNKNOWN', source: 'CLINICAL', manifestation: '', note: '' })
  addDialog.value = true
  if (allergens.value.length === 0) void loadAllergens()
}

async function saveAllergy() {
  if (!addForm.allergenId) {
    ElMessage.warning('请选择过敏原')
    return
  }
  saving.value = true
  try {
    const d = (await client.post(`/cdss/allergy/patients/${patientId.value}/allergies`, {
      allergenId: addForm.allergenId,
      severity: addForm.severity,
      manifestation: addForm.manifestation || undefined,
      source: addForm.source,
      note: addForm.note || undefined,
    })).data.data
    ElMessage.success('已登记')
    // 后端 warnings：过敏原无药品映射时登记了也拦不住，必须原样显示，不能吞
    const warns = (d.warnings ?? []) as string[]
    warns.forEach((w) => ElMessage.warning(w))
    addDialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function revoke(row: Row) {
  const r = await ElMessageBox.prompt(
    '撤销会直接放开一次拦截，必须填写原因（例如：后续皮试证实并非过敏）', '撤销过敏记录',
    { inputPlaceholder: '撤销原因', inputValidator: (v) => (v && v.trim() ? true : '原因必填') },
  ).catch(() => null)
  if (!r) return
  await client.post(
    `/cdss/allergy/patients/${patientId.value}/allergies/${Number(row.id)}/revoke`,
    { reason: r.value },
  )
  ElMessage.success('已撤销')
  await load()
}

/* ---------------- 开单审查预演 ---------------- */
const drugs = ref<Row[]>([])
const drugLoading = ref(false)
const previewDrugIds = ref<number[]>([])
const previewing = ref(false)
const preview = ref<Record<string, unknown> | null>(null)
const hits = computed(() => [
  ...((preview.value?.directHits ?? []) as Row[]),
  ...((preview.value?.crossHits ?? []) as Row[]),
])

async function searchDrugs(kw: string) {
  if (!kw) return
  drugLoading.value = true
  try {
    drugs.value = (await client.get('/masterdata/drugs', { params: { keyword: kw } })).data.data as Row[]
  } finally {
    drugLoading.value = false
  }
}

async function doPreview() {
  previewing.value = true
  try {
    preview.value = (await client.post('/cdss/allergy/preview', {
      patientId: patientId.value, drugIds: previewDrugIds.value,
    })).data.data
  } finally {
    previewing.value = false
  }
}

if (patientId.value) void load()
</script>

<style scoped>
.caveat { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.zero { color: var(--el-color-warning); font-weight: 600; }
.raw { white-space: pre-wrap; word-break: break-all; }
.raw-box {
  white-space: pre-wrap;
  word-break: break-all;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  font-family: Consolas, 'Courier New', monospace;
  line-height: 1.7;
}
.section-title { font-weight: 600; margin: 14px 0 6px; }
.gaps { margin: 4px 0 0 16px; padding: 0; }
</style>
