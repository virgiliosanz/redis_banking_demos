/** UC11: Real-time Transaction Monitoring — Redis Streams */
(function () {
    'use strict';

    // --- DOM refs ---
    var btnStart   = document.getElementById('btnStart');
    var btnStop    = document.getElementById('btnStop');
    var btnAnomaly = document.getElementById('btnAnomaly');
    var btnReset   = document.getElementById('btnReset');

    var metricTps    = document.getElementById('metricTps');
    var metricTotal  = document.getElementById('metricTotal');
    var metricAvgAmt = document.getElementById('metricAvgAmt');
    var metricMaxAmt = document.getElementById('metricMaxAmt');
    var metricRisk   = document.getElementById('metricRisk');

    var chartCount  = document.getElementById('chartCount');
    var chartAmount = document.getElementById('chartAmount');

    var pollInterval = null;
    var simulating = false;

    // --- Code tabs ---
    window.initCodeTabs();

    // --- Chart drawing ---
    function drawChart(canvas, data, label, color, unit) {
        var ctx = canvas.getContext('2d');
        var dpr = window.devicePixelRatio || 1;
        var rect = canvas.parentElement.getBoundingClientRect();
        var width = Math.floor(rect.width); // floor to prevent sub-pixel growth
        var height = 200;

        canvas.width = width * dpr;
        canvas.height = height * dpr;
        canvas.style.width = width + 'px';
        canvas.style.height = height + 'px';
        ctx.scale(dpr, dpr);

        var padding = { top: 25, right: 20, bottom: 30, left: 55 };
        var plotW = width - padding.left - padding.right;
        var plotH = height - padding.top - padding.bottom;

        ctx.clearRect(0, 0, width, height);

        // Background grid
        var gridColor = getComputedStyle(document.documentElement)
            .getPropertyValue('--border-color').trim() || '#E1E4E8';
        ctx.strokeStyle = gridColor;
        ctx.lineWidth = 0.5;
        for (var i = 0; i <= 4; i++) {
            var gy = padding.top + (plotH / 4) * i;
            ctx.beginPath();
            ctx.moveTo(padding.left, gy);
            ctx.lineTo(width - padding.right, gy);
            ctx.stroke();
        }

        // Axes
        ctx.strokeStyle = gridColor;
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(padding.left, padding.top);
        ctx.lineTo(padding.left, height - padding.bottom);
        ctx.lineTo(width - padding.right, height - padding.bottom);
        ctx.stroke();

        if (!data || data.length === 0) {
            ctx.fillStyle = '#6B7280';
            ctx.font = '13px "Space Grotesk", sans-serif';
            ctx.textAlign = 'center';
            ctx.fillText('No data yet — start the simulation', width / 2, height / 2);
            return;
        }

        var values = data.map(function (d) { return d.value; });
        var maxVal = Math.max.apply(null, values.concat([1]));
        maxVal = Math.ceil(maxVal * 1.1); // 10% headroom

        var xStep = plotW / (data.length - 1 || 1);
        var yScale = plotH / maxVal;

        // Area fill
        ctx.fillStyle = color + '18';
        ctx.beginPath();
        ctx.moveTo(padding.left, height - padding.bottom);
        data.forEach(function (point, idx) {
            var x = padding.left + idx * xStep;
            var y = height - padding.bottom - point.value * yScale;
            if (idx === 0) ctx.lineTo(x, y);
            else ctx.lineTo(x, y);
        });
        ctx.lineTo(padding.left + (data.length - 1) * xStep, height - padding.bottom);
        ctx.closePath();
        ctx.fill();

        // Data line
        ctx.strokeStyle = color;
        ctx.lineWidth = 2;
        ctx.beginPath();
        data.forEach(function (point, idx) {
            var x = padding.left + idx * xStep;
            var y = height - padding.bottom - point.value * yScale;
            if (idx === 0) ctx.moveTo(x, y);
            else ctx.lineTo(x, y);
        });
        ctx.stroke();

        // Y-axis labels
        var textColor = getComputedStyle(document.documentElement)
            .getPropertyValue('--text-secondary').trim() || '#6B7280';
        ctx.fillStyle = textColor;
        ctx.font = '11px "Space Grotesk", sans-serif';
        ctx.textAlign = 'right';
        for (var j = 0; j <= 4; j++) {
            var val = maxVal - (maxVal / 4) * j;
            var ly = padding.top + (plotH / 4) * j + 4;
            ctx.fillText((unit || '') + val.toFixed(0), padding.left - 5, ly);
        }

        // X-axis labels
        ctx.textAlign = 'center';
        ctx.fillText('60s ago', padding.left, height - 5);
        ctx.fillText('now', width - padding.right, height - 5);

        // Title label
        ctx.fillStyle = color;
        ctx.font = 'bold 12px "Space Grotesk", sans-serif';
        ctx.textAlign = 'left';
        ctx.fillText(label, padding.left, padding.top - 8);
    }

    // --- Metrics update ---
    function updateMetrics(data) {
        metricTps.textContent = (data.tps || 0).toFixed(1);
        metricTotal.textContent = data.totalCount || 0;
        metricAvgAmt.textContent = '€' + (data.avgAmount || 0).toFixed(0);
        metricMaxAmt.textContent = '€' + (data.maxAmount || 0).toFixed(0);
        metricRisk.textContent = (data.highRiskPct || 0).toFixed(1) + '%';
    }

    // --- Highlight risk card on anomaly ---
    function highlightRisk(pct) {
        var card = metricRisk.parentElement;
        if (pct > 30) {
            card.style.borderColor = '#FF4438';
            card.style.background = 'rgba(255, 68, 56, 0.08)';
        } else {
            card.style.borderColor = '';
            card.style.background = '';
        }
    }

    // --- Poll metrics ---
    function fetchMetrics() {
        fetch('/api/transactions/metrics')
            .then(function (r) { return r.json(); })
            .then(function (data) {
                updateMetrics(data);
                highlightRisk(data.highRiskPct || 0);
                drawChart(chartCount, data.countSeries || [], 'TPS', '#FF4438', '');
                drawChart(chartAmount, data.amountSeries || [], 'Avg €', '#36B37E', '€');
                // Sync button state
                if (data.simulating !== simulating) {
                    simulating = data.simulating;
                    updateButtons();
                }
            })
            .catch(function () { /* ignore fetch errors */ });
    }

    function startPolling() {
        if (pollInterval) return;
        pollInterval = setInterval(fetchMetrics, 1000);
        fetchMetrics();
    }

    function stopPolling() {
        if (pollInterval) {
            clearInterval(pollInterval);
            pollInterval = null;
        }
    }

    // --- Button state ---
    function updateButtons() {
        btnStart.disabled = simulating;
        btnStop.disabled = !simulating;
    }

    // --- Event handlers ---
    btnStart.addEventListener('click', function () {
        fetch('/api/transactions/simulate/start', { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function () {
                simulating = true;
                updateButtons();
                startPolling();
            });
    });

    btnStop.addEventListener('click', function () {
        fetch('/api/transactions/simulate/stop', { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function () {
                simulating = false;
                updateButtons();
                // Keep polling briefly to show final state
                setTimeout(stopPolling, 3000);
            });
    });

    btnAnomaly.addEventListener('click', function () {
        fetch('/api/transactions/simulate/anomaly', { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function () {
                // Ensure polling is active to see the spike
                if (!pollInterval) startPolling();
            });
    });

    btnReset.addEventListener('click', function () {
        fetch('/api/transactions/reset', { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function () {
                simulating = false;
                updateButtons();
                stopPolling();
                fetchMetrics();
            });
    });

    // --- Resize handler ---
    var resizeTimer;
    window.addEventListener('resize', function () {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(fetchMetrics, 200);
    });

    // --- Initial state ---
    fetchMetrics();
    // Check if simulation is already running
    fetch('/api/transactions/metrics')
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (data.simulating) {
                simulating = true;
                updateButtons();
                startPolling();
            }
        });
})();
