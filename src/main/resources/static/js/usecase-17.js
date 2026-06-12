(function () {
    'use strict';

    window.WORKSHOP_UC = 'UC17';
    if (window.initCodeTabs) window.initCodeTabs();

    var AGENTS = [
        { key: 'risk', name: 'Risk Analyst', role: 'Financial risk assessment' },
        { key: 'compliance', name: 'Compliance Advisor', role: 'Regulatory obligations and controls' },
        { key: 'portfolio', name: 'Portfolio Advisor', role: 'Allocation and product guidance' },
        { key: 'fraud', name: 'Fraud Analyst', role: 'Alerts, anomalies, and suspicious activity' }
    ];
    var PHASES = ['start', 'thinking', 'searching', 'tools', 'reasoning', 'done'];
    var AGENT_MAP = Object.create(null);
    AGENTS.forEach(function (agent) { AGENT_MAP[agent.key] = agent; });

    var sessionId = 'coord-' + Math.random().toString(36).substring(2, 10);
    var userId = 'demo-user';
    var currentRunId = 0;
    var currentStream = null;
    var EXAMPLE_PLACEHOLDER = '';

    var queryInput = document.getElementById('uc17-query');
    var exampleSelect = document.getElementById('uc17-example');
    var sendBtn = document.getElementById('uc17-send');
    var resetBtn = document.getElementById('uc17-reset');
    var fullscreenToggleBtn = document.getElementById('uc17-fullscreen-toggle');
    var fullscreenThemeBtn = document.getElementById('uc17-theme-toggle');
    var fullscreenExitBtn = document.getElementById('uc17-exit-fullscreen');
    var modeBadge = document.getElementById('uc17-mode-badge');
    var sessionValue = document.getElementById('uc17-session-id');
    var userValue = document.getElementById('uc17-user-id');
    var coordinatorCard = document.getElementById('uc17-coordinator-card');
    var coordinatorStatus = document.getElementById('uc17-coordinator-status');
    var coordinatorCopy = document.getElementById('uc17-coordinator-copy');
    var coordinatorPlan = document.getElementById('uc17-plan');
    var coordinatorResponse = document.getElementById('uc17-final-response');
    var coordinatorDashboardResponse = document.getElementById('uc17-final-response-dashboard');
    var coordinatorCommands = document.getElementById('uc17-coordinator-commands');
    var totalLatency = document.getElementById('uc17-total-latency');
    var streamLog = document.getElementById('uc17-stream-log');

    var agentEls = Object.create(null);
    document.querySelectorAll('[data-agent-card]').forEach(function (card) {
        agentEls[card.getAttribute('data-agent-card')] = {
            card: card,
            status: card.querySelector('[data-agent-status]'),
            stateCopy: card.querySelector('[data-agent-state-copy]'),
            task: card.querySelector('[data-agent-task]'),
            progressSteps: Array.prototype.slice.call(card.querySelectorAll('[data-agent-progress-step]')),
            tools: card.querySelector('[data-agent-tools]'),
            response: card.querySelector('[data-agent-response]'),
            meta: card.querySelector('[data-agent-meta]'),
            commands: card.querySelector('[data-agent-commands]')
        };
    });

    if (sessionValue) sessionValue.textContent = sessionId;
    if (userValue) userValue.textContent = userId;

    var coordinatorState;
    var agentState;

    function escapeHtml(text) {
        var div = document.createElement('div');
        div.textContent = text == null ? '' : String(text);
        return div.innerHTML;
    }

    function formatMs(value) {
        var number = Number(value);
        return isFinite(number) ? Math.round(number) + 'ms' : '—';
    }

    function formatTokens(value) {
        var number = Number(value);
        return isFinite(number) && number > 0 ? Math.round(number) + ' tok' : null;
    }

    function isEditableTarget(target) {
        if (!target) return false;
        if (target.isContentEditable) return true;
        var tagName = (target.tagName || '').toLowerCase();
        return tagName === 'input' || tagName === 'textarea' || tagName === 'select';
    }

    function setFullscreen(enabled) {
        document.body.classList.toggle('uc17-fullscreen', enabled);
        if (fullscreenToggleBtn) {
            fullscreenToggleBtn.setAttribute('aria-pressed', String(enabled));
            fullscreenToggleBtn.setAttribute('title', enabled ? 'Exit fullscreen demo mode' : 'Enter fullscreen demo mode');
        }
        if (fullscreenThemeBtn) {
            fullscreenThemeBtn.setAttribute('aria-hidden', enabled ? 'false' : 'true');
        }
        if (fullscreenExitBtn) {
            fullscreenExitBtn.setAttribute('aria-hidden', enabled ? 'false' : 'true');
        }
    }

    function toggleFullscreen(forceState) {
        var nextState = typeof forceState === 'boolean'
            ? forceState
            : !document.body.classList.contains('uc17-fullscreen');
        setFullscreen(nextState);
    }

    function handleFullscreenShortcut(event) {
        if (event.altKey || event.ctrlKey || event.metaKey || event.repeat) return;
        if (event.key === 'Escape' && document.body.classList.contains('uc17-fullscreen')) {
            event.preventDefault();
            toggleFullscreen(false);
            return;
        }
        if ((event.key === 'f' || event.key === 'F') && !isEditableTarget(event.target)) {
            event.preventDefault();
            toggleFullscreen();
        }
    }

    function updateFullscreenThemeToggle() {
        if (!fullscreenThemeBtn) return;
        var isDark = document.documentElement.getAttribute('data-theme') === 'dark';
        fullscreenThemeBtn.textContent = isDark ? '☀ Light mode' : '☾ Dark mode';
    }

    function toggleFullscreenTheme() {
        var currentTheme = document.documentElement.getAttribute('data-theme');
        var nextTheme = currentTheme === 'dark' ? 'light' : 'dark';
        document.documentElement.setAttribute('data-theme', nextTheme);
        localStorage.setItem('redis-workshop-theme', nextTheme);
        updateFullscreenThemeToggle();
    }

    function truncate(text, max) {
        var value = String(text == null ? '' : text);
        return value.length > max ? value.substring(0, max - 1) + '…' : value;
    }

    function compactValue(value) {
        if (value == null || value === '') return '—';
        if (typeof value === 'string') return truncate(value, 160);
        if (Array.isArray(value)) return value.map(function (item) { return compactValue(item); }).join(', ');
        if (typeof value === 'object') {
            return truncate(JSON.stringify(value), 180);
        }
        return String(value);
    }

    function normalizeAgentKey(source) {
        var value = source;
        if (value && typeof value === 'object') {
            value = value.agent || value.name || value.role || value.id || '';
        }
        value = String(value || '').toLowerCase();
        if (value.indexOf('risk') !== -1) return 'risk';
        if (value.indexOf('compl') !== -1) return 'compliance';
        if (value.indexOf('portfolio') !== -1) return 'portfolio';
        if (value.indexOf('fraud') !== -1) return 'fraud';
        return '';
    }

    function normalizeCommands(commands) {
        if (!commands) return [];
        var list = Array.isArray(commands) ? commands : [commands];
        return list.map(function (entry) {
            if (entry == null) return '';
            if (typeof entry === 'string') return entry;
            if (typeof entry === 'object') return entry.command || entry.redisCommand || entry.name || JSON.stringify(entry);
            return String(entry);
        }).filter(Boolean);
    }

    function uniquePush(target, items) {
        items.forEach(function (item) {
            if (item && target.indexOf(item) === -1) target.push(item);
        });
    }

    function setModeBadge(mode, message) {
        if (!modeBadge) return;
        modeBadge.classList.remove('on', 'mock', 'error', 'checking');
        if (mode === 'openai') {
            modeBadge.textContent = 'AI: OpenAI';
            modeBadge.classList.add('on');
            return;
        }
        if (mode === 'error') {
            modeBadge.textContent = /configur/i.test(String(message || '')) || !message
                ? 'AI: not configured'
                : 'AI: error';
            modeBadge.classList.add('error');
            return;
        }
        modeBadge.textContent = 'AI: checking…';
        modeBadge.classList.add('checking');
    }

    function updateModeFromPayload(payload) {
        if (!payload || typeof payload !== 'object') return;
        if (typeof payload.openaiConfigured === 'boolean') {
            setModeBadge(payload.openaiConfigured ? 'openai' : 'error', payload.message);
        } else if (typeof payload.openAiConfigured === 'boolean') {
            setModeBadge(payload.openAiConfigured ? 'openai' : 'error', payload.message);
        } else if (typeof payload.openaiUsed === 'boolean') {
            setModeBadge(payload.openaiUsed ? 'openai' : 'error', payload.message);
        } else if (typeof payload.mode === 'string') {
            var mode = payload.mode.toLowerCase();
            if (mode === 'openai' || mode === 'live') {
                setModeBadge('openai', payload.message);
            } else if (mode === 'error') {
                setModeBadge('error', payload.message);
            }
        }
    }

    function setCoordinatorVisualState(state) {
        coordinatorCard.className = 'uc17-card uc17-coordinator ' + state;
    }

    function renderCommandStrip(el, commands) {
        if (!el) return;
        if (!commands.length) {
            el.className = 'uc17-command-strip is-empty';
            el.textContent = 'No commands recorded yet.';
            return;
        }
        el.className = 'uc17-command-strip';
        el.innerHTML = commands.map(function (command) {
            return '<span class="uc17-command-chip">' + escapeHtml(command) + '</span>';
        }).join('');
    }

    function renderPlan() {
        var plan = coordinatorState.plan;
        if (!plan.length) {
            coordinatorPlan.className = 'uc17-plan-list is-empty';
            coordinatorPlan.textContent = 'No subtasks dispatched yet.';
            return;
        }
        coordinatorPlan.className = 'uc17-plan-list';
        coordinatorPlan.innerHTML = plan.map(function (item) {
            return '<div class="uc17-plan-item"><strong>' + escapeHtml(item.name || item.agent || 'Specialist') + '</strong> · '
                + escapeHtml(item.role || item.task || 'Task pending')
                + (item.task && item.role ? '<br/>' + escapeHtml(item.task) : '')
                + '</div>';
        }).join('');
    }

    function renderStreamLog() {
        if (!coordinatorState.streamEvents.length) {
            streamLog.className = 'uc17-stream-log is-empty';
            streamLog.textContent = 'No stream activity yet.';
            return;
        }
        streamLog.className = 'uc17-stream-log';
        streamLog.innerHTML = coordinatorState.streamEvents.map(function (entry) {
            return '<div class="uc17-stream-log-item"><strong>' + escapeHtml(entry.title) + '</strong><br/>'
                + escapeHtml(entry.detail) + '</div>';
        }).join('');
    }

    function renderCoordinator() {
        coordinatorStatus.className = 'status-badge ' + coordinatorState.statusClass;
        coordinatorStatus.textContent = coordinatorState.badge;
        coordinatorCopy.innerHTML = coordinatorState.copy;
        coordinatorResponse.textContent = coordinatorState.response;
        if (coordinatorDashboardResponse) coordinatorDashboardResponse.textContent = coordinatorState.response;
        totalLatency.textContent = 'Total · ' + (coordinatorState.totalLatency ? formatMs(coordinatorState.totalLatency) : '—');
        renderPlan();
        renderCommandStrip(coordinatorCommands, coordinatorState.commands);
        renderStreamLog();
    }

    function renderAgent(key) {
        var refs = agentEls[key];
        var state = agentState[key];
        if (!refs || !state) return;

        refs.card.className = 'uc17-card uc17-agent-card ' + state.visual;
        refs.status.className = 'status-badge ' + state.statusClass;
        refs.status.textContent = state.badge;
        refs.stateCopy.innerHTML = state.stateCopy;
        refs.task.textContent = state.task;
        refs.response.textContent = state.response;

        refs.progressSteps.forEach(function (step, index) {
            step.className = 'uc17-progress-step';
            if (state.state === 'done' && index <= state.progressIndex) {
                step.classList.add('is-complete');
            } else if (state.state === 'error' && index === state.progressIndex) {
                step.classList.add('is-error');
            } else if (index < state.progressIndex) {
                step.classList.add('is-complete');
            } else if (index === state.progressIndex) {
                step.classList.add('is-current');
            }
        });

        if (state.tools.length || state.ragResults.length) {
            var html = '';
            if (state.tools.length) {
                html += '<div class="uc17-tool-list">' + state.tools.map(function (tool) {
                    return '<div class="uc17-tool-item"><strong>' + escapeHtml(tool.name || 'Tool') + '</strong> · '
                        + escapeHtml(compactValue(tool.result || tool.output || tool.detail || tool.value)) + '</div>';
                }).join('') + '</div>';
            }
            if (state.ragResults.length) {
                html += '<div class="uc17-rag-list">' + state.ragResults.map(function (item) {
                    var score = item.score != null ? '<span class="uc17-rag-score">score ' + escapeHtml(item.score) + '</span>' : '';
                    return '<div class="uc17-rag-item"><strong>' + escapeHtml(item.title || item.source || item.redisKey || 'RAG hit') + '</strong>'
                        + score + '<br/>' + escapeHtml(compactValue(item.summary || item.content || item.snippet || item.detail)) + '</div>';
                }).join('') + '</div>';
            }
            refs.tools.innerHTML = html;
        } else {
            refs.tools.textContent = state.toolsCopy;
        }

        var meta = [];
        if (state.state === 'working' || state.state === 'thinking' || state.state === 'searching' || state.state === 'tools' || state.state === 'reasoning') {
            meta.push('<span class="uc17-spinner-row"><span class="uc17-spinner"></span>' + escapeHtml(state.badge) + '…</span>');
        }
        if (state.latencyMs != null) {
            meta.push('<span class="uc17-inline-badge">Latency · ' + escapeHtml(formatMs(state.latencyMs)) + '</span>');
        }
        if (state.tokensLabel) {
            meta.push('<span class="uc17-inline-badge">Tokens · ' + escapeHtml(state.tokensLabel) + '</span>');
        }
        refs.meta.innerHTML = meta.join('');

        renderCommandStrip(refs.commands, state.commands);

        var laneStatus = document.querySelector('[data-lane-status="' + key + '"]');
        if (laneStatus) laneStatus.textContent = state.laneStatus;
    }

    function resetAgentState() {
        agentState = Object.create(null);
        AGENTS.forEach(function (agent) {
            agentState[agent.key] = {
                state: 'idle',
                visual: 'is-idle',
                badge: 'Idle',
                statusClass: 'uc17-status-idle',
                stateCopy: 'Waiting for coordinator dispatch.',
                task: 'Coordinator will assign work once the query is decomposed.',
                tools: [],
                ragResults: [],
                toolsCopy: 'No tool calls yet.',
                response: 'Response pending.',
                latencyMs: null,
                tokensLabel: null,
                progressIndex: -1,
                commands: [],
                laneStatus: 'Idle'
            };
            renderAgent(agent.key);
        });
    }

    function resetCoordinatorState() {
        coordinatorState = {
            badge: 'Ready',
            statusClass: 'uc17-status-idle',
            copy: 'Submit a complex question to generate a coordination plan.',
            response: 'The coordinator response will appear here after all specialists finish.',
            plan: [],
            commands: [],
            totalLatency: null,
            streamEvents: []
        };
        setCoordinatorVisualState('is-idle');
        renderCoordinator();
    }

    function resetView() {
        resetCoordinatorState();
        resetAgentState();
        document.querySelectorAll('[data-lane-track]').forEach(function (track) {
            track.innerHTML = '';
        });
    }

    function addStreamEvent(title, detail) {
        coordinatorState.streamEvents.unshift({ title: title, detail: detail });
        coordinatorState.streamEvents = coordinatorState.streamEvents.slice(0, 8);
        renderStreamLog();
    }

    function pulseLane(agentKey, direction) {
        var track = document.querySelector('[data-lane-track="' + agentKey + '"]');
        if (!track) return;
        var pulse = document.createElement('span');
        pulse.className = 'uc17-flow-pulse ' + direction;
        track.appendChild(pulse);
        setTimeout(function () {
            if (pulse.parentNode) pulse.parentNode.removeChild(pulse);
        }, 1300);
    }

    function defaultCommands(eventName, agentKey) {
        if (eventName === 'plan') {
            return ['XADD uc17:stream:tasks'];
        }
        if (eventName === 'agent-start') {
            return ['XREADGROUP GROUP uc17-agents ' + agentKey];
        }
        if (eventName === 'agent-done') {
            return ['XADD uc17:stream:results', 'XACK uc17:stream:tasks uc17-agents'];
        }
        if (eventName === 'assembling') {
            return ['XPENDING uc17:stream:tasks uc17-agents'];
        }
        return [];
    }

    function extractTokens(payload) {
        if (!payload || typeof payload !== 'object') return null;
        return payload.tokensUsed || payload.tokenCount || payload.totalTokens || (payload.tokenUsage && payload.tokenUsage.totalTokens) || null;
    }

    function handlePlan(payload) {
        updateModeFromPayload(payload);
        coordinatorState.badge = 'Planning';
        coordinatorState.statusClass = 'uc17-status-working';
        coordinatorState.copy = 'Coordinator decomposed the query and is dispatching parallel tasks through Redis Streams.';
        coordinatorState.plan = Array.isArray(payload.agents) ? payload.agents : [];
        uniquePush(coordinatorState.commands, normalizeCommands(payload.redisCommandsUsed || payload.redisCommands).concat(defaultCommands('plan')));
        setCoordinatorVisualState('is-active');
        addStreamEvent('Coordinator dispatched subtasks', (payload.query || 'Complex query') + ' · ' + coordinatorState.plan.length + ' specialists');
        coordinatorState.plan.forEach(function (planItem) {
            var key = normalizeAgentKey(planItem.name || planItem.agent || planItem.role || planItem.task);
            if (key) {
                agentState[key].task = planItem.task || planItem.role || agentState[key].task;
                renderAgent(key);
                pulseLane(key, 'outgoing');
            }
        });
        renderCoordinator();
    }

    function handleAgentStart(payload) {
        var key = normalizeAgentKey(payload.agent || payload.name || payload.role);
        if (!key) return;
        agentState[key].state = 'working';
        agentState[key].visual = 'is-working';
        agentState[key].badge = 'Starting';
        agentState[key].statusClass = 'uc17-status-working';
        agentState[key].stateCopy = 'Task picked up from the consumer group.';
        agentState[key].task = payload.task || agentState[key].task;
        agentState[key].progressIndex = PHASES.indexOf('start');
        agentState[key].laneStatus = 'Starting';
        uniquePush(agentState[key].commands, normalizeCommands(payload.redisCommands).concat(defaultCommands('agent-start', key)));
        pulseLane(key, 'outgoing');
        addStreamEvent(AGENT_MAP[key].name + ' started', payload.task || 'Processing assigned task');
        renderAgent(key);
    }

    function handleAgentThinking(payload) {
        var key = normalizeAgentKey(payload.agent || payload.name || payload.role);
        if (!key) return;
        agentState[key].state = 'thinking';
        agentState[key].visual = 'is-thinking';
        agentState[key].badge = 'Thinking';
        agentState[key].statusClass = 'uc17-status-thinking';
        agentState[key].stateCopy = payload.detail || 'Analyzing query and determining approach...';
        agentState[key].progressIndex = PHASES.indexOf('thinking');
        agentState[key].laneStatus = 'Thinking';
        addStreamEvent(AGENT_MAP[key].name + ' thinking', agentState[key].stateCopy);
        renderAgent(key);
    }

    function handleAgentSearching(payload) {
        var key = normalizeAgentKey(payload.agent || payload.name || payload.role);
        if (!key) return;
        agentState[key].state = 'searching';
        agentState[key].visual = 'is-searching';
        agentState[key].badge = 'Searching';
        agentState[key].statusClass = 'uc17-status-searching';
        agentState[key].stateCopy = payload.detail || 'Searching knowledge base and regulations...';
        agentState[key].toolsCopy = 'Searching knowledge base and regulations…';
        if (Array.isArray(payload.ragResults)) agentState[key].ragResults = payload.ragResults;
        agentState[key].progressIndex = PHASES.indexOf('searching');
        agentState[key].laneStatus = 'Searching';
        addStreamEvent(AGENT_MAP[key].name + ' searching', 'RAG hits ' + (payload.ragResults ? payload.ragResults.length : 0));
        renderAgent(key);
    }

    function handleAgentTools(payload) {
        var key = normalizeAgentKey(payload.agent || payload.name || payload.role);
        if (!key) return;
        agentState[key].state = 'tools';
        agentState[key].visual = 'is-tools';
        agentState[key].badge = 'Tools';
        agentState[key].statusClass = 'uc17-status-tools';
        agentState[key].stateCopy = 'Tool results and RAG context are arriving in real time.';
        agentState[key].toolsCopy = 'Streaming tools and RAG hits…';
        agentState[key].progressIndex = PHASES.indexOf('tools');
        uniquePush(agentState[key].commands, normalizeCommands(payload.redisCommands));
        if (Array.isArray(payload.tools)) agentState[key].tools = payload.tools;
        if (Array.isArray(payload.ragResults)) agentState[key].ragResults = payload.ragResults;
        agentState[key].laneStatus = 'Tools';
        pulseLane(key, 'outgoing');
        addStreamEvent(AGENT_MAP[key].name + ' used tools', 'Tool count ' + (payload.tools ? payload.tools.length : 0) + ' · RAG hits ' + (payload.ragResults ? payload.ragResults.length : 0));
        renderAgent(key);
    }

    function handleAgentReasoning(payload) {
        var key = normalizeAgentKey(payload.agent || payload.name || payload.role);
        if (!key) return;
        agentState[key].state = 'reasoning';
        agentState[key].visual = 'is-reasoning';
        agentState[key].badge = 'Reasoning';
        agentState[key].statusClass = 'uc17-status-reasoning';
        agentState[key].stateCopy = payload.detail || 'Generating specialist analysis...';
        agentState[key].progressIndex = PHASES.indexOf('reasoning');
        agentState[key].laneStatus = 'Reasoning';
        addStreamEvent(AGENT_MAP[key].name + ' reasoning', agentState[key].stateCopy);
        renderAgent(key);
    }

    function handleAgentDone(payload) {
        var key = normalizeAgentKey(payload.agent || payload.name || payload.role);
        if (!key) return;
        agentState[key].state = 'done';
        agentState[key].visual = 'is-done';
        agentState[key].badge = 'Done';
        agentState[key].statusClass = 'uc17-status-done';
        agentState[key].stateCopy = 'Finished specialist analysis and returned a result to the coordinator.';
        agentState[key].response = payload.response || payload.summary || 'Completed with no response body.';
        agentState[key].latencyMs = payload.latencyMs != null ? payload.latencyMs : agentState[key].latencyMs;
        agentState[key].tokensLabel = formatTokens(extractTokens(payload));
        agentState[key].progressIndex = PHASES.indexOf('done');
        agentState[key].laneStatus = 'Done';
        uniquePush(agentState[key].commands, normalizeCommands(payload.redisCommands).concat(defaultCommands('agent-done', key)));
        pulseLane(key, 'incoming');
        addStreamEvent(AGENT_MAP[key].name + ' finished', 'Latency ' + formatMs(payload.latencyMs) + ' · ' + truncate(payload.response || '', 120));
        renderAgent(key);
    }

    function handleAssembling(payload) {
        coordinatorState.badge = 'Assembling';
        coordinatorState.statusClass = 'uc17-status-working';
        coordinatorState.copy = 'All available agent outputs are back. Coordinator is assembling the final banking recommendation.';
        uniquePush(coordinatorState.commands, normalizeCommands(payload.redisCommandsUsed || payload.redisCommands).concat(defaultCommands('assembling')));
        setCoordinatorVisualState('is-active');
        addStreamEvent('Coordinator assembling', 'Combining specialist outputs into one final response');
        renderCoordinator();
    }

    function applyAgentSummary(summary) {
        var key = normalizeAgentKey(summary.agent || summary.name || summary.role);
        if (!key) return;
        if (summary.response && agentState[key].response === 'Response pending.') {
            agentState[key].response = summary.response;
        }
        if (summary.latencyMs != null && agentState[key].latencyMs == null) {
            agentState[key].latencyMs = summary.latencyMs;
        }
        var tokens = formatTokens(extractTokens(summary));
        if (tokens && !agentState[key].tokensLabel) agentState[key].tokensLabel = tokens;
        if (summary.status === 'error') {
            agentState[key].state = 'error';
            agentState[key].visual = 'is-error';
            agentState[key].badge = 'Error';
            agentState[key].statusClass = 'uc17-status-error';
            agentState[key].stateCopy = 'Agent execution failed.';
            agentState[key].laneStatus = 'Error';
            agentState[key].progressIndex = Math.max(agentState[key].progressIndex, PHASES.indexOf('reasoning'));
        } else if (agentState[key].state !== 'done') {
            agentState[key].state = 'done';
            agentState[key].visual = 'is-done';
            agentState[key].badge = 'Done';
            agentState[key].statusClass = 'uc17-status-done';
            agentState[key].stateCopy = 'Finished specialist analysis and returned a result to the coordinator.';
            agentState[key].progressIndex = PHASES.indexOf('done');
            agentState[key].laneStatus = 'Done';
        }
        renderAgent(key);
    }

    function handleResult(payload) {
        updateModeFromPayload(payload);
        var isErrorMode = payload && typeof payload.mode === 'string' && payload.mode.toLowerCase() === 'error';
        coordinatorState.badge = isErrorMode ? 'Error' : 'Complete';
        coordinatorState.statusClass = isErrorMode ? 'uc17-status-error' : 'uc17-status-done';
        coordinatorState.copy = isErrorMode
            ? 'Coordination finished with agent errors. Review the per-agent cards for details.'
            : 'Coordinator received all specialist outputs and assembled the final answer.';
        coordinatorState.response = payload.response || payload.finalResponse || 'No final response returned.';
        coordinatorState.totalLatency = payload.totalLatencyMs != null ? payload.totalLatencyMs : payload.latencyMs;
        uniquePush(coordinatorState.commands, normalizeCommands(payload.redisCommandsUsed || payload.redisCommands));
        setCoordinatorVisualState(isErrorMode ? 'is-error' : 'is-done');
        if (Array.isArray(payload.agentSummaries)) {
            payload.agentSummaries.forEach(applyAgentSummary);
        }
        addStreamEvent('Final result ready', 'Coordinator returned the assembled response to the UI');
        renderCoordinator();
    }

    function handleError(payload) {
        updateModeFromPayload(payload || { mode: 'error' });
        var key = normalizeAgentKey(payload.agent || payload.name || payload.role);
        if (key) {
            agentState[key].state = 'error';
            agentState[key].visual = 'is-error';
            agentState[key].badge = 'Error';
            agentState[key].statusClass = 'uc17-status-error';
            agentState[key].stateCopy = 'Agent execution failed.';
            agentState[key].response = payload.message || 'An unexpected agent error occurred.';
            agentState[key].progressIndex = Math.max(agentState[key].progressIndex, PHASES.indexOf('reasoning'));
            agentState[key].laneStatus = 'Error';
            renderAgent(key);
        }
        coordinatorState.badge = 'Error';
        coordinatorState.statusClass = 'uc17-status-error';
        coordinatorState.copy = 'The coordination flow failed before a final response could be assembled.';
        coordinatorState.response = payload.message || 'Could not complete the coordination request.';
        setCoordinatorVisualState('is-error');
        addStreamEvent('Coordination error', payload.message || 'Unknown error');
        renderCoordinator();
    }

    function handleEvent(eventName, payload, runId) {
        if (runId !== currentRunId) return;
        switch (eventName) {
            case 'plan':
                handlePlan(payload);
                break;
            case 'agent-start':
                handleAgentStart(payload);
                break;
            case 'agent-thinking':
                handleAgentThinking(payload);
                break;
            case 'agent-searching':
                handleAgentSearching(payload);
                break;
            case 'agent-tools':
                handleAgentTools(payload);
                break;
            case 'agent-reasoning':
                handleAgentReasoning(payload);
                break;
            case 'agent-done':
                handleAgentDone(payload);
                break;
            case 'assembling':
                handleAssembling(payload);
                break;
            case 'result':
                handleResult(payload);
                break;
            case 'error':
                handleError(payload || {});
                break;
            default:
                if (payload && payload.response && !coordinatorState.totalLatency) {
                    handleResult(payload);
                }
        }
    }

    function parseSseBlock(block) {
        var eventName = 'message';
        var data = [];
        block.split(/\r?\n/).forEach(function (line) {
            if (!line || line.charAt(0) === ':') return;
            if (line.indexOf('event:') === 0) {
                eventName = line.substring(6).trim();
            } else if (line.indexOf('data:') === 0) {
                data.push(line.substring(5).trim());
            }
        });
        var raw = data.join('\n');
        var payload = raw;
        if (raw) {
            try {
                payload = JSON.parse(raw);
            } catch (err) {
                payload = { raw: raw };
            }
        }
        return { event: eventName, payload: payload };
    }

    function consumeSseStream(stream, runId) {
        var reader = stream.getReader();
        var decoder = new TextDecoder();
        var buffer = '';

        function flush(finalFlush) {
            var blocks = buffer.split(/\r?\n\r?\n/);
            buffer = blocks.pop();
            blocks.forEach(function (block) {
                if (!block.trim()) return;
                var parsed = parseSseBlock(block);
                handleEvent(parsed.event, parsed.payload, runId);
            });
            if (finalFlush && buffer.trim()) {
                var last = parseSseBlock(buffer);
                handleEvent(last.event, last.payload, runId);
                buffer = '';
            }
        }

        function pump() {
            return reader.read().then(function (result) {
                if (result.done) {
                    flush(true);
                    return;
                }
                buffer += decoder.decode(result.value, { stream: true });
                flush(false);
                return pump();
            });
        }

        return pump();
    }

    function updateButtons(isRunning) {
        if (sendBtn) {
            sendBtn.disabled = isRunning;
            sendBtn.textContent = isRunning ? 'Coordinating…' : 'Coordinate agents';
        }
        if (queryInput) queryInput.disabled = isRunning;
        if (exampleSelect) exampleSelect.disabled = isRunning;
    }

    function loadExample() {
        if (!exampleSelect || !queryInput) return;
        var value = exampleSelect.value || '';
        if (!value) return;
        queryInput.value = value;
        exampleSelect.value = EXAMPLE_PLACEHOLDER;
        queryInput.focus();
    }

    function loadMode() {
        setModeBadge('checking');
    }

    function runQuery(prompt) {
        var query = (prompt || (queryInput && queryInput.value) || '').trim();
        if (!query) {
            if (queryInput) queryInput.style.borderColor = 'var(--redis-primary)';
            return;
        }
        if (queryInput) queryInput.style.borderColor = '';

        currentRunId += 1;
        var runId = currentRunId;
        if (currentStream) currentStream.abort();
        currentStream = new AbortController();
        resetView();
        setModeBadge('checking');
        coordinatorState.copy = 'Opening SSE coordination stream and waiting for the first planner event…';
        coordinatorState.badge = 'Starting';
        coordinatorState.statusClass = 'uc17-status-working';
        coordinatorState.response = 'Streaming coordination output…';
        setCoordinatorVisualState('is-active');
        renderCoordinator();
        updateButtons(true);

        fetch('/api/agents/coordinate', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                Accept: 'text/event-stream'
            },
            body: JSON.stringify({
                query: query,
                userId: userId,
                sessionId: sessionId
            }),
            signal: currentStream.signal
        }).then(function (response) {
            var contentType = response.headers.get('Content-Type') || '';
            if (!response.ok && contentType.indexOf('application/json') !== -1) {
                return response.json().then(function (body) {
                    throw new Error((body && (body.message || body.error)) || 'Coordination request failed');
                });
            }
            if (!response.ok) {
                throw new Error('Coordination request failed with HTTP ' + response.status);
            }
            if (!response.body) {
                throw new Error('Streaming response body is not available in this browser.');
            }
            return consumeSseStream(response.body, runId);
        }).catch(function (error) {
            if (error && error.name === 'AbortError') return;
            handleError({ message: error && error.message ? error.message : 'Coordination request failed.' });
        }).finally(function () {
            if (runId !== currentRunId) return;
            updateButtons(false);
            if (queryInput) queryInput.focus();
        });
    }

    function resetDemo() {
        if (currentStream) currentStream.abort();
        currentRunId += 1;
        resetBtn.disabled = true;
        resetBtn.textContent = 'Resetting…';
        fetch('/api/agents/reset', {
            method: 'POST',
            headers: { Accept: 'application/json' }
        }).then(function (response) {
            if (!response.ok) throw new Error('Reset failed with HTTP ' + response.status);
            sessionId = 'coord-' + Math.random().toString(36).substring(2, 10);
            if (sessionValue) sessionValue.textContent = sessionId;
            if (queryInput) queryInput.value = '';
            if (exampleSelect) exampleSelect.value = '';
            resetView();
            setModeBadge('checking');
        }).catch(function (error) {
            resetView();
            handleError({ message: (error && error.message) || 'Could not reset /api/agents/reset. Verify the backend is available.' });
        }).finally(function () {
            resetBtn.disabled = false;
            resetBtn.textContent = 'Reset demo';
            if (queryInput) queryInput.focus();
        });
    }

    if (exampleSelect) exampleSelect.addEventListener('change', loadExample);
    if (sendBtn) sendBtn.addEventListener('click', function () { runQuery(); });
    if (resetBtn) resetBtn.addEventListener('click', resetDemo);
    if (fullscreenToggleBtn) fullscreenToggleBtn.addEventListener('click', function () { toggleFullscreen(); });
    if (fullscreenThemeBtn) fullscreenThemeBtn.addEventListener('click', toggleFullscreenTheme);
    if (fullscreenExitBtn) fullscreenExitBtn.addEventListener('click', function () { toggleFullscreen(false); });
    document.addEventListener('keydown', handleFullscreenShortcut);
    if (fullscreenThemeBtn && window.MutationObserver) {
        new MutationObserver(updateFullscreenThemeToggle).observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['data-theme']
        });
    }
    if (queryInput) {
        queryInput.addEventListener('keydown', function (event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                runQuery();
            }
        });
    }

    updateFullscreenThemeToggle();
    setFullscreen(document.body.classList.contains('uc17-fullscreen'));
    resetView();
    loadMode();
})();