# SD-006 (SCREEN-026 프리셋 관리 화면) — 관례 정합 노트

정본 파일: `design.css` + `design.html`
기준본(관례): SCREEN-009 `design/design.css`
같은 폴더의 `design-main.{html,css}` 는 서버 현재본의 로컬 미러이며 이번에 수정하지 않았다.

재디자인이 아니다. 바꾼 것은 ①시맨틱 색 토큰 접두어 ②표면·모션 별칭 이름 ③타이포 지정 방식
④`color-scheme`·폰트 스택 뿐이고, 색값·간격·구성요소·문구는 그대로다.

---

## 0. 착수 확인 — 색 스케일은 이미 정합돼 있었다

지시서가 "이 파일은 토큰이 이미 기준본과 같을 가능성이 높다"고 했고, 파싱해 대조한 결과 그대로였다.

| 대조 | 수치 |
|---|---|
| 기준본 `:root` 토큰 | 101개 |
| 원본 `:root` 토큰 | 124개 |
| 이름이 겹치는 토큰 | 52개 |
| 그중 **값 충돌** | **0건** (충돌 4건은 전부 값이 아니라 표기 — 폰트 스택 1 + `rgba` 공백 3) |

`--n-*` `--p-*` `--s-*` 33개는 이름·단계·값이 기준본과 **완전 일치**해 한 글자도 손대지 않았다.
따라서 이 파일의 색 작업은 **시맨틱 4계열의 접두어**뿐이었다.

### 시맨틱 접두어 — 값(hex) 기준 매핑

자릿수 규칙이 아니라 hex 로 기준본 이름을 역인덱스해 찾았다. 결과적으로 단계 숫자가 보존됐지만,
그건 규칙을 적용한 결과가 아니라 **값 대조가 그렇게 나온 것**이다.

| 원본 | → | 확인한 값 (0→7 순) |
|---|---|---|
| `--info-0…7` | `--i-0…7` | `#e7f4fe #d3ebfd #9ed2fa #5fb5f7 #2098f3 #0b78cb #096ab3 #085691` |
| `--warn-0…7` | `--w-0…7` | `#fff3db #ffe0a3 #ffc95c #ffb114 #c78500 #9e6a00 #8a5c00 #614100` |
| `--err-0…7` | `--e-0…7` | `#fdefec #fcdfd9 #f7afa1 #f48771 #f05f42 #de3412 #bd2c0f #8a240f` |
| `--ok-0…7` | `--su-0…7` | `#eaf6ec #d8eedd #a9dab4 #7ec88e #3fa654 #228738 #267337 #285d33` |

⚠ SD-009 에서 문제가 됐던 두 자리(`#ffb114` → `--w-3`, `#1f4727` → `--su-8`)는 이 파일에서
**`#ffb114` 가 이미 `--warn-3`** 이었고 `#1f4727` 은 아예 없다. 어긋난 자리는 0건이다.

**기준본에 있는 8~10 단계는 추가하지 않았다.** 원본이 갖고 있지 않았고, 넣으면 새 hex 가 생겨
"hex 집합이 원본과 동일해야 한다"는 검증 축이 깨진다.

### 수동 판단분 (값 불변, 이름만)

| 원본 | 조치 | 근거 |
|---|---|---|
| `--surface: #ffffff` | `--bg-page` | 관례표 지정 이름. 값 동일 |
| `--surface-alt: var(--n-0)` | `--bg-alt` | 관례표 지정 이름. 값 동일 |
| `--row-hover-bg: #fffbeb` | `--row-hover` | 기준본·완료 실례(SCREEN-018/024)의 이름. 설명 주석도 그대로 유지 |
| `--dur-fast/base/slow` | `--duration-fast/base/slow` | 기준본 + 완료 실례 3건이 전부 `--duration-*` |
| `--shadow-sm/md/lg` | `rgba(14,21,40,…)` 공백 제거 | 기준본 표기. **계산값 동일** |
| `--border`, `--border-strong` | 그대로 | 이미 `var(--n-2)`/`var(--n-4)` 로 관례와 일치 |

### 유지한 화면 전용 별칭 (판단 지점 — 아래 §5 참조)

`--text`(n-9) · `--text-muted`(n-6) · `--text-faint`(n-6) · `--focus-ring`(p-5) 4개는 남겼다.
기준본에는 없는 이름이지만 **경쟁하는 색 스케일이 아니라 팔레트를 가리키는 별칭**이고,
값 충돌이 0건이다. 특히 이 파일의 `--text-faint` 는 `n-6` 인데 기준본의 `.text-faint` 는 `n-5` 라,
클래스로 옮기면 **색이 실제로 바뀐다**. "색을 바꾸지 않는다"가 우선이라 토큰으로 뒀다.

## 1. 타이포 — 사다리 유틸 `.t-*` 12종

`:root` 의 `--fs-*`/`--fw-*`/`--lh-*` **30개를 제거**하고, 기준본 verbatim 값으로 `.t-*` 12종을
CSS 에 정의한 뒤 **HTML 에서 클래스로** 지정했다. 유틸 정의는 컴포넌트 규칙보다 **앞**에 둔다
(같은 명시도에서 컴포넌트 override 가 이기게 하려는 의도 — 아래 ghost 버튼 참조).

HTML 유틸 사용: `t-button` 34 · `t-body-sm` 25 · `t-caption` 19 · `t-label` 14 · `t-title-sm` 9 ·
`t-title-md` 3 · `t-body-md` 2 · `t-title-lg` 1.

### 컴포넌트에 남긴 `font-size` (사다리 밖)

기준본 자신도 사다리 밖 `font-size` 를 10건 갖고 있으므로 이건 결함이 아니다.

| 남긴 것 | 값 | 사유 |
|---|---|---|
| `.psm-btn--ghost` · `.psm-btn--sm` | 15px | 15px/**500** — 사다리에 없는 조합(body-sm 은 400) |
| `.psm-state-panel__title` | 14px/700 | 사다리에 없는 조합(label 은 600) |
| `.psm-inline-state__title` | 15px/600 | 사다리에 없는 조합 |
| `.psm-chip` | 13px/500 | 사다리 밖 소형 |
| `.psm-chip__shape` | 10px/700 | 사다리 밖 장식 |
| `.psm-tag-ref` · `.psm-charcount` · `.psm-label-picker__shape` | 11px | 사다리 밖 소형 |
| `.psm-checkbox` | 13px/700 | 체크 글리프(✓) 크기 |
| `.psm-inline-state__icon` | 18px | 이모지 글리프 크기 |
| `.psm-alert__icon` · `.psm-modal-preview__close` | 16px | 글리프(ⓘ·×) 크기 |

`.psm-btn` 은 사다리(`t-button` 17/500/1.4)를 HTML 로 옮겼고, ghost·sm 은 **CSS 가 뒤에 와서**
`font-size:15px` 만 덮어쓴다(weight 500·line-height 1.4 는 유틸에서 옴). 실측 `fontSize` 차이 0건으로
이 순서가 실제로 성립함을 확인했다.

### 사다리 밖이지만 `px` 로 되돌린 두 자리

- `.psm-card__desc { min-height: calc(var(--fs-body-sm) * var(--lh-body-sm) * 2) }`
  → `calc(15px * 1.6 * 2)`. 사다리 변수를 지우므로 리터럴로 고정했다(계산값 48px 동일).
- `.psm-alert__icon { line-height: var(--lh-body-sm) }` → `line-height: 1.6` (동일 값).

## 2. `color-scheme` · `lang` · 폰트 스택

- `html { color-scheme: only light; }` 추가. 원본에는 `html` 규칙 자체가 없었다.
  (지시서 본문의 "넣지 않는다"는 폐기됐고, 기준본 SCREEN-009 가 실제로 갖고 있다.)
- `<html lang="ko">` 는 **원본에 이미 있어** 손대지 않았다.
- `--font-body` 에 `-apple-system` 추가 — 관례 문자열 verbatim.
  **실측 결과 이 교체로 인한 폭 변화는 0건**이었다(1280·480 두 폭 전부 `dw`·`dx` 0).
  지시서가 예외로 인정해 둔 ±1~2px 조차 나오지 않아 사유를 적을 대상이 없다.
- `--font-mono` 는 이미 관례 문자열과 동일해 변경 없음.

## 3. 브라우저 실측 (Chromium 1228, 뷰포트 1280×900 · 480×900)

```
node bin/compare-render.mjs diff screens/SCREEN-026/design/design-main.html \
                                 screens/SCREEN-026/design/design.html --width 1280,480
```

두 폭 모두 동일한 결과다(요소 358개 1:1 대조).

| 축 | 결과 |
|---|---|
| fontSize | **0건** |
| fontWeight | **0건** |
| color · backgroundColor · border*Color · border*Width | **0건** |
| lineHeight | 71건 (전부 사다리 규정값 적용) |
| 가로 변화(`dx≠0` 또는 `dw≠0`) | **0건 — 가로 리플로우 없음** |
| 문서 높이 | 5315 → 5259 px (**-56px**) |

`--intended` 는 쓰지 않았다(변화를 전부 출력해 직접 확인).

### lineHeight 71건의 내역 — 전부 의도된 사다리 적용

원본은 사다리의 **크기·굵기만** 쓰고 line-height 는 루트의 1.6 을 상속시키고 있었다.
유틸은 삼요소 세트라 규정 line-height 가 함께 붙는다.

| 대상 | 전 → 후 | 근거 |
|---|---|---|
| breadcrumb · 카드 푸터 · 페이지네이션 · 도움말 · 카운트 · 인라인 상태 메시지 · 쇼케이스 주석 | 22.4 → 21px | `t-caption` 1.5 |
| 이벤트 배지 · 필드 라벨 · 상태 쇼케이스 라벨 | 22.4 → 19.6px | `t-label` 1.4 |
| `.psm-tag-ref` | 17.6 → 15.4px | 부모(`t-label`) 상속 |
| 오버레이 h2 · 모달/다이얼로그 제목 | 28.8 → 26.1px | `t-title-md` 1.45 |
| `input.psm-input` | `normal` → 24px | `t-body-sm` 1.6 (min-height 44px 고정이라 `dh` 0) |

세로 이동(`dy`)은 이 축소가 누적된 결과이고, **폭·색·크기는 전부 불변**이다.
가장 큰 개별 축소는 상태 쇼케이스 라벨 문단 `dh -8px`(2줄 × 2.8px)이다.

### 폭 지배 검사 — 결함 없음

```
node bin/compare-render.mjs width screens/SCREEN-026/design/design.html \
     --target '.psm-input' --vary '.psm-field__help' --width 1280
node bin/compare-render.mjs width screens/SCREEN-026/design/design.html \
     --target '.psm-modal-preview' --vary '.psm-field__help' --width 1280
```

도움말 문장을 ×2·×4·축소로 바꿔도 `.psm-input` 647px, `.psm-modal-preview` 695px 로 **전부 고정**.
SD-009 의 결함 (a)는 이 파일에 없다. 폭이 `grid-template-columns: 1.4fr 1fr` 와 `width:100%` 로만
정해지고 `align-items: flex-end` 를 쓰는 flex 필터 폼 자체가 없기 때문이다.

`margin-left:auto` / `margin:auto` 는 파일 전체에 **0건** — SD-009 의 결함 (b)도 없다.

## 4. 입력 경계 대비 — 수치 보고만 (고치지 않음)

`.psm-input` `.psm-select` `.psm-textarea` `.psm-btn` `.psm-label-picker` 의 테두리는
`--border`(= `--n-2` `#cdd1d5`)다. 흰 배경 위 실측:

| 토큰 | hex | vs `#ffffff` | vs `--n-0` `#f4f5f6` |
|---|---|---|---|
| `--n-2` (= `--border`) | `#cdd1d5` | **1.54:1** | 1.41:1 |
| `--n-3` | `#b1b8be` | 2.01:1 | 1.84:1 |
| `--n-4` (= `--border-strong`) | `#8a949e` | **3.08:1** | 2.82:1 |
| `--n-5` | `#6d7882` | 4.51:1 | 4.13:1 |
| `--n-6` (= `--text-muted`/`--text-faint`) | `#58616a` | 6.30:1 | 5.77:1 |

WCAG 1.4.11(비텍스트 UI)은 3:1 을 요구하므로 입력 경계는 **미달**이다.
지시서대로 **고치지 않았다**(9건 공통 결정 대상). 참고로 `.psm-checkbox` 만 `--border-strong` 을
써서 3.08:1 로 통과한다 — 같은 화면 안에서 경계 대비 기준이 갈려 있다.

## 5. 죽은 규칙 — 렌더 결과 보존, 기록만

브라우저에서 셀렉터 151건을 `querySelectorAll` 로 세어 **매칭 0건 13건**을 확인했다.
전부 원본에 있던 것이고 이번에 생긴 게 아니며, 렌더 결과에 영향이 없어 **그대로 뒀다**.

| 셀렉터 | 판정 |
|---|---|
| `.psm-root select` · `.psm-root input` · `.psm-root textarea` | 폼 컨트롤이 전부 `.psm-overlay-showcase` 안에 있어 매칭 0. 다만 그쪽에 같은 `font-family: inherit` 규칙이 **별도로 있어** 실효는 없다. 본문에 폼이 생길 때를 위한 방어 규칙으로 유지 |
| `.t-body-lg` · `.t-nav-link` · `.t-mono` · `.t-display-sm` | 이 화면이 쓰지 않는 사다리 단계. 관례가 "12종을 정의"하라고 하므로 유지 |
| `.psm-visually-hidden` · `.psm-btn--full` · `.psm-btn-row` · `.psm-page-ellipsis` · `.psm-btn.is-disabled` | 이 목업이 쓰지 않는 컴포넌트/상태 규칙 |
| `.psm-chip--unlinked .psm-chip__shape` | 미연결 칩에는 형태 배지 자식이 없어 매칭 0. 방어 규칙 |

## 6. 확인 필요 (지어내지 않고 남긴다)

- **`--text-faint` 와 `--text-muted` 가 둘 다 `var(--n-6)` 로 같은 값**이다.
  그 결과 `.psm-card__desc.is-empty`(설명 없음 플레이스홀더)가 실제 설명 문장과 **색이 완전히 같아**
  구분되지 않는다. `.psm-card__footer` · `.psm-charcount` · `.psm-page-ellipsis` 도 같은 상태다.
  기준본은 `.text-faint` 를 `--n-5` 로 한 단계 옅게 둔다. 원본 주석이 "60단 이상 규칙 준수"라고
  적힌 것으로 보아 대비를 위해 의도적으로 올린 것일 수 있으나 근거를 확인하지 못했다.
  **색이 바뀌는 변경이라 손대지 않았다.**
- `--row-hover` 주석이 "SCREEN-005 와 동일 토큰 세트를 유지하기 위해" 선언만 해 뒀다고 적는데,
  이 화면에는 표가 없어 사용처가 0이다. 원본 서술이라 그대로 뒀다.

---

## 7. 최종 정리 스윕 (#9) — 경계 상향 · faint 분리 · 죽은 규칙 정리

앞 라운드와 달리 **렌더가 의도적으로 바뀐다.** 아래 변화 전건과 수치를 남긴다.

### 7.1 폼 컨트롤·버튼·리스트 경계 → `--border-strong` (5자리)

`--border`(=`--n-2` `#cdd1d5`)는 순백 위 **1.54:1** 로 WCAG 1.4.11(비텍스트 UI 3:1) 미달이다.
`--border-strong`(=`--n-4` `#8a949e`)로 올렸다. `:root` 에 두 별칭이 이미 있어 신설은 없다.

| 자리 | 전 → 후 | 안쪽면 대비 전→후 | 바깥면 대비 전→후 |
|---|---|---|---|
| `.psm-btn` 기본 경계 | `--border` → `--border-strong` | 1.54 → **3.08** (흰) | — |
| `.psm-btn--secondary` 경계 | `--border` → `--border-strong` | 1.41 → 2.82 (n-0 채움) | 1.54 → **3.08** (흰) |
| `.psm-input` · `.psm-select` · `.psm-textarea` | `--border` → `--border-strong` | 1.54 → **3.08** | 1.54 → **3.08** |
| `.psm-page-btn` | `--border` → `--border-strong` | 1.54 → **3.08** (흰 채움) | 1.41 → 2.82 (n-0 페이지면) |
| `.psm-label-picker`(리스트박스 바깥 경계) | `--border` → `--border-strong` | 1.54 → **3.08** | 1.54 → **3.08** |

⚠ **완전히 해소되지는 않는다.** 회색 표면(`--n-0` `#f4f5f6`)과 맞닿는 면은 n-4 로도 **2.82:1** 이라
3:1 에 미치지 못한다 — `.psm-btn--secondary`(채움이 n-0) 와 `.psm-page-btn`(페이지 바탕이 n-0) 두 자리다.
3:1 을 채우려면 n-5(4.13:1)가 필요한데, 지시서가 목표값을 n-4 로 못박았고 컨트롤마다 경계 단계가
갈리는 편이 더 나쁘다고 판단해 **n-4 로 통일**했다. 수치만 남긴다.

**올리지 않은 자리** — `.psm-card`(카드) · `.psm-card__footer`(구획선) · `.psm-state-panel`(패널) ·
`.psm-modal-preview__footer`(구획선) · `.psm-label-picker__row`(리스트 내부 구분선) ·
`.psm-tag-ref`(배지 테두리) 는 컨트롤이 아니라 컨테이너·구획선이다(지시서 §1).
`.psm-checkbox` 는 **원래부터** `--border-strong` 이었다 — 같은 화면 안에서 갈려 있던 정책이 이번에 통일됐다.
**hover/focus 충돌 없음** — `.psm-btn:hover`·`.psm-page-btn:hover` 는 배경만 바꾸고 경계를 건드리지 않으며
`:focus-visible` 은 `--p-5` 라 기본값과 겹치지 않는다. 따라서 hover 를 한 단계 올릴 필요가 없었다.

### 7.2 `--text-faint` → `--n-5` + 회색 표면 2자리 `n-6` 고정 (§6 "확인 필요" 해소)

`--text-faint` 와 `--text-muted` 가 **둘 다 `n-6`** 이라 `.psm-card__desc.is-empty`("설명이 없습니다")가
실제 설명(`.psm-card__desc`)과 색이 **완전히 같아 구분되지 않았다.** faint 를 `--n-5` 로 한 단계 옅게 뒀다.

**배경을 추측하지 않고 실측했다** — 각 소비처에서 조상을 거슬러 올라가 실제로 칠해진 배경색을 읽었다.

| `--text-faint` 소비처 | 실측 배경 | 조치 | 후 대비 |
|---|---|---|---|
| `.psm-card__desc.is-empty` | `#ffffff` (`.psm-card`) | n-5 | 4.51:1 |
| `.psm-card__footer`(+자식 span 18) | `#ffffff` (`.psm-card`) | n-5 | 4.51:1 |
| `.psm-state-panel__title` | `#ffffff` (`.psm-state-panel`) | n-5 | 4.51:1 |
| `.psm-charcount` | `#ffffff` (`.psm-modal-preview`) | n-5 | 4.51:1 |
| `.psm-modal-preview__close` | `#ffffff` (`.psm-modal-preview`) | n-5 | 4.51:1 |
| `.psm-input::placeholder` · `.psm-textarea::placeholder` | `#ffffff` (입력 채움) | n-5 | 4.51:1 |
| **`.psm-inline-state--empty .psm-inline-state__icon`** | **`#f4f5f6`** (아이콘 자신의 `background: var(--n-0)`) | **`n-6` 고정** | 5.77:1 |
| **`.psm-page-ellipsis`** | **`#f4f5f6`** (`.psm-root` 배경 = `--bg-alt`) | **`n-6` 고정** | 5.77:1 |

즉 원본 주석 *"회색 표면 위 보조텍스트는 60단 이상"* 은 **폐기가 아니라 그 자리들에서 계속 유효**하다
— n-5 는 흰 위 4.51:1(AA 통과)이지만 n-0 위에서는 **4.13:1 로 AA 미달**이라 그대로 두면 규칙 위반이 된다.
`--text-muted`(n-6)는 손대지 않았다.

### 7.3 죽은 규칙 — 삭제 5건 / 보존 9건

선택자 **159건**의 매칭 수를 브라우저에서 세고(매칭 0건 14건), 별도로 캐스케이드 승자를 계산해
"명시도에 눌려 한 번도 이기지 못한 선언"을 찾았다(6건, 이 중 3건은 아래 사유로 사실상 유효).

#### 삭제

| 지운 것 | 사유 | 원래 의도(되살릴 때 참고) |
|---|---|---|
| `.psm-visually-hidden` (규칙 전체) | 매칭 0. 상태·`.t-*`·에러/빈상태 어디에도 안 걸리고 `psm-` 접두는 이 화면 전용이라 다른 화면과 공유되지도 않는다 | 스크린리더 전용 텍스트 유틸: `position:absolute; width:1px; height:1px; overflow:hidden; clip:rect(0 0 0 0); white-space:nowrap` |
| `.psm-btn--full` | 매칭 0. 미사용 버튼 폭 변형 | `width: 100%` |
| `.psm-btn-row` | 매칭 0. 미사용 버튼 배치 헬퍼 | `display:flex; gap: var(--sp-sm)` |
| `.psm-page-header__titles { min-width: 0 }` (규칙 전체) | **명시도에 눌려 死** — `.psm-root [class^="psm-"]`(명시도 200)가 **같은 값** `min-width: 0` 으로 먼저 이긴다 | 값이 동일해 되살릴 의미 없음 |
| `.psm-card__title-row` 의 `min-width: 0` (선언 1개) | 위와 동일 | 위와 동일 |
| `.psm-page-btn` 의 `min-width: 36px` (선언 1개) | **명시도에 눌려 死** — 같은 전역 규칙이 `min-width: 0` 으로 이겨 36px 이 **한 번도 적용된 적 없다** | ⚠ **의도는 살아 있다**: 페이지 버튼의 최소 폭 36px(정사각 터치 타깃). 되살리려면 명시도를 200 이상으로 올리거나(`.psm-pagination .psm-page-btn`) 전역 `[class^="psm-"]` 를 좁혀야 한다. 지시서 §3 "살리지 말고 삭제"에 따라 이번에는 지우기만 했다 |

> ⚠ **파생 관찰**: `.psm-root [class^="psm-"] { min-width: 0 }` 는 `psm-` 네임스페이스 **전체**의
> `min-width` 를 눌러 버린다. 이번에 지운 3건이 그 결과다. 규칙 자체를 좁히는 것은 렌더가 바뀌는
> 변경이라 이번 승인 범위 밖이라 **손대지 않았다.**

#### 보존

| 남긴 것 | 사유 |
|---|---|
| `.t-body-lg` · `.t-nav-link` · `.t-mono` · `.t-display-sm` | 관례가 `.t-*` **12종 정의**를 요구 (지시서 명시) |
| `.psm-btn.is-disabled` · `.psm-page-btn[disabled]` | 상태 규칙 — 정적 목업에 그 상태 노드가 없을 뿐 |
| `.psm-chip--unlinked .psm-chip__shape { display:none }` | 변형 사양 — "미연결 칩에는 형태 배지를 표시하지 않는다". 미연결 칩에 형태 자식이 없어 매칭 0일 뿐 |
| `.psm-page-ellipsis` | 페이지네이션 컴포넌트의 생략 표기. 페이지 수가 많을 때의 사양이다(색은 §7.2 대로 n-6 고정) |
| `.psm-root select` · `.psm-root input` · `.psm-root textarea` (4선택자 규칙 중 3개) | 폼 컨트롤이 전부 `.psm-overlay-showcase` 안에 있어 매칭 0이지만 `button` 은 매칭된다. 리셋(폰트 상속)에서 3개만 떼면 본문에 폼이 생겼을 때 커버가 사라지고 렌더 이득은 0 |
| `.psm-btn { color: var(--text) }` | 캐스케이드 승리는 0이지만 **base+modifier 구조의 base 기본값**이다. 이 목업의 모든 버튼이 변형을 달고 있을 뿐, 변형 없는 `.psm-btn` 에는 이 값이 적용된다 |
| `.psm-grid { grid-template-columns: repeat(3, 1fr) }` | 死가 아니다 — `@media (max-width: 1280px)` 가 **경계 포함**이라 측정 뷰포트 1280px 에서만 2열이 이겼을 뿐, **1281px 이상에서 3열로 살아 있다**(측정 아티팩트) |
| `.psm-input, .psm-select, .psm-textarea { font-family: inherit }` | `.psm-overlay-showcase input`(명시도 101)이 **같은 값**으로 이기지만, 이건 컴포넌트 자신의 base 선언이고 저쪽은 쇼케이스 스코프 정규화라 층이 다르다 |

### 7.4 렌더가 바뀐 것 — **전건** (1280 · 480 두 폭에서 동일)

스윕 직전 상태를 역적용해 복원한 사본과 요소 **358개** 1:1 대조. `--intended` 는 쓰지 않았다.

| 축 | 결과 |
|---|---|
| fontSize · fontWeight · lineHeight · backgroundColor · border*Width | **0건** |
| color | **35건** — 전부 `#58616a`(n-6) → `#6d7882`(n-5) |
| border*Color | 컨트롤 **10요소** `#cdd1d5`(n-2) → `#8a949e`(n-4) + 위 35건의 `currentColor` 파생 |
| 기하 변화 | **0건** (가로 리플로우 0, 문서 높이 3303 → 3303 **±0px**) |

**바뀐 hex 전건 — 2종뿐이고 둘 다 지시서로 설명된다**

| 전 → 후 | 건수 | 사유 |
|---|---|---|
| `#58616a` → `#6d7882` | color 35 (`.psm-card__footer` 9 + 그 자식 span 18 + `.psm-card__desc.is-empty` 3 + `.psm-state-panel__title` 3 + `.psm-modal-preview__close` 1 + `.psm-charcount` 1) + `::placeholder` 2(의사요소라 위 집계 밖 · 별도 실측) | **§4-(a) 플레이스홀더 색 분리** |
| `#cdd1d5` → `#8a949e` | border 10요소 (`.psm-input` 1 · `.psm-select` 1 · `.psm-textarea` 1 · `.psm-label-picker` 1 · `.psm-page-btn` 3 · `.psm-btn--secondary` 3) | **§1 경계 상향** |

**§1·§4 로 설명되지 않는 hex 변화는 0건이다.**
`.psm-inline-state--empty .psm-inline-state__icon` 이 diff 에 **나타나지 않는 것**이 §7.2 의 n-6 고정이
의도대로 걸렸다는 증거다(faint 를 그냥 n-5 로 바꿨다면 여기도 함께 바뀌었을 것이다).

### 7.5 판단이 필요했던 지점

1. **`.psm-label-picker` 를 "컨트롤"로 볼 것인가** — 겉보기엔 박스지만 체크박스 다중선택 **리스트박스
   컨트롤**이다. 바깥 경계만 올리고 **내부 행 구분선(`__row`)은 구획선으로 보아 그대로 뒀다.**
2. **`.psm-page-btn` 을 올릴 것인가** — "버튼 경계는 폼 컨트롤이 아니다"에 걸리지만, 채움이 흰색이라
   **경계가 유일한 식별 수단**인 케이스(지시서가 `.btn-secondary` 를 예로 든 그 유형)라 올렸다.
3. **회색 표면 위 faint** — 지시서가 "실측하고 판단하라"고 한 지점. 조상 체인을 거슬러 실제 배경을
   읽어 **2자리(`inline-state__icon`, `page-ellipsis`)만 n-6 으로 고정**했다. 특히 `.psm-page-ellipsis` 는
   매칭 0인 규칙이라 놓치기 쉬웠는데, `.psm-root` 배경이 `--bg-alt` 라 살아났을 때 회색 위에 놓인다.
4. **`.psm-grid` 를 지울 뻔했다** — 자동 검출은 "한 번도 이기지 못함"으로 표시했지만 원인은
   `max-width: 1280px` 가 **경계를 포함**하는 것이었다. 측정 폭이 곧 판정 조건이 되는 함정이다.

### 7.6 경계 상향 기준 확장 재감사 — 신규 대상 **0건** (이미 §7.1 에서 전부 처리됨)

조율자 정정으로 기준이 *"경계선이 그 상호작용 컨트롤의 **유일한 시각적 식별 수단**이고 3:1 미만이면 상향"*
으로 확장됐다. 상호작용 요소를 전수 재감사한 결과, **§7.1 에서 올린 5자리가 곧 그 대상 전부**였다.

| 컨트롤 | 경계 | 판정 |
|---|---|---|
| `.psm-input` · `.psm-textarea` · `.psm-select` · `.psm-label-picker` | `#8a949e`(n-4) 3.08:1 | §7.1 에서 상향 완료 |
| `.psm-btn--secondary` ×3 · `.psm-page-btn` ×3 | `#8a949e`(n-4) | §7.1 에서 상향 완료. 회색 면 쪽 잔존 2.82:1 은 §7.1 ⚠ 참조 |
| `.psm-btn--primary` ×3 · `.psm-btn--destructive` ×1 · `.psm-page-btn.is-active` ×1 | 채움 `#256ef4`/`#de3412` (주변 대비 4.17~4.56) | **채움으로 식별** — 비대상 |
| `.psm-btn--ghost` ×27 | `border-color: transparent` | **경계가 아예 없다** — 비대상 |
| `.psm-modal-preview__close` ×1 · breadcrumb `a` ×1 | `border: none` / 경계 없음 | 비대상 |

**상태 역전 점검(필수 항목)** — 기본을 n-4 로 올린 뒤 hover 가 더 옅어지는 자리가 없는지 전수 확인:
이 화면에서 `:hover` 가 **경계색을 바꾸는 컨트롤은 0개**다(`.psm-btn:hover`·`.psm-page-btn:hover`·
`.psm-label-picker__row:hover` 는 배경만 바꾼다). `:focus-visible` 은 `--p-5`(#256ef4, 4.55:1)로 기본값과
색상·대비 모두 구분된다. 유일하게 경계색을 바꾸는 `.psm-card:hover`(n-2 → n-3)는 **컨테이너**라 상향 대상이
아니었고 방향도 정상(진해짐)이다. **역전·중복 0건 → hover 상향 불필요.**

### 7.7 `viewbox`(소문자) — 이 화면에는 **0건**

`design.html` 에 인라인 SVG 자체가 없어 해당 사항이 없다(소문자 `viewbox=` 0 · `viewBox=` 0).
같은 배치의 SCREEN-024(5건)·SCREEN-030(18건)은 각 notes 에 수정·실측을 기록했다.
왕복 검증도 형식적으로 통과한다 — 문자 단위 diff **opcode 0건**(수정 전후 파일이 바이트 동일).
같은 산출물 세트의 표기 드리프트(정규화 4화면 vs 소문자 5화면) 해소 대상에서 이 화면은 **무관**하다.


### 7.8 기계 검출기 — 함정 2건 차단 후 재판정 (결론 불변)

| 함정 | 차단 방법 | 확인 |
|---|---|---|
| ⓐ `transition` 미차단 시 전환 대상 속성 오판 | 로드 후 `*{transition:none!important;animation:none!important}` 주입 + **그 주입 시트는 판정 대상에서 제외**(마커 식별) | `transitionDuration=0s` · `animationName=none` |
| ⓑ `getComputedStyle().cssText` 빈 문자열 → 스냅샷 전부 통과 | **쓰지 않는다.** CSSOM 의 작성자 선언 + 명시도·순서로 캐스케이드를 직접 계산 → computed 값 비교가 없어 ⓐ 도 원리적으로 성립하지 않음(주입은 이중 안전장치) | `cssText === ''` = true 임을 확인하고 판정 근거로 쓰지 않음 |

**재판정 결과**: 규칙 137건 · 매칭 0건 **11건** · 명시도에 눌린 선언 보유 규칙 **3건** — 전부 §7.3 에서
보존 사유를 적어 둔 그 3건(`.psm-btn{color}` base 기본값 · `.psm-grid` 측정 아티팩트 ·
`.psm-input{font-family}` 컴포넌트 base)이다. **가드 적용 전후 결론이 바뀌지 않았다.**

### 7.9 작업 환경 위험 — 스크래치패드가 공유되고 있다 (보고)

작업 중 `scratchpad/` 의 내 검출기 파일이 **다른 담당 에이전트의 동명 파일로 덮어써진 것**을 확인했다.
이후 도구·기준선을 전용 하위 디렉터리 `scratchpad/mine-024-026-030/` 로 옮겨 재구축하고 수치를 다시 냈다.
⚠ 파일명이 겹치면 조용히 덮어써진다 — 다른 담당의 수치도 같은 위험에 노출됐을 수 있다.

### 7.10 확정 기준 재적용 — **표면 기준 3:1**, §7.1 의 잔존 미달 2종 해소

조율자 최종 확정으로 목표가 **토큰 이름(`--border-strong`)에서 "실제로 맞닿는 표면 대비 3:1"** 로
바뀌었다. n-4 는 흰 배경에서만 3:1 을 넘으므로(회색 표면 위 **2.82**), §7.1 ⚠ 에서 *"수치만 남긴다"* 고
적었던 2종을 이제 **`--n-5` 로 올려 해소**했다. 판정 방식은 §7.2 플레이스홀더와 **같은 원리**다 —
조상 체인을 거슬러 실제 배경을 읽고 그 배경에 맞는 단계를 고른다.

| 자리 | 전 → 후 | 안쪽면 | 바깥면 | 불리한 쪽 전→후 |
|---|---|---|---|---|
| `.psm-btn--secondary` ×3 | `--border-strong`(n-4) → **`--n-5`** | 채움 `#f4f5f6` | `#ffffff` | **2.82 → 4.13** |
| `.psm-page-btn` ×3 | `--border-strong`(n-4) → **`--n-5`** | 채움 `#ffffff` | `#f4f5f6`(`.psm-root`) | **2.82 → 4.13** |
| `.psm-input`·`.psm-select`·`.psm-textarea`·`.psm-label-picker` | `--border-strong`(n-4) **유지** | `#ffffff` | `#ffffff` | 3.08 (양면 흰 → 이미 통과, **내리지 않음**) |

⚠ 그 결과 같은 화면에서 **입력류는 n-4, 버튼류는 n-5** 로 단계가 갈린다. 이는 드리프트가 아니라
**새 기준의 직접적 귀결**이다 — 입력류는 모달(흰) 안이고 버튼류는 회색 면과 맞닿는다. 단계를 억지로
통일하면 둘 중 하나가 기준을 어긴다.

**상태 역전 재점검 — 0건.** 이 화면은 `:hover` 가 **경계색을 바꾸는 컨트롤이 0개**이므로(배경만 바꾼다)
기본을 올려도 겹칠 자리가 없다. 다만 hover 시 **채움이 바뀌어 맞닿는 면이 달라지므로** 그 상태의
대비도 계산했다: `.psm-btn--secondary:hover`(채움 n-1) **3.67:1** · `.psm-page-btn:hover`(채움 n-0)
**4.13:1** — 둘 다 통과. `:focus-visible`(p-5)은 색상·대비 모두 구분된다.

**미달 잔존 0종** — 상호작용 요소 전수 재감사에서 새 기준 미달 **0종**이다(§7.1 ⚠ 의 유보 해소).

### 7.11 최종 렌더 변화 — hex 3종, 전부 §1·§4 로 설명됨

pre-sweep 사본과 요소 358개 1:1 대조(1280·480 동일, `--intended` 없음).
`fontSize`·`fontWeight`·`lineHeight`·`backgroundColor`·`border*Width` **0건** · **기하 0건** ·
문서 높이 **3303/3303 · 5259/5259 (±0)**.

| 전 → 후 | 대상 | 사유 |
|---|---|---|
| `#cdd1d5`(n-2) → `#6d7882`(n-5) | **6요소** — `.psm-btn--secondary` 3 · `.psm-page-btn` 3 | §1 경계 상향(표면 기준) |
| `#cdd1d5`(n-2) → `#8a949e`(n-4) | **4요소** — `.psm-input` · `.psm-textarea` · `.psm-select` · `.psm-label-picker` | §1 경계 상향(양면 흰) |
| `#58616a`(n-6) → `#6d7882`(n-5) | **color 35건** + `::placeholder` 2(의사요소, 별도 실측 `rgb(88,97,106)→rgb(109,120,130)`) | §4-(a) 플레이스홀더 색 분리 |

**이 3종 외 hex 변화 0건.** 회색 표면 위 2자리(`.psm-inline-state__icon`·`.psm-page-ellipsis`)가
diff 에 **나타나지 않는 것**이 §7.2 의 n-6 고정이 유지된다는 증거다.
