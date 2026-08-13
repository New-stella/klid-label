# SD-003 (SCREEN-012 작업 목록 화면) — 관례 정합 노트

정본은 `design.css` / `design.html` 이다. `design-main.{css,html}` 은 서버 현재본의 로컬 미러이며 읽기만 했다.

**이것은 재디자인이 아니다.** 바꾼 것은 ①색 토큰 변수명 ②타이포 지정 방식 ③파일명 참조뿐이고,
레이아웃·색·간격·구성요소·문구는 손대지 않았다. 브라우저 실측에서 `fontSize`·색·`border` 차이 0건,
위치·크기 차이 0건이다(§4).

---

## 1. 색 토큰 — 값(hex) 기준 매핑

기준본 `SCREEN-009/design/design.css` 의 `:root` 를 파싱해 **hex → 토큰명 역인덱스**(78건, 중복 hex 0)를
만들고, 대상 토큰의 **값으로** 새 이름을 찾았다. 치환은 정규식 `--[A-Za-z0-9_-]+` 최장매칭 + 사전 조회
**단일 패스**로 했다(문자열 순차 치환은 `--color-neutral-100 → --n-10 → --n-1` 식 이중 변환 사고를 낸다).

이 파일의 접두는 `--color-*` 라 기준본(`--n-*`)과 **이름 충돌이 없었고**, 스케일도
`0/10/…/100 → 0/1/…/10` 이 값 기준으로 그대로 1:1 대응했다(자릿수 규칙과 결과가 우연히 일치).
확인은 규칙이 아니라 **값 대조**로 했다.

| 원본 | 정합본 | 근거 |
|---|---|---|
| `--color-neutral-{0,10..100}` | `--n-{0..10}` | hex 일치 |
| `--color-primary-*` | `--p-*` | hex 일치 |
| `--color-secondary-*` | `--s-*` | hex 일치 |
| `--color-info-*` | `--i-*` | hex 일치 |
| `--color-warn-*` | `--w-*` | hex 일치 |
| `--color-error-*` | `--e-*` | hex 일치 |
| `--color-success-*` | `--su-*` | hex 일치 |
| `--color-bg-surface` (`#ffffff`) | `--bg-page` | hex 일치 |
| `--color-bg-page` (`var(--color-neutral-0)`) | `--bg-alt` | 기준본 `--bg-alt: var(--n-0)` 와 값 동일 |
| `--color-border` | `--border` | 기준본 별칭 |
| `--color-border-strong` | `--border-strong` | 기준본 별칭 |
| `--color-table-row-hover` (`#fffbeb`) | `--row-hover` | SD-009·SD-001 과 동일 이름 |
| `--space-*` | `--sp-*` | 기준본·SD-001·SD-009·SD-011 전부 `--sp-*` |

### 별칭 4종을 없앤 것

`--color-text-body`(=n-10) · `--color-text-muted`(=n-6) · `--color-text-muted-on-white`(=n-6) ·
`--color-focus-ring`(=p-5) 은 **기준본에 대응 별칭이 없어** 참조처를 팔레트 토큰으로 인라인했다
(`var(--n-10)` · `var(--n-6)` · `var(--p-5)`). 값은 그대로다.
`--color-text-muted` 와 `--color-text-muted-on-white` 는 원래부터 **같은 값(n-6)** 이었다.

### hex 대조

`:root` 밖 raw hex 0건, `rgb()/rgba()` 색 0건(그림자 `rgba(14,21,40,…)` 는 `:root` 안에만 있다).
원본/정합본 hex 집합 **79건 완전일치**(§4 증거 블록).
`:root` 선언은 104건 → **100건**(별칭 4종 인라인분).

---

## 2. 타이포 — `.ts-*` → `.t-*` 사다리 12종

원본은 `.ts-*` 11종이었다(`.t-nav-link` 부재). 기준본 사다리 12종을 **값 verbatim** 으로 다시 쓰고
`.t-nav-link` 를 추가했다. `.t-body-lg`·`.t-nav-link`·`.t-mono` 는 이 화면에서 쓰이지 않지만
**카탈로그 정의라 남긴다**(SD-001·SD-009·SD-011 도 동일).

HTML 의 `ts-*` 19곳을 `t-*` 로 바꾸고, **사다리 단계와 (크기·굵기·행간) 3요소가 정확히 일치하는**
컴포넌트 선언 11건을 CSS 에서 걷어내 HTML 클래스로 옮겼다 → HTML 유틸 사용 **75곳**.

| CSS 에서 걷어낸 선언 | 값 | 옮긴 유틸 |
|---|---|---|
| `.btn` | 17/500/1.4 | `.t-button` (24곳) |
| `.ds-alert-banner .title` | 17/600/1.5 | `.t-title-sm` |
| `.ds-alert-banner .desc` | 15/400/1.6 | `.t-body-sm` |
| `.field-label` | 14/600/1.4 | `.t-label` (6곳) |
| `.input`, `.select-trigger` | 17/400/— | `.t-body-md` (6곳) |
| `.kpi-label` | 14/600 (HTML 에 이미 `ts-label`) | `.t-label` |
| `.kpi-value` | 26/700/1.3 | `.t-display-sm` (5곳) |
| `.data-table` | 15/400/1.6 | `.t-body-sm` (`<table>` 1곳) |
| `.page-btn` | 15/400/1.6 | `.t-body-sm` (6곳) |
| `.timeline-time` | 14 (HTML 에 이미 `ts-caption`) | `.t-caption` |
| `.timeline-desc`, `.overlay-hint` | 15/400/1.6 | `.t-body-sm` |

### 사다리 밖이라 CSS 에 남긴 17건

관례는 "font-size 0건"이 아니다 — 기준본 자신이 10건을 갖고 있다. **3요소가 사다리 단계와 어긋나는 것**은
컴포넌트에 남기고 사유를 주석에 적었다.

- `body 17px` — `.t-*` 를 달지 않은 요소의 기준 크기
- `.btn-sm 15/500/1.4` — 15px 소형 버튼 변형(기준본 `.btn-sm`·SD-009 `.du-btn--sm` 과 같은 처지)
- **행간이 다른 것**: `.filter-hint`·`.video-chip`(14/400/**1.6** vs `.t-caption` 1.5) ·
  `.chip-count`·`.badge`·`.badge-event`·`.data-table th`·`.overlay-tag`·`.state-showcase-label`
  (14/600/**1.6** vs `.t-label` 1.4) · `.empty-state/.error-state .title`(17/600/**1.6** vs `.t-title-sm` 1.5) ·
  `.form-warning`·`.timeline-reason`(14/400/1.6)
- **크기가 사다리에 없는 것**: `.video-id`(mono 12px) · `.badge-derivative`(12/600)
- `.empty-state .desc, .error-state .desc`(15/400/1.6) — **사다리 일치이지만 CSS 에 남겼다.**
  `.error-state` 쪽은 이 HTML 에 노드가 없어(§3) 공유 선택자를 쪼개면 한쪽만 HTML·한쪽만 CSS 인
  비대칭 규칙이 된다. SD-001·SD-011 도 같은 이유로 `.error-state-preview .title` 을 CSS 에 뒀다.

⚠ `.btn-sm` 버튼 18개는 `.t-button` 을 달았지만 **`.btn-sm` 이 뒤에 정의돼 크기만 15px 로 덮인다**
(굵기 500·행간 1.4 는 `.t-button` 이 준다). 의도된 동작이며 원본 렌더와 동일함을 실측으로 확인했다(§4).

---

## 3. 관례 3종 — `lang` · `color-scheme` · 폰트 스택

- `<html lang="ko">` — **원본에 이미 있었다**(변경 없음).
- `html { color-scheme: only light; }` — **원본에 이미 있었다**(변경 없음).
  지시서 초안의 "넣지 않는다"는 폐기됐고 확정 관례는 "넣는다"이므로 그대로 둔다.
- `--font-body` / `--font-mono` — 관례 문자열로 교체했다.
  원본은 `-apple-system`(body) 과 `Menlo, Consolas`(mono) 가 빠져 있었고 따옴표도 `"` 였다.
  ⚠ **이 교체로 인한 폭 변화가 이 기기에서 0px 였다**(`compare-render` 가로 변화 0건, §4).
  즉 지시서가 예외로 인정한 `dw ±1~2px` 는 이 파일에서 **발생하지 않았고 예외를 쓸 필요가 없었다.**

---

## 4. 브라우저 실측 (`bin/compare-render.mjs`)

`--intended` 를 쓰지 않고 **전 요소를 판정 대상에 두었다**.

```
node bin/compare-render.mjs diff screens/SCREEN-012/design/design-main.html \
                                 screens/SCREEN-012/design/design.html --width 1280,480
```

1280×900 · 480×900 **양쪽 동일한 결과**, 요소 398개 1:1 대조:

| 축 | 결과 |
|---|---|
| `fontSize` | **0건** |
| `fontWeight` | **0건** |
| `color` · `backgroundColor` · `border*Color` · `border*Width` | **각 0건** |
| 위치·크기 변화 | **0건** (가로 리플로우 0건) |
| 문서 높이 | 3236 / 3236 (1280) · 3411 / 3411 (480) — **±0px** |
| `lineHeight` | 40건 — 아래 |

`lineHeight` 40건은 전부 **행간이 `normal` 이던 폼 컨트롤류가 명시값을 받은 것**이며,
사다리 채택의 의도된 귀결이다. 기하 변화가 0건이라 화면에 드러나지 않는다.

- `input.input` ×1 · `select.select-trigger` ×5 · `option` ×24 : `normal → 27.2px` (=17×1.6)
- `button.page-btn` ×6 및 그 안의 `svg`/`path` ×4 : `normal → 24px` (=15×1.6)

### 폭 지배 검사

```
node bin/compare-render.mjs width screens/SCREEN-012/design/design.html \
     --target '.input' --vary '.filter-hint' --width 1280
```
→ 원본/2배/4배/축소/복원 **전부 583px**. **폭 고정** — SD-009 의 (a) 결함(도움말 문장이 입력 폭을 지배)은
이 화면에 없다. 필터가 이미 `display:grid`(`.filter-grid`, `grid-template-columns: 1.4fr 1fr 1fr 1fr auto auto`)라
구조적으로 발생하지 않는다. **그래서 손대지 않았다.**

### (b) `margin-left:auto` 결함

`margin-left:auto` 0건. `.bulk-bar .spacer { flex: 1 }` 이 있으나 이건 **필터와 그 실행 버튼을 갈라놓는**
구조가 아니라 "선택 개수(좌) ↔ 일괄 조작(우)" 액션바라 정상이다. 필터의 조회·초기화 버튼은
`.filter-actions` 로 필터 그리드 **안**에 있다. **손대지 않았다.**

---

## 5. 공통 관찰 ①: 입력 경계 대비 (수치 보고 — 고치지 않았다)

지시대로 **색을 바꾸지 않았다**(바꾸면 "색 차이 0건" 판정이 깨진다).

이 화면의 **입력·셀렉트 경계는 `--border-strong`(= `--n-4` `#8a949e`)** 이다
(`design.css` `.input, .select-trigger { border: 1px solid var(--border-strong) }`).
입력은 `.card`(배경 `--bg-page` `#ffffff`) 안에 있으므로 판정 배경은 흰색이다.

| 토큰 | hex | on `#ffffff` | on `--n-0` `#f4f5f6` | WCAG 1.4.11 (3:1) |
|---|---|---|---|---|
| `--n-2` (= `--border`) | `#cdd1d5` | **1.54:1** | 1.41:1 | 미달 |
| `--n-3` | `#b1b8be` | 2.01:1 | 1.84:1 | 미달 |
| **`--n-4` (= `--border-strong`) — 이 화면의 입력 경계** | `#8a949e` | **3.08:1** | 2.82:1 | **통과** |
| `--n-5` | `#6d7882` | 4.51:1 | 4.13:1 | 통과 |

**→ 이 화면의 입력 경계는 3.08:1 로 1.4.11 을 통과한다.** 다른 SD 들과 달리 조치 필요가 없다.

⚠ 다만 이 화면은 **페이지 배경이 `--bg-alt`(`#f4f5f6`)** 다(`body { background: var(--bg-alt) }`).
그 위에 놓인 `.card` 경계는 `--border`(n-2) 라 **1.41:1** 이다. 이건 컨테이너 구획선(장식)이라
1.4.11 의 "non-text UI component" 대상인지 판단이 갈린다 — **정보 부족으로 남기고 손대지 않았다.**
9건 공통 결정으로 올릴 항목이면 함께 판단해 주기 바란다.

---

## 6. 공통 관찰 ②: 죽은 규칙

전 선택자 163건을 브라우저에서 `querySelectorAll` 로 매칭 검사했다 → **매칭 0건 15건**.
**명시도에 눌려 한 번도 적용되지 않는 선언은 0건**이었다(사다리 클래스 승패 전수 검사에서
어긋난 18건은 전부 의도된 `.btn-sm` 15px 덮어쓰기 — §2).

15건은 전부 **"이 정적 목업에 노드가 없을 뿐"** 인 것이라 **렌더 결과를 보존하고 남겼다**:

| 종류 | 선택자 |
|---|---|
| 리셋·포커스(방어) | `h4` · `ul` · `a:focus-visible` · `[tabindex]:focus-visible` |
| 사다리 카탈로그 | `.t-body-lg` · `.t-nav-link` · `.t-mono` |
| 컴포넌트 변형(카탈로그) | `.btn-danger` · `.btn-danger:hover:not(:disabled)` · `.timeline-dot[data-tone="success"]` |
| 이 목업에 노드가 없는 상태 미리보기 | `.error-state` · `.error-state .icon` · `.error-state .title` · `.error-state .desc` |
| 오버레이 폼 경고(미배치) | `.form-warning` |

⚠ `.error-state` 4건과 `.form-warning` 은 **HTML 에 대응 노드가 아예 없다**(`.empty-state` 만 있다).
디자인 의도상 "오류 상태도 정의해 둔 것"인지, 아니면 목업에서 빠진 것인지는 **확인하지 못했다 — 정보 부족.**
지우면 정보가 사라지므로 그대로 뒀다.

---

## 7. 공통 관찰 ③: 정렬 붕괴

`align-items: flex-end|end|center` 사용처를 전수 확인했다.
`.filter-grid { align-items: end }` 는 **grid** 이고, 자식은 `.field`(라벨+입력) 와 `.filter-actions`(버튼)로
높이가 다르지만 `end` 정렬이라 입력 하단과 버튼 하단이 맞는 **의도된 배치**다.
도움말 `.filter-hint` 는 `grid-column: 1 / -1` 로 **별도 행**에 있어 이미 지시서가 권장한 형태다.
`.ds-alert-banner .body` 는 `flex: 1; min-width: 0` 을 명시로 갖고 있다.

**폭을 고정한 곳이 없으므로 텍스트 줄 수 변화로 인한 정렬 영향도 없다.** 손대지 않았다.

---

## 8. 그 밖에 바꾼 것

- `design.html` 의 `<link rel="stylesheet" href="design-main.css">` → `href="design.css"`
  (로컬에서 브라우저로 바로 열기 위한 것. **업로드 시에는 링크째 제거**한다 — 서버가 주입한다).
- CSS 머리말을 정본 표기로 교체(다른 SD 정합본과 동일 문구).
- 한글 무결성: HTML 은 한글 런 150종 / 898자 **완전 동일**(사라진 런·새 런 0).
  CSS 는 주석을 의도적으로 다시 썼고, 손상 음절 없음을 런 단위 대조로 확인했다.

## 9. 하지 않은 것

- LogiCraft 업로드·ITEM 수정 없음 · 커밋 없음
- `design-main.{css,html}` · `_sd-meta.md` 미수정
- 담당 외 SCREEN 파일 미수정

---

# 최종 정리 스윕 (#9)

지시서 4항(①입력·셀렉트 경계 상향 ②`color-scheme` 보정 ③死 규칙 정리 ④허용된 렌더 변경)을
이 화면에 적용했다. **바뀐 것은 `.page-btn` 경계 1건 + `viewBox` 표기 정규화뿐**이고,
나머지 3항은 "이미 되어 있음 / 해당 없음"으로 판정했다. 아래는 그 판정 근거다
(다른 8화면과 달리 변경이 적으므로 "안 봤다"와 구분되게 근거를 남긴다).

## 10-1. 경계 상향 — 입력·셀렉트는 **불필요**, `.page-btn` 1건은 **상향**

**이미 통과라 손대지 않은 것 (내리지도 않았다)**

- `.input, .select-trigger { border: 1px solid var(--border-strong) }` — 이미 **n-4 `#8a949e`,
  흰 배경 3.08:1** 로 WCAG 1.4.11 통과다(§5 에서 이미 실측). 목표값과 같으므로 **그대로 둔다.**
- `.btn-secondary` · `.btn-outline` 도 이미 `--border-strong` 이다.
  → 형제 화면 **SCREEN-031 의 `.btn-secondary` 가 `--n-3`(2.01:1)이었던 것이 드리프트**였고,
  이번 스윕에서 그쪽을 이 화면 값에 맞춰 올렸다. **이 화면이 기준 쪽이다.**
- 이 화면에 체크박스/라디오 컨트롤은 없다.

**★ 상향 1건 — `.page-btn`(페이지네이션 버튼) `--border` → `--border-strong`**

| 자리 | 전 | 후 | 흰 배경 대비 |
|---|---|---|---|
| `.page-btn` 기본 경계 | `--border` (= n-2 `#cdd1d5`) | **`--border-strong`** (= n-4 `#8a949e`) | 1.54:1 → **3.08:1** |

근거는 **같은 파일 안의 일관성**이다. 면이 `--bg-page`(흰색)이고 얹힌 `.card` 도 흰색이라
경계가 버튼 박스의 유일한 시각적 표시인데, **같은 파일의 `.btn-secondary`·`.btn-outline` 은
이미 `--border-strong`** 이었다. `.page-btn` 만 `--border` 로 남기면 한 파일 안에서 버튼 경계
정책이 갈리고, 그게 이번 스윕이 지우려는 드리프트다.

**상태 역전·강조 잠식 없음 (확인함)**

- `hover`(`.page-btn:hover:not(:disabled):not([aria-current])`)는 **배경만** 바꾸고 경계를 건드리지
  않는다 → 기본이 진해져도 역전이 생기지 않는다. `--n-5` 승격이 **필요 없다.**
- **현재 페이지 표시**(`.page-btn[aria-current="page"]`)는 `border-color: var(--p-5)` + 파란 면 +
  흰 글자로 기본 규칙을 덮는다. 실측에서 `.page-btn` 6개 중 **5개만** 경계색이 바뀌고 현재 페이지
  1개는 그대로였다(§10-5) — 강조가 묻히지 않음이 수치로 확인된다.

**올리지 않은 자리 — 전건과 사유**

`--border`(n-2, 1.54:1) 를 쓰는 나머지는 전부 **컨트롤이 아니다**:
`.card` · `.table-scroll` · `.data-table th/td` 괘선 · `.state-showcase` 상단 점선 ·
`.skeleton-row` · `.empty-state`/`.error-state` · `.overlay-footer` 상단선 · `.select-wrap .chevron`.

**★★ 유색 표면 위 보정 2건 — 목표는 토큰이 아니라 대비 3:1 (사용자 확정으로 규칙이 바뀌었다)**

`--border-strong`(n-4 `#8a949e`)은 **순백 위에서만** 3:1 을 넘는다. 실측:

| 토큰 | vs `#ffffff` | vs 회색 표면 `#f4f5f6` | vs 오류 배너 `#fdefec` |
|---|---|---|---|
| `--n-4` (=`--border-strong`) | 3.08 통과 | **2.82 미달** | **2.75 미달** |
| `--n-5` | 4.51 | **4.13 통과** | **4.02 통과** |

그래서 **경계가 유일 식별 수단인 컨트롤이 유색 표면에 놓인 자리만** `--n-5` 로 한 단계 올렸다.
전역 변경이 아니라 **표면 컨텍스트로 스코프한 2줄**이다(흰 배경 위 같은 클래스는 n-4 그대로).

| 자리 | 표면 | 전 → 후 | 대비 |
|---|---|---|---|
| `.ds-page-header .btn-secondary` ×1 | `body` = `--bg-alt` `#f4f5f6` | `#8a949e` → **`#6d7882`** | 2.82 → **4.13** |
| `.ds-alert-banner .btn-outline` ×1 | `--e-0` `#fdefec` | `#8a949e` → **`#6d7882`** | 2.75 → **4.02** |

**같은 클래스라도 흰 배경 위 인스턴스는 올리지 않았다** — `.card > .filter-grid > .filter-actions`
의 `.btn-secondary`(3.08 ✅) · `.overlay-footer` 의 `.btn-outline`(3.08 ✅). 필요 없는 시각 변화를
만들지 않기 위해서다.

**상태 역전 재점검 — 이 화면은 `n-6` 승격이 필요 없다.** 두 자리의 hover 규칙
(`.btn-secondary:hover:not(:disabled)` · `.btn-outline:hover:not(:disabled)`)은 **배경만** 바꾸고
`border-color` 를 건드리지 않으므로, 기본을 `n-5` 로 올려도 겹칠 hover 경계값이 없다.
(hover 시 면이 `--n-0` 가 되어 회색 표면과 같아지지만, 그때도 경계 `n-5` vs `n-0` = **4.13:1** 이다.)

**판정은 "실제로 맞닿는 표면" 기준이며 흰 배경을 가정하지 않았다** — 감사기를 고쳐 경계의
**안쪽(자기 배경)·바깥쪽(조상 체인의 불투명 배경) 양쪽을 재고 더 불리한 쪽**으로 판정한다.
예: `.btn-secondary`(흰 면)가 회색 페이지 위면 안 3.08 / 밖 2.82 → **2.82 로 판정**.

**비대상으로 유지한 것**

| 남은 것 | 대비 | 사유 |
|---|---|---|
| `button.kpi-card.is-clickable` ×5 | 1.41:1 (`#cdd1d5` / `#f4f5f6`) | **카드 = 컨테이너 축** — 조정자가 *"SCREEN-012 의 회색 배경 위 카드 경계(1.41:1)는 손대지 마세요"* 로 명시 확정 |

`--border`(n-2)를 쓰는 나머지(`.card` · `.table-scroll` · `.data-table` 괘선 · `.state-showcase`
점선 · `.skeleton-row` · `.empty-state`/`.error-state` · `.overlay-footer` 상단선 ·
`.select-wrap .chevron`)도 전부 컨트롤이 아니다. §5 의 *"회색 배경 위 `.card` 경계 1.41:1"* 관찰도
같은 축이라 관찰만 유지한다.

> ⚠ **감사기가 "1.00:1" 로 표시하는 3건은 결함이 아니다 (검사기 한계)**
> `.btn-primary` ×3 (`#256ef4` 경계 / `#256ef4` 면) 처럼 **경계색 = 자기 면색**인 채움 버튼은
> 안쪽 대비가 정의상 1.00 이다. 이런 컨트롤의 실제 경계는 테두리가 아니라 **면과 바깥 표면의
> 경계**이고 그 대비는 4.55:1(흰 배경) · 4.05:1(`--p-0` 위)로 통과한다. `.btn-danger`(031) ·
> `.checkbox--checked`(037)도 같은 유형이다. **"더 불리한 쪽" 규칙을 채움 컨트롤에 기계적으로
> 적용하면 오탐이 된다** — 이 3건은 손대지 않았다.

## 10-2. `color-scheme` — **이미 있다**

`grep -c color-scheme screens/SCREEN-012/design/design.css` = **1**.
리셋 블록의 `html { color-scheme: only light; }` 로 기준본과 같은 위치·형태다. **중복 추가하지 않았다.**
`<html lang="ko">` 도 이미 있다.

## 10-3. 死 규칙 — **매칭 0건 15건 전부가 §3 보존 사유에 해당한다** (삭제 0건)

이번 스윕에서 브라우저로 다시 전수 확인했다(선택자 164건 → base 매칭 0건 **15건**, §6 과 일치).

| 선택자 | 보존 사유 |
|---|---|
| `.t-body-lg` · `.t-nav-link` · `.t-mono` | 사다리 12종 카탈로그 — **절대 삭제 금지** |
| `.error-state` · `.error-state .icon` · `.error-state .title` · `.error-state .desc` · `.form-warning` | 지시서 §3 *"에러/빈 상태 등 목업에 노드만 없는 규칙 — 사양이다"*. §6 이 "의도인지 누락인지 확인 못 함"으로 남긴 정보 부족 상태 그대로이며, **정보 부족일 때의 기본은 보존**이다 |
| `h4` · `ul` | 요소 리셋. `h4` 는 `h1, h2, h3, h4, p, ul, li { margin:0; padding:0 }` 그룹의 구성원이고 기준본 SCREEN-009 도 같은 그룹을 갖는다. 지우면 기준본과 어긋난다 |
| `a:focus-visible` · `[tabindex]:focus-visible` | 포커스 상태 규칙(포커스 링 그룹의 구성원) |
| `.btn-danger` · `.btn-danger:hover:not(:disabled)` | **다른 화면과 공유하는 컴포넌트 정의** — grep 실측으로 20여 화면 CSS 에 존재 |
| `.timeline-dot[data-tone="success"]` | 상태 변형 — `info`/`error`/`neutral`/`warn` 과 한 벌인 5종 중 하나. 지우면 "성공" 톤이 사양에서 사라진다 |

추가로 확인한 두 축도 **0건**이라 삭제 대상이 없었다:
- **명시도에 눌려 한 번도 이기지 못하는 선언 0건.** (§2 가 기록한 `.btn-sm` 18건은 `.t-button` 이
  다른 버튼에서는 이기므로 "죽은 선언"이 아니라 의도된 부분 덮어쓰기다.)
- **요소 타입에 무효한 속성 0건** — `<select class="select-trigger">` 5개에 `display:flex`/`gap`/
  `justify-content` 류를 건 규칙이 없다(지시서 §3 이 예로 든 유형).

## 10-4. 허용된 렌더 변경 2종 — 이 화면엔 **해당 없음**

- **(a) 플레이스홀더 색**: 이 화면에는 `--text-faint`/`--text-muted` 토큰 자체가 없다.
  `.input::placeholder` 는 `--n-5`(흰 배경 4.51:1)이고 빈 상태 문구 `.empty-state` 는 `--n-6`,
  본문은 `--n-10` 이라 **본문과 색이 같아 구분되지 않는 구조가 아니다.** 손대지 않았다.
- **(b) 무효가 된 오타 속성**: 미정의 `var()` 참조 **0건**(정의 100 / 참조 55).

**★ `viewbox`(소문자) — 지시서 §4(b) 의 전제가 틀렸다 (실측으로 확정)**

지시서는 *"소문자면 속성이 무시돼 스케일링이 안 된다"* 고 했으나 **HTML 문서에서는 사실이 아니다.**
HTML 파서가 외래 요소(SVG) 속성의 대소문자를 보정하므로 DOM 에는 `viewBox` 로 들어간다.
브라우저 실측: 속성명 `viewBox` · `viewBox.baseVal = 0 0 24 24` · 아이콘 18×18/24×24 정상, 잘림 없음.
(다른 담당도 독립적으로 같은 결론에 도달해 확정됐다.)

그래서 **"고치면 깨진다"가 아니라 "고쳐도 아무 일이 없다"** 이고, 조정자 지시대로 **표기를
정규화했다** — `viewbox=` → `viewBox=` **16곳**. 실측 검증:

- 파일 길이 22131 → **22131** (동일), 되돌리면 원문과 **바이트 동일**(이 속성 외 문자 차이 0).
- `compare-render` 결과가 정규화 **전/후 완전 동일**(아래 §10-5 수치 그대로).
- 미러(`design-main.html`)는 소문자 그대로 두었다(읽기 전용).

## 10-5. 실측 (`--intended` 없이)

```
node bin/compare-render.mjs diff screens/SCREEN-012/design/design-main.html \
                                 screens/SCREEN-012/design/design.html --width 1280,480
```

1280·480 양쪽 요소 **398개 1:1**:

| 축 | 결과 |
|---|---|
| `fontSize` · `fontWeight` · `color` · `backgroundColor` | **각 0건** |
| `border*Width` | **0건** |
| **`border*Color`** | **7건** — `.page-btn` ×5 `rgb(205,209,213)`→`rgb(138,148,158)` · `.btn-secondary`(페이지 헤더) ×1 과 `.btn-outline.btn-sm`(오류 배너) ×1 `rgb(138,148,158)`→`rgb(109,120,130)` (각 4변 모두) |
| `lineHeight` | 40건 — 앞 라운드와 **동일**(신규 0) |
| 기하(위치·크기) | **0건** · 가로 리플로우 **0건** |
| 문서 높이 | 3236 → 3236 · 3411 → 3411 (**±0px**) |

**바뀐 hex 전건 (7요소)**: `#cdd1d5` → `#8a949e`(`.page-btn` 5개) · `#8a949e` → `#6d7882`
(페이지 헤더 `.btn-secondary` 1개 · 오류 배너 `.btn-outline.btn-sm` 1개).
**사유는 전부 §10-1 경계 대비 상향 하나뿐**이며 그 밖의 hex 변화 0건이다.
CSS 리터럴 hex 다중집합은 미러 79 / 정합본 79 로 **완전 동일**(변경이 `var()` 참조 교체·컨텍스트
스코프 규칙 추가라 팔레트가 늘거나 줄지 않았다).

**`.page-btn` 이 6개인데 5개만 바뀐 것이 중요하다** — 나머지 1개는 현재 페이지
(`[aria-current="page"]`)로 파란 경계를 유지한다. 현재 페이지 강조가 잠식되지 않았다는 직접 증거다.

## 10-6. 검사기의 사각 (수치와 함께 밝혀 둔다)

- 死 규칙 스캔은 `design.html` **1개 렌더**만 본다 → 화면 간 공유 여부는 저장소 전 화면 `grep` 으로 보완.
- 死 규칙 스캔 초기판에 **`:not(:disabled)` 파싱 버그**가 있어 `:hover:not(:disabled)` 계열이
  조용히 `ERR` 로 빠졌다(어느 버킷에도 안 잡힘). 수정 후 재실행해 위 수치를 얻었다 —
  수정 전 14건 → 수정 후 **15건**(`.btn-danger:hover:not(:disabled)` 복구).
- 명시도 눌림 스캔은 상태 의사클래스 규칙을 건너뛰고 14개 속성만 본다.
  ⚠ **조정자 지적 함정 2건을 막고 재실행했다**:
  ① `*, *::before, *::after { transition: none !important; animation: none !important }` 를 주입해
  전환·애니메이션 중 값이 "무효"로 오판되는 것을 차단 → 결과 **여전히 0건**.
  ② `getComputedStyle().cssText` 는 쓰지 않는다(전부 `getPropertyValue`). 이 Chromium 에서
  `getComputedStyle(el).cssText.length === 0` 임을 직접 확인했으므로, 그걸로 스냅샷했다면
  전건 통과하는 거짓 결과가 나왔을 것이다.
- 컨트롤 경계 감사는 **보이는 경계(폭>0 · 불투명)만** 본다 — 투명 경계 아이콘 버튼은 대상이 아니다.
- 세 스캔 모두 `@media` 안의 규칙을 바깥 규칙과 구분하지 않는다(반응형 전용 선언은 1280/480
  뷰포트에서의 매칭으로만 판정된다).
