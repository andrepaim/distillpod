import {before, after, beforeEach, test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {initializeTestEnvironment, assertFails, assertSucceeds} from '@firebase/rules-unit-testing';
import {doc, getDoc, getDocFromServer, setDoc, updateDoc, deleteDoc, runTransaction, writeBatch, where, limit, collection, query, orderBy, getDocs} from 'firebase/firestore';

// Fail closed: these tests must never use an authenticated production endpoint.
const endpoint = process.env.FIRESTORE_EMULATOR_HOST;
if (!endpoint || !/^127\.0\.0\.1:8085$|^localhost:8085$/.test(endpoint)) throw Error('Isolated Firestore emulator required');
// DISTILLPOD_FIRESTORE_RULES selects another rules file, for example the currently deployed protocol-3
// rules (DISTILLPOD_FIRESTORE_RULES_PROTOCOL=3), to prove clients keep working before a rules deployment.
const rulesFile = process.env.DISTILLPOD_FIRESTORE_RULES ?? 'firestore.rules';
const deployedProtocol3 = process.env.DISTILLPOD_FIRESTORE_RULES_PROTOCOL === '3';
const protocol4 = deployedProtocol3 ? {skip:'protocol-4 compaction rules not loaded'} : {};
let env;
before(async () => { env = await initializeTestEnvironment({projectId:'demo-distillpod-sync', firestore:{
  host:'127.0.0.1', port:8085, rules:await readFile(rulesFile,'utf8')}}); });
after(async()=>env?.cleanup());
beforeEach(async()=>env.clearFirestore());
const db = uid => env.authenticatedContext(uid).firestore();
const control = uid => `users/${uid}`;
async function seed(uid='alice',protocol=1,extra={}) { await env.withSecurityRulesDisabled(async ctx=>{
  await setDoc(doc(ctx.firestore(),control(uid)),{generation:'g1',sequence:0,active:true,protocol,...extra});
}); }
// Fixture-only shortcut for a long history: the rules-disabled admin context moves the head.
async function adminHead(uid,fields) { await env.withSecurityRulesDisabled(async ctx=>updateDoc(doc(ctx.firestore(),control(uid)),fields)); }
const sha256 = value => createHash('sha256').update(value,'utf8').digest('hex');
const validPayload={subscribed:'true',podcastId:'podcast1',feedUrl:'https://example.test/feed',originalFeedUrl:'https://example.test/feed',title:'Show',author:'Author'};
async function commit(database, {uid='alice',id='op1',key='subscription_item1',baseVersion=0,payload=validPayload,deleted=false,generation='g1',fullSignature=false}={}) {
 let observedSequence, staged=false;
 for(let attempt=0;attempt<5;attempt++) {
 staged=false;
 try { return await runTransaction(database,async tx=>{
  staged=false;
  const headRef=doc(database,control(uid)), entityRef=doc(database,`${control(uid)}/entities/${key}`), receiptRef=doc(database,`${control(uid)}/receipts/${id}`);
  const [head,entity,receipt]=await Promise.all([tx.get(headRef),tx.get(entityRef),tx.get(receiptRef)]);
  observedSequence=head.data().sequence;
  const canonical=JSON.stringify({key,baseVersion,payload,deleted,generation});
  // Protocol 4 stores only the canonical signature's SHA-256; either stored form identifies the operation.
  const signature=head.data().protocol>=4 && !fullSignature ? sha256(canonical) : canonical;
  if(receipt.exists()) { if(![canonical,sha256(canonical)].includes(receipt.data().signature)) throw Error('Operation ID reused'); return receipt.data(); }
  if(head.data().generation!==generation || !head.data().active) throw Error('Account unavailable');
  if((entity.exists()?entity.data().version:0)!==baseVersion) throw Error('Conflict');
  const sequence=head.data().sequence+1; staged=true;
  const value={version:baseVersion+1,payload:deleted?{}:payload,deleted,generation,sequence,operationId:id};
  const result={signature,key,version:value.version,sequence,generation};
  tx.update(headRef,{sequence}); tx.set(entityRef,value);
  tx.set(doc(database,`${control(uid)}/changes/${String(sequence)}`),{...value,key}); tx.set(receiptRef,result);
  return result;
 }); } catch(error) {
   // Rules can observe a competing commit before the SDK precondition retry.
   // Retry only after proving the same account head advanced; never ignore denial.
   if(error.code!=='permission-denied' || observedSequence===undefined || attempt===4) throw error;
   const latest=(await getDocFromServer(doc(database,control(uid)))).data();
   if(!latest.active || latest.generation!==generation) throw error;
   // Without head advancement, refused staged writes are this operation's deterministic rejection;
   // FirebaseSubscriptionCloud reports it as SyncOperationRejected and the outbox continues.
   if(latest.sequence<=observedSequence) { if(staged) error.rejected=true; throw error; }
 }
 }
}
test('owner commit and retry produce one change',async()=>{await seed();const a=db('alice');const first=await commit(a);assert.deepEqual(await commit(a),first);assert.equal((await getDocs(collection(a,'users/alice/changes'))).size,1);});
test('two devices commit unrelated changes with unique sequences',async()=>{await seed();await Promise.all([commit(db('alice')),commit(db('alice'),{id:'op2',key:'subscription_item2'})]);assert.deepEqual((await getDocs(query(collection(db('alice'),'users/alice/changes'),orderBy('sequence')))).docs.map(x=>x.data().sequence),[1,2]);});
test('stale device cannot resurrect tombstone',async()=>{await seed();const a=db('alice');await commit(a);await commit(a,{id:'delete1',baseVersion:1,deleted:true});await assert.rejects(commit(a,{id:'stale1'}),/Conflict/);assert.equal((await getDoc(doc(a,'users/alice/entities/subscription_item1'))).data().deleted,true);});
test('operation ID cannot be reused with different payload',async()=>{await seed();const a=db('alice');await commit(a);await assert.rejects(commit(a,{payload:{subscribed:'false'}}),/reused/);});
test('unauthenticated and other user cannot read or list',async()=>{await seed();await commit(db('alice'));for(const database of [db('bob'),env.unauthenticatedContext().firestore()]) {await assertFails(getDoc(doc(database,'users/alice')));await assertFails(getDocs(collection(database,'users/alice/entities')));}});
test('owner cannot bypass transaction with direct entity write',async()=>{await seed();await assertFails(setDoc(doc(db('alice'),'users/alice/entities/subscription_item1'),{version:1,payload:{},deleted:false,generation:'g1',sequence:1,operationId:'op1'}));});
test('owner cannot change account generation',async()=>{await seed();await assertFails(setDoc(doc(db('alice'),'users/alice'),{generation:'g2',sequence:0,active:true,protocol:1}));});
test('receipt and changes cannot be overwritten',async()=>{await seed();const a=db('alice');await commit(a);await assertFails(setDoc(doc(a,'users/alice/receipts/op1'),{signature:'bad'}));await assertFails(setDoc(doc(a,'users/alice/changes/1'),{sequence:100}));});
test('unexpected entity fields rejected',async()=>{await seed();const a=db('alice');await assertFails(setDoc(doc(a,'users/alice/entities/subscription_item1'),{accessToken:'secret'}));});

test('numeric subscription title is rejected',async()=>{await seed();await assertFails(commit(db('alice'),{payload:{...validPayload,title:42}}));});
test('oversized subscription title is rejected',async()=>{await seed();await assertFails(commit(db('alice'),{payload:{...validPayload,title:'x'.repeat(1001)}}));});
test('incomplete subscription snapshot is rejected',async()=>{await seed();await assertFails(commit(db('alice'),{payload:{subscribed:'true'}}));});
test('public HTTP RSS addresses remain supported',async()=>{await seed();await assertSucceeds(commit(db('alice'),{payload:{...validPayload,feedUrl:'http://example.test/feed',originalFeedUrl:'http://example.test/feed'}}));});
test('public path containing at sign is not treated as userinfo',async()=>{await seed();await assertSucceeds(commit(db('alice'),{payload:{...validPayload,feedUrl:'https://example.test/@show/rss',originalFeedUrl:'https://example.test/@show/rss'}}));});
test('embedded URL credentials are rejected',async()=>{await seed();await assertFails(commit(db('alice'),{payload:{...validPayload,feedUrl:'https://user:password@example.test/rss'}}));});

const episodeIdentity={episodeId:'episode1',podcastId:'podcast1',feedUrl:'https://example.test/feed',originalFeedUrl:'https://example.test/feed',podcastTitle:'Show',podcastAuthor:'Author',guid:'guid1',title:'Episode',audioUrl:'https://example.test/audio',durationMs:'100000',fingerprint:'sha'};
const progress={...episodeIdentity,positionMs:'10000',sessionId:'session1',deviceId:'device1',deviceSequence:'1'};
const moment={...episodeIdentity,momentId:'moment1',startMs:'0',endMs:'10000',excerpt:'Source words',quote:'Source',insight:'Saved idea',revision:'r1',createdAt:'1',model:'tiny'};
const completion={...episodeIdentity,completed:'false',origin:'explicit',sessionId:'session1'};
test('portable progress and rewind snapshots use revision checks',async()=>{await seed();const a=db('alice');await assertSucceeds(commit(a,{key:'progress_episode1',payload:progress}));await assertSucceeds(commit(a,{key:'progress_episode1',id:'rewind',baseVersion:1,payload:{...progress,positionMs:'1000',deviceSequence:'2'}}));await assert.rejects(commit(a,{key:'progress_episode1',id:'stale',baseVersion:1,payload:progress}),/Conflict/);});
test('portable moment create and deletion are idempotent',async()=>{await seed();const a=db('alice');await assertSucceeds(commit(a,{key:'moment_moment1',payload:moment}));await commit(a,{key:'moment_moment1',id:'delete',baseVersion:1,deleted:true});await assert.rejects(commit(a,{key:'moment_moment1',id:'stale',baseVersion:1,payload:moment}),/Conflict/);});
test('explicit completion snapshots remain independent of position',async()=>{await seed();await assertSucceeds(commit(db('alice'),{key:'completion_episode1',payload:completion}));});
test('portable identity and field types cannot be forged',async()=>{await seed();const a=db('alice');for(const [i,payload] of [{...progress,positionMs:'-1'},{...progress,episodeId:'other'},{...progress,positionMs:42},{...progress,localFile:'/private/audio'},{...progress,audioUrl:'https://example.test/audio?token=secret'}].entries())await assertFails(commit(a,{key:'progress_episode1',id:`bad${i}`,payload}));});
test('moment job states and invalid intervals are rejected',async()=>{await seed();const a=db('alice');await assertFails(commit(a,{key:'moment_moment1',payload:{...moment,state:'running'}}));await assertFails(commit(a,{key:'moment_moment1',payload:{...moment,startMs:'10001'}}));});

test('queue order and episode identities are versioned without playback state',async()=>{await seed('alice',2);const a=db('alice');await assertSucceeds(commit(a,{key:'episode_episode1',payload:episodeIdentity}));await assertSucceeds(commit(a,{key:'queue_library',id:'queue',payload:{items:'episode1,episode2'}}));});
test('portable preferences have independent typed documents',async()=>{await seed('alice',2);const a=db('alice');await assertSucceeds(commit(a,{key:'preference_model',payload:{value:'base'}}));await assertSucceeds(commit(a,{key:'preference_language',id:'lang',payload:{value:'pt'}}));});
test('queue cannot carry private URLs or playback commands',async()=>{await seed('alice',2);const a=db('alice');await assertFails(commit(a,{key:'queue_library',payload:{items:'https://secret.test/audio?token=secret'}}));await assertFails(commit(a,{key:'queue_library',payload:{items:'episode1',playing:'true'}}));});
test('preferences exclude identity device policies and invalid values',async()=>{await seed('alice',2);const a=db('alice');for(const [key,value] of [['token','secret'],['wifi','false'],['model','bad'],['language','bad']])await assertFails(commit(a,{key:`preference_${key}`,payload:{value}}));});

test('finished content manifest stores only public identity and a verified descriptor',async()=>{await seed('alice',2);await assertSucceeds(commit(db('alice'),{key:'artifact_episode1',payload:{...episodeIdentity,hash:'a'.repeat(64),expandedBytes:'1000',schema:'1'}}));});
test('full-scope protocol upgrade is owner-only monotonic and keeps generation',async()=>{await seed();const a=db('alice');await assertSucceeds(setDoc(doc(a,'users/alice'),{generation:'g1',sequence:0,active:true,protocol:2}));await assertFails(setDoc(doc(a,'users/alice'),{generation:'g1',sequence:0,active:true,protocol:1}));await assertFails(setDoc(doc(db('bob'),'users/alice'),{generation:'g1',sequence:0,active:true,protocol:2}));});
test('old protocol cannot publish record types older clients cannot read',async()=>{await seed();await assertFails(commit(db('alice'),{key:'preference_model',payload:{value:'tiny'}}));});

test('contextual moments require protocol three and cannot be downgraded by old writers',async()=>{
 await seed('alice',2);const a=db('alice');const newer={...moment,momentSchema:'2',sourceJson:'{}',quote:''};
 await assertFails(commit(a,{key:'moment_moment1',payload:newer}));
 await assertSucceeds(setDoc(doc(a,'users/alice'),{generation:'g1',sequence:0,active:true,protocol:3}));
 await assertSucceeds(commit(a,{key:'moment_moment1',payload:newer}));
 await assertFails(commit(a,{key:'moment_moment1',id:'old',baseVersion:1,payload:moment}));
 await assertSucceeds(commit(a,{key:'moment_moment1',id:'delete',baseVersion:1,deleted:true}));
 await assertFails(setDoc(doc(a,'users/alice'),{generation:'g1',sequence:2,active:true,protocol:2}));
});
test('protocol three validates moment schema and bounds metadata',async()=>{
 await seed('alice',3);const a=db('alice');
 for(const [i,extra] of [{momentSchema:'3',sourceJson:''},{momentSchema:'2'},{sourceJson:''},{momentSchema:'2',sourceJson:'x'.repeat(32769)}].entries())
  await assertFails(commit(a,{key:'moment_moment1',id:`bad-context-${i}`,payload:{...moment,...extra}}));
 await assertSucceeds(commit(a,{key:'moment_moment1',payload:{...moment,momentSchema:'2',sourceJson:'',quote:''}}));
});

test('rules refusal of a staged write is a deterministic operation rejection and later work continues',async()=>{
 await seed();const a=db('alice');
 const refused=await commit(a,{key:'preference_model',payload:{value:'tiny'}}).then(()=>assert.fail('Write accepted'),error=>error);
 assert.equal(refused.code,'permission-denied');assert.equal(refused.rejected,true);
 assert.equal((await getDocFromServer(doc(a,'users/alice'))).data().sequence,0);
 const accepted=await commit(a,{id:'op2'});assert.equal(accepted.sequence,1);
});
test('denied account reads are not operation rejections',async()=>{
 await seed();
 const denied=await commit(db('bob'),{uid:'alice'}).then(()=>assert.fail('Read allowed'),error=>error);
 assert.equal(denied.code,'permission-denied');assert.notEqual(denied.rejected,true);
});

// History compaction (protocol 4, issue #176). The deployed protocol-3 rules must refuse all of it.
test('deployed protocol-3 rules refuse the compaction upgrade, watermark and deletes',{skip:!deployedProtocol3&&'only with deployed protocol-3 rules'},async()=>{
 await seed('alice',3);const a=db('alice');await commit(a);
 await assertFails(updateDoc(doc(a,'users/alice'),{protocol:4}));
 await assertFails(updateDoc(doc(a,'users/alice'),{compactedThrough:1}));
 await assertFails(deleteDoc(doc(a,'users/alice/changes/1')));
 await assertFails(deleteDoc(doc(a,'users/alice/receipts/op1')));
});
test('rolling back to the deployed protocol-3 rules keeps a compacted account syncing',{skip:!deployedProtocol3&&'only with deployed protocol-3 rules'},async()=>{
 await seed('alice',4,{compactedThrough:0});const a=db('alice');
 const first=await commit(a);assert.match(first.signature,/^[a-f0-9]{64}$/);
 assert.deepEqual(await commit(a),first);assert.equal((await commit(a,{id:'op2',key:'subscription_item2'})).sequence,2);
});
test('protocol four upgrade is owner-only and only from protocol three',protocol4,async()=>{
 for(const protocol of [1,2]) { await seed('alice',protocol);await assertFails(updateDoc(doc(db('alice'),'users/alice'),{protocol:4})); }
 await seed('alice',3);
 await assertFails(updateDoc(doc(db('bob'),'users/alice'),{protocol:4}));
 await assertFails(updateDoc(doc(db('alice'),'users/alice'),{protocol:4,compactedThrough:0}));
 await assertFails(updateDoc(doc(db('alice'),'users/alice'),{protocol:5}));
 await assertSucceeds(updateDoc(doc(db('alice'),'users/alice'),{protocol:4}));
 await assertFails(updateDoc(doc(db('alice'),'users/alice'),{protocol:3}));
});
test('watermark is owner-only monotonic and trails the head by the retained window',protocol4,async()=>{
 await seed('alice',4,{sequence:10050});const a=db('alice');const head=doc(a,'users/alice');
 await assertFails(updateDoc(doc(db('bob'),'users/alice'),{compactedThrough:10}));
 for(const bad of [51,'10',10.5,-1,0]) await assertFails(updateDoc(head,{compactedThrough:bad}));
 await assertFails(updateDoc(head,{compactedThrough:10,sequence:10051}));
 await assertFails(updateDoc(head,{compactedThrough:10,active:false}));
 await assertSucceeds(updateDoc(head,{compactedThrough:10}));
 await assertFails(updateDoc(head,{compactedThrough:10}));
 await assertFails(updateDoc(head,{compactedThrough:9}));
 await assertSucceeds(updateDoc(head,{compactedThrough:50}));
 // Ordinary commits continue and leave the watermark alone.
 assert.equal((await commit(a)).sequence,10051);assert.equal((await getDocFromServer(head)).data().compactedThrough,50);
});
test('watermark needs protocol four and an active account',protocol4,async()=>{
 await seed('alice',3,{sequence:20000});
 await assertFails(updateDoc(doc(db('alice'),'users/alice'),{compactedThrough:10}));
 await seed('alice',4,{sequence:20000,active:false});
 await assertFails(updateDoc(doc(db('alice'),'users/alice'),{compactedThrough:10}));
});
async function compactedAccount() {
 await seed('alice',4);const a=db('alice');
 for(const [i,key] of ['subscription_item1','subscription_item2','subscription_item3'].entries())await commit(a,{id:`op${i+1}`,key});
 // A long history: the head moved far enough that sequences 1 and 2 may be compacted.
 await adminHead('alice',{sequence:10003});
 await assertSucceeds(updateDoc(doc(a,'users/alice'),{compactedThrough:2}));
 return a;
}
test('owner deletes changes and receipts only at or below the watermark',protocol4,async()=>{
 const a=await compactedAccount();
 for(const path of ['changes/3','receipts/op3','changes/10003','changes/abc','changes/02'])await assertFails(deleteDoc(doc(a,`users/alice/${path}`)));
 for(const database of [db('bob'),env.unauthenticatedContext().firestore()])
  for(const path of ['changes/1','receipts/op1'])await assertFails(deleteDoc(doc(database,`users/alice/${path}`)));
 for(const path of ['changes/1','changes/2','receipts/op1','receipts/op2'])await assertSucceeds(deleteDoc(doc(a,`users/alice/${path}`)));
 // Repeated deletes after an interruption are harmless.
 await assertSucceeds(deleteDoc(doc(a,'users/alice/receipts/op1')));await assertSucceeds(deleteDoc(doc(a,'users/alice/changes/1')));
 assert.deepEqual((await getDocs(collection(a,'users/alice/changes'))).docs.map(x=>x.id),['3']);
 assert.deepEqual((await getDocs(collection(a,'users/alice/receipts'))).docs.map(x=>x.id),['op3']);
});
test('compaction never deletes current state or the account head',protocol4,async()=>{
 const a=await compactedAccount();
 for(const path of ['entities/subscription_item1','entities/subscription_item3','']) await assertFails(deleteDoc(doc(a,`users/alice${path?'/'+path:''}`)));
 assert.equal((await getDocs(collection(a,'users/alice/entities'))).size,3);
});
test('compaction deletes are refused before protocol four even with a watermark field',protocol4,async()=>{
 await seed('alice',3);const a=db('alice');await commit(a);
 await adminHead('alice',{sequence:20000,compactedThrough:5});
 await assertFails(deleteDoc(doc(a,'users/alice/changes/1')));await assertFails(deleteDoc(doc(a,'users/alice/receipts/op1')));
});
test('protocol four receipts carry only a sha256 signature and commits continue after compaction',protocol4,async()=>{
 const a=await compactedAccount();
 assert.match((await getDoc(doc(a,'users/alice/receipts/op3'))).data().signature,/^[a-f0-9]{64}$/);
 await assertFails(commit(a,{id:'full',key:'subscription_item4',fullSignature:true}));
 const next=await commit(a,{id:'op4',key:'subscription_item4'});assert.equal(next.sequence,10004);
 assert.deepEqual(await commit(a,{id:'op4',key:'subscription_item4'}),next);
});
test('protocol-3 receipts keep their full signature and stay retryable after the upgrade',protocol4,async()=>{
 await seed('alice',3);const a=db('alice');const first=await commit(a);
 assert.equal(first.signature,JSON.stringify({key:'subscription_item1',baseVersion:0,payload:validPayload,deleted:false,generation:'g1'}));
 await assertSucceeds(updateDoc(doc(a,'users/alice'),{protocol:4}));
 assert.deepEqual(await commit(a),first);
});
test('client batches delete the oldest compacted history and refuse a batch reaching past the watermark',protocol4,async()=>{
 await seed('alice',4);const a=db('alice');
 for(let i=1;i<=12;i++)await commit(a,{id:`op${i}`,key:`subscription_item${i}`});
 await adminHead('alice',{sequence:10012});await assertSucceeds(updateDoc(doc(a,'users/alice'),{compactedThrough:11}));
 const oldest=async name=>(await getDocs(query(collection(a,`users/alice/${name}`),where('sequence','<=',12),orderBy('sequence'),limit(12)))).docs;
 for(const name of ['changes','receipts']) {
  const docs=await oldest(name);
  const tooFar=writeBatch(a);docs.forEach(d=>tooFar.delete(d.ref));await assertFails(tooFar.commit());
  assert.equal((await oldest(name)).length,12);
  const batch=writeBatch(a);docs.slice(0,10).forEach(d=>batch.delete(d.ref));await assertSucceeds(batch.commit());
  assert.deepEqual((await oldest(name)).map(d=>d.data().sequence),[11,12]);
 }
});
