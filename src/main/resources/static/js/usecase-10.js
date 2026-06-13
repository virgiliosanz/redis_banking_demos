/** UC10: Cache-Aside Pattern — Banking Product Catalog Caching */
(function () {
    'use strict';
    window.WORKSHOP_UC = 'UC10';

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

    function fetchJson(url, opts) {
        return fetch(url, opts).then(function (response) {
            window.showLatencyBadge('btnFetch', window.extractLatency(response));
            return window.parseJsonResponse(response);
        });
    }

    // --- Product type icons ---
    var typeLabels = {
        'Mortgage': 'MTG', 'Savings': 'SAV', 'Credit Card': 'CC', 'Business': 'BIZ'
    };

    // --- Load products ---
    function loadProducts() {
        fetchJson('/api/cache/products')
            .then(function (products) {
                productCards.innerHTML = '';
                products.forEach(function (p) {
                    var card = document.createElement('div');
                    card.className = 'cache-product-card';
                    card.setAttribute('data-id', p.id);
                    card.innerHTML = '<span class="cache-card-icon">' + (typeLabels[p.type] || 'PKG') + '</span>' +
                        '<span class="cache-card-name">' + p.name + '</span>' +
                        '<span class="cache-card-type">' + p.type + '</span>';
                    card.addEventListener('click', function () { selectProduct(p.id); });
                    window.appendAnimatedElement(productCards, card, 'slide-up highlight-new');
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
        fetchJson('/api/cache/product/' + selectedProductId)
            .then(function (data) {
                if (data.error) {
                    cacheStatus.className = 'cache-status-badge cache-miss';
                    cacheStatus.textContent = data.error;
                    resultData.innerHTML = '';
                    resultBox.style.display = 'block';
                    window.showToast(data.error, 'error');
                    return;
                }
                var isHit = data.cacheHit;
                cacheStatus.className = 'cache-status-badge ' + (isHit ? 'cache-hit' : 'cache-miss');
                cacheStatus.innerHTML = (isHit ? 'CACHE HIT' : 'CACHE MISS') +
                    ' — <strong>' + data.latencyMs + 'ms</strong> from ' + data.source;

                var html = '';
                var product = data.product;
                Object.keys(product).forEach(function (key) {
                    html += '<div class="data-row"><span class="data-label">' + key +
                        '</span><span class="data-value">' + product[key] + '</span></div>';
                });
                resultData.innerHTML = html;
                resultBox.style.display = 'block';
                window.animateResult(resultBox, 'fade-in');
                window.animateChildren(resultData, '.data-row', 'slide-up highlight-new', 25);

                addLogEntry(data);
                refreshStats();
                window.showToast(
                    isHit ? ('Cache hit for product ' + selectedProductId + '.') : 'Cache miss served from the backing store and cached.',
                    isHit ? 'info' : 'success'
                );
            })
            .catch(function (err) {
                cacheStatus.className = 'cache-status-badge cache-miss';
                cacheStatus.textContent = 'Error: ' + err.message;
                window.showToast(err.message || 'Could not fetch the selected product.', 'error');
            })
            .finally(function () { btnFetch.disabled = false; });
    }

    // --- Evict ---
    function evictProduct() {
        if (!selectedProductId) return;
        fetchJson('/api/cache/product/' + selectedProductId, { method: 'DELETE' })
            .then(function (data) {
                addLogEntry({ cacheHit: null, latencyMs: 0, source: 'EVICT', product: { id: data.productId } });
                refreshStats();
                window.showToast('Evicted product ' + data.productId + ' from cache.', 'info');
            }).catch(function (err) {
                window.showToast(err.message || 'Could not evict the selected product.', 'error');
            });
    }

    function evictAll() {
        fetchJson('/api/cache/products', { method: 'DELETE' })
            .then(function (data) {
                addLogEntry({ cacheHit: null, latencyMs: 0, source: 'EVICT_ALL', product: { id: data.count + ' keys' } });
                refreshStats();
                window.showToast('Evicted ' + data.count + ' cached products.', 'success');
            }).catch(function (err) {
                window.showToast(err.message || 'Could not evict cached products.', 'error');
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
        window.appendAnimatedElement(requestLog, entry, 'slide-up highlight-new', true);
        while (requestLog.children.length > 20) requestLog.removeChild(requestLog.lastChild);
    }

    // --- Stats ---
    function refreshStats() {
        fetchJson('/api/cache/stats')
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
