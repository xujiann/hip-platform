import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as Vue from 'vue'
import axios from 'axios'
import * as versionLabels from '../../views/outpatient/emr-version/types'
import { versionListCopySource } from '../emr-copy-source'

const cleanups: Array<() => Promise<void>> = []
afterEach(async () => {
  for (const cleanup of cleanups.splice(0)) await cleanup()
})

function executable(source: string, filename: string) {
  const ast = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  return ast.statements.filter(statement => !ts.isImportDeclaration(statement))
    .map(statement => statement.getText(ast)).join('\n')
}

type Wire = {
  config: any
  settled: boolean
  reply: (body: Blob, status?: number) => void
}

async function setup() {
  const storage = new Map([['hip_token', 'v166-synthetic-token']])
  const localStorage = {
    getItem: (key: string) => storage.get(key) ?? null,
    removeItem: (key: string) => storage.delete(key),
    setItem: (key: string, value: string) => storage.set(key, value),
  }
  const router = { push: vi.fn() }
  const ElMessage = Object.assign(vi.fn(), { warning: vi.fn(), error: vi.fn(), success: vi.fn(), info: vi.fn() })
  const clientBox: Record<string, any> = { axios, localStorage, router, ElMessage, Blob, exports: {} }
  const clientSource = readFileSync(new URL('../../api/client.ts', import.meta.url), 'utf8')
  runInNewContext(ts.transpileModule(executable(clientSource, 'client.ts'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, clientBox)
  const client = clientBox.exports.default
  const wires: Wire[] = []
  const calls: Array<{ method: string; url: string }> = []
  client.defaults.adapter = (config: any) => new Promise((resolve, reject) => {
    calls.push({ method: config.method, url: config.url })
    if (String(config.url).endsWith('/ofd-preview')) {
      const wire: Wire = {
        config, settled: false,
        reply(body, status = 200) {
          expect(wire.settled).toBe(false)
          wire.settled = true
          const response = { config, status, statusText: String(status),
            headers: { 'content-type': body.type }, data: body }
          if (status >= 400) {
            reject(new axios.AxiosError('HTTP ' + status, 'ERR_BAD_REQUEST', config, undefined, response))
          } else resolve(response)
        },
      }
      wires.push(wire)
      return
    }
    const detail = String(config.url).match(/^\/emr\/versions\/(INP|OUTP)\/(\d+)\/versions\/(\d+)$/)
    if (detail) {
      const emrType = detail[1], emrId = Number(detail[2]), versionNo = Number(detail[3])
      resolve({ config, status: 200, headers: {},
        data: { code: 0, message: 'success', data: { emrType, emrId, versionNo, versionId: emrId * 100 + versionNo,
          source: 'MANUAL', contentLen: 10, contentHash: 'a'.repeat(64), savedBy: 7, savedByName: '模拟医师',
          savedAt: '2026-11-01T00:00:00Z', savedOn: '2026-11-01', label: 'v' + versionNo,
          content: JSON.stringify({ content: '模拟历史病历' + emrId }), fields: {
            content: { label: '病历正文', value: '模拟历史病历' + emrId },
          } } } })
      return
    }
    throw new Error('Unconfigured actual request ' + config.method + ' ' + config.url)
  })
  type Anchor = { href: string; download: string; click: ReturnType<typeof vi.fn>; remove: ReturnType<typeof vi.fn> }
  const anchors: Anchor[] = []
  const urlApi = { createObjectURL: vi.fn(() => 'blob:v166-preview'), revokeObjectURL: vi.fn() }
  const document = {
    createElement(tag: string) {
      expect(tag).toBe('a')
      const anchor: Anchor = { href: '', download: '', click: vi.fn(), remove: vi.fn() }
      anchors.push(anchor)
      return anchor
    },
    body: { appendChild: vi.fn(), removeChild: vi.fn() },
  }
  const unmounts: Array<() => void> = []
  const route = Vue.reactive({ query: {} as Record<string, string> })
  const scope = Vue.effectScope()
  const source = readFileSync(new URL('../../views/outpatient/emr-version/EmrVersionView.vue', import.meta.url), 'utf8')
  const body = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const box: Record<string, any> = { ...Vue, ...versionLabels, versionListCopySource, client,
    ElMessage, useRoute: () => route, useEmrPasteGuard: () => ({ onCopy: vi.fn() }),
    onMounted: vi.fn(), onUnmounted: (callback: () => void) => unmounts.push(callback),
    URL: urlApi, document, window: { URL: urlApi, document }, Blob, console }
  scope.run(() => runInNewContext(ts.transpileModule(executable(body, 'EmrVersionView.ts') + `
globalThis.view = { applied, emrType, emrId, drawer, detail, openVersion, load,
  downloadOfdPreview: typeof downloadOfdPreview === 'function' ? downloadOfdPreview : undefined };
`, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, box))
  const view = box.view
  const results: Array<Promise<unknown>> = []
  async function open(id = 41, version = 2, type = 'INP') {
    view.applied.value = { emrType: type, emrId: id }
    view.emrType.value = type
    view.emrId.value = id
    await view.openVersion(version)
    await Vue.nextTick()
    expect(view.detail.value.emrId).toBe(id)
    expect(view.detail.value.versionNo).toBe(version)
  }
  function download() {
    expect(typeof view.downloadOfdPreview).toBe('function')
    const promise = Promise.resolve(view.downloadOfdPreview()) as Promise<unknown>
    results.push(promise)
    void promise.catch(() => undefined)
    return promise
  }
  async function begin() {
    const previous = wires.length
    const result = download()
    await vi.waitFor(() => expect(wires.length).toBe(previous + 1), { interval: 1 })
    return { wire: wires[previous]!, result }
  }
  cleanups.push(async () => {
    try {
      for (const wire of wires.filter(item => !item.settled)) {
        wire.reply(new Blob([new Uint8Array([80, 75, 3, 4])], { type: 'application/ofd' }))
      }
      await Promise.allSettled(results)
    } finally {
      unmounts.forEach(callback => callback())
      scope.stop()
    }
  })
  return { view, wires, calls, open, begin, download, anchors, urlApi, ElMessage, router, storage,
    unmount: () => unmounts.forEach(callback => callback()) }
}

type Harness = Awaited<ReturnType<typeof setup>>
function ofd() {
  return new Blob([new Uint8Array([80, 75, 3, 4])], { type: 'application/ofd' })
}
function noDownload(harness: Harness) {
  expect(harness.urlApi.createObjectURL).not.toHaveBeenCalled()
  expect(harness.anchors.every(anchor => anchor.click.mock.calls.length === 0)).toBe(true)
}

describe('v166 actual historical version OFD download consumer', () => {
  it('uses the viewed explicit INP snapshot and releases the generated URL', async () => {
    const h = await setup()
    await h.open()
    const { wire, result } = await h.begin()
    expect(wire.config.url).toBe('/emr/versions/INP/41/versions/2/ofd-preview')
    expect(wire.config.responseType).toBe('blob')
    expect(wire.config.timeout).toBe(180000)
    expect(String(wire.config.headers.Authorization)).toBe('Bearer v166-synthetic-token')
    wire.reply(ofd())
    await result
    expect(h.anchors).toHaveLength(1)
    expect(h.anchors[0]!.download).toBe('emr-INP-41-v2-preview.ofd')
    expect(h.anchors[0]!.href).toBe('blob:v166-preview')
    expect(h.anchors[0]!.click).toHaveBeenCalledTimes(1)
    expect(h.urlApi.revokeObjectURL).toHaveBeenCalledWith('blob:v166-preview')
    expect(h.calls.every(call => call.method === 'get')).toBe(true)
  })

  it('keeps the viewed snapshot when input fields are edited without a new query', async () => {
    const h = await setup()
    await h.open()
    h.view.emrType.value = 'OUTP'
    h.view.emrId.value = 999
    const { wire, result } = await h.begin()
    expect(wire.config.url).toBe('/emr/versions/INP/41/versions/2/ofd-preview')
    wire.reply(ofd())
    await result
    expect(h.anchors[0]!.download).toBe('emr-INP-41-v2-preview.ofd')
  })

  it('suppresses a second click while one preview is being generated', async () => {
    const h = await setup()
    await h.open()
    const { wire, result } = await h.begin()
    const second = h.download()
    await Vue.nextTick()
    expect(h.wires).toHaveLength(1)
    wire.reply(ofd())
    await Promise.allSettled([result, second])
    expect(h.anchors).toHaveLength(1)
    expect(h.anchors[0]!.click).toHaveBeenCalledTimes(1)
  })

  for (const transition of ['close', 'otherRecord', 'otherVersion', 'closeAndReopen', 'unmount'] as const) {
    it('does not download an old binary after ' + transition, async () => {
      const h = await setup()
      await h.open()
      const { wire, result } = await h.begin()
      if (transition === 'close' || transition === 'closeAndReopen') {
        h.view.drawer.value = false
        await Vue.nextTick()
      }
      if (transition === 'otherRecord') await h.open(52, 3)
      if (transition === 'otherVersion') await h.open(41, 3)
      if (transition === 'closeAndReopen') await h.open(41, 2)
      if (transition === 'unmount') h.unmount()
      wire.reply(ofd())
      await Promise.allSettled([result])
      noDownload(h)
    })
  }

  it('does not treat outpatient details as an inpatient preview', async () => {
    const h = await setup()
    await h.open(41, 2, 'OUTP')
    await Promise.allSettled([h.download()])
    expect(h.wires).toHaveLength(0)
    noDownload(h)
  })

  it('does not save a business error JSON Blob as an OFD file', async () => {
    const h = await setup()
    await h.open()
    const { wire, result } = await h.begin()
    wire.reply(new Blob([JSON.stringify({ code: 5708, message: '源版本校验失败', data: null })],
      { type: 'application/json' }))
    await Promise.allSettled([result])
    noDownload(h)
    expect(h.ElMessage.error).toHaveBeenCalledWith('源版本校验失败')
    expect(h.storage.get('hip_token')).toBe('v166-synthetic-token')
    expect(h.router.push).not.toHaveBeenCalled()
  })

  it('does not save a wrong binary media type with an OFD filename', async () => {
    const h = await setup()
    await h.open()
    const { wire, result } = await h.begin()
    wire.reply(new Blob(['upstream error page'], { type: 'text/html' }))
    await Promise.allSettled([result])
    noDownload(h)
  })

  it('does not save corrupt bytes even when the media type says OFD', async () => {
    const h = await setup()
    await h.open()
    const { wire, result } = await h.begin()
    wire.reply(new Blob(['not a ZIP document'], { type: 'application/ofd' }))
    await Promise.allSettled([result])
    noDownload(h)
    expect(h.ElMessage.error).toHaveBeenCalled()
  })

  it('rechecks the detail session after asynchronous prefix reading', async () => {
    const h = await setup()
    await h.open()
    let finishPrefix!: (bytes: ArrayBuffer) => void
    const pendingPrefix = new Promise<ArrayBuffer>(resolve => { finishPrefix = resolve })
    let prefixStarted = false
    class PrefixBlob extends Blob {
      override arrayBuffer(): Promise<ArrayBuffer> {
        prefixStarted = true
        return pendingPrefix
      }
    }
    class DelayedBlob extends Blob {
      override slice(): Blob {
        return new PrefixBlob([new Uint8Array([80, 75, 3, 4])])
      }
    }
    const { wire, result } = await h.begin()
    wire.reply(new DelayedBlob([new Uint8Array([80, 75, 3, 4])], { type: 'application/ofd' }))
    await vi.waitFor(() => expect(prefixStarted).toBe(true), { interval: 1 })
    h.view.drawer.value = false
    await Vue.nextTick()
    finishPrefix(new Uint8Array([80, 75, 3, 4]).buffer)
    await Promise.allSettled([result])
    noDownload(h)
  })

  it('keeps authentication and the viewed detail when export is forbidden', async () => {
    const h = await setup()
    await h.open()
    const { wire, result } = await h.begin()
    wire.reply(new Blob([JSON.stringify({ code: 1005, message: '当前岗位已变化', data: null })],
      { type: 'application/json' }), 403)
    await Promise.allSettled([result])
    noDownload(h)
    expect(h.storage.get('hip_token')).toBe('v166-synthetic-token')
    expect(h.router.push).not.toHaveBeenCalled()
    expect(h.view.detail.value.emrId).toBe(41)
    expect(h.view.detail.value.versionNo).toBe(2)
    expect(h.ElMessage.error).toHaveBeenCalledWith('无该功能权限，请联系管理员分配角色')
  })
})
