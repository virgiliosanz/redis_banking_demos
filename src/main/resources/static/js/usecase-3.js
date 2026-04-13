/** UC3: Transaction Deduplication */
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        var form = document.getElementById('paymentForm');
        var payBtn = document.getElementById('payBtn');
        var doubleClickBtn = document.getElementById('doubleClickBtn');
        var resetBtn = document.getElementById('resetBtn');
        var resultBox = document.getElementById('resultBox');
        var resultIcon = document.getElementById('resultIcon');
        var resultStatus = document.getElementById('resultStatus');
        var resultDetails = document.getElementById('resultDetails');
        var txLog = document.getElementById('txLog');

        function getFormData() {
            return {
                sender: document.getElementById('sender').value,
                receiver: document.getElementById('receiver').value,
                amount: document.getElementById('amount').value
            };
        }

        function showResult(data) {
            var accepted = data.status === 'ACCEPTED';
            resultBox.style.display = 'block';
            resultBox.className = 'result-box ' + (accepted ? 'result-accepted' : 'result-duplicate');
            resultIcon.textContent = accepted ? '✅' : '🚫';
            resultStatus.textContent = accepted ? 'Payment Accepted' : 'Duplicate Detected!';
            resultDetails.innerHTML =
                '<span class="detail-label">Hash:</span> <code>' + data.txHash + '</code><br>' +
                '<span class="detail-label">Key:</span> <code>' + data.redisKey + '</code><br>' +
                '<span class="detail-label">TTL:</span> ' + data.ttlSeconds + 's';

            // Animate
            resultBox.classList.remove('result-animate');
            void resultBox.offsetWidth; // force reflow
            resultBox.classList.add('result-animate');
        }

        function renderLog(entries) {
            if (!entries || entries.length === 0) {
                txLog.innerHTML = '<p class="placeholder-text">No transactions yet. Submit a payment above.</p>';
                return;
            }
            var html = '<table class="log-table"><thead><tr>' +
                '<th>Status</th><th>Sender</th><th>Receiver</th><th>Amount</th><th>Hash</th><th>Time</th>' +
                '</tr></thead><tbody>';
            entries.forEach(function (e) {
                var accepted = e.status === 'ACCEPTED';
                var ts = e.timestamp ? new Date(e.timestamp).toLocaleTimeString() : '';
                html += '<tr class="' + (accepted ? 'log-accepted' : 'log-duplicate') + '">' +
                    '<td><span class="status-badge ' + (accepted ? 'badge-accepted' : 'badge-duplicate') + '">' +
                    e.status + '</span></td>' +
                    '<td>' + e.sender + '</td>' +
                    '<td>' + e.receiver + '</td>' +
                    '<td>&euro;' + e.amount + '</td>' +
                    '<td><code>' + e.txHash.substring(0, 10) + '…</code></td>' +
                    '<td>' + ts + '</td></tr>';
            });
            html += '</tbody></table>';
            txLog.innerHTML = html;
        }

        function submitPayment(data) {
            payBtn.disabled = true;
            return workshopFetch('/api/dedup/submit', data)
                .then(function (result) {
                    showResult(result);
                    return refreshLog();
                })
                .catch(function (err) {
                    resultBox.style.display = 'block';
                    resultBox.className = 'result-box result-duplicate';
                    resultIcon.textContent = '❌';
                    resultStatus.textContent = 'Error';
                    resultDetails.textContent = err.message;
                })
                .finally(function () {
                    payBtn.disabled = false;
                });
        }

        function refreshLog() {
            return workshopGet('/api/dedup/log').then(renderLog);
        }

        // Pay button
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            submitPayment(getFormData());
        });

        // Double-click simulation — sends same tx twice with 100ms gap
        doubleClickBtn.addEventListener('click', function () {
            var data = getFormData();
            doubleClickBtn.disabled = true;
            doubleClickBtn.textContent = '⚡ Sending...';
            submitPayment(data).then(function () {
                return new Promise(function (resolve) { setTimeout(resolve, 100); });
            }).then(function () {
                return submitPayment(data);
            }).finally(function () {
                doubleClickBtn.disabled = false;
                doubleClickBtn.textContent = '⚡ Double-Click Simulation';
            });
        });

        // Reset
        resetBtn.addEventListener('click', function () {
            workshopFetch('/api/dedup/reset', {}).then(function () {
                resultBox.style.display = 'none';
                refreshLog();
            });
        });

        // Load initial log
        refreshLog();
    });
})();
