/**
 * UC9: AI Agent Memory + RAG
 * Chat interface with short-term, long-term memory and RAG inspection.
 * Supports SSE streaming (when OpenAI is configured) and mock fallback.
 */
(function () {
    'use strict';

    // --- State ---
    var sessionId = 'sess-' + Math.random().toString(36).substring(2, 10);
    var userName = 'Demo User';
    var openaiConfigured = false; // set on load via /api/assistant/status

    // --- DOM refs ---
    var chatMessages = document.getElementById('chat-messages');
    var chatInput = document.getElementById('chatInput');
    var sendBtn = document.getElementById('sendBtn');
    var shortTermInfo = document.getElementById('short-term-info');
    var memoryResults = document.getElementById('memory-results');
    var ragResults = document.getElementById('rag-results');
    var latencyDisplay = document.getElementById('latency-display');
    var resetBtn = document.getElementById('resetBtn');
    var apiStatusText = document.getElementById('api-status-text');
    var streamingIndicator = document.getElementById('streaming-indicator');
    var cacheStatusText = document.getElementById('cache-status-text');
    var cacheIndicator = document.getElementById('cache-indicator');
    var cacheBadge = document.getElementById('cache-badge');
    var cacheLatencyEl = document.getElementById('cache-latency');
    var cacheHitsEl = document.getElementById('cache-hits');
    var cacheMissesEl = document.getElementById('cache-misses');
    var cacheEntriesEl = document.getElementById('cache-entries');
    var cacheHitRateEl = document.getElementById('cache-hit-rate');
    var cacheTokensUsedEl = document.getElementById('cache-tokens-used');
    var cacheTokensSavedEl = document.getElementById('cache-tokens-saved');
    var cacheCostSavedEl = document.getElementById('cache-cost-saved');

    // --- Code Tabs ---
    window.initCodeTabs();

    // --- Helpers ---
    function escapeHtml(text) {
        var div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    function formatMarkdown(text) {
        // Simple markdown: **bold**, *italic*, bullet points, newlines
        var html = escapeHtml(text);
        html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');
        html = html.replace(/^• (.+)$/gm, '<span style="display:block; padding-left:16px;">• $1</span>');
        html = html.replace(/\n/g, '<br/>');
        return html;
    }

    function addMessage(role, content) {
        // Remove welcome message
        var welcome = chatMessages.querySelector('.chat-welcome');
        if (welcome) welcome.remove();

        var msgDiv = document.createElement('div');
        msgDiv.style.cssText = 'margin-bottom:12px; padding:10px 14px; border-radius:var(--border-radius); max-width:90%; font-size:0.85rem; line-height:1.5;';

        if (role === 'user') {
            msgDiv.style.cssText += 'background:var(--redis-primary); color:#fff; margin-left:auto; text-align:right;';
            msgDiv.innerHTML = escapeHtml(content);
        } else {
            msgDiv.style.cssText += 'background:var(--bg-tertiary); color:var(--text-primary);';
            msgDiv.innerHTML = formatMarkdown(content);
        }

        chatMessages.appendChild(msgDiv);
        chatMessages.scrollTop = chatMessages.scrollHeight;
    }

    function addTypingIndicator() {
        var ind = document.createElement('div');
        ind.id = 'typing-indicator';
        ind.style.cssText = 'margin-bottom:12px; padding:10px 14px; border-radius:var(--border-radius); background:var(--bg-tertiary); color:var(--text-muted); font-size:0.85rem; font-style:italic;';
        ind.textContent = 'AI is thinking...';
        chatMessages.appendChild(ind);
        chatMessages.scrollTop = chatMessages.scrollHeight;
    }

    function removeTypingIndicator() {
        var ind = document.getElementById('typing-indicator');
        if (ind) ind.remove();
    }

    // --- Update Memory Inspection Panel ---
    function updateShortTermMemory() {
        window.workshopGet('/api/assistant/conversation/' + sessionId).then(function (data) {
            if (!data.exists) {
                shortTermInfo.innerHTML = '<span style="color:var(--text-muted);">No active conversation yet.</span>';
                return;
            }
            var msgs = data.messages || [];
            var html = '';
            html += '<div class="data-row"><span class="data-label">Redis Key</span><span class="data-value" style="font-family:var(--font-code); font-size:0.7rem;">' + escapeHtml(data.redisKey) + '</span></div>';
            html += '<div class="data-row"><span class="data-label">Messages</span><span class="data-value">' + msgs.length + '</span></div>';
            html += '<div class="data-row"><span class="data-label">TTL</span><span class="data-value" style="color:var(--redis-primary); font-weight:600;">' + data.ttl + 's</span></div>';
            html += '<div class="data-row"><span class="data-label">User</span><span class="data-value">' + escapeHtml(data.userName || '') + '</span></div>';
            html += '<div class="data-row"><span class="data-label">Last Active</span><span class="data-value" style="font-size:0.75rem;">' + (data.lastActive || '—') + '</span></div>';
            shortTermInfo.innerHTML = html;
        });
    }

    function scoreClass(score) {
        var s = parseFloat(score);
        if (isNaN(s)) return 'uc9-score-low';
        if (s >= 0.8) return 'uc9-score-high';
        if (s >= 0.5) return 'uc9-score-med';
        return 'uc9-score-low';
    }

    function scoreBadge(score) {
        if (score == null || score === '') return '';
        return '<span class="uc9-source-score ' + scoreClass(score) + '">Score: ' + parseFloat(score).toFixed(2) + '</span>';
    }

    function updateMemoryResults(memories) {
        if (!memories || memories.length === 0) {
            memoryResults.innerHTML = '<span>No memories retrieved for this query.</span>';
            return;
        }
        var html = '';
        memories.forEach(function (mem) {
            html += '<div class="uc9-source-item">';
            if (mem.redisKey) html += '<span class="uc9-source-key">' + escapeHtml(mem.redisKey) + '</span>';
            html += '<span class="uc9-source-title">' + escapeHtml(mem.summary || mem.id || '') + '</span>';
            html += scoreBadge(mem.score);
            var meta = [];
            if (mem.date) meta.push(escapeHtml(mem.date));
            if (mem.tags) meta.push('Tags: ' + escapeHtml(mem.tags));
            if (meta.length) html += '<div class="uc9-source-meta">' + meta.join(' — ') + '</div>';
            html += '</div>';
        });
        memoryResults.innerHTML = html;
    }

    function updateRagResults(docs) {
        if (!docs || docs.length === 0) {
            ragResults.innerHTML = '<span>No documents retrieved for this query.</span>';
            return;
        }
        var html = '';
        docs.forEach(function (doc) {
            html += '<div class="uc9-source-item">';
            if (doc.redisKey) html += '<span class="uc9-source-key">' + escapeHtml(doc.redisKey) + '</span>';
            html += '<span class="uc9-source-title">' + escapeHtml(doc.title || doc.id || '') + '</span>';
            html += scoreBadge(doc.score);
            if (doc.tags) html += '<div class="uc9-source-meta">Tags: ' + escapeHtml(doc.tags) + '</div>';
            html += '</div>';
        });
        ragResults.innerHTML = html;
    }

    // --- Cache display ---
    function showCacheBadge(isHit, latencyMs, tokensSaved) {
        if (!cacheIndicator || !cacheBadge) return;
        cacheIndicator.style.display = '';
        if (isHit) {
            cacheBadge.textContent = 'CACHE HIT';
            cacheBadge.style.background = '#059669';
            cacheBadge.style.color = '#fff';
        } else {
            cacheBadge.textContent = 'CACHE MISS';
            cacheBadge.style.background = 'var(--bg-tertiary)';
            cacheBadge.style.color = 'var(--text-muted)';
        }
        if (cacheLatencyEl && latencyMs !== undefined) {
            if (isHit && tokensSaved) {
                cacheLatencyEl.textContent = latencyMs + 'ms | ~' + tokensSaved + ' tokens saved';
            } else {
                cacheLatencyEl.textContent = latencyMs + 'ms';
            }
        }
    }

    function updateCacheStats() {
        window.workshopGet('/api/assistant/cache/stats').then(function (data) {
            if (!data) return;
            if (cacheHitsEl) cacheHitsEl.textContent = data.hits || 0;
            if (cacheMissesEl) cacheMissesEl.textContent = data.misses || 0;
            if (cacheEntriesEl) cacheEntriesEl.textContent = data.cachedEntries || 0;
            if (cacheHitRateEl) cacheHitRateEl.textContent = data.hitRate || 'N/A';
            if (cacheTokensUsedEl) cacheTokensUsedEl.textContent = (data.tokensUsed || 0).toLocaleString();
            if (cacheTokensSavedEl) cacheTokensSavedEl.textContent = (data.tokensSaved || 0).toLocaleString();
            if (cacheCostSavedEl) cacheCostSavedEl.textContent = data.estimatedCostSavedUsd || '$0.0000';
            if (cacheStatusText) {
                cacheStatusText.textContent = data.enabled
                    ? 'Semantic cache active (threshold: ' + data.distanceThreshold + ')'
                    : 'Disabled (no OpenAI key)';
                cacheStatusText.style.color = data.enabled ? '#059669' : '#d97706';
            }
        }).catch(function () {});
    }

    // --- Input control ---
    function setInputEnabled(enabled) {
        chatInput.disabled = !enabled;
        sendBtn.disabled = !enabled;
        sendBtn.textContent = enabled ? 'Send' : '...';
        if (enabled) chatInput.focus();
    }

    function showStreamingIndicator() {
        if (streamingIndicator) streamingIndicator.style.display = '';
    }

    function hideStreamingIndicator() {
        if (streamingIndicator) streamingIndicator.style.display = 'none';
    }

    // --- SSE Streaming send ---
    function sendMessageStream(message) {
        addMessage('user', message);

        // Create empty assistant bubble for streaming
        var welcome = chatMessages.querySelector('.chat-welcome');
        if (welcome) welcome.remove();

        var msgDiv = document.createElement('div');
        msgDiv.style.cssText = 'margin-bottom:12px; padding:10px 14px; border-radius:var(--border-radius); max-width:90%; font-size:0.85rem; line-height:1.5; background:var(--bg-tertiary); color:var(--text-primary);';
        chatMessages.appendChild(msgDiv);

        setInputEnabled(false);
        showStreamingIndicator();
        addTypingIndicator();

        var url = '/api/assistant/chat/stream?sessionId=' + encodeURIComponent(sessionId)
            + '&userName=' + encodeURIComponent(userName)
            + '&message=' + encodeURIComponent(message);

        var eventSource = new EventSource(url);
        var fullResponse = '';

        eventSource.addEventListener('sources', function (e) {
            removeTypingIndicator();
            try {
                var sources = JSON.parse(e.data);

                // Show cache badge from sources event
                if (sources.semanticCacheHit !== undefined) {
                    showCacheBadge(sources.semanticCacheHit);
                }

                // Update unified Redis Context panel
                updateMemoryResults(sources.memories);
                updateRagResults(sources.kbDocs);
                updateShortTermMemory();
            } catch (err) { console.error('UC9 sources handler error:', err); }
        });

        eventSource.addEventListener('token', function (e) {
            removeTypingIndicator();
            try {
                var data = JSON.parse(e.data);
                fullResponse += data.content;
                msgDiv.innerHTML = formatMarkdown(fullResponse);
                chatMessages.scrollTop = chatMessages.scrollHeight;
            } catch (err) { /* ignore parse errors */ }
        });

        eventSource.addEventListener('done', function (e) {
            eventSource.close();
            hideStreamingIndicator();
            setInputEnabled(true);
            updateShortTermMemory();
            updateCacheStats();
            try {
                var meta = JSON.parse(e.data);
                if (meta.latencyMs) {
                    latencyDisplay.textContent = 'Total latency: ' + meta.latencyMs + 'ms';
                }
                if (meta.semanticCacheHit !== undefined) {
                    showCacheBadge(meta.semanticCacheHit, meta.latencyMs, meta.tokensSaved);
                }
            } catch (err) { /* ignore */ }
        });

        eventSource.onerror = function () {
            eventSource.close();
            removeTypingIndicator();
            hideStreamingIndicator();
            if (!fullResponse) {
                msgDiv.innerHTML = '<span style="color:var(--redis-primary);">Error connecting to AI service. Check your OpenAI API key or try again.</span>';
            }
            // Update short-term memory even on error (conversation may have been saved server-side)
            updateShortTermMemory();
            setInputEnabled(true);
        };
    }

    // --- Mock send (existing behavior) ---
    function sendMessageMock(message) {
        addMessage('user', message);
        addTypingIndicator();
        setInputEnabled(false);

        window.workshopFetch('/api/assistant/chat', {
            sessionId: sessionId,
            userName: userName,
            message: message
        }).then(function (data) {
            removeTypingIndicator();
            setInputEnabled(true);

            if (data.error) {
                addMessage('assistant', 'Sorry, something went wrong: ' + data.error);
                return;
            }

            addMessage('assistant', data.response);

            // Update unified Redis Context panel
            updateShortTermMemory();
            updateMemoryResults(data.memoriesRetrieved);
            updateRagResults(data.kbDocsRetrieved);

            // Semantic cache indicator
            if (data.semanticCacheEnabled) {
                showCacheBadge(data.semanticCacheHit, data.cacheLatencyMs, data.tokensSaved);
            }
            updateCacheStats();

            if (data.latencyMs !== undefined) {
                latencyDisplay.textContent = 'Total latency: ' + data.latencyMs + 'ms';
            }
        }).catch(function (err) {
            console.error('UC9 chat error:', err);
            removeTypingIndicator();
            setInputEnabled(true);
            addMessage('assistant', 'Sorry, I encountered an error. Please try again.');
        });
    }

    // --- Send Message (router) ---
    function sendMessage() {
        var message = chatInput.value.trim();
        if (!message) return;
        chatInput.value = '';

        if (openaiConfigured) {
            sendMessageStream(message);
        } else {
            sendMessageMock(message);
        }
    }

    // --- Event Listeners ---
    sendBtn.addEventListener('click', sendMessage);
    chatInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') sendMessage();
    });

    resetBtn.addEventListener('click', function () {
        resetBtn.disabled = true;
        resetBtn.textContent = 'Resetting...';
        window.workshopFetch('/api/assistant/reset', {}).then(function () {
            sessionId = 'sess-' + Math.random().toString(36).substring(2, 10);
            chatMessages.innerHTML = '<div class="chat-welcome" style="color:var(--text-muted); font-style:italic; padding:16px 0; text-align:center;">Ask about banking services — accounts, transfers, investments, loans, cards, or regulations. Try the example prompts below!</div>';
            shortTermInfo.innerHTML = 'No active conversation yet.';
            memoryResults.innerHTML = 'No memories retrieved yet.';
            ragResults.innerHTML = 'No documents retrieved yet.';
            if (cacheIndicator) cacheIndicator.style.display = 'none';
            latencyDisplay.textContent = '';
            resetBtn.disabled = false;
            resetBtn.textContent = 'Reset Demo';
            updateCacheStats();
        });
    });

    // --- Example prompt buttons ---
    document.querySelectorAll('.uc9-prompt').forEach(function (btn) {
        btn.addEventListener('click', function () {
            chatInput.value = btn.getAttribute('data-prompt');
            sendMessage();
        });
    });

    // --- Check API status on load ---
    window.workshopGet('/api/assistant/status').then(function (data) {
        openaiConfigured = !!(data && data.openaiConfigured);
        if (openaiConfigured) {
            apiStatusText.innerHTML = 'OpenAI Connected';
            apiStatusText.style.color = '#059669';
        } else {
            apiStatusText.innerHTML = 'Mock Mode <span style="font-weight:400;">(set OPENAI_API_KEY for real AI)</span>';
            apiStatusText.style.color = '#d97706';
        }
    }).catch(function () {
        openaiConfigured = false;
        apiStatusText.innerHTML = 'Mock Mode';
        apiStatusText.style.color = '#d97706';
    });

    // Fetch initial cache stats
    updateCacheStats();

    // Focus input on load
    chatInput.focus();
})();
