<template>
  <!-- ============ 工位二：取材（工作列表 → 大体描述 → 蜡块编号） ============ -->
  <el-form inline size="small">
    <el-form-item label="范围">
      <el-select v-model="query.scope" style="width: 230px" @change="load">
        <el-option value="pending" label="待取材（已核收、未诊断且无蜡块）" />
        <el-option value="all" label="全部（已核收未拒收，含存量无蜡块标本）" />
      </el-select>
    </el-form-item>
    <el-form-item label="类别">
      <el-select v-model="query.specimenType" clearable placeholder="全部" style="width: 120px">
        <el-option v-for="t in SPECIMEN_TYPES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
    </el-form-item>
    <el-form-item label="仅加急">
      <el-switch v-model="query.urgentOnly" />
    </el-form-item>
    <el-form-item label="关键词">
      <el-input v-model="query.keyword" clearable placeholder="条码 / 病理号 / 患者 / 患者号"
                style="width: 200px" @keyup.enter="load" />
    </el-form-item>
    <el-form-item>
      <el-button type="primary" :loading="loading" @click="load">查询</el-button>
    </el-form-item>
  </el-form>

  <el-alert v-if="note" type="info" :closable="false" class="cav" :title="note" />
  <el-alert v-if="truncated" type="warning" show-icon :closable="false" class="cav"
            :title="`命中超过 ${limit} 条，仅显示前 ${limit} 条（不做翻页）；请收窄条件`" />

  <el-table :data="rows" v-loading="loading" size="small" border stripe max-height="440">
    <el-table-column label="病理号 / 条码" width="180">
      <template #default="{ row }">
        <span class="code">{{ fmt(row.path_no) }}</span><br>
        <span class="code muted">{{ fmt(row.barcode) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="部位" width="60">
      <template #default="{ row }">{{ fmt(row.part_no) }}</template>
    </el-table-column>
    <el-table-column label="患者" width="150">
      <template #default="{ row }">
        {{ fmt(row.patient_name) }}
        <span class="muted">{{ fmt(row.patient_no) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="类别" width="90">
      <template #default="{ row }">
        {{ typeName(row.specimen_type) }}
        <el-tag v-if="row.urgent === true" size="small" type="danger">急</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="取材部位" min-width="120" show-overflow-tooltip>
      <template #default="{ row }">{{ fmt(row.sampling_site) }}</template>
    </el-table-column>
    <el-table-column label="临床诊断" min-width="150" show-overflow-tooltip>
      <template #default="{ row }">{{ fmt(row.clinical_diagnosis) }}</template>
    </el-table-column>
    <el-table-column label="签收时刻" width="140">
      <template #default="{ row }">{{ fmtTime(row.received_at) }}</template>
    </el-table-column>
    <el-table-column label="距签收(小时)" width="110">
      <template #default="{ row }">{{ fmt(row.hours_since_received) }}</template>
    </el-table-column>
    <el-table-column label="蜡块/切片" width="90">
      <template #default="{ row }">{{ num(row.block_count) }} / {{ num(row.slide_count) }}</template>
    </el-table-column>
    <el-table-column label="操作" width="110" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" size="small" @click="openGrossing(row)">取材登记</el-button>
      </template>
    </el-table-column>
    <template #empty>该范围内无标本</template>
  </el-table>

  <!-- ============ 取材登记 ============ -->
  <el-dialog v-model="dialog" title="取材登记" width="760px" top="6vh">
    <el-descriptions :column="3" border size="small" class="cav">
      <el-descriptions-item label="病理号">
        <span class="code">{{ fmt(current.path_no) }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="条码">
        <span class="code">{{ fmt(current.barcode) }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="患者">{{ fmt(current.patient_name) }}</el-descriptions-item>
      <el-descriptions-item label="类别">{{ typeName(current.specimen_type) }}</el-descriptions-item>
      <el-descriptions-item label="已有蜡块">{{ num(current.block_count) }}</el-descriptions-item>
      <el-descriptions-item label="取材部位">{{ fmt(current.sampling_site) }}</el-descriptions-item>
    </el-descriptions>

    <el-alert v-if="num(current.block_count) > 0" type="warning" show-icon :closable="false" class="cav"
              title="该标本已有蜡块：第二次及以后的取材必须显式勾选「补取材」，否则后端返 5223。误点两次「取材」凭空多出一组蜡块是事故，与病理医师看完 HE 片后下的补取材必须区分开。" />

    <el-form label-width="100px" size="small">
      <el-form-item label="补取材">
        <el-switch v-model="form.append" />
        <span class="muted" style="margin-left: 8px">已诊断标本的补取材同样必须显式声明</span>
      </el-form-item>

      <el-form-item label="取材模板">
        <el-select v-model="form.templateCode" clearable placeholder="不用模板" style="width: 260px"
                   @change="onTemplateChange">
          <el-option v-for="t in templates" :key="String(t.code)" :value="String(t.code)"
                     :label="`${t.name}（${t.code}）`">
            <span>{{ t.name }}（{{ t.code }}）</span>
            <el-tag size="small" :type="t.source === 'CONFIG' ? 'warning' : 'info'"
                    style="margin-left: 6px">{{ t.source === 'CONFIG' ? '配置' : '内置' }}</el-tag>
          </el-option>
        </el-select>
        <span v-if="templateNote" class="muted" style="margin-left: 8px">字段清单可配，模板管理未做</span>
      </el-form-item>

      <el-form-item v-if="currentTemplate && currentTemplate.example" label="示例描述">
        <span class="muted">{{ currentTemplate.example }}</span>
      </el-form-item>
      <el-form-item v-else-if="currentTemplate" label="示例描述">
        <span class="muted">—（配置新增的模板无示例文本：sys_config 只有 255 字符，不编一段假的）</span>
      </el-form-item>

      <el-form-item v-for="f in grossFields" :key="f" :label="f">
        <el-input v-model="form.gross[f]" maxlength="300" show-word-limit />
      </el-form-item>

      <el-form-item label="自由描述">
        <el-input v-model="form.grossText" type="textarea" :rows="2"
                  placeholder="与上面的结构化字段拼成一段大体所见；总长上限 2000 字" />
      </el-form-item>

      <el-form-item label="蜡块">
        <div style="width: 100%">
          <el-button size="small" @click="addBlock">增加一块</el-button>
          <span class="muted" style="margin-left: 8px">
            共 {{ form.blocks.length }} 块（一次最多 50 块）；块号从既有最大号 +1 续排，
            编码 =「病理号（缺失回落条码）- 块号」
          </span>
          <div v-for="(b, i) in form.blocks" :key="i" class="block-row">
            <span class="block-idx">第 {{ i + 1 }} 块</span>
            <el-input v-model="b.tissueDesc" size="small" maxlength="500" show-word-limit
                      placeholder="组织描述（可留空）" style="flex: 1" />
            <el-button link type="danger" size="small" :disabled="form.blocks.length <= 1"
                       @click="form.blocks.splice(i, 1)">删除</el-button>
          </div>
        </div>
      </el-form-item>

      <el-form-item label="备注">
        <el-input v-model="form.remark" maxlength="255" show-word-limit />
      </el-form-item>
    </el-form>

    <el-alert type="info" :closable="false" show-icon
              title="大体所见只在该标本此列为空时写入，绝不覆盖：该列同时被病理医师出报告时修订。已有大体所见还传结构化字段/自由描述会被拒（5222），补取材的组织描述请写在各蜡块里。" />

    <template #footer>
      <el-button size="small" @click="dialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submit">登记取材</el-button>
    </template>
  </el-dialog>

  <!-- ============ 取材结果 ============ -->
  <el-dialog v-model="resultDialog" title="取材完成" width="560px">
    <el-alert v-if="result.codePrefixSource === 'BARCODE'" type="warning" show-icon :closable="false"
              class="cav"
              title="本标本尚无病理号，蜡块编码回落院内条码；病理号事后补发不会回改已生成的蜡块编码。" />
    <el-descriptions :column="2" border size="small" class="cav">
      <el-descriptions-item label="本次产出">{{ num(result.blockCount) }} 块</el-descriptions-item>
      <el-descriptions-item label="该标本累计">{{ num(result.totalBlockCount) }} 块</el-descriptions-item>
      <el-descriptions-item label="编码前缀" :span="2">
        <span class="code">{{ fmt(result.codePrefix) }}</span>
        <el-tag size="small" style="margin-left: 6px">{{ fmt(result.codePrefixSource) }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="大体所见是否写入" :span="2">
        {{ result.grossFindingWritten === true ? '已写入' : '未写入（该列原本已有内容或本次未填）' }}
      </el-descriptions-item>
    </el-descriptions>
    <el-table :data="resultBlocks" size="small" border max-height="240">
      <el-table-column label="块号" width="80">
        <template #default="{ row }">{{ fmt(row.block_no) }}</template>
      </el-table-column>
      <el-table-column label="蜡块编码" width="180">
        <template #default="{ row }"><span class="code">{{ fmt(row.block_code) }}</span></template>
      </el-table-column>
      <el-table-column label="组织描述" show-overflow-tooltip>
        <template #default="{ row }">{{ fmt(row.tissue_desc) }}</template>
      </el-table-column>
    </el-table>
    <p class="muted">打码机属设备直连，平台只给编码字符串，不负责打印。</p>
    <template #footer>
      <el-button type="primary" size="small" @click="resultDialog = false">知道了</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 工位二：取材。对接 {@code PathologyProcessController}（/api/pathology/process）。
 *
 * <p>「待取材」的判据是<b>名下一块蜡块都没有</b>，不是 status——既有 status 只有
 * COLLECTED/RECEIVED/DIAGNOSED 三档、压根没有「已取材」这一档。
 * {@code hours_since_received} 是原始事实（距签收小时数），<b>本页不判超时</b>：
 * 时限阈值口径归病理质控页唯一定义，两处各判一次必然分叉。
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../../api/client'
import { SPECIMEN_TYPES, fmt, fmtTime, num, typeName, type Row } from './format'

const emit = defineEmits<{ (e: 'changed'): void }>()

const rows = ref<Row[]>([])
const loading = ref(false)
const truncated = ref(false)
const limit = ref(100)
const note = ref('')
const query = reactive({ scope: 'pending', specimenType: '', urgentOnly: false, keyword: '' })

async function load() {
  loading.value = true
  try {
    const d = (await client.get('/pathology/process/grossing/worklist', {
      params: {
        scope: query.scope,
        specimenType: query.specimenType || undefined,
        urgentOnly: query.urgentOnly || undefined,
        keyword: query.keyword || undefined,
      },
    })).data.data as Row
    rows.value = (d.items ?? []) as Row[]
    truncated.value = d.truncated === true
    limit.value = num(d.limit) || 100
    note.value = String(d.note ?? '')
  } finally {
    loading.value = false
  }
}

/* ---------------- 取材模板 ---------------- */
const templates = ref<Row[]>([])
const templateNote = ref('')

async function loadTemplates() {
  const d = (await client.get('/pathology/process/grossing/templates')).data.data as Row
  templates.value = (d.items ?? []) as Row[]
  templateNote.value = String(d.note ?? '')
}

const currentTemplate = computed<Row | null>(
  () => templates.value.find((t) => String(t.code) === form.templateCode) ?? null,
)
const grossFields = computed<string[]>(
  () => ((currentTemplate.value?.fields ?? []) as string[]),
)

function onTemplateChange() {
  form.gross = {}
  for (const f of grossFields.value) form.gross[f] = ''
}

/* ---------------- 取材登记 ---------------- */
const dialog = ref(false)
const resultDialog = ref(false)
const saving = ref(false)
const current = ref<Row>({})
const result = ref<Row>({})
const resultBlocks = computed<Row[]>(() => (result.value.blocks ?? []) as Row[])

const form = reactive({
  templateCode: '',
  gross: {} as Record<string, string>,
  grossText: '',
  append: false,
  remark: '',
  blocks: [{ tissueDesc: '' }] as { tissueDesc: string }[],
})

function addBlock() {
  if (form.blocks.length >= 50) {
    ElMessage.warning('一次取材最多 50 个蜡块')
    return
  }
  form.blocks.push({ tissueDesc: '' })
}

function openGrossing(row: Row) {
  current.value = row
  Object.assign(form, {
    templateCode: '',
    gross: {},
    grossText: '',
    // **不按「已有蜡块」自动勾上补取材**：后端要求显式声明，正是为了把
    // 「病理医师看完 HE 片后的补取材」与「误点两次取材凭空多出一组蜡块」区分开。
    // 前端替人勾上就等于把这道闸拆了——默认关，由人自己确认。
    append: false,
    remark: '',
    blocks: [{ tissueDesc: '' }],
  })
  dialog.value = true
}

async function submit() {
  if (form.blocks.length === 0) {
    ElMessage.warning('取材必须至少产出 1 个蜡块')
    return
  }
  saving.value = true
  try {
    // 值为空的结构化字段整条不传：后端会把空值字段略去，前端也不必送出「大小：」这种半截话
    const gross: Record<string, string> = {}
    for (const [k, v] of Object.entries(form.gross)) if (v && v.trim()) gross[k] = v.trim()

    result.value = (await client.post('/pathology/process/grossing', {
      specimenId: Number(current.value.id),
      templateCode: form.templateCode || undefined,
      gross: Object.keys(gross).length ? gross : undefined,
      grossText: form.grossText || undefined,
      append: form.append,
      remark: form.remark || undefined,
      blocks: form.blocks.map((b) => ({ tissueDesc: b.tissueDesc || undefined })),
    })).data.data as Row
    dialog.value = false
    resultDialog.value = true
    await load()
    emit('changed')
  } finally {
    saving.value = false
  }
}

defineExpose({ reload: load })

void load()
void loadTemplates()
</script>

<style scoped>
.cav { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.code { font-family: Consolas, Monaco, monospace; }
.block-row { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.block-idx { width: 60px; color: #606266; font-size: 12px; }
</style>
