<template>
  <el-card v-loading="loading">
    <template #header>
      <span style="font-weight: 600">重复用药与同类药 · 口径 / 覆盖率 / 主数据维护</span>
      <el-button type="primary" size="small" style="margin-left: 12px" @click="load">刷新</el-button>
    </template>

    <cdss-gate-bar />

    <!-- ============ 三层的数据源与「现在到底查得出什么」 ============ -->
    <el-alert v-if="unavailableLevels.length" type="error" show-icon :closable="false" class="caveat"
              :title="`有 ${unavailableLevels.length} 层判重【当前查不出任何东西】：${unavailableLevels.join('、')}`">
      <div>
        对应主数据未维护（通用名 / 药理类别列只加了列没填值）。
        <b>「没报重复」不等于「没有重复用药」</b>，只是这一层压根没有数据可比。
      </div>
    </el-alert>
    <el-table :data="levels" size="small" border class="caveat">
      <el-table-column label="匹配层" width="150">
        <template #default="{ row }">{{ enumText(LEVEL_TEXT, row.level) }}</template>
      </el-table-column>
      <el-table-column prop="dataSource" label="数据源" width="220" />
      <el-table-column label="当前是否可用" width="130">
        <!-- available:false 绝不显示成 0 或留白：显示「缺数据源」与原因 -->
        <template #default="{ row }">
          <el-tag v-if="row.available" type="success" size="small">可用</el-tag>
          <el-tag v-else type="danger" size="small">缺数据源</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="可否拦截" width="100">
        <template #default="{ row }">{{ row.blockable ? '可拦' : '恒不拦' }}</template>
      </el-table-column>
      <el-table-column prop="note" label="口径" min-width="320" />
    </el-table>
    <el-table :data="scopes" size="small" border class="caveat">
      <el-table-column label="范围" width="150">
        <template #default="{ row }">{{ enumText(SCOPE_TEXT, row.scope) }}</template>
      </el-table-column>
      <el-table-column label="可否拦截" width="100">
        <template #default="{ row }">{{ row.blockable ? '可拦' : '恒不拦' }}</template>
      </el-table-column>
      <el-table-column prop="note" label="口径" min-width="420" />
    </el-table>

    <el-descriptions v-if="coverage" :column="4" border size="small" class="caveat" title="主数据维护覆盖率">
      <el-descriptions-item label="在用药品数">{{ num(coverage.enabled_drugs) }}</el-descriptions-item>
      <el-descriptions-item label="已维护通用名">
        <span :class="{ bad: Number(coverage.generic_maintained) === 0 }">{{ num(coverage.generic_maintained) }}</span>
        （{{ pct(coverage.generic_maintained, coverage.enabled_drugs) }}）
      </el-descriptions-item>
      <el-descriptions-item label="已维护药理类别">
        <span :class="{ bad: Number(coverage.category_maintained) === 0 }">{{ num(coverage.category_maintained) }}</span>
        （{{ pct(coverage.category_maintained, coverage.enabled_drugs) }}）
      </el-descriptions-item>
      <el-descriptions-item label="回溯窗口">{{ num(lookbackDays) }} 天（{{ lookbackKey }}）</el-descriptions-item>
    </el-descriptions>
    <el-alert v-if="knownGap" type="warning" :closable="false" class="caveat" :title="knownGap" />

    <el-tabs v-model="tab">
      <!-- ===== 只读预览 ===== -->
      <el-tab-pane name="check" label="重复用药预览（只读）">
        <el-alert type="info" :closable="false" class="caveat"
                  title="本页只调用【只读预览】端点：返回全部三层发现，不看 gate、不拦、不落台账。受 gate 管辖并会留痕的 gate-check 是开单链路的入口，不在维护页上调，以免把演示动作写进台账。" />
        <el-alert type="success" :closable="false" class="caveat"
                  title="接线状态（实测 DoctorStationService.createOrders）：重复用药校验【已接进门诊开单主链路】，医生开单时按 gate 自动执行并落台账。本页的预览是额外的自查入口。" />
        <el-input-number v-model="regId" :min="1" :controls="false" size="small"
                         placeholder="挂号 ID" style="width: 150px" />
        <el-button size="small" style="margin-left: 8px" @click="loadWorklist">载入当日挂号</el-button>
        <el-select v-if="worklist.length" v-model="regId" filterable placeholder="当日挂号"
                   style="width: 320px; margin-left: 8px">
          <el-option v-for="w in worklist" :key="Number(w.registrationId)" :value="Number(w.registrationId)"
                     :label="`${w.regNo}　${w.patientName}（${w.patientNo}）`" />
        </el-select>
        <div v-if="worklistDenied" class="muted">{{ worklistDenied }}——请直接填挂号 ID。</div>
        <div style="margin-top: 8px">
          <el-select v-model="checkDrugIds" multiple filterable remote :remote-method="searchDrugs"
                     :loading="drugLoading" placeholder="本次拟开药品（同一个药写两行就选两次——本次提交内部的重复也要抓）"
                     style="width: 560px">
            <el-option v-for="d in drugs" :key="Number(d.id)" :value="Number(d.id)"
                       :label="`${d.name}${d.spec ? ' ' + d.spec : ''}`" />
          </el-select>
          <el-button type="primary" size="small" style="margin-left: 8px" :loading="checking"
                     :disabled="!regId || checkDrugIds.length === 0" @click="doCheck">预览</el-button>
        </div>
        <template v-if="checkResult">
          <el-alert :type="findings.length ? 'warning' : 'info'" show-icon :closable="false" class="caveat"
                    :title="findings.length
                      ? `命中 ${findings.length} 条重复用药提示（gate=${checkResult.gate}，回溯 ${checkResult.lookbackDays} 天）`
                      : `未命中重复用药提示（gate=${checkResult.gate}，回溯 ${checkResult.lookbackDays} 天）——请连同下方「未维护主数据」一起看`" />
          <el-table v-if="findings.length" :data="findings" size="small" border stripe>
            <el-table-column label="范围" width="150">
              <template #default="{ row }">{{ enumText(SCOPE_TEXT, row.scope) }}</template>
            </el-table-column>
            <el-table-column label="匹配层" width="130">
              <template #default="{ row }">{{ enumText(LEVEL_TEXT, row.matchLevel) }}</template>
            </el-table-column>
            <el-table-column label="严重度" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.blocking ? 'danger' : 'warning'">
                  {{ enumText(SEVERITY_TEXT, row.severity) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="newDrugName" label="本次药品" width="140" />
            <el-table-column prop="priorDrugName" label="既往/同诊药品" width="140" />
            <el-table-column label="既往就诊" width="110">
              <template #default="{ row }">{{ row.priorVisitDate ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="剩余疗程" width="100">
              <template #default="{ row }">{{ row.remainingDays === null ? '无法推算' : row.remainingDays + ' 天' }}</template>
            </el-table-column>
            <el-table-column prop="message" label="提示原文" min-width="300" show-overflow-tooltip />
          </el-table>
          <!-- 未维护清单：这是「查不出重复」与「没有重复」的分界线 -->
          <el-alert v-if="unmaintained" type="warning" :closable="false" class="caveat"
                    title="本次涉及药品中未维护主数据的部分（第②③层对它们查不出重复）">
            <div>涉及药品 {{ num(unmaintained.involvedDrugCount) }} 个。</div>
            <div>未维护通用名：{{ listText(unmaintained.missingGenericName) }}</div>
            <div>未维护药理类别：{{ listText(unmaintained.missingPharmCategory) }}</div>
            <div v-if="(unmaintained.unknownDrugIds as unknown[])?.length">
              <b>药品目录里查不到的 ID：{{ listText(unmaintained.unknownDrugIds) }}</b>（未被任何一层检查）
            </div>
          </el-alert>
          <el-alert v-if="checkResult.note" type="info" :closable="false" class="caveat"
                    :title="String(checkResult.note)" />
        </template>
      </el-tab-pane>

      <!-- ===== 台账 ===== -->
      <el-tab-pane name="alerts" label="重复用药台账">
        <el-alert type="warning" :closable="false" class="caveat"
                  title="已知缺口：block 档拦下的那一次随业务异常回滚，台账里查不到；warn 档的放行记录完整可信——而 warn→block 的决策依据恰恰来自 warn 档数据。" />
        <el-input-number v-model="alertRegId" :min="1" :controls="false" size="small"
                         placeholder="按挂号ID筛选（可空）" style="width: 190px" />
        <el-button size="small" style="margin-left: 8px" :loading="alertLoading" @click="loadAlerts">查询台账</el-button>
        <el-table :data="alerts" size="small" border stripe style="margin-top: 8px" max-height="420">
          <el-table-column label="时刻" width="150">
            <template #default="{ row }">{{ fmt(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="当次档位" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="gateTagType(String(row.gate))">{{ row.gate }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="范围" width="140">
            <template #default="{ row }">{{ enumText(SCOPE_TEXT, row.scope) }}</template>
          </el-table-column>
          <el-table-column label="匹配层" width="120">
            <template #default="{ row }">{{ enumText(LEVEL_TEXT, row.match_level) }}</template>
          </el-table-column>
          <el-table-column prop="new_drug_name" label="本次药品" width="140" />
          <el-table-column prop="prior_drug_name" label="既往药品" width="140" />
          <el-table-column label="是否拦下" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.blocked ? 'danger' : 'info'">{{ row.blocked ? '已拦' : '放行' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="提示原文" min-width="300" show-overflow-tooltip />
          <template #empty>暂无台账记录</template>
        </el-table>
      </el-tab-pane>

      <!-- ===== 主数据维护 ===== -->
      <el-tab-pane name="md" label="通用名与药理类别维护">
        <el-alert type="warning" :closable="false" class="caveat"
                  title="不按药名猜：「阿莫西林」与「阿莫西林克拉维酸钾」名字相近但不是同一个药。通用名精确相等比较，不做前缀/相似度匹配。">
          <div>这是<b>主数据</b>，一次改动影响全院所有处方的判重结果，故只开给管理员与药师。</div>
        </el-alert>
        <div class="section-title">① 维护药品通用名</div>
        <el-select v-model="genericForm.drugId" filterable remote :remote-method="searchDrugs" :loading="drugLoading"
                   placeholder="搜索药品" style="width: 300px">
          <el-option v-for="d in drugs" :key="Number(d.id)" :value="Number(d.id)"
                     :label="`${d.name}${d.spec ? ' ' + d.spec : ''}`" />
        </el-select>
        <el-input v-model="genericForm.genericName" placeholder="通用名（留空 = 撤回维护，置回未维护）"
                  style="width: 260px; margin-left: 8px" />
        <el-button type="primary" size="small" style="margin-left: 8px" :loading="saving"
                   :disabled="!genericForm.drugId" @click="saveGeneric">保存</el-button>

        <div class="section-title">② 维护药品药理类别</div>
        <el-select v-model="categoryForm.drugId" filterable remote :remote-method="searchDrugs" :loading="drugLoading"
                   placeholder="搜索药品" style="width: 300px">
          <el-option v-for="d in drugs" :key="Number(d.id)" :value="Number(d.id)"
                     :label="`${d.name}${d.spec ? ' ' + d.spec : ''}`" />
        </el-select>
        <el-select v-model="categoryForm.categoryCode" filterable clearable placeholder="药理类别"
                   style="width: 260px; margin-left: 8px">
          <el-option v-for="c in categories" :key="String(c.code)" :value="String(c.code)"
                     :label="`${c.code}　${c.name}`" />
        </el-select>
        <el-button type="primary" size="small" style="margin-left: 8px" :loading="saving"
                   :disabled="!categoryForm.drugId" @click="saveCategory">保存</el-button>

        <div class="section-title">③ 药理类别字典（出厂只有一条标注为占位的示例行）</div>
        <el-input v-model="catForm.code" placeholder="类别码" style="width: 160px" />
        <el-input v-model="catForm.name" placeholder="类别名称" style="width: 220px; margin-left: 8px" />
        <el-input v-model="catForm.remark" placeholder="备注" style="width: 220px; margin-left: 8px" />
        <el-button type="primary" size="small" style="margin-left: 8px" :loading="saving" @click="saveCat">
          新建 / 改名
        </el-button>
        <el-table :data="categories" size="small" border stripe style="margin-top: 8px" max-height="300">
          <el-table-column prop="code" label="类别码" width="180" />
          <el-table-column prop="name" label="名称" min-width="220" />
          <el-table-column prop="drug_count" label="已归类药品" width="110" />
          <el-table-column prop="remark" label="备注" min-width="240" show-overflow-tooltip />
          <template #empty>药理类别字典为空</template>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../../api/client'
import CdssGateBar from './CdssGateBar.vue'
import {
  LEVEL_TEXT, SCOPE_TEXT, SEVERITY_TEXT, enumText, fmt, gateTagType, num, type Row,
} from './cdssCommon'

const loading = ref(false)
const saving = ref(false)
const tab = ref('check')
const levels = ref<Row[]>([])
const scopes = ref<Row[]>([])
/** 后端 config().coverage 是 JdbcTemplate 直出的一行——键名是**蛇形**，不是驼峰 */
const coverage = ref<Record<string, unknown> | null>(null)
const lookbackDays = ref<number | null>(null)
const lookbackKey = ref('')
const knownGap = ref('')
const categories = ref<Row[]>([])

const unavailableLevels = computed(() => levels.value
  .filter((l) => l.available !== true)
  .map((l) => enumText(LEVEL_TEXT, l.level)))

function pct(a: unknown, b: unknown): string {
  const x = Number(a)
  const y = Number(b)
  if (!Number.isFinite(x) || !Number.isFinite(y) || y === 0) return '—'
  return `${Math.round((x * 10000) / y) / 100}%`
}

function listText(v: unknown): string {
  const arr = (v ?? []) as unknown[]
  return arr.length === 0 ? '（无）' : arr.join('、')
}

async function load() {
  loading.value = true
  try {
    const d = (await client.get('/cdss/duplicate/config')).data.data
    levels.value = (d.levels ?? []) as Row[]
    scopes.value = (d.scopes ?? []) as Row[]
    coverage.value = d.coverage ?? null
    lookbackDays.value = d.lookbackDays ?? null
    lookbackKey.value = String(d.lookbackKey ?? '')
    knownGap.value = String(d.knownGap ?? '')
    categories.value = (await client.get('/cdss/duplicate/categories')).data.data as Row[]
  } finally {
    loading.value = false
  }
}

/* ---------------- 只读预览 ---------------- */
const regId = ref<number | undefined>(undefined)
const worklist = ref<Row[]>([])
const worklistDenied = ref('')
const drugs = ref<Row[]>([])
const drugLoading = ref(false)
const checkDrugIds = ref<number[]>([])
const checking = ref(false)
const checkResult = ref<Record<string, unknown> | null>(null)
const findings = computed(() => (checkResult.value?.findings ?? []) as Row[])
const unmaintained = computed(() => (checkResult.value?.unmaintained ?? null) as Record<string, unknown> | null)

async function searchDrugs(kw: string) {
  if (!kw) return
  drugLoading.value = true
  try {
    drugs.value = (await client.get('/masterdata/drugs', { params: { keyword: kw } })).data.data as Row[]
  } finally {
    drugLoading.value = false
  }
}

/** 当日挂号列表只对医生/管理员开放，故做成点按加载：药师进页面时不该先吃一个 403 */
async function loadWorklist() {
  try {
    const today = new Date()
    const d = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
    worklist.value = (await client.get('/outpatient/doctor/worklist', { params: { date: d } })).data.data as Row[]
    worklistDenied.value = ''
  } catch (e) {
    const err = e as { response?: { status?: number } }
    worklistDenied.value = err?.response?.status === 403 ? '当日挂号列表对当前角色不开放' : '当日挂号列表加载失败'
  }
}

async function doCheck() {
  checking.value = true
  try {
    checkResult.value = (await client.post('/cdss/duplicate/check', {
      registrationId: regId.value, drugIds: checkDrugIds.value,
    })).data.data
  } finally {
    checking.value = false
  }
}

/* ---------------- 台账 ---------------- */
const alerts = ref<Row[]>([])
const alertRegId = ref<number | undefined>(undefined)
const alertLoading = ref(false)

async function loadAlerts() {
  alertLoading.value = true
  try {
    alerts.value = (await client.get('/cdss/duplicate/alerts', {
      params: { registrationId: alertRegId.value || undefined, limit: 200 },
    })).data.data as Row[]
  } finally {
    alertLoading.value = false
  }
}

/* ---------------- 主数据维护 ---------------- */
const genericForm = reactive({ drugId: undefined as number | undefined, genericName: '' })
const categoryForm = reactive({ drugId: undefined as number | undefined, categoryCode: '' })
const catForm = reactive({ code: '', name: '', remark: '' })

async function saveGeneric() {
  saving.value = true
  try {
    const d = (await client.post('/cdss/duplicate/drug-generic', {
      drugId: genericForm.drugId, genericName: genericForm.genericName || null,
    })).data.data
    ElMessage.success(String(d.note ?? '已保存'))
    await load()
  } finally {
    saving.value = false
  }
}

async function saveCategory() {
  saving.value = true
  try {
    const d = (await client.post('/cdss/duplicate/drug-category', {
      drugId: categoryForm.drugId, categoryCode: categoryForm.categoryCode || null,
    })).data.data
    ElMessage.success(String(d.note ?? '已保存'))
    await load()
  } finally {
    saving.value = false
  }
}

async function saveCat() {
  if (!catForm.code.trim() || !catForm.name.trim()) {
    ElMessage.warning('类别码与名称必填')
    return
  }
  saving.value = true
  try {
    await client.post('/cdss/duplicate/categories', { ...catForm })
    ElMessage.success('已保存')
    Object.assign(catForm, { code: '', name: '', remark: '' })
    await load()
  } finally {
    saving.value = false
  }
}

void load()
</script>

<style scoped>
.caveat { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.bad { color: var(--el-color-danger); font-weight: 600; }
.section-title { font-weight: 600; margin: 14px 0 8px; }
</style>
