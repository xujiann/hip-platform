<template>
  <el-card>
    <template #header>
      <span style="font-weight: 600">出院前审签检查</span>
      <el-tag type="info" size="small" style="margin-left: 10px">纯只读预检，不拦任何东西</el-tag>
      <el-input v-model="admissionIdText" size="small" style="width: 230px; margin-left: 12px"
                placeholder="住院 id（inp_admission.id）" clearable @keyup.enter="check">
        <template #append>
          <el-button :loading="loading" @click="check">检查</el-button>
        </template>
      </el-input>
      <el-button link type="primary" size="small" style="margin-left: 8px"
                 @click="router.push('/inpatient/countersign')">
        ← 上级审签工作台
      </el-button>
    </template>

    <!-- ================================================================
         一、上级资格口径（后端 caveat 原文照登）——本页最要紧的一条
         ================================================================ -->
    <el-alert v-if="body?.caveat" type="warning" show-icon :closable="false" class="caveat"
              title="先看这一条：本平台不判定「谁有资格当上级」（后端随本端点下发的 caveat，一字未改）">
      <div class="raw">{{ body.caveat }}</div>
      <div class="muted" style="margin-top: 6px">
        所以下表里「没有缺项」只意味着<b>每份应审签病历都有另一位不同于书写人的账号签过字</b>，
        <b>不意味着签字的人是上级</b>。上下级关系由院方在账号授权与职称维护上保证，本平台不代为判定。
      </div>
    </el-alert>

    <!-- ================================================================
         二、本端点的边界：三句话说清「它到底证明了什么」
         ================================================================ -->
    <el-alert type="info" show-icon :closable="false" class="caveat" title="本页的裁决边界（先看清再用）">
      <div>①「缺项」是客观事实，<b>三个 gate 档位下都照算</b>——off 档不提示，但缺项照样是真的。</div>
      <div>
        ②「会被挡」只表示<b>若把这条 gate 挂在出院/归档挡点上，此刻会不会被挡</b>；
        本端点自己<b>不拦截任何写入</b>，当前是否真的已挂到出院流程上，不在本页能证明的范围内。
      </div>
      <div>
        ③ 本端点<b>不校验住院 id 是否存在</b>：id 打错时同样返回「无缺项」。
        请核对下方回显的住院 id 与您要查的那次住院一致——一个绿灯不能只看颜色。
      </div>
    </el-alert>

    <template v-if="body">
      <!-- ============ 裁决 ============ -->
      <el-descriptions :column="3" border size="small" class="caveat" title="裁决">
        <el-descriptions-item label="住院 id（后端回显）">
          <b class="mono">{{ num(body.admissionId) }}</b>
        </el-descriptions-item>
        <el-descriptions-item label="gate 档位">
          <el-tag :type="gateMeta(body.gate).tag" size="small">{{ gateMeta(body.gate).label }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="此刻是否会被挡">
          <el-tag v-if="body.blocked" type="danger" size="small">会被挡（业务码 {{ num(body.blockCode) }}）</el-tag>
          <el-tag v-else type="success" size="small">不会被挡</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="审签缺项条数" :span="3">
          <b :class="{ 'danger-text': findings.length > 0 }">{{ findings.length }}</b> 条
          <span class="muted" style="margin-left: 8px">
            这里<b>刻意不给「审签覆盖率」</b>：本端点只返回缺项（分子），
            不返回「本次住院一共有几份应审签病历」（分母）。分母未知时算出来的百分比是编的，
            「100%」到底是 2/2 还是 200/200 一眼看不出来的数，比不给更坏。
            要看分母，请到住院病历列表按下方「纳入审签的病历类型」自行核对。
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="纳入审签的病历类型" :span="3">
          <el-tag v-for="t in body.requiredTypes ?? []" :key="t" size="small" style="margin-right: 6px">
            {{ recordTypeText(t) }}
          </el-tag>
          <span v-if="!(body.requiredTypes ?? []).length" class="muted">—</span>
          <span class="muted" style="margin-left: 8px">
            不在这个白名单里的病历（如查房记录 ROUND）<b>不进本页判定</b>——缺项为 0 不代表全部病历都签过。
          </span>
        </el-descriptions-item>
      </el-descriptions>

      <!-- blockMessage / warnings 一律原样上屏 -->
      <el-alert v-if="body.blocked" type="error" show-icon :closable="false" class="caveat"
                title="gate=block：此刻走出院/归档挡点会被拒">
        <div class="raw">{{ body.blockMessage }}</div>
      </el-alert>
      <el-alert v-for="(w, i) in body.warnings ?? []" :key="`w${i}`" type="warning" show-icon
                :closable="false" class="caveat" :title="w" />
      <el-alert v-if="!body.blocked && (body.warnings ?? []).length === 0 && findings.length > 0"
                type="info" show-icon :closable="false" class="caveat"
                title="有缺项但既不挡也不提示——当前 gate=off。缺项照样是真的，只是 off 档不拿它做任何事。" />

      <!-- ============ 缺项 ============ -->
      <div class="section-title">审签缺项明细</div>
      <el-table :data="findings" size="small" border stripe>
        <el-table-column label="缺项类型" width="250">
          <template #default="{ row }">
            <el-tag :type="findingMeta(row.code).tag" size="small">{{ findingMeta(row.code).label }}</el-tag>
            <div class="mono muted">{{ row.code }}</div>
          </template>
        </el-table-column>
        <el-table-column label="病历" width="220">
          <template #default="{ row }">
            {{ recordTypeText(row.recordType) }}
            <div class="muted">病历 id {{ num(row.recordId) }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="text" label="说明（后端原文）" min-width="300" show-overflow-tooltip />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRecord(Number(row.recordId))">
              审签历史 / 审签
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div style="padding: 12px">
            本次住院在「纳入审签的病历类型」范围内<b>没有审签缺项</b>。
            请同时核对上方回显的住院 id 是否正确——本端点不校验住院是否存在。
          </div>
        </template>
      </el-table>
    </template>

    <el-empty v-else-if="!loading" description="请输入住院 id 后点「检查」" :image-size="70" />
  </el-card>

  <!-- ==================================================================
       某份病历的审签历史 + 审签
       ================================================================== -->
  <el-drawer v-model="drawer" size="72%" :title="`病历 id ${num(recordId)} — 审签历史与审签`">
    <el-alert v-if="loaded" :type="valid ? 'success' : 'warning'" show-icon :closable="false" class="caveat"
              :title="valid
                ? '当前正文已有一条有效审签（validForCurrentContent = true）'
                : '当前正文没有任何有效审签（validForCurrentContent = false）'" />

    <!-- 提交前判定：能不能签、为什么不能 -->
    <el-alert v-if="loaded && signBlockReason" type="error" show-icon :closable="false" class="caveat"
              title="本份病历在本页不能签（提交前判定，不是等后端拒）">
      <div class="raw">{{ signBlockReason }}</div>
    </el-alert>

    <div class="section-title">审签记录（时间正序，只增不改不删）</div>
    <!-- 三态图例：DIGEST_ONLY 不是空白，是一个有原因的确定状态 -->
    <el-alert type="info" show-icon :closable="false" class="caveat"
              title="「签的哪一版」三态图例（version_source）">
      <div v-for="(m, k) in VERSION_SOURCE_META" :key="k" style="margin-bottom: 4px">
        <el-tag :type="m.tag" size="small">{{ m.label }}</el-tag>
        <span class="mono muted" style="margin: 0 6px">{{ k }}</span>
        <span>{{ m.meaning }}</span>
      </div>
    </el-alert>

    <el-table :data="rows" v-loading="rowsLoading" size="small" border stripe max-height="360">
      <el-table-column label="审签时刻" width="150">
        <template #default="{ row }">{{ ts(row.countersigned_at) }}</template>
      </el-table-column>
      <el-table-column label="审签人" width="140">
        <template #default="{ row }">
          {{ txt(row.countersigner_name) }}<span class="muted">（id {{ num(row.countersigner_id) }}）</span>
        </template>
      </el-table-column>
      <el-table-column label="当时职称（原文快照）" width="150">
        <template #default="{ row }">
          <span v-if="row.countersigner_title">{{ row.countersigner_title }}</span>
          <span v-else class="muted">空（该账号当时就没填职称，不猜、不补）</span>
        </template>
      </el-table-column>
      <el-table-column label="是否仍有效" width="130">
        <template #default="{ row }">
          <el-tag v-if="row.stale === true" type="danger" size="small" effect="plain">已失效（正文后被改）</el-tag>
          <el-tag v-else-if="row.stale === false" type="success" size="small" effect="plain">有效</el-tag>
          <span v-else class="muted">—（后端未返 stale）</span>
        </template>
      </el-table-column>
      <el-table-column label="签的哪一版" min-width="200">
        <template #default="{ row }">
          <el-tag :type="versionSourceMeta(row.version_source).tag" size="small">
            {{ versionSourceMeta(row.version_source).label }}
          </el-tag>
          <span class="mono muted" style="margin-left: 6px">{{ txt(row.version_source) }}</span>
          <div v-if="row.version_source === 'VERSION_TABLE'">
            第 <b>{{ num(row.version_no) }}</b> 版（版本行 id {{ num(row.version_id) }}）
          </div>
          <div v-else class="muted">无版本号——{{ versionSourceMeta(row.version_source).meaning }}</div>
        </template>
      </el-table-column>
      <el-table-column label="正文摘要 / 字数" width="180">
        <template #default="{ row }">
          <el-tooltip :content="String(row.content_sha256 ?? '')" placement="top">
            <span class="mono">{{ shortSha(row.content_sha256) }}</span>
          </el-tooltip>
          <div class="muted">{{ num(row.content_len) }} 字</div>
        </template>
      </el-table-column>
      <el-table-column label="审签意见" min-width="180">
        <template #default="{ row }">
          <span v-if="row.opinion" class="raw">{{ row.opinion }}</span>
          <span v-else class="muted">未填（意见可空，签字动作本身已留痕）</span>
        </template>
      </el-table-column>
      <template #empty>
        <div style="padding: 12px">
          该病历<b>没有任何审签记录</b>。本版<b>零回填</b>：历史病历当时没采集审签动作，那就是没有。
        </div>
      </template>
    </el-table>

    <template v-if="loaded && !signBlockReason">
      <div class="section-title">审签意见（选填，最多 {{ OPINION_MAX }} 字；超长后端返 5724）</div>
      <el-input v-model="opinion" type="textarea" :rows="3" :maxlength="OPINION_MAX" show-word-limit
                placeholder="例如：已审阅，同意" />
      <div class="muted" style="margin-top: 6px">
        书写人取自该病历<b>已有审签记录里的书写人快照</b>（id {{ num(authorId) }}），与当前登录人（id {{ num(myId) }}）不同，故可签。
      </div>
    </template>

    <template v-if="signResult">
      <div class="section-title">本次审签结果</div>
      <el-descriptions :column="2" border size="small" class="caveat">
        <el-descriptions-item label="审签时刻">{{ ts(signResult.countersignedAt) }}</el-descriptions-item>
        <el-descriptions-item label="当时 gate">
          <el-tag :type="gateMeta(signResult.gate).tag" size="small">{{ gateMeta(signResult.gate).label }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="签的这一版（version_source）" :span="2">
          <el-tag :type="versionSourceMeta(signResult.versionSource).tag" size="small">
            {{ versionSourceMeta(signResult.versionSource).label }}
          </el-tag>
          <span class="mono muted" style="margin-left: 6px">{{ signResult.versionSource }}</span>
          <template v-if="signResult.versionSource === 'VERSION_TABLE'">
            　→ 第 <b>{{ num(signResult.versionNo) }}</b> 版（版本行 id {{ num(signResult.versionId) }}）
          </template>
          <div class="muted">{{ versionSourceMeta(signResult.versionSource).meaning }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="正文摘要 sha256" :span="2">
          <span class="mono">{{ signResult.contentSha256 }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert v-for="(w, i) in signResult.warnings ?? []" :key="`rw${i}`" type="warning" show-icon
                :closable="false" class="caveat" :title="w" />
    </template>

    <template #footer>
      <span class="muted" style="float: left">
        审签只增不改不删，<b>没有撤销</b>；否定前一次审签走「病历被改 → 旧审签失效 → 重新审签」。
      </span>
      <el-button size="small" @click="drawer = false">关闭</el-button>
      <el-button type="primary" size="small" :loading="saving"
                 :disabled="!loaded || !!signBlockReason || !!signResult" @click="submitSign">
        确认审签
      </el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * v53 车道 W2：出院前审签检查（GET /api/inpatient/countersign/check）。
 *
 * 【本页为什么在「缺项」上不给「审签」按钮的直路】
 * /check 的 findings 只有 { code, recordId, recordType, text }，**不带书写人**。
 * 没有书写人就无法在提交前判定「审签人是不是书写人」，只能等后端 5722 把人拒回来——
 * 那正是本版要消灭的体验。所以本页的审签走一条能证明的路：先读该病历的审签历史，
 * 历史里带 author_id（书写人快照）；取得到才允许在本页签，取不到就明说取不到、
 * 并把人指回带书写人列的工作台。缺 authorId 这件事已写进 cross_lane。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import client from '../../../api/client'
import { useAuthStore } from '../../../stores/auth'
import {
  OPINION_MAX, VERSION_SOURCE_META, findingMeta, gateMeta, num, recordTypeText, shortSha,
  ts, txt, versionSourceMeta, type Row,
} from './countersignCommon'

const BASE = '/inpatient/countersign'

interface Finding { code: string; recordId: number; recordType: string; text: string }

interface CheckBody {
  admissionId: number
  gate: string
  blocked: boolean
  blockCode: number
  blockMessage: string | null
  findings: Finding[]
  warnings: string[]
  requiredTypes: string[]
  caveat: string
}

interface SignResult {
  countersignedAt: string
  contentSha256: string
  versionId: number | null
  versionNo: number | null
  versionSource: string
  gate: string
  warnings: string[]
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const myId = computed<number | null>(() => auth.user?.id ?? null)
const roles = computed<string[]>(() => auth.user?.roles ?? [])
const canSign = computed(() => roles.value.includes('ADMIN') || roles.value.includes('DOCTOR_OUTP'))

const admissionIdText = ref('')
const body = ref<CheckBody | null>(null)
const loading = ref(false)

const findings = computed<Finding[]>(() => body.value?.findings ?? [])

async function check() {
  const id = Number(admissionIdText.value)
  if (!Number.isInteger(id) || id <= 0) {
    ElMessage.warning('请输入正整数住院 id（inp_admission.id）')
    return
  }
  loading.value = true
  try {
    body.value = (await client.get(`${BASE}/check`, { params: { admissionId: id } })).data.data as CheckBody
  } finally {
    loading.value = false
  }
}

/* ---------------- 某份病历：审签历史 + 审签 ---------------- */
const drawer = ref(false)
const recordId = ref<number | null>(null)
const rows = ref<Row[]>([])
const valid = ref(false)
const loaded = ref(false)
const rowsLoading = ref(false)
const opinion = ref('')
const saving = ref(false)
const signResult = ref<SignResult | null>(null)

/** 书写人只能从已有审签记录的 author_id 快照里取——/check 不带它，本页不猜 */
const authorId = computed<number | null>(() => {
  const r = rows.value.find((x) => x.author_id !== null && x.author_id !== undefined)
  return r ? Number(r.author_id) : null
})

/** 「这份病历我能不能在本页签」——提交前判定，逐条对齐后端拒绝理由 */
const signBlockReason = computed<string>(() => {
  if (!canSign.value) {
    return '当前账号没有审签权限：后端 POST /records/{recordId} 限 ADMIN 或 DOCTOR_OUTP，'
      + 'QUALITY（病案室）可以查谁签过，但不能代医师签字。'
  }
  if (myId.value === null) {
    return '当前登录用户 id 未知，无法在提交前判定「审签人与书写人是否为同一人」，后端会返 5721。'
  }
  if (authorId.value === null) {
    return '本页取不到这份病历的书写人：/check 的缺项不带书写人，而该病历还没有任何审签记录，'
      + '快照也就无从取得。因此无法在您点之前告诉您「您是不是本份病历的书写人」——'
      + '若您正是书写人，提交会被后端 5722 拒。请到「上级审签工作台」签，那里的待审签列表带书写人列，能提前判定。'
  }
  if (authorId.value === Number(myId.value)) {
    return '您就是本份病历的书写人（取自已有审签记录的书写人快照）：自己审自己写的病历不构成上级审签，后端返 5722。'
  }
  return ''
})

async function openRecord(id: number) {
  recordId.value = id
  rows.value = []
  valid.value = false
  loaded.value = false
  opinion.value = ''
  signResult.value = null
  drawer.value = true
  rowsLoading.value = true
  try {
    const d = (await client.get(`${BASE}/records/${id}`)).data.data
    rows.value = (d.countersigns ?? []) as Row[]
    valid.value = d.validForCurrentContent === true
    loaded.value = true
  } finally {
    rowsLoading.value = false
  }
}

async function submitSign() {
  if (recordId.value === null || signBlockReason.value) return
  saving.value = true
  try {
    signResult.value = (await client.post(`${BASE}/records/${recordId.value}`, {
      opinion: opinion.value.trim() || undefined,
    })).data.data as SignResult
    ElMessage.success('审签已记录（只增不改不删，无撤销）')
    await check()
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  const q = route.query.admissionId
  if (typeof q === 'string' && q) {
    admissionIdText.value = q
    void check()
  }
})
</script>

<style scoped>
.caveat {
  margin-bottom: 8px;
}
.section-title {
  font-weight: 600;
  margin: 16px 0 8px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.danger-text {
  color: var(--el-color-danger);
}
.raw {
  white-space: pre-wrap;
  word-break: break-word;
}
.mono {
  font-family: Consolas, Menlo, monospace;
}
</style>
