---
logicraft_item: TEST-002
type: test_scenario
version: 13
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-24T10:56:09.942Z
status: CHANGED
prev_version: 12
content_hash: bb90ea58b7c947c5d16ebab5bdad61f00f0ae437ae7f70bd091124b134529f11
stale: false
raw: ./_raw/TEST-002.json
links:
  references: ["[[DOMAIN-005]]", "[[DOMAIN-010]]", "[[DOMAIN-011]]", "[[SCREEN-005]]", "[[UC-022]]"]
---

# 외부 VLM 시계열 메타 위탁·콜백 수신 정상 흐름

## kind

integration

## notes

시험시나리오 KLID-AT-IT-TS-002. 대외 연동 경계: 외부 VLM 시계열 메타 서비스(KLID-AT-II-003 위탁·KLID-AT-II-004 결과 콜백 POST /v1/vlm/callback). 관련 요구사항 RQ-SFR-08. 핵심 테이블 LS_DATA_META·LS_DATA_META_REVIEW·LS_WEBHOOK_IDEMPOTENCY(상관키 원장)·LS_MARKING. 정상 흐름(happy path)만 수록. 위탁은 묘사(CoT)·추가 질문(VQA) 두 축 이중 제출이자 논블로킹 제출이며, 수락(ACK) 여부는 완료 핸들러가 원장에 비동기로 기록한다. selected_frames 상한 절단 같은 경계 검증은 이 정상 흐름 시나리오가 아니라 수용기준에서 다룬다.

## steps

### [1]

- **seq**: 1
- **note**: TC-004 | auto_test / 논블로킹 제출 — ACK 왕복 동안 파이프라인 스레드를 점유하지 않는다. 위탁이 2건이므로 상관키도 2건이다
- **action**: 시계열 위탁 이중 제출(논블로킹)
- **expected**: 두 위탁 모두 제출을 개시하고 즉시 반환한다(status="submitted"). 【검증】제출 전 선커밋 — DB: SELECT CHNL_CD, STTS_CD, RAW_SN FROM LS_WEBHOOK_IDEMPOTENCY WHERE IDMP_KEY IN (:describeRequestId, :describeSubRequestId) → CHNL_CD='VLM'·STTS_CD='ISSUED' 2행(위탁 1건당 1행); SELECT STTS_CD FROM LS_MARKING WHERE MARKING_SN=:markingSn → 'VLM_REQUESTED'. 동기 응답은 위탁마다 {request_id, status} 이며 status='accepted' 만 수락으로 인정한다. 수락(ACK) 수신 시 완료 핸들러가 원장을 ISSUED→ACCEPTED 로 비동기 전이한다. 외부 연동(외부 VLM 위탁 — 활성화 토글은 폐지됐다. 연동 주소가 주입돼 있으면 실 위탁, 없으면 실패한다. 건너뛰려면 검수자가 시계열 묶음을 수동 스킵해야 한다)
- **test_item**: 마킹 완료 영상을 묘사(CoT)·추가 질문(VQA) 두 축으로 이중 위탁하고 각 요청의 상관키를 선커밋하는지 확인
- **input_data**: 【위탁 대상】"묘사(CoT) POST /v1/videovlm-klid/describe + 추가 질문(VQA) POST /v1/videovlm-klid/describe-sub 이중 제출(요청 2건)" 【frame_policy.mode】"frame_selected"(마킹 유무와 무관한 단일 모드) 【selected_frames】"[360, 1350, 1890]"(마킹된 프레임 인덱스를 정렬·중복제거한 값. 마킹이 없으면 자동 선택된 프레임 리스트를 싣는다. 값은 0 이상이며 건수는 최대 600건 — 이 순번은 정상 흐름이라 상한 미만 표본을 쓴다) 【프레임 추출 간격】"연동 측이 지정하지 않는다 — frame_policy 에 간격·장수 필드를 두지 않고 외부 분석 서버가 관리한다" 【event_type】"fire"(관제 인입값 그대로 위탁, 화이트리스트 사전 차단 없음) 【상관키(request_id)】"(요청마다 시스템이 따로 발급하는 UUID 2건 — 원장 키이자 콜백 역조회 키)" 【콜백 경로】"서버가 고정한 /v1/vlm/callback 을 요청 바디 callback_url 로 전달"
- **preconditions**: 마킹 완료된 비식별 영상이 존재한다

### [2]

- **seq**: 2
- **note**: TC-005 | auto_test / POST /v1/vlm/callback
- **action**: 결과 콜백 수신·진위 검증
- **expected**: 검증을 통과하고 5초 이내에 2xx 로 수신 확인을 응답한다. 【검증】응답 200 + DB: SELECT IDMP_KEY FROM LS_DATA_META WHERE RAW_SN=:rawSn → IDMP_KEY=콜백 상관키(request_id) 기록. 같은 상관키의 재전송(벤더는 실패 시 최대 3회·5초 간격 재전송)은 멱등 처리로 무처리 skip 한다
- **test_item**: VLM 결과 콜백을 수신해 진위(인증·시각·멱등키)를 검증하고 상관키로 어느 위탁 축의 결과인지 역조회하는지 확인
- **input_data**: 【상관키(request_id)】"순번1 의 묘사(CoT) 위탁에서 발급된 값"(콜백 본문에 API 종류 식별자가 없어 이 값으로 축을 역조회한다) 【처리 상태(status)】"completed" 【결과(results)】"{description: 야간 주차장에서 차량 화재가 발생해 연기가 번지는 상황}"(자연어 평문 서술 1건) 【일치도(accuracy)·판정(detected)】"미포함 — 두 필드는 verify 전용이라 묘사·추가 질문 콜백에는 오지 않는다" 【영상 식별자】"1001"
- **preconditions**: 순번1(TC-004) 제출이 개시됐다 — 상관키 2건이 선커밋돼 있어 ACK 보다 콜백이 먼저 도착해도 역조회가 성립한다

### [3]

- **seq**: 3
- **note**: TC-005 | DB SQL 확인
- **action**: 메타 적재
- **expected**: 시계열 메타가 1건 적재된다. 【검증】DB: SELECT RAW_SN, META_KEY, META_VL FROM LS_DATA_META WHERE RAW_SN=:rawSn AND META_KEY=:metaKey → META_KEY='vlm.description'·META_VL='야간 주차장에서 차량 화재가 발생해 연기가 번지는 상황' 1행(RAW_SN+META_KEY UK)
- **test_item**: 검증 통과 결과 서술이 시계열 메타로 적재되는지 확인
- **input_data**: 【영상 식별자】"1001" 【메타 키】"vlm.description" 【메타 값】"야간 주차장에서 차량 화재가 발생해 연기가 번지는 상황"(순번2 콜백 results.description 원문)
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

마킹 완료 영상을 외부 시계열 분석 서비스에 묘사(CoT)·추가 질문(VQA) 두 축으로 이중 위탁하고(요청마다 상관키를 따로 발급해 마킹 상태와 함께 선커밋한 뒤 논블로킹 제출), 수락(ACK)을 완료 핸들러가 원장에 비동기 기록한 뒤, 결과 콜백을 상관키로 역조회·검증해 서술을 시계열 메타로 적재하고 검수 대기로 진입시키는 정상 흐름을 검증한다.

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
