import { expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { computed, reactive, ref } from 'vue'

const response = (data: unknown) => Promise.resolve({ data: { data: structuredClone(data) } })
function deferred() {
  let resolve!: (value: any) => void, reject!: (reason: unknown) => void
  const promise = new Promise<any>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (data: unknown) => resolve({ data: { data } }), reject }
}
const pendingRow = { source: 'OUTP', order_id: 31, inp_order_id: null, patient_name: '模拟患者甲', item_name: '模拟病理项目', order_status: 'CHARGED', registered_parts: 0, urgent: true }
const secondRow = { ...pendingRow, order_id: 32, patient_name: '模拟患者乙', registered_parts: 2 }
const specimenRow = { id: 8, barcode: 'PB8', path_no: '2026-C-000008', status: 'COLLECTED', rejected_at: null }
const registered = { id: 9, barcode: 'PB9', pathNo: '2026-C-000009', partNo: 1, source: 'OUTP', specimenType: 'ROUTINE', status: 'COLLECTED', manual: false }

function setup(get = vi.fn((path: string) => response({ items: path.endsWith('/pending') ? [pendingRow, secondRow] : [specimenRow], limit: 200, truncated: false })),
  post = vi.fn(() => response(registered)), put = vi.fn(() => response(null)), roles = ['TECHNICIAN']) {
  const component = readFileSync(new URL('../../views/medtech/pathology/RegistryPanel.vue', import.meta.url), 'utf8')
  const source = component.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import[\s\S]*?from ['"][^'"]+['"]\r?$/gm, '')
  const dispose: Array<() => void> = [], user = reactive({ roles })
  const box: Record<string, any> = { ref, reactive, computed, onUnmounted: (f: () => void) => dispose.push(f),
    defineEmits: () => vi.fn(), defineExpose: () => {}, useAuthStore: () => ({ user }), client: { get, post, put },
    num: (v: unknown) => Number(v) || 0, ElMessage: { success: vi.fn(), warning: vi.fn() } }
  const buttons = Array.from(component.matchAll(/<el-button\b([^>]+)>/g))
  const binding = (action: string, attribute: string, fallback: string) => buttons.find(m => m[1].includes(`@click="${action}"`))?.[1].match(new RegExp(`${attribute}="([^"]+)"`))?.[1] ?? fallback
  const expression = (text: string) => text.replace(/\b(canRegister|saving)\b/g, '$1.value')
  runInNewContext(ts.transpileModule(source + `
    globalThis.view = { pending, pendingLoading, pendingTruncated, pendingLimit, pendingQuery, specimens, searchLoading, searchTruncated, searchLimit, searchRange, rejectedFlag, searchQuery,
      saving, regDialog, resultDialog, regResult, regForm, loadPending, loadSpecimens, openRegister, submitRegister, receiveDialog, rejectDialog, receiveForm, rejectForm, current, openReceive, submitReceive, openReject, submitReject };
    globalThis.visible = { normal: () => ${expression(binding('openRegister(row, false)', 'v-if', 'true'))}, manual: () => ${expression(binding('openRegister(row, true)', 'v-if', 'true'))} };
    globalThis.disabled = { normal: () => ${expression(binding('openRegister(row, false)', ':disabled', 'false'))}, manual: () => ${expression(binding('openRegister(row, true)', ':disabled', 'false'))}, submit: () => ${expression(binding('submitRegister', ':disabled', 'false'))} };
    globalThis.emits = emit;
  `, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box)
  return { v: box.view, box, get, post, put, user, component, dispose: () => dispose.forEach(f => f()) }
}
type Harness = ReturnType<typeof setup>
async function ready(h: Harness) { await h.v.loadPending(); await h.v.loadSpecimens(); h.get.mockClear() }
const settle = (promise: Promise<unknown>) => promise.catch(() => {})
const queueReads = (h: Harness) => h.get.mock.calls.filter(c => c[0] === '/pathology/registry/pending' || c[0] === '/pathology/registry/specimens/search')

for (const role of ['ADMIN', 'TECHNICIAN', 'DOCTOR_OUTP']) {
  it(`preserves ${role} ordinary and manual registration through their original endpoints`, async () => {
    const h = setup(undefined, undefined, undefined, [role]); await ready(h)
    expect(h.box.visible.normal()).toBe(true); expect(h.box.visible.manual()).toBe(true)
    h.v.openRegister(h.v.pending.value[0], false); await h.v.submitRegister()
    h.v.openRegister(h.v.pending.value[0], true); await h.v.submitRegister()
    expect(h.post.mock.calls.map(c => c[0])).toEqual(['/pathology/registry/specimens', '/pathology/registry/specimens/manual'])
    expect(h.v.regResult.value).toEqual(registered); expect(h.v.resultDialog.value).toBe(true)
  })
}
for (const roles of [['NURSE'], ['CASHIER'], ['PHARMACIST'], ['INTERFACE'], [], ['TECHNICIAN_OLD']]) {
  it(`hides and directly denies registration for ${roles.join('/') || 'no role'}`, async () => {
    const h = setup(undefined, undefined, undefined, roles); await ready(h)
    expect(h.box.visible.normal()).toBe(false); expect(h.box.visible.manual()).toBe(false)
    h.v.openRegister(h.v.pending.value[0], false); await h.v.submitRegister()
    h.v.openRegister(h.v.pending.value[0], true); await h.v.submitRegister()
    expect(h.post).not.toHaveBeenCalled(); expect(h.v.regDialog.value).toBe(false)
  })
}
it('preserves all ordinary metadata, source identity and supplemental part number', async () => {
  const h = setup(); await ready(h); h.v.openRegister(h.v.pending.value[1], false)
  Object.assign(h.v.regForm, { specimenType: 'FROZEN', specimenDesc: ' 甲段 ', samplingSite: ' 胃 ', clinicalDiagnosis: ' 模拟诊断 ', fixative: ' 模拟固定液 ', fixedAt: '2026-10-22 10:01:02' })
  await h.v.submitRegister()
  expect(h.post).toHaveBeenCalledExactlyOnceWith('/pathology/registry/specimens', { orderId: 32, inpOrderId: undefined, partNo: 3, specimenType: 'FROZEN', specimenDesc: ' 甲段 ', samplingSite: ' 胃 ', clinicalDiagnosis: ' 模拟诊断 ', fixative: ' 模拟固定液 ', fixedAt: '2026-10-22 10:01:02', urgent: true })
})
it('preserves a non-CANCELLED inpatient source without imposing outpatient payment', async () => {
  const h = setup(); await ready(h); h.v.openRegister({ source: 'INP', inp_order_id: 53, order_status: 'ACTIVE', registered_parts: 0 }, false); await h.v.submitRegister()
  expect(h.post.mock.calls[0][1]).toMatchObject({ orderId: undefined, inpOrderId: 53, partNo: 1 })
})
it('manual source opened before a real refund remains manual and sends explicit receipt trace metadata', async () => {
  const h = setup(undefined, vi.fn(() => response({ ...registered, manual: true, status: 'RECEIVED' }))); await ready(h)
  const row = h.v.pending.value[0]; h.v.openRegister(row, true); row.order_status = 'CREATED'
  Object.assign(h.v.regForm, { applyDept: ' 模拟送检科 ', applyDoctor: ' 模拟送检人 ', note: ' 模拟实物已送达 ' })
  await h.v.submitRegister()
  expect(h.post).toHaveBeenCalledExactlyOnceWith('/pathology/registry/specimens/manual', { orderId: 31, inpOrderId: undefined, partNo: 1, specimenType: 'ROUTINE', specimenDesc: undefined, samplingSite: undefined, clinicalDiagnosis: undefined, fixative: undefined, fixedAt: undefined, urgent: true, applyDept: ' 模拟送检科 ', applyDoctor: ' 模拟送检人 ', note: ' 模拟实物已送达 ' })
  expect(h.v.regResult.value.status).toBe('RECEIVED'); expect(h.v.regResult.value.manual).toBe(true)
})
it('an unusable source is rejected locally without consuming the write guard', async () => {
  const h = setup(); await ready(h); h.v.openRegister({ source: 'OUTP' }, false); await h.v.submitRegister()
  expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessage.warning).toHaveBeenCalledOnce(); expect(h.v.saving.value).toBe(false)
})
it('a direct submit without an open registration dialog cannot create a specimen', async () => {
  const h = setup(); await ready(h); h.v.openRegister(h.v.pending.value[0], false); h.v.regDialog.value = false; await h.v.submitRegister()
  expect(h.post).not.toHaveBeenCalled()
})
it('losing the role while a draft is open prevents its direct submission', async () => {
  const h = setup(); await ready(h); h.v.openRegister(h.v.pending.value[0], false); h.user.roles = ['NURSE']; await h.v.submitRegister(); expect(h.post).not.toHaveBeenCalled()
})
it('ordinary and manual double-clicks share one synchronous registration write guard', async () => {
  const write = deferred(), h = setup(undefined, vi.fn(() => write.promise)); await ready(h); h.v.openRegister(h.v.pending.value[0], false)
  const first = h.v.submitRegister(); h.v.regForm.manual = true; const second = h.v.submitRegister()
  expect(h.box.disabled.normal()).toBe(true); expect(h.box.disabled.manual()).toBe(true); expect(h.box.disabled.submit()).toBe(true)
  expect(h.post).toHaveBeenCalledOnce(); write.resolve(registered); await Promise.all([first, second]); expect(h.v.saving.value).toBe(false)
})
it('in-flight registration prevents receipt and rejection writes without changing either clinical rule', async () => {
  const write = deferred(), h = setup(undefined, vi.fn(() => write.promise)); await ready(h); h.v.openRegister(h.v.pending.value[0], false)
  const first = h.v.submitRegister(); h.v.openReceive(specimenRow); h.v.openReject(specimenRow); h.v.rejectForm.reason = '模拟不符'
  await Promise.all([h.v.submitReceive(), h.v.submitReject()]); expect(h.put).not.toHaveBeenCalled()
  write.resolve(registered); await first
})
for (const action of ['Receive', 'Reject']) {
  it(`an existing ${action.toLowerCase()} write prevents ordinary and manual registration`, async () => {
    const write = deferred(), h = setup(undefined, undefined, vi.fn(() => write.promise)); await ready(h)
    h.v[`open${action}`](specimenRow); h.v.rejectForm.reason = '模拟不符'; const first = h.v[`submit${action}`]()
    h.v.openRegister(h.v.pending.value[0], false); await h.v.submitRegister(); h.v.openRegister(h.v.pending.value[0], true); await h.v.submitRegister()
    expect(h.post).not.toHaveBeenCalled(); write.resolve(null); await first
  })
}
for (const manual of [false, true]) {
  it(`a late ${manual ? 'manual' : 'ordinary'} response keeps a subsequently opened draft and result intact`, async () => {
    const write = deferred(), h = setup(undefined, vi.fn(() => write.promise)); await ready(h); h.v.openRegister(h.v.pending.value[0], manual)
    const first = h.v.submitRegister(); const originalBody = structuredClone(h.post.mock.calls[0][1]); h.v.regDialog.value = false
    h.v.openRegister(secondRow, !manual); h.v.regForm.specimenDesc = '后开的模拟草稿'; h.v.resultDialog.value = true; h.v.regResult.value = { id: 88, barcode: 'PB88' }
    write.resolve(registered); await first
    expect(h.post.mock.calls[0][1]).toEqual(originalBody); expect(h.v.regForm.orderId).toBe(32); expect(h.v.regForm.specimenDesc).toBe('后开的模拟草稿')
    expect(h.v.regDialog.value).toBe(true); expect(h.v.regResult.value).toEqual({ id: 88, barcode: 'PB88' }); expect(h.v.resultDialog.value).toBe(true)
    expect(queueReads(h)).toHaveLength(2); expect(h.box.emits).toHaveBeenCalledExactlyOnceWith('changed')
  })
  it(`a ${manual ? 'manual' : 'ordinary'} success refreshes pending and specimen lists and releases the guard only afterwards`, async () => {
    const refresh = deferred(); let slow = false
    const h = setup(vi.fn((path: string) => slow ? refresh.promise : response({ items: path.endsWith('/pending') ? [pendingRow] : [specimenRow] }))); await ready(h)
    h.v.openRegister(h.v.pending.value[0], manual); slow = true; const first = h.v.submitRegister(); await vi.waitFor(() => expect(queueReads(h)).toHaveLength(2))
    expect(h.v.saving.value).toBe(true); await h.v.submitRegister(); expect(h.post).toHaveBeenCalledOnce()
    refresh.resolve({ items: [], limit: 200, truncated: false }); await first; expect(h.v.saving.value).toBe(false); expect(h.v.specimens.value).toEqual([])
  })
}
for (const code of [5201, 5202]) {
  it(`${code} rejection refreshes both real queues with no success and no automatic switch to manual`, async () => {
    const error = { bizCode: code }, h = setup(undefined, vi.fn(() => Promise.reject(error))); await ready(h); h.v.openRegister(h.v.pending.value[0], false)
    h.v.regForm.specimenDesc = '退款前模拟草稿'; await settle(h.v.submitRegister())
    expect(queueReads(h)).toHaveLength(2); expect(h.post).toHaveBeenCalledOnce(); expect(h.post.mock.calls[0][0]).toBe('/pathology/registry/specimens')
    expect(h.v.regForm.manual).toBe(false); expect(h.v.regForm.specimenDesc).toBe('退款前模拟草稿'); expect(h.v.regDialog.value).toBe(true)
    expect(h.v.resultDialog.value).toBe(false); expect(h.v.regResult.value).toEqual({}); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.box.emits).not.toHaveBeenCalled(); expect(h.v.saving.value).toBe(false)
  })
  it(`late ${code} rejection preserves newer draft and result while refreshing current queues`, async () => {
    const write = deferred(), h = setup(undefined, vi.fn(() => write.promise)); await ready(h); h.v.openRegister(h.v.pending.value[0], false); const first = settle(h.v.submitRegister())
    h.v.regDialog.value = false; h.v.openRegister(secondRow, true); h.v.regForm.note = '新模拟手工草稿'; h.v.regResult.value = { id: 88 }; h.v.resultDialog.value = true
    write.reject({ bizCode: code }); await first
    expect(queueReads(h)).toHaveLength(2); expect(h.v.regForm.orderId).toBe(32); expect(h.v.regForm.note).toBe('新模拟手工草稿'); expect(h.v.regDialog.value).toBe(true); expect(h.v.resultDialog.value).toBe(true); expect(h.v.regResult.value).toEqual({ id: 88 })
  })
}
for (const code of [1005, 5203, 5204, 5205, 5210, 5212]) {
  it(`ordinary ${code} error keeps its failure and draft without extra queue requests`, async () => {
    const error = { bizCode: code }, h = setup(undefined, vi.fn(() => Promise.reject(error))); await ready(h); h.v.openRegister(h.v.pending.value[0], false)
    await expect(h.v.submitRegister()).rejects.toBe(error); expect(h.get).not.toHaveBeenCalled(); expect(h.v.regDialog.value).toBe(true); expect(h.v.resultDialog.value).toBe(false); expect(h.v.saving.value).toBe(false)
  })
}
it('the latest pending query owns rows, truncation, limit and spinner after old requests finish last', async () => {
  const old = deferred(), latest = deferred(); let controlled = false
  const h = setup(vi.fn((_path: string, opts: any) => !controlled ? response({ items: [] }) : opts.params.keyword === '旧' ? old.promise : latest.promise)); await ready(h); controlled = true
  h.v.pendingQuery.keyword = '旧'; const first = h.v.loadPending(); h.v.pendingQuery.keyword = '新'; const second = h.v.loadPending()
  latest.resolve({ items: [secondRow], truncated: true, limit: 12 }); await second; old.resolve({ items: [pendingRow], truncated: false, limit: 8 }); await first
  expect(h.v.pending.value).toEqual([secondRow]); expect(h.v.pendingTruncated.value).toBe(true); expect(h.v.pendingLimit.value).toBe(12); expect(h.v.pendingLoading.value).toBe(false)
})
it('a pending filter changed without a new query makes the former response inapplicable', async () => {
  const read = deferred(); let controlled = false
  const h = setup(vi.fn(() => controlled ? read.promise : response({ items: [pendingRow] }))); await ready(h); controlled = true
  const first = h.v.loadPending(); h.v.pendingQuery.source = 'INP'; read.resolve({ items: [secondRow] }); await first; expect(h.v.pending.value).toEqual([pendingRow]); expect(h.v.pendingLoading.value).toBe(false)
})
it('the latest specimen query owns rows and caps when the former request returns last', async () => {
  const old = deferred(), latest = deferred(); let controlled = false
  const h = setup(vi.fn((_path: string, opts: any) => !controlled ? response({ items: [] }) : opts.params.barcode === '旧' ? old.promise : latest.promise)); await ready(h); controlled = true
  h.v.searchQuery.barcode = '旧'; const first = h.v.loadSpecimens(); h.v.searchQuery.barcode = '新'; const second = h.v.loadSpecimens()
  latest.resolve({ items: [{ ...specimenRow, id: 99 }], truncated: true, limit: 15 }); await second; old.resolve({ items: [specimenRow], truncated: false, limit: 8 }); await first
  expect(h.v.specimens.value[0].id).toBe(99); expect(h.v.searchTruncated.value).toBe(true); expect(h.v.searchLimit.value).toBe(15); expect(h.v.searchLoading.value).toBe(false)
})
it('a changed specimen date/filter prevents its old response from replacing the current list', async () => {
  const read = deferred(); let controlled = false
  const h = setup(vi.fn(() => controlled ? read.promise : response({ items: [specimenRow] }))); await ready(h); controlled = true
  const first = h.v.loadSpecimens(); h.v.searchRange.value = ['2026-10-21', '2026-10-22']; h.v.rejectedFlag.value = 'Y'; read.resolve({ items: [] }); await first
  expect(h.v.specimens.value).toEqual([specimenRow]); expect(h.v.searchLoading.value).toBe(false)
})
it('pending and specimen filters retain the existing snake/camel HTTP contracts', async () => {
  const h = setup(); await ready(h); Object.assign(h.v.pendingQuery, { source: 'OUTP', keyword: '胃', includeRegistered: true }); await h.v.loadPending()
  Object.assign(h.v.searchQuery, { pathNo: '2026', barcode: 'PB', patientName: '模拟', status: 'COLLECTED', specimenType: 'FROZEN', source: 'INP' }); h.v.searchRange.value = ['2026-10-21', '2026-10-22']; h.v.rejectedFlag.value = 'N'; await h.v.loadSpecimens()
  expect(h.get).toHaveBeenCalledWith('/pathology/registry/pending', { params: { source: 'OUTP', keyword: '胃', includeRegistered: true } })
  expect(h.get).toHaveBeenCalledWith('/pathology/registry/specimens/search', { params: { pathNo: '2026', barcode: 'PB', patientName: '模拟', from: '2026-10-21', to: '2026-10-22', status: 'COLLECTED', specimenType: 'FROZEN', source: 'INP', rejected: false } })
})
for (const failure of [false, true]) {
  it(`leaving during a registration ${failure ? 'failure' : 'success'} prevents late reads, dialogs and change events`, async () => {
    const write = deferred(), h = setup(undefined, vi.fn(() => write.promise)); await ready(h); h.v.openRegister(h.v.pending.value[0], false); const first = settle(h.v.submitRegister())
    h.dispose(); if (failure) write.reject({ bizCode: 5201 }); else write.resolve(registered); await first
    expect(h.get).not.toHaveBeenCalled(); expect(h.v.resultDialog.value).toBe(false); expect(h.v.regResult.value).toEqual({}); expect(h.box.emits).not.toHaveBeenCalled()
  })
}
it('leaving before a direct open or submit prevents registration', async () => {
  const h = setup(); await ready(h); h.v.openRegister(h.v.pending.value[0], false); h.dispose(); await h.v.submitRegister(); expect(h.post).not.toHaveBeenCalled()
})
it('unmounted queue requests cannot alter the former page after they return', async () => {
  const read = deferred(); let controlled = false
  const h = setup(vi.fn(() => controlled ? read.promise : response({ items: [specimenRow] }))); await ready(h); controlled = true
  const reads = [h.v.loadPending(), h.v.loadSpecimens()]; h.dispose(); read.resolve({ items: [secondRow], limit: 17, truncated: true }); await Promise.all(reads)
  expect(h.v.pending.value).toEqual([specimenRow]); expect(h.v.specimens.value).toEqual([specimenRow]); expect(h.v.pendingTruncated.value).toBe(false); expect(h.v.searchTruncated.value).toBe(false)
})
