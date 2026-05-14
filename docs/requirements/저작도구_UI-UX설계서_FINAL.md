# 학습데이터 저작도구 · UI/UX 설계서

> **사업명** : AI 기반 지방정부 CCTV 관제지원시스템 구축(2차)
> **발주기관** : 한국지역정보개발원 (디지털사업개발부)
> **수행기관** : 쿠도커뮤니케이션 | **담당** : 박찬기 | **Ver** : FINAL (mock 기준 전면 재작성) | **작성일** : 2026-05-11 | **기준 commit** : `df2ac0f`

---

## 목차
1. [문서 개요](#1-문서-개요)
2. [공통 UI 규칙](#2-공통-ui-규칙)
3. [공통 컴포넌트](#3-공통-컴포넌트)
4. [화면 설계](#4-화면-설계)
   - 4-1. [세션 인계 · 진입](#4-1-세션-인계--진입)
   - 4-2. [영상 처리 현황](#4-2-영상-처리-현황-메인-진입점)
   - 4-3. [메인 대시보드](#4-3-메인-대시보드)
   - 4-4. [프리셋 관리](#4-4-프리셋-관리)
   - 4-5. [작업 목록 · 배정](#4-5-작업-목록--배정)
   - 4-6. [라벨링 도구](#4-6-라벨링-도구)
   - 4-7. [오토라벨링 결과 · 시계열 메타데이터 검토](#4-7-오토라벨링-결과--시계열-메타데이터-검토)
   - 4-8. [히스토리 · 버전관리](#4-8-히스토리--버전관리)
   - 4-9. [검수 워크플로우](#4-9-검수-워크플로우)
   - 4-10. [대시보드 · 통계](#4-10-대시보드--통계)
   - 4-11. [데이터 증강](#4-11-데이터-증강) (V2 — 내보내기 화면 제거)
   - 4-12. [포털 진입 모드](#4-12-포털-진입-모드-외부-사용자)
   - 4-13. [관리 · 사용자 관리](#4-13-관리--사용자-관리)
5. [사용자 플로우](#5-사용자-플로우)
6. [접근성 · 반응형 정책](#6-접근성--반응형-정책)
7. [화면 ID 인덱스](#7-화면-id-인덱스)

---

## 1. 문서 개요

### 1.1 목적

본 문서는 학습데이터 저작도구의 UI/UX 설계 기준서다. **mock 구현체(`mock/` 디렉토리)가 단일 진실 공급원(single source of truth)** 이며, 본 설계서는 mock에 구현된 화면·컴포넌트·인터랙션을 정확히 기술한다. mock에 없는 기능은 "(미구현 — 후속 적용)"으로 명시한다.

### 1.2 범위

- **대상 시스템**: AI 기반 지방정부 CCTV 관제지원시스템(2차)의 학습데이터 저작도구
- **사용자 채널**: 내부(관제서버 인계) — 검수자/라벨링 작업자, 외부(포털 인계) — 포털 사용자
- **포함 기능**: 영상 처리 현황·라벨링·검수·증강 결과 검토·통계·시스템 설정 (V2 — 저작도구 내 "내보내기" 화면·메뉴 제거. 외부 시스템 학습데이터 API는 화면 미보유)
- **제외 기능 (V1.4~V1.8 외부 이관)**:
  - **데이터마트 (SFR-13)** — 외부제공 시스템 책임으로 이관
  - **생성형 AI 모델 학습/UI (SFR-06)** — 외부 시스템. 저작도구는 SCR-AUG-002 결과 검수만 보유
  - **다운로드 기능 (SFR-15)** — 포털 자체 책임. 저작도구 mock에 다운로드 카드/D-day/만료 UI 없음
  - **VLM 시계열 메타 자동 추출 본체 (SFR-03)** — 외부 시스템. 저작도구는 SCR-AUTO-002에서 검토·수정만 제공
  - **영상 합성 모델 (SFR-11)** — 외부 시스템

### 1.3 관련 SFR (요구사항정의서와 동기화)

V1.8 이후 본체가 외부 시스템인 SFR-03·SFR-06·SFR-11은 요구사항정의서에서 제거되었고 저작도구 잔존 책임은 모두 **SFR-08(저작도구 핵심 기능)** 에 흡수되었다. 본 설계서가 다루는 SFR은:

- **SFR-08** : 저작도구 핵심 기능 (영상 처리 현황·라벨링·검수·증강 결과 검수·외부 메타 검토·배경영상 요청·생성된 영상 라벨링·외부 시스템 학습데이터 API)
- **SFR-07** : 데이터 증강 결과 활용 결정 (SCR-AUG-002)
- **SFR-09** : 비식별 처리 — V2 자동 파이프라인 영상 단위 무조건 단계. 결과 검토 화면(SCR-DEIDENT-*)은 미제공이며 라벨러 [비식별 누락 신고]로 갈음
- **SFR-10** : 검수 워크플로우 (SCR-REVIEW-*)
- **SFR-12** : 통계/대시보드 (SCR-STAT-*, SCR-DASH-001)
- **SFR-14** : 사용자/시스템 관리 (SCR-MANAGE-*)
- **SFR-16/17** : 학습데이터 산출 목표 (이미지 10만장·영상 5,000건)

> **V2 변경**: SFR-09 학습데이터 내보내기 화면(SCR-EXPORT-001)은 V2에서 제거되었다. 외부 시스템(데이터마트·외부 학습 시스템)이 학습데이터를 가져가는 외부 API만 유지하며 저작도구 내 사용자 화면은 없다.

### 1.4 화면 ID 체계

화면 식별자는 `SCR-{도메인}-{일련번호}` 형식이다. **본 문서의 SCR ID는 다른 산출물(요구사항정의서·DB설계서·review-guide·changelog)에서 강하게 인용되므로 ID는 절대 변경하지 않는다**. 정책상 제거된 SCR ID(SCR-VIDEO-002, SCR-DEIDENT-001/002, SCR-GEN-001/002, **V2: SCR-EXPORT-001**)는 본문에서 완전히 삭제하며 변경 이력은 `저작도구_FINAL_변경사항_2026-05-07.md` 및 `저작도구_FINAL_변경사항_2026-05-13.md`를 참조한다.

| 도메인 코드 | 의미 | 활성 화면 수 |
|:----------:|------|:----------:|
| VIDEO | 영상 처리 현황 | 2 (VIDEO-001, VIDEO-003) |
| DASH | 메인 대시보드 | 1 |
| PRESET | 프리셋 관리 | 2 |
| TASK | 작업 목록·배정 | 2 |
| LABEL | 라벨링 도구 | 2 |
| AUTO | 오토라벨링·시계열 메타 검토 | 2 |
| HIST | 히스토리·버전관리 | 2 |
| REVIEW | 검수 워크플로우 | 3 |
| STAT | 통계 | 2 |
| AUG | 데이터 증강 | 2 |
| EXPORT | (V2 제거) | 0 |
| PORTAL | 포털 외부 사용자 | 2 |
| MANAGE | 관리 (사용자/시스템) | 2 |
| **합계** | | **24** |

---

## 2. 공통 UI 규칙

### 2.1 레이아웃 기본 구조

mock의 `AppShell` (내부 사용자) / `PortalShell` (포털 사용자) 두 가지 셸로 분리된다.

#### 내부 사용자 (REVIEWER/WORKER) — `AppShell.tsx`

```
┌──────────────────────────────────────────────────────────────┐
│  GNB  fixed top-0 left-0 right-0  z-40  h-14                 │  ← 56px
│  ┌─ 로고/제목 (w-60) ─┐   spacer   ┌── 사용자 (아바타·이름) ┐
└──────────────────────────────────────────────────────────────┘
┌── LNB ──┐┌────────────── main ──────────────────────────────┐
│ fixed   ││  pl-60 pt-14   p-6  bg-gray-50                   │
│ top-14  ││                                                  │
│ left-0  ││  <Outlet />                                      │
│ bottom-0││                                                  │
│ w-60    ││                                                  │
│ z-30    ││                                                  │
└─────────┘└──────────────────────────────────────────────────┘
```

- GNB 높이 56px (`h-14`), 우상단 사용자 아바타 + 이름
- LNB 폭 240px (`w-60`), 그룹 단위 메뉴 — **접힘/토글 기능 없음** (항상 펼침)
- 본문 영역 `pl-60 pt-14`, 내부 패딩 `p-6`, 배경 `bg-gray-50`

> **시연용 mock 전용 요소(상용 빌드 제외)**: 현재 mock에는 GNB 우측에 `RoleSwitcher`(역할 강제 전환 셀렉터)와 로고 옆 `"목업"` 회색 뱃지가 함께 노출되지만, **이 둘은 운영 빌드에서 제거**된다. 운영 GNB의 우측 영역은 사용자 아바타·이름 + 사용자 메뉴(프로필/로그아웃)로 구성한다.

#### 포털 사용자 — `PortalShell.tsx`

```
┌──────────────────────────────────────────────────────────────┐
│  포털 GNB  h-14   md:px-6  로고(주황) ··· 사용자 (아바타·이름)│
└──────────────────────────────────────────────────────────────┘
[모바일에선 우측 햄버거 → 우측 드로어 (w-72)]
※ 포털 GNB도 운영 빌드에서는 시연용 RoleSwitcher / "목업" 뱃지가 제거된다 (§3.1 참조).
┌──────────────────────────────────────────────────────────────┐
│  main flex-1                                                 │
│  <Outlet />                                                  │
└──────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│  Footer  bg-white  border-t  text-center                     │
│  문의 / 개인정보처리방침 / 이용약관                          │
└──────────────────────────────────────────────────────────────┘
```

- 좌측 LNB 없음 — 포털은 단순 1뎁스 구조
- 모바일(`md:` 이하)에서 햄버거 → 우측 드로어 표시 (`w-72`, 백드롭 `bg-black/40`)
- 비-포털 역할이 `/portal/*` 진입 시 노란 경고 배너 + [포털 사용자로 전환] / [대시보드로] 버튼

### 2.2 색상 팔레트 (Tailwind 표준)

mock `tailwind.config.ts` 기준. **Material Design 색상값(prior 문서)은 모두 폐기**. Tailwind 기본 팔레트만 사용한다.

| 토큰 | HEX | 용도 |
|:----:|:---:|------|
| `primary-50` | `#EFF6FF` | hover 배경, active 메뉴 배경 |
| `primary-100` | `#DBEAFE` | 아바타 배경 |
| `primary-500` | `#3B82F6` | active 보더, 포커스 링 |
| `primary-600` | `#2563EB` | 버튼 primary, 링크, 강조 텍스트 |
| `primary-700` | `#1D4ED8` | 버튼 hover |
| `success` | `#10B981` | 성공 톤(완료 뱃지) |
| `warning` | `#F59E0B` | 경고 톤(처리 대기) |
| `danger` | `#EF4444` | 에러 톤(반려/실패) |
| `muted` | `#6B7280` | 보조 텍스트 |

부수 색상은 Tailwind 기본 팔레트 사용 (`gray-*`, `red-*`, `yellow-*`, `green-*`, `blue-*`, `purple-*`, `orange-*`, `cyan-*`, `rose-*`, `amber-*`).

포털 GNB는 `bg-orange-500` 로고 + `text-orange-700` 보조색을 사용한다.

### 2.3 타이포그래피

폰트 패밀리: `Pretendard` (fallback: `-apple-system`, `BlinkMacSystemFont`, `Segoe UI`, `Roboto`, `Helvetica Neue`, `Arial`, `sans-serif`)

| 위계 | Tailwind | px / weight | 용도 |
|:----:|:--------:|:-----------:|------|
| H1 | `text-xl font-bold` | 20px / 700 | 페이지 제목 |
| H2 | `text-lg font-semibold` | 18px / 600 | 섹션 제목 |
| H3 | `text-sm font-semibold` | 14px / 600 | 카드/박스 제목 |
| Body | `text-sm` | 14px / 400 | 본문 기본 |
| Small | `text-xs` | 12px / 400 | 메타·라벨·설명 |
| Caption | `text-[10px] font-semibold uppercase tracking-widest` | 10px / 600 | LNB 그룹 헤더 |

숫자(통계·페이지)는 `tabular-nums` 적용으로 자릿수 정렬한다.

### 2.4 공통 인터랙션

- **버튼**: `Button.tsx` 4-variant (`primary`, `secondary`, `danger`, `ghost`) × 3-size (`sm`, `md`, `lg`) — `transition-colors`, `focus:ring-2 focus:ring-primary-500 focus:ring-offset-1`
- **Modal**: `Modal.tsx` — 백드롭 `bg-black/50`, ESC 닫기, 첫 입력 자동 포커스, 5-size (`sm`/`md`/`lg`/`xl`/`2xl`)
- **Toast**: `useToast()` — bottom-right(`bottom-6 right-6`) 알림. 3-tone (`success`/`error`/`info`). 자동 사라짐
- **Skeleton**: `Skeleton.tsx` — 스피너 오버레이 사용 안 함. 카드/표/그리드 단위 placeholder만 사용
- **세션 만료 처리**: (미구현 — 후속 적용) mock은 세션 만료 감지 미구현. 실제 구현 시 401 응답 → 채널별 로그인 페이지 리다이렉트
- **알림 아이콘/패널**: GNB에 미존재 (mock 정책상 제거)

### 2.5 반응형 브레이크포인트

mock은 Tailwind 기본 브레이크포인트만 사용한다. **1280px 등 커스텀 BP는 사용하지 않는다**.

| BP | min-width | 적용 |
|:--:|:---------:|------|
| `sm` | 640px | 포털 GNB 라벨 표시 |
| `md` | 768px | 포털 데스크톱/모바일 분기, 그리드 2칼럼 |
| `lg` | 1024px | 그리드 2~4칼럼 확장 |
| `xl` | 1280px | KPI 4칼럼 |
| `2xl` | 1536px | (미사용) |

내부 셸(AppShell)은 데스크톱 우선 — `pl-60 pt-14` 고정. 모바일 대응은 포털만 정식 지원한다.

### 2.6 역할별 메뉴 노출 정책

`mock/src/routes/routes.ts`의 `roles[]` 배열이 정합 100% 진실이다. LNB는 `Lnb.tsx`에서 `!hideInMenu && !portal && r.roles.includes(currentRole)` 필터링.

| 메뉴 그룹 | 경로 | REVIEWER | WORKER | PORTAL_USER |
|:---------|:-----|:--------:|:------:|:-----------:|
| 대시보드 | `/dashboard` | O | O | × |
| 영상 | `/video/completed` | O | O | × |
| 작업 | `/task` | O | O | × |
| 작업 | `/review/pending` | O | × | × |
| 통계 | `/stat/worker` | O | O | × |
| 통계 | `/stat/overall` | O | × | × |
| 데이터 | `/augment/request` | O | × | × |
| 관리 | `/manage/users` | O | × | × |
| 관리 | `/manage/settings` | O | × | × |
| 관리 | `/preset` | O | × | × |
| 포털 | `/portal` | × | × | O (PortalShell) |

`hideInMenu: true` 라우트(상세/편집)는 LNB에 노출되지 않으나 권한이 있으면 직접 진입 가능.

> **V1.3 변경 메모**: 시스템 관리자(ADMIN) 역할은 폐지되었고 모든 권한이 REVIEWER에 통합되었다. 관리 화면 URL은 `/manage/*`로 변경되었다.

---

## 3. 공통 컴포넌트

### 3.1 GNB — `Gnb.tsx`

운영 빌드 기준 명세. mock(시연 빌드)에만 존재하는 요소는 별도 박스로 분리한다.

```
┌─ h-14 fixed bg-white border-b ──────────────────────────────────┐
│ [▣ Video] 학습데이터 저작도구                       [아바타·이름]│
└─────────────────────────────────────────────────────────────────┘
```

- **GNB 자체에는 메뉴 없음** (모든 네비게이션은 LNB)
- 좌측: 로고(`Video` 아이콘 + `bg-primary-600` 박스) + 제목 "학습데이터 저작도구"
- 우측: 사용자 이니셜 아바타(`bg-primary-100 text-primary-700`) + 이름 + 사용자 메뉴(프로필/로그아웃 — **(미구현 — 후속 적용)**)
- 로고 클릭 → 역할 분기 리다이렉트(`HomeRedirect`: PORTAL_USER → `/portal`, 그 외 → `/dashboard`)

> **시연용 mock 전용 요소 (운영 빌드에서 반드시 제거)**
>
> 현재 mock의 `Gnb.tsx`에는 다음 두 가지 시연 보조 요소가 추가되어 있지만, **상용 운영 빌드에서는 모두 제거**된다. 본 설계서의 GNB 명세는 운영 기준이며, mock은 시연 편의를 위해 임시 확장된 상태로 본다.
>
> | 시연 요소 | mock 코드 위치 | 운영 처리 |
> |:---------|:--------------|:---------|
> | `[목업]` 회색 뱃지 (`bg-gray-100 text-gray-400 rounded-full`) | `mock/src/layouts/Gnb.tsx:21~23` | 빌드 환경 분기로 제외 (예: `if (import.meta.env.MODE !== 'production')`) |
> | `RoleSwitcher` 셀렉터 (`bg-primary-50 border` 드롭다운으로 역할 강제 전환) | `mock/src/layouts/Gnb.tsx:31` + `mock/src/components/RoleSwitcher.tsx` | 운영 빌드에서 미렌더 — 실제로는 인증된 JWT 클레임의 `role`만 사용 |
>
> 운영 빌드의 우측 영역은 위 시연 요소 자리에 사용자 메뉴(프로필/로그아웃 드롭다운)가 들어간다. mock의 가시 동작을 그대로 베껴 구현해서는 안 된다.

### 3.2 LNB — `Lnb.tsx`

```
┌─ w-60 fixed top-14 left-0 bottom-0 bg-white border-r ─┐
│  ─ 대시보드 ───────────                                │
│   대시보드                                             │
│  ─ 영상 ───────────────                                │
│   영상 처리 현황                                       │
│  ─ 작업 ───────────────                                │
│   작업 목록                                            │
│   검수 목록  (REVIEWER만)                              │
│  ─ 통계 ───────────────                                │
│   작업자 통계                                          │
│   전체 구축 현황 (REVIEWER만)                          │
│  ─ 데이터 ─────────────  (REVIEWER만)                  │
│   증강 요청                                            │
│  ─ 관리 ───────────────  (REVIEWER만)                  │
│   사용자 관리                                          │
│   시스템 설정                                          │
│   프리셋 관리                                          │
└────────────────────────────────────────────────────────┘
```

- 그룹 헤더: `text-[10px] font-semibold text-gray-400 uppercase tracking-widest`
- 항목: `mx-2 px-3 py-2 rounded-md text-sm font-medium`
- active(`isActive=true`): `bg-primary-50 text-primary-700 border-l-2 border-primary-500 pl-[10px]`
- hover: `hover:bg-gray-50 hover:text-gray-900`
- **그룹 접힘 토글 없음** — 모든 그룹 항상 펼침

### 3.3 데이터 테이블 — `Table.tsx`

mock은 **공통 검색/필터/정렬 도구를 제공하지 않는다**. 각 페이지가 직접 검색/필터 UI를 구성하고 결과를 `Table`에 넘긴다.

- `Table<T>` Props: `columns: ColumnDef<T>[]`, `rows: T[]`, `rowKey: (row) => string`, `loading?: boolean`, `emptyMessage?: string`
- `ColumnDef<T>`: `{ key, header, render?, headerClassName?, cellClassName? }`
- 로딩 상태: `Skeleton` 행 그룹 표시 (각 페이지에서 직접 처리)
- 빈 상태: `emptyMessage` 표출 (예: `"완료된 영상이 없습니다."`)
- 페이징: `Pagination.tsx` 컴포넌트 (`page`, `totalPages`, `onChange`)

### 3.4 상태 뱃지

#### `StatusBadge` (작업/검수 상태)

| 상태 코드 | 라벨 | 톤 |
|:---------|:----|:--:|
| `BATCH_PROCESSING` | 배치 처리중 | warning |
| `BATCH_COMPLETED` | 배치 완료 | success |
| `PENDING` | 대기 | neutral |
| `IN_PROGRESS` | 진행중 | primary |
| `REVIEW_PENDING` | 검수 대기 | warning |
| `REVIEWING` / `REVIEW` / `IN_REVIEW` | 검수중 | primary |
| `COMPLETED` / `APPROVED` | 완료/승인 | success |
| `REJECTED` | 반려 | danger |

#### `EventTypeBadge` (이벤트 6종 — `mock/src/components/batch/EventTypeBadge.tsx` 매핑)

| 이벤트 키 | bg / text |
|:--------|:--------|
| `쓰러짐` | `bg-purple-100 text-purple-700` |
| `폭력` | `bg-red-100 text-red-700` |
| `교통사고` | `bg-blue-100 text-blue-700` |
| `이상행동(유괴)` | `bg-amber-100 text-amber-700` |
| `침수` | `bg-cyan-100 text-cyan-700` |
| `산불` | `bg-rose-100 text-rose-700` |
| (그 외) | `bg-gray-100 text-gray-600` (기본 fallback) |

- 사이즈: `sm` (`text-xs px-2 py-0.5`) / `md` (`text-sm px-2.5 py-1`)
- 모양: `inline-flex font-medium rounded-full`

### 3.5 폼 입력 공통 규칙

- 페이지별로 `<input>`, `<select>`, `<textarea>`, `<input type="checkbox">`, `<input type="radio">` 직접 사용
- 공통 클래스 패턴: `text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500`
- 필수 필드 표시(`*`)·인라인 에러 메시지·표준 라벨 컴포넌트는 **(표준화 미흡 — 후속 적용)**. 현재는 페이지별 ad-hoc 처리

---

## 4. 화면 설계

### 4-1. 세션 인계 · 진입

#### 진입 흐름 — `Home.tsx`

저작도구는 **독립 로그인 UI 없음**. 관제서버(내부) / 포털 서버(외부)가 발급한 JWT 토큰을 인계받아 `/` 진입 시 역할에 따라 분기한다.

```typescript
// mock/src/pages/Home.tsx (전문 발췌)
if (currentRole === 'PORTAL_USER') return <Navigate to="/portal" replace />;
return <Navigate to="/dashboard" replace />;
```

| 역할 | 진입 경로 |
|:----|:--------|
| `REVIEWER`, `WORKER` | `/dashboard` |
| `PORTAL_USER` | `/portal` |

- 두 채널 모두 동일 JWT 발급 서버 — 단일 검증 로직 (`JwtAuthenticationFilter` — 백엔드 책임)
- 토큰 `role` + `channel` 클레임으로 권한 분기
- 세션 만료 처리: **(미구현 — 후속 적용)**. 실제 구현 시 각 상위 시스템 로그인 페이지로 리다이렉트
- **운영 빌드의 GNB에는 RoleSwitcher가 존재하지 않는다** — 역할은 JWT 클레임에 의해서만 결정됨. mock의 `RoleSwitcher`는 시연 편의를 위한 임시 컨트롤로, 운영 코드에는 포함하지 않는다 (§3.1 GNB 시연 요소 박스 참조)

---

### 4-2. 영상 처리 현황 (메인 진입점)

#### SCR-VIDEO-001 · 영상 처리 현황 목록

배치 파이프라인 처리 결과(V2 — VLM → DEIDENTIFY → FRAME_EXTRACT → YOLO → SAM2 5단계)를 영상 단위로 모니터링한다. mock 구현 = `BatchCompletedList.tsx`.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ 영상 처리 현황                                              [↻ 새로고침]   │
│ 관제서버에서 인계받은 영상의 배치 처리 상태와 단계를 확인합니다.            │
│ ┌─ BatchFilters (form, flex-wrap) ───────────────────────────────────┐    │
│ │ [검색 CCTV명/영상ID 🔍] [상태▾] [이벤트▾] [날짜 from] [날짜 to]    │    │
│ │                                              [↻ 초기화] [필터 적용]│    │
│ └─────────────────────────────────────────────────────────────────────┘    │
│ ┌─ 액션바 (선택 ≥1 시) ───────────────────────────────────────────────┐    │
│ │ 선택 N건                                                           │    │
│ └─────────────────────────────────────────────────────────────────────┘    │
│ ┌─ Table ─────────────────────────────────────────────────────────────┐    │
│ │ ☐ │ CCTV명 │ 이벤트 │ 녹화일 │ 길이 │ 개인정보 │ 처리 단계 │ 액션 │    │
│ ├───┼────────┼────────┼────────┼──────┼──────────┼───────────┼──────┤    │
│ │ ☐ │ ...    │[Badge] │YY-MM-DD│ 02:30│ PRVC     │ [VLM ●]   │상세▶ │    │
│ └─────────────────────────────────────────────────────────────────────┘    │
│                                       [< 1 2 3 ... >]                       │
└────────────────────────────────────────────────────────────────────────────┘
```

**헤더 영역**
- 제목 `text-xl font-bold` "영상 처리 현황" + 부제 `text-xs text-gray-500` "관제서버에서 인계받은 영상의 배치 처리 상태와 단계를 확인합니다."
- 우측: `[↻ 새로고침]` (variant=secondary, size=sm) — `refetch()` 호출

**컬럼 8개** (`ColumnDef<VideoDto>[]` — `BatchCompletedList.tsx:141~228`)
| # | key | header | width | 셀 내용 |
|:-:|:----|:------|:-----:|:-------|
| 1 | `_check` | (빈 헤더, indeterminate 미사용) | 40px | 행 체크박스 (`accent-primary-600`, `aria-label="선택"`) |
| 2 | `cctvName` | CCTV명 | — | `font-medium text-gray-800 text-xs` |
| 3 | `eventType` | 이벤트 | — | `EventTypeBadge` |
| 4 | `recordedAt` | 녹화일 | — | `formatDate(YYYY-MM-DD)` `text-xs text-gray-500` |
| 5 | `durationSec` | 길이 | — | `formatDuration(durationSec)` `text-xs` |
| 6 | `privacyType` | 개인정보 | — | `Badge tone=privacyTone(t) size=sm`: PRVC→danger, PSDO→warning, ANONY→neutral |
| 7 | `_stage` | 처리 단계 | 120px | `currentStage(stages)` 산출, 모두 DONE이면 `[Badge success "완료"]`, 아니면 `[Badge tone=stageBadgeTone() {stageLabel}]` |
| 8 | `_actions` | 액션 | — | `[Button variant=ghost size=sm "상세▶"]` → `navigate('/video/:id')` |

**처리 단계 산출 — `currentStage(stages)` (BatchCompletedList.tsx:38~56)**
1. `PROGRESS` 단계가 있으면 그 단계
2. `FAIL` 단계가 있으면 그 단계
3. 모두 `DONE` → 마지막 단계
4. 시작 전 → 첫 단계 (`PENDING`)

**처리 단계 톤 — `stageBadgeTone(name, status)` (line 27~36)**
- `DONE` → `success`
- `FAIL` → `danger`
- `VLM` (모든 상태) → `purple` (V1.8 — 외부 시계열 메타 진입 단계 구분)
- `DEIDENTIFY` (모든 상태) → `rose` (V2 — 영상 단위 비식별 단계 구분)
- `DEIDENT_FAILED` → `danger` (V2 — 재시도 최대 횟수 초과)
- `PROGRESS` → `info`
- 그 외 → `neutral`

**액션바 (선택 ≥1 시)**
- `bg-primary-50 border border-primary-200 rounded-lg px-4 py-2.5 text-sm`
- "선택 N건" 텍스트만 표시 (대량 액션 버튼 없음 — 작업 배정은 SCR-TASK-001에서 일원화)

**필터 (`BatchFilters.tsx`)**
- 6개 입력: CCTV명/영상ID 검색(`q`), 상태(`status`), 이벤트(`eventType`), 날짜 from/to
- 상태 옵션: 전체 / 완료(`COMPLETED`) / 처리중(`PROCESSING`) / 대기(`PENDING`) / 실패(`FAILED`)
- 이벤트 옵션 6종: 쓰러짐 / 폭력 / 교통사고 / 이상행동(유괴) / 침수 / 산불
- `[↻ 초기화]` + `[필터 적용]` 버튼 (submit 시 onChange 트리거)
- 필터 변경 시 URL 동기화(`replace: true`) + 페이지 0으로 리셋 + 선택 해제

**페이징**
- 페이지 사이즈 기본 20 (`size: 20`, `fetchParams.page`)
- `Pagination` 컴포넌트 사용

**비즈니스 규칙**
- 배치 파이프라인 단계 (V2): `VLM → DEIDENTIFY → FRAME_EXTRACT → YOLO → SAM2` (5단계)
- 비식별 처리는 V2에서 자동 파이프라인 영상 단위 무조건 단계로 부활. `PRVC_TYPE_CD` 무관(ANONY 포함) 호출
- 라벨러 [비식별 누락 신고]가 발생한 영상은 처리 단계가 `DEIDENTIFY`로 되돌림됨 — 단계 셀에 재시도 카운트 배지 함께 노출 (예: `[Badge rose "DEIDENTIFY 재시도 2회"]`)
- 비식별 재시도 최대 횟수 초과 시 `DEIDENT_FAILED` 단계로 정지하며 검수자 별도 조치 필요
- 배치 실패 시 행은 표시되며 처리단계 셀이 FAIL 톤으로 노출

> **이전 SCR-VIDEO-002(상세 영상 정보)** 는 정책상 폐기되어 SCR-VIDEO-003 탭 구조로 통합되었다.

#### SCR-VIDEO-003 · 영상 상세 (탭 3개 구조)

mock 구현 = `VideoDetail.tsx`. 단일 화면이 아니라 **헤더 카드 + 탭 3개**로 구성된다.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ ← 뒤로가기                                                                  │
│ ┌─ 헤더 Card ──────────────────────────────────────────────────────────┐  │
│ │ [썸네일 128×80] {cctvName}                       [GitBranch 버전관리로 이동] │
│ │                 [EventTypeBadge] [StatusBadge]                        │  │
│ │                 길이: 02:30   녹화일: YYYY-MM-DD                       │  │
│ └────────────────────────────────────────────────────────────────────────┘ │
│ ┌─ Tabs ── [기본 정보] [프레임 미리보기] [오토라벨 결과] ─────────────┐  │
│ │ 활성 탭 컨텐츠                                                       │  │
│ └────────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────┘
```

**상단**
- `← 뒤로가기` 텍스트 버튼 (`text-sm text-gray-500`, `ArrowLeft` 아이콘) — `navigate(-1)`
- 헤더 Card (`Card actions=[버전관리로 이동]`):
  - 좌: 썸네일 `<img>` 128×80 (mock은 `picsum.photos/seed/video-${id}/320/180`)
  - 우: `h2 text-lg font-bold` 제목(`cctvName`) + 한 줄 메타(`EventTypeBadge size=md` + `StatusBadge size=md`) + 보조 메타(`길이: {formatDuration} / 녹화일: {YYYY-MM-DD}`)
  - 액션: `[Button secondary sm leftIcon=GitBranch "버전관리로 이동"]` → `navigate('/history/:id')`

**탭 3개** (`Tabs value={activeTab} onChange={setActiveTab}`)
| value | label | 컴포넌트 |
|:------|:------|:---------|
| `info` | 기본 정보 | `InfoTab` |
| `frames` | 프레임 미리보기 | `FramePreviewTab` |
| `autolabel` | 오토라벨 결과 | `AutoLabelTab` (`AutoLabelSummary` 내재) |

##### 탭 1 — 기본 정보 (`InfoTab`)

메타 그리드 8행 (`grid-cols-1 md:grid-cols-2 gap-3`, 각 셀 `bg-gray-50 rounded-lg px-4 py-3`):
1. **CCTV ID** = `video.id`
2. **해상도** = `"1920 × 1080"` (현재 mock 하드코딩)
3. **길이** = `formatDuration(durationSec)`
4. **녹화 시각** = `formatDate(recordedAt)`
5. **처리 단계** = `<BatchStageIndicator stages={video.stages} />` (overflow-x-auto 래퍼)
6. **개인정보 분류** = `privacyTypeLabel(privacyType)` (가명/익명/개인 등)
7. **생성일** = `formatDate(createdAt)`
8. **수정일** = `formatDate(updatedAt)`

##### 탭 2 — 프레임 미리보기 (`FramePreviewTab`)

- 그리드 `grid-cols-3 sm:grid-cols-6 gap-2` 프레임 썸네일
- 각 썸네일: `<img loading="lazy" w=160 h=90>` + 우상단 `hasIssue` 빨간 점(`w-2 h-2 rounded-full bg-red-500`) + 하단 프레임 번호 오버레이(`bg-black/50 text-white text-xs`)
- 썸네일 클릭 → **Lightbox Modal** (`size="xl"`)
  - 큰 이미지(`max-h-80 object-contain`, 640×360)
  - 프레임 번호 / 타임스탬프(ms) / 이슈 있음 텍스트
  - 푸터: `[닫기] [라벨링 편집]` → `navigate('/label/${videoId}?frame=${frameNo}')`
- 로딩: `aspect-video bg-gray-200 animate-pulse` skeleton 12개
- 빈 상태: `"프레임 데이터가 없습니다."`

##### 탭 3 — 오토라벨 결과 (`AutoLabelTab`)

- `useFetch<FrameLabels>('/videos/{id}/frames/0/labels')` — frame 0 라벨 사용 (집계 표현)
- `<AutoLabelSummary video={video} labels={frameLabels?.objects ?? []} />` 내재 (SCR-AUTO-001 본문 참조)
- 로딩: 16rem 높이 skeleton 3개

**에러/로딩 상태**
- 영상 로딩: 제목 + 본문 skeleton 2단
- 영상 없음(`error || !video`): `"영상을 찾을 수 없습니다."` + `[뒤로가기]`

**액션**
- `[작업 배정]` (REVIEWER만) → `AssignModal` 단건 모드 호출

---

### 4-3. 메인 대시보드

#### SCR-DASH-001 · 메인 대시보드

mock 구현 = `Dashboard.tsx`. 역할에 따라 KPI 개수와 하단 카드 구성이 달라진다.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ 대시보드                              [🕒 YYYY-MM-DD HH:mm:ss] [↻ 새로고침] │
│                                                                            │
│ ┌─ KPI grid-cols-2 xl:grid-cols-{4|3} ────────────────────────────────┐  │
│ │ [처리 대기]  [처리 완료]  [내 작업*]  [반려 건수]                    │  │
│ │  warning      success      primary      danger                       │  │
│ │ * WORKER만                                                            │  │
│ └─────────────────────────────────────────────────────────────────────┘    │
│                                                                            │
│ ┌─ 데이터 개수 grid-cols-1 lg:grid-cols-2 ────────────────────────────┐  │
│ │ ┌ Card "이미지 데이터 개수" ┐  ┌ Card "영상 데이터 개수" ──┐         │  │
│ │ │ {imageCompleted}장 (크게)│  │ {videoCompleted}건 (크게) │         │  │
│ │ │ ─── 이벤트별 ────────    │  │ ─── 이벤트별 ──────────   │         │  │
│ │ │ 쓰러짐  N  (2칼럼)       │  │ 쓰러짐  N  (2칼럼)        │         │  │
│ │ │ 폭력    N                │  │ 폭력    N                 │         │  │
│ │ └──────────────────────────┘  └───────────────────────────┘         │  │
│ └─────────────────────────────────────────────────────────────────────┘    │
│                                                                            │
│ ┌─ 하단 grid-cols-1 xl:grid-cols-2 (WORKER만 2칼럼) ──────────────────┐  │
│ │ ┌ Card "최근 완료 영상" ──┐  ┌ Card "내 작업 현황" (WORKER만) ──┐  │  │
│ │ │ Table 4컬럼             │  │ Table 3컬럼                       │  │  │
│ │ │ CCTV명/이벤트/길이/완료일│ 영상/상태/진행률(ProgressBar)       │  │  │
│ │ └─────────────────────────┘  └───────────────────────────────────┘  │  │
│ └─────────────────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────────────────┘
```

**헤더 영역**
- 제목 `text-xl font-bold` "대시보드"
- 우측: `NowClock` 컴포넌트(`Clock` 아이콘 + `dayjs YYYY-MM-DD HH:mm:ss`, 1초 setInterval) + `[Button secondary sm leftIcon=RefreshCw "새로고침"]` → `refetchVideos()`

**KPI 카드 구성** (`grid-cols-2 xl:grid-cols-{4|3} gap-4`)
| 역할 | 카드 (`StatCard`) |
|:----|:----|
| `WORKER` | 처리 대기(`warning`) · 처리 완료(`success`) · **내 작업**(`primary`) · 반려 건수(`danger`) — **4개** |
| `REVIEWER` | 처리 대기 · 처리 완료 · 반려 건수 — **3개** (`내 작업` 제외) |

- KPI 산출: `computeKpiStats(videosPage.content, currentUser.id, sessionRole)` (`pages/dashboard/useKpiStats.ts`)
- 데이터 소스: `useFetch<Page<VideoDto>>('/videos', { size: 200, page: 0 })` — 한 번에 200건 가져와 클라이언트 집계
- `delta` (전일 대비 증감): `randomDelta(seed)` 시연용 mock — 실제 구현 시 일별 집계 차이로 대체
- 로딩 중: `Skeleton height="6rem"` 4개 또는 3개

**데이터 개수 카드** (`grid-cols-1 lg:grid-cols-2 gap-4`)
- 제목 Card 2개: "이미지 데이터 개수" / "영상 데이터 개수"
- 본문: 큰 숫자 `text-2xl font-semibold text-primary-600` + 구분선 + 이벤트별 분포 `grid-cols-2 gap-x-4 gap-y-1.5` (`text-gray-500` 라벨 / `text-gray-800 tabular-nums` 카운트)
- 데이터 소스: `useFetch<OverallStat>('/stats/overall')` — `imageByEvent` / `videoByEvent` 6종

**하단 섹션** (`grid-cols-1 xl:grid-cols-2 gap-4`, WORKER만 2칼럼)
- **최근 완료 영상** (모두 역할): `useFetch<Page<VideoDto>>('/videos', { status: 'COMPLETED', size: 5, page: 0 })` — 4컬럼(CCTV명 / 이벤트(EventTypeBadge) / 길이(`formatDuration`) / 완료일(`formatDate MM-DD HH:mm`))
- **내 작업 현황** (WORKER만): `useFetch<Page<TaskDto>>('/tasks', { assigneeId: currentUser.id, size: 5, page: 0 })` — 3컬럼(영상명 / 상태(`StatusBadge`) / 진행률(`ProgressBar size=sm` + `{progress}%`))
- 둘 다 `Table` 컴포넌트 사용, `emptyMessage="완료된 영상이 없습니다." / "작업이 없습니다."`

> **공지사항 섹션은 mock에 없음 — 설계서에서 제거**. 향후 운영 요구가 발생하면 신규 SCR로 정의 후 추가.

---

### 4-4. 프리셋 관리

#### SCR-PRESET-001 · 프리셋 목록

mock 구현 = `PresetList.tsx`. 라벨링에 자주 쓰이는 라벨 코드 묶음(프리셋)을 관리한다.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ [▣ Layers (orange)] 프리셋 관리                              [+ 새 프리셋] │
│                       전체 N개                                              │
│                                                                            │
│ ┌─ 카드 그리드 grid-cols-1 md:grid-cols-2 gap-4 ──────────────────────┐  │
│ │ ┌ Card (border, p-5) ──────────────────────────────────────────────┐ │  │
│ │ │ {name}                                          [✎ 수정] [🗑 삭제] │ │  │
│ │ │ {description (truncate)}                                          │ │  │
│ │ │ [Badge code1] [Badge code2] ... [Badge "N개" neutral]             │ │  │
│ │ │ ─────────────────────────────────────────                         │ │  │
│ │ │ 생성: YYYY-MM-DD                      수정: YYYY-MM-DD           │ │  │
│ │ └───────────────────────────────────────────────────────────────────┘ │  │
│ └─────────────────────────────────────────────────────────────────────┘    │
│                                       [< 1 2 3 ... >]                       │
└────────────────────────────────────────────────────────────────────────────┘
```

**헤더 영역**
- 좌: `Layers` 아이콘(`w-9 h-9 rounded-lg bg-orange-50`, `text-orange-600`) + `h1 text-xl font-bold` "프리셋 관리" + `text-xs text-gray-400` "전체 {totalElements}개"
- 우: `[Button primary md leftIcon=Plus "새 프리셋"]` → `openCreate()`

**카드 본문** (`bg-white border border-gray-200 rounded-lg p-5 space-y-3 hover:border-primary-300`)
1. 상단 row: 이름(`font-semibold text-sm`) + 설명(`text-xs text-gray-400 truncate`) | `[ghost sm leftIcon=Pencil "수정"] [ghost sm leftIcon=Trash2 "삭제" text-red-500]`
2. 라벨 코드 row: `Badge tone=info size=sm` 각 코드 + 마지막에 `Badge tone=neutral size=sm "{N}개"`
3. 하단 row(`border-t border-gray-100 pt-2 text-xs text-gray-400`): `"생성: {createdAt ko-KR}" | "수정: {updatedAt ko-KR}"`

**도메인 모델 (mock 단순화 — `mock/src/api/types.ts:188~195`)**
```typescript
PresetDto {
  id: string
  name: string
  description: string
  labelCodes: string[]   // 라벨 코드 단순 배열 (예: ['PERSON', 'VEHICLE'])
  createdAt: string
  updatedAt: string
}
```

> **이전 풀모델(이벤트 유형/서브유형/형태/색상/속성)은 모두 제거**. mock은 라벨 코드 배열만 관리. 향후 도메인이 성장하면 별도 단계에서 확장.

**상태별 UI**
- 로딩: `Skeleton height="10rem"` 카드 4개
- 빈 상태: `bg-white border rounded-lg p-12 text-center` + `Layers 40` 아이콘 + "프리셋이 없습니다." + 보조 텍스트 + `[Button primary md leftIcon=Plus "새 프리셋 만들기"]`
- 정상: 카드 그리드 + `Pagination`

**페이징**
- `PAGE_SIZE = 10` (`/presets?page={page}&size=10`)

**삭제 흐름**
- 카드 `[삭제]` 클릭 → `Modal size=sm` 열림
  - 제목: "프리셋 삭제"
  - 본문: `<span font-semibold>"{name}"</span> 프리셋을 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.`
  - 푸터: `[secondary "취소"] [danger "삭제" loading]`
- 확정 → `DELETE /presets/{id}` → toast `info`: `"{name} 프리셋이 삭제되었습니다."` → `refetch()`

#### SCR-PRESET-002 · 프리셋 생성/수정 모달

mock 구현 = `components/preset/PresetFormModal.tsx`. SCR-PRESET-001 카드의 `[수정]` 또는 `[+ 새 프리셋]`에서 호출. `Modal size="lg"`.

```
┌─ Modal size=lg ────────────────────────────────────────────────┐
│ 새 프리셋 만들기 | 프리셋 수정                           ×       │
├───────────────────────────────────────────────────────────────┤
│ 프리셋 이름 *                                                  │
│ [예: 교통사고 표준 프리셋]                                     │
│ (에러 시: text-xs text-red-500 안내)                          │
│                                                                │
│ 설명                                                           │
│ [textarea rows=2: 프리셋에 대한 설명을 입력하세요.]            │
│                                                                │
│ 라벨 항목 * (N개)                                              │
│ [PERSON ×][VEHICLE ×][... 칩]                                  │
│                                                                │
│ [라벨 코드 입력 (Enter로 추가)] [+ 추가]                       │
│                                                                │
│ 빠른 추가:                                                     │
│ [+ PERSON (사람)] [+ VEHICLE (차량)] [+ BICYCLE (자전거)] ...  │
├───────────────────────────────────────────────────────────────┤
│                                          [취소] [저장|만들기]  │
└───────────────────────────────────────────────────────────────┘
```

**필드**
1. **프리셋 이름** (*required, `id=preset-name`) — `input type=text`. 비어있으면 에러 `"프리셋 이름을 입력하세요."`. 에러 시 `border-red-400`
2. **설명** (optional, `id=preset-desc`) — `textarea rows=2`
3. **라벨 항목** (*required, `≥1`) — 칩 입력
   - 기존 칩: `bg-primary-100 text-primary-700 rounded-full text-xs` + `Trash2 11` 삭제 버튼 (`aria-label="{code} 삭제"`)
   - 추가 입력: input + `Enter` 키 또는 `[secondary sm leftIcon=Plus "추가"]` — 대문자 자동 변환(`.toUpperCase()`), 중복 차단
   - **빠른 추가 suggestions 10종** (`PRESET_LABEL_SUGGESTIONS`): PERSON(사람) / VEHICLE(차량) / BICYCLE(자전거) / MOTORCYCLE(오토바이) / TRUCK(트럭) / BUS(버스) / FIRE(화재) / SMOKE(연기) / WATER(침수물) / FALLEN(쓰러진 사람) — 이미 추가된 코드는 노출에서 제외

**액션**
- `[secondary md "취소"]` — `saving` 중 disabled
- `[primary md loading=saving "저장|만들기"]` — 생성 모드는 "만들기", 수정 모드는 "저장"

**검증 / 응답**
- 라벨 0개일 때 에러: `"라벨 항목을 하나 이상 추가하세요."`
- 성공 toast: `"프리셋이 생성되었습니다."` (생성) / `"프리셋이 수정되었습니다."` (수정) → `refetch()`
- API: `POST /presets` 또는 `PUT /presets/{id}`

---

### 4-5. 작업 목록 · 배정

#### SCR-TASK-001 · 작업 목록

mock 구현 = `TaskList.tsx`. **영상 기반 데이터 모델** — left-join task로 표시한다 (`TaskRow = { video, task | undefined }`).

```
┌────────────────────────────────────────────────────────────────────────────┐
│ 작업 목록  [현재 역할: 검수자]  처리 완료된 영상만 표시       [↻ 새로고침] │
│ ┌─ TaskFilters ────────────────────────────────────────────────────────┐  │
│ │ [검색 q] [상태▾ (UNASSIGNED 포함)] [이벤트▾] [작업자▾ (REVIEWER만)]  │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
│ ┌─ KPI grid-cols-2 sm:grid-cols-4 ─────────────────────────────────────┐  │
│ │ [전체 작업 primary] [진행중 success] [검수대기 warning] [반려 danger] │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
│ ┌─ 액션바 (REVIEWER + 선택 ≥1) ────────────────────────────────────────┐  │
│ │ [Badge N개 선택됨] [선택 해제]              [Users {N}개 일괄 배정]  │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
│ ┌─ Table 헤더 ─────────────────────────────────────────────────────────┐  │
│ │ ☐ 현재 페이지 전체 선택 (indeterminate)        전체 N건 (X/Y 페이지) │  │
│ ├──────────────────────────────────────────────────────────────────────┤  │
│ │ ☐ │ 영상명·videoId │ 이벤트 │ 상태 │ 작업자 │ 검수자 │ 진행률 │ 액션 │  │
│ ├───┼────────────────┼────────┼──────┼────────┼────────┼────────┼──────┤  │
│ │ ☐ │ ...            │[Badge] │[칩]  │ 김작업 │ 이검수  │ ███N% │[배정]│  │
│ └──────────────────────────────────────────────────────────────────────┘  │
│                                       [< 1 2 3 ... >]                       │
└────────────────────────────────────────────────────────────────────────────┘
```

**헤더 영역**
- 제목 `text-xl font-bold` "작업 목록" + `Badge tone=info size=sm "현재 역할: {ROLE_LABEL}"` + 보조 텍스트 `text-xs text-gray-500` "처리 완료된 영상만 표시"
- 우측: `[Button secondary sm leftIcon=RefreshCw "새로고침"]` → `refetch()`

**데이터 모델 — 영상 기반 left-join (TaskList.tsx:67~78, 184~210)**
```typescript
TaskRow {
  id: string          // = video.id
  video: VideoDto
  task: TaskDto | undefined
  rowStatus: 'UNASSIGNED' | 'PENDING' | 'IN_PROGRESS' | 'REVIEW_PENDING' | 'COMPLETED' | 'REJECTED'
  videoName: string   // task.videoName ?? video.cctvName
}
```
- base = `isVideoFullyProcessed(video)` 만족(모든 stage가 DONE)하는 영상 전체
- task 매핑 = `taskByVideoId.get(v.id)` (영상당 첫 번째 task)
- `task` 없는 영상은 `rowStatus='UNASSIGNED'`로 표시

**상태 코드 / 라벨 / 톤 (`STATUS_LABEL` / `STATUS_TONE`)**
| 코드 | 라벨 | 톤 |
|:----|:----|:--:|
| `UNASSIGNED` | 미배정 | neutral |
| `PENDING` | 대기 | neutral |
| `IN_PROGRESS` | 진행중 | info |
| `REVIEW_PENDING` | 검수대기 | warning |
| `COMPLETED` | 완료 | success |
| `REJECTED` | 반려 | danger |

**역할 가드 (TaskList.tsx:215~224)**
- `WORKER` — `task.assigneeId === currentUser.id`인 행만
- `REVIEWER` — `task 없거나 / task.reviewerId === currentUser.id / 또는 task에 assigneeId 없는` 행

**KPI 카드 4개** (`grid-cols-2 sm:grid-cols-4 gap-4`)
| 카드 | 톤 | 아이콘 | 값 |
|:----|:--:|:------:|:--|
| 전체 작업 | primary | `ListTodo` | visibleRows.length |
| 진행중 | success | `Play` | IN_PROGRESS 카운트 |
| 검수대기 | warning | `Flame` | REVIEW_PENDING 카운트 |
| 반려 | danger | `ArrowDown` | REJECTED 카운트 |

**필터 (`TaskFilters`)**
- `q` 검색 (영상명 / 작업자명)
- `status` (UNASSIGNED 포함 6개)
- `eventType` (영상의 이벤트 유형 unique 정렬)
- `assigneeId` — **REVIEWER만 노출** (`showAssigneeSelect`)
- 모든 변경 시 URL 동기화 + `page` 0 + 선택 해제

**다중 선택 액션바 (REVIEWER + 선택 ≥1)**
- 좌: `Badge tone=info "N개 선택됨"` + 텍스트 링크 "선택 해제"
- 우: `[Button primary sm leftIcon=Users "{N}개 일괄 배정"]` → `openBulkAssign()`

**Table 헤더 영역** (`bg-gray-50 px-4 py-2`)
- 좌: REVIEWER + 행 있을 때 → "현재 페이지 전체 선택" 체크박스 (indeterminate 지원)
- 우: `"전체 {visibleRows.length}건 ({page+1}/{totalPages} 페이지)"`

**컬럼 (REVIEWER 8개 / WORKER 7개 — 체크박스 제외)**
| # | key | header | width | 셀 |
|:-:|:----|:------|:-----:|:--|
| 1 | `_select` (REVIEWER만) | (빈) | 40px | 행 체크박스 (`aria-label="{영상명} 선택"`) |
| 2 | `videoName` | 영상명 | min-w 160 | `font-medium text-sm truncate` + 보조 `text-xs text-gray-400` video.id |
| 3 | `eventType` | 이벤트 | — | `EventTypeBadge` 또는 `-` |
| 4 | `status` | 상태 | — | `Badge tone=STATUS_TONE size=sm` |
| 5 | `assigneeName` | 작업자 | — | task.assigneeName 또는 `"미배정"` (italic) |
| 6 | `reviewerId` | 검수자 | — | `reviewerMap[reviewerId]` 또는 `"미등록"` (italic) |
| 7 | `progress` | 진행률 | 100px | `ProgressBar size=sm` + `text-xs tabular-nums {N}%` (task 없으면 `-`) |
| 8 | `_actions` | 액션 | — | (아래 동적 액션) |

**행 액션 (동적)** — `_actions` 컬럼 (TaskList.tsx:483~533)
| 버튼 | 표시 조건 | 아이콘 | 동작 |
|:----|:--------|:------|:----|
| `[ghost sm "작업"]` | WORKER + task 있음 | `Play` | alert "라벨링 화면으로 이동 ({task.id})" (Phase 6 구현 예정) |
| `[secondary sm "배정"]` | REVIEWER + (task 없음 OR `task.status === 'PENDING'`) | `UserPlus` | `openAssign(row, 'assign')` |
| `[secondary sm "재배정"]` | REVIEWER + `task.assigneeId` 있음 | `RefreshCw` | `openAssign(row, 'reassign')` |
| `[ghost sm "이력"]` | task 있음 (모든 역할) | `History` | `openHistory(task)` — `AssignmentHistory` 사이드 패널 |

**페이징 (클라이언트)**
- `PAGE_SIZE = 20`. 서버 호출 `/tasks?size=999`로 한 번에 받아 FE에서 페이징·필터
- 빈 상태: `"처리 완료된 영상이 없습니다."`

**부속 모달·패널**
- **AssignModal** (SCR-TASK-002): `open` / `task: TaskDto | null` / `mode: 'assign' | 'reassign' | 'bulk'` / `videoIds: string[]` (bulk 모드) / `videoNameById: Record<string,string>`
- **AssignmentHistory** 사이드 패널: `task` / `open` — 배정 이력 시각화

**선택 토글 / Optimistic 갱신**
- 행 체크 토글 `toggleRow(videoId)` / 헤더 전체 선택 `toggleAllPaged()` (현재 페이지 기준)
- 단일 배정 성공 시 `localTasks` upsert / 일괄 배정 성공 시 `{ assigned + skipped }` 합산해 toast `"{N}건 일괄 배정 완료"` + 선택 해제

#### SCR-TASK-002 · 배정 모달

mock 구현 = `components/task/AssignModal.tsx`. 모드 3종 — `assign` / `reassign` / `bulk`. 진입 경로: 행 `[배정]` `[재배정]` 또는 액션바 `[N개 일괄 배정]`.

```
┌─ Modal ─────────────────────────────────────────────────────────────┐
│  작업 배정 — {영상명}                       (assign 모드)            │
│  작업 재배정 — {영상명}                     (reassign 모드)          │
│  일괄 배정 — N개 영상                       (bulk 모드)              │
├─────────────────────────────────────────────────────────────────────┤
│  대상 영상 요약 (bulk: 앞 3개 + "외 N건")                            │
│                                                                      │
│  작업자 *  [▾ 현재 사용자 자동 선택]                                 │
│  검수자    [REVIEWER 자동 지정 — readonly]                          │
│                                                                      │
│  (멱등 처리 안내) — 동일 배정이면 skip 처리                          │
├─────────────────────────────────────────────────────────────────────┤
│                                            [취소] [저장|배정]       │
└─────────────────────────────────────────────────────────────────────┘
```

**기본값 정책 (mode별)**
- `bulk`: 작업자 = `currentUser.id` 자동 선택 (작업자가 검수자 본인이어도 시연 편의로 자동 채움)
- `assign` (단건): `task.assigneeId` 유지, 비어있으면 `currentUser.id`
- `reassign` (단건): `task.assigneeId` 유지 (사용자가 변경)
- 모든 모드에서 검수자 = 모달을 연 REVIEWER 본인 자동 지정

**API**
- 단건도 `POST /api/v1/tasks/bulk-assign` 1건 호출로 통합 (V1.x 후속 — 단일 호출 경로 일원화)
- 응답 `BulkAssignResponse`: `{ assigned: number, skipped: number, total: number, tasks: TaskDto[] }`
- 멱등: 같은 `assigneeId`로 다시 호출하면 `skipped++`, task는 변경 없음

**toast**
- bulk 성공: `"{assigned + skipped}건 일괄 배정 완료"`
- 단건 성공: 호출 측에서 toast 처리 (또는 `onSuccess` 콜백)

**`mode='bulk'` 단일 영상 케이스**
- 미배정 영상 행에서 `[배정]` 클릭 시 task가 없으므로 **bulk 모드(1건)로 진입** → 신규 task 생성 흐름과 합류 (`POST /tasks/bulk-assign`)

**부속 필드**
- `assigneeName?` — 응답 매핑용
- `reviewerId?` — REVIEWER 본인 ID (서버 멱등 검증)

---

### 4-6. 라벨링 도구

#### SCR-LABEL-001 · 라벨링 도구 메인

mock 구현 = `LabelEditor.tsx`. 영상 프레임 단위 라벨링 환경. **portalMode prop으로 포털 라벨링도 동일 컴포넌트 재사용**.

**전체 레이아웃 — fixed inset-0 z-50 dark theme**

라벨링은 GNB/LNB 위에 덮이는 **전체 화면 오버레이**다 (`fixed inset-0 bg-gray-900 z-50`). 다크 테마(`bg-gray-900` / `bg-gray-800` 패널).

```
┌─ Top bar (h=56px, bg-gray-800) ─────────────────────────────────────────────┐
│ [×]  {영상명}                  Frame N/M             N개 객체  [GitBranch  │
│      {eventType}              ● 편집중 / ✓ 저장됨              히스토리]    │
│                                                                  [Save 저장]│
│ (portalMode일 때 좌측 "포털 — 라벨링" 주황 / 우측 [Bot 오토라벨링 실행])    │
└─────────────────────────────────────────────────────────────────────────────┘
┌─ Toolbar (좌, 약 56px) ┐┌─ LabelCanvas (flex-1) ────┐┌─ 우측 패널 (w-72) ─┐
│ [B] 박스                ││                            ││ ─ 객체 목록 ──     │
│ [P] 폴리곤              ││   konva.js Stage           ││  ObjectTree        │
│ [S] 세그멘테이션         ││                            ││ ─ 속성 ─────       │
│ [T] SAM2 트랙           ││  bbox / polygon / mask     ││  AttributePanel    │
│ [E] 편집                ││                            ││                    │
│ [Del] 삭제               ││                            ││                    │
│ [+/-] 줌                ││                            ││                    │
└─────────────────────────┘└────────────────────────────┘└────────────────────┘
┌─ Bottom bar (h=120px = 60 strip + 40 slider, border-t border-gray-700) ─────┐
│ FrameStrip (60): 썸네일 스트립 (수평 스크롤, 현재 프레임 강조)              │
│ FrameSlider (40): [◀] [────●────] [▶]                                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Top bar 구성** (`bg-gray-800 border-b border-gray-700 px-4 h-14`)
- 좌:
  - `[× 닫기]` 버튼 (`X 18` 아이콘, `text-gray-400 hover:text-white`) → `navigate(-1)`
  - `text-sm font-semibold text-white truncate` 영상명 (portalMode면 `text-orange-300 "포털 — 라벨링"`)
  - `text-xs text-gray-400` `eventType`
- 중앙:
  - 일반 모드: `"Frame {currentFrame + 1} / {totalFrames}"`
  - 포털 모드: `"라벨링 진행: {objectCount} / {totalFrames} 프레임"`
  - 아래 상태 라벨: `dirty ? 'text-yellow-400 ● 편집 중' : 'text-green-400 ✓ 저장됨'`
- 우:
  - `text-xs text-gray-400` `{objectCount}개 객체`
  - **일반 모드**: `[GitBranch 히스토리]` (`border border-gray-600`) → `navigate('/history/:videoId')`
  - **포털 모드**: 오토라벨링 실행 중이면 `ProgressBar size=sm tone=primary` w-32 표시 + `[Bot 오토라벨링 실행]` (`bg-orange-600`) — `autoLabelRunning` 동안 disabled
  - `[Save 저장]` (`bg-blue-600`) → `handleSave()`

**저장 시퀀스** (`handleSave`)
1. `PUT /api/v1/videos/{videoId}/frames/{currentFrame}/labels` — `{ objects: LabelObject[] }`
2. `markClean()` (store dirty 플래그 해제)
3. (try) `POST /api/v1/history/videos/{videoId}/commit` — `{ frame, objectsCount }` (Gitea 자동 커밋). 실패해도 저장은 성공으로 처리
4. toast: 커밋 성공 시 `"저장됨 · 버전 기록됨"` / 커밋 실패 시 `"저장됨"` / PUT 실패 시 `"저장 실패"` (error 톤)

> 저장 자체는 portalMode 분기 없이 동일. portalMode 분기는 **UI 노출 여부**(히스토리/오토라벨링 버튼)에서만 발생.

**오토라벨링 (포털 전용)** — `handleAutoLabel()`
- 5초간 progress 시뮬 (100ms 간격, 최대 99%)
- `POST /api/v1/portal/auto-label/{videoId}` 호출 → 응답 `{ videoId, objects: LabelObject[] }`
- 응답 객체들을 store에 `addObject()` 추가
- toast: `"오토라벨링 완료: {N}개 객체 추가"` (success) 또는 `"오토라벨링 실패"` (error)
- 종료 후 0.8초 뒤 progress 리셋

**Toolbar (`components/label/Toolbar.tsx`)** — 좌측 도구 패널
- 단축키: `B` 박스 / `P` 폴리곤 / `S` 세그멘테이션(마스크) / `T` SAM2 트랙 / `E` 편집 / `Del` 삭제 / `+` `-` 줌
- 프레임 이동: `←` / `→`
- 저장: `Ctrl+S`
- 되돌리기: `Ctrl+Z`
- 단축키 hook: `useLabelShortcuts({ onSave: handleSave })`

**우측 패널 (w-72, bg-gray-800)**
- 상단 절반: `─ 객체 목록 ─` 라벨 + `ObjectTree`
- 하단 절반: `─ 속성 ─` 라벨 + `AttributePanel`
- 각 라벨 row: `px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide border-b border-gray-700`

**LabelCanvas** (konva.js 기반)
- 현재 frame의 객체 배열을 받아 bbox / polygon / mask 렌더링
- 객체 선택 → `AttributePanel`에 동기화
- 신규 객체 그리기 → store `addObject()`

**상태 / store (`useLabelStore`)**
- `setVideo(videoId, durationSec=60)` — 영상 로드 후 초기화
- `setFrame(n)` / `currentFrame` / `totalFrames`
- `frames: Record<number, LabelObject[]>` / `setFrameLabels(frame, objects)` / `addObject(obj)`
- `dirty` / `markClean()`
- URL `?frame=N` 파라미터로 초기 프레임 지정 (예: VideoDetail 라이트박스에서 진입)

**프레임 lazy 로딩**
- `currentFrame` 변경 시 `loadedFramesRef`에 없으면 `GET /api/v1/videos/{id}/frames/{n}/labels`로 fetch → store 갱신
- 같은 프레임 재방문 시 캐시 활용

**로딩 / 에러 상태**
- 로딩: `fixed inset-0 bg-gray-900`에 스피너 + `"영상 정보 로드 중..."` (다크 풀스크린)
- 에러: 풀스크린 다크 배경 + `"영상을 찾을 수 없습니다"` + ID 표시 + `[뒤로 가기]` 버튼

**portalMode 분기 (`PortalLabelEditor`에서 `<LabelEditor portalMode />` prop 주입)**
- Top bar 좌측 라벨이 `"포털 — 라벨링"` (`text-orange-300`)으로 변경
- 우측 버튼: `[히스토리]` 대신 `[Bot 오토라벨링 실행]` (`bg-orange-600`)
- 검수 제출 버튼 없음 (포털은 검수 흐름 없음)
- 객체 카운트는 `objectCount / totalFrames` 형태로 표시

**V2 — 영상 표시 정책**
- 라벨러(WORKER) 화면: **비식별 프레임만 표시**. 원본 프레임은 접근 불가
- 검수자(REVIEWER) 화면 (SCR-REVIEW-002 등): 캔버스 상단에 `[원본 보기] / [비식별 보기]` 토글 버튼 (`bg-gray-700 text-white border border-gray-600`). 기본은 비식별 보기. 검수 품질 확인용으로만 원본 보기 가능
- 라벨링 좌표(bbox/polygon/segment)는 원본 기준 1벌이며, 두 영상 모두에서 동일 위치에 오버레이됨

**V2 — [비식별 누락 신고] 버튼 (WORKER/REVIEWER 공통)**

라벨러가 비식별 프레임에서 개인정보 잔존(얼굴·번호판 등)을 발견할 때 신고하여 비식별 재시도를 트리거할 수 있다.

- **위치**: 우측 패널 상단 (`─ 객체 목록 ─` 라벨 위)
- **버튼**: `[🚨 비식별 누락 신고]` (`bg-red-600 hover:bg-red-700 text-white font-semibold px-3 py-2 rounded-md w-full mb-2`)
- **클릭 시 모달**:
  - 누락 위치 마킹 옵션 (캔버스 클릭으로 좌표 입력, 선택)
  - 누락 유형 라디오: `얼굴` / `번호판` / `기타` (`MISSING_TYPE_CD`)
  - 코멘트 textarea (자유 입력, max 2000자)
  - `[취소]` `[신고]` 버튼
- **저장 시 동작**:
  - 토스트 안내: `"영상이 비식별 재시도 큐에 적재되었습니다. 재시도 완료 시 알림이 전송됩니다."` (`info` 톤, 5초)
  - 영상이 LOCKED 상태로 전환되어 캔버스 편집 차단 + 상단 안내 배너 표시 (`bg-amber-50 border border-amber-200 text-amber-800 px-4 py-2 rounded` "비식별 재시도 중 — 작업이 일시 잠금되었습니다")
  - 라벨링 좌표(원본 기준)는 그대로 유지
- **재시도 결과 알림**:
  - 성공: 토스트 `"비식별 재시도 완료. 작업을 재개할 수 있습니다."` + LOCKED 해제 + 비식별 프레임 자동 새로고침
  - 실패: 토스트 `"비식별 재시도 실패 — 검수자에게 알림이 전송되었습니다."` + LOCKED 유지

> **(V1.x 폐기 안내)** 이전 V1.x에서 "비식별 처리는 stage 아닌 내보내기 옵션"이라는 안내가 있었으나, V2에서 비식별이 자동 파이프라인 영상 단위 무조건 단계로 부활하면서 [원본/비식별 보기] 토글(검수자)과 [비식별 누락 신고] 버튼(라벨러)이 라벨링 화면에 추가되었다.

#### SCR-LABEL-002 · 라벨링 객체 패널

`ObjectTree` + `AttributePanel` 두 영역 (mock에서는 우측 패널에 통합 배치).

- **ObjectTree**: 프레임 내 객체 목록 (라벨 코드별 색상 점 + 라벨명 + 신뢰도). 클릭 시 캔버스 강조
- **AttributePanel**: 선택된 객체의 속성 편집 (라벨 코드/track ID/임의 attribute key-value)
- 객체 데이터: `LabelObject { id, type, labelCode, labelName, color, confidence, createdBy: 'auto'|'manual', bbox?, points?, attributes?, trackId? }`

---

### 4-7. 오토라벨링 결과 · 시계열 메타데이터 검토

#### SCR-AUTO-001 · 오토라벨링 결과 요약

mock 구현 = `AutoLabelSummary.tsx`. SCR-VIDEO-003 탭 3 또는 라벨링 도구 진입 전 요약 패널로 사용된다.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ 처리 정보                                                                  │
│ ┌─ grid-cols-2 sm:grid-cols-3 ─────────────────────────────────────────┐  │
│ │ [총 라벨 수: N]   [오토라벨 수: N]                                    │  │
│ │ [오토라벨 비율: N.N%]  [처리 상태: batchStatus]                       │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│ 신뢰도 분포 (오토라벨)                                                     │
│ 오토라벨 N건 기준                                                          │
│ ┌─ ConfidenceHistogram (h-24) ─────────────────────────────────────────┐  │
│ │  [N]      [N]       [N]                                              │  │
│ │  █green   █yellow   █red                                             │  │
│ │  0.9+     0.7–0.9   <0.7                                             │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│ 라벨별 분포 (상위 10개 내림차순)                                            │
│ ┌─ LabelBarChart ──────────────────────────────────────────────────────┐  │
│ │ {labelName}  [████████████]  N                                       │  │
│ │ {labelName}  [██████]        N                                       │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
```

**처리 정보 그리드 4개** (`grid-cols-2 sm:grid-cols-3 gap-3`, 각 셀 `bg-gray-50 rounded-lg p-3`)
1. 총 라벨 수 = `labels.length`
2. 오토라벨 수 = `labels.filter(l => l.createdBy === 'auto').length`
3. 오토라벨 비율 = `(autoCount / totalLabels) * 100 .toFixed(1) + '%'`
4. 처리 상태 = `video.batchStatus`

**신뢰도 분포 (ConfidenceHistogram, h-24)** — 오토라벨만 기준
- 3 버킷: `0.9+` (`bg-green-500`) / `0.7–0.9` (`bg-yellow-400`) / `<0.7` (`bg-red-400`)
- 각 막대 위에 카운트(`text-xs font-semibold tabular-nums`), 아래에 라벨
- 빈 상태: `"오토라벨 데이터가 없습니다."` (`text-center text-gray-400 py-4`)

**라벨별 분포 (LabelBarChart)** — 전체 라벨 기준
- `buildLabelCounts(labels)`로 labelCode별 집계 + 내림차순 정렬
- **상위 10개**만 표시 (`counts.slice(0, 10)`)
- 한 줄: `text-xs text-gray-600 w-20 truncate` 라벨명 + 막대 `h-3 rounded-full bg-gray-100` (내부 색상 = `l.color`) + `text-xs tabular-nums w-8 text-right` 카운트
- 빈 상태: `"라벨 데이터가 없습니다."` (`text-center text-gray-400 py-4`)

> **[낮은 신뢰도 보기] / [라벨링 도구로] 액션 버튼은 mock에 없음 — 설계서에서 제거**. 라벨링은 SCR-VIDEO-003의 프레임 미리보기 또는 작업 목록의 `[▶]` 버튼으로 진입.

#### SCR-AUTO-002 · 시계열 메타데이터 검토

V1.7~V1.8 정책 — VLM 시계열 메타 자동 추출은 외부 시스템 책임. 저작도구는 외부에서 생성된 메타를 **검토·수정**만 제공한다.

- mock 구현은 SCR-AUTO-001과 같은 표시 패널 구조로 통합되어 있으며, 외부에서 들어온 시계열 메타(프레임별 자연어 설명/객체 행동/환경 조건 등)를 표 또는 timeline 형태로 노출한다 (현 mock에서는 별도 페이지 미배포 — **(미구현 — 후속 적용)**).
- 외부 메타 검토 결과는 라벨 객체 attribute에 반영하거나 검수 의견에 부착한다.

---

### 4-8. 히스토리 · 버전관리

#### SCR-HIST-001 · 버전 히스토리

mock 구현 = `VersionHistory.tsx`. Gitea 자동 커밋된 영상별 라벨 히스토리 + diff/롤백.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ ← 뒤로가기                                                                  │
│ 버전 관리                                                                  │
│ {videoId}                                                                  │
│                                                                            │
│ ┌─ grid-cols-[400px_1fr] gap-6 items-start ───────────────────────────┐  │
│ │ ┌ 커밋 목록 (400px) ──────────┐  ┌ 변경 내용 (Diff) (1fr) ─────────┐ │  │
│ │ │ [GitCommit] 커밋 목록 [N]   │  │ 변경 내용 (Diff)  [↻ 롤백*]    │ │  │
│ │ │ (2개 선택 시 "2개 선택됨")  │  │ * rollbackHash 있을 때만        │ │  │
│ │ │ ─────────────────────────── │  │                                 │ │  │
│ │ │ ☐ [hash][최신 Badge]        │  │ DiffViewer videoId from to      │ │  │
│ │ │   message                   │  │                                 │ │  │
│ │ │   author · YYYY-MM-DD HH:mm │  │ (단일 선택: 해당 커밋 vs 이전)  │ │  │
│ │ │ ☐ [hash]                    │  │ (2개 선택: 두 커밋 간)          │ │  │
│ │ │   message                   │  │                                 │ │  │
│ │ │   author · ...              │  │                                 │ │  │
│ │ │ ...                         │  │                                 │ │  │
│ │ │ ─────────────────────────── │  │                                 │ │  │
│ │ │ (2개 선택 시) [diff 비교]   │  │                                 │ │  │
│ │ └─────────────────────────────┘  └─────────────────────────────────┘ │  │
│ └───────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
```

**헤더 영역**
- `[← 뒤로가기]` (ArrowLeft 18, `p-2 rounded-lg hover:bg-gray-100`) → `navigate(-1)`
- 제목 `text-xl font-bold` "버전 관리" + 보조 `text-sm text-gray-500` `{videoId}`

**좌측 커밋 목록 (`w-400`)**
- 카드 헤더: `GitCommit 15` 아이콘 + `text-sm font-semibold "커밋 목록"` + `Badge tone=neutral size=sm {commitList.length}`. 우측 (2개 선택 시) `text-xs text-blue-600 "2개 선택됨"`
- 본문: `ul divide-y divide-gray-100 max-h-[600px] overflow-y-auto`
  - 각 row: `px-4 py-3` + 체크박스 + 클릭 영역(`text-left flex-1`)
    - 해시 7글자 코드 `text-xs font-mono text-blue-700 bg-blue-50 px-1.5 py-0.5 rounded`
    - 최신(`idx === 0`)이면 `Badge tone=success size=sm "최신"`
    - `message` (truncate `text-sm text-gray-800`)
    - `{authorName} · {formatTime ko-KR YYYY-MM-DD HH:mm}` (`text-xs text-gray-400`)
  - 선택 행(`isSelected`): `bg-blue-50 border-l-2 border-blue-500`
  - 체크박스 2개 선택 후에는 단일 선택 모드 비활성화 (`button disabled`)
- 푸터: 2개 선택 시 `[Button primary sm w-full "diff 비교"]` 표시 (DiffViewer가 `activeFrom/To`로 자동 갱신되므로 onClick 동작은 비어있음)

**우측 DiffViewer 패널**
- 헤더: `text-sm font-semibold "변경 내용 (Diff)"` + (`rollbackHash` 있을 때만) `[Button secondary sm leftIcon=RotateCcw "이 버전으로 롤백"]`
- 본문: `<DiffViewer videoId={videoId} from={activeFrom} to={activeTo} />`

**Diff/롤백 모드 (VersionHistory.tsx:38~63)**
| `checkedHashes` | `selectedHash` | `activeFrom` | `activeTo` | 롤백 가능 |
|:---|:---|:---|:---|:---:|
| 0개 | null | undefined | undefined | ✗ (안내문만) |
| 0개 | hash | `commits[idx+1]?.hash` (이전 커밋) | `selectedHash` | hash !== latestHash일 때만 |
| 2개 | — | `checkedHashes[1]` (오래된 것) | `checkedHashes[0]` (최신 것) | ✗ (비교 모드) |

#### SCR-HIST-002 · 롤백 확인 모달

mock 구현 = `components/history/RollbackModal.tsx`. SCR-HIST-001 우측 `[이 버전으로 롤백]`에서 호출.

- Props: `open` / `hash: string` / `onClose` / `onConfirm(reason: string)`
- 본문: 롤백 대상 hash 표시 + 사유 textarea (필수)
- 액션: `[취소]` / `[롤백 실행]`
- 저장: `POST /api/v1/history/videos/{videoId}/rollback` `{ hash, reason }` → toast `"롤백이 완료되었습니다."` + `refetch()` + `selectedHash` 리셋

---

### 4-9. 검수 워크플로우

#### SCR-REVIEW-001 · 검수 목록

mock 구현 = `ReviewPending.tsx`. REVIEWER 전용.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ 검수 목록                                                                  │
│ ┌─ KPI grid-cols-2 sm:grid-cols-4 ─────────────────────────────────────┐  │
│ │ [검수 대기 Hourglass warning] [검수중 ClipboardCheck primary]         │  │
│ │ [승인 CheckCircle2 success]   [반려 XCircle danger]                   │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
│ ┌─ 필터 (Card border) ─────────────────────────────────────────────────┐  │
│ │ [검색 영상명/작업자명 (Enter)] [상태▾ 5종] [조회] [초기화]            │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
│ ┌─ Table ──────────────────────────────────────────────────────────────┐  │
│ │ 영상명 │ 작업자 │ 제출일 │ 라벨 수 │ 상태(StatusBadge) │ 액션         │  │
│ ├────────┼────────┼────────┼─────────┼──────────────────┼─────────────┤  │
│ │ ...    │ ...    │YYYY-MM-DD│ N(우측)│ [Badge]          │ 동적 액션    │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
│                                       [< 1 2 3 ... >]                       │
└────────────────────────────────────────────────────────────────────────────┘
```

**헤더 영역**
- 제목 `text-xl font-bold` "검수 목록" (단순, 우측 액션 없음)

**KPI 카드 4개 (`grid-cols-2 sm:grid-cols-4 gap-4`)**
| 카드 | 톤 | 아이콘 | 카운트 |
|:----|:--:|:------:|:------|
| 검수 대기 | warning | `Hourglass` | `PENDING` |
| 검수중 | primary | `ClipboardCheck` | `IN_REVIEW` |
| 승인 | success | `CheckCircle2` | `APPROVED` |
| 반려 | danger | `XCircle` | `REJECTED` |

데이터 소스: 별도 `useFetch<Page<ReviewDto>>('/reviews', { size: 100 })`로 전체를 한 번에 가져와 클라이언트 집계

**필터 (Card)** (`bg-white border rounded-lg p-4 flex flex-wrap items-end gap-3`)
- "영상명 / 작업자명" 검색 input (Enter 키로도 조회) — flex-1 min-w-160
- "상태" select — 전체 / 검수대기 / 검수중 / 승인 / 반려 (5종)
- `[Button blue "조회"]` + `[Button gray "초기화"]`
- 적용 방식: `setAppliedSearch/setAppliedStatus` 분리 — Enter 또는 조회 클릭 후에만 데이터 갱신

**Table 컬럼 6개** (`<table>` 직접 — Table 컴포넌트 미사용)
| # | 컬럼 | 정렬 | 셀 |
|:-:|:----|:----:|:--|
| 1 | 영상명 | left | `font-medium text-gray-900 truncate max-w-220 block`, `title={videoName}` |
| 2 | 작업자 | left | `text-gray-600` |
| 3 | 제출일 | left | `text-xs text-gray-500 whitespace-nowrap` (`ko-KR YYYY-MM-DD`) |
| 4 | 라벨 수 | **right** | `tabular-nums text-gray-700` `{labelCount.toLocaleString()}` |
| 5 | 상태 | center | `<StatusBadge status={review.status} />` |
| 6 | 액션 | center | 상태별 동적 버튼 (아래) |

**상태별 동적 액션** (`actionLabel(status)` / `actionClass(status)`)
| 상태 | 라벨 | 색상 | 진입 URL |
|:----|:----|:----:|:--------|
| `PENDING` | `"검수시작 ▶"` | `bg-blue-600 hover:bg-blue-700` | `/review/{id}` |
| `IN_REVIEW` | `"이어서 검수"` | `bg-purple-600 hover:bg-purple-700` | `/review/{id}` |
| `APPROVED` | `"결과보기 ▶"` | `bg-gray-100 text-gray-700` | `/review/{id}?readonly=true` |
| `REJECTED` | `"결과보기 ▶"` | `bg-gray-100 text-gray-700` | `/review/{id}?readonly=true` |

**페이징** — `PAGE_SIZE = 10`. 서버 측 `/reviews?status={appliedStatus}&page={page}&size=10` + 클라이언트 검색 필터

**빈 / 로딩 상태**
- 로딩: `"로드 중..."` (`text-gray-400 text-sm py-16 text-center`)
- 빈: `ClipboardCheck 40` 아이콘(`opacity-30`) + `"검수 작업이 없습니다"`

#### SCR-REVIEW-002 · 검수 화면

mock 구현 = `ReviewEditor.tsx`. **라벨링 도구와 유사한 다크 풀스크린 오버레이**(`fixed inset-0 bg-gray-900 z-50`). 라벨은 readOnly로 표시되고 우측 패널이 ObjectTree+AttributePanel(readOnly)+ReviewNotePanel 3분할 구조다.

```
┌─ Top bar (h=56) ────────────────────────────────────────────────────────────┐
│ [× 목록]  검수 — {영상명}                                                    │
│           작업자: {workerName}                                              │
└─────────────────────────────────────────────────────────────────────────────┘
┌─ LabelCanvas (flex-1, readOnly) ──────────────┐┌─ 우측 패널 (w-72) ──────┐
│                                                ││ ─ 객체 목록 (flex 50%) │
│   konva.js Stage (객체 표시만, 편집 불가)      ││  ObjectTree            │
│                                                ││ ─ 속성 (flex 25%) ──   │
│   캔버스 좌표 마커 미사용                       ││  AttributePanel        │
│                                                ││  (readOnly)            │
│                                                ││ ─ 검수 노트 (flex-1) ─ │
│                                                ││  ReviewNotePanel       │
│                                                ││   - 이슈 모드 토글     │
│                                                ││   - 이슈 목록 (프레임  │
│                                                ││     번호 + comment)    │
│                                                ││   - 전체 의견          │
│                                                ││     (textarea)         │
└────────────────────────────────────────────────┘└────────────────────────┘
┌─ Bottom bar ────────────────────────────────────────────────────────────────┐
│ FrameStripWithIssues (h=60) — 이슈 프레임 강조                              │
│ FrameSlider (h=40)                                                          │
│ (!readOnly) [❌ 반려 red-600]  [✅ 승인 green-600]                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Top bar**
- 좌: `[× 닫기]` (`X 18`) → `navigate(-1)` + "검수 — {video.cctvName}" + 보조 "작업자: {review.workerName}"
- isReadOnly(`?readonly=true`)이면 하단 [반려][승인] 버튼 영역 숨김

**진입 시 라벨 store 초기화**
- `setVideo(video.id, 60)` + `setFrame(0)`
- `setReadOnly(true)` — 검수 컨텍스트에서는 캔버스 readOnly. unmount 시 `setReadOnly(false)`로 복원

**우측 패널 3분할 (w-72 `bg-gray-800 border-l border-gray-700`)**
- **상단 50%** (`flex-1`): `─ 객체 목록 ─` 헤더 + `ObjectTree`
- **중간 25%** (`flex: 0 0 25%`): `─ 속성 ─` 헤더 + `<AttributePanel readOnly />`
- **하단 (`flex-1`)**: `ReviewNotePanel` 본체
  - 이슈 목록 (각 항목: 프레임 번호 + 코멘트 텍스트 + 삭제/편집 액션 + 프레임 점프)
  - 이슈 모드 토글 버튼 (켜져있을 때 캔버스 클릭으로 새 이슈 등록)
  - 전체 의견 `textarea` (`overallComment`)
  - props: `issues / frameNo / isIssueMode / onToggleIssueMode / onRemoveIssue / onEditIssue / onGoToFrame / overallComment / onOverallCommentChange / readOnly`

**이슈 모델** (`types/review.ts`)
```typescript
ReviewIssue { id: string, frameNo: number, comment: string, createdAt: string }
```
- ID 생성: `issue-{Date.now()}-{rand4}`
- 좌표 마커는 사용하지 않음 — **프레임 번호 + 코멘트만**

**프레임별 이슈 강조**
- `FrameStripWithIssues videoId issueFrameNos` — `Set<number>` 전달
- FrameStrip 내부에서 해당 프레임 강조 표시 (overlay 색상)

**하단 액션 (`!isReadOnly`일 때)**
- `[❌ 반려]` (`bg-red-600`) → `setShowReject(true)` → `RejectModal` 열기
- `[✅ 승인]` (`bg-green-600`) → `setShowApprove(true)` → `ApproveConfirm` 모달

**부속 모달 3종**
- `ApproveConfirm`: `open / onClose / onConfirm / isLoading`. 단순 확인 모달
- `RejectModal`: `open / onClose / onConfirm / issues / workerName / isLoading`. 반려 사유 + 등록된 이슈 요약
- `IssueCommentModal`: `open / frameNo / onClose / onConfirm`. 새 이슈 등록 (프레임별 코멘트 textarea)

#### SCR-REVIEW-003 · 반려 처리 모달 (`RejectModal`)

mock 구현 = `components/review/RejectModal.tsx`. SCR-REVIEW-002 `[❌ 반려]` 클릭 시 열림.

- Props: `open` / `onClose` / `onConfirm(reason: string)` / `issues: ReviewIssue[]` (요약 표시용) / `workerName` / `isLoading`
- 본문: 반려 사유 textarea (필수) + 등록된 이슈 목록 요약 표시
- 액션: `[취소]` / `[반려 확정]`
- 저장: `POST /api/v1/reviews/{id}/reject` (반려 사유 + 이슈 목록) → toast → `navigate('/review/pending')`

**검수 결과 read-only 진입**
- SCR-REVIEW-001 `APPROVED` / `REJECTED` 행의 `[결과보기 ▶]` 클릭 → `/review/{id}?readonly=true`
- SCR-REVIEW-002와 동일 화면이지만 하단 액션 영역 미렌더 + 이슈 모드 토글 비활성 + 이슈 등록 모달 비활성

---

### 4-10. 대시보드 · 통계

#### SCR-STAT-001 · 작업자 통계

mock 구현 = `WorkerStats.tsx`. WORKER는 본인 통계, REVIEWER는 모든 작업자를 select로 전환 조회.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ [▣ BarChart2 blue] 작업자 통계               [작업자 선택▾ (REVIEWER만)]   │
│                    {workerStat.workerName}                                  │
│                                                                            │
│ ┌─ KPI 4개 (grid-cols-2 md:grid-cols-4) ──────────────────────────────┐ │
│ │ [완료 작업 success CheckCircle] [진행 중 warning Clock]               │ │
│ │ [반려 danger XCircle] [총 라벨 수 primary Tag]                        │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
│ ┌─ 보조 KPI 2개 (grid-cols-2) ─────────────────────────────────────────┐ │
│ │ [오토라벨 비율: N.N%]  [반려율: N.N% (>10%면 red)]                    │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
│ ┌─ 일별 작업량 (최근 30일) — SimpleBarChart h=200 color=#3b82f6 ──────┐ │
│ │  ▁▂▃▅▇▆▅▃▂▁▂▃▄▅▆▇▆▅▄▃▂▁▂▃▄▅▆▇▆▅                                    │ │
│ │ (xAxisInterval=5, 5일 간격 x축 라벨)                                  │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
│ ┌─ 월별 통계 (최근 12개월) — Table 4컬럼 ─────────────────────────────┐ │
│ │ 월 │ 완료(tabular-nums font-medium) │ 반려(tabular-nums red-500)   │ │
│ │     │ 라벨 수(tabular-nums localeString)                              │ │
│ │ (빈 상태: "월별 데이터가 없습니다.")                                   │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────┘
```

**헤더 영역**
- 좌: `BarChart2 20` 아이콘(`w-9 h-9 bg-blue-50 rounded-lg`, `text-blue-600`) + `text-xl font-bold` "작업자 통계" + 부제 `text-xs text-gray-400` `workerStat.workerName`
- 우 (REVIEWER만): 작업자 선택 `<select>` (`overall.workers` 옵션) — `aria-label="작업자 선택"`
- WORKER는 본인 통계만 자동 표시 (선택 select 미노출)

**KPI 4개** (`grid-cols-2 md:grid-cols-4 gap-4`)
| 카드 | 톤 | 아이콘 | 값 |
|:----|:--:|:------:|:--|
| 완료 작업 | success | `CheckCircle` | `workerStat.completed` |
| 진행 중 | **warning** | `Clock` | `workerStat.inProgress` |
| 반려 | danger | `XCircle` | `workerStat.rejected` |
| 총 라벨 수 | primary | `Tag` | `workerStat.labelCount.toLocaleString()` |

**보조 KPI 2개** (`grid-cols-2 gap-4`, `bg-white border rounded-lg px-5 py-4`)
- 오토라벨 비율: `text-xs text-gray-500 "오토라벨 비율"` + `text-xl font-bold tabular-nums` `{(autoLabelRate * 100).toFixed(1)}%`
- 반려율: 동일 구조. **`rejectRate > 0.1`일 때 `text-red-600`**, 그 외 `text-gray-900`

**일별 작업량 차트** (`SimpleBarChart`)
- 데이터: `dailyBarData` (최근 30일)
- props: `height={200} / xAxisInterval={5} / color="#3b82f6"`
- 빈 상태: `"데이터가 없습니다."` (`text-center text-gray-400 py-8`)

**월별 통계 테이블** (`Table` 컴포넌트 + `MONTHLY_COLS`)
- 컬럼 4개:
  | key | header | render |
  |:----|:------|:------|
  | `month` | 월 | (기본) |
  | `completed` | 완료 | `tabular-nums font-medium` |
  | `rejected` | 반려 | `tabular-nums text-red-500` |
  | `labelCount` | 라벨 수 | `tabular-nums` `toLocaleString()` |
- `rowKey: r => r.month`
- 빈 상태: `"월별 데이터가 없습니다."`

**상태별 UI**
- `workerStat` null이면 KPI/보조 KPI는 `"통계 데이터가 없습니다."` (`text-sm text-gray-400 p-4`)로 대체

> **이벤트 분포 파이 차트는 mock에 없음 — 설계서에서 제거**. 이벤트 분포는 SCR-STAT-002에서만 다룬다.
> **월별 6컬럼 테이블도 mock에 없음 — 4컬럼으로 정정**. 오토라벨 비율은 보조 KPI에 별도 표기됨.

#### SCR-STAT-002 · 전체 구축 현황

mock 구현 = `OverallStats.tsx`. REVIEWER 전용 — 사업 전체 진척도 한눈에. 누적 표시만(목표/비율 없음).

```
┌────────────────────────────────────────────────────────────────────────────┐
│ [▣ TrendingUp indigo] 전체 구축 현황                                       │
│                                                                            │
│ ┌─ 누적 카드 2개 (grid-cols-1 md:grid-cols-2) ────────────────────────┐  │
│ │ ┌ [Image blue] 이미지 학습데이터    ┐ ┌ [Film purple] 영상 학습데이터 ┐│  │
│ │ │ {imageCompleted.toLocaleString()} │ │ {videoCompleted.toLocaleString()}││  │
│ │ │                  장 (text-3xl font-black) │ │              건 (text-3xl)│  │
│ │ └────────────────────────────────────┘ └────────────────────────────┘│  │
│ └─────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│ ┌─ 처리 현황 5카드 (grid-cols-5) ──────────────────────────────────────┐ │
│ │ [전체 gray] [완료 green-600] [처리중 blue-600]                       │ │
│ │ [실패 red-600] [대기 yellow-600] (모두 bg-gray-50 카드)              │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│ ┌─ 일별 전체 작업량 (최근 30일) SimpleBarChart h=200 color=#6366f1 ────┐ │
│ │  ▁▂▃▅▇▆▅▃▂▁ ...                                                       │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│ ┌─ 이벤트 유형 분포 ───────────────────────────────────────────────────┐ │
│ │ ┌ flex items-center gap-8 ────────────────────────────────────┐      │ │
│ │ │ [SimplePieChart size=160 showLegend]   [수평 막대 6종]       │      │ │
│ │ │  쓰러짐/폭력/교통사고/이상행동/침수/산불                      │      │ │
│ │ │  각 항목: 라벨 + 카운트(tabular-nums) + 막대(h-2 rounded)    │      │ │
│ │ └──────────────────────────────────────────────────────────────┘      │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│ ┌─ 작업자별 현황 (Table — buildWorkerColumns) ─────────────────────────┐ │
│ │ "컬럼 헤더 클릭으로 정렬" (안내문)                                    │ │
│ │ 작업자 │ 완료 │ 진행 │ 라벨 │ 오토라벨 │ 반려율 (sortField/sortDir)  │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────┘
```

**헤더 영역**
- `TrendingUp 20` 아이콘(`w-9 h-9 bg-indigo-50 rounded-lg`, `text-indigo-600`) + `text-xl font-bold` "전체 구축 현황"

**누적 카드 2개** (`grid-cols-1 md:grid-cols-2 gap-4`, 각 `bg-white border rounded-lg p-6 space-y-3`)
- 이미지: `Image 18 blue-500` + "이미지 학습데이터" + `text-3xl font-black text-primary tabular-nums` 카운트 + "장"
- 영상: `Film 18 purple-500` + "영상 학습데이터" + 동일 구조 + "건"
- **목표/비율 표시 없음** (V1.x 정책 — 누적만)

**처리 현황 5카드** (`grid-cols-5 gap-3`, 각 `bg-gray-50 rounded-lg p-3 text-center`)
| 라벨 | 값 색상 | mock 데이터 |
|:----|:------|:--|
| 전체 | `text-gray-800` | `batchStats.total` (5820) |
| 완료 | `text-green-600` | `batchStats.completed` (4210) |
| 처리중 | `text-blue-600` | `batchStats.processing` (340) |
| 실패 | `text-red-600` | `batchStats.failed` (82) |
| 대기 | `text-yellow-600` | `batchStats.pending` (1188) |

**일별 전체 작업량** (`SimpleBarChart`)
- 데이터: `overall.dailyCounts.map(d => ({ label: d.date, value: d.count }))`
- props: `height={200} / xAxisInterval={5} / color="#6366f1"` (indigo)

**이벤트 유형 분포** (`flex items-center gap-8`)
- 좌: `<SimplePieChart data={EVENT_DIST} size={160} showLegend />` (6종 색상은 EventTypeBadge 매핑)
- 우: 수평 막대 — 각 라벨 row(`text-xs`)에 카운트(`tabular-nums font-medium`) + `h-2 bg-gray-100 rounded-full` 막대 (width=비율 %, backgroundColor=색상)

**작업자별 현황 테이블** (`Table` + `buildWorkerColumns(sortField, sortDir, handleSort)`)
- 컬럼: 작업자 / 완료 / 진행 / 라벨 / 오토라벨 / 반려율
- 정렬: 컬럼 헤더 클릭 시 `sortField` + `sortDir`(asc/desc) 토글
- `sortedWorkers = [...overall.workers].sort()` 클라이언트 정렬
- `rowKey: r => r.workerId`
- 안내: `text-xs text-gray-400 "컬럼 헤더 클릭으로 정렬"`
- 빈 상태: `"작업자 데이터가 없습니다."`

**로딩 상태**
- Skeleton: 제목 + 누적 카드 2개 + 차트 + 테이블 영역

---

### 4-11. 데이터 증강 (V2 — 내보내기 화면 제거)

#### SCR-AUG-001 · 증강 요청

mock 구현 = `AugmentRequest.tsx`. REVIEWER 전용. SFR-07 — 증강은 외부 시스템(생성형 AI)이 수행하고 저작도구는 요청·결과 검수만 담당.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ [▣ Wand2 purple] 데이터 증강 요청                                          │
│ 증강 요청은 검수 완료(승인)된 영상만 가능합니다                              │
│                                                                            │
│ ┌─ Step 1: 증강 유형 선택 ───────────────────────────────────────────┐  │
│ │ [① 원형 번호] 증강 유형 선택   [Badge "{N}종 선택"]                  │  │
│ │ ┌ grid-cols-2 md:grid-cols-4 gap-4 ────────────────────────────┐   │  │
│ │ │ [WINTER ❄] [NIGHT 🌙] [RAIN ☔] [RESOLUTION 🔬]               │   │  │
│ │ │ (AugmentTypeCard — 복수 선택)                                  │   │  │
│ │ └────────────────────────────────────────────────────────────────┘   │  │
│ │ (0개 선택 시) ⚠ "증강 유형을 하나 이상 선택하세요." (amber)          │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│ ┌─ Step 2: 대상 영상 선택 ──────────────────────────────────────────────┐ │
│ │ [② 원형] 대상 영상 선택  [검수 완료 {approvedVideos.length}건] [선택 N건] │ │
│ │ [검색 q] [이벤트 필터▾]   URL 동기화                                  │ │
│ │ ┌─ Table 5컬럼 ────────────────────────────────────────────────┐   │ │
│ │ │ ☐ │ 영상명·CCTV명·videoId │ 이벤트 │ 녹화일 │ 검수 완료 일시 │   │ │
│ │ └──────────────────────────────────────────────────────────────┘   │ │
│ │                                       [< 1 2 3 ... >]                │ │
│ │ (0건 선택 시) ⚠ "대상 영상을 하나 이상 선택하세요." (amber)         │ │
│ └──────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│ ┌─ Step 3: 최근 요청 이력 (5초 polling) ──────────────────────────────┐ │
│ │ [History] 최근 요청 이력  [Badge {totalElements}건]                  │ │
│ │ ┌ 카드 그리드 grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3 ──┐ │ │
│ │ │ ┌ 카드 (clickable button) ────────────────────────────────┐   │ │ │
│ │ │ │ {jobId.fontmono.gray}    [활용 결정*][상태 Badge]        │   │ │ │
│ │ │ │ [WINTER][NIGHT] (purple-50 칩)                            │   │ │ │
│ │ │ │ N건 영상            MM-DD HH:mm                            │   │ │ │
│ │ │ │ 진행률  ─────────────── N%  (ProgressBar)                 │   │ │ │
│ │ │ │ (FAILED는 "처리 실패" 빨강)                                │   │ │ │
│ │ │ └────────────────────────────────────────────────────────────┘   │ │ │
│ │ │ * COMPLETED 상태일 때만 활용 결정 배지 노출                       │ │ │
│ │ └────────────────────────────────────────────────────────────────┘ │ │
│ │ (빈 상태) "아직 증강 요청 이력이 없습니다." (gray-50 박스)           │ │
│ │ (로딩) Skeleton h-9rem × 6개                                          │ │
│ └──────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│ ┌─ 하단 고정 바 fixed bottom-0 z-20 ──────────────────────────────────┐  │
│ │  선택: {types.size}종 × {videos.size}건 = 예상 N건                   │  │
│ │                                       [취소] [Wand2 증강 요청]       │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
```

**헤더 영역**
- `Wand2 20` 아이콘(`w-9 h-9 bg-purple-50 rounded-lg`, `text-purple-600`) + `text-xl font-bold` "데이터 증강 요청" + `text-xs text-gray-500` 안내문

**Step 1 — 증강 유형 선택**
- 원형 번호 배지 `w-7 h-7 rounded-full bg-primary-600 text-white text-xs font-bold "1"` + `text-base font-semibold` "증강 유형 선택" + (선택 ≥1) `Badge tone=info "N종 선택"`
- `AugmentTypeCard`: `grid-cols-2 md:grid-cols-4 gap-4`, 4종 카드 (`WINTER`/`NIGHT`/`RAIN`/`RESOLUTION`)
- 0건 선택 안내: `flex items-center gap-1.5 text-xs text-amber-600 AlertCircle 13 "증강 유형을 하나 이상 선택하세요."`

**Step 2 — 대상 영상 선택**
- 원형 ② + "대상 영상 선택" + `Badge tone=neutral "검수 완료 {approvedVideos.length}건"` + (선택 ≥1) `Badge tone=success "{selectedCount}건 선택"`
- **검수 완료 가드**: `taskStatus === 'COMPLETED'` 영상만 `approvedVideos`에 포함 (FE에서 1차 필터, 백엔드도 422로 차단)
- 필터: 검색 `q` + 이벤트 `eventType` + URL 동기화
- **컬럼 5개** (`ColumnDef<VideoDto>[]`)
  | # | key | header | width | 셀 |
  |:-:|:----|:------|:-----:|:--|
  | 1 | `_select` | (빈) | 40px | 행 체크박스 (`aria-label="{cctvName} 선택"`) |
  | 2 | `cctvName` | 영상명 / CCTV명 | min-w 160 | `font-medium text-sm truncate max-w-260` + 보조 `text-xs text-gray-400` videoId |
  | 3 | `eventType` | 이벤트 | — | `EventTypeBadge` 또는 `-` |
  | 4 | `recordedAt` | 녹화일 | — | `formatDate YYYY-MM-DD` `text-xs text-gray-500` |
  | 5 | `updatedAt` | 검수 완료 일시 | — | `formatDate YYYY-MM-DD HH:mm` `text-xs text-gray-500` |
- 헤더 indeterminate 체크박스(`toggleAllPaged`) — 현재 페이지 단위
- 페이징 (Pagination)
- 0건 선택 안내: amber 경고

**Step 3 — 최근 요청 이력**
- 헤더: `History 18` 아이콘 + "최근 요청 이력" + (응답 있을 때) `Badge tone=neutral "{totalElements}건"`
- **카드 그리드** `grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3` (테이블 아님)
- 각 카드: `bg-white border rounded-lg p-4 space-y-3 hover:border-primary-400 hover:shadow-sm` — `<button>` 클릭 시 `navigate('/augment/result/${job.id}')`
  - 상단 row: `text-xs font-mono text-gray-400 truncate` jobId | 우측 (`status === 'COMPLETED'`이면) `Badge tone=DECISION_TONE size=sm "{decision}"` + `Badge tone=STATUS_TONE size=sm "{status}"`
  - 증강 유형 칩: `inline-flex text-xs bg-purple-50 text-purple-700 rounded-full px-2 py-0.5` 다수
  - 통계 row: "{videoIds.length}건 영상" | 시각 (`ko-KR MM-DD HH:mm`)
  - 진행률: `FAILED`면 `text-xs text-red-500 "처리 실패"`, 아니면 ProgressBar (COMPLETED는 100, 그 외 `job.progress`) + 라벨 + `tabular-nums {N}%`
- 데이터 소스: `useFetch<Page<AugmentJob>>('/augment', { page: 0, size: 6 })`
- **5초 폴링** (`hasPendingJobs && refetchInterval: 5000`): pending/in_progress가 있으면 자동 갱신. 첫 로딩만 skeleton (`jobsLoading && !jobsPage`), 이후 polling은 silent in-place (TanStack Query staleTime + cache)
- 빈 상태: `bg-gray-50 rounded-lg p-8 text-center text-sm text-gray-500 "아직 증강 요청 이력이 없습니다."`

**하단 고정 바** (`fixed bottom-0 left-0 right-0 z-20 bg-white border-t px-6 py-4`)
- 좌: 선택 요약 `text-sm text-gray-600` — `"선택: {types.size}종 × {videos.size}건 = 예상 {totalEstimated}건"` (강조: `font-bold text-primary-600`)
- 우: `[Button secondary md "취소"]` (`navigate(-1)`) + `[Button primary md leftIcon=Wand2 loading=submitting disabled=!canSubmit "증강 요청"]`

**제출 흐름**
- `canSubmit = types.size > 0 && videos.size > 0`
- 클릭 → `POST /api/v1/augment/request` `{ videoIds, types }` → 응답 `AugmentJob` → toast `"증강 요청이 등록되었습니다."` + `refetchJobs()` + `navigate('/augment/result/{job.id}')`
- 에러: toast `"증강 요청 중 오류가 발생했습니다."` (error)

#### SCR-AUG-002 · 증강 결과

mock 구현 = `AugmentResult.tsx`. SCR-AUG-001 카드 클릭 또는 `/augment/result/:jobId` 직접 진입. 영상 단위 섹션이 N개 표시되며, 각 영상마다 12프레임 그리드 + 큰 좌우 비교 + 활용 결정 카드가 있다.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ ← 뒤로  증강 결과 — Job {jobId}     [상태 Badge] [활용 결정 Badge]        │
│ {types 칩들}                                  N건 영상 · 생성 시각        │
│                                                                            │
│ ┌─ VideoSection (영상 1 — 항상 펼침) ──────────────────────────────────┐ │
│ │ ┌ 영상 헤더 ─────────────────────────────────────────────────────┐  │ │
│ │ │ {videoId font-mono gray}      [WINTER 칩][NIGHT 칩] ...        │  │ │
│ │ └──────────────────────────────────────────────────────────────────┘  │ │
│ │ ┌ 유형 탭 (types.length > 1일 때만) ─────────────────────────────┐  │ │
│ │ │ [WINTER active=primary] [NIGHT] [RAIN] [RESOLUTION]            │  │ │
│ │ └──────────────────────────────────────────────────────────────────┘  │ │
│ │ ┌ 프레임 그리드 ────────────────────────────────────────────────┐  │ │
│ │ │ [Badge "{N+1}/12 선택"] 우측 정렬                                │  │ │
│ │ │ grid-cols-3 md:grid-cols-6 gap-2, 12개 썸네일                    │  │ │
│ │ │ 각: 상단=원본 / 하단=증강 (다크 배경) / 라벨={frameTimeLabel}    │  │ │
│ │ │ 선택 시: 우상단 ✓(primary-500 동그라미) + border-primary-500     │  │ │
│ │ │ 안내: "상단: 원본 / 하단: {TYPE} 증강 — 프레임 클릭 시 비교 뷰"  │  │ │
│ │ └──────────────────────────────────────────────────────────────────┘  │ │
│ │ ┌ SideBySideCompare (h=380, 50:50) ──────────────────────────────┐  │ │
│ │ │ [원본 프레임 큰 이미지]      [증강 프레임 큰 이미지]              │  │ │
│ │ │ label: { before: "원본", after: "{TYPE} 증강" }                  │  │ │
│ │ └──────────────────────────────────────────────────────────────────┘  │ │
│ └──────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│ ┌─ [더 보기 ▾] (visibleVideos < 전체) ──────────────────────────────┐  │
│ │ INITIAL_VIDEO_LIMIT = 2. 초과 시 토글 버튼 노출                      │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│ ┌─ 학습데이터 활용 결정 (Job 단위 — 페이지 하단 1개) ────────────────┐  │
│ │ 학습데이터 활용 결정                            [Badge 활용 결정]    │  │
│ │ ─────────────────────────────                                       │  │
│ │ (PENDING) 이 증강 결과를 검토하고 학습데이터로 활용할지 결정해주세요.│  │
│ │ [👍 ThumbsUp 학습데이터로 활용] [👎 ThumbsDown 거부]                 │  │
│ │ ─────────────────────────────                                       │  │
│ │ (ACCEPTED/REJECTED)                                                 │  │
│ │ "이 증강 결과는 학습데이터로 활용 등록되었습니다." (또는 거부 메시지)│  │
│ │ 결정 시각: ... | 결정자: {decisionBy mono}                           │  │
│ │ (REJECTED) 사유: {decisionReason} (red-50 박스)                     │  │
│ │ [RotateCcw 결정 변경]                                                │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
```

**헤더 영역 (Job 단위)**
- `[← 뒤로가기]` → `navigate(-1)` (또는 `/augment/request`)
- 제목 `text-xl font-bold` "증강 결과 — Job {jobId}"
- 우측: Job 상태 Badge + (COMPLETED일 때) 활용 결정 Badge
- 부제: 증강 유형 칩들 + `"N건 영상 · {createdAt}"`

**유형 탭** — `types.length > 1`일 때만 노출
- 단일 유형이면 탭 숨김
- 활성 탭: `bg-primary-600 text-white border-primary-600`
- 비활성: `bg-white text-gray-600 border-gray-300 hover:bg-gray-50`

**프레임 그리드** (각 VideoSection 내부)
- 상단 라벨: `text-xs font-semibold` "프레임 그리드" + 우측 `Badge tone=neutral size=sm "{selectedFrameIdx+1}/{TOTAL_FRAMES=12} 선택"`
- 그리드: `grid-cols-3 md:grid-cols-6 gap-2`
- 각 프레임 카드 (clickable button, `aria-label="프레임 {time} 선택"`):
  - 상단(`bg-gray-900`): 원본 이미지 (`picsum/seed/orig-{vid}-{idx}/160/90`)
  - 하단(`bg-gray-800`): 증강 이미지 (`picsum/seed/{type}-{vid}-{idx}/160/90`)
  - 라벨(`bg-gray-900 text-center py-0.5`): `text-[10px] text-gray-400 font-mono` 프레임 시간
  - 선택 시: `border-2 border-primary-500 shadow-md` + 우상단 ✓ (`absolute top-1 right-1 w-4 h-4 bg-primary-500 rounded-full Check 10`)
- 안내 문구 하단: `text-xs text-gray-400` "상단: 원본 / 하단: {TYPE} 증강 — 프레임 클릭 시 아래 비교 뷰가 전환됩니다."

**Side-by-side 비교** (`SideBySideCompare` 컴포넌트)
- props: `beforeSrc / afterSrc / height={380} / label={{ before: "원본", after: "{TYPE} 증강" }}`
- 화면 절반씩(50:50), 슬라이드 핸들로 비교 가능

**"더 보기" 토글**
- `INITIAL_VIDEO_LIMIT = 2`. 영상 수가 2 초과면 초기에는 2건만 펼침
- `[더 보기 ▾]` 버튼 클릭 → 나머지 영상 표시
- 토글: `showAll && setShowAll(true)`

**학습데이터 활용 결정 카드** (Job 단위 — 페이지 하단 1개)
- 제목 + `Badge tone=DECISION_TONE size=md "{DECISION_LABEL}"`
- **PENDING**: 안내문 + `[Button primary md leftIcon=ThumbsUp loading "학습데이터로 활용"]` + `[Button secondary md leftIcon=ThumbsDown "거부"]`
  - 활용 클릭 → 즉시 `submitDecision('ACCEPTED')`
  - 거부 클릭 → `setRejectModalOpen(true)` (거부 사유 모달)
- **ACCEPTED/REJECTED**: 결정 메시지 + 결정 시각 + 결정자(`font-mono`) + (REJECTED일 때) `text-xs bg-red-50 border-red-100 rounded px-3 py-2 text-red-700 "사유: {decisionReason}"` + `[Button secondary sm leftIcon=RotateCcw "결정 변경"]`
  - **결정 변경 동작 (UX 토글)**: 현재 ACCEPTED면 REJECTED 모달 / 현재 REJECTED면 즉시 ACCEPTED로 전환

**거부 사유 모달** (`Modal size="md"`)
- 제목: "활용 거부 사유"
- 본문: `text-sm text-gray-600` "거부 사유를 입력해주세요. (선택)" + `textarea rows=4` (placeholder "예: 라벨 무결성 부족 — 재증강 필요")
- 푸터: `[취소] [거부 확정 (loading)]`
- **사유는 선택 입력 (required 아님)** — 빈 사유로도 거부 가능

**활용 결정 (`AugmentDecision`)**
- `PENDING` → `ACCEPTED` 또는 `REJECTED`
- `decision` / `decisionAt` / `decisionBy` / `decisionReason`(REJECTED만) 필드를 응답에 포함
- API: `POST /api/v1/augment/{jobId}/decision` `{ decision, reason? }`

**상태별 동적 UI**
- COMPLETED이면 활용 결정 카드 + 프레임 비교 표시
- 그 외(PROGRESS/FAILED) 상태에서는 진행률 바 또는 실패 메시지만 표시

#### (V2 폐기) SCR-EXPORT-001 · 내보내기 (포털 전송)

**V2에서 본 화면은 제거되었다.**

V1.x에서 운영하던 "내보내기" 화면(포털 전송, 좌측 3단계 폼 + 우측 sticky 미리보기·실행 2열 레이아웃, `ExportPage.tsx`)은 V2에서 정책상 폐기되었다.

- **제거 사유**: 저작도구 사용자가 직접 트리거할 일이 없음. 외부 시스템(데이터마트·외부 학습 시스템)이 학습데이터를 가져가는 외부 API 호출 모델로 일원화
- **대체 모델**: 외부 시스템 학습데이터 API (저작도구 BE에서 제공, 화면 미보유)
  - 노출 대상: 검수 승인된 영상만 (`taskStatus === 'COMPLETED'`)
  - 기본 응답: 원본 메타 JSON과 비식별 메타 JSON을 분리하여 반환. 라벨링 좌표(bbox/polygon/segment)는 1벌이며 두 JSON에서 동일 참조
  - `?merge=true` 옵션: 두 JSON을 머지한 단일 응답 (영상/프레임 단위에 `deidentified: true|false` 플래그가 포함된 비식별 표시 메타 함께 노출)
  - 포맷 옵션: `?format=COCO|YOLO|CVAT|PASCAL_VOC`
  - 인증: M2M 토큰(`API_KEY_ID`) + IP 허용 목록
  - 호출 로그: `LS_DATA_API_LOG` 테이블 (DB 설계서 §4.7 V2 참조)
- **V1.x 잔존 폐기 항목**:
  - 라우트 `/export`, `pages/export/ExportPage.tsx`, `ExportStatusBadge` 컴포넌트 모두 제거
  - LNB "내보내기" 메뉴 제거 (§4-13에서 메뉴 인덱스 갱신)
  - DTO 폐기: `ExportRequest` (targetServer / deidentify / format / options / forceReexport), `ExportResponse` (jobId / count / failedDueDeident / skippedAlreadyExported / blockedNotApproved), `VideoDto.exportStatus / exportedAt / lastExportFailureReason`
  - 이력 추적은 외부 API 호출 로그로 일원화 (영상 단위 영구 필드 제거)
- **사용자 워크플로우 변경**:
  - 검수자: 검수 승인까지가 책임 범위. 이후 외부 노출은 외부 시스템 책임
  - 외부 시스템: M2M 토큰으로 학습데이터 API 호출 → 응답 JSON 수신 → 데이터마트 적재 / 외부 학습 시스템 입수

이 화면을 참조하는 다른 문서·SCR 항목은 모두 갱신되었다 (요구사항정의서 §4.4.6 V2, DB설계서 §4.7 V2 / §4.8 V2, review-guide V2).

---

### 4-12. 포털 진입 모드 (외부 사용자)

#### SCR-PORTAL-001 · 포털 메인

mock 구현 = `PortalMain.tsx`. PORTAL_USER 전용. **Hero(gradient orange) + 내 현황 + 이용 방법 2단계 + 내 업로드 목록** 4섹션 구조. 반응형(모바일 지원).

```
┌────────────────────────────────────────────────────────────────────────────┐
│ [PortalShell GNB — 주황 로고 + 사용자]                                     │
│                                                                            │
│ ┌─ Hero (bg-gradient-to-br from-orange-500 to-orange-600 text-white py-10)┐│
│ │ AI 학습데이터 작성 포털                                                  ││
│ │ 영상/이미지 업로드, 간편 라벨링, 오토라벨링 체험                          ││
│ └─────────────────────────────────────────────────────────────────────────┘│
│                                                                            │
│ ── max-w-4xl mx-auto px-4 py-6 space-y-8 ──                              │
│                                                                            │
│ ┌─ 내 현황 ────────────────────────────────────────────────────────────┐ │
│ │ ["내 현황" 라벨 uppercase tracking-wide]                               │ │
│ │ ┌ grid-cols-2 gap-4 ────────────────────────────────────────────┐    │ │
│ │ │ ┌ 업로드 건수 ──┐  ┌ 라벨링 완료 ──┐                          │    │ │
│ │ │ │ {uploadCount}건│  │ {labeledCount}건│ (text-2xl font-bold)    │    │ │
│ │ │ └────────────────┘  └─────────────────┘                          │    │ │
│ │ └────────────────────────────────────────────────────────────────┘    │ │
│ └────────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│ ┌─ 이용 방법 ──────────────────────────────────────────────────────────┐ │
│ │ ["이용 방법" 라벨 uppercase tracking-wide]                              │ │
│ │ ┌ flex-col md:flex-row gap-4 ─────────────────────────────────────┐  │ │
│ │ │ ┌ Step 1 — 업로드 ─────┐ → ┌ Step 2 — 라벨링 ─────┐              │  │ │
│ │ │ │ [Upload icon orange]   │   │ [Tag icon orange]      │              │  │ │
│ │ │ │ "영상 또는 이미지를     │   │ "업로드한 데이터에      │              │  │ │
│ │ │ │  업로드하세요"         │   │  라벨을 추가하세요"     │              │  │ │
│ │ │ │ <UploadDropzone />     │   │ "현재 라벨링 필요 N건"  │              │  │ │
│ │ │ │                        │   │ [Play 시작하기 (orange)]│              │  │ │
│ │ │ └────────────────────────┘   └────────────────────────┘              │  │ │
│ │ └────────────────────────────────────────────────────────────────────┘  │ │
│ │ "※ 다운로드는 포털 자체 시스템에서 별도 제공됩니다."                     │ │
│ └────────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│ ┌─ 내 업로드 목록 ─────────────────────────────────────────────────────┐ │
│ │ ["내 업로드 목록" 라벨]   (totalElements > 8) [전체 보기]              │ │
│ │ ┌ grid-cols-2 sm:grid-cols-4 gap-3 ──────────────────────────────┐    │ │
│ │ │ ┌ Card (썸네일 + 정보) ┐                                          │    │ │
│ │ │ │ [picsum aspect-video]│                                          │    │ │
│ │ │ │ {cctvName truncate}  │                                          │    │ │
│ │ │ │ [StatusBadge 작은] │                                          │    │ │
│ │ │ │ [COMPLETED=결과보기 │                                          │    │ │
│ │ │ │  / 그 외=라벨링 orange]│                                        │    │ │
│ │ │ └────────────────────────┘                                        │    │ │
│ │ └────────────────────────────────────────────────────────────────┘    │ │
│ │ (빈 상태) "업로드한 데이터가 없습니다..."                              │ │
│ └────────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────┘
```

**Hero 섹션** (`bg-gradient-to-br from-orange-500 to-orange-600 text-white py-10 px-6`)
- 제목 `text-2xl font-bold` "AI 학습데이터 작성 포털"
- 부제 `text-orange-100 text-sm` "영상/이미지 업로드, 간편 라벨링, 오토라벨링 체험"

**내 현황 섹션**
- 라벨: `text-sm font-semibold text-gray-500 uppercase tracking-wide` "내 현황"
- KPI 2개 (`grid-cols-2 gap-4`, `bg-white rounded-xl shadow-sm border p-4`):
  - 업로드 건수: `text-xs text-gray-500` + `text-2xl font-bold` `{me?.uploadCount ?? 0}` + "건"
  - 라벨링 완료: 동일 구조 `{me?.labeledCount ?? 0}`
- 데이터 소스: `useFetch<PortalUserDto>('/portal/me')` (`refetchMe` 업로드 완료 시)
- 로딩: `h-20 bg-gray-200 rounded-xl animate-pulse` 2개

**이용 방법 섹션** (2단계 카드, `flex-col md:flex-row gap-4`)
- **Step 1 — 업로드**: `Upload 16 orange-500` + "1" 원형 + "업로드" + 안내 + `<UploadDropzone onUploadComplete={refetchMe} />`
- 화살표 (`ArrowRight 20 hidden md:flex text-gray-300`)
- **Step 2 — 라벨링**: `Tag 16 orange-500` + "2" 원형 + "라벨링" + 안내 + `"현재 라벨링 필요 {pendingCount}건"` + `[Play 14 시작하기 bg-orange-500]` (firstVideoId 없으면 disabled)
- 하단 안내: `mt-3 text-xs text-gray-500` "※ 다운로드는 포털 자체 시스템에서 별도 제공됩니다."

**내 업로드 목록 섹션** (`id="my-uploads"`)
- 라벨 + (`totalElements > 8`이면) 우측 `[text-xs text-orange-600 hover:underline "전체 보기"]`
- 그리드 `grid-cols-2 sm:grid-cols-4 gap-3` (반응형: 모바일 2칼럼, 태블릿+ 4칼럼)
- 카드: `bg-white rounded-xl shadow-sm border overflow-hidden flex flex-col`
  - 썸네일: `aspect-video bg-gray-200 picsum/seed/video-{id}/320/180`
  - 정보: `p-2` 영역 — `text-xs font-medium truncate` 이름 + `StatusBadge` + (taskStatus===`COMPLETED`이면) `[border-gray-200 결과 보기]` / 그 외 `[bg-orange-500 Play 라벨링]`
- 로딩: `h-32 bg-gray-200 animate-pulse` 4개
- 빈 상태: `text-center py-12 text-gray-400 text-sm` "업로드한 데이터가 없습니다. 위에서 파일을 업로드해보세요."

**StatusBadge 매핑 (포털 전용 — `PortalMain.tsx:7~24`)**
| status | 라벨 | tone |
|:------|:----|:----|
| `BATCH_COMPLETED`, `PENDING` | "업로드완료" | `bg-blue-100 text-blue-700` |
| `IN_PROGRESS`, `REVIEW_PENDING`, `REVIEW` | "라벨링중" | `bg-yellow-100 text-yellow-700` |
| `COMPLETED` | "완료" | `bg-green-100 text-green-700` |
| `REJECTED` | "반려" | `bg-red-100 text-red-700` |

**전반 톤**
- 주황 액센트 (`bg-orange-500`, `text-orange-100`, `bg-orange-100 text-orange-600`)
- 회색 배경(`bg-gray-50`) — 메인 컨테이너
- **다운로드 버튼/카드/D-day 없음** (V1.5 — 다운로드는 포털 자체 책임)
- 업로드 TUS 프로토콜(재개 가능 업로드) — CVAT portable-modules/03 포팅 (백엔드 책임)

#### SCR-PORTAL-002 · 포털 라벨링

mock 구현 = `PortalLabelEditor.tsx`. **`LabelEditor`에 `portalMode={true}`를 주입한 래퍼 컴포넌트**.

- 도구: 바운딩박스 / 폴리곤 / 세그멘테이션 + 오토라벨링 체험(YOLO+SAM2)
- **VLM / 버전관리 / 검수 미노출** (히스토리·검수 제출 버튼 미렌더)
- 본인 업로드 데이터만 로드/편집 가능 (백엔드 권한 가드)

---

### 4-13. 관리 · 사용자 관리

#### SCR-MANAGE-001 · 사용자 관리

mock 구현 = `UserManagement.tsx`. REVIEWER 전용 (V1.3 — ADMIN 통합).

```
┌────────────────────────────────────────────────────────────────────────────┐
│ [▣ Users gray-100] 사용자 관리                                             │
│                    전체 N명                                                 │
│                                                                            │
│ ┌─ 필터 (flex-wrap items-center gap-3) ────────────────────────────────┐ │
│ │ [🔍 검색 이름/이메일 (Enter)] [역할▾] [상태▾]                          │ │
│ └────────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│ ┌─ Table 6컬럼 ─────────────────────────────────────────────────────────┐ │
│ │ 이름(아바타 원형) │ 이메일 │ 역할(Badge) │ 상태(Badge) │ 최근 로그인 │ 관리│ │
│ ├───────────────────┼────────┼─────────────┼─────────────┼─────────────┼────┤ │
│ │ [🅰] 김검수       │ ...    │ [REVIEWER]  │ [활성]      │ YYYY-MM-DD  │ [수정][비활성화] │ │
│ │ [🅱] 이작업       │ ...    │ [WORKER]    │ [비활성]    │ YYYY-MM-DD  │ [수정][활성화]   │ │
│ └────────────────────────────────────────────────────────────────────────┘ │
│                                       [< 1 2 3 ... >]                       │
└────────────────────────────────────────────────────────────────────────────┘
```

**헤더 영역**
- `Users 20` 아이콘(`w-9 h-9 bg-gray-100 rounded-lg`, `text-gray-600`) + `text-xl font-bold` "사용자 관리" + 부제 `text-xs text-gray-400` "전체 {totalElements}명"

**필터** (`flex flex-wrap items-center gap-3`)
- 검색 input: `flex items-center border rounded-lg px-3 py-2 + Search 14 icon` + placeholder "이름 또는 이메일 검색" — Enter 키로 적용
- 역할 select: `REVIEWER` / `WORKER` / `PORTAL_USER` / 전체
- 상태 select: `ACTIVE` / `INACTIVE` / 전체

**컬럼 6개** (`UserDto` — `{ id, name, email, role, status, lastLoginAt }`)
| # | key | header | width | 셀 |
|:-:|:----|:------|:------|:--|
| 1 | `name` | 이름 | — | 아바타(`w-7 h-7 rounded-full` 색상=`avatarColorFromId(id)`, 이니셜 = name[0]) + `font-medium text-gray-800` 이름 |
| 2 | `email` | 이메일 | — | `text-gray-500 text-xs` |
| 3 | `role` | 역할 | — | `Badge tone=ROLE_TONE size=sm "{ROLE_LABEL}"` (REVIEWER/WORKER/PORTAL_USER) |
| 4 | `status` | 상태 | — | `Badge tone=STATUS_TONE size=sm` "활성" / "비활성" (ACTIVE/INACTIVE) |
| 5 | `lastLoginAt` | 최근 로그인 | — | `text-xs text-gray-400 ko-KR YYYY-MM-DD` |
| 6 | `actions` | 관리 | — | `[Button ghost sm "수정"] [Button ghost sm 색상별 "비활성화|활성화"]` |

**액션 동작**
- `[수정]` → `UserEditModal` (역할/상태 변경, `handleEditOpen(u)`)
- `[비활성화|활성화]` — 현재 상태에 따라 라벨 변경:
  - ACTIVE: "비활성화" (`text-red-500 hover:bg-red-50`)
  - INACTIVE: "활성화" (`text-green-600 hover:bg-green-50`)
- `handleToggleStatus(u)` → `PUT /api/v1/users/{id}` 상태 토글

**페이징**
- `PAGE_SIZE = 10` (mock 기준)
- 서버 필터링: `/users?role={role}&status={status}&q={search}&page={page}&size=10`

#### SCR-MANAGE-002 · 시스템 설정

mock 구현 = `SystemSettings.tsx`. REVIEWER 전용. **3섹션 구조**.

```
┌────────────────────────────────────────────────────────────────────────┐
│ [Settings] 시스템 설정                                                 │
│                                                                        │
│ ━━ 섹션 1: 편집 가능 — DB 영속화 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│ ┌─ FFmpeg 설정 (md:grid-cols-2 카드) ───┐ ┌─ 배치 처리 ────────────┐ │
│ │ 스레드 수    [───●───] 1~16     [저장] │ │ 처리 주기 [─●─] 10~300 │ │
│ │ 추출 fps     [▾ 1/2/5/10]              │ │ 동시 처리수[●─] 1~8    │ │
│ │                                         │ │                  [저장]│ │
│ └─────────────────────────────────────────┘ └────────────────────────┘ │
│                                                                        │
│ ━━ 섹션 2: 실시간 모니터링 (read-only) ━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│ ┌─ 외부 연동 상태 ──────────────────────────────[👁 실시간 모니터링]┐ │
│ │ [Wi-Fi] 관제서버    control.example.com  12ms   [정상]            │ │
│ │ [Wi-Fi] 포털서버    portal.example.com    8ms   [정상]            │ │
│ │ [Wi-Fi] AI서버      localhost:9300       45ms   [정상]            │ │
│ │ [Wi-Fi] Gitea       gitea.example.com    22ms   [정상]            │ │
│ │ ─ "이 항목은 actuator/health에서 실시간 조회되며 편집할 수 없습니다." │ │
│ └─────────────────────────────────────────────────────────────────┘ │
│                                                                        │
│ ━━ 섹션 3: 위험 액션 (운영 도구 이관 예정 — placeholder) ━━━━━━━━ │
│ ┌─ 위험 구역 ─────────────────────────────────────────────────────┐ │
│ │ [⚠ 안내 배너] 위험 액션은 별도 운영 도구로 이관 예정.            │ │
│ │ [시스템 초기화] [배치 큐 초기화] [캐시 삭제]                     │ │
│ │ 모두 confirm() + toast (시연용 동작만)                           │ │
│ └─────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

**섹션 1 — 편집 가능 (DB 영속화)**
- FFmpeg: 스레드 수(1~16 슬라이더, accent-primary), 출력 fps(1/2/5/10 select)
- 배치: 처리 주기(10~300초 슬라이더), 동시 처리 수(1~8 슬라이더)
- 카드별 **개별 저장 버튼** (dirty 추적, 변경 없으면 disabled)
- API: `PUT /manage/settings/ffmpeg`, `PUT /manage/settings/batch`

**섹션 2 — 실시간 모니터링 (read-only)**
- **4개 외부 시스템** (`mock/src/mocks/handlers/manage.ts:45~50`): 관제서버 / 포털서버 / AI서버 / Gitea
- 카드 헤더 우측: `[Eye 12 + "실시간 모니터링"]` 배지 (`bg-blue-50 border-blue-100`)
- 각 row: `Wifi 16` 또는 `WifiOff 16` 아이콘 + 시스템명 + URL(`text-xs truncate max-w-200`) + 레이턴시(`Clock 11 + {N}ms`) + 상태 배지
- 상태 로직 (mock 2상태 + 지연 분기):
  - `CONNECTED` + `latencyMs <= 100`: `bg-green-50 border-green-200`, 정상 (green)
  - `CONNECTED` + `latencyMs > 100`: `bg-yellow-50 border-yellow-200`, 지연 (yellow)
  - `DISCONNECTED`: `bg-red-50 border-red-200`, 연결 끊김 (red), `WifiOff` 아이콘
- 카드 푸터 안내: `text-xs text-gray-400 Eye 12` "이 항목은 actuator/health에서 실시간 조회되며 편집할 수 없습니다."

**섹션 3 — 위험 액션 (placeholder)**
- 안내 배너: "위험 액션은 별도 운영 도구로 이관 예정. 데모 동작만 수행."
- 버튼 3종: 시스템 초기화 / 배치 큐 초기화 / 캐시 삭제
- 모두 `window.confirm` + toast — 실제 동작 없음 (시연용)

---

## 5. 사용자 플로우

### 5.1 라우트 트리 (`mock/src/routes/routes.ts` 기준)

| 경로 | 화면 | 메뉴 노출 | 역할 |
|:----|:----|:--------:|:----|
| `/` | `HomeRedirect` | × | 모두 |
| `/dashboard` | 대시보드 | O | REVIEWER, WORKER |
| `/video/completed` | 영상 처리 현황 | O | REVIEWER, WORKER |
| `/video/:id` | 영상 상세 | × | REVIEWER, WORKER |
| `/task` | 작업 목록 | O | REVIEWER, WORKER |
| `/label/:id` | 라벨링 | × | REVIEWER, WORKER |
| `/review/pending` | 검수 목록 | O | REVIEWER |
| `/review/:id` | 검수 화면 | × | REVIEWER |
| `/history/:id` | 버전관리 | × | REVIEWER, WORKER |
| `/augment/request` | 증강 요청 | O | REVIEWER |
| `/augment/result/:id` | 증강 결과 | × | REVIEWER |
| `/portal` | 포털 메인 | (포털 셸) | PORTAL_USER |
| `/portal/label/:id` | 포털 라벨링 | × | PORTAL_USER |
| `/stat/worker` | 작업자 통계 | O | REVIEWER, WORKER |
| `/stat/overall` | 전체 구축 현황 | O | REVIEWER |
| `/manage/users` | 사용자 관리 | O | REVIEWER |
| `/manage/settings` | 시스템 설정 | O | REVIEWER |
| `/preset` | 프리셋 관리 | O | REVIEWER |

### 5.2 핵심 플로우

#### 플로우 A — 라벨링 작업자 (WORKER)

```
관제서버 JWT 인계
   │
   ▼
HomeRedirect → /dashboard
   │
   ▼ KPI 4개 + 내 작업 현황 카드
   │
   ▼  "내 작업"의 [영상명] 클릭 또는 LNB 작업 목록
   │
TaskList (/task)  ← assigneeId=me 자동 필터
   │
   ▼ 행 [▶] 클릭
   │
LabelEditor (/label/:id)
   │
   ▼ 라벨링 → Ctrl+S 저장 → 자동 Gitea 커밋
   │
   ▼ 헤더 [검수 제출]
   │
TaskList ← 상태 REVIEW_PENDING
```

#### 플로우 B — 검수자 (REVIEWER)

```
관제서버 JWT 인계 → /dashboard (KPI 3개)
   │
   ▼ LNB "검수 목록"
   │
ReviewPending (/review/pending) ← KPI 4 카드
   │
   ▼ PENDING 행 [검수 시작 ▶]
   │
ReviewEditor (/review/:id)
   │
   ▼ 이슈 등록 → 의견 작성 → [승인] 또는 [반려]
   │
ReviewPending ← 상태 갱신
```

#### 플로우 C — 일괄 배정 (REVIEWER)

```
TaskList → 행 체크박스 N개 선택
   │
   ▼ 다중 액션바 [일괄 배정]
   │
AssignModal mode='bulk'
   │  - 작업자 기본값=currentUser.id
   │  - 검수자 자동
   │
   ▼ [배정] → POST /tasks/bulk-assign (멱등)
   │
toast "N건 배정 완료, M건 변경 없음"
```

#### 플로우 D — 증강 → 결과 검수 (REVIEWER)

```
LNB "증강 요청" → AugmentRequest (/augment/request)
   │
   ▼ 1. 유형 선택 (WINTER/NIGHT/RAIN/RESOLUTION)
   ▼ 2. 영상 선택 (검수 완료 가드)
   ▼ 3. [요청 시작]
   │
   ▼ 최근 이력 5초 polling → COMPLETED
   │
[결과 보기] → AugmentResult (/augment/result/:jobId)
   │
   ▼ 영상별 섹션 → 12프레임 라디오 → 50:50 비교
   │
[활용 채택] 또는 [거부 (사유)]
```

#### 플로우 E — 포털 사용자 (PORTAL_USER)

```
포털 JWT 인계 → HomeRedirect → /portal
   │
PortalMain
   │
   ▼ STEP 1 [업로드 (TUS)]
   │
   ▼ "내 업로드 목록" 행 [라벨링]
   │
PortalLabelEditor (/portal/label/:id)  ← LabelEditor + portalMode=true
   │  - 히스토리/검수 제출 미노출
   │  - 오토라벨링 체험 노출
   │
   ▼ 라벨링 → 저장 (Gitea 커밋 X)
   │
PortalMain ← KPI 갱신
```

#### 플로우 F — (V2 폐기) 학습데이터 내보내기

V2에서 본 플로우는 폐기되었다. 저작도구 내 "내보내기" 화면(`/export`, `ExportPage.tsx`)이 제거되었으므로 사용자 플로우 자체가 존재하지 않는다.

**V2 대체 모델**: 외부 시스템이 학습데이터 API(`GET /api/v1/datasets/videos?merge=true&format=COCO`)를 호출하여 학습데이터 JSON을 직접 가져간다. 저작도구 사용자 화면은 검수 승인까지만 책임지며, 외부 노출은 외부 시스템(데이터마트·외부 학습 시스템)의 자동화된 잡으로 처리된다.

#### 플로우 G — (V2 신규) 라벨러 비식별 누락 신고 → 비식별 재시도 (WORKER)

```
LabelEditor (/label/:id) ← 비식별 프레임 검수 중
   │
   ▼ 우측 패널 [🚨 비식별 누락 신고] 클릭
   │
[누락 신고 모달]
   ▼ 1. 누락 위치 마킹 (옵션 — 캔버스 클릭)
   ▼ 2. 누락 유형 선택 (얼굴 / 번호판 / 기타)
   ▼ 3. 코멘트 입력
   ▼ 4. [신고] 클릭
   │
POST /api/v1/videos/{id}/deident-report
   │  → BATCH_STAGE_CD='DEIDENTIFY'로 영상 단위 되돌림
   │  → DE_IDNTF_RETRY_CNT+=1, DE_IDNTF_LAST_FAIL_REASON='라벨러 신고 — {누락 유형}'
   │  → 비식별 재시도 큐 적재
   │  → LS_LABELER_REPORT INSERT
   │
영상이 LOCKED 상태 → 캔버스 편집 차단 + 안내 배너
   │
   ▼ (비동기) 외부 비식별 서버 재호출
   │
재시도 결과
   ├ 성공 → 토스트 + LOCKED 해제 + 비식별 프레임 자동 새로고침 → 작업 재개
   └ 실패 → 토스트 + 검수자 알림 (LOCKED 유지)
```

---

## 6. 접근성 · 반응형 정책

### 6.1 접근성 (WCAG 2.1 AA 목표)

mock의 현재 수준:
- `aria-label` 속성: 16곳 적용 (모달 닫기, 햄버거, 카드 액션 등)
- 포커스 링: 모든 인터랙티브 요소에 `focus:ring-2 focus:ring-primary-500` 적용
- 키보드 네비게이션: 라벨링 단축키 11종 (B/P/S/T/E/Del/←/→/Ctrl+Z/Ctrl+S/+/−)
- 모달: ESC 닫기 / 첫 입력 자동 포커스 (포커스 트래핑은 부분 구현)

**(향후 검증)**
- 색상 대비 4.5:1 검증 미시행 — 디자인 QA 단계에서 측정 필요
- 스크린 리더 동작 검증 미시행 (VoiceOver / NVDA)
- `tab` 순서 일관성 — 페이지별 ad-hoc, 표준 검사 미수행
- a11y 자동 검사 (axe-core / lighthouse) 도입 — **(미구현 — 후속 적용)**

### 6.2 반응형 정책

| 셸 | 모바일 | 태블릿 | 데스크톱 |
|:--:|:------:|:------:|:--------:|
| AppShell (내부) | × (1024px+ 가정) | △ (LNB 고정 240px로 협소) | O |
| PortalShell | O (햄버거→드로어) | O | O |

- AppShell: 데스크톱 우선. 모바일/태블릿 정식 지원 없음 — 좁은 뷰포트에서는 가로 스크롤 발생 가능
- PortalShell: 모바일 우선 적용 (`md:` 분기) — 햄버거 메뉴, 카드 그리드 자동 재배치

### 6.3 다국어

mock은 한국어 단일 — i18n 구조 없음. 다국어 요구는 V2 범위.

---

## 7. 화면 ID 인덱스

활성 화면 24개 — SFR-08(저작도구 핵심) 전제. 각 항목은 mock 구현 파일 경로와 함께 표기한다. V2에서 SCR-EXPORT-001이 폐기되어 1개 감소했다.

| SCR ID | 화면명 | mock 파일 | 역할 |
|:------|:------|:--------|:----|
| SCR-VIDEO-001 | 영상 처리 현황 목록 | `pages/batch/BatchCompletedList.tsx` | REVIEWER, WORKER |
| SCR-VIDEO-003 | 영상 상세 (탭 3개) | `pages/batch/VideoDetail.tsx` | REVIEWER, WORKER |
| SCR-DASH-001 | 메인 대시보드 | `pages/Dashboard.tsx` | REVIEWER, WORKER |
| SCR-PRESET-001 | 프리셋 목록 | `pages/preset/PresetList.tsx` | REVIEWER |
| SCR-PRESET-002 | 프리셋 폼 모달 | `components/preset/PresetFormModal.tsx` | REVIEWER |
| SCR-TASK-001 | 작업 목록 | `pages/task/TaskList.tsx` | REVIEWER, WORKER |
| SCR-TASK-002 | 배정 모달 | `components/task/AssignModal.tsx` | REVIEWER |
| SCR-LABEL-001 | 라벨링 도구 메인 | `pages/label/LabelEditor.tsx` | REVIEWER, WORKER |
| SCR-LABEL-002 | 라벨링 객체/속성 패널 | `components/label/{ObjectTree,AttributePanel}.tsx` | REVIEWER, WORKER |
| SCR-AUTO-001 | 오토라벨링 결과 요약 | `components/auto/AutoLabelSummary.tsx` | REVIEWER, WORKER |
| SCR-AUTO-002 | 시계열 메타데이터 검토 | (외부 메타 표시 패널 — 미구현 — 후속 적용) | REVIEWER, WORKER |
| SCR-HIST-001 | 버전 히스토리 | `pages/history/VersionHistory.tsx` | REVIEWER, WORKER |
| SCR-HIST-002 | 롤백 모달 | `components/history/RollbackModal.tsx` | REVIEWER, WORKER |
| SCR-REVIEW-001 | 검수 대기 목록 | `pages/review/ReviewPending.tsx` | REVIEWER |
| SCR-REVIEW-002 | 검수 화면 | `pages/review/ReviewEditor.tsx` | REVIEWER |
| SCR-REVIEW-003 | 검수 결과 (read-only) | `pages/review/ReviewEditor.tsx?readonly=true` | REVIEWER |
| SCR-STAT-001 | 작업자 통계 | `pages/stat/WorkerStats.tsx` | REVIEWER, WORKER |
| SCR-STAT-002 | 전체 구축 현황 | `pages/stat/OverallStats.tsx` | REVIEWER |
| SCR-AUG-001 | 증강 요청 | `pages/augment/AugmentRequest.tsx` | REVIEWER |
| SCR-AUG-002 | 증강 결과 | `pages/augment/AugmentResult.tsx` | REVIEWER |
| SCR-PORTAL-001 | 포털 메인 | `pages/portal/PortalMain.tsx` | PORTAL_USER |
| SCR-PORTAL-002 | 포털 라벨링 | `pages/portal/PortalLabelEditor.tsx` | PORTAL_USER |
| SCR-MANAGE-001 | 사용자 관리 | `pages/manage/UserManagement.tsx` | REVIEWER |
| SCR-MANAGE-002 | 시스템 설정 | `pages/manage/SystemSettings.tsx` | REVIEWER |

### 폐기된 SCR ID (참조 금지)

다음 ID는 정책상 폐기되었으며 본 문서 본문에서 다루지 않는다. 변경 사유는 `저작도구_FINAL_변경사항_2026-05-07.md` 및 `저작도구_FINAL_변경사항_2026-05-13.md` 참조.

- `SCR-VIDEO-002` — 단독 영상 정보 화면 → SCR-VIDEO-003 정보 탭으로 통합
- `SCR-DEIDENT-001` / `SCR-DEIDENT-002` — 비식별 전용 결과 검토 화면 폐지. V2에서 비식별이 자동 파이프라인 무조건 단계로 부활했으나 결과 검토 UI는 미제공, 라벨러 [비식별 누락 신고]로 갈음
- `SCR-GEN-001` / `SCR-GEN-002` — 생성형 AI 본체 화면 폐지 (SFR-06 외부 이관)
- **(V2)** `SCR-EXPORT-001` — 학습데이터 내보내기 화면 폐지. 외부 시스템 학습데이터 API로 대체 (저작도구 사용자 화면 없음)

---

*문서 버전: FINAL (mock 기준 전면 재작성) | 작성일: 2026-05-11 | 기준 mock commit: `df2ac0f`*
