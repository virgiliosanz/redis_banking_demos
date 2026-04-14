/**
 * UC8: Document Database — Full-text + Vector + Hybrid
 * Interactive demo: search bar, mode toggle, results rendering
 */
(function () {
    'use strict';

    // --- DOM refs ---
    var searchInput = document.getElementById('searchInput');
    var searchBtn = document.getElementById('searchBtn');
    var commandCard = document.getElementById('command-card');
    var commandOutput = document.getElementById('command-output');
    var resultsSummary = document.getElementById('results-summary');
    var resultMode = document.getElementById('result-mode');
    var resultCount = document.getElementById('result-count');
    var resultsContainer = document.getElementById('results-container');

    var currentMode = 'full-text';

    // --- Code Tabs ---
    document.querySelectorAll('.code-tab').forEach(function (tab) {
        tab.addEventListener('click', function () {
            document.querySelectorAll('.code-tab').forEach(function (t) { t.classList.remove('active'); });
            document.querySelectorAll('.code-block').forEach(function (b) { b.classList.remove('active'); });
            tab.classList.add('active');
            document.getElementById('tab-' + tab.getAttribute('data-tab')).classList.add('active');
        });
    });

    // --- Mode Toggle ---
    document.querySelectorAll('.doc-mode-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            document.querySelectorAll('.doc-mode-btn').forEach(function (b) { b.classList.remove('active'); });
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
        if (!query) {
            searchInput.style.borderColor = 'var(--redis-primary)';
            return;
        }
        searchInput.style.borderColor = '';
        searchBtn.disabled = true;
        searchBtn.textContent = 'Searching...';

        var url = '/api/docs/search?q=' + encodeURIComponent(query) + '&mode=' + encodeURIComponent(currentMode);

        window.workshopGet(url).then(function (data) {
            searchBtn.disabled = false;
            searchBtn.textContent = '🔍 Search';
            renderResults(data);
        }).catch(function () {
            searchBtn.disabled = false;
            searchBtn.textContent = '🔍 Search';
            resultsContainer.innerHTML = '<p style="color:var(--redis-primary);">Search failed. Is Redis running?</p>';
        });
    }

    function renderResults(data) {
        // Show Redis command
        if (data.redisCommand) {
            commandCard.style.display = '';
            commandOutput.textContent = data.redisCommand;
        }

        // Show summary
        resultsSummary.style.display = '';
        resultMode.textContent = getModeLabel(data.mode);
        resultCount.textContent = data.resultCount || 0;

        // Render result cards
        var results = data.results || [];
        if (results.length === 0) {
            resultsContainer.innerHTML = '<div class="data-card" style="text-align:center; padding:32px;"><p style="color:var(--text-muted);">No documents found. Try a different query or search mode.</p></div>';
            return;
        }

        var html = '';
        results.forEach(function (doc, idx) {
            var scoreDisplay = formatScore(doc.score, data.mode);
            var categoryClass = getCategoryClass(doc.category);

            html += '<div class="data-card" style="margin-bottom:12px; animation: resultPop 0.3s ease ' + (idx * 0.05) + 's both;">';
            html += '<div style="display:flex; justify-content:space-between; align-items:flex-start; margin-bottom:8px;">';
            html += '<div style="flex:1;">';
            html += '<div style="font-weight:700; font-size:0.95rem; color:var(--text-primary); margin-bottom:4px;">' + escapeHtml(doc.title || '') + '</div>';
            html += '<div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">';
            html += '<span class="status-badge ' + categoryClass + '" style="font-size:0.7rem;">' + escapeHtml(doc.category || '') + '</span>';
            if (doc.tags) {
                var tags = (doc.tags || '').split(',');
                tags.slice(0, 3).forEach(function (tag) {
                    html += '<span style="font-family:var(--font-code); font-size:0.65rem; color:var(--text-muted); background:var(--bg-tertiary); padding:2px 6px; border-radius:3px;">' + escapeHtml(tag.trim()) + '</span>';
                });
            }
            html += '</div></div>';
            html += '<div style="text-align:right; min-width:80px;">' + scoreDisplay + '</div>';
            html += '</div>';
            html += '<div style="font-size:0.82rem; color:var(--text-secondary); line-height:1.5;">' + escapeHtml(doc.summary || '') + '</div>';
            html += '</div>';
        });

        resultsContainer.innerHTML = html;
    }

    function getModeLabel(mode) {
        if (mode === 'vector') return '🧠 Vector (KNN)';
        if (mode === 'hybrid') return '⚡ Hybrid (Text + KNN)';
        return '📝 Full-Text (RQE)';
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

    function getCategoryClass(category) {
        if (category === 'PSD2') return 'active';
        if (category === 'GDPR') return 'expired';
        return '';
    }

    function escapeHtml(str) {
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(str));
        return div.innerHTML;
    }
})();
