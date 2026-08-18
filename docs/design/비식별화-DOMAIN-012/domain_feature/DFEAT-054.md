---
logicraft_item: DFEAT-054
type: domain_feature
version: 2
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:55.727Z
status: NEW
prev_version: null
content_hash: 003ae5047c1114f6d2327c21ac4e224b38fe6c2a0755ee29f1d0057de67b5936
stale: true
raw: ./_raw/DFEAT-054.json
links:
  belongs_to_domain: ["[[DOMAIN-005]]"]
  consumes: ["[[EVT-006]]", "[[EVT-008]]"]
  specializes: ["[[FEAT-003]]"]
  triggers: ["[[EVT-009]]"]
  realizes_backward: ["[[MOD-043]]"]
---

# 검수 승인 학습데이터 산출(NIA JSON)

## title

검수 승인 학습데이터 산출(NIA JSON)

## status

implemented

## consumes

- EVT-006
- EVT-008

## priority

must

## triggers

- EVT-009

## brownfield

### notes

산출을 범위 밖으로 둔 구 결정이 대체되면서 가능해진 기능이다. 기능 층에만 대응 항목이 없어 결정·규격·이벤트 층에서만 서술되던 것을 메운다.

### status

new

### decided_by

ADR-020

### change_kind

- feature-add

## user_story

### as

관제서버·데이터마트

### i_want

검수를 통과한 영상의 학습데이터를 회차별로 완결된 파일로 받기를

### so_that

별도 조회 없이 폴더를 가져가 적재하고, 수정이 생기면 이전 회차와 비교하거나 그리로 되돌릴 수 있다

## description

검수가 승인된 시점에 그 영상의 라벨·메타를 학습데이터 파일로 산출해 관제·데이터마트가 가져갈 수 있게 둔다. 산출물은 영상 루트 아래 회차 폴더에 프레임별 JSON 과 이미지로 놓이며, 이미지는 원천과 비식별 두 벌이다.

[진입은 이벤트이지 요청이 아니다] 이 기능에는 호출할 통로가 없다. 승인 트랜잭션이 커밋된 뒤 비동기로 시작하며, 아직 열려 있는 비식별 누락 신고가 해소될 때도 같은 경로로 들어온다.

[회차는 자기완결이고 지우지 않는다] 재생성은 달라진 부분만 바꾸는 것이 아니라 새 회차 폴더에 전량을 다시 만든다. 이전 회차는 전부 보존한다 — 사업 요구가 버전 비교와 복구를 말하므로 델타만 두면 복구가 성립하지 않기 때문이다. 오래된 회차를 정리하는 장치를 두지 않으며 그에 따른 저장소 증폭은 감수한다.

[재생성 시점은 수정이 아니라 재승인이다] 승인 뒤에 사람이 내용을 고치면 그 영상은 재검토 대상으로 표시될 뿐이고, 검수자가 그 수정을 다시 승인한 시점에 새 회차가 나온다. 학습데이터는 검수를 통과한 것만 확정이므로, 검수자가 아직 보지 않은 내용이 산출물 축으로만 먼저 나가면 검수라는 관문이 우회된다. 그 대가로 재승인 전까지 관제는 직전 승인본을 본다.

[재생성 대상은 개수가 아니라 성질로 정한다] 사람이 산출물에 들어가는 내용을 바꾸는 모든 경로가 대상이다. 제외는 넷뿐이다 — 외부 시계열 콜백, 비식별 누락 신고 접수, 운영자 정정 배치, 이벤트 어노테이션의 지연 승인(승인 행위 자체). 판정 기준은 사람이 산출물에 들어가는 내용을 바꿨는가 하나다.

[통지는 산출이 성공한 뒤에 나간다] 산출이 비동기라 통지가 먼저 나가면 관제가 이전 회차 폴더를 가져간다. 산출이 실패하면 통지를 보류하고, 재산출이 성공한 시점에 보류된 통지를 이어서 보낸다 — 유실이 아니라 지연이다. 재산출 트리거는 통지 발송 토글과 무관하게 항상 동작한다.

[관제는 폴더 경로로 픽업한다] 산출 폴더 경로는 영상 루트를 가리킨다 — 회차 루트가 아니다. 관제가 한 경로 아래에서 여러 회차를 보고 골라야 비교와 복구가 성립하기 때문이다.

[폐기된 구 기능과 혼동하지 말 것] 1차의 학습데이터 내보내기·데이터마트 등록과는 다른 기능이다. 그것은 별도 데이터셋 원장을 기반으로 사람이 내보내고 마트에 등록하는 통로였고 폐기됐으며, 그 폐기는 뒤집히지 않았다. 데이터마트의 구축·검색·다운로드는 여전히 외부 책임이고, 이 기능은 승인 경로의 파일 자동 산출만 담당한다.

## invokes_apis

_(empty)_

## implementation

### status

implemented

### modules

- MOD-043

### records

_(empty)_

### progress

100

### subtasks

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_DATASET_EXPORT
- LS_DATASET_VIDEO_META

## related_acceptances

_(empty)_

## specializes_feature

FEAT-003

## implemented_by_endpoints

_(empty)_

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
