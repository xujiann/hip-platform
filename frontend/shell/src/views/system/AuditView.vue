<template>
  <el-card>
    <div class="toolbar">
      <h3>审计日志（写操作与登录留痕）</h3>
      <div style="display: flex; gap: 8px; flex-wrap: wrap">
        <el-input v-model="username" placeholder="按用户名筛选" clearable style="width: 160px"
                  @keyup.enter="reload" @clear="reload" />
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                        start-placeholder="起" end-placeholder="止" style="width: 260px"
                        @change="reload" />
        <el-checkbox v-model="sensitive" @change="reload">仅敏感操作</el-checkbox>
        <el-button type="primary" size="small" @click="reload">查询</el-button>
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
    <el-pagination class="pager" background layout="total, prev, pager, next, sizes"
                   :total="total" :current-page="page" :page-size="size" :page-sizes="[20, 50, 100, 200]"
                   @current-change="onPage" @size-change="onSize" />
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import client from '../../api/client'

const username = ref('')
const range = ref<[string, string] | null>(null)
const sensitive = ref(false)
const records = ref<Record<string, unknown>[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(50)

async function load() {
  const params: Record<string, unknown> = {
    username: username.value || undefined,
    sensitive: sensitive.value || undefined,
    from: range.value?.[0] || undefined,
    to: range.value?.[1] || undefined,
    page: page.value,
    size: size.value,
  }
  const data = (await client.get('/audit/logs', { params })).data.data
  records.value = data.list
  total.value = data.total
}

/** 改筛选条件回到第 1 页再查（否则停在超出范围的页码会空白） */
function reload() {
  page.value = 1
  load()
}
function onPage(p: number) {
  page.value = p
  load()
}
function onSize(s: number) {
  size.value = s
  page.value = 1
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; gap: 12px; flex-wrap: wrap; }
.toolbar h3 { margin: 0; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
