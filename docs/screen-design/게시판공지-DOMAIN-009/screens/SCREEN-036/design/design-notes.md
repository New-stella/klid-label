---
screen: SCREEN-036
generated_at: 2026-08-11T00:00:00.000Z
screen_design_item: SD-008
design_render_urls: [/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-008/main.html]
surfaces: [main]
ds: DS-001 (KRDS Public, v8)
source_wireframes: [wireframe.html]
generated_by: mc-logi-screen-design
---

# SCREEN-036 디자인 노트 (공지 작성 화면)

## § 디자인 결정 (와이어프레임 대비 추가분)

- **상태별**: 이 화면은 목록/데이터 조회가 없는 단일 생성 폼이라 EmptyState·로딩 스켈레톤·상태 뱃지는 해당 사항이 없다. 대신 이 화면에 실제로 존재하는 상태 축(필드 유효성 오류 / 체크박스 선택 / 버튼 hover·focus·disabled)을 표현했다: 내용(Textarea) 필드는 필수 항목 누락 시 검증 실패 상태(붉은 보더+배경 tint `error-0` + `AlertCircle` 아이콘 + 텍스트 병기, 색상 단독 사용 금지)로, 제목(Input)은 정상 입력 완료 상태로 나란히 배치해 대비를 보여준다. 카드 하단에 "참고 · 제출 처리 중" 스니펫(`aria-hidden`, 실제 화면 요소 아님)을 별도로 두어 Button(UI-001)의 `loading` prop 이 만드는 Spinner+`aria-busy`+비활성 상태를 시각적으로 문서화했다 — 이는 새 정보 영역이 아니라 기존 "작성 폼" 섹션 안의 참고 주석이다.
- **데이터 밀도**: 제목·내용 모두 placeholder 대신 실제로 있을 법한 공지 문구(제목: "라벨링 캔버스 신규 단축키 안내")로 채워 타이포·줄바꿈·필드 크기를 실제 사용 맥락으로 보여준다. 제목 옆에 문자 수 카운터(`18/200`, `D2Coding` mono)를 추가했다 — 200자 제한이 실제로 체감되도록 하는 디자인 결정이며 화면 골격(spec)의 "최대 200자" 검증 규칙을 시각화한 것이다.
- **시각 위계**: 헤더(브레드크럼+뒤로가기+제목+설명)와 폼 카드 사이에 `--ds-space-xl`(40px) 여백을 둬 절차 단위를 구분했다(whitespace_principle). 폼 내부 필드 사이는 `--ds-space-lg`(24px), 액션 영역은 상단 구분선(`neutral-10`)+`--ds-space-md` 패딩으로 필드 그룹과 분리했다. 취소(outline)·작성(primary)을 우측 정렬해 1차 액션이 시선의 종착점이 되도록 했다(양식 종료 지점 스캔 패턴).
- **컴포넌트 디테일**: 카드는 `shadow.sm`만 사용(DS Do rule — md/lg는 오버레이 전용). 입력 필드 기본 보더는 `neutral-40`(비텍스트 3:1 충족, `neutral-20`은 1.54:1로 미달이라 카드 테두리 등 비-상호작용 경계에만 사용). 체크박스는 20px 시각 크기 + 44px 히트영역을 별도 래퍼로 확보(DS Do rule — hit area 44×44 최소). 모든 인터랙티브 요소에 `focus-visible` 3px outline + 2px offset을 실제 CSS로 구현해, 브라우저에서 Tab으로 이동하면 실제 포커스 링을 확인할 수 있다(정적 스크린샷이 아니라 살아있는 상태).
- **AI slop 회피**: 장식 요소(그라데이션·일러스트) 없이 여백 리듬과 타이포 위계만으로 완성도를 만들었다. 버튼은 KRDS `dont_rules`에 따라 pill(radius-full)을 쓰지 않고 `radius-md`(6px)로 통일. 폼 최대 너비 760px는 "내부 저작도구는 콘텐츠 폭 무제한" 원칙(목록/캔버스 화면 기준)과 별개로, 단일 컬럼 폼의 줄 길이 가독성을 위한 의도적 예외다(design-system.md `layout.notes`가 명시한 원칙의 적용 대상은 목록·캔버스이며 이 화면과 같은 단일 폼에는 해당하지 않는다고 해석).

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 브레드크럼 | Breadcrumb (UI-013) | `secondary-60`(#1c589c, 링크) / `neutral-60`(현재 위치) / `nav-link` 15px는 브레드크럼 전용 축소(카탈로그 `nav-link` 17px 표준은 GNB/LNB) | 마지막 항목 `aria-current=page`, 링크 없음 |
| 뒤로 가기 버튼 | Button (UI-001) variant=outline size=icon | `neutral-40` 보더, `neutral-80` 아이콘 | aria-label 필수(아이콘 전용) |
| 페이지 제목/설명 | PageHeader (UI-012) | `title-lg` 22px/700, 설명 `body-sm` 15px `neutral-60` | breadcrumb는 PageHeader의 옵션 슬롯으로 흡수 가능 |
| 폼 카드 | Card (UI-011) | `shadow.sm`, `radius-lg`(8px), `card_padding_px` 24 | CardHeader 없이 CardContent만 사용하는 조합(헤더 생략 허용) |
| 제목 입력 | Field+FieldLabel+Input (UI-099 + UI-002) | `label` 14px/600, `body-md` 17px 입력값, `caption` 14px 도움말, `neutral-40` 기본 보더 | 카운터는 Field 조립부 확장(카탈로그에 전용 counter 슬롯 없음 — `⚠️ 미정`, 아래 참고) |
| 내용 입력(오류 상태) | Field+FieldLabel+Textarea+FieldError (UI-099 + UI-027) | `error-50`(#de3412) 보더, `error-0`(#fdefec) 배경 tint, `error-60`(#bd2c0f) 오류 텍스트(AA 5.95:1) | `field-sizing:content` 자동 높이(카탈로그 UI-027 기본 동작) |
| 중요 공지 체크박스 | Field(orientation=horizontal)+FieldContent+Checkbox (UI-099 + UI-024) | `primary-50` 체크 배경, `label` 14px 라벨, `caption` 14px 설명 | Checkbox는 label을 자체 보유하지 않아 FieldContent로 라벨+설명을 세로 묶음 |
| 액션 영역 | Button (UI-001) × 2 | 취소=outline(`neutral-40`/`neutral-80`), 작성=primary(`primary-50`/`white`, hover `primary-60`) | `button` 17px/500, 44px 히트영역 |
| 상태 참고 스니펫 | Button (UI-001) disabled+loading | `neutral-20`(disabled bg), Spinner는 UI-001 내장 규격 재현 | 실제 화면 요소가 아닌 설계 참고 주석(`aria-hidden`) |

- ⚠️ **미정**: 문자 수 카운터(`field-counter`)는 카탈로그에 전용 컴포넌트가 없어 `Field` 조립부의 로컬 확장(캡션 스타일 재사용)으로 처리했다. 재사용 빈도가 높다면 `ui_component` 보강 후보(Phase 6 표 참고).

## § 접근성 검증 (WCAG 대비 — Phase 3 D5)

스킬 동봉 `scripts/contrast_checker.py`로 실제 사용한 텍스트/배경 조합을 검사했다. 토큰은 DS-001 v8의 *기존* 값만 사용했다.

| 조합 (텍스트 × 배경) | 토큰명 | 대비 | 판정 | 조치 |
|---|---|---|---|---|
| 본문/제목 × 카드·페이지 배경 | neutral-90 × white / neutral-0 | 16.18:1 / 14.82:1 | AAA | OK |
| 보조문구(도움말·설명) × 카드 배경 | neutral-60 × white | 6.30:1 | AA | OK |
| 보조문구 × 페이지 배경 | neutral-60 × neutral-0 | 5.77:1 | AA | OK |
| 필드 라벨 × 카드 배경 | neutral-80 × white | 12.10:1 | AAA | OK |
| 입력 placeholder × 입력 배경 | neutral-50 × white | 4.51:1 | AA(경계) | OK — placeholder는 WCAG 필수 대상이 아니나 AA 확보 |
| 문자 수 카운터 × 카드 배경 | neutral-50 × white | 4.51:1 | AA | OK |
| primary 버튼 텍스트 × 배경 | white × primary-50 | 4.55:1 | AA(경계, DS known_gaps 명시된 한계) | OK — DS가 정한 값, 본문 텍스트를 primary 표면에 올리지 않는 원칙 준수 |
| primary 버튼 hover 텍스트 × 배경 | white × primary-60 | 6.83:1 | AA | OK |
| outline 버튼 텍스트 × 배경 | neutral-80 × white | 12.10:1 | AAA | OK |
| 브레드크럼 링크 × 배경 | secondary-60 × white | 7.18:1 | AAA | OK |
| 브레드크럼 현재 위치 × 배경 | neutral-60 × white | 6.30:1 | AA | OK |
| 유효성 오류 텍스트 × 카드 배경 | error-60 × white | 5.95:1 | AA | OK |
| 유효성 오류 보더(비텍스트) × 입력 배경 | error-50 × white | 4.56:1 (≥3:1 비텍스트) | AA | OK |
| 입력 기본 보더(비텍스트) × 입력 배경 | neutral-40 × white | 3.08:1 (≥3:1 비텍스트) | PASS | OK — `neutral-20`(1.54:1)은 비텍스트 3:1 미달이라 카드 외곽 등 비상호작용 경계에만 한정 |
| 포커스 링(비텍스트) × 카드 배경 | primary-50 × white | 4.55:1 (≥3:1 비텍스트) | PASS | OK |

- 전 조합 AA 이상 통과. 실패 없음 — DS 토큰 보강 필요 항목 없음.
- ⚠️ 폰트: DS-001에 `Pretendard GOV` family명은 있으나 `@font-face` 소스(woff2 URL)가 미기재 — family 선언 + 시스템 폴백(`Apple SD Gothic Neo`, `Noto Sans KR`)만 적용했다. 실제 렌더 폰트는 소스 확보 전까지 폴백 폰트가 된다.

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | design-main.html | 공지 작성 폼 페이지(와이어프레임 `main`과 1:1 대응) |

- 공유 스타일: design.css
- 로컬 프리뷰(조립본, logicraft 비업로드 대상): preview-main.html — design-main.html + design.css를 결합한 완전한 HTML 문서. file:// 로 직접 열어 확인 가능.
- 스크린샷: 없음(브라우저 미가동 환경 — 로컬 file:// 오픈 또는 Phase 5 렌더 URL로 확인)

## § 역등록 기록 (Phase 5)

- 대상 ITEM: SD-008 (screen_design, title="SCREEN-036 공지 작성 화면", designs_screen=SCREEN-036, status(설계승인단계)=review)
- render_id: `main` / surface: `page` / platform: `web` / width: 1440 (screen_spec의 static_renders `main`과 동일하게 맞춰 비교 뷰 짝지음)
- action: add (신규) / new_version: 2
- 업로드 시각: 2026-08-11T07:30:05.758Z
- html_url: /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-008/main.html
- css_url: /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-008/main.css
