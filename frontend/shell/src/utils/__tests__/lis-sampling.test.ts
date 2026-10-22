import { it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { computed, reactive, ref } from 'vue'

const response = (data: unknown) => Promise.resolve({ data: { data: structuredClone(data) } })
function deferred() {
  let resolve!: (value: any) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<any>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (data: unknown) => resolve({ data: { data } }), raw: resolve, reject }
}
const awaiting = { order_id: 31, item_name: '模拟采样项目', exec_dept_id: 7, exec_dept_name: '模拟检验科', urgent: true, specimen_type: '模拟血样', sampling_site: '模拟部位', remark: '模拟注意事项' }
const collected = { ...awaiting, id: 1, order_id: 11, barcode: 'V115-A', status: 'COLLECTED', order_status: 'CHARGED', substitute: true, substitute_name: '模拟替检人' }
const resultSample = { ...collected, id: 2, order_id: 22, barcode: 'V115-B', status: 'RECEIVED', order_status: 'EXECUTED' }

function setup(get = vi.fn((path: string) => response(path === '/lis/samples' ? [collected, resultSample] : path === '/lis/pending' ? [awaiting] : [])),
  post = vi.fn(() => response({ barcode: 'V115-C' })), put = vi.fn(() => response(null)), roles = ['TECHNICIAN']) {
  const component = readFileSync(new URL('../../views/medtech/LisView.vue', import.meta.url), 'utf8')
  const source = component.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
  const dispose: Array<() => void> = []
  const user = reactive({ roles, deptId: null })
  const box: Record<string, any> = { ref, computed, onMounted: () => {}, onUnmounted: (f: () => void) => dispose.push(f),
    useAuthStore: () => ({ user }), client: { get, post, put }, window: { open: vi.fn() },
    ElMessage: { success: vi.fn(), warning: vi.fn() }, ElMessageBox: { prompt: vi.fn(() => Promise.resolve({ value: '' })) } }
  const buttons = Array.from(component.matchAll(/<el-button\b([^>]+)>/g))
  const binding = (action: string, attribute: string, fallback: string) => buttons.find(m => m[1].includes(`@click="${action}"`))?.[1].match(new RegExp(`${attribute}="([^"]+)"`))?.[1] ?? fallback
  const expression = (text: string) => text.replace(/\b(canWrite|writing|current)\b/g, '$1.value')
  const column = Array.from(component.matchAll(/<el-table-column\b([^>]+)>/g)).find(m => m[1].includes('label="源医嘱状态"'))
  const sourceColumn = column ? component.slice(column.index!, component.indexOf('</el-table-column>', column.index!) + 18) : ''
  const sourceText = sourceColumn.match(/\{\{([^}]+)\}\}/)?.[1] ?? "''"
  // Run the component's script and its template expressions, without a second policy implementation.
  runInNewContext(ts.transpileModule(source + `
    globalThis.view = { deptId, depts, pending, samples, dialogVisible, current, results, busyId, publishLoading, load, loadDepts, collect, receive, openResult, publish };
    globalThis.visible = { collect: row => ${expression(binding('collect(row)', 'v-if', 'true'))}, receive: row => ${expression(binding('receive(row)', 'v-if', 'true'))} };
    globalThis.disabled = { collect: () => ${expression(binding('collect(row)', ':disabled', 'false'))}, receive: () => ${expression(binding('receive(row)', ':disabled', 'false'))} };
    globalThis.sourceText = row => ${sourceText};
  `, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box)
  return { v: box.view, box, user, get, post, put, component, dispose: () => dispose.forEach(f => f()) }
}
const settle = (promise: Promise<unknown>) => promise.catch(() => {})
const queueReads = (h: ReturnType<typeof setup>) => h.get.mock.calls.filter(c => c[0] === '/lis/pending' || c[0] === '/lis/samples')

for (const role of ['ADMIN', 'TECHNICIAN', 'DOCTOR_OUTP', 'NURSE']) {
  it(`retains ${role} collection and receiving through their original endpoints`, async () => {
    const h = setup(undefined, undefined, undefined, [role]); await h.v.load()
    expect(h.box.visible.collect(h.v.pending.value[0])).toBe(true); expect(h.box.visible.receive(h.v.samples.value[0])).toBe(true)
    await h.v.collect(h.v.pending.value[0]); await h.v.receive(h.v.samples.value[0])
    expect(h.post).toHaveBeenCalledExactlyOnceWith('/lis/samples', null, { params: { orderId: 31, substituteName: undefined } })
    expect(h.put).toHaveBeenCalledExactlyOnceWith('/lis/samples/V115-A/receive')
    expect(h.box.ElMessage.success).toHaveBeenCalledExactlyOnceWith('条码 V115-C')
    expect(queueReads(h)).toHaveLength(6)
  })
}
it('retains receiving an EXECUTED source after machine results arrived before physical receipt', async () => {
  const row = { ...collected, order_status: 'EXECUTED' }
  const h = setup(vi.fn((path: string) => response(path === '/lis/samples' ? [row] : []))); await h.v.load()
  expect(h.box.visible.receive(h.v.samples.value[0])).toBe(true); await h.v.receive(h.v.samples.value[0])
  expect(h.put).toHaveBeenCalledExactlyOnceWith('/lis/samples/V115-A/receive')
})
for (const status of ['CREATED', 'CANCELLED', 'DISPENSED', undefined]) {
  it(`keeps collected history from ${status ?? 'missing'} source visible without a receive button or direct write`, async () => {
    const row = { ...collected, order_status: status }
    const h = setup(vi.fn((path: string) => response(path === '/lis/samples' ? [row] : []))); await h.v.load()
    expect(h.v.samples.value).toEqual([row]); await h.v.receive(h.v.samples.value[0]); expect(h.put).not.toHaveBeenCalled()
    expect(h.box.visible.receive(h.v.samples.value[0])).toBe(false)
    if (status === 'CREATED') expect(h.box.sourceText(h.v.samples.value[0])).toBe('未收费')
  })
}
for (const status of ['RECEIVED', 'PUBLISHED', undefined]) {
  it(`does not receive a sample in ${status ?? 'missing'} state`, async () => {
    const h = setup(vi.fn((path: string) => response(path === '/lis/samples' ? [{ ...collected, status }] : []))); await h.v.load()
    expect(h.box.visible.receive(h.v.samples.value[0])).toBe(false); await h.v.receive(h.v.samples.value[0]); expect(h.put).not.toHaveBeenCalled()
  })
}
for (const roles of [['CASHIER'], ['PHARMACIST'], ['INTERFACE'], []]) {
  it(`denies both visible and direct sampling writes to ${roles.join('/') || 'no role'}`, async () => {
    const h = setup(undefined, undefined, undefined, roles); await h.v.load()
    expect(h.box.visible.collect(h.v.pending.value[0])).toBe(false); expect(h.box.visible.receive(h.v.samples.value[0])).toBe(false)
    await h.v.collect(h.v.pending.value[0]); await h.v.receive(h.v.samples.value[0])
    expect(h.box.ElMessageBox.prompt).not.toHaveBeenCalled(); expect(h.post).not.toHaveBeenCalled(); expect(h.put).not.toHaveBeenCalled()
  })
}
it('preserves substitute input verbatim and source specimen, department and request metadata', async () => {
  const h = setup(); await h.v.load(); h.box.ElMessageBox.prompt = vi.fn(() => Promise.resolve({ value: ' 模拟家属 张某 ' }))
  await h.v.collect(h.v.pending.value[0])
  expect(h.post).toHaveBeenCalledExactlyOnceWith('/lis/samples', null, { params: { orderId: 31, substituteName: ' 模拟家属 张某 ' } })
  expect(h.box.ElMessage.success).toHaveBeenCalledExactlyOnceWith('条码 V115-C（替检已标识）')
  expect(h.v.samples.value[0]).toEqual(collected)
  for (const key of ['exec_dept_name', 'urgent', 'specimen_type', 'sampling_site', 'remark', 'substitute_name']) expect(h.component).toContain(`row.${key}`)
})
it('cancelled collection prompts make no request and release the page write guard', async () => {
  const h = setup(); await h.v.load(); h.box.ElMessageBox.prompt = vi.fn(() => Promise.reject('cancel'))
  await h.v.collect(h.v.pending.value[0]); expect(h.post).not.toHaveBeenCalled(); expect(h.v.busyId.value).toBe(null)
  await h.v.receive(h.v.samples.value[0]); expect(h.put).toHaveBeenCalledOnce()
})
it('does not submit detached rows that were replaced by a fresh queue read', async () => {
  const h = setup(); await h.v.load(); const oldPending = h.v.pending.value[0], oldSample = h.v.samples.value[0]
  await h.v.load(); await h.v.collect(oldPending); await h.v.receive(oldSample)
  expect(h.box.ElMessageBox.prompt).not.toHaveBeenCalled(); expect(h.post).not.toHaveBeenCalled(); expect(h.put).not.toHaveBeenCalled()
})
it('rechecks current source state before a direct receive call from a stale button', async () => {
  const h = setup(); await h.v.load(); const row = h.v.samples.value[0]; row.order_status = 'CREATED'
  expect(h.box.visible.receive(row)).toBe(false); await h.v.receive(row); expect(h.put).not.toHaveBeenCalled()
})
it('a collection prompt invalidated by a full refresh cannot submit its former order', async () => {
  const prompt = deferred(); const h = setup(); await h.v.load(); h.box.ElMessageBox.prompt = vi.fn(() => prompt.promise)
  const first = h.v.collect(h.v.pending.value[0]); await h.v.load(); prompt.raw({ value: '' }); await first
  expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('a collection prompt from the previous department cannot write', async () => {
  const prompt = deferred(); const h = setup(); await h.v.load(); h.box.ElMessageBox.prompt = vi.fn(() => prompt.promise)
  const first = h.v.collect(h.v.pending.value[0]); h.v.deptId.value = 7; await h.v.load(); prompt.raw({ value: '模拟替检' }); await first
  expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('role loss while the collection prompt is open prevents submission', async () => {
  const prompt = deferred(); const h = setup(); await h.v.load(); h.box.ElMessageBox.prompt = vi.fn(() => prompt.promise)
  const first = h.v.collect(h.v.pending.value[0]); h.user.roles = ['CASHIER']; prompt.raw({ value: '' }); await first
  expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('a pending collection prompt blocks duplicate collection, receipt and result publication', async () => {
  const prompt = deferred(); const h = setup(); await h.v.load(); h.v.openResult(h.v.samples.value[1]); h.box.ElMessageBox.prompt = vi.fn(() => prompt.promise)
  const first = h.v.collect(h.v.pending.value[0]); const other = [h.v.collect(h.v.pending.value[0]), h.v.receive(h.v.samples.value[0]), h.v.publish()]
  expect(h.box.disabled.collect()).toBe(true); expect(h.box.disabled.receive()).toBe(true)
  expect(h.box.ElMessageBox.prompt).toHaveBeenCalledOnce(); expect(h.put).not.toHaveBeenCalled(); expect(h.post).not.toHaveBeenCalled()
  prompt.raw({ value: '' }); await Promise.all([first, ...other]); expect(h.post).toHaveBeenCalledOnce(); expect(h.put).not.toHaveBeenCalled()
})
it('an in-flight receipt blocks a duplicate, collection prompt and publication until queue refresh completes', async () => {
  const write = deferred(), refresh = deferred(); let slow = false
  const h = setup(vi.fn((path: string) => slow ? refresh.promise : response(path === '/lis/samples' ? [collected, resultSample] : [awaiting])), undefined, vi.fn(() => write.promise))
  await h.v.load(); h.v.openResult(h.v.samples.value[1]); const first = h.v.receive(h.v.samples.value[0]); slow = true; write.resolve(null)
  await vi.waitFor(() => expect(queueReads(h)).toHaveLength(4))
  await Promise.all([h.v.receive(h.v.samples.value[0]), h.v.collect(h.v.pending.value[0]), h.v.publish()])
  expect(h.put).toHaveBeenCalledOnce(); expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessageBox.prompt).not.toHaveBeenCalled()
  refresh.resolve([]); await first; expect(h.v.busyId.value).toBe(null)
})
for (const [action, code] of [['collect', 9940], ['receive', 9941]] as const) {
  it(`${action} ${code} rejection rereads both queues and displays no success or barcode`, async () => {
    let refunded = false
    const get = vi.fn((path: string) => response(path === '/lis/pending' ? (refunded ? [] : [awaiting]) : [{ ...collected, order_status: refunded ? 'CREATED' : 'CHARGED' }, resultSample]))
    const failure = vi.fn(() => Promise.reject({ bizCode: code }))
    const h = setup(get, action === 'collect' ? failure : undefined, action === 'receive' ? failure : undefined); await h.v.load(); get.mockClear(); refunded = true
    await settle(h.v[action](action === 'collect' ? h.v.pending.value[0] : h.v.samples.value[0]))
    expect(get).toHaveBeenCalledWith('/lis/pending', { params: { deptId: undefined } }); expect(get).toHaveBeenCalledWith('/lis/samples', { params: { deptId: undefined } })
    expect(h.v.pending.value).toEqual([]); expect(h.v.samples.value[0].order_status).toBe('CREATED')
    expect(h.box.visible.receive(h.v.samples.value[0])).toBe(false); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.box.window.open).not.toHaveBeenCalled()
    expect(h.v.busyId.value).toBe(null)
  })
  it(`late ${action} ${code} rejection preserves a newer result dialog and unsent draft while refreshing queues`, async () => {
    const write = deferred(); const h = setup(undefined, action === 'collect' ? vi.fn(() => write.promise) : undefined, action === 'receive' ? vi.fn(() => write.promise) : undefined)
    await h.v.load(); const first = settle(h.v[action](action === 'collect' ? h.v.pending.value[0] : h.v.samples.value[0]))
    await vi.waitFor(() => expect(action === 'collect' ? h.post : h.put).toHaveBeenCalledOnce())
    h.v.openResult(h.v.samples.value[1]); h.v.results.value[0].value = '后开的模拟草稿'
    write.reject({ bizCode: code }); await first
    expect(h.v.dialogVisible.value).toBe(true); expect(h.v.current.value.barcode).toBe('V115-B'); expect(h.v.results.value[0].value).toBe('后开的模拟草稿')
    expect(queueReads(h)).toHaveLength(4); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.box.window.open).not.toHaveBeenCalled()
  })
  it(`a late ${action} ${code} rejection after department change cannot refresh or replace its new queues`, async () => {
    const write = deferred(); const h = setup(undefined, action === 'collect' ? vi.fn(() => write.promise) : undefined, action === 'receive' ? vi.fn(() => write.promise) : undefined)
    await h.v.load(); const first = settle(h.v[action](action === 'collect' ? h.v.pending.value[0] : h.v.samples.value[0]))
    await vi.waitFor(() => expect(action === 'collect' ? h.post : h.put).toHaveBeenCalledOnce())
    h.v.deptId.value = 7; await h.v.load(); h.v.openResult(h.v.samples.value[1]); h.v.results.value[0].value = '新科室草稿'; h.get.mockClear()
    write.reject({ bizCode: code }); await first
    expect(h.get).not.toHaveBeenCalled(); expect(h.v.dialogVisible.value).toBe(true); expect(h.v.results.value[0].value).toBe('新科室草稿'); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
  })
  it(`ordinary ${action} failure stays rejected without false success or unwanted refresh`, async () => {
    const error = { bizCode: action === 'collect' ? 4770 : 1005 }
    const failure = vi.fn(() => Promise.reject(error)); const h = setup(undefined, action === 'collect' ? failure : undefined, action === 'receive' ? failure : undefined)
    await h.v.load(); h.v.openResult(h.v.samples.value[1]); h.v.results.value[0].value = '保留的结果草稿'; h.get.mockClear()
    await expect(h.v[action](action === 'collect' ? h.v.pending.value[0] : h.v.samples.value[0])).rejects.toBe(error)
    expect(h.get).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.v.dialogVisible.value).toBe(true); expect(h.v.results.value[0].value).toBe('保留的结果草稿')
  })
  it(`leaving during ${action} suppresses late completion reads, messages and dialogs`, async () => {
    const write = deferred(); const h = setup(undefined, action === 'collect' ? vi.fn(() => write.promise) : undefined, action === 'receive' ? vi.fn(() => write.promise) : undefined)
    await h.v.load(); const first = h.v[action](action === 'collect' ? h.v.pending.value[0] : h.v.samples.value[0])
    await vi.waitFor(() => expect(action === 'collect' ? h.post : h.put).toHaveBeenCalledOnce()); h.get.mockClear(); h.dispose(); write.resolve({ barcode: 'V115-C' }); await first
    expect(h.get).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.box.window.open).not.toHaveBeenCalled()
  })
}
it('leaving during a collection prompt prevents submitting the target', async () => {
  const prompt = deferred(); const h = setup(); await h.v.load(); h.box.ElMessageBox.prompt = vi.fn(() => prompt.promise)
  const first = h.v.collect(h.v.pending.value[0]); h.dispose(); prompt.raw({ value: '' }); await first
  expect(h.post).not.toHaveBeenCalled()
})
it('the latest department request owns both queue results when prior reads return last', async () => {
  const old = deferred(), latest = deferred()
  const h = setup(vi.fn((_path: string, options: any) => options.params.deptId === 1 ? old.promise : latest.promise))
  h.v.deptId.value = 1; const first = h.v.load(); h.v.deptId.value = 2; const second = h.v.load()
  latest.resolve([resultSample]); await second; old.resolve([collected]); await first
  expect(h.v.pending.value).toEqual([resultSample]); expect(h.v.samples.value).toEqual([resultSample])
})
it('department filtering and department directory enabled flags keep the original HTTP contracts', async () => {
  const h = setup(vi.fn((path: string) => response(path === '/system/depts' ? [{ id: 7, name: '模拟检验科' }, { id: 8, name: '停用科室', enabled: false }] : [])))
  h.v.deptId.value = 7; await h.v.load(); await h.v.loadDepts()
  expect(h.get).toHaveBeenCalledWith('/lis/pending', { params: { deptId: 7 } }); expect(h.get).toHaveBeenCalledWith('/lis/samples', { params: { deptId: 7 } })
  expect(h.v.depts.value).toEqual([{ id: 7, name: '模拟检验科' }])
})
