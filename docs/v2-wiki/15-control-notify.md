# 15. 관제서버 통지

> 출처: CLAUDE.md(완료/수정 통지, 관제서버 통지+조회 API), R2 KLID-AT-UC-009, 코드(`controlnotify/`)
> 관련: [12 검수](12-review-assignment.md) · [13 버전관리](13-version-control.md) · [19 외부 시스템](19-external-security-cvat.md)

코드: `controlnotify/`(18 파일). 저작도구 → 관제서버 **단방향 outbound 통지 + inbound 조회 API** (양방향 M2M deprecated).

## 15.1 통지 종류

| 통지 | 트리거 | 페이로드 |
|------|--------|----------|
| **TASK_COMPLETED** | `LS_RAW_DATA_STATUS.DATA_STTS_CD` → APPROVED 전이 | 이벤트 타입 + 작업 ID(RAW_SN) + 영상 메타(파일명·길이·채널) + 검수 완료 일시 + 프레임 개수 + 결과 요약 카운트(라벨 N·메타 M) + 요청 ID. **본문 미포함** |
| **TASK_MODIFIED** | 검수 완료 후 라벨/메타 수정 | 이벤트 타입 + 작업 ID + 마지막 수정 일시 + **변경 프레임 목록**(SRC_SN + 변경 종류 LABEL_ADDED/UPDATED/DELETED·META_UPDATED) + 변경 요약 카운트 + 요청 ID. **본문 미포함** |

- **통지 단위 = 영상 1건** (라벨/이미지 1장 단위 아님)
- 동일 작업 ID 유지, 버전 업 아님 — 수신측은 마지막 상태로 갱신
- 페이로드에 **PII·토큰·원본 비-비식별 이미지 포함 금지**

## 15.2 발송 흐름

**★ 통지는 export 산출이 끝난 뒤에만 발송한다(Phase 5C 확정)** — 산출이 먼저 끝나야 관제가 조회하는 `V_COMPLETED_VIDEO.OUTPUT_PATH_NM`(V174 개명 — 구 `EXPORT_PATH_NM`. 최신 SUCCEEDED/PARTIAL — V160)이 이번 승인/수정의 새 버전 폴더를 담는다. export 가 먼저 나가면 관제가 **구 버전 폴더**를 픽업한다([24 export](24-dataset-export.md) 참조). 승인/수정 경로는 트리거·리스너 구성이 다르다.

**★ 통지 판정 기준은 "예외 없음"이 아니라 export 종결 결과다(D-ISSUE-61 — 2026-08-02 확정, 구속)** — `DatasetExportService.export` 는 **예외를 던지지 않고 실패로 마감하는 경로가 4종**이다(`NO_INPUT`·산출 base 거부·버전 채번 소진·산출물 0건). 구 구현은 러너가 "예외 없음 = 성공"으로 판정해 이 4경로에서도 `TASK_COMPLETED`/`TASK_MODIFIED` 를 발송했고, 관제는 통지를 받고 뷰를 조회했을 때 **최초 승인 실패면 경로를 못 찾고, 재승인 실패면 구 버전 폴더를 최신으로 오인**했다(실측 rawSn=72 — export FAILED 직후 TASK_COMPLETED, 뷰 0행). 이제 `export()` 가 `DatasetExportOutcome` 을 반환하고 `AsyncDatasetExportRunner` 가 `notifiable()` **단일 판정**으로 통지 여부를 정한다.

| outcome | 통지 | 근거 |
|---------|:----:|------|
| `COMPLETED` | O | 이번 실행이 산출물을 만들고 SUCCEEDED 마감 |
| `PARTIAL` | O | 일부 산출이지만 **뷰에도 노출**된다(V160) — 뷰·통지·멱등 baseline 3판정 일치 |
| `IDEMPOTENT_SKIP` | O | 재산출을 생략했을 뿐 직전 SUCCEEDED/PARTIAL 산출물이 곧 최신 |
| `NO_INPUT` / `FAILED` / `VERSION_EXHAUSTED` | **X** | 이번 실행의 산출물이 없다 — 보류(회수기가 재산출 성공 후 재개) |
| `DEIDENT_BLOCKED` | **X** | 신고 구간 정책 보류(예외로 이탈하나 판정 누락 대비 fail-closed 명시) |

> 보류는 **유실이 아니라 지연**이다 — `DatasetExportFailureRecoverer` 가 재산출에 성공하면 `runApprovalAsync` 가 완료 이벤트를 재발행해 통지가 재개된다.

### TASK_COMPLETED (검수 승인)

```
검수 승인 커밋 → ReviewApprovedEvent(AFTER_COMMIT)
  → DatasetExportBridge (항상 활성 — dataset-export.enabled 토글, control-notify 와 무관)
  → AsyncDatasetExportRunner.runApprovalAsync (force=true 전량 재생성)
  → export 성공(SUCCEEDED)   → DatasetExportCompletedEvent 발행
       └→ ControlNotifyEventListener (control-notify.enabled 토글 종속) → ControlNotifyService.sendCompleted
  → export 실패              → 통지 미발행(보류). LS_DATASET_EXPORT 에 FAILED 행만 남고
                                DatasetExportFailureRecoverer 가 재시도 → 성공 시 같은 완료 이벤트로 통지 재개
```

### TASK_MODIFIED (승인 후 라벨/촬영환경 등 수정)

```
승인 후 수정 커밋 → TaskModifiedEvent(exportRegenerated=?)(AFTER_COMMIT)
  → TaskModifiedAccumulateListener (항상 활성 — control-notify 토글과 무관)
  → ControlNotifyDebouncer.accumulate (짧은 시간 내 다수 변경 → rawSn 단위 윈도우에 축적, 기본 60초)
  → 전용 flush 스케줄러(기본 10초 tick, authoring.dataset-export.regen-flush.enabled) 만료 윈도우 flush:
      · exportRegenerated=true 인 윈도우 → AsyncDatasetExportRunner.runReExportThenNotify(force=true)
          → export 성공 시에만 통지 콜백(sendModified) 실행 — export 실패 시 통지 보류
          (재시도는 DatasetExportFailureRecoverer 가 FAILED 행을 회수해 재산출 성공 후 완료 이벤트로 재개)
      · exportRegenerated=false 인 윈도우(export 무관 메타) → 디스크 변경 없이 즉시 sendModified
  → ControlNotifyClient (idempotency + Resilience4j) → 관제서버 inbound SPI (비동기 push)
  → 발송 결과를 LS_CONTROL_NOTIFY_FALLBACK 에 적재:
      · 즉시 성공 → STTS_CD=SUCCEEDED + SEND_RSLT_CD=SUCCESS 터미널 행(재시도 큐 미진입, 관찰용)
      · 실패      → STTS_CD=PENDING + SEND_RSLT_CD=FAILED → dead-letter/재등록 큐 (Quartz Job 재시도)
```

> **재export 트리거는 통지 토글과 독립적으로 동작한다**: `DatasetExportBridge`·`TaskModifiedAccumulateListener`·`ControlNotifyDebouncer` 는 모두 `authoring.control-notify.enabled` 와 무관하게 항상 활성이다(토글 off 인 dev/stg/prd 기본 형상 포함). `authoring.control-notify.enabled` 는 **통지 발송(`sendCompleted`/`sendModified`)만** 게이팅한다 — 토글이 꺼져 있어도 export 재생성(데이터마트 동기화)은 그대로 일어난다.

> **발송 상태 관찰(V77)**: `STTS_CD`(큐 처리 상태)와 `SEND_RSLT_CD`(발송 결과 SUCCESS/FAILED)를 분리해, 즉시 성공 발송도 DB로 관찰 가능. `SELECT SEND_RSLT_CD FROM LS_CONTROL_NOTIFY_FALLBACK WHERE RAW_SN=? AND IDMP_KEY=?` → `'SUCCESS'`. 성공 관찰 행은 `SUCCEEDED` 터미널이라 재시도 잡·depth 게이지(`STTS_CD IN PENDING,RETRYING`)에서 제외.

## 15.3 관제서버 조회 패턴

- 관제서버가 통지 수신 후 **저작도구 조회 API로 상세 데이터를 직접 가져가** 데이터마트 UPSERT
- `GET /v1/tasks/{rawSn}/summary | labels | meta` + `V_COMPLETED_*` View SELECT → [18](18-database.md)
- 코드: `controlnotify/TaskQueryController`, `TaskQueryService`

## 15.4 보안

- 양방향 M2M 인증 미운영 (deprecated) — 통지는 인계 토큰 또는 IP 화이트리스트로 보호
- 요청 ID idempotency로 중복 방지
- **인증 헤더 `x-access-token` 부착(D-ISSUE-62 — 2026-08-02)**: 관제 inbound SPI 계약(API-251/API-285)이 요구한다. `controlNotifyWebClient` 가 `authoring.control-notify.token`(환경변수 `CONTROL_NOTIFY_TOKEN`) 값을 `defaultHeader` 로 부착한다.
  - **값은 설정에서만 로드하며 코드·yml 평문 상수 금지**(CWE-798). 로그에 **토큰 값을 출력하지 않는다** — 존재/길이만(CWE-532). `LogMaskingPatterns` 가 헤더명을 마스킹 대상으로 이미 보유한다.
  - **빈 값이면 헤더를 붙이지 않는다** — 인증을 요구하지 않는 local 목 서버 연동 동작이 그대로 유지된다. `enabled=true` 인데 토큰이 비면 **기동 WARN** 으로 드러낸다(기동 차단은 하지 않는다 — 통지 실패는 폴백 큐로 회수되므로 앱 전체를 세울 사안이 아니다).
  - 평문 `http://` 엔드포인트에 토큰이 설정되면 **CWE-319 WARN**(값 미출력).
  - ⚠ 실제 헤더명·값 형식은 **관제팀 확정 필요**(UNCERTAINTIES #26). 로컬 목 서버는 인증을 검사하지 않으므로 **로컬 202 통과가 실환경 계약을 보증하지 못한다** — 미부착 상태로 실연동하면 전 통지가 401 로 거부되고 폴백 큐가 재시도 상한 소진 후 dead-letter 로 고착된다.

## 15.5 관련 데이터 (DB)

`LS_CONTROL_NOTIFY_FALLBACK`(통지 재시도 큐 + 발송 결과 상태 — `STTS_CD` 큐 처리 / `SEND_RSLT_CD` 성공·실패 관찰, V77), `LS_RAW_DATA_STATUS`(완료 상태). 페이로드: `TaskCompletedPayload`/`TaskModifiedPayload`. → [18](18-database.md).
