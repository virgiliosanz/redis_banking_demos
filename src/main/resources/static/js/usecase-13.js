/**
 * UC13: Distributed Locking
 * Interactive demo: acquire/release locks, TTL countdown, contention simulation
 */
(function () {
    'use strict';

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

    function showResult(data) {
        resultLog.style.display = '';
        resultJson.textContent = window.formatJson(data);
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
        }
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
                return;
            }
            lockTtl.textContent = currentTtl + 's';
        }, 1000);
    }

    function refreshStatus() {
        var resourceId = resourceSelect.value;
        window.workshopGet('/api/lock/info/' + resourceId)
            .then(function (data) {
                updateLockDisplay(data);
                showResult(data);
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
        }).then(function (data) {
            btnAcquire.disabled = false;
            btnAcquire.textContent = 'Acquire Lock';
            showResult(data);
            refreshStatus();
        }).catch(function () {
            btnAcquire.disabled = false;
            btnAcquire.textContent = 'Acquire Lock';
        });
    });

    // --- Release ---
    btnRelease.addEventListener('click', function () {
        var resourceId = resourceSelect.value;
        var clientId = clientIdInput.value.trim() || 'anonymous';
        btnRelease.disabled = true;
        btnRelease.textContent = 'Releasing...';

        window.workshopFetch('/api/lock/release', {
            resourceId: resourceId,
            clientId: clientId
        }).then(function (data) {
            btnRelease.disabled = false;
            btnRelease.textContent = 'Release Lock';
            showResult(data);
            if (data.released) {
                updateLockDisplay({ locked: false });
            }
            refreshStatus();
        }).catch(function () {
            btnRelease.disabled = false;
            btnRelease.textContent = 'Release Lock';
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
        }).then(function (data) {
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
        }).catch(function () {
            btnSimulate.disabled = false;
            btnSimulate.textContent = 'Simulate 3 Concurrent Clients';
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
