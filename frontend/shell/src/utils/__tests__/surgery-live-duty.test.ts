import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as Vue from 'vue'

// Execute complete real setup scripts; only HTTP, UI dialogs and lifecycle are controlled.
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const response = (data: unknown) => Promise.resolve({ data: { code: 0, data: copy(data) } })
const scopes: ReturnType<typeof Vue.effectScope>[] = []
afterEach(() => { for (const scope of scopes.splice(0)) scope.stop() })
function deferred() {
  let resolve!: (value: unknown) => void, reject!: (error: unknown) => void
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (data: unknown) => resolve({ data: { code: 0, data } }), reject }
}
const files = { surgery: 'inpatient/SurgeryView', intraop: 'inpatient/SurgeryIntraopView', anes: 'medtech/SpecialtyView' }
const tails = {
  surgery: 'canDecide, form, dialogVisible, saveLoading, busyId, records, admissions, board, save, complete, punch, edit, editVisible, editLoading, saveEdit, cancelForm, cancelVisible, cancelLoading, submitCancel',
  intraop: 'surgeryId, tubes, transfusions, events, eventSummary, tubeSummary, transfusionSummary, tubeForm, transfusionForm, eventForm, tubeDialog, transfusionDialog, eventDialog, saving, saveTube, saveTransfusion, saveEvent, removeRow',
  anes: 'canAnes, anes, anesRecords, pacu, recordAnesLoading, recordAnes',
}
type Page = keyof typeof files
function setup(page: Page, roles = ['ADMIN']) {
  const filename = files[page].split('/').at(-1)!
  const path = process.env.HIP_V139_FRONT_BASELINE_ROOT
    ? resolve(process.env.HIP_V139_FRONT_BASELINE_ROOT, filename + '.vue')
    : new URL('../../views/' + files[page] + '.vue', import.meta.url)
  const component = readFileSync(path, 'utf8')
  const script = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const parsed = ts.createSourceFile(filename + '.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const actual = parsed.statements.filter(value => !ts.isImportDeclaration(value)).map(value => value.getText(parsed)).join('\n')
  const get = vi.fn((url: string) => response(url === '/anes/records/pacu-status' ? { latestScore: 5, canLeave: true }
    : url === '/surgery/intraop/summary' ? { tubes: [], transfusions: [], events: [], tubeSummary: {}, transfusionSummary: {}, eventSummary: [] } : []))
  const post = vi.fn((_url: string, _body?: unknown) => response({ id: 91 })), put = vi.fn((_url: string, _body?: unknown) => response(null)), remove = vi.fn((_url: string) => response(null))
  const box: Record<string, any> = { ...Vue, onMounted: vi.fn(), onUnmounted: vi.fn(),
    useAuthStore: () => ({ user: Vue.reactive({ roles, id: 7 }) }), useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }),
    client: { get, post, put, delete: remove }, fmtDateTime: String, outpTemplatesOnly: (value: unknown) => value,
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
    ElMessageBox: { confirm: vi.fn(() => Promise.resolve('confirm')), prompt: vi.fn(() => Promise.resolve({ value: '模拟术后记录' })) } }
  const scope = Vue.effectScope(); scopes.push(scope)
  scope.run(() => runInNewContext(ts.transpileModule(actual + '\nglobalThis.view = { ' + tails[page] + ' };',
    { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box))
  return { page, v: box.view as Record<string, any>, get, post, put, remove, box }
}
type Harness = ReturnType<typeof setup>
const cases = [
  ['surgery', 'save', 'post'], ['surgery', 'complete', 'put'],
  ['intraop', 'saveTube', 'post'], ['intraop', 'saveTransfusion', 'post'], ['intraop', 'saveEvent', 'post'], ['intraop', 'removeRow', 'remove'],
  ['anes', 'recordAnes', 'post'],
] as const
const row = { id: 12, status: 'REQUESTED' }
async function prepare(h: Harness) {
  if (h.page === 'surgery') {
    Object.assign(h.v.form, { admissionId: 10, procedureName: '模拟术式', anesthesiaType: '全身麻醉', scheduledAt: '' })
    h.v.dialogVisible.value = true; h.v.records.value = [row]; h.v.admissions.value = [{ id: 10 }]
  } else if (h.page === 'intraop') {
    h.v.surgeryId.value = 12
    Object.assign(h.v.tubeForm, { id: 0, tubeType: '尿管', position: '原位置', depthCm: 2, insertedAt: '', removedAt: '', remark: '原管路草稿' })
    Object.assign(h.v.transfusionForm, { id: 0, productType: 'RBC', isAuto: true, volumeMl: 200, transfusedAt: '' })
    Object.assign(h.v.eventForm, { id: 0, eventType: 'RESCUE', eventTime: '', plannedFlag: 'N', detail: '原事件草稿', remark: '原备注' })
    h.v.tubeDialog.value = h.v.transfusionDialog.value = h.v.eventDialog.value = true
    h.v.tubes.value = [{ id: 1 }]; h.v.transfusions.value = [{ id: 2 }]; h.v.events.value = [{ id: 3 }]
  } else {
    Object.assign(h.v.anes, { surgeryId: '12', phase: 'PACU', hr: '80', sbp: '120', dbp: '70', spo2: '99', stewardScore: '5' })
    await Vue.nextTick()
    h.v.anesRecords.value = [{ id: 51, hr: 81 }]; h.v.pacu.value = { latestScore: 3, canLeave: false }
  }
  h.get.mockClear()
}
function snapshot(h: Harness) {
  const names = h.page === 'surgery' ? ['form', 'dialogVisible', 'records', 'admissions', 'board']
    : h.page === 'intraop' ? ['surgeryId', 'tubeForm', 'transfusionForm', 'eventForm', 'tubeDialog', 'transfusionDialog', 'eventDialog', 'tubes', 'transfusions', 'events', 'eventSummary', 'tubeSummary', 'transfusionSummary']
      : ['anes', 'anesRecords', 'pacu']
  return copy(Object.fromEntries(names.map(name => [name, Vue.unref(h.v[name])])))
}
function invoke(h: Harness, action: string) {
  return action === 'complete' ? h.v.complete(row) : action === 'removeRow' ? h.v.removeRow('events', { id: 3 }) : h.v[action]()
}
function idle(h: Harness) {
  if (h.page === 'surgery') { expect(h.v.saveLoading.value).toBe(false); expect(h.v.busyId.value).toBeNull() }
  else if (h.page === 'intraop') expect(h.v.saving.value).toBe(false)
  else expect(h.v.recordAnesLoading.value).toBe(false)
}
const denied = () => Object.assign(new Error('共享client已提示无该功能权限'), { response: { status: 403, data: { code: 1005 } } })

describe.each(cases)('%s.%s exact current-duty denial', (page, action, method) => {
  it('consumes only the exact transport denial without changing a draft, reloading, closing or claiming success', async () => {
    const h = setup(page); await prepare(h); const before = snapshot(h)
    h[method].mockRejectedValueOnce(denied())
    await expect(invoke(h, action)).resolves.toBeUndefined()
    expect(h[method]).toHaveBeenCalledOnce(); expect(snapshot(h)).toEqual(before); expect(h.get).not.toHaveBeenCalled()
    expect(h.box.ElMessage.success).not.toHaveBeenCalled(); idle(h)
  })
  it.each([
    ['other403', { response: { status: 403, data: { code: 1999 } } }], ['401', { response: { status: 401, data: { code: 1000 } } }],
    ['HTTP200/code1005', { response: { status: 200, data: { code: 1005 } } }], ['pure-business1005', { bizCode: 1005 }],
    ['original-business-code', { bizCode: 4925 }], ['network', new Error('模拟网络故障')],
  ])('keeps %s propagation and releases original busy state', async (_label, error) => {
    const h = setup(page); await prepare(h); const before = snapshot(h); h[method].mockRejectedValueOnce(error)
    await expect(invoke(h, action)).rejects.toBe(error)
    expect(h[method]).toHaveBeenCalledOnce(); expect(snapshot(h)).toEqual(before); expect(h.get).not.toHaveBeenCalled()
    expect(h.box.ElMessage.success).not.toHaveBeenCalled(); idle(h)
  })
  it('preserves a newer in-flight draft when the earlier request receives the exact denial', async () => {
    const h = setup(page); await prepare(h); const wait = deferred(); h[method].mockImplementationOnce(() => wait.promise as any)
    const pending = invoke(h, action); await vi.waitFor(() => expect(h[method]).toHaveBeenCalledOnce())
    if (page === 'surgery') h.v.form.procedureName = '在途新术式'
    else if (page === 'intraop') { h.v.eventForm.detail = '后开新事件草稿'; h.v.tubeForm.remark = '后开新管路草稿'; h.v.transfusionForm.volumeMl = 300 }
    else { h.v.anes.hr = '91'; h.v.anes.sbp = '131' }
    const before = snapshot(h); wait.reject(denied()); await expect(pending).resolves.toBeUndefined()
    expect(snapshot(h)).toEqual(before); expect(h[method]).toHaveBeenCalledOnce(); expect(h.get).not.toHaveBeenCalled()
    expect(h.box.ElMessage.success).not.toHaveBeenCalled(); idle(h)
  })
  it('retains the original successful payload and follow-up behavior', async () => {
    const h = setup(page); await prepare(h); await invoke(h, action); expect(h[method]).toHaveBeenCalledOnce(); idle(h)
    if (action === 'save') { expect(h.post).toHaveBeenCalledWith('/inpatient/surgeries', { admissionId: 10, procedureName: '模拟术式', anesthesiaType: '全身麻醉', scheduledAt: null }); expect(h.v.dialogVisible.value).toBe(false) }
    if (action === 'complete') expect(h.put).toHaveBeenCalledWith('/inpatient/surgeries/12/complete', { opNote: '模拟术后记录', anesNote: '模拟术后记录' })
    if (action === 'saveTube') { expect(h.post.mock.calls[0][0]).toBe('/surgery/intraop/tubes'); expect(h.post.mock.calls[0][1]).toMatchObject({ surgeryId: 12, tubeType: '尿管', position: '原位置' }); expect(h.v.tubeDialog.value).toBe(false) }
    if (action === 'saveTransfusion') { expect(h.post.mock.calls[0]).toEqual(['/surgery/intraop/transfusions', { surgeryId: 12, productType: 'RBC', volumeMl: 200, isAuto: true, transfusedAt: undefined }]); expect(h.v.transfusionDialog.value).toBe(false) }
    if (action === 'saveEvent') { expect(h.post.mock.calls[0][0]).toBe('/surgery/intraop/events'); expect(h.post.mock.calls[0][1]).toMatchObject({ surgeryId: 12, eventType: 'RESCUE', planned: false, detail: '原事件草稿' }); expect(h.v.eventDialog.value).toBe(false) }
    if (action === 'removeRow') expect(h.remove).toHaveBeenCalledWith('/surgery/intraop/events/3')
    if (action === 'recordAnes') expect(h.post).toHaveBeenCalledWith('/anes/records', { surgeryId: 12, phase: 'PACU', hr: 80, sbp: 120, dbp: 70, spo2: 99, stewardScore: 5 })
    expect(h.get).toHaveBeenCalledTimes(page === 'surgery' ? 3 : page === 'anes' ? 2 : 1)
    expect(h.box.ElMessage.success).toHaveBeenCalledTimes(action === 'complete' ? 0 : 1)
  })
})

it.each(['saveTube', 'saveTransfusion', 'saveEvent'])('%s existing-child update retains its original PUT', async action => {
  const h = setup('intraop'); await prepare(h)
  h.v[{ saveTube: 'tubeForm', saveTransfusion: 'transfusionForm', saveEvent: 'eventForm' }[action]!].id = 31
  await invoke(h, action); expect(h.post).not.toHaveBeenCalled(); expect(h.put).toHaveBeenCalledOnce()
  expect(h.put.mock.calls[0][0]).toBe('/surgery/intraop/' + ({ saveTube: 'tubes', saveTransfusion: 'transfusions', saveEvent: 'events' }[action]) + '/31'); idle(h)
})
it.each(['tubes', 'transfusions', 'events'])('keeps cancelled %s delete confirmation without HTTP or a new busy flag', async kind => {
  const h = setup('intraop'); await prepare(h); const before = snapshot(h); h.box.ElMessageBox.confirm.mockRejectedValueOnce('cancel')
  await h.v.removeRow(kind, { id: 3 }); expect(h.remove).not.toHaveBeenCalled(); expect(snapshot(h)).toEqual(before); idle(h)
})
it.each(['ADMIN', 'DOCTOR_OUTP', 'NURSE', 'TECHNICIAN', 'QUALITY'])('retains original medical and anesthesia visibility for %s', role => {
  expect(setup('surgery', [role]).v.canDecide.value).toBe(['ADMIN', 'DOCTOR_OUTP'].includes(role))
  expect(setup('anes', [role]).v.canAnes.value).toBe(['ADMIN', 'DOCTOR_OUTP', 'TECHNICIAN'].includes(role))
})
it.each(['punch', 'saveEdit', 'submitCancel'])('retains existing %s catch-all instead of changing its error policy', async action => {
  const h = setup('surgery'); await prepare(h); h.v.cancelForm.reason = '原取消原因'; h.v.edit.id = 12
  h.put.mockRejectedValueOnce(new Error('原来统一消费的错误'))
  await expect(action === 'punch' ? h.v.punch(row, 'IN_ROOM') : h.v[action]()).resolves.toBeUndefined()
  expect(h.put).toHaveBeenCalledOnce(); expect(h.get).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
  expect(h.v.editLoading.value).toBe(false); expect(h.v.cancelLoading.value).toBe(false); idle(h)
})
