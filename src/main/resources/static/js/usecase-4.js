/** UC4: Rate Limiting — Open Banking API Protection (PSD2) */
(function () {
    'use strict';
    window.WORKSHOP_UC = 'UC4';

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
    window.initCodeTabs();

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
            gaugeFill.style.background = getComputedStyle(document.documentElement).getPropertyValue('--color-warning').trim();
        } else {
            gaugeFill.style.background = getComputedStyle(document.documentElement).getPropertyValue('--color-danger').trim();
        }
    }

    // --- Status display ---
    function showStatus(allowed, data) {
        if (allowed) {
            statusBox.className = 'rl-status rl-status-ok';
            statusIcon.textContent = '\u2713';
            statusText.textContent = '200 OK — Request ' + data.currentCount + '/' + data.limit + ' allowed';
        } else {
            statusBox.className = 'rl-status rl-status-blocked';
            statusIcon.textContent = '\u2717';
            statusText.textContent = '429 Too Many Requests — retry after ' + data.retryAfter + 's';
        }
        window.animateResult(statusBox, allowed ? 'fade-in' : 'fade-in highlight-new');
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
        window.appendAnimatedElement(requestLog, entry, 'slide-up highlight-new', true);

        // Keep only last 15 entries
        while (requestLog.children.length > 15) {
            requestLog.removeChild(requestLog.lastChild);
        }
    }

    // --- API call ---
    function callApi() {
        btnCallApi.disabled = true;
        window.workshopFetch('/api/ratelimit/check', {}, 'btnCallApi')
            .then(function (data) {
                limit = data.limit;
                updateGauge(data.remaining, data.limit);
                showStatus(data.allowed, data);
                addLogEntry(data);
                if (data.ttl > 0) startTtlCountdown(data.ttl);
                if (!data.allowed) {
                    window.showToast('Rate limit exceeded. Retry after ' + data.retryAfter + 's.', 'warning');
                }
            })
            .catch(function (err) {
                statusBox.className = 'rl-status rl-status-blocked';
                statusIcon.textContent = '!';
                statusText.textContent = 'Error: ' + err.message;
                window.showToast(err.message || 'Rate-limit check failed.', 'error');
            })
            .finally(function () { btnCallApi.disabled = false; });
    }

    // --- Burst ---
    function burstCalls() {
        btnBurst.disabled = true;
        var calls = [];
        for (var i = 0; i < 5; i++) {
            calls.push(window.workshopFetch('/api/ratelimit/check', {}, 'btnCallApi'));
        }
        Promise.all(calls).then(function (results) {
            var blockedCount = 0;
            results.forEach(function (data) {
                updateGauge(data.remaining, data.limit);
                showStatus(data.allowed, data);
                addLogEntry(data);
                if (data.ttl > 0) startTtlCountdown(data.ttl);
                if (!data.allowed) blockedCount++;
            });
            window.showToast(
                blockedCount > 0
                    ? ('Burst finished — ' + blockedCount + ' requests were rate-limited.')
                    : 'Burst finished — all 5 requests were allowed.',
                blockedCount > 0 ? 'warning' : 'success'
            );
        }).catch(function (err) {
            window.showToast((err && err.message) || 'Burst simulation failed.', 'error');
        }).finally(function () { btnBurst.disabled = false; });
    }

    // --- Reset ---
    function resetLimit() {
        window.workshopFetch('/api/ratelimit/reset', {}, 'btnCallApi')
            .then(function () {
                updateGauge(limit, limit);
                statusBox.className = 'rl-status rl-status-ok';
                statusIcon.textContent = '\u2713';
                statusText.textContent = 'Rate limit reset — ready for new requests';
                ttlBox.style.display = 'none';
                clearInterval(ttlInterval);
                requestLog.innerHTML = '';
                window.showToast('Rate limit window reset.', 'success');
            })
            .catch(function (err) {
                window.showToast((err && err.message) || 'Could not reset the rate limit window.', 'error');
            });
    }

    // --- Refresh status (used after TTL expires) ---
    function refreshStatus() {
        window.workshopGet('/api/ratelimit/status', 'btnCallApi')
            .then(function (data) {
                limit = data.limit;
                updateGauge(data.remaining, data.limit);
                if (!data.active) {
                    statusBox.className = 'rl-status rl-status-ok';
                    statusIcon.textContent = '\u2713';
                    statusText.textContent = 'Window expired — counter reset. Ready for new requests!';
                }
            })
            .catch(function (err) {
                window.showToast((err && err.message) || 'Could not refresh the rate-limit status.', 'error');
            });
    }

    // --- Init ---
    btnCallApi.addEventListener('click', callApi);
    btnBurst.addEventListener('click', burstCalls);
    btnReset.addEventListener('click', resetLimit);

    // Load initial status
    refreshStatus();


})();
