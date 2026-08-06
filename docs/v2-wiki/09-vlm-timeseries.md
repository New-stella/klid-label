# 09. VLM 시계열 메타

> 출처: CLAUDE.md(VLM 연동·범위 외), R2 KLID-AT-SS-005, **외부 확정 계약 `docs/video_vlm_api_ v2.0.1.docx`(IntelliVIX AI연구소, 2026-06-15)**, 코드(`VlmClient`, `batch/step/VlmTimeseriesStep`, `webhook/VlmResultController`, `meta/`)
> 관련: [07 배치 파이프라인](07-batch-pipeline.md) · [12 검수](12-review-assignment.md) · [24 데이터셋 산출](24-dataset-export.md)

> **VQA/CoT 어노테이션(event_annotation) — 확정·구현 완료**: 이벤트 단위 VQA/CoT(질문·캡션·사고과정·근거)는 학습데이터 JSON에서 COCO `annotations`(객체 배열)와 분리된 **최상위 `event_annotation` 키**(후보 `c1..cn`)로 표현한다. 외부 자동 생성값을 프리필하고 라벨링 '메타' 탭 **이벤트 어노테이션 패널**(`EventAnnotationPanel`)에서 WORKER/REVIEWER 가 전 필드를 수동 덮어쓰기, REVIEWER 가 승인/반려한다(§9.3-1). 필드 정의·조달·저장 API 는 [24 데이터셋 산출 §24.3.1](24-dataset-export.md) 참조.

## 9.1 범위 — 연동만

- **VLM 모델 본체(학습·파인튜닝·프롬프트 관리)는 외부 시스템 책임** (범위 외)
- 저작도구는 **외부 VLM 서비스를 호출해 시계열 정보를 획득하는 연동**만 보유
- 외부 벤더 = **IntelliVIX Video VLM API** (확정 계약 v2.0.1). 아래 §9.5 참조
- `ai-server/app/routers/vlm.py`(`POST /infer/vlm/verify-objects`)는 **내부 객체검증**용으로 이 외부 벤더와 무관(별개 경로, 현재 mock)

## 9.2 연동 흐름

```
[배치] BatchOrchestrator → VlmTimeseriesStep
   ① 사전조건 — 검증이벤트유형(LS_DATA_INGEST.VRFC_EVNT_TYPE_CD) 조달·허용목록 판정
                ※ 없거나 목록 밖이면 외부 호출 0 + VLM/SKIPPED(사유) 기록 후 재개 대기
   ② 선커밋 — 상관키 등록(LS_WEBHOOK_IDEMPOTENCY = ISSUED) + 마킹 PENDING→VLM_REQUESTED
                ※ 둘 다 REQUIRES_NEW 독립 커밋. 제출 <앞>에 수행한다
   ③ 논블로킹 제출 — POST /v1/videovlm/verify 를 subscribe 만 하고 즉시 반환(status="submitted")
                ※ ACK 왕복조차 기다리지 않는다. 파이프라인은 다음 단계로 계속 진행
   ④ 완료 핸들러 VlmSubmitOutcomeRecorder (전용 풀 vlmSubmitScheduler)
        ACK 수신  → 원장 ISSUED→ACCEPTED + LS_BATCH_PROC_LOG 기록
        제출 실패 → VLM/SKIPPED(사유) 기록 후 재개 대기  ※ 배치·작업 상태는 강등하지 않는다
        ↓ 상세 결과는 별도 콜백
[콜백] POST /v1/vlm/callback   (results = 단일 객체 {accuracy, description})
   → VlmResultService 가 LS_DATA_META 원자 upsert — vlm.description (+ 있으면 vlm.accuracy)
   → 검수큐 LS_DATA_META_REVIEW 진입은 vlm.description 1건만 (§9.4-1)
   → 마킹 ACTIVE(PENDING|VLM_REQUESTED) → VLM_COMPLETED
```

- 마킹 정보는 **`frame_policy` 로만** 반영된다 → [06](06-marking.md). `rawSn`·`eventName`·`marks` 배열은 벤더 규격 밖이라 요청 바디에 싣지 않는다(구 서술 "이벤트명 + 영상경로 + marks 를 전달" 폐기 — verify 규격 정합)
- 코드: `VlmClient`, `VlmTimeseriesStep`/`VlmSubmitOutcomeRecorder`, `batch/vlm/{VlmSubmitPendingSweeper,VlmSubmitReclaimTxService}`, `webhook/VlmResultController`/`VlmResultService`

### 9.2-0 `frame_policy` 도출 규칙 (마킹 → 요청)

판정 단일 원천은 `VlmTimeseriesStep.buildRequest` 다. 화면·다른 스텝이 재유도하지 않는다.

| 마킹 | `mode` | `selected_frames` | `framerate` |
|------|--------|-------------------|-------------|
| **수동**(`MODE_MANUAL`) + 사용 가능한 프레임 ≥1 | `frame_selected` | 마킹 프레임 인덱스를 **정렬·중복제거**, 벤더 상한 **8** 초과분 절단 | 설정 `vlm.client.frame-policy.framerate`(기본 25) |
| **자동**(`MODE_AUTO`) · 마킹 없음 · 미지 모드 · 수동인데 쓸 프레임 0건 | `frame_interval` | (없음) | `LS_MARKING.FRME_INTV_NOCS`(없거나 ≤0 이면 설정값) |

- ★ **`framerate` 는 "초당 프레임수(FPS)"가 아니라 "몇 프레임당 1장"(추출 간격)** 이다(규격서 §2.1 본문). 그래서 자동 마킹이 싣는 값은 **마킹 프레임 간격 `FRME_INTV_NOCS`** 이고 **`LsMarking.fps` 는 쓰지 않는다** — FPS 를 보내면 벤더가 전혀 다른 간격으로 프레임을 뽑는다. ⚠ 이름만 보고 FPS 로 단정하는 오해가 실제로 한 번 발생한 지점이다.
- `framerate` 는 **mode 무관 필수**(벤더 §3.2)이며 `0` 이면 벤더가 422 를 낸다 — 그래서 폴백이 항상 양수를 보장한다.
- 마킹 JSON(`MARK_CN`) 파싱 실패·프레임 0건은 **예외가 아니라 `frame_interval` 폴백**(fail-secure)이고 WARN 을 남긴다. 요청 조립은 **선커밋 이전**에 끝낸다 — 선커밋 뒤에서 터지면 파이프라인 FAILED + 전량 재실행이 되고 재개 경로(@Async)는 예외가 삼켜져 상관키만 남은 영구 대기가 된다.

### 9.2-1 URL 검증 정책 (운영 엄격 / 개발 완화)

- 기본(운영): `vlm.client.url` 은 **HTTPS 전용 + 사설·내부 대역 차단**(CWE-319/918). 위반 시 빈 생성 실패 → 기동 차단.
- 개발 완화: `vlm.client.allow-insecure-url=true` 일 때만 평문 http + 사설 IP 허용(로컬 목업 `klid-mock-server:9400` 실배선용). **이 플래그는 `local`/`dev` 프로파일에서만 인정**되며, 그 밖의 프로파일에서 켜져 있으면 **기동이 실패**한다(`VlmUrlPolicy` fail-closed). placeholder 호스트·비허용 스키마 차단은 모든 프로파일 공통.
- 판정 로직은 KPST 비식별 연동과 동일한 `ExternalUrlPolicy` 를 공유한다(정책 차이는 "내부망 전제 여부" 값 하나) → [22](22-deid-solution-api.md).

### 9.2-2 단계 미수행(skip) 기록

- `vlm.client.enabled=false` 로 VLM 단계를 건너뛰면 `LS_BATCH_PROC_LOG` 에 `PROC_STEP_CD='VLM'` / `PROC_STTS_CD='SKIPPED'` 감사 행 1건을 **사유와 함께**(`ERR_MSG_CN`) 남긴다.
- 이 감사 행은 append-only 이며 진행 상태 조회에서 제외되므로 다음 단계 전이에 덮이지 않는다 — VLM 비활성/장애 구간에 처리된 영상을 DB 만으로 재처리 대상으로 식별할 수 있다.

**미수행 사유 카탈로그** (`VlmTimeseriesStep` 상수 — 값은 **재개 배선의 키**라 바꾸면 재개가 끊긴다)

| 사유 | 발생 시점 | 재개 대상 |
|------|----------|:--------:|
| VLM 위탁 비활성 (`vlm.client.enabled=false`) | 스텝 진입 즉시 NO-OP | ✕ |
| 비식별 누락 신고 구간 — 위탁 보류(재비식별 대기) | 외부 전송 직전 게이트 | ○ |
| **검증이벤트유형 미수신** (`VRFC_EVNT_TYPE_CD` null) | 사전조건 판정 | ○ |
| **검증이벤트유형 미지원**(허용목록 6종 밖) | 사전조건 판정 | ○ |
| **비동기 제출 실패** — 재개 대기 | 완료 핸들러 `onError` | ○ |
| **수락 응답·콜백 미수신** — 미결 회수 후 재개 | 스위퍼 **ACK 창** 만료 | ○ |
| **결과 콜백 미수신** — 콜백 창 만료 회수 후 재개 | 스위퍼 **콜백 창** 만료 | ○ |

> ⚠ 사유 **문자열 값**에는 아직 `describe` 라는 낱말이 남아 있다(예: `VLM describe 비동기 제출 실패 — 재개 대기`).
> 이 값은 `LS_BATCH_PROC_LOG.ERR_MSG_CN` 에 **적재된 뒤 재개 판정의 키로 다시 읽히므로**, 문구를 다듬으면
> 이미 쌓인 행이 `RESUMABLE_SKIP_REASONS` 매칭에서 탈락해 **재개가 끊긴다**. 의도적으로 그대로 둔 레거시 값이다.

- 재개 판정의 단일 원천은 `VlmTimeseriesStep.RESUMABLE_SKIP_REASONS` 이며, 재개는 `VlmWithheldResumeRunner` 가 **멱등 조건(시계열 메타 0건)** 으로 수행한다.
- 제출 실패를 배치 재시도 큐로 넘기지 않는 이유: 재시도 큐는 rawSn 단위로 파이프라인 **전체**를 재실행하므로, VLM 제출 1건 실패에 프레임추출·YOLO·SAM2 가 전부 다시 돈다.

### 9.2-3 논블로킹 제출 · 미결 회수 (2026-07-30)

프로토콜은 원래부터 비동기였으나(ACK 만 받고 결과는 콜백) **ACK 왕복 동안 스레드를 점유**했다(`.block()`). 그 스레드는 `batch-async-`(core 2)·Quartz 워커·수동 재처리의 Tomcat 요청 스레드였다. 이제 **ACK 도 기다리지 않는다**.

- **선커밋이 제출 앞에 온다** — 상관키 등록(`ledger.recordIssued`)과 마킹 `PENDING→VLM_REQUESTED`(`VlmMarkingTxService`, REQUIRES_NEW)를 제출 **전에** 각각 독립 커밋한다. 콜백이 ACK 보다 먼저 도착해도 역조회·전이가 성립한다(**콜백 선행 레이스** 폐쇄).
- **수신부 조회 범위 확대(양단 방어)** — `VlmResultService` 가 전이 대상을 `VLM_REQUESTED` 단독에서 `LsMarking.ACTIVE_STATUSES`(PENDING + VLM_REQUESTED)로 넓혔다(`findByRawSnAndSttsCdIn`). 종결 상태(VLM_COMPLETED/VLM_FAILED)는 포함하지 않는다(역행 금지). 단 **위탁 발급 시각(원장 REG_DT) 이후에 새로 생성된 PENDING 마킹은 제외**한다 — 이전 위탁의 지각 콜백이 아직 위탁된 적 없는 새 마킹을 완료/실패로 만들지 않게 한다. 발급 시각을 알 수 없으면(구 원장 행) 종전대로 전부 대상.
- **완료 핸들러는 상태를 강등하지 않는다** — 제출 실패가 파이프라인 스레드 밖에서 오므로 `BatchOrchestrator` FAILED + 재시도 큐 사슬을 되살리지 않는다(rawSn 단위 전량 재실행이 되기 때문). 대신 `LS_BATCH_PROC_LOG` 에 `VLM/SKIPPED` + 사유를 남기고 재개(`VlmWithheldResumeRunner`, 멱등 조건 = 시계열 메타 0건)로 회수한다.
- **동기 실패 전파가 남는 것은 제출 이전 사전 조건뿐** — rawSn null · 영상 미존재 · 비식별 경로 부재 · 상관키 등록 실패.

**미결 회수 스위퍼 `VlmSubmitPendingSweeper`** — 노드 사망·재기동으로 in-flight subscription 이 사라지면 어떤 신호도 오지 않는다(실패 행이 없어 재시도 큐·회수기도 집지 못함). 이 스윕이 유일한 회수 경로이며 **창을 둘로 나눈다**.

| 창 | 원장 상태 | 임계(기본) | 회수 사유 코드 |
|----|:--------:|:---------:|---------------|
| **ACK 창** — 수락 응답조차 관측 못 함 | `ISSUED` | 30분(하한 clamp 10분) | `VLM describe 수락 응답·콜백 미수신 — 미결 회수 후 재개` |
| **콜백 창** — 수락은 받았고 결과만 안 옴 | `ACCEPTED` | 360분(하한 clamp 60분) | `VLM describe 결과 콜백 미수신 — 콜백 창 만료 회수 후 재개` |

- 두 창을 구분할 수 있는 근거는 완료 핸들러가 ACK 수신 시 원장을 `ISSUED → ACCEPTED` 로 전이하기 때문이다. 하나의 임계로 덮으면 분석 중(수십 분)인 정상 위탁을 뺏어 같은 비식별 영상을 중복 위탁한다.
- 2노드 Active-Active 정합은 **조건부 원자 UPDATE 클레임**(`VlmSubmitReclaimTxService.claim`/`claimAccepted`)이 담당한다 — Quartz 클러스터링은 트리거 중복만 막는다.
- 무한 재위탁 방지로 영상당 **회수 예산**(`max-reclaims`, 기본 3)을 두고 초과 시 기록만 남기고 재개하지 않는다. **상태 강등은 하지 않는다**.
- `@Scheduled` 가 아니라 **데몬 스레드 1개짜리 전용 스케줄러**로 돈다(`BatchRetryStaleReclaimSweeper`·`AugmentJobExpirySweeper` 동형) — 무조건적 `@EnableScheduling` 이 게이팅 없는 남의 잡까지 깨우는 것을 피한다. 자기 토글만 본다.

**설정 키** (전부 `application.yml` 미기재 — 코드 `@Value` 기본값. 필요 시 환경별 yml/환경변수로 override)

| 키 | 기본값 | 설명 |
|----|:-----:|------|
| `authoring.batch.vlm.submit-reclaim.enabled` | `true` | 미결 회수 스윕 on/off |
| `authoring.batch.vlm.submit-reclaim.interval-ms` | `900000` | 스윕 주기(하한 60,000ms) |
| `authoring.batch.vlm.submit-reclaim.initial-delay-ms` | `300000` | 기동 후 첫 스윕 지연 |
| `authoring.batch.vlm.submit-reclaim.stale-timeout-minutes` | `30` | **ACK 창** 임계(하한 10) |
| `authoring.batch.vlm.submit-reclaim.callback-timeout-minutes` | `360` | **콜백 창** 임계(하한 60) |
| `authoring.batch.vlm.submit-reclaim.batch-size` | `50` | tick 당 회수 상한(CWE-770) |
| `authoring.batch.vlm.submit-reclaim.max-reclaims` | `3` | 영상당 회수 예산 |

> 외부 호출 자체의 타임아웃은 `vlm.client.timeout-seconds`(기본 **10초**, WebClient `.timeout()` 단일 출처) + Resilience4j `vlmClient` 재시도(3회·1s·×2) / 서킷브레이커다. 논블로킹 전환 이후에도 이 값들은 그대로이며, 달라진 것은 **그 왕복을 어느 스레드도 기다리지 않는다**는 점이다.

## 9.3 메타 검토·수정

화면: 시계열 메타 검토·수정은 **라벨링 캔버스(SC-005) 우측 '메타' 탭의 시계열 메타 패널(`TimeseriesSidePanel`)**에서 수행. (구 `SC-015` VLM 메타 검토 전용 페이지 `/auto/:videoId/meta`는 진입점 없는 orphan으로 2026-06-17 deprecated·코드 제거 → [04 화면·IA](04-screens-ia.md))

**★ 이 패널은 텍스트 수정만 제공한다 — 승인/반려 UI 는 없다 (2026-08-03 사용자 확정, 구속)**

| 항목 | 동작 |
|------|------|
| 제공 | 적재된 시계열 메타(`LS_DATA_META`) 텍스트 조회·수정·저장 (`useMeta`/`useUpdateMeta` → `GET`/`PUT /v1/frames/{srcSn}/meta`) |
| 미제공 | 검토 상태 배지, 승인/반려 버튼, 반려 사유 입력 — **FE 진입점 없음** |
| 검토 상태 확정 | 영상 검수 승인(`APPROVED`) 시 BE 가 자동 동결 (`MetaService.autoApproveOnVideoApproval`, `ReviewService.approve`에서 호출) |
| 읽기 표시 | 검수 화면(SC-006)의 읽기 전용 패널 `features/review/components/ReviewMetaPanel.tsx` 는 상태 배지를 계속 표시 |

**★ 패널은 세 부류를 다르게 렌더한다 (2026-08-06, verify 전환 반영 — R8/R9)**

| 부류 | 예 | 화면 | 저장 payload |
|------|----|------|:------------:|
| **편집 슬롯** | `vlm.description`(서술 전문) · `manual-timeseries`(수동 등록) | 편집 가능 textarea(라벨 "시계열 서술" / "시계열 메타", `maxLength=2000`) | 편집한 것만 포함 |
| **레거시 구간행** | `0-8` · `8-16` … (구 describe 산출물) | **읽기 전용 병기** — 삭제·숨김하지 않는다 | 미포함 |
| **읽기 전용 메타** | `vlm.accuracy`(일치도) 등 BE `readOnlyMeta` | 읽기 전용(라벨 "일치도", 값은 백분율 환산 `0.92`→`92%`) | 미포함 |

- **편집 단위 = 저장 단위(`metaKey`)** — 여러 키를 하나의 textarea 로 합치지 않는다. 합치면 편집분을 키로 되돌릴 수 없어 **저장해도 아무것도 안 바뀌고 성공 토스트만 뜨는 조용한 무동작**이 된다(2026-08-03 실사고). verify 전환 후에도 레거시 구간이 남은 영상이 있으므로 이 원칙은 유효하다.
- **편집 여부는 알려진 키 화이트리스트**(`EDITABLE_META_KEYS`)로 판정하고 접두 문자열을 파싱하지 않는다 — BE `MetaService` 의 fail-closed 방향과 대칭이며, 새 자동 생성 키가 늘어도 편집 슬롯·저장 payload 로 새지 않는다.
- **레거시 구간은 BE 계약상 편집 가능**(`items`)하지만 화면이 편집 동선을 주지 않는다. 값 보존은 확정 정책이라 **표시는 반드시 유지**한다. 정렬은 `start_sec` **숫자** 오름차순 — 문자열 정렬이면 구간이 10개를 넘는 순간 `10-18` 이 `8-16` 앞으로 와 시간축이 깨진다.
- **편집 가능한 항목이 하나도 없으면**(메타 0건 / 레거시 구간뿐) 신규 등록 슬롯(`manual-timeseries`) 1개를 제공한다 — 레거시 영상에서도 전문을 작성할 수 있고, 그 저장이 레거시 구간 값을 덮지 않는다.
- **읽기 전용 목록은 미지의 키도 일반적으로 렌더**한다(BE 가 fail-closed 로 늘릴 수 있다). 라벨은 알려진 키만 한국어로 매핑하고 나머지는 내부 네임스페이스 접두(`vlm.`)만 떼어 표시한다 — 화면 문구에 모델명·기술 용어를 노출하지 않는다.
- **검수 화면(`ReviewMetaPanel`)도 일치도를 읽기 전용으로 표시**한다('참고 정보' 섹션). 검수자가 서술의 신뢰도를 판단할 근거이며, 라벨·값 표기는 라벨링 패널과 **같은 판정기**(`features/auto/metaKeys.ts`)를 재사용한다(복제 금지 — 복제하면 한쪽만 갱신돼 어긋난다).
- 코드: FE `features/auto/metaKeys.ts`(판정 단일 원천) · `features/auto/api.ts`(`toFrameMeta`) · `features/label/components/TimeseriesSidePanel.tsx` · `features/review/components/ReviewMetaPanel.tsx`

- **결정 근거**: SFR-08 의 "시계열 메타 검토·수정" 요구는 **라벨링/검수 화면에서 메타 텍스트를 직접 수정할 수 있는 것**으로 충족된다. 반려(REJECT)로 특정 메타를 배제하는 동선은 화면에서 제공하지 않는다. 실사용상 승인 완료 영상은 전부 `APPROVED` 라 "검토 상태 승인됨" 줄만 메타 개수만큼 반복돼 정보가치가 0이었고, `metaKey` 를 표시하지 않아 어느 메타의 상태인지 식별조차 불가능했다.
- **BE 는 존치**: `POST /v1/meta/{metaReviewSn}/approve|reject`(`MetaController`/`MetaService`)와 `LS_DATA_META_REVIEW` 테이블은 그대로 유지한다. 호출하는 FE 진입점만 없다(FE 클라이언트 `approveMetaReview`/`rejectMetaReview` 및 훅 `useMetaReview` 는 제거).
- 검수 상태 `LS_DATA_META_REVIEW.RVW_STTS_CD`: AUTO_GENERATED / PENDING / APPROVED / REJECTED
- 데이터마트 노출은 `RVW_STTS_CD='APPROVED'`만 (`V_COMPLETED_META`) → [18](18-database.md). 자동 동결이 이 게이트를 통과시키는 유일한 경로다.
- 코드: BE `meta/MetaController`·`meta/service/MetaService` / FE `features/label/components/TimeseriesSidePanel.tsx`
- 회귀 가드: `features/label/components/__tests__/TimeseriesSidePanel.test.tsx`(검토행이 있는 메타를 REVIEWER 로 렌더해도 상태 배지·승인/반려 버튼 미노출)

### 9.3-1 event_annotation 수동입력·검수 (VQA/CoT)

시계열 메타(자연어 텍스트)와 별개로, **이벤트 단위 VQA/CoT** 는 라벨링 캔버스(SC-005) 우측 **'메타' 탭의 `EventAnnotationPanel`**(`TimeseriesSidePanel` 아래)에서 입력·검수한다. INTERNAL 채널만 노출(포털 미노출, ADR-013).

- 조회/저장: `GET`/`PUT /v1/videos/{rawSn}/event-annotation`(WORKER 본인 배정·REVIEWER). 외부 자동 생성값(`LS_EVNT_ANNO.ANNO_CN`)을 폼에 프리필 → 전 필드 수동 덮어쓰기.
- 검수: `POST .../approve`·`.../reject`(REVIEWER). 상태 `LS_EVNT_ANNO_REVIEW.RVW_STTS_CD`: AUTO_GENERATED / PENDING / APPROVED / REJECTED.
- 페이로드: `event_class`(필수)·`question`·`answer` + `caption`/`evidence` 후보 `c1..cn`(caption=caption_text+cot 3단계, evidence=evidence_text+frame_id/obj_id/obj_bbox/obj_label). → [24 §24.3.1](24-dataset-export.md).
- 코드: FE `features/label/components/EventAnnotationPanel.tsx`·`hooks/useEventAnnotation.ts`·`useUpdateEventAnnotation.ts`·`api/eventAnnotation.ts` / BE `evntanno/`(controller·service·entity `LsEvntAnno`/`LsEvntAnnoReview`).

## 9.4 관련 데이터 (DB)

`LS_DATA_META` (`META_KEY`/`META_VL`/`EXTERNAL_JOB_ID`), `LS_DATA_META_HSTRY`(변경 이력), `LS_DATA_META_REVIEW`(검수 상태). event_annotation 은 `LS_EVNT_ANNO`(`ANNO_CN` jsonb)·`LS_EVNT_ANNO_REVIEW`(검수 상태). → [18](18-database.md).

### 9.4-1 metaKey 규격 (verify 콜백 적재)

`LS_DATA_META` 스키마는 **무변경**이다 — K/V + `(RAW_SN, META_KEY)` UK 가 아래를 그대로 수용한다.

| metaKey | 값 | 검수큐 진입 | 조회 응답 목록 | 수정(`PUT`) |
|---------|----|:----------:|------|------|
| `vlm.description` | verify 서술 전문(≤2000, `META_VL` 길이와 동일) | ✅ PENDING 1건 | `items` | 가능 |
| `vlm.accuracy` | 일치도 `0~1` 문자열(경계 포함). 콜백에 없으면 **행을 만들지 않는다** | ❌ | **`readOnlyMeta`** | **400** |
| (레거시) `0-8`·`8-16` … | 구 describe 구간 서술 | 기존 유지 | `items` | 가능 |
| `manual-timeseries` | 편집 가능한 항목이 하나도 없는 영상(메타 0건 **또는 레거시 구간뿐**)의 FE 수동 등록 슬롯 — **사람이 직접 쓴 상황묘사 전문** | 신규 시 PENDING | `items` | 가능 |
| `video.*` | ffprobe/관제 인입 기술메타 (`VideoMetaService` 소유) | ❌ | `technicalMeta` | 400 |

**조회 응답 계약 — `GET /v1/frames/{srcSn}/meta` (2026-08-06)**

응답은 `items`(편집 가능) / `technicalMeta`(`video.*`) / **`readOnlyMeta`(신설 — 화면 전용 읽기)** 세 목록이며
셋 다 항상 배열이다(0건이면 빈 배열). **기존 `items`·`technicalMeta` 및 각 항목 필드
(`metaSn`/`metaKey`/`metaVal`/`dataMetaReviewSn`/`reviewStatus`)의 이름·타입·시맨틱은 불변**이고 `readOnlyMeta`
가 **추가**됐을 뿐이다 — 단 `vlm.accuracy` 는 `items` 에서 빠진다(의도된 계약 변경).

- **읽기 전용 판정은 `vlm.*` 네임스페이스 안의 화이트리스트**다 — `vlm.description` 만 편집 가능하고 그 밖의
  `vlm.*` 는 전부 읽기 전용(fail-closed). 판정 범위를 접두 안으로 한정하므로 **레거시 구간 키와
  `manual-timeseries` 는 영향을 받지 않고 계속 편집 가능**하다.
- **판정 술어의 소유자는 `MetaService` 하나**다(`video.*` 는 `VideoMetaService.isTechnicalKey`). 접두·키 문자열을
  DTO·컨트롤러·FE 로 복제하지 않는다 — 소유자가 키를 늘릴 때 조용히 드리프트한다.
- **저장 경로도 서버가 직접 막는다** — 조회에서 빼는 것에 의존하지 않는다(클라이언트가 임의 payload 를 보낼 수
  있다, CWE-20/915). 거부는 `video.*` 와 **같은 400**이며 메시지에 요청받은 키를 echo 하지 않는다(CWE-117/209).

- **FE 는 이 분류를 재해석하지 않는다** — 어댑터(`features/auto/api.ts` `toFrameMeta`)가 세 목록을 그대로 실어
  나르고(누락 응답은 빈 배열), 화면 렌더 규칙은 §9.3 참조. 편집 슬롯 판정만 FE 화이트리스트
  (`features/auto/metaKeys.ts`)가 별도로 갖는데, 이는 BE 판정을 복제한 것이 아니라 **BE 가 편집을 허용하는
  레거시 구간 키를 화면에서만 더 좁히는** 것이다(R9 보존 정책).

> ⚠ 구 서술 **"`vlm.accuracy` 도 편집·저장이 가능하다(거부 목록은 `video.*` 뿐)" 는 폐기**(2026-08-06).
>
> ⚠ 구 서술 **"남은 갭(FE — Phase 6): 어댑터가 `readOnlyMeta` 를 읽지 않아 일치도가 화면에서 사라진 상태" 는
> 해소**(2026-08-06 Phase 6 — 어댑터 수용 + 라벨링·검수 양 화면 읽기 전용 렌더).

**적재 (콜백 경로)**

- **콜백 적재 경로의 검수큐 진입은 화이트리스트**다 — `VlmResultService` 는 `vlm.description` 에만 검토행을 만든다. "description 이 아닌 건 전부 제외"가 fail-closed 라 향후 `vlm.*` 키가 늘어도 검수큐·데이터마트 뷰로 새지 않는다. ⚠ **사람이 `PUT` 으로 신규 등록하는 키(`manual-timeseries` 등)는 별개 경로**이며 `MetaService` 가 VLM 경로와 동일하게 PENDING 검토행을 만든다(위 표의 "신규 시 PENDING") — 두 경로를 하나로 읽지 말 것.
- 적재는 `LsDataMetaRepository.upsertMeta`(PostgreSQL `ON CONFLICT`) **원자 upsert** 다. 재위탁 동시 콜백은 `request_id` 가 달라 원장 비관적 락이 걸리지 않으므로 find-then-save 로는 UNIQUE 위반·값 유실이 난다(CWE-362).
- 레거시 구간 키는 metaKey 가 달라 UK 충돌이 없으므로 신규 2키가 그대로 **추가**된다.

**★ export 조달 (2026-08-06, @req R10)**: 학습데이터 export JSON 의 `video.vd_description` 은 **① `vlm.description` → ② `manual-timeseries`(사람이 직접 쓴 전문) → ③ 없으면 레거시 구간 행을 `start_sec` 오름차순 이어붙임 → ④ 없으면 `null`** 로 조달된다(판정 단일 원천 `VlmDescriptionPolicy`, 상세는 [24 §24.4.3](24-dataset-export.md)). `vlm.accuracy` 는 **export 에 넣지 않는다**(아래 R12).

> ⚠ **구 서술 "② 없으면 레거시 구간" (즉 `manual-timeseries` 는 조달 무관 키) 는 폐기**(2026-08-06). 위 표대로 그 슬롯은 **편집 가능한 항목이 하나도 없을 때** 뜨는데, 그 조건에는 **레거시 구간뿐인 영상도 포함**된다 — 구 조달은 사람이 방금 쓴 전문을 무시하고 **편집조차 불가능한 옛 구간 이어붙임**을 대신 내보냈다(조용한 손실).
>
> 그 결과 **`manual-timeseries` 수정도 export 재생성 대상**이다(`VlmDescriptionPolicy.participates` 가 참). 다만 조달 참여 키라도 **값이 실제로 바뀌어야** 재생성한다 — 무변경 저장이 `v2·v3·v4…` 를 적층하던 결함(CWE-770)의 차단이며, 무변경 저장 자체는 **200 성공 + 통지 발행**이고 재생성 플래그만 생략된다([24 §24.4.3](24-dataset-export.md)).

**★ R12 — `accuracy` 는 화면 전용이다 (구속)**

검수큐에 넣지 않으므로 `V_COMPLETED_META`(리뷰행 INNER JOIN + `RVW_STTS_CD='APPROVED'`)에 **구조적으로 도달하지 않는다**. 학습데이터 export JSON 에도 넣지 않는다. **이것은 의도된 설계이며 "마트에 안 나온다"를 결함으로 보고하거나 뷰를 고치지 않는다.**

> ⚠ **알려진 관찰(결함 아님) — 관제 pull API 는 다른 채널이다**: 관제가 통지 수신 후 호출하는 조회 API(`TaskQueryService.getMeta`)는 `LS_DATA_META` **전 행**을 반환해 `vlm.accuracy`·`video.*` 가 포함된다. R12 가 규정한 축은 **검수큐 → `V_COMPLETED_META` → export** 이고 이 pull 은 그 축이 아니며, `video.*` 도 이전부터 그랬다. 좁히려면 **관제 계약 협의가 선행**돼야 하므로 임의로 필터를 넣지 않는다.

**★ R13 — 승인 영상의 서술이 갱신되면 재검수 + 통지 (구속)**

검수 완료(`APPROVED`) 영상이 재위탁돼 `vlm.description` 이 **실제로 달라진 경우에만**:

1. 해당 검토행을 `PENDING` 으로 되돌려 재승인을 강제한다 (`LsDataMetaReview.reopenForRecheck`).
2. `TaskModifiedEvent(META_UPDATED, srcSn=null, **exportRegenerated=true**)` 를 발행한다 — 발행 방식·페이로드는 `MetaService.update` 와 동일하며 소비는 `TaskModifiedAccumulateListener`(AFTER_COMMIT)다.

> ⚠ 구 서술 **"`exportRegenerated=false`(디스크 산출물은 그대로)" 는 폐기**(2026-08-06, @req R10) — 서술이 export JSON 의 `video.vd_description` **입력**이 되면서 디스크가 실제로 달라진다. `false` 로 두면 통지만 나가고 산출 폴더는 **옛 서술로 고착**된다. 이 트리거는 콘텐츠 해시 편입(`LabelContentHasher` `VDSC` 블록)과 **정합 세트**이나, ⚠ **현재 배포 형상에서 실제로 일하는 쪽은 이 트리거 하나**다(해시는 `force=false` 진입 경로가 0건이라 아무것도 게이트하지 않는다 — [24 §24.4.3](24-dataset-export.md)). 무변경 폭주를 막는 것은 해시가 아니라 **값 비교 가드**(`applyResults` 의 `changed`)다.

- **근거**: 뷰가 **라이브** `LS_DATA_META` 를 조인하므로, 값만 갱신하고 상태를 `APPROVED` 로 두면 REVIEWER 가 한 번도 보지 않은 새 서술이 그대로 관제로 나간다. CLAUDE.md 의 "검수 완료 후 수정 시마다 `TASK_MODIFIED` 통지" 규칙도 이 경로만 위반하고 있었다.
- **값이 같으면 아무것도 하지 않는다(멱등)** — 재검수·통지 폭주 방지. **신규 적재도 대상이 아니다**(이미 PENDING 으로 새로 들어간다). **미승인 영상도 대상이 아니다**(되돌릴 확정이 없고 검수 전 갱신은 통지 대상이 아니다).
- `REJECTED` 검토행도 되돌린다 — 반려 판단은 *바뀌기 전 본문*에 대한 것이라 새 본문에 적용되지 않는다(`autoApproveOnVideoApproval` 의 "반려 존중"은 본문이 그대로일 때의 규칙이다).
- 회귀 가드: `VlmResultServiceTest`(R13 5건 · mutation 실증 완료) · `VlmVerifyCallbackFlowIntegrationTest` · `VlmMarkingTransitionPersistenceIntegrationTest`

## 9.5 외부 확정 계약 — IntelliVIX Video VLM API v2.0.1

> 원문: `docs/video_vlm_api_ v2.0.1.docx` (IntelliVIX AI연구소, 2026-06-15 "일치도 추가 및 시작/종료 시간 제거"). 비동기 콜백 모델.

**엔드포인트 3종**

| 기능 | Endpoint | 콜백 결과 형식 | 우리 사용 |
|------|----------|---------------|:--------:|
| 이벤트 검증 | `POST /v1/videovlm/verify` | `results:{accuracy, description}` (**단일 객체**, accuracy=일치도) | **○ 유일 사용** |
| 상황 묘사 | `POST /v1/videovlm/describe` | `results:[{start_sec, end_sec, description}]` (구간 배열) | ✕ (미사용) |
| 상태 체크 | `GET /v1/videovlm/status` | `{status:"ready"|"busy"}` | ✕ |

> **우리는 `verify` 하나만 쓴다.** 관제가 인입으로 보내주는 검증이벤트유형(`LS_DATA_INGEST.VRFC_EVNT_TYPE_CD`)에 대해
> "그 이벤트가 실제로 있었는가(일치도)와 무엇이 보이는가(서술)"를 받는 것이 시계열 메타의 용도이기 때문이다.
> 따라서 **콜백의 `results` 는 배열이 아니라 객체**이며, 구 describe 배열 규격은 폐기됐다.

**요청 규격**: `{request_id, event_type, media:{type(image|video), source_type(path|upload), path, frame_policy:{mode(frame_interval|frame_selected), framerate, selected_frames≤8}}, callback_url}`. 동기응답 `{request_id, status:"accepted"}` → 완료 후 요청의 `callback_url`로 결과 POST. 실패 콜백 `{request_id, status:"failed", error:{code, message}}`.
- `event_type` enum 6종: `fire`·`fall`·`violence`·`flooding`·`car_accident`·`kidnapping` (우리 쪽 조달·거부는 §9.2-2 · 단일 원천 `LsDataIngest.VRFC_EVNT_TYPES`)
- video는 `frame_policy` 필수. 추론 1회 최대 8프레임, 초과 시 sliding window(size=stride=8). **우리가 어떤 값을 싣는지는 §9.2-0**
- `source_type=upload`은 multipart, `=path`는 파일경로
- 인증 헤더는 규격서에 **미명시**

## 9.6 구현 정합 상태 — ✅ 정렬 완료 (2026-08-06)

> **구 서술 폐기** — 이 절은 "계약 확정 전 추정 스텁이라 3계층 모두 어긋난다(연동 차단 블로커 3종)"였다.
> 그 블로커 3종은 아래 표대로 **전부 해소**됐으므로 구 서술은 더 이상 유효하지 않다.

| 구 블로커 | 현재 |
|-----------|------|
| `callback_url` 미전송 → 벤더가 콜백 보낼 대상 없음 | **해소** — 고정 base URL + `HmacWebhookFilter.PATH_VLM` 로 조립해 전송(사용자 입력 미반영, SSRF 차단) |
| 동기응답 검증 거부(우리 `externalJobId` 필수 요구 vs 벤더 `{request_id, status:"accepted"}`) | **해소** — `VlmClient` 가 `request_id` echo 일치 + `status="accepted"` 만 검증 |
| 콜백 HMAC 강제(`POST /v1/vlm/result`) → 벤더 미서명이라 401 위험 | **해소** — 경로 `POST /v1/vlm/callback` **무서명**, 대신 IP allowlist + rate limit/size cap + **`request_id` 발급 게이트** 3계층(§9.2, [03 인증·역할](03-auth-roles.md)) |
| 단일 `/v1/timeseries/submit`(추정) | **해소** — `POST /v1/videovlm/verify` |
| `eventName`(자유 문자열) | **해소** — `event_type` enum 6종. 조달처는 관제 인입 `LS_DATA_INGEST.VRFC_EVNT_TYPE_CD`(우리가 매핑표를 만들지 않는다), 허용목록 밖·미수신은 외부 호출 없이 SKIPPED |
| `frame_policy` 미전송 | **해소** — `mode`/`framerate`(+`selected_frames`≤8)를 **마킹에서 도출**해 전송(도출 규칙 표 = **§9.2-0**). `framerate` 는 "초당 프레임수"가 아니라 **추출 간격**이라 자동 마킹의 `LS_MARKING.FRME_INTV_NOCS` 가 대응값이다 |
| 콜백 필드 `idempotencyKey·rawSn·vlmMetaItems[]` | **해소** — `request_id`·`results{accuracy, description}`. rawSn 은 바디에 없고 `request_id` 로 역조회 |
| describe 구간(초) ↔ `metaKey`(frameIndex) 변환 필요 | **소멸** — verify 는 구간 개념이 없다. metaKey 는 `vlm.description`/`vlm.accuracy` 고정(§9.4-1) |

**남은 미확정**: IntelliVIX **실서버**(목 아님) 대조. 현재 정합 근거는 규격서 원문 + `klid-mock-server` 왕복이며,
실 벤더 서버와의 완전 일치(오류코드 카탈로그·재시도 정책 등)는 실연동 시점에 확인한다 → `docs/test-cases/UNCERTAINTIES.md` #13.

**과도기 방어**: 벤더가 구 describe **배열**을 계속 보내면 Jackson 이 400 으로 거부한다(관대한 파싱은 두지 않는다 —
무단 하위호환은 벤더 버그를 숨긴다). 다만 전역 400 메시지만으로는 원인이 보이지 않아, `VlmResultController` 가
그 엔드포인트에 한해 **구조 힌트**("results 가 배열입니다 — 신규 규격은 객체")를 서버 로그에 남긴다.
요청 바디 원문은 남기지 않는다(CWE-117/359). 응답 계약은 전역 핸들러와 동일하다(CWE-209).
