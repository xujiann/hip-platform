import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as Vue from 'vue'
import axios from 'axios'

// Compatibility positives: execute the complete original template-maintenance
// setup and real shared client. Only lifecycle/UI/storage and Axios transport
// are controlled. This is not a mounted-control or old-source RED claim.
const scopes: ReturnType<typeof Vue.effectScope>[] = []
const pendingChecks: Array<() => void> = []
afterEach(() => {
  try { for (const check of pendingChecks.splice(0)) check() }
  finally { for (const scope of scopes.splice(0)) scope.stop() }
})
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const settle = () => new Promise<void>(resolve => setTimeout(resolve, 0))
const withoutImports = (source: string, filename: string) => {
  const parsed = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  return parsed.statements.filter(s => !ts.isImportDeclaration(s)).map(s => s.getText(parsed)).join('\n')
}
type Wire = { config: any; done: boolean; reply: (data?: unknown, code?: number, status?: number) => void }
const row = { id: 71, name: '模拟科室模板', content: '原模板正文', template_type: 'EMR', scope: 'DEPT',
  scopeName: '科室', dept_id: 7, dept_name: '模拟科室', owner_id: 3, owner_name: '模拟医生',
  record_type: 'OUTP', is_default: false, enabled: true, grant_count: 2, editable: true }
const grant = { id: 711, template_id: 71, grantee_type: 'USER', grantee_id: 8,
  granted_at: '2026-10-29T00:00:00Z', grantee_name: '模拟被授权者', granted_by_name: '模拟医生' }
const actions = ['save', 'disable', 'enable', 'grant', 'revoke', 'default', 'replace', 'unset'] as const
type Action = typeof actions[number]

function setup() {
  const storage = new Map([['hip_token', 'v144-compatible-token']])
  const localStorage = { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) }
  const ElMessage = Object.assign(vi.fn(), { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() })
  const ElMessageBox = { confirm: vi.fn(() => Promise.resolve(true)) }, router = { push: vi.fn() }
  const clientBox: Record<string, any> = { axios, ElMessage, router, localStorage, Blob, exports: {} }
  const sharedSource = readFileSync(new URL('../../api/client.ts', import.meta.url), 'utf8')
  runInNewContext(ts.transpileModule(withoutImports(sharedSource, 'client.ts'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, clientBox)
  const client = clientBox.exports.default, requests: Wire[] = []
  const listReply = [copy(row)], grantReply = [copy(grant)]
  client.defaults.adapter = (config: any) => new Promise((resolve, reject) => {
    const wire: Wire = { config, done: false, reply(data = null, code = 0, status = 200) {
      expect(wire.done).toBe(false); wire.done = true
      const response = { data: { code, message: code ? '模板未授权或岗位无权' : 'success', data },
        config, status, statusText: String(status), headers: {} }
      if (status >= 400) reject(new axios.AxiosError('Request failed with status code ' + status,
        'ERR_BAD_REQUEST', config, undefined, response))
      else resolve(response)
    } }
    requests.push(wire)
    if (config.method !== 'get') return
    if (config.url === '/emr-templates/visible') wire.reply(copy(listReply))
    else if (config.url === '/emr-templates/71/grants') wire.reply(copy(grantReply))
    else throw new Error('Unconfigured actual GET ' + config.url)
  })
  const box: Record<string, any> = { ...Vue, client, ElMessage, ElMessageBox, onMounted: vi.fn(), console }
  const component = readFileSync(new URL('../../views/quality/EmrTemplateView.vue', import.meta.url), 'utf8')
  const source = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
  const exposed = '\nglobalThis.view = { all, actor, form, dialogVisible, dialogTitle, saving, origScope, origDeptId, '
    + 'filterType, filterScope, filterDeptId, keyword, includeDisabled, grantVisible, grantRow, grantRows, '
    + 'grantType, granteeId, granting, openEdit, save, toggleEnabled, setDefault, unsetDefault, addGrant, revokeGrant };'
  const scope = Vue.effectScope(); scopes.push(scope)
  scope.run(() => runInNewContext(ts.transpileModule(withoutImports(source, 'EmrTemplateView.ts') + exposed, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText, box))
  const v = box.view as Record<string, any>
  v.all.value = [copy(row)]
  v.actor.value = { userId: 3, deptId: 7, deptName: '模拟科室', admin: false, grantableScopes: ['DEPT', 'PERSONAL'] }
  v.openEdit(copy(row)); v.form.name = '医生未保存的模板名'; v.form.content = '医生未保存的模板正文'
  v.grantVisible.value = true; v.grantRow.value = copy(row); v.grantRows.value = [copy(grant)]
  v.granteeId.value = 9; v.grantType.value = 'USER'
  v.filterScope.value = 'DEPT'; v.filterDeptId.value = 7; v.keyword.value = '当前筛选'
  pendingChecks.push(() => expect(requests.filter(wire => !wire.done)).toEqual([]))
  const invoke = (action: Action) => {
    if (action === 'save') return v.save()
    if (action === 'disable' || action === 'enable') return v.toggleEnabled(copy(row), action === 'enable')
    if (action === 'grant') return v.addGrant()
    if (action === 'revoke') return v.revokeGrant(copy(grant))
    if (action === 'default' || action === 'replace') return v.setDefault(copy(row), action === 'replace')
    return v.unsetDefault(copy(row))
  }
  return { v, requests, ElMessage, ElMessageBox, storage, router, listReply, grantReply,
    async begin(action: Action) {
      // Attach rejection handling immediately; no failure is left unhandled.
      const result = invoke(action).then((value: unknown) => ({ ok: true, value }), (error: any) => ({ ok: false, error }))
      await settle()
      const wire = requests.filter(w => !w.done).at(-1)
      expect(wire).toBeDefined()
      return { wire: wire!, result }
    } }
}
type Harness = ReturnType<typeof setup>
const state = (h: Harness) => copy({ all: h.v.all.value, actor: h.v.actor.value, form: h.v.form,
  dialog: h.v.dialogVisible.value, title: h.v.dialogTitle.value, grantVisible: h.v.grantVisible.value,
  grantRow: h.v.grantRow.value, grantRows: h.v.grantRows.value, granteeId: h.v.granteeId.value,
  grantType: h.v.grantType.value, filterType: h.v.filterType.value, filterScope: h.v.filterScope.value,
  filterDeptId: h.v.filterDeptId.value, keyword: h.v.keyword.value, includeDisabled: h.v.includeDisabled.value })
function expectTokenKept(h: Harness) {
  expect(h.storage.get('hip_token')).toBe('v144-compatible-token'); expect(h.router.push).not.toHaveBeenCalled()
}
const requestContract: Record<Action, { method: string; url: string; params?: unknown }> = {
  save: { method: 'put', url: '/emr-templates/71' }, disable: { method: 'put', url: '/emr-templates/71/disable' },
  enable: { method: 'put', url: '/emr-templates/71/enable' }, grant: { method: 'post', url: '/emr-templates/71/grants' },
  revoke: { method: 'delete', url: '/emr-templates/71/grants/711' },
  default: { method: 'put', url: '/emr-templates/71/default', params: { replace: false } },
  replace: { method: 'put', url: '/emr-templates/71/default', params: { replace: true } },
  unset: { method: 'delete', url: '/emr-templates/71/default' },
}

describe('v144 original HEAD maintenance consumer compatibility', () => {
  for (const refusal of [{ name: 'current ACL 4066', code: 4066, status: 200 },
    { name: 'current duty HTTP403', code: 1005, status: 403 }, { name: 'transport HTTP503', code: 5000, status: 503 }]) {
    it.each(actions)(`%s: ${refusal.name} retains drafts, rows and token, without false success, refresh or retry`, async action => {
      const h = setup(), before = state(h), request = await h.begin(action)
      expect(request.wire.config).toMatchObject(requestContract[action])
      expect(request.wire.config.headers.Authorization).toBe('Bearer v144-compatible-token')
      if (action === 'save') expect(h.v.saving.value).toBe(true)
      if (action === 'grant') expect(h.v.granting.value).toBe(true)
      request.wire.reply(null, refusal.code, refusal.status)
      const result = await request.result
      expect(result.ok).toBe(action === 'default' || action === 'replace') // original handler consumes non-4067 errors
      if (!result.ok && refusal.status === 200) expect(result.error.bizCode).toBe(4066)
      expect(state(h)).toEqual(before); expect(h.v.saving.value).toBe(false); expect(h.v.granting.value).toBe(false)
      expect(h.ElMessage.success).not.toHaveBeenCalled(); expect(h.ElMessage.error).toHaveBeenCalledTimes(1)
      expect(h.ElMessageBox.confirm).toHaveBeenCalledTimes(action === 'disable' ? 1 : 0)
      expect(h.requests).toHaveLength(1); expectTokenKept(h)
    })
  }
  it.each(actions)('%s: authorized success keeps the original verb/path/body and performs its legitimate refresh', async action => {
    const h = setup(), request = await h.begin(action)
    expect(request.wire.config).toMatchObject(requestContract[action])
    if (action === 'save') expect(JSON.parse(request.wire.config.data)).toEqual({ name: '医生未保存的模板名',
      content: '医生未保存的模板正文', templateType: 'EMR', scope: 'DEPT', deptId: 7, recordType: 'OUTP' })
    if (action === 'grant') expect(JSON.parse(request.wire.config.data)).toEqual({ granteeType: 'USER', granteeId: 9 })
    request.wire.reply(); expect((await request.result).ok).toBe(true)
    expect(h.ElMessage.success).toHaveBeenCalledTimes(1); expect(h.ElMessage.error).not.toHaveBeenCalled()
    expect(h.requests.filter(w => w.config.method !== 'get')).toHaveLength(1)
    expect(h.requests.filter(w => w.config.url === '/emr-templates/visible')).toHaveLength(1)
    expect(h.requests.filter(w => w.config.url === '/emr-templates/71/grants')).toHaveLength(action === 'grant' || action === 'revoke' ? 1 : 0)
    expect(h.v.saving.value).toBe(false); expect(h.v.granting.value).toBe(false); expectTokenKept(h)
    if (action === 'save') { expect(h.v.dialogVisible.value).toBe(false); expect(h.v.filterScope.value).toBeNull(); expect(h.v.filterDeptId.value).toBeNull() }
    if (action === 'grant') expect(h.v.granteeId.value).toBeNull()
  })
  it('only original 4067 offers explicit replacement, with replace=true as the sole second mutation', async () => {
    const h = setup(), first = await h.begin('default')
    first.wire.reply(null, 4067); await settle()
    expect(h.ElMessage.error).not.toHaveBeenCalled(); expect(h.ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(h.ElMessageBox.confirm.mock.calls[0][0]).toContain('接管默认位')
    const second = h.requests.find(w => !w.done)!
    expect(second.config).toMatchObject(requestContract.replace); second.reply()
    expect((await first.result).ok).toBe(true); expect(h.requests.filter(w => w.config.method !== 'get')).toHaveLength(2)
    expect(h.ElMessage.success).toHaveBeenCalledWith('已替换为默认模板'); expectTokenKept(h)
  })
  it('canceling the original 4067 replacement leaves every row and draft unchanged, with no second write', async () => {
    const h = setup(); h.ElMessageBox.confirm.mockRejectedValueOnce('cancel')
    const before = state(h), first = await h.begin('default'); first.wire.reply(null, 4067)
    expect((await first.result).ok).toBe(true); expect(state(h)).toEqual(before)
    expect(h.requests).toHaveLength(1); expect(h.ElMessage.success).not.toHaveBeenCalled(); expectTokenKept(h)
  })
  it('a replacement losing ACL returns one real error and never recursively confirms or claims success', async () => {
    const h = setup(), before = state(h), first = await h.begin('default')
    first.wire.reply(null, 4067); await settle()
    h.requests.find(w => !w.done)!.reply(null, 4066)
    const result = await first.result
    expect(result.ok).toBe(false); expect(result.error.bizCode).toBe(4066); expect(state(h)).toEqual(before)
    expect(h.ElMessageBox.confirm).toHaveBeenCalledTimes(1); expect(h.ElMessage.error).toHaveBeenCalledTimes(1)
    expect(h.ElMessage.success).not.toHaveBeenCalled(); expect(h.requests).toHaveLength(2); expectTokenKept(h)
  })
  it('an actual HTTP401 keeps the original expiry/logout behavior and preserves the unsaved form', async () => {
    const h = setup(), before = state(h), request = await h.begin('save'); request.wire.reply(null, 1000, 401)
    expect((await request.result).ok).toBe(false); expect(state(h)).toEqual(before)
    expect(h.storage.has('hip_token')).toBe(false); expect(h.router.push).toHaveBeenCalledWith('/login')
    expect(h.ElMessage.warning).toHaveBeenCalledWith('登录已过期，请重新登录'); expect(h.ElMessage.success).not.toHaveBeenCalled()
  })
})
