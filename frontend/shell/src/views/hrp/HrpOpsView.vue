<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="物资耗材" name="materials">
        <el-form inline size="small">
          <el-form-item><el-input v-model="mat.name" placeholder="名称" style="width: 160px" /></el-form-item>
          <el-form-item>
            <el-select v-model="mat.category" style="width: 140px">
              <el-option label="普通物资" value="MATERIAL" />
              <el-option label="设备配件" value="DEVICE_PART" />
              <el-option label="高值耗材" value="CONSUMABLE_HIGH" />
              <el-option label="低值耗材" value="CONSUMABLE_LOW" />
            </el-select>
          </el-form-item>
          <el-form-item><el-input v-model="mat.unit" placeholder="单位" style="width: 80px" /></el-form-item>
          <el-form-item><el-input-number v-model="mat.price" :min="0" :max="9999999.99" :precision="2" /></el-form-item>
          <el-button type="primary" size="small" :loading="createMaterialLoading" @click="createMaterial">新建</el-button>
        </el-form>
        <el-table :data="materials" size="small" border>
          <el-table-column prop="code" label="编码" width="110" />
          <el-table-column prop="name" label="名称" />
          <el-table-column label="类别" width="90">
            <template #default="{ row }">{{ catNames[row.category as string] }}</template>
          </el-table-column>
          <el-table-column prop="price" label="单价" width="90" />
          <el-table-column prop="stock" label="库存" width="80" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button link type="success" size="small" :loading="stockBusyId === row.id" @click="stockIo(row, 'in')">入库</el-button>
              <el-button link type="warning" size="small" :loading="stockBusyId === row.id" @click="stockIo(row, 'out')">出库</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="供应商" name="suppliers">
        <el-form inline size="small">
          <el-form-item><el-input v-model="sup.name" placeholder="供应商名称" style="width: 200px" /></el-form-item>
          <el-form-item><el-input v-model="sup.contact" placeholder="联系人" style="width: 100px" /></el-form-item>
          <el-form-item><el-input v-model="sup.phone" placeholder="电话" style="width: 140px" /></el-form-item>
          <el-form-item><el-input v-model="sup.scope" placeholder="供货范围" style="width: 180px" /></el-form-item>
          <el-button type="primary" size="small" :loading="createSupplierLoading" @click="createSupplier">登记</el-button>
        </el-form>
        <el-table :data="suppliers" size="small" border>
          <el-table-column prop="name" label="名称" />
          <el-table-column prop="contact" label="联系人" width="100" />
          <el-table-column prop="phone" label="电话" width="140" />
          <el-table-column prop="scope" label="供货范围" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="OA 审批" name="oa">
        <el-form inline size="small">
          <el-form-item>
            <el-select v-model="oa.type" style="width: 110px">
              <el-option label="请假" value="LEAVE" />
              <el-option label="用章" value="SEAL" />
              <el-option label="采购申请" value="PURCHASE" />
              <el-option label="其他" value="OTHER" />
            </el-select>
          </el-form-item>
          <el-form-item><el-input v-model="oa.title" placeholder="标题" style="width: 180px" /></el-form-item>
          <el-form-item><el-input v-model="oa.content" placeholder="内容" style="width: 260px" /></el-form-item>
          <el-button type="primary" size="small" :loading="createOaLoading" @click="createOa">提交申请</el-button>
        </el-form>
        <el-table :data="oaList" size="small" border>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ oaTypes[row.type as string] }}</template>
          </el-table-column>
          <el-table-column prop="title" label="标题" width="180" />
          <el-table-column prop="applicant_name" label="申请人" width="90" />
          <el-table-column prop="content" label="内容" show-overflow-tooltip />
          <el-table-column label="状态" width="170">
            <template #default="{ row }">
              <template v-if="row.status === 'PENDING'">
                <el-button link type="success" size="small" :loading="decideBusyId === row.id" @click="decide(row, true)">批准</el-button>
                <el-button link type="danger" size="small" :loading="decideBusyId === row.id" @click="decide(row, false)">驳回</el-button>
              </template>
              <el-tag v-else :type="row.status === 'APPROVED' ? 'success' : 'danger'" size="small">
                {{ row.status === 'APPROVED' ? '已批准' : '已驳回' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('materials')
const catNames: Record<string, string> = {
  MATERIAL: '普通物资', DEVICE_PART: '设备配件', CONSUMABLE_HIGH: '高值耗材', CONSUMABLE_LOW: '低值耗材',
}
const oaTypes: Record<string, string> = { LEAVE: '请假', SEAL: '用章', PURCHASE: '采购', OTHER: '其他' }

const materials = ref<Record<string, unknown>[]>([])
const suppliers = ref<Record<string, unknown>[]>([])
const oaList = ref<Record<string, unknown>[]>([])
const mat = reactive({ name: '', category: 'CONSUMABLE_LOW', unit: '个', price: 1 })
const sup = reactive({ name: '', contact: '', phone: '', scope: '' })
const oa = reactive({ type: 'LEAVE', title: '', content: '' })
const createMaterialLoading = ref(false)
const createSupplierLoading = ref(false)
const createOaLoading = ref(false)
const stockBusyId = ref<unknown>(null)
const decideBusyId = ref<unknown>(null)

async function loadAll() {
  materials.value = (await client.get('/hrp/materials')).data.data
  suppliers.value = (await client.get('/hrp/suppliers')).data.data
  oaList.value = (await client.get('/oa/requests')).data.data
}

async function createMaterial() {
  if (!mat.name) { ElMessage.warning('请填写名称'); return }
  createMaterialLoading.value = true
  try {
    await client.post('/hrp/materials', mat)
    ElMessage.success('已建档')
    await loadAll()
  } finally {
    createMaterialLoading.value = false
  }
}

async function stockIo(row: Record<string, unknown>, dir: 'in' | 'out') {
  const res = await ElMessageBox.prompt('数量', dir === 'in' ? '入库' : '出库', { inputValue: '10' }).catch(() => null)
  if (!res) return
  const { value } = res
  stockBusyId.value = row.id
  try {
    await client.post(`/hrp/materials/${row.id}/stock-${dir}`, null, { params: { qty: Number(value) } })
    ElMessage.success('库存已更新')
    await loadAll()
  } finally {
    stockBusyId.value = null
  }
}

async function createSupplier() {
  if (!sup.name) { ElMessage.warning('请填写供应商名称'); return }
  createSupplierLoading.value = true
  try {
    await client.post('/hrp/suppliers', sup)
    await loadAll()
  } finally {
    createSupplierLoading.value = false
  }
}

async function createOa() {
  if (!oa.title || !oa.content) { ElMessage.warning('请填写标题和内容'); return }
  createOaLoading.value = true
  try {
    await client.post('/oa/requests', oa)
    ElMessage.success('已提交')
    await loadAll()
  } finally {
    createOaLoading.value = false
  }
}

async function decide(row: Record<string, unknown>, approve: boolean) {
  decideBusyId.value = row.id
  try {
    await client.put(`/oa/requests/${row.id}/decide`, null, { params: { approve, note: approve ? '同意' : '不同意' } })
    await loadAll()
  } finally {
    decideBusyId.value = null
  }
}

onMounted(loadAll)
</script>
