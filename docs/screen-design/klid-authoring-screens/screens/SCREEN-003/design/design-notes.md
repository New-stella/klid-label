# SCREEN-003 접근 거부 화면 — 디자인 노트

| 항목 | 값 |
|---|---|
| 대상 | SCREEN-003 접근 거부 화면 (`/forbidden`) |
| 골격 기준 | `screens/SCREEN-003/SCREEN-003.md` **v10** (키트 session 5 · stale false) |
| 디자인 시스템 | DS-001 KRDS Public **v8** (`_shared/design-system.md`) |
| 성격 | **신규 작성**. 이 화면에는 기존 고충실 시안이 없었다 |
| 기술 제약 | 무 JS · CSS-only, 셸(GNB/LNB/푸터) 제외 |
| surface | `main` (page) — 와이어프레임과 동일 |

---

## § 골격 대조 — 섹션 2개 · 컴포넌트 5개 전수

| 골격 | 컴포넌트 | 시안에서 그린 자리 |
|---|---|---|
| ① 접근 거부 안내 hero · role=hero · layout=stack | `LockIconBadge`(Custom, 잠금 아이콘 · 붉은 원형 배지) | `.lock-badge` 72px 원형 — `error.scale[0]` 배경 + `error.scale[6]` 잠금 아이콘 + `error.scale[2]` 테두리 |
| | `Heading`(이 화면에 접근할 수 없습니다) | `h2.deny-title`(title-lg) |
| | `Description`(Custom, 현재 역할로는 이 페이지에 접근 권한이 없습니다.) | `.deny-desc`(body-md, neutral 60단) |
| ② 현재 역할 표시 + 대시보드 이동 · role=main · layout=stack | `Badge`(현재 역할 배지, binds_to=claims.role) | `.role-row` 안 `'현재 역할'` 라벨 + `.role-badge` |
| | `Button`(대시보드로, variant=primary, navigate('/')) | 카드 하단 `button.btn-primary.btn-block` |

- 골격이 `<main role="alert">` 를 명시하므로 카드 자체에 `role="alert"` 를 부여했다.
- `consumes_apis` 는 비어 있다(골격: "서버 호출 없음"). 시안에도 호출을 암시하는 요소가 없다.

### 골격에 없어 그리지 않은 것

- **뒤로가기** — 골격에 없다. 되돌아가면 다시 막히는 자리라 유일한 출구인 '대시보드로' 하나만 뒀다.
- **권한 요청·문의 링크** — 골격에 없다. 역할 부여 동선은 SCREEN-002 가 담당한다.
- **막힌 화면 이름 표시** — 골격에 없고, 접근 권한이 없는 화면의 이름을 알려주는 것 자체가
  정보 노출이 될 수 있어 지어내지 않았다.

---

## § 디자인 결정 (골격이 규정하지 않은 부분)

### 1. 셸 밖 화면이라 기준본의 `page-head + breadcrumb` 관례를 쓰지 않았다

기준본 SD-004 는 셸 안 페이지라 breadcrumb 이 화면 콘텐츠지만, 이 화면은 상단·좌측 메뉴가 없다.
화면명·경로는 **시안 표지(`.sheet-head`)** 로 분리했다.

★ 구현 시 **`.sheet-head` 와 `.state-frame`·`.state-caption` 은 화면에 그리지 않는다.**

### 2. 붉은 배지를 "경고"가 아니라 "잠금"으로 그렸다

골격이 지정한 것은 잠금 아이콘 + 붉은 원형 배지다. 붉은색을 큰 면적으로 채우면 DS dont_rule
("Brand·강한 색을 큰 면적 배경으로 채우지 않는다")에 어긋나고 실패·오류로 오인된다. 그래서
**연한 tint 배경(`error[0]`) + 진한 아이콘(`error[6]`) + 얇은 테두리**로 두어, 붉은 신호는 유지하되
면적을 쓰지 않았다. 이건 장애가 아니라 **권한 상태 안내**다.

### 3. '현재 역할'을 별도 표면에 담았다

역할 배지를 제목 밑에 그냥 두면 hero 의 일부로 읽혀 "이 화면이 요구하는 역할"로 오해된다.
회색 표면 한 줄(`.role-row`)로 감싸고 `현재 역할` 라벨을 붙여, 이것이 **내 상태**임을 분명히 했다.

### 4. 역할 배지 4종을 카탈로그 매핑 그대로 그렸다

`UI-110 RoleBadge` 는 variant 별 배경·글자 토큰을 이미 규정한다(reviewer=primary-05×primary-60,
worker=secondary-05×secondary-70, portal=neutral-05×border×neutral-80, unassigned=warn-05×warn-70 +
경고 아이콘 병기). 시안은 그 매핑을 그대로 썼고 색을 새로 정하지 않았다. 카탈로그가 명시한
"색상 단독으로 전달하지 않기 위한 아이콘 병기"도 4종 전부에 적용했다.

`unassigned` variant 는 골격의 **"매핑 없으면 원본 role 코드"** 자리에 대응시켰다 — 카탈로그의
'미배정' 과 골격의 '미매핑' 은 같은 상황(아는 역할이 아님)이라 별도 variant 를 만들지 않았다.

### 5. 도달 경로 2종을 참고 영역에 남겼다

골격 설명은 "역할 기반 또는 채널 기반 접근 제어를 통과하지 못한 경우"라고 두 경로를 규정하는데,
화면에는 그 구분이 드러나지 않는다(문구가 하나뿐). 구현자가 어느 가드에서 이 화면으로 보내야
하는지 알아야 하므로 참고 영역에 두 경로를 적었다.

---

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 역할 배지 | **UI-110 RoleBadge** | reviewer `primary[0]×primary[6]` · worker `secondary[0]×secondary[7]` · portal `neutral[0]×neutral[8]`+테두리 · unassigned `warn[0]×warn[7]` | 카탈로그 지정 매핑 그대로 · `radius.full` |
| 대시보드 이동 | **UI-001 Button** | `primary.scale[5]` → hover `primary.scale[6]` · `button 17/500` | variant=primary · block |
| 카드 표면 | **UI-011 Card** | `radius.lg` · `shadow.sm` · `neutral.scale[2]` | DS: 카드는 shadow.sm 만 |
| 잠금 배지 | ⚠️ 미정 (`LockIconBadge` 카탈로그 미등록) | `error.scale[0]`(배경) · `error.scale[2]`(테두리) · `error.scale[6]`(아이콘) · `radius.full` | 골격 `custom_name` 그대로. 신규 컴포넌트 후보 |
| '현재 역할' 줄 | ⚠️ 미정 | `neutral.scale[0]`(배경) · `neutral.scale[2]`(테두리) · `neutral.scale[7]`(라벨) · `radius.md` | 신규 컴포넌트 후보 |
| '참고' 표식 | **UI-014 StatusBadge** | `neutral.scale[1]` × `neutral.scale[7]` · `radius.full` | |
| 본문·보조 텍스트 | — (타이포 ladder) | `title-lg 22` · `body-md 17` · `body-sm 15` · `label 14` · `caption 14` | ladder step 그대로 |
| 시안 표지·상태 프레임 | ⚠️ 미정 | — | **시안 전용** — 구현 대상이 아니다 |

---

## § 접근성 검증 (WCAG 2.1 — 동봉 `scripts/contrast_checker.py`)

| 조합 | 토큰 | 대비 | 판정 |
|---|---|---|---|
| 제목 | `neutral[9]` × `#ffffff` | 16.18:1 | AAA |
| 포털 역할 배지 | `neutral[8]` × `neutral[0]` | 11.08:1 | AAA |
| 작업자 역할 배지 | `secondary[7]` × `secondary[0]` | 10.01:1 | AAA |
| 미매핑 역할 배지 | `warn[7]` × `warn[0]` | 8.43:1 | AAA |
| '현재 역할' 라벨 | `neutral[7]` × `neutral[0]` | 7.95:1 | AAA |
| 검수자 역할 배지 | `primary[6]` × `primary[0]` | 6.09:1 | AA |
| 상태 번호 배지 | `secondary[6]` × `secondary[0]` | 6.39:1 | AAA |
| 설명 문장 | `neutral[6]` × `#ffffff` | 6.30:1 | AA |
| 참고 영역 보조 텍스트 | `neutral[6]` × `neutral[0]` | 5.77:1 | AA |
| 잠금 아이콘(비텍스트) | `error[6]` × `error[0]` | 5.31:1 | PASS(3:1) |
| 도달 경로 목록 본문 | `neutral[8]` × `#ffffff` | 12.10:1 | AAA |
| 주요 버튼 글자 | `#ffffff` × `primary[5]` | 4.55:1 | AA |

**미달 0건.** 카탈로그가 RoleBadge variant 별로 적어 둔 대비 수치(6.09 / 10.01 / 8.43)와 실측이 일치했다.

### 그 밖의 접근성 처리

- 카드에 `role="alert"` (골격 지정 `<main role="alert">` 대응).
- 역할 배지 4종 전부 **아이콘 + 한글 라벨**을 함께 표기해 색 단독 전달을 피했다(DS dont_rule).
- 장식 아이콘·잠금 배지는 `aria-hidden="true"`, 배지 안 아이콘도 동일.
- 주요 버튼 글자 대비 4.55:1 은 DS `known_gaps` 가 기록한 정본 주조색의 성질이라 수용한다.

---

## § 실측 검증 (브라우저 · Chromium)

| 검사 | 결과 |
|---|---|
| 내용 잘림 | **0건** (1280·360, 2회 재현) |
| 문서 가로 넘침 | **0건** (1440 / 1280 / 768 / 480 / 360 / 320) |
| 참고 영역 속성 전수 | **2/2 기준 일치** — `bg rgb(244,245,246)` + 4면 `2px dashed rgb(138,148,158)` |
| 44px hit area | 미달 0건 |
| raw hex | **0건** — hex 78건 전부 DS 토큰 값으로 추적됨 |

---

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | `design.html` | 와이어프레임 `main` (surface=page) |

- 공유 스타일: `design.css`

---

## § 신규 컴포넌트 후보 (Phase 6)

| 이름 | category | 쓰인 자리 | 비고 |
|---|---|---|---|
| `LockIconBadge` | display | 접근 거부 hero | 골격 `custom_name` 그대로. 원형 tint 배지 + 아이콘 |

---

## § 역등록 기록 (Phase 5)

| 항목 | 값 |
|---|---|
| SD ITEM | SD-019 |
| render_id | `main` (와이어프레임과 동일) |
| surface | `page` |
| label | 접근 거부 화면 — 고충실 디자인 |
| action | add |
| 게시 URL | `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-019/main.html` (+ `main.css`) |
| 왕복 대조 | 업로드 원문 ↔ 게시본 문자 단위 대조 — **미설명 잔차 0건**. 차이는 전부 알려진 서버 정규화(lang 제거 · CSS link 주입 · void self-closing · `viewBox` 소문자화 · 공백)다. CSS 는 원문 해시 일치 |
