<template>
  <el-card>
    <div class="toolbar">
      <h3>患者建档</h3>
      <div class="search">
        <el-input v-model="keyword" placeholder="姓名 / 患者号 / 身份证号 / 手机号" clearable
                  style="width: 300px" @keyup.enter="search" @clear="search" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button type="success" @click="openCreate">新建档案</el-button>
      </div>
    </div>

    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="patientNo" label="患者号" width="110" />
      <el-table-column prop="name" label="姓名" width="100" />
      <el-table-column label="性别" width="60">
        <template #default="{ row }">{{ sexNames[row.sex] ?? '' }}</template>
      </el-table-column>
      <el-table-column prop="age" label="年龄" width="60" />
      <el-table-column prop="idNo" label="证件号" width="180" />
      <el-table-column prop="phone" label="手机号" width="130" />
      <el-table-column label="医保" width="90">
        <template #default="{ row }">{{ insuranceNames[row.insuranceType] ?? row.insuranceType }}</template>
      </el-table-column>
      <el-table-column prop="address" label="住址" show-overflow-tooltip />
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="pageNo" :page-size="pageSize" :total="total"
                   layout="total, prev, pager, next" style="margin-top: 12px" @current-change="load" />

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑档案' : '新建档案'" width="560px">
      <el-form :model="form" label-width="90px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="证件类型">
              <el-select v-model="form.idType" style="width: 100%">
                <el-option label="身份证" value="ID_CARD" />
                <el-option label="护照" value="PASSPORT" />
                <el-option label="其他" value="OTHER" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="证件号">
              <el-input v-model="form.idNo" placeholder="身份证可自动带出生日/性别" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="姓名" required><el-input v-model="form.name" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="性别">
              <el-radio-group v-model="form.sex">
                <el-radio value="M">男</el-radio>
                <el-radio value="F">女</el-radio>
                <el-radio value="U">未知</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="出生日期">
              <el-date-picker v-model="form.birthDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="手机号"><el-input v-model="form.phone" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="医保类型">
              <el-select v-model="form.insuranceType" style="width: 100%">
                <el-option v-for="(label, value) in insuranceNames" :key="value" :label="label" :value="value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="血型">
              <el-select v-model="form.bloodType" clearable style="width: 100%">
                <el-option v-for="t in ['A', 'B', 'O', 'AB', 'RH-']" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="住址"><el-input v-model="form.address" /></el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="过敏史">
              <el-input v-model="form.allergyHistory" type="textarea" :rows="2" placeholder="无则留空" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

interface PatientRow {
  id: number | null
  patientNo?: string
  name: string
  sex: string
  birthDate: string | null
  idType: string
  idNo: string | null
  phone: string | null
  address: string | null
  insuranceType: string
  bloodType: string | null
  allergyHistory: string | null
  age?: number
}

const sexNames: Record<string, string> = { M: '男', F: '女', U: '未知' }
const insuranceNames: Record<string, string> = { SELF: '自费', YB_STAFF: '职工医保', YB_RESIDENT: '居民医保' }

const keyword = ref('')
const records = ref<PatientRow[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = 20
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)

const emptyForm: PatientRow = {
  id: null, name: '', sex: 'U', birthDate: null, idType: 'ID_CARD', idNo: null,
  phone: null, address: null, insuranceType: 'SELF', bloodType: null, allergyHistory: null,
}
const form = reactive<PatientRow>({ ...emptyForm })

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/patients', {
      params: { keyword: keyword.value, page: pageNo.value - 1, size: pageSize },
    })
    records.value = resp.data.data.records
    total.value = resp.data.data.total
  } finally {
    loading.value = false
  }
}

function search() {
  pageNo.value = 1
  load()
}

function openCreate() {
  Object.assign(form, emptyForm)
  dialogVisible.value = true
}

function openEdit(row: PatientRow) {
  Object.assign(form, row)
  dialogVisible.value = true
}

async function save() {
  if (!form.name) {
    ElMessage.warning('请填写姓名')
    return
  }
  saving.value = true
  try {
    if (form.id) {
      await client.put(`/patients/${form.id}`, form)
      ElMessage.success('已保存')
    } else {
      const resp = await client.post('/patients', form)
      ElMessage.success(`建档成功，患者号 ${resp.data.data.patientNo}`)
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.toolbar h3 { margin: 0; }
.search { display: flex; gap: 8px; }
</style>
