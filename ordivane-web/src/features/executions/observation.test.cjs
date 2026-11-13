/** 抽取实际async处理器并装载实际HTTP/observer；仅前端聚焦测试。 */
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path')
const ts=require('../../../node_modules/typescript')
const eid='00000000-0000-4000-8000-000000000060',iid='00000000-0000-4000-8000-000000000030',pid='00000000-0000-4000-8000-000000000050',event='00000000-0000-4000-8000-000000000001',digest='a'.repeat(64)
const executionPath=`/incidents/${iid}/executions/${eid}`
const url=s=>`data:text/javascript;base64,${Buffer.from(ts.transpileModule(s,{compilerOptions:{target:ts.ScriptTarget.ES2023,module:ts.ModuleKind.ESNext}}).outputText).toString('base64')}`
let serial=0
async function harness({active=true,sinkThrow=false}={}){
 const events=[],pageEvents=new Map(),old={window:global.window,document:global.document,location:global.location,fetch:global.fetch}
 global.window={setTimeout,clearTimeout,addEventListener:(k,fn)=>pageEvents.set(k,fn)};global.window.top=global.window
 global.document={visibilityState:'visible'};global.location={origin:'http://127.0.0.1:5175',pathname:executionPath}
 global[Symbol.for('ordivane.readTaskObservation.bootstrap.v1')]=()=>({spec:{version:1,documentId:'a'.repeat(32),executionId:eid,incidentId:iid,executionPath,deadlineAt:Date.now()+60000},sink:e=>{if(sinkThrow)throw Error('sink');events.push(e)}})
 const observerUrl=url(fs.readFileSync(path.join(__dirname,'../../lib/diagnostics/read-task-observer.ts'),'utf8').replaceAll('import.meta.env.DEV',String(active))+`\n// instance ${++serial}`)
 const clientUrl=url(fs.readFileSync(path.join(__dirname,'../../lib/http/client.ts'),'utf8').replaceAll("'../diagnostics/read-task-observer'",JSON.stringify(observerUrl)))
 const client=await import(clientUrl),observerModule=await import(observerUrl)
 const script=fs.readFileSync(path.join(__dirname,'ExecutionDetail.vue'),'utf8').match(/<script[^>]*>([\s\S]*?)<\/script>/)[1]
 const ast=ts.createSourceFile('page.ts',script,ts.ScriptTarget.Latest,true)
 const declarations=['active','clearRead','schedule','load','cancel'].map(name=>ast.statements.find(s=>ts.isFunctionDeclaration(s)&&s.name?.text===name).getText(ast)).join('\n')
 const source=`
  const {HttpFailure}=await import(${JSON.stringify(clientUrl)});
  const observer=(await import(${JSON.stringify(observerUrl)})).getReadObserver(),observationInstance=observer?.instance()??null;
  const navigator={onLine:true},id={value:${JSON.stringify(eid)}},incidentId={value:${JSON.stringify(iid)}};
  let generation=1,readSequence=1,failures=0,timer,controller=null,observationTimer=null;
  const execution={value:null},plan={value:null},incident={value:null},loading={value:false},busy={value:false},error={value:null},writeError={value:null},notice={value:''},stopped={value:''},updated={value:''};
  const canCancel={value:true},heading={value:null};let scheduled=0,focuses=0;
  const window={setTimeout(){return ++scheduled},clearTimeout(){}},nextTick=()=>Promise.resolve(),pollingDelay=()=>5000,closeConfirmation=()=>{};
  const client=await import(${JSON.stringify(clientUrl)});
  const getExecution=async(id,signal)=>(await client.requestResult('/api/v1/executions/'+id,{signal})).body;
  const getPlan=async(i,id,signal)=>client.request('/api/v1/recovery-plans/'+id,{signal});
  const getIncident=async(id,signal)=>client.request('/api/v1/incidents/'+id,{signal});
  const cancelExecution=async(id,owner)=>{const r=await client.executionWrite('/api/v1/executions/'+id+'/cancellations',undefined,undefined,owner);return {status:r.status,execution:r.body}};
  ${declarations}
  export {load,cancel};
  export const leave=()=>{observer?.closeAdmission('ROUTE');generation++;clearRead('ROUTE');execution.value=null;busy.value=true;notice.value='new-object'};
  export const state=()=>({generation,readSequence,loading:loading.value,busy:busy.value,notice:notice.value,error:error.value,execution:execution.value,scheduled});
  export const cleanup=()=>{observer?.seal('CONTROLLER');clearRead()};
 `
 const h=await import(url(source))
 const requests=[]
 global.fetch=async(p,init)=>{
  requests.push({path:p,init})
  if(p.endsWith('/csrf'))return Response.json({headerName:'X-CSRF-TOKEN',token:'unit-token'})
  if(p.includes('/recovery-plans/'))return Response.json({id:pid,version:1,digest})
  if(p.includes('/incidents/'))return Response.json({id:iid,event_id:event})
  return Response.json({id:eid,planId:pid,version:1,digest,eventId:event,status:'QUEUED'})
 }
 return {h,events,requests,client,observer:observerModule.getReadObserver(),setFetch:fn=>global.fetch=fn,restore(){h.cleanup();Object.assign(global,old);delete global[Symbol.for('ordivane.readTaskObservation.bootstrap.v1')]}}
}
test('实际load保留async/TailOnly，一K三子调用，TAIL位于HTTP/finally与调度之后',async()=>{
 const f=await harness();try{
  await f.h.load(false,false,'INITIAL')
  assert.equal(f.h.state().loading,false);assert.equal(f.h.state().execution.id,eid)
  assert.equal(f.events.filter(e=>e.kind==='task-admitted').length,1)
  assert.deepEqual(f.events.filter(e=>e.kind==='http-registered').map(e=>e.detail.role),['EXECUTION_GET','LINKED_PLAN_GET','LINKED_INCIDENT_GET'])
  const tail=f.events.findLast(e=>e.kind==='task-tail');assert.deepEqual(tail.detail.pendingChildTokens,[]);assert.equal(f.events.at(-1),tail)
  assert.ok(f.events.find(e=>e.kind==='effect'&&e.detail.effect==='TIMER').seq<tail.seq)
  assert.ok(f.requests.every(r=>new Headers(r.init.headers).has('X-Ordivane-Diagnostic-Request')))
  const source=fs.readFileSync(path.join(__dirname,'ExecutionDetail.vue'),'utf8');assert.match(source,/async function load\(/);assert.doesNotMatch(source,/loadCore|allSettled|\.then\(/)
 }finally{f.restore()}
})
test('实际load旧成功/错误/finally均不得污染新对象',async()=>{
 for(const mode of ['success','error']){
  const f=await harness();try{
   let release;const pending=new Promise(r=>release=r),base=global.fetch
   f.setFetch(async(p,i)=>{if(p===`/api/v1/executions/${eid}`){await pending;if(mode==='error')throw Error('late');}return base(p,i)})
   const loading=f.h.load(false,false,'INITIAL');f.h.leave();const before=f.h.state();release();await loading
   assert.equal(f.h.state().busy,true);assert.equal(f.h.state().notice,'new-object');assert.equal(f.h.state().execution,null);assert.equal(f.h.state().scheduled,before.scheduled)
   const guards=f.events.filter(e=>e.kind==='guard');assert.ok(guards.some(e=>e.detail.stage==='ERROR'&&e.detail.decision==='SKIP'))
   assert.ok(guards.some(e=>e.detail.stage==='FINALLY'&&e.detail.decision==='SKIP'))
   assert.ok(f.events.some(e=>e.kind==='task-retired'))
  }finally{f.restore()}
 }
})
test('实际取消W跨CSRF await离页后保留准确owner和一次标记，CSRF不带头',async()=>{
 const f=await harness();try{
  await f.h.load(false,false,'INITIAL')
  let release;const pending=new Promise(r=>release=r),base=global.fetch;let posts=0
  f.setFetch(async(p,i)=>{if(p.endsWith('/csrf')){assert.equal(new Headers(i.headers).has('X-Ordivane-Diagnostic-Request'),false);await pending;return Response.json({headerName:'X-CSRF-TOKEN',token:'unit-token'})}if(p.endsWith('/cancellations')){posts++;assert.equal(i.body,undefined);assert.equal(new Headers(i.headers).has('Content-Type'),false);assert.match(new Headers(i.headers).get('X-Ordivane-Diagnostic-Request'),/^[0-9a-f]{32}$/);return Response.json({status:'CANCELLED',cancelledAt:'2026-10-27T00:00:00Z'})}return base(p,i)})
  const writing=f.h.cancel();f.h.leave();release();await writing
  assert.equal(posts,1);assert.equal(f.h.state().busy,true);assert.equal(f.h.state().notice,'new-object')
  const w=f.events.find(e=>e.kind==='write-admitted'),child=f.events.find(e=>e.kind==='http-registered'&&e.detail.role==='CANCEL_POST')
  assert.equal(child.detail.ownerToken,w.detail.writeToken);assert.equal(child.detail.childToken,w.detail.reservedToken)
  assert.ok(f.events.filter(e=>e.kind==='write-guard').every(e=>e.detail.decision==='SKIP'))
 }finally{f.restore()}
})
test('OFF与观测sink失败都不改实际load结果，登录等请求没有诊断头',async()=>{
 for(const options of [{active:false},{sinkThrow:true}]){
  const f=await harness(options);try{await f.h.load();assert.equal(f.h.state().execution.id,eid);assert.ok(f.requests.every(r=>!new Headers(r.init.headers).has('X-Ordivane-Diagnostic-Request')))}finally{f.restore()}
 }
})
test('实际取消POST迟到拒绝的error/finally不写新对象，也不重查',async()=>{
 const f=await harness();try{
  await f.h.load(false,false,'INITIAL');let release;const pending=new Promise(r=>release=r),base=global.fetch
  f.setFetch(async(p,i)=>{if(p.endsWith('/cancellations')){await pending;return Response.json({code:'CSRF_REJECTED',traceId:eid},{status:403})}return base(p,i)})
  const writing=f.h.cancel();await new Promise(r=>setTimeout(r,0));f.h.leave();const scheduled=f.h.state().scheduled;release();await writing
  assert.equal(f.h.state().busy,true);assert.equal(f.h.state().notice,'new-object');assert.equal(f.h.state().scheduled,scheduled)
  assert.ok(f.events.some(e=>e.kind==='write-guard'&&e.detail.stage==='ERROR'&&e.detail.decision==='SKIP'))
  assert.ok(f.events.some(e=>e.kind==='write-guard'&&e.detail.stage==='FINALLY'&&e.detail.decision==='SKIP'))
 }finally{f.restore()}
})
