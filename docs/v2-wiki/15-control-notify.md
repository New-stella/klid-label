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

**★ 통지는 export 산출이 끝난 뒤에만 발송한다(Phase 5C 확정)** — 산출이 먼저 끝나야 관제가 조회하는 `V_COMPLETED_VIDEO.EXPORT_PATH_NM`(최신 SUCCEEDED)이 이번 승인/수정의 새 버전 폴더를 담는다. export 가 먼저 나가면 관제가 **구 버전 폴더**를 픽업한다([24 export](24-dataset-export.md) 참조). 승인/수정 경로는 트리거·리스너 구성이 다르다.

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

## 15.5 관련 데이터 (DB)

`LS_CONTROL_NOTIFY_FALLBACK`(통지 재시도 큐 + 발송 결과 상태 — `STTS_CD` 큐 처리 / `SEND_RSLT_CD` 성공·실패 관찰, V77), `LS_RAW_DATA_STATUS`(완료 상태). 페이로드: `TaskCompletedPayload`/`TaskModifiedPayload`. → [18](18-database.md).
