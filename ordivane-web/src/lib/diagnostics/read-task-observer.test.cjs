/** H2实际模块聚焦检查；不替代真实Chrome/业务链。 */
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path')
const ts=require('../../../node_modules/typescript')
const source=()=>fs.readFileSync(path.join(__dirname,'read-task-observer.ts'),'utf8')
const url=s=>`data:text/javascript;base64,${Buffer.from(ts.transpileModule(s.replaceAll('import.meta.env.DEV','false'),{compilerOptions:{target:ts.ScriptTarget.ES2023,module:ts.ModuleKind.ESNext}}).outputText).toString('base64')}`
let modulePromise;const actual=()=>modulePromise??=import(url(source()))
const eid='00000000-0000-4000-8000-000000000060',iid='00000000-0000-4000-8000-000000000030',pid='00000000-0000-4000-8000-000000000050'
const execPath=`/incidents/${iid}/executions/${eid}`
async function fixture(overrides={}){
 const m=await actual(),events=[];let now=100,serial=0
 const spec={version:1,documentId:'a'.repeat(32),executionId:eid,incidentId:iid,executionPath:execPath,deadlineAt:Date.now()+60000}
 const observer=m.createReadObserver(spec,e=>events.push(e),{dev:true,origin:'http://127.0.0.1:5175',path:execPath,now:()=>now,date:()=>100000+now,timeOrigin:100000,random:()=> (++serial).toString(16).padStart(32,'0'),...overrides})
 return {m,observer,events,scope:{instanceToken:'b'.repeat(32),executionId:eid,incidentId:iid,generation:1,readSequence:2},advance:ms=>now+=ms}
}
test('默认OFF与非任务文档不分配观测、不加头',async()=>{
 const m=await actual();assert.equal(m.getReadObserver(),null)
 for(const overrides of [{dev:false},{origin:'http://elsewhere'},{path:'/login'}]){const f=await fixture(overrides);assert.equal(f.observer,null);assert.equal(f.events.length,0)}
})
test('一任务三GET以同一外Signal、不同内Signal及token归属',async()=>{
 const f=await fixture(),o=f.observer,external=new AbortController(),t=o.admitRead(f.scope,external.signal,'INITIAL');o.bindPlan(t,pid)
 const paths=[`/api/v1/executions/${eid}`,`/api/v1/recovery-plans/${pid}`,`/api/v1/incidents/${iid}`]
 const hs=paths.map(p=>o.http(p,{signal:external.signal},new AbortController().signal))
 assert.equal(new Set(hs.map(h=>h.token)).size,3);assert.equal(new Set(hs.map(h=>h.internalSignalToken)).size,3)
 assert.deepEqual(hs.map(h=>h.role),['EXECUTION_GET','LINKED_PLAN_GET','LINKED_INCIDENT_GET'])
 assert.ok(hs.every(h=>h.ownerToken===t.token&&h.marker===h.token))
 assert.equal(f.events.filter(e=>e.kind==='task-admitted').length,1)
 assert.equal(o.http('/api/v1/access/csrf',{},new AbortController().signal),null)
})
test('取消桥退役与TAIL不造原生Promise落定时间',async()=>{
 const f=await fixture(),o=f.observer,a=new AbortController(),t=o.admitRead(f.scope,a.signal,'INITIAL'),inside=new AbortController()
 const h=o.http(`/api/v1/executions/${eid}`,{signal:a.signal},inside.signal)
 o.invalidate(a.signal,2,3,'ROUTE');a.abort();inside.abort();o.retire(a.signal,2,3)
 o.guard(t,'FINALLY',false,2,3);o.tail(t)
 assert.equal(o.state,'ACTIVE');assert.ok(f.events.some(e=>e.kind==='task-retired'))
 const tail=f.events.find(e=>e.kind==='task-tail');assert.equal(tail.detail.promiseSettlementObserved,false);assert.equal(tail.detail.settlementAt,null)
 assert.ok(f.events.some(e=>e.kind==='http-abort'&&e.detail.childToken===h.token))
})
test('旧W同epoch预留标记，首次关闭期限不续期且仅一次消费',async()=>{
 const f=await fixture(),o=f.observer,w=o.admitWrite(f.scope)
 o.closeAdmission('ROUTE');f.advance(9000);o.closeAdmission('UNMOUNT')
 const h=o.http(`/api/v1/executions/${eid}/cancellations`,{method:'POST'},new AbortController().signal,w)
 assert.equal(h.marker,w.reservedToken);assert.equal(h.ownerToken,w.token)
 assert.equal(o.http(`/api/v1/executions/${eid}/cancellations`,{method:'POST'},new AbortController().signal,w),null)
 assert.equal(o.state,'FAILED')
 const expired=await fixture(),ew=expired.observer.admitWrite(expired.scope);expired.observer.closeAdmission('ROUTE');expired.advance(10001)
 assert.equal(expired.observer.http(`/api/v1/executions/${eid}/cancellations`,{method:'POST'},new AbortController().signal,ew),null)
 assert.equal(expired.observer.state,'FAILED')
})
test('阶段期限与pagehide/seal禁止旧W，新写及伪owner不可续接',async()=>{
 const f=await fixture(),o=f.observer,w=o.admitWrite(f.scope);o.setDeadlineAt(100500);o.closeAdmission('ROUTE');f.advance(501)
 assert.equal(o.http(`/api/v1/executions/${eid}/cancellations`,{method:'POST'},new AbortController().signal,w),null)
 const b=await fixture(),bw=b.observer.admitWrite(b.scope);b.observer.seal('PAGEHIDE')
 assert.equal(b.observer.http(`/api/v1/executions/${eid}/cancellations`,{method:'POST'},new AbortController().signal,bw),null)
 const c=await fixture();c.observer.closeAdmission('ROUTE');assert.equal(c.observer.admitWrite(c.scope),null)
})
test('头冲突不覆盖原init；非白名单及preabort不加头',async()=>{
 const f=await fixture(),o=f.observer,a=new AbortController(),t=o.admitRead(f.scope,a.signal,'INITIAL')
 a.abort();const pre=o.http(`/api/v1/executions/${eid}`,{signal:a.signal},new AbortController().signal)
 assert.equal(pre.marker,null)
 const g=await fixture(),ga=new AbortController();g.observer.admitRead(g.scope,ga.signal,'INITIAL')
 const h=g.observer.http(`/api/v1/executions/${eid}`,{signal:ga.signal},new AbortController().signal)
 const original={'x-ordivane-diagnostic-request':'caller','X-CSRF-TOKEN':'test'}
 assert.equal(g.observer.headers(original,h),original);assert.equal(original['x-ordivane-diagnostic-request'],'caller');assert.equal(g.observer.state,'FAILED')
 const b=await fixture(),ba=new AbortController();b.observer.admitRead(b.scope,ba.signal,'INITIAL')
 const bh=b.observer.http(`/api/v1/executions/${eid}`,{signal:ba.signal},new AbortController().signal)
 assert.equal(new Headers(b.observer.headers({'X-CSRF-TOKEN':'test'},bh)).get('X-Ordivane-Diagnostic-Request'),bh.token)
})
test('sink异常/事件上限只关闭采样，保留业务调用方控制权',async()=>{
 const m=await actual();let calls=0
 const o=m.createReadObserver({version:1,documentId:'a'.repeat(32),executionId:eid,incidentId:iid,executionPath:execPath,deadlineAt:Date.now()+60000},()=>{calls++;throw Error('sink')},{dev:true,origin:'http://127.0.0.1:5175',path:execPath,now:()=>0,date:()=>0,timeOrigin:0,random:()=> 'c'.repeat(32)})
 assert.equal(o.state,'FAILED');assert.equal(calls,1)
 const f=await fixture();for(let i=0;i<2100;i++)f.observer.note('VISIBILITY');assert.equal(f.observer.state,'FAILED')
 f.observer.seal('CONTROLLER');assert.equal(f.observer.state,'FAILED')
})
test('正常轮询必须绑定真实timer来源，未知或已取消timer不能冒充正常周期',async()=>{
 const f=await fixture(),o=f.observer,a=new AbortController(),t=o.admitRead(f.scope,a.signal,'INITIAL')
 const timer=o.scheduleTimer(t,5000);o.tail(t);o.fireTimer(timer)
 const next=o.admitRead({...f.scope,readSequence:3},new AbortController().signal,'TIMER',timer)
 assert.ok(next);assert.ok(f.events.some(e=>e.kind==='task-admitted'&&e.detail.sourceToken===timer))
 const bad=await fixture(),bt=bad.observer.admitRead(bad.scope,new AbortController().signal,'INITIAL'),cancelled=bad.observer.scheduleTimer(bt,5000)
 bad.observer.cancelTimer(cancelled);bad.observer.tail(bt)
 assert.equal(bad.observer.admitRead({...bad.scope,readSequence:3},new AbortController().signal,'TIMER',cancelled),null)
 assert.equal(bad.observer.state,'FAILED')
})
