<template>
  <el-card v-loading="loading">
    <template #header>
      <span style="font-weight: 600">给药途径与溶媒配伍 · 规则维护与只读核对</span>
      <el-button type="primary" size="small" style="margin-left: 12px" @click="load">刷新</el-button>
    </template>

    <cdss-gate-bar />

    <!-- ============ 覆盖率：为 0 时校验不产生任何判定，这一点必须先说 ============ -->
    <el-alert v-if="cov" :type="covType" show-icon :closable="false" class="caveat" :title="covTitle">
      <div v-if="cov.routeCheckActive !== true">
        <b>途径校验当前不产生任何判定</b>：已配置适用途径的药品数为 {{ num(cov.drugsWithRouteConfigured) }}。
        规则表是空的，<b>不是「全院途径都对」</b>。
      </div>
      <div v-if="cov.solventCheckActive !== true">
        <b>溶媒配伍校验当前不产生任何判定</b>：已登记溶媒品规 {{ num(cov.solventDrugsRegistered) }} 个、
        有配伍规则的药品 {{ num(cov.drugsWithSolventRule) }} 个，两者都需非零才会判定。
      </div>
    </el-alert>
    <el-descriptions v-if="cov" :column="4" border size="small" class="caveat" title="维护覆盖率">
      <el-descriptions-item label="在用药品数">{{ num(cov.enabledDrugs) }}</el-descriptions-item>
      <el-descriptions-item label="已配适用途径药品">
        <span :class="{ bad: Number(cov.drugsWithRouteConfigured) === 0 }">{{ num(cov.drugsWithRouteConfigured) }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="途径覆盖率">{{ num(cov.routeCoverageRatePct) }}%</el-descriptions-item>
      <el-descriptions-item label="计算式">{{ cov.rateFormula }}</el-descriptions-item>
      <el-descriptions-item label="途径词表（示例行/已维护）">
        {{ num(cov.routeDictExampleRows) }} / {{ num(cov.routeDictMaintainedRows) }}
      </el-descriptions-item>
      <el-descriptions-item label="用法别名（示例行/已维护）">
        {{ num(cov.routeAliasExampleRows) }} / {{ num(cov.routeAliasMaintainedRows) }}
      </el-descriptions-item>
      <el-descriptions-item label="已登记溶媒品规">{{ num(cov.solventDrugsRegistered) }}</el-descriptions-item>
      <el-descriptions-item label="有溶媒配伍规则药品">{{ num(cov.drugsWithSolventRule) }}</el-descriptions-item>
    </el-descriptions>
    <el-alert v-if="cov?.note" type="info" :closable="false" class="caveat" :title="String(cov.note)" />
    <!-- caveats：口径自述，逐条显示，不折叠 -->
    <el-alert v-for="(c, i) in caveats" :key="i" type="warning" :closable="false" class="caveat" :title="c" />
    <el-alert v-if="matching" type="info" :closable="false" class="caveat" :title="matching" />

    <el-tabs v-model="tab">
      <!-- ===== 只读核对 ===== -->
      <el-tab-pane name="review" label="只读核对（不留痕、不拦截）">
        <el-alert type="error" show-icon :closable="false" class="caveat"
                  title="接线状态（实测 DoctorStationService.createOrders）：途径与溶媒校验【尚未接进门诊开单主链路】——同版的过敏、重复用药、特殊人群三条已接，本条没有">
          <div>
            也就是说：医生走正常开单路径<b>不会</b>触发途径/溶媒判定，无论 gate 是 warn 还是 block。
            现在只能由本页的只读核对、或由调用方显式调 <code>/api/cdss/route/check</code> 触发。已作为接线需求交主控。
          </div>
        </el-alert>
        <el-alert type="info" :closable="false" class="caveat"
                  title="本页只调用【只读】端点：不写台账、不拦截。会留痕并可能拦单的 /check 属开单链路，不在维护页上调。" />
        <div>
          <el-input-number v-model="regId" :min="1" :controls="false" size="small"
                           placeholder="挂号 ID" style="width: 150px" />
          <el-button type="primary" size="small" style="margin-left: 8px" :disabled="!regId"
                     :loading="reviewing" @click="reviewRegistration">回顾核对该挂号已开药品医嘱</el-button>
          <span class="muted">读 outp_order 的 item_id / usage_route / group_no，走同一套判定，只报不写。</span>
        </div>
        <div class="section-title">或手工组一组医嘱行试判</div>
        <div v-for="(l, i) in lines" :key="i" class="line-row">
          <el-select v-model="l.drugId" filterable remote :remote-method="searchDrugs" :loading="drugLoading"
                     placeholder="药品" style="width: 240px">
            <el-option v-for="d in drugs" :key="Number(d.id)" :value="Number(d.id)"
                       :label="`${d.name}${d.spec ? ' ' + d.spec : ''}`" />
          </el-select>
          <el-input v-model="l.usageRoute" placeholder="用法途径原文（如：静脉滴注）" style="width: 220px" />
          <el-input v-model="l.groupNo" placeholder="处方组号（同组配对溶媒）" style="width: 180px" />
          <el-button link type="danger" size="small" @click="lines.splice(i, 1)">移除</el-button>
        </div>
        <el-button size="small" @click="lines.push({ drugId: undefined, usageRoute: '', groupNo: '' })">
          + 增加一行
        </el-button>
        <el-button type="primary" size="small" :loading="reviewing" @click="doReview">只读试判</el-button>

        <template v-if="result">
          <el-alert :type="violations.length ? 'warning' : 'info'" show-icon :closable="false" class="caveat"
                    :title="`途径 gate=${result.routeGate}、溶媒 gate=${result.solventGate}；`
                      + `送审 ${num(result.lineCount)} 行，违规 ${violations.length} 条`" />
          <el-table v-if="violations.length" :data="violations" size="small" border stripe>
            <el-table-column label="提示类型" width="200">
              <template #default="{ row }">{{ enumText(ROUTE_KIND_TEXT, row.kind) }}</template>
            </el-table-column>
            <el-table-column label="严重度" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.severity === 'VIOLATION' ? 'danger' : 'info'">
                  {{ enumText(SEVERITY_TEXT, row.severity) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="drugName" label="药品" width="150" />
            <el-table-column prop="routeTextRaw" label="用法原文" width="140" />
            <el-table-column prop="message" label="提示原文" min-width="320" />
          </el-table>
          <!-- notes 是「没判」的那部分：未识别、未维护，一条都不能吞 -->
          <el-alert v-for="(n, i) in (result.notes as string[])" :key="'note' + i" type="warning"
                    :closable="false" class="caveat" :title="n" />
          <el-empty v-if="!violations.length && !(result.notes as string[])?.length" :image-size="50"
                    description="本组未产生任何判定——请连同上方覆盖率一起看：未维护的药品不会被判定，也不会被拦截" />
        </template>
      </el-tab-pane>

      <!-- ===== 途径词表与别名 ===== -->
      <el-tab-pane name="route" :label="`途径词表（${routes.length}）/ 别名（${aliases.length}）`">
        <el-alert type="warning" :closable="false" class="caveat"
                  title="用法文本按【归一化后等值】查找：只去空白、ASCII 转小写。「静滴」与「静脉滴注」是两条别名，必须分别登记——引擎不做同义推断，也不做正则/包含匹配。" />
        <div>
          <el-input v-model="routeForm.routeCode" placeholder="途径编码" style="width: 150px" />
          <el-input v-model="routeForm.routeName" placeholder="途径名称" style="width: 180px; margin-left: 8px" />
          <el-checkbox v-model="routeForm.intravenous" style="margin-left: 8px">静脉途径（只有静脉途径才跑溶媒检查）</el-checkbox>
          <el-input v-model="routeForm.remark" placeholder="备注" style="width: 180px; margin-left: 8px" />
          <el-button type="primary" size="small" style="margin-left: 8px" :loading="saving" @click="addRoute">
            登记途径
          </el-button>
        </div>
        <el-table :data="routes" size="small" border stripe style="margin-top: 8px" max-height="280">
          <el-table-column prop="route_code" label="途径编码" width="150" />
          <el-table-column prop="route_name" label="途径名称" min-width="160" />
          <el-table-column label="静脉途径" width="90">
            <template #default="{ row }">{{ row.intravenous ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column label="示例行" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.is_example" type="warning" size="small">示例</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="200" show-overflow-tooltip />
          <template #empty>途径词表为空</template>
        </el-table>

        <div class="section-title">用法文本别名</div>
        <el-input v-model="aliasForm.aliasText" placeholder="用法原文（入库前按同一函数归一化）" style="width: 260px" />
        <el-select v-model="aliasForm.routeCode" filterable placeholder="对应途径" style="width: 200px; margin-left: 8px">
          <el-option v-for="r in routes" :key="String(r.route_code)" :value="String(r.route_code)"
                     :label="`${r.route_code}　${r.route_name}`" />
        </el-select>
        <el-button type="primary" size="small" style="margin-left: 8px" :loading="saving" @click="addAlias">
          登记别名
        </el-button>
        <el-table :data="aliases" size="small" border stripe style="margin-top: 8px" max-height="280">
          <el-table-column prop="alias_text" label="用法文本（归一化）" width="220" />
          <el-table-column prop="route_code" label="途径编码" width="150" />
          <el-table-column prop="route_name" label="途径名称" min-width="160" />
          <el-table-column label="示例行" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.is_example" type="warning" size="small">示例</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <template #empty>别名表为空——此时任何用法文本都识别不了，也就不会判定</template>
        </el-table>
      </el-tab-pane>

      <!-- ===== 药品适用途径 ===== -->
      <el-tab-pane name="drugRoute" :label="`药品适用途径（${drugRoutes.length}）`">
        <el-alert type="warning" :closable="false" class="caveat"
                  title="md_drug 没有给药途径字段，dose_form 是剂型不是途径、不可互推（注射剂里可静滴/仅肌注/禁静推三类都有，猜错就是拦错）。本表是该缺口的可维护替代，建完即空。" />
        <el-select v-model="drugRouteForm.drugId" filterable remote :remote-method="searchDrugs" :loading="drugLoading"
                   placeholder="药品" style="width: 260px">
          <el-option v-for="d in drugs" :key="Number(d.id)" :value="Number(d.id)"
                     :label="`${d.name}${d.spec ? ' ' + d.spec : ''}`" />
        </el-select>
        <el-select v-model="drugRouteForm.routeCode" filterable placeholder="适用途径" style="width: 200px; margin-left: 8px">
          <el-option v-for="r in routes" :key="String(r.route_code)" :value="String(r.route_code)"
                     :label="`${r.route_code}　${r.route_name}`" />
        </el-select>
        <el-input v-model="drugRouteForm.basisSource" placeholder="依据出处（必填）" style="width: 240px; margin-left: 8px" />
        <el-button type="primary" size="small" style="margin-left: 8px" :loading="saving" @click="addDrugRoute">
          维护
        </el-button>
        <el-table :data="drugRoutes" size="small" border stripe style="margin-top: 8px" max-height="400">
          <el-table-column prop="drug_name" label="药品" min-width="180" />
          <el-table-column prop="route_code" label="途径编码" width="150" />
          <el-table-column prop="route_name" label="途径名称" width="150" />
          <el-table-column prop="basis_source" label="依据出处" min-width="220" show-overflow-tooltip />
          <el-table-column label="启用" width="80">
            <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
          </el-table-column>
          <template #empty>尚无任何药品配置适用途径——此时途径校验一次也不会判定</template>
        </el-table>
      </el-tab-pane>

      <!-- ===== 溶媒 ===== -->
      <el-tab-pane name="solvent" :label="`溶媒品规（${solventDrugs.length}）/ 配伍规则（${solventRules.length}）`">
        <el-alert type="warning" :closable="false" class="caveat"
                  title="未维护 ≠ 禁止：药品未配途径、该药无溶媒规则、该溶媒未登记，三种情形一律不判定。拿录到一半的表当禁配依据，是把维护缺口伪装成临床问题。" />
        <div class="section-title">① 登记溶媒品规（把某个药品标记为溶媒）</div>
        <el-select v-model="solventDrugForm.drugId" filterable remote :remote-method="searchDrugs"
                   :loading="drugLoading" placeholder="溶媒药品" style="width: 240px">
          <el-option v-for="d in drugs" :key="Number(d.id)" :value="Number(d.id)"
                     :label="`${d.name}${d.spec ? ' ' + d.spec : ''}`" />
        </el-select>
        <el-input v-model="solventDrugForm.solventCode" placeholder="溶媒编码" style="width: 150px; margin-left: 8px" />
        <el-input v-model="solventDrugForm.solventName" placeholder="溶媒名称" style="width: 180px; margin-left: 8px" />
        <el-input v-model="solventDrugForm.basisSource" placeholder="依据出处（必填）" style="width: 200px; margin-left: 8px" />
        <el-button type="primary" size="small" style="margin-left: 8px" :loading="saving" @click="addSolventDrug">
          登记
        </el-button>
        <el-table :data="solventDrugs" size="small" border stripe style="margin-top: 8px" max-height="240">
          <el-table-column prop="drug_name" label="药品" min-width="180" />
          <el-table-column prop="solvent_code" label="溶媒编码" width="150" />
          <el-table-column prop="solvent_name" label="溶媒名称" width="180" />
          <el-table-column prop="basis_source" label="依据出处" min-width="220" show-overflow-tooltip />
          <template #empty>尚无溶媒品规登记</template>
        </el-table>

        <div class="section-title">② 维护溶媒配伍规则</div>
        <el-select v-model="solventRuleForm.drugId" filterable remote :remote-method="searchDrugs"
                   :loading="drugLoading" placeholder="主药" style="width: 220px">
          <el-option v-for="d in drugs" :key="Number(d.id)" :value="Number(d.id)"
                     :label="`${d.name}${d.spec ? ' ' + d.spec : ''}`" />
        </el-select>
        <el-select v-model="solventRuleForm.solventCode" filterable placeholder="溶媒编码"
                   style="width: 180px; margin-left: 8px">
          <el-option v-for="s in solventCodes" :key="s.code" :value="s.code" :label="`${s.code}　${s.name}`" />
        </el-select>
        <el-select v-model="solventRuleForm.verdict" style="width: 130px; margin-left: 8px">
          <el-option value="ALLOW" label="ALLOW 可配" />
          <el-option value="FORBID" label="FORBID 禁配" />
        </el-select>
        <el-input v-model="solventRuleForm.basisSource" placeholder="依据出处（必填）" style="width: 200px; margin-left: 8px" />
        <el-input v-model="solventRuleForm.basisLevel" placeholder="依据级别（必填）" style="width: 150px; margin-left: 8px" />
        <el-input v-model="solventRuleForm.message" placeholder="提示原文（必填）" style="width: 300px; margin-top: 8px" />
        <el-button type="primary" size="small" style="margin-left: 8px" :loading="saving" @click="addSolventRule">
          维护
        </el-button>
        <el-table :data="solventRules" size="small" border stripe style="margin-top: 8px" max-height="280">
          <el-table-column prop="drug_name" label="主药" min-width="160" />
          <el-table-column prop="solvent_code" label="溶媒编码" width="150" />
          <el-table-column label="配伍结论" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.verdict === 'FORBID' ? 'danger' : 'success'">{{ row.verdict }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="basis_source" label="依据出处" min-width="180" show-overflow-tooltip />
          <el-table-column prop="basis_level" label="级别" width="110" />
          <el-table-column prop="message" label="提示原文" min-width="240" show-overflow-tooltip />
          <template #empty>尚无溶媒配伍规则</template>
        </el-table>
      </el-tab-pane>

      <!-- ===== 留痕 ===== -->
      <el-tab-pane name="alerts" label="提示留痕">
        <el-select v-model="alertKind" clearable placeholder="按类型筛选" size="small" style="width: 260px">
          <el-option v-for="(v, k) in ROUTE_KIND_TEXT" :key="k" :value="k" :label="v" />
        </el-select>
        <el-button size="small" style="margin-left: 8px" :loading="alertLoading" @click="loadAlerts">查询</el-button>
        <span class="muted">药剂科据此补别名、复核拦截。</span>
        <el-table :data="alerts" size="small" border stripe style="margin-top: 8px" max-height="420">
          <el-table-column label="时刻" width="150">
            <template #default="{ row }">{{ fmt(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="类型" width="200">
            <template #default="{ row }">{{ enumText(ROUTE_KIND_TEXT, row.kind) }}</template>
          </el-table-column>
          <el-table-column prop="drug_name" label="药品" width="150" />
          <el-table-column prop="route_text_raw" label="用法原文" width="140" />
          <el-table-column prop="route_text_norm" label="归一化" width="130" />
          <el-table-column label="当次档位" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="gateTagType(String(row.gate))">{{ row.gate }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="是否拦下" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.blocked ? 'danger' : 'info'">{{ row.blocked ? '已拦' : '放行' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="提示原文" min-width="300" show-overflow-tooltip />
          <template #empty>暂无留痕</template>
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
import { ROUTE_KIND_TEXT, SEVERITY_TEXT, enumText, fmt, gateTagType, num, type Row } from './cdssCommon'

interface Line { drugId: number | undefined; usageRoute: string; groupNo: string }

const loading = ref(false)
const saving = ref(false)
const tab = ref('review')
const routes = ref<Row[]>([])
const aliases = ref<Row[]>([])
const drugRoutes = ref<Row[]>([])
const solventDrugs = ref<Row[]>([])
const solventRules = ref<Row[]>([])
const cov = ref<Record<string, unknown> | null>(null)
const caveats = ref<string[]>([])
const matching = ref('')

const covType = computed<'error' | 'warning' | 'success'>(() => {
  if (!cov.value) return 'warning'
  if (cov.value.routeCheckActive !== true && cov.value.solventCheckActive !== true) return 'error'
  if (cov.value.routeCheckActive !== true || cov.value.solventCheckActive !== true) return 'warning'
  return 'success'
})
const covTitle = computed(() => {
  if (covType.value === 'error') return '途径与溶媒校验当前【都不产生任何判定】——请先看这一条'
  if (covType.value === 'warning') return '两项校验中有一项当前不产生任何判定'
  return '途径与溶媒规则均已有可用维护数据'
})

const solventCodes = computed(() => {
  const seen = new Map<string, string>()
  solventDrugs.value.forEach((s) => seen.set(String(s.solvent_code), String(s.solvent_name)))
  return [...seen].map(([code, name]) => ({ code, name }))
})

async function load() {
  loading.value = true
  try {
    const d = (await client.get('/cdss/route/rules')).data.data
    routes.value = (d.routes ?? []) as Row[]
    aliases.value = (d.aliases ?? []) as Row[]
    drugRoutes.value = (d.drugRoutes ?? []) as Row[]
    solventDrugs.value = (d.solventDrugs ?? []) as Row[]
    solventRules.value = (d.solventRules ?? []) as Row[]
    cov.value = d.coverage ?? null
    caveats.value = (d.caveats ?? []) as string[]
    const s = (await client.get('/cdss/route/settings')).data.data
    matching.value = `${s.matching}　${s.note}`
  } finally {
    loading.value = false
  }
}

/* ---------------- 只读核对 ---------------- */
const regId = ref<number | undefined>(undefined)
const lines = ref<Line[]>([{ drugId: undefined, usageRoute: '', groupNo: '' }])
const drugs = ref<Row[]>([])
const drugLoading = ref(false)
const reviewing = ref(false)
const result = ref<Record<string, unknown> | null>(null)
const violations = computed(() => (result.value?.violations ?? []) as Row[])

async function searchDrugs(kw: string) {
  if (!kw) return
  drugLoading.value = true
  try {
    drugs.value = (await client.get('/masterdata/drugs', { params: { keyword: kw } })).data.data as Row[]
  } finally {
    drugLoading.value = false
  }
}

async function doReview() {
  const payload = lines.value.filter((l) => l.drugId).map((l) => ({
    drugId: l.drugId, usageRoute: l.usageRoute || null, groupNo: l.groupNo || null,
  }))
  if (payload.length === 0) {
    ElMessage.warning('至少填一行药品')
    return
  }
  reviewing.value = true
  try {
    result.value = (await client.post('/cdss/route/review', { lines: payload })).data.data
  } finally {
    reviewing.value = false
  }
}

async function reviewRegistration() {
  reviewing.value = true
  try {
    result.value = (await client.get(`/cdss/route/review/registration/${regId.value}`)).data.data
  } finally {
    reviewing.value = false
  }
}

/* ---------------- 维护 ---------------- */
const routeForm = reactive({ routeCode: '', routeName: '', intravenous: false, remark: '' })
const aliasForm = reactive({ aliasText: '', routeCode: '' })
const drugRouteForm = reactive({ drugId: undefined as number | undefined, routeCode: '', basisSource: '' })
const solventDrugForm = reactive({
  drugId: undefined as number | undefined, solventCode: '', solventName: '', basisSource: '',
})
const solventRuleForm = reactive({
  drugId: undefined as number | undefined, solventCode: '', verdict: 'FORBID',
  basisSource: '', basisLevel: '', message: '',
})

async function post(url: string, body: Record<string, unknown>, okMsg: string) {
  saving.value = true
  try {
    const d = (await client.post(url, body)).data.data
    ElMessage.success(String(d?.note ?? okMsg))
    await load()
  } finally {
    saving.value = false
  }
}

function addRoute() {
  if (!routeForm.routeCode.trim() || !routeForm.routeName.trim()) {
    ElMessage.warning('途径编码与名称必填')
    return
  }
  void post('/cdss/route/routes', { ...routeForm }, '已登记途径')
}

function addAlias() {
  if (!aliasForm.aliasText.trim() || !aliasForm.routeCode) {
    ElMessage.warning('别名文本与途径编码必填')
    return
  }
  void post('/cdss/route/aliases', { ...aliasForm }, '已登记别名')
}

function addDrugRoute() {
  if (!drugRouteForm.drugId || !drugRouteForm.routeCode || !drugRouteForm.basisSource.trim()) {
    ElMessage.warning('药品、途径与依据出处均必填——没有出处的规则入不了库')
    return
  }
  void post('/cdss/route/drug-routes', { ...drugRouteForm }, '已维护适用途径')
}

function addSolventDrug() {
  if (!solventDrugForm.drugId || !solventDrugForm.solventCode.trim()
      || !solventDrugForm.solventName.trim() || !solventDrugForm.basisSource.trim()) {
    ElMessage.warning('药品、溶媒编码、溶媒名称与依据出处均必填')
    return
  }
  void post('/cdss/route/solvent-drugs', { ...solventDrugForm }, '已登记溶媒品规')
}

function addSolventRule() {
  if (!solventRuleForm.drugId || !solventRuleForm.solventCode
      || !solventRuleForm.basisSource.trim() || !solventRuleForm.basisLevel.trim()
      || !solventRuleForm.message.trim()) {
    ElMessage.warning('主药、溶媒、依据出处、依据级别与提示原文均必填')
    return
  }
  void post('/cdss/route/solvent-rules', { ...solventRuleForm }, '已维护配伍规则')
}

/* ---------------- 留痕 ---------------- */
const alerts = ref<Row[]>([])
const alertKind = ref('')
const alertLoading = ref(false)

async function loadAlerts() {
  alertLoading.value = true
  try {
    alerts.value = (await client.get('/cdss/route/alerts', {
      params: { kind: alertKind.value || undefined, limit: 200 },
    })).data.data as Row[]
  } finally {
    alertLoading.value = false
  }
}

void load()
</script>

<style scoped>
.caveat { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; margin-left: 8px; }
.bad { color: var(--el-color-danger); font-weight: 600; }
.section-title { font-weight: 600; margin: 14px 0 8px; }
.line-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
</style>
