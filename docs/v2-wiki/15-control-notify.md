# 15. 관제서버 통지

> 출처: CLAUDE.md(완료/수정 통지, 관제서버 통지+조회 API), R2 KLID-AT-UC-009, 코드(`controlnotify/`)
> 관련: [12 검수](12-review-assignment.md) · [13 버전관리](13-version-control.md) · [19 외부 시스템](19-external-security-cvat.md)

코드: `controlnotify/`(18 파일). 저작도구 → 관제서버 **단방향 outbound 통지 + inbound 조회 API** (양방향 M2M deprecated).

## 15.1 통지 종류

| 통지 | 트리거 | 페이로드 |
|------|--------|----------|
| **TASK_COMPLETED** | 검수 승인 → export 산출 `SUCCEEDED` (`ReviewApprovedEvent` → `DatasetExportCompletedEvent`) | required `job_id`·`event_type_cd`·`evnt_cls_cd`·`evnt_ctgry_cd`·`lclgv_cd`·`lclgv_nm`·`duration_sec`·`image_count`·`gen_ai_yn` + 선택 **`output_ver_no`**. required 는 값이 `null` 이어도 **키를 남긴다**. **본문 미포함** |
| **TASK_MODIFIED** | 검수 완료 후 수정이 **재검수에서 승인**될 때 (`REVLT_YN='Y'` 표시 → 재승인이 해제 → 다음 flush tick) | `job_id` + **`changed_items`**`{images[]·jsons[]}`(산출 폴더의 실제 **파일명** `{FRM_NO 4자리 zero-pad}.jpg`/`.json`) + 선택 **`ver_expln`** + 선택 **`output_ver_no`**. **본문 미포함** |

> ⚠ **구 서술 폐기** — TASK_COMPLETED 의 `이벤트 타입`·`영상 메타(파일명·채널)`·`검수 완료 일시`·`결과 요약 카운트`·`요청 ID`, TASK_MODIFIED 의 `이벤트 타입`·`마지막 수정 일시`·`변경 프레임 목록(SRC_SN)`·`변경 종류`·`변경 요약 카운트`·`요청 ID` 는 **어느 것도 페이로드에 없다.** 요청 식별자는 바디가 아니라 폴백 큐 `IDMP_KEY` 로만 쓰이고, 변경 종류(`ChangeType`)는 디바운스 축적 키·감사 로그 전용이다. `@JsonInclude` 는 **필드 레벨로만** 건다 — 클래스 레벨에 걸면 required 까지 생략돼 통지가 전량 `422` 로 깨진다.

- **통지 단위 = 영상 1건** (라벨/이미지 1장 단위 아님)
- 동일 작업 ID 유지, 버전 업 아님 — 수신측은 마지막 상태로 갱신
- 페이로드에 **PII·토큰·원본 비-비식별 이미지 포함 금지**

### 15.1.1 필드 정합 (관제 계약 API-251 v17)

**완료 통지는 required 8 + optional 1 = 9필드**이며, 여기에 **선택 필드 `output_ver_no` 를 더해 최대 10필드**다.
구 형상은 6필드였고 그대로 발송하면 **전량 `422 VALIDATION_FAILED`** 로 거부됐다.

> ⚠ **위 §15.1 표의 "required 9" 와 여기의 "required 8 + optional 1" 은 층이 다르다 — 한쪽으로 통일하지 말 것.**
> 후자는 **관제 계약**이 규정한 필수 여부이고, 전자는 **우리 구현**이 실제로 하는 일이다 —
> 앞 9개는 관제가 `lclgv_nm` 을 optional 로 두었더라도 **값이 `null` 이어도 키를 남긴다.**
> 통일하면 어느 한쪽의 정확한 계약이 지워진다.
통지 토글을 켜기 전에 반드시 이 형상이어야 한다.

| 필드 | 필수 | 조달 |
|---|:--:|---|
| `evnt_cls_cd` | ✔ | **인입 `LS_DATA_INGEST.EVNT_CLSF_CD`** — LATERAL 조인(설계 D1) |
| `evnt_ctgry_cd` | ✔ | **인입 `LS_DATA_INGEST.EVNT_CTGRY_CD`** — LATERAL 조인 |
| `gen_ai_yn` | ✔ | **자기 `LS_DATA_RAW.SRC_TYPE IN ('GENERATED','AUGMENTED')`** — 인입 조인이 아니다 |
| `ver_expln`(수정 통지) | optional | `VersionExplanationPolicy` (판정 단일 원천, 복제 금지) |
| `output_ver_no`(양 통지) | optional | 최신 산출 `LS_DATASET_EXPORT.OUTPUT_VER_NO` (`ControlNotifyPayloadFactory.resolveOutputVerNo`) |

- ★ **`output_ver_no` 는 관제가 "어느 통지가 어느 산출 버전 폴더 `v{n}` 에 대응하는지" 를 알기 위한 선택 필드다** (2026-08-12 관제 요청 수용). **값이 없으면 키를 생략**하고 관제는 그것을 **"산출물 변경 없음 — 재픽업 불요"** 로 처리한다. 관제 수신 API 는 이 필드가 없는 통지도 정상 수신하므로 배포 순서 제약이 없다.
  - **조달 기준은 데이터마트 뷰와 같아야 한다** — `V_COMPLETED_VIDEO` 가 `OUTPUT_PATH_NM` 을 고르는 기준(`OUTPUT_STTS_CD IN ('SUCCEEDED','PARTIAL')` 중 `OUTPUT_VER_NO` 최대 1건, V174)과 **동일**하다. 어긋나면 통지가 가리키는 버전과 관제가 뷰에서 보는 폴더가 달라져 이 필드를 넣는 의미가 없다. **두 기준은 함께 바꾼다.**
  - **값의 유무는 `changed_items` 와 같은 축**(export 재생성 동반 여부)에서 갈린다 — 완료 통지(export 종결 후 발송)·전량 재생성 수정 통지는 싣고, 재생성 없는 수정 통지(촬영환경 메타 수정 등)는 싣지 않는다.
  - 산출 이력이 없으면 `null` 이며 **예외를 던지지 않는다** — 값 결손은 실패가 아니다(던지면 통지 전체가 폴백 큐로 밀린다).
- ⚠ **JSON 키는 관제 스펙명이며 우리 컬럼명이 아니다** — `evnt_cls_cd`(관제, `cls`) ≠ `EVNT_CLSF_CD`(우리 컬럼, `CLSF`). 임의로 맞추지 말 것.
- ⚠ **이벤트 2코드는 `LS_DATA_RAW` 에서 오지 않는다** — 그 테이블에 두 컬럼은 **없다**. 관제 읽기전용 사실을 가변 마스터로 복사하지 않는다는 확정 설계(D1)에 따라 인입에서 조인하며, 연결 규칙은 뷰와 같은 단일 진실원 **`IngestSourceLink`** 다(파생은 `ORGNL_RAW_SN` 1단계 폴백).
- ⚠ **null 처리 규약이 두 통지에서 정반대이며 의도된 비대칭이다** — 완료 통지의 3필드는 **required 라 값이 `null` 이어도 키를 남긴다**. 반대로 `ver_expln`·`output_ver_no` 는 **optional 이라 `null` 이면 키를 생략**한다(`@JsonInclude NON_NULL`) — 관제 `dataset_versions.ver_expln` 이 **NOT NULL** 이라 명시적 `null` 보다 미전송이 안전하고, `output_ver_no` 는 키 부재 자체가 "산출물 변경 없음" 이라는 신호이기 때문이다.
  - ⚠⚠ **그래서 `@JsonInclude(NON_NULL)` 은 반드시 <필드 레벨>이다** — 클래스 레벨에 걸면 required 필드까지 생략되어 **완료 통지가 전량 422 로 깨진다**. 회귀 가드: `TaskPayloadStructureTest(requiredFieldsKeepExplicitNullKeysAfterOptionalFieldAdded)`(mutation 실증 완료).
- `gen_ai_yn` 의 판정은 뷰 `V_COMPLETED_VIDEO.GEN_AI_YN`·동결 스냅샷과 **같은 헬퍼**(`LsDataRaw.genAiYn()`)를 공유한다 — 3경로가 어긋나지 않는다.

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
  → ReviewRecheckMarkListener(REQUIRES_NEW) → LS_RAW_DATA_STATUS.REVLT_YN='Y' (재검토 표시)
  → 표시가 서 있는 동안 만료 flush **보류** — 창이 만료되는 것만으로는 나가지 않는다
      (LsMonNotiAcmlRepository 의 flush 후보 SELECT 와 클레임 UPDATE **두 곳 모두**에
       NOT EXISTS(REVLT_YN='Y') — SELECT~UPDATE 창까지 폐쇄)
  → 검수자 재승인 → ReviewService.approve 가 clearNeedsRecheck()
      (재승인은 ReviewApprovedEvent 를 발행하지 않는다 — 중복 방지)
  → 그제서야 다음 flush tick 에 축적분이 풀린다.
    전용 flush 스케줄러(기본 10초 tick, authoring.dataset-export.regen-flush.enabled) 만료 윈도우 flush:
      · exportRegenerated=true 인 윈도우 → AsyncDatasetExportRunner.runReExportThenNotify(force=true)
          → export 성공 시에만 통지 콜백(sendModified) 실행 — export 실패 시 통지 보류
          (재시도는 DatasetExportFailureRecoverer 가 FAILED 행을 회수해 재산출 성공 후 완료 이벤트로 재개)
      · exportRegenerated=false 인 윈도우(export 무관 메타) → 디스크 변경 없이 즉시 sendModified
  → ControlNotifyClient (idempotency + Resilience4j) → 관제서버 inbound SPI (비동기 push)
  → 발송 결과를 LS_CONTROL_NOTIFY_FALLBACK 에 적재:
      · 즉시 성공 → STTS_CD=SUCCEEDED + SEND_RSLT_CD=SUCCESS 터미널 행(재시도 큐 미진입, 관찰용)
      · 실패      → STTS_CD=PENDING + SEND_RSLT_CD=FAILED → dead-letter/재등록 큐 (Quartz Job 재시도)
```

> ⚠ **재시도 잡 자체가 `authoring.control-notify.enabled=true` 로 명시 설정돼야만 등록된다(2026-08-19 코드 실측 보강)** — `ControlNotifyFallbackRetryJob`(5분 간격, 최초지연 60초)은 `@ConditionalOnProperty(name="authoring.control-notify.enabled", havingValue="true")` 이며 **`matchIfMissing` 이 없다**. 기본값은 `${CONTROL_NOTIFY_ENABLED:false}`(전 환경 공통 기본 false)이므로, 통지 토글이 꺼진 기본 형상에서는 **이 Quartz Job 자체가 스케줄러에 등록되지 않는다**. 단 이 토글이 꺼져 있으면 §15.2 흐름상 `sendCompleted`/`sendModified` 자체가 호출되지 않아 `LS_CONTROL_NOTIFY_FALLBACK` 에 애초에 쌓일 실패 행이 없으므로 실무 영향은 없다 — 토글을 **켠 뒤에만** "폴백 큐가 항상 재시도된다"고 서술할 것. 근거: `ControlNotifyFallbackRetryJob`, `application.yml`(`authoring.control-notify.enabled`).

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
