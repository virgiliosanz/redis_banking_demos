/**
 * UC2: Session Storage
 * Interactive demo: login, session display, TTL countdown, logout
 */
(function () {
    'use strict';

    var MAX_TTL = 300;
    var ttlInterval = null;
    var currentUser = null;
    var currentToken = null;

    // --- DOM refs ---
    var loginSection   = document.getElementById('login-section');
    var sessionSection = document.getElementById('session-section');
    var loginBtn       = document.getElementById('loginBtn');
    var logoutBtn      = document.getElementById('logoutBtn');
    var usernameInput  = document.getElementById('username');
    var passwordInput  = document.getElementById('password');
    var loginError     = document.getElementById('login-error');
    var sessionData    = document.getElementById('session-data');
    var tokenData      = document.getElementById('token-data');
    var ttlValue       = document.getElementById('ttl-value');
    var ttlFill        = document.getElementById('ttl-fill');
    var sessionStatus  = document.getElementById('session-status');
    var sessionExpired = document.getElementById('session-expired');

    // --- Code Tabs ---
    window.initCodeTabs();

    // --- Helpers ---
    function buildRow(label, value) {
        return '<div class="data-row"><span class="data-label">' + label + '</span><span class="data-value">' + value + '</span></div>';
    }

    function showLogin() {
        loginSection.style.display = '';
        sessionSection.style.display = 'none';
        loginError.style.display = 'none';
        sessionExpired.style.display = 'none';
        usernameInput.value = '';
        passwordInput.value = '';
    }

    function showSession(data) {
        loginSection.style.display = 'none';
        sessionSection.style.display = '';
        sessionExpired.style.display = 'none';
        sessionStatus.textContent = '● ACTIVE';
        sessionStatus.className = 'status-badge active';

        var rows = '';
        rows += buildRow('Key', data.sessionKey);
        rows += buildRow('Username', data.username);
        rows += buildRow('Full Name', data.fullName);
        rows += buildRow('Email', data.email);
        rows += buildRow('Role', data.role);
        rows += buildRow('Account', data.accountId);
        rows += buildRow('IP Address', data.ipAddress);
        sessionData.innerHTML = rows;

        var tokenRows = '';
        tokenRows += buildRow('Key', data.tokenKey);
        tokenRows += buildRow('Token', data.token);
        tokenData.innerHTML = tokenRows;

        currentUser = data.username;
        currentToken = data.token;
        startTtlCountdown(data.ttl);
    }

    function startTtlCountdown(initialTtl) {
        stopTtlCountdown();
        var ttl = initialTtl;

        function tick() {
            if (ttl <= 0) {
                ttlValue.textContent = '0s';
                ttlFill.style.width = '0%';
                sessionStatus.textContent = '● EXPIRED';
                sessionStatus.className = 'status-badge expired';
                sessionExpired.style.display = '';
                logoutBtn.style.display = 'none';
                stopTtlCountdown();
                // Auto-return to login after 3s
                setTimeout(function () {
                    currentUser = null;
                    currentToken = null;
                    logoutBtn.style.display = '';
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
        if (ttlInterval) {
            clearInterval(ttlInterval);
            ttlInterval = null;
        }
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

        window.workshopFetch('/api/session/login', { username: username, password: password })
            .then(function (data) {
                loginBtn.disabled = false;
                loginBtn.textContent = 'Login';
                if (data.error) {
                    loginError.textContent = data.error;
                    loginError.style.display = '';
                } else {
                    showSession(data);
                }
            })
            .catch(function () {
                loginBtn.disabled = false;
                loginBtn.textContent = 'Login';
                loginError.textContent = 'Network error — is the server running?';
                loginError.style.display = '';
            });
    });

    // Enter key to login
    passwordInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') loginBtn.click();
    });
    usernameInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') passwordInput.focus();
    });

    // --- Logout ---
    logoutBtn.addEventListener('click', function () {
        if (!currentUser) return;

        window.workshopFetch('/api/session/logout', { username: currentUser })
            .then(function () {
                stopTtlCountdown();
                currentUser = null;
                currentToken = null;
                showLogin();
            });
    });
})();
