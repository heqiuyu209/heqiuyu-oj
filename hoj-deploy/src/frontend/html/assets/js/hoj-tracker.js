/**
 * HOJ Behavior Tracker — lightweight, non-blocking, batch-upload.
 * Disabled automatically when document body has data-contest-mode="true".
 */
;(function () {
  'use strict';

  if (typeof window.__HOJ_TRACKER_LOADED !== 'undefined') return;
  window.__HOJ_TRACKER_LOADED = true;

  var BEACON_URL = '/api/beacon';
  var BATCH_URL = '/api/beacon/batch';
  var FLUSH_INTERVAL = 5000;   // 5s
  var MAX_BATCH = 20;          // per flush

  var buffer = [];
  var timer = null;
  var disabled = false;

  // --- helpers ---
  function getUid() {
    try {
      var raw = localStorage.getItem('hoj_user') || localStorage.getItem('user');
      if (raw) {
        var u = JSON.parse(raw);
        return u.uid || u.id || u.uuid || null;
      }
    } catch (_) { /* ignore */ }
    return window.__HOJ_UID__ || null;
  }

  function isContest() {
    if (disabled) return true;
    try {
      var body = document.body;
      if (body && body.getAttribute('data-contest-mode') === 'true') return true;
    } catch (_) { /* ignore */ }
    return false;
  }

  function sendBatch(events) {
    if (!events.length) return;
    var payload = JSON.stringify(events);
    // prefer sendBeacon for reliability
    if (navigator.sendBeacon) {
      navigator.sendBeacon(BATCH_URL, payload);
    } else {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', BATCH_URL, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.send(payload);
    }
  }

  function flush() {
    if (isContest() || buffer.length === 0) return;
    var batch = buffer.splice(0, MAX_BATCH);
    sendBatch(batch);
  }

  function scheduleFlush() {
    if (timer) return;
    timer = setInterval(flush, FLUSH_INTERVAL);
  }

  function trackEvent(eventName, pid, payload) {
    if (isContest()) return;
    var uid = getUid();
    if (!uid) return; // don't track anonymous
    buffer.push({
      uid: uid,
      event: eventName,
      pid: pid || null,
      payload: payload || {},
      timestamp: Math.floor(Date.now() / 1000)
    });
    if (buffer.length >= MAX_BATCH * 2) flush();
  }

  // --- public API ---
  window.HOJTracker = {
    track: trackEvent,

    identify: function (uid) {
      window.__HOJ_UID__ = uid;
    },

    enable: function () { disabled = false; },
    disable: function () { disabled = true; },
    isDisabled: function () { return disabled || isContest(); },

    flush: flush
  };

  // --- auto-start ---
  scheduleFlush();

  // --- auto page-view ---
  if (document.readyState === 'complete') {
    trackEvent('page_view', null, { url: location.pathname });
  } else {
    window.addEventListener('load', function () {
      trackEvent('page_view', null, { url: location.pathname });
    });
  }
})();
