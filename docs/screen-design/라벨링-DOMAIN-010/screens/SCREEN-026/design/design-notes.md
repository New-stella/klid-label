---
screen: SCREEN-026
generated_at: 2026-08-11T07:30:00Z
screen_design_item: SD-006
design_render_urls: [/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-006/main.html]
surfaces: [main]
ds: DS-001 (KRDS Public)
source_wireframes: [wireframe.html]
generated_by: mc-logi-screen-design
---

# SCREEN-026 디자인 노트 — 프리셋 관리 화면

> Phase 0~5(키트 게이트 → 입력 합성 → 로컬 디자인 작성 → 검증 → 키트 반영 → screen_design 역등록)를
> 모두 완료했다. Phase 6(ui_component 보강)은 신규 컴포넌트 후보를 표로 안내만 하고 실제 등록은
> 사용자 확인 후 별도 진행 대상으로 남긴다(이번 배치에서 미수행).

## § 디자인 결정 (와이어프레임 대비 추가분)

- **상태별**: 조회 성공(정상) 카드 그리드 아래에 "참고" 표기 점선 박스로 로딩 스켈레톤(제목/배지/본문
  2줄/칩 3개 shimmer)·빈 상태(📦 아이콘 + 안내 + "새 프리셋 만들기" 버튼)·조회 실패(⚠ 아이콘 + 안내 +
  "다시 시도" 버튼) 3종을 나란히 병기했다. 세 상태는 동시에 나타나지 않는다는 점을 라벨 문구로 명시했다
  (색만으로 구분하지 않고 아이콘+텍스트 병기, DS dont_rules 준수).
- **데이터 밀도**: 9개 카드(첫 페이지, 9건/페이지 변형 채택)에 라벨 개수 1~12개까지 편차를 둔 실제
  라벨명(차량/화염/침수영역/열원 등 도메인 용어)으로 채웠고, 6개 초과 시 "+N" 칩으로 접는 규칙을
  실제로 재현했다(카드 1: 8개 중 6개+2, 카드 5: 9개 중 6개+3, 카드 8: 12개 중 6개+6). 설명 없는 프리셋은
  "설명이 없습니다."로, 미매핑 프리셋은 이벤트 배지에 "미매핑"으로 채웠다. 카드 7에는 라벨 마스터에
  더 이상 없는 미연결(legacy) 코드 칩("구형카메라전용 · 미연결")을 실제로 배치해 그 표시 규칙(형태 배지
  숨김·별도 색)을 시연했다.
- **시각 위계**: 페이지 헤더(제목+동적 부제+프리셋 추가 버튼) → 카드 그리드(1차 정보) → 상태 참고
  박스(보조 문서) → 페이지네이션 순으로 여백 리듬(xl/lg 토큰)을 두어 절차를 구분했다. 카드 내부는
  제목+이벤트배지(1차) / 수정·복제·삭제 ghost 액션(보조, 우측 정렬) / 설명(보조 텍스트) / 라벨 칩(데이터)
  / 푸터 캡션(라벨수·수정일) 순으로 그루핑했다. 모달의 "만들기"(primary)와 "취소"(secondary), 삭제
  다이얼로그의 "삭제"(destructive)와 "취소"(secondary)로 1차/보조 액션을 색으로 구분했다.
- **컴포넌트 디테일**: 카드 hover 시 shadow-sm→shadow-md 전환 + border 강조, 버튼 전종 hover(배경 톤
  변화)·focus-visible(3px outline+2px offset)·disabled(opacity 0.5)를 반영했다. 라벨 마스터 체크박스
  멀티셀렉트는 실제로 8/20개 선택 상태(체크 8·미체크 2)로 채웠고 선택 개수 카운터를 굵게 강조했다.
  텍스트영역에는 글자수 카운터("38 / 500자")를 인라인 도움말 위에 병기했다.
- **접근성/공공 제약**: 본문 15~19px(body-sm/md 사용, design-prompt 지시대로 body-sm 이상), 카드 제목
  17px, 모든 클릭 가능 요소를 `button`/`a`/`label`로 구현(`div onclick` 없음), 버튼 최소 높이 44px(ghost
  변형은 밀집 카드 액션 전용으로 36px 예외 — 카드 액션이 3개 나란히 배치되는 좁은 영역이라 텍스트
  ghost 버튼 크기를 줄였다. 단독 히트영역이 아니라 카드 우상단에 나란히 있어 넓은 클릭 여지가 있다),
  focus-visible 3px 링, 페이지네이션 버튼 36px(보조 내비게이션, 단일 페이지 이동 반복 클릭용). 장식적
  그라데이션·neumorphism·auto-play 없음(스켈레톤 shimmer 애니메이션은 "로딩 중" 상태를 알리는 기능적
  움직임이라 장식 애니메이션 금지 규칙과 무관).
- **AI slop 회피**: 토큰을 단순 적용하는 데 그치지 않고 ①이벤트 배지 6종에 서로 다른 semantic 색을
  의미에 맞게 배정(교통사고=warn, 화재/산불=error, 폭력=error 진한 단계, 침수=info, 이상행동(유괴)=
  secondary, 미매핑=neutral)해 한 화면에서 이벤트 유형을 색으로도 빠르게 스캔할 수 있게 했다(단 라벨
  텍스트를 항상 함께 표기해 색 단독 의존은 아니다) ②라벨 칩과 그 안의 형태(BBOX/POLYGON) 서브배지를
  시각적으로 중첩된 2단 정보로 구성해 "이름+형태"를 한 칩에서 읽히게 했다(칩 안에 흰 배경의 작은 형태
  태그) ③모달의 라벨 체크박스 목록에 실제 스크롤 가능한 높이 제한(260px)을 둬 "라벨 마스터가 실제로는
  20개 넘게 있고 스크롤해서 고른다"는 실제 사용 감각을 재현했다.
- **⚠️ 배치 조정 없음**: 이 화면은 SCREEN-005와 달리 컴포넌트 배치를 재해석할 필요가 없었다 — 와이어
  프레임의 5개 섹션(헤더/카드그리드/페이지네이션/편집모달/삭제확인)을 그대로 페이지 흐름 순서(헤더→
  그리드→페이지네이션)와 오버레이 쇼케이스(모달+다이얼로그)로 옮겼을 뿐 순서·구성 변경은 없다.

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 페이지 헤더(제목/부제/액션) | UI-012 PageHeader | title-lg 22/700, body-sm 15/400 | breadcrumb는 UI-013 Breadcrumb 구성 반영(현재 위치 nav) |
| 프리셋 추가/새 프리셋 만들기 버튼 | UI-001 Button (variant=primary) | button 17/500, primary-5/6 | 헤더·빈 상태 안내 양쪽에서 동일 컴포넌트 재사용 |
| 카드 컨테이너 | UI-011 Card (CardHeader/CardTitle/CardAction/CardContent/CardFooter 조합) | radius-lg 8px, shadow-sm→md(hover), spacing lg 24px(카드 내부 패딩) | CardAction에 수정/삭제/복제 3버튼 배치 |
| 카드 액션(수정/삭제/복제) | UI-001 Button (variant=ghost) | button 17/500(밀집 배치로 sm 36px 예외 적용) | 삭제는 hover 시 error 톤으로 전환(is-danger) |
| 이벤트 배지 | UI-016 EventTypeBadge | warn/error/info/secondary/neutral 0·6 조합 | 미매핑은 neutral 폴백(카탈로그의 "빈 문자열이면 빈 배지" 대신 "미매핑" 텍스트 배지로 명시 표현) |
| 라벨 코드 칩 | UI-092 PresetCodeChip | primary-0/7(연결), neutral-0/muted(미연결), radius-full | 형태 배지는 연결된 코드에만 표시(카탈로그 규칙 그대로) |
| "+N" 초과 칩 | ⚠️ 미정(전용 UI-NNN 없음 — PresetCodeChip과 유사하되 개수 표시 전용, neutral 톤으로 이 디자인이 신규 정의) | neutral-1/muted | 앞 6개 초과 라벨 축약 표시 |
| 클라이언트 페이지네이션 | UI-008 Pagination | body-sm 15/400, primary-5(활성 페이지) | 9건/페이지, 1페이지 초과 시에만 노출(이 디자인은 2페이지 상태를 시연) |
| 로딩 스켈레톤 | UI-033 Skeleton | neutral-0/1(shimmer gradient) | 카드 6개 변형을 1개 대표 카드로 압축 표시(참고용) |
| 빈 상태 | UI-020 EmptyState | neutral-0(아이콘 배경), body-sm | action 버튼 포함(새 프리셋 만들기) |
| 조회 실패 | UI-021 ErrorState | error-0/6(아이콘 배경/색) | onRetry 버튼 포함 |
| 프리셋 편집 모달 | UI-091 PresetEditModal(구성 요소는 UI-004 Modal 상당) | shadow-lg, radius-lg, spacing lg | 신규/수정 공용, 이 디자인은 신규(새 프리셋 만들기) 상태로 시연 |
| 프리셋 이름 입력 | UI-002 Input | body-sm 15/400, radius-md | required 표시(err-5 asterisk) |
| 설명 입력 | UI-027 Textarea | body-sm, caption(글자수 카운터) | field-sizing 자동 확장 특성은 유지(실제 구현에서 반영) |
| 매핑 이벤트 타입 선택 | UI-003 Select | body-sm 15/400, radius-md | 서버 동적 옵션(이벤트유형 표시명) — 이 디자인은 6종 프리셋 예시로 채움 |
| 라벨 마스터 체크박스 멀티셀렉트 | UI-024 Checkbox(멀티) + ⚠️ 미정(목록 컨테이너·행 레이아웃 전용 UI-NNN 없음, PresetEditModal의 라벨 선택 슬롯을 이 디자인이 직접 구성) | primary-5(체크 상태), border, radius-md | 형태(BBOX/POLYGON)는 UI-024와 별개의 읽기전용 텍스트 배지로 병기 |
| 미연결 경고 배너 | UI-103 AlertBanner(variant=info 상당, Alert 역할) | info-0/7 | "저장 시 자동 제외" 안내 텍스트 포함 |
| 만들기/저장·취소 버튼 | UI-001 Button (primary/secondary) | button 17/500 | 모달 하단 우측 정렬 |
| 삭제 확인 다이얼로그 | UI-005 ConfirmDialog(variant=danger) | shadow-lg, error-5(삭제 버튼) | "되돌릴 수 없습니다" 문구 포함(카탈로그 usage_example 지침 준수) |

> ⚠️ 표에 `⚠️ 미정`으로 표기한 2개 영역("+N" 초과 칩, 라벨 체크박스 목록 컨테이너)은 `_shared/ui-catalog.md`
> 108건 중 전용 컴포넌트가 없어 이 디자인이 인접 컴포넌트(PresetCodeChip·Checkbox)를 조합해 새로
> 표현했다. Phase 6에서 이 신규 조합을 카탈로그 보강 후보로 안내했다(등록은 사용자 확인 후).

## § 접근성 검증 (WCAG 대비 — Phase 3 D5)

동봉 `scripts/contrast_checker.py`로 실제 사용한 텍스트/배경 조합을 전수 검사했다(DS 토큰의 *기존* hex 값만
사용, 새 색 추가 없음).

| 조합 (텍스트 × 배경) | 토큰명 | 대비 | 판정 | 조치 |
|---|---|---|---|---|
| 본문/제목 × white(카드·모달 배경) | neutral-9(#1e2124) × white | 16.18:1 | AAA | OK |
| 본문/제목 × 페이지 배경 | neutral-9 × neutral-0(#f4f5f6) | 14.82:1 | AAA | OK |
| 보조텍스트(설명/캡션) × white | neutral-6(#58616a) × white | 6.3:1 | AA | OK |
| 보조텍스트 × 회색 표면(상태 참고 박스) | neutral-6 × neutral-0 | 5.77:1 | AA | OK (DS do_rules: 60단 이상 사용 지침 준수) |
| "+N" 칩 텍스트 × neutral-1 배경 | neutral-6 × neutral-1(#e6e8ea) | 5.13:1 | AA | OK |
| 버튼 텍스트 × primary 배경 | white × primary-5(#256ef4) | 4.55:1 | AA | OK (DS known_gaps 기재: 여유 적음 — 텍스트를 primary 표면에 추가로 얹지 않음) |
| 버튼 텍스트 × destructive 배경 | white × error-5(#de3412) | 4.56:1 | AA | OK |
| 필수 표시(*) × white | error-5 × white | 4.56:1 | AA | OK |
| 라벨 칩 텍스트 × primary-0 배경 | primary-7(#083891) × primary-0(#ecf2fe) | 9.42:1 | AAA | OK |
| 라벨 칩 형태 서브배지 × white | primary-6(#0b50d0) × white | 6.83:1 | AA | OK |
| 이벤트 배지(교통사고) × warn-0 | warn-6(#8a5c00) × warn-0(#fff3db) | 5.29:1 | AA | OK |
| 이벤트 배지(화재/산불) × error-0 | error-6(#bd2c0f) × error-0(#fdefec) | 5.31:1 | AA | OK |
| 이벤트 배지(폭력) × error-0 | error-7(#8a240f) × error-0 | 8.01:1 | AAA | OK |
| 이벤트 배지(침수) × info-0 | info-6(#096ab3) × info-0(#e7f4fe) | 5.04:1 | AA | OK |
| 이벤트 배지(이상행동/유괴) × secondary-1 | secondary-7(#063a74) × secondary-1(#d6e0eb) | 8.43:1 | AAA | OK |
| 미연결 경고 배너 텍스트 × info-0 | info-7(#085691) × info-0 | 6.82:1 | AA | OK |

- **전 조합 AA 이상 통과 — 재작성 필요 없음.**
- ⚠️ 참고(범위 밖 관찰, 실패 아님 — SCREEN-005 design-notes와 동일 관찰): 카드·입력·칩 테두리에 쓴
  `--border`(neutral-2, #cdd1d5)는 white 배경 대비 1.54:1로 WCAG 1.4.11 비텍스트 3:1 기준에는 못 미친다.
  이 스킬의 D5 게이트는 텍스트/배경 조합만 검사 대상으로 하며(notes-template.md 명시), 테두리는 그
  범위 밖이다. neutral-2는 DS 팔레트 값 그대로이고 이 프로젝트 전 화면이 공유하는 기본 테두리 톤이라
  이 화면 단독으로 진하게 바꾸지 않았다.

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | design-main.html | 전체 페이지(page) — 헤더·카드 그리드(9건 + 상태 참고 박스)·페이지네이션 + 오버레이 쇼케이스(편집 모달·삭제 확인) |

- 공유 스타일: design-main.css — 게시본(logicraft 렌더)과 바이트 동일한 로컬 미러이며 design-main.html 이 참조하는 정본이다. 구 작성본 design.css 는 이후 수정이 반영되지 않아 게시본과 어긋난 채 남아 있었으므로 삭제했다(2026-08-18).
- 스크린샷: (없음 — 브라우저 프리뷰 및 logicraft 렌더 URL로 확인)

## § 역등록 기록 (Phase 5 — 완료)

- `screen_design` ITEM 신규 생성: **SD-006** (`designs_screen: SCREEN-026`, domain: DOMAIN-010, status: draft)
- `upload_design_render` 호출: render_id=`main`, surface=`page` (SCREEN-026의 `static_renders`(wireframe.html)와
  같은 surface·render_id로 맞춰 비교 뷰가 형성되도록 함)
  - html url: `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-006/main.html`
  - css url: `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-006/main.css`
  - action: `add` (신규), 결과 `current_version: 2`
- **역등록 무결성 검증(2026-08-11)**: `https://logicraft.cudo.co.kr:10000/api/uploads/designs/{project}/SD-006/main.{css,html}`
  를 무인증 재다운로드해 로컬 원본과 바이트 단위 대조했다. CSS는 21,370바이트로 **완전 일치**(diff 0줄).
  HTML은 서버가 `<!-- ... -->` 주석 노드를 제거하고 인라인 `style` 속성의 공백·세미콜론을 정규화하는
  것 외에는 **완전 일치**(주석 제거 후 diff: 공백 2건 + 세미콜론 1건만, 전부 비-한글·비-콘텐츠 영역).
  한글 손상 0건, raw hex 유입 0건.
- Phase 6(ui_component 보강)은 아래 § 신규 컴포넌트 후보 안내만 수행했고 등록은 미수행 — 사용자 확인
  후 별도 진행 대상으로 남는다.

## § Phase 6 — ui_component 보강 권고 (안내만, 등록 보류)

`_shared/ui-catalog.md`(108건) 대조 결과 이 디자인에서 사용한 컴포넌트는 대부분 기존 카탈로그로
매핑됐다. 카탈로그에 없는 신규 후보는 2건이며, **이번 배치에서는 등록하지 않고 표로만 안내**한다.

| 후보 이름 | category | variants | 쓰인 화면 | 비고 |
|---|---|---|---|---|
| PresetLabelOverflowChip ("+N" 초과 칩) | display | default(neutral 톤, 숫자만 표시) | SCREEN-026 | PresetCodeChip(UI-092)과 유사하나 라벨 정보 없이 "+N" 숫자만 표시하는 축약 전용 칩. UI-092의 variant로 흡수할지, 별도 컴포넌트로 등록할지는 논의 필요 |
| PresetLabelPicker (라벨 마스터 체크박스 목록 컨테이너) | input | default(스크롤 가능 목록, 행당 체크박스+라벨명+형태 읽기전용 배지) | SCREEN-026 | UI-091 PresetEditModal의 "라벨 마스터 체크박스 멀티셀렉트" 슬롯을 실제로 구성하는 하위 컴포넌트. Checkbox(UI-024)를 조합한 상위 목록 레이아웃이라 재사용 가치가 있음 |

등록을 원하시면 말씀해 주세요 — 동의 시 `register_ui_components`로 신규 등록 후 `mc-logi-screen-kit`
SYNC로 로컬 카탈로그를 갱신합니다(기존 UI-091/UI-092 등의 수정은 이 스킬 범위 밖이며 `mc-logi-update`
소관입니다).
