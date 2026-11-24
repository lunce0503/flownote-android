import { cp, mkdir, readFile } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { build } from 'esbuild';
const assets = pathToFileURL(resolve(process.argv[2] ?? fileURLToPath(new URL('../build/generated/terminalAssets', import.meta.url))) + sep);
const target = new URL('terminal/vendor/', assets);
await mkdir(target, { recursive: true });
for (const [source, name] of [
  ['@xterm/xterm/lib/xterm.js', 'xterm.js'],
  ['@xterm/xterm/css/xterm.css', 'xterm.css'],
  ['@xterm/addon-fit/lib/addon-fit.js', 'addon-fit.js'],
  ['@xterm/xterm/LICENSE', 'LICENSE-xterm'],
  ['@xterm/addon-fit/LICENSE', 'LICENSE-addon-fit'],
]) await cp(new URL(`node_modules/${source}`, import.meta.url), new URL(name, target));
const editor = new URL('editor/vendor/', assets);
await mkdir(editor, { recursive: true });
await build({ entryPoints: [fileURLToPath(new URL('editor.mjs', import.meta.url))], outfile: fileURLToPath(new URL('editor.js', editor)),
  bundle: true, format: 'iife', target: ['chrome90'], minify: true, legalComments: 'eof' });
await mkdir(new URL('licenses/', editor), { recursive: true });
const lock = JSON.parse(await readFile(new URL('package-lock.json', import.meta.url), 'utf8'));
for (const [dependency, metadata] of Object.entries(lock.packages)) {
  if (!dependency || metadata.dev || !dependency.startsWith('node_modules/')) continue;
  const name = dependency.slice('node_modules/'.length).replaceAll('/', '-');
  for (const license of ['LICENSE', 'LICENSE.txt']) {
    try { await cp(new URL(`${dependency}/${license}`, import.meta.url), new URL(`licenses/${name}.txt`, editor)); break; }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
}
