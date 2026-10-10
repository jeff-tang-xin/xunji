/*!
 * 玄机万象 Chat Widget —— 零依赖嵌入式对话组件（主文档 4.9 第三条消费路径）。
 *
 * 用法（AppKey 鉴权，App 须绑定已发布 AGENT 资产）：
 *   <script src="https://your-host/widget/chat.js"
 *           data-app-id="app_xxx" data-app-key="app-xxxxxxxx"
 *           data-base-url="https://your-host" data-title="智能助手" defer></script>
 *
 * 或编程式：window.XuanjiChat.mount({ appId, appKey, baseUrl, title });
 *
 * 协议：POST {baseUrl}/api/v1/apps/{appId}/chat-messages
 *   Header: Authorization: Bearer {appKey}
 *   Body:   { query, sessionId }
 *   SSE:    agent.start / agent.delta{delta} / agent.result{text} /
 *           tool.call / tool.result / stage.finish{status} / error{message}
 */
(function () {
  'use strict';

  var DEFAULT_BASE = ''; // 缺省同源（widget 与 API 同域部署）
  window.XuanjiChat = (function () {

  function XuanjiChat(opts) {
    this.opts = Object.assign({
      appId: '', appKey: '', baseUrl: DEFAULT_BASE,
      title: '智能助手', placeholder: '请输入您的问题…',
      welcome: '您好，我是智能助手，请问有什么可以帮您？',
    }, opts || {});
    this.sessionId = 'web-' + Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
    this.busy = false;
    this._build();
  }

  XuanjiChat.prototype._build = function () {
    var o = this.opts;
    var css = ''
      + '.xjw-root{position:fixed;right:24px;bottom:24px;z-index:2147483000;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;font-size:14px;color:#1f2329}'
      + '.xjw-fab{width:52px;height:52px;border-radius:50%;background:#1677ff;color:#fff;border:none;cursor:pointer;box-shadow:0 6px 16px rgba(22,119,255,.35);font-size:22px;display:flex;align-items:center;justify-content:center;transition:transform .15s}'
      + '.xjw-fab:hover{transform:scale(1.06)}'
      + '.xjw-panel{display:none;width:380px;height:560px;max-height:calc(100vh - 100px);background:#fff;border-radius:12px;box-shadow:0 12px 40px rgba(0,0,0,.18);overflow:hidden;flex-direction:column;border:1px solid #f0f0f0}'
      + '.xjw-panel.xjw-open{display:flex}'
      + '.xjw-head{height:52px;background:#1677ff;color:#fff;display:flex;align-items:center;justify-content:space-between;padding:0 14px;flex:none}'
      + '.xjw-head b{font-size:15px;font-weight:600}'
      + '.xjw-close{background:none;border:none;color:#fff;font-size:20px;cursor:pointer;line-height:1;padding:2px 6px;opacity:.85}'
      + '.xjw-body{flex:1;overflow-y:auto;padding:14px;background:#f7f8fa;display:flex;flex-direction:column;gap:10px}'
      + '.xjw-msg{max-width:86%;padding:9px 12px;border-radius:10px;line-height:1.55;word-break:break-word;white-space:pre-wrap}'
      + '.xjw-msg-agent{background:#fff;border:1px solid #eee;border-top-left-radius:3px;align-self:flex-start}'
      + '.xjw-msg-user{background:#1677ff;color:#fff;border-top-right-radius:3px;align-self:flex-end}'
      + '.xjw-msg-sys{align-self:center;font-size:12px;color:#999;background:none;padding:2px}'
      + '.xjw-tool{align-self:flex-start;max-width:86%;font-size:12px;color:#389e0d;background:#f6ffed;border:1px solid #b7eb8f;border-radius:8px;padding:6px 10px}'
      + '.xjw-caret{display:inline-block;width:7px;height:15px;background:#1677ff;vertical-align:-2px;animation:xjw-blink 1s steps(1) infinite;margin-left:1px}'
      + '@keyframes xjw-blink{50%{opacity:0}}'
      + '.xjw-foot{flex:none;border-top:1px solid #f0f0f0;padding:10px;display:flex;gap:8px;background:#fff}'
      + '.xjw-input{flex:1;border:1px solid #e5e6eb;border-radius:8px;padding:8px 10px;font-size:14px;resize:none;height:40px;line-height:22px;font-family:inherit;outline:none}'
      + '.xjw-input:focus{border-color:#1677ff;box-shadow:0 0 0 2px rgba(22,119,255,.12)}'
      + '.xjw-send{border:none;background:#1677ff;color:#fff;border-radius:8px;padding:0 16px;cursor:pointer;font-size:14px}'
      + '.xjw-send:disabled{background:#a0c4ff;cursor:not-allowed}'
      + '@media (max-width:480px){.xjw-root{right:0;bottom:0}.xjw-panel{width:100vw;height:100vh;max-height:100vh;border-radius:0}.xjw-fab{right:16px;bottom:16px;position:fixed}}';
    var style = document.createElement('style');
    style.textContent = css;
    document.head.appendChild(style);

    var root = document.createElement('div');
    root.className = 'xjw-root';
    root.innerHTML = ''
      + '<button class="xjw-fab" title="' + escAttr(o.title) + '" aria-label="open chat">💬</button>'
      + '<div class="xjw-panel" role="dialog" aria-label="' + escAttr(o.title) + '">'
      +   '<div class="xjw-head"><b>' + escHtml(o.title) + '</b>'
      +     '<button class="xjw-close" aria-label="close">×</button></div>'
      +   '<div class="xjw-body"></div>'
      +   '<div class="xjw-foot">'
      +     '<textarea class="xjw-input" rows="1" placeholder="' + escAttr(o.placeholder) + '"></textarea>'
      +     '<button class="xjw-send">发送</button>'
      +   '</div>'
      + '</div>';
    document.body.appendChild(root);

    this.elRoot = root;
    this.elFab = root.querySelector('.xjw-fab');
    this.elPanel = root.querySelector('.xjw-panel');
    this.elBody = root.querySelector('.xjw-body');
    this.elInput = root.querySelector('.xjw-input');
    this.elSend = root.querySelector('.xjw-send');

    var self = this;
    this.elFab.addEventListener('click', function () { self.toggle(); });
    root.querySelector('.xjw-close').addEventListener('click', function () { self.toggle(false); });
    this.elSend.addEventListener('click', function () { self.submit(); });
    this.elInput.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); self.submit(); }
    });

    this._sys(o.welcome);
  };

  XuanjiChat.prototype.toggle = function (open) {
    var willOpen = typeof open === 'boolean' ? open : !this.elPanel.classList.contains('xjw-open');
    this.elPanel.classList.toggle('xjw-open', willOpen);
    this.elFab.style.display = willOpen ? 'none' : 'flex';
    if (willOpen) {
      var self = this;
      setTimeout(function () { self.elInput.focus(); }, 50);
    }
  };

  XuanjiChat.prototype._addMsg = function (cls, text) {
    var div = document.createElement('div');
    div.className = 'xjw-msg ' + cls;
    div.textContent = text;
    this.elBody.appendChild(div);
    this._scroll();
    return div;
  };

  XuanjiChat.prototype._sys = function (text) {
    var div = document.createElement('div');
    div.className = 'xjw-msg-sys';
    div.textContent = text;
    this.elBody.appendChild(div);
    this._scroll();
  };

  XuanjiChat.prototype._tool = function (text) {
    var div = document.createElement('div');
    div.className = 'xjw-tool';
    div.textContent = '🔧 ' + text;
    this.elBody.appendChild(div);
    this._scroll();
  };

  XuanjiChat.prototype._scroll = function () {
    this.elBody.scrollTop = this.elBody.scrollHeight;
  };

  XuanjiChat.prototype.submit = function () {
    var q = this.elInput.value.trim();
    if (!q || this.busy) return;
    if (!this.opts.appId || !this.opts.appKey) {
      this._sys('组件未配置 appId / appKey');
      return;
    }
    this.elInput.value = '';
    this._addMsg('xjw-msg-user', q);
    this._stream(q);
  };

  XuanjiChat.prototype._setBusy = function (busy) {
    this.busy = busy;
    this.elSend.disabled = busy;
  };

  /**
   * 发起对话并消费 SSE：POST 不能用 EventSource（仅 GET），用 fetch +
   * ReadableStream 手动按 \n\n 切帧；data: 行为 JSON 负载。
   */
  XuanjiChat.prototype._stream = function (query) {
    var self = this;
    var o = this.opts;
    var url = (o.baseUrl || '') + '/api/v1/apps/' + encodeURIComponent(o.appId) + '/chat-messages';
    this._setBusy(true);

    var agentDiv = this._addMsg('xjw-msg-agent', '');
    var caret = document.createElement('span');
    caret.className = 'xjw-caret';
    agentDiv.appendChild(caret);
    var answer = '';

    function appendText(t) {
      answer += t;
      agentDiv.textContent = answer;
      agentDiv.appendChild(caret);
      self._scroll();
    }
    function removeCaret() {
      if (caret.parentNode) caret.parentNode.removeChild(caret);
    }

    fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + o.appKey,
      },
      body: JSON.stringify({ query: query, sessionId: this.sessionId }),
    }).then(function (resp) {
      if (!resp.ok) {
        return resp.text().then(function (t) {
          throw new Error('HTTP ' + resp.status + (t ? '：' + t.slice(0, 120) : ''));
        });
      }
      var reader = resp.body.getReader();
      var decoder = new TextDecoder('utf-8');
      var buf = '';

      function pump() {
        return reader.read().then(function (r) {
          if (r.done) return;
          buf += decoder.decode(r.value, { stream: true });
          var frames = buf.split('\n\n');
          buf = frames.pop(); // 末段可能不完整，留到下一轮
          frames.forEach(function (frame) { self._handleFrame(frame, appendText, removeCaret); });
          return pump();
        });
      }
      return pump();
    }).then(function () {
      removeCaret();
      if (!answer) agentDiv.textContent = '（无回复内容）';
    }).catch(function (e) {
      removeCaret();
      if (agentDiv.parentNode) agentDiv.parentNode.removeChild(agentDiv);
      self._sys('请求失败：' + (e && e.message ? e.message : e));
    }).finally(function () {
      self._setBusy(false);
      self.elInput.focus();
    });
  };

  /** 处理单个 SSE 帧：event: 行定事件名，data: 行取 JSON 负载 */
  XuanjiChat.prototype._handleFrame = function (frame, appendText, removeCaret) {
    var event = 'message';
    var dataLines = [];
    frame.split('\n').forEach(function (line) {
      line = line.replace(/\r$/, '');
      if (line.indexOf('event:') === 0) event = line.slice(6).trim();
      else if (line.indexOf('data:') === 0) dataLines.push(line.slice(5).trim());
    });
    if (!dataLines.length) return;
    var payload = {};
    try { payload = JSON.parse(dataLines.join('\n')); } catch (e) { payload = { raw: dataLines.join('\n') }; }

    switch (event) {
      case 'agent.start':
        break;
      case 'agent.delta':
        if (payload.delta) appendText(payload.delta);
        break;
      case 'agent.result':
        if (payload.text) appendText(payload.text);
        break;
      case 'tool.call':
        this._tool((payload.name || '工具') + ' · 调用中…');
        break;
      case 'tool.result':
        this._tool((payload.name || '工具') + ' · 完成');
        break;
      case 'error':
        removeCaret();
        this._sys('错误：' + (payload.message || '未知错误'));
        break;
      case 'stage.finish':
        break;
      default:
        break;
    }
  };

  /* ---------- 转义（防注入：配置项进 HTML/属性） ---------- */

  function escHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function escAttr(s) { return escHtml(s); }

  /* ---------- 对外：编程式挂载（单例，重复调用先卸载旧实例） ---------- */

  var singleton = null;
  XuanjiChat.mount = function (opts) {
    if (singleton) {
      if (singleton.elRoot && singleton.elRoot.parentNode) {
        singleton.elRoot.parentNode.removeChild(singleton.elRoot);
      }
    }
    singleton = new XuanjiChat(opts);
    return singleton;
  };
  XuanjiChat.unmount = function () {
    if (singleton && singleton.elRoot && singleton.elRoot.parentNode) {
      singleton.elRoot.parentNode.removeChild(singleton.elRoot);
    }
    singleton = null;
  };

  return XuanjiChat;
  })();
})();

/* ---------- 自动初始化：data-* 属性声明式接入 ---------- */
(function () {
  function autoInit() {
    var s = document.querySelector('script[data-app-id][data-app-key]');
    if (!s || window.XuanjiChat.__xjwAuto) return;
    window.XuanjiChat.__xjwAuto = true;
    window.XuanjiChat.mount({
      appId: s.getAttribute('data-app-id'),
      appKey: s.getAttribute('data-app-key'),
      baseUrl: s.getAttribute('data-base-url') || '',
      title: s.getAttribute('data-title') || undefined,
      placeholder: s.getAttribute('data-placeholder') || undefined,
      welcome: s.getAttribute('data-welcome') || undefined,
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', autoInit);
  } else {
    autoInit();
  }
})();
