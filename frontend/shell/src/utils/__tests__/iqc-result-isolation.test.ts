import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as Vue from 'vue'
import axios, { type AxiosRequestConfig } from 'axios'

// Execute both complete real setup and real shared client. Only the Axios wire adapter,
// lifecycle, router and storage are controlled; actual interceptors/dedup remain active.
const scopes: ReturnType<typeof Vue.effectScope>[] = []
const cleanups: Array<() => Promise<void>> = []
afterEach(async () => {
  try { for (const cleanup of cleanups.splice(0)) await cleanup() }
  finally { for (const scope of scopes.splice(0)) scope.stop() }
})
const settle = () => new Promise<void>(resolve => setTimeout(resolve, 0))
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const A = { itemCode: 'V141', level: 'L1', lotNo: 'LOT', targetValue: 10, sd: 1, measuredValue: 14 }
const B = { ...A, measuredValue: 11 }
const bad = { inControl: false, zScore: 4, rule: '1-3s' }
const good = { inControl: true, zScore: 1, rule: '' }
const latest = (value: number) => [{ item_code: 'V141', level: 'L1', lot_no: 'LOT', measured_value: value,
  z_score: value - 10, in_control: value < 13, rule_broken: value < 13 ? null : '1-3s', measured_at: '2026-10-28T01:00:00Z' },
{ item_code: 'OTHER', level: 'L2', lot_no: 'GLOBAL', measured_value: 2, z_score: 0, in_control: true, rule_broken: null, measured_at: '2026-10-27T00:00:00Z' }]
type Wire = { config: any; body: any; resolved: boolean; resolve: (value: any) => void; reject: (error: any) => void }
function setup() {
  const path = process.env.HIP_V141_FRONT_BASELINE_ROOT
    ? resolve(process.env.HIP_V141_FRONT_BASELINE_ROOT, 'LisQcView.vue')
    : new URL('../../views/medtech/LisQcView.vue', import.meta.url)
  const component = readFileSync(path, 'utf8'), source = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const withoutImports = (source: string, filename: string) => {
    const parsed = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
    return parsed.statements.filter(value => !ts.isImportDeclaration(value)).map(value => value.getText(parsed)).join('\n')
  }
  const warnings = vi.fn(), errors = vi.fn(), pushes = vi.fn()
  const storage = new Map([['hip_token', 'employee-A']])
  const localStorage = { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) }
  const clientBox: Record<string, any> = { axios, ElMessage: { warning: warnings, error: errors },
    router: { push: pushes }, localStorage, Blob, exports: {} }
  const clientSource = readFileSync(new URL('../../api/client.ts', import.meta.url), 'utf8')
  runInNewContext(ts.transpileModule(withoutImports(clientSource, 'client.ts'), { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
  } }).outputText, clientBox)
  const client = clientBox.exports.default, requests: Wire[] = []
  client.defaults.adapter = (config: AxiosRequestConfig) => new Promise((resolve, reject) => {
    requests.push({ config, body: typeof config.data === 'string' ? JSON.parse(config.data) : copy(config.data ?? null), resolved: false, resolve, reject })
  })
  const posts = vi.spyOn(client, 'post'), gets = vi.spyOn(client, 'get')
  const listeners = new Map<string, Set<(event: unknown) => void>>()
  const window = { addEventListener: vi.fn((kind: string, fn: (event: unknown) => void) => {
    if (!listeners.has(kind)) listeners.set(kind, new Set()); listeners.get(kind)!.add(fn)
  }), removeEventListener: vi.fn((kind: string, fn: (event: unknown) => void) => listeners.get(kind)?.delete(fn)) }
  const auth = Vue.reactive({ token: 'employee-A', user: { id: 7 } }), unmounted: Array<() => unknown> = []
  const box: Record<string, any> = { ...Vue, onMounted: vi.fn(), onUnmounted: (fn: () => unknown) => unmounted.push(fn),
    auth, useAuthStore: () => auth, localStorage, window, client, ElMessage: { warning: warnings, success: vi.fn(), error: errors } }
  const scope = Vue.effectScope(); scopes.push(scope)
  scope.run(() => runInNewContext(ts.transpileModule(withoutImports(source, 'LisQcView.ts') + `\nglobalThis.view = {
    tab, qc, qcResult, qcLatest, submitQc, loadQcLatest, tatRange, tat, loadTat
  };`, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box))
  const v = box.view as Record<string, any>; v.tab.value = 'qc'; Object.assign(v.qc, A)
  cleanups.push(async () => {
    for (const fn of unmounted) fn()
    // Old production can issue a forbidden continuation GET after an assertion
    // fails. Settle every controlled transport, including those spawned by cleanup.
    for (let turn = 0; turn < 10; ++turn) {
      const pending = requests.filter(request => !request.resolved)
      if (!pending.length) return
      for (const request of pending) {
        request.resolved = true
        request.resolve({ data: { code: 0, message: 'cleanup', data: [] }, status: 200, statusText: 'OK', headers: {}, config: request.config })
      }
      await settle()
    }
    expect(requests.every(request => request.resolved)).toBe(true)
  })
  return { v, requests, client, posts, gets, warnings, errors, pushes, storage, auth, window,
    leave: () => unmounted.forEach(fn => fn()),
    storageEvent: (key: string | null = 'hip_token', area: unknown = localStorage) => {
      for (const fn of listeners.get('storage') ?? []) fn({ key, storageArea: area })
    } }
}
type Harness = ReturnType<typeof setup>
const wires = (h: Harness, method: string) => h.requests.filter(r => r.config.method === method)
async function resolveWire(wire: Wire, data: unknown, code = 0) {
  expect(wire).toBeDefined(); expect(wire.resolved).toBe(false); wire.resolved = true
  wire.resolve({ data: { code, message: code ? 'original failure' : 'ok', data }, status: 200, statusText: 'OK', headers: {}, config: wire.config })
  await settle()
}
async function rejectWire(wire: Wire, error: any) {
  expect(wire).toBeDefined(); expect(wire.resolved).toBe(false); wire.resolved = true
  error.config = wire.config; wire.reject(error); await settle()
}
async function start(h: Harness, body = A) {
  Object.assign(h.v.qc, body); const count = wires(h, 'post').length
  const done = h.v.submitQc() as Promise<void>; void done.catch(() => {})
  await settle(); return { done, post: wires(h, 'post')[count] }
}
async function drainGets(h: Harness, data: unknown = []) {
  for (const wire of wires(h, 'get').filter(r => !r.resolved)) await resolveWire(wire, data)
}
async function save(h: Harness, body = A, data: unknown = bad, rows: unknown = latest(body.measuredValue)) {
  const run = await start(h, body); await resolveWire(run.post, data); await drainGets(h, rows); await run.done; return run
}

describe('IQC current judgment and actual independent saves', () => {
  it('retains original body, three-key judgment and the full global latest list', async () => {
    const h = setup(); await save(h)
    expect(wires(h, 'post')).toHaveLength(1); expect(wires(h, 'post')[0].body).toEqual(A)
    expect(h.v.qcResult.value).toEqual(bad); expect(h.v.qcLatest.value).toEqual(latest(14)); expect(h.v.qc).toEqual(A)
    expect(h.posts).toHaveBeenCalledOnce(); expect(h.gets).toHaveBeenCalledExactlyOnceWith('/lis/qc/latest')
    expect(h.warnings).not.toHaveBeenCalled()
  })
  it('two different bodies both reach the real client; delayed old POST cannot replace B', async () => {
    const h = setup(), a = await start(h, A), b = await start(h, B)
    expect(wires(h, 'post').map(r => r.body)).toEqual([A, B])
    await resolveWire(b.post, good); await drainGets(h, latest(11)); await b.done
    await resolveWire(a.post, bad)
    expect(h.v.qcResult.value).toEqual(good)
    expect(wires(h, 'get')).toHaveLength(2) // Old known success may refresh current global DB state.
    await drainGets(h, latest(11)); await a.done; expect(h.v.qcLatest.value).toEqual(latest(11))
  })
  it('old A success while B is pending does not display A, but may refresh all projects', async () => {
    const h = setup(), a = await start(h, A), b = await start(h, B)
    await resolveWire(a.post, bad); expect(h.v.qcResult.value).toBeNull()
    await drainGets(h, latest(14)); await a.done; expect(h.v.qcLatest.value).toEqual(latest(14))
    await resolveWire(b.post, good); await drainGets(h, latest(11)); await b.done; expect(h.v.qcResult.value).toEqual(good)
  })
  it('same body pending duplicate warns once, stays one actual POST and preserves original display ownership', async () => {
    const h = setup(), first = await start(h), duplicate = await start(h)
    await expect(duplicate.done).resolves.toBeUndefined()
    expect(duplicate.post).toBeUndefined(); expect(wires(h, 'post')).toHaveLength(1)
    expect(h.warnings).toHaveBeenCalledExactlyOnceWith('操作处理中，请勿重复提交')
    await resolveWire(first.post, bad); await drainGets(h, latest(14)); await first.done
    expect(h.v.qcResult.value).toEqual(bad); expect(wires(h, 'get')).toHaveLength(1)
  })
  it('a same-body new save is allowed as soon as POST resolves while its old GET still waits', async () => {
    const h = setup(), first = await start(h); await resolveWire(first.post, bad)
    expect(wires(h, 'get')).toHaveLength(1); const second = await start(h)
    expect(wires(h, 'post')).toHaveLength(2); expect(h.warnings).not.toHaveBeenCalled()
    await resolveWire(second.post, good); await drainGets(h, latest(14)); await first.done; await second.done
    expect(h.v.qcResult.value).toEqual(good)
  })
  it('one completed body does not release another still-pending body from dedup', async () => {
    const h = setup(), a = await start(h, A), b = await start(h, B)
    await resolveWire(a.post, bad); await drainGets(h); await a.done
    const duplicate = await start(h, B); await expect(duplicate.done).resolves.toBeUndefined()
    expect(wires(h, 'post')).toHaveLength(2); expect(h.warnings).toHaveBeenCalledOnce()
    await resolveWire(b.post, good); await drainGets(h); await b.done; expect(h.v.qcResult.value).toEqual(good)
  })
  it('latest accepted save generation is not stolen by an invalid click', async () => {
    const h = setup(), a = await start(h)
    // An invalid attempted draft invalidates A on edit, then remains empty; no extra POST.
    h.v.qc.itemCode = ''; const invalid = h.v.submitQc(); await expect(invalid).resolves.toBeUndefined()
    await resolveWire(a.post, bad); await drainGets(h); await a.done
    expect(wires(h, 'post')).toHaveLength(1); expect(h.v.qcResult.value).toBeNull()
  })
  it('takes an owned body snapshot before passing it to the asynchronous shared client', async () => {
    const h = setup(), run = await start(h), passed = h.posts.mock.calls[0][1]
    Object.assign(h.v.qc, B)
    expect(passed).toEqual(A); expect(wires(h, 'post')[0].body).toEqual(A)
    await resolveWire(run.post, bad); await drainGets(h); await run.done
  })
})

const changedFields = [
  ['itemCode', 'OTHER'], ['level', 'L2'], ['lotNo', 'NEWLOT'], ['targetValue', 12], ['sd', 2], ['measuredValue', 11],
] as const
describe('IQC judgment belongs to the unchanged six-field draft', () => {
  it.each(changedFields)('editing %s clears a displayed judgment but preserves the entire global list', async (field, value) => {
    const h = setup(); await save(h); h.v.qc[field] = value
    expect(h.v.qcResult.value).toBeNull(); expect(h.v.qcLatest.value).toEqual(latest(14))
    expect(wires(h, 'post')).toHaveLength(1); expect(wires(h, 'get')).toHaveLength(1)
  })
  it.each(changedFields)('editing %s while POST waits prevents old judgment, preserves saved body and permits global refresh', async (field, value) => {
    const h = setup(), run = await start(h); h.v.qc[field] = value
    await resolveWire(run.post, bad); expect(h.v.qcResult.value).toBeNull()
    expect(wires(h, 'post')[0].body).toEqual(A); expect(wires(h, 'get')).toHaveLength(1)
    await drainGets(h, latest(14)); await run.done; expect(h.v.qcLatest.value).toEqual(latest(14))
  })
  it('draft A→B→A without a second save cannot revive an old result', async () => {
    const h = setup(), run = await start(h); h.v.qc.measuredValue = 11; h.v.qc.measuredValue = 14
    await resolveWire(run.post, bad); await drainGets(h, latest(14)); await run.done
    expect(h.v.qcResult.value).toBeNull(); expect(h.v.qcLatest.value).toEqual(latest(14))
  })
  it('A→B→A with a genuine third save belongs to the third result', async () => {
    const h = setup(), a1 = await start(h, A), b = await start(h, B)
    await resolveWire(a1.post, bad); await drainGets(h); await a1.done
    const a2 = await start(h, A); await resolveWire(a2.post, good); await drainGets(h); await a2.done
    await resolveWire(b.post, bad); await drainGets(h); await b.done
    expect(wires(h, 'post')).toHaveLength(3); expect(h.v.qcResult.value).toEqual(good)
  })
})

describe('IQC global list has its own read ownership', () => {
  it('the newer full list survives an older GET finishing last', async () => {
    const h = setup(), first = h.v.loadQcLatest(), second = h.v.loadQcLatest(); await settle()
    const [a, b] = wires(h, 'get'); await resolveWire(b, latest(11)); await second; await resolveWire(a, latest(14)); await first
    expect(h.v.qcLatest.value).toEqual(latest(11)); expect(h.posts).not.toHaveBeenCalled()
  })
  it('tab-entry old GET cannot overwrite the later save refresh', async () => {
    const h = setup(), initial = h.v.loadQcLatest(); await settle(); const old = wires(h, 'get')[0]
    const saved = await start(h, B); await resolveWire(saved.post, good); const fresh = wires(h, 'get')[1]
    await resolveWire(fresh, latest(11)); await saved.done; await resolveWire(old, latest(14)); await initial
    expect(h.v.qcLatest.value).toEqual(latest(11)); expect(h.v.qcResult.value).toEqual(good)
  })
  it('editing the draft does not discard or filter an already pending global read', async () => {
    const h = setup(), reading = h.v.loadQcLatest(); await settle(); Object.assign(h.v.qc, { itemCode: 'UNSAVED', level: 'L9' })
    await resolveWire(wires(h, 'get')[0], latest(14)); await reading
    expect(h.v.qcLatest.value).toEqual(latest(14)); expect(h.v.qcResult.value).toBeNull()
  })
  it('a latest failure propagates and cannot permit an older read to overwrite retained global rows', async () => {
    const h = setup(); h.v.qcLatest.value = latest(9)
    const first = h.v.loadQcLatest(), second = h.v.loadQcLatest(); void second.catch(() => {}); await settle()
    const error = new Error('latest unavailable'), expected = expect(second).rejects.toBe(error)
    await rejectWire(wires(h, 'get')[1], error); await expected
    await resolveWire(wires(h, 'get')[0], latest(14)); await first; expect(h.v.qcLatest.value).toEqual(latest(9))
  })
})

const invalidate = [
  ['leave for micro', (h: Harness) => { h.v.tab.value = 'micro' }],
  ['leave for TAT', (h: Harness) => { h.v.tab.value = 'tat' }],
  ['tab ABA', (h: Harness) => { h.v.tab.value = 'micro'; h.v.tab.value = 'qc' }],
  ['unmount', (h: Harness) => h.leave()],
  ['new auth token', (h: Harness) => { h.auth.token = 'employee-B'; h.storage.set('hip_token', 'employee-B') }],
  ['auth token ABA', (h: Harness) => { h.auth.token = ''; h.auth.token = 'employee-A' }],
  ['current actual token changed', (h: Harness) => { h.storage.set('hip_token', 'employee-B') }],
  ['storage hip_token', (h: Harness) => h.storageEvent()],
  ['storage clear', (h: Harness) => h.storageEvent(null)],
] as const
describe('IQC page and employee attribution', () => {
  it.each(invalidate)('%s prevents old POST continuation from setting judgment or taking global read ownership', async (_name, change) => {
    const h = setup(), run = await start(h); change(h)
    await resolveWire(run.post, bad)
    expect(h.v.qcResult.value).toBeNull(); expect(wires(h, 'get')).toHaveLength(0); expect(wires(h, 'post')).toHaveLength(1)
    await run.done
  })
  it.each(invalidate)('%s prevents old global GET from filling the current view', async (_name, change) => {
    const h = setup(), reading = h.v.loadQcLatest(); await settle(); change(h)
    await resolveWire(wires(h, 'get')[0], latest(14)); await reading
    expect(h.v.qcLatest.value).toEqual([]); expect(h.posts).not.toHaveBeenCalled()
  })
  it('old-page successful POST cannot supersede the new-page initial read', async () => {
    const h = setup(), run = await start(h); h.v.tab.value = 'micro'; h.v.tab.value = 'qc'
    const reading = h.v.loadQcLatest(); await settle(); await resolveWire(wires(h, 'get')[0], latest(11)); await reading
    await resolveWire(run.post, bad)
    expect(wires(h, 'get')).toHaveLength(1); expect(h.v.qcLatest.value).toEqual(latest(11)); expect(h.v.qcResult.value).toBeNull()
    await run.done
  })
  it('only one shared storage listener remains and unrelated keys/storage areas preserve IQC ownership', async () => {
    const h = setup(), run = await start(h); h.storageEvent('unrelated'); h.storageEvent('hip_token', {})
    expect(h.window.addEventListener).toHaveBeenCalledOnce()
    await resolveWire(run.post, bad); await drainGets(h, latest(14)); await run.done; expect(h.v.qcResult.value).toEqual(bad)
    h.leave(); expect(h.window.removeEventListener).toHaveBeenCalledExactlyOnceWith('storage', h.window.addEventListener.mock.calls[0][1])
  })
})

describe('IQC truthful success, original failures and pending release', () => {
  it.each([
    ['missing item', { itemCode: '' }], ['missing lot', { lotNo: '' }], ['missing target', { targetValue: undefined }],
    ['missing SD', { sd: undefined }], ['missing measured', { measuredValue: undefined }],
  ])('%s remains original local validation without POST or GET', async (_name, change) => {
    const h = setup(); Object.assign(h.v.qc, change); await h.v.submitQc(); await settle()
    expect(h.warnings).toHaveBeenCalledExactlyOnceWith('请填齐项目/批号/靶值/SD/实测'); expect(h.requests).toHaveLength(0)
  })
  it.each([7109, 7110])('original business %s propagates and releases this body for an explicit later retry', async code => {
    const h = setup(), run = await start(h), expected = expect(run.done).rejects.toMatchObject({ bizCode: code, message: 'original failure' })
    await resolveWire(run.post, null, code); await expected; expect(wires(h, 'get')).toHaveLength(0)
    const retry = await start(h); expect(wires(h, 'post')).toHaveLength(2); await resolveWire(retry.post, bad); await drainGets(h); await retry.done
    expect(h.v.qcResult.value).toEqual(bad)
  })
  it('unknown POST failure propagates without a read or automatic retry; a later explicit same-body save works', async () => {
    const h = setup(), run = await start(h), error = new Error('unknown network result'), expected = expect(run.done).rejects.toBe(error)
    await rejectWire(run.post, error); await expected; expect(h.requests).toHaveLength(1); expect(h.v.qcResult.value).toBeNull()
    await save(h); expect(wires(h, 'post')).toHaveLength(2); expect(h.v.qcResult.value).toEqual(bad)
  })
  it('POST success followed by GET failure propagates while preserving the known saved judgment and avoiding a retry', async () => {
    const h = setup(), run = await start(h); await resolveWire(run.post, bad)
    const error = new Error('read failed after save'), expected = expect(run.done).rejects.toBe(error)
    await rejectWire(wires(h, 'get')[0], error); await expected
    expect(h.v.qcResult.value).toEqual(bad); expect(wires(h, 'post')).toHaveLength(1); expect(wires(h, 'get')).toHaveLength(1)
  })
  it('a rejected older POST keeps the newer judgment and releases only its own body', async () => {
    const h = setup(), a = await start(h, A), b = await start(h, B), error = new Error('old write unknown')
    await resolveWire(b.post, good); await drainGets(h, latest(11)); await b.done
    const expected = expect(a.done).rejects.toBe(error); await rejectWire(a.post, error); await expected
    expect(h.v.qcResult.value).toEqual(good); expect(wires(h, 'get')).toHaveLength(1)
  })
})
