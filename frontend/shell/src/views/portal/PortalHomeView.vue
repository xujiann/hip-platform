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

      <el-tab-pane label="检查报告" name="exams">
        <el-card v-for="(e, i) in examReports" :key="i" class="item" shadow="never">
          <b>{{ e.item_name }}</b>
          <el-tag size="small" style="margin-left: 6px">{{ e.report_type === 'PATH' ? '病理' : '检查' }}</el-tag>
          <span class="muted">{{ String(e.report_date).slice(0, 10) }}</span>
          <div style="margin-top: 6px"><b>结论：</b>{{ e.conclusion }}</div>
          <div class="muted">{{ e.detail }}</div>
        </el-card>
        <el-empty v-if="!examReports.length" description="暂无报告" />
      </el-tab-pane>

      <el-tab-pane label="缴费" name="charges">
        <template v-if="bills.length">
          <h4>待缴费</h4>
          <el-card v-for="b in bills" :key="String(b.registration_id)" class="item" shadow="never">
            <div class="row">
              <div>
                <b>¥{{ b.total }}</b>
                <span class="muted">{{ String(b.items).slice(0, 40) }}</span>
              </div>
              <div>
                <el-button size="small" type="success" @click="startPay(b, 'WECHAT')">微信支付</el-button>
                <el-button size="small" type="primary" @click="startPay(b, 'ALIPAY')">支付宝</el-button>
              </div>
            </div>
          </el-card>
        </template>
        <el-card v-if="paying" class="item" shadow="never">
          <b>扫码支付 ¥{{ paying.amount }}</b>
          <div class="muted" style="word-break: break-all">{{ paying.qrContent }}</div>
          <el-button size="small" type="success" style="margin-top: 6px" @click="confirmPay">
            我已支付（模拟回调）</el-button>
        </el-card>
        <h4>历史账单</h4>
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
        <el-empty v-if="!charges.length && !bills.length" description="暂无费用记录" />
      </el-tab-pane>

      <el-tab-pane label="智能导诊" name="guide">
        <el-input v-model="symptom" placeholder="描述症状，如：胸闷两天" clearable>
          <template #append><el-button @click="doGuide">导诊</el-button></template>
        </el-input>
        <el-card v-for="(g, i) in guides" :key="i" class="item" shadow="never" style="margin-top: 8px">
          <b>建议科室：{{ g.dept_name }}</b>
          <div class="muted">{{ g.advice }}</div>
        </el-card>
        <el-empty v-if="guided && !guides.length" description="未匹配到科室，建议挂全科/咨询导诊台" />
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
const examReports = ref<Record<string, unknown>[]>([])
const bills = ref<Record<string, unknown>[]>([])
const paying = ref<{ payNo: string; amount: unknown; qrContent: string } | null>(null)
const symptom = ref('')
const guides = ref<Record<string, unknown>[]>([])
const guided = ref(false)

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
  const [r, l, c, e, b] = await Promise.all([
    portal.get('/my/registrations'), portal.get('/my/lab-reports'), portal.get('/my/charges'),
    portal.get('/my/exam-reports'), portal.get('/my/pending-bill'),
  ])
  regs.value = r.data.data
  labs.value = l.data.data
  charges.value = c.data.data
  examReports.value = e.data.data
  bills.value = b.data.data
}

async function startPay(bill: Record<string, unknown>, channel: string) {
  const resp = await portal.post('/my/pay', { registrationId: bill.registration_id, channel })
  paying.value = resp.data.data
}

async function confirmPay() {
  if (!paying.value) return
  const resp = await portal.post(`/my/pay/${paying.value.payNo}/confirm`)
  ElMessage.success(`缴费成功：${resp.data.data.chargeNo}`)
  paying.value = null
  await loadMine()
}

async function doGuide() {
  if (!symptom.value) return
  guides.value = (await portal.get('/guide', { params: { symptom: symptom.value } })).data.data
  guided.value = true
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
