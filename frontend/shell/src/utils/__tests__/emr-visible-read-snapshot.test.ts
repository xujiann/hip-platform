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
import * as inpEditUtils from '../inp-record-edit'

// Positive compatibility of four ENTIRE original SFC setup scripts and the
// real shared client/Axios. This is neither a mounted browser nor the Java
// JDBC snapshot RED. Original success caches are not retroactively revoked.
const scopes: ReturnType<typeof Vue.effectScope>[] = []
const complete: Array<() => void> = []
afterEach(() => {
  try { for (const check of complete.splice(0)) check() }
  finally { for (const scope of scopes.splice(0)) scope.stop() }
})
const copy = <T>(v: T): T => JSON.parse(JSON.stringify(v))
function withoutImports(source: string, name: string) {
  const file = ts.createSourceFile(name, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  return file.statements.filter(s => !ts.isImportDeclaration(s)).map(s => s.getText(file)).join('\n')
}
type Kind = 'MAINT' | 'OUTP' | 'INP' | 'ER'
type Wire = { config: any; done: boolean; reply: (data?: unknown, code?: number, status?: number) => void }
const list = [
  { id: 73, name: 'V148模拟默认', content: '原合法默认正文', template_type: 'EMR', scope: 'DEPT', scopeName: '科室',
    owner_id: 8, dept_id: 7, owner_name: '模拟所有者', dept_name: '模拟甲科', record_type: 'CUSTOM',
    is_default: true, enabled: true, editable: false, grant_count: 2, created_by: 8, created_at: '2026-10-30T00:00:00.000+00:00' },
  { id: 71, name: 'V148模拟全院', content: '原合法全院正文', template_type: 'EMR', scope: 'HOSPITAL', scopeName: '全院',
    owner_id: 9, dept_id: null, owner_name: '模拟管理员', dept_name: null, record_type: 'OUTP',
    is_default: false, enabled: true, editable: false, grant_count: 0, created_by: 9, created_at: '2026-10-30T00:00:00.000+00:00' },
  { id: 72, name: 'V148模拟个人', content: '此前合法个人正文', template_type: 'EMR', scope: 'PERSONAL', scopeName: '个人',
    owner_id: 3, dept_id: null, owner_name: '模拟本人', dept_name: null, record_type: null,
    is_default: false, enabled: true, editable: true, grant_count: 1, created_by: 3, created_at: '2026-10-30T00:00:00.000+00:00' },
  { id: 74, name: 'V148模拟住院', content: '原合法住院正文', template_type: 'EMR', scope: 'DEPT', scopeName: '科室',
    owner_id: 8, dept_id: 7, owner_name: '模拟所有者', dept_name: '模拟甲科', record_type: 'INP',
    is_default: false, enabled: true, editable: false, grant_count: 2, created_by: 8, created_at: '2026-10-30T00:00:00.000+00:00' },
]
const admission = { id: 21, patientId: 201, patientName: 'V148模拟住院', doctorId: 3 }
async function setup(kind: Kind) {
  const storage = new Map([['hip_token', 'v148-original-token']])
  const localStorage = { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, v: string) => storage.set(key, v), removeItem: (key: string) => storage.delete(key) }
  const router = { push: vi.fn() }
  const ElMessage = Object.assign(vi.fn(), { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() })
  const ElMessageBox = { confirm: vi.fn(() => Promise.resolve(true)), prompt: vi.fn(() => Promise.resolve({ value: '模拟模板' })) }
  const clientBox: Record<string, any> = { axios, localStorage, router, ElMessage, Blob, exports: {} }
  const clientSource = readFileSync(new URL('../../api/client.ts', import.meta.url), 'utf8')
  runInNewContext(ts.transpileModule(withoutImports(clientSource, 'client.ts'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, clientBox)
  const client = clientBox.exports.default, requests: Wire[] = []
  client.defaults.adapter = (config: any) => new Promise((resolve, reject) => {
    const wire: Wire = { config, done: false, reply(data = null, code = 0, status = 200) {
      expect(wire.done).toBe(false); wire.done = true
      const response = { config, status, statusText: String(status), headers: {}, data: { code, message: code ? '原读取被拒' : 'success', data } }
      if (status >= 400) reject(new axios.AxiosError('Request failed with status code ' + status, 'ERR_BAD_REQUEST', config, undefined, response))
      else resolve(response)
    } }
    requests.push(wire); expect(config.method).toBe('get')
    if (config.url === '/emr-templates/visible') return
    if (/^\/inpatient\/admissions\/\d+\/workspace$/.test(config.url)) wire.reply({ orders: [], totalAmount: 0, depositAmount: 100 })
    else if (/^\/inpatient\/admissions\/\d+\/(records|vitals)$/.test(config.url)) wire.reply([])
    else if (/^\/inpatient\/admissions\/\d+\/emr-integrity$/.test(config.url)) wire.reply({ complete: true, missing: [] })
    else if (/^\/inpatient\/admissions\/\d+\/account$/.test(config.url)) wire.reply({ balance: 100, owed: false })
    else throw new Error('Unconfigured original request ' + config.url)
  })
  const componentPath = kind === 'MAINT' ? '../../views/quality/EmrTemplateView.vue'
    : kind === 'OUTP' ? '../../views/outpatient/DoctorStationView.vue'
      : kind === 'INP' ? '../../views/inpatient/InpDoctorView.vue' : '../../views/medtech/SpecialtyView.vue'
  const component = readFileSync(new URL(componentPath, import.meta.url), 'utf8')
  const original = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const expose = kind === 'MAINT' ? 'all, rows, load, filterType, filterScope, filterDeptId, includeDisabled, keyword, form, dialogVisible, preview, previewRow, previewVisible'
    : kind === 'OUTP' ? 'loadEmrTemplates, emrTemplates, emr, structTemplateId, structValues'
      : kind === 'INP' ? 'open, emrTemplates, current, records, recordTitle, recordContent'
        : 'loadErTemplates, erTemplates, erNoteDialog, erNoteText, erNoteTarget'
  const box: Record<string, any> = { ...Vue, ...dateUtils, ...templateUtils, ...defaultUtils, ...submitUtils,
    ...printUtils, ...rxUtils, ...inpEditUtils, applyTemplateToEmr: templateUtils.applyTemplate,
    useRouter: () => router, useRoute: () => ({ query: {} }),
    client, ElMessage, ElMessageBox, useAuthStore: () => ({ user: { id: 3, roles: ['DOCTOR_OUTP'], menus: [] } }),
    useEmrPasteGuard: () => ({ mode: Vue.ref('warn'), loadPolicy: vi.fn(), onCopy: vi.fn(), onPaste: vi.fn() }),
    onMounted: vi.fn(), onUnmounted: vi.fn(), window: { open: vi.fn() }, console }
  const scope = Vue.effectScope(); scopes.push(scope)
  scope.run(() => runInNewContext(ts.transpileModule(withoutImports(original, kind + '.ts') + '\nglobalThis.view = { ' + expose + ' };', {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box))
  const v = box.view as Record<string, any>
  const templates = kind === 'MAINT' ? v.all : kind === 'ER' ? v.erTemplates : v.emrTemplates
  templates.value = copy(list)
  if (kind === 'MAINT') { v.form.content = '尚未保存的维护草稿'; v.dialogVisible.value = true; v.preview(copy(list[0])) }
  else if (kind === 'OUTP') { v.emr.presentIllness = '尚未保存的门诊正文'; v.structValues.value = { f: '原结构化草稿' }; v.structTemplateId.value = 73 }
  else if (kind === 'INP') { v.current.value = copy(admission); v.recordTitle.value = '未存住院标题'; v.recordContent.value = '未存住院正文' }
  else { v.erNoteDialog.value = true; v.erNoteTarget.value = { id: 31 }; v.erNoteText.value = '未存急诊正文' }
  const invoke = () => kind === 'MAINT' ? v.load() : kind === 'OUTP' ? v.loadEmrTemplates() : kind === 'INP' ? v.open(copy(admission)) : v.loadErTemplates()
  const begin = async () => {
    const before = requests.length
    const result = invoke().then((value: unknown) => ({ ok: true, value }), (error: any) => ({ ok: false, error }))
    await vi.waitFor(() => expect(requests.slice(before).some(w => w.config.url === '/emr-templates/visible')).toBe(true))
    return { result, wire: requests.slice(before).find(w => w.config.url === '/emr-templates/visible')! }
  }
  complete.push(() => expect(requests.filter(w => !w.done)).toEqual([]))
  return { kind, v, templates, requests, storage, router, ElMessage, invoke, begin }
}
type Harness = Awaited<ReturnType<typeof setup>>
function draft(h: Harness) {
  return copy(h.kind === 'MAINT' ? { form: h.v.form, dialog: h.v.dialogVisible.value, preview: h.v.previewRow.value, previewVisible: h.v.previewVisible.value }
    : h.kind === 'OUTP' ? { emr: h.v.emr, fields: h.v.structValues.value, id: h.v.structTemplateId.value }
      : h.kind === 'INP' ? { title: h.v.recordTitle.value, content: h.v.recordContent.value }
        : { dialog: h.v.erNoteDialog.value, target: h.v.erNoteTarget.value, text: h.v.erNoteText.value })
}
function session(h: Harness) {
  expect(h.storage.get('hip_token')).toBe('v148-original-token'); expect(h.router.push).not.toHaveBeenCalled()
  expect(h.requests.every(w => w.config.method === 'get')).toBe(true); expect(h.ElMessage.success).not.toHaveBeenCalled()
}
describe('v148 original visible-template list consumers', () => {
  for (const kind of ['MAINT', 'OUTP', 'INP', 'ER'] as const) {
    it(`${kind}: consumes original complete rows/metadata and preserves server order with original type selection only`, async () => {
      const h = await setup(kind), before = draft(h), pending = await h.begin()
      expect(pending.wire.config.params).toEqual(kind === 'MAINT' ? { includeDisabled: false, type: 'EMR' } : { type: 'EMR' })
      expect(pending.wire.config.headers.Authorization).toBe('Bearer v148-original-token')
      pending.wire.reply(copy(list)); expect((await pending.result).ok).toBe(true); session(h)
      expect(h.templates.value).toEqual(kind === 'OUTP' || kind === 'ER' ? list.filter(v => v.record_type !== 'INP') : list)
      expect(draft(h)).toEqual(before)
    })
    it(`${kind}: hidden/filtered success [] consumes an empty list without a4066 toast or clinical write`, async () => {
      const h = await setup(kind), before = draft(h), pending = await h.begin(); pending.wire.reply([])
      expect((await pending.result).ok).toBe(true); expect(h.templates.value).toEqual([]); expect(draft(h)).toEqual(before); session(h)
      expect(h.ElMessage.error).not.toHaveBeenCalled(); expect(h.ElMessage.warning).not.toHaveBeenCalled()
    })
    for (const status of [403, 503]) it(`${kind}: original HTTP${status} preserves the component's failure semantics and current unsaved draft`, async () => {
      const h = await setup(kind), before = draft(h), pending = await h.begin(); pending.wire.reply(null, status === 403 ? 1005 : 5000, status)
      const result = await pending.result; expect(result.ok).toBe(kind !== 'MAINT'); session(h); expect(draft(h)).toEqual(before)
      expect(h.ElMessage.error).toHaveBeenCalledTimes(1)
      expect(h.templates.value).toEqual(kind === 'MAINT' ? list : [])
      if (kind === 'INP') { expect(h.v.current.value).toBeNull(); expect(h.v.records.value).toEqual([]) }
    })
    it(`${kind}: original401 expires login while retaining this VM's unsaved form, without synthesizing list data`, async () => {
      const h = await setup(kind), before = draft(h), pending = await h.begin(); pending.wire.reply(null, 1000, 401); await pending.result
      expect(h.storage.has('hip_token')).toBe(false); expect(h.router.push).toHaveBeenCalledWith('/login')
      expect(h.ElMessage.warning).toHaveBeenCalledWith('登录已过期，请重新登录'); expect(draft(h)).toEqual(before)
      expect(h.requests.every(w => w.config.method === 'get')).toBe(true)
    })
  }
  it('MAINT: original three explicit refreshes consume visible→empty→restored without changing the legal cached preview or edit draft', async () => {
    const h = await setup('MAINT'), before = draft(h)
    for (const rows of [list, [], list]) { const next = await h.begin(); next.wire.reply(copy(rows)); expect((await next.result).ok).toBe(true); expect(h.v.all.value).toEqual(rows) }
    expect(draft(h)).toEqual(before); expect(h.requests).toHaveLength(3); session(h)
  })
  it('MAINT: original type/scope/keyword/includeDisabled params are trimmed as the actual page sends them, with department filtering local', async () => {
    const h = await setup('MAINT'); h.v.filterType.value = 'EMR'; h.v.filterScope.value = 'DEPT'
    h.v.keyword.value = '  模拟_%  '; h.v.includeDisabled.value = true; h.v.filterDeptId.value = 7
    const next = await h.begin(); expect(next.wire.config.params).toEqual({ includeDisabled: true, type: 'EMR', scope: 'DEPT', keyword: '模拟_%' })
    next.wire.reply(copy(list.filter(row => row.dept_id === 7))); await next.result
    expect(h.v.rows.value).toEqual(list.filter(row => row.dept_id === 7)); expect(h.requests).toHaveLength(1); session(h)
  })
  it('MAINT: clear optional filters preserves only includeDisabled and local unassigned-department selection sends no request', async () => {
    const h = await setup('MAINT'); h.v.filterType.value = null; h.v.filterScope.value = null; h.v.keyword.value = '  '
    const next = await h.begin(); expect(next.wire.config.params).toEqual({ includeDisabled: false }); next.wire.reply(copy(list)); await next.result
    h.v.filterDeptId.value = 0; expect(h.v.rows.value).toEqual(list.filter(row => !row.dept_id)); expect(h.requests).toHaveLength(1); session(h)
  })
  it('ER: an original successful list remains cached and a later local load does not pretend to perform a fresh authorization read', async () => {
    const h = await setup('ER'), next = await h.begin(); next.wire.reply(copy(list)); await next.result
    await h.invoke(); expect(h.requests).toHaveLength(1); expect(h.templates.value.map((v: any) => v.id)).toEqual([73, 71, 72]); session(h)
  })
  it('ER: failed original load remains retryable and the next explicit load consumes the restored list', async () => {
    const h = await setup('ER'), first = await h.begin(); first.wire.reply(null, 1005, 403); await first.result
    const restored = await h.begin(); restored.wire.reply(copy(list)); expect((await restored.result).ok).toBe(true)
    expect(h.requests).toHaveLength(2); expect(h.templates.value.map((v: any) => v.id)).toEqual([73, 71, 72]); session(h)
  })
})
