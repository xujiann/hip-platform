<template>
  <el-card>
    <div class="toolbar">
      <h3>线索管理<span class="hint">（收到之日起 15 个工作日内核查并决定是否立案，可延长一次）</span></h3>
      <div>
        <el-select v-model="statusFilter" clearable placeholder="全部状态" style="width: 130px; margin-right: 8px" @change="load">
          <el-option v-for="(v, k) in CLUE_STATUS" :key="k" :label="v" :value="k" />
        </el-select>
        <el-button type="primary" @click="createVisible = true">登记线索</el-button>
      </div>
    </div>
    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="clueNo" label="线索编号" width="120" />
      <el-table-column label="来源" width="100">
        <template #default="{ row }">{{ SOURCE[row.source] || row.source }}</template>
      </el-table-column>
      <el-table-column prop="suspectName" label="违法嫌疑人" width="160" />
      <el-table-column label="类别" width="120">
        <template #default="{ row }">{{ PARTY_TYPE[row.suspectType] }}</template>
      </el-table-column>
      <el-table-column prop="content" label="线索内容" show-overflow-tooltip />
      <el-table-column prop="receivedAt" label="收到日期" width="110" />
      <el-table-column label="核查期限" width="130">
        <template #default="{ row }">
          <span :class="{ overdue: row.status === 'PENDING' && row.deadlineAt < today }">
            {{ row.deadlineAt }}{{ row.extended ? '（已延期）' : '' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'FILED' ? 'success' : row.status === 'REJECTED' ? 'info' : 'primary'" size="small">
            {{ CLUE_STATUS[row.status] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button size="small" type="primary" @click="toFile(row)">立案</el-button>
            <el-button size="small" :disabled="row.extended" @click="onExtend(row)">延期</el-button>
            <el-button size="small" type="warning" @click="onReject(row)">不予立案</el-button>
          </template>
          <span v-else class="hint">{{ row.verifyResult || '-' }}</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="登记线索" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="线索来源">
          <el-select v-model="form.source" style="width: 100%">
            <el-option v-for="(v, k) in SOURCE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="违法嫌疑人">
          <el-input v-model="form.suspectName" placeholder="单位名称或个人姓名" />
        </el-form-item>
        <el-form-item label="嫌疑人类别">
          <el-select v-model="form.suspectType" style="width: 100%">
            <el-option v-for="(v, k) in PARTY_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="收到日期">
          <el-date-picker v-model="form.receivedAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="线索内容">
          <el-input v-model="form.content" type="textarea" :rows="4" placeholder="违法行为线索描述" />
        </el-form-item>
        <el-form-item label="核查人">
          <el-input v-model="form.handler" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="onCreate">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import client from '../../api/client'
import { CLUE_STATUS, PARTY_TYPE, SOURCE } from './labels'

const router = useRouter()
const records = ref<any[]>([])
const loading = ref(false)
const statusFilter = ref('')
const createVisible = ref(false)
const today = new Date().toISOString().slice(0, 10)
const form = reactive({ source: 'COMPLAINT', suspectName: '', suspectType: 'PROVIDER', receivedAt: today, content: '', handler: '' })

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/bureau/clues', { params: statusFilter.value ? { status: statusFilter.value } : {} })
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

async function onCreate() {
  if (!form.suspectName || !form.content) {
    ElMessage.warning('请填写嫌疑人与线索内容')
    return
  }
  await client.post('/bureau/clues', form)
  ElMessage.success('线索已登记')
  createVisible.value = false
  form.suspectName = ''
  form.content = ''
  load()
}

async function onExtend(row: any) {
  const { value } = await ElMessageBox.prompt('特殊情况说明（经主要负责人批准，延长 15 个工作日）', '核查延期', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/clues/${row.id}/extend`, { reason: value })
  ElMessage.success('已延期')
  load()
}

async function onReject(row: any) {
  const { value } = await ElMessageBox.prompt('核查结果（不予立案理由）', '不予立案', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/clues/${row.id}/reject`, { verifyResult: value })
  ElMessage.success('已办结')
  load()
}

function toFile(row: any) {
  router.push({ path: '/case/list', query: { clueId: row.id, suspectName: row.suspectName, suspectType: row.suspectType } })
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
.overdue { color: #c0392b; font-weight: 600; }
</style>
