/**
 * Redis Monitor — polls /api/monitor every 2 seconds and renders live metrics.
 */
(function () {
    'use strict';

    const POLL_INTERVAL_MS = 2000;

    const el = {
        status: document.getElementById('monitorStatus'),
        statusText: document.getElementById('monitorStatusText'),
        memory: document.getElementById('mMemory'),
        memoryPeak: document.getElementById('mMemoryPeak'),
        memoryBar: document.getElementById('mMemoryBar'),
        keys: document.getElementById('mKeys'),
        commands: document.getElementById('mCommands'),
        ops: document.getElementById('mOps'),
        hitRate: document.getElementById('mHitRate'),
        hits: document.getElementById('mHits'),
        misses: document.getElementById('mMisses'),
        clients: document.getElementById('mClients'),
        uptime: document.getElementById('mUptime'),
        version: document.getElementById('mVersion'),
        updated: document.getElementById('mUpdated')
    };

    function formatNumber(n) {
        if (n === null || n === undefined) return '—';
        return Number(n).toLocaleString();
    }

    function formatUptime(seconds) {
        if (!seconds || seconds < 0) return '0m';
        const days = Math.floor(seconds / 86400);
        const hours = Math.floor((seconds % 86400) / 3600);
        const minutes = Math.floor((seconds % 3600) / 60);
        const parts = [];
        if (days > 0) parts.push(days + 'd');
        if (hours > 0 || days > 0) parts.push(hours + 'h');
        parts.push(minutes + 'm');
        return parts.join(' ');
    }

    function parseHumanSizeToBytes(human) {
        if (!human || typeof human !== 'string') return 0;
        const m = human.match(/^([0-9.]+)\s*([KMGT]?)/i);
        if (!m) return 0;
        const value = parseFloat(m[1]);
        const unit = (m[2] || '').toUpperCase();
        const mult = { '': 1, K: 1024, M: 1024 * 1024, G: 1024 * 1024 * 1024, T: 1024 * 1024 * 1024 * 1024 };
        return value * (mult[unit] || 1);
    }

    function setStatus(state, text) {
        el.status.setAttribute('data-state', state);
        el.statusText.textContent = text;
    }

    function render(data) {
        el.memory.textContent = data.used_memory_human || '—';
        el.memoryPeak.textContent = data.used_memory_peak_human || '—';

        const used = parseHumanSizeToBytes(data.used_memory_human);
        const peak = parseHumanSizeToBytes(data.used_memory_peak_human);
        const pct = peak > 0 ? Math.min(100, Math.round((used / peak) * 100)) : 0;
        el.memoryBar.style.width = pct + '%';

        el.keys.textContent = formatNumber(data.db_size);
        el.commands.textContent = formatNumber(data.total_commands_processed);
        el.ops.textContent = formatNumber(data.instantaneous_ops_per_sec);

        const hits = Number(data.keyspace_hits || 0);
        const misses = Number(data.keyspace_misses || 0);
        const total = hits + misses;
        const hitRate = total > 0 ? (hits / total) * 100 : 0;
        el.hitRate.textContent = total > 0 ? hitRate.toFixed(1) + '%' : '—';
        el.hits.textContent = formatNumber(hits);
        el.misses.textContent = formatNumber(misses);

        el.clients.textContent = formatNumber(data.connected_clients);
        el.uptime.textContent = formatUptime(Number(data.uptime_seconds || 0));
        el.version.textContent = data.redis_version || '—';

        el.updated.textContent = new Date().toLocaleTimeString();
    }

    function poll() {
        fetch('/api/monitor', { headers: { 'Accept': 'application/json' } })
            .then(function (res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            })
            .then(function (data) {
                if (data && data.error) {
                    setStatus('error', 'error: ' + data.error);
                    return;
                }
                setStatus('ok', 'connected');
                render(data);
            })
            .catch(function (err) {
                setStatus('error', 'disconnected');
                console.error('[monitor] poll failed:', err);
            });
    }

    function escapeHtml(s) {
        if (s === null || s === undefined) return '';
        return String(s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function pollCommands() {
        fetch('/api/monitor/commands?limit=20', { headers: { 'Accept': 'application/json' } })
            .then(function (res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            })
            .then(function (commands) {
                var logEl = document.getElementById('commandLog');
                if (!logEl) return;
                if (!commands || commands.length === 0) {
                    logEl.innerHTML = '<p class="command-log-empty">No commands yet. Run a demo!</p>';
                    return;
                }
                logEl.innerHTML = commands.map(function (cmd) {
                    var time = cmd.timestamp ? new Date(cmd.timestamp).toLocaleTimeString() : '';
                    var detail = cmd.detail
                        ? '<span class="cmd-detail">' + escapeHtml(cmd.detail) + '</span>'
                        : '';
                    return '<div class="command-entry">'
                        + '<span class="cmd-time">' + escapeHtml(time) + '</span>'
                        + '<span class="cmd-uc">' + escapeHtml(cmd.useCase) + '</span>'
                        + '<span class="cmd-name">' + escapeHtml(cmd.command) + '</span>'
                        + '<span class="cmd-key">' + escapeHtml(cmd.key) + '</span>'
                        + detail
                        + '</div>';
                }).join('');
            })
            .catch(function (err) {
                console.error('[monitor] commands poll failed:', err);
            });
    }

    document.addEventListener('DOMContentLoaded', function () {
        poll();
        pollCommands();
        setInterval(poll, POLL_INTERVAL_MS);
        setInterval(pollCommands, POLL_INTERVAL_MS);
    });
})();
