| 항목 | 값 |
|---|---|
| CO 식별자 | CO-20260915-포털-스타일격리-preflight-스코프 |
| 제목 | 포털 채널의 전역 CSS 리셋을 우리 마운트 영역 안으로 좁힌다 — Host(KRDS) UI 파손 해소 |
| 대상 도메인 | 프론트(전 화면 · SCREEN-029 등 포털 채널 전체) — `klid-web-implementer` |
| 구현 상태 | 📝 작성 |
| LogiCraft 설계반영 | ⏳ 대기 |
| 생성일 | 2026-09-15 |

---

## §1 배경

### 증상

포털 개발망(`https://192.168.102.103:38444/workspace/authoring/portal`)에서 저작도구 화면을
한 번 들르면 **포털 자체 UI 가 깨진다.**

- 상단 GNB 메뉴·로고·breadcrumb·푸터 로고에 **검은 테두리**가 생긴다
- 푸터 레이아웃이 풀려 주소·연락처가 **화면 왼쪽 끝에 붙는다**
- breadcrumb 이 오른쪽에서 잘린다

포털 홈(`/`)만 보면 멀쩡하다 — **저작도구를 마운트한 순간부터** 깨진다.

### 근본 원인 (2026-09-15 브라우저 실측으로 확정)

저작도구 번들이 딸려 보내는 CSS 가 Host 문서의 `<head>` 에 **문서 전역 `<link>` 로 꽂힌다**:

```
https://192.168.102.103:38444/label-remote/assets/AuthoringApp-CJhF_kXO.css   (약 870KB)
```

`vite.config.ts` 의 Module Federation `bundleAllCSS: true` 산출물이다. 그 안에 **Tailwind v4
Preflight(전역 리셋)** 와 우리가 직접 쓴 `@layer base` 블록이 들어 있고, 그것들의 선택자가
`html` · `body` · `*` · `ol,ul,menu` 처럼 **문서 전체**를 가리킨다.

파일 안에서 직접 확인한 규칙:

```css
html,:host{ -webkit-text-size-adjust:100%; font-family:Pretendard GOV,… }
body{ color:…; background-color:…; margin:0; … }
ol,ul,menu{ list-style:none; margin:0; padding:0 }
*,::before,::after{ border:0 solid }        ← 검은 테두리의 직접 원인
```

**검은 테두리의 기전**: `border: 0 solid` 가 `border-style` 을 `none` → `solid` 로 바꾼다.
KRDS 는 `border-width` 만 지정하고 style 은 브라우저 기본값(`none`)에 맡긴 자리가 있는데,
거기에 style 이 생기면 **숨어 있던 굵기가 그대로 그려진다.**

실측 (상단 메뉴 '학습데이터' 링크 `A.gnb-main-trigger`):

| | 우리 CSS 켬 | 우리 CSS 끔 |
|---|---|---|
| `border-width` | **3px** | 0px |
| `border-style` | **solid** | none |
| `border-color` | `rgb(71,85,105)` (우리 팔레트) | `rgb(200,204,208)` |

**결정적 실험**: 그 `<link>` 하나만 `disabled = true` 로 끄면 포털이 **즉시 정상 복구**된다
(검은 테두리 소멸 · breadcrumb 안 잘림 · 푸터 정상). 대신 우리 화면이 민무늬가 된다 —
즉 이 CSS 는 확실히 우리 것이고 **유일한 원인**이다.

### ★ 기존 코드가 깔고 있던 잘못된 전제 (이번에 바로잡는다)

`src/styles/global.css` 의 헤더 주석은 이렇게 적고 있다:

> preflight(리셋)는 «레이어 안에 그대로 둔다» — 밖으로 빼지 말 것.
> 우리 리셋까지 레이어 밖으로 나가면 그것이 포털의 헤더·좌측 메뉴«까지» 덮어써 Host UI 를
> 망가뜨린다.

**「레이어 안에 두면 Host UI 가 안전하다」는 전제가 틀렸다.** CSS 레이어는 **같은 요소의 같은
속성을 두고 맞붙을 때만** 진다. Host 가 **아예 선언하지 않은 속성**(`border-style` 이 바로 그
경우)은 경쟁자가 없어 **우리 레이어 규칙이 그대로 적용된다.**

⇒ **레이어는 우선순위 조절 장치이지 격리 장치가 아니다.** 격리는 선택자 범위로만 얻어진다.

⚠ 위 주석의 **앞부분(유틸리티를 레이어 밖에 둔다)은 그대로 유효하다** — 그것은 Host 의
레이어 밖 리셋을 우리가 **이겨야 하는** 축이고, 지금 고치는 것은 우리가 **건드리지 말아야 하는**
축이다. 두 축을 섞지 말 것.

### 책임 소재 — 우리다

포털 회신 문서 `docs/operations/CLAUDE_포털 회신 - MF·Apache 반영 확정.md` §3 이 미합의
항목으로 이렇게 남겨 두었다:

> 스타일 격리 — **격리 책임은 저작도구.** 개발망 첫 로드에서 포털 KRDS 화면 시각 회귀를 함께 확인

그 「함께 확인」이 실제로 터진 것이고, 아직 미해결 상태였다. 이 CO 가 그 항목을 닫는다.

### 함께 드러난 잠복 결함 — 모달·드로어가 스코프 밖이다

`createPortal` 로 **`document.body` 직하에 붙는 것이 5곳**이다:

- `src/components/common/Modal.tsx`
- `src/components/common/Drawer.tsx`
- `src/components/common/FloatingWindow.tsx`
- `src/features/label/components/ToolBar.tsx`
- `src/features/task/components/HistoryDrawer.tsx`

이것들은 `.klid-portal-embed` **바깥**이다. 그래서 **이번 변경 이전에도** 그 앵커가 주는
`--spacing: 4px` 를 못 받고 있었다 — 포털 채널의 모달·드로어는 **지금도 Host 의 루트 글꼴
62.5% 를 그대로 받아 간격이 눌려 있을 것**이다(별도 실측 대상). 스코프를 좁히면 리셋까지
못 받게 되므로 **이번에 함께 해소한다.**

---

## §2 변경 요지

1. **포털 채널 빌드에서만** Tailwind Preflight 와 우리 `@layer base` 선언의 선택자를
   **`.klid-portal-embed` 하위로 좁힌다.** 관제 채널 산출물은 **한 글자도 바뀌지 않는다.**
2. `createPortal` 로 `document.body` 에 붙는 5곳이 **같은 앵커 클래스를 가진 공용 컨테이너**를
   쓰게 해, 스코프 밖으로 떨어지지 않게 한다.
3. **회귀 가드**를 남긴다 — 포털 채널 산출 CSS 에 스코프 없는 전역 리셋 선택자가 없고,
   관제 채널 산출 CSS 에는 그대로 있다는 것을 **두 수치를 나란히** 고정한다.

---

## §3 도메인별 변경 상세

> 이 절이 `klid-web-implementer` 에 `change_detail` 로 그대로 전달된다.

### 프론트 (포털 채널 전 화면) — `klid-web-implementer`

#### 대상 파일·심볼

| 파일 | 역할 |
|---|---|
| `frontend/src/styles/global.css` | Tailwind import 4줄 · 우리 `@layer base` 2블록 · v4 보정 3종 · `.klid-portal-embed` 앵커 |
| `frontend/postcss.config.js` | `@tailwindcss/postcss` 단독 — 후처리 자리가 필요하면 여기 |
| `frontend/vite.config.ts` | `IS_PORTAL_BUILD`(= `process.env.VITE_BUILD_CHANNEL === 'portal'`) 이미 존재 |
| `frontend/src/lib/buildChannel.ts` | `isPortalEmbedChannel()` · `IS_PORTAL_CHANNEL_BUILD` — **판정 재사용, 새로 만들지 말 것** |
| `frontend/src/remote/AuthoringRemote.tsx` | 앵커 `<div className="klid-portal-embed h-full">` — **이미 있다** |
| `frontend/src/components/common/{Modal,Drawer,FloatingWindow}.tsx` | `createPortal(node, document.body)` |
| `frontend/src/features/label/components/ToolBar.tsx` | 〃 |
| `frontend/src/features/task/components/HistoryDrawer.tsx` | 〃 |

#### 변경 1 — 리셋 스코프 좁히기 (핵심)

**목표**: 포털 채널 산출 CSS 에서 `html` · `body` · `*` · `ol,ul,menu` 같은 **문서 전역 선택자가
사라지고**, 대신 `.klid-portal-embed` 를 기점으로 한 선택자만 남는다.

**방식은 (A) PostCSS 후처리로 확정한다 — 2026-09-15 실측으로 성립을 확인했다.**

`npm run build:portal` 산출물 `dist/assets/AuthoringApp-CSDRAJcI.css`(872,801 B)를 직접 열어
확인한 구조:

| 산출물의 자리 | 내용 | 스코프 필요 |
|---|---|---|
| **`@layer base{…}`** (4,032 B · **규칙 53개**) | preflight 48 + 우리 base 5 | ★**여기만 좁히면 된다** |
| `@layer theme{…}` (규칙 1개) | `:root,:host{--…}` — 커스텀 프로퍼티 선언만 | 불필요(요소 모양을 바꾸지 않음) |
| `@layer properties{…}` | `*,:before,:after,::backdrop{--tw-*:…}` — 커스텀 프로퍼티만 | 불필요(같은 이유) |
| 레이어 밖 | 유틸리티 · `:root{--krds-*}` · `.klid-portal-embed{--spacing:4px}` | 불필요 |

⇒ **`@layer base { … }` 블록이 산출물에 그대로 살아남는다.** 따라서 `@tailwindcss/postcss` 가
`@import` 를 모두 펼친 **뒤에** 도는 로컬 PostCSS 플러그인이 그 블록 안 규칙의 선택자만
`:where(.klid-portal-embed, .klid-portal-embed *)` 로 다시 쓰면 된다. 손댈 범위가 **한 블록 53개
규칙**으로 닫혀 있다.

**이 방식을 고른 이유**: Tailwind preflight 를 **그대로 쓰므로 단일 진실원이 유지**된다 —
버전업 시 자동 추종하고 사본 드리프트가 없다.

**`@layer base` 안 53개 규칙의 실제 구성** (구현 시 대조용):

```
*,:after,:before,::backdrop      ::file-selector-button           html,:host
hr   abbr:where([title])         h1,h2,h3,h4,h5,h6                a
b,strong                         code,kbd,samp,pre                small
sub,sup / sub / sup              table                            progress
:-moz-focusring:where(:not(iframe))                               summary
ol,ul,menu                       img,svg,video,canvas,audio,iframe,embed,object
img,video                        button,input,select,optgroup,textarea
:where(select:is([multiple],[size])) optgroup (+ option)          ::placeholder
textarea                         ::-webkit-* (검색/날짜시간/스핀버튼 14종)
:-moz-ui-invalid                 button,input:where([type=button],[type=reset],[type=submit])
[hidden]:where(:not([hidden=until-found]))
── 여기부터 우리 것 5개 ──
html,body,#root                  body
*,:after,:before,::backdrop (border-color)                        ::file-selector-button
input::placeholder,textarea::placeholder
button:not(:disabled),[role=button]:not(:disabled)
```

**기각한 대안 (되살리지 말 것)**

- **(B) preflight 의 스코프한 사본을 둔다** — 사본이 **두 번째 진실원**이 되어 Tailwind
  버전업 때 조용히 어긋난다. (A) 가 성립하므로 채택할 이유가 없다.
- **(C) `@scope` 단독** — `@import` 는 `@scope` 안에 못 들어가므로 성립하지 않는다.

**플러그인 구현 시 주의**

- 포털 채널일 때만 동작해야 한다. 이 파일은 **Node 에서 평가**되므로
  `process.env.VITE_BUILD_CHANNEL === 'portal'` 로 판정한다(`vite.config.ts` 의 `IS_PORTAL_BUILD`
  와 같은 키·같은 방식 — 그 파일 주석이 「브라우저는 `import.meta.env`, Node 는 `process.env`,
  Vite 가 전자를 후자에서 채우므로 같은 값」임을 이미 기록해 두었다).
- `@tailwindcss/postcss` **뒤에** 와야 한다(그 전에는 `@import` 가 아직 안 펼쳐져 있다).
- `@layer base` **안의 규칙만** 건드린다. `theme`·`properties`·레이어 밖은 손대지 않는다.
- ⚠ `html`·`:host`·`:root` 처럼 **앵커의 조상**을 가리키는 선택자는 접두만으로는 뜻이 달라진다.
  포털 채널에서 그 규칙들이 **무엇을 해야 하는지** 개별 판단이 필요하다(아래 변경 2 참조).

**우선순위(specificity) 주의**: 접두는 반드시 **`:where()`** 로 감싼다. `:where()` 는 우선순위가
0 이라, 감싸지 않으면 리셋이 **유틸리티 클래스를 이겨** 우리 화면이 통째로 깨진다.

**앵커 자신도 포함해야 한다**: `:where(.klid-portal-embed) *` 만 쓰면 **앵커 요소 자체**가 리셋을
못 받는다. `:where(.klid-portal-embed, .klid-portal-embed *)` 형태로 자기 자신을 포함할 것.

#### 변경 2 — 우리 `@layer base` 블록도 함께 좁힌다

`global.css` 가 직접 쓴 것들도 **전부 Host 로 샌다.** 채널별 처리가 다르다:

| 현재 선언 | 포털 채널에서 어떻게 |
|---|---|
| `html, body, #root { height: 100% }` | **내보내지 않는다.** 문서를 소유하지 않으므로 의미가 없고, `#root` 는 포털 채널에 존재조차 하지 않는다. 높이는 앵커의 `h-full` 이 담당한다 |
| `body { margin, font-family, font-size, line-height, font-weight, color, background-color, font-smoothing }` | **앵커로 옮긴다** — `.klid-portal-embed { … }`. ⚠ `background-color` 를 앵커에 그대로 옮기면 Host 슬롯 배경을 덮을 수 있으니 시각 확인 후 결정 |
| v4 보정 ① `*,::after,::before,::backdrop,::file-selector-button { border-color: #cdd1d5 }` | 스코프 대상 — Host 요소의 border-color 를 바꾸면 안 된다 |
| v4 보정 ② `input::placeholder, textarea::placeholder { color: #8a949e }` | 스코프 대상 |
| v4 보정 ③ `button:not(:disabled), [role=button]:not(:disabled) { cursor: pointer }` | 스코프 대상 |
| `:root { --krds-* }` | **그대로 둔다.** 커스텀 프로퍼티 선언은 값을 「정의」할 뿐 요소 모양을 바꾸지 않는다. ⚠ 단 Host 가 같은 이름의 토큰을 쓰면 덮어쓰게 되므로 **`--krds-` 접두 토큰이 Host 에도 있는지 실측으로 확인**하고, 있으면 앵커로 옮긴다 |
| `.klid-portal-embed { --spacing: 4px }` | **그대로 둔다** — 이미 스코프돼 있다 |

#### 변경 3 — `createPortal` 5곳을 앵커 안으로

**공용 헬퍼 1개를 신설**하고 5곳이 그것을 쓰게 한다(각자 `document.body` 를 직접 참조하지 말 것).

- 헬퍼는 `document.body` 하위에 **오버레이 컨테이너 요소**를 1회 만들어 재사용하고,
  **포털 채널일 때 그 요소에 `klid-portal-embed` 클래스를 붙인다**(판정은
  `isPortalEmbedChannel()` 재사용).
- 관제 채널에서는 **기존과 동작이 같아야 한다** — 컨테이너가 `document.body` 직하에 생기는 것
  자체는 무해하나, 기존 시험이 `document.body` 를 전제하고 있으면 그 시험이 깨지는지 먼저 볼 것.
- ⚠ **스태킹 컨텍스트에 주의.** 컨테이너에 `transform` · `filter` · `position` · `z-index` 같은
  속성을 새로 주면 **모달이 배경 뒤로 들어가거나 클리핑**된다. 컨테이너는 **레이아웃에 영향이
  없어야 한다** — 클래스 외에 스타일을 주지 말고, 필요하면 최소한으로 하되 그 이유를 주석에 남길 것.
- ⚠ 앵커 클래스가 붙으면 그 컨테이너도 `--spacing: 4px` 를 받는다. **이것이 의도한 수정이다**
  (지금 포털에서 모달 간격이 눌려 있는 것을 함께 고친다).

#### 불변 (건드리면 안 되는 것)

- ★★ **관제 채널(`build:control`) 산출물은 한 글자도 달라지면 안 된다.** 그쪽은 문서를 소유한
  독립 앱이라 전역 리셋이 정상이고 **필요하다.** 검증은 「관제 산출 CSS 에 전역 리셋 선택자가
  그대로 있다」를 **포털 쪽 0건과 나란히** 확인하는 것이다. **양쪽이 함께 0 이 되면 성공이
  아니라 실패다** (`vite.config.ts` 의 tree-shaking 절이 같은 함정을 이미 기록해 두었다).
- **채널 판정을 두 벌로 만들지 않는다** — `lib/buildChannel` 의 `isPortalEmbedChannel()`(런타임) ·
  `IS_PORTAL_CHANNEL_BUILD`(빌드타임 접힘) 중 성질에 맞는 것을 **재사용**한다. 그 파일 주석이
  두 형태가 왜 있는지와 「사본이 아님」의 근거를 이미 담고 있다.
- **앵커 클래스 이름 `klid-portal-embed` 를 바꾸지 않는다** — `AuthoringRemote.tsx` 와
  `global.css` 가 이미 그 이름으로 계약돼 있고 주석이 「우리가 소유한 앵커」임을 못 박고 있다.
- **Host 가 만든 요소(`.klid-authoring-slot`)에 기대지 않는다** — 상대가 이름을 바꾸면 조용히
  깨진다(`AuthoringRemote.tsx` 주석의 기존 판단).
- **유틸리티를 레이어 밖에 두는 현재 구조를 되돌리지 않는다** — 2026-09-10 실측으로 확정된
  별개 축이며, 되돌리면 포털에서 우리 스타일이 전멸한다.
- `bundleAllCSS: true` 를 끄지 않는다 — 끄면 스타일 없는 화면이 된다.

#### 주의 (이 변경 특유의 함정)

1. **`:where()` 를 빠뜨리면 우리 화면이 깨진다** — 리셋이 유틸리티를 이긴다.
2. **앵커 자신을 스코프에 포함하지 않으면** 앵커 요소의 `box-sizing` 등이 빠져 레이아웃이 어긋난다.
3. **`html`/`body` 를 앵커로 「치환」하면 안 되는 것이 있다** — `height:100%` 는 옮기는 게 아니라
   포털 채널에서 **빼는** 것이다.
4. **모달·드로어·툴바·플로팅창이 진짜 위험 구간**이다. 스코프를 좁힌 뒤 **실제로 열어 보고**
   확인할 것 — 시험만으로는 잡히지 않는다.
5. **konva 라벨링 캔버스**는 `<canvas>` 라 CSS 리셋 영향이 작지만, 그 주변 오버레이 UI 는
   영향을 받는다.
6. 산출 CSS 는 **870KB** 다. 검사 시험은 전문 로드가 아니라 **선택자 패턴 grep** 으로 짤 것.

#### 수용기준

- **AC-1** 포털 채널 산출 CSS 에 **스코프 없는** `html` · `body` · `*,::before,::after` ·
  `ol,ul,menu` 리셋 선택자가 **0건**이다.
- **AC-2** 관제 채널 산출 CSS 에는 그 선택자들이 **그대로 있다**(건수 > 0). AC-1 과 **나란히** 확인한다.
- **AC-3** 포털 Host 의 GNB 링크(`a.gnb-main-trigger`)의 계산된 `border-style` 이
  저작도구 마운트 후에도 **`none`** 이다. (실측 판정 기준 — 개발망 확인)
- **AC-4** 포털 채널에서 우리 화면(목록·상세·라벨링·검수)과 **모달·드로어·플로팅창·라벨링 툴바**의
  시각 회귀가 없다.
- **AC-5** 프론트 전건 시험 통과. 회귀 가드 신설분 포함.

### 공유기반 선처리

**해당 없음.** 백엔드·DB·`common/` 변경 0. 프론트 단독.

---

## §4 영향·리스크

- **하위호환**: API 계약 변경 0. 백엔드 변경 0. 관제 채널 산출물 **무변경이 불변 조건**.
- **되돌리기**: 프론트 커밋 되돌림 한 번. 배포는 `klid portal 저작도구 web` 잡 재실행.
- **리스크**
  - **(높음)** 스코프를 좁히면 우리 컴포넌트가 브라우저 기본 스타일을 다시 받아 **광범위한 시각
    회귀**가 날 수 있다. 특히 스코프 밖으로 나가는 오버레이 계열.
  - **(중)** `:where()` 누락 시 우선순위 역전으로 화면 전체가 깨진다.
  - **(중)** (B) 방식을 고르면 Tailwind 버전업 때 **조용히** 어긋난다 — 드리프트 시험 필수.
  - **(낮)** 오버레이 컨테이너 신설이 스태킹 컨텍스트를 바꿔 모달 z-order 가 어긋날 수 있다.

---

## §5 검증

1. `npm run build:portal` · `npm run build:control` **둘 다** 돌려 산출 CSS 선택자 건수를 **나란히** 대조 (AC-1·AC-2)

**★ 변경 전 기준선 (2026-09-15 실측 — 두 채널 모두 빌드해 잰 값)**

| | 산출 CSS | `@layer base` | 규칙 수 | 핵심 전역 선택자 |
|---|---:|---:|---:|---:|
| 포털 채널 | 872,801 B | 4,032 B | 53 | **6건** |
| 관제 채널 | 843,393 B | 4,032 B | 53 | **6건** |

핵심 전역 선택자 6건 = `*,:after,:before,::backdrop`(preflight) · `html,:host` · `ol,ul,menu` ·
`html,body,#root`(우리) · `body`(우리) · `*,:after,:before,::backdrop`(우리 border-color 보정).

**⇒ 지금은 두 채널이 완전히 같다.** 변경 후 기대값은 **포털 0건 / 관제 6건**이다.
둘 다 0이면 관제가 깨진 것이고, 둘 다 6이면 아무것도 안 고쳐진 것이다.
기준선 사본: `/tmp/klid-css-baseline/{portal,control}-before.css` (일회성 — 필요하면 재생성)
2. 프론트 전건 시험 `npm run test`
3. **개발망 실화면 확인** — `https://192.168.102.103:38444/workspace/authoring/portal`
   · Host GNB·로고·breadcrumb·푸터에 검은 테두리 없음
   · 저작도구 화면 정상
   · **모달·드로어·플로팅창·라벨링 툴바를 실제로 열어 확인**
4. 회귀 가드 신설 — 선례 `src/lib/__tests__/routeAccessChannelStripping.test.ts`(산출물 검사)
5. ⚠ 시험 카탈로그(`docs/test-cases/`)는 **동결 상태**라 케이스 행을 추가하지 않는다.

---

## §6 관련 설계 ITEM  ★구현 전에 먼저 확정한다

| ITEM | 타입 | 무엇을 어떻게 | 근거 |
|---|---|---|---|
| INT-013 | integration_point | 포털 MF 임베딩 계약에 **스타일 격리 규약**을 확정한다 — ①우리 리셋은 문서 전역이 아니라 마운트 앵커 하위로만 적용 ②앵커는 저작도구가 소유(`klid-portal-embed`), Host 요소에 기대지 않음 ③오버레이(모달 등)도 같은 앵커 안에 둔다 ④관제 채널은 전역 리셋 유지 | 포털 회신 §3 이 「격리 책임은 저작도구」로 남긴 미합의 항목. 개발망 실측으로 파손 확인 |

**cascade 예상 하위**: `SCREEN-029`(포털 작업 화면) · 포털 채널 관련 `AC-*` · `SD-*` 렌더.
→ `analyze_impact` 로 leaf 까지 조회해 실제 대조 후 판정할 것.

**⚠ ITEM 본문에 넣지 말 것**: 레포 경로 · 파일명 · 클래스명 중 구현 내부 구조
(`Modal.tsx` · `postcss.config.js` 등) · 커밋 해시 · 구현 상태 서술.
**넣을 것**: 계약으로서의 규약 — 「리셋 범위」 「앵커 소유권」 「오버레이 포함」 「채널별 차이」.
앵커 **클래스 이름**은 Host 와 맞닿는 계약 어휘이므로 남겨도 된다.

**확정: `INT-013` v30 → v31** (2026-09-15)

- `spec_inline_markdown` 에 **「스타일 격리 규약」 절 신설** — 네 항목(리셋 적용 범위 · 앵커
  소유권 · 덧띄움 요소 포함 · 채널별 차이) + 판단 근거(「계층은 격리 수단이 아니다」).
- `description` 의 「협의 대상 — 해소」 1번을 새 절로 가리키게 하고, 구 수단 표현
  **「범위 한정 스타일 계층」을 폐기 표기**했다(그것이 바로 이번에 틀렸다고 확인한 오해다).
  「빌드타임 접두어」는 유효한 수단으로 유지.
- 쓰기 검증: 앵커 `count==1` 결정적 치환 → 재조회본이 기대본과 **바이트 완전일치**,
  spec 은 **단일 insert(삭제 0 · replace 0)**, description 은 대상 한 줄 범위로 한정.
  알려진 오타 grep 0건. `status` draft 보존.

**cascade 판정 (10건 전수 대조 — 「변경 없음」 단정 없이 실제로 열어 확인)**

| ITEM | 판정 |
|---|---|
| **SHELL-002**(포털 채널 셸) | ★**변경 필요(high)** → ✅ **v8 → v9 확정** — 규약 2·3번이 셸 소유 책임인데 본문에 앵커·스타일·덧띄움 서술이 **0건**이었다. 앵커 소유(표식 `klid-portal-embed`·앵커 자신 포함·Host 슬롯 비의존) + 덧띄움 공용 담을 곳(같은 표식·쌓임 맥락 미생성) + 관제 채널 비대상 단서를 추가하고 **근거는 복제하지 않고 `INT-013` 을 가리켰다.** 검증: 재조회본 문자 단위 완전일치 · **삽입 1건(633자), 치환·삭제 0** · 상한 여유 310자 · 링크 1건 신설(→`INT-013`) |
| SHELL-002 하위 NAV-002 | 변경 불요 — 메뉴 트리·이동 탭·Host 소유 경계 축뿐이고 앵커·리셋·덧띄움 서술 0건 |
| DOMAIN-013 · ADR-012 · SEQ-034 · INT-015 · SHELL-001 · AC-1104/1105/1106 | 변경 불요 — 축이 다르다(토큰 인계·배포 향·채널 격리·전역 인가). ⚠ 「격리」·「전역」 낱말이 겹칠 뿐이므로 스타일 축을 끌어오지 말 것 |
| EXTSYS-006 | 본문이 「격리 **책임**은 저작도구」로 미러하는데 그 문장은 여전히 참이고 폐기된 **수단** 표현은 담고 있지 않다 — 변경 불요 |

⚠ **SHELL-001(관제 채널 셸)을 함께 고치면 관제 화면이 깨진다** — 규약 4번이 관제 채널
무변경을 명시한다.

**★ 잔여 — 다음 라운드로 넘긴다**

1. ★★ **쓰기가 서버의 stale 표식을 자동 해제한다 — 이번 라운드에서 두 번 일어났다.**
   편집이 그 stale 의 **원인을 대조한 것이 아닌데도** 표식이 사라져, **다음 감사에서 그
   미대조가 드러나지 않는다.** 그래서 여기에 명시적으로 남긴다.

   | ITEM | 원래 stale 사유 | 이번 편집이 한 것 | 남은 미대조 |
   |---|---|---|---|
   | `INT-013` | `API-247` 의 3개 필드 변경 — references | 스타일 격리 축만 | **`API-247` 대조** |
   | `SHELL-002` | `SCREEN-045` 의 `static_renders` 변경 — applies_to | 앵커·덧띄움 축만 | **`SCREEN-045` 대조** |

   ⚠ 이건 이 저장소의 **구조적 함정**이다 — 무관한 편집이 남의 stale 을 지운다.
   앞으로도 ITEM 을 고칠 때 **쓰기 전 stale 사유를 기록**할 것.
   추가로 `NAV-002` 도 `SCREEN-045` 사유로 stale 이며 아직 해소되지 않았다.
2. **필드 상한 근접** — `spec_inline_markdown` 19,983 / 20,000자(잔여 17자) ·
   `description` 3,988 / 4,000자(잔여 12자). **다음 편집은 넣는 만큼 줄여야 하며 무심코
   append 하면 실패한다.**
3. **수용기준 ITEM 신설 여부(사용자 확정 필요)** — 이 규약의 판정 기준을 검증하는
   `acceptance` ITEM 이 프로젝트 전역에 **0건**이다(활성 1,750 ITEM 전수 확인).
4. **DS-002 반영 여부** — 포털 디자인 시스템은 색·간격 토큰 축이라 리셋 범위 축을 갖고 있지
   않다. **기본 권고는 `INT-013` 단일 정본 유지**(중복 서술 방지).

---

## §7 구현 로그

| 일시 | 도메인 | 에이전트 | 결과 | QA | 커밋 |
|---|---|---|---|---|---|
| | | | | | |

**미반영·보류 항목**:
- 포털 채널 모달·드로어의 간격 눌림(`--spacing` 미적용)이 **이번 변경 이전부터** 있었는지
  개발망에서 실측 확인 — 변경 3 이 함께 해소하므로 별건으로 남기지 않되, **확인 결과는 기록**할 것.
