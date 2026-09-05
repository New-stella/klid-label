---
logicraft_item: DFEAT-041
type: domain_feature
version: 15
domain: DOMAIN-012
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:43:42.716Z
status: CHANGED
prev_version: 14
content_hash: b4d0ccbeca804381dd8dde85d1ee1429ca5b558695d430f58d1af9e0425ac8f1
stale: true
raw: ./_raw/DFEAT-041.json
links:
  based_on: ["[[ADR-006]]"]
  belongs_to_domain: ["[[DOMAIN-012]]"]
  consumes: ["[[EVT-007]]"]
  implements: ["[[API-112]]"]
  specializes: ["[[FEAT-005]]"]
  verifies: ["[[AC-1063]]", "[[AC-1064]]"]
  depicts_backward: ["[[CDIAG-003]]", "[[CMP-003]]"]
  realizes_backward: ["[[MOD-049]]", "[[UC-011]]"]
  references_backward: ["[[CDIAG-003]]"]
---

# 비식별 처리 위탁·결과 저장

## title

비식별 처리 위탁·결과 저장

## status

designed

## consumes

- EVT-007

## priority

must

## triggers

_(empty)_

## brownfield

### notes

2026-07-30: refreshExisting 강제 재생성 + resolve fail-closed 게이트(경로실존+시점비교) 반영. 2026-08-06: AC-011 정합 — 비식별 대상 게이팅 폐지(전체 영상)·논블로킹 제출·KPST 폴링 단일화(구 콜백 경로 제거)·자동 재비식별 큐 폐기 반영.

### status

new

### decided_by

ADR-006

## description

전체 영상(PRVC/PSDO/ANONY — PRVC_TYPE_CD 게이팅 폐지)을 외부 KPST 비식별 솔루션에 위탁하고 결과 비식별본을 STORAGE_DEIDENTIFIED_PATH 에 저장하며 원본을 보존한다. 비식별은 파이프라인 선두 단계로, 적재 완료(VideoIngestedEvent, 커밋 이후 비동기)를 신호로 자동 시작하고 REVIEWER 의 재비식별 요청(POST /v1/videos/{rawSn}/redeident, 202 Accepted)도 진입점으로 둔다. 성공 시 LS_DATA_RAW.DATA_STTS_CD 가 MARKING_READY 로 전이해 비식별 영상 대상 마킹이 허용된다.

[제출은 논블로킹] 비식별 위탁은 WAITING 원장 행을 선커밋한 뒤 프로젝트 생성을 비동기 디스패치한다 — 파이프라인 스레드를 붙잡지 않는다. 결과는 별도 콜백이 아니라 주기 폴링 배치가 진행을 조회해 회수한다.

[회수 경로] **회수 경로는 축마다 다르다.** 시계열은 **전용 미결 스위퍼**가 집어 **재위탁**하고, 비식별은 **전용 스위퍼가 없이 폴링 잡이 ACK 대기 유예 만료로 회수**해 **실패 코드로 원장을 마감**한다(**재위탁하지 않는다**). 비식별 쪽 회수는 **「우리가 ACK 를 관측하지 못했다」**는 뜻이지 「외부에서 실패했다」가 아니다 — 다만 **그 회수도 제출 확정 실패와 같은 종결 경로를 타서 원장을 FAILED 로 마감하고 영상 비식별 여부를 실패('F')로 내린다.** 종료 계기가 달라도 종료값은 같다 — 외부 터미널 상태·폴링 경과 만료·불완전 산출물, 제출 확정 실패(KPST_SUBMIT_FAILED), ACK 대기 유예 만료 회수(KPST_ACK_MISSING) 가 모두 그렇다. **예외는 하나뿐이며 다른 쪽이다** — 호출자 트랜잭션 롤백에 따른 취소 종결(KPST_SUBMIT_CANCELED)만 원장을 FAILED 로 마감하되 **영상 비식별 여부를 바꾸지 않는다**(외부로 아무것도 나가지 않았기 때문). **ACK 대기 유예 만료 회수를 따로 두는 근거는 영상 상태가 아니라 운영 조치다** — 그 코드가 남았다는 것은 우리가 수락 응답을 관측하지 못했다는 뜻이라 외부에는 프로젝트가 실제로 생성돼 있을 수 있으므로(노드 사망 등), **사람이 외부 상태를 확인해야 하는 건**을 식별하려는 분리다. 제출 신호가 노드와 함께 사라지면 실패 행조차 남지 않아 재시도 큐·실패 회수기가 집지 못하므로, **각 축의 이 회수 경로가 유일하다.**

[결과 경로] 결과 파일 경로는 솔루션이 통보한 값을 LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM 에 그대로 기록한다 — 파일명을 조합하거나 추측하지 않는다(mock=deidentified.mp4 / KPST={원본stem}-mask{ext} 로 영상마다 다르다).

[실패·신고] 실패 시 LS_DATA_RAW.DE_IDENT_YN='F'(뷰 노출명 DE_IDNTF_YN) 마킹 + 원본 절대 삭제 금지. **ACK 대기 유예 만료 회수(KPST_ACK_MISSING)·제출 확정 실패(KPST_SUBMIT_FAILED)도 이 마킹 대상이며, 취소 종결(KPST_SUBMIT_CANCELED)만 예외로 영상 비식별 여부를 바꾸지 않는다.** 자동 재비식별 큐는 폐기됐고 외부 솔루션에서 수동 재비식별한 뒤 resolve 로 해소한다. 비식별 누락 신고 구간에는 외부 위탁을 개시하지 않고 보류한다. 재비식별 시 강제 재생성(refreshExisting=true)하며, 신고 해소(resolve) 시 산출물 실재 검증(최신 SUCCESS 비식별 procLog 경로 실존 + 시점 비교)이 fail-closed 로 통과를 판정한다(원본 폴백 없음).

[폐기된 구 서술] ①'PRVC/PSDO 영상에 대해서만 위탁' — 게이팅은 폐지됐다. ②'콜백 POST /v1/deidentify/result 로 결과 수신' — 그 콜백 경로는 두지 않는다. 비식별 결과 수신은 KPST 폴링으로 단일화한다. ③'실패 시 재시도 큐' — 자동 재비식별 큐는 폐기됐다. ④'아무 신호도 없으면 미결 스위퍼가 유일한 회수 경로다(ACK 창 기본 30분 · 콜백 창 기본 360분을 구분해 정상 위탁을 빼앗지 않는다)' — 비식별 축에는 전용 미결 스위퍼가 없다. 회수 주체는 폴링 잡이고 동작은 재위탁이 아니라 실패 코드로 원장을 마감하는 것이다. 함께 적힌 두 시간 창 수치는 시계열 축의 값을 이 축에 옮겨 적은 것이라 함께 폐기한다 — 비식별 축의 ACK 대기 유예 값은 별도 확인 대상이다. ⑤'ACK 대기 유예 만료 회수는 영상 비식별 상태를 실패로 내리지 않는다' — 사실과 다르다. 그 회수는 제출 확정 실패와 같은 종결 경로를 타 원장을 FAILED 로 마감하고 영상 비식별 여부를 'F' 로 내린다. 영상 상태를 바꾸지 않는 예외는 취소 종결(KPST_SUBMIT_CANCELED) 하나뿐이다. 수동 블러(DFEAT-013)는 폐기되고 외부 솔루션 연동으로 대체됨(ADR-006).

## invokes_apis

_(empty)_

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

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_DEIDENT_PROC_LOG

## related_acceptances

- AC-1063
- AC-1064

## specializes_feature

FEAT-005

## implemented_by_endpoints

- API-112

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
