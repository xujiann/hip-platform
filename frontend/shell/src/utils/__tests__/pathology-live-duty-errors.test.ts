import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as Vue from 'vue'

// Run the entire actual setup script. Only HTTP, user/dialog and component
// lifecycle are controlled boundaries; no production handler is reconstructed.
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const response = (data: unknown) => Promise.resolve({ data: { code: 0, data: copy(data) } })
const sample = { id: 11, barcode: 'PB11', path_no: '2026-C-000011', status: 'RECEIVED', received_at: '2026-10-27', rejected_at: null, diagnosed_at: null, report_issued_at: null, block_count: 0 }
const block = { id: 21, specimen_id: 11, block_code: 'PB11-1', embedded_at: null }
const slide = { id: 31, specimen_id: 11, block_id: 21, stained_at: null }
const order = { id: 41, specimen_id: 11, status: 'ORDERED', tech_type: 'RESAMPLE' }
const tails: Record<string, string> = {
  RegistryPanel: 'saving, pending, specimens, regForm, regDialog, regResult, resultDialog, receiveDialog, rejectDialog, receiveForm, rejectForm, loadPending, loadSpecimens, openRegister, submitRegister, openReceive, submitReceive, openReject, submitReject',
  GrossingPanel: 'saving, rows, current, dialog, mode, form, result, resultDialog, existingGross, load, openGrossing, openRevise, submit, submitRevise',
  ProcessingPanel: 'saving, blocks, slides, batches, selectedBlocks, selectedSlides, batchNo, batchQuality, slideForm, stainForm, currentBlock, currentSlide, slideDialog, stainDialog, loadBlocks, loadSlides, loadBatches, openSlide, openStain, submitBatch, embed, submitSlide, submitStain, submitBatchComplete',
  TechOrderPanel: 'saving, rows, load, done, cancel',
  DiagnosisPanel: 'saving, rows, specimenId, specimen, drawer, primary, diagnoseDialog, diagnoseForm, supplementDialog, supplementForm, techDialog, techForm, techRows, openReport, loadReport, loadTech, openDiagnose, submitDiagnose, firstSign, secondSign, issue, openSupplement, submitSupplement, openTech, submitTech, techDone, techCancel',
}
const cases = [
  ['RegistryPanel', 'submitRegister', 5201], ['RegistryPanel', 'submitReceive', 5208], ['RegistryPanel', 'submitReject', 5208],
  ['GrossingPanel', 'submit', 5221], ['GrossingPanel', 'submitRevise', 5221],
  ['ProcessingPanel', 'submitBatch', 5241], ['ProcessingPanel', 'embed', 5241], ['ProcessingPanel', 'submitSlide', 5241], ['ProcessingPanel', 'submitStain', 5245], ['ProcessingPanel', 'submitBatchComplete', 5245],
  ['TechOrderPanel', 'done', 5268], ['TechOrderPanel', 'cancel', 5268],
  ['DiagnosisPanel', 'submitDiagnose', 4553], ['DiagnosisPanel', 'firstSign', 5261], ['DiagnosisPanel', 'secondSign', 5261], ['DiagnosisPanel', 'issue', 5261], ['DiagnosisPanel', 'submitSupplement', 5261], ['DiagnosisPanel', 'submitTech', 5261], ['DiagnosisPanel', 'techDone', 5268], ['DiagnosisPanel', 'techCancel', 5268],
] as const
const scopes: ReturnType<typeof Vue.effectScope>[] = []
afterEach(() => { for (const scope of scopes.splice(0)) scope.stop() })
async function turns() { for (let n = 0; n < 20; n++) await Promise.resolve() }

function harness(name: string, action: string, error?: unknown) {
  const path = process.env.HIP_V137_FRONT_BASELINE_ROOT
    ? resolve(process.env.HIP_V137_FRONT_BASELINE_ROOT, `${name}.vue`)
    : new URL(`../../views/medtech/pathology/${name}.vue`, import.meta.url)
  const component = readFileSync(path, 'utf8')
  const script = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const parsed = ts.createSourceFile(name + '.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const actual = parsed.statements.filter(x => !ts.isImportDeclaration(x)).map(x => x.getText(parsed)).join('\n')
  const diagnosed = ['firstSign', 'secondSign', 'issue', 'submitSupplement'].includes(action)
  const report = { specimen: { ...sample, ...(diagnosed ? { status: 'DIAGNOSED', diagnosed_at: '2026-10-27' } : {}) }, hasPrimary: diagnosed,
    primary: { grossFinding: '模拟既有大体', microFinding: '模拟既有镜下', diagnosis: diagnosed ? '模拟既有诊断' : null,
      firstSignerId: action === 'firstSign' ? null : 9, firstSignedAt: action === 'firstSign' ? null : '2026-10-27',
      secondSignerId: action === 'issue' ? 8 : null, secondSignedAt: action === 'issue' ? '2026-10-27' : null },
    supplements: [], process: [], doubleSignGate: 'warn', truncated: false }
  const get = vi.fn((url: string) => response(url.endsWith('/reports') ? report
    : /grossing\/\d+$/.test(url) ? { grossFindingPresent: true, grossFinding: '模拟既有大体', fields: [], revisions: [], textRevisionSeq: 1 }
      : url.endsWith('/blocks') ? { items: [block] } : url.endsWith('/slides/search') ? { items: [slide] }
        : url.endsWith('/tech-orders') ? { items: [order] } : url.endsWith('/specimens/search') ? { items: [{ ...sample, status: 'COLLECTED' }] }
          : url.endsWith('/worklist') ? { items: [sample] } : { items: [] }))
  const write = () => error === undefined
    ? response({ id: 101, seqNo: 1, warnings: [], moved: [], blocks: [], doneRemark: '模拟完成', reportIssuedAt: '2026-10-27' })
    : Promise.reject(error)
  const put = vi.fn(write), post = vi.fn(write), emit = vi.fn(), dispose: (() => void)[] = []
  const box: Record<string, any> = { ...Vue, onMounted: vi.fn(), onUnmounted: (fn: () => void) => dispose.push(fn),
    defineEmits: () => emit, defineExpose: vi.fn(), useAuthStore: () => ({ user: Vue.reactive({ roles: ['DOCTOR_OUTP'], id: 7 }) }),
    client: { get, put, post }, num: (v: unknown) => Number(v) || 0, fmt: String, fmtTime: String, fmtDateTime: String,
    ElMessage: { success: vi.fn(), warning: vi.fn(), info: vi.fn(), error: vi.fn() },
    ElMessageBox: { prompt: vi.fn(() => Promise.resolve({ value: '模拟取消理由' })), confirm: vi.fn(() => Promise.resolve('confirm')), alert: vi.fn(() => Promise.resolve()) } }
  const scope = Vue.effectScope(); scopes.push(scope)
  scope.run(() => runInNewContext(ts.transpileModule(actual + `\nglobalThis.view = { ${tails[name]} };`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box))
  return { v: box.view as Record<string, any>, get, put, post, emit, box, dispose: () => dispose.forEach(fn => fn()) }
}
type Harness = ReturnType<typeof harness>
async function prepare(h: Harness, name: string, action: string) {
  const v = h.v
  if (name === 'RegistryPanel') {
    await v.loadPending(); await v.loadSpecimens()
    if (action === 'submitRegister') { v.openRegister({ source: 'OUTP', order_id: 101, patient_name: '模拟患者', item_name: '模拟病理' }, false); v.regForm.specimenDesc = '模拟送检草稿' }
    if (action === 'submitReceive') { v.openReceive({ ...sample, status: 'COLLECTED' }); v.receiveForm.remark = '模拟核收草稿' }
    if (action === 'submitReject') { v.openReject({ ...sample, status: 'COLLECTED' }); v.rejectForm.reason = '模拟拒收草稿' }
  }
  if (name === 'GrossingPanel') { await v.load(); if (action === 'submit') v.openGrossing(sample); else await v.openRevise(sample); v.form.grossText = '模拟取材草稿' }
  if (name === 'ProcessingPanel') {
    await v.loadBlocks(); await v.loadSlides(); await v.loadBatches()
    if (action === 'submitBatch') { v.selectedBlocks.value = [block]; v.batchNo.value = '模拟批次' }
    if (action === 'submitSlide') await v.openSlide(block)
    if (action === 'submitStain') v.openStain(slide)
    if (action === 'submitBatchComplete') v.selectedSlides.value = [slide]
  }
  if (name === 'TechOrderPanel') await v.load()
  if (name === 'DiagnosisPanel') {
    v.openReport(11); await v.loadReport(); await v.loadTech()
    if (action === 'submitDiagnose') { v.openDiagnose(); v.diagnoseForm.diagnosis = '模拟诊断草稿' }
    if (action === 'submitSupplement') { v.openSupplement(); v.supplementForm.content = '模拟补充草稿'; v.supplementForm.reason = '模拟追加依据' }
    if (action === 'submitTech') { v.openTech(); v.techForm.techType = 'RESAMPLE'; v.techForm.reason = '模拟特检草稿' }
  }
  await turns(); h.get.mockClear(); h.emit.mockClear()
  for (const fn of Object.values(h.box.ElMessage) as any[]) fn.mockClear()
}
function invoke(h: Harness, action: string) {
  return h.v[action](action === 'embed' ? block : action === 'done' || action === 'cancel' || action === 'techDone' || action === 'techCancel' ? order : undefined)
}
function state(h: Harness) {
  return copy(Object.fromEntries(Object.entries(h.v).filter(([key, value]) => key !== 'saving' && typeof value !== 'function')
    .map(([key, value]) => [key, Vue.isRef(value) ? value.value : value])))
}
const denied = () => Object.assign(new Error('共享client已提示无该功能权限'), { response: { status: 403, data: { code: 1005 } } })

describe.each(cases)('%s/%s current-duty rejection', (name, action, conflict) => {
  it('consumes exact HTTP403/code1005 without changing draft, rows or selection', async () => {
    const h = harness(name, action, denied()); await prepare(h, name, action); const before = state(h)
    await expect(invoke(h, action)).resolves.toBeUndefined()
    expect(h.put.mock.calls.length + h.post.mock.calls.length).toBe(1)
    expect(state(h)).toEqual(before); expect(h.v.saving.value).toBe(false)
    expect(h.get).not.toHaveBeenCalled(); expect(h.emit).not.toHaveBeenCalled()
    for (const fn of Object.values(h.box.ElMessage)) expect(fn).not.toHaveBeenCalled()
  })
  it.each([
    ['other403', { response: { status: 403, data: { code: 1999 } } }],
    ['401', { response: { status: 401, data: { code: 1000 } } }],
    ['HTTP200/code1005', { response: { status: 200, data: { code: 1005 } } }],
    ['business1005', { bizCode: 1005 }],
    ['network', new Error('模拟网络故障')],
  ])('preserves propagation of %s and restores its guard', async (_label, error) => {
    const h = harness(name, action, error); await prepare(h, name, action); const before = state(h)
    await expect(invoke(h, action)).rejects.toBe(error)
    expect(h.put.mock.calls.length + h.post.mock.calls.length).toBe(1)
    expect(state(h)).toEqual(before); expect(h.v.saving.value).toBe(false)
    expect(h.get).not.toHaveBeenCalled(); expect(h.emit).not.toHaveBeenCalled()
  })
  it('retains its original business-conflict refresh without a success receipt', async () => {
    const h = harness(name, action, { bizCode: conflict }); await prepare(h, name, action)
    await expect(invoke(h, action)).resolves.toBeUndefined()
    expect(h.put.mock.calls.length + h.post.mock.calls.length).toBe(1)
    expect(h.get.mock.calls.length).toBeGreaterThan(0); expect(h.v.saving.value).toBe(false)
    expect(h.emit).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
  })
  it('retains a legal successful write and its original completion path', async () => {
    const h = harness(name, action); await prepare(h, name, action)
    await expect(invoke(h, action)).resolves.toBeUndefined()
    expect(h.put.mock.calls.length + h.post.mock.calls.length).toBe(1)
    expect(h.v.saving.value).toBe(false)
    expect(h.get.mock.calls.length).toBeGreaterThan(0)
  })
})
