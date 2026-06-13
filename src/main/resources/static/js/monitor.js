/** Redis Monitor Dashboard */
(function () {
    'use strict';

    var statusEl = document.getElementById('monitorStatus');
    var statusTextEl = document.getElementById('monitorStatusText');
    var lastUpdatedEl = document.getElementById('monitorLastUpdated');
    var sparklineEl = document.getElementById('monitorOpsSparkline');
    var sparklineStatEl = document.getElementById('monitorSparklineStat');
    var commandsStatusEl = document.getElementById('monitorCommandsStatus');
    var commandsStatusTextEl = document.getElementById('monitorCommandsStatusText');
    var commandsOutputEl = document.getElementById('monitorCommandsOutput');
    var commandsCounterEl = document.getElementById('monitorCommandsCounter');
    var copyAllBtn = document.getElementById('monitorCommandsCopyAll');
    var clearBtn = document.getElementById('monitorCommandsClear');
    var autoScrollBtn = document.getElementById('monitorCommandsAutoScroll');

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
    var commandStream = null;
    var reconnectHandle = null;
    var autoScrollEnabled = true;
    var disposed = false;
    var MAX_COMMANDS = 200;

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

    function updateCommandsStatus(state, text) {
        if (!commandsStatusEl || !commandsStatusTextEl) return;
        commandsStatusEl.classList.remove('is-live', 'is-reconnecting', 'is-down');
        if (state) {
            commandsStatusEl.classList.add(state);
        }
        commandsStatusTextEl.textContent = text;
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

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function formatCommandTime(timestamp) {
        if (!timestamp) return '--:--:--';
        var parsed = new Date(timestamp);
        if (Number.isNaN(parsed.getTime())) return String(timestamp);
        return parsed.toLocaleTimeString();
    }

    function fallbackCopyText(command) {
        try {
            var ta = document.createElement('textarea');
            ta.value = command;
            ta.setAttribute('readonly', '');
            ta.style.position = 'absolute';
            ta.style.left = '-9999px';
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
            return true;
        } catch (e) {
            return false;
        }
    }

    function copyRedisCommand(btn, command, defaultTitle) {
        var baseTitle = defaultTitle || 'Copy command to clipboard';

        function flash() {
            btn.classList.add('cmd-copied');
            btn.title = 'Copied!';
            btn.setAttribute('aria-label', 'Copied!');
            window.setTimeout(function () {
                btn.classList.remove('cmd-copied');
                btn.title = baseTitle;
                btn.setAttribute('aria-label', baseTitle);
            }, 1500);
        }

        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(command).then(flash).catch(function () {
                if (fallbackCopyText(command)) flash();
            });
            return;
        }

        if (fallbackCopyText(command)) flash();
    }

    function getCommandEntries() {
        if (!commandsOutputEl) return [];
        return commandsOutputEl.querySelectorAll('.redis-cmd-entry.command-entry');
    }

    function removeCommandsPlaceholder() {
        if (!commandsOutputEl) return;
        var placeholder = commandsOutputEl.querySelector('.commands-empty');
        if (placeholder) {
            placeholder.remove();
        }
    }

    function ensureCommandsPlaceholder() {
        if (!commandsOutputEl) return;
        if (getCommandEntries().length > 0 || commandsOutputEl.querySelector('.commands-empty')) return;

        var placeholder = document.createElement('div');
        placeholder.className = 'commands-empty';
        placeholder.textContent = 'Waiting for Redis MONITOR stream…';
        commandsOutputEl.appendChild(placeholder);
    }

    function getVisibleCommands() {
        var entries = getCommandEntries();
        var commands = [];
        for (var i = 0; i < entries.length; i++) {
            var fullCommand = entries[i].getAttribute('data-full-command');
            if (fullCommand) {
                commands.push(fullCommand);
            }
        }
        return commands;
    }

    function clearCommandEntries() {
        var entries = getCommandEntries();
        for (var i = 0; i < entries.length; i++) {
            entries[i].remove();
        }
    }

    function updateCommandsToolbar() {
        var count = getCommandEntries().length;
        if (commandsCounterEl) {
            commandsCounterEl.textContent = count + ' command' + (count === 1 ? '' : 's');
        }
        if (copyAllBtn) copyAllBtn.disabled = count === 0;
        if (clearBtn) clearBtn.disabled = count === 0;
    }

    function trimCommandEntries() {
        var entries = getCommandEntries();
        while (entries.length > MAX_COMMANDS) {
            entries[0].remove();
            entries = getCommandEntries();
        }
    }

    function scrollCommandsToLatest() {
        if (!commandsOutputEl || !autoScrollEnabled) return;
        commandsOutputEl.scrollTop = commandsOutputEl.scrollHeight;
    }

    function updateAutoScrollButton() {
        if (!autoScrollBtn) return;
        autoScrollBtn.setAttribute('aria-pressed', autoScrollEnabled ? 'true' : 'false');
        autoScrollBtn.textContent = autoScrollEnabled ? 'Auto-scroll on' : 'Auto-scroll paused';
        autoScrollBtn.title = autoScrollEnabled ? 'Pause auto-scroll' : 'Resume auto-scroll';
    }

    function createCommandEntry(cmd) {
        var fullCmd = cmd.fullCommand || ((cmd.command || '') + (cmd.key ? ' ' + cmd.key : ''));
        var commandName = cmd.command || 'UNKNOWN';
        var keyText = cmd.key || '';

        var details = document.createElement('details');
        details.className = 'redis-cmd-entry command-entry monitor-command-entry';
        details.setAttribute('data-full-command', fullCmd);

        var summary = document.createElement('summary');

        var timeEl = document.createElement('span');
        timeEl.className = 'cmd-time';
        timeEl.textContent = formatCommandTime(cmd.timestamp);
        summary.appendChild(timeEl);

        if (cmd.useCase) {
            var badgeEl = document.createElement('span');
            badgeEl.className = 'cmd-uc';
            badgeEl.textContent = cmd.useCase;
            summary.appendChild(badgeEl);
        }

        var summaryCode = document.createElement('code');
        summaryCode.className = 'cmd-summary';
        summaryCode.innerHTML = escapeHtml(commandName)
            + (keyText ? ' <span class="cmd-key-text">' + escapeHtml(keyText) + '</span>' : '');
        summary.appendChild(summaryCode);

        var copyBtn = document.createElement('button');
        copyBtn.type = 'button';
        copyBtn.className = 'cmd-copy-btn';
        copyBtn.title = 'Copy command to clipboard';
        copyBtn.setAttribute('aria-label', 'Copy command to clipboard');
        copyBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none"'
            + ' stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
            + '<rect x="9" y="9" width="13" height="13" rx="2"/>'
            + '<path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>'
            + '</svg>';
        copyBtn.addEventListener('click', function (event) {
            event.preventDefault();
            event.stopPropagation();
            copyRedisCommand(copyBtn, fullCmd);
        });
        summary.appendChild(copyBtn);

        var expanded = document.createElement('div');
        expanded.className = 'cmd-expanded';
        expanded.innerHTML = '<div class="cmd-full"><span class="cmd-label">Full command</span>'
            + '<code>' + escapeHtml(fullCmd) + '</code></div>';

        details.appendChild(summary);
        details.appendChild(expanded);
        return details;
    }

    function appendCommand(cmd) {
        if (!commandsOutputEl) return;
        removeCommandsPlaceholder();
        commandsOutputEl.appendChild(createCommandEntry(cmd));
        trimCommandEntries();
        updateCommandsToolbar();
        scrollCommandsToLatest();
    }

    function loadRecentCommands() {
        if (!commandsOutputEl) return;

        fetch('/api/redis/commands?limit=20', { headers: { 'Accept': 'application/json' } })
            .then(function (response) {
                return response.ok ? response.json() : null;
            })
            .then(function (data) {
                if (!data) return;

                var commands = Array.isArray(data) ? data : (data.commands || []);
                if (!commands.length) {
                    ensureCommandsPlaceholder();
                    updateCommandsToolbar();
                    return;
                }

                for (var i = commands.length - 1; i >= 0; i--) {
                    appendCommand(commands[i]);
                }
            })
            .catch(function () {
                ensureCommandsPlaceholder();
                updateCommandsToolbar();
            });
    }

    function scheduleReconnect() {
        if (disposed || reconnectHandle) return;
        reconnectHandle = window.setTimeout(function () {
            reconnectHandle = null;
            connectCommandStream();
        }, 2000);
    }

    function connectCommandStream() {
        if (!commandsOutputEl || disposed) return;

        if (commandStream) {
            commandStream.close();
            commandStream = null;
        }

        updateCommandsStatus('', 'Connecting…');
        commandStream = new EventSource('/api/redis/commands/stream');

        commandStream.onopen = function () {
            updateCommandsStatus('is-live', 'Live');
        };

        commandStream.addEventListener('command', function (event) {
            var cmd;
            try {
                cmd = JSON.parse(event.data);
            } catch (e) {
                return;
            }
            appendCommand(cmd);
        });

        commandStream.onerror = function () {
            if (disposed) return;
            updateCommandsStatus('is-reconnecting', 'Reconnecting…');
            if (commandStream) {
                commandStream.close();
                commandStream = null;
            }
            scheduleReconnect();
        };
    }

    function initCommandsPanel() {
        if (!commandsOutputEl) return;

        ensureCommandsPlaceholder();
        updateAutoScrollButton();
        updateCommandsToolbar();

        if (copyAllBtn) {
            copyAllBtn.addEventListener('click', function () {
                var commands = getVisibleCommands();
                if (!commands.length) return;
                copyRedisCommand(copyAllBtn, commands.join('\n'), 'Copy all commands');
            });
        }

        if (clearBtn) {
            clearBtn.addEventListener('click', function () {
                clearCommandEntries();
                ensureCommandsPlaceholder();
                updateCommandsToolbar();
            });
        }

        if (autoScrollBtn) {
            autoScrollBtn.addEventListener('click', function () {
                autoScrollEnabled = !autoScrollEnabled;
                updateAutoScrollButton();
                scrollCommandsToLatest();
            });
        }

        loadRecentCommands();
        connectCommandStream();
    }

    function startPolling() {
        if (pollHandle) return;
        fetchMetrics();
        pollHandle = window.setInterval(fetchMetrics, 2000);
    }

    window.addEventListener('beforeunload', function () {
        disposed = true;
        if (pollHandle) {
            window.clearInterval(pollHandle);
        }
        if (reconnectHandle) {
            window.clearTimeout(reconnectHandle);
        }
        if (commandStream) {
            commandStream.close();
            commandStream = null;
        }
    });

    startPolling();
    initCommandsPanel();
})();