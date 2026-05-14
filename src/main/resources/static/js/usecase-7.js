/**
 * UC7: Online Feature Store + ML Inference
 * Interactive demo: versioned features, transaction simulator, inference pipeline
 */
(function () {
    'use strict';
    window.WORKSHOP_UC = 'UC7';

    var clientSelect = document.getElementById('clientSelect');
    var featureSetSelect = document.getElementById('featureSetSelect');
    var featureCard = document.getElementById('feature-card');
    var featureTable = document.getElementById('feature-table');
    var redisKeyDisplay = document.getElementById('redis-key-display');
    var riskBadgeContainer = document.getElementById('risk-badge-container');
    var riskBadge = document.getElementById('risk-badge');
    var simulateBtn = document.getElementById('simulateBtn');
    var txAmount = document.getElementById('txAmount');
    var txCountry = document.getElementById('txCountry');
    var inferenceBtn = document.getElementById('inferenceBtn');
    var inferenceLatency = document.getElementById('inference-latency');
    var inferenceResult = document.getElementById('inference-result');
    var latencyComparison = document.getElementById('latency-comparison');
    var latencyComparisonEmpty = document.getElementById('latency-comparison-empty');
    var commandsCard = document.getElementById('redis-commands-card');
    var pipelineSteps = {
        fetch: document.getElementById('pipeline-fetch'),
        model: document.getElementById('pipeline-model'),
        decision: document.getElementById('pipeline-decision')
    };

    var FEATURE_LABELS = {
        feature_set_v1: {
            tx_count_1h: 'Transactions (1h)',
            tx_count_24h: 'Transactions (24h)',
            tx_amount_avg_24h: 'Avg Amount 24h (€)',
            tx_amount_max_24h: 'Max Amount 24h (€)',
            distinct_countries_7d: 'Distinct Countries (7d)',
            distinct_devices_30d: 'Distinct Devices (30d)',
            last_tx_timestamp: 'Last Transaction',
            risk_score: 'Risk Score'
        },
        feature_set_v2: {
            batch_income_monthly: 'Monthly Income (€)',
            batch_dti_ratio: 'Debt-to-Income Ratio',
            batch_payment_ratio_90d: 'Payment Ratio (90d)',
            batch_delinquency_count_12m: 'Delinquencies (12m)',
            batch_credit_utilization: 'Credit Utilization',
            realtime_tx_count_1h: 'Transactions (1h)',
            realtime_tx_count_24h: 'Transactions (24h)',
            realtime_tx_amount_avg_24h: 'Avg Amount 24h (€)',
            realtime_tx_amount_max_24h: 'Max Amount 24h (€)',
            realtime_distinct_countries_7d: 'Distinct Countries (7d)',
            realtime_distinct_devices_30d: 'Distinct Devices (30d)',
            realtime_seconds_since_last_tx: 'Seconds Since Last Tx',
            realtime_risk_score: 'Real-time Risk Score',
            last_tx_timestamp: 'Last Transaction'
        }
    };

    window.initCodeTabs();

    function buildRow(label, value, highlight) {
        var cls = highlight ? ' style="font-weight:700; color:var(--redis-primary);"' : '';
        return '<div class="data-row"><span class="data-label">' + label + '</span><span class="data-value"' + cls + '>' + value + '</span></div>';
    }

    function buildSectionTitle(title) {
        return '<div style="margin:12px 0 8px; font-size:0.75rem; font-family:var(--font-code); color:var(--text-muted); text-transform:uppercase; letter-spacing:0.05em;">' + title + '</div>';
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function formatTimestamp(ts) {
        if (!ts || ts === '0') return '—';
        var date = new Date(parseInt(ts, 10));
        if (isNaN(date.getTime())) return '—';
        return date.toLocaleTimeString() + ' ' + date.toLocaleDateString();
    }

    function formatMs(value) {
        var n = Number(value);
        if (isNaN(n)) return '—';
        return (n < 1 ? n.toFixed(3) : n.toFixed(2)) + ' ms';
    }

    function formatScore(value) {
        var n = Number(value);
        if (isNaN(n)) return '—';
        return (n * 100).toFixed(1) + '%';
    }

    function getSelectedFeatureSet() {
        return featureSetSelect ? featureSetSelect.value : 'feature_set_v2';
    }

    function getRiskScore(features, featureSetVersion) {
        if (!features) return '0';
        if (featureSetVersion === 'feature_set_v2') {
            return features.realtime_risk_score || '0';
        }
        return features.risk_score || '0';
    }

    function getRiskLevel(score) {
        var s = parseInt(score, 10) || 0;
        if (s >= 70) return { label: 'HIGH RISK (' + s + ')', bg: 'rgba(255,68,56,0.15)', color: '#FF4438' };
        if (s >= 40) return { label: 'MEDIUM RISK (' + s + ')', bg: 'rgba(245,158,11,0.15)', color: '#D97706' };
        return { label: 'LOW RISK (' + s + ')', bg: 'rgba(10,126,62,0.15)', color: '#0a7e3e' };
    }

    function formatFeatureValue(key, value) {
        if (key === 'last_tx_timestamp') return formatTimestamp(value);
        if (key.indexOf('_ratio') !== -1) return value;
        return value;
    }

    function buildGroupRows(title, features, labels, highlightKey) {
        if (!features || !Object.keys(features).length) return '';
        var rows = buildSectionTitle(title);
        Object.keys(features).forEach(function (key) {
            rows += buildRow(labels[key] || key, formatFeatureValue(key, features[key]), key === highlightKey);
        });
        return rows;
    }

    function buildFeatureRows(data) {
        var version = data.featureSetVersion || getSelectedFeatureSet();
        var groups = data.featureGroups || {};
        if (version === 'feature_set_v2') {
            return buildGroupRows('Batch Features', groups.batch || {}, FEATURE_LABELS.feature_set_v2, '') +
                buildGroupRows('Real-time Features', groups.realtime || {}, FEATURE_LABELS.feature_set_v2, 'realtime_risk_score') +
                buildGroupRows('Metadata', groups.metadata || {}, FEATURE_LABELS.feature_set_v2, '');
        }
        return buildGroupRows('Baseline Features', groups.baseline || data.features || {}, FEATURE_LABELS.feature_set_v1, 'risk_score');
    }

    function loadClients() {
        window.workshopGet('/api/features/clients').then(function (clients) {
            clientSelect.innerHTML = '';
            clients.forEach(function (client) {
                var opt = document.createElement('option');
                opt.value = client.clientId;
                opt.textContent = client.clientId + ' — ' + client.name + ' (' + client.segment + ')';
                clientSelect.appendChild(opt);
            });
            if (clients.length > 0) {
                loadFeatures(clients[0].clientId, getSelectedFeatureSet());
            }
        });
    }

    function loadFeatures(clientId, featureSet) {
        window.workshopGet('/api/features/client/' + clientId + '?version=' + encodeURIComponent(featureSet)).then(function (data) {
            if (data.error) return;

            featureCard.style.display = '';
            riskBadgeContainer.style.display = '';
            redisKeyDisplay.textContent = data.redisKey + ' • ' + data.featureSetVersion;
            featureTable.innerHTML = buildFeatureRows(data);

            var risk = getRiskLevel(getRiskScore(data.features, data.featureSetVersion));
            riskBadge.textContent = risk.label;
            riskBadge.style.background = risk.bg;
            riskBadge.style.color = risk.color;
        });
    }

    function setPipelineStep(step, state, text) {
        var el = pipelineSteps[step];
        if (!el) return;

        var background = 'var(--bg-tertiary)';
        var borderColor = 'var(--border-color)';
        var color = 'var(--text-primary)';

        if (state === 'active') {
            background = 'rgba(255, 68, 56, 0.08)';
            borderColor = 'rgba(255, 68, 56, 0.4)';
            color = 'var(--redis-primary)';
        } else if (state === 'done') {
            background = 'rgba(10, 126, 62, 0.08)';
            borderColor = 'rgba(10, 126, 62, 0.35)';
            color = '#0a7e3e';
        }

        el.style.background = background;
        el.style.borderColor = borderColor;
        el.style.color = color;
        var statusEl = el.querySelector('[data-step-status]');
        if (statusEl) {
            statusEl.textContent = text || 'Waiting';
            statusEl.style.color = state === 'default' ? 'var(--text-muted)' : color;
        }
    }

    function resetInferenceView() {
        setPipelineStep('fetch', 'default', 'Waiting');
        setPipelineStep('model', 'default', 'Waiting');
        setPipelineStep('decision', 'default', 'Waiting');

        inferenceLatency.style.display = 'none';
        inferenceLatency.innerHTML = '';

        inferenceResult.style.display = 'none';
        inferenceResult.className = 'result-box';
        inferenceResult.style.background = '';
        inferenceResult.style.borderColor = '';
        inferenceResult.innerHTML = '';

        latencyComparison.style.display = 'none';
        latencyComparison.innerHTML = '';
        latencyComparisonEmpty.style.display = '';
    }

    function renderInferenceLatency(latency) {
        inferenceLatency.style.display = '';
        inferenceLatency.innerHTML = buildSectionTitle('Latency Breakdown') +
            buildRow('Redis feature fetch', formatMs(latency.redisFeatureFetchMs), false) +
            buildRow('Model inference', formatMs(latency.modelInferenceMs), false) +
            buildRow('Total latency', formatMs(latency.totalMs), true);
    }

    function renderComparison(comparison) {
        latencyComparisonEmpty.style.display = 'none';
        latencyComparison.style.display = '';
        latencyComparison.innerHTML = buildRow('Redis online fetch', formatMs(comparison.redisFeatureFetchMs), true) +
            buildRow('Simulated database fetch', formatMs(comparison.simulatedDatabaseFetchMs), false) +
            buildRow('Latency saved', formatMs(comparison.savedMs), false) +
            buildRow('Speed-up', Number(comparison.speedupX).toFixed(1) + 'x', false);
    }

    function renderInferenceResult(data) {
        var decision = data.decision || 'REVIEW';
        var decisionIcon = decision === 'APPROVE' ? '✅' : (decision === 'DENY' ? '⛔' : '⚠️');
        var extraClass = decision === 'APPROVE' ? ' result-accepted' : (decision === 'DENY' ? ' result-duplicate' : '');

        inferenceResult.className = 'result-box' + extraClass;
        if (decision === 'REVIEW') {
            inferenceResult.style.background = 'rgba(245, 158, 11, 0.08)';
            inferenceResult.style.borderColor = 'rgba(245, 158, 11, 0.35)';
        }

        var signals = Array.isArray(data.signals) ? data.signals.slice(0, 3) : [];
        var signalItems = signals.map(function (signal) {
            return '<li style="margin-bottom:4px;">' + escapeHtml(signal) + '</li>';
        }).join('');

        inferenceResult.innerHTML = '<div class="result-icon">' + decisionIcon + '</div>' +
            '<div class="result-status">' + escapeHtml(decision) + '</div>' +
            '<div class="result-details">Approval probability: <strong>' + formatScore(data.probabilityScore) + '</strong></div>' +
            '<div class="result-details" style="margin-top:6px;">Confidence score: <strong>' + formatScore(data.confidenceScore) + '</strong></div>' +
            '<div class="result-details" style="margin-top:10px; text-align:left;">' +
                '<div style="font-family:var(--font-code); text-transform:uppercase; color:var(--text-muted); font-size:0.75rem; margin-bottom:6px;">Top model signals</div>' +
                '<ul style="margin:0; padding-left:18px;">' + signalItems + '</ul>' +
            '</div>';
        inferenceResult.style.display = '';
    }

    function runInference() {
        var clientId = clientSelect.value;
        var featureSet = getSelectedFeatureSet();

        inferenceBtn.disabled = true;
        inferenceBtn.textContent = 'Running...';
        resetInferenceView();
        setPipelineStep('fetch', 'active', 'HGETALL ' + featureSet);

        window.workshopGet('/api/features/inference/' + clientId + '?version=' + encodeURIComponent(featureSet)).then(function (data) {
            if (data.error) {
                inferenceBtn.disabled = false;
                inferenceBtn.textContent = 'Run ML Inference';
                return;
            }

            setPipelineStep('fetch', 'done', formatMs(data.latency.redisFeatureFetchMs) + ' • ' + data.featuresFetched + ' fields');

            setTimeout(function () {
                setPipelineStep('model', 'active', 'Scoring ' + (data.modelName || 'mock model'));

                setTimeout(function () {
                    setPipelineStep('model', 'done', formatMs(data.latency.modelInferenceMs));
                    setPipelineStep('decision', 'active', 'Producing credit decision');

                    setTimeout(function () {
                        setPipelineStep('decision', 'done', data.decision + ' • p=' + formatScore(data.probabilityScore));
                        renderInferenceLatency(data.latency || {});
                        renderComparison(data.comparison || {});
                        renderInferenceResult(data);
                    }, 180);
                }, 180);
            }, 120);

            inferenceBtn.disabled = false;
            inferenceBtn.textContent = 'Run ML Inference';
        }, function () {
            inferenceBtn.disabled = false;
            inferenceBtn.textContent = 'Run ML Inference';
            resetInferenceView();
            setPipelineStep('decision', 'active', 'Inference request failed');
        });
    }

    clientSelect.addEventListener('change', function () {
        loadFeatures(clientSelect.value, getSelectedFeatureSet());
        resetInferenceView();
        if (commandsCard) commandsCard.style.display = 'none';
    });

    featureSetSelect.addEventListener('change', function () {
        loadFeatures(clientSelect.value, getSelectedFeatureSet());
        resetInferenceView();
    });

    simulateBtn.addEventListener('click', function () {
        var clientId = clientSelect.value;
        var amount = parseFloat(txAmount.value);

        if (!amount || amount <= 0) {
            txAmount.style.borderColor = 'var(--redis-primary)';
            return;
        }
        txAmount.style.borderColor = '';

        simulateBtn.disabled = true;
        simulateBtn.textContent = 'Processing...';

        window.workshopFetch('/api/features/simulate', {
            clientId: clientId,
            amount: amount,
            country: txCountry.value
        }).then(function () {
            simulateBtn.disabled = false;
            simulateBtn.textContent = 'Simulate Transaction';
            loadFeatures(clientId, getSelectedFeatureSet());
            resetInferenceView();
        }, function () {
            simulateBtn.disabled = false;
            simulateBtn.textContent = 'Simulate Transaction';
        });
    });

    inferenceBtn.addEventListener('click', runInference);

    txAmount.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') simulateBtn.click();
    });

    resetInferenceView();
    loadClients();
})();
