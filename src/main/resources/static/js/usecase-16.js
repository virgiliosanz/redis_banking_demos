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
    var gatewayErrorBanner = document.getElementById('uc16-error-banner');
    var latencyBreakdown = document.getElementById('latencyBreakdown');
    var pipelineEl = document.getElementById('uc16-pipeline');
    var guardrailRouteValue = document.getElementById('guardrailRouteValue');
    var selectedModel = document.getElementById('selectedModel');
    var routeReason = document.getElementById('routeReason');
    var cacheValue = document.getElementById('cacheValue');
    var matchedQuestion = document.getElementById('matchedQuestion');
    var budgetRemaining = document.getElementById('budgetRemaining');
    var costValue = document.getElementById('costValue');
    var sessionSpend = document.getElementById('sessionSpend');
    var latencyValue = document.getElementById('latencyValue');

    window.initCodeTabs();

    if (sessionIdValue) sessionIdValue.textContent = sessionId;
    if (userIdValue) userIdValue.textContent = userId;
    if (gatewayBadge) gatewayBadge.textContent = 'AI: checking…';

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

    function stageClass(status) {
        var normalized = String(status || '').toLowerCase();
        if (normalized === 'block' || normalized === 'blocked') return 'block';
        if (normalized === 'flag') return 'flag';
        return 'pass';
    }

    function stageLabel(stage) {
        var labels = {
            topic: 'Topic',
            inputPii: 'Input PII',
            promptInjection: 'Prompt Injection',
            modelRoute: 'Model Route',
            semanticCache: 'Semantic Cache',
            rateLimit: 'Rate Limit',
            response: 'Response',
            outputPii: 'Output PII',
            compliance: 'Compliance',
            cost: 'Cost + Log'
        };
        return labels[stage] || String(stage || 'Stage');
    }

    function setResultBadge(text, state) {
        if (!resultBadge) return;
        resultBadge.textContent = text;
        resultBadge.className = 'status-badge' + (state ? ' ' + state : '');
    }

    function setGatewayBadge(text, state) {
        if (!gatewayBadge) return;
        gatewayBadge.textContent = text;
        gatewayBadge.classList.remove('mock', 'on');
        gatewayBadge.classList.add(state || 'mock');
    }

    function showGatewayError(message) {
        if (!gatewayErrorBanner) return;
        gatewayErrorBanner.textContent = message;
        gatewayErrorBanner.style.display = 'block';
    }

    function hideGatewayError() {
        if (!gatewayErrorBanner) return;
        gatewayErrorBanner.textContent = '';
        gatewayErrorBanner.style.display = 'none';
    }

    function hasPipelineStage(pipeline, stage) {
        return Array.isArray(pipeline) && pipeline.some(function (step) {
            return step && step.stage === stage;
        });
    }

    function clearPipeline() {
        if (pipelineEl) pipelineEl.innerHTML = '';
    }

    function renderPipeline(pipeline) {
        if (!pipelineEl) return;
        if (!Array.isArray(pipeline) || !pipeline.length) {
            pipelineEl.innerHTML = '<div class="uc16-empty">No pipeline data.</div>';
            return;
        }

        var stagesHtml = '';
        var detailHtml = '';
        pipeline.forEach(function (step) {
            var label = stageLabel(step.stage);
            stagesHtml += '<span class="uc16-stage ' + stageClass(step.status) + '">'
                + escapeHtml(label) + ' · ' + escapeHtml(step.status || 'PASS') + ' · ' + escapeHtml(formatMs(step.latencyMs))
                + '</span>';
            detailHtml += '<div><strong>' + escapeHtml(label) + ':</strong> '
                + escapeHtml(step.detail || 'No detail provided.')
                + '</div>';
        });

        pipelineEl.innerHTML = stagesHtml
            + '<details class="uc16-pipeline-detail" open>'
            + '<summary>Step details</summary>'
            + '<div class="uc16-pipeline-detail-list">' + detailHtml + '</div>'
            + '</details>';
    }

    function resetView() {
        clearPipeline();
        if (guardrailRouteValue) guardrailRouteValue.textContent = '—';
        selectedModel.textContent = '—';
        routeReason.textContent = '—';
        cacheValue.textContent = '—';
        matchedQuestion.textContent = '—';
        budgetRemaining.textContent = '—';
        costValue.textContent = '—';
        sessionSpend.textContent = '—';
        latencyValue.textContent = '—';
        setResultBadge('Ready', '');
        resultMeta.textContent = 'Run a query to inspect route, cache, cost, and latency.';
        responseOutput.textContent = 'Run a query to inspect the final answer, the selected model, and the accumulated session cost.';
        latencyBreakdown.textContent = 'No latency breakdown captured yet.';
        hideGatewayError();
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

    function refreshStatsPanel() {
        return window.workshopGet('/api/gateway/stats').then(renderStats);
    }

    function refreshLogPanel() {
        return window.workshopGet('/api/gateway/log?limit=10').then(renderLog);
    }

    function refreshDashboard() {
        return Promise.all([refreshStatsPanel(), refreshLogPanel()]);
    }

    function renderResult(data) {
        var blocked = !!data.blocked;
        var cacheHit = !!data.cacheHit;
        var rateLimited = !!data.rateLimited;
        var rateLimit = data.rateLimit || {};
        var route = data.route || {};
        var cache = data.cache || {};
        var latency = data.latency || {};
        var cost = data.cost || {};
        var llmUnavailable = data.error === 'LLM not configured' || data.openaiConfigured === false;

        renderPipeline(data.pipeline);
        if (guardrailRouteValue) guardrailRouteValue.textContent = data.guardrailRoute || '—';
        selectedModel.textContent = data.model || '—';
        routeReason.textContent = route.reason || '—';
        cacheValue.textContent = cacheHit
            ? 'Hit @ ' + formatDistance(cache.distance)
            : (hasPipelineStage(data.pipeline, 'semanticCache') ? 'Miss' : 'Skipped');
        matchedQuestion.textContent = cache.matchedQuestion || '—';
        budgetRemaining.textContent = hasPipelineStage(data.pipeline, 'rateLimit')
            ? ((rateLimit.remaining != null ? rateLimit.remaining : '—')
                + (rateLimit.limit != null ? ' / ' + rateLimit.limit : ''))
            : 'Skipped';
        costValue.textContent = formatUsd(cost.estimatedCostUsd);
        sessionSpend.textContent = formatUsd(cost.sessionTotalUsd) + ' · ' + (cost.sessionTotalTokens || 0) + ' tok';
        latencyValue.textContent = formatMs(latency.totalMs);

        if (llmUnavailable) {
            setResultBadge('LLM unavailable', 'expired');
            resultMeta.textContent = 'Gateway completed the Redis pipeline but could not call OpenAI.';
            showGatewayError(data.message || 'LLM not configured — set OPENAI_API_KEY');
        } else if (blocked) {
            setResultBadge('Blocked', 'expired');
            resultMeta.textContent = rateLimited
                ? (data.model || 'Provider') + ' budget exhausted before model execution.'
                : 'Gateway stopped at the ' + stageLabel((data.pipeline && data.pipeline[data.pipeline.length - 1] && data.pipeline[data.pipeline.length - 1].stage) || 'topic').toLowerCase() + ' step.';
            hideGatewayError();
        } else if (cacheHit) {
            setResultBadge('Cache hit', 'active');
            resultMeta.textContent = 'Response served from semantic cache for the selected model.';
            hideGatewayError();
        } else {
            setResultBadge('Routed live', 'active');
            resultMeta.textContent = 'Gateway called ' + (data.model || 'the selected model') + ' and cached the result.';
            hideGatewayError();
        }

        responseOutput.textContent = llmUnavailable
            ? (data.message || data.error || 'LLM not configured — set OPENAI_API_KEY')
            : (data.response || data.error || 'No response body returned.');
        latencyBreakdown.textContent = 'topic ' + formatMs(latency.topicMs)
            + ' · input PII ' + formatMs(latency.inputPiiMs)
            + ' · injection ' + formatMs(latency.injectionMs)
            + ' · routing ' + formatMs(latency.routingMs)
            + ' · cache ' + formatMs(latency.cacheMs)
            + ' · rate-limit ' + formatMs(latency.rateLimitMs)
            + ' · response ' + formatMs(latency.responseMs)
            + ' · output PII ' + formatMs(latency.outputPiiMs)
            + ' · compliance ' + formatMs(latency.complianceMs)
            + ' · stats ' + formatMs(latency.statsMs)
            + ' · log ' + formatMs(latency.logMs)
            + ' · total ' + formatMs(latency.totalMs);

        if (llmUnavailable) {
            setGatewayBadge('AI: unavailable', 'mock');
        } else {
            setGatewayBadge('AI: OpenAI live', 'on');
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
        hideGatewayError();

        window.workshopFetch('/api/gateway/query', {
            query: query,
            userId: userId,
            sessionId: sessionId
        }).then(function (data) {
            data = data || {};
            renderResult(data);
            return data.blocked ? refreshLogPanel() : refreshDashboard();
        }).catch(function () {
            setResultBadge('Error', 'expired');
            resultMeta.textContent = 'Gateway request failed.';
            responseOutput.textContent = 'Could not reach /api/gateway/query. Verify the app and Redis are running.';
            setGatewayBadge('AI: unavailable', 'mock');
            showGatewayError('LLM request failed. Check OPENAI_API_KEY and server connectivity.');
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
            setGatewayBadge('AI: checking…', 'mock');
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