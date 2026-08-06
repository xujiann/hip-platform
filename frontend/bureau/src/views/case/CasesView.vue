<template>
  <el-card>
    <div class="toolbar">
      <h3>案件办理<span class="hint">（普通程序自立案之日起 90 日内作出处理决定）</span></h3>
      <div>
        <el-select v-model="statusFilter" clearable placeholder="全部状态" style="width: 130px; margin-right: 8px" @change="load">
          <el-option v-for="(v, k) in CASE_STATUS" :key="k" :label="v" :value="k" />
        </el-select>
        <el-button type="primary" @click="openCreate()">立案</el-button>
      </div>
    </div>
    <el-table :data="records" v-loading="loading" border stripe @row-click="(r: any) => $router.push(`/case/detail/${r.id}`)">
      <el-table-column prop="caseNo" label="案号" width="170" />
      <el-table-column prop="name" label="案件名称" show-overflow-tooltip />
      <el-table-column label="当事人类别" width="120">
        <template #default="{ row }">{{ PARTY_TYPE[row.partyType] }}</template>
      </el-table-column>
      <el-table-column label="程序" width="90">
        <template #default="{ row }">{{ row.procedureType === 'SUMMARY' ? '简易' : '普通' }}</template>
      </el-table-column>
      <el-table-column prop="filedAt" label="立案日期" width="110" />
      <el-table-column prop="deadlineAt" label="办案期限" width="110" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="(CASE_STATUS_TAG[row.status] as any)" size="small">{{ CASE_STATUS[row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="amountInvolved" label="涉案金额" width="110" align="right" />
    </el-table>

    <el-dialog v-model="createVisible" title="立案（执法人员不得少于两人）" width="640px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="当事人名称">
          <el-input v-model="form.partyName" />
        </el-form-item>
        <el-form-item label="当事人类别">
          <el-select v-model="form.partyType" style="width: 100%" @change="form.causeId = null">
            <el-option v-for="(v, k) in PARTY_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="案由">
          <el-select v-model="form.causeId" style="width: 100%" filterable placeholder="按当事人类别筛选">
            <el-option v-for="c in causes.filter((x) => x.subjectType === form.partyType)" :key="c.id"
                       :label="`${c.itemNo}. ${c.category}——${c.description}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="程序类型">
          <el-radio-group v-model="form.procedureType">
            <el-radio value="NORMAL">普通程序</el-radio>
            <el-radio value="SUMMARY">简易程序（当场处罚：公民≤200元，单位≤3000元）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="证照/证件号">
          <el-input v-model="form.partyCreditNo" placeholder="统一社会信用代码或身份证号" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.partyAddress" />
        </el-form-item>
        <el-form-item label="法定代表人">
          <el-input v-model="form.partyLegalRep" placeholder="自然人案件可留空" />
        </el-form-item>
        <el-form-item label="联系方式">
          <el-input v-model="form.partyContact" />
        </el-form-item>
        <el-form-item label="涉案金额">
          <el-input-number v-model="form.amountInvolved" :min="0" :precision="2" style="width: 200px" />
        </el-form-item>
        <el-form-item label="案情摘要">
          <el-input v-model="form.summary" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="办案人员">
          <div style="width: 100%">
            <div v-for="(o, i) in form.officers" :key="i" class="officer-row">
              <el-input v-model="o.name" placeholder="姓名" style="width: 140px" />
              <el-input v-model="o.certNo" placeholder="执法证号" style="width: 180px" />
              <el-select v-model="o.duty" style="width: 100px">
                <el-option label="主办" value="LEAD" />
                <el-option label="协办" value="MEMBER" />
              </el-select>
              <el-button v-if="form.officers.length > 2" text type="danger" @click="form.officers.splice(i, 1)">删除</el-button>
            </div>
            <el-button text type="primary" @click="form.officers.push({ name: '', certNo: '', duty: 'MEMBER' })">+ 添加人员</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="onCreate">立案</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import client from '../../api/client'
import { CASE_STATUS, CASE_STATUS_TAG, PARTY_TYPE } from './labels'

const route = useRoute()
const router = useRouter()
const records = ref<any[]>([])
const causes = ref<any[]>([])
const loading = ref(false)
const statusFilter = ref('')
const createVisible = ref(false)

const emptyOfficers = () => [
  { name: '', certNo: '', duty: 'LEAD' },
  { name: '', certNo: '', duty: 'MEMBER' },
]
const form = reactive<any>({
  clueId: null, causeId: null, procedureType: 'NORMAL',
  partyName: '', partyType: 'PROVIDER', partyCreditNo: '', partyAddress: '',
  partyLegalRep: '', partyContact: '', summary: '', amountInvolved: 0,
  officers: emptyOfficers(),
})

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/bureau/cases', { params: statusFilter.value ? { status: statusFilter.value } : {} })
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.officers = emptyOfficers()
  createVisible.value = true
}

async function onCreate() {
  if (!form.partyName || !form.causeId) {
    ElMessage.warning('请填写当事人并选择案由')
    return
  }
  if (form.officers.some((o: any) => !o.name || !o.certNo)) {
    ElMessage.warning('办案人员须填写姓名与执法证号')
    return
  }
  const resp = await client.post('/bureau/cases', form)
  ElMessage.success(`已立案：${resp.data.data.caseNo}`)
  createVisible.value = false
  router.push(`/case/detail/${resp.data.data.id}`)
}

onMounted(async () => {
  const resp = await client.get('/bureau/causes')
  causes.value = resp.data.data
  // 从线索页跳转：预填当事人并直接打开立案表单
  if (route.query.clueId) {
    form.clueId = Number(route.query.clueId)
    form.partyName = String(route.query.suspectName || '')
    form.partyType = String(route.query.suspectType || 'PROVIDER')
    openCreate()
  }
  load()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
.officer-row { display: flex; gap: 8px; margin-bottom: 8px; }
</style>
