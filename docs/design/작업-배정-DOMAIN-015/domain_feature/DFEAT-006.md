---
logicraft_item: DFEAT-006
type: domain_feature
version: 12
domain: DOMAIN-015
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-17T01:12:53.352Z
status: CHANGED
prev_version: 12
content_hash: 9858bf803b11f266b46fef8dee4100a14526a0352e57a5d974584b96ee4c27b4
stale: false
raw: ./_raw/DFEAT-006.json
links:
  based_on: ["[[ADR-055]]"]
  belongs_to_domain: ["[[DOMAIN-015]]"]
  implements: ["[[API-070]]", "[[API-071]]", "[[API-072]]", "[[API-073]]", "[[API-116]]", "[[API-259]]", "[[IMPREC-350]]"]
  migrated_from: ["[[LEGACY-112]]"]
  depicts_backward: ["[[CDIAG-007]]", "[[CMP-009]]"]
  realizes_backward: ["[[MOD-014]]", "[[UC-029]]"]
  references_backward: ["[[ADR-069]]", "[[CDIAG-007]]"]
---

# 작업 배정·재배정·해제·확인

## title

작업 배정·재배정·해제·확인

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

_(empty)_

## brownfield

### status

modified

### decided_by

ADR-055

### change_kind

- actor-change

### diff_summary

1차 관리자/담당자 배정 → 2차 검수자(REVIEWER)가 작업자(WORKER)에게 배정, 영상 단위 배정 (V1.3/V1.8). 관리자(ADMIN)는 역할 계층(ROLE_ADMIN > ROLE_REVIEWER)으로 검수자 권한을 물려받아 배정할 수 있다.

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-03-02-14

#### legacy_artifact_id

LEGACY-112

## user_story

### as

검수자

### i_want

작업자에게 데이터를 배정하고 재배정·확인하기를

### so_that

작업 부하를 분배하고 진행을 통제한다

## description

검수자가 영상 1건 단위로 작업자에게 작업을 배정한다. (RAW_DATA_ID, USER_NO, TASK_TYPE_CD) 유니크로 중복 배정을 막고, 재배정 시 기존 배정 행을 갱신하고 이벤트 로그(LS_TASK_EVNT_LOG)에 이전·신규 담당자를 기록 — 재배정 전용 이력 테이블은 두지 않는다. 작업 상태는 배정 시점에 생성되고 배치 완료 시 ASSIGNED 로 복귀하며, 검수 승인 시 APPROVED 로 종결한다. 증강 파생은 검수자가 사용하기로 결정한 것만 작업목록·배정에 등재된다. (1차 baseline, 화면 SKKLID-UI-03-02-14~15, 02-02-03)

배정의 대상은 작업자뿐이다. 검수자를 영상에 배정하는 절차는 두지 않는다 — 검수는 배정 없이 전체 대기열에서 집어가고 자격은 역할이 정하므로, 배정을 검수의 진입 조건으로 쓰지 않는다. 배정 원장의 작업 유형 값역에 남아 있는 검수자 값은 이미 적재된 행을 판독하기 위한 것이며 새로 만들지 않는다. 작업 목록에도 검수자 축을 두지 않는다.

작업 이벤트 로그는 배정·재배정뿐 아니라 검수 시작·검수 제출·승인·반려까지 한 타임라인에 누적한다. 검수 점유를 전용 컬럼이나 별도 표가 아니라 이 원장의 「검수 시작」 이벤트로 표현하기 때문이며, 그래서 지금 누가 그 영상을 검수 중인지도 이 원장을 읽어 판정한다. 이벤트 종류로 거르는 질의 항목을 두지 않으므로 값역이 넓어지면 새 종류도 그대로 실린다.

이력 항목마다 그 행위를 한 시점의 행위자 역할이 함께 남는다. 조회 시점에 현재 역할을 다시 읽지 않아 그 사람의 역할이 바뀌어도 과거 행위의 역할은 그대로이며, 관리자가 수행한 행위는 관리자로 남는다. 역할 칸이 생기기 전에 쌓인 이력은 비어 있을 수 있고 지어내 채우지 않는다.

근거는 ADR-067.

배정을 해제한다. 해제는 배정을 없애는 것이라 배정 원장에서 그 행이 사라진다 — 배정 원장에는 상태 칸이 없어 배정은 있거나 없거나뿐이다. 작업 결과는 함께 지워지지 않는다. 라벨과 그 이력은 그대로 남고 배정만 푼다. 해제 이력은 작업 이벤트 로그에 남으며 해제된 작업자를 대상자 자리에 싣는다 — 재배정이 이전 담당자와 대상자 자리를 쓰는 것과 같은 축이다. 되돌리려면 다시 배정한다. 해제를 취소하는 창구는 따로 두지 않는다. 인가는 배정 생성·재배정과 같은 축이다(검수자 이상, 역할 계층으로 관리자도 수행한다).

재배정과 해제는 가른다. 재배정은 담당을 바꾸고 해제는 배정을 없앤다. 지금까지 배정을 되돌리는 유일한 수단이 재배정이었으므로, 이 구분을 적어 두지 않으면 재배정으로 해제를 대신하려는 설계가 다시 나온다.

해제에는 해제 전용 거부 조건을 둔다. 검수 대기·검수 중·승인 상태의 배정은 해제되지 않는다 — 배정이 그 워크플로의 전제라 풀면 검수 흐름이 주인 없는 상태가 된다. 재배정의 기존 거부 조건과는 다른 축이므로 한 조건으로 합쳐 적지 않는다. 반려 상태는 해제할 수 있다. 워크플로가 작업자에게 되돌아온 상태라 그 작업 자체를 접을 수 있어야 한다.

제외된 영상에는 배정할 수 없다. 목록에서 보이지 않고 제외분 보기에서도 배정 동작을 노출하지 않는다. 반대로 배정이 있는 영상은 제외가 거부되므로 해제가 그 앞단에 필요하다. 이 기능이 소유한 목록에서는 제외된 영상이 빠진다 — 검수자 축과 작업자 축 둘 다.

근거는 ADR-069.

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

- IMPREC-350
- IMPREC-481

### progress

100

### subtasks

_(empty)_

### last_updated

2026-09-16T01:22:26.345Z

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_TASK_ALTMNT
- LS_TASK_EVNT_LOG

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-070
- API-071
- API-072
- API-116
- API-073
- API-259

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
