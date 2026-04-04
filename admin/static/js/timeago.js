/**
 * timeago.js — Shared timestamp humanization helpers (Spanish).
 *
 * Provides:
 *   timeAgo(epoch)          → "hace 5 minutos"
 *   formatAbsolute(epoch)   → "2026-04-04 12:34:56"
 *   humanizeEpochs(root)    → converts all .epoch-ago[data-epoch] spans
 *   humanizeDuration(secs)  → "2m 30s"
 *
 * Auto-runs humanizeEpochs(document) on DOMContentLoaded.
 */

'use strict';

var TimeAgo = (function() {

    function timeAgo(epoch) {
        if (!epoch) return 'Nunca';
        var now = Math.floor(Date.now() / 1000);
        var diff = now - epoch;
        if (diff < 0) diff = 0;
        if (diff < 60) return 'hace ' + diff + ' segundos';
        var minutes = Math.floor(diff / 60);
        if (minutes < 60) return 'hace ' + minutes + (minutes === 1 ? ' minuto' : ' minutos');
        var hours = Math.floor(minutes / 60);
        if (hours < 24) return 'hace ' + hours + (hours === 1 ? ' hora' : ' horas');
        var days = Math.floor(hours / 24);
        return 'hace ' + days + (days === 1 ? ' dia' : ' dias');
    }

    function formatAbsolute(epoch) {
        var d = new Date(epoch * 1000);
        var pad = function(n) { return n < 10 ? '0' + n : '' + n; };
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
               ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
    }

    /**
     * Convert seconds to human-readable duration: "Xs" or "Xm Ys".
     */
    function humanizeDuration(seconds) {
        if (seconds == null || isNaN(seconds)) return '--';
        seconds = Math.round(seconds);
        if (seconds < 60) return seconds + 's';
        var m = Math.floor(seconds / 60);
        var s = seconds % 60;
        return s > 0 ? m + 'm ' + s + 's' : m + 'm';
    }

    /**
     * Parse an ISO-8601 string (e.g. "2026-04-04T12:34:56") to epoch seconds.
     * Returns NaN if parsing fails.
     */
    function isoToEpoch(isoStr) {
        if (!isoStr) return NaN;
        var ts = Date.parse(isoStr);
        return isNaN(ts) ? NaN : Math.floor(ts / 1000);
    }

    function humanizeEpochs(container) {
        var spans = (container || document).querySelectorAll('.epoch-ago[data-epoch]');
        for (var i = 0; i < spans.length; i++) {
            var raw = spans[i].getAttribute('data-epoch');
            var ep = Number(raw);
            // If not a number, try parsing as ISO string
            if (isNaN(ep)) {
                ep = isoToEpoch(raw);
            }
            if (!isNaN(ep) && ep > 0) {
                spans[i].textContent = timeAgo(ep);
                spans[i].setAttribute('title', formatAbsolute(ep));
                spans[i].style.cursor = 'help';
            }
        }
    }

    // Auto-run on DOMContentLoaded
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() { humanizeEpochs(document); });
    } else {
        humanizeEpochs(document);
    }

    return {
        timeAgo: timeAgo,
        formatAbsolute: formatAbsolute,
        humanizeDuration: humanizeDuration,
        humanizeEpochs: humanizeEpochs,
        isoToEpoch: isoToEpoch
    };

})();
