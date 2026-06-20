/**
 * AuthModal — shared authorization / tool-approval modal.
 *
 * Renders a dynamic form from an InteractionFormSchema and posts the response
 * to POST /api/chat/respond/{sessionUuid}.
 *
 * Usage:
 *   AuthModal.init(document.getElementById('auth-modal'));
 *
 *   AuthModal.show({
 *     title       : string,               // modal header text (optional)
 *     reasoning   : string | null,        // markdown justification (optional)
 *     formSchema  : object,               // { title, fields, actions }
 *     sessionUuid : string,
 *     eventId     : string,
 *     onDone      : function(action, responseData, err)  // called after submit
 *   });
 *
 * Requires: VorkModal (vork-modal.js) and marked to be loaded first.
 */

/* global VorkModal, marked */

'use strict';

(function (global) {

    var _modal    = null;
    var _bsModal  = null;
    var _labelEl  = null;
    var _bodyEl   = null;
    var _footerEl = null;

    // ── Init ──────────────────────────────────────────────────────────────────

    function init(el) {
        _modal    = el;
        _bsModal  = new VorkModal(el);
        _labelEl  = el.querySelector('#auth-modal-label');
        _bodyEl   = el.querySelector('#auth-modal-body');
        _footerEl = el.querySelector('#auth-modal-footer');
    }

    // ── Show ──────────────────────────────────────────────────────────────────

    function show(opts) {
        if (!_modal) { console.warn('[AuthModal] init() was not called'); return; }

        var sessionUuid = opts.sessionUuid;
        var eventId     = opts.eventId;
        var formSchema  = opts.formSchema  || {};
        var title       = opts.title || formSchema.title || 'Authorise Action';
        var reasoning   = opts.reasoning || '';
        var onDone      = opts.onDone || null;

        _labelEl.textContent = title;
        _bodyEl.innerHTML    = '';

        // Reasoning / justification
        if (reasoning && reasoning.trim()) {
            var reasonEl = document.createElement('div');
            reasonEl.className = 'modal-reasoning markdown-body mb-3';
            reasonEl.innerHTML = marked.parse(reasoning);
            _bodyEl.appendChild(reasonEl);
        }

        // Form fields
        var rawFields = Array.isArray(formSchema.fields) ? formSchema.fields : [];
        var fields = rawFields.filter(function (f) {
            return f && f.name && (f.type || '').toLowerCase() !== 'hidden';
        });

        if (fields.length > 0) {
            var container = document.createElement('div');
            container.className = 'vstack gap-3';
            fields.forEach(function (f) {
                container.appendChild(_buildField(f, sessionUuid));
            });
            _bodyEl.appendChild(container);
        }

        // Action buttons
        _footerEl.innerHTML = '';
        var actions = (Array.isArray(formSchema.actions) && formSchema.actions.length > 0)
            ? formSchema.actions
            : [{ name: 'ONCE', label: 'Approve', style: 'success' }];

        actions.forEach(function (action) {
            var btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'btn btn-' + ((action.style || '').toLowerCase() || 'primary');
            btn.textContent = action.label || action.name;
            btn.addEventListener('click', function () {
                _submit(sessionUuid, eventId, action.name, _collectFields(fields), onDone);
            });
            _footerEl.appendChild(btn);
        });

        _bsModal.show();
    }

    // ── Field builder ─────────────────────────────────────────────────────────

    function _buildField(field, sessionUuid) {
        var wrapper = document.createElement('div');
        var inputId = 'auth-field-' + (sessionUuid || 'x') + '-' + field.name;
        var fieldValue = (field.value != null)
            ? String(field.value)
            : ((field.defaultValue != null) ? String(field.defaultValue) : '');

        var label = document.createElement('label');
        label.className = 'form-label mb-1';
        label.htmlFor = inputId;
        label.textContent = field.label || field.name;
        wrapper.appendChild(label);

        var type = (field.type || 'text').toLowerCase();

        if (type === 'markdown') {
            var mdDiv = document.createElement('div');
            mdDiv.className = 'markdown-body';
            mdDiv.innerHTML = marked.parse(fieldValue || field.placeholder || '');
            wrapper.appendChild(mdDiv);
            return wrapper;
        }

        if (type === 'select' && Array.isArray(field.options)) {
            var sel = document.createElement('select');
            sel.className = 'form-select form-select-sm';
            sel.id = inputId;
            sel.dataset.fieldName = field.name;
            if (field.required) sel.required = true;
            (field.options || []).forEach(function (opt) {
                var option = document.createElement('option');
                if (opt && typeof opt === 'object') {
                    option.value = (opt.value != null) ? String(opt.value) : '';
                    option.textContent = (opt.label != null) ? String(opt.label) : option.value;
                } else {
                    option.value = String(opt == null ? '' : opt);
                    option.textContent = option.value;
                }
                if (fieldValue && option.value === fieldValue) {
                    option.selected = true;
                }
                sel.appendChild(option);
            });
            wrapper.appendChild(sel);
        } else if (type === 'textarea') {
            var ta = document.createElement('textarea');
            ta.className = 'form-control form-control-sm';
            ta.id = inputId;
            ta.dataset.fieldName = field.name;
            ta.rows = 4;
            if (field.placeholder) ta.placeholder = field.placeholder;
            if (fieldValue) ta.value = fieldValue;
            if (field.required) ta.required = true;
            wrapper.appendChild(ta);
        } else if (type === 'checkbox') {
            var chk = document.createElement('input');
            chk.className = 'form-check-input';
            chk.type = 'checkbox';
            chk.id = inputId;
            chk.dataset.fieldName = field.name;
            chk.checked = fieldValue.toLowerCase() === 'true';
            if (field.required) chk.required = true;

            wrapper.className = 'form-check';
            wrapper.appendChild(chk);

            var checkLabel = document.createElement('label');
            checkLabel.className = 'form-check-label ms-2';
            checkLabel.htmlFor = inputId;
            checkLabel.textContent = field.label || field.name;
            wrapper.appendChild(checkLabel);
        } else {
            var inp = document.createElement('input');
            inp.className = 'form-control form-control-sm';
            inp.type = type === 'password' ? 'password' : 'text';
            inp.id = inputId;
            inp.dataset.fieldName = field.name;
            if (field.placeholder) inp.placeholder = field.placeholder;
            if (fieldValue) inp.value = fieldValue;
            if (type === 'readonly') inp.readOnly = true;
            if (field.required) inp.required = true;
            wrapper.appendChild(inp);
        }

        return wrapper;
    }

    // ── Field collection ──────────────────────────────────────────────────────

    function _collectFields(fields) {
        var values = {};
        fields.forEach(function (f) {
            var el = _bodyEl.querySelector('[data-field-name="' + f.name + '"]');
            if (!el) return;
            if (el.type === 'checkbox') {
                values[f.name] = el.checked ? 'true' : 'false';
            } else {
                values[f.name] = el.value || '';
            }
        });
        return values;
    }

    // ── Submission ────────────────────────────────────────────────────────────

    function _submit(sessionUuid, eventId, action, fieldValues, onDone) {
        _bsModal.hide();
        fetch('/api/chat/respond/' + encodeURIComponent(sessionUuid), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                eventId : eventId,
                intent  : 'AUTHORIZE_TOOL',
                action  : action,
                fields  : fieldValues
            })
        })
        .then(function (r) { return r.ok ? r.json() : Promise.reject('HTTP ' + r.status); })
        .then(function (data) {
            if (typeof onDone === 'function') onDone(action, data, null);
        })
        .catch(function (err) {
            if (typeof onDone === 'function') onDone(action, null, err);
        });
    }

    // ── Export ────────────────────────────────────────────────────────────────

    global.AuthModal = { init: init, show: show };

}(window));
