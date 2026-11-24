import assert from 'node:assert/strict';
import { mkdir, readFile } from 'node:fs/promises';
import { chromium } from 'playwright';

const browser = await chromium.launch({ headless: true });
const origin = 'https://appassets.androidplatform.net';
const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
const failures = [];
page.on('pageerror', error => failures.push(error.message));
await page.addInitScript(() => { globalThis.messages = []; globalThis.NativeEditor = { postMessage: text => globalThis.messages.push(JSON.parse(text)) }; });
await page.route('**/*', async route => {
  const files = { '/assets/editor/index.html': ['index.html', 'text/html'], '/assets/editor/editor.css': ['editor.css', 'text/css'], '/assets/editor/vendor/editor.js': ['vendor/editor.js', 'application/javascript'] };
  const entry = files[new URL(route.request().url()).pathname];
  if (!entry) return route.abort();
  const assets = entry[0].startsWith('vendor/') ? '../build/generated/terminalAssets/' : '../src/main/assets/';
  await route.fulfill({ body: await readFile(new URL(`${assets}editor/${entry[0]}`, import.meta.url)), contentType: entry[1] });
});
const deliver = (message, source = origin) => page.evaluate(({message, source}) => window.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(message), origin: source })), {message, source});
const snapshot = async () => {
  await deliver({ type: 'snapshot', epoch: 1, id: 'test' });
  return page.evaluate(() => messages.filter(m => m.type === 'snapshot').at(-1).content);
};
try {
  await page.goto(`${origin}/assets/editor/index.html`);
  await page.waitForFunction(() => messages.some(m => m.type === 'ready'));
  const content = '// 한글\r\nexport const value = 42;\r\n';
  await deliver({ type: 'load', epoch: 1, path: 'src/main.ts', content });
  await page.locator('.cm-content').waitFor();
  assert.equal(await snapshot(), content, 'CRLF must survive an unchanged snapshot');
  assert.ok(await page.locator('.cm-line span').count(), 'syntax highlighting must be present');
  await page.locator('.cm-content').click();
  await page.keyboard.press('Control+End');
  await page.keyboard.insertText('// 모바일 수정');
  await page.waitForFunction(() => messages.some(m => m.type === 'change' && m.content.includes('모바일 수정')));
  const changed = await snapshot();
  await deliver({ type: 'undo', epoch: 1 }); assert.equal(await snapshot(), content);
  await deliver({ type: 'redo', epoch: 1 }); assert.equal(await snapshot(), changed);
  await deliver({ type: 'load', epoch: 1, path: 'README.md', content: '# File two\n' });
  assert.deepEqual(await page.evaluate(() => { const m = messages.filter(x => x.type === 'cursor').at(-1); return [m.path, m.line, m.column]; }), ['README.md', 1, 1]);
  await deliver({ type: 'load', epoch: 1, path: 'src/main.ts', content: changed });
  await deliver({ type: 'undo', epoch: 1 }); assert.equal(await snapshot(), content, 'tab switch must preserve undo history');
  await deliver({ type: 'load', epoch: 1, path: 'src/main.ts', content: 'injected' }, 'https://foreign.invalid');
  assert.equal(await snapshot(), content);
  await deliver({ type: 'load', epoch: 1, path: 'native.txt', content: 'native-message' }, '');
  assert.equal(await snapshot(), 'native-message', 'Android native messages may have an empty source origin');
  await page.locator('.cm-content').click();
  await page.keyboard.insertText('x'.repeat(256 * 1024 + 1));
  assert.equal(await snapshot(), 'native-message');
  assert.ok(await page.evaluate(() => messages.some(m => m.type === 'limit')), 'oversized edits must show an error and preserve the current document');
  const windowsContent = 'x\r\n'.repeat(60000);
  await deliver({ type: 'load', epoch: 1, path: 'windows.txt', content: windowsContent });
  await page.locator('.cm-content').click();
  await page.keyboard.insertText('x'.repeat(256 * 1024 - windowsContent.length + 1));
  assert.equal(await snapshot(), windowsContent, 'size limits must count the CRLF bytes that will actually be saved');
  await deliver({ type: 'load', epoch: 1, path: 'src/main.ts', content });
  await deliver({ type: 'search', epoch: 1 });
  await page.locator('input[name=search]').fill('value');
  await page.locator('input[name=replace]').fill('result');
  await page.locator('button[name=replaceAll]').click();
  assert.ok((await snapshot()).includes('const result = 42'));
  await page.keyboard.press('Escape');
  await deliver({ type: 'load', epoch: 2, path: 'src/main.ts', content });
  await deliver({ type: 'undo', epoch: 2 });
  await deliver({ type: 'snapshot', epoch: 2, id: 'new-host' });
  assert.equal(await page.evaluate(() => messages.filter(m => m.id === 'new-host').at(-1).content), content, 'new PC must not inherit undo state');
  await deliver({ type: 'load', epoch: 1, path: 'safe.html', content: '<script>globalThis.executed=true</script>\n' });
  assert.equal(await page.evaluate(() => globalThis.executed), undefined, 'source code must never execute');
  await deliver({ type: 'load', epoch: 1, path: 'src/project.ts', content: 'interface Task {\n  id: number;\n  title: string;\n  completed: boolean;\n}\n\n// 모바일에서 편집 중인 프로젝트\nexport function pending(tasks: Task[]) {\n  return tasks\n    .filter(task => !task.completed)\n    .map(task => ({\n      ...task,\n      title: task.title.trim(),\n    }));\n}\n\nconst tasks: Task[] = [];\nconsole.log(pending(tasks));\n' });
  await mkdir(new URL('../build/editor-screenshots/', import.meta.url), { recursive: true });
  for (const [width, height] of [[390,844],[844,390],[800,1280],[1280,800]]) {
    await page.setViewportSize({ width, height });
    await deliver({ type: 'wrap', epoch: 1, enabled: true });
    assert.ok(await page.locator('.cm-content').isVisible());
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: new URL(`../build/editor-screenshots/${width}x${height}.png`, import.meta.url).pathname });
  }
  assert.deepEqual(failures, []);
  console.log('CodeMirror: typing, Korean, CRLF, undo/redo, tabs, search/replace, origin/PC isolation and responsive viewports passed.');
} finally { await browser.close(); }
