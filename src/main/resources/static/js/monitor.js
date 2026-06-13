/** Redis Monitor Dashboard */
(function () {
    'use strict';

    var statusEl = document.getElementById('monitorStatus');
    var statusTextEl = document.getElementById('monitorStatusText');
    var lastUpdatedEl = document.getElementById('monitorLastUpdated');
    var sparklineEl = document.getElementById('monitorOpsSparkline');
    var sparklineStatEl = document.getElementById('monitorSparklineStat');

    var metricEls = {
        ops: document.getElementById('monitorOpsPerSec'),
        memory: document.getElementById('monitorMemoryUsed'),
        keys: document.getElementById('monitorTotalKeys'),
        hitRate: document.getElementById('monitorHitRate'),
        clients: document.getElementById('monitorConnectedClients'),
        uptime: document.getElementById('monitorUptime'),
        commands: document.getElementById('monitorTotalCommands'),
        version: document.getElementById('monitorRedisVersion')
    };

    var opsHistory = [];
    var pollHandle = null;

    function formatNumber(value) {
        return Number(value || 0).toLocaleString();
    }

    function formatHitRate(hits, misses) {
        var total = hits + misses;
        if (!total) return '0.0%';
        return ((hits / total) * 100).toFixed(1) + '%';
    }

    function formatUptime(seconds) {
        var totalSeconds = Number(seconds || 0);
        var days = Math.floor(totalSeconds / 86400);
        var hours = Math.floor((totalSeconds % 86400) / 3600);
        var minutes = Math.floor((totalSeconds % 3600) / 60);
        return days + 'd ' + hours + 'h ' + minutes + 'm';
    }

    function updateStatus(state, text) {
        statusEl.classList.remove('is-up', 'is-down', 'is-pending');
        statusEl.classList.add(state);
        statusTextEl.textContent = text;
    }

    function updateMetrics(data) {
        var hits = Number(data.keyspace_hits || 0);
        var misses = Number(data.keyspace_misses || 0);
        var opsPerSec = Number(data.instantaneous_ops_per_sec || 0);

        metricEls.ops.textContent = formatNumber(opsPerSec);
        metricEls.memory.textContent = data.used_memory_human || '--';
        metricEls.keys.textContent = formatNumber(data.db_size);
        metricEls.hitRate.textContent = formatHitRate(hits, misses);
        metricEls.clients.textContent = formatNumber(data.connected_clients);
        metricEls.uptime.textContent = formatUptime(data.uptime_seconds);
        metricEls.commands.textContent = formatNumber(data.total_commands_processed);
        metricEls.version.textContent = data.redis_version || '--';
        lastUpdatedEl.textContent = new Date().toLocaleTimeString();

        opsHistory.push(opsPerSec);
        if (opsHistory.length > 30) {
            opsHistory.shift();
        }
        renderSparkline();
    }

    function renderSparkline() {
        if (!opsHistory.length) {
            sparklineEl.innerHTML = '<text x="180" y="64" text-anchor="middle" class="monitor-sparkline-empty">Waiting for metrics…</text>';
            sparklineStatEl.textContent = 'Waiting for data…';
            return;
        }

        var width = 360;
        var height = 120;
        var padding = 10;
        var chartHeight = 84;
        var maxValue = Math.max.apply(null, opsHistory.concat([1]));
        var divisor = Math.max(opsHistory.length - 1, 1);
        var points = opsHistory.map(function (value, index) {
            var x = padding + (index * (width - (padding * 2)) / divisor);
            var y = padding + chartHeight - ((value / maxValue) * chartHeight);
            return x.toFixed(2) + ',' + y.toFixed(2);
        });

        if (opsHistory.length === 1) {
            points = [padding + ',' + (padding + chartHeight / 2), (width - padding) + ',' + (padding + chartHeight / 2)];
        }

        sparklineEl.innerHTML = [
            '<line x1="10" y1="94" x2="350" y2="94" class="monitor-sparkline-axis"></line>',
            '<line x1="10" y1="52" x2="350" y2="52" class="monitor-sparkline-grid"></line>',
            '<line x1="10" y1="10" x2="350" y2="10" class="monitor-sparkline-grid"></line>',
            '<polyline class="monitor-sparkline-line" points="' + points.join(' ') + '"></polyline>',
            '<text x="10" y="112" class="monitor-sparkline-label">60s ago</text>',
            '<text x="350" y="112" text-anchor="end" class="monitor-sparkline-label">now</text>'
        ].join('');

        sparklineStatEl.textContent = formatNumber(opsHistory[opsHistory.length - 1]) + ' ops/sec · ' + opsHistory.length + '/30 samples';
    }

    function fetchMetrics() {
        fetch('/api/monitor')
            .then(function (response) {
                if (!response.ok) {
                    throw new Error('Monitor API unavailable');
                }
                return response.json();
            })
            .then(function (data) {
                updateMetrics(data);
                updateStatus('is-up', 'Redis responding');
            })
            .catch(function () {
                updateStatus('is-down', 'Redis unavailable');
                if (!opsHistory.length) {
                    lastUpdatedEl.textContent = 'Unavailable';
                }
            });
    }

    function startPolling() {
        if (pollHandle) return;
        fetchMetrics();
        pollHandle = window.setInterval(fetchMetrics, 2000);
    }

    window.addEventListener('beforeunload', function () {
        if (pollHandle) {
            window.clearInterval(pollHandle);
        }
    });

    startPolling();
})();