<template>
  <el-card>
    <el-alert title="集成适配器与内容路由（雏形）：文件/数据库两类适配器 + 关键字路由；ESB 级路由编排属配套产品"
              type="info" show-icon :closable="false" style="margin-bottom: 10px" />
    <el-form inline size="small">
      <el-form-item><el-input v-model="ad.name" placeholder="适配器名称" style="width: 150px" /></el-form-item>
      <el-form-item>
        <el-select v-model="ad.type" style="width: 100px">
          <el-option label="文件" value="FILE" /><el-option label="数据库" value="DB" />
        </el-select>
      </el-form-item>
      <el-form-item><el-input v-model="ad.config" placeholder="目标（目录/数据源）" style="width: 180px" /></el-form-item>
      <el-button type="primary" size="small" @click="saveAdapter">保存适配器</el-button>
    </el-form>
    <el-table :data="adapters" size="small" border>
      <el-table-column prop="name" label="适配器" width="160" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ row.type === 'FILE' ? '文件' : '数据库' }}</template>
      </el-table-column>
      <el-table-column prop="config" label="目标" show-overflow-tooltip />
      <el-table-column prop="rules" label="路由规则数" width="100" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link size="small" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h4>内容路由规则
      <el-button link type="primary" size="small" @click="addRule">新增规则</el-button>
    </h4>
    <el-table :data="rules" size="small" border>
      <el-table-column prop="keyword" label="报文关键字" width="140" />
      <el-table-column prop="adapter_name" label="路由到" width="160" />
      <el-table-column prop="adapter_type" label="类型" width="80" />
      <el-table-column prop="remark" label="说明" show-overflow-tooltip />
    </el-table>

    <h4>路由测试</h4>
    <el-input v-model="testContent" placeholder="粘贴报文片段（如含 ORU 的 HL7 消息）" style="width: 420px" />
    <el-button type="primary" size="small" style="margin-left: 8px" @click="routeTest">测试路由</el-button>
    <el-alert v-if="testResult" style="margin-top: 8px" show-icon :closable="false"
              :type="testResult.matched ? 'success' : 'warning'"
              :title="testResult.matched
                ? `命中适配器「${testResult.adapter}」→ ${testResult.type === 'FILE' ? '文件' : '数据库'}：${testResult.target}（已留痕 channel=ADAPTER）`
                : '未命中任何路由规则'" />
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

interface TestResult { matched: boolean; adapter?: string; type?: string; target?: string }

const adapters = ref<Record<string, unknown>[]>([])
const rules = ref<Record<string, unknown>[]>([])
const testContent = ref('MSH|^~\\&|LIS|ORU^R01|...')
const testResult = ref<TestResult | null>(null)
const ad = reactive({ name: '', type: 'FILE', config: '' })

async function load() {
  adapters.value = (await client.get('/integration/adapters')).data.data
  rules.value = (await client.get('/integration/adapters/rules')).data.data
}
async function saveAdapter() {
  if (!ad.name || !ad.config) return
  await client.post('/integration/adapters', ad)
  ElMessage.success('已保存')
  await load()
}
async function toggle(row: Record<string, unknown>) {
  await client.put(`/integration/adapters/${row.id}/toggle`)
  await load()
}
async function addRule() {
  const { value } = await ElMessageBox.prompt('关键字,适配器ID（逗号分隔）', '新增路由规则', { inputValue: 'ADT,1' })
  const [keyword, adapterId] = value.split(/[,，]/).map((s: string) => s.trim())
  await client.post('/integration/adapters/rules', { keyword, adapterId: Number(adapterId), remark: '' })
  await load()
}
async function routeTest() {
  testResult.value = (await client.post('/integration/adapters/route-test', null,
    { params: { content: testContent.value } })).data.data
}

onMounted(load)
</script>
