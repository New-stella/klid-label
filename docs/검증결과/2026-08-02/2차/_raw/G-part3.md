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
