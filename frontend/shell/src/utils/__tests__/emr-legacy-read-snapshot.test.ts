import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as Vue from 'vue'
import axios from 'axios'

// Entire original RisView setup and real shared client/Axios; no copied policy
// handlers, fake client or mounted-browser claim. Old legal caches stay legal.
const cleanup: Array<() => void> = []
afterEach(() => { for (const done of cleanup.splice(0)) done() })
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
function withoutImports(source: string, name: string) {
  const file = ts.createSourceFile(name, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  return file.statements.filter(s => !ts.isImportDeclaration(s)).map(s => s.getText(file)).join('\n')
}
const rows = [
  { id: 61, dept_id: 8, name: 'V149模拟科室影像模板', content: '合法科室影像所见', template_type: 'RIS', enabled: true,
    scope: 'DEPT', owner_id: 5, record_type: 'OUTP', is_default: false, created_by: 5, created_at: '2026-10-30T00:00:00.000+00:00' },
  { id: 63, dept_id: null, name: 'V149模拟全院影像模板', content: '合法全院影像所见', template_type: 'RIS', enabled: true,
    scope: 'HOSPITAL', owner_id: 1, record_type: null, is_default: false, created_by: 1, created_at: '2026-10-30T00:00:00.000+00:00' },
  { id: 65, dept_id: null, name: 'V149模拟获授权个人影像模板', content: '此前合法个人影像所见', template_type: 'RIS', enabled: true,
    scope: 'PERSONAL', owner_id: 9, record_type: null, is_default: false, created_by: 9, created_at: '2026-10-30T00:00:00.000+00:00' },
]
const examination = { id: 31, item_name: 'V149模拟胸片', group_no: 'V149-SIMULATED', status: 'REGISTERED', order_status: 'CHARGED', findings: '', impression: '' }
type Wire = { config: any; done: boolean; reply: (data?: unknown, code?: number, status?: number) => void }
async function setup(roles = ['TECHNICIAN']) {
  const storage = new Map([['hip_token', 'v149-original-token']])
  const localStorage = { getItem: (k: string) => storage.get(k) ?? null,
    setItem: (k: string, v: string) => storage.set(k, v), removeItem: (k: string) => storage.delete(k) }
  const router = { push: vi.fn() }, window = { open: vi.fn() }
  const ElMessage = Object.assign(vi.fn(), { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() })
  const clientBox: Record<string, any> = { axios, localStorage, router, ElMessage, Blob, exports: {} }
  const clientSource = readFileSync(new URL('../../api/client.ts', import.meta.url), 'utf8')
  runInNewContext(ts.transpileModule(withoutImports(clientSource, 'client.ts'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, clientBox)
  const client = clientBox.exports.default, requests: Wire[] = []
  client.defaults.adapter = (config: any) => new Promise((resolve, reject) => {
    expect(config.method).toBe('get')
    expect(['/ris/worklist', '/emr-templates', '/ris/pacs-info']).toContain(config.url)
    const wire: Wire = { config, done: false, reply(data = null, code = 0, status = 200) {
      expect(wire.done).toBe(false); wire.done = true
      const response = { config, status, statusText: String(status), headers: {}, data: { code, message: code ? '原读取失败' : 'success', data } }
      if (status >= 400) reject(new axios.AxiosError('Request failed with status code ' + status, 'ERR_BAD_REQUEST', config, undefined, response))
      else resolve(response)
    } }
    requests.push(wire)
  })
  const component = readFileSync(new URL('../../views/medtech/RisView.vue', import.meta.url), 'utf8')
  const script = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const dispose: Array<() => void> = [], user = Vue.reactive({ id: 7, roles })
  const box: Record<string, any> = { ...Vue, client, ElMessage, window, location: { origin: 'http://v149.invalid' },
    ElMessageBox: { prompt: vi.fn(), alert: vi.fn() }, useAuthStore: () => ({ user }),
    onMounted: vi.fn(), onUnmounted: (fn: () => void) => dispose.push(fn) }
  const scope = Vue.effectScope()
  scope.run(() => runInNewContext(ts.transpileModule(withoutImports(script, 'RisView.ts') + `
    globalThis.view = { records, templates, templateId, current, dialogVisible, findings, impression,
      load, refreshRecords, openReport, closeReport, applyTemplate, loadPacs, pacs, openPacs };
  `, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box))
  const v = box.view as Record<string, any>
  v.records.value = [copy(examination)]; v.templates.value = copy(rows)
  cleanup.push(() => {
    try { expect(requests.filter(w => !w.done)).toEqual([]) }
    finally { dispose.forEach(fn => fn()); scope.stop() }
  })
  const begin = async (method: 'load' | 'refreshRecords' | 'loadPacs' = 'refreshRecords') => {
    const offset = requests.length
    const result = v[method]().then((value: unknown) => ({ ok: true, value }), (error: any) => ({ ok: false, error }))
    await vi.waitFor(() => expect(requests.length - offset).toBe(method === 'loadPacs' ? 1 : 2))
    return { result, wires: requests.slice(offset), list: requests.slice(offset).find(w => w.config.url === '/emr-templates')!,
      worklist: requests.slice(offset).find(w => w.config.url === '/ris/worklist')! }
  }
  return { v, requests, begin, storage, router, ElMessage, window, user, unmount: () => dispose.forEach(fn => fn()) }
}
type Harness = Awaited<ReturnType<typeof setup>>
function draft(h: Harness) { return copy({ dialog: h.v.dialogVisible.value, current: h.v.current.value, findings: h.v.findings.value, impression: h.v.impression.value, templateId: h.v.templateId.value }) }
function open(h: Harness) { h.v.openReport(h.v.records.value[0]); h.v.findings.value = '原未提交影像草稿'; h.v.impression.value = '原未提交诊断意见' }
function session(h: Harness) {
  expect(h.storage.get('hip_token')).toBe('v149-original-token'); expect(h.router.push).not.toHaveBeenCalled()
  expect(h.requests.every(w => w.config.method === 'get')).toBe(true); expect(h.ElMessage.success).not.toHaveBeenCalled()
}
describe('v149 original legacy RIS template consumer compatibility', () => {
  it('consumes the complete original snake_case rows and server id order with exact literal RIS params', async () => {
    const h = await setup(), next = await h.begin()
    expect(next.list.config.params).toEqual({ type: 'RIS' }); expect(next.worklist.config.params).toBeUndefined()
    expect(next.list.config.headers.Authorization).toBe('Bearer v149-original-token')
    next.list.reply(copy(rows)); next.worklist.reply([copy(examination)]); expect((await next.result).ok).toBe(true)
    expect(h.v.templates.value).toEqual(rows); expect(h.v.templates.value.map((t: any) => t.id)).toEqual([61, 63, 65])
    expect(Object.keys(h.v.templates.value[0]).sort()).toEqual(Object.keys(rows[0]).sort()); session(h)
    for (const key of ['scopeName', 'editable', 'grant_count', 'owner_name', 'dept_name', '__legacy_visible']) expect(h.v.templates.value[0]).not.toHaveProperty(key)
  })
  it('a code0 empty legacy list replaces options without an error or changing an already open draft', async () => {
    const h = await setup(); open(h); const before = draft(h), next = await h.begin()
    next.list.reply([]); next.worklist.reply([copy(examination)]); expect((await next.result).ok).toBe(true)
    expect(h.v.templates.value).toEqual([]); expect(draft(h)).toEqual(before); expect(h.ElMessage.error).not.toHaveBeenCalled(); session(h)
  })
  it('opens and applies an original cached template entirely locally without a detail request or write', async () => {
    const h = await setup(); open(h); h.v.templateId.value = 65; h.v.applyTemplate()
    expect(h.v.findings.value).toBe(rows[2].content); expect(h.v.impression.value).toBe('原未提交诊断意见'); expect(h.requests).toEqual([]); session(h)
  })
  it('three explicit toolbar refreshes close old drafts and consume list→empty→restored before local reuse', async () => {
    const h = await setup(); open(h)
    for (const list of [rows, [], rows]) {
      const next = await h.begin('load'); expect(h.v.current.value).toBeNull(); expect(h.v.dialogVisible.value).toBe(false)
      expect(h.v.findings.value).toBe(''); expect(h.v.impression.value).toBe(''); expect(h.v.templates.value).toEqual([])
      next.worklist.reply([copy(examination)]); next.list.reply(copy(list)); expect((await next.result).ok).toBe(true)
      expect(h.v.templates.value).toEqual(list); h.v.openReport(h.v.records.value[0]); h.v.templateId.value = 61; h.v.applyTemplate()
      expect(h.v.findings.value).toBe(list.length ? rows[0].content : ''); expect(h.v.impression.value).toBe('')
    }
    expect(h.requests).toHaveLength(6); session(h)
  })
  for (const method of ['refreshRecords', 'load'] as const) for (const status of [403, 503, 401]) {
    it(`${method}: keeps its original HTTP${status} failure, login and draft/list clearing semantics`, async () => {
      const h = await setup(); open(h); const before = draft(h), next = await h.begin(method)
      next.worklist.reply([copy(examination)]); next.list.reply(null, status === 403 ? 1005 : status === 401 ? 1000 : 5000, status)
      const result = await next.result; expect(result.ok).toBe(false)
      expect(h.v.templates.value).toEqual(method === 'load' ? [] : rows)
      expect(h.v.records.value).toEqual(method === 'load' ? [] : [examination])
      if (method === 'refreshRecords') expect(draft(h)).toEqual(before)
      else { expect(h.v.current.value).toBeNull(); expect(h.v.dialogVisible.value).toBe(false); expect(h.v.findings.value).toBe(''); expect(h.v.impression.value).toBe('') }
      if (status === 401) { expect(h.storage.has('hip_token')).toBe(false); expect(h.router.push).toHaveBeenCalledWith('/login'); expect(h.ElMessage.warning).toHaveBeenCalledWith('登录已过期，请重新登录') }
      else { session(h); expect(h.ElMessage.error).toHaveBeenCalledTimes(1) }
      expect(h.requests).toHaveLength(2)
    })
  }
  it('does not treat an original envelope4000 failure as a successful empty projection', async () => {
    const h = await setup(); open(h); const before = draft(h), next = await h.begin()
    next.list.reply(null, 4000); next.worklist.reply([copy(examination)]); const result = await next.result
    expect(result.ok).toBe(false); if (!result.ok) expect(result.error.bizCode).toBe(4000)
    expect(h.v.templates.value).toEqual(rows); expect(draft(h)).toEqual(before); expect(h.ElMessage.error).toHaveBeenCalledWith('原读取失败'); session(h)
  })
  it('only the latest original pair of reads adopts rows when two legal refreshes overlap', async () => {
    const h = await setup(), older = await h.begin(), newer = await h.begin(); const fresh = [{ ...rows[0], content: '最新合法正文' }]
    newer.list.reply(fresh); newer.worklist.reply([{ ...examination, item_name: '最新模拟检查' }]); expect((await newer.result).ok).toBe(true)
    older.list.reply(copy(rows)); older.worklist.reply([copy(examination)]); expect((await older.result).ok).toBe(true)
    expect(h.v.templates.value).toEqual(fresh); expect(h.v.records.value[0].item_name).toBe('最新模拟检查'); expect(h.requests).toHaveLength(4); session(h)
  })
  it('a later successful empty projection stays empty when an earlier authorized list arrives last', async () => {
    const h = await setup(), older = await h.begin(), newer = await h.begin()
    newer.list.reply([]); newer.worklist.reply([copy(examination)]); await newer.result
    older.list.reply(copy(rows)); older.worklist.reply([copy(examination)]); await older.result
    expect(h.v.templates.value).toEqual([]); session(h)
  })
  it('unmount invalidates existing read adoption and local template application without rewriting replies', async () => {
    const h = await setup(); open(h); const before = draft(h), next = await h.begin(); h.unmount()
    next.list.reply([{ ...rows[0], content: '卸载后原成功回包' }]); next.worklist.reply([]); await next.result
    expect(h.v.templates.value).toEqual(rows); expect(h.v.records.value).toEqual([examination]); expect(draft(h)).toEqual(before)
    h.v.templateId.value = 61; h.v.applyTemplate(); expect(h.v.findings.value).toBe(before.findings); session(h)
  })
  for (const id of [null, 900000]) it(`clearing or choosing missing cached template ${id} preserves the existing findings`, async () => {
    const h = await setup(); open(h); const before = h.v.findings.value; h.v.templateId.value = id; h.v.applyTemplate()
    expect(h.v.findings.value).toBe(before); expect(h.requests).toEqual([]); session(h)
  })
  it('a local role loss preserves a legally cached template/draft while preventing new template application', async () => {
    const h = await setup(); open(h); const before = draft(h); h.user.roles = ['QUALITY']
    h.v.templateId.value = 61; h.v.applyTemplate()
    expect(h.v.findings.value).toBe(before.findings); expect(h.v.impression.value).toBe(before.impression)
    expect(h.v.templates.value).toEqual(rows); expect(h.requests).toEqual([]); session(h)
  })
  it('PACS configuration remains an independent original GET and local viewer link', async () => {
    const h = await setup(); open(h); const before = draft(h), next = await h.begin('loadPacs')
    expect(next.wires[0].config.url).toBe('/ris/pacs-info'); next.wires[0].reply({ configured: true, viewerUrl: 'http://pacs.invalid/viewer' }); await next.result
    h.v.openPacs(); expect(h.window.open).toHaveBeenCalledWith('http://pacs.invalid/viewer', '_blank'); expect(draft(h)).toEqual(before); expect(h.v.templates.value).toEqual(rows); session(h)
  })
  it('PACS failure keeps its original safe state and does not consume or clear report templates or draft', async () => {
    const h = await setup(); open(h); const before = draft(h), next = await h.begin('loadPacs')
    next.wires[0].reply(null, 5000, 503); expect((await next.result).ok).toBe(true); h.v.openPacs()
    expect(h.v.pacs.value).toEqual({ configured: false, viewerUrl: '' }); expect(h.window.open).not.toHaveBeenCalled(); expect(draft(h)).toEqual(before); expect(h.v.templates.value).toEqual(rows); session(h)
  })
  it('PACS read generation rejects an old successful viewer URL without affecting the legacy list', async () => {
    const h = await setup(), older = await h.begin('loadPacs'), newer = await h.begin('loadPacs')
    newer.wires[0].reply({ configured: true, viewerUrl: 'http://pacs.invalid/new' }); await newer.result
    older.wires[0].reply({ configured: true, viewerUrl: 'http://pacs.invalid/old' }); await older.result
    expect(h.v.pacs.value.viewerUrl).toBe('http://pacs.invalid/new'); expect(h.v.templates.value).toEqual(rows); session(h)
  })
})
