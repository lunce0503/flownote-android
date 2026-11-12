// ADB/CDP are test transports only. Product requests use the fixed cloud staging origins.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID, verify } from 'node:crypto';
import { readFileSync, writeFileSync, unlinkSync } from 'node:fs';
import { chromium } from '../../flownote/node_modules/playwright-core/index.mjs';
import { verifyCodeNotes } from '../../flownote-desktop/scripts/code-note-smoke.mjs';
import { verifyNoteTools, verifyWrappedCodeNote } from '../../flownote-desktop/scripts/note-tools-smoke.mjs';

const root = new URL('../../', import.meta.url).pathname;
const apk = process.argv[2] || `${root}flownote/public/downloads/flownote-1.3.2-staging.apk`;
const adbPath = process.env.FLOWNOTE_TEST_ADB || '/tmp/flownote-app-android-sdk/platform-tools/adb';
const avdConfig = process.env.FLOWNOTE_TEST_AVD_CONFIG || '/home/kwon/.android/avd/flownote-api35.avd/config.ini';
const adb = (...args) => execFileSync(adbPath, args, { encoding: 'utf8', timeout: 60000 });
const adbBytes = (...args) => execFileSync(adbPath, args, { timeout: 60000 });
const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const pkg = 'com.flownote.mobile.staging';
const origin = 'https://flownote-react-staging.vercel.app';
const api = 'https://flownote-api-staging.up.railway.app';
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const html = await (await fetch(origin)).text();
const entry = html.match(/<script\b[^>]*type="module"[^>]*src="([^"]+)"/);
assert(entry, 'Android staging entry is missing');
const entryUrl = new URL(entry[1], origin).href;
const expectedEntryHash = digest(Buffer.from(await (await fetch(entryUrl)).arrayBuffer()));
assert(/^hw\.keyboard\s*=\s*yes\s*$/m.test(readFileSync(avdConfig, 'utf8')), 'Android code IME requires hw.keyboard=yes');

async function verifyEntry(page) {
  const actualHash = await page.evaluate(async url => {
    const script = document.querySelector('script[type="module"][src]');
    if (!script || new URL(script.getAttribute('src'), location.href).href !== url) throw new Error('Unexpected Android executing entry');
    const response = await fetch(url, { cache: 'force-cache' });
    if (!response.ok) throw new Error('Android executing entry is unavailable');
    const value = await crypto.subtle.digest('SHA-256', await response.arrayBuffer());
    return Array.from(new Uint8Array(value), byte => byte.toString(16).padStart(2, '0')).join('');
  }, entryUrl);
  assert.equal(actualHash, expectedEntryHash);
  assert.equal(await page.locator('html').getAttribute('data-android-app'), 'true');
  console.log(JSON.stringify({ androidEntry: entryUrl, sha256: actualHash }));
}

async function waitFor(check, label, attempts = 90) {
  for (let attempt = 0; attempt < attempts; attempt++) {
    if (await check()) return;
    await pause(1000);
  }
  throw new Error(`Android code-note timeout: ${label}`);
}
function hierarchy() {
  adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/code-note-test.xml');
  return adb('shell', 'cat', '/sdcard/code-note-test.xml');
}
function nodes() { return hierarchy().match(/<node\b[^>]*>/g) || []; }
function tapAny(labels) {
  const node = nodes().find(value => labels.some(label => value.includes(`text="${label}"`) || value.includes(`content-desc="${label}"`)));
  assert(node, `Android code-note native control missing: ${labels.join('/')}`);
  const [, left, top, right, bottom] = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  adb('shell', 'input', 'tap', String((+left + +right) / 2), String((+top + +bottom) / 2));
}
async function connect() {
  await waitFor(() => {
    try {
      const pid = adb('shell', 'pidof', pkg).trim();
      if (!/^\d+$/.test(pid) || !adb('shell', 'cat', '/proc/net/unix').includes(`webview_devtools_remote_${pid}`)) return false;
      adb('forward', 'tcp:9223', `localabstract:webview_devtools_remote_${pid}`); return true;
    } catch { return false; }
  }, 'WebView DevTools');
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9223', { noDefaults: true });
  const page = browser.contexts()[0].pages()[0]; assert(page); page.setDefaultTimeout(60000);
  return { browser, page };
}

const envelope = await (await fetch(`${origin}/updates/staging/android.json`)).json();
const feedPayload = Buffer.from(envelope.payload, 'base64');
assert(verify('sha256', feedPayload, readFileSync(`${root}flownote-desktop/update-public.pem`), Buffer.from(envelope.signature, 'base64')), 'Staging feed signature');
const feed = JSON.parse(feedPayload.toString('utf8'));
assert.equal(feed.channel, 'staging'); assert.equal(feed.platform, 'android');
assert.match(feed.version, /^\d+\.\d+\.\d+$/); assert(Number.isSafeInteger(feed.versionCode) && feed.versionCode > 0);
const apkDigest = createHash('sha256').update(readFileSync(apk)).digest('hex');
const unpublishedDigest = process.env.FLOWNOTE_TEST_UNPUBLISHED_APK_SHA256;
const candidateVersion = process.env.FLOWNOTE_TEST_APK_VERSION || feed.version;
const candidateCode = Number(process.env.FLOWNOTE_TEST_APK_VERSION_CODE || feed.versionCode);
if (unpublishedDigest) {
  assert.match(unpublishedDigest, /^[a-f0-9]{64}$/);
  assert.equal(apkDigest, unpublishedDigest, 'Use the exact unpublished native candidate requested by the test owner');
} else assert.equal(apkDigest, feed.artifacts.apk.sha256, 'Use the exact staging-channel APK');
adb('install', '-r', apk);
assert(adb('shell', 'dumpsys', 'package', pkg).includes(`versionCode=${candidateCode}`));
adb('shell', 'am', 'start', '-n', `${pkg}/com.flownote.mobile.MainActivity`);
let { browser, page } = await connect();
const email = `android-code-${randomUUID()}@example.com`, password = randomUUID();
const created = await fetch(`${api}/api/users`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email, password, username: `ac-${randomUUID().slice(0, 8)}`, nickname: 'Synthetic Android code note' }),
});
assert(created.ok, `Android code-note signup: ${created.status}`);
const login = await fetch(`${api}/api/users/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password }) });
assert(login.ok, `Android code-note login: ${login.status}`); const { token } = await login.json();
const importedFiles = [];

try {
  try { await page.goto(`${origin}/login`); }
  catch (error) {
    if (new URL(page.url()).pathname !== '/login') throw error;
  }
  await page.getByLabel('이메일').waitFor(); await page.getByLabel('이메일').fill(email); await page.getByLabel('비밀번호', { exact: true }).fill(password);
  await page.getByRole('button', { name: '계속하기', exact: true }).click(); await page.getByRole('navigation', { name: '앱 기능' }).waitFor();
  await verifyEntry(page);
  await page.goto(`${origin}/settings`); await page.getByTestId('android-update-settings').waitFor();
  await page.waitForFunction(version => window.__flownoteAndroidUpdateState?.version === version, `${candidateVersion}-staging`);
  const updateLink = page.locator('a[href="/__flownote_native__/updates"]'); await updateLink.waitFor();
  const point = await updateLink.evaluate(link => { const box = link.getBoundingClientRect(); return { x: box.x + box.width / 2, y: box.y + box.height / 2 }; });
  await page.mouse.click(point.x, point.y);
  await waitFor(() => hierarchy().includes('앱 업데이트'), 'existing update settings dialog'); tapAny(['닫기', 'Close']);
  console.log('PASS Android existing Settings update path remains reachable');

  const selectImport = async ({ name, bytes, trigger }) => {
    const local = `/tmp/${name}`, remote = `/sdcard/Download/${name}`;
    writeFileSync(local, bytes);
    try { adb('push', local, remote); } finally { unlinkSync(local); }
    importedFiles.push(remote);
    adb('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file://${remote}`);
    const triggered = trigger();
    await waitFor(() => /documentsui/i.test(adb('shell', 'dumpsys', 'activity', 'activities')), 'system code file picker');
    let visible = false;
    for (let attempt = 0; attempt < 20 && !visible; attempt++) {
      visible = hierarchy().includes(`text="${name}"`);
      if (!visible) await pause(500);
    }
    if (!visible) {
      await waitFor(() => hierarchy().includes('content-desc="Show roots"') || hierarchy().includes('content-desc="탐색 창 표시"'), 'system picker roots');
      tapAny(['Show roots', '탐색 창 표시']);
      await waitFor(() => hierarchy().includes('text="Downloads"') || hierarchy().includes('text="다운로드"'), 'Downloads root');
      tapAny(['Downloads', '다운로드']);
      await waitFor(() => hierarchy().includes(`text="${name}"`), `indexed ${name}`, 30);
    }
    tapAny([name]); await triggered;
  };
  const captureDownload = async ({ name, mime, expected, trigger }) => {
    const remote = `/sdcard/Download/${name}`; adb('shell', 'rm', '-f', remote);
    const triggered = trigger();
    await waitFor(() => /documentsui/i.test(adb('shell', 'dumpsys', 'activity', 'activities')), 'system code save picker');
    const field = nodes().find(value => value.includes('class="android.widget.EditText"'));
    assert(field?.includes(`text="${name}"`), `Android save picker must retain the exact code filename: ${name}`);
    await waitFor(() => nodes().some(value => ['SAVE', 'Save', '저장'].some(label => value.includes(`text="${label}"`))), 'system save action');
    tapAny(['SAVE', 'Save', '저장']); await triggered;
    await waitFor(() => {
      try { return Number(adb('shell', 'stat', '-c', '%s', remote).trim()) === expected.length; } catch { return false; }
    }, `saved ${name}`, 30);
    const bytes = adbBytes('exec-out', 'cat', remote); adb('shell', 'rm', '-f', remote);
    return { name, mime, bytes };
  };
  const restart = async () => {
    await browser.close(); adb('shell', 'am', 'force-stop', pkg); adb('shell', 'am', 'start', '-n', `${pkg}/com.flownote.mobile.MainActivity`);
    ({ browser, page } = await connect());
    await page.waitForURL(`${origin}/**`); await page.getByRole('navigation', { name: '앱 기능' }).waitFor();
    await verifyEntry(page); return page;
  };
  if (!['only', 'wrap'].includes(process.env.FLOWNOTE_TEST_NOTE_TOOLS)) {
    const result = await verifyCodeNotes(page, token, { androidWebView: true, selectImport, captureDownload, restart });
    page = result.page;
    console.log(`PASS Android ${candidateVersion} actual WebView: system code-file selection/save, exact .py/.rs and UTF-16 bytes, IME, small viewport and process restart`);
  }
  if (process.env.FLOWNOTE_TEST_NOTE_TOOLS) {
    const readClipboard = async currentPage => {
      await currentPage.evaluate(() => {
        const input = document.createElement('textarea'); input.id = 'native-note-clipboard-probe';
        input.style.cssText = 'position:fixed;inset:0;z-index:2147483647';
        document.body.append(input); input.focus();
      });
      try {
        adb('shell', 'input', 'keyevent', '279');
        await currentPage.waitForFunction(() => document.querySelector('#native-note-clipboard-probe')?.value.length > 0);
        return await currentPage.locator('#native-note-clipboard-probe').inputValue();
      } finally { await currentPage.evaluate(() => document.querySelector('#native-note-clipboard-probe')?.remove()); }
    };
    if (process.env.FLOWNOTE_TEST_NOTE_TOOLS === 'wrap') await verifyWrappedCodeNote(page, token, { readClipboard });
    else await verifyNoteTools(page, token, { touch: true, readClipboard });
  }
} finally {
  if (browser) await browser.close();
  for (const remote of importedFiles) adb('shell', 'rm', '-f', remote);
  const revoked = await fetch(`${api}/api/users/logout-all`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(30000) });
  assert.equal(revoked.status, 204, 'Revoke synthetic Android code-note sessions');
  const oldToken = await fetch(`${api}/api/users/me`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(30000) });
  assert.equal(oldToken.status, 401, 'Synthetic Android session revocation readback');
}
