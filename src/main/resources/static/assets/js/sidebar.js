

function loadSidebar() {
  // 현재 페이지의 경로 깊이를 감지하여 base path 설정
  const path = window.location.pathname;
  const isInSubfolder = path.includes('/pages/');
  const basePath = isInSubfolder ? '../../' : '';

  const sidebarHTML = `
    <aside class="sidebar" id="sidebar">
      <div class="sidebar-header">
        <a href="${basePath}index.html" class="sidebar-title">JDEND <span class="erp-logo">ERP</span></a>
        <button class="sidebar-toggle" id="sidebarToggle">
          <span class="toggle-icon"></span>
          <span class="toggle-icon"></span>
          <span class="toggle-icon"></span>
        </button>
      </div>
      <nav>
        <ul class="sidebar-menu">
          <li class="has-sub">
            <span class="menu-label">고객관리</span>
            <ul>
              <li><a href="${basePath}pages/customer/customer_register.html">고객등록</a></li>
              <li><a href="${basePath}pages/customer/account_register.html">계좌등록</a></li>
              <li class="has-sub">
                <span class="menu-label">기타거래처</span>
                <ul>
                  <li><a href="${basePath}pages/customer/partner_register.html">거래처등록</a></li>
                  <li><a href="${basePath}pages/customer/partner_account_register.html">계좌등록</a></li>
                </ul>
              </li>
            </ul>
          </li>
          <li class="has-sub">
            <span class="menu-label">여신관리</span>
            <ul>
              <li><a href="${basePath}pages/contract/contract_register.html">대출등록</a></li>
              <li><a href="${basePath}pages/contract/contract_overview.html">대출현황</a></li>
              <li><a href="${basePath}pages/payment/schedule_management.html">상환스케줄</a></li>
              <li><a href="${basePath}pages/contract/early_termination.html">중도상환</a></li>
              <li class="has-sub">
                <span class="menu-label">만기관리</span>
                <ul>
                  <li><a href="${basePath}pages/contract/maturity_management.html">만기연장</a></li>
                  <li><a href="${basePath}pages/contract/maturity_termination.html">만기종료</a></li>
                </ul>
              </li>
            </ul>
          </li>
          <li class="has-sub">
            <span class="menu-label">채권관리</span>
            <ul>
              <li><a href="${basePath}pages/payment/overdue_status.html">연체현황</a></li>
              <li><a href="${basePath}pages/management/contract_status.html">채권현황</a></li>
              <li class="has-sub">
                <span class="menu-label">미수관리</span>
                <ul>
                  <li><a href="${basePath}pages/payment/receivable_register.html">미수금등록</a></li>
                  <li><a href="${basePath}pages/payment/receivable_status.html">미수현황</a></li>
                </ul>
              </li>
              <li><a href="${basePath}pages/contract/legal_proceeding.html">법적절차</a></li>
              <li><a href="${basePath}pages/payment/consultation_register.html">상담등록</a></li>
            </ul>
          </li>
          <li class="has-sub">
            <span class="menu-label">수납관리</span>
            <ul>
              <li class="has-sub">
                <span class="menu-label">청구관리</span>
                <ul>
                  <li><a href="${basePath}pages/payment/billing_create.html">청구생성</a></li>
                  <li><a href="${basePath}pages/payment/billing_issue.html">청구서발행</a></li>
                </ul>
              </li>
              <li class="has-sub">
                <span class="menu-label">수납관리</span>
                <ul>
                  <li><a href="${basePath}pages/payment/payment_register.html">수납등록</a></li>
                </ul>
              </li>
              <li class="has-sub">
                <span class="menu-label">은행내역업로드</span>
                <ul>
                  <li><a href="${basePath}pages/payment/bank_transaction_upload.html">은행내역업로드</a></li>
                </ul>
              </li>
            </ul>
          </li>
          <li class="has-sub">
            <span class="menu-label">회계관리</span>
            <ul>
              <li class="has-sub">
                <span class="menu-label">전표등록</span>
                <ul>
                  <li><a href="${basePath}pages/accounting/daily_voucher.html">일일전표등록</a></li>
                  <li><a href="${basePath}pages/accounting/sales_voucher.html">매출전표등록</a></li>
                  <li><a href="${basePath}pages/accounting/monthly_voucher.html">월 전표등록</a></li>
                  <li><a href="${basePath}pages/accounting/voucher_approval.html">전표승인</a></li>
                </ul>
              </li>
              <li class="has-sub">
                <span class="menu-label">재무제표관리</span>
                <ul>
                  <li><a href="${basePath}pages/accounting/balance_sheet.html">재무상태표</a></li>
                  <li><a href="${basePath}pages/accounting/income_statement.html">손익계산서</a></li>
                   <li><a href="${basePath}pages/management/financial_statement_management.html">재무제표관리</a></li>
                </ul>
              </li>
              <li class="has-sub">
                <span class="menu-label">일일자금</span>
                <ul>
                  <li><a href="${basePath}pages/accounting/daily_cash_flow.html">일자별 입출금현황</a></li>
                  <li><a href="${basePath}pages/accounting/daily_fund_report.html">일일자금일보</a></li>
                </ul>
              </li>
              <li class="has-sub">
                <span class="menu-label">미지급관리</span>
                <ul>
                  <li><a href="${basePath}pages/accounting/payable_management.html">미지급현황</a></li>
                </ul>
              </li>
              <li class="has-sub">
                <span class="menu-label">선수금관리</span>
                <ul>
                  <li><a href="${basePath}pages/accounting/prepaid_rent_management.html">선수금관리</a></li>
                </ul>
              </li>
            </ul>
          </li>
          <li class="has-sub">
            <span class="menu-label">총괄관리</span>
            <ul>
              <li class="has-sub">
                <span class="menu-label">회계계정관리</span>
                <ul>
                  <li><a href="${basePath}pages/accounting/account_management.html">기타계정관리</a></li>
                </ul>
              </li>
              <li><a href="${basePath}pages/management/my_info.html">내정보관리</a></li>
              <li id="companyUsersMenuItem" style="display:none;">
                <a href="${basePath}pages/management/company_users.html">사용자관리</a>
              </li>
              <li id="reportEmailSettingsMenuItem" style="display:none;">
                <a href="${basePath}pages/management/report_email_settings.html">이메일 보고서 설정</a>
              </li>
              <li id="taxConsultationMenu" class="has-sub" style="display:none;">
                <span class="menu-label">세무상담</span>
                <ul>
                  <li id="taxConsultationUserItem" style="display:none;">
                    <a href="${basePath}pages/consultation/tax_consultation.html">문의하기</a>
                  </li>
                  <li id="taxConsultationAdminItem" style="display:none;">
                    <a href="${basePath}pages/consultation/tax_consultation_admin.html">문의관리</a>
                  </li>
                </ul>
              </li>
              <li id="subscriptionMenuItem" style="display:none;">
                <a href="${basePath}pages/admin/subscription_list.html">구독관리</a>
              </li>
            </ul>
          </li>
        </ul>
      </nav>
      <div class="sidebar-footer">
        <button class="sidebar-logout-btn" id="sidebarLogoutBtn">&#x2192; 로그아웃</button>
      </div>
    </aside>
  `;
  document.getElementById('sidebar-container').innerHTML = sidebarHTML + '<div class="sidebar-overlay" id="sidebarOverlay"></div>';

  // 로그아웃 버튼
  const logoutBtn = document.getElementById('sidebarLogoutBtn');
  if (logoutBtn) {
    logoutBtn.addEventListener('click', function() {
      const base = (typeof API_BASE_URL !== 'undefined' && API_BASE_URL) ? String(API_BASE_URL).replace(/\/+$/, '') : '';
      fetch(base + '/api/auth/logout', { method: 'POST', credentials: 'include' })
        .finally(function() {
          window.location.href = base + '/login.html';
        });
    });
  }

  // 회사관리자(또는 운영자)일 때만 "사용자관리" 메뉴 노출. 페이지별로 role.js를 따로 안 넣어도
  // 항상 동작하게 sidebar.js 자체에서 직접 /api/auth/me를 조회한다.
  (function () {
    const base = (typeof API_BASE_URL !== 'undefined' && API_BASE_URL) ? String(API_BASE_URL).replace(/\/+$/, '') : '';
    fetch(base + '/api/auth/me', { credentials: 'include' })
      .then(function (res) { return res.json(); })
      .then(function (data) {
        const role = data && data.success ? data.role : null;
        const taxEnabled = data && data.taxConsultationEnabled;

        // 사용자관리 메뉴
        const item = document.getElementById('companyUsersMenuItem');
        if (item && (role === 'ADMIN' || role === 'COMPANY_ADMIN')) item.style.display = '';

        // 이메일 보고서 설정 메뉴 (회사관리자·책임자 이상 노출)
        const reportItem = document.getElementById('reportEmailSettingsMenuItem');
        if (reportItem && (role === 'COMPANY_ADMIN' || role === 'MANAGER')) reportItem.style.display = '';

        // 세무상담 메뉴
        const taxMenu = document.getElementById('taxConsultationMenu');
        const taxUserItem = document.getElementById('taxConsultationUserItem');
        const taxAdminItem = document.getElementById('taxConsultationAdminItem');
        if (role === 'ADMIN' || role === 'TAX_AGENT') {
          if (taxMenu) taxMenu.style.display = '';
          if (taxAdminItem) taxAdminItem.style.display = '';
        } else if (taxEnabled) {
          if (taxMenu) taxMenu.style.display = '';
          if (taxUserItem) taxUserItem.style.display = '';
        }

        // 구독관리 메뉴 — ADMIN 전용
        const subscriptionItem = document.getElementById('subscriptionMenuItem');
        if (subscriptionItem && role === 'ADMIN') subscriptionItem.style.display = '';

      })
      .catch(function (e) { console.error('권한 조회 실패', e); });
  })();

  // 서브메뉴 접기/펼치기 (아코디언 방식)
  document.querySelectorAll('.sidebar .has-sub > .menu-label').forEach(function(label) {
    label.addEventListener('click', function(e) {
      e.stopPropagation();
      const parent = label.parentElement;
      const isOpen = parent.classList.contains('open');

      // 같은 레벨의 모든 메뉴 닫기
      const siblings = parent.parentElement.children;
      Array.from(siblings).forEach(function(sibling) {
        if(sibling.classList.contains('has-sub')) {
          sibling.classList.remove('open');
        }
      });

      // 클릭한 메뉴가 닫혀있었으면 열기
      if(!isOpen) {
        parent.classList.add('open');
      }
    });
  });

  // 현재 페이지에 해당하는 링크에 active 클래스 추가 및 부모 메뉴 열기
  const currentPage = window.location.pathname.split('/').pop();
  const currentLink = document.querySelector(`a[href$="${currentPage}"]`);

  if (currentLink) {
    currentLink.classList.add('active');

    // 현재 링크의 부모 메뉴들을 찾아서 open 클래스 추가
    let parent = currentLink.closest('.has-sub');
    while (parent) {
      parent.classList.add('open');
      parent = parent.parentElement.closest('.has-sub');
    }
  }

  // 모든 메뉴 링크의 파일 존재 여부를 자동 체크 (웹서버 환경에서만)
  // 로컬 파일(file://) 환경에서는 fetch가 작동하지 않으므로 건너뜀
  if (window.location.protocol !== 'file:') {
    document.querySelectorAll('.sidebar-menu a[href$=".html"]').forEach(function(link) {
      const href = link.getAttribute('href');
      if (!href || href === '#') return;

      fetch(href, { method: 'HEAD' })
        .then(function(response) {
          if (!response.ok) {
            // 파일이 없으면 미연결 표시
            link.style.color = '#e53e3e';
            link.style.fontWeight = '600';
            link.innerHTML = link.textContent + ' <span style="font-size:10px;background:#e53e3e;color:#fff;padding:1px 4px;border-radius:3px;margin-left:4px;">미연결</span>';
            link.addEventListener('click', function(e) {
              e.preventDefault();
              alert('아직 준비 중인 페이지입니다.');
            });
          }
        })
        .catch(function() {
          // 에러 발생 시에도 미연결 표시
          link.style.color = '#e53e3e';
          link.style.fontWeight = '600';
          link.innerHTML = link.textContent + ' <span style="font-size:10px;background:#e53e3e;color:#fff;padding:1px 4px;border-radius:3px;margin-left:4px;">미연결</span>';
          link.addEventListener('click', function(e) {
            e.preventDefault();
            alert('아직 준비 중인 페이지입니다.');
          });
        });
    });
  }

  // 사이드바 토글 기능
  const sidebarToggle = document.getElementById('sidebarToggle');
  const sidebar = document.getElementById('sidebar');
  const sidebarOverlay = document.getElementById('sidebarOverlay');
  const contentWrapper = document.querySelector('.content-wrapper');

  // 모바일 체크 함수
  function isMobile() {
    return window.innerWidth <= 768;
  }

  if (sidebarToggle) {
    sidebarToggle.addEventListener('click', function(e) {
      e.stopPropagation();

      if (isMobile()) {
        // 모바일: open 클래스 토글
        sidebar.classList.toggle('open');

        if (sidebar.classList.contains('open')) {
          sidebarOverlay.style.display = 'block';
        } else {
          sidebarOverlay.style.display = 'none';
        }
      } else {
        // 데스크톱: collapsed 클래스 토글
        sidebar.classList.toggle('collapsed');

        if (contentWrapper) {
          if (sidebar.classList.contains('collapsed')) {
            contentWrapper.style.marginLeft = '0';
          } else {
            contentWrapper.style.marginLeft = '220px';
          }
        }
      }
    });
  }

  // 오버레이 클릭 시 사이드바 닫기
  if (sidebarOverlay) {
    sidebarOverlay.addEventListener('click', function() {
      sidebar.classList.remove('open');
      sidebarOverlay.style.display = 'none';
    });
  }

  // 윈도우 리사이즈 시 처리
  window.addEventListener('resize', function() {
    if (!isMobile()) {
      sidebar.classList.remove('open');
      sidebarOverlay.style.display = 'none';

      // 데스크톱에서 collapsed 상태가 아니면 margin 복원
      if (contentWrapper && !sidebar.classList.contains('collapsed')) {
        contentWrapper.style.marginLeft = '220px';
      }
    } else {
      // 모바일에서는 margin 제거
      if (contentWrapper) {
        contentWrapper.style.marginLeft = '0';
      }
    }
  });
}
window.addEventListener('DOMContentLoaded', loadSidebar);

// ── 날짜 입력 연도 4자리 제한 ──────────────────────────────────────────
// Chrome의 <input type="date"> 연도 섹션은 4자리 입력 후 자동으로 월로 이동해야
// 하지만 이동이 안 될 경우 5~6자리 입력이 가능해지는 버그가 있음.
// 각 섹션(연4/월2/일2)을 추적하여 최대 자릿수 초과 시 입력 차단 + 섹션 자동 이동.
(function () {
  var SEC_MAX = [4, 2, 2]; // year, month, day

  function applyYearFix(el) {
    if (el._yrFixed) return;
    el._yrFixed = true;
    if (!el.hasAttribute('max')) el.setAttribute('max', '9999-12-31');

    var sec = 0, cnt = 0;

    function resetState() { sec = 0; cnt = 0; }

    el.addEventListener('focus', resetState);
    el.addEventListener('blur', resetState);
    el.addEventListener('click', resetState);

    el.addEventListener('keydown', function (e) {
      if (/^\d$/.test(e.key)) {
        cnt++;
        if (cnt > SEC_MAX[sec]) {
          // 해당 섹션 최대 자릿수 초과 → 차단
          e.preventDefault();
          return;
        }
        if (cnt === SEC_MAX[sec] && sec < 2) {
          // 섹션 꽉 참 → 다음 섹션으로 자동 이동
          var me = this;
          setTimeout(function () {
            sec++;
            cnt = 0;
            // Chrome date input에서 '/' 키가 섹션을 전진시킴
            me.dispatchEvent(new KeyboardEvent('keydown', {
              key: '/', code: 'Slash', keyCode: 191,
              bubbles: true, cancelable: false
            }));
          }, 0);
        }
      } else if (e.key === '/' || e.key === 'ArrowRight') {
        sec = Math.min(2, sec + 1); cnt = 0;
      } else if (e.key === 'ArrowLeft') {
        sec = Math.max(0, sec - 1); cnt = 0;
      } else if (e.key === 'Backspace' || e.key === 'Delete') {
        cnt = Math.max(0, cnt - 1);
      }
    });
  }

  function init() {
    document.querySelectorAll('input[type="date"]').forEach(applyYearFix);
    // 동적으로 추가된 date input에도 적용
    new MutationObserver(function (muts) {
      muts.forEach(function (m) {
        m.addedNodes.forEach(function (n) {
          if (!n || n.nodeType !== 1) return;
          if (n.type === 'date') applyYearFix(n);
          if (n.querySelectorAll) n.querySelectorAll('input[type="date"]').forEach(applyYearFix);
        });
      });
    }).observe(document.documentElement, { childList: true, subtree: true });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
