---
logicraft_item: TEST-004
type: test_scenario
version: 17
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:41:14.435Z
status: NEW
prev_version: null
content_hash: 5f08b1b76012fc5e1fb7adee7895950686abaabc9e3a74f80d178636c939c632
stale: true
raw: ./_raw/TEST-004.json
links:
  references: ["[[API-074]]", "[[API-075]]", "[[API-076]]", "[[DOMAIN-005]]", "[[DOMAIN-010]]", "[[DOMAIN-016]]", "[[SCREEN-019]]", "[[UC-007]]", "[[UC-009]]"]
---

# 검수 완료·수정에 따른 관제서버 단방향 통지 정상 흐름

## kind

integration

## notes

시험시나리오 KLID-AT-IT-TS-004. 대외 연동 경계: 관제서버 완료/수정 통지(KLID-AT-II-007 단방향 outbound). 관련 요구사항 RQ-SFR-08. 핵심 테이블 LS_RAW_DATA_STATUS · LS_LABEL_VERSION · LS_CONTROL_NOTIFY_FALLBACK. 정상 흐름(happy path)만 수록.

[페이로드 규격] 완료 통지(TaskCompletedPayload)는 required 9필드(job_id · event_type_cd · evnt_cls_cd · evnt_ctgry_cd · lclgv_cd · lclgv_nm · duration_sec · image_count · gen_ai_yn)와 선택 필드 output_ver_no 로 이뤄진다. required 는 값이 null 이어도 키를 남기고(키를 빼면 관제가 422 로 거부한다) output_ver_no 는 값이 없으면 키 자체를 내보내지 않는다. 프레임 개수는 image_count 하나이며 라벨·메타 결과 요약 카운트는 두지 않는다. 수정 통지(TaskModifiedPayload)는 job_id · changed_items(images·jsons) · ver_expln(선택) · output_ver_no(선택) 4필드이고, changed_items 는 내부 식별자(SRC_SN)가 아니라 산출 폴더의 실제 파일명({FRM_NO 4자리 zero-pad}.jpg/.json) 리스트다. 변경 종류(LABEL_ADDED 등)와 마지막 수정 일시·변경 요약 카운트는 지금은 관제로 보내지 않는다 — 변경 종류는 내부 디바운스 축적 키·감사 기록으로만 쓴다.

[재생성·통지 트리거] 수정 통지의 트리거는 수정 시점이 아니라 재검수 승인이다. 검수 완료된 영상을 고치면 재검토 표시(LS_RAW_DATA_STATUS.REVLT_YN='Y')가 서고, 그 표시가 서 있는 동안 축적분의 만료 flush 가 보류된다. 검수자가 그 수정을 다시 승인해 표시가 해제되면 다음 flush tick 에 산출물이 새 버전 폴더로 전량 재생성되고 통지가 1회 나간다. 최초 승인만 승인 이벤트로 강제 재생성 후 완료 통지를 내며, 재승인은 그 이벤트를 발행하지 않고 디바운서가 재생성·수정 통지를 낸다. 요청 식별자(UUID)도 폴백·재등록 큐의 내부 키라 통지 본문에 싣지 않는다.

## steps

### [1]

- **seq**: 1
- **note**: TC-009 | manual_test+DB SQL 확인
- **action**: 검수 승인
- **expected**: 영상이 검수 완료(DATA_STTS_CD='APPROVED') 상태로 전이되고 검수자 userNo가 이벤트 로그에 기록된다. 【검증】DB: SELECT DATA_STTS_CD, VER FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID=:rawDataId → DATA_STTS_CD='APPROVED'(IN_REVIEW→APPROVED); SELECT EVNT_TYPE_CD, ACTOR_USER_NO FROM LS_TASK_EVNT_LOG WHERE RAW_DATA_ID=:rawDataId AND EVNT_TYPE_CD='APPROVE' → ACTOR_USER_NO=검수자 userNo · 화면(SCREEN-019): '검수중' 배지→'승인' 버튼
- **test_item**: 검수 상세 화면에서 영상 단위 검수를 승인하는지 확인
- **input_data**: 【영상 식별자】"1001" 【검수자(숫자 userNo)】"101"
- **screen_ref**: SCREEN-019
- **preconditions**: 검수 진행중(IN_REVIEW) 영상이 존재한다

### [2]

- **seq**: 2
- **note**: TC-009 | DB SQL 확인
- **action**: 버전 스냅샷 생성
- **expected**: 검수 승인 시 라벨이 있는 프레임마다 스냅샷이 1건씩(프레임 단위) 해시 식별자와 함께 저장된다(영상 1건이 아니라 라벨 보유 프레임 수만큼). 【검증】DB: SELECT COUNT(*) FROM LS_LABEL_VERSION WHERE DATA_RAW_SN=:rawSn AND SAVE_REASON_CD='APPROVED' AND ACTVTN_YN='Y' → 라벨 보유 프레임 수만큼(다중 행)·각 행 VERSION_HASH(SHA-256)·LBL_PAYLOAD(JSON)
- **test_item**: 승인 시점 라벨 전체가 버전 스냅샷으로 저장되는지 확인
- **input_data**: 【영상 식별자】"1001" 【저장 사유 코드】"APPROVED" 【라벨 수】"18"
- **screen_ref**: SCREEN-019
- **preconditions**: 순번1 승인 완료

### [3]

- **seq**: 3
- **note**: TC-009 | auto_test / required 9필드 키 유지 · output_ver_no 키 생략 규약 / image_count 가 실제 프레임 수와 일치하는지 확인한다.
- **action**: 완료 통지 발송
- **expected**: 완료 통지가 1회 발송되고 수신측이 200 을 반환한다(실패 시 PENDING·FAILED 폴백). 페이로드는 required 9필드(job_id · event_type_cd · evnt_cls_cd · evnt_ctgry_cd · lclgv_cd · lclgv_nm · duration_sec · image_count · gen_ai_yn)의 키를 값이 null 이어도 모두 유지해야 하며(키를 빼면 관제 검증에서 전량 422 다), image_count 는 해당 영상의 실제 프레임 수와 일치해야 한다. output_ver_no 는 값이 있을 때만 키가 실리고 없으면 키 자체가 나가지 않는다. ★ 라벨·메타 결과 요약 카운트 · 검수 완료 일시 · 요청 ID · 영상 파일명 · 채널은 합격 조건이 아니다 — 지금은 페이로드에 두지 않는다. 통지는 export 산출이 성공한 뒤에만 나가야 하며, export 가 실패하면 통지가 보류되는 것이 합격이다.
- **test_item**: 관제서버로 완료 통지가 발송되는지 확인
- **input_data**: 【이벤트 종류】"TASK_COMPLETED" 【작업 식별자(job_id)】"1001" 【프레임 수(image_count)】"해당 영상의 실제 프레임 수" 【산출 버전 번호(output_ver_no)】"1"
- **preconditions**: 순번2 스냅샷 완료 + 해당 영상의 export 산출이 SUCCEEDED

### [4]

- **seq**: 4
- **note**: TC-010 | manual_test / 동일 작업 ID 유지
- **action**: 검수 완료 후 수정
- **expected**: 변경이 반영되고 수정 통지 대상으로 누적되며 동일 작업 ID를 유지한다(버전 업 아님). 이때 그 영상에 재검토 표시(LS_RAW_DATA_STATUS.REVLT_YN='Y')가 서고 재검수 대상이 되며, 이 시점에는 재생성도 통지도 나가지 않아야 한다 — 수정만으로 통지가 나가면 불합격이다. 【검증】DB: 라벨 변경은 LS_DATA_LBL(+LS_DATA_LBL_HSTRY) 반영·동일 RAW_SN 유지(버전 업 아님) · 화면(SCREEN-019): 수정 반영
- **test_item**: 검수 완료 영상의 라벨/메타 수정이 반영되는지 확인
- **input_data**: 【영상 식별자】"1001" 【프레임 식별자】"5001" 【수정 내용】"라벨 수정(내부 변경 종류 LABEL_UPDATED — 통지 본문에는 실리지 않는다)"
- **screen_ref**: SCREEN-005
- **preconditions**: 검수 완료 영상이 존재한다

### [5]

- **seq**: 5
- **note**: TC-010 | auto_test / 재검수 승인 시점에 1회 통지 / changed_items 가 산출 폴더 파일명 리스트인지 확인
- **action**: 재검수 승인 후 수정 통지 발송
- **expected**: 수정 통지는 수정 시점이 아니라 검수자가 그 수정을 다시 승인한 시점에 발송된다 — 재검토 표시(LS_RAW_DATA_STATUS.REVLT_YN='Y')가 서 있는 동안 만료 flush 가 보류되고, 재승인으로 표시가 해제된 뒤 다음 flush tick 에 영상 1건 단위로 1회 발송되며 동일 작업 식별자를 유지한다(버전 업 아님). 수정 횟수와 무관하게 재승인 1건이 새 버전 폴더 1개와 통지 1건에 대응하므로, 재승인 전에 통지가 나가거나 재승인 1건에 통지가 2회 이상 나가면 불합격이다. 페이로드는 job_id · changed_items · ver_expln(선택) · output_ver_no(선택) 4필드이며, changed_items.images·jsons 는 산출 폴더의 실제 파일명({FRM_NO 4자리 zero-pad}.jpg/.json) 리스트여야 한다(내부 프레임 식별자를 실으면 관제 워커가 그대로 픽업하지 못한다). 목록 범위는 export 재생성을 동반했는지로 갈린다 — 동반이면 전 프레임 이미지·JSON 을 싣고(비우면 관제 보유본이 stale 로 고착된다), 재생성이 없으면 빈 리스트가 정상이며(없는 파일을 실으면 관제가 404 를 맞는다) 어느 경우든 통지 자체는 발송된다. ver_expln·output_ver_no 는 값이 없으면 키를 생략한다. ★ 변경 종류(LABEL_ADDED·LABEL_UPDATED·LABEL_DELETED·META_UPDATED)는 합격 조건이 아니다 — 지금은 관제로 보내지 않고 내부 디바운스 축적 키·감사 기록으로만 쓴다. ★ 디바운스는 같은 영상의 다중 변경을 1회로 합치는 축으로 그대로 살아 있다 — 폐기된 것은 창 만료만으로 발송된다는 부분뿐이다.
- **test_item**: 관제서버로 수정 통지가 발송되는지 확인
- **input_data**: 【이벤트 종류】"TASK_MODIFIED" 【작업 식별자(job_id)】"1001" 【변경 이미지 파일명(changed_items.images)】"[\"0338.jpg\"]" 【변경 JSON 파일명(changed_items.jsons)】"[\"0338.json\"]" 【버전 설명(ver_expln)】"(선택 — 없으면 키 생략)" 【산출 버전 번호(output_ver_no)】"2"
- **preconditions**: 순번4 수정 발생 + 검수자가 그 수정을 다시 검수해 승인(재승인)하여 재검토 표시가 해제됨

### [6]

- **seq**: 6
- **note**: TC-009 | manual_test
- **action**: 관제 조회 API 호출
- **expected**: GET /v1/tasks/{rawSn}/summary|labels|meta 세 경로 각각 200 을 반환하고, /summary 응답의 status 가 단계 1에서 전이된 값(APPROVED)과 일치한다. 이 시나리오에서 UC-009.main_flow 순번 6(관제가 조회 API 로 상세를 가져가 데이터마트 UPSERT)을 처음 검증한다 — 이전까지는 이 축을 exercise 하는 시나리오가 없었다.

### [7]

- **seq**: 7
- **note**: TC-009 | manual_test+DB SQL 확인
- **action**: 통지 전송 실패 → dead-letter/재등록 큐
- **expected**: 관제 엔드포인트가 5xx/타임아웃을 반환하면 통지가 즉시 유실되지 않고 LS_CONTROL_NOTIFY_FALLBACK 에 적재된다. 재시도가 최대 횟수를 초과하면 STTS_CD 가 DEAD_LETTER 로 고정된다. 이 페이로드는 이 시나리오가 자체 선언하는 핵심 테이블(LS_CONTROL_NOTIFY_FALLBACK)을 검증하는 첫 스텝이다 — 이전까지는 괄호 언급 1회뿐이었다. 【검증】DB: SELECT STTS_CD, RTRY_NMTM, SEND_RSLT_CD FROM LS_CONTROL_NOTIFY_FALLBACK WHERE RAW_SN=:rawSn

## status

draft

## objective

영상 단위 검수 승인 시 완료 상태 전이·라벨 스냅샷 후 완료 통지를, 검수 완료된 영상을 수정한 뒤 그 수정이 재검수에서 승인될 때 수정 통지를 관제서버에 단방향 발송하는 정상 흐름을 검증한다. 통지는 요약만 전달하며 본문·개인정보를 포함하지 않는다.

## related_apis

- API-074
- API-075
- API-076

## preconditions

_(empty)_

## verifies_nfrs

_(empty)_

## related_domains

- DOMAIN-016
- DOMAIN-005
- DOMAIN-010

## covers_use_cases

- UC-009
- UC-007

## exercises_screens

- SCREEN-019

## verifies_requirements

_(empty)_
