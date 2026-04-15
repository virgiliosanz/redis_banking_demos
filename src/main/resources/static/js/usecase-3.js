/**
 * UC3: User Profile Storage
 * Interactive demo: select user → load from DBs → view profile → edit → sync back
 */
(function () {
    'use strict';

    var MAX_TTL = 600;
    var ttlInterval = null;

    // --- DOM refs ---
    var userSelect     = document.getElementById('userSelect');
    var loadBtn        = document.getElementById('loadBtn');
    var syncBtn        = document.getElementById('syncBtn');
    var profileCard    = document.getElementById('profile-card');
    var profileData    = document.getElementById('profile-data');
    var redisKeyDisplay = document.getElementById('redis-key-display');
    var editCard       = document.getElementById('edit-card');
    var editField      = document.getElementById('editField');
    var editValue      = document.getElementById('editValue');
    var updateBtn      = document.getElementById('updateBtn');
    var ttlContainer   = document.getElementById('ttl-container');
    var ttlValue       = document.getElementById('ttl-value');
    var ttlFill        = document.getElementById('ttl-fill');
    var syncResult     = document.getElementById('sync-result');

    // --- Code Tabs ---
    window.initCodeTabs();

    // --- Helpers ---
    function buildRow(label, value, highlight) {
        var cls = highlight ? ' style="font-weight:700; color:var(--redis-primary);"' : '';
        return '<div class="data-row"><span class="data-label">' + label +
               '</span><span class="data-value"' + cls + '>' + value + '</span></div>';
    }

    // --- Load users into selector ---
    function loadUsers() {
        window.workshopGet('/api/profile/users').then(function (users) {
            userSelect.innerHTML = '';
            users.forEach(function (u) {
                var opt = document.createElement('option');
                opt.value = u.userId;
                opt.textContent = u.userId + ' — ' + u.name + ' (' + u.segment + ')';
                userSelect.appendChild(opt);
            });
        });
    }

    // --- Display profile ---
    function displayProfile(data) {
        profileCard.style.display = '';
        editCard.style.display = '';
        ttlContainer.style.display = '';
        syncBtn.disabled = false;
        redisKeyDisplay.textContent = '📦 ' + data.redisKey;

        var rows = '';
        var keys = Object.keys(data).sort();
        keys.forEach(function (key) {
            if (key === 'redisKey' || key === 'ttl' || key === 'fieldCount' || key === 'sources') return;
            var label = key.replace(/^(account_|activity_|pref_)/, function (m) {
                return m === 'account_' ? '🏦 ' : m === 'activity_' ? '📊 ' : '⚙️ ';
            });
            rows += buildRow(label, data[key], key.startsWith('account_balance'));
        });
        profileData.innerHTML = rows;

        startTtlCountdown(data.ttl || MAX_TTL);
    }

    function startTtlCountdown(initialTtl) {
        if (ttlInterval) clearInterval(ttlInterval);
        var ttl = initialTtl;
        function tick() {
            if (ttl <= 0) { ttlValue.textContent = '0s'; ttlFill.style.width = '0%'; clearInterval(ttlInterval); return; }
            ttlValue.textContent = ttl + 's';
            ttlFill.style.width = ((ttl / MAX_TTL) * 100) + '%';
            ttl--;
        }
        tick();
        ttlInterval = setInterval(tick, 1000);
    }

    // --- Load profile ---
    loadBtn.addEventListener('click', function () {
        var userId = userSelect.value;
        loadBtn.disabled = true;
        loadBtn.textContent = 'Loading from DBs...';
        syncResult.style.display = 'none';

        window.workshopFetch('/api/profile/load/' + userId, {})
            .then(function (data) {
                loadBtn.disabled = false;
                loadBtn.textContent = '📥 Load Profile from DBs';
                if (data.error) return;
                displayProfile(data);
            })
            .catch(function () {
                loadBtn.disabled = false;
                loadBtn.textContent = '📥 Load Profile from DBs';
            });
    });

    // --- Update field ---
    updateBtn.addEventListener('click', function () {
        var userId = userSelect.value;
        var field = editField.value;
        var value = editValue.value.trim();
        if (!value) { editValue.style.borderColor = 'var(--redis-primary)'; return; }
        editValue.style.borderColor = '';

        var body = {};
        body[field] = value;
        window.workshopFetch('/api/profile/update/' + userId, body)
            .then(function (data) {
                if (data && !data.error) displayProfile(data);
                editValue.value = '';
            });
    });

    // --- Sync back ---
    syncBtn.addEventListener('click', function () {
        var userId = userSelect.value;
        window.workshopFetch('/api/profile/sync/' + userId, {})
            .then(function (data) {
                syncResult.style.display = '';
                syncResult.className = 'alert alert-success';
                syncResult.innerHTML = '✅ ' + data.message;
            });
    });

    // --- Init ---
    loadUsers();
})();
