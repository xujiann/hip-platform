import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as Vue from 'vue'

// Execute the entire real setup script; only HTTP, user/dialog and lifecycle
// boundaries are controlled. No verification/share handler is reconstructed.
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const response = (data: unknown) => Promise.resolve({ data: { code: 0, data: copy(data) } })
const rows = [
  { id: 11, status: 'REGISTERED', order_status: 'CHARGED', findings: '原所见', impression: '原结论' },
  { id: 12, status: 'REPORTED', order_status: 'EXECUTED', findings: '待审所见', impression: '待审结论' },
]
const scopes: ReturnType<typeof Vue.effectScope>[] = []
afterEach(() => { for (const scope of scopes.splice(0)) scope.stop() })
function deferred() {
  let resolve!: (value: unknown) => void, reject!: (reason: unknown) => void
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (data: unknown) => resolve({ data: { code: 0, data } }), reject }
}
function setup(roles = ['TECHNICIAN']) {
  const path = process.env.HIP_V138_FRONT_BASELINE_ROOT
    ? resolve(process.env.HIP_V138_FRONT_BASELINE_ROOT, 'RisView.vue')
    : new URL('../../views/medtech/RisView.vue', import.meta.url)
  const component = readFileSync(path, 'utf8')
  const script = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const parsed = ts.createSourceFile('RisView.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const actual = parsed.statements.filter(x => !ts.isImportDeclaration(x)).map(x => x.getText(parsed)).join('\n')
  const get = vi.fn((url: string) => response(url === '/ris/worklist' ? rows : []))
  const put = vi.fn(() => response(null)), post = vi.fn(() => response({ url: '/api/share/v138-synthetic' }))
  const dispose: (() => void)[] = [], user = Vue.reactive({ roles })
  const box: Record<string, any> = { ...Vue, onMounted: vi.fn(), onUnmounted: (fn: () => void) => dispose.push(fn),
    useAuthStore: () => ({ user }), client: { get, put, post }, location: { origin: 'http://v138.local' }, window: { open: vi.fn() },
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
    ElMessageBox: { alert: vi.fn(() => Promise.resolve()), prompt: vi.fn(() => Promise.resolve({ value: '' })) } }
  const scope = Vue.effectScope(); scopes.push(scope)
  scope.run(() => runInNewContext(ts.transpileModule(actual + `
    globalThis.view = { records, current, findings, impression, dialogVisible, templates, templateId,
      busyId, saveReportLoading, writing, load, openReport, verify, arrive, saveReport, markCritical };
  `, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box))
  return { v: box.view as Record<string, any>, get, put, post, box, user, dispose: () => dispose.forEach(fn => fn()) }
}
type Harness = ReturnType<typeof setup>
async function prepare(h: Harness) {
  await h.v.load(); h.v.openReport(h.v.records.value[0])
  h.v.findings.value = '保留当前草稿'; h.v.impression.value = '保留当前意见'
  h.get.mockClear()
}
function state(h: Harness) {
  return copy(Object.fromEntries(['records', 'current', 'findings', 'impression', 'dialogVisible', 'templates', 'templateId']
    .map(key => [key, h.v[key].value])))
}
const denied = () => Object.assign(new Error('共享 client 已提示无该功能权限'), { response: { status: 403, data: { code: 1005 } } })

describe.each(['verify', 'share'] as const)('%s exact current-duty denial', phase => {
  it('consumes HTTP403/code1005, preserves the draft and releases the original guard', async () => {
    const h = setup(); await prepare(h); const before = state(h), error = denied()
    ;(phase === 'verify' ? h.put : h.post).mockRejectedValueOnce(error)
    await expect(h.v.verify(h.v.records.value[1])).resolves.toBeUndefined()
    expect(h.put).toHaveBeenCalledExactlyOnceWith('/ris/exams/12/verify')
    expect(h.post).toHaveBeenCalledTimes(phase === 'share' ? 1 : 0)
    expect(h.get).toHaveBeenCalledTimes(phase === 'share' ? 2 : 0)
    expect(state(h)).toEqual(before); expect(h.v.busyId.value).toBeNull(); expect(h.v.writing.value).toBe(false)
    expect(h.box.ElMessage.success).toHaveBeenCalledTimes(phase === 'share' ? 1 : 0)
    if (phase === 'share') expect(h.box.ElMessage.success).toHaveBeenCalledExactlyOnceWith('已审核发布，医嘱转已执行')
    expect(h.box.ElMessageBox.alert).not.toHaveBeenCalled()
    for (const key of ['warning', 'error', 'info']) expect(h.box.ElMessage[key]).not.toHaveBeenCalled()
  })
  it.each([
    ['other403', { response: { status: 403, data: { code: 1999 } } }],
    ['401', { response: { status: 401, data: { code: 1000 } } }],
    ['HTTP200/code1005', { response: { status: 200, data: { code: 1005 } } }],
    ['business1005', { bizCode: 1005 }],
    ['network', new Error('模拟网络故障')],
  ])('keeps %s propagation and the original completion facts', async (_label, error) => {
    const h = setup(); await prepare(h); const before = state(h)
    ;(phase === 'verify' ? h.put : h.post).mockRejectedValueOnce(error)
    await expect(h.v.verify(h.v.records.value[1])).rejects.toBe(error)
    expect(state(h)).toEqual(before); expect(h.v.busyId.value).toBeNull()
    expect(h.get).toHaveBeenCalledTimes(phase === 'share' ? 2 : 0)
    expect(h.box.ElMessage.success).toHaveBeenCalledTimes(phase === 'share' ? 1 : 0)
    expect(h.box.ElMessageBox.alert).not.toHaveBeenCalled()
  })
})

it.each(['ADMIN', 'TECHNICIAN', 'DOCTOR_OUTP', 'NURSE'])('preserves %s verification and its original share duties', async role => {
  const h = setup([role]); await prepare(h)
  await h.v.verify(h.v.records.value[1])
  expect(h.put).toHaveBeenCalledExactlyOnceWith('/ris/exams/12/verify')
  expect(h.get).toHaveBeenCalledTimes(2); expect(h.v.busyId.value).toBeNull()
  expect(h.post).toHaveBeenCalledTimes(role === 'NURSE' ? 0 : 1)
  expect(h.box.ElMessageBox.alert).toHaveBeenCalledTimes(role === 'NURSE' ? 0 : 1)
  if (role !== 'NURSE') expect(h.box.ElMessageBox.alert).toHaveBeenCalledWith(expect.stringContaining('http://v138.local/api/share/v138-synthetic'), '报告分享')
})
it.each([['verify', 9945], ['share', 4660]] as const)('retains %s original business-conflict recovery', async (phase, bizCode) => {
  const h = setup(); await prepare(h); const before = state(h)
  ;(phase === 'verify' ? h.put : h.post).mockRejectedValueOnce({ bizCode })
  await expect(h.v.verify(h.v.records.value[1])).resolves.toBeUndefined()
  expect(h.get).toHaveBeenCalledTimes(phase === 'verify' ? 2 : 4)
  expect(state(h)).toEqual(before); expect(h.v.busyId.value).toBeNull()
  expect(h.box.ElMessageBox.alert).not.toHaveBeenCalled()
})
it('keeps a newer draft when the earlier share is refused after a wait', async () => {
  const h = setup(); await prepare(h); const wait = deferred(); h.post.mockImplementationOnce(() => wait.promise as any)
  const first = h.v.verify(h.v.records.value[1]); await vi.waitFor(() => expect(h.post).toHaveBeenCalledOnce())
  h.v.openReport(h.v.records.value[0]); h.v.findings.value = '后开独立草稿'; h.v.impression.value = '后开独立意见'
  const before = state(h); h.get.mockClear(); wait.reject(denied())
  await expect(first).resolves.toBeUndefined()
  expect(state(h)).toEqual(before); expect(h.get).not.toHaveBeenCalled()
  expect(h.v.busyId.value).toBeNull(); expect(h.box.ElMessageBox.alert).not.toHaveBeenCalled()
})
it('consumes the exact late share denial after unmount without an alert or another refresh', async () => {
  const h = setup(); await prepare(h); const wait = deferred(); h.post.mockImplementationOnce(() => wait.promise as any)
  const first = h.v.verify(h.v.records.value[1]); await vi.waitFor(() => expect(h.post).toHaveBeenCalledOnce())
  h.dispose(); h.get.mockClear(); wait.reject(denied())
  await expect(first).resolves.toBeUndefined()
  expect(h.get).not.toHaveBeenCalled(); expect(h.box.ElMessageBox.alert).not.toHaveBeenCalled(); expect(h.v.busyId.value).toBeNull()
})
it('holds the original guard throughout share and releases it after the exact denial', async () => {
  const h = setup(); await prepare(h); const wait = deferred(); h.post.mockImplementationOnce(() => wait.promise as any)
  const first = h.v.verify(h.v.records.value[1]); await vi.waitFor(() => expect(h.post).toHaveBeenCalledOnce())
  await Promise.all([h.v.verify(h.v.records.value[1]), h.v.arrive(h.v.records.value[0]), h.v.saveReport(), h.v.markCritical(h.v.records.value[1])])
  expect(h.put).toHaveBeenCalledTimes(1); expect(h.post).toHaveBeenCalledTimes(1); expect(h.box.ElMessageBox.prompt).not.toHaveBeenCalled()
  wait.reject(denied()); await expect(first).resolves.toBeUndefined(); expect(h.v.writing.value).toBe(false)
})
