import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { computed, ref, watch } from 'vue'

// Run the complete ordinary script and real composable, including copy-source
// matching and all off/warn/block branches. Only HTTP, storage, DOM and the
// confirmation dependency are replaced. The old one-argument function accepts
// the additional callback at runtime, so its RED is a stale write, not a missing
// helper/import/signature failure.
const source = readFileSync(new URL('../../components/EmrRefDrawer.vue', import.meta.url), 'utf8')
const ordinary = source.match(/<script lang="ts">([\s\S]*?)<\/script>/)![1]
const parsed = ts.createSourceFile('EmrRefDrawer.ts', ordinary, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const actual = parsed.statements.filter(s => !ts.isImportDeclaration(s))
  .map(s => s.getText(parsed)).join('\n').replace(/^export\s+(?=function\s)/gm, '')
const executable = ts.transpileModule(actual + '\nglobalThis.makeGuard = useEmrPasteGuard;', {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
}).outputText

function deferred() {
  let resolve!: () => void, reject!: (reason?: unknown) => void
  const promise = new Promise<void>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

class InputEvent {
  constructor(public type: string, public options: { bubbles?: boolean } = {}) {}
  get bubbles() { return this.options.bubbles ?? false }
}

class TextControl {
  private stored: string
  isConnected = true
  selectionStart = 2
  selectionEnd = 4
  writes = vi.fn()
  onInput: ((value: string) => void) | null = null
  focus = vi.fn()
  setSelectionRange = vi.fn((start: number, end: number) => {
    this.selectionStart = start; this.selectionEnd = end
  })
  dispatchEvent = vi.fn((event: InputEvent) => {
    if (event.type === 'input') this.onInput?.(this.value)
    return true
  })
  constructor(value = 'abcdef') { this.stored = value }
  get value() { return this.stored }
  set value(value: string) { this.writes(value); this.stored = value }
}
class InputControl extends TextControl {}
class TextareaControl extends TextControl {}

const copiedText = '模拟来源患者的临床病历正文'
const sourceKey = 'hip_emr_copy_src'

function setup(withSession = true) {
  const storage = new Map<string, string>()
  const session = { generation: 7, ready: true, signed: false }
  const patient = { patientId: 22 as number | null, patientName: '模拟当前甲' }
  const model = { value: '当前甲病历' }
  const confirmations: ReturnType<typeof deferred>[] = []
  const confirm = vi.fn(() => {
    const pending = deferred(); confirmations.push(pending); return pending.promise
  })
  const messages = { error: vi.fn(), warning: vi.fn() }
  const get = vi.fn(() => Promise.resolve({ data: { data: { mode: 'warn', scopeNote: '仅院内来源' } } }))
  const captureSession = vi.fn(() => {
    const generation = session.generation
    return () => generation === session.generation && session.ready && !session.signed
  })
  const box: Record<string, any> = {
    computed, ref, watch, client: { get }, ElMessageBox: { confirm }, ElMessage: messages,
    localStorage: { getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value) },
    window: { getSelection: () => '' }, HTMLInputElement: InputControl,
    HTMLTextAreaElement: TextareaControl, Event: InputEvent,
  }
  runInNewContext(executable, box)
  const guard = withSession ? box.makeGuard(() => ({ ...patient }), captureSession)
    : box.makeGuard(() => ({ ...patient }))
  function copied(id: number | null = 11, text = copiedText) {
    const before = { ...patient }
    patient.patientId = id; patient.patientName = '模拟来源患者'
    const input = new TextareaControl(text)
    input.selectionStart = 0; input.selectionEnd = text.length
    guard.onCopy({ target: input })
    Object.assign(patient, before)
  }
  copied()
  function event(target: unknown = new TextareaControl(), text = copiedText) {
    if (target instanceof TextControl) target.onInput = value => { model.value = value }
    return { target, clipboardData: { getData: vi.fn((type: string) => type === 'text/plain' ? text : '') },
      preventDefault: vi.fn() }
  }
  async function settle(index = 0, accept = true) {
    expect(confirmations[index]).toBeDefined()
    if (accept) confirmations[index].resolve()
    else confirmations[index].reject('cancel')
    await confirmations[index].promise.catch(() => {})
    await Promise.resolve(); await Promise.resolve()
  }
  return { guard, session, patient, model, storage, confirmations, confirm, messages,
    get, captureSession, copied, event, settle }
}

function untouched(input: TextControl, value = 'abcdef') {
  expect(input.value).toBe(value)
  expect(input.writes).not.toHaveBeenCalled()
  expect(input.dispatchEvent).not.toHaveBeenCalled()
  expect(input.focus).not.toHaveBeenCalled()
  expect(input.setSelectionRange).not.toHaveBeenCalled()
}

describe('actual EmrRefDrawer paste confirmation stays with its captured patient session', () => {
  it('does not write or dispatch into the new patient after the original session changes', async () => {
    const h = setup(), input = new TextareaControl(), event = h.event(input)
    h.guard.onPaste(event)
    expect(event.preventDefault).toHaveBeenCalledOnce(); expect(h.confirm).toHaveBeenCalledOnce()
    h.session.generation++; h.patient.patientId = 33; h.patient.patientName = '模拟新乙'
    h.model.value = '新乙病历保持'
    await h.settle()
    expect(h.model.value).toBe('新乙病历保持'); untouched(input)
  })

  it('does not accept A to B to A as the same session when the original confirmation resolves', async () => {
    const h = setup(), input = new InputControl()
    h.guard.onPaste(h.event(input))
    h.session.generation++; h.patient.patientId = 33
    h.session.generation++; h.patient.patientId = 22
    h.model.value = '甲的新一代病历'
    await h.settle()
    expect(h.model.value).toBe('甲的新一代病历'); untouched(input)
  })

  it('does not write after the captured workspace becomes unready while confirming', async () => {
    const h = setup(), input = new TextareaControl()
    h.guard.onPaste(h.event(input)); h.session.ready = false
    await h.settle()
    expect(h.model.value).toBe('当前甲病历'); untouched(input)
  })

  it('does not write after the captured workspace becomes signed while confirming', async () => {
    const h = setup(), input = new TextareaControl()
    h.guard.onPaste(h.event(input)); h.session.signed = true
    await h.settle()
    expect(h.model.value).toBe('当前甲病历'); untouched(input)
  })

  it('does not update, dispatch, focus or move selection on an unmounted target even with a valid session', async () => {
    const h = setup(), input = new TextareaControl()
    h.guard.onPaste(h.event(input)); input.isConnected = false
    await h.settle()
    expect(h.model.value).toBe('当前甲病历'); untouched(input)
  })

  it('also refuses an unmounted target for an existing one-argument caller', async () => {
    const h = setup(false), input = new InputControl()
    h.guard.onPaste(h.event(input)); input.isConnected = false
    await h.settle()
    expect(h.model.value).toBe('当前甲病历'); untouched(input)
  })

  for (const [label, make] of [['input', () => new InputControl()], ['textarea', () => new TextareaControl()]] as const) {
    it(`keeps active warn confirmation insertion, captured caret and v-model input for ${label}`, async () => {
      const h = setup(), input = make(), event = h.event(input)
      h.guard.onPaste(event)
      expect(event.preventDefault).toHaveBeenCalledOnce(); untouched(input)
      input.selectionStart = 0; input.selectionEnd = 0 // the confirmation stole the selection
      await h.settle()
      expect(input.value).toBe('ab' + copiedText + 'ef')
      expect(h.model.value).toBe(input.value)
      expect(input.writes).toHaveBeenCalledOnce(); expect(input.dispatchEvent).toHaveBeenCalledOnce()
      expect(input.dispatchEvent.mock.calls[0][0]).toMatchObject({ type: 'input', options: { bubbles: true } })
      expect(input.focus).toHaveBeenCalledOnce()
      expect(input.setSelectionRange).toHaveBeenCalledWith(2 + copiedText.length, 2 + copiedText.length)
    })
  }

  it('preserves existing one-argument warn insertion when the target is still connected', async () => {
    const h = setup(false), input = new TextareaControl()
    h.guard.onPaste(h.event(input)); await h.settle()
    expect(input.value).toBe('ab' + copiedText + 'ef'); expect(input.dispatchEvent).toHaveBeenCalledOnce()
  })

  it('leaves the model and control untouched when the clinician cancels warn confirmation', async () => {
    const h = setup(), input = new TextareaControl(), event = h.event(input)
    h.guard.onPaste(event); await h.settle(0, false)
    expect(event.preventDefault).toHaveBeenCalledOnce(); expect(h.model.value).toBe('当前甲病历')
    untouched(input); expect(h.messages.error).not.toHaveBeenCalled()
  })

  it('preserves block: prevents the recognized cross-patient paste and reports its original policy reason', () => {
    const h = setup(), input = new TextareaControl(), event = h.event(input)
    h.guard.mode.value = 'block'; h.guard.onPaste(event)
    expect(event.preventDefault).toHaveBeenCalledOnce(); expect(h.confirm).not.toHaveBeenCalled()
    expect(h.messages.error).toHaveBeenCalledWith(expect.stringContaining('禁止跨患者粘贴'))
    untouched(input)
  })

  it('preserves off: leaves browser-native paste alone even when the optional session would refuse', () => {
    const h = setup(), input = new TextareaControl(), event = h.event(input)
    h.guard.mode.value = 'off'; h.session.ready = false; h.guard.onPaste(event)
    expect(event.preventDefault).not.toHaveBeenCalled(); expect(h.confirm).not.toHaveBeenCalled()
    untouched(input)
  })

  it('preserves same-patient paste under block without confirmation or a manual DOM write', () => {
    const h = setup(), input = new TextareaControl(), event = h.event(input)
    h.copied(22); h.guard.mode.value = 'block'; h.guard.onPaste(event)
    expect(event.preventDefault).not.toHaveBeenCalled(); expect(h.confirm).not.toHaveBeenCalled()
    expect(h.messages.error).not.toHaveBeenCalled(); untouched(input)
  })

  for (const side of ['source', 'target'] as const) {
    it(`preserves the unknown ${side} patient bypass`, () => {
      const h = setup(), input = new TextareaControl(), event = h.event(input)
      if (side === 'source') h.copied(null)
      else h.patient.patientId = null
      h.guard.onPaste(event)
      expect(event.preventDefault).not.toHaveBeenCalled(); expect(h.confirm).not.toHaveBeenCalled(); untouched(input)
    })
  }

  it('preserves external paste without recorded source', () => {
    const h = setup(), input = new TextareaControl(), event = h.event(input)
    h.storage.clear(); h.guard.onPaste(event)
    expect(event.preventDefault).not.toHaveBeenCalled(); expect(h.confirm).not.toHaveBeenCalled(); untouched(input)
  })

  it('preserves external paste when stored source is invalid JSON', () => {
    const h = setup(), input = new TextareaControl(), event = h.event(input)
    h.storage.set(sourceKey, '{broken'); h.guard.onPaste(event)
    expect(event.preventDefault).not.toHaveBeenCalled(); expect(h.confirm).not.toHaveBeenCalled(); untouched(input)
  })

  it('does not treat unrelated external text as the known source', () => {
    const h = setup(), input = new TextareaControl(), event = h.event(input, '外部来源的新文字')
    h.guard.onPaste(event)
    expect(event.preventDefault).not.toHaveBeenCalled(); expect(h.confirm).not.toHaveBeenCalled(); untouched(input)
  })

  it('continues recognizing normalized source text and inserts the unchanged clipboard text', async () => {
    const h = setup(), input = new TextareaControl(), text = '模拟 来源病历 的正文'
    h.copied(11, '模拟\n来源病历   的正文')
    const event = h.event(input, text)
    h.guard.onPaste(event); await h.settle()
    expect(event.preventDefault).toHaveBeenCalledOnce(); expect(input.value).toBe('ab' + text + 'ef')
  })

  it('ignores an empty clipboard without opening confirmation', () => {
    const h = setup(), input = new TextareaControl(), event = h.event(input, '')
    h.guard.onPaste(event)
    expect(event.preventDefault).not.toHaveBeenCalled(); expect(h.confirm).not.toHaveBeenCalled(); untouched(input)
  })

  it('retains the unsupported-target warning instead of attempting to write a non-text element', async () => {
    const h = setup(), event = h.event({ isConnected: true })
    h.guard.onPaste(event); await h.settle()
    expect(event.preventDefault).toHaveBeenCalledOnce()
    expect(h.messages.warning).toHaveBeenCalledWith(expect.stringContaining('不支持自动写入'))
    expect(h.model.value).toBe('当前甲病历')
  })

  it('uses the real copy handler to record patient identity and selected text', () => {
    const h = setup(), recorded = JSON.parse(h.storage.get(sourceKey)!)
    expect(recorded).toMatchObject({ patientId: 11, patientName: '模拟来源患者', text: copiedText })
    expect(typeof recorded.at).toBe('number')
  })

  it('keeps loading the existing copy-policy endpoint and its scope note', async () => {
    const h = setup()
    h.get.mockResolvedValueOnce({ data: { data: { mode: 'block', scopeNote: '仅院内来源' } } })
    await h.guard.loadPolicy()
    expect(h.get).toHaveBeenCalledWith('/outpatient/emr-ref/copy-policy')
    expect(h.guard.mode.value).toBe('block'); expect(h.guard.scopeNote.value).toBe('仅院内来源')
  })

  it('keeps warn when copy-policy transport fails', async () => {
    const h = setup()
    h.get.mockRejectedValueOnce(new Error('offline'))
    await h.guard.loadPolicy()
    expect(h.guard.mode.value).toBe('warn'); expect(h.messages.error).not.toHaveBeenCalled()
  })
})
