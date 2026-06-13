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
    var modeBadgeEl = document.getElementById('uc15-mode-badge');
    var errorBannerEl = document.getElementById('uc15-error-banner');

    var totalChatsEl = document.getElementById('uc15-total-chats');
    var blockedChatsEl = document.getElementById('uc15-blocked-chats');
    var piiFlagsEl = document.getElementById('uc15-pii-flags');
    var complianceFlagsEl = document.getElementById('uc15-compliance-flags');
    var LATENCY_CONTAINER_ID = 'uc15-send';

    function escapeHtml(text) {
        var div = document.createElement('div');
        div.textContent = text == null ? '' : String(text);
        return div.innerHTML;
    }

    function currentUserId() {
        return (userInputEl && userInputEl.value ? userInputEl.value : 'demo-user').trim() || 'demo-user';
    }

    function setModeBadge(label, state) {
        if (!modeBadgeEl) return;
        modeBadgeEl.textContent = label;
        modeBadgeEl.classList.remove('on', 'mock');
        modeBadgeEl.classList.add(state || 'mock');
    }

    function showErrorBanner(message) {
        if (!errorBannerEl) return;
        errorBannerEl.textContent = message;
        errorBannerEl.style.display = 'block';
    }

    function hideErrorBanner() {
        if (!errorBannerEl) return;
        errorBannerEl.textContent = '';
        errorBannerEl.style.display = 'none';
    }

    function syncUserLabel() {
        if (userLabelEl) userLabelEl.textContent = currentUserId();
    }

    function fetchJson(url, opts, containerId) {
        opts = opts || {};
        opts.headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
        return fetch(url, opts).then(function (response) {
            window.showLatencyBadge(containerId || LATENCY_CONTAINER_ID, window.extractLatency(response));
            return window.parseJsonResponse(response);
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
        window.animateResult(chatEl.lastElementChild, 'fade-in slide-up');
        window.animateChildren(chatEl.lastElementChild, '.uc15-stage', 'slide-up highlight-new', 20);
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
            window.renderAnimatedHtml(auditBodyEl, '<tr><td colspan="4" class="uc15-empty">No audit events yet.</td></tr>', {
                containerClasses: 'fade-in',
                childSelector: 'tr',
                childClasses: 'slide-up'
            });
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
        window.renderAnimatedHtml(auditBodyEl, html, {
            containerClasses: 'fade-in',
            childSelector: 'tr',
            childClasses: 'slide-up highlight-new',
            staggerMs: 20
        });
    }

    function refreshStats() {
        return fetchJson('/api/guardrails/stats', null, LATENCY_CONTAINER_ID).then(renderStats);
    }

    function refreshAudit() {
        return fetchJson('/api/guardrails/audit?limit=20', null, LATENCY_CONTAINER_ID).then(function (data) {
            renderAudit(data.entries || []);
        });
    }

    function sendMessage(message) {
        if (!message || !message.trim()) return Promise.resolve();

        syncUserLabel();
        addMessage('user', message, null);
        inputEl.value = '';
        sendEl.disabled = true;
        hideErrorBanner();

        return fetchJson('/api/guardrails/chat', {
            method: 'POST',
            body: JSON.stringify({
                userId: currentUserId(),
                message: message
            })
        }, 'uc15-send').then(function (data) {
            var llmUnavailable = data.error === 'LLM not configured' || data.openaiConfigured === false;
            if (llmUnavailable) {
                setModeBadge('AI: unavailable', 'mock');
                showErrorBanner(data.message || 'LLM not configured — set OPENAI_API_KEY');
                window.showToast(data.message || 'LLM not configured — set OPENAI_API_KEY', 'warning');
            } else {
                setModeBadge('AI: OpenAI live', 'on');
                window.showToast(data.blocked ? 'Guardrails blocked the request.' : 'Guardrail review completed successfully.', data.blocked ? 'warning' : 'success');
            }
            addMessage('assistant', llmUnavailable ? (data.message || data.error) : (data.response || '(empty response)'), data);
            renderStats(data.stats || null);
            return refreshAudit();
        }).catch(function (err) {
            setModeBadge('AI: unavailable', 'mock');
            showErrorBanner('LLM request failed. Check OPENAI_API_KEY and server connectivity.');
            addMessage('assistant', '⚠ ' + (err.message || err), { blocked: true, route: 'error', pipeline: [] });
            window.showToast((err && err.message) || 'LLM request failed. Check OPENAI_API_KEY and server connectivity.', 'error');
        }).finally(function () {
            sendEl.disabled = false;
            inputEl.focus();
        });
    }

    function resetDemo() {
        resetEl.disabled = true;
        return fetchJson('/api/guardrails/reset', { method: 'POST' }, 'uc15-send').then(function () {
            chatEl.innerHTML = '<div class="chat-welcome">Runtime state reset. Send another prompt to rebuild the guardrail trail.</div>';
            hideErrorBanner();
            setModeBadge('AI: checking…', 'mock');
            window.showToast('Guardrails demo reset.', 'success');
            return Promise.all([refreshStats(), refreshAudit()]);
        }).catch(function (err) {
            window.showToast((err && err.message) || 'Could not reset the guardrails demo.', 'error');
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
    setModeBadge('AI: checking…', 'mock');
    refreshStats().then(refreshAudit);
})();