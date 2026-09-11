<template>
  <!-- ============ 工位三：制片（脱水分篮 → 包埋 → 切片 → 染色） ============ -->
  <el-tabs v-model="tab">
    <!-- ---------------- 蜡块台：分篮 / 包埋 / 切片 ---------------- -->
    <el-tab-pane name="blocks" :label="`蜡块（${blocks.length}）`">
      <el-form inline size="small">
        <el-form-item label="标本ID">
          <el-input-number v-model="blockQuery.specimenId" :min="1" :controls="false"
                           placeholder="可空" style="width: 110px" />
        </el-form-item>
        <el-form-item label="脱水批次">
          <el-input v-model="blockQuery.dehydrateBatch" clearable style="width: 130px" />
        </el-form-item>
        <el-form-item label="包埋状态">
          <el-select v-model="embeddedFlag" clearable placeholder="全部" style="width: 120px">
            <el-option label="仅未包埋" value="N" />
            <el-option label="仅已包埋" value="Y" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="blockQuery.keyword" clearable placeholder="蜡块编码 / 条码 / 病理号"
                    style="width: 190px" @keyup.enter="loadBlocks" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="blockLoading" @click="loadBlocks">查询</el-button>
        </el-form-item>
      </el-form>

      <div class="bar">
        <el-input v-model="batchNo" size="small" placeholder="脱水批次号（上限 32 字）"
                  style="width: 200px" />
        <el-input v-model="batchRemark" size="small" placeholder="分篮备注（可空）" style="width: 200px" />
        <el-button type="primary" size="small" :disabled="selectedBlocks.length === 0"
                   :loading="saving" @click="submitBatch">
          归入脱水篮（已选 {{ selectedBlocks.length }} 块）
        </el-button>
        <span class="muted">
          逻辑分组，不是脱水机直连：平台不采集程序号/温度/时长，批次号只用于成组追溯
        </span>
      </div>

      <el-alert v-if="blockTruncated" type="warning" show-icon :closable="false" class="cav"
                :title="`命中超过 ${blockLimit} 条，仅显示前 ${blockLimit} 条（不做翻页）`" />

      <el-table :data="blocks" v-loading="blockLoading" size="small" border stripe max-height="420"
                @selection-change="onBlockSelect">
        <el-table-column type="selection" width="42" />
        <el-table-column label="蜡块编码" width="170">
          <template #default="{ row }"><span class="code">{{ fmt(row.block_code) }}</span></template>
        </el-table-column>
        <el-table-column label="病理号 / 条码" width="170">
          <template #default="{ row }">
            <span class="code">{{ fmt(row.path_no) }}</span>
            <span class="code muted">　{{ fmt(row.barcode) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="组织描述" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ fmt(row.tissue_desc) }}</template>
        </el-table-column>
        <el-table-column label="脱水篮" width="120">
          <template #default="{ row }">
            <span v-if="row.dehydrate_batch">{{ row.dehydrate_batch }}</span>
            <span v-else class="muted">未分篮（可选环节）</span>
          </template>
        </el-table-column>
        <el-table-column label="包埋" width="150">
          <template #default="{ row }">
            <span v-if="row.embedded_at">{{ fmtTime(row.embedded_at) }}</span>
            <el-tag v-else size="small" type="warning">未包埋</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="切片(已染色)" width="110">
          <template #default="{ row }">
            {{ num(row.slide_count) }}（{{ num(row.stained_slide_count) }}）
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="!!row.embedded_at"
                       @click="embed(row)">包埋</el-button>
            <el-button link type="primary" size="small" @click="openSlide(row)">切片</el-button>
          </template>
        </el-table-column>
        <template #empty>无命中蜡块</template>
      </el-table>
    </el-tab-pane>

    <!-- ---------------- 脱水批次进度 ---------------- -->
    <el-tab-pane name="batches" :label="`脱水批次（${batches.length}）`">
      <el-form inline size="small">
        <el-form-item label="批次号">
          <el-input v-model="batchQuery.batchNo" clearable style="width: 160px"
                    @keyup.enter="loadBatches" />
        </el-form-item>
        <el-form-item label="仅未完成">
          <el-switch v-model="batchQuery.onlyPending" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="batchLoading" @click="loadBatches">查询</el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="batchNote" type="info" :closable="false" class="cav" :title="batchNote" />
      <el-table :data="batches" v-loading="batchLoading" size="small" border stripe max-height="420">
        <el-table-column prop="batch_no" label="批次号" width="150" />
        <el-table-column label="进度" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="progressTag(String(row.progress))">
              {{ progressName(String(row.progress)) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="蜡块数" width="90">
          <template #default="{ row }">{{ num(row.block_count) }}</template>
        </el-table-column>
        <el-table-column label="已包埋 / 未包埋" width="130">
          <template #default="{ row }">
            {{ num(row.embedded_count) }} / {{ num(row.pending_count) }}
          </template>
        </el-table-column>
        <el-table-column label="涉及标本数" width="100">
          <template #default="{ row }">{{ num(row.specimen_count) }}</template>
        </el-table-column>
        <el-table-column label="最早建块（非分篮时刻）" width="170">
          <template #default="{ row }">{{ fmtTime(row.first_block_created_at) }}</template>
        </el-table-column>
        <el-table-column label="最晚建块" width="150">
          <template #default="{ row }">{{ fmtTime(row.last_block_created_at) }}</template>
        </el-table-column>
        <el-table-column label="最后包埋" width="150">
          <template #default="{ row }">{{ fmtTime(row.last_embedded_at) }}</template>
        </el-table-column>
        <template #empty>无脱水批次</template>
      </el-table>
    </el-tab-pane>

    <!-- ---------------- 切片与染色 ---------------- -->
    <el-tab-pane name="slides" :label="`切片与染色（${slides.length}）`">
      <el-form inline size="small">
        <el-form-item label="标本ID">
          <el-input-number v-model="slideQuery.specimenId" :min="1" :controls="false"
                           placeholder="可空" style="width: 110px" />
        </el-form-item>
        <el-form-item label="染色类型">
          <el-select v-model="slideQuery.stainType" clearable placeholder="全部" style="width: 130px">
            <el-option v-for="s in STAIN_TYPES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="切片质量">
          <el-select v-model="slideQuery.quality" clearable placeholder="全部" style="width: 130px">
            <el-option v-for="q in SLIDE_QUALITIES" :key="q.value" :label="q.label" :value="q.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="染色状态">
          <el-select v-model="stainedFlag" clearable placeholder="全部" style="width: 120px">
            <el-option label="仅未染色" value="N" />
            <el-option label="仅已染色" value="Y" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期口径">
          <el-select v-model="slideQuery.dateField" style="width: 150px">
            <el-option value="CREATED" label="按切片产出时间" />
            <el-option value="STAINED" label="按染色完成时间" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期区间">
          <el-date-picker v-model="slideRange" type="daterange" unlink-panels
                          value-format="YYYY-MM-DD" range-separator="至"
                          start-placeholder="起" end-placeholder="止" style="width: 230px" />
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="slideQuery.keyword" clearable placeholder="切片/蜡块编码、病理号、患者"
                    style="width: 200px" @keyup.enter="loadSlides" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="slideLoading" @click="loadSlides">查询</el-button>
        </el-form-item>
      </el-form>

      <div class="bar">
        <el-select v-model="batchQuality" size="small" clearable placeholder="染色质量（可留空=未评）"
                   style="width: 190px">
          <el-option v-for="q in SLIDE_QUALITIES" :key="q.value" :label="q.label" :value="q.value" />
        </el-select>
        <el-input v-model="batchStainItem" size="small" placeholder="染色项目（留空则保留原值）"
                  style="width: 190px" />
        <el-button type="primary" size="small" :disabled="selectedSlides.length === 0"
                   :loading="saving" @click="submitBatchComplete">
          批量核销染色（已选 {{ selectedSlides.length }} 张）
        </el-button>
        <span class="muted">
          质量留空计入「未评」而不是优良——默认填优会让优良率恒等于 100%
        </span>
      </div>

      <el-alert v-if="slideNote" type="info" :closable="false" class="cav" :title="slideNote" />
      <el-alert v-if="slideTruncated" type="warning" show-icon :closable="false" class="cav"
                :title="`命中超过 ${slideLimit} 条，仅显示前 ${slideLimit} 条（不做翻页）`" />

      <el-table :data="slides" v-loading="slideLoading" size="small" border stripe max-height="420"
                @selection-change="onSlideSelect">
        <el-table-column type="selection" width="42" />
        <el-table-column label="切片编码" width="180">
          <template #default="{ row }"><span class="code">{{ fmt(row.slide_code) }}</span></template>
        </el-table-column>
        <el-table-column label="病理号 / 患者" width="180">
          <template #default="{ row }">
            <span class="code">{{ fmt(row.path_no) }}</span>　{{ fmt(row.patient_name) }}
          </template>
        </el-table-column>
        <el-table-column label="染色类型" width="100">
          <template #default="{ row }">{{ stainName(row.stain_type) }}</template>
        </el-table-column>
        <el-table-column label="染色项目" width="120">
          <template #default="{ row }">{{ fmt(row.stain_item) }}</template>
        </el-table-column>
        <el-table-column label="染色时刻" width="150">
          <template #default="{ row }">
            <span v-if="row.stained_at">{{ fmtTime(row.stained_at) }}</span>
            <el-tag v-else size="small" type="warning">未染色</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="切片质量" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.quality === 'GOOD'" size="small" type="success">优</el-tag>
            <el-tag v-else-if="row.quality === 'FAIR'" size="small">良</el-tag>
            <el-tag v-else-if="row.quality === 'POOR'" size="small" type="danger">差</el-tag>
            <span v-else class="muted">未评（不进优良率分母）</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="!!row.stained_at"
                       @click="openStain(row)">染色</el-button>
          </template>
        </el-table-column>
        <template #empty>无命中切片</template>
      </el-table>
    </el-tab-pane>
  </el-tabs>

  <!-- ============ 切片登记 ============ -->
  <el-dialog v-model="slideDialog" title="切片登记" width="520px">
    <el-form label-width="90px" size="small">
      <el-form-item label="蜡块">
        <span class="code">{{ fmt(currentBlock.block_code) }}</span>
      </el-form-item>
      <el-form-item label="切片份数">
        <el-input-number v-model="slideForm.count" :min="1" :max="20" />
        <span class="muted" style="margin-left: 8px">1–20 张，片号从既有最大号 +1 续排</span>
      </el-form-item>
      <el-form-item label="染色类型">
        <el-select v-model="slideForm.stainType" style="width: 180px" :disabled="!!pickedTechOrder">
          <el-option v-for="s in STAIN_TYPES" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <span v-if="pickedTechOrder" class="muted" style="margin-left: 8px">
          已按医嘱类型「{{ String(pickedTechOrder.tech_type_name ?? pickedTechOrder.tech_type) }}」锁定为 {{ stainName(slideForm.stainType) }}
        </span>
      </el-form-item>
      <el-form-item label="染色项目">
        <el-input v-model="slideForm.stainItem" maxlength="64" show-word-limit
                  :readonly="techOrderItemLocked"
                  :placeholder="pickedTechOrder && !techOrderItemLocked ? '医嘱未指定项目，可填' : '如 CK7、Ki-67、PAS'" />
        <span v-if="techOrderItemLocked" class="muted">项目随医嘱锁定为「{{ slideForm.stainItem }}」；要做别的项目请另下医嘱</span>
      </el-form-item>
      <el-form-item label="挂接特检医嘱">
        <el-select v-model="slideForm.techOrderId" clearable placeholder="不挂接（普通切片）" style="width: 100%"
                   @change="onTechOrderPick">
          <el-option v-for="t in techOrderOptions" :key="Number(t.id)" :value="Number(t.id)" :label="techOrderLabel(t)" />
        </el-select>
        <span class="muted">只列本标本「待执行」的特检医嘱；挂接不会把医嘱置为已完成，完成仍在特检工位由技师确认。
          挂接后染色类型 / 项目须与医嘱一致（免疫组化→IHC、特殊染色→SPECIAL、分子→MOLECULAR、深切 / 重切 / 补取材→HE），不一致后端 5274 拒绝且一张片不插。
          补取材医嘱标「已出块 N」（取材工位补取材时挂接到该医嘱的蜡块数，v60）：N=0 表示还没为它补出块，先去取材工位补取材再来切片</span>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="slideForm.remark" maxlength="255" show-word-limit />
      </el-form-item>
    </el-form>
    <el-alert type="info" :closable="false" show-icon
              title="新建切片尚未染色（染色时刻为空）；玻片打码机属设备直连，平台只给编码字符串，不负责打印。挂接了特检医嘱的切片会带 tech_order_id，医嘱清单据此计「挂接切片数」。" />
    <template #footer>
      <el-button size="small" @click="slideDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitSlide">登记切片</el-button>
    </template>
  </el-dialog>

  <!-- ============ 染色登记（单张） ============ -->
  <el-dialog v-model="stainDialog" title="染色登记" width="520px">
    <el-form label-width="90px" size="small">
      <el-form-item label="切片">
        <span class="code">{{ fmt(currentSlide.slide_code) }}</span>
        　{{ stainName(currentSlide.stain_type) }}
      </el-form-item>
      <el-form-item label="染色质量">
        <el-select v-model="stainForm.quality" clearable placeholder="留空 = 未评" style="width: 180px">
          <el-option v-for="q in SLIDE_QUALITIES" :key="q.value" :label="q.label" :value="q.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="染色项目">
        <el-input v-model="stainForm.stainItem" maxlength="64" show-word-limit
                  placeholder="留空则保留切片时登记的项目，不会清空" />
        <span v-if="currentSlide.tech_order_id" class="muted">
          本片挂接了特检医嘱 #{{ String(currentSlide.tech_order_id) }}：医嘱有项目时不得改成别的（后端 5274），留空即保留
        </span>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="stainForm.remark" maxlength="255" show-word-limit />
      </el-form-item>
    </el-form>
    <el-alert type="info" :closable="false" show-icon
              title="质量是染色切片优良率的唯一数据源。不评就留空——留空计入「未评」，比默认填「优」诚实（默认优会让优良率恒等于 100%）。" />
    <template #footer>
      <el-button size="small" @click="stainDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitStain">登记染色</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 工位三：制片（脱水分篮 / 包埋 / 切片 / 染色）。
 * 对接 {@code PathologyProcessController}（/api/pathology/process）。
 *
 * <p>后端如实标注的三处，本页全部显示出来：
 * 包埋返回体的 {@code warnings}（未分篮直接包埋）、切片返回体的 {@code warnings}（蜡块无包埋记录）、
 * 分篮返回体的 {@code moved}（有蜡块从别的批次改判过来）。
 * 切片质量为空一律显示「未评」，<b>绝不显示成优或空白</b>。
 *
 * <p>v60（2563 尾）：挂接下拉里补取材医嘱标出「补取材（已出块 N）」（后端 sampled_block_count：取材 append 挂接到该医嘱的蜡块数，
 * V167 path_block.tech_order_id），蜡块段在医嘱未指定蜡块时读派生列 blocks_derived。
 *
 * <p>v59（2563 一致性）：切片登记选了特检医嘱后，染色类型按 {@code TECH_TO_STAIN} 锁定为映射值、项目按医嘱预填并只读
 * （医嘱无项目——深切 / 重切 / 补取材——才可填）。修复前 onTechOrderPick 只做预填，下拉仍可改回 HE，
 * 一条「免疫组化 CK7」医嘱能挂上 2 张 HE 片推到「已染色待确认」。后端同口径 5274 兜底，前端锁定只是省一次被打回。
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../../api/client'
import { SLIDE_QUALITIES, STAIN_TYPES, fmt, fmtTime, num, stainName, type Row } from './format'

const emit = defineEmits<{ (e: 'changed'): void }>()

const tab = ref('blocks')
const saving = ref(false)

/* ---------------- 蜡块 ---------------- */
const blocks = ref<Row[]>([])
const blockLoading = ref(false)
const blockTruncated = ref(false)
const blockLimit = ref(100)
const embeddedFlag = ref('')
const selectedBlocks = ref<Row[]>([])
const blockQuery = reactive({
  specimenId: undefined as number | undefined, dehydrateBatch: '', keyword: '',
})

function onBlockSelect(rows: Row[]) {
  selectedBlocks.value = rows
}

async function loadBlocks() {
  blockLoading.value = true
  try {
    const d = (await client.get('/pathology/process/blocks', {
      params: {
        specimenId: blockQuery.specimenId || undefined,
        dehydrateBatch: blockQuery.dehydrateBatch || undefined,
        embedded: embeddedFlag.value === 'Y' ? true : embeddedFlag.value === 'N' ? false : undefined,
        keyword: blockQuery.keyword || undefined,
      },
    })).data.data as Row
    blocks.value = (d.items ?? []) as Row[]
    blockTruncated.value = d.truncated === true
    blockLimit.value = num(d.limit) || 100
  } finally {
    blockLoading.value = false
  }
}

/* ---------------- 脱水分篮 ---------------- */
const batchNo = ref('')
const batchRemark = ref('')

async function submitBatch() {
  if (!batchNo.value.trim()) {
    ElMessage.warning('脱水批次号不能为空')
    return
  }
  saving.value = true
  try {
    const d = (await client.put('/pathology/process/blocks/dehydrate-batch', {
      batchNo: batchNo.value.trim(),
      blockIds: selectedBlocks.value.map((b) => Number(b.id)),
      remark: batchRemark.value || undefined,
    })).data.data as Row
    const moved = (d.moved ?? []) as Row[]
    ElMessage.success(`已归入批次 ${fmt(d.batchNo)}：${num(d.blockCount)} 块 / ${num(d.specimenCount)} 例`)
    if (moved.length) {
      // 「从别的批次改判过来」是后端刻意回带的事实，不能吞掉——它会改变别的批次的分母
      await ElMessageBox.alert(
        moved.map((m) => `${fmt(m.blockCode)}：${fmt(m.fromBatchNo)} → ${fmt(d.batchNo)}`).join('\n'),
        `有 ${moved.length} 块从其它批次改判`, { type: 'warning' },
      ).catch(() => null)
    }
    await loadBlocks()
    await loadBatches()
    emit('changed')
  } finally {
    saving.value = false
  }
}

async function embed(row: Row) {
  const d = (await client.put(`/pathology/process/blocks/${Number(row.id)}/embed`, null))
    .data.data as Row
  const warnings = (d.warnings ?? []) as string[]
  if (warnings.length) ElMessage.warning(warnings.join('；'))
  else ElMessage.success(`已包埋 ${fmt(d.blockCode)}`)
  await loadBlocks()
  emit('changed')
}

/* ---------------- 脱水批次进度 ---------------- */
const batches = ref<Row[]>([])
const batchLoading = ref(false)
const batchNote = ref('')
const batchQuery = reactive({ batchNo: '', onlyPending: false })

async function loadBatches() {
  batchLoading.value = true
  try {
    const d = (await client.get('/pathology/process/dehydrate/batches', {
      params: {
        batchNo: batchQuery.batchNo || undefined,
        onlyPending: batchQuery.onlyPending || undefined,
      },
    })).data.data as Row
    batches.value = (d.items ?? []) as Row[]
    batchNote.value = String(d.note ?? '')
  } finally {
    batchLoading.value = false
  }
}

function progressName(v: string) {
  return ({ PENDING: '一块未包埋', PARTIAL: '部分已包埋', EMBEDDED: '全部已包埋' } as Record<string, string>)[v] ?? v
}

function progressTag(v: string): 'success' | 'warning' | 'info' {
  return v === 'EMBEDDED' ? 'success' : v === 'PARTIAL' ? 'warning' : 'info'
}

/* ---------------- 切片 ---------------- */
const slideDialog = ref(false)
const currentBlock = ref<Row>({})
const slideForm = reactive({
  count: 1, stainType: 'HE', stainItem: '', remark: '',
  techOrderId: null as number | null,   // v57：可选挂接的特检技术医嘱；空 = 普通切片（旧契约）
})

/**
 * 本标本待执行的特检技术医嘱——挂接下拉的唯一来源，取自
 * {@code GET /pathology/report/tech-orders?specimenId=…&status=ORDERED}
 * （标本分支默认全状态，这里显式只要 ORDERED：别的状态后端 5272 会拒，列出来只会让技师选了再被打回）。
 */
const techOrderOptions = ref<Row[]>([])

async function openSlide(row: Row) {
  currentBlock.value = row
  Object.assign(slideForm, { count: 1, stainType: 'HE', stainItem: '', remark: '', techOrderId: null })
  techOrderOptions.value = []
  slideDialog.value = true
  const d = await client.get('/pathology/report/tech-orders', {
    params: { specimenId: Number(row.specimen_id), status: 'ORDERED' },
  }).then((r) => r.data.data as Row).catch(() => ({} as Row))
  techOrderOptions.value = (d.items ?? []) as Row[]
}

/**
 * 挂接下拉的一行文案：「#id 类型 项目（蜡块）」。
 * v60（2563 尾）：补取材医嘱标出「补取材（已出块 N）」——N 是取材 append 挂接到该医嘱的蜡块数（path_block.tech_order_id，V167），
 * 技师据此知道这条补取材已经出了几块可以切；蜡块段改读 blocks_derived（医嘱未指定蜡块时按挂接蜡块 / 挂接切片所在块派生）。
 * 旧后端没有这两键时按 v59 文案回落。
 */
function techOrderLabel(t: Row): string {
  const item = t.tech_item ? ` ${String(t.tech_item)}` : ''
  const resample = String(t.tech_type) === 'RESAMPLE' ? `（已出块 ${num(t.sampled_block_count)}）` : ''
  const blockCode = t.block_code ?? t.blocks_derived
  const block = blockCode ? `（${String(blockCode)}）` : ''
  return `#${String(t.id)} ${String(t.tech_type_name ?? t.tech_type)}${item}${resample}${block}`
}

/**
 * 特检类型 → 挂接切片必须登记的染色类型，逐字照抄后端 {@code PathologyProcessController.TECH_TO_STAIN}
 * （挂接时后端按同一张表判 5274）：免疫组化 / 特殊染色 / 分子病理各对应自己的染色类型；深切 / 重切 / 补取材是 HE 片的再制。
 */
const TECH_TO_STAIN: Record<string, string> = {
  IHC: 'IHC', SPECIAL_STAIN: 'SPECIAL', MOLECULAR: 'MOLECULAR', DEEP_CUT: 'HE', RECUT: 'HE', RESAMPLE: 'HE',
}

/** 当前选中的待执行医嘱（清空即普通切片） */
const pickedTechOrder = computed<Row | null>(() => {
  const id = slideForm.techOrderId
  if (!id) return null
  return techOrderOptions.value.find((t) => Number(t.id) === Number(id)) ?? null
})

/** 医嘱有项目 → 项目输入只读（随医嘱）；医嘱无项目（深切 / 重切 / 补取材）→ 可填 */
const techOrderItemLocked = computed<boolean>(() => !!pickedTechOrder.value?.tech_item)

/**
 * 选中医嘱：染色类型锁定为映射值、项目按医嘱覆盖（v59 起不再只填空白项——项目随医嘱，技师手填的会被后端 5274 打回）。
 * 清空医嘱：解锁，保留当前值让技师自己改。
 */
function onTechOrderPick(id: unknown) {
  const hit = techOrderOptions.value.find((t) => Number(t.id) === Number(id))
  if (!hit) return
  const st = TECH_TO_STAIN[String(hit.tech_type)]
  if (st) slideForm.stainType = st
  if (hit.tech_item) slideForm.stainItem = String(hit.tech_item)
}

async function submitSlide() {
  saving.value = true
  try {
    // 挂接医嘱时以医嘱为准再钉一次（只读输入挡不住脚本改值；后端 5274 是最终守卫）
    const picked = pickedTechOrder.value
    if (picked) {
      const st = TECH_TO_STAIN[String(picked.tech_type)]
      if (st) slideForm.stainType = st
      if (picked.tech_item) slideForm.stainItem = String(picked.tech_item)
    }
    const d = (await client.post('/pathology/process/slides', {
      blockId: Number(currentBlock.value.id),
      count: slideForm.count,
      stainType: slideForm.stainType,
      stainItem: slideForm.stainItem || undefined,
      remark: slideForm.remark || undefined,
      techOrderId: slideForm.techOrderId || undefined,
    })).data.data as Row
    const warnings = (d.warnings ?? []) as string[]
    ElMessage.success(`已产出切片 ${num(d.slideCount)} 张`)
    if (warnings.length) ElMessage.warning(warnings.join('；'))
    slideDialog.value = false
    await loadBlocks()
    await loadSlides()
    emit('changed')
  } finally {
    saving.value = false
  }
}

/* ---------------- 切片检索与染色 ---------------- */
const slides = ref<Row[]>([])
const slideLoading = ref(false)
const slideTruncated = ref(false)
const slideLimit = ref(100)
const slideNote = ref('')
const stainedFlag = ref('')
const slideRange = ref<[string, string] | null>(null)
const selectedSlides = ref<Row[]>([])
const slideQuery = reactive({
  specimenId: undefined as number | undefined,
  stainType: '', quality: '', dateField: 'CREATED', keyword: '',
})

function onSlideSelect(rows: Row[]) {
  selectedSlides.value = rows
}

async function loadSlides() {
  slideLoading.value = true
  try {
    const d = (await client.get('/pathology/process/slides/search', {
      params: {
        specimenId: slideQuery.specimenId || undefined,
        stainType: slideQuery.stainType || undefined,
        quality: slideQuery.quality || undefined,
        stained: stainedFlag.value === 'Y' ? true : stainedFlag.value === 'N' ? false : undefined,
        dateField: slideQuery.dateField,
        from: slideRange.value?.[0] || undefined,
        to: slideRange.value?.[1] || undefined,
        keyword: slideQuery.keyword || undefined,
      },
    })).data.data as Row
    slides.value = (d.items ?? []) as Row[]
    slideTruncated.value = d.truncated === true
    slideLimit.value = num(d.limit) || 100
    slideNote.value = String(d.note ?? '')
  } finally {
    slideLoading.value = false
  }
}

const stainDialog = ref(false)
const currentSlide = ref<Row>({})
const stainForm = reactive({ quality: '', stainItem: '', remark: '' })

function openStain(row: Row) {
  currentSlide.value = row
  Object.assign(stainForm, { quality: '', stainItem: '', remark: '' })
  stainDialog.value = true
}

async function submitStain() {
  saving.value = true
  try {
    const d = (await client.put(`/pathology/process/slides/${Number(currentSlide.value.id)}/stain`, {
      quality: stainForm.quality || undefined,
      stainItem: stainForm.stainItem || undefined,
      remark: stainForm.remark || undefined,
    })).data.data as Row
    const warnings = (d.warnings ?? []) as string[]
    ElMessage.success('已登记染色')
    if (warnings.length) ElMessage.warning(warnings.join('；'))
    stainDialog.value = false
    await notifyTechProgress(currentSlide.value)
    await loadSlides()
    emit('changed')
  } finally {
    saving.value = false
  }
}

/**
 * v58：染色成功后，若该切片挂在特检技术医嘱上，提示该医嘱的执行进度（已染色待确认时技师就可以去点完成了）。
 * 只读清单：{@code GET /pathology/report/tech-orders?specimenId&status=ALL&slideId}——切片检索行不带 tech_order_id，
 * 由清单按 slideId 反查（0 或 1 行）；普通切片查不到就不提示。读失败不影响已登记的染色结果。
 */
async function notifyTechProgress(slide: Row) {
  if (!slide.id) return
  const d = await client.get('/pathology/report/tech-orders', {
    params: { specimenId: Number(slide.specimen_id), status: 'ALL', slideId: Number(slide.id) },
  }).then((r) => r.data.data as Row).catch(() => ({} as Row))
  for (const t of (d.items ?? []) as Row[]) {
    const item = t.tech_item ? ` ${String(t.tech_item)}` : ''
    ElMessage.info(`医嘱 #${String(t.id)} ${String(t.tech_type_name ?? t.tech_type)}${item} 进度：`
      + `${String(t.progress_name ?? t.progress ?? '—')}（已染色 ${num(t.stained_count)} / 挂接 ${num(t.slide_count)} 片）`)
  }
}

/* ---------------- 批量核销 ---------------- */
const batchQuality = ref('')
const batchStainItem = ref('')

async function submitBatchComplete() {
  saving.value = true
  try {
    const d = (await client.put('/pathology/process/slides/batch-complete', {
      slideIds: selectedSlides.value.map((s) => Number(s.id)),
      quality: batchQuality.value || undefined,
      stainItem: batchStainItem.value || undefined,
    })).data.data as Row
    // requested 与 completed 分别显示：只报「已完成」会掩盖两者不等的情形
    ElMessage.success(`本次提交 ${num(d.requested)} 张，实际核销 ${num(d.completed)} 张，`
      + `涉及 ${num(d.specimenCount)} 例`)
    if (num(d.completed) < num(d.requested)) {
      ElMessage.warning('有切片未被核销（可能已被他人先行染色），请核对后重试')
    }
    if (!batchQuality.value) ElMessage.warning('本批未评定切片质量，计入「未评」而非优良')
    await loadSlides()
    emit('changed')
  } finally {
    saving.value = false
  }
}

defineExpose({ reload: loadBlocks })

void loadBlocks()
void loadBatches()
void loadSlides()
</script>

<style scoped>
.cav { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.code { font-family: Consolas, Monaco, monospace; }
.bar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
</style>
