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
   ① 선커밋 — 상관키 등록(LS_WEBHOOK_IDEMPOTENCY = ISSUED) + 마킹 PENDING→VLM_REQUESTED
                ※ 둘 다 REQUIRES_NEW 독립 커밋. 제출 <앞>에 수행한다
   ② 논블로킹 제출 — subscribe 만 하고 즉시 반환(status="submitted")
                ※ ACK 왕복조차 기다리지 않는다. 파이프라인은 다음 단계로 계속 진행
   ③ 완료 핸들러 VlmSubmitOutcomeRecorder (전용 풀 vlmSubmitScheduler)
        ACK 수신  → 원장 ISSUED→ACCEPTED + LS_BATCH_PROC_LOG 기록
        제출 실패 → VLM/SKIPPED(사유) 기록 후 재개 대기  ※ 배치·작업 상태는 강등하지 않는다
        ↓ 상세 결과는 별도 콜백
[콜백] POST /v1/vlm/callback
   → VlmResultService 가 LS_DATA_META 적재
   → 검수큐 LS_DATA_META_REVIEW 진입
```

- 마킹 결과(이벤트명 + 영상경로 + marks)를 VLM에 콜백 형태로 전달 → [06](06-marking.md)
- 코드: `VlmClient`, `VlmTimeseriesStep`/`VlmSubmitOutcomeRecorder`, `batch/vlm/{VlmSubmitPendingSweeper,VlmSubmitReclaimTxService}`, `webhook/VlmResultController`/`VlmResultService`

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
| describe **비동기 제출 실패** — 재개 대기 | 완료 핸들러 `onError` | ○ |
| describe **수락 응답·콜백 미수신** — 미결 회수 후 재개 | 스위퍼 **ACK 창** 만료 | ○ |
| describe **결과 콜백 미수신** — 콜백 창 만료 회수 후 재개 | 스위퍼 **콜백 창** 만료 | ○ |

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

- 두 창을 구분할 수 있는 근거는 완료 핸들러가 ACK 수신 시 원장을 `ISSUED → ACCEPTED` 로 전이하기 때문이다. 하나의 임계로 덮으면 describe 분석 중(수십 분)인 정상 위탁을 뺏어 같은 비식별 영상을 중복 위탁한다.
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

## 9.3 메타 검수 (REVIEWER)

화면: 시계열 메타 검토·수정은 **라벨링 캔버스(SC-005) 우측 시계열 메타 패널(`TimeseriesSidePanel`)**에서 수행. (구 `SC-015` VLM 메타 검토 전용 페이지 `/auto/:videoId/meta`는 진입점 없는 orphan으로 2026-06-17 deprecated·코드 제거 → [04 화면·IA](04-screens-ia.md))

- 적재된 시계열 메타(`LS_DATA_META`)를 **REVIEWER가 검토·수정** (라벨링 화면 `useMeta`/`useUpdateMeta`)
- 검수 상태 `LS_DATA_META_REVIEW.RVW_STTS_CD`: PENDING / APPROVED / REJECTED
- 데이터마트 노출은 `RVW_STTS_CD='APPROVED'`만 (`V_COMPLETED_META`) → [18](18-database.md)
- 코드: `meta/MetaController`, FE `features/label/components/TimeseriesSidePanel.tsx`

### 9.3-1 event_annotation 수동입력·검수 (VQA/CoT)

시계열 메타(자연어 텍스트)와 별개로, **이벤트 단위 VQA/CoT** 는 라벨링 캔버스(SC-005) 우측 **'메타' 탭의 `EventAnnotationPanel`**(`TimeseriesSidePanel` 아래)에서 입력·검수한다. INTERNAL 채널만 노출(포털 미노출, ADR-013).

- 조회/저장: `GET`/`PUT /v1/videos/{rawSn}/event-annotation`(WORKER 본인 배정·REVIEWER). 외부 자동 생성값(`LS_EVNT_ANNO.ANNO_CN`)을 폼에 프리필 → 전 필드 수동 덮어쓰기.
- 검수: `POST .../approve`·`.../reject`(REVIEWER). 상태 `LS_EVNT_ANNO_REVIEW.RVW_STTS_CD`: AUTO_GENERATED / PENDING / APPROVED / REJECTED.
- 페이로드: `event_class`(필수)·`question`·`answer` + `caption`/`evidence` 후보 `c1..cn`(caption=caption_text+cot 3단계, evidence=evidence_text+frame_id/obj_id/obj_bbox/obj_label). → [24 §24.3.1](24-dataset-export.md).
- 코드: FE `features/label/components/EventAnnotationPanel.tsx`·`hooks/useEventAnnotation.ts`·`useUpdateEventAnnotation.ts`·`api/eventAnnotation.ts` / BE `evntanno/`(controller·service·entity `LsEvntAnno`/`LsEvntAnnoReview`).

## 9.4 관련 데이터 (DB)

`LS_DATA_META` (`META_KEY`/`META_VL`/`EXTERNAL_JOB_ID`), `LS_DATA_META_HSTRY`(변경 이력), `LS_DATA_META_REVIEW`(검수 상태). event_annotation 은 `LS_EVNT_ANNO`(`ANNO_CN` jsonb)·`LS_EVNT_ANNO_REVIEW`(검수 상태). → [18](18-database.md).

## 9.5 외부 확정 계약 — IntelliVIX Video VLM API v2.0.1

> 원문: `docs/video_vlm_api_ v2.0.1.docx` (IntelliVIX AI연구소, 2026-06-15 "일치도 추가 및 시작/종료 시간 제거"). 비동기 콜백 모델.

**엔드포인트 3종**

| 기능 | Endpoint | 콜백 결과 형식 |
|------|----------|---------------|
| 이벤트 검증 | `POST /v1/videovlm/verify` | `results:{accuracy, description}` (accuracy=일치도) |
| 상황 묘사 | `POST /v1/videovlm/describe` | `results:[{start_sec, end_sec, description}]` (구간 배열) |
| 상태 체크 | `GET /v1/videovlm/status` | `{status:"ready"|"busy"}` |

**요청 규격**: `{request_id, event_type, media:{type(image|video), source_type(path|upload), path, frame_policy:{mode(frame_interval|frame_selected), framerate, selected_frames≤8}}, callback_url}`. 동기응답 `{request_id, status:"accepted"}` → 완료 후 요청의 `callback_url`로 결과 POST. 실패 콜백 `{request_id, status:"failed", error:{code, message}}`.
- `event_type` enum 6종: `fire`·`fall`·`violence`·`flooding`·`car_accident`·`kidnapping`
- video는 `frame_policy` 필수. 추론 1회 최대 8프레임, 초과 시 sliding window(size=stride=8)
- `source_type=upload`은 multipart, `=path`는 파일경로
- 인증 헤더는 규격서에 **미명시**

## 9.6 구현 정합 상태 — ⚠ 미정렬 (계약 확정 전 스텁)

현재 `VlmClient`/`VlmResultService`는 **계약 확정 전 추정으로 작성된 스텁**이라 §9.5 규격과 3계층 모두 어긋난다. `VLM_CLIENT_ENABLED` 기본 `false`라 프로덕션 영향은 없으나, 실연동 전 재정렬 필요.

**연동 차단 블로커 3종**
1. **`callback_url` 미전송** — `VlmTimeseriesStep.java:134`가 `callbackUrl=null`로 요청 → 벤더가 콜백 보낼 대상 없음
2. **동기응답 검증 거부** — 우리는 `externalJobId`(필수)+`status∈{ACCEPTED,QUEUED,...}` 요구, 벤더는 `{request_id, status:"accepted"}`(소문자·externalJobId 없음)
3. **콜백 HMAC 불일치** — 우리 `POST /v1/vlm/result`는 `X-Signature`/`X-Timestamp` 강제, 벤더 규격은 인증 미명시 → 콜백 401 위험

**기타 불일치**: 단일 `/v1/timeseries/submit`(하드코딩 추정) vs verify/describe 2종 / `eventName`(자유) vs `event_type`(enum) / `frame_policy` 미전송 / 콜백 필드 `idempotencyKey·rawSn·vlmMetaItems[{metaKey,metaVal}]` vs `request_id·results{accuracy|start_sec,end_sec,description}`. describe 구간(초 단위) ↔ 우리 `metaKey`(frameIndex) 변환 필요.
