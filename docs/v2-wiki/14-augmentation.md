# 14. 데이터 증강 · 해상도 변경

> 출처: R1 RQ-SFR-07-01~03·06-03, R2 KLID-AT-UC-001/002/003/010, CLAUDE.md(증강=새 영상), 코드(`augment/`, `webhook/GenAiCallbackController`)
> 관련: [12 검수](12-review-assignment.md) · [19 외부 시스템](19-external-security-cvat.md)

화면: `KLID-AT-SC-022`(증강 요청 `/augment`, REVIEWER), `SC-023`(증강 결과 `/augment/result/:jobId`, REVIEWER). 코드: `augment/`(16 파일).

## 14.1 외부 증강 (WINTER/NIGHT/RAIN)

- **증강(생성) 본체는 외부 시스템 책임** — 저작도구는 위탁·결과 검수만
- **증강 AI는 이미지-to-이미지** — 영상(비디오)을 재생성하지 않는다. 증강 결과 영상은 원본(비식별) 영상 파일을 그대로 복사하고 프레임 이미지만 변환한다.
- 위탁 유형 3종: **WINTER / NIGHT / RAIN** (날씨·계절·시간). 해상도 변경(RESOLUTION)은 §14.3 내부 수행 — 단, 2026-07-21부터 처리 결과 자체는 증강과 동일하게 새 파생영상(RAW_SN)을 생성한다(외부 위탁 여부만 다름)
- **요청 시 생성 조건(prompt) 5필드 필수 (2026-07-31)** — REVIEWER 가 `time`/`season`/`weather`/`terrain`/`severity` 를 입력하면 명세서 v1.1 §4.1 `prompt`(자유 구조 dict)로 **가공 없이 그대로** 전송되고, 같은 값이 `LS_DATA_AUG.PROMPT_CN`(JSON 원문)에 보관된다. 구 구현의 "증강 유형별 서버 고정 문구"는 폐기. 값은 **자유 문자열**이다(계약이 허용값 enum 을 정의하지 않으므로 우리가 좁히지 않는다) — 형식 검증은 필수·공백금지·50자 상한 + **보이지 않는 문자 제거**(`VisibleTextNormalizer` — 제어문자에 더해 NBSP(U+00A0)·ZWSP(U+200B)·BOM(U+FEFF)·WJ(U+2060)·U+2028/2029·RLO(U+202E) 등 Cf/Zl/Zp/Zs 카테고리)뿐. ⚠ 공용 `ControlCharNormalizer` 를 넓히지 않고 **전용 정규화기를 분리**한 이유는 그 공용 유틸이 **SQL 표현식(`translate`)과 등가**여야 하는 제약(목록 필터 옵션 왕복)을 지고 있어, 한쪽만 제거 집합을 넓히면 이벤트유형 필터가 조용히 0건이 되기 때문이다. ⚠ **증강 유형(`AUG_TYPE_CD`)은 prompt 에서 파생하지 않는다** — 자유 문자열이 유형으로 흘러가면 파생 산출물 경로(`.../{augTypeCd}.mp4`) 순회(CWE-22)와 `RESL_` 네임스페이스 침범(검수 우회)이 열린다. 유형의 단일 원천은 `types[]` enum 3종이다.
- **★같은 (영상 × 종류) 재요청은 몇 번이든 허용 (2026-07-31 사용자 확정, 구속)** — 구 "요청 1회 = 파생영상 1건" 정책(사전 조회 409 + 부분 유니크 `UK_LS_DATA_AUG_ACTVTN` V143)은 **폐기**했다(V147 DROP). 근거: 증강 결과 이미지는 요청마다 다르게 생성되므로 원하는 결과가 안 나오면 같은 영상·종류로 다시 요청하는 것이 **정상 운영 동선**이다. 연타(오조작) 방어는 **FE 단독 책임**이며 **속도 제한(RateLimiter)도 두지 않는다**(자원 소모 CWE-770 은 사용자가 인지·수용한 잔여 위험 — "보안 강화" 명목으로 되살리지 말 것). ⚠ 해상도 파생 전용 `UK_LS_DATA_AUG_RESL`(V125)은 성격이 다르므로 **그대로 유지**된다.
- **★반복 요청의 귀결 ① 결과 구분축은 prompt** — 같은 (영상 × 종류) 파생이 여러 건 공존하므로 `GET /v1/augments/{jobId}/result` 가 **외부 위탁 항목을 항목(=`DATA_AUG_SN`) 단위로** 내려주고 각 항목에 `prompt`(전송 원문 JSON 문자열, 재가공 없음)를 싣는다. 항목을 구분하는 축은 `type` 이 아니라 `id`+`prompt` 다. 구 구현은 이 항목을 제외해, 조건을 볼 수 있는 경로가 accept/reject **응답**뿐이었다 — 즉 **결정을 내린 뒤에야**, 게다가 재전이가 CONFLICT 로 막혀 **다시 조회할 수 없었다**(R9 미충족). 이 엔드포인트는 REVIEWER 전용이라 노출 범위는 accept/reject 와 동일하다(목록 `GET /v1/augments` 는 WORKER 도 허용되므로 prompt 를 싣지 않는다). 외부 위탁 항목도 **`framePairs` 를 채우고 `derivativeRawSn` 을 내려준다** — 짝짓기 근거는 `LS_DATA_AUG.NEW_RAW_SN`(V149)이며 유형·시각 추정을 쓰지 않는다(구 서술 "framePairs 는 비어 있고 derivativeRawSn 은 항상 null" 은 V149 로 해소).
- **★반복 요청의 귀결 ② 파생 영상 식별자는 시각이 아니라 증강행 PK** — `LS_DATA_RAW.VMS_CLIP_ID` 를 `{부모}_AUG_{종류}_{DATA_AUG_SN}` 로 만든다(구: `System.currentTimeMillis()`). 동시 콜백 2건이 같은 밀리초에 도달하면 `UK_LS_DATA_RAW_VMS_CLIP` 위반 → 콜백 tx 롤백 → 파생 미생성 + PENDING 잔류 → 만료 스윕 FAILED 로 **이미 생성된 외부 결과물이 유실**된다(2노드 Active-Active). 포맷은 그대로라 `AugTypeParser`·데이터마트 뷰·동결 메타는 영향 없다. 상한(VARCHAR(128)) 초과 시 앞쪽(부모 부분)만 잘라 유일 접미를 보존한다.
- **★파생 영상에서의 증강 요청은 400 (2026-07-31, Phase 6)** — `ORGNL_RAW_SN IS NOT NULL` 인 영상(증강·해상도 파생 공통)으로 `POST /v1/augments/request` 를 호출하면 `AugmentRequestService.requireNotDerivative` 가 거부한다(`INVALID_INPUT`, "파생 영상은 증강 요청 대상이 아닙니다."). 해상도 변경 경로(`VideoResolutionService`)는 원래부터 같은 가드를 갖고 있었는데 증강 요청만 빠져 있던 드리프트를 정정한 것 — 새 에러코드는 만들지 않는다. 판정은 **인가(REVIEWER) 뒤·검수완료(APPROVED) 검증 앞**에 둔다 — 순서가 바뀌면 파생본 요청 시 "미검수" 같은 엉뚱한 사유가 먼저 뜨고(파생본은 통상 미검수 상태) 진짜 사유에 도달할 수 없다. 응답에 **부모 rawSn 은 담지 않는다**(파생에 배정된 WORKER 는 원본 접근 권한이 없어 원본으로 유도해도 따라갈 수 없고, 접근 권한 없는 자원의 존재를 알려주는 CWE-209/639 위험도 있다). 손자 파생(깊이 2+)이 이미 존재해도 정리하지 않는다 — 신규 생성만 막는다.
- **증강 요청 화면(SCR-AUG-001)은 통합 단일 선택 UI** — 처리 종류 카드 4개(겨울/야간/우천/해상도 변경)를 `radiogroup` 으로 **하나만** 선택하고, 대상 영상도 검수 완료(승인) 1건만 단일 선택한다(§14.6). BE 증강 요청 API 는 `types` enum allowlist(WINTER/NIGHT/RAIN)로 강제하며, RESOLUTION 은 증강 잡 경로가 아니라 저작도구 직접 수행 경로(§14.3)로 분기된다.

### 외부연동 6종 구현 상태 (「생성형 AI API 연동명세서 v1.1」, LogiCraft EXTSYS-002)

| INT 코드 | 명세 | 방향 | 상태 | 구현 코드 |
|------|------|:---:|:---:|------|
| INT-001 | §4.1 작업 요청(`POST /api/genai/jobs`) | 저작도구→외부 | ✅ 구현 | `AugmentJobSubmitService`/`ExternalAugmentClient.requestAugment` |
| INT-019 | §4.2 결과 웹훅(진행·결과 콜백) | 외부→저작도구 | ✅ 구현 | `GenAiCallbackController`(`POST /v1/genai/callback`)/`GenAiCallbackService` |
| INT-020 | §4.4 상태 조회(`GET /api/genai/jobs/{job_id}`) | 저작도구→외부 | ✅ 구현 | `ExternalAugmentClient.fetchJobStatus`, §14.4.1 진행상태 조회 |
| INT-029 | §4.3 상태 동기화(status-sync) | 외부→저작도구 | ⛔ **미구현** | 수신 엔드포인트·클라이언트 모두 없음(백엔드 전수 검색 0건) |
| INT-030 | §4.5 결과 조회(`GET /api/genai/jobs/{job_id}/results`) | 저작도구→외부 | ✅ 구현 | `ExternalAugmentClient.fetchJobResults`, 웹훅 유실 회수(§14.4.1) |
| INT-031 | §4.6 취소(`POST /api/genai/jobs/{job_id}/cancel`) | 저작도구→외부 | ✅ 구현 | `AugmentCancelService`, §14.4.1 취소 |

> **INT-029(상태 동기화)만 미구현**이다. 명세서상 이 경로는 **수신측(저작도구)이 제공**해야 하는데(외부가 진행 중 상태를 능동적으로 밀어 넣는 보조 채널), 저작도구 쪽에 이 요청을 받는 엔드포인트가 없다(`status-sync` 문자열로 백엔드 전수 검색해도 0건). mock-server 는 이 갭을 알고 있어 자동 발신하지 않고, `MOCK_GENAI_STATUS_SYNC_URL` 을 **명시했을 때만** 테스트용으로 수동 발신한다(mock-server/README.md §상태 동기화). 진행 상태는 §4.2 웹훅(INT-019, 자동)과 §4.4 상태 조회(INT-020, 폴링)만으로도 갱신되므로 기능 공백은 아니지만, 명세 6종 완전 정합은 아니다.

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
        · 위탁 <전에> 모든 청크의 LS_DATA_AUG_JOB 을 RECEIVED 로 전량 선기록(REQUIRES_NEW)
          — 하나라도 실패하면 한 건도 위탁하지 않고 앞 청크까지 FAILED 로 종결
        · 같은 tx 에 LS_DATA_AUG_JOB_FILE 선기록 — 입력 순서(FILE_SEQ)↔프레임(SRC_SN) 대응
        · 제출은 <논블로킹 + concatMap 직렬>: 앞 청크 완료 → 비식별 신고 재판정 → 다음 청크
          202 ACK 는 완료 핸들러(AugmentSubmitOutcomeRecorder, 전용 풀)가 받아 OTSD_JOB_ID 적재
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
- **재수신(200) vs 진짜 충돌(409) 구분**: 증강 인계(`AugmentResultService`)에서 `otsd_job_id` 소유자를 **쓰기 이전에** 확인한다. 같은 증강의 재수신은 200(`DUPLICATE`, `applied=false`)으로 흡수하고, **다른 증강**이 그 job_id 를 보유한 오배송만 409 다. 충돌을 UNIQUE 위반으로 판정하면 PostgreSQL 이 트랜잭션을 abort(25P02) 시켜 이후 모든 쿼리가 거부되므로(=500), 위반 이후의 소유자 재조회는 **REQUIRES_NEW 독립 트랜잭션**(`AugmentJobIdOwnerLookup`)에서만 한다. 회귀 가드: `AugmentCallbackIdempotencyIT`(실 DB — 재수신 no-op·오배송 409·동시 2건 1회 반영·호출자 트랜잭션 무오염).
- **고아 키 방지**: ledger 등록·외부 위탁은 요청 트랜잭션 안이 아니라 **AFTER_COMMIT** 에서만 수행 — 요청 롤백 시 aug 행도 멱등 키도 남지 않는다.

#### 위탁 제출의 논블로킹화 (2026-07-30)

`ExternalAugmentClient.requestAugment` 반환 타입이 `AugmentSubmitResult` → **`Mono<AugmentSubmitResult>`** 로 바뀌었다. 구 구현은 202 ACK 왕복을 `.block()` 으로 기다렸고, 그 스레드가 `AugmentRequestBridge` 의 `batchAsyncExecutor`(core 2 · CallerRuns)였다 — 청크가 N 개면 점유도 N 배였다. 공통 골격은 [07 §7.2-1](07-batch-pipeline.md) 참조이며, 증강 고유 규칙은 다음과 같다.

- **청크 직렬화는 유지된다 — `flatMap` 이 아니라 `concatMap`**. 청크 사이에는 비식별 누락 신고 **재판정**(`Mono.defer` 안, 구독 시점 평가)이 있어 "위탁 도중 신고" 를 관측해 남은 청크의 PII 경로 전송을 끊는다. 병렬 발사하면 그 방어가 통째로 무력화된다(벤더 rate 측면에서도 동시 발사는 금물).
- **`augmentSubmitScheduler` 전용 풀은 "완료 기록" 뿐 아니라 <u>청크 직렬 전송의 실행 스레드</u>** 다 — 두 번째 청크부터의 신고 재판정(DB 조회)·제출이 여기서 돌아야 reactor-netty 이벤트 루프가 블로킹되지 않는다. 증강만 `publishOn` 을 유지하는 이유가 이것이다(VLM·KPST 는 `SubmitSignalDispatch` 명시 투입).
- **`SubmitOutcome.accepted` → `dispatched` 로 의미 변경** — 이제 "수락된 job 수"가 아니라 **시퀀스에 투입한 청크 수**다. 따라서 `dispatched==0` 은 "**제출 전에** 거부됐다"(신고 구간 · 비식별 경로 부재 · 선기록 실패)는 뜻이며 이때만 호출부가 즉시 실패 롤업한다(외부로 나간 것이 없어 안전).
- **개시된 뒤의 전건 실패는 시퀀스 종료 롤업이 확정** — `doFinally` 에서 `AugmentSubmitOutcomeRecorder.onSubmitSequenceFinished` → `AugmentSubmitRollupTxService`. 구 "`accepted==0` 즉시 롤업" 의 이관처다.
- **완료 핸들러는 조건부 원자 UPDATE 로 기록** — 지각 ACK 신호가 콜백이 이미 올린 job 상태를 강등하지 못한다.
- **회수는 기존 `AugmentJobExpirySweeper`** 가 비종결(RECEIVED/RUNNING) job 을 집는다. **새 스위퍼를 만들지 않는다**(이중 진실원 금지).
- `NoopExternalAugmentClient`(`mode=noop`)도 같은 시그니처로 `Mono.just(AugmentSubmitResult.skipped())` 를 반환한다.
- 코드: `augment/integration/{ExternalAugmentClient,HttpExternalAugmentClient,NoopExternalAugmentClient}`, `augment/service/{AugmentJobSubmitService,AugmentSubmitOutcomeRecorder,AugmentSubmitRollupTxService,AugmentJobRecorder}`

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
- **결과 조회(`GET /v1/augments/{jobId}/result`, SC-023)는 프레임 비교쌍을 반환**한다 — 좌=원본 비식별 프레임 / 우=파생 프레임(둘 다 `/v1/frames/{srcSn}/deid-image` 경로만 노출, 스토리지 경로 미노출), `(RAW_SN, FRM_NO)` 동등 조인 + 기본 12장 페이징.
  - **★외부 위탁 증강(WINTER/NIGHT/RAIN)도 쌍을 채운다 (2026-07-31 확정)** — 구 구현은 해상도 파생에만 적용하고 외부 위탁은 `framePairs=[]`·`totalFramePairs=0` 을 **상수**로 반환해, 정작 **검수(채택/반려) 대상인 유일한 유형**의 비교 이미지가 영구히 0장이었다. 그 상태에서 FE 는 "외부 연동 이후 표시됩니다" 를 띄우면서 채택/거부 버튼을 활성으로 그려, "이미지를 비교해 보고 사용 유무를 선택" 요구의 등재 게이트가 의례적 절차가 됐다. 짝짓기 근거는 유형별로 다르다 — **외부 위탁=`NEW_RAW_SN`(V149)** / **해상도=`VMS_CLIP_ID` 마커 파싱**(백필이 없어 기존 해상도 파생은 `NEW_RAW_SN` 이 NULL 이므로 **통일 금지**).
  - **★항목 상태축 `resultState` 신설** — `GENERATING`(생성 중) / `PREPARING_FRAMES`(생성 완료·비교 이미지 반입 중 = 0장이 **정상**) / `READY` / `WITHHELD`(파생이 비식별 신고 구간) / `GENERATION_FAILED`(dead-letter — 기다려도 안 생김) / `CANCELED` / **`PURGED`**(폐기 유예 경과로 실삭제 — 영구히 이미지 없음). `decision` 만으로는 ①0장인데 정상 vs 영구 실패 ②**생성 실패 vs 사람의 반려**(둘 다 `REJECTED`) 가 구분되지 않아 화면이 사실과 다른 문구를 띄웠다. 응답 공통 `message` 는 항목별 사정을 표현할 수 없으므로 항목 단위 필드로 둔다. `PURGED` 는 **최우선 판정**이다 — 실삭제된 항목은 쌍이 0장이라 그냥 두면 `PREPARING_FRAMES`("곧 옴")로 계산돼 같은 응답의 `discard.purged=true`("영영 없음")와 정면으로 모순된다.
  - **★폐기 축 `discard` 신설 (2026-08-01)** — 반려된 결과물의 `discardedAt`(표식 시각) / `purgeAt`(실삭제 예정 = 표식 + 유예일) / `purged`(실삭제 완료) / `restorable`(복구 시도 가능 — **UI 힌트일 뿐 최종 판정 아님**, 실제 판정은 복구 API 의 `findOpenForUpdate` 행 잠금) 4필드. 그전에는 폐기 상태를 **내려주는 GET 이 하나도 없어** 화면이 복구 버튼도 유예 안내도 만들 수 없었다(§14.4.2 의 복구 API 가 사실상 도달 불가).
    - **판정은 "최신 1행" 이 아니라 "최신 행의 상태"** — 폐기 원장은 반려마다 **새 행**을 만들고 복구는 행을 **닫을 뿐 지우지 않아** 반려→복구→재반려 이력이 여러 행으로 쌓인다. 최신 행을 상태 확인 없이 노출하면 **복구되어 지금은 멀쩡한 항목이 과거의 닫힌 표식 때문에 "곧 삭제됨" 으로 표시**된다. 규칙: 복구됨(`RSTR_DT`)·행 없음 → `discard=null` / 실삭제(`DEL_DT`) → `purged=true`+`restorable=false` / 실삭제 클레임 중(`DEL_PRCS_DT`) → `restorable=false` / 그 외 열린 표식 → `restorable=true`.
    - **`purgeAt` 은 스윕 비활성(`authoring.augment.discard.enabled=false`) 시 `null`** — 스윕이 뜨지 않아 영원히 지워지지 않으므로 예정 시각을 내리면 거짓말이 된다. **`purgeAt` 이 이미 과거인데 `purged=false` 인 상태는 정상**이다(스윕 주기 기본 1시간).
    - **`purged=true` 의 관측 창은 좁다** — 실삭제는 `LS_DATA_AUG` 행 자체를 지우므로 커밋 후에는 항목이 목록에서 통째로 사라진다. 이 값이 나가는 경우는 **조회가 잡은 증강 스냅샷과 폐기 표식 사이에 스윕이 커밋된 창**뿐이며, 그 창에서 응답이 자기모순에 빠지지 않게 하는 장치다.
    - **경로·사유는 싣지 않는다(CWE-209/359)** — `VDO_FILE_PATH`(NAS 실경로)는 응답 DTO 에 **필드로도 만들지 않고**, `DSCD_RSN` 은 이미 `rejectReason` 으로 나가므로 중복 축을 두지 않는다. 해상도 파생은 검수 대상이 아니라 표식이 생길 수 없어 항상 `null`. 조회는 검수 행과 동일하게 **배치 1회**(항목 수 무관, 인덱스 `IX_LS_DATA_AUG_DSCD_LOOKUP` V152 — 기존 4개는 전부 부분 인덱스이거나 다른 컬럼이라 이 조회를 커버하지 못해 seq scan 이었고, 비석 테이블은 retention 정리가 없어 시간이 갈수록 나빠진다).
    - **★같은 응답의 다른 필드도 함께 맞춘다 (2026-08-01 자기모순 해소)** — 실삭제 스윕은 한 트랜잭션에서 `LS_DATA_AUG_RVW` → `LS_DATA_AUG` 를 지우므로, 조회 도중 커밋되면 **검수 행만 사라진 상태**를 읽어 응답이 "영구히 삭제됨" 과 "아직 결정 대기이니 채택/반려하라" 를 **동시에** 말했다(그 버튼은 FE 가 그리는 정상 동선이고 누르면 404). 폐기 축이 살아 있으면 → `decision=REJECTED`(**폐기 표식의 존재 자체가 사람이 반려했다는 증거** — 표식은 반려 트랜잭션에서만 생긴다) + `reviewable=false`, 실삭제(`purged`)면 추가로 `resultState=PURGED` + `framePairs=[]` + `totalFramePairs=0`(이미 CASCADE 로 사라진 `srcSn` 의 **죽은 이미지 링크** 차단). 판정 입력은 전부 **매퍼가 돌려준 폐기 축** 하나라 복구된 항목은 자동으로 예외가 된다(다시 채택/반려 가능).
  - **★dead-letter 는 결정 불가** — 비동기 확정(Phase A/B/C) 실패는 `AUG_PROC_STTS_CD` 를 `ACCEPTED` 로 **남겨둔 채** `DEAD_LETTER_AT` 만 찍는다(`applyGenerationResult` 가 PENDING 에서만 전이하므로). 그래서 `LsDataAug.isGenerationSucceeded()` 는 상태 + **dead-letter 축**을 함께 본다 — 그러지 않으면 프레임 0건 파생을 채택해 등재 게이트를 통과시키고, 같은 화면 헤더(`isProcessingFailed` → FAILED)와 상반된다.
  - **★accept/reject 는 증강 행 `FOR UPDATE` 로 직렬화** — `LS_DATA_AUG_RVW` 에 `DATA_AUG_SN` 유니크가 없어(V25 는 비유니크 인덱스) 동시 채택+반려가 각자 새 검수 행을 INSERT 하면 게이트(`EXISTS ACCEPTED`)는 등재로, 화면(최신 1행)은 거부됨으로 갈렸다. 유니크 인덱스는 기존 중복 행 선정리를 전제하므로 채택하지 않고 **웹훅 경로가 이미 쓰는 잠금**을 재사용한다(락 순서 동일 — 데드락 없음).
  - **★페이징 축이 둘이다 (2026-07-31 확정)** — `page`/`size`(기본 0/12, max 100)는 **프레임 쌍 축**, `itemPage`/`itemSize`(기본 0/20, max 100)는 **결과 항목 축**이며 서로 **독립**이다. 응답에 항목 축 총량 `totalElements`/`totalPages` 를 싣는다.
  - 한 창을 공유하면 **"한쪽 축 총량이 0이면 다른 축이 갇힌다"** 가 구조적으로 남는다 — 실제로 프레임 쌍 0건인 순수 외부 위탁 영상(WINTER 12 + RAIN 1)에서 FE 페이저가 렌더되지 않아 **13번째 항목이 도달 불가**가 됐고, 응답에 항목 축 총량이 없어 2페이지의 존재조차 알 수 없었다. 프레임 페이지 이동이 **탭 구성을 바꾸던** 부수 증상도 축 분리로 해소된다.
  - **해상도 파생도 항목 축의 정식 원소다** — 구 서술("항목 페이징 대상이 아니다 / 모든 항목 페이지에 함께 실린다")은 폐기됐다. 모든 페이지에 실으면 페이지를 이어붙이는 클라이언트가 **중복 수집**하고, `itemPage==0` 에만 실으면 이번엔 **프레임 축이 항목 축 위치에 갇힌다**(itemPage≥1 이면 비교 이미지 도달 불가 — 같은 결함의 역방향 재발). "전 항목 도달 가능 + 페이지 간 중복 0 + `totalElements` 정합" 을 동시에 만족하는 구성은 이것뿐이며, `totalElements` 는 **외부 위탁 + 해상도 파생 전체**를 센다. 프레임 축은 그 페이지에 실린 각 항목 **안에서** 독립적으로 동작한다.
  - **★"센 뒤 드롭" 해소 (2026-08-01)** — 구 구현은 `totalElements` 를 확정한 **뒤** 페이지 슬라이스 루프에서 해상도 파생만 두 지점(**마커 미해석** · **파생본이 신고 구간**)에서 드롭해, 총량이 실제 `results` 합계보다 컸다(FE 페이저가 존재하지 않는 페이지를 그리고 마지막 페이지가 비었다). 드롭 술어를 **항목 구성 시점**(`resolveItems`)으로 옮겨 두 값이 **같은 집합**에서 나오게 했다 — 새 드롭 조건이 생기면 반드시 이 단계에 넣는다. 비용 증가는 없다(파생 해석은 종전에도 jobId 단위 1회, 신고 게이트 대상은 해상도 파생뿐이라 영상당 최대 3건).
  - ⚠ **외부 위탁 항목은 이 필터에 걸리지 않는다(의도된 비대칭)** — 신고 구간이어도 **항목은 남기고 이미지만** 뺀다(`withheld` + `resultState=WITHHELD`). 항목을 지우면 그 증강의 결정 상태·생성 조건까지 화면에서 사라져 REVIEWER 가 무슨 일이 있었는지 알 수 없다. "일관성" 을 이유로 해상도와 통일하지 말 것.
  - 하위호환: 신규 파라미터는 전부 optional 이고 **기존 `page`/`size` 의 기본값·의미는 불변**이라 구 호출이 그대로 동작한다. FE 항목 페이저 배선은 후속(Phase 5). 파생↔프리셋 판별은 `VMS_CLIP_ID` 를 **중앙 파서 `video/util/AugTypeParser` 단일 원천**으로 해석하므로 실데이터 포맷 드리프트(`_RESL_RESL_480P_`·구형 `_RES_RES_480P_`)도 증강 이력 화면과 동일하게 인식된다. 코드: `augment/service/AugmentResultViewService`
- **★파생 생성은 원본 비식별 신고와 무관하다 (2026-07-29 확정, 구속)** — 증강 파생과 동일 정책이다. 해상도 파생은 **외부 위탁이 전혀 없는 내부 ffmpeg/Java2D 리스케일**뿐이라 신고 구간에 생성해도 외부 유출 경로가 열리지 않는다.
  - 부모 게이트 3곳(`ResolutionReservationPersister` 예약 · `ResolutionSnapshotService` Phase A · `ResolutionPersistService` Phase C)은 **`DE_IDNTF_YN='N'`(비식별 미수행)·null 만 차단**하고 `'F'`(신고)는 통과시킨다. 판정 단일 원천은 `LsDataRaw.hasDeidentArtifact()`(`'Y'`|`'F'`)이며 **증강 경로(`AugmentResultService.evaluateParentGate`)도 같은 헬퍼를 쓴다**.
  - `'F'` 는 의미가 둘이다 — ①**비식별 누락 신고**(비식별본은 디스크에 존재, 마스킹만 실패) ②**비식별 API 실패**(산출물 자체가 없음). 플래그만으로 구분되지 않으므로 **산출물 실재 검증이 fail-closed 로 뒤를 받친다**: Phase A 는 최신 SUCCESS 비식별 procLog 경로 부재 → `NOT_FOUND`, 프레임 비식별 경로 부재 → `CONFLICT`(`deidFrameSourceStrict`), Phase B 는 비식별 영상 파일 부재 → `NOT_FOUND`. **원본(비-비식별) 경로 폴백은 어디에도 두지 않는다**(PII 복제 차단).
  - **stale 창 게이트(Phase C)는 존치하되 판정축이 신고가 아니라 복사 원자성**이다 — 구 조건 ①`capturedAt` 이후 신고 이력은 **제거**하고, ②최신 SUCCESS 비식별 procLog 경로 불일치 ③(파일 존재 시) mtime > `capturedAt` 두 조건만 남긴다. 스냅샷 이후 부모 비식별본이 교체되면 프레임별로 다른 버전이 섞인 산출물이 나오므로 abort 한다(러너가 cleanup + FAILED 전이).
- 코드: BE `video/service/{VideoResolutionService,ResolutionDerivativeService,ResolutionReservationPersister,ResolutionDerivativeFinalizer}`, 적재 대상 `LS_DATA_AUG`(AUG_TYPE_CD=RESL_*)+`LS_DATA_AUG_LBL_MAP`, 인덱스 `UK_LS_DATA_AUG_RESL`(V124), 구 테이블 DROP(V125). FE `pages/AugmentRequestPage`(submit 분기) + `features/video/hooks/useResolutionDerivative`

## 14.4 활용 여부 검수 (RQ-SFR-07-03, UC-010)

```
PENDING 증강 영상 (SCR-AUG-002)
  → REVIEWER 확인 → accept(PENDING→ACCEPTED) 또는 reject(사유 필수, PENDING→REJECTED)
  → ACCEPTED 만 기존 배정·검수 흐름으로 학습데이터 편입
```

- `POST /v1/augments/{id}/accept` · `/reject`, PENDING 외 상태 전이는 409
- `LS_DATA_AUG.AUG_PROC_STTS_CD`: PENDING / ACCEPTED / REJECTED / **CANCELED**(V148 신설, §14.4.1)

### 승인해야만 작업목록·배정에 등재 (Phase 6, 2026-07-31)

REVIEWER 가 accept(사용 채택)하기 전까지 파생영상은 **작업목록·배정 어디에도 나타나지 않는다** — "이미지를 비교해 보고 사용 유무를 선택"한 뒤에야 라벨링 대상이 된다는 요구를 실제로 강제한다.

- **판정 단일 원천**: `augment/repository/DerivativeWorkEligibility` — 원본 영상(`ORGNL_RAW_SN IS NULL`)은 무조건 통과, 파생 영상은 그 파생을 만든 증강 행의 검수(`LS_DATA_AUG_RVW.RVW_STTS_CD`)가 `ACCEPTED` 여야 통과. 판정 축은 **검수 축**이지 `LS_DATA_AUG.AUG_PROC_STTS_CD`(생성 결과 축, 웹훅이 생성 성공만으로 채운다)가 아니다 — 생성 성공만으로 게이팅하면 생성된 파생이 전부 통과해 게이트가 아무것도 막지 못한다.
- **가시 범위 차단(목록에서 숨김)** — 작업목록(`TaskBoardQueryRepository`)·배정 후보 목록(`AssignmentQueryRepository`) 양쪽에 같은 술어가 걸려 목록·count·KPI 집계·이벤트유형 옵션 전부에서 미등재 파생이 빠진다.
- **쓰기 경로 차단(400)** — 목록에 없어도 REVIEWER 가 rawSn 을 직접 알아내 배정 API를 호출하면 목록 우회가 되므로, **신규 배정(`assign`)과 재배정(`reassign`) 양쪽**에서 같은 판정으로 한 번 더 막는다(`AssignmentService.rejectUnenrolledDerivatives`, `INVALID_INPUT` "검수 승인 전인 파생 영상은 배정할 수 없습니다."). 1건이라도 미등재 파생이면 요청 전체를 거부한다(부분성공 없음, 기존 APPROVED 가드와 동일 정책). 거부 메시지에 부모 rawSn 은 담지 않는다.
- **예외 2가지**: ①**해상도 파생**(`AUG_TYPE_CD` `RESL_` 접두) — 검수 대상이 아니라 accept/reject 진입 자체가 400 이라 검수 행이 영영 생기지 않는다. 예외가 없으면 해상도 파생 전량이 작업목록에서 사라진다. ②**그랜드퍼더링**(`NEW_RAW_SN` 매핑이 없는 V149 이전 파생) — 어느 증강 행이 만든 파생인지 알 수 없어 무조건 미등재로 두면 고아 배정(배정 행은 있는데 목록에는 없어 접근 불가)이 된다.
- **판정 범위는 자기 행 하나** — 파생의 파생(손자, 깊이 2+)이 있어도 조상·자손을 순회하지 않는다(이 프로젝트는 조상/자손 전파를 4라운드 시도 후 전부 철회했다 — 차단↔복구 비대칭, 팬아웃 상한 초과 시 정상 트리 fail-closed DoS).
- **⚠ 알려진 한계 — 그랜드퍼더링 항목의 화면 표시 불일치**: `NEW_RAW_SN` 매핑이 없는 기존 증강 항목은 프레임 비교쌍을 만들 수단이 없어 `resultState` 가 영구히 `PREPARING_FRAMES`("반입 중")로 표시된다 — 실제로는 0장에서 더 늘어나지 않는데도 "생성 완료, 반입 대기"처럼 보인다. 등재 게이트 자체는 이 항목도 정상적으로 검수 대상으로 취급하므로(그랜드퍼더링 예외는 등재 게이트 한정) REVIEWER 가 비교 이미지를 한 번도 보지 못한 채 accept 할 수 있다.

### 14.4.1 진행상태 조회 · 취소 · 웹훅 유실 회수 (FE 내부 API)

- **`GET /v1/augments/{id}/progress`** (REVIEWER/WORKER) — `id` = `LS_DATA_AUG.DATA_AUG_SN`(accept/reject 와 동일 식별자, 별도 job PK 개념 없음).
  - **진행률 = 청크 job 의 파일 수 가중 평균**: `round(Σ(weight×p)/Σweight)`, `weight = max(1, LS_DATA_AUG_JOB.TOT_NOCS)`, `p` = 종결 청크 100 / 비종결 청크는 외부 상태조회(§4.4) `progress`(미제공 0). **min 이 아니다** — min 이면 청크 3개 중 2개가 100%여도 전체가 0%로 보인다. `weight` 최솟값 1 은 위탁 거부 기록(`TOT_NOCS=0`)이 분모에서 사라지는 것을 막는다.
  - **외부 장애는 200 으로 degrade** — `progress:null` + `unavailableReason` **4값**(`AugmentProgressUnavailableReason`): `NOOP`(외부 미연동 `mode=noop` — **오류 아님**, 진행률 바를 숨기고 안내만) / `TRANSIENT_ERROR`(서킷 open·타임아웃·계약 위반 — **진짜 장애**, 서버 WARN + 재시도 안내) / `AWAITING_ACK`(비종결 청크 중 `OTSD_JOB_ID` 미보유 — "접수 확인 중", 오류로 표시 금지) / `QUERY_LIMIT_EXCEEDED`(**우리 쪽** 자체 상한 — 청크 수·요청 시간 예산 소진, 벤더 장애 아님. `TRANSIENT_ERROR` 와 분리하지 않으면 우리 자체 상한이 벤더 장애로 위장된다). dev/stg/prd 기본이 `noop` 이라 대부분 `NOOP` 이 나온다.
  - `nextPollAfterMs` 는 **권고** 폴링 간격(0=종결). 속도 제한(RateLimiter)은 두지 않기로 확정돼 폴링 증폭을 줄이는 수단은 이 힌트뿐이다.
  - 진행률이 0 보다 크면 표시 상태는 `RUNNING` 이다(“접수됨 99%” 자기모순 방지). DB `JOB_STTS_CD` 를 덮어쓰지는 않는다.
  - **웹훅 유실 회수(INT-030)** — 외부 상태조회가 종결(SUCCEEDED/FAILED/CANCELED)인데 로컬이 비종결이면 이 조회 시점에 결과조회(`GET /api/genai/jobs/{job_id}/results`)로 산출물을 회수해 인계한다. 산출 경로 검증은 웹훅과 **같은 코드**(`AugmentJobSuccessApplier.verifyOutputPaths` → `verifyExternalReadablePath`)를 쓰며, 허용 루트 밖이면 회수하지 않고 job 을 비종결로 둔다(만료 스윕이 회수). 멱등은 증강 행 `FOR UPDATE` + `job.isTerminal()` 재확인 + `uk_aug_external_job_id` UNIQUE 3겹이라 **파생 영상이 중복 생성되지 않는다**.
- **`POST /v1/augments/{id}/cancel`** (REVIEWER 전용) — 요청 바디는 선택이며 **`reason` 필드 하나만** 받는다(Mass Assignment 방어 — 종결 판정을 요청으로 조작할 수 있는 필드를 두지 않는다).
  - 계약상 취소는 **웹훅을 발사하지 않으므로**(동기 응답이 유일한 통보) 같은 요청에서 `LS_DATA_AUG` 를 `CANCELED` 로 확정한다. 확정하지 않으면 그 증강은 영구 `PENDING` 이고, 고아 회수기(`findOrphanPendingAugSns`)는 "job 0건" 만 집으므로 만료 스윕도 건지지 못한다.
  - **비종결 청크 전부**에 §4.6 취소를 보낸다. 일부만 성립하면 `fullyCanceled=false` + `failedJobSeqs` 로 **부분 실패를 드러낸다**(재시도 멱등). 재시도하지 않아도 남은 청크는 만료 스윕이 회수하므로 영구 대기는 없다.
  - **동시 취소·이미 종결은 409 가 아니라 200 + `canceled=false`** — 증강 행 `FOR UPDATE` 안에서 `PENDING→CANCELED` 전이가 곧 클레임이라 외부 호출은 정확히 한 번만 나간다.
  - 취소 직후 도착한 SUCCEEDED 결과는 non-PENDING 앵커에 흡수되어 폐기된다(WARN — 의도된 동작).
- **해상도 파생(`RESL_*`)은 두 API 모두 400** — 외부 위탁 job 이 없어 조회·취소 대상 자체가 없다(`AugmentReviewService.loadOrThrow` 의 accept/reject 차단과 같은 패턴).
- 없는 `id` 는 역할과 무관하게 **404**(403 과 섞으면 응답 코드가 존재 여부 오라클이 된다). 소유자 스코프는 목록 `GET /v1/augments` 와 동일하게 두지 않는다(기존 정책 상속).
- 코드: `augment/service/{AugmentProgressService,AugmentProgressCalculator,AugmentSnapshotLoader,AugmentExternalProbe,AugmentCancelService,AugmentCancelTxService,AugmentResultRecoveryService,AugmentJobRecoveryTxService}`, `webhook/service/AugmentJobSuccessApplier`(웹훅/회수 공용)
- **해상도 파생(`AUG_TYPE_CD='RESL_*'`)은 검수 대상 아님** — 저작도구 내부 생성물이라 accept/reject 진입 자체가 400 으로 차단된다(`AugmentReviewService.loadOrThrow`). 이력·집계에는 포함되며 상태는 내부 라이프사이클(예약 PENDING → finalize ACCEPTED)로만 전이한다(§14.3)

### 14.4.2 반려 = 폐기 표식 → 유예 → 실삭제 (Phase 7)

반려는 "목록에서 감추기" 로 끝나지 않고 **파생영상 자체를 회수**한다. 등재 게이트가 즉시 차단하고(§14.4), 유예가 지나면 배치가 **DB 행과 생성 파일을 영구 삭제**한다.

```
reject(사유 필수) → LS_DATA_AUG_DSCD 폐기 표식(DSCD_DT = 유예 기산점)
   ├─ 유예 내 복구 POST /v1/augments/{id}/restore (REVIEWER, 사유 필수)
   │     → 표식 닫힘 + 검수 재오픈(REJECTED→PENDING) = 다시 채택/반려를 고를 수 있다
   └─ 유예 경과 → 스윕(원자 클레임) → DB 삭제(1 트랜잭션) → 커밋 후 파일 삭제
```

- **유예는 설정값(기본 7일)** — `authoring.augment.discard.grace-days`. **0/음수면 기동이 실패**한다(0 = "반려 즉시 영구 삭제" 라 되돌릴 수 없다). `.env` 에 빈 값을 두면 기본값이 무력화되므로 값을 명시한다. 설정 키는 `authoring.augment.discard.*` **7개**(`AugmentDiscardProperties`) — 전부 하한 위반 시 `@PostConstruct` **기동 실패**(경고가 아니다):

  | 키(`AUGMENT_DISCARD_*`) | 기본값 | 의미 | 하한 |
  |---|---|---|---|
  | `ENABLED` | `true` | 스윕 활성화. 끄면 표식만 쌓이고 실삭제는 일어나지 않는다(복구는 계속 가능) | — |
  | `GRACE_DAYS` | `7` | 유예 기간(일) | 1 |
  | `INTERVAL_MS` | `3600000`(1h) | 스윕 주기 | 60,000ms |
  | `INITIAL_DELAY_MS` | `600000`(10m) | 기동 후 첫 스윕까지 지연 | — |
  | `BATCH_SIZE` | `50` | tick 당 처리 상한(CWE-770 방어) | 1 |
  | `CLAIM_STALE_MINUTES` | `60` | 클레임 후 이 시간 경과 시 스트랜드 클레임으로 보고 재클레임 허용 | 5분 |
  | `FILE_CLEANUP_MAX_ATTEMPTS` | `5` | 파일 정리 재시도 상한(회) — 초과 시 데드레터(사람 개입) 종결 | 1 |

  `interval-ms × file-cleanup-max-attempts` 가 "자동 복구를 포기하기까지의 시간"이다(기본 1h×5회=5시간) — NAS 장애가 이보다 길 수 있는 환경에서는 올려야 한다.
- **복구는 표식 해제가 아니라 반려를 되돌리는 것** — 표식만 지우면 검수가 `REJECTED` 로 남아 게이트(`EXISTS ACCEPTED`)가 계속 닫혀 "복구했는데 여전히 안 보이는 반쪽 복구" 가 된다. **검수 행은 적층하지 않는다**(과거 `ACCEPTED` 행이 남으면 이후 반려해도 게이트가 열린다). 되돌린 이력(누가·언제·왜)과 원래 반려 사유는 폐기 원장에 남는다.
- **삭제되지 않는 것** — ①원본 영상(최종 DELETE 문에 `ORGNL_RAW_SN IS NOT NULL` 리터럴) ②검수 승인(`APPROVED`)된 파생(관제 접근 보장 구속 정책, 배치가 독립 재확인) ③클레임 이후 복구된 건(최종 DELETE 가 표식을 재평가, 0건이면 **앞 단계 삭제까지 전체 롤백**) ④`NEW_RAW_SN` 이 없는 그랜드퍼더링 증강(어느 파생인지 알 수 없어 **표식 자체를 만들지 않고** "수동 정리 필요" 만 로그).
- **삭제 순서(FK 없는 테이블 포함)** — `LS_DATA_LBL_ATTR_VAL` → `LS_DATA_AUG_LBL_MAP` → `LS_DATA_LBL_HSTRY` → `LS_DATA_LBL` → `LS_DATA_AUG_RVW` → `LS_DATA_AUG` → `LS_DATA_RAW`(V146 CASCADE 가 자식 27개 정리). 상수 `AugmentDiscardPurgeTxService.DELETE_ORDER` 가 고정하고 드리프트 가드 테스트가 SQL 과 대조한다. ⚠ 지우는 것은 라벨 **속성값**(`LS_DATA_LBL_ATTR_VAL`)이지 라벨 마스터의 속성 **정의**(`LS_LABEL_ATTR`)가 아니다.
- **DB 먼저 커밋 → 파일 삭제** — 역순이면 "파일은 없는데 행은 살아있는" 영상이 된다. 커밋 전에 파생 비디오 경로를 비석에 기록하고(`VDO_FILE_PATH`), 파일 삭제 실패는 `FILE_DEL_DT IS NULL` 로 남아 다음 tick 이 재시도한다.
- **파일 삭제 범위** — `frames/deid/{파생 rawSn}/**` 과 `videos/{augment|resolution}/{부모}/{파생}/*.mp4` 뿐. 실경로(`toRealPath`) 기준 세그먼트 검증을 통과해야 하고, **심링크는 따라가지도 지우지도 않는다**(원본/비식별 base 가 같은 운영에서 `frames/raw/**` 로의 우회 차단 — CWE-59/367). 판정이 서지 않으면 그 파생의 파일 삭제를 skip 하고 WARN 한다(원본 삭제보다 고아 파일 존치가 안전).
- 2노드 Active-Active 중복 집행은 **조건부 UPDATE 클레임**이 막는다(Quartz 클러스터링은 트리거 중복만 막는다). 클레임 직후 프로세스가 죽으면 `claim-stale-minutes` 경과 후 재클레임된다.
- **⚠ 데드레터(사람 개입 필요) 종결 비석의 재개 절차(운영 필수 숙지)**: 파일 정리가 `file-cleanup-max-attempts` 를 넘겨 실패하면 그 비석은 `FILE_DEL_FAIL_DT` 가 찍혀 자동 재시도 큐(`LS_DATA_AUG_DSCD.FILE_DEL_RTRY_NMTM` 오름차순 폴링)에서 **영구히** 빠진다(그러지 않으면 심링크·규약 밖 항목 하나가 오래된 순 배치의 앞자리를 점유해 이후 비석의 파일 정리를 전면 정지시킨다 — head-of-line blocking). 이 큐에는 재개 API 가 없으므로 **원인(NAS 순단·권한 등)을 해소한 뒤 운영자가 DB 에서 직접** `FILE_DEL_FAIL_DT` **와** `FILE_DEL_RTRY_NMTM` **를 함께 리셋**해야 한다 — `FILE_DEL_FAIL_DT` 만 지우면 이미 상한에 도달한 `FILE_DEL_RTRY_NMTM` 값 때문에 다음 tick 단 한 번의 실패로 즉시 재-데드레터된다(재시도 조건 `attempts >= fileCleanupMaxAttempts` 가 시도마다 재평가되므로).
- 코드: `augment/service/{AugmentDiscardService,AugmentDiscardPurgeSweeper,AugmentDiscardPurgeTxService,DerivativeArtifactRemover}`, `augment/repository/LsDataAugDscdRepository`, 설정 `augment/config/AugmentDiscardProperties`.
- 검증: `AugmentDiscardPurgeIT`(원본·승인분 미삭제, 유예 전/후, 고아 0건, 클레임 레이스, 복구 후 재결정), `DerivativeArtifactRemoverTest`(심링크·경로 방어), `AugmentDiscardPropertiesTest`(유예 오설정 기동 실패), `AugmentDiscardStateMapperTest`+`AugmentResultDiscardStateTest`(조회 노출 축 — 복구 이력 판정·실삭제·클레임·경로 미노출·배치 조회).
- **⚠ FE 미배선 (BE 전용 — Phase 7 은 BE 만 배포됨)**: 복구(`POST /v1/augments/{id}/restore`) 호출부·유예/폐기 상태 안내 UI가 프론트엔드에 없다(`frontend/src/features/augment/` 전수 검색 시 `restore` 미참조). 반려된 파생을 되살리려면 현재는 API 를 직접 호출해야 한다. **BE 조회 축은 2026-08-01 에 열렸다** — §14.3 `discard`(`discardedAt`/`purgeAt`/`purged`/`restorable`)로 화면이 복구 버튼·유예 안내를 만들 재료는 갖춰졌고, 남은 것은 FE 배선이다. 마찬가지로 §14.3의 `resultState`(`GENERATING`/`PREPARING_FRAMES`/`READY`/`WITHHELD`/`GENERATION_FAILED`/`CANCELED`/`PURGED`) 축도 BE 응답 필드로는 존재하나 FE `features/augment/types.ts` 에 아직 반영되지 않아 화면은 여전히 `decision` 만으로 분기한다(0장 정상 vs 영구 실패 구분이 화면에는 나타나지 않는다).

## 14.5 관련 데이터 (DB)

`LS_DATA_AUG`(증강·상태 — 해상도 파생도 `AUG_TYPE_CD='RESL_*'` 로 통합 적재, 부분 유니크 인덱스 `UK_LS_DATA_AUG_RESL` V124), `LS_DATA_AUG_RVW`(검수·`LBL_INTGRT_PCT`·`REJECT_RSN`), `LS_DATA_AUG_LBL_MAP`(원본-증강/해상도 파생 공통 라벨 매핑·`COORD_RECALC_YN`/`SCALE_X`/`SCALE_Y`), **`LS_DATA_AUG_DSCD`**(폐기 원장 — 표식·유예·복구·실삭제 비석, V150). 구 전용 테이블 `LS_RESOLUTION_EXPORT`·`LS_RESOLUTION_LBL_MAP`은 폐기(V125). → [18](18-database.md).

## 14.6 통합 단일 선택 UX (SCR-AUG-001)

증강 요청 화면(`/augment`, REVIEWER)은 처리 종류와 대상 영상을 **각각 1건만** 고르는 단일 선택 흐름이다.

1. **처리 종류 선택** — 카드 4개(겨울/야간/우천/해상도 변경)를 `radiogroup`(로빙 tabindex·화살표 탐색, WCAG 4.1.2)으로 하나만 선택. '해상도 변경' 선택 시에만 타겟 해상도(1080P/720P/480P) 선택 UI 노출. 종류를 바꾸면 타겟 해상도·해상도 결과가 초기화된다.
2. **대상 영상 선택** — 검수 완료(`DATA_STTS_CD=COMPLETED` + `RVW_STTS_CD=APPROVED`) 영상만 라디오로 1건 선택(검색·이벤트 필터·페이징, 페이지 이동 후에도 선택 보존).
3. **생성 조건(프롬프트) 입력 (2026-07-31, 증강 3종 전용)** — 증강 카드 선택 시 `AugmentPromptFieldset` 이 시간대/계절/날씨/지형/심각도 5필드 입력폼을 노출한다. 전부 필수·자유 문자열이며 BE 와 동일 검증(`validateAugmentPrompt`)을 **미리** 수행해 제출 전 400 을 막는다. 입력값이 **외부 생성형 AI 로 그대로 전송**된다는 개인정보 경고를 상시 노출한다. 해상도 변경 카드에는 이 입력폼이 없다(외부 위탁이 아니므로 prompt 개념 자체가 없음).
4. **실행(submit) 시나리오 분기**:
   - 증강 3종(`isAugmentKind`) → `POST /v1/augments/request`(videoIds·types 길이 1 배열) → 성공 시 토스트 + 결과화면(`/augment/result/{jobId}`) 네비게이션. **단건 계약이 정본**이라 2건 이상은 400(`@Size(max=1)`, 서비스도 동일 규칙 fail-closed 재확인)이고, 응답은 요청 개수 echo 가 아니라 **실제 생성 수(`createdCount`)** 를 담는다. **생성 0건은 성공이 아니다** — 프레임 미추출 영상은 412(`PRECONDITION_FAILED` + `data.skippedVideoIds`)로 거부한다(구 동작: 조용히 스킵 후 200 = silent no-op).
   - 해상도 변경 → `POST /v1/videos/{rawSn}/resolution`(presets 전달, 선택) → 성공 시 프리셋별 생성 결과 목록(`derivatives: [{rawSn, goalResCd, targetW, targetH, status}]`) inline 표시(업스케일 포함 정상 처리). 미검수/증강본/전부 스킵은 BE 400, 동일 (원본,해상도) 중복은 409, 전부 실패는 500 → 에러 메시지 노출.
5. **실행 버튼 비활성 조건**: 종류 미선택 · 영상 미선택 · (해상도 종류인데 타겟 해상도 미선택) · (증강 3종인데 프롬프트 5필드 미충족) · 처리 중(`isPending`).
6. **보안**: kind/preset 은 allowlist 상수(`PROCESS_KINDS`/`RESOLUTION_PRESETS`)로만 좁혀 임의 문자열 분기 차단, videoId 는 number, 라우트는 REVIEWER 가드.
7. **결과 화면(SC-023, `/augment/result/:jobId`)**: 진행률 바(§14.4.1 실값, `unavailableReason` 별 안내 문구 분기)·취소 버튼(`AugmentCancelModal`)·항목 축 페이저(§14.3)를 갖춘다. 탭 키는 증강 **종류**가 아니라 **항목 id**(`DATA_AUG_SN`) 기준이라 같은 종류의 다건(중복 요청 결과)을 구분해 볼 수 있다.

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
