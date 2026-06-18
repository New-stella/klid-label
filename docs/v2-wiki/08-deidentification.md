# 08. 비식별화

> 출처: R1 RQ-SFR-09-01~05, R2 KLID-AT-UC-011/016, CLAUDE.md, 코드(`batch/step/DeidentifyStep`, `batch/service/KpstDeidentService`)
> 관련: [07 배치 파이프라인](07-batch-pipeline.md) · [19 외부 시스템](19-external-security-cvat.md) · [22 비식별 솔루션 API 명세](22-deid-solution-api.md)

> ✅ **KPST 폴링 단일 경로 확정(UC018, 2026-06-18)**: 비식별 확정은 실제 KPST API([22 명세](22-deid-solution-api.md))의 폴링 모델(`POST /upload`→`POST /project`→`GET /retrieve_progress` 폴링→`GET /download`)로 **단일화**되었다(`KpstDeidentifyClient`+`KpstDeidentPollJob`(Quartz)). **레거시 동기 SPI(`DeidentifyClient`)와 결과 콜백 수신 경로(`POST /v1/deidentify/result`)는 제거**되었다(레거시 폴백 없음). `kpst.deid.enabled` 토글은 킬스위치로 유지(기본 **true**)하며, local 자족 환경은 `authoring.integration.deidentify.mock-mode=true` 의 mock 복사 경로를 사용한다. 영상 1건=프로젝트 1개. (명세 정본은 [22.1](22-deid-solution-api.md#221-연동-개요) 참조.)

## 8.1 개요

영상 개인정보를 **외부 비식별 솔루션**(발주기관 SW 직접구매 제공)으로 처리. 저작도구는 **연동만** 담당.

- **파이프라인 선두 단계**(구현됨): 영상 적재 직후 자동 실행되며, 마킹 단계의 선행 조건이다 → [07](07-batch-pipeline.md#71-파이프라인-순서-구현됨)
- 대상: **전체 영상 무조건**(`PRVC_TYPE_CD` 게이팅 폐지 — `ANONY` 포함). 비식별 영상이 마킹·작업 대상이 되며 원본은 별도 보존
- **자동 트리거**: 적재(TUS 업로드 + dev 경로) → `VideoIngestedEvent` → `IngestDeidentifyBridge`(AFTER_COMMIT) → `AsyncDeidentifyRunner`(@Async) → `DeidentifyStep.run` → 성공 시 `LsDataRaw.dataSttsCd = MARKING_READY`
  - ⚠ **증강(augment) 적재 경로는 아직 `VideoIngestedEvent` 미발행** — 선두 비식별 자동화 미연동(planned/후속)
- 출력: `STORAGE_DEIDENTIFIED_PATH` 하위 강제 (CWE-22 경로 검증)
- **원본 절대 삭제 금지**, 원본·비식별본 별도 보관
- SSRF(CWE-918) 차단 — `application.yml` base-url 사용, 사용자 입력 URL 구성 금지

## 8.2 연동 흐름

비식별 확정 경로는 **KPST 폴링 단일 경로**다(`kpst.deid.enabled=true`, 기본). local 자족 환경만 mock 복사 경로를 사용한다.

**[KPST 폴링 경로]** (`kpst.deid.enabled=true`, 기본)
```
[배치] DeidentifyStep.run → KpstDeidentService.submit(upload→project, WAITING)
        ↓ (영상 1건 = KPST 프로젝트 1개. DE_IDENT_YN 미전이 — 완료 대기)
[폴링] KpstDeidentPollJob(Quartz) GET /retrieve_progress 반복 폴링
        ↓ state=2(완료) 감지
GET /download → 결과 파일 검증(0바이트/미존재 시 Y 전이 차단)
        ↓ (KpstDeidentTxService.finishDownloadAndComplete, REQUIRES_NEW 원자화)
LS_DATA_RAW.DE_IDENT_YN='Y' + dataSttsCd=MARKING_READY + 작업락 해제 + 신고 해소
   타임아웃 시 DE_IDENT_YN='F'
```

**[local mock 경로]** (`authoring.integration.deidentify.mock-mode=true`, local 전용)
```
[배치] DeidentifyStep.runMock → 원본을 STORAGE_DEIDENTIFIED_PATH 하위로 atomic 복사(원본 보존)
        ↓ (외부 미접촉)
LS_DATA_RAW.DE_IDENT_YN='Y' + 작업락 해제 + 신고 해소 + 알림
   원본 부재 시 'F' 마킹(성공 위장 금지, REQUIRES_NEW 독립 커밋)
```

> ⚠ **레거시 동기 SPI/콜백 경로 제거(UC018)**: 구 `DeidentifyClient`(동기 위탁) + `POST /v1/deidentify/result` 콜백 수신(`DeidentifyResultController`/`DeidentifyResultService`/`DeidentifyResultRequest`) 경로는 제거되었다. mock 도 아니고 KPST 서비스도 없으면(설정 오류) `DeidentifyStep` 은 레거시 폴백 대신 명확한 설정 오류 예외(내부 정보 미노출)로 처리한다.

- 실패 시 `DE_IDENT_YN='F'`, 원본 보존. 재비식별은 외부 솔루션 수동 처리(자동 재비식별 큐 없음).
- 코드(폴링 경로): `KpstDeidentifyClient`, `KpstWebClientConfig`(자체CA TLS), `batch/service/KpstDeidentService`/`KpstDeidentTxService`, `batch/scheduler/KpstDeidentPollJob`
- 코드(트리거/mock): `batch/step/DeidentifyStep`(mock/KPST/설정오류 3분기)
- 공유 인프라(무변경): `HmacWebhookFilter`/`HmacSigner` + VLM(`/v1/vlm/result`)·증강(`/v1/augments/result`) 콜백은 그대로 유지

## 8.3 처리 이력 (RQ-SFR-09-03)

- 영상 단위 이력 `LS_DEIDENT_PROC_LOG`: `EXTERNAL_JOB_ID`, `ORGNL_FILE_PATH_NM`, `DE_IDNTF_FILE_PATH_NM`, `PROC_STTS_CD`(REQUESTED/SUCCEEDED/FAILED), `REQ_DT`/`RES_DT`, `ERROR_CD/MSG`
- 저작도구 화면은 `DE_IDENT_YN`(Y/F) 상태 + 이력만 표시. **상세 검토는 외부 솔루션 검토화면**으로 연계 (SC-016/017 deprecated)

## 8.4 누락 신고 (RQ-SFR-09-03, UC-016)

작업자가 라벨/마킹 작업 중 비식별 누락(PII 노출)을 발견하면:

```
누락 신고 (LS_DEIDENT_REPORT: OPEN)
  → 현재 작업 내용 삭제 + DE_IDENT_YN='F'
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

`LS_DEIDENT_REPORT`(누락 신고), `LS_DEIDENT_PROC_LOG`(처리 이력), `LS_DATA_RAW.DE_IDENT_YN`, `LS_AUTH_WORK_LOCK`(재진행 중 잠금). → [18](18-database.md).
