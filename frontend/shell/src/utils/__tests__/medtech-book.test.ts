import { afterEach, it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as Vue from 'vue'

const fixtureScopes: ReturnType<typeof Vue.effectScope>[] = []
afterEach(() => { for (const scope of fixtureScopes.splice(0)) scope.stop() })

function deferred() {
  let resolve!: (value: unknown) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (data: unknown) => resolve({ data: { data } }), reject }
}

async function setup() {
  const scope = Vue.effectScope(); fixtureScopes.push(scope)
  const component = readFileSync(new URL('../../views/medtech/ApptQueueView.vue', import.meta.url), 'utf8')
  const source = component.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
  const write = deferred()
  const post = vi.fn(() => write.promise)
  const get = vi.fn((path: string) => Promise.resolve({ data: { data: path === '/appointments/pending'
    ? [] : [{ id: 31, status: 'BOOKED', period: 'AM', seq_no: 7 }] } }))
  const box: Record<string, any> = {
    ...Vue, onMounted: () => {}, onUnmounted: () => {}, todayLocal: () => '2026-10-26',
    client: { get, post },
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
    ElMessageBox: { prompt: vi.fn() },
  }
  // Exercise the real SFC book handler and its original finally/reload behavior.
  scope.run(() => runInNewContext(ts.transpileModule(source + '\nglobalThis.view = { book, bookBusy, pending, appts, loadAppts };', {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box))
  await box.view.loadAppts()
  get.mockClear()
  return { v: box.view, box, write, post, get }
}

const row = { order_id: 13, item_name: '模拟已收费申请', _date: '2026-10-27', _period: 'AM' }

it('handles an already-toasted 4510 without rejecting again, false success or reload, and releases booking state', async () => {
  const h = await setup()
  h.v.pending.value = [row]
  const result = h.v.book(row)
  expect(h.post).toHaveBeenCalledExactlyOnceWith('/appointments', { orderId: 13, slotDate: '2026-10-27', period: 'AM' })
  expect(h.v.bookBusy.value).toBe(row.order_id)
  const refusal = Object.assign(new Error('申请单不存在或未收费'), { bizCode: 4510 })
  h.write.reject(refusal)
  await expect(result).resolves.toBeUndefined()
  expect(h.v.bookBusy.value).toBeNull()
  expect(h.v.pending.value).toEqual([row])
  expect(h.box.ElMessage.success).not.toHaveBeenCalled()
  expect(h.box.ElMessage.error).not.toHaveBeenCalled() // Shared client already showed its business toast.
  expect(h.get).not.toHaveBeenCalled()
})

it('preserves unknown, network and 403 rejections unchanged while releasing booking state', async () => {
  const errors = [new Error('unexpected program failure'),
    Object.assign(new Error('network unavailable'), { code: 'ERR_NETWORK' }),
    Object.assign(new Error('forbidden'), { response: { status: 403 }, bizCode: 1005 })]
  for (const error of errors) {
    const h = await setup()
    const result = h.v.book(row)
    expect(h.v.bookBusy.value).toBe(row.order_id)
    h.write.reject(error)
    await expect(result).rejects.toBe(error)
    expect(h.v.bookBusy.value).toBeNull()
    expect(h.box.ElMessage.success).not.toHaveBeenCalled()
    expect(h.get).not.toHaveBeenCalled()
  }
})

it('retains the original seqNo success message and both pending/list reloads after a valid booking', async () => {
  const h = await setup()
  h.v.pending.value = [row]
  const result = h.v.book(row)
  expect(h.v.bookBusy.value).toBe(row.order_id)
  h.write.resolve({ seqNo: 7 })
  await expect(result).resolves.toBeUndefined()
  expect(h.box.ElMessage.success).toHaveBeenCalledExactlyOnceWith('预约成功，顺序号 7')
  expect(h.get).toHaveBeenCalledTimes(2)
  expect(h.get).toHaveBeenCalledWith('/appointments/pending')
  expect(h.get).toHaveBeenCalledWith('/appointments', { params: { date: '2026-10-26' } })
  expect(h.v.pending.value).toEqual([])
  expect(h.v.appts.value).toEqual([{ id: 31, status: 'BOOKED', period: 'AM', seq_no: 7 }])
  expect(h.v.bookBusy.value).toBeNull()
})
