import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';

const url='https://tysnnn23.github.io/Ketvoice/admin/ketvoice-admin.html';
const normalize=text=>text.replace(/\r\n/g,'\n');
const sha=text=>createHash('sha256').update(normalize(text)).digest('hex');
const local=await readFile(new URL('../admin/ketvoice-admin.html',import.meta.url),'utf8');
const response=await fetch(url+'?studio-check='+Date.now(),{cache:'no-store'});
assert.equal(response.status,200);
const remote=await response.text();
assert.equal(sha(remote),sha(local),'Published Studio must match the validated local HTML (UTF-8/LF SHA-256)');
console.log(JSON.stringify({url,status:response.status,sha256_utf8_lf:sha(remote),ownerSession:false,publicationTest:false},null,2));
