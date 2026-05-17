(function () {
    const qs = new URLSearchParams(window.location.search);
    const sessionUuid = qs.get('sessionUuid');
    const requestedEventId = qs.get('eventId');

    const reasoningEl = document.getElementById('reasoning');
    const argsEl = document.getElementById('tool-args');
    const feedbackEl = document.getElementById('feedback');

    const actionButtons = {
        ONCE: document.getElementById('once-btn'),
        SESSION: document.getElementById('session-btn'),
        ALWAYS: document.getElementById('always-btn')
    };

    let activeEventId = requestedEventId;

    function setButtonsEnabled(enabled) {
        Object.values(actionButtons).forEach(function (btn) {
            if (!btn) return;
            btn.disabled = !enabled;
        });
    }

    function escapeHtml(value) {
        return String(value || '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
    }

    function showFeedback(message, isOk) {
        feedbackEl.classList.remove('d-none', 'feedback-ok', 'feedback-error');
        feedbackEl.classList.add(isOk ? 'feedback-ok' : 'feedback-error');
        feedbackEl.textContent = message;
    }

    function pendingUrl() {
        const base = '/api/chat/authorize/' + encodeURIComponent(sessionUuid) + '/pending';
        if (!requestedEventId) {
            return base;
        }
        return base + '?eventId=' + encodeURIComponent(requestedEventId);
    }

    function loadPending() {
        if (!sessionUuid) {
            showFeedback('Missing sessionUuid in URL. Open this page with ?sessionUuid=<id>.', false);
            setButtonsEnabled(false);
            return;
        }

        fetch(pendingUrl())
            .then(function (resp) {
                return resp.json().then(function (json) {
                    return { ok: resp.ok, body: json };
                });
            })
            .then(function (result) {
                if (!result.ok) {
                    showFeedback(result.body.message || 'No pending authorization found.', false);
                    setButtonsEnabled(false);
                    argsEl.textContent = '{}';
                    reasoningEl.textContent = '';
                    return;
                }

                const body = result.body;
                activeEventId = body.eventId || requestedEventId;

                reasoningEl.textContent = body.reasoning || 'No supervisor reasoning provided.';
                argsEl.textContent = body.displayArguments || body.arguments || '{}';

                const allowed = new Set(Array.isArray(body.actions) ? body.actions.map(function (a) {
                    return String(a || '').toUpperCase();
                }) : ['ONCE', 'SESSION', 'ALWAYS']);

                Object.entries(actionButtons).forEach(function (entry) {
                    const action = entry[0];
                    const btn = entry[1];
                    if (!btn) return;
                    btn.classList.toggle('d-none', !allowed.has(action));
                });

                setButtonsEnabled(true);
            })
            .catch(function (err) {
                showFeedback('Failed to load authorization details: ' + err.message, false);
                setButtonsEnabled(false);
            });
    }

    function submitAction(action) {
        if (!sessionUuid) {
            return;
        }

        setButtonsEnabled(false);
        showFeedback('Submitting authorization decision...', true);

        fetch('/api/chat/respond/' + encodeURIComponent(sessionUuid), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                eventId: activeEventId,
                action: action,
                fields: { source: 'authorize-page' }
            })
        })
            .then(function (resp) {
                return resp.json().then(function (json) {
                    return { ok: resp.ok, body: json };
                });
            })
            .then(function (result) {
                if (!result.ok) {
                    const message = (result.body && result.body.message) ? result.body.message : 'Authorization request failed.';
                    showFeedback(message, false);
                    setButtonsEnabled(true);
                    return;
                }

                const status = result.body.status || 'OK';
                showFeedback('Authorization submitted successfully. You can close this page.', true);
            })
            .catch(function (err) {
                showFeedback('Failed to submit authorization: ' + err.message, false);
                setButtonsEnabled(true);
            });
    }

    Object.entries(actionButtons).forEach(function (entry) {
        const action = entry[0];
        const button = entry[1];
        if (!button) {
            return;
        }
        button.addEventListener('click', function () {
            submitAction(action);
        });
    });

    // Keep markdown/code safe while still readable in summary sections.
    argsEl.innerHTML = escapeHtml(argsEl.textContent);
    loadPending();
})();
