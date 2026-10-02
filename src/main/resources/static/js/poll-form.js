/* Create (/polls/new) and edit (/polls/{id}/edit) poll form. */
(function () {
    'use strict';
    var P = window.PollApp;
    var el = P.el;

    var MIN_OPTIONS = 2;
    var MAX_OPTIONS = 10;
    var MAX_QUESTION = 300;
    var MAX_OPTION = 100;

    if (!P.requireLogin()) return;

    var main = document.getElementById('main');
    var pollId = main.getAttribute('data-poll-id');
    var isEdit = !!pollId;

    var form = document.getElementById('poll-form');
    var questionInput = form.querySelector('[data-testid="question-input"]');
    var questionCount = document.getElementById('question-count');
    var list = document.getElementById('option-list');
    var addBtn = form.querySelector('[data-testid="add-option-btn"]');
    var saveBtn = form.querySelector('[data-testid="save-btn"]');
    var errorBox = form.querySelector('[data-testid="error-msg"]');
    var loading = document.getElementById('form-loading');
    var cancelLink = document.getElementById('cancel-link');

    var version = null;
    var rowSeq = 0;

    // ---- rows --------------------------------------------------------------

    function rows() {
        return Array.prototype.slice.call(list.querySelectorAll('[data-testid="option-row"]'));
    }

    function createRow(option) {
        rowSeq += 1;
        var inputId = 'option-' + rowSeq;
        var votes = option && typeof option.voteCount === 'number' ? option.voteCount : 0;
        var hasVotes = votes > 0;

        var label = el('label', { className: 'option-row__label', for: inputId }, ['Answer']);
        var input = el('input', {
            id: inputId,
            type: 'text',
            className: 'input option-row__input',
            'data-testid': 'option-input',
            maxlength: String(MAX_OPTION),
            autocomplete: 'off',
            required: true
        });
        input.value = option ? option.text : '';

        var removeBtn = el('button', {
            type: 'button',
            className: 'icon-btn option-row__remove',
            'data-testid': 'remove-option-btn'
        }, [el('span', { 'aria-hidden': 'true', className: 'icon-btn__glyph' }, ['×'])]);

        var children = [label, el('div', { className: 'option-row__control' }, [input, removeBtn])];

        if (option && option.optionId != null) {
            var hintId = inputId + '-hint';
            var hint = el('p', { className: 'option-row__hint', id: hintId }, [
                el('span', { className: 'option-row__votes' }, [P.plural(votes, 'vote', 'votes')]),
                hasVotes ? ', so it can’t be removed. You can still reword it.' : ''
            ]);
            children.push(hint);
            input.setAttribute('aria-describedby', hintId);
            if (hasVotes) removeBtn.setAttribute('aria-describedby', hintId);
        }

        var li = el('li', {
            className: 'option-row' + (hasVotes ? ' option-row--locked' : ''),
            'data-testid': 'option-row'
        }, children);
        if (option && option.optionId != null) li.setAttribute('data-option-id', String(option.optionId));
        li.setAttribute('data-votes', String(votes));

        removeBtn.addEventListener('click', function () {
            if (removeBtn.disabled) return;
            var all = rows();
            var index = all.indexOf(li);
            li.remove();
            refresh();
            var remaining = rows();
            var focusRow = remaining[Math.min(index, remaining.length - 1)];
            if (focusRow) focusRow.querySelector('input').focus();
            else addBtn.focus();
        });

        input.addEventListener('keydown', function (event) {
            if (event.key !== 'Enter') return;
            event.preventDefault();
            var all = rows();
            var index = all.indexOf(li);
            if (index < all.length - 1) {
                all[index + 1].querySelector('input').focus();
            } else if (all.length < MAX_OPTIONS) {
                addRow().querySelector('input').focus();
            }
        });
        input.addEventListener('input', function () { input.removeAttribute('aria-invalid'); });

        return li;
    }

    function addRow(option) {
        var li = createRow(option);
        list.appendChild(li);
        refresh();
        return li;
    }

    /** Renumbers labels and updates which add/remove buttons are enabled. */
    function refresh() {
        var all = rows();
        var atMin = all.length <= MIN_OPTIONS;
        all.forEach(function (li, i) {
            var n = i + 1;
            li.querySelector('.option-row__label').textContent = 'Answer ' + n;
            var input = li.querySelector('input');
            input.setAttribute('placeholder', n === 1 ? 'Tacos' : n === 2 ? 'Ramen' : 'Another answer');
            var btn = li.querySelector('[data-testid="remove-option-btn"]');
            var locked = Number(li.getAttribute('data-votes')) > 0;
            btn.disabled = locked || atMin;
            btn.setAttribute('aria-label', 'Remove answer ' + n);
            btn.title = locked ? 'This answer has votes, so it can’t be removed'
                : atMin ? 'A poll needs at least ' + MIN_OPTIONS + ' answers' : 'Remove answer ' + n;
        });
        addBtn.disabled = all.length >= MAX_OPTIONS;
        addBtn.textContent = all.length >= MAX_OPTIONS ? 'Maximum of 10 answers' : 'Add answer';
    }

    function updateCount() {
        questionCount.textContent = questionInput.value.length + ' / ' + MAX_QUESTION;
    }

    addBtn.addEventListener('click', function () {
        if (rows().length >= MAX_OPTIONS) return;
        addRow().querySelector('input').focus();
    });
    questionInput.addEventListener('input', function () {
        updateCount();
        questionInput.removeAttribute('aria-invalid');
    });

    // ---- validation --------------------------------------------------------

    function collect() {
        return {
            question: questionInput.value.trim(),
            options: rows().map(function (li) {
                var id = li.getAttribute('data-option-id');
                return { id: id ? Number(id) : null, text: li.querySelector('input').value.trim(), input: li.querySelector('input') };
            })
        };
    }

    function validate(data) {
        var errors = [];
        var firstInvalid = null;
        function flag(input, field, message) {
            input.setAttribute('aria-invalid', 'true');
            errors.push({ field: field, message: message });
            if (!firstInvalid) firstInvalid = input;
        }
        if (!data.question) flag(questionInput, 'question', 'enter a question');
        else if (data.question.length > MAX_QUESTION) flag(questionInput, 'question', 'keep it to ' + MAX_QUESTION + ' characters');

        var seen = {};
        data.options.forEach(function (opt, i) {
            var field = 'options[' + i + ']';
            if (!opt.text) flag(opt.input, field, 'enter an answer or remove this row');
            else if (opt.text.length > MAX_OPTION) flag(opt.input, field, 'keep it to ' + MAX_OPTION + ' characters');
            else {
                var key = opt.text.toLowerCase();
                if (seen[key]) flag(opt.input, field, 'this answer is a duplicate');
                seen[key] = true;
            }
        });
        if (data.options.length < MIN_OPTIONS || data.options.length > MAX_OPTIONS) {
            errors.push({ field: 'options', message: 'add between ' + MIN_OPTIONS + ' and ' + MAX_OPTIONS + ' answers' });
            if (!firstInvalid) firstInvalid = addBtn;
        }
        return { errors: errors, focus: firstInvalid };
    }

    function reloadAction() {
        var btn = el('button', { type: 'button', className: 'btn btn--secondary btn--small alert__action', 'data-testid': 'reload-btn' }, ['Reload']);
        btn.addEventListener('click', function () { window.location.reload(); });
        return btn;
    }

    // ---- submit ------------------------------------------------------------

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        if (saveBtn.disabled) return;

        var data = collect();
        var check = validate(data);
        if (check.errors.length) {
            P.showError(errorBox, { message: 'Check the highlighted fields.', fieldErrors: check.errors });
            if (check.focus) check.focus.focus();
            return;
        }
        P.showError(errorBox, null);

        var request;
        if (isEdit) {
            request = P.api.put('/polls/' + encodeURIComponent(pollId), {
                question: data.question,
                version: version,
                options: data.options.map(function (o) { return { id: o.id, text: o.text }; })
            });
        } else {
            request = P.api.post('/polls', {
                question: data.question,
                options: data.options.map(function (o) { return o.text; })
            });
        }

        var restore = P.setBusy(saveBtn, isEdit ? 'Saving…' : 'Creating…');
        addBtn.disabled = true;

        request.then(function (poll) {
            window.location.assign('/p/' + encodeURIComponent(poll.shareId));
        }).catch(function (err) {
            restore();
            refresh();
            if (err.status === 401) return; // redirecting to login
            var extra = null;
            if (err.status === 409 && isEdit) {
                extra = reloadAction();
            }
            P.showError(errorBox, err, extra);
            errorBox.scrollIntoView({ block: 'nearest' });
            if (extra) extra.focus();
        });
    });

    // ---- init --------------------------------------------------------------

    function startBlank() {
        addRow();
        addRow();
        updateCount();
    }

    if (!isEdit) {
        startBlank();
        return;
    }

    form.hidden = true;
    loading.hidden = false;
    P.api.get('/polls/' + encodeURIComponent(pollId))
        .then(function (poll) {
            version = poll.version;
            questionInput.value = poll.question || '';
            updateCount();
            list.textContent = '';
            (poll.options || []).forEach(function (opt) { addRow(opt); });
            while (rows().length < MIN_OPTIONS) addRow();
            cancelLink.setAttribute('href', '/p/' + encodeURIComponent(poll.shareId));
            loading.hidden = true;
            form.hidden = false;
        })
        .catch(function (err) {
            loading.hidden = true;
            if (err.status === 401) return;
            var message = err.status === 403 ? 'Only the person who created this poll can edit it.'
                : err.status === 404 ? 'This poll doesn’t exist.' : 'Couldn’t load this poll. ' + err.message;
            form.hidden = false;
            Array.prototype.forEach.call(form.querySelectorAll('.field, .form__actions'), function (n) { n.hidden = true; });
            var back = el('a', { href: '/', className: 'btn btn--secondary btn--small alert__action' }, ['Back to my polls']);
            P.showError(errorBox, message, back);
        });
})();
