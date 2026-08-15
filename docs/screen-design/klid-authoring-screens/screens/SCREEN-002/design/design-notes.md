# SCREEN-002 역할 클레임 화면 — 디자인 노트

| 항목 | 값 |
|---|---|
| 대상 | SCREEN-002 역할 클레임 화면 (`/role-claim`) |
| 골격 기준 | `screens/SCREEN-002/SCREEN-002.md` **v12** (키트 session 5 · stale false) |
| 디자인 시스템 | DS-001 KRDS Public **v8** (`_shared/design-system.md`) |
| 성격 | **신규 작성**. 이 화면에는 기존 고충실 시안이 없었다 |
| 기술 제약 | 무 JS · CSS-only, 셸(GNB/LNB/푸터) 제외 |
| surface | `main` (page) — 와이어프레임과 동일 |

---

## § 골격 대조 — 섹션 3개 · 컴포넌트 5개 전수

| 골격 | 컴포넌트 | 시안에서 그린 자리 |
|---|---|---|
| ① 안내 헤더 · role=header · layout=stack | `Heading`(권한 부여 필요) | 카드 상단 `h2.auth-title`(title-lg) |
| | `SubText`(Custom, 관리자에게 받은 패스워드로 역할을 부여받으세요.) | 그 아래 `.auth-sub`(body-md, neutral 60단) |
| ② 역할 선택 · role=main · layout=form | `RadioGroup`(역할 선택, binds_to=role, options 2종) | `fieldset.radio-group` + `legend` + 카드형 라디오 2개 |
| ③ 권한 부여 폼 · role=main · layout=form | `Input`(관리자 패스워드, type=password, autoComplete=new-password, binds_to=adminPassword) | `.field` + `label[for]` + `input[type=password][autocomplete=new-password]` |
| | `Alert`(state=hidden, variant=destructive, 실패 시에만 role=alert) | 기본 상태에서는 **없음**. 참고 영역 ③ 에 노출 상태를 그렸다 |
| | `Button`(권한 부여 확인, variant=primary, triggers_api=API-007) | 카드 하단 `button.btn-primary.btn-block` |

- 골격의 `state: hidden` 을 그대로 지켜 **기본 화면에는 Alert 를 그리지 않았다.** 숨김 컴포넌트를
  대표 상태에 그려 넣으면 "항상 보이는 영역"으로 오해된다.
- `triggers_api: API-007` 은 제출 버튼에만 걸려 있고, 시안의 다른 요소는 어떤 호출도 암시하지 않는다.

### 골격에 없어 그리지 않은 것

- **역할 설명 링크·도움말 페이지** — 골격에 없다.
- **패스워드 표시 토글(eye 아이콘)** — 골격에 없고, 관리자 공유 패스워드라 화면에 평문 노출 경로를
  임의로 만들지 않았다.
- **취소·뒤로가기** — 골격에 없다. 이 화면은 역할이 없으면 다른 곳으로 갈 수 없는 게이트다.

---

## § 디자인 결정 (골격이 규정하지 않은 부분)

### 1. 셸 밖 화면이라 기준본의 `page-head + breadcrumb` 관례를 쓰지 않았다

기준본 SD-004 는 셸 안 페이지라 breadcrumb 이 화면 콘텐츠지만, 이 화면은 상단·좌측 메뉴가 없고
가리킬 상위도 없다. 화면명·경로는 **시안 표지(`.sheet-head`)** 로 분리해 문서 메타임을 드러냈다.

★ 구현 시 **`.sheet-head` 와 `.state-frame`·`.state-caption` 은 화면에 그리지 않는다.**

### 2. 세 섹션을 카드 3장이 아니라 카드 1장 안의 3블록으로 묶었다

골격은 섹션 3개지만 사용자에게는 **한 번에 끝내는 하나의 폼**이다. 카드를 셋으로 쪼개면 단계가
셋인 것처럼 읽혀 실제보다 무겁게 느껴진다. 대신 안내 헤더와 폼 사이에 구분선(`.auth-divider`)을
넣어 섹션 경계는 남겼다.

### 3. 라디오를 카드형으로 만들고 역할 설명을 덧붙였다

골격 옵션 라벨은 `작업자 (WORKER)` / `검수자 (REVIEWER)` 뿐이라, 처음 들어온 사용자가 무엇을 고를지
판단할 근거가 없다. 라벨은 **골격 그대로 두고** 그 아래 한 줄 설명을 덧댔다(작업자=라벨 제작·검수 요청,
검수자=배정·검수 승인·관리). 선택 영역은 44px 하한을 훨씬 넘는 카드(292×105)라 오조작이 어렵다.

선택 상태는 색만으로 구분하지 않는다 — 테두리 색 + 안쪽 1px 링 + 배경 tint 3중이라 색각 이상에서도
구분된다(DS dont_rule: 색만으로 상태 구분 금지).

### 4. 대표 상태를 "고르고 입력한 뒤"로 잡았다

초기 진입은 미선택 + 제출 비활성이지만, 그 상태를 대표로 그리면 화면의 주행동이 무엇인지 보이지
않는다. 대표 프레임은 정보량이 가장 많은 **선택·입력 완료 상태**로 두고, 비활성 초기 상태는 참고
영역 ① 로 뺐다. 기준본이 확립한 "대표 1종 + 변형은 참고 영역" 관례를 그대로 따른 것이다.

### 5. 실패 문구를 표로 정리했다

골격은 응답별 문구 5종을 산문으로 규정한다. 산문으로 두면 구현자가 어느 문구가 어느 응답인지
매번 되짚어야 하므로 **응답 × 문구** 2열 표로 옮겼다. 403 은 정상 동선에서 도달하지 않는다는 골격의
단서도 표 아래 각주로 남겼다 — 지우면 다음 사람이 "쓰이지 않는 문구"로 보고 없앨 수 있다.

### 6. 제출 진행 중에는 문구를 바꾸지 않고 스피너만 넣었다

골격은 "진행 중이면 제출 비활성"만 규정하고 문구 변경은 규정하지 않는다. 그래서 라벨은
`권한 부여 확인` 그대로 두고 앞에 18px 스피너만 붙였다. (문구를 바꾸는 SCREEN-004 와 다른 것은
그쪽 골격이 `'발급 중…'` 을 **명시**하기 때문이다 — 두 화면의 차이는 의도된 것이다.)

---

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 역할 선택 | **UI-026 RadioGroup** + **UI-025 Radio** | `primary.scale[0]`(선택 배경) · `primary.scale[5]`(테두리·링) · `neutral.scale[4]`(기본 테두리) · `radius.md` | 카드형. `legend` = label 14 |
| 패스워드 입력 | **UI-002 Input** (+ **UI-099 Field**) | `neutral.scale[4]`(테두리) · `neutral.scale[9]`(값) · `neutral.scale[6]`(플레이스홀더·도움말) · `radius.md` | min-height 44px |
| 제출 | **UI-001 Button** | `primary.scale[5]` → hover `primary.scale[6]` · `button 17/500` | variant=primary · block |
| 실패 안내 | **UI-103 AlertBanner** | `error.scale[0]` · `error.scale[2]` · `error.scale[7]` · `neutral.scale[8]` | variant=destructive |
| 성공 안내(참고) | **UI-103 AlertBanner** | `info.scale[0]` · `info.scale[2]` · `info.scale[7]` | 참고 영역 전용 |
| 카드 표면 | **UI-011 Card** | `radius.lg` · `shadow.sm` · `neutral.scale[2]` | DS: 카드는 shadow.sm 만 |
| 문구 표 | ⚠️ 미정 | 헤더 `secondary.scale[0]` · 행 hover `#FFFBEB` | **참고 영역 전용 — 구현 대상이 아니다.** 카탈로그의 `UI-007 DataTable` 은 서버 페이징 목록용(60vh 스크롤·sticky 헤더·빈 상태 내장)이라 이 정적 2열 표와 축이 다르다. 헤더 배경은 DS do_rule 지정, hover 색은 DS 가 지정한 정본 밖 값(known_gaps 기록) |
| '참고' 표식 | **UI-014 StatusBadge** | `neutral.scale[1]` × `neutral.scale[7]` · `radius.full` | |
| 시안 표지·상태 프레임 | ⚠️ 미정 | — | **시안 전용** — 구현 대상이 아니다 |

---

## § 접근성 검증 (WCAG 2.1 — 동봉 `scripts/contrast_checker.py`)

| 조합 | 토큰 | 대비 | 판정 |
|---|---|---|---|
| 카드 위 제목 | `neutral[9]` × `#ffffff` | 16.18:1 | AAA |
| 선택된 라디오 라벨 | `neutral[9]` × `primary[0]` | 14.40:1 | AAA |
| 표 헤더 | `neutral[8]` × `secondary[0]` | 10.76:1 | AAA |
| 표 행 hover 본문 | `neutral[8]` × `#FFFBEB` | 11.67:1 | AAA |
| 오류 안내 본문 | `neutral[8]` × `error[0]` | 10.79:1 | AAA |
| 정보 안내 본문 | `neutral[8]` × `info[0]` | 10.81:1 | AAA |
| 폼 라벨 | `neutral[8]` × `#ffffff` | 12.10:1 | AAA |
| 오류 안내 제목 | `error[7]` × `error[0]` | 8.01:1 | AAA |
| 정보 안내 제목 | `info[7]` × `info[0]` | 6.82:1 | AAA |
| 상태 캡션 | `neutral[7]` × `neutral[0]` | 7.95:1 | AAA |
| 도움말·플레이스홀더 | `neutral[6]` × `#ffffff` | 6.30:1 | AA |
| 선택된 라디오 보조 설명 | `neutral[6]` × `primary[0]` | 5.61:1 | AA |
| 회색 표면 위 보조 텍스트 | `neutral[6]` × `neutral[0]` | 5.77:1 | AA |
| 주요 버튼 글자 | `#ffffff` × `primary[5]` | 4.55:1 | AA |

**미달 0건.**

### 검증 중 고친 것 1건

`.input:disabled` 의 글자색이 `neutral[5]` × `neutral[0]` = **4.13:1** 로 AA 미달이었다. DS `do_rules` 가
바로 이 수치를 지목해 금지한 조합("50단은 흰 배경에서만 통과하고 회색 표면 위에선 미달이다 —
neutral 0 위 4.13")이라 `neutral[6]`(5.77:1)로 올렸다. 비활성 컨트롤은 WCAG 대비 요구 대상이 아니지만
DS 가 명시적으로 금지한 조합이므로 예외를 두지 않았다.

### 그 밖의 접근성 처리

- `label[for]` 로 입력과 명시 연결, 도움말은 `aria-describedby` 로 연결(DS do_rule).
- 라디오는 `fieldset` + `legend` 로 묶어 그룹 이름이 읽히게 했다.
- 실패 안내는 `role="alert"`, 성공 안내는 `role="status"`.
- 주요 버튼 글자 대비가 4.55:1 로 AA 하한에 가깝다 — DS `known_gaps` 가 기록한 정본 주조색의 성질이며
  DS 가 정한 값이므로 수용한다(임의로 더 진한 색을 만들지 않는다).

---

## § 실측 검증 (브라우저 · Chromium)

| 검사 | 결과 |
|---|---|
| 내용 잘림 | **0건** (1280·360, 2회 재현) |
| 문서 가로 넘침 | **0건** (1440 / 1280 / 768 / 480 / 360 / 320) |
| 입력 플레이스홀더·값 넘침 | **0건** (1280 / 480 / 400 / 375 / 360) |
| 참고 영역 속성 전수 | **2/2 기준 일치** |
| 44px hit area | 미달 0건 (라디오 라벨 실측 292×105) |
| raw hex | **0건** — hex 79건 전부 DS 토큰 값 또는 DS 가 지정한 행 hover 값 |

### 검사기 사각을 하나 메웠다

`clip.mjs` 는 `<input>` 의 `scrollWidth` 를 **값 기준**으로 재기 때문에 **플레이스홀더 넘침을
구조적으로 못 본다**. 이 화면의 패스워드 플레이스홀더(263px)가 360px 폭에서 가용 폭(258px)을 넘고
있었는데 잘림 검사는 0건을 반환했다. 실제 렌더 폰트로 텍스트 폭을 재는 검사기를 새로 만들어
잡았고, 입력 좌우 여백을 400px 이하에서만 좁혀 해소했다(글자 크기는 낮추지 않았다 — DS
`iteration_guide`: "좁으면 크기를 낮추지 말고 열 수를 줄인다").

---

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | `design.html` | 와이어프레임 `main` (surface=page) |

- 공유 스타일: `design.css`

---

## § 관례 이탈 · 미해결

- ⚠️ **화면 노출 텍스트에 영문 역할 코드가 남는다** — `작업자 (WORKER)` / `검수자 (REVIEWER)` 는
  골격 `options` 값 그대로다. 확정 관례(화면 노출 텍스트에 영문 구현 식별자 금지)와 어긋나지만,
  옵션 라벨을 바꾸는 것은 **골격 변경**이라 시안이 임의로 할 수 없다. 정리하려면 SCREEN-002 ITEM 의
  `options` 를 먼저 고쳐야 한다(LogiCraft 먼저 → 코드 순서).
  참고로 SCREEN-003 은 같은 역할을 **`검수자`/`작업자`/`포털`** 로 표기하도록 골격이 규정하고 있어,
  두 화면 사이에 표기 축이 갈려 있다.
- ⚠️ **320px 이하는 미검증 폭이다** — 플레이스홀더가 320px 에서 넘친다(263px vs 218px). 같은 안내가
  입력 아래 도움말로 반복되므로 정보 손실은 없다. DS `responsive_breakpoints` 가 하한을 규정하지
  않아 지원 폭 자체가 미확정이다.

---

## § 역등록 기록 (Phase 5)

| 항목 | 값 |
|---|---|
| SD ITEM | SD-018 |
| render_id | `main` (와이어프레임과 동일) |
| surface | `page` |
| label | 역할 클레임 화면 — 고충실 디자인 |
| action | add |
| 게시 URL | `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-018/main.html` (+ `main.css`) |
| 왕복 대조 | 업로드 원문 ↔ 게시본 문자 단위 대조 — **미설명 잔차 0건**. 차이는 전부 알려진 서버 정규화(lang 제거 · CSS link 주입 · void self-closing · `viewBox` 소문자화 · 공백)다. CSS 는 원문 해시 일치 |
| ⚠ 게시본 속성 손실 | 서버 sanitizer 가 `scope="col"`·`scope="row"`(표 머리칸 8곳) · `autocomplete="new-password"` 를 **저장 시 삭제**한다. `role`·`aria-*`·`checked`·`disabled` 는 보존되므로 표현 정규화가 아니라 속성 허용목록 문제다. **로컬 `design/` 원본이 진실원이며 구현은 그쪽을 따른다.** 플랫폼에 보고 완료 |
