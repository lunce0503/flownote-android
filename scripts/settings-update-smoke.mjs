// ADB/CDP are test transports only. Product web/API requests use cloud staging.
// Before integration, --inject-candidate-settings mounts the actual candidate React component
// into the already-deployed settings page. This is NOT evidence of a staging deployment.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { writeFileSync, readFileSync } from 'node:fs';
import { verifyNoteEditing } from '../../flownote-desktop/scripts/note-web-smoke.mjs';
import { verifyCompositionControl } from './ime-control-smoke.mjs';
import { build } from '../../flownote/node_modules/esbuild/lib/main.js';
import { chromium } from '../../flownote/node_modules/playwright-core/index.mjs';

const root = new URL('../../', import.meta.url).pathname;
const candidate = process.argv[2], downloadFixture = process.argv[3];
assert(candidate && downloadFixture, 'Usage: node settings-update-smoke.mjs <1.3.1-debug-apk> <test-1.2.99-debug-apk> [--inject-candidate-settings]');
const inject = process.argv.includes('--inject-candidate-settings');
const adbPath = process.env.FLOWNOTE_TEST_ADB || '/tmp/flownote-app-android-sdk/platform-tools/adb';
const adb = (...args) => execFileSync(adbPath, args, { encoding: 'utf8', timeout: 60000 });
const avdConfigPath = process.env.FLOWNOTE_TEST_AVD_CONFIG || '/tmp/flownote-library-avds/library.avd/config.ini';
assert(/^hw\.keyboard\s*=\s*yes\s*$/m.test(readFileSync(avdConfigPath, 'utf8')),
  'CDP IME setup: enable hw.keyboard=yes in the AVD config and restart; FLOWNOTE_TEST_AVD_CONFIG selects that file.');
assert(/(?:^|-)qwerty(?:-|$)/m.test(adb('shell', 'am', 'get-config')),
  'CDP IME requires an emulator hardware keyboard: set AVD hw.keyboard=yes and restart. A soft-IME preference alone is insufficient.');
const pkg = 'com.flownote.mobile.staging', origin = 'https://flownote-react-staging.vercel.app';
const action = `${origin}/__flownote_native__/updates`;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function waitFor(check, label, attempts = 60) {
  for (let i = 0; i < attempts; i++) { if (await check()) return; await pause(1000); }
  throw new Error(`Timed out: ${label}`);
}
function ui() {
  adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/note-updates-test.xml');
  return adb('shell', 'cat', '/sdcard/note-updates-test.xml');
}
function dialogIsOpen() {
  return (ui().match(/<node\b[^>]*>/g) || []).some(node => node.includes('resource-id="android:id/alertTitle"') && node.includes('text="앱 업데이트"'));
}
function tap(label) {
  const node = (ui().match(/<node\b[^>]*>/g) || []).find(value => value.includes(`text="${label}"`));
  assert(node, `Native control missing: ${label}`);
  const [, l, t, r, b] = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  adb('shell', 'input', 'tap', String((+l + +r) / 2), String((+t + +b) / 2));
}
function webBounds() {
  const node = (ui().match(/<node\b[^>]*>/g) || []).find(value => value.includes('class="android.webkit.WebView"'));
  assert(node); return node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/).slice(1).map(Number);
}
let browser, page;
async function connect() {
  await waitFor(() => {
    try {
      const pid = adb('shell', 'pidof', pkg).trim();
      if (!/^\d+$/.test(pid)) throw new Error('Process not started');
      if (!adb('shell', 'cat', '/proc/net/unix').includes(`webview_devtools_remote_${pid}`)) return false;
      adb('forward', 'tcp:9223', `localabstract:webview_devtools_remote_${pid}`); return true;
    } catch {
      // Package replacement can finish killing the old process after am start.
      adb('shell', 'am', 'start', '-n', `${pkg}/com.flownote.mobile.MainActivity`);
      return false;
    }
  }, 'WebView debug transport');
  browser = await chromium.connectOverCDP('http://127.0.0.1:9223', { noDefaults: true });
  page = browser.contexts()[0].pages()[0]; assert(page); page.setDefaultTimeout(45000);
}
async function restart(apk, downgrade = false) {
  if (browser) { await browser.close(); browser = null; }
  if (/topResumedActivity=.*com.android.settings/.test(adb('shell', 'dumpsys', 'activity', 'activities'))) {
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  }
  if (/topResumedActivity=.*InstallSuccess/.test(adb('shell', 'dumpsys', 'activity', 'activities'))) {
    const xml = ui();
    const done = ['DONE', 'Done', '완료'].find(value => xml.includes(`text="${value}"`));
    if (done) tap(done); else adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  }
  adb('shell', 'am', 'force-stop', pkg);
  adb('install', '-r', ...(downgrade ? ['-d'] : []), apk);
  adb('shell', 'am', 'start', '-n', `${pkg}/com.flownote.mobile.MainActivity`);
  await connect();
}
let component;
if (inject) {
  const result = await build({ absWorkingDir: root + 'flownote', tsconfig: 'tsconfig.app.json', bundle: true, write: false, format: 'iife', jsx: 'automatic',
    stdin: { contents: "import React from 'react'; import {createRoot} from 'react-dom/client'; import Settings from './src/pages/SettingsPage/AndroidUpdateSettings'; const node=document.createElement('div');node.dataset.candidateUpdateMount='true';document.querySelector('[data-testid=settings-panel]').prepend(node);createRoot(node).render(React.createElement(Settings));", resolveDir: root + 'flownote', loader: 'tsx' },
  });
  component = result.outputFiles[0].text;
  console.log('PRE-INTEGRATION: mounting candidate Settings component on live staging; no web deployment performed');
}
async function settings(viaMenu = false) {
  if (viaMenu) {
    await page.goto(origin + '/');
    await page.getByRole('button', { name: '전체 기능', exact: true }).click();
    await page.getByRole('link', { name: '설정', exact: true }).click();
  } else await page.goto(origin + '/settings');
  await page.getByTestId('settings-panel').waitFor();
  if (inject) await page.evaluate(component); // DevTools test execution; do not weaken the app's CSP.
  await page.getByTestId('android-update-settings').waitFor();
}
async function openUpdates() {
  const point = await page.evaluate(async () => {
    const link = document.querySelector('a[href="/__flownote_native__/updates"]');
    if (!(link instanceof HTMLElement)) throw new Error('Missing update settings action');
    link.scrollIntoView({ block: 'center' });
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const box = link.getBoundingClientRect();
    if (box.height < 44 || box.width < 44) throw new Error('Update settings touch target must be at least 44px');
    return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  });
  // A real input gesture. WebView consumes this navigation, so there is no browser load event
  // for Playwright's locator navigation waiter; native title + unchanged page are the contract.
  await page.mouse.click(point.x, point.y);
  await waitFor(dialogIsOpen, 'native update settings dialog');
  assert(page.url().startsWith(origin + '/settings'), 'Fixed action must not navigate the WebView');
}
const email = `native-settings-${randomUUID()}@example.com`, password = randomUUID();
const created = await fetch('https://flownote-api-staging.up.railway.app/api/users', { method: 'POST',
  headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password, username: `ns-${randomUUID().slice(0, 8)}`, nickname: 'Synthetic native settings' }) });
assert(created.ok, `Staging fixture registration: ${created.status}`);
const envelope = await (await fetch(origin + '/updates/staging/android.json')).json();
const manifest = JSON.parse(Buffer.from(envelope.payload, 'base64'));
const priorHardwareIme = adb('shell', 'settings', 'get', 'secure', 'show_ime_with_hard_keyboard').trim();
assert(manifest.versionCode >= 13000 && manifest.versionCode <= 13100, 'Expected current 1.3.0 or integrated 1.3.1 staging feed');
if (!inject) {
  assert.equal(manifest.versionCode, 13100);
  assert.equal(createHash('sha256').update(readFileSync(candidate)).digest('hex'), manifest.artifacts.apk.sha256, 'Test the exact publicly served staging APK');
}
try {
  // CDP emulates a hardware IME. Do not let Gboard independently finish each
  // provisional composition between commands; restore the emulator preference below.
  adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '0');
  await restart(root + 'flownote/public/downloads/flownote-1.3.0-staging.apk', true);
  await page.goto(origin + '/login');
  await page.getByLabel('이메일').fill(email); await page.getByLabel('비밀번호', { exact: true }).fill(password);
  await page.getByRole('button', { name: '계속하기', exact: true }).click();
  await page.getByRole('navigation', { name: '앱 기능' }).waitFor();
  await page.evaluate(() => localStorage.setItem('flownote-native-settings-preserve', 'draft-preserved'));
  await settings();
  await page.getByText('현재 앱은 화면 하단의 업데이트 버튼에서 확인·설치·이력을 제공합니다.').waitFor();
  assert.equal(await page.evaluate(() => typeof window.__flownoteAndroidUpdateState), 'undefined');
  const baseline = webBounds();
  assert((ui().match(/<node\b[^>]*>/g) || []).some(node => {
    const bounds = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    return node.includes('class="android.widget.Button"') && /text="(업데이트|설치)"/.test(node) && bounds && +bounds[2] >= baseline[3];
  }), 'Old native update action remains below the WebView');
  console.log('PASS new Settings component with existing 1.3.0 native updater fallback');

  // Same new native implementation with test-only lower version exercises the existing signed cloud feed.
  await browser.close(); browser = null;
  adb('shell', 'am', 'force-stop', pkg);
  // Clear only this synthetic test app's updater cache, never its WebView/session/draft data.
  adb('shell', 'run-as', pkg, 'rm', '-f', 'files/updates/update.apk', 'files/updates/update.apk.part');
  await restart(downloadFixture, true); await settings();
  await waitFor(() => page.evaluate(() => window.__flownoteAndroidUpdateState?.status.includes('준비됨')), 'signed automatic download', 120);
  const downloaded = execFileSync(adbPath, ['exec-out', 'run-as', pkg, 'cat', 'files/updates/update.apk']);
  assert.equal(createHash('sha256').update(downloaded).digest('hex'), manifest.artifacts.apk.sha256);
  await openUpdates();
  assert(ui().includes('다운로드·서명 검증 완료'));
  assert(ui().includes(`${manifest.version} 다운로드 시작`), 'Exercise an actual cloud download before checking cache reuse');
  tap('닫기');
  // Revoke only the synthetic package's install app-op, so the actual permission UI is exercised.
  adb('shell', 'appops', 'set', pkg, 'REQUEST_INSTALL_PACKAGES', 'deny');
  const mtime = adb('shell', 'run-as', pkg, 'stat', '-c', '%Y', 'files/updates/update.apk');
  await restart(downloadFixture); await settings();
  await waitFor(() => page.evaluate(() => window.__flownoteAndroidUpdateState?.status.includes('준비됨')), 'verified cache after process restart');
  assert.equal(adb('shell', 'run-as', pkg, 'stat', '-c', '%Y', 'files/updates/update.apk'), mtime);
  await openUpdates(); tap('설치');
  await waitFor(() => /packageinstaller|text="앱 업데이트 설치 허용"/.test(ui()), 'OS permission or install confirmation');
  assert(ui().includes('text="앱 업데이트 설치 허용"'), 'Permission explanation must precede OS settings');
  if (ui().includes('text="앱 업데이트 설치 허용"')) {
    tap('설정 열기');
    await pause(1000);
    assert(/topResumedActivity=.*com.android.settings/.test(adb('shell', 'dumpsys', 'activity', 'activities')), 'Actual OS settings must be foreground');
    // API35 Settings uses Compose: the switch is an accessible checkable View.
    const toggles = (ui().match(/<node\b[^>]*>/g) || []).filter(value => value.includes('checkable="true"'));
    assert.equal(toggles.length, 1, 'Unknown-app-source screen must expose exactly one permission switch');
    const toggle = toggles[0];
    const [, l, t, r, b] = toggle.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    if (toggle.includes('checked="false"')) adb('shell', 'input', 'tap', String((+l + +r) / 2), String((+t + +b) / 2));
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
    tap('설치');
  }
  await waitFor(() => /topResumedActivity=.*packageinstaller/.test(adb('shell', 'dumpsys', 'activity', 'activities')), 'actual OS package installer');
  assert(adb('shell', 'dumpsys', 'package', pkg).includes('versionCode=12999'), 'No silent installation');
  const xml = ui();
  const label = ['UPDATE', 'Update', '업데이트', 'INSTALL', 'Install', '설치'].find(value => xml.includes(`text="${value}"`));
  assert(label); tap(label);
  await waitFor(() => adb('shell', 'dumpsys', 'package', pkg).includes(`versionCode=${manifest.versionCode}`), 'explicitly confirmed signed APK install');
  await waitFor(() => /topResumedActivity=.*InstallSuccess/.test(adb('shell', 'dumpsys', 'activity', 'activities')), 'OS installation completion');
  const success = ui();
  const done = ['DONE', 'Done', '완료'].find(value => success.includes(`text="${value}"`));
  if (done) tap(done); else adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  console.log('PASS new updater: signed automatic download, SHA-256/native signer, cached restart and user-confirmed OS installation');

  await restart(candidate); await settings(true);
  await waitFor(() => page.evaluate(() => window.__flownoteAndroidUpdateState?.version === '1.3.1-staging'), 'new native status');
  assert.equal(await page.evaluate(() => localStorage.getItem('flownote-native-settings-preserve')), 'draft-preserved');
  assert(!page.url().includes('/login'));
  assert.equal(await page.evaluate(() => typeof window.FlownoteUpdater), 'undefined');
  const current = webBounds();
  assert(current[3] - baseline[3] >= 40, 'Removing the native bar must return its height to WebView');
  assert(!ui().includes('text="업데이트"'), 'No native bottom updater bar');
  const metrics = await page.evaluate(() => ({ innerHeight, dpr: devicePixelRatio, width: innerWidth, documentWidth: document.documentElement.scrollWidth }));
  assert(Math.abs(metrics.innerHeight * metrics.dpr - (current[3] - current[1])) < 4);
  assert(metrics.documentWidth <= metrics.width + 1);
  console.log(JSON.stringify({ baselineWebBounds: baseline, newWebBounds: current, returnedPhysicalPixels: current[3] - baseline[3], metrics }));
  await openUpdates(); assert(ui().includes('확인·다운로드 이력')); assert(ui().includes('설치 버전 확인 · 1.3.1-staging'));
  tap('업데이트 확인');
  await waitFor(() => ui().includes('최신 버전 · 1.3.1-staging'), 'manual check and same/newer-version no downgrade');
  adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  await pause(500); assert(!dialogIsOpen());
  await page.waitForFunction(() => !!document.querySelector('[data-testid="android-update-settings"]'));
  assert(page.url().startsWith(origin + '/settings'));
  // Cross-frame and scripted navigation cannot invoke the native command.
  const client = await page.context().newCDPSession(page);
  // Playwright evaluate/waitForFunction explicitly grants userGesture:true. Let that
  // transient activation expire before asserting the absence of a user gesture.
  await waitFor(async () => {
    const result = await client.send('Runtime.evaluate', { expression: 'navigator.userActivation.isActive', userGesture: false, returnByValue: true });
    return result.result.value === false;
  }, 'expired test-injected user activation', 10);
  await client.send('Runtime.evaluate', { userGesture: false,
    expression: `(()=>{const frame=document.createElement('iframe');frame.src=${JSON.stringify(action)};document.body.append(frame);setTimeout(()=>location.href=${JSON.stringify(action)},0)})()` });
  await client.detach();
  await pause(1000); assert(!dialogIsOpen());
  await openUpdates(); tap('닫기');
  await page.screenshot({ path: '/tmp/flownote-note-native-settings.png' });
  await restart(candidate); await settings();
  assert.equal(await page.evaluate(() => localStorage.getItem('flownote-native-settings-preserve')), 'draft-preserved');
  await openUpdates(); assert(ui().includes('설치 버전 확인 · 1.3.1-staging'));
  writeFileSync('/tmp/flownote-note-native-update-dialog.png', execFileSync(adbPath, ['exec-out', 'screencap', '-p']));
  tap('닫기');
  console.log('PASS Android 1.3.0→1.3.1: session/draft/history persistence, fixed Settings action, live state/manual check, no downgrade, dialog Back/close/reopen, iframe/no-gesture rejection and full available WebView height');
  if (!inject) {
    const login = await fetch('https://flownote-api-staging.up.railway.app/api/users/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password }) });
    assert(login.ok); const { token } = await login.json();
    // The native fixed-action navigation is intentionally consumed. Start a real
    // cloud route before locator-driven input, clearing that pending navigation.
    await page.goto(origin + '/blog');
    await page.getByRole('button', { name: '새 노트', exact: true }).first().waitFor();
    await verifyCompositionControl(page);
    await verifyNoteEditing(page, token, { androidWebView: true });
  }
} finally {
  if (browser) await browser.close();
  if (priorHardwareIme === 'null') adb('shell', 'settings', 'delete', 'secure', 'show_ime_with_hard_keyboard');
  else adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', priorHardwareIme);
}
