<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="病历时限质控" name="emr">
        <el-button size="small" @click="loadEmr">重新核查</el-button>
        <el-descriptions :column="3" border size="small" style="margin: 10px 0">
          <el-descriptions-item label="缺陷总数">{{ emr.defectTotal ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="门诊病历未签名">{{ emr.unsignedOutpEmrCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="缺出院小结">{{ (emr.missingDischargeSummary as unknown[])?.length ?? '-' }}</el-descriptions-item>
        </el-descriptions>
        <h4>入院超 24h 未书写入院记录</h4>
        <el-table :data="(emr.missingAdmissionRecord as Record<string, unknown>[]) ?? []" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="170" />
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="dept_name" label="科室" width="120" />
          <el-table-column prop="hours" label="已入院(小时)" width="110" />
        </el-table>
        <h4 style="margin-top: 12px">出院未书写出院小结</h4>
        <el-table :data="(emr.missingDischargeSummary as Record<string, unknown>[]) ?? []" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="170" />
          <el-table-column prop="patient_name" label="患者" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="不良事件" name="adverse">
        <el-form inline size="small">
          <el-form-item><el-select v-model="ae.type" style="width: 130px">
            <el-option v-for="x in ['跌倒', '给药错误', '压疮', '管路滑脱', '其他']" :key="x" :label="x" :value="x" />
          </el-select></el-form-item>
          <el-form-item label="分级"><el-input-number v-model="ae.level" :min="1" :max="4" /></el-form-item>
          <el-form-item><el-date-picker v-model="ae.occurredOn" type="date" value-format="YYYY-MM-DD" placeholder="发生日期" /></el-form-item>
          <el-form-item><el-input v-model="ae.description" placeholder="事件描述" style="width: 240px" /></el-form-item>
          <el-form-item><el-checkbox v-model="ae.anonymous">匿名</el-checkbox></el-form-item>
          <el-button type="danger" size="small" @click="reportAe">上报</el-button>
        </el-form>
        <el-table :data="aeList" size="small" border>
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column prop="level" label="级" width="50" />
          <el-table-column prop="occurred_on" label="发生日期" width="110" />
          <el-table-column prop="description" label="描述" show-overflow-tooltip />
          <el-table-column label="上报" width="70">
            <template #default="{ row }">{{ row.anonymous ? '匿名' : '实名' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'HANDLED'" type="success" size="small">已处理</el-tag>
              <el-button v-else link type="primary" size="small" @click="handleAe(row)">处理</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="院感监测" name="infection">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 10px">
          <el-descriptions-item label="登记感染病例">{{ (inf.cases as unknown[])?.length ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="抗菌药物已执行医嘱">{{ inf.antibioticOrderCount ?? 0 }} 条</el-descriptions-item>
        </el-descriptions>
        <el-table :data="(inf.cases as Record<string, unknown>[]) ?? []" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="170" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="site" label="感染部位" width="110" />
          <el-table-column prop="pathogen" label="病原体" width="120" />
          <el-table-column prop="confirmed_on" label="确诊日期" width="110" />
          <el-table-column prop="note" label="备注" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('emr')
const emr = ref<Record<string, unknown>>({})
const aeList = ref<Record<string, unknown>[]>([])
const inf = ref<Record<string, unknown>>({})
const ae = reactive({ type: '跌倒', level: 3, occurredOn: '', description: '', anonymous: false })

async function loadEmr() {
  emr.value = (await client.get('/quality/emr-timeliness')).data.data
}
async function loadAe() {
  aeList.value = (await client.get('/quality/adverse-events')).data.data
}
async function loadInf() {
  inf.value = (await client.get('/quality/infections')).data.data
}

async function reportAe() {
  if (!ae.occurredOn || !ae.description) {
    ElMessage.warning('发生日期与描述必填')
    return
  }
  await client.post('/quality/adverse-events', ae)
  ElMessage.success('已上报')
  ae.description = ''
  await loadAe()
}

async function handleAe(row: Record<string, unknown>) {
  const { value } = await ElMessageBox.prompt('处理意见', '处理不良事件', { inputValue: '已核实并整改' })
  await client.put(`/quality/adverse-events/${row.id}/handle`, null, { params: { note: value } })
  await loadAe()
}

onMounted(() => {
  loadEmr()
  loadAe()
  loadInf()
})
</script>
