/** 仅验证前端边界；桩不能替代真实业务链。 */
const { test } = require('node:test')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const ts = require('../../../node_modules/typescript')
global.window = globalThis
const encode = source => `data:text/javascript;base64,${Buffer.from(ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2023, module: ts.ModuleKind.ESNext } }).outputText).toString('base64')}`
function load(file) { return import(encode(fs.readFileSync(path.join(__dirname, file), 'utf8').replace(/^import type .*$/gm, ''))) }
test('意图跨刷新保持原键，身份隔离，存储丢失拒绝新建', async () => {
  const { IntentStore } = await load('intent.ts')
  const data = new Map(), storage = { getItem: k => data.get(k) ?? null, setItem: (k,v) => data.set(k,v) }
  const binding = { identityId: 'operator', planId: 'plan', version: 1, digest: 'a'.repeat(64) }
  const store = new IntentStore(storage), intent = store.confirm(binding)
  assert.equal(new IntentStore(storage).confirm(binding).key, intent.key)
  store.save({...intent,executionId:'known-execution'});assert.equal(new IntentStore(storage).read(binding).executionId,'known-execution')
  assert.notEqual(store.confirm({ ...binding, identityId: 'other' }).key, intent.key)
  data.delete(store.keyFor(binding))
  assert.throws(() => store.confirm(binding), /未确认/)
  assert.throws(() => new IntentStore(storage).confirm(binding), /未确认/)
  assert.throws(() => new IntentStore({ getItem() { throw Error('denied') }, setItem() {} }).confirm(binding), /未确认/)
})
test('轮询遵守WAIT期限与UNKNOWN预算，终态停止', async () => {
  const { pollingDelay } = await load('polling.ts')
  const now = Date.now(), base = { status:'WAITING', nextCheckAt:new Date(now+60000).toISOString(), verificationDeadline:null, verificationCount:0, verificationLimit:6, actionCompleted:false, deliveryState:null }
  assert.equal(pollingDelay(base, now), 60000)
  assert.equal(pollingDelay({ ...base, status:'UNKNOWN', nextCheckAt:null, verificationCount:6 }, now), null)
  assert.equal(pollingDelay({ ...base, status:'UNKNOWN' }, now), 60000)
  for(const status of ['SUCCEEDED','CANCELLED','MANUAL_REQUIRED']) assert.equal(pollingDelay({ ...base,status,actionCompleted:true },now),null)
})
test('实际执行事件处理器合并重复点击，迟到POST不清新对象busy或导航', async () => {
  const source=fs.readFileSync(path.join(__dirname,'../recovery-plans/RecoveryPlan.vue'),'utf8').match(/<script[^>]*>([\s\S]*?)<\/script>/)[1]
  const ast=ts.createSourceFile('page.ts',source,ts.ScriptTarget.Latest,true)
  const declaration=ast.statements.find(s=>ts.isFunctionDeclaration(s)&&s.name?.text==='execute').getText(ast)
  const harness=await import(encode(`
    let generation=0, calls=0, navigations=0, release;
    const busy={value:false},writeError={value:null},executionMessage={value:''},knownExecutionId={value:''},firstExecution={value:true};
    const session={identity:{id:'operator'}}, router={async push(){navigations++}};
    const exact=p=>({version:p.version,digest:p.digest}),closeConfirmation=()=>{};
    const intentStore={read:()=>null,confirm:b=>({...b,key:'stable',executionId:null}),save:()=>{}};
    const pending=new Promise(r=>release=r);
    const createExecution=async()=>{calls++;return pending},getExecution=createExecution;
    class HttpFailure extends Error {}
    ${declaration}
    export {execute};
    export const state=()=>({calls,navigations,busy:busy.value});
    export const switchObject=()=>{generation++;busy.value=true};
    export const finish=v=>release(v);
  `))
  const plan={id:'plan',version:1,digest:'a'.repeat(64),incidentId:'incident',status:'APPROVED',expiresAt:new Date(Date.now()+60000).toISOString()}
  const first=harness.execute(plan,'operator');await harness.execute(plan,'operator')
  assert.equal(harness.state().calls,1)
  harness.switchObject();harness.finish({id:'execution',planId:plan.id,version:1,digest:plan.digest,requesterId:'operator'});await first
  assert.deepEqual(harness.state(),{calls:1,navigations:0,busy:true})
})
test('实际可见性处理器清理timer并中止GET，恢复先刷新；不冒充真实Chrome隐藏测试', async()=>{
  const source=fs.readFileSync(path.join(__dirname,'ExecutionDetail.vue'),'utf8').match(/<script[^>]*>([\s\S]*?)<\/script>/)[1]
  const ast=ts.createSourceFile('page.ts',source,ts.ScriptTarget.Latest,true)
  const functions=['active','clearRead','visibility'].map(name=>ast.statements.find(s=>ts.isFunctionDeclaration(s)&&s.name?.text===name).getText(ast)).join('\n')
  const h=await import(encode(`
    const {getReadObserver}=await import(${JSON.stringify(encode(fs.readFileSync(path.join(__dirname,'../../lib/diagnostics/read-task-observer.ts'),'utf8').replaceAll('import.meta.env.DEV','false')))});
    const observer=getReadObserver();let observationTimer=null;
    const document={visibilityState:'visible'},navigator={onLine:true},window=globalThis;
    const loading={value:true},stopped={value:''};let timer,readSequence=0,controller=new AbortController(),calls=0;
    const original=controller;async function load(){calls++}
    ${functions}
    export const hide=()=>{document.visibilityState='hidden';visibility()};
    export const show=()=>{document.visibilityState='visible';visibility()};
    export const halt=()=>{stopped.value='stopped'};
    export const state=()=>({calls,aborted:original.signal.aborted,loading:loading.value,readSequence});
  `))
  h.hide();assert.deepEqual(h.state(),{calls:0,aborted:true,loading:false,readSequence:1});h.show();assert.equal(h.state().calls,1);h.halt();h.hide();h.show();assert.equal(h.state().calls,1)
})
