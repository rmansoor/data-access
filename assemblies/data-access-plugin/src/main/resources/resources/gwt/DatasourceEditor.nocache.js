/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2026 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/

/*
 * Data access entry points for the user console and the plugins that call them (Analyzer,
 * Interactive Reporting, dashboards). This plain script replaces the GWT module that used to be
 * compiled to this same file name, which PUC's Mantle.jsp loads; every function it put on the
 * window is still here, with the same arguments and callbacks:
 *
 *   pho.showDatasourceSelectionDialog(context, callback)  choose a model: callback.onFinish(domainId, modelId, modelName),
 *                                                         onCancel(), onError(message)
 *   pho.deleteModel(domainId, modelName, callback)       callback.onFinish(true), onError(message)
 *   pho.openDatasourceEditor(callback)                   create a dataset   } open the Helix console, where data
 *   pho.openEditDatasourceEditor(domainId, modelId, cb)  edit a data source } sources and datasets are now created
 *   pho.showDatasourceManageDialog(callback)             manage            } and managed; callback.onCancel() is
 *   pho.showDatabaseDialog(callback)                     connections       } called, since nothing changed here
 *   pho.showMetadataImportDialog(callback), pho.showAnalysisImportDialog(callback)
 *   pho.registerUIDatasourceService(service)             kept for compatibility; the old wizard's extension point
 *   addDataAccessGlassPaneListener(listener) / removeDataAccessGlassPaneListenerById(id)
 *                                                        listener.glassPaneShown() / glassPaneHidden() around the dialog
 *   gwtConfirm(message, callback, options)               callback.onOk(value) when accepted
 *
 * No framework, ES5, so it runs in every page PUC and the plugins open.
 */
(function (window) {
  'use strict';

  var document = window.document;

  /** "/pentaho/": PUC defines CONTEXT_PATH; otherwise worked out from this script's own address. */
  function contextPath() {
    if (typeof window.CONTEXT_PATH === 'string' && window.CONTEXT_PATH) {
      return window.CONTEXT_PATH.replace(/\/?$/, '/');
    }
    var scripts = document.getElementsByTagName('script');
    for (var i = 0; i < scripts.length; i++) {
      var m = /^(.*\/)content\/data-access\/resources\/gwt\/DatasourceEditor\.nocache\.js/.exec(scripts[i].src || '');
      if (m) {
        return m[1].replace(/^[a-z]+:\/\/[^/]+/i, '');
      }
    }
    return '/pentaho/';
  }

  var ROOT = contextPath();
  var API = ROOT + 'plugin/data-access/api/datasource/dsw/';

  function message(key, fallback) {
    try {
      if (window.pho_messages && window.pho_messages.getMessage) {
        var text = window.pho_messages.getMessage(key, fallback);
        if (text && text !== key) {
          return text;
        }
      }
    } catch (e) {
      // Fall back to English.
    }
    return fallback;
  }

  function call(callback, name) {
    if (callback && typeof callback[name] === 'function') {
      callback[name].apply(callback, Array.prototype.slice.call(arguments, 2));
    }
  }

  /** Opens a page of the Helix console in a new tab ("datasets/new", "datasources"). */
  function openConsole(page, callback) {
    window.open(ROOT + 'console/' + page, '_blank', 'noopener');
    call(callback, 'onCancel');
  }

  // ------------------------------------------------------------------------------------------------
  // Glass pane listeners: told when the dialog covers the page.

  var glassPaneListeners = {};
  var nextListener = 1;

  function glassPane(shown) {
    for (var id in glassPaneListeners) {
      if (Object.prototype.hasOwnProperty.call(glassPaneListeners, id)) {
        try {
          call(glassPaneListeners[id], shown ? 'glassPaneShown' : 'glassPaneHidden');
        } catch (e) {
          // A listener's failure must not keep the dialog from opening or closing.
        }
      }
    }
  }

  // ------------------------------------------------------------------------------------------------
  // The model selection dialog.

  function send(method, url, headers, done) {
    var xhr = new XMLHttpRequest();
    xhr.open(method, url, true);
    xhr.setRequestHeader('Accept', 'application/json');
    for (var name in headers) {
      if (Object.prototype.hasOwnProperty.call(headers, name)) {
        xhr.setRequestHeader(name, headers[name]);
      }
    }
    xhr.onreadystatechange = function () {
      if (xhr.readyState === 4) {
        done(xhr.status, xhr.responseText, xhr);
      }
    };
    xhr.send();
  }

  /**
   * GET as is; anything else with the CSRF token when the server issues one (GET /api/csrf/token
   * answers 204 with X-CSRF-HEADER and X-CSRF-TOKEN, or with neither when protection is off).
   */
  function request(method, url, done) {
    if (method === 'GET') {
      send(method, url, {}, done);
      return;
    }
    var absolute = window.location.protocol + '//' + window.location.host + url;
    send('GET', ROOT + 'api/csrf/token?url=' + encodeURIComponent(absolute), {}, function (code, text, xhr) {
      var headers = {};
      var header = xhr.getResponseHeader('X-CSRF-HEADER');
      var token = xhr.getResponseHeader('X-CSRF-TOKEN');
      if (header && token) {
        headers[header] = token;
      }
      send(method, url, headers, done);
    });
  }

  function element(tag, className, text) {
    var e = document.createElement(tag);
    if (className) {
      e.className = className;
    }
    if (text !== undefined) {
      e.appendChild(document.createTextNode(text));
    }
    return e;
  }

  function showSelectionDialog(context, callback) {
    var finished = false;
    var models = [];
    var selected = -1;

    var overlay = element('div', 'pho-ds-overlay');
    var dialog = element('div', 'pho-ds-dialog');
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    var titleId = 'pho-ds-title-' + new Date().getTime();
    dialog.setAttribute('aria-labelledby', titleId);
    var title = element('h2', 'pho-ds-title', message('selectDatasource', 'Select a data source'));
    title.id = titleId;
    var filter = element('input', 'pho-ds-filter');
    filter.type = 'search';
    filter.placeholder = message('filter', 'Filter');
    filter.setAttribute('aria-label', message('filter', 'Filter'));
    var list = element('ul', 'pho-ds-list');
    list.setAttribute('role', 'listbox');
    list.setAttribute('aria-labelledby', titleId);
    var status = element('p', 'pho-ds-status', message('loading', 'Loading…'));
    status.setAttribute('role', 'status');
    var footer = element('div', 'pho-ds-footer');
    var create = element('a', 'pho-ds-link', message('newDatasourceEllipsis', 'New data source…'));
    create.href = ROOT + 'console/datasets/new';
    create.target = '_blank';
    create.rel = 'noopener';
    var cancel = element('button', 'pho-ds-button', message('cancelButton', 'Cancel'));
    cancel.type = 'button';
    var ok = element('button', 'pho-ds-button pho-ds-primary', message('okButton', 'OK'));
    ok.type = 'button';
    ok.disabled = true;

    footer.appendChild(create);
    footer.appendChild(cancel);
    footer.appendChild(ok);
    dialog.appendChild(title);
    dialog.appendChild(filter);
    dialog.appendChild(status);
    dialog.appendChild(list);
    dialog.appendChild(footer);
    overlay.appendChild(dialog);

    function close() {
      if (overlay.parentNode) {
        overlay.parentNode.removeChild(overlay);
      }
      document.removeEventListener('keydown', onKey, true);
      glassPane(false);
    }

    function finish() {
      if (finished || selected < 0) {
        return;
      }
      finished = true;
      var m = models[selected];
      close();
      call(callback, 'onFinish', m.domainId, m.modelId, m.modelName);
    }

    function dismiss() {
      if (finished) {
        return;
      }
      finished = true;
      close();
      call(callback, 'onCancel');
    }

    function render() {
      var q = filter.value.toLowerCase();
      list.innerHTML = '';
      var shown = 0;
      for (var i = 0; i < models.length; i++) {
        var m = models[i];
        if (q && String(m.modelName).toLowerCase().indexOf(q) < 0) {
          continue;
        }
        shown++;
        var item = element('li', 'pho-ds-item' + (i === selected ? ' pho-ds-selected' : ''), m.modelName);
        item.setAttribute('role', 'option');
        item.setAttribute('aria-selected', i === selected ? 'true' : 'false');
        item.setAttribute('data-index', String(i));
        item.tabIndex = 0;
        list.appendChild(item);
      }
      status.style.display = shown ? 'none' : '';
      if (!shown) {
        status.textContent = models.length ? message('noMatch', 'No data sources match.') : message('noDatasources', 'No data sources yet.');
      }
      ok.disabled = selected < 0;
    }

    function pick(target) {
      var index = target && target.getAttribute && target.getAttribute('data-index');
      if (index !== null && index !== undefined) {
        selected = parseInt(index, 10);
        render();
      }
    }

    function onKey(e) {
      if (e.key === 'Escape' || e.keyCode === 27) {
        e.stopPropagation();
        dismiss();
      } else if ((e.key === 'Enter' || e.keyCode === 13) && e.target && e.target.getAttribute && e.target.getAttribute('data-index') !== null) {
        pick(e.target);
        finish();
      }
    }

    list.onclick = function (e) {
      pick(e.target);
    };
    list.ondblclick = function (e) {
      pick(e.target);
      finish();
    };
    filter.oninput = render;
    cancel.onclick = dismiss;
    ok.onclick = finish;
    overlay.onclick = function (e) {
      if (e.target === overlay) {
        dismiss();
      }
    };
    document.addEventListener('keydown', onKey, true);

    glassPane(true);
    document.body.appendChild(overlay);
    filter.focus();

    request('GET', API + 'logicalModels?context=' + encodeURIComponent(context || ''), function (code, text) {
      if (finished) {
        return;
      }
      if (code !== 200) {
        finished = true;
        close();
        call(callback, 'onError', message('loadFailed', 'The data sources could not be loaded') + ' (' + code + ')');
        return;
      }
      try {
        models = JSON.parse(text) || [];
      } catch (e) {
        models = [];
      }
      render();
    });
  }

  // ------------------------------------------------------------------------------------------------

  var pho = window.pho = window.pho || {};

  pho.showDatasourceSelectionDialog = showSelectionDialog;

  pho.deleteModel = function (domainId, modelName, callback) {
    request('DELETE', API + 'logicalModels?domainId=' + encodeURIComponent(domainId) + '&modelName=' + encodeURIComponent(modelName),
      function (code) {
        if (code === 204) {
          call(callback, 'onFinish', true);
        } else {
          call(callback, 'onError', message('deleteFailed', 'The data source could not be deleted') + ' (' + code + ')');
        }
      });
  };

  pho.openDatasourceEditor = function (callback) {
    openConsole('datasets/new', callback);
  };

  pho.openEditDatasourceEditor = function (domainId, modelId, callback) {
    openConsole('datasources', callback);
  };

  pho.showDatasourceManageDialog = function (callback) {
    openConsole('datasources', callback);
  };

  pho.showDatabaseDialog = function (callback) {
    openConsole('datasources', callback);
  };

  pho.showMetadataImportDialog = function (callback) {
    openConsole('datasources', callback);
  };

  pho.showAnalysisImportDialog = function (callback) {
    openConsole('datasources', callback);
  };

  /** The old wizard's way for plugins to add data source types; datasets replace it. Kept so callers don't fail. */
  pho.registerUIDatasourceService = function () {};

  window.addDataAccessGlassPaneListener = function (listener) {
    var id = 'pho-ds-' + nextListener++;
    glassPaneListeners[id] = listener;
    return id;
  };

  window.removeDataAccessGlassPaneListenerById = function (id) {
    delete glassPaneListeners[id];
  };

  window.gwtConfirm = function (text, callback) {
    var accepted = window.confirm(text);
    if (accepted) {
      call(callback, 'onOk', true);
    }
    // The GWT module's fallback called this misspelled name; some callers implemented it.
    call(callback, 'okOk', accepted);
  };
})(window);
