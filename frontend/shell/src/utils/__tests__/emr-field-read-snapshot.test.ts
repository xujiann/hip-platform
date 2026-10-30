import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as Vue from 'vue'
import axios from 'axios'
import * as dateUtils from '../date'
import * as templateUtils from '../emr-template'
import * as defaultUtils from '../default-template'
import * as submitUtils from '../emr-submit'
import * as printUtils from '../print-format'
import * as rxUtils from '../rx-template-apply'

// Positive compatibility of entire original SFC setup scripts and actual client.
// Transport/lifecycle are controlled. This VM is not a mounted browser or the
// server JDBC proof. Same-workspace A→B→A has no per-read generation in v68;
// that known P2 is deliberately not advertised as fixed by these tests.
const cleanups: Array<() => void> = []
afterEach(() => { for (const done of cleanups.splice(0)) done() })
const copy = <T>(v: T): T => JSON.parse(JSON.stringify(v))
function withoutImports(source: string, name: string) {
  const parsed = ts.createSourceFile(name, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  return parsed.statements.filter(s => !ts.isImportDeclaration(s)).map(s => s.getText(parsed)).join('\n')
}
const fieldKeys = ['id', 'templateId', 'fieldCode', 'label', 'datatype', 'required', 'sortNo', 'valueSet', 'placeholder', 'unit', 'enabled'].sort()
const definitions = ['TEXT', 'NUMBER', 'CHECKBOX', 'RADIO', 'MULTI', 'DATE'].map((datatype, index) => ({
  id: 711 + index, templateId: 71, fieldCode: 'f' + index, label: 'V150模拟' + datatype,
  datatype, required: false, sortNo: index + 1, valueSet: ['RADIO', 'MULTI'].includes(datatype) ? ['低', '高'] : [],
  placeholder: '原元素提示', unit: datatype === 'NUMBER' ? '℃' : null, enabled: true,
}))
const a = { registrationId: 11, patientId: 101, patientName: 'V150模拟甲', deptId: 7, status: 'VISITED' }
const b = { registrationId: 22, patientId: 202, patientName: 'V150模拟乙', deptId: 8, status: 'VISITED' }
type Wire = { config: any; done: boolean; reply: (data?: unknown, code?: number, status?: number) => void }
async function setup() {
  const storage = new Map([['hip_token', 'v150-original-token']])
  const localStorage = { getItem: (k: string) => storage.get(k) ?? null, setItem: (k: string, v: string) => storage.set(k, v), removeItem: (k: string) => storage.delete(k) }
  const router = { push: vi.fn() }
  const ElMessage = Object.assign(vi.fn(), { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() })
  const ElMessageBox = { confirm: vi.fn(), prompt: vi.fn() }
  const clientBox: Record<string, any> = { axios, localStorage, router, ElMessage, Blob, exports: {} }
  const clientSource = readFileSync(new URL('../../api/client.ts', import.meta.url), 'utf8')
  runInNewContext(ts.transpileModule(withoutImports(clientSource, 'client.ts'), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText, clientBox)
  const requests: Wire[] = [], client = clientBox.exports.default, unmounts: Array<() => void> = []
  client.defaults.adapter = (config: any) => new Promise((resolve, reject) => {
    expect(config.method).toBe('get')
    const wire: Wire = { config, done: false, reply(data = null, code = 0, status = 200) {
      expect(wire.done).toBe(false); wire.done = true
      const response = { config, status, statusText: String(status), headers: {}, data: { code, message: code ? '模板字段读取被拒' : 'success', data } }
      if (status >= 400) reject(new axios.AxiosError('Request failed with status code ' + status, 'ERR_BAD_REQUEST', config, undefined, response))
      else resolve(response)
    } }; requests.push(wire)
    if (/^\/emr\/templates\/\d+\/fields$/.test(config.url)) return
    const workspace = config.url.match(/^\/outpatient\/doctor\/(\d+)\/workspace$/)
    if (workspace) wire.reply({ emr: { id: Number(workspace[1]) * 10, doctorId: 3, signature: null,
      chiefComplaint: '原主诉' + workspace[1], presentIllness: '原现病史', pastHistory: '原既往史', physicalExam: '原查体', advice: '原处理' },
      diagnoses: [{ icdCode: 'D' + workspace[1], icdName: '原模拟诊断' }], orders: [] })
    else if (config.url === '/outpatient/doctor/special-disease') wire.reply([])
    else if (config.url === '/outpatient/emr-ref') wire.reply({ prefill: {} })
    else if (/^\/outpatient\/doctor\/\d+\/emr\/amendments$/.test(config.url)) wire.reply([])
    else throw new Error('Unconfigured original GET ' + config.url)
  })
  const component = readFileSync(new URL('../../views/outpatient/DoctorStationView.vue', import.meta.url), 'utf8')
  const script = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const box: Record<string, any> = { ...Vue, ...dateUtils, ...templateUtils, ...defaultUtils, ...submitUtils,
    ...printUtils, ...rxUtils, applyTemplateToEmr: templateUtils.applyTemplate, client, ElMessage, ElMessageBox,
    useAuthStore: () => ({ user: { id: 3, roles: ['DOCTOR_OUTP'], menus: [] } }),
    useEmrPasteGuard: () => ({ mode: Vue.ref('warn'), loadPolicy: vi.fn(), onCopy: vi.fn(), onPaste: vi.fn() }),
    onMounted: vi.fn(), onUnmounted: (fn: () => void) => unmounts.push(fn), window: { open: vi.fn() }, console }
  const scope = Vue.effectScope()
  scope.run(() => runInNewContext(ts.transpileModule(withoutImports(script, 'DoctorStationView.ts') + `
globalThis.view = { openPatient, current, emr, diagList, emrSigned, currentEmrId, structTemplateId,
  structFields, structValues, structHint, loadStructFields, workspaceReady, workspaceLoading };`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box))
  const v = box.view as Record<string, any>
  await v.openPatient(copy(a)); requests.length = 0
  cleanups.push(() => { try { expect(requests.filter(w => !w.done)).toEqual([]) } finally { unmounts.forEach(fn => fn()); scope.stop() } })
  const begin = async (id: number | null = 71) => {
    const offset = requests.length; v.structTemplateId.value = id
    const result = v.loadStructFields() as Promise<void>
    if (!id) return { result, wire: undefined }
    await vi.waitFor(() => expect(requests.length).toBeGreaterThan(offset))
    return { result, wire: requests.slice(offset).find(w => w.config.url === `/emr/templates/${id}/fields`)! }
  }
  return { v, requests, begin, storage, router, ElMessage, ElMessageBox, unmount: () => unmounts.forEach(fn => fn()) }
}
type Harness = Awaited<ReturnType<typeof setup>>
const draft = (h: Harness) => copy({ current: h.v.current.value, emr: h.v.emr, diagnoses: h.v.diagList.value, signed: h.v.emrSigned.value, emrId: h.v.currentEmrId.value })
function kept(h: Harness) {
  expect(h.storage.get('hip_token')).toBe('v150-original-token'); expect(h.router.push).not.toHaveBeenCalled()
  expect(h.requests.every(w => w.config.method === 'get')).toBe(true); expect(h.ElMessage.success).not.toHaveBeenCalled()
}
function form(fields = copy(definitions), values: Record<string, unknown> = {}) {
  const props = Vue.reactive({ fields, modelValue: values, disabled: false, previewLabel: '保存后写入现病史' }), emit = vi.fn()
  const component = readFileSync(new URL('../../components/StructuredFieldForm.vue', import.meta.url), 'utf8')
  const script = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const scope = Vue.effectScope(), box: Record<string, any> = { ...Vue, ElMessage: { success: vi.fn() },
    defineProps: () => props, withDefaults: (v: unknown) => v, defineEmits: () => emit, defineExpose: vi.fn() }
  scope.run(() => runInNewContext(ts.transpileModule(withoutImports(script, 'StructuredFieldForm.ts') + `
globalThis.form = { sorted, options, asText, asNumber, asBool, asArray, set, answered,
  missingRequired, preview, toNumberOrNull, toStringArray };`, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box))
  cleanups.push(() => scope.stop()); return { v: box.form as Record<string, any>, props, emit }
}

describe('v150 original structured-field consumer compatibility', () => {
  it('clears previous definitions/values/hint before the exact GET and consumes all11 DTO keys without changing clinical draft', async () => {
    const h = await setup(), before = draft(h)
    h.v.structFields.value = [{ fieldCode: 'old' }]; h.v.structValues.value = { old: '旧本地值' }; h.v.structHint.value = '旧提示'
    const next = await h.begin(); expect(h.v.structFields.value).toEqual([]); expect(h.v.structValues.value).toEqual({}); expect(h.v.structHint.value).toBe('')
    expect(next.wire!.config).toMatchObject({ method: 'get', url: '/emr/templates/71/fields' })
    expect(next.wire!.config.params).toBeUndefined(); expect(next.wire!.config.headers.Authorization).toBe('Bearer v150-original-token')
    next.wire!.reply(copy(definitions)); await next.result
    expect(h.v.structFields.value).toEqual(definitions); expect(Object.keys(h.v.structFields.value[0]).sort()).toEqual(fieldKeys)
    expect(draft(h)).toEqual(before); expect(h.v.structHint.value).toBe(''); kept(h)
  })
  it.each([[], null])('original empty payload %j gives its free-text guidance with no fabricated403/4066', async payload => {
    const h = await setup(), before = draft(h), next = await h.begin(); next.wire!.reply(payload); await next.result
    expect(h.v.structFields.value).toEqual([]); expect(h.v.structHint.value).toContain('该模板尚未配置结构化元素')
    expect(h.ElMessage.error).not.toHaveBeenCalled(); expect(draft(h)).toEqual(before); kept(h)
  })
  it('clear selection removes structured local state and makes no HTTP request', async () => {
    const h = await setup(), before = draft(h); h.v.structFields.value = copy(definitions); h.v.structValues.value = { f0: '旧值' }; h.v.structHint.value = '旧提示'
    await (await h.begin(null)).result; expect(h.requests).toEqual([]); expect(h.v.structFields.value).toEqual([])
    expect(h.v.structValues.value).toEqual({}); expect(h.v.structHint.value).toBe(''); expect(draft(h)).toEqual(before); kept(h)
  })
  for (const [name, code, status] of [['4066', 4066, 200], ['403', 1005, 403], ['503', 5000, 503]] as const) {
    it(`${name} keeps original text/diagnoses/token, shows original unreadable guidance and never saves`, async () => {
      const h = await setup(), before = draft(h), next = await h.begin(); next.wire!.reply(null, code, status); await next.result
      expect(h.v.structFields.value).toEqual([]); expect(h.v.structValues.value).toEqual({}); expect(h.v.structHint.value).toContain('结构化元素定义暂不可读')
      expect(h.ElMessage.error).toHaveBeenCalledTimes(1); expect(draft(h)).toEqual(before); kept(h)
    })
  }
  it('401 retains draft but follows the real client expiry path and clears only its token', async () => {
    const h = await setup(), before = draft(h), next = await h.begin(); next.wire!.reply(null, 1000, 401); await next.result
    expect(draft(h)).toEqual(before); expect(h.storage.has('hip_token')).toBe(false); expect(h.router.push).toHaveBeenCalledWith('/login')
    expect(h.ElMessage.warning).toHaveBeenCalledTimes(1); expect(h.v.structHint.value).toContain('暂不可读')
  })
  it('explicit reselection after a known denial permits a new successful read without saving clinical data', async () => {
    const h = await setup(), before = draft(h), first = await h.begin(); first.wire!.reply(null, 4066); await first.result
    await (await h.begin(null)).result; const next = await h.begin(); next.wire!.reply(copy(definitions)); await next.result
    expect(h.requests).toHaveLength(2); expect(h.v.structFields.value).toEqual(definitions); expect(h.v.structHint.value).toBe(''); expect(draft(h)).toEqual(before); kept(h)
  })
  it.each([false, true])('different current template ignores the older success/error response (error=%s)', async error => {
    const h = await setup(), first = await h.begin(71), next = await h.begin(72)
    const rows = definitions.map(f => ({ ...f, templateId: 72 })); next.wire!.reply(rows); await next.result
    first.wire!.reply(error ? null : copy(definitions), error ? 4066 : 0); await first.result
    expect(h.v.structFields.value).toEqual(rows); expect(h.v.structHint.value).toBe(''); kept(h)
  })
  it.each([false, true])('another patient workspace ignores the old success/error (error=%s)', async error => {
    const h = await setup(), first = await h.begin(); await h.v.openPatient(copy(b)); const before = draft(h)
    first.wire!.reply(error ? null : copy(definitions), error ? 4066 : 0); await first.result
    expect(draft(h)).toEqual(before); expect(h.v.structFields.value).toEqual([]); expect(h.v.structHint.value).toBe(''); kept(h)
  })
  it('reopening the same visit still creates a new workspace generation that rejects an old definition reply', async () => {
    const h = await setup(), first = await h.begin(); await h.v.openPatient(copy(a)); first.wire!.reply(copy(definitions)); await first.result
    expect(h.v.structFields.value).toEqual([]); expect(h.v.structTemplateId.value).toBeNull(); kept(h)
  })
  it.each([false, true])('unmount rejects old field success/error without updating definitions or hint (error=%s)', async error => {
    const h = await setup(), first = await h.begin(); h.unmount(); first.wire!.reply(error ? null : copy(definitions), error ? 4066 : 0); await first.result
    expect(h.v.structFields.value).toEqual([]); expect(h.v.structHint.value).toBe(''); kept(h)
  })
  it('read completion does not reset values typed locally after that same read began', async () => {
    const h = await setup(), next = await h.begin(); h.v.structValues.value = { f0: '读取期间的本地值' }
    next.wire!.reply(copy(definitions)); await next.result; expect(h.v.structValues.value).toEqual({ f0: '读取期间的本地值' }); kept(h)
  })
})

describe('v150 complete original StructuredFieldForm six-type props contract', () => {
  it.each([
    ['TEXT', ' 原文本 ', 'asText', ' 原文本 '], ['NUMBER', '37.5', 'asNumber', 37.5],
    ['CHECKBOX', false, 'asBool', false], ['RADIO', '高', 'asText', '高'],
    ['MULTI', ['低', '高'], 'asArray', ['低', '高']], ['DATE', '2026-10-30', 'asText', '2026-10-30'],
  ])('reads original %s value shape through the actual component', (datatype, value, method, expected) => {
    const f = definitions.find(x => x.datatype === datatype)!, h = form(copy(definitions), { [f.fieldCode]: value })
    expect(h.v[String(method)](f)).toEqual(expected); expect(h.v.sorted.value).toHaveLength(6)
  })
  it('filters disabled definitions and preserves original sortNo/fieldCode tie order without changing props', () => {
    const rows = [{ ...definitions[0], fieldCode: 'z', sortNo: 2 }, { ...definitions[1], fieldCode: 'a', sortNo: 2 }, { ...definitions[2], enabled: false }]
    const h = form(rows), before = copy(rows); expect(h.v.sorted.value.map((f: any) => f.fieldCode)).toEqual(['a', 'z']); expect(h.props.fields).toEqual(before)
  })
  it('accepts original array/legacy JSON options and fails malformed options to an empty list', () => {
    const h = form(); expect(h.v.options({ valueSet: ['甲', '乙'] })).toEqual(['甲', '乙'])
    expect(h.v.options({ valueSet: '["甲","乙"]' })).toEqual(['甲', '乙']); expect(h.v.options({ valueSet: '{bad' })).toEqual([])
    expect(h.v.options({ valueSet: '{}' })).toEqual([])
  })
  it('checkbox false is an answer and previews 否 while empty required text stays missing', () => {
    const rows = [{ ...definitions[0], required: true }, { ...definitions[2], required: true }], h = form(rows, { f2: false })
    expect(h.v.missingRequired.value.map((f: any) => f.fieldCode)).toEqual(['f0']); expect(h.v.preview.value).toBe('V150模拟CHECKBOX：否')
  })
  it('emits a copied field value only and keeps clinical rendering as local preview', () => {
    const h = form(copy(definitions), { f0: '原值' }); h.v.set(definitions[1], 38.5)
    expect(h.emit).toHaveBeenCalledWith('update:modelValue', { f0: '原值', f1: 38.5 }); expect(h.props.modelValue).toEqual({ f0: '原值' })
    h.props.modelValue = { f1: 38.5, f4: ['低', '高'], f2: false }
    expect(h.v.preview.value).toBe('V150模拟NUMBER（℃）：38.5；V150模拟CHECKBOX：否；V150模拟MULTI：低、高')
  })
})
