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

// Original complete setup + actual shared client. Only transport, UI and
// lifecycle registration are controlled; this is not mounted browser evidence.
const cleanups: Array<() => Promise<void>> = []
afterEach(async () => { for (const cleanup of cleanups.splice(0)) await cleanup() })
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
function script(source: string, filename: string) {
  const ast = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  return ast.statements.filter(s => !ts.isImportDeclaration(s)).map(s => s.getText(ast)).join('\n')
}
const a = { registrationId: 11, patientId: 101, patientName: 'V152模拟甲', status: 'VISITED', deptId: 7 }
const b = { registrationId: 22, patientId: 202, patientName: 'V152模拟乙', status: 'VISITED', deptId: 8 }
const row = { source: 'INP', record_id: 211, record_type: 'PROGRESS', title: '模拟病程', created_at: '2026-11-01T00:00:00Z',
  visit_no: 'V152-1', dept_name: '模拟科室', doctor_name: '模拟医师', content_length: 8 }
const content = { source: 'INP', recordId: 211, recordType: 'PROGRESS', title: '模拟病程', content: '模拟既往正文', createdAt: row.created_at }
const saved = { id: 71, scope: 'PERSONAL', recordType: 'OUTP', contentLength: 8, sourceLength: 8, truncated: false, maxContent: 4000 }
type Kind = 'list' | 'content' | 'copy'
type Wire = { config: any; done: boolean; reply: (data: unknown, code?: number, status?: number) => void }
const urls = { list: '/emr-templates/prior-records', content: '/emr-templates/prior-records/content', copy: '/emr-templates/from-record' }

async function setup(open = true) {
  const storage = new Map([['hip_token', 'v152-token']])
  const localStorage = { getItem: (key: string) => storage.get(key) ?? null, removeItem: (key: string) => storage.delete(key),
    setItem: (key: string, value: string) => storage.set(key, value) }
  const router = { push: vi.fn() }, ElMessage = Object.assign(vi.fn(), { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() })
  const ElMessageBox = { confirm: vi.fn(), prompt: vi.fn().mockResolvedValue({ value: '  V152个人模板  ' }) }
  const clientBox: Record<string, any> = { axios, localStorage, router, ElMessage, Blob, exports: {} }
  const clientSource = readFileSync(new URL('../../api/client.ts', import.meta.url), 'utf8')
  runInNewContext(ts.transpileModule(script(clientSource, 'client.ts'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, clientBox)
  const client = clientBox.exports.default, wires: Wire[] = [], results: Promise<unknown>[] = []
  client.defaults.adapter = (config: any) => new Promise((resolve, reject) => {
    const wire: Wire = { config, done: false, reply(data, code = 0, status = 200) {
      expect(wire.done).toBe(false); wire.done = true
      const response = { config, status, statusText: String(status), headers: {},
        data: { code, message: code === 4036 ? '无权查阅该患者的既往病历' : code ? '来源读取失败' : 'success', data } }
      if (status >= 400) reject(new axios.AxiosError('HTTP ' + status, 'ERR_BAD_REQUEST', config, undefined, response))
      else resolve(response)
    } }; wires.push(wire)
    if (Object.values(urls).includes(config.url)) return
    const workspace = String(config.url).match(/^\/outpatient\/doctor\/(\d+)\/workspace$/)
    if (workspace) wire.reply({ emr: { id: Number(workspace[1]) * 10, doctorId: 3, signature: null,
      chiefComplaint: '模拟主诉' + workspace[1], presentIllness: '模拟现病史', pastHistory: '模拟既往史', physicalExam: '模拟查体', advice: '模拟处理' },
    diagnoses: [{ icdCode: 'D1', icdName: '模拟诊断' }], orders: [{ id: 19, status: 'CREATED', itemName: '模拟医嘱' }] })
    else if (config.url === '/outpatient/doctor/special-disease') wire.reply([])
    else if (config.url === '/outpatient/emr-ref') wire.reply({ prefill: {} })
    else throw new Error('Unconfigured real request ' + config.method + ' ' + config.url)
  })
  const unmounts: Array<() => void> = []
  const sfc = readFileSync(new URL('../../views/outpatient/DoctorStationView.vue', import.meta.url), 'utf8')
  const body = sfc.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const box: Record<string, any> = { ...Vue, ...dateUtils, ...templateUtils, ...defaultUtils, ...submitUtils, ...printUtils, ...rxUtils,
    applyTemplateToEmr: templateUtils.applyTemplate, client, ElMessage, ElMessageBox, localStorage, window: { open: vi.fn() }, console,
    useAuthStore: () => ({ user: { id: 3, roles: ['DOCTOR_OUTP'], menus: [] } }),
    useEmrPasteGuard: () => ({ mode: Vue.ref('warn'), loadPolicy: vi.fn(), onCopy: vi.fn(), onPaste: vi.fn() }),
    onMounted: vi.fn(), onUnmounted: (fn: () => void) => unmounts.push(fn) }
  const scope = Vue.effectScope()
  scope.run(() => runInNewContext(ts.transpileModule(script(body, 'DoctorStation.ts') + `
globalThis.view = { openPatient, current, emr, diagList, orders, emrSigned, currentEmrId, emrAuthorId,
  structFields, structValues, priorRecords, priorVisible, priorLoading, savingTpl, insertTarget,
  openPriorRecords, usePriorRecord, saveAsTemplate };`, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box))
  const v = box.view as Record<string, any>
  cleanups.push(async () => {
    try {
      for (const wire of wires.filter(w => !w.done)) wire.reply(null)
      await Promise.allSettled(results)
      expect(wires.every(w => w.done)).toBe(true)
    } finally { unmounts.forEach(fn => fn()); scope.stop() }
  })
  if (open) { await v.openPatient(copy(a)); wires.length = 0 }
  const invoke = (kind: Kind) => {
    const promise = (kind === 'list' ? v.openPriorRecords() : kind === 'content' ? v.usePriorRecord(copy(row)) : v.saveAsTemplate()) as Promise<void>
    results.push(promise); void promise.catch(() => undefined); return promise
  }
  async function begin(kind: Kind) {
    const offset = wires.length, result = invoke(kind)
    await vi.waitFor(() => expect(wires.slice(offset).some(w => w.config.url === urls[kind])).toBe(true), { interval: 1 })
    return { result, wire: wires.slice(offset).find(w => w.config.url === urls[kind])! }
  }
  return { v, wires, begin, invoke, storage, router, ElMessage, ElMessageBox, unmount: () => unmounts.forEach(fn => fn()) }
}
type Harness = Awaited<ReturnType<typeof setup>>
const clinical = (h: Harness) => copy({ current: h.v.current.value, emr: h.v.emr, diagnoses: h.v.diagList.value, orders: h.v.orders.value,
  signed: h.v.emrSigned.value, emrId: h.v.currentEmrId.value, author: h.v.emrAuthorId.value,
  fields: h.v.structFields.value, values: h.v.structValues.value })
function noClinicalWrite(h: Harness) {
  expect(h.wires.filter(w => w.config.method !== 'get').every(w => w.config.method === 'post' && w.config.url === urls.copy)).toBe(true)
}

describe('v152 original complete DoctorStation history permission consumer', () => {
  for (const kind of ['list', 'content', 'copy'] as const) {
    for (const [name, code, status] of [['object4036', 4036, 200], ['source4068', 4068, 200], ['role403', 1005, 403], ['server503', 5000, 503]] as const) {
      it(`${kind} ${name} preserves draft/token/cache, rejects and releases only its pending action`, async () => {
        const h = await setup(); h.v.priorRecords.value = [copy(row)]; h.v.priorVisible.value = true
        h.v.structValues.value = { f: '未存结构化值' }; const before = clinical(h), read = await h.begin(kind)
        expect(kind === 'list' ? h.v.priorLoading.value : kind === 'copy' ? h.v.savingTpl.value : false).toBe(kind !== 'content')
        read.wire.reply(null, code, status); await expect(read.result).rejects.toBeDefined()
        expect(clinical(h)).toEqual(before); expect(h.v.priorRecords.value).toEqual([row]); expect(h.v.priorVisible.value).toBe(true)
        expect(h.v.priorLoading.value).toBe(false); expect(h.v.savingTpl.value).toBe(false)
        expect(h.storage.get('hip_token')).toBe('v152-token'); expect(h.router.push).not.toHaveBeenCalled()
        expect(h.ElMessage.error).toHaveBeenCalledTimes(1); expect(h.ElMessage.success).not.toHaveBeenCalled(); noClinicalWrite(h)
      })
    }
    it(`${kind} original current401 keeps local data but follows shared identity-expiry`, async () => {
      const h = await setup(), before = clinical(h), read = await h.begin(kind)
      read.wire.reply(null, 1000, 401); await expect(read.result).rejects.toBeDefined()
      expect(clinical(h)).toEqual(before); expect(h.storage.has('hip_token')).toBe(false)
      expect(h.router.push).toHaveBeenCalledWith('/login'); expect(h.ElMessage.warning).toHaveBeenCalledTimes(1)
      expect(h.ElMessage.success).not.toHaveBeenCalled(); noClinicalWrite(h)
    })
    for (const change of ['patient', 'reopen', 'unmount'] as const) {
      it(`${kind} old4036 after ${change} retains the existing workspace invalidation`, async () => {
        const h = await setup(), read = await h.begin(kind)
        if (change === 'unmount') h.unmount()
        else await h.v.openPatient(copy(change === 'patient' ? b : a))
        const before = clinical(h); read.wire.reply(null, 4036); await read.result
        expect(clinical(h)).toEqual(before); expect(h.v.priorLoading.value).toBe(false); expect(h.v.savingTpl.value).toBe(false)
        expect(h.ElMessage.error).toHaveBeenCalledTimes(1); expect(h.ElMessage.success).not.toHaveBeenCalled(); noClinicalWrite(h)
      })
    }
  }
  it('list consumes original nine snake_case keys without requesting body or changing clinical state', async () => {
    const h = await setup(), before = clinical(h), read = await h.begin('list')
    expect(read.wire.config).toMatchObject({ method: 'get', url: urls.list, params: { patientId: 101 } })
    read.wire.reply([copy(row)]); await read.result
    expect(h.v.priorRecords.value).toEqual([row]); expect(Object.keys(h.v.priorRecords.value[0])).toHaveLength(9)
    expect(h.v.priorVisible.value).toBe(true); expect(clinical(h)).toEqual(before); noClinicalWrite(h)
  })
  it.each([[], null])('authorized empty list %j clears only prior rows using its original empty presentation', async payload => {
    const h = await setup(); h.v.priorRecords.value = [copy(row)]; const before = clinical(h), read = await h.begin('list')
    read.wire.reply(payload); await read.result
    expect(h.v.priorRecords.value).toEqual([]); expect(h.v.priorVisible.value).toBe(true); expect(clinical(h)).toEqual(before)
    expect(h.ElMessage.error).not.toHaveBeenCalled(); noClinicalWrite(h)
  })
  it('content consumes six camelCase keys and appends only the selected section without saving', async () => {
    const h = await setup(); h.v.priorVisible.value = true; h.v.insertTarget.value = 'pastHistory'
    const before = clinical(h), read = await h.begin('content')
    expect(read.wire.config.params).toEqual({ patientId: 101, source: 'INP', recordId: 211 })
    expect(Object.keys(content)).toHaveLength(6); read.wire.reply(copy(content)); await read.result
    expect(h.v.emr).toEqual({ ...before.emr, pastHistory: before.emr.pastHistory + '\n' + content.content })
    expect(h.v.priorVisible.value).toBe(false); expect(h.ElMessage.success).toHaveBeenCalledTimes(1); noClinicalWrite(h)
  })
  it.each(['', '   ', null])('empty original source body %j warns without inserting', async body => {
    const h = await setup(); h.v.priorVisible.value = true; const before = clinical(h), read = await h.begin('content')
    read.wire.reply({ ...content, content: body }); await read.result
    expect(clinical(h)).toEqual(before); expect(h.ElMessage.warning).toHaveBeenCalledWith('这份既往病历没有正文可取')
    expect(h.v.priorVisible.value).toBe(true); expect(h.ElMessage.success).not.toHaveBeenCalled(); noClinicalWrite(h)
  })
  it('signed current EMR keeps original insertText refusal despite a readable historical source', async () => {
    const h = await setup(); h.v.emrSigned.value = true; const before = clinical(h), read = await h.begin('content')
    read.wire.reply(copy(content)); await read.result
    expect(clinical(h)).toEqual(before); expect(h.ElMessage.warning).toHaveBeenCalled(); expect(h.ElMessage.success).not.toHaveBeenCalled(); noClinicalWrite(h)
  })
  it.each([false, true])('copy keeps the original five explicit UI payload keys and consumes seven result keys (truncated=%s)', async truncated => {
    const h = await setup(), before = clinical(h), read = await h.begin('copy')
    expect(JSON.parse(read.wire.config.data)).toEqual({ source: 'OUTP', recordId: 110, patientId: 101, name: 'V152个人模板', scope: 'PERSONAL' })
    // Java FromRecordReq has seven components; the two optional deptId and
    // recordType remain omitted by this real UI, not silently added to its wire.
    const reply = { ...saved, truncated, sourceLength: truncated ? 4200 : 8, contentLength: truncated ? 4000 : 8 }
    expect(Object.keys(reply)).toHaveLength(7); read.wire.reply(reply); await read.result
    expect(clinical(h)).toEqual(before); expect(h.v.savingTpl.value).toBe(false)
    expect(h.ElMessage.success.mock.calls[0][0]).toContain(truncated ? '已截断保存前 4000 字' : '已存为个人模板')
    expect(h.wires).toHaveLength(1); noClinicalWrite(h)
  })
  it('cancelled name prompt performs no POST', async () => {
    const h = await setup(), before = clinical(h); h.ElMessageBox.prompt.mockRejectedValue(new Error('cancel'))
    await h.invoke('copy'); expect(h.wires).toEqual([]); expect(clinical(h)).toEqual(before); expect(h.v.savingTpl.value).toBe(false)
  })
  it('copy without an existing saved EMR does not prompt or POST', async () => {
    const h = await setup(); h.v.currentEmrId.value = null; await h.invoke('copy')
    expect(h.ElMessageBox.prompt).not.toHaveBeenCalled(); expect(h.wires).toEqual([])
  })
  it('new workspace during prompt cannot combine old patient and new record identifiers', async () => {
    const h = await setup(); let finish!: (value: unknown) => void
    h.ElMessageBox.prompt.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const result = h.invoke('copy'); await h.v.openPatient(copy(b)); const before = clinical(h), count = h.wires.length
    finish({ value: '旧模板名' }); await result
    expect(h.wires).toHaveLength(count); expect(clinical(h)).toEqual(before); expect(h.ElMessage.success).not.toHaveBeenCalled()
  })
  it('duplicate live list/copy actions use the existing local pending-action guard', async () => {
    const h = await setup(), list = await h.begin('list'); await h.invoke('list')
    expect(h.wires.filter(w => w.config.url === urls.list)).toHaveLength(1); list.wire.reply([]); await list.result
    const writing = await h.begin('copy'); await h.invoke('copy')
    expect(h.wires.filter(w => w.config.url === urls.copy)).toHaveLength(1); writing.wire.reply(saved); await writing.result
    noClinicalWrite(h)
  })
})
