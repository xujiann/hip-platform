import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as Vue from 'vue'
import axios, { type AxiosRequestConfig } from 'axios'

// Execute the complete real setup and shared client. Only the Axios adapter,
// lifecycle, router and storage are controlled; interceptors and dedup are real.
const scopes: ReturnType<typeof Vue.effectScope>[] = []
const cleanups: Array<() => Promise<void>> = []
afterEach(async () => {
  try { for (const cleanup of cleanups.splice(0)) await cleanup() }
  finally { for (const scope of scopes.splice(0)) scope.stop() }
})
const settle = () => new Promise<void>(resolve => setTimeout(resolve, 0))
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const A = { id: 11, barcode: 'V142-A', order_id: 101, item_name: '培养A', patient_name: '模拟A' }
const B = { id: 12, barcode: 'V142-B', order_id: 102, item_name: '培养B', patient_name: '模拟B' }
const formA = { specimen: '血', organism: '模拟菌A', gram: 'NEG', colonyCount: '+++',
  ast: [{ antibiotic: '模拟药A', method: 'MIC', micValue: '1', sir: 'S' }] }
const formB = { specimen: '痰', organism: '模拟菌B', gram: 'POS', colonyCount: '+',
  ast: [{ antibiotic: '模拟药B', method: 'KB', micValue: '20', sir: 'R' }] }
type Wire = { config: any; body: any; resolved: boolean; resolve: (value: any) => void; reject: (error: any) => void }
function setup() {
  const path = process.env.HIP_V142_FRONT_BASELINE_ROOT
    ? resolve(process.env.HIP_V142_FRONT_BASELINE_ROOT, 'LisQcView.vue')
    : new URL('../../views/medtech/LisQcView.vue', import.meta.url)
  const component = readFileSync(path, 'utf8'), source = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const withoutImports = (source: string, filename: string) => {
    const parsed = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
    return parsed.statements.filter(value => !ts.isImportDeclaration(value)).map(value => value.getText(parsed)).join('\n')
  }
  const warnings = vi.fn(), errors = vi.fn(), successes = vi.fn(), pushes = vi.fn()
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
  const auth = Vue.reactive({ token: 'employee-A', user: { id: 7 } }), unmounted: Array<() => unknown> = [], mounted: Array<() => unknown> = []
  const box: Record<string, any> = { ...Vue, onMounted: (fn: () => unknown) => mounted.push(fn), onUnmounted: (fn: () => unknown) => unmounted.push(fn),
    useAuthStore: () => auth, localStorage, window, client, ElMessage: { warning: warnings, success: successes, error: errors } }
  const scope = Vue.effectScope(); scopes.push(scope)
  scope.run(() => runInNewContext(ts.transpileModule(withoutImports(source, 'LisQcView.ts') + `\nglobalThis.view = {
    tab, microSamples, microCur, microForm, microDialog, microLoading, openMicro, submitMicro, loadMicro, onTab
  };`, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box))
  const v = box.view as Record<string, any>
  cleanups.push(async () => {
    for (const fn of unmounted) fn()
    // A failing old-source assertion may leave continuations waiting. Settle all
    // controlled POSTs and any reads they spawn, without hiding unhandled errors.
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
  return { v, requests, client, posts, gets, warnings, errors, successes, pushes, storage, auth, window, mounted,
    leave: () => unmounted.forEach(fn => fn()),
    storageEvent: (key: string | null = 'hip_token', area: unknown = localStorage) => {
      for (const fn of listeners.get('storage') ?? []) fn({ key, storageArea: area })
    } }
}
type Harness = ReturnType<typeof setup>
const wires = (h: Harness, method: string) => h.requests.filter(r => r.config.method === method)
function open(h: Harness, row = A, body = formA) { h.v.openMicro(copy(row)); Object.assign(h.v.microForm, copy(body)) }
async function begin(h: Harness) {
  const count = wires(h, 'post').length, done = h.v.submitMicro() as Promise<void>; void done.catch(() => {})
  await settle(); return { done, post: wires(h, 'post')[count] }
}
async function start(h: Harness, row = A, body = formA) { open(h, row, body); return begin(h) }
async function read(h: Harness) {
  const count = wires(h, 'get').length, done = h.v.loadMicro() as Promise<void>; void done.catch(() => {})
  await settle(); return { done, get: wires(h, 'get')[count] }
}
async function resolveWire(wire: Wire, data: unknown = null, code = 0) {
  expect(wire).toBeDefined(); expect(wire.resolved).toBe(false); wire.resolved = true
  wire.resolve({ data: { code, message: code ? 'original failure' : 'ok', data }, status: 200, statusText: 'OK', headers: {}, config: wire.config }); await settle()
}
async function rejectWire(wire: Wire, error: any) {
  expect(wire).toBeDefined(); expect(wire.resolved).toBe(false); wire.resolved = true
  error.config = wire.config; wire.reject(error); await settle()
}
async function drainGets(h: Harness, rows: unknown = [A, B]) {
  for (const wire of wires(h, 'get').filter(r => !r.resolved)) await resolveWire(wire, rows)
}
async function save(h: Harness, row = A, body = formA) {
  const run = await start(h, row, body); await resolveWire(run.post); await drainGets(h); await run.done; return run
}

describe('micro save feedback belongs to its original modal and draft', () => {
  it('preserves five-key body, AST filtering, original one success, close and full received-sample refresh', async () => {
    const h = setup(); open(h); h.v.microForm.ast.push({ antibiotic: '', method: '', micValue: '', sir: 'S' })
    const run = await begin(h); expect(h.v.microLoading.value).toBe(true)
    await resolveWire(run.post); await drainGets(h); await run.done
    expect(run.post.body).toEqual(formA); expect(h.posts).toHaveBeenCalledExactlyOnceWith('/lis/micro/' + A.barcode, formA)
    expect(h.successes).toHaveBeenCalledExactlyOnceWith('已保存微生物药敏'); expect(h.v.microDialog.value).toBe(false)
    expect(h.v.microLoading.value).toBe(false); expect(h.v.microSamples.value).toEqual([A, B])
    expect(h.gets).toHaveBeenCalledExactlyOnceWith('/lis/micro/samples') // Saving does not remove RECEIVED queue entries.
  })
  it.each([A, B])('a delayed old save cannot close a new modal for $barcode or claim its unsaved draft', async row => {
    const h = setup(), run = await start(h); h.v.microDialog.value = false; open(h, row, formB)
    await resolveWire(run.post)
    expect(h.v.microDialog.value).toBe(true); expect(h.v.microCur.value).toEqual(row); expect(h.v.microForm).toEqual(formB)
    expect(h.successes).not.toHaveBeenCalled(); expect(h.v.microLoading.value).toBe(false)
    expect(wires(h, 'get')).toHaveLength(1); await drainGets(h); await run.done
    expect(h.v.microSamples.value).toEqual([A, B])
  })
  it('opening B directly while A waits also creates a distinct modal session', async () => {
    const h = setup(), run = await start(h); open(h, B, formB); await resolveWire(run.post)
    expect(h.v.microDialog.value).toBe(true); expect(h.v.microForm).toEqual(formB); expect(h.successes).not.toHaveBeenCalled()
    await drainGets(h); await run.done
  })
  it('A→B→A modal sessions cannot revive the first A feedback even with identical draft values', async () => {
    const h = setup(), run = await start(h); open(h, B, formB); open(h)
    await resolveWire(run.post); expect(h.v.microDialog.value).toBe(true); expect(h.successes).not.toHaveBeenCalled()
    await drainGets(h); await run.done
  })
  it('cancel without reopen keeps the modal closed and does not show a late success toast', async () => {
    const h = setup(), run = await start(h); h.v.microDialog.value = false
    expect(h.v.microLoading.value).toBe(false); await resolveWire(run.post)
    expect(h.successes).not.toHaveBeenCalled(); expect(h.v.microDialog.value).toBe(false)
    await drainGets(h); await run.done; expect(h.v.microSamples.value).toEqual([A, B])
  })
  it('owns the original barcode and independent nested AST request snapshot', async () => {
    const h = setup(), run = await start(h), passed = h.posts.mock.calls[0][1]
    h.v.microForm.ast[0].antibiotic = 'edited after submit'; h.v.microForm.ast[0].sir = 'R'; h.v.microCur.value.barcode = B.barcode
    expect(passed).toEqual(formA); expect(run.post.body).toEqual(formA); expect(run.post.config.url).toBe('/lis/micro/' + A.barcode)
    await resolveWire(run.post); expect(h.v.microDialog.value).toBe(true); expect(h.successes).not.toHaveBeenCalled()
    await drainGets(h); await run.done
  })
})

const draftEdits = [
  ['specimen', (h: Harness) => { h.v.microForm.specimen = '痰' }],
  ['organism', (h: Harness) => { h.v.microForm.organism = 'other' }],
  ['gram', (h: Harness) => { h.v.microForm.gram = 'POS' }],
  ['colonyCount', (h: Harness) => { h.v.microForm.colonyCount = '+' }],
  ['antibiotic', (h: Harness) => { h.v.microForm.ast[0].antibiotic = 'other' }],
  ['method', (h: Harness) => { h.v.microForm.ast[0].method = 'KB' }],
  ['micValue', (h: Harness) => { h.v.microForm.ast[0].micValue = '20' }],
  ['sir', (h: Harness) => { h.v.microForm.ast[0].sir = 'R' }],
  ['append AST', (h: Harness) => { h.v.microForm.ast.push({ antibiotic: 'other', method: 'KB', micValue: '20', sir: 'R' }) }],
  ['remove AST', (h: Harness) => { h.v.microForm.ast.splice(0, 1) }],
  ['draft ABA', (h: Harness) => { h.v.microForm.organism = 'other'; h.v.microForm.organism = formA.organism }],
] as const
describe('micro full draft attribution', () => {
  it.each(draftEdits)('editing %s while POST waits preserves the current draft and invalidates feedback, but allows global refresh', async (_name, edit) => {
    const h = setup(), run = await start(h); edit(h); const changed = copy(h.v.microForm)
    await resolveWire(run.post)
    expect(h.v.microDialog.value).toBe(true); expect(h.v.microForm).toEqual(changed); expect(h.successes).not.toHaveBeenCalled()
    expect(run.post.body).toEqual(formA); expect(wires(h, 'get')).toHaveLength(1)
    await drainGets(h); await run.done; expect(h.v.microLoading.value).toBe(false); expect(h.v.microSamples.value).toEqual([A, B])
  })
  it('opening and editing another modal does not clear or filter an already pending global list', async () => {
    const h = setup(), run = await read(h); open(h, B, formB); await resolveWire(run.get, [A, B]); await run.done
    expect(h.v.microSamples.value).toEqual([A, B]); expect(h.v.microDialog.value).toBe(true)
  })
})

describe('micro independent saves and busy ownership', () => {
  it('a new modal starts idle while a prior modal save is pending', async () => {
    const h = setup(), a = await start(h); h.v.microDialog.value = false; open(h, B, formB)
    expect(h.v.microLoading.value).toBe(false); await resolveWire(a.post); await drainGets(h); await a.done
  })
  it('an old successful POST cannot release the newer modal busy state', async () => {
    const h = setup(), a = await start(h), b = await start(h, B, formB)
    await resolveWire(a.post); await drainGets(h); await a.done
    expect(h.v.microDialog.value).toBe(true); expect(h.v.microLoading.value).toBe(true); expect(h.successes).not.toHaveBeenCalled()
    await resolveWire(b.post); await drainGets(h); await b.done
    expect(h.v.microDialog.value).toBe(false); expect(h.v.microLoading.value).toBe(false); expect(h.successes).toHaveBeenCalledOnce()
    expect(wires(h, 'post').map(r => r.config.url)).toEqual(['/lis/micro/' + A.barcode, '/lis/micro/' + B.barcode])
  })
  it('a delayed old post-save GET cannot release a newer modal busy state', async () => {
    const h = setup(), a = await start(h); await resolveWire(a.post); const oldRead = wires(h, 'get')[0]
    const b = await start(h, B, formB); await resolveWire(oldRead, [A, B]); await a.done
    expect(h.v.microLoading.value).toBe(true); expect(h.v.microDialog.value).toBe(true)
    await resolveWire(b.post); await drainGets(h); await b.done; expect(h.successes).toHaveBeenCalledTimes(2)
  })
  it('an older unknown POST failure propagates without releasing newer busy ownership', async () => {
    const h = setup(), a = await start(h), b = await start(h, B, formB), error = new Error('unknown old write')
    const expected = expect(a.done).rejects.toBe(error); await rejectWire(a.post, error); await expected
    expect(h.v.microLoading.value).toBe(true); expect(h.v.microDialog.value).toBe(true); expect(h.successes).not.toHaveBeenCalled()
    await resolveWire(b.post); await drainGets(h); await b.done
  })
  it('same URL with different bodies remains two legal independent writes with only the latest unchanged draft feedback', async () => {
    const h = setup(), a = await start(h); Object.assign(h.v.microForm, copy(formB)); const b = await begin(h)
    expect(wires(h, 'post').map(r => r.body)).toEqual([formA, formB])
    await resolveWire(a.post); await drainGets(h); await a.done; expect(h.successes).not.toHaveBeenCalled(); expect(h.v.microLoading.value).toBe(true)
    await resolveWire(b.post); await drainGets(h); await b.done; expect(h.successes).toHaveBeenCalledOnce(); expect(h.v.microDialog.value).toBe(false)
  })
  it('same pending URL/body duplicate warns once, sends one wire and cannot steal the first save ownership', async () => {
    const h = setup(), first = await start(h), duplicate = await begin(h)
    await expect(duplicate.done).resolves.toBeUndefined(); expect(duplicate.post).toBeUndefined(); expect(wires(h, 'post')).toHaveLength(1)
    expect(h.warnings).toHaveBeenCalledExactlyOnceWith('操作处理中，请勿重复提交'); expect(h.v.microLoading.value).toBe(true)
    await resolveWire(first.post); await drainGets(h); await first.done
    expect(h.successes).toHaveBeenCalledOnce(); expect(h.v.microDialog.value).toBe(false)
  })
  it('identical content for another barcode is legal and not treated as a duplicate', async () => {
    const h = setup(), a = await start(h), b = await start(h, B, formA)
    expect(wires(h, 'post')).toHaveLength(2); expect(h.warnings).not.toHaveBeenCalled()
    await resolveWire(b.post); await drainGets(h); await b.done; await resolveWire(a.post); await drainGets(h); await a.done
    expect(h.successes).toHaveBeenCalledOnce(); expect(h.v.microDialog.value).toBe(false)
  })
  it('POST completion releases the same body for a new modal save even while the old GET still waits', async () => {
    const h = setup(), a = await start(h); await resolveWire(a.post); const b = await start(h)
    expect(wires(h, 'post')).toHaveLength(2); expect(h.warnings).not.toHaveBeenCalled()
    await resolveWire(b.post); await drainGets(h); await a.done; await b.done; expect(h.successes).toHaveBeenCalledTimes(2)
  })
  it('one completed body cannot remove dedup protection from another pending body', async () => {
    const h = setup(), a = await start(h), b = await start(h, B, formB)
    await resolveWire(a.post); await drainGets(h); await a.done; const duplicate = await begin(h)
    await expect(duplicate.done).resolves.toBeUndefined(); expect(wires(h, 'post')).toHaveLength(2); expect(h.v.microLoading.value).toBe(true)
    await resolveWire(b.post); await drainGets(h); await b.done; expect(h.successes).toHaveBeenCalledOnce()
  })
})

describe('micro global samples use independent read ownership', () => {
  it('new full sample read survives an older response finishing last', async () => {
    const h = setup(), a = await read(h), b = await read(h)
    await resolveWire(b.get, [B]); await b.done; await resolveWire(a.get, [A]); await a.done
    expect(h.v.microSamples.value).toEqual([B]); expect(h.posts).not.toHaveBeenCalled()
  })
  it('a tab-entry old read cannot overwrite a later save refresh', async () => {
    const h = setup(), old = await read(h), saved = await start(h)
    await resolveWire(saved.post); await resolveWire(wires(h, 'get')[1], [A, B]); await saved.done
    await resolveWire(old.get, [A]); await old.done; expect(h.v.microSamples.value).toEqual([A, B])
  })
  it('a late known old save may start a fresh global DB read after the newer save, without re-claiming modal feedback', async () => {
    const h = setup(), a = await start(h), b = await start(h, B, formB)
    await resolveWire(b.post); await drainGets(h, [B]); await b.done
    await resolveWire(a.post); expect(wires(h, 'get')).toHaveLength(2); await drainGets(h, [A, B]); await a.done
    expect(h.v.microSamples.value).toEqual([A, B]); expect(h.successes).toHaveBeenCalledOnce()
  })
  it('the latest read failure propagates and cannot let an older response overwrite retained rows', async () => {
    const h = setup(); h.v.microSamples.value = [B]; const a = await read(h), b = await read(h), error = new Error('samples unavailable')
    const expected = expect(b.done).rejects.toBe(error); await rejectWire(b.get, error); await expected
    await resolveWire(a.get, [A]); await a.done; expect(h.v.microSamples.value).toEqual([B])
  })
})

const invalidate = [
  ['leave for IQC', (h: Harness) => { h.v.tab.value = 'qc' }],
  ['leave for TAT', (h: Harness) => { h.v.tab.value = 'tat' }],
  ['tab ABA', (h: Harness) => { h.v.tab.value = 'qc'; h.v.tab.value = 'micro' }],
  ['unmount', (h: Harness) => h.leave()],
  ['new auth token', (h: Harness) => { h.auth.token = 'employee-B'; h.storage.set('hip_token', 'employee-B') }],
  ['auth token ABA', (h: Harness) => { h.auth.token = ''; h.auth.token = 'employee-A' }],
  ['current actual token changed', (h: Harness) => { h.storage.set('hip_token', 'employee-B') }],
  ['storage hip_token', (h: Harness) => h.storageEvent()],
  ['storage clear', (h: Harness) => h.storageEvent(null)],
] as const
describe('micro page and employee attribution', () => {
  it.each(invalidate)('%s prevents old POST feedback or a global refresh continuation', async (_name, change) => {
    const h = setup(), run = await start(h); change(h); await resolveWire(run.post)
    expect(h.successes).not.toHaveBeenCalled(); expect(wires(h, 'get')).toHaveLength(0); expect(wires(h, 'post')).toHaveLength(1)
    await run.done
  })
  it.each(invalidate)('%s prevents old sample reads from filling the new page', async (_name, change) => {
    const h = setup(), run = await read(h); change(h); await resolveWire(run.get, [A]); await run.done
    expect(h.v.microSamples.value).toEqual([]); expect(h.posts).not.toHaveBeenCalled()
  })
  it('old-page successful save cannot supersede the current page fresh list', async () => {
    const h = setup(), saved = await start(h); h.v.tab.value = 'qc'; h.v.tab.value = 'micro'
    const current = await read(h); await resolveWire(current.get, [B]); await current.done
    await resolveWire(saved.post); expect(wires(h, 'get')).toHaveLength(1); await saved.done
    expect(h.v.microSamples.value).toEqual([B]); expect(h.successes).not.toHaveBeenCalled()
  })
  it('one shared storage listener preserves legitimate ownership for unrelated keys and other storage areas', async () => {
    const h = setup(), saved = await start(h); h.storageEvent('unrelated'); h.storageEvent('hip_token', {})
    expect(h.window.addEventListener).toHaveBeenCalledOnce(); await resolveWire(saved.post); await drainGets(h); await saved.done
    expect(h.successes).toHaveBeenCalledOnce(); h.leave()
    expect(h.window.removeEventListener).toHaveBeenCalledExactlyOnceWith('storage', h.window.addEventListener.mock.calls[0][1])
  })
})

describe('micro original validation, truthful success and failures', () => {
  it.each(['', '   '])('blank organism %j is local validation without any transport', async organism => {
    const h = setup(); open(h); h.v.microForm.organism = organism; await h.v.submitMicro(); await settle()
    expect(h.warnings).toHaveBeenCalledExactlyOnceWith('菌种名必填'); expect(h.requests).toHaveLength(0); expect(h.v.microLoading.value).toBe(false)
  })
  it.each([7106, 7107, 7108])('business %s keeps the original error/draft and allows only a later explicit same-body retry', async code => {
    const h = setup(), run = await start(h), expected = expect(run.done).rejects.toMatchObject({ bizCode: code, message: 'original failure' })
    await resolveWire(run.post, null, code); await expected
    expect(h.v.microDialog.value).toBe(true); expect(h.v.microForm).toEqual(formA); expect(h.v.microLoading.value).toBe(false)
    expect(h.successes).not.toHaveBeenCalled(); expect(wires(h, 'get')).toHaveLength(0)
    const retry = await begin(h); expect(wires(h, 'post')).toHaveLength(2); await resolveWire(retry.post); await drainGets(h); await retry.done
  })
  it('unknown POST failure propagates without auto retry or GET and leaves the current draft', async () => {
    const h = setup(), run = await start(h), error = new Error('unknown network result'), expected = expect(run.done).rejects.toBe(error)
    await rejectWire(run.post, error); await expected
    expect(h.requests).toHaveLength(1); expect(h.v.microDialog.value).toBe(true); expect(h.v.microForm).toEqual(formA)
    expect(h.v.microLoading.value).toBe(false); expect(h.successes).not.toHaveBeenCalled()
  })
  it('HTTP403 retains shared client error handling and preserves the unsaved modal', async () => {
    const h = setup(), run = await start(h), error = Object.assign(new Error('forbidden'), { response: { status: 403, data: { code: 1005, message: 'forbidden' } } })
    const expected = expect(run.done).rejects.toBe(error); await rejectWire(run.post, error); await expected
    expect(h.errors).toHaveBeenCalledExactlyOnceWith('无该功能权限，请联系管理员分配角色'); expect(h.v.microDialog.value).toBe(true)
    expect(h.successes).not.toHaveBeenCalled(); expect(wires(h, 'get')).toHaveLength(0)
  })
  it('POST success followed by GET failure propagates without pretending the known save rolled back', async () => {
    const h = setup(), run = await start(h); await resolveWire(run.post)
    const error = new Error('read failed after save'), expected = expect(run.done).rejects.toBe(error)
    await rejectWire(wires(h, 'get')[0], error); await expected
    expect(h.successes).toHaveBeenCalledExactlyOnceWith('已保存微生物药敏'); expect(h.v.microDialog.value).toBe(false)
    expect(h.v.microLoading.value).toBe(false); expect(wires(h, 'post')).toHaveLength(1); expect(wires(h, 'get')).toHaveLength(1)
  })
  it('invalid draft click cannot take busy or feedback ownership from its in-flight save', async () => {
    const h = setup(), run = await start(h); h.v.microForm.organism = ' '; await h.v.submitMicro()
    expect(wires(h, 'post')).toHaveLength(1); expect(h.v.microLoading.value).toBe(true)
    await resolveWire(run.post); expect(h.successes).not.toHaveBeenCalled(); expect(h.v.microDialog.value).toBe(true)
    await drainGets(h); await run.done; expect(h.v.microLoading.value).toBe(false)
  })
})
