<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="服务窗口" name="windows">
        <el-form inline size="small">
          <el-form-item><el-input v-model="win.winNo" placeholder="窗口编号" style="width: 100px" /></el-form-item>
          <el-form-item><el-input v-model="win.name" placeholder="窗口名称" style="width: 150px" /></el-form-item>
          <el-form-item>
            <el-select v-model="win.winType" style="width: 120px">
              <el-option label="采样" value="SAMPLE" /><el-option label="发药" value="PHARMACY" /><el-option label="收费" value="CHARGE" />
            </el-select>
          </el-form-item>
          <el-button type="primary" size="small" :loading="saveWindowLoading" @click="saveWindow">保存窗口</el-button>
        </el-form>
        <el-table :data="windows" size="small" border>
          <el-table-column prop="win_no" label="编号" width="90" />
          <el-table-column prop="name" label="名称" width="170" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              {{ { SAMPLE: '采样', PHARMACY: '发药', CHARGE: '收费' }[row.win_type as string] }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'OPEN' ? 'success' : 'info'" size="small">
                {{ row.status === 'OPEN' ? '开放' : '关闭' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link size="small" :loading="windowBusyId === row.win_no" @click="toggleWindow(row)">{{ row.status === 'OPEN' ? '关闭' : '开放' }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="科研台账" name="research">
        <el-form inline size="small">
          <el-form-item>
            <el-select v-model="sr.itemType" style="width: 150px">
              <el-option label="科研项目" value="PROJECT" /><el-option label="知识产权转化" value="IP" />
            </el-select>
          </el-form-item>
          <el-form-item><el-input v-model="sr.title" placeholder="题目/成果名称" style="width: 220px" /></el-form-item>
          <el-form-item><el-input v-model="sr.leader" placeholder="负责人" style="width: 100px" /></el-form-item>
          <el-form-item><el-input-number v-model="sr.amount" :min="0" :max="99999999.99" :step="10000" /></el-form-item>
          <el-button type="primary" size="small" :loading="addResearchLoading" @click="addResearch">登记</el-button>
        </el-form>
        <el-table :data="research" size="small" border>
          <el-table-column label="类型" width="120">
            <template #default="{ row }">{{ row.item_type === 'IP' ? '知识产权转化' : '科研项目' }}</template>
          </el-table-column>
          <el-table-column prop="title" label="题目" show-overflow-tooltip />
          <el-table-column prop="leader" label="负责人" width="90" />
          <el-table-column prop="amount" label="金额" width="100" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'APPROVED' ? 'success'
                : row.status === 'REJECTED' ? 'info' : 'warning'">
                {{ { DRAFT: '草稿', APPROVED: '已审核', REJECTED: '已驳回' }[row.status as string] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <template v-if="row.status === 'DRAFT'">
                <el-button link type="success" size="small" :loading="researchBusyId === row.id" @click="reviewResearch(row, true)">审核通过</el-button>
                <el-button link type="danger" size="small" :loading="researchBusyId === row.id" @click="reviewResearch(row, false)">驳回</el-button>
                <el-button link size="small" :loading="researchBusyId === row.id" @click="deleteResearch(row)">删除</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="我发起的 OA" name="myoa">
        <el-table :data="myRequests" size="small" border>
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column prop="title" label="标题" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'APPROVED' ? 'success'
                : row.status === 'REJECTED' ? 'danger' : 'warning'">
                {{ { PENDING: '审批中', APPROVED: '已通过', REJECTED: '已驳回' }[row.status as string] ?? row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="approve_note" label="审批意见" show-overflow-tooltip />
          <el-table-column prop="created_at" label="发起时间" width="170" />
        </el-table>
        <el-empty v-if="!myRequests.length" description="暂无我发起的流程" />
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('windows')
const windows = ref<Record<string, unknown>[]>([])
const research = ref<Record<string, unknown>[]>([])
const myRequests = ref<Record<string, unknown>[]>([])
const win = reactive({ winNo: '', name: '', winType: 'SAMPLE' })
const sr = reactive({ itemType: 'PROJECT', title: '', leader: '', amount: 0 })
const saveWindowLoading = ref(false)
const addResearchLoading = ref(false)
const windowBusyId = ref<unknown>(null)
const researchBusyId = ref<unknown>(null)

async function loadWindows() { windows.value = (await client.get('/windows')).data.data }
async function loadResearch() { research.value = (await client.get('/research')).data.data }
async function loadMyOa() { myRequests.value = (await client.get('/oa/my-requests')).data.data }

async function saveWindow() {
  if (!win.winNo || !win.name) { ElMessage.warning('请填写窗口编号和名称'); return }
  saveWindowLoading.value = true
  try {
    await client.post('/windows', win)
    await loadWindows()
  } finally {
    saveWindowLoading.value = false
  }
}
async function toggleWindow(row: Record<string, unknown>) {
  windowBusyId.value = row.win_no
  try {
    await client.put(`/windows/${row.win_no}/toggle`)
    await loadWindows()
  } finally {
    windowBusyId.value = null
  }
}
async function addResearch() {
  if (!sr.title) { ElMessage.warning('请填写题目/成果名称'); return }
  addResearchLoading.value = true
  try {
    await client.post('/research', { ...sr, content: '' })
    ElMessage.success('已登记')
    sr.title = ''
    await loadResearch()
  } finally {
    addResearchLoading.value = false
  }
}
async function reviewResearch(row: Record<string, unknown>, approve: boolean) {
  const res = await ElMessageBox.prompt('审核意见', approve ? '通过' : '驳回', { inputValue: approve ? '同意' : '' }).catch(() => null)
  if (!res) return
  const { value } = res
  researchBusyId.value = row.id
  try {
    await client.put(`/research/${row.id}/review`, null, { params: { approve, note: value } })
    await loadResearch()
  } finally {
    researchBusyId.value = null
  }
}
async function deleteResearch(row: Record<string, unknown>) {
  researchBusyId.value = row.id
  try {
    await client.delete(`/research/${row.id}`)
    await loadResearch()
  } finally {
    researchBusyId.value = null
  }
}

watch(tab, (t) => {
  if (t === 'research') loadResearch()
  if (t === 'myoa') loadMyOa()
})
onMounted(loadWindows)
</script>
