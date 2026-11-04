import {before,after,beforeEach,test} from 'node:test';
import {readFile} from 'node:fs/promises';
import {initializeTestEnvironment,assertSucceeds,assertFails} from '@firebase/rules-unit-testing';
import {doc,setDoc} from 'firebase/firestore';
import {randomBytes} from 'node:crypto';
import {ref,uploadBytes,getBytes,deleteObject,list} from 'firebase/storage';
if(!/^(127\.0\.0\.1|localhost):9199$/.test(process.env.FIREBASE_STORAGE_EMULATOR_HOST??''))throw Error('Isolated Storage emulator required');
let env;
before(async()=>{env=await initializeTestEnvironment({projectId:'demo-distillpod-sync',firestore:{host:'127.0.0.1',port:8085,rules:await readFile('firestore.rules','utf8')},storage:{host:'127.0.0.1',port:9199,rules:await readFile('storage.rules','utf8')}})});
after(async()=>env?.cleanup());
beforeEach(async()=>{hash=randomBytes(32).toString('hex');path=`users/alice/g1/${hash}.gz`;metadata={contentType:'application/gzip',customMetadata:{hash,expandedBytes:'100',schema:'1'}};await env.clearStorage();await env.clearFirestore();await env.withSecurityRulesDisabled(async c=>{await setDoc(doc(c.firestore(),'users/alice'),{generation:'g1',active:true,sequence:0,protocol:1})})});
let hash,path,metadata;
const storage=uid=>uid?env.authenticatedContext(uid).storage():env.unauthenticatedContext().storage();
test('owner can create and read immutable content',async()=>{const item=ref(storage('alice'),path);await assertSucceeds(uploadBytes(item,new Uint8Array([1,2,3]),metadata));await assertSucceeds(getBytes(item));await assertFails(uploadBytes(item,new Uint8Array([4]),metadata));await assertFails(deleteObject(item));});
test('other user and anonymous callers cannot access content',async()=>{await uploadBytes(ref(storage('alice'),path),new Uint8Array([1]),metadata);for(const uid of ['bob',null]){await assertFails(getBytes(ref(storage(uid),path)));await assertFails(uploadBytes(ref(storage(uid),path),new Uint8Array([1]),metadata));}});
test('stale generation and oversized or malformed metadata are denied',async()=>{const a=storage('alice');await assertFails(uploadBytes(ref(a,path.replace('/g1/','/g0/')),new Uint8Array([1]),metadata));await assertFails(uploadBytes(ref(a,path),new Uint8Array([1]),{...metadata,customMetadata:{...metadata.customMetadata,expandedBytes:'33554433'}}));await assertFails(uploadBytes(ref(a,path),new Uint8Array([1]),{...metadata,customMetadata:{...metadata.customMetadata,token:'secret'}}));});

test('readiness probe can list only the active owner generation',async()=>{await assertSucceeds(list(ref(storage('alice'),'users/alice/g1'),{maxResults:1}));await assertFails(list(ref(storage('bob'),'users/alice/g1'),{maxResults:1}));await assertFails(list(ref(storage('alice'),'users/alice/g0'),{maxResults:1}));});
