# SD-008 (SCREEN-036 공지 작성 화면) — 관례 정합 노트

정본 파일: `design.css` + `design.html`
관례 기준본: `screens/SCREEN-009/design/design.css`
같은 폴더의 `design-main.{html,css}` 는 서버 현재본의 로컬 미러이며 이번에 수정하지 않았다.

재디자인이 아니다. 바꾼 것은 ①색 토큰 이름 ②타이포 지정 방식 ③폰트 스택 문자열 ④잠재 결함 1건뿐이고,
**hex 값·간격·레이아웃·구성요소·문구는 한 자도 바꾸지 않았다.** 브라우저 실측에서
`fontSize`·`fontWeight`·`lineHeight`·색·`border*` 차이가 4개 뷰포트 전부 **0건**이다.

---

## 1. 색 토큰 — 이름 기준이 아니라 **값 기준**으로 매핑했다

이 파일은 `--ds-` 접두 + 10단위 스케일(`--ds-neutral-0 … -100`)이었다. 기준본과 접두어가 달라
이름 충돌(SD-001·007·011 의 위험)은 없었지만, **자릿수 규칙(`10→1`, `100→10`)을 전제로 두지 않고**
지시서 §2 절차대로 값으로 찾았다.

1. 기준본 `:root` 를 파싱해 **hex → 기준본 토큰명** 역인덱스를 만들었다 (기준본 안에 hex 중복 0건)
2. 이 파일 `:root` 를 파싱해 **각 토큰의 값(hex)으로** 기준본 이름을 찾았다
3. old→new 사전을 만들고 파일 전체를 정규식 `--[A-Za-z0-9_-]+` 로 **한 번에** 치환했다
   (최장 매칭이라 `--ds-neutral-100` 이 통째로 잡히고 결과를 재스캔하지 않아 이중 변환이 원리적으로 불가능)

**결과**: 색 토큰 34개 전부가 기준본에 같은 hex 로 존재했고 미매칭 0건. 자릿수 규칙과 어긋난 자리도
0건이었다 — 다만 **그것은 검증 결과이지 전제가 아니다.** 규칙이 아니라 값으로 찾은 뒤 확인한 것이다.

| 접두어 (변환 전 → 후) | 비고 |
|---|---|
| `--ds-neutral-0/10/20/…/100` → `--n-0/1/2/…/10` | 11종 |
| `--ds-primary-0/10/…/80` → `--p-0/1/…/8` | 9종 |
| `--ds-secondary-50/60` → `--s-5/6` | 2종 |
| `--ds-error-0/10/50/60` → `--e-0/1/5/6` | 4종 |
| `--ds-success-0/50/60` → `--su-0/5/6` | 3종 |
| `--ds-warn-0/50` → `--w-0/5` · `--ds-info-0/50` → `--i-0/5` | 4종 |
| `--ds-white` → `--bg-page` | 둘 다 `#ffffff` |
| `--ds-space-*` → `--sp-*` · `--ds-radius-*` → `--radius-*` · `--ds-shadow-*` → `--shadow-*` | |
| `--ds-ease-*` → `--ease-*` · `--ds-dur-fast/base/slow` → `--duration-fast/base/slow` | |
| `--ds-font-base/mono` → `--font-body/--font-mono` | |

### 표면·경계 토큰 신설 (관례 4종)

`--bg-page`(#ffffff) · `--bg-alt`(`var(--n-0)`) · `--border`(`var(--n-2)`) · `--border-strong`(`var(--n-4)`).
**전부 기존 값의 별칭이라 새 hex 를 만들지 않았다**(hex 집합 대조 통과).

의미가 분명한 자리에만 별칭을 적용했다:

| 자리 | 변환 |
|---|---|
| `body` 캔버스 배경 (회색) | `var(--n-0)` → `var(--bg-alt)` |
| `.card` 경계 · `.state-reference` 구분선 | `var(--n-2)` → `var(--border)` |
| `.input`/`.textarea`/`.checkbox .box` 경계 | `var(--n-4)` → `var(--border-strong)` |

**버튼 경계(`.btn-outline`)는 별칭으로 바꾸지 않았다.** 기본 `--n-4` → hover `--n-5` → disabled `--n-2`/`--n-3`
으로 **스케일을 오르내리는 진행**이라, 한 단계만 별칭으로 바꾸면 같은 규칙 안에 두 표기가 섞여 읽기 어려워진다.

---

## 2. 타이포 — `.t-*` 사다리 12종 + HTML 클래스 지정

원본은 `.ds-title-lg` 등 **7종 유틸을 정의해 놓고 HTML 에서 한 번도 쓰지 않았다**(참조 0건).
실제 크기 지정은 전부 컴포넌트 규칙의 `font:` 단축 선언이었다 — "font-size 선언 2건"으로 보였던 이유가
이것이다(나머지는 `font:` 단축이라 `font-size` 문자열로 잡히지 않았다).

死 유틸 7종을 지우고 기준본 verbatim 사다리 12종을 넣은 뒤, 사다리에 **있는** 단계를 HTML 클래스로 옮겼다.

| 컴포넌트 | 옮긴 곳 |
|---|---|
| `.page-header-titles h1` 700 22/1.4 | `.t-title-lg` |
| `.page-header-desc` 400 15/1.6 | `.t-body-sm` |
| `.btn` 500 17/1.4 | `.t-button` (버튼 5개) |
| `.field-label` · `.checkbox-label` 600 14/1.4 | `.t-label` |
| `.field-help` · `.field-error` · `.checkbox-desc` 400 14/1.5 | `.t-caption` |
| `.input` · `.textarea` 400 17/1.6 | `.t-body-md` |
| `.field-counter` mono 14/1.5 | `.t-mono` |

### 사다리 **밖**이라 컴포넌트에 남긴 것 (지시서 §1 단서)

| 자리 | 값 | 사유 |
|---|---|---|
| `.breadcrumb a` · `.crumb-current` | 15/500/1.5 | `.t-body-sm` 은 15/**400**/1.**6** — 굵기·행간이 둘 다 다르다 |
| `.breadcrumb .sep` | 13px | 사다리 하한(14px) 아래 장식 구분자 |
| `.state-reference-label` | 14/600/1.5 | `.t-label` 은 1.4, `.t-caption` 은 400 — 어느 쪽도 아닌 혼합 |
| `body` | 17px/1.6 | 클래스 없는 텍스트의 상속 기준. 지우면 브라우저 기본 16px 로 떨어진다 |

### 폼 컨트롤에 `font-family: inherit` 를 명시했다

`font:` 단축 선언을 걷어내면 `<button>`·`<input>`·`<textarea>` 는 **font-family 를 상속하지 않아**
UA 기본(Arial 13.3px)으로 떨어진다. `.t-*` 유틸은 family 를 갖지 않으므로(`.t-mono` 제외)
`.btn` 과 `.input,.textarea` 에 `font-family: inherit` 를 남겼다.
실측으로 확인했다 — `--props fontFamily` 대조에서 `input.input`·`textarea.textarea`·`button.btn*` 이
전부 본문 스택을 유지한다(UA 폰트로 떨어진 요소 0건).

---

## 3. 고친 결함 1건 — 정의되지 않은 CSS 변수 참조

원본 `.checkbox .box` 의 transition:

```css
/* 변환 전 */
transition: background-color var(--ds-dur-fast) var(--ds-ease-standard),
            border-color var(--ds-dur-fast) var(--ease-standard);   /* ← --ds- 접두 누락 */
```

`--ease-standard` 는 원본에 **정의되지 않은 이름**이었다(오타). `var()` 치환 실패는 선언 전체를
computed-value 시점에 무효로 만들므로 **체크박스의 transition 이 통째로 죽어 있었다**
(배경색 전환도 함께 사라진다 — 앞 절이 유효해서가 아니라 선언 하나가 통째로 버려지기 때문).

토큰 rename 으로 `--ease-standard` 가 실재하는 이름이 되어 이 참조가 자동으로 해소됐다.
그대로 두는 편이 "무변경"에 가깝지만, 되살리려면 존재하지 않는 이름을 일부러 다시 만들어야 해
**의도적으로 해소된 채 두었다.** 정적 렌더에는 영향이 없다(전환은 시간축 효과이고
`compare-render` 의 비교 속성에도 `transition` 이 없다).

검증: 변환 후 파일의 **미정의 `var()` 참조 0건**(원본은 1건).

---

## 4. 폰트 스택 교체 — 렌더 폭 변화 **0px**

지시서 §1 대로 기준본 문자열로 교체했다.

| | 변환 전 | 변환 후 |
|---|---|---|
| `--font-body` | `"Pretendard GOV", "Apple SD Gothic Neo", "Noto Sans KR", -apple-system, BlinkMacSystemFont, sans-serif` | `'Pretendard GOV', -apple-system, 'Apple SD Gothic Neo', 'Noto Sans KR', sans-serif` |
| `--font-mono` | `"D2Coding", ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace` | `'D2Coding', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace` |

지시서가 예고한 `dw ±1~2px` **예외를 쓸 일이 없었다** — 이 개발 기기에서는 두 스택의 첫 사용 가능
폰트가 같아, 4개 뷰포트 전부에서 가로 변화가 0px 이고 문서 높이도 ±0px 이다.

---

## 5. 관찰만 하고 **고치지 않은 것**

### (a) 입력 경계 대비 — 이 파일은 이미 통과다

SCREEN-037(같은 공지 계열)에서 보고된 "입력 경계 1.54:1 미달"은 **이 파일에 없다.**
흰 배경(#ffffff) 위 실측:

| 토큰 | hex | 대비 | WCAG 1.4.11 (3:1) | 이 파일에서 쓰이는 곳 |
|---|---|---|---|---|
| `--n-2` (=`--border`) | `#cdd1d5` | **1.54:1** | 미달 | `.card` 경계 · `.state-reference` 점선 |
| `--n-4` (=`--border-strong`) | `#8a949e` | **3.08:1** | 통과 | **`.input` · `.textarea` · `.checkbox .box` 경계** |
| `--n-5` | `#6d7882` | 4.51:1 | 통과 | (이 파일에서 경계로는 미사용) |

즉 **컨트롤 경계는 전부 `--n-4`(3.08:1)로 통일돼 있고**, 037 이 지적한 "같은 파일 안에서 입력과
체크박스의 경계 정책이 갈린다"는 현상도 없다(둘 다 `--n-4`). `--n-2` 가 남는 자리는 카드 컨테이너와
참고 스니펫 구분선뿐인데, 이들은 상태를 전달하는 UI 컴포넌트가 아니라 1.4.11 적용 대상이 아니다.

→ **9건 공통 결정으로 올릴 때 이 화면은 "이미 `--border-strong` 축"으로 분류하면 된다.**

### (b) `.field:last-of-type` 류 死 규칙 — 이 파일에 없다

`last-of-type`·`last-child`·`first-of-type` 선택자 **0건**.

### (c) 레이아웃 결함 — 없다. 손대지 않았다

지시서 §5(a)의 "도움말이 입력 폭을 결정" 유형을 실측으로 확인했다.
이 화면은 **세로 스택 폼**이다 — `.form-fields`/`.field` 가 `flex-direction: column` 이고
`.input`/`.textarea` 는 `width: 100%`, 폭은 컨테이너의 `max-width: 760px` 이 정한다.
`compare-render width` 로 도움말 문장을 ×2·×4·축소해도 입력 폭은 **760px 고정**이었다.
SCREEN-024 에서 폭 고정이 도움말 줄 수를 바꿔 `align-items: flex-end` 정렬을 깨뜨린 회귀가 있었으므로,
**없는 결함을 만들지 않기 위해 레이아웃은 한 줄도 건드리지 않았다.**

### (d) `<title>` 부재 — 지어내지 않았다

이 화면의 원본 HTML 에는 `<title>` 이 없다(같은 계열 SCREEN-037·SCREEN-024 의 원본에는 있다).
문서 제목 추가는 색·타이포 정합 범위 밖의 **내용 추가**라 이번에 넣지 않았다. 필요하면 별도 결정으로.

### (e) 미사용 토큰

원본이 DS 팔레트를 실사용분보다 넓게 선언해 두었다(변환 전 21종 미사용). hex 집합 동일성이
검증 조건이므로 **그대로 보존**했고, 여기에 별칭 4종이 더해져 24종이 미사용 상태다. 의도된 보존이다.

---

## 6. HTML 변경

- `<html lang="ko">` — 원본에 **이미 있었다**(이 화면은 관례 항목 ③이 처음부터 충족).
- `html { color-scheme: only light; }` — 원본에 있던 그대로 **유지**했다(기준본과 동일).
- `<link rel="stylesheet" href="design-main.css">` → `href="design.css"` (로컬에서 바로 열기 위함).
  ⚠ 서버 업로드 시에는 이 `<link>` 를 제거해 보낸다(서버가 주입) — 지시서 §7.5.
- 한 줄이던 `<!DOCTYPE>`~`<head>` 를 기준 정합본(SCREEN-024·037)과 같은 형태로 줄바꿈했다.
- `.t-*` 클래스 부여 — 위 §2 표.
- `viewbox`(소문자) 는 **그대로 두었다.** 소문자는 SVG 속성으로 인식되지 않아 실질적으로
  viewBox 없음 상태인데, `viewBox` 로 고치면 **SVG 렌더 결과가 바뀐다.** 서버 직렬화 형태이기도 하다.
- **텍스트는 한 자도 바꾸지 않았다** — 태그 제거 후 본문 텍스트 완전일치, 한글 런 24/24 완전일치.

---

# 7. 최종 정리 스윕 — `viewBox` 오타 · 경계 재확인 · 죽은 규칙

앞의 §1~§6 은 관례 정합 라운드의 기록이다. 이 절은 그 위에 얹은 별도 라운드다.
**결과적으로 이 화면은 렌더가 한 픽셀도 바뀌지 않았다.**

## 7.1 ★`viewbox` 오타 — 고쳤으나, "렌더가 바뀐다"는 전제는 사실이 아니었다

§6 은 *"소문자는 SVG 속성으로 인식되지 않아 실질적으로 viewBox 없음 상태인데, `viewBox` 로 고치면
SVG 렌더 결과가 바뀐다"* 고 적었다. **이 진술은 HTML 문서에서 틀렸다.**

HTML 파서는 외래 요소(SVG) 속성에 **대소문자 보정 표**를 적용해 `viewbox` 를 `viewBox` 로
정규화한다. 즉 소문자로 써도 DOM 에는 처음부터 `viewBox` 로 들어가 있었다. 실측:

```
소스가 viewbox(소문자)인 파일          소스가 viewBox 인 파일
  attributes    → ["viewBox", ...]       attributes    → ["viewBox", ...]
  getAttribute("viewBox") → "0 0 24 24"  getAttribute("viewBox") → "0 0 24 24"
  getAttribute("viewbox") → null         getAttribute("viewbox") → null
  viewBox.baseVal → [0,0,24,24]          viewBox.baseVal → [0,0,24,24]
  getScreenCTM 배율 → 0.75               getScreenCTM 배율 → 0.75
```

아이콘 4개의 **박스·잉크 범위·오버플로가 전부 소수점까지 동일**했다(스케일 0.75 = 18px 박스에
24 단위 콘텐츠). 잘림도 확대도 없다 — `overflow` 값이 전부 음수(박스 안쪽)다.

| # | 위치 | 박스 | 잉크(박스 기준 상대) | 오버플로 | 전/후 |
|---|---|---|---|---|---|
| 0 | `.back-btn` | 18×18 | 3.75, 3.75, 10.5×10.5 | -3.75 | 동일 |
| 1 | `.field-error` | 16×16 | 1.33, 1.33, 13.33×13.33 | -1.33 | 동일 |
| 2 | `.checkbox .box` | 14×14 | 2.33, 3.5, 9.33×6.42 | -2.33 | 동일 |
| 3 | `.state-reference-label` | 14×14 | 1.17, 1.17, 11.67×11.67 | -1.17 | 동일 |

**조치**: 4건을 `viewBox` 로 고쳤다(파일 길이 변화 0자 — 대소문자만). 렌더 근거가 아니라
**소스 위생** 근거다 — XHTML/XML 로 직렬화되거나 JSX 로 옮겨질 때는 파서 보정이 없어 실제로 깨진다.
지시서가 요구한 "깨지면 되돌린다"는 조건은 발생하지 않았다(깨질 여지 자체가 없었다).

> ⚠ **다른 화면에도 같은 정정이 필요하다** — "소문자 viewbox 는 무시된다"를 근거로 렌더 변화를
> 예상하거나 두려워한 판단이 있다면 그 전제가 틀렸다. 서버 미러(`design-main.html`)도 소문자이며,
> 서버가 재직렬화하면 다시 소문자로 돌아갈 수 있다(업로드 축의 문제라 여기서는 다루지 않는다).

## 7.2 경계 대비 — 이미 통과. **내리지 않았다**

§5(a) 의 결론이 이번 라운드에서 그대로 확정됐다. 컨트롤 경계는 전부 `--border-strong`(=`--n-4`,
#8a949e, **3.08:1**)이다.

| 자리 | 토큰 | 대비 | 조치 |
|---|---|---|---|
| `.input` · `.textarea` | `--border-strong` | 3.08:1 통과 | **변경 없음** |
| `.checkbox .box` | `--border-strong` | 3.08:1 통과 | **변경 없음** |
| `.btn-outline` 기본/hover | `--n-4` / `--n-5` | 3.08 / 4.51 | 변경 없음(버튼 · §1 대상 아님) |
| `.card` 경계 · `.state-reference` 점선 | `--border`(n-2) | 1.54:1 | **컨테이너 경계라 대상 아님** |

지시서 §1 의 "이미 3:1 을 넘는 것은 그대로 둔다(내리지 마라)"를 그대로 지켰다.

## 7.3 죽은 규칙 — 기계 검출 결과 **삭제 대상 0건**

스타일시트 전 규칙을 브라우저에서 재판정했다(선언을 제거했다 되돌리며 computed style 재측정.
`* { transition: none }` 로 전환을 끄지 않으면 `.btn`·`.input`·`.checkbox .box` 의 transition 대상
속성이 전부 "inert" 로 오판된다 — 이 파일에서 실제로 발생했다).

- **명시도에 눌린 선언 0건**
- **매칭 0 규칙 7건 — 전부 보존**

| 보존 | 사유 |
|---|---|
| `.t-title-md` · `.t-title-sm` · `.t-body-lg` · `.t-nav-link` · `.t-display-sm` | 관례가 사다리 **12종 전부 정의**를 요구한다 |
| `.btn-outline .spinner` | 버튼의 **제출 중(로딩) 상태** 규칙이다. 정적 목업에 그 상태 노드가 없을 뿐 |
| `.field-counter.near-limit` | 글자 수가 상한에 가까울 때의 **경고 상태** 규칙 |

§2 가 기록한 원본의 死 유틸 `.ds-*` 7종은 앞 라운드에서 이미 제거됐다 — 이번에 새로 지울 것이 없다.

## 7.4 `.spinner` 측정 아티팩트

이 화면에도 `@keyframes ds-spin` 회전 요소가 있어 `getBoundingClientRect` 가 실행마다 흔들린다.
3회 실행에서 1280px 은 `dw-1 / dw-4 / 0건`, 480px 은 `0건 / dw-3 / dw3` 로 **부호까지 바뀌었다**.
이 요소를 빼면 이번 편집의 기하·스타일 변화는 **전 뷰포트 0건**이다.
