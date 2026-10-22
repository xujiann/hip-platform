import { expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { computed, reactive, ref } from 'vue'

// Execute the actual SFC scripts and template expressions, with controlled HTTP and dialog promises.
const response = (data: unknown) => Promise.resolve({ data: { data: structuredClone(data) } })
function deferred() {
  let resolve!: (value: any) => void, reject!: (reason: unknown) => void
  const promise = new Promise<any>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (data: unknown) => resolve({ data: { data } }), raw: resolve, reject }
}
const sample = { id: 11, barcode: 'PB11', path_no: '2026-C-000011', status: 'COLLECTED', rejected_at: null, report_issued_at: null }
const other = { ...sample, id: 12, barcode: 'PB12' }
const settle = (promise: Promise<unknown>) => promise.catch(() => {})

function harness(name: string, tail: string, get: any, put: any, post: any, roles: string[]) {
  const component = readFileSync(new URL(`../../views/medtech/${name}.vue`, import.meta.url), 'utf8')
  const source = component.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import[\s\S]*?from ['"][^'"]+['"]\r?$/gm, '')
  const dispose: Array<() => void> = [], user = reactive({ roles, id: 7 })
  const box: Record<string, any> = { ref, reactive, computed, watch: () => {}, onMounted: () => {}, onUnmounted: (f: () => void) => dispose.push(f),
    defineEmits: () => vi.fn(), defineExpose: () => {}, useAuthStore: () => ({ user }), useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }),
    client: { get, put, post }, num: (v: unknown) => Number(v) || 0, fmt: String, fmtTime: String, fmtDateTime: String, statusName: String,
    outpTemplatesOnly: (v: unknown) => v, ElMessage: { success: vi.fn(), warning: vi.fn(), info: vi.fn() },
    ElMessageBox: { confirm: vi.fn(() => Promise.resolve('confirm')), prompt: vi.fn(() => Promise.resolve({ value: '模拟原因' })) } }
  const buttons = Array.from(component.matchAll(/<el-button\b([^>]+)>/g))
  const binding = (action: string, attribute: string, fallback: string) => buttons.find(m => m[1].includes(`@click="${action}"`))?.[1].match(new RegExp(`${attribute}="([^"]+)"`))?.[1] ?? fallback
  const unwrap = (text: string) => text.replace(/\b(canRegister|saving|canTech|canPathology|pathologySaving|canDiagnose|canSign|diagnoseBlockedReason|firstSignBlockedReason|secondSignBlockedReason|issueBlockedReason|supplementBlockedReason)\b/g, '$1.value')
  const supplementClick = buttons.find(m => m[1].includes('supplementDialog = true')) ? 'supplementDialog.value = true' : 'openSupplement()'
  runInNewContext(ts.transpileModule(source + '\n' + tail.replace('__SUPPLEMENT_CLICK__', supplementClick) + `
    globalThis.template = {
      receiveVisible: row => ${unwrap(binding('openReceive(row)', 'v-if', binding('receive(row)', 'v-if', 'true')))},
      receiveDisabled: row => ${unwrap(binding('openReceive(row)', ':disabled', binding('receive(row)', ':disabled', 'false')))},
      rejectVisible: row => ${unwrap(binding('openReject(row)', 'v-if', 'true'))},
      rejectDisabled: row => ${unwrap(binding('openReject(row)', ':disabled', 'false'))},
      diagnoseDisabled: () => ${unwrap(binding('openDiagnose', ':disabled', 'false'))},
      firstDisabled: () => ${unwrap(binding('firstSign', ':disabled', 'false'))},
      secondDisabled: () => ${unwrap(binding('secondSign', ':disabled', 'false'))},
      issueDisabled: () => ${unwrap(binding('issue', ':disabled', 'false'))}
    };
  `, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box)
  return { v: box.view, box, template: box.template, get, put, post, user, component, dispose: () => dispose.forEach(f => f()) }
}

function registry(roles = ['TECHNICIAN'], put = vi.fn(() => response(null)), get = vi.fn(() => response({ items: [sample, other] }))) {
  return harness('pathology/RegistryPanel', `globalThis.view = { current, saving, receiveDialog, rejectDialog, receiveForm, rejectForm, openReceive, openReject, submitReceive, submitReject, openHistory, loadPending, loadSpecimens, specimens, pending }; globalThis.emits = emit;`, get, put, vi.fn(() => response(null)), roles)
}
async function registryReady(h: ReturnType<typeof registry>) { await h.v.loadSpecimens(); await h.v.loadPending(); h.get.mockClear() }
const registryReads = (h: ReturnType<typeof registry>) => h.get.mock.calls.filter((c: any[]) => ['/pathology/registry/pending', '/pathology/registry/specimens/search'].includes(c[0]))
for (const role of ['ADMIN', 'TECHNICIAN', 'DOCTOR_OUTP']) {
  it(`retains ${role} receipt and rejection, including physical specimens without payment status`, async () => {
    const h = registry([role]); await registryReady(h)
    h.v.openReceive(sample); Object.assign(h.v.receiveForm, { receivedAt: '2026-10-22 10:12:13', remark: '模拟一致' }); await h.v.submitReceive()
    h.v.openReject({ ...other, status: 'RECEIVED' }); Object.assign(h.v.rejectForm, { reason: '模拟未固定', rejectedAt: '2026-10-22 10:13:14' }); await h.v.submitReject()
    expect(h.put.mock.calls).toEqual([['/pathology/registry/specimens/11/receive-check', { receivedAt: '2026-10-22 10:12:13', remark: '模拟一致' }], ['/pathology/registry/specimens/12/reject', { reason: '模拟未固定', rejectedAt: '2026-10-22 10:13:14' }]])
  })
}
for (const role of ['NURSE', 'CASHIER', 'PHARMACIST', 'INTERFACE', 'TECHNICIAN_OLD']) {
  it(`hides receipt/rejection and denies direct functions for ${role}`, async () => {
    const h = registry([role]); await registryReady(h)
    expect(h.template.receiveVisible(sample) && !h.template.receiveDisabled(sample)).toBe(false)
    expect(h.template.rejectVisible(sample) && !h.template.rejectDisabled(sample)).toBe(false)
    h.v.openReceive(sample); await h.v.submitReceive(); h.v.openReject(sample); h.v.rejectForm.reason = '模拟原因'; await h.v.submitReject(); expect(h.put).not.toHaveBeenCalled()
  })
}
for (const [action, row] of [['Receive', { ...sample, status: 'RECEIVED' }], ['Receive', { ...sample, rejected_at: '2026-10-22' }], ['Reject', { ...sample, status: 'DIAGNOSED' }], ['Reject', { ...sample, rejected_at: '2026-10-22' }]] as const) {
  it(`direct ${action} rejects locally invalid ${row.status}/${row.rejected_at}`, async () => {
    const h = registry(); await registryReady(h); h.v[`open${action}`](row); h.v.rejectForm.reason = '模拟原因'; await h.v[`submit${action}`](); expect(h.put).not.toHaveBeenCalled()
  })
}
for (const action of ['Receive', 'Reject']) {
  it(`${action} requires an open dialog and checks role again at submit`, async () => {
    const h = registry(); await registryReady(h); h.v[`open${action}`](sample); h.v.rejectForm.reason = '模拟原因'; h.v[`${action.toLowerCase()}Dialog`].value = false
    await h.v[`submit${action}`](); expect(h.put).not.toHaveBeenCalled()
    h.v[`open${action}`](sample); h.v.rejectForm.reason = '模拟原因'; h.user.roles = ['NURSE']; await h.v[`submit${action}`](); expect(h.put).not.toHaveBeenCalled()
  })
  it(`${action} binds its own original specimen despite another action and history selection`, async () => {
    const h = registry(); await registryReady(h); h.v[`open${action}`](sample); h.v.rejectForm.reason = '模拟原原因'; h.v.openHistory(other)
    await h.v[`submit${action}`](); expect(h.put.mock.calls[0][0]).toBe(`/pathology/registry/specimens/11/${action === 'Receive' ? 'receive-check' : 'reject'}`)
  })
  it(`${action} success does not close a subsequently opened dialog`, async () => {
    const write = deferred(), h = registry(undefined, vi.fn(() => write.promise)); await registryReady(h); h.v[`open${action}`](sample); h.v.rejectForm.reason = '模拟原原因'; const first = h.v[`submit${action}`]()
    h.v[`${action.toLowerCase()}Dialog`].value = false; h.v[`open${action}`](other); h.v.rejectForm.reason = '模拟新原因'; write.resolve(null); await first
    expect(h.v[`${action.toLowerCase()}Dialog`].value).toBe(true); expect(h.v.rejectForm.reason).toBe('模拟新原因'); expect(registryReads(h)).toHaveLength(2)
  })
  it(`${action} holds the synchronous guard until both queue refreshes finish`, async () => {
    const read = deferred(); let slow = false; const h = registry(undefined, undefined, vi.fn(() => slow ? read.promise : response({ items: [sample] }))); await registryReady(h)
    h.v[`open${action}`](sample); h.v.rejectForm.reason = '模拟原因'; slow = true; const first = h.v[`submit${action}`](); await vi.waitFor(() => expect(registryReads(h)).toHaveLength(2))
    expect(h.v.saving.value).toBe(true); await h.v[`submit${action}`](); expect(h.put).toHaveBeenCalledOnce(); read.resolve({ items: [] }); await first; expect(h.v.saving.value).toBe(false)
  })
  it(`${action} state conflict refreshes both queues without success or closing its draft`, async () => {
    const h = registry(undefined, vi.fn(() => Promise.reject({ bizCode: 5208 }))); await registryReady(h); h.v[`open${action}`](sample); h.v.rejectForm.reason = '模拟原因'; await settle(h.v[`submit${action}`]())
    expect(registryReads(h)).toHaveLength(2); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.v[`${action.toLowerCase()}Dialog`].value).toBe(true); expect(h.v.saving.value).toBe(false)
  })
  it(`${action} completion after leaving cannot toast, refresh or emit`, async () => {
    const write = deferred(), h = registry(undefined, vi.fn(() => write.promise)); await registryReady(h); h.v[`open${action}`](sample); h.v.rejectForm.reason = '模拟原因'; const first = h.v[`submit${action}`](); h.dispose(); write.resolve(null); await first
    expect(h.get).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.box.emits).not.toHaveBeenCalled()
  })
}
it('receipt and rejection cannot overlap even through direct function calls', async () => {
  const write = deferred(), h = registry(undefined, vi.fn(() => write.promise)); await registryReady(h); h.v.openReceive(sample); h.v.openReject(other); h.v.rejectForm.reason = '模拟原因'
  const first = h.v.submitReceive(); await h.v.submitReject(); expect(h.put).toHaveBeenCalledOnce(); write.resolve(null); await first
})

function reportData(id = 11, diagnosed = false) {
  return { specimen: { ...sample, id, barcode: `PB${id}`, status: diagnosed ? 'DIAGNOSED' : 'RECEIVED' }, hasPrimary: diagnosed,
    primary: { grossFinding: '原大体所见', microFinding: '原镜下所见', diagnosis: diagnosed ? '模拟首次诊断' : null, pathologistId: 7, firstSignerId: null, firstSignedAt: null, secondSignerId: null, secondSignedAt: null },
    supplements: [], process: [], doubleSignGate: 'warn', truncated: false }
}
function report(roles = ['DOCTOR_OUTP'], put = vi.fn(() => response({ reportIssuedAt: '2026-10-22', warnings: [] })), post = vi.fn(() => response({ seqNo: 1 })), getOverride?: any) {
  const reports: Record<number, any> = { 11: reportData(), 12: reportData(12) }
  const get = getOverride ?? vi.fn((path: string) => response(path.endsWith('/reports') ? reports[Number(path.split('/')[3])] : path.endsWith('/worklist') ? { items: [sample] } : { items: [] }))
  const h = harness('pathology/DiagnosisPanel', `globalThis.view = { rows, loading, truncated, limit, query, range, load, drawer, saving, reportLoading, specimenId, specimen, primary, hasPrimary, supplements, processRows, doubleSignGate, openReport, loadReport, firstSign, secondSign, issue, diagnoseDialog, diagnoseForm, openDiagnose, submitDiagnose, supplementDialog, supplementForm, openSupplement: () => { __SUPPLEMENT_CLICK__ }, submitSupplement, techRows, specimenBlocks, priorRows, loadTech, loadPrior, loadSpecimenBlocks, submitTech, techDone, techCancel }; globalThis.emits = emit;`, get, put, post, roles)
  return { ...h, reports }
}
async function reportReady(h: ReturnType<typeof report>, diagnosed = false, id = 11) { h.reports[id] = reportData(id, diagnosed); h.v.openReport(id); await h.v.loadReport(); h.get.mockClear() }
const reportReads = (h: ReturnType<typeof report>) => h.get.mock.calls.filter((c: any[]) => c[0].endsWith('/reports') || c[0].endsWith('/worklist'))
for (const role of ['ADMIN', 'TECHNICIAN', 'DOCTOR_OUTP']) {
  it(`retains ${role} first diagnosis including existing gross/micro metadata`, async () => {
    const h = report([role]); await reportReady(h); h.v.openDiagnose(); h.v.diagnoseForm.diagnosis = '模拟首次病理诊断'; await h.v.submitDiagnose()
    expect(h.put).toHaveBeenCalledExactlyOnceWith('/pathology/specimens/PB11/diagnose', { grossFinding: '原大体所见', microFinding: '原镜下所见', diagnosis: '模拟首次病理诊断' })
  })
}
for (const role of ['ADMIN', 'DOCTOR_OUTP']) {
  for (const action of ['firstSign', 'secondSign', 'issue', 'submitSupplement']) {
    it(`retains ${role} ${action} on its legal specimen`, async () => {
      const h = report([role]); await reportReady(h, true); if (action === 'secondSign') Object.assign(h.v.primary.value, { firstSignerId: 9, firstSignedAt: '2026-10-22' })
      if (action === 'submitSupplement') { h.v.openSupplement(); h.v.supplementForm.content = '模拟追加诊断'; }
      await h.v[action](); expect(h.put.mock.calls.length + h.post.mock.calls.length).toBe(1)
    })
  }
}
for (const role of ['NURSE', 'CASHIER', 'PHARMACIST', 'INTERFACE', 'TECHNICIAN_OLD']) {
  it(`denies diagnosis and all four legal report writes to ${role}`, async () => {
    const h = report([role]); await reportReady(h); expect(h.template.diagnoseDisabled()).toBe(true); h.v.openDiagnose(); h.v.diagnoseForm.diagnosis = '模拟诊断'; await h.v.submitDiagnose()
    await reportReady(h, true); Object.assign(h.v.primary.value, { firstSignerId: 9, firstSignedAt: '2026-10-22' }); expect(h.template.issueDisabled()).toBe(true)
    await h.v.firstSign(); await h.v.secondSign(); await h.v.issue(); h.v.openSupplement(); h.v.supplementForm.content = '模拟补充'; await h.v.submitSupplement(); expect(h.put).not.toHaveBeenCalled(); expect(h.post).not.toHaveBeenCalled()
  })
}
it('a technician retains diagnosis but cannot directly sign, issue or supplement', async () => {
  const h = report(['TECHNICIAN']); await reportReady(h, true); Object.assign(h.v.primary.value, { firstSignerId: 9, firstSignedAt: '2026-10-22' })
  expect(h.template.firstDisabled()).toBe(true); expect(h.template.secondDisabled()).toBe(true); expect(h.template.issueDisabled()).toBe(true)
  await h.v.firstSign(); await h.v.secondSign(); await h.v.issue(); h.v.openSupplement(); h.v.supplementForm.content = '模拟补充'; await h.v.submitSupplement(); expect(h.put).not.toHaveBeenCalled(); expect(h.post).not.toHaveBeenCalled()
})
it('direct second signature rejects the first signer even when the gate is off', async () => {
  const h = report(); await reportReady(h, true); h.v.doubleSignGate.value = 'off'; Object.assign(h.v.primary.value, { firstSignerId: 7, firstSignedAt: '2026-10-22' }); await h.v.secondSign(); expect(h.put).not.toHaveBeenCalled()
})
it('direct issue respects block and does not offer a bypass confirmation', async () => {
  const h = report(); await reportReady(h, true); h.v.doubleSignGate.value = 'block'; await h.v.issue(); expect(h.put).not.toHaveBeenCalled(); expect(h.box.ElMessageBox.confirm).not.toHaveBeenCalled()
})
it('warn issue allows its original author after explicit acknowledgement and consumes real warnings', async () => {
  const h = report(undefined, vi.fn(() => response({ reportIssuedAt: '2026-10-22', warnings: ['模拟缺复签'] }))); await reportReady(h, true); await h.v.issue()
  expect(h.put).toHaveBeenCalledExactlyOnceWith('/pathology/report/11/issue', null); expect(h.box.ElMessageBox.confirm).toHaveBeenCalledOnce(); expect(h.box.ElMessage.warning).toHaveBeenCalledWith('模拟缺复签')
})
for (const state of [{ rejected_at: '2026-10-22' }, { report_issued_at: '2026-10-22' }]) {
  it(`direct sign and issue deny specimen ${JSON.stringify(state)}`, async () => {
    const h = report(); await reportReady(h, true); Object.assign(h.v.specimen.value, state); Object.assign(h.v.primary.value, { firstSignerId: 9, firstSignedAt: '2026-10-22' })
    await h.v.firstSign(); await h.v.secondSign(); await h.v.issue(); expect(h.put).not.toHaveBeenCalled()
  })
}
it('a rejected specimen cannot receive a direct supplementary report', async () => {
  const h = report(); await reportReady(h, true); h.v.specimen.value.rejected_at = '2026-10-22'; h.v.openSupplement(); h.v.supplementForm.content = '模拟补充'; await h.v.submitSupplement(); expect(h.post).not.toHaveBeenCalled()
})
it('a supplement remains legal before formal issue and never sends primary text', async () => {
  const h = report(); await reportReady(h, true); h.v.openSupplement(); Object.assign(h.v.supplementForm, { content: '模拟补充正文', reason: '模拟会诊回报' }); await h.v.submitSupplement()
  expect(h.post).toHaveBeenCalledExactlyOnceWith('/pathology/report/11/supplement', { content: '模拟补充正文', reason: '模拟会诊回报' })
})
it('issue owns the synchronous write guard before its confirmation and prevents double confirmation', async () => {
  const confirmation = deferred(), h = report(); await reportReady(h, true); h.box.ElMessageBox.confirm.mockImplementation(() => confirmation.promise)
  const first = h.v.issue(); const second = h.v.issue(); await h.v.firstSign(); expect(h.v.saving.value).toBe(true); expect(h.box.ElMessageBox.confirm).toHaveBeenCalledOnce(); expect(h.put).not.toHaveBeenCalled()
  confirmation.raw('confirm'); await Promise.all([first, second]); expect(h.put).toHaveBeenCalledOnce(); expect(h.v.saving.value).toBe(false)
})
for (const change of ['specimen', 'role', 'drawer', 'unmount']) {
  it(`issue confirmation cancels after a ${change} change without signing a new specimen`, async () => {
    const confirmation = deferred(), h = report(); await reportReady(h, true); h.box.ElMessageBox.confirm.mockImplementation(() => confirmation.promise); const first = h.v.issue()
    if (change === 'specimen') { h.v.openReport(12); await h.v.loadReport() }
    if (change === 'role') h.user.roles = ['TECHNICIAN']
    if (change === 'drawer') h.v.drawer.value = false
    if (change === 'unmount') h.dispose()
    confirmation.raw('confirm'); await first; expect(h.put).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.v.saving.value).toBe(false)
  })
}
for (const action of ['firstSign', 'secondSign', 'submitDiagnose', 'submitSupplement']) {
  it(`${action} uses a synchronous write guard and keeps newer drafts after late success`, async () => {
    const write = deferred(), h = report(undefined, vi.fn(() => write.promise), vi.fn(() => write.promise)); await reportReady(h, action !== 'submitDiagnose'); Object.assign(h.v.primary.value, action === 'secondSign' ? { firstSignerId: 9, firstSignedAt: '2026-10-22' } : {})
    if (action === 'submitDiagnose') { h.v.openDiagnose(); h.v.diagnoseForm.diagnosis = '模拟原诊断' }
    if (action === 'submitSupplement') { h.v.openSupplement(); h.v.supplementForm.content = '模拟原补充' }
    const first = h.v[action](); const duplicate = h.v[action](); expect(h.put.mock.calls.length + h.post.mock.calls.length).toBe(1)
    h.v.openReport(12); await h.v.loadReport(); if (action === 'submitSupplement') { h.v.hasPrimary.value = true; h.v.primary.value.diagnosis = '模拟既有诊断'; h.v.openSupplement(); h.v.supplementForm.content = '模拟新补充' } else { h.v.openDiagnose(); h.v.diagnoseForm.diagnosis = '模拟新诊断' }
    write.resolve({ seqNo: 1 }); await Promise.all([first, duplicate])
    if (action === 'submitSupplement') { expect(h.v.supplementDialog.value).toBe(true); expect(h.v.supplementForm.content).toBe('模拟新补充') } else { expect(h.v.diagnoseDialog.value).toBe(true); expect(h.v.diagnoseForm.diagnosis).toBe('模拟新诊断') }
    expect(h.v.specimenId.value).toBe(12); expect(h.v.specimen.value.id).toBe(12)
  })
}
for (const [action, code] of [['submitDiagnose', 4553], ['firstSign', 5261], ['secondSign', 5263], ['issue', 5265], ['submitSupplement', 5261]] as const) {
  it(`${action} conflict ${code} refreshes report and worklist without false success`, async () => {
    const fail = () => Promise.reject({ bizCode: code }), h = report(undefined, vi.fn(fail), vi.fn(fail)); await reportReady(h, action !== 'submitDiagnose')
    if (action === 'secondSign') Object.assign(h.v.primary.value, { firstSignerId: 9, firstSignedAt: '2026-10-22' })
    if (action === 'submitDiagnose') { h.v.openDiagnose(); h.v.diagnoseForm.diagnosis = '模拟原诊断' }
    if (action === 'submitSupplement') { h.v.openSupplement(); h.v.supplementForm.content = '模拟原补充' }
    await settle(h.v[action]()); expect(reportReads(h)).toHaveLength(2); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.box.emits).not.toHaveBeenCalled(); expect(h.v.saving.value).toBe(false)
  })
}
for (const action of ['firstSign', 'secondSign', 'issue', 'submitDiagnose', 'submitSupplement']) {
  it(`${action} completion after unmount cannot alter UI or trigger new reads`, async () => {
    const write = deferred(), h = report(undefined, vi.fn(() => write.promise), vi.fn(() => write.promise)); await reportReady(h, action !== 'submitDiagnose')
    if (action === 'secondSign') Object.assign(h.v.primary.value, { firstSignerId: 9, firstSignedAt: '2026-10-22' })
    if (action === 'submitDiagnose') { h.v.openDiagnose(); h.v.diagnoseForm.diagnosis = '模拟诊断' }
    if (action === 'submitSupplement') { h.v.openSupplement(); h.v.supplementForm.content = '模拟补充' }
    const first = h.v[action](); if (action === 'issue') await vi.waitFor(() => expect(h.put).toHaveBeenCalledOnce()); h.get.mockClear(); h.dispose(); write.resolve({ reportIssuedAt: '2026-10-22', seqNo: 1, warnings: [] }); await first
    expect(h.get).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.box.emits).not.toHaveBeenCalled()
  })
}
it('a closed drawer and stale dialog cannot directly submit diagnosis or supplement', async () => {
  const h = report(); await reportReady(h); h.v.openDiagnose(); h.v.diagnoseForm.diagnosis = '模拟诊断'; h.v.diagnoseDialog.value = false; await h.v.submitDiagnose(); expect(h.put).not.toHaveBeenCalled()
  await reportReady(h, true); h.v.openSupplement(); h.v.supplementForm.content = '模拟补充'; h.v.drawer.value = false; await h.v.submitSupplement(); expect(h.post).not.toHaveBeenCalled()
})
it('late report response cannot replace the new specimen or release its loading state', async () => {
  const old = deferred(), current = deferred(); let slow = false
  const get = vi.fn((path: string) => path.endsWith('/reports') && slow ? (path.includes('/11/') ? old.promise : current.promise) : response(path.endsWith('/reports') ? reportData() : { items: [] }))
  const h = report(undefined, undefined, undefined, get); await reportReady(h); slow = true; const first = h.v.loadReport(); h.v.openReport(12); const last = h.v.loadReport()
  old.resolve(reportData(11, true)); await first; expect(h.v.specimen.value.id).not.toBe(11); expect(h.v.reportLoading.value).toBe(true)
  current.resolve(reportData(12)); await last; expect(h.v.specimen.value.id).toBe(12); expect(h.v.reportLoading.value).toBe(false)
})
it('an in-progress report read blocks all direct writes until the actual object is loaded', async () => {
  const read = deferred(); let slow = false
  const h = report(undefined, undefined, undefined, vi.fn((path: string) => slow && path.endsWith('/reports') ? read.promise : response(path.endsWith('/reports') ? reportData(11, true) : { items: [] })))
  await reportReady(h, true); slow = true; const first = h.v.loadReport(); const sign = h.v.firstSign(); const issue = h.v.issue(); h.v.openSupplement(); h.v.supplementForm.content = '模拟补充'; const supplement = h.v.submitSupplement()
  expect(h.put).not.toHaveBeenCalled(); expect(h.post).not.toHaveBeenCalled(); read.resolve(reportData(11, true)); await Promise.all([first, sign, issue, supplement])
})
it('worklist query versions preserve the latest result and cap when requests finish out of order', async () => {
  const old = deferred(), latest = deferred(); let slow = false
  const h = report(undefined, undefined, undefined, vi.fn((_path: string, opts: any) => slow ? opts.params.keyword === '旧' ? old.promise : latest.promise : response({ items: [] }))); await h.v.load(); slow = true
  h.v.query.keyword = '旧'; const first = h.v.load(); h.v.query.keyword = '新'; const second = h.v.load(); latest.resolve({ items: [other], limit: 3, truncated: true }); await second; old.resolve({ items: [sample], limit: 5, truncated: false }); await first
  expect(h.v.rows.value).toEqual([other]); expect(h.v.limit.value).toBe(3); expect(h.v.truncated.value).toBe(true); expect(h.v.loading.value).toBe(false)
})
it('late specimen-dependent ancillary reads do not show previous patient technical orders, blocks or prior reports', async () => {
  const old = deferred(); let slow = false
  const get = vi.fn((path: string, opts?: any) => slow && !path.endsWith('/reports') && !path.endsWith('/dict') && (path.includes('/11/') || opts?.params?.specimenId === 11) ? old.promise : response(path.endsWith('/reports') ? reportData(Number(path.split('/')[3])) : { items: [] }))
  const h = report(undefined, undefined, undefined, get); await reportReady(h); slow = true; const reads = [h.v.loadTech(), h.v.loadPrior(), h.v.loadSpecimenBlocks()]; h.v.openReport(12); await h.v.loadReport(); old.resolve({ items: [{ id: 88 }], patientResolved: true }); await Promise.all(reads)
  expect(h.v.techRows.value).toEqual([]); expect(h.v.priorRows.value).toEqual([]); expect(h.v.specimenBlocks.value).toEqual([])
})

function specialty(roles = ['TECHNICIAN'], put = vi.fn(() => response(null)), get = vi.fn(() => response([sample, other]))) {
  return harness('SpecialtyView', `globalThis.view = { tab, specimens, busyId, receive, loadSpecimens };`, get, put, vi.fn(() => response(null)), roles)
}
for (const role of ['ADMIN', 'TECHNICIAN', 'DOCTOR_OUTP']) {
  it(`retains old receipt endpoint for ${role}`, async () => {
    const h = specialty([role]); h.v.tab.value = 'path'; await h.v.loadSpecimens(); await h.v.receive(h.v.specimens.value[0]); expect(h.put).toHaveBeenCalledExactlyOnceWith('/pathology/specimens/PB11/receive')
  })
}
for (const role of ['NURSE', 'CASHIER', 'PHARMACIST', 'INTERFACE']) {
  it(`old receipt is hidden and directly denied for ${role}`, async () => {
    const h = specialty([role]); h.v.tab.value = 'path'; await h.v.loadSpecimens(); expect(h.template.receiveVisible(sample) && !h.template.receiveDisabled(sample)).toBe(false); await h.v.receive(sample); expect(h.put).not.toHaveBeenCalled()
  })
}
it('old receipt denies rejected or no-longer-collected specimens', async () => {
  const h = specialty(); h.v.tab.value = 'path'; await h.v.loadSpecimens(); await h.v.receive({ ...sample, rejected_at: '2026-10-22' }); await h.v.receive({ ...sample, status: 'RECEIVED' }); expect(h.put).not.toHaveBeenCalled()
})
it('old receipt is synchronously deduplicated and retains its original barcode', async () => {
  const write = deferred(), h = specialty(undefined, vi.fn(() => write.promise)); h.v.tab.value = 'path'; await h.v.loadSpecimens(); const row = h.v.specimens.value[0]; const first = h.v.receive(row); row.barcode = 'PB99'; const duplicate = h.v.receive(other)
  expect(h.put).toHaveBeenCalledExactlyOnceWith('/pathology/specimens/PB11/receive'); write.resolve(null); await Promise.all([first, duplicate])
})
it('old receipt conflict refreshes the actual specimen queue without swallowing unrelated errors', async () => {
  const h = specialty(undefined, vi.fn(() => Promise.reject({ bizCode: 4551 }))); h.v.tab.value = 'path'; await h.v.loadSpecimens(); h.get.mockClear(); await settle(h.v.receive(sample)); expect(h.get).toHaveBeenCalledExactlyOnceWith('/pathology/specimens')
})
it('old receipt completion after leaving does not start another request', async () => {
  const write = deferred(), h = specialty(undefined, vi.fn(() => write.promise)); h.v.tab.value = 'path'; await h.v.loadSpecimens(); h.get.mockClear(); const first = h.v.receive(sample); h.dispose(); write.resolve(null); await first; expect(h.get).not.toHaveBeenCalled()
})

for (const action of ['Receive', 'Reject']) {
  it(`${action} refuses a known latest queue state or a rebound original row`, async () => {
    const h = registry(); await registryReady(h); const row = h.v.specimens.value[0]; h.v[`open${action}`](row); h.v.rejectForm.reason = '模拟原因'
    if (action === 'Receive') row.status = 'RECEIVED'; else row.rejected_at = '2026-10-22'
    await h.v[`submit${action}`](); expect(h.put).not.toHaveBeenCalled()
    h.v[`open${action}`](sample); h.v.rejectForm.reason = '模拟原因'; h.v.specimens.value = [{ ...sample, status: 'DIAGNOSED' }]; await h.v[`submit${action}`](); expect(h.put).not.toHaveBeenCalled()
  })
  it(`${action} leaves validation and authorization errors visible without queue reload`, async () => {
    const error = { bizCode: 1005 }, h = registry(undefined, vi.fn(() => Promise.reject(error))); await registryReady(h); h.v[`open${action}`](sample); h.v.rejectForm.reason = '模拟原因'
    await expect(h.v[`submit${action}`]()).rejects.toBe(error); expect(h.get).not.toHaveBeenCalled(); expect(h.v.saving.value).toBe(false)
  })
}
for (const action of ['firstSign', 'secondSign', 'issue', 'submitDiagnose', 'submitSupplement']) {
  it(`a direct ${action} after unmount cannot use cached computed permissions`, async () => {
    const h = report(); await reportReady(h, action !== 'submitDiagnose'); if (action === 'secondSign') Object.assign(h.v.primary.value, { firstSignerId: 9, firstSignedAt: '2026-10-22' })
    if (action === 'submitDiagnose') { h.v.openDiagnose(); h.v.diagnoseForm.diagnosis = '模拟诊断' }
    if (action === 'submitSupplement') { h.v.openSupplement(); h.v.supplementForm.content = '模拟补充' }
    h.dispose(); await h.v[action](); expect(h.put).not.toHaveBeenCalled(); expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessageBox.confirm).not.toHaveBeenCalled()
  })
  it(`${action} holds its guard through both refreshes even when one read fails`, async () => {
    const read = deferred(); let slow = false
    const h = report(undefined, undefined, undefined, vi.fn((path: string) => slow ? path.endsWith('/worklist') ? Promise.reject(new Error('模拟列表失败')) : read.promise : response(path.endsWith('/reports') ? reportData(11, action !== 'submitDiagnose') : { items: [] })))
    await reportReady(h, action !== 'submitDiagnose'); if (action === 'secondSign') Object.assign(h.v.primary.value, { firstSignerId: 9, firstSignedAt: '2026-10-22' })
    if (action === 'submitDiagnose') { h.v.openDiagnose(); h.v.diagnoseForm.diagnosis = '模拟诊断' }
    if (action === 'submitSupplement') { h.v.openSupplement(); h.v.supplementForm.content = '模拟补充' }
    slow = true; const first = h.v[action](); await vi.waitFor(() => expect(reportReads(h)).toHaveLength(2)); expect(h.v.saving.value).toBe(true)
    const duplicate = h.v[action](); expect(h.put.mock.calls.length + h.post.mock.calls.length).toBe(1); read.resolve(reportData(11, true)); await Promise.all([first, duplicate]); expect(h.v.saving.value).toBe(false)
  })
}
for (const action of ['submitDiagnose', 'submitSupplement']) {
  it(`late failed ${action} does not close, clear, or reload another specimen's draft`, async () => {
    const write = deferred(), h = report(undefined, vi.fn(() => write.promise), vi.fn(() => write.promise)); await reportReady(h, action === 'submitSupplement')
    if (action === 'submitDiagnose') { h.v.openDiagnose(); h.v.diagnoseForm.diagnosis = '原稿' } else { h.v.openSupplement(); h.v.supplementForm.content = '原补充' }
    const first = settle(h.v[action]()); h.v.openReport(12); await h.v.loadReport(); h.get.mockClear()
    if (action === 'submitDiagnose') { h.v.openDiagnose(); h.v.diagnoseForm.diagnosis = '新稿' } else { h.v.hasPrimary.value = true; h.v.openSupplement(); h.v.supplementForm.content = '新补充' }
    write.reject({ bizCode: action === 'submitDiagnose' ? 4553 : 5261 }); await first
    expect(h.get.mock.calls.some((c: any[]) => c[0].endsWith('/reports'))).toBe(false); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
    if (action === 'submitDiagnose') { expect(h.v.diagnoseDialog.value).toBe(true); expect(h.v.diagnoseForm.diagnosis).toBe('新稿') } else { expect(h.v.supplementDialog.value).toBe(true); expect(h.v.supplementForm.content).toBe('新补充') }
  })
}
it('cancelling the issue confirmation releases its guard without writing or refreshing', async () => {
  const h = report(); await reportReady(h, true); h.box.ElMessageBox.confirm.mockRejectedValue('cancel'); await h.v.issue(); expect(h.put).not.toHaveBeenCalled(); expect(h.get).not.toHaveBeenCalled(); expect(h.v.saving.value).toBe(false)
})
it('changing the gate to block while issue confirmation is open cannot bypass the current rule', async () => {
  const confirmation = deferred(), h = report(); await reportReady(h, true); h.box.ElMessageBox.confirm.mockImplementation(() => confirmation.promise); const first = h.v.issue(); h.v.doubleSignGate.value = 'block'; confirmation.raw('confirm'); await first; expect(h.put).not.toHaveBeenCalled()
})
it('new report open immediately clears the previous patient header and primary before the next read', async () => {
  const read = deferred(); let slow = false
  const h = report(undefined, undefined, undefined, vi.fn((path: string) => slow && path.endsWith('/reports') ? read.promise : response(path.endsWith('/reports') ? reportData(11, true) : { items: [] }))); await reportReady(h, true)
  slow = true; h.v.openReport(12); expect(h.v.specimen.value).toEqual({}); expect(h.v.primary.value).toEqual({}); expect(h.v.hasPrimary.value).toBe(false); read.resolve(reportData(12)); await h.v.loadReport()
})
it('a worklist filter changed without issuing another read invalidates the pending response', async () => {
  const read = deferred(); let slow = false; const h = report(undefined, undefined, undefined, vi.fn(() => slow ? read.promise : response({ items: [sample] }))); await h.v.load(); slow = true; const first = h.v.load(); h.v.query.scope = 'diagnosed'; read.resolve({ items: [other] }); await first; expect(h.v.rows.value).toEqual([sample])
})
it('late report and worklist reads after unmount cannot change the old page', async () => {
  const read = deferred(); let slow = false; const h = report(undefined, undefined, undefined, vi.fn((path: string) => slow ? read.promise : response(path.endsWith('/reports') ? reportData() : { items: [sample] }))); await reportReady(h); await h.v.load(); slow = true
  const reads = [h.v.loadReport(), h.v.load()]; h.dispose(); read.resolve({ ...reportData(12, true), items: [other] }); await Promise.all(reads); expect(h.v.specimen.value.id).toBe(11); expect(h.v.rows.value).toEqual([sample])
})
it('a pending report write prevents technical writes without changing their clinical policy', async () => {
  const write = deferred(), h = report(undefined, vi.fn(() => write.promise)); await reportReady(h, true); const first = h.v.firstSign(); const techDone = h.v.techDone({ id: 5, status: 'ORDERED' }); const techCancel = h.v.techCancel({ id: 5, status: 'ORDERED' }); const techSubmit = h.v.submitTech()
  expect(h.put).toHaveBeenCalledOnce(); expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessageBox.prompt).not.toHaveBeenCalled(); write.resolve(null); await Promise.all([first, techDone, techCancel, techSubmit])
})
it('old receipt known validation errors propagate and do not reload the list', async () => {
  const error = { bizCode: 1005 }, h = specialty(undefined, vi.fn(() => Promise.reject(error))); h.v.tab.value = 'path'; await h.v.loadSpecimens(); h.get.mockClear(); await expect(h.v.receive(sample)).rejects.toBe(error); expect(h.get).not.toHaveBeenCalled()
})
it('old pathology queue keeps the latest response when two reloads finish in reverse order', async () => {
  const old = deferred(), latest = deferred(); let count = 0; const h = specialty(undefined, undefined, vi.fn(() => (++count === 1 ? old.promise : latest.promise))); h.v.tab.value = 'path'; const first = h.v.loadSpecimens(); const second = h.v.loadSpecimens(); latest.resolve([other]); await second; old.resolve([sample]); await first; expect(h.v.specimens.value).toEqual([other])
})
for (const action of ['Receive', 'Reject']) {
  it(`issued historical COLLECTED specimen cannot ${action} through the registry`, async () => {
    const h = registry(); await registryReady(h); const row = { ...sample, report_issued_at: '2026-10-22' }; h.v[`open${action}`](row); h.v.rejectForm.reason = '模拟原因'; await h.v[`submit${action}`](); expect(h.put).not.toHaveBeenCalled()
    expect(h.template[`${action.toLowerCase()}Visible`](row) && !h.template[`${action.toLowerCase()}Disabled`](row)).toBe(false)
  })
}
it('issued historical COLLECTED specimen cannot use the old receipt endpoint', async () => {
  const h = specialty(); h.v.tab.value = 'path'; const row = { ...sample, report_issued_at: '2026-10-22' }; await h.v.receive(row); expect(h.put).not.toHaveBeenCalled(); expect(h.template.receiveVisible(row) && !h.template.receiveDisabled(row)).toBe(false)
})
it('issued historical RECEIVED specimen cannot reopen or directly write its first diagnosis', async () => {
  const h = report(); await reportReady(h); h.v.specimen.value.report_issued_at = '2026-10-22'; h.v.openDiagnose(); h.v.diagnoseForm.diagnosis = '模拟改写'; await h.v.submitDiagnose(); expect(h.put).not.toHaveBeenCalled(); expect(h.template.diagnoseDisabled()).toBe(true)
})
