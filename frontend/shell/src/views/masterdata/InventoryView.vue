<template>
  <div class="inv-page">
    <el-card>
      <template #header>药品入库</template>
      <el-form :model="form" label-width="80px">
        <el-form-item label="药品" required>
          <el-select v-model="form.drugId" filterable remote :remote-method="searchDrugs" placeholder="搜索药品"
                     style="width: 100%">
            <el-option v-for="d in drugOptions" :key="d.id as number"
                       :label="`${d.name} ${d.spec}（现存 ${d.stock}）`" :value="d.id as number" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量" required><el-input-number v-model="form.qty" :min="1" /></el-form-item>
        <el-form-item label="批号"><el-input v-model="form.batchNo" /></el-form-item>
        <el-form-item label="效期">
          <el-date-picker v-model="form.expireDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="供应商"><el-input v-model="form.supplier" /></el-form-item>
        <el-button type="primary" :loading="saving" @click="stockIn">入 库</el-button>
      </el-form>

      <el-divider>低库存预警（&lt;100）</el-divider>
      <el-table :data="lowStock" size="small" height="180">
        <el-table-column prop="name" label="药品" />
        <el-table-column prop="stock" label="库存" width="80" />
      </el-table>
    </el-card>

    <el-card>
      <template #header>
        库存流水
        <el-button link type="primary" style="float: right" @click="loadTransactions">刷新</el-button>
      </template>
      <el-table :data="transactions" size="small" height="520">
        <el-table-column prop="drugName" label="药品" />
        <el-table-column label="类型" width="70">
          <template #default="{ row }">
            <el-tag :type="{ IN: 'success', OUT: 'warning', ADJ: 'info' }[row.type as string]" size="small">
              {{ { IN: '入库', OUT: '出库', ADJ: '调整' }[row.type as string] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" width="70" />
        <el-table-column prop="stockAfter" label="结存" width="70" />
        <el-table-column prop="refNo" label="关联单号" width="150" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const drugOptions = ref<Record<string, unknown>[]>([])
const transactions = ref<Record<string, unknown>[]>([])
const lowStock = ref<Record<string, unknown>[]>([])
const saving = ref(false)

const form = reactive({
  drugId: null as number | null, qty: 100, batchNo: '', expireDate: null as string | null, supplier: '',
})

async function searchDrugs(kw: string) {
  const resp = await client.get('/masterdata/drugs', { params: { keyword: kw } })
  drugOptions.value = resp.data.data
}

async function stockIn() {
  if (!form.drugId) {
    ElMessage.warning('请选择药品')
    return
  }
  saving.value = true
  try {
    const resp = await client.post('/inventory/stock-in', form)
    ElMessage.success(`入库成功，单号 ${resp.data.data.inNo}`)
    await Promise.all([loadTransactions(), loadLowStock()])
  } finally {
    saving.value = false
  }
}

async function loadTransactions() {
  const resp = await client.get('/inventory/transactions')
  transactions.value = resp.data.data
}

async function loadLowStock() {
  const resp = await client.get('/inventory/low-stock', { params: { threshold: 100 } })
  lowStock.value = resp.data.data
}

onMounted(() => {
  searchDrugs('')
  loadTransactions()
  loadLowStock()
})
</script>

<style scoped>
.inv-page {
  display: grid;
  grid-template-columns: 420px 1fr;
  gap: 12px;
}
</style>
