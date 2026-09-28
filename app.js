const state = {
  loggedIn: false, currentView: 'overview', projectFilter: 'all', search: '',
  scheduleValue: 5, scheduleUnit: 'minutes', restorePasses: 2,
  checks: [], accounts: [], running: false, history: [], projects: [], mode: 'LIVE',
  nextRunAt: null, selectedResults: null, selectedRun: null,
};
let token = sessionStorage.getItem('poolguard-token') || '';
let savedPolicyIds = [];
let pollTimer;
let busy = false;
async function api(path, method = 'GET', data) {
  const response = await fetch(`/api${path}`, {
    method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(data === undefined ? {} : { body: JSON.stringify(data) }),
  });
  const text = await response.text();
  let body;
  try { body = text ? JSON.parse(text) : null; } catch { throw new Error('后端响应异常，请通过 Java 服务地址打开页面'); }
  if (!response.ok) {
    if (response.status === 401) { token = ''; sessionStorage.removeItem('poolguard-token'); state.loggedIn = false; render(); }
    throw new Error(body?.message || `请求失败（${response.status}）`);
  }
  return body;
}
function notice(message, error = false) {
  document.querySelector('#notice')?.remove();
  const element = document.createElement('div');
  element.id = 'notice'; element.className = `notice ${error ? 'error' : ''}`;
  element.setAttribute('role', 'status'); element.textContent = message;
  document.body.append(element); setTimeout(() => element.remove(), 6000);
}
async function perform(action) {
  if (busy) return;
  busy = true;
  try { await action(); } catch (error) { notice(error.message, true); }
  finally { busy = false; }
}
function time(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未检测'; }
async function loadData(full = true) {
  const [accounts, history] = await Promise.all([api('/accounts'), api('/detection-runs')]);
  state.accounts = accounts.items.map(a => ({ ...a, status: a.status.toLowerCase(), lastCheck: time(a.lastCheck),
    latency: a.latencyMs == null ? '—' : `${a.latencyMs} ms` }));
  state.history = history; state.running = history.some(r => r.status === 'RUNNING');
  if (full) {
    const [settings, checks, system] = await Promise.all([api('/settings'), api('/check-policies'), api('/system')]);
    Object.assign(state, settings, system); state.checks = checks; savedPolicyIds = checks.map(c => c.id);
  }
}
function schedulePoll() {
  clearTimeout(pollTimer);
  pollTimer = setTimeout(async () => {
    if (!state.loggedIn) return;
    try {
      await loadData(false);
      if (!busy && !document.activeElement?.matches('input, textarea, select') && ['overview', 'accounts', 'history'].includes(state.currentView)) render();
    } catch (error) { notice(error.message, true); }
    schedulePoll();
  }, state.running ? 1500 : 15000);
}

const icons = {
  grid: '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7" rx="1.4"/><rect x="14" y="3" width="7" height="7" rx="1.4"/><rect x="3" y="14" width="7" height="7" rx="1.4"/><rect x="14" y="14" width="7" height="7" rx="1.4"/></svg>',
  users: '<svg viewBox="0 0 24 24"><path d="M16 20v-1.8a3.8 3.8 0 0 0-3.8-3.8H6.8A3.8 3.8 0 0 0 3 18.2V20"/><circle cx="9.5" cy="7.3" r="3.3"/><path d="M21 20v-1.7a3.7 3.7 0 0 0-2.7-3.6M16.5 4.1a3.3 3.3 0 0 1 0 6.4"/></svg>',
  pulse: '<svg viewBox="0 0 24 24"><path d="M3 12h4l2.2-7 4.2 14 2.3-7H21"/></svg>',
  sliders: '<svg viewBox="0 0 24 24"><path d="M4 6h16M4 12h16M4 18h16"/><circle cx="9" cy="6" r="2"/><circle cx="15" cy="12" r="2"/><circle cx="11" cy="18" r="2"/></svg>',
  search: '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/></svg>',
  arrow: '<svg viewBox="0 0 24 24"><path d="M5 12h14M13 6l6 6-6 6"/></svg>',
  check: '<svg viewBox="0 0 24 24"><path d="m5 12 4 4L19 6"/></svg>',
  play: '<svg viewBox="0 0 24 24"><path d="m8 5 11 7-11 7V5Z"/></svg>',
  pause: '<svg viewBox="0 0 24 24"><path d="M7 5v14M17 5v14"/></svg>',
  logout: '<svg viewBox="0 0 24 24"><path d="M10 5H5v14h5M14 8l4 4-4 4M18 12H9"/></svg>',
  bell: '<svg viewBox="0 0 24 24"><path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg>',
  clock: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3.2 2"/></svg>',
};

function icon(name) { return `<span class="icon">${icons[name] || ''}</span>`; }
function escapeHtml(value) { return String(value).replace(/[&<>'"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#039;', '"': '&quot;' }[char])); }
function statusMeta(status) {
  return ({
    untested: ['待检测', 'gray'], healthy: ['正常', 'green'], degraded: ['降智风险', 'amber'], recovering: ['恢复观察', 'blue'], disabled: ['已禁用', 'red'],
  })[status] || ['未知', 'gray'];
}
function filteredAccounts() {
  return state.accounts.filter((account) => {
    const projectMatch = state.projectFilter === 'all' || account.project === state.projectFilter;
    const query = state.search.trim().toLowerCase();
    const searchMatch = !query || `${account.email} ${account.id} ${account.project} ${account.model}`.toLowerCase().includes(query);
    return projectMatch && searchMatch;
  });
}
function count(status) { return state.accounts.filter((account) => account.status === status).length; }
function intervalText() { return `每 ${state.scheduleValue} ${{ minutes: '分钟', hours: '小时', days: '天' }[state.scheduleUnit]}`; }
function nextRunTime() { return state.nextRunAt ? time(state.nextRunAt) : '保存设置后计算'; }

function nextRunRelative() { return state.mode === 'DEMO' ? '演示模式：自动巡检默认关闭' : '繁忙时等待当前批次结束'; }

function render() {
  document.querySelector('#app').innerHTML = state.loggedIn ? dashboardTemplate() : loginTemplate();
  bindEvents();
}

function loginTemplate() {
  return `<main class="login-shell">
    <section class="login-visual">
      <div class="brand-mark"><span class="brand-symbol">P</span><span>PoolGuard</span></div>
      <div class="visual-copy"><p class="eyebrow">ACCOUNT RELIABILITY CONTROL</p><h1>让每个账号的<br><em>真实能力</em>都可见。</h1><p>统一检测 sub2api 与 codex-proxy-rs 号池，发现降智，自动隔离；恢复能力后，再回到生产池。</p></div>
      <div class="signal-card"><div class="signal-top"><span>号池能力检测</span><span class="live-dot">PoolGuard</span></div><div class="signal-bars"><i style="height: 28%"></i><i style="height: 42%"></i><i style="height: 35%"></i><i style="height: 58%"></i><i style="height: 50%"></i><i style="height: 72%"></i><i style="height: 64%"></i><i style="height: 88%"></i><i style="height: 78%"></i><i style="height: 100%"></i><i style="height: 90%"></i><i style="height: 100%"></i></div><div class="signal-foot"><strong>自定义检测</strong><span>题目 · 答案 · 周期</span></div></div>
      <div class="login-orbit orbit-one"></div><div class="login-orbit orbit-two"></div>
    </section>
    <section class="login-panel"><div class="login-form-wrap"><div class="mobile-brand"><span class="brand-symbol">P</span> PoolGuard</div><p class="eyebrow">WELCOME BACK</p><h2>登录管控台</h2><p class="form-subtitle">继续管理你的 AI 账号池</p><form id="login-form"><label>管理员账号<input id="login-user" value="admin" autocomplete="username" placeholder="输入管理员账号" /></label><label>登录密码<div class="password-wrap"><input id="login-password" type="password" value="pool-admin" autocomplete="current-password" placeholder="输入密码" /><button type="button" class="eye-button" aria-label="显示密码">◉</button></div></label><div class="form-meta"><span>登录会话在当前浏览器标签页保留</span></div><button class="primary-button login-button" type="submit">进入管控台 ${icon('arrow')}</button><p class="demo-hint">默认账号：<strong>admin</strong> / <strong>pool-admin</strong></p></form></div><div class="login-footer"><span>PoolGuard v0.2</span><span>Asia/Shanghai</span></div></section>
  </main>`;
}

function dashboardTemplate() {
  const active = state.currentView;
  return `<main class="app-shell"><aside class="sidebar"><div class="brand-mark"><span class="brand-symbol">P</span><span>PoolGuard</span></div><div class="workspace-label">CONTROL CENTER</div><nav>${[['overview','概览','grid'],['accounts','账号池','users'],['checks','检测策略','pulse'],['history','检测记录','clock'],['settings','系统设置','sliders']].map(([key, label, iconName]) => `<button class="nav-item ${active === key ? 'active' : ''}" data-view="${key}">${icon(iconName)}<span>${label}</span>${key === 'accounts' ? `<b class="nav-count">${state.accounts.length}</b>` : ''}</button>`).join('')}</nav><div class="sidebar-bottom"><div class="health-mini"><span class="health-icon">✦</span><div><strong>${state.mode === 'DEMO' ? '模拟项目环境' : 'Java 控制面'}</strong><small>${state.mode === 'DEMO' ? 'H2 · 内存会话' : '项目状态见系统设置'}</small></div></div><button id="logout" class="nav-item logout">${icon('logout')}<span>退出登录</span></button></div></aside><section class="main-content"><header class="topbar"><div><p class="breadcrumb">PoolGuard <span>/</span> ${active === 'overview' ? '总览' : active === 'accounts' ? '账号池' : active === 'checks' ? '检测策略' : active === 'history' ? '检测记录' : '系统设置'}</p><h1>${active === 'overview' ? '号池管控台' : active === 'accounts' ? '账号池' : active === 'checks' ? '检测策略' : active === 'history' ? '检测记录' : '系统设置'}</h1></div><div class="top-actions"><div class="avatar">AD</div><div class="top-user"><strong>Admin</strong><span>超级管理员</span></div></div></header>${state.mode === 'DEMO' ? '<div class="mode-banner">演示模式：账号、回答和禁用动作均为模拟；数据在服务重启后重置。</div>' : ''}${active === 'overview' ? overviewView() : active === 'accounts' ? accountsView() : active === 'checks' ? checksView() : active === 'history' ? historyView() : settingsView()}</section></main>`;
}

function overviewView() {
  const latest = state.history[0];
  const metrics = [['账号总数', state.accounts.length], ['检测通过', count('healthy')], ['自动隔离 / 观察', count('degraded') + count('recovering')], ['手工停用', count('disabled')]];
  return `<div class="view-body"><section class="hero-row"><div><p class="section-kicker">${new Date().toLocaleDateString('zh-CN')}</p><h2>号池健康度，一眼掌握。</h2><p class="muted">${latest ? `最近批次：${escapeHtml(latest.status)} · ${time(latest.startedAt)}` : '同步账号后，运行第一轮检测。'}</p></div><button id="run-check" class="primary-button" ${state.running ? 'disabled' : ''}>${icon('play')}${state.running ? '检测中…' : '立即检测'}</button></section>
  <section class="metric-grid">${metrics.map(([label, value]) => `<div class="metric-card"><div class="metric-label">${label}</div><strong>${value}</strong></div>`).join('')}</section>
  <div class="content-grid"><section class="panel"><div class="panel-heading"><div><h3>项目账号</h3><p>当前同步目录中的状态</p></div><button id="sync-accounts" class="secondary-button">同步账号</button></div>${['sub2api', 'codex-proxy-rs'].map(project => { const all = state.accounts.filter(a => a.project === project); return `<div class="connection-row"><div><strong>${project}</strong><small>共 ${all.length} 个 · ${all.filter(a => a.status === 'healthy').length} 个通过 · ${all.filter(a => a.failureReason).length} 个有待处理记录</small></div></div>`; }).join('')}</section>
  <section class="panel schedule-panel"><div class="panel-heading"><div><h3>检测调度</h3><p>${intervalText()}</p></div>${icon('clock')}</div><div class="next-run"><strong>${nextRunTime()}</strong><span>${nextRunRelative()}</span></div><button id="edit-interval" class="secondary-button">调整周期和恢复次数</button></section></div>
  <section class="panel recent-panel"><div class="panel-heading"><div><h3>最近账号状态</h3><p>答题结果与接口异常分别记录</p></div><button class="text-button" data-view="history">检测记录 ${icon('arrow')}</button></div>${recentRows()}</section></div>`;
}

function recentRows() { return `<div class="table-wrap"><table><thead><tr><th>账号</th><th>项目</th><th>模型</th><th>通过率</th><th>状态</th><th>检测时间</th><th>操作</th></tr></thead><tbody>${state.accounts.slice(0, 4).map(accountRow).join('') || '<tr><td colspan="7">暂无账号，请先同步</td></tr>'}</tbody></table></div>`; }

function accountsView() {
  const rows = filteredAccounts();
  return `<div class="view-body"><div class="toolbar"><div class="filter-tabs"><button class="${state.projectFilter === 'all' ? 'selected' : ''}" data-project="all">全部账号 <b>${state.accounts.length}</b></button><button class="${state.projectFilter === 'sub2api' ? 'selected' : ''}" data-project="sub2api">sub2api <b>${state.accounts.filter((a) => a.project === 'sub2api').length}</b></button><button class="${state.projectFilter === 'codex-proxy-rs' ? 'selected' : ''}" data-project="codex-proxy-rs">codex-proxy-rs <b>${state.accounts.filter((a) => a.project === 'codex-proxy-rs').length}</b></button></div><div class="toolbar-actions"><button id="sync-accounts" class="secondary-button">同步账号</button><label class="search-box">${icon('search')}<input id="account-search" value="${escapeHtml(state.search)}" placeholder="搜索账号、模型或 ID" /></label><button id="run-check" class="primary-button small">${icon('play')}立即检测</button></div></div><section class="panel account-panel"><div class="panel-heading"><div><h3>账号状态</h3><p>答错后隔离并继续复检；连续通过后恢复。接口异常不作为答错。</p></div><span class="table-summary">显示 ${rows.length} / ${state.accounts.length}</span></div><div class="table-wrap"><table><thead><tr><th>账号</th><th>项目</th><th>模型</th><th>健康分</th><th>状态</th><th>上次检测</th><th></th></tr></thead><tbody>${rows.length ? rows.map(accountRow).join('') : `<tr><td colspan="7" class="empty-state">没有符合条件的账号</td></tr>`}</tbody></table></div></section></div>`;
}

function accountRow(account) {
  const [label, tone] = statusMeta(account.status);
  const id = escapeHtml(account.id);
  return `<tr><td><div class="account-cell"><span class="account-avatar ${tone}">${escapeHtml(account.email.slice(0,1))}</span><div><strong>${escapeHtml(account.email)}</strong><small>${escapeHtml(account.externalId)}</small></div></div></td><td><span class="project-pill">${escapeHtml(account.project)}</span></td><td><span class="mono">${escapeHtml(account.model)}</span></td><td>${account.score}%</td><td><span class="status ${tone}">${label}</span>${account.failureReason ? `<small class="account-reason">${escapeHtml(account.failureReason)}</small>` : ''}</td><td>${account.lastCheck}</td><td><div class="account-actions">${account.monitoring ? `<button class="row-action" data-account-action="retest" data-id="${id}">复检</button><button class="row-action" data-account-action="disable" data-id="${id}">停用</button>` : `<button class="row-action" data-account-action="restore" data-id="${id}">手工恢复</button>`}</div></td></tr>`;
}

function checksView() {
  return `<div class="view-body"><div class="hero-row compact"><div><p class="section-kicker">DETECTION ENGINE</p><h2>检测策略</h2><p class="muted">答案默认精确匹配（忽略大小写、首尾空白）。如需关键词匹配，以 keywords: 开头，随后每行一个必需关键词。</p></div><button id="run-check" class="primary-button">${icon('play')}立即运行全量检测</button></div><section class="strategy-layout"><div class="panel strategy-list"><div class="panel-heading"><div><h3>当前问题集</h3><p>共 ${state.checks.length} 道 · 全部启用题目通过才视为通过</p></div><div class="panel-heading-actions"><button id="add-check" class="secondary-button">＋新增题目</button><button id="save-checks" class="text-button">保存问题集</button></div></div><div class="check-editor-list">${state.checks.map((check, index) => `<div class="check-editor"><div class="check-editor-head"><span class="check-index">0${index + 1}</span><button class="delete-check" data-delete-check="${index}" ${state.checks.length === 1 ? 'disabled' : ''}>删除</button></div><label>检测题目<input data-check-field="title" data-check-index="${index}" value="${escapeHtml(check.title)}" /></label><label>标准答案 / 评分要点<textarea data-check-field="answer" data-check-index="${index}" rows="3">${escapeHtml(check.answer)}</textarea></label><label class="remember"><input type="checkbox" data-check-active="${index}" ${check.active !== false ? 'checked' : ''} />启用此题</label><label>能力标签<input data-check-field="model" data-check-index="${index}" value="${escapeHtml(check.model)}" /></label></div>`).join('')}</div></div><div class="panel policy-panel"><div class="panel-heading"><div><h3>判定策略</h3><p>账号状态如何变化</p></div></div><div class="policy-step"><span class="policy-number green-bg">1</span><div><strong>通过检测</strong><p>全部启用题目通过，保留在生产号池。</p></div></div><div class="policy-step"><span class="policy-number amber-bg">2</span><div><strong>首次失败</strong><p>标记答错，调用目标项目隔离，下一轮继续复检。</p></div></div><div class="policy-step"><span class="policy-number red-bg">3</span><div><strong>接口异常</strong><p>记录 ERROR，不把超时或不支持判定为答错。</p></div></div><div class="policy-step"><span class="policy-number blue-bg">4</span><div><strong>恢复观察</strong><p>连续 ${state.restorePasses} 次通过后恢复可用。</p></div></div></div></section></div>`;
}

function settingsView() {
  return `<div class="view-body"><section class="settings-grid"><div class="panel settings-card"><div class="panel-heading"><div><h3>检测调度</h3><p>自动巡检的运行节奏</p></div>${icon('clock')}</div><label class="setting-label">检测间隔<div class="interval-input"><input id="schedule-value" type="number" min="1" max="999" value="${state.scheduleValue}" /><select id="schedule-unit"><option value="minutes" ${state.scheduleUnit === 'minutes' ? 'selected' : ''}>分钟</option><option value="hours" ${state.scheduleUnit === 'hours' ? 'selected' : ''}>小时</option><option value="days" ${state.scheduleUnit === 'days' ? 'selected' : ''}>天</option></select></div><small class="setting-help">支持 1 分钟至 999 天，保存后用于下一次自动巡检。</small></label><label class="setting-label">恢复观察次数<select id="restore-passes"><option value="1" ${state.restorePasses === 1 ? 'selected' : ''}>连续 1 次通过</option><option value="2" ${state.restorePasses === 2 ? 'selected' : ''}>连续 2 次通过</option><option value="3" ${state.restorePasses === 3 ? 'selected' : ''}>连续 3 次通过</option><option value="5" ${state.restorePasses === 5 ? 'selected' : ''}>连续 5 次通过</option></select></label><button class="primary-button small" id="save-settings">保存设置</button></div><div class="panel settings-card"><div class="panel-heading"><div><h3>项目连接</h3><p>管理密钥由服务端环境变量提供</p></div></div>${state.projects.map(project => `<div class="connection-row"><div><strong>${escapeHtml(project.code)}</strong><small>${project.enabled ? '已配置' : '未配置'} · ${escapeHtml(project.capability)}</small></div></div>`).join('')}<button id="sync-accounts" class="secondary-button">同步并检查连接</button></div></section></div>`;
}

function historyView() {
  return `<div class="view-body"><section class="panel"><div class="panel-heading"><div><h3>检测批次</h3><p>最多显示最近 20 次 · ERROR 表示检测或状态回写异常</p></div></div><div class="table-wrap"><table><thead><tr><th>时间</th><th>触发</th><th>状态</th><th>总数 / 通过 / 答错 / 异常</th><th>详情</th></tr></thead><tbody>${state.history.map(run => `<tr><td>${time(run.startedAt)}</td><td>${escapeHtml(run.triggerType)}</td><td>${escapeHtml(run.status)}<small class="account-reason">${escapeHtml(run.errorMessage || '')}</small></td><td>${run.total} / ${run.passed} / ${run.failed} / ${run.errors}</td><td><button class="row-action" data-run-id="${run.id}">查看答题记录</button></td></tr>`).join('') || '<tr><td colspan="5">尚无检测记录</td></tr>'}</tbody></table></div></section>
  ${state.selectedResults ? `<section class="panel recent-panel"><div class="panel-heading"><h3>逐题记录</h3></div>${state.selectedResults.map(r => `<div class="result-card"><strong>${escapeHtml(r.outcome)} · ${escapeHtml(state.accounts.find(a => a.id === r.accountId)?.externalId || r.accountId)}</strong><p>${escapeHtml(r.question || '状态回写')}</p><small>标准答案：${escapeHtml(r.expectedAnswer || '—')}</small><pre>${escapeHtml(r.answerExcerpt || r.reason || '无回答')}</pre></div>`).join('') || '<p class="result-card">暂无逐题记录</p>'}</section>` : ''}</div>`;
}

function bindEvents() {
  document.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () => { state.currentView = button.dataset.view; render(); }));
  document.querySelectorAll('[data-project]').forEach(button => button.addEventListener('click', () => { state.projectFilter = button.dataset.project; render(); }));
  document.querySelector('#logout')?.addEventListener('click', () => perform(async () => { await api('/auth/logout', 'POST'); token = ''; sessionStorage.removeItem('poolguard-token'); state.loggedIn = false; clearTimeout(pollTimer); render(); }));
  document.querySelector('#login-form')?.addEventListener('submit', event => { event.preventDefault(); perform(async () => {
    const login = await api('/auth/login', 'POST', { username: document.querySelector('#login-user').value, password: document.querySelector('#login-password').value });
    token = login.token; sessionStorage.setItem('poolguard-token', token); await loadData(); state.loggedIn = true; render(); schedulePoll();
  }); });
  document.querySelector('.eye-button')?.addEventListener('click', () => { const input = document.querySelector('#login-password'); input.type = input.type === 'password' ? 'text' : 'password'; });
  document.querySelector('#account-search')?.addEventListener('input', event => { state.search = event.target.value; const cursor = event.target.selectionStart; render(); const input = document.querySelector('#account-search'); input.focus(); input.setSelectionRange(cursor, cursor); });
  document.querySelector('#save-settings')?.addEventListener('click', () => perform(async () => {
    const value = Number(document.querySelector('#schedule-value').value);
    if (!Number.isInteger(value) || value < 1 || value > 999) throw new Error('间隔必须是 1 到 999 的整数');
    Object.assign(state, await api('/settings', 'PATCH', { scheduleValue: value, scheduleUnit: document.querySelector('#schedule-unit').value, restorePasses: Number(document.querySelector('#restore-passes').value) }));
    render(); notice('检测设置已保存');
  }));
  document.querySelector('#add-check')?.addEventListener('click', () => { state.checks.push({ title: '', answer: '', model: '自定义能力', active: true }); render(); });
  document.querySelectorAll('[data-delete-check]').forEach(button => button.addEventListener('click', () => { state.checks.splice(Number(button.dataset.deleteCheck), 1); render(); }));
  document.querySelectorAll('[data-check-field]').forEach(input => input.addEventListener('input', event => { state.checks[Number(event.target.dataset.checkIndex)][event.target.dataset.checkField] = event.target.value; }));
  document.querySelectorAll('[data-check-active]').forEach(input => input.addEventListener('change', event => { state.checks[Number(event.target.dataset.checkActive)].active = event.target.checked; }));
  document.querySelector('#save-checks')?.addEventListener('click', () => perform(async () => {
    if (state.checks.some(c => !c.title.trim() || !c.answer.trim() || !c.model.trim())) throw new Error('请补齐题目、答案和能力标签');
    state.checks = await api('/check-policies', 'PUT', state.checks.map(check => ({
      id: check.id || null, policy: { title: check.title, answer: check.answer, model: check.model, active: check.active !== false }
    })));
    savedPolicyIds = state.checks.map(c => c.id); render(); notice('问题集已保存');
  }));
  document.querySelector('#edit-interval')?.addEventListener('click', () => { state.currentView = 'settings'; render(); });
  document.querySelector('#sync-accounts')?.addEventListener('click', () => perform(async () => {
    const reports = await api('/accounts/sync', 'POST'); await loadData(false); render();
    notice(reports.length ? reports.map(r => `${r.project}：${r.success ? `同步 ${r.accounts} 个账号` : r.message}`).join('；') : '尚未配置项目管理 API Key', !reports.length || reports.some(r => !r.success));
  }));
  document.querySelectorAll('[data-account-action]').forEach(button => button.addEventListener('click', () => perform(async () => {
    await api(`/accounts/${button.dataset.id}/actions`, 'POST', { action: button.dataset.accountAction });
    await loadData(false); render(); schedulePoll(); notice('操作已提交');
  })));
  document.querySelector('#run-check')?.addEventListener('click', () => perform(async () => {
    await api('/detection-runs', 'POST'); await loadData(false); render(); schedulePoll(); notice('检测批次已提交');
  }));
  document.querySelectorAll('[data-run-id]').forEach(button => button.addEventListener('click', () => perform(async () => {
    state.selectedRun = button.dataset.runId; state.selectedResults = await api(`/detection-runs/${state.selectedRun}/results`); render();
  })));
}

render();
(async () => {
  try {
    const system = await api('/system'); Object.assign(state, system);
    if (token) { await loadData(); state.loggedIn = true; schedulePoll(); }
    if (state.loggedIn) render();
  } catch (error) { notice(error.message, true); }
})();
