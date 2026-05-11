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

let sessionUuid = null;
let stomp       = null;
let waiting     = false;

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

// ── Render a single message bubble ───────────────────────────────────────────

function renderMessage(msg) {
    const isUser    = msg.role === 'USER';
    const html      = isUser
        ? escapeHtml(msg.content).replace(/\n/g, '<br>')
        : marked.parse(msg.content || '');

    const bubbleCls  = isUser ? 'user' : (msg.role === 'ERROR' ? 'error' : 'assistant');
    const avatarCls  = isUser ? 'user' : 'assistant';
    const avatarIcon = isUser
        ? '<i class="fa-solid fa-user"></i>'
        : '<i class="fa-solid fa-robot"></i>';

    const row = document.createElement('div');
    row.className = 'message-row' + (isUser ? ' user' : '');
    row.innerHTML =
        '<div class="avatar ' + avatarCls + '">' + avatarIcon + '</div>' +
        '<div class="bubble ' + bubbleCls + '">' + html + '</div>';

    messagesArea.insertBefore(row, typingEl);
    scrollBottom();
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
                showTyping(false);
                setInputEnabled(true);
                renderMessage(msg);
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
            (data.messages || []).forEach(renderMessage);
            connectWebSocket();
            messageInput.focus();
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
    if (!content || waiting || !stomp || !stomp.connected) { return; }

    renderMessage({ role: 'USER', content: content });

    messageInput.value = '';
    messageInput.style.height = 'auto';

    showTyping(true);
    setInputEnabled(false);

    stomp.publish({
        destination: '/app/chat.send',
        body: JSON.stringify({ sessionUuid: sessionUuid, content: content, provider: providerSel.value })
    });
});

// ── Splash ───────────────────────────────────────────────────────────────────

(function runSplash() {
    const splash     = document.getElementById('splash');
    const chatLayout = document.querySelector('.chat-layout');

    setTimeout(function () {
        chatLayout.classList.add('visible');
        splash.classList.add('fade-out');
        splash.addEventListener('transitionend', function () { splash.remove(); }, { once: true });
    }, 5000);
}());

// ── Boot ─────────────────────────────────────────────────────────────────────

initSession();
