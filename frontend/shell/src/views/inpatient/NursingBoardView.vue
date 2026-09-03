<template>
  <el-card>
    <div class="toolbar">
      <h3>护理白板 · 在院一览</h3>
      <el-button link type="primary" @click="load">刷新</el-button>
    </div>
    <!-- 2012★：与医生站同款检索区。白板数据来自 /nursing/board（含护理级别/过敏/待执行医嘱），
         过滤则统一交给 /inpatient/admissions 判定后按 admission_id 取交集——
         "是否转科""我的病人"这类条件只有服务端知道，前端自行过滤必然与后端口径漂移。 -->
    <div class="filters">
      <el-input v-model="q.keyword" size="small" clearable placeholder="姓名 / 住院号" style="width: 160px"
                @keyup.enter="load" @clear="load" />
      <el-select v-model="q.deptId" size="small" clearable placeholder="科室" style="width: 130px" @change="load">
        <el-option v-for="d in clinicalDepts" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-select v-model="q.wardId" size="small" clearable placeholder="病区" style="width: 130px" @change="load">
        <el-option v-for="w in wards" :key="w.id" :label="w.name" :value="w.id" />
      </el-select>
      <el-select v-model="q.careLevel" size="small" clearable placeholder="护理级别" style="width: 120px" @change="load">
        <el-option v-for="l in careLevels" :key="l" :label="`${l}护理`" :value="l" />
      </el-select>
      <el-select v-model="q.transferred" size="small" clearable placeholder="是否转科" style="width: 110px" @change="load">
        <el-option label="转过科" :value="true" />
        <el-option label="未转科" :value="false" />
      </el-select>
      <el-checkbox v-model="q.mine" size="small" @change="load">只看我主管的</el-checkbox>
      <el-button link size="small" @click="reset">重置</el-button>
      <span class="count">共 {{ records.length }} 人{{ filterActive ? '（已筛选）' : '' }}</span>
    </div>
    <el-alert v-if="listError" type="warning" show-icon :closable="false" :title="listError"
              style="margin-bottom: 8px" />
    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="ward_name" label="病区" width="110" />
      <el-table-column prop="bed_no" label="床号" width="70" />
      <el-table-column prop="patient_name" label="姓名" width="90" />
      <el-table-column label="护理级别" width="130">
        <template #default="{ row }">
          <el-select :model-value="row.care_level" size="small" style="width: 90px"
                     @change="(v: string) => setLevel(row, v)">
            <el-option v-for="l in ['特级', '一级', '二级', '三级']" :key="l" :label="l" :value="l" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="过敏" width="140">
        <template #default="{ row }">
          <el-tag v-if="row.allergy_history" type="danger" size="small">{{ row.allergy_history }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="dept_name" label="科室" width="110" />
      <el-table-column label="待执行医嘱" width="110">
        <template #default="{ row }">
          <el-tag :type="Number(row.pending_orders) > 0 ? 'warning' : 'success'" size="small">
            {{ row.pending_orders }} 条
          </el-tag>
        </template>
      </el-table-column>
      <!-- v42：白板是护士的主入口，护理文书入口挂在这里，否则新表没人写 -->
      <el-table-column label="护理文书" width="130">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openRecords(row)">
            记录 {{ row.nursing_records }} 条
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="admission_no" label="住院号" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const router = useRouter()
const records = ref<Record<string, unknown>[]>([])
const loading = ref(false)

// 2012★：白板检索区
const careLevels = ['特级', '一级', '二级', '三级']
const depts = ref<{ id: number; name: string; type: string }[]>([])
const clinicalDepts = computed(() => depts.value.filter((d) => d.type === 'CLINICAL'))
const wards = computed(() => depts.value.filter((d) => d.type === 'NURSING'))
const listError = ref('')
const q = reactive({
  keyword: '',
  deptId: null as number | null,
  wardId: null as number | null,
  careLevel: null as string | null,
  transferred: null as boolean | null,
  mine: false,
})
const filterActive = computed(() =>
  !!q.keyword.trim() || q.deptId != null || q.wardId != null || !!q.careLevel
  || q.transferred != null || q.mine)

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/inpatient/nursing/board')
    const board = resp.data.data as Record<string, unknown>[]
    if (!filterActive.value) {
      listError.value = ''
      records.value = board
      return
    }
    const params: Record<string, unknown> = {}
    if (q.keyword.trim()) params.keyword = q.keyword.trim()
    if (q.deptId != null) params.deptId = q.deptId
    if (q.wardId != null) params.wardId = q.wardId
    if (q.careLevel) params.careLevel = q.careLevel
    if (q.transferred != null) params.transferred = q.transferred
    if (q.mine) params.mine = true
    const hit = await client.get('/inpatient/admissions', { params })
    if (hit.data.code !== 0) {
      // 4880 检索条件非法 / 4881 护理级别非法
      listError.value = hit.data.message
      return
    }
    listError.value = ''
    const ids = new Set((hit.data.data as Record<string, unknown>[]).map((a) => Number(a.id)))
    records.value = board.filter((r) => ids.has(Number(r.admission_id)))
  } finally {
    loading.value = false
  }
}

function reset() {
  q.keyword = ''
  q.deptId = null
  q.wardId = null
  q.careLevel = null
  q.transferred = null
  q.mine = false
  load()
}

/**
 * 调级走 v42 的带原因端点：分级护理的升降级是护理工作量与收费的直接依据，
 * 无原因的调级在质控上不可追溯。取消则重新加载还原下拉显示（下拉是非受控回显）。
 */
async function setLevel(row: Record<string, unknown>, level: string) {
  const res = await ElMessageBox.prompt(`调整为「${level}护理」的原因`, '护理级别变更', {
    inputPlaceholder: '如：病情加重转特级护理 / 病情稳定降级',
  }).catch(() => null)
  if (!res) {
    await load()
    return
  }
  await client.put(`/inpatient/admissions/${row.admission_id}/care-level/change`,
    { level, reason: res.value })
  ElMessage.success(`已调整为${level}护理`)
  await load()
}

function openRecords(row: Record<string, unknown>) {
  router.push({ path: '/inpatient/nursing-record', query: { admissionId: String(row.admission_id) } })
}

onMounted(async () => {
  depts.value = (await client.get('/system/depts')).data.data
  await load()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
.filters { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.count { color: #909399; font-size: 12px; margin-left: auto; }
</style>
