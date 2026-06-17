# 09. VLM 시계열 메타

> 출처: CLAUDE.md(VLM 연동·범위 외), R2 KLID-AT-SS-005, 코드(`VlmClient`, `batch/step/VlmTimeseriesStep`, `webhook/VlmResultController`, `meta/`)
> 관련: [07 배치 파이프라인](07-batch-pipeline.md) · [12 검수](12-review-assignment.md)

## 9.1 범위 — 연동만

- **VLM 모델 본체(학습·파인튜닝·프롬프트 관리)는 외부 시스템 책임** (범위 외)
- 저작도구는 **외부 VLM 서비스를 호출해 시계열 정보를 획득하는 연동**만 보유
- `ai-server/app/routers/vlm.py`는 외부 VLM 호출 어댑터 (현재 mock 응답 가능)

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
