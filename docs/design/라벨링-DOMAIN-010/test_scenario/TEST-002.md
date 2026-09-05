---
logicraft_item: TEST-002
type: test_scenario
version: 15
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-25T10:52:10.078Z
status: CHANGED
prev_version: 12
content_hash: 690394046c92c211d149563ba07772a236646c412927b88c7d331590dad05154
stale: false
raw: ./_raw/TEST-002.json
links:
  references: ["[[DOMAIN-005]]", "[[DOMAIN-010]]", "[[DOMAIN-011]]", "[[SCREEN-005]]", "[[UC-022]]"]
---

# 외부 VLM 시계열 메타 위탁·콜백 수신 정상 흐름

## kind

integration

## notes

시험시나리오 KLID-AT-IT-TS-002. 대외 연동 경계: 외부 VLM 시계열 메타 서비스(KLID-AT-II-003 위탁·KLID-AT-II-004 결과 콜백 POST /v1/vlm/callback). 관련 요구사항 RQ-SFR-08. 핵심 테이블 LS_DATA_META·LS_DATA_META_REVIEW·LS_WEBHOOK_IDEMPOTENCY(상관키 원장)·LS_MARKING. 정상 흐름(happy path)만 수록. 위탁은 묘사(CoT)·추가 질문(VQA) 두 축 이중 제출이자 논블로킹 제출이며, 수락(ACK) 여부는 완료 핸들러가 원장에 비동기로 기록한다. selected_frames 상한 절단 같은 경계 검증은 이 정상 흐름 시나리오가 아니라 수용기준에서 다룬다. 어노테이션 조달 축은 2026-08-24 에 "추가 질문 결과는 답변(answer) 축 초안으로 간다"(묘사를 캡션·사고 단계 축으로 보내는 지시는 그때 폐기)로 확정했다가, 2026-08-25 사업 담당 회신으로 다시 캡션·사고 단계 축으로 되돌렸다 — 같은 축이 이틀 만에 두 번 뒤집혔으므로 경위를 남겨 세 번째 반전을 막는다. 질문 문구는 저작도구가 보관한다(검증 이벤트 유형 LS_VRFC_EVNT_TYPE · 유형별 질문 LS_VRFC_EVNT_QSTN · 마킹 선택값 LS_MARKING.VRFC_EVNT_QSTN_SN) — 연동 규격이 질문 문장을 서버가 관리하는 것으로 못 박아 콜백에 실려 오지 않는데, 이후 질의를 받는 방향으로 규격이 바뀔 수 있다는 협의가 있어 그 전환을 미리 준비한 것이다. 다만 지금 위탁 요청 본문에는 질문을 실을 자리가 없어, 첫 번째가 아닌 질문을 고르면 기록된 질문과 사업자가 실제로 쓴 질문이 달라진다 — 인지하고 수용한 잔여 위험이다. 어노테이션 적재 테이블은 LS_EVNT_ANNO(RAW_SN 당 1건, ANNO_CN JSONB)이고 검토 상태는 LS_EVNT_ANNO_REVIEW 가 갖는다. 기적재분은 백필하지 않고 신규 수신분부터 적용한다.

## steps

### [1]

- **seq**: 1
- **note**: TC-004 | auto_test / 논블로킹 제출 — ACK 왕복 동안 파이프라인 스레드를 점유하지 않는다. 위탁이 2건이므로 상관키도 2건이다
- **action**: 시계열 위탁 이중 제출(논블로킹)
- **expected**: 두 위탁 모두 제출을 개시하고 즉시 반환한다(status="submitted"). 【검증】제출 전 선커밋 — DB: SELECT CHNL_CD, STTS_CD, RAW_SN FROM LS_WEBHOOK_IDEMPOTENCY WHERE IDMP_KEY IN (:describeRequestId, :describeSubRequestId) → CHNL_CD='VLM'·STTS_CD='ISSUED' 2행(위탁 1건당 1행); SELECT STTS_CD FROM LS_MARKING WHERE MARKING_SN=:markingSn → 'VLM_REQUESTED'. 동기 응답은 위탁마다 {request_id, status} 이며 status='accepted' 만 수락으로 인정한다. 수락(ACK) 수신 시 완료 핸들러가 원장을 ISSUED→ACCEPTED 로 비동기 전이한다. 외부 연동(외부 VLM 위탁 — 활성화 토글은 폐지됐다. 연동 주소가 주입돼 있으면 실 위탁, 없으면 실패한다. 건너뛰려면 검수자가 시계열 묶음을 수동 스킵해야 한다)
- **test_item**: 마킹 완료 영상을 묘사(CoT)·추가 질문(VQA) 두 축으로 이중 위탁하고 각 요청의 상관키를 선커밋하는지 확인
- **input_data**: 【위탁 대상】"묘사(CoT) POST /v1/videovlm-klid/describe + 추가 질문(VQA) POST /v1/videovlm-klid/describe-sub 이중 제출(요청 2건)" 【frame_policy.mode】"frame_selected"(마킹 유무와 무관한 단일 모드) 【selected_frames】"[360, 1350, 1890]"(마킹 본문의 프레임 인덱스를 정렬·중복제거한 값. 그 본문에서 인덱스를 하나도 얻지 못하면 이 항목 없이 frame_interval 모드로 내린다. 값은 0 이상이며 건수는 최대 600건 — 이 순번은 정상 흐름이라 상한 미만 표본을 쓴다) 【프레임 추출 간격】"연동 측이 지정하지 않는다 — frame_policy 에 간격·장수 필드를 두지 않고 외부 분석 서버가 관리한다" 【event_type】"fire"(관제 인입값 그대로 위탁, 화이트리스트 사전 차단 없음) 【상관키(request_id)】"(요청마다 시스템이 따로 발급하는 UUID 2건 — 원장 키이자 콜백 역조회 키)" 【콜백 경로】"서버가 고정한 /v1/vlm/callback 을 요청 바디 callback_url 로 전달"
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

### [5]

- **seq**: 5
- **note**: 묘사 축 — 시계열 서술 전문 적재(순번3)와 같은 콜백에서 함께 일어난다
- **action**: 묘사 결과의 사고 단계 자동채움
- **expected**: 캡션 후보 c1 의 사고 단계 1단계가 「상황」 줄의 값으로 채워진다. 【검증】DB: SELECT ANNO_CN FROM LS_EVNT_ANNO WHERE RAW_SN=:rawSn → caption.c1.cot 의 '1단계' 키가 '차량에서 화재가 발생해 연기가 번진다' — 그 줄의 줄바꿈까지만 값으로 쓰고 다음 라벨 줄을 이어붙이지 않는다. 선행 공백과 콜론 앞뒤 공백은 관용 처리하고, 「상황」이 여러 번 나오면 첫 번째를 쓴다. 「상황」 줄이 없으면 1단계 키를 아예 만들지 않는다 — 빈 값을 넣지 않는다. 사고 단계는 1단계 키만 만들고 2단계 이후 키는 만들지 않는다. 화면(SC-005): 우측 '메타' 탭 이벤트 어노테이션 패널에 표시
- **test_item**: 묘사(describe) 콜백 수신 시 시계열 서술 전문이 적재되면서 그 전문의 「상황」 라벨 줄만 캡션 후보 c1 의 사고 단계 1단계로 들어가는지 확인
- **input_data**: 【묘사 전문】"- 장소: 야간 주차장\n- 날씨: 맑음\n- 상황: 차량에서 화재가 발생해 연기가 번진다"(- 라벨: 값 줄 단위로 오는 서술) 【파싱 대상 라벨】"상황"(라벨 목록 전체·순서·필수 여부에 의존하지 않고 이 한 줄만 찾는다) 【영상 식별자】"1001"
- **screen_ref**: SCREEN-005
- **preconditions**: 순번3 적재 완료 — 이벤트 분류(event_class) 조달값이 있어 어노테이션 행 생성 대상이다

### [6]

- **seq**: 6
- **note**: 추가 질문 축 — 답변·근거 서술은 사람이 확정하므로 자동채움 대상이 아니다
- **action**: 추가 질문 결과의 캡션 본문 자동채움
- **expected**: 캡션 후보 c1 의 캡션 본문이 응답 서술로 채워진다. 【검증】DB: SELECT ANNO_CN FROM LS_EVNT_ANNO WHERE RAW_SN=:rawSn → caption.c1.caption_text='차량 하부에서 불꽃이 관측되고 주변으로 연기가 확산되어 화재로 판단된다'. 답변(answer) 칸은 자동으로 채우지 않는다 — 사람이 확정할 공란이다. 근거 서술(evidence)도 채우지 않는다 — 자유 서술이라 형식이 고정돼 있지 않아 파싱에 의존하지 않는다. 사고 단계 2단계 이후 키도 만들지 않는다
- **test_item**: 추가 질문(describe-sub) 콜백 수신 시 응답 서술이 캡션 후보 c1 의 캡션 본문으로 들어가고 답변 칸은 공란으로 남는지 확인
- **input_data**: 【상관키(request_id)】"순번1 의 추가 질문(describe-sub) 위탁에서 발급된 값"(콜백 본문에 창구 식별자가 없어 이 값으로 축을 역조회한다) 【결과(results)】"{description: 차량 하부에서 불꽃이 관측되고 주변으로 연기가 확산되어 화재로 판단된다}" 【영상 식별자】"1001"
- **screen_ref**: SCREEN-005
- **preconditions**: 순번1 의 추가 질문 위탁 상관키가 선커밋돼 있다

### [7]

- **seq**: 7
- **note**: 두 창구가 같은 후보를 공유하므로 통째 교체는 한쪽 결과의 유실이 된다
- **action**: 두 창구 결과의 공존(도착 순서 무관)
- **expected**: 두 순서 모두에서 캡션 후보 c1 에 캡션 본문과 사고 단계 1단계가 함께 남는다. 【검증】DB: SELECT ANNO_CN FROM LS_EVNT_ANNO WHERE RAW_SN=:rawSn → caption.c1.caption_text 와 caption.c1.cot 의 '1단계' 키가 동시에 존재. 나중에 도착한 축이 먼저 도착한 축의 값을 지우지 않는다 — 후보를 통째로 교체하지 않고 자기 칸만 채운다
- **test_item**: 묘사·추가 질문 콜백이 어느 순서로 도착해도 같은 캡션 후보 c1 에 두 값이 공존하는지 확인
- **input_data**: 【도착 순서】"① 묘사 → 추가 질문 ② 추가 질문 → 묘사 두 순서를 각각 시행"(두 창구의 도착 순서는 보장되지 않는다) 【공유 후보】"c1"(두 창구가 같은 캡션 후보를 공유한다) 【영상 식별자】"1001"
- **screen_ref**: SCREEN-005
- **preconditions**: 순번5·순번6 의 두 콜백이 모두 도착했다

### [8]

- **seq**: 8
- **note**: 질문 문구는 콜백에 실려 오지 않으므로 저작도구가 보관한 목록에서 조달한다
- **action**: 질문 칸 조달
- **expected**: 질문 칸이 마킹 선택값이 가리키는 질문 내용으로 채워진다. 【검증】DB: SELECT ANNO_CN FROM LS_EVNT_ANNO WHERE RAW_SN=:rawSn → question=LS_VRFC_EVNT_QSTN.QSTN_CN(마킹 선택값이 가리키는 행). 선택값이 없거나 그 유형에 속하지 않는 질문이면 그 유형의 정렬순서 첫 번째 질문으로 되돌린다 — (VRFC_EVNT_TYPE_CD, SORT_SEQ) UNIQUE 가 「첫 번째」가 조회마다 흔들리지 않게 하는 결정성의 근거다. 마킹을 거치지 않는 경로는 언제나 그 유형의 첫 번째 질문을 쓴다. 검증 이벤트 유형이 미수신이거나 등록된 질문이 0건이면 질문 칸은 빈 채로 둔다 — 그래도 위탁 자체는 그대로 나간다(유형 표는 허용목록이 아니다)
- **test_item**: 어노테이션 질문 칸이 마킹에서 고른 질문으로 채워지고, 선택이 없으면 그 유형의 첫 번째 질문으로 채워지는지 확인
- **input_data**: 【마킹 선택값】"LS_MARKING.VRFC_EVNT_QSTN_SN"(작업자가 마킹 저장 시 고른 질문) 【대체값】"그 유형의 정렬순서 첫 번째 질문 — LS_VRFC_EVNT_QSTN.SORT_SEQ 최소" 【부재 조건】"검증 이벤트 유형이 미수신이거나 그 유형에 등록된 질문이 0건"
- **screen_ref**: SCREEN-005
- **preconditions**: 영상의 검증 이벤트 유형이 관제 인입 원장에서 조달돼 있고 그 유형에 질문이 등록돼 있다

### [9]

- **seq**: 9
- **note**: 자동채움은 사람이 콘텐츠를 고치는 경로가 아니므로 재검토 표식을 세우지 않는다
- **action**: 자동채움 보호 경계 유지
- **expected**: 다섯 경계 모두에서 자동채움이 억제되거나 무해하다. 【검증】① 승인 이력이 있으면 자동채움을 하지 않는다 ② 값이 이미 있는 칸은 덮지 않는다 ③ SELECT RVW_STTS_CD FROM LS_EVNT_ANNO_REVIEW WHERE EVNT_ANNO_SN=:annoSn → 종결 상태면 자동채움을 하지 않는다 ④ 같은 상관키 재전송은 멱등 처리로 무처리 skip 한다 ⑤ SELECT COUNT(*) FROM LS_EVNT_ANNO WHERE RAW_SN=:rawSn → 이벤트 분류 조달값이 없으면 0행(행을 만들지 않는다). 자동채움은 재검수를 발화시키지 않는다 — LS_RAW_DATA_STATUS.REVLT_YN 이 'Y' 로 바뀌지 않는다
- **test_item**: 어노테이션 자동채움이 기존 보호 경계를 그대로 지키는지 확인
- **input_data**: 【경계 조건】"① 승인 이력이 있는 영상 ② 그 칸에 이미 값이 있는 영상 ③ 어노테이션 검토가 종결된 영상 ④ 같은 상관키의 중복 콜백 ⑤ 이벤트 분류(event_class) 조달값이 없는 영상"
- **preconditions**: 순번5~순번8 의 자동채움 대상 영상

## status

draft

## objective

마킹 완료 영상을 외부 시계열 분석 서비스에 묘사(CoT)·추가 질문(VQA) 두 축으로 이중 위탁하고(요청마다 상관키를 따로 발급해 마킹 상태와 함께 선커밋한 뒤 논블로킹 제출), 수락(ACK)을 완료 핸들러가 원장에 비동기 기록한 뒤, 결과 콜백을 상관키로 역조회·검증해 서술을 시계열 메타로 적재하고 검수 대기로 진입시키는 정상 흐름을 검증한다. 이어서 두 창구의 콜백이 이벤트 어노테이션의 어느 칸을 채우는지도 함께 검증한다 — 묘사 전문의 「상황」 줄은 캡션 후보 c1 의 사고 단계 1단계로, 추가 질문 응답 서술은 같은 후보의 캡션 본문으로 들어가고 답변과 근거 서술은 사람이 확정할 공란으로 남는다.

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
