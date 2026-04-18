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

        initPresentationMode();
    });

    // --- Presentation Mode ---
    function initPresentationMode() {
        // Only on use case pages (two-panel layout)
        var panels = document.querySelector('.usecase-panels');
        if (!panels) return;

        var PM_KEY = 'presentationMode';
        var toggle = document.createElement('button');
        toggle.className = 'presentation-toggle';
        toggle.type = 'button';
        toggle.setAttribute('aria-label', 'Toggle presentation mode');

        function updateMode(enabled) {
            document.body.classList.toggle('presentation-mode', enabled);
            toggle.textContent = enabled ? 'Exit Presentation' : 'Presentation Mode';
            toggle.setAttribute('aria-pressed', String(enabled));
            localStorage.setItem(PM_KEY, String(enabled));
        }

        toggle.addEventListener('click', function () {
            updateMode(!document.body.classList.contains('presentation-mode'));
        });

        document.body.appendChild(toggle);
        updateMode(localStorage.getItem(PM_KEY) === 'true');
    }

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

    // --- Redis Commands Panel ---
    // Each UC page sets window.WORKSHOP_UC = 'UCn'. We poll /api/redis/commands
    // for that UC, newest-first, and render entries in #commands-output inside
    // the #redis-commands-card block.
    var _lastCmdTsMicros = null;

    function pollRedisCommands() {
        var uc = window.WORKSHOP_UC;
        if (!uc) return;

        var card = document.getElementById('redis-commands-card');
        var output = document.getElementById('commands-output');
        if (!card || !output) return;

        var url = '/api/redis/commands?uc=' + encodeURIComponent(uc) + '&limit=15';
        if (_lastCmdTsMicros !== null) {
            url += '&since=' + encodeURIComponent(_lastCmdTsMicros);
        }

        fetch(url, { headers: { 'Accept': 'application/json' } })
            .then(function (res) { return res.ok ? res.json() : null; })
            .then(function (data) {
                if (!data) return;
                var commands = Array.isArray(data) ? data : (data.commands || []);
                if (commands.length === 0 && _lastCmdTsMicros === null) {
                    card.style.display = 'none';
                    return;
                }
                if (commands.length === 0) return;

                card.style.display = '';
                var newest = commands[0];
                if (newest && typeof newest.tsMicros === 'number') {
                    _lastCmdTsMicros = newest.tsMicros;
                }

                var placeholder = output.querySelector('.command-log-empty, .commands-empty');
                if (placeholder) placeholder.remove();

                // commands are newest-first; insert in reverse so newest ends on top
                for (var i = commands.length - 1; i >= 0; i--) {
                    var el = createCommandEntry(commands[i]);
                    output.insertBefore(el, output.firstChild);
                }

                while (output.children.length > 20) {
                    output.removeChild(output.lastChild);
                }
            })
            .catch(function () { /* silent */ });
    }

    function createCommandEntry(cmd) {
        var fullCmd = cmd.fullCommand || ((cmd.command || '') + (cmd.key ? ' ' + cmd.key : ''));
        var commandName = cmd.command || '';
        var keyText = cmd.key || '';

        var details = document.createElement('details');
        details.className = 'redis-cmd-entry command-entry';

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

    function copyRedisCommand(btn, command) {
        function flash() {
            btn.classList.add('cmd-copied');
            btn.title = 'Copied!';
            setTimeout(function () {
                btn.classList.remove('cmd-copied');
                btn.title = 'Copy command to clipboard';
            }, 1500);
        }
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(command).then(flash).catch(function () {});
            return;
        }
        // Fallback for older/non-secure contexts
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
            flash();
        } catch (e) { /* non-fatal */ }
    }

    document.addEventListener('DOMContentLoaded', function () {
        if (!window.WORKSHOP_UC) return;
        setTimeout(pollRedisCommands, 500);
        setInterval(pollRedisCommands, 2000);
    });
})();
