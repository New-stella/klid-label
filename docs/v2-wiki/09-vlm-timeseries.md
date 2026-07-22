# 09. VLM 시계열 메타

> 출처: CLAUDE.md(VLM 연동·범위 외), R2 KLID-AT-SS-005, **외부 확정 계약 `docs/video_vlm_api_ v2.0.1.docx`(IntelliVIX AI연구소, 2026-06-15)**, 코드(`VlmClient`, `batch/step/VlmTimeseriesStep`, `webhook/VlmResultController`, `meta/`)
> 관련: [07 배치 파이프라인](07-batch-pipeline.md) · [12 검수](12-review-assignment.md) · [24 데이터셋 산출](24-dataset-export.md)

> **VQA/CoT 어노테이션 산출 포맷**: VLM이 산출하는 이벤트 단위 VQA/CoT(질문·캡션·사고과정·근거)는 학습데이터 JSON에서 COCO `annotations`(객체 배열)와 분리된 **최상위 `event_annotation` 키**로 표현한다. 필드 정의·조달(VLM / VLM+수동입력)은 [24 데이터셋 산출 §24.3.1](24-dataset-export.md) 참조. **설계 제안·키 변경 가능**.

## 9.1 범위 — 연동만

- **VLM 모델 본체(학습·파인튜닝·프롬프트 관리)는 외부 시스템 책임** (범위 외)
- 저작도구는 **외부 VLM 서비스를 호출해 시계열 정보를 획득하는 연동**만 보유
- 외부 벤더 = **IntelliVIX Video VLM API** (확정 계약 v2.0.1). 아래 §9.5 참조
- `ai-server/app/routers/vlm.py`(`POST /infer/vlm/verify-objects`)는 **내부 객체검증**용으로 이 외부 벤더와 무관(별개 경로, 현재 mock)

## 9.2 연동 흐름

```
[배치] BatchOrchestrator → VlmTimeseriesStep
   동기 호출 (45s 타임아웃, Resilience4j 재시도)
   VLM 서버 즉시 응답 시 파이프라인 다음 단계 진행
        ↓ 상세 결과는 별도 콜백
[콜백] POST /v1/vlm/result
   → VlmResultService 가 LS_DATA_META 적재
   → 검수큐 LS_DATA_META_REVIEW 진입
```

- 마킹 결과(이벤트명 + 영상경로 + marks)를 VLM에 콜백 형태로 전달 → [06](06-marking.md)
- 코드: `VlmClient`, `VlmTimeseriesStep`, `webhook/VlmResultController`/`VlmResultService`

## 9.3 메타 검수 (REVIEWER)

화면: 시계열 메타 검토·수정은 **라벨링 캔버스(SC-005) 우측 시계열 메타 패널(`TimeseriesSidePanel`)**에서 수행. (구 `SC-015` VLM 메타 검토 전용 페이지 `/auto/:videoId/meta`는 진입점 없는 orphan으로 2026-06-17 deprecated·코드 제거 → [04 화면·IA](04-screens-ia.md))

- 적재된 시계열 메타(`LS_DATA_META`)를 **REVIEWER가 검토·수정** (라벨링 화면 `useMeta`/`useUpdateMeta`)
- 검수 상태 `LS_DATA_META_REVIEW.RVW_STTS_CD`: PENDING / APPROVED / REJECTED
- 데이터마트 노출은 `RVW_STTS_CD='APPROVED'`만 (`V_COMPLETED_META`) → [18](18-database.md)
- 코드: `meta/MetaController`, FE `features/label/components/TimeseriesSidePanel.tsx`

## 9.4 관련 데이터 (DB)

`LS_DATA_META` (`META_KEY`/`META_VL`/`EXTERNAL_JOB_ID`), `LS_DATA_META_HSTRY`(변경 이력), `LS_DATA_META_REVIEW`(검수 상태). → [18](18-database.md).

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
