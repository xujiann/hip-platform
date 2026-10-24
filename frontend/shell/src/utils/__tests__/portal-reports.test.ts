import { it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { ref } from 'vue'

const paths = ['/my/registrations', '/my/lab-reports', '/my/charges', '/my/exam-reports', '/my/pending-bill', '/my/appointments', '/my/admissions']
const fields = ['regs', 'labs', 'charges', 'examReports', 'bills', 'appts', 'admissions']
const report = { report_type: 'EXAM', item_name: '模拟检查', conclusion: '模拟结论', detail: '模拟所见', report_date: '2026-10-24T01:00:00.123456Z' }
const response = (data: unknown) => Promise.resolve({ data: { data } })
function deferred() {
  let resolve!: (value: any) => void, reject!: (reason: unknown) => void
  const promise = new Promise<any>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (data: unknown) => resolve({ data: { data } }), reject }
}
function setup(get = vi.fn((path: string) => response(path === '/my/exam-reports' ? [report] : [{ path }]))) {
  const component = readFileSync(new URL('../../views/portal/PortalHomeView.vue', import.meta.url), 'utf8')
  const source = component.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
  const disposers: Array<() => void> = [], storage = new Map([['hip_portal_token', 'synthetic-patient-A'], ['hip_portal_name', '模拟患者甲']])
  const push = vi.fn()
  const box: Record<string, any> = { ref, onMounted: () => {}, onUnmounted: (f: () => void) => disposers.push(f),
    useRouter: () => ({ push }), localStorage: { getItem: (key: string) => storage.get(key) ?? null, removeItem: (key: string) => storage.delete(key) },
    portal: { get, post: vi.fn() }, ElMessage: { success: vi.fn(), info: vi.fn() }, fmtDate: (v: unknown) => v, todayLocal: () => '2026-10-24' }
  runInNewContext(ts.transpileModule(source + '\nglobalThis.view = { regs, labs, charges, examReports, bills, appts, admissions, loadMine, logout };',
    { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box)
  return { v: box.view, get, storage, push, dispose: () => disposers.forEach(f => f()) }
}
function pending() {
  const wait = deferred(), get = vi.fn((path: string) => path === '/my/exam-reports' ? wait.promise : response([{ path }]))
  return { wait, h: setup(get) }
}

it('keeps all seven original personal endpoints and consumes the existing report payload', async () => {
  const h = setup(); await h.v.loadMine()
  expect(h.get.mock.calls.map(c => c[0])).toEqual(paths)
  expect(h.v.examReports.value).toEqual([report])
  fields.forEach((field, index) => expect(h.v[field].value).toEqual(field === 'examReports' ? [report] : [{ path: paths[index] }]))
})
it('clears previous personal lists as a new report read starts', async () => {
  const { h, wait } = pending(); fields.forEach(field => { h.v[field].value = [{ old: field }] })
  const request = h.v.loadMine()
  fields.forEach(field => expect(h.v[field].value).toEqual([]))
  wait.resolve([]); await request; expect(h.v.examReports.value).toEqual([])
})
it('an older report cannot resurrect after a newer empty result', async () => {
  const old = deferred(), count = new Map<string, number>()
  const h = setup(vi.fn((path: string) => {
    const n = (count.get(path) ?? 0) + 1; count.set(path, n)
    return path === '/my/exam-reports' && n === 1 ? old.promise : response(path === '/my/exam-reports' ? [] : [{ request: n }])
  }))
  const first = h.v.loadMine(); await h.v.loadMine(); old.resolve([report]); await first
  expect(h.v.examReports.value).toEqual([])
  fields.filter(field => field !== 'examReports').forEach(field => expect(h.v[field].value).toEqual([{ request: 2 }]))
})
it('an older empty report cannot erase the newer legitimate report', async () => {
  const old = deferred(); let read = 0
  const h = setup(vi.fn((path: string) => path === '/my/exam-reports' ? (++read === 1 ? old.promise : response([report])) : response([])))
  const first = h.v.loadMine(); await h.v.loadMine(); old.resolve([]); await first
  expect(h.v.examReports.value).toEqual([report])
})
it('ignores completion after leaving the page', async () => {
  const { h, wait } = pending(); const request = h.v.loadMine(); h.dispose(); wait.resolve([report]); await request
  fields.forEach(field => expect(h.v[field].value).toEqual([]))
})
it('does not issue personal reads after leaving the page', async () => {
  const h = setup(); h.dispose(); await h.v.loadMine(); expect(h.get).not.toHaveBeenCalled()
})
it('does not apply an old identity response after the portal token changes', async () => {
  const { h, wait } = pending(); const request = h.v.loadMine(); h.storage.set('hip_portal_token', 'synthetic-patient-B')
  wait.resolve([report]); await request; fields.forEach(field => expect(h.v[field].value).toEqual([]))
})
it('logout clears personal lists and invalidates pending reports before unmount', async () => {
  const { h, wait } = pending(); const request = h.v.loadMine(); h.v.logout()
  expect(h.push).toHaveBeenCalledWith('/portal'); expect(h.storage.has('hip_portal_token')).toBe(false)
  wait.resolve([report]); await request; fields.forEach(field => expect(h.v[field].value).toEqual([]))
})
it('a failed current read leaves the old report hidden rather than treating it as current', async () => {
  const { h, wait } = pending(); h.v.examReports.value = [report]; const request = h.v.loadMine()
  wait.reject(new Error('simulated network failure')); await expect(request).rejects.toThrow('simulated network failure')
  expect(h.v.examReports.value).toEqual([])
})
it('a late old read failure does not clear the newer valid result', async () => {
  const old = deferred(); let read = 0
  const h = setup(vi.fn((path: string) => path === '/my/exam-reports' ? (++read === 1 ? old.promise : response([report])) : response([])))
  const first = h.v.loadMine(); await h.v.loadMine(); old.reject(new Error('old failure'))
  await expect(first).rejects.toThrow('old failure'); expect(h.v.examReports.value).toEqual([report])
})
it('retains a normal refresh under the same patient token', async () => {
  const h = setup(); await h.v.loadMine(); await h.v.loadMine()
  expect(h.get).toHaveBeenCalledTimes(14); expect(h.v.examReports.value).toEqual([report])
})
