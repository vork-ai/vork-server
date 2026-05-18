/* global StompJs, SockJS, marked */

'use strict';

marked.use({ breaks: true, gfm: true });

const messagesArea   = document.getElementById('messages-area');
const typingEl       = document.getElementById('typing-indicator');
const messageInput   = document.getElementById('message-input');
const sendBtn        = document.getElementById('send-btn');
const chatForm       = document.getElementById('chat-form');
const providerSel    = document.getElementById('provider-select');
const statusDot      = document.getElementById('status-dot');
const sessionDisplay = document.getElementById('session-display');
const attachStrip    = document.getElementById('attachment-strip');
const fileInput      = document.getElementById('file-input');
const uploadFilesBtn = document.getElementById('upload-files-btn');

let sessionUuid = null;
let stomp       = null;
let waiting     = false;

const terminalState = {
    views: new Map(),
    pendingByTerminal: new Map(),
    socketsByTerminal: new Map()
};

const TERMINAL_ROW_PREFIX = 'terminal-';
const TERMINAL_REPLAY_MAX_CHARS = 24000;
const TERMINAL_REPLAY_TAIL_CHARS = 12000;
const textDecoder = new TextDecoder();
const textEncoder = new TextEncoder();

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

function isUiEventFrame(obj) {
    return obj && typeof obj === 'object'
        && typeof obj.type === 'string'
        && typeof obj.intent === 'string'
        && obj.payload && typeof obj.payload === 'object';
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
        && (obj.status === 'TERMINAL_START' || obj.status === 'TERMINAL_END' || obj.status === 'TERMINAL_DATA');
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
        '    <div class="terminal-stream-title"><i class="fa-solid fa-terminal"></i> <code>' + escapeHtml(command || 'Live Terminal Stream') + '</code></div>' +
        '    <button type="button" class="terminal-stream-toggle" aria-label="Hide output" title="Hide output">' +
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
        live: false,
        completed: false,
        expanded: expanded,
        command: command || 'Live Terminal Stream'
    };

    view.toggleBtn.addEventListener('click', function () {
        setTerminalExpanded(view, !view.expanded);
    });

    terminalState.views.set(getTerminalViewId(terminalId), view);
    setTerminalExpanded(view, expanded);
    return view;
}

function getPendingTerminalState(terminalId) {
    let pending = terminalState.pendingByTerminal.get(terminalId);
    if (!pending) {
        pending = {
            chunks: [],
            endReceived: false
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

    terminalState.pendingByTerminal.delete(view.terminalId);
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
    markTerminalCompleted(view);

    view.passivePre.textContent = view.bufferedText || '(no terminal output)';
    view.passivePre.classList.remove('d-none');
    view.xtermContainer.classList.add('d-none');
    setTerminalExpanded(view, view.expanded);

    if (!hasLiveTerminal()) {
        setInputEnabled(true);
        focusMessageInput();
    }
}

function setTerminalExpanded(view, expanded) {
    if (!view) {
        return;
    }
    view.expanded = !!expanded;
    view.body.classList.toggle('d-none', !view.expanded);
    if (view.toggleBtn) {
        const icon = view.toggleBtn.querySelector('i');
        if (icon) {
            icon.className = view.expanded
                ? 'fa-solid fa-chevron-up'
                : 'fa-solid fa-chevron-down';
        }
        const label = view.expanded ? 'Hide output' : 'Show output';
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
    if (view.toggleBtn) {
        view.toggleBtn.disabled = false;
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

    showTyping(false);
    setInputEnabled(false);

    const command = frame && frame.command ? String(frame.command) : 'Live Terminal Stream';
    const view = createTerminalInlineRow(terminalId, command, { expanded: true });
    cleanupTerminalLiveBindings(view);
    view.bufferedText = '';
    view.pendingChunks = [];
    view.endReceived = false;
    view.live = true;
    view.completed = false;

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

    if (command) {
        term.writeln('$ ' + command);
        view.bufferedText += '$ ' + command + '\n';
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
    if (!terminalId) {
        if (!hasLiveTerminal()) {
            setInputEnabled(true);
            focusMessageInput();
        }
        return;
    }

    const view = findTerminalView(terminalId);

    if (!view) {
        const pending = getPendingTerminalState(terminalId);
        pending.endReceived = true;
        if (!hasLiveTerminal()) {
            setInputEnabled(true);
            focusMessageInput();
        }
        return;
    }

    view.endReceived = true;
    flushTerminalChunks(view);
    maybeFinalizeTerminalView(view);
}

function renderTerminalTranscript(terminalId, command, output, expanded, attachment) {
    const view = createTerminalInlineRow(terminalId, command, { expanded: expanded });
    view.bufferedText = output || '';
    view.completed = true;
    view.live = false;
    view.xtermContainer.classList.add('d-none');
    view.passivePre.textContent = view.bufferedText || '(no terminal output)';
    view.passivePre.classList.remove('d-none');
    if (attachment && attachment.uuid) {
        const download = document.createElement('a');
        download.className = 'bubble-file-link mt-2 d-inline-block';
        download.href = '/api/files/' + attachment.uuid;
        download.download = attachment.name || 'terminal-output.txt';
        download.target = '_blank';
        download.innerHTML = '<i class="fa-solid fa-file-lines"></i>' + escapeHtml(attachment.name || 'Download full terminal output');
        view.passivePre.parentElement.appendChild(download);
    }
    setTerminalExpanded(view, expanded);
    return view;
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
    renderTerminalTranscript(msg.uuid, transcript.command, output, expanded, attachment);
    return true;
}

function renderPromptRequiredFrame(frame) {
    const payload = frame.payload || {};
    const reasoning = typeof payload.reasoning === 'string' && payload.reasoning.trim()
        ? payload.reasoning
        : 'The AI requested this action to process your command.';
    const rawArgs = typeof payload.arguments === 'string' ? payload.arguments : JSON.stringify(payload.arguments || {});
    const displayArgs = typeof payload.displayArguments === 'string' ? payload.displayArguments : rawArgs;
    const actions = Array.isArray(payload.actions) ? payload.actions : [];

    const previewSource = String(displayArgs || '').trim();
    const previewLines = previewSource.split(/\r?\n/);
    const isSingleLinePreview = (previewLines.length === 1)
        || (previewLines.length === 3 && previewLines[0].startsWith("```") && previewLines[2] === "```");
    const previewHtml = '<div class="prompt-args">' + marked.parse(displayArgs) + '</div>';
    const previewSection = isSingleLinePreview
        ? ('<div class="prompt-args-inline-label">Tool input preview</div>' + previewHtml)
        : (
            '<details class="prompt-args-toggle">' +
            '  <summary>Tool input preview</summary>' +
            '  ' + previewHtml +
            '</details>'
        );

    const row = document.createElement('div');
    row.className = 'message-row';
    row.innerHTML =
        '<div class="avatar assistant"><i class="fa-solid fa-robot"></i></div>' +
        '<div class="bubble assistant prompt-required">' +
        '  <div class="prompt-title"><i class="fa-solid fa-shield-halved"></i> Authorization Required</div>' +
        '  <div class="prompt-reasoning-body">' + marked.parse(reasoning) + '</div>' +
        '  ' + previewSection +
        '  <div class="prompt-actions"></div>' +
        '</div>';

    const actionsEl = row.querySelector('.prompt-actions');
    actions.forEach(function (action) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn btn-sm prompt-action-btn';
        btn.dataset.action = action;
        btn.textContent = action;

        if (action === 'DENIED') {
            btn.classList.add('btn-outline-danger');
        } else {
            btn.classList.add('btn-outline-primary');
        }

        btn.addEventListener('click', function () {
            sendAuthorizationAction(frame.eventId, action, {});
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
        } else {
            handleTerminalEnd(frame);
        }
        return;
    }

    switch (frame.type) {
        case 'PROMPT_REQUIRED':
            renderPromptRequiredFrame(frame);
            setInputEnabled(true);
            showTyping(false);
            return;

        case 'TEXT_RESPONSE':
            if (frame.payload && frame.payload.message && typeof frame.payload.message === 'object') {
                renderMessage(frame.payload.message);
                return;
            }
            if (frame.payload && typeof frame.payload.content === 'string') {
                renderMessage({ role: 'ASSISTANT', content: frame.payload.content });
                return;
            }
            renderMessage({ role: 'ASSISTANT', content: '' });
            return;

        case 'ERROR':
            renderMessage({
                role: 'ERROR',
                content: (frame.payload && frame.payload.message) ? String(frame.payload.message) : 'Unknown error'
            });
            return;

        default:
            if (frame.payload && frame.payload.message && typeof frame.payload.message === 'object') {
                renderMessage(frame.payload.message);
            }
    }
}

function renderSessionRecord(msg, index, messages) {
    if (msg.role === 'PROMPT_REQUIRED') {
        const frame = tryParseJson(msg.content);
        if (frame && isUiEventFrame(frame)) {
            renderPromptRequiredFrame(frame);
            return;
        }
    }

    if (msg.role === 'TEXT_RESPONSE') {
        const frame = tryParseJson(msg.content);
        if (frame && frame.payload && typeof frame.payload.content === 'string') {
            renderMessage({ role: 'ASSISTANT', content: frame.payload.content });
            return;
        }
    }

    if (msg.role === 'TOOL') {
        renderTerminalSessionRecord(msg, index, messages);
        return;
    }

    renderMessage(msg);
}

function sendAuthorizationAction(eventId, action, fields) {
    if (!sessionUuid) return;

    showTyping(true);
    setInputEnabled(false);

    fetch('/api/chat/respond/' + sessionUuid, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            eventId: eventId,
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

// ── WebSocket / STOMP ────────────────────────────────────────────────────────

function connectWebSocket() {
    setStatus('connecting');
    stomp = new StompJs.Client({
        webSocketFactory: function () { return new SockJS('/ws'); },
        reconnectDelay: 5000,
        onConnect: function () {
            setStatus('connected');
            stomp.subscribe('/topic/chat/' + sessionUuid, function (frame) {
                const msg = JSON.parse(frame.body);
                if (isTerminalEventFrame(msg)) {
                    handleIncomingUiFrame(msg);
                    return;
                }

                showTyping(false);
                if (!hasLiveTerminal()) {
                    setInputEnabled(true);
                }

                if (isUiEventFrame(msg)) {
                    handleIncomingUiFrame(msg);
                } else {
                    renderMessage(msg);
                }

                if (!hasLiveTerminal()) {
                    focusMessageInput();
                }
            });
        },
        onDisconnect: function () { setStatus('disconnected'); },
        onStompError: function () { setStatus('disconnected'); }
    });
    stomp.activate();
}

// ── Session init ─────────────────────────────────────────────────────────────

function initSession() {
    fetch('/api/chat/session?provider=' + providerSel.value)
        .then(function (resp) {
            if (!resp.ok) { throw new Error('HTTP ' + resp.status + ' \u2014 ' + resp.statusText); }
            return resp.json();
        })
        .then(function (data) {
            sessionUuid = data.sessionUuid;
            sessionDisplay.textContent = sessionUuid.substring(0, 8) + '\u2026';
            (data.messages || []).forEach(function (msg, index, messages) {
                renderSessionRecord(msg, index, messages);
            });
            connectWebSocket();
            focusMessageInput();
        })
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
            provider:        providerSel.value,
            attachmentUuids: attachmentUuids
        })
    });
});

// ── Splash ───────────────────────────────────────────────────────────────────

(function runSplash() {
    const splash     = document.getElementById('splash');
    const chatLayout = document.querySelector('.chat-layout');

    setTimeout(function () {
        chatLayout.classList.add('visible');
        splash.classList.add('fade-out');
        focusMessageInput();
        splash.addEventListener('transitionend', function () { splash.remove(); }, { once: true });
    }, 5000);
}());

// ── Boot ─────────────────────────────────────────────────────────────────────

initSession();
