// The part of the browser tool that runs inside a page.
//
// It is evaluated in an isolated world: it shares the page's DOM and nothing
// else, so the page cannot see, replace or call any of it. Everything it
// returns is plain data. The page's own text is untrusted and is only ever
// reported, never acted on.
(() => {
  if (globalThis.__mike) return;

  const MAX_LINES = 4000;
  const NAME_CHARS = 120;
  const TEXT_CHARS = 240;

  // An element keeps one number for as long as it lives, so a ref from an
  // earlier snapshot still names the same element after the page re-renders
  // around it. A WeakRef keeps a removed element collectable.
  const refs = new Map();
  const ids = new WeakMap();
  let nextRef = globalThis.__mikeRefStart || 1;
  const refOf = (el) => {
    let id = ids.get(el);
    if (!id) {
      id = nextRef++;
      ids.set(el, id);
      refs.set(id, new WeakRef(el));
    }
    return id;
  };
  const byRef = (id) => {
    const holder = refs.get(Number(id));
    const el = holder && holder.deref();
    return el && el.isConnected ? el : null;
  };

  const clean = (text, max) => {
    const flat = String(text || '').replace(/\s+/g, ' ').trim();
    return flat.length > max ? flat.slice(0, max - 1) + '…' : flat;
  };

  const SKIP = new Set(['script', 'style', 'noscript', 'template', 'head', 'meta', 'link', 'title', 'base', 'br', 'wbr']);
  const INTERACTIVE = new Set([
    'link', 'button', 'textbox', 'searchbox', 'combobox', 'listbox', 'checkbox', 'radio', 'switch',
    'slider', 'spinbutton', 'tab', 'menuitem', 'menuitemcheckbox', 'menuitemradio', 'option', 'treeitem', 'file',
  ]);

  const visible = (el) => {
    if (el.getAttribute && el.getAttribute('aria-hidden') === 'true') return false;
    if (typeof el.checkVisibility === 'function') {
      return el.checkVisibility({ checkOpacity: false, checkVisibilityCSS: true });
    }
    const style = getComputedStyle(el);
    return style.display !== 'none' && style.visibility !== 'hidden';
  };

  const roleOf = (el) => {
    const explicit = el.getAttribute('role');
    if (explicit && explicit.trim()) return explicit.trim().split(/\s+/)[0].toLowerCase();
    const tag = el.localName;
    switch (tag) {
      case 'a': return el.hasAttribute('href') ? 'link' : null;
      case 'button': case 'summary': return 'button';
      case 'select': return el.multiple ? 'listbox' : 'combobox';
      case 'textarea': return 'textbox';
      case 'img': return 'img';
      case 'iframe': return 'iframe';
      case 'h1': case 'h2': case 'h3': case 'h4': case 'h5': case 'h6': return 'heading';
      case 'input': {
        const type = (el.getAttribute('type') || 'text').toLowerCase();
        if (type === 'hidden') return null;
        if (type === 'button' || type === 'submit' || type === 'reset' || type === 'image') return 'button';
        if (type === 'checkbox') return 'checkbox';
        if (type === 'radio') return 'radio';
        if (type === 'range') return 'slider';
        if (type === 'search') return 'searchbox';
        if (type === 'file') return 'file';
        return 'textbox';
      }
    }
    if (el.isContentEditable && !(el.parentElement && el.parentElement.isContentEditable)) return 'textbox';
    return null;
  };

  // Something a script made clickable without saying so: a div with a click
  // handler shows only as a pointer cursor it does not inherit.
  const looksClickable = (el) => {
    if (el.hasAttribute('onclick')) return true;
    const tab = el.getAttribute('tabindex');
    if (tab !== null && Number(tab) >= 0) return true;
    if (getComputedStyle(el).cursor !== 'pointer') return false;
    const parent = el.parentElement;
    return !parent || getComputedStyle(parent).cursor !== 'pointer';
  };

  const labelText = (el) => {
    if (el.labels && el.labels.length) {
      const text = Array.from(el.labels).map((label) => label.textContent).join(' ');
      if (text.trim()) return text;
    }
    return '';
  };

  const nameOf = (el) => {
    const aria = el.getAttribute('aria-label');
    if (aria && aria.trim()) return clean(aria, NAME_CHARS);
    const labelledBy = el.getAttribute('aria-labelledby');
    if (labelledBy) {
      const text = labelledBy.split(/\s+/).map((id) => {
        const node = el.ownerDocument.getElementById(id);
        return node ? node.textContent : '';
      }).join(' ');
      if (text.trim()) return clean(text, NAME_CHARS);
    }
    const tag = el.localName;
    if (tag === 'img') return clean(el.getAttribute('alt') || el.getAttribute('title') || '', NAME_CHARS);
    if (tag === 'iframe') return clean(el.getAttribute('title') || el.getAttribute('name') || '', NAME_CHARS);
    if (tag === 'input' || tag === 'select' || tag === 'textarea') {
      const label = labelText(el);
      if (label) return clean(label, NAME_CHARS);
      const type = (el.getAttribute('type') || '').toLowerCase();
      if (tag === 'input' && (type === 'button' || type === 'submit' || type === 'reset')) {
        return clean(el.value || type, NAME_CHARS);
      }
      return clean(el.getAttribute('placeholder') || el.getAttribute('title') || el.getAttribute('name') || '', NAME_CHARS);
    }
    const text = typeof el.innerText === 'string' ? el.innerText : el.textContent;
    if (text && text.trim()) return clean(text, NAME_CHARS);
    const image = el.querySelector && el.querySelector('img[alt]');
    return clean(el.getAttribute('title') || (image ? image.getAttribute('alt') : '') || '', NAME_CHARS);
  };

  const isPassword = (el) => el.localName === 'input' && (el.getAttribute('type') || '').toLowerCase() === 'password';
  const isCard = (el) => /^cc-|(^|\s)cc-/.test((el.getAttribute && el.getAttribute('autocomplete')) || '');

  const describe = (el, role) => {
    let line = role + ' ' + JSON.stringify(nameOf(el)) + ' [e' + refOf(el) + ']';
    const tag = el.localName;
    if (role === 'textbox' || role === 'searchbox' || role === 'spinbutton' || role === 'slider') {
      if (isPassword(el)) {
        line += ' [password]';
      } else if (isCard(el)) {
        line += ' [card]';
      } else {
        const value = tag === 'input' || tag === 'textarea' ? el.value : (el.isContentEditable ? el.innerText : '');
        if (value) line += ' value=' + JSON.stringify(clean(value, 80));
      }
    }
    if (tag === 'select') {
      const chosen = Array.from(el.selectedOptions || []).map((option) => clean(option.textContent, 40));
      line += ' value=' + JSON.stringify(chosen.join(', '));
      const options = Array.from(el.options || []).slice(0, 12).map((option) => clean(option.textContent, 40));
      line += ' options=' + JSON.stringify(options) + (el.options.length > 12 ? '+' + (el.options.length - 12) : '');
    }
    if (role === 'checkbox' || role === 'radio' || role === 'switch' || role === 'menuitemcheckbox' || role === 'menuitemradio') {
      const checked = tag === 'input' ? el.checked : el.getAttribute('aria-checked') === 'true';
      line += checked ? ' [checked]' : ' [unchecked]';
    }
    if (role === 'tab' || role === 'option') {
      const selected = tag === 'option' ? el.selected : el.getAttribute('aria-selected') === 'true';
      if (selected) line += ' [selected]';
    }
    const expanded = el.getAttribute('aria-expanded');
    if (expanded === 'true') line += ' [expanded]';
    if (expanded === 'false') line += ' [collapsed]';
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') line += ' [disabled]';
    if (role === 'link' && !nameOf(el)) line += ' href=' + JSON.stringify(clean(el.getAttribute('href'), 80));
    return line;
  };

  // One pass over the page in document order. `quiet` is set under an element
  // whose name already carries its text, so the text is not said twice.
  const walk = (root, emit) => {
    const visit = (node, quiet) => {
      if (node.nodeType === Node.TEXT_NODE) {
        if (!quiet) {
          const text = clean(node.nodeValue, TEXT_CHARS);
          if (text) emit('text', text, null);
        }
        return;
      }
      if (node.nodeType !== Node.ELEMENT_NODE) return;
      const el = node;
      const tag = el.localName;
      if (SKIP.has(tag) || !visible(el)) return;
      if (tag === 'svg') {
        const label = el.getAttribute('aria-label');
        if (label && !quiet) emit('img', clean(label, NAME_CHARS), null);
        return;
      }
      let role = roleOf(el);
      if (!role && !quiet && looksClickable(el) && nameOf(el)) role = 'clickable';
      let childQuiet = quiet;
      if (role && (INTERACTIVE.has(role) || role === 'clickable')) {
        emit('element', describe(el, role), el);
        childQuiet = true;
      } else if (role === 'heading') {
        if (!quiet) emit('heading', nameOf(el), el, /^h[1-6]$/.test(tag) ? tag.slice(1) : (el.getAttribute('aria-level') || '2'));
        childQuiet = true;
      } else if (role === 'img') {
        const alt = nameOf(el);
        if (alt && !quiet) emit('img', alt, el);
        return;
      } else if (role === 'iframe') {
        emit('iframe', nameOf(el), el);
        return;
      } else if (tag === 'tr' && !quiet && !el.querySelector('a[href],button,input,select,textarea,[role],[onclick]')) {
        const cells = Array.from(el.children).map((cell) => clean(cell.innerText, 80)).filter(Boolean);
        if (cells.length) emit('row', cells.join(' | '), el);
        return;
      }
      // A native select describes its own options; listing them again is noise.
      if (tag === 'select') return;
      if (el.shadowRoot) for (const child of el.shadowRoot.childNodes) visit(child, childQuiet);
      for (const child of el.childNodes) visit(child, childQuiet);
    };
    visit(root, false);
  };

  const snapshot = (interactiveOnly) => {
    const lines = [];
    let pendingText = '';
    const flush = () => {
      if (pendingText) lines.push('text ' + JSON.stringify(clean(pendingText, TEXT_CHARS)));
      pendingText = '';
    };
    try {
      walk(document.body || document.documentElement, (kind, value, el, level) => {
        if (lines.length >= MAX_LINES) throw new Error('full');
        if (kind === 'text') {
          if (interactiveOnly) return;
          // Neighbouring fragments of one sentence read as one line.
          if ((pendingText + ' ' + value).length > TEXT_CHARS) flush();
          pendingText = pendingText ? pendingText + ' ' + value : value;
          return;
        }
        flush();
        if (kind === 'element') lines.push(value);
        else if (kind === 'heading') lines.push('heading ' + JSON.stringify(value) + ' [h' + level + ']');
        else if (kind === 'iframe') lines.push('iframe ' + JSON.stringify(value));
        else if (!interactiveOnly) lines.push(kind + ' ' + JSON.stringify(value));
      });
    } catch (error) {
      if (error.message !== 'full') throw error;
    }
    flush();
    const scroller = document.scrollingElement || document.documentElement;
    return {
      url: location.href,
      title: document.title,
      lines,
      truncated: lines.length >= MAX_LINES,
      scrollY: Math.round(scroller.scrollTop),
      scrollMax: Math.max(0, Math.round(scroller.scrollHeight - innerHeight)),
    };
  };

  const candidates = () => {
    const found = [];
    walk(document.body || document.documentElement, (kind, value, el) => {
      if (kind === 'element' && el) found.push(el);
    });
    return found;
  };

  const score = (name, wanted, exact) => {
    const a = name.toLowerCase();
    const b = wanted.toLowerCase();
    if (a === b) return 3;
    if (exact) return 0;
    if (a.startsWith(b)) return 2;
    return a.includes(b) ? 1 : 0;
  };

  // Resolve a target to a ref. A ref or a selector names one element; text is
  // matched against what the snapshot calls each control, best match first.
  const find = (target) => {
    if (target.ref !== undefined && target.ref !== null) {
      const el = byRef(String(target.ref).replace(/^e/, ''));
      return el ? { ref: refOf(el), count: 1 } : { error: 'stale_ref' };
    }
    if (target.selector) {
      let list;
      try { list = document.querySelectorAll(target.selector); } catch (error) { return { error: 'bad_selector' }; }
      const el = Array.from(list).find(visible) || list[0];
      return el ? { ref: refOf(el), count: list.length } : { error: 'not_found' };
    }
    if (!target.text) return { error: 'no_target' };
    let best = null;
    let bestScore = 0;
    let count = 0;
    for (const el of candidates()) {
      const role = roleOf(el) || 'clickable';
      if (target.role && role !== String(target.role).toLowerCase()) continue;
      const points = score(nameOf(el), String(target.text), target.exact === true);
      if (points > bestScore) { best = el; bestScore = points; count = 1; }
      else if (points > 0 && points === bestScore) count++;
    }
    if (best) return { ref: refOf(best), count };
    if (target.role) return { error: 'not_found' };
    // Nothing the snapshot calls a control: fall back to the smallest element
    // that shows this text, which is what a person would press. Every match is
    // counted, so a label shown twice is reported as ambiguous instead of the
    // first one being pressed.
    if (!document.body) return { error: 'not_found' };
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const wanted = String(target.text).toLowerCase();
    let first = null;
    let matches = 0;
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (node.nodeValue.toLowerCase().includes(wanted) && node.parentElement && visible(node.parentElement)) {
        if (!first) first = node.parentElement;
        matches++;
      }
    }
    return first ? { ref: refOf(first), count: matches } : { error: 'not_found' };
  };

  // The middle of the element's first drawn box, in its own frame's viewport.
  const pointOf = (el) => {
    for (const rect of el.getClientRects()) {
      if (rect.width >= 1 && rect.height >= 1) return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    }
    return null;
  };

  // Would a press at the element's middle reach it? A cookie banner, a dialog
  // backdrop or a sticky header on top of it would take the press instead,
  // and the click would be reported against the wrong element.
  const hitCheck = (ref) => {
    const el = byRef(ref);
    if (!el) return { error: 'stale_ref' };
    const point = pointOf(el);
    if (!point) return { error: 'no_box' };
    const view = el.ownerDocument.defaultView;
    // Android can pan only the visual viewport when the keyboard opens. A
    // control inside the layout viewport may then be outside the actual view.
    const viewport = view.visualViewport;
    const left = viewport ? viewport.offsetLeft : 0;
    const top = viewport ? viewport.offsetTop : 0;
    const width = viewport ? viewport.width : view.innerWidth;
    const height = viewport ? viewport.height : view.innerHeight;
    if (point.x < left || point.y < top || point.x >= left + width || point.y >= top + height) return { error: 'offscreen' };
    const root = el.getRootNode();
    let hit = (typeof root.elementFromPoint === 'function' ? root : el.ownerDocument).elementFromPoint(point.x, point.y);
    while (hit && hit !== el && hit.shadowRoot) {
      const inner = hit.shadowRoot.elementFromPoint(point.x, point.y);
      if (!inner || inner === hit) break;
      hit = inner;
    }
    if (!hit) return { error: 'offscreen' };
    // The element itself, something inside it, or something it sits inside
    // (a press on an ancestor is what a person's finger would do too).
    if (hit === el || el.contains(hit) || hit.contains(el)) return { ok: true };
    // A press on a control's own label activates the control.
    const label = hit.closest && hit.closest('label');
    if (label && label.control === el) return { ok: true };
    return { blockedBy: (roleOf(hit) || hit.localName) + ' ' + JSON.stringify(clean(nameOf(hit), 60)), ref: refOf(hit) };
  };

  const deepFocus = () => {
    let el = document.activeElement;
    while (el && el.shadowRoot && el.shadowRoot.activeElement) el = el.shadowRoot.activeElement;
    return el;
  };
  const activationTarget = (el) => {
    for (let node = el; node; node = node.parentElement || node.getRootNode().host) {
      if (node.localName === 'label' && node.control) return node.control;
      if (['button', 'a', 'input', 'select', 'textarea'].includes(node.localName)) return node;
      if (node.hasAttribute('role')) {
        // Search results can expose a link-role div inside a real anchor.
        // Judge the anchor's action and destination, not just that inner label.
        if (roleOf(node) === 'link') {
          const anchor = node.closest('a[href]');
          if (anchor) return anchor;
        }
        return node;
      }
    }
    return el;
  };
  const approvalToken = (el) => {
    el = activationTarget(el);
    const form = el.form;
    return JSON.stringify([location.href, refOf(el), nameOf(el), el.type, el.href,
      el.getAttribute('formaction'), el.getAttribute('formmethod'), el.getAttribute('formtarget'),
      form && [form.action, form.method, form.target]]);
  };
  const acceptsHit = (ref, hit) => {
    const el = byRef(ref);
    if (!el || !hit) return false;
    for (let node = hit; node; node = node.parentElement || node.getRootNode().host) if (node === el) return true;
    const label = hit.closest && hit.closest('label');
    return !!label && label.control === el;
  };

  const info = (ref) => {
    const el = byRef(ref);
    if (!el) return null;
    const tag = el.localName;
    const type = (el.getAttribute('type') || '').toLowerCase();
    const textLike = tag === 'textarea' ||
      (tag === 'input' && !['checkbox', 'radio', 'button', 'submit', 'reset', 'image', 'file', 'hidden',
        'range', 'color', 'date', 'datetime-local', 'month', 'time', 'week'].includes(type));
    return {
      tag,
      type,
      role: roleOf(el) || (looksClickable(el) ? 'clickable' : tag),
      name: nameOf(el),
      password: isPassword(el),
      card: isCard(el),
      disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
      editable: textLike || el.isContentEditable,
      valueOnly: tag === 'input' && !textLike && !['checkbox', 'radio', 'button', 'submit', 'reset', 'image', 'file', 'hidden'].includes(type),
      confirmation: needsApproval(el),
      approvalToken: approvalToken(el),
    };
  };

  const needsApproval = (el) => {
    el = activationTarget(el);
    const name = nameOf(el);
    if (/\b(send|publish|buy|pay|purchase|checkout|place order|confirm order|delete|remove account)\b|שלח|שליחה|פרסם|פרסום|רכישה|תשלום|מחק|מחיקה/i.test(name)) return true;
    // A search result named "forms/post" describes a path, not a request to
    // publish content. Strip URL/path tokens only for this one word and only
    // on ordinary HTTP(S) links. Buttons, action links and other sensitive
    // verbs retain their approval checks.
    const method = (el.getAttribute('data-method') || el.getAttribute('data-turbo-method') || 'get').toLowerCase();
    const webLink = el.localName === 'a' && /^https?:$/.test(el.protocol) &&
      roleOf(el) !== 'button' && !el.hasAttribute('download') && method === 'get';
    const postName = webLink ? name.replace(/(?:https?:\/\/|www\.)\S+/gi, ' ').replace(/\S*\/\S*/g, ' ') : name;
    if (/\bpost\b/i.test(postName)) return true;
    const form = el.form;
    if (form && (el.type === 'submit' || el.localName === 'button' && !el.type)) {
      // Search and sign-in are ordinary navigation. Other submissions send user data.
      return !form.querySelector('input[type="search"],input[type="password"]') && form.getAttribute('role') !== 'search';
    }
    return false;
  };
  const submitNeedsApproval = () => {
    let el = document.activeElement;
    for (;;) {
      if (el && el.shadowRoot && el.shadowRoot.activeElement) { el = el.shadowRoot.activeElement; continue; }
      // Focus inside a frame shows here only as the frame element. Follow it
      // into a readable frame; a frame that cannot be read is not judged as
      // harmless, so Enter in it asks first.
      if (el && (el.localName === 'iframe' || el.localName === 'frame')) {
        let inner = null;
        try { inner = el.contentDocument; } catch (error) { inner = null; }
        if (!inner) return true;
        el = inner.activeElement;
        continue;
      }
      break;
    }
    if (!el) return true;
    if (isPassword(el) || isCard(el)) return true;
    if (needsApproval(el)) return true;
    return !!el.form && !el.form.querySelector('input[type="search"],input[type="password"]') && el.form.getAttribute('role') !== 'search';
  };

  const setNative = (el, value) => {
    // A framework that wraps `value` only hears the prototype's own setter.
    const proto = el.localName === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  };

  // Focus a field with its whole content selected, so typed text replaces it.
  const focusForTyping = (ref) => {
    const el = byRef(ref);
    if (!el) return false;
    el.scrollIntoView({ block: 'center', inline: 'nearest' });
    el.focus();
    if (el.isContentEditable) {
      const range = document.createRange();
      range.selectNodeContents(el);
      const selection = getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
    } else if (typeof el.select === 'function') {
      el.select();
    }
    return deepFocus() === el || el.contains(document.activeElement);
  };

  const valueOf = (ref) => {
    const el = byRef(ref);
    if (!el || isPassword(el) || isCard(el)) return null;
    if (el.localName === 'input' || el.localName === 'textarea' || el.localName === 'select') return el.value;
    return el.isContentEditable ? el.innerText : null;
  };

  const stateOf = (ref) => {
    const el = byRef(ref);
    if (!el || isPassword(el) || isCard(el)) return null;
    const nativeCheck = el.localName === 'input' && ['checkbox', 'radio'].includes(el.type);
    const aria = el.getAttribute('aria-checked');
    return { value: valueOf(ref), checked: nativeCheck ? el.checked : aria === 'true' ? true : aria === 'false' ? false : null };
  };

  const setValue = (ref, value) => {
    const el = byRef(ref);
    if (!el) return false;
    setNative(el, value);
    return true;
  };

  const choose = (ref, wanted) => {
    const el = byRef(ref);
    if (!el || el.localName !== 'select') return { error: 'not_a_select' };
    const text = String(wanted).trim().toLowerCase();
    const options = Array.from(el.options);
    const option = options.find((o) => o.value.toLowerCase() === text) ||
      options.find((o) => o.textContent.trim().toLowerCase() === text) ||
      options.find((o) => o.textContent.trim().toLowerCase().includes(text));
    if (!option) return { error: 'no_option', options: options.slice(0, 20).map((o) => clean(o.textContent, 40)) };
    el.value = option.value;
    option.selected = true;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return { chosen: clean(option.textContent, 80) };
  };

  // The thing that scrolls when a person swipes: the page, or the one big
  // panel an app shell scrolls instead.
  const mainScroller = () => {
    const page = document.scrollingElement || document.documentElement;
    if (page.scrollHeight > innerHeight + 16) return page;
    let best = page;
    let bestArea = 0;
    for (const el of document.querySelectorAll('*')) {
      if (el.scrollHeight <= el.clientHeight + 16) continue;
      const overflow = getComputedStyle(el).overflowY;
      if (overflow !== 'auto' && overflow !== 'scroll') continue;
      const area = el.clientWidth * el.clientHeight;
      if (area > bestArea) { best = el; bestArea = area; }
    }
    return best;
  };

  const scroll = (direction, ref) => {
    if (ref !== undefined && ref !== null) {
      const el = byRef(ref);
      if (!el) return { error: 'stale_ref' };
      el.scrollIntoView({ block: 'center', inline: 'nearest' });
    } else {
      const target = mainScroller();
      const step = Math.round((target === document.scrollingElement ? innerHeight : target.clientHeight) * 0.8);
      if (direction === 'top') target.scrollTop = 0;
      else if (direction === 'bottom') target.scrollTop = target.scrollHeight;
      else target.scrollTop += direction === 'up' ? -step : step;
    }
    const target = mainScroller();
    return {
      scrollY: Math.round(target.scrollTop),
      scrollMax: Math.max(0, Math.round(target.scrollHeight - target.clientHeight)),
    };
  };

  // Resolves once the DOM has stopped changing for `quietMs`, or at `maxMs`.
  const settle = (quietMs, maxMs) => new Promise((resolve) => {
    let timer = null;
    const done = () => { observer.disconnect(); clearTimeout(timer); clearTimeout(limit); resolve(true); };
    const observer = new MutationObserver(() => { clearTimeout(timer); timer = setTimeout(done, quietMs); });
    observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
    timer = setTimeout(done, quietMs);
    const limit = setTimeout(done, maxMs);
  });

  const present = (condition) => {
    if (condition.selector) {
      let el = null;
      try { el = document.querySelector(condition.selector); } catch (error) { return false; }
      return !!el && visible(el);
    }
    if (condition.text) {
      const body = document.body ? document.body.innerText : '';
      return body.toLowerCase().includes(String(condition.text).toLowerCase());
    }
    return false;
  };

  // The page as reading text: headings, paragraphs, lists and links, without
  // the navigation around them.
  const read = () => {
    const root = document.querySelector('article, main, [role="main"]') || document.body;
    const out = [];
    const BLOCKS = new Set(['p', 'div', 'section', 'article', 'li', 'ul', 'ol', 'table', 'tr', 'blockquote',
      'pre', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'dd', 'dt', 'figcaption', 'main', 'header', 'footer']);
    const CHROME = new Set(['nav', 'aside', 'footer', 'form', 'button', 'select', 'input', 'textarea']);
    let line = '';
    const flush = () => { const text = line.replace(/\s+/g, ' ').trim(); if (text) out.push(text); line = ''; };
    const visit = (node) => {
      if (node.nodeType === Node.TEXT_NODE) { line += node.nodeValue; return; }
      if (node.nodeType !== Node.ELEMENT_NODE) return;
      const tag = node.localName;
      if (SKIP.has(tag) && tag !== 'br') return;
      if (tag === 'br') { flush(); return; }
      if (CHROME.has(tag) || tag === 'svg' || !visible(node)) return;
      if (tag === 'img') { const alt = node.getAttribute('alt'); if (alt) line += ' [image: ' + clean(alt, 80) + '] '; return; }
      if (tag === 'pre') { flush(); out.push('```\n' + node.innerText.slice(0, 4000) + '\n```'); return; }
      const block = BLOCKS.has(tag);
      if (block) flush();
      if (/^h[1-6]$/.test(tag)) line += '#'.repeat(Number(tag[1])) + ' ';
      if (tag === 'li') line += '- ';
      if (tag === 'a' && node.hasAttribute('href')) {
        const text = clean(node.innerText, 160);
        if (text) line += '[' + text + '](' + node.href + ')';
        return;
      }
      if (tag === 'td' || tag === 'th') line += ' | ';
      if (node.shadowRoot) for (const child of node.shadowRoot.childNodes) visit(child);
      for (const child of node.childNodes) visit(child);
      if (block) flush();
    };
    visit(root);
    flush();
    return { url: location.href, title: document.title, text: out.join('\n') };
  };

  globalThis.__mike = {
    snapshot, find, info, focusForTyping, valueOf, stateOf, setValue, choose, scroll, settle, present, read, submitNeedsApproval, hitCheck,
    element: (ref) => byRef(ref),
    isFocused: (ref) => deepFocus() === byRef(ref),
    focusedElement: deepFocus,
    focusedRef: () => { const el = deepFocus(); return el && !['iframe', 'frame'].includes(el.localName) ? refOf(el) : null; },
    focusToken: () => { const el = deepFocus(); return el ? approvalToken(el) : null; },
    acceptsHit,
  };
})();
