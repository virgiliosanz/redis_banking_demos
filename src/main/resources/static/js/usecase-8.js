/**
 * UC8: Document Database — CRUD + Full-text + Vector + Hybrid
 * Interactive demo: CRUD operations tab + search tab
 */
(function () {
    'use strict';
    window.WORKSHOP_UC = 'UC8';

    // --- Shared helpers ---
    function escapeHtml(str) {
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(str));
        return div.innerHTML;
    }

    // --- Code Tabs ---
    window.initCodeTabs();

    // --- AI status badge (reflects OpenAI configuration) ---
    (function updateAiBadge() {
        var badge = document.getElementById('ai-badge');
        if (!badge) return;
        window.workshopGet('/api/health', 'tab-crud-btn').then(function (data) {
            var configured = !!(data && data.openai && data.openai.configured);
            badge.classList.remove('on', 'mock');
            if (configured) {
                badge.classList.add('on');
                badge.textContent = 'AI: ON';
                badge.title = 'OpenAI is configured — real embeddings in use';
            } else {
                badge.classList.add('mock');
                badge.textContent = 'AI: Mock';
                badge.title = 'No OPENAI_API_KEY — using mock embeddings';
            }
        }).catch(function () {
            badge.classList.remove('on');
            badge.classList.add('mock');
            badge.textContent = 'AI: Mock';
            window.showToast('Could not verify AI status for document search.', 'warning');
        });
    })();

    // --- Main Tab Toggle (CRUD / Search) ---
    var crudTab = document.getElementById('crud-tab');
    var searchTab = document.getElementById('search-tab');
    var tabCrudBtn = document.getElementById('tab-crud-btn');
    var tabSearchBtn = document.getElementById('tab-search-btn');

    tabCrudBtn.addEventListener('click', function () {
        crudTab.style.display = '';
        searchTab.style.display = 'none';
        tabCrudBtn.classList.add('active');
        tabSearchBtn.classList.remove('active');
    });
    tabSearchBtn.addEventListener('click', function () {
        crudTab.style.display = 'none';
        searchTab.style.display = '';
        tabSearchBtn.classList.add('active');
        tabCrudBtn.classList.remove('active');
    });

    // =============================================
    // CRUD TAB
    // =============================================

    // --- Read Document by ID ---
    var readDocBtn = document.getElementById('readDocBtn');
    readDocBtn.addEventListener('click', function () {
        var id = document.getElementById('readDocId').value;
        readDocBtn.disabled = true;
        readDocBtn.textContent = 'Reading...';

        window.workshopGet('/api/docs/' + encodeURIComponent(id), 'tab-crud-btn').then(function (data) {
            readDocBtn.disabled = false;
            readDocBtn.textContent = 'Read';
            showCmd('read-cmd', 'read-cmd-output', data.redisCommand);
            var container = document.getElementById('read-result');
            if (data.status === 'NOT_FOUND') {
                renderAnimatedCards(container, '<div class="data-card"><p style="color:var(--text-muted);">Document not found.</p></div>');
                window.showToast('Document not found.', 'warning');
                return;
            }
            var doc = data.document;
            try { doc = JSON.parse(doc); if (Array.isArray(doc)) doc = doc[0]; } catch(e) {}
            renderAnimatedCards(container, '<div class="data-card"><pre style="font-size:0.78rem; margin:0; white-space:pre-wrap; color:var(--text-secondary); font-family:var(--font-code); max-height:300px; overflow:auto;">' + escapeHtml(JSON.stringify(doc, null, 2)) + '</pre></div>');
            window.showToast('Document loaded from Redis JSON.', 'success');
        }).catch(function () {
            readDocBtn.disabled = false;
            readDocBtn.textContent = 'Read';
            window.renderAnimatedHtml(document.getElementById('read-result'), '<p style="color:var(--redis-primary);">Failed to read document.</p>', { containerClasses: 'fade-in highlight-new' });
            window.showToast('Failed to read the document.', 'error');
        });
    });

    // --- Read Specific Field ---
    var readFieldBtn = document.getElementById('readFieldBtn');
    readFieldBtn.addEventListener('click', function () {
        var id = document.getElementById('fieldDocId').value;
        var path = document.getElementById('fieldPath').value;
        readFieldBtn.disabled = true;
        readFieldBtn.textContent = 'Reading...';

        window.workshopGet('/api/docs/' + encodeURIComponent(id) + '/' + encodeURIComponent(path), 'tab-crud-btn').then(function (data) {
            readFieldBtn.disabled = false;
            readFieldBtn.textContent = 'Read Field';
            showCmd('field-cmd', 'field-cmd-output', data.redisCommand);
            var container = document.getElementById('field-result');
            if (data.status === 'NOT_FOUND') {
                renderAnimatedCards(container, '<div class="data-card"><p style="color:var(--text-muted);">Field not found.</p></div>');
                window.showToast('Field not found in the selected document.', 'warning');
                return;
            }
            var val = data.value;
            try { val = JSON.parse(val); } catch(e) {}
            renderAnimatedCards(container, '<div class="data-card"><div style="font-family:var(--font-code); font-size:0.85rem; color:var(--text-secondary); word-break:break-word;">' + escapeHtml(typeof val === 'string' ? val : JSON.stringify(val, null, 2)) + '</div></div>');
            window.showToast('Field loaded successfully.', 'success');
        }).catch(function () {
            readFieldBtn.disabled = false;
            readFieldBtn.textContent = 'Read Field';
            window.renderAnimatedHtml(document.getElementById('field-result'), '<p style="color:var(--redis-primary);">Failed to read field.</p>', { containerClasses: 'fade-in highlight-new' });
            window.showToast('Failed to read the requested field.', 'error');
        });
    });

    // --- Create Document ---
    var createDocBtn = document.getElementById('createDocBtn');
    createDocBtn.addEventListener('click', function () {
        var title = document.getElementById('createTitle').value.trim();
        if (!title) { document.getElementById('createTitle').style.borderColor = 'var(--redis-primary)'; return; }
        document.getElementById('createTitle').style.borderColor = '';
        createDocBtn.disabled = true;
        createDocBtn.textContent = 'Creating...';

        var body = {
            title: title,
            category: document.getElementById('createCategory').value,
            summary: document.getElementById('createSummary').value.trim(),
            tags: document.getElementById('createTags').value.trim(),
            content: document.getElementById('createSummary').value.trim()
        };

        window.workshopFetch('/api/docs', body, 'tab-crud-btn').then(function (data) {
            createDocBtn.disabled = false;
            createDocBtn.textContent = 'Create Document';
            showCmd('create-cmd', 'create-cmd-output', data.redisCommand);
            var container = document.getElementById('create-result');
            var doc = data.document || {};
            renderAnimatedCards(container, '<div class="data-card" style="border-left:3px solid #0a7e3e;">' +
                '<div style="color:#0a7e3e; font-weight:700; margin-bottom:8px;">Document Created</div>' +
                '<div style="font-family:var(--font-code); font-size:0.78rem; color:var(--text-muted); margin-bottom:4px;">Key: ' + escapeHtml(data.key || '') + '</div>' +
                '<div style="font-family:var(--font-code); font-size:0.78rem; color:var(--text-muted); margin-bottom:8px;">ID: ' + escapeHtml(data.id || '') + '</div>' +
                '<pre style="font-size:0.75rem; margin:0; white-space:pre-wrap; color:var(--text-secondary); font-family:var(--font-code);">' + escapeHtml(JSON.stringify(doc, null, 2)) + '</pre></div>');
            // Clear form
            document.getElementById('createTitle').value = '';
            document.getElementById('createSummary').value = '';
            document.getElementById('createTags').value = '';
            window.showToast('Document created successfully.', 'success');
        }).catch(function () {
            createDocBtn.disabled = false;
            createDocBtn.textContent = 'Create Document';
            window.renderAnimatedHtml(document.getElementById('create-result'), '<p style="color:var(--redis-primary);">Failed to create document.</p>', { containerClasses: 'fade-in highlight-new' });
            window.showToast('Failed to create the document.', 'error');
        });
    });

    // --- Query by Field ---
    var queryBtn = document.getElementById('queryBtn');
    queryBtn.addEventListener('click', function () {
        var field = document.getElementById('queryField').value;
        var value = document.getElementById('queryValue').value.trim();
        if (!value) { document.getElementById('queryValue').style.borderColor = 'var(--redis-primary)'; return; }
        document.getElementById('queryValue').style.borderColor = '';
        doQuery(field, value);
    });

    document.querySelectorAll('.query-preset').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var field = btn.getAttribute('data-field');
            var value = btn.getAttribute('data-value');
            document.getElementById('queryField').value = field;
            document.getElementById('queryValue').value = value;
            doQuery(field, value);
        });
    });

    function doQuery(field, value) {
        queryBtn.disabled = true;
        queryBtn.textContent = 'Querying...';
        window.workshopGet('/api/docs/query?field=' + encodeURIComponent(field) + '&value=' + encodeURIComponent(value), 'tab-crud-btn').then(function (data) {
            queryBtn.disabled = false;
            queryBtn.textContent = 'Query';
            showCmd('query-cmd', 'query-cmd-output', data.redisCommand);
            var container = document.getElementById('query-result');
            var results = data.results || [];
            if (results.length === 0) {
                renderAnimatedCards(container, '<div class="data-card" style="text-align:center; padding:16px;"><p style="color:var(--text-muted);">No documents found for ' + escapeHtml(data.query || '') + '</p></div>');
                window.showToast('No documents matched the field query.', 'warning');
                return;
            }
            renderAnimatedCards(container, '<div style="font-family:var(--font-code); font-size:0.8rem; color:var(--text-muted); margin-bottom:8px;">Found ' + results.length + ' document(s)</div>' + renderDocCards(results));
            window.showToast('Field query returned ' + results.length + ' document(s).', 'success');
        }).catch(function () {
            queryBtn.disabled = false;
            queryBtn.textContent = 'Query';
            window.renderAnimatedHtml(document.getElementById('query-result'), '<p style="color:var(--redis-primary);">Query failed.</p>', { containerClasses: 'fade-in highlight-new' });
            window.showToast('Field query failed.', 'error');
        });
    }

    // --- Shared CRUD helpers ---
    function showCmd(cardId, outputId, cmd) {
        if (!cmd) return;
        var card = document.getElementById(cardId);
        card.style.display = '';
        document.getElementById(outputId).textContent = cmd;
        window.animateResult(card, 'fade-in');
    }

    function renderAnimatedCards(container, html, childSelector) {
        return window.renderAnimatedHtml(container, html, {
            containerClasses: 'fade-in',
            childSelector: childSelector || '.data-card',
            childClasses: 'slide-up highlight-new',
            staggerMs: 35
        });
    }

    function renderDocCards(results) {
        var html = '';
        results.forEach(function (doc) {
            html += '<div class="data-card" style="margin-bottom:8px;">';
            html += '<div style="font-weight:700; font-size:0.9rem; color:var(--text-primary); margin-bottom:4px;">' + escapeHtml(doc.title || '') + '</div>';
            html += '<div style="display:flex; gap:8px; align-items:center; margin-bottom:4px;">';
            html += '<span class="status-badge" style="font-size:0.7rem;">' + escapeHtml(doc.category || '') + '</span>';
            if (doc.tags) {
                doc.tags.split(',').slice(0, 3).forEach(function (tag) {
                    html += '<span style="font-family:var(--font-code); font-size:0.65rem; color:var(--text-muted); background:var(--bg-tertiary); padding:2px 6px; border-radius:3px;">' + escapeHtml(tag.trim()) + '</span>';
                });
            }
            html += '</div>';
            html += '<div style="font-size:0.8rem; color:var(--text-secondary); line-height:1.4;">' + escapeHtml(doc.summary || '') + '</div>';
            html += '</div>';
        });
        return html;
    }

    // =============================================
    // SEARCH TAB (existing functionality)
    // =============================================

    var searchInput = document.getElementById('searchInput');
    var searchBtn = document.getElementById('searchBtn');
    var commandCard = document.getElementById('command-card');
    var commandOutput = document.getElementById('command-output');
    var resultsSummary = document.getElementById('results-summary');
    var resultMode = document.getElementById('result-mode');
    var resultCount = document.getElementById('result-count');
    var resultsContainer = document.getElementById('results-container');
    var currentMode = 'full-text';

    // --- Search Mode Toggle ---
    document.querySelectorAll('#mode-toggle .doc-mode-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            document.querySelectorAll('#mode-toggle .doc-mode-btn').forEach(function (b) { b.classList.remove('active'); });
            btn.classList.add('active');
            currentMode = btn.getAttribute('data-mode');
        });
    });

    // --- Quick Search Buttons ---
    document.querySelectorAll('.doc-quick').forEach(function (btn) {
        btn.addEventListener('click', function () {
            searchInput.value = btn.getAttribute('data-q');
            doSearch();
        });
    });

    // --- Search ---
    searchBtn.addEventListener('click', doSearch);
    searchInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') doSearch();
    });

    function doSearch() {
        var query = searchInput.value.trim();
        if (!query) { searchInput.style.borderColor = 'var(--redis-primary)'; return; }
        searchInput.style.borderColor = '';
        searchBtn.disabled = true;
        searchBtn.textContent = 'Searching...';

        var url = '/api/docs/search?q=' + encodeURIComponent(query) + '&mode=' + encodeURIComponent(currentMode);

        window.workshopGet(url, 'tab-crud-btn').then(function (data) {
            searchBtn.disabled = false;
            searchBtn.textContent = 'Search';
            renderSearchResults(data);
            window.showToast('Document search completed in ' + currentMode + ' mode.', 'info');
        }).catch(function () {
            searchBtn.disabled = false;
            searchBtn.textContent = 'Search';
            resultsContainer.innerHTML = '<p style="color:var(--redis-primary);">Search failed. Is Redis running?</p>';
            window.showToast('Document search failed. Is Redis running?', 'error');
        });
    }

    function renderSearchResults(data) {
        if (data.redisCommand) {
            commandCard.style.display = '';
            commandOutput.textContent = data.redisCommand;
        }
        resultsSummary.style.display = '';
        resultMode.textContent = getModeLabel(data.mode);
        resultCount.textContent = data.resultCount || 0;

        var existingWarning = document.getElementById('mock-vectors-warning');
        if (existingWarning) existingWarning.remove();
        if (data.mockVectors) {
            var warning = document.createElement('div');
            warning.id = 'mock-vectors-warning';
            warning.style.cssText = 'background:var(--bg-tertiary); border-left:3px solid var(--redis-primary); padding:8px 12px; margin-bottom:12px; font-size:0.8rem; color:var(--text-muted); border-radius:0 5px 5px 0;';
            warning.innerHTML = 'Using mock vectors (no OpenAI API key). Similarity scores are not meaningful — configure <code>OPENAI_API_KEY</code> for real embeddings.';
            resultsContainer.parentNode.insertBefore(warning, resultsContainer);
            window.animateResult(warning, 'fade-in highlight-new');
        }

        var results = data.results || [];
        if (results.length === 0) {
            renderAnimatedCards(resultsContainer, '<div class="data-card" style="text-align:center; padding:32px;"><p style="color:var(--text-muted);">No documents found. Try a different query or search mode.</p></div>');
            return;
        }

        var html = '';
        results.forEach(function (doc) {
            var scoreDisplay = formatScore(doc.score, data.mode);
            html += '<div class="data-card" style="margin-bottom:12px;">';
            html += '<div style="display:flex; justify-content:space-between; align-items:flex-start; margin-bottom:8px;">';
            html += '<div style="flex:1;">';
            html += '<div style="font-weight:700; font-size:0.95rem; color:var(--text-primary); margin-bottom:4px;">' + escapeHtml(doc.title || '') + '</div>';
            html += '<div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">';
            html += '<span class="status-badge" style="font-size:0.7rem;">' + escapeHtml(doc.category || '') + '</span>';
            if (doc.tags) {
                doc.tags.split(',').slice(0, 3).forEach(function (tag) {
                    html += '<span style="font-family:var(--font-code); font-size:0.65rem; color:var(--text-muted); background:var(--bg-tertiary); padding:2px 6px; border-radius:3px;">' + escapeHtml(tag.trim()) + '</span>';
                });
            }
            html += '</div></div>';
            html += '<div style="text-align:right; min-width:80px;">' + scoreDisplay + '</div>';
            html += '</div>';
            html += '<div style="font-size:0.82rem; color:var(--text-secondary); line-height:1.5;">' + escapeHtml(doc.summary || '') + '</div>';
            html += '</div>';
        });
        renderAnimatedCards(resultsContainer, html);
    }

    function getModeLabel(mode) {
        if (mode === 'vector') return 'Vector (KNN)';
        if (mode === 'hybrid') return 'Hybrid (Text + KNN)';
        return 'Full-Text (RQE)';
    }

    function formatScore(score, mode) {
        if (score === undefined || score === null) return '';
        var s = parseFloat(score);
        var pct = Math.round(s * 100);
        var color = pct >= 80 ? '#0a7e3e' : (pct >= 50 ? '#cc8800' : 'var(--text-muted)');
        var label = mode === 'full-text' ? 'Match' : 'Similarity';
        return '<div style="font-family:var(--font-code); font-size:0.75rem; color:var(--text-muted);">' + label + '</div>' +
               '<div style="font-family:var(--font-code); font-size:1.1rem; font-weight:700; color:' + color + ';">' + pct + '%</div>';
    }
})();
