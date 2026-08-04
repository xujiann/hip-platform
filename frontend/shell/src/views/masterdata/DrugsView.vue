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
      <el-table-column prop="unit" label="单位" width="60" />
      <el-table-column prop="price" label="单价" width="80" />
      <el-table-column prop="stock" label="库存" width="80" />
      <el-table-column label="抗菌药" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.antibiotic" type="warning" size="small">是</el-tag>
        </template>
      </el-table-column>
    </el-table>
    <p class="hint">药品新增/调价/入库将在药库模块提供，目前为种子数据。</p>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import client from '../../api/client'

const keyword = ref('')
const records = ref<unknown[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/masterdata/drugs', { params: { keyword: keyword.value } })
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
.hint { color: #999; font-size: 12px; margin-top: 12px; }
</style>
