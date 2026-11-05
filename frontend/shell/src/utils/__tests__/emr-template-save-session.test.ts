import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

// Execute the compiled SFC template and its handlers against controllable API
// promises. Element controls use a lightweight renderer without browser setup.
type Node = { tag: string; text: string; props: Record<string, any>; children: Node[]; parent: Node | null }
type Wire = {
  method: 'GET' | 'POST' | 'PUT'
  url: string
  params?: unknown
  body?: unknown
  pending: boolean
  resolve: (data: unknown) => void
  reject: (error: Error) => void
}
const makeNode = (tag: string, text = ''): Node => ({ tag, text, props: {}, children: [], parent: null })
const children = (root: Node): Node[] => [root, ...root.children.flatMap(children)]
const text = (node: Node): string => node.text + node.children.map(text).join('')
const settled = () => new Promise<void>(resolve => setTimeout(resolve, 0))
const cleanups: Array<() => Promise<void>> = []
afterEach(async () => {
  for (const cleanup of cleanups.splice(0)) await cleanup()
})

const departments = [{ id: 7, name: '模拟科室甲' }, { id: 8, name: '模拟科室乙' }]
const actor = { userId: 3, deptId: 7, deptName: '模拟科室甲', admin: false, grantableScopes: ['DEPT', 'PERSONAL'] }
const a = { id: 71, name: '模拟模板甲', content: '甲正文', template_type: 'EMR', scope: 'DEPT', scopeName: '科室',
  dept_id: 7, dept_name: '模拟科室甲', owner_id: 3, owner_name: '模拟维护人', record_type: 'OUTP',
  is_default: false, enabled: true, editable: true, grant_count: 1 }
const b = { ...a, id: 72, name: '模拟模板乙', content: '乙正文', template_type: 'RIS' }
const visible = '/emr-templates/visible'
const source = readFileSync(new URL('../../views/quality/EmrTemplateView.vue', import.meta.url), 'utf8')
const { descriptor, errors: parseErrors } = parse(source, { filename: 'EmrTemplateView.vue' })
if (parseErrors.length) throw new Error('Unable to parse EmrTemplateView.vue')
const compiled = compileScript(descriptor, { id: 'v163-template-save-dialog', inlineTemplate: true })
const code = ts.transpileModule(compiled.content, { compilerOptions: {
  target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
} }).outputText

async function mount() {
  const writes: Wire[] = []
  const lists: Wire[] = []
  const errors: unknown[] = []
  const messages = { success: vi.fn(), warning: vi.fn() }
  const response = (data: unknown) => ({ data: { data } })
  let holdLists = false
  let holdClose = false
  let finishNativeClose: (() => void) | undefined
  function pending(ledger: Wire[], method: Wire['method'], url: string, details: { body?: unknown; params?: unknown }) {
    return new Promise((resolve, reject) => {
      const request: Wire = {
        method, url, ...details, pending: true,
        resolve(data) { request.pending = false; resolve(response(data)) },
        reject(error) { request.pending = false; reject(error) },
      }
      ledger.push(request)
      if (method === 'GET' && !holdLists) request.resolve([a, b])
    })
  }
  const client = {
    get(url: string, config: { params?: unknown } = {}) {
      if (url === '/system/depts') return Promise.resolve(response(departments))
      if (url === '/emr-templates/actor') return Promise.resolve(response(actor))
      if (url === visible) return pending(lists, 'GET', url, { params: config.params })
      throw new Error('Unexpected GET ' + url)
    },
    post: (url: string, body: unknown) => pending(writes, 'POST', url, { body: structuredClone(body) }),
    put: (url: string, body: unknown) => pending(writes, 'PUT', url, { body: structuredClone(body) }),
  }
  const exports: Record<string, any> = {}
  runInNewContext(code, { exports, console, require(name: string) {
    if (name === 'vue') return Vue
    if (name === '../../api/client') return { default: client }
    if (name === 'element-plus') return { ElMessage: messages, ElMessageBox: {} }
    throw new Error('Unexpected compiled import ' + name)
  } })
  function remove(node: Node) {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
    node.parent = null
  }
  const renderer = Vue.createRenderer<Node, Node>({
    createElement: makeNode, createText: value => makeNode('#text', value),
    createComment: value => makeNode('#comment', value),
    setText: (node, value) => { node.text = value },
    setElementText: (node, value) => { node.text = value; node.children = [] },
    parentNode: node => node.parent,
    nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
    patchProp: (node, key, _previous, value) => { node.props[key] = value },
    insert(node, parent, anchor = null) {
      remove(node)
      parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, node)
      node.parent = parent
    },
    remove,
  })
  const app = renderer.createApp(exports.default)
  for (const name of ['ElCard', 'ElTag', 'ElEmpty', 'ElCheckbox']) {
    app.component(name, Vue.defineComponent({ name, setup: (_, { slots }) => () => Vue.h('div', slots.default?.()) }))
  }
  app.component('ElDialog', Vue.defineComponent({
    props: ['modelValue', 'title', 'beforeClose'], emits: ['update:modelValue', 'close', 'closed'],
    setup: (props, { slots, emit }) => () => props.modelValue ? Vue.h('section', { title: props.title }, [
      Vue.h('button', { onClick() {
        const done = () => {
          emit('close')
          const afterLeave = () => { emit('closed'); emit('update:modelValue', false) }
          if (holdClose) finishNativeClose = afterLeave
          else afterLeave()
        }
        if (typeof props.beforeClose === 'function') props.beforeClose(done)
        else done()
      } }, '对话框关闭'),
      slots.default?.(), slots.footer?.(),
    ]) : null,
  }))
  app.component('ElForm', Vue.defineComponent({
    props: ['disabled'],
    setup(props, { slots }) {
      Vue.provide('form-disabled', Vue.computed(() => Boolean(props.disabled)))
      return () => Vue.h('form', slots.default?.())
    },
  }))
  app.component('ElFormItem', Vue.defineComponent({
    props: ['label'], setup: (props, { slots }) => () => Vue.h('label', { label: props.label }, slots.default?.()),
  }))
  app.component('ElButton', Vue.defineComponent({
    props: ['disabled', 'loading'], emits: ['click'],
    setup: (props, { slots, emit }) => () => Vue.h('button', {
      disabled: Boolean(props.disabled || props.loading), onClick: () => emit('click'),
    }, slots.default?.()),
  }))
  for (const [name, tag] of [['ElInput', 'input'], ['ElSelect', 'select']]) {
    app.component(name, Vue.defineComponent({
      props: ['modelValue', 'placeholder', 'disabled'], emits: ['update:modelValue', 'change'],
      setup(props, { slots, emit }) {
        const disabled = Vue.inject('form-disabled', Vue.ref(false))
        return () => Vue.h(tag, { value: props.modelValue, placeholder: props.placeholder,
          disabled: Boolean(props.disabled || disabled.value),
          onChoose(value: unknown) { emit('update:modelValue', value); emit('change', value) },
        }, slots.default?.())
      },
    }))
  }
  app.component('ElOption', Vue.defineComponent({
    props: ['value', 'label', 'disabled'],
    setup: props => () => Vue.h('option', { value: props.value, disabled: props.disabled }, String(props.label)),
  }))
  app.component('ElTable', Vue.defineComponent({
    props: ['data'],
    setup(props, { slots }) {
      Vue.provide('rows', () => props.data ?? [])
      return () => Vue.h('table', slots.default?.())
    },
  }))
  app.component('ElTableColumn', Vue.defineComponent({
    props: ['prop', 'label'],
    setup(props, { slots }) {
      const rows = Vue.inject<() => Array<Record<string, any>>>('rows')!
      return () => Vue.h('tbody', rows().map(row => Vue.h('tr', { row: row.id },
        slots.default ? slots.default({ row }) : String(row[props.prop] ?? ''))))
    },
  }))
  app.config.errorHandler = error => { errors.push(error) }
  const root = makeNode('root')
  app.mount(root)
  cleanups.push(async () => {
    holdLists = false
    for (const request of writes.filter(request => request.pending)) request.resolve(null)
    for (const request of lists.filter(request => request.pending)) request.resolve([a, b])
    await settled()
    app.unmount()
  })
  await settled()

  const dialog = () => children(root).find(node => node.tag === 'section')
  const find = (predicate: (node: Node) => boolean, within = root) => {
    const matches = children(within).filter(predicate)
    expect(matches, 'One rendered control must match').toHaveLength(1)
    return matches[0]
  }
  function rowOf(node: Node): unknown {
    let parent = node.parent
    while (parent && parent.tag !== 'tr') parent = parent.parent
    return parent?.props.row
  }
  const button = (label: string, row?: number) => find(node =>
    node.tag === 'button' && text(node).trim() === label && (row === undefined || rowOf(node) === row))
  const field = (label: string) => {
    const current = dialog()
    expect(current, 'The edit dialog must be visible').toBeDefined()
    const wrapper = find(node => node.tag === 'label' && node.props.label === label, current)
    return find(node => node.tag === 'input' || node.tag === 'select', wrapper)
  }
  const filter = (placeholder: string) => find(node => node.tag === 'select' && node.props.placeholder === placeholder)
  async function click(node: Node) {
    expect(node.props.disabled, 'The rendered button must be enabled').not.toBe(true)
    node.props.onClick()
    await settled()
  }
  async function choose(node: Node, value: unknown) {
    expect(node.props.disabled, 'The rendered field must be enabled').not.toBe(true)
    node.props.onChoose(value)
    await settled()
  }
  return { writes, lists, errors, messages, dialog, button, field, filter, click, choose,
    holdLists() { holdLists = true },
    holdClose() { holdClose = true },
    async finishClose() {
      expect(finishNativeClose, 'Native dialog began its close transition').toBeDefined()
      finishNativeClose?.()
      await settled()
    },
    async resolve(request: Wire, data: unknown = null) {
      expect(request.pending).toBe(true)
      request.resolve(data)
      await settled()
    },
    async reject(request: Wire, error: Error) {
      expect(request.pending).toBe(true)
      request.reject(error)
      await settled()
    },
    tableText: () => children(root).filter(node => node.tag === 'table').map(text).join(''),
  }
}

describe('ordinary EMR template save dialog sessions', () => {
  it.each([
    { open: '编辑', row: a.id, close: '取消', title: '编辑 #71《模拟模板甲》' },
    { open: '复制', row: b.id, close: '对话框关闭', title: '复制自 #72《模拟模板乙》' },
  ])('retains a new $open draft after the previous edit succeeds', async next => {
    const h = await mount()
    await h.choose(h.filter('全部作用范围'), 'DEPT')
    await h.choose(h.filter('全部科室'), 7)
    await h.click(h.button('编辑', a.id))
    await h.choose(h.field('作用范围'), 'PERSONAL')
    await h.click(h.button('保存'))
    const old = h.writes[0]
    expect(old).toMatchObject({ method: 'PUT', url: '/emr-templates/71', body: {
      name: a.name, content: a.content, templateType: 'EMR', scope: 'PERSONAL', deptId: null, recordType: 'OUTP',
    } })
    await h.click(h.button(next.close))
    await h.click(h.button(next.open, next.row))
    await h.choose(h.field('模板名称'), '还未提交的新草稿')
    const listsBefore = h.lists.length

    await h.resolve(old)

    expect(h.dialog()?.props.title).toBe(next.title)
    expect(h.field('模板名称').props.value).toBe('还未提交的新草稿')
    expect(h.filter('全部类型').props.value).toBe('EMR')
    expect(h.filter('全部作用范围').props.value).toBe('DEPT')
    expect(h.filter('全部科室').props.value).toBe(7)
    expect(h.messages.success).not.toHaveBeenCalled()
    expect(h.lists).toHaveLength(listsBefore)
    expect(h.writes).toHaveLength(1)
  })

  it.each(['success', 'failure'] as const)('preserves the new draft when the previous write ends in %s', async outcome => {
    const h = await mount()
    await h.click(h.button('编辑', a.id))
    await h.click(h.button('保存'))
    await h.click(h.button('取消'))
    await h.click(h.button('新增模板'))
    await h.choose(h.field('模板名称'), '本次新模板')
    await h.choose(h.field('模板正文'), '本次新正文')
    expect(h.button('保存').props.disabled).toBe(true)
    expect(h.writes).toHaveLength(1)
    const old = h.writes[0]
    const error = new Error('模拟旧保存请求失败')
    if (outcome === 'success') await h.resolve(old)
    else await h.reject(old, error)

    expect(h.dialog()?.props.title).toBe('新增模板')
    expect(h.field('模板名称').props.value).toBe('本次新模板')
    expect(h.button('保存').props.disabled).toBe(false)
    expect(h.messages.success).not.toHaveBeenCalled()
    expect(h.lists).toHaveLength(1)
    expect(h.errors).toEqual(outcome === 'failure' ? [error] : [])
    await h.click(h.button('保存'))
    expect(h.writes).toHaveLength(2)
    const current = h.writes[1]
    expect(current).toMatchObject({ method: 'POST', url: '/emr-templates/scoped', body: {
      name: '本次新模板', content: '本次新正文', templateType: 'EMR', scope: 'DEPT', deptId: 7, recordType: null,
    } })
    await h.resolve(current, 73)
    expect(h.dialog()).toBeUndefined()
    expect(h.messages.success).toHaveBeenCalledExactlyOnceWith('模板已保存（已自动授权给所属科室/创建人）')
    expect(h.lists).toHaveLength(2)
  })

  it('ignores the old success as soon as a native close starts', async () => {
    const h = await mount()
    await h.click(h.button('编辑', a.id))
    await h.click(h.button('保存'))
    h.holdClose()
    await h.click(h.button('对话框关闭'))

    await h.resolve(h.writes[0])

    expect(h.messages.success).not.toHaveBeenCalled()
    expect(h.lists).toHaveLength(1)
    await h.finishClose()
    expect(h.dialog()).toBeUndefined()
  })

  it('a delayed save refresh does not replace rows from a later filter request', async () => {
    const h = await mount()
    await h.click(h.button('编辑', a.id))
    await h.click(h.button('保存'))
    h.holdLists()
    await h.resolve(h.writes[0])
    const saveRefresh = h.lists[1]
    expect(saveRefresh).toMatchObject({ url: visible, params: { includeDisabled: false, type: 'EMR' }, pending: true })
    await h.choose(h.filter('全部类型'), 'RIS')
    const filterRefresh = h.lists[2]
    expect(filterRefresh).toMatchObject({ url: visible, params: { includeDisabled: false, type: 'RIS' }, pending: true })
    await h.resolve(filterRefresh, [b])
    await h.resolve(saveRefresh, [a])

    expect(h.filter('全部类型').props.value).toBe('RIS')
    expect(h.tableText()).toContain(b.name)
    expect(h.tableText()).not.toContain(a.name)
  })

  it('an earlier list response cannot update the table or unlock a newer save', async () => {
    const h = await mount()
    await h.click(h.button('编辑', a.id))
    await h.click(h.button('保存'))
    h.holdLists()
    await h.resolve(h.writes[0])
    const previousRefresh = h.lists[1]
    expect(previousRefresh.pending).toBe(true)
    await h.click(h.button('编辑', b.id))
    await h.choose(h.field('模板名称'), '乙的新草稿')
    await h.click(h.button('保存'))
    expect(h.writes[1]).toMatchObject({ method: 'PUT', url: '/emr-templates/72', pending: true })

    await h.resolve(previousRefresh, [{ ...a, name: '不应显示的旧列表' }])

    expect(h.tableText()).toContain(b.name)
    expect(h.tableText()).not.toContain('不应显示的旧列表')
    expect(h.field('模板名称').props.value).toBe('乙的新草稿')
    expect(h.button('保存').props.disabled).toBe(true)
    const error = new Error('模拟第二次保存失败')
    await h.reject(h.writes[1], error)
    expect(h.errors).toEqual([error])
    expect(h.button('保存').props.disabled).toBe(false)
  })

  it('handles two save events before the button re-renders with one write', async () => {
    const h = await mount()
    await h.click(h.button('编辑', a.id))
    const save = h.button('保存')
    save.props.onClick()
    save.props.onClick()
    await settled()

    expect(h.writes).toHaveLength(1)
    expect(h.button('保存').props.disabled).toBe(true)
    expect(h.field('模板名称').props.disabled).toBe(true)
    await h.resolve(h.writes[0])
    expect(h.messages.success).toHaveBeenCalledExactlyOnceWith('模板已更新')
  })

  it.each([
    { open: '编辑', row: a.id, scope: 'PERSONAL', deptId: null, name: a.name, templateType: 'EMR',
      path: '/emr-templates/71', method: 'PUT',
      message: '模板已更新；作用范围已变，原有授权已清空并按新范围重新自动授权' },
    { open: '编辑', row: a.id, scope: 'DEPT', deptId: null, name: a.name, templateType: 'EMR',
      path: '/emr-templates/71', method: 'PUT', message: '模板已更新' },
    { open: '复制', row: b.id, scope: 'DEPT', deptId: 7, name: b.name + ' 副本', templateType: 'RIS',
      path: '/emr-templates/scoped', method: 'POST', message: '模板已保存（已自动授权给所属科室/创建人）' },
  ])('$open keeps its request contract and successful save feedback ($scope, $deptId)', async expected => {
    const h = await mount()
    await h.choose(h.filter('全部作用范围'), 'DEPT')
    await h.choose(h.filter('全部科室'), 7)
    await h.click(h.button(expected.open, expected.row))
    await h.choose(h.field('模板名称'), '  ' + expected.name + '  ')
    await h.choose(h.field('模板正文'), '  模板正文保留空白  ')
    await h.choose(h.field('作用范围'), expected.scope)
    if (expected.scope === 'DEPT') await h.choose(h.field('所属科室'), expected.deptId)
    await h.click(h.button('保存'))

    expect(h.writes).toHaveLength(1)
    expect(h.writes[0]).toMatchObject({ method: expected.method, url: expected.path })
    expect(h.writes[0].body).toEqual({ name: expected.name, content: '  模板正文保留空白  ',
      templateType: expected.templateType, scope: expected.scope, deptId: expected.deptId, recordType: 'OUTP' })
    await h.resolve(h.writes[0], expected.method === 'POST' ? 73 : null)
    expect(h.dialog()).toBeUndefined()
    expect(h.messages.success).toHaveBeenCalledExactlyOnceWith(expected.message)
    expect(h.filter('全部类型').props.value).toBe(expected.templateType)
    expect(h.filter('全部作用范围').props.value).toBeNull()
    expect(h.filter('全部科室').props.value).toBeNull()
    expect(h.lists).toHaveLength(3)
    expect(h.lists.at(-1)?.params).toEqual({ includeDisabled: false, type: expected.templateType })
  })

  it('retains the current draft after rejection and accepts one explicit retry', async () => {
    const h = await mount()
    await h.click(h.button('编辑', a.id))
    await h.choose(h.field('模板名称'), '提交失败时保留的草稿')
    await h.click(h.button('保存'))
    const error = new Error('模拟当前保存失败')
    await h.reject(h.writes[0], error)

    expect(h.errors).toEqual([error])
    expect(h.dialog()?.props.title).toBe('编辑 #71《模拟模板甲》')
    expect(h.field('模板名称').props.value).toBe('提交失败时保留的草稿')
    expect(h.button('保存').props.disabled).toBe(false)
    expect(h.messages.success).not.toHaveBeenCalled()
    expect(h.lists).toHaveLength(1)
    expect(h.writes).toHaveLength(1)
    await h.click(h.button('保存'))
    expect(h.writes[1].body).toEqual(h.writes[0].body)
    await h.resolve(h.writes[1])
    expect(h.dialog()).toBeUndefined()
    expect(h.messages.success).toHaveBeenCalledExactlyOnceWith('模板已更新')
  })
})
