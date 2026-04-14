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
    var commandsCard = document.getElementById('commands-card');
    var commandsOutput = document.getElementById('commands-output');
    var latencyDisplay = document.getElementById('latency-display');
    var resetBtn = document.getElementById('resetBtn');
    var apiStatusText = document.getElementById('api-status-text');
    var streamingIndicator = document.getElementById('streaming-indicator');
    var sourcesPanel = document.getElementById('sourcesPanel');
    var sourcesContent = document.getElementById('sourcesContent');

    // --- Code Tabs ---
    document.querySelectorAll('.code-tab').forEach(function (tab) {
        tab.addEventListener('click', function () {
            document.querySelectorAll('.code-tab').forEach(function (t) { t.classList.remove('active'); });
            document.querySelectorAll('.code-block').forEach(function (b) { b.classList.remove('active'); });
            tab.classList.add('active');
            document.getElementById('tab-' + tab.getAttribute('data-tab')).classList.add('active');
        });
    });

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

    function updateMemoryResults(memories) {
        if (!memories || memories.length === 0) {
            memoryResults.innerHTML = '<span style="color:var(--text-muted);">No relevant memories found for this query.</span>';
            return;
        }
        var html = '';
        memories.forEach(function (mem) {
            html += '<div style="padding:6px 0; border-bottom:1px solid var(--border-color);">';
            html += '<div style="font-weight:600; font-size:0.8rem;">🧠 ' + escapeHtml(mem.summary) + '</div>';
            html += '<div style="color:var(--text-muted); font-size:0.7rem;">' + escapeHtml(mem.date) + ' — Tags: ' + escapeHtml(mem.tags) + '</div>';
            html += '</div>';
        });
        memoryResults.innerHTML = html;
    }

    function updateRagResults(docs) {
        if (!docs || docs.length === 0) {
            ragResults.innerHTML = '<span style="color:var(--text-muted);">No relevant documents found for this query.</span>';
            return;
        }
        var html = '';
        docs.forEach(function (doc) {
            html += '<div style="padding:6px 0; border-bottom:1px solid var(--border-color);">';
            html += '<div style="font-weight:600; font-size:0.8rem;">📄 ' + escapeHtml(doc.title) + '</div>';
            html += '<div style="color:var(--text-muted); font-size:0.7rem;">Tags: ' + escapeHtml(doc.tags) + '</div>';
            html += '</div>';
        });
        ragResults.innerHTML = html;
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

    // --- Sources panel ---
    function scoreClass(score) {
        if (score >= 0.8) return 'uc9-score-high';
        if (score >= 0.5) return 'uc9-score-med';
        return 'uc9-score-low';
    }

    function displaySources(sources) {
        if (!sourcesPanel || !sourcesContent) return;
        var html = '';

        // Knowledge Base Documents
        if (sources.kbDocs && sources.kbDocs.length > 0) {
            html += '<div class="uc9-sources-section"><h5>📄 Knowledge Base Documents</h5>';
            sources.kbDocs.forEach(function (doc) {
                html += '<div class="uc9-source-item">';
                html += '<span class="uc9-source-key">' + escapeHtml(doc.redisKey || '') + '</span>';
                if (doc.title) html += '<span class="uc9-source-title">' + escapeHtml(doc.title) + '</span>';
                html += '<span class="uc9-source-score ' + scoreClass(doc.score) + '">Score: ' + (doc.score != null ? doc.score.toFixed(2) : '—') + '</span>';
                html += '</div>';
            });
            html += '</div>';
        }

        // Memories
        if (sources.memories && sources.memories.length > 0) {
            html += '<div class="uc9-sources-section"><h5>🧠 Relevant Memories</h5>';
            sources.memories.forEach(function (mem) {
                html += '<div class="uc9-source-item">';
                html += '<span class="uc9-source-key">' + escapeHtml(mem.redisKey || '') + '</span>';
                if (mem.summary) html += '<span class="uc9-source-title">' + escapeHtml(mem.summary) + '</span>';
                html += '<span class="uc9-source-score ' + scoreClass(mem.score) + '">Score: ' + (mem.score != null ? mem.score.toFixed(2) : '—') + '</span>';
                html += '</div>';
            });
            html += '</div>';
        }

        // Redis Commands
        if (sources.redisCommands && sources.redisCommands.length > 0) {
            html += '<div class="uc9-sources-section"><h5>⚡ Redis Commands Used</h5>';
            sources.redisCommands.forEach(function (cmd) {
                html += '<code class="uc9-redis-cmd">' + escapeHtml(cmd) + '</code>';
            });
            html += '</div>';
        }

        if (html) {
            sourcesContent.innerHTML = html;
            sourcesPanel.style.display = '';
        }
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
                displaySources(sources);

                // Update the memory inspection panels with source data
                if (sources.memories) updateMemoryResults(sources.memories);
                if (sources.kbDocs) updateRagResults(sources.kbDocs);
                if (sources.redisCommands) {
                    commandsCard.style.display = '';
                    commandsOutput.textContent = sources.redisCommands.join('\n');
                }
                // Update short-term memory panel as soon as sources arrive
                updateShortTermMemory();
            } catch (err) { /* ignore parse errors */ }
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
            try {
                var meta = JSON.parse(e.data);
                if (meta.latencyMs) {
                    latencyDisplay.textContent = '⏱ Total latency: ' + meta.latencyMs + 'ms';
                }
            } catch (err) { /* ignore */ }
        });

        eventSource.onerror = function () {
            eventSource.close();
            removeTypingIndicator();
            hideStreamingIndicator();
            if (!fullResponse) {
                msgDiv.innerHTML = '<span style="color:var(--redis-primary);">⚠️ Error connecting to AI service. Check your OpenAI API key or try again.</span>';
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

            // Update inspection panels first (critical for demo visibility)
            updateShortTermMemory();
            updateMemoryResults(data.memoriesRetrieved);
            updateRagResults(data.kbDocsRetrieved);

            // Show inline sources summary in chat bubble (nice-to-have)
            try {
                if ((data.memoriesRetrieved && data.memoriesRetrieved.length > 0) ||
                    (data.kbDocsRetrieved && data.kbDocsRetrieved.length > 0)) {
                    var sourcesHtml = '<div style="margin-top:8px; padding:8px 12px; background:var(--bg-secondary); border-radius:var(--border-radius); border:1px solid var(--border-color); font-size:0.75rem;">';
                    sourcesHtml += '<div style="font-weight:600; color:var(--redis-primary); margin-bottom:4px;">📚 Context used (Redis Vector Search):</div>';
                    if (data.kbDocsRetrieved && data.kbDocsRetrieved.length > 0) {
                        data.kbDocsRetrieved.forEach(function(doc) {
                            sourcesHtml += '<div style="color:var(--text-muted);">📄 ' + escapeHtml(doc.title || doc.id || '') + '</div>';
                        });
                    }
                    if (data.memoriesRetrieved && data.memoriesRetrieved.length > 0) {
                        data.memoriesRetrieved.forEach(function(mem) {
                            sourcesHtml += '<div style="color:var(--text-muted);">🧠 ' + escapeHtml(mem.summary || mem.id || '') + '</div>';
                        });
                    }
                    sourcesHtml += '</div>';
                    // Append to the last assistant message bubble
                    var lastMsg = chatMessages.lastElementChild;
                    if (lastMsg) lastMsg.innerHTML += sourcesHtml;
                }
            } catch (e) {
                console.warn('Could not render inline sources:', e);
            }

            if (data.redisCommands) {
                commandsCard.style.display = '';
                commandsOutput.textContent = data.redisCommands.join('\n');
            }
            if (data.latencyMs !== undefined) {
                latencyDisplay.textContent = '⏱ Total latency: ' + data.latencyMs + 'ms';
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
            shortTermInfo.innerHTML = '<span style="color:var(--text-muted);">No active conversation yet.</span>';
            memoryResults.innerHTML = '<span style="color:var(--text-muted);">No memories retrieved yet.</span>';
            ragResults.innerHTML = '<span style="color:var(--text-muted);">No documents retrieved yet.</span>';
            commandsCard.style.display = 'none';
            if (sourcesPanel) sourcesPanel.style.display = 'none';
            latencyDisplay.textContent = '';
            resetBtn.disabled = false;
            resetBtn.textContent = '🔄 Reset Demo';
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
            apiStatusText.innerHTML = '✅ OpenAI Connected';
            apiStatusText.style.color = '#059669';
        } else {
            apiStatusText.innerHTML = '⚠️ Mock Mode <span style="font-weight:400;">(set OPENAI_API_KEY for real AI)</span>';
            apiStatusText.style.color = '#d97706';
        }
    }).catch(function () {
        openaiConfigured = false;
        apiStatusText.innerHTML = '⚠️ Mock Mode';
        apiStatusText.style.color = '#d97706';
    });

    // Focus input on load
    chatInput.focus();
})();
