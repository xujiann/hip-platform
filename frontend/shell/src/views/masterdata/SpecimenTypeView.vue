<template>
  <el-card>
    <div class="toolbar">
      <div>
        <h3 style="margin:0">标本类型字典</h3>
        <span class="hint">
          医生站开单的标本类型与流向规则的标本条件取自本字典；医生可手工录入字典外值，但字典外值的申请不参与流向匹配、按收费项目字典带出执行科室。
        </span>
      </div>
      <div v-if="canWrite">
        <el-button type="primary" size="small" @click="openAdd">新增标本类型</el-button>
      </div>
    </div>

    <div class="filters">
      <el-button size="small" @click="load">刷新</el-button>
    </div>

    <el-table :data="rows" size="small" border stripe v-loading="loading">
      <el-table-column prop="code" label="编码" width="100" />
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="sortNo" label="排序" width="70" align="center" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="引用规则数" width="100" align="center">
        <template #default="{ row }">
          <span :class="{ muted: !Number(row.ruleCount) }">{{ Number(row.ruleCount ?? 0) }}</span>
        </template>
      </el-table-column>
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

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑标本类型' : '新增标本类型'" width="460px">
      <el-form :model="form" label-width="80px" size="small">
        <el-form-item label="编码" required>
          <el-input v-model="form.code" :disabled="editing" maxlength="16" placeholder="如 SER / UR，建档后不可改" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="32" placeholder="如 血清 / 尿液" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortNo" :min="0" :max="9999" :controls="false" style="width:120px" />
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

/** 契约见规划 v78「接口契约」：GET /masterdata/specimen-types?all=true 的行；ruleCount 由后端随行算好（启用规则里标本键等于本行名称规范化值的条数） */
type Row = { id: number; code: string; name: string; sortNo: number; enabled: boolean; ruleCount: number }

const auth = useAuthStore()
/** 写操作后端 hasAnyRole('ADMIN','TECHNICIAN')——与流向规则同口径；前端只是藏按钮，不是安全边界 */
const canWrite = computed(() => {
  const roles = auth.user?.roles ?? []
  return roles.includes('ADMIN') || roles.includes('TECHNICIAN')
})

const rows = ref<Row[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editing = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({ code: '', name: '', sortNo: 0 })

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/masterdata/specimen-types', { params: { all: true } })).data.data
  } finally {
    loading.value = false
  }
}

function openAdd() {
  Object.assign(form, { code: '', name: '', sortNo: 0 })
  editing.value = false
  editingId.value = null
  dialogVisible.value = true
}

function openEdit(row: Row) {
  Object.assign(form, { code: row.code, name: row.name, sortNo: row.sortNo ?? 0 })
  editing.value = true
  editingId.value = row.id
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim() || (!editing.value && !form.code.trim())) {
    ElMessage.warning('编码与名称必填')
    return
  }
  saving.value = true
  try {
    // 编码建档后不可改：编辑只送 {name,sortNo}；5915（编码/名称已存在）由拦截器弹红字
    if (editing.value && editingId.value != null) {
      await client.put(`/masterdata/specimen-types/${editingId.value}`, { name: form.name.trim(), sortNo: form.sortNo ?? 0 })
    } else {
      await client.post('/masterdata/specimen-types', { code: form.code.trim(), name: form.name.trim(), sortNo: form.sortNo ?? 0 })
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggle(row: Row) {
  const target = !row.enabled
  if (!target) {
    const n = Number(row.ruleCount ?? 0)
    const ok = await ElMessageBox.confirm(
      n > 0
        ? `停用标本类型「${row.name}」？仍有 ${n} 条启用规则引用，后端会拒绝；请先停用或改掉这些规则。`
        : `停用标本类型「${row.name}」？停用后医生站下拉不再列出，已开出的申请不受影响。`,
      '确认停用', { type: 'warning' },
    ).catch(() => null)
    if (!ok) return
  }
  // 5917（仍被启用规则引用）由拦截器弹红字
  await client.put(`/masterdata/specimen-types/${row.id}/enabled`, null, { params: { enabled: target } })
  ElMessage.success(target ? '已启用' : '已停用')
  await load()
}

async function remove(row: Row) {
  const n = Number(row.ruleCount ?? 0)
  const ok = await ElMessageBox.confirm(
    n > 0
      ? `删除标本类型「${row.name}」？仍有 ${n} 条启用规则引用，后端会拒绝；请先停用或改掉这些规则。`
      : `删除标本类型「${row.name}」？已开出的申请不受影响。`,
    '确认删除', { type: 'warning' },
  ).catch(() => null)
  if (!ok) return
  await client.delete(`/masterdata/specimen-types/${row.id}`)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; display: block; margin-top: 4px; }
.muted { color: var(--el-text-color-placeholder); }
.filters { display: flex; align-items: center; margin-bottom: 12px; }
</style>
