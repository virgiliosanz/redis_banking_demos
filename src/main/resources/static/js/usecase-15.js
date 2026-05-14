(function () {
    'use strict';

    window.WORKSHOP_UC = 'UC15';
    if (window.initCodeTabs) window.initCodeTabs();

    var chatEl = document.getElementById('uc15-chat');
    var inputEl = document.getElementById('uc15-input');
    var sendEl = document.getElementById('uc15-send');
    var resetEl = document.getElementById('uc15-reset');
    var userInputEl = document.getElementById('uc15-user-id');
    var userLabelEl = document.getElementById('uc15-user-label');
    var auditBodyEl = document.getElementById('uc15-audit-body');

    var totalChatsEl = document.getElementById('uc15-total-chats');
    var blockedChatsEl = document.getElementById('uc15-blocked-chats');
    var piiFlagsEl = document.getElementById('uc15-pii-flags');
    var complianceFlagsEl = document.getElementById('uc15-compliance-flags');

    function escapeHtml(text) {
        var div = document.createElement('div');
        div.textContent = text == null ? '' : String(text);
        return div.innerHTML;
    }

    function currentUserId() {
        return (userInputEl && userInputEl.value ? userInputEl.value : 'demo-user').trim() || 'demo-user';
    }

    function syncUserLabel() {
        if (userLabelEl) userLabelEl.textContent = currentUserId();
    }

    function fetchJson(url, opts) {
        opts = opts || {};
        opts.headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
        return fetch(url, opts).then(function (res) {
            return res.json().then(function (body) {
                if (!res.ok) {
                    throw Object.assign(new Error(body.message || res.statusText), { body: body, status: res.status });
                }
                return body;
            });
        });
    }

    function stageClass(status) {
        var normalized = String(status || '').toLowerCase();
        if (normalized === 'block') return 'block';
        if (normalized === 'flag') return 'flag';
        return 'pass';
    }

    function addMessage(role, text, payload) {
        var welcome = chatEl.querySelector('.chat-welcome');
        if (welcome) welcome.remove();

        var pipeline = payload && payload.pipeline ? payload.pipeline : [];
        var route = payload && payload.route ? payload.route : '';
        var blocked = payload && payload.blocked;
        var html = '';
        html += '<div class="uc15-msg ' + escapeHtml(role) + '">';
        html += '<div class="uc15-msg-head"><span>' + escapeHtml(role === 'user' ? 'User' : 'Assistant') + '</span>';
        if (payload && payload.latencyMs != null) {
            html += '<span>' + escapeHtml(payload.latencyMs) + 'ms</span>';
        }
        html += '</div>';
        html += '<div class="uc15-msg-body">' + escapeHtml(text).replace(/\n/g, '<br/>') + '</div>';
        if (route) {
            html += '<div class="uc15-route">Route: <strong>' + escapeHtml(route) + '</strong>' + (blocked ? ' · blocked' : '') + '</div>';
        }
        if (pipeline.length) {
            html += '<div class="uc15-pipeline">';
            pipeline.forEach(function (step) {
                html += '<span class="uc15-stage ' + stageClass(step.status) + '">' + escapeHtml(step.stage) + ' · ' + escapeHtml(step.status) + ' · ' + escapeHtml(step.latencyMs) + 'ms</span>';
            });
            html += '</div>';
            html += '<div class="uc15-pipeline-detail">';
            pipeline.forEach(function (step) {
                html += '<div><strong>' + escapeHtml(step.stage) + ':</strong> ' + escapeHtml(step.detail || '') + '</div>';
            });
            html += '</div>';
        }
        html += '</div>';
        chatEl.insertAdjacentHTML('beforeend', html);
        chatEl.scrollTop = chatEl.scrollHeight;
    }

    function renderStats(stats) {
        if (!stats) return;
        if (totalChatsEl) totalChatsEl.textContent = stats.totalChats || 0;
        if (blockedChatsEl) blockedChatsEl.textContent = stats.blockedChats || 0;
        if (piiFlagsEl) piiFlagsEl.textContent = (stats.inputPiiFlags || 0) + (stats.outputPiiFlags || 0);
        if (complianceFlagsEl) complianceFlagsEl.textContent = stats.complianceAdjustments || 0;
    }

    function renderAudit(entries) {
        if (!entries || !entries.length) {
            auditBodyEl.innerHTML = '<tr><td colspan="4" class="uc15-empty">No audit events yet.</td></tr>';
            return;
        }

        var html = '';
        entries.forEach(function (entry) {
            var statusClass = stageClass(entry.status);
            html += '<tr>';
            html += '<td><code>' + escapeHtml(entry.stage || '') + '</code></td>';
            html += '<td><span class="uc15-stage ' + statusClass + '">' + escapeHtml(entry.status || '') + '</span></td>';
            html += '<td>' + escapeHtml(entry.latencyMs || '0') + 'ms</td>';
            html += '<td>' + escapeHtml(entry.detail || '') + '</td>';
            html += '</tr>';
        });
        auditBodyEl.innerHTML = html;
    }

    function refreshStats() {
        return fetchJson('/api/guardrails/stats').then(renderStats);
    }

    function refreshAudit() {
        return fetchJson('/api/guardrails/audit?limit=20').then(function (data) {
            renderAudit(data.entries || []);
        });
    }

    function sendMessage(message) {
        if (!message || !message.trim()) return Promise.resolve();

        syncUserLabel();
        addMessage('user', message, null);
        inputEl.value = '';
        sendEl.disabled = true;

        return fetchJson('/api/guardrails/chat', {
            method: 'POST',
            body: JSON.stringify({
                userId: currentUserId(),
                message: message
            })
        }).then(function (data) {
            addMessage('assistant', data.response || '(empty response)', data);
            renderStats(data.stats || null);
            return refreshAudit();
        }).catch(function (err) {
            addMessage('assistant', '⚠ ' + (err.message || err), { blocked: true, route: 'error', pipeline: [] });
        }).finally(function () {
            sendEl.disabled = false;
            inputEl.focus();
        });
    }

    function resetDemo() {
        resetEl.disabled = true;
        return fetchJson('/api/guardrails/reset', { method: 'POST' }).then(function () {
            chatEl.innerHTML = '<div class="chat-welcome">Runtime state reset. Send another prompt to rebuild the guardrail trail.</div>';
            return Promise.all([refreshStats(), refreshAudit()]);
        }).finally(function () {
            resetEl.disabled = false;
        });
    }

    if (sendEl) {
        sendEl.addEventListener('click', function () {
            sendMessage(inputEl.value);
        });
    }

    if (inputEl) {
        inputEl.addEventListener('keydown', function (event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                sendMessage(inputEl.value);
            }
        });
    }

    if (resetEl) {
        resetEl.addEventListener('click', resetDemo);
    }

    if (userInputEl) {
        userInputEl.addEventListener('change', syncUserLabel);
        userInputEl.addEventListener('keyup', syncUserLabel);
    }

    document.querySelectorAll('.uc15-prompt').forEach(function (button) {
        button.addEventListener('click', function () {
            var prompt = button.getAttribute('data-prompt') || '';
            inputEl.value = prompt;
            sendMessage(prompt);
        });
    });

    syncUserLabel();
    refreshStats().then(refreshAudit);
})();