/* My polls ("/"): lists the logged-in user's polls. */
(function () {
    'use strict';
    var P = window.PollApp;
    var el = P.el;

    if (!P.requireLogin()) return;

    var list = document.getElementById('poll-list');
    var loading = document.getElementById('polls-loading');
    var errorBox = document.getElementById('polls-error');
    var empty = document.querySelector('[data-testid="empty-state"]');

    function row(poll) {
        var link = P.shareUrl(poll.shareId);
        var copyBtn = el('button', {
            type: 'button',
            className: 'btn btn--quiet btn--small',
            'data-testid': 'copy-link-btn',
            'aria-label': 'Copy link to “' + poll.question + '”'
        }, ['Copy link']);
        copyBtn.addEventListener('click', function () {
            P.copyToClipboard(link).then(function (ok) {
                P.toast(ok ? 'Link copied' : 'Couldn’t copy. The link is ' + link, ok ? '' : 'error');
            });
        });

        var meta = [
            el('span', { className: 'poll-row__votes', 'data-testid': 'poll-row-votes' },
                [P.plural(poll.totalVotes || 0, 'vote', 'votes')]),
            el('span', { className: 'poll-row__date' }, [
                'Created ',
                el('time', { datetime: poll.createdAt || '' }, [P.formatDate(poll.createdAt)])
            ])
        ];
        if (poll.status === 'CLOSED') {
            meta.push(el('span', { className: 'badge badge--closed' }, ['Closed']));
        }

        return el('li', { className: 'poll-row', 'data-testid': 'poll-row', dataset: { pollId: String(poll.id) } }, [
            el('div', { className: 'poll-row__main' }, [
                el('a', {
                    className: 'poll-row__question',
                    href: '/p/' + encodeURIComponent(poll.shareId),
                    'data-testid': 'poll-row-question'
                }, [poll.question]),
                el('p', { className: 'poll-row__meta' }, meta)
            ]),
            el('div', { className: 'poll-row__actions' }, [
                el('a', {
                    className: 'btn btn--secondary btn--small',
                    href: '/p/' + encodeURIComponent(poll.shareId),
                    'data-testid': 'open-link'
                }, ['Open']),
                el('a', {
                    className: 'btn btn--quiet btn--small',
                    href: '/polls/' + encodeURIComponent(poll.id) + '/edit',
                    'data-testid': 'edit-link'
                }, ['Edit']),
                copyBtn
            ])
        ]);
    }

    P.api.get('/users/me/polls')
        .then(function (polls) {
            loading.hidden = true;
            polls = Array.isArray(polls) ? polls : [];
            if (!polls.length) {
                empty.hidden = false;
                return;
            }
            list.textContent = '';
            polls.forEach(function (poll) { list.appendChild(row(poll)); });
            list.hidden = false;
        })
        .catch(function (err) {
            loading.hidden = true;
            if (err.status === 401) return; // redirecting to login
            P.showError(errorBox, 'Couldn’t load your polls. ' + err.message);
        });
})();
