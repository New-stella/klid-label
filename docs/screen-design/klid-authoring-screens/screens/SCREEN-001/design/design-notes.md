# SCREEN-001 세션 인계 진입 화면 — 디자인 노트

| 항목 | 값 |
|---|---|
| 대상 | SCREEN-001 세션 인계 진입 화면 (`/ingress`) |
| 골격 기준 | `screens/SCREEN-001/SCREEN-001.md` **v13** (키트 session 5 · stale false) |
| 디자인 시스템 | DS-001 KRDS Public **v8** (`_shared/design-system.md`) |
| 성격 | **신규 작성**. 이 화면에는 기존 고충실 시안이 없었다 |
| 기술 제약 | 무 JS · CSS-only, 셸(GNB/LNB/푸터) 제외 |
| surface | `main` (page) — 와이어프레임과 동일 |

---

## § 골격 대조 — 섹션 2개 · 컴포넌트 2개 전수

골격이 규정한 것을 하나도 빠뜨리지 않았는지 항목 단위로 대조한다.

| 골격 | 컴포넌트 | 시안에서 그린 자리 |
|---|---|---|
| ① 세션 확인 (로딩) · role=main · layout=stack | `Spinner`(Custom, state=loading, size=lg, label='세션을 확인하는 중', role=status aria-live=polite) | 상태 프레임 1 — 중앙 카드 안 `.spinner`(48px) + 라벨. `role="status" aria-live="polite"` 를 `.spinner-block` 에 부여 |
| ② 인증 실패 안내 · role=main · layout=stack | `Alert`(variant=destructive, state=error, role=alert aria-live=assertive) | 상태 프레임 2 — 같은 카드 자리에 `.alert.alert-error`. `role="alert" aria-live="assertive"` 부여 |

- 두 섹션은 **같은 화면의 서로 배타적인 두 상태**다(하나가 보이면 다른 하나는 없다). 그래서 카드를
  두 개 나열하지 않고 **상태 프레임 2개**로 나눠, 각 프레임이 그 순간의 화면 전체를 담게 했다.
- `consumes_apis` 는 비어 있다(골격 명시: "API 호출 없음"). 시안에도 호출을 암시하는 요소를 넣지 않았다.

### 골격에 없어 그리지 않은 것

- **재시도 버튼** — 골격에 없다. 이 화면은 자동 이동이 전제이고 실패 시 로그인 화면으로 보내므로
  머무를 수 있는 조작을 만들지 않았다.
- **진행률 표시** — 확인에 걸리는 시간을 알 수 없다. DS `ProgressBar` 설명이 "진행률을 알 수 없는
  대기는 Spinner" 라고 규정하므로 Spinner 만 뒀다.

---

## § 디자인 결정 (골격이 규정하지 않은 부분)

### 1. 셸 밖 화면이라 기준본의 `page-head + breadcrumb` 관례를 쓰지 않았다

기준본 SD-004(SCREEN-009)는 **셸 안 페이지**라 breadcrumb(`작업 목록 › 영상 상세`)과 페이지 제목이
곧 화면 콘텐츠다. 이 화면은 상단 메뉴·좌측 메뉴가 없는 진입 화면이라 breadcrumb 이 가리킬 상위가
없고, 실제 화면에는 제목조차 없다(스피너 하나뿐).

그래서 화면명·경로는 **시안 표지(`.sheet-head`)** 로 분리했다 — 문서 메타이지 화면 콘텐츠가 아님이
드러나도록 회색 보조 텍스트 + 하단 구분선으로 처리했다. 와이어프레임 생성기가 붙이는
`SCREEN-001 / 세션 인계 진입 화면` 머리줄도 같은 성격이라 이 표지가 그 자리를 대신한다.

★ 구현 시 **`.sheet-head` 와 `.state-frame`·`.state-caption` 은 화면에 그리지 않는다.** 실제 화면은
`.state-stage` 안의 `.auth-card` 하나다.

### 2. 화면 노출 텍스트에서 구현 식별자를 걷어냈다

골격 설명문은 `localStorage(klid-jwt-token)`·`VITE_DEV_TOKEN`·`?next=` 같은 구현 식별자를 담고 있다.
확정 관례(화면 노출 텍스트에 영문 구현 식별자 금지)에 따라 시안에서는 전부 우리말로 옮겼다 —
"브라우저 저장소 또는 쿠키", "미리 설정해 둔 개발용 대체 토큰", "원래 접근하려던 위치를 함께 넘겨".
의미는 유지하고 표기만 바꾼 것이며 골격을 줄이지 않았다.

### 3. 실패 문구 2종을 참고 영역으로 뺐다

골격은 사유별 문구를 두 가지로 규정한다(인증 정보 없음 / 만료). 실제 화면에는 **하나만** 나타나므로
상태 프레임에는 대표 1종만 그리고, 나머지는 참고 영역(4면 dashed)에 나란히 뒀다. 이것이 기준본이
확립한 "실제 화면 = 대표 상태 하나, 변형 = 참고 영역" 관례다.

### 4. 처리 순서를 번호 목록으로 남겼다

이 화면의 실질은 보이는 요소가 아니라 **진입 직후 1회 수행하는 분기**다. 그 분기가 어디서 멈추면
② 상태가 되는지를 구현자가 알아야 하므로 참고 영역에 4단계 목록으로 적었다. 각 단계의 예외·단서는
본문보다 한 단 낮은 캡션으로 눌러 위계를 만들었다.

### 5. 안내 문구는 "무엇을 기다리는지" 까지 적었다

골격의 라벨은 `세션을 확인하는 중` 한 줄이다. 아무 설명 없는 스피너는 장애로 오인되기 쉬워
보조 문장("관제서버 또는 포털에서 전달한 인증 정보를 확인하고 있습니다. 확인이 끝나면 자동으로
이동합니다.")을 덧붙였다. 라벨 자체는 골격 그대로 두고 아래에 덧댄 것이라 골격 변경이 아니다.

---

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 로딩 표시 | **UI-032 Spinner** | `primary.scale[1]`(테두리) · `primary.scale[5]`(진행 호) · `radius.full` | size=lg → 48px. `role=status aria-live=polite` |
| 실패 안내 | **UI-103 AlertBanner** | `error.scale[0]`(배경) · `error.scale[2]`(테두리) · `error.scale[7]`(제목) · `neutral.scale[8]`(본문) | variant=destructive · `role=alert aria-live=assertive` |
| 카드 표면 | **UI-011 Card** | `radius.lg` · `shadow.sm` · `neutral.scale[2]`(테두리) | DS: 카드는 shadow.sm 만 |
| '참고' 표식 | **UI-014 StatusBadge** | `neutral.scale[1]` × `neutral.scale[7]` · `radius.full` | pill 은 배지 전용(DS shape_principle) |
| 본문·보조 텍스트 | — (타이포 ladder) | `body-lg 19` · `body-md 17` · `body-sm 15` · `caption 14` | ladder step 그대로 |
| 경로 칩 | ⚠️ 미정 | `neutral.scale[1]` × `neutral.scale[7]` · `mono 14` | **시안 표지 전용** — 화면 요소가 아니라 구현 대상이 아니다 |
| 상태 프레임 | ⚠️ 미정 | — | **시안 전용** — 구현 대상이 아니다 |
| 처리 순서 목록 | ⚠️ 미정 | `primary.scale[0]` × `primary.scale[7]`(번호) | **참고 영역 전용** — 구현 대상이 아니다 |

---

## § 접근성 검증 (WCAG 2.1 — 동봉 `scripts/contrast_checker.py`)

이 화면에서 실제로 쓴 텍스트 × 배경 조합 전수.

| 조합 | 토큰 | 대비 | 판정 |
|---|---|---|---|
| 카드 위 제목·본문 | `neutral[9]` × `#ffffff` | 16.18:1 | AAA |
| 회색 표면 위 제목 | `neutral[9]` × `neutral[0]` | 14.82:1 | AAA |
| 오류 안내 본문 | `neutral[8]` × `error[0]` | 10.79:1 | AAA |
| 오류 안내 제목 | `error[7]` × `error[0]` | 8.01:1 | AAA |
| 오류 아이콘(비텍스트) | `error[6]` × `error[0]` | 5.31:1 | PASS(3:1) |
| 상태 캡션 | `neutral[7]` × `neutral[0]` | 7.95:1 | AAA |
| 경로 칩 | `neutral[7]` × `neutral[1]` | 7.07:1 | AAA |
| 카드 위 보조 텍스트 | `neutral[6]` × `#ffffff` | 6.30:1 | AA |
| 회색 표면 위 보조 텍스트 | `neutral[6]` × `neutral[0]` | 5.77:1 | AA |
| 흐름 단계 번호 | `primary[7]` × `primary[0]` | 9.42:1 | AAA |
| 상태 번호 배지 | `secondary[6]` × `secondary[0]` | 6.39:1 | AAA |

**미달 0건.** DS `do_rules` 의 "회색 표면 위 보조 텍스트는 60단 이상" 규칙을 지켜 `neutral[5]`(50단)을
회색 표면 위에 쓰지 않았다.

### 그 밖의 접근성 처리

- 로딩은 `role=status` + `aria-live=polite`, 실패는 `role=alert` + `aria-live=assertive` (골격 지정 그대로).
- 스피너에 `prefers-reduced-motion: reduce` 대응(회전 주기 900ms → 2400ms). 완전 정지시키지 않은 것은
  "처리 중"이라는 유일한 신호가 사라지기 때문이다.
- 장식 아이콘은 전부 `aria-hidden="true"`.
- 오류는 색만이 아니라 **아이콘 + 문구**를 함께 쓴다(DS do_rule).

---

## § 실측 검증 (브라우저 · Chromium)

애니메이션 동결(`animation:none`) 후 측정. 스피너 회전이 기하를 흔드는 것을 막기 위함.

| 검사 | 결과 |
|---|---|
| 내용 잘림 (`scrollHeight/Width` vs `client`) | **0건** (1280·360, 2회 재현) |
| 문서 가로 넘침 | **0건** (1440 / 1280 / 768 / 480 / 360 / 320) |
| 참고 영역 속성 전수 | **2/2 기준 일치** — `bg rgb(244,245,246)` + 4면 `2px dashed rgb(138,148,158)` |
| 44px hit area | 미달 0건 |
| raw hex (토큰 밖 색) | **0건** — 파일 내 hex 78건 전부 DS 토큰 값으로 추적됨 |

기준본 SD-004 가 360px 에서 96px 넘치는 미해결 항목이 있어 **같은 폭을 명시적으로 측정**했고,
이 화면은 320px 까지 넘침이 없다.

---

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | `design.html` | 와이어프레임 `main` (surface=page) |

- 공유 스타일: `design.css`
- 렌더 게시 시 `<link rel="stylesheet">` 를 제거하고 `css` 파라미터로 분리 전달한다.

---

## § 역등록 기록 (Phase 5)

| 항목 | 값 |
|---|---|
| SD ITEM | SD-017 |
| render_id | `main` (와이어프레임과 동일) |
| surface | `page` |
| label | 세션 인계 진입 화면 — 고충실 디자인 |
| action | add |
| 게시 URL | `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-017/main.html` (+ `main.css`) |
| 왕복 대조 | 업로드 원문 ↔ 게시본 문자 단위 대조 — **미설명 잔차 0건**. 차이는 전부 알려진 서버 정규화(lang 제거 · CSS link 주입 · void self-closing · `viewBox` 소문자화 · 공백)다. CSS 는 원문 해시 일치 |
