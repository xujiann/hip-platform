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
            <div>
              <el-button size="small" :disabled="Number(s.remaining) <= 0" @click="openSlots(s)">
                {{ Number(s.remaining) > 0 ? `选时段(余${s.remaining})` : '已约满' }}
              </el-button>
              <el-button type="success" size="small" :disabled="Number(s.remaining) <= 0" @click="book(s)">
                直接挂号
              </el-button>
            </div>
          </div>
          <!-- 分时段预约：选中该排班后展开时段与余号（避免到院集中候诊） -->
          <div v-if="slotScheduleId === s.id" class="slots">
            <el-empty v-if="!slots.length" description="该号源未开放分时段，可直接挂号" :image-size="60" />
            <div v-for="t in slots" :key="t.id as number" class="row slot-line">
              <span>{{ String(t.time_begin).slice(0, 5) }} - {{ String(t.time_end).slice(0, 5) }}</span>
              <el-button type="primary" size="small" :disabled="Number(t.remaining) <= 0"
                         @click="bookSlot(t)">
                {{ Number(t.remaining) > 0 ? `预约(余${t.remaining})` : '已约满' }}
              </el-button>
            </div>
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="我的预约" name="appts">
        <el-card v-for="a in appts" :key="a.id as number" class="item" shadow="never">
          <div class="row">
            <div>
              <b>{{ a.deptName }}</b> 第{{ a.apptNo }}号
              <span class="muted">{{ a.scheduleDate }}
                {{ String(a.timeBegin).slice(0, 5) }}-{{ String(a.timeEnd).slice(0, 5) }} · ¥{{ a.fee }}</span>
            </div>
            <div>
              <el-tag size="small" :type="apptTag[a.status as string]">{{ apptNames[a.status as string] }}</el-tag>
              <el-button v-if="a.status === 'BOOKED'" type="danger" link size="small"
                         @click="cancelAppt(a)">取消</el-button>
            </div>
          </div>
        </el-card>
        <el-empty v-if="!appts.length" description="暂无预约" />
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

      <el-tab-pane label="我的住院" name="inp">
        <el-card v-for="a in admissions" :key="a.id as number" class="item" shadow="never"
                 :class="{ picked: admission && admission.id === a.id }" @click="pickAdmission(a)">
          <div class="row">
            <div>
              <b>{{ a.deptName }}</b>
              <span class="muted">{{ a.bedNo ? a.bedNo + '床 · ' : '' }}{{ a.admissionNo }}</span>
              <div class="muted">入院 {{ String(a.admitAt).slice(0, 10) }} · {{ a.admitDiagName }}</div>
            </div>
            <el-tag size="small" :type="a.status === 'IN_HOSPITAL' ? 'success' : 'info'">
              {{ a.status === 'IN_HOSPITAL' ? '在院' : '已出院' }}
            </el-tag>
          </div>
        </el-card>
        <el-empty v-if="!admissions.length" description="暂无住院记录" />

        <template v-if="admission && account">
          <!-- 余额条：欠费（余额为负）标红提示，口径同院内住院账户 -->
          <el-card class="item" shadow="never" :class="account.owed ? 'owe' : ''">
            <div class="row">
              <div>
                <b :class="{ abnormal: account.owed }">余额 ¥{{ account.balance }}</b>
                <span class="muted">押金 ¥{{ account.depositTotal }} · 已发生 ¥{{ account.executedAmount }}</span>
              </div>
              <el-tag size="small" :type="account.owed ? 'danger' : 'success'">
                {{ account.owed ? '已欠费，请及时补交押金' : '余额充足' }}
              </el-tag>
            </div>
            <div v-if="Number(account.pendingAmount) > 0" class="muted">
              待执行医嘱预计 ¥{{ account.pendingAmount }}（不计入余额）
            </div>
          </el-card>

          <el-date-picker v-model="feeDate" type="date" value-format="YYYY-MM-DD" :clearable="false"
                          style="width: 100%; margin-bottom: 8px" @change="loadDailyFees" />
          <el-card class="item" shadow="never">
            <b>{{ feeDate }} 费用清单</b>
            <el-table :data="dailyFees" size="small" style="margin-top: 6px">
              <el-table-column prop="itemName" label="项目" />
              <el-table-column prop="qty" label="数量" width="56" />
              <el-table-column prop="amount" label="金额" width="80" />
            </el-table>
            <div class="row" style="margin-top: 6px">
              <span class="muted">共 {{ dailyFees.length }} 项</span>
              <b>当日合计 ¥{{ feeTotal }}</b>
            </div>
          </el-card>
        </template>
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
import { todayLocal } from '../../utils/date'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { portalClient as portal } from '../../api/client'

const router = useRouter()
const name = localStorage.getItem('hip_portal_name') ?? ''
const tab = ref('book')
const date = ref(todayLocal())
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
// v40 分时段预约
const slots = ref<Record<string, unknown>[]>([])
const slotScheduleId = ref<unknown>(null)
const appts = ref<Record<string, unknown>[]>([])
// v40 我的住院
const admissions = ref<Record<string, unknown>[]>([])
const admission = ref<Record<string, unknown> | null>(null)
const account = ref<Record<string, unknown> | null>(null)
const feeDate = ref(todayLocal())
const dailyFees = ref<Record<string, unknown>[]>([])
const feeTotal = ref<unknown>(0)

const regNames: Record<string, string> = { REGISTERED: '已挂号', CANCELLED: '已退号', VISITED: '已就诊' }
const regTag: Record<string, string> = { REGISTERED: 'success', CANCELLED: 'info', VISITED: '' }
const apptNames: Record<string, string> = { BOOKED: '待就诊', CHECKED_IN: '已签到', CANCELLED: '已取消' }
const apptTag: Record<string, string> = { BOOKED: 'success', CHECKED_IN: '', CANCELLED: 'info' }

async function loadSchedules() {
  schedules.value = (await portal.get('/schedules', { params: { date: date.value } })).data.data
}

async function loadMine() {
  const [r, l, c, e, b, a, adm] = await Promise.all([
    portal.get('/my/registrations'), portal.get('/my/lab-reports'), portal.get('/my/charges'),
    portal.get('/my/exam-reports'), portal.get('/my/pending-bill'),
    portal.get('/my/appointments'), portal.get('/my/admissions'),
  ])
  regs.value = r.data.data
  labs.value = l.data.data
  charges.value = c.data.data
  examReports.value = e.data.data
  bills.value = b.data.data
  appts.value = a.data.data
  admissions.value = adm.data.data
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

/** 展开/收起某排班的时段（再次点击同一排班即收起） */
async function openSlots(s: Record<string, unknown>) {
  if (slotScheduleId.value === s.id) {
    slotScheduleId.value = null
    slots.value = []
    return
  }
  slotScheduleId.value = s.id
  slots.value = (await portal.get(`/schedules/${s.id}/slots`)).data.data
}

/** 分时段预约：只传 slotId——患者身份由后端从令牌取，前端不传也不该传 */
async function bookSlot(t: Record<string, unknown>) {
  const resp = await portal.post('/appointments', { slotId: t.id })
  ElMessage.success(`预约成功：第 ${resp.data.data.apptNo} 号，请按时段到院`)
  const sid = slotScheduleId.value
  slots.value = (await portal.get(`/schedules/${sid}/slots`)).data.data
  await Promise.all([loadSchedules(), loadMine()])
  tab.value = 'appts'
}

async function cancelAppt(a: Record<string, unknown>) {
  await portal.post(`/my/appointments/${a.id}/cancel`)
  ElMessage.success('已取消，号源已释放')
  await Promise.all([loadSchedules(), loadMine()])
  if (slotScheduleId.value !== null) {
    slots.value = (await portal.get(`/schedules/${slotScheduleId.value}/slots`)).data.data
  }
}

async function pickAdmission(a: Record<string, unknown>) {
  admission.value = a
  await Promise.all([loadAccount(), loadDailyFees()])
}

async function loadAccount() {
  if (!admission.value) return
  account.value = (await portal.get(`/my/admissions/${admission.value.id}/account`)).data.data
}

async function loadDailyFees() {
  if (!admission.value) return
  const resp = await portal.get(`/my/admissions/${admission.value.id}/daily-fees`,
    { params: { date: feeDate.value } })
  dailyFees.value = resp.data.data.rows
  feeTotal.value = resp.data.data.total
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
.slots { margin-top: 8px; border-top: 1px dashed #dcdfe6; padding-top: 6px; }
.slot-line { padding: 4px 0; }
.picked { border-color: #16786f; }
.owe { background: #fef0f0; }
.muted { color: #999; font-size: 12px; margin-left: 8px; }
.abnormal { color: #d03050; font-weight: 600; }
</style>
