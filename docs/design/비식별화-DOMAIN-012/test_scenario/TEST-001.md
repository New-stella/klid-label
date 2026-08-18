---
logicraft_item: TEST-001
type: test_scenario
version: 10
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:55.874Z
status: NEW
prev_version: null
content_hash: 4d8ff59703b54aaf64751fdcc2e668475f9fc76d3f8e85582443eebf0ebda155
stale: false
raw: ./_raw/TEST-001.json
links:
  references: ["[[DOMAIN-003]]", "[[DOMAIN-011]]", "[[DOMAIN-012]]", "[[SCREEN-008]]", "[[UC-011]]", "[[UC-018]]"]
---

# 관제 학습용 영상 적재 후 선두 비식별 처리(외부 위탁·폴링) 정상 흐름

## kind

integration

## notes

시험시나리오 KLID-AT-IT-TS-001. 대외 연동 경계: 비식별 서버(KLID-AT-II-001 위탁·KLID-AT-II-002 진행 폴링) + 관제 인입 원장 픽업 적재(공유 클립 마스터 스캔은 적재 주체 반전으로 폐지). 관련 요구사항 RQ-SFR-09-01·RQ-SFR-11-04. 핵심 테이블 LS_DATA_RAW·LS_DEIDENT_PROC_LOG. 정상 흐름(happy path)만 수록.

## steps

### [1]

- **seq**: 1
- **note**: TC-001 | auto_test / 배치 연동(화면 없음)
- **action**: 미처리 인입 행 조회
- **expected**: 미처리 인입 행만 조회되고 이미 처리된 행은 제외된다. 【검증】DB: SELECT RCPTN_SN, VMS_CLIP_ID, RAW_FILE_PATH_NM, VDO_LEN_SEC FROM LS_DATA_INGEST WHERE PRCS_STTS_CD='PENDING' → 미처리 인입 행만 수신일시 순으로 반환(읽기 전용)
- **test_item**: 관제서버가 인입 원장에 등록한 미처리 행만 조회되는지 확인
- **input_data**: 【클립 식별자】"CCTV-001" 【처리 상태】"PENDING" 【원본 파일 경로】"/vms/clip_0001.mp4" 【영상 길이(ms)】"45000"
- **preconditions**: 인입 원장에 미처리 행이 존재한다

### [2]

- **seq**: 2
- **note**: TC-001 | DB SQL 확인 / 원본 경로 보존
- **action**: 영상 적재
- **expected**: 새 영상이 처리 대기 상태로 1건 등록되고 원본 경로가 보존된다. 【검증】DB: SELECT RAW_SN, VMS_CLIP_ID, RAW_FILE_PATH_NM, DATA_STTS_CD FROM LS_DATA_RAW WHERE VMS_CLIP_ID=:vmsClipId → DATA_STTS_CD='PENDING'·원본경로 보존 1행 · 화면(SC-008): 신규 1행이 처리단계 '대기'로 노출
- **test_item**: 조회된 클립이 저작도구 영상으로 1건 적재되는지 확인
- **input_data**: 【클립 식별자】"CCTV-001" 【원본 파일 경로】"/vms/clip_0001.mp4" 【영상 길이(ms)】"45000"
- **screen_ref**: SCREEN-008
- **preconditions**: 순번1에서 대상 클립을 확보한다

### [3]

- **seq**: 3
- **note**: TC-002 | auto_test / 실 KPST 연동(base-url 은 배포 환경변수로 주입) 마스킹 방식·마스킹 범위 배율·프레임 저장 여부는 상수가 아니라 시스템 설정(kpst.deid.masking-type · kpst.deid.masking-range · kpst.deid.db-save)에서 조달되므로, 시험 전에 설정한 값이 위탁 본문에 그대로 실리는지 함께 확인한다. 설정이 없거나 허용 목록 밖이면 규격 기본값으로 대체된다.
- **action**: 비식별 위탁 요청
- **expected**: 위탁 제출이 논블로킹으로 개시된다 — 응답은 제출 개시 확인이며 이 시점에 처리결과코드·프로젝트 식별자가 보장되지 않는다(수락 ACK 는 비동기로 별도 기록된다). 【검증】외부 연동(실제 KPST 비식별 위탁 — Resilience4j 적용): 위탁 요청 응답 수신 확인 — 적재 결과는 순번4 DB(LS_DEIDENT_PROC_LOG)로 확인 · 위탁 본문의 마스킹 방식·마스킹 범위 배율·프레임 저장 여부가 시험 전 설정값과 일치
- **test_item**: 적재 직후 외부 비식별 솔루션에 프로젝트 생성·영상 위탁이 성공하는지 확인
- **input_data**: 【프로젝트명】"raw1001" 【요청자】"authoring" 【입력 경로(원본 부모디렉토리·끝슬래시)】"/vms/" 【대상 파일 목록】"[clip_0001.mp4]" 【결과 저장 경로】"/nas-storage/videos/1001/" 【마스킹 종류】"0" 【마스킹 범위 배율】"1.0" 【프레임 저장 여부】"0"
- **preconditions**: 처리 대기 상태의 적재 영상이 존재한다

### [4]

- **seq**: 4
- **note**: TC-002 | DB SQL 확인 / 재시도 최대 3회(점증 대기)
- **action**: 처리 이력 기록
- **expected**: 비식별 처리 이력이 '요청'·폴링 대기 상태로 1건 기록된다(제출이 논블로킹이므로 프로젝트 식별자는 ACK 비동기 기록 완료 후에 채워진다). 【검증】DB: SELECT DATA_RAW_SN, PROC_STTS_CD, POLL_STTS_CD, DE_IDNTF_PJT_ID FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN=:rawSn → PROC_STTS_CD='REQUESTED'·POLL_STTS_CD='WAITING' 1행(ACK 반영 후 DE_IDNTF_PJT_ID=9001)
- **test_item**: 위탁 상태가 '요청' 이력으로 기록되는지 확인
- **input_data**: 【영상 식별자】"1001" 【처리 상태 코드】"REQUESTED" 【폴링 상태 코드】"WAITING" 【비식별 프로젝트 식별자】"9001"
- **preconditions**: 순번3 위탁이 성공한다

### [5]

- **seq**: 5
- **note**: TC-003 | auto_test / 실 KPST 폴링 연동
- **action**: 비식별 진행 폴링
- **expected**: 진행 상태(처리상태·진행률)를 응답받고 폴링 대기→폴링중으로 전이한다. 【검증】DB: SELECT POLL_STTS_CD, POLL_ATMPT_CNT, POLL_LAST_DT FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN=:rawSn → POLL_STTS_CD='POLLING'·POLL_ATMPT_CNT≥1
- **test_item**: 30초 주기로 외부 비식별 진행 상태를 조회하는지 확인
- **input_data**: 【요청자 식별자】"authoring" 【비식별 프로젝트 식별자】"9001"
- **preconditions**: 비식별 처리가 '요청' 상태다

### [6]

- **seq**: 6
- **note**: TC-003 | DB SQL 확인 / 완료 상태 정상 수신 케이스
- **action**: 완료 확인
- **expected**: 비식별 처리가 '성공'으로 전이되고 비식별 영상 경로가 기록된다. 【검증】DB: SELECT PROC_STTS_CD, POLL_STTS_CD, DE_IDNTF_FILE_PATH_NM FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN=:rawSn → PROC_STTS_CD='SUCCEEDED'·POLL_STTS_CD='DOWNLOADED'·DE_IDNTF_FILE_PATH_NM=비식별 경로(NOT NULL)
- **test_item**: 전체 완료 상태 확인 후 비식별 결과 경로를 회수하는지 확인
- **input_data**: 【영상 식별자】"1001" 【처리 상태 코드】"SUCCEEDED"
- **preconditions**: 순번5에서 전체 완료 상태를 수신한다

### [7]

- **seq**: 7
- **note**: TC-003 | DB SQL 확인 / 상태 전이 검증
- **action**: 마킹 진입 전이
- **expected**: 영상이 비식별 완료로 표시되고 '마킹 대기' 상태로 전이된다. 【검증】DB: SELECT DATA_STTS_CD, DE_IDENT_YN FROM LS_DATA_RAW WHERE RAW_SN=:rawSn → DATA_STTS_CD='MARKING_READY'·DE_IDENT_YN='Y' · 화면(SC-008): 처리단계 '마킹 대기'로 전이(비동기라 즉시 아님·폴링 필요)
- **test_item**: 비식별 완료 시 영상이 마킹 진입 상태로 전이되는지 확인
- **input_data**: 【영상 식별자】"1001" 【비식별 완료 여부】"Y" 【처리 단계 코드】"MARKING_READY"
- **screen_ref**: SCREEN-008
- **preconditions**: 순번6 완료 처리

## status

draft

## objective

관제서버가 인입 원장에 직접 등록한 미처리 영상을 주기 배치가 픽업해 저작도구에 적재하고, 적재 직후 외부 비식별 솔루션에 위탁한 뒤 진행 폴링으로 완료를 확인해 비식별 완료·마킹 진입 상태까지 이르는 정상 흐름을 검증한다.

## related_apis

_(empty)_

## preconditions

_(empty)_

## verifies_nfrs

_(empty)_

## related_domains

- DOMAIN-003
- DOMAIN-012
- DOMAIN-011

## covers_use_cases

- UC-018
- UC-011

## exercises_screens

- SCREEN-008

## verifies_requirements

_(empty)_
