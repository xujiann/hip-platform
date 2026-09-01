<template>
  <el-card>
    <div class="toolbar">
      <div>
        <h3 style="margin:0">费用类别字典</h3>
        <span class="hint">
          财务口径的费用分类，供「费用分类报表」按类别汇总金额。收费项目与药品在各自目录页挂类。
        </span>
      </div>
      <el-button type="primary" size="small" @click="openAdd">新增类别</el-button>
    </div>

    <!-- 外部边界：本仓不预置任何医保国标码值，页面必须把这条说清楚，避免实施期误以为已对照 -->
    <el-alert type="warning" :closable="false" show-icon style="margin-bottom:12px"
              title="国标 / 医保费用类别码由实施期填写，本系统不预置">
      <template #default>
        下方「国标码 / 码表来源」两列**出厂为空**：医保费用类别码表随各地医保局版本发布，
        本系统无权威来源，预置即为伪造，而其消费场景（医保结算清单、病案首页上报）不容许猜测。
        请由院方或医保对照工具填入实际码值后再用于对外上报；院内报表不依赖这两列。
      </template>
    </el-alert>

    <el-table :data="rows" size="small" border stripe v-loading="loading">
      <el-table-column prop="code" label="类别码" width="110" />
      <el-table-column prop="name" label="类别名称" min-width="140" />
      <el-table-column prop="sort_no" label="排序" width="70" />
      <el-table-column label="挂靠项目" width="100">
        <template #default="{ row }">
          <span :class="{ muted: !Number(row.item_count) }">{{ Number(row.item_count ?? 0) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="国标码" width="120">
        <template #default="{ row }">{{ row.std_code || '—' }}</template>
      </el-table-column>
      <el-table-column label="码表来源" width="120">
        <template #default="{ row }">{{ row.std_system || '—' }}</template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑费用类别' : '新增费用类别'" width="520px">
      <el-form :model="form" label-width="110px" size="small">
        <el-form-item label="类别码" required>
          <el-input v-model="form.code" :disabled="!!form.id" placeholder="如 WM / EXAM，建档后不可改" />
        </el-form-item>
        <el-form-item label="类别名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortNo" :min="0" :max="9999" :controls="false" style="width:120px" />
        </el-form-item>
        <el-form-item label="国标码">
          <el-input v-model="form.stdCode" placeholder="留空；由实施期按当地医保码表填写" />
        </el-form-item>
        <el-form-item label="码表来源">
          <el-input v-model="form.stdSystem" placeholder="留空；如「XX省医保局 2024 版」" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <span class="hint" style="margin-left:10px">仍有项目挂靠时不允许停用</span>
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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

type Row = Record<string, unknown>
type Form = {
  id: number | null; code: string; name: string; sortNo: number
  stdCode: string; stdSystem: string; enabled: boolean
}

const rows = ref<Row[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const form = reactive<Form>(blank())

function blank(): Form {
  return { id: null, code: '', name: '', sortNo: 0, stdCode: '', stdSystem: '', enabled: true }
}

/** 挂靠数由后端随行返回（item_count）：前端不能用 /charge-items + /drugs 自己数——那是 top20 检索接口 */
async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/masterdata/fee-categories', { params: { all: true } })).data.data
  } finally {
    loading.value = false
  }
}

function openAdd() {
  Object.assign(form, blank())
  dialogVisible.value = true
}

function openEdit(row: Row) {
  Object.assign(form, {
    id: row.id as number,
    code: String(row.code ?? ''),
    name: String(row.name ?? ''),
    sortNo: Number(row.sort_no ?? 0),
    stdCode: String(row.std_code ?? ''),
    stdSystem: String(row.std_system ?? ''),
    enabled: row.enabled as boolean,
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('类别码与名称必填')
    return
  }
  saving.value = true
  try {
    const body = {
      code: form.code.trim(), name: form.name.trim(), sortNo: form.sortNo,
      enabled: form.enabled, stdCode: form.stdCode.trim(), stdSystem: form.stdSystem.trim(),
    }
    if (form.id) await client.put(`/masterdata/fee-categories/${form.id}`, body)
    else await client.post('/masterdata/fee-categories', body)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: Row) {
  const ok = await ElMessageBox.confirm(
    `删除费用类别「${row.name}」？仍有项目挂靠时会被拒绝。`, '确认', { type: 'warning' },
  ).catch(() => null)
  if (!ok) return
  await client.delete(`/masterdata/fee-categories/${row.id}`)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; display: block; margin-top: 4px; }
.muted { color: var(--el-text-color-placeholder); }
</style>
