<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="病组分析" name="analysis">
        <el-button type="primary" size="small" @click="groupAll">批量入组（已出院未入组）</el-button>
        <el-descriptions v-if="analysis" :column="4" border size="small" style="margin: 10px 0">
          <el-descriptions-item label="入组病例">{{ analysis.cases }} 例</el-descriptions-item>
          <el-descriptions-item label="总权重">{{ analysis.totalWeight }}</el-descriptions-item>
          <el-descriptions-item label="CMI">
            <b style="color: #2563eb">{{ analysis.cmi }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="歧义病例(QY)">{{ analysis.ambiguous }} 例</el-descriptions-item>
        </el-descriptions>
        <el-table v-if="analysis" :data="analysis.groups" size="small" border>
          <el-table-column prop="drg_code" label="DRG 组" width="90" />
          <el-table-column prop="drg_name" label="组名" show-overflow-tooltip />
          <el-table-column prop="weight" label="权重 RW" width="100" />
          <el-table-column prop="cases" label="例数" width="80" />
          <el-table-column prop="avg_cost" label="次均费用" width="110" />
          <el-table-column prop="avg_days" label="平均住院日" width="110" />
          <el-table-column label="费用消耗指数" width="130">
            <template #default="{ row }">
              <el-tag v-if="row.cost_index != null" size="small"
                      :type="Number(row.cost_index) > 1 ? 'danger' : 'success'">{{ row.cost_index }}</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="入组明细" name="cases">
        <el-table :data="cases" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="140" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="admit_diag_icd" label="主诊断" width="90" />
          <el-table-column prop="admit_diag_name" label="诊断名称" show-overflow-tooltip />
          <el-table-column prop="drg_code" label="DRG" width="70" />
          <el-table-column prop="drg_name" label="病组" show-overflow-tooltip />
          <el-table-column prop="weight" label="权重" width="90" />
          <el-table-column prop="total_cost" label="费用" width="90" />
          <el-table-column prop="inp_days" label="住院日" width="80" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="分组定义" name="defs">
        <el-alert title="CHS-DRG 简化规则：主诊断 ICD 前缀入组，同前缀按有无手术操作分列；正式分组器接入后本表由官方目录替换"
                  type="info" show-icon :closable="false" style="margin-bottom: 8px" />
        <el-table :data="groups" size="small" border>
          <el-table-column prop="mdc_code" label="MDC" width="70" />
          <el-table-column prop="mdc_name" label="大类" width="130" />
          <el-table-column prop="drg_code" label="DRG" width="70" />
          <el-table-column prop="drg_name" label="组名" show-overflow-tooltip />
          <el-table-column prop="icd_prefixes" label="ICD 前缀" width="180" />
          <el-table-column label="手术组" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.surgical" type="danger" size="small">手术</el-tag>
              <el-tag v-else type="info" size="small">内科</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="weight" label="权重 RW" width="100" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

interface Analysis {
  groups: Record<string, unknown>[]
  cases: number
  ambiguous: number
  totalWeight: string
  cmi: string
}

const tab = ref('analysis')
const analysis = ref<Analysis | null>(null)
const cases = ref<Record<string, unknown>[]>([])
const groups = ref<Record<string, unknown>[]>([])

async function loadAnalysis() { analysis.value = (await client.get('/drg/analysis')).data.data }
async function loadCases() { cases.value = (await client.get('/drg/cases')).data.data }
async function loadGroups() { groups.value = (await client.get('/drg/groups')).data.data }

async function groupAll() {
  const r = (await client.post('/drg/group-all')).data.data
  ElMessage.success(`入组完成：${r.grouped} 例入组，${r.ambiguous} 例歧义(QY)`)
  await loadAnalysis()
}

watch(tab, (t) => {
  if (t === 'cases') loadCases()
  if (t === 'defs') loadGroups()
})
onMounted(loadAnalysis)
</script>
