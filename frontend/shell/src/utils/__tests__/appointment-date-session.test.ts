import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as Vue from 'vue'

// Execute the complete actual SFC. No proposed helper names, reconstructed
// handlers, stubbed loaders or fake watcher: Vue watch/nextTick are real.
// HTTP, UI confirmations, clock and component lifecycle are the boundaries.
const relative = '../../views/medtech/ApptQueueView.vue'
const source = readFileSync(new URL(relative, import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
const parsed = ts.createSourceFile('ApptQueueView.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const actual = parsed.statements.filter(x => !ts.isImportDeclaration(x)).map(x => x.getText(parsed)).join('\n')
const executable = ts.transpileModule(actual + `
globalThis.view = { tab, pending, appts, queryDate, selection, bookBusy, busyId,
 batchCancelLoading, callNextLoading, queues, loadPending, loadAppts, loadQueue,
 book, done, reschedule, batchCancel, callNext, isAppointmentPermissionDenied };`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
}).outputText
const A = '2026-10-26', B = '2026-10-27', ALL = '<all>'
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const answer = (data: unknown) => ({ data: { code: 0, message: 'success', data } })
function deferred() {
  let resolve!: (value: any) => void, reject!: (error: any) => void
  const promise = new Promise<any>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (value: unknown) => resolve(answer(value)), raw: resolve, reject }
}
type Item = Record<string, any>
function rows(date: string, first: number): Item[] {
  return ['BOOKED', 'CALLED', 'DONE', 'CANCELLED', 'BOOKED'].map((status, i) => ({
    id: first + i, order_id: first * 100 + i, slot_date: date, period: i ? 'PM' : 'AM',
    seq_no: i + 1, status, patient_name: '模拟' + date + '_' + i, item_name: '模拟检查' + i,
  }))
}
const scopes: ReturnType<typeof Vue.effectScope>[] = []
afterEach(() => { for (const scope of scopes.splice(0)) scope.stop() })
function setup() {
  const scope = Vue.effectScope(); scopes.push(scope)
  const a = rows(A, 11), b = rows(B, 21)
  const data = new Map<string, Item[]>([[A, a], [B, b]])
  const pendingRows = [{ order_id: 301, item_name: '模拟已收费申请', _date: A, _period: 'AM' }]
  const calls: { method: string; url: string; body: any }[] = []
  const routes: { method: string; url: string; handler: (body: any) => any }[] = []
  const unmounts: (() => void)[] = []
  const dateKey = (value: unknown) => value == null || value === '' ? ALL : String(value)
  const list = (date: unknown) => copy(data.get(dateKey(date)) ?? [])
  function dispatch(method: string, url: string, body?: any): Promise<any> {
    calls.push({ method, url, body: body === undefined ? null : copy(body) })
    const custom = routes.find(x => x.method === method && x.url === url)
    if (custom) return Promise.resolve(custom.handler(body))
    if (method === 'GET' && url === '/appointments') {
      if (dateKey(body?.params?.date) === ALL) return Promise.reject(Object.assign(new Error('Required request parameter date is missing'), { response: { status: 400 } }))
      return Promise.resolve(answer(list(body?.params?.date)))
    }
    if (method === 'GET' && url === '/appointments/pending') return Promise.resolve(answer(copy(pendingRows)))
    if (method === 'GET' && url.startsWith('/queue-center/')) return Promise.resolve(answer({ waiting: [{ id: 501 }], called: [{ id: 502 }] }))
    if (method === 'PUT' && url.endsWith('/done')) return Promise.resolve(answer(null))
    if (method === 'PUT' && url.endsWith('/reschedule')) return Promise.resolve(answer({ seqNo: 9 }))
    if (method === 'POST' && url === '/appointments/batch-cancel') return Promise.resolve(answer({ cancelled: 2 }))
    if (method === 'POST' && url === '/appointments') return Promise.resolve(answer({ seqNo: 8 }))
    if (method === 'POST' && url.endsWith('/call-next')) return Promise.resolve(answer({ patient_name: '模拟叫号患者' }))
    throw new Error('Unconfigured fixture endpoint: ' + method + ' ' + url)
  }
  const get = vi.fn((url: string, body?: any) => dispatch('GET', url, body))
  const put = vi.fn((url: string, body?: any) => dispatch('PUT', url, body))
  const post = vi.fn((url: string, body?: any) => dispatch('POST', url, body))
  const prompt = vi.fn(() => Promise.resolve({ value: B + '， PM' }))
  const messages = { success: vi.fn(), warning: vi.fn(), error: vi.fn() }
  const box: Record<string, any> = { ...Vue, todayLocal: () => A, client: { get, put, post },
    ElMessage: messages, ElMessageBox: { prompt }, onMounted: vi.fn(),
    onUnmounted: (fn: () => void) => unmounts.push(fn), onBeforeUnmount: (fn: () => void) => unmounts.push(fn) }
  scope.run(() => runInNewContext(executable, box))
  const v = box.view as Record<string, any>
  function route(method: string, url: string, handler: (body: any) => any) { routes.unshift({ method, url, handler }) }
  function hold(method: string, url: string) { const d = deferred(); route(method, url, () => d.promise); return d }
  function holdList(date: unknown) {
    const d = deferred(); route('GET', '/appointments', p => dateKey(p?.params?.date) === dateKey(date) ? d.promise : answer(list(p?.params?.date)))
    return d
  }
  function clear() { calls.length = 0; get.mockClear(); put.mockClear(); post.mockClear(); for (const x of Object.values(messages)) x.mockClear() }
  async function load(date: unknown = v.queryDate.value) { v.queryDate.value = date; await Vue.nextTick(); await v.loadAppts() }
  return { v, a, b, data, pendingRows, calls, get, put, post, prompt, messages,
    route, hold, holdList, list, load, clear, unmount: () => { unmounts.forEach(fn => fn()); scope.stop() } }
}
type Harness = ReturnType<typeof setup>
const writes = (h: Harness) => h.calls.filter(x => x.method !== 'GET')
const listReads = (h: Harness) => h.calls.filter(x => x.method === 'GET' && x.url === '/appointments')
const row = (h: Harness, id: number) => h.v.appts.value.find((x: Item) => x.id === id)
const state = (h: Harness) => copy({ date: h.v.queryDate.value, rows: h.v.appts.value, selection: h.v.selection.value })
async function turns() { for (let n = 0; n < 20; n++) await Promise.resolve() }
async function called(h: Harness, method: string, url: string) {
  await vi.waitFor(() => expect(h.calls.some(x => x.method === method && x.url === url)).toBe(true))
}
function invoke(h: Harness, kind: string, item: Item) {
  if (kind === 'batchCancel') return h.v.batchCancel()
  return h.v[kind](item)
}
const permission = () => Object.assign(new Error('已由共享client提示'), { response: { status: 403, data: { code: 1005 } } })

describe('actual appointment list dates and read generations', () => {
  it('loads the requested date with the original list body and no clinical mutation', async () => {
    const h = setup(); await h.load(A)
    expect(h.get).toHaveBeenCalledExactlyOnceWith('/appointments', { params: { date: A } })
    expect(h.v.appts.value).toEqual(h.a); expect(writes(h)).toEqual([])
  })
  it('a slow A read cannot replace a fully loaded B date list or B selection', async () => {
    const h = setup(); const d = h.holdList(A); const old = h.v.loadAppts()
    await h.load(B); h.v.selection.value = [row(h, 21)]; const before = state(h)
    d.resolve(h.a); await old; expect(state(h)).toEqual(before)
  })
  it('changing the date synchronously removes old rows and selection before any next tick', async () => {
    const h = setup(); await h.load(A); h.v.selection.value = [row(h, 11)]
    h.v.queryDate.value = B
    expect(h.v.appts.value).toEqual([]); expect(h.v.selection.value).toEqual([])
  })
  it('same-date explicit reload also removes stale rows and selection while pending', async () => {
    const h = setup(); await h.load(A); h.v.selection.value = [row(h, 11)]
    const d = h.holdList(A); const loading = h.v.loadAppts()
    const captured = state(h); d.resolve(h.a); await loading
    expect(captured.rows).toEqual([]); expect(captured.selection).toEqual([])
  })
  it('two reads for the same date accept only the newer generation', async () => {
    const h = setup(); const d = deferred(); let reads = 0
    h.route('GET', '/appointments', () => ++reads === 1 ? d.promise : answer([{ ...h.a[0], patient_name: '同日最新读' }]))
    const old = h.v.loadAppts(); await h.v.loadAppts(); h.v.selection.value = [h.v.appts.value[0]]
    const before = state(h); d.resolve(h.a); await old; expect(state(h)).toEqual(before)
  })
  it('A to B to A invalidates the original A response even though its date text matches again', async () => {
    const h = setup(); const d = deferred(); let aReads = 0
    h.route('GET', '/appointments', p => p.params.date === A && ++aReads === 1 ? d.promise : answer(h.list(p.params.date)))
    const old = h.v.loadAppts(); await h.load(B); await h.load(A)
    h.v.selection.value = [row(h, 15)]; const before = state(h); d.resolve([{ ...h.a[0], patient_name: '最早A' }]); await old
    expect(state(h)).toEqual(before)
  })
  it('a current failed read preserves the same rejection and leaves the appointment view unwritable', async () => {
    const h = setup(); await h.load(A); const error = new Error('当前列表失败')
    h.route('GET', '/appointments', () => Promise.reject(error))
    await expect(h.v.loadAppts()).rejects.toBe(error)
    expect(h.v.appts.value).toEqual([]); expect(h.v.selection.value).toEqual([])
  })
  it('a stale read rejection cannot reject or destroy a newer accepted date session', async () => {
    const h = setup(); const d = h.holdList(A); const old = h.v.loadAppts()
    // Attach the rejection observer before release, preserving raw unknown error
    // semantics for live reads while asking stale work to stop at its boundary.
    const observed = old.then(() => 'resolved', () => 'rejected')
    await h.load(B); const before = state(h); d.reject(new Error('旧A失败'))
    expect(await observed).toBe('resolved'); expect(state(h)).toEqual(before)
  })
  it('a late response after leaving the page cannot refill the appointment list', async () => {
    const h = setup(); const d = h.holdList(A); const old = h.v.loadAppts(); h.unmount(); const before = state(h)
    d.resolve(h.a); await old; expect(state(h)).toEqual(before)
  })
  it('a cleared date preserves omission of date and the same required-parameter refusal without a today fallback', async () => {
    const h = setup(), error = Object.assign(new Error('缺少必填date'), { response: { status: 400 } })
    h.route('GET', '/appointments', () => Promise.reject(error))
    await expect(h.load(null)).rejects.toBe(error)
    const sent = listReads(h)[0]; expect(sent.body?.params?.date == null || sent.body?.params?.date === '').toBe(true)
    expect(h.v.queryDate.value).toBeNull(); expect(h.v.appts.value).toEqual([]); expect(writes(h)).toEqual([])
  })
  it('a slow missing-date refusal cannot reject or overwrite a newly accepted concrete B date', async () => {
    const h = setup(); h.v.queryDate.value = null; await Vue.nextTick(); const d = h.holdList(null); const old = h.v.loadAppts()
    const observed = old.then(() => 'fulfilled', () => 'rejected')
    await h.load(B); const before = state(h); d.reject(Object.assign(new Error('date必填'), { response: { status: 400 } }))
    expect(await observed).toBe('fulfilled'); expect(state(h)).toEqual(before)
  })
})

describe('actual row and selection write-entry gates', () => {
  it.each(['done', 'reschedule', 'batchCancel'])('%s refuses a left-over A row/selection immediately after switching to B', async kind => {
    const h = setup(); await h.load(A); const old = row(h, 11); h.v.selection.value = [old]
    h.v.queryDate.value = B; h.clear()
    await invoke(h, kind, old); expect(writes(h)).toEqual([])
    if (kind === 'reschedule') expect(h.prompt).not.toHaveBeenCalled()
  })
  it.each(['done', 'reschedule', 'batchCancel'])('%s refuses stale input while the B list is actually pending', async kind => {
    const h = setup(); await h.load(A); const old = row(h, 11); const d = h.holdList(B)
    h.v.queryDate.value = B; await Vue.nextTick(); const loading = h.v.loadAppts(); h.v.selection.value = [old]; h.clear()
    const action = invoke(h, kind, old); await turns(); const sent = copy(writes(h))
    d.resolve(h.b); await Promise.all([loading, action]); expect(sent).toEqual([])
  })
  it.each(['done', 'reschedule', 'batchCancel'])('%s refuses stale input after the current list read fails', async kind => {
    const h = setup(); await h.load(A); const old = row(h, 11); const error = new Error('读取失败')
    h.route('GET', '/appointments', () => Promise.reject(error)); await h.v.loadAppts().catch(() => {})
    h.v.selection.value = [old]; h.clear()
    // Release write-triggered reads normally on the old implementation, so
    // a missing gate produces an assertion RED rather than a fixture rejection.
    h.route('GET', '/appointments', () => answer(h.a)); await invoke(h, kind, old)
    expect(writes(h)).toEqual([])
  })
  it.each(['done', 'reschedule'])('%s refuses a forged same-id object that was never loaded into this list', async kind => {
    const h = setup(); await h.load(A); const forged = { ...row(h, 11) }; h.clear(); await invoke(h, kind, forged)
    expect(writes(h)).toEqual([])
  })
  it.each(['done', 'reschedule'])('%s refuses the old object after a same-date replacement read', async kind => {
    const h = setup(); await h.load(A); const old = row(h, 11); await h.v.loadAppts(); h.clear()
    await invoke(h, kind, old); expect(writes(h)).toEqual([])
  })
  it.each(['DONE', 'CANCELLED'])('done refuses the current %s row', async status => {
    const h = setup(); await h.load(A); const item = h.v.appts.value.find((x: Item) => x.status === status); h.clear()
    await h.v.done(item); expect(writes(h)).toEqual([])
  })
  it.each(['CALLED', 'DONE', 'CANCELLED'])('reschedule refuses the current %s row without a prompt', async status => {
    const h = setup(); await h.load(A); const item = h.v.appts.value.find((x: Item) => x.status === status); h.clear()
    await h.v.reschedule(item); expect(writes(h)).toEqual([]); expect(h.prompt).not.toHaveBeenCalled()
  })
  it('batch refuses the whole selection containing a stale or non-BOOKED object rather than partially cancelling it', async () => {
    const h = setup(); await h.load(A); h.v.selection.value = [row(h, 11), row(h, 12)]; h.clear()
    await h.v.batchCancel(); expect(writes(h)).toEqual([])
  })
  it.each(['BOOKED', 'CALLED'])('done retains its legal %s row and original completion/list reload', async status => {
    const h = setup(); await h.load(A); const item = h.v.appts.value.find((x: Item) => x.status === status); h.clear()
    await h.v.done(item); expect(h.put).toHaveBeenCalledExactlyOnceWith(`/appointments/${item.id}/done`)
    expect(h.get).toHaveBeenCalledExactlyOnceWith('/appointments', { params: { date: A } }); expect(h.messages.success).not.toHaveBeenCalled()
  })
  it('a live BOOKED reschedule preserves prompt parsing, seqNo toast and original date reload', async () => {
    const h = setup(); await h.load(A); const item = row(h, 11); h.clear(); await h.v.reschedule(item)
    expect(h.put).toHaveBeenCalledExactlyOnceWith('/appointments/11/reschedule', { slotDate: B, period: 'PM' })
    expect(h.messages.success).toHaveBeenCalledExactlyOnceWith('已改约，新顺序号 9')
    expect(h.get).toHaveBeenCalledExactlyOnceWith('/appointments', { params: { date: A } })
  })
  it('live batch preserves the captured selection order, exact cancelled count and both original reloads', async () => {
    const h = setup(); await h.load(A); h.v.selection.value = [row(h, 15), row(h, 11)]; h.clear(); await h.v.batchCancel()
    expect(h.post).toHaveBeenCalledExactlyOnceWith('/appointments/batch-cancel', { ids: [15, 11] })
    expect(h.messages.success).toHaveBeenCalledExactlyOnceWith('已取消 2 条'); expect(h.v.selection.value).toEqual([])
    expect(h.get).toHaveBeenCalledTimes(2); expect(h.get).toHaveBeenCalledWith('/appointments/pending')
    expect(h.get).toHaveBeenCalledWith('/appointments', { params: { date: A } })
  })
  it('duplicate valid selected objects preserve duplicate ids and use the actual returned count', async () => {
    const h = setup(); await h.load(A); const item = row(h, 11); h.v.selection.value = [item, item]
    h.route('POST', '/appointments/batch-cancel', () => answer({ cancelled: 1 })); h.clear(); await h.v.batchCancel()
    expect(h.post).toHaveBeenCalledExactlyOnceWith('/appointments/batch-cancel', { ids: [11, 11] })
    expect(h.messages.success).toHaveBeenCalledExactlyOnceWith('已取消 1 条')
  })
})

describe('actual reschedule prompt and in-flight row ownership', () => {
  it.each(['date', 'same-date reload', 'A-B-A', 'unmount', 'status', 'id'])('reschedule prompt is invalidated by %s before any PUT', async change => {
    const h = setup(); await h.load(A); const item = row(h, 11); const d = deferred(); h.prompt.mockImplementation(() => d.promise)
    const old = h.v.reschedule(item)
    if (change === 'date') await h.load(B)
    if (change === 'same-date reload') await h.v.loadAppts()
    if (change === 'A-B-A') { await h.load(B); await h.load(A) }
    if (change === 'unmount') h.unmount()
    if (change === 'status') item.status = 'CALLED'
    if (change === 'id') item.id = 999
    h.clear(); d.raw({ value: B + ',PM' }); await old; expect(writes(h)).toEqual([])
  })
  it('cancelling the original prompt still sends nothing and leaves the current row intact', async () => {
    const h = setup(); await h.load(A); const item = row(h, 11); h.prompt.mockRejectedValue('cancel'); const before = state(h); h.clear()
    await h.v.reschedule(item); expect(writes(h)).toEqual([]); expect(state(h)).toEqual(before)
  })
  it.each(['done', 'reschedule', 'batchCancel'])('%s cannot overlap a pending done on the same id', async kind => {
    const h = setup(); await h.load(A); const item = row(h, 11); const d = h.hold('PUT', '/appointments/11/done')
    const old = h.v.done(item); h.v.selection.value = [item]; h.clear(); const duplicate = invoke(h, kind, item)
    await turns(); const sent = copy(writes(h)); d.resolve(null); await Promise.all([old, duplicate]); expect(sent).toEqual([])
  })
  it('a prompt already owns its appointment id against a duplicate prompt or done', async () => {
    const h = setup(); await h.load(A); const item = row(h, 11); const d = deferred(); h.prompt.mockImplementation(() => d.promise)
    const first = h.v.reschedule(item); const second = h.v.reschedule(item); const completion = h.v.done(item)
    await turns(); const prompts = h.prompt.mock.calls.length, sent = copy(writes(h)); d.raw({ value: B + ',PM' })
    await Promise.all([first, second, completion]); expect(prompts).toBe(1); expect(sent).toEqual([])
  })
  it('a pending batch owns every captured id against row writes and another overlapping batch', async () => {
    const h = setup(); await h.load(A); const one = row(h, 11), two = row(h, 15); h.v.selection.value = [one, two]
    const d = h.hold('POST', '/appointments/batch-cancel'); const first = h.v.batchCancel(); h.clear()
    const done = h.v.done(two); const again = h.v.batchCancel(); await turns(); const sent = copy(writes(h)); d.resolve({ cancelled: 2 })
    await Promise.all([first, done, again]); expect(sent).toEqual([])
  })
  it('different valid appointment ids may still be completed concurrently', async () => {
    const h = setup(); await h.load(A); const first = row(h, 11), second = row(h, 15)
    const d = h.hold('PUT', '/appointments/11/done'); const old = h.v.done(first); await h.v.done(second)
    const sent = copy(writes(h)); d.resolve(null); await old
    expect(sent.map(x => x.url)).toEqual(['/appointments/11/done', '/appointments/15/done'])
  })
  it('switching A to B to A cannot duplicate the same id while its original done is pending', async () => {
    const h = setup(); await h.load(A); const d = h.hold('PUT', '/appointments/11/done'); const old = h.v.done(row(h, 11))
    await h.load(B); await h.load(A); h.clear(); const again = h.v.done(row(h, 11)); await turns(); const sent = copy(writes(h))
    d.resolve(null); await Promise.all([old, again]); expect(sent).toEqual([])
  })
  it('returning A to B to A retains an unfinished original batch and cannot resubmit its ids', async () => {
    const h = setup(); await h.load(A); h.v.selection.value = [row(h, 11)]
    const d = h.hold('POST', '/appointments/batch-cancel'); const old = h.v.batchCancel()
    await h.load(B); await h.load(A); h.v.selection.value = [row(h, 11)]; h.clear()
    const again = h.v.batchCancel(); await turns(); const sent = copy(writes(h)), busy = h.v.batchCancelLoading.value
    d.resolve({ cancelled: 1 }); await Promise.all([old, again]); expect(sent).toEqual([]); expect(busy).toBe(true)
  })
  it('batch ids are a primitive snapshot even if rows and selection mutate after dispatch', async () => {
    const h = setup(); await h.load(A); const first = row(h, 11), second = row(h, 15); h.v.selection.value = [second, first]
    const d = h.hold('POST', '/appointments/batch-cancel'); const old = h.v.batchCancel(); const body = h.post.mock.calls[0][1]
    first.id = 999; h.v.selection.value = []; const captured = copy(body); d.resolve({ cancelled: 2 }); await old
    expect(captured).toEqual({ ids: [15, 11] })
  })
})

describe('actual old write completions and live refresh failure paths', () => {
  it.each(['done', 'reschedule', 'batchCancel'])('an obsolete A %s transport rejection does not reject or refresh the new B session', async kind => {
    const h = setup(); await h.load(A); const item = row(h, 11); h.v.selection.value = [item]
    const url = kind === 'batchCancel' ? '/appointments/batch-cancel' : `/appointments/11/${kind}`
    const d = h.hold(kind === 'batchCancel' ? 'POST' : 'PUT', url)
    const old = invoke(h, kind, item); const observed = old.then(() => 'fulfilled', () => 'rejected')
    await called(h, kind === 'batchCancel' ? 'POST' : 'PUT', url); await h.load(B); h.v.selection.value = [row(h, 21)]
    const before = state(h); h.clear(); d.reject(new Error('旧A响应错误'))
    expect(await observed).toBe('fulfilled'); expect(state(h)).toEqual(before); expect(h.get).not.toHaveBeenCalled()
  })
  it.each(['done', 'reschedule', 'batchCancel'])('late A %s completion cannot clear B selection, reload B or announce B success', async kind => {
    const h = setup(); await h.load(A); const item = row(h, 11); h.v.selection.value = [item]
    const url = kind === 'batchCancel' ? '/appointments/batch-cancel' : `/appointments/11/${kind}`
    const d = h.hold(kind === 'batchCancel' ? 'POST' : 'PUT', url); const old = invoke(h, kind, item)
    await called(h, kind === 'batchCancel' ? 'POST' : 'PUT', url); await h.load(B); h.v.selection.value = [row(h, 21)]
    const before = state(h); h.clear(); d.resolve(kind === 'reschedule' ? { seqNo: 9 } : kind === 'batchCancel' ? { cancelled: 1 } : null); await old
    expect(state(h)).toEqual(before); expect(listReads(h)).toEqual([]); expect(h.messages.success).not.toHaveBeenCalled()
    if (kind === 'batchCancel') expect(h.get).toHaveBeenCalledExactlyOnceWith('/appointments/pending')
  })
  it.each(['done', 'reschedule'])('late A %s finally cannot clear the busy row of an independent pending B operation', async kind => {
    const h = setup(); await h.load(A); const a = h.hold('PUT', `/appointments/11/${kind}`); const old = invoke(h, kind, row(h, 11))
    await called(h, 'PUT', `/appointments/11/${kind}`); await h.load(B)
    const b = h.hold('PUT', '/appointments/21/done'); const current = h.v.done(row(h, 21))
    a.resolve(kind === 'reschedule' ? { seqNo: 9 } : null); await old; const busy = h.v.busyId.value
    b.resolve(null); await current; expect(busy).toBe(21)
  })
  it('late A batch finally cannot clear B batch loading or B selection', async () => {
    const h = setup(); await h.load(A); h.v.selection.value = [row(h, 11)]; const a = deferred(), b = deferred(); let requests = 0
    h.route('POST', '/appointments/batch-cancel', () => ++requests === 1 ? a.promise : b.promise)
    const old = h.v.batchCancel(); await h.load(B); h.v.selection.value = [row(h, 21)]; const current = h.v.batchCancel()
    a.resolve({ cancelled: 1 }); await old; const busy = h.v.batchCancelLoading.value, selected = copy(h.v.selection.value)
    b.resolve({ cancelled: 1 }); await current; expect(busy).toBe(true); expect(selected.map((x: Item) => x.id)).toEqual([21])
  })
  it.each(['done', 'reschedule', 'batchCancel'])('%s still rejects the exact unknown error from its current successful-write list refresh', async kind => {
    const h = setup(); await h.load(A); const item = row(h, 11); h.v.selection.value = [item]
    const error = new Error('当前写后刷新失败'); h.route('GET', '/appointments', () => Promise.reject(error))
    await expect(invoke(h, kind, item)).rejects.toBe(error)
  })
  it.each(['done', 'reschedule', 'batchCancel'])('%s cannot repopulate a date after leaving the page', async kind => {
    const h = setup(); await h.load(A); const item = row(h, 11); h.v.selection.value = [item]
    const url = kind === 'batchCancel' ? '/appointments/batch-cancel' : `/appointments/11/${kind}`
    const d = h.hold(kind === 'batchCancel' ? 'POST' : 'PUT', url); const old = invoke(h, kind, item)
    await called(h, kind === 'batchCancel' ? 'POST' : 'PUT', url); h.unmount(); h.clear()
    d.resolve(kind === 'reschedule' ? { seqNo: 9 } : kind === 'batchCancel' ? { cancelled: 1 } : null); await old
    expect(h.get).not.toHaveBeenCalled(); expect(h.messages.success).not.toHaveBeenCalled()
  })
})

describe('original error envelopes and date-independent workflows', () => {
  it.each(['done', 'batchCancel'])('%s consumes only original HTTP403/code1005 without false success or reload', async kind => {
    const h = setup(); await h.load(A); const item = row(h, 11); h.v.selection.value = [item]; const before = state(h)
    h.route(kind === 'done' ? 'PUT' : 'POST', kind === 'done' ? '/appointments/11/done' : '/appointments/batch-cancel', () => Promise.reject(permission()))
    h.clear(); await expect(invoke(h, kind, item)).resolves.toBeUndefined(); expect(state(h)).toEqual(before)
    expect(h.get).not.toHaveBeenCalled(); expect(h.messages.success).not.toHaveBeenCalled(); expect(h.messages.error).not.toHaveBeenCalled()
  })
  it('reschedule consumes original4513 without success/reload and retains its live row', async () => {
    const h = setup(); await h.load(A); const item = row(h, 11); const before = state(h)
    h.route('PUT', '/appointments/11/reschedule', () => Promise.reject(Object.assign(new Error('旧4513'), { bizCode: 4513 })))
    h.clear(); await expect(h.v.reschedule(item)).resolves.toBeUndefined(); expect(state(h)).toEqual(before); expect(h.get).not.toHaveBeenCalled(); expect(h.messages.success).not.toHaveBeenCalled()
  })
  it.each(['done', 'reschedule', 'batchCancel'])('%s preserves all other live error objects and does not mutate its list', async kind => {
    const errors = [new Error('未知错误'), Object.assign(new Error('network'), { code: 'ERR_NETWORK' }),
      Object.assign(new Error('other403'), { response: { status: 403, data: { code: 9999 } } }),
      Object.assign(new Error('http200'), { response: { status: 200, data: { code: 1005 } } }),
      Object.assign(new Error('4512'), { bizCode: 4512 }), permission()]
    if (kind !== 'reschedule') errors.pop()
    for (const error of errors) {
      const h = setup(); await h.load(A); const item = row(h, 11); h.v.selection.value = [item]; const before = state(h)
      const url = kind === 'batchCancel' ? '/appointments/batch-cancel' : `/appointments/11/${kind}`
      h.route(kind === 'batchCancel' ? 'POST' : 'PUT', url, () => Promise.reject(error)); h.clear()
      await expect(invoke(h, kind, item)).rejects.toBe(error); expect(state(h)).toEqual(before); expect(h.get).not.toHaveBeenCalled()
    }
  })
  it('book remains legal for a pending order whose chosen slot differs from the appointment-list date', async () => {
    const h = setup(); await h.load(B); await h.v.loadPending(); const item = h.v.pending.value[0]; h.clear(); await h.v.book(item)
    expect(h.post).toHaveBeenCalledExactlyOnceWith('/appointments', { orderId: 301, slotDate: A, period: 'AM' })
    expect(h.messages.success).toHaveBeenCalledExactlyOnceWith('预约成功，顺序号 8')
    expect(h.get).toHaveBeenCalledWith('/appointments/pending'); expect(h.get).toHaveBeenCalledWith('/appointments', { params: { date: B } })
  })
  it('booking stays available while the date-bound appointment list is pending', async () => {
    const h = setup(); await h.v.loadPending(); const item = h.v.pending.value[0]; const d = h.holdList(A); const loading = h.v.loadAppts()
    const booked = h.v.book(item); await turns(); const sent = copy(writes(h)); d.resolve(h.a); await Promise.all([loading, booked])
    expect(sent).toEqual([{ method: 'POST', url: '/appointments', body: { orderId: 301, slotDate: A, period: 'AM' } }])
  })
  it('a late global booking keeps the pending-list refresh but does not reload a newly selected B appointment date', async () => {
    const h = setup(); await h.load(A); await h.v.loadPending(); const item = h.v.pending.value[0]; const d = h.hold('POST', '/appointments'); const old = h.v.book(item)
    await h.load(B); h.v.selection.value = [row(h, 21)]; const before = state(h); h.clear(); d.resolve({ seqNo: 8 }); await old
    expect(state(h)).toEqual(before); expect(listReads(h)).toEqual([]); expect(h.get).toHaveBeenCalledExactlyOnceWith('/appointments/pending')
  })
  it('book4510 remains already-toasted with no successful reload and restores booking state', async () => {
    const h = setup(); await h.load(A); await h.v.loadPending(); const item = h.v.pending.value[0]
    h.route('POST', '/appointments', () => Promise.reject(Object.assign(new Error('4510'), { bizCode: 4510 }))); h.clear()
    await expect(h.v.book(item)).resolves.toBeUndefined(); expect(h.get).not.toHaveBeenCalled(); expect(h.messages.success).not.toHaveBeenCalled(); expect(h.v.bookBusy.value).toBeNull()
  })
  it('callNext still calls its global technical queue and loads waiting/called regardless of appointment date', async () => {
    const h = setup(); h.v.queryDate.value = null; h.clear(); await h.v.callNext('EXAM')
    expect(h.post).toHaveBeenCalledExactlyOnceWith('/queue-center/EXAM/call-next')
    expect(h.get).toHaveBeenCalledExactlyOnceWith('/queue-center/EXAM'); expect(h.v.queues[1].waiting).toEqual([{ id: 501 }])
    expect(h.v.queues[1].called).toEqual([{ id: 502 }]); expect(h.messages.success).toHaveBeenCalledExactlyOnceWith('请 模拟叫号患者 就位')
  })
  it('the actual tab watcher still loads its selected global queue', async () => {
    const h = setup(); h.v.tab.value = 'LAB'; await Vue.nextTick(); await turns()
    expect(h.get).toHaveBeenCalledExactlyOnceWith('/queue-center/LAB'); expect(h.v.queues[2].called).toEqual([{ id: 502 }])
  })
})
