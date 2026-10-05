<template>
  <el-card>
    <div class="toolbar">
      <div>
        <h3 style="margin:0">检验流向规则</h3>
        <span class="hint">
          检验申请开单时按（项目、标本类型、开单科室）匹配规则确定执行科室；无规则命中则回落收费项目字典里配置的执行科室。
        </span>
      </div>
      <div v-if="canWrite">
        <el-button type="primary" size="small" @click="openAdd">新增规则</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px" title="匹配口径与注意事项">
      <template #default>
        <div>匹配顺序：具体度（项目 &gt; 标本类型 &gt; 开单科室）→ 优先级小者先 → 建立顺序；下表即按此顺序排列，排在前面的先匹配。</div>
        <div>落值即快照：执行科室在开单那一刻写入医嘱，之后修改或停用规则不回改已开出的申请。</div>
        <div>标本类型条件取自标本类型字典；医生手工录入的字典外值不命中规则、回落收费项目字典（匹配忽略大小写与空白，含全角空格）；留空的键表示不限。</div>
        <div>总开关为系统配置 lab.route.enabled：关闭后开单不查规则，执行科室只按收费项目字典带出。</div>
      </template>
    </el-alert>

    <div class="filters">
      <el-checkbox v-model="includeDisabled" size="small" @change="load">含停用</el-checkbox>
      <el-button size="small" style="margin-left:12px" @click="load">刷新</el-button>
    </div>

    <el-table :data="rows" size="small" border stripe v-loading="loading">
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="项目" min-width="180">
        <template #default="{ row }">
          <span v-if="row.chargeItemId">{{ row.chargeItemCode }} {{ row.chargeItemName }}</span>
          <span v-else class="muted">全部检验项目</span>
        </template>
      </el-table-column>
      <el-table-column label="标本类型" width="110">
        <template #default="{ row }">
          <span v-if="row.specimenType">{{ row.specimenType }}</span>
          <span v-else class="muted">不限</span>
        </template>
      </el-table-column>
      <el-table-column label="开单科室" width="120">
        <template #default="{ row }">
          <span v-if="row.orderDeptId">{{ row.orderDeptName }}</span>
          <span v-else class="muted">不限</span>
        </template>
      </el-table-column>
      <el-table-column prop="execDeptName" label="执行科室" width="120" />
      <el-table-column prop="priority" label="优先级" width="80" align="center" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="170" />
      <el-table-column v-if="canWrite" label="操作" width="160">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link :type="row.enabled ? 'warning' : 'success'" size="small" @click="toggle(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-card shadow="never" class="trial">
      <template #header>
        <div>
          <strong>规则试算</strong>
          <span class="hint" style="display:inline; margin-left:8px">
            按当前规则与收费项目字典算一次执行科室（与开单落值走同一套匹配，不写任何数据）
          </span>
        </div>
      </template>
      <el-form inline size="small" :model="trial">
        <el-form-item label="项目" required>
          <el-select v-model="trial.chargeItemId" filterable remote clearable :remote-method="searchTrialItems"
                     :loading="trialItemLoading" placeholder="输入名称检索检验项目" style="width:260px">
            <el-option v-for="c in trialItemOptions" :key="c.id" :label="`${c.code} ${c.name}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="标本类型">
          <!-- v78：试算允许手填字典外值（allow-create），用来演示"不命中、回落字典"；规则表单那边不允许自造 -->
          <el-select v-model="trial.specimenType" filterable allow-create default-first-option clearable
                     placeholder="选字典项或手填" style="width:160px">
            <el-option v-for="s in specimenTypes" :key="s.id" :label="s.name" :value="s.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="开单科室">
          <el-select v-model="trial.orderDeptId" filterable clearable placeholder="不限" style="width:160px">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="trialLoading" @click="runTrial">试算</el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="trialResult" :type="trialResult.source === 'NONE' ? 'warning' : 'success'" :closable="false"
                show-icon :title="trialText" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑规则' : '新增规则'" width="560px">
      <el-form :model="form" label-width="90px" size="small">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="64" placeholder="如 急诊血常规→急诊检验室" />
        </el-form-item>
        <el-form-item label="项目">
          <el-select v-model="form.chargeItemId" filterable remote clearable :remote-method="searchFormItems"
                     :loading="formItemLoading" placeholder="输入名称检索检验项目；留空=全部检验项目" style="width:100%">
            <el-option v-for="c in formItemOptions" :key="c.id" :label="`${c.code} ${c.name}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="标本类型">
          <!-- v78：只能选字典启用项，不允许自造（后端 5916/5917 兜底）；编辑时若行里存的是已停用项，先按名称种一条让它能显示 -->
          <el-select v-model="form.specimenType" filterable clearable placeholder="选字典项；留空=不限" style="width:100%">
            <el-option v-for="s in formSpecimenOptions" :key="s.id" :label="s.name" :value="s.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="开单科室">
          <el-select v-model="form.orderDeptId" filterable clearable placeholder="留空=不限" style="width:100%">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行科室" required>
          <el-select v-model="form.execDeptId" filterable placeholder="必选" style="width:100%">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="1" :max="9999" :step="10" />
          <span class="hint" style="display:inline; margin-left:8px">同具体度下数值小者先匹配，默认 100</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="255" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'

/** 契约见规划 v77「接口契约」：GET /masterdata/lab-route-rules 的行，后端已按匹配顺序排好，前端不再排 */
type Rule = {
  id: number
  name: string
  itemCategory: string
  chargeItemId: number | null
  chargeItemCode: string | null
  chargeItemName: string | null
  specimenType: string | null
  orderDeptId: number | null
  orderDeptName: string | null
  execDeptId: number
  execDeptName: string
  priority: number
  enabled: boolean
  remark: string | null
  updatedAt: string
}
type Dept = { id: number; name: string; enabled?: boolean }
type SpecimenType = { id: number; name: string }
/** /masterdata/charge-items 返回 ChargeItem 实体（id/code/name/category/...），这里只用到四个键 */
type ItemOption = { id: number; code: string; name: string; category: string }
type Resolve = {
  execDeptId: number | null
  execDeptName: string | null
  source: 'RULE' | 'ITEM' | 'NONE'
  routeEnabled?: boolean | null
  ruleId: number | null
  ruleName: string | null
}

const auth = useAuthStore()
/** 写操作后端 hasAnyRole('ADMIN','TECHNICIAN')——检验科自己配自己的分流；前端只是藏按钮，不是安全边界 */
const canWrite = computed(() => {
  const roles = auth.user?.roles ?? []
  return roles.includes('ADMIN') || roles.includes('TECHNICIAN')
})

const rows = ref<Rule[]>([])
const loading = ref(false)
const includeDisabled = ref(false)
const depts = ref<Dept[]>([])
/** v78：标本类型字典启用项（契约 GET /masterdata/specimen-types 默认只返启用项）；规则表单与试算区共用，值取名称字符串 */
const specimenTypes = ref<SpecimenType[]>([])

const dialogVisible = ref(false)
const editing = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive({
  name: '',
  chargeItemId: null as number | null,
  specimenType: '',
  orderDeptId: null as number | null,
  execDeptId: null as number | null,
  priority: 100,
  remark: '',
})
/**
 * 规则表单下拉选项：字典启用项，外加「编辑时行里已存、但已不在启用项里」的那个值（种一条 id=0 的影子项）——
 * 否则 el-select 只能显示裸字符串、看不出它是停用项；保存时后端照旧按 5917 拒绝，这里不替它放行。
 */
const formSpecimenOptions = computed<SpecimenType[]>(() => {
  const cur = form.specimenType
  if (!cur || specimenTypes.value.some((s) => s.name === cur)) return specimenTypes.value
  return [{ id: 0, name: cur }, ...specimenTypes.value]
})
// 表单与试算区各自一份选项：共用一份会在另一边检索时把已选项挤出下拉，el-select 就只剩一个裸 id 可显示
const formItemOptions = ref<ItemOption[]>([])
const formItemLoading = ref(false)
const trialItemOptions = ref<ItemOption[]>([])
const trialItemLoading = ref(false)

const trial = reactive({ chargeItemId: null as number | null, specimenType: '', orderDeptId: null as number | null })
const trialLoading = ref(false)
const trialResult = ref<Resolve | null>(null)
const trialText = computed(() => {
  const r = trialResult.value
  if (!r) return ''
  if (r.routeEnabled === false) {
    return r.source === 'ITEM'
      ? `流向规则总开关已关闭（lab.route.enabled=0），未查规则，按收费项目字典 → ${r.execDeptName ?? ''}`
      : '流向规则总开关已关闭（lab.route.enabled=0），未查规则，且该项目未配置执行科室，开单后执行科室为空（申请单印 —）'
  }
  if (r.source === 'RULE') return `命中规则「${r.ruleName ?? ''}」→ ${r.execDeptName ?? ''}`
  if (r.source === 'ITEM') return `无规则命中，按收费项目字典 → ${r.execDeptName ?? ''}`
  return '无规则命中且该项目未配置执行科室，开单后执行科室为空（申请单印 —）'
})

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/masterdata/lab-route-rules', {
      params: { includeDisabled: includeDisabled.value },
    })).data.data
  } finally {
    loading.value = false
  }
}

/** 科室下拉沿用系统管理的科室接口（所有登录用户可读）；只给启用的科室选，已停用的后端也会拒（5911/5914） */
async function loadDepts() {
  const all = (await client.get('/system/depts')).data.data as Dept[]
  depts.value = all.filter((d) => d.enabled !== false)
}

async function loadSpecimenTypes() {
  specimenTypes.value = (await client.get('/masterdata/specimen-types')).data.data
}

/**
 * 项目远程检索：规则键只认 category=LAB 的收费项目（后端 5912 兜底），前端过滤只留检验类。
 * 同时传 category=LAB 让后端的 top20 不被检查/治疗项目占满。
 */
async function searchLabItems(kw: string): Promise<ItemOption[]> {
  const list = (await client.get('/masterdata/charge-items', {
    params: { keyword: kw ?? '', category: 'LAB' },
  })).data.data as ItemOption[]
  return list.filter((c) => c.category === 'LAB')
}

async function searchFormItems(kw: string) {
  formItemLoading.value = true
  try {
    formItemOptions.value = await searchLabItems(kw)
  } finally {
    formItemLoading.value = false
  }
}

async function searchTrialItems(kw: string) {
  trialItemLoading.value = true
  try {
    trialItemOptions.value = await searchLabItems(kw)
  } finally {
    trialItemLoading.value = false
  }
}

function openAdd() {
  Object.assign(form, {
    name: '', chargeItemId: null, specimenType: '', orderDeptId: null, execDeptId: null, priority: 100, remark: '',
  })
  formItemOptions.value = []
  editing.value = false
  editingId.value = null
  dialogVisible.value = true
}

function openEdit(row: Rule) {
  Object.assign(form, {
    name: row.name,
    chargeItemId: row.chargeItemId,
    specimenType: row.specimenType ?? '',
    orderDeptId: row.orderDeptId,
    execDeptId: row.execDeptId,
    priority: row.priority,
    remark: row.remark ?? '',
  })
  // 远程下拉的已选项必须在选项里，否则只显示 id：先用行里的编码/名称种一条
  formItemOptions.value = row.chargeItemId
    ? [{ id: row.chargeItemId, code: row.chargeItemCode ?? '', name: row.chargeItemName ?? '', category: 'LAB' }]
    : []
  editing.value = true
  editingId.value = row.id
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('名称必填')
    return
  }
  if (form.execDeptId == null) {
    ElMessage.warning('执行科室必选')
    return
  }
  saving.value = true
  try {
    // 空值一律传 null（留空=不限 / 全部检验项目；el-select 清空后可能是 ''/undefined，一并归 null）；5911–5914 由拦截器弹红字
    const body = {
      name: form.name.trim(),
      chargeItemId: form.chargeItemId || null,
      specimenType: String(form.specimenType ?? '').trim() || null,   // el-select 清空后可能是 undefined
      orderDeptId: form.orderDeptId || null,
      execDeptId: form.execDeptId,
      priority: form.priority ?? 100,
      remark: form.remark.trim() || null,
    }
    if (editing.value && editingId.value != null) await client.put(`/masterdata/lab-route-rules/${editingId.value}`, body)
    else await client.post('/masterdata/lab-route-rules', body)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggle(row: Rule) {
  const target = !row.enabled
  await client.put(`/masterdata/lab-route-rules/${row.id}/enabled`, null, { params: { enabled: target } })
  ElMessage.success(target ? '已启用' : '已停用')
  await load()
}

async function remove(row: Rule) {
  const ok = await ElMessageBox.confirm(
    `删除规则「${row.name}」？已按此规则开出的申请不受影响。`,
    '确认删除', { type: 'warning' },
  ).catch(() => null)
  if (!ok) return
  await client.delete(`/masterdata/lab-route-rules/${row.id}`)
  ElMessage.success('已删除')
  await load()
}

async function runTrial() {
  if (!trial.chargeItemId) {
    ElMessage.warning('请先选择项目')
    return
  }
  trialLoading.value = true
  try {
    trialResult.value = (await client.get('/masterdata/lab-route-rules/resolve', {
      params: {
        chargeItemId: trial.chargeItemId,
        specimenType: String(trial.specimenType ?? '').trim() || undefined,
        orderDeptId: trial.orderDeptId || undefined,
      },
    })).data.data
  } finally {
    trialLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadDepts(), loadSpecimenTypes(), load()])
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; display: block; margin-top: 4px; }
.muted { color: var(--el-text-color-placeholder); }
.filters { display: flex; align-items: center; margin-bottom: 12px; }
.trial { margin-top: 16px; }
</style>
