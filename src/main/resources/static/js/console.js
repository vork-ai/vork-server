/* ────────────────────────────────────────────────────────────────────────────
 * console.js — xterm.js + WebSocket bridge for the Vork SSH console panel
 * ────────────────────────────────────────────────────────────────────────────
 * Lifecycle:
 *   - Terminal and WebSocket are initialised on DOMContentLoaded.
 *   - The SSH session is established immediately (even while panel is hidden).
 *   - Toggling the Console button merely shows/hides the panel and fits the
 *     terminal to its container.
 * ──────────────────────────────────────────────────────────────────────────── */

(function () {
    'use strict';

    var terminal = null;
    var fitAddon = null;
    var ws       = null;
    var panel    = null;
    var pendingWrite = '';  // Buffer for batching writes
    var writeScheduled = false;

    // ── Init ──────────────────────────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        panel = document.getElementById('console-panel');

        // Create the xterm.js Terminal
        terminal = new Terminal({
            theme: {
                background:  '#0d0d0d',
                foreground:  '#e0e0e0',
                cursor:      '#e0e0e0',
                black:       '#000000',
                brightBlack: '#555555',
                white:       '#bbbbbb',
                brightWhite: '#ffffff'
            },
            fontFamily: 'Menlo, Consolas, "DejaVu Sans Mono", monospace',
            fontSize:   13,
            lineHeight: 1.2,
            cursorBlink: true,
            scrollback:  1000
        });

        fitAddon = new FitAddon.FitAddon();
        terminal.loadAddon(fitAddon);
        terminal.open(document.getElementById('console-terminal'));

        // Connect WebSocket immediately so the SSH session is ready
        connectWebSocket();

        // Wire the toggle button
        var toggleBtn = document.getElementById('console-toggle');
        if (toggleBtn) {
            toggleBtn.addEventListener('click', toggleConsole);
        }

        // Wire the close button inside the panel
        var closeBtn = document.getElementById('console-close');
        if (closeBtn) {
            closeBtn.addEventListener('click', hideConsole);
        }

        // Re-fit on window resize when panel is open
        window.addEventListener('resize', function () {
            if (panel && panel.classList.contains('open')) {
                fitAndResize();
            }
        });
    });

    // ── WebSocket ─────────────────────────────────────────────────────────────

    function connectWebSocket() {
        var proto = location.protocol === 'https:' ? 'wss' : 'ws';
        ws = new WebSocket(proto + '://' + location.host + '/terminal');

        ws.binaryType = 'arraybuffer';

        ws.addEventListener('open', function () {
            terminal.writeln('\x1b[32mConnected to Vork SSH console.\x1b[0m');
            // Send initial size
            sendResize();
        });

        ws.addEventListener('message', function (event) {
            var data;
            if (typeof event.data === 'string') {
                data = event.data;
            } else {
                // ArrayBuffer — convert to string
                data = new TextDecoder().decode(new Uint8Array(event.data));
            }
            
            // Buffer the data and flush quickly via timer to avoid rAF throttling.
            pendingWrite += data;
            if (!writeScheduled) {
                writeScheduled = true;
                setTimeout(function () {
                    if (pendingWrite && terminal) {
                        terminal.write(pendingWrite);
                        pendingWrite = '';
                    }
                    writeScheduled = false;
                }, 8);
            }
        });

        ws.addEventListener('close', function () {
            // Flush any pending writes before writing the close message
            if (pendingWrite && terminal) {
                terminal.write(pendingWrite);
                pendingWrite = '';
                writeScheduled = false;
            }
            terminal.writeln('\r\n\x1b[31mSession closed.\x1b[0m');
        });

        ws.addEventListener('error', function () {
            // Flush any pending writes before writing the error message
            if (pendingWrite && terminal) {
                terminal.write(pendingWrite);
                pendingWrite = '';
                writeScheduled = false;
            }
            terminal.writeln('\r\n\x1b[31mWebSocket error — console unavailable.\x1b[0m');
        });

        // Forward keyboard input to SSH
        terminal.onData(function (data) {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(data);
            }
        });

        // Notify server on terminal resize
        terminal.onResize(function (size) {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ type: 'resize', cols: size.cols, rows: size.rows }));
            }
        });
    }

    // ── Toggle / visibility ───────────────────────────────────────────────────

    function toggleConsole() {
        if (panel.classList.contains('open')) {
            hideConsole();
        } else {
            showConsole();
        }
    }

    function showConsole() {
        panel.classList.add('open');
        fitAndResize();
        terminal.focus();
    }

    function hideConsole() {
        panel.classList.remove('open');
    }

    // ── Fit helper ────────────────────────────────────────────────────────────

    function fitAndResize() {
        // Small delay to let the panel finish its CSS transition
        setTimeout(function () {
            if (fitAddon) {
                fitAddon.fit();
            }
            sendResize();
        }, 20);
    }

    function sendResize() {
        if (terminal && ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({
                type: 'resize',
                cols: terminal.cols,
                rows: terminal.rows
            }));
        }
    }

}());
