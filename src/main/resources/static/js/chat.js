/* global StompJs, SockJS, marked */

'use strict';

marked.use({ breaks: true, gfm: true });

const messagesArea   = document.getElementById('messages-area');
const typingEl       = document.getElementById('typing-indicator');
const messageInput   = document.getElementById('message-input');
const sendBtn        = document.getElementById('send-btn');
const chatForm       = document.getElementById('chat-form');
const providerSel    = document.getElementById('provider-select');
const agentSel       = document.getElementById('agent-select');
const statusDot      = document.getElementById('status-dot');
const sessionDisplay = document.getElementById('session-display');
const attachStrip    = document.getElementById('attachment-strip');
const fileInput      = document.getElementById('file-input');
const uploadFilesBtn = document.getElementById('upload-files-btn');
const logoutBtn      = document.getElementById('logout-btn');
const logoutForm     = document.getElementById('logout-form');
const sidebarToggle  = document.getElementById('sidebar-toggle');
const newChatBtn     = document.getElementById('new-chat-btn');
const sessionListEl  = document.getElementById('chat-session-list');

let sessionUuid = null;
let stomp       = null;
let chatSubscription = null;
let waiting     = false;
let sessions    = [];
let editingSessionUuid = null;
let awaitingPostTerminalResponse = false;

// Populated on page load from /api/chat/models
let availableModels = [];

// Load available provider/model options and populate the dropdown
function loadAvailableModels() {
    return fetch('/api/chat/models')
        .then(function (r) { return r.ok ? r.json() : Promise.reject(r.statusText); })
        .then(function (groups) {
            availableModels = groups;
            providerSel.innerHTML = '';
            groups.forEach(function (group) {
                if (!group.configured) return; // only show configured providers
                group.models.forEach(function (m) {
                    const opt = document.createElement('option');
                    opt.value = group.providerKey + ':' + m.modelId;
                    opt.textContent = group.providerLabel + ' \u2014 ' + m.label;
                    if (m.isDefault) opt.setAttribute('data-default', 'true');
                    providerSel.appendChild(opt);
                });
            });
            // Try to select the global default; fall back to first option
            return fetch('/api/system/settings')
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (s) {
                    if (s && s.defaultProvider && s.defaultModelId) {
                        var key = s.defaultProvider + ':' + s.defaultModelId;
                        var opt = providerSel.querySelector('option[value="' + key + '"]');
                        if (opt) { providerSel.value = key; return; }
                    }
                    if (providerSel.options.length > 0 && !providerSel.value) {
                        providerSel.selectedIndex = 0;
                    }
                })
                .catch(function () {
                    if (providerSel.options.length > 0 && !providerSel.value) {
                        providerSel.selectedIndex = 0;
                    }
                });
        })
        .catch(function () { /* keep existing placeholder option */ });
}

/** Parse the combined PROVIDER:modelId value into {provider, modelId}. */
function parseProviderModel(combined) {
    if (!combined) return { provider: 'GEMINI', modelId: '' };
    const sep = combined.indexOf(':');
    if (sep === -1) return { provider: combined, modelId: '' };
    return { provider: combined.substring(0, sep), modelId: combined.substring(sep + 1) };
}

const terminalState = {
    views: new Map(),
    pendingByTerminal: new Map(),
    socketsByTerminal: new Map()
};

const TERMINAL_ROW_PREFIX = 'terminal-';
const TERMINAL_REPLAY_MAX_CHARS = 24000;
const TERMINAL_REPLAY_TAIL_CHARS = 12000;
const TERMINAL_COLLAPSED_HEIGHT = '1.6rem';
const TERMINAL_END_GRACE_MS = 160;
const textDecoder = new TextDecoder();
const textEncoder = new TextEncoder();
const ANSI_ESCAPE_PATTERN = /\u001B\[[0-9;?]*[ -/]*[@-~]/g;

// Staged attachments: [{uuid, name, mimeType, aiSupported, chipEl}]
let stagedAttachments = [];

// ── Utilities ────────────────────────────────────────────────────────────────

function escapeHtml(s) {
    return s
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function setStatus(state) {
    statusDot.className = 'status-dot ms-1 ' + state;
    const labels = { connected: 'Connected', disconnected: 'Disconnected', connecting: 'Connecting\u2026' };
    statusDot.title = labels[state] || state;
}

function scrollBottom() {
    messagesArea.scrollTop = messagesArea.scrollHeight;
}

function showTyping(on) {
    typingEl.classList.toggle('d-none', !on);
    if (on) { scrollBottom(); }
}

function setInputEnabled(on) {
    messageInput.disabled = !on;
    sendBtn.disabled = !on;
    waiting = !on;
}

function setAwaitingPostTerminalResponse(on) {
    awaitingPostTerminalResponse = !!on;
    showTyping(awaitingPostTerminalResponse);
    if (awaitingPostTerminalResponse) {
        setInputEnabled(false);
    }
}

function isTerminalToolMessage(msg) {
    return !!(msg && msg.role === 'TOOL' && msg.toolName === 'executeTerminalCommand');
}

function getTerminalWsUrl(terminalId) {
    const proto = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
    return proto + window.location.host + '/ws/terminal/' + encodeURIComponent(sessionUuid) + '/' + encodeURIComponent(terminalId);
}

function closeTerminalSocket(terminalId) {
    const socket = terminalState.socketsByTerminal.get(terminalId);
    if (!socket) {
        return;
    }
    try {
        socket.close();
    } catch (_) {
        // no-op
    }
    terminalState.socketsByTerminal.delete(terminalId);
}

function connectTerminalSocket(view) {
    const terminalId = view && view.terminalId ? view.terminalId : null;
    if (!terminalId || !sessionUuid) {
        return null;
    }

    closeTerminalSocket(terminalId);

    const socket = new WebSocket(getTerminalWsUrl(terminalId));
    socket.binaryType = 'arraybuffer';

    socket.onopen = function () {
        view.socketConnected = true;
        if (view.endReceived) {
            scheduleTerminalFinalize(view, 80);
        }
    };

    // Instrumentation for measuring data transfer
    const stats = {
        frameCount: 0,
        totalBytes: 0,
        minFrameSize: Infinity,
        maxFrameSize: 0,
        startTime: Date.now(),
        lastLogTime: Date.now()
    };

    socket.onmessage = function (event) {
        if (!event || !event.data) {
            return;
        }
        if (!view.live) {
            return;
        }
        
        // Record statistics
        const frameSize = event.data instanceof ArrayBuffer ? event.data.byteLength : event.data.length;
        stats.frameCount++;
        stats.totalBytes += frameSize;
        stats.minFrameSize = Math.min(stats.minFrameSize, frameSize);
        stats.maxFrameSize = Math.max(stats.maxFrameSize, frameSize);
        
        // Log every 100 frames
        if (stats.frameCount % 100 === 0) {
            const now = Date.now();
            const elapsedMs = now - stats.startTime;
            const avgFrameSize = Math.floor(stats.totalBytes / stats.frameCount);
            const throughputKbps = elapsedMs > 0 ? Math.floor((stats.totalBytes * 8) / elapsedMs) : 0;
            console.log(`[WS-${terminalId.substring(0,8)}] frames=${stats.frameCount}, avg=${avgFrameSize}B, throughput=${throughputKbps}Kbps, min=${stats.minFrameSize}B, max=${stats.maxFrameSize}B`);
        }
        
        if (typeof event.data === 'string') {
            writeChunkToTerminalView(view, event.data);
        } else {
            const chunk = textDecoder.decode(new Uint8Array(event.data));
            writeChunkToTerminalView(view, chunk);
        }
        if (view.endReceived) {
            scheduleTerminalFinalize(view, 80);
        }
        scrollBottom();
    };

    socket.onclose = function () {
        // Final stats log
        if (stats.frameCount > 0) {
            const elapsedMs = Date.now() - stats.startTime;
            const avgFrameSize = Math.floor(stats.totalBytes / stats.frameCount);
            const throughputKbps = elapsedMs > 0 ? Math.floor((stats.totalBytes * 8) / elapsedMs) : 0;
            console.log(`[WS-${terminalId.substring(0,8)}] FINAL: frames=${stats.frameCount}, totalBytes=${stats.totalBytes}, avg=${avgFrameSize}B, throughput=${throughputKbps}Kbps, min=${stats.minFrameSize}B, max=${stats.maxFrameSize}B, elapsed=${elapsedMs}ms`);
        }
        if (terminalState.socketsByTerminal.get(terminalId) === socket) {
            terminalState.socketsByTerminal.delete(terminalId);
        }
        view.socketConnected = false;
        if (view.endReceived) {
            scheduleTerminalFinalize(view, 0);
        }
    };

    terminalState.socketsByTerminal.set(terminalId, socket);
    return socket;
}

function focusMessageInput() {
    if (!messageInput || messageInput.disabled) return;
    requestAnimationFrame(function () {
        messageInput.focus();
    });
}

function resetTerminalState() {
    for (const terminalId of terminalState.socketsByTerminal.keys()) {
        closeTerminalSocket(terminalId);
    }
    terminalState.views.clear();
    terminalState.pendingByTerminal.clear();
}

function clearConversationUi() {
    resetTerminalState();
    messagesArea.querySelectorAll('.message-row').forEach(function (row) {
        if (row !== typingEl) {
            row.remove();
        }
    });
    showTyping(false);
    setInputEnabled(true);
}

function formatRelativeTime(epochMillis) {
    if (!epochMillis) {
        return '';
    }
    const deltaMs = Date.now() - epochMillis;
    const mins = Math.floor(deltaMs / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return mins + 'm ago';
    const hours = Math.floor(mins / 60);
    if (hours < 24) return hours + 'h ago';
    const days = Math.floor(hours / 24);
    return days + 'd ago';
}

// ── MIME-type helpers ─────────────────────────────────────────────────────────

function isImage(mime) {
    return mime && mime.startsWith('image/');
}

/** Returns a Font Awesome class name for a given MIME type. */
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

// ── Render a single message bubble ───────────────────────────────────────────

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
    const isUser    = msg.role === 'USER';
    const textHtml  = isUser
        ? escapeHtml(msg.content || '').replace(/\n/g, '<br>')
        : marked.parse(msg.content || '');

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

    // Wire up lightbox on image thumbnails
    row.querySelectorAll('.bubble-img-thumb').forEach(function (img) {
        img.addEventListener('click', function () { openLightbox(img.dataset.src); });
    });

    messagesArea.insertBefore(row, typingEl);
    scrollBottom();
}

function renderAgentTransition(text) {
    const row = document.createElement('div');
    row.className = 'agent-transition-row';
    row.innerHTML = '<i class="fa-solid fa-gears" aria-hidden="true"></i><span>' + escapeHtml(text) + '</span>';
    messagesArea.insertBefore(row, typingEl);
    scrollBottom();
}

function renderSkillEvent(text) {
    const row = document.createElement('div');
    row.className = 'agent-transition-row';
    row.innerHTML = '<i class="fa-solid fa-bolt" aria-hidden="true"></i><span>' + escapeHtml(text) + '</span>';
    messagesArea.insertBefore(row, typingEl);
    scrollBottom();
}

function renderModelSwitch(text) {
    const row = document.createElement('div');
    row.className = 'agent-transition-row';
    row.innerHTML = '<i class="fa-solid fa-robot" aria-hidden="true"></i><span>' + escapeHtml(text) + '</span>';
    messagesArea.insertBefore(row, typingEl);
    scrollBottom();
}

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

function tryParseJson(text) {
    if (!text || typeof text !== 'string') return null;
    try {
        return JSON.parse(text);
    } catch (_) {
        return null;
    }
}

function isTerminalEventFrame(obj) {
    return obj
        && typeof obj === 'object'
        && obj.type === 'EVENT'
        && typeof obj.terminalId === 'string'
        && (obj.status === 'TERMINAL_START' || obj.status === 'TERMINAL_END'
        || obj.status === 'TERMINAL_DATA' || obj.status === 'TERMINAL_ABORTED');
}

function getTerminalViewId(terminalId) {
    return TERMINAL_ROW_PREFIX + terminalId;
}

function createTerminalInlineRow(terminalId, command, options) {
    const expanded = !options || options.expanded !== false;
    const row = document.createElement('div');
    row.className = 'message-row terminal-stream-row';
    row.innerHTML =
        '<div class="bubble assistant terminal-stream-bubble">' +
        '  <div class="terminal-stream-header">' +
        '    <div class="terminal-stream-actions">' +
        '      <span class="terminal-status-icon terminal-status-running" title="Running" aria-label="Running">' +
        '        <i class="fa-solid fa-circle-notch fa-spin" aria-hidden="true"></i>' +
        '      </span>' +
        '      <button type="button" class="terminal-stop-btn" title="Terminate command" aria-label="Terminate command">' +
        '        <i class="fa-solid fa-stop" aria-hidden="true"></i>' +
        '      </button>' +
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

    messagesArea.insertBefore(row, typingEl);

    const view = {
        terminalId: terminalId,
        row: row,
        body: row.querySelector('.terminal-stream-body'),
        actions: row.querySelector('.terminal-stream-actions'),
        xtermContainer: row.querySelector('.terminal-stream-xterm'),
        passivePre: row.querySelector('.terminal-stream-passive'),
        toggleBtn: row.querySelector('.terminal-stream-toggle'),
        terminal: null,
        fitAddon: null,
        dataListener: null,
        bufferedText: '',
        pendingChunks: [],
        pendingWrite: '',
        pendingWriteScheduled: false,
        endReceived: false,
        endReceivedAt: 0,
        aborted: false,
        live: false,
        completed: false,
        expanded: expanded,
        command: command || 'Live Terminal Stream',
        attachmentUuid: null,
        statusIcon: row.querySelector('.terminal-status-icon'),
        stopBtn: row.querySelector('.terminal-stop-btn'),
        socketConnected: false,
        finalizeTimer: null
    };

    view.toggleBtn.addEventListener('click', function () {
        setTerminalExpanded(view, !view.expanded);
    });

    if (view.stopBtn) {
        view.stopBtn.addEventListener('click', function () {
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
    setTerminalExpanded(view, expanded);
    return view;
}

function getPendingTerminalState(terminalId) {
    let pending = terminalState.pendingByTerminal.get(terminalId);
    if (!pending) {
        pending = {
            chunks: [],
            endReceived: false,
            aborted: false
        };
        terminalState.pendingByTerminal.set(terminalId, pending);
    }
    return pending;
}

function applyPendingTerminalState(view) {
    if (!view) {
        return;
    }
    const pending = terminalState.pendingByTerminal.get(view.terminalId);
    if (!pending) {
        return;
    }

    pending.chunks.forEach(function (chunk) {
        writeChunkToTerminalView(view, chunk);
    });
    if (pending.endReceived) {
        view.endReceived = true;
    }
    if (pending.aborted) {
        view.aborted = true;
    }

    terminalState.pendingByTerminal.delete(view.terminalId);
}

function normalizeTerminalTranscriptForPre(text) {
    if (!text) {
        return '';
    }
    let normalized = String(text);
    normalized = normalized.replace(/\r\n/g, '\n');
    normalized = normalized.replace(/\r/g, '\n');
    normalized = normalized.replace(ANSI_ESCAPE_PATTERN, '');
    normalized = normalized.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, '');
    return normalized;
}

function buildPassiveTranscript(view) {
    const normalized = normalizeTerminalTranscriptForPre(view && view.bufferedText ? view.bufferedText : '');
    const commandPrefix = (view && view.command) ? ('$ ' + view.command + '\n') : '';
    return (commandPrefix + normalized) || '(no terminal output)';
}

function writeChunkToTerminalView(view, chunk) {
    view.bufferedText += chunk;
    if (view.terminal) {
        // Buffer writes and flush quickly with a timer to avoid rAF throttling in background tabs.
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

function flushTerminalChunks(view) {
    if (!view || !view.pendingChunks || view.pendingChunks.length === 0) {
        return;
    }
    view.pendingChunks.forEach(function (chunk) {
        writeChunkToTerminalView(view, chunk);
    });
    view.pendingChunks = [];
}

function maybeFinalizeTerminalView(view) {
    if (!view || !view.endReceived) {
        return;
    }

    if (view.live) {
        const elapsed = view.endReceivedAt > 0 ? (Date.now() - view.endReceivedAt) : 0;
        if (elapsed < TERMINAL_END_GRACE_MS) {
            scheduleTerminalFinalize(view, TERMINAL_END_GRACE_MS - elapsed + 10);
            return;
        }
    }

    // Flush any pending batched writes before disposing the terminal
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
    if (view.finalizeTimer) {
        clearTimeout(view.finalizeTimer);
        view.finalizeTimer = null;
    }
    if (view.aborted) {
        markTerminalAborted(view);
    } else {
        markTerminalCompleted(view);
    }

    view.passivePre.textContent = buildPassiveTranscript(view);
    view.passivePre.classList.remove('d-none');
    view.xtermContainer.classList.add('d-none');
    setTerminalExpanded(view, view.expanded);

    if (!hasLiveTerminal() && !awaitingPostTerminalResponse) {
        setInputEnabled(true);
        focusMessageInput();
    }
}

function scheduleTerminalFinalize(view, delayMs) {
    if (!view) {
        return;
    }
    if (view.finalizeTimer) {
        clearTimeout(view.finalizeTimer);
    }
    view.finalizeTimer = setTimeout(function () {
        view.finalizeTimer = null;
        maybeFinalizeTerminalView(view);
    }, Math.max(0, delayMs || 0));
}

function setTerminalExpanded(view, expanded) {
    if (!view) {
        return;
    }
    view.expanded = !!expanded;
    view.row.classList.toggle('terminal-collapsed', !view.expanded);

    if (view.xtermContainer) {
        view.xtermContainer.style.height = view.expanded ? '220px' : TERMINAL_COLLAPSED_HEIGHT;
    }
    if (view.passivePre) {
        view.passivePre.style.maxHeight = view.expanded ? '320px' : TERMINAL_COLLAPSED_HEIGHT;
    }

    if (view.fitAddon && view.terminal && view.xtermContainer && !view.xtermContainer.classList.contains('d-none')) {
        setTimeout(function () {
            if (view.fitAddon && view.terminal) {
                view.fitAddon.fit();
            }
        }, 0);
    }

    if (view.toggleBtn) {
        const icon = view.toggleBtn.querySelector('i');
        if (icon) {
            icon.className = view.expanded
                ? 'fa-solid fa-chevron-up'
                : 'fa-solid fa-chevron-down';
        }
        const label = view.expanded ? 'Collapse output' : 'Expand output';
        view.toggleBtn.setAttribute('aria-label', label);
        view.toggleBtn.setAttribute('title', label);
        view.toggleBtn.setAttribute('aria-expanded', String(view.expanded));
        view.toggleBtn.disabled = !view.completed && !view.live;
    }
}

function markTerminalCompleted(view) {
    if (!view) {
        return;
    }
    view.completed = true;
    view.live = false;
    if (view.stopBtn) {
        view.stopBtn.style.display = 'none';
    }
    if (view.toggleBtn) {
        view.toggleBtn.disabled = false;
    }
    if (view.statusIcon) {
        view.statusIcon.className = 'terminal-status-icon terminal-status-done';
        view.statusIcon.title = 'Completed';
        view.statusIcon.setAttribute('aria-label', 'Completed');
        view.statusIcon.innerHTML = '<i class="fa-solid fa-square-check" aria-hidden="true"></i>';
    }
}

function markTerminalAborted(view) {
    if (!view) {
        return;
    }
    view.completed = true;
    view.live = false;
    if (view.stopBtn) {
        view.stopBtn.style.display = 'none';
    }
    if (view.toggleBtn) {
        view.toggleBtn.disabled = false;
    }
    if (view.statusIcon) {
        view.statusIcon.className = 'terminal-status-icon terminal-status-aborted';
        view.statusIcon.title = 'Terminated';
        view.statusIcon.setAttribute('aria-label', 'Terminated');
        view.statusIcon.innerHTML = '<i class="fa-solid fa-circle-stop" aria-hidden="true"></i>';
    }
}

function collapseCompletedTerminals() {
    for (const view of terminalState.views.values()) {
        if (view.completed) {
            setTerminalExpanded(view, false);
        }
    }
}

function hasLaterUserMessage(messages, index) {
    for (let i = index + 1; i < messages.length; i += 1) {
        if (messages[i] && messages[i].role === 'USER') {
            return true;
        }
    }
    return false;
}

function findTerminalView(terminalId) {
    return terminalState.views.get(getTerminalViewId(terminalId));
}

function cleanupTerminalLiveBindings(view) {
    if (!view) {
        return;
    }
    if (view.dataListener && view.dataListener.dispose) {
        view.dataListener.dispose();
    }
    view.dataListener = null;
    view.live = false;
    view.socketConnected = false;
}

function hasLiveTerminal() {
    for (const view of terminalState.views.values()) {
        if (view.live) {
            return true;
        }
    }
    return false;
}

function handleTerminalStart(frame) {
    if (!stomp || !stomp.connected || !sessionUuid) {
        return;
    }

    const terminalId = frame && typeof frame.terminalId === 'string' ? frame.terminalId : null;
    if (!terminalId) {
        return;
    }

    setAwaitingPostTerminalResponse(false);
    showTyping(false);
    setInputEnabled(false);

    const command = frame && frame.command ? String(frame.command) : 'Live Terminal Stream';
    const view = createTerminalInlineRow(terminalId, command, { expanded: true });
    cleanupTerminalLiveBindings(view);
    view.bufferedText = '';
    view.pendingChunks = [];
    view.endReceived = false;
    view.endReceivedAt = 0;
    view.live = true;
    view.completed = false;
    view.socketConnected = false;
    if (view.finalizeTimer) {
        clearTimeout(view.finalizeTimer);
        view.finalizeTimer = null;
    }

    view.passivePre.classList.add('d-none');
    view.xtermContainer.classList.remove('d-none');
    view.xtermContainer.innerHTML = '';

    if (typeof Terminal !== 'function') {
        view.passivePre.textContent = 'xterm.js is not available in this page build.';
        view.passivePre.classList.remove('d-none');
        view.xtermContainer.classList.add('d-none');
        return;
    }

    const term = new Terminal({
        convertEol: true,
        cursorBlink: true,
        fontSize: 13,
        fontFamily: "'Menlo', 'Monaco', 'Consolas', monospace"
    });
    view.terminal = term;
    term.open(view.xtermContainer);

    if (typeof FitAddon !== 'undefined' && typeof FitAddon.FitAddon === 'function') {
        view.fitAddon = new FitAddon.FitAddon();
        term.loadAddon(view.fitAddon);
        view.fitAddon.fit();
    }

    const socket = connectTerminalSocket(view);

    view.dataListener = term.onData(function (data) {
        if (!socket || socket.readyState !== WebSocket.OPEN) {
            return;
        }
        socket.send(textEncoder.encode(data));
    });

    applyPendingTerminalState(view);
    flushTerminalChunks(view);
    maybeFinalizeTerminalView(view);

    scrollBottom();
}

function handleTerminalData(frame) {
    const terminalId = frame && typeof frame.terminalId === 'string' ? frame.terminalId : null;
    if (!terminalId) {
        return;
    }

    const chunk = frame && typeof frame.chunk === 'string' ? frame.chunk : '';
    if (!chunk) {
        return;
    }

    const view = findTerminalView(terminalId);
    if (!view) {
        const pending = getPendingTerminalState(terminalId);
        pending.chunks.push(chunk);
        return;
    }

    writeChunkToTerminalView(view, chunk);

    maybeFinalizeTerminalView(view);
    scrollBottom();
}

function handleTerminalEnd(frame) {
    const terminalId = frame && typeof frame.terminalId === 'string' ? frame.terminalId : null;
    setAwaitingPostTerminalResponse(true);

    if (!terminalId) {
        return;
    }

    const view = findTerminalView(terminalId);

    if (!view) {
        const pending = getPendingTerminalState(terminalId);
        pending.endReceived = true;
        return;
    }

    view.endReceived = true;
    view.endReceivedAt = Date.now();
    flushTerminalChunks(view);
    scheduleTerminalFinalize(view, TERMINAL_END_GRACE_MS);

}

function handleTerminalAborted(frame) {
    const terminalId = frame && typeof frame.terminalId === 'string' ? frame.terminalId : null;
    setAwaitingPostTerminalResponse(true);

    if (!terminalId) {
        return;
    }

    const view = findTerminalView(terminalId);

    if (!view) {
        const pending = getPendingTerminalState(terminalId);
        pending.endReceived = true;
        pending.aborted = true;
        return;
    }

    view.aborted = true;
    view.endReceived = true;
    view.endReceivedAt = Date.now();
    flushTerminalChunks(view);
    scheduleTerminalFinalize(view, TERMINAL_END_GRACE_MS);

}

function renderTerminalTranscript(terminalId, command, output, expanded, attachment) {
    const view = createTerminalInlineRow(terminalId, command, { expanded: expanded });
    view.bufferedText = output || '';
    markTerminalCompleted(view);
    view.xtermContainer.classList.add('d-none');
    view.passivePre.textContent = buildPassiveTranscript(view);
    view.passivePre.classList.remove('d-none');
    attachTerminalHeaderFile(view, attachment);
    setTerminalExpanded(view, expanded);
    return view;
}

function attachTerminalHeaderFile(view, attachment) {
    if (!view || !view.actions || !attachment || !attachment.uuid) {
        return;
    }
    if (view.attachmentUuid === attachment.uuid) {
        return;
    }
    view.attachmentUuid = attachment.uuid;

    // Remove any existing attachment link (but leave the status icon intact)
    const existing = view.actions.querySelector('.terminal-stream-attachment-btn');
    if (existing) {
        existing.remove();
    }

    const link = document.createElement('a');
    link.className = 'terminal-stream-attachment-btn';
    link.href = '/api/files/' + attachment.uuid;
    link.download = attachment.name || 'terminal-output.txt';
    link.target = '_blank';
    link.title = attachment.name || 'Download terminal output';
    link.setAttribute('aria-label', attachment.name || 'Download terminal output');
    link.innerHTML = '<i class="fa-solid fa-paperclip" aria-hidden="true"></i>';
    view.actions.appendChild(link);
}

function findBestTerminalViewForToolResult(command, terminalId) {
    if (terminalId) {
        const exact = findTerminalView(terminalId);
        if (exact) {
            return exact;
        }
    }
    const views = Array.from(terminalState.views.values());
    for (let i = views.length - 1; i >= 0; i -= 1) {
        const view = views[i];
        if (!view) {
            continue;
        }
        if (command && view.command && view.command !== command) {
            continue;
        }
        return view;
    }
    return null;
}

function renderDeniedToolMessage(toolName) {
    const row = document.createElement('div');
    row.className = 'agent-transition-row';
    row.innerHTML = '<i class="fa-solid fa-ban" aria-hidden="true"></i>'
        + '<span>Request denied: <code>' + escapeHtml(toolName || 'tool') + '</code></span>';
    messagesArea.insertBefore(row, typingEl);
    scrollBottom();
}

async function renderLiveToolMessage(msg) {
    const transcript = tryGetTerminalTranscript(msg);
    if (!transcript) {
        // Check whether this is a DENIED tool response and render a small indicator.
        // All other non-terminal TOOL messages are internal plumbing — skip silently.
        const payload = tryParseJson(msg.content);
        const firstResponse = payload && Array.isArray(payload.responses) && payload.responses.length > 0
            ? payload.responses[0]
            : null;
        const responseData = firstResponse ? tryParseJson(firstResponse.responseData) : null;
        if (responseData && responseData.status === 'DENIED') {
            renderDeniedToolMessage(msg.toolName);
        }
        return;
    }

    const attachment = Array.isArray(msg.attachments) && msg.attachments.length > 0
        ? msg.attachments[0]
        : null;
    const existing = findBestTerminalViewForToolResult(transcript.command, transcript.terminalId);
    if (existing) {
        attachTerminalHeaderFile(existing, attachment || (transcript.outputFileUuid ? {
            uuid: transcript.outputFileUuid,
            name: 'terminal-output.txt',
            mimeType: 'text/plain'
        } : null));
        return;
    }

    await renderTerminalSessionRecord(msg, 0, [msg]);
}

function buildReplayOutput(text) {
    if (!text) {
        return '(no terminal output)';
    }
    if (text.length <= TERMINAL_REPLAY_MAX_CHARS) {
        return text;
    }
    const tail = text.slice(-TERMINAL_REPLAY_TAIL_CHARS);
    return '[large output truncated; showing tail]\n\n' + tail;
}

async function loadReplayOutputFromAttachment(attachmentUuid, fallbackOutput) {
    if (!attachmentUuid) {
        return buildReplayOutput(fallbackOutput || '');
    }
    try {
        const resp = await fetch('/api/files/' + attachmentUuid, { method: 'GET' });
        if (!resp.ok) {
            return buildReplayOutput(fallbackOutput || '');
        }
        const text = await resp.text();
        return buildReplayOutput(text);
    } catch (_) {
        return buildReplayOutput(fallbackOutput || '');
    }
}

function tryGetTerminalTranscript(msg) {
    if (!msg || msg.role !== 'TOOL' || msg.toolName !== 'executeTerminalCommand') {
        return null;
    }
    const payload = tryParseJson(msg.content);
    if (!payload || !payload.terminalTranscript || typeof payload.terminalTranscript !== 'object') {
        return null;
    }
    const transcript = payload.terminalTranscript;
    return {
        terminalId: typeof transcript.terminalId === 'string' ? transcript.terminalId : null,
        command: typeof transcript.command === 'string' ? transcript.command : 'terminal command',
        output: typeof transcript.output === 'string' ? transcript.output : '',
        outputFileUuid: typeof transcript.outputFileUuid === 'string' ? transcript.outputFileUuid : null
    };
}

async function renderTerminalSessionRecord(msg, index, messages) {
    const transcript = tryGetTerminalTranscript(msg);
    if (!transcript) {
        return false;
    }
    const expanded = !hasLaterUserMessage(messages, index);
    const attachment = Array.isArray(msg.attachments) && msg.attachments.length > 0
        ? msg.attachments[0]
        : null;
    const output = await loadReplayOutputFromAttachment(
        transcript.outputFileUuid || (attachment ? attachment.uuid : null),
        transcript.output
    );
    renderTerminalTranscript(transcript.terminalId || msg.uuid, transcript.command, output, expanded, attachment);
    return true;
}

function findLastUnansweredPromptIndex(messages) {
    if (!Array.isArray(messages) || messages.length === 0) {
        return -1;
    }
    const lastIndex = messages.length - 1;
    const last = messages[lastIndex];
    if (last && last.role === 'PROMPT_REQUIRED') {
        return lastIndex;
    }
    return -1;
}

function renderPromptRequiredFrame(frame) {
    const payload = frame.payload || {};
    const schema = frame.formSchema || {};
    const schemaTitle = (typeof schema.title === 'string' && schema.title.trim())
        ? schema.title.trim()
        : 'Authorization Required';
    const schemaFields = Array.isArray(schema.fields)
        ? schema.fields.filter(function (field) { return !!field && typeof field.name === 'string' && field.name.trim(); })
        : [];
    const hasSchemaArgumentsPreview = schemaFields.some(function (field) {
        return String(field.name || '').toLowerCase() === 'arguments';
    });
    const reasoning = (typeof frame.textResponse === 'string' && frame.textResponse.trim())
        ? frame.textResponse
        : ((typeof schema.description === 'string' && schema.description.trim())
            ? schema.description
            : ((typeof payload.reasoning === 'string' && payload.reasoning.trim())
            ? payload.reasoning
            : 'The AI requested this action to process your command.'));

    function contextValue(name) {
        const fields = Array.isArray(schema.fields) ? schema.fields : [];
        for (let i = 0; i < fields.length; i++) {
            const f = fields[i];
            if (f && f.name === name) {
                if (typeof f.value === 'string') {
                    return f.value;
                }
                return typeof f.placeholder === 'string' ? f.placeholder : '';
            }
        }
        return '';
    }

    const rawArgs = contextValue('arguments')
        || (typeof payload.arguments === 'string' ? payload.arguments : JSON.stringify(payload.arguments || {}));
    const displayArgs = typeof payload.displayArguments === 'string' ? payload.displayArguments : rawArgs;

    const actions = Array.isArray(schema.actions) && schema.actions.length > 0
        ? schema.actions.map(function (a) {
            if (typeof a === 'string') {
                return { name: a, label: a, style: '' };
            }
            return {
                name: (a && typeof a.name === 'string') ? a.name : '',
                label: (a && typeof a.label === 'string' && a.label.trim()) ? a.label : ((a && a.name) ? a.name : 'Continue'),
                style: (a && typeof a.style === 'string') ? a.style : ''
            };
        }).filter(function (a) { return !!a.name; })
        : ((Array.isArray(payload.actions) ? payload.actions : []).map(function (a) {
            return { name: String(a || ''), label: String(a || ''), style: '' };
        }).filter(function (a) { return !!a.name; }));

    const previewSource = String(displayArgs || '').trim();
    let previewSection = '';
    if (!hasSchemaArgumentsPreview && previewSource && previewSource !== '{}') {
        const previewLines = previewSource.split(/\r?\n/);
        const isSingleLinePreview = (previewLines.length === 1)
            || (previewLines.length === 3 && previewLines[0].startsWith("```") && previewLines[2] === "```");
        const previewHtml = '<div class="prompt-args">' + marked.parse(displayArgs) + '</div>';
        previewSection = isSingleLinePreview
            ? ('<div class="prompt-args-inline-label">Tool input preview</div>' + previewHtml)
            : (
                '<details class="prompt-args-toggle">' +
                '  <summary>Tool input preview</summary>' +
                '  ' + previewHtml +
                '</details>'
            );
    }

    const formIdPrefix = 'prompt-field-' + String(frame.eventId || Date.now());
    const fieldsSection = schemaFields.length > 0
        ? (
            '<div class="prompt-fields">' +
            schemaFields.map(function (field, index) {
                const fieldName = String(field.name || '').trim();
                const fieldType = String(field.type || 'text').toLowerCase();
                const fieldLabel = (typeof field.label === 'string' && field.label.trim()) ? field.label : fieldName;
                const fieldPlaceholder = (typeof field.placeholder === 'string') ? field.placeholder : '';
                const fieldValue = (typeof field.value === 'string')
                    ? field.value
                    : ((field.value == null) ? '' : String(field.value));
                const fieldRequired = !!field.required;
                const fieldOptions = Array.isArray(field.options) ? field.options : [];
                const inputId = formIdPrefix + '-' + index;
                const requiredAttr = fieldRequired ? ' required' : '';

                if (fieldType === 'markdown') {
                    const markdownContent = fieldValue || fieldPlaceholder;
                    return (
                        '<div class="prompt-field mb-2">' +
                        '  <div class="prompt-args-inline-label">' + escapeHtml(fieldLabel) + '</div>' +
                        '  <div class="prompt-args">' + marked.parse(markdownContent) + '</div>' +
                        '</div>'
                    );
                }

                if ((fieldType === 'select' || fieldType === 'dropdown') && fieldOptions.length > 0) {
                    const optionsHtml = fieldOptions.map(function (option) {
                        const value = String(option || '');
                        const selectedAttr = (fieldValue && value === fieldValue) ? ' selected' : '';
                        return '<option value="' + escapeHtml(value) + '"' + selectedAttr + '>' + escapeHtml(value) + '</option>';
                    }).join('');
                    const emptyOptionSelected = !fieldValue ? ' selected' : '';
                    return (
                        '<div class="prompt-field mb-2">' +
                        '  <label class="form-label mb-1" for="' + escapeHtml(inputId) + '">' + escapeHtml(fieldLabel) + '</label>' +
                        '  <select class="form-select form-select-sm" id="' + escapeHtml(inputId) + '" data-field-name="' + escapeHtml(fieldName) + '"' + requiredAttr + '>' +
                        '    <option value=""' + emptyOptionSelected + '>Select a value</option>' +
                        optionsHtml +
                        '  </select>' +
                        '</div>'
                    );
                }

                if (fieldType === 'textarea') {
                    return (
                        '<div class="prompt-field mb-2">' +
                        '  <label class="form-label mb-1" for="' + escapeHtml(inputId) + '">' + escapeHtml(fieldLabel) + '</label>' +
                        '  <textarea class="form-control form-control-sm" id="' + escapeHtml(inputId) + '" data-field-name="' + escapeHtml(fieldName) + '" placeholder="' + escapeHtml(fieldPlaceholder) + '" rows="4"' + requiredAttr + '>' + escapeHtml(fieldValue) + '</textarea>' +
                        '</div>'
                    );
                }

                if (fieldType === 'hidden') {
                    return '<input data-field-name="' + escapeHtml(fieldName) + '" type="hidden" value="' + escapeHtml(fieldValue || fieldPlaceholder) + '">';
                }

                if (fieldType === 'checkbox') {
                    const helpText = fieldPlaceholder
                        ? ('<div class="form-text mt-1">' + escapeHtml(fieldPlaceholder) + '</div>')
                        : '';
                    const checkedAttr = String(fieldValue).toLowerCase() === 'true' ? ' checked' : '';
                    return (
                        '<div class="prompt-field form-check mb-2">' +
                        '  <input class="form-check-input" id="' + escapeHtml(inputId) + '" data-field-name="' + escapeHtml(fieldName) + '" type="checkbox" value="true"' + requiredAttr + checkedAttr + '>' +
                        '  <label class="form-check-label" for="' + escapeHtml(inputId) + '">' + escapeHtml(fieldLabel) + '</label>' +
                        '  ' + helpText +
                        '</div>'
                    );
                }

                const inputType = fieldType === 'password' ? 'password' : 'text';
                const readonlyAttr = fieldType === 'readonly' ? ' readonly' : '';
                return (
                    '<div class="prompt-field mb-2">' +
                    '  <label class="form-label mb-1" for="' + escapeHtml(inputId) + '">' + escapeHtml(fieldLabel) + '</label>' +
                    '  <input class="form-control form-control-sm" id="' + escapeHtml(inputId) + '" data-field-name="' + escapeHtml(fieldName) + '" type="' + inputType + '" value="' + escapeHtml(fieldValue) + '" placeholder="' + escapeHtml(fieldPlaceholder) + '"' + readonlyAttr + requiredAttr + '>' +
                    '</div>'
                );
            }).join('') +
            '</div>'
        )
        : '';

    const row = document.createElement('div');
    row.className = 'message-row';
    row.innerHTML =
        '<div class="avatar assistant"><i class="fa-solid fa-robot"></i></div>' +
        '<div class="bubble assistant prompt-required">' +
        '  <div class="prompt-title"><i class="fa-solid fa-shield-halved"></i> ' + escapeHtml(schemaTitle) + '</div>' +
        '  <div class="prompt-reasoning-body">' + marked.parse(reasoning) + '</div>' +
        '  ' + fieldsSection +
        '  ' + previewSection +
        '  <div class="prompt-actions"></div>' +
        '</div>';

    const actionsEl = row.querySelector('.prompt-actions');
    actions.forEach(function (actionDef) {
        const action = actionDef.name;
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn btn-sm prompt-action-btn';
        btn.dataset.action = action;
        btn.textContent = actionDef.label;

        if (actionDef.style) {
            btn.classList.add('btn-' + actionDef.style);
        } else if (action === 'DENIED') {
            btn.classList.add('btn-outline-danger');
        } else {
            btn.classList.add('btn-outline-primary');
        }

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

            if (missingRequiredField) {
                return;
            }

            const schemaIntent = (frame && frame.formSchema && typeof frame.formSchema.intent === 'string')
                ? frame.formSchema.intent
                : '';
            if (schemaIntent === 'OAUTH_AUTHORIZE_OUT_OF_BAND' && action !== 'DENIED') {
                const authorizationUrl = String(fieldValues.authorizationUrl || '').trim();
                if (authorizationUrl) {
                    row.querySelectorAll('.prompt-action-btn').forEach(function (b) { b.disabled = true; });
                    window.location.href = authorizationUrl;
                    return;
                }
            }

            sendAuthorizationAction(frame.eventId, frame.intent, action, fieldValues);
            row.querySelectorAll('.prompt-action-btn').forEach(function (b) { b.disabled = true; });
        });
        actionsEl.appendChild(btn);
    });

    messagesArea.insertBefore(row, typingEl);
    scrollBottom();
}

function handleIncomingUiFrame(frame) {
    if (isTerminalEventFrame(frame)) {
        if (frame.status === 'TERMINAL_START') {
            handleTerminalStart(frame);
        } else if (frame.status === 'TERMINAL_DATA') {
            handleTerminalData(frame);
        } else if (frame.status === 'TERMINAL_ABORTED') {
            handleTerminalAborted(frame);
        } else {
            handleTerminalEnd(frame);
        }
        return;
    }

    switch (frame.type) {
        case 'PROMPT_REQUIRED':
            setAwaitingPostTerminalResponse(false);
            renderPromptRequiredFrame(frame);
            setInputEnabled(true);
            showTyping(false);
            return;

        case 'TEXT_RESPONSE':
            setAwaitingPostTerminalResponse(false);
            if (frame.payload && frame.payload.message && typeof frame.payload.message === 'object') {
                renderMessage(frame.payload.message);
                return;
            }
            if (typeof frame.textResponse === 'string') {
                renderMessage({ role: 'ASSISTANT', content: frame.textResponse });
                return;
            }
            if (frame.payload && typeof frame.payload.content === 'string') {
                renderMessage({ role: 'ASSISTANT', content: frame.payload.content });
                return;
            }
            renderMessage({ role: 'ASSISTANT', content: '' });
            return;

        case 'AGENT_TRANSITION':
            renderAgentTransition(frame.textResponse || '');
            return;

        case 'SKILL_TRANSITION':
            renderSkillEvent(frame.textResponse || '');
            return;

        case 'AGENT_SWITCH':
            // Active agent was changed server-side — update the dropdown
            // Visual notification is handled by the accompanying AGENT_TRANSITION event
            if (agentSel && frame.textResponse) {
                agentSel.value = frame.textResponse;
            }
            return;

        case 'ERROR':
            setAwaitingPostTerminalResponse(false);
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

async function renderSessionRecord(msg, index, messages, lastPromptIndex) {
    if (msg.role === 'PROMPT_REQUIRED') {
        // Only render the final unanswered authorization prompt.
        // Historical prompts that already led to a tool response are omitted.
        if (index !== lastPromptIndex) {
            return;
        }
        const frame = tryParseJson(msg.content);
        if (frame && isUiEventFrame(frame)) {
            renderPromptRequiredFrame(frame);
            return;
        }
    }

    if (msg.role === 'TEXT_RESPONSE') {
        const frame = tryParseJson(msg.content);
        if (frame && typeof frame.textResponse === 'string') {
            renderMessage({ role: 'ASSISTANT', content: frame.textResponse });
            return;
        }
        if (frame && frame.payload && typeof frame.payload.content === 'string') {
            renderMessage({ role: 'ASSISTANT', content: frame.payload.content });
            return;
        }
    }

    if (msg.role === 'TOOL') {
        await renderTerminalSessionRecord(msg, index, messages);
        return;
    }

    if (msg.role === 'AGENT_TRANSITION') {
        renderAgentTransition(msg.content || '');
        return;
    }

    if (msg.role === 'SKILL_TRANSITION') {
        renderSkillEvent(msg.content || '');
        return;
    }

    renderMessage(msg);
}

function sendAuthorizationAction(eventId, intent, action, fields) {
    if (!sessionUuid) return;

    showTyping(true);
    setInputEnabled(false);

    fetch('/api/chat/respond/' + sessionUuid, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            eventId: eventId,
            intent: intent || 'AUTHORIZE_TOOL',
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
            // Response is acknowledged here; rendering is driven by websocket
            // broadcast to avoid duplicate message rendering.
            return resp.text();
        })
        .then(function () {
            // Keep waiting state until websocket event arrives.
        })
        .catch(function (err) {
            showTyping(false);
            setInputEnabled(true);
            renderMessage({ role: 'ERROR', content: 'Failed to submit authorization response: ' + err.message });
        });
}

// ── Lightbox ──────────────────────────────────────────────────────────────────

function openLightbox(src) {
    let lb = document.getElementById('lightbox');
    if (!lb) {
        lb = document.createElement('div');
        lb.id = 'lightbox';
        lb.addEventListener('click', function () { lb.remove(); });
        document.body.appendChild(lb);
    }
    lb.innerHTML = '<img src="' + src + '" alt="attachment">';
    document.body.appendChild(lb);
}

// ── Auto-resize textarea ─────────────────────────────────────────────────────

messageInput.addEventListener('input', function () {
    this.style.height = 'auto';
    this.style.height = Math.min(this.scrollHeight, 140) + 'px';
});

messageInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        chatForm.dispatchEvent(new Event('submit', { cancelable: true }));
    }
});

// ── Attachment strip helpers ──────────────────────────────────────────────────

function syncStripVisibility() {
    attachStrip.classList.toggle('d-none', attachStrip.children.length === 0);
}

function removeAttachment(uuid) {
    const idx = stagedAttachments.findIndex(function (a) { return a.uuid === uuid; });
    if (idx === -1) return;
    stagedAttachments[idx].chipEl.remove();
    stagedAttachments.splice(idx, 1);
    syncStripVisibility();
}

/**
 * Creates an attachment chip and appends it to the strip.
 * Returns the chip element so it can be updated (spinner → final state).
 */
function createChip(tempId, filename) {
    const chip = document.createElement('div');
    chip.className = 'attach-chip';
    chip.dataset.tempId = tempId;

    // Spinner overlay (shown while uploading)
    chip.innerHTML =
        '<div class="chip-spinner">' +
        '  <div class="spinner-border spinner-border-sm text-light" role="status"></div>' +
        '</div>' +
        '<span class="chip-label">' + escapeHtml(filename) + '</span>';

    attachStrip.appendChild(chip);
    syncStripVisibility();
    return chip;
}

function finaliseChip(chip, attachment) {
    // Remove spinner
    const spinner = chip.querySelector('.chip-spinner');
    if (spinner) spinner.remove();

    if (isImage(attachment.mimeType)) {
        const img = document.createElement('img');
        img.className = 'chip-thumb';
        img.src = attachment.url;
        img.alt = attachment.name;
        chip.insertBefore(img, chip.firstChild);
    } else {
        const icon = document.createElement('i');
        icon.className = 'fa-solid ' + mimeIcon(attachment.mimeType) + ' chip-icon';
        chip.insertBefore(icon, chip.firstChild);
    }

    // Label
    let label = chip.querySelector('.chip-label');
    if (!label) {
        label = document.createElement('span');
        label.className = 'chip-label';
        chip.appendChild(label);
    }
    label.textContent = attachment.name;

    // Unsupported-by-AI warning
    if (!attachment.aiSupported) {
        const warn = document.createElement('i');
        warn.className = 'fa-solid fa-triangle-exclamation chip-warn';
        warn.title = 'This file type cannot be processed by the AI';
        chip.appendChild(warn);
    }

    // Remove button
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'chip-remove';
    btn.innerHTML = '<i class="fa-solid fa-xmark"></i>';
    btn.title = 'Remove';
    btn.addEventListener('click', function (e) {
        e.stopPropagation();
        removeAttachment(attachment.uuid);
    });
    chip.appendChild(btn);
}

function markChipError(chip, filename) {
    chip.querySelector('.chip-spinner')?.remove();
    chip.innerHTML =
        '<i class="fa-solid fa-circle-exclamation chip-icon" style="color:var(--bs-danger)"></i>' +
        '<span class="chip-label">' + escapeHtml(filename) + '</span>';

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'chip-remove';
    btn.innerHTML = '<i class="fa-solid fa-xmark"></i>';
    btn.title = 'Dismiss';
    btn.addEventListener('click', function () { chip.remove(); syncStripVisibility(); });
    chip.appendChild(btn);
}

// ── File upload ───────────────────────────────────────────────────────────────

uploadFilesBtn.addEventListener('click', function (e) {
    e.preventDefault();
    fileInput.value = '';          // reset so the same file can be re-selected
    fileInput.click();
});

fileInput.addEventListener('change', function () {
    const files = Array.from(fileInput.files || []);
    files.forEach(uploadFile);
});

function uploadFile(file) {
    const tempId = 'tmp-' + Math.random().toString(36).slice(2);
    const chip   = createChip(tempId, file.name);

    const formData = new FormData();
    formData.append('file', file);

    fetch('/api/files', { method: 'POST', body: formData })
        .then(function (resp) {
            if (!resp.ok) { return resp.json().then(function (b) { throw new Error(b.message || resp.statusText); }); }
            return resp.json();
        })
        .then(function (data) {
            const attachment = {
                uuid:        data.uuid,
                name:        data.name,
                mimeType:    data.mimeType,
                url:         data.url,
                aiSupported: data.aiSupported,
                chipEl:      chip
            };
            stagedAttachments.push(attachment);
            finaliseChip(chip, attachment);
        })
        .catch(function (err) {
            console.error('File upload error:', err);
            markChipError(chip, file.name);
        });
}

// ── Logout ───────────────────────────────────────────────────────────────────

if (logoutBtn) {
    logoutBtn.addEventListener('click', function (e) {
        e.preventDefault();
        // Fetch CSRF token from server meta tag or make logout request with fetch
        fetch('/logout', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded'
            },
            credentials: 'same-origin'
        })
        .then(function (response) {
            // Spring Security redirect to /login?logout=true will be followed
            sessionStorage.removeItem('splashShown');
            window.location.href = '/login?logout=true';
        })
        .catch(function (error) {
            console.error('Logout failed:', error);
            sessionStorage.removeItem('splashShown');
            window.location.href = '/login?logout=true';
        });
    });
}

// ── Sidebar DOM refs ─────────────────────────────────────────────────────────

const sidebarTabBtns = document.querySelectorAll('.sidebar-tab-btn[data-tab]');

function switchSidebarTab(tabName) {
    document.querySelectorAll('.sidebar-tab-content').forEach(function (el) {
        el.classList.toggle('d-none', el.id !== 'tab-' + tabName);
    });
    sidebarTabBtns.forEach(function (btn) {
        btn.classList.toggle('active', btn.dataset.tab === tabName);
    });
    if (tabName === 'skills' && sessionUuid) {
        loadSkillsPanel(sessionUuid);
    }
}

sidebarTabBtns.forEach(function (btn) {
    btn.addEventListener('click', function () {
        switchSidebarTab(btn.dataset.tab);
        if (document.body.classList.contains('sidebar-collapsed')) {
            document.body.classList.remove('sidebar-collapsed');
        }
    });
});

if (sidebarToggle) {
    sidebarToggle.addEventListener('click', function () {
        document.body.classList.toggle('sidebar-collapsed');
    });
}

// ── Skills / Tools panel ─────────────────────────────────────────────────────

/** All available tools from /api/chat/tools — loaded once on demand. */
let _toolsCache = null;
/** All skills — we get them from agent-config + search as needed. */
let _skillsSearchCache = null;

async function loadSkillsPanel(uuid) {
    if (!uuid) return;
    let config;
    try {
        const resp = await fetch('/api/chat/session/' + encodeURIComponent(uuid) + '/agent-config');
        if (!resp.ok) return;
        config = await resp.json();
    } catch (e) {
        console.warn('loadSkillsPanel failed:', e);
        return;
    }
    renderSkillPills('agent-skills-list', config.agentSkills || [], false, false, uuid);
    renderSkillPills('session-skills-list', config.sessionSkills || [], true, false, uuid);
    renderToolPills('agent-tools-list', config.agentTools || [], false, uuid);
    renderToolPills('session-tools-list', config.sessionTools || [], true, uuid);
}

function renderSkillPills(containerId, skills, removable, _unused, sessionUuidRef) {
    const el = document.getElementById(containerId);
    if (!el) return;
    el.innerHTML = '';
    if (skills.length === 0) {
        el.innerHTML = '<span class="text-muted small">' + (removable ? 'None' : 'None assigned') + '</span>';
        return;
    }
    skills.forEach(function (skill) {
        const pill = document.createElement('span');
        pill.className = 'skill-pill ' + (removable ? 'session-skill-pill' : 'agent-skill-pill');
        pill.title = skill.description || '';
        pill.textContent = skill.name;
        if (removable) {
            const removeBtn = document.createElement('button');
            removeBtn.className = 'pill-remove';
            removeBtn.innerHTML = '&times;';
            removeBtn.title = 'Remove';
            removeBtn.addEventListener('click', function () {
                removeSessionSkillAction(sessionUuidRef, skill.uuid);
            });
            pill.appendChild(removeBtn);
        }
        el.appendChild(pill);
    });
}

function renderToolPills(containerId, tools, removable, sessionUuidRef) {
    const el = document.getElementById(containerId);
    if (!el) return;
    el.innerHTML = '';
    if (tools.length === 0) {
        el.innerHTML = '<span class="text-muted small">' + (removable ? 'None' : 'None assigned') + '</span>';
        return;
    }
    tools.forEach(function (tool) {
        const pill = document.createElement('span');
        pill.className = 'tool-pill ' + (removable ? 'session-tool-pill' : 'agent-tool-pill');
        pill.title = tool.description || '';
        pill.textContent = tool.name;
        if (removable) {
            const removeBtn = document.createElement('button');
            removeBtn.className = 'pill-remove';
            removeBtn.innerHTML = '&times;';
            removeBtn.title = 'Remove';
            removeBtn.addEventListener('click', function () {
                removeSessionToolAction(sessionUuidRef, tool.id);
            });
            pill.appendChild(removeBtn);
        }
        el.appendChild(pill);
    });
}

async function removeSessionSkillAction(uuid, skillUuid) {
    try {
        const resp = await fetch('/api/chat/session/' + encodeURIComponent(uuid)
            + '/session-skills/' + encodeURIComponent(skillUuid), { method: 'DELETE' });
        if (resp.ok) loadSkillsPanel(uuid);
    } catch (e) { console.error('removeSessionSkill failed:', e); }
}

async function removeSessionToolAction(uuid, toolId) {
    try {
        const resp = await fetch('/api/chat/session/' + encodeURIComponent(uuid)
            + '/session-tools/' + encodeURIComponent(toolId), { method: 'DELETE' });
        if (resp.ok) loadSkillsPanel(uuid);
    } catch (e) { console.error('removeSessionTool failed:', e); }
}

async function addSessionSkillAction(uuid, skillUuid) {
    try {
        const resp = await fetch('/api/chat/session/' + encodeURIComponent(uuid)
            + '/session-skills/' + encodeURIComponent(skillUuid), { method: 'POST' });
        if (resp.ok) loadSkillsPanel(uuid);
    } catch (e) { console.error('addSessionSkill failed:', e); }
}

async function addSessionToolAction(uuid, toolId) {
    try {
        const resp = await fetch('/api/chat/session/' + encodeURIComponent(uuid)
            + '/session-tools/' + encodeURIComponent(toolId), { method: 'POST' });
        if (resp.ok) loadSkillsPanel(uuid);
    } catch (e) { console.error('addSessionTool failed:', e); }
}

// ── Skill search autocomplete ─────────────────────────────────────────────────

function setupSkillSearch() {
    const input = document.getElementById('skill-search-input');
    const dropdown = document.getElementById('skill-search-results');
    if (!input || !dropdown) return;

    input.addEventListener('input', async function () {
        const query = input.value.trim().toLowerCase();
        dropdown.classList.add('d-none');
        dropdown.innerHTML = '';
        if (query.length < 1) return;
        if (!_skillsSearchCache) {
            // Fetch all skills from the API (we'll use /api/skills if it exists, or just local)
            // For now pull from agent-config which gives session context; use a dedicated search endpoint
            try {
                const resp = await fetch('/api/skills?page=0&pageSize=100');
                if (resp.ok) _skillsSearchCache = await resp.json();
            } catch (e) { _skillsSearchCache = []; }
        }
        const matches = (_skillsSearchCache || []).filter(function (s) {
            return s.name.toLowerCase().includes(query) || (s.description || '').toLowerCase().includes(query);
        }).slice(0, 10);
        if (matches.length === 0) return;
        matches.forEach(function (skill) {
            const item = document.createElement('div');
            item.className = 'skills-search-item';
            item.textContent = skill.name;
            item.title = skill.description || '';
            item.addEventListener('click', function () {
                if (sessionUuid) addSessionSkillAction(sessionUuid, skill.uuid);
                input.value = '';
                dropdown.classList.add('d-none');
            });
            dropdown.appendChild(item);
        });
        dropdown.classList.remove('d-none');
    });

    document.addEventListener('click', function (e) {
        if (!input.contains(e.target) && !dropdown.contains(e.target)) {
            dropdown.classList.add('d-none');
        }
    });
}

// ── Tool search autocomplete ──────────────────────────────────────────────────

function setupToolSearch() {
    const input = document.getElementById('tool-search-input');
    const dropdown = document.getElementById('tool-search-results');
    if (!input || !dropdown) return;

    input.addEventListener('input', async function () {
        const query = input.value.trim().toLowerCase();
        dropdown.classList.add('d-none');
        dropdown.innerHTML = '';
        if (query.length < 1) return;
        if (!_toolsCache) {
            try {
                const resp = await fetch('/api/chat/tools');
                if (resp.ok) _toolsCache = await resp.json();
            } catch (e) { _toolsCache = []; }
        }
        const matches = (_toolsCache || []).filter(function (t) {
            return t.name.toLowerCase().includes(query)
                || (t.description || '').toLowerCase().includes(query)
                || (t.category || '').toLowerCase().includes(query);
        }).slice(0, 10);
        if (matches.length === 0) return;
        matches.forEach(function (tool) {
            const item = document.createElement('div');
            item.className = 'skills-search-item';
            item.textContent = tool.name + (tool.category ? '  [' + tool.category + ']' : '');
            item.title = tool.description || '';
            item.addEventListener('click', function () {
                if (sessionUuid) addSessionToolAction(sessionUuid, tool.id);
                input.value = '';
                dropdown.classList.add('d-none');
            });
            dropdown.appendChild(item);
        });
        dropdown.classList.remove('d-none');
    });

    document.addEventListener('click', function (e) {
        if (!input.contains(e.target) && !dropdown.contains(e.target)) {
            dropdown.classList.add('d-none');
        }
    });
}

setupSkillSearch();
setupToolSearch();

if (newChatBtn) {
    newChatBtn.addEventListener('click', function () {
        createNewChat();
    });
}

// ── WebSocket / STOMP ────────────────────────────────────────────────────────

function connectWebSocket() {
    setStatus('connecting');
    stomp = new StompJs.Client({
        webSocketFactory: function () { return new SockJS('/ws'); },
        reconnectDelay: 5000,
        onConnect: function () {
            setStatus('connected');
            subscribeToCurrentSession();
        },
        onDisconnect: function () { setStatus('disconnected'); },
        onStompError: function () { setStatus('disconnected'); }
    });
    stomp.activate();
}

function subscribeToCurrentSession() {
    if (!stomp || !stomp.connected || !sessionUuid) {
        return;
    }
    if (chatSubscription) {
        chatSubscription.unsubscribe();
        chatSubscription = null;
    }
    chatSubscription = stomp.subscribe('/topic/chat/' + sessionUuid, function (frame) {
        const msg = JSON.parse(frame.body);
        if (isTerminalEventFrame(msg)) {
            handleIncomingUiFrame(msg);
            return;
        }

        // Non-terminal TOOL messages (e.g. connectSsh, sshDownloadFile) are internal
        // plumbing — the result is already used server-side. Never render their raw JSON.
        if (msg.role === 'TOOL' && !isTerminalToolMessage(msg)) {
            return;
        }

        if (isTerminalToolMessage(msg)) {
            setAwaitingPostTerminalResponse(true);
        } else {
            setAwaitingPostTerminalResponse(false);
            showTyping(false);
            if (!hasLiveTerminal()) {
                setInputEnabled(true);
            }
        }

        if (isUiEventFrame(msg)) {
            handleIncomingUiFrame(msg);
        } else {
            if (isTerminalToolMessage(msg)) {
                renderLiveToolMessage(msg);
            } else {
                renderMessage(msg);
            }
        }

        if (!hasLiveTerminal() && !awaitingPostTerminalResponse) {
            focusMessageInput();
        }
    });
}

// ── Session init + sidebar ───────────────────────────────────────────────────

function renderSessionList() {
    sessionListEl.innerHTML = '';
    if (!sessions || sessions.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'chat-session-empty';
        empty.textContent = 'No chats yet';
        sessionListEl.appendChild(empty);
        return;
    }

    sessions.forEach(function (session) {
        const item = document.createElement('div');
        item.className = 'chat-session-item' + (session.sessionUuid === sessionUuid ? ' active' : '');

        const isEditing = editingSessionUuid === session.sessionUuid;

        if (isEditing) {
            const input = document.createElement('input');
            input.type = 'text';
            input.className = 'chat-session-rename-input';
            input.value = session.sessionName || 'Untitled';
            input.maxLength = 60;

            const saveBtn = document.createElement('button');
            saveBtn.type = 'button';
            saveBtn.className = 'chat-session-rename chat-session-rename-save';
            saveBtn.title = 'Save name';
            saveBtn.setAttribute('aria-label', 'Save name');
            saveBtn.innerHTML = '<i class="fa-solid fa-check"></i>';

            const cancelBtn = document.createElement('button');
            cancelBtn.type = 'button';
            cancelBtn.className = 'chat-session-rename chat-session-rename-cancel';
            cancelBtn.title = 'Cancel rename';
            cancelBtn.setAttribute('aria-label', 'Cancel rename');
            cancelBtn.innerHTML = '<i class="fa-solid fa-xmark"></i>';

            const submit = function () {
                submitSessionRename(session.sessionUuid, input.value);
            };

            saveBtn.addEventListener('click', submit);
            cancelBtn.addEventListener('click', function () {
                editingSessionUuid = null;
                renderSessionList();
            });

            input.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    submit();
                }
                if (e.key === 'Escape') {
                    e.preventDefault();
                    editingSessionUuid = null;
                    renderSessionList();
                }
            });

            item.appendChild(input);
            item.appendChild(saveBtn);
            item.appendChild(cancelBtn);
            sessionListEl.appendChild(item);
            requestAnimationFrame(function () {
                input.focus();
                input.select();
            });
            return;
        }

        const openBtn = document.createElement('button');
        openBtn.type = 'button';
        openBtn.className = 'chat-session-open';
        openBtn.innerHTML =
            '<span class="chat-session-name">' + escapeHtml(session.sessionName || 'Untitled') + '</span>' +
            '<span class="chat-session-meta">' + escapeHtml(formatRelativeTime(session.createdAt)) + '</span>';
        openBtn.addEventListener('click', function () {
            loadSession(session.sessionUuid);
        });

        const renameBtn = document.createElement('button');
        renameBtn.type = 'button';
        renameBtn.className = 'chat-session-rename';
        renameBtn.title = 'Rename chat';
        renameBtn.setAttribute('aria-label', 'Rename chat');
        renameBtn.innerHTML = '<i class="fa-solid fa-pen"></i>';
        renameBtn.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            editingSessionUuid = session.sessionUuid;
            renderSessionList();
        });

        item.appendChild(openBtn);
        item.appendChild(renameBtn);
        sessionListEl.appendChild(item);
    });
}

function submitSessionRename(targetSessionUuid, nextName) {
    fetch('/api/chat/session/' + encodeURIComponent(targetSessionUuid) + '/name', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: nextName })
    })
        .then(function (resp) {
            if (!resp.ok) { throw new Error('HTTP ' + resp.status + ' — ' + resp.statusText); }
            return resp.json();
        })
        .then(function (renamed) {
            editingSessionUuid = null;
            sessions = sessions.map(function (s) {
                return s.sessionUuid === renamed.sessionUuid ? renamed : s;
            });
            renderSessionList();
            if (sessionUuid === renamed.sessionUuid) {
                sessionDisplay.textContent = (renamed.sessionName || 'Untitled') + ' · ' + sessionUuid.substring(0, 8) + '…';
            }
        })
        .catch(function (err) {
            editingSessionUuid = null;
            renderSessionList();
            renderMessage({ role: 'ERROR', content: '**Failed to rename chat:** ' + err.message });
        });
}

function loadSessionList() {
    return fetch('/api/chat/sessions')
        .then(function (resp) {
            if (!resp.ok) { throw new Error('HTTP ' + resp.status + ' — ' + resp.statusText); }
            return resp.json();
        })
        .then(function (data) {
            sessions = Array.isArray(data) ? data : [];
            renderSessionList();
            return sessions;
        });
}

function loadAgents(activeAgentTemplateId) {
    fetch('/api/chat/agents?type=INTERACTIVE')
        .then(function (resp) { return resp.ok ? resp.json() : Promise.resolve([]); })
        .then(function (agents) {
            // Preserve the default option and repopulate the rest
            agentSel.innerHTML = '<option value="">Default (Concierge)</option>';
            agents.forEach(function (agent) {
                const opt = document.createElement('option');
                opt.value = agent.uuid;
                opt.textContent = agent.name;
                agentSel.appendChild(opt);
            });
            agentSel.value = activeAgentTemplateId || '';
        })
        .catch(function () { /* non-critical */ });
}

agentSel.addEventListener('change', function () {
    if (!sessionUuid) return;
    const agentTemplateId = agentSel.value;
    const selectedLabel = agentSel.options[agentSel.selectedIndex].text;
    // Derive the display name: strip the "Default (...)" wrapper if present
    const agentDisplayName = agentTemplateId
        ? selectedLabel
        : 'Concierge';
    fetch('/api/chat/session/' + encodeURIComponent(sessionUuid) + '/agent', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ agentTemplateId: agentTemplateId || '' })
    })
        .then(function (resp) {
            if (!resp.ok) {
                console.warn('Agent switch failed: HTTP ' + resp.status);
                // Revert dropdown to avoid misleading state
                loadAgents(null);
            } else {
                renderAgentTransition('Changed to ' + agentDisplayName);
            }
        })
        .catch(function (err) { console.warn('Agent switch error:', err); });
});

function loadSession(targetSessionUuid) {
    const { provider, modelId } = parseProviderModel(providerSel.value);
    let url = '/api/chat/session?provider=' + encodeURIComponent(provider);
    if (modelId) url += '&modelId=' + encodeURIComponent(modelId);
    if (targetSessionUuid) {
        url += '&sessionUuid=' + encodeURIComponent(targetSessionUuid);
    }

    return fetch(url)
        .then(function (resp) {
            if (!resp.ok) { throw new Error('HTTP ' + resp.status + ' — ' + resp.statusText); }
            return resp.json();
        })
        .then(async function (data) {
            sessionUuid = data.sessionUuid;
            sessionDisplay.textContent = (data.sessionName || 'Untitled') + ' · ' + sessionUuid.substring(0, 8) + '…';
            loadAgents(data.activeAgentTemplateId);
            loadSkillsPanel(sessionUuid);
            clearConversationUi();
            const messages = data.messages || [];
            const lastPromptIndex = findLastUnansweredPromptIndex(messages);
            for (let i = 0; i < messages.length; i++) {
                await renderSessionRecord(messages[i], i, messages, lastPromptIndex);
            }

            if (!stomp) {
                connectWebSocket();
            } else if (stomp.connected) {
                subscribeToCurrentSession();
            }

            if (data.provider) {
                // Build combined key and try to select the matching option
                const combinedKey = data.provider + ':' + (data.modelId || '');
                const matchFull = providerSel.querySelector('option[value="' + combinedKey + '"]');
                const matchProvider = providerSel.querySelector('option[value^="' + data.provider + ':"]');
                if (matchFull) {
                    providerSel.value = combinedKey;
                } else if (matchProvider) {
                    providerSel.value = matchProvider.value;
                }
            }

            loadSessionList();
            focusMessageInput();
            checkPendingSessions();

            // Show a welcome message for new (empty) sessions
            if (messages.length === 0) {
                showTyping(true);
                setInputEnabled(false);
                fetch('/api/chat/welcome?provider=' + encodeURIComponent(provider))
                    .then(function (resp) { return resp.ok ? resp.json() : Promise.reject(resp.statusText); })
                    .then(function (welcome) {
                        showTyping(false);
                        renderMessage({ role: 'ASSISTANT', content: welcome.content });
                        setInputEnabled(true);
                        welcomeSignal.resolve();
                    })
                    .catch(function () {
                        showTyping(false);
                        setInputEnabled(true);
                        welcomeSignal.resolve();
                    });
            } else {
                // Existing session — dismiss splash immediately
                welcomeSignal.resolve();
            }
        })
        .catch(function (err) {
            renderMessage({ role: 'ERROR', content: '**Failed to load session:** ' + err.message });
            setStatus('disconnected');
            setInputEnabled(false);
            welcomeSignal.resolve(); // ensure splash is never stuck
        });
}

function createNewChat() {
    const { provider, modelId } = parseProviderModel(providerSel.value);
    let url = '/api/chat/session/new?provider=' + encodeURIComponent(provider);
    if (modelId) url += '&modelId=' + encodeURIComponent(modelId);
    fetch(url)
        .then(function (resp) {
            if (!resp.ok) { throw new Error('HTTP ' + resp.status + ' — ' + resp.statusText); }
            return resp.json();
        })
        .then(function (session) {
            return loadSession(session.sessionUuid);
        })
        .catch(function (err) {
            renderMessage({ role: 'ERROR', content: '**Failed to create new chat:** ' + err.message });
        });
}

function initSession() {
    // Always bootstrap against /api/chat/session with no explicit session UUID
    // so backend can bind the active chat to the current HTTP session.
    loadSession()
        .catch(function (err) {
            renderMessage({ role: 'ERROR', content: '**Failed to initialise session:** ' + err.message });
            setStatus('disconnected');
            setInputEnabled(false);
        });
}

// ── Form submit ──────────────────────────────────────────────────────────────

chatForm.addEventListener('submit', function (e) {
    e.preventDefault();
    const content = messageInput.value.trim();
    const hasAttachments = stagedAttachments.length > 0;

    if (!content && !hasAttachments) return;
    if (waiting || !stomp || !stomp.connected) return;

    // Block if any attachment is not AI-supported
    const unsupported = stagedAttachments.filter(function (a) { return !a.aiSupported; });
    if (unsupported.length > 0) {
        const names = unsupported.map(function (a) { return a.name; }).join(', ');
        renderMessage({
            role: 'ERROR',
            content: 'The following file(s) cannot be processed by the AI and must be removed before sending: **' + names + '**'
        });
        return;
    }

    // Render user message immediately (with attachments)
    collapseCompletedTerminals();
    const attachmentSnapshot = stagedAttachments.slice();
    renderMessage({
        role: 'USER',
        content: content,
        attachments: attachmentSnapshot.map(function (a) {
            return { uuid: a.uuid, name: a.name, mimeType: a.mimeType };
        })
    });

    const attachmentUuids = attachmentSnapshot.map(function (a) { return a.uuid; });

    // Clear input + staged attachments
    messageInput.value = '';
    messageInput.style.height = 'auto';
    stagedAttachments = [];
    attachStrip.innerHTML = '';
    syncStripVisibility();

    showTyping(true);
    setInputEnabled(false);

    stomp.publish({
        destination: '/app/chat.send',
        body: JSON.stringify({
            sessionUuid:     sessionUuid,
            content:         content,
            provider:        parseProviderModel(providerSel.value).provider,
            attachmentUuids: attachmentUuids
        })
    });
});

// ── Splash ───────────────────────────────────────────────────────────────────

// Resolved when the welcome message is ready (or skipped for existing sessions).
const welcomeSignal = (function () {
    let resolve;
    const promise = new Promise(function (res) { resolve = res; });
    return { promise: promise, resolve: resolve };
}());

(function runSplash() {
    const splash     = document.getElementById('splash');
    const chatLayout = document.querySelector('.chat-layout');

    // Skip the splash entirely when navigating back from another page
    // (splash was already shown during this browser session / login).
    if (sessionStorage.getItem('splashShown')) {
        splash.remove();
        chatLayout.classList.add('visible');
        welcomeSignal.resolve();
        return;
    }

    const splashImg  = splash.querySelector('img');
    const taglineEl  = document.getElementById('splash-tagline');
    const typedEl    = document.getElementById('splash-typed');
    const cursorEl   = document.getElementById('splash-cursor');

    const SPLASH_MESSAGE = 'Loading the Vork Concierge...';

    function dismissSplash() {
        sessionStorage.setItem('splashShown', '1');
        cursorEl.classList.remove('blink');
        cursorEl.style.opacity = '0';
        splashImg.classList.add('glitch');
        setTimeout(function () {
            chatLayout.classList.add('visible');
            splash.classList.add('fade-out');
            focusMessageInput();
            splash.addEventListener('transitionend', function () { splash.remove(); }, { once: true });
        }, 600);
    }

    function charDelay(ch) {
        // Base 48-88 ms per character; slower after punctuation; random micro-pauses
        let delay = 48 + Math.random() * 40;
        if (ch === '.' || ch === ',') { delay += 100 + Math.random() * 120; }
        if (Math.random() < 0.14)     { delay += 70  + Math.random() * 130; } // thinking pause
        return delay;
    }

    function typeMessage(index) {
        if (index >= SPLASH_MESSAGE.length) {
            // Typing finished — wait for welcome signal then dismiss
            welcomeSignal.promise.then(dismissSplash);
            return;
        }
        typedEl.textContent += SPLASH_MESSAGE[index];
        setTimeout(function () { typeMessage(index + 1); }, charDelay(SPLASH_MESSAGE[index]));
    }

    // Show tagline + cursor after logo pop animation finishes (~0.8 s)
    setTimeout(function () {
        taglineEl.style.opacity = '1';
        cursorEl.classList.add('blink');

        // Blink cursor alone for 500 ms, then start typing
        setTimeout(function () { typeMessage(0); }, 500);
    }, 800);
}());

// ── Boot ─────────────────────────────────────────────────────────────────────

// ── Pending sessions alert ────────────────────────────────────────────────────

/**
 * Checks whether there are any off-channel sessions (Telegram / Background)
 * currently awaiting user input.  If so, shows a non-intrusive amber alert bar
 * above the message feed with a link to /pending-sessions.
 *
 * Called once per session initialisation so the user is notified at login time.
 */
function checkPendingSessions() {
    fetch('/api/chat/sessions/pending-input')
        .then(function (r) { return r.ok ? r.json() : Promise.reject('HTTP ' + r.status); })
        .then(function (sessions) {
            var alertEl = document.getElementById('pending-sessions-alert');
            if (!alertEl) return;
            if (!sessions || sessions.length === 0) {
                alertEl.classList.add('d-none');
                return;
            }
            var count = sessions.length;
            var label = count === 1 ? '1 session is' : count + ' sessions are';
            alertEl.innerHTML =
                '<i class="fa-solid fa-inbox me-2"></i>'
                + '<strong>' + label + ' waiting for your input.</strong> '
                + '<a href="/pending-sessions" class="pending-alert-link">Review now</a>'
                + '<button type="button" class="btn-close btn-close-white btn-close-sm ms-auto" aria-label="Dismiss"></button>';
            alertEl.classList.remove('d-none');

            alertEl.querySelector('.btn-close').addEventListener('click', function () {
                alertEl.classList.add('d-none');
            });
        })
        .catch(function () { /* silent — non-critical */ });
}

// Load available provider/model options first, then initialise the session.
// The dropdown is pre-seeded with a Gemini placeholder so the UI is usable
// even if the models endpoint is slow.
loadAvailableModels().then(function () { initSession(); }).catch(function () { initSession(); });

// When the user switches provider/model, persist the choice to the active session.
providerSel.addEventListener('change', function () {
    if (!sessionUuid) return;
    const { provider, modelId } = parseProviderModel(providerSel.value);
    const selectedOpt = providerSel.options[providerSel.selectedIndex];
    const label = selectedOpt ? selectedOpt.text : (provider + (modelId ? ' \u2014 ' + modelId : ''));
    renderModelSwitch('Switched to ' + label);
    fetch('/api/chat/session/' + encodeURIComponent(sessionUuid) + '/model', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider: provider, modelId: modelId })
    }).catch(function (err) {
        console.warn('Failed to update session model:', err);
    });
});
