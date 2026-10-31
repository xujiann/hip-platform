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

// Run the complete real SFC setup and real shared client. Only transport,
// browser/UI dependencies and lifecycle registration are controlled. These
// deterministic reads are neither mounted-browser nor server/JDBC evidence.
// The field reader retains its original workspace/selection policy; this
// suite does not promise a new authentication watcher or instant revocation.
const cleanups: Array<() => Promise<void>> = []
afterEach(async () => { for (const cleanup of cleanups.splice(0)) await cleanup() })
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
function withoutImports(source: string, filename: string) {
  const parsed = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  return parsed.statements.filter(statement => !ts.isImportDeclaration(statement)).map(statement => statement.getText(parsed)).join('\n')
}
const a = { registrationId: 11, patientId: 101, patientName: 'V151模拟甲', deptId: 7, status: 'VISITED' }
const b = { registrationId: 22, patientId: 202, patientName: 'V151模拟乙', deptId: 8, status: 'VISITED' }
const noFields = '该模板尚未配置结构化元素，本次按自由文本书写即可（元素定义须由管理员配置）'
const unreadable = '结构化元素定义暂不可读，本次仍可按自由文本书写病历'
const fields = (label: string, templateId = 71) => [{ id: templateId * 10, templateId, fieldCode: 'TEXT',
  label, datatype: 'TEXT', required: false, sortNo: 1, valueSet: [], placeholder: '模拟输入', unit: null, enabled: true }]
type Wire = { config: any; done: boolean; reply: (data: unknown, code?: number, status?: number) => void }
type Read = { result: Promise<void>; wire?: Wire }
type Outcome = { name: string; data: unknown; code: number; status: number }
const staleOutcomes: Outcome[] = [
  { name: 'old populated success', data: fields('首次A的旧字段'), code: 0, status: 200 },
  { name: 'old empty success', data: [], code: 0, status: 200 },
  { name: 'old parent denial 4066', data: null, code: 4066, status: 200 },
  { name: 'old role denial 403', data: null, code: 1005, status: 403 },
  { name: 'old server failure 503', data: null, code: 5000, status: 503 },
]
const currentOutcomes: Outcome[] = [
  { name: 'current populated success', data: fields('当前A的最新字段'), code: 0, status: 200 },
  { name: 'current empty success', data: [], code: 0, status: 200 },
  { name: 'current null success', data: null, code: 0, status: 200 },
  { name: 'current parent denial 4066', data: null, code: 4066, status: 200 },
  { name: 'current role denial 403', data: null, code: 1005, status: 403 },
  { name: 'current server failure 503', data: null, code: 5000, status: 503 },
]

async function setup(open = true) {
  const storage = new Map([['hip_token', 'v151-original-token']])
  const localStorage = { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) }
  const auth = Vue.reactive({ token: 'v151-original-token', user: { id: 3, roles: ['DOCTOR_OUTP'], menus: [] } })
  const ElMessage = Object.assign(vi.fn(), { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() })
  const ElMessageBox = { confirm: vi.fn(), prompt: vi.fn() }, router = { push: vi.fn() }
  const clientBox: Record<string, any> = { axios, localStorage, router, ElMessage, Blob, exports: {} }
  const clientSource = readFileSync(new URL('../../api/client.ts', import.meta.url), 'utf8')
  runInNewContext(ts.transpileModule(withoutImports(clientSource, 'client.ts'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, clientBox)
  const client = clientBox.exports.default, requests: Wire[] = [], results: Promise<void>[] = []
  client.defaults.adapter = (config: any) => new Promise((resolve, reject) => {
    expect(config.method).toBe('get')
    const wire: Wire = { config, done: false, reply(data, code = 0, status = 200) {
      expect(wire.done).toBe(false); wire.done = true
      const response = { config, status, statusText: String(status), headers: {},
        data: { code, message: code ? '字段定义读取失败' : 'success', data } }
      if (status >= 400) reject(new axios.AxiosError('Request failed with status code ' + status, 'ERR_BAD_REQUEST', config, undefined, response))
      else resolve(response)
    } }; requests.push(wire)
    if (/^\/emr\/templates\/\d+\/fields$/.test(config.url)) return
    const workspace = String(config.url).match(/^\/outpatient\/doctor\/(\d+)\/workspace$/)
    if (workspace) wire.reply({ emr: { id: Number(workspace[1]) * 10, doctorId: 3, signature: null,
      chiefComplaint: '模拟原主诉' + workspace[1], presentIllness: '模拟原现病史', pastHistory: '模拟既往史',
      physicalExam: '模拟查体', advice: '模拟处理' },
    diagnoses: [{ icdCode: 'D' + workspace[1], icdName: '模拟诊断' }],
    orders: [{ id: Number(workspace[1]) * 100, status: 'CREATED', itemName: '模拟原医嘱' }] })
    else if (config.url === '/outpatient/doctor/special-disease') wire.reply([])
    else if (config.url === '/outpatient/emr-ref') wire.reply({ prefill: {} })
    else if (/^\/outpatient\/doctor\/\d+\/emr\/amendments$/.test(config.url)) wire.reply([])
    else throw new Error('Unconfigured actual GET: ' + config.url)
  })
  const unmounts: Array<() => void> = []
  const source = readFileSync(new URL('../../views/outpatient/DoctorStationView.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const box: Record<string, any> = { ...Vue, ...dateUtils, ...templateUtils, ...defaultUtils, ...submitUtils,
    ...printUtils, ...rxUtils, applyTemplateToEmr: templateUtils.applyTemplate, client, ElMessage, ElMessageBox,
    useAuthStore: () => auth,
    useEmrPasteGuard: () => ({ mode: Vue.ref('warn'), loadPolicy: vi.fn(), onCopy: vi.fn(), onPaste: vi.fn() }),
    onMounted: vi.fn(), onUnmounted: (fn: () => void) => unmounts.push(fn), localStorage, window: { open: vi.fn() }, console }
  const scope = Vue.effectScope()
  scope.run(() => runInNewContext(ts.transpileModule(withoutImports(script, 'DoctorStationView.ts') + `
globalThis.view = { openPatient, current, emr, diagList, orders, emrSigned, currentEmrId, emrAuthorId,
  structTemplateId, structFields, structValues, structHint, loadStructFields, workspaceReady, workspaceLoading };`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box))
  const v = box.view as Record<string, any>
  cleanups.push(async () => {
    try {
      // Even an expected old-source assertion failure must close every real
      // deferred wire; cleanup responses are not used as assertion evidence.
      for (const wire of requests.filter(request => !request.done)) wire.reply([])
      await Promise.all(results)
      expect(requests.every(request => request.done)).toBe(true)
      expect(requests.every(request => request.config.method === 'get')).toBe(true)
    } finally { unmounts.forEach(fn => fn()); scope.stop() }
  })
  if (open) { await v.openPatient(copy(a)); requests.length = 0 }
  const begin = async (templateId: number | null = 71): Promise<Read> => {
    const offset = requests.length; v.structTemplateId.value = templateId
    const result = v.loadStructFields() as Promise<void>; results.push(result)
    if (!templateId || !v.workspaceReady.value || v.workspaceLoading.value) { await result; return { result } }
    await vi.waitFor(() => expect(requests.length).toBeGreaterThan(offset), { interval: 1, timeout: 1000 })
    const wire = requests.slice(offset).find(request => request.config.url === `/emr/templates/${templateId}/fields`)
    expect(wire).toBeDefined()
    return { result, wire: wire! }
  }
  return { v, requests, begin, storage, auth, ElMessage, ElMessageBox, router, unmount: () => unmounts.forEach(fn => fn()) }
}
type Harness = Awaited<ReturnType<typeof setup>>
const clinical = (h: Harness) => copy({ current: h.v.current.value, emr: h.v.emr, diagnoses: h.v.diagList.value,
  orders: h.v.orders.value, signed: h.v.emrSigned.value, emrId: h.v.currentEmrId.value, authorId: h.v.emrAuthorId.value })
function noClinicalWrites(h: Harness) {
  expect(h.requests.every(request => request.config.method === 'get')).toBe(true)
  expect(h.ElMessage.success).not.toHaveBeenCalled(); expect(h.ElMessageBox.confirm).not.toHaveBeenCalled()
}
async function answer(read: Read, outcome: Outcome) {
  expect(read.wire).toBeDefined(); read.wire!.reply(copy(outcome.data), outcome.code, outcome.status); await read.result
}
const selected = { name: 'populated', data: fields('当前A的最新字段'), code: 0, status: 200 }
const patterns = ['A-B-A', 'A-clear-A', 'same-A-again'] as const
async function cycle(h: Harness, pattern: typeof patterns[number]) {
  const old = await h.begin(71)
  if (pattern === 'A-B-A') await answer(await h.begin(72), { ...selected, data: fields('中间B字段', 72) })
  if (pattern === 'A-clear-A') await (await h.begin(null)).result
  const current = await h.begin(71)
  expect(old.wire).not.toBe(current.wire)
  return { old, current }
}

describe('v151 current field reads keep the original contract [compat]', () => {
  it.each(currentOutcomes)('$name retains exact GET/DTO/error guidance and free text', async outcome => {
    const h = await setup(), before = clinical(h)
    h.v.structFields.value = fields('旧已交付字段'); h.v.structValues.value = { TEXT: '旧结构化值' }; h.v.structHint.value = '旧提示'
    const read = await h.begin()
    expect(h.v.structFields.value).toEqual([]); expect(h.v.structValues.value).toEqual({}); expect(h.v.structHint.value).toBe('')
    expect(read.wire!.config).toMatchObject({ method: 'get', url: '/emr/templates/71/fields' })
    expect(read.wire!.config.params).toBeUndefined(); expect(read.wire!.config.headers.Authorization).toBe('Bearer v151-original-token')
    await answer(read, outcome)
    expect(h.v.structFields.value).toEqual(outcome.code ? [] : outcome.data ?? [])
    expect(h.v.structHint.value).toBe(outcome.code ? unreadable : Array.isArray(outcome.data) && outcome.data.length ? '' : noFields)
    expect(h.ElMessage.error).toHaveBeenCalledTimes(outcome.code ? 1 : 0)
    expect(clinical(h)).toEqual(before); expect(h.storage.get('hip_token')).toBe('v151-original-token'); noClinicalWrites(h)
    if (!outcome.code && Array.isArray(outcome.data) && outcome.data.length) {
      expect(Object.keys(h.v.structFields.value[0]).sort()).toEqual(['id', 'templateId', 'fieldCode', 'label', 'datatype',
        'required', 'sortNo', 'valueSet', 'placeholder', 'unit', 'enabled'].sort())
    }
  })
  it('clearing removes structured scratch only and sends no request', async () => {
    const h = await setup(), before = clinical(h)
    h.v.structFields.value = fields('已交付字段'); h.v.structValues.value = { TEXT: '本地结构值' }; h.v.structHint.value = '旧提示'
    await (await h.begin(null)).result
    expect(h.requests).toEqual([]); expect(h.v.structFields.value).toEqual([]); expect(h.v.structValues.value).toEqual({})
    expect(h.v.structHint.value).toBe(''); expect(clinical(h)).toEqual(before); noClinicalWrites(h)
  })
  it('a workspace that is not ready dispatches no field read', async () => {
    const h = await setup(false); await (await h.begin()).result
    expect(h.requests).toEqual([]); expect(h.v.structFields.value).toEqual([]); noClinicalWrites(h)
  })
  it('current completion preserves structured input typed after that read began', async () => {
    const h = await setup(), before = clinical(h), read = await h.begin()
    h.v.structValues.value = { TEXT: '当前读取期间输入' }; await answer(read, selected)
    expect(h.v.structFields.value).toEqual(selected.data); expect(h.v.structValues.value).toEqual({ TEXT: '当前读取期间输入' })
    expect(clinical(h)).toEqual(before); noClinicalWrites(h)
  })
  it('current 401 retains draft and follows the unchanged shared-client identity-expiry path', async () => {
    const h = await setup(), before = clinical(h), read = await h.begin()
    await answer(read, { name: 'expired login', data: null, code: 1000, status: 401 })
    expect(h.storage.has('hip_token')).toBe(false); expect(h.router.push).toHaveBeenCalledWith('/login')
    expect(h.ElMessage.warning).toHaveBeenCalledTimes(1); expect(h.v.structHint.value).toBe(unreadable)
    expect(clinical(h)).toEqual(before); noClinicalWrites(h)
  })
})

for (const pattern of patterns) {
  describe(`v151 ${pattern} latest read owns field state [business RED]`, () => {
    it.each(staleOutcomes)('$name delivered after latest success cannot replace current fields, hint or input', async outcome => {
      const h = await setup(), before = clinical(h), reads = await cycle(h, pattern)
      await answer(reads.current, selected); h.v.structValues.value = { TEXT: '最新A本地草稿' }
      await answer(reads.old, outcome)
      expect(h.v.structTemplateId.value).toBe(71); expect(h.v.structFields.value).toEqual(selected.data)
      expect(h.v.structHint.value).toBe(''); expect(h.v.structValues.value).toEqual({ TEXT: '最新A本地草稿' })
      expect(clinical(h)).toEqual(before); expect(h.ElMessage.error).toHaveBeenCalledTimes(outcome.code ? 1 : 0); noClinicalWrites(h)
    })
    it.each(staleOutcomes)('$name delivered while latest read is pending cannot fill fields or a hint early', async outcome => {
      const h = await setup(), before = clinical(h), reads = await cycle(h, pattern)
      h.v.structValues.value = { TEXT: '最新A请求期间草稿' }
      await answer(reads.old, outcome)
      const whilePending = copy({ fields: h.v.structFields.value, hint: h.v.structHint.value, values: h.v.structValues.value })
      await answer(reads.current, selected)
      expect(whilePending).toEqual({ fields: [], hint: '', values: { TEXT: '最新A请求期间草稿' } })
      expect(h.v.structFields.value).toEqual(selected.data); expect(h.v.structHint.value).toBe('')
      expect(h.v.structValues.value).toEqual({ TEXT: '最新A请求期间草稿' }); expect(clinical(h)).toEqual(before); noClinicalWrites(h)
    })
    it.each(currentOutcomes.slice(1))('old populated success cannot undo $name', async outcome => {
      const h = await setup(), before = clinical(h), reads = await cycle(h, pattern)
      await answer(reads.current, outcome); const hint = h.v.structHint.value
      await answer(reads.old, staleOutcomes[0])
      expect(h.v.structFields.value).toEqual([]); expect(h.v.structHint.value).toBe(hint)
      expect(h.v.structValues.value).toEqual({}); expect(clinical(h)).toEqual(before); noClinicalWrites(h)
    })
  })
}

describe('v151 existing workspace and error boundaries [compat]', () => {
  for (const change of ['different-patient', 'reopen-same-visit', 'unmount', 'different-template', 'clear-only'] as const) {
    it.each(staleOutcomes)(`${change}: $name cannot repopulate the invalidated local field state`, async outcome => {
      const h = await setup(), old = await h.begin()
      if (change === 'different-patient') await h.v.openPatient(copy(b))
      if (change === 'reopen-same-visit') await h.v.openPatient(copy(a))
      if (change === 'unmount') h.unmount()
      if (change === 'different-template') await answer(await h.begin(72), { ...selected, data: fields('当前B字段', 72) })
      if (change === 'clear-only') await (await h.begin(null)).result
      const before = clinical(h), state = copy({ fields: h.v.structFields.value, hint: h.v.structHint.value, values: h.v.structValues.value })
      await answer(old, outcome)
      expect({ fields: h.v.structFields.value, hint: h.v.structHint.value, values: h.v.structValues.value }).toEqual(state)
      expect(clinical(h)).toEqual(before); expect(h.ElMessage.error).toHaveBeenCalledTimes(outcome.code ? 1 : 0); noClinicalWrites(h)
    })
  }
  it.each(staleOutcomes.filter(outcome => outcome.code))('$name keeps the original shared toast even when its local field result is stale', async outcome => {
    const h = await setup(), reads = await cycle(h, 'A-B-A'); await answer(reads.current, selected)
    await answer(reads.old, outcome)
    expect(h.ElMessage.error).toHaveBeenCalledTimes(1); expect(h.router.push).not.toHaveBeenCalled()
    expect(h.storage.get('hip_token')).toBe('v151-original-token'); noClinicalWrites(h)
  })
  it('latest field read may recover normally after a known current denial', async () => {
    const h = await setup(), before = clinical(h)
    await answer(await h.begin(), currentOutcomes[3]); await (await h.begin(null)).result
    await answer(await h.begin(), selected)
    expect(h.v.structFields.value).toEqual(selected.data); expect(h.v.structHint.value).toBe('')
    expect(clinical(h)).toEqual(before); noClinicalWrites(h)
  })
})
