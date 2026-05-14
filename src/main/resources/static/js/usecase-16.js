/**
 * UC16: AI Gateway (Routing + Semantic Cache + Observability)
 */
(function () {
    'use strict';
    window.WORKSHOP_UC = 'UC16';

    var sessionId = 'gw-' + Math.random().toString(36).substring(2, 10);
    var userId = 'demo-user';

    var gatewayBadge = document.getElementById('gateway-badge');
    var sessionIdValue = document.getElementById('sessionIdValue');
    var userIdValue = document.getElementById('userIdValue');
    var queryInput = document.getElementById('queryInput');
    var sendBtn = document.getElementById('sendBtn');
    var resetBtn = document.getElementById('resetBtn');
    var statsGrid = document.getElementById('statsGrid');
    var logList = document.getElementById('logList');
    var responseOutput = document.getElementById('responseOutput');
    var resultBadge = document.getElementById('resultBadge');
    var resultMeta = document.getElementById('resultMeta');
    var latencyBreakdown = document.getElementById('latencyBreakdown');
    var selectedModel = document.getElementById('selectedModel');
    var routeReason = document.getElementById('routeReason');
    var cacheValue = document.getElementById('cacheValue');
    var matchedQuestion = document.getElementById('matchedQuestion');
    var budgetRemaining = document.getElementById('budgetRemaining');
    var costValue = document.getElementById('costValue');
    var sessionSpend = document.getElementById('sessionSpend');
    var latencyValue = document.getElementById('latencyValue');
    var routeStep = document.getElementById('step-route');
    var cacheStep = document.getElementById('step-cache');
    var limitStep = document.getElementById('step-limit');
    var responseStep = document.getElementById('step-response');
    var routeCopy = document.getElementById('step-route-copy');
    var cacheCopy = document.getElementById('step-cache-copy');
    var limitCopy = document.getElementById('step-limit-copy');
    var responseCopy = document.getElementById('step-response-copy');

    window.initCodeTabs();

    if (sessionIdValue) sessionIdValue.textContent = sessionId;
    if (userIdValue) userIdValue.textContent = userId;
    if (gatewayBadge) gatewayBadge.textContent = 'Providers: mock';

    function escapeHtml(text) {
        var div = document.createElement('div');
        div.textContent = text == null ? '' : String(text);
        return div.innerHTML;
    }

    function formatUsd(value) {
        var n = Number(value || 0);
        return '$' + n.toFixed(4);
    }

    function formatMs(value) {
        var n = Number(value || 0);
        return Math.round(n) + 'ms';
    }

    function formatDistance(value) {
        var n = Number(value);
        return isFinite(n) ? n.toFixed(4) : '—';
    }

    function formatPercent(value) {
        var n = Number(value || 0);
        return n.toFixed(1) + '%';
    }

    function setStepState(el, state) {
        if (!el) return;
        el.className = 'uc16-step ' + state;
    }

    function resetView() {
        setStepState(routeStep, 'is-idle');
        setStepState(cacheStep, 'is-idle');
        setStepState(limitStep, 'is-idle');
        setStepState(responseStep, 'is-idle');
        routeCopy.textContent = 'Vector routing decision pending.';
        cacheCopy.textContent = 'No cache lookup yet.';
        limitCopy.textContent = 'Per-model budget not checked yet.';
        responseCopy.textContent = 'Awaiting response and stream append.';
        selectedModel.textContent = '—';
        routeReason.textContent = '—';
        cacheValue.textContent = '—';
        matchedQuestion.textContent = '—';
        budgetRemaining.textContent = '—';
        costValue.textContent = '—';
        sessionSpend.textContent = '—';
        latencyValue.textContent = '—';
        resultBadge.textContent = 'Ready';
        resultMeta.textContent = 'Run a query to inspect route, cache, cost, and latency.';
        responseOutput.textContent = 'Run a query to inspect the final answer, the selected model, and the accumulated session cost.';
        latencyBreakdown.textContent = 'No latency breakdown captured yet.';
        if (queryInput) queryInput.focus();
    }

    function renderStats(data) {
        var models = (data && data.models) || [];
        if (!models.length) {
            statsGrid.innerHTML = '<div class="uc16-empty">No stats yet. Send a query to populate the dashboard.</div>';
            return;
        }

        var html = '';
        models.forEach(function (model) {
            html += '<div class="uc16-stat-card">';
            html += '<h4>' + escapeHtml(model.model || '') + '</h4>';
            html += '<p>' + escapeHtml(model.capability || '') + '</p>';
            html += '<div class="data-row"><span class="data-label">Requests</span><span class="data-value">' + escapeHtml(model.requests) + '</span></div>';
            html += '<div class="data-row"><span class="data-label">Cache hit rate</span><span class="data-value">' + formatPercent(model.cacheHitRate) + '</span></div>';
            html += '<div class="data-row"><span class="data-label">Avg latency</span><span class="data-value">' + formatMs(model.avgLatencyMs) + '</span></div>';
            html += '<div class="data-row"><span class="data-label">Total cost</span><span class="data-value">' + formatUsd(model.totalCostUsd) + '</span></div>';
            html += '<div class="data-row"><span class="data-label">Remaining budget</span><span class="data-value">' + escapeHtml(model.remaining) + ' / ' + escapeHtml(model.rateLimitPerMinute) + '</span></div>';
            html += '<div class="data-row"><span class="data-label">Cached entries</span><span class="data-value">' + escapeHtml(model.cachedEntries) + '</span></div>';
            html += '</div>';
        });
        statsGrid.innerHTML = html;
    }

    function renderLog(data) {
        var entries = (data && data.entries) || [];
        if (!entries.length) {
            logList.innerHTML = '<div class="uc16-empty">No gateway requests yet.</div>';
            return;
        }

        var html = '';
        entries.forEach(function (entry) {
            var cacheHit = String(entry.cacheHit) === 'true';
            var rateLimited = String(entry.rateLimited) === 'true';
            html += '<div class="uc16-log-item">';
            html += '<div class="uc16-log-top">';
            html += '<div class="uc16-log-query">' + escapeHtml(entry.query || '') + '</div>';
            html += '<div class="uc16-log-flags">';
            html += '<span class="uc16-flag">' + escapeHtml(entry.model || '') + '</span>';
            html += '<span class="uc16-flag ' + (cacheHit ? 'ok' : '') + '">' + (cacheHit ? 'CACHE HIT' : 'CACHE MISS') + '</span>';
            html += '<span class="uc16-flag ' + (rateLimited ? 'warn' : '') + '">' + (rateLimited ? 'LIMITED' : 'ALLOWED') + '</span>';
            html += '</div></div>';
            html += '<div class="uc16-log-meta">';
            html += escapeHtml(new Date(entry.timestamp).toLocaleString()) + ' · ';
            html += 'latency ' + escapeHtml(entry.latencyMs || '0') + 'ms · ';
            html += 'cost ' + escapeHtml(entry.costUsd || '0') + ' · ';
            html += 'remaining ' + escapeHtml(entry.remaining || '0');
            html += '</div>';
            if (entry.response) {
                html += '<div class="uc16-log-meta" style="margin-top:6px;">' + escapeHtml(entry.response) + '</div>';
            }
            html += '</div>';
        });
        logList.innerHTML = html;
    }

    function refreshDashboard() {
        return Promise.all([
            window.workshopGet('/api/gateway/stats').then(renderStats),
            window.workshopGet('/api/gateway/log?limit=10').then(renderLog)
        ]);
    }

    function renderResult(data) {
        var cacheHit = !!data.cacheHit;
        var rateLimited = !!data.rateLimited;
        var rateLimit = data.rateLimit || {};
        var route = data.route || {};
        var cache = data.cache || {};
        var latency = data.latency || {};
        var cost = data.cost || {};

        setStepState(routeStep, 'is-active');
        setStepState(cacheStep, cacheHit ? 'is-hit' : 'is-miss');
        setStepState(limitStep, rateLimited ? 'is-blocked' : 'is-ok');
        setStepState(responseStep, rateLimited ? 'is-blocked' : 'is-active');

        routeCopy.textContent = (route.model || data.model || '—') + ' · distance ' + formatDistance(route.distance);
        cacheCopy.textContent = cacheHit
            ? 'Hit on “' + (cache.matchedQuestion || 'similar prompt') + '”'
            : 'Miss · will store response for ' + (route.model || data.model || 'selected model');
        limitCopy.textContent = rateLimited
            ? 'Blocked · retry after ' + (rateLimit.retryAfter || 0) + 's'
            : 'Allowed · ' + (rateLimit.remaining || 0) + ' remaining in current window';
        responseCopy.textContent = rateLimited
            ? 'Returned rate-limit message and logged the request.'
            : 'Returned ' + (data.model || 'response') + ' and appended stream event.';

        selectedModel.textContent = data.model || '—';
        routeReason.textContent = route.reason || '—';
        cacheValue.textContent = cacheHit ? 'Hit @ ' + formatDistance(cache.distance) : 'Miss';
        matchedQuestion.textContent = cache.matchedQuestion || '—';
        budgetRemaining.textContent = (rateLimit.remaining != null ? rateLimit.remaining : '—')
            + (rateLimit.limit != null ? ' / ' + rateLimit.limit : '');
        costValue.textContent = formatUsd(cost.estimatedCostUsd);
        sessionSpend.textContent = formatUsd(cost.sessionTotalUsd) + ' · ' + (cost.sessionTotalTokens || 0) + ' tok';
        latencyValue.textContent = formatMs(latency.totalMs);

        if (rateLimited) {
            resultBadge.textContent = 'Rate limited';
            resultMeta.textContent = (data.model || 'Provider') + ' budget exhausted for this window.';
        } else if (cacheHit) {
            resultBadge.textContent = 'Cache hit';
            resultMeta.textContent = 'Response served from semantic cache for the selected model.';
        } else {
            resultBadge.textContent = 'Routed live';
            resultMeta.textContent = 'Gateway called ' + (data.model || 'the selected model') + ' and cached the result.';
        }

        responseOutput.textContent = data.response || data.error || 'No response body returned.';
        latencyBreakdown.textContent = 'routing ' + formatMs(latency.routingMs)
            + ' · cache ' + formatMs(latency.cacheMs)
            + ' · rate-limit ' + formatMs(latency.rateLimitMs)
            + ' · model ' + formatMs(latency.modelMs)
            + ' · stats ' + formatMs(latency.statsMs)
            + ' · log ' + formatMs(latency.logMs)
            + ' · total ' + formatMs(latency.totalMs);

        if (gatewayBadge) {
            gatewayBadge.textContent = 'Model: ' + (data.model || 'mock');
            gatewayBadge.classList.remove('mock', 'on');
            gatewayBadge.classList.add(rateLimited ? 'mock' : 'on');
        }
    }

    function runQuery(prompt) {
        var query = (prompt || queryInput.value || '').trim();
        if (!query) {
            queryInput.style.borderColor = 'var(--redis-primary)';
            return;
        }

        queryInput.style.borderColor = '';
        sendBtn.disabled = true;
        sendBtn.textContent = 'Routing…';

        window.workshopFetch('/api/gateway/query', {
            query: query,
            userId: userId,
            sessionId: sessionId
        }).then(function (data) {
            renderResult(data || {});
            return refreshDashboard();
        }).catch(function () {
            resultBadge.textContent = 'Error';
            resultMeta.textContent = 'Gateway request failed.';
            responseOutput.textContent = 'Could not reach /api/gateway/query. Verify the app and Redis are running.';
        }).finally(function () {
            sendBtn.disabled = false;
            sendBtn.textContent = 'Send through gateway';
            queryInput.focus();
        });
    }

    function resetGateway() {
        resetBtn.disabled = true;
        resetBtn.textContent = 'Resetting…';
        window.workshopFetch('/api/gateway/reset', {}).then(function () {
            sessionId = 'gw-' + Math.random().toString(36).substring(2, 10);
            if (sessionIdValue) sessionIdValue.textContent = sessionId;
            if (queryInput) queryInput.value = '';
            if (gatewayBadge) {
                gatewayBadge.textContent = 'Providers: mock';
                gatewayBadge.classList.remove('on');
                gatewayBadge.classList.add('mock');
            }
            resetView();
            return refreshDashboard();
        }).finally(function () {
            resetBtn.disabled = false;
            resetBtn.textContent = 'Reset demo';
        });
    }

    if (sendBtn) sendBtn.addEventListener('click', function () { runQuery(); });
    if (resetBtn) resetBtn.addEventListener('click', resetGateway);
    if (queryInput) queryInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            e.preventDefault();
            runQuery();
        }
    });
    document.querySelectorAll('.uc16-prompt').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var prompt = btn.getAttribute('data-prompt') || '';
            if (queryInput) queryInput.value = prompt;
            runQuery(prompt);
        });
    });

    resetView();
    refreshDashboard().catch(function () {
        statsGrid.innerHTML = '<div class="uc16-empty">Could not load gateway stats.</div>';
        logList.innerHTML = '<div class="uc16-empty">Could not load the gateway request log.</div>';
    });
})();