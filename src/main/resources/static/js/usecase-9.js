/**
 * UC9: AI Agent Memory + RAG
 * Chat interface with short-term, long-term memory and RAG inspection
 */
(function () {
    'use strict';

    // --- State ---
    var sessionId = 'sess-' + Math.random().toString(36).substring(2, 10);
    var userName = 'Demo User';

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

    // --- Send Message ---
    function sendMessage() {
        var message = chatInput.value.trim();
        if (!message) return;

        chatInput.value = '';
        sendBtn.disabled = true;
        sendBtn.textContent = '...';

        addMessage('user', message);
        addTypingIndicator();

        window.workshopFetch('/api/assistant/chat', {
            sessionId: sessionId,
            userName: userName,
            message: message
        }).then(function (data) {
            removeTypingIndicator();
            sendBtn.disabled = false;
            sendBtn.textContent = 'Send';

            if (data.error) {
                addMessage('assistant', 'Sorry, something went wrong: ' + data.error);
                return;
            }

            addMessage('assistant', data.response);

            // Update inspection panels
            updateShortTermMemory();
            updateMemoryResults(data.memoriesRetrieved);
            updateRagResults(data.kbDocsRetrieved);

            // Show Redis commands
            if (data.redisCommands) {
                commandsCard.style.display = '';
                commandsOutput.textContent = data.redisCommands.join('\n');
            }
            if (data.latencyMs !== undefined) {
                latencyDisplay.textContent = '⏱ Total latency: ' + data.latencyMs + 'ms';
            }
        }).catch(function () {
            removeTypingIndicator();
            sendBtn.disabled = false;
            sendBtn.textContent = 'Send';
            addMessage('assistant', 'Sorry, I encountered an error. Please try again.');
        });
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
            chatMessages.innerHTML = '<div class="chat-welcome" style="color:var(--text-muted); font-style:italic; padding:16px 0; text-align:center;">Type a message below to start chatting. Try asking about transfers, accounts, or investments.</div>';
            shortTermInfo.innerHTML = '<span style="color:var(--text-muted);">No active conversation yet.</span>';
            memoryResults.innerHTML = '<span style="color:var(--text-muted);">No memories retrieved yet.</span>';
            ragResults.innerHTML = '<span style="color:var(--text-muted);">No documents retrieved yet.</span>';
            commandsCard.style.display = 'none';
            resetBtn.disabled = false;
            resetBtn.textContent = '🔄 Reset Demo';
        });
    });

    // Focus input on load
    chatInput.focus();
})();
