<template>
  <el-card v-loading="loading">
    <template #header>
      <span style="font-weight: 600">过敏规则维护（药剂科）· 过敏原目录 / 药品映射 / 交叉过敏族</span>
      <el-button type="primary" size="small" style="margin-left: 12px" @click="load">刷新</el-button>
    </template>

    <cdss-gate-bar />

    <el-alert type="warning" show-icon :closable="false" class="caveat"
              title="本平台不预置任何药学判断：过敏原目录、药品映射、交叉族的内容全部由药剂科按院内用药目录逐条维护">
      <div>
        <b>禁止用药名子串或相似度自动生成映射</b>：子串既拦不住「青霉素过敏 vs 阿莫西林」（假阴性），
        又会把「碘过敏 vs 碘伏」一并拦下（假阳性）。命中的<b>唯一</b>依据是下面这张显式映射表。
      </div>
      <div>
        接线状态（实测 DoctorStationService.createOrders）：过敏审查<b>已接进门诊开单主链路</b>，
        医生开单时按 gate 自动执行并写台账；本页的「预演」只是额外的自查入口。
      </div>
    </el-alert>

    <!-- ============ 覆盖率：把 gate 收紧到 block 的唯一数据依据 ============ -->
    <el-alert v-if="cov" :type="covType" show-icon :closable="false" class="caveat" :title="covTitle">
      <div v-if="Number(cov.drugMapCount) === 0">
        <b>过敏原→药品映射表为空</b>：无论 gate 是 warn 还是 block，过敏审查现在<b>拦不住任何一张处方</b>。
        这不是「全院没有过敏禁忌」，是规则表还没维护。
      </div>
      <div v-else-if="Number(cov.allergenWithoutDrugMap) > 0">
        有 <b>{{ num(cov.allergenWithoutDrugMap) }}</b> 个过敏原一条药品映射都没有——它们躺在患者档案里只是好看，永远不会命中。
      </div>
      <div v-if="Number(cov.patientsTextPending) > 0">
        另有 <b>{{ num(cov.patientsTextPending) }}</b> 名患者的自由文本过敏史<b>未经人工核对</b>，
        对他们而言「未发现过敏禁忌」这句话不成立。
        <el-button link type="primary" size="small" @click="$router.push('/cdss/allergy-review')">去核对工作台</el-button>
      </div>
    </el-alert>
    <el-descriptions v-if="cov" :column="4" border size="small" class="caveat" title="维护完成度">
      <el-descriptions-item label="过敏原总数">{{ num(cov.allergenCount) }}</el-descriptions-item>
      <el-descriptions-item label="其中无药品映射">
        <span :class="{ bad: Number(cov.allergenWithoutDrugMap) > 0 }">{{ num(cov.allergenWithoutDrugMap) }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="映射条数">
        <span :class="{ bad: Number(cov.drugMapCount) === 0 }">{{ num(cov.drugMapCount) }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="被映射到的药品数">{{ num(cov.mappedDrugCount) }}</el-descriptions-item>
      <el-descriptions-item label="在用药品总数">{{ num(cov.enabledDrugCount) }}</el-descriptions-item>
      <el-descriptions-item label="有自由文本过敏史患者">{{ num(cov.patientsWithFreeText) }}</el-descriptions-item>
      <el-descriptions-item label="其中待人工核对">
        <span :class="{ bad: Number(cov.patientsTextPending) > 0 }">{{ num(cov.patientsTextPending) }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="有结构化过敏原患者">{{ num(cov.patientsWithStructured) }}</el-descriptions-item>
    </el-descriptions>
    <el-alert v-if="note" type="info" :closable="false" class="caveat" :title="note" />

    <el-tabs v-model="tab">
      <!-- ===== 过敏原目录 ===== -->
      <el-tab-pane name="allergen" :label="`过敏原目录（${allergens.length}）`">
        <el-button type="primary" size="small" @click="allergenDialog = true">新增过敏原</el-button>
        <el-table :data="allergens" size="small" border stripe style="margin-top: 8px" max-height="420">
          <el-table-column prop="code" label="编码" width="150" />
          <el-table-column prop="name" label="名称" min-width="180" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ ALLERGEN_TYPE[String(row.allergen_type)] ?? row.allergen_type }}</template>
          </el-table-column>
          <el-table-column label="药物侧粒度" width="110">
            <template #default="{ row }">{{ enumText(MAPPED_LEVEL_TEXT, row.drug_level) }}</template>
          </el-table-column>
          <el-table-column label="已映射药品" width="110">
            <template #default="{ row }">
              <span :class="{ bad: Number(row.mapped_drug_count) === 0 }">{{ num(row.mapped_drug_count) }}</span>
              <el-tag v-if="Number(row.mapped_drug_count) === 0" type="warning" size="small"
                      style="margin-left: 4px">拦不住</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="patient_count" label="生效患者数" width="100" />
          <el-table-column label="启用" width="150">
            <template #default="{ row }">
              <el-switch :model-value="row.enabled === true" size="small"
                         @change="(v: boolean) => setEnabled(row, v)" />
              <span class="muted">停用只挡新登记</span>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openMap(row)">药品映射</el-button>
            </template>
          </el-table-column>
          <template #empty>过敏原目录为空——此时过敏审查什么也拦不住</template>
        </el-table>
      </el-tab-pane>

      <!-- ===== 交叉过敏族 ===== -->
      <el-tab-pane name="group" :label="`交叉过敏族（${groups.length}）`">
        <el-alert type="info" :closable="false" class="caveat"
                  title="交叉命中在开单侧【恒为警告、三档下永不拦截】：交叉过敏是概率性的，硬拦会让青霉素过敏患者用不了任何头孢，医生随之养成无脑点继续的习惯，真警告也会被一起点掉。" />
        <el-button type="primary" size="small" @click="groupDialog = true">新增族</el-button>
        <el-button size="small" @click="memberDialog = true">向族内添加过敏原</el-button>
        <el-button size="small" @click="crossDialog = true">登记族间交叉风险</el-button>
        <el-table :data="groups" size="small" border stripe style="margin-top: 8px" max-height="260">
          <el-table-column prop="code" label="族编码" width="160" />
          <el-table-column prop="name" label="族名称" min-width="180" />
          <el-table-column prop="member_count" label="成员数" width="90" />
          <el-table-column label="启用" width="80">
            <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
          <template #empty>暂无交叉过敏族</template>
        </el-table>
        <div class="muted" style="margin: 6px 0">
          后端未提供「族成员列表」查询端点（规则总览只回成员计数，移除成员需要的 memberId 无处可取），
          故此处只能新增成员、无法逐条列出与移除——已作为接口缺口交主控，不做假按钮。
        </div>
        <div class="section-title">族间交叉风险（无序对，A-B 与 B-A 是同一条）</div>
        <el-table :data="crossRisks" size="small" border stripe max-height="260">
          <el-table-column prop="group_lo_name" label="族 A" min-width="150" />
          <el-table-column prop="group_hi_name" label="族 B" min-width="150" />
          <el-table-column label="风险" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.risk_level === 'HIGH' ? 'danger' : 'warning'">{{ row.risk_level }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="说明" min-width="220" show-overflow-tooltip />
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="removeCross(row)">撤销</el-button>
            </template>
          </el-table-column>
          <template #empty>暂无族间交叉风险</template>
        </el-table>
      </el-tab-pane>

      <!-- ===== 审查台账 ===== -->
      <el-tab-pane name="log" label="过敏审查台账">
        <el-alert type="info" :closable="false" class="caveat"
                  title="三档都记、命中与否都记——否则「过敏拦截命中率」的分母是假的，也就永远没有依据决定何时收紧到 block。" />
        <el-input-number v-model="logPatientId" :min="1" :controls="false" size="small"
                         placeholder="按患者ID筛选（可空）" style="width: 180px" />
        <el-button size="small" style="margin-left: 8px" :loading="logLoading" @click="loadLog">查询台账</el-button>
        <el-table :data="gateLog" size="small" border stripe style="margin-top: 8px" max-height="420">
          <el-table-column label="时刻" width="150">
            <template #default="{ row }">{{ fmt(row.occurred_at) }}</template>
          </el-table-column>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column label="当次档位" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="gateTagType(String(row.gate))">{{ row.gate }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="drug_count" label="送审药品" width="90" />
          <el-table-column prop="direct_hits" label="直接命中" width="90" />
          <el-table-column prop="cross_hits" label="交叉命中" width="90" />
          <el-table-column label="是否拦下" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.blocked ? 'danger' : 'info'">{{ row.blocked ? '已拦' : '放行' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="覆盖缺口" width="200">
            <template #default="{ row }">
              <el-tag v-if="row.unreviewed_text" type="warning" size="small">仍有未核对原文</el-tag>
              <el-tag v-if="Number(row.unmapped_count) > 0" type="warning" size="small" style="margin-left: 4px">
                {{ row.unmapped_count }} 个过敏原无映射
              </el-tag>
              <span v-if="!row.unreviewed_text && !Number(row.unmapped_count)" class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="命中摘要" min-width="260" show-overflow-tooltip />
          <el-table-column prop="operator_name" label="操作人" width="90" />
          <template #empty>暂无审查台账</template>
        </el-table>
        <el-alert v-if="logNote" type="info" :closable="false" class="caveat" :title="logNote" />
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <!-- ===== 新增过敏原 ===== -->
  <el-dialog v-model="allergenDialog" title="新增过敏原" width="520px">
    <el-form label-width="110px" size="small">
      <el-form-item label="编码"><el-input v-model="allergenForm.code" maxlength="32" /></el-form-item>
      <el-form-item label="名称"><el-input v-model="allergenForm.name" maxlength="64" /></el-form-item>
      <el-form-item label="类型">
        <el-select v-model="allergenForm.allergenType" style="width: 100%">
          <el-option v-for="(v, k) in ALLERGEN_TYPE" :key="k" :value="k" :label="v" />
        </el-select>
      </el-form-item>
      <el-form-item label="药物侧粒度">
        <el-select v-model="allergenForm.drugLevel" clearable style="width: 100%" placeholder="非药物过敏原可空">
          <el-option v-for="(v, k) in MAPPED_LEVEL_TEXT" :key="k" :value="k" :label="v" />
        </el-select>
      </el-form-item>
      <el-form-item label="备注"><el-input v-model="allergenForm.remark" maxlength="255" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="allergenDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="addAllergen">保存</el-button>
    </template>
  </el-dialog>

  <!-- ===== 药品映射 ===== -->
  <el-drawer v-model="mapDrawer" size="52%" :title="`过敏原「${mapAllergen?.name ?? ''}」的院内药品映射`">
    <el-alert type="warning" :closable="false" class="caveat"
              title="这是全模块唯一的命中依据。没映射 = 开单时永远不会命中，不是「该药安全」。" />
    <el-select v-model="mapForm.drugId" filterable remote :remote-method="searchDrugs" :loading="drugLoading"
               placeholder="搜索院内药品" style="width: 280px">
      <el-option v-for="d in drugs" :key="Number(d.id)" :value="Number(d.id)"
                 :label="`${d.name}${d.spec ? ' ' + d.spec : ''}`" />
    </el-select>
    <el-select v-model="mapForm.mappedLevel" style="width: 130px; margin-left: 8px">
      <el-option v-for="(v, k) in MAPPED_LEVEL_TEXT" :key="k" :value="k" :label="v" />
    </el-select>
    <el-input v-model="mapForm.note" placeholder="依据说明（选填）" style="width: 200px; margin-left: 8px" />
    <el-button type="primary" size="small" style="margin-left: 8px" :loading="saving" @click="addMap">新增映射</el-button>
    <el-table :data="mapItems" size="small" border stripe style="margin-top: 10px">
      <el-table-column prop="drug_name" label="药品" min-width="180" />
      <el-table-column prop="spec" label="规格" width="130" />
      <el-table-column label="映射粒度" width="110">
        <template #default="{ row }">{{ enumText(MAPPED_LEVEL_TEXT, row.mapped_level) }}</template>
      </el-table-column>
      <el-table-column prop="note" label="说明" min-width="150" show-overflow-tooltip />
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button link type="danger" size="small" @click="removeMap(row)">撤销</el-button>
        </template>
      </el-table-column>
      <template #empty>该过敏原尚无任何药品映射</template>
    </el-table>
  </el-drawer>

  <!-- ===== 族 / 成员 / 交叉 ===== -->
  <el-dialog v-model="groupDialog" title="新增交叉过敏族" width="480px">
    <el-form label-width="90px" size="small">
      <el-form-item label="族编码"><el-input v-model="groupForm.code" maxlength="32" /></el-form-item>
      <el-form-item label="族名称"><el-input v-model="groupForm.name" maxlength="64" /></el-form-item>
      <el-form-item label="备注"><el-input v-model="groupForm.remark" maxlength="255" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="groupDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="addGroup">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="memberDialog" title="向交叉过敏族添加过敏原" width="480px">
    <el-form label-width="90px" size="small">
      <el-form-item label="族">
        <el-select v-model="memberForm.groupId" style="width: 100%" filterable>
          <el-option v-for="g in groups" :key="Number(g.id)" :value="Number(g.id)"
                     :label="`${g.code}　${g.name}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="过敏原">
        <el-select v-model="memberForm.allergenId" style="width: 100%" filterable>
          <el-option v-for="a in allergens" :key="Number(a.id)" :value="Number(a.id)"
                     :label="`${a.code}　${a.name}`" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="memberDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="addMember">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="crossDialog" title="登记族间交叉风险" width="520px">
    <el-alert type="info" :closable="false" class="caveat"
              title="本平台不预置任何交叉率数字：「青霉素与头孢交叉 X%」随文献与头孢代际大幅变动，编一个进去就是假证据。风险高低按院内共识填。" />
    <el-form label-width="90px" size="small">
      <el-form-item label="族 A">
        <el-select v-model="crossForm.groupIdA" style="width: 100%" filterable>
          <el-option v-for="g in groups" :key="Number(g.id)" :value="Number(g.id)" :label="`${g.code}　${g.name}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="族 B">
        <el-select v-model="crossForm.groupIdB" style="width: 100%" filterable>
          <el-option v-for="g in groups" :key="Number(g.id)" :value="Number(g.id)" :label="`${g.code}　${g.name}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="风险级别">
        <el-select v-model="crossForm.riskLevel" style="width: 100%">
          <el-option value="HIGH" label="HIGH 高" />
          <el-option value="MEDIUM" label="MEDIUM 中" />
          <el-option value="LOW" label="LOW 低" />
        </el-select>
      </el-form-item>
      <el-form-item label="依据说明"><el-input v-model="crossForm.note" maxlength="255" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="crossDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="addCross">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../../api/client'
import CdssGateBar from './CdssGateBar.vue'
import { MAPPED_LEVEL_TEXT, enumText, fmt, gateTagType, num, type Row } from './cdssCommon'

const ALLERGEN_TYPE: Record<string, string> = { DRUG: '药物', FOOD: '食物', OTHER: '其他' }

const loading = ref(false)
const saving = ref(false)
const tab = ref('allergen')
const allergens = ref<Row[]>([])
const groups = ref<Row[]>([])
const crossRisks = ref<Row[]>([])
const cov = ref<Record<string, unknown> | null>(null)
const note = ref('')

const covType = computed<'error' | 'warning' | 'success'>(() => {
  if (!cov.value) return 'warning'
  if (Number(cov.value.drugMapCount) === 0) return 'error'
  if (Number(cov.value.allergenWithoutDrugMap) > 0 || Number(cov.value.patientsTextPending) > 0) return 'warning'
  return 'success'
})
const covTitle = computed(() => {
  if (covType.value === 'error') return '过敏审查当前【不具备拦截能力】——请先看这一条'
  if (covType.value === 'warning') return '过敏审查存在覆盖缺口：缺口内的处方永远不会被拦下'
  return '过敏原映射与人工核对均无已知缺口'
})

async function load() {
  loading.value = true
  try {
    const d = (await client.get('/cdss/allergy/rules', { params: { limit: 200 } })).data.data
    allergens.value = (d.allergens ?? []) as Row[]
    groups.value = (d.groups ?? []) as Row[]
    crossRisks.value = (d.crossRisks ?? []) as Row[]
    cov.value = d.coverage ?? null
    note.value = d.note ?? ''
  } finally {
    loading.value = false
  }
}

/* ---------------- 过敏原 ---------------- */
const allergenDialog = ref(false)
const allergenForm = reactive({ code: '', name: '', allergenType: 'DRUG', drugLevel: '', remark: '' })

async function addAllergen() {
  if (!allergenForm.code.trim() || !allergenForm.name.trim()) {
    ElMessage.warning('编码与名称必填')
    return
  }
  saving.value = true
  try {
    await client.post('/cdss/allergy/allergens', {
      code: allergenForm.code, name: allergenForm.name, allergenType: allergenForm.allergenType,
      drugLevel: allergenForm.drugLevel || undefined, remark: allergenForm.remark || undefined,
    })
    ElMessage.success('已新增')
    allergenDialog.value = false
    Object.assign(allergenForm, { code: '', name: '', allergenType: 'DRUG', drugLevel: '', remark: '' })
    await load()
  } finally {
    saving.value = false
  }
}

async function setEnabled(row: Row, enabled: boolean) {
  const d = (await client.put(`/cdss/allergy/allergens/${Number(row.id)}/enabled`, { enabled })).data.data
  // 后端 note 明说停用不影响既有患者记录参与审查——原样弹出，别让人误以为「停用=不再拦」
  ElMessage.warning(String(d.note ?? '已更新'))
  await load()
}

/* ---------------- 药品映射 ---------------- */
const mapDrawer = ref(false)
const mapAllergen = ref<Row | null>(null)
const mapItems = ref<Row[]>([])
const drugs = ref<Row[]>([])
const drugLoading = ref(false)
const mapForm = reactive({ drugId: undefined as number | undefined, mappedLevel: 'INGREDIENT', note: '' })

async function searchDrugs(kw: string) {
  if (!kw) return
  drugLoading.value = true
  try {
    drugs.value = (await client.get('/masterdata/drugs', { params: { keyword: kw } })).data.data as Row[]
  } finally {
    drugLoading.value = false
  }
}

async function openMap(row: Row) {
  mapAllergen.value = row
  mapForm.drugId = undefined
  mapDrawer.value = true
  await loadMap()
}

async function loadMap() {
  if (!mapAllergen.value) return
  mapItems.value = ((await client.get(`/cdss/allergy/allergens/${Number(mapAllergen.value.id)}/drugs`,
    { params: { limit: 200 } })).data.data.items ?? []) as Row[]
}

async function addMap() {
  if (!mapForm.drugId) {
    ElMessage.warning('请选择药品')
    return
  }
  saving.value = true
  try {
    await client.post(`/cdss/allergy/allergens/${Number(mapAllergen.value?.id)}/drugs`, {
      drugId: mapForm.drugId, mappedLevel: mapForm.mappedLevel, note: mapForm.note || undefined,
    })
    ElMessage.success('已映射')
    mapForm.drugId = undefined
    mapForm.note = ''
    await loadMap()
    await load()
  } finally {
    saving.value = false
  }
}

async function removeMap(row: Row) {
  const ok = await ElMessageBox.confirm('撤销后该药品不再因此过敏原被命中，确认？', '撤销映射', { type: 'warning' })
    .catch(() => null)
  if (!ok) return
  await client.delete(`/cdss/allergy/allergen-drugs/${Number(row.id)}`)
  ElMessage.success('已撤销')
  await loadMap()
  await load()
}

/* ---------------- 族 / 成员 / 交叉 ---------------- */
const groupDialog = ref(false)
const memberDialog = ref(false)
const crossDialog = ref(false)
const groupForm = reactive({ code: '', name: '', remark: '' })
const memberForm = reactive({ groupId: undefined as number | undefined, allergenId: undefined as number | undefined })
const crossForm = reactive({
  groupIdA: undefined as number | undefined, groupIdB: undefined as number | undefined,
  riskLevel: 'MEDIUM', note: '',
})

async function addGroup() {
  if (!groupForm.code.trim() || !groupForm.name.trim()) {
    ElMessage.warning('族编码与名称必填')
    return
  }
  saving.value = true
  try {
    await client.post('/cdss/allergy/groups', { ...groupForm })
    ElMessage.success('已新增')
    groupDialog.value = false
    Object.assign(groupForm, { code: '', name: '', remark: '' })
    await load()
  } finally {
    saving.value = false
  }
}

async function addMember() {
  if (!memberForm.groupId || !memberForm.allergenId) {
    ElMessage.warning('请选择族与过敏原')
    return
  }
  saving.value = true
  try {
    await client.post(`/cdss/allergy/groups/${memberForm.groupId}/members`, { allergenId: memberForm.allergenId })
    ElMessage.success('已添加成员')
    memberDialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function addCross() {
  if (!crossForm.groupIdA || !crossForm.groupIdB) {
    ElMessage.warning('请选择两个族')
    return
  }
  saving.value = true
  try {
    await client.post('/cdss/allergy/cross', { ...crossForm, note: crossForm.note || undefined })
    ElMessage.success('已登记')
    crossDialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function removeCross(row: Row) {
  const ok = await ElMessageBox.confirm('撤销后这对族之间不再产生交叉风险提示，确认？', '撤销交叉风险',
    { type: 'warning' }).catch(() => null)
  if (!ok) return
  await client.delete(`/cdss/allergy/cross/${Number(row.id)}`)
  ElMessage.success('已撤销')
  await load()
}

/* ---------------- 台账 ---------------- */
const gateLog = ref<Row[]>([])
const logPatientId = ref<number | undefined>(undefined)
const logLoading = ref(false)
const logNote = ref('')

async function loadLog() {
  logLoading.value = true
  try {
    const d = (await client.get('/cdss/allergy/gate-log', {
      params: { patientId: logPatientId.value || undefined, limit: 200 },
    })).data.data
    gateLog.value = (d.items ?? []) as Row[]
    logNote.value = d.note ?? ''
  } finally {
    logLoading.value = false
  }
}

void load()
</script>

<style scoped>
.caveat { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.bad { color: var(--el-color-danger); font-weight: 600; }
.section-title { font-weight: 600; margin: 14px 0 6px; }
</style>
