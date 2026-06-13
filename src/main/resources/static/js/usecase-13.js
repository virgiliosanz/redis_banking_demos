/**
 * UC13: Distributed Locking
 * Interactive demo: acquire/release locks, TTL countdown, contention simulation
 */
(function () {
    'use strict';
    window.WORKSHOP_UC = 'UC13';

    // --- Code tabs: wire up the shared tab behavior (Java / WATCH-MULTI-EXEC / CLI) ---
    window.initCodeTabs();

    var resourceSelect = document.getElementById('resourceSelect');
    var clientIdInput = document.getElementById('clientIdInput');
    var btnAcquire = document.getElementById('btnAcquire');
    var btnRelease = document.getElementById('btnRelease');
    var btnRefresh = document.getElementById('btnRefresh');
    var btnSimulate = document.getElementById('btnSimulate');
    var lockBadge = document.getElementById('lockBadge');
    var lockHolder = document.getElementById('lockHolder');
    var lockTtl = document.getElementById('lockTtl');
    var resultLog = document.getElementById('resultLog');
    var resultJson = document.getElementById('resultJson');
    var simulationResult = document.getElementById('simulationResult');

    var ttlInterval = null;
    var currentTtl = 0;
    // Track the clientId that successfully acquired each resource in this session.
    // Used on release so the UI does not depend on the (mutable) input field value.
    var acquiredHolders = {};
    function showResult(data) {
        resultLog.style.display = '';
        resultJson.textContent = window.formatJson(data);
        window.animateResult(resultLog, 'fade-in');
    }

    function showFeedback(message, type) {
        window.showToast(message, type === 'error' ? 'error' : 'success');
    }

    function updateLockDisplay(info) {
        clearInterval(ttlInterval);
        if (info.locked) {
            lockBadge.textContent = '● LOCKED';
            lockBadge.className = 'status-badge active';
            lockBadge.style.background = 'var(--redis-red, #FF4438)';
            lockBadge.style.color = '#fff';
            lockHolder.textContent = 'Held by: ' + info.holder;
            currentTtl = info.ttl || 0;
            lockTtl.textContent = currentTtl + 's';
            startTtlCountdown();
        } else {
            lockBadge.textContent = '● UNLOCKED';
            lockBadge.className = 'status-badge';
            lockBadge.style.background = 'var(--success, #2ECC71)';
            lockBadge.style.color = '#fff';
            lockHolder.textContent = 'No active lock';
            lockTtl.textContent = '';
            // Clear any stale stored holder for this resource if Redis says it's unlocked.
            var rid = resourceSelect.value;
            if (acquiredHolders[rid]) { delete acquiredHolders[rid]; }
        }
        window.animateResult(lockBadge, 'highlight-new');
    }

    function startTtlCountdown() {
        clearInterval(ttlInterval);
        ttlInterval = setInterval(function () {
            currentTtl--;
            if (currentTtl <= 0) {
                clearInterval(ttlInterval);
                lockBadge.textContent = '● EXPIRED';
                lockBadge.style.background = 'var(--text-muted, #666)';
                lockHolder.textContent = 'Lock expired (TTL reached 0)';
                lockTtl.textContent = '0s';
                // Expired locks are no longer held by us.
                var rid = resourceSelect.value;
                if (acquiredHolders[rid]) { delete acquiredHolders[rid]; }
                return;
            }
            lockTtl.textContent = currentTtl + 's';
        }, 1000);
    }

    function refreshStatus() {
        var resourceId = resourceSelect.value;
        window.workshopGet('/api/lock/info/' + resourceId, 'btnAcquire')
            .then(function (data) {
                updateLockDisplay(data);
                showResult(data);
            })
            .catch(function (err) {
                showFeedback((err && err.message) || 'Could not load the current lock status.', 'error');
            });
    }

    // --- Acquire ---
    btnAcquire.addEventListener('click', function () {
        var resourceId = resourceSelect.value;
        var clientId = clientIdInput.value.trim() || 'anonymous';
        btnAcquire.disabled = true;
        btnAcquire.textContent = 'Acquiring...';

        window.workshopFetch('/api/lock/acquire', {
            resourceId: resourceId,
            clientId: clientId,
            ttlSeconds: 30
        }, 'btnAcquire').then(function (data) {
            btnAcquire.disabled = false;
            btnAcquire.textContent = 'Acquire Lock';
            showResult(data);
            if (data.acquired) {
                // Remember which clientId actually holds the lock so release
                // works even if the input field is later edited or cleared.
                acquiredHolders[resourceId] = clientId;
                showFeedback('Lock acquired by "' + clientId + '" — stored for safe release', 'success');
            } else {
                showFeedback('Acquire denied — lock already held by "' + (data.currentHolder || 'another client') + '"', 'error');
            }
            refreshStatus();
        }).catch(function () {
            btnAcquire.disabled = false;
            btnAcquire.textContent = 'Acquire Lock';
            showFeedback('Could not acquire the lock. Verify the app and Redis are running.', 'error');
        });
    });

    // --- Release ---
    btnRelease.addEventListener('click', function () {
        var resourceId = resourceSelect.value;
        // Prefer the clientId that actually acquired the lock in this session.
        // Fall back to the input only if we have no record (e.g. after a reload).
        var storedClientId = acquiredHolders[resourceId];
        var inputClientId = clientIdInput.value.trim() || 'anonymous';
        var clientId = storedClientId || inputClientId;
        btnRelease.disabled = true;
        btnRelease.textContent = 'Releasing...';

        window.workshopFetch('/api/lock/release', {
            resourceId: resourceId,
            clientId: clientId
        }, 'btnAcquire').then(function (data) {
            btnRelease.disabled = false;
            btnRelease.textContent = 'Release Lock';
            showResult(data);
            if (data.released) {
                delete acquiredHolders[resourceId];
                updateLockDisplay({ locked: false });
                showFeedback('Lock released by "' + clientId + '"', 'success');
            } else {
                showFeedback('Release denied — "' + clientId + '" does not hold this lock (safe-release Lua guard)', 'error');
            }
            refreshStatus();
        }).catch(function () {
            btnRelease.disabled = false;
            btnRelease.textContent = 'Release Lock';
            showFeedback('Could not release the lock. Verify the app and Redis are running.', 'error');
        });
    });

    // --- Refresh ---
    btnRefresh.addEventListener('click', refreshStatus);

    // --- Simulate Contention ---
    btnSimulate.addEventListener('click', function () {
        var resourceId = resourceSelect.value;
        btnSimulate.disabled = true;
        btnSimulate.textContent = 'Simulating...';
        simulationResult.style.display = 'none';

        window.workshopFetch('/api/lock/simulate', {
            resourceId: resourceId
        }, 'btnAcquire').then(function (data) {
            btnSimulate.disabled = false;
            btnSimulate.textContent = 'Simulate 3 Concurrent Clients';
            showResult(data);
            refreshStatus();

            // Show contention results
            var html = '<div style="font-size:0.85rem;">';
            html += '<p style="margin-bottom:8px;"><strong>Winner:</strong> ' +
                '<span style="color:var(--redis-red, #FF4438); font-weight:700;">' + (data.winner || 'none') + '</span></p>';
            if (data.attempts) {
                data.attempts.forEach(function (a) {
                    var icon = a.acquired ? '✓' : '✗';
                    var color = a.acquired ? 'var(--success, #2ECC71)' : 'var(--text-muted, #666)';
                    html += '<div style="padding:4px 8px; margin:4px 0; border-radius:5px; background:var(--bg-secondary, #f5f5f5);">';
                    html += icon + ' <strong>' + (a.clientId || a.client) + '</strong> — ';
                    html += '<span style="color:' + color + ';">' + (a.acquired ? 'ACQUIRED' : 'DENIED') + '</span>';
                    html += '</div>';
                });
            }
            html += '</div>';
            simulationResult.innerHTML = html;
            simulationResult.style.display = '';
            window.animateResult(simulationResult, 'fade-in slide-up');
        }).catch(function () {
            btnSimulate.disabled = false;
            btnSimulate.textContent = 'Simulate 3 Concurrent Clients';
            showFeedback('Could not simulate lock contention.', 'error');
        });
    });

    // Refresh lock status when resource changes
    resourceSelect.addEventListener('change', function () {
        simulationResult.style.display = 'none';
        refreshStatus();
    });

    // Initial status check
    refreshStatus();
})();
