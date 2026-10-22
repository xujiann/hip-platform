import { expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { computed, reactive, ref } from 'vue'

// Actual setup scripts run in a VM; HTTP, confirmation and lifecycle are controlled seams.
const response = (data: any) => Promise.resolve({ data: { data: structuredClone(data) } })
function deferred() {
  let raw!: (data: any) => void, reject!: (reason: any) => void
  const promise = new Promise<any>((yes, no) => { raw = yes; reject = no })
  return { promise, raw, resolve: (data: any) => raw({ data: { data } }), reject }
}
const specimen = { id: 11, barcode: 'PB11', received_at: '2026-10-22', rejected_at: null, diagnosed_at: null, report_issued_at: null, status: 'RECEIVED', block_count: 0 }
const block = { id: 21, specimen_id: 11, block_code: 'PB11-1', embedded_at: null }
const slide = { id: 31, specimen_id: 11, block_id: 21, stained_at: null }
const order = { id: 41, specimen_id: 11, status: 'ORDERED', tech_type: 'RESAMPLE' }
const other = { ...specimen, id: 12, barcode: 'PB12' }
const otherBlock = { ...block, id: 22, specimen_id: 12 }
const otherSlide = { ...slide, id: 32, specimen_id: 12 }
const report = (id = 11) => ({ specimen: { ...specimen, id, barcode: `PB${id}` }, primary: {}, hasPrimary: false, supplements: [], process: [], doubleSignGate: 'warn' })
const grossData = (text = '原取材描述') => ({ grossFindingPresent: true, grossFinding: text, fields: [], revisions: [], textRevisionSeq: 1 })
const settle = (p: Promise<any>) => p.catch(() => {})
const tails: Record<string, string> = {
  GrossingPanel: 'rows, load, current, dialog, mode, form, saving, result, resultDialog, existingGross, loadExistingGross, resampleOrders, loadResampleOrders, openGrossing, openRevise, canRevise, submit, submitRevise, openView, view',
  ProcessingPanel: 'blocks, slides, batches, selectedBlocks, selectedSlides, blockQuery, slideQuery, batchNo, batchQuality, batchStainItem, saving, slideForm, stainForm, currentBlock, currentSlide, slideDialog, stainDialog, techOrderOptions, openSlide, openStain, submitBatch, embed, submitSlide, submitStain, submitBatchComplete, loadBlocks, loadSlides, loadBatches, notifyTechProgress',
  TechOrderPanel: 'rows, load, done, cancel, saving: typeof saving === "undefined" ? ref(false) : saving',
  DiagnosisPanel: 'rows, specimen, specimenId, drawer, saving, techRows, techDialog, techForm, specimenBlocks, openReport, loadReport, loadTech, submitTech, techDone, techCancel, openTech: () => { if (typeof openTech === "function") openTech(); else techDialog.value = true }',
}
function harness(name: string, roles = ['TECHNICIAN'], put = vi.fn(() => response({ doneRemark: '已出块 2', warnings: [], requested: 1, completed: 1, specimenCount: 1 })), post = vi.fn(() => response({ blockCount: 1, slideCount: 1, blocks: [], warnings: [] })), getOverride?: any) {
  const component = readFileSync(new URL(`../../views/medtech/pathology/${name}.vue`, import.meta.url), 'utf8')
  const script = component.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import[\s\S]*?from ['"][^'"]+['"]\r?$/gm, '')
  const dispose: Array<() => void> = [], user = reactive({ roles, id: 7 })
  const get = getOverride ?? vi.fn((path: string) => response(path.endsWith('/reports') ? report(Number(path.split('/')[3])) : /grossing\/\d+$/.test(path) ? grossData() : path.endsWith('/blocks') ? { items: [block, otherBlock] } : path.endsWith('/slides/search') ? { items: [slide, otherSlide] } : path.endsWith('/tech-orders') ? { items: [order] } : path.endsWith('/worklist') ? { items: [specimen, other] } : { items: [] }))
  const box: Record<string, any> = { ref, reactive, computed, onUnmounted: (f: () => void) => dispose.push(f), defineEmits: () => vi.fn(), defineExpose: () => {},
    useAuthStore: () => ({ user }), client: { get, put, post }, num: (v: any) => Number(v) || 0, fmt: String, fmtTime: String, fmtDateTime: String,
    ElMessage: { success: vi.fn(), warning: vi.fn(), info: vi.fn(), error: vi.fn() }, ElMessageBox: { prompt: vi.fn(() => Promise.resolve({ value: '模拟取消原因' })), alert: vi.fn(() => Promise.resolve()), confirm: vi.fn(() => Promise.resolve('confirm')) } }
  const buttons = Array.from(component.matchAll(/<el-button\b([^>]+)>/g))
  const binding = (action: string, attr: string, fallback: string) => buttons.find(m => m[1].includes(`@click="${action}"`))?.[1].match(new RegExp(`${attr}="([^"]+)"`))?.[1] ?? fallback
  const unwrap = (text: string) => text.replace(/\b(canProcess|canTech|canDiagnose|saving|selectedBlocks|selectedSlides|reportUnavailable)\b/g, '$1.value')
  const actions = ['openGrossing(row)', 'openRevise(row)', 'submit', 'submitRevise', 'submitBatch', 'embed(row)', 'openSlide(row)', 'submitSlide', 'openStain(row)', 'submitStain', 'submitBatchComplete', 'done(row)', 'cancel(row)', 'submitTech', 'techDone(row)', 'techCancel(row)']
  const expressions = actions.map(a => `${JSON.stringify(a)}: row => (${unwrap(binding(a, 'v-if', 'true'))}) && !(${unwrap(binding(a, ':disabled', 'false'))})`).join(',')
  runInNewContext(ts.transpileModule(script + `\nglobalThis.view = {${tails[name]}}; globalThis.emits = emit; globalThis.template = {${expressions}};`, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box)
  return { v: box.view, template: box.template, get, put, post, box, user, dispose: () => dispose.forEach(f => f()) }
}
async function ready(h: ReturnType<typeof harness>, name: string) {
  if (name === 'GrossingPanel') await h.v.load()
  if (name === 'ProcessingPanel') { await h.v.loadBlocks(); await h.v.loadSlides(); await h.v.loadBatches() }
  if (name === 'TechOrderPanel') await h.v.load()
  if (name === 'DiagnosisPanel') { h.v.openReport(11); await h.v.loadReport(); await h.v.loadTech() }
  h.get.mockClear()
}
async function prepare(h: ReturnType<typeof harness>, name: string, action: string) {
  await ready(h, name)
  if (action === 'submit') { h.v.openGrossing(specimen); h.v.form.grossText = '模拟取材' }
  if (action === 'submitRevise') { await h.v.openRevise(specimen); h.v.form.grossText = '模拟修订' }
  if (action === 'submitBatch') { h.v.selectedBlocks.value = [block]; h.v.batchNo.value = '模拟批次' }
  if (action === 'submitSlide') await h.v.openSlide(block)
  if (action === 'submitStain') h.v.openStain(slide)
  if (action === 'submitBatchComplete') h.v.selectedSlides.value = [slide]
  if (action === 'submitTech') { h.v.openTech(); h.v.techForm.techType = 'RESAMPLE' }
  h.get.mockClear()
}
const cases = [['GrossingPanel', 'submit', specimen], ['GrossingPanel', 'submitRevise', specimen], ['ProcessingPanel', 'submitBatch', block], ['ProcessingPanel', 'embed', block], ['ProcessingPanel', 'submitSlide', block], ['ProcessingPanel', 'submitStain', slide], ['ProcessingPanel', 'submitBatchComplete', slide], ['TechOrderPanel', 'done', order], ['TechOrderPanel', 'cancel', order], ['DiagnosisPanel', 'submitTech', order], ['DiagnosisPanel', 'techDone', order], ['DiagnosisPanel', 'techCancel', order]] as const
for (const [name, action, row] of cases) {
  for (const role of ['ADMIN', 'TECHNICIAN', 'DOCTOR_OUTP']) it(`${name}/${action} retains ${role} collaboration and existing response facts`, async () => {
    const h = harness(name, [role]); await prepare(h, name, action); await h.v[action](row)
    expect(h.put.mock.calls.length + h.post.mock.calls.length).toBe(1)
    if (['done', 'techDone'].includes(action)) expect(h.box.ElMessage.success).toHaveBeenCalledWith('已出块 2')
  })
  it(`${name}/${action} denies wrong role in template and direct function`, async () => {
    const h = harness(name); await prepare(h, name, action); h.user.roles = ['NURSE']
    expect(h.template[action + (['embed', 'done', 'cancel', 'techDone', 'techCancel'].includes(action) ? '(row)' : '')](row)).toBe(false)
    await h.v[action](row); expect(h.put).not.toHaveBeenCalled(); expect(h.post).not.toHaveBeenCalled()
  })
  it(`${name}/${action} occupies synchronous guard through response and refresh`, async () => {
    const write = deferred(), h = harness(name, undefined, vi.fn(() => write.promise), vi.fn(() => write.promise)); await prepare(h, name, action)
    const first = h.v[action](row); const second = h.v[action](row)
    await Promise.resolve(); expect(h.put.mock.calls.length + h.post.mock.calls.length).toBeLessThanOrEqual(1)
    expect(h.v.saving.value).toBe(true); write.resolve({ warnings: [], blocks: [] }); await Promise.all([first, second]); expect(h.v.saving.value).toBe(false)
  })
  it(`${name}/${action} ignores late completion after unmount`, async () => {
    const write = deferred(), h = harness(name, undefined, vi.fn(() => write.promise), vi.fn(() => write.promise)); await prepare(h, name, action)
    const first = h.v[action](row); await Promise.resolve(); h.dispose(); write.resolve({ warnings: [], blocks: [] }); await first
    expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.box.ElMessage.warning).not.toHaveBeenCalled(); expect(h.get).not.toHaveBeenCalled(); expect(h.box.emits).not.toHaveBeenCalled()
  })
  const code = name === 'GrossingPanel' ? 5221 : name === 'ProcessingPanel' ? 5241 : 5261
  it(`${name}/${action} ${code} refreshes actual state without success`, async () => {
    const h = harness(name, undefined, vi.fn(() => Promise.reject({ bizCode: code })), vi.fn(() => Promise.reject({ bizCode: code }))); await prepare(h, name, action)
    await settle(h.v[action](row)); expect(h.get.mock.calls.length).toBeGreaterThan(0); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
  })
}
for (const action of ['submit', 'submitRevise', 'submitSlide', 'submitStain', 'submitTech']) it(`${action} requires its original open dialog`, async () => {
  const name = action === 'submit' || action === 'submitRevise' ? 'GrossingPanel' : action === 'submitTech' ? 'DiagnosisPanel' : 'ProcessingPanel'
  const h = harness(name); await prepare(h, name, action); h.v[action === 'submitSlide' ? 'slideDialog' : action === 'submitStain' ? 'stainDialog' : action === 'submitTech' ? 'techDialog' : 'dialog'].value = false
  await h.v[action](); expect(h.put).not.toHaveBeenCalled(); expect(h.post).not.toHaveBeenCalled()
})
for (const action of ['submit', 'submitRevise', 'submitSlide', 'submitStain', 'submitTech']) it(`${action} late response preserves a new draft for the same object`, async () => {
  const name = action === 'submit' || action === 'submitRevise' ? 'GrossingPanel' : action === 'submitTech' ? 'DiagnosisPanel' : 'ProcessingPanel'
  const write = deferred(), h = harness(name, undefined, vi.fn(() => write.promise), vi.fn(() => write.promise)); await prepare(h, name, action)
  const first = h.v[action](); const dialogKey = action === 'submitSlide' ? 'slideDialog' : action === 'submitStain' ? 'stainDialog' : action === 'submitTech' ? 'techDialog' : 'dialog'
  h.v[dialogKey].value = false
  if (action === 'submit') h.v.openGrossing(specimen)
  if (action === 'submitRevise') await h.v.openRevise(specimen)
  if (action === 'submitSlide') await h.v.openSlide(block)
  if (action === 'submitStain') h.v.openStain(slide)
  if (action === 'submitTech') h.v.openTech()
  h.get.mockClear(); write.resolve({ warnings: [], blocks: [] }); await first
  expect(h.v[dialogKey].value).toBe(true); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('grossing keeps explicit append legal after diagnosis and issue', async () => {
  const h = harness('GrossingPanel'); await ready(h, 'GrossingPanel'); const issued = { ...specimen, diagnosed_at: '2026-10-22', report_issued_at: '2026-10-22', block_count: 2 }
  h.v.rows.value = [issued]; h.v.openGrossing(issued); h.v.form.append = true; h.v.form.grossText = '补取材'; await h.v.submit()
  expect(h.post.mock.calls[0][1]).toMatchObject({ specimenId: 11, append: true, grossText: '补取材' }); expect(h.v.canRevise(issued)).toBe(false)
})
it('grossing rejected or not received specimens cannot be opened by direct function', async () => {
  const h = harness('GrossingPanel'); await ready(h, 'GrossingPanel')
  h.v.openGrossing({ ...specimen, rejected_at: '2026-10-22' }); expect(h.v.dialog.value).toBe(false)
  h.v.openGrossing({ ...specimen, received_at: null }); expect(h.v.dialog.value).toBe(false)
})
it('issued legacy row without diagnosed_at cannot revise original text', async () => {
  const h = harness('GrossingPanel'); await ready(h, 'GrossingPanel'); expect(h.v.canRevise({ ...specimen, report_issued_at: '2026-10-22' })).toBe(false)
})
for (const operation of ['done', 'cancel', 'techDone', 'techCancel']) it(`${operation} does not write a foreign or stale order`, async () => {
  const name = operation.startsWith('tech') ? 'DiagnosisPanel' : 'TechOrderPanel', h = harness(name); await ready(h, name)
  await h.v[operation]({ ...order, id: 99 }); await h.v[operation]({ ...order, status: 'DONE' }); expect(h.put).not.toHaveBeenCalled()
})
for (const name of ['TechOrderPanel', 'DiagnosisPanel']) for (const change of ['role', 'status', 'unmount']) it(`${name} cancellation rechecks ${change} after prompt`, async () => {
  const h = harness(name), confirm = deferred(); await ready(h, name); h.box.ElMessageBox.prompt.mockImplementation(() => confirm.promise)
  const first = h.v[name === 'TechOrderPanel' ? 'cancel' : 'techCancel'](order)
  if (change === 'role') h.user.roles = ['CASHIER']
  if (change === 'status') h.v[name === 'TechOrderPanel' ? 'rows' : 'techRows'].value = [{ ...order, status: 'DONE' }]
  if (change === 'unmount') h.dispose()
  confirm.raw({ value: '模拟取消' }); await first; expect(h.put).not.toHaveBeenCalled()
})
it('processing all five write entries share one guard', async () => {
  const write = deferred(), h = harness('ProcessingPanel', undefined, vi.fn(() => write.promise), vi.fn(() => write.promise)); await prepare(h, 'ProcessingPanel', 'submitSlide')
  h.v.openStain(slide); h.v.selectedBlocks.value = [block]; h.v.selectedSlides.value = [slide]; h.v.batchNo.value = '模拟批次'
  const first = h.v.submitSlide(), others = [h.v.embed(block), h.v.submitStain(), h.v.submitBatch(), h.v.submitBatchComplete()]
  expect(h.post).toHaveBeenCalledOnce(); expect(h.put).not.toHaveBeenCalled(); write.resolve({ warnings: [] }); await first; await Promise.all(others)
})
it('single stain keeps original specimen when notifying progress after switching drafts', async () => {
  const write = deferred(), h = harness('ProcessingPanel', undefined, vi.fn(() => write.promise)); await prepare(h, 'ProcessingPanel', 'submitStain')
  const first = h.v.submitStain(); h.v.stainDialog.value = false; h.v.openStain(otherSlide); write.resolve({ warnings: [] }); await first
  const progress = h.get.mock.calls.find((c: any[]) => c[0].endsWith('/tech-orders')); expect(progress?.[1]?.params).toMatchObject({ specimenId: 11, slideId: 31 })
})
for (const [name, loader, key] of [['GrossingPanel', 'load', 'rows'], ['ProcessingPanel', 'loadBlocks', 'blocks'], ['ProcessingPanel', 'loadSlides', 'slides'], ['TechOrderPanel', 'load', 'rows']] as const) it(`${name}/${loader} ignores older list response and unmounted responses`, async () => {
  const h = harness(name); await ready(h, name); const older = deferred(), newer = deferred(); h.get.mockImplementationOnce(() => older.promise).mockImplementationOnce(() => newer.promise)
  const a = h.v[loader](), b = h.v[loader](); newer.resolve({ items: [{ id: 222 }] }); await b; older.resolve({ items: [{ id: 111 }] }); await a; expect(h.v[key].value).toEqual([{ id: 222 }])
  const late = deferred(); h.get.mockImplementationOnce(() => late.promise); const c = h.v[loader](); h.dispose(); late.resolve({ items: [{ id: 333 }] }); await c; expect(h.v[key].value).toEqual([{ id: 222 }])
})
it('grossing prior prefill cannot replace a new specimen draft', async () => {
  const h = harness('GrossingPanel'); await ready(h, 'GrossingPanel'); const old = deferred(); h.get.mockImplementationOnce(() => old.promise)
  const first = h.v.openRevise(specimen); h.v.openGrossing(other); old.resolve(grossData('旧标本正文')); await first
  expect(h.v.current.value.id).toBe(12); expect(h.v.mode.value).toBe('GROSSING'); expect(h.v.form.grossText).toBe('')
})
it('processing late attachment options cannot enter another block dialog', async () => {
  const h = harness('ProcessingPanel'); await ready(h, 'ProcessingPanel'); const old = deferred(); h.get.mockImplementationOnce(() => old.promise)
  const first = h.v.openSlide(block); await h.v.openSlide(otherBlock); old.resolve({ items: [{ ...order, id: 999 }] }); await first
  expect(h.v.currentBlock.value.id).toBe(22); expect(h.v.techOrderOptions.value.some((r: any) => r.id === 999)).toBe(false)
})
it('processing historical DONE/CANCELLED attachment still permits physical stain registration', async () => {
  const h = harness('ProcessingPanel'); await prepare(h, 'ProcessingPanel', 'submitStain'); await h.v.submitStain(); expect(h.put.mock.calls[0][0]).toBe('/pathology/process/slides/31/stain')
})
