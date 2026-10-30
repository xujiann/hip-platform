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

// Positive compatibility: the complete original DoctorStationView setup and
// shared client/Axios execute. This VM is not a mounted table or a browser, and
// transport ordering is not the server's JDBC snapshot regression proof.
const scopes: ReturnType<typeof Vue.effectScope>[] = []
const checks: Array<() => void> = []
afterEach(() => {
  try { for (const check of checks.splice(0)) check() }
  finally { for (const scope of scopes.splice(0)) scope.stop() }
})
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const blank = () => ({ chiefComplaint: '', presentIllness: '', pastHistory: '', physicalExam: '', advice: '' })
const content = '主诉：新合法主诉\n现病史：新合法现病史\n既往史：新合法既往史\n体格检查：新合法查体\n处理意见：新合法处理'
const expected = { chiefComplaint: '新合法主诉', presentIllness: '新合法现病史', pastHistory: '新合法既往史', physicalExam: '新合法查体', advice: '新合法处理' }
const candidate = { id: 71, name: 'V147模拟默认', content, template_type: 'EMR', scope: 'DEPT',
  dept_id: 7, owner_id: 9, record_type: 'OUTP', enabled: true, is_default: true,
  created_by: 9, created_at: '2026-10-30T00:00:00.000+00:00' }
type Visit = { registrationId: number; patientId: number; patientName: string; deptId?: number; status: string }
const a: Visit = { registrationId: 11, patientId: 101, patientName: 'V147模拟甲', deptId: 7, status: 'VISITED' }
const b: Visit = { registrationId: 22, patientId: 202, patientName: 'V147模拟乙', deptId: 8, status: 'VISITED' }
type Wire = { config: any; done: boolean; reply: (data?: unknown, code?: number, status?: number) => void }
function withoutImports(source: string, name: string) {
  const parsed = ts.createSourceFile(name, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  return parsed.statements.filter(s => !ts.isImportDeclaration(s)).map(s => s.getText(parsed)).join('\n')
}
async function setup() {
  const storage = new Map([['hip_token', 'v147-original-token']])
  const localStorage = { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) }
  const router = { push: vi.fn() }
  const ElMessage = Object.assign(vi.fn(), { error: vi.fn(), success: vi.fn(), warning: vi.fn(), info: vi.fn() })
  const ElMessageBox = { confirm: vi.fn(() => Promise.resolve(true)), prompt: vi.fn(() => Promise.resolve({ value: '模拟模板' })) }
  const clientBox: Record<string, any> = { axios, ElMessage, router, localStorage, Blob, exports: {} }
  const clientSource = readFileSync(new URL('../../api/client.ts', import.meta.url), 'utf8')
  runInNewContext(ts.transpileModule(withoutImports(clientSource, 'client.ts'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, clientBox)
  const client = clientBox.exports.default, requests: Wire[] = [], unmounts: Array<() => void> = []
  const spaces = new Map<number, any>([a, b].map(row => [row.registrationId,
    { emr: null, diagnoses: [{ icdCode: 'D' + row.registrationId, icdName: row.patientName + '原诊断', primaryDiag: true }], orders: [] }]))
  const basics = new Map<number, any>()
  client.defaults.adapter = (config: any) => new Promise((resolve, reject) => {
    const wire: Wire = { config, done: false, reply(data = null, code = 0, status = 200) {
      expect(wire.done).toBe(false); wire.done = true
      const response = { data: { code, message: code ? '默认模板读取被拒' : 'success', data },
        status, statusText: String(status), config, headers: {} }
      if (status >= 400) reject(new axios.AxiosError('Request failed with status code ' + status,
        'ERR_BAD_REQUEST', config, undefined, response))
      else resolve(response)
    } }
    requests.push(wire)
    expect(config.method).toBe('get')
    if (config.url === '/emr-templates/default' || config.url === '/emr-templates/71') return
    const workspace = config.url.match(/^\/outpatient\/doctor\/(\d+)\/workspace$/)
    if (workspace) wire.reply(copy(spaces.get(Number(workspace[1]))))
    else if (config.url === '/outpatient/doctor/special-disease') wire.reply([])
    else if (/^\/outpatient\/doctor\/\d+\/emr\/amendments$/.test(config.url)) wire.reply([])
    else if (config.url === '/outpatient/emr-ref') wire.reply({ prefill: basics.get(config.params.registrationId) ?? {} })
    else throw new Error('Unconfigured original request ' + config.url)
  })
  const component = readFileSync(new URL('../../views/outpatient/DoctorStationView.vue', import.meta.url), 'utf8')
  const original = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const box: Record<string, any> = { ...Vue, ...dateUtils, ...templateUtils, ...defaultUtils, ...submitUtils,
    ...printUtils, ...rxUtils, applyTemplateToEmr: templateUtils.applyTemplate, client, ElMessage, ElMessageBox,
    useAuthStore: () => ({ user: { id: 3, deptId: 8, roles: ['DOCTOR_OUTP'], menus: [] } }),
    useEmrPasteGuard: () => ({ mode: Vue.ref('warn'), loadPolicy: vi.fn(), onCopy: vi.fn(), onPaste: vi.fn() }),
    onMounted: vi.fn(), onUnmounted: (fn: () => void) => unmounts.push(fn), window: { open: vi.fn() }, console }
  const scope = Vue.effectScope(); scopes.push(scope)
  scope.run(() => runInNewContext(ts.transpileModule(withoutImports(original, 'DoctorStationView.ts') + `
globalThis.view = { openPatient, undoAutoTemplate, emr, autoTpl, current, currentEmrId, emrAuthorId,
  emrSigned, diagList, orders, workspaceReady, workspaceLoading, basicBrought, prefilledNote };`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box))
  const v = box.view as Record<string, any>
  const begin = (row: Visit = a) => v.openPatient(copy(row)) as Promise<unknown>
  const wait = async (url: string, index = 0) => {
    await vi.waitFor(() => expect(requests.filter(w => w.config.url === url).length).toBeGreaterThan(index))
    return requests.filter(w => w.config.url === url)[index]!
  }
  checks.push(() => expect(requests.filter(w => !w.done)).toEqual([]))
  return { v, begin, wait, requests, spaces, basics, storage, router, ElMessage, ElMessageBox,
    unmount: () => unmounts.forEach(fn => fn()) }
}
type Harness = Awaited<ReturnType<typeof setup>>
const defaultRequests = (h: Harness) => h.requests.filter(w => w.config.url === '/emr-templates/default')
function stable(h: Harness) {
  expect(h.v.workspaceReady.value).toBe(true); expect(h.v.workspaceLoading.value).toBe(false)
  expect(h.storage.get('hip_token')).toBe('v147-original-token'); expect(h.router.push).not.toHaveBeenCalled()
  expect(h.requests.every(w => w.config.method === 'get')).toBe(true)
  expect(h.v.currentEmrId.value).toBeNull(); expect(h.v.emrSigned.value).toBe(false)
  expect(h.v.diagList.value[0].icdCode).toBe('D' + h.v.current.value.registrationId)
}
function untouched(h: Harness) {
  expect(copy(h.v.emr)).toEqual(blank()); expect(h.v.autoTpl.value).toBeNull()
  expect(h.ElMessage.success).not.toHaveBeenCalled()
}

describe('v147 original default-template consumer compatibility', () => {
  it('reads the original registration department, consumes a complete row and fills only the five local sections', async () => {
    const h = await setup(), pending = h.begin(), wire = await h.wait('/emr-templates/default')
    expect(wire.config).toMatchObject({ method: 'get', params: { deptId: 7, recordType: 'OUTP' }, __silentCodes: [4066, 4067] })
    expect(wire.config.headers.Authorization).toBe('Bearer v147-original-token')
    wire.reply(copy(candidate)); await pending; stable(h)
    expect(copy(h.v.emr)).toEqual(expected); expect(h.v.autoTpl.value.name).toBe(candidate.name)
    expect(h.v.autoTpl.value.before).toEqual(blank()); expect(h.v.autoTpl.value.after).toEqual(expected)
    expect(h.requests.some(w => w.config.url === '/emr-templates/71')).toBe(false)
  })
  it('no enabled default is a successful null and does not inject a template', async () => {
    const h = await setup(), pending = h.begin(); (await h.wait('/emr-templates/default')).reply(null)
    await pending; stable(h); untouched(h); expect(h.ElMessage.error).not.toHaveBeenCalled()
  })
  for (const code of [4066, 4067]) it(`known ${code} rejects in the real client and is silently consumed as no default`, async () => {
    const h = await setup(), pending = h.begin(); (await h.wait('/emr-templates/default')).reply(null, code)
    await pending; stable(h); untouched(h)
    expect(defaultRequests(h)).toHaveLength(1); expect(h.ElMessage.error).not.toHaveBeenCalled()
    expect(h.ElMessage.warning).not.toHaveBeenCalled()
  })
  for (const status of [403, 503]) it(`original HTTP${status} displays its original client error and does not clear login or inject a template`, async () => {
    const h = await setup(), pending = h.begin(); (await h.wait('/emr-templates/default')).reply(null, status === 403 ? 1005 : 5000, status)
    await pending; stable(h); untouched(h); expect(h.ElMessage.error).toHaveBeenCalledTimes(1)
    expect(defaultRequests(h)).toHaveLength(1)
  })
  it('original HTTP401 expires the original login without applying an unreturned default', async () => {
    const h = await setup(), pending = h.begin(); (await h.wait('/emr-templates/default')).reply(null, 1000, 401)
    await pending; untouched(h); expect(h.storage.has('hip_token')).toBe(false)
    expect(h.router.push).toHaveBeenCalledWith('/login'); expect(h.ElMessage.warning).toHaveBeenCalledWith('登录已过期，请重新登录')
  })
  for (const change of [{ enabled: false }, { template_type: 'RIS' }, { content: null }, { content: '' }]) {
    it(`original unusable default ${JSON.stringify(change)} stays local no-op without detail fallback`, async () => {
      const h = await setup(), pending = h.begin(); (await h.wait('/emr-templates/default')).reply({ ...copy(candidate), ...change })
      await pending; stable(h); untouched(h); expect(h.requests.some(w => w.config.url === '/emr-templates/71')).toBe(false)
    })
  }
  it('an original sparse row still uses its real detail fallback before applying', async () => {
    const h = await setup(), pending = h.begin(), sparse: Record<string, unknown> = copy(candidate); delete sparse.content
    ;(await h.wait('/emr-templates/default')).reply(sparse)
    const detail = await h.wait('/emr-templates/71'); expect(detail.config.__silentCodes).toEqual([4066])
    detail.reply({ ...copy(candidate), scopeName: '科室' }); await pending; stable(h)
    expect(copy(h.v.emr)).toEqual(expected); expect(defaultRequests(h)).toHaveLength(1)
  })
  it('a sparse-row detail4066 is silent and never applies its cached name as content', async () => {
    const h = await setup(), pending = h.begin(), sparse: Record<string, unknown> = copy(candidate); delete sparse.content
    ;(await h.wait('/emr-templates/default')).reply(sparse); (await h.wait('/emr-templates/71')).reply(null, 4066)
    await pending; stable(h); untouched(h); expect(h.ElMessage.error).not.toHaveBeenCalled()
  })
  it('a fresh explicit open after a known refusal reads again and applies the restored current row', async () => {
    const h = await setup(), first = h.begin(); (await h.wait('/emr-templates/default')).reply(null, 4066); await first; untouched(h)
    const second = h.begin(); (await h.wait('/emr-templates/default', 1)).reply(copy(candidate)); await second
    stable(h); expect(copy(h.v.emr)).toEqual(expected); expect(defaultRequests(h)).toHaveLength(2)
  })
  for (const code of [0, 4066]) it(`an old A default${code} finishing after B opened cannot mutate or release B`, async () => {
    const h = await setup(), first = h.begin(), old = await h.wait('/emr-templates/default')
    const second = h.begin(b), current = await h.wait('/emr-templates/default', 1)
    expect(h.v.workspaceLoading.value).toBe(true); old.reply(code ? null : copy(candidate), code); await first
    expect(h.v.workspaceLoading.value).toBe(true); expect(h.v.workspaceReady.value).toBe(false); untouched(h)
    current.reply(null); await second; stable(h); untouched(h); expect(h.v.current.value.registrationId).toBe(22)
  })
  it('an old detail fallback finishing after B cannot inject A into the new workspace', async () => {
    const h = await setup(), first = h.begin(), sparse: Record<string, unknown> = copy(candidate); delete sparse.content
    ;(await h.wait('/emr-templates/default')).reply(sparse); const detail = await h.wait('/emr-templates/71')
    const second = h.begin(b); (await h.wait('/emr-templates/default', 1)).reply(null); await second
    detail.reply(copy(candidate)); await first; stable(h); untouched(h); expect(h.v.current.value.registrationId).toBe(22)
  })
  it('unmount invalidates the original pending default continuation', async () => {
    const h = await setup(), pending = h.begin(), wire = await h.wait('/emr-templates/default')
    h.unmount(); wire.reply(copy(candidate)); await pending; untouched(h)
    expect(h.requests.every(w => w.config.method === 'get')).toBe(true)
  })
  it('an existing unsigned clinical record never fetches a default or overwrites its draft', async () => {
    const h = await setup(); h.spaces.set(11, { emr: { id: 110, doctorId: 3, ...expected, signature: null }, diagnoses: [], orders: [] })
    await h.begin(); expect(defaultRequests(h)).toEqual([]); expect(copy(h.v.emr)).toEqual(expected); expect(h.v.autoTpl.value).toBeNull()
  })
  it('a signed clinical record stays frozen and never fetches a default', async () => {
    const h = await setup(); h.spaces.set(11, { emr: { id: 110, doctorId: 3, ...expected, signature: 'original-signature' }, diagnoses: [], orders: [] })
    await h.begin(); expect(defaultRequests(h)).toEqual([]); expect(h.v.emrSigned.value).toBe(true); expect(copy(h.v.emr)).toEqual(expected)
  })
  it('the original allergy prefill is preserved when an authorized default fills the other sections', async () => {
    const h = await setup(); h.basics.set(11, { allergyHistory: '模拟过敏史' })
    const pending = h.begin(); (await h.wait('/emr-templates/default')).reply(copy(candidate)); await pending; stable(h)
    expect(copy(h.v.emr)).toEqual({ ...expected, pastHistory: '过敏史：模拟过敏史\n新合法既往史' })
    expect(h.v.autoTpl.value.before.pastHistory).toBe('过敏史：模拟过敏史')
  })
  it('explicit undo restores the prior local draft and suppresses automatic reapplication for that visit', async () => {
    const h = await setup(), pending = h.begin(); (await h.wait('/emr-templates/default')).reply(copy(candidate)); await pending
    await h.v.undoAutoTemplate(); expect(copy(h.v.emr)).toEqual(blank()); expect(h.v.autoTpl.value).toBeNull()
    await h.begin(); stable(h); expect(copy(h.v.emr)).toEqual(blank()); expect(h.v.autoTpl.value).toBeNull()
    expect(defaultRequests(h)).toHaveLength(1); expect(h.ElMessage.success).toHaveBeenCalledWith('已撤销自动套用的科室默认模板')
  })
  it('a legally delivered default remains a local draft until another explicit workspace open', async () => {
    const h = await setup(), pending = h.begin(); (await h.wait('/emr-templates/default')).reply(copy(candidate)); await pending
    const count = h.requests.length; await Vue.nextTick(); expect(copy(h.v.emr)).toEqual(expected)
    expect(h.requests).toHaveLength(count); expect(h.requests.every(w => w.config.method === 'get')).toBe(true)
    // This is not a newly fetched server disclosure or retroactive cache revocation claim.
  })
  it('a queue row lacking its original numeric department does not request a default', async () => {
    const h = await setup(); await h.begin({ ...a, deptId: undefined })
    expect(defaultRequests(h)).toEqual([]); untouched(h)
  })
})
