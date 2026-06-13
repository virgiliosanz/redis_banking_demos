/**
 * Redis Banking Workshop — Shared JS
 * Dark mode toggle + utility functions
 */
(function () {
    'use strict';

    // --- Dark Mode Toggle ---
    const THEME_KEY = 'redis-workshop-theme';

    function getPreferredTheme() {
        const stored = localStorage.getItem(THEME_KEY);
        if (stored) return stored;
        return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }

    function applyTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem(THEME_KEY, theme);
    }

    // Apply stored/preferred theme immediately
    applyTheme(getPreferredTheme());

    document.addEventListener('DOMContentLoaded', function () {
        const toggle = document.getElementById('themeToggle');
        if (toggle) {
            toggle.addEventListener('click', function () {
                const current = document.documentElement.getAttribute('data-theme');
                applyTheme(current === 'dark' ? 'light' : 'dark');
            });
        }

        // Set active option in nav select based on current path
        var navSelect = document.getElementById('useCaseSelect');
        if (navSelect) {
            var path = window.location.pathname;
            var matched = false;
            for (var i = 0; i < navSelect.options.length; i++) {
                if (navSelect.options[i].value === path) {
                    navSelect.selectedIndex = i;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                navSelect.selectedIndex = 0;
            }
        }

        initResetAll();
    });

    // --- Reset All (navbar) ---
    function initResetAll() {
        var btn = document.getElementById('resetAllBtn');
        if (!btn) return;
        var label = btn.querySelector('.reset-label');
        var originalLabel = label ? label.textContent : 'Reset All';

        btn.addEventListener('click', function () {
            if (btn.disabled) return;
            if (!window.confirm('This will reset all demo data. Continue?')) return;

            btn.disabled = true;
            btn.classList.add('is-loading');
            if (label) label.textContent = 'Resetting…';

            fetch('/api/reset-all', {
                method: 'POST',
                headers: { 'Accept': 'application/json' }
            })
                .then(function (res) {
                    return res.json().then(function (body) { return { ok: res.ok, body: body }; });
                })
                .then(function (result) {
                    var totalMs = (result.body && result.body.totalMs) || 0;
                    if (result.ok) {
                        if (label) label.textContent = 'Reset ✓ ' + totalMs + 'ms';
                    } else {
                        if (label) label.textContent = 'Reset failed';
                        console.error('Reset-all partial/failed', result.body);
                    }
                })
                .catch(function (err) {
                    if (label) label.textContent = 'Reset failed';
                    console.error('Reset-all error', err);
                })
                .finally(function () {
                    btn.classList.remove('is-loading');
                    setTimeout(function () {
                        btn.disabled = false;
                        if (label) label.textContent = originalLabel;
                    }, 2500);
                });
        });
    }


    // --- Redis latency badge ---
    // Renders a pill in the demo panel header. Green <1ms, yellow 1-10ms, red >10ms.
    window.renderRedisLatency = function (latencyMs) {
        if (latencyMs === null || latencyMs === undefined) return;
        var n = Number(latencyMs);
        if (!isFinite(n) || n < 0) return;

        var panel = document.querySelector('.usecase-panels .demo-panel');
        if (!panel) return;

        var badge = document.getElementById('redis-latency-badge');
        if (!badge) {
            badge = document.createElement('span');
            badge.id = 'redis-latency-badge';
            badge.className = 'redis-latency-badge';
            badge.title = 'Server-side Redis operation latency';
            var h2 = panel.querySelector(':scope > h2');
            if (h2) h2.appendChild(badge);
            else panel.insertBefore(badge, panel.firstChild);
        }

        var state = n < 1 ? 'ok' : (n <= 10 ? 'warn' : 'slow');
        badge.className = 'redis-latency-badge ' + state;
        var display = n < 10 ? n.toFixed(2) : n.toFixed(1);
        badge.textContent = 'Redis: ' + display + ' ms';
    };

    // Auto-tap every /api/** response: the X-Redis-Latency-Ms header is set by
    // RedisLatencyAdvice on the server, so we can render the pill for any fetch
    // (raw fetch, workshopFetch, workshopGet) without per-UC changes.
    var _origFetch = window.fetch.bind(window);
    window.fetch = function (input, init) {
        return _origFetch(input, init).then(function (res) {
            try {
                var url = typeof input === 'string' ? input : (input && input.url) || '';
                if (url.indexOf('/api/') !== -1) {
                    var header = res.headers.get('X-Redis-Latency-Ms');
                    if (header != null) window.renderRedisLatency(parseFloat(header));
                }
            } catch (e) { /* non-fatal */ }
            return res;
        });
    };

    // --- Utility: POST JSON ---
    window.workshopFetch = function (url, data) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: data ? JSON.stringify(data) : undefined
        }).then(function (res) { return res.json(); });
    };

    // --- Utility: GET JSON ---
    window.workshopGet = function (url) {
        return fetch(url).then(function (res) { return res.json(); });
    };

    // --- Utility: Format JSON for display ---
    window.formatJson = function (obj) {
        return JSON.stringify(obj, null, 2);
    };

    // --- Code Tabs: shared initializer ---
    window.initCodeTabs = function () {
        document.querySelectorAll('.code-tab').forEach(function (tab) {
            tab.addEventListener('click', function () {
                document.querySelectorAll('.code-tab').forEach(function (t) { t.classList.remove('active'); });
                document.querySelectorAll('.code-block, .code-tab-content').forEach(function (c) { c.classList.remove('active'); });
                tab.classList.add('active');
                var target = document.getElementById('tab-' + tab.getAttribute('data-tab'));
                if (target) target.classList.add('active');
                if (window.Prism) Prism.highlightAll();
            });
        });
    };

    // --- Shared: HTML escape ---
    function escapeHtml(s) {
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // --- Redis Commands Panel (SSE) ---
    // Each UC page sets window.WORKSHOP_UC = 'UCn'. We open an SSE stream
    // filtered by UC and push entries into #commands-output inside the
    // #redis-commands-card block. A one-shot REST call backfills any
    // commands that happened before the page loaded.
    var MAX_COMMANDS = 25;
    var _eventSource = null;

    function initRedisCommands() {
        var uc = window.WORKSHOP_UC;
        if (!uc) return;

        var card = document.getElementById('redis-commands-card');
        var output = document.getElementById('commands-output');
        var counter = document.getElementById('commands-counter');
        var copyAllBtn = document.getElementById('copy-all-commands-btn');
        var clearBtn = document.getElementById('clear-commands-btn');
        if (!card || !output) return;

        ensureCommandsPlaceholder(output);
        updateCommandsToolbar(output, counter, copyAllBtn, clearBtn);

        if (copyAllBtn) {
            copyAllBtn.addEventListener('click', function () {
                var commands = getVisibleCommands(output);
                if (!commands.length) return;
                copyRedisCommand(copyAllBtn, commands.join('\n'), 'Copy all commands');
            });
        }

        if (clearBtn) {
            clearBtn.addEventListener('click', function () {
                clearCommandEntries(output);
                ensureCommandsPlaceholder(output);
                updateCommandsToolbar(output, counter, copyAllBtn, clearBtn);
                card.style.display = '';
            });
        }

        // Backfill recent commands captured before page load
        fetch('/api/redis/commands?uc=' + encodeURIComponent(uc) + '&limit=10',
                { headers: { 'Accept': 'application/json' } })
            .then(function (res) { return res.ok ? res.json() : null; })
            .then(function (data) {
                if (!data) return;
                var commands = Array.isArray(data) ? data : (data.commands || []);
                if (commands.length === 0) return;

                card.style.display = '';
                removeCommandsPlaceholder(output);

                // Backend returns newest-first; insert in reverse so newest ends on top
                for (var i = commands.length - 1; i >= 0; i--) {
                    var el = createCommandEntry(commands[i]);
                    output.insertBefore(el, output.firstChild);
                }
                trimCommandEntries(output);
                updateCommandsToolbar(output, counter, copyAllBtn, clearBtn);
            })
            .catch(function () { /* silent */ });

        // Open SSE stream for real-time push
        var streamUrl = '/api/redis/commands/stream?uc=' + encodeURIComponent(uc);
        _eventSource = new EventSource(streamUrl);

        _eventSource.addEventListener('command', function (event) {
            var cmd;
            try { cmd = JSON.parse(event.data); } catch (e) { return; }

            card.style.display = '';
            removeCommandsPlaceholder(output);

            var el = createCommandEntry(cmd);
            output.insertBefore(el, output.firstChild);

            trimCommandEntries(output);
            updateCommandsToolbar(output, counter, copyAllBtn, clearBtn);

            flashCodeShowcase(cmd.command);
        });

        _eventSource.onerror = function () {
            // EventSource auto-reconnects by default
        };
    }

    window.addEventListener('beforeunload', function () {
        if (_eventSource) {
            _eventSource.close();
            _eventSource = null;
        }
    });

    function createCommandEntry(cmd) {
        var fullCmd = cmd.fullCommand || ((cmd.command || '') + (cmd.key ? ' ' + cmd.key : ''));
        var commandName = cmd.command || '';
        var keyText = cmd.key || '';

        var details = document.createElement('details');
        details.className = 'redis-cmd-entry command-entry';
        details.setAttribute('data-full-command', fullCmd);

        var summary = document.createElement('summary');
        var summaryCode = document.createElement('code');
        summaryCode.className = 'cmd-summary';
        summaryCode.innerHTML = escapeHtml(commandName)
            + (keyText ? ' <span class="cmd-key-text">' + escapeHtml(keyText) + '</span>' : '');

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
        copyBtn.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            copyRedisCommand(copyBtn, fullCmd);
        });

        summary.appendChild(summaryCode);
        summary.appendChild(copyBtn);

        var expanded = document.createElement('div');
        expanded.className = 'cmd-expanded';
        expanded.innerHTML = '<div class="cmd-full"><span class="cmd-label">Full command</span>'
            + '<code>' + escapeHtml(fullCmd) + '</code></div>';

        details.appendChild(summary);
        details.appendChild(expanded);
        return details;
    }

    function getCommandEntries(output) {
        return output.querySelectorAll('.redis-cmd-entry.command-entry, .command-entry-simple');
    }

    function getVisibleCommands(output) {
        var entries = getCommandEntries(output);
        var commands = [];
        for (var i = 0; i < entries.length; i++) {
            var fullCommand = entries[i].getAttribute('data-full-command');
            if (fullCommand) commands.push(fullCommand);
        }
        return commands;
    }

    function removeCommandsPlaceholder(output) {
        var placeholder = output.querySelector('.command-log-empty, .commands-empty');
        if (placeholder) placeholder.remove();
    }

    function ensureCommandsPlaceholder(output) {
        if (getCommandEntries(output).length > 0) {
            removeCommandsPlaceholder(output);
            return;
        }

        if (output.querySelector('.command-log-empty, .commands-empty')) return;

        var placeholder = document.createElement('div');
        placeholder.className = 'commands-empty';
        placeholder.textContent = 'No Redis commands captured yet.';
        output.appendChild(placeholder);
    }

    function clearCommandEntries(output) {
        var entries = getCommandEntries(output);
        for (var i = 0; i < entries.length; i++) {
            entries[i].remove();
        }
    }

    function trimCommandEntries(output) {
        var entries = getCommandEntries(output);
        while (entries.length > MAX_COMMANDS) {
            entries[entries.length - 1].remove();
            entries = getCommandEntries(output);
        }
    }

    function updateCommandsToolbar(output, counter, copyAllBtn, clearBtn) {
        var count = getCommandEntries(output).length;
        if (counter) {
            counter.textContent = count + ' command' + (count === 1 ? '' : 's');
        }
        if (copyAllBtn) copyAllBtn.disabled = count === 0;
        if (clearBtn) clearBtn.disabled = count === 0;
    }

    // Briefly flash the code-showcase snippet that contains the executed command.
    // Matches the command token within the currently visible (active) code block,
    // ignoring substrings of longer commands (e.g. HGET inside HGETALL).
    function flashCodeShowcase(commandName) {
        if (!commandName) return;
        var panel = document.querySelector('.code-panel');
        if (!panel) return;

        var blocks = panel.querySelectorAll('.code-block.active, .code-tab-content.active');
        if (!blocks.length) blocks = panel.querySelectorAll('.code-block, .code-tab-content');

        var escaped = commandName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        var re = new RegExp('(^|[^A-Z0-9.])' + escaped + '(?![A-Z0-9.])');

        for (var i = 0; i < blocks.length; i++) {
            var pres = blocks[i].querySelectorAll('pre');
            for (var j = 0; j < pres.length; j++) {
                var code = pres[j].querySelector('code');
                var text = code ? code.textContent : pres[j].textContent;
                if (re.test(text)) {
                    var target = pres[j];
                    target.classList.remove('code-highlight-flash');
                    // Force reflow so animation restarts on repeated matches
                    void target.offsetWidth;
                    target.classList.add('code-highlight-flash');
                    setTimeout(function (el) {
                        return function () { el.classList.remove('code-highlight-flash'); };
                    }(target), 1700);
                    return;
                }
            }
        }
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
            setTimeout(function () {
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

    document.addEventListener('DOMContentLoaded', function () {
        if (!window.WORKSHOP_UC) return;
        initRedisCommands();
        initCodeTabs();
    });
})();
