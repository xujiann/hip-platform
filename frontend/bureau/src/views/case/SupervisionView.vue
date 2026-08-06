<template>
  <div v-loading="loading">
    <el-alert type="info" :closable="false" class="mb"
              title="督办看板：按法定时限自动预警——线索核查15个工作日、法制审核10个工作日、办案期限90日（含延长与扣除）、送达7个工作日、先行登记保存7个工作日、封存30日" />
    <el-row :gutter="12">
      <el-col :span="12">
        <el-card class="mb">
          <h4>线索核查超期 <el-tag type="danger" size="small">{{ d.clueOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.clueOverdue" size="small" border>
            <el-table-column prop="clue_no" label="线索号" width="120" />
            <el-table-column prop="suspect_name" label="嫌疑人" />
            <el-table-column prop="deadline_at" label="核查期限" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>法制审核超期 <el-tag type="danger" size="small">{{ d.reviewOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.reviewOverdue" size="small" border>
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="submitted_at" label="提交日期" width="110" />
            <el-table-column prop="deadline_at" label="审核期限" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>先行登记保存超期未处理 <el-tag type="danger" size="small">{{ d.holdOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.holdOverdue" size="small" border>
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="name" label="证据" />
            <el-table-column prop="hold_expire_at" label="处理期限" width="110" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card class="mb">
          <h4>办案期限临期/超期（10日内） <el-tag type="warning" size="small">{{ d.caseNearDeadline?.length || 0 }}</el-tag></h4>
          <el-table :data="d.caseNearDeadline" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="name" label="案件" show-overflow-tooltip />
            <el-table-column prop="effective_deadline" label="有效期限" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>决定书送达超期 <el-tag type="danger" size="small">{{ d.deliveryOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.deliveryOverdue" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="name" label="案件" show-overflow-tooltip />
            <el-table-column prop="decided_at" label="决定日期" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>封存临期/到期（5日内） <el-tag type="warning" size="small">{{ d.sealExpiring?.length || 0 }}</el-tag></h4>
          <el-table :data="d.sealExpiring" size="small" border>
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="name" label="证据" />
            <el-table-column prop="seal_expire_at" label="封存到期" width="110" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import client from '../../api/client'

const d = ref<any>({})
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    const resp = await client.get('/bureau/stats/supervision')
    d.value = resp.data.data
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.mb { margin-bottom: 12px; }
h4 { margin: 0 0 8px; }
</style>
