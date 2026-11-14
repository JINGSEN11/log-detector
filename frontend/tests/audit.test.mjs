import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

const vite = await createServer({ server: { middlewareMode: true }, appType: 'custom' })
after(() => vite.close())

test('审计路由只允许管理员进入', async () => {
  const values = new Map([['csys_token', 'test-token'], ['csys_role', 'ADMIN']])
  globalThis.localStorage = { getItem: key => values.get(key) ?? null }
  globalThis.window = { navigator: { userAgent: '' }, location: { protocol: 'http:', host: 'localhost', pathname: '/', search: '', hash: '' }, history: { state: null, replaceState(state) { this.state = state }, pushState(state) { this.state = state } }, addEventListener() {}, removeEventListener() {} }
  globalThis.document = { querySelector: () => null, addEventListener() {}, removeEventListener() {} }
  globalThis.location = globalThis.window.location
  globalThis.history = globalThis.window.history
  const { default: router } = await vite.ssrLoadModule('/src/router/index.ts')
  assert.equal(router.resolve('/audit').name, 'audit', '审计路由尚未注册')
  assert.ok(router.resolve('/audit').meta.adminOnly, '审计路由应限制管理员')
  await router.push('/audit')
  assert.equal(router.currentRoute.value.path, '/audit')
  values.set('csys_role', 'ANALYST')
  await router.push('/home')
  await router.push('/audit')
  assert.notEqual(router.currentRoute.value.path, '/audit')
  values.delete('csys_token')
  await router.push('/audit')
  assert.equal(router.currentRoute.value.path, '/login')
})

test('审计列表加载、分页、空状态和失败重试', async () => {
  const exists = await import('node:fs/promises').then(fs => fs.access(new URL('../src/views/useAudit.ts', import.meta.url)).then(() => true, () => false))
  assert.ok(exists, '审计页面状态管理尚未实现')
  const { useAudit } = await vite.ssrLoadModule('/src/views/useAudit.ts')
  const calls = []
  let respond
  const load = (page, size) => { calls.push([page, size]); return new Promise((resolve, reject) => { respond = { resolve, reject } }) }
  const state = useAudit(load)
  const first = state.loadPage(1)
  assert.equal(state.loading.value, true)
  respond.resolve({ list: [{ id: 1, action: 'LOGIN_SUCCESS', detail: 'secret' }], total: 21, page: 1, size: 20 })
  await first
  assert.equal(state.loading.value, false)
  assert.equal(state.rows.value[0].action, 'LOGIN_SUCCESS')
  const second = state.loadPage(2)
  assert.deepEqual(calls[1], [2, 20])
  respond.resolve({ list: [], total: 21, page: 2, size: 20 })
  await second
  assert.equal(state.rows.value.length, 0)
  assert.equal(state.page.value, 2)
  const failure = state.loadPage(2)
  respond.reject(new Error('failed'))
  await failure
  assert.equal(state.error.value, true)
  assert.equal(state.rows.value.length, 0)
  const retry = state.loadPage(2)
  respond.resolve({ list: [{ id: 2, action: 'LOGIN_FAILURE' }], total: 21, page: 2, size: 20 })
  await retry
  assert.equal(state.error.value, false)
  assert.equal(state.rows.value[0].id, 2)
})

test('导航仅向管理员展示审计入口', async () => {
  const { createSSRApp, h } = await import('vue')
  const { renderToString } = await import('@vue/server-renderer')
  const { createPinia, setActivePinia } = await import('pinia')
  const { useAuthStore } = await vite.ssrLoadModule('/src/stores/auth.ts')
  const { default: Layout } = await vite.ssrLoadModule('/src/layouts/MainLayout.vue')
  const render = async role => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().role = role
    const app = createSSRApp(Layout)
    app.use(pinia)
    app.component('router-view', { render: () => null })
    app.component('el-menu', { render() { return h('nav', this.$slots.default?.()) } })
    app.component('el-menu-item', { render() { return h('div', this.$slots.default?.()) } })
    app.component('el-button', { render() { return h('button', this.$slots.default?.()) } })
    const { routeLocationKey, routerKey } = await import('vue-router')
    app.provide(routeLocationKey, { path: '/home' })
    app.provide(routerKey, { push() {} })
    return renderToString(app)
  }
  assert.match(await render('ADMIN'), /审计记录/)
  assert.doesNotMatch(await render('ANALYST'), /审计记录/)
})

test('审计页面存在且不展示原始敏感详情', async () => {
  const { createSSRApp, h } = await import('vue')
  const { renderToString } = await import('@vue/server-renderer')
  const { default: Audit } = await vite.ssrLoadModule('/src/views/Audit.vue')
  const app = createSSRApp(Audit)
  for (const name of ['el-card', 'el-button', 'el-table', 'el-table-column', 'el-pagination']) {
    app.component(name, { render() { return h('div', this.$slots.default?.()) } })
  }
  const { routerKey } = await import('vue-router')
  app.provide(routerKey, { replace() {} })
  const html = await renderToString(app)
  assert.match(html, /审计记录/)
  assert.match(html, /接口响应仍可能包含详情并传输至浏览器/)
  assert.match(html, /翻页结果可能重复或遗漏/)
  assert.doesNotMatch(html, /detail|secret/i)
})

test('跨标签身份变更时清空旧记录、丢弃迟到响应并离开审计页', async () => {
  const { createRenderer, h, nextTick } = await import('vue')
  const { createPinia } = await import('pinia')
  const { routerKey } = await import('vue-router')
  const { useAudit } = await vite.ssrLoadModule('/src/views/useAudit.ts')
  const values = new Map([['csys_token', 'old'], ['csys_username', 'old-user'], ['csys_role', 'ADMIN']])
  globalThis.localStorage = { getItem: key => values.get(key) ?? null }
  const listeners = new Map()
  globalThis.window = { addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: (name, fn) => { if (listeners.get(name) === fn) listeners.delete(name) } }
  const pending = []
  let state
  const Component = { setup() { state = useAudit(() => new Promise(resolve => pending.push(resolve))); return () => h('div', JSON.stringify(state.rows.value)) } }
  const renderer = createRenderer({
    createElement: type => ({ type, children: [], props: {} }), createText: text => ({ text }), createComment: text => ({ text }),
    setText: (n, text) => { n.text = text }, setElementText: (n, text) => { n.text = text },
    patchProp: (n, key, old, value) => { n.props[key] = value },
    insert: (n, parent) => { parent.children.push(n); n.parent = parent },
    remove: n => { if (n.parent) n.parent.children = n.parent.children.filter(c => c !== n) },
    parentNode: n => n.parent, nextSibling: () => null
  })
  const root = { children: [] }
  const app = renderer.createApp(Component)
  app.use(createPinia())
  const navigations = []
  app.provide(routerKey, { replace: target => { navigations.push(target); return Promise.resolve() } })
  app.mount(root)
  const first = state.loadPage(1)
  pending.shift()({ list: [{ id: 1, action: 'old-record' }], total: 1, page: 1, size: 20 })
  await first; await nextTick()
  assert.equal(state.rows.value[0].action, 'old-record')
  const late = state.loadPage(2)
  assert.ok(listeners.has('storage'), '应监听跨标签身份变化')
  listeners.get('storage')({ key: 'other', oldValue: 'a', newValue: 'b', storageArea: localStorage })
  assert.equal(navigations.length, 0)
  values.set('csys_token', 'new')
  listeners.get('storage')({ key: 'csys_token', oldValue: 'old', newValue: 'new', storageArea: localStorage })
  assert.deepEqual(state.rows.value, [])
  assert.equal(navigations.length, 1)
  pending.shift()({ list: [{ id: 2, action: 'late-record' }], total: 2, page: 2, size: 20 })
  await late
  assert.deepEqual(state.rows.value, [])
  app.unmount()
  assert.equal(listeners.has('storage'), false)
})

test('审计查询请求后端分页接口', async () => {
  globalThis.localStorage = { getItem: () => null }
  const { auditApi } = await vite.ssrLoadModule('/src/api/index.ts')
  assert.ok(auditApi, '管理员审计 API 尚未实现')
  const { default: client } = await vite.ssrLoadModule('/src/api/request.ts')
  let request
  client.defaults.adapter = async config => {
    request = config
    return { data: { code: 0, message: '', data: { list: [], total: 0, page: 2, size: 20 } }, status: 200, statusText: 'OK', headers: {}, config }
  }
  const result = await auditApi.list(2, 20)
  assert.equal(request.url, '/audit')
  assert.deepEqual(request.params, { page: 2, size: 20 })
  assert.equal(result.page, 2)
})
