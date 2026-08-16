---
logicraft_item: DOMAIN-003
type: domain
version: 12
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:51.136Z
status: NEW
prev_version: null
content_hash: c5eaf5898b0ef41bc975315725b98a565ae9cc57cec9695d299a12f2a733c6e7
stale: false
raw: ./_raw/DOMAIN-003.json
links:
  collaborates_with: ["[[DOMAIN-010]]"]
  migrated_from: ["[[LEGACY-003]]"]
  applies_to_backward: ["[[NFR-008]]", "[[NFR-012]]"]
  belongs_to_domain_backward: ["[[ADR-042]]", "[[API-042]]", "[[API-043]]", "[[API-044]]", "[[API-045]]", "[[API-046]]", "[[API-084]]", "[[API-092]]", "[[API-114]]", "[[API-143]]", "[[API-144]]", "[[API-145]]", "[[API-146]]", "[[API-148]]", "[[API-150]]", "[[API-156]]", "[[API-158]]", "[[API-160]]", "[[API-162]]", "[[API-164]]", "[[API-167]]", "[[API-168]]", "[[API-170]]", "[[API-172]]", "[[API-173]]", "[[API-174]]", "[[API-181]]", "[[API-185]]", "[[API-186]]", "[[API-191]]", "[[API-192]]", "[[API-198]]", "[[API-199]]", "[[API-200]]", "[[API-201]]", "[[CDIAG-001]]", "[[CMP-001]]", "[[CMP-010]]", "[[DFEAT-007]]", "[[DFEAT-008]]", "[[DFEAT-009]]", "[[DFEAT-010]]", "[[DFEAT-011]]", "[[ERD-012]]", "[[ERD-020]]", "[[ERD-025]]", "[[EVT-002]]", "[[EVT-005]]", "[[SCREEN-008]]", "[[SCREEN-009]]", "[[SCREEN-038]]", "[[SD-004]]", "[[STATE-002]]"]
  collaborates_with_backward: ["[[DOMAIN-010]]"]
  derived_domain_backward: ["[[AC-025]]", "[[AC-026]]"]
  implements_in_backward: ["[[MOD-003]]", "[[MOD-041]]", "[[MOD-042]]", "[[MOD-045]]", "[[MOD-046]]", "[[MOD-048]]"]
  references_backward: ["[[TEST-001]]"]
---

# 영상·프레임 수집

## name

영상·프레임 수집

## brownfield

### notes

1차 도메인(II-001/002 배치). Pass 2에서 재플랫폼(Java) modified

### status

preserved

### legacy_source

#### type

module

#### legacy_artifact_id

LEGACY-003

## description

관제 영상을 저작도구로 들여오고 프레임을 추출해 라벨링 원천을 만드는 도메인.

[★적재 주체 반전 (ADR-042)] 저작도구가 관제 공유 테이블을 스캔하던 구조를 폐기한다. 관제서버가 학습용으로 설정한 영상을 LS_DATA_INGEST 에 직접 INSERT 하고, 저작도구 폴링 배치가 미처리 행을 원자 클레임해 LS_DATA_RAW 로 적재한다. 영상 관련 정보는 전부 이 인입 테이블에서 평면으로 받으며 관제 공유 마스터 조인은 하지 않는다. 이름값(CCTV명·지자체명·이벤트명·파일포맷)은 인입 행을 LEFT JOIN 해 얻으며, 파생영상은 자기 인입 행이 없으므로 ORGNL_RAW_SN 1단계 폴백으로 부모 행을 참조한다(파생 깊이가 1 로 고정돼 재귀가 필요 없다).

[파이프라인 순서] 적재(PENDING) → ★비식별화(선두, 전체 영상 자동) → MARKING_READY → 마킹(비식별 영상 대상) → VLM 시계열 → 프레임 추출 → YOLO → SAM2 → 트랙 보간.

[프레임 추출] FFmpeg 기반 배치(1건/분)이며 ★마킹 위치를 기준으로 원본·비식별 2벌을 추출한다(frames/raw|deid/{rawSn} 로 분기 저장돼 원본 덮어쓰기가 없다). 오토라벨링은 원본에만 실행하고 동일 해상도이므로 비식별본과 좌표를 공유한다.

[라벨링 캔버스 서빙] 프레임 이미지는 기본이 비식별본이며 원본은 REVIEWER 가 명시적으로 요청할 때만 나간다. 비식별 프레임을 여는 4경로는 동일 판정기로 검증하고 그 실경로를 NOFOLLOW 로 열어야 한다(심링크 교체로 마스킹 전 픽셀이 새는 것을 막는다).

[범위 밖] 포털 사용자 본인 자산 업로드는 LS_PORTAL_* 전용 경로로 본 도메인·데이터마트와 완전 분리된다. 구 관리화면 TUS 자체 업로드는 1차 적재 경로가 아니며 폐지 예정이다.

## upstream_of

_(empty)_

## context_kind

core

## collaborators

- DOMAIN-010

## integrates_with

_(empty)_

## uses_integrations

_(empty)_

## ubiquitous_language

### [1]

- **term**: 인입(LS_DATA_INGEST)
- **meaning**: 관제가 직접 INSERT 하는 평면 수신 테이블. 영상 관련 정보의 단일 창구이며 행을 삭제하는 코드가 없어 영구 보존된다

### [2]

- **term**: 원천(원시)데이터
- **meaning**: LS_DATA_RAW — 프레임 추출·라벨링의 원천이 되는 영상 단위. 작업 식별자 RAW_SN 이 곳 PK 다

### [3]

- **term**: 프레임
- **meaning**: FFmpeg 으로 추출된 개별 이미지. 원본·비식별 2벌이 각기 다른 경로에 저장된다

### [4]

- **term**: 파생영상
- **meaning**: 증강·해상도 변환으로 만들어진 새 영상. ORGNL_RAW_SN 으로 부모를 참조하며 깊이는 1 로 고정된다. 파생에는 원본영상이 없고 비식별본만 있다

### [5]

- **term**: 폴링 적재
- **meaning**: 인입 테이블의 미처리 행을 주기적으로 원자 클레임해 LS_DATA_RAW 로 옮기는 배치. 2노드 동시 적재를 조건부 UPDATE 로 막는다

### [6]

- **term**: 자동 분류
- **meaning**: 이벤트 유형·위치 등 인입 메타 기반 분류. 촬영환경(날씨·시간대·계절)은 자동 파생이 아니라 수동 입력이다
