---
screen: SCREEN-019
generated_at: 2026-08-11T00:00:00.000Z
screen_design_item: SD-005
design_render_urls:
  main: /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-005/main.html (css: main.css)
surfaces: [main]
ds: DS-001 (KRDS Public)
source_wireframes: [wireframe.html]
generated_by: mc-logi-screen-design (Phase 1~5 수행, Phase 6 미실행)
---

# SCREEN-019 디자인 노트 — 검수 상세 화면

> ⚠️ 이 배치는 스킬의 Phase 1~5(입력 합성 → 로컬 디자인 작성 → 검증 → 키트 반영 → screen_design 역등록)까지 수행했다.
> Phase 6(ui_component 보강 권고)는 사용자 지시로 이번에 수행하지 않았다.

## § 디자인 결정 (와이어프레임 대비 추가분)

- **레이아웃 재구성**: 와이어프레임은 "상단 프레임 이동 바"·"프레임 썸네일 스트립"을 168px 좌측 레일에 세로로 쌓아 두었으나, `SCREEN-019.md`의 section description(각 섹션이 "헤더 바로 아래 별도 상단바" / "캔버스 바로 위" 라고 명시)과 카탈로그 `FrameNavigator`(UI-052, "라벨링 헤더·검수 헤더 어느 쪽에도 두지 않으며 이 컨트롤이 단독으로 담당")·`ReviewFrameTimeline`(UI-063, "가로 스크롤 썸네일 스트립")의 실제 배치 규약에 따라 ①헤더 바로 아래 전체폭 프레임 이동 바 ②본문 컬럼 상단(캔버스 위)의 가로 썸네일 스트립으로 재배치했다. 와이어프레임 파일 자체가 "이 열의 항목들은 실제 화면에서는 탭으로 나뉘어 한 번에 하나만 보일 수 있다 … 실제보다 길다"고 명시해 배치 정확도를 스스로 부인하고 있어, section 순서·구성 요소·설명 문구(골격)는 100% 보존하되 CSS 배치만 설명 텍스트에 맞춰 교정했다. 승인/반려 버튼도 와이어프레임은 별도 footer 카드에 그렸지만 section 10 설명과 `ReviewHeader`(UI-060) 카탈로그 규약("우: 상태 배지 + 반려 + 승인")에 따라 헤더 우측에 배치했다.
- **상태별**: 검수 상태 배지 4종(검수대기/검수중/완료/반려)을 색+아이콘 도트+한글 라벨로 구분(색상 단독 금지, DS do_rule 준수). 재검토 필요 배지는 warn 톤 + 경고 아이콘 + "재검토 필요" 텍스트로 병기. 썸네일 테두리 색은 우선순위(현재>미해소 문의>라벨 저장>기본)를 4색+아이콘(문의는 점 아이콘 추가)으로 구현. 빈 상태·로딩 스켈레톤·에러 상태는 실제 화면 흐름상 항시 노출되지 않으므로 페이지 하단 "디자인 검토용 참고 갤러리"(실 화면 아님, 명시 구분)에 별도로 모아 시각화했다.
- **데이터 밀도**: 캔버스는 실제 프레임 대신 추상화된 다크 매트(그라데이션)+오버레이 박스/폴리곤으로 표현(DS 예외 — "영상 프레임을 얹는 매트만 다크 허용"). 객체 트리·이슈 스레드·메모 목록은 실제 개수(5객체/3스레드/3메모)로 채우고 mono 폰트로 좌표·프레임번호를 정렬. 서술 전문은 글자수 카운터 병기.
- **시각 위계**: 헤더(56px, 라이트)·상단바·본문 3분할(캔버스 dark mat / 우측 패널 white)로 절차 구분. 1차 액션(승인=primary)과 2차 액션(반려=danger outline형, 닫기=ghost)을 명확히 분리. 우측 패널은 카드 padding을 24px 표준 대신 밀집 예외(8-16px)로 좁히되 모든 인터랙티브 요소 44px 히트영역은 유지(DS whitespace_principle 명시 허용).
- **컴포넌트 디테일**: 버튼 hover/disabled 스타일, 입력 focus-visible 링(3px+2px offset), 객체 행 선택/hover 배경 분리(선택=primary tint, hover=DS 명시 예외 #FFFBEB), 이슈 스레드 잠금/충돌 안내 인라인 표시.

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 검수 헤더 전체 | UI-060 ReviewHeader | primary/neutral, title-md, body-sm | 닫기+제목+메타+상태배지+반려/승인 한 컴포넌트 규약 |
| 닫기 버튼 | UI-001 Button(variant=ghost, size=icon) | neutral-7 | aria-label 필수(아이콘 전용) |
| 검수 상태 배지 | UI-014 StatusBadge | neutral/warn/success/error 각 -0/-7 | 4개 값 매핑, 도트+라벨 |
| 재검토 필요 배지 | ⚠️ 미정 (카탈로그 없음) | warn-0/-7 | RecheckBadge 또는 StatusBadge 확장 후보 — 아래 참조 |
| 반려/승인 버튼 | UI-001 Button(danger/primary) | error-5/-6, primary-5/-6 | ReviewHeader 내부에 조합 |
| 상단 프레임 이동 바 | UI-052 FrameNavigator | mono(프레임 번호), primary(슬라이더) | 처음/이전/다음/마지막+번호입력+슬라이더 |
| 프레임 썸네일 스트립 | UI-063 ReviewFrameTimeline | primary/warn/success 테두리, mono(진행률) | 4색 우선순위 테두리, role=listbox |
| 라벨 캔버스 | UI-058 ReviewLabelCanvas | info-1/secondary-3(오버레이), nt-9/10(매트) | 읽기전용, hover 칩은 1건 상시 노출로 시연 |
| 읽기 전용 배지 | ⚠️ 미정 (카탈로그 없음, `ReadOnlyBadge` custom_name) | nt-10 반투명 배경, 흰 텍스트 | 캔버스 좌상단 고정 |
| 화면 맞춤 버튼 | UI-001 Button(secondary, size=xs) | neutral | 캔버스 내 오버레이 버튼 |
| 카테고리 그룹 트리 | UI-049 ObjectClassTree | info/secondary/warn(그룹 dot), sc-0(type-badge bg) | 편집 핸들러 미전달 → 읽기 전용 목록으로 렌더(카탈로그 규약) |
| 선택 객체 속성 | UI-050 ObjectAttributePanel(읽기 전용 매핑) → UI-011 Card로 감쌈 | secondary-0(head), mono(좌표), success-5(신뢰도바) | 좌표/신뢰도/유형/카테고리 read-only 표시만 |
| 검수 메모 목록 | UI-065 ReviewMemoPanel | secondary-6(태그), neutral-0(레거시 항목 배경) | 이전 등록 이슈는 점선 카드로 구분 |
| 이벤트 어노테이션 검토 | ⚠️ 미정 (카탈로그 없음, `EventAnnotationReviewPanel`) | neutral/mono | Select+시간 입력 2종 |
| 시계열 메타 검토 | ⚠️ 미정 (카탈로그 없음, `TimeseriesMetaReviewPanel`) — UI-056 TimeseriesSidePanel과 유사하나 referenced_by에 SCREEN-019 없음 | info-5(일치도 바), neutral(레거시 리스트) | 서술 전문 편집+일치도 읽기전용+레거시 구간 읽기전용 3단 구성 |
| 영상 기술 정보 | ⚠️ 미정 (카탈로그 없음, `VideoTechnicalMetaPanel`) | mono, neutral-5 | 2열 key-value 읽기전용 |
| 이슈 탭 헤더 + 스레드 | UI-097 IssueThreadPanel | error/secondary(유형배지), warn/info/success(상태배지), error-5(카운트) | 반려/문의 통합 스레드, 해소버튼(reviewer), 동시처리 충돌 인라인 |
| 승인 확정 확인창 | UI-005 ConfirmDialog(variant=primary) | primary | |
| 반려 처리 창 | UI-062 RejectModal | error, mono(글자수) | 메모 합산 안내 포함 |
| 라벨 없음 확인창 | UI-005 ConfirmDialog 변형(체크박스 포함) | warn-0(안내 박스) | 체크박스 확인 후 승인 |

### ⚠️ 토큰/컴포넌트 미정 — logicraft 보강 후보 (Phase 6 미실행, 참고용 목록)

- `RecheckBadge`(재검토 필요 배지) — StatusBadge와 유사하나 별도 boolean 배지. StatusBadge 확장 또는 신규 컴포넌트로 등록 검토.
- `ReadOnlyBadge` — 캔버스 좌상단 고정 배지. 범용 배지 프리미티브로 등록 검토.
- `EventAnnotationReviewPanel` / `TimeseriesMetaReviewPanel`(UI-056과 별개, referenced_by SCREEN-019 없음) / `VideoTechnicalMetaPanel` — 메타 탭 3영역. `screen_spec`이 custom_name으로 명시했으나 `ui-catalog.md`(100건) 전체에 대응 항목이 없다(그렙 확인 완료). 3종 모두 신규 후보.

이 목록은 이번 배치에서 등록하지 않았다 — 사용자 확인 후 별도 Phase 6에서 처리 예정.

## § 접근성 검증 (WCAG 대비 — Phase 3 D5)

동봉 `scripts/contrast_checker.py`로 실제 사용 조합을 개별 검사(--palette 전체조합 모드는 무관한 조합까지 포함해 사용 안 함). 토큰은 DS의 *기존* hex(base/scale) 값만 사용.

| 조합 (텍스트 × 배경) | 토큰명 | 대비 | 판정 | 조치 |
|---|---|---|---|---|
| 본문 텍스트 × 백그라운드 | neutral-9 × #fff | 16.18:1 | AAA | OK |
| 헤더 메타/캡션 × 백그라운드(흰) | neutral-6 × #fff | 6.30:1 | AAA | OK |
| 보조 텍스트(회색 표면) × neutral-0 | neutral-5 × neutral-0 | 4.13:1 | **FAIL**(AA 미달) | neutral-6로 교체(5.77:1) — `.issue-comment-time`, `.design-ref-gallery-note` 2곳 수정 완료 |
| 보조 텍스트(흰 배경) × 백그라운드 | neutral-5 × #fff | 4.51:1 | AA(여유 근소) | OK — DS known_gaps 문서화된 한계, 흰 배경 한정 사용 유지 |
| 검수중 배지 텍스트 × warn 표면 | warn-7 × warn-0 | 8.43:1 | AAA | OK (warn-5 기본색은 known_gaps상 미달 — warn-7로 한 단 더 깊게 사용) |
| 완료 배지 텍스트 × success 표면 | success-7 × success-0 | 6.99:1 | AA | OK |
| 반려 배지 텍스트 × error 표면 | error-7 × error-0 | 8.01:1 | AAA | OK |
| 이슈 답변완료 배지 × info 표면 | info-7 × info-0 | 6.82:1 | AA | OK |
| 타입 배지 텍스트 × secondary 표면 | secondary-7 × secondary-0 | 10.01:1 | AAA | OK |
| 자동 라벨 배지 × primary 표면 | primary-7 × primary-0 | 9.42:1 | AAA | OK |
| 승인 버튼 흰 텍스트 × primary 배경 | #fff × primary-5 | 4.55:1 | AA(여유 근소) | OK — DS known_gaps 문서화된 한계(주조색 자체 특성), 본문 텍스트를 primary 표면에 얹지 않는 원칙 준수 |
| 미해결 건수 배지 흰 텍스트 × error 배경 | #fff × error-5 | 4.56:1 | AA(여유 근소) | OK |
| 반려 버튼 텍스트 × 흰 배경 | error-6 × #fff | 5.95:1 | AA | OK |
| 라벨 칩(사람) 텍스트 × info 표면 | neutral-10 × info-1 | 14.98:1 | AAA | OK |
| 라벨 칩(차량) 텍스트 × secondary 표면 | neutral-10 × secondary-3 | 8.21:1 | AAA | OK |
| 객체 행 hover 텍스트 × row-hover(#FFFBEB) | neutral-8 × #FFFBEB | 11.67:1 | AAA | OK — `#FFFBEB`는 DS `do_rules`에 명시된 지정값(표 행 hover 전용, KRDS 정본에 대응 토큰 없어 DS 자신이 채택한 값 — 임의 발명 아님) |
| 카드 헤더 텍스트 × secondary 표면 | neutral-9 × secondary-0 | 14.39:1 | AAA | OK |

**raw hex 검출(D4)**: `grep -oE "#[0-9a-fA-F]{3,6}"` 결과 — `design-main.html`은 0건(전량 CSS 변수 참조). `design.css`는 `:root` 토큰 선언부에서만 DS 실제 hex 55건 검출(전부 DS `tokens.colors.*.hex`/`.scale` 원본값과 1:1 일치, 도입한 새 hex 없음). 예외 1건: `#ffffff`(흰색) — DS `tokens.colors`에 별도 white 토큰이 없어 카드/페이지 기본 배경·버튼 텍스트에 순수 백색을 직접 사용했다. KRDS 계열 DS 문서 어디에도 명시적 반례가 없고(모든 표면이 흰 배경을 전제로 대비 수치를 문서화), neutral 스케일 자체가 lightest step조차 `#f4f5f6`(순백 아님)이라 순백을 대체할 토큰이 없다 — ⚠️ 미정 토큰(DS에 `white`/`surface-base` 토큰 신설 검토 권고).

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | design-main.html | 검수 상세 전체(단일 페이지, 다이얼로그 3종 포함) |

- 공유 스타일: design.css
- 스크린샷: 없음(이번 배치 미촬영)

## § 역등록 기록 (Phase 5)

- `screen_design` ITEM 신규 생성: **SD-005** (`designs_screen: SCREEN-019`, status: draft, device: desktop)
- `upload_design_render(SD-005, render_id="main", surface="page")` 완료 — html/css 업로드 후 `get_design_render`로 재조회해 한글 손상 여부를 검증(§ 검증 방법 참조). CSS는 바이트 단위 완전 일치(30,976자). HTML은 서버 측 정규화(HTML 주석 전량 제거, SVG 자기종료 태그 재직렬화, `&ldquo;`/`&rdquo;` → 실제 곡선 인용부호 디코드, `onsubmit`/`inputmode` 속성 제거, 인라인 style 내 공백 정리)만 있었고 한글 단어열 diff 결과 실제 렌더 텍스트(배지·라벨·메모·다이얼로그 문구 등)는 전량 보존(주석에만 있던 한글 섹션 표시문만 삭제, 치환 0건).
- Phase 6(ui_component 보강)은 사용자 지시에 따라 이번 배치에서 수행하지 않았다. 위 "⚠️ 토큰/컴포넌트 미정" 섹션이 향후 등록 시 참고 목록이다.
