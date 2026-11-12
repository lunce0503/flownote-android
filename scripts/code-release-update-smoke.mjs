// Synthetic staging account only. All application requests use fixed cloud origins.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { randomUUID, createHash, verify } from 'node:crypto';
import { chromium } from '../../flownote/node_modules/playwright-core/index.mjs';

const root = new URL('../../', import.meta.url).pathname;
const adbPath = process.env.FLOWNOTE_TEST_ADB || '/tmp/flownote-app-android-sdk/platform-tools/adb';
const adb = (...args) => execFileSync(adbPath, args, { encoding: 'utf8', timeout: 60000 });
const bytes = (...args) => execFileSync(adbPath, args, { timeout: 60000 });
const pkg = 'com.flownote.mobile.staging';
const web = 'https://flownote-react-staging.vercel.app';
const api = 'https://flownote-api-staging.up.railway.app';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(check, label) {
  for (let index = 0; index < 90; index++) {
    if (await check()) return;
    await pause(1000);
  }
  throw new Error(`Android signed upgrade timeout: ${label}`);
}
function ui() {
  adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/code-release-update.xml');
  return adb('shell', 'cat', '/sdcard/code-release-update.xml');
}
function tapNode(node) {
  assert(node, 'Required system control is missing');
  const [, left, top, right, bottom] = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  adb('shell', 'input', 'tap', String((+left + +right) / 2), String((+top + +bottom) / 2));
}
function tap(labels) {
  tapNode((ui().match(/<node\b[^>]*>/g) || []).find(node => labels.some(label => node.includes(`text="${label}"`))));
}
let browser, page;
async function connect() {
  await until(() => {
    const pid = adb('shell', `pidof ${pkg} || true`).trim();
    if (!/^\d+$/.test(pid)) {
      adb('shell', 'am', 'start', '-n', `${pkg}/com.flownote.mobile.MainActivity`); return false;
    }
    if (!adb('shell', 'cat', '/proc/net/unix').includes(`webview_devtools_remote_${pid}`)) return false;
    adb('forward', 'tcp:9223', `localabstract:webview_devtools_remote_${pid}`); return true;
  }, 'WebView ready');
  browser = await chromium.connectOverCDP('http://127.0.0.1:9223', { noDefaults: true });
  page = browser.contexts()[0].pages()[0]; assert(page); page.setDefaultTimeout(45000);
}
async function restart() {
  if (browser) await browser.close();
  adb('shell', 'am', 'force-stop', pkg);
  adb('shell', 'am', 'start', '-n', `${pkg}/com.flownote.mobile.MainActivity`);
  await connect();
}
async function openUpdates() {
  await page.goto(`${web}/settings`); await page.getByTestId('android-update-settings').waitFor();
  const point = await page.locator('a[href="/__flownote_native__/updates"]').evaluate(node => {
    node.scrollIntoView({ block: 'center' }); const box = node.getBoundingClientRect();
    return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  });
  await page.mouse.click(point.x, point.y);
  await until(() => ui().includes('text="앱 업데이트"'), 'native update dialog');
}
async function apiRequest(route, method, body, token) {
  const response = await fetch(api + route, { method, signal: AbortSignal.timeout(30000),
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  assert(response.ok, `Synthetic upgrade ${method} ${route}: ${response.status}`);
  const value = await response.text(); return value ? JSON.parse(value) : null;
}

const envelope = await (await fetch(`${web}/updates/staging/android.json`)).json();
const payload = Buffer.from(envelope.payload, 'base64');
assert(verify('sha256', payload, readFileSync(`${root}flownote-desktop/update-public.pem`), Buffer.from(envelope.signature, 'base64')));
const feed = JSON.parse(payload); assert.equal(feed.versionCode, 13200); assert.equal(feed.version, '1.3.2'); assert.equal(feed.channel, 'staging');
const candidate = readFileSync(`${root}flownote/public/downloads/flownote-1.3.2-staging.apk`);
assert.equal(candidate.length, feed.artifacts.apk.size);
assert.equal(createHash('sha256').update(candidate).digest('hex'), feed.artifacts.apk.sha256);
const email = `native-upgrade-${randomUUID()}@example.com`, password = randomUUID();
await apiRequest('/api/users', 'POST', { email, password, username: `nu-${randomUUID().slice(0, 8)}`, nickname: 'Synthetic signed upgrade' });
const { token } = await apiRequest('/api/users/login', 'POST', { email, password });
const id = randomUUID(), marker = `code-upgrade-${randomUUID()}`;
const content = { format: 'code', version: 1, language: 'python', encoding: 'utf-16le', text: '# 한글\r\nprint(1)\r\n' };
let noteCreated = false;
try {
  await apiRequest('/api/notes', 'POST', { id, title: 'upgrade-preserved.py', content, revision: 1, client_id: randomUUID() }, token); noteCreated = true;
  if (browser) await browser.close();
  adb('shell', 'am', 'force-stop', pkg);
  adb('install', '-r', '-d', `${root}flownote/public/downloads/flownote-1.3.1-staging.apk`);
  assert(adb('shell', 'dumpsys', 'package', pkg).includes('versionCode=13100'));
  adb('shell', 'run-as', pkg, 'rm', '-f', 'files/updates/update.apk', 'files/updates/update.apk.part');
  await restart();
  await page.goto(`${web}/login`); await page.getByLabel('이메일').fill(email); await page.getByLabel('비밀번호', { exact: true }).fill(password);
  await page.getByRole('button', { name: '계속하기', exact: true }).click(); await page.getByRole('navigation', { name: '앱 기능' }).waitFor();
  await page.evaluate(marker => localStorage.setItem('flownote-native-code-upgrade-probe', marker), marker);
  await page.goto(`${web}/blog/${id}`); await page.getByTestId('note-code-editor').waitFor();
  await openUpdates(); tap(['업데이트 확인']);
  await until(() => ui().includes('준비됨'), 'signed automatic download');
  const downloaded = bytes('exec-out', 'run-as', pkg, 'cat', 'files/updates/update.apk');
  assert.equal(createHash('sha256').update(downloaded).digest('hex'), feed.artifacts.apk.sha256);
  assert(ui().includes('다운로드·서명 검증 완료')); tap(['닫기']);
  const mtime = adb('shell', 'run-as', pkg, 'stat', '-c', '%Y', 'files/updates/update.apk');
  await restart(); await openUpdates();
  assert.equal(adb('shell', 'run-as', pkg, 'stat', '-c', '%Y', 'files/updates/update.apk'), mtime);
  tap(['설치']);
  await until(() => ui().includes('앱 업데이트 설치 허용') || /topResumedActivity=.*packageinstaller/i.test(adb('shell', 'dumpsys', 'activity', 'activities')), 'permission or OS installer');
  if (ui().includes('앱 업데이트 설치 허용')) {
    tap(['설정 열기']);
    await until(() => /topResumedActivity=.*com.android.settings/.test(adb('shell', 'dumpsys', 'activity', 'activities')), 'OS permission settings');
    await until(() => ui().includes('checkable="true"'), 'OS permission switch rendered');
    const toggles = (ui().match(/<node\b[^>]*>/g) || []).filter(node => node.includes('checkable="true"'));
    assert.equal(toggles.length, 1);
    if (toggles[0].includes('checked="false"')) tapNode(toggles[0]);
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); tap(['설치']);
  }
  await until(() => /topResumedActivity=.*packageinstaller/i.test(adb('shell', 'dumpsys', 'activity', 'activities')), 'OS install confirmation');
  assert(adb('shell', 'dumpsys', 'package', pkg).includes('versionCode=13100'));
  tap(['UPDATE', 'Update', '업데이트', 'INSTALL', 'Install', '설치']);
  await until(() => adb('shell', 'dumpsys', 'package', pkg).includes('versionCode=13200'), 'user-confirmed13200 upgrade');
  await until(() => /topResumedActivity=.*InstallSuccess/.test(adb('shell', 'dumpsys', 'activity', 'activities')), 'OS completed');
  tap(['DONE', 'Done', '완료']); await restart();
  await page.getByRole('navigation', { name: '앱 기능' }).waitFor();
  assert.equal(await page.evaluate(() => localStorage.getItem('flownote-native-code-upgrade-probe')), marker);
  await page.goto(`${web}/blog/${id}`); await page.getByTestId('note-code-editor').waitFor();
  assert.deepEqual((await apiRequest(`/api/notes/${id}`, 'GET', null, token)).content, content);
  assert.equal(await page.getByLabel('노트 제목').inputValue(), 'upgrade-preserved.py');
  await openUpdates(); tap(['업데이트 확인']);
  await until(() => ui().includes('최신 버전 · 1.3.2-staging'), 'latest version after upgrade'); tap(['닫기']);
  await page.evaluate(() => localStorage.removeItem('flownote-native-code-upgrade-probe'));
  console.log('PASS signed13100→13200: actual download/SHA/native signer/cache restart/OS confirmation, session/local marker/code encoding preservation, latest-version check');
} finally {
  if (browser) await browser.close();
  if (noteCreated) await apiRequest(`/api/notes/${id}`, 'DELETE', null, token);
  await apiRequest('/api/users/logout-all', 'POST', null, token);
  const revoked = await fetch(`${api}/api/users/me`, { headers: { Authorization: `Bearer ${token}` } }); assert.equal(revoked.status, 401);
}
