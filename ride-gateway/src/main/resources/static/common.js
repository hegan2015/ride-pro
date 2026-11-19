// 司乘端/管理端共享工具：REST、WebSocket（内置心跳）、金额/状态格式化、toast、抽屉/弹窗、订单详情组件、轻量地图。
const WS_PORT = 9101;

const PRESETS = [
  { name: '国贸', lat: 39.908, lng: 116.459 },
  { name: '中关村', lat: 39.983, lng: 116.316 },
  { name: '望京', lat: 39.998, lng: 116.470 },
  { name: '西单', lat: 39.907, lng: 116.374 },
  { name: '北京南站', lat: 39.865, lng: 116.378 },
  { name: '首都机场', lat: 40.080, lng: 116.603 },
];
const BBOX = { minLat: 39.80, maxLat: 40.12, minLng: 116.20, maxLng: 116.65 };

const STATUS_LABEL = {
  CREATED: '已创建', DISPATCHING: '派单中', ACCEPTED: '已接单', ARRIVED: '司机已到达',
  IN_PROGRESS: '行程中', COMPLETED: '待支付', PAID: '已支付',
  CANCELLED: '已取消', EXPIRED: '已过期', REFUNDED: '已退款',
};
const STATUS_KIND = {
  PAID: 'ok', COMPLETED: 'warn', CANCELLED: 'err', EXPIRED: 'err', REFUNDED: 'warn',
  DISPATCHING: 'info', ACCEPTED: 'info', ARRIVED: 'info', IN_PROGRESS: 'info', CREATED: 'info',
};
const SERVICE_LABEL = { EXPRESS: '快车', PREMIER: '专车', TAXI: '出租车', CARPOOL: '拼车', TEHUI: '特惠快车', YOUXIANG: '优享', LUXURY: '豪华车' };
const BIZ_LABEL = {
  TOPUP: '充值', WITHDRAW: '提现', PAY_DEBIT: '支付车费', PAY_CREDIT: '行程收入',
  PLATFORM_FEE: '平台服务费', CANCEL_FEE_DEBIT: '取消费支出', CANCEL_FEE_CREDIT: '取消费收入',
  REFUND_DEBIT: '退款扣回(司机)', REFUND_FEE_DEBIT: '退款承担(平台)', REFUND_CREDIT: '退款到账',
  TIP: '打赏/调度费', TIP_CREDIT: '打赏/调度费收入', TIP_DEBIT: '打赏/调度费支出',
};
const ACTIVE_STATUSES = ['CREATED', 'DISPATCHING', 'ACCEPTED', 'ARRIVED', 'IN_PROGRESS', 'COMPLETED'];

async function api(method, path, body, headers) {
  const opt = { method, headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}) };
  if (body !== undefined) opt.body = JSON.stringify(body);
  const res = await fetch(path, opt);
  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!res.ok) {
    const msg = (data && data.error) ? data.error : (res.status + ' ' + res.statusText);
    const err = new Error(msg);
    err.status = res.status;
    throw err;
  }
  return data;
}

function yuan(fen) {
  if (fen === null || fen === undefined) return '--';
  return '¥' + (fen / 100).toFixed(2);
}

function now() {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false });
}

function fmtTime(ts) {
  if (!ts) return '--';
  const d = new Date(ts);
  const p = n => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function fmtDateInput(ts) {
  const d = ts ? new Date(ts) : new Date();
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function dayStartTs(dateStr) {
  if (!dateStr) return undefined;
  return new Date(dateStr + 'T00:00:00').getTime();
}
function dayEndTs(dateStr) {
  if (!dateStr) return undefined;
  return new Date(dateStr + 'T23:59:59.999').getTime();
}

function statusBadge(status) {
  return `<span class="badge ${STATUS_KIND[status] || ''}">${STATUS_LABEL[status] || status}</span>`;
}

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// 断线自动重连（指数退避封顶 15s）+ 内置 30s PING 心跳（服务端 90s 空闲检测）。
// 收到 KICKED（同身份被顶号）则停止重连。返回 handle：close() 表示主动放弃。
function connectWS(role, id, onEvent, onStatus) {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const url = `${proto}://${location.hostname}:${WS_PORT}/ws/${role}/${encodeURIComponent(id)}`;
  let ws = null, stopped = false, delay = 1000, heartbeat = null;
  const open = () => {
    if (stopped) return;
    ws = new WebSocket(url);
    ws.onopen = () => {
      delay = 1000;
      clearInterval(heartbeat);
      heartbeat = setInterval(() => { if (ws && ws.readyState === 1) ws.send(JSON.stringify({ event: 'PING' })); }, 30000);
      onStatus && onStatus('connected');
    };
    ws.onmessage = (e) => {
      let msg;
      try { msg = JSON.parse(e.data); } catch { return; }
      if (msg.event === 'KICKED') stopped = true;
      try { onEvent(msg); } catch { /* ignore */ }
      if (msg.event === 'KICKED') ws.close();
    };
    ws.onclose = () => {
      clearInterval(heartbeat);
      onStatus && onStatus('disconnected');
      if (!stopped) {
        setTimeout(open, delay);
        delay = Math.min(delay * 2, 15000);
      }
    };
    ws.onerror = () => ws.close();
  };
  open();
  return {
    get readyState() { return ws ? ws.readyState : 3; },
    send: (data) => ws && ws.send(data),
    close: () => { stopped = true; clearInterval(heartbeat); if (ws) ws.close(); },
  };
}

function pushLog(container, event, data) {
  const item = document.createElement('div');
  item.className = 'item';
  const detail = data ? JSON.stringify(data) : '';
  item.innerHTML = `<span class="t">${now()}</span><span class="e">${escapeHtml(event)}</span> <span class="muted small">${escapeHtml(detail)}</span>`;
  container.prepend(item);
  while (container.children.length > 60) container.removeChild(container.lastChild);
}

// 浮动 toast 栈：无需页面预置元素。
function toast(msg, kind) {
  let stack = document.getElementById('toastStack');
  if (!stack) {
    stack = document.createElement('div');
    stack.id = 'toastStack';
    document.body.appendChild(stack);
  }
  const el = document.createElement('div');
  el.className = 'toast ' + (kind || 'info');
  el.textContent = msg;
  stack.appendChild(el);
  setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 300); }, 3500);
  while (stack.children.length > 5) stack.removeChild(stack.firstChild);
}

// 右侧抽屉（订单详情/流水详情等）。
function openDrawer(title, bodyHtml, wide) {
  closeDrawer();
  const overlay = document.createElement('div');
  overlay.className = 'drawer-overlay';
  overlay.innerHTML = `
    <aside class="drawer${wide ? ' wide' : ''}">
      <div class="drawer-head"><h3>${escapeHtml(title)}</h3><button class="ghost drawer-close">✕</button></div>
      <div class="drawer-body">${bodyHtml}</div>
    </aside>`;
  overlay.addEventListener('click', e => { if (e.target === overlay) closeDrawer(); });
  overlay.querySelector('.drawer-close').onclick = closeDrawer;
  document.body.appendChild(overlay);
  document.body.style.overflow = 'hidden';
  return overlay.querySelector('.drawer-body');
}
function closeDrawer() {
  const el = document.querySelector('.drawer-overlay');
  if (el) el.remove();
  document.body.style.overflow = '';
}

// 确认弹窗，Promise<boolean>。
function confirmDialog(message, okText) {
  return new Promise(resolve => {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
      <div class="modal">
        <div class="modal-body">${message}</div>
        <div class="btns" style="justify-content:flex-end">
          <button class="ghost" data-act="no">取消</button>
          <button data-act="ok">${escapeHtml(okText || '确认')}</button>
        </div>
      </div>`;
    const done = v => { overlay.remove(); resolve(v); };
    overlay.querySelector('[data-act=no]').onclick = () => done(false);
    overlay.querySelector('[data-act=ok]').onclick = () => done(true);
    overlay.addEventListener('click', e => { if (e.target === overlay) done(false); });
    document.body.appendChild(overlay);
  });
}

// 评价弹窗：1~5 星 + 评语，Promise<{score,comment}|null>。
function ratingModal(title) {
  return new Promise(resolve => {
    let score = 5;
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
      <div class="modal">
        <h3 style="margin:0 0 10px">${escapeHtml(title)}</h3>
        <div class="stars">${[1, 2, 3, 4, 5].map(i => `<span data-star="${i}" class="on">★</span>`).join('')}</div>
        <textarea id="ratingComment" rows="3" placeholder="说说本次行程体验（可选）" style="width:100%;margin-top:10px;background:#0b1220;color:var(--text);border:1px solid var(--line);border-radius:9px;padding:9px"></textarea>
        <div class="btns" style="justify-content:flex-end">
          <button class="ghost" data-act="no">跳过</button>
          <button data-act="ok">提交评价</button>
        </div>
      </div>`;
    const stars = overlay.querySelectorAll('[data-star]');
    const paint = () => stars.forEach(s => s.classList.toggle('on', +s.dataset.star <= score));
    stars.forEach(s => s.onclick = () => { score = +s.dataset.star; paint(); });
    const done = v => { overlay.remove(); resolve(v); };
    overlay.querySelector('[data-act=no]').onclick = () => done(null);
    overlay.querySelector('[data-act=ok]').onclick = () => done({ score, comment: overlay.querySelector('#ratingComment').value.trim() });
    document.body.appendChild(overlay);
  });
}

// 标签化评价弹窗：星级 + 标签 chips + 匿名 + 评语，Promise<{score,comment,tags,anonymous}|null>。
function ratingModalTags(title, tagOptions) {
  return new Promise(resolve => {
    let score = 5;
    const picked = new Set();
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
      <div class="modal">
        <h3 style="margin:0 0 10px">${escapeHtml(title)}</h3>
        <div class="stars">${[1, 2, 3, 4, 5].map(i => `<span data-star="${i}" class="on">★</span>`).join('')}</div>
        <div class="chips" id="rtTags" style="display:flex;gap:7px;flex-wrap:wrap;margin:10px 0">${(tagOptions || []).map(t => `<button type="button" data-tag="${escapeHtml(t)}" style="background:#f4f6fa;color:var(--text);border:1.5px solid transparent;border-radius:999px;font-size:12.5px;padding:6px 12px">${escapeHtml(t)}</button>`).join('')}</div>
        <label style="display:flex;align-items:center;gap:6px;margin:6px 0"><input type="checkbox" id="rtAnon" style="width:auto"> 匿名评价</label>
        <textarea id="ratingComment" rows="2" placeholder="说说本次体验（可选）" style="width:100%;margin-top:6px;background:#fff;color:var(--text);border:1px solid var(--line);border-radius:9px;padding:9px"></textarea>
        <div class="btns" style="justify-content:flex-end">
          <button class="ghost" data-act="no">跳过</button>
          <button data-act="ok">提交评价</button>
        </div>
      </div>`;
    const stars = overlay.querySelectorAll('[data-star]');
    const paint = () => stars.forEach(s => s.classList.toggle('on', +s.dataset.star <= score));
    stars.forEach(s => s.onclick = () => { score = +s.dataset.star; paint(); });
    overlay.querySelectorAll('[data-tag]').forEach(b => b.onclick = () => {
      const t = b.dataset.tag;
      if (picked.has(t)) { picked.delete(t); b.style.borderColor = 'transparent'; b.style.background = '#f4f6fa'; b.style.color = 'var(--text)'; }
      else { picked.add(t); b.style.borderColor = 'var(--orange,#ff6a00)'; b.style.background = 'var(--soft,#fff4ea)'; b.style.color = 'var(--orange-d,#e85d04)'; }
    });
    const done = v => { overlay.remove(); resolve(v); };
    overlay.querySelector('[data-act=no]').onclick = () => done(null);
    overlay.querySelector('[data-act=ok]').onclick = () => done({ score, comment: overlay.querySelector('#ratingComment').value.trim(), tags: [...picked].join(','), anonymous: overlay.querySelector('#rtAnon').checked });
    document.body.appendChild(overlay);
  });
}

// 分页条。
function renderPager(el, page, size, total, cb) {
  const pages = Math.max(1, Math.ceil(total / size));
  el.innerHTML = `
    <button class="ghost" ${page <= 1 ? 'disabled' : ''} data-p="${page - 1}">上一页</button>
    <span class="muted small">第 ${page} / ${pages} 页 · 共 ${total} 条</span>
    <button class="ghost" ${page >= pages ? 'disabled' : ''} data-p="${page + 1}">下一页</button>`;
  el.querySelectorAll('button[data-p]').forEach(b => b.onclick = () => cb(+b.dataset.p));
}

// 计价明细（预估快照/最终价通用）。
function fareHtml(f) {
  if (!f) return '<div class="muted small">无计价明细</div>';
  const rows = [
    ['车型', SERVICE_LABEL[f.serviceType] || f.serviceType || '--'],
    ['里程 / 时长', `${f.distanceKm} km / ${f.durationMin} 分钟`],
    ['起步价', yuan(f.baseFee)],
    ['里程费', yuan(f.distanceFee)],
    ['时长费', yuan(f.timeFee)],
  ];
  if (f.peakRate && f.peakRate > 1) rows.push(['高峰/夜间系数', `×${f.peakRate}`]);
  if (f.longDistanceFee) rows.push(['远途费', yuan(f.longDistanceFee)]);
  rows.push(['合计', `<b style="color:var(--green)">${yuan(f.totalFee)}</b>`]);
  if (f.version) rows.push(['规则版本', f.version]);
  return rows.map(([k, v]) => `<div class="kv"><span class="k">${k}</span><span class="v">${v}</span></div>`).join('');
}

// 纵向时间线：订单全生命周期节点。
function timelineHtml(o) {
  const nodes = [
    ['叫车', o.createdAt], ['接单', o.acceptedAt], ['司机到达', o.arrivedAt],
    ['行程开始', o.startedAt], ['行程结束', o.completedAt], ['支付完成', o.paidAt],
    ['取消', o.cancelledAt], ['过期', o.expiredAt], ['退款', o.refundedAt],
  ].filter(([, ts]) => ts);
  return `<div class="vtl">${nodes.map(([label, ts], i) => `
    <div class="vtl-item${i === nodes.length - 1 ? ' last' : ''}">
      <span class="vtl-dot"></span>
      <div><div class="vtl-label">${label}</div><div class="muted small">${fmtTime(ts)}</div></div>
    </div>`).join('')}</div>`;
}

function ledgerRowsHtml(items) {
  if (!items || !items.length) return '<div class="muted small">暂无流水</div>';
  return items.map(e => `
    <div class="kv">
      <span class="k">${BIZ_LABEL[e.bizType] || e.bizType}<br><span class="small">${fmtTime(e.createdAt)}</span></span>
      <span class="v" style="color:${e.amount >= 0 ? 'var(--green)' : 'var(--red)'}">${e.amount >= 0 ? '+' : ''}${yuan(e.amount)}</span>
    </div>`).join('');
}

function ratingsHtml(ratings) {
  if (!ratings || !ratings.length) return '<div class="muted small">暂无评价</div>';
  return ratings.map(r => `
    <div class="kv"><span class="k">${r.role === 'passenger' ? '乘客评价' : '司机评价'}${r.anonymous ? '（匿名）' : ''}<br>
      <span class="small">${fmtTime(r.createdAt)}</span></span>
      <span class="v"><span style="color:var(--amber)">${'★'.repeat(r.score)}${'☆'.repeat(5 - r.score)}</span>
      ${r.tags ? `<br><span class="small" style="color:var(--orange,#ff6a00)">${escapeHtml(r.tags).replace(/,/g, ' · ')}</span>` : ''}
      ${r.comment ? `<br><span class="small muted">${escapeHtml(r.comment)}</span>` : ''}</span></div>`).join('');
}

// 订单详情抽屉：概要 + 计价明细 + 纵向时间线 + 本人流水 + 评价 + 轨迹小地图。
// opts: { userId: 查本人流水, extraHtml: 追加操作区, onReady(body, order) }
async function openOrderDetail(orderId, opts) {
  opts = opts || {};
  const body = openDrawer('订单详情 ' + orderId, '<div class="muted">加载中…</div>', true);
  try {
    const o = await api('GET', '/api/orders/' + orderId);
    const [ledger, ratings, track] = await Promise.all([
      opts.userId ? api('GET', `/api/wallets/${opts.userId}/ledger?orderId=${orderId}&size=50`).catch(() => null) : Promise.resolve(null),
      api('GET', `/api/orders/${orderId}/ratings`).catch(() => []),
      api('GET', `/api/locations/track/${orderId}`).catch(() => []),
    ]);
    body.innerHTML = `
      <div class="spread" style="margin-bottom:10px">
        ${statusBadge(o.status)}
        <span class="badge">${SERVICE_LABEL[o.serviceType] || o.serviceType || '快车'}</span>
      </div>
      <div class="kv"><span class="k">乘客</span><span class="v">${escapeHtml(o.passengerName || '--')} (${escapeHtml(o.passengerId || '')})</span></div>
      <div class="kv"><span class="k">司机</span><span class="v">${o.driverName ? escapeHtml(o.driverName) + ' · ' + escapeHtml(o.driverCarNo || '') : '未接单'}</span></div>
      <div class="kv"><span class="k">行程</span><span class="v">${escapeHtml(o.originName || '--')} → ${escapeHtml(o.destName || '--')}</span></div>
      <div class="kv"><span class="k">预估 / 实际</span><span class="v">${yuan(o.estimateFee)} / ${yuan(o.actualFee)}</span></div>
      ${o.actualDistanceKm != null ? `<div class="kv"><span class="k">实际里程 / 时长</span><span class="v">${o.actualDistanceKm} km / ${o.actualDurationMin} 分钟</span></div>` : ''}
      ${o.cancelFee ? `<div class="kv"><span class="k">取消费</span><span class="v">${yuan(o.cancelFee)}</span></div>` : ''}
      ${o.cancelReason ? `<div class="kv"><span class="k">取消/退款原因</span><span class="v">${escapeHtml(o.cancelReason)}</span></div>` : ''}
      ${o.dispatchRound ? `<div class="kv"><span class="k">派单轮次</span><span class="v">${o.dispatchRound}</span></div>` : ''}
      <h4 class="sec">计价明细（下单快照）</h4>${fareHtml(o.fare)}
      <h4 class="sec">行程时间线</h4>${timelineHtml(o)}
      <h4 class="sec">行程轨迹${track && track.length ? `（${track.length} 点）` : '（无）'}</h4>
      <canvas class="map mini" id="drawerMap"></canvas>
      ${opts.userId ? `<h4 class="sec">我的相关流水</h4>${ledgerRowsHtml(ledger && ledger.items)}` : ''}
      <h4 class="sec">评价</h4>${ratingsHtml(ratings)}
      ${opts.extraHtml || ''}`;
    const canvas = body.querySelector('#drawerMap');
    if (canvas) {
      const m = new MapView(canvas);
      const markers = [];
      if (o.originLat != null) markers.push({ lat: o.originLat, lng: o.originLng, color: '#34d399', label: '起点' });
      if (o.destLat != null) markers.push({ lat: o.destLat, lng: o.destLng, color: '#f87171', label: '终点' });
      if (track && track.length) m.setPath(track, '#60a5fa');
      m.setMarkers(markers);
    }
    opts.onReady && opts.onReady(body, o);
  } catch (e) {
    body.innerHTML = `<div class="badge err">加载失败：${escapeHtml(e.message)}</div>`;
  }
}

// 订单记录列表行（司乘端历史/管理端共用）。
function historyRowsHtml(items) {
  if (!items.length) return '<div class="muted" style="padding:16px 0">没有符合条件的订单</div>';
  return items.map(o => `
    <div class="hist-item" data-oid="${o.id}">
      <div class="spread">
        <span class="hist-route">${escapeHtml(o.originName || '--')} → ${escapeHtml(o.destName || '--')}</span>
        ${statusBadge(o.status)}
      </div>
      <div class="spread muted small" style="margin-top:4px">
        <span>${o.id} · ${SERVICE_LABEL[o.serviceType] || '快车'} · ${fmtTime(o.createdAt)}</span>
        <span style="color:var(--text);font-weight:600">${yuan(o.actualFee != null ? o.actualFee : o.estimateFee)}</span>
      </div>
    </div>`).join('');
}

function bindHistoryRows(container, onOpen) {
  container.querySelectorAll('.hist-item').forEach(el => el.onclick = () => onOpen(el.dataset.oid));
}

// 轻量 canvas 地图：高德免 key 栅格瓦片底图（Web Mercator）+ 标记 + 轨迹折线，支持点击取坐标。
// 瓦片加载失败（离线）时回退网格底图，保证零依赖可运行。
const AMAP_TILES = new Map();
function mercX(lng) { return (lng + 180) / 360; }
function mercY(lat) { const s = Math.sin(lat * Math.PI / 180); return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI); }
function invMercY(y) { return 180 / Math.PI * Math.atan(Math.sinh(Math.PI * (1 - 2 * y))); }
class MapView {
  constructor(canvas, onClick) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.markers = [];
    this.path = null;
    this.onClick = onClick;
    this.center = null;
    this.zOverride = null;
    this.ctrls = [];
    this.resize();
    window.addEventListener('resize', () => this.resize());
    const deviceXY = (e) => {
      const rect = canvas.getBoundingClientRect();
      return {
        x: (e.clientX - rect.left) * (canvas.width / rect.width),
        y: (e.clientY - rect.top) * (canvas.height / rect.height)
      };
    };
    canvas.addEventListener('click', (e) => {
      const { x, y } = deviceXY(e);
      const hit = this.ctrls.find(c => x >= c.x && x <= c.x + c.w && y >= c.y && y <= c.y + c.h);
      if (hit) { hit.act(); return; }
      if (this.onClick) this.onClick(this.unproject(x, y));
    });
    canvas.addEventListener('wheel', (e) => {
      e.preventDefault();
      const { x, y } = deviceXY(e);
      this.zoomAt(x, y, e.deltaY < 0 ? 1 : -1);
    }, { passive: false });
  }
  resize() {
    const rect = this.canvas.getBoundingClientRect();
    if (!rect.width) return;
    this.canvas.width = rect.width * devicePixelRatio;
    this.canvas.height = rect.height * devicePixelRatio;
    this.computeView();
    this.draw();
  }
  computeView() {
    const dpr = devicePixelRatio || 1;
    this.dpw = dpr / (dpr >= 1.5 ? 2 : 1);
    this.tileDev = 256 * this.dpw;
    const w = this.canvas.width, h = this.canvas.height;
    if (this.zOverride != null) {
      this.z = Math.max(3, Math.min(18, this.zOverride));
    } else {
      const fx = (BBOX.maxLng - BBOX.minLng) / 360;
      const fy = mercY(BBOX.minLat) - mercY(BBOX.maxLat);
      const world = Math.min(w / (this.dpw * fx), h / (this.dpw * fy));
      this.z = Math.max(3, Math.min(17, Math.floor(Math.log2(world / 256))));
    }
    this.W = 256 * Math.pow(2, this.z);
    const c = this.center || { lat: (BBOX.minLat + BBOX.maxLat) / 2, lng: (BBOX.minLng + BBOX.maxLng) / 2 };
    this.cx = mercX(c.lng) * this.W;
    this.cy = mercY(c.lat) * this.W;
    this.left = this.cx - w / 2 / this.dpw;
    this.top = this.cy - h / 2 / this.dpw;
  }
  focus(lat, lng, z) {
    this.center = { lat, lng };
    this.zOverride = z == null ? 16 : z;
    this.computeView();
    this.draw();
  }
  resetView() {
    this.center = null;
    this.zOverride = null;
    this.computeView();
    this.draw();
  }
  zoomAt(x, y, dz) {
    const before = this.unproject(x, y);
    const c = this.center || { lat: (BBOX.minLat + BBOX.maxLat) / 2, lng: (BBOX.minLng + BBOX.maxLng) / 2 };
    this.zOverride = Math.max(3, Math.min(18, this.z + dz));
    this.center = { lat: c.lat, lng: c.lng };
    this.computeView();
    const after = this.unproject(x, y);
    this.center = { lat: c.lat + (before.lat - after.lat), lng: c.lng + (before.lng - after.lng) };
    this.computeView();
    this.draw();
  }
  project(lat, lng) {
    return {
      x: this.canvas.width / 2 + (mercX(lng) * this.W - this.cx) * this.dpw,
      y: this.canvas.height / 2 + (mercY(lat) * this.W - this.cy) * this.dpw
    };
  }
  unproject(x, y) {
    const wx = (x - this.canvas.width / 2) / this.dpw + this.cx;
    const wy = (y - this.canvas.height / 2) / this.dpw + this.cy;
    return { lat: +invMercY(wy / this.W).toFixed(5), lng: +(wx / this.W * 360 - 180).toFixed(5) };
  }
  tile(x, y) {
    const n = Math.pow(2, this.z);
    x = ((x % n) + n) % n;
    if (y < 0 || y >= n) return null;
    const key = `${this.z}/${x}/${y}`;
    let t = AMAP_TILES.get(key);
    if (!t) {
      t = { ok: false, failed: false, img: new Image() };
      AMAP_TILES.set(key, t);
      t.img.crossOrigin = 'anonymous';
      t.img.onload = () => { t.ok = true; this.draw(); };
      t.img.onerror = () => { t.failed = true; };
      t.img.src = `https://webrd0${(x + y) % 4 + 1}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=${x}&y=${y}&z=${this.z}`;
    }
    return t;
  }
  setMarkers(markers) { this.markers = markers; this.draw(); }
  setPath(points, color) { this.path = { points, color: color || '#60a5fa' }; this.draw(); }
  draw() {
    const ctx = this.ctx, w = this.canvas.width, h = this.canvas.height;
    if (!w || !h || !this.W) return;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = '#eef2f6'; ctx.fillRect(0, 0, w, h);
    ctx.strokeStyle = 'rgba(148,163,184,.12)'; ctx.lineWidth = 1;
    for (let i = 1; i < 8; i++) {
      ctx.beginPath(); ctx.moveTo(w / 8 * i, 0); ctx.lineTo(w / 8 * i, h); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(0, h / 8 * i); ctx.lineTo(w, h / 8 * i); ctx.stroke();
    }
    const x0 = Math.floor(this.left / 256), x1 = Math.floor((this.left + w / this.dpw) / 256);
    const y0 = Math.floor(this.top / 256), y1 = Math.floor((this.top + h / this.dpw) / 256);
    for (let tx = x0; tx <= x1; tx++) {
      for (let ty = y0; ty <= y1; ty++) {
        const t = this.tile(tx, ty);
        if (t && t.ok) ctx.drawImage(t.img, (tx * 256 - this.left) * this.dpw, (ty * 256 - this.top) * this.dpw, this.tileDev, this.tileDev);
      }
    }
    ctx.fillStyle = 'rgba(100,116,139,.6)';
    PRESETS.forEach(p => {
      const { x, y } = this.project(p.lat, p.lng);
      ctx.beginPath(); ctx.arc(x, y, 2.5 * devicePixelRatio, 0, 7); ctx.fill();
    });
    if (this.path && this.path.points.length > 1) {
      ctx.strokeStyle = this.path.color;
      ctx.lineWidth = 2.5 * devicePixelRatio;
      ctx.lineJoin = 'round';
      ctx.beginPath();
      this.path.points.forEach((p, i) => {
        const { x, y } = this.project(p.lat, p.lng);
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      });
      ctx.stroke();
    }
    this.markers.forEach(m => {
      const { x, y } = this.project(m.lat, m.lng);
      ctx.beginPath(); ctx.arc(x, y, 7 * devicePixelRatio, 0, 7);
      ctx.fillStyle = m.color; ctx.fill();
      ctx.lineWidth = 2 * devicePixelRatio; ctx.strokeStyle = '#ffffff'; ctx.stroke();
      if (m.label) {
        ctx.font = `bold ${11 * devicePixelRatio}px sans-serif`;
        ctx.lineWidth = 3 * devicePixelRatio; ctx.strokeStyle = 'rgba(255,255,255,.85)';
        ctx.strokeText(m.label, x + 9 * devicePixelRatio, y + 4 * devicePixelRatio);
        ctx.fillStyle = m.color;
        ctx.fillText(m.label, x + 9 * devicePixelRatio, y + 4 * devicePixelRatio);
      }
    });
    const s = 26 * devicePixelRatio, gap = 6 * devicePixelRatio, mx = 8 * devicePixelRatio;
    this.ctrls = [];
    [['+', () => this.zoomAt(w / 2, h / 2, 1)], ['−', () => this.zoomAt(w / 2, h / 2, -1)], ['⌂', () => this.resetView()]].forEach(([label, act], i) => {
      const x = w - mx - s, y = mx + i * (s + gap);
      ctx.beginPath();
      if (ctx.roundRect) ctx.roundRect(x, y, s, s, 6 * devicePixelRatio); else ctx.rect(x, y, s, s);
      ctx.fillStyle = 'rgba(255,255,255,.92)'; ctx.fill();
      ctx.lineWidth = devicePixelRatio; ctx.strokeStyle = 'rgba(51,65,85,.25)'; ctx.stroke();
      ctx.fillStyle = '#334155'; ctx.font = `bold ${14 * devicePixelRatio}px sans-serif`;
      ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillText(label, x + s / 2, y + s / 2 + devicePixelRatio);
      ctx.textAlign = 'left'; ctx.textBaseline = 'alphabetic';
      this.ctrls.push({ x, y, w: s, h: s, act });
    });
    ctx.font = `${10 * devicePixelRatio}px sans-serif`;
    ctx.textAlign = 'right';
    ctx.fillStyle = 'rgba(51,65,85,.7)';
    ctx.fillText('© 高德地图', w - 6 * devicePixelRatio, h - 6 * devicePixelRatio);
    ctx.textAlign = 'left';
  }
}

// 逆地理编码：Photon（OSM，免费免 key，街道/POI 级）→ BigDataCloud（区级兜底）→ 本地地标 → 坐标文案。
// 两源均间歇可达，失败不缓存以便下次重试；成功缓存 + 并发去重。
const RG_CACHE = new Map();
const RG_INFLIGHT = new Map();
async function rgFetch(url) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), 6000);
  try {
    const r = await fetch(url, { signal: ctl.signal });
    return r.ok ? await r.json() : null;
  } catch { return null; } finally { clearTimeout(t); }
}
function reverseGeocode(lat, lng) {
  const key = lat.toFixed(4) + ',' + lng.toFixed(4);
  if (RG_CACHE.has(key)) return Promise.resolve(RG_CACHE.get(key));
  if (RG_INFLIGHT.has(key)) return RG_INFLIGHT.get(key);
  const p = (async () => {
    const j = await rgFetch(`https://photon.komoot.io/reverse?lat=${lat}&lon=${lng}&limit=1`);
    const a = j && (j.features || [])[0];
    if (a) {
      const pr = a.properties || {};
      const road = (pr.street || '') + (pr.housenumber ? pr.housenumber + '号' : '');
      const name = pr.name || road || pr.locality || pr.district || pr.city;
      if (name) {
        const v = { name, detail: [pr.city, pr.district, pr.locality, road].filter(Boolean).join(' ') || name };
        RG_CACHE.set(key, v);
        return v;
      }
    }
    const b = await rgFetch(`https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=${lat}&longitude=${lng}&localityLanguage=zh`);
    if (b) {
      const loc = (b.locality || '').replace(/區/g, '区');
      const name = [b.city, loc].filter(Boolean).join(' ') || b.principalSubdivision || b.countryName;
      if (name) {
        const v = { name, detail: name, coarse: true };
        RG_CACHE.set(key, v);
        return v;
      }
    }
    return null;
  })().finally(() => RG_INFLIGHT.delete(key));
  RG_INFLIGHT.set(key, p);
  return p;
}
async function placeName(lat, lng) {
  const rg = await reverseGeocode(lat, lng);
  if (rg) return rg.coarse ? rg.name + '附近' : rg.name;
  let best = null, bd = 1e9;
  PRESETS.forEach(p => { const d = (p.lat - lat) ** 2 + (p.lng - lng) ** 2; if (d < bd) { bd = d; best = p; } });
  if (best && bd < 0.0004) return best.name + '附近';
  return `地图选点(${lat.toFixed(4)},${lng.toFixed(4)})`;
}

function fillPresetSelect(select) {
  select.innerHTML = '<option value="">自定义坐标…</option>' +
    PRESETS.map((p, i) => `<option value="${i}">${p.name}</option>`).join('');
}