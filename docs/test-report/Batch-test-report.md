# Batch (Phase 5) 테스트 결과

> 테스트 결과: 46/46 통과 (Phase 5 신규)
> 누적: 112/112 통과 (Phase 0~5 backend 전체)

## API 테스트 결과 (Controller)

### 1. REVIEWER가_GET_batch_status_호출시_최근_N건_반환
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/api/v1/batch/status?limit=10` |
| Status | `200 OK` |
| Auth | `Bearer {REVIEWER JWT}` |
| Response Body | `{ "success": true, "data": { "items": [...] } }` |
| 결과 | PASS |

### 2. WORKER도_GET_batch_status_호출_가능
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/api/v1/batch/status` |
| Status | `200 OK` |
| Auth | `Bearer {WORKER JWT}` |
| 결과 | PASS |

### 3. PORTAL_USER는_GET_batch_status_호출시_403
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/api/v1/batch/status` |
| Status | `403 Forbidden` |
| Auth | `Bearer {PORTAL_USER JWT}` |
| 결과 | PASS |

### 4. 인증_없으면_401
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/api/v1/batch/status` |
| Status | `401 Unauthorized` |
| 결과 | PASS |

### 5. limit_상한_초과시_400
| 항목 | 내용 |
|------|------|
| Method | `GET` |
| URL | `/api/v1/batch/status?limit=501` |
| Status | `400 Bad Request` |
| 결과 | PASS |

## 단위 테스트 결과

### BatchOrchestratorTest (10건)
- ANONY_영상은_DeidentifyStep_skip — PASS
- PRVC_영상은_DeidentifyStep_호출 — PASS
- PSDO_영상도_DeidentifyStep_호출 — PASS
- 비식별_API_500_응답시_FAILED_+_재시도큐_등록 — PASS
- YOLO_단계_정상_처리시_COMPLETED_상태_전이 — PASS
- VLM_단계_실패시_이전_단계는_커밋되고_FAILED_고정 — PASS
- 최대_3회_재시도_후_FAILED_상태_고정_큐_재등록_거부 — PASS
- rawSn_null이면_INVALID_INPUT — PASS
- 프레임_추출_결과_0건이면_FAILED_+_이후_단계_미호출 — PASS
- 성공_시_재시도큐_clear_호출 — PASS

### LsDataLblTest (6건)
- createAutoBbox는_AUTO_LBL_YN_Y_+_BBOX_타입_저장 — PASS
- createAutoPolygon은_POLYGON_타입_+_AUTO_LBL_YN_Y — PASS
- CONF_SCORE는_0_미만이면_IllegalArgumentException — PASS
- CONF_SCORE는_1_초과면_IllegalArgumentException — PASS
- CONF_SCORE는_0_과_1_경계값_허용 — PASS
- updateConfScore는_새_값으로_갱신_+_범위_검증 — PASS

### BatchRetryQueueTest (6건)
- 최초_enqueue는_attempt_1_+_재시도_가능 — PASS
- max_attempts_3_초과시_4번째_enqueue는_거부 — PASS
- clear_호출시_retry_메타_제거 — PASS
- pollReady_즉시_호출시_빈_옵셔널_반환 — PASS
- 초기지연_0초_설정시_즉시_pollReady_가능 — PASS
- 동시_enqueueIfRetryable_호출_안전성_AtomicInteger — PASS

### BatchStatusServiceTest (5건)
- markStage_후_currentStage_조회_가능 — PASS
- markFailed_시_retryCount_증가_+_errorMessage_저장 — PASS
- recent는_lastUpdatedAt_DESC_정렬_+_limit_적용 — PASS
- 동시_markStage_호출_안전성_검증_ConcurrentHashMap — PASS
- limit_0_이하는_1로_보정_+_상한_초과는_MAX_ENTRIES로_보정 — PASS

### FfmpegFrameExtractorTest (6건)
- 1분당_1프레임_추출_300초_영상은_5프레임 — PASS
- 60초_미만_영상도_최소_1프레임_추출 — PASS
- durationSec_0_이하면_INVALID_INPUT — PASS
- 존재하지_않는_파일_경로면_INVALID_INPUT — PASS
- manifest_jsonl_파일이_생성됨 — PASS
- computeFrameCount_경계값 — PASS

### YoloAutolabelStepTest (4건)
- YOLO_검출_결과는_AUTO_LBL_YN_Y_+_BBOX_타입_+_score_0_1_저장 — PASS
- YOLO_검출_결과_없으면_라벨_미저장 — PASS
- rawSn_null이면_INVALID_INPUT — PASS
- ai_server_예외시_EXTERNAL_API_ERROR — PASS

### BatchQuartzJobTest (4건)
- BatchQuartzJob은_DisallowConcurrentExecution_애너테이션_보유 — PASS
- dequeueOne이_empty면_orchestrator_미호출_no_op — PASS
- dequeueOne_성공시_orchestrator_process_호출 — PASS
- orchestrator_예외시_Job은_안전하게_종료 — PASS

## 요약

| # | 테스트 | Method | URL | Status | 결과 |
|---|------|--------|-----|--------|------|
| 1 | REVIEWER batch status 조회 | GET | /api/v1/batch/status?limit=10 | 200 | PASS |
| 2 | WORKER batch status 조회 | GET | /api/v1/batch/status | 200 | PASS |
| 3 | PORTAL_USER 403 | GET | /api/v1/batch/status | 403 | PASS |
| 4 | 인증 없음 401 | GET | /api/v1/batch/status | 401 | PASS |
| 5 | limit 상한 초과 400 | GET | /api/v1/batch/status?limit=501 | 400 | PASS |

| # | 테스트 클래스 | 건수 | 결과 |
|---|-------------|------|------|
| 1 | BatchOrchestratorTest | 10 | PASS |
| 2 | LsDataLblTest | 6 | PASS |
| 3 | BatchRetryQueueTest | 6 | PASS |
| 4 | BatchStatusServiceTest | 5 | PASS |
| 5 | FfmpegFrameExtractorTest | 6 | PASS |
| 6 | YoloAutolabelStepTest | 4 | PASS |
| 7 | BatchQuartzJobTest | 4 | PASS |
| 8 | BatchStatusControllerTest | 5 | PASS |
| | **신규 합계** | **46** | **PASS** |
| | 기존 누적 (Phase 0~4) | 66 | PASS |
| | **전체** | **112** | **PASS** |
