<template>
  <el-card>
    <el-tabs v-model="tab" @tab-change="load">
      <el-tab-pane label="我的待确认" name="mine">
        <el-alert v-if="mine.length === 0" title="暂无待确认危急值" type="success" :closable="false" show-icon />
        <el-table v-else :data="mine" size="small" border>
          <el-table-column label="来源" width="70">
            <template #default="{ row }">
              <el-tag :type="row.source === 'RIS' ? 'warning' : 'danger'" size="small">
                {{ row.source === 'RIS' ? '影像' : '检验' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="content" label="危急值内容" show-overflow-tooltip />
          <el-table-column label="应确认时限" width="160">
            <template #default="{ row }">
              <span :class="{ overdue: row.overdue }">
                {{ String(row.deadline_at ?? '').slice(0, 19).replace('T', ' ') }}
                <el-tag v-if="row.overdue" type="danger" size="small" effect="dark">已超期</el-tag>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="ack(row)">接收确认</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="超期看板" name="overdue">
        <el-alert v-if="overdue.length === 0" title="无超期未确认危急值" type="success" :closable="false" show-icon />
        <el-table v-else :data="overdue" size="small" border>
          <el-table-column label="来源" width="70">
            <template #default="{ row }">{{ row.source === 'RIS' ? '影像' : '检验' }}</template>
          </el-table-column>
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="content" label="危急值内容" show-overflow-tooltip />
          <el-table-column prop="notify_to_name" label="应确认医师" width="110" />
          <el-table-column label="应确认时限" width="160">
            <template #default="{ row }">{{ String(row.deadline_at ?? '').slice(0, 19).replace('T', ' ') }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('mine')
const mine = ref<Record<string, unknown>[]>([])
const overdue = ref<Record<string, unknown>[]>([])

async function load() {
  if (tab.value === 'mine') {
    mine.value = (await client.get('/outpatient/critical-alerts/my-pending')).data.data
  } else {
    overdue.value = (await client.get('/outpatient/critical-alerts/overdue')).data.data
  }
}

async function ack(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('处置措施（必填，将留痕）', '危急值接收确认',
    { inputPlaceholder: '如：已电话通知患者返院复查并调整用药' }).catch(() => null)
  if (!res) return
  const disposition = (res.value || '').trim()
  if (!disposition) { ElMessage.warning('处置措施必填'); return }
  await client.put(`/outpatient/critical-alerts/${row.id}/acknowledge`, { disposition })
  ElMessage.success('已确认并留痕')
  await load()
}

onMounted(load)
</script>

<style scoped>
.overdue { color: var(--el-color-danger); font-weight: 600; }
</style>
