<template>
  <el-card>
    <div class="toolbar">
      <div>
        <h3 style="margin:0">处方模板与协定处方</h3>
        <span class="hint">
          模板按三级范围发放：<b>个人</b>只有本人可见可改；<b>科室</b>本科室医生可见、创建者与管理员可改；
          <b>全院</b>所有医生可见、仅管理员可改。<b>协定处方</b>（药事委员会审定的固定组合）套用时整组带入，
          <b>明细任何人都不可就地修改</b>——需要调整请停用本模板后另建新版，
          这样已按旧版开出的处方仍追得到当时用的是哪一版。
          <br>
          <b>套用模板只是把明细填进医生站的开单表单，不代替也不跳过任何开单校验</b>：
          医生点「开立」时仍走原有开单接口，过敏禁忌、同诊重复用药、抗菌药分级处方权、CDSS 审查、
          停用药拦截与库存预警<b>照常执行</b>。模板只提速，不放行。
        </span>
      </div>
      <el-button type="primary" size="small" @click="openAdd">新增模板</el-button>
    </div>

    <div class="filters">
      <el-select v-model="filterCategory" clearable placeholder="全部类别" size="small"
                 style="width: 150px" @change="load">
        <el-option v-for="c in CATEGORIES" :key="c.value" :label="c.label" :value="c.value" />
      </el-select>
      <el-input v-model="keyword" clearable placeholder="按模板名称检索" size="small"
                style="width: 200px" @keyup.enter="load" />
      <el-checkbox v-model="includeDisabled" size="small" @change="load">含已停用</el-checkbox>
      <el-button link type="primary" size="small" @click="load">刷新</el-button>
      <span class="count">共 {{ rows.length }} 条</span>
    </div>

    <el-table :data="rows" size="small" border v-loading="loading">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="模板名称" min-width="180" show-overflow-tooltip />
      <el-table-column label="类别" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.category === 'AGREED' ? 'danger' : 'success'">
            {{ categoryName(row.category) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="作用范围" width="150">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ scopeName(row.scope) }}</el-tag>
          <span v-if="row.scope === 'DEPT'" class="sub">{{ row.dept_name }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="owner_name" label="归属人" width="110" />
      <el-table-column prop="line_count" label="明细行" width="70" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
            {{ row.enabled ? '启用中' : '已停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="preview(row)">明细</el-button>
          <el-button v-if="row.editable" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.editable && row.enabled" link type="warning" size="small"
                     @click="setEnabled(row, false)">停用</el-button>
          <el-button v-if="row.editable && !row.enabled" link type="success" size="small"
                     @click="setEnabled(row, true)">启用</el-button>
          <el-button v-if="row.editable" link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!rows.length && !loading" description="当前筛选下没有模板" :image-size="60" />

    <!-- ===== 新增/编辑 ===== -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="880px">
      <el-form :model="form" label-width="90px" size="small">
        <el-form-item label="模板名称" required>
          <el-input v-model="form.name" maxlength="64" show-word-limit style="width: 320px"
                    placeholder="如：上呼吸道感染·成人常用" />
        </el-form-item>
        <el-form-item label="模板类别" required>
          <el-radio-group v-model="form.category" :disabled="editingId !== null">
            <el-radio value="RX">处方模板（可套用后再改）</el-radio>
            <el-radio value="AGREED">协定处方（固定组合，不可改明细）</el-radio>
          </el-radio-group>
          <div v-if="editingId !== null" class="sub">类别建档后不可改：改类别等于换一张模板，请另建。</div>
        </el-form-item>
        <el-form-item label="作用范围" required>
          <el-select v-model="form.scope" style="width: 160px">
            <el-option v-for="s in SCOPES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
          <el-select v-if="form.scope === 'DEPT'" v-model="form.deptId" clearable filterable
                     placeholder="留空＝本人所在科室" style="width: 200px; margin-left: 8px">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <span class="sub">全院模板仅系统管理员可建可改。</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="255" style="width: 420px"
                    placeholder="如：药事委员会 2026 年第一版" />
        </el-form-item>

        <el-form-item label="模板明细" required>
          <div style="width: 100%">
            <el-alert v-if="linesLocked" type="warning" :closable="false" show-icon
                      title="协定处方的明细由药事委员会固定，不可就地修改"
                      description="需要调整请先停用本模板，再按新组合另建一张。这样已按旧版开出的处方仍能追溯到当时的版本。" />
            <div v-else class="line-add">
              <el-select v-model="pickType" size="small" style="width: 100px">
                <el-option v-for="t in ORDER_TYPES" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
              <el-select v-model="pickItemId" filterable remote clearable :remote-method="searchItems"
                         :loading="searching" size="small" style="width: 260px" placeholder="输入名称检索">
                <el-option v-for="o in itemOptions" :key="o.id" :label="itemLabel(o)" :value="o.id" />
              </el-select>
              <el-button type="primary" size="small" :disabled="!pickItemId" @click="addLine">加入明细</el-button>
            </div>

            <el-table :data="form.lines" size="small" border style="margin-top: 8px">
              <el-table-column label="类型" width="70">
                <template #default="{ row }">{{ typeName(row.orderType) }}</template>
              </el-table-column>
              <el-table-column prop="itemName" label="项目" min-width="160" show-overflow-tooltip />
              <el-table-column label="数量" width="90">
                <template #default="{ row }">
                  <el-input-number v-model="row.qty" :min="1" :max="9999" :controls="false"
                                   size="small" :disabled="linesLocked" style="width: 70px" />
                </template>
              </el-table-column>
              <el-table-column label="用法" width="110">
                <template #default="{ row }">
                  <el-input v-model="row.usageRoute" size="small" :disabled="linesLocked || row.orderType !== 'DRUG'" />
                </template>
              </el-table-column>
              <el-table-column label="频次" width="100">
                <template #default="{ row }">
                  <el-input v-model="row.frequency" size="small" :disabled="linesLocked || row.orderType !== 'DRUG'" />
                </template>
              </el-table-column>
              <el-table-column label="单次量" width="110">
                <template #default="{ row }">
                  <el-input v-model="row.dosePerTime" size="small" :disabled="linesLocked || row.orderType !== 'DRUG'" />
                </template>
              </el-table-column>
              <el-table-column label="天数" width="90">
                <template #default="{ row }">
                  <el-input-number v-model="row.days" :min="1" :max="999" :controls="false" size="small"
                                   :disabled="linesLocked || row.orderType !== 'DRUG'" style="width: 70px" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="70">
                <template #default="{ $index }">
                  <el-button link type="danger" size="small" :disabled="linesLocked"
                             @click="form.lines.splice($index, 1)">移除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="sub">
              明细只是「开单表单的预填值」。医生套用后仍要点开单，届时过敏、重复用药、抗菌药分级、
              CDSS、停用药与库存等校验一条不少。
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- ===== 明细查看 ===== -->
    <el-dialog v-model="previewVisible" :title="`模板明细：${previewName}`" width="820px">
      <el-alert v-if="previewLocked" type="warning" :closable="false" show-icon
                title="协定处方：套用时整组带入，医生不可逐行增删改" />
      <el-table :data="previewLines" size="small" border style="margin-top: 8px">
        <el-table-column label="类型" width="70">
          <template #default="{ row }">{{ typeName(row.orderType) }}</template>
        </el-table-column>
        <el-table-column label="项目" min-width="180">
          <template #default="{ row }">
            {{ row.itemName }}
            <el-tag v-if="row.itemExists === false" type="danger" size="small">项目已不存在</el-tag>
            <el-tag v-else-if="row.itemEnabled === false" type="warning" size="small">已停用</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="spec" label="规格" width="130" show-overflow-tooltip />
        <el-table-column prop="qty" label="数量" width="60" />
        <el-table-column prop="usageRoute" label="用法" width="80" />
        <el-table-column prop="frequency" label="频次" width="80" />
        <el-table-column prop="dosePerTime" label="单次量" width="90" />
        <el-table-column prop="days" label="天数" width="60" />
        <el-table-column prop="stock" label="库存" width="70" />
      </el-table>
      <template #footer><el-button @click="previewVisible = false">关闭</el-button></template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
/**
 * v44 车道F：处方模板与协定处方维护页（技术偏离表 999★ / 1000★）。
 *
 * 本页只维护模板。医生站「套用模板」的入口在门诊医生站开单区（合版时统一加），
 * 取数走同一组端点：GET /outpatient/rx-templates（列可见模板）
 * + GET /outpatient/rx-templates/{id}/lines（取明细，返回体字段名与开单行逐字段对齐，拿到即可用）。
 *
 * 纪律：套用只是填充开单表单，绝不绕过任何既有开单校验——本页与该组端点都不开单。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const CATEGORIES = [
  { value: 'RX', label: '处方模板' },
  { value: 'AGREED', label: '协定处方' },
]
const SCOPES = [
  { value: 'PERSONAL', label: '个人' },
  { value: 'DEPT', label: '科室' },
  { value: 'HOSPITAL', label: '全院' },
]
/** 与 outp_order.order_type / md_charge_item.category 同域 */
const ORDER_TYPES = [
  { value: 'DRUG', label: '药品' },
  { value: 'LAB', label: '检验' },
  { value: 'EXAM', label: '检查' },
  { value: 'TREAT', label: '治疗' },
]

type Dept = { id: number; name: string }
type MdItem = { id: number; name: string; spec?: string | null; unit?: string; price?: number }
type TplRow = Record<string, unknown> & {
  id: number; name: string; category: string; scope: string; enabled: boolean; editable: boolean
}
/** 明细行：前七个字段与后端 OrderLine / rx_template_line 逐字段同名 */
type Line = {
  orderType: string
  itemId: number
  qty: number
  usageRoute: string | null
  frequency: string | null
  dosePerTime: string | null
  days: number | null
  itemName?: string
  spec?: string | null
}
type Form = {
  name: string; category: string; scope: string; deptId: number | null
  remark: string; lines: Line[]
}

const rows = ref<TplRow[]>([])
const depts = ref<Dept[]>([])
const loading = ref(false)
const saving = ref(false)
const searching = ref(false)
const filterCategory = ref<string | null>(null)
const keyword = ref('')
const includeDisabled = ref(false)

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<Form>(blank())
const pickType = ref('DRUG')
const pickItemId = ref<number | null>(null)
const itemOptions = ref<MdItem[]>([])

const previewVisible = ref(false)
const previewName = ref('')
const previewLocked = ref(false)
const previewLines = ref<Record<string, unknown>[]>([])

/** 协定处方的明细不可改（后端 4064）：编辑既有协定处方时整块置灰；新建时仍要能录入首版 */
const linesLocked = computed(() => form.category === 'AGREED' && editingId.value !== null)
const dialogTitle = computed(() =>
  editingId.value === null ? '新增模板' : `编辑模板 #${editingId.value}`)

function blank(): Form {
  return { name: '', category: 'RX', scope: 'PERSONAL', deptId: null, remark: '', lines: [] }
}

function categoryName(v: unknown) {
  return CATEGORIES.find((c) => c.value === String(v))?.label ?? String(v ?? '')
}
function scopeName(v: unknown) {
  return SCOPES.find((s) => s.value === String(v))?.label ?? String(v ?? '')
}
function typeName(v: unknown) {
  return ORDER_TYPES.find((t) => t.value === String(v))?.label ?? String(v ?? '')
}
function itemLabel(o: MdItem) {
  return o.spec ? `${o.name}（${o.spec}）` : o.name
}

async function load() {
  loading.value = true
  try {
    const params: Record<string, unknown> = { includeDisabled: includeDisabled.value }
    if (filterCategory.value) params.category = filterCategory.value
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    rows.value = (await client.get('/outpatient/rx-templates', { params })).data.data
  } finally {
    loading.value = false
  }
}

async function searchItems(kw: string) {
  if (!kw) { itemOptions.value = []; return }
  searching.value = true
  try {
    const url = pickType.value === 'DRUG' ? '/masterdata/drugs' : '/masterdata/charge-items'
    const params: Record<string, unknown> = { keyword: kw }
    if (pickType.value !== 'DRUG') params.category = pickType.value
    itemOptions.value = (await client.get(url, { params })).data.data
  } finally {
    searching.value = false
  }
}

function addLine() {
  const item = itemOptions.value.find((o) => o.id === pickItemId.value)
  if (!item) return
  const drug = pickType.value === 'DRUG'
  form.lines.push({
    orderType: pickType.value,
    itemId: item.id,
    qty: 1,
    usageRoute: drug ? '口服' : null,
    frequency: drug ? 'bid' : null,
    dosePerTime: drug ? '1' : null,
    days: drug ? 3 : null,
    itemName: item.name,
    spec: item.spec ?? null,
  })
  pickItemId.value = null
  itemOptions.value = []
}

function openAdd() {
  Object.assign(form, blank())
  editingId.value = null
  dialogVisible.value = true
}

async function openEdit(row: TplRow) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    category: row.category,
    scope: row.scope,
    deptId: row.dept_id ? Number(row.dept_id) : null,
    remark: (row.remark as string) ?? '',
    lines: [],
  })
  dialogVisible.value = true
  // forEdit=true：允许查看已停用模板的明细（否则停用后连改都改不了）
  const data = (await client.get(`/outpatient/rx-templates/${row.id}/lines`,
    { params: { forEdit: true } })).data.data as Record<string, unknown>[]
  form.lines = data.map((d) => ({
    orderType: String(d.orderType),
    itemId: Number(d.itemId),
    qty: Number(d.qty ?? 1),
    usageRoute: (d.usageRoute as string) ?? null,
    frequency: (d.frequency as string) ?? null,
    dosePerTime: (d.dosePerTime as string) ?? null,
    days: d.days == null ? null : Number(d.days),
    itemName: String(d.itemName ?? ''),
    spec: (d.spec as string) ?? null,
  }))
}

async function save() {
  if (!form.name.trim()) { ElMessage.warning('模板名称必填'); return }
  if (!linesLocked.value && !form.lines.length) { ElMessage.warning('模板明细至少要有一行'); return }
  saving.value = true
  try {
    const body: Record<string, unknown> = {
      name: form.name.trim(),
      scope: form.scope,
      deptId: form.scope === 'DEPT' ? form.deptId : null,
      category: form.category,
      remark: form.remark.trim() || null,
      // 协定处方编辑时不回传 lines（后端会以 4064 拒绝改明细）
      lines: linesLocked.value ? null : form.lines.map((l, i) => ({ ...l, sortNo: i })),
    }
    if (editingId.value === null) {
      await client.post('/outpatient/rx-templates', body)
    } else {
      await client.put(`/outpatient/rx-templates/${editingId.value}`, body)
    }
    ElMessage.success('模板已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function setEnabled(row: TplRow, enabled: boolean) {
  await client.put(`/outpatient/rx-templates/${row.id}/${enabled ? 'enable' : 'disable'}`)
  ElMessage.success(enabled ? '模板已启用' : '模板已停用')
  await load()
}

async function remove(row: TplRow) {
  try {
    await ElMessageBox.confirm(
      `删除后该模板与其明细将不可恢复。日常请优先用「停用」——停用会保留明细，历史处方仍能解释当时照的是哪张模板。确定删除《${row.name}》？`,
      '删除模板', { type: 'warning' })
  } catch {
    return   // 用户取消
  }
  await client.delete(`/outpatient/rx-templates/${row.id}`)
  ElMessage.success('模板已删除')
  await load()
}

async function preview(row: TplRow) {
  previewName.value = row.name
  previewLocked.value = row.category === 'AGREED'
  previewLines.value = (await client.get(`/outpatient/rx-templates/${row.id}/lines`,
    { params: { forEdit: !row.enabled } })).data.data
  previewVisible.value = true
}

onMounted(async () => {
  depts.value = (await client.get('/system/depts')).data.data
  await load()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 12px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; display: block; margin-top: 4px; max-width: 980px; line-height: 1.7; }
.filters { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; }
.count { color: var(--el-text-color-secondary); font-size: 12px; }
.sub { color: var(--el-text-color-secondary); font-size: 12px; margin-left: 8px; line-height: 1.6; }
.line-add { display: flex; gap: 8px; align-items: center; }
</style>
