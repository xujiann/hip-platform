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
  const put = vi.fn(() => write.promise)
  const get = vi.fn(() => Promise.resolve({ data: { data: [{ id: 13, status: 'BOOKED', period: 'PM', seq_no: 7 }] } }))
  const box: Record<string, any> = {
    ref, reactive, onMounted: () => {}, watch: () => {}, todayLocal: () => '2026-10-26',
    client: { get, put },
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
    ElMessageBox: { prompt: vi.fn(() => Promise.resolve({ value: '2026-10-27， PM' })) },
  }
  // Exercise the real SFC handler, including its original parsing, finally and reload.
  runInNewContext(ts.transpileModule(source + '\nglobalThis.view = { reschedule, busyId, queryDate, appts };', {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box)
  return { v: box.view, box, write, put, get }
}

const row = { id: 13, status: 'BOOKED', period: 'AM', seq_no: 2 }

it('handles an already-toasted 4513 without rejecting again, false success or reload, and releases busy state', async () => {
  const h = setup()
  h.v.appts.value = [row]
  const result = h.v.reschedule(row)
  await vi.waitFor(() => expect(h.put).toHaveBeenCalledOnce())
  expect(h.v.busyId.value).toBe(row.id)
  expect(h.put).toHaveBeenCalledExactlyOnceWith('/appointments/13/reschedule', { slotDate: '2026-10-27', period: 'PM' })
  const refusal = Object.assign(new Error('仅未叫号的预约可改约'), { bizCode: 4513 })
  h.write.reject(refusal)
  await expect(result).resolves.toBeUndefined()
  expect(h.v.busyId.value).toBeNull()
  expect(h.v.appts.value).toEqual([row])
  expect(h.box.ElMessage.success).not.toHaveBeenCalled()
  expect(h.box.ElMessage.error).not.toHaveBeenCalled() // The shared client already showed its toast.
  expect(h.get).not.toHaveBeenCalled()
})

it('preserves unknown, network and 403 rejections unchanged while releasing busy state', async () => {
  const errors = [new Error('unexpected program failure'),
    Object.assign(new Error('network unavailable'), { code: 'ERR_NETWORK' }),
    Object.assign(new Error('forbidden'), { response: { status: 403 }, bizCode: 1005 })]
  for (const error of errors) {
    const h = setup()
    const result = h.v.reschedule(row)
    await vi.waitFor(() => expect(h.put).toHaveBeenCalledOnce())
    expect(h.v.busyId.value).toBe(row.id)
    h.write.reject(error)
    await expect(result).rejects.toBe(error)
    expect(h.v.busyId.value).toBeNull()
    expect(h.box.ElMessage.success).not.toHaveBeenCalled()
    expect(h.get).not.toHaveBeenCalled()
  }
})

it('retains the original seqNo success message and queue reload after a valid move', async () => {
  const h = setup()
  const result = h.v.reschedule(row)
  await vi.waitFor(() => expect(h.put).toHaveBeenCalledOnce())
  expect(h.v.busyId.value).toBe(row.id)
  h.write.resolve({ seqNo: 7 })
  await expect(result).resolves.toBeUndefined()
  expect(h.box.ElMessage.success).toHaveBeenCalledExactlyOnceWith('已改约，新顺序号 7')
  expect(h.get).toHaveBeenCalledExactlyOnceWith('/appointments', { params: { date: '2026-10-26' } })
  expect(h.v.appts.value).toEqual([{ id: 13, status: 'BOOKED', period: 'PM', seq_no: 7 }])
  expect(h.v.busyId.value).toBeNull()
})
