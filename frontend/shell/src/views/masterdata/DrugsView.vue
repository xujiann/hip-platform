<template>
  <el-card>
    <div class="toolbar">
      <h3>药品目录</h3>
      <el-input v-model="keyword" placeholder="按名称搜索" clearable style="width: 240px"
                @keyup.enter="load" @clear="load" />
    </div>
    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="code" label="编码" width="90" />
      <el-table-column prop="name" label="药品名称" />
      <el-table-column prop="spec" label="规格" width="150" />
      <el-table-column prop="doseForm" label="剂型" width="80" />
      <!-- v42：财务口径的费用类别（V132 已按 drug_class 回填 W→西药费 / C→中成药费） -->
      <el-table-column label="费用类别" width="120">
        <template #default="{ row }">
          <span v-if="row.feeCategoryCode">{{ categoryName(row.feeCategoryCode as string) }}</span>
          <el-tag v-else type="warning" size="small">未挂类</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="60" />
      <el-table-column prop="price" label="单价" width="80" />
      <el-table-column prop="stock" label="库存" width="80" />
      <el-table-column label="抗菌药" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.antibiotic" type="warning" size="small">是</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="自费" width="70">
        <template #default="{ row }">
          <el-tag v-if="row.selfPay" type="danger" size="small">自费</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="isAdmin" label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">挂类/自费</el-button>
        </template>
      </el-table-column>
    </el-table>
    <p class="hint">
      药品新增/调价/入库在药库模块提供，本页只维护费用类别与自费标记。
      未挂类的药品在「费用分类报表」里进入「未分类」行；自费标记是自费知情同意 gate
      （emr.gate.consent.selfpay）的判定依据，未标记则该 gate 永远不会触发。
    </p>

    <el-dialog v-model="dialogVisible" title="维护费用类别 / 自费标记" width="460px">
      <el-form label-width="100px" size="small">
        <el-form-item label="药品">{{ current.code }} {{ current.name }}</el-form-item>
        <el-form-item label="费用类别">
          <el-select v-model="formCategory" clearable placeholder="不挂类" style="width:220px">
            <el-option v-for="c in categories" :key="String(c.code)" :label="`${c.name}（${c.code}）`"
                       :value="String(c.code)" />
          </el-select>
        </el-form-item>
        <el-form-item label="自费药品">
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
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const current = ref<Row>({})
const formCategory = ref('')
const formSelfPay = ref(false)

function categoryName(code: string): string {
  const hit = categories.value.find((c) => c.code === code)
  return hit ? String(hit.name) : code
}

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/masterdata/drugs', { params: { keyword: keyword.value } })
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

async function loadCategories() {
  categories.value = (await client.get('/masterdata/fee-categories')).data.data
}

function openEdit(row: Row) {
  current.value = row
  formCategory.value = (row.feeCategoryCode as string) ?? ''
  formSelfPay.value = !!row.selfPay
  dialogVisible.value = true
}

async function save() {
  saving.value = true
  try {
    await client.put(`/masterdata/drugs/${current.value.id}/attrs`, {
      feeCategoryCode: formCategory.value || null,
      selfPay: formSelfPay.value,
    })
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await Promise.all([load(), loadCategories()])
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 12px; line-height: 1.7; }
</style>
