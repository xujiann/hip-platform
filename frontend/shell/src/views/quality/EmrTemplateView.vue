<template>
  <el-card>
    <div class="toolbar">
      <div>
        <h3 style="margin:0">病历模板维护</h3>
        <span class="hint">
          模板按「类型 + 科室」发放：<b>科室模板只对该科室可见，科室留空即全院通用</b>（住院医生站与
          RIS 报告页的模板下拉都按此口径取数）。如需改动模板内容，请用「复制为新模板」另建并重新命名
          ——原地修改与停用尚未开放，已发放的模板会被医生直接套进病历，就地改写会让历史病历的来源无从追溯。
        </span>
      </div>
      <el-button type="primary" size="small" @click="openAdd">新增模板</el-button>
    </div>

    <div class="filters">
      <el-select v-model="filterType" clearable placeholder="全部类型" size="small" style="width: 160px" @change="load">
        <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-select v-model="filterDeptId" clearable filterable placeholder="全部科室" size="small" style="width: 180px">
        <el-option label="全院通用（科室留空）" :value="0" />
        <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-button link type="primary" size="small" @click="load">刷新</el-button>
      <span class="count">共 {{ rows.length }} 条</span>
    </div>

    <el-table :data="rows" size="small" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="模板名称" width="200" show-overflow-tooltip />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="TYPE_TAG[String(row.template_type)] ?? 'info'">
            {{ typeName(row.template_type) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="适用科室" width="140">
        <template #default="{ row }">
          <span v-if="row.dept_id">{{ deptName(row.dept_id) }}</span>
          <el-tag v-else size="small" type="warning">全院通用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="字数" width="70">
        <template #default="{ row }">{{ String(row.content ?? '').length }}</template>
      </el-table-column>
      <el-table-column prop="content" label="内容" show-overflow-tooltip />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="preview(row)">查看</el-button>
          <el-button link type="warning" size="small" @click="openCopy(row)">复制为新模板</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!rows.length" description="当前筛选下没有模板" :image-size="60" />

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="640px">
      <el-form :model="form" label-width="90px" size="small">
        <el-form-item label="模板名称" required>
          <el-input v-model="form.name" maxlength="64" show-word-limit placeholder="如：入院记录·呼吸内科" />
        </el-form-item>
        <el-form-item label="模板类型" required>
          <el-select v-model="form.templateType" style="width: 200px">
            <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="适用科室">
          <el-select v-model="form.deptId" clearable filterable placeholder="留空＝全院通用" style="width: 200px">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="模板内容" required>
          <el-input v-model="form.content" type="textarea" :rows="12" maxlength="4000" show-word-limit
                    placeholder="主诉：&#10;现病史：&#10;既往史：&#10;体格检查：&#10;辅助检查：&#10;初步诊断：" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="previewVisible" :title="`模板：${previewRow?.name ?? ''}`" width="640px">
      <pre class="preview">{{ previewRow?.content }}</pre>
      <template #footer><el-button @click="previewVisible = false">关闭</el-button></template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

/** 与 emr_template.template_type 一致（V116 起分类）：EMR 病历 / RIS 报告 / CONSENT 同意书 */
const TYPES = [
  { value: 'EMR', label: '病历模板' },
  { value: 'RIS', label: 'RIS 报告' },
  { value: 'CONSENT', label: '知情同意书' },
]
const TYPE_TAG: Record<string, 'success' | 'primary' | 'danger'> = {
  EMR: 'success', RIS: 'primary', CONSENT: 'danger',
}

type Dept = { id: number; name: string; type: string }
type Form = { name: string; templateType: string; deptId: number | null; content: string }

const all = ref<Record<string, unknown>[]>([])
const depts = ref<Dept[]>([])
const filterType = ref<string | null>('EMR')
const filterDeptId = ref<number | null>(null)
const dialogVisible = ref(false)
const dialogTitle = ref('新增模板')
const saving = ref(false)
const previewVisible = ref(false)
const previewRow = ref<Record<string, unknown> | null>(null)
const form = reactive<Form>(blank())

function blank(): Form {
  return { name: '', templateType: 'EMR', deptId: null, content: '' }
}

/**
 * 科室筛选刻意放在前端做「精确匹配」：GET /emr-templates 的 deptId 口径是「本科室 **或** 全院通用」
 * ——那是给消费方（医生站下拉）用的，维护页要能单独看清某科室到底有哪些自有模板，
 * 用同一个参数会把全院通用模板混进来，误导维护人重复建模板。
 */
const rows = computed(() => {
  // el-select 的 clearable 清空后给的是 undefined 而非 null，用 == null 一并兜住（否则清空筛选后表全空）
  if (filterDeptId.value == null) return all.value
  if (filterDeptId.value === 0) return all.value.filter((r) => !r.dept_id)
  return all.value.filter((r) => Number(r.dept_id) === filterDeptId.value)
})

function typeName(v: unknown) {
  return TYPES.find((t) => t.value === String(v))?.label ?? String(v ?? '')
}
function deptName(id: unknown) {
  return depts.value.find((d) => d.id === Number(id))?.name ?? `科室${id}`
}

async function load() {
  const params: Record<string, unknown> = {}
  if (filterType.value) params.type = filterType.value
  all.value = (await client.get('/emr-templates', { params })).data.data
}

function openAdd() {
  Object.assign(form, blank())
  form.templateType = filterType.value ?? 'EMR'
  dialogTitle.value = '新增模板'
  dialogVisible.value = true
}

function openCopy(row: Record<string, unknown>) {
  Object.assign(form, {
    name: `${row.name} 副本`,
    templateType: String(row.template_type ?? 'EMR'),
    deptId: row.dept_id ? Number(row.dept_id) : null,
    content: String(row.content ?? ''),
  })
  dialogTitle.value = `复制自 #${row.id}《${row.name}》`
  dialogVisible.value = true
}

function preview(row: Record<string, unknown>) {
  previewRow.value = row
  previewVisible.value = true
}

async function save() {
  if (!form.name.trim()) { ElMessage.warning('模板名称必填'); return }
  if (!form.content.trim()) { ElMessage.warning('模板内容必填'); return }
  saving.value = true
  try {
    await client.post('/emr-templates', {
      deptId: form.deptId, name: form.name.trim(), content: form.content, templateType: form.templateType,
    })
    ElMessage.success('模板已保存')
    dialogVisible.value = false
    // 把筛选切到刚保存的这一条上，否则"保存成功但列表里找不到"会让维护人重复建模板
    filterType.value = form.templateType
    filterDeptId.value = null
    await load()
  } finally { saving.value = false }
}

onMounted(async () => {
  depts.value = (await client.get('/system/depts')).data.data
  await load()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 12px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; display: block; margin-top: 4px; max-width: 900px; line-height: 1.6; }
.filters { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; }
.count { color: var(--el-text-color-secondary); font-size: 12px; }
.preview { white-space: pre-wrap; word-break: break-all; margin: 0; font-family: inherit; font-size: 13px; line-height: 1.7; }
</style>
