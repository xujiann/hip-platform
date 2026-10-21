import { describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { ref } from 'vue'

// Execute the actual view's setup functions against deferred HTTP, without a DOM or copied implementation.
function setup(get: ReturnType<typeof vi.fn>, post = vi.fn()) {
  const source = readFileSync(new URL('../../views/portal/PortalHomeView.vue', import.meta.url), 'utf8')
    .split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
  const dispose: Array<() => void> = []
  const box: Record<string, any> = { ref, onMounted: () => {}, onUnmounted: (f: () => void) => dispose.push(f),
    useRouter: () => ({ push: vi.fn() }), localStorage: { getItem: () => 'test', removeItem: vi.fn() },
    ElMessage: { success: vi.fn(), info: vi.fn() }, portal: { get, post }, fmtDate: () => '', todayLocal: () => '2026-10-21' }
  const expose = '\nglobalThis.view = { schedules, date, slots, slotScheduleId, tab, openSlots, loadSchedules, bookSlot };'
  runInNewContext(ts.transpileModule(source + expose, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box)
  return { v: box.view, dispose: () => dispose.forEach(f => f()) }
}
function deferred() {
  let resolve!: (v: any) => void
  const promise = new Promise<any>(r => { resolve = r })
  return { promise, resolve: (data: unknown) => resolve({ data: { data } }) }
}
const response = (data: unknown) => Promise.resolve({ data: { data } })
const a = { id: 11 }, b = { id: 22 }, sa = { id: 111, remaining: 2 }, sb = { id: 222, remaining: 3 }

describe('portal slot browsing against the real view setup', () => {
  it('keeps the enabled schedule happy path and original slot request', async () => {
    const get = vi.fn(() => response([sa])); const { v } = setup(get)
    v.schedules.value = [a, b]; await v.openSlots(a)
    expect(get).toHaveBeenCalledWith('/schedules/11/slots')
    expect(v.slotScheduleId.value).toBe(11); expect(v.slots.value).toEqual([sa])
  })
  it('does not let a late first schedule replace the second schedule slots', async () => {
    const first = deferred(), second = deferred(); const get = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const { v } = setup(get); v.schedules.value = [a, b]
    const one = v.openSlots(a), two = v.openSlots(b)
    second.resolve([sb]); await two; first.resolve([sa]); await one
    expect(v.slotScheduleId.value).toBe(22); expect(v.slots.value).toEqual([sb])
  })
  it('clears the old panel on date changes and ignores the old catalog response', async () => {
    const first = deferred(), second = deferred(); const get = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const { v } = setup(get); v.schedules.value = [a]; v.slots.value = [sa]; v.slotScheduleId.value = 11
    const one = v.loadSchedules(); v.date.value = '2026-10-22'; const two = v.loadSchedules()
    expect(v.slots.value).toEqual([]); expect(v.slotScheduleId.value).toBeNull()
    second.resolve([b]); await two; first.resolve([a]); await one
    expect(v.schedules.value).toEqual([b]); expect(v.slots.value).toEqual([])
  })
  it('a collapsed panel stays empty when its pending response arrives', async () => {
    const pending = deferred(); const { v } = setup(vi.fn(() => pending.promise)); v.schedules.value = [a]
    const request = v.openSlots(a); await v.openSlots(a); pending.resolve([sa]); await request
    expect(v.slotScheduleId.value).toBeNull(); expect(v.slots.value).toEqual([])
  })
  it('a submitted old slot never reloads or changes the newly selected panel', async () => {
    const pending = deferred(); const post = vi.fn(() => pending.promise)
    const get = vi.fn((path: string) => response(path === '/schedules/22/slots' ? [sb] : []))
    const { v } = setup(get, post); v.schedules.value = [a, b]; v.slotScheduleId.value = 11; v.slots.value = [sa]
    const booking = v.bookSlot(sa); await v.openSlots(b); pending.resolve({ apptNo: 1 }); await booking
    expect(post).toHaveBeenCalledWith('/appointments', { slotId: 111 })
    expect(get.mock.calls.filter(c => c[0] === '/schedules/22/slots')).toHaveLength(1)
    expect(v.slotScheduleId.value).toBe(22); expect(v.slots.value).toEqual([sb]); expect(v.tab.value).toBe('book')
  })
  it('does not restore a pending panel after leaving the page', async () => {
    const pending = deferred(); const { v, dispose } = setup(vi.fn(() => pending.promise)); v.schedules.value = [a]
    const request = v.openSlots(a); dispose(); pending.resolve([sa]); await request
    expect(v.slots.value).toEqual([])
  })
})
