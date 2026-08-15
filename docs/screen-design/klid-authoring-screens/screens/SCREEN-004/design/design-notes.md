# SCREEN-004 개발용 로그인 화면 — 디자인 노트

| 항목 | 값 |
|---|---|
| 대상 | SCREEN-004 개발용 로그인 화면 (`/dev/login`) |
| 골격 기준 | `screens/SCREEN-004/SCREEN-004.md` **v8** (키트 session 5 · stale false) |
| 디자인 시스템 | DS-001 KRDS Public **v8** (`_shared/design-system.md`) |
| 성격 | **신규 작성**. 이 화면에는 기존 고충실 시안이 없었다 |
| 기술 제약 | 무 JS · CSS-only, 셸(GNB/LNB/푸터) 제외 |
| surface | `main` (page) — 와이어프레임과 동일 |
| ⚠ 성격 | **비운영 화면**이다. 운영 빌드에 포함되지 않는다 |

---

## § 골격 대조 — 섹션 2개 · 컴포넌트 8개 전수

| 골격 | 컴포넌트 | 시안에서 그린 자리 |
|---|---|---|
| ① DEV 안내 헤더 · role=header · layout=stack | `Heading`(Dev Login) | 카드 상단 `h2`(title-lg) |
| | `Badge`(Custom, DEV 빌드 전용 · 노란색 경고 배지) | 제목 옆 `.badge.badge-warn` + 경고 아이콘 |
| | `Text`(Custom, 로컬·개발 환경에서 관제서버 없이 토큰을 발급합니다. 운영 배포에는 포함되지 않습니다.) | `.dev-note` 띠 안 문장 |
| ② 토큰 발급 폼 · role=main · layout=form | `RadioGroup`(역할 선택, binds_to=role, options 3종) | `fieldset.radio-group` + 카드형 라디오 3개 + 채널 칩 |
| | `Input`(userNo (선택), binds_to=userNo, placeholder=역할별 기본값(예 1001)) | `.field` + `label[for]` + `input` |
| | `Input`(expSeconds (선택), number, min=1, placeholder=3600) | `.field` + `label[for]` + `input[type=number][min=1]` |
| | `Alert`(state=hidden, variant=destructive, errorMessage 존재 시에만 role=alert) | 기본 상태에서는 **없음**. 참고 영역 ③ 에 노출 상태를 그렸다 |
| | `Button`(토큰 발급 + 진입, variant=primary, 제출 중 disabled + '발급 중…') | 카드 하단 `button.btn-primary.btn-block`. 진행 중 상태는 참고 영역 ② |

- 골격의 `state: hidden` 을 그대로 지켜 기본 화면에는 Alert 를 그리지 않았다.
- 골격 note 가 `POST /api/v1/dev/tokens (API ITEM 미등록)` 이라 적으므로 API-NNN 을 지어내지 않았다.

### 골격에 없어 그리지 않은 것

- **발급된 토큰 표시·복사 버튼** — 골격에 없다. 발급 후 곧바로 이동하는 흐름이라 머무를 자리가 없다.
- **channel 선택 입력** — 골격상 채널은 역할에 **연동**되는 값이지 사용자가 고르는 값이 아니다.
  그래서 입력이 아니라 각 역할 옆의 **표시 칩**으로만 그렸다.

---

## § 디자인 결정 (골격이 규정하지 않은 부분)

### 1. 셸 밖 화면이라 기준본의 `page-head + breadcrumb` 관례를 쓰지 않았다

★ 구현 시 **`.sheet-head` 와 `.state-frame`·`.state-caption` 은 화면에 그리지 않는다.**

### 2. 개발 전용이라는 사실을 두 겹으로 말한다

이 화면은 **인증 없이 임의 권한의 토큰을 발급**한다. 운영에 노출되면 인증 체계 전체가 우회되는
성격이라, 배지 하나로만 알리면 약하다고 판단했다. 제목 옆 `DEV 빌드 전용` 배지(경고 아이콘 병기)와
그 아래 warn tint 띠(`.dev-note`) 두 겹으로 두고, 띠는 **폼보다 위에** 놓아 조작 전에 읽히게 했다.

warn 계열을 큰 면적으로 칠하지 않은 것은 DS dont_rule 때문이며, 띠는 카드 폭 한 줄로 제한했다.

### 3. 역할 선택지에 채널을 칩으로 병기했다

골격 옵션 라벨(`REVIEWER (1001, 김검수) · INTERNAL`)은 역할·번호·이름·채널 4개 정보를 가운뎃점으로
이어 붙인 한 줄이다. 그대로 두면 무엇이 무엇인지 구분되지 않아, **역할·번호·이름은 라벨로 두고
채널만 우측 칩으로 분리**했다. 문자열을 줄이지 않았고 순서도 바꾸지 않았다 — 시각적으로 갈랐을 뿐이다.

`PORTAL` 칩만 중립색인 것은 의도다. `INTERNAL` 둘은 같은 축(내부)이라 같은 색으로 묶이고,
포털은 다른 축임이 색으로도 보여야 한다.

### 4. 선택 입력 2개를 가로 2열로 두지 않았다

처음에는 `userNo`·`expSeconds` 를 2열로 뒀는데, 실측에서 `userNo` 의 한글 플레이스홀더
(`역할별 기본값(예 1001)`)가 잘렸다. DS `iteration_guide` 가 "좁으면 크기를 낮추지 말고 열 수를
줄인다"고 규정하므로 **글자 크기를 유지한 채 1열로 바꿨다.**

### 5. 진행 중에는 문구를 바꿨다 — SCREEN-002 와 다른 이유

골격이 `제출 중 disabled + '발급 중…'` 을 **명시**하므로 라벨을 바꾼다. 문구 변경을 규정하지 않은
SCREEN-002 는 라벨을 유지하고 스피너만 붙였다. **두 화면의 차이는 골격의 차이이며 통일하지 말 것.**

### 6. 운영 미노출 3중 통제를 번호 목록으로 남겼다

골격 설명의 절반이 "이 화면이 운영에 나가지 않도록 막는 3중 구조"다. 화면에 보이는 요소는 아니지만
구현자가 반드시 알아야 하는 제약이라 참고 영역에 단계별로 적었고, 각 단계의 **제목**(무엇이 막히나)과
**본문**(왜 그런가)을 위계로 갈랐다.

### 7. 성공 이후 흐름도 적었다

발급 성공 시 이 화면은 사라지고 운영과 같은 진입 경로를 탄다. "이 화면에 머무르지 않는다"는 사실이
없으면 구현자가 결과 표시 영역을 만들 수 있어, 별도 참고 영역으로 2단계를 남겼다.

---

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 역할 선택 | **UI-026 RadioGroup** + **UI-025 Radio** | `primary.scale[0]`(선택 배경) · `primary.scale[5]`(테두리·링) · `neutral.scale[4]`(기본 테두리) · `radius.md` | 카드형 |
| 선택 입력 2종 | **UI-002 Input** (+ **UI-099 Field**) | `neutral.scale[4]`(테두리) · `neutral.scale[9]`(값) · `neutral.scale[6]`(플레이스홀더·도움말) · `mono 17` | min-height 44px · 값은 mono |
| 제출 | **UI-001 Button** | `primary.scale[5]` → hover `primary.scale[6]` · `button 17/500` | variant=primary · block |
| DEV 배지 | **UI-014 StatusBadge** | `warn.scale[0]` × `warn.scale[7]` · `radius.full` | 경고 아이콘 병기 |
| 실패 안내 | **UI-103 AlertBanner** | `error.scale[0]` · `error.scale[2]` · `error.scale[7]` · `neutral.scale[8]` | variant=destructive |
| 카드 표면 | **UI-011 Card** | `radius.lg` · `shadow.sm` · `neutral.scale[2]` | DS: 카드는 shadow.sm 만 |
| 개발 전용 안내 띠 | ⚠️ 미정 | `warn.scale[0]`(배경) · `warn.scale[2]`(테두리) · `warn.scale[7]`(아이콘) · `neutral.scale[8]`(문장) | 신규 컴포넌트 후보 |
| 채널 칩 | ⚠️ 미정 | INTERNAL `secondary[0]×secondary[7]` · PORTAL `neutral[1]×neutral[8]` · `radius.sm` | 신규 컴포넌트 후보 |
| 시안 표지·상태 프레임·통제 목록 | ⚠️ 미정 | — | **시안 전용** — 구현 대상이 아니다 |

---

## § 접근성 검증 (WCAG 2.1 — 동봉 `scripts/contrast_checker.py`)

| 조합 | 토큰 | 대비 | 판정 |
|---|---|---|---|
| 제목 | `neutral[9]` × `#ffffff` | 16.18:1 | AAA |
| 선택된 라디오 라벨 | `neutral[9]` × `primary[0]` | 14.40:1 | AAA |
| 폼 라벨 | `neutral[8]` × `#ffffff` | 12.10:1 | AAA |
| 개발 전용 안내 문장 | `neutral[8]` × `warn[0]` | 11.00:1 | AAA |
| 오류 안내 본문 | `neutral[8]` × `error[0]` | 10.79:1 | AAA |
| 채널 칩 (INTERNAL) | `secondary[7]` × `secondary[0]` | 10.01:1 | AAA |
| 채널 칩 (PORTAL) | `neutral[8]` × `neutral[1]` | 9.85:1 | AAA |
| DEV 배지 · 단계 번호 | `warn[7]` × `warn[0]` | 8.43:1 | AAA |
| 오류 안내 제목 | `error[7]` × `error[0]` | 8.01:1 | AAA |
| 상태 캡션 | `neutral[7]` × `neutral[0]` | 7.95:1 | AAA |
| 도움말·플레이스홀더 | `neutral[6]` × `#ffffff` | 6.30:1 | AA |
| 선택된 라디오 보조 설명 | `neutral[6]` × `primary[0]` | 5.61:1 | AA |
| 회색 표면 위 보조 텍스트 | `neutral[6]` × `neutral[0]` | 5.77:1 | AA |
| 주요 버튼 글자 | `#ffffff` × `primary[5]` | 4.55:1 | AA |

**미달 0건.**

### 검증 중 고친 것 1건

`.input:disabled` 의 글자색이 `neutral[5]` × `neutral[0]` = **4.13:1** 로 AA 미달이었다. DS `do_rules` 가
바로 이 수치를 지목해 금지한 조합이라 `neutral[6]`(5.77:1)로 올렸다.

### 그 밖의 접근성 처리

- `label[for]` + `aria-describedby` 로 입력·라벨·도움말 연결(DS do_rule).
- 라디오는 `fieldset` + `legend` 로 묶었다.
- DEV 배지는 색만이 아니라 **경고 아이콘 + 한글 문구**를 함께 쓴다(DS dont_rule).
- `expSeconds` 는 `type=number` + `min=1` (골격 note 그대로).

---

## § 실측 검증 (브라우저 · Chromium)

| 검사 | 결과 |
|---|---|
| 내용 잘림 | **0건** (1280·360, 2회 재현) |
| 문서 가로 넘침 | **0건** (1440 / 1280 / 768 / 480 / 360 / 320) |
| 입력 플레이스홀더·값 넘침 | **0건** (1280 / 480 / 400 / 375 / 360 / 320) |
| 참고 영역 속성 전수 | **3/3 기준 일치** |
| 44px hit area | 미달 0건 (라디오 라벨 실측 292×153·292×132) |
| raw hex | **0건** — hex 78건 전부 DS 토큰 값으로 추적됨 |

### 실측이 잡은 결함 1건

`userNo` 플레이스홀더가 2열 배치에서 잘렸다(닫는 괄호 유실). `clip.mjs` 는 `<input>` 의 `scrollWidth` 를
값 기준으로 재기 때문에 **플레이스홀더 넘침을 구조적으로 못 보고 0건을 반환**했고, 육안으로 발견해
전용 검사기를 만들어 확정했다. 1열 전환으로 해소(185px / 가용 404px).

---

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | `design.html` | 와이어프레임 `main` (surface=page) |

- 공유 스타일: `design.css`

---

## § 관례 이탈 · 미해결

- ⚠️ **화면 노출 텍스트에 영문 구현 식별자가 남는다** — `Dev Login` · `REVIEWER`/`WORKER`/`PORTAL_USER` ·
  `INTERNAL`/`PORTAL` · `userNo` · `expSeconds`. 전부 골격이 규정한 라벨·옵션 값이라 시안이 임의로
  바꾸면 골격 변경이 된다.
  **이 화면에 한해 관례 이탈을 수용한다** — 비운영(개발자 전용) 화면이고, 이 값들이 곧 토큰 발급
  요청의 필드명이라 우리말로 옮기면 오히려 대응 관계가 끊긴다. 대신 각 입력 아래 **우리말 도움말**을
  붙여 무엇을 넣는 자리인지 알 수 있게 했다.
  정리하려면 SCREEN-004 ITEM 을 먼저 고쳐야 한다(LogiCraft 먼저 → 코드 순서).

---

## § 신규 컴포넌트 후보 (Phase 6)

| 이름 | category | 쓰인 자리 | 비고 |
|---|---|---|---|
| `DevOnlyNotice` | feedback | 카드 상단 개발 전용 안내 띠 | warn tint 띠 + 아이콘 |
| `ChannelChip` | display | 역할 선택지 우측 채널 표시 | INTERNAL / PORTAL 2종 |

---

## § 역등록 기록 (Phase 5)

| 항목 | 값 |
|---|---|
| SD ITEM | SD-020 |
| render_id | `main` (와이어프레임과 동일) |
| surface | `page` |
| label | 개발용 로그인 화면 — 고충실 디자인 |
| action | add |
| 게시 URL | `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-020/main.html` (+ `main.css`) |
| 왕복 대조 | 업로드 원문 ↔ 게시본 문자 단위 대조 — **미설명 잔차 0건**. 차이는 전부 알려진 서버 정규화(lang 제거 · CSS link 주입 · void self-closing · `viewBox` 소문자화 · 공백)다. CSS 는 원문 해시 일치 |
| ⚠ 게시본 속성 손실 | 서버 sanitizer 가 `min="1"` · `inputmode="numeric"` 를 **저장 시 삭제**한다. `role`·`aria-*`·`checked`·`disabled` 는 보존되므로 표현 정규화가 아니라 속성 허용목록 문제다. **로컬 `design/` 원본이 진실원이며 구현은 그쪽을 따른다.** 플랫폼에 보고 완료 |
