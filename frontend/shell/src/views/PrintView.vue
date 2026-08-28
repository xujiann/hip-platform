<template>
  <div class="print-page">
    <div v-if="data" class="ticket" :class="{ sheet: isSheet }">
      <h2>{{ hospitalName }}</h2>
      <h3>{{ titles[type] }}</h3>
      <hr />
      <template v-if="type === 'registration'">
        <p>患者：{{ data.patient_name }}（{{ data.patient_no }}）</p>
        <p>科室：{{ data.dept_name }} · 第 <b class="big">{{ data.reg_no }}</b> 号</p>
        <p>就诊日期：{{ data.visit_date }}　挂号费：¥{{ data.fee }}</p>
      </template>
      <template v-else-if="type === 'charge'">
        <p>患者：{{ data.patient_name }}（{{ data.patient_no }}）</p>
        <p>结算单号：{{ data.charge_no }}</p>
        <table class="items">
          <tr><th>项目</th><th>数量</th><th>金额</th></tr>
          <tr v-for="(i, idx) in (data.items as Record<string, unknown>[])" :key="idx">
            <td>{{ i.item_name }}</td><td>{{ i.qty }}</td><td>¥{{ i.amount }}</td>
          </tr>
        </table>
        <p class="big">合计：¥{{ data.total_amount }}（{{ payNames[String(data.pay_method)] }}）</p>
      </template>
      <template v-else-if="type === 'lab-report'">
        <p>患者：{{ data.patient_name }}（{{ data.patient_no }}）　项目：{{ data.item_name }}</p>
        <p>申请单号：{{ data.group_no }}</p>
        <table class="items">
          <tr><th>项目</th><th>结果</th><th>单位</th><th>参考范围</th><th>标志</th></tr>
          <tr v-for="(r, idx) in (data.results as Record<string, unknown>[])" :key="idx">
            <td>{{ r.item_name }}</td>
            <td :class="{ abnormal: r.abnormal_flag && r.abnormal_flag !== 'N' }">{{ r.result_value }}</td>
            <td>{{ r.unit }}</td><td>{{ r.ref_range }}</td><td>{{ r.abnormal_flag }}</td>
          </tr>
        </table>
      </template>
      <!-- 收尾环·打印1：住院费用一日清单（患者可拿走的凭据） -->
      <template v-else-if="type === 'inp-daily-fee'">
        <p>患者：{{ data.patient_name }}（{{ data.patient_no }}）　住院号：{{ data.admission_no }}</p>
        <p>科室：{{ data.dept_name }} · {{ data.ward_name }} {{ data.bed_no }}床　日期：{{ data.date }}</p>
        <table class="items">
          <tr><th>项目</th><th>规格</th><th>数量</th><th>单价</th><th>金额</th></tr>
          <tr v-for="(r, idx) in (data.rows as Record<string, unknown>[])" :key="idx">
            <td>{{ r.item_name }}</td><td>{{ r.spec }}</td><td>{{ r.qty }}</td>
            <td>¥{{ r.unit_price }}</td><td>¥{{ r.amount }}</td>
          </tr>
          <tr v-if="!(data.rows as unknown[]).length"><td colspan="5" style="text-align:center">当日无已执行费用</td></tr>
        </table>
        <p class="big">当日合计：¥{{ data.dayTotal }}</p>
        <p>已交押金：¥{{ data.depositTotal }}　　已发生费用：¥{{ data.executedTotal }}</p>
        <p :class="{ owed: data.owed }">
          押金余额：¥{{ data.balance }}
          <span v-if="data.owed">（已欠费，请及时续交押金）</span>
        </p>
      </template>
      <!-- 收尾环·打印2：出院小结 -->
      <template v-else-if="type === 'inp-discharge-summary'">
        <p>患者：{{ data.patient_name }}（{{ data.patient_no }}）　性别：{{ sexName }}</p>
        <p>住院号：{{ data.admission_no }}　科室：{{ data.dept_name }}　主管医生：{{ data.doctor_name }}</p>
        <p>入院日期：{{ fmtDate(data.admit_at) }}　　出院日期：{{ fmtDate(data.discharged_at) }}</p>
        <p>入院诊断：{{ data.admit_diag_name }} {{ data.admit_diag_icd ? '(' + data.admit_diag_icd + ')' : '' }}</p>
        <p>出院诊断：{{ data.discharge_diag_name || data.admit_diag_name }}
          {{ data.discharge_diag_icd ? '(' + data.discharge_diag_icd + ')' : '' }}</p>
        <p v-if="otherDiag.length">其他诊断：{{ otherDiag.map((d) => `${d.name}(${d.icd})`).join('；') }}</p>
        <hr />
        <div class="section">
          <b>诊疗经过</b>
          <el-empty v-if="!records.length" description="无病历记录" :image-size="60" />
          <div v-for="(r, idx) in records" :key="idx" class="rec">
            <p class="rec-h">{{ recordTypeNames[String(r.record_type)] ?? r.record_type }} · {{ fmtDate(r.created_at) }}
              <b>{{ r.title }}</b></p>
            <p class="rec-c">{{ r.content }}</p>
          </div>
        </div>
        <div v-if="meds.length" class="section">
          <b>出院医嘱（带药）</b>
          <table class="items">
            <tr><th>药品</th><th>规格</th><th>用法</th><th>数量</th></tr>
            <tr v-for="(mm, idx) in meds" :key="idx">
              <td>{{ mm.item_name }}</td><td>{{ mm.spec }}</td>
              <td>{{ mm.usage_route }} {{ mm.dose_per_time }} {{ mm.frequency }}</td><td>{{ mm.qty }}</td>
            </tr>
          </table>
        </div>
      </template>
      <hr />
      <p class="foot">打印时间：{{ now }}　本单据仅作就诊凭证</p>
      <el-button class="no-print" type="primary" @click="doPrint">打 印</el-button>
    </div>
    <el-empty v-else description="加载中或单据不存在" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import client from '../api/client'

const route = useRoute()
const type = String(route.query.type ?? 'registration')
const id = String(route.query.id ?? '')
const date = String(route.query.date ?? '')
const data = ref<Record<string, unknown> | null>(null)
const hospitalName = ref('')
const now = new Date().toLocaleString('zh-CN')

const titles: Record<string, string> = {
  registration: '挂号凭条', charge: '收费票据', 'lab-report': '检验报告单',
  'inp-daily-fee': '住院费用一日清单', 'inp-discharge-summary': '出院小结',
}
const payNames: Record<string, string> = { CASH: '现金', WECHAT: '微信', ALIPAY: '支付宝', YB: '医保' }
const recordTypeNames: Record<string, string> = { ADMISSION: '入院记录', PROGRESS: '病程记录', DISCHARGE: '出院小结' }

// 住院单据用较宽版式（A5 清单/小结），门诊凭条保持窄条
const isSheet = computed(() => type === 'inp-daily-fee' || type === 'inp-discharge-summary')
const sexName = computed(() => ({ M: '男', F: '女' } as Record<string, string>)[String(data.value?.sex)] ?? '')
const otherDiag = computed(() => (data.value?.otherDiagnoses as { icd: string; name: string }[]) ?? [])
const records = computed(() => (data.value?.records as Record<string, unknown>[]) ?? [])
const meds = computed(() => (data.value?.meds as Record<string, unknown>[]) ?? [])

function fmtDate(v: unknown): string {
  if (!v) return '—'
  return String(v).slice(0, 10)
}

// 住院打印数据集在 inpatient 端点，门诊沿用 /print/{type}/{id}
function endpoint(): string {
  if (type === 'inp-daily-fee') return `/inpatient/admissions/${id}/print/daily-fee?date=${date}`
  if (type === 'inp-discharge-summary') return `/inpatient/admissions/${id}/print/discharge-summary`
  return `/print/${type}/${id}`
}

function doPrint() {
  window.print()
}

onMounted(async () => {
  const [resp, cfg] = await Promise.all([
    client.get(endpoint()),
    client.get('/config/public'),
  ])
  data.value = resp.data.data
  hospitalName.value = cfg.data.data.hospital_name ?? ''
})
</script>

<style scoped>
.print-page { display: flex; justify-content: center; padding: 24px; background: #fff; min-height: 100%; }
.ticket { width: 420px; font-size: 13px; }
.ticket.sheet { width: 640px; }
h2, h3 { text-align: center; margin: 4px 0; }
.items { width: 100%; border-collapse: collapse; margin: 8px 0; }
.items th, .items td { border: 1px solid #999; padding: 4px 6px; text-align: left; }
.big { font-size: 18px; }
.abnormal { color: #d03050; font-weight: 700; }
.owed { color: #d03050; font-weight: 700; }
.foot { color: #888; font-size: 11px; }
.section { margin: 10px 0; }
.rec { margin: 6px 0; }
.rec-h { color: #666; font-size: 12px; margin: 2px 0; }
.rec-c { white-space: pre-wrap; margin: 2px 0 0; }
@media print {
  .no-print { display: none; }
}
</style>
