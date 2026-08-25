---
logicraft_item: DFEAT-055
type: domain_feature
version: 5
domain: DOMAIN-013
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-25T10:52:12.903Z
status: CHANGED
prev_version: 5
content_hash: 0ba020ed1f152cfe4b133f74281b2e34f1fa112ff80229af51923df19941671c
stale: true
raw: ./_raw/DFEAT-055.json
links:
  belongs_to_domain: ["[[DOMAIN-013]]"]
  implements: ["[[API-115]]", "[[API-140]]", "[[API-142]]", "[[API-203]]"]
---

# 포털 작업 데이터 보존기간 만료 자동 삭제 (데이터마트·업로드 공통)

## title

포털 작업 데이터 보존기간 만료 자동 삭제 (데이터마트·업로드 공통)

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

_(empty)_

## brownfield

### notes

2026-08 사용자 확정 — 데이터마트·업로드 두 채널 모두 보존기간 만료 시 자동 삭제(업로드는 DB+파일 함께). 기준점은 채널별 '마지막 활동'으로 정의하되 계산식이 다르다: 데이터마트는 저장 라벨의 MAX(REG_DT), 업로드는 자산 등록일과 라벨 최종 저장일 중 늦은 시각. 업로드 축은 상태별 보존기간이 다르다 — READY 7일, FAILED 1일(되찾을 수 없는 실패 자산은 더 빨리 정리), UPLOADED(처리 시작 전)·PROCESSING(처리 중)은 대상 아님(고지할 만료 시각도 없음). 2026-08 사용자 확정 — 처리 시작 전·처리 중으로 방치된 자산은 새 삭제 경로를 만들지 않고 먼저 처리 실패로 전이시켜 기존 실패 축 보존기간이 지우게 한다. 방치 판정 시간은 설정값(기본 30분)이고 판정 축은 무갱신 경과다.

### status

new

### decided_by

ADR-013

### change_kind

- capability-add

## description

포털 사용자의 작업 데이터는 보존기간이 지나면 자동으로 삭제된다. 데이터마트 채널(LS_PORTAL_USER_LABEL)은 마지막 저장일(MAX(REG_DT)) 기준 설정 가능 일수(기본 7일, portal.datamart.retention-days) 경과 시 해당 (사용자, 영상)의 저장 라벨을 삭제한다. 업로드 채널(LS_PORTAL_ULD/LS_PORTAL_ULD_FRME/LS_PORTAL_ULD_LBL)은 READY 상태 자산은 마지막 작업일(자산 등록일과 그 자산 라벨의 마지막 저장일 중 더 늦은 시각) 기준 설정 가능 일수(기본 7일, portal.upload.retention-days) 경과 시, FAILED 상태 자산은 FAILED 전이 시각 기준 설정 가능 일수(기본 1일, portal.upload.failed-retention-days) 경과 시 DB 행과 저장소 파일을 함께 삭제한다. UPLOADED(처리 시작 전)·PROCESSING(처리 중) 상태 자산은 삭제 대상이 아니며, 고지할 만료 시각도 없다 — 삭제 후보는 READY·FAILED 상태뿐이다. 다만 이 두 상태로 영구히 남지는 않는다 — 방치 판정 시간(portal.upload.stuck-timeout-minutes, 기본 30분) 동안 최종 변경 일시가 갱신되지 않은 자산은 처리 실패(FAILED)로 전이되고, 그때부터 이미 있는 실패 축 보존기간 규칙이 그대로 적용되어 같은 삭제 경로가 지운다. 이 공백을 새 삭제 경로로 닫지 않고 상태 전이로 닫는 것은 의도다 — 비가역 삭제의 판정 지점이 둘로 늘면 두 조건을 영원히 맞춰야 한다. 방치 판정 축은 총 처리 시간이 아니라 최종 변경 일시의 무갱신 경과다 — 처리가 진행되는 동안에는 그 시각이 계속 갱신되므로 오래 걸리는 대용량 영상이 방치로 오인되지 않는다. 방치 판정 시간은 보존일수와 같은 운영 설정 축에서 조정할 수 있으며, 값이 비어 있거나 0 이하·해석 불가면 그 회차의 방치 전이를 수행하지 않는다 — 임의 기본값으로 대체하거나 0 을 그대로 적용해 즉시 실패 전이로 전락시키지 않는다(보존일수 설정이 없을 때 그 축을 건너뛰는 것과 같은 취지다). 전이된 자산에는 실패 사유가 남고 실패 축 만료 예정 시각이 생겨 기존 고지 경로(자산 목록·상세 조회 응답)에 그대로 실린다 — 화면이 표시하는 항목은 달라지지 않고, 비어 있던 자리에 시각이 채워질 뿐이다. 전이는 삭제의 예고이므로 사용자가 알아차릴 유예는 실패 축 보존기간(기본 1일)이 제공한다. 두 채널 모두 삭제는 비가역이며, 삭제 전에 만료 예정 시각을 조회 응답으로 고지해 사용자가 예고 없이 작업물을 잃지 않게 한다 — 데이터마트 채널은 영상 목록 조회 응답에, 업로드 채널은 자산 목록·상세 조회 응답에 그 시각을 실어 내리고 화면은 받은 값을 표시한다. 이 시각은 저장하지 않고 조회 시점의 보존기간 설정값으로 계산하는 파생값이므로, 보존기간 설정이 바뀌면 다음 조회부터 값이 함께 움직인다 — 클라이언트는 캐시하지 않고 매 조회마다 갱신한다. 데이터마트 채널에서 본인 저장 라벨이 없는 영상은 고지할 만료 시각도 없다. 작업 중(마지막 활동이 계속 갱신되는 동안)에는 만료 시각이 계속 밀리므로 삭제되지 않는다. 삭제된 데이터마트 라벨은 재작업 시 데이터마트 원본을 다시 불러온 새 작업으로 대체되며, 삭제된 업로드 자산은 복구되지 않는다. 이 자동 삭제와 그 앞단의 방치 전이를 수행하는 주기 실행은 자기 자신의 활성화 설정으로만 켜지고 꺼진다 — 다른 기능의 스케줄링 활성화에 얹으면 그 무관한 기능을 끄는 순간 보존기간 삭제가 소리 없이 멈춘다.

## invokes_apis

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-008

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-17T04:33:25.807Z

### module_paths

- backend/src/main/java/kr/co/cudo/authoring/portal/scheduler/PortalRetentionSweepJob.java
- backend/src/main/java/kr/co/cudo/authoring/portal/service/PortalRetentionSweepTxService.java
- backend/src/main/java/kr/co/cudo/authoring/portal/service/PortalRetentionPolicy.java
- backend/src/main/java/kr/co/cudo/authoring/portal/service/PortalStoragePathGuard.java
- backend/src/main/java/kr/co/cudo/authoring/portal/repository/LsPortalUserLabelRepository.java
- backend/src/main/java/kr/co/cudo/authoring/portal/repository/LsPortalUldRepository.java
- backend/src/main/java/kr/co/cudo/authoring/portal/repository/LsPortalUldLblRepository.java
- backend/src/main/java/kr/co/cudo/authoring/sysconfig/ConfigKeys.java
- backend/src/main/resources/db/migration/V11__seed_portal_retention_config.sql

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_PORTAL_USER_LABEL
- LS_PORTAL_ULD
- LS_PORTAL_ULD_FRME
- LS_PORTAL_ULD_LBL

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-115
- API-142
- API-140
- API-203

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
