# 08. 비식별화

> 출처: R1 RQ-SFR-09-01~05, R2 KLID-AT-UC-011/016, CLAUDE.md, 코드(`batch/step/DeidentifyStep`, `batch/service/KpstDeidentService`)
> 관련: [07 배치 파이프라인](07-batch-pipeline.md) · [19 외부 시스템](19-external-security-cvat.md) · [22 비식별 솔루션 API 명세](22-deid-solution-api.md)

> ✅ **KPST 공유 마운트 단일 경로 확정(2026-06-30)**: 비식별 확정은 실제 KPST API([22 명세](22-deid-solution-api.md))의 **공유 마운트 no-copy 모델**(`POST /project`[input_path=원본 디렉터리, export_path=우리 base]→`GET /retrieve_progress` 폴링→완료 응답 `fileName` 으로 경로 회수)로 **단일화**되었다(`KpstDeidentifyClient`+`KpstDeidentPollJob`(Quartz)). **`POST /upload`·`GET /download` 는 미사용**(공유 마운트로 입력 참조·결과 직접 산출). **레거시 동기 SPI(`DeidentifyClient`)와 결과 콜백 수신 경로(`POST /v1/deidentify/result`)는 제거**되었다(레거시 폴백 없음). `kpst.deid.enabled` 토글은 킬스위치로 유지(기본 **true**)하며, **local/dev 는 `authoring.integration.deidentify.mock-mode=true`(원본 복사 mock, KPST 미호출) 기본**, stg/prd 는 공유 마운트 실연동. 영상 1건=프로젝트 1개. (명세 정본은 [22.1](22-deid-solution-api.md#221-연동-개요) 참조.)

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
[배치] DeidentifyStep.run → KpstDeidentService.submit(project만 — input_path=원본 디렉터리, export_path={base}/videos/{rawSn}/, WAITING)
        ↓ (영상 1건 = KPST 프로젝트 1개. DE_IDENT_YN 미전이 — 완료 대기. upload 없음)
[폴링] KpstDeidentPollJob(Quartz) GET /retrieve_progress 반복 폴링
        ↓ state=2(완료) 감지
응답 dsStatus.fileName 으로 산출 경로 회수(no-copy) → 결과 파일 검증(0바이트/미존재 시 Y 전이 차단)
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
  → 수동 해소(resolve): 신고 OPEN→RESOLVED + DE_IDENT_YN 'F'→'Y' 복원 (원본 보존)
```

- 상태: `OPEN` / `RESOLVED` / `DISMISSED`
- 코드: `deident/`, `frontend DeidentReportButton`, `LS_DEIDENT_REPORT`(V21)
- 자동 재비식별 큐는 폐기 → **수동 비식별화**가 해소 주체(외부 비식별 SW)
- **수동 해소 시 `DE_IDENT_YN` 'F'→'Y' 복원(마킹 게이트 재개방)**: `DeidentReportService.resolveManually` 가 신고를 RESOLVED 전이 + 작업락 해제하면서 `LS_DATA_RAW.DE_IDENT_YN` 을 `'F'`→`'Y'` 로 되돌려 비식별 완료를 전제로 하는 마킹 진입 게이트(`deIdntfYn=='Y'`)를 재개방한다. 복원하지 않으면 게이트가 영구 폐쇄되어 재마킹이 불가능해진다. 자동 배치 해소(`resolveOpenReports`)는 `DeidentifyStep` 이 `'Y'` 로 복원하지만 수동 경로에는 복원 주체가 없어 이 서비스가 직접 복원한다.
- **후기 배치 단계(`LS_DATA_RAW.DATA_STTS_CD`)는 되감지 않음**: 해소는 비식별 게이트(`DE_IDENT_YN`)만 재개방하며 배치 단계 상태(예: MARKING_READY/PROCESSING/COMPLETED)는 변경하지 않는다. 마킹 단계 신고는 `report()` 가 MARKING_READY 를 보존하므로 `'Y'` 복원만으로 게이트를 통과한다.
- **수동 해소 시 비식별 산출물 검증 게이트(CWE-359, fail-closed)**: `resolveManually` 는 `'F'`→`'Y'` 복원 전에 해당 `RAW_SN` 의 최신 성공 처리 이력(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`)에 기록된 비식별 파일이 스토리지에 실존(정규 파일 + >0바이트)하는지 확인한다. 기록이 없거나 파일이 부재/빈 파일이면 `409` 로 거부(내부 경로 미노출)하고 신고는 `OPEN`·작업락·`DE_IDENT_YN='F'` 를 유지한다 — 실제 외부 비식별 없이 마킹 게이트/스트리밍이 재개방되어 PII 가 재노출되는 것을 차단한다. 경로는 DB 적재값만 사용(사용자 입력 경로 구성 금지 — Path Manipulation 방지).

## 8.5 옵션 설정 (RQ-SFR-09-04)

- 관리 화면에서 시스템 설정(key/value, Caffeine TTL 60s)으로 비식별 옵션 설정 → 후속 위탁에 적용
- 세부 옵션 필드는 외부 계약 확정 시 보완(현행 위탁 계약은 {원본 경로, 출력 경로, 멱등키})

## 8.6 검수완료 영상 재비식별 (Approved Re-deidentification)

검수완료(APPROVED)됐지만 비식별이 안 된 영상(주로 v1→v2 이관분: `DE_IDENT_YN='N'`)에 **기존 프레임·라벨·검수상태를 보존한 채** 사후 비식별을 적용하는 REVIEWER 전용 기능.

```
POST /v1/videos/{rawSn}/redeident   (@PreAuthorize REVIEWER)
  → 전제: APPROVED AND DE_IDENT_YN != 'Y'   (기비식별 영상 배제 = 네이티브 frm_no 순번 영상 자연 배제)
  → 작업락 선점(LS_AUTH_WORK_LOCK, 동일영상 활성락 1건 UNIQUE 강제 V69) → 202 Accepted
  → KPST 영상단위 위탁(원본 raw_file_path_nm 입력, REQ_KND_CD='REDEIDENT') — 비동기
  → 폴링 완료 시(REDEIDENT 분기):
      ① DeidentFrameAttacher: deidentified.mp4 에서 기존 LS_DATA_SRC 의 frm_no 프레임을
         프레임번호 직접 추출(ffmpeg select=eq(n,N), fps 무관)해 같은 행에 attachDeidPath (새 행 INSERT 없음 → 라벨 무변경)
      ② DE_IDENT_YN='Y' + PRVC_TYPE_CD 'UNKNOWN'→'PRVC' 정정
      ③ APPROVED 상태 유지(강등 금지 — 상태머신/BatchOrchestrator/MarkingCompletedEvent 미경유) + 작업락 해제
  → 실패(해상도 불일치 등): 전체 롤백(DE_IDENT_YN 미변경·라벨 불변) + procLog FAILED(terminal, 무한 재폴링 차단) + 작업락 해제(재요청 가능)
```

**핵심 불변식**
- **라벨 보존**: 비식별=원본 마스킹이라 좌표 동일 → 기존 라벨이 그대로 유효. 프레임은 같은 `SRC_SN` 행에 비식별 경로만 attach.
- **해상도 fail-closed**: 비식별 출력 해상도 ≠ 원본이면 좌표가 깨지므로 전체 거부·롤백.
- **APPROVED 보존**: 기존 자동 비식별 완료경로(BATCH)의 `MARKING_READY` 강등을 타지 않도록 `REQ_KND_CD`로 완료 분기 분리. 기존 BATCH 경로 무변경.
- **멱등**: 이미 비식별 프레임이 attach된 행은 skip → 재요청으로 이어서 완성.
- 코드: `video/controller/ApprovedRedeidentController`, `video/service/ApprovedRedeidentService`, `batch/step/DeidentFrameAttacher`, `batch/service/KpstDeidentTxService`(완료 분기 `applyRedeidentCompletion`/`applyBatchCompletion`), `LS_DEIDENT_PROC_LOG.REQ_KND_CD`(V68 도입, V83 rename REQ_KIND_CD→REQ_KND_CD), `LS_AUTH_WORK_LOCK` partial unique index(V69)
- 이관 연계: v1→v2 이관 비식별 미완 영상의 정공법 해법 → [23](23-v1-v2-db-migration.md)

## 8.7 관련 데이터 (DB)

`LS_DEIDENT_REPORT`(누락 신고), `LS_DEIDENT_PROC_LOG`(처리 이력, `REQ_KND_CD` BATCH/REDEIDENT), `LS_DATA_RAW.DE_IDENT_YN`, `LS_AUTH_WORK_LOCK`(재진행 중 잠금, 동일영상 활성락 1건 UNIQUE). → [18](18-database.md).
