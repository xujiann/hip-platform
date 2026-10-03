<template>
  <span class="icd-select">
    <!-- 字典检索：名称 / 编码 / 拼音首字母，命中后同时回填编码与名称 -->
    <el-select v-if="!manual" :model-value="icd || undefined" filterable remote clearable reserve-keyword
               :remote-method="search" :loading="loading" :size="size" :disabled="disabled"
               :placeholder="placeholder" :style="{ width: selectWidth }"
               no-data-text="字典里没有匹配项，可勾选右侧「字典没有，手工录入」"
               @visible-change="onVisible" @update:model-value="onPick">
      <el-option v-for="i in shownOptions" :key="i.code" :label="`${i.name} (${i.code})`" :value="i.code" />
    </el-select>

    <!-- 文本回退：字典是常见病目录（全量编码须实施期导入），搜不到时医生仍要能录入，故回退不是可选项 -->
    <template v-else>
      <el-input :model-value="icd" :size="size" :disabled="disabled" placeholder="ICD-10"
                style="width: 110px" @update:model-value="(v: string) => emit('update:icd', v)" />
      <el-input :model-value="name" :size="size" :disabled="disabled" placeholder="诊断名称"
                style="width: 200px" @update:model-value="(v: string) => emit('update:name', v)" />
    </template>

    <el-checkbox v-model="manual" :size="size" :disabled="disabled" class="icd-manual">字典没有，手工录入</el-checkbox>
  </span>
</template>

<script setup lang="ts">
/**
 * v76 车道B（993★ ②）：ICD 字典检索选择器，出院诊断与住院其他诊断共用。
 *
 * 接口：两个独立 v-model，键名与后端请求体一一对应，调用方不用改任何请求体——
 *   <IcdSelect v-model:icd="x.icd" v-model:name="x.name" />
 * 检索走既有 GET /masterdata/icd10?keyword=（返回 [{code, name, pinyin}]，最多 20 条）。
 *
 * 不做「编码必须在字典中」的校验（DoctorStationService 4031 的纪律同源）：字典只有常见病目录，
 * 手工录入的非标编码照常保存——但它不参与 DRG 的 MCC/CC 前缀判定（现状如此，页面上提示一句）。
 */
import { computed, ref, watch } from 'vue'
import client from '../api/client'

interface IcdRow { code: string; name: string; pinyin?: string | null }

const props = withDefaults(defineProps<{
  icd: string
  name: string
  size?: 'small' | 'default' | 'large'
  disabled?: boolean
  placeholder?: string
  selectWidth?: string
}>(), { size: 'small', disabled: false, placeholder: '搜索 ICD（名称/编码/拼音）', selectWidth: '320px' })

const emit = defineEmits<{
  (e: 'update:icd', v: string): void
  (e: 'update:name', v: string): void
}>()

const options = ref<IcdRow[]>([])
const loading = ref(false)
const manual = ref(false)
let seq = 0   // 只认最后一次检索的返回，避免快速输入时旧响应覆盖新结果

/** 当前值若不在检索结果里（回填已存的诊断、或手工录入后切回检索），补一条同值选项，免得下拉里只显示裸编码 */
const shownOptions = computed<IcdRow[]>(() => {
  if (!props.icd || options.value.some((o) => o.code === props.icd)) return options.value
  return [{ code: props.icd, name: props.name || props.icd }, ...options.value]
})

async function search(kw: string) {
  const my = ++seq
  loading.value = true
  try {
    const resp = await client.get('/masterdata/icd10', { params: { keyword: (kw ?? '').trim() } })
    if (my === seq) options.value = (resp.data.data ?? []) as IcdRow[]
  } catch {
    if (my === seq) options.value = []
  } finally {
    if (my === seq) loading.value = false
  }
}

/** 展开下拉时若还没有候选，先拉一页（空关键字 = 字典前 20 条），免得医生面对一个空框 */
function onVisible(open: boolean) {
  if (open && options.value.length === 0) void search('')
}

function onPick(code: string | undefined) {
  if (!code) {
    emit('update:icd', '')
    emit('update:name', '')
    return
  }
  const hit = shownOptions.value.find((o) => o.code === code)
  emit('update:icd', code)
  emit('update:name', hit?.name ?? '')
}

// 外部整体清空（保存成功后重置表单）时，旧候选不再有意义
watch(() => props.icd, (v) => { if (!v) options.value = [] })
</script>

<style scoped>
.icd-select { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.icd-manual { margin-left: 4px; }
</style>
