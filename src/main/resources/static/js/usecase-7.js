/**
 * UC7: Feature Store for Risk Scoring
 * Interactive demo: client feature dashboard, transaction simulator
 */
(function () {
    'use strict';

    // --- DOM refs ---
    var clientSelect = document.getElementById('clientSelect');
    var featureCard = document.getElementById('feature-card');
    var featureTable = document.getElementById('feature-table');
    var redisKeyDisplay = document.getElementById('redis-key-display');
    var riskBadgeContainer = document.getElementById('risk-badge-container');
    var riskBadge = document.getElementById('risk-badge');
    var simulateBtn = document.getElementById('simulateBtn');
    var txAmount = document.getElementById('txAmount');
    var txCountry = document.getElementById('txCountry');
    var commandsCard = document.getElementById('commands-card');
    var commandsOutput = document.getElementById('commands-output');

    // Feature labels for display
    var FEATURE_LABELS = {
        tx_count_1h: 'Transactions (1h)',
        tx_count_24h: 'Transactions (24h)',
        tx_amount_avg_24h: 'Avg Amount 24h (€)',
        tx_amount_max_24h: 'Max Amount 24h (€)',
        distinct_countries_7d: 'Distinct Countries (7d)',
        distinct_devices_30d: 'Distinct Devices (30d)',
        last_tx_timestamp: 'Last Transaction',
        risk_score: 'Risk Score'
    };

    // --- Code Tabs ---
    window.initCodeTabs();

    // --- Helpers ---
    function buildRow(label, value, highlight) {
        var cls = highlight ? ' style="font-weight:700; color:var(--redis-primary);"' : '';
        return '<div class="data-row"><span class="data-label">' + label +
               '</span><span class="data-value"' + cls + '>' + value + '</span></div>';
    }

    function formatTimestamp(ts) {
        if (!ts || ts === '0') return '—';
        var d = new Date(parseInt(ts));
        return d.toLocaleTimeString() + ' ' + d.toLocaleDateString();
    }

    function getRiskLevel(score) {
        var s = parseInt(score);
        if (s >= 70) return { label: 'HIGH RISK (' + s + ')', bg: 'rgba(255,68,56,0.15)', color: '#FF4438' };
        if (s >= 40) return { label: 'MEDIUM RISK (' + s + ')', bg: 'rgba(255,170,0,0.15)', color: '#cc8800' };
        return { label: 'LOW RISK (' + s + ')', bg: 'rgba(10,126,62,0.15)', color: '#0a7e3e' };
    }

    // --- Load clients into selector ---
    function loadClients() {
        window.workshopGet('/api/features/clients').then(function (clients) {
            clientSelect.innerHTML = '';
            clients.forEach(function (c) {
                var opt = document.createElement('option');
                opt.value = c.clientId;
                opt.textContent = c.clientId + ' — ' + c.name + ' (' + c.segment + ')';
                clientSelect.appendChild(opt);
            });
            if (clients.length > 0) {
                loadFeatures(clients[0].clientId);
            }
        });
    }

    // --- Load and display features for a client ---
    function loadFeatures(clientId) {
        window.workshopGet('/api/features/client/' + clientId).then(function (data) {
            if (data.error) return;

            featureCard.style.display = '';
            riskBadgeContainer.style.display = '';
            redisKeyDisplay.textContent = data.redisKey;

            var features = data.features;
            var rows = '';
            var keys = Object.keys(FEATURE_LABELS);
            keys.forEach(function (key) {
                var val = features[key] || '—';
                var label = FEATURE_LABELS[key];
                if (key === 'last_tx_timestamp') {
                    val = formatTimestamp(val);
                }
                var isRisk = (key === 'risk_score');
                rows += buildRow(label, val, isRisk);
            });
            featureTable.innerHTML = rows;

            // Risk badge
            var risk = getRiskLevel(features.risk_score || '0');
            riskBadge.textContent = risk.label;
            riskBadge.style.background = risk.bg;
            riskBadge.style.color = risk.color;
        });
    }

    // --- Client selector change ---
    clientSelect.addEventListener('change', function () {
        loadFeatures(clientSelect.value);
        commandsCard.style.display = 'none';
    });

    // --- Simulate transaction ---
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
        }).then(function (data) {
            simulateBtn.disabled = false;
            simulateBtn.textContent = 'Simulate Transaction';

            // Show executed Redis commands
            if (data.redisCommands) {
                commandsCard.style.display = '';
                commandsOutput.textContent = data.redisCommands.join('\n');
            }

            // Refresh feature dashboard
            loadFeatures(clientId);
        }).catch(function () {
            simulateBtn.disabled = false;
            simulateBtn.textContent = 'Simulate Transaction';
        });
    });

    // Enter key on amount field
    txAmount.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') simulateBtn.click();
    });

    // --- Init ---
    loadClients();
})();
