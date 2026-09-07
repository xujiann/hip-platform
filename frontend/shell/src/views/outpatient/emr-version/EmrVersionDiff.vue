<template>
  <div>
    <!-- compare-current 的 notice：右侧不是版本记录、不一致意味着改动发生在留痕接缝之外。
         这句话是后端专门写来给人看的，原样上屏，不改写不摘要。 -->
    <el-alert v-if="body.notice" type="warning" show-icon :closable="false" class="gap"
              title="本次对比的口径说明（后端原文）">
      <div class="pre-line">{{ body.notice }}</div>
    </el-alert>

    <el-descriptions :column="2" border size="small" class="gap">
      <el-descriptions-item :label="`左侧 ${sideName(body.from)}`">
        <div class="pre-line">{{ sideDesc(body.from) }}</div>
      </el-descriptions-item>
      <el-descriptions-item :label="`右侧 ${sideName(body.to)}`">
        <div class="pre-line">{{ sideDesc(body.to) }}</div>
      </el-descriptions-item>
    </el-descriptions>

    <el-descriptions :column="5" border size="small" class="gap" title="逐字段差异统计">
      <el-descriptions-item label="新增">{{ body.summary.added }}</el-descriptions-item>
      <el-descriptions-item label="删除">{{ body.summary.removed }}</el-descriptions-item>
      <el-descriptions-item label="修改">{{ body.summary.modified }}</el-descriptions-item>
      <el-descriptions-item label="未变化">{{ body.summary.unchanged }}</el-descriptions-item>
      <el-descriptions-item label="改动字段">
        <template v-if="body.summary.changedFields.length">
          <el-tag v-for="f in body.summary.changedFields" :key="f" size="small" type="warning"
                  class="chip">{{ fieldLabel(f) }}</el-tag>
        </template>
        <span v-else>无</span>
      </el-descriptions-item>
    </el-descriptions>

    <el-alert v-if="body.summary.identical" type="success" show-icon :closable="false" class="gap"
              title="两侧逐字段完全一致（后端 summary.identical = true）" />

    <div class="toolbar">
      <el-switch v-model="showUnchanged" size="small" active-text="同时显示未变化的字段" />
      <span class="hint">
        差异接口<b>刻意不重复返回未变化字段的正文</b>（只回长度）；下方未变化行的正文取自左侧版本快照。
      </span>
    </div>

    <div v-for="row in rows" :key="row.d.field" class="field" :class="`st-${row.d.status}`">
      <div class="field-head">
        <span class="fname">{{ row.d.label }}</span>
        <el-tag :type="TAG_TYPE[row.d.status]" size="small" class="chip">{{ STATUS_TEXT[row.d.status] }}</el-tag>
        <span class="meta">改前 {{ lenText(row.d.fromLen) }}　→　改后 {{ lenText(row.d.toLen) }}</span>
        <span v-if="row.d.firstDiffAt !== null" class="meta">
          首个差异位置：第 {{ row.d.firstDiffAt + 1 }} 字
        </span>
        <span class="meta code">{{ row.d.field }}</span>
      </div>

      <!-- 未变化：绝不渲染成两块空白（from/to 本来就是 null，画空白等于谎报「这一版把内容删了」） -->
      <div v-if="row.d.status === 'UNCHANGED'" class="unchanged">
        <span>两侧相同（{{ lenText(row.d.fromLen) }}）。差异接口未重复返回该字段正文。</span>
        <el-button link type="primary" size="small" @click="toggle(row.d.field)">
          {{ expanded.has(row.d.field) ? '收起正文' : '显示正文（取自左侧版本快照）' }}
        </el-button>
        <div v-if="expanded.has(row.d.field)" class="text plain">{{ snapshotText(row.d.field) }}</div>
      </div>

      <div v-else class="pair">
        <div class="cell">
          <div class="cell-head">{{ sideName(body.from) }}（改前）</div>
          <div v-if="row.left" class="text"
            ><span>{{ row.left.pre }}</span
            ><span v-if="row.left.mid" class="hl hl-from">{{ row.left.mid }}</span
            ><span v-else-if="row.right && row.right.mid" class="caret" title="改后在此处插入了内容">‸</span
            ><span>{{ row.left.post }}</span
          ></div>
          <div v-else class="empty-val">{{ row.leftEmpty }}</div>
        </div>
        <div class="cell">
          <div class="cell-head">{{ sideName(body.to) }}（改后）</div>
          <div v-if="row.right" class="text"
            ><span>{{ row.right.pre }}</span
            ><span v-if="row.right.mid" class="hl hl-to">{{ row.right.mid }}</span
            ><span v-else-if="row.left && row.left.mid" class="caret" title="此处删去了左侧标红的内容">‸</span
            ><span>{{ row.right.post }}</span
          ></div>
          <div v-else class="empty-val">{{ row.rightEmpty }}</div>
        </div>
        <div v-if="row.clipped" class="clip-note">
          后端返回的差异片段已被截断（超 2000 字），此处改为「从首个差异位置起整段标出」——
          标注范围大于真实差异范围，请以上方全文为准。
        </div>
      </div>
    </div>

    <el-empty v-if="rows.length === 0" :image-size="60"
              description="本次对比没有可显示的字段行（未变化字段已折叠，可用上方开关展开）" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CompareBody, DiffStatus, FieldDiff, VersionSnapshot } from './types'
import { fieldLabel, fmtTime } from './types'

const props = defineProps<{ body: CompareBody }>()

const STATUS_TEXT: Record<DiffStatus, string> = {
  ADDED: '新增（原本为空）',
  REMOVED: '删除（改后为空）',
  MODIFIED: '修改',
  UNCHANGED: '未变化',
}

const TAG_TYPE: Record<DiffStatus, 'success' | 'danger' | 'warning' | 'info'> = {
  ADDED: 'success',
  REMOVED: 'danger',
  MODIFIED: 'warning',
  UNCHANGED: 'info',
}

const showUnchanged = ref(false)
const expanded = ref<Set<string>>(new Set())

function toggle(field: string) {
  const next = new Set(expanded.value)
  if (next.has(field)) next.delete(field)
  else next.add(field)
  expanded.value = next
}

/** 高亮切片：公共前缀 + 差异片段 + 公共后缀 */
interface Seg {
  pre: string
  mid: string
  post: string
}

interface Row {
  d: FieldDiff
  left: Seg | null
  right: Seg | null
  /** left/right 为 null 时的说明文案：区分「null」与「空字符串」，不混为一谈 */
  leftEmpty: string
  rightEmpty: string
  /** 差异片段被后端截断，高亮范围是兜底的整段标注而非精确差异 */
  clipped: boolean
}

/**
 * 用后端给的 `firstDiffAt` + `fromChanged/toChanged` 复原高亮位置。
 *
 * **这不是前端自己做的文本 diff**：位置与片段都由后端算好，这里只做一次<b>可验证</b>的对齐——
 * 先按下标把片段切出来跟后端给的片段逐字比对，对得上才高亮那一段；对不上（后端把超 2000 字的
 * 片段截断过）就退回「从首差位置起整段标出」，并在界面上说明标注范围偏大。
 * 绝不在前端做相似度匹配或二次分词去「猜」差异边界。
 */
function segment(text: string, at: number | null, changed: string | null): { seg: Seg; exact: boolean } {
  if (at === null || changed === null) {
    return { seg: { pre: text, mid: '', post: '' }, exact: true }
  }
  const start = Math.max(0, Math.min(at, text.length))
  // changed 为空串是**纯插入/纯删除**：这一侧没有被改掉的字，高亮段为空、只标一个插入位置。
  // （曾把空串当成对不上、退回「从首差位置整段标出」，结果把没变的后缀也划成了删除线。）
  if (text.slice(start, start + changed.length) === changed) {
    return {
      seg: { pre: text.slice(0, start), mid: changed, post: text.slice(start + changed.length) },
      exact: true,
    }
  }
  return { seg: { pre: text.slice(0, start), mid: text.slice(start), post: '' }, exact: false }
}

/** 空值文案：null（该字段未填写）与 ''（空字符串）是两回事，分开说 */
function emptyText(v: string | null): string {
  return v === null ? '（未填写 / null）' : '（空字符串）'
}

const rows = computed<Row[]>(() =>
  props.body.diffs
    .filter((d) => showUnchanged.value || d.status !== 'UNCHANGED')
    .map((d) => {
      if (d.status === 'UNCHANGED') {
        return { d, left: null, right: null, leftEmpty: '', rightEmpty: '', clipped: false }
      }
      const l = d.from ? segment(d.from, d.firstDiffAt, d.fromChanged) : null
      const r = d.to ? segment(d.to, d.firstDiffAt, d.toChanged) : null
      return {
        d,
        left: l ? l.seg : null,
        right: r ? r.seg : null,
        leftEmpty: emptyText(d.from),
        rightEmpty: emptyText(d.to),
        clipped: (l !== null && !l.exact) || (r !== null && !r.exact),
      }
    }),
)

/** 未变化字段的正文：取左侧快照里的 fields[字段].value（两侧相等，取哪侧都一样） */
function snapshotText(field: string): string {
  const fv = props.body.from.fields[field]
  if (!fv) return '（左侧快照里没有这个字段，无法显示正文）'
  return fv.value === null ? '（未填写 / null）' : fv.value === '' ? '（空字符串）' : fv.value
}

function sideName(s: VersionSnapshot): string {
  return s.versionNo === null ? '当前正文' : `第 ${s.versionNo} 版`
}

function sideDesc(s: VersionSnapshot): string {
  if (s.versionNo === null) {
    return `label=${s.label}　${s.contentLen} 字\n`
      + '数据库里的当前正文，不是一个版本记录（没有保存人、没有保存时间，本次对比也不会生成版本行）'
  }
  const who = s.savedBy === null
    ? '保存人：未记录（本次保存没有登录上下文，后端如实回 null，不回填猜测）'
    : `保存人：${s.savedByName ?? `用户 #${s.savedBy}（姓名未查到）`}`
  return `${who}\n保存时间：${fmtTime(s.savedAt)}　业务日：${s.savedOn ?? '—'}\n`
    + `来源：${s.source}　${s.contentLen} 字　摘要：${s.contentHash}`
}

function lenText(v: number | null): string {
  return v === null ? '未填写' : `${v} 字`
}
</script>

<style scoped>
.gap {
  margin-bottom: 8px;
}
.chip {
  margin-right: 4px;
}
.pre-line {
  white-space: pre-line;
  line-height: 1.6;
}
.toolbar {
  margin: 12px 0 4px;
}
.toolbar .hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.field {
  margin-top: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-left-width: 4px;
  border-radius: 4px;
  padding: 8px 10px;
}
.field.st-MODIFIED {
  border-left-color: var(--el-color-warning);
}
.field.st-ADDED {
  border-left-color: var(--el-color-success);
}
.field.st-REMOVED {
  border-left-color: var(--el-color-danger);
}
.field.st-UNCHANGED {
  border-left-color: var(--el-border-color);
  background: var(--el-fill-color-lighter);
}
.field-head {
  margin-bottom: 6px;
  line-height: 22px;
}
.fname {
  font-weight: 600;
  margin-right: 8px;
}
.meta {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.meta.code {
  font-family: Consolas, Monaco, monospace;
}
.pair {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.cell {
  min-width: 0;
}
.cell-head {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
.text {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.7;
  max-height: 320px;
  overflow: auto;
  padding: 6px 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 3px;
  background: var(--el-bg-color-page);
}
.text.plain {
  margin-top: 6px;
}
.hl {
  border-radius: 2px;
  padding: 0 1px;
}
.hl-from {
  background: var(--el-color-danger-light-7);
  text-decoration: line-through;
}
.hl-to {
  background: var(--el-color-success-light-7);
}
/* 纯插入/纯删除时，这一侧一个字都没变，只标出位置——不能拿相邻的没变的字去凑高亮 */
.caret {
  color: var(--el-color-primary);
  font-weight: 700;
}
.empty-val {
  padding: 6px 8px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 3px;
}
.unchanged {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.clip-note {
  grid-column: 1 / -1;
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-color-warning);
}
</style>
