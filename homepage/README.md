# JDEND 대부업 ERP - 홍보 홈페이지 (로컬)

대부업자 대상 마케팅 홈페이지. **정적 HTML/CSS/JS(빌드 불필요, self-contained)**.
ERP 소스(`../src/`)와 분리되어 있으며 이 폴더만으로 동작/배포 가능합니다.

> 2026-08-24: 제품이 렌터카 ERP → 대부업 ERP로 전환되면서 5개 페이지와 브리프 2건을 전면 개편했습니다.

## 로컬에서 열기
- 파일 더블클릭: `index.html`을 브라우저로 열면 됩니다(외부 의존성 없음).
- 로컬 서버(선택): `python -m http.server 5500` 후 http://localhost:5500

## 폴더 구조
```
homepage/
├─ index.html        # 랜딩(히어로·문제공감·핵심기능·차별점·도입절차·FAQ·CTA)
├─ features.html     # 기능 소개(여신/채권/청구수납/자동회계/재무제표/멀티회사)
├─ pricing.html      # 요금제(Lite·Basic·Standard·Pro·Enterprise, 잠정)
├─ contact.html      # 무료 데모/상담 신청 폼(프론트 only, 전송 미연동)
├─ support.html      # 고객지원·1:1 문의 게시판(골격, CS 정책 미확정)
├─ robots.txt        # SEO(네이버 Yeti 포함)
├─ sitemap.xml       # SEO 사이트맵
├─ _brief/           # 기획·마케팅 인풋 문서
└─ assets/
   ├─ css/style.css  # 공용 스타일(모바일 우선)
   └─ js/main.js     # 모바일 메뉴·폼 처리·게시판 탭
```

## 미연동/추후 작업 (중요)
- **마케팅 도메인 미확정**: 현재 `www.planbloan.co.kr`로 넣었으나 이는 ERP CORS 설정에서 가져온 값이다.
  플랜비대부는 **고객사**이지 ERP 판매사가 아니므로, 마케팅 도메인이 이게 맞는지 총괄 확인이 필요하다.
  (`index/features/pricing/contact/support`의 canonical·og:url, `robots.txt`, `sitemap.xml` 5곳 + 2파일)
- **폼 전송 미연동**: contact/support 폼은 프론트 검증·완료 안내만. 실제 저장/이메일은 백엔드 연동 필요(`main.js`의 TODO).
- **요금 잠정**: pricing.html 금액은 렌터카 티어를 채권 건수로 옮긴 1차안. 기획팀·총괄과 확정 필요.
  과금 단위를 채권 건수로 할지 회차(스케줄 행) 수로 할지도 미결(`_brief/positioning-pricing.md` §3-3).
- **SEO 키워드 미검증**: 대부업 키워드 검색량·경쟁도 재조사 필요(`_brief/messaging-copy.md` §5).
- **CS 게시판 골격**: 실제 게시판(저장·상태·비밀글·SLA)은 CS 담당자와 정책 확정 후 연동.
- **연락처 placeholder**: 전화·이메일은 확정 시 반영.
- **OG 이미지**: og:image 미지정(대시보드/채권현황 화면 캡처 권장).

## 카피 작성 시 지켜야 할 선 (대부업 특유)
- **법령 준수를 보증하는 표현 금지.** "연 20% 상한을 검증한다"까지만. "그래서 법적으로 안전하다"는 안 된다.
- **추심을 강조하는 자극적 카피 금지.** 채권추심법 취지·브랜드 리스크 양쪽에 걸린다.
- **보안 카피는 보수적으로.** 개인신용정보를 다루므로 미적용 인증을 사실처럼 쓰면 리스크가 크다.
