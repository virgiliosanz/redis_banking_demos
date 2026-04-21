/**
 * UC14: Agent Memory Server (dedicated)
 * Drives the /api/ams/** endpoints: status, seed, reset, chat and traces.
 * Separate from UC9 on purpose — this showcase focuses on AMS working memory,
 * long-term memory and context assembly (no raw Redis commands here).
 */
(function () {
    'use strict';
    window.WORKSHOP_UC = 'UC14';

    var SESSION_ID = 'ams-' + Math.random().toString(36).substring(2, 10);
    var USER_ID = 'demo-user';

    // --- DOM refs ---
    var amsBadge = document.getElementById('ams-status-badge');
    var aiBadge = document.getElementById('ai-badge');
    var amsBaseUrl = document.getElementById('ams-base-url');
    var amsMcpUrl = document.getElementById('ams-mcp-url');
    var amsNamespace = document.getElementById('ams-namespace');
    var amsSession = document.getElementById('ams-session');
    var amsUser = document.getElementById('ams-user');
    var amsSeedCount = document.getElementById('ams-seed-count');
    var btnSeed = document.getElementById('btnSeed');
    var btnReset = document.getElementById('btnReset');
    var chatMessages = document.getElementById('chat-messages');
    var chatInput = document.getElementById('chatInput');
    var sendBtn = document.getElementById('sendBtn');
    var wmBody = document.getElementById('wm-body');
    var ltBody = document.getElementById('lt-body');
    var assembledBody = document.getElementById('assembled-body');
    var latencyDisplay = document.getElementById('latency-display');
    var tracesBody = document.getElementById('traces-body');

    if (amsSession) amsSession.textContent = SESSION_ID;

    window.initCodeTabs();

    // --- Helpers ---
    function escapeHtml(text) {
        var div = document.createElement('div');
        div.textContent = text == null ? '' : String(text);
        return div.innerHTML;
    }

    function setBadge(el, label, state) {
        if (!el) return;
        el.textContent = label;
        el.classList.remove('ok', 'fail', 'mock');
        el.classList.add(state);
    }

    function fetchJson(url, opts) {
        opts = opts || {};
        opts.headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
        return fetch(url, opts).then(function (r) {
            return r.json().then(function (body) {
                if (!r.ok) throw Object.assign(new Error(body.error || r.statusText), { body: body, status: r.status });
                return body;
            });
        });
    }

    function addMessage(role, content) {
        var welcome = chatMessages.querySelector('.chat-welcome');
        if (welcome) welcome.remove();
        var div = document.createElement('div');
        div.className = 'uc14-msg uc14-msg-' + role;
        div.innerHTML = escapeHtml(content).replace(/\n/g, '<br/>');
        chatMessages.appendChild(div);
        chatMessages.scrollTop = chatMessages.scrollHeight;
    }

    // --- Renderers ---
    function renderWorkingMemory(wm) {
        if (!wm || !wm.messages || !wm.messages.length) {
            wmBody.textContent = 'No chat turns yet.';
            return;
        }
        var html = '';
        wm.messages.forEach(function (m) {
            html += '<div class="uc14-wm-item"><span class="uc14-role-pill uc14-role-' + escapeHtml(m.role) + '">'
                + escapeHtml(m.role) + '</span>' + escapeHtml(m.content) + '</div>';
        });
        wmBody.innerHTML = html;
    }

    function renderLongTerm(ctx) {
        var memories = (ctx && ctx.context && ctx.context.long_term_memories) || [];
        if (!memories.length) {
            ltBody.textContent = 'No long-term memories retrieved for this query.';
            return;
        }
        var html = '';
        memories.forEach(function (m) {
            var score = (m.dist != null) ? Number(m.dist).toFixed(3) : '';
            var topics = (m.topics || []).join(', ');
            html += '<div class="uc14-lt-item">'
                + '<div style="font-weight:600; color:var(--text-primary); margin-bottom:4px;">'
                + escapeHtml(m.id || '') + (score ? '<span class="uc14-score">dist ' + score + '</span>' : '')
                + '</div>'
                + '<div>' + escapeHtml(m.text || '') + '</div>'
                + (topics ? '<div style="font-size:0.7rem; color:var(--text-muted); margin-top:4px;">' + escapeHtml(topics) + '</div>' : '')
                + '</div>';
        });
        ltBody.innerHTML = html;
    }

    function renderAssembled(messages) {
        if (!messages || !messages.length) {
            assembledBody.textContent = 'No prompt assembled yet.';
            return;
        }
        var html = '';
        messages.forEach(function (m) {
            html += '<div class="uc14-assembled-item"><span class="uc14-role-pill uc14-role-' + escapeHtml(m.role) + '">'
                + escapeHtml(m.role) + '</span>' + escapeHtml(m.content) + '</div>';
        });
        assembledBody.innerHTML = html;
    }

    function renderTraces(entries) {
        if (!entries || !entries.length) {
            tracesBody.textContent = 'No traces yet. Run Seed or send a chat message.';
            return;
        }
        var html = '';
        entries.forEach(function (t, i) {
            var statusClass = (t.statusCode >= 200 && t.statusCode < 400) ? 'uc14-trace-status-ok' : 'uc14-trace-status-err';
            var body = { operation: t.operation, request: t.request, response: t.response };
            if (t.error) body.error = t.error;
            html += '<details class="uc14-trace"' + (i === 0 ? ' open' : '') + '>'
                + '<summary>'
                + '<span class="uc14-trace-method">' + escapeHtml(t.method) + '</span>'
                + '<span class="uc14-trace-path">' + escapeHtml(t.path) + '</span>'
                + '<span class="' + statusClass + '">' + escapeHtml(t.statusCode) + '</span>'
                + '<span class="uc14-trace-duration">' + escapeHtml(t.durationMs) + 'ms</span>'
                + '</summary>'
                + '<pre>' + escapeHtml(JSON.stringify(body, null, 2)) + '</pre>'
                + '</details>';
        });
        tracesBody.innerHTML = html;
    }

    // --- API actions ---
    function refreshTraces() {
        return fetchJson('/api/ams/traces?limit=20')
            .then(function (data) { renderTraces(data.traces || []); })
            .catch(function () { /* non-fatal on refresh */ });
    }

    function loadStatus() {
        return fetchJson('/api/ams/status').then(function (data) {
            if (amsBaseUrl) amsBaseUrl.textContent = data.baseUrl || '—';
            if (amsMcpUrl) amsMcpUrl.textContent = data.mcpUrl || '—';
            if (amsNamespace) amsNamespace.textContent = data.namespace || '—';
            if (amsUser) amsUser.textContent = USER_ID;
            if (amsSeedCount) amsSeedCount.textContent = (data.seededMemoryIds || []).length;
            setBadge(amsBadge, data.reachable ? 'AMS: reachable' : 'AMS: unreachable', data.reachable ? 'ok' : 'fail');
            setBadge(aiBadge, data.openaiConfigured ? 'AI: live' : 'AI: mock', data.openaiConfigured ? 'ok' : 'mock');
        }).catch(function (e) {
            setBadge(amsBadge, 'AMS: error', 'fail');
            console.error('UC14 status failed', e);
        });
    }

    function doSeed() {
        btnSeed.disabled = true;
        return fetchJson('/api/ams/seed', {
            method: 'POST',
            body: JSON.stringify({ sessionId: SESSION_ID, userId: USER_ID })
        }).then(function (data) {
            if (amsSeedCount) amsSeedCount.textContent = (data.seededMemoryIds || []).length;
            addMessage('assistant', 'Demo memories seeded. Ask a question about the customer.');
            return refreshTraces();
        }).catch(function (e) {
            addMessage('assistant', '⚠ Seed failed: ' + (e.message || e));
        }).finally(function () { btnSeed.disabled = false; });
    }

    function doReset() {
        btnReset.disabled = true;
        return fetchJson('/api/ams/reset', {
            method: 'POST',
            body: JSON.stringify({ sessionId: SESSION_ID, userId: USER_ID })
        }).then(function () {
            chatMessages.innerHTML = '<div class="chat-welcome">Session reset. Seed again to start fresh.</div>';
            renderWorkingMemory(null);
            renderLongTerm(null);
            renderAssembled(null);
            latencyDisplay.textContent = '';
            return refreshTraces();
        }).catch(function (e) {
            addMessage('assistant', '⚠ Reset failed: ' + (e.message || e));
        }).finally(function () { btnReset.disabled = false; });
    }

    function doChat(message) {
        if (!message || !message.trim()) return Promise.resolve();
        addMessage('user', message);
        chatInput.value = '';
        sendBtn.disabled = true;
        return fetchJson('/api/ams/chat', {
            method: 'POST',
            body: JSON.stringify({ sessionId: SESSION_ID, userId: USER_ID, message: message })
        }).then(function (data) {
            addMessage('assistant', data.response || '(empty response)');
            renderWorkingMemory(data.workingMemory);
            renderLongTerm(data.contextAssembly);
            renderAssembled(data.assembledMessages);
            latencyDisplay.textContent = 'Context assembly + LLM latency: ' + data.latencyMs + 'ms'
                + (data.openaiUsed ? ' · OpenAI live' : ' · mock reply');
            return refreshTraces();
        }).catch(function (e) {
            addMessage('assistant', '⚠ Chat failed: ' + (e.message || e));
        }).finally(function () { sendBtn.disabled = false; chatInput.focus(); });
    }

    // --- Wire up ---
    if (btnSeed) btnSeed.addEventListener('click', doSeed);
    if (btnReset) btnReset.addEventListener('click', doReset);
    if (sendBtn) sendBtn.addEventListener('click', function () { doChat(chatInput.value); });
    if (chatInput) chatInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); doChat(chatInput.value); }
    });
    document.querySelectorAll('.uc14-prompt').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var p = btn.getAttribute('data-prompt') || '';
            chatInput.value = p;
            doChat(p);
        });
    });

    loadStatus().then(refreshTraces);
})();

