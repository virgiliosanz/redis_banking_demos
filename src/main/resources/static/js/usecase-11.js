/** UC11: Real-time Transaction Monitoring — Redis Streams */
(function () {
    'use strict';
    window.WORKSHOP_UC = 'UC11';

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
    var metricsWarningShown = false;

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
    function updateMetric(el, nextValue) {
        if (!el) return;
        var next = String(nextValue);
        if (el.textContent !== next) {
            el.textContent = next;
            window.animateResult(el.parentElement || el, 'highlight-new');
        }
    }

    function updateMetrics(data) {
        updateMetric(metricTps, (data.tps || 0).toFixed(1));
        updateMetric(metricTotal, data.totalCount || 0);
        updateMetric(metricAvgAmt, '€' + (data.avgAmount || 0).toFixed(0));
        updateMetric(metricMaxAmt, '€' + (data.maxAmount || 0).toFixed(0));
        updateMetric(metricRisk, (data.highRiskPct || 0).toFixed(1) + '%');
    }

    // --- Highlight risk card on anomaly ---
    function highlightRisk(pct) {
        var card = metricRisk.parentElement;
        if (pct > 30) {
            card.style.borderColor = '#FF4438';
            card.style.background = 'rgba(255, 68, 56, 0.08)';
            window.animateResult(card, 'highlight-new');
        } else {
            card.style.borderColor = '';
            card.style.background = '';
        }
    }

    // --- Poll metrics ---
    function fetchMetrics() {
        window.workshopGet('/api/transactions/metrics', 'btnStart')
            .then(function (data) {
                if (metricsWarningShown) {
                    metricsWarningShown = false;
                    window.showToast('Transaction metrics stream reconnected.', 'success', 2500);
                }
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
            .catch(function () {
                if (!metricsWarningShown) {
                    metricsWarningShown = true;
                    window.showToast('Could not refresh transaction metrics. Retrying…', 'warning');
                }
            });
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
        window.workshopFetch('/api/transactions/simulate/start', {}, 'btnStart')
            .then(function () {
                simulating = true;
                updateButtons();
                startPolling();
                window.showToast('Transaction simulator started.', 'success');
            }).catch(function (err) {
                window.showToast((err && err.message) || 'Could not start the transaction simulator.', 'error');
            });
    });

    btnStop.addEventListener('click', function () {
        window.workshopFetch('/api/transactions/simulate/stop', {}, 'btnStart')
            .then(function () {
                simulating = false;
                updateButtons();
                // Keep polling briefly to show final state
                setTimeout(stopPolling, 3000);
                window.showToast('Transaction simulator stopped.', 'info');
            }).catch(function (err) {
                window.showToast((err && err.message) || 'Could not stop the simulator.', 'error');
            });
    });

    btnAnomaly.addEventListener('click', function () {
        window.workshopFetch('/api/transactions/simulate/anomaly', {}, 'btnStart')
            .then(function () {
                // Ensure polling is active to see the spike
                if (!pollInterval) startPolling();
                window.showToast('Injected an anomaly into the live stream.', 'warning');
            }).catch(function (err) {
                window.showToast((err && err.message) || 'Could not inject an anomaly.', 'error');
            });
    });

    btnReset.addEventListener('click', function () {
        window.workshopFetch('/api/transactions/reset', {}, 'btnStart')
            .then(function () {
                simulating = false;
                updateButtons();
                stopPolling();
                fetchMetrics();
                window.showToast('Transaction monitoring demo reset.', 'success');
            }).catch(function (err) {
                window.showToast((err && err.message) || 'Could not reset transaction monitoring.', 'error');
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
    window.workshopGet('/api/transactions/metrics', 'btnStart')
        .then(function (data) {
            if (data.simulating) {
                simulating = true;
                updateButtons();
                startPolling();
            }
        });
})();
