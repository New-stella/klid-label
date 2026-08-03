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
