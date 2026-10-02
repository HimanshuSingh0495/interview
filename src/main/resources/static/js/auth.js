/* Login and register pages (form#auth-form[data-mode="login"|"register"]). */
(function () {
    'use strict';
    var P = window.PollApp;

    var USERNAME_RE = /^[A-Za-z0-9_]{3,30}$/;

    var form = document.getElementById('auth-form');
    if (!form) return;
    var mode = form.getAttribute('data-mode');
    var usernameInput = form.querySelector('[data-testid="username-input"]');
    var passwordInput = form.querySelector('[data-testid="password-input"]');
    var submitBtn = form.querySelector('[data-testid="submit-btn"]');
    var errorBox = form.querySelector('[data-testid="error-msg"]');

    var next = P.nextParam();
    var target = P.safeNext(next);

    // Keep ?next= when switching between login and register.
    var switchLink = document.querySelector('[data-testid="switch-auth-link"]');
    if (switchLink && next) {
        var base = mode === 'login' ? '/register' : '/login';
        switchLink.setAttribute('href', base + '?next=' + P.encodePath(target));
    }

    // Already logged in: go straight on.
    if (P.isLoggedIn() && next) {
        window.location.replace(target);
        return;
    }

    function markInvalid(input, invalid) {
        if (invalid) input.setAttribute('aria-invalid', 'true');
        else input.removeAttribute('aria-invalid');
    }

    function validate(username, password) {
        var errors = [];
        if (mode === 'register') {
            if (!USERNAME_RE.test(username)) {
                errors.push({ field: 'username', message: 'use 3 to 30 letters, numbers or underscores' });
            }
            if (password.length < 8 || password.length > 72) {
                errors.push({ field: 'password', message: 'use 8 to 72 characters' });
            }
        } else {
            if (!username) errors.push({ field: 'username', message: 'enter your username' });
            if (!password) errors.push({ field: 'password', message: 'enter your password' });
        }
        return errors;
    }

    function applyFieldMarks(fieldErrors) {
        var names = fieldErrors.map(function (f) { return f.field; });
        markInvalid(usernameInput, names.indexOf('username') !== -1);
        markInvalid(passwordInput, names.indexOf('password') !== -1);
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        if (submitBtn.disabled) return;

        var username = usernameInput.value.trim();
        var password = passwordInput.value;

        var errors = validate(username, password);
        applyFieldMarks(errors);
        if (errors.length) {
            P.showError(errorBox, { message: 'Check the highlighted fields.', fieldErrors: errors });
            var first = errors[0].field === 'username' ? usernameInput : passwordInput;
            first.focus();
            return;
        }

        P.showError(errorBox, null);
        var restore = P.setBusy(submitBtn, mode === 'login' ? 'Logging in…' : 'Creating account…');
        var path = mode === 'login' ? '/auth/login' : '/auth/register';

        P.api.post(path, { username: username, password: password }, { auth: false, redirectOn401: false })
            .then(function (auth) {
                P.saveAuth(auth);
                window.location.assign(target);
            })
            .catch(function (err) {
                restore();
                if (mode === 'login' && err.status === 401) {
                    err.message = err.message || 'Wrong username or password.';
                }
                applyFieldMarks(err.fieldErrors || []);
                P.showError(errorBox, err);
                if (mode === 'register' && err.status === 409) markInvalid(usernameInput, true);
                usernameInput.focus();
            });
    });
})();
