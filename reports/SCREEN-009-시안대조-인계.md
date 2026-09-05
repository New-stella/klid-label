# SCREEN-009 영상 상세 — 시안↔구현 격차 전수 대조 (gui 인계본)

- 작성: issue-0826 워크트리 · 2026-08-26
- 기준 시안: `docs/screen-design/klid-authoring-screens/screens/SCREEN-009/design/design-main.{html,css}` (SD-004 v18, 서버 현재 판)
- 판정: **CODE-WRONG 82 · SD-STALE 2 · UNCLEAR 8**
- 대조 방식: **소스·CSS 정적 대조.** 브라우저 실렌더 대조는 하지 않았다(아래 「확인 못 한 것」).

## 왜 인계하나

사용자가 "이력 카드 모양이 시안과 다르다"고 지적해 조사했더니 화면 전역에 같은 성격의 격차가
있었고, 그중 8건이 공용 컴포넌트라 32화면에 파급한다. 사용자 결정: **화면 전용 74건까지 포함해
전량을 gui 워크트리가 맡는다.** issue-0826 은 이 축에 손대지 않는다.

## 토큰 전제 (중요)

`tailwind.config.js` 팔레트가 시안 CSS 변수와 **1:1 동일**하다 — `--n-2`=`gray-200`=#CDD1D5,
`--e-5`=`danger`=#DE3412, `--radius-md`=`rounded-md`=6px 등. 따라서 아래 색·반경 차이는
**토큰 부재가 아니라 선택의 차이**다. 새 hex 를 도입할 필요가 없다.

## 시안 신선도

`design-main.html` 이 최신 변경(「조치가 필요한 작업 묶음」·「산출물을 이미 보유한 묶음은 제외」·
검수완료 영상의 오토라벨 재수행 비활성)을 이미 담고 있다 → **배치 패널 축의 시안은 낡지 않았다.**
SD-STALE 로 판정된 것은 라이트박스 2건뿐이다.

---

## A. 페이지 레벨

| # | 축 | 시안 | 구현 | 판정 |
|---|---|---|---|---|
| A1 | 페이지 헤더 | `.page-head` — breadcrumb + `h1.t-title-lg` + `p.page-desc` | 없음. `PageHeader.tsx`(13개 페이지 사용) 미사용 | UNCLEAR — 사양에 해당 절 없음 |
| A2 | 블록 간 간격 | `.screen-root { gap: --sp-lg }` 24px | `space-y-4` 16px | **CODE-WRONG** |

## B. 영상 헤더 카드

| # | 축 | 시안 | 구현 | 판정 |
|---|---|---|---|---|
| B1 | 뒤로가기 위치 | `.hero-top` — 카드 **안** | 카드 **바깥·위** | **CODE-WRONG** (사양 component[1] 도 카드 소속) |
| B2 | 뒤로가기 형태 | `.btn.btn-ghost` min-h 44px·radius 6px·`t-button`(17/500)·아이콘 20px | 순수 `<button>` + `ArrowLeft size={16}`, `Button` 미사용 | **CODE-WRONG** |
| B3 | 카드 세로 패딩 | `padding: --sp-lg` 24px 전방향 | `Card py-4`(16) + `CardContent px-6`(24) | **CODE-WRONG** |
| B4 | 썸네일 크기 | 200×120, radius 6px | `w-32 h-20`(128×80), `rounded-lg`(8px) | **CODE-WRONG** |
| B5 | 썸네일 매트 | `--n-9`(#1E2124) 다크 + 중앙 비디오 아이콘 | `bg-gray-100`(#E6E8EA) | **CODE-WRONG** |
| B6 | 썸네일 캡션 | 좌하단 오버레이 `#0 · 00:00` | 없음 | **CODE-WRONG** |
| B7 | 본문 gap | 24px | `gap-3` 12px | **CODE-WRONG** |
| B8 | 제목 굵기 | `t-title-md` 18/**600** | `font-bold`(700) — ladder 덮어씀 | **CODE-WRONG**(경미) |
| B9 | 메타 행 | gap 24px, key `n-6` / value `n-9` **2색** | `gap-4`, 전체 `text-gray-500` 단일색 | **CODE-WRONG** |
| B10 | 메타 표기 | 콜론 없음, 값은 mono | `길이: 3분 5초`, mono 없음 | **CODE-WRONG** |
| B11 | 라벨 문구 | `녹화 시각` | `녹화일` | **CODE-WRONG** (사양도 「녹화 시각」) |

## C. 좌측 탭 레일

| # | 축 | 시안 | 구현 | 판정 |
|---|---|---|---|---|
| C1 | 레일 패딩 | 8px | `p-3` 12px | **CODE-WRONG** |
| C2 | 헤딩 색 | `--n-5`(#6D7882) | `text-gray-400`(#8A949E=n-4) | **CODE-WRONG** |
| C3 | 헤딩 좌우 패딩 | `8px 8px 4px` | `px-3 pt-2 pb-1` | **CODE-WRONG**(경미) |
| C4 | 선택 탭 굵기 | 600 | `font-medium`(500) — `Tabs.tsx` | **CODE-WRONG**(경미) |
| C5 | 나머지 | 폭 200·gap 2·radius 6·border-left 3px·sticky 24px·선택색 | **전부 일치** | — |

## D. 메타 정보 그리드

| # | 축 | 시안 | 구현 | 판정 |
|---|---|---|---|---|
| D1 | mono 값 글꼴 | `font-family: --font-mono`(D2Coding) | `tabular-nums` 만, `font-mono` 없음 | **CODE-WRONG** |
| D2 | 나머지 | 2열·gap 16/24·dt/dd 등급 | **전부 일치** | — |

## E. 처리 단계 (BatchStageIndicator) ⚠ 마킹 화면과 공유

| # | 축 | 시안 | 구현 | 판정 |
|---|---|---|---|---|
| E1 | 밴드 상단 여백 | `margin-top 8 + padding-top 16 + border-top` | 부모 `gap-6` + `pt-4` | **CODE-WRONG**(경미) |
| E2 | 라벨 색 | 본문색 `--n-9` | `text-gray-600` | **CODE-WRONG** |
| E3 | 점 형태 | **16×16 단일 점**, border 2px, 상태색 채움 | `w-8 h-8` 링 + 내부 8px 점, 링 `bg-{tone}/10` | **CODE-WRONG** |
| E4 | PROGRESS 색 | `--p-5`(#256EF4 primary) + `box-shadow 0 0 0 3px --p-1` | `bg-info/10 border-info`, 점 `#0B78CB`(info) | UNCLEAR — UI-018 은 "파랑"만 규정 |
| E5 | PENDING 색 | 점 배경 흰색 + 테두리 `--n-3` | `bg-gray-200 border-gray-300` + 점 `bg-gray-400` | **CODE-WRONG** |
| E6 | 캡션 상태색 | done=su-6 / progress=p-6+600 / fail=e-6+600 / pending=n-5 | 전 상태 `text-gray-600` 고정 | **CODE-WRONG** |
| E7 | 보조 표기 색 | `--n-6` | `text-gray-500` | **CODE-WRONG**(경미) |
| E8 | 캡션 줄바꿈 | `max-width 120px` + `word-break: keep-all`(2줄 허용) | `whitespace-nowrap` | **CODE-WRONG**(경미) |
| E9 | 연결선 | 2px, done=`su-4`(#3FA654) / 그 외 `n-2` | 2px, done=`bg-success`(#228738=su-5) | **CODE-WRONG**(경미) |

## F. 배치 실패 사유·조치 (BatchFailurePanel) — 격차 최대

| # | 축 | 시안 | 구현 | 판정 |
|---|---|---|---|---|
| F1 | 패널 좌측 강조바 | `border-left 4px --e-5`(warn=w-5 / neutral=n-4) | 없음 | **CODE-WRONG** |
| F2 | 패널 테두리·배경 | `1px --e-2`(#F7AFA1) + **흰 배경** | `border-danger/40` + `bg-danger/5` 틴트 | **CODE-WRONG** |
| F3 | warn 톤 | `data-tone=warn` → **노랑 계열**(w-2/w-5) | skipped·cleared·bundleFailure·lastFailure 전부 **회색** | **CODE-WRONG** |
| F4 | neutral 톤(파생영상) | 전용 표면(`--border` / n-4) | 전용 톤 없음, 안내 문단만 | **CODE-WRONG** |
| F5 | 패널 패딩·gap | padding 16 전방향, gap 16 | `px-4 py-3`, 자식 개별 마진 | **CODE-WRONG**(경미) |
| F6 | 패널 상단 여백 | 24px | 부모 `gap-6` | — 일치 |
| F7 | 헤드 우측 역할 배지 | `검수자 전용` / `파생영상` / `검수 완료` 배지 | 없음. 좌측 `AlertTriangle` 아이콘 | **CODE-WRONG** |
| F8 | 제목 색 | `--n-10` | `text-gray-800`(n-8) | **CODE-WRONG**(경미) |
| F9 | 실패 사유 블록 | `.alert.alert-error` 박스(e-0 배경·e-2 테두리·radius 6·padding 16·아이콘) | 박스 없음, 평문 `<dl>` | **CODE-WRONG** |
| F10 | 실패 단계 표기 | `.fp-stage-chip` 알약 칩 + 점(e-1/e-7) | 평문 `<dd>` | **CODE-WRONG** |
| F11 | 서버 문구 표식 | 정보 아이콘 + "문구는 서버가 보낸 그대로입니다" | 없음 | **CODE-WRONG** |
| F12 | 묶음 목록 제목 | `h4 "작업 묶음"` | 없음 | **CODE-WRONG** |
| F13 | 묶음 행 표면 | `border 1px` + `border-left 4px`(fail=e-5/skipped=w-5/released=p-5) + radius 6 + padding 8/16 + 흰 배경 | `<li className="flex flex-col gap-1">` — 테두리·배경·강조바 **전무** | **CODE-WRONG** |
| F14 | 묶음 행 레이아웃 | `grid minmax(180px,1.4fr) auto minmax(0,1fr)` — 버튼 **우측 정렬** | `flex flex-wrap` 좌측 흐름 | **CODE-WRONG** |
| F15 | 묶음 부제 | `오토라벨링` 아래 `AI 탐지 · AI 분할 · 트랙 보간` 별도 줄(state별 색) | 없음 — 이름 한 줄뿐 | **CODE-WRONG** ⚠ 묶음 *이름* 을 멤버 나열로 되돌리라는 뜻이 **아니다**. 이름 + **부제** 병기다 |
| F16 | 묶음 이름 타이포 | `t-title-sm`(17/600) + n-10 | 부모 `text-body-md`(17/400) | **CODE-WRONG** |
| F17 | 전체 재기동 위치 | `.fp-foot` — 패널 **맨 아래**, border-top + 좌측 안내 / **우측** 버튼 | 묶음 목록 **위**, 버튼 아래 힌트, 구분선 없음 | **CODE-WRONG**(구조) |
| F18 | 배지 형태 | padding `3px 10px`, 14/**600**, **상태 점 포함** | `Badge`(UI-111) `px-2 py-0.5`, 14/600, 점 없음 | UNCLEAR — UI-014 "배지에 아이콘 금지"(점이 아이콘인지 미규정) |
| F19 | 건너뜀/해제 배지 색 | `badge-warn`(w-0/w-7) · `badge-info`(i-0/i-7) | 둘 다 `neutral`(gray-100/700) | **CODE-WRONG** |
| F20 | 조작 버튼 variant | 건너뛰기=secondary · 재수행=**primary** | 재수행 `secondary` | **CODE-WRONG** |

## G. 비식별 이력 (DeidentHistoryPanel) — 사용자가 처음 지적한 곳

| # | 축 | 시안 | 구현 | 판정 |
|---|---|---|---|---|
| G1 | 패널 래퍼 | `border 1px` + **`border-left 4px --n-4`** + radius 8 + 흰 배경 + padding 16 + gap 16 | 래퍼가 `<div>` 뿐 | **CODE-WRONG** |
| G2 | 이력 항목 카드 | `1px solid --border` + 배경 `#fff` | 테두리 없음 + `bg-gray-50`(#F4F5F6) | **CODE-WRONG** |
| G3 | 항목 모서리 | `--radius-md` 6px | `rounded-lg` 8px | **CODE-WRONG** |
| G4 | 수치 그리드 구분선 | `padding-top 8 + border-top 1px --n-1` | `mt-2` 만 | **CODE-WRONG** |
| G5 | 항목 상태 강조바 | fail→좌측 4px e-5 / progress→p-5 | 없음 | **CODE-WRONG** |
| G6 | 항목 패딩 | `8px 16px` | `px-4 py-3` | **CODE-WRONG**(경미) |
| G7 | 헤드 우측 역할 배지 | `검수자 · 라벨링 작업자` | 없음 | **CODE-WRONG** |
| G8 | 제목 태그·색 | `h3.t-title-sm` + n-10 | `h4 ... text-gray-700` | **CODE-WRONG**(경미) |
| G9 | 회차 종류 부제 | `비식별` 아래 `배치 비식별` / `재비식별` 아래 `검수 완료 후 재비식별` | **없음** | **CODE-WRONG** |
| G10 | 종류 타이포 | `t-title-sm`(17/600) + n-10 | `text-body-md font-medium`(17/500) | **CODE-WRONG**(경미) |
| G11 | 요청 일시 정렬 | `.fp-spacer` 로 **우측 끝** + nowrap | 배지 뒤 좌측 흐름 | **CODE-WRONG** |
| G12 | 집계 열 폭 | `repeat(3, minmax(0,240px))` — 좌측으로 모음(넓은 폭 흩어짐 방지, CSS 주석 명시) | `grid-cols-3` 균등 | **CODE-WRONG** |
| G13 | 집계 타이포 | dt `t-label`(14/**600**) · dd `t-body-sm`(15/400) + **font-mono** + n-9 | dt `text-caption`(14/400) · dd `text-body-md`(17/500) + tabular-nums | **CODE-WRONG** |
| G14 | 처리 구간 아이콘 | 시계 아이콘 + 문구 | 아이콘 없음 | **CODE-WRONG** |
| G15 | 패널 푸터 안내 | `border-top` + "한 항목이 위탁 1회차이며 … 최근 20건 … 결과 못 받은 회차는 …" | **없음** | **CODE-WRONG** (사양 note 가 규정하는 정책 문구) |
| G16 | 빈 상태 | `.state-box` 방패 아이콘 + `t-body-sm`/600/n-8, padding 24 | 아이콘 없는 `<p>` + 회색 타일 | **CODE-WRONG** |
| G17 | 상태 배지 색 | 완료=success · 진행 중=**info**(i-0/i-7) · 실패=error | 완료 success · 진행 중 **warning** · 실패 danger | **CODE-WRONG** ⚠ 진행 중이 **색 계열 자체가 다름** |
| G18 | 배지 타이포 | 14/600 + `3px 10px` + 점 | `text-caption`(14/400) + `px-2 py-0.5` | **CODE-WRONG**(경미) |

## H. 프레임 미리보기 탭

| # | 축 | 시안 | 구현 | 판정 |
|---|---|---|---|---|
| H1 | 툴바 | "총 N개 중 N개 표시" + "빨간 점 = 이슈" 범례 | 없음 | UNCLEAR — 사양에 해당 요소 없음 |
| H2 | 타일 테두리 | `1px solid --border` + radius 6 | 테두리 없음, `rounded-md` | **CODE-WRONG** |
| H3 | 프레임 번호 위치 | 이미지 **아래** 흰 스트립(`t-caption`+n-6) | 이미지 **위** 오버레이 `bg-black/50 text-white` | **CODE-WRONG** |
| H4 | 미디어 매트 | `--n-8` 다크 | `bg-gray-100` | **CODE-WRONG** |
| H5 | 이슈 점 | 10px + **흰 테두리 2px + shadow-sm**, top/right 6px | 8px, 링·그림자 없음, `top-1 right-1` | **CODE-WRONG** |
| H6 | "상세 보기" | hover/focus 시 그라데이션 + `btn-secondary btn-sm` 노출 | 없음(타일 전체 버튼, aria-label 만) | **CODE-WRONG** — 사양 component[2] `Button "프레임 상세 보기"` 실재 |
| H7 | 열 수 브레이크포인트 | 6열 ≥1280 / **4열** ≤1279 | 6열 ≥768 / 3열 <768 | UNCLEAR — 사양은 "3~6열"만 규정 |

## I. 프레임 라이트박스 모달

| # | 축 | 시안 | 구현 | 판정 |
|---|---|---|---|---|
| I1 | 폭 | `min(720px, …)` | `Modal size="xl"` = 1024px | **SD-STALE** — 사양 note "Modal size=xl" |
| I2 | 미디어 매트 | `--n-9` + `aspect-ratio 16/9` + radius 6 | `object-contain max-h-80 bg-gray-100 rounded-lg` | **CODE-WRONG** |
| I3 | 타임스탬프 표기 | `t-mono "00:00:00.960"` | `{ms} ms` raw | **SD-STALE** — 사양 "타임스탬프(ms)" |
| I4 | 이슈 표시 | `badge-error "이슈 있음"` | 평문 `<span>` | **CODE-WRONG** |
| I5 | 모서리 | `--radius-lg` 8px | `rounded-xl` **12px**(config 에 xl 미정의 → Tailwind 기본) | **CODE-WRONG** *(공용)* |

## J. 오토라벨 결과 탭

| # | 축 | 시안 | 구현 | 판정 |
|---|---|---|---|---|
| J1 | 통계 카드 열 수 | 4열(≤1279 시 2열) | `grid-cols-2 md:grid-cols-3` — 4항목이 **3열**이라 마지막 줄 어긋남 | **CODE-WRONG** |
| J2 | 통계 카드 표면 | `border 1px` + radius 8 + **흰 배경** + padding 16 | `bg-gray-50 rounded-lg p-3` 회색 타일 | **CODE-WRONG** |
| J3 | 통계 값 크기 | `t-display-sm`(26/700) + n-10, 단위 17/400 n-6 | `text-body-md font-semibold`(17/600), 단위 분리 없음 | **CODE-WRONG** |
| J4 | 통계 라벨 | `t-label`(14/600) | `text-caption`(14/400) | **CODE-WRONG**(경미) |
| J5 | **처리 상태 값** | `badge-error "보간 실패"` — 실제 상태 배지 | **문자열 `'COMPLETED'` 하드코딩** | **CODE-WRONG** ⚠ **표면이 아니라 내용 결함** |
| J6 | 차트 배치 | `grid 1fr 1fr` — 두 차트 **좌우**, 각각 카드(테두리+radius 8+padding 16+흰 배경) | 세로 스택, 카드 컨테이너 없음 | **CODE-WRONG** |
| J7 | 차트 제목 색 | `--n-9`, `margin-bottom 16` | `text-gray-700`, `mb-1` | **CODE-WRONG**(경미) |
| J8 | 막대 차트 크기 | height 180px, 막대 width 40px, `radius 3px 3px 0 0`, 하단 축선 | `h-24`(96) + 60px 막대, `w-full`, `rounded-t`, 축선 없음 | **CODE-WRONG** |
| J9 | 중간 신뢰도 색 | `--w-4`(#C78500) | `bg-warning`(#9E6A00=w-5) | **CODE-WRONG**(경미) |
| J10 | 막대 라벨 문구 | `0.9 이상` / `0.7~0.9` / `0.7 미만` | `0.9+` / `0.7~0.9` / `<0.7` | **CODE-WRONG**(표기) |
| J11 | 막대 값 타이포 | `t-body-sm`(15/400) + n-9 | `text-label font-semibold`(14/600) | **CODE-WRONG**(경미) |
| J12 | 분포 열 폭 | `96px 1fr 48px`, 이름·수치 `t-body-sm` | `w-20`(80) / flex-1 / `w-8`(32), 둘 다 14px | **CODE-WRONG** |
| J13 | 분포 막대 색 | `--p-5` **primary 단일색** | 인라인 **라벨 고유색** | UNCLEAR — 코드 주석이 "라벨 고유색이라 불변" 명시, 사양 미규정 |
| J14 | 트랙 배경 | `--n-0`(#F4F5F6) | `bg-gray-100`(#E6E8EA=n-1) | **CODE-WRONG**(경미) |
| J15 | 제목 문구 | `라벨별 분포 (상위 10종)` | `라벨별 분포` (코드는 실제 `slice(0,10)`) | **CODE-WRONG**(표기) |
| J16 | 로딩 스켈레톤 | `n-1` 배경 + radius 4 + sweep, 폭 60/100/100% | `h-16 bg-gray-100 animate-pulse rounded-lg` ×3 동일 폭, 공용 `Skeleton` 미사용 | **CODE-WRONG** |
| J17 | 에러 상태 | 32px 경고 아이콘(e-5) + 제목(e-7/600) + 캡션 | 아이콘 없는 `<p>` ×2, 공용 `ErrorState` 미사용 | **CODE-WRONG** |
| J18 | 빈 상태 | 32px 아이콘 + 제목 600/n-8 + 캡션 | 아이콘 없는 `<p>` ×2 | **CODE-WRONG** |

## K. 건너뛰기 / 재수행 모달

| # | 축 | 시안 | 구현 | 판정 |
|---|---|---|---|---|
| K1 | 대상 묶음 표시 | "대상 묶음" 라벨 + 중립 칩 + 멤버 부제 | 없음(제목 문자열에만) | **CODE-WRONG** |
| K2 | 설명 타이포 | `t-body-md`(17/400) + n-7 | `Modal` description `text-sub`(14/400) | **CODE-WRONG** |
| K3 | 필수 표식 | `사유 <span class="req">*</span>`(e-6) | `*` 없음 | **CODE-WRONG** |
| K4 | 글자수 카운터 | `"32 / 500"` 라벨 줄 **우측** | 없음(`maxLength` 만) | **CODE-WRONG** |
| K5 | 도움말 문구 | "배치 이력에 그대로 남습니다. 공백만으로는 저장되지 않습니다." | 없음 | **CODE-WRONG** |
| K6 | textarea | min-h 88px, `8px 16px`, `border 1px --border-strong`(n-4) | `min-h-[120px]`, `px-3 py-2`, `border-gray-300`(n-3) | **CODE-WRONG**(경미) |
| K7 | 취소 버튼 | `btn-secondary`(흰 배경 + 중립 테두리) | `variant="outline"`(primary 색) | **CODE-WRONG** |
| K8 | 재수행 경고 | `e-0` 배경 + `e-2` 테두리 박스 + 아이콘 | `ConfirmDialog` description 평문 | **CODE-WRONG** |
| K9 | 확인창 폭 | 520px | `size="sm"` = 384px | **CODE-WRONG** |
| K10 | 시계열 재수행 확인창 | 존재(비파괴 안내용) | 확인창 없이 즉시 mutate | UNCLEAR — 사양 note 는 오토라벨에만 사전 고지 요구, 코드 주석이 의도 명시 |

## L. 공용 컴포넌트 — 다른 화면에 파급

| # | 축 | 시안 | 구현 | 판정 |
|---|---|---|---|---|
| L1 | 버튼 모서리 | `--radius-md` **6px** | `rounded-lg` **8px** | **CODE-WRONG** *(전 화면)* |
| L2 | primary 배경 | `--p-5` #256EF4 | `bg-primary-600` #0B50D0 | UNCLEAR — **둘 다 AA 통과**(4.55:1 / 6.83:1, 실측). 구현이 여유가 넓을 뿐이라 접근성 근거로 시안을 기각할 수 없다 |
| L3 | secondary 테두리 | `--border-strong` n-4 #8A949E | `border-gray-300` #B1B8BE | **CODE-WRONG** |
| L4 | sm 버튼 높이 | min-h 36px, 15px | min-h 없음, 14px | **CODE-WRONG**(경미) |
| L5 | 모달 모서리 | 8px | `rounded-xl` 12px | **CODE-WRONG** |
| L6 | 모달 닫기 색 | `--n-6` | `text-gray-400`(n-4) | **CODE-WRONG**(경미) |
| L7 | 카드 세로 패딩 | 24px 전방향 | `py-4`(16) + `px-6`(24) | **CODE-WRONG** *(전 화면)* |
| L8 | StatusBadge 타이포 | 14/**600**, `3px 10px` | `text-sub font-medium`(14/500), `px-2 py-0.5` | **CODE-WRONG**(경미) *(전 화면)* |

---

## ⚠ 정정 이력

- **L2 대비 수치 (2026-08-26)** — 초판에 `#256EF4 = 3.6:1 < 4.5` 로 적었으나 **틀렸다.**
  직접 계산하면 **4.55:1 로 AA 를 통과**한다(구현 `#0B50D0` 은 6.83:1). 대비는 대칭이라
  「흰 배경 위 파란 글자」와 「파란 배경 위 흰 글자」가 같은 값이다.
  DS-001 `known_gaps` 도 *"#256EF4 는 4.55:1 로 AA 를 통과하나 여유가 거의 없다"* 로 적고 있다.
  ⇒ **판정은 UNCLEAR 유지**지만 사유가 바뀐다 — "시안이 AA 미달"이 아니라 "둘 다 통과하나
  구현이 여유가 넓다"이다. 접근성을 근거로 시안을 기각할 수는 없다.

## ★ 한 곳만 고치면 오히려 어긋나는 묶음 (반드시 함께 이동)

1. **G1+G2+G3 는 한 묶음.** 시안은 흰 카드(`.dh-item`)가 흰 패널(`.deident-panel`, 좌측 4px n-4 강조바)
   **안에** 있어 성립한다. 패널을 맨 `<div>` 로 둔 채 카드만 흰색+테두리로 바꾸면 **흰 위의 흰**이 되어
   지금(gray-50 타일)보다 구분이 약해진다.
2. **F13 은 F1/F2 와 한 묶음.** `.group-row`(흰 카드)는 `.failure-panel`(흰 배경 + 좌측 강조바) 안의
   카드다. 패널 배경을 `bg-danger/5` 로 둔 채 행만 흰 카드로 만들면 **위험 틴트 위에 흰 카드**가 뜬다.
3. **F3 과 F19 는 함께.** 시안 CSS 주석: *"행 배경을 물들이면 같은 색 계열 배지가 배경에 묻혀 알약
   형태를 잃는다."* 패널만 warn 노랑으로 바꾸고 배지를 gray 로 두면 반대로 배지만 튄다.
4. **L1(버튼 6px)·L5(모달 8px)·G3(카드 6px) 은 같은 라운드에서 결정.** 현재 버튼 8 / 모달 12 / 카드 8
   세 값이고 시안은 버튼 6 / 모달 8 / 항목 카드 6 / 패널 8 이다. 한 컴포넌트만 내리면 **한 화면에 6·8·12
   세 반경이 공존**한다.
5. **E3 과 E9 는 한 묶음.** 연결선 `top:15 / left:calc(50%+18px)` 이 **32px 링을 전제한 상수**라,
   점만 16px 로 줄이면 선이 공중에 뜬다.
6. **F17 은 F12 와 함께.** 버튼만 아래로 내리고 목록 제목이 없으면 패널이 "사유 → 목록 → 버튼"
   세 덩어리 중 **가운데가 이름 없는 상태**가 된다.

## 확인 못 한 것 (정보 부족 — 그대로 넘긴다)

- **브라우저 실렌더 대조 미수행.** 위는 전부 소스·CSS 정적 대조다. 실제 픽셀(반응형 접힘, 폰트 로드,
  `field-sizing:content` 로 인한 textarea 실높이) 미확인.
- `AuthImage.tsx` 내부(로딩·실패 표면) 미확인
- `Field`/`FieldLabel`/`FieldError` 내부 스타일 미확인
- `Spinner`, `lib/focusRing.KRDS_FOCUS` 값 — 시안 `outline 3px --p-5 / offset 2px` 와 대조 못 함
- `Tabs orientation='vertical'` 의 **다른 화면 사용처 유무** 미확인 (C4 파급 범위 판단에 필요)
- 시안 `design-notes.md`(40KB) 미열람 — UNCLEAR 8건의 일부가 거기서 해소될 수 있다
- `BulkRetryResultModal`·`BulkSkipReasonModal`·`VlmSkipDefaultBanner` 는 `VideoDetailPage` import 에
  없어 **이 화면 대상 아님**으로 제외(import 로 확인)

## issue-0826 이 이 파일들에 대해 갖는 계획

**없다.** 이 축 전량을 gui 가 맡는다. 다만 아래 둘은 issue-0826 이 별도로 진행 중이니 충돌 주의:

- `features/video/components/BatchFailurePanel.tsx` · `DeidentHistoryPanel.tsx` — 방금 `main` 에 머지
  (`8780f1fa`). 배치 배너 조치 축 + `Badge` `error` variant 신설. **최신 main 을 받고 시작할 것.**
- `pages/VideoDetailPage.tsx` — 같은 머지에 해상도·CCTV ID 실값 노출 변경 포함.
- **백엔드** `video/service/VideoQueryService.java` 의 오토라벨 응답(라벨 마스터 조인)은 issue-0826 이
  진행한다. J5(처리 상태 하드코딩)와 **다른 항목**이니 혼동 말 것 — J5 는 프론트다.
