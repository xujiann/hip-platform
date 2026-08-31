<template>
  <el-card>
    <div class="toolbar">
      <div>
        <h3 style="margin:0">检验参考区间 / 危急值阈值</h3>
        <span class="hint">结果缺标志时按项目+性别+年龄自动判 N/H/L/HH/LL；HH/LL 触发危急值闭环。当前确认时限 {{ deadline }} 分钟。</span>
      </div>
      <el-button type="primary" size="small" @click="openAdd">新增区间</el-button>
    </div>
    <el-table :data="rows" size="small" border>
      <el-table-column prop="item_code" label="项目代码" width="110" />
      <el-table-column prop="item_name" label="名称" width="110" />
      <el-table-column label="性别" width="70">
        <template #default="{ row }">{{ { M: '男', F: '女' }[row.sex as string] ?? '通用' }}</template>
      </el-table-column>
      <el-table-column label="参考区间" width="130">
        <template #default="{ row }">{{ fmt(row.ref_low) }} ~ {{ fmt(row.ref_high) }}</template>
      </el-table-column>
      <el-table-column label="危急下限(LL)" width="110"><template #default="{ row }">{{ fmt(row.crit_low) }}</template></el-table-column>
      <el-table-column label="危急上限(HH)" width="110"><template #default="{ row }">{{ fmt(row.crit_high) }}</template></el-table-column>
      <el-table-column prop="unit" label="单位" width="90" />
      <el-table-column label="启用" width="70">
        <template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑参考区间' : '新增参考区间'" width="560px">
      <el-form :model="form" label-width="120px" size="small">
        <el-form-item label="项目代码" required><el-input v-model="form.itemCode" :disabled="!!form.id" placeholder="与检验结果 item_code 对应" /></el-form-item>
        <el-form-item label="项目名称"><el-input v-model="form.itemName" /></el-form-item>
        <el-form-item label="性别">
          <el-select v-model="form.sex" clearable placeholder="通用">
            <el-option label="男" value="M" /><el-option label="女" value="F" />
          </el-select>
        </el-form-item>
        <el-form-item label="参考下限"><el-input-number v-model="form.refLow" :controls="false" :min="0" :max="999999" style="width:150px" /></el-form-item>
        <el-form-item label="参考上限"><el-input-number v-model="form.refHigh" :controls="false" :min="0" :max="999999" style="width:150px" /></el-form-item>
        <el-form-item label="危急下限(LL)"><el-input-number v-model="form.critLow" :controls="false" :min="0" :max="999999" style="width:150px" /></el-form-item>
        <el-form-item label="危急上限(HH)"><el-input-number v-model="form.critHigh" :controls="false" :min="0" :max="999999" style="width:150px" /></el-form-item>
        <el-form-item label="单位"><el-input v-model="form.unit" style="width:150px" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

type Form = {
  id: number | null; itemCode: string; itemName: string; sex: string | null
  refLow: number | null; refHigh: number | null; critLow: number | null; critHigh: number | null
  unit: string; enabled: boolean
}
const rows = ref<Record<string, unknown>[]>([])
const deadline = ref(10)
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive<Form>(blank())

function blank(): Form {
  return { id: null, itemCode: '', itemName: '', sex: null, refLow: null, refHigh: null, critLow: null, critHigh: null, unit: '', enabled: true }
}
function fmt(v: unknown) { return v === null || v === undefined ? '—' : v }

async function load() {
  rows.value = (await client.get('/lab-ref-ranges')).data.data
  deadline.value = (await client.get('/lab-ref-ranges/ack-deadline-minutes')).data.data
}

function openAdd() { Object.assign(form, blank()); dialogVisible.value = true }
function openEdit(row: Record<string, unknown>) {
  Object.assign(form, {
    id: row.id, itemCode: String(row.item_code), itemName: String(row.item_name ?? ''), sex: (row.sex as string) ?? null,
    refLow: row.ref_low as number, refHigh: row.ref_high as number, critLow: row.crit_low as number, critHigh: row.crit_high as number,
    unit: String(row.unit ?? ''), enabled: row.enabled as boolean,
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.itemCode.trim()) { ElMessage.warning('项目代码必填'); return }
  saving.value = true
  try {
    const body = { ...form }
    if (form.id) await client.put(`/lab-ref-ranges/${form.id}`, body)
    else await client.post('/lab-ref-ranges', body)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally { saving.value = false }
}

async function remove(row: Record<string, unknown>) {
  await ElMessageBox.confirm(`删除 ${row.item_code} 的参考区间？`, '确认', { type: 'warning' }).catch(() => null)
    .then(async (ok) => { if (ok) { await client.delete(`/lab-ref-ranges/${row.id}`); ElMessage.success('已删除'); await load() } })
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; display: block; margin-top: 4px; }
</style>
