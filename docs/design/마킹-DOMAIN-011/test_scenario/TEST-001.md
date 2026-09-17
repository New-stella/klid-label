---
logicraft_item: TEST-001
type: test_scenario
version: 14
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-17T01:12:40.238Z
status: CHANGED
prev_version: 13
content_hash: c1cba27f2e37957a147ec1ec2e69ceea63fcc729630e2b8331e4cfc1d6a5679f
stale: false
raw: ./_raw/TEST-001.json
links:
  references: ["[[API-167]]", "[[API-199]]", "[[DOMAIN-003]]", "[[DOMAIN-011]]", "[[DOMAIN-012]]", "[[SCREEN-008]]", "[[SCREEN-009]]", "[[UC-011]]", "[[UC-018]]"]
---

# 관제 학습용 영상 적재 후 선두 비식별 처리(외부 위탁·폴링) 정상 흐름 + 수락 응답 미관측 회수·취소 종결 대비 + 비식별 제외 분기

## kind

integration

## notes

시험시나리오 KLID-AT-IT-TS-001. 대외 연동 경계: 비식별 서버(KLID-AT-II-001 위탁·KLID-AT-II-002 진행 폴링) + 관제 인입 원장 픽업 적재(공유 클립 마스터 스캔은 적재 주체 반전으로 폐지). 관련 요구사항 RQ-SFR-09-01·RQ-SFR-11-04. 핵심 테이블 LS_DATA_RAW·LS_DEIDENT_PROC_LOG. 정상 흐름(순번1~7)에 더해 수락 응답 미관측 건의 수락 대기 유예 만료 회수(순번8)와 그 대비인 취소 종결(순번9)을 수록한다. 유예 값·폴링 주기는 설정값이라 이 문서에 숫자를 두지 않는다. 순번8·9 를 확인하는 자동 시험이 실재하는지는 확인되지 않았다. 순번10~12 는 비식별 제외 분기(ADR-066)를 다룬다 — 적재는 출처유형과 무관하게 같고 제외 판정은 선두 비식별 단계 처리 시점에 한 번 하므로, 순번1~9 의 입력 영상은 출처유형이 제외 목록에 없는 것(예: ORIGINAL)으로 둔다. 제외 목록은 배포 설정 파일 전용이라 설정을 바꾸는 순번은 재기동이 필요하다. 검수 완료 영상의 재비식별은 제외 축을 타지 않으며 이 시나리오의 범위 밖이다. 순번13~15 는 선두 비식별 실패의 수동 복구(배치 재시작 — 건별 API-167 · 일괄 API-199)를 다룬다. 재시작 거부 판정(파생영상·승인 이력·열린 비식별 누락 신고·진행 중 위탁·동시 요청)과 실패 종결별 잠금 해제는 수용기준 AC-1134·AC-1135 가 담당하며 이 시나리오는 대표 흐름만 둔다(정상 복구는 AC-1133).

## steps

### [1]

- **seq**: 1
- **note**: TC-001 | auto_test / 배치 연동(화면 없음)
- **action**: 미처리 인입 행 조회
- **expected**: 미처리 인입 행만 조회되고 이미 처리된 행은 제외된다. 【검증】DB: SELECT RCPTN_SN, VMS_CLIP_ID, RAW_FILE_PATH_NM, VDO_LEN_SEC FROM LS_DATA_INGEST WHERE PRCS_STTS_CD='PENDING' → 미처리 인입 행만 수신일시 순으로 반환(읽기 전용)
- **test_item**: 관제서버가 인입 원장에 등록한 미처리 행만 조회되는지 확인
- **input_data**: 【클립 식별자】"CCTV-001" 【처리 상태】"PENDING" 【원본 파일 경로】"/vms/clip_0001.mp4" 【영상 길이(ms)】"45000" 【출처유형】"ORIGINAL"
- **preconditions**: 인입 원장에 미처리 행이 존재한다

### [2]

- **seq**: 2
- **note**: TC-001 | DB SQL 확인 / 원본 경로 보존
- **action**: 영상 적재
- **expected**: 새 영상이 처리 대기 상태로 1건 등록되고 원본 경로가 보존된다. 【검증】DB: SELECT RAW_SN, VMS_CLIP_ID, RAW_FILE_PATH_NM, DATA_STTS_CD FROM LS_DATA_RAW WHERE VMS_CLIP_ID=:vmsClipId → DATA_STTS_CD='PENDING'·원본경로 보존 1행 · 화면(SC-008): 신규 1행이 처리단계 '대기'로 노출
- **test_item**: 조회된 클립이 저작도구 영상으로 1건 적재되는지 확인
- **input_data**: 【클립 식별자】"CCTV-001" 【원본 파일 경로】"/vms/clip_0001.mp4" 【영상 길이(ms)】"45000" 【출처유형】"ORIGINAL"
- **screen_ref**: SCREEN-008
- **preconditions**: 순번1에서 대상 클립을 확보한다

### [3]

- **seq**: 3
- **note**: TC-002 | auto_test / 실 KPST 연동(base-url 은 배포 환경변수로 주입) 마스킹 방식·마스킹 범위 배율·프레임 저장 여부는 상수가 아니라 시스템 설정(kpst.deid.masking-type · kpst.deid.masking-range · kpst.deid.db-save)에서 조달되므로, 시험 전에 설정한 값이 위탁 본문에 그대로 실리는지 함께 확인한다. 설정이 없거나 허용 목록 밖이면 규격 기본값으로 대체된다.
- **action**: 비식별 위탁 요청
- **expected**: 위탁 제출이 논블로킹으로 개시된다 — 응답은 제출 개시 확인이며 이 시점에 처리결과코드·프로젝트 식별자가 보장되지 않는다(수락 ACK 는 비동기로 별도 기록된다). 【검증】외부 연동(실제 KPST 비식별 위탁 — Resilience4j 적용): 위탁 요청 응답 수신 확인 — 적재 결과는 순번4 DB(LS_DEIDENT_PROC_LOG)로 확인 · 위탁 본문의 마스킹 방식·마스킹 범위 배율·프레임 저장 여부가 시험 전 설정값과 일치
- **test_item**: 적재 직후 외부 비식별 솔루션에 프로젝트 생성·영상 위탁이 성공하는지 확인
- **input_data**: 【프로젝트명】"raw1001" 【요청자】"authoring" 【입력 경로(원본 부모디렉토리·끝슬래시)】"/vms/" 【대상 파일 목록】"[clip_0001.mp4]" 【결과 저장 경로】"/nas-storage/videos/1001/" 【마스킹 종류】"0" 【마스킹 범위 배율】"1.0" 【프레임 저장 여부】"0"
- **preconditions**: 처리 대기 상태의 적재 영상이 존재한다 — 그 영상의 출처유형(ORIGINAL)은 비식별 제외 목록에 없다

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

### [8]

- **seq**: 8
- **note**: TC-004 | 결손 보강 — 정상 흐름에 없던 회수 경로. 배치 연동(화면 없음). 자동 시험이 실재하는지는 확인되지 않았다 — 통과 기록을 만들지 말고 수동 확인 절차로 수행한다. 유예 값은 설정값이므로 시험 전에 설정한 값을 기준으로 만료를 만든다(이 문서에 숫자를 두지 않는다). 여러 노드가 동시에 도는 형상에서는 같은 후보를 두 번 집지 않는지(조건부 갱신 원자 선점)도 함께 본다. 「폴링 경과 만료」와 「수락 대기 유예 만료」는 다른 축이므로 혼동하지 않는다.
- **action**: 수락 응답 미관측 건 회수
- **expected**: 폴링 잡이 미결 행을 회수해 실패로 종결하고, 재위탁 없이 영상 비식별 여부가 'F'로 내려간다. 【검증】DB: SELECT PROC_STTS_CD, POLL_STTS_CD, FAIL_RSN_CD FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN=:rawSn → PROC_STTS_CD='FAILED'·종결 사유 'KPST_ACK_MISSING' 1행 · DB: SELECT DE_IDENT_YN FROM LS_DATA_RAW WHERE RAW_SN=:rawSn → DE_IDENT_YN='F' · 재위탁 없음: 외부 비식별 위탁 호출이 추가로 발생하지 않고 LS_DEIDENT_PROC_LOG 에 새 '요청' 이력이 늘지 않는다(행 수 불변)
- **test_item**: 수락 응답도 결과 회신도 관측되지 않은 위탁 건이 수락 대기 유예 만료 후 회수되어 실패 사유 KPST_ACK_MISSING 으로 마감되고, 재위탁 없이 영상 비식별 여부가 'F'로 내려가는지 확인
- **input_data**: 【영상 식별자】"1002" 【처리 상태 코드】"REQUESTED" 【폴링 상태 코드】"WAITING" 【비식별 프로젝트 식별자】"(미채움 — 수락 응답을 관측하지 못했다)" 【종결 사유 코드】"KPST_ACK_MISSING"
- **preconditions**: 위탁 제출은 개시됐으나 수락 응답도 결과 회신도 끝내 관측되지 않은 미결 행이 존재하고, 그 행의 수락 대기 유예가 지났다

### [9]

- **seq**: 9
- **note**: TC-004 | 순번8과 짝으로 본다 — 같은 종료값인데 영상 상태가 갈리는 유일한 예외다. 트리거가 달라서 단계를 갈랐다(순번8은 제출 후 신호 미관측, 순번9는 호출자 트랜잭션 롤백이라 사전조건이 서로 배타적이다). 이 대비가 없으면 다음 독자가 「모든 종결이 'F'」로 일반화해 유일한 예외를 다시 잃는다. 자동 시험이 실재하는지는 확인되지 않았다 — 통과 기록을 만들지 말고 수동 확인 절차로 수행한다.
- **action**: 취소 종결 대비 확인
- **expected**: 원장만 실패로 마감되고 영상 비식별 여부는 그대로다. 【검증】DB: SELECT PROC_STTS_CD, FAIL_RSN_CD FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN=:rawSn → PROC_STTS_CD='FAILED'·종결 사유 'KPST_SUBMIT_CANCELED' 1행 · DB: SELECT DE_IDENT_YN FROM LS_DATA_RAW WHERE RAW_SN=:rawSn → 직전 값 그대로이며 'F'로 내려가지 않는다 · 그 영상이 신고 게이트(조회 차단·스트리밍 차단·산출 보류)에 들어가지 않는다
- **test_item**: 호출자 트랜잭션 롤백으로 취소 종결된 건은 원장 종료값이 순번8과 같은데도 영상 비식별 여부가 불변인지 확인 — 외부로 아무것도 나가지 않았다는 것이 상태를 내리지 않는 근거다
- **input_data**: 【영상 식별자】"1003" 【처리 상태 코드】"FAILED" 【종결 사유 코드】"KPST_SUBMIT_CANCELED" 【시험 전 영상 비식별 여부】"N"
- **preconditions**: 위탁 제출을 개시한 호출자 트랜잭션이 롤백되어 외부로 아무것도 나가지 않은 행이 존재한다

### [10]

- **seq**: 10
- **note**: TC-005 | 비식별 제외 분기(ADR-066) / 배치 연동. 적재(순번1·2)는 출처유형과 무관하게 같고, 제외 판정은 적재 시점이 아니라 선두 비식별 단계가 영상을 처리하는 시점에 한 번 한다. 외부 비식별 연동이 꺼져 있어도 이 분기는 동작해야 한다. 폴링 대상 제외는 외부 진행 상태 폴링 주기와 수락 대기 유예가 한 번 이상 지난 뒤에 판정한다(주기·유예 값은 설정값이라 이 문서에 숫자를 두지 않는다).
- **action**: 비식별 제외 출처유형 영상 처리
- **expected**: 외부 비식별 솔루션 위탁 호출이 발생하지 않는다. 비식별 영상 쓰기 위치에 원본 파일명 그대로 원본과 바이트 동일한 복사본이 일반 파일로 생기고, 비식별 처리 이력에 비식별 제외 성공 행이 남으며, 영상이 비식별 완료·마킹 대기로 전이한다. 【검증】DB: SELECT PROC_STTS_CD, REQ_KND_CD, POLL_STTS_CD, DE_IDNTF_FILE_PATH_NM FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN=:rawSn → PROC_STTS_CD='SUCCEEDED'·REQ_KND_CD='EXCLUDED'·POLL_STTS_CD 비어 있음(NULL)·DE_IDNTF_FILE_PATH_NM=복사본 경로 1행 · DB: SELECT DATA_STTS_CD, DE_IDENT_YN FROM LS_DATA_RAW WHERE RAW_SN=:rawSn → DATA_STTS_CD='MARKING_READY'·DE_IDENT_YN='Y' · 파일: 복사본의 파일명이 원본과 같고 해시가 원본과 일치하며 심볼릭 링크·하드 링크가 아니다 · 폴링 제외: 폴링 주기와 수락 대기 유예가 지난 뒤에도 이 행의 POLL_STTS_CD 는 비어 있고 PROC_STTS_CD='SUCCEEDED'·DE_IDENT_YN='Y' 가 그대로이며, 이 영상에 대한 외부 진행 조회 호출이 발생하지 않는다 · 화면(SC-008): 처리단계 '마킹 대기'로 전이
- **test_item**: 출처유형이 비식별 제외 목록에 든 영상(GENERATED)이 외부 위탁 없이 원본 복사로 비식별을 완료해 마킹 대기로 넘어가고, 그 이력이 외부 진행 상태 폴링·수락 대기 유예 회수 대상에 오르지 않는지 확인
- **input_data**: 【클립 식별자】"CCTV-GEN-001" 【출처유형】"GENERATED" 【처리 상태】"PENDING" 【원본 파일 경로】"/vms/gen_0001.mp4" 【영상 식별자】"1004"
- **screen_ref**: SCREEN-008
- **preconditions**: 배포 설정 authoring.deidentify.excluded-src-types 가 기본값(GENERATED)이다. 인입 원장에 출처유형 GENERATED 인 미처리 행이 있고 그 원본 영상 파일이 실재한다

### [11]

- **seq**: 11
- **note**: TC-005 | 비식별 제외 분기의 실패 경로(ADR-066). 적재 시점에는 원본 파일 실재를 확인하므로, 원본 부재는 적재가 끝난 뒤 선두 비식별 단계가 처리하기 전에 원본을 치워 만든다. 원본이 실재한 채로 복사가 실패하는 경우(경로 위반·입출력 오류)도 같은 결과여야 하며, 그때는 원본 해시가 시험 전과 같은지로 원본 불변을 확인한다.
- **action**: 비식별 제외 복사 실패 처리
- **expected**: 영상 비식별 여부가 'F' 로 내려가고 비식별 처리 이력에 비식별 제외 실패 행이 남으며, 마킹 대기로 전이하지 않는다. 【검증】DB: SELECT PROC_STTS_CD, REQ_KND_CD FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN=:rawSn → PROC_STTS_CD='FAILED'·REQ_KND_CD='EXCLUDED' 1행 · DB: SELECT DATA_STTS_CD, DE_IDENT_YN FROM LS_DATA_RAW WHERE RAW_SN=:rawSn → DE_IDENT_YN='F'·DATA_STTS_CD 가 'MARKING_READY' 가 아니다 · 파일: 비식별 영상 쓰기 위치에 복사본·반쯤 쓴 임시 파일이 남지 않고, 원본 경로에 파일이 새로 만들어지거나 지워지지 않는다
- **test_item**: 비식별 제외 대상 영상의 원본 복사가 실패하면 실패로 종결되고 마킹 대기로 넘어가지 않으며 원본에 손대지 않는지 확인
- **input_data**: 【영상 식별자】"1005" 【출처유형】"GENERATED" 【원본 파일 경로】"/vms/gen_0002.mp4"(선두 비식별 단계 처리 시점에 파일 없음)
- **preconditions**: 배포 설정 authoring.deidentify.excluded-src-types 가 기본값(GENERATED)이다. 출처유형 GENERATED 인 영상이 적재됐고, 선두 비식별 단계가 처리하는 시점에 원본 경로에 파일이 없다

### [12]

- **seq**: 12
- **note**: TC-005 | 순번10 과 짝으로 본다 — 설정이 비면 모든 영상을 위탁하는 안전측 대조다. 제외 목록은 배포 설정 파일 전용이라 관리 화면에서 바꿀 수 없고 재기동해야 반영된다. 기동 로그에 적용 중인 제외 목록 한 줄이 남으므로 시험 전에 빈 목록으로 반영됐는지 먼저 확인한다.
- **action**: 제외 목록을 비운 설정에서 생성형 영상 처리
- **expected**: 제외 처리 없이 순번3·4 와 같이 외부 비식별 솔루션 위탁이 개시되고 요청 이력이 남는다. 【검증】DB: SELECT PROC_STTS_CD, POLL_STTS_CD, REQ_KND_CD FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN=:rawSn → PROC_STTS_CD='REQUESTED'·POLL_STTS_CD='WAITING'·REQ_KND_CD 비어 있음(NULL) 1행 · REQ_KND_CD='EXCLUDED' 행이 없다 · 외부 연동: 이 영상에 대한 비식별 위탁 요청이 발생한다 · 소급 없음: 순번10 에서 제외 처리된 영상(1004)의 비식별 처리 이력과 DE_IDENT_YN 은 설정을 비운 뒤에도 그대로다
- **test_item**: 배포 설정의 비식별 제외 목록을 빈 값으로 두면 출처유형 GENERATED 영상도 종전대로 외부 비식별 솔루션에 위탁되는지 확인
- **input_data**: 【설정 authoring.deidentify.excluded-src-types】""(빈 값) 【출처유형】"GENERATED" 【영상 식별자】"1006"
- **preconditions**: 배포 설정 authoring.deidentify.excluded-src-types(환경변수 DEIDENTIFY_EXCLUDED_SRC_TYPES)를 빈 값으로 두고 재기동했다. 인입 원장에 출처유형 GENERATED 인 미처리 행이 있고 그 원본 영상 파일이 실재한다

### [13]

- **seq**: 13
- **note**: TC-006 | 선두 비식별 실패 복구(UC-011 대안 흐름 「선두 비식별 실패 후 배치 재시작」). 복구는 사람이 누를 때만 일어난다 — 자동 재시도·스윕·스케줄이 없으므로 순번8·11 의 실패 종결 뒤 시간이 지나도 이 영상에 대한 비식별 위탁 호출이 새로 생기지 않는지 먼저 확인한다. 새 버튼이 아니라 비식별 단계 실패에서 뜨는 기존 배치 실패 패널의 재시작이다. 자동 시험이 실재하는지는 확인되지 않았다 — 통과 기록을 만들지 말고 수동 확인 절차로 수행한다.
- **action**: 선두 비식별 실패 영상 배치 재시작(건별)
- **expected**: 요청이 접수되고 선두 비식별 단계가 한 번 다시 수행되어, 완료되면 영상이 비식별 완료·마킹 대기로 전이한다. 이전 회차 이력은 그대로 남는다. 【검증】응답: 200 · rawSn=1002 · stage='PENDING'(접수 시점 처리 단계 — PROCESSING 으로 바뀌지 않는다) · DB: SELECT COUNT(*) FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN=:rawSn → 순번8 대비 1행 증가, 순번8 의 KPST_ACK_MISSING 행은 그대로 · 완료 후 DB: SELECT DATA_STTS_CD, DE_IDENT_YN FROM LS_DATA_RAW WHERE RAW_SN=:rawSn → DATA_STTS_CD='MARKING_READY'·DE_IDENT_YN='Y' · 잠금 해제: 완료 뒤 이 영상에 영상 단위 작업 잠금이 남지 않는다 · 화면(SC-009): 배치 실패 패널이 사라지고 처리단계 '마킹 대기'로 전이(비동기라 즉시 아님)
- **test_item**: 선두 비식별이 실패해 마킹 대기에 이르지 못한 영상을 검수자가 영상 상세의 배치 재시작으로 다시 요청하면, 적재 직후와 같은 선두 비식별 단계가 한 번 다시 수행되어 비식별 완료·마킹 대기로 복구되는지 확인
- **input_data**: 【영상 식별자】"1002" 【요청】영상 상세 배치 실패 패널의 재시작(POST /v1/videos/{rawSn}/batch/retry)
- **screen_ref**: SCREEN-009
- **preconditions**: 순번8에서 종결돼 비식별 여부 'F' · 처리 단계 PENDING 인 영상(1002)이 있고, 그 영상에 열린 비식별 누락 신고·진행 중 위탁·검수 승인 이력이 없다. 검수자로 접속했다. 외부 비식별 솔루션은 순번3과 같은 설정으로 정상 응답한다

### [14]

- **seq**: 14
- **note**: TC-006 | 「누르면 아무 반응이 없다」 재발 방지 — 전건 거부도 200 이며 화면은 결과 창을 반드시 연다. 자동 시험이 실재하는지는 확인되지 않았다 — 통과 기록을 만들지 말고 수동 확인 절차로 수행한다.
- **action**: 영상 목록 일괄 재시작 — 전건 거부 결과 표시
- **expected**: 접수 0건과 영상 1004 의 거부 사유가 결과 창에 표시되고, 영상 상태·이력은 바뀌지 않는다. 【검증】응답: 200 · 접수 0건 · 1004 건별 거부 사유 1건 · 외부 연동: 이 영상에 대한 비식별 위탁 호출이 발생하지 않는다 · DB: SELECT COUNT(*) FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN=:rawSn → 요청 전과 같음 · DB: SELECT DATA_STTS_CD, DE_IDENT_YN FROM LS_DATA_RAW WHERE RAW_SN=:rawSn → 'MARKING_READY'·'Y' 그대로 · 화면(SC-008): 결과 창이 열려 접수 0건과 거부 사유를 보인다
- **test_item**: 재시작 대상이 아닌 영상만 골라 일괄 재시작을 요청하면 접수 0건과 건별 거부 사유가 결과 창에 보이고, 어떤 재수행도 일어나지 않는지 확인
- **input_data**: 【선택 영상】"1004" 【요청】영상 목록 일괄 재시작(POST /v1/videos/batch/retry)
- **screen_ref**: SCREEN-008
- **preconditions**: 순번10에서 비식별 완료·마킹 대기가 된 영상(1004)이 있다(배치 실패도 선두 비식별 실패도 아니다). 검수자로 접속했다

### [15]

- **seq**: 15
- **note**: TC-006 | 일괄은 건마다 건별과 같은 판정을 적용한다. 출처유형 제외 여부는 재시작이 처리되는 시점의 설정으로 다시 판정하므로, 순번12 에서 제외 목록을 비웠다면 기본값(GENERATED)으로 되돌려 재기동한 뒤 수행한다. 자동 시험이 실재하는지는 확인되지 않았다 — 통과 기록을 만들지 말고 수동 확인 절차로 수행한다.
- **action**: 영상 목록 일괄 재시작 — 부분 성공
- **expected**: 1005 만 접수되어 외부 위탁 없이 원본 복사로 비식별을 완료하고, 1004 는 사유와 함께 거부된다. 【검증】응답: 200 · 접수 1건(1005) · 거부 1건(1004, 건별 사유) · 외부 연동: 두 영상 모두 비식별 위탁 호출이 발생하지 않는다 · DB: SELECT PROC_STTS_CD, REQ_KND_CD FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN=1005 → 순번11 의 EXCLUDED 실패 행은 그대로이고 EXCLUDED 성공 행 1행이 더해진다 · DB: SELECT DATA_STTS_CD, DE_IDENT_YN FROM LS_DATA_RAW WHERE RAW_SN=1005 → 'MARKING_READY'·'Y' · 화면(SC-008): 결과 창에 접수 1건과 1004 의 거부 사유를 보인다
- **test_item**: 선두 비식별이 실패한 출처유형 제외 영상과 재시작 대상이 아닌 영상을 함께 골라 일괄 재시작하면, 앞의 영상만 접수되어 원본 복사로 비식별을 완료하고 뒤의 영상은 사유와 함께 거부되는지 확인
- **input_data**: 【선택 영상】"1005", "1004" 【요청】영상 목록 일괄 재시작(POST /v1/videos/batch/retry)
- **screen_ref**: SCREEN-008
- **preconditions**: 순번11에서 비식별 여부 'F' 로 종결된 출처유형 GENERATED 영상(1005)의 원본 파일을 원래 경로에 되돌려 두었고, 배포 설정의 비식별 제외 목록이 기본값(GENERATED)이다. 순번10 의 영상(1004)이 있다. 검수자로 접속했다

## status

draft

## objective

관제서버가 인입 원장에 직접 등록한 미처리 영상을 주기 배치가 픽업해 저작도구에 적재하고, 적재 직후 외부 비식별 솔루션에 위탁한 뒤 진행 폴링으로 완료를 확인해 비식별 완료·마킹 진입 상태까지 이르는 정상 흐름을 검증한다. 아울러 수락 응답을 끝내 관측하지 못한 건이 수락 대기 유예 만료로 회수되어 실패로 종결되고 영상 비식별 여부가 'F'로 내려가는 경로(순번8)와, 원장 종료값은 같으면서 영상 비식별 여부만 불변인 유일한 예외인 취소 종결(순번9)을 짝으로 검증한다. 여기에 출처유형이 배포 설정의 비식별 제외 목록에 든 영상이 외부 위탁 없이 원본 복사로 비식별을 완료하는 분기(순번10), 그 복사가 실패하는 경로(순번11), 제외 목록을 비웠을 때 종전대로 위탁되는 대조(순번12)를 더해 검증한다(ADR-066). 끝으로 선두 비식별 실패 영상이 자동 재시도 없이 검수자의 배치 재시작(건별 순번13 · 일괄 순번15)으로만 비식별 완료·마킹 대기로 복구되는 경로와, 일괄 재시작이 전건 거부여도 결과 창에 접수 0건과 사유를 보이는지(순번14)를 검증한다(UC-011).

## related_apis

- API-167
- API-199

## preconditions

- 순번1~9 의 입력 영상은 출처유형(SRC_TYPE)이 배포 설정 authoring.deidentify.excluded-src-types 의 비식별 제외 목록에 없다(예: ORIGINAL). 순번10~12 의 입력 영상과 설정은 각 순번의 사전조건을 따른다

## verifies_nfrs

_(empty)_

## attached_files

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
- SCREEN-009

## verifies_requirements

_(empty)_
