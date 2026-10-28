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

// Compatibility positives: the original production reader already degrades
// safely. Execute its complete real setup and real shared Axios interceptors;
// only the transport adapter, UI, storage and lifecycle are controlled.
const cleanups: Array<() => Promise<void>> = []
const scopes: ReturnType<typeof Vue.effectScope>[] = []
afterEach(async () => {
  try { for (const cleanup of cleanups.splice(0)) await cleanup() }
  finally { for (const scope of scopes.splice(0)) scope.stop() }
})
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const settle = () => new Promise<void>(resolve => setTimeout(resolve, 0))
const withoutImports = (source: string, filename: string) => {
  const parsed = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  return parsed.statements.filter(s => !ts.isImportDeclaration(s)).map(s => s.getText(parsed)).join('\n')
}
type Row = { registrationId: number; patientId: number; patientName: string; deptId: number; status: string }
type Wire = { config: any; done: boolean; reply: (data: unknown, code?: number, status?: number) => void }
const field = { id: 711, templateId: 71, fieldCode: 'V143_TEXT', label: '模拟结构化元素', datatype: 'TEXT',
  required: false, sortNo: 1, valueSet: [], placeholder: '模拟输入', unit: null, enabled: true }

function setup() {
  const a: Row = { registrationId: 11, patientId: 101, patientName: '模拟甲', deptId: 7, status: 'IN_PROGRESS' }
  const b: Row = { registrationId: 22, patientId: 202, patientName: '模拟乙', deptId: 8, status: 'IN_PROGRESS' }
  const storage = new Map([['hip_token', 'v143-compatible-token']])
  const localStorage = { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) }
  const ElMessage = Object.assign(vi.fn(), { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() })
  const router = { push: vi.fn() }, clientBox: Record<string, any> = { axios, ElMessage, router, localStorage, Blob, exports: {} }
  const sharedSource = readFileSync(new URL('../../api/client.ts', import.meta.url), 'utf8')
  runInNewContext(ts.transpileModule(withoutImports(sharedSource, 'client.ts'), { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
  } }).outputText, clientBox)
  const client = clientBox.exports.default, requests: Wire[] = []
  client.defaults.adapter = (config: any) => new Promise((resolve, reject) => {
    const wire: Wire = { config, done: false, reply(data, code = 0, status = 200) {
      expect(wire.done).toBe(false); wire.done = true
      const response = { data: { code, message: code ? '模板未授权或岗位无权' : 'success', data },
        config, status, statusText: String(status), headers: {} }
      if (status >= 400) reject(new axios.AxiosError('Request failed with status code ' + status, 'ERR_BAD_REQUEST', config, undefined, response))
      else resolve(response)
    } }
    requests.push(wire)
    if (/^\/emr\/templates\/\d+\/fields$/.test(config.url)) return
    const match = String(config.url).match(/^\/outpatient\/doctor\/(\d+)\/workspace$/)
    if (match) {
      const row = Number(match[1]) === 11 ? a : b
      wire.reply({ emr: { id: row.registrationId * 10, doctorId: 3, chiefComplaint: row.patientName + '主诉',
        presentIllness: row.patientName + '原现病史', pastHistory: '原既往史', physicalExam: '原查体', advice: '原处理', signature: null },
      diagnoses: [{ icdCode: 'D' + row.registrationId, icdName: row.patientName + '诊断' }],
      orders: [{ id: row.registrationId * 100, status: 'CREATED', itemName: row.patientName + '医嘱' }] })
    } else if (config.url === '/outpatient/doctor/special-disease') wire.reply([])
    else if (config.url === '/outpatient/emr-ref') wire.reply({ prefill: { patientName: '模拟患者', sexText: '女' } })
    else throw new Error('Unconfigured actual request: ' + config.method + ' ' + config.url)
  })
  const unmounts: Array<() => void> = []
  const box: Record<string, any> = {
    ...Vue, ...dateUtils, ...templateUtils, ...defaultUtils, ...submitUtils, ...printUtils, ...rxUtils,
    applyTemplateToEmr: templateUtils.applyTemplate, client, ElMessage,
    ElMessageBox: { confirm: vi.fn(() => Promise.resolve(true)), prompt: vi.fn() },
    useAuthStore: () => ({ user: { id: 3, menus: [] } }),
    useEmrPasteGuard: () => ({ mode: Vue.ref('warn'), loadPolicy: vi.fn(), onCopy: vi.fn(), onPaste: vi.fn() }),
    onMounted: vi.fn(), onUnmounted: (fn: () => void) => unmounts.push(fn), window: { open: vi.fn() }, console,
  }
  const component = readFileSync(new URL('../../views/outpatient/DoctorStationView.vue', import.meta.url), 'utf8')
  const source = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const exposed = '\nglobalThis.view = { current, emr, diagList, orders, currentEmrId, emrAuthorId, emrSigned, '
    + 'structTemplateId, structFields, structValues, structHint, openPatient, loadStructFields };'
  const scope = Vue.effectScope(); scopes.push(scope)
  scope.run(() => runInNewContext(ts.transpileModule(withoutImports(source, 'DoctorStationView.ts') + exposed, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box))
  const v = box.view as Record<string, any>
  const leave = () => unmounts.forEach(fn => fn())
  cleanups.push(async () => {
    leave()
    for (let turn = 0; turn < 10; ++turn) {
      const pending = requests.filter(wire => !wire.done)
      if (!pending.length) return
      for (const wire of pending) wire.reply([])
      await settle()
    }
    expect(requests.every(wire => wire.done)).toBe(true)
  })
  return { v, a, b, requests, ElMessage, storage, router, leave,
    open: (row: Row | null) => v.openPatient(row),
    async begin(id = 71) {
      v.structTemplateId.value = id
      const promise = v.loadStructFields()
      await settle()
      const wire = requests.filter(r => !r.done && r.config.url === `/emr/templates/${id}/fields`).at(-1)
      expect(wire).toBeDefined()
      return { promise, wire: wire! }
    } }
}
type Harness = ReturnType<typeof setup>
const clinical = (h: Harness) => copy({ current: h.v.current.value, emr: h.v.emr, diagnoses: h.v.diagList.value,
  orders: h.v.orders.value, emrId: h.v.currentEmrId.value, author: h.v.emrAuthorId.value, signed: h.v.emrSigned.value })
const expectNoWritesOrSuccess = (h: Harness) => {
  expect(h.requests.every(wire => wire.config.method === 'get')).toBe(true)
  expect(h.ElMessage.success).not.toHaveBeenCalled()
}

describe('existing real field reader safely consumes parent ACL refusals (compatibility positives)', () => {
  it.each([{ name: 'parent visibility 4066', code: 4066, status: 200 },
    { name: 'role 403/1005', code: 1005, status: 403 },
    { name: 'original field contract error 4024', code: 4024, status: 200 },
    { name: 'actual server failure', code: 5000, status: 503 }])('$name preserves the current clinical draft and degrades only field selection', async ({ code, status }) => {
    const h = setup(); await h.open(h.a)
    h.v.emr.presentIllness = '医生未保存的自由正文'; h.v.structFields.value = [field]; h.v.structValues.value = { V143_TEXT: '旧临时结构值' }
    const before = clinical(h), request = await h.begin()
    expect(h.v.structFields.value).toEqual([]); expect(h.v.structValues.value).toEqual({})
    request.wire.reply(null, code, status); await request.promise
    expect(clinical(h)).toEqual(before)
    expect(h.v.structHint.value).toBe('结构化元素定义暂不可读，本次仍可按自由文本书写病历')
    expect(h.v.structFields.value).toEqual([]); expect(h.v.structTemplateId.value).toBe(71)
    expect(h.ElMessage.error).toHaveBeenCalledTimes(1)
    expect(h.storage.get('hip_token')).toBe('v143-compatible-token'); expect(h.router.push).not.toHaveBeenCalled()
    expectNoWritesOrSuccess(h)
  })
  it('receives the original eleven-key field DTO with real token transport and no clinical mutation', async () => {
    const h = setup(); await h.open(h.a); const before = clinical(h), request = await h.begin()
    expect(request.wire.config.headers.Authorization).toBe('Bearer v143-compatible-token')
    request.wire.reply([copy(field)]); await request.promise
    expect(h.v.structFields.value).toEqual([field]); expect(Object.keys(h.v.structFields.value[0])).toHaveLength(11)
    expect(h.v.structHint.value).toBe(''); expect(clinical(h)).toEqual(before); expectNoWritesOrSuccess(h)
  })
  it('the original empty-definition fallback is distinct from an ACL refusal', async () => {
    const h = setup(); await h.open(h.a); const before = clinical(h), request = await h.begin()
    request.wire.reply([]); await request.promise
    expect(h.v.structHint.value).toContain('该模板尚未配置结构化元素')
    expect(h.ElMessage.error).not.toHaveBeenCalled(); expect(clinical(h)).toEqual(before); expectNoWritesOrSuccess(h)
  })
  it('a later authorized read restores real definitions after a refused selection', async () => {
    const h = setup(); await h.open(h.a); const before = clinical(h), denied = await h.begin()
    denied.wire.reply(null, 4066); await denied.promise
    h.v.structTemplateId.value = null; await h.v.loadStructFields()
    const restored = await h.begin(); restored.wire.reply([copy(field)]); await restored.promise
    expect(h.v.structFields.value).toEqual([field]); expect(h.v.structHint.value).toBe('')
    expect(clinical(h)).toEqual(before); expectNoWritesOrSuccess(h)
  })
  it('clearing the selector clears only field scratch state without a new request', async () => {
    const h = setup(); await h.open(h.a); h.v.structFields.value = [field]; h.v.structValues.value = { V143_TEXT: '临时值' }
    const before = clinical(h), count = h.requests.length
    h.v.structTemplateId.value = null; await h.v.loadStructFields()
    expect(h.requests).toHaveLength(count); expect(h.v.structFields.value).toEqual([]); expect(h.v.structValues.value).toEqual({})
    expect(h.v.structHint.value).toBe(''); expect(clinical(h)).toEqual(before); expectNoWritesOrSuccess(h)
  })
  it('without a ready current workspace it does not dispatch a field request', async () => {
    const h = setup(); h.v.structTemplateId.value = 71; await h.v.loadStructFields()
    expect(h.requests).toEqual([]); expectNoWritesOrSuccess(h)
  })
  const lateReplies = [{ name: 'success', code: 0, status: 200 },
    { name: 'parent ACL 4066', code: 4066, status: 200 }, { name: 'role denial 403', code: 1005, status: 403 }]
  it.each(lateReplies)('late $name after switching patients cannot alter B definitions, hint or clinical draft', async ({ code, status }) => {
    const h = setup(); await h.open(h.a); const old = await h.begin()
    await h.open(h.b); h.v.emr.chiefComplaint = '乙未保存主诉'; h.v.structTemplateId.value = 72
    h.v.structFields.value = [{ ...field, id: 722, templateId: 72, label: '乙元素' }]; h.v.structValues.value = { B: '乙临时值' }; h.v.structHint.value = '乙当前提示'
    const before = clinical(h), defs = copy(h.v.structFields.value), values = copy(h.v.structValues.value)
    old.wire.reply(code ? null : [copy(field)], code, status); await old.promise
    expect(clinical(h)).toEqual(before); expect(h.v.structFields.value).toEqual(defs); expect(h.v.structValues.value).toEqual(values)
    expect(h.v.structHint.value).toBe('乙当前提示'); expectNoWritesOrSuccess(h)
  })
  it.each(lateReplies)('late $name after workspace A→B→A still belongs to the first A generation', async ({ code, status }) => {
    const h = setup(); await h.open(h.a); const old = await h.begin()
    await h.open(h.b); await h.open(h.a); h.v.emr.advice = '新一轮甲草稿'; h.v.structTemplateId.value = 71
    h.v.structFields.value = [{ ...field, label: '新一轮甲元素' }]; h.v.structHint.value = '新一轮甲提示'
    const before = clinical(h), defs = copy(h.v.structFields.value)
    old.wire.reply(code ? null : [copy(field)], code, status); await old.promise
    expect(clinical(h)).toEqual(before); expect(h.v.structFields.value).toEqual(defs)
    expect(h.v.structHint.value).toBe('新一轮甲提示'); expectNoWritesOrSuccess(h)
  })
  it.each(lateReplies)('late $name for another selected template cannot replace the current selection', async ({ code, status }) => {
    const h = setup(); await h.open(h.a); const old = await h.begin()
    const current = await h.begin(72), currentField = { ...field, id: 722, templateId: 72, label: '当前模板元素' }
    current.wire.reply([currentField]); await current.promise
    const before = clinical(h)
    old.wire.reply(code ? null : [copy(field)], code, status); await old.promise
    expect(h.v.structTemplateId.value).toBe(72); expect(h.v.structFields.value).toEqual([currentField]); expect(h.v.structHint.value).toBe('')
    expect(clinical(h)).toEqual(before); expectNoWritesOrSuccess(h)
  })
  it.each(lateReplies)('late $name after unmount cannot repopulate definitions or a fallback hint', async ({ code, status }) => {
    const h = setup(); await h.open(h.a); const old = await h.begin(); h.leave()
    h.v.structFields.value = []; h.v.structHint.value = '已卸载'
    const before = clinical(h)
    old.wire.reply(code ? null : [copy(field)], code, status); await old.promise
    expect(h.v.structFields.value).toEqual([]); expect(h.v.structHint.value).toBe('已卸载')
    expect(clinical(h)).toEqual(before); expectNoWritesOrSuccess(h)
  })
})
