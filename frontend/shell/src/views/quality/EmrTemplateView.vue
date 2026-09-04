<template>
  <el-card>
    <div class="toolbar">
      <div>
        <h3 style="margin:0">病历模板维护</h3>
        <span class="hint">
          模板按<b>四级作用范围</b>发放：<b>全局</b>（平台预置）与<b>全院</b>人人可见、仅管理员维护；
          <b>科室</b>对本科室与<b>被授权科室</b>可见，创建者与管理员可维护；
          <b>个人</b>只有本人与<b>被授权个人</b>看得到（管理员也看不到别人的个人模板）。
          新建时会<b>自动授权给所属科室 / 创建人</b>，无需手工补。
          停用是软开关：停用后医生站与报告页的下拉不再出现它，<b>但历史病历仍能追溯当时照的是哪一张</b>。
        </span>
      </div>
      <el-button type="primary" size="small" @click="openAdd">新增模板</el-button>
    </div>

    <div class="filters">
      <el-select v-model="filterType" clearable placeholder="全部类型" size="small" style="width: 150px" @change="load">
        <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-select v-model="filterScope" clearable placeholder="全部作用范围" size="small" style="width: 150px" @change="load">
        <el-option v-for="s in SCOPES" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-select v-model="filterDeptId" clearable filterable placeholder="全部科室" size="small" style="width: 170px">
        <el-option label="不挂科室（全局/全院/个人）" :value="0" />
        <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-input v-model="keyword" clearable placeholder="模板名称" size="small" style="width: 160px" @keyup.enter="load" />
      <el-checkbox v-model="includeDisabled" size="small" @change="load">显示已停用</el-checkbox>
      <el-button link type="primary" size="small" @click="load">刷新</el-button>
      <span class="count">共 {{ rows.length }} 条{{ actor.admin ? '（管理员视角）' : '' }}</span>
    </div>

    <el-table :data="rows" size="small" border>
      <el-table-column prop="id" label="ID" width="64" />
      <el-table-column label="模板名称" min-width="190" show-overflow-tooltip>
        <template #default="{ row }">
          <el-tag v-if="row.is_default" size="small" type="danger" effect="dark" style="margin-right:4px">默认</el-tag>
          <span :class="{ off: !row.enabled }">{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="TYPE_TAG[String(row.template_type)] ?? 'info'">
            {{ typeName(row.template_type) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="作用范围" width="150">
        <template #default="{ row }">
          <el-tag size="small" :type="SCOPE_TAG[String(row.scope)] ?? 'info'">{{ row.scopeName }}</el-tag>
          <span v-if="row.dept_id" class="sub">{{ row.dept_name ?? deptName(row.dept_id) }}</span>
          <span v-else-if="row.scope === 'PERSONAL'" class="sub">{{ row.owner_name ?? '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="病历类型" width="110">
        <template #default="{ row }">{{ recordTypeName(row.record_type) }}</template>
      </el-table-column>
      <el-table-column label="授权" width="70">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openGrants(row)">{{ row.grant_count }} 个</el-button>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="76">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '已停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="字数" width="64">
        <template #default="{ row }">{{ String(row.content ?? '').length }}</template>
      </el-table-column>
      <el-table-column label="操作" width="330" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="preview(row)">查看</el-button>
          <el-button link type="primary" size="small" :disabled="!row.editable" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" size="small" @click="openCopy(row)">复制</el-button>
          <el-button v-if="!row.is_default" link type="danger" size="small"
                     :disabled="!row.editable" @click="setDefault(row, false)">设为科室默认</el-button>
          <el-button v-else link type="info" size="small" :disabled="!row.editable"
                     @click="unsetDefault(row)">取消默认</el-button>
          <el-button v-if="row.enabled" link type="danger" size="small"
                     :disabled="!row.editable" @click="toggleEnabled(row, false)">停用</el-button>
          <el-button v-else link type="success" size="small"
                     :disabled="!row.editable" @click="toggleEnabled(row, true)">启用</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!rows.length" description="当前筛选下没有可见模板" :image-size="60" />

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="680px">
      <el-form :model="form" label-width="94px" size="small">
        <el-form-item label="模板名称" required>
          <el-input v-model="form.name" maxlength="64" show-word-limit placeholder="如：入院记录·呼吸内科" />
        </el-form-item>
        <el-form-item label="模板类型" required>
          <el-select v-model="form.templateType" style="width: 200px">
            <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="作用范围" required>
          <el-select v-model="form.scope" style="width: 200px">
            <el-option v-for="s in SCOPES" :key="s.value" :label="s.label" :value="s.value"
                       :disabled="!actor.grantableScopes.includes(s.value)" />
          </el-select>
          <span class="sub">{{ SCOPE_HINT[form.scope] }}</span>
        </el-form-item>
        <el-form-item v-if="form.scope === 'DEPT'" label="所属科室" required>
          <el-select v-model="form.deptId" clearable filterable placeholder="留空＝本人所在科室" style="width: 200px">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <span class="sub">建好后自动授权给该科室</span>
        </el-form-item>
        <el-form-item label="病历类型">
          <el-select v-model="form.recordType" clearable filterable allow-create default-first-option
                     placeholder="不绑定" style="width: 200px">
            <el-option v-for="r in RECORD_TYPES" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
          <span class="sub">「科室默认模板」按<b>科室 + 病历类型</b>取，要设默认必须先绑定这两项</span>
        </el-form-item>
        <el-form-item label="模板正文" required>
          <el-input v-model="form.content" type="textarea" :rows="12" maxlength="4000" show-word-limit
                    placeholder="主诉：&#10;现病史：&#10;既往史：&#10;体格检查：&#10;辅助检查：&#10;初步诊断：" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 授权维护 -->
    <el-dialog v-model="grantVisible" :title="`授权：${grantRow?.name ?? ''}`" width="620px">
      <p class="hint" style="margin-top:0">
        授权<b>只放大可见范围，不放大维护权限</b>——被授权的科室与个人可以套用这张模板，但改不动它。
        建模板时自动授予的那一条（科室模板→所属科室 / 个人模板→创建人）不可撤销：撤掉之后模板对自己都不可见。
      </p>
      <div class="filters">
        <el-select v-model="grantType" size="small" style="width: 110px" @change="loadCandidates">
          <el-option label="授权科室" value="DEPT" />
          <el-option label="授权个人" value="USER" />
        </el-select>
        <el-select v-model="granteeId" filterable clearable size="small" style="width: 220px"
                   :placeholder="grantType === 'DEPT' ? '选择科室' : '选择人员'">
          <el-option v-for="c in candidates" :key="c.id" :label="candidateLabel(c)" :value="c.id" />
        </el-select>
        <el-button type="primary" size="small" :disabled="!granteeId" :loading="granting" @click="addGrant">
          授予
        </el-button>
      </div>
      <el-table :data="grantRows" size="small" border>
        <el-table-column label="对象类型" width="100">
          <template #default="{ row }">{{ row.grantee_type === 'DEPT' ? '科室' : '个人' }}</template>
        </el-table-column>
        <el-table-column prop="grantee_name" label="对象" min-width="140" />
        <el-table-column prop="granted_by_name" label="授予人" width="110" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" size="small" :disabled="!grantRow?.editable"
                       @click="revokeGrant(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!grantRows.length" description="暂无授权记录" :image-size="50" />
      <template #footer><el-button @click="grantVisible = false">关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="previewVisible" :title="`模板：${previewRow?.name ?? ''}`" width="640px">
      <pre class="preview">{{ previewRow?.content }}</pre>
      <template #footer><el-button @click="previewVisible = false">关闭</el-button></template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import type { BizError } from '../../api/client'

/** 与 emr_template.template_type 一致（V116 起分类）：EMR 病历 / RIS 报告 / CONSENT 同意书 */
const TYPES = [
  { value: 'EMR', label: '病历模板' },
  { value: 'RIS', label: 'RIS 报告' },
  { value: 'CONSENT', label: '知情同意书' },
]
const TYPE_TAG: Record<string, 'success' | 'primary' | 'danger'> = {
  EMR: 'success', RIS: 'primary', CONSENT: 'danger',
}

/** 四级作用范围（V138 / 1073★），顺序从大到小 */
const SCOPES = [
  { value: 'GLOBAL', label: '全局（平台预置）' },
  { value: 'HOSPITAL', label: '全院' },
  { value: 'DEPT', label: '科室' },
  { value: 'PERSONAL', label: '个人' },
]
const SCOPE_TAG: Record<string, 'danger' | 'warning' | 'primary' | 'info'> = {
  GLOBAL: 'danger', HOSPITAL: 'warning', DEPT: 'primary', PERSONAL: 'info',
}
const SCOPE_HINT: Record<string, string> = {
  GLOBAL: '人人可见，仅管理员维护',
  HOSPITAL: '人人可见，仅管理员维护',
  DEPT: '本科室 + 被授权科室可见，创建者与管理员可维护',
  PERSONAL: '仅本人 + 被授权个人可见（管理员也看不到别人的个人模板）',
}

/**
 * 病历类型是**开放集合**：后端刻意不设白名单（与 V133 对 record_type 的取舍同口径，
 * 实施期院方常有自定义类型），故下拉允许 allow-create 自行输入。
 */
const RECORD_TYPES = [
  { value: 'ADMISSION', label: '入院记录' },
  { value: 'FIRST_PROGRESS', label: '首次病程' },
  { value: 'PROGRESS', label: '病程记录' },
  { value: 'ROUND', label: '查房记录' },
  { value: 'PREOP', label: '术前小结' },
  { value: 'DISCHARGE', label: '出院记录' },
  { value: 'OUTP', label: '门诊病历' },
]

type Dept = { id: number; name: string; type: string }
type Tpl = {
  id: number; name: string; content: string; template_type: string
  scope: string; scopeName: string; dept_id: number | null; dept_name: string | null
  owner_id: number | null; owner_name: string | null; record_type: string | null
  is_default: boolean; enabled: boolean; grant_count: number; editable: boolean
}
type Grant = {
  id: number; grantee_type: string; grantee_id: number
  grantee_name: string | null; granted_by_name: string | null
}
type Candidate = { id: number; name: string; dept_name?: string | null }
type Actor = {
  userId: number | null; deptId: number | null; deptName: string | null
  admin: boolean; grantableScopes: string[]
}
type Form = {
  id: number | null; name: string; templateType: string; scope: string
  deptId: number | null; recordType: string | null; content: string
}

const all = ref<Tpl[]>([])
const depts = ref<Dept[]>([])
const actor = ref<Actor>({ userId: null, deptId: null, deptName: null, admin: false, grantableScopes: [] })
const filterType = ref<string | null>('EMR')
const filterScope = ref<string | null>(null)
const filterDeptId = ref<number | null>(null)
const keyword = ref('')
const includeDisabled = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('新增模板')
const saving = ref(false)
const previewVisible = ref(false)
const previewRow = ref<Tpl | null>(null)
const form = reactive<Form>(blank())

// 授权对话框
const grantVisible = ref(false)
const grantRow = ref<Tpl | null>(null)
const grantRows = ref<Grant[]>([])
const grantType = ref('DEPT')
const granteeId = ref<number | null>(null)
const candidates = ref<Candidate[]>([])
const granting = ref(false)

function blank(): Form {
  return { id: null, name: '', templateType: 'EMR', scope: 'PERSONAL', deptId: null, recordType: null, content: '' }
}

/**
 * 科室筛选放在前端做「精确匹配」：后端 /emr-templates/visible 的作用范围过滤是**按登录人可见性**
 * 算的（本科室 + 被授权科室 + 全院 + 个人），维护页还要能单独看清某个科室到底挂了哪些自有模板，
 * 两者是不同的问题，混用一个参数会把全院模板混进来、误导维护人重复建模板。
 */
const rows = computed(() => {
  if (filterDeptId.value == null) return all.value
  if (filterDeptId.value === 0) return all.value.filter((r) => !r.dept_id)
  return all.value.filter((r) => Number(r.dept_id) === filterDeptId.value)
})

function typeName(v: unknown) {
  return TYPES.find((t) => t.value === String(v))?.label ?? String(v ?? '')
}
function recordTypeName(v: unknown) {
  if (v == null || v === '') return '—'
  return RECORD_TYPES.find((r) => r.value === String(v))?.label ?? String(v)
}
function deptName(id: unknown) {
  return depts.value.find((d) => d.id === Number(id))?.name ?? `科室${id}`
}
function candidateLabel(c: Candidate) {
  return c.dept_name ? `${c.name}（${c.dept_name}）` : c.name
}

async function load() {
  const params: Record<string, unknown> = { includeDisabled: includeDisabled.value }
  if (filterType.value) params.type = filterType.value
  if (filterScope.value) params.scope = filterScope.value
  if (keyword.value.trim()) params.keyword = keyword.value.trim()
  all.value = (await client.get('/emr-templates/visible', { params })).data.data
}

function openAdd() {
  Object.assign(form, blank())
  form.templateType = filterType.value ?? 'EMR'
  // 默认落在自己能建的最小范围：有科室就是科室模板，否则个人
  form.scope = actor.value.deptId ? 'DEPT' : 'PERSONAL'
  form.deptId = actor.value.deptId
  dialogTitle.value = '新增模板'
  dialogVisible.value = true
}

function openEdit(row: Tpl) {
  Object.assign(form, {
    id: row.id,
    name: row.name,
    templateType: row.template_type ?? 'EMR',
    scope: row.scope ?? 'PERSONAL',
    deptId: row.dept_id ?? null,
    recordType: row.record_type ?? null,
    content: row.content ?? '',
  })
  dialogTitle.value = `编辑 #${row.id}《${row.name}》`
  dialogVisible.value = true
}

/** 复制为新模板：协定口径不变——想保留旧版可追溯时用它，而不是就地改写 */
function openCopy(row: Tpl) {
  Object.assign(form, {
    id: null,
    name: `${row.name} 副本`,
    templateType: row.template_type ?? 'EMR',
    scope: actor.value.grantableScopes.includes(row.scope) ? row.scope : 'PERSONAL',
    deptId: row.dept_id ?? actor.value.deptId,
    recordType: row.record_type ?? null,
    content: row.content ?? '',
  })
  dialogTitle.value = `复制自 #${row.id}《${row.name}》`
  dialogVisible.value = true
}

function preview(row: Tpl) {
  previewRow.value = row
  previewVisible.value = true
}

async function save() {
  if (!form.name.trim()) { ElMessage.warning('模板名称必填'); return }
  if (!form.content.trim()) { ElMessage.warning('模板正文必填'); return }
  const body = {
    name: form.name.trim(),
    content: form.content,
    templateType: form.templateType,
    scope: form.scope,
    deptId: form.scope === 'DEPT' ? form.deptId : null,
    recordType: form.recordType || null,
  }
  saving.value = true
  try {
    if (form.id) await client.put(`/emr-templates/${form.id}`, body)
    else await client.post('/emr-templates/scoped', body)
    ElMessage.success(form.id ? '模板已更新' : '模板已保存（已自动授权给所属科室/创建人）')
    dialogVisible.value = false
    // 把筛选切到刚保存的这一条上，否则"保存成功但列表里找不到"会让维护人重复建模板
    filterType.value = form.templateType
    filterScope.value = null
    filterDeptId.value = null
    await load()
  } finally { saving.value = false }
}

async function toggleEnabled(row: Tpl, enabled: boolean) {
  if (!enabled) {
    const ok = await ElMessageBox.confirm(
      `停用《${row.name}》后，医生站与报告页的模板下拉不再出现它；已按它写成的历史病历不受影响。`,
      '停用模板', { type: 'warning' }).catch(() => null)
    if (!ok) return
  }
  await client.put(`/emr-templates/${row.id}/${enabled ? 'enable' : 'disable'}`)
  ElMessage.success(enabled ? '已启用' : '已停用')
  await load()
}

/** 4067 科室默认模板冲突：不弹默认红字，改成「是否替换」的引导框（replace=true 走同一事务，无并发窗口） */
async function setDefault(row: Tpl, replace: boolean) {
  try {
    await client.put(`/emr-templates/${row.id}/default`, null,
      { params: { replace }, __silentCodes: [4067] })
    ElMessage.success('已设为该科室该病历类型的默认模板')
    await load()
  } catch (e) {
    const err = e as BizError
    if (err.bizCode !== 4067) return
    const ok = await ElMessageBox.confirm(`${err.message}\n是否改由《${row.name}》接管默认位？`,
      '默认模板冲突', { type: 'warning', confirmButtonText: '替换为默认' }).catch(() => null)
    if (!ok) return
    // 只有"已有默认模板占位"这一种冲突可以替换；缺科室/缺病历类型/模板已停用会再次返回 4067，
    // 此时按提示回去补齐即可（不再递归弹框）
    await client.put(`/emr-templates/${row.id}/default`, null, { params: { replace: true } })
    ElMessage.success('已替换为默认模板')
    await load()
  }
}

async function unsetDefault(row: Tpl) {
  await client.delete(`/emr-templates/${row.id}/default`)
  ElMessage.success('已取消默认')
  await load()
}

async function openGrants(row: Tpl) {
  grantRow.value = row
  granteeId.value = null
  grantType.value = 'DEPT'
  grantVisible.value = true
  await Promise.all([loadGrants(), loadCandidates()])
}

async function loadGrants() {
  if (!grantRow.value) return
  grantRows.value = (await client.get(`/emr-templates/${grantRow.value.id}/grants`)).data.data
}

async function loadCandidates() {
  granteeId.value = null
  candidates.value = (await client.get('/emr-templates/grantee-candidates',
    { params: { granteeType: grantType.value } })).data.data
}

async function addGrant() {
  if (!grantRow.value || !granteeId.value) return
  granting.value = true
  try {
    await client.post(`/emr-templates/${grantRow.value.id}/grants`,
      { granteeType: grantType.value, granteeId: granteeId.value })
    ElMessage.success('已授权')
    granteeId.value = null
    await Promise.all([loadGrants(), load()])
  } finally { granting.value = false }
}

async function revokeGrant(row: Grant) {
  if (!grantRow.value) return
  await client.delete(`/emr-templates/${grantRow.value.id}/grants/${row.id}`)
  ElMessage.success('已撤销授权')
  await Promise.all([loadGrants(), load()])
}

onMounted(async () => {
  depts.value = (await client.get('/system/depts')).data.data
  actor.value = (await client.get('/emr-templates/actor')).data.data
  await load()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 12px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; display: block; margin-top: 4px; max-width: 980px; line-height: 1.6; }
.filters { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
.count { color: var(--el-text-color-secondary); font-size: 12px; }
.sub { color: var(--el-text-color-secondary); font-size: 12px; margin-left: 8px; }
.off { color: var(--el-text-color-secondary); text-decoration: line-through; }
.preview { white-space: pre-wrap; word-break: break-all; margin: 0; font-family: inherit; font-size: 13px; line-height: 1.7; }
</style>
