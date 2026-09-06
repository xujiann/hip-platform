<template>
  <el-card>
    <template #header>
      <span style="font-weight: 600">特殊人群用药 · 妊娠 / 哺乳 / 肝功能不全 / 肾功能不全</span>
      <el-tag type="info" size="small" style="margin-left: 10px">状态只能人工申报，不从病历文本推断</el-tag>
    </template>

    <cdss-gate-bar />

    <el-alert type="warning" show-icon :closable="false" class="caveat"
              title="「否认妊娠」与「妊娠」文本高度相近而语义相反——与过敏史同一类反向拦截风险，故本模块不提供任何「从自由文本推断状态」的功能。">
      <div>
        <b>NEITHER（问过了、不是）与「从未申报」是两件事</b>：前者静默放行，后者会在评估结果里提示「该维度未评估」。
        妊娠 / 哺乳申报<b>必须给失效日</b>——无失效日的妊娠标记会在产后继续拦该患者的所有处方，且没人会想起来清它。
      </div>
      <div>
        接线状态（实测 DoctorStationService.createOrders）：特殊人群校验<b>已接进门诊开单主链路</b>，
        医生开单时按 gate 自动执行并落 cdss_population_alert；本页的「只读评估」是额外的预检入口。
      </div>
    </el-alert>

    <el-tabs v-model="tab">
      <!-- ===== 状态申报 ===== -->
      <el-tab-pane name="status" label="妊娠/哺乳状态维护">
        <el-input-number v-model="patientId" :min="1" :controls="false" size="small"
                         placeholder="患者 ID" style="width: 140px" />
        <el-select v-model="patientId" filterable remote clearable :remote-method="searchPatients"
                   :loading="searching" placeholder="或按姓名/患者号搜索"
                   style="width: 280px; margin-left: 8px">
          <el-option v-for="p in patients" :key="Number(p.id)" :value="Number(p.id)"
                     :label="`${p.name}（${p.patientNo}）`" />
        </el-select>
        <el-button type="primary" size="small" style="margin-left: 8px" :disabled="!patientId"
                   :loading="statusLoading" @click="loadStatus">查询状态</el-button>
        <el-button size="small" :disabled="!patientId" @click="statusDialog = true">申报状态</el-button>
        <div v-if="searchDenied" class="muted">{{ searchDenied }}——请直接填患者 ID。</div>

        <template v-if="status">
          <el-alert :type="statusAlertType" show-icon :closable="false" class="caveat" :title="statusTitle">
            <div v-if="status.reason">原因：{{ status.reason }}</div>
            <div v-if="status.stale">
              该申报距今 <b>{{ num(status.assertedDaysAgo) }}</b> 天，已超过陈旧阈值：本次仍按该状态判定，<b>但请核对</b>。
              三年前的「否认妊娠」永久静默压制警告，是最典型的静默失败。
            </div>
            <div v-if="status.status === 'UNKNOWN'">
              妊娠 / 哺乳类规则对该患者<b>整类不评估</b>——评估结果里没有妊娠警告，不代表该患者用药安全。
            </div>
          </el-alert>
          <el-descriptions :column="4" border size="small" class="caveat">
            <el-descriptions-item label="当前状态">{{ enumText(STATUS_TEXT, status.status) }}</el-descriptions-item>
            <el-descriptions-item label="来源">{{ enumText(SOURCE_TEXT, status.source) }}</el-descriptions-item>
            <el-descriptions-item label="申报时刻">{{ fmt(status.assertedAt) }}</el-descriptions-item>
            <el-descriptions-item label="失效日">{{ status.validUntil ?? '—' }}</el-descriptions-item>
          </el-descriptions>
        </template>

        <el-table :data="history" size="small" border stripe style="margin-top: 8px" max-height="320">
          <el-table-column label="状态" width="150">
            <template #default="{ row }">{{ enumText(STATUS_TEXT, row.status) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="130">
            <template #default="{ row }">{{ enumText(SOURCE_TEXT, row.source) }}</template>
          </el-table-column>
          <el-table-column label="申报时刻" width="150">
            <template #default="{ row }">{{ fmt(row.asserted_at) }}</template>
          </el-table-column>
          <el-table-column prop="asserted_by_name" label="申报人" width="90" />
          <el-table-column prop="valid_until" label="失效日" width="110" />
          <el-table-column label="是否已撤销" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.revoked_at" type="info" size="small">已撤销</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="revoke_reason" label="撤销原因" min-width="150" show-overflow-tooltip />
          <el-table-column prop="note" label="备注" min-width="150" show-overflow-tooltip />
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button v-if="!row.revoked_at" link type="danger" size="small"
                         @click="revokeStatus(row)">撤销</el-button>
            </template>
          </el-table-column>
          <template #empty>该患者尚无申报历史</template>
        </el-table>
        <div class="muted">软撤销：不删行、不改语义列，历史原样保留。</div>
      </el-tab-pane>

      <!-- ===== 规则维护 ===== -->
      <el-tab-pane name="rules" :label="`规则维护（${rules.length}）`">
        <!-- v52：本页签的 GET /rules 不含 NURSE。空表必须给出原因，否则护士会读成「全院一条规则都没有」 -->
        <el-alert v-if="!canReadRules" type="error" show-icon :closable="false" class="caveat"
                  title="当前角色无「特殊人群规则」查阅权限（后端 GET /api/cdss/population/rules 限 ADMIN / 门诊医生 / 药师）">
          <div>
            下表<b>为空是因为没有取数，不是因为全院没有规则</b>——规则照常在开单时生效。
            本页签的维护动作另限 ADMIN / 药师。您可正常使用「妊娠/哺乳状态维护」与「只读评估」两个页签。
          </div>
        </el-alert>
        <el-alert type="warning" :closable="false" class="caveat"
                  title="依据两列必填（出处 + 级别）：只说「孕妇慎用」而不给出处，医生无从判断该不该采纳，最终整类提示会被无视。">
          <div>
            肝/肾规则必须给全「检验项目码 + 比较符 + 阈值 + 单位」：本引擎<b>不做单位换算</b>，
            结果单位与阈值单位不一致时该规则整条跳过并出提示。
            <b>没有批量导入</b>——批量导入等于让一份来源不明的表格一次性变成院内规则。
          </div>
        </el-alert>
        <el-select v-model="rulePopulation" clearable placeholder="按人群筛选" size="small" style="width: 160px"
                   :disabled="!canReadRules" @change="loadRules">
          <el-option v-for="(v, k) in POPULATION_TEXT" :key="k" :value="k" :label="v" />
        </el-select>
        <el-button size="small" style="margin-left: 8px" :loading="ruleLoading" :disabled="!canReadRules"
                   @click="loadRules">刷新</el-button>
        <el-button type="primary" size="small" @click="ruleDialog = true">新增规则</el-button>
        <el-table :data="rules" size="small" border stripe style="margin-top: 8px" max-height="420">
          <el-table-column label="人群" width="120">
            <template #default="{ row }">{{ enumText(POPULATION_TEXT, row.population) }}</template>
          </el-table-column>
          <el-table-column prop="drug_keyword" label="药名关键词" width="150" />
          <el-table-column label="严重度" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.severity === 'FORBID' ? 'danger' : 'warning'">
                {{ enumText(SEVERITY_TEXT, row.severity) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="检验条件" width="200">
            <template #default="{ row }">
              <span v-if="row.lab_item_code">
                {{ row.lab_item_code }} {{ row.comparator }} {{ row.threshold }} {{ row.lab_unit }}
                （近 {{ row.lab_max_age_days }} 天）
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="basis_source" label="依据出处" min-width="180" show-overflow-tooltip />
          <el-table-column prop="basis_level" label="依据级别" width="110" />
          <el-table-column prop="message" label="提示原文" min-width="240" show-overflow-tooltip />
          <el-table-column label="启用" width="90">
            <template #default="{ row }">
              <el-switch :model-value="row.enabled === true" size="small"
                         @change="(v: boolean) => setRuleEnabled(row, v)" />
            </template>
          </el-table-column>
          <template #empty>规则库为空——此时特殊人群校验不会产生任何判定</template>
        </el-table>
        <div class="muted">规则不删，只停用：停用过的规则要能查回来解释历史留痕。</div>
      </el-tab-pane>

      <!-- ===== 只读评估 ===== -->
      <el-tab-pane name="eval" label="只读评估（预检）">
        <el-input-number v-model="evalPatientId" :min="1" :controls="false" size="small"
                         placeholder="患者 ID" style="width: 140px" />
        <el-input-number v-model="evalRegId" :min="1" :controls="false" size="small"
                         placeholder="挂号 ID（可空）" style="width: 170px; margin-left: 8px" />
        <!-- 规则按【药名包含关键词】匹配，故这里收的是药名文本：输入后回车即为一条 -->
        <el-select v-model="evalDrugNames" multiple filterable allow-create default-first-option
                   :reserve-keyword="false" placeholder="药名（输入后回车；规则按关键词包含匹配）"
                   style="width: 420px; margin-left: 8px" />
        <el-button type="primary" size="small" style="margin-left: 8px" :loading="evaluating"
                   :disabled="!evalPatientId" @click="doEvaluate">评估</el-button>

        <template v-if="evalResult">
          <el-alert :type="hits.length ? 'warning' : 'info'" show-icon :closable="false" class="caveat"
                    :title="`gate=${evalResult.gate}；命中 ${hits.length} 条。没有命中【不等于】没问题——请把下面的「本次判不了的维度」看完。`" />
          <!-- notices 是本端点的重点：判不了的维度必须显示 -->
          <el-alert v-for="(n, i) in (evalResult.notices as string[])" :key="'n' + i" type="error"
                    show-icon :closable="false" class="caveat" :title="n" />
          <el-alert v-if="!(evalResult.notices as string[])?.length" type="success" :closable="false" class="caveat"
                    title="本次没有「判不了」的维度：状态已采集、所需检验结果齐全且单位一致。" />
          <el-table v-if="hits.length" :data="hits" size="small" border stripe>
            <el-table-column label="人群" width="120">
              <template #default="{ row }">{{ enumText(POPULATION_TEXT, row.population) }}</template>
            </el-table-column>
            <el-table-column prop="drugName" label="药品" width="150" />
            <el-table-column label="严重度" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.severity === 'FORBID' ? 'danger' : 'warning'">
                  {{ enumText(SEVERITY_TEXT, row.severity) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="message" label="提示" min-width="220" show-overflow-tooltip />
            <el-table-column prop="basisSource" label="依据出处" min-width="160" show-overflow-tooltip />
            <el-table-column prop="basisLevel" label="级别" width="100" />
            <el-table-column prop="evidence" label="证据" min-width="200" show-overflow-tooltip />
          </el-table>
        </template>
      </el-tab-pane>

      <!-- ===== 留痕 ===== -->
      <el-tab-pane name="alerts" label="命中留痕">
        <!-- v52：GET /alerts 限 ADMIN / 门诊医生 / 药师 / 质控，不含 NURSE。空表要说清是没取数 -->
        <el-alert v-if="!canReadAlerts" type="error" show-icon :closable="false" class="caveat"
                  title="当前角色无「命中留痕」查阅权限（后端 GET /api/cdss/population/alerts 限 ADMIN / 门诊医生 / 药师 / 质控）">
          <div>下表<b>为空是因为没有取数，不是因为没有命中过</b>——留痕照常在开单时落库。</div>
        </el-alert>
        <el-alert type="info" :closable="false" class="caveat"
                  title="依据与提示原文是【快照】：规则被改后，仍能还原医生当时看到的原话。" />
        <el-input-number v-model="alertPatientId" :min="1" :controls="false" size="small"
                         :disabled="!canReadAlerts" placeholder="按患者ID筛选（可空）" style="width: 190px" />
        <el-button size="small" style="margin-left: 8px" :loading="alertLoading" :disabled="!canReadAlerts"
                   @click="loadAlerts">查询</el-button>
        <el-table :data="alerts" size="small" border stripe style="margin-top: 8px" max-height="420">
          <el-table-column label="时刻" width="150">
            <template #default="{ row }">{{ fmt(row.occurred_at) }}</template>
          </el-table-column>
          <el-table-column label="人群" width="110">
            <template #default="{ row }">{{ enumText(POPULATION_TEXT, row.population) }}</template>
          </el-table-column>
          <el-table-column prop="drug_name" label="药品" width="140" />
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
          <el-table-column prop="basis_source" label="依据出处（快照）" min-width="170" show-overflow-tooltip />
          <el-table-column prop="basis_level" label="级别" width="100" />
          <el-table-column prop="message" label="提示原文（快照）" min-width="240" show-overflow-tooltip />
          <el-table-column prop="evidence" label="证据" min-width="180" show-overflow-tooltip />
          <template #empty>暂无留痕</template>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <!-- ===== 申报状态 ===== -->
  <el-dialog v-model="statusDialog" title="申报妊娠 / 哺乳状态" width="520px">
    <el-form label-width="90px" size="small">
      <el-form-item label="状态">
        <el-radio-group v-model="statusForm.status">
          <el-radio value="PREGNANT">妊娠</el-radio>
          <el-radio value="LACTATING">哺乳</el-radio>
          <el-radio value="NEITHER">均否（问过了，不是）</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="来源">
        <el-select v-model="statusForm.source" style="width: 100%">
          <el-option value="SELF_REPORT" label="患者自述" />
          <el-option value="CLINICIAN_CONFIRMED" label="临床确认" />
          <el-option value="LAB_CONFIRMED" label="检验确认" />
        </el-select>
      </el-form-item>
      <el-form-item label="失效日">
        <el-date-picker v-model="statusForm.validUntil" type="date" value-format="YYYY-MM-DD"
                        placeholder="预产期 / 预计哺乳结束日" style="width: 100%" />
        <div class="muted">妊娠、哺乳必填；「均否」可空。过期即回落「未采集」，而不是回落到更早的那条申报。</div>
      </el-form-item>
      <el-form-item label="备注"><el-input v-model="statusForm.note" maxlength="255" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="statusDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="saveStatus">申报</el-button>
    </template>
  </el-dialog>

  <!-- ===== 新增规则 ===== -->
  <el-dialog v-model="ruleDialog" title="新增特殊人群用药规则" width="620px">
    <el-form label-width="110px" size="small">
      <el-form-item label="人群">
        <el-select v-model="ruleForm.population" style="width: 100%">
          <el-option v-for="(v, k) in POPULATION_TEXT" :key="k" :value="k" :label="v" />
        </el-select>
      </el-form-item>
      <el-form-item label="药名关键词">
        <el-input v-model="ruleForm.drugKeyword" maxlength="128" placeholder="按【包含】匹配药名，请填能唯一定位的写法" />
      </el-form-item>
      <el-form-item label="严重度">
        <el-radio-group v-model="ruleForm.severity">
          <el-radio value="FORBID">FORBID 禁用</el-radio>
          <el-radio value="CAUTION">CAUTION 慎用</el-radio>
        </el-radio-group>
      </el-form-item>
      <template v-if="ruleForm.population === 'HEPATIC' || ruleForm.population === 'RENAL'">
        <el-form-item label="检验项目码"><el-input v-model="ruleForm.labItemCode" maxlength="64" /></el-form-item>
        <el-form-item label="比较符">
          <el-select v-model="ruleForm.comparator" style="width: 100%">
            <el-option value="LT" label="LT 小于" />
            <el-option value="LTE" label="LTE 小于等于" />
            <el-option value="GT" label="GT 大于" />
            <el-option value="GTE" label="GTE 大于等于" />
          </el-select>
        </el-form-item>
        <el-form-item label="阈值">
          <el-input-number v-model="ruleForm.threshold" :precision="3" :controls="false" style="width: 160px" />
          <el-input v-model="ruleForm.labUnit" placeholder="阈值单位（必填，不做换算）"
                    style="width: 220px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="检验有效期">
          <el-input-number v-model="ruleForm.labMaxAgeDays" :min="1" :max="3650" />
          <span class="muted" style="margin-left: 8px">天内的结果才采用，超期即「判不了」并出提示</span>
        </el-form-item>
      </template>
      <el-form-item label="依据出处">
        <el-input v-model="ruleForm.basisSource" maxlength="255" placeholder="说明书版本 / 院内用药目录 / 指南名称+年份" />
      </el-form-item>
      <el-form-item label="依据级别"><el-input v-model="ruleForm.basisLevel" maxlength="64" /></el-form-item>
      <el-form-item label="提示原文">
        <el-input v-model="ruleForm.message" type="textarea" :rows="2" maxlength="512" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="ruleDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="addRule">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../../api/client'
import { useAuthStore } from '../../../stores/auth'
import CdssGateBar from './CdssGateBar.vue'
import {
  POPULATION_TEXT, SEVERITY_TEXT, SOURCE_TEXT, STATUS_TEXT, enumText, fmt, gateTagType, num, type Row,
} from './cdssCommon'

const tab = ref('status')
const saving = ref(false)

/**
 * v52 合版补：本页四个页签的后端角色集**不是同一个**（逐条读自 PopulationRuleController）：
 *
 * <pre>
 *   POST /status、/status/{id}/revoke        ADMIN DOCTOR_OUTP NURSE
 *   GET  /status、/status/history            ADMIN DOCTOR_OUTP NURSE PHARMACIST
 *   POST /evaluate、GET /gate                ADMIN DOCTOR_OUTP NURSE PHARMACIST
 *   GET  /rules                              ADMIN DOCTOR_OUTP PHARMACIST      ← 无 NURSE
 *   GET  /alerts                             ADMIN DOCTOR_OUTP PHARMACIST QUALITY ← 无 NURSE
 * </pre>
 *
 * 妊娠/哺乳状态申报本就是护理采集，菜单必须授给 NURSE；但 {@code loadRules()} 是在
 * setup 阶段无条件发起的，护士一进页面就会先吃一个 403 全局红字，
 * 而红字写的是「无该功能权限」——护士会以为整个页面都没权限，其实她的主职能（状态申报）是通的。
 * 这里按角色决定发不发这两个请求，并在对应页签里写清「为什么这里是空的」。
 * **这是 UX 收口，不是安全边界**：后端 @PreAuthorize 仍是唯一兜底。
 */
const RULES_ROLES = ['ADMIN', 'DOCTOR_OUTP', 'PHARMACIST']
const ALERTS_ROLES = ['ADMIN', 'DOCTOR_OUTP', 'PHARMACIST', 'QUALITY']
const myRoles = computed(() => useAuthStore().user?.roles ?? [])
const canReadRules = computed(() => myRoles.value.some((r) => RULES_ROLES.includes(r)))
const canReadAlerts = computed(() => myRoles.value.some((r) => ALERTS_ROLES.includes(r)))

/* ---------------- 状态 ---------------- */
const patientId = ref<number | undefined>(undefined)
const patients = ref<Row[]>([])
const searching = ref(false)
const searchDenied = ref('')
const status = ref<Record<string, unknown> | null>(null)
const history = ref<Row[]>([])
const statusLoading = ref(false)
const statusDialog = ref(false)
const statusForm = reactive({ status: 'NEITHER', source: 'CLINICIAN_CONFIRMED', validUntil: '', note: '' })

const statusAlertType = computed<'error' | 'warning' | 'success'>(() => {
  if (!status.value) return 'warning'
  if (status.value.status === 'UNKNOWN') return 'error'
  if (status.value.stale === true) return 'warning'
  return 'success'
})
const statusTitle = computed(() => {
  if (!status.value) return ''
  if (status.value.status === 'UNKNOWN') return '该患者妊娠/哺乳状态【未采集】——该维度本次不评估，缺的是数据不是风险'
  if (status.value.stale === true) return '状态申报已陈旧，请人工核对后再依赖它'
  return `当前状态：${enumText(STATUS_TEXT, status.value.status)}`
})

async function searchPatients(kw: string) {
  if (!kw) return
  searching.value = true
  try {
    patients.value = (await client.get('/patients', { params: { keyword: kw, page: 0, size: 10 } }))
      .data.data.records as Row[]
    searchDenied.value = ''
  } catch (e) {
    const err = e as { response?: { status?: number } }
    searchDenied.value = err?.response?.status === 403 ? '患者检索接口对当前角色不开放' : '患者检索失败'
  } finally {
    searching.value = false
  }
}

async function loadStatus() {
  if (!patientId.value) return
  statusLoading.value = true
  try {
    status.value = (await client.get('/cdss/population/status', { params: { patientId: patientId.value } })).data.data
    history.value = (await client.get('/cdss/population/status/history',
      { params: { patientId: patientId.value, limit: 100 } })).data.data as Row[]
  } finally {
    statusLoading.value = false
  }
}

async function saveStatus() {
  if (statusForm.status !== 'NEITHER' && !statusForm.validUntil) {
    ElMessage.warning('妊娠 / 哺乳必须填失效日：无失效日的状态会在产后继续误拦处方')
    return
  }
  saving.value = true
  try {
    await client.post('/cdss/population/status', {
      patientId: patientId.value, status: statusForm.status, source: statusForm.source,
      validUntil: statusForm.validUntil || undefined, note: statusForm.note || undefined,
    })
    ElMessage.success('已申报')
    statusDialog.value = false
    await loadStatus()
  } finally {
    saving.value = false
  }
}

async function revokeStatus(row: Row) {
  const r = await ElMessageBox.prompt('撤销一条录错的申报（软撤销，历史行保留）', '撤销申报',
    { inputPlaceholder: '撤销原因' }).catch(() => null)
  if (!r) return
  await client.post(`/cdss/population/status/${Number(row.id)}/revoke`, { reason: r.value })
  ElMessage.success('已撤销')
  await loadStatus()
}

/* ---------------- 规则 ---------------- */
const rules = ref<Row[]>([])
const rulePopulation = ref('')
const ruleLoading = ref(false)
const ruleDialog = ref(false)
const ruleForm = reactive({
  population: 'PREGNANCY', drugKeyword: '', severity: 'CAUTION',
  labItemCode: '', comparator: 'LT', threshold: undefined as number | undefined,
  labUnit: '', labMaxAgeDays: 90, basisSource: '', basisLevel: '', message: '',
})

async function loadRules() {
  ruleLoading.value = true
  try {
    rules.value = (await client.get('/cdss/population/rules', {
      params: { population: rulePopulation.value || undefined, limit: 200 },
    })).data.data as Row[]
  } finally {
    ruleLoading.value = false
  }
}

async function addRule() {
  if (!ruleForm.drugKeyword.trim() || !ruleForm.basisSource.trim()
      || !ruleForm.basisLevel.trim() || !ruleForm.message.trim()) {
    ElMessage.warning('药名关键词、依据出处、依据级别、提示原文均为必填')
    return
  }
  const lab = ruleForm.population === 'HEPATIC' || ruleForm.population === 'RENAL'
  saving.value = true
  try {
    await client.post('/cdss/population/rules', {
      drugKeyword: ruleForm.drugKeyword,
      population: ruleForm.population,
      severity: ruleForm.severity,
      labItemCode: lab ? ruleForm.labItemCode : null,
      comparator: lab ? ruleForm.comparator : null,
      threshold: lab ? ruleForm.threshold : null,
      labUnit: lab ? ruleForm.labUnit : null,
      labMaxAgeDays: lab ? ruleForm.labMaxAgeDays : null,
      basisSource: ruleForm.basisSource,
      basisLevel: ruleForm.basisLevel,
      message: ruleForm.message,
    })
    ElMessage.success('已新增')
    ruleDialog.value = false
    await loadRules()
  } finally {
    saving.value = false
  }
}

async function setRuleEnabled(row: Row, enabled: boolean) {
  // 后端此处收的是 query 参数（@RequestParam boolean enabled），不是请求体
  await client.post(`/cdss/population/rules/${Number(row.id)}/enabled`, null, { params: { enabled } })
  ElMessage.success(enabled ? '已启用' : '已停用（规则不删，留痕可回溯）')
  await loadRules()
}

/* ---------------- 评估 ---------------- */
const evalPatientId = ref<number | undefined>(undefined)
const evalRegId = ref<number | undefined>(undefined)
const evalDrugNames = ref<string[]>([])
const evaluating = ref(false)
const evalResult = ref<Record<string, unknown> | null>(null)
const hits = computed(() => (evalResult.value?.hits ?? []) as Row[])

async function doEvaluate() {
  evaluating.value = true
  try {
    evalResult.value = (await client.post('/cdss/population/evaluate', {
      registrationId: evalRegId.value || null, patientId: evalPatientId.value, drugNames: evalDrugNames.value,
    })).data.data
  } finally {
    evaluating.value = false
  }
}

/* ---------------- 留痕 ---------------- */
const alerts = ref<Row[]>([])
const alertPatientId = ref<number | undefined>(undefined)
const alertLoading = ref(false)

async function loadAlerts() {
  alertLoading.value = true
  try {
    alerts.value = (await client.get('/cdss/population/alerts', {
      params: { patientId: alertPatientId.value || undefined, limit: 200 },
    })).data.data as Row[]
  } finally {
    alertLoading.value = false
  }
}

// 无 GET /rules 权限（护士）时不发这一枪：发了必 403，红字还会误导成「整页没权限」
if (canReadRules.value) void loadRules()
</script>

<style scoped>
.caveat { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
</style>
