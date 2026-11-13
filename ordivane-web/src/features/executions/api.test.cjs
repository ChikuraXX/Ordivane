/** 执行HTTP边界、取消无正文与主动GET取消回归。 */
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path')
const ts=require('../../../node_modules/typescript');global.window=globalThis
const url=s=>`data:text/javascript;base64,${Buffer.from(ts.transpileModule(s,{compilerOptions:{target:ts.ScriptTarget.ES2023,module:ts.ModuleKind.ESNext}}).outputText).toString('base64')}`
const observer=url(fs.readFileSync(path.join(__dirname,'../../lib/diagnostics/read-task-observer.ts'),'utf8').replaceAll('import.meta.env.DEV','false'))
const client=url(fs.readFileSync(path.join(__dirname,'../../lib/http/client.ts'),'utf8').replaceAll("'../diagnostics/read-task-observer'",JSON.stringify(observer)))
const plan=url(fs.readFileSync(path.join(__dirname,'../recovery-plans/api.ts'),'utf8').replace("'../../lib/http/client'",JSON.stringify(client)))
const raw=url(fs.readFileSync(path.join(__dirname,'raw-json.ts'),'utf8'))
const api=url(fs.readFileSync(path.join(__dirname,'api.ts'),'utf8').replace("'../../lib/http/client'",JSON.stringify(client)).replace("'../recovery-plans/api'",JSON.stringify(plan)).replace("'./raw-json'",JSON.stringify(raw)))
const id='00000000-0000-4000-8000-000000000060',hash='a'.repeat(64)
const fixture=()=>({id,planId:id,requesterId:id,version:1,digest:hash,eventId:id,businessKey:'key',fingerprint:hash,transportFingerprint:hash,status:'QUEUED',eligibilityHeld:true,businessVerified:false,actionCompleted:false,action:'REPLAY_ORIGINAL',reason:'PENDING',checks:{CONFLICTING_EXECUTION:'PASS',ATOMIC_EXECUTION_ELIGIBILITY:'PASS'},createdAt:'2026-10-23T01:00:00Z',attempts:[],verificationCount:0,verificationLimit:6,nextCheckAt:null,verificationDeadline:null,cancelRequestedAt:null,cancelledAt:null,releasedAt:null,observations:[],verificationAttempts:[],timeline:[],deliveryState:'PENDING',incidentStatus:'OPEN'})
test('十状态严格解码及缺失/null/false/0',async()=>{
 const a=await import(api)
 for(const status of Object.keys(a.statusNames)){const v={...fixture(),status};if(status==='SUCCEEDED'){v.businessVerified=true;v.actionCompleted=true}if(status==='CANCELLED'){v.eligibilityHeld=false;v.cancelledAt=v.createdAt}assert.equal(a.executionFrom(v,id).status,status)}
 for(const change of [{status:'VERIFYING'},{status:'FAILED'},{verificationCount:'0'},{businessVerified:null},{nextCheckAt:undefined},{deliveryState:'ACK'},{status:'SUCCEEDED'},{id:'wrong'}])assert.throws(()=>a.executionFrom({...fixture(),...change},id))
 assert.equal(a.executionFrom(fixture()).verificationCount,0)
})
test('取消无正文且保留202；409/500/网络仅一次原键原体',async()=>{
 const a=await import(api),c=await import(client),old=global.fetch
 try{
  global.fetch=async(p,init)=>p.endsWith('/csrf')?Response.json({headerName:'X-CSRF-TOKEN',token:'test'}):(assert.equal(init.body,undefined),assert.equal(init.headers['Content-Type'],undefined),Response.json(fixture(),{status:202}))
  assert.equal((await a.cancelExecution(id)).status,202)
  for(const status of [409,500,0]){let count=0;global.fetch=async(p,init)=>{count++;assert.equal(init.headers['Idempotency-Key'],'stable');assert.deepEqual(JSON.parse(init.body),{version:1,digest:hash});if(!status)throw Error('offline');return Response.json({code:'CONFLICT',message:'unsafe',traceId:id},{status})};await assert.rejects(a.createExecution({identityId:id,planId:id,version:1,digest:hash,key:'stable',executionId:null}));assert.equal(count,1)}
  let aborted=false;global.fetch=(p,init)=>new Promise((resolve,reject)=>init.signal.addEventListener('abort',()=>{aborted=true;reject(new DOMException('cancel','AbortError'))}))
  const controller=new AbortController(),pending=c.request('/api/test',{signal:controller.signal});controller.abort();await assert.rejects(pending,e=>e.name==='AbortError');assert.equal(aborted,true)
 }finally{global.fetch=old}
})

test('原始观测大整数、转义及嵌套JSON不舍入',async()=>{const {rawAt}=await import(raw);const content='{ "n": 9007199254740993, "nested": [{"quote":"a\\\"b"}], "zero":0, "no":false }';const source='{"observations":[{"content":'+content+'}]}';JSON.parse(source);assert.equal(rawAt(source,['observations',0,'content']),content)})
test('旧会话迟到401不得注销已经更新的会话',async()=>{
 const c=await import(client),old=global.fetch;let release,invalidated=0
 try{c.setUnauthorizedHandler(()=>invalidated++);global.fetch=()=>new Promise(r=>release=r);const pending=c.request('/api/old',{method:'POST'});c.clearCsrf();release(Response.json({code:'UNAUTHENTICATED'},{status:401}));await assert.rejects(pending);assert.equal(invalidated,0)}finally{global.fetch=old}
})
