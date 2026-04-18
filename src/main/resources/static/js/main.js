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

    // --- Utility: auto-render redisCommands when present on a response ---
    // Looks up the standard container (#redis-commands-list). Returns the
    // same data unchanged so it can be chained in .then(...) handlers.
    window.maybeRenderRedisCommands = function (data) {
        try {
            if (data && data.redisCommands && window.renderRedisCommands) {
                var container = document.getElementById('redis-commands-list');
                if (container) {
                    window.renderRedisCommands(container, data.redisCommands);
                }
            }
        } catch (e) { /* non-fatal */ }
        return data;
    };

    // --- Utility: POST JSON ---
    window.workshopFetch = function (url, data) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: data ? JSON.stringify(data) : undefined
        }).then(function (res) { return res.json(); })
          .then(window.maybeRenderRedisCommands);
    };

    // --- Utility: GET JSON ---
    window.workshopGet = function (url) {
        return fetch(url).then(function (res) { return res.json(); })
          .then(window.maybeRenderRedisCommands);
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

    // --- Shared: Redis Commands panel renderer ---
    // Renders an array of commands into a container. Each entry may be either:
    //   "COMMAND args → result summary"  (collapsible: summary = command, expanded = result)
    //   "COMMAND args"                   (non-collapsible single line)
    // Shows the parent .redis-commands-card; keeps it visible if already shown.
    function escapeHtml(s) {
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    window.renderRedisCommands = function (container, commands) {
        if (!container) return;
        var card = container.closest('.redis-commands-card') || container.parentElement;
        if (!commands || !commands.length) {
            container.innerHTML = '<div class="commands-empty">No Redis commands recorded for this request.</div>';
            if (card) card.style.display = '';
            return;
        }
        var html = '';
        commands.forEach(function (entry) {
            if (!entry) return;
            var str = String(entry);
            var idx = str.indexOf(' → ');
            if (idx > -1) {
                var cmdPart = str.substring(0, idx);
                var resultPart = str.substring(idx + 3);
                html += '<details class="command-entry">' +
                          '<summary><code>' + escapeHtml(cmdPart) + '</code></summary>' +
                          '<div class="cmd-expanded">' +
                            '<div class="cmd-result">' +
                              '<span class="cmd-label">Result</span>' +
                              '<code>' + escapeHtml(resultPart) + '</code>' +
                            '</div>' +
                          '</div>' +
                        '</details>';
            } else {
                html += '<div class="command-entry command-entry-simple">' +
                          '<code>' + escapeHtml(str) + '</code>' +
                        '</div>';
            }
        });
        container.innerHTML = html;
        if (card) card.style.display = '';
    };
})();
