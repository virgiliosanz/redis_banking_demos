/**
 * Redis Banking Workshop — Shared JS
 * Dark mode toggle + utility functions
 */
(function () {
    'use strict';

    // --- Dark Mode Toggle ---
    const THEME_KEY = 'redis-workshop-theme';

    function getPreferredTheme() {
        const stored = localStorage.getItem(THEME_KEY);
        if (stored) return stored;
        return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }

    function applyTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem(THEME_KEY, theme);
    }

    // Apply stored/preferred theme immediately
    applyTheme(getPreferredTheme());

    document.addEventListener('DOMContentLoaded', function () {
        const toggle = document.getElementById('themeToggle');
        if (toggle) {
            toggle.addEventListener('click', function () {
                const current = document.documentElement.getAttribute('data-theme');
                applyTheme(current === 'dark' ? 'light' : 'dark');
            });
        }

        // Set active option in nav select based on current path
        var navSelect = document.getElementById('useCaseSelect');
        if (navSelect) {
            var path = window.location.pathname;
            var matched = false;
            for (var i = 0; i < navSelect.options.length; i++) {
                if (navSelect.options[i].value === path) {
                    navSelect.selectedIndex = i;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                navSelect.selectedIndex = 0;
            }
        }

        initPresentationMode();
        initResetAll();
    });

    // --- Reset All (navbar) ---
    function initResetAll() {
        var btn = document.getElementById('resetAllBtn');
        if (!btn) return;
        var label = btn.querySelector('.reset-label');
        var originalLabel = label ? label.textContent : 'Reset All';

        btn.addEventListener('click', function () {
            if (btn.disabled) return;
            if (!window.confirm('This will reset all demo data. Continue?')) return;

            btn.disabled = true;
            btn.classList.add('is-loading');
            if (label) label.textContent = 'Resetting…';

            fetch('/api/reset-all', {
                method: 'POST',
                headers: { 'Accept': 'application/json' }
            })
                .then(function (res) {
                    return res.json().then(function (body) { return { ok: res.ok, body: body }; });
                })
                .then(function (result) {
                    var totalMs = (result.body && result.body.totalMs) || 0;
                    if (result.ok) {
                        if (label) label.textContent = 'Reset ✓ ' + totalMs + 'ms';
                    } else {
                        if (label) label.textContent = 'Reset failed';
                        console.error('Reset-all partial/failed', result.body);
                    }
                })
                .catch(function (err) {
                    if (label) label.textContent = 'Reset failed';
                    console.error('Reset-all error', err);
                })
                .finally(function () {
                    btn.classList.remove('is-loading');
                    setTimeout(function () {
                        btn.disabled = false;
                        if (label) label.textContent = originalLabel;
                    }, 2500);
                });
        });
    }

    // --- Presenter Mode ---
    function initPresentationMode() {
        var OPEN_KEY = 'redis-workshop-presenter-open';
        var DEFAULT_TARGET_MINUTES = 3;
        var toggle = document.getElementById('presenterModeToggle');
        var panel = document.getElementById('presenterPanel');
        var backdrop = document.getElementById('presenterBackdrop');
        var closeBtn = document.getElementById('presenterClose');
        var timerBtn = document.getElementById('presenterTimer');
        var timerMeta = document.getElementById('presenterTimerMeta');
        var contextTitle = document.getElementById('presenterContextTitle');
        var prevBtn = document.getElementById('presenterPrevBtn');
        var nextBtn = document.getElementById('presenterNextBtn');
        var overviewCard = document.getElementById('presenterOverviewCard');
        var whatSection = document.getElementById('presenterWhatSection');
        var whatContent = document.getElementById('presenterWhatContent');
        var stepsSection = document.getElementById('presenterStepsSection');
        var stepsList = document.getElementById('presenterStepsList');
        var stepCount = document.getElementById('presenterStepCount');
        var pointsSection = document.getElementById('presenterPointsSection');
        var pointsContent = document.getElementById('presenterPointsContent');
        var redisSection = document.getElementById('presenterRedisSection');
        var redisContent = document.getElementById('presenterRedisContent');

        if (!toggle || !panel || !backdrop) return;

        var presenterState = {
            open: false,
            startedAt: 0,
            targetSeconds: DEFAULT_TARGET_MINUTES * 60,
            timerId: null,
            completedStepsByPath: {}
        };

        function isEditableTarget(target) {
            if (!target) return false;
            var tagName = (target.tagName || '').toLowerCase();
            return target.isContentEditable || tagName === 'input' || tagName === 'textarea' || tagName === 'select';
        }

        function formatSeconds(totalSeconds) {
            var safe = Math.max(0, totalSeconds || 0);
            var minutes = Math.floor(safe / 60);
            var seconds = safe % 60;
            return String(minutes).padStart(2, '0') + ':' + String(seconds).padStart(2, '0');
        }

        function getCurrentUseCaseId() {
            var match = window.location.pathname.match(/^\/usecase\/(\d+)$/);
            return match ? parseInt(match[1], 10) : null;
        }

        function getUseCaseIds() {
            var ids = [];
            var select = document.getElementById('useCaseSelect');
            if (!select) return ids;
            Array.prototype.forEach.call(select.options, function (option) {
                var match = String(option.value || '').match(/^\/usecase\/(\d+)$/);
                if (match) ids.push(parseInt(match[1], 10));
            });
            return ids;
        }

        function getCurrentPathKey() {
            return window.location.pathname || 'index';
        }

        function getPresenterSection(root, sectionName) {
            return root ? root.querySelector('[data-presenter-section="' + sectionName + '"]') : null;
        }

        function buildIndexOverview() {
            var select = document.getElementById('useCaseSelect');
            var totalCount = 0;
            var totalMinutes = 0;

            if (select) {
                Array.prototype.forEach.call(select.options, function (option) {
                    if (!String(option.value || '').match(/^\/usecase\/(\d+)$/)) return;
                    totalCount += 1;
                    totalMinutes += parseInt(option.getAttribute('data-presenter-minutes'), 10) || DEFAULT_TARGET_MINUTES;
                });
            }

            return {
                targetMinutes: totalMinutes || DEFAULT_TARGET_MINUTES,
                overviewHtml: '<div class="presenter-overview-stats">'
                    + '<div class="presenter-overview-stat"><span class="presenter-overview-label">Use cases</span><strong>' + totalCount + '</strong></div>'
                    + '<div class="presenter-overview-stat"><span class="presenter-overview-label">Estimated time</span><strong>~' + totalMinutes + ' min</strong></div>'
                    + '</div>'
                    + '<p class="presenter-overview-copy">Open with the landing page to frame the workshop, then move left-to-right from core Redis patterns into AI use cases.</p>',
                whatHtml: '<p>This page is the opening map for the workshop: explain that the demos progress from state, caching, and search patterns into guarded AI flows and multi-agent coordination.</p>',
                steps: [
                    'Introduce the workshop structure and point out the grouped categories.',
                    'Set expectations: every use case has a live demo and a curated code panel.',
                    'Open UC1 to begin the story with simple state and TTL patterns.'
                ],
                pointsHtml: '<ul><li>Everything is demo-first and designed for live explanation.</li><li>Use the panel timer to keep each use case short and comparable.</li><li>The last block of demos shows Redis supporting AI memory, guardrails, gateways, and orchestration.</li></ul>',
                highlightHtml: '<p><code>' + totalCount + ' use cases</code> with an estimated <code>~' + totalMinutes + ' min</code> total walkthrough.</p>'
            };
        }

        function extractPresenterData() {
            var noteRoot = document.querySelector('.presenter-notes');
            var useCaseBadge = document.querySelector('.usecase-badge');
            var titleEl = document.querySelector('.usecase-header h1');
            var currentId = getCurrentUseCaseId();
            var data = {
                title: titleEl ? titleEl.textContent.trim() : 'Workshop overview',
                badge: useCaseBadge ? useCaseBadge.textContent.trim() : '',
                targetMinutes: DEFAULT_TARGET_MINUTES,
                overviewHtml: '',
                whatHtml: '',
                steps: [],
                pointsHtml: '',
                highlightHtml: ''
            };

            if (noteRoot) {
                data.targetMinutes = parseInt(noteRoot.getAttribute('data-target-minutes'), 10) || DEFAULT_TARGET_MINUTES;
                var what = getPresenterSection(noteRoot, 'what-to-say');
                var demoSteps = getPresenterSection(noteRoot, 'demo-steps');
                var talkingPoints = getPresenterSection(noteRoot, 'key-talking-points');
                var redisHighlight = getPresenterSection(noteRoot, 'redis-highlight');

                data.whatHtml = what ? what.innerHTML : '';
                data.pointsHtml = talkingPoints ? talkingPoints.innerHTML : '';
                data.highlightHtml = redisHighlight ? redisHighlight.innerHTML : '';
                if (demoSteps) {
                    Array.prototype.forEach.call(demoSteps.querySelectorAll('li'), function (item) {
                        data.steps.push(item.innerHTML);
                    });
                }
                return data;
            }

            if (window.location.pathname === '/' || document.querySelector('.use-case-grid')) {
                var overview = buildIndexOverview();
                data.title = 'Workshop overview';
                data.badge = 'INDEX';
                data.targetMinutes = overview.targetMinutes;
                data.overviewHtml = overview.overviewHtml;
                data.whatHtml = overview.whatHtml;
                data.steps = overview.steps;
                data.pointsHtml = overview.pointsHtml;
                data.highlightHtml = overview.highlightHtml;
                return data;
            }

            data.title = currentId ? 'UC' + currentId : 'Presenter notes unavailable';
            data.whatHtml = '<p>Presenter notes are available on the landing page and each use case page.</p>';
            data.pointsHtml = '<ul><li>Use <kbd>P</kbd> to reopen the panel after navigation.</li></ul>';
            data.highlightHtml = '<p><code>sessionStorage</code> keeps the panel open state between pages.</p>';
            return data;
        }

        function updateStepCountDisplay(stepValues) {
            var done = 0;
            for (var i = 0; i < stepValues.length; i++) {
                if (stepValues[i]) done += 1;
            }
            stepCount.textContent = done + ' / ' + stepValues.length;
        }

        function renderSteps(stepHtmlList) {
            stepsList.innerHTML = '';
            if (!stepHtmlList.length) {
                stepsSection.hidden = true;
                return;
            }

            stepsSection.hidden = false;
            var pathKey = getCurrentPathKey();
            if (!presenterState.completedStepsByPath[pathKey] || presenterState.completedStepsByPath[pathKey].length !== stepHtmlList.length) {
                presenterState.completedStepsByPath[pathKey] = stepHtmlList.map(function () { return false; });
            }

            var stepValues = presenterState.completedStepsByPath[pathKey];
            stepHtmlList.forEach(function (stepHtml, index) {
                var item = document.createElement('li');
                var button = document.createElement('button');
                button.type = 'button';
                button.className = 'presenter-step';
                button.setAttribute('role', 'checkbox');
                button.setAttribute('aria-checked', stepValues[index] ? 'true' : 'false');
                if (stepValues[index]) button.classList.add('is-done');
                button.innerHTML = '<span class="presenter-step-marker">' + (index + 1) + '</span>'
                    + '<span class="presenter-step-body">' + stepHtml + '</span>';
                button.addEventListener('click', function () {
                    stepValues[index] = !stepValues[index];
                    button.classList.toggle('is-done', stepValues[index]);
                    button.setAttribute('aria-checked', stepValues[index] ? 'true' : 'false');
                    updateStepCountDisplay(stepValues);
                });
                item.appendChild(button);
                stepsList.appendChild(item);
            });
            updateStepCountDisplay(stepValues);
        }

        function syncNavButtons() {
            var ids = getUseCaseIds();
            var currentId = getCurrentUseCaseId();
            var index = ids.indexOf(currentId);
            var hasPrev = index > 0;
            var hasNext = index !== -1 && index < ids.length - 1;

            prevBtn.disabled = !hasPrev;
            nextBtn.disabled = !hasNext;
            prevBtn.setAttribute('aria-disabled', hasPrev ? 'false' : 'true');
            nextBtn.setAttribute('aria-disabled', hasNext ? 'false' : 'true');
        }

        function renderPresenterContent() {
            var data = extractPresenterData();
            presenterState.targetSeconds = Math.max(1, data.targetMinutes) * 60;
            contextTitle.textContent = data.badge && data.badge !== 'INDEX'
                ? data.badge + ' · ' + data.title
                : data.title;

            overviewCard.hidden = !data.overviewHtml;
            overviewCard.innerHTML = data.overviewHtml;

            whatSection.hidden = !data.whatHtml;
            whatContent.innerHTML = data.whatHtml;

            pointsSection.hidden = !data.pointsHtml;
            pointsContent.innerHTML = data.pointsHtml;

            redisSection.hidden = !data.highlightHtml;
            redisContent.innerHTML = data.highlightHtml;

            renderSteps(data.steps);
            syncNavButtons();
        }

        function stopTimer() {
            if (presenterState.timerId) {
                window.clearInterval(presenterState.timerId);
                presenterState.timerId = null;
            }
        }

        function updateTimer() {
            var elapsed = Math.floor((Date.now() - presenterState.startedAt) / 1000);
            var valueEl = timerBtn.querySelector('.presenter-timer-label');
            valueEl.textContent = formatSeconds(elapsed);
            timerMeta.textContent = 'Target ' + formatSeconds(presenterState.targetSeconds) + ' · click to reset';
            timerBtn.classList.toggle('is-over', elapsed >= presenterState.targetSeconds);
        }

        function startTimer() {
            stopTimer();
            presenterState.startedAt = Date.now();
            updateTimer();
            presenterState.timerId = window.setInterval(updateTimer, 1000);
        }

        function setPresenterOpen(enabled) {
            presenterState.open = !!enabled;
            document.body.classList.toggle('presenter-open', presenterState.open);
            panel.setAttribute('aria-hidden', presenterState.open ? 'false' : 'true');
            backdrop.setAttribute('aria-hidden', presenterState.open ? 'false' : 'true');
            toggle.setAttribute('aria-pressed', presenterState.open ? 'true' : 'false');
            toggle.setAttribute('title', presenterState.open ? 'Close presenter mode (P)' : 'Open presenter mode (P)');
            sessionStorage.setItem(OPEN_KEY, presenterState.open ? 'true' : 'false');

            if (presenterState.open) {
                renderPresenterContent();
                startTimer();
                panel.scrollTop = 0;
            } else {
                stopTimer();
            }
        }

        function navigateRelative(delta) {
            var ids = getUseCaseIds();
            var currentId = getCurrentUseCaseId();
            var index = ids.indexOf(currentId);
            if (index === -1) return;
            var targetId = ids[index + delta];
            if (!targetId) return;
            window.location.assign('/usecase/' + targetId);
        }

        function handlePresenterKeydown(event) {
            if (event.altKey || event.ctrlKey || event.metaKey || event.repeat) return;

            if ((event.key === 'p' || event.key === 'P') && !isEditableTarget(event.target)) {
                event.preventDefault();
                setPresenterOpen(!presenterState.open);
                return;
            }

            if (event.key === 'Escape' && presenterState.open) {
                event.preventDefault();
                setPresenterOpen(false);
                return;
            }

            if (!presenterState.open || isEditableTarget(event.target)) return;

            if (event.key === 'ArrowLeft') {
                event.preventDefault();
                navigateRelative(-1);
            } else if (event.key === 'ArrowRight') {
                event.preventDefault();
                navigateRelative(1);
            }
        }

        toggle.addEventListener('click', function () {
            setPresenterOpen(!presenterState.open);
        });
        if (closeBtn) closeBtn.addEventListener('click', function () { setPresenterOpen(false); });
        if (backdrop) backdrop.addEventListener('click', function () { setPresenterOpen(false); });
        if (timerBtn) timerBtn.addEventListener('click', function () { startTimer(); });
        if (prevBtn) prevBtn.addEventListener('click', function () { navigateRelative(-1); });
        if (nextBtn) nextBtn.addEventListener('click', function () { navigateRelative(1); });
        document.addEventListener('keydown', handlePresenterKeydown);

        renderPresenterContent();
        setPresenterOpen(sessionStorage.getItem(OPEN_KEY) === 'true');
    }

    // --- Redis latency badge ---
    // Renders a pill in the demo panel header. Green <1ms, yellow 1-10ms, red >10ms.
    window.renderRedisLatency = function (latencyMs) {
        if (latencyMs === null || latencyMs === undefined) return;
        var n = Number(latencyMs);
        if (!isFinite(n) || n < 0) return;

        var panel = document.querySelector('.usecase-panels .demo-panel');
        if (!panel) return;

        var badge = document.getElementById('redis-latency-badge');
        if (!badge) {
            badge = document.createElement('span');
            badge.id = 'redis-latency-badge';
            badge.className = 'redis-latency-badge';
            badge.title = 'Server-side Redis operation latency';
            var h2 = panel.querySelector(':scope > h2');
            if (h2) h2.appendChild(badge);
            else panel.insertBefore(badge, panel.firstChild);
        }

        var state = n < 1 ? 'ok' : (n <= 10 ? 'warn' : 'slow');
        badge.className = 'redis-latency-badge ' + state;
        var display = n < 10 ? n.toFixed(2) : n.toFixed(1);
        badge.textContent = 'Redis: ' + display + ' ms';
    };

    // Auto-tap every /api/** response: the X-Redis-Latency-Ms header is set by
    // RedisLatencyAdvice on the server, so we can render the pill for any fetch
    // (raw fetch, workshopFetch, workshopGet) without per-UC changes.
    var _origFetch = window.fetch.bind(window);
    window.fetch = function (input, init) {
        return _origFetch(input, init).then(function (res) {
            try {
                var url = typeof input === 'string' ? input : (input && input.url) || '';
                if (url.indexOf('/api/') !== -1) {
                    var header = res.headers.get('X-Redis-Latency-Ms');
                    if (header != null) window.renderRedisLatency(parseFloat(header));
                }
            } catch (e) { /* non-fatal */ }
            return res;
        });
    };

    // --- Utility: POST JSON ---
    window.workshopFetch = function (url, data) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: data ? JSON.stringify(data) : undefined
        }).then(function (res) { return res.json(); });
    };

    // --- Utility: GET JSON ---
    window.workshopGet = function (url) {
        return fetch(url).then(function (res) { return res.json(); });
    };

    // --- Utility: Format JSON for display ---
    window.formatJson = function (obj) {
        return JSON.stringify(obj, null, 2);
    };

    // --- Code Tabs: shared initializer ---
    window.initCodeTabs = function () {
        document.querySelectorAll('.code-tab').forEach(function (tab) {
            tab.addEventListener('click', function () {
                document.querySelectorAll('.code-tab').forEach(function (t) { t.classList.remove('active'); });
                document.querySelectorAll('.code-block, .code-tab-content').forEach(function (c) { c.classList.remove('active'); });
                tab.classList.add('active');
                var target = document.getElementById('tab-' + tab.getAttribute('data-tab'));
                if (target) target.classList.add('active');
                if (window.Prism) Prism.highlightAll();
            });
        });
    };

    // --- Shared: HTML escape ---
    function escapeHtml(s) {
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // --- Redis Commands Panel (SSE) ---
    // Each UC page sets window.WORKSHOP_UC = 'UCn'. We open an SSE stream
    // filtered by UC and push entries into #commands-output inside the
    // #redis-commands-card block. A one-shot REST call backfills any
    // commands that happened before the page loaded.
    var MAX_COMMANDS = 25;
    var _eventSource = null;

    function initRedisCommands() {
        var uc = window.WORKSHOP_UC;
        if (!uc) return;

        var card = document.getElementById('redis-commands-card');
        var output = document.getElementById('commands-output');
        var counter = document.getElementById('commands-counter');
        var copyAllBtn = document.getElementById('copy-all-commands-btn');
        var clearBtn = document.getElementById('clear-commands-btn');
        if (!card || !output) return;

        ensureCommandsPlaceholder(output);
        updateCommandsToolbar(output, counter, copyAllBtn, clearBtn);

        if (copyAllBtn) {
            copyAllBtn.addEventListener('click', function () {
                var commands = getVisibleCommands(output);
                if (!commands.length) return;
                copyRedisCommand(copyAllBtn, commands.join('\n'), 'Copy all commands');
            });
        }

        if (clearBtn) {
            clearBtn.addEventListener('click', function () {
                clearCommandEntries(output);
                ensureCommandsPlaceholder(output);
                updateCommandsToolbar(output, counter, copyAllBtn, clearBtn);
                card.style.display = '';
            });
        }

        // Backfill recent commands captured before page load
        fetch('/api/redis/commands?uc=' + encodeURIComponent(uc) + '&limit=10',
                { headers: { 'Accept': 'application/json' } })
            .then(function (res) { return res.ok ? res.json() : null; })
            .then(function (data) {
                if (!data) return;
                var commands = Array.isArray(data) ? data : (data.commands || []);
                if (commands.length === 0) return;

                card.style.display = '';
                removeCommandsPlaceholder(output);

                // Backend returns newest-first; insert in reverse so newest ends on top
                for (var i = commands.length - 1; i >= 0; i--) {
                    var el = createCommandEntry(commands[i]);
                    output.insertBefore(el, output.firstChild);
                }
                trimCommandEntries(output);
                updateCommandsToolbar(output, counter, copyAllBtn, clearBtn);
            })
            .catch(function () { /* silent */ });

        // Open SSE stream for real-time push
        var streamUrl = '/api/redis/commands/stream?uc=' + encodeURIComponent(uc);
        _eventSource = new EventSource(streamUrl);

        _eventSource.addEventListener('command', function (event) {
            var cmd;
            try { cmd = JSON.parse(event.data); } catch (e) { return; }

            card.style.display = '';
            removeCommandsPlaceholder(output);

            var el = createCommandEntry(cmd);
            output.insertBefore(el, output.firstChild);

            trimCommandEntries(output);
            updateCommandsToolbar(output, counter, copyAllBtn, clearBtn);

            flashCodeShowcase(cmd.command);
        });

        _eventSource.onerror = function () {
            // EventSource auto-reconnects by default
        };
    }

    window.addEventListener('beforeunload', function () {
        if (_eventSource) {
            _eventSource.close();
            _eventSource = null;
        }
    });

    function createCommandEntry(cmd) {
        var fullCmd = cmd.fullCommand || ((cmd.command || '') + (cmd.key ? ' ' + cmd.key : ''));
        var commandName = cmd.command || '';
        var keyText = cmd.key || '';

        var details = document.createElement('details');
        details.className = 'redis-cmd-entry command-entry';
        details.setAttribute('data-full-command', fullCmd);

        var summary = document.createElement('summary');
        var summaryCode = document.createElement('code');
        summaryCode.className = 'cmd-summary';
        summaryCode.innerHTML = escapeHtml(commandName)
            + (keyText ? ' <span class="cmd-key-text">' + escapeHtml(keyText) + '</span>' : '');

        var copyBtn = document.createElement('button');
        copyBtn.type = 'button';
        copyBtn.className = 'cmd-copy-btn';
        copyBtn.title = 'Copy command to clipboard';
        copyBtn.setAttribute('aria-label', 'Copy command to clipboard');
        copyBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none"'
            + ' stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
            + '<rect x="9" y="9" width="13" height="13" rx="2"/>'
            + '<path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>'
            + '</svg>';
        copyBtn.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            copyRedisCommand(copyBtn, fullCmd);
        });

        summary.appendChild(summaryCode);
        summary.appendChild(copyBtn);

        var expanded = document.createElement('div');
        expanded.className = 'cmd-expanded';
        expanded.innerHTML = '<div class="cmd-full"><span class="cmd-label">Full command</span>'
            + '<code>' + escapeHtml(fullCmd) + '</code></div>';

        details.appendChild(summary);
        details.appendChild(expanded);
        return details;
    }

    function getCommandEntries(output) {
        return output.querySelectorAll('.redis-cmd-entry.command-entry, .command-entry-simple');
    }

    function getVisibleCommands(output) {
        var entries = getCommandEntries(output);
        var commands = [];
        for (var i = 0; i < entries.length; i++) {
            var fullCommand = entries[i].getAttribute('data-full-command');
            if (fullCommand) commands.push(fullCommand);
        }
        return commands;
    }

    function removeCommandsPlaceholder(output) {
        var placeholder = output.querySelector('.command-log-empty, .commands-empty');
        if (placeholder) placeholder.remove();
    }

    function ensureCommandsPlaceholder(output) {
        if (getCommandEntries(output).length > 0) {
            removeCommandsPlaceholder(output);
            return;
        }

        if (output.querySelector('.command-log-empty, .commands-empty')) return;

        var placeholder = document.createElement('div');
        placeholder.className = 'commands-empty';
        placeholder.textContent = 'No Redis commands captured yet.';
        output.appendChild(placeholder);
    }

    function clearCommandEntries(output) {
        var entries = getCommandEntries(output);
        for (var i = 0; i < entries.length; i++) {
            entries[i].remove();
        }
    }

    function trimCommandEntries(output) {
        var entries = getCommandEntries(output);
        while (entries.length > MAX_COMMANDS) {
            entries[entries.length - 1].remove();
            entries = getCommandEntries(output);
        }
    }

    function updateCommandsToolbar(output, counter, copyAllBtn, clearBtn) {
        var count = getCommandEntries(output).length;
        if (counter) {
            counter.textContent = count + ' command' + (count === 1 ? '' : 's');
        }
        if (copyAllBtn) copyAllBtn.disabled = count === 0;
        if (clearBtn) clearBtn.disabled = count === 0;
    }

    // Briefly flash the code-showcase snippet that contains the executed command.
    // Matches the command token within the currently visible (active) code block,
    // ignoring substrings of longer commands (e.g. HGET inside HGETALL).
    function flashCodeShowcase(commandName) {
        if (!commandName) return;
        var panel = document.querySelector('.code-panel');
        if (!panel) return;

        var blocks = panel.querySelectorAll('.code-block.active, .code-tab-content.active');
        if (!blocks.length) blocks = panel.querySelectorAll('.code-block, .code-tab-content');

        var escaped = commandName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        var re = new RegExp('(^|[^A-Z0-9.])' + escaped + '(?![A-Z0-9.])');

        for (var i = 0; i < blocks.length; i++) {
            var pres = blocks[i].querySelectorAll('pre');
            for (var j = 0; j < pres.length; j++) {
                var code = pres[j].querySelector('code');
                var text = code ? code.textContent : pres[j].textContent;
                if (re.test(text)) {
                    var target = pres[j];
                    target.classList.remove('code-highlight-flash');
                    // Force reflow so animation restarts on repeated matches
                    void target.offsetWidth;
                    target.classList.add('code-highlight-flash');
                    setTimeout(function (el) {
                        return function () { el.classList.remove('code-highlight-flash'); };
                    }(target), 1700);
                    return;
                }
            }
        }
    }

    function fallbackCopyText(command) {
        try {
            var ta = document.createElement('textarea');
            ta.value = command;
            ta.setAttribute('readonly', '');
            ta.style.position = 'absolute';
            ta.style.left = '-9999px';
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
            return true;
        } catch (e) {
            return false;
        }
    }

    function copyRedisCommand(btn, command, defaultTitle) {
        var baseTitle = defaultTitle || 'Copy command to clipboard';

        function flash() {
            btn.classList.add('cmd-copied');
            btn.title = 'Copied!';
            btn.setAttribute('aria-label', 'Copied!');
            setTimeout(function () {
                btn.classList.remove('cmd-copied');
                btn.title = baseTitle;
                btn.setAttribute('aria-label', baseTitle);
            }, 1500);
        }

        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(command).then(flash).catch(function () {
                if (fallbackCopyText(command)) flash();
            });
            return;
        }

        if (fallbackCopyText(command)) flash();
    }

    document.addEventListener('DOMContentLoaded', function () {
        if (!window.WORKSHOP_UC) return;
        initRedisCommands();
        initCodeTabs();
    });
})();
