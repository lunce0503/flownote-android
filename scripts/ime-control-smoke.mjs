import assert from 'node:assert/strict';
import { sendKoreanComposition } from '../../flownote-desktop/scripts/note-web-smoke.mjs';

/** React-free control for the exact native WebView CDP stream used by note-web-smoke. */
export async function verifyCompositionControl(page) {
  for (let iteration = 0; iteration < 5; iteration++) {
    await page.evaluate(() => {
      const node = document.createElement('div');
      node.id = 'flownote-synthetic-ime-control';
      node.contentEditable = 'true';
      node.setAttribute('role', 'textbox');
      node.setAttribute('aria-label', 'Synthetic native IME control');
      node.style.cssText = 'position:fixed;top:10px;left:0;z-index:99999;background:white;padding:10px';
      document.body.append(node);
    });
    const control = page.getByRole('textbox', { name: 'Synthetic native IME control', exact: true });
    let cdp;
    try {
      await control.pressSequentially('alpha new beta gamma');
      await control.press('Home');
      for (let i = 0; i < 6; i++) await control.press('ArrowRight');
      for (let i = 0; i < 4; i++) await control.press('Shift+ArrowRight');
      await control.evaluate(node => {
        const events = [];
        Object.assign(node, { __imeControl: events });
        for (const type of ['compositionstart', 'compositionupdate', 'compositionend']) {
          node.addEventListener(type, event => events.push({ type, data: event.data }));
        }
      });
      cdp = await page.context().newCDPSession(page);
      await sendKoreanComposition(cdp, true);
      await page.waitForFunction(() => Reflect.get(document.getElementById('flownote-synthetic-ime-control'), '__imeControl').some(event => event.type === 'compositionend'));
      const actual = await control.evaluate(node => ({ text: node.textContent, events: Reflect.get(node, '__imeControl') }));
      assert.equal(actual.text, 'alpha 한글 beta gamma', JSON.stringify({ iteration, ...actual }));
      assert.equal(actual.events.filter(event => event.type === 'compositionstart').length, 1);
      assert.equal(actual.events.filter(event => event.type === 'compositionend').length, 1);
      assert.deepEqual(actual.events.filter(event => event.type === 'compositionupdate').slice(0, 2).map(event => event.data), ['한', '한글']);
    } finally {
      if (cdp) await cdp.detach();
      await page.evaluate(() => document.getElementById('flownote-synthetic-ime-control')?.remove());
    }
  }
  console.log('PASS native plain contenteditable IME control 5/5: same middle four-character selection, CDP session order, multi-step Korean input, exact text and one composition start/end');
}
