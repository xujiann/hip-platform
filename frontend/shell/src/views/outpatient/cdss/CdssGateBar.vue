<template>
  <el-alert type="warning" show-icon :closable="false" class="gate-bar"
            title="CDSS 五个 gate 的当前档位（这一排决定现在到底拦不拦，请先看它再看下面的规则）">
    <div class="gate-grid">
      <div v-for="g in gates" :key="g.key" class="gate-cell">
        <span class="gate-key">{{ g.label }}</span>
        <code class="gate-cfg">{{ g.key }}</code>
        <template v-if="g.state === 'ok'">
          <el-tag :type="gateTagType(g.value)" size="small" effect="dark">{{ g.value }}</el-tag>
          <span class="gate-mean">{{ gateMeaning(g.value) }}</span>
        </template>
        <!-- 读不到就说读不到：把读取失败画成 off 或 warn，等于替药剂科编一个档位 -->
        <el-tag v-else-if="g.state === 'loading'" type="info" size="small">读取中…</el-tag>
        <template v-else>
          <el-tag type="danger" size="small" effect="plain">读取失败</el-tag>
          <span class="gate-mean err">{{ g.error }}——本页无法显示该档位，请勿据此判断系统是否在拦截</span>
        </template>
      </div>
    </div>
    <div class="gate-foot">
      档位改动走系统配置（sys_config），不在本页维护。坏配置一律回落 <b>warn</b>（不回落 off）：
      宁可多提示，也不让一个笔误把校验静默关掉。<b>warn 档只有在提示真的显示给医生时才有意义。</b>
      <span v-if="lookbackDays !== null">　｜　重复用药回溯窗口 {{ lookbackDays }} 天（cdss.duplicate.lookback.days）</span>
    </div>
  </el-alert>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import client from '../../../api/client'
import { gateMeaning, gateTagType } from './cdssCommon'

interface GateCell {
  key: string
  label: string
  state: 'loading' | 'ok' | 'error'
  value: string | null
  error: string
}

/**
 * 全仓实测只有 **5 个** cdss.gate.* 键（V152/V153/V154/V155 各自 seed）：
 * allergy / duplicate / population / route / solvent。药房盘点那条是 pharm.gate.stocktake.batch，
 * 刻意不叫 cdss.gate.*，故不在此列——凑数把它拉进来只会让人以为 CDSS 多了一道防线。
 */
const gates = ref<GateCell[]>([
  { key: 'cdss.gate.allergy', label: '过敏审查', state: 'loading', value: null, error: '' },
  { key: 'cdss.gate.duplicate', label: '重复用药', state: 'loading', value: null, error: '' },
  { key: 'cdss.gate.population', label: '特殊人群', state: 'loading', value: null, error: '' },
  { key: 'cdss.gate.route', label: '给药途径', state: 'loading', value: null, error: '' },
  { key: 'cdss.gate.solvent', label: '溶媒配伍', state: 'loading', value: null, error: '' },
])
const lookbackDays = ref<number | null>(null)

function set(key: string, value: string | null, error?: string) {
  const cell = gates.value.find((g) => g.key === key)
  if (!cell) return
  if (error) {
    cell.state = 'error'
    cell.error = error
  } else {
    cell.state = 'ok'
    cell.value = value
  }
}

function reason(e: unknown): string {
  const err = e as { response?: { status?: number }; message?: string }
  if (err?.response?.status === 403) return '当前角色无权读取该端点'
  if (err?.response?.status === 404) return 'CDSS 模块开关未启用或端点不存在'
  return err?.message || '请求失败'
}

async function load() {
  // 四个端点分别 catch：任何一个失败都不该把其余四档一起变成空白
  await Promise.all([
    client.get('/cdss/allergy/rules', { params: { limit: 1 } })
      .then((r) => set('cdss.gate.allergy', String(r.data.data.gate)))
      .catch((e) => set('cdss.gate.allergy', null, reason(e))),
    client.get('/cdss/duplicate/config')
      .then((r) => {
        set('cdss.gate.duplicate', String(r.data.data.gate))
        lookbackDays.value = Number(r.data.data.lookbackDays)
      })
      .catch((e) => set('cdss.gate.duplicate', null, reason(e))),
    client.get('/cdss/population/gate')
      .then((r) => set('cdss.gate.population', String(r.data.data.gate)))
      .catch((e) => set('cdss.gate.population', null, reason(e))),
    client.get('/cdss/route/settings')
      .then((r) => {
        set('cdss.gate.route', String(r.data.data.routeGate))
        set('cdss.gate.solvent', String(r.data.data.solventGate))
      })
      .catch((e) => {
        set('cdss.gate.route', null, reason(e))
        set('cdss.gate.solvent', null, reason(e))
      }),
  ])
}

onMounted(load)
defineExpose({ load })
</script>

<style scoped>
.gate-bar { margin-bottom: 10px; }
.gate-grid { display: flex; flex-wrap: wrap; gap: 4px 18px; margin-top: 4px; }
.gate-cell { display: flex; align-items: center; gap: 6px; line-height: 22px; }
.gate-key { font-weight: 600; }
.gate-cfg { font-size: 12px; color: #909399; }
.gate-mean { font-size: 12px; color: #606266; }
.gate-mean.err { color: var(--el-color-danger); }
.gate-foot { margin-top: 6px; font-size: 12px; color: #606266; }
</style>
