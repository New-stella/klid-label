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
[배치] DeidentifyStep.run → KpstDeidentService.submit
        ① 원본 실재 가드 + 요청 조립(input_path=원본 디렉터리, export_path={base}/videos/{rawSn}/)
        ② 선커밋 — 위탁 원장 발급(POLL_STTS=WAITING, prjId=null) ※ REQUIRES_NEW 독립 커밋
        ③ 논블로킹 제출 — POST /project 를 subscribe 만 하고 즉시 반환(ACK 미대기)
             · 활성 트랜잭션이 있으면 afterCommit 에 구독 / 롤백이면 제출 자체를 하지 않고 원장만 취소 종결
        ④ 완료 핸들러 KpstSubmitOutcomeRecorder(전용 풀 kpstSubmitScheduler) 가 ACK 로 prjId 확정
        ↓ (영상 1건 = KPST 프로젝트 1개. DE_IDENT_YN 미전이 — 완료 대기. upload 없음)
[폴링] KpstDeidentPollJob(Quartz) GET /retrieve_progress 반복 폴링
        ※ prjId 가 아직 없으면 ACK 대기 유예(kpst.deid.submit-ack-grace-sec, 기본 180초) 안에서는
          진행조회를 부르지 않고 시도 카운터도 소모하지 않는다. 유예 초과 시 KPST_ACK_MISSING 회수
        ↓ state=2(완료) 감지
응답 dsStatus.fileName 으로 산출 경로 회수(no-copy) → 산출물 무결성 검증(미존재/512바이트 미만/영상 컨테이너 시그니처 불일치 시 Y 전이 차단)
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
- 코드(폴링 경로): `KpstDeidentifyClient`(`createProject` → `Mono`), `KpstWebClientConfig`(자체CA TLS), `batch/service/KpstDeidentService`/`KpstDeidentTxService`/`KpstSubmitOutcomeRecorder`, `batch/scheduler/KpstDeidentPollJob`
- 코드(트리거/mock): `batch/step/DeidentifyStep`(mock/KPST/설정오류 3분기)
- 공유 인프라: `HmacWebhookFilter`/`HmacSigner` + VLM(`/v1/vlm/callback`) 콜백은 그대로 유지. 증강 콜백은 2026-07-27 Phase 7-A2 에서 무서명 `/v1/genai/callback` 으로 교체됐다(→ [14](14-augmentation.md))

### 8.2-1 위탁 제출의 논블로킹화 (2026-07-30)

KPST 는 원래부터 비동기 프로토콜(결과는 `retrieve_progress` 폴링)이었으나 **수락 응답 왕복 동안 스레드를 점유**했다(`blockOptional(45s)`). 그 스레드는 적재 경로의 `batch-async-`(core 2) 또는 재비식별 요청의 Tomcat 요청 스레드였다. 공통 골격은 [07 §7.2-1](07-batch-pipeline.md) 참조이며, KPST 고유 규칙은 다음과 같다.

- **ACK 대기 판정 = `prjId` 유무 + 유예** — 원장은 `POLL_STTS=WAITING` + `DE_IDNTF_PJT_ID=null` 로 선커밋되고, 폴러는 이 상태를 "제출 ACK 대기"로 해석한다. 유예(`kpst.deid.submit-ack-grace-sec`, 기본 **180초**) 안에서는 **외부 호출 0건 · 시도 카운터 미소모**로 건너뛰고, 초과하면 폴러가 `KPST_ACK_MISSING` 으로 회수한다. **별도 스위퍼를 만들지 않는다** — 폴러가 이미 클레임·타임아웃·`'F'` 종결을 갖춘 회수기다.
  - 기본 180초의 근거: 클라이언트 타임아웃 45s × 재시도 3회 + 백오프(1s·2s) 최악값(≈138s)을 덮는 값. 더 짧으면 정상 재시도 중인 건을 회수한다.
  - 회수 종결은 **조건부 UPDATE**(WAITING + prjId null)라, 판정 직후 ACK 가 도착했으면 0행 no-op 이다(지각 ACK 를 강등하지 않는다).
- **호출자 트랜잭션이 롤백되면 영상을 `'F'` 로 만들지 않는다** — 제출은 `afterCommit` 에 구독하므로 롤백 시 **외부로 아무것도 나가지 않는다**. 이때 선커밋된 원장만 `KPST_SUBMIT_CANCELED` 로 취소 종결하고 **영상 상태는 건드리지 않는다**. 그대로 두면 유예 만료 회수의 종착(`DE_IDNTF_YN='F'`)이 걸려, 위탁하지도 않은 영상이 3분 뒤 신고 게이트에 걸려 라벨 조회 412 · 스트리밍 404 · export 보류가 됐다(APPROVED 영상 재비식별 요청 실패 시 특히 유해).
  - 커밋 후 구독이 필요한 또 다른 이유: 재비식별 호출자(`ApprovedRedeidentService`)는 자기 트랜잭션에서 **작업락을 INSERT** 한다. 실패 신호가 그 커밋보다 먼저 오면 실패 핸들러(REQUIRES_NEW)가 아직 커밋되지 않은 락을 보지 못해 해제하지 못하고, 재요청이 409 로 영구 차단된다.
- **원장 종결 코드 3종(`LS_DEIDENT_PROC_LOG.ERR_CD`)** — 운영이 "외부에 작업이 실재하는가"를 코드로 구분하기 위해 분리한다.

  | 코드 | 의미 | 영상 `DE_IDNTF_YN` |
  |------|------|:------------------:|
  | `KPST_SUBMIT_FAILED` | 제출이 **확정 실패**(onError·구독 거부·빈 응답) — 신호를 받았다 | `'F'` |
  | `KPST_ACK_MISSING` | ACK 를 **관측하지 못함**(노드 사망·기록 유실) — KPST 에 프로젝트가 실재할 수 있다 | `'F'` |
  | `KPST_SUBMIT_CANCELED` | 호출자 tx 미커밋 — **외부로 나간 것이 없다** | 불변(전이 없음) |

- **동기 실패 전파가 남는 것은 제출 이전 사전 조건뿐** — raw null · 원본 부재 · 경로 손상 · export 디렉터리 생성/검증 실패. 실패 흔적은 별도 트랜잭션으로 커밋한 뒤 예외를 전파한다.

**신규 설정 키**

| 키 | 기본값 | 설명 |
|----|:-----:|------|
| `kpst.deid.submit-ack-grace-sec` | `180` | 제출 ACK 대기 유예(초). 이 안에서는 폴러가 해당 건을 건너뛴다(외부 호출·카운터 미소모). `0` 이하 또는 `REQ_DT` 부재면 유예 없이 즉시 회수 대상 |

> `application.yml` 의 `kpst.deid` 블록에는 아직 이 키가 명시돼 있지 않고 코드 `@Value` 기본값으로만 동작한다(환경변수 override 는 `kpst.deid.submit-ack-grace-sec` 프로퍼티 경로로 주입).

## 8.3 처리 이력 (RQ-SFR-09-03)

- 영상 단위 이력 `LS_DEIDENT_PROC_LOG`: `EXTERNAL_JOB_ID`, `ORGNL_FILE_PATH_NM`, `DE_IDNTF_FILE_PATH_NM`, `PROC_STTS_CD`(REQUESTED/SUCCEEDED/FAILED), `REQ_DT`/`RES_DT`, `ERROR_CD/MSG`
- 저작도구 화면은 `DE_IDENT_YN`(Y/F) 상태 + 이력만 표시. **상세 검토는 외부 솔루션 검토화면**으로 연계 (SC-016/017 deprecated)

## 8.4 누락 신고 (RQ-SFR-09-03, UC-016)

작업자가 라벨/마킹 작업 중 비식별 누락(PII 노출)을 발견하면:

```
누락 신고 (LS_DEIDENT_REPORT: OPEN)   ※ ★비파생 영상에서만 접수 — 파생영상은 412 거부
  → 작업락 + DE_IDENT_YN='F' + 개인정보 3필드 리셋 (★라벨은 보존 — 삭제 안 함)
  → 신고 구간 동안 해당 영상 라벨 조회·저장 모두 차단(412) — 라벨은 보존되고 resolve 시 그대로 재사용
  → ★게이트는 자기 rawSn 행의 DE_IDENT_YN='F' 만 판정 (조상·자손 전파 없음)
  → 작업자/검수자가 외부 비식별 솔루션으로 수동 비식별화
  → 수동 해소(resolve): 신고 OPEN→RESOLVED + DE_IDENT_YN 'F'→'Y' 복원 (원본 보존)
  → 조회 게이트 자동 해제 → 보존된 기존 라벨을 그대로 재사용
                          + APPROVED 영상이면 그 영상 하나의 export 재산출 재트리거
```

- 상태: `OPEN` / `RESOLVED` / `DISMISSED`
- 코드: `deident/`, `frontend DeidentReportButton`, `LS_DEIDENT_REPORT`(V21)
- **신고 접수 진입점 2개 (B-ISSUE-28 — 마킹 단계 rawSn 경로 구현 완료)**:

  | 단계 | 엔드포인트 | 식별 축 | 비고 |
  |------|-----------|:------:|------|
  | 라벨링 | `POST /v1/labels/{srcSn}/deident-report` | 프레임 | 기존 |
  | **마킹** | **`POST /v1/videos/{rawSn}/deident-report`** | **영상** | 마킹 화면은 비식별 *영상* 재생이라 프레임 컨텍스트가 없다 |

  두 경로의 **부수효과는 완전히 동일**하다 — `DeidentReportService` 내부에서 같은 본체(`doReport`)로 수렴하므로 갈라질 수 없다(파생영상 412 거부 · 작업락 · `DE_IDENT_YN='F'` · 개인정보 3필드 리셋 · 스트림 메타 캐시 무효화 · APPROVED 영상 `TASK_MODIFIED` 통지 · REVIEWER 알림). 차이는 둘뿐이다: ①인가가 `LabelAccessGuard.verifyRawAccess`(영상 단위, 규칙은 동일 — REVIEWER 전체 / WORKER 본인 배정만) ②`TaskModifiedEvent.srcSn=null`(영상 단위 변경 — 개인정보 리셋 자체가 영상 전 프레임 대상이라 특정 프레임을 지목할 근거가 없다). 응답 규약도 동일: 201 / 400(사유 누락·1000자 초과) / 401 / 403 / 404 / 409(이미 재비식별 중) / **412(파생영상 · 비식별 미수행)**. 해소는 두 경로 모두 `POST /v1/deident-reports/{rprtSn}/resolve` 공통.

  **비식별 미수행 영상은 412 (프리컨디션)**: `DE_IDENT_YN='N'`(비식별 미실행, `PENDING`)인 영상은 신고를 접수하지 않는다. 라벨링(srcSn) 경로는 프레임이 있어야 도달하므로 사실상 비식별·프레임추출 완료가 전제였지만, 마킹(rawSn) 경로는 이 상태에 직접 닿는다. 접수하면 ①`'N'→'F'` 로 `LsDataRaw.hasDeidentArtifact()` 가 **거짓으로 true** 가 되어 증강·해상도 파생 부모 게이트를 통과하고(뒤의 산출물 실재 fail-closed 검사가 막긴 하지만 판정 원천이 거짓이 되는 것 자체가 결함) ②"외부 솔루션이 **재**비식별했다"는 전제의 `resolve` 로만 풀 수 있는 작업락이 파이프라인 진행 중 영상에 고착된다. 판정은 `hasDeidentArtifact()`(`'Y'`|`'F'`) **단일 원천**이므로 **이미 신고된 `'F'` 는 통과**하며 그 중복 신고는 기존 409 경로가 처리한다.
- **신고 관리 목록 `GET /v1/deident-reports?status&page&size` (REVIEWER 전용, SC-033)**: `status` 는 `OPEN`(기본)/`RESOLVED`/`DISMISSED` allowlist 만 허용(그 외 400). 응답 행(`DeidentReportListResponse`)은 신고자를 **두 축**으로 내린다 — `reporterNo`(`USER_NO` 원값, 하위호환) + **`reporterName`**(`MNG_ACCT_USER.USER_NM`, 2026-08-04 추가). **화면 '신고자' 컬럼은 `reporterName` 을 표시**한다(내부 번호를 사람 이름 자리에 찍지 않는다). 이름은 페이지의 `USER_NO` 를 **단일 IN 쿼리**로 한 번에 해석하고(N+1 금지), 마스터에 없는 번호(탈퇴·관제 계정 삭제)는 **`null`** 로 남기되 목록 조회 자체는 정상 반환한다(fail-soft).
- 자동 재비식별 큐는 폐기 → **수동 비식별화**가 해소 주체(외부 비식별 SW)
- **★라벨 보존 정책 (2026-07-27 사용자 확정 — 구 "전체 라벨 삭제 + 복원 스냅샷" 폐기)**: 신고는 "비식별이 잘못됐다"는 신호일 뿐 라벨 작업 결과를 폐기할 근거가 아니므로 **해당 영상의 라벨을 삭제하지 않는다**. 구 정책이 삭제 직전에 남기던 `LS_LABEL_VERSION`(`SAVE_REASON='DEIDENT_REPORT'`, `ACTIVE_YN='N'`) **비활성 스냅샷도 더 이상 적재하지 않는다** — 그 스냅샷은 `DATA_SRC_SN=NULL`(영상 스코프)이라 프레임(srcSn) 스코프인 버전 목록·롤백 API 에서 조회·복원할 수 없는 write-only 이력이었다(D-ISSUE-25). 이미 적재된 기존 행은 보존하며, 프레임 스코프가 아닌 버전 해시로 diff 를 호출하면 400 으로 명시 거부한다(구 미처리 500 수정 — D-ISSUE-26). 삭제분 소급 복구는 하지 않는다.
- **신고 구간 라벨 조회 차단 게이트 (S7, CWE-359)**: 라벨이 보존되므로 신고~재비식별 완료 사이에 라벨 좌표(=PII 위치 특정 정보)가 계속 노출되는 창이 생긴다. 따라서 `DE_IDENT_YN='F'` 인 동안 해당 영상 프레임의 라벨 조회(`GET /v1/frames/{srcSn}/labels`)를 **412 PRECONDITION_FAILED** 로 차단한다. 인가(WORKER 본인 배정/REVIEWER) 검사를 통과한 **뒤** 평가하는 프리컨디션이며 **REVIEWER 도 동일하게 차단**된다(영상 스트리밍의 비식별 미완료 NOT_FOUND·마킹 진입 게이트와 같은 역할 무관 정책). 라벨 저장/수정은 기존 작업락(`LS_AUTH_WORK_LOCK`)이 409 로 차단하므로 신고 구간은 읽기·쓰기 모두 봉쇄된다. `resolve` 가 `'F'→'Y'` 를 복원하면 게이트가 자동으로 열려 **보존된 라벨을 그대로** 사용한다(별도 복원 API 없음).
- **수동 해소 시 `DE_IDENT_YN` 'F'→'Y' 복원(마킹 게이트 재개방)**: `DeidentReportService.resolveManually` 가 신고를 RESOLVED 전이 + 작업락 해제하면서 `LS_DATA_RAW.DE_IDENT_YN` 을 `'F'`→`'Y'` 로 되돌려 비식별 완료를 전제로 하는 마킹 진입 게이트(`deIdntfYn=='Y'`)를 재개방한다. 복원하지 않으면 게이트가 영구 폐쇄되어 재마킹이 불가능해진다. 자동 배치 해소(`resolveOpenReports`)는 `DeidentifyStep` 이 `'Y'` 로 복원하지만 수동 경로에는 복원 주체가 없어 이 서비스가 직접 복원한다.
- **후기 배치 단계(`LS_DATA_RAW.DATA_STTS_CD`)는 되감지 않음**: 해소는 비식별 게이트(`DE_IDENT_YN`)만 재개방하며 배치 단계 상태(예: MARKING_READY/PROCESSING/COMPLETED)는 변경하지 않는다. 마킹 단계 신고는 `report()` 가 MARKING_READY 를 보존하므로 `'Y'` 복원만으로 게이트를 통과한다.
- **★신고 접수 대상 = 비파생 영상만 (2026-07-29 사용자 확정, 구속)**: 파생영상(증강 `WINTER/NIGHT/RAIN` · 해상도 `RESL_*`)에서는 신고를 **접수하지 않는다** — `POST /v1/labels/{srcSn}/deident-report` 가 **412 PRECONDITION_FAILED** 로 거부한다(`DeidentReportService.requireReportableVideo`). 파생 프레임은 원본 비식별 산출물의 복사·리스케일 사본인데, 재비식별은 외부 솔루션이 **원본 영상**을 다시 처리하는 방식뿐이라 **파생본 자체를 다시 비식별할 수단이 없다** — 접수해도 해소할 수 없는 신고(작업락 + `'F'` 고착)만 남는다. FE 는 파생영상에서 신고 버튼을 비활성화하므로 이 412 경로는 API 직접 호출·낡은 화면에서만 도달한다. **원본으로 유도하지 않는다**(원본 신고는 아래대로 파생에 아무 영향이 없고, 파생 배정 WORKER 는 원본 접근 권한도 없다).
- **★신고 게이트 판정 범위 = 자기 `rawSn` 행 하나 (2026-07-29 사용자 확정, 구속)**: 판정 단일 원천은 `video/service/DeidentReportGate` 이며, **자기 행의 `DE_IDENT_YN='F'` 만** 본다 — `ORGNL_RAW_SN` 을 **보지 않는다(조상·자손 전파 없음)**. 잠금 판정(`isUnderDeidentReportLocked`)도 자기 행 하나만 `SELECT … FOR UPDATE` 하므로 잠금 순서를 맞출 필요가 없다(교착 위험 없음). 복구 발행·스트림 메타 캐시 무효화도 자기 `rawSn` 단건이다.
  - **★이 정책의 함의(감추지 않음)**: **부모 신고는 파생영상에 영향을 주지 않는다.** 부모의 마스킹 실패 픽셀은 그 시점에 복사된 파생본에도 남아 있지만 **파생본은 계속 서빙·산출된다.** 파생본은 재비식별 수단이 없어 차단해도 해소할 방법이 없으므로 **사용자가 인지하고 감수하기로 한 확정 사항**이다(파생은 독립 취급). 신규 파생 생성은 **신고 여부로 막지 않고**(위 '파생 생성 축은 차단 범위 아님'), 스냅샷 이후 부모 비식별본이 **교체**되면 abort 하는 **복사 원자성 게이트**(procLog 경로 불일치 · 파일 mtime)로 방어한다 → [14 §14.3](14-augmentation.md) · [24](24-dataset-export.md).
  - **폐기된 안 — 조상/자손 전파 (2026-07-28~29 시도 후 철회)**: 부모 신고를 파생까지 전파하려고 ①조상(`ORGNL_RAW_SN`) 체인 순회 판정(깊이 상한 8 · 상한 초과 fail-closed) ②자손 방향 캐시 evict·복구 팬아웃 ③조상 → 자손 잠금 정준 순서를 넣었으나, **차단과 복구가 비대칭**(막는 조건과 푸는 조건이 어긋나 정상 트리가 영구 차단됨)이고 팬아웃 상한 초과 시 DoS·막다른 안내(파생 배정 WORKER 는 부모에 403)까지 연쇄 결함이 나와 **전량 철회**했다. **다시 시도하지 말 것** — 되살리려면 "파생본 재비식별 수단"부터 만들어야 한다.
- **차단 범위와 응답 코드(구현 실측)**:

  | 대상 | 엔드포인트/경로 | 응답 |
  |------|----------------|:----:|
  | 라벨 조회·라벨 이력 | `GET /v1/frames/{srcSn}/labels`, `GET /v1/frames/{srcSn}/label-history` | 412 |
  | **라벨 저장(full-replace)** | `PUT /v1/frames/{srcSn}/labels` (`LabelService.bulkUpsert`) | **412** |
  | 개인정보 메타 저장 | `PUT /v1/videos/{rawSn}/privacy-meta`, `PUT /v1/frames/{srcSn}/privacy-meta`, `PUT /v1/frames/privacy-meta`(벌크) | 412 |
  | 버전 diff·롤백 | `VersionService.diff` / `rollback` | 412 |
  | 프레임 이미지 | `GET /v1/frames/{srcSn}/image`, `GET /v1/frames/{srcSn}/deid-image`, `GET /v1/videos/{rawSn}/frames/{frameNo}/image` | 412 |
  | 포털 | `GET /v1/portal/frames/{srcSn}/labels`, `GET /v1/portal/frames/{srcSn}/image` | 412 |
  | 관제 조회 API(라벨 본문) | `TaskQueryController` 라벨 조회 | 412 |
  | 데이터셋 export | `DatasetExportService`·`DatasetExportTxService`·`DatasetExportFailureRecoverer` | 산출 보류(skip, 통지도 보류) |
  | **영상 스트리밍** | `GET /v1/videos/{rawSn}/stream`, `GET /v1/videos/{rawSn}/stream-url` | **404** |

  **라벨 저장이 이 표에 들어온 경위 (2026-08-04 · C-ISSUE-22)**: 구 정책은 *"조회는 게이트가 412, 저장·수정은 작업락이 409"* 로 두 축을 나눴는데, 이 분업은 **작업락의 수명이 신고 구간과 같을 때만** 성립한다. 실제로는 신고 락이 6h 만료로 생성되고 `WorkLockSweepJob` 이 회수하는 반면 `DE_IDENT_YN='F'` 는 resolve 까지 남아, 그 창에서 **조회 412 ↔ 저장 200** 비대칭이 열렸다. 저장 계약이 full-replace 라 조회가 막힌 채로 `items:[]` 를 보내면 **기존 라벨이 전량 삭제**됐고(실동작 재현), 저장 응답에 좌표가 실려 412 열람 차단까지 우회됐다. 지금은 `bulkUpsert` 진입부가 **락 검사보다 먼저** 게이트를 평가한다 — 신고 축의 응답을 락 유무와 무관하게 412 로 통일해 응답 코드가 잠금 상태 오라클이 되지 않게 하며, 409 는 **신고와 무관한 락**(트랙 병합 등 일시적 충돌)에만 남는다.

  스트리밍만 404 인 것은 "비식별이 유효하지 않으면 원본 노출 금지 → 404" 라는 그 엔드포인트의 **기존 규약**에 맞춘 것이다 — 같은 엔드포인트가 비식별 미완료(`'N'`)와 신고(`'F'`)를 서로 다른 코드로 내면 **응답 코드가 내부 상태를 알려주는 오라클**이 된다(CWE-209). 모든 게이트는 **인가 검사 이후** 평가되는 프리컨디션이며 역할 무관(REVIEWER 포함)이다.

  **★위 표는 전부 '원본 보호 축'이며 그대로 유지된다. 반면 '파생 *생성* 축'은 차단 범위가 아니다 (2026-07-29 확정, 구속)** — 증강 콜백 인계(`AugmentResultService.evaluateParentGate`)와 해상도 파생 예약/확정(`ResolutionReservationPersister`·`ResolutionSnapshotService`·`ResolutionPersistService`)은 신고 구간(`'F'`)에도 **파생을 생성한다**. 파생 생성은 원본 신고와 무관하다는 위 정책(§ 신고 접수 대상)과 대칭이며, 신고가 실제로 막는 것은 **외부 위탁(요청·전송)** 뿐이다(`AugmentRequestService` 412 · `AugmentJobSubmitService` `ERR_DEID_REPORT_OPEN` 거부 · `VlmTimeseriesStep` 보류). 해상도 파생은 외부 위탁이 전혀 없는 내부 리스케일이라 애초에 유출 축이 없다.
  - 판정 단일 원천은 `LsDataRaw.hasDeidentArtifact()`(`'Y'`|`'F'` 통과 / `'N'`·null 차단) — 네 경로가 모두 이 헬퍼를 쓴다. `'F'` 는 ①신고(산출물 존재) ②비식별 API 실패(산출물 부재) 두 의미가 겹치므로 **산출물 실재 검증이 fail-closed 로 뒤를 받친다**(비식별 procLog 경로/프레임 비식별 경로/비식별 영상 파일 부재 → 실패). **원본 경로 폴백은 두지 않는다.**
  - 증강 **고아 PENDING 만료 스윕**(`AugmentJobExpiryTxService.expireOrphanPending` / `LsDataAugRepository.findOrphanPendingAugSns`)의 신고 구간 제외 술어도 **제거**했다 — "신고 = 정책 보류" 전제가 폐기되고 재개 리스너도 삭제돼 깨울 주체가 없으므로, 제외하면 PENDING 영구 고착만 남는다.
- **게이트가 걸린 미디어 응답은 `Cache-Control: no-store` (CWE-359/525)** — 적용 경로 **전체 목록**(코드 실측):

  | # | 엔드포인트 | 구현 |
  |:-:|-----------|------|
  | 1 | `GET /v1/videos/{rawSn}/stream` (200·206) | `VideoStreamService` |
  | 2 | `GET /v1/frames/{srcSn}/image` | `FrameImageService.serveBySrcSn` (컨트롤러는 위임만) |
  | 3 | `GET /v1/frames/{srcSn}/deid-image` | `FrameImageService.serveDeidentified` |
  | 4 | `GET /v1/videos/{rawSn}/frames/{frameNo}/image` | `FrameImageService.serve` |
  | 5 | `GET /v1/portal/frames/{srcSn}/image` | `PortalLabelService.serveFrameImage` |

  5번은 포털(외부 채널)로 내보내는 **내부 파이프라인 비식별 프레임**이라 위 412 게이트 대상인데, 캐시만 `private, max-age=300` 으로 남아 신고 이후에도 최대 5분간 마스킹 실패 프레임이 재노출됐다(2026-07-28 누락 보정). 반면 **포털 업로드 자산**(`GET /v1/portal/uploads/frames/{uldFrmeSn}/image`, `PortalUploadService`)은 포털 사용자 **본인이 업로드한** 자산이라 비식별·신고 게이트 대상이 아니며(ADR-013 예외, 내부 파이프라인·데이터마트와 분리) 이 통일 대상이 **아니다**.

  이 응답들은 매 요청 게이트를 통과해야 하는데, 클라이언트가 `max-age` 동안 응답을 재사용하면 **요청이 서버에 오지 않아** 신고 직후에도 마스킹 실패 영상/프레임이 계속 재생·표시된다(재생 중 신고 시나리오에서 실증). 응답에 검증자(ETag/Last-Modified)가 없어 `no-cache`(재검증 강제)로 해도 304 가 성립하지 않아 대역폭 이득 없이 디스크 캐시 잔존 위험만 남으므로 `no-store` 로 통일했다. 서버측 `stream-meta` 캐시는 유지하되 **게이트를 캐시 앞(매 요청)에서 평가**하고, 신고/해소 시 **자기 `rawSn` 캐시만** 커밋 후 무효화한다(파생 캐시는 대상 아님 — 위 판정 범위와 대칭).
- **비식별 프레임 경로 검증은 단일 판정기 + NOFOLLOW open (CWE-59/367/22/359)** — 비식별 프레임을 서빙·산출하는 경로는 모두 `StorageSubtreePolicy.verifyDeidentifiedFile` 하나로 판정하고, **판정에 쓴 실경로(`toRealPath`)를 그대로** `LinkOption.NOFOLLOW_LINKS` 로 연다(`FrameImageService.openNoFollow` — 구현 1벌 공용).

  | 경로 | 구현 | 성격 |
  |------|------|------|
  | `GET /v1/frames/{srcSn}/image` · `/deid-image` · `/v1/videos/{rawSn}/frames/{frameNo}/image` | `FrameImageService` | 내부 |
  | `GET /v1/portal/frames/{srcSn}/image` | `PortalLabelService.serveFrameImage` | **외부(포털)** |
  | 검수 승인 export | `FrameSource` | 파일 산출 |

  운영 형상이 `STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH`(=`/nas-storage`, 의도된 동일 설정)라 `startsWith(deidBase)` 만 보는 lexical 검사는 `frames/raw/**`(마스킹 전 원본)까지 통과시키고(fail-open), `frames/deid/{rawSn}/f.jpg → ../../raw/{rawSn}/f.jpg` 심링크는 `Files.exists`/`Files.size`/`FileSystemResource` 가 모두 **따라가** 원본 픽셀을 "비식별본"으로 200 서빙한다. 그래서 ①서브트리 판정을 **실경로**에 적용하고 ②판정~open 사이 교체(TOCTOU)까지 NOFOLLOW 로 fail-closed 처리한다. 포털 경로는 **외부 채널**인데 이 정합에서 마지막까지 lexical 검증(`resolveSafe`)으로 남아 있던 것을 **2026-07-30 보정**했다(응답 계약은 불변 — 파일 부재 404 / base 이탈·서브트리 밖 403, 내부 경로·예외 원인 미노출). 포털 **업로드 자산**(`PortalUploadService`)은 본인 업로드분이라 이 대상이 아니다.
- **신규 API `GET /v1/frames/{srcSn}/deid-image`**: 프레임의 **비식별 이미지 전용** 서빙(`DE_IDNTF_SRC_FILE_PATH_NM`). 해상도 파생 프레임은 원본 픽셀이 실재하지 않아 `SRC_FILE_PATH_NM` 이 null 이므로 기존 `/image` 로는 조회되지 않는다. **원본 폴백 없음** — 비식별 경로가 없거나 파일이 없으면 404. 응답 200 / 401 / 403(미배정·경로 위반) / 404 / 412(신고 구간). 인가(`LabelAccessGuard`) → 신고 게이트 → 경로 검증(심링크·경로순회 차단) 순서로 평가한다. **2026-07-28 백엔드 신설 — FE 연동은 후속**.
- **해소(resolve) 시 export 재산출 재트리거**: `'F'→'Y'` 복원으로 위 게이트가 전부 자동 해제되고, 신고 구간에 보류됐던 **검수 승인(APPROVED) 영상의 export 재산출**이 `DeidentReportResolvedEvent` → `DatasetExportBridge`(AFTER_COMMIT)로 재개된다. 신고 구간 export 는 `LS_DATASET_EXPORT` 행을 남기지 않아 실패 회수기(FAILED 행 스캔)가 집지 못하므로 **해제 시점 재트리거가 유일한 복구 경로**다. **복구 범위는 해제된 영상 하나뿐**이다 — 어떤 신고가 막는 노드는 정확히 그 신고된 영상 하나이므로(위 판정 범위) **자손 팬아웃·상한·"다른 조상이 아직 신고 중인가" 판정이 모두 불필요**하다. 함께 발행되는 `DeidentGateReopenedEvent` 는 승인 여부와 무관하게 항상 발행되어 보류됐던 파이프라인 작업(특히 **VLM 시계열 위탁**)을 재개시킨다.
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
       응답 = {rawSn, procLogSn, status:"ACCEPTED"}   ※ kpstPrjId 필드 제거(아래)
  → KPST 영상단위 위탁(원본 raw_file_path_nm 입력, REQ_KND_CD='REDEIDENT') — 비동기
       실제 외부 전송은 <이 트랜잭션 커밋 후>에 개시된다(작업락이 커밋돼야 위탁 실패 시 해제가 성립).
       롤백되면 위탁은 개시되지 않고 선커밋 원장만 취소 종결된다(영상 상태 불변)
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
- **응답 계약 변경(2026-07-30) — `kpstPrjId` 필드 제거**: 논블로킹 제출로 **응답 시점에는 KPST 프로젝트 ID 가 존재할 수 없다**(항상 null). 절대 채워지지 않는 필드를 계약에 남기면 소비자가 불필요한 null 분기를 하게 되므로 제거했다(FE 사용처 0건 확인). **위탁 추적의 안정 식별자는 `procLogSn`** 이며 항상 채워진다 — 프로젝트 ID 가 필요하면 `procLogSn` 으로 원장(`LS_DEIDENT_PROC_LOG.DE_IDNTF_PJT_ID`)을 조회한다.
- 코드: `video/controller/ApprovedRedeidentController`, `video/service/ApprovedRedeidentService`, `batch/step/DeidentFrameAttacher`, `batch/service/KpstDeidentTxService`(완료 분기 `applyRedeidentCompletion`/`applyBatchCompletion`), `LS_DEIDENT_PROC_LOG.REQ_KND_CD`(V68 도입, V83 rename REQ_KIND_CD→REQ_KND_CD), `LS_AUTH_WORK_LOCK` partial unique index(V69)
- 이관 연계: v1→v2 이관 비식별 미완 영상의 정공법 해법 → [23](23-v1-v2-db-migration.md)

## 8.7 관련 데이터 (DB)

`LS_DEIDENT_REPORT`(누락 신고), `LS_DEIDENT_PROC_LOG`(처리 이력, `REQ_KND_CD` BATCH/REDEIDENT), `LS_DATA_RAW.DE_IDENT_YN`, `LS_AUTH_WORK_LOCK`(재진행 중 잠금, 동일영상 활성락 1건 UNIQUE). → [18](18-database.md).
