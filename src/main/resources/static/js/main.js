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

        // Highlight active nav link
        const path = window.location.pathname;
        document.querySelectorAll('.nav-link').forEach(function (link) {
            if (link.getAttribute('href') === path) {
                link.classList.add('active');
            }
        });
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
})();
