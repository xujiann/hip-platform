<template>
  <el-card>
    <div class="toolbar">
      <div>
        <h3 style="margin:0">费用分类报表</h3>
        <span class="hint">按费别（参保类型）与按费用类别汇总门诊 + 住院金额，可导出 CSV。</span>
      </div>
      <div style="display:flex; gap:8px; align-items:center">
        <el-date-picker v-model="month" type="month" value-format="YYYY-MM" style="width:130px"
                        placeholder="统计月份" @change="reload" />
        <el-button size="small" @click="reload">刷新</el-button>
      </div>
    </div>

    <el-tabs v-model="tab">
      <!-- ① 按费别 -->
      <el-tab-pane label="按费别汇总" name="insurance">
        <el-alert v-if="insCaveat" type="info" :closable="false" show-icon
                  title="口径说明（与后端端点同一段文字）" style="margin-bottom:12px">
          <template #default><div class="caveat">{{ insCaveat }}</div></template>
        </el-alert>

        <div class="bar">
          <span>
            门诊收入 <b>{{ money(insTotals.outp_amount) }}</b>
            ｜住院收入 <b>{{ money(insTotals.inp_amount) }}</b>
            ｜门诊退费 <b class="neg">{{ money(insTotals.outp_refund_amount) }}</b>
            ｜住院冲销 <b class="neg">{{ money(insTotals.inp_refund_amount) }}</b>
          </span>
          <el-button size="small" @click="exportCsv('insurance')">导出 CSV</el-button>
        </div>

        <el-table :data="insRows" size="small" border stripe v-loading="loading">
          <el-table-column prop="insurance_name" label="费别" width="180" />
          <el-table-column prop="outp_bills" label="门诊笔数" width="90" />
          <el-table-column label="门诊金额" width="110">
            <template #default="{ row }">{{ money(row.outp_amount) }}</template>
          </el-table-column>
          <el-table-column prop="outp_refund_bills" label="门诊退费笔数" width="110" />
          <el-table-column label="门诊退费金额" width="120">
            <template #default="{ row }"><span class="neg">{{ money(row.outp_refund_amount) }}</span></template>
          </el-table-column>
          <el-table-column prop="inp_bills" label="住院笔数" width="90" />
          <el-table-column label="住院金额" width="110">
            <template #default="{ row }">{{ money(row.inp_amount) }}</template>
          </el-table-column>
          <el-table-column label="住院冲销金额" width="120">
            <template #default="{ row }"><span class="neg">{{ money(row.inp_refund_amount) }}</span></template>
          </el-table-column>
          <el-table-column prop="total_bills" label="合计笔数" width="90" />
          <el-table-column label="合计金额" width="120">
            <template #default="{ row }"><b>{{ money(row.total_amount) }}</b></template>
          </el-table-column>
          <el-table-column label="均次费用" width="100">
            <template #default="{ row }">{{ money(row.avg_amount) }}</template>
          </el-table-column>
          <el-table-column label="占比" width="90">
            <template #default="{ row }">{{ Number(row.share_pct ?? 0).toFixed(1) }}%</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ② 按费用类别 -->
      <el-tab-pane label="按费用类别汇总" name="category">
        <el-alert v-if="catCaveat" type="info" :closable="false" show-icon
                  title="口径说明（与后端端点同一段文字）" style="margin-bottom:12px">
          <template #default><div class="caveat">{{ catCaveat }}</div></template>
        </el-alert>

        <div class="bar">
          <span style="display:flex; align-items:center; gap:8px">
            <span>费别过滤</span>
            <el-select v-model="insuranceType" size="small" style="width:150px" clearable
                       placeholder="全院" @change="loadCategory">
              <el-option label="自费" value="SELF" />
              <el-option label="职工医保" value="YB_STAFF" />
              <el-option label="居民医保" value="YB_RESIDENT" />
              <el-option label="其他/未填写" value="OTHER" />
            </el-select>
            <span>门诊 <b>{{ money(catTotals.outp_amount) }}</b> ｜住院 <b>{{ money(catTotals.inp_amount) }}</b></span>
          </span>
          <el-button size="small" @click="exportCsv('category')">导出 CSV</el-button>
        </div>

        <el-alert v-if="unclassified > 0" type="warning" :closable="false" show-icon style="margin-bottom:10px"
                  :title="`本月有 ${money(unclassified)} 元费用未挂费用类别（「未分类」行）——请到「基础数据 → 费用类别」与目录页补挂`" />

        <el-table :data="catRows" size="small" border stripe v-loading="loading">
          <el-table-column prop="category_code" label="类别码" width="120" />
          <el-table-column prop="category_name" label="费用类别" min-width="180" />
          <el-table-column prop="outp_lines" label="门诊行数" width="90" />
          <el-table-column label="门诊金额" width="120">
            <template #default="{ row }">{{ money(row.outp_amount) }}</template>
          </el-table-column>
          <el-table-column prop="inp_lines" label="住院行数" width="90" />
          <el-table-column label="住院金额" width="120">
            <template #default="{ row }">{{ money(row.inp_amount) }}</template>
          </el-table-column>
          <el-table-column label="合计金额" width="130">
            <template #default="{ row }"><b>{{ money(row.total_amount) }}</b></template>
          </el-table-column>
          <el-table-column label="占比" width="90">
            <template #default="{ row }">{{ Number(row.share_pct ?? 0).toFixed(1) }}%</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import client from '../../api/client'
import { todayLocal } from '../../utils/date'

type Row = Record<string, unknown>
type Totals = Record<string, unknown>

const month = ref(todayLocal().slice(0, 7))
const tab = ref('insurance')
const loading = ref(false)
const insuranceType = ref('')

const insRows = ref<Row[]>([])
const insTotals = ref<Totals>({})
const insCaveat = ref('')

const catRows = ref<Row[]>([])
const catTotals = ref<Totals>({})
const catCaveat = ref('')

function money(v: unknown): string {
  return Number(v ?? 0).toFixed(2)
}

/** 「未分类」行金额 = 主数据挂类欠账的量化值，超过 0 就在页面上直接催办 */
const unclassified = computed(() =>
  catRows.value
    .filter((r) => r.category_code === 'UNCLASSIFIED')
    .reduce((s, r) => s + Number(r.total_amount ?? 0), 0))

async function loadInsurance() {
  const d = (await client.get('/stats/fee-by-insurance', { params: { month: month.value } })).data.data
  insRows.value = d.rows
  insTotals.value = d.totals
  insCaveat.value = d.caveat
}

async function loadCategory() {
  const d = (await client.get('/stats/fee-by-category', {
    params: { month: month.value, insuranceType: insuranceType.value || undefined },
  })).data.data
  catRows.value = d.rows
  catTotals.value = d.totals
  catCaveat.value = d.caveat
}

async function reload() {
  loading.value = true
  try {
    await Promise.all([loadInsurance(), loadCategory()])
  } finally {
    loading.value = false
  }
}

async function exportCsv(which: 'insurance' | 'category') {
  const url = which === 'insurance' ? '/stats/fee-by-insurance.csv' : '/stats/fee-by-category.csv'
  const params: Record<string, string | undefined> = { month: month.value }
  if (which === 'category' && insuranceType.value) params.insuranceType = insuranceType.value
  const resp = await client.get(url, { params, responseType: 'blob' })
  const href = URL.createObjectURL(resp.data as Blob)
  const a = document.createElement('a')
  a.href = href
  a.download = `${which === 'insurance' ? '费别金额汇总' : '费用类别金额汇总'}_${month.value}.csv`
  a.click()
  URL.revokeObjectURL(href)
}

onMounted(reload)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 10px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; display: block; margin-top: 4px; }
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; font-size: 13px; }
.caveat { line-height: 1.7; font-size: 12px; }
.neg { color: #f56c6c; }
</style>
