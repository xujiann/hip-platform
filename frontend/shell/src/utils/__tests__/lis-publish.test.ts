import { it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { computed, ref } from 'vue'

const response = (data: unknown) => Promise.resolve({ data: { data } })
function deferred() {
  let resolve!: (value: any) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<any>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (data: unknown) => resolve({ data: { data } }), raw: resolve, reject }
}
const a = { id: 1, barcode: 'V113-A', order_id: 11, item_name: '模拟甲检验', status: 'RECEIVED', order_status: 'CHARGED' }
const b = { id: 2, barcode: 'V113-B', order_id: 22, item_name: '模拟乙检验', status: 'RECEIVED', order_status: 'EXECUTED' }
const pending = { order_id: 33, item_name: '模拟采样', exec_dept_id: 7 }
function setup(get = vi.fn((path: string) => response(path === '/lis/samples' ? [a, b] : path === '/lis/pending' ? [pending] : [])),
  post = vi.fn(() => response(null)), roles = ['TECHNICIAN']) {
  const component = readFileSync(new URL('../../views/medtech/LisView.vue', import.meta.url), 'utf8')
  const source = component.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
  const dispose: Array<() => void> = []
  const box: Record<string, any> = { ref, computed, onMounted: () => {}, onUnmounted: (f: () => void) => dispose.push(f),
    useAuthStore: () => ({ user: { roles, deptId: null } }), client: { get, post, put: post }, window: { open: vi.fn() },
    ElMessage: { success: vi.fn(), warning: vi.fn() }, ElMessageBox: { prompt: vi.fn(() => Promise.resolve({ value: '' })) } }
  const button = Array.from(component.matchAll(/<el-button\b([^>]+)>/g)).find(m => m[1].includes('@click="openResult(row)"'))?.[1] ?? ''
  const condition = button.match(/v-if="([^"]+)"/)?.[1] ?? 'true'
  // Evaluate the actual template condition and source-state column, not a copied predicate.
  const column = Array.from(component.matchAll(/<el-table-column\b([^>]+)>/g)).find(m => m[1].includes('label="源医嘱状态"'))
  const sourceColumn = column ? component.slice(column.index!, component.indexOf('</el-table-column>', column.index!) + 18) : ''
  runInNewContext(ts.transpileModule(source + `\nglobalThis.view = { deptId, pending, samples, dialogVisible, current, results, busyId, publishLoading, load, loadDepts, collect, receive, openResult, publish };
    globalThis.publishVisible = (row) => ${condition.replace(/\b(canWrite|writing)\b/g, '$1.value')};
  `, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box)
  return { v: box.view, box, get, post, sourceColumn, dispose: () => dispose.forEach(f => f()) }
}
async function opened(h: ReturnType<typeof setup>, index = 0) {
  await h.v.load(); h.v.openResult(h.v.samples.value[index]); return h.v.results.value[0]
}
const settle = (promise: Promise<unknown>) => promise.catch(() => {})

it('publishes a real received sample with blank draft default and prints its source order', async () => {
  const h = setup(); const draft = await opened(h)
  expect(draft.value).toBe(''); draft.value = '实测 8.2'
  await h.v.publish()
  expect(h.post).toHaveBeenCalledExactlyOnceWith('/lis/samples/V113-A/publish', { results: [{ code: '', name: a.item_name, value: '实测 8.2', unit: '', refRange: '', flag: 'N' }] })
  expect(h.box.window.open).toHaveBeenCalledExactlyOnceWith('/print?type=lab-report&id=11', '_blank')
  expect(h.box.ElMessage.success).toHaveBeenCalledOnce(); expect(h.v.dialogVisible.value).toBe(false)
})
for (const role of ['ADMIN', 'TECHNICIAN', 'DOCTOR_OUTP', 'NURSE']) {
  it(`retains ${role} manual publishing on an EXECUTED source`, async () => {
    const h = setup(undefined, undefined, [role]); await opened(h, 1)
    expect(h.box.publishVisible(h.v.samples.value[1])).toBe(true)
    await h.v.publish(); expect(h.post).toHaveBeenCalledWith('/lis/samples/V113-B/publish', expect.anything())
  })
}
it('binds the new source status column to the real order_status response field', () => {
  const h = setup(); expect(h.sourceColumn).toContain('order_status')
})
it('retains refunded received history visibly but offers no publishing or direct stale-page write', async () => {
  const history = { ...a, order_status: 'CREATED' }
  const h = setup(vi.fn((path: string) => response(path === '/lis/samples' ? [history] : [])))
  await h.v.load(); expect(h.v.samples.value).toEqual([history]); expect(h.box.publishVisible(h.v.samples.value[0])).toBe(false)
  h.v.openResult(h.v.samples.value[0]); await h.v.publish()
  expect(h.post).not.toHaveBeenCalled(); expect(h.v.dialogVisible.value).toBe(false)
})
it('missing source status and unreceived samples cannot open a publishing dialog', async () => {
  const rows = [{ ...a, order_status: undefined }, { ...b, status: 'COLLECTED' }]
  const h = setup(vi.fn((path: string) => response(path === '/lis/samples' ? rows : []))); await h.v.load()
  for (const row of h.v.samples.value) {
    expect(h.box.publishVisible(row)).toBe(false); h.v.openResult(row); await h.v.publish()
  }
  expect(h.v.dialogVisible.value).toBe(false); expect(h.post).not.toHaveBeenCalled()
})
it('wrong roles have neither visible publishing nor callable writes from old pages', async () => {
  const h = setup(undefined, undefined, ['CASHIER']); await h.v.load()
  expect(h.box.publishVisible(h.v.samples.value[0])).toBe(false)
  h.v.openResult(h.v.samples.value[0]); await h.v.publish(); await h.v.collect(h.v.pending.value[0]); await h.v.receive({ ...a, status: 'COLLECTED' })
  expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessageBox.prompt).not.toHaveBeenCalled()
})
it('captures an independent request body before HTTP, including every result row', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); const draft = await opened(h)
  draft.value = '甲原稿'; const first = h.v.publish(); draft.value = '后来的编辑'; h.v.results.value.push({ name: '后来加行', value: '2' })
  expect(h.post.mock.calls[0][1].results).toEqual([{ code: '', name: a.item_name, value: '甲原稿', unit: '', refRange: '', flag: 'N' }])
  wait.resolve(null); await first
})
it('blocks duplicate publish and receive before HTTP while a write is pending', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await opened(h)
  const first = h.v.publish(), duplicate = h.v.publish(), other = h.v.receive({ ...b, status: 'COLLECTED' })
  wait.resolve(null); await Promise.all([first, duplicate, other]); expect(h.post).toHaveBeenCalledTimes(1)
})
it('a late A success cannot close, clear or print a newly opened B dialog', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await opened(h)
  const first = h.v.publish(); h.v.dialogVisible.value = false; h.v.openResult(h.v.samples.value[1]); h.v.results.value[0].value = '乙未提交草稿'
  wait.resolve(null); await first
  expect(h.v.dialogVisible.value).toBe(true); expect(h.v.current.value.barcode).toBe('V113-B')
  expect(h.v.results.value[0].value).toBe('乙未提交草稿'); expect(h.box.window.open).not.toHaveBeenCalled()
  expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('closing and reopening the same sample invalidates the old completion', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await opened(h)
  const first = h.v.publish(); h.v.dialogVisible.value = false; h.v.openResult(h.v.samples.value[0]); h.v.results.value[0].value = '重新打开的草稿'
  wait.resolve(null); await first
  expect(h.v.dialogVisible.value).toBe(true); expect(h.v.results.value[0].value).toBe('重新打开的草稿'); expect(h.box.window.open).not.toHaveBeenCalled()
})
it('9942 closes only the original invalid dialog and refreshes true queues without success or print', async () => {
  let state = [a, b]
  const get = vi.fn((path: string) => response(path === '/lis/samples' ? state : []))
  const h = setup(get, vi.fn(() => Promise.reject({ bizCode: 9942 }))); await opened(h); get.mockClear(); state = [{ ...a, order_status: 'CREATED' }, b]
  await settle(h.v.publish())
  expect(h.v.dialogVisible.value).toBe(false); expect(h.v.samples.value[0].order_status).toBe('CREATED')
  expect(get).toHaveBeenCalledWith('/lis/pending', { params: { deptId: undefined } })
  expect(get).toHaveBeenCalledWith('/lis/samples', { params: { deptId: undefined } })
  expect(h.box.window.open).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('a late A rejection refreshes queues while preserving a new B draft and dialog', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await opened(h)
  const first = settle(h.v.publish()); h.v.dialogVisible.value = false; h.v.openResult(h.v.samples.value[1]); h.v.results.value[0].value = '乙保留'
  wait.reject({ bizCode: 9942 }); await first
  expect(h.v.dialogVisible.value).toBe(true); expect(h.v.current.value.barcode).toBe('V113-B'); expect(h.v.results.value[0].value).toBe('乙保留')
  expect(h.get.mock.calls.filter(c => c[0] === '/lis/samples')).toHaveLength(2)
})
it('ordinary publish failure keeps the original draft available to correct and retry', async () => {
  const h = setup(undefined, vi.fn(() => Promise.reject({ bizCode: 9943 }))); const draft = await opened(h); draft.value = '待修正结果'
  await settle(h.v.publish())
  expect(h.v.dialogVisible.value).toBe(true); expect(h.v.results.value[0].value).toBe('待修正结果'); expect(h.box.window.open).not.toHaveBeenCalled()
})
it('a full refresh invalidates an open stale dialog before network completion', async () => {
  const wait = deferred(); let slow = false
  const h = setup(vi.fn((path: string) => slow ? wait.promise : response(path === '/lis/samples' ? [a, b] : [])))
  await opened(h); slow = true; const refresh = h.v.load()
  expect(h.v.dialogVisible.value).toBe(false); expect(h.v.samples.value).toEqual([])
  wait.resolve([]); await refresh
})
it('only the latest department refresh may replace either queue', async () => {
  const old = deferred(), latest = deferred()
  const get = vi.fn((_path: string, options: any) => options.params.deptId === 1 ? old.promise : latest.promise)
  const h = setup(get); h.v.deptId.value = 1; const first = h.v.load(); h.v.deptId.value = 2; const second = h.v.load()
  latest.resolve([b]); await second; old.resolve([a]); await first
  expect(h.v.pending.value).toEqual([b]); expect(h.v.samples.value).toEqual([b])
})
it('a removed sample cannot be opened or submitted from a detached row reference', async () => {
  const h = setup(); await h.v.load(); const removed = h.v.samples.value[0]; h.v.samples.value = [h.v.samples.value[1]]
  h.v.openResult(removed); await h.v.publish(); expect(h.post).not.toHaveBeenCalled()
})
it('a source status changed on the current list prevents a previously opened publish', async () => {
  const h = setup(); await opened(h); h.v.samples.value[0].order_status = 'CREATED'; await h.v.publish()
  expect(h.post).not.toHaveBeenCalled()
})
it('leaving the page prevents late reads and publication side effects', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await opened(h)
  const first = h.v.publish(); h.dispose(); wait.resolve(null); await first
  expect(h.get).toHaveBeenCalledTimes(2); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.box.window.open).not.toHaveBeenCalled()
  const late = deferred(); const reads = setup(vi.fn(() => late.promise)); const loading = reads.v.load(); reads.dispose(); late.resolve([a]); await loading
  expect(reads.v.pending.value).toEqual([]); expect(reads.v.samples.value).toEqual([])
})
it('a collect prompt from the previous department cannot submit its stale order', async () => {
  const prompt = deferred(); const h = setup(undefined, vi.fn(() => response({ barcode: 'V113-C' }))); await h.v.load(); h.box.ElMessageBox.prompt = vi.fn(() => prompt.promise)
  const first = h.v.collect(h.v.pending.value[0]); h.v.deptId.value = 7; await h.v.load(); prompt.raw({ value: '模拟替检人' }); await first
  expect(h.post).not.toHaveBeenCalled()
})
it('a pending collect prompt blocks a second prompt and all other writes', async () => {
  const prompt = deferred(); const h = setup(undefined, vi.fn(() => response({ barcode: 'V113-C' }))); await opened(h)
  h.box.ElMessageBox.prompt = vi.fn(() => prompt.promise)
  const first = h.v.collect(h.v.pending.value[0]); const duplicate = h.v.collect(h.v.pending.value[0]), publish = h.v.publish()
  expect(h.box.ElMessageBox.prompt).toHaveBeenCalledTimes(1); expect(h.post).not.toHaveBeenCalled()
  prompt.raw({ value: '' }); await Promise.all([first, duplicate, publish]); expect(h.post).toHaveBeenCalledTimes(1)
})
it('retains actual collection and substitute-name parameters without inventing a result', async () => {
  const h = setup(undefined, vi.fn(() => response({ barcode: 'V113-C' }))); await h.v.load()
  h.box.ElMessageBox.prompt = vi.fn(() => Promise.resolve({ value: '模拟替检人' })); await h.v.collect(h.v.pending.value[0])
  expect(h.post).toHaveBeenCalledExactlyOnceWith('/lis/samples', null, { params: { orderId: 33, substituteName: '模拟替检人' } })
  expect(h.box.ElMessage.success).toHaveBeenCalledExactlyOnceWith('条码 V113-C（替检已标识）')
})
it('retains receiving a collected sample through its original barcode endpoint', async () => {
  const collected = { ...a, status: 'COLLECTED' }; const h = setup(vi.fn((path: string) => response(path === '/lis/samples' ? [collected] : [])))
  await h.v.load(); await h.v.receive(h.v.samples.value[0]); expect(h.post).toHaveBeenCalledExactlyOnceWith('/lis/samples/V113-A/receive')
})
