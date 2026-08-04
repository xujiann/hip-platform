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
          {{ { LAB: '检验', EXAM: '检查', TREAT: '治疗', MATERIAL: '材料' }[row.category as string] }}
        </template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="60" />
      <el-table-column prop="price" label="单价" width="90" />
    </el-table>
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
    const resp = await client.get('/masterdata/charge-items', { params: { keyword: keyword.value } })
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
</style>
