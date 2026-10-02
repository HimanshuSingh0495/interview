/*
 * Shared client helpers: token storage, JSON fetch wrapper, nav state, toast, clipboard.
 * Exposed as window.PollApp. Loaded with `defer` before each page script.
 */
(function () {
    'use strict';

    var TOKEN_KEY = 'pollapp.token';
    var USER_KEY = 'pollapp.user';
    var API_BASE = '/api/v1';

    // ---- storage -----------------------------------------------------------

    function storageGet(key) {
        try { return window.localStorage.getItem(key); } catch (e) { return null; }
    }

    function storageSet(key, value) {
        try { window.localStorage.setItem(key, value); } catch (e) { /* storage blocked */ }
    }

    function storageRemove(key) {
        try { window.localStorage.removeItem(key); } catch (e) { /* storage blocked */ }
    }

    function getToken() {
        return storageGet(TOKEN_KEY);
    }

    function getUser() {
        var raw = storageGet(USER_KEY);
        if (!raw) return null;
        try {
            var user = JSON.parse(raw);
            return user && typeof user.username === 'string' ? user : null;
        } catch (e) {
            return null;
        }
    }

    function isLoggedIn() {
        return !!getToken() && !!getUser();
    }

    /** Stores an AuthResponse {token, userId, username}. */
    function saveAuth(auth) {
        storageSet(TOKEN_KEY, auth.token);
        storageSet(USER_KEY, JSON.stringify({ userId: auth.userId, username: auth.username }));
    }

    function clearAuth() {
        storageRemove(TOKEN_KEY);
        storageRemove(USER_KEY);
    }

    // ---- navigation helpers ------------------------------------------------

    /** encodeURIComponent, but keeps "/" readable so next=/p/abc stays legible. */
    function encodePath(path) {
        return encodeURIComponent(path).replace(/%2F/gi, '/');
    }

    function currentPath() {
        return window.location.pathname + window.location.search;
    }

    function loginUrl(next) {
        return '/login?next=' + encodePath(next || currentPath());
    }

    function redirectToLogin(next) {
        window.location.assign(loginUrl(next));
    }

    /** Returns `next` only if it is a same-origin absolute path; otherwise "/". */
    function safeNext(next) {
        if (typeof next !== 'string' || next.charAt(0) !== '/' || next.charAt(1) === '/' || next.charAt(1) === '\\') {
            return '/';
        }
        try {
            var url = new URL(next, window.location.origin);
            if (url.origin !== window.location.origin) return '/';
            return url.pathname + url.search + url.hash;
        } catch (e) {
            return '/';
        }
    }

    function nextParam() {
        return new URLSearchParams(window.location.search).get('next');
    }

    /** Redirects to login when there is no token. Returns true when logged in. */
    function requireLogin() {
        if (isLoggedIn()) return true;
        clearAuth();
        redirectToLogin();
        return false;
    }

    function logout() {
        clearAuth();
        window.location.assign('/login');
    }

    // ---- fetch wrapper -----------------------------------------------------

    function ApiError(data, status) {
        data = data || {};
        this.name = 'ApiError';
        this.status = typeof data.status === 'number' ? data.status : status;
        this.error = data.error || '';
        this.message = data.message || defaultMessage(status);
        this.fieldErrors = Array.isArray(data.fieldErrors) ? data.fieldErrors : [];
    }
    ApiError.prototype = Object.create(Error.prototype);
    ApiError.prototype.constructor = ApiError;

    function defaultMessage(status) {
        if (status === 0) return 'Could not reach the server. Check your connection and try again.';
        if (status === 401) return 'Please log in to continue.';
        if (status === 403) return 'You don’t have access to this.';
        if (status === 404) return 'Not found.';
        if (status >= 500) return 'Something went wrong on the server. Try again in a moment.';
        return 'Request failed (' + status + ').';
    }

    /**
     * request(method, path, body?, options?)
     *   path is relative to /api/v1.
     *   options.redirectOn401 (default true): clear the token and go to /login on 401.
     *   options.auth (default true): send the bearer token if we have one.
     * Resolves with parsed JSON (or null); rejects with ApiError {status,error,message,fieldErrors}.
     */
    function request(method, path, body, options) {
        options = options || {};
        var headers = { 'Accept': 'application/json' };
        var init = { method: method, headers: headers };
        if (body !== undefined && body !== null) {
            headers['Content-Type'] = 'application/json';
            init.body = JSON.stringify(body);
        }
        var token = getToken();
        if (token && options.auth !== false) {
            headers['Authorization'] = 'Bearer ' + token;
        }

        return fetch(API_BASE + path, init).then(function (res) {
            return res.text().then(function (text) {
                var data = null;
                if (text) {
                    try { data = JSON.parse(text); } catch (e) { data = null; }
                }
                if (res.ok) return data;

                var err = new ApiError(data, res.status);
                if (res.status === 401 && options.redirectOn401 !== false) {
                    clearAuth();
                    redirectToLogin();
                }
                throw err;
            });
        }, function () {
            throw new ApiError(null, 0);
        });
    }

    var api = {
        get: function (path, opts) { return request('GET', path, undefined, opts); },
        post: function (path, body, opts) { return request('POST', path, body, opts); },
        put: function (path, body, opts) { return request('PUT', path, body, opts); },
        del: function (path, opts) { return request('DELETE', path, undefined, opts); }
    };

    // ---- UI helpers --------------------------------------------------------

    var toastTimer = null;

    function toast(message, kind) {
        var el = document.querySelector('[data-testid="toast"]');
        if (!el) return;
        window.clearTimeout(toastTimer);
        el.className = 'toast' + (kind === 'error' ? ' toast--error' : '');
        el.hidden = false;
        el.textContent = message;
        // Force reflow so the entrance transition replays on repeated toasts.
        void el.offsetWidth;
        el.classList.add('is-visible');
        toastTimer = window.setTimeout(function () {
            el.classList.remove('is-visible');
            toastTimer = window.setTimeout(function () {
                el.hidden = true;
                el.textContent = '';
            }, 250);
        }, 2800);
    }

    function copyToClipboard(text) {
        if (navigator.clipboard && window.isSecureContext) {
            return navigator.clipboard.writeText(text).then(function () { return true; }, function () {
                return legacyCopy(text);
            });
        }
        return Promise.resolve(legacyCopy(text));
    }

    function legacyCopy(text) {
        var ta = document.createElement('textarea');
        ta.value = text;
        ta.setAttribute('readonly', '');
        ta.style.position = 'fixed';
        ta.style.top = '-1000px';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        var ok = false;
        try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
        document.body.removeChild(ta);
        return ok;
    }

    /** Small DOM builder. Children may be nodes or strings (strings become text nodes, never HTML). */
    function el(tag, attrs, children) {
        var node = document.createElement(tag);
        if (attrs) {
            Object.keys(attrs).forEach(function (key) {
                var value = attrs[key];
                if (value === null || value === undefined || value === false) return;
                if (key === 'text') node.textContent = value;
                else if (key === 'className') node.className = value;
                else if (key === 'dataset') Object.keys(value).forEach(function (k) { node.dataset[k] = value[k]; });
                else if (value === true) node.setAttribute(key, '');
                else node.setAttribute(key, String(value));
            });
        }
        (children || []).forEach(function (child) {
            if (child === null || child === undefined || child === false) return;
            node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
        });
        return node;
    }

    /** Disables a button while a request runs and swaps its label. Returns a restore function. */
    function setBusy(button, busyLabel) {
        var original = button.textContent;
        button.disabled = true;
        button.setAttribute('aria-busy', 'true');
        if (busyLabel) button.textContent = busyLabel;
        return function restore(label) {
            button.disabled = false;
            button.removeAttribute('aria-busy');
            button.textContent = label || original;
        };
    }

    /** Shows an ApiError (or string) in an error box, including field errors as a list. */
    function showError(box, err, extraNode) {
        if (!box) return;
        box.textContent = '';
        if (!err) {
            box.hidden = true;
            return;
        }
        var message = typeof err === 'string' ? err : err.message;
        box.appendChild(el('p', { className: 'alert__message', text: message }));
        var fields = (err && err.fieldErrors) || [];
        if (fields.length) {
            var list = el('ul', { className: 'alert__list' });
            fields.forEach(function (f) {
                list.appendChild(el('li', { text: humanField(f.field) + ': ' + f.message }));
            });
            box.appendChild(list);
        }
        if (extraNode) box.appendChild(extraNode);
        box.hidden = false;
    }

    function humanField(field) {
        if (!field) return 'Field';
        var m = /^options\[(\d+)\](?:\.text)?$/.exec(field);
        if (m) return 'Answer ' + (Number(m[1]) + 1);
        if (field === 'options') return 'Answers';
        return field.charAt(0).toUpperCase() + field.slice(1);
    }

    function formatDate(iso) {
        if (!iso) return '';
        var d = new Date(iso);
        if (isNaN(d.getTime())) return '';
        return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
    }

    function plural(n, one, many) {
        return n + ' ' + (n === 1 ? one : many);
    }

    function shareUrl(shareId) {
        return window.location.origin + '/p/' + encodeURIComponent(shareId);
    }

    // ---- nav ---------------------------------------------------------------

    function renderNav() {
        var user = isLoggedIn() ? getUser() : null;
        document.querySelectorAll('[data-auth]').forEach(function (node) {
            node.hidden = node.getAttribute('data-auth') === 'in' ? !user : !!user;
        });
        var name = document.querySelector('[data-testid="nav-username"]');
        if (name) name.textContent = user ? user.username : '';

        var login = document.querySelector('[data-testid="nav-login"]');
        if (login) {
            var path = window.location.pathname;
            var onAuthPage = path === '/login' || path === '/register';
            login.setAttribute('href', onAuthPage ? '/login' + window.location.search : loginUrl());
        }
    }

    function initNav() {
        renderNav();
        var btn = document.querySelector('[data-testid="logout-btn"]');
        if (btn) btn.addEventListener('click', logout);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initNav);
    } else {
        initNav();
    }

    window.PollApp = {
        api: api,
        ApiError: ApiError,
        getToken: getToken,
        getUser: getUser,
        isLoggedIn: isLoggedIn,
        saveAuth: saveAuth,
        clearAuth: clearAuth,
        requireLogin: requireLogin,
        logout: logout,
        loginUrl: loginUrl,
        redirectToLogin: redirectToLogin,
        safeNext: safeNext,
        nextParam: nextParam,
        encodePath: encodePath,
        copyToClipboard: copyToClipboard,
        toast: toast,
        el: el,
        setBusy: setBusy,
        showError: showError,
        formatDate: formatDate,
        plural: plural,
        shareUrl: shareUrl,
        renderNav: renderNav
    };
})();
