/** UC10: Cache-Aside Pattern — Banking Product Catalog Caching */
(function () {
    'use strict';

    // --- DOM refs ---
    var productCards = document.getElementById('productCards');
    var btnFetch    = document.getElementById('btnFetch');
    var btnEvict    = document.getElementById('btnEvict');
    var btnEvictAll = document.getElementById('btnEvictAll');
    var resultBox   = document.getElementById('resultBox');
    var cacheStatus = document.getElementById('cacheStatus');
    var resultData  = document.getElementById('resultData');
    var requestLog  = document.getElementById('requestLog');
    var statHits    = document.getElementById('statHits');
    var statMisses  = document.getElementById('statMisses');
    var statRatio   = document.getElementById('statRatio');
    var statAvgHit  = document.getElementById('statAvgHit');
    var statAvgMiss = document.getElementById('statAvgMiss');

    var selectedProductId = null;

    // --- Code tabs ---
    window.initCodeTabs();

    // --- Product type icons ---
    var typeIcons = {
        'Mortgage': '🏠', 'Savings': '💰', 'Credit Card': '💳', 'Business': '🏢'
    };

    // --- Load products ---
    function loadProducts() {
        fetch('/api/cache/products')
            .then(function (r) { return r.json(); })
            .then(function (products) {
                productCards.innerHTML = '';
                products.forEach(function (p) {
                    var card = document.createElement('div');
                    card.className = 'cache-product-card';
                    card.setAttribute('data-id', p.id);
                    card.innerHTML = '<span class="cache-card-icon">' + (typeIcons[p.type] || '📦') + '</span>' +
                        '<span class="cache-card-name">' + p.name + '</span>' +
                        '<span class="cache-card-type">' + p.type + '</span>';
                    card.addEventListener('click', function () { selectProduct(p.id); });
                    productCards.appendChild(card);
                });
            });
    }

    function selectProduct(id) {
        selectedProductId = id;
        btnFetch.disabled = false;
        btnEvict.disabled = false;
        document.querySelectorAll('.cache-product-card').forEach(function (c) {
            c.classList.toggle('selected', c.getAttribute('data-id') === id);
        });
    }

    // --- Fetch product ---
    function fetchProduct() {
        if (!selectedProductId) return;
        btnFetch.disabled = true;
        fetch('/api/cache/product/' + selectedProductId)
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.error) {
                    cacheStatus.className = 'cache-status-badge cache-miss';
                    cacheStatus.textContent = '❌ ' + data.error;
                    resultData.innerHTML = '';
                    resultBox.style.display = 'block';
                    return;
                }
                var isHit = data.cacheHit;
                cacheStatus.className = 'cache-status-badge ' + (isHit ? 'cache-hit' : 'cache-miss');
                cacheStatus.innerHTML = (isHit ? '⚡ CACHE HIT' : '🗄️ CACHE MISS') +
                    ' — <strong>' + data.latencyMs + 'ms</strong> from ' + data.source;

                var html = '';
                var product = data.product;
                Object.keys(product).forEach(function (key) {
                    html += '<div class="data-row"><span class="data-label">' + key +
                        '</span><span class="data-value">' + product[key] + '</span></div>';
                });
                resultData.innerHTML = html;
                resultBox.style.display = 'block';

                addLogEntry(data);
                refreshStats();
            })
            .catch(function (err) {
                cacheStatus.className = 'cache-status-badge cache-miss';
                cacheStatus.textContent = '⚠️ Error: ' + err.message;
            })
            .finally(function () { btnFetch.disabled = false; });
    }

    // --- Evict ---
    function evictProduct() {
        if (!selectedProductId) return;
        fetch('/api/cache/product/' + selectedProductId, { method: 'DELETE' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                addLogEntry({ cacheHit: null, latencyMs: 0, source: 'EVICT', product: { id: data.productId } });
                refreshStats();
            });
    }

    function evictAll() {
        fetch('/api/cache/products', { method: 'DELETE' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                addLogEntry({ cacheHit: null, latencyMs: 0, source: 'EVICT_ALL', product: { id: data.count + ' keys' } });
                refreshStats();
            });
    }

    // --- Log ---
    function addLogEntry(data) {
        var entry = document.createElement('div');
        var isHit = data.cacheHit === true;
        var isMiss = data.cacheHit === false;
        var isEvict = data.source === 'EVICT' || data.source === 'EVICT_ALL';
        entry.className = 'rl-log-entry ' + (isHit ? 'rl-log-ok' : isMiss ? 'rl-log-blocked' : '');
        var time = new Date().toLocaleTimeString();
        var label = isHit ? 'HIT' : isMiss ? 'MISS' : data.source;
        var latency = data.latencyMs > 0 ? data.latencyMs + 'ms' : '';
        var productId = data.product && data.product.id ? data.product.id : '';
        entry.innerHTML = '<span class="rl-log-time">' + time + '</span>' +
            '<span class="rl-log-status">' + label + '</span>' +
            '<span class="rl-log-detail">' + productId + (latency ? ' — ' + latency : '') + '</span>';
        requestLog.insertBefore(entry, requestLog.firstChild);
        while (requestLog.children.length > 20) requestLog.removeChild(requestLog.lastChild);
    }

    // --- Stats ---
    function refreshStats() {
        fetch('/api/cache/stats')
            .then(function (r) { return r.json(); })
            .then(function (s) {
                statHits.textContent = s.hits;
                statMisses.textContent = s.misses;
                statRatio.textContent = s.hitRatio;
                statAvgHit.textContent = s.avgHitLatencyMs + 'ms';
                statAvgMiss.textContent = s.avgMissLatencyMs + 'ms';
            });
    }

    // --- Init ---
    btnFetch.addEventListener('click', fetchProduct);
    btnEvict.addEventListener('click', evictProduct);
    btnEvictAll.addEventListener('click', evictAll);
    loadProducts();
    refreshStats();
})();
