/**
 * exportTableToExcel — DOM 테이블을 CSV(엑셀 호환)로 내보냅니다.
 * UTF-8 BOM 포함으로 한글 깨짐 없이 Excel에서 바로 열립니다.
 *
 * @param {string} tbodyId  - <tbody> 요소의 id
 * @param {string} filename - 다운로드 기본 파일명 (날짜 자동 추가)
 */
function exportTableToExcel(tbodyId, filename) {
  var tbody = document.getElementById(tbodyId);
  if (!tbody) {
    alert('테이블을 찾을 수 없습니다.');
    return;
  }

  var parentTable = tbody.closest('table');
  var rows = [];

  // 헤더 행
  if (parentTable) {
    var headerRow = parentTable.querySelector('thead tr');
    if (headerRow) {
      rows.push(
        // data-noexport 가 붙은 열(체크박스 등)은 내보내지 않는다. 내보내면 빈 열이
        // 하나 끼어 엑셀에서 머리글과 값이 한 칸씩 어긋나 보인다.
        Array.from(headerRow.querySelectorAll('th'))
          .filter(function(th) { return !th.hasAttribute('data-noexport'); })
          .map(function(th) { return th.textContent.trim(); })
      );
    }
  }

  // 데이터 행 (colspan 단독 셀 = 빈 데이터/오류 메시지 → 건너뜀)
  var dataRows = tbody.querySelectorAll('tr');
  for (var i = 0; i < dataRows.length; i++) {
    var cells = dataRows[i].querySelectorAll('td');
    if (!cells.length) continue;
    if (cells.length === 1 && cells[0].hasAttribute('colspan')) continue;
    rows.push(
      Array.from(cells)
        .filter(function(td) { return !td.hasAttribute('data-noexport'); })
        .map(function(td) {
          return td.textContent.trim().replace(/\s+/g, ' ');
        })
    );
  }

  if (rows.length <= 1) {
    alert('내보낼 데이터가 없습니다.\n먼저 조회하기를 눌러 데이터를 불러오세요.');
    return;
  }

  // CSV 생성 (RFC 4180, UTF-8 BOM)
  var csv = rows.map(function(row) {
    return row.map(function(cell) {
      return '"' + String(cell).replace(/"/g, '""') + '"';
    }).join(',');
  }).join('\r\n');

  var blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8;' });
  var url = URL.createObjectURL(blob);
  var a = document.createElement('a');
  a.href = url;
  a.download = filename + '_' + new Date().toISOString().slice(0, 10) + '.csv';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
