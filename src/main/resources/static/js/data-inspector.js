// ── Data Inspector ───────────────────────────────────────────────────────────
'use strict';

(function () {

    // ── DOM refs ──────────────────────────────────────────────────────────────

    var typeSelect      = document.getElementById('type-select');
    var typeActions     = document.getElementById('type-actions');
    var typeCountBadge  = document.getElementById('type-count-badge');
    var createBtn       = document.getElementById('create-btn');
    var tableHead       = document.getElementById('table-head');
    var tableBody       = document.getElementById('table-body');
    var prevBtn         = document.getElementById('prev-btn');
    var nextBtn         = document.getElementById('next-btn');
    var paginationInfo  = document.getElementById('pagination-info');
    var modalEl         = document.getElementById('record-modal');
    var modalLabel      = document.getElementById('record-modal-label');
    var modalBody       = document.getElementById('modal-body');
    var modalSaveBtn    = document.getElementById('modal-save-btn');
    var modalError      = document.getElementById('modal-error');

    var bsModal = new VorkModal(modalEl);

    // ── State ─────────────────────────────────────────────────────────────────

    var currentFqn    = null;
    var currentSchema = null;
    var currentPage   = 0;
    var pageSize      = 20;
    var totalCount    = 0;

    // ── Boot: populate type selector ──────────────────────────────────────────

    fetch('/api/types/java-types')
        .then(function (r) { return r.ok ? r.json() : Promise.reject('HTTP ' + r.status); })
        .then(function (types) {
            // Sort alphabetically by simple name
            types.sort(function (a, b) { return a.simpleName.localeCompare(b.simpleName); });
            types.forEach(function (t) {
                var opt = document.createElement('option');
                opt.value = t.fqn;
                opt.textContent = t.simpleName;
                typeSelect.appendChild(opt);
            });
            // Auto-select from URL hash e.g. #sh.vork.generated.Customer
            var hash = window.location.hash.slice(1);
            if (hash && typeSelect.querySelector('option[value="' + CSS.escape(hash) + '"]')) {
                typeSelect.value = hash;
                onTypeChange();
            }
        })
        .catch(function (err) {
            showError('Failed to load type list: ' + err);
        });

    // ── Type selector ─────────────────────────────────────────────────────────

    typeSelect.addEventListener('change', onTypeChange);

    function onTypeChange() {
        var fqn = typeSelect.value;
        if (!fqn) {
            showState('initial');
            typeActions.classList.add('d-none');
            currentFqn = null;
            currentSchema = null;
            return;
        }
        window.location.hash = fqn;
        currentFqn = fqn;
        currentPage = 0;
        showState('loading');
        typeActions.classList.add('d-none');

        loadSchema(fqn).then(function (schema) {
            currentSchema = schema;
            return loadPage(fqn, 0);
        }).catch(function (err) {
            showError('Failed to load type: ' + err);
        });
    }

    // ── Schema loading ────────────────────────────────────────────────────────

    function loadSchema(fqn) {
        return fetch('/api/types/' + encodeURIComponent(fqn) + '/schema')
            .then(function (r) { return r.ok ? r.json() : r.json().then(function (e) { return Promise.reject(e.message || 'Schema error'); }); });
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    function loadPage(fqn, page) {
        showState('loading');
        var countPromise = fetch('/api/types/' + encodeURIComponent(fqn) + '/count')
            .then(function (r) { return r.ok ? r.json() : Promise.reject('count error'); })
            .then(function (d) { return d.count || 0; });

        var listPromise = fetch(
            '/api/types/' + encodeURIComponent(fqn) + '/list?page=' + page + '&pageSize=' + pageSize
        ).then(function (r) { return r.ok ? r.json() : Promise.reject('list error'); });

        return Promise.all([countPromise, listPromise]).then(function (results) {
            totalCount  = results[0];
            var items   = results[1];
            currentPage = page;

            typeCountBadge.textContent = totalCount + ' record' + (totalCount !== 1 ? 's' : '');
            typeActions.classList.remove('d-none');

            if (items.length === 0 && page === 0) {
                showState('empty');
                return;
            }

            renderTable(currentSchema, items);
            updatePagination();
            showState('table');
        });
    }

    // ── Table rendering ───────────────────────────────────────────────────────

    function renderTable(schema, items) {
        var columns = tableColumns(schema);

        // thead
        tableHead.innerHTML = '';
        var headerRow = document.createElement('tr');
        columns.forEach(function (col) {
            var th = document.createElement('th');
            th.textContent = col.label;
            headerRow.appendChild(th);
        });
        var thActions = document.createElement('th');
        thActions.className = 'col-actions';
        headerRow.appendChild(thActions);
        tableHead.appendChild(headerRow);

        // tbody
        tableBody.innerHTML = '';
        items.forEach(function (item) {
            var tr = document.createElement('tr');
            columns.forEach(function (col) {
                var td = document.createElement('td');
                var raw = item[col.name];
                var display;
                if (col.type === 'enum' && Array.isArray(col.options)) {
                    var match = col.options.find(function (o) { return o.value === raw; });
                    display = match ? match.label : displayValue(raw);
                } else {
                    display = displayValue(raw);
                }
                td.title = display;
                td.textContent = display;
                tr.appendChild(td);
            });

            // Actions
            var tdAct = document.createElement('td');
            tdAct.className = 'col-actions';
            tdAct.appendChild(buildEditBtn(item));
            tdAct.appendChild(document.createTextNode(' '));
            tdAct.appendChild(buildDeleteBtn(item));
            tr.appendChild(tdAct);

            tableBody.appendChild(tr);
        });
    }

    function tableColumns(schema) {
        if (!schema || !Array.isArray(schema.fields)) return [];
        return schema.fields.filter(function (f) { return f.tableColumn && f.name !== 'uuid'; });
    }

    function displayValue(val) {
        if (val === null || val === undefined) return '';
        if (typeof val === 'object') return JSON.stringify(val);
        return String(val);
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    function updatePagination() {
        var start = currentPage * pageSize + 1;
        var end   = Math.min(start + pageSize - 1, totalCount);
        paginationInfo.textContent = totalCount === 0 ? 'No records' :
            'Showing ' + start + '–' + end + ' of ' + totalCount;

        prevBtn.disabled = currentPage === 0;
        nextBtn.disabled = end >= totalCount;
    }

    prevBtn.addEventListener('click', function () {
        if (currentPage > 0) loadPage(currentFqn, currentPage - 1);
    });
    nextBtn.addEventListener('click', function () {
        if ((currentPage + 1) * pageSize < totalCount) loadPage(currentFqn, currentPage + 1);
    });

    // ── Row action buttons ────────────────────────────────────────────────────

    function buildEditBtn(item) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn btn-sm btn-outline-secondary';
        btn.title = 'Edit';
        btn.innerHTML = '<i class="fa-solid fa-pen"></i>';
        btn.addEventListener('click', function () { openModal(item); });
        return btn;
    }

    function buildDeleteBtn(item) {
        var uuid = item.uuid || '';

        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn btn-sm btn-outline-danger';
        btn.title = 'Delete';
        btn.innerHTML = '<i class="fa-solid fa-trash"></i>';

        btn.addEventListener('click', function () {
            if (btn.dataset.confirming) {
                // Second click = confirmed delete
                btn.disabled = true;
                fetch('/api/types/' + encodeURIComponent(currentFqn) + '/' + encodeURIComponent(uuid), {
                    method: 'DELETE'
                })
                    .then(function (r) { return r.json(); })
                    .then(function (res) {
                        if (res.status === 'ok') {
                            totalCount = Math.max(0, totalCount - 1);
                            loadPage(currentFqn, currentPage);
                        } else {
                            alert('Delete failed: ' + (res.message || 'unknown error'));
                        }
                    })
                    .catch(function (err) { alert('Delete error: ' + err); });
            } else {
                // First click = ask for confirmation by changing button appearance
                btn.dataset.confirming = '1';
                btn.innerHTML = '<i class="fa-solid fa-circle-exclamation"></i>';
                btn.title = 'Click again to confirm delete';
                btn.classList.remove('btn-outline-danger');
                btn.classList.add('btn-danger');
                setTimeout(function () {
                    delete btn.dataset.confirming;
                    btn.innerHTML = '<i class="fa-solid fa-trash"></i>';
                    btn.title = 'Delete';
                    btn.classList.remove('btn-danger');
                    btn.classList.add('btn-outline-danger');
                }, 3000);
            }
        });

        return btn;
    }

    // ── Create button ─────────────────────────────────────────────────────────

    createBtn.addEventListener('click', function () { openModal(null); });

    // ── Modal ─────────────────────────────────────────────────────────────────

    function openModal(existingItem) {
        modalError.classList.add('d-none');
        modalError.textContent = '';

        var isEdit = existingItem !== null && existingItem !== undefined;
        var typeName = currentSchema ? currentSchema.title : 'Record';

        modalLabel.textContent = isEdit ? ('Edit ' + typeName) : ('Create ' + typeName);
        modalSaveBtn.disabled = false;

        modalBody.innerHTML = '';
        if (currentSchema) {
            var form = buildForm(currentSchema.fields, existingItem, '', isEdit);
            modalBody.appendChild(form);
        }

        bsModal.show();
    }

    // Save handler
    modalSaveBtn.addEventListener('click', function () {
        modalError.classList.add('d-none');
        modalSaveBtn.disabled = true;

        var formData = collectFormData(modalBody);

        // For new records the uuid hidden input is absent — generate one now.
        // For edits the hidden input is already present from the read-only row.
        if (!formData.get('uuid')) {
            formData.set('uuid', crypto.randomUUID());
        }

        fetch('/api/types/' + encodeURIComponent(currentFqn), {
            method: 'POST',
            body: formData
        })
            .then(function (r) { return r.json(); })
            .then(function (res) {
                if (res.status === 'ok') {
                    bsModal.hide();
                    loadPage(currentFqn, currentPage);
                } else {
                    modalError.textContent = res.message || 'Save failed';
                    modalError.classList.remove('d-none');
                    modalSaveBtn.disabled = false;
                }
            })
            .catch(function (err) {
                modalError.textContent = 'Request failed: ' + err;
                modalError.classList.remove('d-none');
                modalSaveBtn.disabled = false;
            });
    });

    // ── Form builder ──────────────────────────────────────────────────────────

    /**
     * Recursively builds a form `<div>` for a list of field descriptors.
     *
     * @param {Array}   fields   Array of field descriptor objects from the schema.
     * @param {Object}  values   Existing entity values (null for create).
     * @param {string}  prefix   Dot-notation prefix for nested records (e.g. "address.").
     * @param {boolean} isEdit   True when editing an existing record.
     * @returns {HTMLElement}
     */
    function buildForm(fields, values, prefix, isEdit) {
        var container = document.createElement('div');
        container.className = 'vstack gap-3';

        fields.forEach(function (field) {
            var fieldEl = buildField(field, values, prefix, isEdit);
            if (fieldEl) container.appendChild(fieldEl);
        });

        return container;
    }

    function buildField(field, values, prefix, isEdit) {
        var val = values ? values[field.name] : null;

        // uuid is auto-generated on create; shown read-only on edit.
        if (field.name === 'uuid' && prefix === '') {
            if (!isEdit) return null;  // omit entirely — value generated at save time
            return buildReadOnlyUuid(val);
        }

        if (field.type === 'record') {
            return buildNestedRecordSection(field, val, prefix, isEdit);
        }

        if (field.type === 'array') {
            return buildArrayRepeater(field, val, prefix);
        }

        // Scalar field
        return buildScalarField(field, val, prefix + field.name);
    }

    // ── Read-only UUID row (edit mode) ─────────────────────────────────────────

    function buildReadOnlyUuid(value) {
        var wrapper = document.createElement('div');
        wrapper.className = 'mb-0';

        var label = document.createElement('label');
        label.className = 'form-label mb-1 text-muted';
        label.textContent = 'ID';
        wrapper.appendChild(label);

        var display = document.createElement('div');
        display.className = 'form-control form-control-sm uuid-readonly';
        display.textContent = value || '';
        display.title = value || '';
        wrapper.appendChild(display);

        // Hidden input carries the uuid value through FormData
        var hidden = document.createElement('input');
        hidden.type = 'hidden';
        hidden.name = 'uuid';
        hidden.value = value || '';
        wrapper.appendChild(hidden);

        return wrapper;
    }

    // ── Scalar field ──────────────────────────────────────────────────────────

    function buildScalarField(field, value, inputName) {
        var wrapper = document.createElement('div');
        wrapper.className = 'mb-0';

        var label = document.createElement('label');
        label.className = 'form-label mb-1';
        label.textContent = field.label || field.name;
        if (field.required) {
            var req = document.createElement('span');
            req.className = 'text-danger ms-1';
            req.textContent = '*';
            label.appendChild(req);
        }
        wrapper.appendChild(label);

        var inputType = field.inputType || 'text';
        if (inputType === 'auto') inputType = inferInputTypeFromSchema(field);

        if (inputType === 'checkbox') {
            var checkWrapper = document.createElement('div');
            checkWrapper.className = 'form-check';
            var check = document.createElement('input');
            check.type = 'checkbox';
            check.className = 'form-check-input';
            check.name = inputName;
            check.value = 'true';
            if (value === true || value === 'true') check.checked = true;
            checkWrapper.appendChild(check);
            wrapper.appendChild(checkWrapper);
        } else if (inputType === 'textarea') {
            var ta = document.createElement('textarea');
            ta.className = 'form-control form-control-sm';
            ta.name = inputName;
            ta.rows = 3;
            if (field.placeholder) ta.placeholder = field.placeholder;
            if (field.required) ta.required = true;
            if (value !== null && value !== undefined) ta.value = String(value);
            wrapper.appendChild(ta);
        } else if (inputType === 'select' && Array.isArray(field.options)) {
            var sel = document.createElement('select');
            sel.className = 'form-select form-select-sm';
            sel.name = inputName;
            if (field.required) sel.required = true;
            var emptyOpt = document.createElement('option');
            emptyOpt.value = '';
            emptyOpt.textContent = '\u2014 select \u2014';
            sel.appendChild(emptyOpt);
            field.options.forEach(function (opt) {
                var option = document.createElement('option');
                option.value = opt.value;
                option.textContent = opt.label;
                if (value === opt.value) option.selected = true;
                sel.appendChild(option);
            });
            wrapper.appendChild(sel);
        } else {
            var input = document.createElement('input');
            input.type = inputType;
            input.className = 'form-control form-control-sm';
            input.name = inputName;
            if (field.placeholder) input.placeholder = field.placeholder;
            if (field.required) input.required = true;
            if (value !== null && value !== undefined) input.value = String(value);
            wrapper.appendChild(input);
        }

        return wrapper;
    }

    function inferInputTypeFromSchema(field) {
        if (field.type === 'boolean') return 'checkbox';
        if (field.type === 'integer' || field.type === 'number') return 'number';
        return 'text';
    }

    // ── Nested record section ─────────────────────────────────────────────────

    function buildNestedRecordSection(field, values, prefix, isEdit) {
        var section = document.createElement('div');
        section.className = 'field-section';

        var title = document.createElement('div');
        title.className = 'field-section-title';
        title.textContent = field.label || field.name;
        section.appendChild(title);

        var nestedPrefix = prefix + field.name + '.';
        var subFields = field.fields || [];
        var subForm = buildForm(subFields, values, nestedPrefix, isEdit);
        section.appendChild(subForm);

        return section;
    }

    // ── Array repeater ────────────────────────────────────────────────────────

    function buildArrayRepeater(field, values, prefix) {
        var wrapper = document.createElement('div');

        var label = document.createElement('label');
        label.className = 'form-label mb-1';
        label.textContent = field.label || field.name;
        wrapper.appendChild(label);

        var container = document.createElement('div');
        container.className = 'repeater-container';
        container.dataset.fieldName = prefix + field.name;
        container.dataset.itemType  = field.itemType || 'string';
        wrapper.appendChild(container);

        // Add existing values
        var existingItems = Array.isArray(values) ? values : [];
        if (existingItems.length === 0) {
            // Start with one empty row for convenience
            addRepeaterRow(container, field, null, 0);
        } else {
            existingItems.forEach(function (item, idx) {
                addRepeaterRow(container, field, item, idx);
            });
        }

        var addBtn = document.createElement('button');
        addBtn.type = 'button';
        addBtn.className = 'btn btn-sm btn-outline-secondary repeater-add-btn';
        addBtn.innerHTML = '<i class="fa-solid fa-plus me-1"></i>Add row';
        addBtn.addEventListener('click', function () {
            var rowCount = container.querySelectorAll('.repeater-row').length;
            addRepeaterRow(container, field, null, rowCount);
        });
        wrapper.appendChild(addBtn);

        return wrapper;
    }

    function addRepeaterRow(container, field, values, index) {
        var row = document.createElement('div');
        row.className = 'repeater-row';

        var inputsDiv = document.createElement('div');
        inputsDiv.className = 'repeater-inputs';

        var baseName = container.dataset.fieldName + '[' + index + ']';

        if (field.itemType === 'record' && field.itemSchema && Array.isArray(field.itemSchema.fields)) {
            // Record list: one input per sub-field
            field.itemSchema.fields.forEach(function (subField) {
                var subVal = values ? values[subField.name] : null;
                var subFieldDef = Object.assign({}, subField);
                var subContainer = buildScalarField(subFieldDef, subVal, baseName + '.' + subField.name);
                subContainer.className = 'form-group mb-0';
                inputsDiv.appendChild(subContainer);
            });
        } else {
            // Scalar list: one input per row
            var inputType = inferInputTypeFromSchema(field);
            var input = document.createElement('input');
            input.type = inputType === 'checkbox' ? 'text' : inputType;
            input.className = 'form-control form-control-sm';
            input.name = baseName;
            if (values !== null && values !== undefined) input.value = String(values);
            inputsDiv.appendChild(input);
        }

        var removeBtn = document.createElement('button');
        removeBtn.type = 'button';
        removeBtn.className = 'btn btn-sm btn-outline-danger repeater-remove-btn';
        removeBtn.innerHTML = '<i class="fa-solid fa-minus"></i>';
        removeBtn.addEventListener('click', function () {
            row.remove();
            reindexRepeater(container, field);
        });

        row.appendChild(inputsDiv);
        row.appendChild(removeBtn);
        container.appendChild(row);
    }

    /** Renumbers all `[N]` indices after a row is removed. */
    function reindexRepeater(container, field) {
        var rows = container.querySelectorAll('.repeater-row');
        var baseName = container.dataset.fieldName;

        rows.forEach(function (row, idx) {
            var inputs = row.querySelectorAll('[name]');
            inputs.forEach(function (input) {
                // Replace the [oldIndex] with [idx], preserving any trailing .subfield
                input.name = input.name.replace(/\[\d+\]/, '[' + idx + ']');
            });
        });
    }

    // ── Form data collection ──────────────────────────────────────────────────

    /**
     * Walks all named inputs inside `container` and builds a `FormData` object.
     * Checkbox handling: if unchecked, sets value to "false"; if checked, "true".
     */
    function collectFormData(container) {
        var fd = new FormData();
        var inputs = container.querySelectorAll('[name]');
        inputs.forEach(function (el) {
            if (el.type === 'checkbox') {
                fd.append(el.name, el.checked ? 'true' : 'false');
            } else {
                fd.append(el.name, el.value);
            }
        });
        return fd;
    }

    // ── State display ─────────────────────────────────────────────────────────

    function showState(name) {
        ['initial', 'loading', 'empty', 'error', 'table'].forEach(function (s) {
            var el = document.getElementById('state-' + s);
            if (el) el.classList.toggle('d-none', s !== name);
        });
    }

    function showError(msg) {
        document.getElementById('error-message').textContent = msg;
        showState('error');
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

}());
