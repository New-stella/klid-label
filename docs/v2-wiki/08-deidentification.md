# 08. 비식별화

> 출처: R1 RQ-SFR-09-01~05, R2 KLID-AT-UC-011/016, CLAUDE.md, 코드(`deident/`, `webhook/DeidentifyResultController`)
> 관련: [07 배치 파이프라인](07-batch-pipeline.md) · [19 외부 시스템](19-external-security-cvat.md)

## 8.1 개요

영상 개인정보를 **외부 비식별 솔루션**(발주기관 SW 직접구매 제공)으로 처리. 저작도구는 **연동만** 담당.

- 대상: `PRVC_TYPE_CD = PRVC 또는 PSDO` (ANONY는 원본만 저장)
- 출력: `STORAGE_DEIDENTIFIED_PATH` 하위 강제 (CWE-22 경로 검증)
- **원본 절대 삭제 금지**, 원본·비식별본 별도 보관
- SSRF(CWE-918) 차단 — `application.yml` base-url 사용, 사용자 입력 URL 구성 금지

## 8.2 연동 흐름

```
[배치/요청] DeidentifyClient(Resilience4j, ~70s) → 외부 비식별 API (동기 위탁)
   요청: {원본 경로, 출력 경로(STORAGE_DEIDENTIFIED_PATH 하위), 멱등키}
        ↓ 비동기
[콜백] POST /v1/deidentify/result (HMAC + idempotencyKey)
   수신: {멱등키, 외부 작업 ID, 처리 상태(SUCCESS/FAILED/PARTIAL), RAW_SN, 비식별 파일 경로, 처리 영역 목록}
        ↓
결과 경로 검증 → LS_DATA_RAW.DE_IDNTF_YN='Y' 마킹 + LS_DEIDENT_PROC_LOG 이력 저장
```

- 멱등키(미지정 시 자동 발급)로 중복 인계 방지, 외부 작업 ID UNIQUE 로 콜백 upsert
- 실패 시 `DE_IDNTF_YN='F'` + 재시도 큐, 원본 보존
- 코드: `DeidentifyClient`, `batch/step/DeidentifyStep`, `webhook/DeidentifyResultController`/`DeidentifyResultService`

## 8.3 처리 이력 (RQ-SFR-09-03)

- 영상 단위 이력 `LS_DEIDENT_PROC_LOG`: `EXTERNAL_JOB_ID`, `ORGNL_FILE_PATH_NM`, `DE_IDNTF_FILE_PATH_NM`, `PROC_STTS_CD`(REQUESTED/SUCCEEDED/FAILED), `REQ_DT`/`RES_DT`, `ERROR_CD/MSG`
- 저작도구 화면은 `DE_IDNTF_YN`(Y/F) 상태 + 이력만 표시. **상세 검토는 외부 솔루션 검토화면**으로 연계 (SC-016/017 deprecated)

## 8.4 누락 신고 (RQ-SFR-09-03, UC-016)

작업자가 라벨/마킹 작업 중 비식별 누락(PII 노출)을 발견하면:

```
누락 신고 (LS_DEIDENT_REPORT: OPEN)
  → 현재 작업 내용 삭제 + DE_IDNTF_YN='F'
  → 작업자/검수자가 외부 비식별 솔루션으로 수동 비식별화
  → 완료 시 신고 RESOLVED (원본 보존)
```

- 상태: `OPEN` / `RESOLVED` / `DISMISSED`
- 코드: `deident/`, `frontend DeidentReportButton`, `LS_DEIDENT_REPORT`(V21)
- 자동 재비식별 큐는 폐기 → **수동 비식별화**가 해소 주체(외부 비식별 SW)

## 8.5 옵션 설정 (RQ-SFR-09-04)

- 관리 화면에서 시스템 설정(key/value, Caffeine TTL 60s)으로 비식별 옵션 설정 → 후속 위탁에 적용
- 세부 옵션 필드는 외부 계약 확정 시 보완(현행 위탁 계약은 {원본 경로, 출력 경로, 멱등키})

## 8.6 관련 데이터 (DB)

`LS_DEIDENT_REPORT`(누락 신고), `LS_DEIDENT_PROC_LOG`(처리 이력), `LS_DATA_RAW.DE_IDNTF_YN`, `LS_AUTH_WORK_LOCK`(재진행 중 잠금). → [18](18-database.md).
