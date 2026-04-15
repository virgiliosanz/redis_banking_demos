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
    });

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
})();
