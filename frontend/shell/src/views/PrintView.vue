<template>
  <div class="print-page">
    <div v-if="data" class="ticket">
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
      <hr />
      <p class="foot">打印时间：{{ now }}　本单据仅作就诊凭证</p>
      <el-button class="no-print" type="primary" @click="doPrint">打 印</el-button>
    </div>
    <el-empty v-else description="加载中或单据不存在" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import client from '../api/client'

const route = useRoute()
const type = String(route.query.type ?? 'registration')
const id = String(route.query.id ?? '')
const data = ref<Record<string, unknown> | null>(null)
const hospitalName = ref('')
const now = new Date().toLocaleString('zh-CN')

const titles: Record<string, string> = { registration: '挂号凭条', charge: '收费票据', 'lab-report': '检验报告单' }
const payNames: Record<string, string> = { CASH: '现金', WECHAT: '微信', ALIPAY: '支付宝', YB: '医保' }

function doPrint() {
  window.print()
}

onMounted(async () => {
  const [resp, cfg] = await Promise.all([
    client.get(`/print/${type}/${id}`),
    client.get('/config/public'),
  ])
  data.value = resp.data.data
  hospitalName.value = cfg.data.data.hospital_name ?? ''
})
</script>

<style scoped>
.print-page { display: flex; justify-content: center; padding: 24px; background: #fff; min-height: 100%; }
.ticket { width: 420px; font-size: 13px; }
h2, h3 { text-align: center; margin: 4px 0; }
.items { width: 100%; border-collapse: collapse; margin: 8px 0; }
.items th, .items td { border: 1px solid #999; padding: 4px 6px; text-align: left; }
.big { font-size: 18px; }
.abnormal { color: #d03050; font-weight: 700; }
.foot { color: #888; font-size: 11px; }
@media print {
  .no-print { display: none; }
}
</style>
