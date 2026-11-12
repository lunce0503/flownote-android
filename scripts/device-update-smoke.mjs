// Android emulator/device staging only. CDP and ADB are diagnostic transports,
// never application API fallbacks. All product data requests use cloud HTTPS.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomUUID, createHash, verify } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { chromium } from '../../flownote/node_modules/playwright-core/index.mjs';
const adbPath = process.env.FLOWNOTE_TEST_ADB || '/tmp/flownote-app-android-sdk/platform-tools/adb';
const adb = (...args) => execFileSync(adbPath, args, { encoding: 'utf8', timeout: 30000 });
const staging = 'https://flownote-react-staging.vercel.app';
const packageName = 'com.flownote.mobile.staging';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function waitFor(check, label, attempts = 30) {
  for (let i = 0; i < attempts; i++) { if (await check()) return; await pause(1000); }
  throw new Error(`Timed out: ${label}`);
}
function ui() {
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      const output = adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/flownote-updater-test.xml');
      if (output.includes('dumped to:')) return adb('shell', 'cat', '/sdcard/flownote-updater-test.xml');
    } catch (error) { if (error.status !== 137) throw error; }
  }
  throw new Error('Android UI snapshot unavailable; refusing to tap stale coordinates');
}
function tap(text) {
  const nodes = ui().match(/<node\b[^>]*>/g) || [];
  const node = nodes.find(value => value.includes(`text="${text}"`)); assert(node, `Missing native UI: ${text}`);
  const [, x1, y1, x2, y2] = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  adb('shell', 'input', 'tap', String((+x1 + +x2) / 2), String((+y1 + +y2) / 2));
}
function forward() {
  const pid = adb('shell', 'pidof', packageName).trim(); assert(/^\d+$/.test(pid));
  adb('forward', 'tcp:9223', `localabstract:webview_devtools_remote_${pid}`);
}
const envelope = await (await fetch(`${staging}/updates/staging/android.json`)).json();
const payload = Buffer.from(envelope.payload, 'base64');
assert(verify('sha256', payload, readFileSync(new URL('../../flownote-desktop/update-public.pem', import.meta.url)), Buffer.from(envelope.signature, 'base64')));
const manifest = JSON.parse(payload); assert.equal(manifest.channel, 'staging');
console.log('PASS signed staging feed');
if (process.argv.includes('--post-install')) {
  assert(adb('shell', 'dumpsys', 'package', packageName).includes(`versionCode=${manifest.versionCode}`));
  forward();
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9223', { noDefaults: true });
  const page = browser.contexts()[0].pages()[0]; page.setDefaultTimeout(30000);
  await page.getByRole('navigation', { name: '앱 기능' }).waitFor();
  assert(!page.url().includes('/login'));
  assert.equal(await page.evaluate(() => localStorage.getItem('flownote-native-update-draft-probe')), 'preserved');
  await waitFor(() => ui().includes('최신 버전'), 'same-version skip after OS upgrade');
  await browser.close();
  console.log(`PASS Android versionCode ${manifest.versionCode} OS installation completed; login and draft preserved; native same-version skip confirmed`);
  process.exit(0);
}
const baselineVersion = Number(adb('shell', 'dumpsys', 'package', packageName).match(/versionCode=(\d+)/)?.[1]);
assert(baselineVersion > 0 && baselineVersion < manifest.versionCode, 'Install an earlier updater-enabled debug baseline first');
adb('shell', 'appops', 'set', packageName, 'REQUEST_INSTALL_PACKAGES', 'default');
adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
adb('shell', 'am', 'force-stop', packageName);
adb('shell', 'am', 'start', '-n', `${packageName}/com.flownote.mobile.MainActivity`);
await pause(1000);
forward();
let browser = await chromium.connectOverCDP('http://127.0.0.1:9223', { noDefaults: true });
let page = browser.contexts()[0].pages()[0]; page.setDefaultTimeout(30000);
await page.waitForURL(`${staging}/**`);
assert.equal(new URL(page.url()).origin, staging);
const devtools = await page.context().newCDPSession(page);
await devtools.send('Network.clearBrowserCookies');
const email = `android-update-${randomUUID()}@example.com`, password = randomUUID();
const api = 'https://flownote-api-staging.up.railway.app';
await waitFor(async () => (await fetch(`${api}/api/users/me`)).status === 401, 'staging auth ready');
const created = await fetch(`${api}/api/users`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password, username: `update-${randomUUID().slice(0, 8)}`, nickname: 'Android update verification' }) });
assert(created.ok, `Staging fixture creation: ${created.status}`);
await page.goto(`${staging}/login`);
await page.getByLabel('이메일').fill(email); await page.getByLabel('비밀번호', { exact: true }).fill(password);
await page.getByRole('button', { name: '계속하기', exact: true }).click();
await page.getByRole('navigation', { name: '앱 기능' }).waitFor();
console.log('PASS staging WebView login');
await page.evaluate(() => localStorage.setItem('flownote-native-update-draft-probe', 'preserved'));
assert.equal(await page.evaluate(() => typeof window.FlownoteUpdater), 'undefined');
assert.equal(await page.locator('header').count(), 0);
if (!ui().includes('준비됨')) { tap('업데이트'); tap('다시 확인'); }
await waitFor(() => ui().includes('준비됨'), 'automatic APK download and native signature verification');
const apk = execFileSync(adbPath, ['exec-out', 'run-as', packageName, 'cat', 'files/updates/update.apk']);
assert.equal(createHash('sha256').update(apk).digest('hex'), manifest.artifacts.apk.sha256);
console.log('PASS downloaded APK hash and native signer verification');
const modified = adb('shell', 'run-as', packageName, 'stat', '-c', '%Y', 'files/updates/update.apk');
tap('설치');
assert(ui().includes('다운로드·서명 검증 완료'), 'Native history must explain verification');
tap('닫기');
// Reopen the app without clearing data: cached verified APK must survive.
await browser.close();
adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
adb('shell', 'am', 'force-stop', packageName); adb('shell', 'am', 'start', '-n', `${packageName}/com.flownote.mobile.MainActivity`);
await waitFor(() => ui().includes('준비됨'), 'cached update on app restart');
assert.equal(adb('shell', 'run-as', packageName, 'stat', '-c', '%Y', 'files/updates/update.apk'), modified, 'Cached APK must not be downloaded again');
console.log('PASS verified cache survives process restart');
tap('설치'); tap('설치');
await waitFor(() => ui().includes('앱 업데이트 설치 허용'), 'unknown-source permission explanation');
tap('설정 열기');
await pause(500);
// Toggle the emulator's test-only permission as the user would in Settings.
const settings = ui(); const switchNode = (settings.match(/<node\b[^>]*>/g) || []).find(value => value.includes('android.widget.Switch'));
if (switchNode) {
  const [, x1, y1, x2, y2] = switchNode.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  adb('shell', 'input', 'tap', String((+x1 + +x2) / 2), String((+y1 + +y2) / 2));
  adb('shell', 'input', 'keyevent', '4');
} else {
  // Google's ATD image deliberately omits the Settings application.
  assert(adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.settings.MANAGE_UNKNOWN_APP_SOURCES', '-d', `package:${packageName}`).includes('No activity found'));
  console.log('SKIP Settings switch UI: ATD has no Settings activity. Granting test-only app-op to exercise the actual OS installer; real-device permission screen remains manual validation.');
  adb('shell', 'appops', 'set', packageName, 'REQUEST_INSTALL_PACKAGES', 'allow');
  adb('shell', 'am', 'start', '-n', `${packageName}/com.flownote.mobile.MainActivity`);
}
await waitFor(() => ui().includes('text="설치"'), 'return to app with verified install action');
tap('설치'); tap('설치');
await waitFor(() => /topResumedActivity=.*packageinstaller/.test(adb('shell', 'dumpsys', 'activity', 'activities')), 'Android OS installer');
assert(adb('shell', 'dumpsys', 'package', packageName).includes(`versionCode=${baselineVersion}`), 'Download must not silently install');
const installUI = ui(); const label = installUI.includes('text="UPDATE"') ? 'UPDATE' : installUI.includes('text="Update"') ? 'Update' : installUI.includes('text="업데이트"') ? '업데이트' : installUI.includes('text="INSTALL"') ? 'INSTALL' : installUI.includes('text="Install"') ? 'Install' : '설치';
tap(label);
await waitFor(() => adb('shell', 'dumpsys', 'package', packageName).includes(`versionCode=${manifest.versionCode}`), 'user-confirmed version upgrade');
await waitFor(() => /topResumedActivity=.*InstallSuccess/.test(adb('shell', 'dumpsys', 'activity', 'activities')), 'OS installation completion');
const completion = ui();
if (completion.includes('text="OPEN"')) tap('OPEN');
else if (completion.includes('text="열기"')) tap('열기');
adb('shell', 'am', 'start', '-n', `${packageName}/com.flownote.mobile.MainActivity`);
await waitFor(() => {
  try {
    const pid = adb('shell', 'pidof', packageName).trim();
    if (/^\d+$/.test(pid) && adb('shell', 'cat', '/proc/net/unix').includes(`webview_devtools_remote_${pid}`)) return true;
  } catch { }
  // Package replacement may still kill the old process after versionCode is
  // visible. Reopen only this test package until its new WebView is ready.
  adb('shell', 'am', 'start', '-n', `${packageName}/com.flownote.mobile.MainActivity`);
  return false;
}, 'updated application WebView process', 60);
forward();
browser = await chromium.connectOverCDP('http://127.0.0.1:9223', { noDefaults: true }); page = browser.contexts()[0].pages()[0]; page.setDefaultTimeout(30000);
await page.getByRole('navigation', { name: '앱 기능' }).waitFor();
assert.equal(await page.evaluate(() => localStorage.getItem('flownote-native-update-draft-probe')), 'preserved');
assert(!page.url().includes('/login'), 'Login must survive the APK upgrade');
await waitFor(() => ui().includes('최신 버전'), 'same-version update skip after upgrade');
await browser.close();
console.log(`PASS Android OS upgrade: signed staging feed, APK auto-download/hash/native signer, cache restart, native history, permission explanation, explicit OS confirmation, version ${baselineVersion}→${manifest.versionCode}, login/draft preservation and same-version skip`);
