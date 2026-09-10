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
    <el-table-column label="操作" width="290" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" size="small" @click="openGrossing(row)">取材登记</el-button>
        <!-- v59（2530 复核）：未诊断前取材员可改自己录的字段与文本，出新版本；诊断后由诊断端点修订 -->
        <el-button v-if="canRevise(row)" link type="warning" size="small" @click="openRevise(row)">修订取材描述</el-button>
        <el-button link type="primary" size="small" @click="openView(Number(row.id))">查看大体所见</el-button>
      </template>
    </el-table-column>
    <template #empty>该范围内无标本</template>
  </el-table>

  <!-- ============ 大体所见查看（只读，不分诊断状态，2530） ============ -->
  <el-dialog v-model="viewDialog" title="大体所见与蜡块（只读）" width="760px" top="6vh">
    <div v-loading="viewLoading">
      <el-descriptions :column="3" border size="small" class="cav">
        <el-descriptions-item label="病理号">
          <span class="code">{{ fmt(view.specimen?.path_no) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="条码">
          <span class="code">{{ fmt(view.specimen?.barcode) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="患者">{{ fmt(view.specimen?.patient_name) }}</el-descriptions-item>
        <el-descriptions-item label="类别">{{ typeName(view.specimen?.specimen_type) }}</el-descriptions-item>
        <el-descriptions-item label="取材部位">{{ fmt(view.specimen?.sampling_site) }}</el-descriptions-item>
        <el-descriptions-item label="写完诊断">{{ fmtTime(view.diagnosedAt) }}</el-descriptions-item>
      </el-descriptions>

      <div class="sec">
        <b>大体所见</b>
        <el-tag v-if="view.grossFindingPresent" size="small" type="info" style="margin-left: 6px">
          {{ view.diagnosedAt ? '已写诊断（该列可能已被诊断端点修订）' : '取材工位录入，诊断尚未书写' }}</el-tag>
        <pre v-if="view.grossFindingPresent">{{ view.grossFinding }}</pre>
        <el-empty v-else description="该标本尚未录入大体所见" :image-size="50" />
      </div>

      <!-- 字段级记录（v58，2530）：只认 path_gross_field 的行，不从上面的文本反解析 -->
      <div class="sec">
        <b>字段级记录</b>
        <template v-if="view.fieldsAvailable === true">
          <el-tag size="small" :type="view.fieldsCurrent === false ? 'warning' : 'success'" style="margin-left: 6px">
            第 {{ fmt(view.fieldsRevisionSeq) }} 版字段（{{ viewFields.length }} 项）
            {{ view.fieldsCurrent === false ? '' : '，与当前文本同版' }}</el-tag>
          <!-- v59：字段随版本走；诊断只改文本不改字段，两者分叉时明说，不让两处并排自相矛盾 -->
          <el-alert v-if="view.fieldsCurrent === false" type="warning" show-icon :closable="false" style="margin-top: 4px"
                    :title="String(view.fieldsNote || `字段级记录对应第 ${fmt(view.fieldsRevisionSeq)} 版，文本已在第 ${fmt(view.textRevisionSeq)} 版修订，以文本为准`)" />
          <el-table :data="viewFields" size="small" border max-height="200" style="margin-top: 4px">
            <el-table-column label="#" width="50">
              <template #default="{ row }">{{ fmt(row.seq) }}</template>
            </el-table-column>
            <el-table-column label="字段" width="140">
              <template #default="{ row }">{{ fmt(row.label) }}</template>
            </el-table-column>
            <el-table-column label="内容" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ fmt(row.value) }}</template>
            </el-table-column>
            <el-table-column label="录入" width="220">
              <template #default="{ row }">{{ fmt(row.operatorName) }}　{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
        </template>
        <span v-else class="muted" style="margin-left: 6px">
          {{ view.grossFindingPresent ? '历史标本或纯自由文本，无字段级记录（不从文本反解析）' : '无字段级记录' }}
        </span>
      </div>

      <h4>蜡块（{{ viewBlocks.length }}）</h4>
      <el-table :data="viewBlocks" size="small" border max-height="220">
        <el-table-column label="块号" width="70">
          <template #default="{ row }">{{ fmt(row.block_no) }}</template>
        </el-table-column>
        <el-table-column label="蜡块编码" width="170">
          <template #default="{ row }"><span class="code">{{ fmt(row.block_code) }}</span></template>
        </el-table-column>
        <el-table-column label="组织描述" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ fmt(row.tissue_desc) }}</template>
        </el-table-column>
        <el-table-column label="建块" width="180">
          <template #default="{ row }">{{ fmt(row.created_by_name) }}　{{ fmtTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="包埋" width="140">
          <template #default="{ row }">{{ fmtTime(row.embedded_at) }}</template>
        </el-table-column>
        <el-table-column label="切片" width="60">
          <template #default="{ row }">{{ num(row.slide_count) }}</template>
        </el-table-column>
        <template #empty>尚无蜡块</template>
      </el-table>

      <h4>取材打点（{{ viewEvents.length }}）</h4>
      <el-table :data="viewEvents" size="small" border max-height="160">
        <el-table-column label="时刻" width="150">
          <template #default="{ row }">{{ fmtTime(row.occurred_at) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="110">
          <template #default="{ row }">{{ fmt(row.operator_name) }}</template>
        </el-table-column>
        <el-table-column label="备注" show-overflow-tooltip>
          <template #default="{ row }">{{ fmt(row.remark) }}</template>
        </el-table-column>
        <template #empty>该标本尚无取材打点</template>
      </el-table>
      <p v-if="view.note" class="muted">{{ view.note }}</p>
    </div>
    <template #footer>
      <el-button type="primary" size="small" @click="viewDialog = false">关闭</el-button>
    </template>
  </el-dialog>

  <!-- ============ 取材登记 / 修订取材描述（v59 起同一张表单两种模式） ============ -->
  <el-dialog v-model="dialog" :title="mode === 'REVISE' ? '修订取材描述' : '取材登记'" width="760px" top="6vh">
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

    <el-alert v-if="mode === 'GROSSING' && num(current.block_count) > 0" type="warning" show-icon :closable="false" class="cav"
              title="该标本已有蜡块：第二次及以后的取材必须显式勾选「补取材」，否则后端返 5223。误点两次「取材」凭空多出一组蜡块是事故，与病理医师看完 HE 片后下的补取材必须区分开。" />

    <!-- 已有大体所见：先给人看见，再决定怎么填——否则填了结构化字段提交才吃 5222 -->
    <div v-if="existingGross" class="sec" v-loading="existingLoading">
      <b>{{ mode === 'REVISE' ? '当前大体所见（修订前，将记为 old_text）' : '该标本已录入的大体所见' }}</b>
      <el-tag size="small" type="info" style="margin-left: 6px">
        {{ mode === 'REVISE' ? `当前第 ${fmt(existingTextSeq)} 版` : '只读；不覆盖，勾选「补取材」后可作为新版本追加' }}</el-tag>
      <pre>{{ existingGross }}</pre>
    </div>

    <!-- v59：修订预填——字段来自库里最新一版字段行，自由描述由当前文本剥掉字段前缀得到；拆不开时整段放自由描述 -->
    <el-alert v-if="mode === 'REVISE' && revisePrefillNote" type="warning" show-icon :closable="false" class="cav"
              :title="revisePrefillNote" />

    <el-form label-width="100px" size="small">
      <el-form-item v-if="mode === 'GROSSING'" label="补取材">
        <el-switch v-model="form.append" />
        <span class="muted" style="margin-left: 8px">已诊断标本的补取材同样必须显式声明</span>
      </el-form-item>

      <el-form-item v-if="mode === 'GROSSING' && existingGross && !form.append" label="大体所见">
        <span class="muted">已有内容（见上），本次不再填写：勾选「补取材」后可填写本次描述（作为新版本追加）；修订既有描述用列表里的「修订取材描述」</span>
      </el-form-item>

      <el-alert v-if="mode === 'GROSSING' && existingGross && form.append" type="info" show-icon :closable="false" class="cav"
                title="本次填写的描述将作为新版本追加：新文本 = 既有文本 + 「。补取材：」 + 本次描述，不覆盖既有内容；不填则只加蜡块、不出新版本。" />

      <el-form-item v-if="showGrossInputs" label="取材模板">
        <el-select v-model="form.templateCode" clearable placeholder="不用模板" style="width: 260px"
                   @change="onTemplateChange">
          <el-option v-for="t in templates" :key="String(t.code)" :value="String(t.code)"
                     :label="`${t.name}（${t.code}）`">
            <span>{{ t.name }}（{{ t.code }}）</span>
            <el-tag size="small" :type="t.source === 'CONFIG' ? 'warning' : 'info'"
                    style="margin-left: 6px">{{ t.source === 'CONFIG' ? '配置' : '内置' }}</el-tag>
          </el-option>
        </el-select>
        <span v-if="templateNote" class="muted" style="margin-left: 8px">字段清单可配，模板管理未做；模板码随本版修订落库</span>
      </el-form-item>

      <el-form-item v-if="showGrossInputs && currentTemplate && currentTemplate.example" label="示例描述">
        <span class="muted">{{ currentTemplate.example }}</span>
      </el-form-item>
      <el-form-item v-else-if="showGrossInputs && currentTemplate" label="示例描述">
        <span class="muted">—（配置新增的模板无示例文本：sys_config 只有 255 字符，不编一段假的）</span>
      </el-form-item>

      <template v-if="showGrossInputs">
        <el-form-item v-for="f in grossFields" :key="f" :label="f">
          <el-input v-model="form.gross[f]" maxlength="300" show-word-limit />
        </el-form-item>

        <el-form-item label="自由描述">
          <el-input v-model="form.grossText" type="textarea" :rows="2"
                    placeholder="与上面的结构化字段拼成一段大体所见；总长上限 2000 字" />
        </el-form-item>
      </template>

      <el-form-item v-if="mode === 'GROSSING'" label="蜡块">
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

      <el-form-item v-if="mode === 'GROSSING'" label="备注">
        <el-input v-model="form.remark" maxlength="255" show-word-limit />
      </el-form-item>
    </el-form>

    <el-alert v-if="mode === 'GROSSING'" type="info" :closable="false" show-icon
              title="大体所见为空时首写；已有内容时不覆盖——勾选「补取材」后填写的描述作为新版本追加（v59），未勾选还传描述会被拒（5222）。该列同时被病理医师出报告时修订。" />
    <el-alert v-else type="info" :closable="false" show-icon
              title="提交后生成新一版修订（来源「取材修订」）：字段行落在新版下、文本整体替换、修订前原文留痕；仅未诊断前可用，诊断后由诊断端点修订。与当前文本完全相同会被拒（没有变化就没有版本）。" />

    <template #footer>
      <el-button size="small" @click="dialog = false">取消</el-button>
      <el-button v-if="mode === 'GROSSING'" type="primary" size="small" :loading="saving" @click="submit">登记取材</el-button>
      <el-button v-else type="warning" size="small" :loading="saving" @click="submitRevise">提交修订</el-button>
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

    <!-- 已落库的字段级记录（v58，2530）：取材成功后回读 GET /grossing/{id}，展示的是库里的行，不是表单里的值 -->
    <div class="sec" v-loading="resultFieldsLoading">
      <b>已落库的字段级记录</b>
      <template v-if="resultView.fieldsAvailable === true">
        <el-tag size="small" type="success" style="margin-left: 6px">{{ resultFields.length }} 项，按填写顺序</el-tag>
        <el-table :data="resultFields" size="small" border max-height="200" style="margin-top: 4px">
          <el-table-column label="#" width="50">
            <template #default="{ row }">{{ fmt(row.seq) }}</template>
          </el-table-column>
          <el-table-column label="字段" width="140">
            <template #default="{ row }">{{ fmt(row.label) }}</template>
          </el-table-column>
          <el-table-column label="内容" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ fmt(row.value) }}</template>
          </el-table-column>
          <el-table-column label="录入时刻" width="150">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
      </template>
      <span v-else class="muted" style="margin-left: 6px">
        {{ result.grossFindingWritten === true
          ? '无字段级记录：本次只写了自由描述，或该标本此前只有自由文本'
          : '无字段级记录：本次未写大体所见' }}
      </span>
    </div>
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
/** 字段清单 = 模板字段 + 表单里已有的其它键（修订预填自库里字段行时，不在模板里的字段也得有输入框，不能悄悄丢） */
const grossFields = computed<string[]>(() => {
  const out: string[] = [...((currentTemplate.value?.fields ?? []) as string[])]
  for (const k of Object.keys(form.gross)) if (!out.includes(k)) out.push(k)
  return out
})

/** 换模板：同名字段保留已填值，不在新模板里但已填了内容的字段也保留——悄悄吞掉用户写的字比多显示一个输入框坏得多 */
function onTemplateChange() {
  const kept = { ...form.gross }
  form.gross = {}
  for (const f of ((currentTemplate.value?.fields ?? []) as string[])) form.gross[f] = kept[f] ?? ''
  for (const [k, v] of Object.entries(kept)) if (v && v.trim() && !(k in form.gross)) form.gross[k] = v
}

/* ---------------- 取材登记 / 修订取材描述 ---------------- */
const dialog = ref(false)
/** GROSSING = 取材登记（POST /grossing）；REVISE = 修订取材描述（PUT /grossing/{id}/fields，v59） */
const mode = ref<'GROSSING' | 'REVISE'>('GROSSING')
const resultDialog = ref(false)
const saving = ref(false)
const current = ref<Row>({})
const result = ref<Row>({})
const resultBlocks = computed<Row[]>(() => (result.value.blocks ?? []) as Row[])
/** 描述输入框何时出现：修订模式恒出；取材模式在无既有大体所见、或勾了补取材（作为新版本追加）时出 */
const showGrossInputs = computed(
  () => mode.value === 'REVISE' || !existingGross.value || form.append,
)

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

/**
 * 打开登记对话框前先取该标本已录入的大体所见（只读）。
 * 后端对「已有大体所见还传结构化字段」直接报 5222，前端若不先把既有内容摆出来，
 * 人就得填完一整段再被拒——补取材场景（已有蜡块）几乎必然踩到。
 */
const existingGross = ref('')
const existingTextSeq = ref<unknown>(null)   // v59：既有文本的版号（textRevisionSeq），修订对话框里给人看
const existingLoading = ref(false)

async function loadExistingGross(specimenId: number) {
  existingLoading.value = true
  try {
    const d = (await client.get(`/pathology/process/grossing/${specimenId}`)).data.data as Row
    existingGross.value = d.grossFindingPresent === true ? String(d.grossFinding ?? '') : ''
    existingTextSeq.value = d.textRevisionSeq ?? null
  } catch {
    existingGross.value = ''   // 取不到就按「未知」处理：仍允许填写，由后端最终裁决
    existingTextSeq.value = null
  } finally {
    existingLoading.value = false
  }
}

function openGrossing(row: Row) {
  mode.value = 'GROSSING'
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
  existingGross.value = ''
  existingTextSeq.value = null
  revisePrefillNote.value = ''
  dialog.value = true
  void loadExistingGross(Number(row.id))
}

/* ---------------- 修订取材描述（v59，2530 复核） ---------------- */
const revisePrefillNote = ref('')

/** 只对未诊断的标本给入口：诊断后由诊断端点修订，后端也会以 5221 拒 */
function canRevise(row: Row): boolean {
  return row.diagnosed_at == null && row.rejected_at == null
}

/**
 * 把当前文本按取材端点的拼装规则（「标签：值」以「；」相连，末尾「。」接自由文本）拆回字段 + 自由描述。
 * 字段来自库里最新一版字段行，不从文本猜；文本与字段拼不上（如经补取材追加、或只有自由文本）时，
 * 整段放进自由描述、字段留空——既不丢字，也不会提交后拼出重复内容。
 */
function splitGross(text: string, fields: Row[]): { gross: Record<string, string>; free: string; parsed: boolean } {
  const gross: Record<string, string> = {}
  for (const f of fields) gross[String(f.label)] = String(f.value ?? '')
  if (!fields.length) return { gross, free: text, parsed: true }
  const prefix = fields.map((f) => `${String(f.label)}：${String(f.value ?? '')}`).join('；')
  if (text === prefix) return { gross, free: '', parsed: true }
  if (text.startsWith(prefix + '。')) return { gross, free: text.slice(prefix.length + 1), parsed: true }
  return { gross: {}, free: text, parsed: false }
}

async function openRevise(row: Row) {
  let d: Row
  try {
    d = (await client.get(`/pathology/process/grossing/${Number(row.id)}`)).data.data as Row
  } catch {
    return   // 拦截器已提示；取不到当前内容就不开表单，免得对着空白改
  }
  if (d.grossFindingPresent !== true) {
    ElMessage.warning('该标本尚无大体所见，无从修订：请先「取材登记」填写')
    return
  }
  mode.value = 'REVISE'
  current.value = row
  const text = String(d.grossFinding ?? '')
  const fields = (d.fieldsCurrent === true ? (d.fields ?? []) : []) as Row[]
  const split = splitGross(text, fields)
  const revisions = (d.revisions ?? []) as Row[]
  const lastTemplate = revisions.length ? revisions[revisions.length - 1]?.templateCode : null
  const templateCode = lastTemplate != null && templates.value.some((t) => String(t.code) === String(lastTemplate))
    ? String(lastTemplate) : ''
  Object.assign(form, {
    templateCode,
    gross: split.gross,
    grossText: split.free,
    append: false,
    remark: '',
    blocks: [{ tissueDesc: '' }],
  })
  // 模板字段里没填过的也给输入框（值空则提交时略去），顺序：模板字段在前、既有字段在后
  const tf = (templates.value.find((t) => String(t.code) === templateCode)?.fields ?? []) as string[]
  for (const f of tf) if (!(f in form.gross)) form.gross[f] = ''
  revisePrefillNote.value = split.parsed
    ? (d.fieldsAvailable === true && d.fieldsCurrent !== true
        ? `库里的字段行属于第 ${fmt(d.fieldsRevisionSeq)} 版，与当前第 ${fmt(d.textRevisionSeq)} 版文本不同版，未按它预填；已把当前文本整段放入自由描述`
        : '')
    : '当前文本不是由库里最新字段直接拼出的形态（如经补取材追加、或只有自由文本），已整段放入自由描述、字段留空；如需字段化请自行拆分'
  existingGross.value = text
  existingTextSeq.value = d.textRevisionSeq ?? null
  dialog.value = true
}

async function submitRevise() {
  saving.value = true
  try {
    const gross: Record<string, string> = {}
    for (const [k, v] of Object.entries(form.gross)) if (v && v.trim()) gross[k] = v.trim()
    if (!Object.keys(gross).length && !form.grossText.trim()) {
      ElMessage.warning('字段与自由描述至少填一项')
      return
    }
    const r = (await client.put(`/pathology/process/grossing/${Number(current.value.id)}/fields`, {
      templateCode: form.templateCode || undefined,
      gross: Object.keys(gross).length ? gross : undefined,
      grossText: form.grossText || undefined,
    })).data.data as Row
    dialog.value = false
    ElMessage.success(`已生成第 ${fmt(r.revisionSeq)} 版取材描述（字段 ${num(r.grossFieldCount)} 项）`)
    await load()
    emit('changed')
  } finally {
    saving.value = false
  }
}

/* ---------------- 大体所见查看（只读，2530） ---------------- */
const viewDialog = ref(false)
const viewLoading = ref(false)
const view = ref<{ specimen?: Row; grossFinding?: unknown; grossFindingPresent?: unknown;
  fieldsAvailable?: unknown; fields?: Row[]; revisions?: Row[];
  fieldsRevisionSeq?: unknown; textRevisionSeq?: unknown; fieldsNote?: unknown; fieldsCurrent?: unknown;   // v59：字段与文本各自的版号
  diagnosedAt?: unknown; blocks?: Row[]; grossingEvents?: Row[]; note?: unknown }>({})
const viewBlocks = computed<Row[]>(() => (view.value.blocks ?? []) as Row[])
const viewEvents = computed<Row[]>(() => (view.value.grossingEvents ?? []) as Row[])
const viewFields = computed<Row[]>(() => (view.value.fields ?? []) as Row[])

/* ---------------- 取材成功后回读已落库字段（v58，2530） ---------------- */
const resultView = ref<{ fieldsAvailable?: unknown; fields?: Row[] }>({})
const resultFields = computed<Row[]>(() => (resultView.value.fields ?? []) as Row[])
const resultFieldsLoading = ref(false)

/** 展示的是库里的 path_gross_field 行（GET /grossing/{id}），不是表单里的值——落库了什么就显示什么 */
async function loadResultFields(specimenId: number) {
  resultFieldsLoading.value = true
  resultView.value = {}
  try {
    resultView.value = (await client.get(`/pathology/process/grossing/${specimenId}`)).data.data as typeof resultView.value
  } catch {
    resultView.value = {}   // 回读失败按「无记录」显示，不拿表单值冒充库里的行
  } finally {
    resultFieldsLoading.value = false
  }
}

async function openView(specimenId: number) {
  viewDialog.value = true
  viewLoading.value = true
  try {
    view.value = (await client.get(`/pathology/process/grossing/${specimenId}`)).data.data as typeof view.value
  } finally {
    viewLoading.value = false
  }
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

    // v59：无既有大体所见 → 首写；已有且勾了补取材 → 作为新版本追加；已有且未勾 → 一律不传（后端会拒 5222）
    const sendGross = !existingGross.value || form.append
    result.value = (await client.post('/pathology/process/grossing', {
      specimenId: Number(current.value.id),
      templateCode: form.templateCode || undefined,
      gross: sendGross && Object.keys(gross).length ? gross : undefined,
      grossText: sendGross && form.grossText ? form.grossText : undefined,
      append: form.append,
      remark: form.remark || undefined,
      blocks: form.blocks.map((b) => ({ tissueDesc: b.tissueDesc || undefined })),
    })).data.data as Row
    dialog.value = false
    resultDialog.value = true
    void loadResultFields(Number(current.value.id))   // 回读库里的字段行，不用表单值冒充
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
.sec { margin-bottom: 10px; }
.sec pre {
  margin: 4px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
}
h4 { margin: 12px 0 6px; }
</style>
