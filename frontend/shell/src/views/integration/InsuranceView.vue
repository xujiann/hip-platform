<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="医保汇总" name="summary">
        <el-descriptions v-if="summary" :column="3" border size="small">
          <el-descriptions-item label="今日医保结算">
            {{ summary.outpToday.cnt }} 笔 / ￥{{ summary.outpToday.amount }}
          </el-descriptions-item>
          <el-descriptions-item label="今日统筹支付">￥{{ summary.splitToday.fund_pay }}</el-descriptions-item>
          <el-descriptions-item label="今日个人支付">￥{{ summary.splitToday.self_pay }}</el-descriptions-item>
          <el-descriptions-item label="今日审核提醒">{{ summary.auditWarns }} 条</el-descriptions-item>
          <el-descriptions-item label="目录对照数">{{ summary.mappedCount }} 项</el-descriptions-item>
          <el-descriptions-item label="最近对账">
            <template v-if="summary.lastRecon.length">
              {{ summary.lastRecon[0].recon_date }}：{{ summary.lastRecon[0].matched_cnt }}/{{ summary.lastRecon[0].total_cnt }} 一致
              <el-tag :type="summary.lastRecon[0].diff_cnt === 0 ? 'success' : 'danger'" size="small">
                差异 {{ summary.lastRecon[0].diff_cnt }}</el-tag>
            </template>
            <span v-else>尚未对账</span>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert style="margin-top: 10px" type="info" show-icon :closable="false"
                  title="当前为 Mock 医保通道：结算/退费/分割/对账全流程可用，接入真实医保 SDK 后仅需替换 InsuranceAdapter 实现" />
      </el-tab-pane>

      <el-tab-pane label="目录对照" name="catalog">
        <el-form inline size="small">
          <el-form-item>
            <el-select v-model="mapForm.itemType" style="width: 100px">
              <el-option label="药品" value="DRUG" /><el-option label="诊疗" value="ITEM" />
            </el-select>
          </el-form-item>
          <el-form-item><el-input v-model="mapForm.itemCode" placeholder="本地编码" style="width: 110px" /></el-form-item>
          <el-form-item><el-input v-model="mapForm.itemName" placeholder="名称" style="width: 150px" /></el-form-item>
          <el-form-item><el-input v-model="mapForm.ybCode" placeholder="医保国码" style="width: 150px" /></el-form-item>
          <el-form-item>
            <el-select v-model="mapForm.chargeClass" style="width: 100px">
              <el-option label="甲类 A" value="A" /><el-option label="乙类 B" value="B" /><el-option label="丙类 C" value="C" />
            </el-select>
          </el-form-item>
          <el-form-item label="自付比">
            <el-input-number v-model="mapForm.selfRatio" :min="0" :max="1" :step="0.05" style="width: 110px" />
          </el-form-item>
          <el-button type="primary" size="small" @click="saveMapping">保存对照</el-button>
        </el-form>
        <div style="margin: 4px 0 10px; display: flex; gap: 12px; align-items: center; flex-wrap: wrap;">
          <span v-if="catalogStats" style="font-size: 13px;">
            对照率：药品 {{ catalogStats.mapped_drugs }}/{{ catalogStats.total_drugs }}，
            诊疗 {{ catalogStats.mapped_items }}/{{ catalogStats.total_items }}（合计 {{ mappedRate }}%）
          </span>
          <el-button size="small" @click="csvInput?.click()">CSV 批量导入</el-button>
          <span style="font-size: 12px; color: #909399;">
            列：item_type,item_code,item_name,yb_code,charge_class,self_ratio[,effective_date]（模板 tools/migrate-templates/yb-catalog.csv）
          </span>
          <input ref="csvInput" type="file" accept=".csv,text/csv" style="display: none" @change="importCsv" />
        </div>
        <el-row :gutter="16">
          <el-col :span="15">
            <h4>已对照（{{ mapped.length }}）</h4>
            <el-table :data="mapped" size="small" border max-height="420">
              <el-table-column prop="item_type" label="类型" width="70" />
              <el-table-column prop="item_code" label="编码" width="90" />
              <el-table-column prop="item_name" label="名称" show-overflow-tooltip />
              <el-table-column prop="yb_code" label="医保国码" width="150" />
              <el-table-column label="类别" width="70">
                <template #default="{ row }">
                  <el-tag :type="row.charge_class === 'A' ? 'success' : row.charge_class === 'B' ? 'warning' : 'info'"
                          size="small">{{ row.charge_class }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="self_ratio" label="自付比" width="80" />
            </el-table>
          </el-col>
          <el-col :span="9">
            <h4>未对照（结算按丙类自费）</h4>
            <el-table :data="unmapped" size="small" border max-height="420">
              <el-table-column prop="code" label="编码" width="90" />
              <el-table-column prop="name" label="名称" show-overflow-tooltip />
              <el-table-column prop="kind" label="类型" width="70" />
            </el-table>
          </el-col>
        </el-row>
      </el-tab-pane>

      <el-tab-pane label="结算分割" name="splits">
        <el-date-picker v-model="splitDate" type="date" value-format="YYYY-MM-DD" size="small"
                        style="width: 150px" @change="loadSplits" />
        <el-table :data="splits" size="small" border style="margin-top: 8px" @row-click="showDetail">
          <el-table-column prop="charge_no" label="结算单号" width="170" />
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="insurance_type" label="参保类型" width="110" />
          <el-table-column prop="total" label="总额" width="90" />
          <el-table-column prop="class_a" label="甲类" width="80" />
          <el-table-column prop="class_b" label="乙类" width="80" />
          <el-table-column prop="class_c" label="丙类" width="80" />
          <el-table-column prop="deductible_pay" label="起付线" width="80" />
          <el-table-column label="统筹支付" width="100">
            <template #default="{ row }"><b style="color: #2563eb">￥{{ row.fund_pay }}</b></template>
          </el-table-column>
          <el-table-column prop="self_pay" label="个人支付" width="90" />
        </el-table>
        <el-alert v-if="splits.length" title="点击行可查看行级分割明细" type="info" :closable="false" style="margin-top: 6px" />
      </el-tab-pane>

      <el-tab-pane label="审核提醒" name="audits">
        <el-table :data="audits" size="small" border>
          <el-table-column prop="charge_no" label="结算单号" width="170" />
          <el-table-column prop="rule_code" label="规则" width="80" />
          <el-table-column label="级别" width="80">
            <template #default="{ row }">
              <el-tag :type="row.level === 'BLOCK' ? 'danger' : 'warning'" size="small">{{ row.level }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="提示" show-overflow-tooltip />
          <el-table-column prop="created_at" label="时间" width="170" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="医保对账" name="recon">
        <el-form inline size="small">
          <el-date-picker v-model="reconDate" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
          <el-button type="primary" size="small" style="margin-left: 8px" @click="runRecon">试对账</el-button>
          <el-button type="success" size="small" @click="saveRecon">对账并留痕</el-button>
        </el-form>
        <template v-if="recon">
          <el-descriptions :column="3" border size="small" style="margin: 8px 0">
            <el-descriptions-item label="应对账">{{ recon.total }} 笔</el-descriptions-item>
            <el-descriptions-item label="一致">{{ recon.matched }} 笔</el-descriptions-item>
            <el-descriptions-item label="差异">
              <el-tag :type="recon.diff === 0 ? 'success' : 'danger'" size="small">{{ recon.diff }} 笔</el-tag>
            </el-descriptions-item>
          </el-descriptions>
          <el-table :data="recon.rows" size="small" border>
            <el-table-column prop="biz_type" label="业务" width="70" />
            <el-table-column prop="charge_no" label="单号" width="180" />
            <el-table-column prop="amount" label="金额" width="90" />
            <el-table-column prop="local_status" label="本地状态" width="100" />
            <el-table-column label="结算报文" width="90">
              <template #default="{ row }">{{ row.has_settle_msg ? '✓' : '✗' }}</template>
            </el-table-column>
            <el-table-column label="退费报文" width="90">
              <template #default="{ row }">{{ row.has_refund_msg ? '✓' : '—' }}</template>
            </el-table-column>
            <el-table-column label="结果" width="90">
              <template #default="{ row }">
                <el-tag :type="row.consistent ? 'success' : 'danger'" size="small">
                  {{ row.consistent ? '一致' : '差异' }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <h4>对账批次</h4>
        <el-table :data="batches" size="small" border>
          <el-table-column prop="recon_date" label="对账日" width="120" />
          <el-table-column prop="total_cnt" label="总笔数" width="90" />
          <el-table-column prop="matched_cnt" label="一致" width="80" />
          <el-table-column prop="diff_cnt" label="差异" width="80" />
          <el-table-column prop="detail" label="差异单号" show-overflow-tooltip />
          <el-table-column prop="created_at" label="对账时间" width="170" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

interface Recon { rows: Record<string, unknown>[]; total: number; matched: number; diff: number }

const tab = ref('summary')
const today = new Date().toISOString().slice(0, 10)
const summary = ref<Record<string, any> | null>(null)
const mapped = ref<Record<string, unknown>[]>([])
const unmappedRaw = ref<{ drugs: Record<string, unknown>[]; items: Record<string, unknown>[] }>({ drugs: [], items: [] })
const splits = ref<Record<string, unknown>[]>([])
const audits = ref<Record<string, unknown>[]>([])
const recon = ref<Recon | null>(null)
const batches = ref<Record<string, unknown>[]>([])
const splitDate = ref(today)
const reconDate = ref(today)
const mapForm = reactive({ itemType: 'DRUG', itemCode: '', itemName: '', ybCode: '', chargeClass: 'A', selfRatio: 0 })
const catalogStats = ref<Record<string, number> | null>(null)
const csvInput = ref<HTMLInputElement>()

const mappedRate = computed(() => {
  const s = catalogStats.value
  if (!s) return 0
  const total = s.total_drugs + s.total_items
  return total === 0 ? 0 : Math.round(((s.mapped_drugs + s.mapped_items) / total) * 1000) / 10
})

const unmapped = computed(() => [
  ...unmappedRaw.value.drugs.map((d) => ({ ...d, kind: '药品' })),
  ...unmappedRaw.value.items.map((i) => ({ ...i, kind: '诊疗' })),
])

async function loadSummary() { summary.value = (await client.get('/insurance/summary')).data.data }
async function loadCatalog() {
  const d = (await client.get('/insurance/catalog')).data.data
  mapped.value = d.mapped
  unmappedRaw.value = { drugs: d.unmappedDrugs, items: d.unmappedItems }
  catalogStats.value = d.stats
}

async function importCsv(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  const text = await file.text()
  const r = (await client.post('/insurance/catalog/import', text,
    { headers: { 'Content-Type': 'text/plain' } })).data.data
  if (r.errorCount > 0) {
    ElMessageBox.alert(r.errors.join('<br>'), `导入 ${r.imported} 条，${r.errorCount} 行有误`,
      { dangerouslyUseHTMLString: true })
  } else {
    ElMessage.success(`导入 ${r.imported} 条`)
  }
  if (csvInput.value) csvInput.value.value = ''
  await loadCatalog()
}
async function loadSplits() { splits.value = (await client.get('/insurance/splits', { params: { date: splitDate.value } })).data.data }
async function loadAudits() { audits.value = (await client.get('/insurance/audits')).data.data }
async function loadBatches() { batches.value = (await client.get('/insurance/reconcile/batches')).data.data }

async function saveMapping() {
  if (!mapForm.itemCode || !mapForm.ybCode) return
  await client.post('/insurance/catalog', mapForm)
  ElMessage.success('已保存')
  await loadCatalog()
}
function showDetail(row: Record<string, unknown>) {
  // 批次二起明细含 eligible（可报销基数，统筹在账单级计算）；旧数据仍是行级 fund
  const items = JSON.parse(String(row.detail || '[]')) as
    { item: string; class: string; amount: number; eligible?: number; fund?: number }[]
  ElMessageBox.alert(
    items.map((i) => `${i.item}（${i.class}类）￥${i.amount} → ${
      i.eligible !== undefined ? `可报销 ￥${i.eligible}` : `统筹 ￥${i.fund}`}`).join('<br>'),
    `分割明细 ${row.charge_no}`, { dangerouslyUseHTMLString: true })
}
async function runRecon() { recon.value = (await client.get('/insurance/reconcile', { params: { date: reconDate.value } })).data.data }
async function saveRecon() {
  const r = (await client.post('/insurance/reconcile', null, { params: { date: reconDate.value } })).data.data
  ElMessage.success(`对账完成：${r.matched}/${r.total} 一致，差异 ${r.diff}`)
  await Promise.all([runRecon(), loadBatches()])
}

watch(tab, (t) => {
  if (t === 'catalog') loadCatalog()
  if (t === 'splits') loadSplits()
  if (t === 'audits') loadAudits()
  if (t === 'recon') loadBatches()
})
onMounted(loadSummary)
</script>
