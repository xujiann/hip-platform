<template>
  <el-card>
    <div class="toolbar">
      <div>
        <h3 style="margin:0">诊断字典（ICD-10）</h3>
        <span class="hint">
          门诊医生站、入院登记的诊断检索下拉取自本字典；停用的诊断不再出现在下拉里，已开出的历史诊断不受影响。
        </span>
      </div>
      <div v-if="isAdmin">
        <el-button size="small" @click="csvInput?.click()">CSV 批量导入</el-button>
        <el-button type="primary" size="small" @click="openAdd">新增诊断</el-button>
        <input ref="csvInput" type="file" accept=".csv,text/csv,text/plain" style="display: none"
               @change="importCsv" />
      </div>
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
              title="列说明与导入规则">
      <template #default>
        <div>列：编码（如 J06.900，建档后不可改）、名称、拼音（名称的拼音首字母，如 JXSHXDGR）、启用状态、更新时间。</div>
        <div>
          CSV 导入列：code,name,pinyin（UTF-8，首行表头可省略）。本系统不会推断拼音，CSV 必须自带；
          没有拼音的诊断无法按拼音检索。
        </div>
        <div>
          只要有一行格式不合法，整批都不会导入，页面会列出每个错误行号；全部合法时按编码覆盖更新，
          已停用的诊断只更新名称和拼音，不会被导入重新启用。
        </div>
        <div>全量国临版编码需院方提供权威码表后通过 CSV 导入，本系统不预置。</div>
      </template>
    </el-alert>

    <div class="filters">
      <el-input v-model="keyword" size="small" clearable style="width:260px"
                placeholder="名称 / 编码前缀 / 拼音前缀" @keyup.enter="search" @clear="search" />
      <el-checkbox v-model="includeDisabled" size="small" style="margin-left:12px" @change="search">
        含停用
      </el-checkbox>
      <el-button size="small" style="margin-left:12px" @click="search">查询</el-button>
    </div>

    <el-table :data="rows" size="small" border stripe v-loading="loading">
      <el-table-column prop="code" label="编码" width="130" />
      <el-table-column prop="name" label="名称" min-width="200" />
      <el-table-column label="拼音" width="160">
        <template #default="{ row }">{{ row.pinyin || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="170" />
      <el-table-column v-if="isAdmin" label="操作" width="130">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link :type="row.enabled ? 'danger' : 'success'" size="small" @click="toggle(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pager" small background layout="total, prev, pager, next, sizes"
                   :total="total" v-model:current-page="page" v-model:page-size="size"
                   :page-sizes="[20, 50, 100]" @current-change="load" @size-change="search" />

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑诊断' : '新增诊断'" width="480px">
      <el-form :model="form" label-width="80px" size="small">
        <el-form-item label="编码" required>
          <el-input v-model="form.code" :disabled="editing" placeholder="如 J06.900，建档后不可改" />
        </el-form-item>
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="拼音" required>
          <el-input v-model="form.pinyin" placeholder="名称拼音首字母，如 JXSHXDGR" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- CSV 5901：整批被拒，逐行列出原因。纯文本渲染（reason 会回显用户提交的内容，不能走 HTML） -->
    <el-dialog v-model="errDialog" :title="`导入失败：${errCount} 行格式不合法，整批未导入`" width="640px">
      <el-table :data="errRows" size="small" border max-height="360">
        <el-table-column prop="line" label="行号" width="80" />
        <el-table-column prop="reason" label="原因" />
      </el-table>
      <p v-if="errCount > errRows.length" class="hint">
        仅显示前 {{ errRows.length }} 条，共 {{ errCount }} 条，修正后重新导入会继续提示余下的行。
      </p>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client, { type BizError } from '../../api/client'
import { useAuthStore } from '../../stores/auth'

type Row = { code: string; name: string; pinyin: string | null; enabled: boolean; updatedAt: string }
type ErrRow = { line: number; reason: string }

const auth = useAuthStore()
const isAdmin = computed(() => !!auth.user?.roles?.includes('ADMIN'))

const rows = ref<Row[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const includeDisabled = ref(false)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editing = ref(false)
const form = reactive({ code: '', name: '', pinyin: '' })
const csvInput = ref<HTMLInputElement>()
const errDialog = ref(false)
const errRows = ref<ErrRow[]>([])
const errCount = ref(0)

async function load() {
  loading.value = true
  try {
    const d = (await client.get('/masterdata/icd-dict', {
      params: {
        keyword: keyword.value.trim() || undefined,
        includeDisabled: includeDisabled.value,
        page: page.value,
        size: size.value,
      },
    })).data.data
    rows.value = d.records
    total.value = d.total
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  return load()
}

function openAdd() {
  Object.assign(form, { code: '', name: '', pinyin: '' })
  editing.value = false
  dialogVisible.value = true
}

function openEdit(row: Row) {
  Object.assign(form, { code: row.code, name: row.name, pinyin: row.pinyin ?? '' })
  editing.value = true
  dialogVisible.value = true
}

async function save() {
  if (!form.code.trim() || !form.name.trim() || !form.pinyin.trim()) {
    ElMessage.warning('编码、名称、拼音都必填')
    return
  }
  saving.value = true
  try {
    const body = { code: form.code.trim(), name: form.name.trim(), pinyin: form.pinyin.trim() }
    if (editing.value) await client.put(`/masterdata/icd-dict/${encodeURIComponent(body.code)}`, body)
    else await client.post('/masterdata/icd-dict', body)
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
    const ok = await ElMessageBox.confirm(
      `停用诊断「${row.code} ${row.name}」？停用后医生站检索不再出现，历史记录不受影响。`,
      '确认', { type: 'warning' },
    ).catch(() => null)
    if (!ok) return
  }
  await client.put(`/masterdata/icd-dict/${encodeURIComponent(row.code)}/enabled`, null, {
    params: { enabled: target },
  })
  ElMessage.success(target ? '已启用' : '已停用')
  await load()
}

async function importCsv(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const text = await file.text()
    const r = (await client.post('/masterdata/icd-dict/import', text, {
      headers: { 'Content-Type': 'text/plain; charset=UTF-8' },
      __silentCodes: [5901],
    })).data.data
    const kept = r.disabledKept > 0 ? `，其中 ${r.disabledKept} 条已停用、保持停用` : ''
    ElMessage.success(`导入 ${r.imported} 条（新增 ${r.created}，更新 ${r.updated}${kept}）`)
    await search()
  } catch (err) {
    const be = err as BizError
    if (be.bizCode === 5901) {
      const d = be.data as { errorCount: number; errors: ErrRow[] }
      errRows.value = d.errors
      errCount.value = d.errorCount
      errDialog.value = true
    }
    // 其余错误拦截器已弹红字
  } finally {
    input.value = ''
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; display: block; margin-top: 4px; }
.filters { display: flex; align-items: center; margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
