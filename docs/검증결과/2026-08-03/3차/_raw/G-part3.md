# G클러스터 (ai-server) part3 — 3차 검증 결과

담당 범위: `docs/test-cases/G-ai-server.md` **G-1. YOLO 탐지**(TC-AIYOLO-01~28) + **G-2. YOLO 후처리/트래커**(TC-AIYOLO-29~43) — 총 43건.
이슈 ID: `G-ISSUE-41`부터.

## 0. 검증 방법

- **G-1(예측 엔드포인트)**: `POST http://localhost:19300/infer/yolo/predict` 실동작 curl 위주. 컨테이너는 `docker exec klid-ai-server ls -la /app/weights` → 0 files, `AI_MOCK_MODE=false` → **mock_reason=weights_missing** 고정 형상(1차·2차와 동일, 이번 회차도 재확인).
- **G-2(후처리/트래커, unit)**: `ai-server/app/models/yolox_loader.py` 코드 직독 + baseline pytest 결과(`_raw/test-baseline.md`: ai-server 145/145 전부 통과, 2026-08-04 00:xx 실행) 대조.
- 근거 `file:line` 전건 Read 대조(43건) — 드리프트 1건 발견·정정(아래 5절), 그 외 42건은 실측 라인과 일치.
- 이전 회차(2차) 이슈 중 내 범위(TC-AIYOLO-01~43)에 걸친 것 재검증: G-ISSUE-01(track 입력검증 우회, TC-49는 G-3 이지만 수정 여파가 G-1 코드에 있어 확인), G-ISSUE-02(가중치 부재), G-ISSUE-03(imgsz 무효), G-ISSUE-04/64(로깅 유실), G-ISSUE-06(resilience4j), G-ISSUE-09(근거 드리프트).

## 1. 판정 집계

| 판정 | 건수 |
|---|---|
| PASS | 39 |
| PARTIAL | 2 |
| FAIL | 2 |
| BLOCKED | 0 (BLOCKED 였던 항목은 unit 테스트 커버로 PASS 전환, 아래 비고 참조) |
| **합계** | **43** |

## 2. 케이스별 결과

### G-1. YOLO 탐지 (`/infer/yolo/predict`)

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-AIYOLO-01 | PASS | [실동작] `curl POST /infer/yolo/predict` 유효 PNG → `{"detections":[],"mock":true,"source":"mock","mock_reason":"weights_missing","success":true}` HTTP 200 | |
| TC-AIYOLO-02 | PASS | [테스트] `tests/test_yolo_dispatch.py:34-63` env_mock 결정적 person 박스(score=0.9, track_id 없음) — pytest 통과(baseline). 현재 컨테이너는 `AI_MOCK_MODE=false`(weights_missing)라 실컨테이너 live 재현은 불가, unit 계층 커버로 대체 | 컨테이너 env 변경 없이 검증(범위 밖 조작 회피) |
| TC-AIYOLO-03 | PASS | [실동작] `conf_threshold=1.5` → `400 {"error_code":"VALIDATION_ERROR","message":"Input should be less than or equal to 1"}` | |
| TC-AIYOLO-04 | PASS | [실동작] `conf_threshold=-0.1` → 400 동일 계열 | |
| TC-AIYOLO-05 | **PARTIAL** | [실동작] `conf_threshold` 미전송 → 200, `req.conf_threshold==0.4` 는 `tests/test_yolo.py:87-94`로 검증됨(PASS). 그러나 기대결과의 "**로그 conf=0.40**"은 실패 — `docker logs klid-ai-server \| grep -c "INFO:app"` → **0**(로깅 미구성으로 INFO 전량 유실, `logger.info(...predict reason=%s conf_threshold=%.2f...)` at `routers/yolo.py:140-143` 자체가 출력되지 않음). 값 자체는 맞으나 검증 수단(로그)이 성립하지 않음 | 근본원인은 G-ISSUE-04/64(2차, 미해소) — 아래 §4 G-ISSUE-42로 이월 |
| TC-AIYOLO-06 | PASS | [정적] `routers/yolo.py:99-106` `if score >= conf_threshold:`(score=0.9) → conf=0.95 면 미충족 → append 스킵 → `detections=[]`. 코드 로직 명확, 컨테이너가 weights_missing이라 env_mock 자체를 live로 만들 수 없어 정적 확인으로 대체 | |
| TC-AIYOLO-07 | PASS | [실동작] iou=1.5→400, iou=-0.1→400 | |
| TC-AIYOLO-08 | PASS | [실동작] imgsz=100→400("...ge to 320"), imgsz=5000→400("...le to 1920") | |
| TC-AIYOLO-09 | **FAIL**(carry-forward, 미해소) | [정적] `grep -n imgsz ai-server/app/models/yolox_loader.py` → 매치 0건. `_YoloxBackend.predict`(yolox_loader.py:301-314)는 `self._input_size`(고정 `_DEFAULT_INPUT_SIZE=(640,640)`, :53)만 사용, `params.imgsz` 미참조 확인 | 2차 G-ISSUE-03 그대로 미해소 → §4 G-ISSUE-41로 이월 |
| TC-AIYOLO-10 | PASS | [실동작] `{"max_det":10,...}` → 400 "Extra inputs are not permitted" | |
| TC-AIYOLO-11 | PASS | [실동작] `{"foo":1}` → 400 동일 | |
| TC-AIYOLO-12 | PASS | [실동작] `image_b64:""` → 400 "String should have at least 1 character" | |
| TC-AIYOLO-13 | PASS | [실동작] `image_b64` 누락 → 400 "Field required" | |
| TC-AIYOLO-14 | PASS | [실동작] `"!!!notb64"` → 400 `{"error_code":"INVALID_IMAGE","message":"base64 디코드 실패"}` | 참고: G-ISSUE-06(2차, resilience4j `ai` 인스턴스에 ignore-exceptions 없음)은 `application.yml:532-538` 재확인 결과 **여전히 미해소**(vlmClient만 존재) — 이 자체가 TC-14 판정을 바꾸진 않으나 BE↔ai-server 4xx가 재시도/서킷에 집계되는 구조적 문제는 지속 |
| TC-AIYOLO-15 | PASS | [실동작] 운영 기본 10MB 한도로 38.9MB 랜덤노이즈 PNG 전송 → 413 `{"error_code":"IMAGE_TOO_LARGE","message":"이미지 크기 한도 초과 (max 10MB)"}`. 카탈로그 전제 "MAX=1MB"는 `tests/conftest.py:18`(`MAX_IMAGE_SIZE_MB=1`, pytest 전용) 값이며 명시적으로 그렇게 라벨링되어 있어 드리프트 아님(2차 G-ISSUE-09가 "실운영 10MB"를 지적했으나 카탈로그의 전제 컬럼 자체는 이미 정확히 스코프됨 — 별도 정정 불필요로 판단, 실측만 보강) | |
| TC-AIYOLO-16 | PASS | [실동작] GIF(10x10) → 400 "지원하지 않는 형식: GIF" / BMP → 400 "지원하지 않는 형식: BMP" | |
| TC-AIYOLO-17 | PASS | [실동작] 8000x8000 단색 PNG(파일 202KB, 64M px) → 413 "이미지 픽셀 수 한도 초과 (max 50000000 pixels)" — 바이트 크기가 아니라 픽셀 수 게이트가 별도로 동작함을 실증 | |
| TC-AIYOLO-18 | PASS | [테스트] `tests/test_yolo_class_filter.py` classes=["car"] → person mock 결과 제외(`detections==[]`) — pytest 통과 | |
| TC-AIYOLO-19 | PASS | [테스트] 동 파일 classes=["person"] → person 통과 | |
| TC-AIYOLO-20 | PASS | [테스트] `test_yolo_classes_빈리스트면_전체_검출` classes=[] → 필터 미적용(전체 반환), `routers/yolo.py:54` `if not classes: return detections` | |
| TC-AIYOLO-21 | PASS | [테스트] classes 미전송(None) → 전체 반환 | |
| TC-AIYOLO-22 | PASS | [실동작] classes 101개 → 400 "List should have at most 100 items after validation, not 101" | |
| TC-AIYOLO-23 | PASS | [실동작] 현재 운영 컨테이너 실제 형상(`AI_MOCK_MODE=false`+가중치 0개)에서 정확히 카탈로그 전제와 일치 — `mock=true reason="weights_missing" detections=[]` 확인(TC-01과 동일 응답) | |
| TC-AIYOLO-24 | PASS(unit) | [테스트] `tests/test_yolox_loader.py:218-233`(onnxruntime import 실패 시뮬레이션) + `:236-257`(세션 빌드 예외) 둘 다 `get_yolox_mock_reason()=="load_failed"` 단언, pytest 통과. 실컨테이너는 weights_missing 형상이라 load_failed 실사례 live 재현은 불가(BLOCKED-live) | 근본원인(가중치 미배포)은 G-ISSUE-02(2차) 이월 — §4 |
| TC-AIYOLO-25 | PASS(unit) | [테스트] `tests/test_yolox_loader.py:260-` 정상 가중치+ort 시뮬레이션 → 싱글톤 백엔드 로드, `mock_reason=None` 확인. 실컨테이너 가중치 여전히 0개(`docker exec klid-ai-server ls -la /app/weights` 재확인)라 live E2E는 BLOCKED | 동일 |
| TC-AIYOLO-26 | PASS | [실동작] `docker logs klid-ai-server \| grep -c "returning mock prediction"` → **1** (본 세션에서만 predict 요청 15회 이상 발생했음에도 WARN 1회) | |
| TC-AIYOLO-27 | PASS | [정적] mock 경로(`routers/yolo.py:100-106`, Detection 생성 시 `track_id` 미지정) → 기본값 None(`schemas.py:59`) / 실추론 경로(`yolox_loader.py:225`, `_yolox_postprocess` 내 `DetectionResult(...,track_id=None)` 명시) — 두 경로 모두 predict 응답 `track_id`는 항상 None | 근거 라인 드리프트 발견·정정 완료(§5) |
| TC-AIYOLO-28 | PASS | [실동작] `PUT /infer/yolo/predict` → `405 {"detail":"Method Not Allowed"}` | |

### G-2. YOLO 후처리/트래커 (`yolox_loader` — unit)

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-AIYOLO-29 | PASS | [정적+테스트] `yolox_loader.py:142-228` `_yolox_postprocess` decoded 분기 + ratio 역보정(:196-197). `tests/test_yolox_loader.py:64` 단위테스트 존재, baseline 통과 |
| TC-AIYOLO-30 | PASS | [정적+테스트] `_decode_grid_if_needed`(:104-139) grid+stride 복원. `tests/test_yolox_loader.py:85` |
| TC-AIYOLO-31 | PASS | [정적+테스트] `:199-209` `keep_mask = scores >= conf_threshold`. `tests/test_yolox_loader.py:130` |
| TC-AIYOLO-32 | PASS | [정적+테스트] `:171-176`(ndim/shape 방어) + `:229-231`(전체 try/except로 예외 시 빈 리스트). `tests/test_yolox_loader.py:141` |
| TC-AIYOLO-33 | PASS | [정적+테스트] `_nms`(:69-101) 순수 NumPy IoU 억제. `tests/test_yolox_loader.py:160` |
| TC-AIYOLO-34 | PASS | [정적+테스트] 비겹침 박스 유지, `tests/test_yolox_loader.py:168` |
| TC-AIYOLO-35 | PASS | [정적+테스트] 클래스별 `np.unique(cls_ids)` 루프(:212-217)로 클래스 간 NMS 미적용. `tests/test_yolox_loader.py:175` |
| TC-AIYOLO-36 | PASS | [정적] anchor 개수 불일치 시 원본 반환(:135-136 `if predictions.ndim != 3 or predictions.shape[1] != grids.shape[1]: return predictions`) |
| TC-AIYOLO-37 | PASS | [정적+테스트] `_evict_lru_locked`(:454-458). `tests/test_yolox_loader.py:300` `_MAX_TRACKERS=3`로 monkeypatch 후 4번째 clip 추가 시 최고참 제거 확인 |
| TC-AIYOLO-38 | PASS | [정적+테스트] `_evict_expired_locked`(:446-451). `tests/test_yolox_loader.py:316` 가짜 시계로 301초 경과 후 만료 확인 |
| TC-AIYOLO-39 | PASS | [정적+테스트] `get_yolox_tracker`(:461-495) clip별 격리+재사용. `tests/test_yolox_loader.py:334` `a is a2`, `a is not b` |
| TC-AIYOLO-40 | PASS | [정적+테스트] `:469-473` mock_reason 존재 시 즉시 None. `tests/test_yolox_loader.py:347` 3가지 reason 전부 None 확인 |
| TC-AIYOLO-41 | PASS | [실동작] `python3 -c "import app.models.yolox_loader; print('onnxruntime' in sys.modules)"` → **False** (직접 실행 확인) |
| TC-AIYOLO-42 | PASS | [실동작] `grep -n "^def test_app_routers_yolo에_ultralytics" tests/test_yolo_dispatch.py` → line 126, `test_app_routers_yolo에_rtdetr_import가_없음` → line 153. 카탈로그 라인과 정확히 일치. pytest 통과 |
| TC-AIYOLO-43 | PASS | [실동작] `test_yolo_loader_모듈이_삭제됨` line 137, `test_rtdetr_loader_모듈이_삭제됨` line 147 — 정확히 일치, `pytest.raises(ModuleNotFoundError)` 로 실제 부재 확인 |

## 3. 이전 회차(2차) 이슈 해소 여부 — 내 범위(TC-AIYOLO-01~43) 대조

| 이전 이슈 | 관련 케이스 | 3차 상태 |
|---|---|---|
| G-ISSUE-01 (track 입력검증 mock사유 무관 우회) | TC-AIYOLO-49(G-3, 범위 밖)지만 수정 코드가 G-1과 동일 파일 | **✅ 해소** — `routers/yolo.py:161-203` `_track_yolox`가 이제 `decode_image_b64_pil`을 **트래커 획득 이전**에 무조건 1회 수행(코드 주석에 "G-ISSUE-01"·"G-ISSUE-03" 명시적 언급 확인). `/predict`·`/track` 양쪽 입력검증 대칭 회복 |
| G-ISSUE-02 (YOLOX 가중치 미배포) | TC-AIYOLO-23/24/25 | **미해소 유지** — `docker exec klid-ai-server ls -la /app/weights` 재확인 결과 여전히 0 files. 단 unit 테스트 커버로 로직 자체는 PASS(위 표 참조), live E2E만 BLOCKED |
| G-ISSUE-03 (imgsz 실추론 무반영) | TC-AIYOLO-09 | **미해소 유지** — `grep imgsz yolox_loader.py` 매치 0건 재확인 |
| G-ISSUE-04 / G-ISSUE-64 (ai-server INFO 로그 전량 유실) | TC-AIYOLO-05 | **미해소 유지** — `docker logs klid-ai-server \| grep -c "INFO:app"` → 0 재확인(main.py/Dockerfile에 basicConfig/dictConfig/--log-level 여전히 없음) |
| G-ISSUE-06 (resilience4j `ai` 인스턴스 ignore-exceptions 부재) | TC-AIYOLO-14 인접 | **미해소 유지** — `application.yml:532-538`(circuitbreaker), `:608-615`(retry) 재확인, `ai` 인스턴스에는 여전히 `ignore-exceptions` 없음(vlmClient만 보유) |
| G-ISSUE-09 (근거 드리프트 3건 — TC-15/27/45) | TC-AIYOLO-15, 27 (내 범위) | TC-27 **본 회차 카탈로그 정정 완료**(§5). TC-15는 재검토 결과 전제 컬럼이 이미 "MAX=1MB"로 정확히 스코프되어 있어 드리프트 아님으로 재판정(정정 불필요) — TC-45는 G-3 범위 밖 |

## 4. 이슈 기록 (카탈로그 정정은 §5, 여기는 이월 결함만)

### [G-ISSUE-41] TC-AIYOLO-09 — `imgsz` 가 API 계약상 검증되지만 실추론에 전혀 반영되지 않음(640 고정) — **2차 G-ISSUE-03 미해소, 이월**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `imgsz: 320~1920`(기본 1280)을 검증까지 하면 그 값이 추론 입력 해상도에 반영돼야 한다.
- **현재 동작(이슈 내용)**: `ai-server/app/models/yolox_loader.py:53` `_DEFAULT_INPUT_SIZE: tuple[int, int] = (640, 640)`이 하드코딩되어 `_YoloxBackend.__init__`(:267)·`predict`(:301-314)·`track`(:316-326) 어디에서도 `params.imgsz`를 참조하지 않는다(`grep -n imgsz ai-server/app/models/yolox_loader.py` → 매치 0건, 3차 재확인). 반면 `schemas.py:41`은 `imgsz: int = Field(default=1280, ge=320, le=1920)`으로 범위를 강제한다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:19300/infer/yolo/predict -H 'Content-Type: application/json' \
    -d "{\"image_b64\":\"<png>\",\"imgsz\":1920}"   # 200 수용되지만 추론 해상도는 항상 640
  grep -n "params.imgsz\|\.imgsz" ai-server/app/models/yolox_loader.py   # 매치 0건
  ```
- **영향**: 기능/운영. 해상도 튜닝 수단(`LS_SYSTEM_CONFIG.YOLO_IMGSZ`)이 있는 것처럼 노출되나 죽은 설정. 소형 객체 검출률 저하를 운영자가 진단·튜닝할 수 없음.
- **수정 방향(제안)**: ⓐ`_YoloxBackend.predict/track`이 `params.imgsz`로 `input_size`를 동적 구성(ONNX 세션이 동적 shape를 지원하는 경우) 또는 ⓑ지원 불가 시 `schemas.py`의 `imgsz` description에 "현재 640 고정 — 값 무시됨" 명시 + BE 설정 화면에서 `YOLO_IMGSZ` 숨김. ⚠ 구현하지 않음.

### [G-ISSUE-42] TC-AIYOLO-05 — ai-server INFO 로그가 전량 유실되어 mock 사유·추론 파라미터가 관측 불가 — **2차 G-ISSUE-04/64 미해소, 이월**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `routers/yolo.py:140-143`의 `logger.info("[YOLOX][MOCK] predict reason=%s conf_threshold=%.2f imgsz=%d iou=%.2f image_size=%dx%d", ...)`가 요청마다 출력되어, 운영에서 mock 사유·추론 파라미터를 사후 추적할 수 있어야 한다(TC-AIYOLO-05 기대결과 "정상(로그 conf=0.40)"도 이 로그 출력을 전제).
- **현재 동작(이슈 내용)**: `ai-server/app/main.py`·`Dockerfile`에 `logging.basicConfig`/`dictConfig`/uvicorn `--log-level` 배선이 전혀 없어(`grep -rn "basicConfig\|dictConfig\|log_level" ai-server/app ai-server/Dockerfile*` → 매치 0건), Python 기본 root 레벨(WARNING)로 기동되어 INFO 로그가 전량 드롭된다.
  ```bash
  $ docker logs klid-ai-server 2>&1 | grep -c "INFO:app"
  0
  ```
  15회 이상 `/infer/yolo/predict` 호출(본 세션 포함)에도 `predict reason=` 로그는 단 한 줄도 없음(WARN 레벨의 mock-once 경고만 1회 출력됨 — TC-AIYOLO-26).
- **재현/확인 경로**: `docker logs --since 5m klid-ai-server | grep "predict reason="` → 0건, 대조로 `docker logs klid-ai-server | grep -c "returning mock prediction"`(WARNING) → 1건.
- **영향**: 운영/관찰가능성. mock 폴백 원인·추론 파라미터를 첫 1회 WARN 이후 추적 불가 → 학습데이터 품질 저하 원인 규명 불능.
- **수정 방향(제안)**: `app/main.py`에 `logging.basicConfig(level=os.getenv("LOG_LEVEL","INFO"))`를 앱 생성 전에 배선하거나 Dockerfile CMD에 `--log-level info` 추가. ⚠ 구현하지 않음.

## 5. 카탈로그 정정 (본 회차, 담당 라인범위 12~64행 내)

1건 정정 완료(Edit 적용됨):

- **TC-AIYOLO-27**(`docs/test-cases/G-ai-server.md:42`) — 근거 `file:line`이 `routers/yolo.py:107`(단순 `return YoloResponse(` 라인, 실제 track_id 결정 로직과 무관)로 되어 있던 것을 `routers/yolo.py:100-106(mock 미설정)+schemas.py:59(기본값None)+yolox_loader.py:225(실추론 명시 None)`로 정정. 2차 G-ISSUE-09에서 이미 지적됐으나 카탈로그 반영이 안 되어 있던 건.

검토 후 정정 보류(드리프트 아님으로 재판정):
- TC-AIYOLO-15 — 전제 "MAX=1MB"는 `tests/conftest.py:18`의 테스트 전용값을 정확히 지칭하는 것으로 확인, 별도 수정 불필요.

## 6. self-fill 관점 (참고)

- 이번 회차도 ai-server는 실추론 불가 시 값을 지어내지 않음(`weights_missing`/`load_failed` → `detections=[]` + `mock=true`/`source="mock"`/`mock_reason` 3필드로 자기 상태 명시) — self-fill 결함 없음, 2차와 동일 결론 재확인.
