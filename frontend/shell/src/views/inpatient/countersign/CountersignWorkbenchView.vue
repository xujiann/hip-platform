<template>
  <el-card>
    <template #header>
      <span style="font-weight: 600">上级医师审签工作台</span>
      <el-tag type="info" size="small" style="margin-left: 10px">《病历书写基本规范》上级医师审阅并签名</el-tag>
      <el-tag :type="gateMeta(config?.gate).tag" size="small" effect="plain" style="margin-left: 8px">
        gate {{ gateMeta(config?.gate).label }}
      </el-tag>
      <el-button type="primary" size="small" :loading="loading" style="margin-left: 12px" @click="reload">
        刷新待审签
      </el-button>
      <el-input v-model="lookupRecordId" size="small" style="width: 210px; margin-left: 12px"
                placeholder="按病历 id 直接查审签历史" clearable @keyup.enter="openHistoryById">
        <template #append>
          <el-button @click="openHistoryById">查历史</el-button>
        </template>
      </el-input>
      <el-button link type="primary" size="small" style="margin-left: 8px"
                 @click="router.push('/inpatient/countersign/check')">
        出院前审签检查 →
      </el-button>
    </template>

    <!-- ================================================================
         一、上级资格口径（后端 GET /check 的 caveat，原文照登）
         不写出来，读的人会以为「上级审签」真的校验了层级——而它在证据上只成立到
         「另一位不同于书写人的用户签过」。这是本页最要紧的一条，故放在最上面。
         ================================================================ -->
    <el-alert v-if="caveat" type="warning" show-icon :closable="false" class="caveat"
              title="先看这一条：本平台不判定「谁有资格当上级」（后端随 GET /check 下发的 caveat，一字未改）">
      <div class="raw">{{ caveat }}</div>
      <div class="muted" style="margin-top: 6px">
        换句话说：本页每一条「已审签」都只证明<b>有另一位不同于书写人的账号签过字</b>，
        <b>不证明签字的人是上级</b>。上下级关系由院方在账号授权与职称维护上保证。
      </div>
    </el-alert>
    <el-alert v-else type="info" show-icon :closable="false" class="caveat"
              title="上级资格口径说明尚未取到（本页不写第二份，只转发后端原文）">
      <div>
        该说明由后端随 <code>GET /api/inpatient/countersign/check</code> 的 <code>caveat</code> 字段下发，
        需要一个住院 id 才能取。当前待审签列表为空（或尚未加载），故没有可用的住院 id。
        请到「出院前审签检查」页输入住院 id 查看，或先刷新待审签列表。
      </div>
    </el-alert>

    <!-- ================================================================
         二、当前生效配置（全部取自 GET /config，前端不写死默认值）
         ================================================================ -->
    <el-descriptions :column="4" border size="small" class="caveat" title="当前生效的审签配置">
      <el-descriptions-item label="gate 档位">
        <el-tag :type="gateMeta(config?.gate).tag" size="small">{{ gateMeta(config?.gate).label }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="gate 配置键">{{ txt(config?.gateKey) }}</el-descriptions-item>
      <el-descriptions-item label="审签时限">
        {{ num(config?.dueHours) }} 小时（自病历创建时刻起算）
      </el-descriptions-item>
      <el-descriptions-item label="时限的作用">仅用于下表的超期标记与排序，<b>不拦截任何写入</b></el-descriptions-item>
      <el-descriptions-item label="纳入审签的病历类型" :span="4">
        <el-tag v-for="t in config?.requiredTypes ?? []" :key="t" size="small" style="margin-right: 6px">
          {{ recordTypeText(t) }}
        </el-tag>
        <span v-if="!(config?.requiredTypes ?? []).length" class="muted">—</span>
      </el-descriptions-item>
    </el-descriptions>
    <!-- notes 原样上屏：这是后端明说的两条纪律，改写就失真 -->
    <el-alert v-for="(n, i) in config?.notes ?? []" :key="i" type="info" :closable="false" class="caveat" :title="n" />

    <!-- ================================================================
         三、筛选
         ================================================================ -->
    <div class="filters">
      <el-select v-model="deptId" size="small" clearable filterable placeholder="科室（全部）"
                 style="width: 180px" @change="reload">
        <el-option v-for="d in depts" :key="d.id" :value="d.id" :label="d.name" />
      </el-select>
      <el-select v-model="wardId" size="small" clearable filterable placeholder="病区（全部）"
                 style="width: 180px" @change="reload">
        <el-option v-for="d in depts" :key="d.id" :value="d.id" :label="d.name" />
      </el-select>
      <el-input-number v-model="doctorId" size="small" :min="1" :controls="false" placeholder="书写医师 id"
                       style="width: 130px" @change="reload" />
      <el-button v-if="myId" size="small" @click="filterMine">只看我书写的</el-button>
      <el-switch v-model="overdueOnly" size="small" active-text="只看超期" @change="reload" />
      <el-switch v-model="includeArchived" size="small" active-text="含已归档病案" @change="reload" />
      <el-input-number v-model="limit" size="small" :min="1" :max="LIMIT_MAX" :step="20"
                       style="width: 120px" @change="reload" />
      <span class="muted">条 / 页（上限 {{ LIMIT_MAX }}）</span>
      <el-button size="small" :disabled="offset === 0 || loading" @click="prevPage">上一页</el-button>
      <span class="muted">offset {{ offset }}</span>
      <el-button size="small" :disabled="items.length < limit || loading" @click="nextPage">下一页</el-button>
      <el-input v-model="keyword" size="small" clearable placeholder="过滤本页（住院号/患者/标题/书写人）"
                style="width: 220px" />
    </div>

    <!-- 分页与总数口径：不显示编出来的总数 -->
    <el-alert type="info" :closable="false" class="caveat" :title="PAGING_NOTE" />
    <div class="muted" style="margin-bottom: 8px">
      本页取到 <b>{{ items.length }}</b> 条<span v-if="keyword">，按关键词过滤后显示 <b>{{ shown.length }}</b> 条（<b>仅过滤本页已取到的这 {{ items.length }} 条，不是全院搜索</b>）</span>；
      判据 = 病历类型在上表白名单内 <b>且当前正文没有一条有效审签</b>（签过但正文之后被改，照样算待审签）。
      <template v-if="items.length >= limit">
        <b class="warn-text">本页已取满 {{ limit }} 条，后面很可能还有——请翻页或调大每页条数。</b>
      </template>
    </div>

    <el-table :data="shown" v-loading="loading" size="small" border stripe max-height="520">
      <el-table-column prop="admission_no" label="住院号" width="120" />
      <el-table-column prop="patient_name" label="患者" width="90" />
      <el-table-column label="科室 / 病区" width="150">
        <template #default="{ row }">{{ txt(row.dept_name) }} / {{ txt(row.ward_name) }}</template>
      </el-table-column>
      <el-table-column label="住院状态" width="80">
        <template #default="{ row }">{{ admissionStatusText(row.admission_status) }}</template>
      </el-table-column>
      <el-table-column label="病历类型" width="130">
        <template #default="{ row }">{{ recordTypeText(row.record_type) }}</template>
      </el-table-column>
      <el-table-column prop="title" label="病历标题" min-width="180" show-overflow-tooltip />
      <el-table-column label="书写人" width="130">
        <template #default="{ row }">
          <span v-if="row.author_id === null || row.author_id === undefined" class="danger-text">
            书写人未知
          </span>
          <span v-else>
            {{ txt(row.author_name) }}<span class="muted">（id {{ num(row.author_id) }}）</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="书写时刻" width="130">
        <template #default="{ row }">{{ txt(row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="190">
        <template #default="{ row }">
          <el-tag v-if="row.overdue" type="danger" size="small" effect="plain">
            超期未审签（&gt; {{ num(config?.dueHours) }} 小时）
          </el-tag>
          <el-tag v-if="Number(row.countersign_count ?? 0) > 0" type="warning" size="small"
                  effect="plain" style="margin-left: 4px">
            已签 {{ num(row.countersign_count) }} 次但正文之后被改，需重签
          </el-tag>
          <span v-if="!row.overdue && Number(row.countersign_count ?? 0) === 0" class="muted">从未审签</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <!-- 提前提示：不等提交被 5722 拒。disabled 的按钮不触发鼠标事件，故用 span 包住 -->
          <el-tooltip :content="blockReason(row) || '打开审签抽屉'" placement="top">
            <span>
              <el-button link type="primary" size="small" :disabled="!!blockReason(row)"
                         @click="openSign(row)">审签</el-button>
            </span>
          </el-tooltip>
          <el-button link type="primary" size="small" @click="openHistory(Number(row.record_id))">审签历史</el-button>
          <el-button link type="primary" size="small" @click="gotoCheck(row)">出院前检查</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div style="padding: 12px">
          当前筛选条件下没有待审签病历。<b>这不等于「全院都签完了」</b>——
          默认排除已归档病案，且只统计上表白名单里的病历类型。
        </div>
      </template>
    </el-table>
  </el-card>

  <!-- ==================================================================
       审签抽屉
       ================================================================== -->
  <el-drawer v-model="signDrawer" size="60%"
             :title="`上级审签 — ${txt(signRow?.title)}（病历 id ${num(signRow?.record_id)}）`">
    <template v-if="signRow">
      <!-- 提交前就说清楚为什么不能签，而不是等后端 5722 -->
      <el-alert v-if="blockReason(signRow)" type="error" show-icon :closable="false" class="caveat"
                title="本份病历您不能审签（提交前判定，不是等后端拒）">
        <div>{{ blockReason(signRow) }}</div>
      </el-alert>

      <el-alert v-if="caveat" type="warning" show-icon :closable="false" class="caveat"
                title="签字之前请再看一遍：本平台不判定「谁有资格当上级」（后端 caveat 原文）">
        <div class="raw">{{ caveat }}</div>
      </el-alert>

      <el-descriptions :column="2" border size="small" class="caveat" title="被审签的病历">
        <el-descriptions-item label="患者">
          {{ txt(signRow.patient_name) }}（住院号 {{ txt(signRow.admission_no) }}）
        </el-descriptions-item>
        <el-descriptions-item label="科室 / 病区">
          {{ txt(signRow.dept_name) }} / {{ txt(signRow.ward_name) }}
        </el-descriptions-item>
        <el-descriptions-item label="病历类型">{{ recordTypeText(signRow.record_type) }}</el-descriptions-item>
        <el-descriptions-item label="书写时刻">{{ ts(signRow.created_at) }}</el-descriptions-item>
        <el-descriptions-item label="书写人">
          {{ txt(signRow.author_name) }}<span class="muted">（id {{ num(signRow.author_id) }}）</span>
        </el-descriptions-item>
        <el-descriptions-item label="审签人（当前登录）">
          {{ txt(auth.user?.realName) }}<span class="muted">（id {{ num(myId) }}）</span>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert type="info" :closable="false" class="caveat"
                title="本页不显示病历正文：审签抽屉里的正文快照与库里当前正文之间存在时间差，照着一份可能已过期的正文签字比不显示更危险。请在住院医生站读完正文后再回本页签字——后端会把签字当刻的正文摘要落库，「签完又改了」事后可当场验算。" />

      <!-- 本次住院的审签缺项（GET /check，纯只读预检，不拦任何东西） -->
      <div class="section-title">本次住院的审签缺项（GET /check，纯只读预检）</div>
      <el-alert v-if="signCheck && signCheck.blocked" type="error" show-icon :closable="false" class="caveat"
                :title="`gate=block：此刻若走出院/归档挡点会被挡（业务码 ${num(signCheck.blockCode)}）`">
        <div class="raw">{{ signCheck.blockMessage }}</div>
      </el-alert>
      <el-alert v-for="(w, i) in signCheck?.warnings ?? []" :key="`sw${i}`" type="warning" show-icon
                :closable="false" class="caveat" :title="w" />
      <el-table v-if="(signCheck?.findings ?? []).length" :data="signCheck?.findings ?? []" size="small" border>
        <el-table-column label="缺项" width="230">
          <template #default="{ row }">
            <el-tag :type="findingMeta(row.code).tag" size="small">{{ findingMeta(row.code).label }}</el-tag>
            <span class="muted" style="margin-left: 4px">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column label="病历" width="200">
          <template #default="{ row }">
            {{ recordTypeText(row.recordType) }}<span class="muted">（id {{ num(row.recordId) }}）</span>
          </template>
        </el-table-column>
        <el-table-column prop="text" label="说明" min-width="240" show-overflow-tooltip />
      </el-table>
      <el-alert v-else-if="signCheck" type="success" :closable="false" class="caveat"
                title="本次住院在「纳入审签的病历类型」范围内没有审签缺项。" />

      <!-- 审签意见 -->
      <div class="section-title">审签意见（选填，最多 {{ OPINION_MAX }} 字；超长后端返 5724）</div>
      <el-input v-model="opinion" type="textarea" :rows="3" :maxlength="OPINION_MAX" show-word-limit
                placeholder="例如：已审阅，同意；或：主诉与现病史需补充发病诱因，已当面告知书写医师" />
      <div class="muted" style="margin-top: 6px">
        审签是<b>只增不改不删</b>的法定签字动作，<b>没有「撤销审签」</b>。
        需要否定前一次审签时，路径是「病历被改 → 旧审签自动失效 → 重新审签」。
      </div>

      <!-- 审签结果 -->
      <template v-if="signResult">
        <div class="section-title">本次审签结果</div>
        <el-descriptions :column="2" border size="small" class="caveat">
          <el-descriptions-item label="审签时刻">{{ ts(signResult.countersignedAt) }}</el-descriptions-item>
          <el-descriptions-item label="审签人 id">{{ num(signResult.countersignerId) }}</el-descriptions-item>
          <el-descriptions-item label="书写人 id">{{ num(signResult.authorId) }}</el-descriptions-item>
          <el-descriptions-item label="当时 gate">
            <el-tag :type="gateMeta(signResult.gate).tag" size="small">{{ gateMeta(signResult.gate).label }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="签的这一版（version_source）" :span="2">
            <el-tag :type="versionSourceMeta(signResult.versionSource).tag" size="small">
              {{ versionSourceMeta(signResult.versionSource).label }}
            </el-tag>
            <span class="muted" style="margin-left: 6px">{{ signResult.versionSource }}</span>
            <template v-if="signResult.versionSource === 'VERSION_TABLE'">
              　→ 第 <b>{{ num(signResult.versionNo) }}</b> 版（版本行 id {{ num(signResult.versionId) }}）
            </template>
            <div class="muted">{{ versionSourceMeta(signResult.versionSource).meaning }}</div>
          </el-descriptions-item>
          <el-descriptions-item label="正文摘要 sha256" :span="2">
            <span class="mono">{{ signResult.contentSha256 }}</span>
            <div class="muted">
              这一列是审签的举证核心：重算当前正文的 sha256 与它比对，即可判定「签完又改了」。
            </div>
          </el-descriptions-item>
        </el-descriptions>
        <!-- warnings 原样上屏：不写出来，签字的人不知道自己这一签绑到了什么粒度 -->
        <el-alert v-for="(w, i) in signResult.warnings ?? []" :key="`rw${i}`" type="warning" show-icon
                  :closable="false" class="caveat" :title="w" />
      </template>
    </template>

    <template #footer>
      <span class="muted" style="float: left">
        审签人取当前登录人，<b>后端三道拦「审签人 = 书写人」</b>：应用层 5722 + 数据库 CHECK + 跨表触发器。
      </span>
      <el-button size="small" @click="signDrawer = false">关闭</el-button>
      <el-button type="primary" size="small" :loading="saving"
                 :disabled="!signRow || !!blockReason(signRow) || !!signResult" @click="submitSign">
        确认审签
      </el-button>
    </template>
  </el-drawer>

  <!-- ==================================================================
       审签历史抽屉
       ================================================================== -->
  <el-drawer v-model="historyDrawer" size="72%" :title="`审签历史 — 病历 id ${num(historyRecordId)}`">
    <el-alert v-if="historyLoaded" :type="historyValid ? 'success' : 'warning'" show-icon :closable="false"
              class="caveat"
              :title="historyValid
                ? '当前正文已有一条有效审签（validForCurrentContent = true）'
                : '当前正文没有任何有效审签（validForCurrentContent = false）——待审签或待重新审签'">
      <div v-if="!historyValid && historyRows.length">
        下表里有审签记录，但<b>没有一条对应现在这一版正文</b>：签完之后正文被改过，原审签已失效。
      </div>
    </el-alert>

    <!-- 三态图例：DIGEST_ONLY 不是空白，是一个有原因的确定状态 -->
    <el-alert type="info" show-icon :closable="false" class="caveat"
              title="「签的哪一版」三态图例（version_source）">
      <div v-for="(m, k) in VERSION_SOURCE_META" :key="k" style="margin-bottom: 4px">
        <el-tag :type="m.tag" size="small">{{ m.label }}</el-tag>
        <span class="mono muted" style="margin: 0 6px">{{ k }}</span>
        <span>{{ m.meaning }}</span>
      </div>
    </el-alert>

    <el-table :data="historyRows" v-loading="historyLoading" size="small" border stripe
              max-height="calc(100vh - 340px)">
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
      <el-table-column label="书写人" width="140">
        <template #default="{ row }">
          {{ txt(row.author_name) }}<span class="muted">（id {{ num(row.author_id) }}）</span>
        </template>
      </el-table-column>
      <el-table-column label="是否仍有效" width="130">
        <template #default="{ row }">
          <el-tag v-if="row.stale === true" type="danger" size="small" effect="plain">已失效（正文后被改）</el-tag>
          <el-tag v-else-if="row.stale === false" type="success" size="small" effect="plain">有效</el-tag>
          <span v-else class="muted">—（后端未返 stale）</span>
        </template>
      </el-table-column>
      <el-table-column label="签的哪一版" min-width="210">
        <template #default="{ row }">
          <el-tag :type="versionSourceMeta(row.version_source).tag" size="small">
            {{ versionSourceMeta(row.version_source).label }}
          </el-tag>
          <span class="mono muted" style="margin-left: 6px">{{ txt(row.version_source) }}</span>
          <template v-if="row.version_source === 'VERSION_TABLE'">
            <div>第 <b>{{ num(row.version_no) }}</b> 版（版本行 id {{ num(row.version_id) }}）</div>
          </template>
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
      <el-table-column label="审签意见" min-width="200">
        <template #default="{ row }">
          <span v-if="row.opinion" class="raw">{{ row.opinion }}</span>
          <span v-else class="muted">未填（意见可空，签字动作本身已留痕）</span>
        </template>
      </el-table-column>
      <template #empty>
        <div style="padding: 12px">
          该病历<b>没有任何审签记录</b>。本版<b>零回填</b>：历史病历当时没有采集审签动作，
          那就是没有——不从查房记录的「上级修正意见」里倒推出一条从未发生的签字。
        </div>
      </template>
    </el-table>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * v53 车道 W2：上级审签工作台（待审签工作列表 + 审签操作 + 审签历史）。
 * 对接 CountersignController（/api/inpatient/countersign，类级 ADMIN/DOCTOR_OUTP/QUALITY；
 * 写端点 POST /records/{recordId} 收窄到 ADMIN/DOCTOR_OUTP——病案室能看不能代签）。
 * 字段大小写口径见 ./countersignCommon.ts 顶部的实读记录。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import client from '../../../api/client'
import { useAuthStore } from '../../../stores/auth'
import {
  LIMIT_MAX, OPINION_MAX, PAGING_NOTE, VERSION_SOURCE_META, admissionStatusText, findingMeta,
  gateMeta, num, recordTypeText, shortSha, ts, txt, versionSourceMeta, type Row,
} from './countersignCommon'

const BASE = '/inpatient/countersign'

interface ConfigBody {
  gate: string
  gateKey: string
  requiredTypes: string[]
  dueHours: number
  notes: string[]
}

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
  recordId: number
  admissionId: number | null
  recordType: string
  authorId: number
  countersignerId: number
  countersignedAt: string
  contentSha256: string
  versionId: number | null
  versionNo: number | null
  versionSource: string
  gate: string
  warnings: string[]
}

const router = useRouter()
const auth = useAuthStore()
const myId = computed<number | null>(() => auth.user?.id ?? null)
const roles = computed<string[]>(() => auth.user?.roles ?? [])
/** 写端点 @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")：QUALITY 可查不可签 */
const canSign = computed(() => roles.value.includes('ADMIN') || roles.value.includes('DOCTOR_OUTP'))

const config = ref<ConfigBody | null>(null)
const depts = ref<{ id: number; name: string; type: string }[]>([])
const items = ref<Row[]>([])
const loading = ref(false)

/** 后端 caveat 原文缓存：任何一次 /check 返回后都会回填，页面顶部与审签抽屉共用同一份 */
const caveat = ref('')

const deptId = ref<number | undefined>(undefined)
const wardId = ref<number | undefined>(undefined)
const doctorId = ref<number | undefined>(undefined)
const overdueOnly = ref(false)
const includeArchived = ref(false)
const limit = ref(50)
const offset = ref(0)
const keyword = ref('')
const lookupRecordId = ref('')

/** 关键词过滤**只作用于本页已取到的这一批**，不是全院搜索——措辞上必须说清楚 */
const shown = computed<Row[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return items.value
  return items.value.filter((r) => [r.admission_no, r.patient_name, r.title, r.author_name]
    .some((v) => String(v ?? '').toLowerCase().includes(k)))
})

/**
 * 「这一行我能不能签」——**提交前**判定，不等后端 5722 把人拒回来。
 * 三条与后端逐条对齐：角色（写端点 ADMIN/DOCTOR_OUTP）、书写人未知、审签人=书写人。
 */
function blockReason(row: Row | null): string {
  if (!row) return ''
  if (!canSign.value) {
    return '当前账号没有审签权限：后端 POST /records/{recordId} 限 ADMIN 或 DOCTOR_OUTP，'
      + 'QUALITY（病案室）可以看待审签清单、可以查谁签过，但不能代医师签字。'
  }
  if (myId.value === null) {
    return '当前登录用户 id 未知，无法在提交前判定「审签人与书写人是否为同一人」，后端会返 5721。'
  }
  if (row.author_id === null || row.author_id === undefined) {
    return '该病历的书写人未知（历史病历 doctor_id 为空）：无从核验审签人与书写人是否为同一人，'
      + '后端返 5722。本版不回填、不猜——无从核验就不能盖章放行。'
  }
  if (Number(row.author_id) === Number(myId.value)) {
    return '您就是本份病历的书写人：自己审自己写的病历不构成上级审签，后端返 5722（数据库 CHECK 与触发器另有两道）。'
  }
  return ''
}

async function loadConfig() {
  config.value = (await client.get(`${BASE}/config`)).data.data as ConfigBody
}

/**
 * 筛选值归一：清空 el-select / el-input-number 时拿到的可能是 undefined、null 或空串。
 * 空串一旦拼进 query（`?deptId=`），Spring 绑 Long 会 400——那是个只在「清空筛选」时才出现、
 * 又极难从「网络请求失败」的红字里看出来的坑。这里一律折成 undefined，axios 会整条省略。
 */
function q(v: unknown): number | undefined {
  if (v === null || v === undefined || v === '') return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

async function loadPending() {
  loading.value = true
  try {
    const d = (await client.get(`${BASE}/pending`, {
      params: {
        deptId: q(deptId.value),
        wardId: q(wardId.value),
        doctorId: q(doctorId.value),
        overdueOnly: overdueOnly.value,
        includeArchived: includeArchived.value,
        limit: limit.value,
        offset: offset.value,
      },
    })).data.data
    items.value = (d.items ?? []) as Row[]
    // 后端把生效的 limit/offset 回带（可能与请求值不同），以回带值为准
    if (typeof d.limit === 'number') limit.value = d.limit
    if (typeof d.offset === 'number') offset.value = d.offset
  } finally {
    loading.value = false
  }
}

/**
 * caveat 只随 GET /check 下发，而 /check 必须带一个住院 id。
 * 这里用本页第一条待审签记录的 admission_id 去取——**只取 caveat，不用它的裁决**：
 * 那是别人那次住院的判定，拿来当本页的结论就是张冠李戴。
 */
async function hydrateCaveat() {
  if (caveat.value) return
  const first = items.value[0]
  if (!first || first.admission_id === null || first.admission_id === undefined) return
  try {
    const d = (await client.get(`${BASE}/check`, {
      params: { admissionId: Number(first.admission_id) },
    })).data.data as CheckBody
    caveat.value = String(d.caveat ?? '')
  } catch {
    // 口径取不到不影响待审签列表本身；页面会显示「尚未取到」的说明分支
  }
}

async function reload() {
  offset.value = 0
  await loadPending()
  await hydrateCaveat()
}

async function prevPage() {
  offset.value = Math.max(0, offset.value - limit.value)
  await loadPending()
}

async function nextPage() {
  offset.value += limit.value
  await loadPending()
}

function filterMine() {
  doctorId.value = myId.value ?? undefined
  void reload()
}

function gotoCheck(row: Row) {
  void router.push({ path: '/inpatient/countersign/check', query: { admissionId: String(row.admission_id) } })
}

/* ---------------- 审签 ---------------- */
const signDrawer = ref(false)
const signRow = ref<Row | null>(null)
const signCheck = ref<CheckBody | null>(null)
const signResult = ref<SignResult | null>(null)
const opinion = ref('')
const saving = ref(false)

async function openSign(row: Row) {
  signRow.value = row
  signCheck.value = null
  signResult.value = null
  opinion.value = ''
  signDrawer.value = true
  if (row.admission_id === null || row.admission_id === undefined) return
  try {
    const d = (await client.get(`${BASE}/check`, {
      params: { admissionId: Number(row.admission_id) },
    })).data.data as CheckBody
    signCheck.value = d
    caveat.value = String(d.caveat ?? caveat.value)
  } catch {
    // 预检取不到不挡审签本身——审签的三道硬校验在后端
  }
}

async function submitSign() {
  const row = signRow.value
  if (!row) return
  if (blockReason(row)) {
    ElMessage.error(blockReason(row))
    return
  }
  saving.value = true
  try {
    const d = (await client.post(`${BASE}/records/${Number(row.record_id)}`, {
      opinion: opinion.value.trim() || undefined,
    })).data.data as SignResult
    signResult.value = d
    ElMessage.success('审签已记录（只增不改不删，无撤销）')
    await loadPending()
  } finally {
    saving.value = false
  }
}

/* ---------------- 审签历史 ---------------- */
const historyDrawer = ref(false)
const historyRecordId = ref<number | null>(null)
const historyRows = ref<Row[]>([])
const historyValid = ref(false)
const historyLoaded = ref(false)
const historyLoading = ref(false)

async function openHistory(recordId: number) {
  historyRecordId.value = recordId
  historyRows.value = []
  historyValid.value = false
  historyLoaded.value = false
  historyDrawer.value = true
  historyLoading.value = true
  try {
    const d = (await client.get(`${BASE}/records/${recordId}`)).data.data
    historyRows.value = (d.countersigns ?? []) as Row[]
    historyValid.value = d.validForCurrentContent === true
    historyLoaded.value = true
  } finally {
    historyLoading.value = false
  }
}

function openHistoryById() {
  const id = Number(lookupRecordId.value)
  if (!Number.isInteger(id) || id <= 0) {
    ElMessage.warning('请输入正整数病历 id（inp_medical_record.id）')
    return
  }
  void openHistory(id)
}

onMounted(async () => {
  await loadConfig()
  try {
    depts.value = (await client.get('/system/depts')).data.data
  } catch {
    // 科室下拉取不到时退化为不筛选，不挡主功能
  }
  await loadPending()
  await hydrateCaveat()
})
</script>

<style scoped>
.caveat {
  margin-bottom: 8px;
}
.filters {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin: 12px 0 8px;
}
.section-title {
  font-weight: 600;
  margin: 16px 0 8px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.warn-text {
  color: var(--el-color-warning);
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
