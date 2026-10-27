import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as Vue from 'vue'
import * as dateUtils from '../date'
import * as templateUtils from '../emr-template'
import * as defaultUtils from '../default-template'
import * as submitUtils from '../emr-submit'
import * as printUtils from '../print-format'
import * as rxUtils from '../rx-template-apply'

// Execute the ENTIRE real setup script, not reconstructed handlers or proposed
// guard names. Only transport/UI/lifecycle dependencies are substituted. All
// actual loaders, openPatient, save/sign/start/submit and template utilities run.
// Deferred replies establish ordering without timers, network or SQL fixtures.
const source = readFileSync(new URL('../../views/outpatient/DoctorStationView.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
const parsed = ts.createSourceFile('DoctorStationView.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const actualScript = parsed.statements.filter(s => !ts.isImportDeclaration(s)).map(s => s.getText(parsed)).join('\n')
const exposed = `
globalThis.view = {
 current, worklist, loadWorklist, emr, currentEmrId, emrAuthorId, autoTpl, visitDenied, diagList, icdOptions,
 specialDiseases, orders, emrSigned, emrSignerName, emrSignedAtText, amendments,
 amendForm, skinTests, basicBrought, prefilledNote, rxLines, labLines, stockWarnings,
 rxCdssWarnings, cdssWarnBarClosed, cdssWarnDialog, cdssTips, tab, structTemplateId,
 structFields, structValues, structHint, savingEmr, signing, submitting, amending,
 history, historyVisible, historyDenied, assist, assistVisible, assistHistoryDenied,
 priorRecords, priorVisible, priorLoading, emrTemplates, insertTarget, refVisible,
 specialForm, savingSpecial, savingTpl, tplHint,
 openPatient, saveEmr, signEmr, startVisit, submitOrders, loadBasicRef,
 loadSpecialDiseases, loadCdssTips, loadAmendments, openHistory, loadAssist,
 loadSkinTests, openPriorRecords, usePriorRecord, applyTemplateBody, loadStructFields,
 submitAmend, addSpecialDisease, removeSpecialDisease, saveAsTemplate,
 undoAutoTemplate, applyTemplate, cancelOrder,
};`
const executable = ts.transpileModule(actualScript + exposed, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
}).outputText

const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const answer = (data: unknown, code = 0, message = 'success') => ({ data: { code, message, data } })
function deferred() {
  let resolve!: (value: any) => void, reject!: (reason: any) => void
  const promise = new Promise<any>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (data: unknown) => resolve(answer(data)),
    raw: resolve, reject }
}
type Row = { registrationId: number; patientId: number; patientName: string; deptId: number; status: string }
function workspace(row: Row, extra: Record<string, any> = {}) {
  const n = row.registrationId
  return { emr: { id: n * 10, doctorId: 3, chiefComplaint: row.patientName + '主诉',
    presentIllness: row.patientName + '现病史', pastHistory: '', physicalExam: row.patientName + '查体',
    advice: row.patientName + '处理', signature: null },
  diagnoses: [{ icdCode: 'D' + n, icdName: row.patientName + '诊断', diagSystem: 'ICD10', prefix: '左' }],
  orders: [{ id: n * 100, registrationId: n, status: 'CREATED', itemName: row.patientName + '医嘱' }],
  ...extra }
}

function setup() {
  const a: Row = { registrationId: 11, patientId: 101, patientName: '模拟甲', deptId: 7, status: 'WAITING' }
  const b: Row = { registrationId: 22, patientId: 202, patientName: '模拟乙', deptId: 8, status: 'WAITING' }
  const rows = [a, b]
  const spaces = new Map(rows.map(row => [row.registrationId, workspace(row)]))
  const calls: { method: string; url: string; body: any }[] = []
  const overrides: { method: string; url: string; handler: (body: any) => any }[] = []
  const get = vi.fn((url: string, opts?: any) => dispatch('GET', url, opts))
  const put = vi.fn((url: string, data?: any) => dispatch('PUT', url, data))
  const post = vi.fn((url: string, data?: any) => dispatch('POST', url, data))
  const del = vi.fn((url: string, data?: any) => dispatch('DELETE', url, data))
  function dispatch(method: string, url: string, body?: any): Promise<any> {
    // Axios serializes when dispatching; retain that request's original body,
    // never a reference that later follows the shared reactive form into B.
    calls.push({ method, url, body: body === undefined ? null : copy(body) })
    const override = overrides.find(x => x.method === method && x.url === url)
    if (override) return Promise.resolve(override.handler(body))
    if (method === 'GET') {
      const m = url.match(/^\/outpatient\/doctor\/(\d+)\/workspace$/)
      if (m) return Promise.resolve(answer(copy(spaces.get(Number(m[1])))))
      if (url === '/outpatient/doctor/special-disease') return Promise.resolve(answer([{ id: body.params.patientId, diseaseName: '病种' + body.params.patientId }]))
      if (url === '/outpatient/emr-ref') return Promise.resolve(answer({ prefill: { patientName: body.params.registrationId === 11 ? '模拟甲' : '模拟乙', sexText: '女', age: 30 } }))
      if (url === '/emr-templates/default') return Promise.resolve(answer(null))
      if (url.endsWith('/emr/amendments')) return Promise.resolve(answer([{ id: Number(url.split('/')[3]) * 1000, amendText: '原补正' }]))
      if (url === '/outpatient/doctor/worklist') return Promise.resolve(answer(copy(rows)))
      if (url === '/cdss/suggestions') return Promise.resolve(answer([{ content: '建议' + body.params.icd }]))
      if (url.endsWith('/history')) return Promise.resolve(answer([{ id: body?.params?.patientId ?? 1, marker: url }]))
      if (url === '/outpatient/doctor/diagnosis-assist') return Promise.resolve(answer({ history: [{ icdName: '历史' + body.params.patientId }], favorite: [], frequent: [] }))
      if (url === '/outpatient/nurse/skin-tests/for-doctor') return Promise.resolve(answer([{ id: body.params.registrationId, result: 'NEGATIVE' }]))
      if (url === '/emr-templates/prior-records') return Promise.resolve(answer([{ record_id: body.params.patientId, source: 'OUTP' }]))
      if (url === '/emr-templates/prior-records/content') return Promise.resolve(answer({ content: '既往甲内容' }))
      if (url.match(/^\/emr\/templates\/\d+\/fields$/)) return Promise.resolve(answer([{ id: 1, field_key: 'fieldA' }]))
      if (url.match(/^\/emr-templates\/\d+$/)) return Promise.resolve(answer({ content: '主诉：模板甲内容' }))
    }
    if (method === 'PUT' && url.endsWith('/emr')) return Promise.resolve(answer({ id: Number(url.split('/')[3]) * 10, doctorId: 3 }))
    if (method === 'POST' && url.endsWith('/start')) return Promise.resolve(answer({ status: 'IN_PROGRESS' }))
    if (method === 'POST' && url.endsWith('/emr/sign')) return Promise.resolve(answer(null))
    if (method === 'POST' && url.endsWith('/orders')) return Promise.resolve(answer([]))
    if (method === 'POST' && url.endsWith('/emr/amend')) return Promise.resolve(answer(null))
    if (method === 'POST' && url === '/outpatient/doctor/special-disease') return Promise.resolve(answer({ id: 31 }))
    if (method === 'DELETE' && url.startsWith('/outpatient/doctor/special-disease/')) return Promise.resolve(answer(null))
    if (method === 'POST' && url === '/emr-templates/from-record') return Promise.resolve(answer({ id: 71 }))
    throw new Error('Unconfigured fixture endpoint: ' + method + ' ' + url)
  }
  const ElMessage = Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() })
  const confirm = vi.fn(() => Promise.resolve(true)), prompt = vi.fn(() => Promise.resolve({ value: '模拟个人模板' }))
  const unmounts: (() => void)[] = []
  const box: Record<string, any> = {
    ...Vue, ...dateUtils, ...templateUtils, ...defaultUtils, ...submitUtils, ...printUtils, ...rxUtils,
    applyTemplateToEmr: templateUtils.applyTemplate, client: { get, put, post, delete: del },
    ElMessage, ElMessageBox: { confirm, prompt },
    useAuthStore: () => ({ user: { id: 3, menus: [] } }),
    useEmrPasteGuard: () => ({ mode: Vue.ref('warn'), loadPolicy: vi.fn(), onCopy: vi.fn(), onPaste: vi.fn() }),
    onMounted: vi.fn(), onUnmounted: (fn: () => void) => unmounts.push(fn),
    window: { open: vi.fn() }, console,
  }
  runInNewContext(executable, box)
  const v = box.view as Record<string, any>
  function route(method: string, url: string, handler: (body: any) => any) { overrides.unshift({ method, url, handler }) }
  function hold(method: string, url: string) { const d = deferred(); route(method, url, () => d.promise); return d }
  function clearCalls() { calls.length = 0; get.mockClear(); put.mockClear(); post.mockClear(); del.mockClear(); for (const x of Object.values(ElMessage)) if (typeof x === 'function' && 'mockClear' in x) x.mockClear() }
  return { v, a, b, rows, spaces, calls, get, put, post, del, route, hold, ElMessage, confirm, prompt,
    clearCalls, open: (r: Row | null) => v.openPatient(r), unmount: () => unmounts.forEach(fn => fn()) }
}
type Harness = ReturnType<typeof setup>
const wsPath = (n: number) => `/outpatient/doctor/${n}/workspace`
const fields = ['current', 'currentEmrId', 'emrAuthorId', 'autoTpl', 'visitDenied', 'diagList', 'icdOptions', 'specialDiseases', 'orders', 'emrSigned', 'emrSignerName', 'emrSignedAtText', 'amendments', 'skinTests', 'basicBrought', 'prefilledNote', 'rxLines', 'labLines', 'stockWarnings', 'rxCdssWarnings', 'cdssWarnBarClosed', 'cdssWarnDialog', 'cdssTips', 'tab', 'structTemplateId', 'structFields', 'structValues', 'structHint', 'savingEmr', 'signing', 'submitting', 'amending', 'history', 'historyVisible', 'historyDenied', 'assist', 'assistVisible', 'assistHistoryDenied', 'priorRecords', 'priorVisible', 'priorLoading', 'refVisible', 'savingSpecial', 'savingTpl', 'tplHint']
function state(h: Harness) {
  return copy({ emr: h.v.emr, amendForm: h.v.amendForm, specialForm: h.v.specialForm,
    ...Object.fromEntries(fields.map(k => [k, h.v[k].value])) })
}
function withoutBusy(value: ReturnType<typeof state>) {
  const out: Record<string, any> = copy(value)
  for (const key of ['savingEmr', 'signing', 'submitting', 'amending', 'savingSpecial', 'savingTpl', 'priorLoading']) delete out[key]
  return out
}
function editedB(h: Harness) {
  h.v.emr.chiefComplaint = '乙医生未保存正文'; h.v.emr.pastHistory = ''
  h.v.rxLines.value = [{ itemId: 222, itemName: '乙未提交处方', qty: 1 }]
  h.v.labLines.value = [{ itemId: 223, itemName: '乙未提交申请', qty: 1 }]
  h.v.stockWarnings.value = ['乙库存提示']; h.v.rxCdssWarnings.value = ['乙审查提示']
  h.v.tab.value = 'emr'
}
async function untilCalled(h: Harness, method: string, url: string) {
  await vi.waitFor(() => expect(h.calls.some(x => x.method === method && x.url === url)).toBe(true))
}
const writes = (h: Harness) => h.calls.filter(x => x.method !== 'GET')

describe('real openPatient readiness and generation boundaries', () => {
  it('loads one live workspace with original EMR, diagnosis, order, special and BASIC contracts', async () => {
    const h = setup(); await h.open(h.a)
    expect(h.v.current.value.registrationId).toBe(11); expect(h.v.currentEmrId.value).toBe(110)
    expect(h.v.emr.chiefComplaint).toBe('模拟甲主诉'); expect(h.v.diagList.value[0]).toMatchObject({ icdCode: 'D11', prefix: '左' })
    expect(h.v.orders.value[0].id).toBe(1100); expect(h.v.specialDiseases.value[0].id).toBe(101)
    expect(h.v.basicBrought.value).toContain('模拟甲'); expect(h.v.visitDenied.value).toBe(false)
    expect(writes(h)).toEqual([])
  })
  it('keeps real default-template and allergy prefill behavior for a new live workspace', async () => {
    const h = setup(); h.spaces.set(11, workspace(h.a, { emr: null, diagnoses: [] }))
    h.route('GET', '/outpatient/emr-ref', () => answer({ prefill: { patientName: '模拟甲', allergyHistory: '青霉素' } }))
    h.route('GET', '/emr-templates/default', () => answer({ id: 71, name: '本科默认', content: '主诉：新模板\n既往史：旧病史', enabled: true, template_type: 'EMR' }))
    await h.open(h.a)
    expect(h.v.emr.chiefComplaint).toBe('新模板'); expect(h.v.emr.pastHistory).toContain('过敏史：青霉素')
    expect(h.v.emr.pastHistory).toContain('旧病史'); expect(h.v.autoTpl.value).toMatchObject({ regId: 11, name: '本科默认' })
    expect(writes(h)).toEqual([])
  })
  it('does not fetch a default template for an existing EMR', async () => {
    const h = setup(); await h.open(h.a); expect(h.calls.some(x => x.url === '/emr-templates/default')).toBe(false)
  })
  it('loads signed amendments and retains signature facts on the same visit', async () => {
    const h = setup(); h.spaces.set(11, workspace(h.a, { emr: { ...workspace(h.a).emr, signature: 'signed', signedAt: '2026-10-26T00:00:00Z' }, emrSignerName: '甲医师' }))
    await h.open(h.a); expect(h.v.emrSigned.value).toBe(true); expect(h.v.emrSignerName.value).toBe('甲医师')
    expect(h.v.amendments.value).toEqual([{ id: 11000, amendText: '原补正' }])
  })
  it('shows the original denied empty state for a live 4036', async () => {
    const h = setup(); h.route('GET', wsPath(11), () => Promise.reject({ bizCode: 4036 }))
    await h.open(h.a); expect(h.v.current.value).toBeNull(); expect(h.v.visitDenied.value).toBe(true)
  })
  it('propagates a non-4036 workspace error under the original contract', async () => {
    const h = setup(); const e = { bizCode: 5000 }; h.route('GET', wsPath(11), () => Promise.reject(e))
    await expect(h.open(h.a)).rejects.toBe(e)
  })
  it('silently degrades unavailable BASIC reference without stopping the live visit', async () => {
    const h = setup(); h.route('GET', '/outpatient/emr-ref', () => Promise.reject({ bizCode: 4036 }))
    await h.open(h.a); expect(h.v.current.value.registrationId).toBe(11); expect(h.v.basicBrought.value).toBe('')
  })
  it('a slow A workspace cannot overwrite a fully loaded and edited B', async () => {
    const h = setup(); const d = h.hold('GET', wsPath(11)); const old = h.open(h.a)
    await h.open(h.b); editedB(h); const before = state(h); h.clearCalls()
    d.resolve(workspace(h.a)); await old
    expect(state(h)).toEqual(before); expect(h.calls).toEqual([])
  })
  it('a late A 4036 cannot clear or deny a newer B workspace', async () => {
    const h = setup(); const d = h.hold('GET', wsPath(11)); const old = h.open(h.a)
    await h.open(h.b); editedB(h); const before = state(h)
    d.reject({ bizCode: 4036 }); await old; expect(state(h)).toEqual(before)
  })
  it.each(['null', 'unmount'])('a workspace reply after %s cannot refill patient UI or continue dependent reads', async leave => {
    const h = setup(); const d = h.hold('GET', wsPath(11)); const old = h.open(h.a)
    if (leave === 'null') await h.open(null); else h.unmount()
    const before = state(h); h.clearCalls(); d.resolve(workspace(h.a)); await old
    expect(state(h)).toEqual(before); expect(h.calls).toEqual([])
  })
  it('A to B to A rejects the original A response despite equal registration identity', async () => {
    const h = setup(); const d = deferred(); let hits = 0
    h.route('GET', wsPath(11), () => ++hits === 1 ? d.promise : answer(workspace(h.a)))
    const old = h.open(h.a); await h.open(h.b); await h.open(h.a)
    h.v.emr.chiefComplaint = '甲再次打开后的新稿'; const before = state(h); h.clearCalls()
    d.resolve(workspace(h.a, { orders: [{ id: 999 }] })); await old
    expect(state(h)).toEqual(before); expect(h.calls).toEqual([])
  })
  it('switching to pending B immediately clears visible A clinical data', async () => {
    const h = setup(); await h.open(h.a); const d = h.hold('GET', wsPath(22)); const pending = h.open(h.b)
    const visible = { text: h.v.emr.chiefComplaint, diagnoses: copy(h.v.diagList.value), orders: copy(h.v.orders.value), special: copy(h.v.specialDiseases.value), basic: h.v.basicBrought.value }
    d.resolve(workspace(h.b)); await pending
    expect(visible).toEqual({ text: '', diagnoses: [], orders: [], special: [], basic: '' })
  })
  it.each(['saveEmr', 'signEmr', 'startVisit', 'submitOrders'])('%s cannot submit while B is pending over A form data', async name => {
    const h = setup(); await h.open(h.a); const d = h.hold('GET', wsPath(22)); const pending = h.open(h.b); h.clearCalls()
    // The old sign/orders handler itself reloads B, so release the original
    // workspace read BEFORE awaiting those handlers. A stuck fixture is not RED.
    const action = h.v[name](...(name === 'submitOrders' ? ['rx'] : []))
    for (let turn = 0; turn < 20; turn++) await Promise.resolve()
    const sent = copy(writes(h)); d.resolve(workspace(h.b)); await Promise.all([pending, action])
    expect(sent).toEqual([]); if (name === 'signEmr') expect(h.confirm).not.toHaveBeenCalled()
  })
  it.each(['special', 'amendments', 'basic'])('A continuation after awaiting %s cannot alter or continue into B', async phase => {
    const h = setup(); let d: ReturnType<typeof deferred>
    if (phase === 'special') {
      d = deferred(); h.route('GET', '/outpatient/doctor/special-disease', p => p.params.patientId === 101 ? d.promise : answer([{ id: 202, diseaseName: '病种202' }]))
    } else if (phase === 'amendments') {
      h.spaces.set(11, workspace(h.a, { emr: { ...workspace(h.a).emr, signature: 'signed' } })); d = h.hold('GET', '/outpatient/doctor/11/emr/amendments')
    } else {
      d = deferred(); h.route('GET', '/outpatient/emr-ref', p => p.params.registrationId === 11 ? d.promise : answer({ prefill: { patientName: '模拟乙' } }))
    }
    const old = h.open(h.a)
    await untilCalled(h, 'GET', phase === 'special' ? '/outpatient/doctor/special-disease' : phase === 'amendments' ? '/outpatient/doctor/11/emr/amendments' : '/outpatient/emr-ref')
    await h.open(h.b); editedB(h); const before = state(h); h.clearCalls()
    d.resolve(phase === 'basic' ? { prefill: { patientName: '旧甲', allergyHistory: '甲过敏' } } : [{ id: 999, amendText: '旧甲', diseaseName: '旧甲' }]); await old
    expect(state(h)).toEqual(before); expect(h.calls).toEqual([])
  })
  it('an old default-list response cannot issue its template-content continuation after switching', async () => {
    const h = setup(); h.spaces.set(11, workspace(h.a, { emr: null, diagnoses: [] })); const d = h.hold('GET', '/emr-templates/default')
    const old = h.open(h.a); await untilCalled(h, 'GET', '/emr-templates/default'); await h.open(h.b)
    const before = state(h); h.clearCalls(); d.resolve({ id: 71, name: '旧甲模板', enabled: true, template_type: 'EMR' }); await old
    expect(state(h)).toEqual(before); expect(h.calls).toEqual([])
  })
  it('an old default-content reply does not mark a new generation of the SAME visit as auto-applied', async () => {
    const h = setup(); h.spaces.set(11, workspace(h.a, { emr: null, diagnoses: [] })); const d = h.hold('GET', '/emr-templates/71')
    h.route('GET', '/emr-templates/default', () => answer({ id: 71, name: '旧甲模板', enabled: true, template_type: 'EMR' }))
    const old = h.open(h.a); await untilCalled(h, 'GET', '/emr-templates/71')
    await h.open(h.b); h.spaces.set(11, workspace(h.a)); await h.open(h.a)
    const before = state(h); h.clearCalls(); d.resolve({ content: '主诉：迟到的甲模板' }); await old
    expect(state(h)).toEqual(before); expect(h.calls).toEqual([])
  })
})

describe('real patient-associated readers, drawers and editor continuations', () => {
  const readers = [
    { name: 'openHistory', url: '/outpatient/doctor/patient/101/history', data: [{ id: 999, marker: '甲历史' }] },
    { name: 'loadAssist', url: '/outpatient/doctor/diagnosis-assist', data: { history: [{ icdName: '甲历史' }], favorite: [], frequent: [] } },
    { name: 'loadSkinTests', url: '/outpatient/nurse/skin-tests/for-doctor', data: [{ id: 999, result: 'POSITIVE' }] },
    { name: 'openPriorRecords', url: '/emr-templates/prior-records', data: [{ record_id: 999, source: 'OUTP' }] },
    { name: 'usePriorRecord', url: '/emr-templates/prior-records/content', data: { content: '甲的既往正文' } },
    { name: 'loadStructFields', url: '/emr/templates/71/fields', data: [{ field_key: '甲元素', id: 999 }] },
    { name: 'applyTemplate', url: '/outpatient/rx-templates/71/lines', data: [{ orderType: 'DRUG', itemId: 999, itemName: '甲药', qty: 1 }] },
    { name: 'loadBasicRef', url: '/outpatient/emr-ref', data: { prefill: { patientName: '甲过时信息', allergyHistory: '甲过敏' } } },
  ]
  function invoke(h: Harness, name: string) {
    if (name === 'loadStructFields') h.v.structTemplateId.value = 71
    const args = name === 'usePriorRecord' ? [{ source: 'OUTP', record_id: 110 }]
      : name === 'applyTemplate' ? [71] : name === 'loadBasicRef' ? [true] : []
    return h.v[name](...args)
  }
  it.each(readers)('$name discards its late A result after switching to edited B', async ({ name, url, data }) => {
    const h = setup(); await h.open(h.a); const d = deferred()
    h.route('GET', url, p => name === 'loadBasicRef' && p.params.registrationId !== 11 ? answer({ prefill: { patientName: '模拟乙' } }) : d.promise)
    const old = invoke(h, name)
    await h.open(h.b); editedB(h)
    // Drawers are patient state too. Explicit B values must not be replaced by A.
    h.v.history.value = [{ id: 222 }]; h.v.priorRecords.value = [{ record_id: 222 }]
    h.v.assist.value = { history: [{ icdName: '乙历史' }], favorite: [], frequent: [] }
    h.v.skinTests.value = [{ id: 222 }]; h.v.structFields.value = [{ field_key: '乙元素' }]
    const before = state(h); h.clearCalls(); d.resolve(data); await old
    expect(state(h)).toEqual(before); expect(h.calls).toEqual([])
    expect(h.ElMessage.success).not.toHaveBeenCalled(); expect(h.ElMessage.info).not.toHaveBeenCalled()
  })
  it.each(readers)('$name retains its original result for one live A workspace', async ({ name, url, data }) => {
    const h = setup(); await h.open(h.a); h.route('GET', url, () => answer(data)); h.clearCalls(); await invoke(h, name)
    expect(h.calls[0].url).toBe(url); expect(writes(h)).toEqual([])
    if (name === 'openHistory') { expect(h.v.history.value).toEqual(data); expect(h.v.historyVisible.value).toBe(true) }
    if (name === 'loadAssist') expect(h.v.assist.value).toEqual(data)
    if (name === 'loadSkinTests') expect(h.v.skinTests.value).toEqual(data)
    if (name === 'openPriorRecords') expect(h.v.priorRecords.value).toEqual(data)
    if (name === 'usePriorRecord') expect(h.v.emr.presentIllness).toContain('甲的既往正文')
    if (name === 'loadStructFields') expect(h.v.structFields.value).toEqual(data)
    if (name === 'applyTemplate') expect(h.v.rxLines.value[0]).toMatchObject({ itemId: 999, tplId: 71 })
    if (name === 'loadBasicRef') expect(h.v.basicBrought.value).toContain('甲过时信息')
  })
  it('a late history 4036 does not mark B denied or open an A history drawer', async () => {
    const h = setup(); await h.open(h.a); const d = h.hold('GET', '/outpatient/doctor/patient/101/history'); const old = h.v.openHistory()
    await h.open(h.b); const before = state(h); d.reject({ bizCode: 4036 }); await old; expect(state(h)).toEqual(before)
  })
  it('a late assist 4036 does not deny B history or send an obsolete fallback read', async () => {
    const h = setup(); await h.open(h.a); const d = h.hold('GET', '/outpatient/doctor/diagnosis-assist'); const old = h.v.loadAssist()
    await h.open(h.b); const before = state(h); h.clearCalls(); d.reject({ bizCode: 4036 })
    // A fallback GET uses this same endpoint on the old version. Provide its
    // ordinary result so it cannot turn a business assertion into fixture wait.
    h.route('GET', '/outpatient/doctor/diagnosis-assist', () => answer({ history: [], favorite: [], frequent: [] }))
    await old; expect(state(h)).toEqual(before); expect(h.calls).toEqual([])
  })
  it('live history 4036 retains the original denied drawer state', async () => {
    const h = setup(); await h.open(h.a); h.route('GET', '/outpatient/doctor/patient/101/history', () => Promise.reject({ bizCode: 4036 }))
    await h.v.openHistory(); expect(h.v.historyDenied.value).toBe(true); expect(h.v.historyVisible.value).toBe(true); expect(h.v.history.value).toEqual([])
  })
  it('live diagnosis 4036 still loads nonpatient favorites with its original fallback', async () => {
    const h = setup(); await h.open(h.a); h.route('GET', '/outpatient/doctor/diagnosis-assist', p => p.params.patientId ? Promise.reject({ bizCode: 4036 }) : answer({ history: [], favorite: [{ icdName: '个人常用' }], frequent: [] }))
    h.clearCalls(); await h.v.loadAssist(); expect(h.v.assistHistoryDenied.value).toBe(true)
    expect(h.v.assist.value.favorite).toEqual([{ icdName: '个人常用' }]); expect(h.get).toHaveBeenCalledTimes(2)
  })
  it('manual template overwrite confirmation cannot let actual applyTemplateToEmr write into B', async () => {
    const h = setup(); await h.open(h.a); h.v.structTemplateId.value = 71; h.v.emrTemplates.value = [{ id: 71, name: '甲正文模板', content: '主诉：甲替换正文' }]
    const d = deferred(); h.confirm.mockImplementation(() => d.promise); const old = h.v.applyTemplateBody()
    expect(h.confirm).toHaveBeenCalledOnce(); await h.open(h.b); editedB(h); const before = state(h); h.clearCalls()
    d.raw(true); await old; expect(state(h)).toEqual(before); expect(h.calls).toEqual([]); expect(h.ElMessage.success).not.toHaveBeenCalled()
  })
  it('manual template overwrite still runs actual utility and reports its live success', async () => {
    const h = setup(); await h.open(h.a); h.v.structTemplateId.value = 71; h.v.emrTemplates.value = [{ id: 71, name: '甲正文模板', content: '主诉：甲替换正文' }]
    await h.v.applyTemplateBody(); expect(h.v.emr.chiefComplaint).toBe('甲替换正文'); expect(h.confirm).toHaveBeenCalledOnce(); expect(h.ElMessage.success).toHaveBeenCalled()
  })
  it('undo-default confirmation cannot restore captured A text into B', async () => {
    const h = setup(); await h.open(h.a); const beforeA = copy(h.v.emr)
    h.v.autoTpl.value = { regId: 11, name: '甲默认', before: beforeA, written: { chiefComplaint: '甲默认段' }, after: { ...beforeA, chiefComplaint: '甲默认段' }, notes: [] }
    h.v.emr.chiefComplaint = '甲对默认模板的新编辑'
    const d = deferred(); h.confirm.mockImplementation(() => d.promise); const old = h.v.undoAutoTemplate()
    expect(h.confirm).toHaveBeenCalledOnce(); await h.open(h.b); editedB(h); const before = state(h); d.raw(true); await old
    expect(state(h)).toEqual(before)
  })
  it('live undo-default retains actual restoration and does not write a clinical endpoint', async () => {
    const h = setup(); await h.open(h.a); const before = copy(h.v.emr)
    h.v.autoTpl.value = { regId: 11, name: '甲默认', before, written: { chiefComplaint: '甲默认段' }, after: { ...before, chiefComplaint: '甲默认段' }, notes: [] }
    h.v.emr.chiefComplaint = '甲默认段'; h.clearCalls(); await h.v.undoAutoTemplate()
    expect(h.v.emr).toEqual(before); expect(h.v.autoTpl.value).toBeNull(); expect(writes(h)).toEqual([])
  })
  it('a delayed save-as-template prompt cannot combine A patientId with B recordId', async () => {
    const h = setup(); await h.open(h.a); const d = deferred(); h.prompt.mockImplementation(() => d.promise); const old = h.v.saveAsTemplate()
    await h.open(h.b); editedB(h); const before = state(h); h.clearCalls(); d.raw({ value: '旧甲名字' }); await old
    expect(writes(h)).toEqual([]); expect(state(h)).toEqual(before)
  })
  it('live save-as-template retains original A ownership and trimmed personal-template payload', async () => {
    const h = setup(); await h.open(h.a); h.prompt.mockResolvedValue({ value: '  甲个人模板  ' }); h.clearCalls(); await h.v.saveAsTemplate()
    expect(writes(h)).toEqual([{ method: 'POST', url: '/emr-templates/from-record', body: { source: 'OUTP', recordId: 110, patientId: 101, name: '甲个人模板', scope: 'PERSONAL' } }])
  })
  it.each(['submitAmend', 'addSpecialDisease', 'removeSpecialDisease'])('late %s success cannot clear B inputs or trigger a B followup read', async name => {
    const h = setup(); await h.open(h.a); h.v.amendForm.amendText = '甲补正'; h.v.amendForm.reason = '甲原因'; h.v.specialForm.diseaseName = '甲病种'
    const method = name === 'removeSpecialDisease' ? 'DELETE' : 'POST'
    const url = name === 'submitAmend' ? '/outpatient/doctor/11/emr/amend' : '/outpatient/doctor/special-disease' + (name === 'removeSpecialDisease' ? '/31' : '')
    const d = h.hold(method, url); const old = h.v[name](...(name === 'removeSpecialDisease' ? [31] : []))
    await h.open(h.b); editedB(h); h.v.amendForm.amendText = '乙自己的补正'; h.v.amendForm.reason = '乙自己的原因'; h.v.specialForm.diseaseName = '乙自己的病种'
    const before = state(h); h.clearCalls(); d.resolve(null); await old
    expect(state(h)).toEqual(before); expect(h.calls).toEqual([]); expect(h.ElMessage.success).not.toHaveBeenCalled()
  })
  it.each(['submitAmend', 'addSpecialDisease', 'removeSpecialDisease'])('live %s retains original endpoint and readback', async name => {
    const h = setup(); await h.open(h.a); h.v.amendForm.amendText = '甲补正'; h.v.amendForm.reason = '甲原因'; h.v.specialForm.diseaseName = '甲病种'
    h.clearCalls(); await h.v[name](...(name === 'removeSpecialDisease' ? [31] : []))
    expect(writes(h)).toHaveLength(1)
    expect(writes(h)[0].url).toBe(name === 'submitAmend' ? '/outpatient/doctor/11/emr/amend' : '/outpatient/doctor/special-disease' + (name === 'removeSpecialDisease' ? '/31' : ''))
    expect(h.calls.some(x => x.method === 'GET' && x.url === (name === 'submitAmend' ? '/outpatient/doctor/11/emr/amendments' : '/outpatient/doctor/special-disease'))).toBe(true)
  })
})

describe('real core write continuations remain bound to their originating workspace', () => {
  it.each(['saveEmr', 'startVisit', 'submitOrders', 'signEmr'])('returning A to B to A cannot duplicate an already pending A %s', async name => {
    const h = setup(); await h.open(h.a)
    const method = name === 'saveEmr' ? 'PUT' : 'POST'
    const url = `/outpatient/doctor/11/${name === 'saveEmr' ? 'emr' : name === 'startVisit' ? 'start' : 'orders'}`
    const d = name === 'signEmr' ? deferred() : h.hold(method, url)
    if (name === 'signEmr') h.confirm.mockImplementation(() => d.promise)
    const invoke = () => h.v[name](...(name === 'submitOrders' ? ['rx'] : []))
    const original = invoke()
    if (name !== 'signEmr') await untilCalled(h, method, url)
    await h.open(h.b); await h.open(h.a); h.v.emr.chiefComplaint = '甲新一代草稿'
    const before = withoutBusy(state(h)); h.clearCalls(); const duplicate = invoke()
    for (let turn = 0; turn < 20; turn++) await Promise.resolve()
    const duplicateWrites = copy(writes(h))
    if (name === 'signEmr') d.raw(true)
    else d.resolve(name === 'saveEmr' ? { id: 110, doctorId: 3 } : name === 'startVisit' ? { status: 'IN_PROGRESS' } : [])
    await Promise.all([original, duplicate])
    expect(duplicateWrites).toEqual([]); expect(writes(h)).toEqual([]); expect(withoutBusy(state(h))).toEqual(before)
    if (name === 'signEmr') expect(h.confirm).toHaveBeenCalledOnce()
  })
  it('the dispatched EMR payload cannot follow reactive form or structured values into B before transport serialization', async () => {
    const h = setup(); await h.open(h.a); h.v.structTemplateId.value = 71; h.v.structFields.value = [{ key: 'f' }]; h.v.structValues.value = { f: '甲结构值' }
    const expectedEmr = copy(h.v.emr); const originalFields = h.v.structValues.value
    const d = h.hold('PUT', '/outpatient/doctor/11/emr'); const old = h.v.saveEmr()
    const rawBody = h.put.mock.calls[0][1]
    await h.open(h.b); editedB(h); originalFields.f = '晚修改的甲值'
    const observed = copy(rawBody); d.resolve({ id: 110, doctorId: 3 }); await old
    expect(observed.emr).toEqual(expectedEmr); expect(observed.fields).toEqual({ f: '甲结构值' })
  })
  it('the dispatched order body does not retain mutable draft-line references before transport serialization', async () => {
    const h = setup(); await h.open(h.a); const originalLine = { itemId: 111, itemName: '甲药', qty: 1 }; h.v.rxLines.value = [originalLine]
    const d = h.hold('POST', '/outpatient/doctor/11/orders'); const old = h.v.submitOrders('rx')
    const rawBody = h.post.mock.calls[0][1]; originalLine.qty = 5
    const observed = copy(rawBody); d.resolve([]); await old
    expect(observed).toEqual({ lines: [{ itemId: 111, itemName: '甲药', qty: 1 }] })
  })
  it('saves a live EMR with original diagnosis and structured-field payload', async () => {
    const h = setup(); await h.open(h.a); h.clearCalls(); h.v.structTemplateId.value = 71; h.v.structFields.value = [{ key: 'f' }]; h.v.structValues.value = { f: '真值' }
    expect(await h.v.saveEmr()).toBe(true)
    const req = writes(h)[0]; expect(req.url).toBe('/outpatient/doctor/11/emr'); expect(req.body.templateId).toBe(71); expect(req.body.fields).toEqual({ f: '真值' })
    expect(req.body.diagnoses[0]).toMatchObject({ icdCode: 'D11', prefix: '左', suffix: null, certainty: null })
    expect(h.v.currentEmrId.value).toBe(110); expect(h.v.emrAuthorId.value).toBe(3); expect(h.v.savingEmr.value).toBe(false)
    expect(h.v.cdssTips.value).toEqual(['建议D11'])
  })
  it('preserves a live failed save result and never signs after a failed presave', async () => {
    const h = setup(); await h.open(h.a); h.route('PUT', '/outpatient/doctor/11/emr', () => answer(null, 4009, '不可保存'))
    h.clearCalls(); expect(await h.v.saveEmr()).toBe(false); await h.v.signEmr()
    expect(h.post).not.toHaveBeenCalled(); expect(h.ElMessage.error).toHaveBeenCalledWith('不可保存'); expect(h.v.savingEmr.value).toBe(false)
  })
  it('a rejected live save propagates and releases its own loading flag', async () => {
    const h = setup(); await h.open(h.a); const e = { bizCode: 4008 }; h.route('PUT', '/outpatient/doctor/11/emr', () => Promise.reject(e))
    await expect(h.v.saveEmr()).rejects.toBe(e); expect(h.v.savingEmr.value).toBe(false)
  })
  it.each(['B', 'null', 'unmount'])('a late save receipt after %s cannot reassign EMR identity, author, auto-template or tips', async leave => {
    const h = setup(); await h.open(h.a); const d = h.hold('PUT', '/outpatient/doctor/11/emr'); const old = h.v.saveEmr()
    if (leave === 'B') { await h.open(h.b); editedB(h); h.v.autoTpl.value = { regId: 22, name: '乙自己的模板' } }
    else if (leave === 'null') await h.open(null); else h.unmount()
    const before = state(h); h.clearCalls(); d.resolve({ id: 999, doctorId: 999 }); await old
    expect(leave === 'unmount' ? withoutBusy(state(h)) : state(h)).toEqual(leave === 'unmount' ? withoutBusy(before) : before)
    expect(h.calls).toEqual([]); expect(h.ElMessage.success).not.toHaveBeenCalled()
  })
  it('a late A save finally cannot clear the loading flag of an independently pending B save', async () => {
    const h = setup(); await h.open(h.a); const a = h.hold('PUT', '/outpatient/doctor/11/emr'); const old = h.v.saveEmr()
    await h.open(h.b); const b = h.hold('PUT', '/outpatient/doctor/22/emr'); const active = h.v.saveEmr()
    a.resolve({ id: 999, doctorId: 999 }); await old; const busy = h.v.savingEmr.value
    b.resolve({ id: 220, doctorId: 3 }); await active; expect(busy).toBe(true)
  })
  it('a late A CDSS response from save cannot replace B tips', async () => {
    const h = setup(); await h.open(h.a); const d = h.hold('GET', '/cdss/suggestions'); const old = h.v.saveEmr()
    await untilCalled(h, 'GET', '/cdss/suggestions'); await h.open(h.b); h.v.cdssTips.value = ['乙自己的建议']; const before = state(h)
    d.resolve([{ content: '甲过时建议' }]); await old; expect(state(h)).toEqual(before)
  })
  it('a live start keeps the original endpoint, IN_PROGRESS result and worklist refresh', async () => {
    const h = setup(); await h.open(h.a); h.clearCalls(); await h.v.startVisit()
    expect(writes(h).map(x => x.url)).toEqual(['/outpatient/doctor/11/start']); expect(h.v.current.value.status).toBe('IN_PROGRESS')
    expect(h.calls.some(x => x.url === '/outpatient/doctor/worklist')).toBe(true)
  })
  it('a late A start receipt does not mark B in progress or refresh B worklist', async () => {
    const h = setup(); await h.open(h.a); const d = h.hold('POST', '/outpatient/doctor/11/start'); const old = h.v.startVisit()
    await h.open(h.b); const before = state(h); h.clearCalls(); d.resolve({ status: 'IN_PROGRESS' }); await old
    expect(state(h)).toEqual(before); expect(h.calls).toEqual([]); expect(h.ElMessage.success).not.toHaveBeenCalled()
  })
  it('a delayed signature confirmation cannot presave or sign the newly selected B', async () => {
    const h = setup(); await h.open(h.a); const d = deferred(); h.confirm.mockImplementation(() => d.promise)
    const old = h.v.signEmr(); await h.open(h.b); editedB(h); const before = state(h); h.clearCalls()
    d.raw(true); await old
    expect(writes(h)).toEqual([]); expect(state(h)).toEqual(before); expect(h.ElMessage.success).not.toHaveBeenCalled()
  })
  it('a signature presave completed after switching never follows it with a B signature', async () => {
    const h = setup(); await h.open(h.a); const d = h.hold('PUT', '/outpatient/doctor/11/emr'); const old = h.v.signEmr()
    await untilCalled(h, 'PUT', '/outpatient/doctor/11/emr'); await h.open(h.b); editedB(h); const before = state(h); h.clearCalls()
    d.resolve({ id: 110, doctorId: 3 }); await old
    expect(writes(h)).toEqual([]); expect(state(h)).toEqual(before)
  })
  it('a delayed signature confirmation after null cannot make a new write or throw a null dereference', async () => {
    const h = setup(); await h.open(h.a); const d = deferred(); h.confirm.mockImplementation(() => d.promise)
    const old = h.v.signEmr(); await h.open(null); h.clearCalls(); d.raw(true)
    await expect(old).resolves.toBeUndefined(); expect(writes(h)).toEqual([])
  })
  it('a late A signature response cannot reopen B or clear its draft', async () => {
    const h = setup(); await h.open(h.a); const d = h.hold('POST', '/outpatient/doctor/11/emr/sign'); const old = h.v.signEmr()
    await untilCalled(h, 'POST', '/outpatient/doctor/11/emr/sign'); await h.open(h.b); editedB(h); const before = state(h); h.clearCalls()
    d.resolve(null); await old
    expect(state(h)).toEqual(before); expect(h.calls).toEqual([]); expect(h.ElMessage.success).not.toHaveBeenCalled()
  })
  it('a live owned draft saves then signs only A using original response and refresh contracts', async () => {
    const h = setup(); await h.open(h.a); h.clearCalls(); await h.v.signEmr()
    expect(writes(h).map(x => x.url)).toEqual(['/outpatient/doctor/11/emr', '/outpatient/doctor/11/emr/sign'])
    expect(h.calls.some(x => x.url === wsPath(11))).toBe(true); expect(h.v.signing.value).toBe(false)
  })
  it('a different author is signed directly without overwriting authorship through presave', async () => {
    const h = setup(); h.spaces.set(11, workspace(h.a, { emr: { ...workspace(h.a).emr, doctorId: 9 } })); await h.open(h.a); h.clearCalls(); await h.v.signEmr()
    expect(writes(h).map(x => x.url)).toEqual(['/outpatient/doctor/11/emr/sign'])
  })
  it('cancelling a live signature confirmation performs no write', async () => {
    const h = setup(); await h.open(h.a); h.confirm.mockRejectedValue('cancel'); h.clearCalls(); await h.v.signEmr(); expect(writes(h)).toEqual([])
  })
  it.each(['rx', 'lab'])('a late A %s order response never clears B drafts, reloads B or shows A warnings', async kind => {
    const h = setup(); await h.open(h.a); h.v[kind === 'rx' ? 'rxLines' : 'labLines'].value = [{ itemId: 111, qty: 1 }]
    const d = h.hold('POST', '/outpatient/doctor/11/orders'); const old = h.v.submitOrders(kind)
    await h.open(h.b); editedB(h); const before = state(h); h.clearCalls()
    d.resolve([{ id: 999, itemName: '旧甲医嘱', execDeptName: '甲检验科', stockWarnAvailable: 0, cdssWarnings: ['甲禁忌'] }]); await old
    expect(state(h)).toEqual(before); expect(h.calls).toEqual([]); expect(h.ElMessage.success).not.toHaveBeenCalled()
  })
  it.each(['rx', 'lab'])('a live %s order retains original body, refresh and warnings', async kind => {
    const h = setup(); await h.open(h.a); h.v[kind === 'rx' ? 'rxLines' : 'labLines'].value = [{ itemId: 111, qty: 2 }]
    h.route('POST', '/outpatient/doctor/11/orders', () => answer([{ itemName: '模拟药', stockWarnAvailable: 1, cdssWarnings: ['同一警告', '同一警告'], execDeptName: '检验甲科' }]))
    h.clearCalls(); await h.v.submitOrders(kind)
    expect(writes(h)[0]).toMatchObject({ url: '/outpatient/doctor/11/orders', body: { lines: [{ itemId: 111, qty: 2 }] } })
    expect(h.v.stockWarnings.value).toEqual(['模拟药（余 1）']); expect(h.v.rxCdssWarnings.value).toEqual(['同一警告']); expect(h.v.cdssWarnDialog.value).toBe(true)
    expect(h.v.tab.value).toBe('rx'); expect(h.v.submitting.value).toBe(false)
    if (kind === 'lab') expect(h.ElMessage.success).toHaveBeenCalledWith('申请已提交；检验执行科室：检验甲科')
  })
  it('a late order finally cannot clear B independently pending submit loading', async () => {
    const h = setup(); await h.open(h.a); const a = h.hold('POST', '/outpatient/doctor/11/orders'); const old = h.v.submitOrders('rx')
    await h.open(h.b); const b = h.hold('POST', '/outpatient/doctor/22/orders'); const active = h.v.submitOrders('lab')
    a.resolve([]); await old; const busy = h.v.submitting.value; b.resolve([]); await active; expect(busy).toBe(true)
  })
})

it('a transport-to-caller microtask handoff cannot reopen A after the real B selection', async () => {
  const h = setup(); await h.open(h.a)
  const d = h.hold('POST', '/outpatient/doctor/11/orders'); h.clearCalls()
  const old = h.v.submitOrders('rx'); d.resolve([])
  let newer!: Promise<unknown>
  // Native promise jobs establish the precise handoff, without timers, helper
  // names or a mocked core handler. In the candidate wrapper implementation its
  // own validity check has finished but the awaiting caller has not resumed.
  await new Promise<void>(selected => {
    const schedule = (remaining: number) => queueMicrotask(() => {
      if (remaining) schedule(remaining - 1)
      else { newer = h.open(h.b); selected() }
    })
    schedule(2)
  })
  await Promise.all([old, newer])
  expect(h.v.current.value.registrationId).toBe(22)
  const opens = h.calls.filter(x => x.method === 'GET' && x.url.endsWith('/workspace')).map(x => x.url)
  const selectedB = opens.indexOf(wsPath(22)); expect(selectedB).toBeGreaterThanOrEqual(0)
  expect(opens.slice(selectedB)).toEqual([wsPath(22)])
})

it.each(['signEmr', 'submitOrders'])('%s preserves the current self-refresh unknown error as the same rejected object', async name => {
  const h = setup(); await h.open(h.a)
  const error = { bizCode: 5000, message: '本次刷新真实错误' }
  h.route('GET', wsPath(11), () => Promise.reject(error))
  await expect(h.v[name](...(name === 'submitOrders' ? ['rx'] : []))).rejects.toBe(error)
})

it.each(['startVisit', 'signEmr'])('%s write-triggered queue read cannot overwrite a newer B manual queue refresh', async name => {
  const h = setup(); await h.open(h.a); const d = deferred(); let reads = 0
  const latest = [{ registrationId: 22, patientId: 202, patientName: '模拟乙当前队列', status: 'VISITED' }]
  h.route('GET', '/outpatient/doctor/worklist', () => ++reads === 1 ? d.promise : answer(latest))
  const old = h.v[name]()
  await untilCalled(h, 'GET', '/outpatient/doctor/worklist')
  await h.open(h.b); await h.v.loadWorklist()
  const before = state(h); h.clearCalls(); d.resolve([{ registrationId: 11, patientName: '甲过时队列', status: 'REGISTERED' }]); await old
  expect(h.v.worklist.value).toEqual(latest)
  expect(state(h)).toEqual(before); expect(h.calls).toEqual([])
})
