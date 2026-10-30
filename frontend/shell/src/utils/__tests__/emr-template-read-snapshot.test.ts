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

// Positive consumer compatibility, not a browser or backend snapshot RED:
// execute each ENTIRE original SFC setup and the real shared client/Axios.
// Only lifecycle, UI/storage and transport are controlled. A legally delivered
// cached body/grant row is not retroactively a new server disclosure.
const scopes: ReturnType<typeof Vue.effectScope>[] = []
const finished: Array<() => void> = []
afterEach(() => {
  try { for (const check of finished.splice(0)) check() }
  finally { for (const scope of scopes.splice(0)) scope.stop() }
})
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
function withoutImports(source: string, filename: string) {
  const parsed = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  return parsed.statements.filter(s => !ts.isImportDeclaration(s)).map(s => s.getText(parsed)).join('\n')
}
type Kind = 'OUTP' | 'ER' | 'GRANTS'
type Wire = { config: any; done: boolean; reply: (data?: unknown, code?: number, status?: number) => void }
const row = { id: 71, name: 'V146模拟可见模板', template_type: 'EMR', scope: 'DEPT',
  scopeName: '科室', dept_id: 7, owner_id: 3, record_type: 'OUTP', enabled: true,
  is_default: false, editable: true, grant_count: 1 }
const grant = { id: 711, template_id: 71, grantee_type: 'USER', grantee_id: 8,
  granted_at: '2026-10-30T00:00:00Z', grantee_name: '原合法被授权者', granted_by_name: '原授权医师' }
const emr = { id: 110, registrationId: 11, doctorId: 3, chiefComplaint: '原主诉', presentIllness: '当前未存现病史',
  pastHistory: '原既往史', physicalExam: '原查体', advice: '原处理', signature: null }
const visit = { registrationId: 11, patientId: 101, patientName: 'V146模拟门诊', deptId: 7, status: 'VISITED' }
const failures = [{ name: 'current ACL4066', code: 4066, status: 200 },
  { name: 'original HTTP403', code: 1005, status: 403 }, { name: 'transport HTTP503', code: 5000, status: 503 }]

async function setup(kind: Kind) {
  const storage = new Map([['hip_token', 'v146-original-token']])
  const localStorage = { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) }
  const ElMessage = Object.assign(vi.fn(), { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() })
  const ElMessageBox = { confirm: vi.fn((_text: string, _title?: string, _options?: unknown) => Promise.resolve(true)),
    prompt: vi.fn(() => Promise.resolve({ value: '模拟模板' })) }
  const router = { push: vi.fn() }
  const sharedSource = readFileSync(new URL('../../api/client.ts', import.meta.url), 'utf8')
  const clientBox: Record<string, any> = { axios, ElMessage, router, localStorage, Blob, exports: {} }
  runInNewContext(ts.transpileModule(withoutImports(sharedSource, 'client.ts'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, clientBox)
  const client = clientBox.exports.default, requests: Wire[] = []
  const target = kind === 'GRANTS' ? '/emr-templates/71/grants' : '/emr-templates/71'
  client.defaults.adapter = (config: any) => new Promise((resolve, reject) => {
    const wire: Wire = { config, done: false, reply(data = null, code = 0, status = 200) {
      expect(wire.done).toBe(false); wire.done = true
      const response = { data: { code, message: code ? '模板不存在或未授权使用' : 'success', data },
        config, status, statusText: String(status), headers: {} }
      if (status >= 400) reject(new axios.AxiosError('Request failed with status code ' + status,
        'ERR_BAD_REQUEST', config, undefined, response))
      else resolve(response)
    } }
    requests.push(wire)
    if (config.url === target) return
    expect(config.method).toBe('get')
    if (config.url === '/outpatient/doctor/11/workspace') wire.reply({ emr: copy(emr), diagnoses: [], orders: [] })
    else if (config.url === '/outpatient/doctor/special-disease') wire.reply([])
    else if (config.url === '/outpatient/emr-ref') wire.reply({ prefill: {} })
    else if (config.url === '/emr-templates/default') wire.reply(null)
    else if (config.url === '/emr-templates/visible') wire.reply([])
    else if (config.url === '/emr-templates/grantee-candidates') wire.reply([])
    else throw new Error('Unconfigured original request ' + config.url)
  })
  const path = kind === 'OUTP' ? '../../views/outpatient/DoctorStationView.vue'
    : kind === 'ER' ? '../../views/medtech/SpecialtyView.vue' : '../../views/quality/EmrTemplateView.vue'
  const component = readFileSync(new URL(path, import.meta.url), 'utf8')
  const original = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const exposed = kind === 'OUTP'
    ? 'current, emr, diagList, emrTemplates, structTemplateId, structFields, structValues, insertTarget, applyTemplateBody, openPatient, currentEmrId, emrAuthorId, emrSigned'
    : kind === 'ER'
      ? 'erNoteTarget, erNoteText, erNoteDialog, erTplId, erTemplates, erNotes, erNoteSaving, applyErTemplate'
      : 'all, actor, form, dialogVisible, dialogTitle, grantRow, grantRows, grantVisible, granteeId, grantType, candidates, openEdit, openGrants, loadGrants'
  const box: Record<string, any> = { ...Vue, ...dateUtils, ...templateUtils, ...defaultUtils, ...submitUtils,
    ...printUtils, ...rxUtils, applyTemplateToEmr: templateUtils.applyTemplate,
    client, ElMessage, ElMessageBox, useRouter: () => router, useRoute: () => ({ query: {} }),
    useAuthStore: () => ({ user: { id: 3, roles: ['DOCTOR_OUTP'], menus: [] } }),
    useEmrPasteGuard: () => ({ mode: Vue.ref('warn'), loadPolicy: vi.fn(), onCopy: vi.fn(), onPaste: vi.fn() }),
    onMounted: vi.fn(), onUnmounted: vi.fn(), window: { open: vi.fn() }, console }
  const scope = Vue.effectScope(); scopes.push(scope)
  scope.run(() => runInNewContext(ts.transpileModule(withoutImports(original, kind + '.ts')
    + '\nglobalThis.view = { ' + exposed + ' };', {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box))
  const v = box.view as Record<string, any>
  if (kind === 'OUTP') {
    await v.openPatient(copy(visit)); v.emrTemplates.value = [copy(row)]; v.structTemplateId.value = 71
    v.insertTarget.value = 'presentIllness'; v.structValues.value = { prior: '既有结构化草稿' }
  } else if (kind === 'ER') {
    v.erNoteTarget.value = { id: 51, patientId: 101 }; v.erNoteText.value = '未保存的观察情况'
    v.erNoteDialog.value = true; v.erTplId.value = 71; v.erTemplates.value = [copy(row)]
    v.erNotes.value = [{ id: 510, note: '原合法记录' }]
  } else {
    v.all.value = [{ ...copy(row), content: '原合法已缓存正文' }]
    v.actor.value = { userId: 3, deptId: 7, deptName: '模拟科室', admin: false, grantableScopes: ['DEPT', 'PERSONAL'] }
    v.openEdit(v.all.value[0]); v.form.content = '当前尚未保存的模板正文'
    v.grantRow.value = copy(row); v.grantRows.value = [copy(grant)]; v.grantVisible.value = true
  }
  const clear = () => {
    requests.length = 0
    for (const value of Object.values(ElMessage)) if (typeof value === 'function' && 'mockClear' in value) value.mockClear()
  }
  clear(); finished.push(() => expect(requests.filter(w => !w.done)).toEqual([]))
  const invoke = () => kind === 'OUTP' ? v.applyTemplateBody() : kind === 'ER' ? v.applyErTemplate(71) : v.openGrants(copy(row))
  return { kind, v, requests, storage, ElMessage, ElMessageBox, router, target, clear, invoke,
    async begin() {
      const result = invoke().then((value: unknown) => ({ ok: true, value }), (error: any) => ({ ok: false, error }))
      await vi.waitFor(() => expect(requests.some(w => w.config.url === target && !w.done)).toBe(true))
      return { result, wire: requests.find(w => w.config.url === target && !w.done)! }
    } }
}
type Harness = Awaited<ReturnType<typeof setup>>
const state = (h: Harness) => copy(h.kind === 'OUTP'
  ? { current: h.v.current.value, emr: h.v.emr, diagnoses: h.v.diagList.value, template: h.v.structTemplateId.value,
    values: h.v.structValues.value, emrId: h.v.currentEmrId.value, author: h.v.emrAuthorId.value, signed: h.v.emrSigned.value }
  : h.kind === 'ER' ? { target: h.v.erNoteTarget.value, text: h.v.erNoteText.value, dialog: h.v.erNoteDialog.value,
    template: h.v.erTplId.value, rows: h.v.erNotes.value, saving: h.v.erNoteSaving.value }
    : { all: h.v.all.value, form: h.v.form, dialog: h.v.dialogVisible.value, title: h.v.dialogTitle.value,
      grantRow: h.v.grantRow.value, grantRows: h.v.grantRows.value, grantVisible: h.v.grantVisible.value })
const tokenKept = (h: Harness) => {
  expect(h.storage.get('hip_token')).toBe('v146-original-token'); expect(h.router.push).not.toHaveBeenCalled()
}
function assertOnlyReads(h: Harness) {
  expect(h.requests.every(w => w.config.method === 'get')).toBe(true)
  expect(h.requests.filter(w => w.config.url === h.target)).toHaveLength(1)
}
const accepted = (h: Harness) => h.kind === 'GRANTS' ? [copy(grant)] : { ...copy(row), content: 'V146新合法正文' }

describe('v146 real original template-read consumer compatibility', () => {
  for (const kind of ['OUTP', 'ER', 'GRANTS'] as const) {
    for (const failure of failures) {
      it(`${kind} ${failure.name}: one original read preserves draft/session without false application or retry`, async () => {
        const h = await setup(kind), before = state(h), request = await h.begin()
        expect(request.wire.config).toMatchObject({ method: 'get', url: h.target })
        expect(request.wire.config.headers.Authorization).toBe('Bearer v146-original-token')
        request.wire.reply(null, failure.code, failure.status)
        const result = await request.result; expect(result.ok).toBe(false)
        if (failure.status === 200) expect(result.error.bizCode).toBe(4066)
        else expect(result.error.response.status).toBe(failure.status)
        expect(state(h)).toEqual(before); expect(h.ElMessage.success).not.toHaveBeenCalled()
        expect(h.ElMessage.error).toHaveBeenCalledTimes(1)
        expect(h.ElMessageBox.confirm).not.toHaveBeenCalled(); assertOnlyReads(h); tokenKept(h)
      })
    }
    it(`${kind}: a new explicit read after known4066 succeeds with the original path and retained draft`, async () => {
      const h = await setup(kind), before = state(h), first = await h.begin()
      first.wire.reply(null, 4066); expect((await first.result).ok).toBe(false); expect(state(h)).toEqual(before)
      h.clear(); const restored = await h.begin(); restored.wire.reply(accepted(h))
      expect((await restored.result).ok).toBe(true); assertOnlyReads(h); tokenKept(h)
      if (kind === 'OUTP') {
        expect(h.v.emr.presentIllness).toBe('V146新合法正文'); expect(h.v.emr.chiefComplaint).toBe(emr.chiefComplaint)
      } else if (kind === 'ER') expect(h.v.erNoteText.value).toBe('V146新合法正文')
      else { expect(h.v.grantRows.value).toEqual([grant]); expect(h.v.form.content).toBe(before.form.content) }
    })
    it(`${kind}: original authorized reply is consumed without clinical/template mutation`, async () => {
      const h = await setup(kind), request = await h.begin(); request.wire.reply(accepted(h))
      expect((await request.result).ok).toBe(true); assertOnlyReads(h); tokenKept(h)
      if (kind === 'OUTP') expect(h.ElMessage.success).toHaveBeenCalledWith(expect.stringContaining('已套用模板'))
      if (kind === 'GRANTS') expect(h.v.grantRows.value[0]).toEqual(grant)
    })
  }
  it.each(['OUTP', 'ER'] as const)('%s: legally cached content still applies locally, with no new GET or disclosure claim', async kind => {
    const h = await setup(kind)
    const rows = kind === 'OUTP' ? h.v.emrTemplates : h.v.erTemplates
    rows.value = [{ ...copy(row), content: '此前合法发到客户端的正文' }]
    await h.invoke(); expect(h.requests).toEqual([]); tokenKept(h)
    expect(kind === 'OUTP' ? h.v.emr.presentIllness : h.v.erNoteText.value).toBe('此前合法发到客户端的正文')
  })
  it('GRANTS: a visible empty grant collection consumes [] without exposing an internal parent sentinel', async () => {
    const h = await setup('GRANTS'), request = await h.begin(); request.wire.reply([])
    expect((await request.result).ok).toBe(true); expect(h.v.grantRows.value).toEqual([])
    expect(h.ElMessage.error).not.toHaveBeenCalled(); assertOnlyReads(h); tokenKept(h)
  })
  it('original HTTP401 expires login while preserving the unsaved maintenance form', async () => {
    const h = await setup('GRANTS'), before = state(h), request = await h.begin(); request.wire.reply(null, 1000, 401)
    expect((await request.result).ok).toBe(false); expect(state(h)).toEqual(before)
    expect(h.storage.has('hip_token')).toBe(false); expect(h.router.push).toHaveBeenCalledWith('/login')
    expect(h.ElMessage.warning).toHaveBeenCalledWith('登录已过期，请重新登录')
    expect(h.ElMessage.success).not.toHaveBeenCalled(); assertOnlyReads(h)
  })
})
