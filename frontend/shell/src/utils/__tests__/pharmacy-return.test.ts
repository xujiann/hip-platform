import { it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { computed, ref } from 'vue'

const response = (data: unknown) => Promise.resolve({ data: { data } })
function deferred() {
  let resolve!: (value: any) => void
  const promise = new Promise<any>(r => { resolve = r })
  return { promise, resolve: (data: unknown) => resolve({ data: { data } }) }
}
const a = { id: 111, registrationId: 11, itemName: '模拟甲药品' }
const b = { id: 222, registrationId: 22, itemName: '模拟乙药品' }
const wa = { registrationId: 11, patientName: '模拟甲', orders: [a] }
const wb = { registrationId: 22, patientName: '模拟乙', orders: [b] }
function setup(get = vi.fn((path: string) => response(path.endsWith('/worklist') ? [wa, wb] : path.endsWith('/registrations') ? [] : [a])),
  post = vi.fn(() => response(null)), roles = ['PHARMACIST'], permission = true) {
  const component = readFileSync(new URL('../../views/outpatient/PharmacyView.vue', import.meta.url), 'utf8')
  const source = component.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
  const dispose: Array<() => void> = []
  const box: Record<string, any> = { ref, computed, onMounted: () => {}, onUnmounted: (f: () => void) => dispose.push(f),
    todayLocal: () => '2026-10-22', useAuthStore: () => ({ user: { roles }, hasPerm: () => permission }),
    client: { get, post }, ElMessage: { success: vi.fn(), warning: vi.fn() } }
  const refresh = component.match(/@click="(\w+)">刷新<\/el-button>/)?.[1]
  // Execute the real template conditions as well as the real SFC functions.
  const button = (action: string) => Array.from(component.matchAll(/<el-button\b([^>]+)>/g)).find(m => m[1].includes(`@click="${action}"`))?.[1] ?? ''
  const condition = (action: string) => button(action).match(/v-if="([^"]+)"/)?.[1] ?? 'true'
  const unwrap = (expression: string) => expression.replace(/\b(canDispense|canReturn|writing)\b/g, '$1.value')
  runInNewContext(ts.transpileModule(source + `
    globalThis.view = { worklist, dispensing, returning, todayRegs, returnRegId, dispensed, load, loadDispensed, returnDrug, dispense, refresh: ${refresh} };
    globalThis.buttons = { dispense: (w) => ${unwrap(condition('dispense(w)'))}, return: (row) => ${unwrap(condition('returnDrug(row)'))} };
  `, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box)
  return { v: box.view, box, get, post, dispose: () => dispose.forEach(f => f()) }
}
async function select(h: ReturnType<typeof setup>, registrationId: number | null) {
  h.v.returnRegId.value = registrationId
  await h.v.loadDispensed()
}

it('a pharmacist returns the selected dispensed order through the existing API', async () => {
  const h = setup(); await h.v.load(); await select(h, 11)
  await h.v.returnDrug(h.v.dispensed.value[0])
  expect(h.post).toHaveBeenCalledExactlyOnceWith('/outpatient/dispense/orders/111/return')
  expect(h.box.ElMessage.success).toHaveBeenCalledOnce()
})
it('an administrator dispenses a captured worklist item through the existing API', async () => {
  const h = setup(undefined, undefined, ['ADMIN']); await h.v.load(); await h.v.dispense(h.v.worklist.value[0])
  expect(h.post).toHaveBeenCalledExactlyOnceWith('/outpatient/dispense/11')
  expect(h.box.ElMessage.success).toHaveBeenCalledOnce()
})
it('a pharmacist loads today choices from the pharmacy endpoint rather than the cashier registration desk', async () => {
  const h = setup(); await h.v.load()
  expect(h.get).toHaveBeenCalledWith('/outpatient/dispense/registrations', { params: { date: '2026-10-22' } })
  expect(h.get).not.toHaveBeenCalledWith('/outpatient/registrations', expect.anything())
})
it('a non-pharmacy role has neither visible write buttons nor callable stale-page writes', async () => {
  const h = setup(undefined, undefined, ['NURSE']); h.v.worklist.value = [wa]; h.v.returnRegId.value = 11; h.v.dispensed.value = [a]
  expect(h.box.buttons.dispense(wa)).toBe(false); expect(h.box.buttons.return(a)).toBe(false)
  await h.v.dispense(wa); await h.v.returnDrug(a); expect(h.post).not.toHaveBeenCalled()
})
it('an explicit missing button permission also prevents direct stale-page writes', async () => {
  const h = setup(undefined, undefined, ['PHARMACIST'], false); await h.v.load(); await select(h, 11)
  expect(h.box.buttons.dispense(wa)).toBe(false); expect(h.box.buttons.return(a)).toBe(false)
  await h.v.dispense(h.v.worklist.value[0]); await h.v.returnDrug(h.v.dispensed.value[0]); expect(h.post).not.toHaveBeenCalled()
})
it('an in-flight return blocks duplicate returns and dispensing before the HTTP client', async () => {
  const pending = deferred(); const h = setup(undefined, vi.fn(() => pending.promise)); await h.v.load(); await select(h, 11)
  const row = h.v.dispensed.value[0]; const first = h.v.returnDrug(row)
  const duplicate = h.v.returnDrug(row), other = h.v.dispense(h.v.worklist.value[1])
  pending.resolve(null); await Promise.all([first, duplicate, other]); expect(h.post).toHaveBeenCalledTimes(1)
})
it('an in-flight dispense blocks a second dispense and a return', async () => {
  const pending = deferred(); const h = setup(undefined, vi.fn(() => pending.promise)); await h.v.load(); await select(h, 11)
  const first = h.v.dispense(h.v.worklist.value[0]), other = h.v.dispense(h.v.worklist.value[1]), returned = h.v.returnDrug(h.v.dispensed.value[0])
  pending.resolve(null); await Promise.all([first, other, returned]); expect(h.post).toHaveBeenCalledTimes(1)
})
it('orders and worklist entries absent from the current lists cannot submit', async () => {
  const h = setup(); await h.v.load(); await select(h, 11)
  await h.v.returnDrug(b); await h.v.dispense({ registrationId: 99, patientName: '过期患者' }); expect(h.post).not.toHaveBeenCalled()
})
it('clearing the selected registration immediately removes old return rows and invalidates late reads', async () => {
  const pending = deferred(); const h = setup(vi.fn(() => pending.promise)); h.v.returnRegId.value = 11
  const first = h.v.loadDispensed(); h.v.dispensed.value = [a]
  await select(h, null); expect(h.v.dispensed.value).toEqual([])
  pending.resolve([a]); await first; expect(h.v.dispensed.value).toEqual([])
})
it('a late return-list response for A never replaces the newly selected B', async () => {
  const pending = deferred(); const h = setup(vi.fn().mockReturnValueOnce(pending.promise).mockReturnValueOnce(response([b])))
  h.v.returnRegId.value = 11; const first = h.v.loadDispensed(); await select(h, 22)
  pending.resolve([a]); await first; expect(h.v.dispensed.value).toEqual([b])
})
it('an earlier full refresh cannot overwrite the latest worklist or patient choices', async () => {
  const one = deferred(), two = deferred(); let round = 0
  const h = setup(vi.fn(() => (++round <= 2 ? one.promise : two.promise)))
  const first = h.v.load(), last = h.v.load(); two.resolve([wb]); await last; one.resolve([wa]); await first
  expect(h.v.worklist.value).toEqual([wb]); expect(h.v.todayRegs.value).toEqual([wb])
})
it('a return refused with 6004 refreshes both real lists without a success or stock-restored message', async () => {
  let rows = [a]; const get = vi.fn((path: string) => response(path.endsWith('/dispensed') ? rows : []))
  const h = setup(get, vi.fn(() => Promise.reject({ bizCode: 6004 }))); await select(h, 11); get.mockClear(); rows = []
  await h.v.returnDrug(h.v.dispensed.value[0])
  expect(get).toHaveBeenCalledWith('/outpatient/dispense/worklist')
  expect(get).toHaveBeenCalledWith('/outpatient/dispense/dispensed', { params: { registrationId: 11 } })
  expect(h.v.dispensed.value).toEqual([]); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('a successful return refreshes the pending dispensing queue as well as the selected return list', async () => {
  const h = setup(); await select(h, 11); h.get.mockClear(); await h.v.returnDrug(h.v.dispensed.value[0])
  expect(h.get).toHaveBeenCalledWith('/outpatient/dispense/worklist')
  expect(h.get).toHaveBeenCalledWith('/outpatient/dispense/dispensed', { params: { registrationId: 11 } })
})
it('an A write finishing after selecting B neither reloads B nor announces A on B', async () => {
  const pending = deferred(); const get = vi.fn((path: string, options?: any) => response(path.endsWith('/dispensed') ? options.params.registrationId === 11 ? [a] : [b] : []))
  const h = setup(get, vi.fn(() => pending.promise)); await select(h, 11)
  const first = h.v.returnDrug(h.v.dispensed.value[0]); await select(h, 22); get.mockClear(); pending.resolve(null); await first
  expect(h.v.dispensed.value).toEqual([b]); expect(get).not.toHaveBeenCalledWith('/outpatient/dispense/dispensed', expect.anything())
  expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('switching A to B and back does not let an earlier A write refresh the new A selection', async () => {
  const pending = deferred(); const h = setup(undefined, vi.fn(() => pending.promise)); await select(h, 11)
  const first = h.v.returnDrug(h.v.dispensed.value[0]); await select(h, 22); await select(h, 11); h.get.mockClear()
  pending.resolve(null); await first
  expect(h.get).not.toHaveBeenCalledWith('/outpatient/dispense/dispensed', expect.anything()); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('a late write after leaving the page cannot announce success or start new reads', async () => {
  const pending = deferred(); const h = setup(undefined, vi.fn(() => pending.promise)); await select(h, 11)
  const first = h.v.returnDrug(h.v.dispensed.value[0]); h.dispose(); h.get.mockClear(); pending.resolve(null); await first
  expect(h.get).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('late worklist and return-list reads cannot restore the page after disposal', async () => {
  const pending = deferred(); const h = setup(vi.fn(() => pending.promise)); h.v.returnRegId.value = 11
  const list = h.v.load(), selected = h.v.loadDispensed(); h.dispose(); pending.resolve([a]); await Promise.all([list, selected])
  expect(h.v.worklist.value).toEqual([]); expect(h.v.todayRegs.value).toEqual([]); expect(h.v.dispensed.value).toEqual([])
})
it('the actual refresh button reloads both the worklist and the selected dispensed orders', async () => {
  const h = setup(); await select(h, 11); h.get.mockClear(); await h.v.refresh()
  expect(h.get).toHaveBeenCalledWith('/outpatient/dispense/worklist')
  expect(h.get).toHaveBeenCalledWith('/outpatient/dispense/dispensed', { params: { registrationId: 11 } })
})
