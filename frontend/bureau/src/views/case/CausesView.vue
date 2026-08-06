<template>
  <el-card>
    <div class="toolbar">
      <h3>案由字典<span class="hint">（苏医保督〔2024〕1号：4 类主体 / 9 种案由 / 35 项违法情形；法律更新可增补）</span></h3>
      <el-button v-if="auth.user?.roles?.includes('ADMIN')" type="primary" @click="createVisible = true">增补案由</el-button>
    </div>
    <el-tabs v-model="tab">
      <el-tab-pane v-for="(label, type) in PARTY_TYPE" :key="type" :label="label" :name="type">
        <el-table :data="causes.filter((c) => c.subjectType === type)" border stripe size="small">
          <el-table-column prop="itemNo" label="序号" width="70" />
          <el-table-column prop="category" label="案由种类" width="240" />
          <el-table-column prop="description" label="违法情形" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="createVisible" title="增补案由" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="违法主体">
          <el-select v-model="form.subjectType" style="width: 100%">
            <el-option v-for="(v, k) in PARTY_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="案由种类"><el-input v-model="form.category" /></el-form-item>
        <el-form-item label="序号"><el-input-number v-model="form.itemNo" :min="36" /></el-form-item>
        <el-form-item label="违法情形"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="onCreate">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'
import { PARTY_TYPE } from './labels'

const auth = useAuthStore()
const causes = ref<any[]>([])
const tab = ref('PROVIDER')
const createVisible = ref(false)
const form = reactive({ subjectType: 'PROVIDER', category: '', itemNo: 36, description: '' })

async function load() {
  const resp = await client.get('/bureau/causes')
  causes.value = resp.data.data
}

async function onCreate() {
  if (!form.category || !form.description) {
    ElMessage.warning('请填写案由种类与违法情形')
    return
  }
  await client.post('/bureau/causes', form)
  ElMessage.success('已增补')
  createVisible.value = false
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
