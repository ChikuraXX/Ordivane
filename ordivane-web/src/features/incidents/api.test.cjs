/** 严格案件状态与nullable解决投影，不代替真实联调。 */
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path')
const ts=require('../../../node_modules/typescript')
const url=s=>`data:text/javascript;base64,${Buffer.from(ts.transpileModule(s,{compilerOptions:{target:ts.ScriptTarget.ES2023,module:ts.ModuleKind.ESNext}}).outputText).toString('base64')}`
const observer=url(fs.readFileSync(path.join(__dirname,'../../lib/diagnostics/read-task-observer.ts'),'utf8').replaceAll('import.meta.env.DEV','false'))
const client=url(fs.readFileSync(path.join(__dirname,'../../lib/http/client.ts'),'utf8').replaceAll("'../diagnostics/read-task-observer'",JSON.stringify(observer)))
const api=url(fs.readFileSync(path.join(__dirname,'api.ts'),'utf8').replace("'../../lib/http/client'",JSON.stringify(client)))
test('仅接受OPEN/RESOLVED，解决字段缺失、false、0不转换为null',async()=>{
  const {incidentFrom}=await import(api),base={id:'i',source:'s',event_id:'e',handler_id:'h',status:'OPEN',created_at:'t',resolved_at:null,resolved_execution_id:null,resolution_observation_id:null}
  assert.equal(incidentFrom(base).resolved_at,null)
  assert.equal(incidentFrom({...base,status:'RESOLVED'}).status,'RESOLVED')
  for(const status of ['UNKNOWN','CLOSED','',null])assert.throws(()=>incidentFrom({...base,status}))
  for(const key of ['resolved_at','resolved_execution_id','resolution_observation_id'])for(const value of [undefined,false,0,'','invalid'])assert.throws(()=>incidentFrom({...base,[key]:value}))
})
