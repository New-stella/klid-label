---
logicraft_item: DOMAIN-011
type: domain
version: 6
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:55.117Z
status: NEW
prev_version: null
content_hash: e7cffc9e1be8e99131000baf29107dbda2c2d023578f5b1474eba7b4d5ff94ee
stale: false
raw: ./_raw/DOMAIN-011.json
links:
  depends_on: ["[[INT-002]]"]
  applies_to_backward: ["[[NFR-008]]", "[[NFR-011]]", "[[NFR-018]]", "[[NFR-019]]"]
  belongs_to_domain_backward: ["[[ADR-008]]", "[[API-047]]", "[[API-091]]", "[[CDIAG-002]]", "[[CMP-002]]", "[[DFEAT-039]]", "[[ERD-013]]", "[[EVT-001]]", "[[SCREEN-006]]", "[[SD-012]]"]
  derived_domain_backward: ["[[AC-027]]", "[[AC-028]]"]
  implements_in_backward: ["[[MOD-004]]"]
  references_backward: ["[[TEST-001]]", "[[TEST-002]]"]
---

# 마킹

## name

마킹

## brownfield

### status

new

### decided_by

ADR-008

### change_kind

- capability-add

### diff_summary

2차 신규 도메인 (DFEAT-039 마킹). 1차 화면/도메인 카탈로그에 없던 신규 — 영상 단위 이벤트 식별로 배치 파이프라인 시작점 제공

## description

영상에서 자동(프레임 간격)/수동(작업자 단축키) 모드로 이벤트 시점을 식별·표시하는 도메인.

[★대상은 비식별 영상이다 — 파이프라인의 시작점이 아니다] 구 서술은 마킹을 '파이프라인의 출발점'으로 적었으나, 선두는 비식별화다. 적재 직후 비식별이 자동 수행되어 MARKING_READY 가 된 뒤에야 마킹이 열리며, 작업자는 원본이 아닌 비식별 영상을 보며 마킹한다. 마킹 완료가 트리거하는 것은 전체 배치가 아니라 잔여 배치(VLM → 프레임추출 → YOLO → SAM2 → 보간)이고, 부모의 deIdntfYn 이 'Y' 일 때만 진행한다.

[화면] 비식별 영상 스트리밍(HTTP Range) + 배속 0.25x~4x + 단축키(Space 마킹, Del 삭제, Enter 완료). 스트리밍은 항상 비식별본만 서빙하며 비식별 미완료면 404 로 원본 노출을 막는다.

[★마킹 중 비식별 누락 발견] 이 단계에서도 신고할 수 있다(rawSn 기준). 라벨링 단계 신고(srcSn 기준)와 2채널이며 둘 다 구현됐다.

[VLM 연계] 마킹 결과는 frame_policy(수동 마킹=frame_selected+선택 프레임 목록 selected_frames, 벤더 상한 8 초과분 절단 / 자동 마킹·마킹 부재·미지 모드=frame_interval)로만 VLM 시계열 위탁(POST /v1/videovlm/verify)에 반영된다 — 이벤트명·영상 경로·마킹 원문 배열은 위탁 규격 밖이라 싣지 않는다. ★위탁은 논블로킹 제출이다 — 파이프라인 스레드를 붙잡지 않고 제출만 개시하며, 결과 상세는 VLM 서버가 별도 콜백으로 보낸다. 신고 구간에는 위탁을 보류하고 해소 시 재위탁한다.

## upstream_of

_(empty)_

## context_kind

core

## collaborators

_(empty)_

## integrates_with

_(empty)_

## uses_integrations

- INT-002

## ubiquitous_language

### [1]

- **term**: 마킹(Marking)
- **meaning**: 비식별 영상 내 이벤트 시점 표시 — 자동(프레임 간격)/수동(단축키) 모드

### [2]

- **term**: intervalFrames
- **meaning**: 자동 마킹의 프레임 간격

### [3]

- **term**: MARKING_READY
- **meaning**: 선두 비식별이 성공해 마킹 진입이 허용된 상태. 마킹은 이 이후에만 가능하다

### [4]

- **term**: MarkingCompleted
- **meaning**: 마킹 완료 도메인 이벤트(EVT-001) — 트랜잭션 커밋 후 잔여 배치를 비동기로 시작한다

### [5]

- **term**: 비식별 신고(마킹 단계)
- **meaning**: 마킹 중 개인정보 노출을 발견해 rawSn 기준으로 재비식별을 요청하는 경로
