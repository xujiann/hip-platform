import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'

// Mount the complete compiled SFC, including its real template event bindings,
// lifecycle and watchers. Element controls are renderer stubs; the host tree is
// intentionally DOM independent, so no browser or new testing dependency is needed.
type Node = { kind: string; props: Record<string, any>; text: string; children: Node[]; parent: Node | null }
type Kind = 'DEPT' | 'USER'
type Wire = { method: string; url: string; params: any; body: any; done: boolean;
  resolve: (value: unknown) => void; reject: (reason: unknown) => void }
const makeNode = (kind: string, text = ''): Node => ({ kind, text, props: {}, children: [], parent: null })
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const turn = async () => { await Promise.resolve(); await Vue.nextTick(); await new Promise<void>(resolve => setTimeout(resolve, 0)); await Vue.nextTick() }
const DEPTS = [{ id: 17, name: '模拟甲科室' }, { id: 19, name: '模拟乙科室' }]
const USERS = [{ id: 17, name: '模拟甲护士', dept_name: '模拟外科' }, { id: 23, name: '模拟乙医生', dept_name: '模拟内科' }]
const A = { id: 71, name: '模拟模板甲', content: '甲正文', template_type: 'EMR', scope: 'DEPT', scopeName: '科室',
  dept_id: 7, dept_name: '模拟内科', owner_id: 3, owner_name: '模拟创建人', record_type: 'OUTP', is_default: false,
  enabled: true, editable: true, grant_count: 1 }
const B = { ...A, id: 72, name: '模拟模板乙', content: '乙正文', grant_count: 2 }
const grant = (template: number, id = 801) => ({ id, template_id: template, grantee_type: 'USER', grantee_id: 23,
  grantee_name: template === A.id ? '甲模板原有人员' : '乙模板原有人员', granted_by_name: '模拟创建人' })
const jobs: Array<() => Promise<void>> = []

function mount() {
  let unmounted = false
  const errors: unknown[] = [], messages = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }
  const requests: Wire[] = [], initial = [copy(A), copy(B)]
  const request = (method: string, url: string, body: unknown = undefined, config: any = {}) => {
    if (method === 'get' && url === '/system/depts') return Promise.resolve({ data: { data: copy(DEPTS) } })
    if (method === 'get' && url === '/emr-templates/actor') return Promise.resolve({ data: { data: {
      userId: 3, deptId: 7, deptName: '模拟内科', admin: false, grantableScopes: ['DEPT', 'PERSONAL'],
    } } })
    if (method === 'get' && url === '/emr-templates/visible') return Promise.resolve({ data: { data: copy(initial) } })
    return new Promise((resolve, reject) => {
      requests.push({ method, url, params: copy(config.params ?? {}), body: body === undefined ? undefined : copy(body), done: false,
        resolve(value) { this.done = true; resolve({ data: { data: copy(value) } }) },
        reject(reason) { this.done = true; reject(reason) } })
    })
  }
  const client = { get: (url: string, config?: any) => request('get', url, undefined, config),
    post: (url: string, body: unknown) => request('post', url, body),
    delete: (url: string) => request('delete', url), put: (url: string, body: unknown, config?: any) => request('put', url, body, config) }
  const component = readFileSync(new URL('../../views/quality/EmrTemplateView.vue', import.meta.url), 'utf8')
  const parsed = parse(component, { filename: 'EmrTemplateView.vue' })
  expect(parsed.errors).toEqual([])
  const compiled = compileScript(parsed.descriptor, { id: 'v162-real-grant-component', inlineTemplate: true })
  const exports: Record<string, any> = {}
  const code = ts.transpileModule(compiled.content, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
  } }).outputText
  runInNewContext(code, { exports, console, require(name: string) {
    if (name === 'vue') return Vue
    if (name === '../../api/client') return { default: client }
    if (name === 'element-plus') return { ElMessage: messages, ElMessageBox: { confirm: vi.fn(() => Promise.resolve(true)) } }
    throw new Error('Unexpected compiled SFC import ' + name)
  } })
  const renderer = Vue.createRenderer<Node, Node>({
    createElement: kind => makeNode(kind), createText: text => makeNode('#text', text), createComment: text => makeNode('#comment', text),
    setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text; node.children = [] },
    parentNode: node => node.parent, nextSibling: node => { const siblings = node.parent?.children ?? []; return siblings[siblings.indexOf(node) + 1] ?? null },
    patchProp: (node, key, _previous, value) => { node.props[key] = value },
    insert(node, parent, anchor = null) {
      if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
      const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, node); node.parent = parent
    },
    remove(node) { if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1); node.parent = null },
  })
  const app = renderer.createApp(exports.default)
  const pass = (name: string) => Vue.defineComponent({ name, setup: (_, { slots }) => () => Vue.h('div', { 'data-control': name }, slots.default?.()) })
  for (const name of ['ElCard', 'ElForm', 'ElFormItem', 'ElTag', 'ElEmpty', 'ElCheckbox', 'ElInput']) app.component(name, pass(name))
  app.component('ElButton', Vue.defineComponent({ name: 'ElButton', inheritAttrs: false,
    props: ['disabled', 'loading'], setup: (props, { attrs, slots }) => () => Vue.h('button', {
      ...attrs, disabled: Boolean(props.disabled || props.loading), 'data-loading': Boolean(props.loading),
    }, slots.default?.()) }))
  app.component('ElDialog', Vue.defineComponent({ name: 'ElDialog', props: ['modelValue', 'title'], emits: ['update:modelValue', 'close', 'closed'],
    setup: (props, { slots, emit }) => () => props.modelValue ? Vue.h('section', { role: 'dialog', title: props.title }, [
      Vue.h('button', { 'data-dialog-x': true, onClick() { emit('update:modelValue', false); emit('close'); emit('closed') } }, '对话框关闭'),
      slots.default?.(), slots.footer?.(),
    ]) : null }))
  app.component('ElSelect', Vue.defineComponent({ name: 'ElSelect', inheritAttrs: false,
    props: ['modelValue', 'disabled', 'loading', 'placeholder'], emits: ['update:modelValue', 'change'],
    setup: (props, { attrs, slots, emit }) => () => Vue.h('select', { ...attrs, disabled: Boolean(props.disabled),
      value: props.modelValue, placeholder: props.placeholder, 'data-loading': Boolean(props.loading),
      onChoose(value: unknown) { emit('update:modelValue', value); emit('change', value) },
    }, slots.default?.()) }))
  app.component('ElOption', Vue.defineComponent({ name: 'ElOption', props: ['value', 'label', 'disabled'],
    setup: props => () => Vue.h('option', { value: props.value, disabled: props.disabled }, String(props.label)) }))
  app.component('ElTable', Vue.defineComponent({ name: 'ElTable', props: ['data'], setup(props, { slots }) {
    Vue.provide('v162-table-rows', () => props.data ?? [])
    return () => Vue.h('table', slots.default?.())
  } }))
  app.component('ElTableColumn', Vue.defineComponent({ name: 'ElTableColumn', props: ['prop', 'label'], setup(props, { slots }) {
    const rows = Vue.inject<() => any[]>('v162-table-rows')!
    return () => Vue.h('tbody', { 'data-column': props.label }, rows().map(row => Vue.h('tr', { 'data-row-id': row.id },
      slots.default ? slots.default({ row }) : String(row[props.prop] ?? ''))))
  } }))
  app.directive('loading', {})
  app.config.errorHandler = error => { errors.push(error) }
  const tree = makeNode('root'); app.mount(tree)
  const all = (parent: Node = tree): Node[] => [parent, ...parent.children.flatMap(child => all(child))]
  const text = (node: Node): string => node.text + node.children.map(text).join('')
  const dialog = () => all().find(node => node.kind === 'section' && String(node.props.title).startsWith('授权：'))
  const inside = () => { const active = dialog(); expect(active, 'The actual grant dialog must be visible').toBeDefined(); return all(active!) }
  const button = (label: string, grantDialog = true) => {
    const found = (grantDialog ? inside() : all()).filter(node => node.kind === 'button' && text(node).trim() === label)
    expect(found, 'Unique rendered button ' + label).toHaveLength(1); return found[0]
  }
  const click = async (node: Node) => { expect(node.props.disabled, 'A user cannot click a disabled control').not.toBe(true); node.props.onClick?.(); await turn() }
  const typeSelect = () => inside().find(node => node.kind === 'select' && all(node).some(option => option.kind === 'option' && text(option) === '授权个人'))!
  const candidateSelect = () => inside().find(node => node.kind === 'select' && ['选择科室', '选择人员'].includes(node.props.placeholder))!
  const options = () => all(candidateSelect()).filter(node => node.kind === 'option').map(node => ({ id: node.props.value, label: text(node) }))
  const choose = async (node: Node, value: number | Kind) => {
    expect(node).toBeDefined(); expect(node.props.disabled, 'A user cannot choose from a disabled select').not.toBe(true)
    expect(all(node).filter(option => option.kind === 'option' && option.props.value === value)).toHaveLength(1)
    node.props.onChoose(value); await turn()
  }
  const pending = (method: string, url: string) => requests.filter(wire => !wire.done && wire.method === method && wire.url === url)
  const candidateWires = () => requests.filter(wire => wire.url === '/emr-templates/grantee-candidates')
  const answer = async (wire: Wire, value: unknown) => { expect(wire).toBeDefined(); expect(wire.done).toBe(false); wire.resolve(value); await turn() }
  const h = { tree, app, errors, messages, requests, dialog, inside, all, text, button, click, typeSelect, candidateSelect, options, choose, pending, candidateWires, answer,
    async open(row = A) {
      const before = requests.length
      await click(button(row.grant_count + ' 个', false))
      const grants = requests.slice(before).filter(wire => !wire.done && wire.method === 'get' && wire.url === `/emr-templates/${row.id}/grants`)
      expect(grants).toHaveLength(1); await answer(grants[0], [grant(row.id)])
      return candidateWires().at(-1)!
    },
    async switchType(kind: Kind) { await choose(typeSelect(), kind); return candidateWires().at(-1)! },
    async close() { await click(button('对话框关闭')) },
    posts() { return requests.filter(wire => wire.method === 'post') },
    async drain() {
      if (unmounted) return
      for (let i = 0; i < 20; ++i) {
        const pending = requests.filter(wire => !wire.done)
        if (!pending.length) break
        for (const wire of pending) await answer(wire, wire.method !== 'get' ? null
          : wire.url.endsWith('/grants') ? [grant(Number(wire.url.split('/')[2]))]
            : wire.params.granteeType === 'USER' ? USERS : DEPTS)
      }
      expect(requests.filter(wire => !wire.done)).toEqual([])
      app.unmount(); unmounted = true
    },
  }
  jobs.push(() => h.drain()); return h
}

afterEach(async () => { for (const drain of jobs.splice(0)) await drain() })

const candidates = (kind: Kind) => kind === 'USER' ? USERS : DEPTS
const labels = (kind: Kind) => candidates(kind).map(row => ({ id: row.id, label: 'dept_name' in row ? `${row.name}（${row.dept_name}）` : row.name }))
async function ready(kind: Kind = 'DEPT') {
  const h = mount(); await turn(); const initial = await h.open()
  await h.answer(initial, DEPTS)
  if (kind === 'USER') { const next = await h.switchType('USER'); await h.answer(next, USERS) }
  return h
}
function expectBlocked(h: ReturnType<typeof mount>) {
  expect(h.button('授予').props.disabled, 'Grant must stay disabled without a current loaded candidate').toBe(true)
  expect(h.candidateSelect().props.disabled || h.options().length === 0,
    'Old-type candidates must be absent or impossible to select while current type is pending').toBe(true)
}

describe('v162 mounted grant dialog owns the current candidate type and session', () => {
  for (const [first, current] of [['DEPT', 'USER'], ['USER', 'DEPT']] as const) {
    for (const oldFirst of [true, false]) it(`${first}→${current}: ${oldFirst ? 'old-first' : 'new-first'} responses retain only current-type options`, async () => {
      const h = mount(); await turn(); const department = await h.open()
      const old = first === 'DEPT' ? department : await h.switchType('USER')
      if (first === 'USER') await h.answer(department, DEPTS)
      const latest = await h.switchType(current)
      expect(old.params.granteeType).toBe(first); expect(latest.params.granteeType).toBe(current)
      if (oldFirst) { await h.answer(old, candidates(first)); expectBlocked(h); await h.answer(latest, candidates(current)) }
      else { await h.answer(latest, candidates(current)); await h.answer(old, candidates(first)) }
      expect(h.typeSelect().props.value).toBe(current); expect(h.options()).toEqual(labels(current)); expect(h.posts()).toEqual([])
    })
    it(`${first}→${current}: pending current request makes the old loaded numeric id unselectable and ungrantable`, async () => {
      const h = await ready(first); await h.choose(h.candidateSelect(), 17)
      expect(h.button('授予').props.disabled).toBe(false)
      const latest = await h.switchType(current); expectBlocked(h)
      await h.answer(latest, candidates(current)); expect(h.candidateSelect().props.value).toBeNull()
      expect(h.button('授予').props.disabled).toBe(true); expect(h.posts()).toEqual([])
    })
  }
  it('an old department label must never send its overlapping numeric id as a USER grant', async () => {
    const h = mount(); await turn(); const old = await h.open(), latest = await h.switchType('USER')
    await h.answer(latest, USERS); await h.answer(old, DEPTS)
    // Execute the erroneous user-visible path on old code before asserting it is
    // gone. Department 17 and user 17 deliberately exist with different names.
    if (h.options().some(option => option.label === '模拟甲科室') && !h.candidateSelect().props.disabled) {
      await h.choose(h.candidateSelect(), 17); await h.click(h.button('授予'))
    }
    expect(h.posts(), 'A displayed department cannot be submitted as USER 17').toEqual([])
    expect(h.options()).toEqual(labels('USER'))
  })
  for (const oldFirst of [true, false]) it(`DEPT→USER→DEPT ABA ignores first same-type request (${oldFirst ? 'old-first' : 'new-first'})`, async () => {
    const h = mount(); await turn(); const old = await h.open(), middle = await h.switchType('USER'), latest = await h.switchType('DEPT')
    const fresh = [{ id: 29, name: '最新科室' }]
    if (oldFirst) { await h.answer(old, DEPTS); expectBlocked(h); await h.answer(latest, fresh) }
    else { await h.answer(latest, fresh); await h.answer(old, DEPTS) }
    await h.answer(middle, USERS); expect(h.options()).toEqual([{ id: 29, label: '最新科室' }]); expect(h.posts()).toEqual([])
  })
  for (const row of [A, B]) it(`close and reopen ${row.id} creates a new session even when type is DEPT again`, async () => {
    const h = mount(); await turn(); const old = await h.open(); await h.close(); const latest = await h.open(row)
    await h.answer(latest, [{ id: 29, name: '当前会话科室' }]); await h.answer(old, DEPTS)
    expect(h.dialog()!.props.title).toBe('授权：' + row.name); expect(h.options()).toEqual([{ id: 29, label: '当前会话科室' }])
    expect(h.posts()).toEqual([])
  })
  it('switching directly from template A to B cannot install old A candidates into B', async () => {
    const h = mount(); await turn(); const old = await h.open(), latest = await h.open(B)
    await h.answer(latest, [{ id: 29, name: '乙模板新科室' }]); await h.answer(old, DEPTS)
    expect(h.dialog()!.props.title).toBe('授权：' + B.name); expect(h.options()).toEqual([{ id: 29, label: '乙模板新科室' }])
  })
  for (const kind of ['DEPT', 'USER'] as const) it(`${kind}: legitimate current option preserves the original exact POST and refreshes its own grants`, async () => {
    const h = await ready(kind); expect(h.options()).toEqual(labels(kind)); await h.choose(h.candidateSelect(), 17)
    await h.click(h.button('授予')); expect(h.posts()).toHaveLength(1)
    expect(h.posts()[0]).toMatchObject({ method: 'post', url: '/emr-templates/71/grants', body: { granteeType: kind, granteeId: 17 } })
    await h.answer(h.posts()[0], null); await h.drain()
    expect(h.messages.success).toHaveBeenCalledExactlyOnceWith('已授权'); expect(h.errors).toEqual([])
  })
  it('returning to the same type cannot reuse a previously selected id that is no longer a candidate', async () => {
    const h = await ready(); await h.choose(h.candidateSelect(), 17)
    const middle = await h.switchType('USER'); await h.answer(middle, USERS)
    const current = await h.switchType('DEPT'); expectBlocked(h)
    await h.answer(current, [{ id: 29, name: '替换后的科室' }])
    expect(h.candidateSelect().props.value).toBeNull(); expect(h.button('授予').props.disabled).toBe(true); expect(h.posts()).toEqual([])
  })
  for (const row of [A, B]) it(`old grant-list response cannot replace the reopened ${row.id} grant list`, async () => {
    const h = mount(); await turn(); await h.click(h.button('1 个', false)); const old = h.pending('get', '/emr-templates/71/grants')[0]
    const oldCandidates = h.candidateWires().at(-1)!; await h.close()
    const current = await h.open(row); await h.answer(current, DEPTS); await h.answer(oldCandidates, DEPTS)
    const before = h.text(h.dialog()!); await h.answer(old, [{ ...grant(A.id), grantee_name: '过时甲授权对象' }])
    expect(h.text(h.dialog()!)).toBe(before); expect(h.text(h.dialog()!)).not.toContain('过时甲授权对象')
  })
  it('a late successful grant for A cannot clear the chosen B recipient or refresh B on A behalf', async () => {
    const h = await ready(); await h.choose(h.candidateSelect(), 17); await h.click(h.button('授予'))
    const old = h.posts()[0]; await h.close(); const current = await h.open(B); await h.answer(current, DEPTS)
    await h.choose(h.candidateSelect(), 19); const before = h.text(h.dialog()!)
    await h.answer(old, null)
    expect(h.candidateSelect().props.value).toBe(19); expect(h.text(h.dialog()!)).toBe(before)
    expect(h.pending('get', '/emr-templates/72/grants')).toEqual([]); expect(h.messages.success).not.toHaveBeenCalled()
  })
  it('old POST finally cannot release the current session pending submission and permit a duplicate', async () => {
    const h = await ready(); await h.choose(h.candidateSelect(), 17); await h.click(h.button('授予'))
    const old = h.posts()[0]; await h.close(); const current = await h.open(B); await h.answer(current, DEPTS)
    await h.choose(h.candidateSelect(), 19); await h.click(h.button('授予'))
    expect(h.posts()).toHaveLength(2); const active = h.posts()[1]
    expect(active).toMatchObject({ url: '/emr-templates/72/grants', body: { granteeType: 'DEPT', granteeId: 19 } })
    await h.answer(old, null)
    expect(h.button('授予').props.disabled, 'B remains busy until B actual POST completes').toBe(true)
    expect(h.posts()).toHaveLength(2); await h.answer(active, null)
  })
  it('an old revoke completion cannot refresh or replace the newly opened template B grants', async () => {
    const h = await ready(); await h.click(h.button('撤销'))
    const old = h.requests.find(wire => wire.method === 'delete')!; expect(old.url).toBe('/emr-templates/71/grants/801')
    await h.close(); const current = await h.open(B); await h.answer(current, DEPTS)
    const before = h.text(h.dialog()!); await h.answer(old, null)
    expect(h.text(h.dialog()!)).toBe(before); expect(h.pending('get', '/emr-templates/72/grants')).toEqual([])
    expect(h.messages.success).not.toHaveBeenCalled()
  })
  it('closing a dialog while candidates wait cannot trigger any grant when that old read completes', async () => {
    const h = mount(); await turn(); const old = await h.open(); await h.close(); await h.answer(old, DEPTS)
    expect(h.dialog()).toBeUndefined(); expect(h.posts()).toEqual([]); expect(h.messages.success).not.toHaveBeenCalled(); expect(h.errors).toEqual([])
  })
})
