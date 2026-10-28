import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as Vue from 'vue'

// Run the complete real setup script. Only transport, lifecycle and storage are controlled.
const scopes: ReturnType<typeof Vue.effectScope>[] = []
afterEach(() => { for (const scope of scopes.splice(0)) scope.stop() })
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const reply = (data: unknown) => ({ data: { code: 0, data } })
function deferred() {
  let resolve!: (value: unknown) => void, reject!: (error: unknown) => void
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (data: unknown) => resolve(reply(data)), reject }
}
const A: [string, string] = ['2026-10-01', '2026-10-02']
const B: [string, string] = ['2026-10-03', '2026-10-04']
const result = (total: number) => ({ total, overtime: 1, limitMinutes: 120,
  collect_to_receive_min: 3, receive_to_publish_min: 5, total_tat_min: 8 })
function setup() {
  const path = process.env.HIP_V140_FRONT_BASELINE_ROOT
    ? resolve(process.env.HIP_V140_FRONT_BASELINE_ROOT, 'LisQcView.vue')
    : new URL('../../views/medtech/LisQcView.vue', import.meta.url)
  const component = readFileSync(path, 'utf8')
  const script = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const parsed = ts.createSourceFile('LisQcView.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const actual = parsed.statements.filter(value => !ts.isImportDeclaration(value)).map(value => value.getText(parsed)).join('\n')
  const mounted: Array<() => unknown> = [], unmounted: Array<() => unknown> = []
  const listeners = new Map<string, Set<(event: unknown) => void>>()
  const auth = Vue.reactive({ token: 'employee-A', user: { id: 7 } })
  const storage = new Map([['hip_token', 'employee-A']])
  const localStorage = { getItem: (key: string) => storage.get(key) ?? null }
  const get = vi.fn((_url: string, _config?: unknown) => Promise.resolve(reply([])))
  const post = vi.fn((_url: string, _body?: unknown) => Promise.resolve(reply({ id: 9 })))
  const window = {
    addEventListener: vi.fn((type: string, fn: (event: unknown) => void) => {
      if (!listeners.has(type)) listeners.set(type, new Set())
      listeners.get(type)!.add(fn)
    }),
    removeEventListener: vi.fn((type: string, fn: (event: unknown) => void) => { listeners.get(type)?.delete(fn) }),
  }
  const box: Record<string, any> = { ...Vue, onMounted: (fn: () => unknown) => mounted.push(fn),
    onUnmounted: (fn: () => unknown) => unmounted.push(fn), useAuthStore: () => auth,
    localStorage, window, client: { get, post }, ElMessage: { success: vi.fn(), warning: vi.fn() } }
  const scope = Vue.effectScope(); scopes.push(scope)
  scope.run(() => runInNewContext(ts.transpileModule(actual + `\nglobalThis.view = {
    tab, tatRange, tat, loadTat, onTab, qc, qcResult, qcLatest, submitQc,
    microSamples, microCur, microForm, microDialog, microLoading, openMicro, submitMicro
  };`, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box))
  const v = box.view as Record<string, any>
  v.tab.value = 'tat'
  return { v, get, post, auth, storage, box, mounted, unmounted,
    storageEvent: (key: string | null = 'hip_token', storageArea = localStorage) => {
      for (const fn of listeners.get('storage') ?? []) fn({ key, storageArea })
    },
    leave: () => { for (const fn of unmounted) fn() },
  }
}
type Harness = ReturnType<typeof setup>
function start(h: Harness, range: [string, string] | null, pending = deferred()) {
  h.v.tatRange.value = range && [...range]
  h.get.mockReturnValueOnce(pending.promise as Promise<any>)
  return { pending, done: h.v.loadTat() as Promise<void> }
}
async function complete(run: ReturnType<typeof start>, data: unknown) { run.pending.resolve(data); await run.done }

describe('TAT result belongs to the current date request', () => {
  it('keeps B when older A finishes last, without another read or any write', async () => {
    const h = setup(), a = start(h, A), b = start(h, B)
    expect(h.get.mock.calls.map(call => call[1])).toEqual([{ params: { from: A[0], to: A[1] } }, { params: { from: B[0], to: B[1] } }])
    await complete(b, result(22)); await complete(a, result(11))
    expect(h.v.tat.value).toEqual(result(22)); expect(h.get).toHaveBeenCalledTimes(2); expect(h.post).not.toHaveBeenCalled()
  })
  it('does not expose A while a newer B is still waiting', async () => {
    const h = setup(), a = start(h, A), b = start(h, B)
    await complete(a, result(11)); expect(h.v.tat.value).toBeNull()
    await complete(b, result(22)); expect(h.v.tat.value).toEqual(result(22))
  })
  it('A→B→A does not revive the first A response', async () => {
    const h = setup(), a1 = start(h, A), b = start(h, B), a2 = start(h, A)
    await complete(a2, result(33)); await complete(b, result(22)); await complete(a1, result(11))
    expect(h.v.tat.value).toEqual(result(33)); expect(h.get).toHaveBeenCalledTimes(3)
  })
  it('latest request wins even with identical date parameters', async () => {
    const h = setup(), a1 = start(h, A), a2 = start(h, A)
    await complete(a2, result(22)); await complete(a1, result(11)); expect(h.v.tat.value).toEqual(result(22))
  })
  it('clears an already displayed result immediately when the range changes without a new request', async () => {
    const h = setup(); await complete(start(h, A), result(11))
    h.v.tatRange.value = [...B]
    expect(h.v.tat.value).toBeNull(); expect(h.get).toHaveBeenCalledOnce()
  })
  it('invalidates an in-place date edit even before change triggers a new request', async () => {
    const h = setup(), a = start(h, A)
    h.v.tatRange.value[1] = B[1]
    await complete(a, result(11)); expect(h.v.tat.value).toBeNull()
  })
  it('range A→B→A without a new request cannot revive the original read', async () => {
    const h = setup(), a = start(h, A)
    h.v.tatRange.value = [...B]; h.v.tatRange.value = [...A]
    await complete(a, result(11)); expect(h.v.tat.value).toBeNull()
  })
  it('a refresh clears the previous response while awaiting the new one', async () => {
    const h = setup(); await complete(start(h, A), result(11)); const next = start(h, A)
    expect(h.v.tat.value).toBeNull(); await complete(next, result(22)); expect(h.v.tat.value).toEqual(result(22))
  })
  it('clearing dates clears the old result and keeps the original default-query parameters', async () => {
    const h = setup(); await complete(start(h, A), result(11)); const empty = start(h, null)
    expect(h.v.tat.value).toBeNull(); expect(h.get).toHaveBeenLastCalledWith('/lis/tat', { params: { from: undefined, to: undefined } })
    await complete(empty, result(99)); expect(h.v.tat.value).toEqual(result(99))
  })
  it.each(['qc', 'micro'])('leaving TAT for %s removes prior data and invalidates waiting reads', async tab => {
    const h = setup(); await complete(start(h, A), result(11)); const waiting = start(h, A)
    h.v.tab.value = tab; expect(h.v.tat.value).toBeNull()
    await complete(waiting, result(22)); expect(h.v.tat.value).toBeNull()
  })
  it('leaving and returning to TAT cannot revive the old tab request', async () => {
    const h = setup(), old = start(h, A)
    h.v.tab.value = 'qc'; h.v.tab.value = 'tat'
    await complete(old, result(11)); expect(h.v.tat.value).toBeNull()
    await complete(start(h, A), result(22)); expect(h.v.tat.value).toEqual(result(22))
  })
  it('unmount clears displayed data and blocks a pending result', async () => {
    const h = setup(); await complete(start(h, A), result(11)); const waiting = start(h, B)
    h.leave(); expect(h.v.tat.value).toBeNull(); await complete(waiting, result(22)); expect(h.v.tat.value).toBeNull()
  })
  it.each(['employee-B', ''])('changing the application token to %s clears and invalidates', async token => {
    const h = setup(); await complete(start(h, A), result(11)); const waiting = start(h, A)
    h.auth.token = token; h.storage.set('hip_token', token)
    expect(h.v.tat.value).toBeNull(); await complete(waiting, result(22)); expect(h.v.tat.value).toBeNull()
  })
  it('a logout/login token ABA cannot revive an earlier employee read', async () => {
    const h = setup(), waiting = start(h, A)
    h.auth.token = ''; h.auth.token = 'employee-A'
    await complete(waiting, result(11)); expect(h.v.tat.value).toBeNull()
    await complete(start(h, A), result(22)); expect(h.v.tat.value).toEqual(result(22))
  })
  it('rejects a stale result when actual hip_token changes without a store update', async () => {
    const h = setup(), waiting = start(h, A)
    h.storage.set('hip_token', 'employee-B')
    await complete(waiting, result(11)); expect(h.v.tat.value).toBeNull()
  })
  it.each(['hip_token', null])('a browser storage event for %s invalidates even token ABA', async key => {
    const h = setup(), waiting = start(h, A)
    h.storageEvent(key)
    await complete(waiting, result(11)); expect(h.v.tat.value).toBeNull()
  })
})

describe('TAT compatible transport and neighboring tabs', () => {
  it('accepts the complete original response unchanged and does not mutate the selected range', async () => {
    const h = setup(), data = { ...result(0), extra: 'original extension', collect_to_receive_min: null }
    await complete(start(h, A), data)
    expect(h.v.tat.value).toEqual(data); expect(h.v.tatRange.value).toEqual(A)
    expect(h.get).toHaveBeenCalledOnce(); expect(h.post).not.toHaveBeenCalled()
  })
  it('keeps the default query on initial TAT entry', async () => {
    const h = setup(); h.get.mockResolvedValueOnce(reply(result(0)) as any); h.v.onTab(); await Vue.nextTick()
    expect(h.get).toHaveBeenCalledWith('/lis/tat', { params: { from: undefined, to: undefined } }); expect(h.v.tat.value).toEqual(result(0))
  })
  it.each([
    ['network', new Error('network')], ['401', { response: { status: 401, data: { code: 1000 } } }],
    ['403', { response: { status: 403, data: { code: 1005 } } }], ['business', { bizCode: 4630 }],
  ])('current %s failures propagate and leave no previous-period data', async (_kind, error) => {
    const h = setup(); await complete(start(h, A), result(11)); const failing = start(h, B)
    const expectation = expect(failing.done).rejects.toBe(error); failing.pending.reject(error); await expectation
    expect(h.v.tat.value).toBeNull(); expect(h.get).toHaveBeenCalledTimes(2); expect(h.post).not.toHaveBeenCalled()
  })
  it('stale failure still propagates without erasing the newer success', async () => {
    const h = setup(), a = start(h, A), b = start(h, B), error = new Error('old failure')
    const expectation = expect(a.done).rejects.toBe(error)
    await complete(b, result(22)); a.pending.reject(error); await expectation
    expect(h.v.tat.value).toEqual(result(22)); expect(h.get).toHaveBeenCalledTimes(2)
  })
  it('latest failure does not permit an older successful result to fill the empty view', async () => {
    const h = setup(), a = start(h, A), b = start(h, B), error = new Error('new failure')
    const expectation = expect(b.done).rejects.toBe(error); b.pending.reject(error); await expectation
    await complete(a, result(11)); expect(h.v.tat.value).toBeNull()
  })
  it('an unrelated storage key does not discard a legitimate TAT result', async () => {
    const h = setup(), waiting = start(h, A); h.storageEvent('unrelated')
    await complete(waiting, result(11)); expect(h.v.tat.value).toEqual(result(11))
  })
  it('sessionStorage events do not discard a legitimate TAT result', async () => {
    const h = setup(), waiting = start(h, A); h.storageEvent('hip_token', { getItem: () => null })
    await complete(waiting, result(11)); expect(h.v.tat.value).toEqual(result(11))
  })
  it('removes its storage listener on unmount', () => {
    const h = setup(); h.leave()
    const added = h.box.window.addEventListener.mock.calls.filter((call: any[]) => call[0] === 'storage')
    expect(added).toHaveLength(1); expect(h.box.window.removeEventListener).toHaveBeenCalledWith('storage', added[0][1])
  })
  it('mount still loads only original micro samples', async () => {
    const h = setup(); for (const fn of h.mounted) await fn()
    expect(h.get).toHaveBeenCalledOnce(); expect(h.get).toHaveBeenCalledWith('/lis/micro/samples')
    expect(h.post).not.toHaveBeenCalled()
  })
  it.each([['micro', '/lis/micro/samples', 'microSamples'], ['qc', '/lis/qc/latest', 'qcLatest']])('original %s tab still reads its own list', async (tab, url, field) => {
    const h = setup(); const rows = [{ id: 8, original: true }]; h.v.tab.value = tab
    h.get.mockResolvedValueOnce(reply(rows) as any); h.v.onTab(); await Vue.nextTick()
    expect(h.get).toHaveBeenCalledOnce(); expect(h.get).toHaveBeenCalledWith(url); expect(h.v[field].value).toEqual(rows)
  })
  it('original IQC success keeps payload, result and latest refresh', async () => {
    const h = setup(); h.v.tab.value = 'qc'
    Object.assign(h.v.qc, { itemCode: 'ALT', lotNo: 'lot', targetValue: 10, sd: 1, measuredValue: 11 })
    const body = copy(h.v.qc), data = { inControl: true, zScore: 1, rule: '' }, rows = [{ item_code: 'ALT' }]
    h.post.mockResolvedValueOnce(reply(data) as any); h.get.mockResolvedValueOnce(reply(rows) as any)
    await h.v.submitQc()
    expect(h.post).toHaveBeenCalledExactlyOnceWith('/lis/qc', body); expect(h.v.qcResult.value).toEqual(data)
    expect(h.get).toHaveBeenCalledExactlyOnceWith('/lis/qc/latest'); expect(h.v.qcLatest.value).toEqual(rows)
  })
  it('original micro success keeps barcode, filtered AST, close, refresh and busy release', async () => {
    const h = setup(); h.v.tab.value = 'micro'; h.v.openMicro({ barcode: 'BC8', item_name: 'culture' })
    Object.assign(h.v.microForm, { organism: '菌', specimen: '血' }); h.v.microForm.ast.push({ antibiotic: 'A', method: 'KB', micValue: '10', sir: 'S' })
    const body = { ...copy(h.v.microForm), ast: copy(h.v.microForm.ast.filter((a: any) => a.antibiotic)) }
    await h.v.submitMicro()
    expect(h.post).toHaveBeenCalledExactlyOnceWith('/lis/micro/BC8', body)
    expect(h.v.microDialog.value).toBe(false); expect(h.v.microLoading.value).toBe(false)
    expect(h.get).toHaveBeenCalledExactlyOnceWith('/lis/micro/samples'); expect(h.box.ElMessage.success).toHaveBeenCalledExactlyOnceWith('已保存微生物药敏')
  })
  it('original IQC incomplete fields and micro blank organism remain local validation', async () => {
    const h = setup(); await h.v.submitQc(); h.v.openMicro({ barcode: 'BC8' }); await h.v.submitMicro()
    expect(h.box.ElMessage.warning.mock.calls.map((call: unknown[]) => call[0])).toEqual(['请填齐项目/批号/靶值/SD/实测', '菌种名必填'])
    expect(h.post).not.toHaveBeenCalled(); expect(h.get).not.toHaveBeenCalled(); expect(h.v.microLoading.value).toBe(false)
  })
})
