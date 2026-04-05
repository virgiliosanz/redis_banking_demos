/**
 * admin-utils.js — Shared utility helpers for the admin panel.
 *
 * Provides:
 *   AdminUtils.formatBytes(bytes)     → "1.5 KB"
 *   AdminUtils.isDarkMode()           → true/false
 *   AdminUtils.getThemeColors()       → { gridColor, tickColor, titleColor }
 *   AdminUtils.formatMinutes(min)     → "5 min" / "2 h" / "3 d"
 */

'use strict';

var AdminUtils = (function() {

    /**
     * Format a byte count into a human-readable string.
     */
    function formatBytes(b) {
        if (b < 1024) return b + ' B';
        if (b < 1048576) return (b / 1024).toFixed(1) + ' KB';
        return (b / 1048576).toFixed(1) + ' MB';
    }

    /**
     * Check if the admin panel is currently in dark mode.
     */
    function isDarkMode() {
        return document.documentElement.getAttribute('data-theme') === 'dark';
    }

    /**
     * Return theme-aware colors for Chart.js axes and grids.
     */
    function getThemeColors() {
        if (isDarkMode()) {
            return {
                gridColor: 'rgba(255,255,255,0.15)',
                tickColor: '#ccc',
                titleColor: '#fff'
            };
        }
        return {
            gridColor: 'rgba(0,0,0,0.1)',
            tickColor: '#666',
            titleColor: '#333'
        };
    }

    /**
     * Format a duration in minutes to a short human-readable string.
     * Examples: "5 min", "2 h", "3 d"
     */
    function formatMinutes(minutes) {
        if (minutes == null) return '\u2014';
        if (minutes < 60) return Math.round(minutes) + ' min';
        if (minutes < 1440) return Math.round(minutes / 60) + ' h';
        return Math.round(minutes / 1440) + ' d';
    }

    return {
        formatBytes: formatBytes,
        isDarkMode: isDarkMode,
        getThemeColors: getThemeColors,
        formatMinutes: formatMinutes
    };

})();
