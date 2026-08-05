<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="提醒留痕" name="alerts">
        <el-table :data="alerts" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="rule_type" label="类型" width="80" />
          <el-table-column label="级别" width="90">
            <template #default="{ row }">
              <el-tag :type="row.severity === 'FORBID' ? 'danger' : 'warning'" size="small">{{ row.severity }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="提示内容" show-overflow-tooltip />
          <el-table-column prop="created_at" label="时间" width="170" />
        </el-table>
        <el-alert title="FORBID 级规则在开单时直接拦截（不留此表）；此处为 CAUTION 级提醒留痕"
                  type="info" show-icon :closable="false" style="margin-top: 8px" />
      </el-tab-pane>

      <el-tab-pane label="规则库" name="rules">
        <template v-if="rules">
          <h4>药物相互作用（DDI）
            <el-button link type="primary" size="small" @click="showAddDdi = !showAddDdi">新增规则</el-button>
          </h4>
          <el-form v-if="showAddDdi" inline size="small">
            <el-form-item><el-input v-model="ddi.drugA" placeholder="药品A关键词" style="width: 130px" /></el-form-item>
            <el-form-item><el-input v-model="ddi.drugB" placeholder="药品B关键词" style="width: 130px" /></el-form-item>
            <el-form-item>
              <el-select v-model="ddi.severity" style="width: 120px">
                <el-option label="禁用拦截" value="FORBID" /><el-option label="提醒" value="CAUTION" />
              </el-select>
            </el-form-item>
            <el-form-item><el-input v-model="ddi.message" placeholder="提示语" style="width: 260px" /></el-form-item>
            <el-button type="primary" size="small" @click="addDdi">保存</el-button>
          </el-form>
          <el-table :data="rules.ddi" size="small" border>
            <el-table-column prop="drug_a" label="药品A" width="120" />
            <el-table-column prop="drug_b" label="药品B" width="120" />
            <el-table-column label="级别" width="90">
              <template #default="{ row }">
                <el-tag :type="row.severity === 'FORBID' ? 'danger' : 'warning'" size="small">{{ row.severity }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="message" label="提示" show-overflow-tooltip />
          </el-table>
          <h4>疗程上限</h4>
          <el-table :data="rules.dose" size="small" border>
            <el-table-column prop="drug_keyword" label="药品" width="140" />
            <el-table-column prop="max_days" label="上限(天)" width="90" />
            <el-table-column prop="message" label="提示" show-overflow-tooltip />
          </el-table>
          <h4>年龄限制</h4>
          <el-table :data="rules.age" size="small" border>
            <el-table-column prop="drug_keyword" label="药品" width="140" />
            <el-table-column prop="min_age" label="最小年龄" width="90" />
            <el-table-column prop="max_age" label="最大年龄" width="90" />
            <el-table-column prop="message" label="提示" show-overflow-tooltip />
          </el-table>
        </template>
      </el-tab-pane>

      <el-tab-pane label="诊断建议" name="suggestions">
        <el-form inline size="small">
          <el-form-item><el-input v-model="icdQuery" placeholder="ICD 编码（如 J15.900）" style="width: 200px" /></el-form-item>
          <el-button type="primary" size="small" @click="querySuggestion">查询建议</el-button>
        </el-form>
        <el-alert v-for="s in suggestionHits" :key="s.id" :title="`[${s.icd_prefix}] ${s.content}`"
                  type="success" show-icon :closable="false" style="margin-bottom: 6px" />
        <h4>建议库</h4>
        <el-table :data="rules?.suggestions ?? []" size="small" border>
          <el-table-column prop="icd_prefix" label="ICD 前缀" width="100" />
          <el-table-column prop="content" label="建议内容" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

interface Rules {
  ddi: Record<string, unknown>[]
  dose: Record<string, unknown>[]
  age: Record<string, unknown>[]
  suggestions: Record<string, unknown>[]
}

const tab = ref('alerts')
const alerts = ref<Record<string, unknown>[]>([])
const rules = ref<Rules | null>(null)
const suggestionHits = ref<{ id: number; icd_prefix: string; content: string }[]>([])
const icdQuery = ref('J15.900')
const showAddDdi = ref(false)
const ddi = reactive({ drugA: '', drugB: '', severity: 'CAUTION', message: '' })

async function loadAlerts() { alerts.value = (await client.get('/cdss/alerts')).data.data }
async function loadRules() { rules.value = (await client.get('/cdss/rules')).data.data }

async function addDdi() {
  if (!ddi.drugA || !ddi.drugB || !ddi.message) return
  await client.post('/cdss/ddi-rules', ddi)
  ElMessage.success('已保存')
  showAddDdi.value = false
  await loadRules()
}
async function querySuggestion() {
  suggestionHits.value = (await client.get('/cdss/suggestions', { params: { icd: icdQuery.value } })).data.data
  if (!suggestionHits.value.length) ElMessage.info('该诊断暂无建议')
}

watch(tab, (t) => {
  if (t === 'rules' || t === 'suggestions') loadRules()
})
onMounted(loadAlerts)
</script>
