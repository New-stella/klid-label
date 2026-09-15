| 항목 | 값 |
|---|---|
| CO 식별자 | CO-20260915-포털-간격토큰-block-Tailwind충돌 |
| 제목 | 포털 채널 간격 토큰의 Tailwind 투영 키 `block` 이 Tailwind 4.3 `inline-<간격>` 유틸리티와 충돌해 `inline-block` 요소가 전부 20px 폭이 되는 결함을 투영 키 개명으로 해소한다 |
| 대상 도메인 | 프론트(포털 채널 빌드 전 화면 — 직접 관측된 곳은 SCREEN-029 포털 라벨링) |
| 구현 상태 | ✅ 완료 |
| LogiCraft 설계반영 | — (해당없음 — 아래 §6) |
| 생성일 | 2026-09-15 |

> ★ 이 프로젝트는 **설계를 먼저 확정하고 코드가 뒤따른다**(`klid-dispatch` Phase 3.6). 이 CO 는 설계 값이 아니라 **Tailwind 투영 키**만 바꾸므로 고칠 ITEM 이 없다(§6 근거).

---

## §1 배경

포털 개발망(103번 서버)의 저작도구 라벨링 화면에서 **AI 자동 추적 패널의 라디오 동그라미가 가로로 늘어난 타원**으로 보인다는 신고가 있었다(2026-09-15 스크린샷). 같은 화면의 우측 객체 목록에서 **항목별 트랙 색 막대가 4px 가 아니라 20px 폭**으로 뜨는 것도 같은 원인이다.

브라우저 계산 스타일을 실측했다.

- 라디오 동그라미 `span`(`PortalRadio`, 클래스 `inline-block size-[16px]`)의 계산 크기가 **20×16px** 이다. 링이 옆으로 늘고, 3px 안쪽 흰 틈으로 만드는 가운데 점은 12×8px 타원이 된다.
- 객체 목록의 트랙 색 막대 `span`(`ObjectClassTree`, 클래스 `inline-block w-1`)도 **inline-size 20px** 이다.
- 포털 번들 CSS(`label-remote/assets/AuthoringApp-*.css`)에 **`.inline-block{inline-size:20px}`** 규칙이 정확히 1건 있고, 유틸리티 블록 뒤쪽(`.select-none` 다음)에 놓여 `size-[16px]`·`w-1` 의 `width` 를 이긴다. Host(포털) CSS 가 아니라 **우리 번들 안의 규칙**이다.
- 관제 채널 빌드 산출물에는 `.inline-block{display:inline-block}` 만 있다.

**근본 원인.** Tailwind 4.3 은 논리 크기 유틸리티 `inline-<값>`(→ `inline-size`)·`block-<값>`(→ `block-size`) 을 제공하며 값을 `--spacing-*` 이름공간에서 찾는다. 포털 채널 디자인 토큰 투영(`frontend/design-tokens/ds002.js`)의 간격 사다리에 **`block: '20px'`** 키가 있어, 클래스 `inline-block` 이 「display: inline-block」 정적 유틸리티와 「inline-size: block(=20px)」 함수형 유틸리티 **둘 다에 매칭**돼 규칙이 두 개 생긴다. 관제 채널 토큰에는 그 키가 없어 관제 빌드는 정상이다.

## §2 변경 요지

- `design-tokens/ds002.js` 간격 사다리의 Tailwind 키 **`block` → `block-gap`** 으로 바꾼다(값 20px 불변). 기존 `label-gap`·`card-gap` 과 같은 명명이다.
- 사용처 `gap-block` 2곳을 `gap-block-gap` 으로 바꾼다.
- 회귀 가드: 포털 간격 키 집합에 Tailwind `inline-*` 정적 유틸리티의 접미(`block`·`flex`·`grid`·`table`)가 들어올 수 없음을 시험으로 고정한다. 기존 사다리 시험의 기대 키도 갱신한다.
- 근거 주석: 왜 DS-002 의 토큰명 `block` 과 투영 키가 다른지 `ds002.js` 에 남긴다.

## §3 도메인별 변경 상세

> 이 절이 구현 에이전트에 `change_detail` 로 그대로 전달된다.

### 프론트 (포털 채널 빌드 · 관측 화면 SCREEN-029) — `klid-web-implementer`

- **대상 파일·심볼**
  - `frontend/design-tokens/ds002.js` — `SPACING` 상수의 `block: '20px'` 키.
  - `frontend/src/pages/portal/PortalUploadPage.tsx` 216행 부근 `gap-block`.
  - `frontend/src/features/portal/uploads/components/PortalMarkingToolbar.tsx` 91행 부근 `gap-block`.
  - `frontend/src/test/designTokensPortalChannel.test.ts` — `간격_명명_사다리가_DS_002_값이다` 의 `LADDER` 기대표.
- **변경**
  1. `SPACING` 의 `block` 키를 **`'block-gap'`** 으로 개명한다. 값 `'20px'` 은 그대로. 키 바로 위에 주석으로 남긴다 — *DS-002 토큰명은 `block` 이지만 Tailwind 4.3 이 `inline-<간격>`/`block-<간격>` 논리 크기 유틸리티를 두어 `inline-block` 이 `inline-size: 20px` 로도 매칭되므로 투영 키를 달리 둔다. 값은 DS-002 그대로이며 이름만 투영 층에서 갈린다.*
  2. `gap-block` 사용 2곳을 `gap-block-gap` 으로 바꾼다(`grep -rn 'gap-block' frontend/src` 로 전수 확인 — 시험 파일 포함).
  3. 기존 시험 `간격_명명_사다리가_DS_002_값이다` 의 기대 키 `block` 을 `'block-gap'` 으로 바꾼다.
  4. 회귀 가드 신설(같은 시험 파일에 `it` 하나 추가) — 포털 테마 `spacing` 키 집합에 `block`·`flex`·`grid`·`table` 이 **없다**를 단언한다. 근거 주석: Tailwind 정적 유틸리티 `inline-block`·`inline-flex`·`inline-grid`·`inline-table` 이 함수형 `inline-<간격>` 과 겹쳐 `inline-size` 규칙을 함께 생성한다(2026-09-15 포털 실측 — 라디오 타원·트랙 막대 20px).
  5. **변이 확인**: 4번 가드가 실제로 잡는지 `block` 키를 잠시 되돌려 RED 를 보고 원복한다(바이트 동일).
- **불변**
  - DS-002 의 토큰 값(20px)과 다른 간격 키 10개는 그대로. `inline: '8px'` 키는 현재 충돌이 없으므로 **건드리지 않는다**(`block-inline` 은 정적 유틸리티가 아니다).
  - 관제 채널 토큰(`design-tokens/channel.js` 의 관제 축)은 무변경.
  - `PortalRadio`·`ObjectClassTree` 등 컴포넌트 코드는 **고치지 않는다** — 원인은 부품이 아니라 토큰 키다.
- **주의**
  - 이 저장소는 Tailwind v4 + `@config` JS 설정이라 JS `theme.extend.spacing` 키가 그대로 `--spacing-*` 이름공간이 된다. 키 이름이 곧 유틸리티 접미다.
  - 같은 날 다른 CO(`CO-20260915-포털-스타일격리-preflight-스코프`)가 포털 CSS 격리를 다루고 있으나 그쪽은 `styles/global.css`·PostCSS 축이고 이 CO 는 `design-tokens/ds002.js` 축이라 파일이 겹치지 않는다.
- **수용기준**
  - `npm run build:portal` 산출 CSS 에 `inline-size` 규칙이 **0건**(`grep -c 'inline-size' dist-portal/**/*.css` 또는 산출 경로의 CSS).
  - `.inline-block{display:inline-block}` 은 그대로 1건.
  - vitest: `designTokensPortalChannel.test.ts` 전건 통과, 신설 가드가 변이(키 `block` 복원)에서 RED.
  - `grep -rn 'gap-block\b' frontend/src` 결과 0건(`gap-block-gap` 만 남는다).
  - 프론트 전체 vitest 통과 · `tsc --noEmit` 0.

## §4 영향·리스크

- **하위호환**: 클래스 `gap-block` 은 포털 화면 2곳에서만 쓰였고 함께 바꾼다. 관제 채널 산출물은 이 키를 갖지 않아 무영향.
- **되돌리기**: 키를 `block` 으로 되돌리면 증상이 그대로 재현된다(가드 시험이 RED 로 막는다).
- **리스크**: 사용처를 한 곳 놓치면 그 자리 간격이 0 이 된다(Tailwind 가 미지 클래스를 생성하지 않는다). `grep` 전수 확인이 그것을 막는다.

## §5 검증

- 자동: `frontend` vitest 전건 · `tsc --noEmit` · `npm run build:portal` 산출 CSS grep.
- 수동(배포 후): 포털 라벨링 화면 AI 자동 추적 라디오가 16×16 정원, 객체 목록 트랙 색 막대가 4px 폭.
- 실측 절차(재현·확인용): 포털 라벨링 화면에서 `getComputedStyle(document.querySelector('[data-portal-radio-dot]')).inlineSize` 가 `16px`.

## §6 관련 설계 ITEM  ★구현 전에 먼저 확정한다

**해당 없음 — 근거**

- `DS-002`(포털 채널 디자인 시스템) 는 `tokens.spacing.scale.block = "20px"` 을 갖지만 그 ITEM 은 **외부 채널 정의의 참조 사본**이며 `dont_rules` 가 *"우리가 이 사본의 값을 고치지 않는다"* 고 못박는다. 이번 변경은 그 값도 이름도 건드리지 않고 **우리 저장소의 Tailwind 투영 키**만 바꾼다(`ds002.js` 머리말: *"이 파일은 그 값을 Tailwind 스케일 이름으로 옮겨 적은 투영"*).
- 화면 키트·위키 어디에도 Tailwind 키 `gap-block` 을 적은 곳이 없다(`docs/screen-design`·`docs/design`·`docs/v2-wiki` grep — 걸린 것은 와이어프레임 CSS 의 일반 `display:inline-block` 뿐).
- 따라서 `mc-logi-update` 대상 ITEM 이 없다. 투영 규칙(예약어 회피)을 설계 층에 남길지는 별건 판단이다.

**확정:** — (ITEM 변경 없음)

## §7 구현 로그

| 일시 | 도메인 | 에이전트 | 결과 | QA | 커밋 |
|---|---|---|---|---|---|
| 2026-09-15 | 프론트 | klid-web-implementer(편집) + 메인(검증) | 키 개명·사용처 2곳·시험 갱신+가드 신설 | 생략(사용자 요청) — 메인 실측: 토큰 시험 52건 통과 · build:portal CSS inline-size 0건 | (머지 커밋 참조) |

**미반영·보류 항목**: —
