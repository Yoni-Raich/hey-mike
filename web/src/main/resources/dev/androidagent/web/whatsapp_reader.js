// Hey Mike - read-only WhatsApp Web reader.
// Runs inside the user's own linked WhatsApp Web page. It reads chats that are
// already loaded in the page and never sends, deletes, marks read or changes
// anything, and never asks the network for more. Fixed code bundled with the
// app; the model never supplies script.
(() => {
  if (window.__heyMikeWA) return;
  const results = {};
  const req = (name) => { try { return typeof window.require === 'function' ? window.require(name) : null; } catch (e) { return null; } };
  const store = () => { const c = req('WAWebCollections'); return c && c.Chat && c.Msg ? c : null; };
  const norm = (s) => String(s || '').toLowerCase().replace(/[\s‎‏‪-‮]+/g, ' ').trim();
  const models = (col) => { try { return col.getModelsArray ? col.getModelsArray() : (col.models || []); } catch (e) { return []; } };
  const title = (chat) => chat.formattedTitle || chat.name || (chat.contact && (chat.contact.name || chat.contact.pushname)) || '';
  const isGroup = (chat) => !!(chat.isGroup || (chat.id && chat.id.server === 'g.us'));
  const kind = (m) => m.type === 'chat' ? 'text' : String(m.type || 'other');
  const text = (m) => {
    if (m.type === 'chat' && typeof m.body === 'string') return m.body;
    if (typeof m.caption === 'string' && m.caption) return m.caption;
    return '';
  };
  const sender = (m) => {
    const c = m.senderObj;
    return (c && (c.name || c.pushname || c.verifiedName)) || m.notifyName || null;
  };
  const SKIP = new Set(['e2e_notification', 'notification', 'notification_template', 'gp2', 'protocol', 'call_log', 'ciphertext', 'revoked']);

  function status() {
    const main = document.querySelector('#pane-side');
    const s = store();
    if (main) return { state: s ? 'ready' : 'unsupported' };
    // The landing page shows a QR code or the phone-number link form.
    if (document.querySelector('canvas[aria-label], [data-ref], input[type="text"][aria-label]')) return { state: 'link' };
    return { state: 'loading' };
  }

  async function chat(q) {
    const s = store();
    if (!s) return { error: 'unsupported' };
    const chats = models(s.Chat);
    const want = norm(q.conversation), who = norm(q.sender), hint = norm(q.hint).slice(0, 80);
    let pick = null, by = null;
    const named = want ? chats.filter((c) => norm(title(c)) === want) : [];
    if (named.length === 1) { pick = named[0]; by = 'title'; }
    if (!pick && hint) {
      const pool = named.length ? named : chats;
      const hits = pool.filter((c) => models(c.msgs).slice(-15).some((m) => norm(text(m)).includes(hint)));
      if (hits.length === 1) { pick = hits[0]; by = 'text'; }
    }
    if (!pick && who && !q.group) {
      const hits = chats.filter((c) => !isGroup(c) && norm(title(c)) === who);
      if (hits.length === 1) { pick = hits[0]; by = 'sender'; }
    }
    if (!pick) return { found: false, candidates: named.length };
    const limit = Math.max(1, Math.min(50, q.limit || 20));
    // Local only: what the page already holds. Nothing is fetched from the
    // phone or WhatsApp's servers, not even older messages.
    const msgs = models(pick.msgs);
    const older = (list) => q.before ? list.filter((m) => (m.t || 0) * 1000 < q.before) : list;
    const kept = older(msgs).filter((m) => !SKIP.has(m.type) && !m.isNotification);
    const group = isGroup(pick);
    const out = kept.slice(-limit).map((m) => {
      const mine = !!(m.id && m.id.fromMe);
      return {
        dir: mine ? 'out' : 'in',
        from: mine ? null : (group ? sender(m) : title(pick)),
        text: text(m) || ('[' + kind(m) + ']'),
        at: (m.t || 0) * 1000,
        kind: kind(m),
      };
    });
    const hasEarlier = kept.length > limit;
    return { found: true, title: title(pick), by, group, hasEarlier, messages: out };
  }

  window.__heyMikeWA = {
    status: () => JSON.stringify(status()),
    start(id, q) {
      results[id] = null;
      chat(q).then((r) => { results[id] = JSON.stringify(r); })
        .catch((e) => { results[id] = JSON.stringify({ error: String((e && e.message) || e).slice(0, 200) }); });
      return 'started';
    },
    take(id) { const r = results[id]; if (r != null) delete results[id]; return r == null ? null : r; },
  };
})();
