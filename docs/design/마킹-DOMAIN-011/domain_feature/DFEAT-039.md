---
logicraft_item: DFEAT-039
type: domain_feature
version: 12
domain: DOMAIN-011
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-01T08:40:13.080Z
status: CHANGED
prev_version: 11
content_hash: ea0bee53b3e43a5779a504e41595e8b05c8be4cd92dd6eb6147ea9de1740ae4f
stale: false
raw: ./_raw/DFEAT-039.json
links:
  based_on: ["[[ADR-008]]"]
  belongs_to_domain: ["[[DOMAIN-011]]"]
  implements: ["[[API-047]]", "[[IMPREC-366]]"]
  triggers: ["[[EVT-001]]"]
  verifies: ["[[AC-1013]]", "[[AC-1014]]", "[[AC-1015]]"]
  depicts_backward: ["[[CDIAG-002]]", "[[CMP-002]]"]
  realizes_backward: ["[[MOD-004]]", "[[UC-019]]"]
  references_backward: ["[[ADR-008]]", "[[CDIAG-002]]"]
---

# 마킹 (자동/수동 이벤트 식별)

## title

마킹 (자동/수동 이벤트 식별)

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

- EVT-001

## brownfield

### status

new

### decided_by

ADR-008

### change_kind

- capability-add

### diff_summary

V2.0 파이프라인 마킹 단계 신규 — 1차 미존재. 설계 타깃 순서: 비식별화(전체 영상, 선두)→마킹(비식별 영상)→VLM 시계열→마킹위치 프레임추출→YOLO/SAM2→트랙보간. 마킹 완료 시 잔여 배치 트리거 — 설계 타깃 순서(비식별 선두→마킹→VLM→프레임추출→YOLO/SAM2→트랙보간)대로 확정·구현되었다(구 divergence 폐기)

## user_story

### as

라벨링 작업자

### i_want

영상에서 이벤트 시점을 자동/수동으로 마킹하기를

### so_that

VLM 시계열 분석과 프레임 추출의 기준을 제공한다

## description

비식별화 완료 후 작업자가 비식별 영상에서 이벤트 시점을 자동/수동으로 마킹한다. 영상별 자동/수동 모드 설정 — 자동=프레임 간격(intervalFrames) 기반 마킹, 수동=작업자 키보드 단축키로 이벤트 시점 마킹. 마킹 완료는 마킹 완료 이벤트를 발행해 잔여 배치(VLM 시계열→마킹위치 프레임추출→YOLO/SAM2 오토라벨링→트랙보간)를 트리거하며, 그 시계열 위탁 요청은 프레임 선택 정책(frame_policy)과 검증이벤트유형(event_type)으로 구성된다. 마킹 본문에서 프레임 인덱스를 얻으면 frame_selected 모드로 그것을 싣고, 하나도 얻지 못하면 frame_interval 모드로 내린다(빈 목록은 규격 위반이라 거부된다) — 마킹 모드는 그 인덱스를 누가 골랐는지만 가른다(수동이면 작업자가 지정한 프레임, 자동이면 간격으로 자동 선택된 프레임). 인덱스는 정렬·중복제거 후 0 이상 최대 600건으로 제한하고 초과분은 절단하며 음수 프레임 인덱스는 싣지 않는다. frame_policy 에 framerate 를 두지 않는다 — 추출 간격·장수 세부값은 외부 분석 서버가 관리하고 연동 측은 mode 와 selected_frames 만 지정한다. event_type 은 관제 인입값(LS_DATA_INGEST.VRFC_EVNT_TYPE_CD)을 그대로 실어 위탁하며 화이트리스트로 사전 차단하지 않고 수용 여부는 외부 분석 서버의 응답이 정한다. 위탁은 describe(POST /v1/videovlm-klid/describe)와 describe-sub(POST /v1/videovlm-klid/describe-sub) 두 건으로 제출하며 각 요청에 서로 다른 요청 식별자(request_id)를 부여한다. 위탁은 논블로킹 제출이며 파이프라인 스레드를 붙잡지 않는다. 마킹 중 비식별 누락 신고 구간에는 위탁을 보류하고 신고 해소 후 재위탁한다 — 이벤트명·영상경로·마킹 원문은 위탁 규격 밖이라 싣지 않는다. 영상 스트리밍(Range)+배속(0.25x~4x)+단축키 지원. (★구 divergence 폐기 — 이전 서술은 비식별 선두를 설계 타깃으로, 마킹(원본)의 전체 배치 트리거를 현재 코드로 적었으나 그 분기는 해소됐다: 적재 직후 선두 비식별이 먼저 돌고, 마킹은 비식별 완료 후 진입해 잔여 배치만 트리거하는 순서로 확정·구현되었다) 마킹에는 그 영상의 검증 이벤트 유형에 등록된 질문 중 작업자가 고른 값(LS_MARKING.VRFC_EVNT_QSTN_SN)을 함께 보관한다. 기본값은 그 유형의 첫 번째 질문(정렬순서 최선두 1건)이며, 유형별 정렬순서가 유일하므로 「첫 번째」는 조회마다 흔들리지 않는다. 선택값이 없거나 그 유형에 속하지 않는 질문이면 서버가 그 유형의 첫 번째 질문으로 되돌린다 — 화면 입력을 신뢰하지 않는다. 마킹을 거치지 않는 경로는 언제나 그 유형의 첫 번째 질문을 쓴다. 검증 이벤트 유형이 미수신이면 질문 없이 진행하고 위탁은 그대로 나간다 — 질문 부재가 위탁을 막지 않으며 질문 칸이 빌 뿐이다. 이 선택값은 이벤트 어노테이션의 질문 칸 조달 1순위가 된다. 현행 위탁 요청 규격에는 질문을 실을 자리가 없어, 첫 번째가 아닌 질문을 고르면 보관된 질문과 외부 분석 서버가 실제로 사용한 질문이 달라질 수 있다 — 인지·수용한 잔여 위험이다.

## invokes_apis

_(empty)_

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-366

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:21.821Z

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

### [1]

- **then**: 그 유형의 첫 번째 질문(정렬순서 최선두 1건)이 마킹의 질문 선택값으로 보관된다
- **when**: 작업자가 질문을 고르지 않은 채 마킹을 완료한다
- **given**: 마킹 대상 영상의 검증 이벤트 유형에 질문이 하나 이상 등록되어 있다

### [2]

- **then**: 서버가 그 값을 받아들이지 않고 그 영상 유형의 첫 번째 질문으로 되돌려 보관한다 — 화면 입력을 신뢰하지 않는다
- **when**: 그 유형에 속하지 않는 질문 식별자가 마킹 완료 요청에 실려 온다
- **given**: 마킹 대상 영상의 검증 이벤트 유형에 질문이 하나 이상 등록되어 있다

### [3]

- **then**: 언제나 그 영상의 검증 이벤트 유형의 첫 번째 질문을 쓴다
- **when**: 이벤트 어노테이션의 질문 칸을 채운다
- **given**: 마킹을 거치지 않고 질문 값을 조달하는 경로다

### [4]

- **then**: 질문 선택값 없이 진행하고 위탁은 그대로 제출되며 이벤트 어노테이션의 질문 칸만 비어 있다
- **when**: 마킹을 완료하고 시계열 위탁을 제출한다
- **given**: 영상의 검증 이벤트 유형이 미수신이다

## persists_in_tables

- LS_MARKING

## related_acceptances

- AC-1013
- AC-1014
- AC-1015

## implemented_by_endpoints

- API-047

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
