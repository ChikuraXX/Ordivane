/** 聚焦信任边界与不补发规则；不将本桩测试当真实联调。 */
const { test } = require('node:test')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const root = path.resolve(__dirname, '../../..'), ts = require(path.join(root, 'node_modules/typescript'))
global.window = globalThis
function url(source) { return `data:text/javascript;base64,${Buffer.from(ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2023, module: ts.ModuleKind.ESNext } }).outputText).toString('base64')}` }
const observerUrl = url(fs.readFileSync(path.join(root, 'src/lib/diagnostics/read-task-observer.ts'), 'utf8').replaceAll('import.meta.env.DEV','false'))
const clientUrl = url(fs.readFileSync(path.join(root, 'src/lib/http/client.ts'), 'utf8').replaceAll("'../diagnostics/read-task-observer'", JSON.stringify(observerUrl)))
const apiUrl = url(fs.readFileSync(path.join(__dirname, 'api.ts'), 'utf8').replace("'../../lib/http/client'", JSON.stringify(clientUrl)))
const id = '00000000-0000-4000-8000-000000000050', evidence = '00000000-0000-4000-8000-000000000051', hash = 'a'.repeat(64)
function plan() { return { id, incidentId: id, version: 1, previousId: null, requesterId: id, action: 'VERIFY_ONLY', parameters: {}, evidenceIds: [evidence],
  digest: hash, configurationDigest: hash, eventDigest: hash, actionDigest: hash, createdAt: '2026-10-21T01:00:00Z', expiresAt: '2026-10-21T01:30:00Z',
  status: 'DRAFT', recordedStatus: 'DRAFT', executionAuthorized: false, decisions: [], approvalRequests: [], prechecks: [] } }
test('计划边界、冻结参数、准确绑定与写请求不自动补发', async t => {
  const api = await import(apiUrl), client = await import(clientUrl), original = global.fetch
  try {
    await t.test('跨案、跨计划、错误版本、伪执行资格不接受', () => {
      assert.equal(api.planFrom(plan(), id, id).executionAuthorized, false)
      for (const change of [{ incidentId: evidence }, { id: evidence }, { version: '1' }, { version: 0 }, { executionAuthorized: true }, { evidenceIds: [] }, { evidenceIds: [evidence, evidence] }]) {
        assert.throws(() => api.planFrom({ ...plan(), ...change }, id, id), e => e.code === 'INVALID_RESPONSE')
      }
    })
    await t.test('四动作参数严格匹配，等待整数1..3600', () => {
      for (const value of [1, 3600]) assert.equal(api.planFrom({ ...plan(), action: 'WAIT_AND_RECHECK', parameters: { delaySeconds: value } }, id).parameters.delaySeconds, value)
      for (const value of [0, 3601, 1.5, '1', null, true]) assert.throws(() => api.planFrom({ ...plan(), action: 'WAIT_AND_RECHECK', parameters: { delaySeconds: value } }, id))
      assert.throws(() => api.planFrom({ ...plan(), parameters: { delaySeconds: 2 } }, id))
    })
    await t.test('false事实保留，旧摘要/配置/计划预检不能覆盖当前版', () => {
      const c = { id: evidence, planId: id, version: 1, digest: hash, configurationDigest: hash, createdAt: plan().createdAt, expiresAt: plan().expiresAt,
        passed: false, reasons: ['DEPENDENCY_UNAVAILABLE'], facts: {}, businessVerified: false, executionAuthorized: false, deferredChecks: ['CONFLICTING_EXECUTION','ATOMIC_EXECUTION_ELIGIBILITY'] }
      assert.equal(api.precheckFrom(c, plan()).passed, false)
      for (const change of [{ planId: evidence }, { version: 2 }, { digest: 'b'.repeat(64) }, { configurationDigest: 'b'.repeat(64) }, { passed: 'false' }]) assert.throws(() => api.precheckFrom({ ...c, ...change }, plan()))
    })
    await t.test('500、403、409、503和网络失败只发一次POST，保留trace', async () => {
      for (const status of [500, 403, 409, 503, 0]) {
        client.clearCsrf(); let writes = 0
        global.fetch = async (p, init) => {
          if (p.endsWith('/csrf')) return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'unit-csrf' })
          writes++; assert.equal(init.headers['X-CSRF-TOKEN'], 'unit-csrf'); assert.equal(init.headers['Content-Type'], 'application/json')
          assert.deepEqual(JSON.parse(init.body), { version: 1, digest: hash })
          if (status === 0) throw new Error('offline')
          return Response.json({ code: 'CONFLICT', message: 'untrusted-secret', traceId: 'unit-trace' }, { status })
        }
        await assert.rejects(api.precheck(plan()), e => e.status === status && !e.message.includes('untrusted-secret') && (status === 0 || e.traceId === 'unit-trace'))
        assert.equal(writes, 1)
      }
    })
    await t.test('401仍触发统一会话清除', async () => {
      let cleared = 0; client.setUnauthorizedHandler(() => cleared++)
      global.fetch = async () => Response.json({ code: 'UNAUTHENTICATED' }, { status: 401 })
      await assert.rejects(api.getPlan(id, id)); assert.equal(cleared, 1)
    })
  } finally { global.fetch = original }
})
test('登录返回精确计划深链接，禁止外部地址及未来执行入口', async () => {
  const source = fs.readFileSync(path.join(root, 'src/router.ts'), 'utf8'), ast = ts.createSourceFile('router.ts', source, ts.ScriptTarget.Latest, true)
  const declaration = ast.statements.find(s => ts.isFunctionDeclaration(s) && s.name?.text === 'loginDestination')
  const { loginDestination } = await import(url(declaration.getText(ast)))
  for (const p of [`/incidents/${id}/plans/${id}`, `/incidents/${id}/plans/new?previous=${id}`]) assert.equal(loginDestination(p), p)
  for (const p of ['//evil', 'https://evil', '/incidents\\evil', '/executions/future', `/incidents/${id}/plans/list`]) assert.equal(loginDestination(p), '/incidents')
})
