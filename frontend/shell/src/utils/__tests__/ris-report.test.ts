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
const registered = { id: 1, item_name: '模拟甲检查', group_no: 'V114-A', status: 'REGISTERED', order_status: 'CHARGED', findings: '', impression: '' }
const reported = { id: 2, item_name: '模拟乙检查', group_no: 'V114-B', status: 'REPORTED', order_status: 'EXECUTED', findings: '乙影像所见', impression: '乙意见' }
const template = { id: 7, name: '模拟检查模板', content: '模拟模板影像所见' }
function setup(get = vi.fn((path: string) => response(path === '/ris/worklist' ? [registered, reported] : path === '/emr-templates' ? [template] : {})),
  put = vi.fn(() => response(null)), roles = ['TECHNICIAN']) {
  const component = readFileSync(new URL('../../views/medtech/RisView.vue', import.meta.url), 'utf8')
  const source = component.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
  const dispose: Array<() => void> = []
  const user = reactive({ roles })
  const post = vi.fn(() => response({ url: '/share/v114-synthetic' }))
  const box: Record<string, any> = { ref, computed, onMounted: () => {}, onUnmounted: (f: () => void) => dispose.push(f),
    useAuthStore: () => ({ user }), client: { get, put, post }, window: { open: vi.fn() }, location: { origin: 'http://v114.local' },
    ElMessage: { success: vi.fn(), warning: vi.fn() },
    ElMessageBox: { prompt: vi.fn(() => Promise.resolve({ value: '' })), alert: vi.fn(() => Promise.resolve()) } }
  const buttons = Array.from(component.matchAll(/<el-button\b([^>]+)>/g))
  const binding = (action: string, attribute: string, fallback: string) => buttons.find(m => m[1].includes(`@click="${action}"`))?.[1].match(new RegExp(`${attribute}="([^"]+)"`))?.[1] ?? fallback
  const expression = (text: string) => text.replace(/\b(canWrite|writing|current)\b/g, '$1.value')
  const column = Array.from(component.matchAll(/<el-table-column\b([^>]+)>/g)).find(m => m[1].includes('label="源医嘱状态"'))
  const sourceColumn = column ? component.slice(column.index!, component.indexOf('</el-table-column>', column.index!) + 18) : ''
  // Execute this component's functions and actual template expressions; no duplicate policy implementation.
  runInNewContext(ts.transpileModule(source + `
    globalThis.view = { records, dialogVisible, current, findings, impression, busyId, saveReportLoading, templates, templateId, pacs,
      load, arrive, openReport, saveReport, markCritical, verify, applyTemplate, loadPacs, openPacs };
    globalThis.visible = { arrive: row => ${expression(binding('arrive(row)', 'v-if', 'true'))},
      report: row => ${expression(binding('openReport(row)', 'v-if', 'true'))},
      critical: row => ${expression(binding('markCritical(row)', 'v-if', 'true'))},
      verify: row => ${expression(binding('verify(row)', 'v-if', 'true'))} };
    globalThis.submitDisabled = () => ${expression(binding('saveReport', ':disabled', 'false'))};
  `, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box)
  return { v: box.view, box, user, get, put, post, sourceColumn, dispose: () => dispose.forEach(f => f()) }
}
async function opened(h: ReturnType<typeof setup>, index = 0) {
  await h.v.load(); h.v.openReport(h.v.records.value[index])
}
const settle = (promise: Promise<unknown>) => promise.catch(() => {})

it('retains arrival registration for a paid registered examination', async () => {
  const h = setup(); await h.v.load(); await h.v.arrive(h.v.records.value[0])
  expect(h.put).toHaveBeenCalledExactlyOnceWith('/ris/exams/1/arrive')
  expect(h.box.ElMessage.success).toHaveBeenCalledExactlyOnceWith('已到检登记')
})
it('saves the original examination with copied findings and impression then closes its own draft', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await opened(h)
  expect(h.v.findings.value).toBe(''); h.v.findings.value = '甲原稿'; h.v.impression.value = '甲意见'
  const first = h.v.saveReport(); h.v.findings.value = '后来的编辑'; h.v.impression.value = '后来意见'
  expect(h.put).toHaveBeenCalledExactlyOnceWith('/ris/exams/1/report', { findings: '甲原稿', impression: '甲意见' })
  wait.resolve(null); await first; expect(h.v.dialogVisible.value).toBe(false)
  expect(h.box.ElMessage.success).toHaveBeenCalledExactlyOnceWith('报告已保存')
})
for (const role of ['ADMIN', 'TECHNICIAN', 'DOCTOR_OUTP', 'NURSE']) {
  it(`retains ${role} editing and independent verification on an executed source`, async () => {
    const h = setup(undefined, undefined, [role]); await opened(h, 1)
    expect(h.box.visible.report(h.v.records.value[1])).toBe(true)
    await h.v.saveReport(); await h.v.verify(h.v.records.value[1])
    expect(h.put).toHaveBeenCalledWith('/ris/exams/2/report', { findings: reported.findings, impression: reported.impression })
    expect(h.put).toHaveBeenCalledWith('/ris/exams/2/verify')
  })
}
it('requests and displays the original verified examination share for a technician', async () => {
  const h = setup(); await h.v.load(); await h.v.verify(h.v.records.value[1])
  expect(h.post).toHaveBeenCalledExactlyOnceWith('/ris/exams/2/share')
  expect(h.box.ElMessageBox.alert).toHaveBeenCalledWith(expect.stringContaining('http://v114.local/share/v114-synthetic'), '报告分享')
})
it('a nurse successfully verifies and refreshes without requesting a share forbidden to that role', async () => {
  const h = setup(undefined, undefined, ['NURSE']); await h.v.load(); await h.v.verify(h.v.records.value[1])
  expect(h.put).toHaveBeenCalledExactlyOnceWith('/ris/exams/2/verify'); expect(h.post).not.toHaveBeenCalled()
  expect(h.box.ElMessage.success).toHaveBeenCalledOnce(); expect(h.box.ElMessageBox.alert).not.toHaveBeenCalled()
  expect(h.get.mock.calls.filter(c => c[0] === '/ris/worklist')).toHaveLength(2)
})
it('keeps template application and PACS viewer behavior on a valid draft', async () => {
  const h = setup(vi.fn((path: string) => response(path === '/ris/worklist' ? [registered, reported] : path === '/emr-templates' ? [template] : { configured: true, viewerUrl: 'http://pacs.local/view' })))
  await opened(h); h.v.templateId.value = 7; h.v.applyTemplate(); expect(h.v.findings.value).toBe(template.content)
  await h.v.loadPacs(); h.v.openPacs(); expect(h.box.window.open).toHaveBeenCalledExactlyOnceWith('http://pacs.local/view', '_blank')
})
it('submits a trimmed critical note and preserves the original examination endpoint', async () => {
  const h = setup(); await h.v.load(); h.box.ElMessageBox.prompt = vi.fn(() => Promise.resolve({ value: '  模拟危急征象  ' }))
  await h.v.markCritical(h.v.records.value[1]); expect(h.put).toHaveBeenCalledExactlyOnceWith('/ris/exams/2/critical', { note: '模拟危急征象' })
  expect(h.box.ElMessage.success).toHaveBeenCalledOnce()
})
it('blank and cancelled critical notes do not write', async () => {
  const h = setup(); await h.v.load(); await h.v.markCritical(h.v.records.value[1])
  expect(h.box.ElMessage.warning).toHaveBeenCalledOnce()
  h.box.ElMessageBox.prompt = vi.fn(() => Promise.reject('cancel')); await h.v.markCritical(h.v.records.value[1]); expect(h.put).not.toHaveBeenCalled()
})
it('consumes order_status in the actual source status column while keeping refunded history visible', async () => {
  const history = { ...reported, order_status: 'CREATED' }
  const h = setup(vi.fn((path: string) => response(path === '/ris/worklist' ? [history] : []))); await h.v.load()
  expect(h.sourceColumn).toContain('order_status'); expect(h.v.records.value).toEqual([history])
  for (const isVisible of Object.values(h.box.visible) as Array<(row: unknown) => boolean>) expect(isVisible(h.v.records.value[0])).toBe(false)
})
it('wrong roles cannot see or invoke any of the four writes from a stale page', async () => {
  const h = setup(undefined, undefined, ['CASHIER']); await h.v.load()
  for (const row of h.v.records.value) {
    for (const isVisible of Object.values(h.box.visible) as Array<(row: unknown) => boolean>) expect(isVisible(row)).toBe(false)
    h.v.openReport(row); await h.v.saveReport(); await h.v.arrive(row); await h.v.verify(row); await h.v.markCritical(row)
  }
  expect(h.put).not.toHaveBeenCalled(); expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessageBox.prompt).not.toHaveBeenCalled()
})
for (const source of ['CREATED', 'CANCELLED', undefined]) {
  it(`source ${source ?? 'missing'} permits none of the four direct writes`, async () => {
    const rows = [{ ...registered, order_status: source }, { ...reported, order_status: source }]
    const h = setup(vi.fn((path: string) => response(path === '/ris/worklist' ? rows : []))); await h.v.load()
    for (const row of h.v.records.value) {
      h.v.openReport(row); await h.v.saveReport(); await h.v.arrive(row); await h.v.verify(row); await h.v.markCritical(row)
    }
    expect(h.put).not.toHaveBeenCalled(); expect(h.box.ElMessageBox.prompt).not.toHaveBeenCalled(); expect(h.v.dialogVisible.value).toBe(false)
  })
}
it('a detached row and an invalid examination state cannot trigger any write', async () => {
  const h = setup(); await h.v.load(); const removed = h.v.records.value[1]; h.v.records.value = [{ ...registered, status: 'VERIFIED' }]
  for (const row of [removed, h.v.records.value[0]]) {
    h.v.openReport(row); await h.v.saveReport(); await h.v.arrive(row); await h.v.verify(row); await h.v.markCritical(row)
  }
  expect(h.put).not.toHaveBeenCalled(); expect(h.box.ElMessageBox.prompt).not.toHaveBeenCalled()
})
it('a refreshed unpaid source disables the actual modal save while preserving the unsent draft', async () => {
  const h = setup(); await opened(h); h.v.findings.value = '未提交草稿'
  h.v.records.value = [{ ...registered, order_status: 'CREATED' }, reported]
  expect(h.box.submitDisabled()).toBe(true); await h.v.saveReport()
  expect(h.put).not.toHaveBeenCalled(); expect(h.v.findings.value).toBe('未提交草稿')
})
it('prevents duplicate saves and other table writes while saving', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await opened(h)
  const first = h.v.saveReport(), second = h.v.saveReport(), arrival = h.v.arrive(h.v.records.value[0]), verify = h.v.verify(h.v.records.value[1]), critical = h.v.markCritical(h.v.records.value[1])
  expect(h.box.submitDisabled()).toBe(true); expect(h.box.ElMessageBox.prompt).not.toHaveBeenCalled()
  wait.resolve(null); await Promise.all([first, second, arrival, verify, critical]); expect(h.put).toHaveBeenCalledTimes(1)
})
it('an arrival in flight blocks verification, reporting and critical prompting', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await opened(h)
  const first = h.v.arrive(h.v.records.value[0]); const rest = [h.v.arrive(h.v.records.value[0]), h.v.saveReport(), h.v.verify(h.v.records.value[1]), h.v.markCritical(h.v.records.value[1])]
  wait.resolve(null); await Promise.all([first, ...rest]); expect(h.put).toHaveBeenCalledTimes(1); expect(h.box.ElMessageBox.prompt).not.toHaveBeenCalled()
})
it('a pending critical prompt blocks another prompt and all other writes', async () => {
  const prompt = deferred(); const h = setup(); await opened(h); h.box.ElMessageBox.prompt = vi.fn(() => prompt.promise)
  const first = h.v.markCritical(h.v.records.value[1]); const rest = [h.v.markCritical(h.v.records.value[1]), h.v.saveReport(), h.v.arrive(h.v.records.value[0]), h.v.verify(h.v.records.value[1])]
  expect(h.box.ElMessageBox.prompt).toHaveBeenCalledTimes(1); expect(h.put).not.toHaveBeenCalled()
  prompt.raw({ value: '模拟危急征象' }); await Promise.all([first, ...rest]); expect(h.put).toHaveBeenCalledTimes(1)
})
it('verification and its share request hold the page write guard until completion', async () => {
  const share = deferred(); const h = setup(); await h.v.load(); h.post.mockImplementation(() => share.promise)
  const first = h.v.verify(h.v.records.value[1]); await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
  const rest = [h.v.verify(h.v.records.value[1]), h.v.arrive(h.v.records.value[0]), h.v.markCritical(h.v.records.value[1])]
  share.resolve({ url: '/share/v114-synthetic' }); await Promise.all([first, ...rest])
  expect(h.put).toHaveBeenCalledTimes(1); expect(h.post).toHaveBeenCalledTimes(1); expect(h.box.ElMessageBox.prompt).not.toHaveBeenCalled()
})
it('a critical prompt from before refresh cannot submit its stale examination', async () => {
  const prompt = deferred(); const h = setup(); await h.v.load(); h.box.ElMessageBox.prompt = vi.fn(() => prompt.promise)
  const first = h.v.markCritical(h.v.records.value[1]); await h.v.load(); prompt.raw({ value: '模拟危急征象' }); await first
  expect(h.put).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('a critical prompt cannot target the previous examination after opening another draft', async () => {
  const prompt = deferred(); const h = setup(); await h.v.load(); h.box.ElMessageBox.prompt = vi.fn(() => prompt.promise)
  const first = h.v.markCritical(h.v.records.value[1]); h.v.openReport(h.v.records.value[0]); h.v.findings.value = '甲保留'
  prompt.raw({ value: '模拟危急征象' }); await first; expect(h.put).not.toHaveBeenCalled(); expect(h.v.findings.value).toBe('甲保留')
})
it('a late report success cannot close or clear another examination draft', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await opened(h)
  const first = h.v.saveReport(); h.v.dialogVisible.value = false; h.v.openReport(h.v.records.value[1]); h.v.findings.value = '乙保留'
  wait.resolve(null); await first
  expect(h.v.dialogVisible.value).toBe(true); expect(h.v.current.value.id).toBe(2); expect(h.v.findings.value).toBe('乙保留'); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('closing and reopening the same examination invalidates the previous report completion', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await opened(h)
  const first = h.v.saveReport(); h.v.dialogVisible.value = false; h.v.openReport(h.v.records.value[0]); h.v.findings.value = '重开草稿'
  wait.resolve(null); await first; expect(h.v.dialogVisible.value).toBe(true); expect(h.v.findings.value).toBe('重开草稿'); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('9944 report rejection closes its invalid original dialog and refreshes source state without success', async () => {
  let rows = [registered, reported]
  const h = setup(vi.fn((path: string) => response(path === '/ris/worklist' ? rows : [])), vi.fn(() => Promise.reject({ bizCode: 9944 })))
  await opened(h); rows = [{ ...registered, order_status: 'CREATED' }, reported]; await settle(h.v.saveReport())
  expect(h.v.dialogVisible.value).toBe(false); expect(h.v.records.value[0].order_status).toBe('CREATED')
  expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.post).not.toHaveBeenCalled()
})
it('a late report rejection refreshes records and preserves another draft', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await opened(h)
  const first = settle(h.v.saveReport()); h.v.dialogVisible.value = false; h.v.openReport(h.v.records.value[1]); h.v.findings.value = '乙保留'
  wait.reject({ bizCode: 9944 }); await first
  expect(h.v.dialogVisible.value).toBe(true); expect(h.v.current.value.id).toBe(2); expect(h.v.findings.value).toBe('乙保留')
  expect(h.get.mock.calls.filter(c => c[0] === '/ris/worklist')).toHaveLength(2)
})
it('ordinary report failure preserves the original draft for correction and retry', async () => {
  const h = setup(undefined, vi.fn(() => Promise.reject({ bizCode: 1005 }))); await opened(h); h.v.findings.value = '待修正'
  await settle(h.v.saveReport()); expect(h.v.dialogVisible.value).toBe(true); expect(h.v.findings.value).toBe('待修正'); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
for (const [action, index, code] of [['arrive', 0, 9944], ['verify', 1, 9945], ['markCritical', 1, 9945]] as const) {
  it(`${action} source rejection ${code} refreshes actual state without success or share`, async () => {
    let rows = [registered, reported]
    const h = setup(vi.fn((path: string) => response(path === '/ris/worklist' ? rows : [])), vi.fn(() => Promise.reject({ bizCode: code }))); await h.v.load()
    h.box.ElMessageBox.prompt = vi.fn(() => Promise.resolve({ value: '模拟危急征象' }))
    rows = [{ ...registered, order_status: 'CREATED' }, { ...reported, order_status: 'CREATED' }]
    await settle(h.v[action](h.v.records.value[index]))
    expect(h.v.records.value[index].order_status).toBe('CREATED'); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
    expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessageBox.alert).not.toHaveBeenCalled()
  })
}
it('self-verification 9947 remains a server rejection with no share or success', async () => {
  const h = setup(undefined, vi.fn(() => Promise.reject({ bizCode: 9947 }))); await h.v.load(); await settle(h.v.verify(h.v.records.value[1]))
  expect(h.put).toHaveBeenCalledExactlyOnceWith('/ris/exams/2/verify'); expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled()
})
it('refresh invalidates a pending verification before it can request or display a share', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await h.v.load()
  const first = h.v.verify(h.v.records.value[1]); await h.v.load(); wait.resolve(null); await first
  expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.box.ElMessageBox.alert).not.toHaveBeenCalled()
})
it('opening another draft suppresses the old verification share and preserves that draft', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await h.v.load()
  const first = h.v.verify(h.v.records.value[1]); h.v.openReport(h.v.records.value[0]); h.v.findings.value = '甲保留'
  wait.resolve(null); await first
  expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.v.dialogVisible.value).toBe(true); expect(h.v.findings.value).toBe('甲保留')
})
it('a late share response cannot display over another examination draft', async () => {
  const share = deferred(); const h = setup(); await h.v.load(); h.post.mockImplementation(() => share.promise)
  const first = h.v.verify(h.v.records.value[1]); await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
  expect(h.post).toHaveBeenCalledOnce(); h.v.openReport(h.v.records.value[0]); h.v.findings.value = '甲保留'
  share.resolve({ url: '/share/v114-synthetic' }); await first
  expect(h.box.ElMessageBox.alert).not.toHaveBeenCalled(); expect(h.v.dialogVisible.value).toBe(true); expect(h.v.findings.value).toBe('甲保留')
})
it('a full refresh clears stale rows and the current modal before its HTTP reads complete', async () => {
  const wait = deferred(); let slow = false
  const h = setup(vi.fn((path: string) => slow ? wait.promise : response(path === '/ris/worklist' ? [registered, reported] : [template]))); await opened(h)
  slow = true; const loading = h.v.load(); expect(h.v.dialogVisible.value).toBe(false); expect(h.v.records.value).toEqual([])
  wait.resolve([]); await loading
})
it('only the latest refresh may replace worklist and templates', async () => {
  const oldRows = deferred(), oldTemplates = deferred(), newRows = deferred(), newTemplates = deferred(); let version = 0
  const h = setup(vi.fn((path: string) => {
    if (path === '/ris/worklist') return (++version === 1 ? oldRows : newRows).promise
    return (version === 1 ? oldTemplates : newTemplates).promise
  }))
  const first = h.v.load(), second = h.v.load(); newRows.resolve([reported]); newTemplates.resolve([template]); await second
  oldRows.resolve([registered]); oldTemplates.resolve([]); await first
  expect(h.v.records.value).toEqual([reported]); expect(h.v.templates.value).toEqual([template])
})
it('opening another examination resets the previous template choice', async () => {
  const h = setup(); await opened(h); h.v.templateId.value = 7; h.v.applyTemplate(); h.v.dialogVisible.value = false; h.v.openReport(h.v.records.value[1])
  expect(h.v.templateId.value).toBe(null); expect(h.v.findings.value).toBe(reported.findings)
})
it('leaving the page suppresses late report side effects and follow-up reads', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await opened(h)
  const first = h.v.saveReport(); h.dispose(); wait.resolve(null); await first
  expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.get.mock.calls.filter(c => c[0] === '/ris/worklist')).toHaveLength(1)
})
it('leaving the page suppresses late verification and share display', async () => {
  const wait = deferred(); const h = setup(undefined, vi.fn(() => wait.promise)); await h.v.load()
  const first = h.v.verify(h.v.records.value[1]); h.dispose(); wait.resolve(null); await first
  expect(h.post).not.toHaveBeenCalled(); expect(h.box.ElMessage.success).not.toHaveBeenCalled(); expect(h.box.ElMessageBox.alert).not.toHaveBeenCalled()
})
it('leaving the page suppresses late worklist, template and PACS reads', async () => {
  const late = deferred(); const h = setup(vi.fn(() => late.promise)); const loading = h.v.load(), pacs = h.v.loadPacs(); h.dispose()
  late.resolve([{ ...registered, configured: true, viewerUrl: 'http://pacs.local/view' }]); await Promise.all([loading, pacs])
  expect(h.v.records.value).toEqual([]); expect(h.v.templates.value).toEqual([]); expect(h.v.pacs.value).toEqual({ configured: false, viewerUrl: '' })
})
