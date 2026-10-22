import { it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { computed, reactive, ref } from 'vue'

const response = (data: unknown) => Promise.resolve({ data: { data } })
function deferred() {
  let resolve!: (value: any) => void
  const promise = new Promise<any>(r => { resolve = r })
  return { promise, resolve: (data: unknown) => resolve({ data: { data } }), raw: (data: unknown) => resolve(data) }
}
function setup(kind: 'exec' | 'nurse', get = vi.fn(() => response([])), write = vi.fn(() => response(null)), roles = kind === 'exec' ? ['TECHNICIAN'] : ['NURSE']) {
  const file = kind === 'exec' ? 'ExecStationView' : 'OutpNurseView'
  const source = readFileSync(new URL(`../../views/outpatient/${file}.vue`, import.meta.url), 'utf8')
    .split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
  const dispose: Array<() => void> = []
  const box: Record<string, any> = { ref, reactive, computed, onMounted: () => {}, onUnmounted: (f: () => void) => dispose.push(f),
    useAuthStore: () => ({ user: { roles } }), client: { get, post: write, put: write },
    ElMessage: { success: vi.fn(), warning: vi.fn(), info: vi.fn() }, ElMessageBox: { prompt: vi.fn(() => Promise.resolve({ value: '实际巡视记录' })) } }
  const exposed = kind === 'exec' ? 'worklist, dialogVisible, current, resultText, executing, load, openExecute, execute'
    : 'candidates, infusions, loadCandidates, loadInfusions, createInfusion, startInfusion, addCheck, finishInfusion'
  runInNewContext(ts.transpileModule(source + `\nglobalThis.view = { ${exposed} };`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box)
  return { v: box.view, box, get, write, dispose: () => dispose.forEach(f => f()) }
}
const a = { orderId: 11, patientName: '模拟甲', itemName: '检验' }, b = { orderId: 22, patientName: '模拟乙', itemName: '治疗' }
const pending = { id: 1, order_id: 11, status: 'PENDING', order_status: 'CHARGED' }
const running = { ...pending, status: 'RUNNING' }

it('executes an existing queue item with the captured result and refreshes the queue', async () => {
  const h = setup('exec', vi.fn().mockImplementationOnce(() => response([a])).mockImplementation(() => response([])))
  await h.v.load(); h.v.openExecute(h.v.worklist.value[0]); h.v.resultText.value = '实际报告'; await h.v.execute()
  expect(h.write).toHaveBeenCalledWith('/outpatient/exec/11', { resultText: '实际报告' }); expect(h.v.worklist.value).toEqual([])
})
it('does not close or clear a new execution dialog when an earlier write finishes', async () => {
  const wait = deferred(); const h = setup('exec', vi.fn(() => response([a, b])), vi.fn(() => wait.promise))
  await h.v.load(); h.v.openExecute(h.v.worklist.value[0]); const first = h.v.execute()
  h.v.dialogVisible.value = false; h.v.openExecute(h.v.worklist.value[1]); h.v.resultText.value = '乙的未提交结果'
  wait.resolve(null); await first
  expect(h.v.dialogVisible.value).toBe(true); expect(h.v.current.value.orderId).toBe(22); expect(h.v.resultText.value).toBe('乙的未提交结果')
})
it('ignores execution queue responses older than the latest refresh', async () => {
  const one = deferred(), two = deferred(); const h = setup('exec', vi.fn().mockReturnValueOnce(one.promise).mockReturnValueOnce(two.promise))
  const first = h.v.load(), last = h.v.load(); two.resolve([b]); await last; one.resolve([a]); await first
  expect(h.v.worklist.value).toEqual([b])
})
it('execution rejects duplicate clicks before calling the HTTP client', async () => {
  const wait = deferred(); const h = setup('exec', vi.fn(() => response([a])), vi.fn(() => wait.promise))
  await h.v.load(); h.v.openExecute(h.v.worklist.value[0]); const first = h.v.execute(), second = h.v.execute()
  wait.resolve(null); await Promise.all([first, second]); expect(h.write).toHaveBeenCalledTimes(1)
})
it('refreshes the execution queue after a refunded or already executed order is refused', async () => {
  const get = vi.fn().mockImplementationOnce(() => response([a])).mockImplementation(() => response([]))
  const h = setup('exec', get, vi.fn(() => Promise.reject({ bizCode: 7002 })))
  await h.v.load(); h.v.openExecute(h.v.worklist.value[0]); await h.v.execute()
  expect(h.v.worklist.value).toEqual([]); expect(h.v.dialogVisible.value).toBe(false); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('does not announce or reload a completed execution after leaving the page', async () => {
  const wait = deferred(); const h = setup('exec', vi.fn(() => response([a])), vi.fn(() => wait.promise))
  await h.v.load(); h.v.openExecute(h.v.worklist.value[0]); const first = h.v.execute(); h.dispose(); wait.resolve(null); await first
  expect(h.get).toHaveBeenCalledTimes(1); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('wrong execution roles cannot submit from a stale page', async () => {
  const h = setup('exec', vi.fn(() => response([a])), undefined, ['CASHIER'])
  await h.v.load(); h.v.worklist.value = [a]; h.v.openExecute(h.v.worklist.value[0]); await h.v.execute(); expect(h.write).not.toHaveBeenCalled()
})
it('keeps the latest infusion status when an older refresh arrives late', async () => {
  const one = deferred(), two = deferred(); const h = setup('nurse', vi.fn().mockReturnValueOnce(one.promise).mockReturnValueOnce(two.promise))
  const first = h.v.loadInfusions(), last = h.v.loadInfusions(); two.resolve([{ ...pending, status: 'DONE' }]); await last; one.resolve([pending]); await first
  expect(h.v.infusions.value[0].status).toBe('DONE')
})
it('keeps the latest candidate list when an older refresh arrives late', async () => {
  const one = deferred(), two = deferred(); const h = setup('nurse', vi.fn().mockReturnValueOnce(one.promise).mockReturnValueOnce(two.promise))
  const first = h.v.loadCandidates(), last = h.v.loadCandidates(); two.resolve([]); await last; one.resolve([{ order_id: 11 }]); await first
  expect(h.v.candidates.value).toEqual([])
})
it('does not start a PENDING history whose source order has been refunded', async () => {
  const h = setup('nurse', vi.fn(() => response([{ ...pending, order_status: 'CREATED' }])))
  await h.v.loadInfusions(); await h.v.startInfusion(h.v.infusions.value[0]); expect(h.write).not.toHaveBeenCalled()
})
it('infusion start ignores a duplicate click before HTTP and preserves one success', async () => {
  const wait = deferred(); const h = setup('nurse', vi.fn(() => response([pending])), vi.fn(() => wait.promise))
  await h.v.loadInfusions(); const row = h.v.infusions.value[0]; const first = h.v.startInfusion(row), second = h.v.startInfusion(row)
  wait.resolve(null); await Promise.all([first, second]); expect(h.write).toHaveBeenCalledTimes(1)
})
it('refreshes actual infusion state after a competing start is refused', async () => {
  let state = [pending]; const h = setup('nurse', vi.fn(path => response(path.includes('candidates') ? [] : state)), vi.fn(() => Promise.reject({ bizCode: 4501 })))
  await h.v.loadInfusions(); const row = h.v.infusions.value[0]; state = [{ ...pending, status: 'DONE' }]; await h.v.startInfusion(row)
  expect(h.v.infusions.value[0].status).toBe('DONE'); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('a late check prompt cannot append to an infusion that has finished', async () => {
  const prompt = deferred(); const h = setup('nurse', vi.fn(() => response([running]))); h.box.ElMessageBox.prompt = vi.fn(() => prompt.promise)
  await h.v.loadInfusions(); const first = h.v.addCheck(h.v.infusions.value[0]); h.v.infusions.value = [{ ...running, status: 'DONE' }]
  prompt.raw({ value: '过期巡视内容' }); await first; expect(h.write).not.toHaveBeenCalled()
})
it('does not restore an infusion list after leaving the page', async () => {
  const wait = deferred(); const h = setup('nurse', vi.fn(() => wait.promise)); const first = h.v.loadInfusions(); h.dispose(); wait.resolve([pending]); await first
  expect(h.v.infusions.value).toEqual([])
})
