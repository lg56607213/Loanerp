/**
 * 검색 창 표 쪽 넘김 — 한 번에 30건씩 보여준다.
 *
 * 돋보기를 누르면 계약이든 고객이든 전부 한 줄로 쏟아져서, 수십 건만 되어도
 * 찾으려는 건을 눈으로 좇을 수 없었다. 서버는 그대로 두고 받아 온 목록을
 * 화면에서 끊는다 — 검색 조건을 좁히면 대개 몇 건으로 줄어들고, 서버 쪽을
 * 고치면 각 화면의 조회 API 를 전부 손봐야 한다.
 *
 * 쓰는 법
 *   const pager = createModalPager({
 *     tbody: document.getElementById('contractSearchBody'),
 *     pager: document.getElementById('contractSearchPager'),
 *     colSpan: 6,
 *     emptyText: '검색 결과가 없습니다.',
 *     renderRow: (row) => { ... return tr; }     // <tr> 를 만들어 돌려준다
 *   });
 *   pager.show(list);            // 목록을 넣는다 (첫 쪽부터)
 *   pager.message('검색중...');   // 안내 문구만 보여준다
 *
 * 줄을 만드는 일과 줄을 눌렀을 때의 동작은 화면마다 다르므로 renderRow 에 맡긴다.
 */
(function () {
  var stylesInjected = false;

  function injectStyles() {
    if (stylesInjected) return;
    stylesInjected = true;
    var style = document.createElement('style');
    style.textContent =
      '.mp-pager{ display:flex; align-items:center; gap:6px; margin-top:10px; flex-wrap:wrap; }' +
      '.mp-pager .mp-count{ font-size:12px; color:#64748b; margin-right:auto; }' +
      '.mp-pager button{ min-width:30px; height:28px; padding:0 8px; border:1px solid #d7d7d7;' +
      '  background:#fff; border-radius:5px; font-size:12px; cursor:pointer; }' +
      '.mp-pager button:hover:not(:disabled){ background:#f5f5f5; }' +
      '.mp-pager button.active{ background:#1a3c7a; border-color:#1a3c7a; color:#fff; font-weight:700; }' +
      '.mp-pager button:disabled{ color:#bbb; cursor:not-allowed; }';
    document.head.appendChild(style);
  }

  window.createModalPager = function (opts) {
    injectStyles();

    var tbody = opts.tbody;
    var pagerEl = opts.pager;
    var pageSize = opts.pageSize || 30;
    var colSpan = opts.colSpan || 6;
    var emptyText = opts.emptyText || '검색 결과가 없습니다.';
    var renderRow = opts.renderRow;

    var rows = [];
    var page = 1;

    if (pagerEl) {
      pagerEl.classList.add('mp-pager');
      pagerEl.addEventListener('click', function (e) {
        var p = e.target && e.target.getAttribute && e.target.getAttribute('data-page');
        if (p) render(Number(p));
      });
    }

    /** 표 자리에 안내 문구 한 줄만 보여준다(조회중·실패 등). */
    function message(text) {
      tbody.innerHTML = '<tr><td colspan="' + colSpan +
        '" style="text-align:center;padding:14px;">' + text + '</td></tr>';
      if (pagerEl) pagerEl.innerHTML = '';
    }

    function show(list) {
      rows = Array.isArray(list) ? list : [];
      if (!rows.length) {
        message(emptyText);
        return;
      }
      render(1);
    }

    function render(toPage) {
      var total = rows.length;
      var lastPage = Math.max(1, Math.ceil(total / pageSize));
      page = Math.min(Math.max(1, toPage), lastPage);

      var start = (page - 1) * pageSize;
      var slice = rows.slice(start, start + pageSize);

      tbody.innerHTML = '';
      slice.forEach(function (row, i) {
        var tr = renderRow(row, start + i);
        if (tr) tbody.appendChild(tr);
      });

      renderPager(total, lastPage, start, slice.length);

      // 쪽을 넘겼는데 가운데부터 보이면 어디로 간 것인지 알 수 없다
      var wrap = scrollParentOf(tbody);
      if (wrap) wrap.scrollTop = 0;
    }

    /** 쪽 번호 — 현재 쪽 둘레 다섯 개만. 쪽이 많아져도 줄이 넘치지 않게 한다. */
    function renderPager(total, lastPage, start, shown) {
      if (!pagerEl) return;

      if (lastPage <= 1) {
        pagerEl.innerHTML = '<span class="mp-count">총 ' + total + '건</span>';
        return;
      }

      var dots = '<span class="mp-count">...</span>';
      var html = '<span class="mp-count">총 ' + total + '건 중 ' +
                 (start + 1) + '~' + (start + shown) + '</span>';
      html += btn(page - 1, '이전', page === 1);

      var first = Math.max(1, page - 2);
      var last = Math.min(lastPage, first + 4);
      if (first > 1) html += btn(1, '1', false);
      if (first > 2) html += dots;
      for (var i = first; i <= last; i++) html += btn(i, String(i), false, i === page);
      if (last < lastPage - 1) html += dots;
      if (last < lastPage) html += btn(lastPage, String(lastPage), false);

      html += btn(page + 1, '다음', page === lastPage);
      pagerEl.innerHTML = html;
    }

    function btn(target, label, disabled, active) {
      return '<button type="button" data-page="' + target + '"' +
             (disabled ? ' disabled' : '') +
             (active ? ' class="active"' : '') + '>' + label + '</button>';
    }

    /** 표를 감싼 스크롤 상자를 찾는다. 없으면 null. */
    function scrollParentOf(el) {
      var node = el.parentElement;
      while (node && node !== document.body) {
        var oy = getComputedStyle(node).overflowY;
        if (oy === 'auto' || oy === 'scroll') return node;
        node = node.parentElement;
      }
      return null;
    }

    return { show: show, message: message, render: render };
  };
})();
