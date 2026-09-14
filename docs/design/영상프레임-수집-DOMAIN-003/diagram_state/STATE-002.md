---
logicraft_item: STATE-002
type: diagram_state
version: 3
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-14T05:32:53.135Z
status: CHANGED
prev_version: 2
content_hash: 156f8118ede1c4b94ac5afb45bcd7018db998a587002e066a3e23242b160838a
stale: false
raw: ./_raw/STATE-002.json
links:
  belongs_to_domain: ["[[DOMAIN-003]]"]
---

# 영상 배치 단계 상태 전이 (LS_DATA_RAW.DATA_STTS_CD)

## theme

neutral

## title

영상 배치 단계 상태 전이 (LS_DATA_RAW.DATA_STTS_CD)

## source

stateDiagram-v2
    [*] --> PENDING: 영상 적재 (영상 행 생성 시 PENDING)
    PENDING --> MARKING_READY: 선두 비식별 성공 — 외부 위탁 완료 또는 비식별 제외 출처유형의 원본 복사 완료
    MARKING_READY --> PROCESSING: 마킹 완료로 트리거된 배치 진입 — 원자 클레임
    PROCESSING --> COMPLETED: 배치 성공 또는 재수행 실패 보상 복원
    PROCESSING --> FAILED: 배치 실패 또는 선점 보상 복원
    FAILED --> PROCESSING: 수동 재처리 클레임
    COMPLETED --> PROCESSING: 완주 영상 단계 재수행 클레임
    COMPLETED --> [*]
    note right of PENDING: 이 축의 행은 영상 행과 함께 항상 존재한다 — 배정 시점에 생기는 작업·검수 워크플로 축과 생성 시점이 다르다
    note right of MARKING_READY: 선두 비식별이 실패하면 외부 위탁 실패든 비식별 제외 복사 실패든 이 축은 PENDING 에 머문다 — 영상 비식별여부만 실패(F)로 선다
    note right of PROCESSING: 진입 클레임의 조건은 현재 단계가 PROCESSING 이 아닐 것 하나다. 고착된 선점은 회수 스윕이 선점 직전 단계로 되돌리며 목표값을 모르면 되돌리지 않는다
    note right of COMPLETED: 경계 — 이 축은 마킹 완료와 배치 종료에서 작업·검수 워크플로 축(LS_RAW_DATA_STATUS.DATA_STTS_CD)과 맞물린다. 그 축의 PENDING·ASSIGNED·BATCH_QUEUED·IN_REVIEW·APPROVED·REJECTED 는 이 다이어그램이 그리지 않는다

## brownfield

### status

new

## invariants

- PENDING·MARKING_READY·PROCESSING·COMPLETED·FAILED 는 모두 LS_DATA_RAW.DATA_STTS_CD 의 값이다. 작업·검수 워크플로 축(LS_RAW_DATA_STATUS.DATA_STTS_CD)의 상태값은 이 축에 속하지 않으며 별도 다이어그램이 그린다.
- COMPLETED 는 배치 단계의 종료 상태이되 흡수 상태가 아니다 — 완주 영상의 단계 재수행 클레임이 COMPLETED 에서 PROCESSING 으로 되돌린다.
- FAILED 는 종료 상태가 아니다 — 수동 재처리 클레임이 FAILED 에서 PROCESSING 으로 되돌린다.
- 배치 진입은 현재 단계가 PROCESSING 이 아닐 때만 성립하는 원자 클레임이다. 이 클레임이 동일 영상의 파이프라인 이중 실행을 막는 유일한 장치이며, 읽고 나서 쓰는 방식으로 대체하면 동시 진입이 전부 통과한다.
- 클레임을 걸고 파이프라인을 시작하지 못한 호출자는 반드시 선점 직전 단계로 보상 복원해야 한다. 복원하지 않으면 배치 단계가 PROCESSING 으로 영구 고착돼 이후 재처리가 모두 거부된다.
- MARKING_READY 는 선두 비식별이 성공한 영상에만 선다. 비식별 미완료 상태에서 이 값으로 전이시키면 마킹 화면이 비식별되지 않은 영상을 열게 된다.
- 작업 상태가 검수 소유 상태(PENDING·IN_REVIEW·APPROVED·REJECTED)면 이 축도 함께 멈춘다 — 두 컬럼 중 한쪽만 전이시키지 않는다.
- 선두 비식별 성공에는 비식별 제외 출처유형의 원본 복사 완료가 포함된다(ADR-066) — 배포 설정의 제외 목록에 든 출처유형 영상은 외부 위탁 없이 원본을 비식별 영상 자리에 복사하고 성공 이력을 남긴 뒤 MARKING_READY 로 선다. 이 분기를 위한 새 상태값은 두지 않는다. 선두 비식별 실패는 외부 위탁 실패든 제외 복사 실패든 이 축을 전이시키지 않고 PENDING 에 머문다.

## description

영상 한 건이 적재된 뒤 배치 파이프라인을 통과하는 단계를 그린다 — PENDING·MARKING_READY·PROCESSING·COMPLETED·FAILED 5종이며 모두 LS_DATA_RAW.DATA_STTS_CD 한 컬럼의 값이다. 이 축의 행은 영상 행 자체이므로 적재 시점부터 항상 존재한다.

[축 경계] 같은 이름의 상태가 작업·검수 워크플로 축(LS_RAW_DATA_STATUS.DATA_STTS_CD)에도 있으나 두 축은 별개 컬럼이며 서로 다른 다이어그램이 그린다. 맞물리는 지점은 둘이다 — 마킹 완료가 저 축을 BATCH_QUEUED 로 큐잉하고, 배치 종료가 저 축을 성공 시 ASSIGNED 로 복귀시키거나 실패 시 FAILED 로 세운다. 이 축의 COMPLETED 는 배치 처리 완료를 뜻하며 검수 완료가 아니다 — 검수 종결값은 저 축의 APPROVED 다. 두 축을 한 그림에 섞으면 존재하지 않는 전이가 생긴다.

[단계 순서] 비식별화가 마킹 선행 자동 단계다. 적재 직후 전체 영상 비식별(배치 단계 축) → 비식별 성공 시 MARKING_READY(외부 위탁 완료 또는 비식별 제외 출처유형의 원본 복사 완료 — ADR-066) → 작업자가 비식별 영상에서 마킹 → 마킹 완료 시 BATCH_QUEUED(잔여 배치 VLM~보간). 단계 구성은 선언적 파이프라인이 pre-marking=[DEIDENTIFY], post-marking=[MARKING, VLM, FRAME_EXTRACT, YOLO, SAM2, INTERPOLATE] 로 소유한다. ★구 단계 순서(마킹(원본) 트리거 → VLM → 비식별)는 지금은 그렇게 하지 않는다.

[재수행·보상] 종료 상태에서 다시 PROCESSING 으로 돌아오는 경로가 둘 있다 — 실패분 수동 재처리와 완주 영상의 단계 재수행이다. 둘 다 조건부 UPDATE 로 원자 클레임하며, 클레임 뒤 파이프라인이 시작되지 못하면 선점 직전 단계로 되돌린다. 보상이 없으면 영상이 PROCESSING 으로 영구 고착돼 이후 모든 진입이 거부된다.

[작업 상태와의 동반 전이] 배치 진입·완료·실패는 두 컬럼을 같은 타이밍에 움직인다. 다만 작업 상태가 검수 소유 상태(PENDING·IN_REVIEW·APPROVED·REJECTED)면 두 컬럼 모두 건드리지 않고 멈춘다 — 한쪽만 전이시키면 승인된 영상에 배치 결과가 덧씌워지고도 상태는 승인으로 남는 불일치가 생긴다.

## entity_name

영상 배치 처리 (LS_DATA_RAW)

## initial_state

PENDING

## attached_files

_(empty)_

## implementation

### status

planned

### modules

_(empty)_

### records

_(empty)_

### progress

0

### subtasks

_(empty)_

### module_paths

_(empty)_

## terminal_states

- COMPLETED

## referenced_items

_(empty)_
