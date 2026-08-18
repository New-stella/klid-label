---
logicraft_item: TEST-003
type: test_scenario
version: 14
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:52.845Z
status: NEW
prev_version: null
content_hash: 1929072c684aadc2de6684fc707d99e0be527f8d7b3a38e164f129a50a8ce302
stale: false
raw: ./_raw/TEST-003.json
links:
  references: ["[[API-060]]", "[[API-062]]", "[[API-165]]", "[[DOMAIN-007]]", "[[SCREEN-022]]", "[[SCREEN-023]]", "[[UC-001]]", "[[UC-002]]", "[[UC-010]]"]
---

# 외부 생성형 AI 증강 위탁·콜백 수신·활용 검수 정상 흐름

## kind

integration

## notes

시험시나리오 KLID-AT-IT-TS-003. 대외 연동 경계: 외부 생성형 AI(증강) 시스템(KLID-AT-II-005 위탁 · KLID-AT-II-006 결과 콜백). 관련 요구사항 RQ-SFR-07. 허용값 WINTER/NIGHT/RAIN. 핵심 테이블 LS_DATA_AUG · LS_DATA_AUG_RVW · LS_DATA_AUG_LBL_MAP · LS_DATA_RAW. 정상 흐름(happy path)만 수록.

[연동 규격] 위탁 시 전송 항목은 원본 증강 일련번호 · 증강 유형 · 멱등키 · 외부 작업 식별자 · 콜백 URL 이다(단순한 원본 영상 경로 전송이 아니다). 결과 콜백은 설정된 콜백 기본 URL 뒤에 /v1/genai/callback 을 붙인 경로로 들어온다.

[상태축] 생성 결과 축(AUG_PROC_STTS_CD, 웹훅이 소유)과 사람의 사용·폐기 판단 축(LS_DATA_AUG_RVW.RVW_STTS_CD)은 분리되어 있으며 accept/reject 는 리뷰 행만 쓴다(ADR-045). 파생 영상의 부모 참조 컬럼은 ORGNL_RAW_SN 이다.

[좌표 복사] 순번4 의 '증강 3종은 원본과 해상도가 같아 좌표를 그대로 복사한다' 는 현행 정책이다(UC-002). 좌표를 배율로 재계산하는 것은 해상도 파생(RESL_*) 축이며 이 시나리오 범위 밖이다.

## steps

### [1]

- **seq**: 1
- **note**: TC-006 | manual_test(요청 UI)+auto_test / POST /v1/augments/request · 생성 조건 prompt 5필드(time·season·weather·terrain·severity) 전부 필수 · 동일 (영상 × 종류) 중복 요청은 차단하지 않는다(ADR-044) · 외부 위탁 전송은 AFTER_COMMIT + 비동기 디스패치다
- **action**: 증강 위탁 요청
- **expected**: 요청이 수락되어 위탁 식별자(jobId)와 예약 행이 확정된다. 【검증】DB: SELECT DATA_AUG_SN, SRC_SN, AUG_TYPE_CD, AUG_PROC_STTS_CD, OTSD_JOB_ID, PROMPT_CN FROM LS_DATA_AUG WHERE IDMP_KEY=:idempotencyKey → AUG_TYPE_CD='WINTER'·AUG_PROC_STTS_CD='PENDING'(생성 결과 축 초기값)·PROMPT_CN=요청 시 전송한 생성 조건 원문(JSON) 1행 · 화면(SC-022 증강 요청→SC-023 결과): 종류(WINTER/NIGHT/RAIN) 선택 + 생성 조건 입력 후 위탁
- **test_item**: 검수 완료 영상을 증강 종류·생성 조건과 함께 외부 증강에 위탁하는지 확인
- **input_data**: 【증강 행 식별자】"5001" 【증강 종류】"WINTER" 【생성 조건】"{time:NIGHT, season:WINTER, weather:RAIN, terrain:ROAD, severity:HIGH}" 【멱등키】"aug-2001" 【외부 작업 식별자】"AUG-JOB-2001" 【콜백 회신 경로】"https://authoring/v1/genai/callback"
- **screen_ref**: SCREEN-022
- **preconditions**: 검수 완료 영상이 존재하고 증강 종류가 허용값이다

### [2]

- **seq**: 2
- **note**: TC-007 | auto_test / POST /v1/genai/callback · 멱등 처리
- **action**: 결과 콜백 수신·진위 검증
- **expected**: 검증을 통과하고 수신 확인 결과 코드를 응답한다. 【검증】응답 200 + DB: SELECT IDMP_KEY, AUG_PROC_STTS_CD FROM LS_DATA_AUG WHERE IDMP_KEY=:idempotencyKey → 생성 성공으로 AUG_PROC_STTS_CD='ACCEPTED'(생성 결과 축 — 웹훅이 소유하며 사람의 검수 결정이 아니다)·중복 콜백은 멱등 흡수(무처리)(회신 본문의 반영 여부는 false — 인계가 실제로 일어났을 때만 true 다) · 진위 검증 3계층: 허용 출처 목록 밖 요청은 403(목록 미설정이면 전면 차단)·반복 시도는 429·본문 상한 초과는 413·우리가 발급하지 않은 요청 식별자는 401(발급 원장에 있는 값만 처리)
- **test_item**: 증강 결과 콜백을 수신해 진위를 검증하는지 확인
- **input_data**: 【request_id(멱등키)】"aug-2001" 【job_id(외부 작업 식별자)】"AUG-JOB-2001" 【status】"SUCCEEDED" 【증강 행 식별자】"5001" 【증강 종류】"WINTER" 【results[0].output_file_path】"/nas/aug/2001_winter.mp4" 【results[0].media_type】"VIDEO" 【results[0].generated_data_id】"gen-2001"
- **preconditions**: 증강 위탁이 성공했다(TC-006)

### [3]

- **seq**: 3
- **note**: TC-007 | DB SQL 확인 / 새 영상=새 식별번호 · 부모 참조 컬럼은 ORGNL_RAW_SN(V82 개명, 구 PARENT_RAW_SN)
- **action**: 새 영상 등록
- **expected**: 원본을 부모로 참조하는 새 영상이 미검수 상태로 1건 등록된다. 【검증】DB: SELECT RAW_SN, ORGNL_RAW_SN, DATA_STTS_CD, RAW_FILE_PATH_NM FROM LS_DATA_RAW WHERE ORGNL_RAW_SN=:orgnlRawSn → 신규 RAW_SN·ORGNL_RAW_SN=원본·DATA_STTS_CD='PENDING' 1행; RAW_FILE_PATH_NM 이 부모의 LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM 값에서 파생된 파생 전용 복사 경로이고 부모의 원본(비-비식별) 경로가 아님을 확인(부모 원본 경로 폴백 시 PII 유출, CWE-359); 촬영환경·개인정보 판정 메타(ANONY_INCL_YN/PSDO_INCL_YN/PRVC_INCL_YN 등)가 부모 값으로 생성 시점 1회 계승됐는지 확인
- **test_item**: 원본을 참조하는 새 증강 영상이 등록되는지 확인
- **input_data**: 【영상 식별자(신규)】"2101" 【원본 영상 식별자(ORGNL_RAW_SN)】"2001" 【증강 종류】"WINTER" 【처리 단계 코드】"PENDING"
- **preconditions**: 순번2 결과 콜백 수신·진위 검증 통과

### [4]

- **seq**: 4
- **note**: TC-007 | DB SQL 확인 / 해상도 동일—좌표 그대로 복사
- **action**: 원본 라벨 복사
- **expected**: 원본 라벨이 좌표 재계산 없이 그대로 복사되고 검수 대기를 시작한다. 【검증】DB: SELECT COUNT(*) FROM LS_DATA_AUG_LBL_MAP WHERE DATA_AUG_SN=:dataAugSn AND COORD_RECALC_YN='N' → 원본 라벨 수(예 12)만큼·COORD_RECALC_YN='N'(좌표 재계산 없음)
- **test_item**: 원본 라벨이 증강 영상 라벨로 복사·매핑되는지 확인
- **input_data**: 【좌표 재계산 여부】"N" 【원본 라벨 수】"12"
- **preconditions**: 순번3 새 영상 등록

### [5]

- **seq**: 5
- **note**: TC-008 | manual_test+DB SQL 확인 / 활용 검수 UC-010(반려는 본 편 제외) · 판단 축은 리뷰 행 단독이며 AUG_PROC_STTS_CD 는 건드리지 않는다(ADR-045)
- **action**: 증강 결과 활용 수락
- **expected**: 활용 여부가 리뷰 축에 '채택'으로 기록되고 검토자·검수일시가 남는다. 【검증】DB: SELECT RVW_STTS_CD, RVW_ID, RVW_DT FROM LS_DATA_AUG_RVW WHERE DATA_AUG_SN=:dataAugSn → RVW_STTS_CD='ACCEPTED'·RVW_ID=검수자 sub·RVW_DT 기록 1행; 생성 결과 축은 불변 — SELECT AUG_PROC_STTS_CD FROM LS_DATA_AUG WHERE DATA_AUG_SN=:dataAugSn → 콜백이 남긴 'ACCEPTED' 그대로(검수로 전이되지 않는다); 등재 게이트는 리뷰 축이므로 채택된 파생만 작업목록·배정 대상이 된다 · 화면(SC-023): 프레임 페어 확인 후 '채택' 버튼
- **test_item**: 검수자가 증강 결과 화면에서 활용 수락 처리하고 그 판단이 리뷰 축에만 기록되는지 확인
- **input_data**: 【증강 행 식별자】"5001" 【검수자】"reviewer01" 【결정】"수락"
- **screen_ref**: SCREEN-023
- **preconditions**: 미검수(검수 대기) 증강 영상이 존재한다

## status

draft

## objective

검수 완료 영상을 생성 조건(prompt 5필드)과 함께 외부 증강 시스템(겨울·야간·우천)에 위탁하고, 결과 콜백을 수신·검증해 원본을 ORGNL_RAW_SN 으로 참조하는 새 영상을 등록하고 원본 라벨을 복사한 뒤, 증강 결과의 활용 여부를 리뷰 축에 채택으로 기록하는 정상 흐름을 검증한다.

## related_apis

- API-060
- API-165
- API-062

## preconditions

_(empty)_

## verifies_nfrs

_(empty)_

## related_domains

- DOMAIN-007

## covers_use_cases

- UC-001
- UC-002
- UC-010

## exercises_screens

- SCREEN-022
- SCREEN-023

## verifies_requirements

_(empty)_
