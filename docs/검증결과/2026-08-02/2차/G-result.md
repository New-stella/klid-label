# G-part1 — ai-server YOLO 탐지(G-1, 28건) + YOLO track(G-3, 12건) = 40건

> 검증일 2026-08-02 · 회차 2차 · 담당 범위: `docs/test-cases/G-ai-server.md` §G-1 `TC-AIYOLO-01~28` · §G-3 `TC-AIYOLO-44~55`
> 대상 코드: `ai-server/` (qa-0801 워킹트리) + BE 계약(`backend/.../common/client`, `label/service`, `batch/step`)
> 실행 스택: `klid-ai-server`(:19300, 19시간 가동) · `klid-backend`(:18081) · `klid-mock-server`(:9400) · `klid-postgres`

## 0. 검증 환경 실측 (판정 전제)

| 항목 | 실측값 | 근거 |
|------|--------|------|
| `AI_MOCK_MODE` | **`false`** | `docker inspect klid-ai-server --format '{{json .Config.Env}}'` |
| YOLOX 가중치 | **부재** — `/app/weights` 빈 디렉터리(ro 마운트) | `docker exec klid-ai-server ls -la /app/weights` → `total 8` (파일 0) |
| 실효 mock 사유 | **`weights_missing`** (전 요청) | `POST /infer/yolo/predict` 응답 `"mock_reason":"weights_missing"` |
| onnxruntime / trackers | **설치됨** (1.27.0 / 2.4.0) | `docker exec ... python -c "import onnxruntime, trackers"` |
| ai-server 자동테스트 | 91/91 PASS (1차 baseline) | `docs/검증결과/2026-08-01/1차/_raw/test-baseline.md:12` |
| ai-server 커밋 | 2026-07-25 이후 0건 | `git log -- ai-server` 무출력 (카탈로그 변경이력과 일치) |

**환경 보조 수단(코드·설정 무수정)**: `env_mock` 전제 케이스(TC-02/05/06/18~21/27/44/50/51)는 운영 컨테이너가 `AI_MOCK_MODE=false` 라 재현 불가하므로, **워킹트리 코드를 그대로** `ai-server/.venv` 로 `AI_MOCK_MODE=true MAX_IMAGE_SIZE_MB=1` 환경에서 `127.0.0.1:19399` 에 별도 기동해 실HTTP로 확인한 뒤 종료했다. **파일 수정·빌드·테스트 실행 없음.** ByteTrack 계열(TC-52~55)은 `docker exec klid-ai-server python -c "..."` 인라인 프로브로 실동작 확인했다.

**전 회차 대조**: 1차(2026-08-01)·2차(2026-08-02) `ISSUES.md` 에 `G-ISSUE-*` 0건 — 이월 대상 없음(G 클러스터 최초 검증).

---

## 1. G-1. YOLO 탐지 (`/infer/yolo/predict`) — 28건

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|------|-----------|-----------|
| TC-AIYOLO-01 | PASS | [실동작] | `POST :19300/infer/yolo/predict` 유효 PNG → `200 {"detections":[],"mock":true,"source":"mock","mock_reason":"weights_missing","success":true,"message":"성공","error_code":null}`. 계약 7필드 전부 존재 |
| TC-AIYOLO-02 | PASS | [실동작] | env_mock 인스턴스(:19399) 64x64 PNG conf=0.4 → `detections:[{"label":"person","points":[19.2,19.2,44.8,44.8],"score":0.9,"track_id":null}]`. 중앙 박스 = cx±min(w,h)*0.2 = 32±12.8 계산과 정확히 일치, `track_id=null` |
| TC-AIYOLO-03 | PASS | [실동작] | `conf_threshold=1.5` → `400 {"error_code":"VALIDATION_ERROR","message":"Input should be less than or equal to 1"}` (`schemas.py:40` `le=1.0`) |
| TC-AIYOLO-04 | PASS | [실동작] | `conf_threshold=-0.1` → `400 VALIDATION_ERROR "greater than or equal to 0"` |
| TC-AIYOLO-05 | PARTIAL | [실동작] | 기본값 0.4 적용은 확인(conf 미전송 → score 0.9 detection 반환, `schemas.py:40 default=0.4`). **그러나 기대결과의 "로그 conf=0.40" 은 관측 불가** — `routers/yolo.py:140-143` 의 `logger.info` 가 운영 컨테이너에 단 1줄도 출력되지 않는다(`docker logs klid-ai-server \| grep -c "INFO:app"` = **0**). → **G-ISSUE-04** |
| TC-AIYOLO-06 | PASS | [실동작] | env_mock conf=0.95 → `detections:[]` (0.9 < 0.95, `routers/yolo.py:99`) |
| TC-AIYOLO-07 | PASS | [실동작] | `iou=1.5` → 400 / `iou=-0.1` → 400 (`schemas.py:42`) |
| TC-AIYOLO-08 | PASS | [실동작] | `imgsz=100` → `400 "greater than or equal to 320"` / `imgsz=5000` → `400 "less than or equal to 1920"` (`schemas.py:41`) |
| TC-AIYOLO-09 | PASS | [정적] | 기대결과("imgsz 무시, 로더 640 고정")가 코드와 일치. `yolox_loader.py:53 _DEFAULT_INPUT_SIZE=(640,640)` → `_build_yolox_backend:376` 이 이 상수로 세션 생성 → `_preprocess:284` 가 `self._input_size` 만 사용. `InferenceParams.imgsz`(`detector_backend.py:29`)는 `predict()`(`yolox_loader.py:301-314`)에서 **한 번도 참조되지 않음**(grep 확인). `imgsz=1920` 요청도 200 수용. 실추론 대조는 가중치 부재로 불가 → **UNCERTAINTIES #14 는 "코드상 확정, 실추론 미대조"로 갱신 제안**. 케이스는 PASS 지만 계약 오도라 별건 이슈 → **G-ISSUE-03** |
| TC-AIYOLO-10 | PASS | [실동작] | `max_det` 전송 → `400 VALIDATION_ERROR "Extra inputs are not permitted"` (`schemas.py:37 extra="forbid"`) |
| TC-AIYOLO-11 | PASS | [실동작] | `{"foo":1}` → 400 동일. predict/track/Detection 모두 `extra="forbid"`(`schemas.py:37,54,98,130`) — mass assignment 차단 |
| TC-AIYOLO-12 | PASS | [실동작] | `image_b64:""` → `400 "String should have at least 1 character"` |
| TC-AIYOLO-13 | PASS | [실동작] | `{}` → `400 "Field required"` |
| TC-AIYOLO-14 | PASS | [실동작] | `"!!!notb64"` → `400 {"error_code":"INVALID_IMAGE","message":"base64 디코드 실패"}` (`image_utils.py:37-40`) |
| TC-AIYOLO-15 | PASS | [실동작] | 2200x2200 노이즈 PNG(14.5MB b64) → `413 {"error_code":"IMAGE_TOO_LARGE","message":"이미지 크기 한도 초과 (max 10MB)"}`. ⚠ 카탈로그 전제 `MAX=1MB` 는 **테스트 전용 값**(`tests/conftest.py:18`)이고 운영 기본은 10MB(`config.py:40`) → 근거 드리프트(경미, **G-ISSUE-09**) |
| TC-AIYOLO-16 | PASS | [실동작] | GIF → `400 "지원하지 않는 형식: GIF"`, WEBP → `400 "…: WEBP"`, BMP → `400 "…: BMP"` (`image_utils.py:21,76-78`). ⚠ 자동테스트 커버 0 (`tests/test_image_utils.py` 에 형식 거부 테스트 없음) |
| TC-AIYOLO-17 | PASS | [실동작] | 8000x8000(64M px) 단색 PNG(202KB — 크기 게이트 통과) → `413 {"error_code":"IMAGE_TOO_LARGE","message":"이미지 픽셀 수 한도 초과 (max 50000000 pixels)"}` (`image_utils.py:79-83`). 압축률로 크기 게이트를 우회해도 픽셀 게이트가 잡음 = defense-in-depth 실증 |
| TC-AIYOLO-18 | PASS | [실동작] | env_mock + `classes:["car"]` → `detections:[]` (person 제외) |
| TC-AIYOLO-19 | PASS | [실동작] | `classes:["person"]` → person 1건 통과 |
| TC-AIYOLO-20 | PASS | [실동작] | `classes:[]` → **person 반환**(필터 미적용). `routers/yolo.py:54 if not classes: return detections` 의도된 footgun 동작 그대로 |
| TC-AIYOLO-21 | PASS | [실동작] | `classes:null` → 전체 반환 |
| TC-AIYOLO-22 | PASS | [실동작] | 101개 → `400 "List should have at most 100 items after validation, not 101"` (`schemas.py:43-50`) |
| TC-AIYOLO-23 | PASS | [실동작] | **운영 컨테이너가 실제로 이 상태.** 가중치 부재 → `mock=true source="mock" mock_reason="weights_missing" detections=[]`. `yolox_loader.py:399-404` + 기동 로그 `[YOLOX] weights not found — fallback to mock`. 오염 방지(빈 detections) 확인 |
| TC-AIYOLO-24 | BLOCKED | [정적] | 컨테이너에 onnxruntime 1.27.0 이 정상 설치돼 `load_failed` 재현 불가(패키지 제거는 환경 변조라 미수행). 코드 경로는 `yolox_loader.py:406-417`(`except Exception → _mock_reason="load_failed"`)로 확인, 단위테스트 2건 커버(`test_yolox_loader.py:218,236` — **loader 레벨만**, 라우터 응답 계약은 미커버) |
| TC-AIYOLO-25 | BLOCKED | [정적] | **가중치가 배포되지 않아 실백엔드 경로 자체가 존재하지 않음**(`/app/weights` 빈 디렉터리). `source="model" mock=false` 응답을 이 환경에서 만들 수 없음 → **G-ISSUE-02**. 코드는 `yolox_loader.py:406-410` + `routers/yolo.py:156` |
| TC-AIYOLO-26 | PASS | [실동작] | 19시간 가동·다수 호출에도 `docker logs klid-ai-server \| grep -c "returning mock prediction"` = **1**. `routers/yolo.py:73-83` WARN-once 실증 |
| TC-AIYOLO-27 | PASS | [실동작] | predict 응답 detection `"track_id":null`(env_mock 실측). `routers/yolo.py:100-106` 이 `track_id` 미지정 → `schemas.py:59` 기본 None. ⚠ 카탈로그 근거 `yolo.py:107` 은 `return YoloResponse(` 행 — 실제 근거는 100-106 (**G-ISSUE-09**) |
| TC-AIYOLO-28 | PASS | [실동작] | `PUT /infer/yolo/predict` → `405 {"detail":"Method Not Allowed"}`. CORS preflight(`Access-Control-Request-Method: PUT`) → 400(허용 메서드 GET/POST, `main.py:55`) |

**G-1 집계**: PASS 25 · PARTIAL 1 · FAIL 0 · BLOCKED 2

---

## 2. G-3. YOLO track (`/infer/yolo/track`) — 12건

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|------|-----------|-----------|
| TC-AIYOLO-44 | PASS | [실동작] | env_mock `clip_id=vA frame_index=0` → `{"label":"person","points":[19.2,19.2,44.8,44.8],"score":0.9,"track_id":1}` + `mock=true source="mock" mock_reason="env_mock"` (`routers/yolo.py:227-241`) |
| TC-AIYOLO-45 | PASS | [실동작] | 운영 컨테이너(weights_missing) `POST /infer/yolo/track` → `200 {"detections":[],"mock":true,"mock_reason":"weights_missing"}` (`routers/yolo.py:161-172`) |
| TC-AIYOLO-46 | PASS | [실동작] | `clip_id:""` → `400 "String should have at least 1 character"` / 129자 → `400 "String should have at most 128 characters"` (`schemas.py:101-106`) |
| TC-AIYOLO-47 | PASS | [실동작] | `frame_index:-1` → `400 "greater than or equal to 0"` (`schemas.py:107-109`) |
| TC-AIYOLO-48 | PASS | [정적] | `routers/yolo.py:163` `get_yolox_tracker(req.clip_id, reset=(req.frame_index == 0))` → `yolox_loader.py:479` `if reset or clip_id not in _TRACKERS:` → `_create_fresh_yolox_tracker()` 새 핸들. 단위테스트 `test_트래커_clip별_격리_및_재사용`(`test_yolox_loader.py:334`) 커버. 실백엔드 대조는 가중치 부재로 불가(TC-25 와 동일 사유) |
| **TC-AIYOLO-49** | **FAIL** | **[실동작]** | **기대 400, 실측 200.** 운영 컨테이너에 `{"image_b64":"!!!notb64","clip_id":"c1","frame_index":0}` → `200 {"detections":[],"mock":true,"mock_reason":"weights_missing",...}`. 같은 입력을 env_mock 인스턴스에 보내면 `400 INVALID_IMAGE` — **mock 사유에 따라 입력 검증 유무가 갈린다**. `_mock_track`(`routers/yolo.py:228-229`)이 `if reason == "env_mock":` 블록 안에서만 `decode_image_b64` 를 호출하기 때문. 자동테스트(`test_yolo_track.py:126`)는 conftest 가 `AI_MOCK_MODE=true` 를 강제해 env_mock 분기만 타므로 **거짓 통과** → **G-ISSUE-01** |
| TC-AIYOLO-50 | PASS | [실동작] | env_mock track + `classes:["car"]` → `detections:[]` (`routers/yolo.py:204`) |
| TC-AIYOLO-51 | PASS | [실동작] | track detection 키 집합 = `{label, points, score, track_id}` 정확히 일치, 응답 최상위 = `{detections, mock, source, mock_reason, success, message, error_code}` (`schemas.py:123-141`) |
| TC-AIYOLO-52 | PASS | [실동작] | 컨테이너 내 `_apply_bytetrack(dets, FakeTracker([-1]))` → `track_id=None`. `bytetrack_util.py:92` `int(tid) if tid is not None and int(tid) >= 0 else None` |
| TC-AIYOLO-53 | PASS | [실동작] | dets=2 / tracker_ids=[7] → 로그 `WARNING:app.models.bytetrack_util:[ByteTrack] tracker_id 길이 불일치 dets=2 tracker_ids=1 — 누락분 track_id=None 유지`, 결과 `[7, None]`. 조용한 truncate 없음 (`bytetrack_util.py:83-90`) |
| TC-AIYOLO-54 | PASS | [실동작] | `builtins.__import__` 로 `trackers` ImportError 주입 → `_new_bytetrack_tracker()` 3회 호출 결과 전부 `None`, WARN **1회**만 출력. 예외 미전파 (`bytetrack_util.py:44-56`) |
| TC-AIYOLO-55 | PASS | [실동작] | `coco_id_from_label('person')=0`, `('car')=2` 분리 확인. 실 `ByteTrackTracker` 로 3프레임 연속 update → f0 `[None,None]` · f1 `[0,1]` · f2 `[0,1]` — **person/car 가 서로 다른 track_id 를 유지하고 프레임 간 ID 가 안정**. ⚠ 첫 프레임은 항상 `None`(ByteTrack 확정 지연) — BE `YoloTrackService`(frameIndex=0 시작)·`AutolabelOnlineService`(항상 frameIndex=0)는 실백엔드에서 **track_id 를 영구히 받지 못한다** → G-ISSUE-05 참조. ⚠ 미지 라벨은 `hash()` 기반이라 프로세스마다 값이 달라짐 → **G-ISSUE-07** |

**G-3 집계**: PASS 10 · PARTIAL 0 · **FAIL 1** · BLOCKED 0 · (정적 1건 포함)

---

## 3. 총 집계 (40건)

| 판정 | 건수 |
|------|-----:|
| PASS | 35 |
| FAIL | **1** |
| PARTIAL | 1 |
| BLOCKED | 2 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **40** |

근거확인 분포: [실동작] 36건 · [정적] 4건 (TC-09/24/25/48)

---

## 4. 이슈

### [G-ISSUE-01] TC-AIYOLO-49 — `/infer/yolo/track` 이 weights_missing·load_failed 경로에서 이미지 입력 검증을 전면 스킵한다
- **심각도**: HIGH
- **기대 동작(기대효과)**: `/track` 은 `/predict` 와 동일하게 `image_b64` 를 검증해야 한다 — 잘못된 base64·미지원 형식·크기/픽셀 초과는 `400 INVALID_IMAGE` / `413 IMAGE_TOO_LARGE`. BE 는 이 4xx 로 "프레임 이미지가 깨졌다"를 인지해 그 프레임을 드롭하거나 재추출해야 한다. mock 사유는 **모델 가용성** 축이고 입력 검증은 **요청 유효성** 축이라 서로 독립이어야 한다.
- **현재 동작(이슈 내용)**: `_mock_track` 이 `reason == "env_mock"` 일 때만 디코드한다.
  ```python
  # ai-server/app/routers/yolo.py:227-232
  detections: list[Detection] = []
  if reason == "env_mock":
      width, height = decode_image_b64(req.image_b64)   # ← 검증이 이 블록 안에만 있다
      cx, cy = width / 2.0, height / 2.0
  ```
  반면 `/predict` 는 mock 사유와 무관하게 검증한다 — `routers/yolo.py:137` `width, height = decode_image_b64(req.image_b64)` 가 `if backend is None:` 블록 **선두**에 있다.
  실측(운영 컨테이너, `mock_reason=weights_missing`):
  ```
  $ curl -X POST http://localhost:19300/infer/yolo/track -d '{"image_b64":"!!!notb64","clip_id":"c1","frame_index":0}'
  {"detections":[],"mock":true,"source":"mock","mock_reason":"weights_missing","success":true,...}  HTTP:200
  $ curl -X POST http://localhost:19300/infer/yolo/predict -d '{"image_b64":"!!!notb64"}'
  {"error_code":"INVALID_IMAGE","message":"base64 디코드 실패"}  HTTP:400
  ```
  자동테스트 `tests/test_yolo_track.py:126 test_track_invalid_base64_입력시_400` 은 `tests/conftest.py:17` 이 `AI_MOCK_MODE=true` 를 강제해 **env_mock 분기만** 타므로 통과한다 — **운영 형상(weights_missing)에서는 검증되지 않는 거짓 통과**다.
- **재현/확인 경로**:
  ```bash
  # 운영 컨테이너 = AI_MOCK_MODE=false + /app/weights 비어 있음 → weights_missing
  curl -s -X POST http://localhost:19300/infer/yolo/track -H 'Content-Type: application/json' \
    -d '{"image_b64":"!!!notb64","clip_id":"c1","frame_index":0}' -w "\nHTTP:%{http_code}\n"
  # 기대 400 / 실제 200
  # 크기·형식 게이트도 동일하게 우회된다(GIF·10MB 초과·64Mpx 전부 200)
  ```
- **영향**: CWE-20(Improper Input Validation). ①`/predict` 와 `/track` 의 계약이 배포 형상에 따라 갈려 BE 가 프레임 손상을 탐지하지 못한다(현재 전 환경이 weights_missing 이므로 **실제로 track 입력 검증이 0**). ②가중치를 배포해 실백엔드가 켜지는 순간 같은 입력이 갑자기 400 을 내기 시작한다(형상 의존 동작 변경). ③크기·픽셀 게이트도 함께 우회되므로 가중치 로드 실패(`load_failed`)로 폴백된 순간에는 DoS 방어(CWE-770)가 사라진다.
- **수정 방향(제안)**: `ai-server/app/routers/yolo.py` `_track_yolox`(161-172)에서 `_mock_track` 호출 **전에** `decode_image_b64(req.image_b64)` 를 1회 호출해 검증하고(폭·높이를 `_mock_track` 에 인자로 전달), `_mock_track` 내부의 조건부 디코드를 제거한다 — `/predict` 의 137행 구조와 동일하게 맞춘다. 회귀 가드로 `tests/test_yolo_track.py` 에 `monkeypatch.setattr(yolox_loader, "get_yolox_mock_reason", lambda: "weights_missing")` 를 건 invalid-base64 400 테스트를 추가한다(현 테스트는 env_mock 만 검증). ⚠ 구현은 하지 않는다.

### [G-ISSUE-02] TC-AIYOLO-25/24/48 — YOLOX ONNX 가중치가 어느 환경에도 배포되지 않아 실추론 경로가 전면 미가동·미검증
- **심각도**: HIGH
- **기대 동작(기대효과)**: 검증 스택은 "정상 시나리오 위에서 실동작 판정"이 대전제다(VERIFY-PROMPT §1). 오토라벨(YOLO)이 실제 검출을 내야 `YoloAutolabelStep` → `LS_DATA_LBL` 적재, 트랙 ID 연속성, 좌표 clamp, NMS 등 G-1/G-2/G-3 의 실추론 계약이 검증된다.
- **현재 동작(이슈 내용)**: 가중치 파일이 존재하지 않아 `get_yolox_model()` 이 항상 None 을 반환하고 전 요청이 mock 이다.
  ```
  $ docker exec klid-ai-server ls -la /app/weights
  total 8
  drwxr-xr-x 2 app app   64 Jul 30 17:56 .     ← 파일 0개
  $ docker logs klid-ai-server | head -3
  [YOLOX] weights not found — fallback to mock
  ```
  ```python
  # ai-server/app/models/yolox_loader.py:399-404
  weights_path, reason = _resolve_yolox_weights()
  if reason == "weights_missing":
      logger.warning("[YOLOX] weights not found — fallback to mock")
      _mock_reason = "weights_missing"
      _loaded = True
      return None
  ```
  compose 는 `./weights:/app/weights:ro`(`docker-compose.yml:191`) / `./ai-server/weights:/app/weights:ro`(`docker-compose.local.yml:107`) 로 **경로가 서로 다르고 둘 다 비어 있다.** BE 는 이 상태를 차단하지 않는다 — `YoloAutolabelStep.java:222-232` 는 `resp.untrusted()` 를 WARN 로그로만 남기고 계속 진행하므로 **배치는 "성공"으로 끝나되 라벨 0건**이다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-ai-server ls -la /app/weights          # 빈 디렉터리
  curl -s -X POST http://localhost:19300/infer/yolo/predict -H 'Content-Type: application/json' \
    -d "{\"image_b64\":\"<valid png b64>\"}"              # mock_reason=weights_missing, detections=[]
  docker logs klid-backend 2>&1 | grep "mock response detected"   # 배치가 WARN 만 남기고 통과
  ```
- **영향**: 기능. 오토라벨링(SFR-08 핵심)이 전 환경에서 무동작이며, 그 사실이 배치 실패가 아니라 "라벨 0건 성공"으로 나타나 조용히 지나간다. 부수적으로 TC-AIYOLO-25(실백엔드 mock=false)·24(load_failed)·48(실백엔드 트래커 리셋)·G-2 전 항목·G-3 실트래킹이 **검증 불가(BLOCKED)** 상태로 남는다.
- **수정 방향(제안)**: ①`weights/yolox_s.onnx` 를 배포 산출물에 포함하거나 기동 시 fetch 하는 절차를 `deploy/onprem/docs/04-configuration.md` 에 명문화 + compose 두 파일의 마운트 소스 경로 통일. ②stg/prd 프로파일에서는 `weights_missing`/`load_failed` 를 **기동 차단 또는 배치 FAIL** 로 승격(현 `VlmUrlPolicy`·`QuartzClusteringGuard` 와 동일한 fail-closed 골격) — 운영에서 mock 라벨/0건 라벨이 학습데이터로 흘러가는 것을 막는다. ③검증 환경에는 가중치를 두어 G-2/G-3 실추론 케이스를 다음 회차에 해소. ⚠ 구현은 하지 않는다.

### [G-ISSUE-03] TC-AIYOLO-09 — `imgsz` 파라미터가 API 계약상 유효한 것처럼 검증되지만 추론에는 전혀 반영되지 않는다(640 고정)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: API 가 `imgsz: 320~1920`(기본 1280)을 받아 검증까지 하면 그 값이 추론 입력 해상도에 반영되어야 한다. 반영하지 않을 것이면 파라미터를 받지 않거나 계약에 "무시됨"을 명시해야 한다.
- **현재 동작(이슈 내용)**: 요청 → `InferenceParams` 까지는 전달되지만 전처리는 상수만 쓴다.
  ```python
  # ai-server/app/models/yolox_loader.py:53
  _DEFAULT_INPUT_SIZE: tuple[int, int] = (640, 640)
  # :376  세션 생성 시 고정
  return _YoloxBackend(session, input_size=_DEFAULT_INPUT_SIZE, device=device)
  # :284  전처리는 self._input_size 만 사용 — params.imgsz 미참조
  ih, iw = self._input_size
  ```
  `predict()`(301-314)·`track()`(316-326) 어디에서도 `params.imgsz` 를 읽지 않는다(grep 확인). 그런데 스키마는 `imgsz: int = Field(default=1280, ge=320, le=1920)`(`schemas.py:41,111`)로 범위를 강제하고, BE 는 시스템설정 `YOLO_IMGSZ` 를 읽어 전송한다(`AutolabelOnlineService.java:491`). 즉 **운영자가 화면에서 imgsz 를 바꿔도 추론은 언제나 640** 이고, 기본값 1280 자체가 실제와 2배 어긋난다. `logger.info` 는 `imgsz=%d` 를 찍지만 그 로그도 출력되지 않는다(G-ISSUE-04).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:19300/infer/yolo/predict -H 'Content-Type: application/json' \
    -d "{\"image_b64\":\"<png>\",\"imgsz\":1920}"   # 200 수용
  grep -n "params.imgsz\|\.imgsz" ai-server/app/models/yolox_loader.py   # 참조 0건
  ```
- **영향**: 기능/운영. 해상도 튜닝 수단이 없는데 있는 것처럼 노출되어 소형 객체 검출률 저하를 운영자가 진단할 수 없다. `LS_SYSTEM_CONFIG` 의 `YOLO_IMGSZ` 는 죽은 설정이다. UNCERTAINTIES #14(imgsz 무효 검증 방법)를 **"코드상 확정 — 미반영"** 으로 갱신 가능.
- **수정 방향(제안)**: 둘 중 택1 — ⓐ`_YoloxBackend.predict/track` 이 `params.imgsz` 로 `input_size` 를 동적 구성(ONNX 입력이 동적 shape 인 경우에만 유효하므로 세션 입력 shape 확인 후 지원 여부 판단). ⓑ지원 불가면 `schemas.py` 의 `imgsz` description 에 "현재 로더는 640 고정 — 값은 무시됨"을 명시하고 BE `ConfigKeys.YOLO_IMGSZ` 를 설정 화면에서 숨긴다. 어느 쪽이든 `_DEFAULT_INPUT_SIZE` 와 스키마 `default=1280` 의 불일치는 해소한다. ⚠ 구현은 하지 않는다.

### [G-ISSUE-04] TC-AIYOLO-05 — ai-server 의 `logger.info` 가 운영에서 전량 유실되어 mock 사유·추론 파라미터·트래커 생명주기가 관측 불가
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md "배치 성능 — ai-server 추론 자원 모니터링 포인트 확보" 및 observability 규칙상, 요청별 mock 사유/추론 파라미터/트래커 생성·축출이 로그로 남아야 한다. 특히 mock 응답은 WARN-once 라 **2번째 이후 요청의 mock 사유를 알 수 있는 유일한 수단이 INFO 로그**다.
- **현재 동작(이슈 내용)**: `ai-server` 어디에도 `logging.basicConfig`/`dictConfig` 가 없고(`grep -rn "basicConfig\|dictConfig\|logging.config" ai-server/` → 0건), Dockerfile 은 `CMD ["uvicorn","app.main:app",...]`(`ai-server/Dockerfile:44`)로 로그 설정 없이 기동한다. 결과적으로 root 로거 기본 레벨 WARNING 이 적용되어 앱 INFO 가 전부 드롭된다.
  ```
  $ docker logs klid-ai-server 2>&1 | grep -c "INFO:app"
  0
  $ docker logs klid-ai-server 2>&1 | grep -E "\[AI\] startup|\[YOLOX\]" | head
  [YOLOX] weights not found — fallback to mock          ← WARNING 만 나옴
  ```
  `main.py:33-38` 의 기동 요약(`[AI] startup mock_mode=%s device=%s max_image_mb=%d`)조차 출력되지 않는다. 드롭되는 것들: `routers/yolo.py:140-143`(mock 사유+파라미터), `:151-154`(실추론 파라미터), `:167-171`(track mock), `yolox_loader.py:451,458,484-489`(트래커 TTL/LRU 축출·생성).
- **재현/확인 경로**: `docker logs klid-ai-server 2>&1 | grep -c "INFO:app"` → 0. 대조: 동일 코드를 `logging.basicConfig(level=INFO)` 로 기동하면 같은 메시지가 출력된다(컨테이너 내 `python -c` 프로브로 확인).
- **영향**: 운영/관찰가능성. ①mock 폴백이 언제·왜 발생했는지 첫 1회 WARN 이후 추적 불가 → 학습데이터 오염 원인 규명 불능. ②트래커 축출(G-ISSUE-05)이 완전히 무음이 된다. ③기동 시 `mock_mode`/`max_image_mb` 실효값을 로그로 확인할 수 없어 배포 검증 수단이 없다.
- **수정 방향(제안)**: `app/main.py` 에 `logging.basicConfig(level=os.getenv("LOG_LEVEL","INFO"))` 또는 uvicorn `--log-config` 로 앱 로거 레벨을 지정하고, Dockerfile CMD 에 `--log-level info` 를 추가한다. 레벨은 환경변수(`LOG_LEVEL`)로 조절 가능하게 두어 prd 에서 낮출 수 있게 한다. ⚠ 구현은 하지 않는다.

### [G-ISSUE-05] TC-AIYOLO-48/55 — 트래커 캐시(LRU max=10)가 온라인 오토라벨의 UUID clipId 채번과 충돌해 배치 영상의 track_id 연속성이 무신호로 파손될 수 있다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `clip_id` 별 트래커는 한 영상의 전 프레임 처리 동안 유지되어야 한다. 같은 객체가 프레임을 넘어 같은 `track_id` 를 받아야 `LS_DATA_LBL.TRCK_ID` 로 트랙 보간·트랙 편집이 성립한다. 트래커가 중간에 리셋되면 **동일 객체가 새 track_id 를 받거나(트랙 단절), 다른 객체가 이전 ID 를 재사용(오귀속)** 한다.
- **현재 동작(이슈 내용)**: ai-server 트래커 캐시는 **전역 10개** 상한이다.
  ```python
  # ai-server/app/models/yolox_loader.py:61-62
  _MAX_TRACKERS: int = 10
  _TRACKER_TTL_SEC: float = 300.0
  # :454-458  초과분 무조건 축출 (clip 종류 구분 없음)
  while len(_TRACKERS) > _MAX_TRACKERS:
      oldest_key, _ = _TRACKERS.popitem(last=False)
  ```
  그런데 BE 온라인 경로는 **요청마다 새 clipId** 를 만든다:
  ```java
  // backend/.../label/service/AutolabelOnlineService.java:495-499
  String clipId = rawSn + ":" + UUID.randomUUID();   // 요청마다 유일 — 재사용 없음
  return aiServerClient.predictYoloTrack(
          new YoloTrackRequest(imageB64, clipId, 0, conf, imgsz, iou, classes))
  ```
  → 온라인 요청 10건이면 배치 영상(`clipId = String.valueOf(rawSn)`, `YoloAutolabelStep.java:208-210`)의 트래커가 LRU 로 밀려난다. 다음 프레임 호출은 `clip_id not in _TRACKERS` 라 **새 핸들**을 만들고(`yolox_loader.py:479-490`) track_id 시퀀스가 처음부터 다시 시작된다. 응답에는 이를 알리는 필드가 없고 축출 로그는 `logger.info` 라 출력조차 되지 않는다(G-ISSUE-04). TTL 300초도 같은 축으로, 프레임 간 간격이 5분을 넘으면 조용히 리셋된다.
  더불어 실 ByteTrack 은 **트래커 생성 직후 첫 프레임에 항상 `track_id=None`** 을 준다(컨테이너 실측: f0 `[None,None]` → f1 `[0,1]` → f2 `[0,1]`). 따라서 온라인 경로는 항상 `frameIndex=0` + 매번 새 clipId 라 **실백엔드에서도 track_id 를 영구히 받지 못한다**(`AutolabelOnlineService.java:338,430` 이 `d.trackId()` 를 그대로 저장 → 항상 null).
- **재현/확인 경로**(가중치 배포 후):
  ```bash
  # 1) 배치 영상 clipId="123" 로 frame 0..N 처리 중
  # 2) 그 사이 온라인 오토라벨 11회 호출(각기 다른 UUID clipId)
  # 3) 배치의 다음 프레임 응답에서 track_id 가 1부터 다시 시작되는지 확인
  #    (동일 객체인데 TRCK_ID 가 바뀜)
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT src_sn, trck_id, count(*) FROM ls_data_lbl WHERE auto_lbl_yn='Y' GROUP BY 1,2 ORDER BY 1"
  ```
  현 환경은 가중치 부재로 트래커 캐시 자체가 미가동(`get_yolox_tracker` 가 mock 사유로 즉시 None) → 실증 BLOCKED, 코드·단위테스트(`test_yolox_loader.py:300 LRU`, `:316 TTL`)로 확인.
- **영향**: 데이터 정합. 오토라벨 트랙이 한 영상 안에서 조각나 트랙 보간(`INTERPOLATE` 단계)·트랙 병합/편집이 잘못된 단위로 동작한다. 무신호라 검수자가 원인을 알 수 없다. 온라인 경로는 track_id 가 상시 null 이라 트랙 기능 자체가 성립하지 않는다.
- **수정 방향(제안)**: ①온라인 단발 추론은 트래커가 필요 없으므로 `/infer/yolo/predict` 를 쓰거나(현재 BE `predictYolo` 는 프로덕션 호출자 0건 — 사장된 API), ai-server 에 "트래커 미사용" 플래그를 두어 캐시 엔트리를 만들지 않게 한다. ②`_MAX_TRACKERS` 를 동시 처리 영상 수 기준으로 상향하고 축출을 WARN 으로 승격해 BE 가 관측 가능하게 한다. ③또는 응답에 `tracker_reset: true` 를 실어 BE 가 트랙 경계를 인지하게 한다. ④온라인 경로가 track_id 를 필요로 한다면 첫 프레임 None 을 전제로 설계를 바꾼다. ⚠ 구현은 하지 않는다.

### [G-ISSUE-06] TC-AIYOLO-14/49 — `ai` Resilience4j 인스턴스가 결정적 4xx 를 재시도·서킷 실패로 집계한다(`vlmClient` 와 비대칭)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `400 INVALID_IMAGE`·`413 IMAGE_TOO_LARGE`·`400 VALIDATION_ERROR` 는 **결정적 실패**다. 재시도해도 같은 결과이므로 즉시 실패해야 하고, 서킷 브레이커의 실패율에도 집계되면 안 된다(정상 4xx 가 서킷을 여는 것 방지).
- **현재 동작(이슈 내용)**: 같은 파일 안에서 `vlmClient` 는 이 처리를 명시적으로 했는데 `ai` 는 하지 않았다.
  ```yaml
  # backend/src/main/resources/application.yml:602-612
      ai:
        max-attempts: 3
        wait-duration: 1s
        exponential-backoff-multiplier: 2      # ← ignore-exceptions 없음
      vlmClient:
        max-attempts: 3
        wait-duration: 1s
        exponential-backoff-multiplier: 2
        # V1: 4xx(400/422 등) 비-일시적 오류는 재시도하지 않는다 …
        ignore-exceptions:
          - kr.co.cudo.authoring.common.client.NonRetryableExternalException
  ```
  서킷 쪽도 동일(`:526-530` `ai` 에는 `ignore-exceptions` 없음 / `:532-538` `vlmClient` 에는 있음). `AiServerClient.predictYolo/predictYoloTrack`(`:39-67`)은 `.retrieve()` 라 4xx 에 `WebClientResponseException` 을 던지고, 그것이 `RetryOperator`·`CircuitBreakerOperator` 를 그대로 통과한다.
- **재현/확인 경로**:
  ```bash
  # 깨진 프레임 이미지(0바이트/GIF 등)가 있는 영상으로 YoloAutolabelStep 을 태우면
  # 프레임마다 400 → 1s·2s 백오프 3회 → 프레임당 ~3초 지연
  docker logs klid-backend 2>&1 | grep -E "CircuitBreaker 'ai'|Retry 'ai'"
  ```
- **영향**: 가용성/성능. 손상 프레임이 다수인 영상 1건이 `ai` 서킷(실패율 50%, 슬라이딩 10, 최소 5콜)을 열어 **30초 동안 YOLO·SAM2·VLM 온라인 추론 전부가 차단**된다(같은 `aiCircuitBreaker` 를 4개 메서드가 공유). 배치 지연도 프레임당 3초씩 누적. ⚠ 현재는 G-ISSUE-01 때문에 `/track` 이 4xx 를 아예 내지 않아 증상이 가려져 있으며, 01 을 고치는 순간 이 문제가 드러난다(두 이슈는 같이 봐야 한다).
- **수정 방향(제안)**: `KpstDeidentifyClient`/`VlmClient` 가 쓰는 `NonRetryableExternalException` 매핑을 `AiServerClient` 에도 적용(`onStatus(HttpStatusCode::is4xxClientError, …)`)하고, `application.yml` 의 `resilience4j.retry.instances.ai` 와 `circuitbreaker.instances.ai` 양쪽에 `ignore-exceptions` 를 추가한다. ⚠ 구현은 하지 않는다.

### [G-ISSUE-07] TC-AIYOLO-55 — `coco_id_from_label` 의 미지 라벨 id 가 프로세스마다 달라져 문서화된 계약("항상 동일 정수")을 위반한다
- **심각도**: LOW
- **기대 동작(기대효과)**: docstring 이 "같은 라벨은 항상 같은 정수가 되며 COCO id 범위(0~79)와 충돌하지 않는다"고 명시한다. 트래커 클래스 분리 키가 재시작·노드 간에 안정해야 track 결과가 재현 가능하다.
- **현재 동작(이슈 내용)**:
  ```python
  # ai-server/app/models/detector_backend.py:123-124
  # 미지의 라벨 — 결정적 fallback (해시 mod). 동일 라벨은 항상 동일 정수.
  return _UNKNOWN_LABEL_ID_BASE + (hash(label) & 0xFFFF)
  ```
  CPython 의 `str.__hash__` 는 `PYTHONHASHSEED` 로 프로세스마다 랜덤화된다. 실측(같은 컨테이너, 3개 프로세스):
  ```
  coco_id_from_label('zzz'), coco_id_from_label('unknown_label')
  32934 52844
  51100 66851
  58582 62124
  ```
  "결정적"은 **단일 프로세스 수명 내**에서만 참이다.
- **재현/확인 경로**: `for i in 1 2 3; do docker exec klid-ai-server python -c "from app.models.detector_backend import coco_id_from_label; print(coco_id_from_label('zzz'))"; done`
- **영향**: 기능(잠재). 현재 YOLOX 출력 라벨은 COCO 80종뿐이라 `_LABEL2ID` 히트로 이 분기를 타지 않아 **실피해 없음**. 다만 ai-server 다중 프로세스/재시작 시 비-COCO 라벨이 유입되면 클래스 분리 키가 흔들려 track_id 오귀속이 가능하고, 무엇보다 **주석이 사실과 다르다**(오독 유발).
- **수정 방향(제안)**: `hash()` 대신 `int.from_bytes(hashlib.sha256(label.encode()).digest()[:2],'big')` 같은 안정 해시로 교체하거나, 주석을 "프로세스 수명 내 결정적"으로 정정한다. ⚠ 구현은 하지 않는다.

### [G-ISSUE-08] TC-AIYOLO-52~55 — `bytetrack_util` 전용 테스트 파일이 없어 track_id 정규화 로직의 자동 회귀 커버가 0이다
- **심각도**: LOW
- **기대 동작(기대효과)**: `-1 → None` 정규화, 길이 불일치 WARN+보존, trackers 미설치 graceful, 클래스 분리는 track_id 정합의 핵심이라 자동 테스트로 고정되어야 한다.
- **현재 동작(이슈 내용)**: `ai-server/tests/` 13개 파일 중 `bytetrack_util` 을 직접 검증하는 파일이 없다. 유일한 관련 테스트 `tests/test_yolox_loader.py:389 test_backend_track가_predict후_bytetrack으로_track_id_부여` 는 `monkeypatch.setattr(yolox_loader, "_apply_bytetrack", _fake_apply)`(`:407`)로 **검증 대상 함수를 통째로 대체**하고 "호출됐는지"만 본다. `grep -rn "tracker_id\|_apply_bytetrack" tests/` 결과 실제 로직 단언 0건.
- **재현/확인 경로**: `ls ai-server/tests/ | grep -i bytetrack` → 없음. `grep -rn "_apply_bytetrack" ai-server/tests/` → `test_yolox_loader.py:407`(monkeypatch)뿐.
- **영향**: 회귀 위험. 본 검증에서 컨테이너 인라인 프로브로 4건 모두 정상 확인했으나(TC-52~55 PASS), 코드 변경 시 이를 잡아줄 자동 가드가 없다.
- **수정 방향(제안)**: `ai-server/tests/test_bytetrack_util.py` 신설 — FakeTracker 로 `tracker_id=[-1]`→None, 길이 불일치 WARN(caplog), `builtins.__import__` 패치로 미설치 WARN-once, `coco_id_from_label('person')!=coco_id_from_label('car')` 를 각각 단언. ⚠ 구현은 하지 않는다.

### [G-ISSUE-09] 근거 드리프트 — 카탈로그 `G-ai-server.md` 의 전제·file:line 3건 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 전제·근거는 실제 코드/운영 형상과 일치해야 다음 회차가 같은 기준으로 재검증할 수 있다.
- **현재 동작(이슈 내용)**:
  | 케이스 | 카탈로그 표기 | 실제 |
  |---|---|---|
  | TC-AIYOLO-15 | 전제 `MAX=1MB`, 입력 `1500x1500(~6MB)` | 1MB 는 **테스트 전용**(`tests/conftest.py:18`). 운영 기본 10MB(`config.py:40`) → 1500x1500(6.7MB)은 운영에서 **통과**한다. 실제 413 재현에는 2200x2200(14.5MB) 필요 |
  | TC-AIYOLO-27 | 근거 `routers/yolo.py:107` | 107행은 `return YoloResponse(` — 실제 근거는 `Detection(...)` 생성부 `:100-106` + `schemas.py:59`(default None) |
  | TC-AIYOLO-45 | 근거 `routers/yolo.py:164-172` | 실제 mock 분기는 `:164-172` 중 `:165-172` (164 는 `if backend is None:`). 경미 |
  그 외 G-1/G-3 의 근거 라인 37건은 실측 일치(드리프트율 3/40 = 7.5%).
- **재현/확인 경로**: 위 표의 file:line Read 대조.
- **영향**: 검증 정합성. TC-15 는 전제를 그대로 따르면 **413 이 안 나와 오FAIL** 판정될 수 있다.
- **수정 방향(제안)**: `docs/test-cases/G-ai-server.md` 의 해당 3행 전제·근거를 위 실측으로 갱신. ⚠ 구현은 하지 않는다.

---

## 5. UNCERTAINTIES 갱신 제안

| 항목 | 현 상태 | 본 검증 결과 |
|---|---|---|
| **#14 imgsz 무효(640 고정) 실효 검증 방법** | 미해소 유지 | **부분 확정** — 코드상 `params.imgsz` 참조 0건(`yolox_loader.py:53,301-326`)으로 **무시 확정**. 실추론 대조는 가중치 미배포(G-ISSUE-02)로 여전히 불가. "코드 확정 / 실추론 미대조"로 상태 갱신 권장 |

## 6. self-fill 관점 점검 (본 프로젝트 핵심 관심사)

- ai-server 는 실추론이 불가할 때 **값을 지어내지 않는다** — `weights_missing`/`load_failed` 는 `detections=[]` 를 반환하고(`routers/yolo.py:94-106` — `env_mock` 사유일 때만 결정적 박스 생성), `mock=true`/`source="mock"`/`mock_reason` 3필드로 자기 상태를 명시한다. **self-fill 결함 아님**(실동작 확인).
- BE 도 긍정 증명 기반으로 불신 판정한다 — `AiMockMeta.untrusted(mock, source)` 가 `source="model"` 명시가 없으면 불신(fail-closed, `AiMockMeta.java:37-39`), `YoloResponse.untrusted()` 가 이를 위임한다.
- 다만 **불신 판정 이후의 처리가 로그뿐**이다(`YoloAutolabelStep.java:222-232` WARN 후 계속 진행) → 운영에서 mock/빈 결과가 "성공한 배치"로 종결된다(G-ISSUE-02 의 영향 항목 참조).
# G 클러스터 2차 검증 — part2 (G-2 YOLO 후처리/트래커 · G-6 계약 정합/공통 인프라)

- 대상: `docs/test-cases/G-ai-server.md` **G-2 (TC-AIYOLO-29~43, 15건)** + **G-6 (TC-AICONTRACT-01~12, TC-AIINFRA-01~08, 20건)** = **35건**
- 판정: **PASS 35 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0**
- 신규 이슈: **0건**
- 코드/설정/테스트 파일 수정 **0건**, 빌드/테스트 실행(pytest/gradle 전체 스위트) **0건**. ai-server `app.config.Settings` 를
  `python -c`로 직접 import 해 값을 관찰(단발성 함수 호출, pytest 실행 아님) + 라이브 스택에 실제 HTTP 요청.

---

## 0. 검증 환경 · 근거 수집 방법

| 항목 | 실측값 |
|---|---|
| ai-server | `http://localhost:19300/health` → `{"status":"ok"}` (컨테이너 `klid-ai-server`, 19시간 가동) |
| backend | `http://localhost:18081/api/actuator/health` → `{"status":"UP"}` |
| mock-server | `http://localhost:9400/health` → `{"status":"ok"}` |
| 대상 커밋 | `56d30478910319071d852a892d6d110281e3209b`(2026-08-01) — **ai-server/, mock-server/, backend CocoClasses.java/DetectionBoxNormalizer.java 는 워킹트리 미수정 확인**(`git status --short`에 없음). backend 는 다른 클러스터(D/E) 관련 80파일 미커밋 수정이 있으나 본 스코프(YoloAutolabelStep/AutolabelOnlineService/Sam2SegmentService/Sam2TrackService `.untrusted()` 리팩터)는 mock 판정 방식 변경일 뿐 `DetectionBoxNormalizer`·COCO 매핑 로직과 무관함을 diff 로 확인 |
| baseline | `docs/검증결과/2026-08-01/1차/_raw/test-baseline.md` — ai-server `91 passed 0 failed`, backend `4750/4755 passed, 0 failed, 5 skip`(동일 커밋). 본 스코프 파일 미수정이므로 그대로 유효 |
| ai-server 실행모드 | 라이브 컨테이너는 `AI_MOCK_MODE=true`(predict 응답 `mock_reason=env_mock` 실측) |

---

## 1. G-2. YOLO 후처리/트래커 (`ai-server/app/models/yolox_loader.py` — unit) — 15건

전건 `ai-server/app/models/yolox_loader.py` 정적 대조(Read 전문) + `ai-server/tests/test_yolox_loader.py`(19개 테스트 함수, baseline 91건에 포함되어 PASS 확인됨) 대조. 순수 함수(`_nms`, `_decode_grid_if_needed`, `_yolox_postprocess`)는 numpy 만 사용해 onnxruntime 미설치 환경에서도 직접 단위검증 가능하도록 설계돼 있고, 로더 상태(mock 사유·LRU/TTL)는 monkeypatch 로 대체 검증됨.

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|:--:|------|
| TC-AIYOLO-29 | PASS | [정적] | `_yolox_postprocess`(yolox_loader.py:142-231) decoded=True 경로: `[cx,cy,w,h]→[x1,y1,x2,y2]`(189-193) 후 `ratio` 나눔(196-197) 역보정. 테스트 `test_YOLOX_이미_디코드된_출력이...`(test_yolox_loader.py:64-82) — cx=100,cy=100,w=40,h=20,ratio=0.5 → `[160,180,240,220]` 손계산 일치 단언 |
| TC-AIYOLO-30 | PASS | [정적] | `_decode_grid_if_needed`(104-139) — stride[8,16,32]별 grid 생성 후 `cx=(raw+grid)*stride`, `w=exp(raw)*stride`(137-138). 테스트(test_yolox_loader.py:85-127)가 84-anchor(8x8+4x4+2x2) 합성 텐서로 anchor#0(stride8)·anchor#64(stride16) 손계산 좌표(`[0,0,8,8]`,`[0,0,32,16]`)와 정확 일치 단언 — 공식 YOLOX onnx_inference.demo_postprocess 규약 재현 확인 |
| TC-AIYOLO-31 | PASS | [정적] | score=obj_conf×class_prob.max() 계산(199-201) 후 `keep_mask=scores>=conf_threshold`(204) 필터. 테스트(test_yolox_loader.py:130-138) obj=0.5×prob=0.4=0.2 < conf=0.3 → `dets==[]` 확인 |
| TC-AIYOLO-32 | PASS | [정적] | `ndim==3→preds[0]`(171) 후 `ndim!=2 or shape[0]==0→[]`(173), `shape[1]<6→[]`(175-176) + 최외곽 `except Exception→[]`(229-231) 이중 방어. 테스트(test_yolox_loader.py:141-153) `(1,0,85)` 빈 배열과 `(3,3)` 완전 이상 shape 둘 다 `[]` 반환, 예외 미전파 확인 |
| TC-AIYOLO-33 | PASS | [정적] | `_nms`(69-101) 순수 IoU 억제. 테스트(test_yolox_loader.py:160-165) IoU 높은 2박스(scores 0.9/0.8) → `keep==[0]`(최고점수만) |
| TC-AIYOLO-34 | PASS | [정적] | 동일 `_nms` 로직의 `order.size==0` 종료 루프(87-101). 테스트(168-172) 원거리 비겹침 2박스 → `keep==[0,1]` 둘 다 유지 |
| TC-AIYOLO-35 | PASS | [정적] | `for c in np.unique(cls_ids)`(213) 클래스별로 `_nms` 개별 호출(212-217) — 클래스가 다르면 같은 위치라도 서로 다른 NMS 그룹. 테스트(175-187) person/car 완전 동일 좌표 2박스 → 둘 다 생존 확인(labels==["car","person"]) |
| TC-AIYOLO-36 | PASS | [정적] | `if predictions.ndim!=3 or predictions.shape[1]!=grids.shape[1]: return predictions`(135-136) — anchor 총합 불일치 시 원본 그대로 반환, IndexError/broadcast 오류 회피. 직접 유닛테스트는 없으나 정상 경로 테스트(84-anchor 합성)가 이 분기를 안 타는 것으로 간접 확인되고, 코드상 방어 조건이 명확해 정적 판정 확실 |
| TC-AIYOLO-37 | PASS | [정적] | `_evict_lru_locked`(454-458) `while len>_MAX_TRACKERS: popitem(last=False)` — `_MAX_TRACKERS=10`(61). 테스트(test_yolox_loader.py:300-313) `_MAX_TRACKERS=3` monkeypatch 후 A,B,C,D 순차 등록 → A(최고참) 제거, {B,C,D} 잔존 확인 |
| TC-AIYOLO-38 | PASS | [정적] | `_evict_expired_locked`(446-451) `now-ts>_TRACKER_TTL_SEC`(300.0, 62) lazy expiration. 테스트(316-331) `time.time` monkeypatch 로 301초 경과 시뮬레이션 → old 제거, new 잔존 확인 |
| TC-AIYOLO-39 | PASS | [정적] | `get_yolox_tracker`(461-495) — `reset or clip_id not in _TRACKERS` 시에만 신규, 아니면 기존 인스턴스 재사용+`move_to_end`. 테스트(334-344) clip-A 2회 호출(reset=False) 시 동일 인스턴스(`a is a2`), clip-B 는 별개(`a is not b`) 확인 |
| TC-AIYOLO-40 | PASS | [정적] | `get_yolox_tracker` 진입부(469-473) `reason=get_yolox_mock_reason(); if reason is not None: return None`. 테스트(347-351) env_mock/weights_missing/load_failed 3개 사유 전부 `None` 반환 확인 |
| TC-AIYOLO-41 | PASS | [정적] | 모듈 최상단 import(29-42)에 `onnxruntime` 없음 — 실제 import 는 `_build_yolox_backend` 내부(366) lazy. 근거줄(20-23)은 이 규약을 명시한 docstring(설명 주석, 실제 가드는 "import 부재"라는 소극적 증거). 테스트(test_yolox_loader.py:415-424) `sys.modules.pop` 후 재import → `"onnxruntime" not in sys.modules` 직접 확인 |
| TC-AIYOLO-42 | PASS | [정적] | `ai-server/app/routers/yolo.py` 소스 grep 결과 `ultralytics`/`rtdetr` 문자열 0건(직접 grep 재확인, 아래 참고). 테스트 `test_app_routers_yolo에_ultralytics_import가_없음`(test_yolo_dispatch.py:126-134), `...rtdetr_import가_없음`(153-159) |
| TC-AIYOLO-43 | PASS | [정적] | `ai-server/app/models/` 디렉터리 실측 — `yolo_loader.py`/`rtdetr_loader.py` 파일 부재(`yolox_loader.py`만 존재). 테스트 `test_yolo_loader_모듈이_삭제됨`(137-140)·`test_rtdetr_loader_모듈이_삭제됨`(147-150) `pytest.raises(ModuleNotFoundError)` |

### G-2 반증 로그(확증편향 차단)

| 반증 시도 | 결과 |
|---|---|
| 예상밖 shape 가 실제로 크래시하지 않는가(TC-32) | ✅ 2중 방어(shape 체크 + 최외곽 try/except) 확인, 코드 검토로 우회 경로 없음 |
| NMS IoU 경계값(`iou==threshold`) 처리 | ✅ `order[iou<=iou_thr]` — `>` 초과만 억제, 동률은 유지(오프바이원 없음) |
| anchor 개수 불일치 시 실제 실행 경로(TC-36) | ✅ 조건 분기 명확, 원본 그대로 반환해 후속 처리(`preds.ndim==3→preds[0]`)와도 정합 — decoded=False 표준 경로에서 예상치 못한 모델 export 크기에도 크래시 없음 |
| lazy import 가 실제로 지켜지는가(TC-41) | ✅ 모듈 전체 import 문 재확인(29-42) — onnxruntime/numpy 모두 함수 내부에서만 `import`. `import app.models.yolox_loader` 단독 실행으로 무거운 의존성 안 끌림 |

---

## 2. G-6. 계약 정합 / 공통 인프라 — 20건

### 2-1. TC-AICONTRACT (COCO 매핑 계약 + 스키마/설정 계약) — 12건

★★ 확증편향 금지 지시에 따라 COCO 매핑 계약(BE `CocoClasses.LABELS` ↔ ai-server `COCO_ID2LABEL`)은 **육안 대조가 아니라 프로그램적 diff** 로 재검증함(Bash `python3` 스크립트로 두 소스에서 정규식 추출·리스트 비교 — pytest/gradle 실행 아님).

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|:--:|------|
| TC-AICONTRACT-01 | PASS | [정적] | `detector_backend.py:67-85` `COCO_ID2LABEL` 정규식 파싱 스크립트 실행 결과: `count=80`, `ids==0..79 순차`, `label 중복 0건`, `id0=person id2=car id5=bus id79=toothbrush` 전부 일치 |
| TC-AICONTRACT-02 | PASS | [정적] | `CocoClasses.LABELS`(80개, backend `.../label/domain/CocoClasses.java:25-43`)와 `COCO_ID2LABEL`(ai-server) 을 각각 정규식 추출해 리스트 비교 스크립트 실행 → **`EXACT MATCH: True`**(순서·값 완전 일치, 불일치 0건). `CocoClassesDriftTest.matchesAiServerSource`(파일:48-72)와 동일 로직이며 baseline(backend 4750/4755 pass, 0 failure)에 이 테스트 클래스가 포함돼 실행·통과됨(assumeTrue 경로는 monorepo 형제 디렉터리 구조상 스킵되지 않음) |
| TC-AICONTRACT-03 | PASS | [정적] | `coco_label_from_id`(detector_backend.py:88-96) — `id2label` 미스 시 `COCO_ID2LABEL.get(class_id,...)` fallback. 위 파싱 결과로 0→person, 2→car, 79→toothbrush 직접 확인. 테스트 커버: test_yolox_loader.py:371-382(person/car/truck 매핑) |
| TC-AICONTRACT-04 | PASS | [정적] | 동일 함수 — `id2label and class_id in id2label` 우선(94), 미스면 COCO fallback, 둘 다 미스면 `str(class_id)`(96). 코드 로직상 `class_id=100`(COCO 밖) + `id2label=None` → `COCO_ID2LABEL.get(100, "100")` = `"100"` 확정(딕셔너리에 100 없음, 위 스크립트로 80개/id 0~79만 확인됨) |
| TC-AICONTRACT-05 | PASS | [정적] | `coco_id_from_label`(107-124) — `_LABEL2ID` 우선 조회(117-119), 숫자 문자열 역복원(121-122), 미지 라벨은 `_UNKNOWN_LABEL_ID_BASE(10000)+hash&0xFFFF`(124)로 COCO(0~79) 비충돌 결정적 정수. 실사용처(`bytetrack_util.py:79`)는 단일 프로세스 수명 내 트래커 클래스 분리 목적이라 Python 문자열 해시 랜덤화(PYTHONHASHSEED, 프로세스 간 상이)가 실제 요구사항(같은 프로세스 안에서 동일 라벨→동일 정수)을 저해하지 않음 확인(반증 로그 참고) |
| TC-AICONTRACT-06 | PASS | [정적]+[실동작] | `schemas.py:68-88` `YoloResponse` 필드 = `detections/mock/source/mock_reason/success/message/error_code` 7개 정확 일치. 라이브 `POST /infer/yolo/predict` 응답 실측(`curl`)도 `mock/source/mock_reason/success` 등 동일 필드셋 반환 확인(§3 로그) |
| TC-AICONTRACT-07 | PASS | [실동작] | 라이브 `POST /infer/yolo/predict`·`/infer/sam2/segment`·`/infer/vlm/verify-objects` 3경로 모두 400(검증 실패, 라우팅은 성공)으로 응답 — 404 아님 → `main.py:62-64` 의 `/infer/yolo`·`/infer/sam2`·`/infer/vlm` prefix 등록이 실제로 살아있음을 실동작 확인 |
| TC-AICONTRACT-08 | PASS | [실동작] | `.venv/bin/python`으로 `app.config.Settings(_env_file=None)` 직접 인스턴스화(env 없음) → `ai_mock_mode=False` 확인(코드:26-33 기본값과 일치) |
| TC-AICONTRACT-09 | PASS | [실동작] | 동일 방식 `MAX_IMAGE_SIZE_MB=0`, `=200` 환경변수로 `Settings()` 생성 시도 → **둘 다 `ValidationError` 발생** 확인(코드:40 `ge=1, le=100`과 일치) |
| TC-AICONTRACT-10 | PASS | [실동작] | `Settings(cors_allow_origins="a,b, c").cors_origins_list()` 실행 결과 `['a', 'b', 'c']` — 공백 trim + 빈 토큰 필터 정확 확인(코드:63-64) |
| TC-AICONTRACT-11 | PASS | [정적] | `config.py` `Settings` 필드 전수 확인 — `detector_backend`/`rtdetr_model_id` 없음(YOLOX 단일화). `Settings.model_fields`에 `detector_backend` 부재 직접 확인(파이썬 실행). 테스트: test_yolo_dispatch.py:162-169 |
| TC-AICONTRACT-12 | PASS | [정적] | `DetectionBoxNormalizer.java:50-81`(backend) — clamp(59-64)·형식위반만 예외(51-58)·퇴화시 `Optional.empty()`(65-67) 3규칙 코드와 정확 일치. 4경로(`AutolabelOnlineService`·`YoloAutolabelStep`·`YoloLabelPersister`·`YoloTrackService`) 전부 `grep -l DetectionBoxNormalizer`로 실사용 확인(4파일 전부 매치). 워킹트리에 `YoloAutolabelStep.java`/`AutolabelOnlineService.java` 미커밋 수정이 있으나 diff 확인 결과 `resp.mock()→resp.untrusted()` mock 판정 방식 리팩터(AiMockMeta 도입)일 뿐 `DetectionBoxNormalizer` 호출부·좌표 clamp 로직과는 무관 |

### 2-2. TC-AIINFRA (헬스체크/RequestId/예외처리) — 8건

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|:--:|------|
| TC-AIINFRA-01 | PASS | [실동작] | `curl -i http://localhost:19300/health` → `200 OK`, body `{"status":"ok"}`(main.py:67-69 와 정확 일치) |
| TC-AIINFRA-02 | PASS | [실동작] | 위 응답 헤더에 `x-request-id: 44ae10fb5566`(12자 hex) 존재 확인(request_id.py:34-48) |
| TC-AIINFRA-03 | PASS | [실동작] | `X-Request-Id: my-custom-id-123` 요청 → 응답 헤더 동일값 그대로 반사 확인(38-47) |
| TC-AIINFRA-04 | PASS | [정적]+[실동작] | `_is_safe_id`(51-54, alnum 또는 `-_`만 허용) 미통과 시 재생성(39-41). FastAPI TestClient 기반 테스트(test_request_id.py:29-36) `"evil\r\nInjected: yes"` → 12자 hex 재생성 + `\r`/`\n` 미포함 확인. **추가 반증**: 실 HTTP(raw socket)로 CRLF 자체 전송은 클라이언트 라이브러리(Python `http.client`)가 먼저 차단(`Invalid header value`)해 전송 자체가 불가 — 이는 HTTP 프로토콜 계층의 1차 방어. **Unicode 우회 가능성 반증**: Python `str.isalnum()`이 한글 등 비ASCII 문자도 `True`를 반환하는 점을 이용해 `한글아이디123`(전부 isalnum=True)을 raw UTF-8 바이트로 실제 전송(`curl -v`로 원문 바이트 전송 확인) → 서버가 **여전히 재생성**(mojibake 로 디코드되어 non-alnum 바이트 포함) 확인, 우회 불가 |
| TC-AIINFRA-05 | PASS | [실동작] | 65자 id(`aaa...a`×65) 전송 → 응답 헤더가 12자 hex 로 재생성됨 확인(`_is_safe_id` 의 `len(value)>64` 조건과 일치) |
| TC-AIINFRA-06 | PASS | [정적] | `exceptions.py:65-69` `@app.exception_handler(Exception)` catch-all — `logger.exception`(서버 로그만) 후 클라이언트에는 `"서버 내부 오류"` 고정 메시지만 반환(스택트레이스/타입명 미노출, CWE-209 준수). 직접 이 경로를 트리거하는 전용 테스트는 없음(pytest 목록에 INTERNAL_ERROR/500 케이스 부재) — **테스트 커버 갭**으로 기록하되, FastAPI/Starlette 의 `exception_handler(Exception)` 등록은 프레임워크 표준 동작이라 코드 정적 판정으로 PASS. 실사용 중 실제 미처리 예외 사례(SAM2 라우터의 텐서 크기 불일치 RuntimeError)는 라우터 자체 try/except 로 잡혀 200 mock-fallback 되므로(G-4 스코프) 이 전역 핸들러가 실제 500 을 낸 로그는 관측되지 않음(정상 — 미처리 예외가 드물다는 뜻) |
| TC-AIINFRA-07 | PASS | [실동작] | `conf_threshold=1.5`(범위 초과) 요청 → `{"error_code":"VALIDATION_ERROR","message":"String should have at least 1 character"}` 류 — `error_code`+`message` 2필드만, 스택/내부경로 없음(exceptions.py:51-59). 추가로 mass-assignment 거부(`{"foo":1}`)·잘못된 base64 요청도 동일하게 깨끗한 2필드 응답 확인 |
| TC-AIINFRA-08 | PASS | [정적] | `schemas.py:23-29` `ErrorResponse(model_config=ConfigDict(extra="forbid"))` — `_err()`(exceptions.py:29-31)가 `error_code`/`message` 만 전달해 스키마와 정확 일치, 강제 초과 필드 유입 경로 없음 |

### G-6 반증 로그(확증편향 차단)

| 반증 시도 | 결과 |
|---|---|
| COCO 매핑이 "봤을 때 비슷해 보여서 PASS" 가 아니라 실제 정확 일치인가 | ✅ 정규식 추출+리스트 비교 스크립트로 80개 전항목 프로그램적 대조, `EXACT MATCH: True` |
| RequestId 안전 검증이 CRLF 외 유니코드 우회 가능한가 | ✅ 불가 — HTTP latin-1 헤더 전송 계층에서 UTF-8 다바이트가 mojibake 로 깨져 non-alnum 이 섞임 → 재생성 |
| `max_image_size_mb`/`cors_origins_list` 가 실제로 그렇게 동작하는가(문서만 보고 PASS 아닌가) | ✅ 코드 직접 import·인스턴스화로 실측(pytest 아님, 순수 함수 호출) |
| DetectionBoxNormalizer 4경로 실사용이 우발적 미검토 리팩터로 깨졌는가 | ✅ 최근 미커밋 diff 확인 결과 `mock()→untrusted()` 전환만 있고 clamp 로직 변경 없음 |
| TC-AIINFRA-06(500 미노출)이 실제 트리거된 사례가 있는가 | ⚠ 직접 트리거하는 전용 테스트 부재 — 코드 정적 근거로 PASS 유지하되 테스트 갭으로 기록(결함은 아님, 카탈로그 기대결과 자체는 코드로 충족됨) |

---

## 3. 라이브 응답 원문 발췌(핵심 근거)

```
$ curl -s -i http://localhost:19300/health
HTTP/1.1 200 OK
x-request-id: 44ae10fb5566
{"status":"ok"}

$ curl -s http://localhost:19300/infer/yolo/predict -d '{"image_b64":"","conf_threshold":1.5}'
{"error_code":"VALIDATION_ERROR","message":"String should have at least 1 character"}

$ curl -s http://localhost:19300/infer/yolo/predict -d '{"image_b64":"abc","foo":1}'
{"error_code":"VALIDATION_ERROR","message":"Extra inputs are not permitted"}

$ python3 (detector_backend.py COCO_ID2LABEL vs CocoClasses.java 프로그램적 diff)
py count 80 java count 80
EXACT MATCH: True

$ MAX_IMAGE_SIZE_MB=0 .venv/bin/python -c "...Settings()..."
ValidationError raised: ValidationError
```

## 4. 결론

G-2(15) + G-6(20) = 35건 **전건 PASS**. FAIL/PARTIAL/BLOCKED/확인필요 0건, 신규 이슈 0건.
근거 드리프트(file:line 불일치) 0건 — 카탈로그의 모든 참조 라인이 실제 코드 위치와 정확히 일치함을 확인.
유일한 경미 관측 사항은 TC-AIINFRA-06(전역 500 핸들러)의 전용 자동테스트 부재이나, 코드 구현 자체는 표준 FastAPI 패턴으로 명확해 결함으로 분류하지 않음(테스트 커버 갭으로만 기록, 이슈 미등록).
# G-part3 — G-4. SAM2 (`/infer/sam2/segment`, `/track`) 28건

- **대상**: `docs/test-cases/G-ai-server.md` § `G-4. SAM2` (TC-AISAM2-01 ~ 28)
- **일시**: 2026-08-02 · 2차
- **검증 방식**: ai-server 실동작 호출(`http://localhost:19300/infer/sam2/*`, 컨테이너 `klid-ai-server`) + 소스 정적 대조 + 테스트 자산 대조

## 0. 환경 실측 (판정 해석에 필수)

| 항목 | 실측값 | 근거 |
|------|--------|------|
| ai-server 컨테이너 | `klid-ai-server` (`19300→9300`), `/health` = `{"status":"ok"}` | `docker ps`, `curl` |
| `AI_MOCK_MODE` | **`false`** | `docker exec klid-ai-server env` |
| `AI_DEVICE` | `cpu` | 동상 |
| `MAX_IMAGE_SIZE_MB` | 미설정 → 기본 **10MB** (`app/config.py:40`) — 카탈로그 전제 "1MB"는 pytest conftest 값 | 413 응답 메시지 `max 10MB` |
| **SAM2 실모델 로드 여부** | **로드 성공(실추론 동작)** — 응답 `mock=false, source="model"`, `sam2` 패키지 설치됨(`requirements.txt:177 sam-2 @ git+…`), 스택트레이스에 `/opt/conda/.../sam2/sam2_image_predictor.py` | 실동작 응답 + `docker logs` |
| YOLOX | `weights not found — fallback to mock` (G-4 범위 밖, 참고) | `docker logs` |
| ai-server 워킹트리 | **미커밋 변경 0건** (`git status -- ai-server/` 빈 출력) → 배포 이미지 = 저장소 코드 | `git status` |

> ⚠ **본 회차 판정 해석 주의 — "mock 모드"가 아니라 "실모델 모드"에서 검증했다.**
> `AI_MOCK_MODE=false` + 실모델 로드 성공이라 **`env_mock` 경로는 실동작으로 도달 불가**(TC-22·23이 여기 해당). 대신 **실추론이 빈 마스크/예외를 낼 때의 `empty_mask` mock fallback**은 실동작으로 유도에 성공했고(영면적 box, 3원소 point 등), 그 경로가 `_mock_segment`/`_mock_track` **동일 함수**를 타므로 TC-01·02·14·18·19·20의 mock 응답 계약은 **실동작으로 확인**했다.
>
> ⚠ **`AiMockMeta` 관련 환경 주의(작업 지시)는 백엔드(Java) 측 자산**이다(`backend/.../common/client/dto/AiMockMeta.java`, `Sam2SegmentService`·`Sam2TrackService` 미커밋 수정 존재). **G-4 는 ai-server 를 직접 호출해 판정**했으므로 배포 백엔드 이미지의 반영 여부와 무관하다. 다만 ai-server mock 응답을 BE 가 어떻게 차단하는지(`untrusted()` → `Sam2SegmentResponse.empty()`)는 C 클러스터 소관이며, 본 파일의 G-ISSUE-43 심각도 산정에만 참조했다.

## 1. 판정 요약

| 판정 | 건수 |
|------|:--:|
| PASS | 26 |
| PARTIAL | 2 |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **28** |

- **근거 드리프트 0건** — 카탈로그의 28개 `file:line` 을 전수 대조했고 모두 현행 코드/테스트와 일치.
- 신규 이슈 **5건**(G-ISSUE-41 ~ 45). HIGH 2 · LOW 3.

## 2. 케이스별 판정

| ID | 케이스명 | 판정 | 근거 확인 | 확인 내용 |
|----|---------|:----:|---------|----------|
| TC-AISAM2-01 | segment 포인트 → polygon | PASS | [실동작] | 실추론 실패 유도(`points=[[1,2,3]]`) 시 `_mock_segment` 진입 → `200 {"polygon":4점,"score":0.95,"mock":true,"source":"mock","mock_reason":"empty_mask"}`. 정상 포인트(`[[50,40]]`)는 `mock=false,source=model,score=0.987`. `routers/sam2.py:299-314` 일치 |
| TC-AISAM2-02 | segment 박스 → polygon | PASS | [실동작] | `box=[0,0,0,0]`(영면적)·`[50,40,50,40]` → 마스크 없음 → mock 진입, box 분기(`sam2.py:303-304`)로 4점 polygon + `score=0.95`. 정상 박스 `[10,10,60,50]` 는 실추론 다점 contour 반환 |
| TC-AISAM2-03 | 프롬프트 미제공 중앙 폴백 | PASS | [정적] | `sam2.py:309-310` `x1,y1,x2,y2 = w*0.4,h*0.4,w*0.6,h*0.6` 확인. 실모드에서는 `sam2.py:215-216` 이 중앙 1점을 프롬프트로 실추론(`{"image_b64":…}` 만 전송 → 200, `mock=false`). mock else-분기는 실추론 실패+무프롬프트 동시 조건이 필요해 실동작 미유도 |
| TC-AISAM2-04 | ★임계값/conf 스키마 부재 | PASS | [실동작] | `conf:0.5` → `400 {"error_code":"VALIDATION_ERROR","message":"Extra inputs are not permitted"}`. `threshold:0.5` 도 동일. `schemas.py:149 ConfigDict(extra="forbid")` |
| TC-AISAM2-05 | box 길이≠4 | PASS | [실동작] | `[1,2,3]` → 400 `"List should have at least 4 items…not 3"`, `[1,2,3,4,5]` → 400 `"at most 4 items…not 5"`. `schemas.py:153-155` min/max_length=4 |
| TC-AISAM2-06 | image_b64 빈값/누락 | PASS | [실동작] | `""` → 400 `"String should have at least 1 character"`, 키 자체 누락 → 400 `"Field required"`. `schemas.py:151` |
| TC-AISAM2-07 | invalid base64 → 400 | PASS | [실동작] | `"!!!!notb64!!!"` → `400 {"error_code":"INVALID_IMAGE","message":"base64 디코드 실패"}`. `image_utils.py:37-40` + `exceptions.py:37-40`. GIF 헤더 바이트 → 400 `이미지 디코드 실패`(allowlist JPEG/PNG, `image_utils.py:21,76-78`) |
| TC-AISAM2-08 | 크기초과 → 413 | PASS | [실동작] | 11MB 페이로드 → `413 {"error_code":"IMAGE_TOO_LARGE","message":"이미지 크기 한도 초과 (max 10MB)"}`. `image_utils.py:34-35,42-43`. ⚠ 카탈로그 전제 "1MB"는 pytest conftest 설정값이고 배포 실효값은 10MB(케이스 취지 자체는 충족) |
| TC-AISAM2-09 | mask → contour polygon | PASS | [실동작]+[정적] | `sam2.py:147-152` `findContours(RETR_EXTERNAL, CHAIN_APPROX_SIMPLE)` + `max(contours, key=contourArea)`. 실추론 `box=[10,10,60,50]` → 100점 이상 단일 외곽 contour 반환 확인 |
| TC-AISAM2-10 | 빈/비정상 마스크 → None | PASS | [실동작] | `sam2.py:150-151` `if not contours: return None`. 실동작: 영면적 box → `docker logs` `[SAM2] segment real returned no mask — mock fallback` + 응답 `mock_reason=empty_mask` |
| TC-AISAM2-11 | contour 점<3 → skip None | PASS | [정적] | `sam2.py:155-158` `if len(pts) < 3: logger.debug(...); return None`. 실추론으로 2점 이하 최대contour 를 유도하지 못해 실동작 미확인(설계상 도달 희소) |
| TC-AISAM2-12 | ★score 0~1 clamp(음수→0) | PASS | [정적] | `sam2.py:199-201` `score = max(0.0, min(float(sc[best]), 1.0))`. 단위테스트 `test_sam2_meta.py:200 test_음수_score는_0으로_clamp됨`, `:192 test_score가_0_1로_clamp됨`. 실모델은 음수 score 를 재현 못 함(관측 최저 `1.76e-06`, 최고 `0.987` — 모두 구간 내) |
| TC-AISAM2-13 | segment 실추론 예외 → mock fallback | **PARTIAL** | [실동작] | 예외 경로 자체는 확인 — `points=[[1,2,3]]` 로 `sam2/utils/transforms.py` `IndexError` 유발 → `sam2.py:217-220` catch → `200 mock=true, mock_reason=empty_mask`. **그러나 `points=[[5]]`(원소 1개) 는 fallback 함수 `_mock_segment`(`sam2.py:306`)가 다시 `IndexError` 를 내며 `500 INTERNAL_ERROR`** — "크래시 금지·graceful" 규약이 특정 입력에서 무너진다 → **G-ISSUE-41** |
| TC-AISAM2-14 | segment 마스크 없음 → mock fallback | PASS | [실동작] | 영면적/1점 box → `sam2.py:232-235` 경로, 응답 `mock=true,source=mock,mock_reason=empty_mask`, 로그 `[SAM2] segment real returned no mask — mock fallback` |
| TC-AISAM2-15 | track prev_polygon bbox → 다음 세그멘테이션 | PASS | [실동작] | `prev_polygon=[[1,1],[10,1],[10,10]]` → bbox `[1,1,10,10]` 로 predict(`sam2.py:240-247`). 응답 polygon 좌표가 전부 `0~9` 범위(=bbox 근방)로 국한, `mock=false,source=model,score=0.764` — 전체 이미지(100×80) 가 아닌 bbox 영역만 세그먼트됐음이 증명됨 |
| TC-AISAM2-16 | track prev_polygon min_length=3 | PASS | [실동작] | 2점 → `400 "List should have at least 3 items after validation, not 2"`. `schemas.py:177` |
| TC-AISAM2-17 | track 실추론 예외 → 이전폴리곤 fallback | **PARTIAL** | [실동작]+[정적] | `sam2.py:248-260` 존재 + 단위테스트(`test_sam2_meta.py:221`). 동치 경로(마스크 없음) 실동작 확인 — `polygon=prev, score=0.5, mock=true`. **그러나 bbox 계산(`sam2.py:240-242`)이 try 블록 *밖*이라, `prev_polygon` 원소가 2요소가 아니면(`[[1],[2],[3]]`) 예외가 fallback 없이 그대로 새어 `500 INTERNAL_ERROR`** → **G-ISSUE-42** |
| TC-AISAM2-18 | track 마스크 없음 → 이전폴리곤 | PASS | [실동작] | 퇴화 `prev_polygon=[[5,5],[5,5],[5,5]]` → 로그 `[SAM2] track real returned no mask — prev polygon fallback`, 응답 `polygon=[[5,5],[5,5],[5,5]] score=0.5 mock=true mock_reason=empty_mask`. `sam2.py:282-292` |
| TC-AISAM2-19 | track mock 동일 track_id 유지 | PASS | [실동작] | 위 케이스에서 `track_id="d1"` 반사 + `polygon == prev_polygon` 확인. `_mock_track`(`sam2.py:317-326`)도 동일 형태(`score=0.9`). track_id 는 빈 문자열·개행 포함 값도 그대로 반사(→ G-ISSUE-44) |
| TC-AISAM2-20 | segment/track mock 응답 mock=true source=mock | PASS | [실동작] | segment·track 양쪽 fallback 응답에서 `"mock":true,"source":"mock"` 확인. 회귀 테스트 `test_mock_indicator.py:33`(segment)·`:49`(track) 존재(라인 일치) |
| TC-AISAM2-21 | 로더 lazy: ultralytics import 안 함 | PASS | [정적] | `grep -rn ultralytics ai-server/app/` → `models/detector_backend.py:66`·`models/yolox_loader.py:3,6` **주석 3건뿐**, `models/sam2_loader.py`·`routers/sam2.py` **0건**. 테스트 `test_sam2_meta.py:290` 이 정확히 이 두 파일만 검사(라인 일치). sam2/torch/cv2/np 전부 함수 내부 lazy import |
| TC-AISAM2-22 | AI_MOCK_MODE=true → None env_mock | PASS | [정적] | `sam2_loader.py:32-37` — `if settings.ai_mock_mode: _loaded=True; _mock_reason="env_mock"; return None`. 테스트 `test_sam2_meta.py:299`. 배포 env 가 `false` 라 실동작 도달 불가(환경 제약, 결함 아님) |
| TC-AISAM2-23 | 로드 실패 → None reason=load_failed 전파 | PASS | [정적] | `sam2_loader.py:51-58` except → `_mock_reason="load_failed"`, `finally: _loaded=True`(재시도 폭주 방지). 라우터 `sam2.py:96-98` 이 loader 사유를 그대로 전파. 테스트 `test_sam2_meta.py:306`, `:435 test_load_failed시_segment_응답_mock_reason이_load_failed`. 배포에서는 로드 성공이라 실동작 미도달 |
| TC-AISAM2-24 | ★load_failed 를 weights_missing 으로 오표기하지 않음 | PASS | [정적] | `sam2.py:84-101` `_mock_reason()` 이 `get_sam2_mock_reason()` 을 **우선 위임**하고, loader 값이 있으면 그대로 반환 → 미설치/로드실패는 `load_failed`. **관측**: SAM2 는 HF `from_pretrained` 자동 다운로드라 로컬 가중치 개념이 없어 `sam2.py:101` 의 `weights_missing` 폴백은 실경로 도달 불가(테스트가 `get_sam2_model` 을 monkeypatch 해야만 도달 — `test_mock_indicator.py:96,127`). 결함 아님, 사문(死文) 분기 정리 여지 |
| TC-AISAM2-25 | from_pretrained에 ai_device 전달 | PASS | [실동작]+[정적] | `sam2_loader.py:44-47` `SAM2ImagePredictor.from_pretrained(model_id, device=settings.ai_device)`. 실증: `AI_DEVICE=cpu` 컨테이너에서 **실모델이 실제로 로드·추론**됨(`mock=false,source=model`) — device 미전달이면 기본 `"cuda"` AssertionError 로 `load_failed` mock 이 됐어야 함. 파라미터 테스트 `test_sam2_meta.py:352-384` |
| TC-AISAM2-26 | 정상 로드 시 싱글톤 | PASS | [실동작]+[정적] | `sam2_loader.py:27-29` `if _loaded: return _sam2_model` 게이트. 실증: 동일 요청 2회가 **완전히 동일한 score(`1.7551650444147526e-06`)** 와 균일한 응답시간(1.4s)을 반환 — 요청마다 재로드(수십초) 없음. 워밍업은 `main.py:41` lifespan 에서 1회. 테스트 `test_sam2_meta.py:319`(from_pretrained 호출 1회 단언) |
| TC-AISAM2-27 | (real) segment 포인트/박스 응답형식 | PASS | [실동작] | **실 가중치가 로드된 배포에서 직접 확인** — 포인트: `{"polygon":[[0,0],[0,79],[99,79],[99,0]],"score":0.987,"mock":false,"source":"model","mock_reason":null}`, 박스: 100점 이상 contour + `score` 구간 내. 응답 키 8종(`polygon,score,mock,source,mock_reason,success,message,error_code`) 일치. `test_sam2_real.py:56,77` 는 `sam2` 미설치 시 skip 게이트(라인 일치) |
| TC-AISAM2-28 | (real) track prev_polygon 전파 | PASS | [실동작] | 실모델 track: `track_id` 반사 + bbox 영역 polygon 12점 + `score=0.772`, `mock=false,source=model`. `test_sam2_real.py:94` 라인 일치 |

## 3. 테스트 자산 대조

| 파일 | SAM2 관련 테스트 | 비고 |
|------|---|------|
| `ai-server/tests/test_sam2.py` | 3건 (segment 포인트/박스, track ID 유지) | mock 모드(conftest 강제) |
| `ai-server/tests/test_sam2_meta.py` | 17건 (mask→polygon, clamp, 예외 fallback, loader env_mock/load_failed/싱글톤/device, 계약 스키마, box 길이) | 커버리지 양호 |
| `ai-server/tests/test_sam2_real.py` | 3건 | `sam2` 미설치 시 skip — **배포 컨테이너엔 설치돼 있으므로 CI 에서도 실행 가능** |
| `ai-server/tests/test_mock_indicator.py` | 4건 (segment/track mock 표시, weights_missing 2건) | |

**미커버(테스트 공백)**: ①`points`/`prev_polygon` **원소 길이** 이상 입력(G-ISSUE-41·42 — 테스트가 있었으면 잡혔을 회귀) ②`_mask_to_polygon` contour 점<3 경로(TC-11) ③배열 길이 상한(G-ISSUE-45).

> ⚠ 지시에 따라 **빌드/테스트를 실행하지 않았다** — 위는 소스 정적 인벤토리이며 통과 여부는 `_raw/test-baseline.md`(본 회차 미생성) 대조 대상이다.

---

## 4. 이슈

### [G-ISSUE-41] TC-AISAM2-13 — `points` 원소 길이 미검증: 실추론 예외의 mock fallback 자체가 다시 터져 500
- **심각도**: HIGH
- **기대 동작(기대효과)**: `_real_segment` 의 예외 catch(`sam2.py:217-220`)는 "추론 실패해도 크래시 금지(graceful) → mock 응답"이 계약이다(주석 `graceful: 추론 실패는 mock fallback (크래시 금지)`). 어떤 입력이든 200 mock 또는 400 검증오류여야 하며, 500 이 나오면 BE(`Sam2SegmentService`)는 `EXTERNAL_API_ERROR` 로 승격시켜 라벨링 화면의 SAM2 분할이 통째로 실패한다.
- **현재 동작(이슈 내용)**: `Sam2SegmentRequest.points` 가 `list[list[float]]` 로만 선언돼(`ai-server/app/schemas.py:152`) **내부 리스트의 원소 개수를 검증하지 않는다.** 원소가 1개인 포인트를 보내면 ①실추론이 `IndexError` → ②`sam2.py:217-220` 이 잡아 `_mock_segment` 호출 → ③`_mock_segment` 가 같은 이유로 다시 `IndexError` → 미처리 → 500.
  ```python
  # ai-server/app/schemas.py:152
  points: list[list[float]] | None = Field(default=None, description="[[x, y], ...] 클릭 좌표")
  # ai-server/app/routers/sam2.py:305-308  (_mock_segment)
  elif req.points:
      cx, cy = req.points[0][0], req.points[0][1]   # ← IndexError (fallback 안에서 재발)
  ```
  실측 로그(`docker logs klid-ai-server`):
  ```
  ERROR:app.routers.sam2:[SAM2] segment real predict 실패 — mock fallback
  ...  File "/app/app/routers/sam2.py", line 220, in _real_segment
         return _mock_segment(width, height, req, "empty_mask")
       File "/app/app/routers/sam2.py", line 306, in _mock_segment
         cx, cy = req.points[0][0], req.points[0][1]
  IndexError: list index out of range
  ERROR:app.exceptions:[AI] unhandled exception type=IndexError
  ```
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:19300/infer/sam2/segment \
    -H 'Content-Type: application/json' \
    -d '{"image_b64":"<유효 PNG base64>","points":[[5]]}'
  # 실측: 500 {"error_code":"INTERNAL_ERROR","message":"서버 내부 오류"}
  # 대조: points=[[1,2,3]] 은 200 mock (fallback 성공) — 1원소일 때만 fallback 이 재폭발
  ```
- **영향**: CWE-20(Improper Input Validation) + CWE-755(Improper Handling of Exceptional Conditions). 보안 노출은 없으나(스택트레이스 비노출, `exceptions.py:65-69` 확인) **fail-graceful 설계가 특정 입력에서 무효화**된다. `rules/security.md` "Mishandling of Exceptional Conditions(A10:2025) — 예외 발생 시 안전한 기본값" 위반. BE 가 SAM2 분할 요청 좌표를 그대로 릴레이하므로 FE 버그·악의적 요청 어느 쪽으로도 도달 가능.
- **수정 방향(제안)**:
  1. `schemas.py:152` — `points` 원소에 길이 제약 부여(`list[Annotated[list[float], Field(min_length=2, max_length=2)]]`) → 400 VALIDATION_ERROR 로 앞단 차단.
  2. `sam2.py:_mock_segment` — `req.points` 접근을 방어적으로(`len(req.points[0]) >= 2` 확인 후, 아니면 중앙 폴백 분기 사용)해 **fallback 은 어떤 입력에도 절대 예외를 던지지 않도록** 한다.
  3. 회귀 테스트: `tests/test_sam2_meta.py` 에 "원소 1개 point → 400 또는 200 mock, 500 금지" 케이스 추가.

### [G-ISSUE-42] TC-AISAM2-17 — track bbox 계산이 try 블록 밖이라 `prev_polygon` 이상 원소에서 fallback 없이 500
- **심각도**: HIGH
- **기대 동작(기대효과)**: `_real_track` 은 추론 실패 시 **이전 폴리곤을 그대로 반환**해 트랙이 끊기지 않게 하는 것이 계약이다(`sam2.py:248-260`, 주석 `graceful: 추론 실패는 이전 폴리곤 mock fallback (크래시 금지)`). SAM2 Track 은 SFR-08-01(VOS) 의 핵심 경로라 500 이 나면 N프레임 전파가 중단된다.
- **현재 동작(이슈 내용)**: bbox 유도 코드가 **try 진입 전(`sam2.py:240-242`)** 에 있어 여기서 난 예외는 어떤 fallback 도 타지 못한다. 게다가 `prev_polygon` 역시 원소 길이 미검증(`schemas.py:177` 은 바깥 리스트 `min_length=3` 만 검사).
  ```python
  # ai-server/app/routers/sam2.py:240-244  (_real_track)
  xs = [p[0] for p in req.prev_polygon]
  ys = [p[1] for p in req.prev_polygon]      # ← 원소가 1개면 IndexError, try 밖이라 미포착
  bbox = [min(xs), min(ys), max(xs), max(ys)]
  pil_image = decode_image_b64_pil(req.next_image_b64)
  try:                                        # ← 보호 구간은 여기서야 시작
  ```
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:19300/infer/sam2/track \
    -H 'Content-Type: application/json' \
    -d '{"track_id":"t3","prev_polygon":[[1],[2],[3]],"prev_image_b64":"<PNG b64>","next_image_b64":"<PNG b64>"}'
  # 실측: 500 {"error_code":"INTERNAL_ERROR","message":"서버 내부 오류"}
  ```
  (참고: 정상 3원소 퇴화 폴리곤 `[[5,5],[5,5],[5,5]]` 는 정상적으로 200 + prev 폴리곤 fallback → 보호 구간 자체는 동작)
- **영향**: CWE-20 + CWE-755. `_real_segment` 와 달리 **예외 처리 경계가 잘못 그어진 구조적 결함**이라 향후 bbox 유도 로직을 확장하면 같은 함정이 재발한다. G-ISSUE-41 과 동일 근인(원소 길이 미검증)이나 **fallback 이 아예 존재하지 않는 구간**이라는 점에서 별건.
- **수정 방향(제안)**:
  1. `schemas.py:177` — `prev_polygon` 원소에 `min_length=2, max_length=2` 제약 부여.
  2. `sam2.py` — bbox 유도(240-242)를 `try` 블록 **안**으로 이동하거나, 전용 `_polygon_bbox()` 헬퍼로 뽑고 실패 시 prev-polygon fallback 을 반환하도록 감싼다.
  3. 회귀 테스트: `_real_track` 에 이상 폴리곤 입력 시 500 이 아님을 단언하는 케이스 추가.

### [G-ISSUE-43] TC-AISAM2-02/03 — mock 폴리곤이 입력 좌표를 무검증 반사해 이미지 밖·영면적·비현실 좌표를 생성
- **심각도**: LOW
- **기대 동작(기대효과)**: mock/fallback 응답도 **그 이미지 안의 유효한 폴리곤**이어야 한다. ai-server 는 이미지 해상도(`width,height`)를 이미 알고 있으므로(`sam2.py:35`, `:207`) 경계 clamp 와 최소 면적 보장이 가능하다.
- **현재 동작(이슈 내용)**: `_mock_segment` 가 요청 box/point 를 그대로 폴리곤으로 되돌린다(clamp·유효성 검사 없음).
  ```python
  # ai-server/app/routers/sam2.py:303-311
  if req.box and len(req.box) == 4:
      x1, y1, x2, y2 = req.box              # ← 경계·면적 검증 없이 그대로
  elif req.points:
      cx, cy = req.points[0][0], req.points[0][1]
      half = min(width, height) * 0.1
      x1, y1, x2, y2 = cx - half, cy - half, cx + half, cy + half   # ← 음수 가능
  polygon = [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]
  ```
  실측(100×80 이미지):
  | 입력 | mock 폴리곤 |
  |---|---|
  | `points=[[1,2,3]]` | `[[-7,-6],[9,-6],[9,10],[-7,10]]` — **음수 좌표** |
  | `box=[0,0,0,0]` | `[[0,0],[0,0],[0,0],[0,0]]` — **영면적(퇴화)** |
  | `box=[1e308]*4` | `[[1e308,…]]` — 비현실 좌표 |
- **재현/확인 경로**: 위 표의 3개 요청을 `POST /infer/sam2/segment` 로 전송(전부 200).
- **영향**: 기능 영향 제한적 — BE `Sam2SegmentService`(`backend/.../label/service/Sam2SegmentService.java:126-131`)가 `aiRes.untrusted()` 로 mock 응답을 **빈 폴리곤으로 치환**하고, 통과하더라도 `validatePolygon(polygon, imgWidth, imgHeight)` 이 좌표 상한을 검증한다. 즉 **현재는 BE 방어에 의해 흡수**된다. 다만 ai-server 를 다른 소비자가 직접 호출하면 방어가 없고, `CLAUDE.md` ★3(AI 검출 응답 = clamp) 의 정신과도 어긋난다.
- **수정 방향(제안)**: `_mock_segment` 에서 `x1,y1,x2,y2` 를 `[0,width]`/`[0,height]` 로 clamp하고, clamp 후 면적이 0 이면 중앙 사각(`sam2.py:310`) 분기로 폴백. (⚠ 구현하지 않음)

### [G-ISSUE-44] TC-AISAM2-19 — `track_id` 를 정제 없이 로그 포맷에 투입(Log Injection 잠재)
- **심각도**: LOW
- **기대 동작(기대효과)**: 사용자/상위 시스템이 제어하는 문자열은 로그 출력 전 개행(`\n`,`\r`) 제거가 필요하다(`rules/security.md` — CWE-117 Log Injection, BE 는 `LogSanitizer` 로 이미 이 규약을 지킨다).
- **현재 동작(이슈 내용)**: `track_id` 가 원문 그대로 로그 포맷 인자로 들어간다.
  ```python
  # ai-server/app/routers/sam2.py:57-62, 67-75
  logger.info("[SAM2][MOCK] track reason=%s track_id=%s points=%d", reason, req.track_id, ...)
  logger.info("[SAM2] track received track_id=%s prev=%dx%d next=%dx%d points=%d", req.track_id, ...)
  ```
  `track_id="a\nINJECT b"` 요청은 **200 으로 처리되고 응답에도 그대로 반사**된다(실측). 다만 현재 배포 컨테이너는 **앱 로거의 INFO 가 출력되지 않아**(`docker logs` 에 `[SAM2] track received` 0건, WARNING/ERROR 만 출력) **미발현 상태**다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:19300/infer/sam2/track -H 'Content-Type: application/json' \
    -d '{"track_id":"a\nINJECT b","prev_polygon":[[1,1],[9,1],[9,9]],"prev_image_b64":"<b64>","next_image_b64":"<b64>"}'
  # 실측 200, 응답 track_id 에 개행 그대로. 로그 레벨을 INFO 로 올리면 로그 라인 위조 가능
  ```
- **영향**: CWE-117. 로그 레벨을 INFO 로 올리거나 로그 수집기를 붙이는 순간 위조 라인이 삽입된다(감사 추적 오염). 현재 심각도는 낮으나 **로그 설정 변경 한 번으로 활성화되는 잠재 결함**이다.
- **수정 방향(제안)**: `track_id` 에 스키마 제약(`pattern=r"^[A-Za-z0-9_\-:.]{1,64}$"` 등)을 걸어 앞단에서 차단하거나, ai-server 공통 `sanitize()` 헬퍼로 개행 제거 후 로깅. (⚠ 구현하지 않음)

### [G-ISSUE-45] TC-AISAM2-05/16 인접 — `points`/`prev_polygon` 배열 길이 상한 부재(CWE-770)
- **심각도**: LOW
- **기대 동작(기대효과)**: `image_b64` 는 크기 상한(`max_image_size_mb`)과 픽셀 상한(`MAX_IMAGE_PIXELS=50M`)이 이중으로 걸려 있다(`image_utils.py:34,79`). 동일한 리소스 소비 축인 **프롬프트 배열도 상한**이 있어야 일관된다(`rules/security.md` — Unrestricted Resource Consumption / OWASP API4:2023).
- **현재 동작(이슈 내용)**: 배열 길이 상한이 없다.
  ```python
  # ai-server/app/schemas.py:152
  points: list[list[float]] | None = Field(default=None, ...)          # max_length 없음
  # ai-server/app/schemas.py:177
  prev_polygon: list[list[float]] = Field(..., min_length=3, ...)      # 하한만 있고 상한 없음
  ```
  실측(부하 억제를 위해 1,000 원소까지만 시도): `points=1`/`100`/`1000` 각각 1.43s/1.49s/1.57s, `prev_polygon=1000` 1.44s — **1,000 규모에서는 영향 미미**(SAM2 가 프롬프트를 배치 처리). 상한이 없다는 사실만 확인.
- **재현/확인 경로**: `POST /infer/sam2/segment` 에 `points` 를 대량(수십만 개) 실어 전송. JSON 파싱·numpy 변환 메모리가 요청 크기에 선형 비례한다. (본 검증에서는 공유 환경 보호를 위해 1,000 까지만 실행)
- **영향**: CWE-770. ai-server 는 무상태·무인증이라 내부망에서 직접 도달 가능하고, GPU/CPU 자원을 BE 배치와 공유하므로 대량 프롬프트 요청이 배치 추론 지연으로 번질 수 있다. 실측상 즉각적 DoS 는 아니어서 LOW.
- **수정 방향(제안)**: `points` 에 `max_length`(예: 64), `prev_polygon` 에 `max_length`(예: 4096) 부여. 아울러 uvicorn/게이트웨이 레벨 요청 바디 상한 설정 검토. (⚠ 구현하지 않음)

---

## 5. 근거 드리프트

**0건.** 카탈로그 28행의 `file:line` 을 전수 대조한 결과 전부 현행과 일치(대표: `routers/sam2.py:299-314/303-304/309-310/134-163/150-151/155-158/199-201/217-220/232-235/238-247/248-260/282-292/317-326/84-101`, `schemas.py:148-155/151/153-155/177`, `image_utils.py:37-44/42-43`, `sam2_loader.py:32-37/44-47/51-58/27-59`, `test_mock_indicator.py:33,49`, `test_sam2_meta.py:290`, `test_sam2_real.py:56,77,94`).
`ai-server/` 는 2026-07-25 이후 코드 변경이 없다는 카탈로그 기술과 일치(워킹트리 미커밋 변경 0건).

## 6. UNCERTAINTIES 갱신 제안 (사실 확정분)

| # | 항목 | 기존 | **본 회차 실측** |
|---|------|------|------|
| 15 | 실모델 테스트 게이팅 | 미해소 유지 | **✅ 부분 확정** — 배포 컨테이너 `klid-ai-server` 에 `sam2`(Meta, `requirements.txt:177`) 가 **실제 설치·로드**돼 있고 실추론이 동작한다(`mock=false, source="model"`). 따라서 `test_sam2_real.py` 의 `skipif(find_spec("sam2") is None)` 게이트는 **이 이미지 안에서는 skip 되지 않는다**. 로컬 호스트 `.venv` 기준으로만 "미설치 skip" 이었던 것이며, 컨테이너 기준으로 실모델 테스트를 상시 실행하도록 baseline 방침 갱신 가능 |
| — | (신규) SAM2 `weights_missing` 사유 | — | SAM2 는 HF 자동 다운로드 방식이라 `weights_missing` 이 **실경로에서 도달 불가**한 사문 분기(`routers/sam2.py:101`). YOLOX(로컬 ONNX 파일)와 사유 어휘를 공유하면서 생긴 잔재 — 정리 여부 판단 필요 |
# G 클러스터 검증 결과 — part4 (G-5 VLM verify-objects 14건 + G-10 BE↔벤더 실배선 13건 = 27건)

> 검증일 2026-08-02 · 회차 2차 · 담당 범위: `docs/test-cases/G-ai-server.md` §G-5, §G-10
> 코드 기준: worktree `qa-0801` · **수정 없음(검증 전용)**

## 검증 환경 (실측)

| 항목 | 실측값 |
|---|---|
| 컨테이너 | `klid-postgres` · `klid-backend`(18081→8080) · `klid-ai-server`(19300→9300) · `klid-mock-server`(9400) · `klid-frontend` 전부 Up(healthy) 19h |
| backend 실효 env | `SPRING_PROFILES_ACTIVE=local` · `VLM_CLIENT_ENABLED=true` · `VLM_SERVICE_URL=http://klid-mock-server:9400` · `VLM_ALLOW_INSECURE_URL=true` · `KPST_DEID_ENABLED=true` · `KPST_DEID_BASE_URL=http://klid-mock-server:9400` · `DEIDENTIFY_MOCK_MODE=false` · `AI_SERVER_URL=http://klid-ai-server:9300` |
| ai-server 실효 env | `AI_MOCK_MODE=false`, `AI_DEVICE=cpu` (→ VLM/YOLOX 가중치 부재로 `mock_reason=weights_missing`) |
| 네트워크 | backend=172.20.0.5 · mock-server=172.20.0.4 |
| 외부 연동 self-fill 여부 | **없음** — describe→callback 왕복이 mock-server 로그로 실측됨(아래 TC-AIMOCK-45) |

**내부 목 모드 우회 없음 확인**: `DEIDENTIFY_MOCK_MODE=false` 이며 KPST 위탁·VLM describe 모두 실제 HTTP 로 mock-server 를 경유한다. ai-server 는 `AI_MOCK_MODE=false` 이나 **가중치 파일 부재**로 mock 응답(=설계상 정상, TC-AIVLM-03 참조).

---

## G-5. VLM — ai-server 자체 `/infer/vlm/verify-objects` (14건)

전 케이스를 **기동 중인 ai-server(:19300)에 실제 HTTP 요청**으로 판정했다.

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|:--:|---|---|
| TC-AIVLM-01 | PASS | [실동작] | `POST /infer/vlm/verify-objects` `expected_label="person"` → `{"results":[{"obj_id":"o1","expected_label":"person","verified":true,"confidence":0.92}],"mock":true,"source":"mock","mock_reason":"weights_missing"}` HTTP 200. 코드 `ai-server/app/routers/vlm.py:89-104` 일치(근거 라인 정확) |
| TC-AIVLM-02 | PASS | [실동작] | `"unicorn"` → `verified:false, confidence:0.18` HTTP 200. `vlm.py:93-100` 일치 |
| TC-AIVLM-03 | PASS | [실동작] | `AI_MOCK_MODE=false` 인데도 응답이 `mock:true source:"mock" mock_reason:"weights_missing"` — 실 VLM 미구현이 응답에 명시된다. `vlm.py:38-55` 는 `_should_mock()` 분기 없이 **무조건** `_mock_verify` 를 반환(항상 mock). reason ∈ {env_mock, weights_missing, not_implemented} 화이트리스트도 `vlm.py:63-69` 로 확인. UNCERTAINTIES #13 의 "이 엔드포인트 한정" 서술과 정합 |
| TC-AIVLM-04 | PASS | [실동작] | `"PERSON"` → `verified:true 0.92` (`obj.expected_label.lower()` `vlm.py:93`). 응답의 `expected_label` 은 입력 원문("PERSON") 그대로 반사 |
| TC-AIVLM-05 | PASS | [실동작] | `objects:[]` → HTTP **400** `{"error_code":"VALIDATION_ERROR","message":"List should have at least 1 item after validation, not 0"}`. `schemas.py:207` `min_length=1` (근거 라인 정확) |
| TC-AIVLM-06 | PASS | [실동작] | `bbox:[1,2,3]` → HTTP **400** "List should have at least 4 items". `schemas.py:200` (정확) |
| TC-AIVLM-07 | PASS | [실동작] | `obj_id` 누락 → HTTP **400** "Field required". `schemas.py:195-200` (정확) |
| TC-AIVLM-08 | PASS | [실동작] | `image_b64:"!!!notb64!!!"` → HTTP **400** `{"error_code":"INVALID_IMAGE","message":"base64 디코드 실패"}`. `vlm.py:45` → `image_utils._decode_raw` `validate=True` |
| TC-AIVLM-09 | PASS | [실동작] | 14MB base64 페이로드 → HTTP **413** `{"error_code":"IMAGE_TOO_LARGE","message":"이미지 크기 한도 초과 (max 10MB)"}`. `image_utils.py:42-43`(정확) + 선행 길이 추정 컷 `:34-35`. ⚠ 케이스 전제 "1MB" 는 `tests/conftest.py:18 MAX_IMAGE_SIZE_MB=1` 기준이고 런타임 컨테이너는 기본 10MB — **413 동작 자체는 동일** |
| TC-AIVLM-10 | PASS | [실동작] | 최상위 추가필드(`"bogus":1`) → 400, objects 원소 내 추가필드(`"zz":2`) → 400 둘 다 "Extra inputs are not permitted". 근거 라인은 드리프트(아래 G-ISSUE-63) |
| TC-AIVLM-11 | PASS | [실동작] | `[dog, unicorn, car]` 순서 입력 → 응답 `results` 가 `z(dog,true) → a(unicorn,false) → m(car,true)` 로 **입력 순서 그대로**, obj_id/expected_label 반사. `vlm.py:91-101` |
| TC-AIVLM-12 | PASS | [실동작] | `POST /infer/vlm/video-meta` → HTTP **404** `{"detail":"Not Found"}`. 라우터에 verify-objects 만 등록(`vlm.py:38`). 테스트 `tests/test_vlm.py:52 test_vlm_router_video_meta_endpoint_removed` 커버 |
| TC-AIVLM-13 | PASS | [실동작] | `docker logs klid-ai-server | grep -c "[VLM][MOCK]"` = **1** — 12회 verify-objects 호출 동안 WARN 1회만. `vlm.py:72-80` 전역 플래그(단일 uvicorn 프로세스 `Started server process [1]` 확인) |
| TC-AIVLM-14 | PASS | [정적] | `AiServerClient.java:91` `.uri("/infer/vlm/verify-objects")` (base=`aiServerWebClient`←`AI_SERVER_URL:9300`) vs `VlmClient.java:53` `DESCRIBE_PATH="/v1/videovlm/describe"` (base=`vlmWebClient`←`vlm.client.url:9400`). 클라이언트 클래스·경로·베이스URL 전부 상이. **역방향 반증**: `grep -rn "infer/vlm" backend/src frontend/src` → BE 클라이언트 3곳뿐, FE 0건. 추가 실측 — ai-server 접근 로그의 verify-objects 12건이 **전부 172.20.0.1(호스트=검증자 curl)** 이고 backend(172.20.0.5)발 0건 → **프로덕션 호출부 자체가 없다**(별건 관측, G-ISSUE-62) |

**G-5 소계**: PASS 14 / FAIL 0 / PARTIAL 0 / N/A 0.

---

## G-10. BE ↔ 외부 벤더 실배선 계약 (13건)

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|:--:|---|---|
| TC-AIMOCK-34 | PASS | [실동작] | mock-server 접근 로그에 **`172.20.0.5:53934 - "GET / HTTP/1.1" 200 OK`**(총 25건) — backend 가 벤더 루트를 핑한다. `DeidentifyHealthIndicator.java:92-104` `kpstWebClient.get().uri("/")` (근거 라인 정확), 성공 시 `mode=kpst` UP. 집계 `/api/actuator/health` = `{"status":"UP"}`. 테스트 `DeidentifyHealthIndicatorTest:89 실모드_핑은_벤더가_제공하는_루트경로로_요청한다` 커버. ⚠ 참고: `retrieve().toBodilessEntity()` 라 실벤더 루트가 401/403/404 를 주면 DOWN 오탐 — 기대결과("200 대이면 UP")와는 정합 |
| TC-AIMOCK-35 | PASS | [정적] | `DeidentifyHealthIndicator.java:77-83` — `mockMode` 분기가 **최우선**이라 핑 없이 `UP/mode=mock`. 실행 경로 우선순위와 일치 확인(`DeidentifyStep.java:44-45,256-257` ①mockMode ②KPST ③거부) → 헬스↔실행 판정 축 동일. 테스트 `DeidentifyHealthIndicatorTest:59` 커버. (런타임은 `DEIDENTIFY_MOCK_MODE=false` 라 설정 변경 없이는 실동작 재현 불가 — 설정 수정 금지 규칙 준수) |
| TC-AIMOCK-36 | PASS | [정적] | `:84-91` `if (!kpstEnabled \|\| kpstWebClient == null)` → `DOWN, mode=unconfigured, error=NoDeidentifyPathConfigured` (문자열 일치). 테스트 `:122 mock도_KPST위탁도_아니면_핑없이_DOWN_설정오류` 커버 |
| TC-AIMOCK-37 | PASS | [정적] | `:105-112` catch 에서 `e.getClass().getSimpleName()` 만 노출 — 스택트레이스·URL·내부경로 미노출(CWE-209). 테스트 `:105 실모드_서버타임아웃시_DOWN` 커버 |
| TC-AIMOCK-38 | PASS | [실동작] | 기동 로그 실측: `WARN [VlmUrl] 평문/사설 URL 완화가 활성화됨 — 개발 프로파일 전용. property=vlm.client.allow-insecure-url` + `WARN [ExternalUrl] 평문 HTTP 전송 — 내부망 격리 전제. property=vlm.client.url host=klid-mock-server:9400` → local + flag=true 에서 relaxed(`ExternalUrlPolicy.internalNetwork`, `:80-82`) 적용되어 평문 http·사설 IP 목업으로 정상 기동. `ProfileGatedUrlPolicy.java:121-133`(check/policy) `:145-151`(profileAllowsRelaxation) 근거 라인 정확 |
| TC-AIMOCK-39 | PASS | [정적] | `ProfileGatedUrlPolicy.java:78-91` `@PostConstruct verifyRelaxationScope()` — allowlist `{local,dev}` `containsAll`(혼합·미지정 자동 엄격) + `ENV∈{stg,prd}` 독립축 우선 거부 → `IllegalStateException` 기동 중단. **토글(enabled)과 무관하게** 검사(:74-76 주석 및 코드 확인). 테스트 3종 커버: `VlmUrlPolicyBootGuardTest:23 prd_프로파일에서_완화_플래그가_켜져있으면_컨텍스트_기동이_실패한다`, `VlmUrlPolicyTest:79/:98/:137` |
| TC-AIMOCK-40 | PASS | [정적] | `ExternalUrlPolicy.java:143-153 rejectMetadataRangeIfResolvable` — relaxed 경로에서도 `isLinkLocalAddress()`(fe80::/10 포함) 또는 `169.254.` 접두면 `IllegalStateException`. 테스트 `VlmUrlPolicyTest:161 링크로컬_메타데이터_대역은_완화_프로파일에서도_차단된다`. ⚠ 한계 1건 기록(G-ISSUE-65: `InetAddress.getByName` 단일 주소만 검사) |
| TC-AIMOCK-41 | PASS | [정적] | relaxed: `:144-147` 해석 실패 → `return`(통과) / strict: `:169-175` `UnknownHostException` → `IllegalStateException`. 테스트 `VlmUrlPolicyTest:179 해석되지_않는_컨테이너명은_완화_프로파일에서_계속_허용된다`. [실동작] 보강 — 컨테이너 내부에서 `klid-mock-server` 해석 가능한 상태로 relaxed 기동 성공 |
| TC-AIMOCK-42 | PASS | [정적] | `VlmClient.java:100-112` 파이프라인 + `:154-169 validateResponse` — ①`request_id` echo 불일치 ②`status != "accepted"` 각각 `CustomException(EXTERNAL_API_ERROR)`. 근거 라인 정확(88-112, 154-169). 테스트 `VlmClientTest:141 응답_request_id_echo_불일치_시_EXTERNAL_API_ERROR` / `:156 응답_status_accepted_아니면_EXTERNAL_API_ERROR` 커버. ⚠ 실동작 재현 불가 — mock-server(`app/routers/vlm.py:172-178 _resolve_request_id`)가 요청 request_id 를 항상 echo 하고 응답 조작 훅이 없다. ⚠ 참고: 검증 실패 예외는 `ignore-exceptions` 대상이 아니라 **재시도 3회** 대상(동일 request_id 재위탁) — 계약 위반은 아님 |
| TC-AIMOCK-43 | PASS | [정적] | `VlmClient.java:106` `.onStatus(HttpStatusCode::is4xxClientError, this::toNonRetryable4xx)` → `:121-127` 본문 release 후 `NonRetryableExternalException`(상태코드만, CWE-209). 설정 실측: circuitbreaker `application.yml:532-539 ignore-exceptions: NonRetryableExternalException`, retry `:606-612` 동일 등록 → 재시도·서킷 failure 집계 양쪽에서 제외. 테스트 `VlmClientTest:221 V1_400_형식오류는_재시도없이_1회요청` / `:236 V1_422...` 커버. 근거 라인 드리프트(G-ISSUE-63) |
| TC-AIMOCK-44 | N/A | [정적] | **케이스 전제(45s 블록 계층)가 코드에 없다.** `VlmTimeseriesStep` 은 Phase C-1 논블로킹 전환으로 `.block(45s)` 를 폐지하고 `:366 .subscribe(...)` 로 즉시 반환한다 — `BLOCK_TIMEOUT` 상수 부재, 45s 는 `:70,:348-349` **주석(구 코드 설명)** 에만 남아 있다. 근거로 지정된 `VlmTimeseriesStep.java:78` 은 현재 javadoc 라인. 현존 타임아웃은 `vlm.client.timeout-seconds:10`(`application.yml:709`) → `VlmClient.java:76,108` **단일 계층**. 미구현 갭이 아니라 **의도된 개선**(테스트 `VlmTimeseriesStepNonBlockingTest:106 ACK가_끝내_오지_않아도_제출호출이_파이프라인_스레드를_붙잡지_않는다` 가 반대 방향을 강제)이므로 FAIL 승격하지 않고 카탈로그 정정 대상으로 기록(G-ISSUE-61) |
| TC-AIMOCK-45 | PASS | [실동작] | **self-fill 없음 실증.** backend 로그 `[Vlm] describe submit request_id=847751b3-…` → mock-server 로그 `POST /v1/videovlm/describe 200` + `[MOCK][VLM] describe accepted request_id=847751b3-… callback_url=http://klid-backend:8080/api/v1/vlm/callback` → `POST http://klid-backend:8080/api/v1/vlm/callback "HTTP/1.1 200"` → backend `[Webhook][Vlm] result applied request_id=847751b3-… rawSn=80 new=1 markingsTransitioned=1`. 왕복 3건 관측(rawSn 80·81·94). 공통 기본값은 `application.yml:702 enabled: ${VLM_CLIENT_ENABLED:false}` → `VlmClient.java:94-97` 즉시 SKIPPED(근거 라인 정확), local 은 `application-local.yml:147 enabled: ${VLM_CLIENT_ENABLED:true}`(근거 표기 150 은 드리프트) |
| TC-AIMOCK-46 | PASS | [정적] | `VlmTimeseriesStep.java:303-306` — `deidentReportGate.isUnderDeidentReport(rawSn)` 이 참이면 `recordVlmSkipped(rawSn, SKIP_REASON_DEIDENT_REPORT)` 후 `skipped(null)` 반환. **게이트 위치가 `resolveDeidentifiedPath(rawSn)`(:308) 직전**이라 외부 전송 0건 확정이고, `run`/`runWithMarking` 공용 본체 `doSubmit` 안이라 dev 트리거 등 public 진입점도 우회 불가(:238,:254). 사유 상수 `:113`. DB 적재 실측: `SELECT proc_step_cd, proc_stts_cd, count(*) FROM ls_batch_proc_log WHERE proc_step_cd LIKE '%VLM%'` → `VLM/SKIPPED 10건`, `VLM/FAILED 17건` — SKIPPED 기록 경로가 실제로 동작함(현 데이터셋에 신고 사유 행은 없음). 재개 배선 `VlmWithheldResumeRunner` 존재. 테스트 `VlmTimeseriesStepTest:113 비식별_신고_구간_영상은_외부_VLM_호출_0건이고_보류로_기록된다` / `:137 신고가_해소되면_같은_영상의_VLM_위탁이_재개된다` + `VlmWithheldResumeRunnerTest` 커버. 근거 라인 드리프트(G-ISSUE-63) |

**G-10 소계**: PASS 12 / FAIL 0 / PARTIAL 0 / N/A 1.

---

## 판정 집계 (part4 합계 27건)

| 판정 | 건수 |
|---|--:|
| PASS | 26 |
| FAIL | 0 |
| PARTIAL | 0 |
| BLOCKED | 0 |
| N/A | 1 (TC-AIMOCK-44) |
| 확인필요 | 0 |

**self-fill 결함**: 0건. VLM 시계열은 BE→mock-server 실 HTTP 왕복 + 콜백 수신으로 값이 조달됨을 로그로 실증했고(TC-AIMOCK-45), 비식별 헬스도 실제 벤더 루트 핑을 수행한다(TC-AIMOCK-34). ai-server `verify-objects` 의 고정 응답은 **응답에 `mock:true/source:"mock"/mock_reason` 을 명시**하는 설계된 미구현 표식이라 self-fill(외부 응답을 흉내 내 숨기는 자체 채움)에 해당하지 않는다.

---

## 이슈 대장

### [G-ISSUE-61] TC-AIMOCK-44 — VLM 타임아웃 "2계층(45s 블록 + 10s WebClient)" 전제가 코드에 없음 (논블로킹 전환으로 폐지)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스는 "WebClient 자체 10s(`vlm.client.timeout-seconds`) 안쪽에 배치 오케스트레이션 블록 45s(`BLOCK_TIMEOUT`)" 라는 **2계층 타임아웃**이 존재하고, describe 무응답 시 10s 가 먼저 발화하며 스텝 상한이 45s 임을 보장해야 한다고 기술한다. 외부 지연이 파이프라인 스레드를 무한 점유하지 않도록 하는 안전장치의 존재 확인이 목적이다.
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep` 은 Phase C-1 에서 **논블로킹 제출**로 전환되어 `.block(45s)` 와 `BLOCK_TIMEOUT` 상수가 **삭제**됐다. 45s 는 폐지된 구현을 설명하는 주석에만 남는다.
  - `backend/.../batch/step/VlmTimeseriesStep.java:76` (javadoc) — `<li><b>제출</b> — {@code subscribe} 만 하고 즉시 반환({@code status="submitted"}).</li>`
  - `VlmTimeseriesStep.java:348-349` (주석) — `구 코드는 .block(45s) 로 파이프라인 스레드(batch-async- / Quartz 워커 …)를 최대 45초 붙잡았다.`
  - `VlmTimeseriesStep.java:366` — `.subscribe(`
  - 현존 타임아웃 1계층: `VlmClient.java:76` `this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));` ← `application.yml:709 timeout-seconds: 10`, 적용 지점 `VlmClient.java:108 .timeout(timeout)`
  - 케이스가 지정한 근거 `VlmTimeseriesStep.java:78` 은 현재 javadoc 라인(코드 아님)
- **재현/확인 경로**: `grep -n "BLOCK_TIMEOUT\|block(" backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java` → 매치 0건(주석의 `.block(45s)` 문자열만). 반대 방향을 강제하는 회귀 가드: `backend/src/test/java/.../batch/step/VlmTimeseriesStepNonBlockingTest.java:106 ACK가_끝내_오지_않아도_제출호출이_파이프라인_스레드를_붙잡지_않는다`
- **영향**: 코드 결함 아님(논블로킹 전환은 배치 풀 고갈을 막는 의도된 개선). 다만 **카탈로그가 사문화된 기대값을 들고 있어** 다음 회차에서 "45s 계층이 없다 → FAIL" 로 오판정하거나, 반대로 45s 를 되살리는 회귀 수정을 유발할 수 있다. UNCERTAINTIES #13 의 "타임아웃은 이중 구조 — 블록 상한 45s ↔ 실효 10s" 서술도 같은 이유로 낡았다.
- **수정 방향(제안)**: 코드 수정 없음. `docs/test-cases/G-ai-server.md` TC-AIMOCK-44 의 기대결과를 "타임아웃은 `vlm.client.timeout-seconds`(기본 10s) **단일 출처**이며, 스텝은 ACK 를 기다리지 않고 즉시 반환한다(미회신은 `VlmSubmitPendingSweeper` `staleTimeoutMinutes=30` 이 회수)" 로 교체하고 근거를 `VlmClient.java:76,108` + `VlmTimeseriesStep.java:366` 으로 갱신. `UNCERTAINTIES.md` #13 의 이중구조 문구도 동일 정정.

### [G-ISSUE-62] TC-AIVLM-14 파생 — `AiServerClient.verifyObjects` 프로덕션 호출부 0건 (G-5 엔드포인트 전체가 미사용 표면)
- **심각도**: LOW
- **기대 동작(기대효과)**: ai-server `/infer/vlm/verify-objects` 는 "YOLO/SAM2 검출 결과의 라벨 정합성 검증" 목적으로 노출된 엔드포인트이고, BE 는 `AiServerClient.verifyObjects` 로 이를 호출하는 것이 카탈로그 §G-5 전제다. 노출된 추론 표면은 실제로 사용되거나, 사용되지 않으면 제거되어야 한다(미사용 API 표면 = OWASP API9:2023 Improper Inventory Management).
- **현재 동작(이슈 내용)**: 클라이언트 메서드는 존재하나 **`src/main` 어디에서도 호출되지 않는다.**
  - `backend/.../common/client/AiServerClient.java:89-98` — `public Mono<VlmVerifyResponse> verifyObjects(VlmVerifyRequest request) { return webClient.post().uri("/infer/vlm/verify-objects") … }`
  - `grep -rn "verifyObjects" backend/src/main backend/src/test` → `src/main` 은 정의부 1건뿐, 호출부 0건. `src/test` 도 실호출 없이 `AiInferenceDeidentReportGateTest.java:71` 주석 언급 1건
  - `grep -rn "infer/vlm" backend/src frontend/src` → BE 3건(정의/주석/DTO 주석), FE 0건
  - [실동작] ai-server 접근 로그 집계: `POST /infer/vlm/verify-objects` 12건이 **전부 172.20.0.1(호스트 = 본 검증자 curl)** 이고 backend(172.20.0.5) 발신 0건. 같은 로그에서 `POST /infer/sam2/track` 138건 · `/infer/yolo/predict` 23건 등 실제 사용 엔드포인트는 backend 발신이 관측됨
- **재현/확인 경로**:
  - `grep -rn "verifyObjects" backend/src/main` (호출부 0건 확인)
  - `docker logs klid-ai-server 2>&1 | grep "verify-objects" | awk '{print $2}' | sort -u` (발신 IP 확인)
- **영향**: 기능 결함 아님. 다만 ①인증 없는 추론 표면이 사용처 없이 열려 있고(ai-server 는 무인증) ②G-5 14개 케이스가 **제품 동선에서 도달 불가능한 경로**를 검증 중이라 검증 리소스 배분이 왜곡된다. `AiInferenceDeidentReportGateTest.java:71` 주석은 "`verifyObjects` 처럼 같은 이미지를 운반하는 다른 메서드"가 비식별 신고 게이트를 우회할 수 있음을 지적하는데, 현재는 호출부가 없어 잠재 위험으로만 남아 있다 — 향후 배선 시 게이트 누락 위험(CWE-359).
- **수정 방향(제안)**: 정책 결정 필요 — ①실사용 계획이 없으면 `AiServerClient.verifyObjects` + `ai-server/app/routers/vlm.py` 라우트 + 관련 DTO/스키마를 제거(표면 축소), 또는 ②사용 계획이 있으면 배선 시 `encodeDeidentifiedFrameForInference` 경로 + 비식별 신고 게이트를 반드시 함께 적용. 어느 쪽이든 카탈로그 §G-5 에 "현재 프로덕션 호출부 0건" 을 명기해 검증자가 도달 불가 경로임을 알게 한다.

### [G-ISSUE-63] 근거 file:line 드리프트 4건 (카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 `근거(file:line)` 는 판정자가 즉시 대조할 수 있는 실제 위치여야 한다. 어긋나면 검증자가 엉뚱한 라인을 읽고 거짓 PASS/FAIL 을 낸다.
- **현재 동작(이슈 내용)**: 아래 4건이 실제 위치와 불일치(각각 실측 확인).
  | ID | 카탈로그 표기 | 실제 위치 |
  |---|---|---|
  | TC-AIVLM-10 | `schemas.py:204,211` | 요청 모델 `extra="forbid"` 는 `schemas.py:196`(ObjectToVerify) · `:204`(VlmVerifyRequest). **`:211` 은 응답 모델 `ObjectVerification`** 이라 요청 거부 근거가 아님 |
  | TC-AIMOCK-43 | `application.yml:512-520` | 실제는 circuitbreaker `application.yml:532-539` + retry `:606-612`. 512-520 은 `kpst.deid.*` 폴링 설정 |
  | TC-AIMOCK-45 | `application-local.yml:150` | 실제는 `application-local.yml:147 enabled: ${VLM_CLIENT_ENABLED:true}`. 150 은 빈 줄 |
  | TC-AIMOCK-46 | `VlmTimeseriesStep.java:60-65,95,216-220` | 게이트 본체는 `:303-306`, 사유 상수는 `:113`. `:95` 는 `DEFAULT_FRAMERATE` 상수, `:216-220` 은 `execute(BatchContext)` javadoc |
  (별도로 TC-AIMOCK-44 의 `VlmTimeseriesStep.java:78` 은 G-ISSUE-61 에서 다룸)
- **재현/확인 경로**: `sed -n '196p;204p;211p' ai-server/app/schemas.py` · `sed -n '532,539p;606,612p' backend/src/main/resources/application.yml` · `sed -n '147p;150p' backend/src/main/resources/application-local.yml` · `sed -n '95p;303,306p' backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java`
- **영향**: 검증 효율·신뢰도 저하(카탈로그 자체의 정합성 결함). 제품 동작 영향 없음.
- **수정 방향(제안)**: `docs/test-cases/G-ai-server.md` 의 해당 4행 근거 컬럼을 위 표의 "실제 위치" 로 교체.

### [G-ISSUE-64] ai-server 애플리케이션 INFO 로그가 전량 유실됨 (logging 미구성)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: ai-server 는 각 추론 라우터에서 진단 로그를 남기도록 작성돼 있다(예: 요청별 이미지 크기·객체 수). 장애 분석·계약 검증 시 "무엇이 어떤 입력으로 들어왔는가"를 사후 추적할 수 있어야 한다.
- **현재 동작(이슈 내용)**: **컨테이너 로그에 앱 INFO 가 단 1건도 없다.** ai-server 는 `logging.basicConfig`/`dictConfig`/uvicorn `--log-config` 를 어디에서도 설정하지 않아 앱 로거가 Python 기본 root level(WARNING)로 동작한다. 결과적으로 `logger.info(...)` 는 모두 버려지고 `logger.warning` 이상만 출력된다.
  - `ai-server/app/routers/vlm.py:46-51` — `logger.info("[VLM] verify-objects received image_size=%dx%d objects=%d", width, height, len(req.objects))`
  - `docker logs klid-ai-server | grep -c "verify-objects received"` → **0** (실제로는 12회 호출됨 — uvicorn access 로그로 확인)
  - 같은 로그에서 `WARNING:app.routers.vlm:[VLM][MOCK] …` 은 정상 출력 → 레벨 컷임이 확정
  - `grep -rn "basicConfig\|dictConfig\|log_level\|LOG_LEVEL" ai-server/app ai-server/Dockerfile*` → 매치 0건, 기동 커맨드 `["uvicorn","app.main:app","--host","0.0.0.0","--port","9300"]` 에도 로그 옵션 없음
- **재현/확인 경로**:
  ```
  curl -s -X POST http://localhost:19300/infer/vlm/verify-objects -H 'Content-Type: application/json' \
    -d '{"image_b64":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==","objects":[{"obj_id":"o1","expected_label":"person","bbox":[0,0,1,1]}]}'
  docker logs --since 1m klid-ai-server | grep "verify-objects received"   # 0건
  ```
- **영향**: 운영 관찰가능성 결손 — 추론 서버에서 장애·계약 불일치가 발생해도 요청 단위 컨텍스트(이미지 크기·객체 수·모델 로드 상태 등)를 사후 확인할 수 없다. `main.py:31-37` 의 startup 진단(`[AI] startup mock_mode=… device=… max_image_mb=…`)도 동일하게 유실돼 **실행 중 인스턴스의 mock 모드 여부를 로그로 확인할 수 없다**(본 검증에서도 `docker exec env` 로 우회해야 했다). rules/observability.md 의 traceId·구조화 로깅 원칙과도 어긋난다.
- **수정 방향(제안)**: `ai-server/app/main.py` 에 `logging.config.dictConfig`(또는 최소 `logging.basicConfig(level=os.getenv("LOG_LEVEL","INFO"))`)를 앱 생성 전에 배선하고, 레벨을 `LOG_LEVEL` 환경변수로 노출(기본 INFO, prd 는 조정 가능). `RequestIdMiddleware` 가 이미 request-id 를 들고 있으므로 포매터에 함께 실어 BE 의 `X-Trace-Id` 와 상관관계를 맞춘다. 대안으로 uvicorn `--log-config` 파일 배선.

### [G-ISSUE-65] TC-AIMOCK-40 — 외부 URL 대역 검사가 호스트의 **첫 번째 해석 주소만** 검사 (다중 A 레코드 우회 여지)
- **심각도**: LOW
- **기대 동작(기대효과)**: relaxed 정책은 링크로컬/클라우드 메타데이터 대역(169.254.0.0/16 · fe80::/10)을, strict 정책은 loopback/사설/링크로컬 전부를 거부해야 한다(CWE-918 SSRF). 호스트명이 **여러 주소로 해석되는 경우에도** 위험 대역이 섞여 있으면 거부되는 것이 안전하다.
- **현재 동작(이슈 내용)**: 두 검사 모두 `InetAddress.getByName(host)` 를 써 **첫 번째 주소 1개만** 판정한다. 호스트명이 `[공인IP, 169.254.169.254]` 처럼 복수 레코드로 해석되고 첫 주소가 안전하면 검사를 통과하며, 실제 커넥션은 JDK/OS 의 주소 선택에 따라 위험 주소로 갈 수 있다.
  - `backend/.../common/config/ExternalUrlPolicy.java:161-165` — `return InetAddress.getByName(normalized);` (relaxed 경로)
  - `ExternalUrlPolicy.java:170-175` — `addr = InetAddress.getByName(host);` (strict 경로)
  - `:148` / `:176-177` 이 그 **단일** `addr` 만 검사
- **재현/확인 경로**: `vlm.client.url` 을 A 레코드가 2개(첫 번째 공인, 두 번째 169.254.x)인 호스트명으로 설정 → 기동이 통과. (환경 구성이 필요해 본 회차에서 실동작 재현은 하지 않음 — 정적 확인)
- **영향**: CWE-918(SSRF) 잔여 표면. 다만 **트리거 조건이 "운영자가 base-url 설정값을 그런 호스트명으로 지정" 이라 신뢰 경계 안쪽**이고, 값은 사용자 입력이 아니라 환경변수라 실착취 가능성은 낮다. 부수적으로 기동 시점 검사와 실제 커넥션 시점 해석이 분리돼 있어 DNS rebinding 에 대한 TOCTOU 여지도 동일하게 남는다(현 설계가 이를 방어 대상으로 선언하지 않음).
- **수정 방향(제안)**: `InetAddress.getAllByName(host)` 로 바꿔 **해석된 전 주소**에 대해 대역 검사를 수행(하나라도 위험 대역이면 거부). relaxed 경로의 "해석 실패는 통과" 규약은 그대로 유지(`getAllByName` 도 `UnknownHostException` 을 던지므로 동일 catch 로 처리 가능). 근본적 rebinding 방어가 필요해지면 커넥션 시점 IP 검증(커스텀 `AddressResolver`)을 별도 설계.

---

## 근거 드리프트 요약 (카탈로그 정정 입력)

| ID | 카탈로그 근거 | 실제 |
|---|---|---|
| TC-AIVLM-10 | `schemas.py:204,211` | `schemas.py:196,204` (211=응답모델) |
| TC-AIMOCK-43 | `application.yml:512-520` | `application.yml:532-539`(CB) + `:606-612`(retry) |
| TC-AIMOCK-44 | `VlmTimeseriesStep.java:78` | 45s 계층 폐지 — `VlmClient.java:76,108` 단일 계층 |
| TC-AIMOCK-45 | `application-local.yml:150` | `application-local.yml:147` |
| TC-AIMOCK-46 | `VlmTimeseriesStep.java:95,216-220` | `VlmTimeseriesStep.java:113,303-306` |

정확했던 근거(대조 완료): `routers/vlm.py:38-69/72-80/89-104/93/45`, `schemas.py:195-200/200/207`, `image_utils.py:42-43`, `AiServerClient.java:91`, `VlmClient.java:53,67,88-112,94-97,106,121-127,154-169`, `DeidentifyHealthIndicator.java:77-83/84-91/92-104/105-112`, `ProfileGatedUrlPolicy.java:78-91/121-133/145-151`, `ExternalUrlPolicy.java:80-82/143-153/169-181`, `test_vlm.py:52`.

## UNCERTAINTIES 갱신 제안

- **#13 (VLM 45s 타임아웃·콜백·IntelliVIX v2.0.1)**: "타임아웃은 이중 구조 — 블록 상한 45s ↔ 실효 10s" → **"단일 구조 — `vlm.client.timeout-seconds`(기본 10s) 단일 출처. 45s 블록 계층은 Phase C-1 논블로킹 전환으로 폐지"** 로 정정(G-ISSUE-61). describe→callback 실왕복은 본 회차에서 **재확인**(rawSn 80·81·94). 남은 미확정은 여전히 IntelliVIX 실서버 대조 1건.
- **#15 (실모델 테스트 게이팅)**: 런타임 실측으로 상태 확정 — 컨테이너는 `AI_MOCK_MODE=false` 이지만 **가중치 파일 부재**로 `mock_reason=weights_missing` 이 반환된다(`[YOLOX] weights not found — fallback to mock` 기동 로그). 즉 "환경변수는 실모델이나 실제로는 mock" 상태이며, 이를 구분할 수 있는 신호는 응답의 `mock_reason` 뿐이고 **INFO 로그로는 확인 불가**(G-ISSUE-64). 게이팅 정책 자체는 여전히 미확정.
# G-part5 — 외부 벤더 목업 계약 검증 (G-7 KPST · G-8 VLM · G-9 genai, 33건)

- **대상**: `docs/test-cases/G-ai-server.md` §G-7(8) + §G-8(4) + §G-9(21) = **33건**
- **검증일**: 2026-08-02
- **검증 방식**: mock-server(`127.0.0.1:9400`, 컨테이너 `klid-mock-server`) **실동작 호출 26건** + 정적 대조 7건
- **런타임 실효 환경변수**(docker inspect 실측):
  `MOCK_OUTPUT_BASE=/app/storage/raw,/app/storage/deidentified` · `MOCK_INPUT_BASE=/app/storage` ·
  `MOCK_CALLBACK_ALLOWED_HOSTS=klid-backend,localhost,127.0.0.1` ·
  `MOCK_GENAI_INPUT_BASE=/app/storage` · `MOCK_GENAI_OUTPUT_BASE=/app/genai-out`
  (`MOCK_GENAI_EVENT_TYPES`·`MOCK_GENAI_STATUS_SYNC_URL`·`MOCK_GENAI_CALLBACK_PATH_PREFIXES` 미설정)
- **BE 실경유 증거**: `GET /api/genai/_mock/jobs` 에 BE 발급 `request_id=AUG-{uuid}-1` 형태 job 다수(SUCCEEDED) — genai 경로는 self-fill 아님이 실측 확인됨.
- **벤더 계약 원문 대조 자료**:
  - VLM = `docs/video_vlm_api_ v2.0.1.docx` (IntelliVIX 원본 docx **본문 추출해 전문 대조**)
  - KPST = `docs/v2-wiki/22-deid-solution-api.md` (KPST 『비식별화 솔루션 API 연동 방안』 v1.0 전사본)
  - genai = 「생성형 AI API 연동명세서 v1.1」 원문은 저장소 부재 → `docs/v2-wiki/14-augmentation.md`(INT-001/019/020/029/030/031 매핑) + 코드 주석의 §번호 참조로 간접 대조
- ⚠ **환경 부작용 고지**: G-ISSUE-82 반증 과정에서 VLM 콜백이 목 서버 자신의 `POST /api/genai/_mock/reset` 을 호출해 **genai 인메모리 job 저장소가 1회 초기화**됐다(로그 `[MOCK][GENAI] store reset`, 09:19:32). 이는 취약점의 실증 결과이며 BE DB 에는 영향 없다. 이후 회차에서 genai `_mock/jobs` 이력이 비어 있는 이유가 이것이다.

---

## 판정 요약

| 클러스터 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| G-7 KPST | 8 | 7 | 0 | 1 | 0 | 0 | 0 |
| G-8 VLM | 4 | 3 | 1 | 0 | 0 | 0 | 0 |
| G-9 genai | 21 | 21 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **33** | **31** | **1** | **1** | **0** | **0** | **0** |

---

## G-7. KPST 비식별 벤더 목업 (8건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-AIMOCK-01 | PASS | [실동작] | `GET /` → `HTTP/1.1 200` · `content-type: text/plain; charset=utf-8` · body `Connect`. KPST 명세 §22.3.1(`"Connect"` text/plain 200) 정합. ⚠근거 드리프트: `deid.py:101-105` → 실제 `deid.py:116-119` |
| TC-AIMOCK-02 | PASS | [실동작] | `GET /health` → `200 {"status":"ok"}`. ⚠근거 드리프트: `main.py:94-96` → 실제 `main.py:103-105`. 카탈로그 주석대로 KPST 벤더 계약 아님(명세 13종에 `/health` 없음) — 표기 정확 |
| TC-AIMOCK-03 | PASS | [실동작] | `POST /project`(input_path=`/app/storage/raw/seed/`, files=`["clip-9101.mp4"]`) 후 `GET /retrieve_progress` 응답 `dsStatus[0].fileName = "/app/storage/raw/seed/clip-9101.mp4"` = **input_path + 원본 basename**(산출물명 아님). `retrieve_report`·`manual_deid_info`·`retrieve_job_logs` 도 동일 값 사용해 일관. ⚠근거 드리프트: `deid.py:76-98`→`86-112`, `deid_sim.py:210-217`→`272-279`(현 210-217 은 placeholder 폐기 주석) |
| TC-AIMOCK-04 | PASS | [실동작] | 완료 후 `ls /app/storage/deidentified/videos/qa-g5-p1/` → **`clip-9101-mask.mp4`**(50,854B, 타임스탬프 세그먼트 없음). KPST 실서버 계약(`{원본stem}-mask{ext}`) 정합. ⚠근거 드리프트: `deid_sim.py:162-165,193-207` → 실제 `MASK_SUFFIX`/`mask_name_from` 는 `225,255-269` |
| TC-AIMOCK-05 | PASS | [정적] | `deid_sim.produce_deid_outputs` 진입부: `if not output_base:` → `_base_unset_warned` 1회 WARN 후 `return ProductionOutcome(written=[])` (실패 아님 = 성공 no-op, 응답 200 유지). 테스트 커버: `tests/test_deid_output.py:354 test_output_base_미설정이면_파일이_생기지않는다_failclosed`. 런타임에 `MOCK_OUTPUT_BASE` 가 설정돼 있어 실동작 재현은 불가(설정 변경 = 환경 개조라 미수행). ⚠근거 드리프트: `deid_sim.py:409-444` → 실제 `1704-1714` |
| TC-AIMOCK-06 | PASS | [실동작] | `export_path=/tmp/qa-esc/`(output_base 밖) 요청 → 응답 `200 {"result":"success","prj_id":5}` 유지, 컨테이너 내 `/tmp/qa-esc` **미생성**, 로그 `[MOCK][KPST] deid output dir rejected export_path=/tmp/qa-esc/ output_base=/app/storage/raw,/app/storage/deidentified` + `production failed reason=OUTPUT_DIR_REJECTED`, 이후 `procState=99`/`prjState=5` 로 노출(관측 불가하게 실패하지 않음 — 케이스 요구 충족 + 더 강하게 드러냄) |
| TC-AIMOCK-07 | **PARTIAL** | [실동작] | 보안 의도(임의 파일 미열람·디스크 고갈 차단)는 **완전 충족**이나 **기대결과 문구가 stale**. `input_path=/etc/`, `files=["hosts.mp4"]` → 응답 200, 이후 `procState=99`, `export_path` 디렉터리는 **완전히 비어 있음(placeholder 파일 0건)**. 로그: `deid source rejected(boundary) — 허용 입력 루트 밖 요청이라 원본을 읽지 않고 산출 실패로 종결` + `placeholder 로 최종 이름을 선점하지 않는다`. 코드 주석(`deid_sim.py:210-217`, `config.py:74-85`, `deid.py:239-242`)이 "placeholder 안 = 폐기(CWE-345 위장 산출물)"를 명시. → **G-ISSUE-81** |
| TC-AIMOCK-08 | PASS | [실동작] | 기존 산출물(`clip-9101-mask.mp4`, size 50854 / mtime 1785662040) 있는 export_path 로 새 프로젝트 재실행 → **size·mtime 완전 동일**, 로그 `[MOCK][KPST] deid output exists — skip(no-overwrite) file=clip-9101-mask.mp4`, `procState=2`(멱등 완료). ⚠근거 드리프트: `deid_sim.py:328-346,368-373` → 실제 `_copy_no_overwrite` 439~ / `_write_one_output` 1523~ |

### G-7 부수 관찰 (케이스 밖)
- 산출 완료 후 `export_path` 하위에 빈 `.mock-tmp/`(0700) 디렉터리가 잔존한다. BE 폴백 스캔이 디렉터리를 파일로 오인하지 않는 한 무해하나 정리되지 않는다 → **G-ISSUE-88**.
- `GET /manual_deid_info` 가 KPST 명세 §22.4 의 "프로젝트 상태(state)=3 = 수동 비식별화 대상" 조건을 반영하지 않고 `db_save==1` 만으로 필터한다 → **G-ISSUE-86**.
- `/upload`(§22.3.2)·`/download`(§22.3.12) 미구현은 **의도된 스코프 제외**(공유 마운트 모델, `22-deid-solution-api.md` 명시) — 갭 아님.

---

## G-8. VLM 벤더 목업 (4건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-AIMOCK-09 | PASS | [실동작] | `POST /v1/videovlm/verify` → `200 {"request_id":"qa-g5-001","status":"accepted"}`(벤더 v2.0.1 §2.1 "200 OK / {request_id,status:accepted}" 정합). 2초(`callback_delay_seconds`) 후 로그 `[MOCK][VLM] callback failed url=http://localhost:9999/cb type=ConnectError` → **콜백이 실제로 지연 발사**됨을 확인. `describe` 도 동일(`qa-g5-003`). 성공 페이로드 구조는 벤더 규격과 정합(verify=`results{accuracy,description}` 객체 / describe=`results[]{start_sec,end_sec,description}` 배열, `vlm_sim.build_verify_callback:261-267` / `build_describe_callback:270-279`) |
| TC-AIMOCK-10 | PASS | [실동작] | `callback_url=http://evil.example.com/cb` → `400 {"error_code":"VALIDATION_ERROR","message":"callback_url host is not allowed"}` + 로그 `callback_url rejected(not allowed host) host=evil.example.com`, outbound 로그 **0건**. 케이스 단언은 충족. 단 **호스트만** 검사하고 포트·경로·자기참조는 무제한 → 별건 **G-ISSUE-82** |
| TC-AIMOCK-11 | PASS | [실동작] | `request_id="fail-x"` → 동기 응답 **`200`** `{"request_id":"fail-x","status":"accepted"}`, 콜백만 failed. failed 페이로드는 `{"request_id","status":"failed","error":{"code":"INFERENCE_ERROR","message":...}}`(`vlm_sim.py:329-335`)로 **벤더 v2.0.1 실패 콜백 규격(중첩 `error{code,message}`)과 정합** — 카탈로그의 "error_code/message" 표현이 평면 필드를 뜻한다면 오해 소지. ⚠카탈로그 기대결과 "동기 **202** accepted" 는 **오기**(벤더 규격·실동작 모두 200) → **G-ISSUE-83** |
| TC-AIMOCK-12 | **FAIL** | [실동작] | `GET /v1/videovlm/status` → `200 {"status":"ok","service":"videovlm"}`. **벤더 v2.0.1 §2.7 규격은 `{"status":"ready"}`(요청 처리 가능) / `{"status":"busy"}`(작업 진행 중)** 이며 `service` 필드는 규격에 없다. 목업이 벤더 계약을 재현하지 않고 자체 형식을 반환하며, 카탈로그 기대결과가 그 드리프트를 그대로 정본화하고 있다 → **G-ISSUE-84**. ⚠근거 드리프트: `vlm.py:228-231` → 실제 `241-244` |

### G-8 부수 관찰
- VLM 요청 스키마가 벤더 규격의 **조건부 필수**를 검증하지 않는다(실동작 확인): `frame_policy.framerate` 누락(규격 Required=Y) → 200 / `source_type=path` 인데 `media.path` 누락(규격 필수) → 200 / `mode=frame_selected` 인데 `selected_frames` 누락(규격 필수) → 200. `selected_frames` 9개 → 422 는 정상 → **G-ISSUE-85**

---

## G-9. 생성형 AI(genai) 증강 벤더 목업 (21건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-AIMOCK-13 | PASS | [실동작] | `POST /api/genai/jobs` → `202` `{"request_id":"qa-g5-j1","job_id":"8aff3d68…","status":"RECEIVED","received_at":"2026-08-02T09:16:51+00:00"}` — job_id 는 **목이 발급**(`uuid4().hex`, `augment.py:277`), 4필드 모두 존재 |
| TC-AIMOCK-14 | PASS | [실동작] | `generation_mode=I2I` + `input_files=[]` → `400 {"code":"REQUIRED_FIELD_MISSING","message":"input_files 는 I2I·I2V 에서 1건 이상 필요합니다"}` |
| TC-AIMOCK-15 | PASS | [실동작] | `generation_mode=T2I` + input_files 미지정 → `202 RECEIVED` (위 j1) |
| TC-AIMOCK-16 | PASS | [실동작] | `sequence=[1,1]` → `400 {"code":"INVALID_PARAMETER","message":"input_files[].sequence 는 중복될 수 없습니다"}` |
| TC-AIMOCK-17 | PASS | [실동작] | `callback_url=http://klid-backend:8080/api/v1/genai/callback` 지정 job 진행 시 mock 로그에 **4회 webhook 발사** 관측(09:17:44 → 09:17:46/47 → 09:17:49 → 09:17:52), 각 회 최대 2회 재시도(`genai_webhook_max_attempts=2`) 후 `give-up`. 중간 GET 상태로 `progress=90/current_step=POSTPROCESS`(=90→POSTPROCESS) 확인, 완료 payload 에 `results` 포함(`build_webhook_payload:649-650` — `if job.results`). `PROGRESS_STEPS=((10,PREPROCESS),(50,INFERENCE),(90,POSTPROCESS))` `genai_sim.py:93-97`. ⚠근거 드리프트: `81-86,480-495,583-648` → 실제 `93-97,761-826,639-654` |
| TC-AIMOCK-18 | PASS | [실동작] | RECEIVED/RUNNING 중 `GET …/results` → `409 {"code":"STATE_CONFLICT","message":"결과는 SUCCEEDED 상태에서만 조회할 수 있습니다"}`. SUCCEEDED 후 동일 호출 → 200 |
| TC-AIMOCK-19 | PASS | [실동작] | I2I(원본 `/app/storage/raw/seed/clip-9101.mp4`) 결과 `output_file_path=/app/genai-out/genai/bade5ef…/001_clip-9101_genai.png`. 컨테이너 내 파일 **실존**(20,590B = 원본과 동일 크기 → 복사됨), `sha256sum` = `52c61f9e…42a6` = 응답 `checksum` **완전 일치**. T2I(원본 없음) 는 21B placeholder + 체크섬 일치(`9416c143…a4f0`) |
| TC-AIMOCK-20 | PASS | [실동작] | RUNNING 중 cancel → `200 {"status":"CANCELED","canceled_at":…}`. SUCCEEDED job cancel → `409 STATE_CONFLICT`. `requested_by` 누락 → `400 REQUIRED_FIELD_MISSING` |
| TC-AIMOCK-21 | PASS | [실동작] | `Idempotency-Key: qa-g5-idem-1` 로 **본문이 다른**(request_id/mode 상이) 재요청 → 동일 `job_id=bade5ef6d944473699b6c557b2aaad04` + 저장된 request_id echo, 로그 `idempotent replay key=qa-g5-idem-1`. 65자 키 → `400 {"code":"INVALID_PARAMETER","message":"Idempotency-Key 는 64자 이하여야 합니다"}` |
| TC-AIMOCK-22 | PASS | [정적] | `genai_sim.build_results:555-559` — `if not output_base: raise JobExecutionError(RESULT_SAVE_FAILED)` → `_process_job:830-831` catch → `_fail_job` → FAILED 전이 + `emit_webhook`(payload 에 `error_code`/`error_message` 포함, `639-654`). 테스트: `tests/test_genai_webhook.py:360 test_HIGH3_출력base_미설정이면_FAILED_RESULT_SAVE_FAILED`. 런타임 env 가 설정돼 있어 실동작 재현 불가(설정 개조 미수행). ⚠근거 드리프트: `396-400`→`555-559` |
| TC-AIMOCK-23 | PASS | [실동작] | 상대경로 `../../etc/passwd` → `400 INVALID_PARAMETER`, 루트 밖 절대경로 `/etc/passwd` → `400 INVALID_PARAMETER`(둘 다 `"허용된 루트의 절대경로여야 합니다"` + 로그 `input path rejected(path guard)`). base 미설정 fail-closed 는 `resolve_input_path:395-396` `if not input_base: return None`(정적) + 테스트 `test_genai_security_hardening.py:303 F6`. ⚠근거 드리프트: `223-250`→`382-409` |
| TC-AIMOCK-24 | PASS | [실동작] | 허용목록 밖 `http://evil.example.com/cb` → `400 INVALID_PARAMETER`, **자기참조** `http://localhost:9400/api/genai/jobs` → `400`(=`_is_self_target` 동작 확인). 두 건 모두 로그 `callback_url rejected(url guard)` + outbound 0건. host:port 분리·경로접두사는 `is_allowed_url:328-376` 정적 + 테스트 F2(`159-241`). ⚠근거 드리프트: `161-220`→`328-376` |
| TC-AIMOCK-25 | PASS | [실동작] | 본문 1,200,159B(>1MiB) → **`413` `{"code":"GA-MEDIA-001","message":"요청 본문 크기가 허용 한도를 초과했습니다"}`**. 본문 70,160B(<1MiB) + prompt 70KB(>64KiB) → **`400` `{"code":"INVALID_METADATA","message":"prompt 크기가 허용 한도를 초과했습니다"}`. 두 상한이 별도 축으로 동작 확인 |
| TC-AIMOCK-26 | PASS | [정적] | 처리 시점 재검증 3중: ①`_revalidate_source:620-635` 가 `resolve_input_path` 를 **다시** 호출 + `is_file()` 확인 → 실패 시 `MODEL_EXECUTION_FAILED` ②`_open_source_nofollow:447-467` `os.open(O_RDONLY|O_NOFOLLOW)` + **fd 기준 `fstat`** 로 정규파일/크기 재확인 ③`_write_output:517-520` 실패 시 부분 산출물 `unlink`. 테스트: `test_genai_security_hardening.py:98 F1_접수후_입력파일이_base밖_심볼릭링크로_바뀌면_유출되지_않고_FAILED`, `:129`, `:329 F7`. ⚠근거 드리프트: `288-361,461-476`→`447-467,620-635` |
| TC-AIMOCK-27 | PASS | [실동작] | RUNNING 중 cancel 확정 후 `ls /app/genai-out/genai/271b870b…` → **`No such file or directory`**(산출물·디렉터리 모두 없음). 코드: `discard_results:523-536`(파일 unlink + 빈 부모 rmdir), 호출 지점 `_process_job:806-808`(CancelledError) / `:820-826`(종결 선점). 테스트 `test_genai_security_hardening.py:409 F11`. ⚠근거 드리프트: `364-378`→`523-536` |
| TC-AIMOCK-28 | PASS | [정적] | `augment.py:178-184 _check_event_type` — `allowed = settings.genai_event_types_set()`, `if allowed and evnt_type not in allowed: 400 UNSUPPORTED_EVENT_TYPE`. **근거 라인 정확**. 런타임 `MOCK_GENAI_EVENT_TYPES` 미설정 → 미강제(설계대로, 실동작으로 `evnt_type=WINTER` 202 확인). 테스트 `test_genai_jobs.py:397` |
| TC-AIMOCK-29 | PASS | [정적] | `config.py:203-210 genai_max_jobs(default 1000)` + `state.py:495-512 GenAiJobStore` — "보관 작업 수가 max_jobs 를 넘으면 가장 오래된 작업부터 만료(FIFO)". 테스트 `test_genai_security_hardening.py:270 F3_잡_수_상한을_넘으면_오래된_작업부터_만료된다`. ⚠근거 드리프트: `config.py:179-186` → 실제 `203-210`(179-186 은 `genai_webhook_max_attempts`) |
| TC-AIMOCK-30 | PASS | [실동작] | `GET /api/genai/_mock/jobs` → jobs[] + `active_tasks` + `queue` 반환(BE 발급 `AUG-*` job 다수 관측), `POST /_mock/reset` → `{"result":"success"}`, `POST /_mock/jobs/{id}/status-sync` → 200. 세 EP 모두 `/api/genai/_mock/` 하위로 명세 계약 경로와 분리 |
| TC-AIMOCK-31 | PASS | [실동작] | `MOCK_GENAI_STATUS_SYNC_URL` 미설정 상태에서 `POST /_mock/jobs/{id}/status-sync` → `200 {"job_id":…,"sent":false,"target":null,"reason":"MOCK_GENAI_STATUS_SYNC_URL 미설정 또는 허용되지 않는 대상 — status-sync 비활성"}`. **자동 발신 로그 0건**(전체 세션 동안 status-sync outbound 없음). ⚠근거 드리프트: `563-579`→`722-740` |
| TC-AIMOCK-32 | PASS | [실동작] | 세션 전체에서 **어떤 인증 헤더도 없이** 모든 genai EP 가 정상 응답(202/200/400/409/404). `augment.py:20` + `schemas/genai.py:8-9` 가 "인증은 이번 스코프에서 의도적 미구현"을 명시 — 설계 의도대로 |
| TC-AIMOCK-33 | PASS | [실동작] | `docker logs klid-mock-server` 에 방어 WARN 이 **포맷 갖춰 stdout 노출**됨: `[MOCK][KPST] deid output dir rejected …`, `[MOCK][KPST] deid source rejected(boundary) …`, `[MOCK][VLM] callback_url rejected(not allowed host) …`, `[MOCK][GENAI] callback_url rejected(url guard) …`, `[MOCK][GENAI] webhook give-up …`. `main.py:32-48 configure_logging()` 근거 라인 **정확** |

---

## 이슈

### [G-ISSUE-81] TC-AIMOCK-07 — 기대결과가 폐기된 구 정책(18바이트 placeholder)을 그대로 정본화
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그 기대결과는 현재 코드의 확정 정책과 일치해야 한다. 불일치하면 다음 회차 검증자가 "placeholder 가 없다"를 **결함(FAIL)** 으로 오판하거나, 반대로 정책을 되돌리는 수정을 유발한다(이 저장소는 실제로 placeholder 를 만들었다가 되돌린 이력이 있다).
- **현재 동작(이슈 내용)**: 카탈로그는 `기대결과 = target 파일이 원본 복사가 아닌 18바이트 placeholder` 라고 적었으나, 코드는 **placeholder 를 명시적으로 폐기**했다.
  ```
  # mock-server/app/services/deid_sim.py:210-217
  # ★ #3 — <b>placeholder 산출물은 폐기됐다</b>. 원본을 읽지 못하는 경우(...) 구 구현은 18바이트
  #   스텁을 <b>최종 경로</b>에 쓰고 완료(procState=2)로 보고했다. ... 지금은 <b>산출 실패
  #   (procState=99)</b> 로 종결한다 ... 위장 산출물이 되기 때문이다(CWE-345).
  ```
  실동작(2026-08-02 09:15): `input_path=/etc/` 요청 → export 디렉터리 **완전히 빈 상태**, `procState=99`, 로그 `placeholder 로 최종 이름을 선점하지 않는다`.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:9400/project -H 'Content-Type: application/json' \
    -d '{"project_name":"chk","creator":"qa","export_path":"/app/storage/deidentified/videos/chk/","input_path":"/etc/","files":["hosts.mp4"]}'
  sleep 12
  curl -s -X GET http://localhost:9400/retrieve_progress -H 'Content-Type: application/json' \
    -d '{"reqUserId":"qa","prjName":"chk"}'      # procState=99
  docker exec klid-mock-server ls -la /app/storage/deidentified/videos/chk   # 빈 디렉터리
  ```
- **영향**: 기능/보안 영향 없음(현 동작이 더 안전). **카탈로그 정합성 결함** — 회차 간 판정 재현성을 깨뜨린다.
- **수정 방향(제안)**: `docs/test-cases/G-ai-server.md` TC-AIMOCK-07 의 케이스명·기대결과를 "허용 루트 밖 input_path 는 원본을 읽지 않고 **산출물을 만들지 않은 채 procState=99 로 실패 종결**(placeholder 로 최종 이름 선점 금지 — CWE-345)" 로 정정. 근거 `file:line` 도 `deid_sim.py:210-217, 334-353, 1704~` 로 갱신. **코드는 그대로 둔다.**

### [G-ISSUE-82] TC-AIMOCK-10 — VLM 콜백 SSRF 가드가 genai 가드보다 약해 목 서버 자기참조·임의 포트 POST 가 성립(실증)
- **심각도**: MEDIUM (목 서버가 루프백 전용 발행이라 원격 노출은 없음. 노출 시 HIGH)
- **기대 동작(기대효과)**: 목 서버는 무인증이므로 요청자 지정 `callback_url` 로의 서버측 outbound 는 **호스트뿐 아니라 포트·경로·자기참조**까지 좁혀야 한다. 같은 서버의 genai 가드(`genai_sim.is_allowed_url`)는 이미 `host:port` allowlist + 경로 접두사 + **자기참조 차단**을 구현하고 있으므로, VLM 만 약한 것은 방어 비대칭이다.
- **현재 동작(이슈 내용)**: VLM 은 `url_guard.is_allowed_callback` 만 사용하고 **호스트 완전일치만** 검사한다.
  ```python
  # mock-server/app/services/url_guard.py:7-9 (docstring)
  # 정책: 허용 호스트 목록(...)에 정확히 일치하는 호스트만 수락하고 ...
  # 포트/경로는 제한하지 않는다(콜백 수신 포트가 환경마다 다름).
  # :28-33
  def is_allowed_callback(url, allowed_hosts) -> bool:
      host = callback_host(url)
      if host is None: return False
      return host in {h.lower() for h in allowed_hosts}
  ```
  기본 allowlist 에 `localhost,127.0.0.1` 이 들어 있어 **목 서버 자신**이 항상 허용된다. 실증(2026-08-02 09:19:30~32, 컨테이너 로그):
  ```
  [MOCK][VLM] verify accepted request_id=qa-g5-ssrf1 callback_url=http://127.0.0.1:9400/api/genai/_mock/reset
  [MOCK][GENAI] store reset
  INFO: 127.0.0.1:52944 - "POST /api/genai/_mock/reset HTTP/1.1" 200 OK
  [MOCK][VLM] callback sent url=http://127.0.0.1:9400/api/genai/_mock/reset status=200
  ```
  → VLM 엔드포인트 1회 호출만으로 **다른 벤더(genai)의 작업 저장소가 전량 삭제**됐다. 또한 `http://klid-backend:5005/actuator` 처럼 **backend 컨테이너의 임의 포트**로도 POST 가 발사됐다(ConnectError 로그 = 실제 커넥션 시도).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:9400/v1/videovlm/verify -H 'Content-Type: application/json' \
    -d '{"request_id":"x","event_type":"fall","media":{"type":"video","source_type":"path","path":"/x.mp4"},
         "callback_url":"http://127.0.0.1:9400/api/genai/_mock/reset"}'
  # 200 accepted → 2초 후 docker logs 에 "[MOCK][GENAI] store reset"
  ```
- **영향**: 보안 **CWE-918(SSRF)** + **CWE-352 유사(무인증 상태변경 트리거)**. 컨테이너 네트워크 안에서 임의 호스트:포트 POST 발판 + 목 서버 자체 상태 파괴. 검증 관점에서는 다른 회차의 genai 관측 이력이 조용히 사라져 **검증 결과 신뢰성**을 훼손한다.
- **수정 방향(제안)**: `app/routers/vlm.py:_assert_allowed_callback` 이 `url_guard` 대신 **genai 와 같은 판정기**(`genai_sim.is_allowed_url` 을 벤더 중립 모듈로 승격하거나 `url_guard` 에 `host:port` + 경로 접두사 + `_is_self_target` 을 이식)를 쓰도록 통합. 최소한 `_mock/*` 보조 EP 는 콜백 대상에서 무조건 배제. 아울러 `MOCK_CALLBACK_ALLOWED_HOSTS` 기본값에서 `localhost,127.0.0.1` 의 필요성을 재검토.

### [G-ISSUE-83] TC-AIMOCK-11 — 기대결과의 "동기 202" 는 오기(벤더 규격·실동작 모두 200)
- **심각도**: LOW
- **기대 동작(기대효과)**: VLM verify/describe 의 동기 응답 코드는 벤더 v2.0.1 §2.1/§2.5 가 **200 OK** 로 못박고 있고 BE `VlmClient` 도 그 전제로 동작한다. 카탈로그가 202 라고 적으면 다음 회차가 "202 가 아니니 FAIL" 로 오판할 수 있다.
- **현재 동작(이슈 내용)**: 카탈로그 TC-AIMOCK-11 기대결과 = `동기 202 accepted, 콜백은 status=failed+error_code/message`. 실동작은 **200**:
  ```
  $ curl -w "HTTP=%{http_code}" -X POST .../v1/videovlm/verify -d '{"request_id":"fail-x",...}'
  {"request_id":"fail-x","status":"accepted"}  HTTP=200
  ```
  같은 절의 TC-AIMOCK-09 는 "동기 200" 으로 적혀 있어 **절 내부에서도 자기모순**이다. 또 실패 콜백은 평면 `error_code`/`error_message` 가 아니라 벤더 규격대로 **중첩 `error{code,message}`** 다(`vlm_sim.py:329-335`).
- **재현/확인 경로**: 위 curl 1줄.
- **영향**: 카탈로그 정합성. 202 를 정본으로 착각해 목업을 202 로 "고치면" **벤더 계약을 깨는 회귀**가 된다.
- **수정 방향(제안)**: TC-AIMOCK-11 기대결과를 `동기 200 accepted, 콜백은 {"status":"failed","error":{"code":"INFERENCE_ERROR","message":...}}` 로 정정.

### [G-ISSUE-84] TC-AIMOCK-12 — `/v1/videovlm/status` 응답이 IntelliVIX v2.0.1 규격(`ready`/`busy`)과 불일치하고 카탈로그가 그 드리프트를 정본화
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 목업의 존재 이유는 **벤더 계약을 대신 재현**하는 것이다. 벤더 원문(`docs/video_vlm_api_ v2.0.1.docx` §2.7 "서버 상태 체크")은 다음을 규정한다.
  | 상황 | 응답 |
  |---|---|
  | 요청 처리 가능 | `200 {"status":"ready"}` |
  | 작업 진행 중 | `200 {"status":"busy"}` |
  `service` 필드는 규격에 없다. 즉 목업은 `ready`/`busy` 두 값을 재현해야 소비 측(향후 헬스 인디케이터·서킷 판정)이 로컬에서 실제로 검증된다.
- **현재 동작(이슈 내용)**:
  ```python
  # mock-server/app/routers/vlm.py:241-244
  @router.get("/v1/videovlm/status")
  async def status_check() -> dict[str, str]:
      return {"status": "ok", "service": "videovlm"}
  ```
  실동작: `GET /v1/videovlm/status` → `200 {"status":"ok","service":"videovlm"}`. `ok` 는 규격 어휘가 아니며 `busy` 상태는 아예 재현 불가.
  카탈로그 TC-AIMOCK-12 기대결과가 `200 {status:ok, service:videovlm}` 으로 **목업 구현을 그대로 베껴** 계약 위반을 통과시키고 있다(확증편향 사례).
- **재현/확인 경로**: `curl -s http://localhost:9400/v1/videovlm/status` → `{"status":"ok","service":"videovlm"}` vs 벤더 문서 §2.7.
- **영향**: 기능 영향 **현재 없음**(BE 전수 grep 결과 `/v1/videovlm/status` 호출부 0건 — `VlmClient` 는 `DESCRIBE_PATH="/v1/videovlm/describe"` 만 사용). 그러나 ①"목업 = 계약 정본" 이라는 전제가 이 EP 에서 깨져 있고 ②향후 VLM 헬스 인디케이터를 `"ready"` 기준으로 붙이면 **로컬에선 전부 DOWN, 실벤더에선 UP** 이 되어 로컬 검증이 무의미해진다. mock-server `/health`(`{"status":"ok"}`)와 값이 같아 두 개념이 혼동되기도 쉽다.
- **수정 방향(제안)**: `vlm.py:status_check` 를 규격대로 `{"status": "ready"}` 로 바꾸고, 진행 중인 콜백/probe 태스크가 있을 때 `{"status":"busy"}` 를 반환하도록 선택적으로 확장(`vlm_sim.describe_probe_inflight()` 로 판정 가능). `service` 필드는 제거하거나 명시적으로 "목 전용 확장"임을 주석화. 카탈로그 TC-AIMOCK-12 기대결과도 `ready`/`busy` 로 정정. **본 회차에서는 수정하지 않음.**

### [G-ISSUE-85] G-8 — VLM 요청 스키마가 벤더 규격의 조건부 필수 필드를 검증하지 않아 계약 회귀를 못 잡는다
- **심각도**: LOW
- **기대 동작(기대효과)**: 목업이 벤더보다 관대하면 BE 가 규격 위반 요청을 보내도 로컬에서 200 이 나고, 실벤더 전환 시점에야 400/422 로 드러난다. 벤더 §3.1/§3.2 의 조건부 필수는 목업도 강제해야 한다.
  | 필드 | 벤더 규격 |
  |---|---|
  | `media.path` | `source_type=path` 인 경우 **필수** |
  | `frame_policy.framerate` | **Required = Y** |
  | `frame_policy.selected_frames` | `mode=frame_selected` 인 경우 **필수**(최대 8) |
- **현재 동작(이슈 내용)**: 셋 다 무조건 Optional 이라 누락돼도 접수된다.
  ```python
  # mock-server/app/schemas/vlm.py:42-46
  framerate: Optional[int] = Field(default=None, ge=1, le=240, ...)
  selected_frames: Optional[list[int]] = Field(default=None, max_length=8, ...)
  # :56
  path: Optional[str] = Field(default=None, ...)
  ```
  실동작(3건 모두 `200 accepted`): ①`frame_policy={"mode":"frame_interval"}`(framerate 없음) ②`media={"type":"video","source_type":"path"}`(path 없음) ③`frame_policy={"mode":"frame_selected","framerate":25}`(selected_frames 없음).
- **재현/확인 경로**:
  ```bash
  curl -s -w " %{http_code}\n" -X POST http://localhost:9400/v1/videovlm/describe -H 'Content-Type: application/json' \
    -d '{"request_id":"lax","media":{"type":"video","source_type":"path"},"callback_url":"http://localhost:1/cb"}'
  # {"request_id":"lax","status":"accepted"} 200   ← 벤더 규격상 path 필수
  ```
- **영향**: 계약 검증 공백. 현재 BE(`VlmTimeseriesRequest.ofFrameInterval`)는 항상 `framerate`·`path` 를 채우므로 즉시 장애는 없으나, BE 리팩터가 이를 빠뜨려도 **로컬·CI 어디서도 잡히지 않는다**.
- **수정 방향(제안)**: `schemas/vlm.py` 의 `Media`/`FramePolicy` 에 pydantic `model_validator(mode="after")` 를 추가해 위 3개 조건부 필수를 400(구조 오류) 으로 거부. G-8 에 회귀 케이스 3건 신설.

### [G-ISSUE-86] G-7 — `GET /manual_deid_info` 가 KPST 규격의 "프로젝트 상태=3(수동 대상)" 조건을 반영하지 않는다
- **심각도**: LOW
- **기대 동작(기대효과)**: KPST 명세(`docs/v2-wiki/22-deid-solution-api.md` §22.3.8 / §22.4)는 이 EP 를 "`db_save=1` **이며 프로젝트 상태가 수동 대상(state=3)** 인 데이터셋"으로 정의한다.
- **현재 동작(이슈 내용)**:
  ```python
  # mock-server/app/routers/deid.py:543-545
  def _manual_targets() -> list[Project]:
      """수동 비식별화 대상(db_save=1) 프로젝트 목록."""
      return [p for p in get_store().list_projects() if p.db_save == 1]
  ```
  실동작: `db_save=1` 로 만든 프로젝트가 **진행 중이든 완료든** 전부 반환된다(09:14 실측, 완료 직후 조회 시 1건 반환).
  더 나아가 목의 상태코드 정의(`deid_sim.py:56-60`)는 `prjState 3 = 완료` 인데 KPST 명세 §22.4 는 `프로젝트 상태 3 = 수동 비식별화 대상` 이라 **같은 값에 두 의미**가 붙어 있다.
- **재현/확인 경로**: `curl -s http://localhost:9400/manual_deid_info` — 완료(`prjState=3`)·진행중(`prjState=2`) 프로젝트가 구분 없이 나온다.
- **영향**: 기능 영향 없음(`22-deid-solution-api.md` §22.6 이 수동 비식별 연계를 "⏳ 후속·미구현"으로 명시, BE 호출부 0건). 다만 향후 수동 비식별 워크플로를 붙일 때 목업이 필터를 재현하지 않아 로컬 검증이 헛돌 수 있고, `prjState=3` 의미 충돌은 오독을 부른다.
- **수정 방향(제안)**: `_manual_targets` 에 `prj_state_for(...) == 수동대상` 조건 추가 또는 목이 수동 대상 상태를 별도 축으로 모델링. 최소한 `deid_sim.py:56` 의 상태코드 주석에 "KPST 명세 §22.4 의 '수동 대상 state=3' 과 값이 겹친다"는 경고를 남긴다.

### [G-ISSUE-87] G-7/G-8/G-9 — 근거 `file:line` 대량 드리프트(33건 중 15건)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 `file:line` 은 검증자가 곧바로 해당 코드로 점프하는 진입점이다. 어긋나면 매 회차마다 Grep 재탐색 비용이 들고, 최악에는 **엉뚱한 코드를 근거로 PASS** 를 찍는다(실제로 TC-AIMOCK-03 이 가리키는 `deid_sim.py:210-217` 에는 지금 placeholder **폐기** 주석이 있어, 그대로 읽으면 정반대 결론에 도달한다).
- **현재 동작(이슈 내용)**: 확인된 드리프트 15건.
  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | 01 | `deid.py:101-105` | `deid.py:116-119` |
  | 02 | `main.py:94-96` | `main.py:103-105` |
  | 03 | `deid.py:76-98` / `deid_sim.py:210-217` | `deid.py:86-112` / `deid_sim.py:272-279` |
  | 04 | `deid_sim.py:162-165,193-207` | `deid_sim.py:225,255-269` |
  | 05 | `deid_sim.py:409-444` | `deid_sim.py:1704-1714` |
  | 06 | `deid_sim.py:248-276,446-453` | `deid_sim.py:1716-1725` / `path_policy.resolve_output_dir` |
  | 07 | `deid_sim.py:279-326,359-406` | `deid_sim.py:334-353, 210-217` |
  | 08 | `deid_sim.py:328-346,368-373` | `deid_sim.py:439~, 1523~` |
  | 12 | `vlm.py:228-231` | `vlm.py:241-244` |
  | 17 | `genai_sim.py:81-86,480-495,583-648` | `genai_sim.py:93-97,761-826,639-654` |
  | 19 | `genai_sim.py:380-458` | `genai_sim.py:539-617` |
  | 22 | `genai_sim.py:396-400` | `genai_sim.py:555-559` |
  | 23 | `genai_sim.py:223-250` | `genai_sim.py:382-409` |
  | 24 | `genai_sim.py:161-220` | `genai_sim.py:328-376` |
  | 26 | `genai_sim.py:288-361,461-476` | `genai_sim.py:447-467,620-635` |
  | 27 | `genai_sim.py:364-378` | `genai_sim.py:523-536` |
  | 29 | `config.py:179-186` | `config.py:203-210` |
  | 31 | `genai_sim.py:563-579` | `genai_sim.py:722-740` |
  라우터(`augment.py`)·`main.py:32-48`·`augment.py:178-184` 등은 정확하다. 드리프트는 **크게 성장한 서비스 모듈**(`deid_sim.py` 1,988줄 / `genai_sim.py` 860줄)에 집중.
- **재현/확인 경로**: `sed -n '210,217p' mock-server/app/services/deid_sim.py` — TC-AIMOCK-03 이 "fileName=원본 경로"의 근거로 가리키는 자리에 placeholder 폐기 주석이 있다.
- **영향**: 카탈로그 정합성 + 검증 효율. 회차마다 반복 비용.
- **수정 방향(제안)**: `G-ai-server.md` §G-7~G-9 의 근거 컬럼을 위 표대로 일괄 갱신. 장기적으로는 라인 번호 대신 **심볼명**(`deid_sim.produce_deid_outputs`, `genai_sim.build_results` 등)으로 표기해 드리프트를 원천 차단.

### [G-ISSUE-88] G-7 — 산출 성공 후 `.mock-tmp/` 빈 디렉터리가 export_path 에 잔존
- **심각도**: LOW
- **기대 동작(기대효과)**: BE 는 완료 후 export 디렉터리를 **폴백 스캔**해 단일 산출물을 회수한다(`22-deid-solution-api.md`). 목이 만드는 임시 작업 디렉터리는 산출 완료 시 정리돼 스캔 대상이 깨끗해야 한다.
- **현재 동작(이슈 내용)**: 정상 산출 후에도 디렉터리가 남는다.
  ```
  $ docker exec klid-mock-server ls -la /app/storage/deidentified/videos/qa-g5-p1/
  drwx------ 2 app app  4096 Aug  2 09:14 .mock-tmp     ← 빈 디렉터리, 잔존
  -rw-r--r-- 1 app app 50854 Aug  2 09:14 clip-9101-mask.mp4
  ```
  `sweep_temp_dir`/`sweep_orphan_temp_files`(`deid_sim.py:1174,1227`)는 **임시 파일**을 지우지만 디렉터리 자체는 제거하지 않고, 산출 진입 시점(`produce_deid_outputs`)에만 sweep 한다.
- **재현/확인 경로**: 위 `ls -la` (정상 완료 프로젝트의 export_path).
- **영향**: 현재 무해(BE 회수는 1차 `{stem}-mask{ext}` 경로가 맞아 폴백 스캔에 도달하지 않으며, 폴백 스캔도 파일만 대상). 다만 export 폴더에 계약 밖 엔트리가 남아 관제/데이터마트 관점의 산출 폴더 청결성을 해친다.
- **수정 방향(제안)**: `produce_deid_outputs` 종료부에서 `TEMP_DIR_NAME` 디렉터리가 비어 있으면 `rmdir`(genai `discard_results:534-536` 와 동일 패턴). 실패는 무시(격리).

---

## 부록 — 확증편향 반증 시도 기록

이 클러스터는 "목업 자체의 품질"을 감사하므로, **목업 구현을 기준으로 삼지 않고 벤더 계약 원문을 기준**으로 대조했다.

| 반증 시도 | 결과 |
|---|---|
| 벤더 원문(`video_vlm_api_ v2.0.1.docx`) 본문을 추출해 목업과 필드 단위 대조 | `/status` 응답 어휘 불일치 발견(**G-ISSUE-84**), 조건부 필수 3건 미검증 발견(**G-ISSUE-85**) |
| 카탈로그 기대결과가 코드를 그대로 베낀 것인지 확인 | TC-AIMOCK-12 가 정확히 그 사례 — 계약 위반을 통과시키고 있었음 |
| 목업이 "보안 가드가 있다"고 주장하는 지점을 실제로 뚫어봄 | VLM 콜백 가드를 **자기참조로 우회해 genai 저장소 파괴 성공**(**G-ISSUE-82**) |
| 카탈로그 기대결과가 현 코드 정책과 반대인 곳 탐색 | TC-AIMOCK-07 placeholder(**G-ISSUE-81**), TC-AIMOCK-11 202(**G-ISSUE-83**) |
| PASS 근거로 인용한 라인이 실제로 그 코드인지 확인 | 15건 드리프트(**G-ISSUE-87**) — 그중 TC-AIMOCK-03 은 정반대 결론을 유도할 위치 |
| self-fill 여부(목 미경유 자체 채움) | genai `_mock/jobs` 에 BE 발급 `AUG-*` job 실존, KPST 산출물이 실제 파일로 존재, VLM 콜백 outbound 로그 존재 → **self-fill 징후 없음** |
| 경계 위반이 조용히 성공으로 보고되는지 | export 밖 → `procState=99`, input 밖 → `procState=99`, genai 출력 base 미설정 → FAILED. **거짓 완료 없음** |

### 실동작으로 확인된 응답 코드 매트릭스 (재현용)

| 요청 | 실측 |
|---|---|
| `GET /` | 200 `Connect` (text/plain) |
| `GET /health` | 200 `{"status":"ok"}` |
| `POST /project` 정상 | 200 `{"result":"success","prj_id":N}` |
| `POST /project` 중복 이름 | 409 `CONFLICT` |
| `POST /project` files 없음(is_img=0) | 400 `VALIDATION_ERROR` |
| `GET /retrieve_progress` reqUserId 누락 | 400 · 필터 0개 400 · 미존재 404 |
| `POST /v1/videovlm/verify` 정상 | **200** `{request_id,status:"accepted"}` |
| `…/verify` event_type 오류 | 422 · media 누락 400 · callback 호스트 위반 400 |
| `GET /v1/videovlm/status` | 200 `{"status":"ok","service":"videovlm"}` (규격은 `ready`/`busy`) |
| `POST /api/genai/jobs` 정상 | **202** `{request_id,job_id,status:"RECEIVED",received_at}` |
| genai 필수누락/중복seq/경로위반/콜백위반/64자초과 | 400 (각각 `REQUIRED_FIELD_MISSING`/`INVALID_PARAMETER`) |
| genai 본문>1MiB | 413 `GA-MEDIA-001` · prompt>64KiB 400 `INVALID_METADATA` |
| genai results(비SUCCEEDED) / cancel(종결) | 409 `STATE_CONFLICT` |
| genai 미존재 job | 404 `JOB_NOT_FOUND` |
