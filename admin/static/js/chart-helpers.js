/**
 * chart-helpers.js — Shared Chart.js helpers for the admin panel.
 *
 * Provides:
 *   ChartHelpers.THRESHOLD_DEFS        → threshold definitions by metric suffix
 *   ChartHelpers.getThreshold(name)    → { warning, critical } or null
 *   ChartHelpers.addThresholdAnnotations(metricName, yMax, showThresholds)
 *                                      → annotation object for Chart.js annotation plugin
 *   ChartHelpers.destroyAllCharts(chartsObj)  → destroys all Chart instances in an object
 *   ChartHelpers.createLineChart(canvasId, label, data, options)
 *                                      → creates a themed line chart, returns Chart instance
 */

'use strict';

var ChartHelpers = (function() {

    // Threshold definitions: { metricSuffix: { warning, critical } }
    // Uses suffix matching so container metrics (e.g. n9-lb-nginx.cpu_pct) match *.cpu_pct
    var THRESHOLD_DEFS = {
        'cpu_user_pct':       { warning: 70,  critical: 90  },
        'memory_used_pct':    { warning: 80,  critical: 95  },
        'disk_used_pct':      { warning: 80,  critical: 95  },
        'load_1':             { warning: 2.0, critical: 4.0 },
        'cpu_pct':            { warning: 80,  critical: 95  },
        'mem_pct':            { warning: 80,  critical: 95  },
        'heap_used_pct':      { warning: 75,  critical: 90  },
        'threads_connected':  { warning: 50,  critical: 100 }
    };

    /**
     * Look up threshold for a metric name by its suffix.
     */
    function getThreshold(metricName) {
        var parts = metricName.split('.');
        var suffix = parts[parts.length - 1];
        return THRESHOLD_DEFS[suffix] || null;
    }

    /**
     * Build warning/critical band annotations for Chart.js annotation plugin.
     * Returns an object with warningBand and criticalBand keys, or empty object.
     */
    function addThresholdAnnotations(metricName, yMax, showThresholds) {
        var th = getThreshold(metricName);
        if (!th || !showThresholds) return {};
        var upperBound = Math.max(yMax * 1.1, th.critical * 1.5);
        return {
            warningBand: {
                type: 'box',
                yMin: th.warning,
                yMax: th.critical,
                backgroundColor: 'rgba(255, 193, 7, 0.15)',
                borderColor: 'rgba(255, 193, 7, 0.4)',
                borderWidth: 1,
                label: {
                    display: true,
                    content: 'Warning',
                    position: 'start',
                    color: '#b58900',
                    font: { size: 10 }
                }
            },
            criticalBand: {
                type: 'box',
                yMin: th.critical,
                yMax: upperBound,
                backgroundColor: 'rgba(220, 53, 69, 0.13)',
                borderColor: 'rgba(220, 53, 69, 0.4)',
                borderWidth: 1,
                label: {
                    display: true,
                    content: 'Critical',
                    position: 'start',
                    color: '#c0392b',
                    font: { size: 10 }
                }
            }
        };
    }

    /**
     * Destroy all Chart.js instances stored in an object map and clear it.
     * @param {Object} chartsObj — map of name → Chart instance
     */
    function destroyAllCharts(chartsObj) {
        Object.keys(chartsObj).forEach(function(k) {
            if (chartsObj[k] && typeof chartsObj[k].destroy === 'function') {
                chartsObj[k].destroy();
            }
        });
        // Clear all keys
        Object.keys(chartsObj).forEach(function(k) { delete chartsObj[k]; });
    }

    /**
     * Create a standard themed line chart.
     * @param {string} canvasId — canvas element ID
     * @param {string} label — dataset label
     * @param {Array} data — array of data points
     * @param {Object} [options] — optional overrides (color, fill, tension, etc.)
     * @returns {Chart} the Chart.js instance
     */
    function createLineChart(canvasId, label, data, options) {
        var opts = options || {};
        var color = opts.color || '#3273dc';
        var themeColors = (typeof AdminUtils !== 'undefined') ? AdminUtils.getThemeColors() : {
            gridColor: 'rgba(0,0,0,0.1)', tickColor: '#666', titleColor: '#333'
        };
        var ctx = document.getElementById(canvasId);
        if (!ctx) return null;
        return new Chart(ctx, {
            type: 'line',
            data: {
                labels: opts.labels || data.map(function(_, i) { return i; }),
                datasets: [{
                    label: label,
                    data: data,
                    borderColor: color,
                    backgroundColor: color + '22',
                    fill: opts.fill !== undefined ? opts.fill : true,
                    tension: opts.tension !== undefined ? opts.tension : 0.3,
                    pointRadius: opts.pointRadius !== undefined ? opts.pointRadius : 2,
                    borderWidth: opts.borderWidth !== undefined ? opts.borderWidth : undefined
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: opts.maintainAspectRatio !== undefined ? opts.maintainAspectRatio : false,
                plugins: opts.plugins || { legend: { display: false }, tooltip: { enabled: true } },
                scales: opts.scales || {
                    x: {
                        display: opts.showAxes !== undefined ? opts.showAxes : true,
                        grid: { color: themeColors.gridColor },
                        ticks: { color: themeColors.tickColor }
                    },
                    y: {
                        beginAtZero: true,
                        display: opts.showAxes !== undefined ? opts.showAxes : true,
                        grid: { color: themeColors.gridColor },
                        ticks: { color: themeColors.tickColor }
                    }
                },
                animation: opts.animation !== undefined ? opts.animation : undefined
            }
        });
    }

    return {
        THRESHOLD_DEFS: THRESHOLD_DEFS,
        getThreshold: getThreshold,
        addThresholdAnnotations: addThresholdAnnotations,
        destroyAllCharts: destroyAllCharts,
        createLineChart: createLineChart
    };

})();
