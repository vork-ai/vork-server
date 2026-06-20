/* chat-render.js — shared rendering helpers for chat and job-monitor pages.
 *
 * Usage:
 *   ChatRender.init({
 *     messagesArea      : Element,   // #messages-area container
 *     typingEl          : Element,   // #typing-indicator row
 *     getSessionUuid    : Function,  // () => string — current session UUID
 *     onPromptRequired  : Function,  // (frame) => void — handle PROMPT_REQUIRED
 *     onInputStateChange: Function,  // (enabled: boolean) => void — optional
 *     onAwaitingTerminal: Function,  // (on: boolean) => void — optional
 *   });
 *
 * global StompJs, SockJS, marked, Terminal, FitAddon
 */

/* global marked */

'use strict';

(function (global) {

    // ── Constants ─────────────────────────────────────────────────────────────
    const TERMINAL_ROW_PREFIX         = 'terminal-';
    const TERMINAL_REPLAY_MAX_CHARS   = 24000;
    const TERMINAL_REPLAY_TAIL_CHARS  = 12000;
    const TERMINAL_COLLAPSED_HEIGHT   = '1.6rem';
    const TERMINAL_END_GRACE_MS       = 160;
    const ANSI_ESCAPE_PATTERN         = /\u001B\[[0-9;?]*[ -/]*[@-~]/g;

    const textDecoder = new TextDecoder();
    const textEncoder = new TextEncoder();

    // ── Module state ─────────────────────────────────────────────────────────
    let ctx = null;

    const terminalState = {
        views           : new Map(),
        pendingByTerminal: new Map(),
        socketsByTerminal: new Map()
    };

    // ── Utilities ─────────────────────────────────────────────────────────────

    function escapeHtml(s) {
        return s
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function scrollBottom() {
        if (ctx && ctx.messagesArea) {
            ctx.messagesArea.scrollTop = ctx.messagesArea.scrollHeight;
        }
    }

    function showTyping(on) {
        if (!ctx || !ctx.typingEl) return;
        ctx.typingEl.classList.toggle('d-none', !on);
        if (on) scrollBottom();
    }

    function setInputEnabled(on) {
        if (ctx && typeof ctx.onInputStateChange === 'function') {
            ctx.onInputStateChange(on);
        }
    }

    function setAwaitingTerminal(on) {
        if (ctx && typeof ctx.onAwaitingTerminal === 'function') {
            ctx.onAwaitingTerminal(on);
        }
        showTyping(on);
        if (on) setInputEnabled(false);
    }

    // ── Message rendering ─────────────────────────────────────────────────────

    function isImage(mime) {
        return mime && mime.startsWith('image/');
    }

    function mimeIcon(mime) {
        if (!mime) return 'fa-file';
        if (mime.startsWith('image/'))       return 'fa-file-image';
        if (mime.startsWith('audio/'))       return 'fa-file-audio';
        if (mime.startsWith('video/'))       return 'fa-file-video';
        if (mime === 'application/pdf')      return 'fa-file-pdf';
        if (mime.startsWith('text/'))        return 'fa-file-lines';
        if (mime.includes('zip') || mime.includes('compressed')) return 'fa-file-zipper';
        if (mime.includes('word') || mime.includes('document'))  return 'fa-file-word';
        if (mime.includes('sheet') || mime.includes('excel'))    return 'fa-file-excel';
        return 'fa-file';
    }

    function renderAttachmentsHtml(attachments) {
        if (!attachments || attachments.length === 0) return '';
        let html = '<div class="bubble-attachments">';
        for (const att of attachments) {
            if (isImage(att.mimeType)) {
                html += '<img class="bubble-img-thumb" src="/api/files/' + att.uuid + '"'
                      + ' alt="' + escapeHtml(att.name) + '"'
                      + ' data-src="/api/files/' + att.uuid + '"'
                      + ' title="' + escapeHtml(att.name) + '">';
            } else {
                html += '<a class="bubble-file-link" href="/api/files/' + att.uuid + '"'
                      + ' download="' + escapeHtml(att.name) + '" target="_blank">'
                      + '<i class="fa-solid ' + mimeIcon(att.mimeType) + '"></i>'
                      + escapeHtml(att.name)
                      + '</a>';
            }
        }
        html += '</div>';
        return html;
    }

    function renderMessage(msg) {
        if (!ctx) return;
        const isUser    = msg.role === 'USER';
        const textHtml  = isUser
            ? escapeHtml(msg.content || '').replace(/\n/g, '<br>')
            : (typeof marked !== 'undefined' ? marked.parse(msg.content || '') : escapeHtml(msg.content || ''));
        const bubbleCls  = isUser ? 'user' : (msg.role === 'ERROR' ? 'error' : 'assistant');
        const avatarCls  = isUser ? 'user' : 'assistant';
        const avatarIcon = isUser
            ? '<i class="fa-solid fa-user"></i>'
            : '<i class="fa-solid fa-robot"></i>';
        const attachHtml = renderAttachmentsHtml(msg.attachments);
        const row = document.createElement('div');
        row.className = 'message-row' + (isUser ? ' user' : '');
        row.innerHTML =
            '<div class="avatar ' + avatarCls + '">' + avatarIcon + '</div>' +
            '<div class="bubble ' + bubbleCls + '">' + attachHtml + textHtml + '</div>';
        ctx.messagesArea.insertBefore(row, ctx.typingEl);
        scrollBottom();
    }

    function renderAgentTransition(text) {
        if (!ctx) return;
        const row = document.createElement('div');
        row.className = 'agent-transition-row';
        row.innerHTML = '<i class="fa-solid fa-gears" aria-hidden="true"></i><span>' + escapeHtml(text) + '</span>';
        ctx.messagesArea.insertBefore(row, ctx.typingEl);
        scrollBottom();
    }

    function renderSkillEvent(text) {
        if (!ctx) return;
        const row = document.createElement('div');
        row.className = 'agent-transition-row';
        row.innerHTML = '<i class="fa-solid fa-bolt" aria-hidden="true"></i><span>' + escapeHtml(text) + '</span>';
        ctx.messagesArea.insertBefore(row, ctx.typingEl);
        scrollBottom();
    }

    function renderThinkingEvent(text) {
        if (!ctx || !ctx.messagesArea) return;
        const row = document.createElement('div');
        row.className = 'thinking-row';
        const details = document.createElement('details');
        const summary = document.createElement('summary');
        summary.innerHTML =
            '<i class="fa-solid fa-brain" aria-hidden="true"></i>' +
            '<span>AI reasoning…</span>';
        const content = document.createElement('div');
        content.className = 'thinking-content';
        content.textContent = text;
        details.appendChild(summary);
        details.appendChild(content);
        row.appendChild(details);
        ctx.messagesArea.insertBefore(row, ctx.typingEl);
        scrollBottom();
    }

    function getPromptMode() {
        if (!ctx || !ctx.promptMode) return 'inline';
        return ctx.promptMode;
    }

    function isReadOnlyMode() {
        return !!(ctx && ctx.readOnly === true);
    }

    function sendAuthorizationAction(frame, action, fields, row) {
        if (!ctx || typeof ctx.getSessionUuid !== 'function') return;
        const sessionUuid = ctx.getSessionUuid();
        if (!sessionUuid) return;

        showTyping(true);
        setInputEnabled(false);

        fetch('/api/chat/respond/' + encodeURIComponent(sessionUuid), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                eventId: frame.eventId,
                intent: frame.intent || 'AUTHORIZE_TOOL',
                action: action,
                fields: fields || {}
            })
        })
            .then(function (resp) {
                if (!resp.ok) {
                    return resp.text().then(function (body) {
                        throw new Error(body || ('HTTP ' + resp.status));
                    });
                }
                return resp.text();
            })
            .then(function (body) {
                if (ctx && typeof ctx.onPromptSubmitted === 'function') {
                    let parsed = null;
                    try { parsed = body ? JSON.parse(body) : null; } catch (_) {}
                    ctx.onPromptSubmitted({ frame: frame, action: action, response: parsed });
                }
            })
            .catch(function (err) {
                if (row) {
                    row.querySelectorAll('.prompt-action-btn').forEach(function (btn) {
                        btn.disabled = false;
                    });
                }
                showTyping(false);
                setInputEnabled(true);
                renderMessage({
                    role: 'ERROR',
                    content: 'Failed to submit authorization response: ' + (err && err.message ? err.message : String(err))
                });
            });
    }

    function renderPromptRequiredFrame(frame) {
        if (!ctx || !ctx.messagesArea) return;
        const formSchema = frame && frame.formSchema ? frame.formSchema : {};
        const fields = Array.isArray(formSchema.fields) ? formSchema.fields : [];

        const row = document.createElement('div');
        row.className = 'message-row';
        row.innerHTML =
            '<div class="avatar assistant"><i class="fa-solid fa-lock"></i></div>' +
            '<div class="bubble assistant prompt-required">' +
            '  <div class="prompt-title"></div>' +
            '  <div class="prompt-meta"></div>' +
            '  <div class="prompt-fields"></div>' +
            '  <div class="prompt-actions"></div>' +
            '</div>';

        const titleEl = row.querySelector('.prompt-title');
        const metaEl = row.querySelector('.prompt-meta');
        const fieldsEl = row.querySelector('.prompt-fields');
        const actionsEl = row.querySelector('.prompt-actions');

        titleEl.textContent = formSchema.title || frame.intent || 'Authorization required';
        metaEl.textContent = frame.textResponse || 'Provide required input to continue.';

        fields.forEach(function (field) {
            if (!field || !field.name) return;
            const wrapper = document.createElement('div');
            wrapper.className = 'mb-2';
            const type = String(field.type || 'text').toLowerCase();
            const source = String(field.source || 'CONVERSATION').toUpperCase();

            if (type === 'hidden') {
                const hidden = document.createElement('input');
                hidden.type = 'hidden';
                hidden.setAttribute('data-field-name', field.name);
                hidden.value = (field.value != null ? field.value : field.placeholder) || '';
                fieldsEl.appendChild(hidden);
                return;
            }

            if (type === 'markdown') {
                const md = document.createElement('div');
                md.className = 'prompt-args markdown-body';
                md.innerHTML = (typeof marked !== 'undefined' && marked.parse)
                    ? marked.parse(field.value || field.defaultValue || field.placeholder || '')
                    : escapeHtml(field.value || field.defaultValue || field.placeholder || '');
                wrapper.appendChild(md);
                fieldsEl.appendChild(wrapper);
                return;
            }

            const label = document.createElement('label');
            label.className = 'form-label mb-1';
            label.textContent = field.label || field.name;
            wrapper.appendChild(label);

            let input;
            if (type === 'textarea') {
                input = document.createElement('textarea');
                input.rows = 4;
                input.className = 'form-control form-control-sm';
            } else if (type === 'select') {
                input = document.createElement('select');
                input.className = 'form-select form-select-sm';
                (Array.isArray(field.options) ? field.options : []).forEach(function (opt) {
                    const o = document.createElement('option');
                    o.value = opt.value;
                    o.textContent = opt.label;
                    input.appendChild(o);
                });
            } else if (type === 'checkbox') {
                input = document.createElement('input');
                input.type = 'checkbox';
                input.className = 'form-check-input';
            } else if (type === 'readonly') {
                input = document.createElement('input');
                input.type = 'text';
                input.className = 'form-control form-control-sm';
                input.readOnly = true;
                input.value = (field.value != null ? field.value : field.placeholder) || '';
            } else {
                input = document.createElement('input');
                input.type = (type === 'password') ? 'password' : 'text';
                input.className = 'form-control form-control-sm';
            }

            input.setAttribute('data-field-name', field.name);
            if (field.placeholder && input.type !== 'checkbox') input.placeholder = field.placeholder;
            if (field.value != null) {
                if (input.type === 'checkbox') {
                    input.checked = String(field.value).toLowerCase() === 'true';
                } else {
                    input.value = field.value;
                }
            } else if (field.defaultValue != null) {
                if (input.type === 'checkbox') {
                    input.checked = String(field.defaultValue).toLowerCase() === 'true';
                } else {
                    input.value = field.defaultValue;
                }
            } else if (source === 'CONTEXT' && type !== 'password') {
                if (input.type === 'checkbox') {
                    input.checked = String(field.placeholder || '').toLowerCase() === 'true';
                } else if (field.placeholder) {
                    input.value = field.placeholder;
                }
            }
            if (field.required) input.setAttribute('required', 'required');

            wrapper.appendChild(input);
            fieldsEl.appendChild(wrapper);
        });

        const actions = Array.isArray(formSchema.actions) && formSchema.actions.length > 0
            ? formSchema.actions
            : [{ name: 'ONCE', label: 'Approve', style: 'success' }];

        actions.forEach(function (actionDef) {
            const action = actionDef.name || 'ONCE';
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'btn btn-sm prompt-action-btn';
            btn.textContent = actionDef.label || action;

            const style = (actionDef.style || actionDef.variant || '').toLowerCase();
            if (style === 'danger') btn.classList.add('btn-danger');
            else if (style === 'success') btn.classList.add('btn-success');
            else btn.classList.add('btn-outline-primary');

            btn.addEventListener('click', function () {
                const fieldValues = {};
                let missingRequiredField = false;
                row.querySelectorAll('[data-field-name]').forEach(function (input) {
                    const fieldName = input.getAttribute('data-field-name');
                    const isCheckbox = input.type === 'checkbox';
                    const value = isCheckbox
                        ? (input.checked ? 'true' : 'false')
                        : (input.value == null ? '' : String(input.value));
                    const trimmed = value.trim();
                    const required = input.hasAttribute('required');
                    if ((isCheckbox && required && !input.checked) || (!isCheckbox && required && !trimmed)) {
                        input.classList.add('is-invalid');
                        missingRequiredField = true;
                    } else {
                        input.classList.remove('is-invalid');
                    }
                    fieldValues[fieldName] = value;
                });
                if (missingRequiredField) return;

                row.querySelectorAll('.prompt-action-btn').forEach(function (b) { b.disabled = true; });
                sendAuthorizationAction(frame, action, fieldValues, row);
            });
            actionsEl.appendChild(btn);
        });

        ctx.messagesArea.insertBefore(row, ctx.typingEl);
        scrollBottom();
    }

    // ── UiEventFrame detection ────────────────────────────────────────────────

    function isUiEventFrame(obj) {
        return obj && typeof obj === 'object'
            && typeof obj.type === 'string'
            && typeof obj.intent === 'string'
            && (
                (obj.payload && typeof obj.payload === 'object')
                || typeof obj.textResponse === 'string'
                || (obj.formSchema && typeof obj.formSchema === 'object')
            );
    }

    function isTerminalEventFrame(obj) {
        return obj
            && typeof obj === 'object'
            && obj.type === 'EVENT'
            && typeof obj.terminalId === 'string'
            && (obj.status === 'TERMINAL_START' || obj.status === 'TERMINAL_END'
            || obj.status === 'TERMINAL_DATA' || obj.status === 'TERMINAL_ABORTED');
    }

    function tryParseJson(text) {
        if (!text || typeof text !== 'string') return null;
        try { return JSON.parse(text); } catch (_) { return null; }
    }

    // ── Terminal helpers ──────────────────────────────────────────────────────

    function getTerminalViewId(terminalId) {
        return TERMINAL_ROW_PREFIX + terminalId;
    }

    function getTerminalWsUrl(terminalId) {
        if (!ctx) return null;
        const sessionUuid = ctx.getSessionUuid();
        if (!sessionUuid) return null;
        const proto = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
        return proto + window.location.host + '/ws/terminal/' + encodeURIComponent(sessionUuid) + '/' + encodeURIComponent(terminalId);
    }

    function closeTerminalSocket(terminalId) {
        const socket = terminalState.socketsByTerminal.get(terminalId);
        if (!socket) return;
        try { socket.close(); } catch (_) {}
        terminalState.socketsByTerminal.delete(terminalId);
    }

    function writeChunkToTerminalView(view, chunk) {
        view.bufferedText += chunk;
        if (view.terminal) {
            view.pendingWrite += chunk;
            if (!view.pendingWriteScheduled) {
                view.pendingWriteScheduled = true;
                setTimeout(function () {
                    if (view.terminal && view.pendingWrite) {
                        view.terminal.write(view.pendingWrite);
                        view.pendingWrite = '';
                    }
                    view.pendingWriteScheduled = false;
                }, 8);
            }
        } else if (view.passivePre) {
            view.passivePre.textContent = view.bufferedText || '(no terminal output)';
        }
    }

    function normalizeTerminalTranscriptForPre(text) {
        if (!text) return '';
        let s = String(text).replace(/\r\n/g, '\n').replace(/\r/g, '\n');
        s = s.replace(ANSI_ESCAPE_PATTERN, '');
        s = s.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, '');
        return s;
    }

    function buildPassiveTranscript(view) {
        const normalized = normalizeTerminalTranscriptForPre(view && view.bufferedText ? view.bufferedText : '');
        const prefix = (view && view.command) ? ('$ ' + view.command + '\n') : '';
        return (prefix + normalized) || '(no terminal output)';
    }

    function scheduleTerminalFinalize(view, delayMs) {
        if (!view) return;
        if (view.finalizeTimer) clearTimeout(view.finalizeTimer);
        view.finalizeTimer = setTimeout(function () {
            view.finalizeTimer = null;
            maybeFinalizeTerminalView(view);
        }, Math.max(0, delayMs || 0));
    }

    function cleanupTerminalLiveBindings(view) {
        if (!view) return;
        if (view.dataListener && view.dataListener.dispose) view.dataListener.dispose();
        view.dataListener = null;
        view.live = false;
        view.socketConnected = false;
    }

    function markTerminalCompleted(view) {
        if (!view) return;
        view.completed = true; view.live = false;
        if (view.stopBtn) view.stopBtn.style.display = 'none';
        if (view.toggleBtn) view.toggleBtn.disabled = false;
        if (view.statusIcon) {
            view.statusIcon.className = 'terminal-status-icon terminal-status-done';
            view.statusIcon.title = 'Completed';
            view.statusIcon.setAttribute('aria-label', 'Completed');
            view.statusIcon.innerHTML = '<i class="fa-solid fa-square-check" aria-hidden="true"></i>';
        }
    }

    function markTerminalAborted(view) {
        if (!view) return;
        view.completed = true; view.live = false;
        if (view.stopBtn) view.stopBtn.style.display = 'none';
        if (view.toggleBtn) view.toggleBtn.disabled = false;
        if (view.statusIcon) {
            view.statusIcon.className = 'terminal-status-icon terminal-status-aborted';
            view.statusIcon.title = 'Terminated';
            view.statusIcon.setAttribute('aria-label', 'Terminated');
            view.statusIcon.innerHTML = '<i class="fa-solid fa-circle-stop" aria-hidden="true"></i>';
        }
    }

    function setTerminalExpanded(view, expanded) {
        if (!view) return;
        view.expanded = !!expanded;
        view.row.classList.toggle('terminal-collapsed', !view.expanded);
        if (view.xtermContainer) view.xtermContainer.style.height = view.expanded ? '220px' : TERMINAL_COLLAPSED_HEIGHT;
        if (view.passivePre) view.passivePre.style.maxHeight = view.expanded ? '320px' : TERMINAL_COLLAPSED_HEIGHT;
        if (view.fitAddon && view.terminal && view.xtermContainer && !view.xtermContainer.classList.contains('d-none')) {
            setTimeout(function () { if (view.fitAddon && view.terminal) view.fitAddon.fit(); }, 0);
        }
        if (view.toggleBtn) {
            const icon = view.toggleBtn.querySelector('i');
            if (icon) icon.className = view.expanded ? 'fa-solid fa-chevron-up' : 'fa-solid fa-chevron-down';
            const label = view.expanded ? 'Collapse output' : 'Expand output';
            view.toggleBtn.setAttribute('aria-label', label);
            view.toggleBtn.setAttribute('title', label);
            view.toggleBtn.setAttribute('aria-expanded', String(view.expanded));
            view.toggleBtn.disabled = !view.completed && !view.live;
        }
    }

    function maybeFinalizeTerminalView(view) {
        if (!view || !view.endReceived) return;
        if (view.live) {
            const elapsed = view.endReceivedAt > 0 ? (Date.now() - view.endReceivedAt) : 0;
            if (elapsed < TERMINAL_END_GRACE_MS) {
                scheduleTerminalFinalize(view, TERMINAL_END_GRACE_MS - elapsed + 10);
                return;
            }
        }
        if (view.terminal && view.pendingWrite) {
            view.terminal.write(view.pendingWrite);
            view.pendingWrite = '';
            view.pendingWriteScheduled = false;
        }
        cleanupTerminalLiveBindings(view);
        closeTerminalSocket(view.terminalId);
        if (view.terminal) {
            view.terminal.dispose();
            view.terminal = null;
        }
        view.fitAddon = null;
        if (view.finalizeTimer) { clearTimeout(view.finalizeTimer); view.finalizeTimer = null; }
        if (view.aborted) markTerminalAborted(view); else markTerminalCompleted(view);
        view.passivePre.textContent = buildPassiveTranscript(view);
        view.passivePre.classList.remove('d-none');
        view.xtermContainer.classList.add('d-none');
        setTerminalExpanded(view, view.expanded);
    }

    function flushTerminalChunks(view) {
        if (!view || !view.pendingChunks || view.pendingChunks.length === 0) return;
        view.pendingChunks.forEach(function (chunk) { writeChunkToTerminalView(view, chunk); });
        view.pendingChunks = [];
    }

    function getPendingTerminalState(terminalId) {
        let pending = terminalState.pendingByTerminal.get(terminalId);
        if (!pending) {
            pending = { chunks: [], endReceived: false, aborted: false };
            terminalState.pendingByTerminal.set(terminalId, pending);
        }
        return pending;
    }

    function applyPendingTerminalState(view) {
        if (!view) return;
        const pending = terminalState.pendingByTerminal.get(view.terminalId);
        if (!pending) return;
        pending.chunks.forEach(function (chunk) { writeChunkToTerminalView(view, chunk); });
        if (pending.endReceived) view.endReceived = true;
        if (pending.aborted) view.aborted = true;
        terminalState.pendingByTerminal.delete(view.terminalId);
    }

    function findTerminalView(terminalId) {
        return terminalState.views.get(getTerminalViewId(terminalId));
    }

    function connectTerminalSocket(view) {
        const terminalId = view && view.terminalId ? view.terminalId : null;
        const wsUrl = getTerminalWsUrl(terminalId);
        if (!terminalId || !wsUrl) return null;
        closeTerminalSocket(terminalId);
        const socket = new WebSocket(wsUrl);
        socket.binaryType = 'arraybuffer';
        socket.onopen = function () {
            view.socketConnected = true;
            if (view.endReceived) scheduleTerminalFinalize(view, 80);
        };
        socket.onmessage = function (event) {
            if (!event || !event.data || !view.live) return;
            const chunk = typeof event.data === 'string'
                ? event.data
                : textDecoder.decode(new Uint8Array(event.data));
            writeChunkToTerminalView(view, chunk);
            if (view.endReceived) scheduleTerminalFinalize(view, 80);
            scrollBottom();
        };
        socket.onclose = function () {
            if (terminalState.socketsByTerminal.get(terminalId) === socket)
                terminalState.socketsByTerminal.delete(terminalId);
            view.socketConnected = false;
            if (view.endReceived) scheduleTerminalFinalize(view, 0);
        };
        terminalState.socketsByTerminal.set(terminalId, socket);
        return socket;
    }

    function createTerminalInlineRow(terminalId, command) {
        if (!ctx) return null;
        const stopButtonHtml = isReadOnlyMode()
            ? ''
            : '<button type="button" class="terminal-stop-btn" title="Terminate command" aria-label="Terminate command">' +
              '  <i class="fa-solid fa-stop" aria-hidden="true"></i>' +
              '</button>';
        const row = document.createElement('div');
        row.className = 'message-row terminal-stream-row';
        row.innerHTML =
            '<div class="bubble assistant terminal-stream-bubble">' +
            '  <div class="terminal-stream-header">' +
            '    <div class="terminal-stream-actions">' +
            '      <span class="terminal-status-icon terminal-status-running" title="Running" aria-label="Running">' +
            '        <i class="fa-solid fa-circle-notch fa-spin" aria-hidden="true"></i>' +
            '      </span>' +
                     stopButtonHtml +
            '    </div>' +
            '    <button type="button" class="terminal-stream-toggle" aria-label="Collapse output" title="Collapse output">' +
            '      <i class="fa-solid fa-chevron-up" aria-hidden="true"></i>' +
            '    </button>' +
            '  </div>' +
            '  <div class="terminal-stream-body">' +
            '    <div class="terminal-stream-xterm"></div>' +
            '    <pre class="terminal-stream-passive d-none"></pre>' +
            '  </div>' +
            '</div>';
        ctx.messagesArea.insertBefore(row, ctx.typingEl);
        const view = {
            terminalId, row, command: command || 'Live Terminal Stream',
            body: row.querySelector('.terminal-stream-body'),
            actions: row.querySelector('.terminal-stream-actions'),
            xtermContainer: row.querySelector('.terminal-stream-xterm'),
            passivePre: row.querySelector('.terminal-stream-passive'),
            toggleBtn: row.querySelector('.terminal-stream-toggle'),
            statusIcon: row.querySelector('.terminal-status-icon'),
            stopBtn: row.querySelector('.terminal-stop-btn'),
            terminal: null, fitAddon: null, dataListener: null,
            bufferedText: '', pendingChunks: [], pendingWrite: '', pendingWriteScheduled: false,
            endReceived: false, endReceivedAt: 0, aborted: false, live: false, completed: false,
            expanded: true, attachmentUuid: null, socketConnected: false, finalizeTimer: null
        };
        view.toggleBtn.addEventListener('click', function () { setTerminalExpanded(view, !view.expanded); });
        if (view.stopBtn) {
            view.stopBtn.addEventListener('click', function () {
                const sessionUuid = ctx.getSessionUuid();
                if (!sessionUuid || view.completed) return;
                view.stopBtn.disabled = true;
                fetch('/api/chat/session/' + sessionUuid + '/terminal/' + encodeURIComponent(view.terminalId) + '/terminate', {
                    method: 'POST'
                }).catch(function (err) {
                    console.warn('Failed to terminate command:', err);
                    view.stopBtn.disabled = false;
                });
            });
        }
        terminalState.views.set(getTerminalViewId(terminalId), view);
        setTerminalExpanded(view, true);
        return view;
    }

    // ── Terminal event handlers ───────────────────────────────────────────────

    function handleTerminalStart(frame) {
        const terminalId = frame && typeof frame.terminalId === 'string' ? frame.terminalId : null;
        if (!terminalId) return;
        setAwaitingTerminal(false);
        showTyping(false);
        setInputEnabled(false);
        const command = frame && frame.command ? String(frame.command) : 'Live Terminal Stream';
        const view = createTerminalInlineRow(terminalId, command);
        if (!view) return;
        cleanupTerminalLiveBindings(view);
        view.bufferedText = ''; view.pendingChunks = []; view.endReceived = false;
        view.endReceivedAt = 0; view.live = true; view.completed = false; view.socketConnected = false;
        if (view.finalizeTimer) { clearTimeout(view.finalizeTimer); view.finalizeTimer = null; }
        view.passivePre.classList.add('d-none');
        view.xtermContainer.classList.remove('d-none');
        view.xtermContainer.innerHTML = '';
        if (typeof Terminal !== 'function') {
            view.passivePre.textContent = 'xterm.js is not available.';
            view.passivePre.classList.remove('d-none');
            view.xtermContainer.classList.add('d-none');
            return;
        }
        const term = new Terminal({ convertEol: true, cursorBlink: true, fontSize: 13, fontFamily: "'Menlo','Monaco','Consolas',monospace" });
        view.terminal = term;
        term.open(view.xtermContainer);
        if (typeof FitAddon !== 'undefined' && typeof FitAddon.FitAddon === 'function') {
            view.fitAddon = new FitAddon.FitAddon();
            term.loadAddon(view.fitAddon);
            view.fitAddon.fit();
        }
        const socket = connectTerminalSocket(view);
        view.dataListener = term.onData(function (data) {
            if (!socket || socket.readyState !== WebSocket.OPEN) return;
            socket.send(textEncoder.encode(data));
        });
        applyPendingTerminalState(view);
        flushTerminalChunks(view);
        maybeFinalizeTerminalView(view);
        scrollBottom();
    }

    function handleTerminalData(frame) {
        const terminalId = frame && typeof frame.terminalId === 'string' ? frame.terminalId : null;
        if (!terminalId) return;
        const chunk = frame && typeof frame.chunk === 'string' ? frame.chunk : '';
        if (!chunk) return;
        const view = findTerminalView(terminalId);
        if (!view) { getPendingTerminalState(terminalId).chunks.push(chunk); return; }
        writeChunkToTerminalView(view, chunk);
        maybeFinalizeTerminalView(view);
        scrollBottom();
    }

    function handleTerminalEnd(frame) {
        const terminalId = frame && typeof frame.terminalId === 'string' ? frame.terminalId : null;
        setAwaitingTerminal(true);
        if (!terminalId) return;
        const view = findTerminalView(terminalId);
        if (!view) { const p = getPendingTerminalState(terminalId); p.endReceived = true; return; }
        view.endReceived = true; view.endReceivedAt = Date.now();
        flushTerminalChunks(view);
        scheduleTerminalFinalize(view, TERMINAL_END_GRACE_MS);
    }

    function handleTerminalAborted(frame) {
        const terminalId = frame && typeof frame.terminalId === 'string' ? frame.terminalId : null;
        setAwaitingTerminal(true);
        if (!terminalId) return;
        const view = findTerminalView(terminalId);
        if (!view) { const p = getPendingTerminalState(terminalId); p.endReceived = true; p.aborted = true; return; }
        view.aborted = true; view.endReceived = true; view.endReceivedAt = Date.now();
        flushTerminalChunks(view);
        scheduleTerminalFinalize(view, TERMINAL_END_GRACE_MS);
    }

    // ── Frame dispatcher ──────────────────────────────────────────────────────

    function handleIncomingUiFrame(frame) {
        if (isTerminalEventFrame(frame)) {
            if (frame.status === 'TERMINAL_START')        handleTerminalStart(frame);
            else if (frame.status === 'TERMINAL_DATA')    handleTerminalData(frame);
            else if (frame.status === 'TERMINAL_ABORTED') handleTerminalAborted(frame);
            else                                          handleTerminalEnd(frame);
            return;
        }
        switch (frame.type) {
            case 'AI_THINKING':
                renderThinkingEvent(frame.textResponse || '');
                showTyping(true); // AI is still working — keep the spinner
                return;
            case 'PROMPT_REQUIRED':
                setAwaitingTerminal(false);
                showTyping(false);
                if (ctx && typeof ctx.onPromptRequired === 'function' && getPromptMode() === 'external') {
                    ctx.onPromptRequired(frame);
                } else {
                    renderPromptRequiredFrame(frame);
                }
                return;
            case 'TEXT_RESPONSE':
                setAwaitingTerminal(false);
                if (frame.payload && frame.payload.message && typeof frame.payload.message === 'object') {
                    renderMessage(frame.payload.message); return;
                }
                if (typeof frame.textResponse === 'string') {
                    renderMessage({ role: 'ASSISTANT', content: frame.textResponse }); return;
                }
                if (frame.payload && typeof frame.payload.content === 'string') {
                    renderMessage({ role: 'ASSISTANT', content: frame.payload.content }); return;
                }
                renderMessage({ role: 'ASSISTANT', content: '' });
                return;
            case 'AGENT_TRANSITION':
                renderAgentTransition(frame.textResponse || '');
                showTyping(true); // AI is continuing — more output is expected
                return;
            case 'SKILL_TRANSITION':
                renderSkillEvent(frame.textResponse || '');
                showTyping(true); // AI is continuing — more output is expected
                return;
            case 'AGENT_SWITCH':
                // Handled by host page if needed
                if (ctx && typeof ctx.onAgentSwitch === 'function') ctx.onAgentSwitch(frame.textResponse);
                return;
            case 'ERROR':
                setAwaitingTerminal(false);
                renderMessage({
                    role: 'ERROR',
                    content: (typeof frame.textResponse === 'string' && frame.textResponse)
                        ? frame.textResponse
                        : ((frame.payload && frame.payload.message) ? String(frame.payload.message) : 'Unknown error')
                });
                return;
            default:
                if (frame.payload && frame.payload.message && typeof frame.payload.message === 'object') {
                    renderMessage(frame.payload.message);
                }
        }
    }

    // ── History rendering ─────────────────────────────────────────────────────

    function renderSessionHistory(messages) {
        if (!messages || !messages.length) return;
        const lastPromptIndex = messages.reduce(function (acc, m, i) {
            return m.role === 'PROMPT_REQUIRED' ? i : acc;
        }, -1);
        messages.forEach(function (msg, index) {
            if (msg.role === 'PROMPT_REQUIRED') {
                if (index !== lastPromptIndex) return;
                const frame = tryParseJson(msg.content);
                if (frame && isUiEventFrame(frame)) {
                    if (ctx && typeof ctx.onPromptRequired === 'function' && getPromptMode() === 'external') {
                        ctx.onPromptRequired(frame);
                    } else {
                        renderPromptRequiredFrame(frame);
                    }
                }
                return;
            }
            if (msg.role === 'TEXT_RESPONSE') {
                const frame = tryParseJson(msg.content);
                if (frame && typeof frame.textResponse === 'string') {
                    renderMessage({ role: 'ASSISTANT', content: frame.textResponse }); return;
                }
            }
            if (msg.role === 'AGENT_TRANSITION') { renderAgentTransition(msg.content || ''); return; }
            if (msg.role === 'SKILL_TRANSITION')  { renderSkillEvent(msg.content || '');     return; }
            if (msg.role === 'TOOL') return; // terminal history replay not shown in monitor
            renderMessage(msg);
        });
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    function reset() {
        for (const terminalId of terminalState.socketsByTerminal.keys()) closeTerminalSocket(terminalId);
        terminalState.views.clear();
        terminalState.pendingByTerminal.clear();
        showTyping(false);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    global.ChatRender = {
        /**
         * @param {object} config
         * @param {Element}  config.messagesArea       — #messages-area container
         * @param {Element}  config.typingEl           — #typing-indicator row
         * @param {Function} config.getSessionUuid     — () => current session UUID string
         * @param {Function} [config.onPromptRequired] — (frame) callback for PROMPT_REQUIRED
         * @param {Function} [config.onInputStateChange] — (enabled) optional input toggle
         * @param {Function} [config.onAwaitingTerminal] — (on) optional callback
         * @param {Function} [config.onAgentSwitch]    — (agentId) optional callback
         * @param {Function} [config.onPromptSubmitted] — ({frame, action, response}) callback
         * @param {boolean}  [config.readOnly]          — hide terminal stop controls
         */
        init: function (config) {
            ctx = config;
        },
        renderMessage,
        renderAgentTransition,
        renderSkillEvent,
        renderThinkingEvent,
        showTyping,
        scrollBottom,
        escapeHtml,
        isUiEventFrame,
        isTerminalEventFrame,
        handleIncomingUiFrame,
        renderSessionHistory,
        reset
    };

}(window));
