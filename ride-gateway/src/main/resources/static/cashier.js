/*
 * ride-pro 乘客端收银台（payment-pro 开放能力由 ride 渲染）。
 *
 * 零外部依赖：二维码由本文件内的纯 JS QR 编码器（byte 模式 / ECC-L / version 1..6）
 * 生成内联 SVG，不引任何 CDN、不引第三方库、不发外部请求。
 * 所有资金请求只打 ride 网关 /api/cashier/**，pp appId/appSecret 永不出现在前端。
 * 金额一律「分」，展示经 yuan() 转元。
 */
(function () {
  'use strict';

  // ==================== 纯 JS QR 编码器 ====================
  const QR = (function () {
    const EXP = new Uint8Array(512), LOG = new Uint8Array(256);
    (function () {
      let x = 1;
      for (let i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 0x100) x ^= 0x11d; }
      for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    })();
    const mul = (a, b) => (a === 0 || b === 0) ? 0 : EXP[LOG[a] + LOG[b]];

    // ECC-L，version 1..6：数据码字 / 每块纠错码字 / 块数
    const DATA_CW = [19, 34, 55, 80, 108, 136];
    const ECC_CW = [7, 10, 15, 20, 26, 18];
    const BLOCKS = [1, 1, 1, 1, 1, 2];

    function divisor(degree) {
      const r = new Array(degree).fill(0);
      r[degree - 1] = 1;
      let root = 1;
      for (let i = 0; i < degree; i++) {
        for (let j = 0; j < degree; j++) {
          r[j] = mul(r[j], root);
          if (j + 1 < degree) r[j] ^= r[j + 1];
        }
        root = mul(root, 2);
      }
      return r;
    }

    function remainder(data, div) {
      const r = new Array(div.length).fill(0);
      for (const b of data) {
        const factor = b ^ r[0];
        r.shift(); r.push(0);
        for (let j = 0; j < div.length; j++) r[j] ^= mul(div[j], factor);
      }
      return r;
    }

    function encodeBytes(text) {
      const bytes = [];
      for (const ch of unescape(encodeURIComponent(text))) bytes.push(ch.charCodeAt(0));
      let ver = -1;
      for (let v = 0; v < 6; v++) if (bytes.length + 2 <= DATA_CW[v]) { ver = v; break; }
      if (ver < 0) throw new Error('二维码内容过长（最多 134 字节）');
      const capacity = DATA_CW[ver];
      const bits = [];
      const push = (val, len) => { for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); };
      push(4, 4);                 // byte 模式
      push(bytes.length, 8);      // 字符计数（v1..9 为 8 bit）
      bytes.forEach(b => push(b, 8));
      const cap = capacity * 8;
      for (let i = 0; i < 4 && bits.length < cap; i++) bits.push(0);
      while (bits.length % 8 !== 0) bits.push(0);
      const data = [];
      for (let i = 0; i < bits.length; i += 8) {
        let b = 0;
        for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
        data.push(b);
      }
      for (let i = 0; data.length < capacity; i++) data.push(i % 2 === 0 ? 0xec : 0x11);

      // 分块 + 交织
      const nb = BLOCKS[ver], eccLen = ECC_CW[ver];
      const shortLen = Math.floor(capacity / nb), longCount = capacity % nb;
      const div = divisor(eccLen), dBlocks = [], eBlocks = [];
      let off = 0;
      for (let b = 0; b < nb; b++) {
        const len = shortLen + (b >= nb - longCount ? 1 : 0);
        const chunk = data.slice(off, off + len);
        off += len;
        dBlocks.push(chunk);
        eBlocks.push(remainder(chunk, div));
      }
      const out = [];
      const maxLen = Math.max.apply(null, dBlocks.map(b => b.length));
      for (let i = 0; i < maxLen; i++) dBlocks.forEach(b => { if (i < b.length) out.push(b[i]); });
      for (let i = 0; i < eccLen; i++) eBlocks.forEach(b => out.push(b[i]));
      return { version: ver + 1, codewords: out };
    }

    const MASKS = [
      (x, y) => (x + y) % 2 === 0,
      (x, y) => y % 2 === 0,
      (x, y) => x % 3 === 0,
      (x, y) => (x + y) % 3 === 0,
      (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0,
      (x, y) => (x * y) % 2 + (x * y) % 3 === 0,
      (x, y) => ((x * y) % 2 + (x * y) % 3) % 2 === 0,
      (x, y) => ((x + y) % 2 + (x * y) % 3) % 2 === 0
    ];

    function penalty(m, size) {
      let p = 0;
      for (let k = 0; k < 2; k++) {
        for (let a = 0; a < size; a++) {
          let run = 1;
          for (let b = 1; b < size; b++) {
            const cur = k ? m[b][a] : m[a][b];
            const prev = k ? m[b - 1][a] : m[a][b - 1];
            if (cur === prev) { run++; if (run === 5) p += 3; else if (run > 5) p++; } else run = 1;
          }
        }
      }
      for (let y = 0; y < size - 1; y++) {
        for (let x = 0; x < size - 1; x++) {
          const c = m[y][x];
          if (c === m[y][x + 1] && c === m[y + 1][x] && c === m[y + 1][x + 1]) p += 3;
        }
      }
      const p1 = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0], p2 = [0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1];
      for (let k = 0; k < 2; k++) {
        for (let a = 0; a < size; a++) {
          for (let b = 0; b + 11 <= size; b++) {
            let ok1 = true, ok2 = true;
            for (let i = 0; i < 11; i++) {
              const v = k ? m[b + i][a] : m[a][b + i];
              if (v !== p1[i]) ok1 = false;
              if (v !== p2[i]) ok2 = false;
            }
            if (ok1) p += 40;
            if (ok2) p += 40;
          }
        }
      }
      let dark = 0;
      for (const row of m) for (const v of row) dark += v;
      p += Math.floor(Math.abs(dark * 100 / (size * size) - 50) / 5) * 10;
      return p;
    }

    function matrix(text) {
      const enc = encodeBytes(text);
      const size = 17 + 4 * enc.version;
      const m = [], fn = [];
      for (let i = 0; i < size; i++) { m.push(new Array(size).fill(0)); fn.push(new Array(size).fill(false)); }
      const set = (x, y, v) => { m[y][x] = v ? 1 : 0; fn[y][x] = true; };

      function finder(cx, cy) {
        for (let dy = -4; dy <= 4; dy++) {
          for (let dx = -4; dx <= 4; dx++) {
            const x = cx + dx, y = cy + dy;
            if (x < 0 || x >= size || y < 0 || y >= size) continue;
            const d = Math.max(Math.abs(dx), Math.abs(dy));
            set(x, y, d !== 2 && d !== 4);
          }
        }
      }
      finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
      for (let i = 8; i < size - 8; i++) { set(i, 6, i % 2 === 0); set(6, i, i % 2 === 0); }
      if (enc.version >= 2) {
        const c = size - 7;
        for (let dy = -2; dy <= 2; dy++) {
          for (let dx = -2; dx <= 2; dx++) set(c + dx, c + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
        }
      }
      // 预留格式信息区
      for (let i = 0; i <= 8; i++) { if (i !== 6) { set(8, i, 0); set(i, 8, 0); } }
      for (let i = 0; i < 8; i++) set(size - 1 - i, 8, 0);
      for (let i = 8; i < 15; i++) set(8, size - 15 + i, 0);
      set(8, size - 8, 1); // 固定深色模块

      // 数据蛇形放置
      let bit = 0;
      const total = enc.codewords.length * 8;
      for (let right = size - 1; right >= 1; right -= 2) {
        if (right === 6) right = 5;
        for (let vert = 0; vert < size; vert++) {
          for (let j = 0; j < 2; j++) {
            const x = right - j;
            const upward = ((right + 1) & 2) === 0;
            const y = upward ? size - 1 - vert : vert;
            if (!fn[y][x] && bit < total) {
              m[y][x] = (enc.codewords[bit >>> 3] >>> (7 - (bit & 7))) & 1;
              bit++;
            }
          }
        }
      }

      function format(target, mask) {
        const data = (1 << 3) | mask; // ECC-L formatBits = 01
        let rem = data;
        for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        const bits = ((data << 10) | rem) ^ 0x5412;
        const gb = i => (bits >>> i) & 1;
        for (let i = 0; i <= 5; i++) target[i][8] = gb(i);
        target[7][8] = gb(6); target[8][8] = gb(7); target[8][7] = gb(8);
        for (let i = 9; i < 15; i++) target[8][14 - i] = gb(i);
        for (let i = 0; i < 8; i++) target[8][size - 1 - i] = gb(i);
        for (let i = 8; i < 15; i++) target[size - 15 + i][8] = gb(i);
        target[size - 8][8] = 1;
      }

      let best = null, bestScore = Infinity;
      for (let mask = 0; mask < 8; mask++) {
        const cand = m.map(r => r.slice());
        for (let y = 0; y < size; y++) {
          for (let x = 0; x < size; x++) if (!fn[y][x] && MASKS[mask](x, y)) cand[y][x] ^= 1;
        }
        format(cand, mask);
        const score = penalty(cand, size);
        if (score < bestScore) { bestScore = score; best = cand; }
      }
      return best;
    }

    /** 生成内联 SVG（含 4 模块静区），纯本地计算，零网络请求。 */
    function svg(text, px) {
      const m = matrix(text), n = m.length, quiet = 4, dim = n + quiet * 2;
      let path = '';
      for (let y = 0; y < n; y++) {
        for (let x = 0; x < n; x++) if (m[y][x]) path += 'M' + (x + quiet) + ' ' + (y + quiet) + 'h1v1h-1z';
      }
      return '<svg xmlns="http://www.w3.org/2000/svg" width="' + px + '" height="' + px +
        '" viewBox="0 0 ' + dim + ' ' + dim + '" shape-rendering="crispEdges" role="img" aria-label="付款二维码">' +
        '<rect width="' + dim + '" height="' + dim + '" fill="#ffffff"/>' +
        '<path d="' + path + '" fill="#1f2430"/></svg>';
    }

    return { svg: svg };
  })();

  // ==================== 纯 JS Code128 条形码编码器（付款码页用，零依赖） ====================
  const BAR = (function () {
    // 107 个符号的条/空宽度（偶数位=条，奇数位=空）；每符号 11 模块，STOP(106) 为 13。
    const P = ['212222', '222122', '222221', '121223', '121322', '131222', '122213', '122312', '132212',
      '221213', '221312', '231212', '112232', '122132', '122231', '113222', '123122', '123221', '223211',
      '221132', '221231', '213212', '223112', '312131', '311222', '321122', '321221', '312212', '322112',
      '322211', '212123', '212321', '232121', '111323', '131123', '131321', '112313', '132113', '132311',
      '211313', '231113', '231311', '112133', '112331', '132131', '113123', '113321', '133121', '313121',
      '211331', '231131', '213113', '213311', '213131', '311123', '311321', '331121', '312131', '312311',
      '332111', '314111', '221411', '431111', '111224', '111422', '121124', '121421', '141122', '141221',
      '112214', '112412', '122114', '122411', '142112', '142211', '241211', '221114', '413111', '241112',
      '134111', '111242', '121142', '121241', '114212', '124112', '124211', '411212', '421112', '421211',
      '212141', '214121', '412121', '111143', '111341', '131141', '114113', '114311', '411113', '411311',
      '113141', '114131', '311141', '411131', '211412', '211214', '211232', '2331112'];
    const VALID = P.length === 107 && P.every((s, i) => {
      let n = 0; for (const ch of s) n += +ch;
      return n === (i === 106 ? 13 : 11);
    });

    /** 数字且偶数长度走子集 C（每符号 2 位数字），否则子集 B。含模 103 校验位。 */
    function values(text) {
      const useC = /^\d+$/.test(text) && text.length % 2 === 0;
      const vals = [useC ? 105 : 104];
      if (useC) { for (let i = 0; i < text.length; i += 2) vals.push(+text.substr(i, 2)); }
      else { for (const ch of text) { const c = ch.charCodeAt(0); vals.push(c >= 32 && c <= 126 ? c - 32 : 0); } }
      let sum = vals[0];
      for (let i = 1; i < vals.length; i++) sum += vals[i] * i;
      vals.push(sum % 103, 106);
      return vals;
    }

    /** 内联 SVG 条形码（左右 10 模块静区），高度由 CSS 拉伸。 */
    function svg(text) {
      if (!VALID || !text) return '';
      const vals = values(String(text));
      const bars = [];
      let x = 0;
      for (const v of vals) {
        const p = P[v];
        for (let i = 0; i < p.length; i++) {
          const w = +p[i];
          if (i % 2 === 0) bars.push('M' + (x + 10) + ' 0h' + w + 'v1h-' + w + 'z');
          x += w;
        }
      }
      const dim = x + 20;
      return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + dim + ' 1" preserveAspectRatio="none" ' +
        'shape-rendering="crispEdges" role="img" aria-label="付款条形码">' +
        '<rect width="' + dim + '" height="1" fill="#ffffff"/><path d="' + bars.join('') + '" fill="#1a1a1a"/></svg>';
    }
    return { svg: svg, valid: VALID };
  })();

  // ==================== 样式：外链 cashier.css（支付宝设计语言，零 CDN） ====================
  function ensureCss() {
    if (document.getElementById('csStyle')) return;
    const link = document.createElement('link');
    link.id = 'csStyle';
    link.rel = 'stylesheet';
    link.href = 'cashier.css';
    document.head.appendChild(link);
  }

  // ==================== 内联 SVG 图标（不引任何图标库） ====================
  const st = 'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"';
  const SVG = {
    back: '<svg viewBox="0 0 24 24" ' + st + '><path d="M15 4.5L7.5 12l7.5 7.5"/></svg>',
    close: '<svg viewBox="0 0 24 24" ' + st + '><path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/></svg>',
    check: '<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.6 4.6L19 7.4"/></svg>',
    down: '<svg viewBox="0 0 24 24" ' + st + '><path d="M6 9.5l6 6 6-6"/></svg>',
    right: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 5l7 7-7 7"/></svg>',
    ok: '<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.6 4.6L19 7.4"/></svg>',
    fail: '<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.6" stroke-linecap="round"><path d="M7.5 7.5l9 9M16.5 7.5l-9 9"/></svg>',
    wait: '<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round"><path d="M12 6.5V12l3.5 2.2"/><circle cx="12" cy="12" r="8"/></svg>',
    warn: '<svg viewBox="0 0 24 24" fill="none" stroke="#FF8F1F" stroke-width="2" stroke-linecap="round"><path d="M12 4.5l8.5 15H3.5z"/><path d="M12 10v4M12 16.6v.4"/></svg>',
    fingerprint: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">' +
      '<path d="M4.6 9.2a7.4 7.4 0 0114.8 0v2.2"/><path d="M7.6 9.6a4.4 4.4 0 018.8 0v3.6c0 1.7-.3 3.3-.9 4.9"/>' +
      '<path d="M10.6 10a1.4 1.4 0 012.8 0v3.2c0 2-.4 4-1.2 5.9"/><path d="M7.6 13.2v1.4c0 2-.5 4-1.5 5.8"/>' +
      '<path d="M4.6 13.6v1c0 1.6-.3 3.2-.9 4.7"/><path d="M19.4 13.6v1c0 1.2-.1 2.4-.4 3.6"/></svg>',
    album: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">' +
      '<rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M3.5 16.5l4.5-4 3.5 3 3-2.5 6 5"/><circle cx="8.4" cy="9.4" r="1.3"/></svg>',
    torch: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">' +
      '<path d="M9 3h6v3l-1.6 2.2V21h-2.8V8.2L9 6z"/><path d="M12 11.5v3"/></svg>',
    codeWhite: '<svg viewBox="0 0 24 24" fill="#fff"><rect x="3" y="4" width="2" height="16" rx=".6"/><rect x="6.6" y="4" width="1.2" height="16"/>' +
      '<rect x="9.2" y="4" width="2.4" height="16" rx=".6"/><rect x="13.2" y="4" width="1.2" height="16"/><rect x="15.8" y="4" width="2" height="16" rx=".6"/><rect x="19.4" y="4" width="1.6" height="16"/></svg>'
  };
  const METHOD_SVG = {
    BALANCE: '<svg viewBox="0 0 24 24"><rect x="2" y="5" width="20" height="15" rx="3.4" fill="#1677FF"/>' +
      '<path d="M2 8.6h20v2.2H2z" fill="#fff" opacity=".22"/><rect x="13.6" y="10.6" width="8.4" height="5.2" rx="2.6" fill="#fff"/>' +
      '<circle cx="17.4" cy="13.2" r="1.5" fill="#1677FF"/></svg>',
    CARD: '<svg viewBox="0 0 24 24" fill="#3D6FD6"><path d="M12 2.6l9.2 5.1v2.1H2.8V7.7z"/>' +
      '<rect x="4.8" y="11.2" width="2.6" height="6.6" rx=".6"/><rect x="10.7" y="11.2" width="2.6" height="6.6" rx=".6"/>' +
      '<rect x="16.6" y="11.2" width="2.6" height="6.6" rx=".6"/><rect x="2.8" y="18.8" width="18.4" height="2.4" rx="1.2"/></svg>',
    PAY_CODE: '<svg viewBox="0 0 24 24" fill="#1677FF"><rect x="3" y="4" width="2" height="16" rx=".6"/><rect x="6.6" y="4" width="1.2" height="16"/>' +
      '<rect x="9.2" y="4" width="2.4" height="16" rx=".6"/><rect x="13.2" y="4" width="1.2" height="16"/><rect x="15.8" y="4" width="2" height="16" rx=".6"/><rect x="19.4" y="4" width="1.6" height="16"/></svg>',
    SCAN: '<svg viewBox="0 0 24 24" fill="none" stroke="#1677FF" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">' +
      '<path d="M4 8.5V5.6A1.6 1.6 0 015.6 4h2.9M15.5 4h2.9A1.6 1.6 0 0120 5.6v2.9M20 15.5v2.9a1.6 1.6 0 01-1.6 1.6h-2.9M8.5 20H5.6A1.6 1.6 0 014 18.4v-2.9"/>' +
      '<path d="M4.6 12h14.8"/></svg>',
    QUICK_PAY: '<svg viewBox="0 0 24 24" fill="#FF8F1F"><path d="M13.4 2L4.6 13.6h5.6L9.2 22l9.6-12.2h-6z"/></svg>',
    NET_BANK: '<svg viewBox="0 0 24 24" fill="#5B6B8C"><circle cx="12" cy="12" r="9" opacity=".16"/>' +
      '<path d="M12 3a9 9 0 100 18 9 9 0 000-18zm0 1.9c1.3 0 2.6 2.6 2.6 7.1s-1.3 7.1-2.6 7.1S9.4 16.5 9.4 12 10.7 4.9 12 4.9z"/>' +
      '<path d="M3.4 9.4h17.2v1.7H3.4zM3.4 12.9h17.2v1.7H3.4z"/></svg>',
    CREDIT: '<svg viewBox="0 0 24 24"><rect x="2.4" y="5" width="19.2" height="14" rx="3" fill="#FF6A00" opacity=".16"/>' +
      '<rect x="2.4" y="5" width="19.2" height="14" rx="3" fill="none" stroke="#FF6A00" stroke-width="1.6"/>' +
      '<path d="M6.8 15.2h5.4M6.8 11.6h9.6" stroke="#FF6A00" stroke-width="1.6" stroke-linecap="round"/></svg>',
    COUPON: '<svg viewBox="0 0 24 24"><path d="M3 7.4A2.4 2.4 0 015.4 5h13.2A2.4 2.4 0 0121 7.4v2.2a2.4 2.4 0 000 4.8v2.2a2.4 2.4 0 01-2.4 2.4H5.4A2.4 2.4 0 013 16.6v-2.2a2.4 2.4 0 000-4.8z" fill="#FF4D4F" opacity=".18"/>' +
      '<path d="M9.4 8.6v6.8M14.6 8.6v6.8" stroke="#FF4D4F" stroke-width="1.6" stroke-linecap="round" stroke-dasharray="2.4 2"/></svg>',
    COMBO: '<svg viewBox="0 0 24 24" fill="#7A5AF8"><rect x="3" y="3" width="8" height="8" rx="2.2"/>' +
      '<rect x="13" y="3" width="8" height="8" rx="2.2" opacity=".5"/><rect x="3" y="13" width="8" height="8" rx="2.2" opacity=".5"/>' +
      '<rect x="13" y="13" width="8" height="8" rx="2.2"/></svg>'
  };
  const methodIcon = m => METHOD_SVG[m] || METHOD_SVG.COMBO;

  // ==================== 状态 ====================
  const S = {
    orderId: null, userId: null, data: null, method: 'BALANCE', cardNo: '',
    verify: null, code: null, timers: [], pending: null, smsSandbox: false,
    step: 'summary', busy: false, lastError: null,
    // 纯展示态（不参与任何资金语义）
    baseHtml: '', detailOpen: false, torch: false, codeRefreshing: false
  };
  let overlay = null;

  const el = id => document.getElementById(id);
  const money = f => (typeof yuan === 'function' ? yuan(f) : '¥' + ((f || 0) / 100).toFixed(2));
  const esc = s => (typeof escapeHtml === 'function' ? escapeHtml(String(s == null ? '' : s)) : String(s == null ? '' : s));
  const say = (m, k) => { if (typeof toast === 'function') toast(m, k); };
  const clearTimers = () => { S.timers.forEach(t => clearInterval(t)); S.timers = []; };
  const every = (fn, ms) => { const t = setInterval(fn, ms); S.timers.push(t); return t; };

  /** 千分位金额（仅展示用，不改变任何以「分」为单位的计算） */
  const groupMoney = f => {
    const s = ((f || 0) / 100).toFixed(2).split('.');
    return '¥' + s[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + s[1];
  };
  /** 金额拆成「¥ 小符号 + 大数字」两段，供支付宝式大字金额渲染 */
  const amountHtml = (f, cls) => {
    const s = ((f || 0) / 100).toFixed(2);
    return '<div class="' + cls + '"><span class="cur">¥</span><span class="num">' + s + '</span></div>';
  };
  const maskPhone = p => {
    const s = String(p || '');
    return s.length >= 7 ? s.slice(0, 3) + '****' + s.slice(-4) : (s || '绑定手机号');
  };
  const stripIds = html => String(html).replace(/\sid="[^"]*"/g, '');

  /** 6 格密码框：透明真实 input 覆盖格子上（保留原生键盘/粘贴），格子只负责显示黑点。 */
  function pwdBox(id, opts) {
    opts = opts || {};
    return '<div class="cs-pwdwrap"><div class="cs-pwdbox" data-box="' + id + '" data-filled="0">' +
      '<i></i><i></i><i></i><i></i><i></i><i></i></div>' +
      '<input id="' + id + '" class="cs-pwd-input" type="password" inputmode="numeric" maxlength="6" ' +
      'autocomplete="off" aria-label="' + esc(opts.label || '6 位数字密码') + '"></div>';
  }
  /** 绑定格子渲染；onFull 在用户输入满 6 位时触发一次（支付宝式自动提交）。 */
  function bindPwdBox(id, onFull) {
    const input = el(id);
    const box = overlay.querySelector('[data-box="' + id + '"]');
    if (!input || !box) return;
    const cells = box.querySelectorAll('i');
    let fired = false;
    const paint = () => {
      const v = String(input.value || '').replace(/\D/g, '').slice(0, 6);
      if (v !== input.value) input.value = v;
      box.dataset.filled = String(v.length);
      cells.forEach((c, i) => {
        c.className = (i < v.length ? 'on' : '') + (i === v.length ? ' cur' : '');
      });
      return v;
    };
    input.addEventListener('input', () => {
      const v = paint();
      if (v.length === 6 && !fired && typeof onFull === 'function') { fired = true; onFull(); }
    });
    input.addEventListener('focus', () => box.classList.add('focus'));
    input.addEventListener('blur', () => box.classList.remove('focus'));
    box.addEventListener('click', () => input.focus());
    paint();
    return paint;
  }

  function close() {
    clearTimers();
    if (overlay) { overlay.remove(); overlay = null; }
  }

  /**
   * 渲染外壳。opts.sheet=true 时渲染半屏上滑弹层（背景保留上一页快照，纯展示、不可点）；
   * opts.dark=true 时渲染深色页面（扫一扫取景）。所有 id 与业务绑定保持不变。
   */
  function shell(title, bodyHtml, footHtml, opts) {
    opts = opts || {};
    ensureCss();
    if (!overlay) {
      overlay = document.createElement('div');
      overlay.className = 'cs-overlay';
      document.body.appendChild(overlay);
    }
    const head = '<div class="' + (opts.sheet ? 'cs-sheet-head' : 'cs-nav') + '">' +
      '<button class="cs-icon-btn" id="csBack" title="返回" aria-label="返回">' + SVG.back + '</button>' +
      '<div class="' + (opts.sheet ? 'cs-sheet-t' : 'cs-nav-t') + '">' + esc(title) + '</div>' +
      '<button class="cs-icon-btn" id="csClose" title="关闭" aria-label="关闭">' + SVG.close + '</button></div>';
    const inner = head + '<div class="' + (opts.sheet ? 'cs-sheet-body' : 'cs-body') + '" id="csBody">' + bodyHtml + '</div>' +
      (footHtml ? '<div class="' + (opts.sheet ? 'cs-sheet-foot' : 'cs-foot') + '" id="csFoot">' + footHtml + '</div>' : '');
    if (opts.sheet) {
      const base = S.baseHtml ? '<div class="cs-panel cs-base" aria-hidden="true">' + S.baseHtml + '</div>' : '';
      overlay.innerHTML = base + '<div class="cs-sheet" role="dialog" aria-modal="true" aria-label="' + esc(title) + '">' + inner + '</div>';
    } else {
      overlay.innerHTML = '<div class="cs-panel' + (opts.dark ? ' dark' : '') + '">' + inner + '</div>';
      S.baseHtml = stripIds(inner);
    }
    el('csClose').onclick = close;
    el('csBack').onclick = () => {
      if (S.step === 'summary' || S.step === 'result') close();
      else { clearTimers(); S.pending = null; renderSummary(); }
    };
  }

  // ==================== 入口 ====================
  async function open(orderId) {
    if (typeof passenger === 'undefined' || !passenger) return say('请先选择乘客身份', 'warn');
    clearTimers();
    S.orderId = orderId; S.userId = passenger.id; S.method = 'BALANCE'; S.cardNo = '';
    S.verify = null; S.code = null; S.pending = null; S.lastError = null; S.step = 'summary';
    S.baseHtml = ''; S.detailOpen = false; S.torch = false; S.codeRefreshing = false;
    shell('收银台', '<div class="cs-card"><div class="cs-skel line w45" style="margin:0 auto"></div>' +
      '<div class="cs-skel amt"></div><div class="cs-skel line w70" style="margin:0 auto"></div></div>' +
      '<div class="cs-sec">支付方式</div><div class="cs-card"><div class="cs-skel row"></div>' +
      '<div class="cs-skel row"></div><div class="cs-skel row"></div></div>');
    try {
      S.data = await api('GET', '/api/cashier/methods?userId=' + encodeURIComponent(S.userId) + '&orderId=' + encodeURIComponent(orderId));
      const bal = (S.data.methods || []).find(m => m.method === 'BALANCE');
      if (bal && bal.available === false) {
        const card = (S.data.methods || []).find(m => m.method === 'CARD' && m.available);
        S.method = card ? 'CARD' : 'BALANCE';
      }
      renderSummary();
    } catch (e) {
      S.lastError = e.message;
      shell('收银台', '<div class="cs-result"><div class="cs-result-ic fail">' + SVG.warn + '</div>' +
        '<div class="cs-result-t">收银台加载失败</div></div>' +
        '<div class="cs-err">' + esc(e.message) + '</div>',
        '<button class="cs-btn" id="csRetry">重试</button><button class="cs-btn ghost" id="csFallback">改用钱包快捷支付</button>');
      el('csRetry').onclick = () => open(orderId);
      el('csFallback').onclick = () => { close(); if (typeof doPay === 'function') doPay(); };
    }
  }

  // ==================== 视图 1：收银台主页 ====================
  function renderSummary() {
    S.step = 'summary';
    clearTimers();
    const d = S.data || {};
    const ro = d.rideOrder || {};
    const order = d.order || {};
    const amount = order.amount != null ? order.amount : (d.actualAmount || ro.actualFee || 0);
    const discount = d.discount || 0;
    const actual = d.actualAmount != null ? d.actualAmount : amount;
    const free = d.freeVerifyLimit || 0;
    const methods = d.methods || [];
    const tip = ro.tipFee || 0;

    // 订单信息（次要灰字）
    let head = '<div class="cs-order"><div class="cs-order-route">' +
      '<span class="dot"></span>' + esc(ro.originName || '起点') +
      '<span class="arrow">→</span><span class="dot end"></span>' + esc(ro.destName || '终点') + '</div>' +
      '<div class="cs-order-no">订单号 ' + esc(S.orderId || '') + ' · ' + esc(d.merchantName || order.merchantName || 'ride-pro 平台') + '</div></div>';

    // 金额卡：灰字标签 + 大字金额 + 可展开明细
    let rows = '<div class="cs-kv"><span class="k">订单金额</span><span class="v">' + money(amount) + '</span></div>' +
      (discount ? '<div class="cs-kv"><span class="k">优惠</span><span class="v cut">-' + money(discount) + '</span></div>' : '') +
      (tip ? '<div class="cs-kv"><span class="k">调度加价（归司机）</span><span class="v">' + money(tip) + '</span></div>' : '') +
      (ro.estimateFee ? '<div class="cs-kv"><span class="k">预估价</span><span class="v">' + money(ro.estimateFee) + '</span></div>' : '') +
      '<div class="cs-kv"><span class="k">收款方</span><span class="v">' + esc(d.merchantName || order.merchantName || 'ride-pro 平台') + '</span></div>' +
      '<div class="cs-kv"><span class="k">小额免密限额</span><span class="v' + (actual <= free ? ' hi' : '') + '">' +
      money(free) + (actual <= free ? '（本单免密）' : '') + '</span></div>';
    head += '<div class="cs-card cs-amount"><div class="cs-amount-label">支付金额</div>' +
      amountHtml(actual, 'cs-amount-num') +
      '<button class="cs-amount-toggle' + (S.detailOpen ? ' open' : '') + '" id="csDetailToggle" aria-expanded="' + (S.detailOpen ? 'true' : 'false') + '">' +
      (discount ? '已优惠 ' + money(discount) + ' · ' : '') + '明细' + SVG.down + '</button>' +
      '<div class="cs-amount-rows" id="csDetailRows"' + (S.detailOpen ? '' : ' hidden') + '>' + rows + '</div></div>';

    // 支付方式列表
    let list = '<div class="cs-sec">支付方式</div><div class="cs-card cs-methods">';
    methods.forEach(m => {
      const usable = m.available !== false && m.category !== 'RESERVED';
      const acc = (m.accounts || [])[0];
      const sub = m.method === 'BALANCE' && acc ? ('余额 ' + groupMoney(acc.balance)) : (m.hint || '');
      const reason = usable ? sub : (m.unavailableReason || m.hint || '暂不可用');
      const jump = m.method === 'PAY_CODE' || m.method === 'SCAN';
      const on = S.method === m.method && usable && !jump;
      list += '<div class="cs-m' + (usable ? '' : ' off') + (on ? ' on' : '') + '" data-m="' + esc(m.method) + '"' +
        ' role="button" tabindex="0" aria-pressed="' + (on ? 'true' : 'false') + '">' +
        '<span class="cs-m-ico">' + methodIcon(m.method) + '</span>' +
        '<span class="cs-m-main"><span class="cs-m-name">' + esc(m.name || m.method) +
        (m.category === 'RESERVED' ? '<span class="cs-m-tag">预留</span>' : '') + '</span>' +
        '<span class="cs-m-sub">' + esc(reason) + '</span></span>' +
        (jump ? '<span class="cs-go">' + SVG.right + '</span>' : '<span class="cs-radio">' + SVG.check + '</span>') + '</div>';
    });
    list += '</div>';
    if (S.method === 'CARD') {
      list += '<div class="cs-card cs-field"><div class="cs-label">银行卡号</div>' +
        '<input id="csCard" class="cs-input" inputmode="numeric" placeholder="6222 0202 0202 0202" value="' + esc(S.cardNo) + '">' +
        '<div class="cs-hint">卡号仅用于本次受理，回执只保留后四位。</div></div>';
    }
    const err = S.lastError ? '<div class="cs-err">' + esc(S.lastError) + '</div>' : '';
    const foot = '<div class="cs-links">' +
      '<button class="cs-link-btn" id="csShowCode">' + METHOD_SVG.PAY_CODE + '付款码</button><span class="sep"></span>' +
      '<button class="cs-link-btn" id="csScan">' + METHOD_SVG.SCAN + '扫一扫</button></div>' +
      '<button class="cs-btn" id="csPay">确认付款</button>' +
      '<div class="cs-agree">支付即代表同意<a class="cs-link">《支付服务协议》</a></div>';
    shell('收银台', head + list + err, foot);

    overlay.querySelectorAll('.cs-m').forEach(node => {
      node.onclick = () => {
        if (node.classList.contains('off')) return say('该支付方式暂不可用', 'warn');
        const m = node.dataset.m;
        if (m === 'PAY_CODE') return showCode();
        if (m === 'SCAN') return scanView();
        S.method = m;
        renderSummary();
      };
    });
    el('csDetailToggle').onclick = () => { S.detailOpen = !S.detailOpen; renderSummary(); };
    if (el('csCard')) el('csCard').oninput = e => { S.cardNo = e.target.value.replace(/\s/g, ''); };
    el('csShowCode').onclick = showCode;
    el('csScan').onclick = scanView;
    el('csPay').onclick = () => startPay('PAY');
  }

  // ==================== 支付编排 ====================
  function actualAmount() {
    const d = S.data || {};
    return d.actualAmount != null ? d.actualAmount : ((d.order || {}).amount || (d.rideOrder || {}).actualFee || 0);
  }

  function needVerify() {
    return actualAmount() > (S.data.freeVerifyLimit || 0);
  }

  /** 统一受理：需要验证则先进验证页（pending 记录受理动作），否则直接受理。 */
  function startPay(action, code) {
    S.pending = { action: action, code: code || null };
    S.lastError = null;
    if (S.method === 'CARD' && !S.cardNo) return say('请填写银行卡号', 'warn');
    if (needVerify() && !S.verify) return verifyView();
    submit();
  }

  async function submit() {
    if (S.busy) return;
    S.busy = true;
    const p = S.pending || { action: 'PAY' };
    const body = {
      userId: S.userId, orderId: S.orderId, method: S.method,
      cardNo: S.method === 'CARD' ? S.cardNo : null,
      verifyToken: S.verify ? S.verify.verifyToken : null
    };
    const path = p.action === 'SCAN' ? '/api/cashier/scan-pay' : '/api/cashier/pay';
    if (p.action === 'SCAN') body.code = p.code;
    processing('正在受理支付…');
    try {
      const res = await api('POST', path, body);
      S.busy = false;
      S.verify = null;
      await waitTerminal(res);
    } catch (e) {
      S.busy = false;
      const msg = e.message || '支付失败';
      if (e.status === 403 || /验证|口令|免密/.test(msg)) {
        S.verify = null;
        S.lastError = msg;
        say(msg, 'warn');
        return verifyView();
      }
      if (!e.status) return waitTerminal(null);
      S.lastError = msg;
      resultView('FAILED', msg);
    }
  }

  function processing(text) {
    S.step = 'processing';
    shell('支付处理中', '<div class="cs-loading"><div class="cs-spin"></div>' +
      '<p>' + esc(text) + '</p>' +
      '<p style="margin-top:6px;font-size:12px">BALANCE 为同步受理，通常首次即终态；请勿重复提交。</p></div>');
  }

  /** 轮询终态：ride 订单 PAID + pp 支付单 SUCCESS。处理中继续轮询，超时给明确提示。 */
  async function waitTerminal(res) {
    processing('正在确认支付结果…');
    let tries = 0, last = res;
    const tick = async () => {
      tries++;
      try {
        const r = await api('GET', '/api/cashier/result?userId=' + encodeURIComponent(S.userId) + '&orderId=' + encodeURIComponent(S.orderId));
        last = r;
        const pp = r.ppPaymentStatus || (last && last.ppStatus);
        if (r.orderStatus === 'PAID' || pp === 'SUCCESS') {
          clearTimers();
          return resultView('SUCCESS', null, r);
        }
        if (pp === 'FAILED' || r.status === 'FAILED') {
          clearTimers();
          return resultView('FAILED', r.lastError || '支付系统受理失败', r);
        }
      } catch (e) { /* 轮询期间的瞬时异常继续重试 */ }
      if (tries >= 20) {
        clearTimers();
        resultView('PROCESSING', '支付仍在确认中，可稍后在行程详情查看结果；请勿重复支付。', last);
      }
    };
    await tick();
    if (S.step === 'processing') every(tick, 1500);
  }

  // ==================== 视图 2：支付验证（半屏上滑弹层） ====================
  function verifyView() {
    S.step = 'verify';
    clearTimers();
    const amt = actualAmount();
    const free = S.data.freeVerifyLimit || 0;
    const type = S.vType || 'PASSWORD';
    const methods = S.data.methods || [];
    const cur = methods.find(m => m.method === S.method);
    const acc = cur && (cur.accounts || [])[0];
    const wayName = S.method === 'CARD' && S.cardNo
      ? ('银行卡（尾号 ' + S.cardNo.slice(-4) + '）')
      : (cur ? cur.name : S.method);
    const waySub = S.method === 'BALANCE' && acc ? ('可用余额 ' + groupMoney(acc.balance)) : '';

    let title = '请输入支付密码', inner = '';
    if (type === 'PASSWORD') {
      inner = pwdBox('csPwd', { label: '6 位数字支付密码' }) +
        '<div class="cs-pwd-row"><span class="cs-hint" style="margin:0">密码仅用于本次支付验证</span>' +
        '<span class="cs-link" id="csForgot">忘记密码?</span></div>';
    } else if (type === 'SMS') {
      title = '请输入短信验证码';
      inner = '<div class="cs-sms-row"><span class="cs-sms-phone">验证码将发送至 ' +
        esc(maskPhone(typeof passenger !== 'undefined' && passenger ? passenger.phone : '')) + '</span>' +
        '<button class="cs-sms-btn" id="csSmsSend">获取验证码</button></div>' +
        '<div style="height:14px"></div>' + pwdBox('csSms', { label: '6 位短信验证码' }) +
        '<div id="csSmsHint" class="cs-hint">验证码经短信网关下发，响应默认掩码；联调可勾选沙箱取回明文。</div>' +
        '<label class="cs-sandbox"><input type="checkbox" id="csSandbox"' + (S.smsSandbox ? ' checked' : '') + '>沙箱取码（仅联调）</label>';
    } else if (type === 'BIO') {
      title = '请验证指纹';
      inner = '<div class="cs-bio"><div class="cs-bio-ic">' + SVG.fingerprint + '</div>' +
        '<div class="cs-bio-t">请验证指纹</div>' +
        '<div class="cs-bio-s">点击下方按钮模拟端侧指纹采集（沙箱平台校验）</div></div>';
    } else {
      title = '小额免密支付';
      inner = amt <= free
        ? '<div class="cs-note">本单金额 ' + money(amt) + ' ≤ 免密限额 ' + money(free) + '，可免验证直接支付。</div>'
        : '<div class="cs-err">本单金额 ' + money(amt) + ' 已经超出免密限额 ' + money(free) + '，请改用密码 / 短信 / 指纹验证。</div>';
    }
    const err = S.lastError ? '<div class="cs-err" style="margin-top:12px">' + esc(S.lastError) + '</div>' : '';
    const chips = '<div class="cs-chips-cap">其他验证方式</div><div class="cs-chips" id="csTypes">' +
      [['PASSWORD', '支付密码'], ['SMS', '短信验证'], ['BIO', '指纹/面容'], ['SMALL_AMOUNT_FREE', '小额免密']]
        .map(t => '<button class="cs-chip' + (type === t[0] ? ' on' : '') + '" data-t="' + t[0] + '">' + t[1] + '</button>').join('') +
      '</div>';
    const amtHead = '<div class="cs-sheet-amt"><span class="cur">¥</span>' + (amt / 100).toFixed(2) + '</div>' +
      '<div class="cs-sheet-way"><b>' + esc(wayName) + '</b>' + (waySub ? ' · ' + esc(waySub) : '') + '</div>';
    let foot = '';
    if (type === 'BIO') foot = '<button class="cs-btn" id="csBio">模拟指纹验证</button>';
    else if (type === 'SMALL_AMOUNT_FREE') foot = '<button class="cs-btn" id="csFree"' + (amt <= free ? '' : ' disabled') + '>免密支付</button>';
    else foot = '<button class="cs-btn" id="csVerify">确认支付</button>';
    shell(title, amtHead + inner + err + chips, foot, { sheet: true });

    overlay.querySelectorAll('#csTypes button').forEach(b => {
      b.onclick = () => { S.vType = b.dataset.t; S.lastError = null; verifyView(); };
    });
    if (el('csForgot')) el('csForgot').onclick = passwordView;
    if (el('csVerify')) el('csVerify').onclick = doVerify;
    if (el('csSmsSend')) el('csSmsSend').onclick = sendSms;
    if (el('csSandbox')) el('csSandbox').onchange = e => { S.smsSandbox = e.target.checked; };
    if (el('csBio')) el('csBio').onclick = () => doVerify('BIO-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10));
    if (el('csFree')) el('csFree').onclick = () => { S.verify = null; submit(); };
    // 满 6 位自动提交（支付宝式）；同时禁用按钮避免重复受理
    const auto = () => { const b = el('csVerify'); if (b) b.disabled = true; doVerify(); };
    if (type === 'PASSWORD') { bindPwdBox('csPwd', auto); if (el('csPwd')) el('csPwd').focus(); }
    if (type === 'SMS') bindPwdBox('csSms', auto);
    if (S.lastError) {
      const box = overlay.querySelector('.cs-pwdbox');
      if (box) { box.classList.add('shake'); setTimeout(() => box.classList.remove('shake'), 320); }
    }
  }

  async function sendSms() {
    const btn = el('csSmsSend');
    btn.disabled = true;
    try {
      const r = await api('POST', '/api/cashier/sms', { userId: S.userId, orderId: S.orderId, sandbox: !!S.smsSandbox });
      const hint = el('csSmsHint');
      if (r.smsCode && r.smsCodeMasked !== true && /^\d{6}$/.test(r.smsCode)) {
        hint.className = 'cs-ok';
        hint.style.marginTop = '12px';
        hint.textContent = '沙箱验证码：' + r.smsCode + '（仅联调可见，已自动填入）';
        if (el('csSms')) {
          el('csSms').value = r.smsCode;
          const box = overlay.querySelector('[data-box="csSms"]');
          if (box) {
            box.dataset.filled = '6';
            box.querySelectorAll('i').forEach(c => { c.className = 'on'; });
          }
        }
      } else {
        hint.className = 'cs-note';
        hint.style.marginTop = '12px';
        hint.textContent = (r.notice || '验证码已下发至手机') + '（响应掩码，请查看手机短信）';
      }
      let left = 60;
      const t = every(() => {
        left--;
        if (btn) btn.textContent = left > 0 ? left + 's 后重发' : '获取验证码';
        if (left <= 0) { clearInterval(t); if (btn) btn.disabled = false; }
      }, 1000);
      btn.textContent = '60s 后重发';
    } catch (e) {
      btn.disabled = false;
      say(e.message, 'err');
    }
  }

  async function doVerify(bioToken) {
    const type = S.vType || 'PASSWORD';
    const body = { userId: S.userId, orderId: S.orderId, verifyType: type };
    if (type === 'PASSWORD') {
      const v = (el('csPwd') || {}).value || '';
      if (!/^\d{6}$/.test(v)) return say('请输入 6 位数字支付口令', 'warn');
      body.payPassword = v;
    } else if (type === 'SMS') {
      const v = (el('csSms') || {}).value || '';
      if (!/^\d{6}$/.test(v)) return say('请输入 6 位短信验证码', 'warn');
      body.smsCode = v;
    } else if (type === 'BIO') {
      body.bioToken = bioToken;
    }
    const btn = el('csVerify') || el('csBio');
    if (btn) { btn.disabled = true; btn.textContent = '验证中…'; }
    try {
      const r = await api('POST', '/api/cashier/verify', body);
      if (r.pass === true) {
        S.verify = r;
        S.lastError = null;
        say('验证通过', 'ok');
        return submit();
      }
      const ch = r.challengeType;
      if (ch === 'NEED_SET_PASSWORD') {
        S.lastError = r.reason || '尚未设置支付口令';
        return passwordView();
      }
      if (ch === 'LOCKED') {
        S.lastError = (r.reason || '口令已锁定') + (r.lockedUntil ? '，解锁时间 ' + new Date(r.lockedUntil).toLocaleTimeString() : '');
        verifyView();
        const b = el('csVerify');
        if (b) { b.disabled = true; b.textContent = '已锁定，请稍后再试'; }
        return;
      }
      S.lastError = (r.reason || '验证未通过') + (r.remainAttempts != null ? '（剩余可试 ' + r.remainAttempts + ' 次）' : '');
      verifyView();
    } catch (e) {
      S.lastError = e.message;
      verifyView();
    }
  }

  // ==================== 视图 3：设置支付密码（半屏弹层 + 6 格框） ====================
  function passwordView() {
    S.step = 'password';
    clearTimers();
    shell('设置支付密码', '<div class="cs-note">密码为 6 位数字，仅用于支付验证；服务端加盐哈希存储，不回显、不落本地。</div>' +
      '<div class="cs-pwd-cap">原密码（首次设置留空）</div>' + pwdBox('csOld', { label: '原支付密码' }) +
      '<div class="cs-pwd-cap">新密码</div>' + pwdBox('csNew', { label: '新支付密码' }) +
      '<div class="cs-pwd-cap">确认新密码</div>' + pwdBox('csNew2', { label: '确认新支付密码' }) +
      '<div style="height:6px"></div>',
      '<button class="cs-btn" id="csSetPwd">保存密码</button>', { sheet: true });
    bindPwdBox('csOld'); bindPwdBox('csNew'); bindPwdBox('csNew2');
    if (el('csNew')) el('csNew').focus();
    el('csSetPwd').onclick = async () => {
      const nw = el('csNew').value, nw2 = el('csNew2').value, old = el('csOld').value;
      if (!/^\d{6}$/.test(nw)) return say('新密码须为 6 位数字', 'warn');
      if (nw !== nw2) return say('两次输入不一致', 'warn');
      const btn = el('csSetPwd');
      btn.disabled = true;
      try {
        await api('POST', '/api/cashier/pay-password', { userId: S.userId, password: nw, oldPassword: old || null });
        say('支付密码已设置', 'ok');
        S.vType = 'PASSWORD';
        S.lastError = null;
        verifyView();
      } catch (e) {
        btn.disabled = false;
        say(e.message, 'err');
      }
    };
  }

  // ==================== 视图 4：付款码出示（被扫，条形码 + 二维码） ====================
  async function showCode() {
    S.step = 'code';
    clearTimers();
    shell('付款码', '<div class="cs-code-card"><div class="cs-code-top">' + SVG.codeWhite + '向商家出示付款码</div>' +
      '<div class="cs-code-inner"><div class="cs-skel" style="height:66px"></div>' +
      '<div class="cs-skel line w70" style="margin:12px auto"></div>' +
      '<div class="cs-skel" style="height:176px;width:176px;margin:14px auto 0"></div>' +
      '<div class="cs-ttl">正在生成付款码…</div></div></div>');
    try {
      const c = await api('POST', '/api/cashier/code', { userId: S.userId, orderId: S.orderId, type: 'USER_PRESENTED' });
      S.code = c;
      S.codeRefreshing = false;
      paintCode(c);
    } catch (e) {
      S.codeRefreshing = false;
      shell('付款码', '<div class="cs-result"><div class="cs-result-ic fail">' + SVG.warn + '</div>' +
        '<div class="cs-result-t">付款码生成失败</div></div><div class="cs-err">' + esc(e.message) + '</div>',
        '<button class="cs-btn" id="csCodeRetry">重试</button>');
      el('csCodeRetry').onclick = showCode;
    }
  }

  function paintCode(c) {
    const content = c.qrContent || c.code || '';
    let qr;
    try { qr = QR.svg(content, 176); } catch (e) { qr = '<div class="cs-err">二维码生成失败：' + esc(e.message) + '</div>'; }
    const raw = String(c.code || '');
    const bar = BAR.svg(raw);
    const grouped = c.displayCode || raw.replace(/(\d{4})(?=\d)/g, '$1 ');
    const total = Math.max(1, c.ttlSeconds || 60);
    shell('付款码', '<div class="cs-code-card">' +
      '<div class="cs-code-top">' + SVG.codeWhite + '向商家出示付款码</div>' +
      '<div class="cs-code-inner">' +
      (bar ? '<div class="cs-barcode">' + bar + '</div>' : '') +
      '<div class="cs-code-digits">' + esc(grouped) + '</div>' +
      '<div class="cs-code-split">二维码</div>' +
      '<div class="cs-qrwrap">' + qr + '</div>' +
      '<div class="cs-ttl" id="csTtl">有效期 --</div>' +
      '</div><div class="cs-progress"><i id="csProg"></i></div></div>' +
      '<div class="cs-card">' +
      '<div class="cs-kv"><span class="k">待付金额</span><span class="v">' + money(c.amount || actualAmount()) + '</span></div>' +
      '<div class="cs-kv"><span class="k">订单</span><span class="v">' + esc(S.orderId) + '</span></div>' +
      '<div class="cs-kv"><span class="k">状态</span><span class="v hi" id="csCodeState">' + esc(c.status || 'ACTIVE') + '</span></div>' +
      '</div><div class="cs-note">向司机/商户出示此码，对方扫码后自动扣款；码一次性核销，每分钟自动刷新。</div>',
      '<button class="cs-btn" id="csCodeRefresh">刷新付款码</button>' +
      '<div class="cs-links" style="margin:10px 0 0"><button class="cs-link-btn" id="csScanFromCode">' +
      METHOD_SVG.SCAN + '扫一扫</button></div>');
    el('csCodeRefresh').onclick = showCode;
    el('csScanFromCode').onclick = scanView;

    const deadline = c.expireAt || (Date.now() + total * 1000);
    let firstPaint = true;
    const paintTtl = () => {
      const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
      const n = el('csTtl'), p = el('csProg');
      if (n) n.textContent = left > 0 ? ('付款码每分钟自动刷新 · 剩余 ' + left + ' 秒') : '正在刷新付款码…';
      if (p) p.style.width = Math.max(0, Math.min(100, left / total * 100)) + '%';
      // 首帧不自动刷新（此时定时器尚未注册完），到期后由后续 tick 触发换新码
      if (left <= 0 && !firstPaint && S.step === 'code' && !S.codeRefreshing) {
        S.codeRefreshing = true; clearTimers(); showCode();
      }
    };
    paintTtl();
    firstPaint = false;
    every(paintTtl, 1000);
    every(async () => {
      try {
        const q = await api('GET', '/api/cashier/code/' + encodeURIComponent(c.code));
        const st = el('csCodeState');
        if (st) st.textContent = q.status;
        if (q.status === 'CONSUMED') { clearTimers(); await waitTerminal(null); }
        else if (q.status === 'EXPIRED') {
          const n = el('csTtl');
          if (n) n.textContent = '付款码已过期，正在刷新…';
          if (S.step === 'code' && !S.codeRefreshing) { S.codeRefreshing = true; clearTimers(); showCode(); }
        }
      } catch (e) { /* 轮询瞬时异常忽略 */ }
    }, 2000);
  }

  // ==================== 视图 5：扫一扫（主扫，深色取景框） ====================
  function scanView() {
    S.step = 'scan';
    clearTimers();
    shell('扫一扫', '<div class="cs-scan">' +
      '<div class="cs-scan-stage" id="csStage"><i class="c tl"></i><i class="c tr"></i><i class="c bl"></i><i class="c br"></i>' +
      '<div class="cs-scan-line"></div></div>' +
      '<div class="cs-scan-tip">将二维码放入框内，即可自动扫描</div>' +
      '<div class="cs-scan-actions">' +
      '<button class="cs-scan-act" id="csAlbum"><span class="ic">' + SVG.album + '</span>相册</button>' +
      '<button class="cs-scan-act' + (S.torch ? ' on' : '') + '" id="csTorch"><span class="ic">' + SVG.torch + '</span>手电筒</button>' +
      '</div>' +
      '<div class="cs-scan-manual"><div class="cs-label">沙箱无摄像头：粘贴商户收款码内容（MP 开头）或 18 位付款码</div>' +
      '<textarea id="csScanInput" class="cs-input" rows="2" placeholder="粘贴二维码内容 / 付款码数字"></textarea>' +
      '<div class="cs-scan-btns"><button class="cs-btn" id="csScanParse">解析码</button>' +
      '<button class="cs-btn" id="csScanMerchant">生成本单商户码</button></div></div>' +
      '<div class="cs-scan-info" id="csScanInfo"></div></div>', '', { dark: true });
    el('csScanParse').onclick = parseScan;
    el('csTorch').onclick = () => {
      S.torch = !S.torch;
      el('csTorch').classList.toggle('on', S.torch);
      el('csStage').classList.toggle('torch', S.torch);
    };
    el('csAlbum').onclick = () => {
      const box = el('csScanInput');
      box.scrollIntoView({ block: 'center', behavior: 'smooth' });
      box.focus();
      say('沙箱环境无相册，请粘贴码内容', 'warn');
    };
    el('csScanMerchant').onclick = async () => {
      try {
        const c = await api('POST', '/api/cashier/code', { userId: S.userId, orderId: S.orderId, type: 'MERCHANT_PRESENTED' });
        el('csScanInput').value = c.qrContent || c.code;
        say('已生成商户码并填入，可直接解析', 'ok');
      } catch (e) { say(e.message, 'err'); }
    };
  }

  async function parseScan() {
    const raw = (el('csScanInput').value || '').trim();
    if (!raw) return say('请输入码内容', 'warn');
    const btn = el('csScanParse');
    btn.disabled = true;
    try {
      const c = await api('POST', '/api/cashier/code/parse', { code: raw });
      const box = el('csScanInfo');
      const mismatch = c.rideOrderId && c.rideOrderId !== S.orderId;
      box.innerHTML = '<div class="cs-card">' +
        '<div class="cs-kv"><span class="k">收款方</span><span class="v">' + esc(c.merchantName || '-') + '</span></div>' +
        '<div class="cs-kv"><span class="k">商品</span><span class="v">' + esc(c.subject || '-') + '</span></div>' +
        '<div class="cs-kv"><span class="k">金额</span><span class="v hi">' + money(c.amount) + '</span></div>' +
        '<div class="cs-kv"><span class="k">类型</span><span class="v">' + esc(c.type) + '</span></div>' +
        '<div class="cs-kv"><span class="k">状态</span><span class="v">' + esc(c.status) + '</span></div>' +
        (mismatch ? '<div class="cs-err" style="margin:10px 0 0">该码绑定订单 ' + esc(c.rideOrderId) + '，与当前行程不一致，禁止支付。</div>' : '') +
        (c.status !== 'ACTIVE' ? '<div class="cs-err" style="margin:10px 0 0">码状态 ' + esc(c.status) + '，不可受理。</div>' : '') +
        (mismatch || c.status !== 'ACTIVE' ? '' : '<button class="cs-btn" id="csScanPay">确认付款 ' + money(c.amount) + '</button>') +
        '</div>';
      if (el('csScanPay')) el('csScanPay').onclick = () => startPay('SCAN', raw);
    } catch (e) {
      el('csScanInfo').innerHTML = '<div class="cs-err">' + esc(e.message) + '</div>';
    } finally {
      btn.disabled = false;
    }
  }

  // ==================== 视图 6：结果页 ====================
  function resultView(kind, message, receipt) {
    S.step = 'result';
    clearTimers();
    const r = receipt || {};
    const pr = r.payResult || (r.payResult === null ? null : null);
    const amt = r.amount != null ? r.amount : actualAmount();
    const card = r.cardNo || (S.method === 'CARD' && S.cardNo ? '**** **** **** ' + S.cardNo.slice(-4) : null);
    let ic = SVG.ok, title = '支付成功', cls = 'cs-ok', kindCls = 'ok';
    if (kind === 'FAILED') { ic = SVG.fail; title = '支付失败'; cls = 'cs-err'; kindCls = 'fail'; }
    if (kind === 'PROCESSING') { ic = SVG.wait; title = '支付确认中'; cls = 'cs-note'; kindCls = 'wait'; }
    const wayName = ((S.data && S.data.methods) || []).find(m => m.method === (r.method || S.method));
    const detail = '<div class="cs-card cs-result-card">' +
      '<div class="cs-kv"><span class="k">支付方式</span><span class="v">' + esc(wayName ? wayName.name : (r.method || S.method)) + '</span></div>' +
      (card ? '<div class="cs-kv"><span class="k">卡号</span><span class="v">' + esc(card) + '</span></div>' : '') +
      '<div class="cs-kv"><span class="k">订单号</span><span class="v">' + esc(S.orderId || '') + '</span></div>' +
      (r.ppPaymentId ? '<div class="cs-kv"><span class="k">支付单号</span><span class="v">' + esc(r.ppPaymentId) + '</span></div>' : '') +
      (r.requestId ? '<div class="cs-kv"><span class="k">幂等键</span><span class="v">' + esc(r.requestId) + '</span></div>' : '') +
      (r.orderStatus ? '<div class="cs-kv"><span class="k">订单状态</span><span class="v">' + esc(r.orderStatus) + '</span></div>' : '') +
      (pr && pr.passengerBalance != null ? '<div class="cs-kv"><span class="k">钱包余额</span><span class="v">' + groupMoney(pr.passengerBalance) + '</span></div>' : '') +
      '</div>';
    const foot = kind === 'SUCCESS'
      ? '<button class="cs-btn" id="csDone">完成</button>'
      : (kind === 'FAILED'
        ? '<button class="cs-btn" id="csAgain">重新支付</button><button class="cs-btn ghost" id="csDone">返回行程</button>'
        : '<button class="cs-btn" id="csRecheck">刷新结果</button><button class="cs-btn ghost" id="csDone">返回行程</button>');
    shell(title, '<div class="cs-result"><div class="cs-result-ic ' + kindCls + '">' + ic + '</div>' +
      '<div class="cs-result-t">' + title + '</div>' +
      amountHtml(amt, 'cs-result-amt') +
      (kind === 'SUCCESS' ? '<div class="cs-result-s">车费已支付，司机实收与平台服务费已分账</div>' : '') +
      (message ? '<div class="cs-result-msg ' + cls + '">' + esc(message) + '</div>' : '') +
      '</div>' + detail, foot);
    if (el('csAgain')) el('csAgain').onclick = () => { S.verify = null; S.lastError = null; renderSummary(); };
    if (el('csRecheck')) el('csRecheck').onclick = () => waitTerminal(null);
    el('csDone').onclick = async () => {
      close();
      if (kind === 'SUCCESS') {
        try {
          if (typeof refreshOrder === 'function') await refreshOrder(S.orderId);
          if (typeof refreshWallet === 'function') refreshWallet();
          if (typeof onPaid === 'function') onPaid();
        } catch (e) { /* 展示层刷新失败不影响资金结果 */ }
      } else if (typeof refreshOrder === 'function') {
        refreshOrder(S.orderId);
      }
    };
    if (kind === 'SUCCESS') say('支付成功', 'ok');
  }

  // 暴露给乘客端既有支付入口
  window.openCashier = open;
  window.rideQrSvg = QR.svg;
})();