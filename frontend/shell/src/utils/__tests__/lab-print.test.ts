import { it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { ref, computed, reactive, watch, nextTick } from 'vue'
import * as format from '../print-format'

const original = { id: 11, item_name: '模拟检验', group_no: 'V121-A', created_at: '2026-10-24T01:00:00Z', patient_name: '模拟甲', sex: 'U', patient_no: 'V121-P',
  results: [{ item_name: '模拟钾', result_value: '7.5', unit: 'mmol/L', ref_range: '3.5-5.5', abnormal_flag: 'HH' }] }
const revised = { ...original, results: [...original.results, { item_name: '模拟复查', result_value: '4.5', unit: null, ref_range: null, abnormal_flag: null }] }
const response = (data: unknown) => Promise.resolve({ data: { data } })
function deferred() {
  let resolve!: (v: any) => void, reject!: (reason: unknown) => void
  const promise = new Promise<any>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (data: unknown) => resolve({ data: { data } }), raw: () => resolve(null), reject }
}
function setup(get = vi.fn(() => response(original)), type = 'lab-report', tick = vi.fn(() => nextTick())) {
  const component = readFileSync(new URL('../../views/PrintView.vue', import.meta.url), 'utf8')
  const source = component.split('<script setup lang="ts">')[1].split('</script>')[0]
  const parsed = ts.createSourceFile('PrintView.ts', source, ts.ScriptTarget.ESNext, true, ts.ScriptKind.TS)
  const body = parsed.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getFullText(parsed)).join('\n')
  const disposers: Array<() => void> = [], mounted: Array<() => Promise<void>> = []
  const route = reactive({ query: { type, id: '11', date: '2026-10-24', week: '1' } })
  const storage = new Map([['hip_token', 'synthetic-employee-A']]), print = vi.fn()
  const box: Record<string, any> = { ref, computed, nextTick: tick, onMounted: (f: () => Promise<void>) => mounted.push(f),
    onUnmounted: (f: () => void) => disposers.push(f), watch: (...args: any[]) => { const stop = (watch as any)(...args); disposers.push(stop); return stop },
    useRoute: () => route, localStorage: { getItem: (key: string) => storage.get(key) ?? null }, client: { get, post: vi.fn() },
    URLSearchParams, window: { print }, fmtDate: () => '', fmtDateTime: () => '',
    ageTextOf: format.ageText, allergyLine: format.allergyLine, briefHistoryOf: format.briefHistory, docDoctorOf: format.docDoctor,
    docDoctorSuffixOf: format.docDoctorSuffix, firstOf: format.firstOf, formatDiagnoses: format.formatDiagnoses,
    guideStatusOf: format.guideStatusOf, money2: format.money2, printedAt: format.printedAt }
  const button = Array.from(component.matchAll(/<el-button\b([^>]+)>/g)).find(m => m[1].includes('@click="doPrint"'))
  const disabled = (button?.[1].match(/:disabled="([^"]+)"/)?.[1] ?? 'false').replace(/\b(loading|labPrinting|data)\b/g, '$1.value')
  runInNewContext(ts.transpileModule(body + `\nglobalThis.view = { data, loading, hospitalName, reload, doPrint, gotoWeek, week }; globalThis.disabled = () => ${disabled};`,
    { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box)
  return { v: box.view, get, route, storage, print, tick, disabled: box.disabled, mounted, dispose: () => disposers.forEach(f => f()) }
}
async function loaded(get = vi.fn(() => response(original)), type = 'lab-report') { const h = setup(get, type); await h.v.reload(); return h }
async function turns() { for (let i = 0; i < 8; i++) await Promise.resolve() }

it('retains the selected order endpoint and original report payload including critical and null fields', async () => {
  const h = await loaded(vi.fn(() => response(revised))); expect(h.get).toHaveBeenCalledExactlyOnceWith('/print/lab-report/11'); expect(h.v.data.value).toEqual(revised)
})
it('revalidates a lab report before printing and uses the latest returned results', async () => {
  const h = await loaded(vi.fn().mockResolvedValueOnce({ data: { data: original } }).mockResolvedValueOnce({ data: { data: revised } }))
  await h.v.doPrint(); expect(h.get).toHaveBeenCalledTimes(2); expect(h.v.data.value).toEqual(revised); expect(h.print).toHaveBeenCalledOnce(); expect(h.tick).toHaveBeenCalledOnce()
})
it('holds one print operation across a pending revalidation', async () => {
  const wait = deferred(), h = await loaded(vi.fn().mockImplementationOnce(() => response(original)).mockImplementation(() => wait.promise))
  const first = h.v.doPrint(), second = h.v.doPrint(); expect(h.print).not.toHaveBeenCalled(); expect(h.get).toHaveBeenCalledTimes(2)
  wait.resolve(revised); await Promise.all([first, second]); expect(h.print).toHaveBeenCalledOnce()
})
it('blocks direct print calls while the initial lab report is loading', async () => {
  const wait = deferred(), h = setup(vi.fn(() => wait.promise)); const read = h.v.reload(); await h.v.doPrint()
  expect(h.print).not.toHaveBeenCalled(); expect(h.disabled()).toBe(true); wait.resolve(original); await read
})
it('does not print a lab page without a loaded report', async () => {
  const h = setup(); await h.v.doPrint(); expect(h.get).not.toHaveBeenCalled(); expect(h.print).not.toHaveBeenCalled(); expect(h.disabled()).toBe(true)
})
for (const error of [{ bizCode: 9932 }, new Error('simulated network failure'), { response: { status: 403 } }]) {
  it(`clears the previous lab report and does not print on revalidation refusal ${String((error as any).bizCode ?? (error as any).response?.status ?? 'network')}`, async () => {
    const h = await loaded(vi.fn().mockImplementationOnce(() => response(original)).mockRejectedValueOnce(error))
    await expect(Promise.resolve(h.v.doPrint())).resolves.toBeUndefined(); expect(h.v.data.value).toBeNull(); expect(h.print).not.toHaveBeenCalled(); expect(h.v.loading.value).toBe(false)
  })
}
it('clears the old lab body as another read starts', async () => {
  const wait = deferred(), h = await loaded(vi.fn().mockImplementationOnce(() => response(original)).mockImplementation(() => wait.promise))
  const read = h.v.reload(); expect(h.v.data.value).toBeNull(); wait.resolve(revised); await read
})
it('does not allow a stale read to overwrite the latest report', async () => {
  const old = deferred(); const h = setup(vi.fn().mockImplementationOnce(() => old.promise).mockImplementationOnce(() => response(revised)))
  const first = h.v.reload(); await h.v.reload(); old.resolve(original); await first; expect(h.v.data.value).toEqual(revised)
})
it('ignores an old employee response after the token changes', async () => {
  const wait = deferred(), h = setup(vi.fn(() => wait.promise)); const read = h.v.reload(); h.storage.set('hip_token', 'synthetic-employee-B')
  wait.resolve(original); await read; expect(h.v.data.value).toBeNull()
})
it('a route change invalidates the original lab response without following a new order', async () => {
  const wait = deferred(), h = setup(vi.fn(() => wait.promise)); const read = h.v.reload(); h.route.query.id = '22'; await nextTick()
  wait.resolve(original); await read; expect(h.v.data.value).toBeNull(); expect(h.get).toHaveBeenCalledExactlyOnceWith('/print/lab-report/11')
})
it('does not print the original lab document after its route object changes', async () => {
  const h = await loaded(); h.route.query.id = '22'; await nextTick(); await h.v.doPrint(); expect(h.v.data.value).toBeNull(); expect(h.print).not.toHaveBeenCalled()
})
it('does not restore a pending report after leaving the page', async () => {
  const wait = deferred(), h = setup(vi.fn(() => wait.promise)); const read = h.v.reload(); h.dispose(); wait.resolve(original); await read; expect(h.v.data.value).toBeNull()
})
it('does not fetch or print after leaving the lab page', async () => {
  const h = setup(); h.dispose(); await h.v.reload(); await h.v.doPrint(); expect(h.get).not.toHaveBeenCalled(); expect(h.print).not.toHaveBeenCalled()
})
it('waits for rendered results and rechecks identity before invoking native print', async () => {
  const render = deferred(), tick = vi.fn(() => render.promise), h = setup(vi.fn(() => response(original)), 'lab-report', tick)
  await h.v.reload(); const printing = h.v.doPrint(); await turns(); expect(h.print).not.toHaveBeenCalled(); expect(tick).toHaveBeenCalledOnce()
  h.storage.set('hip_token', 'synthetic-employee-B'); render.raw(); await printing; expect(h.print).not.toHaveBeenCalled()
})
it('a newer read invalidates an earlier print revalidation', async () => {
  const pending = deferred(), h = await loaded(vi.fn().mockImplementationOnce(() => response(original)).mockImplementationOnce(() => pending.promise).mockImplementationOnce(() => response(revised)))
  const printing = h.v.doPrint(); expect(h.get).toHaveBeenCalledTimes(2); await h.v.reload(); pending.resolve(original); await printing
  expect(h.v.data.value).toEqual(revised); expect(h.print).not.toHaveBeenCalled()
})
it('retains the previous temperature sheet when a week read fails', async () => {
  const sheet = { week: 1, totalWeeks: 3, header: {}, points: [] }
  const h = await loaded(vi.fn().mockImplementationOnce(() => response(sheet)).mockRejectedValueOnce(new Error('simulated week failure')), 'temp-sheet')
  await expect(h.v.gotoWeek(2)).rejects.toThrow('simulated week failure'); expect(h.v.data.value).toEqual(sheet); await h.v.doPrint(); expect(h.print).toHaveBeenCalledOnce()
})
for (const type of ['registration', 'charge', 'prescription', 'nur-record']) {
  it(`keeps ${type} printing without a new lab preflight or role restriction`, async () => {
    const h = await loaded(undefined, type); await h.v.doPrint(); expect(h.get).toHaveBeenCalledTimes(1); expect(h.print).toHaveBeenCalledOnce(); expect(h.disabled()).toBe(false)
  })
}
