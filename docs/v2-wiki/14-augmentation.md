# 14. 데이터 증강 · 해상도 변경

> 출처: R1 RQ-SFR-07-01~03·06-03, R2 KLID-AT-UC-001/002/003/010, CLAUDE.md(증강=새 영상), 코드(`augment/`, `webhook/GenAiCallbackController`)
> 관련: [12 검수](12-review-assignment.md) · [19 외부 시스템](19-external-security-cvat.md)

화면: `KLID-AT-SC-022`(증강 요청 `/augment`, REVIEWER), `SC-023`(증강 결과 `/augment/result/:jobId`, REVIEWER). 코드: `augment/`(16 파일).

## 14.1 외부 증강 (WINTER/NIGHT/RAIN)

- **증강(생성) 본체는 외부 시스템 책임** — 저작도구는 위탁·결과 검수만
- **증강 AI는 이미지-to-이미지** — 영상(비디오)을 재생성하지 않는다. 증강 결과 영상은 원본(비식별) 영상 파일을 그대로 복사하고 프레임 이미지만 변환한다.
- 위탁 유형 3종: **WINTER / NIGHT / RAIN** (날씨·계절·시간). 해상도 변경(RESOLUTION)은 §14.3 내부 수행 — 단, 2026-07-21부터 처리 결과 자체는 증강과 동일하게 새 파생영상(RAW_SN)을 생성한다(외부 위탁 여부만 다름)
- **증강 요청 화면(SCR-AUG-001)은 통합 단일 선택 UI** — 처리 종류 카드 4개(겨울/야간/우천/해상도 변경)를 `radiogroup` 으로 **하나만** 선택하고, 대상 영상도 검수 완료(승인) 1건만 단일 선택한다(§14.6). BE 증강 요청 API 는 `types` enum allowlist(WINTER/NIGHT/RAIN)로 강제하며, RESOLUTION 은 증강 잡 경로가 아니라 저작도구 직접 수행 경로(§14.3)로 분기된다.

### 위탁 → 웹훅 → 새 영상 적재 (생성형 AI API 연동명세서 v1.1, 2026-07-27 계약 교체)

요청 시점에 **웹훅이 성립하도록 선행 상태를 먼저 만든다**. 요청 응답만 주고 끝내지 않고, 외부가 결과를 웹훅으로 push 하면 그 웹훅이 실제 새 영상을 적재하는 끝까지 닫힌 흐름이다.

```
[요청] REVIEWER → 대상 영상(검수완료) + 증강 유형 선택
  요청 tx: distinct(영상×종류) 건별로
    - idempotencyKey(AUG-<uuid>) 선발급  ※ job_id 는 외부가 202 로 발급한다
    - LS_DATA_AUG 를 키와 함께 PENDING 단일 INSERT (originAugSn)
    - AugmentRequestedItemEvent 발행
  ── 요청 tx COMMIT ──
  AugmentRequestBridge(@TransactionalEventListener AFTER_COMMIT):
    - ledger.recordIssued(키 allowlist 등록)   ← 고아 키 방지(커밋 후에만)
    - AugmentJobSubmitService: 비식별 프레임을 100장 단위로 분할해
        POST {외부}/api/genai/jobs 위탁 (청크마다 request_id = "{키}-{jobSeq}")
        · 위탁 직전 LS_DATA_AUG_JOB 선기록(RECEIVED) → 202 수신 후 OTSD_JOB_ID 적재
        · 같은 tx 에 LS_DATA_AUG_JOB_FILE 선기록 — 입력 순서(FILE_SEQ)↔프레임(SRC_SN) 대응
        ↓ 비동기
[웹훅] POST /api/v1/genai/callback  (무서명 — 명세서 v1.1 규격)
  수신: {request_id, job_id, status(RUNNING|SUCCEEDED|FAILED), progress, current_step,
         updated_at, results[](SUCCEEDED), error_code/error_message(FAILED)}
  HmacWebhookFilter 무서명 가드 → GenAiCallbackController → GenAiCallbackService.handle()
    - 미발급 request_id 401 · job_id 불일치 409 · 종결 job 재전송 200 멱등 흡수
    - output_file_path 는 허용 루트 하위인지 정규화 후 재검증(CWE-22) 뒤
      LS_DATA_AUG_JOB_FILE.RSLT_FILE_PATH_NM 에 순서대로 적재(버리지 않는다)
    - results 건수 ≠ 위탁 건수 → job FAILED(RESULT_COUNT_MISMATCH) = fail-closed
    - RUNNING → 진행 상태만 갱신(결과 처리 없음)
    - SUCCEEDED/FAILED → job 종결 후 **롤업**: 전 job 종결 시에만 증강 1건 확정
        · 전건 SUCCEEDED → §14.2 새 영상(ORGNL_RAW_SN/PENDING) 생성 + 프레임/라벨/메타 복사
        · 1건이라도 FAILED → **부분 실패 = 전체 실패(fail-closed)**, REJECTED 종결
```

- **웹훅 경로 단일 진실원**: `/v1/genai/callback` 은 `WebhookProtectedPaths.PATH_GENAI_CALLBACK` 한 곳에서만 정의하고, 요청측(`AugmentRequestService.CALLBACK_PATH`)·수신 컨트롤러·가드 등록이 이 상수를 참조한다(경로 드리프트 회귀 차단).
- **인증 = 무서명 3계층**: ①IP allowlist(`webhook.genai.allowed-ip-cidrs`, **미설정이면 전면 차단**) ②rate limit + 본문 1MB 상한 ③`request_id` 발급 게이트(`LS_DATA_AUG_JOB.IDMP_KEY` 에 있는 키만 처리). 구 계약(`/v1/aug/callback` + HMAC 서명)은 외부 실계약과 맞지 않아 제거됐다.
- **롤업 원자성**: job 행을 갱신하기 **전에** `LS_DATA_AUG` 를 `FOR UPDATE` 로 잠근다. 순서를 뒤집으면 동시 콜백 두 건이 서로의 미커밋 갱신을 못 봐서 롤업이 통째로 유실된다. 중복 확정은 `AugmentResultService` 의 PENDING 앵커가 한 번 더 막는다.
- **멱등**: 외부는 전송 실패 시 재시도하므로 같은 페이로드 중복 수신이 정상이다 — 종결된 job 의 재전송은 200 + `applied=false`.
- **고아 키 방지**: ledger 등록·외부 위탁은 요청 트랜잭션 안이 아니라 **AFTER_COMMIT** 에서만 수행 — 요청 롤백 시 aug 행도 멱등 키도 남지 않는다.

## 14.2 증강 = 새 영상

- 성공 시 **새 영상**(`RAW_SN`, `ORGNL_RAW_SN`=원본) 을 **PENDING** 으로 생성
- 영상 파일은 원본(비식별)을 그대로 복사하고 프레임 이미지만 변환(이미지-to-이미지)
- **프레임 픽셀 = 외부 산출물 반입(2026-07-28, Phase 7-D)**: 파생 프레임은 부모 영상에서 재추출하지 않고
  `results[].output_file_path` 를 `{deid_base}/frames/deid/{rawSn}/` 로 복사한다(구 ffmpeg 재추출은 부모와
  픽셀이 같은 사본 = 증강 효과 0 이었다). 반입 전 **허용루트·실재·해상도 동일**을 전건 검증하고 하나라도
  어긋나면 파생 RAW 를 FAILED 로 종결한다(부모 재추출 폴백 없음). 산출물은 비식별 프레임의 변환본이므로
  **비식별 경로 컬럼(`DE_IDNTF_SRC_FILE_PATH_NM`)에만 적재하고 원본 경로는 null**(해상도 파생과 동일한
  V133 정책 A)
- 원본 라벨/메타를 새 영상에 **매핑/복사** (해상도 동일 → 좌표 그대로, 라벨 무결성 RQ-SFR-07-02)
- 라벨 무결성 검증: `LabelIntegrityCalculator` (원본 대비 라벨 수·좌표·속성 보존)
- 코드: `augment/AugmentResultService`, `LS_DATA_AUG`/`LS_DATA_AUG_RVW`/`LS_DATA_AUG_LBL_MAP`

## 14.3 해상도 변경 (RQ-SFR-06-03, 내부 수행 — 2026-07-21 증강형 파생영상 → 2026-07-22 증강 저장모델 통합)

> **설계 반전 → 저장모델 통합 (feat/resolution-derivative-video)**: 구 "다운스케일 전용 + 이미지셋만 제공 + 새 영상 미생성 + 라벨 좌표 미제공" 정책을 폐기하고 **증강과 동일하게 새 파생영상(RAW_SN)을 생성**하는 방식으로 전환한 뒤(2026-07-21), 저장모델도 전용 테이블 없이 **증강 테이블(`LS_DATA_AUG` + `LS_DATA_AUG_LBL_MAP`)로 통합**했다(2026-07-22). 관제 연동 관점에서는 파생영상이 기존 RAW_SN 파이프라인/데이터마트 뷰(`V_COMPLETED_*`)를 그대로 타므로 뷰 스키마 변경은 불필요하다(데이터마트 뷰는 LS_RESOLUTION_* 미참조 → 변경 없음).

- **저작도구가 직접 수행**(외부 위탁 아님) — 표준 해상도 3종 고정 프리셋마다 원본 1건당 **새 파생영상(RAW_SN)** 생성, `ORGNL_RAW_SN`으로 원본 참조
- **비디오는 원본(비식별) 그대로 복사**(재인코딩 없음), **프레임 이미지셋만 목표 해상도로 리스케일**(`Java2DImageResizer`) — 이 두 원칙은 유지
- **업스케일(확대)도 허용** — 구 `targetH>=srcH` 400 거부 가드 제거. 원본과 동일 해상도인 프리셋만 스킵하고 나머지는 3종 전부 생성
- **라벨/이미지 좌표를 해상도 배율(scaleX=targetW/srcW, scaleY=targetH/srcH)로 재계산해 파생영상에 적재**(BBOX/POLYGON/세그멘테이션/키포인트 전 종류) — 구 '좌표 미제공' 폐기
- **저장모델은 증강과 완전 통합** — 파생 판별·라벨매핑을 위한 전용 테이블을 두지 않는다:
  - **판별자 = `LS_DATA_AUG.AUG_TYPE_CD` 값 `RESL_1080P`/`RESL_720P`/`RESL_480P`**(해상도 사업표준단어=RESL, 신규 컬럼 없음, VARCHAR(20) 유지)
  - **원본↔파생 라벨 매핑 = 기존 `LS_DATA_AUG_LBL_MAP.COORD_RECALC_YN/SCALE_X/SCALE_Y` 재사용**(신규 배율 컬럼 없음)
  - **중복 방지 = 부분 유니크 인덱스 `UK_LS_DATA_AUG_RESL (SRC_SN, AUG_TYPE_CD) WHERE AUG_TYPE_CD LIKE 'RESL_%'`(V124)** — 동일 (원본 대표프레임, 해상도 프리셋) 재요청/동시요청은 DB 레벨에서 직렬화되어 409
  - **구 전용 테이블 `LS_RESOLUTION_EXPORT`·`LS_RESOLUTION_LBL_MAP`은 폐기**(V125 백필 후 fail-closed DROP)
- **증강 이력 노출·집계 포함, 단 검수 차단** — 해상도 파생은 증강 이력(`GET /v1/augments`) 응답의 `resolutionTypes` 필드로 별도 노출되고 상태 집계/통계에 포함되나, 저작도구 내부 생성물이라 **accept/reject(검수 승인·반려)는 차단**(진입 시 400 — `AugmentReviewService.loadOrThrow` 가드). aug 상태 라이프사이클은 파생 생성과 일치한다: **예약 시 PENDING(생성 중) → finalize 성공 시 ACCEPTED(생성 완료)**, 실패 시 예약 aug 행 삭제
- 파생영상(RAW) 본체는 증강과 동일하게 **PENDING → 배정 → 검수** 파이프라인에 진입하고, 검수 승인 시 관제에 **별도 완료 통지(TASK_COMPLETED)** 가 발송된다(이 검수는 파생 영상 라벨 검수이며, 위 aug 행 상태와 무관)
- **화면: 증강 요청 화면(SCR-AUG-001)의 통합 단일 선택 UI에 흡수** — '해상도 변경' 카드 선택 시 타겟 해상도(1080P/720P/480P, 미지정 시 3종 전체) 선택 UI가 노출되고, 실행하면 `POST /v1/videos/{rawSn}/resolution` 으로 직접 호출되어 응답 `{derivatives:[{rawSn,goalResCd,targetW,targetH,status}]}` 목록이 화면에 inline 표시된다(네비게이션 없음). 1건 이상 생성 성공=201 / 전부 실패=500 / 대상 프리셋 전부 스킵=400. 증강 3종 실행은 잡 등록 후 결과화면(SC-023)으로 이동한다. (구 '영상 상세 화면 독립 해상도 export 섹션'은 폐지 — 컴포넌트 정리됨)
- **결과 조회(`GET /v1/augments/{jobId}/result`, SC-023)는 해상도 파생의 프레임 비교쌍을 반환**한다 — 좌=원본 비식별 프레임 / 우=파생 프레임(둘 다 `/v1/frames/{srcSn}/deid-image` 경로만 노출, 스토리지 경로 미노출), `(RAW_SN, FRM_NO)` 동등 조인 + 기본 12장 페이징. **외부 위탁 증강(WINTER/NIGHT/RAIN)은 여전히 빈 배열**(외부 SFR-07 연동 이후 제공). 파생↔프리셋 판별은 `VMS_CLIP_ID` 를 **중앙 파서 `video/util/AugTypeParser` 단일 원천**으로 해석하므로 실데이터 포맷 드리프트(`_RESL_RESL_480P_`·구형 `_RES_RES_480P_`)도 증강 이력 화면과 동일하게 인식된다. 코드: `augment/service/AugmentResultViewService`
- 코드: BE `video/service/{VideoResolutionService,ResolutionDerivativeService,ResolutionReservationPersister,ResolutionDerivativeFinalizer}`, 적재 대상 `LS_DATA_AUG`(AUG_TYPE_CD=RESL_*)+`LS_DATA_AUG_LBL_MAP`, 인덱스 `UK_LS_DATA_AUG_RESL`(V124), 구 테이블 DROP(V125). FE `pages/AugmentRequestPage`(submit 분기) + `features/video/hooks/useResolutionDerivative`

## 14.4 활용 여부 검수 (RQ-SFR-07-03, UC-010)

```
PENDING 증강 영상 (SCR-AUG-002)
  → REVIEWER 확인 → accept(PENDING→ACCEPTED) 또는 reject(사유 필수, PENDING→REJECTED)
  → ACCEPTED 만 기존 배정·검수 흐름으로 학습데이터 편입
```

- `POST /v1/augments/{id}/accept` · `/reject`, PENDING 외 상태 전이는 409
- `LS_DATA_AUG.AUG_PROC_STTS_CD`: PENDING / ACCEPTED / REJECTED
- **해상도 파생(`AUG_TYPE_CD='RESL_*'`)은 검수 대상 아님** — 저작도구 내부 생성물이라 accept/reject 진입 자체가 400 으로 차단된다(`AugmentReviewService.loadOrThrow`). 이력·집계에는 포함되며 상태는 내부 라이프사이클(예약 PENDING → finalize ACCEPTED)로만 전이한다(§14.3)

## 14.5 관련 데이터 (DB)

`LS_DATA_AUG`(증강·상태 — 해상도 파생도 `AUG_TYPE_CD='RESL_*'` 로 통합 적재, 부분 유니크 인덱스 `UK_LS_DATA_AUG_RESL` V124), `LS_DATA_AUG_RVW`(검수·`LBL_INTGRT_PCT`·`REJECT_RSN`), `LS_DATA_AUG_LBL_MAP`(원본-증강/해상도 파생 공통 라벨 매핑·`COORD_RECALC_YN`/`SCALE_X`/`SCALE_Y`). 구 전용 테이블 `LS_RESOLUTION_EXPORT`·`LS_RESOLUTION_LBL_MAP`은 폐기(V125). → [18](18-database.md).

## 14.6 통합 단일 선택 UX (SCR-AUG-001)

증강 요청 화면(`/augment`, REVIEWER)은 처리 종류와 대상 영상을 **각각 1건만** 고르는 단일 선택 흐름이다.

1. **처리 종류 선택** — 카드 4개(겨울/야간/우천/해상도 변경)를 `radiogroup`(로빙 tabindex·화살표 탐색, WCAG 4.1.2)으로 하나만 선택. '해상도 변경' 선택 시에만 타겟 해상도(1080P/720P/480P) 선택 UI 노출. 종류를 바꾸면 타겟 해상도·해상도 결과가 초기화된다.
2. **대상 영상 선택** — 검수 완료(`DATA_STTS_CD=COMPLETED` + `RVW_STTS_CD=APPROVED`) 영상만 라디오로 1건 선택(검색·이벤트 필터·페이징, 페이지 이동 후에도 선택 보존).
3. **실행(submit) 시나리오 분기**:
   - 증강 3종(`isAugmentKind`) → `POST /v1/augments/request`(videoIds·types 길이 1 배열) → 성공 시 토스트 + 결과화면(`/augment/result/{jobId}`) 네비게이션.
   - 해상도 변경 → `POST /v1/videos/{rawSn}/resolution`(presets 전달, 선택) → 성공 시 프리셋별 생성 결과 목록(`derivatives: [{rawSn, goalResCd, targetW, targetH, status}]`) inline 표시(업스케일 포함 정상 처리). 미검수/증강본/전부 스킵은 BE 400, 동일 (원본,해상도) 중복은 409, 전부 실패는 500 → 에러 메시지 노출.
4. **실행 버튼 비활성 조건**: 종류 미선택 · 영상 미선택 · (해상도 종류인데 타겟 해상도 미선택) · 처리 중(`isPending`).
5. **보안**: kind/preset 은 allowlist 상수(`PROCESS_KINDS`/`RESOLUTION_PRESETS`)로만 좁혀 임의 문자열 분기 차단, videoId 는 number, 라우트는 REVIEWER 가드.

## 14.7 로컬/운영 차이 — 자족 시뮬레이터 폐지 (2026-07-27)

`DevAugmentCallbackSimulator`(=저작도구가 스스로 성공 콜백을 만들어 자기 자신에게 POST 하던 dev 시뮬)는 **제거**했다. 본 프로그램이 외부 응답 없이 성공 결과를 만들어내면 연동이 실제로 성립하는지 검증할 수 없고, 그 상태가 운영까지 흘러간 이력이 있다.

| 구분 | 운영(dev/stg/prd) | 로컬 |
|------|------------------|------|
| `ExternalAugmentClient` 구현 | `NoopExternalAugmentClient`(외부 미연동 명시) | `HttpExternalAugmentClient` |
| 위탁 대상 | 외부 생성형 AI 시스템 | mock-server(`:9400`) |
| 콜백 주체 | 외부 시스템 | mock-server 가 `callback_url` 로 push |
| 콜백 상태 | RUNNING×3 → SUCCEEDED \| FAILED | 동일(목업이 계약대로 발사) |

- `authoring.augment.external.mode` — `http`(기본, 실제 위탁) / `noop`(외부 미연동 명시). 구 `dev` 값은 더 이상 어떤 빈도 활성화하지 않는다.
- 로컬 IP allowlist 는 `webhook.genai.allowed-ip-cidrs=0.0.0.0/0` 으로 **명시**한다 — 전면 허용을 의도했다는 사실이 설정값에 남아야 한다.

> 통합 검증: `AugmentCallbackFlowIntegrationTest`(웹훅→새 영상/프레임·라벨 복사/멱등/미발급 request_id 401), `GenAiCallbackRollupConcurrencyIT`(마지막 job 동시 콜백에도 롤업 정확히 1회), `WebhookPathBypassSecurityIT`(경로 변형 우회 차단), `GenAiCallbackServiceTest`(부분 실패 fail-closed·경로 순회 거부).
