<template>
  <el-card>
    <template #header>
      <span style="font-weight: 600">自由文本过敏史 · 人工核对工作台</span>
      <el-tag type="danger" size="small" effect="plain" style="margin-left: 10px">纯人工，不解析、不预填</el-tag>
      <el-button type="primary" size="small" :loading="loading" style="margin-left: 12px" @click="load">
        刷新待办
      </el-button>
      <el-switch v-model="includeReviewed" size="small" style="margin-left: 12px"
                 active-text="含已核对" inactive-text="仅待核对" @change="load" />
      <el-input-number v-model="limit" :min="10" :max="200" :step="10" size="small"
                       style="margin-left: 12px; width: 130px" @change="load" />
      <span class="muted">条上限</span>
    </template>

    <!-- ============ 本页最要紧的一条纪律，写在最上面 ============ -->
    <el-alert type="error" show-icon :closable="false" class="caveat"
              title="本页只展示原文，不做关键词高亮、不给建议过敏原、不做任何自动匹配">
      <div>
        「青霉素过敏」与「青霉素皮试阴性」文本相近而<b>语义相反</b>。一个预填好的下拉框会把人工确认
        降级成<b>人工点确定</b>——预填错了，多数人不会去改，结果是该拦的不拦、不该拦的拦住。
        请逐条<b>读原文</b>后再选过敏原。
      </div>
      <div>原文永远保留在患者档案里（本模块只读不写），<b>不会被结构化结果覆盖</b>；原文日后若被修订，该患者自动重回待办。</div>
    </el-alert>

    <cdss-gate-bar />

    <el-descriptions :column="4" border size="small" class="caveat" title="待办口径">
      <el-descriptions-item label="全院待核对患者数">
        <b :class="{ pending: Number(pendingTotal ?? 0) > 0 }">{{ num(pendingTotal) }}</b>
      </el-descriptions-item>
      <el-descriptions-item label="本次列出">{{ items.length }}</el-descriptions-item>
      <el-descriptions-item label="单次上限">{{ num(pageLimit) }}</el-descriptions-item>
      <el-descriptions-item label="判据">原文非空 且 无匹配「<b>当前</b>原文」的核对记录</el-descriptions-item>
    </el-descriptions>
    <el-alert v-if="truncated" type="warning" show-icon :closable="false" class="caveat"
              :title="`待办超过 ${pageLimit} 条，此处只列前 ${pageLimit} 条（不翻页）——把上限调大或分批清理`" />
    <el-alert v-if="note" type="info" :closable="false" class="caveat" :title="note" />

    <el-table :data="items" v-loading="loading" size="small" border stripe max-height="520">
      <el-table-column prop="patient_no" label="患者号" width="120" />
      <el-table-column prop="patient_name" label="姓名" width="100" />
      <el-table-column label="性别" width="60">
        <template #default="{ row }">{{ sexText(row.sex) }}</template>
      </el-table-column>
      <el-table-column prop="birth_date" label="出生日期" width="110" />
      <el-table-column label="过敏史原文（一字不改）" min-width="320">
        <!-- 原文整段照出：不截断、不加粗任何词、不高亮 -->
        <template #default="{ row }"><span class="raw">{{ row.allergy_history }}</span></template>
      </el-table-column>
      <el-table-column label="已结构化" width="90">
        <template #default="{ row }">{{ num(row.structured_count) }}</template>
      </el-table-column>
      <el-table-column label="最近结论" width="200">
        <template #default="{ row }">
          <el-tag v-if="row.latest_resolution" size="small"
                  :type="row.latest_resolution === 'UNCLEAR' ? 'warning' : 'info'">
            {{ enumText(RESOLUTION_TEXT, row.latest_resolution) }}
          </el-tag>
          <span v-else class="muted">未核对</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openReview(row)">人工核对</el-button>
          <el-button link type="primary" size="small" @click="openProfile(row)">过敏档</el-button>
        </template>
      </el-table-column>
      <template #empty>暂无待核对的自由文本过敏史</template>
    </el-table>
  </el-card>

  <!-- ============ 核对抽屉 ============ -->
  <el-drawer v-model="drawer" size="62%" :title="`人工核对 — ${current?.patient_name ?? ''}（${current?.patient_no ?? ''}）`">
    <el-alert v-if="staleWarn" type="error" show-icon :closable="false" class="caveat"
              title="原文已被修订，本次核对未提交">
      <div>{{ staleWarn }}</div>
      <div>待办已刷新，请关闭本抽屉后<b>按最新原文重新核对</b>。</div>
    </el-alert>

    <div class="section-title">① 过敏史原文（系统一字未改，也未做任何标注）</div>
    <div class="raw-box">{{ sourceText }}</div>
    <div class="muted" style="margin: 4px 0 14px">
      快照于打开本抽屉时取得；提交时后端会与当前过敏史逐字比对，不一致即拒收（5613），
      避免把按旧原文做的判断记成对新原文的核对。
    </div>

    <div class="section-title">② 人工结论</div>
    <el-radio-group v-model="resolution" class="res-group">
      <el-radio value="STRUCTURED" border>已确认，登记结构化过敏原</el-radio>
      <el-radio value="NO_ALLERGY" border>原文不构成过敏记录（如「否认过敏史」「皮试阴性」）</el-radio>
      <el-radio value="UNCLEAR" border>无法判定，需再询问患者</el-radio>
    </el-radio-group>
    <el-alert v-if="resolution === 'UNCLEAR'" type="warning" :closable="false" class="caveat"
              title="「无法判定」不等于「无过敏」：该患者的开单审查仍会被标记为覆盖不全，提示不会消失。这正是本档位存在的意义。" />
    <el-alert v-if="resolution === 'NO_ALLERGY'" type="info" :closable="false" class="caveat"
              title="「不构成过敏记录」是一个真结论，会留痕（谁、何时、按哪段原文判的），与「没人看过」区分开。" />

    <template v-if="resolution === 'STRUCTURED'">
      <div class="section-title">③ 逐条选择过敏原（<b>由你选，系统不给候选、不排序、不推荐</b>）</div>
      <div v-for="(row, i) in picks" :key="i" class="pick-row">
        <el-select v-model="row.allergenId" filterable clearable placeholder="搜索并选择过敏原"
                   style="width: 260px" @change="() => onPick(row)">
          <el-option v-for="a in allergens" :key="Number(a.id)" :value="Number(a.id)"
                     :label="`${a.code}　${a.name}`" :disabled="a.enabled !== true">
            <span>{{ a.code }}　{{ a.name }}</span>
            <span class="muted" style="margin-left: 8px">
              映射药品 {{ num(a.mapped_drug_count) }}
              <template v-if="a.enabled !== true">　已停用，不能新登记</template>
            </span>
          </el-option>
        </el-select>
        <el-select v-model="row.severity" style="width: 110px" placeholder="严重度">
          <el-option v-for="s in SEVERITIES" :key="s" :value="s" :label="enumText(SEVERITY_TEXT, s)" />
        </el-select>
        <el-select v-model="row.source" style="width: 170px" placeholder="来源">
          <el-option v-for="s in SOURCES" :key="s" :value="s" :label="enumText(SOURCE_TEXT, s)" />
        </el-select>
        <el-input v-model="row.manifestation" style="width: 190px" placeholder="表现（皮疹/喉头水肿…）" />
        <el-button link type="danger" size="small" @click="picks.splice(i, 1)">移除</el-button>
        <div v-if="row.mappedDrugCount === 0" class="gap">
          该过敏原<b>尚未映射任何院内药品</b>：登记后仍<b>不会</b>在开单时命中，需药剂科在「过敏规则维护」补映射。
        </div>
      </div>
      <el-button size="small" @click="picks.push(newPick())">+ 增加一条过敏原</el-button>
    </template>

    <div class="section-title" style="margin-top: 16px">④ 核对备注（选填，会随结论留痕）</div>
    <el-input v-model="reviewNote" type="textarea" :rows="2" maxlength="500" show-word-limit
              placeholder="例如：已电话联系患者本人确认；或：原文含既往用药史，非过敏内容" />

    <template #footer>
      <span class="muted" style="float: left">确认人取当前登录人——这条记录会拦处方，必须有人签字。</span>
      <el-button size="small" @click="drawer = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submit">提交核对结论</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import client from '../../../api/client'
import CdssGateBar from './CdssGateBar.vue'
import {
  RESOLUTION_TEXT, SEVERITY_TEXT, SOURCE_TEXT, enumText, num, type Row,
} from './cdssCommon'

const SEVERITIES = ['UNKNOWN', 'MILD', 'MODERATE', 'SEVERE']
const SOURCES = ['TEXT_REVIEW', 'SELF_REPORT', 'CLINICAL', 'TEST', 'OTHER']

interface Pick {
  allergenId: number | undefined
  severity: string
  source: string
  manifestation: string
  /** 选中过敏原的已映射药品数：0 表示登记后也拦不住，必须当场说出来 */
  mappedDrugCount: number | null
}

const router = useRouter()
const items = ref<Row[]>([])
const allergens = ref<Row[]>([])
const pendingTotal = ref<number | null>(null)
const pageLimit = ref<number | null>(null)
const truncated = ref(false)
const note = ref('')
const loading = ref(false)
const saving = ref(false)
const includeReviewed = ref(false)
const limit = ref(50)

const drawer = ref(false)
const current = ref<Row | null>(null)
/** 打开抽屉那一刻的原文快照：提交时原样回传，**不做任何 trim/规整**，否则 5613 永远对不上 */
const sourceText = ref('')
const resolution = ref('STRUCTURED')
const picks = ref<Pick[]>([])
const reviewNote = ref('')
const staleWarn = ref('')

function newPick(): Pick {
  return { allergenId: undefined, severity: 'UNKNOWN', source: 'TEXT_REVIEW', manifestation: '', mappedDrugCount: null }
}

function sexText(v: unknown): string {
  return ({ M: '男', F: '女', U: '未知' } as Record<string, string>)[String(v)] ?? String(v ?? '—')
}

async function load() {
  loading.value = true
  try {
    const d = (await client.get('/cdss/allergy/migration/worklist', {
      params: { limit: limit.value, includeReviewed: includeReviewed.value },
    })).data.data
    items.value = (d.items ?? []) as Row[]
    truncated.value = !!d.truncated
    pageLimit.value = d.limit ?? null
    pendingTotal.value = d.pendingTotal ?? null
    note.value = d.note ?? ''
  } finally {
    loading.value = false
  }
}

/** 过敏原目录：只用来给人选，**不按原文过滤、不排序、不预选** */
async function loadAllergens() {
  allergens.value = ((await client.get('/cdss/allergy/rules', { params: { limit: 200 } }))
    .data.data.allergens ?? []) as Row[]
}

function openReview(row: Row) {
  current.value = row
  sourceText.value = String(row.allergy_history ?? '')
  resolution.value = 'STRUCTURED'
  picks.value = [newPick()]
  reviewNote.value = ''
  staleWarn.value = ''
  drawer.value = true
  if (allergens.value.length === 0) void loadAllergens()
}

function openProfile(row: Row) {
  void router.push({ path: '/cdss/allergy-profile', query: { patientId: String(row.patient_id) } })
}

/** 选中过敏原后回填**该过敏原的映射药品数**——这是事实，不是对原文的推断 */
function onPick(row: Pick) {
  const a = allergens.value.find((x) => Number(x.id) === row.allergenId)
  row.mappedDrugCount = a ? Number(a.mapped_drug_count ?? 0) : null
}

async function submit() {
  if (!current.value) return
  if (resolution.value === 'STRUCTURED') {
    const chosen = picks.value.filter((p) => p.allergenId)
    if (chosen.length === 0) {
      ElMessage.warning('结论为「已确认」时至少选择一个过敏原；原文不构成过敏请选第二项，说不准请选第三项')
      return
    }
  }
  saving.value = true
  try {
    const body = {
      sourceText: sourceText.value,
      resolution: resolution.value,
      allergens: resolution.value === 'STRUCTURED'
        ? picks.value.filter((p) => p.allergenId).map((p) => ({
          allergenId: p.allergenId,
          severity: p.severity,
          manifestation: p.manifestation || undefined,
          source: p.source,
        }))
        : [],
      note: reviewNote.value || undefined,
    }
    const d = (await client.post(
      `/cdss/allergy/migration/patients/${Number(current.value.patient_id)}/review`, body)).data.data
    ElMessage.success(`已留痕（核对记录 #${d.reviewId}，登记 ${(d.createdAllergies ?? []).length} 条结构化过敏原）`)
    drawer.value = false
    await load()
  } catch (e) {
    // 5613：原文在核对期间被改过。红字由拦截器统一弹过，这里再把「下一步做什么」留在抽屉里
    const err = e as { bizCode?: number; message?: string }
    if (err?.bizCode === 5613) {
      staleWarn.value = err.message ?? '提交的原文快照与当前过敏史不一致'
      await load()
    }
  } finally {
    saving.value = false
  }
}

void load()
</script>

<style scoped>
.caveat { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.pending { color: var(--el-color-danger); }
.raw { white-space: pre-wrap; word-break: break-all; }
.raw-box {
  white-space: pre-wrap;
  word-break: break-all;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  font-family: Consolas, 'Courier New', monospace;
  line-height: 1.7;
}
.section-title { font-weight: 600; margin: 12px 0 6px; }
.res-group { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
.pick-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 8px; }
.gap { width: 100%; color: var(--el-color-warning); font-size: 12px; }
</style>
