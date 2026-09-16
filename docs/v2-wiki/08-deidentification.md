# 08. 비식별화

> 출처: R1 RQ-SFR-09-01~05, R2 KLID-AT-UC-011/016, CLAUDE.md, 코드(`batch/step/DeidentifyStep`, `batch/service/KpstDeidentService`)
> 관련: [07 배치 파이프라인](07-batch-pipeline.md) · [19 외부 시스템](19-external-security-cvat.md) · [22 비식별 솔루션 API 명세](22-deid-solution-api.md)

> ✅ **KPST 공유 마운트 단일 경로 확정(2026-06-30)**: 비식별 확정은 실제 KPST API([22 명세](22-deid-solution-api.md))의 **공유 마운트 no-copy 모델**(`POST /project`[input_path=원본 디렉터리, export_path=우리 base]→`GET /retrieve_progress` 폴링→완료 응답 `fileName` 으로 경로 회수)로 **단일화**되었다(`KpstDeidentifyClient`+`KpstDeidentPollJob`(Quartz)). **`POST /upload`·`GET /download` 는 미사용**(공유 마운트로 입력 참조·결과 직접 산출). **레거시 동기 SPI(`DeidentifyClient`)와 결과 콜백 수신 경로(`POST /v1/deidentify/result`)는 제거**되었다(레거시 폴백 없음). `kpst.deid.enabled` 토글은 킬스위치로 유지(기본 **true**)하며, stg/prd 는 공유 마운트 실연동이며, **local/dev 도 자체 채움이 아니라 목 서버(:9400)로 실제 HTTP 위탁**한다. ⚠ **구 서술 폐기(2026-09-03)** — *"local/dev 는 `authoring.integration.deidentify.mock-mode=true`(원본 복사 mock, KPST 미호출) 기본"*. **두 가지가 동시에 거짓이다** — ①그 복사 경로는 폐지됐다(그 모드에는 **자체 산출 경로가 없다** — 남은 자리는 **판정뿐**이다) ②그 설정 키는 **어느 프로파일에도 설정돼 있지 않다**(꺼짐). 막을 산출 지점이 실재하지 않으므로 남은 토글은 **위탁 요청층에서 거부**한다 — 그 형상에서 비식별은 **전건 실패**하며 기동 기록과 상태로 드러난다. ⚠ **되살아나면 이 배선으로는 막히지 않는다** — 다시 만든다면 **산출 지점에 별도 차단**이 필요하다. 영상 1건=프로젝트 1개. (명세 정본은 [22.1](22-deid-solution-api.md#221-연동-개요) 참조.)

## 8.1 개요

영상 개인정보를 **외부 비식별 솔루션**(발주기관 SW 직접구매 제공)으로 처리. 저작도구는 **연동만** 담당.

- **파이프라인 선두 단계**(구현됨): 영상 적재 직후 자동 실행되며, 마킹 단계의 선행 조건이다 → [07](07-batch-pipeline.md#71-파이프라인-순서-구현됨)
- 대상: **전체 영상 무조건**(`PRVC_TYPE_CD` 게이팅 폐지 — `ANONY` 포함). 비식별 영상이 마킹·작업 대상이 되며 원본은 별도 보존
  - **★예외 — 출처유형 비식별 제외 (2026-09-14 확정 · ADR-066)**: 배포 설정 `authoring.deidentify.excluded-src-types`(env `DEIDENTIFY_EXCLUDED_SRC_TYPES`, 쉼표 복수, 기본 `GENERATED`)에 든 출처유형 영상은 **KPST 위탁 없이 원본을 비식별 영상 쓰기 위치(`deidVideoDir`)에 원본 파일명 그대로 일반 파일로 복사**해 단계를 끝낸다. 이력 `LS_DEIDENT_PROC_LOG` 성공 행(`REQ_KND_CD='EXCLUDED'`, `DE_IDNTF_FILE_PATH_NM`=복사본) → `DE_IDENT_YN='Y'` → `MARKING_READY`. **단계를 건너뛰지 않는다** — 뒤 소비처(마킹 가드·스트리밍·프레임 두 벌·파생·관제 뷰)는 무변경.
    - 판정: 비식별 단계(`DeidentifyStep.run`) 처리 시점 1회, KPST 활성 여부 검사보다 앞 · `srcType` null 은 대상 아님 · 비우면 전부 위탁 · **소급 없음** · **기동 시 값 검사 없음**(적용 목록 INFO 로그 1줄) · 관리 화면 설정 아님(`ConfigKeys.ALLOWED` 밖 — 저장 API 거부).
    - 복사 실패(원본 부재·경로 위반·IO): `'F'` + 이력 `FAILED`(EXCLUDED) + `MARKING_READY` 미전이 + 임시 파일 정리 · 원본 불변.
    - 제외 행은 `POLL_STTS_CD` 가 비어 KPST 폴링·ACK 유예 회수 대상이 아니다.
    - **회수 지점은 검수** — 오표기(실제 인물 영상이 `GENERATED`)는 검수 중 비식별 누락 신고(§8.4) → 외부 재비식별 → 해소. ⚠ 검수완료 재비식별(§8.6)은 이미 `'Y'` 인 영상을 거부하므로 **회수 경로가 아니다**. 승인 이후·그 파생본은 경로 밖(인지·수용).
    - 영상 상세 비식별 이력 패널은 표시를 바꾸지 않았다 — 제외 회차가 「비식별 / 배치 비식별」로 보인다(인지·수용). 관제 뷰 `DE_IDNTF_YN='Y'` 가 제외 영상에도 서지만 관제와 무관하다 — 2026-09-14 관제 회신: 비식별 작업은 전부 저작도구로 이관돼 관제는 이 값으로 판단하지 않고, 생성형 AI 서버에서 생성된 작업은 기존에도 `GENERATED` 로 전달된다(관제 수정 없음).
- **자동 트리거**: 적재(TUS 업로드 + dev 경로) → `VideoIngestedEvent` → `IngestDeidentifyBridge`(AFTER_COMMIT) → `AsyncDeidentifyRunner`(@Async) → `DeidentifyStep.run` → 성공 시 `LsDataRaw.dataSttsCd = MARKING_READY`
  - ⚠ **증강(augment) 적재 경로는 아직 `VideoIngestedEvent` 미발행** — 선두 비식별 자동화 미연동(planned/후속)
- 출력: `STORAGE_DEIDENTIFIED_PATH` 하위 강제 (CWE-22 경로 검증)
- **원본 절대 삭제 금지**, 원본·비식별본 별도 보관
- SSRF(CWE-918) 차단 — `application.yml` base-url 사용, 사용자 입력 URL 구성 금지

## 8.2 연동 흐름

비식별 확정 경로는 **KPST 폴링 단일 경로**다(`kpst.deid.enabled=true`, 기본). **전 환경이 이 경로 하나뿐이다** — local/dev 는 위탁 대상이 목 서버(:9400)일 뿐 흐름은 같다.

> ⚠ **구 서술 폐기(2026-09-03)** — *"local 자족 환경만 mock 복사 경로를 사용한다"*. 자체 채움 경로가 폐지되어 **견줄 두 번째 경로가 없다**.

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

**[구 「local mock 경로」 — 폐기(2026-09-03 갱신)]**

> ⚠ **아래 흐름은 더 이상 존재하지 않는다. 지우지 않고 남겨 둔다 — 되살리려는 시도를 막기 위해서다.**
>
> ```
> [배치] DeidentifyStep.runMock → 원본을 STORAGE_DEIDENTIFIED_PATH 하위로 atomic 복사(원본 보존)
>         ↓ (외부 미접촉)
> LS_DATA_RAW.DE_IDENT_YN='Y' + 작업락 해제 + 신고 해소 + 알림
>    원본 부재 시 'F' 마킹(성공 위장 금지, REQUIRES_NEW 독립 커밋)
> ```
>
> **폐기 사유**: 이 경로는 마스킹되지 않은 **원본을 「비식별 완료」로 통과**시켜 데이터마트 뷰와
> 산출물로 내보냈다(`DeidentifyStep.runMock` 삭제). 이 프로젝트는 로컬조차 외부 시스템을 **별도 목
> 서버**로 세워 실제 HTTP 로 호출한다 — 애플리케이션이 스스로 결과를 지어내는 경로를 두지 않기 위해서다.
>
> **지금 남은 것은 판정뿐이다** — 그 모드에는 **자체 산출 경로가 없다**. 막을 산출 지점이 실재하지
> 않으므로 남은 토글(`authoring.integration.deidentify.mock-mode`)은 **위탁 요청층에서 거부**한다.
> 그 형상에서 비식별은 **전건 실패**하며 기동 기록과 상태로 드러난다.
>
> **활성화 실태**: **어느 프로파일에도 설정돼 있지 않다**(꺼짐).
>
> ⚠ **되살아나면 이 배선으로는 막히지 않는다** — 위탁 요청층 거부는 HTTP 로 나가는 경로만 덮는데
> 자체 복사는 HTTP 를 타지 않기 때문이다. 다시 만든다면 **산출 지점에 별도 차단**이 필요하다.

> ⚠ **레거시 동기 SPI/콜백 경로 제거(UC018)**: 구 `DeidentifyClient`(동기 위탁) + `POST /v1/deidentify/result` 콜백 수신(`DeidentifyResultController`/`DeidentifyResultService`/`DeidentifyResultRequest`) 경로는 제거되었다. KPST 서비스가 없으면(설정 오류) `DeidentifyStep` 은 레거시 폴백 대신 명확한 설정 오류 예외(내부 정보 미노출)로 처리한다. ⚠ 구 서술 *"mock 도 아니고"* 는 폐기다 — **견줄 mock 분기가 없어 경우의 수는 하나다**.

- 실패 시 `DE_IDENT_YN='F'`, 원본 보존. 재비식별은 외부 솔루션 수동 처리(자동 재비식별 큐 없음).
- **★선두 비식별 실패는 기존 배치 재시작으로 다시 돌린다 (2026-09-16 사용자 확정 · `ADR-006` v7 · `API-167`·`API-199` · `AC-1133`~`AC-1135`)** — 사용자 신고: *"비식별 실패했을때 다시 할 수단이 없어."* 적재 직후 선두 비식별이 실패한 영상(`DE_IDENT_YN='F'` + 배치 단계 `PENDING`)은 배치 재처리(마킹 이후 단계만)·검수완료 재비식별(승인 영상만)·신고 해소(열린 신고 필요) 어디에도 걸리지 않아 **영구 고착**이었다.
  - **새 창구·새 버튼은 없다.** *"비식별 실패도 배치 실패아냐?"* — 기존 재시작(건별 `POST /v1/videos/{rawSn}/batch/retry` · 일괄 `POST /v1/videos/batch/retry`)이 이 형상이면 **적재 직후와 같은 선두 비식별 단계**를 다시 수행한다(외부 위탁 또는 출처유형 제외 복사 — 처리 시점 설정으로 판정). 형상이 아니면 종전 동작 그대로다.
  - 거부: 파생영상 400 · 승인 이력 409 · **열린 비식별 누락 신고 409**(그 `'F'` 는 신고 표식이다 — 246 실데이터 raw 21 처럼 이관 영상은 신고 표식 `'F'` 가 `PENDING` 으로 있을 수 있다) · 진행 중 위탁 409 · 동시 요청 409.
  - 접수 응답은 `{rawSn, stage:"PENDING"}` — 배치 단계를 선점하지 않는다. 실행은 **수동 재기동 전용 풀**(`batchReprocessExecutor`, AbortPolicy)에서 돈다 — 적재 직후 비식별 풀(CallerRuns)을 쓰면 일괄 재시작이 포화될 때 요청 스레드에서 원본 복사가 돌고 신규 적재 비식별을 굶긴다(독립 QA 1차 fail 로 교정). 포화 시 503.
  - 재시작은 영상 작업 잠금(`LCK_ID` 접두 `DEIDRETRY-`)을 잡고, **성공·모든 실패 종결에서 그 잠금만** 푼다(트랙 병합·검수완료 재비식별 잠금은 유지). 스키마 변경 없음.
  - ⚠ **자동 재시도는 여전히 없다** — 사람이 누르는 수동 재시작이라 「자동 재비식별 큐 없음」(`ADR-006` ③)과 양립한다. 외부 재비식별 후 해소는 **열린 신고 영상**의 경로로 한정된다.
  - 목록 비식별 배지는 최신 회차가 진행 중이면 `'F'` 보다 **진행 중**을 먼저 보인다.
  - 진단 쿼리: `deploy/onprem/scripts/verify/verify-queries.sql` 14절.
  - **재위탁 이름은 회차마다 다르다 (2026-09-17)** — 첫 위탁 `raw{영상번호}`, 이후 `raw{영상번호}r{이력 번호}`. 고정 이름이면 위탁이 나간 뒤 실패한 영상의 재시작이 KPST 의 동일 이름 409 로 영원히 막힌다(246 실측). 상세 [22](22-deid-solution-api.md) 22.3.3.
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

### 8.3.1 비식별 처리 결과 리포트 적재 (R14, 2026-08-11 신설)

지금까지는 "언제 맡겨 언제 끝났나"만 있고 **"무엇을 얼마나 가렸나"**가 없었다 — 외부 비식별 솔루션(KPST)의 처리 결과 리포트 API(`GET /retrieve_report`, [22 §22.3.7](22-deid-solution-api.md))는 규격서에 있으나 호출조차 하지 않아 검출 집계가 DB에 전혀 없었다.

- **새 테이블을 만들지 않는다** — `LS_DEIDENT_PROC_LOG`는 위탁 **회차마다 새 행을 INSERT**한다(최초 배치 비식별 + 검수완료 후 재비식별 재위탁 모두 append). 그 행들이 곧 영상 단위 비식별 이력이므로 컬럼만 얹으면 「비식별 이력」이 그대로 성립한다.
- **조회 시점**: `KpstDeidentPollJob`이 폴링으로 완료(state=2)를 감지한 직후 **1회** `GET /retrieve_report`를 조회한다. **리포트 조회 실패·미매칭은 완료 흐름을 막지 않는다** — WARN 로그 후 집계 6종을 `null`로 남긴 채 정상 완료 처리를 계속한다(리포트는 있으면 좋은 것이지 비식별 완료의 전제가 아니다).
- **신규 컬럼 6종**(`LS_DEIDENT_PROC_LOG`, V184, 전부 nullable — DEFAULT 없음): `FACE_DTCT_CNT`(얼굴검출수) · `NOPLT_DTCT_CNT`(번호판검출수) · `FRME_CNT`(총 프레임수) · `PRCS_BGNG_DT`/`PRCS_END_DT`(외부 솔루션 처리 시작·종료 일시) · `RPT_FILE_PATH_NM`(리포트가 회신한 파일 경로, VARCHAR(1000)).
  - `NULL`은 "0건 검출"과 **다른 뜻**이다 — 컬럼 신설 이전 회차이거나 리포트 조회에 실패한 회차다. DEFAULT를 두지 않는 이유도 이 둘을 구분하기 위해서다.
  - `RPT_FILE_PATH_NM`은 벤더 응답의 `dsStatus[].fileName`인데, 실측상 **결과 파일명이 아니라 원본 입력파일의 절대경로**다(비식별 산출물 경로는 여전히 `DE_IDNTF_FILE_PATH_NM`이 담당). 화면에는 노출하지 않는다(개인정보 위치를 특정하는 경로 정보, CWE-359).
- **영상 상세 「비식별 이력」 패널**(SC-009): `VideoDetailResponse.deidentHistory`로 노출한다. 항목 1건 = `LS_DEIDENT_PROC_LOG` 1행 = 위탁 1회차 — 최초 배치 비식별과 재비식별이 각각 한 행을 남기므로 이 목록이 "이 영상을 언제 몇 번 비식별했고 무엇을 얼마나 가렸는가"를 그대로 보여준다. 표시 항목: 처리 상태(`procSttsCd`) · 요청 종류(`reqKndCd`, null=배치 비식별/`REDEIDENT`=검수완료 재비식별) · 요청·종결 일시 · 검출 집계 3종 · 외부 솔루션 처리 시작·종료 일시.
  - **위치**: 「기본 정보」 탭 **최하단**, 배치 실패 사유·조치 패널 바로 다음. 처리 단계 표시기가 "지금 어디까지 왔나"라면 이 이력은 "몇 번 어떻게 처리했나"로 같은 관심사의 연속이라 탭을 새로 만들지 않고 이어 붙였다.
  - **★권한은 검수자·라벨링 작업자 공통**이다. 바로 위의 배치 실패 패널이 **검수자 전용**(`isReviewer` 가드)인 것과 **다르다** — 이력은 조치 수단이 아니라 읽기 전용 사실이고, 작업자도 자기가 맡은 영상이 몇 번 비식별됐는지 알아야 한다. 두 패널이 붙어 있다고 해서 권한을 같이 맞추지 말 것.
  - **정렬은 서버가 정하고 화면은 다시 정렬하지 않는다** — 최신순(`REQ_DT DESC`)이며, 같은 시각에 들어온 회차는 `PROC_LOG_SN DESC`(IDENTITY 증가라 결정적)를 2차 키로 쓴다. 정렬 규칙을 화면이 한 번 더 갖고 있으면 두 번째 진실원이 된다.
  - **최신 20건 상한**(`VideoQueryService.DEIDENT_HISTORY_MAX`) — 위탁 실패가 누적되면 한 영상의 회차가 계속 늘 수 있어 응답 건수를 제한한다(CWE-770). ⚠ **화면에 "일부만 표시" 안내는 없다** — 상세 화면이 보여줄 양을 넘는 이력은 그 화면의 관심사가 아니라고 보고 안내를 두지 않았다(있는 것처럼 서술하지 말 것).
  - **집계가 하나도 없는 회차(구 데이터·조회 실패)는 집계 줄 자체를 감춘다** — `0`으로 채우면 "0건 검출"과 구분되지 않는다. 처리 시작·종료 일시도 둘 다 없으면 같은 이유로 그 줄을 감춘다.
  - **알 수 없는 상태 코드는 원문을 노출하지 않고 '진행 중'으로 둔다** — 표시 문구는 `SUCCEEDED`=완료 · `FAILED`=실패 · `REQUESTED`=진행 중이고, 그 밖의 값은 코드 문자열을 그대로 화면에 흘리지 않는다.
  - **이력이 0건이면 빈 화면이 아니라 「비식별 이력이 없습니다.」** 안내를 보여준다.
  - **파일 경로는 응답에도 화면에도 없다** — BE가 내려주지 않고 화면도 요구하지 않는다.
  - 기존 `VideoDetailResponse.from(...)` 오버로드 6종은 `deidentHistory`를 빈 배열로 위임한다 — 응답 필드 **추가만**(하위호환, 기존 소비자 영향 없음). 화면도 필드 자체가 없는 구 응답에서 빈 이력으로 폴백한다.
- 코드: `common.client.KpstDeidentifyClient.retrieveReport`(진행조회와 같은 GET+JSON 바디 경로 공유, 같은 Resilience4j 정책) · `batch.service.KpstDeidentService.fetchReportQuietly` · `batch.service.KpstDeidentTxService`(완료 전이와 **같은 트랜잭션**에서 적재 — 원자 클레임 성공자 안에서만 기록해 2노드 중복 방지) · FE `features/video/components/DeidentHistoryPanel.tsx`.

## 8.4 누락 신고 (RQ-SFR-09-03, UC-016)

작업자가 라벨/마킹 작업 중 비식별 누락(PII 노출)을 발견하면:

```
누락 신고 (LS_DEIDENT_REPORT: OPEN + ★DCLR_STP_CD=MARKING|LABELING)
  ※ ★비파생 영상에서만 접수 — 파생영상은 412 거부
  ※ ★마킹 단계 신고는 배치 단계가 MARKING_READY 일 때만 접수 — 아니면 412 (라벨링 단계는 무관)
  ※ ★검수가 승인(APPROVED)된 영상은 접수하지 않는다 — 412 거부 (역할 무관, R2)
  → 작업락 + DE_IDENT_YN='F' (★라벨도 개인정보 3필드도 보존 — 삭제·리셋 안 함)
  → 신고 구간 동안 해당 영상 라벨 조회·저장 모두 차단(412) — 라벨은 보존되고 resolve 시 그대로 재사용
  → ★게이트는 자기 rawSn 행의 DE_IDENT_YN='F' 만 판정 (조상·자손 전파 없음)
  → 작업자/검수자가 외부 비식별 솔루션으로 수동 비식별화
  → ★재비식별 산출물 선택(R3): 서버가 산출 디렉터리를 열거해 후보 제시 → 사람이 고름
                        (외부 솔루션이 다른 이름으로 산출하면 서버는 어느 것이 결과인지 모른다)
  → 수동 해소(resolve): ★OPEN→RESOLVED 조건부 UPDATE(원자 클레임, 1행 획득자만 진행)
                        + 선택 산출물을 원장에 새 SUCCESS 행으로 적재(하류가 옛 파일을 쓰지 않게)
                        + DE_IDENT_YN 'F'→'Y' 복원 (원본 보존)
  → 조회 게이트 자동 해제 → 보존된 기존 라벨을 그대로 재사용
                          + APPROVED 영상이면 그 영상 하나의 export 재산출 재트리거
  → ★신고 단계별 재개 (DeidentStageResumeEvent, AFTER_COMMIT)
       MARKING  → 배치 단계 MARKING_READY 되감기 + 활성 마킹 종결  = 마킹부터 다시
       LABELING → 프레임 이미지만 재추출(마킹 유지 · 라벨 좌표 보존) = 라벨링 이어서
       NULL(레거시) → 재개 이벤트 미발행 (단계 미상 — 지어내지 않음)
```

- 상태: `OPEN` / `RESOLVED` / `DISMISSED`
- 코드: `deident/`, `frontend DeidentReportButton`, `LS_DEIDENT_REPORT`(V21)
- **★개인정보 3필드 리셋 폐기 (2026-08-04 사용자 확정, 구속)**: 구 정책은 신고 시 **프레임 축(`LS_DATA_SRC`, V130)·영상 축(`LS_DATA_RAW`, V163)의 익명/가명/PII 3필드를 모두 `null` 로 리셋**하고 그 사실을 행 단위로 감사(`LS_DATA_LBL_HSTRY` · `LS_TASK_EVNT_LOG PRIVACY_META_RESET`)했다. 구 근거는 *"그 판정은 비식별이 잘못된 영상에서 내려진 것이라 재판정 대상이고, 남겨두면 재비식별 후에도 옛 판정이 export 에 stale 로 실린다(CWE-359)"* 였다. **폐기 사유**: 라벨 보존 정책(2026-07-27)과 **같은 취지** — 사람이 입력한 판정도 작업 결과이므로 신고로 폐기하지 않고 해제 후 그대로 이어서 진행한다. stale 우려는 신고 구간의 **export 산출 보류 + 해제 시 재산출·재통지**가 담당한다. 리셋 감사 이벤트 타입·팩토리는 **과거 행 판독용으로 존치**(신규 발생 0).
  - ⚠ **개인정보 메타 PUT 412 게이트는 그대로 유지**된다 — 근거만 교체됐다: 신고 구간은 "비식별이 잘못됐다"고 알려진 구간이라 그 위에서 내린 판정을 새로 쓰면 resolve 후 그대로 관제로 나간다(리셋 여부와 무관하게 성립). 영상 축·프레임 축 양쪽에 건다.
- **신고 접수 진입점 2개**:

  | 단계 | 엔드포인트 | 식별 축 | 비고 |
  |------|-----------|:------:|------|
  | 라벨링 | `POST /v1/labels/{srcSn}/deident-report` | 프레임 | 기존 |
  | **마킹** | **`POST /v1/videos/{rawSn}/deident-report`** | **영상** | 마킹 화면은 비식별 *영상* 재생이라 프레임 컨텍스트가 없다. **배치 단계 `MARKING_READY` 한정** |

  ⚠ 구 서술 *"마킹 단계 rawSn 경로 구현 완료(B-ISSUE-28)"* 는 **BE 만 구현된 상태를 뭉뚱그린 것**이었다 — API 는 있었으나 마킹 화면(`MarkingPage`)에 신고 버튼이 없어 **도달 경로가 0** 이었다(FE 호출 0건). 2026-08-05 FE 신설로 해소.

  두 경로의 **부수효과는 완전히 동일**하다 — `DeidentReportService` 내부에서 같은 본체(`doReport`)로 수렴하므로 갈라질 수 없다(파생영상·비식별 미수행·**검수 승인** 412 거부 · 작업락 · `DE_IDENT_YN='F'` · 스트림 메타 캐시 무효화 · REVIEWER 알림). 차이는 둘뿐이다: ①인가가 `LabelAccessGuard.verifyRawAccess`(영상 단위, 규칙은 동일 — REVIEWER 전체 / WORKER 본인 배정만) ②신고 단계(`DCLR_STP_CD=MARKING`, V171 — 해소 후 재개 지점이 갈린다). 응답 규약도 동일: 201 / 400(사유 누락·1000자 초과) / 401 / 403 / 404 / 409(이미 재비식별 중) / **412(파생영상 · 비식별 미수행 · 마킹 단계 아님 · 검수 승인)**. 해소는 두 경로 모두 `POST /v1/deident-reports/{rprtSn}/resolve` 공통.
  ⚠ 구 서술의 세 번째 차이 *"`TaskModifiedEvent.srcSn=null`(영상 단위 변경)"* 와 부수효과 *"APPROVED 영상 `TASK_MODIFIED` 통지"* 는 **폐기**(R2, 2026-08-10) — 승인 영상은 접수 자체가 412 라 그 발행 분기가 도달 불가가 됐다.

  **비식별 미수행 영상은 412 (프리컨디션)**: `DE_IDENT_YN='N'`(비식별 미실행, `PENDING`)인 영상은 신고를 접수하지 않는다. 라벨링(srcSn) 경로는 프레임이 있어야 도달하므로 사실상 비식별·프레임추출 완료가 전제였지만, 마킹(rawSn) 경로는 이 상태에 직접 닿는다. 접수하면 ①`'N'→'F'` 로 `LsDataRaw.hasDeidentArtifact()` 가 **거짓으로 true** 가 되어 증강·해상도 파생 부모 게이트를 통과하고(뒤의 산출물 실재 fail-closed 검사가 막긴 하지만 판정 원천이 거짓이 되는 것 자체가 결함) ②"외부 솔루션이 **재**비식별했다"는 전제의 `resolve` 로만 풀 수 있는 작업락이 파이프라인 진행 중 영상에 고착된다. 판정은 `hasDeidentArtifact()`(`'Y'`|`'F'`) **단일 원천**이므로 **이미 신고된 `'F'` 는 통과**하며 그 중복 신고는 기존 409 경로가 처리한다.
- **★신고 단계 구분 + 해소 후 재개 지점 분기 (2026-08-05 사용자 확정, 구속 · V171 `LS_DEIDENT_REPORT.DCLR_STP_CD`)**

  | 신고 단계 | 접수 조건 | 해소 후 재개 지점 |
  |---|---|---|
  | **MARKING** (rawSn 경로) | **배치 단계가 `MARKING_READY` 일 때만** — 아니면 412 | 비식별 재수행 결과 위에서 **마킹부터 다시** |
  | **LABELING** (srcSn 경로) | **기존 동작 유지**(배치 단계 무관) | **프레임 이미지만 다시 뽑아** 라벨링을 이어간다 — 마킹 유지, **라벨 좌표 보존** |

  - **왜 마킹 단계만 `MARKING_READY` 로 제한하나**: 그 상태는 **선두 비식별 성공 직후·마킹 이전**이라 **프레임 행(`LS_DATA_SRC`)도 라벨도 아직 없다**(프레임 추출은 마킹 완료로 트리거되는 배치의 `FRAME_EXTRACT` 단계이고, 그 배치는 진입 시 단계를 `PROCESSING` 으로 전이한다). 따라서 ①재마킹이 파괴할 작업 결과가 없고 ②검수 완료(APPROVED) 영상의 강등 충돌이 없으며(APPROVED 는 배치 단계가 `COMPLETED`) ③마킹 화면에서만 도달 가능한 상태라 단계 판정이 상태로 확정된다. 이 제한이 세 문제를 한 번에 없앤다.
  - **거부는 412 한 종류·문구 한 종류**다 — `PROCESSING`/`COMPLETED`/`FAILED`/`PENDING` 을 구분해 알리지 않는다. 구분하면 응답이 **영상 처리 단계를 알려주는 오라클**이 된다(CWE-209, 스트리밍 404 통일과 같은 취지). 문구: *"마킹 단계에서만 이 화면으로 비식별 누락을 신고할 수 있습니다. 이미 다음 단계로 넘어간 영상은 라벨링 화면에서 신고해 주세요."*
  - **재개 배선**: `resolveManually`/`resolveOpenReports` → **신설 `DeidentStageResumeEvent`** → `DeidentStageResumeBridge`(`AFTER_COMMIT`) → `DeidentStageResumeService`(별도 빈 `REQUIRES_NEW`). `AFTER_COMMIT` 이어야 `'F'→'Y'` 복원 커밋 뒤에 돌아 재개 작업이 자기 게이트에 스스로 막히지 않는다.
  - **단계와 무관하게 발행되는 2종은 무변경**: `DeidentGateReopenedEvent`(항상 — VLM 위탁 재개) · `TaskModifiedEvent`(`META_UPDATED` + `exportRegenerated=true` + `needsRecheck=true`, APPROVED 한정 — **재검토 표시만 세운다**). 각각 별개 구독자의 계약이며 신고 단계와 무관하게 필요하다.
    - ⚠ **`DeidentReportResolvedEvent` 는 이 경로에서 발행되지 않는다 — 발행처 0건인 휴면 확장점이다.** 클래스와 `DatasetExportBridge` 수신 배선은 그 자리가 다시 필요해질 때를 위해 **존치**하되, 해소 시점의 즉시 재산출·재통지는 폐기됐다(재생성·통지 트리거는 **검수 승인 한 곳**). 판정 근거: `DeidentReportService(publishResolvedForExportRecovery)`.
  - **라벨링 재개 = `DeidentFrameAttacher.attachDeidentFrames(refreshExisting=true)` 재사용** — 기존 `LS_DATA_SRC` 행을 dirty-update 하므로 `SRC_SN` 이 보존되어 라벨 FK 가 끊기지 않는다. ⚠ **`FfmpegFrameExtractor` 를 쓰면 안 된다**(`LsDataSrc.create()` 로 새 행을 INSERT → 기존 라벨 고아화). 비식별 영상 경로는 **`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 적재값을 읽는다**(조합·추측 금지 — mock=`deidentified.mp4` / KPST=`{stem}-mask{ext}`). 경로가 없으면 **재추출을 건너뛰고 WARN**(fail-closed).
  - **마킹 재개는 상태 되감기까지만**: 배치 단계를 `MARKING_READY` 로 되감고 활성 마킹(`PENDING`/`VLM_REQUESTED` — `LsMarking.ACTIVE_STATUSES`)을 `SKIPPED` 로 종결해 재마킹 409(V142 부분 유니크)를 푼다. 실제 재실행은 사람이 다시 마킹하면 기존 `MarkingCompletedEvent → MarkingBatchBridge` 가 그대로 탄다(파이프라인 재구현 금지). `VLM_REQUESTED` 도 종결 대상인 이유: 그 위탁은 **신고된(마스킹이 잘못된) 비식별본**을 대상으로 나간 것이라 결과를 재비식별 후에 소비하면 안 되고, 남겨두면 재마킹이 영구히 막힌다. 지각 콜백은 `VlmResultService.markingsInScope` 가 활성 상태만 조회하므로 종결된 마킹을 전이시키지 못한다.
  - **단계 미상(NULL)은 백필하지 않고 재개 이벤트도 발행하지 않는다** — 컬럼 신설 이전 신고는 어디서 접수됐는지 알 수 없고, 지어내서 마킹으로 오판정하면 **라벨이 있는 영상을 재마킹 대기로 되감는다**. 미발행 = 기존 2종만 도는 현행 동작 유지.
  - **`resolve` 원자 클레임 (CWE-362, 2노드 Active-Active)**: `OPEN→RESOLVED` **조건부 UPDATE**(`LsDeidentReportRepository.claimResolve`)로 전이하고 **영향행수 1 을 받은 성공자만** 락 해제·`'Y'` 복원·재개 이벤트를 수행한다. 0행은 409. 구 `findById`→상태 문자열 비교는 read-then-write 라 두 노드가 동시 통과해 무거운 프레임 재추출이 2회 기동됐다. 선례 `BatchTransitionService.tryClaimReprocessFromFailed`.
  - **★선결 결함 수정(같은 라운드) — 재추출이 엉뚱한 장면을 뽑던 문제**: `DeidentFrameAttacher` 가 `FRM_NO`(**추출 순번** 0,1,2…)를 프레임 번호로 넘겨 비식별 영상의 **맨 앞 0·1·2 번**을 뽑아 붙이고 있었다. 실제 영상 내 위치는 **`VDO_FRM_NO`** 다(초기 추출 `FfmpegFrameExtractor` 는 `seekMillis = frameIndex × 1000 / fps` 로 실제 위치를 뽑고 `LsDataSrc.create(rawSn, i, frameIndex, …)` 로 두 값을 각각 적재한다). 이 상태로는 "라벨 좌표 보존"이 성립하지 않는다. **이 결함은 기존 KPST 재비식별 경로(`KpstDeidentTxService.applyRedeidentCompletion`)에도 있었고 함께 고쳐진다.**
    - **NULL(레거시 행)은 순번 폴백 없이 그 프레임만 skip + WARN** — 폴백하면 지금 고치는 결함을 그대로 유지하는 것이다(fail-closed). 값이 있지만 비정상(음수)이면 추출기가 던지는 예외로 **전체 롤백**(조용한 skip 보다 시끄러운 실패).
    - **출력 파일명·디렉터리는 불변**: `{deidBase}/frames/deid/{rawSn}/frame-{FRM_NO}.jpg` — 초기 추출(`FfmpegFrameExtractor`)과 **디렉터리·파일명이 동일**해 **제자리 교체**되므로 고아 파일이 생기지 않는다. 즉 "어디서 뽑는가"만 바뀌고 "어디에 쓰는가"는 그대로다.
  > ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 이 절 전체(§8.4)가 비식별 신고 관리 화면을 *"`SC-033`"* 이라 불렀으나 이는 낡은 식별자다. 코드 확정 식별자는 **`SCREEN-032`(축약 `SC-032`)**다(`DeidentReportListPage.tsx`). `SC-033`은 현재 **포털 업로드** 화면에 배정돼 있어 그대로 두면 다른 화면을 가리키게 된다. 아래 「신고 관리 화면」·「신고 관리 목록」·「화면」 3곳의 `SC-033`을 전부 `SC-032`로 정정했다. 근거: `04-screens-ia.md`(§4.2 「화면 ID 재정합」) · `reports/wiki-align-20260819/facts/F3-frontend-screens.md`.
  - **신고 관리 화면(SC-032)에 신고 단계 노출 (2026-08-05)**: 재개 지점이 단계로 갈리는데 목록(`GET /v1/deident-reports`)에 단계가 없어 **REVIEWER 가 "이 신고를 해소하면 무엇이 일어나는지"를 알 수 없었다.** 응답에 `stage`(`MARKING`|`LABELING`|`null`)를 **optional 추가**(기존 7필드는 이름·타입·유무 불변 — 응답 필드 추가만 하위호환)하고, 화면 `/manage/deident-reports` 에 **「신고 단계」 열**을 둔다.

    | 값 | 화면 표기 | 툴팁(요지) |
    |---|---|---|
    | `MARKING` | **마킹** | 해소하면 마킹부터 다시 진행 |
    | `LABELING` | **라벨링** | 해소하면 프레임 이미지만 다시 만들고 기존 마킹·라벨은 유지 |
    | `null`(레거시) | **미상** | 해소해도 재마킹·프레임 재추출이 자동으로 진행되지 않음 |

    - **코드값 원문(`MARKING`/`LABELING`)과 내부 컬럼명(`DCLR_STP_CD`)은 화면에 노출하지 않는다** — BE 는 코드를, FE 가 사용자 언어를 담당한다(상태 컬럼과 동일 관례).
    - **`null` 을 빈칸으로 두지 않는다** — 빈칸은 "값이 없다"와 "로딩 실패"가 구분되지 않는다. **'미상'** 은 "단계가 없다"가 아니라 **"기록이 없다"**는 뜻이며(그 신고도 어딘가에서 접수됐다), 이 행은 해소해도 단계별 재개가 없다는 사실을 툴팁이 알린다. **없는 단계를 지어내 표기하지 않는다**(백필 금지 정책과 같은 취지).
- **신고 관리 목록 `GET /v1/deident-reports?status&page&size` (REVIEWER 전용, SC-032)**: `status` 는 `OPEN`(기본)/`RESOLVED`/`DISMISSED` allowlist 만 허용(그 외 400). 응답 행(`DeidentReportListResponse`)은 신고자를 **두 축**으로 내린다 — `reporterNo`(`USER_NO` 원값, 하위호환) + **`reporterName`**(`LS_ACNT_USER.USER_NM`, 2026-08-04 추가). **화면 '신고자' 컬럼은 `reporterName` 을 표시**한다(내부 번호를 사람 이름 자리에 찍지 않는다). 이름은 페이지의 `USER_NO` 를 **단일 IN 쿼리**로 한 번에 해석하고(N+1 금지), 마스터에 없는 번호(탈퇴·계정 삭제)는 **`null`** 로 남기되 목록 조회 자체는 정상 반환한다(fail-soft). 같은 응답에 **`stage`**(신고 단계, V171)도 optional 로 함께 실린다 — 위 「신고 관리 화면(SC-032)에 신고 단계 노출」 참조.
- 자동 재비식별 큐는 폐기 → **수동 비식별화**가 해소 주체(외부 비식별 SW). ⚠ 이것은 **열린 신고 영상**의 경로다 — 신고 없이 선두 비식별이 실패한 영상은 배치 재시작으로 다시 돈다(위 「선두 비식별 실패」 항목)
- **★라벨 보존 정책 (2026-07-27 사용자 확정 — 구 "전체 라벨 삭제 + 복원 스냅샷" 폐기)**: 신고는 "비식별이 잘못됐다"는 신호일 뿐 라벨 작업 결과를 폐기할 근거가 아니므로 **해당 영상의 라벨을 삭제하지 않는다**. 구 정책이 삭제 직전에 남기던 `LS_LABEL_VERSION`(`SAVE_REASON='DEIDENT_REPORT'`, `ACTIVE_YN='N'`) **비활성 스냅샷도 더 이상 적재하지 않는다** — 그 스냅샷은 `DATA_SRC_SN=NULL`(영상 스코프)이라 프레임(srcSn) 스코프인 버전 목록·롤백 API 에서 조회·복원할 수 없는 write-only 이력이었다(D-ISSUE-25). 이미 적재된 기존 행은 보존하며, 프레임 스코프가 아닌 버전 해시로 diff 를 호출하면 400 으로 명시 거부한다(구 미처리 500 수정 — D-ISSUE-26). 삭제분 소급 복구는 하지 않는다.
- **신고 구간 라벨 조회 차단 게이트 (S7, CWE-359)**: 라벨이 보존되므로 신고~재비식별 완료 사이에 라벨 좌표(=PII 위치 특정 정보)가 계속 노출되는 창이 생긴다. 따라서 `DE_IDENT_YN='F'` 인 동안 해당 영상 프레임의 라벨 조회(`GET /v1/frames/{srcSn}/labels`)를 **412 PRECONDITION_FAILED** 로 차단한다. 인가(WORKER 본인 배정/REVIEWER) 검사를 통과한 **뒤** 평가하는 프리컨디션이며 **REVIEWER 도 동일하게 차단**된다(영상 스트리밍의 비식별 미완료 NOT_FOUND·마킹 진입 게이트와 같은 역할 무관 정책). 라벨 저장/수정(`PUT /v1/frames/{srcSn}/labels`)도 **같은 게이트가 412 로 차단**한다 (2026-08-04, C-ISSUE-22 — 구 서술 *"작업락 409 가 차단한다"* 는 거짓이었다: 작업락은 6h 만료 후 `WorkLockSweepJob` 이 회수하는데 `'F'` 는 resolve 까지 남아 **조회 412 ↔ 저장 200** 비대칭이 열렸고, full-replace 계약상 `items:[]` 저장이 기존 라벨을 전량 삭제했다). 게이트는 **락 검사보다 먼저** 평가해 락 유무와 무관하게 412 로 통일하며, 409 는 **신고와 무관한 락**(트랙 병합 등)에만 남는다. 결과적으로 신고 구간은 읽기·쓰기 모두 봉쇄된다. `resolve` 가 `'F'→'Y'` 를 복원하면 게이트가 자동으로 열려 **보존된 라벨을 그대로** 사용한다(별도 복원 API 없음).
- **수동 해소 시 `DE_IDENT_YN` 'F'→'Y' 복원(마킹 게이트 재개방)**: `DeidentReportService.resolveManually` 가 신고를 RESOLVED 전이 + 작업락 해제하면서 `LS_DATA_RAW.DE_IDENT_YN` 을 `'F'`→`'Y'` 로 되돌려 비식별 완료를 전제로 하는 마킹 진입 게이트(`deIdntfYn=='Y'`)를 재개방한다. 복원하지 않으면 게이트가 영구 폐쇄되어 재마킹이 불가능해진다. 자동 배치 해소(`resolveOpenReports`)는 `DeidentifyStep` 이 `'Y'` 로 복원하지만 수동 경로에는 복원 주체가 없어 이 서비스가 직접 복원한다.
- **후기 배치 단계(`LS_DATA_RAW.DATA_STTS_CD`)는 되감지 않음 (정정 2026-08-05)**: `resolveManually` **본체**는 비식별 게이트(`DE_IDENT_YN`)만 재개방하고 배치 단계는 변경하지 않는다(라벨링 단계 신고·레거시 NULL 신고는 이 동작 그대로 — 검수 완료 영상이 마킹 대기로 역행하지 않는다). **예외는 마킹 단계 신고 하나**로, 위 「신고 단계 구분」의 재개 배선(`DeidentStageResumeService.resumeMarking`)이 **의도적으로** `MARKING_READY` 로 되감는다 — 애초에 `MARKING_READY` 에서만 접수되므로 대개 no-op 이며, 접수~해소 사이에 다른 경로가 상태를 옮겼을 때 재마킹 진입이 영구히 닫히지 않게 하는 fail-safe 다.
- **★신고 접수 대상 = 비파생 영상만 (2026-07-29 사용자 확정, 구속)**: 파생영상(증강 `AUGMENT` — 구 코드값 `WINTER`·`NIGHT`·`RAIN` 파생본 포함 · 해상도 `RESL_*`)에서는 신고를 **접수하지 않는다** — `POST /v1/labels/{srcSn}/deident-report` 가 **412 PRECONDITION_FAILED** 로 거부한다(`DeidentReportService.requireReportableVideo`). 파생 프레임은 원본 비식별 산출물의 복사·리스케일 사본인데, 재비식별은 외부 솔루션이 **원본 영상**을 다시 처리하는 방식뿐이라 **파생본 자체를 다시 비식별할 수단이 없다** — 접수해도 해소할 수 없는 신고(작업락 + `'F'` 고착)만 남는다. FE 는 파생영상에서 신고 버튼을 비활성화하므로 이 412 경로는 API 직접 호출·낡은 화면에서만 도달한다. **원본으로 유도하지 않는다**(원본 신고는 아래대로 파생에 아무 영향이 없고, 파생 배정 WORKER 는 원본 접근 권한도 없다).
- **★검수가 승인(APPROVED)된 영상은 신고를 접수하지 않는다 (R2, 2026-08-10 사용자 확정, 구속)**: 두 진입점 모두 **412 PRECONDITION_FAILED** 로 거부한다(`DeidentReportService.requireNotApprovedVideo`). 게이트는 두 진입점이 수렴하는 `doReport` **한 곳**에만 배선한다(진입점마다 배선하면 새는 것이 이 저장소의 반복 결함). **역할 무관**(REVIEWER 도 막힌다) — 인가 축이 아니라 대상 리소스의 상태에 대한 프리컨디션이다. 평가는 **작업락 409 검사보다 먼저** 한다: 잠금 여부에 따라 412/409 로 갈리면 응답이 잠금 상태 오라클이 된다(CWE-209). 문구는 *"검수가 완료된 영상은 비식별 누락을 신고할 수 없습니다."* 하나이며 처리 단계를 노출하지 않는다. FE(라벨링 화면)는 영상 상세 `reviewSttsCd` 로 **버튼을 미리 비활성 + 툴팁**으로 사유를 알리고, 412 안내 노출은 화면이 상태를 모를 때의 안전망으로 유지한다.
  - **★판정축 확대 — 지금 상태가 아니라 이력이다 (2026-08-11 사용자 확정, 구속 — 구 `ReviewApprovalGate.isApproved` 판정 폐기)**: `ReviewStateMachine`이 `APPROVED → PENDING`(WORKER 재검수 재제출)을 허용하므로, 지금 상태만 보는 `isApproved`는 재제출로 상태가 내려간 구간에서 그대로 뚫린다. 판정은 `ReviewApprovalGate.hasEverApproved`(승인 동결 스냅샷 존재 **OR** 승인 감사 존재, fail-closed OR)로 확대됐다 — 상세·소비처는 [12 §12.2.2](12-review-assignment.md). FE `reviewSttsCd`는 여전히 **다른 축**(현재 상태)이므로 재검수 재제출 구간에서도 신고 버튼을 계속 비활성화하려면 신설된 `VideoDetailResponse.everApproved`를 봐야 한다.
  - **부수효과 — 신고 시점 `TASK_MODIFIED` 발행 분기 소멸**: 구 동작은 승인 영상 신고 접수 시 `TASK_MODIFIED(META_UPDATED)` 를 발행했으나(사유: `DE_IDENT_YN` 이 `'F'` 로 바뀌니 관제가 재픽업), 접수 자체가 막혀 **도달 불가**가 되어 제거했다.
  - **★거부해도 신고 사유는 감사 로그(WARN)로 남긴다 — 로그가 유일한 기록이다 (CWE-778)**: 승인 영상은 사용자가 취할 수 있는 조치가 **0** 이다 — 신고는 이 게이트가 412 로 막고(신고 행 `LS_DEIDENT_REPORT` 미생성 → REVIEWER 알림도 없다), 재비식별 요청은 `ApprovedRedeidentService.requestRedeident` 가 `DE_IDNTF_YN='Y'` 를 409 로 배제하며, 화면의 재비식별 버튼도 뜨지 않는다. 따라서 로그를 빼면 **사용자가 발견한 개인정보 노출 사실이 완전히 소실**된다. 파생영상 거부 경로(`requireReportableVideo`)와 **같은 관례**로 사유를 `LogSanitizer` 로 정제해(제어문자 제거 + 200자 절단, CWE-117) WARN 으로 기록한다 — 정제 함수를 새로 만들지 않는다. "쓰이지 않는 로깅"으로 보고 제거하지 말 것.
  - **⚠ `resolve` 경로는 건드리지 않는다**: 게이트 도입 **이전에** 승인 영상 위에 접수돼 아직 OPEN 인 신고가 실재한다. 그 해소까지 막으면 그 영상이 **작업락 + `DE_IDENT_YN='F'` 로 영구 고착**된다. `resolveManually` 의 승인 분기(재검토 표시 통지)는 그대로다 — "대칭"을 이유로 함께 막지 말 것.
- **★신고 게이트 판정 범위 = 자기 `rawSn` 행 하나 (2026-07-29 사용자 확정, 구속)**: 판정 단일 원천은 `video/service/DeidentReportGate` 이며, **자기 행의 `DE_IDENT_YN='F'` 만** 본다 — `ORGNL_RAW_SN` 을 **보지 않는다(조상·자손 전파 없음)**. 잠금 판정(`isUnderDeidentReportLocked`)도 자기 행 하나만 `SELECT … FOR UPDATE` 하므로 잠금 순서를 맞출 필요가 없다(교착 위험 없음). 복구 발행·스트림 메타 캐시 무효화도 자기 `rawSn` 단건이다.
  - **★이 정책의 함의(감추지 않음)**: **부모 신고는 파생영상에 영향을 주지 않는다.** 부모의 마스킹 실패 픽셀은 그 시점에 복사된 파생본에도 남아 있지만 **파생본은 계속 서빙·산출된다.** 파생본은 재비식별 수단이 없어 차단해도 해소할 방법이 없으므로 **사용자가 인지하고 감수하기로 한 확정 사항**이다(파생은 독립 취급). 신규 파생 생성은 **신고 여부로 막지 않고**(위 '파생 생성 축은 차단 범위 아님'), 스냅샷 이후 부모 비식별본이 **교체**되면 abort 하는 **복사 원자성 게이트**(procLog 경로 불일치 · 파일 mtime)로 방어한다 → [14 §14.3](14-augmentation.md) · [24](24-dataset-export.md).
  - **폐기된 안 — 조상/자손 전파 (2026-07-28~29 시도 후 철회)**: 부모 신고를 파생까지 전파하려고 ①조상(`ORGNL_RAW_SN`) 체인 순회 판정(깊이 상한 8 · 상한 초과 fail-closed) ②자손 방향 캐시 evict·복구 팬아웃 ③조상 → 자손 잠금 정준 순서를 넣었으나, **차단과 복구가 비대칭**(막는 조건과 푸는 조건이 어긋나 정상 트리가 영구 차단됨)이고 팬아웃 상한 초과 시 DoS·막다른 안내(파생 배정 WORKER 는 부모에 403)까지 연쇄 결함이 나와 **전량 철회**했다. **다시 시도하지 말 것** — 되살리려면 "파생본 재비식별 수단"부터 만들어야 한다.
- **차단 범위와 응답 코드(구현 실측)**:

  > ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 아래 표는 과거 9행이었으나 `LabelAccessGuard.requireNotUnderDeidentReport`/`DeidentReportGate` 호출처를 `grep -a` 로 전수 대조하니 **7개 경로가 누락**돼 있었다(라벨 객체 속성값·오토라벨·이벤트 어노테이션·검수 승인·영상 축 확정 저장·버전 회차 조회·포털 데이터마트 다운로드). 특히 **검수 승인(`ReviewService.approve`)이 빠져 있던 것**은 CLAUDE.md 구속 규칙("신고 구간 검수 승인 차단")과도 어긋나는 결손이었다. **이 표는 닫힌 집합이 아니다** — 판정 단일 원천은 `video/service/DeidentReportGate`(직접) 또는 `label/service/LabelAccessGuard#requireNotUnderDeidentReport`(위임)이며, 새 쓰기·조회 경로가 추가될 때마다 이 관용구를 재사용해 대상이 늘어난다. 아래는 2026-08-19 기준 확인된 대표 경로다(전수 재확인 없이 "표에 없으니 차단 안 된다"고 단정하지 말 것).

  | 대상 | 엔드포인트/경로 | 응답 |
  |------|----------------|:----:|
  | 라벨 조회·라벨 이력 | `GET /v1/frames/{srcSn}/labels`, `GET /v1/frames/{srcSn}/label-history` | 412 |
  | **라벨 저장(full-replace)** | `PUT /v1/frames/{srcSn}/labels` (`LabelService.bulkUpsert`) | **412** |
  | **영상 축 라벨 확정 저장** | `PUT /v1/videos/{rawSn}/labels`(`VideoLabelController`·`label/service/VideoLabelSaveTxService`) | **412** |
  | **라벨 객체 속성값** | `GET`·`PUT /v1/labels/{lblSn}/attrs`(`label/service/LabelAttrValueService`) | **412** |
  | **오토라벨 실행** | `POST /v1/frames/{srcSn}/autolabel`(`label/service/AutolabelOnlineService`) | **412** |
  | 개인정보 메타 저장 | `PUT /v1/videos/{rawSn}/privacy-meta`, `PUT /v1/frames/{srcSn}/privacy-meta`, `PUT /v1/frames/privacy-meta`(벌크) | 412 |
  | 버전 diff·롤백 | `VersionService.diff` / `rollback` | 412 |
  | **버전 회차 불러오기** | `GET /v1/videos/{rawSn}/versions/{version}/labels`(`version/service/StartVersionService.loadVersionLabels`, API-195) | **412** — 회차 목록 조회 `GET /v1/videos/{rawSn}/versions`(`listVideoVersions`)은 본문(라벨)을 내려주지 않아 게이트 대상이 **아니다** |
  | 프레임 이미지 | `GET /v1/frames/{srcSn}/image`, `GET /v1/frames/{srcSn}/deid-image`, `GET /v1/videos/{rawSn}/frames/{frameNo}/image` | 412 |
  | 포털 | `GET /v1/portal/frames/{srcSn}/labels`, `GET /v1/portal/frames/{srcSn}/image` | 412 |
  | **포털 데이터마트 다운로드** | `GET /v1/portal/datamart/videos/{rawSn}/download`(`portal/service/PortalDatamartDownloadTxService`) | **412** |
  | 관제 조회 API(라벨 본문) | `TaskQueryController` 라벨 조회 | 412 |
  | **이벤트 어노테이션 저장·승인·반려** | `PUT /v1/videos/{rawSn}/event-annotation`, `POST .../event-annotation/approve`, `POST .../event-annotation/reject`(`evntanno/service/EvntAnnoService`·`EvntAnnoReviewService`) | **412** — 조회(`GET`)는 열려 있다 |
  | **검수 승인** | `POST /v1/reviews/{videoId}/approve`(`review/service/ReviewService.approve`) | **412** |
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
- **★신고 접수 후 FE 라벨 캐시는 무효화가 아니라 *제거* 한다 (2026-08-05 사용자 확정, CWE-359)** — 위 `no-store` 가 **미디어 HTTP 캐시**를 닫은 것과 같은 취지를, **라벨 JSON 의 클라이언트 쿼리 캐시**에 적용한 것이다. 라벨 좌표는 PII **위치 특정** 정보라 프레임 이미지와 같은 등급으로 다룬다.
  - 라벨링 화면의 신고 성공 후처리(`LabelingPage.handleDeidentReportSuccess`)는 `queryClient.removeQueries({ queryKey: LABEL_KEYS.byVideo(srcSn) })` 로 **캐시 항목 자체를 버린다**(`srcSn` 미상이면 `LABEL_KEYS.all`). **구 구현 `invalidateQueries` 는 폐기.**
  - **왜 무효화로는 부족한가**: `useLabels` 는 `staleTime 30s` + `refetchOnWindowFocus:false` + `gcTime` 기본 5분이다. invalidate 는 stale 표식만 붙이고 **데이터를 남기므로**, 신고 직후 화면을 벗어났다가 ①**30초 내 재진입** → 캐시가 즉시 렌더되고(재조회가 백그라운드로도 늦게 붙는다) ②**30초~5분** → 캐시를 먼저 그린 뒤 백그라운드 412 로 교체 — 두 경우 모두 **서버 신고 게이트(412)를 우회해** 라벨 좌표가 화면에 뜬다. 게다가 잠금 표시(`reportedLock`)는 컴포넌트 상태라 재마운트로 초기화돼 **배너조차 없다**.
  - **되돌리지 말 것** — 활성(마운트 중) 화면에서는 remove/invalidate 결과가 같아 "동등하다"고 오판하기 쉽다. 차이는 **재진입 동선에서만** 드러나며, 회귀 가드도 거기에 있다([H TC-FE-315](../test-cases/H-frontend-e2e.md)). ⚠ 그 테스트는 `gcTime` 을 프로덕션 기본(5분)으로 둔 전용 QueryClient 를 쓴다 — 공용 테스트 클라이언트는 `gcTime:0` 이라 unmount 즉시 GC 되어 **가드가 아무것도 지키지 못한다**.
  - 마킹 화면은 신고 성공 시 목록으로 이동(`navigate('/task')`)하며 라벨 캐시를 보유하지 않아 대상이 아니고, 프레임 이미지 blob(`authImageStore`)은 참조 카운트 0 시 즉시 `revoke` 하는 **공유 참조**(영속 저장 없음)라 재진입 시 반드시 서버를 다시 호출한다.
- **비식별 프레임 경로 검증은 단일 판정기 + NOFOLLOW open (CWE-59/367/22/359)** — 비식별 프레임을 서빙·산출하는 경로는 모두 `StorageSubtreePolicy.verifyDeidentifiedFile` 하나로 판정하고, **판정에 쓴 실경로(`toRealPath`)를 그대로** `LinkOption.NOFOLLOW_LINKS` 로 연다(`FrameImageService.openNoFollow` — 구현 1벌 공용).

  | 경로 | 구현 | 성격 |
  |------|------|------|
  | `GET /v1/frames/{srcSn}/image` · `/deid-image` · `/v1/videos/{rawSn}/frames/{frameNo}/image` | `FrameImageService` | 내부 |
  | `GET /v1/portal/frames/{srcSn}/image` | `PortalLabelService.serveFrameImage` | **외부(포털)** |
  | `GET /v1/portal/datamart/videos/{rawSn}/download`(ZIP 내 프레임) | `PortalDatamartDownloadService`(2026-08-19 확인 — 구 표에서 누락) | **외부(포털)** |
  | 검수 승인 export | `FrameSource` | 파일 산출 |

  운영 형상이 `STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH`(=`/nas-storage`, 의도된 동일 설정)라 `startsWith(deidBase)` 만 보는 lexical 검사는 `frames/raw/**`(마스킹 전 원본)까지 통과시키고(fail-open), `frames/deid/{rawSn}/f.jpg → ../../raw/{rawSn}/f.jpg` 심링크는 `Files.exists`/`Files.size`/`FileSystemResource` 가 모두 **따라가** 원본 픽셀을 "비식별본"으로 200 서빙한다. 그래서 ①서브트리 판정을 **실경로**에 적용하고 ②판정~open 사이 교체(TOCTOU)까지 NOFOLLOW 로 fail-closed 처리한다. 포털 경로는 **외부 채널**인데 이 정합에서 마지막까지 lexical 검증(`resolveSafe`)으로 남아 있던 것을 **2026-07-30 보정**했다(응답 계약은 불변 — 파일 부재 404 / base 이탈·서브트리 밖 403, 내부 경로·예외 원인 미노출). 포털 **업로드 자산**(`PortalUploadService`)은 본인 업로드분이라 이 대상이 아니다.
- **신규 API `GET /v1/frames/{srcSn}/deid-image`**: 프레임의 **비식별 이미지 전용** 서빙(`DE_IDNTF_SRC_FILE_PATH_NM`). 해상도 파생 프레임은 원본 픽셀이 실재하지 않아 `SRC_FILE_PATH_NM` 이 null 이므로 기존 `/image` 로는 조회되지 않는다. **원본 폴백 없음** — 비식별 경로가 없거나 파일이 없으면 404. 응답 200 / 401 / 403(미배정·경로 위반) / 404 / 412(신고 구간). 인가(`LabelAccessGuard`) → 신고 게이트 → 경로 검증(심링크·경로순회 차단) 순서로 평가한다. **2026-07-28 백엔드 신설 — FE 연동은 후속**.
- **해소(resolve) 시 재검토 표시**: `'F'→'Y'` 복원으로 위 게이트가 전부 자동 해제되고, 검수 승인(APPROVED) 영상이면 `TaskModifiedEvent`(`META_UPDATED` + `exportRegenerated=true` + `needsRecheck=true`)가 발행되어 ① 재검토여부(`REVLT_YN='Y'`)를 세우고 ② 변경분을 축적한다. **즉시 재산출·재통지가 아니다** — 새 버전 전량 재생성과 `TASK_MODIFIED` 통지는 REVIEWER 가 **재승인해 표시를 지운 뒤** 다음 flush tick 에 나간다(재생성·통지 트리거는 **검수 승인 한 곳**). ⚠ **구 동작 폐기** — `DeidentReportResolvedEvent` → `DatasetExportBridge` 로 즉시 재산출하던 경로는 발행처가 사라져 **휴면**이며, 클래스·수신 배선만 확장점으로 존치한다. 신고 구간 export 는 `LS_DATASET_EXPORT` 행을 남기지 않아 실패 회수기(FAILED 행 스캔)가 집지 못하므로 **이 재검토 표시가 유일한 복구 경로**다. **복구 범위는 해제된 영상 하나뿐**이다 — 어떤 신고가 막는 노드는 정확히 그 신고된 영상 하나이므로(위 판정 범위) **자손 팬아웃·상한·"다른 조상이 아직 신고 중인가" 판정이 모두 불필요**하다. 함께 발행되는 `DeidentGateReopenedEvent` 는 승인 여부와 무관하게 항상 발행되어 보류됐던 파이프라인 작업(특히 **VLM 시계열 위탁**)을 재개시킨다.
- **수동 해소 시 비식별 산출물 검증 게이트(CWE-359, fail-closed)**: `resolveManually` 는 `'F'`→`'Y'` 복원 전에 **해소에 쓸 산출물**이 실제로 유효한 비식별 영상인지 확인한다 — 무결성(`DeidentArtifactIntegrity` 단일 판정기: 정규 파일 + 크기 하한 + 컨테이너 시그니처) **하나뿐**이다. 통과하지 못하면 `409` 로 거부(내부 경로 미노출)하고 신고는 `OPEN`·작업락·`DE_IDENT_YN='F'` 를 유지한다 — 실제 외부 비식별 없이 마킹 게이트/스트리밍이 재개방되어 PII 가 재노출되는 것을 차단한다. 경로는 DB 적재값·서버 열거 결과만 사용한다(사용자 입력으로 경로를 조립하지 않는다 — Path Manipulation 방지).

### ★해소는 "재비식별 산출물 선택" 절차다 (R3)

> ★★**후보 자격의 시간 조건은 폐기됐다 (2026-09-09 · `ADR-027` v4 · `API-202` v3, 구속)**
> 판정은 **무결성 하나**이며, **신고 이전부터 있던 산출물(지금 쓰고 있는 비식별 영상 포함)도
> 고를 수 있고 고르면 해소가 성립한다.** 「신고 이후에 만들어졌는가」는 **표시용 값으로만** 남아
> 화면이 「신고 이전 파일」을 구분해 보여주는 근거가 된다 — 선택 가능 여부를 좌우하지 않는다.
>
> **왜 풀었나**: 시간 조건은 외부 비식별 프로그램이 새 산출물을 내놓아야만 충족되는데, 그것을
> 만드는 경로가 저작도구 안에 없다(재비식별 창구는 검수완료 영상 전용이라 신고 영상에는 성립하지
> 않는다). 그래서 신고된 영상이 작업락 + `DE_IDENT_YN='F'` 로 묶인 채 **해소할 방법이 없었다.**
>
> ⚠ **인지·수용한 대가**: 재비식별을 실제로 하지 않고도 해소가 가능해진다 — 마스킹 누락이 남은
> 산출물이 정상으로 되돌아갈 수 있다. 화면의 「신고 이전 파일」 표시가 그것을 사람이 알아볼
> 유일한 단서이므로 **군더더기로 걷어내지 말 것.**

**왜 필요했나 — 이대로면 영영 해소되지 않는 신고가 있었다.** 구 판정은 원장(`LS_DEIDENT_PROC_LOG`)에 **기록된 경로 1개**만 보고 그 파일의 mtime 이 신고시각 이후인지로 "재비식별됐다"를 판단했다. 즉 외부 솔루션이 **같은 이름으로 제자리 덮어쓰기** 하는 것을 전제한다. 그런데 실제 외부 솔루션(KPST)은 **`{원본stem}-mask{ext}`** 처럼 다른 이름으로 산출하므로(위 §비식별 영상 파일명 규약과 같은 사실), 그런 경우 기록된 경로의 파일은 바뀌지 않아 **그 신고는 영원히 해소되지 않았다** — 작업락 + `DE_IDENT_YN='F'` 가 영구 고착되고 그 영상은 라벨 조회·스트리밍·export 가 모두 막힌 채 남는다.

→ **서버가 산출 디렉터리를 열거해 후보 목록을 만들고, 사람이 실제 재비식별 산출물을 고른다.**

| 항목 | 규약 |
|---|---|
| 후보 조회 | **`GET /v1/deident-reports/{rprtSn}/deident-candidates`** — 응답 항목은 `fileName`·`sizeBytes`·`modifiedAt`·`eligible`·`current`. 인가는 해소와 **동일**(WORKER 본인 배정 / REVIEWER 전체), 신고 없음 404. 디렉터리가 없거나 비면 **빈 목록 + 200**(에러 아님 — 아직 외부 비식별을 하지 않은 정상 상태) |
| 열거 대상 | 비식별 **영상** 디렉터리 — 현 전략의 산출 위치 + 읽기 허용 2-way(구 위치 `{deid_base}/videos/{rawSn}` · co-locate `dirname(원본)/{rawSn}/deid`). 판정 축은 `VideoArtifactRootResolver` 한 곳이며 여기서 복제하지 않는다. 디렉터리 바로 아래 **정규 파일만**(재귀 금지 · 심링크 제외), 파일명 오름차순. **상한은 둘이다**(CWE-770) — ①**결과 후보 수 200건**(디렉터리를 넘나들며 누적) ②**디렉터리 1개당 스캔 항목 1,000건**(열거 자체를 여기서 끊는다). ②가 없으면 ①은 **선언만 하고 걸리지 않는다** — 항목을 전량 적재·정렬한 *뒤* 잘라서 메모리·syscall·정렬 비용이 디렉터리 크기에 비례했다(2026-08-10 정정). 어느 쪽이든 절단되면 **WARN**(응답에 알릴 필드가 없어 로그가 유일한 관측 수단 — 조용한 절단 금지, 로그에 내부 경로 미노출). ⚠ **②가 걸리면 이름순 정렬은 「스캔 창 안에서만」 성립**한다(창에 담기는 항목은 파일시스템 순서라 이름이 앞서는 파일이 빠질 수 있다). 단 **현재 원장 산출물은 열거와 무관하게 항상 후보**라 정상 해소 동선(제자리 덮어쓰기)은 절단과 무관하다 |
| 현재 산출물 | 원장이 가리키는 파일은 **열거 밖이어도 항상 후보**에 남고 `current=true` 로 표시된다 — 빠지면 "제자리 덮어쓰기"라는 정상 해소 동선이 막힌다. ⚠ **구 규약 폐기** — *「이 후보에 한해 원장 완료시각 > 신고시각(신고 후 자동 재비식별 성공)도 자격 근거가 된다」* 는 **더 이상 필요 없다.** 시간 조건 자체가 사라져 이 후보도 다른 후보와 같은 기준(무결성)으로 판정된다 |
| 해소 요청 | `POST /v1/deident-reports/{rprtSn}/resolve` 바디 **`{"fileName": "..."}` 필수**. 누락·공백이면 **400** — **서버가 기본값을 고르지 않는다**(어느 파일이 결과인지 서버는 알 수 없다) |
| 수락 판정 | **목록 대조로만.** 요청 시점에 조회와 **같은 열거 코드**를 다시 돌려 그 결과에 이름이 있을 때만 수락한다 — 목록이 곧 허용목록이다. 없으면 **400**. 목록 키는 언제나 basename 이라 `../…`·절대경로·구분자가 섞인 입력은 **어떤 항목과도 일치할 수 없다**(선택값으로 경로를 조립하지 않는다, CWE-22) |
| 자격 미달 | 목록에는 있으나 **무결성**을 통과하지 못하면 **409**(기존과 같은 행위 중립 메시지, 내부 경로 미노출). ⚠ 「시간조건」은 폐기됐다 |
| 응답 노출 | **내부 저장 경로를 응답에 담지 않는다**(파일명만) — 이 목록은 WORKER 도 조회하므로 디렉터리·마운트 구조가 새면 안 된다(CWE-209, `deidentNotVerified` 가 경로를 감추는 것과 같은 축) |

- **★성공 시 원장에 새 SUCCESS 행을 INSERT 한다** — 해소 이후의 **프레임 재추출**(`DeidentFrameAttacher`)·**영상 스트리밍**(`VideoStreamService`)이 전부 `DE_IDNTF_FILE_PATH_NM` 을 읽으므로, 다른 이름의 새 산출물을 골라도 원장을 갱신하지 않으면 **하류가 옛 파일을 계속 쓴다**(선택이 반쪽이 된다). **UPDATE 가 아니라 INSERT** 인 이유는 이력 보존 + `findLatestSuccessByDataRawSn`(`REQ_DT DESC, PROC_LOG_SN DESC`)가 자연히 새 행을 집기 때문이다. 적재는 **원자 클레임(`claimResolve`) 성공 이후**에만 일어난다(경쟁에서 진 노드가 행을 남기지 않는다). 원본 경로(`ORGNL_FILE_PATH_NM`, NOT NULL)는 영상 자신의 `RAW_FILE_PATH_NM`, 없으면 직전 성공 원장 값으로 폴백하고 둘 다 없으면 적재를 건너뛰고 WARN 한다(여기서 예외를 던지면 이미 클레임된 해소가 롤백돼 신고가 고착된다).
- **화면(SC-032 비식별 신고 관리)**: "해소 처리" 버튼은 곧바로 해소하지 않고 **후보 선택 모달**을 연다. **기본 선택 없음**이며 고르기 전에는 확인 버튼이 비활성이다(서버도 선택값이 없으면 400 — 어느 쪽도 대신 고르지 않는다). 각 후보에 **파일명 + 크기 + 수정시각**을 보여주고(어느 것이 새 산출물인지 판단할 근거), **현재 사용 중** 산출물을 표시로 구분하며, `eligible=false` 인 후보는 **선택할 수 없게** 한다(서버가 어차피 409 로 거부하므로 왕복 없이 사유를 알린다). 그 안내는 **무결성 기준**이다 — *「정상적으로 열리는 영상 파일이 아니라 선택할 수 없습니다」*. ⚠ 구 문구 *「신고 이후에 만들어진 정상 영상 파일이 아니라…」* 는 폐기다. 그리고 **신고 접수보다 먼저 만들어진 후보에는 「신고 이전 파일」 구분 표시**를 단다 — 그 표시는 **선택을 막지 않으며**, 재비식별 없이 해소했을 때의 위험을 사람이 알아볼 유일한 단서다. 후보 0건이면 확인을 비활성하고 "외부 솔루션으로 비식별을 완료한 뒤 다시 시도" 안내를 띄운다. **내부 저장 경로는 화면에도 표시하지 않는다.**
- **기존 계약은 무변경**: 인가(401/403) · 신고 없음 404 · 이미 처리 409 · 원자 클레임 · 작업락 해제 · `'F'→'Y'` 복원 · 스트림 메타 캐시 무효화 · 재개 이벤트(`DeidentGateReopenedEvent` · `DeidentStageResumeEvent` · 승인 영상 재검토 표시 통지) 모두 그대로다. **바뀐 것은 ①요청 바디가 생겼다 ②"파일이 실재하지 않는다"는 사유의 거부가 409 → 400 이 됐다**(실재하지 않으면 애초에 후보로 열거되지 않으므로 "존재하지 않는 대상을 가리킨 요청"이다. 409 는 "목록에는 있으나 자격 미달"에 남는다). 어느 쪽이든 fail-closed 는 동일하다 — 예외 전파 → 트랜잭션 롤백 → 신고 `OPEN`·작업락·`'F'` 유지.
- 코드: `label/service/DeidentArtifactCandidateFinder`(열거·수락 공용 단일 지점) · `label/service/DeidentReportService`(`listDeidentCandidates` · `resolveManually` · `selectArtifact` · `recordResolvedArtifact`) · `label/controller/DeidentReportController`(`deidentCandidates` · `resolve`) · `label/dto/DeidentCandidateResponse` · `label/dto/DeidentResolveRequest` · FE `features/deident/components/DeidentResolveDialog` · `features/deident/hooks/useDeidentReports`(`useDeidentCandidates`)

## 8.4-a 외부 산출물 이관 경로의 비식별 축

> ⚠ **구 제목 폐기(2026-09-01 코드 실측)** — *"(설계 확정, 코드 미착수)"* 는 이 절에 대해 **사실이 아니다.**
> 이 절이 이름을 부르는 그 창구가 **실재한다** — `transfer/controller/ImportDeidentCompleteController`
> (`POST /v1/videos/{rawSn}/deident-complete`, 클래스 수준 `hasRole('REVIEWER')`)이고, 그것을 부르는
> 화면도 `features/import/components/DeidentCompleteDialog.tsx` 로 실재한다. 그 제목을 근거로
> 「아직 만들 것」이라고 판단하면 이미 있는 것을 다시 만들게 된다.
> ⚠ **확인한 것은 그 두 파일의 실재뿐이다** — 아래 표·규약의 개별 동작을 코드와 대조한 것이 아니다.
> 이관 도메인 전체의 갈래별 구현 상태는 [25 머리말](25-external-import.md)이 정본이다.

외부에서 라벨링이 끝난 산출물을 가져오는 경로는 **적재가 비식별을 자동으로 시작시키지 않는다**(ADR-048).
가져올 때 지정한 값과 **영상 파일을 함께 주었는지**의 조합으로 갈린다 → 상세는 [25 §25.4](25-external-import.md).

| 지정 | 영상 파일 | 비식별 |
|---|---|---|
| 비식별 완료 | 있음 | 그 영상이 곧 비식별 영상 — 단계를 다시 밟지 않는다 |
| 원본 | 있음 | **저작도구가 비식별 단계를 태운다** |
| 원본 | 없음(프레임만) | 대상 영상이 없어 스스로 처리 못 한다 — **외부 비식별 산출물을 받아 기록하는 별도 행위**(`POST /v1/videos/{rawSn}/deident-complete`)로 해소 |

> 그 기록 행위는 **비식별 산출물이 실제로 존재하는지 확인한 뒤에만** 성립한다. 확인 없이 기록만 바꾸는 수단이 되면
> 처리되지 않은 산출물이 승인을 통과한다. 이 계약이 **비식별을 수행하지는 않는다.**

⚠ **누락 신고 동선은 이 경로에 쓸 수 없다** — 신고는 비식별을 한 번이라도 수행한 영상만 접수하고, 그 해소는
비식별 완료를 단정해 기록하므로 실제로 처리되지 않은 영상에 쓰면 사실과 달라진다.

### 8.4-a-1 이 기록의 진입점이 SC-032 에도 생겼다 (2026-09-01 사용자 확정 · `SCREEN-032` v24)

**그 기록 창구는 「검수자만 호출할 수 있다」인데, 그것을 부르는 유일한 자리가 관리자 전용 화면**
(SC-039 산출물 가져오기, `/admin/imports`)**에만 있었다.** 역할 계층은 `ROLE_ADMIN > ROLE_REVIEWER`
한 방향이라 **검수자는 그 화면에 들어가지 못한다** — 검수자가 검수 승인 보류를 만나도 그것을 풀 자리에
갈 수 없는 모순이었다.

⇒ **진입점을 양쪽에 둔다.** 관리자는 가져오기 화면에서 적재 직후에, **검수자는 이 문서가 다루는
비식별 신고 관리 화면(SC-032, `/manage/deident-reports`)에서** 보류를 만났을 때 기록한다.
**두 자리는 서로를 대신하는 것이 아니라 각자의 동선에서 같은 일을 한다** — 가져오기 화면의 자리는
**걷어내지 않는다**. 창구가 검수자 축이라 **관리자도 역할 계층으로 그대로 호출**되므로, 자리를 늘려도
창구의 인가는 바뀌지 않는다.

**SC-032 에 더해지는 것은 별도 구획 2개다 — 기존 신고 목록·상태 탭·해소 흐름은 그대로 두고 더하기만 했다.**

| 구획 | 하는 일 |
|---|---|
| **이관 보류 영상 목록** | 가져오기로 들어와 승인 보류가 선 영상을 최근순으로 — 가져온 시각·폴더명·영상 번호·승인 보류·프레임 수·실행자. 조달은 **이관 이력 목록 조회 창구**(검수자가 부를 수 있다). 보류가 선 행에만 기록 자리가 열린다 |
| **비식별 완료 기록 다이얼로그** | 외부에서 이미 비식별한 산출물이 놓인 **폴더 경로를 사람이 적어** 기록. 결과로 승인 보류 해제 여부 · 비식별 이미지를 채운 프레임 수 · **이름이 맞는 파일이 없어 비워 둔 프레임 수**를 알린다 |

- ★ **보류 값은 보류/없음/미상 세 갈래이고, 비어 있는 것을 「보류 아님」으로 단정하지 않는다** — 단정하면
  그 행의 기록 자리가 감춰져 **그 영상은 승인될 길을 잃는다.** 이 값은 **이관 진행 상태와 다른 축**이라
  「이관이 성공했는가」로 대신 판단하지 않는다.
- ★ **기록은 산출물이 실제로 존재하는지 확인한 뒤에만 성립하고, 대응은 파일 이름으로 한다** — 순서나
  개수로 짐작해 잇지 않는다(짐작으로 이으면 다른 프레임의 비식별 이미지가 붙고 되돌릴 수 없다).
  **한 건도 잇지 못하면 아무것도 기록하지 않고 거부**하며 **승인 보류는 그대로 남는다.**
  비워 둔 프레임이 하나라도 있으면 그만큼이 **비식별 이미지 없이 학습데이터 산출물로 나가므로** 알린다.
- ★★ **두 목록을 한 표로 합치지 않는다.** 근거는 넷이며 어느 하나만으로 정한 것이 아니다.

  | 축 | 신고 목록 | 이관 보류 목록 |
  |---|---|---|
  | 한 행의 정체 | 작업자가 접수한 **신고 건** | 외부에서 원본으로 들여온 **영상** |
  | 기존 상태 탭(미처리/처리완료) | 걸린다 | **걸리지 않는다** |
  | 해소가 푸는 것 | **작업락** — 마킹·라벨링이 재개된다 | **검수 승인 보류** 하나뿐 |
  | 입력 방식 | 서버가 열거한 **후보를 고른다** | 사람이 **폴더 경로를 적는다** |

- ⚠ **조회 실패와 「보류가 선 영상이 한 건도 없음」은 다른 안내로 가른다** — 한 문구로 묶으면 *없다*와
  *못 불러왔다*가 구분되지 않는다(같은 문서 §8.4 의 '미상' 표기 규칙과 같은 취지).
- ⚠ **이 두 구획은 아직 설계뿐이다 (2026-09-01 실측)** — SC-032 화면 코드
  (`pages/manage/DeidentReportListPage.tsx`)는 이관 쪽 무엇도 참조하지 않고, 기존 기록 자리를 이루는
  `features/import/` 를 **소비하는 화면은 `pages/manage/ImportPage.tsx` 하나뿐**이다. 반면 **기록 창구와
  가져오기 화면 쪽 진입점은 실재한다**(위 절 머리말).
  **「진입점이 두 자리」는 확정된 사양이지 지금 화면의 상태가 아니다.**
- 상세·계약은 [25 §25.5 · §25.7 · §25.8](25-external-import.md).

## 8.5 옵션 설정 (RQ-SFR-09-04)

REVIEWER 가 **시스템 설정 화면(`/manage/settings` → "비식별 옵션" 카드)** 에서 마스킹 옵션을 조정하면 **후속 위탁부터** 적용된다. 구 상태(코드 상수 고정, 운영자 조정 불가)는 폐기.

| 화면 항목 | 설정 키 | 타입 | 허용값 | 기본값 | 위탁 필드 |
|---|---|:--:|---|:--:|---|
| 마스킹 방식 | `kpst.deid.masking-type` | NUMBER | **{0, 2, 3}** = 0 색상 · 2 모자이크 · 3 블러 | 0 | `masking_type` |
| 마스킹 범위 | `kpst.deid.masking-range` | DECIMAL | **0.5 ~ 2.0**(영역 배율) | 1.0 | `masking_range` |
| 프레임 저장 여부 | `kpst.deid.db-save` | NUMBER | **{0, 1}** = 0 저장 안 함 · 1 저장 | 0 | `db_save` |

- **★마스킹 방식은 "범위"가 아니라 "허용값 목록"이다** — `1` 은 벤더 미할당이라 연속 범위가 아니다. `ConfigKeys.NUMBER_RANGE` 에 `[0,3]` 으로 등록하면 **1 이 통과**하므로 전용 맵 `ConfigKeys.NUMBER_ALLOWED_VALUES` 로 판정한다. ⚠ 두 검증 맵(`NUMBER_ALLOWED_VALUES`/`NUMBER_RANGE`/`DECIMAL_RANGE`) 어디에도 등록하지 않은 NUMBER·DECIMAL 키는 **파싱만 통과하면 무제한 허용**된다 — **등록 누락 = 무검증**이다(회귀 가드: `ConfigKeysTest(deidentOptionKeysAreActuallyValidated)`).
- **★`exp_quality`·`exp_format` 은 화면에 노출하지 않는다 — 벤더가 미지원이라고 회신**했기 때문이다. 다만 규격상 필수 필드라 위탁 요청에는 **기존 규격 기본값을 계속 싣는다**(제거하지 않는다). 설정 키로도 열지 않는다.
- **★`masking_range` 는 실수다(선행 결함 교정)** — 구 DTO 는 `int` 라 **0.5 가 0 으로 잘려 전송 자체가 불가능**했다. `KpstProjectRequest.maskingRange` 를 `double`(기본 `1.0`)로 교정했다. 목서버 스키마(`mock-server/app/schemas/deid.py`)도 같은 오해로 `int` 였고, 그대로 두면 저작도구가 0.5·1.5 로 위탁하는 순간 **로컬·dev 가 422 로 한 건도 완주하지 못하므로** `float` 로 완화했다(회귀 가드: `mock-server/tests/test_deid.py(test_masking_range가_실수여도_수락한다_구_int_스키마_폐기)`). ⚠ 목서버에는 상·하한을 두지 않는다 — 판정의 단일 원천은 저작도구 설정 검증이며 사본을 두면 두 번째 진실원이 된다.
- **★조회 실패는 위탁을 막지 않는다(fail-safe)** — 설정 조달은 **선커밋된 원장 뒤·외부 호출 직전**(`KpstDeidentService.buildProjectRequest`)에서 일어나므로 여기서 예외가 나가면 호출측이 원장을 `'F'` 로 종결해 **비식별 위탁 자체가 실패**한다. 그래서 조회 실패(키 없음·타입 불일치·파싱 실패)는 삼키고 `KpstProjectRequest.DEFAULT_*` 로 폴백하며 WARN 만 남긴다(설정값 원문은 로그에 싣지 않는다 — CWE-117).
- **★DB 수기 수정 방어(fail-closed 2중)** — 입구 검증(`SystemConfigService.update`)과 별개로, **읽어온 값도** 허용값·범위로 재확인해 벗어나면 기본값으로 폴백한다(`readAllowedInt`/`readRangedDouble`). 잘못된 코드값을 외부로 그대로 보내지 않는다. 화면도 같은 규칙으로 선택지에 없는 저장값을 기본값으로 정규화해 표시한다 — 화면이 보여주는 값과 실제 위탁값이 갈리지 않게 하기 위함이다.
- **★반영 지연(인지·수용)** — 설정 캐시 TTL 이 **60초**라 값을 바꾼 노드는 즉시 반영되지만 **2노드 Active-Active 의 다른 노드는 최대 60초 지연**된다.
- **★FE 는 dotted 키를 폼 필드 이름으로 쓰지 않는다** — react-hook-form 은 필드 이름의 점을 **중첩 객체 경로**로 해석하므로 `register('kpst.deid.masking-type')` 은 `{kpst:{deid:{…}}}` 가 되어 zod 스키마·`dirtyFields` 판정이 전부 어긋난다. 폼은 점 없는 별칭(`maskingType`/`maskingRange`/`dbSave`)을 쓰고 **전송 시점에만** 실제 dotted 키로 매핑한다(`DeidentConfigCard.onSubmit`). 다른 설정 카드는 전부 UPPER_SNAKE 키라 이 문제를 겪은 적이 없다.
- 시드: `V178__seed_kpst_deident_option_configs.sql`(멱등 `ON CONFLICT DO NOTHING`). **시드 기본값이 코드 상수와 동일**하므로 마이그레이션만으로 위탁 동작이 달라지지 않는다.
- 코드: `sysconfig/ConfigKeys`(키·허용값) · `sysconfig/service/SystemConfigService`(`validateNumberRange`) · `batch/service/KpstDeidentService`(`resolveMaskingOptions`) · `common/client/dto/KpstProjectRequest` · FE `features/sysconfig/components/DeidentConfigCard`

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

## 8.7 레거시 비식별 프레임 복구 (dev 전용 운영 도구)

프레임 추출기의 self-invocation 결함을 고치기 **이전**에 적재된 영상은 `LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM` 이 NULL 로 남아 있다. 프레임 이미지 API 는 비식별본만 서빙하므로(`PRVC`/`PSDO`) 경로가 없으면 **404** 가 되어 라벨링·검수 화면이 빈 화면이 된다. 코드는 이미 고쳐졌고 신규 영상은 정상이라, 남은 것은 **기존 NULL 행 복구** 하나다.

| 항목 | 내용 |
|------|------|
| 노출 게이팅 | **dev 전용·REVIEWER 전용** — `@Profile("!prd")` 빈 게이팅 + `SecurityConfig` `/v1/dev/**` → `hasRole('REVIEWER')` + 핸들러 `@PreAuthorize`. 배포 표식은 `DevProfileGuard` 가 별도 축으로 차단 |
| API | ① `GET /v1/dev/deident-frame-recovery/targets?rawSn=` = **dry-run**(대상·예상치만, 변경 없음) ② `POST /v1/dev/deident-frame-recovery/runs` (`{"rawSn":12}` 또는 본문 없음) = 실제 복구. 같은 URL 의 쿼리 파라미터 행위분기(`?dryRun=`) 금지 원칙에 따라 sub-resource 로 분리. **두 경로 모두 `rawSn` 지정 = 단건 / 생략 = 전체**(1회 `MAX_VIDEOS_PER_RUN`=50 상한, 잔여는 응답으로 알리고 재호출) |
| **복구 순서 (Critical)** | ① `VDO_FRM_NO` 복원 → ② **커밋** → ③ 비식별 프레임 재추출. `DeidentFrameAttacher` 는 `REQUIRES_NEW` 라 자기 트랜잭션에서 프레임 행을 다시 읽는다 — 복원이 커밋되기 전이면 `VDO_FRM_NO` 를 여전히 NULL 로 보고 **순번 폴백 없이 전 프레임을 skip** 해(의도된 fail-closed) 기능이 조용히 아무것도 하지 않는다 |
| `VDO_FRM_NO` 조달 | `LS_MARKING.MARK_CN` 의 `frameIndex` 배열을 **`FRM_NO` 오름차순 프레임에 순서대로 1:1 매핑**한다. 초기 추출이 `LsDataSrc.create(rawSn, i, mark.frameIndex(), …)` 로 두 값을 같은 순서로 적재하기 때문이며, **마킹 배열을 정렬하면 그 대응이 깨진다** |
| **개수 불일치 = fail-closed** | 프레임 수 ≠ 마킹 수면 어느 마킹이 어느 프레임인지 특정할 수 없다. 추측 매핑은 기존 라벨 좌표를 **엉뚱한 장면 위에** 얹으므로 그 영상을 건너뛰고 사유(`MARK_COUNT_MISMATCH`)를 응답에 담는다. 이미 값이 있는 행이 매핑 결과와 **충돌**하면(`VDO_FRM_NO_CONFLICT`) 매핑 자체를 신뢰할 수 없으므로 역시 건너뛴다 |
| **비식별 누락 신고 구간은 건너뛴다 (구속)** | 신고 구간(`DE_IDENT_YN='F'`) 영상은 **dry-run·실행 양쪽에서** 처리하지 않고 `SKIPPED`/`UNDER_DEIDENT_REPORT` 로 보고한다. 판정은 단일 원천 `DeidentReportGate.isUnderDeidentReport` 재사용이며 **마킹 파싱·파일 I/O 이전(진입부)** 에 평가한다. 근거 둘 — ①그 비식별본은 **마스킹 실패가 확인된** 영상이라, 거기서 프레임을 뽑아 `DE_IDNTF_SRC_FILE_PATH_NM` 을 채우면 그 값이 `V_COMPLETED_FRAME.DEIDENTIFIED_PATH` 로 관제에 노출된다 — 지금은 NULL 이라 관제가 아무것도 가져갈 수 없는데 이 복구가 **없던 노출을 새로 만든다**(CWE-359) ②해소(resolve) 재비식별과 시간이 겹치면 같은 출력 파일을 제자리 교체하므로 복구가 나중에 끝나면 **재비식별 이전(마스킹 실패) 프레임이 최종본으로 굳고**, 경로가 채워져 있어 어떤 재처리 트리거도 걸리지 않는다. 예외가 아니라 **건너뜀**이라 전체 모드에서 나머지 영상 처리는 계속된다. dry-run 도 같은 판정을 타므로 운영자가 대상 목록에서 미리 걸러 볼 수 있다(미리 안 걸러주면 «대상에 있다»고 믿고 실행한다) |
| **동시 실행 가드 (409)** | 같은 영상에 복구가 진행 중이면 `POST .../runs` 는 **409** 다(영상 단위 진행 중 클레임). 재추출은 `REQUIRES_NEW` 트랜잭션 **안에서** 프레임마다 ffmpeg 을 돌려 영상 1건 처리 내내 DB 커넥션 1개를 점유하므로, 재클릭·다중 탭이면 ffmpeg 프로세스와 커넥션 점유가 곱해져 HikariCP 고갈 → 앱 전체 5xx 로 간다(CWE-770). 파일 축도 같은 가드가 닫는다 — 두 실행이 겹치면 같은 출력 경로에 두 ffmpeg 이 동시에 write 해 **부분 기록된 JPEG** 이 확정될 수 있다(CWE-362). 대상 중 하나라도 진행 중이면 **아무것도 시작하지 않고** 거절하며(부분 실행 금지), 클레임은 예외 경로 포함 `finally` 로 해제한다. **dry-run 은 읽기 전용이라 이 가드를 타지 않는다**. ⚠ **노드-로컬 best-effort** — 2노드 Active-Active 에서는 노드 간 동시 실행을 막지 못한다(dev 전용 수동 도구이고 방어 대상이 «사람의 재클릭» 이라 DB 원장 클레임까지 두지 않았다) |
| 원본 폴백 금지 | 비식별 영상 경로는 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` **적재값만** 쓰고 조합·추측하지 않는다(파일명이 mock=`deidentified.mp4` / KPST=`{원본stem}-mask{ext}` 로 다르다). 경로 부재·경계 밖·파일 부재는 **건너뛴다** — 원본(마스킹 전) 영상으로 대체하지 않는다(CWE-359) |
| 경로 판정기 | **영상 축**이므로 `VideoArtifactRootResolver.readableDeidVideoBases`(허용 base) + `resolveRealPathUnder`(실경로 판정)를 **재사용**하고, 판정이 돌려준 실경로를 그대로 넘긴다. ⚠ 프레임(이미지) 판정기 `StorageSubtreePolicy.verifyDeidentifiedFile` 를 영상에 쓰면 co-locate 기본 형상이 전부 거부돼 기능이 죽는다 |
| **도메인 이벤트 미발행 (구속)** | 이 복구는 `LS_DATA_SRC` 의 경로·위치 컬럼만 채운다. `TaskModifiedEvent` 등 어떤 도메인 이벤트도 발행하지 않는다 — 발행하면 승인 영상의 export 가 새 버전으로 재생성되고 관제 재통지까지 나가, 「재생성·통지 트리거는 검수 승인 한 곳」 구속 정책을 위반한다. 재사용하는 `DeidentFrameAttacher` 도 이벤트 발행 배선이 없다 |
| 멱등 | 복원은 `WHERE VDO_FRM_NO IS NULL` 조건부 UPDATE(엔티티 dirty checking 이 아니라 native — 전 컬럼 UPDATE 로 다른 경로가 바꾼 컬럼을 되돌리는 lost update 회피), 재추출은 `refreshExisting=false`. 두 번째 호출은 대상이 사라져 `ALREADY_RECOVERED` 이거나 0건 |
| 실패 격리 | 영상 1건의 재추출 실패(해상도 불일치·원본 프레임 부재 등)는 그 영상의 트랜잭션만 롤백되고 `FAILED`/`ATTACH_FAILED` 로 보고된다. 나머지 영상 처리는 계속된다 |
| 정보 노출 | 로그·응답에 경로·파일명·PII 를 싣지 않는다 — 식별자(rawSn)·건수·**서버가 고른 사유 코드**만(요청값 미반사, CWE-209/359/79) |
| 코드 | `dev/controller/DeidentFrameRecoveryDevController`, `dev/service/DeidentFrameRecoveryService(processOne · claim)`, `dev/service/DeidentFrameNoBackfillTxService`, `video/service/DeidentReportGate(isUnderDeidentReport)`, `batch/repository/LsDataSrcRepository(restoreVideoFrameNo · findRawSnsMissingDeidFramePath · countRawSnsMissingDeidFramePath)` |

## 8.8 관련 데이터 (DB)

`LS_DEIDENT_REPORT`(누락 신고), `LS_DEIDENT_PROC_LOG`(처리 이력, `REQ_KND_CD` BATCH/REDEIDENT), `LS_DATA_RAW.DE_IDENT_YN`, `LS_AUTH_WORK_LOCK`(재진행 중 잠금, 동일영상 활성락 1건 UNIQUE). → [18](18-database.md).
