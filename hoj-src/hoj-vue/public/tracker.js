/**
 * HOJ Behavior Tracker SDK — 用户行为采集
 * 嵌入方式: <script src="/tracker.js"></script>
 * 上报目标: hoj-behavior 服务
 */
(function() {
  'use strict';

  var CONFIG = {
    endpoint: (window.HOJ_BEHAVIOR_URL || '') + '/api/beacon/batch',
    batchEndpoint: (window.HOJ_BEHAVIOR_URL || '') + '/api/beacon/batch',
    apiKey: window.HOJ_BEHAVIOR_KEY || '',
    batchSize: 20,
    flushInterval: 5000,
  };

  var buffer = [];

  function getUserId() {
    var meta = document.querySelector('meta[name="hoj-uid"]');
    if (meta && meta.content) return meta.content;
    try { return localStorage.getItem('hoj_uid') || 'anonymous'; }
    catch(e) { return 'anonymous'; }
  }

  function getCurrentProblemId() {
    var m = location.pathname.match(/\/problem\/(\d+)/);
    return m ? parseInt(m[1]) : null;
  }

  function getCurrentContestId() {
    var m = location.pathname.match(/\/contest\/(\d+)/);
    return m ? parseInt(m[1]) : null;
  }

  function isInContest() {
    return !!getCurrentContestId();
  }

  function flush() {
    if (buffer.length === 0) return;
    var batch = buffer.splice(0, CONFIG.batchSize);
    var payload = JSON.stringify(batch);

    if (navigator.sendBeacon) {
      var blob = new Blob([payload], { type: 'application/json' });
      navigator.sendBeacon(CONFIG.batchEndpoint, blob);
    } else {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', CONFIG.batchEndpoint, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      if (CONFIG.apiKey) xhr.setRequestHeader('X-Api-Key', CONFIG.apiKey);
      xhr.timeout = 3000;
      xhr.onerror = function() {
        for (var i = 0; i < batch.length; i++) buffer.unshift(batch[i]);
      };
      xhr.send(payload);
    }
  }

  function track(event, payload) {
    var uid = getUserId();
    if (!uid) return;
    buffer.push({
      uid: uid,
      event: event,
      pid: payload && payload.pid ? payload.pid : getCurrentProblemId(),
      cid: payload && payload.cid ? payload.cid : getCurrentContestId(),
      payload: payload || {},
      timestamp: Date.now()
    });
    if (buffer.length >= CONFIG.batchSize) flush();
  }

  // --- Auto tracking ---
  function autoTrack() {
    var currentPath = location.pathname;

    // Page view
    track('page_view', { path: currentPath, title: document.title, in_contest: isInContest() });

    // Problem page — track stay time
    if (currentPath.indexOf('/problem/') !== -1) {
      var problemStart = Date.now();
      window.addEventListener('beforeunload', function() {
        track('problem_stay', { duration: Date.now() - problemStart });
      });
    }

    // Scroll depth
    var maxScroll = 0;
    var scrollTimer = null;
    window.addEventListener('scroll', function() {
      if (scrollTimer) clearTimeout(scrollTimer);
      scrollTimer = setTimeout(function() {
        var scrollPercent = Math.round(
          (window.scrollY + window.innerHeight) / document.documentElement.scrollHeight * 100
        );
        if (scrollPercent > maxScroll) {
          maxScroll = scrollPercent;
          track('scroll_depth', { percentage: scrollPercent });
        }
      }, 500);
    });

    // Listen for custom HOJ events dispatched by the Vue app
    document.addEventListener('hoj-submit', function(e) {
      track('submit', e.detail || {});
    });
    document.addEventListener('hoj-submit-result', function(e) {
      track('submit_result', e.detail || {});
    });
    document.addEventListener('hoj-editor-focus', function() {
      track('editor_active', { action: 'focus' });
    });
    document.addEventListener('hoj-view-solution', function(e) {
      track('view_solution', e.detail || {});
    });
    document.addEventListener('hoj-run-test', function(e) {
      track('run_test', e.detail || {});
    });
  }

  // --- Click tracking for links ---
  document.addEventListener('click', function(e) {
    var link = e.target.closest('a');
    if (link && link.href) {
      var pidMatch = link.href.match(/\/problem\/(\d+)/);
      if (pidMatch) {
        track('open_problem', { pid: parseInt(pidMatch[1]), from: location.pathname });
      }
    }
  });

  // --- Init ---
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', autoTrack);
  } else {
    autoTrack();
  }

  // Periodic flush
  setInterval(flush, CONFIG.flushInterval);

  // Flush on page unload
  window.addEventListener('beforeunload', flush);
  window.addEventListener('pagehide', flush);

  // Expose API
  window.HOJ = window.HOJ || {};
  window.HOJ.track = track;
  window.HOJ.getUserId = getUserId;
})();
