# G 클러스터 part1 — G-3(YOLO track) + G-4(SAM2) 검증 결과 (3차)

- 담당: `docs/test-cases/G-ai-server.md` **G-3(TC-AIYOLO-44~55, 12건)** + **G-4(TC-AISAM2-01~28, 28건)** = **40건**
- 검증일: 2026-08-03/04 · 대상 커밋 `e065da42`(= `dcdbb827` "2차 검증 신규 HIGH 11건 수정" 포함)
- 실행 환경: `klid-ai-server` 컨테이너(`localhost:19300`), `AI_MOCK_MODE=false`, `AI_DEVICE=cpu`
  - **YOLOX**: `/app/weights` 빈 디렉터리 → 전 요청 `mock_reason=weights_missing`(stack-bringup §8 재확인). `env_mock`·실백엔드 전제 케이스는 런타임 재현 불가.
  - **SAM2**: HuggingFace 캐시에서 **실모델 로드 성공** — 정상 요청이 `mock=false, source="model"` 로 응답(C클러스터 part5 관측 재확인). `_mock_segment`/`_prev_polygon_fallback` 은 **`empty_mask` fallback 경유로 실동작 관측 가능**(1x1 이미지·영면적 box 로 유도).
- 자동테스트 baseline(3차 `_raw/test-baseline.md`): ai-server **145/145 통과, 스킵 0건** → `test_sam2_real.py`(skipif sam2 미설치)도 실행됨.
- 빌드/테스트 실행 없음. 프로덕션 코드 미수정. 카탈로그 정정은 담당 라인범위(65~114행) 내에서만 수행.

---

## 0. ★★ 2차 HIGH 3건 재현·재확인 결과 (최우선 지시사항)

| 2차 이슈 | 대상 TC | 2차 증상 | **3차 실측** | 판정 |
|---|---|---|---|:--:|
| **G-ISSUE-01** (2차 HIGH #4) — YOLO track mock폴백 시 입력검증 스킵, conftest 강제설정으로 거짓통과 | TC-AIYOLO-49 | `weights_missing` 형상에서 `image_b64="!!!notb64"` → **200** | `POST /infer/yolo/track` invalid base64 → **400 INVALID_IMAGE**. GIF → **400**, 14.5MB PNG → **413**. `/predict` 와 상태코드 완전 대칭 | **해소** |
| **G-ISSUE-41** (2차 HIGH #5) — SAM2 mock폴백이 잘못된 좌표에 재충돌 500 | TC-AISAM2-13 | `points=[[5]]` → `_real_segment` IndexError → `_mock_segment` 에서 재폭발 → **500** | `points=[[5]]` → **400 VALIDATION_ERROR** "List should have at least 2 items". `points=[[1,2,3]]` → **400**. 500 재현 불가 | **해소** |
| **G-ISSUE-42** (2차 HIGH #6) — SAM2 track bbox 계산이 try 블록 밖 → 폴백없이 500 | TC-AISAM2-17 | `prev_polygon=[[1],[2],[3]]` → try 밖 IndexError → **500** | `prev_polygon=[[1],[2],[3]]` → **400**(스키마 앞단 차단). 코드상 bbox 유도가 `_polygon_bbox()`(`sam2.py:334-348`)로 분리돼 **실패 시 예외 대신 None → `_prev_polygon_fallback` 반환**. `_real_track` 선두(371-373)에서 fallback 분기 | **해소** |

### 0-1. 코드 레벨 반증 (try/except 경계)

```python
# ai-server/app/routers/yolo.py:175-177  (_track_yolox — G-ISSUE-01 수정)
img = decode_image_b64_pil(req.image_b64)     # ← mock 사유와 무관하게 선두 1회 검증
try:
    backend = yolox_loader.get_yolox_tracker(req.clip_id, reset=(req.frame_index == 0))
```
`_mock_track`(234-261)에서 조건부 디코드가 **제거**됨(242-243 주석: "입력 검증(디코드)은 호출자가 사유와 무관하게 이미 수행했다").
⭐ 추가로 **검증이 트래커 획득보다 앞**에 놓였다(169-173 docstring) — 깨진 입력 1회로 진행 중 clip 의 트래커가 리셋되거나 LRU 캐시가 축출되지 않는다.

```python
# ai-server/app/routers/sam2.py:369-373  (_real_track — G-ISSUE-42 수정)
def _real_track(model: Any, req: Sam2TrackRequest) -> Sam2TrackResponse:
    bbox = _polygon_bbox(req.prev_polygon)     # ← 내부에서 _sanitize_polygon 위임, 예외 없음
    if bbox is None:
        return _prev_polygon_fallback(req, "empty_mask")   # ← try 진입 전에도 fallback 존재
    pil_image = decode_image_b64_pil(req.next_image_b64)
    try:
```
```python
# ai-server/app/routers/sam2.py:426-427  (_mock_segment — G-ISSUE-41 수정)
box = _sanitize_coords(req.box)          # 비유한/비수치 → 빈 리스트
points = _sanitize_polygon(req.points)   # 원소부족/None/dict/str/1e400 → 버림
```
→ **fallback 3곳(`_mock_segment`·`_mock_track`·`_prev_polygon_fallback`) 전부 "어떤 입력에도 예외를 던지지 않는다"** 를 docstring 계약으로 명시 + 정규화 헬퍼 4종(`_safe_iter`/`_sanitize_polygon`/`_sanitize_coords`/`_echo_polygon`)에 단일 위임.

### 0-2. 거짓통과 근인(conftest 강제설정) 해소 확인

2차 지적의 핵심은 "테스트가 `conftest.py:17` 의 `AI_MOCK_MODE=true` 때문에 `env_mock` 분기만 타서 운영 형상을 검증 못 함"이었다. 신규 회귀테스트 `tests/test_yolo_track_input_validation.py` 가 **mock 사유를 monkeypatch 로 강제**해 이를 정면으로 막는다:

| 테스트 | 검증 내용 |
|---|---|
| `:53` `test_weights_missing_사유일_때_invalid_base64_요청은_400을_반환한다` | 운영 형상 |
| `:67` `test_load_failed_사유일_때도_invalid_base64는_400을_반환한다` | 로드실패 형상 |
| `:81` `test_weights_missing_사유일_때_크기초과_이미지는_413을_반환한다` | 크기 게이트 |
| `:96` `test_predict와_track이_같은_입력에_같은_상태코드를_반환한다` | 형상 의존 계약분기 차단 |
| `:150` `test_env_mock_경로에서_이미지_디코드는_요청당_1회만_수행된다` | 이중 디코드 회귀 |
| `:182` `test_실모델_형상에서_깨진_base64는_트래커_상태변경_전에_400을_반환한다` | 검증→상태변경 순서 |

SAM2 쪽도 `tests/test_sam2_point_validation.py`(9건)·`tests/test_sam2_hardening.py`(24건)이 신설됨.

### 0-3. 500 무발생 적대 fuzz (18 케이스)

두 엔드포인트에 경계·비정상 입력 18종을 전송 — **500 응답 0건**.

```
200 box x1>x2 / box+points 동시 / points 극단 음수(-1e30) / null null / points 빈배열 / box 1e30
400 next·prev 깨진 b64 / track_id 65자 / track_id 슬래시 / conf 1.5 / imgsz 100 / iou -0.1 / classes 101개
200 track_id 64자 경계 / prev_polygon 극단(-1e30) / clip_id 경로문자 / clip_id 개행 / classes 빈배열
```

---

## 1. G-3. YOLO track — 판정표 (12건)

| ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-AIYOLO-44 | PASS | [정적] `routers/yolo.py:245-258` `if reason=="env_mock"` → person/track_id=1/mock=true. 근거 라인 `234-261` 현행 일치. [테스트] `test_yolo_track.py:~100-111` (baseline 통과) | 런타임은 `weights_missing` 이라 env_mock 분기 미도달 — 실동작 재현 불가(G-ISSUE-05 이월) |
| TC-AIYOLO-45 | PASS | [실동작] `{"detections":[],"mock":true,"source":"mock","mock_reason":"weights_missing"}` HTTP 200 | 오염 방지 빈 detections 확인 |
| TC-AIYOLO-46 | PASS | [실동작] `clip_id=""` → 400 "String should have at least 1 character" / 129자 → 400 "at most 128 characters". 근거 `schemas.py:101-106` 일치 | |
| TC-AIYOLO-47 | PASS | [실동작] `frame_index=-1` → 400 "greater than or equal to 0". `schemas.py:107-109` 일치 | |
| TC-AIYOLO-48 | BLOCKED | [정적] `routers/yolo.py:177` `get_yolox_tracker(req.clip_id, reset=(req.frame_index==0))` · `yolox_loader.py:479-490` `if reset or clip_id not in _TRACKERS: 새 핸들` | 가중치 미배포로 `get_yolox_tracker` 가 reason 체크에서 즉시 None 반환(`yolox_loader.py:471-473`) → 리셋 동작 실증 불가. **2차 G-ISSUE-02 이월** |
| TC-AIYOLO-49 | PASS | [실동작] invalid base64 → **400 INVALID_IMAGE**(2차 200 → 해소) | ★2차 HIGH #4 해소. 카탈로그 근거 정정함 |
| TC-AIYOLO-50 | PASS | [정적] `_apply_class_filter`(`yolo.py:44-57`)를 `/track`(217)·`/predict`(64)가 공유, `if not classes: return detections`. [테스트] `test_yolo_class_filter.py` | env_mock 미도달로 런타임은 detections 0건이라 공허 통과 — 정적/테스트로 판정 |
| TC-AIYOLO-51 | PASS | [실동작] 응답 키 = `['detections','error_code','message','mock','mock_reason','source','success']`, extra field → 400. [테스트] `test_yolo_track.py:114` 가 detection 키 `{label,points,score,track_id}` 단언 | |
| TC-AIYOLO-52 | BLOCKED | [정적] `bytetrack_util.py:92` `det.track_id = int(tid) if tid is not None and int(tid) >= 0 else None` — 계약 충족 | 실트래커 경로가 YOLOX 가중치에 종속 → 실동작 불가. **전용 자동테스트 0건**(G-ISSUE-04 이월) |
| TC-AIYOLO-53 | BLOCKED | [정적] `bytetrack_util.py:83-89` 길이 불일치 WARN + `zip` 으로 짧은 쪽만 매핑(누락분 None 유지) — 계약 충족 | 동상. 자동테스트 0건 |
| TC-AIYOLO-54 | BLOCKED | [정적] `bytetrack_util.py:44-56` try/except + `_tracker_unavailable_warned` WARN-once — 계약 충족 | `trackers` 미설치 형상 재현 불가. `test_yolox_loader.py:33,37` 은 플래그 리셋만 하고 WARN-once 자체는 미검증 |
| TC-AIYOLO-55 | PARTIAL | [정적] `bytetrack_util.py:79` `class_id=coco_id_from_label(...)` 로 클래스 분리 — person/car 등 COCO 80종은 결정적. [실동작] 그러나 **미지 라벨은 프로세스마다 id 가 달라짐** (`coco_id_from_label('unknown_thing')` → 26423 / 75426) | 케이스 주목적(person+car 충돌 방지)은 충족하나 `detector_backend.py:107-124` docstring 계약("같은 라벨은 항상 같은 정수") 위반. **2차 G-ISSUE-07 이월** |

**G-3 집계**: PASS 7 · PARTIAL 1 · BLOCKED 4 · FAIL 0

---

## 2. G-4. SAM2 — 판정표 (28건)

| ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-AISAM2-01 | PASS | [실동작] 1x1 PNG + `points=[[0,0]]` → `empty_mask` fallback 으로 `_mock_segment` 도달: `polygon=[[-0.1,-0.1],[0.1,-0.1],[0.1,0.1],[-0.1,0.1]]`(**4점**) `score=0.95` `mock=true`. 근거 `sam2.py:415-440` 일치 | `AI_MOCK_MODE` 전제 대신 실모델 fallback 경유로 실증 |
| TC-AISAM2-02 | PASS | [실동작] `box=[0,0,0,0]` → `polygon=[[0,0],[0,0],[0,0],[0,0]]` score=0.95 mock=true. `sam2.py:426-429` 일치 | 영면적 이슈는 G-ISSUE-02 참조 |
| TC-AISAM2-03 | PASS | [실동작] 프롬프트 없음 + 1x1 → `polygon=[[0.4,0.4],[0.6,0.4],[0.6,0.6],[0.4,0.6]]` = 중앙 40~60% 사각. `sam2.py:434-436` 일치 | |
| TC-AISAM2-04 | PASS | [실동작] `{"conf":0.5}` → 400 "Extra inputs are not permitted". `schemas.py:171-182` `extra="forbid"` | |
| TC-AISAM2-05 | PASS | [실동작] `box=[1,2,3]` → 400 "at least 4 items". `schemas.py:180-182` | |
| TC-AISAM2-06 | PASS | [실동작] `image_b64=""` → 400 "at least 1 character". `schemas.py:174` | |
| TC-AISAM2-07 | PASS | [실동작] `"!!!notb64"` → 400 INVALID_IMAGE "base64 디코드 실패". `image_utils.py:37-40` | |
| TC-AISAM2-08 | PASS | [실동작] 14.5MB PNG → 413 IMAGE_TOO_LARGE "max 10MB". `image_utils.py:42-43` | 전제 컬럼 "1MB" 는 conftest 값(`MAX_IMAGE_SIZE_MB=1`), 런타임 실효값은 10MB(`config.py:47` 기본) — 게이트 동작 자체는 동일 |
| TC-AISAM2-09 | PASS | [실동작] 100x80 + `points=[[50,40]]` → `mock=false source="model"` polygon 5점 반환. [테스트] `test_sam2_meta.py:119` 최대면적 contour 단언. `sam2.py:230-259` 일치 | 실모델 경로 실증 |
| TC-AISAM2-10 | PASS | [실동작] 영면적 box → 로그 `[SAM2] segment real returned no mask — mock fallback` + mock 응답. `sam2.py:246-247` | |
| TC-AISAM2-11 | PASS | [정적] `sam2.py:251-254` `if len(pts) < 3: logger.debug(...); return None`. 2x2 이미지로 유도 시도했으나 4점 contour 가 나와 런타임 미도달 | 전용 자동테스트는 미확인(`test_sam2_meta.py:138` 은 빈 마스크 케이스) |
| TC-AISAM2-12 | PASS | [정적] `sam2.py:296` `score = max(0.0, min(float(sc[best]), 1.0))`. [테스트] `test_sam2_meta.py:192`(0~1 clamp)·`:200`(음수→0). [실동작] 실모델 score 3.13e-7 / 0.9629 전부 `0.0≤s≤1.0` | |
| TC-AISAM2-13 | PASS | [실동작] **2차 500 재현 불가** — `points=[[5]]` 이 400 으로 앞단 차단. [정적] `sam2.py:313-316` except → `_mock_segment(width,height,req,"empty_mask")`, `_mock_segment` 는 `_sanitize_*` 위임으로 예외 불가. [테스트] `test_sam2_meta.py:208` | ★2차 HIGH #5 해소 |
| TC-AISAM2-14 | PASS | [실동작] 1x1/영면적 box → `mock=true mock_reason="empty_mask"` + WARN 로그. `sam2.py:328-331` | |
| TC-AISAM2-15 | PASS | [실동작] `prev_polygon=[[10,10],[60,10],[60,60],[10,60]]` → `mock=false source="model"` 73점 polygon. [정적] `sam2.py:371` `_polygon_bbox` → `378` `_predict_masks(..., box=bbox)` | |
| TC-AISAM2-16 | PASS | [실동작] 2점 → 400 "at least 3 items". `schemas.py:214-216` | |
| TC-AISAM2-17 | PASS | [실동작] `[[1],[2],[3]]` → **400**(2차 500 → 해소). 퇴화 폴리곤 `[[5,5]×3]` → 200 `polygon=prev score=0.5 mock=true mock_reason=empty_mask`. [정적] `sam2.py:379-384` except → `_prev_polygon_fallback` + `371-373` try 밖 방어. [테스트] `test_sam2_meta.py:221`, `test_sam2_hardening.py:223` | ★2차 HIGH #6 해소 |
| TC-AISAM2-18 | PASS | [실동작] 1x1 이미지 track → 로그 `track real returned no mask — prev polygon fallback` + `polygon=[[0,0],[1,0],[1,1]]`(=prev) score=0.5. `sam2.py:406-408` | |
| TC-AISAM2-19 | PASS | [실동작] `track_id` 반사 + `polygon=prev` 확인(fallback 경유). [정적] `sam2.py:443-455` `_mock_track` score=0.9 · `_safe(req.track_id)` · `_echo_polygon(req.prev_polygon)` | env_mock 전용 score=0.9 는 정적 판정 |
| TC-AISAM2-20 | PASS | [실동작] segment/track 양쪽 fallback 응답 모두 `mock=true source="mock"`. [테스트] `test_mock_indicator.py:33,49` 라인 일치 | |
| TC-AISAM2-21 | PASS | [정적] `grep -c ultralytics ai-server/app/**` 0건. [테스트] `test_sam2_meta.py:290` `test_sam2_loader가_ultralytics를_import하지_않음` 라인 일치 | |
| TC-AISAM2-22 | PASS | [정적] `sam2_loader.py:33-37` `if settings.ai_mock_mode: ... _mock_reason="env_mock"; return None`. [테스트] `test_sam2_meta.py:299` | |
| TC-AISAM2-23 | PASS | [정적] `sam2_loader.py:51-55` except → `_mock_reason="load_failed"`. [테스트] `test_sam2_meta.py:306`,`:435` | |
| TC-AISAM2-24 | PASS | [정적] `sam2.py:180-197` `_mock_reason()` 이 loader 사유를 우선 위임(`get_sam2_mock_reason()`), 없을 때만 폴백. [테스트] `test_sam2_meta.py:435` | |
| TC-AISAM2-25 | PASS | [정적] `sam2_loader.py:47` `SAM2ImagePredictor.from_pretrained(model_id, device=settings.ai_device)`. [실동작] `AI_DEVICE=cpu` 컨테이너에서 **실제 로드 성공**(`mock=false`) = 이 수정의 실증. [테스트] `test_sam2_meta.py:353` | |
| TC-AISAM2-26 | PASS | [정적] `sam2_loader.py:29-30` `if _loaded: return _sam2_model`. [실동작] 20+ 연속 요청 전부 실모델 응답, 재로드 로그 0건. [테스트] `test_sam2_meta.py:319` | |
| TC-AISAM2-27 | PASS | [실동작] points/box 프롬프트 각각 `polygon`(5점/73점) + `0≤score≤1`. [테스트] `test_sam2_real.py:56,77` 라인 일치, baseline **스킵 0건**이므로 실제 실행됨 | 게이트(skipif) 미발동 확인 |
| TC-AISAM2-28 | PASS | [실동작] `track_id="t1"` 반사 + polygon 전파. [테스트] `test_sam2_real.py:94` 라인 일치 | |

**G-4 집계**: PASS 28 · FAIL 0 · PARTIAL 0 · BLOCKED 0

---

## 3. 종합 집계

| 섹션 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| G-3 YOLO track | 12 | 7 | 0 | 1 | 4 | 0 | 0 |
| G-4 SAM2 | 28 | 28 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **40** | **35** | **0** | **1** | **4** | **0** | **0** |

PASS율 87.5%(35/40). BLOCKED 4건은 전부 **YOLOX ONNX 가중치 미배포**(2차 G-ISSUE-02) 단일 사유.

---

## 4. 이슈

### [G-ISSUE-01] TC-AIYOLO-46 인접 — `clip_id` 에 문자 패턴 제약이 없어 SAM2 `track_id` 하드닝과 비대칭 (Log Injection 잠재)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 상위 시스템(BE)이 전달하는 식별자 문자열은 로그 출력 전 개행(`\r`,`\n`)·제어문자가 배제돼야 한다(`rules/security.md` CWE-117, BE 는 `LogSanitizer` 로 이미 준수). 같은 라우터군의 SAM2 `track_id` 는 2026-08-03 하드닝에서 **스키마 패턴 + 출력시점 `_safe()` 이중 방어**를 받았으므로, 동일 성격의 `clip_id` 도 같은 계약이어야 한다.
- **현재 동작(이슈 내용)**: `clip_id` 는 길이 제약만 있고 문자 제약이 없으며, 정제 없이 로그 포맷 인자로 들어간다.
  ```python
  # ai-server/app/schemas.py:101-106  (제약 = 길이뿐)
  clip_id: str = Field(..., min_length=1, max_length=128, description="영상 식별자 ...")
  # 대조 — ai-server/app/schemas.py:205-211 (SAM2 track_id 는 패턴까지 강제)
  track_id: str = Field(..., min_length=1, max_length=64, pattern=TRACK_ID_PATTERN, ...)
  ```
  ```python
  # ai-server/app/routers/yolo.py:181-186, 196-199 — 원문 그대로 로그 인자
  logger.info("[YOLOX][MOCK] track reason=%s clip_id=%s frame_index=%d ...", reason, req.clip_id, ...)
  logger.info("[YOLOX] real track clip_id=%s frame_index=%d ...", req.clip_id, ...)
  # ai-server/app/models/yolox_loader.py:451,458,484-489 — 축출/생성 로그에도 clip_id 원문
  ```
  실측: `clip_id="a\nINJECT"` · `clip_id="../../etc/passwd"` 둘 다 **200 수락**.
  ⚠ 현재는 **앱 INFO 로그가 출력되지 않아 미발현**이다(`docker logs klid-ai-server | grep -c 'INFO:app\.'` → **0**, 2차 G-ISSUE-64 지속). 로그 레벨을 INFO 로 올리거나 로그 수집기를 붙이는 순간 활성화된다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:19300/infer/yolo/track -H 'Content-Type: application/json' \
    -d '{"image_b64":"<valid png b64>","clip_id":"a\nINJECT","frame_index":0}' -w "\nHTTP:%{http_code}\n"
  # 실측 200 (SAM2 동일 입력의 track_id 는 400)
  ```
- **영향**: CWE-117(Log Injection). 감사 추적 오염 — `[YOLOX] tracker created clip_id=...` 라인을 위조할 수 있다. 부수적으로 `clip_id` 는 트래커 캐시(LRU max 10)의 **키**이므로 무제약 문자열이 캐시 키 공간에 그대로 들어간다.
- **수정 방향(제안)**: `schemas.py:101-106` 의 `clip_id` 에 SAM2 와 동일한 `pattern=TRACK_ID_PATTERN`(또는 그 계열) 부여 + `routers/yolo.py` 로그 인자에 `sam2.py:38-46` 의 `_safe()` 와 동일한 공통 정제 헬퍼 적용. ⚠ 구현하지 않는다.

### [G-ISSUE-02] TC-AISAM2-02/03 — mock/fallback 폴리곤 좌표 무클램프 (2차 G-ISSUE-43 이월 · **실모델 형상에서도 도달 확인**)
- **심각도**: LOW
- **기대 동작(기대효과)**: mock/fallback 응답도 **그 이미지 안의 유효한 폴리곤**이어야 한다. ai-server 는 `width,height` 를 이미 알고 있으므로(`sam2.py:123`,`:303`) 경계 clamp 와 최소 면적 보장이 가능하다. `CLAUDE.md` ★3("AI 검출 응답 = clamp + 퇴화 스킵")의 정신과 정합해야 한다.
- **현재 동작(이슈 내용)**: `_mock_segment` 가 요청 box/point 를 정규화만 하고 **경계 clamp·면적 검사 없이 그대로** 폴리곤으로 되돌린다.
  ```python
  # ai-server/app/routers/sam2.py:426-437
  box = _sanitize_coords(req.box)
  points = _sanitize_polygon(req.points)
  if len(box) == 4:
      x1, y1, x2, y2 = box              # ← 이미지 경계·면적 검증 없음
  elif points:
      cx, cy = points[0]
      half = min(width, height) * 0.1
      x1, y1, x2, y2 = cx - half, cy - half, cx + half, cy + half   # ← 음수 가능
  polygon = [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]
  ```
  실측 — **2차와 달리 `AI_MOCK_MODE` 없이 실모델 형상에서 `empty_mask` fallback 경유로 도달**:
  | 입력(이미지) | 응답 폴리곤 |
  |---|---|
  | 1x1 PNG + `box=[10,10,60,60]` | `[[10,10],[60,10],[60,60],[10,60]]` — **이미지(1x1) 밖 60배** |
  | 1x1 PNG + `points=[[0,0]]` | `[[-0.1,-0.1],[0.1,-0.1],[0.1,0.1],[-0.1,0.1]]` — **음수 좌표** |
  | 100x80 + `box=[0,0,0,0]` | `[[0,0],[0,0],[0,0],[0,0]]` — **영면적(퇴화)** |
  track 측도 동일 — `prev_polygon=[[-1e30,-1e30],[1,1],[2,2]]` → fallback 이 `-1e+30` 을 그대로 반사.
- **재현/확인 경로**:
  ```bash
  # 1x1 PNG 는 SAM2 실모델이 마스크를 못 내 empty_mask fallback 으로 떨어진다
  curl -s -X POST http://localhost:19300/infer/sam2/segment -H 'Content-Type: application/json' \
    -d '{"image_b64":"<1x1 png b64>","box":[10,10,60,60]}'
  # 실측 200 mock=true mock_reason=empty_mask polygon=[[10,10],[60,10],[60,60],[10,60]]
  ```
- **영향**: 기능 영향은 제한적 — BE `Sam2SegmentService`(`backend/.../label/service/Sam2SegmentService.java`)가 `aiRes.untrusted()` 로 mock 응답을 빈 폴리곤 치환하고 `validatePolygon(polygon, imgWidth, imgHeight)` 로 상한도 검증한다. 다만 **2차 판정("AI_MOCK_MODE 전용")은 오판**이었고 운영 형상에서도 재현되므로, ai-server 를 직접 소비하는 다른 클라이언트에는 방어가 없다.
- **수정 방향(제안)**: `_mock_segment` 에서 `x1,y1,x2,y2` 를 `[0,width]`/`[0,height]` 로 clamp 하고, clamp 후 면적이 0 이면 중앙 사각(`sam2.py:436`) 분기로 폴백. `_prev_polygon_fallback`/`_echo_polygon` 도 동일 정책 적용 여부를 함께 결정(단, `_echo_polygon` 은 "받은 좌표를 그대로 돌려준다"가 명시 계약이라 변경 시 계약 갱신 필요). ⚠ 구현하지 않는다.

### [G-ISSUE-03] TC-AIYOLO-55 — `coco_id_from_label` 미지 라벨 id 가 프로세스마다 달라져 docstring 계약을 위반 (2차 G-ISSUE-07 이월, 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: `detector_backend.py:107-124` docstring 이 "같은 라벨은 항상 같은 정수가 되며 COCO id 범위(0~79)와 충돌하지 않는다"를 계약으로 선언한다. 트래커가 클래스별 트랙 공간을 분리하므로 이 id 가 흔들리면 재기동 전후 track_id 해석이 달라진다.
- **현재 동작(이슈 내용)**:
  ```python
  # ai-server/app/models/detector_backend.py:124
  return _UNKNOWN_LABEL_ID_BASE + (hash(label) & 0xFFFF)
  ```
  Python `str.__hash__` 는 `PYTHONHASHSEED` 로 프로세스마다 랜덤화되며 컨테이너에 해당 변수가 **미설정**이다.
  실측(동일 컨테이너, 별개 프로세스 2회):
  ```
  $ docker exec klid-ai-server python -c "from app.models.detector_backend import coco_id_from_label; print(coco_id_from_label('unknown_thing'))"
  26423
  75426     ← 재실행 시 다른 값
  ```
- **재현/확인 경로**: 위 명령 2회 실행. COCO 80종(person/car 등)은 `_LABEL2ID` 직접 조회라 영향 없음.
- **영향**: 기능(경미). 현재 검출 라벨은 COCO 80종으로 제한(`AutolabelOnlineService.resolveDetectClasses` 가 마스터 매핑 화이트리스트와 교집합만 전달)이라 실 파이프라인에서 미지 라벨이 도달할 경로가 사실상 없다. 다만 **문서화된 계약과 구현이 어긋난 상태**가 유지된다.
- **수정 방향(제안)**: `hash(label)` 대신 `zlib.crc32(label.encode())` 또는 `hashlib.blake2s(label.encode(), digest_size=2)` 같은 **프로세스 불변 해시**로 교체하거나, docstring 계약을 "프로세스 내에서만 안정"으로 하향 정정. ⚠ 구현하지 않는다.

### [G-ISSUE-04] TC-AIYOLO-52~55 — `bytetrack_util` 전용 자동테스트가 여전히 0건 (2차 G-ISSUE-08 이월, 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: `track_id` 정규화(-1→None)·길이 불일치 WARN·WARN-once·클래스 분리는 track 응답 품질의 핵심이므로 회귀 가드가 있어야 한다. 실트래커 없이도 `sv.Detections`/tracker 를 monkeypatch 해 단위 검증이 가능하다.
- **현재 동작(이슈 내용)**: `ai-server/tests/` 17개 파일에 `test_bytetrack_util.py` 가 없다. `_apply_bytetrack` 을 직접 호출·검증하는 테스트도 0건 —
  - `test_yolox_loader.py:33,37` 은 `bytetrack_util.reset_tracker_unavailable_warned()` 만 호출(플래그 리셋).
  - `test_yolox_loader.py:389-412` 는 `_apply_bytetrack` 을 **가짜 함수로 교체**해 호출 여부만 본다(내부 로직 미검증).
  - `test_yolo_dispatch.py:84` 는 응답 키 집합만 단언.
- **재현/확인 경로**: `grep -rn '_apply_bytetrack\|tracker_id' ai-server/tests/` → 위 3곳만.
- **영향**: 테스트 커버리지. TC-AIYOLO-52/53/54 가 3차에서도 BLOCKED 로 남는 직접 원인 중 하나(다른 하나는 가중치 미배포). `-1 → None` 매핑이 깨져도 CI 가 못 잡는다.
- **수정 방향(제안)**: `tests/test_bytetrack_util.py` 신설 — ①`tracker.update` 를 stub 해 `tracker_id=[-1,3]` → `[None,3]` ②`tracker_id` 길이 < dets 길이 → WARN + 누락분 None ③`from trackers import ByteTrackTracker` 를 `ImportError` 로 monkeypatch → None 반환 + WARN 1회 ④`coco_id_from_label` 로 person/car 가 다른 `class_id` 를 받는지. ⚠ 구현하지 않는다.

### [G-ISSUE-05] TC-AIYOLO-44/48/52/53/54 — YOLOX ONNX 가중치 미배포로 G-3 실백엔드·env_mock 케이스 5건이 3차에서도 실동작 미검증 (2차 G-ISSUE-02 이월, 미해소)
- **심각도**: HIGH (이월)
- **기대 동작(기대효과)**: VERIFY-PROMPT §1 대전제("정상 시나리오 위에서 실동작 판정"). 오토라벨(YOLO)이 실검출을 내야 트랙 ID 연속성·NMS·트래커 리셋·ByteTrack 정규화 계약이 검증된다.
- **현재 동작(이슈 내용)**: `docker exec klid-ai-server ls /app/weights` → **0 files**(3차 stack-bringup §8 재확인). 전 요청이 `mock_reason=weights_missing`.
  ```python
  # ai-server/app/models/yolox_loader.py:471-473  (get_yolox_tracker)
  reason = get_yolox_mock_reason()
  if reason is not None:
      return None            # ← 트래커 자체가 생성되지 않아 리셋/LRU/ByteTrack 경로 전부 미도달
  ```
- **재현/확인 경로**: `docker exec klid-ai-server ls -la /app/weights` · `curl POST /infer/yolo/track` → `mock_reason=weights_missing`.
- **영향**: 기능/검증. ①오토라벨링(SFR-08 핵심)이 전 환경 무동작 ②본 회차 40건 중 **4건 BLOCKED + 2건(TC-AIYOLO-44/50) 정적 판정 강제** ③G-2 전 항목·G-3 실트래킹이 회차를 넘겨 계속 미검증.
- **수정 방향(제안)**: 2차 제안 유지 — ①`weights/yolox_s.onnx` 배포 산출물 포함 또는 기동 시 fetch 절차 명문화 + compose 두 파일(`docker-compose.yml:191` `./weights` vs `docker-compose.local.yml:107` `./ai-server/weights`) **마운트 소스 경로 통일** ②검증 환경에 가중치를 두어 다음 회차에 BLOCKED 해소. ⚠ 구현하지 않는다.

### [G-ISSUE-06] TC-AISAM2-20 인접 — `Sam2TrackResponse` 만 연동정의서 표준 래퍼 3필드(`success`/`message`/`error_code`)가 없다
- **심각도**: LOW
- **기대 동작(기대효과)**: 같은 ai-server 의 성공 응답 스키마는 래퍼 필드 유무가 일관돼야 한다 — `YoloResponse`(`schemas.py:86-88`)·`YoloTrackResponse`(`:139-141`)·`Sam2SegmentResponse`(`:197-199`)는 세 필드를 모두 갖고 있고, 테스트도 "연동정의서 표준 래퍼 필드"로 단언한다(`test_mock_indicator.py:28-30,43-45`).
- **현재 동작(이슈 내용)**: `Sam2TrackResponse`(`schemas.py:219-232`)에는 세 필드가 없다.
  ```
  $ curl -s .../infer/sam2/segment ... | jq 'keys'
  ["error_code","message","mock","mock_reason","polygon","score","source","success"]
  $ curl -s .../infer/sam2/track ... | jq 'keys'
  ["mock","mock_reason","polygon","score","source","track_id"]        ← 3필드 부재
  ```
  `test_mock_indicator.py:49-62`(track)만 유일하게 래퍼 필드를 단언하지 않아 이 불일치를 통과시킨다.
- **재현/확인 경로**: 위 두 curl 의 응답 키 비교.
- **영향**: 계약 정합(경미). BE `Sam2TrackResponse`(`backend/.../common/client/dto/Sam2TrackResponse.java:25-32`)는 6필드만 역직렬화하므로 **현재 BE 파손은 없다.** 다만 연동정의서 표준을 근거로 다른 소비자가 `success` 를 읽으면 null 이 된다.
- **수정 방향(제안)**: `Sam2TrackResponse` 에 나머지 3필드를 추가(기본값 있으므로 하위호환)하거나, 반대로 "SAM2 track 은 래퍼 미적용"을 명시적 계약으로 문서화. 어느 쪽이든 `test_mock_indicator.py` 의 track 케이스에 단언을 추가해 고정. ⚠ 구현하지 않는다.

---

## 5. 근거 드리프트

**0건.** 담당 40행의 `file:line` 을 전수 대조한 결과 전부 현행과 일치했다.
카탈로그가 커밋 `9f99db50`(2026-08-03 17:38, "테스트케이스 근거 전수 재확인 4회차")에서 **수정 커밋 `dcdbb827`(2026-08-03 07:10) 이후 시점으로 재정합**됐기 때문이다 — `sam2.py` 가 +201 라인 늘었음에도(254행 → 455행) 인용 라인이 전부 맞는다.

대조 내역(대표): `routers/yolo.py:234-261 / 177-187,245-246 / 177 / 217`, `schemas.py:101-106 / 107-109 / 171-182 / 180-182 / 174 / 214-216`, `routers/sam2.py:415-440 / 426-429 / 434-436 / 230-259 / 246-247 / 251-254 / 280-297 / 313-316 / 328-331 / 369-384 / 379-384 / 406-408 / 443-455 / 180-197`, `image_utils.py:37-44 / 42-43`, `sam2_loader.py:32-37 / 44-47 / 51-58 / 27-59`, `bytetrack_util.py:82-92 / 83-92 / 37-56 / 74-80`, `test_mock_indicator.py:33,49`, `test_sam2_meta.py:290`, `test_sam2_real.py:56,77,94`, `test_yolo_track.py:114,126`.

---

## 6. 카탈로그 정정 (담당 라인범위 65~114행 내 Edit 수행)

CLAUDE.md "문서 동기화 규칙 — ★ 동작·정책이 바뀌면 `docs/test-cases/` 카탈로그도 갱신" 에 따라 **정정 1건 + 신규 9건** 을 반영했다. 폐기 0건.

| 구분 | ID | 내용 |
|:--:|---|---|
| 정정 | TC-AIYOLO-49 | 근거를 `test_yolo_track.py:126` **단독**에서 프로덕션 코드(`routers/yolo.py:175`) + mock 사유 monkeypatch 회귀테스트(`test_yolo_track_input_validation.py:53,67,81,96`)로 교체. **그 테스트가 conftest 강제설정 때문에 2차 HIGH 를 거짓통과시킨 경위**를 기대결과 셀에 명시(같은 오판 재발 차단) |
| 신규 | TC-AIYOLO-56 | `/track`↔`/predict` 상태코드 대칭(GIF 400 · 초과 413) — 배포 형상에 따라 계약이 갈리지 않음 |
| 신규 | TC-AIYOLO-57 | 입력 검증이 트래커 **상태 변경(핸들 생성·리셋·LRU 축출)보다 먼저** 수행됨 |
| 신규 | TC-AIYOLO-58 | ⚠`clip_id` 문자 패턴 미제약(SAM2 `track_id` 와 비대칭) — G-ISSUE-01 대응 케이스 |
| 신규 | TC-AISAM2-29 | 좌표 원소 정확히 2개 강제 → 400 (2차 HIGH #5/#6 근인 앞단 제거, 되돌리기 금지 명시) |
| 신규 | TC-AISAM2-30 | NaN/Infinity/1e400 좌표 거부 → 400 |
| 신규 | TC-AISAM2-31 | `points`≤100 / `prev_polygon`≤1000 상한(CWE-770, BE `@Size` 정합) + 100 경계값 통과 |
| 신규 | TC-AISAM2-32 | `track_id` 패턴·길이 제약 + 출력시점 `_safe()` 이중 방어(CWE-117) |
| 신규 | TC-AISAM2-33 | ⚠`Sam2TrackResponse.polygon` 하한 없음 = segment 와 **의도적 비대칭**(통일 금지 — 통일하면 마지막 방어선이 500 으로 터짐) |
| 신규 | TC-AISAM2-34 | ⚠mock/fallback 폴리곤 무클램프 — 실모델 형상에서도 도달(G-ISSUE-02 대응 케이스) |

> ⚠ **병합 담당자 조치 필요**: 카탈로그 상단 `## 변경 이력` 표와 머리말은 **담당 라인범위(65~114행) 밖**이라 손대지 않았다.
> - 11행에 이미 다른 담당(G-7~G-9)이 추가한 3회차 행이 있다(`| 3 | 2026-08-03(3차) | 2건 | 0건 | 0건 | ... |`). **여기에 본 part1 분을 합산**해야 한다 → 정정 `2건 → 3건`, 신규 `0건 → 9건`, 요약에 다음을 추가:
>   *"G-3/G-4 도 3차 재검증 — 정정 1(TC-AIYOLO-49 근거를 conftest 강제설정 때문에 2차 HIGH 를 거짓통과시킨 테스트 단독 → 프로덕션 코드 `routers/yolo.py:175` + mock 사유 monkeypatch 회귀테스트로 교체) / 신규 9(커밋 `dcdbb827` 하드닝이 카탈로그에 미수록: track 입력검증 대칭·검증→상태변경 순서·clip_id 패턴 비대칭·좌표 원소길이 강제·비유한 좌표 거부·프롬프트 배열 상한·track_id 패턴·track 응답 polygon 하한 비대칭·mock 폴리곤 무클램프). G-3/G-4 40행 근거 드리프트 0건."*
> - 3행 머리말 케이스 수 `163 케이스` → **`172 케이스`** (`grep -cE '^\| *~*TC-' docs/test-cases/G-ai-server.md` 실측 172).

---

## 7. UNCERTAINTIES 갱신 제안 (본 회차 확정분)

| # | 항목 | 기존 | **3차 실측** |
|---|------|------|------|
| 15 | 실모델 테스트 게이팅 | 2차: "부분 확정 — 컨테이너에 sam2 설치·로드됨" | **✅ 확정 확장** — 컨테이너뿐 아니라 **baseline 실행 환경(호스트 시스템 python3)에서도 `test_sam2_real.py` 가 skip 되지 않는다**(145건 중 스킵 **0건**). 즉 `skipif(find_spec("sam2") is None)` 게이트는 현 검증 환경 어디서도 발동하지 않으며, 실모델 테스트는 이미 상시 실행 중이다. 반면 **YOLOX 는 정반대** — `AI_MOCK_MODE=false` 인데 가중치 부재로 전 요청이 mock(G-ISSUE-05). "실모델 게이팅"은 SAM2(HF 자동 다운로드)와 YOLOX(로컬 ONNX 파일)가 **서로 다른 축**임을 전제로 정책을 나눠 정해야 한다 |
| — | (2차 신규) SAM2 `weights_missing` 사유 사문화 | 2차: "HF 자동 다운로드라 실경로 도달 불가한 사문 분기" | **유지** — `sam2.py:197` 폴백은 여전히 존재하나 `sam2_loader` 는 `env_mock`/`load_failed` 두 값만 설정한다(`sam2_loader.py:36,55`). 정리 여부 판단 필요 |

---

## 8. 실행 로그 요약 (재현용)

```bash
# 이미지 준비(스크래치패드): 64x64 / 100x80 / 1x1 / 2x2 PNG + 14.5MB PNG + GIF
# ---- YOLO track (G-ISSUE-01 반증) ----
POST /infer/yolo/track  image_b64="!!!notb64"              → 400 INVALID_IMAGE
POST /infer/yolo/track  GIF base64                         → 400 "지원하지 않는 형식: GIF"
POST /infer/yolo/track  14.5MB PNG                         → 413 IMAGE_TOO_LARGE (predict/segment 동일)
POST /infer/yolo/track  clip_id="" / 129자 / frame_index=-1 → 400 ×3
POST /infer/yolo/track  정상                                → 200 mock_reason=weights_missing detections=[]

# ---- SAM2 segment (G-ISSUE-41 반증) ----
POST /infer/sam2/segment points=[[5]]        → 400 "at least 2 items"
POST /infer/sam2/segment points=[[1,2,3]]    → 400 "at most 2 items"
POST /infer/sam2/segment points=[[50,40]]    → 200 mock=false source=model score=0.9629
POST /infer/sam2/segment box=[0,0,0,0]       → 200 mock=true empty_mask polygon=[[0,0]×4]
POST /infer/sam2/segment 1x1 + 프롬프트 없음   → 200 mock=true polygon=[[0.4,0.4],[0.6,0.4],[0.6,0.6],[0.4,0.6]]

# ---- SAM2 track (G-ISSUE-42 반증) ----
POST /infer/sam2/track prev_polygon=[[1],[2],[3]]        → 400 "at least 2 items"
POST /infer/sam2/track prev_polygon=[[5,5],[5,5],[5,5]]  → 200 polygon=prev score=0.5 mock=true
POST /infer/sam2/track prev_polygon 정상 4점              → 200 mock=false source=model (73점)
POST /infer/sam2/track track_id="a\nINJECT b"            → 400 pattern 위반
POST /infer/sam2/track track_id 64자/65자                 → 200 / 400

# ---- 적대 fuzz 18종 → 500 응답 0건 ----
```
