<template>
  <div v-if="points.length" class="vitals-chart">
    <div v-for="p in panels" :key="p.key" class="panel">
      <div class="panel-title">
        <span class="swatch" :style="{ background: p.color }" />{{ p.title }}
      </div>
      <svg :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none" class="plot">
        <line v-for="g in p.grid" :key="g.v" :x1="PAD" :x2="W - 8" :y1="g.y" :y2="g.y" class="grid" />
        <text v-for="g in p.grid" :key="'t' + g.v" :x="PAD - 6" :y="g.y + 4" class="axis-label" text-anchor="end">
          {{ g.v }}
        </text>
        <polyline :points="p.line" fill="none" :stroke="p.color" stroke-width="2" stroke-linejoin="round" />
        <circle v-for="(pt, i) in p.dots" :key="i" :cx="pt.x" :cy="pt.y" r="4" :fill="p.color"
                stroke="#fff" stroke-width="2" class="dot"
                @mouseenter="showTip($event, pt.tip)" @mouseleave="tip = null" />
      </svg>
    </div>
    <div class="x-axis">
      <span v-for="(t, i) in xLabels" :key="i">{{ t }}</span>
    </div>
    <div v-if="tip" class="tooltip" :style="{ left: tip.x + 'px', top: tip.y + 'px' }">{{ tip.text }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

// 体温与脉搏量纲不同：共享时间轴的两个独立面板，不使用双轴
const props = defineProps<{ vitals: Record<string, unknown>[] }>()

const W = 640
const H = 120
const PAD = 40

const points = computed(() =>
  props.vitals
    .filter((v) => v.temperature != null || v.pulse != null)
    .map((v) => ({
      time: String(v.measuredAt).slice(5, 16).replace('T', ' '),
      temperature: v.temperature == null ? null : Number(v.temperature),
      pulse: v.pulse == null ? null : Number(v.pulse),
    })))

const tip = ref<{ x: number; y: number; text: string } | null>(null)

function x(i: number) {
  const n = Math.max(1, points.value.length - 1)
  return PAD + (i / n) * (W - PAD - 16)
}

function makePanel(key: 'temperature' | 'pulse', title: string, color: string,
                   min: number, max: number, ticks: number[], unit: string) {
  const y = (v: number) => H - 14 - ((v - min) / (max - min)) * (H - 28)
  const pts = points.value
    .map((p, i) => ({ v: p[key], i, time: p.time }))
    .filter((d) => d.v != null)
    .map((d) => ({
      x: x(d.i), y: y(d.v as number),
      tip: `${d.time} · ${title} ${d.v}${unit}`,
    }))
  return {
    key, title: `${title}（${unit}）`, color,
    grid: ticks.map((v) => ({ v, y: y(v) })),
    line: pts.map((p) => `${p.x},${p.y}`).join(' '),
    dots: pts,
  }
}

const panels = computed(() => [
  makePanel('temperature', '体温', '#C0392B', 34, 42, [35, 37, 39, 41], '℃'),
  makePanel('pulse', '脉搏', '#2563EB', 40, 160, [60, 100, 140], '次/分'),
])

const xLabels = computed(() => {
  const p = points.value
  if (!p.length) return []
  if (p.length <= 4) return p.map((d) => d.time)
  return [p[0].time, p[Math.floor(p.length / 2)].time, p[p.length - 1].time]
})

function showTip(e: MouseEvent, text: string) {
  const host = (e.currentTarget as SVGElement).closest('.vitals-chart') as HTMLElement
  const rect = host.getBoundingClientRect()
  tip.value = { x: e.clientX - rect.left + 10, y: e.clientY - rect.top - 28, text }
}
</script>

<style scoped>
.vitals-chart { position: relative; margin-bottom: 12px; }
.panel-title { font-size: 12px; color: #666; margin: 4px 0 2px; display: flex; align-items: center; gap: 6px; }
.swatch { width: 10px; height: 10px; border-radius: 2px; display: inline-block; }
.plot { width: 100%; height: 120px; display: block; }
.grid { stroke: #ececec; stroke-width: 1; }
.axis-label { font-size: 10px; fill: #999; }
.dot { cursor: pointer; }
.x-axis { display: flex; justify-content: space-between; padding-left: 40px; font-size: 11px; color: #999; }
.tooltip {
  position: absolute;
  background: #303133;
  color: #fff;
  font-size: 12px;
  padding: 4px 8px;
  border-radius: 4px;
  pointer-events: none;
  white-space: nowrap;
  z-index: 10;
}
</style>
