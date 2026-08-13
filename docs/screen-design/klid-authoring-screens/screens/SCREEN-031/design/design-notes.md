# SD-010 (SCREEN-031 공지 상세 화면) — 관례 정합 노트

이 화면은 **렌더가 2종**이라 정본도 2벌이다.

| 렌더 | 서버 미러(입력 · 수정 안 함) | 정합본(산출물) |
|---|---|---|
| `main` (page) | `design-main.css` · `design-main.html` | **`design.css` · `design.html`** |
| `delete-confirm` (modal) | `design-delete-confirm.css` · `design-delete-confirm.html` | **`design-delete-confirm-aligned.css` · `design-delete-confirm-aligned.html`** |

기준본(관례): SCREEN-009 `design/design.css` · 살아 있는 실례: SCREEN-024 · SCREEN-037.

재디자인이 아니다. 바꾼 것은 ①색·간격 토큰 이름 ②타이포 지정 방식 ③폰트 스택 문자열뿐이고,
색·간격·구성요소·문구는 그대로다. **레이아웃은 한 줄도 바꾸지 않았다**(사유는 §5).

---

## 0. 파일명을 이렇게 정한 이유

다른 8화면은 미러가 `design-main.*` 하나뿐이라 정합본이 `design.*` 를 그대로 쓴다.
이 화면은 미러가 `design-main.*` **와** `design-delete-confirm.*` 두 벌이라, 두 번째 렌더의
정합본에 `design-delete-confirm.*` 를 쓰면 **읽기 전용 미러를 덮어쓴다.**

그래서:

- `main` 정합본 = `design.css` / `design.html` — 다른 8화면과 **동일한 관례**를 유지한다.
- `delete-confirm` 정합본 = `design-delete-confirm-aligned.css` / `.html` — 미러 이름에
  `-aligned` 를 붙여 충돌을 피했다. 렌더 id(`delete-confirm`)가 이름에 그대로 남아 있어
  업로드 단계에서 어느 렌더에 보낼지 파일명만으로 결정된다.

`_sd-meta.md` 와 미러 4개 파일은 손대지 않았다.

## 1. 색 토큰 — 값(hex) 기준 매핑

이 파일의 스케일은 **Tailwind 식 `--neutral-50 … --neutral-950`** 이라 기준본의 `0..10` 과
자릿수 대응이 성립하지 않는다. 자릿수 규칙을 쓰지 않고 **값으로 찾았다.**

1. 기준본 `SCREEN-009/design/design.css` 의 `:root` 를 파싱해 **hex → 기준본 토큰명** 역인덱스를 만들었다
   (기준본 hex 78종, **중복 0** → 역인덱스가 1:1 로 성립함을 먼저 확인했다).
2. 이 파일의 `:root` 를 파싱해 **각 토큰의 값(hex)으로** 기준본 이름을 찾아 old→new 사전을 만들었다.
3. 파일 전체를 정규식 `--[A-Za-z0-9_-]+` **단일 패스**로 매칭해 사전 조회로 치환했다.
   최장 매칭이라 `--neutral-950` 이 통째로 잡히고, 결과를 다시 스캔하지 않으므로 이중 변환이
   원리적으로 불가능하다(문자열 순차 치환을 쓰지 않았다).

**값 기준 매핑의 결과**: 색 토큰 51개 전부가 기준본에 같은 hex 로 존재했고, 자릿수 순서
(50→0, 100→1 … 950→10)와 어긋난 자리는 한 건도 없었다. 다만 그것은 **검증 결과이지 전제가 아니다.**

| 접두어(변환 전) | 변환 후 |
|---|---|
| `--neutral-50/100/…/950` (11) | `--n-0/1/…/10` |
| `--primary-50/…/950` (11) | `--p-0/…/10` |
| `--secondary-50/…/950` (11) | `--s-0/…/10` |
| `--info-50/500/600/700` | `--i-0/5/6/7` |
| `--warn-50/100/500/600/700` | `--w-0/1/5/6/7` |
| `--error-50/100/500/600/700` | `--e-0/1/5/6/7` |
| `--success-50/100/500/600/700` | `--su-0/1/5/6/7` |
| `--surface-white` (#ffffff) | `--bg-page` |
| `--space-xs/sm/md/lg/xl/2xl` | `--sp-xs/sm/md/lg/xl/2xl` (§3) |

**기준본에 값이 없어 그대로 둔 토큰 1건**

- `--row-hover: #fffbeb` — DS 정본 `design-system.md` 의 `do_rules` 가 "표의 행 hover 표면"으로
  명시한 고정값이다. 이 화면엔 표가 없어 **참조 0건이지만 지우지 않았다**(hex 집합 동일성 유지 +
  미러가 갖고 있던 토큰 동일성 유지). 근거 주석도 원문 그대로 옮겼다.

**미사용 토큰을 정리하지 않았다** — `--s-*` 11개, `--i-*` 4개 등은 미러에서도 미사용이었다.
지우면 hex 집합이 달라져 §7 의 대조가 성립하지 않고, 정합 작업의 범위(이름만 바꾼다)를 넘는다.

### 표면·경계 별칭 신설 — 색이 바뀌지 않는 자리에만 적용

`--bg-page`(이미 존재) 외에 `--bg-alt`(=n-0) · `--border`(=n-2) · `--border-strong`(=n-4) 을 정의하고,
**값이 이미 그 단계이던 자리에만** 적용했다.

| 적용 | 자리 |
|---|---|
| `--bg-alt` | `body` 배경 · `.state-mini-card` 배경 |
| `--border` | `.state-reference` 점선 상단 경계 |
| `--border-strong` | `.btn-secondary:hover` 경계 |

**적용하지 않은 자리**

- `n-1` 경계 4곳(`.card` · `.article-divider` · `.state-mini-card` · `.attachment-row`) —
  `--border`(=n-2)로 바꾸면 **선이 진해져 시각이 달라진다.** `var(--n-1)` 그대로 뒀다.
- `.btn-secondary { border-color: var(--n-3) }` · `.btn-ghost:hover { background: var(--n-0) }` ·
  `.attachment-row:hover { background: var(--n-0) }` — 앞은 별칭에 없는 단계이고,
  뒤 둘은 표면이 아니라 **hover 상태색**이라 `--bg-alt` 를 쓰지 않았다.
- `.error-state` 의 `--e-1` 경계 — 의미색이라 중립 별칭 대상이 아니다.

## 2. 타이포 — 사다리 유틸 `.t-*` 12종

CSS 에 사다리를 정의하고 **HTML 에서 클래스로 지정**한다(기준본 방식). 값은 기준본 verbatim.
미러의 `--fs-* --fw-* --lh-*` **30개 변수는 사다리가 대체하므로 전부 제거**했다(잔재 0건).

HTML 유틸 사용 — `main` **26곳**: `t-button` 7 · `t-caption` 8 · `t-body-sm` 5 · `t-label` 3 ·
`t-body-md` 1 · `t-title-lg` 1 · `t-title-sm` 1.
`delete-confirm` **6곳**: `t-button` 2 · `t-title-md` 1 · `t-body-md` 1 · `t-body-sm` 1 · `t-caption` 1.

### 컴포넌트 CSS 에 남긴 `font-size` — main 4건 / delete-confirm 1건

| 남긴 것 | 값 | 사유 |
|---|---|---|
| `body` | 17px / 1.6 | **사다리 밖 base.** 미러에 있던 값이라 빼면 `.t-*` 를 안 단 요소가 17px→16px 로 조용히 작아진다. 기준본 실례 SCREEN-024 도 같은 이유로 `body` 에 17px 을 남긴다 |
| `.error-state-title` | 15px / 600 | **사다리에 없는 조합**(body-sm 15/400 · label 14/600 어느 쪽도 아니다) |
| `.kv-grid dt` / `.kv-grid dd` | 14px / 15px | `.kv-grid` 는 **어느 렌더에도 노드가 없는 死 규칙**이라 클래스를 달 수 없다(§6-1). 사다리 값을 리터럴로 남겼다 |

`delete-confirm` 은 `body` 1건뿐이다. 기준본 자신도 사다리 밖 `font-size` 를 10건 갖고 있으므로
이건 관례 위반이 아니다.

### `.modal-*` 블록은 두 파일에서 동일하게 유지했다

`main` 의 CSS 에도 `.modal-*` 규칙이 통째로 들어 있지만 `design.html` 에는 모달 노드가 없다
(모달은 `delete-confirm` 렌더가 그린다). 두 파일이 갈라지지 않도록 **양쪽 모두** 모달 규칙에서
`font-size`/`font-weight`/`line-height` 를 빼고 사다리 유틸에 맡겼다.
`main` 쪽은 노드가 없어 이 변경의 렌더 영향이 0 이다(실측 dh 0).
→ `.kv-grid` 와 다르게 처리한 기준: **형제 렌더에 노드가 있어 거기서 클래스를 달 수 있는가.**

## 3. `--space-*` → `--sp-*` (스스로 정한 것)

지시서의 확정 관례 표는 **색** 토큰만 규정한다. 그런데 간격 토큰도 기준본은 `--sp-*` 이고
이 파일은 `--space-*` 였다. 값(4/8/16/24/40/64px)이 **기준본과 완전히 같아** 이름만 바꿔도
시각 결과가 변하지 않으므로 이름 드리프트를 함께 없앴다.
(선례: SD-011 이 같은 사유로 `--dur-base` → `--duration-base` 를 바꿨다.)

`--radius-*` · `--shadow-*` · `--ease-*` · `--duration-*` 은 이미 기준본과 이름이 같아 손대지 않았다.

## 4. `lang="ko"` · `color-scheme` · 폰트 스택

- `<html lang="ko">` — 미러에 **이미 있었다**(두 렌더 모두). 그대로 유지.
- `html { color-scheme: only light; }` — 미러에 **이미 있었다**(두 렌더 모두). **넣는다**는
  확정 관례와 일치하므로 그대로 유지(각 CSS 1건).
- 폰트 스택 — 기준본 문자열로 **교체**했다.

```css
--font-body: 'Pretendard GOV', -apple-system, 'Apple SD Gothic Neo', 'Noto Sans KR', sans-serif;
--font-mono: 'D2Coding', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
```

미러는 body 에 `'Pretendard'`(비-GOV)를, mono 에 `'SFMono-Regular', 'SF Mono'` 를 갖고 있었다.
이미 정합돼 게시된 SD 6건이 위 문자열을 verbatim 으로 쓰므로 관례는 **"이 문자열 그대로"** 다.

**이 교체로 생긴 폭 변화: 0건.** 두 렌더·두 뷰포트 모두 `dw≠0` 인 요소가 없다
(예외로 인정받을 ±1~2px 조차 발생하지 않았다). 이 화면의 텍스트가 전부 폭이 컨테이너에서
내려오는 블록이거나 `flex-shrink` 가 걸린 자리라 글자 폭이 기하에 반영되지 않는다.

`--font-mono` 는 이 화면에서 **참조 0건**(`.t-mono` 노드 없음)이라 렌더 영향이 없다.

## 5. 레이아웃 — 손대지 않았다 (의도적)

지시서·프롬프트가 지목한 세 결함 유형을 모두 찾아봤고 **이 화면엔 없다.**

- **(a) 내용이 폭을 지배** — 브라우저 실측으로 확인했다. `.article-body`(410자)를 ×2·×4 로 늘려도
  `main.page` 폭은 1280px 고정, `.attachment-name` 을 ×4 로 늘려도 `li.attachment-row` 폭은
  1280 뷰포트 1102px · 480 뷰포트 302px 고정이다(§7).
  `.attachment-name` 자신은 29~142px 로 변하지만 이건 `overflow:hidden` + `text-overflow:ellipsis`
  가 정상 동작해 **142px 에서 상한이 걸리는** 것이라 결함이 아니다.
- **(b) `margin-left: auto`** — 두 파일 합쳐 **0건**.
- **정렬 붕괴** — `align-items: flex-end` **0건**. `center`/`baseline`/`flex-start` 만 있고,
  `center` 행은 자식 키가 변해도 중심 기준이라 어긋나지 않는다. 실측에서도 `li.attachment-row`
  `dh 0`, `.action-bar`·`.footer-actions` 하위 버튼 전부 `dh 0` 이다.

**없는 결함을 만들지 않기 위해 아무것도 고치지 않았다.**

## 6. 고치지 않고 기록만 남긴 관찰 3건

### (1) 死 규칙 — `.kv-grid` 블록과 `.modal-dialog-close`

`main` 의 CSS 는 `.kv-grid` / `.kv-grid dt` / `.kv-grid dd` / `.kv-grid dd + dt` 4규칙을 갖는데
`design.html` 에는 `.kv-grid` 노드가 없다. 실제로 쓰이는 것은 `.kv-row-wrap` + `.kv-pair` 다.
`.modal-dialog-close`(모달 닫기 버튼) 도 **두 렌더 어디에도 노드가 없다** —
`delete-confirm` 의 헤더에는 제목만 있고 닫기 버튼이 없으며, 그쪽 CSS 는 이 규칙 자체를 갖고 있지도 않다.

**고치지 않았다** — 규칙을 지우면 렌더 결과는 같지만, 이 규칙들이 "아직 안 그린 상태"를 위한
예비인지 잔재인지 이 작업에서 판단할 수 없다. 렌더 결과를 보존하고 기록만 남긴다.
`main` 전용 예비 규칙 `.btn-primary` · `.badge-draft` · `.card-padded` · `.icon-lg` ·
`.visually-hidden`, `delete-confirm` 의 `.icon-sm` · `.icon-lg` 도 같은 성격이라 그대로 뒀다.

### (2) 비텍스트 UI 경계 대비 — WCAG 1.4.11 (3:1) 미달

이 화면엔 입력 컨트롤이 없지만 **버튼·카드 경계**가 같은 축에 걸린다. 흰 배경(`#ffffff`) 기준 실측:

| 토큰 | hex | 흰 배경 대비 | n-0(`#f4f5f6`) 배경 대비 | 판정 |
|---|---|---|---|---|
| `--n-1` | `#e6e8ea` | **1.23:1** | 1.13:1 | 미달 |
| `--n-2` (=`--border`) | `#cdd1d5` | **1.54:1** | 1.41:1 | 미달 |
| `--n-3` | `#b1b8be` | **2.01:1** | 1.84:1 | 미달 |
| `--n-4` (=`--border-strong`) | `#8a949e` | 3.08:1 | 2.82:1 | 통과 |
| `--n-5` | `#6d7882` | 4.51:1 | 4.13:1 | 통과 |

실제 사용처

- `.btn-secondary { border-color: var(--n-3) }` — **2.01:1.** 이 화면의 "수정" 버튼과
  모달의 "취소" 버튼이 이 경계 하나로만 식별된다(배경이 흰색이라 면으로는 구분되지 않는다).
- `.card` · `.article-divider` · `.state-mini-card` · `.attachment-row` = `--n-1` **1.23:1**.
  카드 경계는 그림자(`--shadow-sm`)가 보조하지만 divider·attachment-row 는 선 하나뿐이다.

**고치지 않았다** — 이번 작업의 통과 기준이 *"색 차이 0건"* 이라 색을 바꾸면 정합 작업의 판정을
스스로 깬다. 색 결정은 디자인 소관으로 올린다. 지금까지 완료된 다른 화면에서도 같은 계열의
미달이 보고됐으므로 **화면별이 아니라 DS 축에서 정할 사안**으로 보인다.

### (3) `.modal-scrim` 의 반투명 오버레이

`background: rgba(19, 20, 22, 0.48)` — `rgb(19,20,22)` 는 `--n-10`(`#131416`)과 같은 색이지만
**알파를 실은 대응 토큰이 DS 에 없다.** 억지로 토큰화하지 않고 그대로 뒀다.
`--shadow-*` 의 `rgba(14,21,40,…)` 도 기준본 verbatim 값이라 손대지 않았다.

## 7. 검증 결과

### 정적

| 검사 | `design.css`/`.html` (main) | `design-delete-confirm-aligned.*` |
|---|---|---|
| 구 토큰 잔재 (`--neutral/primary/…/fs/fw/lh-`) | **0건** | **0건** |
| 미정의 `var()` 참조 | **0건** | **0건** |
| `.t-*` 정의 (`grep -cE '^\.t-'`) | **12** | **12** |
| HTML 유틸 사용 | **26곳** | **6곳** |
| 주석 제외 `:root` 밖 raw hex | **0건** | **0건** |
| **hex 집합 대조(미러 대비)** | **54종 완전 동일** | **54종 완전 동일** |
| hex **다중집합**(등장 횟수까지) | 54 → 54 **동일** | 54 → 54 **동일** |
| `lang="ko"` | 있음 | 있음 |
| `color-scheme: only light` | 1건(관례대로 유지) | 1건(관례대로 유지) |
| HTML 문자 단위 diff | `replace` opcode **0건** (delete 2 = `<meta>` 위치 이동·css 파일명·들여쓰기) | 동일 |
| HTML 한글 런 | 172→177 (+5 = 신설 `<title>`) · 기존 런 **전건 보존** | 40→46 (+6 = `<title>`) · 기존 런 전건 보존 |

### 브라우저 실측 (Chromium, 1280×900 · 480×900)

| 축 | main (요소 98개 1:1) | delete-confirm (요소 27개 1:1) |
|---|---|---|
| `fontSize` · `fontWeight` | **0건** | **0건** |
| `color` · `backgroundColor` | **0건** | **0건** |
| `border*Color` · `border*Width` | **0건** | **0건** |
| **가로 변화(dx/dw)** | **0건** (아래 ★) | **0건** |
| `lineHeight` | 16건 — 사다리 채택의 의도된 귀결 | 7건 — 동일 |
| 문서 높이 | 1364→1357 (−7px, 1280) · 1889→1878 (−11px, 480) | 900→900 (**±0**, 두 뷰포트) |

**★ 가로 변화 "2건" 은 측정 잡음이다.** 도구는 매 실행 `2건` 또는 `0건` 을 오가며, 대상은 항상
다운로드 중 첨부 행의 **회전 스피너 `svg` 와 그 `path` 하나뿐**이다(3회 실행: 2건 / **0건** / 2건).
`.btn .spinner { animation: spin … linear infinite }` 가 걸린 요소라 `getBoundingClientRect` 가
회전 위상에 따라 달라지는 것이다. 이 화면의 다른 애니메이션 `skeleton-pulse` 는 `opacity` 만
바꾸므로 기하에 영향이 없다. **리플로우가 아니므로 실제 가로 변화는 0건이다.**

`lineHeight` 변화는 전부 `1.6`(body 상속) → 사다리 규정값이다.

| 대상 | 변화 | 개수 |
|---|---|---|
| `.kv-pair dt` → `t-caption` | 22.4 → 21px | 3 |
| `.state-reference-label` → `t-label` (+ 자식 svg 4) | 22.4 → 19.6px | 5 |
| `.state-mini-title` → `t-caption` | 22.4 → 21px | 2 |
| `.error-state-message` → `t-caption` | 22.4 → 21px | 1 |
| `.attachments-heading` → `t-title-sm` (+ 자식 svg 2) | 27.2 → 25.5px | 3 |
| `.attachment-size` → `t-caption` | 22.4 → 21px | 2 |
| (delete-confirm) `.modal-dialog-title` → `t-title-md` (+ 자식 svg 6) | 28.8 → 26.1px | 7 |

세로 축소(main −7/−11px)는 이 요소들이 줄어든 만큼이 그대로 누적된 것이다.
delete-confirm 은 다이얼로그가 3px 줄었으나 스크림 안에서 세로 중앙 정렬이라 문서 높이는 변하지 않았다
(`dy +2` = 3px 축소의 절반이 반올림된 재중앙 배치).

> ⚠ **`--intended` 를 쓰지 않았다.** 레이아웃을 바꾸지 않았으므로 판정에서 제외한 영역이 없다.
> 위 수치는 두 화면 **전체**에 대한 것이다.

## 8. 확인하지 못한 것

- 배포 환경에 비-GOV `Pretendard` 가 설치돼 있는지 — 이 작업에서 확인할 수 없다.
  다만 이 화면은 폰트 교체로 인한 폭 변화가 **0건**이라 설치 여부와 무관하게 영향이 없다.
- `.kv-grid` · `.modal-dialog-close` 가 예비 사양인지 잔재인지 — 화면 사양(SCREEN-031)을
  열어 판단할 사안이며 이 정합 작업의 범위 밖이다.
  > ⏩ **후속 스윕에서 둘 다 삭제했다** — 판단 근거와 원래 의도는 아래 §10 에 남긴다.

---

# 최종 정리 스윕 (#9) — 사용자 확정 4항 반영

앞의 §1~§8 은 **관례 정합** 라운드의 기록이고 그대로 유효하다. 아래 §9~§11 은 그 위에 얹은
정리 스윕이며, 이번엔 **렌더가 의도적으로 바뀐다**(경계 색 1축).
**두 정합본(`design.*` / `design-delete-confirm-aligned.*`) 에 같은 정리를 적용했다.**

## 9. `.btn-secondary` 경계 상향 — 판단해서 올렸다 (렌더 변경 · 승인된 변화)

지시서 §1 은 **버튼을 기본 대상에서 제외**하되 *"`.btn-secondary` 처럼 경계가 유일한 식별 수단인
버튼은 판단해서 올리되 반드시 보고"* 하라고 한다. **올렸다.**

| 자리 | 전 | 후 | 흰 배경 대비 |
|---|---|---|---|
| `.btn-secondary` 기본 경계 | `--n-3` (`#b1b8be`) | **`--border-strong`** (= n-4 `#8a949e`) | 2.01:1 → **3.08:1** |
| `.btn-secondary:hover:not(:disabled)` | `--border-strong` (n-4) | **`--n-5`** (`#6d7882`) | 3.08:1 → 4.51:1 |

**올린 근거 3가지**

1. **경계가 유일한 식별 수단이다.** 이 버튼의 면은 `--bg-page`(`#ffffff`) 인데 얹혀 있는 표면도
   흰색이다(main 은 `.card`, delete-confirm 은 `.modal-dialog`). 테두리가 사라지면 버튼의 경계
   자체가 보이지 않는다. §6-(2) 가 이미 *"이 화면의 '수정' 버튼과 모달의 '취소' 버튼이 이 경계
   하나로만 식별된다"* 고 실측 기록해 둔 자리다.
2. **드리프트 교정이지 신규 정책이 아니다.** 이미 정합돼 있는 형제 화면 **SCREEN-012 의
   `.btn-secondary`·`.btn-outline` 이 `--border-strong` 을 쓴다.** 같은 컴포넌트가 화면마다 다른
   경계 단계를 쓰던 것이 어긋남이었다.
3. hover 를 함께 올린 이유 — 기본이 `--border-strong` 이 되면 hover 선언의 `--border-strong` 이
   **같은 값이 되어 무효**가 된다. 원래 설계의 "hover 는 한 단계 진하다"(n-3 → n-4) 관계를
   그대로 한 단계 옮겨(n-4 → n-5) 유지했다. 정적 렌더에는 드러나지 않는다.

**올리지 않은 것 (지시서 §1 대로)**

- `.card` · `.article-divider` · `.state-mini-card` · `.attachment-row` 의 `--n-1`(1.23:1) —
  **컨테이너 경계·구획선이라 컨트롤이 아니다.** §6-(2) 가 보고한 그대로 **관찰만 유지**한다.
- `.btn-outline`(경계 `--p-5`) · `.btn-ghost`(투명) · `.btn-primary`/`.btn-danger`(면으로 식별) —
  경계가 유일한 식별 수단이 아니다.
- `--n-3` 은 이제 두 파일 모두 **참조 0건**이 되었지만 `:root` 정의는 **지우지 않았다**
  (§1 의 "미사용 토큰을 정리하지 않는다" 원칙 · hex 집합 동일성 유지).

## 10. 死 규칙 삭제 2건 — 판단 근거와 원래 의도

§6-(1) 이 "판단할 수 없다"고 남겨 둔 것을 이번에 판정했다. 판정에 쓴 증거는 **두 렌더 합산 매칭 수**
와 **저장소 전 화면 grep** 두 가지다.

### (a) `.kv-grid` 4규칙 — 삭제

- 매칭: `design.html` 0건 · `design-delete-confirm-aligned.html` 0건 (**두 렌더 모두 0**).
- 저장소 전 화면 grep: `kv-grid` 는 **SCREEN-031 의 CSS 2벌에만** 존재한다(다른 화면 CSS·HTML 0건).
  → 지시서 §3 의 보존 사유 *"다른 화면과 공유하는 컴포넌트 정의"* 에 **해당하지 않는다.**
- 이 화면이 실제로 쓰는 구현은 `.kv-row-wrap` + `.kv-pair`(3건)다. `.kv-grid` 는 대체된 옛 구현이다.
- **원래 의도(복원용)**: `dt`/`dd` 를 `grid-template-columns: max-content 1fr` 2열로 `baseline` 정렬,
  `gap: var(--sp-sm) var(--sp-md)`, `dt` 14px/400/`--n-6`/`nowrap`, `dd` 15px/`--n-7`/`margin:0`,
  `dd + dt { margin-left: var(--sp-lg) }`.
- ⚠ 카탈로그 **UI-106 KeyValueGrid 자체는 `_shared/ui-catalog.md` 가 소유**하며 이 삭제와 무관하다.
  사양이 줄지 않는다.
- 부수 효과: §2 에서 *"死 규칙이라 클래스를 달 수 없어 리터럴로 남겼다"* 던 `font-size` 2건
  (`.kv-grid dt` 14px · `.kv-grid dd` 15px)이 함께 사라져, **컴포넌트 CSS 의 사다리 밖 `font-size`
  가 main 4건 → 2건**(`body` 17px · `.error-state-title` 15px/600)으로 줄었다.

### (b) `.modal-dialog-close` + `:hover` — 삭제

- 매칭: **두 렌더 모두 0건**. 삭제 확인 다이얼로그의 헤더에는 제목만 있고 닫기 버튼이 없다.
- 저장소 전 화면 grep: SCREEN-031 CSS 에만 존재.
- **짝인 `design-delete-confirm-aligned.css` 는 이 규칙을 애초에 갖고 있지 않다.** 즉 §2 가 맞춰 둔
  "두 파일의 `.modal-*` 블록 동일" 이 이 규칙 하나만 깨져 있었고, **삭제로 대칭이 회복된다.**
- **원래 의도(복원용)**: 투명 배경·무테두리 36×36 아이콘 버튼(색 `--n-5`, `--radius-sm`,
  `inline-flex` 중앙 정렬), hover 시 배경 `--n-1` · 색 `--n-9`.

### (c) 삭제하지 않은 매칭 0건 16건 — 보존 사유

| 선택자 | 보존 사유 |
|---|---|
| `.t-display-sm` · `.t-body-lg` · `.t-nav-link` · `.t-mono` | 사다리 12종 카탈로그(관례가 12종 정의를 요구) |
| `h3` · `h4` | 리셋 그룹 `h1,h2,h3,h4` 의 구성원 — 기준본 SCREEN-009 도 같은 그룹을 갖는다 |
| `a:focus-visible` · `[tabindex]:focus-visible` · `.btn[aria-disabled="true"]` · `.btn-primary:hover/:active` | 상태 규칙(정적 목업에 그 상태 노드가 없을 뿐) |
| `.btn-primary` · `.icon-lg` · `.visually-hidden` | **다른 화면과 공유하는 정의**(grep 실측: `.btn-primary` 20여 화면 · `.icon-lg` 7화면 · `.visually-hidden` 4화면) |
| `.badge-draft` | 상태 변형 — `.badge-pinned`/`.badge-published` 와 한 벌인 게시 상태 3종 중 하나. 지우면 "임시저장" 상태가 사양에서 사라진다 |
| `.card-padded` | **판단이 갈린 자리 — 보존했다.** 이 화면에서만 정의되고 두 렌더 모두 미사용이라 삭제 후보였으나, 공유 컴포넌트 `.card`(UI-011)의 패딩 변형이라 §3 의 "컴포넌트 정의" 로 보고 남겼다. 지우자는 판단이면 이 줄을 근거로 후속 처리 가능 |

## 11. 검증 — 두 렌더 각각 (`--intended` 없이)

```
node bin/compare-render.mjs diff screens/SCREEN-031/design/design-main.html \
                                 screens/SCREEN-031/design/design.html --width 1280,480
node bin/compare-render.mjs diff screens/SCREEN-031/design/design-delete-confirm.html \
                                 screens/SCREEN-031/design/design-delete-confirm-aligned.html --width 1280,480
```

| 축 | main (98개 1:1) | delete-confirm (27개 1:1) |
|---|---|---|
| `fontSize` · `fontWeight` · `color` · `backgroundColor` | 각 **0건** | 각 **0건** |
| `border*Width` | **0건** | **0건** |
| **`border*Color`** | **1건** — `button.btn.btn-secondary` `rgb(177,184,190)` → `rgb(138,148,158)` | **1건** — 동일 |
| `lineHeight` | 16건 — 앞 라운드와 동일(신규 0) | 7건 — 동일 |
| 기하 | 68건 · 문서 높이 1364→1357 · 1889→1878 — 앞 라운드와 **동일** | 20건 · 가로 0건 · 900→900 — 동일 |

**바뀐 hex 전건**: `#b1b8be` → `#8a949e` (렌더당 1요소). **사유 = §9 경계 상향 하나뿐.**
CSS 리터럴 hex 다중집합은 두 파일 모두 미러 대비 **54종 완전 동일**.
두 파일의 `:root` 는 삭제·수정 후에도 **바이트 동일**을 재확인했다.

**회전 스피너 잡음**: main 의 가로 변화는 3회 실행에서 `2 / 2 / 2` 건(앞 라운드는 `2 / 0 / 2`)이었고
대상은 언제나 다운로드 중 첨부 행의 **회전 스피너 `svg` 와 그 `path`** 뿐이다
(`dx-1 dy-8 dw2 dh2` · `dx-1 dy-7 dw1 dh1`). `.btn .spinner { animation: spin … infinite }` 때문에
`getBoundingClientRect` 가 회전 위상에 따라 달라지는 것이며 **리플로우가 아니다.**
이번 편집은 스피너의 기하에 관여하지 않는다(경계색·죽은 규칙 삭제뿐).

## 12. 검사기의 사각

- 死 규칙 스캔은 **두 렌더 합산**이라 다른 화면과의 공유는 못 본다 → 저장소 전 화면 `grep` 으로 보완했다.
- 死 규칙 스캔 초기판에 **`:not(:disabled)` 파싱 버그**가 있어 `:hover:not(:disabled)` 계열 5건이
  어느 버킷에도 안 잡히고 조용히 `ERR` 로 빠졌다. 수정 후 재실행해 위 판정을 얻었다
  (수정 전 20건 → 수정 후 **22건**). 그 5건은 전부 상태 규칙이라 삭제 판정에는 영향이 없었다.
- 명시도 눌림 스캔은 상태 의사클래스를 건너뛰고 14개 속성만 본다. 두 렌더 결과 **각 0건**.
  ⚠ 조정자가 지적한 함정 2건을 막고 재실행했다 —
  ① `*, *::before, *::after { transition: none !important; animation: none !important }` 주입
  (안 막으면 전환 대상 속성이 전부 "무효"로 오판된다. 이 화면은 스피너·스켈레톤 애니메이션이
  있어 특히 해당된다) → 결과 **여전히 각 0건**.
  ② `getComputedStyle().cssText` 미사용(전부 `getPropertyValue`). 이 Chromium 에서
  `cssText.length === 0` 임을 직접 확인했다 — 그걸로 스냅샷했다면 전건 통과하는 거짓 결과가 났다.
- 컨트롤 경계 감사는 **보이는 경계(폭>0 · 불투명)만** 본다 — `.btn-ghost`·`.btn-icon-sm` 처럼
  투명 경계인 것은 대상이 아니다(경계가 아예 없어 별개 축이다).
- 요소 타입에 무효한 속성(`<select>` 에 건 flex 등) 스캔 **0건**.
- 미정의 `var()` 참조 **0건**(두 파일 각각 정의 77).

## 13. `viewbox`(소문자) — 지시서 §4(b) 전제가 틀렸고, 표기를 정규화했다

지시서는 *"소문자면 속성이 무시돼 스케일링이 안 된다"* 고 했으나 **HTML 문서에서는 사실이 아니다.**
HTML 파서가 외래 요소(SVG) 속성의 대소문자를 보정해 DOM 에는 `viewBox` 로 들어간다.
브라우저 실측: 속성명 `viewBox` · `viewBox.baseVal = 0 0 24 24` · 아이콘 크기 정상, 잘림 없음.
(다른 담당도 독립적으로 같은 결론에 도달해 확정됐다.)

따라서 **"고치면 깨진다"가 아니라 "고쳐도 아무 일이 없다"** 이고, 조정자 지시대로 표기를
정규화했다 — `design.html` **12곳** · `design-delete-confirm-aligned.html` **3곳**.
검증: 파일 길이 7130 → **7130** · 2091 → **2091**(각각 동일), 되돌리면 원문과 **바이트 동일**
(이 속성 외 문자 차이 0), `compare-render` 결과가 정규화 **전/후 완전 동일**.
미러 2벌은 소문자 그대로 두었다(읽기 전용).

## 14. 컨트롤 경계 전수 감사 — 두 렌더 모두 **미달 0건**

조정자 확정 기준(*"경계선이 그 상호작용 컨트롤의 유일한 시각적 식별 수단이고 3:1 미만이면
`--border-strong` 으로 올린다"*)에 따라 `button/a/input/select/textarea/[role=button]/[tabindex]`
중 **보이는 경계를 가진 것 전부**를 브라우저에서 실측했다(경계색 vs 뒤 배경 실제 대비).

**★ 목표가 토큰이 아니라 "실제로 맞닿는 표면 대비 3:1" 로 바뀐 뒤(사용자 확정) 재실측했다.**
감사기는 경계의 **안쪽(자기 배경)·바깥쪽(조상 불투명 배경) 양쪽을 재고 더 불리한 쪽**으로 판정하며
흰 배경을 가정하지 않는다.

| 렌더 | 컨트롤 | 안 / 밖 표면 | 최저 대비 |
|---|---|---|---|
| main | `.btn-secondary` @ `.page > .card` | `#ffffff` / `#ffffff` | **3.08 ✅** |
| main | `.btn-outline` @ `.action-bar-cluster` | `#ffffff` / `#ffffff` | 4.55 ✅ |
| main | `.btn-outline` @ `.error-state`(배너 `--e-0` 위) | `#ffffff` / `#fdefec` | **4.06 ✅** |
| dc | `.btn-secondary` @ `.modal-dialog-actions` | `#ffffff` / `#ffffff` | **3.08 ✅** |

→ **두 렌더 모두 미달 0건. 추가 상향 없음.** `.btn-secondary` 는 §9 의 상향으로 2.01 → 3.08 이
된 것이고, 둘 다 흰 표면(카드·다이얼로그) 위라 `--border-strong` 으로 충분하다.
유색 표면 위에 놓인 유일한 경계형 컨트롤은 오류 배너 안의 `.btn-outline` 인데 경계가
`--p-5`(파랑)라 **4.06:1** 로 이미 통과한다.

**상태 역전 재점검 — `n-6` 승격 불필요.** 두 렌더 모두 기본이 `n-4` 로 유지되므로 hover(`n-5`)와
겹치지 않는다(`기본 n-4 → hover n-5` 순서 유지).

페이지네이션은 이 화면에 없고, 아이콘 버튼(`.btn-icon-sm`)·`.btn-ghost` 는 **경계가 투명**이라
이 기준의 대상이 아니다. 카드·구획선(`--n-1`, 1.23:1)은 컨테이너라 제외 — §9 에 사유를 적었다.

> ⚠ 감사기가 `.btn-danger` 를 "1.00:1" 로 표시하지만 **결함이 아니다** — 경계색과 면색이 둘 다
> `--e-5`(`#de3412`)라 안쪽 대비가 정의상 1.00 이다. 이 컨트롤의 실제 경계는 **면과 바깥 흰
> 표면의 경계이고 4.56:1** 로 통과한다. "더 불리한 쪽" 규칙을 채움 컨트롤에 기계적으로 적용하면
> 오탐이 된다 — 손대지 않았다.
