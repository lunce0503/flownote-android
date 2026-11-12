// ADB/CDP are test transports. All app requests go to cloud staging.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { chromium } from '../../flownote/node_modules/playwright-core/index.mjs';
import { textbookPdf } from '../../flownote-desktop/scripts/library-fixture.mjs';
const adbPath = process.env.FLOWNOTE_TEST_ADB || '/tmp/flownote-app-android-sdk/platform-tools/adb';
const adb = (...args) => execFileSync(adbPath, args, { encoding: 'utf8', timeout: 60000 });
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const pkg = 'com.flownote.mobile.staging', origin = 'https://flownote-react-staging.vercel.app';
const outputName = `native-library-output-${randomUUID().slice(0, 8)}.pdf`;
function ui() {
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      const output = adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/library-test.xml');
      if (output.includes('dumped to:')) return adb('shell', 'cat', '/sdcard/library-test.xml');
    } catch (error) { if (error.status !== 137 || attempt === 3) throw error; }
  }
  throw new Error('Native hierarchy unavailable');
}
function tap(text) {
  const node = (ui().match(/<node\b[^>]*>/g) || []).find(n => n.includes(`text="${text}"`) || n.includes(`content-desc="${text}"`));
  assert(node, `Native control missing: ${text}`);
  const [, x1, y1, x2, y2] = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  adb('shell', 'input', 'tap', String((+x1 + +x2) / 2), String((+y1 + +y2) / 2));
}
async function connect() {
  for (let attempt = 0; attempt < 30; attempt++) {
    try {
      const pid = adb('shell', 'pidof', pkg).trim(); assert(/^\d+$/.test(pid));
      adb('forward', 'tcp:9223', `localabstract:webview_devtools_remote_${pid}`);
      const browser = await chromium.connectOverCDP('http://127.0.0.1:9223', { noDefaults: true, timeout: 3000 });
      const page = browser.contexts()[0].pages()[0];
      if (!page) { await browser.close(); throw new Error('WebView not ready'); }
      page.setDefaultTimeout(60000); return { browser, page };
    } catch (error) { if (attempt === 29) throw error; await pause(1000); }
  }
}
const original = textbookPdf(100 * 1024 * 1024);
writeFileSync('/tmp/flownote-native-library-input.pdf', original);
adb('push', '/tmp/flownote-native-library-input.pdf', '/sdcard/Download/native-library-input.pdf');
adb('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/native-library-input.pdf');
adb('install', '-r', '-d', new URL('../../flownote/public/downloads/flownote-1.2.0-staging.apk', import.meta.url).pathname);
adb('shell', 'am', 'start', '-n', `${pkg}/com.flownote.mobile.MainActivity`);
await pause(3000);
let { browser, page } = await connect();
const api = 'https://flownote-api-staging.up.railway.app';
for (const route of ['/api/users/me', '/api/canvas/documents']) {
  for (let attempt = 0; attempt < 10; attempt++) {
    const ready = await fetch(api + route, { signal: AbortSignal.timeout(30000) }).catch(() => null);
    if (ready?.status === 401) break;
    assert(attempt < 9, 'Staging auth did not wake'); await pause(2000);
  }
}
const email = `android-library-${randomUUID()}@example.com`, password = randomUUID();
const response = await fetch(`${api}/api/users`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password, username: `al-${randomUUID().slice(0, 8)}`, nickname: 'Native library test' }) });
assert(response.ok, `Staging fixture: ${response.status}`);
try {
  await page.goto(`${origin}/login`);
  await page.getByLabel('이메일').fill(email); await page.getByLabel('비밀번호', { exact: true }).fill(password);
  await page.getByRole('button', { name: '계속하기', exact: true }).click();
  await page.getByRole('navigation', { name: '앱 기능' }).waitFor();
  await page.goto(`${origin}/library`);
  await page.getByRole('button', { name: '책 추가', exact: true }).click();
  await page.getByLabel('PDF 파일').click(); await pause(1500);
  if (!ui().includes('text="native-library-input.pdf"')) {
    tap('Show roots'); await pause(500); tap('Downloads'); await pause(1000);
  }
  tap('native-library-input.pdf');
  console.log('PASS Android system file selection');
  await page.getByLabel('책 제목', { exact: true }).fill('Android retained textbook');
  await page.getByRole('button', { name: '책장에 추가', exact: true }).click();
  await page.getByRole('dialog', { name: '책 펼쳐 보기' }).getByRole('img').waitFor();
  await page.getByRole('button', { name: '책 읽기', exact: true }).click();
  await page.getByTestId('library-pdf-page').waitFor();
  console.log('PASS Android 100MiB import, cover and reader');
  await browser.close();
  adb('shell', 'am', 'force-stop', pkg);
  adb('install', '-r', process.argv[2] || '/tmp/flownote-library-git-130/flownote-android/app/build/outputs/apk/debug/app-debug.apk');
  adb('shell', 'am', 'start', '-n', `${pkg}/com.flownote.mobile.MainActivity`); await pause(2000);
  ({ browser, page } = await connect());
  await page.goto(`${origin}/library`);
  await page.getByRole('button', { name: 'Android retained textbook 표지 보기' }).click();
  console.log('PASS Android 1.2→1.3 upgrade retained the book and login');
  await page.getByRole('button', { name: '원본 PDF 내보내기' }).click();
  let ready = false;
  for (let i = 0; i < 120; i++) {
    await pause(1000);
    if (ui().includes('text="SAVE"')) { ready = true; break; }
  }
  assert(ready, 'Native PDF save picker did not open');
  // Change the output title so the input original cannot be overwritten.
  const xml = ui();
  const field = (xml.match(/<node\b[^>]*>/g) || []).find(n => n.includes('class="android.widget.EditText"'));
  assert(field); const [, x1, y1, x2, y2] = field.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  adb('shell', 'input', 'tap', String((+x1 + +x2) / 2), String((+y1 + +y2) / 2));
  adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END');
  adb('shell', 'input', 'keyevent', '--longpress', 'KEYCODE_DEL');
  // Existing filename is retained by the OS if no change is required; select all explicitly.
  adb('shell', 'input', 'keycombination', '113', '29');
  adb('shell', 'input', 'text', outputName);
  tap('SAVE');
  const expected = createHash('sha256').update(original).digest('hex');
  let valid = false;
  for (let i = 0; i < 30; i++) {
    await pause(1000);
    try {
      const bytes = Number(adb('shell', 'stat', '-c', '%s', `/sdcard/Download/${outputName}`).trim());
      if (bytes === original.length) { assert.equal(adb('shell', 'sha256sum', `/sdcard/Download/${outputName}`).split(/\s/)[0], expected); valid = true; break; }
    } catch { /* The system provider may still be writing. */ }
  }
  assert(valid, 'Original 100MiB PDF not saved');
  console.log('PASS Android system PDF selection, 100MiB IndexedDB import, cover/read, 1.2→1.3 update preservation and original export SHA-256');
} finally { await browser.close(); }
