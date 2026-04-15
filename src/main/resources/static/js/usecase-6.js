/** UC6: Real-time Fraud Detection */
(function () {
    'use strict';

    var RISK_COLORS = {
        LOW: '#0a7e3e',
        MEDIUM: '#d4a017',
        HIGH: '#e67e22',
        CRITICAL: '#FF4438'
    };

    document.addEventListener('DOMContentLoaded', function () {
        var form = document.getElementById('fraudForm');
        var evaluateBtn = document.getElementById('evaluateBtn');
        var burstBtn = document.getElementById('burstBtn');
        var resetBtn = document.getElementById('resetBtn');
        var riskDisplay = document.getElementById('riskDisplay');
        var riskScoreEl = document.getElementById('riskScore');
        var riskGaugeFill = document.getElementById('riskGaugeFill');
        var riskLevelEl = document.getElementById('riskLevel');
        var riskFactorsEl = document.getElementById('riskFactors');
        var txStream = document.getElementById('txStream');

        function getFormData() {
            return {
                cardNumber: document.getElementById('cardNumber').value,
                amount: document.getElementById('amount').value,
                merchant: document.getElementById('merchant').value,
                country: document.getElementById('country').value
            };
        }

        function showRisk(data) {
            riskDisplay.style.display = 'block';
            var score = data.riskScore || 0;
            var level = data.riskLevel || 'LOW';
            var color = RISK_COLORS[level] || RISK_COLORS.LOW;

            riskScoreEl.textContent = score;
            riskScoreEl.style.color = color;
            riskGaugeFill.style.width = score + '%';
            riskGaugeFill.style.background = color;
            riskLevelEl.textContent = level;
            riskLevelEl.className = 'fraud-risk-level fraud-level-' + level.toLowerCase();

            var factorsHtml = '';
            if (data.factors && data.factors.length) {
                data.factors.forEach(function (f) {
                    factorsHtml += '<div class="fraud-factor">' + escapeHtml(f) + '</div>';
                });
            }
            riskFactorsEl.innerHTML = factorsHtml;

            // Animate
            riskDisplay.classList.remove('result-animate');
            void riskDisplay.offsetWidth;
            riskDisplay.classList.add('result-animate');
        }

        function escapeHtml(str) {
            var div = document.createElement('div');
            div.textContent = str;
            return div.innerHTML;
        }

        function renderStream(entries) {
            if (!entries || entries.length === 0) {
                txStream.innerHTML = '<p class="placeholder-text">No evaluations yet. Submit a transaction above.</p>';
                return;
            }
            var html = '<table class="log-table"><thead><tr>' +
                '<th>Risk</th><th>Score</th><th>Card</th><th>Amount</th><th>Country</th><th>Velocity</th><th>Time</th>' +
                '</tr></thead><tbody>';
            entries.forEach(function (e) {
                var level = (e.riskLevel || 'LOW').toLowerCase();
                var color = RISK_COLORS[e.riskLevel] || RISK_COLORS.LOW;
                var ts = e.timestamp ? new Date(e.timestamp).toLocaleTimeString() : '';
                var cardShort = (e.card || '').substring((e.card || '').length - 4);
                var geo = e.geoAnomaly === 'true' ? ' [!]' : '';
                html += '<tr>' +
                    '<td><span class="status-badge fraud-badge-' + level + '">' + (e.riskLevel || '') + '</span></td>' +
                    '<td style="font-weight:700;color:' + color + '">' + (e.riskScore || 0) + '</td>' +
                    '<td><code>***' + cardShort + '</code></td>' +
                    '<td>&euro;' + (e.amount || '0') + '</td>' +
                    '<td>' + (e.country || '') + geo + '</td>' +
                    '<td>' + (e.velocityCount || 0) + ' txs</td>' +
                    '<td>' + ts + '</td></tr>';
            });
            html += '</tbody></table>';
            txStream.innerHTML = html;
        }

        function submitEvaluation(data) {
            evaluateBtn.disabled = true;
            return workshopFetch('/api/fraud/evaluate', data)
                .then(function (result) {
                    showRisk(result);
                    return refreshStream();
                })
                .catch(function (err) {
                    riskDisplay.style.display = 'block';
                    riskScoreEl.textContent = '?';
                    riskLevelEl.textContent = 'ERROR';
                    riskFactorsEl.textContent = err.message;
                })
                .finally(function () {
                    evaluateBtn.disabled = false;
                });
        }

        function refreshStream() {
            return workshopGet('/api/fraud/stream?count=20').then(renderStream);
        }

        // Evaluate button
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            submitEvaluation(getFormData());
        });

        // Rapid-fire: send 5 transactions quickly to trigger velocity alert
        burstBtn.addEventListener('click', function () {
            var data = getFormData();
            burstBtn.disabled = true;
            burstBtn.textContent = 'Sending...';
            var chain = Promise.resolve();
            for (var i = 0; i < 5; i++) {
                chain = chain.then(function () {
                    return submitEvaluation(data);
                }).then(function () {
                    return new Promise(function (r) { setTimeout(r, 200); });
                });
            }
            chain.finally(function () {
                burstBtn.disabled = false;
                burstBtn.textContent = 'Rapid-fire (5 txs)';
            });
        });

        // Reset
        resetBtn.addEventListener('click', function () {
            workshopFetch('/api/fraud/reset', {}).then(function () {
                riskDisplay.style.display = 'none';
                refreshStream();
            });
        });

        // Load initial stream
        refreshStream();
    });
})();
