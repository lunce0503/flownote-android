import { basicSetup, EditorView } from 'codemirror';
import { EditorState, Compartment } from '@codemirror/state';
import { keymap } from '@codemirror/view';
import { indentWithTab, undo, redo, indentMore, indentLess } from '@codemirror/commands';
import { openSearchPanel, gotoLine } from '@codemirror/search';
import { StreamLanguage, HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { tags } from '@lezer/highlight';
import { javascript } from '@codemirror/lang-javascript';
import { python } from '@codemirror/lang-python';
import { java } from '@codemirror/lang-java';
import { json } from '@codemirror/lang-json';
import { markdown } from '@codemirror/lang-markdown';
import { html } from '@codemirror/lang-html';
import { css } from '@codemirror/lang-css';
import { kotlin } from '@codemirror/legacy-modes/mode/clike';
import { shell } from '@codemirror/legacy-modes/mode/shell';

const ORIGIN = 'https://appassets.androidplatform.net';
let epoch = -1, path = '', view, savedStates = new Map(), suppress = false;
let wrapped = false;
const wrapping = new Compartment();
const post = value => globalThis.NativeEditor?.postMessage(JSON.stringify({ ...value, path, epoch }));
function cursor(state) {
  const position = state.selection.main.head, line = state.doc.lineAt(position);
  post({ type: 'cursor', line: line.number, column: position - line.from + 1 });
}
function language(name) {
  const extension = name.split('.').pop().toLowerCase();
  if (['ts','tsx','js','jsx','mjs','cjs'].includes(extension)) return javascript({ typescript: extension.startsWith('ts'), jsx: extension.endsWith('x') });
  return ({ py: python, java, json, md: markdown, markdown, html, htm: html, css,
    kt: () => StreamLanguage.define(kotlin), kts: () => StreamLanguage.define(kotlin), sh: () => StreamLanguage.define(shell) })[extension]?.() || [];
}
const palette = syntaxHighlighting(HighlightStyle.define([
  { tag: tags.keyword, color: '#cf9fff' }, { tag: tags.string, color: '#a6da95' },
  { tag: tags.comment, color: '#89949e', fontStyle: 'italic' }, { tag: tags.number, color: '#f2c879' },
  { tag: tags.function(tags.variableName), color: '#7dcfff' }, { tag: tags.typeName, color: '#ed8796' },
]));
function makeState(content) {
  return EditorState.create({ doc: content, extensions: [basicSetup, language(path), palette, wrapping.of(wrapped ? EditorView.lineWrapping : []),
    EditorState.transactionFilter.of(transaction => {
      if (transaction.docChanged && new TextEncoder().encode(transaction.newDoc.sliceString(0, undefined, transaction.startState.lineBreak)).length > 256 * 1024) {
        post({ type: 'limit' }); return [];
      }
      return transaction;
    }),
    EditorState.lineSeparator.of(content.includes('\r\n') ? '\r\n' : '\n'),
    EditorView.theme({ '&': { color: '#e6e8eb', backgroundColor: '#101214' }, '.cm-gutters': { backgroundColor: '#171a1d', color: '#89949e', border: 'none' },
      '.cm-activeLine,.cm-activeLineGutter': { backgroundColor: '#23292f' }, '.cm-cursor': { borderLeftColor: '#63d4b1' },
      '&.cm-focused .cm-selectionBackground,.cm-selectionBackground,::selection': { backgroundColor: '#3a5465!important' },
      '.cm-panels': { backgroundColor: '#202427', color: '#e6e8eb' }, '.cm-foldPlaceholder': { backgroundColor: '#303840', color: '#f2c879', border: 'none' } }, { dark: true }),
    EditorView.contentAttributes.of({ 'aria-label': '코드 편집기', spellcheck: 'false', autocorrect: 'off', autocapitalize: 'off' }),
    keymap.of([indentWithTab, { key: 'Mod-s', run: () => { post({ type: 'save' }); return true; } }]),
    EditorView.updateListener.of(update => {
      if (suppress) return;
      if (update.docChanged) post({ type: 'change', content: update.state.sliceDoc() });
      if (update.selectionSet || update.docChanged) cursor(update.state);
    }),
  ] });
}
function receive(message) {
  if (!message || typeof message.type !== 'string') return;
  if (message.type === 'load') {
    if (typeof message.path !== 'string' || typeof message.content !== 'string') return;
    if (epoch !== message.epoch) { savedStates.clear(); path = ''; epoch = message.epoch; }
    if (view && path) savedStates.set(path, view.state);
    path = message.path;
    let state = savedStates.get(path);
    if (!state || state.sliceDoc() !== message.content) state = makeState(message.content);
    suppress = true;
    if (view) view.setState(state); else view = new EditorView({ state, parent: document.getElementById('editor') });
    view.dispatch({ effects: wrapping.reconfigure(wrapped ? EditorView.lineWrapping : []) });
    suppress = false;
    cursor(view.state);
    return;
  }
  if (!view || message.epoch !== epoch) return;
  const commands = { undo, redo, search: openSearchPanel, goto: gotoLine, indent: indentMore, outdent: indentLess };
  if (commands[message.type]) {
    commands[message.type](view);
    if (['search','goto'].includes(message.type)) post({ type: 'keyboard' }); else view.focus();
  }
  if (message.type === 'snapshot') post({ type: 'snapshot', id: message.id, content: view.state.sliceDoc() });
  if (message.type === 'wrap') {
    wrapped = message.enabled === true;
    view.dispatch({ effects: wrapping.reconfigure(wrapped ? EditorView.lineWrapping : []) });
  }
  if (message.type === 'close') { savedStates.delete(message.path); if (message.path === path) path = ''; }
}
globalThis.addEventListener('message', event => {
  // Android's destination-scoped native postWebMessage uses an empty source origin.
  if ((event.origin !== ORIGIN && !(event.origin === '' && globalThis.NativeEditor)) || typeof event.data !== 'string') return;
  try { receive(JSON.parse(event.data)); } catch { /* Ignore malformed bridge messages. */ }
});
globalThis.addEventListener('pagehide', () => { view?.destroy(); savedStates.clear(); });
post({ type: 'ready' });
