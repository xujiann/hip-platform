<template>
  <el-card>
    <div class="toolbar">
      <h3>收费项目</h3>
      <el-input v-model="keyword" placeholder="按名称搜索" clearable style="width: 240px"
                @keyup.enter="load" @clear="load" />
    </div>
    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="code" label="编码" width="90" />
      <el-table-column prop="name" label="项目名称" />
      <el-table-column label="类别" width="90">
        <template #default="{ row }">
          {{ { LAB: '检验', EXAM: '检查', TREAT: '治疗', MATERIAL: '材料' }[row.category as string] ?? row.category }}
        </template>
      </el-table-column>
      <!-- v42：财务口径的费用类别（与上面的业务类别并存，前者决定执行线、后者决定金额归集） -->
      <el-table-column label="费用类别" width="120">
        <template #default="{ row }">
          <span v-if="row.feeCategoryCode">{{ categoryName(row.feeCategoryCode as string) }}</span>
          <el-tag v-else type="warning" size="small">未挂类</el-tag>
        </template>
      </el-table-column>
      <!-- v74：执行科室（治疗单/导诊单"前往科室"的数据来源；未配置印"—"，这里同样显式标出） -->
      <el-table-column label="执行科室" width="120">
        <template #default="{ row }">
          <span v-if="row.execDeptId">{{ deptName(row.execDeptId as number) }}</span>
          <el-tag v-else type="info" size="small">未配置</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="自费" width="70">
        <template #default="{ row }">
          <el-tag v-if="row.selfPay" type="danger" size="small">自费</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="60" />
      <el-table-column prop="price" label="单价" width="90" />
      <el-table-column v-if="isAdmin" label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">维护属性</el-button>
        </template>
      </el-table-column>
    </el-table>
    <p class="hint">
      未挂费用类别的项目在「费用分类报表」里会进入「未分类」行——该行金额即主数据维护欠账的量化值。
      自费标记是自费知情同意 gate（emr.gate.consent.selfpay）的判定依据，未标记则该 gate 永远不会触发。
      执行科室是治疗单、检验/检查申请单与导诊单「前往科室」的来源，未配置的项目这些单据上印「—」。
      批量导入 CSV 列：code,name,category,unit,price[,fee_category_code][,self_pay 0/1][,exec_dept_code]，
      exec_dept_code 按科室编码解析，留空表示保持原值。
    </p>

    <el-dialog v-model="dialogVisible" title="维护费用类别 / 自费标记 / 执行科室" width="460px">
      <el-form label-width="100px" size="small">
        <el-form-item label="项目">{{ current.code }} {{ current.name }}</el-form-item>
        <el-form-item label="费用类别">
          <el-select v-model="formCategory" clearable placeholder="不挂类" style="width:220px">
            <el-option v-for="c in categories" :key="String(c.code)" :label="`${c.name}（${c.code}）`"
                       :value="String(c.code)" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行科室">
          <el-select v-model="formExecDept" clearable filterable placeholder="未配置" style="width:220px">
            <el-option v-for="d in depts" :key="Number(d.id)" :label="String(d.name)" :value="Number(d.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="自费项目">
          <el-switch v-model="formSelfPay" />
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
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'

type Row = Record<string, unknown>

const auth = useAuthStore()
const isAdmin = computed(() => !!auth.user?.roles?.includes('ADMIN'))

const keyword = ref('')
const records = ref<Row[]>([])
const categories = ref<Row[]>([])
const depts = ref<Row[]>([])
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const current = ref<Row>({})
const formCategory = ref('')
const formSelfPay = ref(false)
const formExecDept = ref<number | null>(null)
/** 打开弹窗时该项目已配的执行科室：用来判断"清空"是否需要显式发 clearExecDept */
let originalExecDept: number | null = null

function categoryName(code: string): string {
  const hit = categories.value.find((c) => c.code === code)
  return hit ? String(hit.name) : code
}

function deptName(id: number): string {
  const hit = depts.value.find((d) => Number(d.id) === id)
  return hit ? String(hit.name) : `科室#${id}`
}

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/masterdata/charge-items', { params: { keyword: keyword.value } })
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

async function loadCategories() {
  categories.value = (await client.get('/masterdata/fee-categories')).data.data
}

/** 科室下拉沿用系统管理的科室接口（所有登录用户可读）；只给启用的科室选，已停用的后端也会拒 */
async function loadDepts() {
  const all = (await client.get('/system/depts')).data.data as Row[]
  depts.value = all.filter((d) => d.enabled !== false)
}

function openEdit(row: Row) {
  current.value = row
  formCategory.value = (row.feeCategoryCode as string) ?? ''
  formSelfPay.value = !!row.selfPay
  originalExecDept = (row.execDeptId as number | null) ?? null
  formExecDept.value = originalExecDept
  dialogVisible.value = true
}

async function save() {
  saving.value = true
  try {
    await client.put(`/masterdata/charge-items/${current.value.id}/attrs`, {
      feeCategoryCode: formCategory.value || null,
      selfPay: formSelfPay.value,
      // 选了科室就传 id；原来有、现在清掉了才显式 clearExecDept（后端"不传 = 保持原值"）
      execDeptId: formExecDept.value ?? null,
      clearExecDept: formExecDept.value == null && originalExecDept != null ? true : null,
    })
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await Promise.all([load(), loadCategories(), loadDepts()])
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 12px; line-height: 1.7; }
</style>
