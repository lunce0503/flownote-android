/* Only this bundled page receives the origin-scoped native bridge. */
(() => {
  const send = (value) => window.NativeTerminal?.postMessage(JSON.stringify(value));
  const terminal = new Terminal({
    cursorBlink: true, fontSize: 14, fontFamily: 'monospace', scrollback: 3000,
    allowProposedApi: false, convertEol: false, disableStdin: true,
    theme: { background: '#101214', foreground: '#e4e7e9', cursor: '#63d4b1', selectionBackground: '#346052' },
  });
  const fit = new FitAddon.FitAddon();
  terminal.loadAddon(fit);
  terminal.open(document.getElementById('terminal'));
  terminal.parser.registerOscHandler(52, () => true);
  let lastQueued = 0;
  let lastRendered = 0;
  let generation = 0;
  let ctrl = false;
  let alt = false;
  let enabled = false;
  let resizeTimer;
  const fitTerminal = () => {
    const size = fit.proposeDimensions();
    // Insets and rotation can briefly collapse the Android view. Do not destroy its screen then.
    if (size && size.cols >= 20 && size.rows >= 5) {
      const cols = Math.min(300, size.cols), rows = Math.min(200, size.rows);
      if (cols === terminal.cols && rows === terminal.rows) return;
      const following = terminal.buffer.active.viewportY === terminal.buffer.active.baseY;
      terminal.resize(cols, rows);
      if (following) terminal.scrollToBottom();
    }
  };
  terminal.onData(data => {
    if (!enabled) return;
    if (ctrl && /^[\x40-\x7f]$/.test(data)) data = String.fromCharCode(data.toUpperCase().charCodeAt(0) & 31);
    if (alt) data = '\x1b' + data;
    ctrl = false; alt = false;
    send({ type: 'modifiers', ctrl, alt });
    send({ type: 'input', data });
  });
  terminal.onResize(({cols, rows}) => send({ type: 'resize', cols, rows }));
  terminal.attachCustomKeyEventHandler(event => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'v') {
      if (event.type === 'keydown') send({ type: 'paste' });
      return false;
    }
    return true;
  });
  document.addEventListener('paste', event => { event.preventDefault(); send({ type: 'paste' }); }, true);
  new ResizeObserver(() => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(fitTerminal, 120);
  }).observe(document.getElementById('terminal'));
  window.addEventListener('message', event => {
    if (event.origin !== 'https://appassets.androidplatform.net' && event.origin !== '') return;
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    switch (message.type) {
      case 'output': {
        if (message.seq <= lastRendered) { send({ type: 'ack', seq: message.seq }); return; }
        if (message.seq <= lastQueued) return;
        lastQueued = message.seq;
        const current = generation;
        terminal.write(message.data, () => {
          if (current === generation) { lastRendered = message.seq; send({ type: 'ack', seq: message.seq }); }
        });
        break;
      }
      case 'reset':
        generation++; lastQueued = 0; lastRendered = 0;
        ctrl = false; alt = false;
        send({ type: 'modifiers', ctrl, alt });
        // Reset between queued writes, never before pending old-session output.
        terminal.write('', () => terminal.reset());
        break;
      case 'focus': terminal.focus(); send({ type: 'keyboard' }); break;
      case 'follow': terminal.scrollToBottom(); break;
      case 'enabled': enabled = !!message.value; terminal.options.disableStdin = !enabled; break;
      case 'font': terminal.options.fontSize = Math.max(10, Math.min(24, message.size)); fitTerminal(); break;
      case 'modifier':
        if (message.key === 'ctrl') ctrl = !ctrl;
        if (message.key === 'alt') alt = !alt;
        send({ type: 'modifiers', ctrl, alt }); terminal.focus(); break;
      case 'paste': if (enabled) terminal.paste(message.data); break;
    }
  });
  fitTerminal();
  send({ type: 'ready', cols: terminal.cols, rows: terminal.rows });
})();
