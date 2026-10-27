import { build } from 'esbuild';
import { mkdir, readFile, writeFile, copyFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const base = dirname(fileURLToPath(import.meta.url));
const target = resolve(base, '../app/src/main/assets/chem');
await mkdir(resolve(base, 'dist'), { recursive: true });
await build({
  entryPoints: [resolve(base, 'engine.mjs')],
  outfile: resolve(base, 'dist/engine.js'),
  bundle: true,
  format: 'iife',
  globalName: 'RikkaChem',
  platform: 'neutral',
  target: 'es2020',
  minify: true,
  legalComments: 'none',
});
await mkdir(target, { recursive: true });
await copyFile(resolve(base, 'dist/engine.js'), resolve(target, 'engine.js'));
const license = await readFile(resolve(base, 'node_modules/openchemlib/LICENSE'), 'utf8');
await writeFile(resolve(target, 'NOTICE.txt'), 'OpenChemLib JS 9.25.1\nhttps://github.com/cheminfo/openchemlib-js\n\n' + license);
console.log('engine.js', (await readFile(resolve(target, 'engine.js'))).length, 'bytes');
