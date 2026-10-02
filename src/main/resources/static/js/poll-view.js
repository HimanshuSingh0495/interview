/* Public poll page (/p/{shareId}): ballot, pie chart, results list, sharing. */
(function () {
    'use strict';
    var P = window.PollApp;
    var el = P.el;

    var main = document.getElementById('main');
    var shareId = main.getAttribute('data-share-id');

    var loading = document.getElementById('poll-loading');
    var pageError = document.getElementById('poll-error');
    var article = document.getElementById('poll');
    var questionEl = document.querySelector('[data-testid="poll-question"]');
    var creatorEl = document.getElementById('poll-creator');
    var badge = document.getElementById('status-badge');
    var copyBtn = document.querySelector('[data-testid="copy-link-btn"]');
    var shareBtn = document.querySelector('[data-testid="share-btn"]');
    var editLink = document.querySelector('[data-testid="edit-poll-link"]');
    var form = document.getElementById('vote-form');
    var choices = document.getElementById('choices');
    var voteBtn = document.querySelector('[data-testid="vote-btn"]');
    var loginLink = document.querySelector('[data-testid="login-to-vote-link"]');
    var statusMsg = document.querySelector('[data-testid="status-msg"]');
    var totalEl = document.querySelector('[data-testid="total-votes"]');
    var canvas = document.querySelector('[data-testid="results-chart"]');
    var chartEmpty = document.getElementById('chart-empty');
    var resultList = document.getElementById('result-list');

    var link = P.shareUrl(shareId);
    var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    var state = {
        poll: null,
        myVote: null,      // optionId of the current user's vote, or null
        loggedIn: P.isLoggedIn(),
        busy: false
    };
    var chart = null;

    // ---- helpers -----------------------------------------------------------

    function palette() {
        var styles = window.getComputedStyle(document.documentElement);
        var colors = [];
        for (var i = 1; i <= 10; i++) {
            colors.push(styles.getPropertyValue('--c' + i).trim() || '#888');
        }
        return colors;
    }

    function colorFor(index) {
        var colors = palette();
        return colors[index % colors.length];
    }

    function percent(count, total) {
        return total > 0 ? Math.round((count / total) * 100) : 0;
    }

    function setStatus(message, kind) {
        statusMsg.textContent = message || '';
        statusMsg.className = 'status-msg' + (kind ? ' status-msg--' + kind : '');
    }

    function isClosed() {
        return state.poll && state.poll.status === 'CLOSED';
    }

    function selectedOptionId() {
        var checked = choices.querySelector('input[type="radio"]:checked');
        return checked ? Number(checked.value) : null;
    }

    function isOwner() {
        var user = P.getUser();
        return !!(state.loggedIn && user && state.poll && state.poll.creatorUsername &&
            String(user.username).toLowerCase() === String(state.poll.creatorUsername).toLowerCase());
    }

    // ---- ballot ------------------------------------------------------------

    function renderChoices() {
        var poll = state.poll;
        var keep = selectedOptionId();
        var preselect = keep != null ? keep : state.myVote;
        var disabled = !state.loggedIn || isClosed() || state.busy;

        choices.textContent = '';
        poll.options.forEach(function (opt, i) {
            var id = 'choice-' + opt.optionId;
            var radio = el('input', {
                type: 'radio',
                name: 'optionId',
                id: id,
                value: String(opt.optionId),
                className: 'choice__radio',
                'data-testid': 'option-radio'
            });
            radio.checked = preselect != null && Number(preselect) === opt.optionId;
            radio.disabled = disabled;
            radio.addEventListener('change', updateVoteButton);

            var label = el('label', { className: 'choice', for: id }, [
                radio,
                el('span', { className: 'choice__mark', 'aria-hidden': 'true' }),
                el('span', { className: 'choice__swatch', 'aria-hidden': 'true', style: 'background:' + colorFor(i) }),
                el('span', { className: 'choice__text' }, [opt.text]),
                state.myVote === opt.optionId ? el('span', { className: 'choice__mine' }, ['Your vote']) : null
            ]);
            choices.appendChild(label);
        });
    }

    function updateVoteButton() {
        if (!state.loggedIn) {
            voteBtn.hidden = true;
            loginLink.hidden = false;
            loginLink.setAttribute('href', P.loginUrl('/p/' + shareId));
            return;
        }
        loginLink.hidden = true;
        voteBtn.hidden = false;
        if (!state.busy) voteBtn.textContent = state.myVote != null ? 'Change vote' : 'Vote';
        voteBtn.disabled = state.busy || isClosed() || selectedOptionId() == null;
    }

    function setBusy(busy) {
        state.busy = busy;
        choices.querySelectorAll('input').forEach(function (r) {
            r.disabled = busy || isClosed() || !state.loggedIn;
        });
        if (busy) voteBtn.setAttribute('aria-busy', 'true');
        else voteBtn.removeAttribute('aria-busy');
        updateVoteButton();
    }

    // ---- results -----------------------------------------------------------

    function renderResults() {
        var poll = state.poll;
        var total = poll.totalVotes != null ? poll.totalVotes
            : poll.options.reduce(function (sum, o) { return sum + (o.voteCount || 0); }, 0);

        totalEl.textContent = P.plural(total, 'vote', 'votes');

        resultList.textContent = '';
        poll.options.forEach(function (opt, i) {
            var count = opt.voteCount || 0;
            var pct = percent(count, total);
            var color = colorFor(i);
            var mine = state.myVote === opt.optionId;
            var li = el('li', {
                className: 'result-row' + (mine ? ' result-row--mine' : ''),
                'data-testid': 'result-row',
                'data-option-id': String(opt.optionId)
            }, [
                el('div', { className: 'result-row__line' }, [
                    el('span', { className: 'result-row__swatch', 'aria-hidden': 'true', style: 'background:' + color }),
                    el('span', { className: 'result-row__text', 'data-testid': 'result-row-text' }, [
                        opt.text,
                        mine ? el('span', { className: 'result-row__mine' }, ['Your vote']) : null
                    ]),
                    el('span', { className: 'result-row__nums' }, [
                        el('span', { className: 'result-row__count', 'data-testid': 'result-row-count' }, [P.plural(count, 'vote', 'votes')]),
                        el('span', { className: 'result-row__pct', 'data-testid': 'result-row-pct' }, [pct + '%'])
                    ])
                ]),
                el('div', { className: 'result-row__track', 'aria-hidden': 'true' }, [
                    el('span', { className: 'result-row__bar', style: 'width:' + pct + '%;background:' + color })
                ])
            ]);
            resultList.appendChild(li);
        });

        renderChart(total);
    }

    function renderChart(total) {
        var poll = state.poll;
        var summary = poll.options.map(function (o) {
            return o.text + ': ' + (o.voteCount || 0) + ' (' + percent(o.voteCount || 0, total) + '%)';
        }).join('; ');
        canvas.setAttribute('aria-label', 'Pie chart of ' + P.plural(total, 'vote', 'votes') + '. ' + summary);

        if (total === 0) {
            canvas.hidden = true;
            chartEmpty.hidden = false;
            canvas.setAttribute('data-total', '0');
            return;
        }
        chartEmpty.hidden = true;
        canvas.hidden = false;
        canvas.setAttribute('data-total', String(total));

        if (typeof window.Chart !== 'function') return; // library failed to load; list still shows results

        var labels = poll.options.map(function (o) { return o.text; });
        var data = poll.options.map(function (o) { return o.voteCount || 0; });
        var colors = poll.options.map(function (o, i) { return colorFor(i); });
        var surface = window.getComputedStyle(document.documentElement).getPropertyValue('--surface').trim() || '#fff';

        if (chart) {
            chart.data.labels = labels;
            chart.data.datasets[0].data = data;
            chart.data.datasets[0].backgroundColor = colors;
            chart.update();
            return;
        }
        chart = new window.Chart(canvas, {
            type: 'pie',
            data: {
                labels: labels,
                datasets: [{
                    data: data,
                    backgroundColor: colors,
                    borderColor: surface,
                    borderWidth: 3,
                    hoverOffset: 6
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: true,
                aspectRatio: 1,
                animation: reduceMotion ? false : { duration: 500 },
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        callbacks: {
                            label: function (ctx) {
                                var sum = ctx.dataset.data.reduce(function (a, b) { return a + b; }, 0);
                                return ' ' + P.plural(ctx.parsed, 'vote', 'votes') + ' (' + percent(ctx.parsed, sum) + '%)';
                            }
                        }
                    }
                }
            }
        });
    }

    // ---- page --------------------------------------------------------------

    function renderPoll() {
        var poll = state.poll;
        document.title = poll.question + ' | Show of Hands';
        questionEl.textContent = poll.question;
        creatorEl.textContent = poll.creatorUsername ? 'Asked by ' + poll.creatorUsername : '';
        badge.hidden = !isClosed();
        if (isOwner()) {
            editLink.hidden = false;
            editLink.setAttribute('href', '/polls/' + encodeURIComponent(poll.id) + '/edit');
        } else {
            editLink.hidden = true;
        }
        renderChoices();
        updateVoteButton();
        renderResults();
        if (isClosed()) setStatus('This poll is closed. You can still see the results.');
    }

    function fetchPoll() {
        return P.api.get('/polls/share/' + encodeURIComponent(shareId), { redirectOn401: false });
    }

    function fetchMyVote(pollId) {
        if (!state.loggedIn) return Promise.resolve(null);
        return P.api.get('/polls/' + encodeURIComponent(pollId) + '/votes/me', { redirectOn401: false })
            .then(function (vote) { return vote ? vote.optionId : null; })
            .catch(function (err) {
                if (err.status === 404) return null;           // not voted yet
                if (err.status === 401) {                      // stale token: treat as logged out
                    P.clearAuth();
                    P.renderNav();
                    state.loggedIn = false;
                    return null;
                }
                throw err;
            });
    }

    function load() {
        fetchPoll()
            .then(function (poll) {
                state.poll = poll;
                return fetchMyVote(poll.id).catch(function () {
                    setStatus('Couldn’t load your current vote. Refresh to try again.', 'error');
                    return null;
                });
            })
            .then(function (myVote) {
                state.myVote = myVote;
                loading.hidden = true;
                article.hidden = false;
                renderPoll();
            })
            .catch(function (err) {
                loading.hidden = true;
                var message = err.status === 404
                    ? 'This poll doesn’t exist. Check the link you were sent.'
                    : 'Couldn’t load this poll. ' + err.message;
                var back = el('a', { href: '/', className: 'btn btn--secondary btn--small alert__action' }, ['Go to my polls']);
                P.showError(pageError, message, back);
            });
    }

    // ---- voting ------------------------------------------------------------

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        if (state.busy || !state.loggedIn || isClosed()) return;
        var optionId = selectedOptionId();
        if (optionId == null) {
            setStatus('Pick an answer first.', 'error');
            return;
        }

        var changing = state.myVote != null;
        var path = '/polls/' + encodeURIComponent(state.poll.id) + '/votes';
        setBusy(true);
        voteBtn.textContent = changing ? 'Changing…' : 'Voting…';
        setStatus('');

        var call = changing ? P.api.put(path, { optionId: optionId }) : P.api.post(path, { optionId: optionId });

        call.then(function (vote) {
            state.myVote = vote && vote.optionId != null ? vote.optionId : optionId;
            return fetchPoll().then(function (poll) {
                state.poll = poll;
            }).catch(function () { /* results refresh failed; vote itself succeeded */ })
                .then(function () {
                    setBusy(false);
                    renderPoll();
                    setStatus(changing ? 'Vote changed' : 'Vote saved', 'success');
                });
        }).catch(function (err) {
            if (err.status === 401) return; // redirecting to login
            // Resync with the server (e.g. voted in another tab, option removed, poll closed).
            return Promise.all([fetchPoll().catch(function () { return state.poll; }),
                fetchMyVote(state.poll.id).catch(function () { return state.myVote; })])
                .then(function (results) {
                    state.poll = results[0];
                    state.myVote = results[1];
                    setBusy(false);
                    renderPoll();
                    var hint = err.status === 409 && state.myVote != null && !changing
                        ? ' Your earlier vote is selected; use Change vote to switch.' : '';
                    setStatus(err.message + hint, 'error');
                });
        });
    });

    // ---- sharing -----------------------------------------------------------

    copyBtn.addEventListener('click', function () {
        P.copyToClipboard(link).then(function (ok) {
            P.toast(ok ? 'Link copied' : 'Couldn’t copy. The link is ' + link, ok ? '' : 'error');
        });
    });

    function mailtoHref() {
        var question = state.poll ? state.poll.question : 'a poll';
        return 'mailto:?subject=' + encodeURIComponent('Vote: ' + question) +
            '&body=' + encodeURIComponent(question + '\n\nVote here: ' + link);
    }

    shareBtn.addEventListener('click', function () {
        var question = state.poll ? state.poll.question : 'Poll';
        if (navigator.share) {
            navigator.share({ title: question, text: question, url: link }).catch(function (err) {
                if (err && err.name === 'AbortError') return; // user closed the share sheet
                window.location.href = mailtoHref();
            });
            return;
        }
        shareBtn.setAttribute('data-href', mailtoHref());
        window.location.href = mailtoHref();
    });

    load();
})();
