import { it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { reactive, ref } from 'vue'

function deferred() {
  let resolve!: (value: unknown) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (data: unknown) => resolve({ data: { data } }), reject }
}

function setup() {
  const component = readFileSync(new URL('../../views/medtech/ApptQueueView.vue', import.meta.url), 'utf8')
  const source = component.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
  const write = deferred()
  const post = vi.fn(() => write.promise), put = vi.fn(() => write.promise)
  const get = vi.fn((path: string) => Promise.resolve({ data: { data: path === '/appointments/pending'
    ? [] : [{ id: 31, status: 'DONE', period: 'AM', seq_no: 7 }] } }))
  const box: Record<string, any> = {
    ref, reactive, onMounted: () => {}, watch: () => {}, todayLocal: () => '2026-10-26',
    client: { get, post, put },
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
    ElMessageBox: { prompt: vi.fn() },
  }
  // Exercise the actual SFC handlers and loading/selection/reload behavior.
  runInNewContext(ts.transpileModule(source + '\nglobalThis.view = { done, batchCancel, busyId, batchCancelLoading, selection, pending, appts };', {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box)
  return { v: box.view, box, write, post, put, get }
}

const row = { id: 31, status: 'BOOKED', period: 'AM', seq_no: 7 }
const selected = [row, { id: 32, status: 'BOOKED', period: 'PM', seq_no: 8 }]
const permissionError = () => Object.assign(new Error('无该功能权限'), { response: { status: 403, data: { code: 1005 } } })
const otherErrors = () => [new Error('unexpected program failure'),
  Object.assign(new Error('network unavailable'), { code: 'ERR_NETWORK' }),
  Object.assign(new Error('another forbidden response'), { response: { status: 403, data: { code: 9999 } } }),
  Object.assign(new Error('unauthenticated'), { response: { status: 401, data: { code: 1000 } } }),
  Object.assign(new Error('business denial on HTTP 200'), { response: { status: 200, data: { code: 1005 } } }),
  Object.assign(new Error('预约不存在或已完成'), { bizCode: 4512 }),
  Object.assign(new Error('missing forbidden envelope'), { response: { status: 403, data: null } })]

it('done consumes only the already-toasted HTTP403/code1005, keeps the row and releases loading without reload', async () => {
  const h = setup(); h.v.appts.value = [row]
  const result = h.v.done(row)
  expect(h.put).toHaveBeenCalledExactlyOnceWith('/appointments/31/done')
  expect(h.v.busyId.value).toBe(31)
  h.write.reject(permissionError())
  await expect(result).resolves.toBeUndefined()
  expect(h.v.busyId.value).toBeNull(); expect(h.v.appts.value).toEqual([row])
  expect(h.get).not.toHaveBeenCalled()
  expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.box.ElMessage.error).not.toHaveBeenCalled()
})

it('done propagates other forbidden, 401, network, business and unknown errors as the same object', async () => {
  for (const error of otherErrors()) {
    const h = setup(); const result = h.v.done(row)
    expect(h.v.busyId.value).toBe(31)
    h.write.reject(error)
    await expect(result).rejects.toBe(error)
    expect(h.v.busyId.value).toBeNull(); expect(h.get).not.toHaveBeenCalled()
    expect(h.box.ElMessage.success).not.toHaveBeenCalled()
  }
})

it('done retains the original successful completion and one appointment-list reload', async () => {
  const h = setup(); const result = h.v.done(row)
  expect(h.v.busyId.value).toBe(31)
  h.write.resolve(null)
  await expect(result).resolves.toBeUndefined()
  expect(h.put).toHaveBeenCalledExactlyOnceWith('/appointments/31/done')
  expect(h.get).toHaveBeenCalledExactlyOnceWith('/appointments', { params: { date: '2026-10-26' } })
  expect(h.v.appts.value).toEqual([{ id: 31, status: 'DONE', period: 'AM', seq_no: 7 }])
  expect(h.v.busyId.value).toBeNull(); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})

it('batch cancel consumes only HTTP403/code1005, retains selection and releases loading without success or reload', async () => {
  const h = setup(); h.v.selection.value = selected; h.v.appts.value = selected
  const result = h.v.batchCancel()
  expect(h.post).toHaveBeenCalledExactlyOnceWith('/appointments/batch-cancel', { ids: [31, 32] })
  expect(h.v.batchCancelLoading.value).toBe(true)
  h.write.reject(permissionError())
  await expect(result).resolves.toBeUndefined()
  expect(h.v.batchCancelLoading.value).toBe(false)
  expect(h.v.selection.value).toEqual(selected); expect(h.v.appts.value).toEqual(selected)
  expect(h.get).not.toHaveBeenCalled()
  expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.box.ElMessage.error).not.toHaveBeenCalled()
})

it('batch cancel propagates every other error unchanged while releasing loading and retaining selection', async () => {
  for (const error of otherErrors()) {
    const h = setup(); h.v.selection.value = selected; const result = h.v.batchCancel()
    expect(h.v.batchCancelLoading.value).toBe(true)
    h.write.reject(error)
    await expect(result).rejects.toBe(error)
    expect(h.v.batchCancelLoading.value).toBe(false); expect(h.v.selection.value).toEqual(selected)
    expect(h.get).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
  }
})

it('batch cancel keeps the exact cancelled count message, clears selection and reloads pending plus appointments', async () => {
  const h = setup(); h.v.selection.value = selected
  const result = h.v.batchCancel(); expect(h.v.batchCancelLoading.value).toBe(true)
  h.write.resolve({ cancelled: 2 })
  await expect(result).resolves.toBeUndefined()
  expect(h.post).toHaveBeenCalledExactlyOnceWith('/appointments/batch-cancel', { ids: [31, 32] })
  expect(h.box.ElMessage.success).toHaveBeenCalledExactlyOnceWith('已取消 2 条')
  expect(h.v.selection.value).toEqual([]); expect(h.v.batchCancelLoading.value).toBe(false)
  expect(h.get).toHaveBeenCalledTimes(2); expect(h.get).toHaveBeenCalledWith('/appointments/pending')
  expect(h.get).toHaveBeenCalledWith('/appointments', { params: { date: '2026-10-26' } })
})
