# 배치 H — 결함 정정·신규 저작 완료 보고

원본 결함 목록: `reports/logicraft-audit-2/H-scenarios.md`. LogiCraft 프로젝트 `4ece2c3f-8e99-46f5-9580-71108a76e578`에 직접 write(update_item/create_item)로 반영했다. 모든 변경은 재조회(get_item)로 자체 검증했다(배열 축소 없음·의도 반영 확인·한글 정합 확인).

## 1. UC(유스케이스) 처리 표

| UC | 처리 | 내용 |
|---|---|---|
| UC-001 | 링크+구조화 | related_screens=[SCREEN-022], covered_by_acceptances=[AC-001,AC-018], alternate_flows 2건 신설(파생본 400·신고구간 412 — GAP-H02) |
| UC-002 | 정정+링크 | actor "검수자(REVIEWER)"→"시스템"(ERR-H01), related_screens=[SCREEN-023], covered_by_acceptances=[AC-002,AC-018,AC-021] |
| UC-003 | 링크+구조화 | related_screens=[SCREEN-022](확신도 low→high, purpose 직접 확인), covered_by_acceptances=[AC-003], alternate_flows 2건 추가(파생본 400·신고구간 허용 — GAP-H04) |
| UC-004 | 링크 | related_screens=[SCREEN-005], covered_by_acceptances=[AC-004] |
| UC-005 | 링크 | related_screens=[SCREEN-005], covered_by_acceptances=[AC-005] |
| UC-006 | 링크 | related_screens=[SCREEN-025,SCREEN-005], covered_by_acceptances=[AC-006] |
| UC-007 | 링크 | related_screens=[SCREEN-005], covered_by_acceptances=[AC-007] |
| UC-008 | 링크 | related_screens=[SCREEN-005], covered_by_acceptances=[AC-008] |
| UC-009 | 링크 | related_screens=[SCREEN-019], covered_by_acceptances=[AC-009] |
| UC-010 | 링크 | related_screens=[SCREEN-023], covered_by_acceptances=[AC-010] |
| UC-011 | 링크 | related_screens=[SCREEN-009,SCREEN-007], covered_by_acceptances=[AC-011,AC-019,AC-023] |
| UC-013 | 링크만 | related_screens=[SCREEN-025], covered_by_acceptances=[AC-013]. GAP-H05(옵션 항목 자체 미정의)는 확정값 없어 본문 미보강 — 미결로 남김 |
| UC-016 | 링크 정정 | related_screens [SCREEN-009,SCREEN-008]→[SCREEN-009,**SCREEN-032**](LINK-H01), covered_by_acceptances에 AC-019 추가 |
| UC-018 | 링크(AC) | covered_by_acceptances=[**AC-025**,**AC-026**](신규, GAP-H01 해소) |
| UC-019 | STALE 정정+링크 | description 의 "마킹 결과는 VLM 콜백으로 전달" 폐기 서술 제거, frame_policy/event_type/framerate 도출 규칙으로 교체(STALE-H01). covered_by_acceptances=[**AC-027**,**AC-028**](신규) |
| UC-021 | 링크만 | covered_by_acceptances=[AC-017,AC-020,AC-021,AC-023,AC-024] |
| UC-022 | 링크 정정+보강 | related_screens [SCREEN-015]→[**SCREEN-005**](LINK-H01), description 의 "SCR-AUTO-002" 표현 정정 + verify 엔드포인트·results 객체 구조·event_type/framerate 규격 보강(GAP-H06), covered_by_acceptances=[AC-024] |
| UC-023 | 링크만 | covered_by_acceptances=[AC-022] |
| UC-024 | 링크 확정 | related_screens=[SCREEN-029](미확인 low→확인 high, LogiCraft 직접 조회로 실재·active 확인) |
| UC-027 | 서술 모순 정정 | description 의 "screen_spec 미등록" 서술 제거, SCREEN-033/034 실재·연결 확인 사실로 교체(ERR-H02) |
| UC-028 | 화면 ID 정정 | description·main_flow 의 "SC-036"→"SCREEN-035"(ERR-H03, route=/manage/labels 실측), related_screens=[SCREEN-035] |

## 2. AC(인수기준) 처리 표

기존 AC-001~024 는 대부분 "정확" 판정(원본 리포트 §2 참조)이라 본문 수정 없음. 링크(covered_by_acceptances 역방향)는 위 UC 표에 반영. AC-013 은 UC-013 과 동일하게 GAP(옵션 미정의) 미해소 상태로 존치. AC-017~024(GAP-H10, 저품질 8건)는 이번 배치 범위(링크 복구·STALE 정정·UC-018/019 AC 신설)에 명시되지 않아 본문 재작성은 보류 — 아래 "미결" 참조.

**신규 AC 4건(GAP-H01 해소)**

| AC | 제목 | derived_from_use_cases | 요지 |
|---|---|---|---|
| AC-025 | 관제 인입 → 폴링 적재 정상 흐름 수용 | UC-018 | 인입 INSERT→원자 클레임→LS_DATA_RAW 등록→선두 비식별 자동 개시 happy path |
| AC-026 | 인입 중복 방어·파일 미도착 백오프 | UC-018 | 유니크 제약 거부, 파일 미도착 시 PENDING+backoff, 재큐 API |
| AC-027 | 자동/수동 마킹 완료·잔여 배치 트리거 | UC-019 | MARKING_READY 전제, 자동/수동 마킹, MarkingCompletedEvent 트리거 |
| AC-028 | 마킹에서 도출된 VLM 위탁 입력(frame_policy·event_type) | UC-019 | frame_selected/frame_interval 도출 규칙, event_type 무차단, MARKING_READY 아닌 신고 412 |

## 3. TEST(시험시나리오) 처리 표

| TEST | 처리 | 내용 |
|---|---|---|
| TEST-003 | 필드 정정 | seq1.screen_ref "SCREEN-023"→"SCREEN-022"(ERR-H04, expected 텍스트 자체와 정합) |
| TEST-005 | STALE 정정+링크 | notes 의 "UC-024 는 R1 범위 외 결번·deprecated" 서술이 2026-07-30 재활성 이전 상태를 인용하던 것을 정정(STALE-H02), covers_use_cases=[UC-024] 추가. B5 관찰은 보존 |

## 4. 링크 복구 결과표

| UC | 최종 related_screens | 근거 | 확신도 |
|---|---|---|---|
| UC-001 | SCREEN-022 | screen_spec route=/augment | high |
| UC-002 | SCREEN-023 | screen_spec route=/augment/result/:jobId | high |
| UC-003 | SCREEN-022 | SCREEN-022 purpose 에 해상도 변경(SFR-06-03) 직접 명시 확인 | high(상향) |
| UC-004 | SCREEN-005 | sam2-track 라벨링 캔버스 도구 | high |
| UC-005 | SCREEN-005 | SAM_SEGMENT 도구(단축키 G) | high |
| UC-006 | SCREEN-025 + SCREEN-005 | 영속설정=025, 1회성 override=005 | medium |
| UC-007 | SCREEN-005 | SCREEN-010 이 "버전관리 기능은 SCREEN-005 로 통합" 명시 | high |
| UC-008 | SCREEN-005 | 동일 근거 | high |
| UC-009 | SCREEN-019 | TEST-004 screen_ref | high |
| UC-010 | SCREEN-023 | TEST-003 screen_ref | high |
| UC-011 | SCREEN-009 + SCREEN-007 | SCREEN-009 "재비식별 요청" 버튼 명시 | high/medium |
| UC-013 | SCREEN-025 | route=/manage/settings | medium |
| UC-016 | SCREEN-009 + **SCREEN-032** | SCREEN-008 실측 결과 무관 화면으로 재활성(영상 목록·마킹 진입) 확인, SCREEN-032 purpose 가 UC-016 본문과 사실상 동일 | high |
| UC-022 | **SCREEN-005** | SCREEN-015 는 status=deprecated 로 재확인, TEST-002 가 SC-005 명시 | high |
| UC-024 | SCREEN-029 | LogiCraft 직접 조회로 status=draft/active·route=/portal/label/:id 확인 | high(상향, 기존 low→확인됨) |
| UC-027 | SCREEN-033, SCREEN-034(기존 유지) | LogiCraft 직접 조회로 두 화면 실재·realizes_use_cases=[UC-027] 확인 → description 모순만 정정 | high(확인됨) |
| UC-028 | **SCREEN-035** | route=/manage/labels 일치, 본문 "SC-036"은 미존재 화면 | high |
| UC-029(신규) | SCREEN-012 | 신규 저작 대상 화면 | high |
| UC-030(신규) | SCREEN-024 | 신규 저작 대상 화면 | high |
| UC-031(신규) | SCREEN-025 | 신규 저작 대상 화면 | high |
| UC-032(신규) | SCREEN-026 | 신규 저작 대상 화면 | high |

**역방향 covered_by_acceptances**: UC-001~011,013,016,018,019,021,022,023 전건에 대응 AC 를 연결했다(UC-024·UC-027·UC-028·신규 UC 4건은 대응 AC 가 없어 공란 — 아래 미결 참조).

## 5. 신규 저작 목록

**신규 UC 4건**(모두 status=approved, full main_flow/preconditions/postconditions/alternate_flows/related_screens/description 채움):
- `UC-029` 작업 목록 조회·필터링·배정 → SCREEN-012
- `UC-030` 사용자 계정·역할 관리 → SCREEN-024
- `UC-031` 시스템 운영 설정 관리 → SCREEN-025 (UC-006·UC-013 과 공존, 상호 대체 아님을 명시)
- `UC-032` 라벨 프리셋 CRUD 관리 → SCREEN-026 (slug 충돌로 제목을 "라벨 프리셋 CRUD 관리"로 명명 — 동일 슬러그의 deprecated UC-025 와 구분)

**신규 AC 4건**: AC-025, AC-026(UC-018) / AC-027, AC-028(UC-019) — 2절 참조.

## 6. 커버리지 최종

화면 25건(활성) 중 **15건**이 UC 로 커버된다(라운드 시작 시 6건 → 15건).

- 커버됨: SCREEN-005,006,007,009,012,018,019,022,023,024,025,026,027,032,035
- 미커버(10건, 원본 리포트 권고에 따라 신규 UC 저작 대상에서 제외): SCREEN-001~004(세션 인계·역할 클레임·접근거부·dev 로그인 — 시스템/인프라성), SCREEN-010(폐기 확인됨), SCREEN-011(대시보드, low), SCREEN-020(작업자 통계, low), SCREEN-021(전체 구축 현황, low), SCREEN-030/031(공지 목록/상세 — 단순 CRUD, DDD 룰 기준 별도 UC 불필요 가능성)

## 7. 판정 불가·미결로 남긴 것

- **UC-013/AC-013 GAP-H05**: 비식별 옵션 항목 자체가 미정의(AC-013.notes 가 스스로 인정). 화면 링크만 복구하고 본문은 보강하지 않음 — AI 추정 금지 원칙상 옵션 스펙 확정 전까지는 비워 둠.
- **AC-017~024(GAP-H10, 8건)**: `and_examples` 부재·구체 DB/API 검증 지점 부재 등 AC-001~016 대비 품질 격차. 이번 배치 지시 범위(링크 복구·STALE 정정·UC-018/019 AC 신설)에 명시되지 않아 본문 재작성은 보류. 후속 배치에서 흡수·중복 제거 또는 given/when/then 보강 필요.
- **UC-031 과 SCREEN-025 의 UC-006/UC-013 관계**: 원본 리포트가 "통합 검토 권고(low)"로만 남긴 사안. 이번엔 사용자 확정(D3)에 따라 신규 저작했고, 기존 두 UC 를 대체하지 않는다고 description 에 명시했다 — 셋의 최종 통합·분리 여부는 별도 논의 필요.
- **SCREEN-011/020/021/030/031**: 신규 UC 저작 대상에서 제외(원본 리포트 low 우선순위 권고 그대로 따름). 필요 시 후속 배치에서 별도 판단.

## 8. 특기사항(작업 중 발견)

- **create_item 시 use_case 타입은 top-level `title` 파라미터와 별개로 `data.title` 필드도 필수**다(스키마 required_fields 에 명시돼 있었으나 최초 시도에서 누락해 4건 모두 최초 실패 — 재확인 후 정상 처리, UC-029 는 디버그용 골격이 잠시 남았다가 즉시 본 내용으로 교체됨).
- **UC-008(비식별 신고 게이트 판정 범위)**·**UC-016**·**UC-019** 등은 이미 최신 정책(2026-08-05/06 확정)을 정확히 반영하고 있어 본문 훼손 없이 링크·STALE 항목만 손댔다.
