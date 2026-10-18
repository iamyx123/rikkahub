import {build} from 'esbuild';
import {mkdir,readFile,writeFile,copyFile} from 'node:fs/promises';
import {resolve,dirname} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
const base=dirname(fileURLToPath(import.meta.url));
const dist=resolve(base,'dist');
await mkdir(dist,{recursive:true});
await build({entryPoints:[resolve(base,'engine.mjs')],outfile:resolve(dist,'engine.mjs'),bundle:true,format:'esm',platform:'neutral',target:'es2022',conditions:['browser'],define:{'process.env.OPENUI_TELEMETRY_DISABLED':'"1"','process.env.OPENUI_RUNTIME_TELEMETRY_ENABLED':'"0"'},minify:true});
await build({entryPoints:[resolve(base,'engine.mjs')],outfile:resolve(dist,'engine.js'),bundle:true,format:'iife',globalName:'RikkaOpenUI',platform:'neutral',target:'es2022',conditions:['browser'],define:{'process.env.OPENUI_TELEMETRY_DISABLED':'"1"','process.env.OPENUI_RUNTIME_TELEMETRY_ENABLED':'"0"'},minify:true});
const {library,promptOptions}=await import(pathToFileURL(resolve(dist,'engine.mjs')));
const prompt=library.prompt(promptOptions);
for(const target of [resolve(base,'../app/src/main/assets/intelligent-ui'),resolve(base,'../web-ui/public/intelligent-ui')]) {
  await mkdir(target,{recursive:true});
  await copyFile(resolve(dist,'engine.js'),resolve(target,'engine.js'));
  await writeFile(resolve(target,'prompt.txt'),prompt);
  await writeFile(resolve(target,'NOTICE.txt'),'OpenUI Lang Core 1.0.1 and adapted Open Intelligent UI functions, MIT.\nhttps://github.com/thesysdev/openui\nhttps://github.com/thesysdev/open-intelligent-ui/tree/03213439358bf3408ad61a46ec9e3230c5572e48\n\n'+await readFile(resolve(base,'vendor/LICENSE'),'utf8')+'\n\nOpenUI Lang Core:\n'+await readFile(resolve(base,'node_modules/@openuidev/lang-core/LICENSE'),'utf8')+'\n\nZod:\n'+await readFile(resolve(base,'node_modules/zod/LICENSE'),'utf8'));
}
console.log(JSON.stringify({engineBytes:(await readFile(resolve(dist,'engine.js'))).length,promptCharacters:prompt.length,components:Object.keys(library.components).length,functions:Object.keys(library.functions).length}));
