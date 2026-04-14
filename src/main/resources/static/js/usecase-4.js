/** UC4: Rate Limiting — Open Banking API Protection (PSD2) */
(function () {
    'use strict';

    // --- DOM refs ---
    var gaugeFill      = document.getElementById('gaugeFill');
    var remainingCount = document.getElementById('remainingCount');
    var limitCount     = document.getElementById('limitCount');
    var statusBox      = document.getElementById('statusBox');
    var statusIcon     = document.getElementById('statusIcon');
    var statusText     = document.getElementById('statusText');
    var ttlBox         = document.getElementById('ttlBox');
    var ttlValue       = document.getElementById('ttlValue');
    var requestLog     = document.getElementById('requestLog');
    var btnCallApi     = document.getElementById('btnCallApi');
    var btnBurst       = document.getElementById('btnBurst');
    var btnReset       = document.getElementById('btnReset');

    var limit = 10;
    var ttlInterval = null;

    // --- Code tabs ---
    document.querySelectorAll('.code-tab').forEach(function (tab) {
        tab.addEventListener('click', function () {
            document.querySelectorAll('.code-tab').forEach(function (t) { t.classList.remove('active'); });
            document.querySelectorAll('.code-tab-content').forEach(function (c) { c.classList.remove('active'); });
            tab.classList.add('active');
            var target = document.getElementById('tab-' + tab.getAttribute('data-tab'));
            if (target) target.classList.add('active');
        });
    });

    // --- Gauge update ---
    function updateGauge(remaining, max) {
        var pct = (remaining / max) * 100;
        gaugeFill.style.width = pct + '%';
        remainingCount.textContent = remaining;
        limitCount.textContent = max;

        // Color transitions
        if (pct > 50) {
            gaugeFill.style.background = 'var(--redis-primary)';
        } else if (pct > 20) {
            gaugeFill.style.background = '#F59E0B';
        } else {
            gaugeFill.style.background = '#EF4444';
        }
    }

    // --- Status display ---
    function showStatus(allowed, data) {
        if (allowed) {
            statusBox.className = 'rl-status rl-status-ok';
            statusIcon.textContent = '✅';
            statusText.textContent = '200 OK — Request ' + data.currentCount + '/' + data.limit + ' allowed';
        } else {
            statusBox.className = 'rl-status rl-status-blocked';
            statusIcon.textContent = '🚫';
            statusText.textContent = '429 Too Many Requests — retry after ' + data.retryAfter + 's';
        }
    }

    // --- TTL countdown ---
    function startTtlCountdown(ttl) {
        if (ttl <= 0) { ttlBox.style.display = 'none'; return; }
        ttlBox.style.display = 'block';
        ttlValue.textContent = ttl;

        clearInterval(ttlInterval);
        var remaining = ttl;
        ttlInterval = setInterval(function () {
            remaining--;
            ttlValue.textContent = Math.max(0, remaining);
            if (remaining <= 0) {
                clearInterval(ttlInterval);
                ttlBox.style.display = 'none';
                refreshStatus();
            }
        }, 1000);
    }

    // --- Log entry ---
    function addLogEntry(data) {
        var entry = document.createElement('div');
        entry.className = 'rl-log-entry ' + (data.allowed ? 'rl-log-ok' : 'rl-log-blocked');
        var time = new Date().toLocaleTimeString();
        var statusCode = data.allowed ? '200' : '429';
        entry.innerHTML = '<span class="rl-log-time">' + time + '</span>' +
            '<span class="rl-log-status">' + statusCode + '</span>' +
            '<span class="rl-log-detail">Request #' + data.currentCount +
            ' — ' + data.remaining + ' remaining</span>';
        requestLog.insertBefore(entry, requestLog.firstChild);

        // Keep only last 15 entries
        while (requestLog.children.length > 15) {
            requestLog.removeChild(requestLog.lastChild);
        }
    }

    // --- API call ---
    function callApi() {
        btnCallApi.disabled = true;
        fetch('/api/ratelimit/check', { method: 'POST' })
            .then(function (res) { return res.json(); })
            .then(function (data) {
                limit = data.limit;
                updateGauge(data.remaining, data.limit);
                showStatus(data.allowed, data);
                addLogEntry(data);
                if (data.ttl > 0) startTtlCountdown(data.ttl);
            })
            .catch(function (err) {
                statusBox.className = 'rl-status rl-status-blocked';
                statusIcon.textContent = '⚠️';
                statusText.textContent = 'Error: ' + err.message;
            })
            .finally(function () { btnCallApi.disabled = false; });
    }

    // --- Burst ---
    function burstCalls() {
        btnBurst.disabled = true;
        var calls = [];
        for (var i = 0; i < 5; i++) {
            calls.push(fetch('/api/ratelimit/check', { method: 'POST' }).then(function (r) { return r.json(); }));
        }
        Promise.all(calls).then(function (results) {
            results.forEach(function (data) {
                updateGauge(data.remaining, data.limit);
                showStatus(data.allowed, data);
                addLogEntry(data);
                if (data.ttl > 0) startTtlCountdown(data.ttl);
            });
        }).finally(function () { btnBurst.disabled = false; });
    }

    // --- Reset ---
    function resetLimit() {
        fetch('/api/ratelimit/reset', { method: 'POST' })
            .then(function () {
                updateGauge(limit, limit);
                statusBox.className = 'rl-status rl-status-ok';
                statusIcon.textContent = '✅';
                statusText.textContent = 'Rate limit reset — ready for new requests';
                ttlBox.style.display = 'none';
                clearInterval(ttlInterval);
                requestLog.innerHTML = '';
            });
    }

    // --- Refresh status (used after TTL expires) ---
    function refreshStatus() {
        fetch('/api/ratelimit/status')
            .then(function (res) { return res.json(); })
            .then(function (data) {
                limit = data.limit;
                updateGauge(data.remaining, data.limit);
                if (!data.active) {
                    statusBox.className = 'rl-status rl-status-ok';
                    statusIcon.textContent = '✅';
                    statusText.textContent = 'Window expired — counter reset. Ready for new requests!';
                }
            });
    }

    // --- Init ---
    btnCallApi.addEventListener('click', callApi);
    btnBurst.addEventListener('click', burstCalls);
    btnReset.addEventListener('click', resetLimit);

    // Load initial status
    refreshStatus();

    // Re-highlight code after tab switch
    document.querySelectorAll('.code-tab').forEach(function (tab) {
        tab.addEventListener('click', function () {
            if (window.Prism) Prism.highlightAll();
        });
    });
})();
