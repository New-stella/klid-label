---
logicraft_item: TEST-002
type: test_scenario
version: 12
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-20T08:27:28.074Z
status: CHANGED
prev_version: 11
content_hash: 169ffa7ed59ff9328ceafaf28b5981f360dbfefac7625cc99ac955fb5cfe9857
stale: false
raw: ./_raw/TEST-002.json
links:
  references: ["[[DOMAIN-005]]", "[[DOMAIN-010]]", "[[DOMAIN-011]]", "[[SCREEN-005]]", "[[UC-022]]"]
---

# 외부 VLM 시계열 메타 위탁·콜백 수신 정상 흐름

## kind

integration

## notes

시험시나리오 KLID-AT-IT-TS-002. 대외 연동 경계: 외부 VLM 시계열 메타 서비스(KLID-AT-II-003 위탁·KLID-AT-II-004 결과 콜백 POST /v1/vlm/callback). 관련 요구사항 RQ-SFR-08. 핵심 테이블 LS_DATA_META·LS_DATA_META_REVIEW·LS_WEBHOOK_IDEMPOTENCY(상관키 원장)·LS_MARKING. 정상 흐름(happy path)만 수록. 위탁은 논블로킹 제출이며 수락(ACK) 여부는 완료 핸들러가 원장에 비동기로 기록한다.

## steps

### [1]

- **seq**: 1
- **note**: TC-004 | auto_test / 논블로킹 제출 — ACK 왕복 동안 파이프라인 스레드를 점유하지 않는다
- **action**: VLM 시계열 위탁 제출(논블로킹)
- **expected**: 제출을 개시하고 즉시 반환한다(status="submitted"). 【검증】제출 전 선커밋 2건 — DB: SELECT CHNL_CD, STTS_CD, RAW_SN FROM LS_WEBHOOK_IDEMPOTENCY WHERE IDMP_KEY=:requestId → CHNL_CD='VLM'·STTS_CD='ISSUED' 1행; SELECT STTS_CD FROM LS_MARKING WHERE MARKING_SN=:markingSn → 'VLM_REQUESTED'. 수락(ACK) 수신 시 완료 핸들러가 원장을 ISSUED→ACCEPTED 로 비동기 전이한다. 외부 연동(외부 VLM 위탁 — 활성화 토글은 폐지됐다. 연동 주소가 주입돼 있으면 실 위탁, 없으면 실패한다. 건너뛰려면 검수자가 시계열 묶음을 수동 스킵해야 한다)
- **test_item**: 마킹 완료 영상을 외부 VLM 에 논블로킹 제출하며 상관키를 선커밋하는지 확인
- **input_data**: 【frame_policy.mode】"frame_selected" 【selected_frames】"[360, 1350, 1890]"(마킹 프레임 인덱스, 정렬·중복제거 후 벤더 상한 8건 이내 — 수동 마킹 경로 예시) 【event_type】"fire"(관제 인입값 그대로 위탁, 화이트리스트 사전 차단 없음) 【framerate】"10"(LS_MARKING.FRME_INTV_NOCS — 마킹 프레임 간격이며 FPS 가 아니다) 【상관키(request_id)】"(제출 시 시스템 자동발급 UUID — 원장 키)" 【콜백 경로】"서버 고정 /v1/vlm/callback(요청 본문 미포함)"
- **preconditions**: 마킹 완료된 비식별 영상이 존재한다

### [2]

- **seq**: 2
- **note**: TC-005 | auto_test / POST /v1/vlm/callback
- **action**: 결과 콜백 수신·진위 검증
- **expected**: 검증을 통과하고 수신 확인 결과 코드를 응답한다. 【검증】응답 200 + DB: SELECT IDMP_KEY, OTSD_JOB_ID FROM LS_DATA_META WHERE RAW_SN=:rawSn → IDMP_KEY=멱등키 기록(중복 콜백 무처리)
- **test_item**: VLM 결과 콜백을 수신해 진위(인증·시각·멱등키)를 검증하는지 확인
- **input_data**: 【멱등키】"vlm-1001" 【외부 작업 식별자】"VLM-JOB-1001" 【영상 식별자】"1001" 【처리 결과 코드】"SUCCESS" 【시계열 메타 항목 목록】"[{metaKey:activity, metaVal:보행 다수}]"
- **preconditions**: 순번1(TC-004) 제출이 개시됐다 — 상관키가 선커밋돼 있어 ACK 보다 콜백이 먼저 도착해도 역조회가 성립한다

### [3]

- **seq**: 3
- **note**: TC-005 | DB SQL 확인
- **action**: 메타 적재
- **expected**: 시계열 메타가 1건 적재된다. 【검증】DB: SELECT RAW_SN, META_KEY, META_VL FROM LS_DATA_META WHERE RAW_SN=:rawSn AND META_KEY=:metaKey → META_KEY='activity'·META_VL='보행 다수' 1행(RAW_SN+META_KEY UK)
- **test_item**: 검증 통과 메타가 시계열 메타로 적재되는지 확인
- **input_data**: 【영상 식별자】"1001" 【메타 키】"activity" 【메타 값】"보행 다수"
- **preconditions**: 순번1 검증 통과

### [4]

- **seq**: 4
- **note**: TC-005 | 메타 검토는 라벨링 캔버스(SC-005) 시계열 메타 패널에서 수행
- **action**: 검수 대기 진입
- **expected**: 시계열 메타가 검수 대기 상태로 검수큐에 등록된다. 【검증】DB: SELECT META_TYPE_CD, SRC_SYS_CD, RVW_STTS_CD FROM LS_DATA_META_REVIEW WHERE DATA_META_SN=:metaSn AND DATA_RAW_SN=:rawSn → META_TYPE_CD='VLM'·SRC_SYS_CD='AI_SERVER'·RVW_STTS_CD='PENDING' 1행 · 화면(SC-005): 우측 시계열 메타 패널에 검수 대기 메타 표시
- **test_item**: 적재 메타가 VLM 메타 검수 대기로 진입하는지 확인
- **input_data**: 【영상 식별자】"1001" 【메타 유형 코드】"VLM" 【검수 상태 코드】"PENDING"
- **screen_ref**: SCREEN-005
- **preconditions**: 순번2 적재 완료

## status

draft

## objective

마킹 완료 영상을 외부 VLM 서비스에 논블로킹으로 제출하고(상관키·마킹 상태 선커밋 후 subscribe), 수락(ACK)을 완료 핸들러가 원장에 비동기 기록한 뒤, 결과 콜백을 수신·검증해 시계열 메타를 적재하고 검수 대기로 진입시키는 정상 흐름을 검증한다.

## related_apis

_(empty)_

## preconditions

_(empty)_

## verifies_nfrs

_(empty)_

## related_domains

- DOMAIN-011
- DOMAIN-010
- DOMAIN-005

## covers_use_cases

- UC-022

## exercises_screens

- SCREEN-005

## verifies_requirements

_(empty)_
