// ADB/CDP drive the installed app and OS keyboard. Product traffic uses staging.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';

const mode = process.argv[2];
assert(['webview', 'chrome'].includes(mode), 'Choose webview or chrome');
const { chromium } = await import(process.env.FLOWNOTE_TEST_PLAYWRIGHT_MODULE
  || new URL('../../flownote/node_modules/playwright-core/index.mjs', import.meta.url).href);
const adbPath = process.env.FLOWNOTE_TEST_ADB || '/tmp/flownote-app-android-sdk/platform-tools/adb';
const adb = (...args) => execFileSync(adbPath, args, { encoding: 'utf8', timeout: 30000 });
const adbBytes = (...args) => execFileSync(adbPath, args, { timeout: 30000 });
const web = 'https://flownote-react-staging.vercel.app';
const api = 'https://flownote-api-staging.up.railway.app';
const directory = process.env.FLOWNOTE_TEST_ARTIFACTS || '/tmp/flownote-note-soft-ime';
mkdirSync(directory, { recursive: true });
const expectedHash = process.env.FLOWNOTE_TEST_ENTRY_SHA256;
assert.match(expectedHash ?? '', /^[a-f0-9]{64}$/, 'Provide the prepared staging entry SHA-256');
const pkg = mode === 'webview' ? 'com.flownote.mobile.staging' : 'com.android.chrome';
const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
async function until(check, label) {
  for (let attempt = 0; attempt < 60; attempt++) {
    if (await check()) return;
    await pause(250);
  }
  throw new Error('Native soft IME timeout: ' + label);
}
async function request(route, method = 'GET', body, token) {
  const response = await fetch(api + route, {
    method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(30000),
  });
  assert(response.ok, `${method} ${route}: ${response.status}`);
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}
function imeShown() {
  const state = adb('shell', 'dumpsys', 'input_method');
  return /mInputShown=true\b/.test(state) && /mInputViewStarted=true\b/.test(state);
}
function keyboardBounds() {
  const windows = adb('shell', 'dumpsys', 'window', 'windows');
  const section = windows.split(/Window #\d+ /).find(value => value.includes('InputMethod}')
    && value.includes('package=com.google.android.inputmethod.latin'));
  assert(section && /isVisible=true/.test(section), 'Gboard window must be visible');
  const match = section.match(/touchable region=SkRegion\(\((\d+),(\d+),(\d+),(\d+)\)\)/);
  assert(match, 'Visible keyboard touch region');
  return match.slice(1).map(Number);
}
function tapSoftA() {
  // Portrait en-US Gboard, measured against the actual keyboard screenshot.
  const [left, top, right, bottom] = keyboardBounds();
  assert.equal(right - left, 1080, 'Use the verified 1080px portrait emulator');
  assert(bottom - top > 600 && bottom - top < 1000, 'Verified Gboard geometry');
  adb('shell', 'input', 'tap', String(Math.round(left + (right - left) * 0.105)),
    String(Math.round(top + (bottom - top) * 0.405)));
}

let browser, token, noteId, cdp;
let noteCreated = false;
const initialImePreference = adb('shell', 'settings', 'get', 'secure', 'show_ime_with_hard_keyboard').trim();
const results = [];
try {
  adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1');
  if (mode === 'webview') {
    adb('shell', 'am', 'start', '-n', `${pkg}/com.flownote.mobile.MainActivity`);
    assert(adb('shell', 'dumpsys', 'package', pkg).includes('versionCode=13200'));
  } else adb('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', `${web}/login`, '-p', pkg);
  const port = mode === 'webview' ? 9223 : 9224;
  await until(() => {
    const pid = adb('shell', 'pidof', pkg).trim();
    if (!/^\d+$/.test(pid)) return false;
    const socket = mode === 'webview' ? `webview_devtools_remote_${pid}` : 'chrome_devtools_remote';
    if (!adb('shell', 'cat', '/proc/net/unix').includes(socket)) return false;
    adb('forward', `tcp:${port}`, `localabstract:${socket}`);
    return true;
  }, 'installed browser debug transport');
  browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`, { noDefaults: true });
  const context = browser.contexts()[0];
  const page = context.pages().find(value => value.url().startsWith(web)) || context.pages()[0];
  assert(page); page.setDefaultTimeout(45000);
  await page.bringToFront();
  assert.equal(await page.evaluate(() => document.visibilityState), 'visible', 'Automate the visible installed-browser tab');
  cdp = await context.newCDPSession(page);
  const send = (type, points) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: points });
  const tap = async point => { await send('touchStart', [{ ...point, id: 1 }]); await send('touchEnd', []); };
  await page.goto(`${web}/login`);
  const entryHash = await page.evaluate(async () => {
    const source = document.querySelector('script[type="module"][src]')?.src;
    if (!source) throw new Error('Staging entry missing');
    const response = await fetch(source, { cache: 'force-cache' });
    if (!response.ok) throw new Error('Staging entry unavailable');
    const bytes = await crypto.subtle.digest('SHA-256', await response.arrayBuffer());
    return Array.from(new Uint8Array(bytes), byte => byte.toString(16).padStart(2, '0')).join('');
  });
  assert.equal(entryHash, expectedHash, 'Installed browser executes the prepared staging entry');
  const email = `soft-ime-${randomUUID()}@example.com`, password = randomUUID();
  await request('/api/users', 'POST', { email, password, username: `si-${randomUUID().slice(0, 8)}`, nickname: 'Synthetic IME verification' });
  token = (await request('/api/users/login', 'POST', { email, password })).token;
  await page.getByLabel('이메일').fill(email);
  await page.getByLabel('비밀번호', { exact: true }).fill(password);
  if (imeShown()) {
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
    await until(() => !imeShown(), 'login keyboard dismissed');
  }
  await page.getByRole('button', { name: '계속하기', exact: true }).click();
  await page.waitForURL(url => url.origin === web && url.pathname !== '/login');
  noteId = randomUUID();
  const content = { format: 'code', version: 1, language: 'plaintext', encoding: 'utf-8',
    text: Array.from({ length: 40 }, (_, index) => `alpha bravo charlie ${index}`).join('\n') };
  await request('/api/notes', 'POST', { id: noteId, title: 'Synthetic soft IME.txt', content, revision: 1, client_id: randomUUID() }, token);
  noteCreated = true;
  await page.goto(`${web}/blog/${noteId}`);
  await page.getByRole('textbox', { name: '코드 편집기', exact: true }).waitFor();
  if (imeShown()) adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  const toolbar = page.getByRole('toolbar', { name: '코드 편집 도구', exact: true });
  const beforeToolbar = await toolbar.boundingBox();
  await page.getByRole('button', { name: '노트 확대', exact: true }).click();
  const afterToolbar = await toolbar.boundingBox();
  assert(Math.abs(beforeToolbar.height - afterToolbar.height) < 1, 'Code toolbar height stays fixed during body zoom');
  await page.getByRole('button', { name: '노트 확대율 초기화', exact: true }).click();
  for (let iteration = 0; iteration < 3; iteration++) {
    if (imeShown()) adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
    if (iteration > 0) {
      const box = await page.getByTestId('note-viewport').boundingBox();
      const center = { x: box.x + box.width / 2, y: box.y + Math.min(90, box.height / 2) };
      const one = { ...center, x: center.x - 25, id: 1 }, two = { ...center, x: center.x + 25, id: 2 };
      await send('touchStart', [one]); await send('touchStart', [one, two]);
      await send('touchMove', [{ ...one, x: one.x - 10 }, { ...two, x: two.x + 10 }]);
      await send('touchEnd', []);
      assert(Number(await page.getByTestId('note-viewport').getAttribute('data-zoom')) > 1);
      await page.getByRole('button', { name: '노트 이동', exact: true }).click();
      await send('touchStart', [{ ...center, id: 1 }]);
      await send('touchMove', [{ ...center, y: center.y - 55, id: 1 }]); await send('touchEnd', []);
    }
    await page.getByRole('button', { name: '노트 올가미', exact: true }).click();
    const word = await page.evaluate(() => {
      const viewport = document.querySelector('[data-testid="note-viewport"]').getBoundingClientRect();
      for (const line of document.querySelectorAll('.cm-line')) {
        const walker = document.createTreeWalker(line, NodeFilter.SHOW_TEXT);
        while (walker.nextNode()) {
          const node = walker.currentNode, index = node.textContent.indexOf('bravo');
          if (index < 0) continue;
          const range = document.createRange(); range.setStart(node, index); range.setEnd(node, index + 5);
          const box = range.getBoundingClientRect();
          if (box.top > viewport.top + 15 && box.bottom < viewport.bottom - 15) return box.toJSON();
        }
      }
      throw new Error('Visible synthetic bravo glyph missing');
    });
    const points = [
      { x: word.x + 0.1, y: word.y - 2 }, { x: word.x + word.width - 0.1, y: word.y - 2 },
      { x: word.x + word.width - 0.1, y: word.y + word.height + 2 }, { x: word.x + 0.1, y: word.y + word.height + 2 },
      { x: word.x + 0.1, y: word.y - 2 },
    ].map(point => ({ ...point, id: 1 }));
    await send('touchStart', [points[0]]);
    for (const point of points.slice(1)) await send('touchMove', [point]);
    await send('touchEnd', []);
    await until(async () => (await page.getByTestId('note-selection-status').textContent()).includes('5자 선택'), 'exact lasso selection');
    const before = await request(`/api/notes/${noteId}`, 'GET', undefined, token);
    await page.getByRole('button', { name: '노트 편집', exact: true }).click();
    if (imeShown()) adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
    await tap({ x: word.x + word.width / 2, y: word.y + word.height / 2 });
    await until(imeShown, 'OS keyboard opens after lasso/edit/body touch');
    const target = await page.evaluate(() => {
      const selection = getSelection();
      if (!selection?.rangeCount) throw new Error('Native input selection missing');
      const range = selection.getRangeAt(0);
      const start = range.startContainer.nodeType === Node.ELEMENT_NODE ? range.startContainer : range.startContainer.parentElement;
      const end = range.endContainer.nodeType === Node.ELEMENT_NODE ? range.endContainer : range.endContainer.parentElement;
      const line = start.closest('.cm-line');
      if (!line || end.closest('.cm-line') !== line) throw new Error('Expected one selected code line');
      const prefix = document.createRange(); prefix.selectNodeContents(line); prefix.setEnd(range.startContainer, range.startOffset);
      return { line: line.textContent, from: prefix.toString().length, selected: range.toString() };
    });
    const lines = before.content.text.split('\n');
    const lineIndex = lines.findIndex(line => line === target.line);
    assert(lineIndex >= 0 && lines.filter(line => line === target.line).length === 1, 'Unique synthetic input line');
    const offset = lines.slice(0, lineIndex).reduce((length, line) => length + line.length + 1, 0) + target.from;
    assert.equal(before.content.text.slice(offset, offset + target.selected.length), target.selected, 'DOM input range matches saved source');
    const expectedText = before.content.text.slice(0, offset) + 'a' + before.content.text.slice(offset + target.selected.length);
    const keyboard = keyboardBounds();
    writeFileSync(`${directory}/${mode}-keyboard-${iteration + 1}.png`, adbBytes('exec-out', 'screencap', '-p'));
    tapSoftA();
    let saved;
    await until(async () => {
      saved = await request(`/api/notes/${noteId}`, 'GET', undefined, token);
      return saved.revision > before.revision;
    }, 'soft-keyboard character saved');
    const nextText = saved.content.text;
    assert.equal(nextText, expectedText, 'Actual soft a key replaces exactly the native DOM selection and preserves all surrounding code');
    assert.deepEqual(saved.content, { ...before.content, text: nextText });
    results.push({ iteration: iteration + 1, keyboardShown: true, keyboard, revision: saved.revision, nativeSelectionLength: target.selected.length,
      sourceSha256: createHash('sha256').update(nextText).digest('hex'), afterPinchAndScroll: iteration > 0 });
    console.log(JSON.stringify({ mode, ...results.at(-1) }));
  }
  if (imeShown()) adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  await page.reload(); await page.getByRole('textbox', { name: '코드 편집기', exact: true }).waitFor();
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'No outer horizontal overflow');
  console.log(JSON.stringify({ mode, result: 'PASS', iterations: results.length, entryHash }));
} finally {
  try {
    await cdp?.detach(); await browser?.close();
    if (noteCreated) await request(`/api/notes/${noteId}`, 'DELETE', undefined, token);
  } finally {
    try {
      if (token) {
        await request('/api/users/logout-all', 'POST', undefined, token);
        const response = await fetch(api + '/api/users/me', { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(30000) });
        assert.equal(response.status, 401, 'Synthetic sessions revoked');
      }
    } finally {
      if (initialImePreference === 'null') adb('shell', 'settings', 'delete', 'secure', 'show_ime_with_hard_keyboard');
      else adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', initialImePreference);
      assert.equal(adb('shell', 'settings', 'get', 'secure', 'show_ime_with_hard_keyboard').trim(), initialImePreference);
    }
  }
}
