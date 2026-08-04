<template>
  <div class="portal-home">
    <header class="topbar">
      <span>{{ name }} 的掌上医院</span>
      <el-button link size="small" style="color: #fff" @click="logout">退出</el-button>
    </header>

    <el-tabs v-model="tab" class="tabs" stretch>
      <el-tab-pane label="预约挂号" name="book">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
                        style="width: 100%; margin-bottom: 8px" @change="loadSchedules" />
        <el-card v-for="s in schedules" :key="s.id as number" class="item" shadow="never">
          <div class="row">
            <div>
              <b>{{ s.deptName }}</b>
              <span class="muted">{{ { AM: '上午', PM: '下午', FULL: '全天' }[s.shift as string] }}
                · {{ s.regType === 'EXPERT' ? '专家号' : '普通号' }} · ¥{{ s.fee }}</span>
            </div>
            <el-button type="success" size="small" :disabled="Number(s.remaining) <= 0" @click="book(s)">
              {{ Number(s.remaining) > 0 ? `预约(余${s.remaining})` : '已约满' }}
            </el-button>
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="我的挂号" name="regs">
        <el-card v-for="r in regs" :key="r.id as number" class="item" shadow="never">
          <div class="row">
            <div>
              <b>{{ r.deptName }}</b> 第{{ r.regNo }}号
              <span class="muted">{{ r.visitDate }} · ¥{{ r.fee }}</span>
            </div>
            <el-tag size="small" :type="regTag[r.status as string]">{{ regNames[r.status as string] }}</el-tag>
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="检验报告" name="labs">
        <el-card v-for="l in labs" :key="l.orderId as number" class="item" shadow="never">
          <b>{{ l.itemName }}</b>
          <span class="muted">{{ String(l.reportDate).slice(0, 10) }}</span>
          <el-table :data="l.results as Record<string, unknown>[]" size="small" style="margin-top: 6px">
            <el-table-column prop="itemName" label="项目" />
            <el-table-column label="结果" width="90">
              <template #default="{ row }">
                <span :class="{ abnormal: row.abnormalFlag && row.abnormalFlag !== 'N' }">
                  {{ row.resultValue }}{{ row.abnormalFlag && row.abnormalFlag !== 'N' ? `(${row.abnormalFlag})` : '' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="refRange" label="参考" width="90" />
          </el-table>
        </el-card>
        <el-empty v-if="!labs.length" description="暂无报告" />
      </el-tab-pane>

      <el-tab-pane label="费用" name="charges">
        <el-card v-for="c in charges" :key="String(c.chargeNo)" class="item" shadow="never">
          <div class="row">
            <div>
              <b>¥{{ c.totalAmount }}</b>
              <span class="muted">{{ c.chargeNo }}</span>
            </div>
            <el-tag size="small" :type="c.status === 'PAID' ? 'success' : 'info'">
              {{ c.status === 'PAID' ? '已支付' : '已退费' }}
            </el-tag>
          </div>
        </el-card>
        <el-empty v-if="!charges.length" description="暂无费用记录" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import axios from 'axios'

const router = useRouter()
const name = localStorage.getItem('hip_portal_name') ?? ''
const tab = ref('book')
const date = ref(new Date().toISOString().slice(0, 10))
const schedules = ref<Record<string, unknown>[]>([])
const regs = ref<Record<string, unknown>[]>([])
const labs = ref<Record<string, unknown>[]>([])
const charges = ref<Record<string, unknown>[]>([])

const regNames: Record<string, string> = { REGISTERED: '已挂号', CANCELLED: '已退号', VISITED: '已就诊' }
const regTag: Record<string, string> = { REGISTERED: 'success', CANCELLED: 'info', VISITED: '' }

const portal = axios.create({ baseURL: '/api/portal' })
portal.interceptors.request.use((cfg) => {
  cfg.headers.Authorization = `Bearer ${localStorage.getItem('hip_portal_token')}`
  return cfg
})
portal.interceptors.response.use((resp) => {
  if (resp.data.code !== 0) {
    ElMessage.error(resp.data.message)
    return Promise.reject(new Error(resp.data.message))
  }
  return resp
}, (err) => {
  if (err.response?.status === 401) router.push('/portal')
  return Promise.reject(err)
})

async function loadSchedules() {
  schedules.value = (await portal.get('/schedules', { params: { date: date.value } })).data.data
}

async function loadMine() {
  const [r, l, c] = await Promise.all([
    portal.get('/my/registrations'), portal.get('/my/lab-reports'), portal.get('/my/charges'),
  ])
  regs.value = r.data.data
  labs.value = l.data.data
  charges.value = c.data.data
}

async function book(s: Record<string, unknown>) {
  const resp = await portal.post('/register', { scheduleId: s.id })
  const d = resp.data.data
  ElMessage.success(`预约成功：${d.deptName} ${d.visitDate} 第 ${d.regNo} 号`)
  await Promise.all([loadSchedules(), loadMine()])
  tab.value = 'regs'
}

function logout() {
  localStorage.removeItem('hip_portal_token')
  router.push('/portal')
}

onMounted(() => {
  loadSchedules()
  loadMine()
})
</script>

<style scoped>
.portal-home { max-width: 480px; margin: 0 auto; min-height: 100%; background: #f5f7fa; }
.topbar {
  background: #16786f;
  color: #fff;
  padding: 12px 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.tabs { padding: 8px; }
.item { margin-bottom: 8px; }
.row { display: flex; justify-content: space-between; align-items: center; }
.muted { color: #999; font-size: 12px; margin-left: 8px; }
.abnormal { color: #d03050; font-weight: 600; }
</style>
