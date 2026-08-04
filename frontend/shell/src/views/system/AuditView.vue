<template>
  <el-card>
    <div class="toolbar">
      <h3>审计日志（写操作与登录留痕）</h3>
      <div style="display: flex; gap: 8px">
        <el-input v-model="username" placeholder="按用户名筛选" clearable style="width: 180px"
                  @keyup.enter="load" @clear="load" />
        <el-button type="primary" size="small" @click="load">查询</el-button>
      </div>
    </div>
    <el-table :data="records" size="small" border>
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ String(row.created_at).slice(0, 19).replace('T', ' ') }}</template>
      </el-table-column>
      <el-table-column prop="username" label="用户" width="110" />
      <el-table-column prop="method" label="方法" width="70" />
      <el-table-column prop="path" label="接口" />
      <el-table-column label="结果" width="80">
        <template #default="{ row }">
          <el-tag :type="Number(row.http_status) < 400 ? 'success' : 'danger'" size="small">{{ row.http_status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="client_ip" label="IP" width="120" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import client from '../../api/client'

const username = ref('')
const records = ref<Record<string, unknown>[]>([])

async function load() {
  records.value = (await client.get('/audit/logs', {
    params: { username: username.value || undefined },
  })).data.data
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
</style>
