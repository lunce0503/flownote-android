import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { stripTypeScriptTypes } from 'node:module';

const root = new URL('../../', import.meta.url).pathname;
const source = readFileSync(path.join(root, 'flownote/src/shared/lib/codeContent.ts'), 'utf8');
const module = await import(`data:text/javascript,${encodeURIComponent(stripTypeScriptTypes(source, { mode: 'transform' }))}`);
const extensions = module.CODE_LANGUAGES.flatMap(language => language.extensions);
assert.equal(new Set(extensions).size, extensions.length, 'Owned code extensions must be unique');

const output = mkdtempSync(path.join(tmpdir(), 'flownote-android-export-policy-'));
try {
  execFileSync('javac', ['-d', output,
    'flownote-android/app/src/main/java/com/flownote/mobile/DownloadExportPolicy.java',
    'flownote-android/app/src/test/java/com/flownote/mobile/DownloadExportPolicyTest.java',
  ], { cwd: root, stdio: 'inherit' });
  execFileSync('java', ['-cp', output, 'com.flownote.mobile.DownloadExportPolicyTest', ...extensions], { stdio: 'inherit' });
} finally {
  rmSync(output, { recursive: true, force: true });
}
