/**
 * UC1: Authentication Token Store
 * Interactive demo: login → generate token → validate → logout (destroy)
 */
(function () {
    'use strict';

    var MAX_TTL = 300;
    var ttlInterval = null;
    var currentToken = null;

    // --- DOM refs ---
    var loginSection   = document.getElementById('login-section');
    var tokenSection   = document.getElementById('token-section');
    var loginBtn       = document.getElementById('loginBtn');
    var logoutBtn      = document.getElementById('logoutBtn');
    var validateBtn    = document.getElementById('validateBtn');
    var usernameInput  = document.getElementById('username');
    var passwordInput  = document.getElementById('password');
    var loginError     = document.getElementById('login-error');
    var tokenData      = document.getElementById('token-data');
    var validateResult = document.getElementById('validate-result');
    var ttlValue       = document.getElementById('ttl-value');
    var ttlFill        = document.getElementById('ttl-fill');
    var tokenStatus    = document.getElementById('token-status');
    var tokenExpired   = document.getElementById('token-expired');

    // --- Code Tabs ---
    window.initCodeTabs();

    // --- Helpers ---
    function buildRow(label, value) {
        return '<div class="data-row"><span class="data-label">' + label + '</span><span class="data-value">' + value + '</span></div>';
    }

    function showLogin() {
        loginSection.style.display = '';
        tokenSection.style.display = 'none';
        loginError.style.display = 'none';
        tokenExpired.style.display = 'none';
        validateResult.style.display = 'none';
        usernameInput.value = '';
        passwordInput.value = '';
    }

    function showToken(data) {
        loginSection.style.display = 'none';
        tokenSection.style.display = '';
        tokenExpired.style.display = 'none';
        validateResult.style.display = 'none';
        tokenStatus.textContent = '● VALID';
        tokenStatus.className = 'status-badge active';

        var rows = '';
        rows += buildRow('Redis Key', data.redisKey);
        rows += buildRow('Token ID', data.tokenId);
        rows += buildRow('Username', data.username);
        rows += buildRow('Full Name', data.fullName);
        rows += buildRow('Email', data.email);
        rows += buildRow('Role', data.role);
        rows += buildRow('IP Address', data.ipAddress);
        tokenData.innerHTML = rows;

        currentToken = data.tokenId;
        startTtlCountdown(data.ttl);
    }

    function startTtlCountdown(initialTtl) {
        stopTtlCountdown();
        var ttl = initialTtl;

        function tick() {
            if (ttl <= 0) {
                ttlValue.textContent = '0s';
                ttlFill.style.width = '0%';
                tokenStatus.textContent = '● EXPIRED';
                tokenStatus.className = 'status-badge expired';
                tokenExpired.style.display = '';
                logoutBtn.style.display = 'none';
                validateBtn.disabled = true;
                stopTtlCountdown();
                setTimeout(function () {
                    currentToken = null;
                    logoutBtn.style.display = '';
                    validateBtn.disabled = false;
                    showLogin();
                }, 3000);
                return;
            }
            ttlValue.textContent = ttl + 's';
            ttlFill.style.width = ((ttl / MAX_TTL) * 100) + '%';
            ttl--;
        }

        tick();
        ttlInterval = setInterval(tick, 1000);
    }

    function stopTtlCountdown() {
        if (ttlInterval) { clearInterval(ttlInterval); ttlInterval = null; }
    }

    // --- Login ---
    loginBtn.addEventListener('click', function () {
        var username = usernameInput.value.trim();
        var password = passwordInput.value.trim();
        if (!username || !password) {
            loginError.textContent = 'Please enter username and password.';
            loginError.style.display = '';
            return;
        }
        loginBtn.disabled = true;
        loginBtn.textContent = 'Authenticating...';
        loginError.style.display = 'none';

        window.workshopFetch('/api/auth/login', { username: username, password: password })
            .then(function (data) {
                loginBtn.disabled = false;
                loginBtn.textContent = 'Login & Generate Token';
                if (data.error) { loginError.textContent = data.error; loginError.style.display = ''; }
                else { showToken(data); }
            })
            .catch(function () {
                loginBtn.disabled = false;
                loginBtn.textContent = 'Login & Generate Token';
                loginError.textContent = 'Network error — is the server running?';
                loginError.style.display = '';
            });
    });

    passwordInput.addEventListener('keydown', function (e) { if (e.key === 'Enter') loginBtn.click(); });
    usernameInput.addEventListener('keydown', function (e) { if (e.key === 'Enter') passwordInput.focus(); });

    // --- Validate ---
    validateBtn.addEventListener('click', function () {
        if (!currentToken) return;
        window.workshopFetch('/api/auth/validate', { token: currentToken })
            .then(function (data) {
                validateResult.style.display = '';
                if (data.valid) {
                    validateResult.className = 'alert alert-success';
                    validateResult.innerHTML = '&#10003; Token is <strong>valid</strong>. TTL: ' + data.ttl + 's remaining.';
                } else {
                    validateResult.className = 'alert alert-error';
                    validateResult.innerHTML = '&#10007; Token is <strong>invalid</strong> or expired.';
                }
            });
    });

    // --- Logout (Destroy Token) ---
    logoutBtn.addEventListener('click', function () {
        if (!currentToken) return;
        window.workshopFetch('/api/auth/logout', { token: currentToken })
            .then(function () { stopTtlCountdown(); currentToken = null; showLogin(); });
    });
})();
