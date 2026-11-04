import {before, after, beforeEach, test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {initializeTestEnvironment, assertFails, assertSucceeds} from '@firebase/rules-unit-testing';
import {doc, getDoc, getDocFromServer, setDoc, runTransaction, collection, query, orderBy, getDocs} from 'firebase/firestore';

// Fail closed: these tests must never use an authenticated production endpoint.
const endpoint = process.env.FIRESTORE_EMULATOR_HOST;
if (!endpoint || !/^127\.0\.0\.1:8085$|^localhost:8085$/.test(endpoint)) throw Error('Isolated Firestore emulator required');
let env;
before(async () => { env = await initializeTestEnvironment({projectId:'demo-distillpod-sync', firestore:{
  host:'127.0.0.1', port:8085, rules:await readFile('firestore.rules','utf8')}}); });
after(async()=>env?.cleanup());
beforeEach(async()=>env.clearFirestore());
const db = uid => env.authenticatedContext(uid).firestore();
const control = uid => `users/${uid}`;
async function seed(uid='alice',protocol=1) { await env.withSecurityRulesDisabled(async ctx=>{
  await setDoc(doc(ctx.firestore(),control(uid)),{generation:'g1',sequence:0,active:true,protocol});
}); }
const validPayload={subscribed:'true',podcastId:'podcast1',feedUrl:'https://example.test/feed',originalFeedUrl:'https://example.test/feed',title:'Show',author:'Author'};
async function commit(database, {uid='alice',id='op1',key='subscription_item1',baseVersion=0,payload=validPayload,deleted=false,generation='g1'}={}) {
 let observedSequence;
 for(let attempt=0;attempt<5;attempt++) {
 try { return await runTransaction(database,async tx=>{
  const headRef=doc(database,control(uid)), entityRef=doc(database,`${control(uid)}/entities/${key}`), receiptRef=doc(database,`${control(uid)}/receipts/${id}`);
  const [head,entity,receipt]=await Promise.all([tx.get(headRef),tx.get(entityRef),tx.get(receiptRef)]);
  observedSequence=head.data().sequence;
  const signature=JSON.stringify({key,baseVersion,payload,deleted,generation});
  if(receipt.exists()) { if(receipt.data().signature!==signature) throw Error('Operation ID reused'); return receipt.data(); }
  if(head.data().generation!==generation || !head.data().active) throw Error('Account unavailable');
  if((entity.exists()?entity.data().version:0)!==baseVersion) throw Error('Conflict');
  const sequence=head.data().sequence+1;
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
   if(!latest.active || latest.generation!==generation || latest.sequence<=observedSequence) throw error;
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
