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

// Consumer compatibility positives: run each ENTIRE original setup script and
// the real shared client/Axios. Only transport, lifecycle and UI are controlled.
// Inpatient's original page submits free text, not structured fields. Its API
// structured-save ACL is verified by the public HTTP and real JWT Java suites.
const scopes: ReturnType<typeof Vue.effectScope>[] = []
const finished: Array<() => void> = []
afterEach(() => {
  try { for (const check of finished.splice(0)) check() }
  finally { for (const scope of scopes.splice(0)) scope.stop() }
})
const copy = <T>(v: T): T => JSON.parse(JSON.stringify(v))
function withoutImports(source: string, file: string) {
  const parsed = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  return parsed.statements.filter(s => !ts.isImportDeclaration(s)).map(s => s.getText(parsed)).join('\n')
}
type Kind = 'OUTP' | 'INP'
type Wire = { config: any; done: boolean; reply: (data?: unknown, code?: number, status?: number) => void }
const outpatient = { registrationId: 11, patientId: 101, patientName: 'V145模拟门诊', deptId: 7, status: 'VISITED' }
const inpatient = { id: 21, patientId: 201, patientName: 'V145模拟住院', deptId: 7, doctorId: 3 }
const emr = { id: 110, registrationId: 11, doctorId: 3, chiefComplaint: '原主诉', presentIllness: '原现病史',
  pastHistory: '原既往史', physicalExam: '原查体', advice: '原处理', signature: null }
const field = { id: 711, templateId: 71, fieldCode: 'f', label: 'V145模拟元素', datatype: 'TEXT',
  required: false, sortNo: 1, valueSet: [], placeholder: '', unit: null, enabled: true }
const record = { id: 210, admissionId: 21, doctorId: 3, recordType: 'PROGRESS', title: '原暂存记录',
  content: '原住院正文', contentJson: null, templateId: null, signature: null, signedAt: null }

async function setup(kind: Kind) {
  const storage = new Map([['hip_token', 'v145-original-token']])
  const localStorage = { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) }
  const ElMessage = Object.assign(vi.fn(), { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() })
  const ElMessageBox = { confirm: vi.fn((_message: string, _title?: string, _options?: unknown) => Promise.resolve(true)),
    prompt: vi.fn(() => Promise.resolve({ value: '模拟模板' })) }, router = { push: vi.fn() }
  const clientBox: Record<string, any> = { axios, ElMessage, router, localStorage, Blob, exports: {} }
  const clientSource = readFileSync(new URL('../../api/client.ts', import.meta.url), 'utf8')
  runInNewContext(ts.transpileModule(withoutImports(clientSource, 'client.ts'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, clientBox)
  const client = clientBox.exports.default, requests: Wire[] = []
  const clinical = { emr: copy(emr), diagnoses: [{ icdCode: 'Z00', icdName: '模拟诊断', prefix: '', suffix: '',
    certainty: '', diagSystem: 'ICD10', customName: '' }], orders: [] }
  const records = [copy(record)]
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
    if (config.method !== 'get') return
    const url = config.url
    if (url === '/outpatient/doctor/11/workspace') wire.reply(copy(clinical))
    else if (url === '/outpatient/doctor/special-disease') wire.reply([])
    else if (url === '/outpatient/emr-ref') wire.reply({ prefill: {} })
    else if (url === '/outpatient/doctor/worklist') wire.reply([copy(outpatient)])
    else if (url === '/cdss/suggestions') wire.reply([{ content: '既有建议' }])
    else if (url === '/emr/templates/71/fields') wire.reply([copy(field)])
    else if (url === '/emr-templates/default') wire.reply(null)
    else if (url === '/emr-templates/visible') wire.reply([])
    else if (url === '/inpatient/admissions/21/workspace') wire.reply({ orders: [], totalAmount: 0, depositAmount: 100 })
    else if (url === '/inpatient/admissions/21/records') wire.reply(copy(records))
    else if (url === '/inpatient/admissions/21/vitals') wire.reply([])
    else if (url === '/inpatient/admissions/21/emr-integrity') wire.reply({ complete: true, missing: [] })
    else if (url === '/inpatient/admissions/21/account') wire.reply({ balance: 100, owed: false })
    else throw new Error('Unconfigured original GET ' + url)
  })
  const filename = kind === 'OUTP' ? 'DoctorStationView' : 'InpDoctorView'
  const component = readFileSync(new URL(kind === 'OUTP' ? '../../views/outpatient/DoctorStationView.vue'
    : '../../views/inpatient/InpDoctorView.vue', import.meta.url), 'utf8')
  const original = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const expose = kind === 'OUTP'
    ? 'current, emr, diagList, structTemplateId, structFields, structValues, autoTpl, savingEmr, signing, currentEmrId, emrAuthorId, cdssTips, openPatient, saveEmr, signEmr, loadStructFields, emrSigned'
    : 'current, recordContent, recordTitle, recordType, superiorCorrection, records, emrTemplateId, editingRecord, savingEdit, addRecord, open, clearEditor, saveEdit, roundLevel'
  const box: Record<string, any> = { ...Vue, ...dateUtils, ...templateUtils, ...defaultUtils, ...submitUtils,
    ...printUtils, ...rxUtils, ...inpEditUtils, applyTemplateToEmr: templateUtils.applyTemplate,
    client, ElMessage, ElMessageBox, useAuthStore: () => ({ user: { id: 3, menus: [] } }),
    useEmrPasteGuard: () => ({ mode: Vue.ref('warn'), loadPolicy: vi.fn(), onCopy: vi.fn(), onPaste: vi.fn() }),
    onMounted: vi.fn(), onUnmounted: vi.fn(), window: { open: vi.fn() }, console }
  const scope = Vue.effectScope(); scopes.push(scope)
  scope.run(() => runInNewContext(ts.transpileModule(withoutImports(original, filename + '.ts')
    + '\nglobalThis.view = { ' + expose + ' };', {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box))
  const v = box.view as Record<string, any>
  if (kind === 'OUTP') {
    await v.openPatient(copy(outpatient)); v.structTemplateId.value = 71
    await v.loadStructFields(); v.structValues.value = { f: '尚未保存的结构化值' }
    v.emr.chiefComplaint = '尚未保存的主诉'; v.emr.presentIllness = '尚未保存的现病史'
    v.cdssTips.value = ['当前既有建议']
  } else {
    await v.open(copy(inpatient)); v.recordTitle.value = '尚未暂存的住院标题'
    v.recordContent.value = '尚未暂存的住院正文'; v.emrTemplateId.value = 71
  }
  const clear = () => { requests.length = 0; for (const value of Object.values(ElMessage))
    if (typeof value === 'function' && 'mockClear' in value) value.mockClear() }
  clear(); finished.push(() => expect(requests.filter(x => !x.done)).toEqual([]))
  return { kind, v, requests, storage, router, ElMessage, ElMessageBox, records, clinical, clear,
    async begin(action: 'save' | 'sign' | 'add') {
      const result = (action === 'save' ? v.saveEmr() : action === 'sign' ? v.signEmr() : v.addRecord())
        .then((value: unknown) => ({ ok: true, value }), (error: any) => ({ ok: false, error }))
      await vi.waitFor(() => expect(requests.some(x => x.config.method !== 'get' && !x.done)).toBe(true))
      return { result, wire: requests.find(x => x.config.method !== 'get' && !x.done)! }
    } }
}
type Harness = Awaited<ReturnType<typeof setup>>
const writes = (h: Harness) => h.requests.filter(x => x.config.method !== 'get')
const tokenKept = (h: Harness) => {
  expect(h.storage.get('hip_token')).toBe('v145-original-token'); expect(h.router.push).not.toHaveBeenCalled()
}
const outState = (h: Harness) => copy({ current: h.v.current.value, emr: h.v.emr, diagnoses: h.v.diagList.value,
  templateId: h.v.structTemplateId.value, definitions: h.v.structFields.value, values: h.v.structValues.value,
  auto: h.v.autoTpl.value, emrId: h.v.currentEmrId.value, author: h.v.emrAuthorId.value,
  signed: h.v.emrSigned.value, tips: h.v.cdssTips.value })
const inpState = (h: Harness) => copy({ current: h.v.current.value, content: h.v.recordContent.value,
  title: h.v.recordTitle.value, type: h.v.recordType.value, template: h.v.emrTemplateId.value,
  records: h.v.records.value, editing: h.v.editingRecord.value })
const failures = [{ name: 'parent ACL 4066', code: 4066, status: 200 },
  { name: 'withdrawn duty 403', code: 1005, status: 403 }, { name: 'transport 503', code: 5000, status: 503 }]

describe('v145 original structured outpatient consumer compatibility', () => {
  for (const failure of failures) {
    it.each(['save', 'sign'] as const)(`${failure.name}: %s preserves cached fields/drafts/token and never signs or reports success`, async action => {
      const h = await setup('OUTP'), before = outState(h), first = await h.begin(action)
      expect(first.wire.config).toMatchObject({ method: 'put', url: '/outpatient/doctor/11/emr' })
      const body = JSON.parse(first.wire.config.data)
      expect(body.templateId).toBe(71); expect(body.fields).toEqual({ f: '尚未保存的结构化值' })
      expect(body.emr).toEqual(before.emr)
      expect(first.wire.config.headers.Authorization).toBe('Bearer v145-original-token')
      first.wire.reply(null, failure.code, failure.status)
      const result = await first.result; expect(result.ok).toBe(false)
      if (failure.status === 200) expect(result.error.bizCode).toBe(4066)
      else expect(result.error.response.status).toBe(failure.status)
      expect(outState(h)).toEqual(before); expect(h.v.savingEmr.value).toBe(false); expect(h.v.signing.value).toBe(false)
      expect(h.ElMessage.error).toHaveBeenCalledTimes(1); expect(h.ElMessage.success).not.toHaveBeenCalled()
      expect(h.requests).toHaveLength(1); expect(writes(h)).toHaveLength(1)
      expect(h.ElMessageBox.confirm).toHaveBeenCalledTimes(action === 'sign' ? 1 : 0); tokenKept(h)
    })
  }
  it('a new explicit save after a known 4066 sends the same draft once and succeeds after authorization is restored', async () => {
    const h = await setup('OUTP'), before = outState(h), first = await h.begin('save')
    first.wire.reply(null, 4066); expect((await first.result).ok).toBe(false)
    expect(outState(h)).toEqual(before); const originalBody = first.wire.config.data
    const next = await h.begin('save'); expect(next.wire.config.data).toBe(originalBody)
    next.wire.reply({ id: 110, doctorId: 3 }); expect((await next.result).value).toBe(true)
    expect(writes(h)).toHaveLength(2); expect(h.ElMessage.success).toHaveBeenCalledWith('病历已暂存（未签名，仍可修改）')
    expect(h.v.emr).toEqual(before.emr); expect(h.v.structValues.value).toEqual(before.values)
    expect(h.v.cdssTips.value).toEqual(['既有建议']); expect(h.v.savingEmr.value).toBe(false); tokenKept(h)
  })
  it('an authorized structured save keeps the original path and complete diagnosis/field payload', async () => {
    const h = await setup('OUTP'), first = await h.begin('save'), body = JSON.parse(first.wire.config.data)
    expect(body.diagnoses).toEqual([{ icdCode: 'Z00', icdName: '模拟诊断', prefix: null, suffix: null,
      certainty: null, diagSystem: 'ICD10', customName: null }])
    expect(body.templateId).toBe(71); expect(body.fields).toEqual({ f: '尚未保存的结构化值' })
    first.wire.reply({ id: 110, doctorId: 3 }); expect((await first.result).value).toBe(true)
    expect(h.requests.filter(x => x.config.method === 'get').map(x => x.config.url)).toEqual(['/cdss/suggestions'])
    expect(writes(h)).toHaveLength(1); expect(h.v.currentEmrId.value).toBe(110); tokenKept(h)
  })
  it.each([{}, { f: '' }, { f: null }, { f: [] }])('unused structured values %j keep original free-text JSON without templateId/fields', async values => {
    const h = await setup('OUTP'); h.v.structValues.value = values
    const first = await h.begin('save'), body = JSON.parse(first.wire.config.data)
    expect(body).not.toHaveProperty('templateId'); expect(body).not.toHaveProperty('fields')
    first.wire.reply({ id: 110, doctorId: 3 }); expect((await first.result).value).toBe(true); tokenKept(h)
  })
  it('401 retains the original expiry behavior while preserving the current unsaved structured form', async () => {
    const h = await setup('OUTP'), before = outState(h), first = await h.begin('save')
    first.wire.reply(null, 1000, 401); expect((await first.result).ok).toBe(false)
    expect(outState(h)).toEqual(before); expect(h.storage.has('hip_token')).toBe(false)
    expect(h.router.push).toHaveBeenCalledWith('/login'); expect(h.ElMessage.warning).toHaveBeenCalledWith('登录已过期，请重新登录')
    expect(h.ElMessage.success).not.toHaveBeenCalled(); expect(h.v.savingEmr.value).toBe(false)
  })
})

describe('v145 original inpatient free-text consumer remains compatible', () => {
  for (const failure of failures) {
    it(`${failure.name}: existing addRecord preserves draft and token without refresh or false success`, async () => {
      const h = await setup('INP'), before = inpState(h), first = await h.begin('add')
      expect(first.wire.config).toMatchObject({ method: 'post', url: '/inpatient/admissions/21/records' })
      expect(JSON.parse(first.wire.config.data)).toEqual({ recordType: 'PROGRESS', title: before.title, content: before.content })
      first.wire.reply(null, failure.code, failure.status); expect((await first.result).ok).toBe(false)
      expect(inpState(h)).toEqual(before); expect(h.ElMessage.success).not.toHaveBeenCalled()
      expect(h.ElMessage.error).toHaveBeenCalledTimes(1); expect(h.requests).toHaveLength(1); tokenKept(h)
    })
  }
  it('the existing page submits free text with no invented structured keys and refreshes its own admission after success', async () => {
    const h = await setup('INP'), first = await h.begin('add'), body = JSON.parse(first.wire.config.data)
    expect(Object.keys(body).sort()).toEqual(['content', 'recordType', 'title'])
    const saved = { ...record, id: 211, content: body.content, title: body.title }; h.records.unshift(saved)
    first.wire.reply(saved); expect((await first.result).ok).toBe(true)
    expect(h.v.recordContent.value).toBe(''); expect(h.v.recordTitle.value).toBe(''); expect(h.v.emrTemplateId.value).toBeNull()
    expect(h.v.records.value[0]).toEqual(saved); expect(writes(h)).toHaveLength(1)
    expect(h.ElMessage.success).toHaveBeenCalledWith('已暂存（未签名）'); tokenKept(h)
  })
})
