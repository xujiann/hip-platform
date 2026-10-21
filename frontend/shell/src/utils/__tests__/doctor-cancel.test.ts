import { it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { ref } from 'vue'

// Run the view's actual cancellation function; HTTP timing is the only substituted behavior.
function setup(put = vi.fn(() => response(null)), get = vi.fn(() => response({ orders: [] }))) {
  const source = readFileSync(new URL('../../views/outpatient/DoctorStationView.vue', import.meta.url), 'utf8')
  const start = source.indexOf('async function cancelOrder(')
  const body = source.slice(start, source.indexOf('\nonMounted(', start))
  const a = { registrationId: 11 }, b = { registrationId: 22 }
  const row = { id: 111, registrationId: 11, status: 'CREATED' }
  const box: Record<string, any> = { ref, current: ref(a), orders: ref([row]),
    cancellingOrder: ref<number | null>(null), workspaceGeneration: 0, workspaceAlive: true,
    client: { put, get }, ElMessage: { success: vi.fn() }, openPatient: vi.fn() }
  runInNewContext(ts.transpileModule(body + '\nglobalThis.cancel = cancelOrder;', {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box)
  return { box, row: box.orders.value[0], a, b, put, get }
}
function deferred() {
  let resolve!: (v: any) => void
  const promise = new Promise<any>(r => { resolve = r })
  return { promise, resolve: (data: unknown) => resolve({ data: { data } }) }
}
const response = (data: unknown) => Promise.resolve({ data: { data } })

it('cancels the captured order and refreshes only orders, preserving draft edits', async () => {
  const { box, row, put, get } = setup()
  await box.cancel(row)
  expect(put).toHaveBeenCalledWith('/outpatient/doctor/orders/111/cancel')
  expect(get).toHaveBeenCalledWith('/outpatient/doctor/11/workspace')
  expect(box.orders.value).toEqual([])
  expect(box.openPatient).not.toHaveBeenCalled()
  expect(box.ElMessage.success).toHaveBeenCalledOnce()
})
it('ignores a duplicate click while the first cancellation is pending', async () => {
  const pending = deferred(); const { box, row, put } = setup(vi.fn(() => pending.promise))
  const first = box.cancel(row); await box.cancel(row); expect(put).toHaveBeenCalledTimes(1)
  pending.resolve(null); await first; expect(box.cancellingOrder.value).toBeNull()
})
it('refreshes charged state after 4007 without reporting cancellation success', async () => {
  const charged = { id: 111, registrationId: 11, status: 'CHARGED', chargeId: 99 }
  const { box, row } = setup(vi.fn(() => Promise.reject({ bizCode: 4007 })), vi.fn(() => response({ orders: [charged] })))
  await box.cancel(row)
  expect(box.orders.value).toEqual([charged]); expect(box.ElMessage.success).not.toHaveBeenCalled()
  expect(box.openPatient).not.toHaveBeenCalled()
})
it('an old write completion does not reload or announce success for the new patient', async () => {
  const pending = deferred(); const { box, row, b, get } = setup(vi.fn(() => pending.promise))
  const first = box.cancel(row); box.current.value = b; box.workspaceGeneration++; box.orders.value = [{ id: 222 }]
  pending.resolve(null); await first
  expect(get).not.toHaveBeenCalled(); expect(box.openPatient).not.toHaveBeenCalled()
  expect(box.orders.value).toEqual([{ id: 222 }]); expect(box.ElMessage.success).not.toHaveBeenCalled()
})
it('a slow order refresh never replaces another workspace', async () => {
  const pending = deferred(); const { box, row, b } = setup(undefined, vi.fn(() => pending.promise))
  const first = box.cancel(row); await vi.waitFor(() => expect(box.client.get).toHaveBeenCalled())
  box.current.value = b; box.workspaceGeneration++; box.orders.value = [{ id: 222 }]
  pending.resolve({ orders: [] }); await first; expect(box.orders.value).toEqual([{ id: 222 }])
})
it('does not submit an order left over from another registration', async () => {
  const { box, row, b, put } = setup(); box.current.value = b
  await box.cancel(row); expect(put).not.toHaveBeenCalled()
})
it('does not refill a workspace after leaving the page', async () => {
  const pending = deferred(); const { box, row, get } = setup(vi.fn(() => pending.promise))
  const first = box.cancel(row); box.workspaceAlive = false; pending.resolve(null); await first
  expect(get).not.toHaveBeenCalled(); expect(box.ElMessage.success).not.toHaveBeenCalled()
})
