/**
 * admin.js — shared helpers for the admin panel.
 *
 * Provides automatic report-file detection and display after CLI commands.
 */

'use strict';

var AdminReports = (function() {

    var REPORT_RE = /written to ([^\s]+\.(?:md|json))/;

    /**
     * Scan stdout for a report path.  Returns the path string or null.
     */
    function detectReportPath(stdout) {
        if (!stdout) return null;
        var m = stdout.match(REPORT_RE);
        return m ? m[1] : null;
    }

    /**
     * Fetch report content from the backend and display it below the output.
     *
     * @param {string} reportPath  — absolute path found in command output
     * @param {HTMLElement} anchor — element after which the report box is inserted
     */
    function fetchAndDisplay(reportPath, anchor) {
        var existing = document.getElementById('reportContainer');
        if (existing) existing.remove();

        var container = document.createElement('div');
        container.id = 'reportContainer';
        container.className = 'box mt-4';

        var header = document.createElement('h3');
        header.className = 'title is-5';
        header.innerHTML =
            '<span class="icon-text">' +
            '<span class="icon"><i class="fas fa-file-alt"></i></span>' +
            '<span>Contenido del reporte:</span>' +
            '</span>';
        container.appendChild(header);

        var loading = document.createElement('p');
        loading.textContent = 'Cargando reporte...';
        container.appendChild(loading);

        anchor.parentNode.insertBefore(container, anchor.nextSibling);

        fetch('/api/read-file?path=' + encodeURIComponent(reportPath))
            .then(function(resp) { return resp.json(); })
            .then(function(data) {
                loading.remove();
                if (!data.success) {
                    var err = document.createElement('p');
                    err.className = 'has-text-danger';
                    err.textContent = 'No se pudo leer el reporte: ' + (data.error || 'error desconocido');
                    container.appendChild(err);
                    return;
                }

                var pre = document.createElement('pre');
                pre.className = 'output-box';
                if (reportPath.endsWith('.json')) {
                    try {
                        pre.textContent = JSON.stringify(JSON.parse(data.content), null, 2);
                    } catch (_e) {
                        pre.textContent = data.content;
                    }
                } else {
                    pre.textContent = data.content;
                }
                pre.style.background = '#1a2e1a';
                container.appendChild(pre);

                var closeBtn = document.createElement('button');
                closeBtn.className = 'button is-small is-light mt-3';
                closeBtn.innerHTML =
                    '<span class="icon"><i class="fas fa-times"></i></span>' +
                    '<span>Cerrar reporte</span>';
                closeBtn.addEventListener('click', function() {
                    container.remove();
                });
                container.appendChild(closeBtn);
            })
            .catch(function(err) {
                loading.textContent = 'Error de red al cargar reporte: ' + err.message;
                loading.className = 'has-text-danger';
            });
    }

    /**
     * After a successful command, check stdout for a report path and auto-display it.
     *
     * @param {object} data        — response from /api/run ({stdout, success, ...})
     * @param {HTMLElement} anchor  — element after which to insert the report box
     */
    function handleCommandResult(data, anchor) {
        if (!data.success) return;
        var path = detectReportPath(data.stdout);
        if (path) {
            fetchAndDisplay(path, anchor);
        }
    }

    return {
        detectReportPath: detectReportPath,
        fetchAndDisplay: fetchAndDisplay,
        handleCommandResult: handleCommandResult
    };

})();

/**
 * AdminTheme — dark/light mode toggle with localStorage persistence.
 */
var AdminTheme = (function() {

    function getCurrentTheme() {
        return document.documentElement.getAttribute('data-theme') || 'light';
    }

    function setTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem('admin-theme', theme);
        updateIcon(theme);
        // Dispatch event so charts and other components can react
        document.dispatchEvent(new CustomEvent('themechange', { detail: { theme: theme } }));
    }

    function updateIcon(theme) {
        var icon = document.getElementById('themeIcon');
        if (!icon) return;
        if (theme === 'dark') {
            icon.classList.remove('fa-moon');
            icon.classList.add('fa-sun');
        } else {
            icon.classList.remove('fa-sun');
            icon.classList.add('fa-moon');
        }
    }

    function toggle() {
        var current = getCurrentTheme();
        setTheme(current === 'dark' ? 'light' : 'dark');
    }

    function init() {
        var theme = getCurrentTheme();
        updateIcon(theme);

        var btn = document.getElementById('themeToggle');
        if (btn) {
            btn.addEventListener('click', toggle);
        }
    }

    // Initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    return {
        getCurrentTheme: getCurrentTheme,
        setTheme: setTheme,
        toggle: toggle
    };

})();
