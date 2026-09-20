/* Tao Connect: bilingual DOM annotations. No source text is replaced. */
(() => {
  'use strict';
  const config = window.__taoConfig || {};
  const host = location.hostname.toLowerCase();
  const taobao = location.protocol === 'https:' && (host === 'taobao.com' || host.endsWith('.taobao.com'));
  const fixture = config.testMode === true && location.origin === 'https://appassets.androidplatform.net' && location.pathname.startsWith('/assets/qa/');
  if ((!taobao && !fixture) || !window.TaoNative) return;
  if (window.__taoConnect) { window.__taoConnect.setEnabled(config.enabled !== false); return; }

  const NOTE = '[data-tao-fr]';
  const EXCLUDED = 'script,style,noscript,textarea,input,select,option,code,pre,svg,math,canvas,[contenteditable]:not([contenteditable="false"]),[translate="no"],[aria-hidden="true"],'+NOTE;
  const BLOCK_CHILD = 'div,p,section,article,li,ul,ol,table,button,input,textarea,select,h1,h2,h3,h4';
  const HAN = /[\u3400-\u9fff\uf900-\ufaff]/;
  const records = new Map();
  const cache = new Map();
  const waiting = new Map();
  const requests = new Map();
  const dirty = new Set();
  const scanning = new Map();
  let enabled = false, serial = 0, frame = 0, batchTimer = 0, scrollTimer = 0;
  let scrolling = false, scans = 0, sent = 0, generation = 0;
  const normalize = text => text.replace(/\s+/g, ' ').trim();
  const idle = window.requestIdleCallback ? callback => requestIdleCallback(callback, {timeout: 100}) : callback => setTimeout(() => callback({timeRemaining: () => 8}), 16);
  const visible = el => el.isConnected && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden';

  function sourceOf(el) {
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, {
      acceptNode: node => node.parentElement && !node.parentElement.closest(EXCLUDED) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT
    });
    let text = '', node;
    while ((node = walker.nextNode()) && text.length <= 600) text += node.nodeValue;
    return normalize(text);
  }

  function ownerOf(node) {
    let el = node.parentElement;
    if (!el || el.closest(EXCLUDED)) return null;
    // Group formatting spans into one phrase, without crossing a card or form.
    while (/^(SPAN|B|STRONG|EM|I|SMALL)$/.test(el.tagName) && el.parentElement &&
      !/^(BODY|HTML)$/.test(el.parentElement.tagName) && !el.parentElement.matches(EXCLUDED) &&
      !el.parentElement.querySelector(BLOCK_CHILD)) el = el.parentElement;
    if (/^(BODY|HTML)$/.test(el.tagName) || el.querySelector(BLOCK_CHILD)) return null;
    return el;
  }

  function setStyle(record, property, value) {
    const el = record.el;
    if (!record.styles.has(property)) record.styles.set(property, [el.style.getPropertyValue(property), el.style.getPropertyPriority(property), value]);
    el.style.setProperty(property, value, 'important');
  }

  function removeNote(record) {
    if (record.note) record.note.remove();
    record.note = null;
    for (const [property, [value, priority, applied]] of record.styles) {
      // Do not overwrite a subsequent change made by Taobao.
      if (record.el.style.getPropertyValue(property) === applied) {
        if (value) record.el.style.setProperty(property, value, priority);
        else record.el.style.removeProperty(property);
      }
    }
    record.styles.clear();
  }

  function render(record, translation) {
    if (!enabled || !translation || !visible(record.el) || record.source !== sourceOf(record.el)) return;
    if (scrolling) { record.result = translation; return; }
    if (record.note && record.note.isConnected && record.note.textContent === translation) return;
    removeNote(record);
    const el = record.el, style = getComputedStyle(el);
    const note = document.createElement('span');
    note.setAttribute('data-tao-fr', '');
    note.setAttribute('lang', 'fr');
    note.setAttribute('translate', 'no');
    note.textContent = translation;
    // In normal document flow: the browser itself scrolls both languages together.
    const size = Math.max(12, Math.min(15, (parseFloat(style.fontSize) || 16) * 0.86));
    note.style.cssText = 'display:block!important;position:static!important;float:none!important;box-sizing:border-box!important;'+
      'width:auto!important;max-width:100%!important;height:auto!important;min-height:0!important;'+
      'margin:4px 0 2px!important;padding:0!important;white-space:normal!important;overflow-wrap:anywhere!important;'+
      'text-overflow:clip!important;overflow:visible!important;-webkit-line-clamp:unset!important;'+
      'font-family:system-ui,sans-serif!important;font-weight:400!important;font-style:normal!important;'+
      'line-height:1.35!important;letter-spacing:normal!important;text-transform:none!important;'+
      'color:inherit!important;background:transparent!important;border:0!important;opacity:.86!important;'+
      'flex:0 0 100%!important;font-size:'+size+'px!important;';
    record.note = note;
    if (style.display === 'inline') setStyle(record, 'display', 'inline-block');
    if (style.display.includes('flex')) setStyle(record, 'flex-wrap', 'wrap');
    if (style.display === '-webkit-box') setStyle(record, 'display', 'block');
    setStyle(record, '-webkit-line-clamp', 'unset');
    setStyle(record, 'white-space', 'normal');
    const fixedHeight = parseFloat(style.height);
    if (fixedHeight > 0 && fixedHeight < 140) {
      setStyle(record, 'min-height', fixedHeight+'px');
      setStyle(record, 'height', 'auto');
      setStyle(record, 'max-height', 'none');
    }
    setStyle(record, 'overflow', 'visible');
    el.appendChild(note);
    record.result = translation;
  }

  function remember(source, result) {
    if (!result) return;
    cache.delete(source); cache.set(source, result);
    if (cache.size > 512) cache.delete(cache.keys().next().value);
  }

  function enqueue(record) {
    if (!enabled || !visible(record.el) || record.queued) return;
    if (record.result) { render(record, record.result); return; }
    if (cache.has(record.source)) { render(record, cache.get(record.source)); return; }
    record.queued = true;
    if (!waiting.has(record.source)) waiting.set(record.source, new Set());
    waiting.get(record.source).add(record);
    scheduleBatch();
  }

  function scheduleBatch() {
    if (!enabled || batchTimer) return;
    batchTimer = setTimeout(() => { batchTimer = 0; flush(); }, 70);
  }

  function flush() {
    if (!enabled || requests.size >= 2 || !waiting.size) return;
    const entries = Array.from(waiting.entries()).slice(0, 12);
    entries.forEach(([text]) => waiting.delete(text));
    const id = generation+':'+(++serial), texts = entries.map(([text]) => text);
    const request = {entries, generation, done:new Set(), timer: setTimeout(() => settle(id, []), 300000)};
    requests.set(id, request); sent++;
    try { window.TaoNative.postMessage(JSON.stringify({type:'translate', id, texts})); }
    catch (_) { settle(id, []); }
    if (waiting.size) scheduleBatch();
  }

  function settle(id, translations, partial = false) {
    const request = requests.get(id);
    if (!request) return;
    if (!partial) { clearTimeout(request.timer); requests.delete(id); }
    request.entries.forEach(([source, owners], index) => {
      const result = typeof translations[index] === 'string' ? translations[index] : null;
      if (request.done.has(index) || (partial && !result)) return;
      request.done.add(index);
      if (result) remember(source, result);
      owners.forEach(record => {
        record.queued = false;
        if (enabled && request.generation === generation && records.get(record.el) === record && record.source === sourceOf(record.el)) {
          if (result) render(record, result);
          else record.failed = true; // Retry via refresh; never hammer an unavailable model.
        }
      });
    });
    scheduleBatch();
  }

  window.TaoNative.onmessage = event => {
    try { const data = JSON.parse(event.data); if (Array.isArray(data.translations)) settle(data.id, data.translations, data.partial === true); }
    catch (_) { /* Ignore malformed replies. Original text remains visible. */ }
  };

  function inspect(el) {
    if (!visible(el)) return;
    const source = sourceOf(el);
    let record = records.get(el);
    if (record && record.source !== source) {
      removeNote(record); intersection.unobserve(el); records.delete(el); record = null;
    }
    if (!HAN.test(source) || source.length > 600 || /https?:\/\/|www\.|[¥￥$€£]\s*\d/.test(source)) return;
    if (!record) {
      if (records.size >= 1200) prune(true);
      if (records.size >= 1200) return;
      record = {el, source, note:null, result:null, queued:false, failed:false, styles:new Map()};
      records.set(el, record); intersection.observe(el);
    } else if (record.result && (!record.note || !record.note.isConnected)) render(record, record.result);
    const rect = el.getBoundingClientRect();
    if (!record.failed && rect.bottom >= -300 && rect.top < innerHeight+400) enqueue(record);
  }

  const intersection = new IntersectionObserver(entries => {
    if (!enabled) return;
    entries.forEach(entry => { const record = records.get(entry.target); if (entry.isIntersecting && record && !record.failed) enqueue(record); });
  }, {rootMargin:'400px 0px'});

  function prune(evict) {
    for (const [el, record] of records) {
      if (!el.isConnected) { removeNote(record); intersection.unobserve(el); records.delete(el); }
      if (evict && records.size < 1000) break;
    }
  }

  function scan(root) {
    if (!enabled || !root.isConnected || root.nodeType !== 1 || root.closest(EXCLUDED)) return;
    if (scanning.has(root)) { scanning.set(root, true); return; }
    scanning.set(root, false);
    const scanGeneration = generation;
    // Incremental TreeWalker; never translate scripts, attributes or form values.
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: node => HAN.test(node.nodeValue) && node.parentElement && !node.parentElement.closest(EXCLUDED) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT
    });
    const visit = deadline => {
      if (!enabled || !root.isConnected || generation !== scanGeneration) { scanning.delete(root); return; }
      let node, count = 0;
      const seen = new Set();
      while ((node = walker.nextNode())) {
        const el = ownerOf(node);
        if (el && !seen.has(el)) { seen.add(el); inspect(el); }
        if (++count >= 120 || deadline.timeRemaining() < 1) { idle(visit); return; }
      }
      const again = scanning.get(root); scanning.delete(root);
      if (again) schedule(root);
    };
    scans++; idle(visit);
  }

  function schedule(root) {
    if (!enabled || !root || root.closest(EXCLUDED)) return;
    for (const existing of dirty) if (existing.contains(root)) return;
    for (const existing of dirty) if (root.contains(existing)) dirty.delete(existing);
    dirty.add(root);
    if (!frame) frame = requestAnimationFrame(() => {
      frame = 0;
      const roots = Array.from(dirty); dirty.clear();
      roots.forEach(scan); prune(false);
    });
  }

  const observer = new MutationObserver(mutations => {
    for (const mutation of mutations) {
      const el = mutation.target.nodeType === 1 ? mutation.target : mutation.target.parentElement;
      if (!el || el.closest(NOTE)) continue;
      if (mutation.type === 'attributes' && mutation.attributeName === 'style' && records.get(el)?.styles.size) continue;
      if (mutation.type === 'childList' && [...mutation.addedNodes, ...mutation.removedNodes].every(node => node.nodeType === 1 && node.matches(NOTE))) continue;
      // Also invalidate Chinese -> price/Latin changes, which a Han-only scan would miss.
      for (let parent = el, depth = 0; parent && depth < 8; parent = parent.parentElement, depth++) {
        const record = records.get(parent);
        if (record && record.source !== sourceOf(parent)) {
          removeNote(record); intersection.unobserve(parent); records.delete(parent);
        }
      }
      schedule(el);
    }
  });

  function setEnabled(value) {
    if (enabled === !!value) return;
    enabled = !!value; generation++;
    if (enabled) {
      observer.observe(document.documentElement, {subtree:true, childList:true, characterData:true, attributes:true, attributeFilter:['class','style','hidden','open','aria-hidden']});
      schedule(document.body || document.documentElement);
    } else {
      observer.disconnect(); intersection.disconnect(); dirty.clear(); waiting.clear(); scanning.clear();
      clearTimeout(batchTimer); batchTimer = 0;
      clearTimeout(scrollTimer); scrolling = false;
      cancelAnimationFrame(frame); frame = 0;
      requests.forEach(request => clearTimeout(request.timer)); requests.clear();
      records.forEach(removeNote); records.clear();
    }
  }

  document.addEventListener('scroll', () => {
    if (!enabled) return;
    scrolling = true; clearTimeout(scrollTimer);
    scrollTimer = setTimeout(() => {
      scrolling = false;
      records.forEach(record => { if (record.result && (!record.note || !record.note.isConnected)) render(record, record.result); });
    }, 100);
  }, {passive:true, capture:true});

  window.__taoConnect = Object.freeze({setEnabled, stats: () => ({enabled, records:records.size, notes:document.querySelectorAll(NOTE).length, pending:requests.size, batches:sent, scans, cache:cache.size})});
  setEnabled(config.enabled !== false);
})();
