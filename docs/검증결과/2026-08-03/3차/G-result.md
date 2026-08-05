# G클러스터 (ai-server) — 3차 검증 결과 병합

> 5개 파트 병합. 원본은 `_raw/G-part1.md`~`_raw/G-part5.md` 참조(축약 없이 전문 병합).
> 담당 범위: G-1(YOLO 탐지 28)·G-2(YOLO 후처리/트래커 15, part3) + G-3(YOLO track 12)·G-4(SAM2 28, part1) + G-5(VLM ai-server 14)·G-6(계약정합/공통인프라 20, part4) + G-7(KPST 8)·G-8(VLM벤더 4)·G-9(genai 21, part5) + G-10(BE↔벤더 실배선 13, part2) = 판정 대상 163건(+ 신규 등재 9건, 카탈로그 163→172)

## 종합 판정 집계 (5개 파트 표 재합산)

| 파트 | 절 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| part3 | G-1 YOLO 탐지 (28) + G-2 후처리/트래커 (15) | 43 | 41 | 1 | 1 | 0 | 0 | 0 |
| part1 | G-3 YOLO track (12) | 12 | 7 | 0 | 1 | 4 | 0 | 0 |
| part1 | G-4 SAM2 (28) | 28 | 28 | 0 | 0 | 0 | 0 | 0 |
| part4 | G-5 VLM ai-server (14) + G-6 계약정합/공통인프라 (20) | 34 | 34 | 0 | 0 | 0 | 0 | 0 |
| part5 | G-7 KPST (8) | 8 | 8 | 0 | 0 | 0 | 0 | 0 |
| part5 | G-8 VLM 벤더 (4) | 4 | 3 | 1 | 0 | 0 | 0 | 0 |
| part5 | G-9 genai (21) | 21 | 21 | 0 | 0 | 0 | 0 | 0 |
| part2 | G-10 BE↔벤더 실배선 (13) | 13 | 12 | 0 | 1 | 0 | 0 | 0 |
| **합계** | | **163** | **154** | **2** | **3** | **4** | **0** | **0** |

> ⚠ **병합 시 재합산 정정**: part3(G-1/G-2) 자체 요약표(§1, "PASS 39·PARTIAL 2·FAIL 2")는 **케이스별 판정표 실측(41 PASS·1 PARTIAL·1 FAIL)과 불일치**했다. 병합 담당자가 43개 행을 직접 재카운트(`awk` 로 판정 컬럼 전건 추출)해 정정된 값(PASS 41·PARTIAL 1·FAIL 1)을 위 표·전체 합계에 반영했다. 나머지 4개 파트(part1·part2·part4·part5)의 자체 요약표는 케이스별 표와 전부 일치해 그대로 채택했다.
>
> PARTIAL 1건 = TC-AIYOLO-05(로그 conf 값 자체는 일치하나 ai-server INFO 로그 전량 유실로 검증 수단 자체가 성립 불가). FAIL 1건 = TC-AIYOLO-09(`imgsz` 파라미터가 API 계약상 검증만 되고 실추론엔 전혀 반영되지 않음, 640 고정).

PASS율 = 154/163 = **94.5%**. PASS+PARTIAL(사실상 통과) = 157/163 = **96.3%**.
BLOCKED 4건은 전부 YOLOX ONNX 가중치 미배포(2차 G-ISSUE-02 승계) 단일 사유. FAIL 2건: G-1 TC-AIYOLO-09(imgsz 미반영) + G-8 TC-AIMOCK-12(벤더규격 불일치, 코드 미수정).

카탈로그는 본 회차에 신규 케이스 9건(TC-AIYOLO-56~58, TC-AISAM2-29~34)이 등재됐으나(163→172), 신규분은 이번 판정 대상(163건)에 포함되지 않음 — 다음 회차 검증 대상.

## 카탈로그 정정 집계

| 파트 | 정정 건수 | 대상 |
|---|--:|---|
| part1 | 1건 + 신규 9건 | TC-AIYOLO-49(정정) / TC-AIYOLO-56~58·TC-AISAM2-29~34(신규) |
| part2 | 5건 | TC-AIMOCK-38·39·43·44·45(근거 라인 재드리프트 정정) |
| part3 | 1건 | TC-AIYOLO-27 |
| part4 | 0건 | — |
| part5 | 2건 | TC-AIMOCK-11·12 |
| **총 정정** | **9건** | **+ 신규 9건** (근거: `git diff docs/test-cases/G-ai-server.md` 실측 — 정정 9행 + 신규 9행) |

---


---

# ===== _raw/G-part1.md 원문 =====

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

---

# ===== _raw/G-part2.md 원문 =====

# G 클러스터 part2 — G-10. BE ↔ 외부 벤더 실배선 계약 (TC-AIMOCK-34~46, 13건)

- 담당 파일: `docs/test-cases/G-ai-server.md` §G-10 (검증 착수 시점 217행~파일끝 — 병렬 에이전트의 상위 절 추가로 검증 중 227행~로 이동)
- 대상: `VlmUrlPolicy` / `ProfileGatedUrlPolicy` / `ExternalUrlPolicy` / `DeidentifyHealthIndicator` / `VlmClient` / `VlmTimeseriesStep`
- 환경: `_raw/stack-bringup.md` 실효 배선 확인분 위에서 검증. backend 컨테이너 실효 env 재실측 —
  `SPRING_PROFILES_ACTIVE=local` · `ENV` **미설정** · `VLM_SERVICE_URL=http://klid-mock-server:9400` ·
  `VLM_ALLOW_INSECURE_URL=true` · `VLM_CLIENT_ENABLED=true` · `KPST_DEID_BASE_URL=http://klid-mock-server:9400` ·
  `KPST_DEID_ENABLED=true` · `DEIDENTIFY_MOCK_MODE=false`
- 실동작 도구: ①REVIEWER JWT 발급 후 `/api/actuator/health` 상세 조회(`show-details: when-authorized`)
  ②`docker pause klid-mock-server` 장애 주입 ③운영 이미지(`klid-backend:latest`)로 **일회용 컨테이너 기동 프로브**
  (klid-net 참여, `SPRING_QUARTZ_AUTO_STARTUP=false`, 부팅 완주가 예상되는 케이스는 DB 호스트를 무효화해 공유 데이터 무접촉)
  ④mock-server / backend 컨테이너 로그 ⑤PostgreSQL(`klid_system`) 직접 조회
- **프로덕션 코드·설정 무수정.** 카탈로그 정정만 담당 라인범위 안에서 수행(5건).

---

## 판정 요약

| 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| 13 | 12 | 0 | 1 | 0 | 0 | 0 |

---

## 케이스별 판정

| ID | 판정 | 근거 확인 |
|----|:--:|----------|
| TC-AIMOCK-34 | PASS | [실동작] REVIEWER 토큰으로 `/api/actuator/health` → `"deidentifyHealth":{"status":"UP","details":{"service":"deidentify","mode":"kpst"}}`. 동시에 mock-server 로그에 backend(172.20.0.5)발 **`"GET / HTTP/1.1" 200 OK`** 기록 — `/health` 가 아니라 **루트(`/`)** 를 핑함이 실증됨. 코드 `DeidentifyHealthIndicator.java:95-100`(`.uri("/")`) · UP 반환 `:101-104`. 테스트 `DeidentifyHealthIndicatorTest.java:88 실모드_핑은_벤더가_제공하는_루트경로로_요청한다`(baseline 실패 0건) |
| TC-AIMOCK-35 | PASS | [정적] `DeidentifyHealthIndicator.java:78-83` — `if (mockMode)` 가 **분기 최상단**이라 `kpstWebClient` 존재 여부와 무관하게 핑 없이 `UP/mode=mock`. 테스트 `DeidentifyHealthIndicatorTest.java:58 mock모드_활성시_외부핑없이_UP_mock` + `:184 kpst비활성이면 WebClient빈 없이도 기동`. 실동작 미확인 사유: 실효 env 가 `DEIDENTIFY_MOCK_MODE=false` 이고 이를 켜려면 공유 스택 재기동이 필요(§10 코드/설정 무수정 준수) |
| TC-AIMOCK-36 | PASS | [정적] `:85-91` — `!kpstEnabled \|\| kpstWebClient == null` → `DOWN` + `mode=unconfigured` + `error=NoDeidentifyPathConfigured`(fail-closed, 핑 없음). 테스트 `DeidentifyHealthIndicatorTest.java:121 mock도_KPST위탁도_아니면_핑없이_DOWN_설정오류` |
| TC-AIMOCK-37 | PASS | [실동작] `docker pause klid-mock-server` 로 장애 주입 → `/api/actuator/health` **HTTP 503**, `deidentifyHealth:{"status":"DOWN","details":{"service":"deidentify","mode":"kpst","error":"ReactiveException"}}`. **예외 클래스 simpleName 만** 노출되고 스택트레이스·주소·내부 경로 없음(CWE-209 충족, `:105-112`). `unpause` 후 즉시 UP 복귀 확인. 원인 구분 정보가 뭉개지는 관측성 잔여는 G-ISSUE-25 |
| TC-AIMOCK-38 | PASS | [실동작] 운영 backend 기동 로그에 `[VlmUrl] 평문/사설 URL 완화가 활성화됨 — 개발 프로파일 전용. property=vlm.client.allow-insecure-url` + `[ExternalUrl] 평문 HTTP 전송 — 내부망 격리 전제. property=vlm.client.url host=klid-mock-server:9400`. **평문 http + 컨테이너 사설IP(172.20.0.x) URL 로 기동이 성공했다는 사실 자체**가 relaxed(`ExternalUrlPolicy.internalNetwork`, `ProfileGatedUrlPolicy.java:70,133-136`) 적용의 증거 — strict 였다면 빈 생성 실패로 기동 불가. 이후 describe 왕복 실성립(TC-AIMOCK-45) |
| TC-AIMOCK-39 | PASS | [실동작] 일회용 컨테이너 프로브 **2축 모두 fail-closed 실증**. ①ENV 축: 프로파일은 `local` 그대로 두고 `ENV=prd` 만 추가 → `exit=1`, `IllegalStateException: vlm.client.allow-insecure-url=true 는 [local, dev] 프로파일에서만 허용됩니다 (현재 활성 프로파일=[local], ENV=prd)` (`ProfileGatedUrlPolicy.verifyRelaxationScope(ProfileGatedUrlPolicy.java:93)`). ②프로파일 혼합 축: `SPRING_PROFILES_ACTIVE=local,stg`(ENV 미설정) → `exit=1`, `... (현재 활성 프로파일=[local, stg], ENV=null)`. 두 경우 모두 `vlmUrlPolicy` 빈 init 실패가 `vlmWebClient→vlmClient→vlmTimeseriesStep→…` 로 전파되어 **컨텍스트 refresh 자체가 취소**됨 |
| TC-AIMOCK-40 | PARTIAL | [실동작] 케이스가 명시한 대역은 실제로 차단됨 — relaxed 유지 상태에서 `VLM_SERVICE_URL=http://169.254.169.254:9400` → `exit=1`, `IllegalStateException: vlm.client.url 이 링크로컬/클라우드 메타데이터 대역을 가리킵니다: 169.254.169.254 → 169.254.169.254. 내부망(개발) 정책에서도 차단됩니다`(`ExternalUrlPolicy.java:143-153`). **그러나 같은 정책이 선언한 목적("IMDS 는 어떤 환경에서도 정상 위탁 대상이 아니다", `ExternalUrlPolicy.java:29-37` 보안 메모)을 우회하는 대역이 relaxed·strict 양쪽에서 통과** — AWS IPv6 IMDS `fd00:ec2::254`(ULA, 링크로컬 아님) 와 CGNAT `100.100.100.200`(100.64/10) 이 기동을 통과함을 프로브로 실증. 방어 불완전 → PARTIAL. 상세 **G-ISSUE-21** |
| TC-AIMOCK-41 | PASS | [실동작] 양방향 실증. ①relaxed + 미해석 호스트: `VLM_SERVICE_URL=http://no-such-host-xyzzy:9400` → `[ExternalUrl] 평문 HTTP 전송 … host=no-such-host-xyzzy:9400` WARN 후 `Started AuthoringApplication` (= `check()` 가 끝까지 통과. `resolveQuietly` 가 `null` 반환 시 조용히 통과, `ExternalUrlPolicy.java:144-147,156-166`). ②strict(완화 플래그 off) + `https://no-such-host-xyzzy` → `exit=1`, `IllegalStateException: vlm.client.url 호스트를 해석할 수 없습니다: no-such-host-xyzzy`(`:171-175`) |
| TC-AIMOCK-42 | PASS | [실동작+정적] 정상 경로 실동작: mock-server `[MOCK][VLM] describe accepted request_id=3aa3c19b-…` ↔ backend `[Batch][VlmTimeseries] accepted rawSn=115 request_id=3aa3c19b-… status=accepted`(`VlmSubmitOutcomeRecorder`) — `POST /v1/videovlm/describe` 경로·request_id echo 일치·status="accepted" 3요소가 실왕복으로 성립. 검증 실패 분기는 `VlmClient.java:154-169`(echo 불일치·status≠accepted → `CustomException(EXTERNAL_API_ERROR)`), 테스트 `VlmClientTest.java:141 requestIdEchoMismatchRejected`·`:156 nonAcceptedStatusRejected`(baseline 실패 0건). 실동작 부정 재현은 목업에 응답 조작 훅이 없어(`mock-server/app/routers/vlm.py:200-236` — echo 는 항상 요청값) 미수행 |
| TC-AIMOCK-43 | PASS | [정적] `VlmClient.java:106` `.onStatus(HttpStatusCode::is4xxClientError, this::toNonRetryable4xx)` 가 `.bodyToMono` **앞**에 있어 4xx 는 본문 소비 후 `NonRetryableExternalException`(`:121-127`, 상태코드만 기록 — 응답 본문 미노출). 설정 실측: `application.yml:538-545` circuitbreaker `vlmClient.ignore-exceptions` + **`:612-618` retry `vlmClient.ignore-exceptions`** 양쪽에 동일 예외 등록 — 재시도·서킷 집계 모두 제외. 테스트 `VlmClientTest.java:221 badRequest400NotRetried`·`:236 unprocessable422NotRetried`. ※ 카탈로그 근거가 circuitbreaker 만 가리키고 retry 쪽을 빠뜨려 정정함 |
| TC-AIMOCK-44 | PASS | [실동작] 45s 계층 부재 재확인 — `grep -rn BLOCK_TIMEOUT backend/src/main/java` 결과가 `ControlNotifyClient.java:52`(15s) + `ControlNotifyService` 4개 호출부뿐, **VLM 경로 0건**. 단일 타임아웃은 `VlmClient.java:71,76,108`(`timeout-seconds:10` → `.timeout(timeout)`). 논블로킹 제출은 `VlmTimeseriesStep.java:366 .subscribe(...)` → `:381 return VlmTimeseriesResponse.submitted(requestId)`. **무신호 회수도 실동작 확인** — backend 로그 `[Vlm][Reclaim] pending VLM submit reclaimed=2 ackWindowMin=30 callbackWindowMin=360`, DB `ls_batch_proc_log` 에 `VLM/SKIPPED/"VLM describe 수락 응답·콜백 미수신 — 미결 회수 후 재개"` **5건**. 두 창의 판정 근거인 원장 전이도 실재(`PersistentWebhookIdempotencyLedger.recordAckReceived:78-84` 조건부 UPDATE, `ls_webhook_idempotency` 에 ISSUED/PROCESSED/FAILED 행 관측) |
| TC-AIMOCK-45 | PASS | [실동작] local 실왕복: mock-server `POST /v1/videovlm/describe 200 OK` ↔ backend `accepted rawSn=115` ↔ 콜백 수신 `[Webhook][Vlm] result applied request_id=3aa3c19b-… rawSn=115 new=1 markingsTransitioned=1`. **self-fill 아님**(값이 외부 콜백에서 옴). 공통 기본 SKIPPED 는 `VlmClient.java:94-97` + `application.yml:704 enabled: ${VLM_CLIENT_ENABLED:false}`, local 기본 true 는 `application-local.yml:150`. 테스트 `VlmClientTest.java:171 enabledFalseReturnsSkippedWithoutCall` |
| TC-AIMOCK-46 | PASS | [실동작] backend 로그 `[Batch][VlmTimeseries] withheld — deident report open rawSn=105`, DB `ls_batch_proc_log` 에 `VLM/SKIPPED/"비식별 누락 신고 구간 — VLM 위탁 보류(재비식별 대기)"` **10건** 적재. 해당 시각 mock-server 인바운드 describe 0건(외부 호출 없음). 게이트가 `VlmTimeseriesStep.java:303-307` 로 **경로 해석(`:309 resolveDeidentifiedPath`) 직전**·`doSubmit` 내부(오케스트레이터 아님)에 위치해 public 진입점 우회 불가. 재위탁 배선 `VlmWithheldResumeRunnerTest.java:81,93,109,119,137,153`(6케이스, baseline 실패 0건) |

---

## 이전 회차(2026-08-02 2차) 이슈 대조

| 이전 이슈 | 상태 | 근거 |
|---|---|---|
| **G-ISSUE-61** TC-AIMOCK-44 "45s+10s 2계층" 전제가 코드에 없음 | **✅ 해소** | 카탈로그 기대결과가 논블로킹 제출 서술로 교체됨(현 TC-AIMOCK-44). 코드 실측도 일치 — `BLOCK_TIMEOUT` 은 `ControlNotifyClient` 전용 15s 로만 잔존 |
| **G-ISSUE-63** 근거 드리프트 4건 중 G-10 소관 3건 | **🔶 부분 해소 후 재드리프트** | TC-AIMOCK-46(`:113,303-306`)은 정정값이 유효. **TC-AIMOCK-43**(정정값 `application.yml:532-539` → 실제 `538-545`)·**TC-AIMOCK-45**(정정값 `application-local.yml:147` → 실제 `150`)는 그 사이 코드 라인이 다시 밀려 재드리프트. 이번 회차에 재정정 |
| **G-ISSUE-65** TC-AIMOCK-40 외부 URL 대역 검사가 첫 해석 주소만 검사 | **❌ 미해소(이월)** | `ExternalUrlPolicy.java:162 return InetAddress.getByName(normalized);`(relaxed) · `:172 addr = InetAddress.getByName(host);`(strict) 그대로. `getAllByName` 미도입 → G-ISSUE-22 로 이월 |

---

## 이슈

### [G-ISSUE-21] TC-AIMOCK-40 — 클라우드 메타데이터/내부 대역 차단이 IPv4 링크로컬에만 걸려 있어 AWS IPv6 IMDS·CGNAT 대역이 strict 정책에서도 통과한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `ExternalUrlPolicy` 의 선언된 목적은 두 가지다 — ①relaxed 에서도 **"IMDS 는 어떤 환경에서도 정상 위탁 대상이 될 수 없다"** (`ExternalUrlPolicy.java:29-37` 보안 메모, `:136-137` javadoc) ②strict 에서 **loopback/사설/링크로컬/메타데이터 대역 전면 차단**(CWE-918 SSRF, `:168`). 외부 연동 base-url 이 클라우드 메타데이터 엔드포인트를 가리키면 어느 정책에서도 기동이 거부돼야 한다.
- **현재 동작(이슈 내용)**: 두 판정 모두 **Java `InetAddress` 의 IPv4 중심 술어**에만 의존해, IPv4 링크로컬(169.254/16)과 IPv6 링크로컬(fe80::/10)만 잡는다.
  ```java
  // ExternalUrlPolicy.java:148-152 (relaxed)
  if (resolved.isLinkLocalAddress() || resolved.getHostAddress().startsWith("169.254.")) { throw ... }
  // ExternalUrlPolicy.java:176-187 (strict)
  if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
      || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) { throw ... }
  String ip = addr.getHostAddress();
  if (ip.startsWith("169.254.")) { throw ... }
  ```
  `Inet6Address.isSiteLocalAddress()` 는 **deprecated 된 `fec0::/10`** 만 판정하고 실제 IPv6 사설 대역인 **ULA `fc00::/7` 은 판정하지 않는다.** `isSiteLocalAddress()`(IPv4)도 10/8·172.16/12·192.168/16 만 보고 **CGNAT `100.64/10`** 은 보지 않는다. 그 결과:
  - **AWS IPv6 IMDS `fd00:ec2::254`** (ULA) — relaxed·strict 양쪽 통과
  - **`100.100.100.200`** (Alibaba Cloud 메타데이터 서버, CGNAT 대역) — strict 통과
  [실동작] 운영 이미지 프로브 3회(모두 DB 무효화로 공유 데이터 무접촉):
  ```
  relaxed + VLM_SERVICE_URL=http://[fd00:ec2::254]:9400
    → [ExternalUrl] 평문 HTTP 전송 … host=[fd00:ec2::254]:9400 → Started AuthoringApplication (통과)
  strict(VLM_ALLOW_INSECURE_URL=false) + https://[fd00:ec2::254]
    → 거부 로그 없음 → Started AuthoringApplication (통과)
  strict + https://100.100.100.200
    → 거부 로그 없음 → Started AuthoringApplication (통과)
  대조군: relaxed + http://169.254.169.254:9400 → exit=1 "링크로컬/클라우드 메타데이터 대역을 가리킵니다" (차단)
  ```
- **재현/확인 경로**:
  ```bash
  # 실행 중 backend 의 env 를 복제해 URL 만 바꾼 일회용 컨테이너로 재현(공유 DB 무접촉)
  docker inspect klid-backend --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -v '^$' > /tmp/be.env
  printf 'SPRING_QUARTZ_AUTO_STARTUP=false\nCONTROL_DB_HOST=no-such-db-host\nPORTAL_DB_HOST=no-such-db-host\n' >> /tmp/be.env
  sed -i '' 's|^VLM_SERVICE_URL=.*|VLM_SERVICE_URL=https://[fd00:ec2::254]|' /tmp/be.env
  sed -i '' 's|^VLM_ALLOW_INSECURE_URL=.*|VLM_ALLOW_INSECURE_URL=false|' /tmp/be.env
  docker run --rm --name klid-ssrf-probe --network klid-net --env-file /tmp/be.env klid-backend:latest 2>&1 | grep -E "IllegalState|Started Authoring"
  ```
- **영향**: **CWE-918 (SSRF)**. 트리거 조건은 "운영자가 base-url 환경변수를 그 값으로 지정" 이라 신뢰 경계 안쪽이고 사용자 입력 경유 경로가 아니므로 즉시 착취 가능한 취약점은 아니다. 다만 ①이 정책의 존재 이유가 **설정 사고(오타·복붙·공격자 제어 설정 주입)로 IMDS 를 치는 것을 기동 시점에 막는 것**인데 IPv6 환경의 AWS IMDS 에 대해서는 그 방어가 **성립하지 않는다**(자격증명 탈취로 직결되는 대역) ②KPST·증강·VLM 3개 연동이 같은 판정기를 공유하므로 갭도 3개 연동에 공통이다 ③IPv6 듀얼스택 배포로 전환하면 노출면이 그대로 활성화된다.
- **수정 방향(제안)**: `ExternalUrlPolicy` 의 대역 판정을 **명시적 CIDR 매칭 헬퍼**로 교체한다 — IPv4: `169.254/16`·`127/8`·`10/8`·`172.16/12`·`192.168/16`·**`100.64/10`**·`0/8`, IPv6: `::1`·`fe80::/10`·**`fc00::/7`**·`fec0::/10`(+ IPv4-mapped 는 언랩 후 IPv4 규칙 적용). relaxed 는 그중 **메타데이터·링크로컬 집합**(`169.254/16`, `fe80::/10`, `fd00:ec2::254`)만, strict 는 전체를 거부. `Inet6Address.isSiteLocalAddress()` 에 의존하지 말 것(deprecated 대역만 판정). 회귀 가드는 `VlmUrlPolicyTest.metadataRangeRejectedEvenWhenRelaxed`(현 `:160`) 옆에 IPv6 ULA·CGNAT 케이스를 추가.

### [G-ISSUE-22] TC-AIMOCK-40/41 — (2차 G-ISSUE-65 이월) 호스트의 첫 번째 해석 주소만 대역 검사
- **심각도**: LOW
- **기대 동작(기대효과)**: 호스트명이 복수 주소로 해석될 때 **하나라도 위험 대역이면 거부**되어야 한다. 실제 커넥션이 어느 주소로 갈지는 JDK/OS 의 주소 선택에 달려 있어, 첫 주소만 검사하면 검사 대상과 접속 대상이 달라질 수 있다.
- **현재 동작(이슈 내용)**: 2차 지적 이후 코드 변경 없음.
  ```java
  // ExternalUrlPolicy.java:161-165 (relaxed)
  try { return InetAddress.getByName(normalized); } catch (UnknownHostException | SecurityException e) { return null; }
  // ExternalUrlPolicy.java:170-175 (strict)
  try { addr = InetAddress.getByName(host); } catch (UnknownHostException e) { throw new IllegalStateException(...); }
  ```
  `:148`·`:176-187` 이 그 **단일** `addr` 만 판정한다. 부수적으로 TC-AIMOCK-41 에서 실증한 "relaxed 는 해석 실패를 통과" 규약과 결합하면, 기동 시점에 NXDOMAIN 이던 호스트가 이후 IMDS 로 해석돼도 재검증 지점이 없다(DNS rebinding TOCTOU — 현 설계가 방어 대상으로 선언하지는 않음).
- **재현/확인 경로**: `grep -n "getByName" backend/src/main/java/kr/co/cudo/authoring/common/config/ExternalUrlPolicy.java` → `:162`, `:172` (`getAllByName` 0건).
- **영향**: CWE-918 잔여 표면. 트리거 조건이 설정값(환경변수)이라 신뢰 경계 안쪽이며 실착취 가능성은 낮다.
- **수정 방향(제안)**: `InetAddress.getAllByName(host)` 로 바꿔 해석된 전 주소에 대역 검사를 수행(하나라도 위험 대역이면 거부). relaxed 의 "해석 실패는 통과" 규약은 `getAllByName` 도 `UnknownHostException` 을 던지므로 동일 catch 로 유지 가능. **G-ISSUE-21 과 같은 메서드를 고치는 작업이므로 한 번에 처리할 것.**

### [G-ISSUE-23] TC-AIMOCK-38/39/43/44/45 — 근거 `file:line` 드리프트 5건 (카탈로그 정정 완료)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 `file:line` 은 판정자가 즉시 대조할 수 있는 실제 위치여야 한다. 어긋나면 엉뚱한 라인을 읽고 거짓 PASS/FAIL 이 난다.
- **현재 동작(이슈 내용)**: 아래 5건이 실제 위치와 불일치. **2차에서 정정한 2건(TC-AIMOCK-43·45)이 그 사이 코드 라인 이동으로 재드리프트**한 것이 포함된다.
  | ID | 카탈로그(정정 전) | 실제 위치 |
  |---|---|---|
  | TC-AIMOCK-38 | `ProfileGatedUrlPolicy.java:121-133,145-151` | `:124-136`(`check`/`policy`) · `:148-151`(`profileAllowsRelaxation`). `:121-123` 은 javadoc |
  | TC-AIMOCK-39 | `ProfileGatedUrlPolicy.java:78-91` | `:81-94`(`verifyRelaxationScope`, throw 는 `:90-93` — 프로브 스택트레이스가 `ProfileGatedUrlPolicy.java:93` 으로 확정). 판정 규칙 본체는 `DeployedEnvironmentDetector.java:63-70` |
  | TC-AIMOCK-43 | `application.yml:532-539` (2차 정정값) | circuitbreaker `vlmClient` 는 `:538-545`, **retry `vlmClient.ignore-exceptions` 는 `:612-618`**(2차 정정값이 retry 쪽을 누락) |
  | TC-AIMOCK-44 | `VlmTimeseriesStep.java` docstring `340-357` / `VlmSubmitPendingSweeper.java:118-124` | 폐지 주석은 `:346-357`(`:340-341` 은 `VlmTimeseriesRequest req = …` 코드). 스위퍼 두 임계 `@Value` 는 `:121-122`, cutoff 적용은 `:192-197` |
  | TC-AIMOCK-45 | `application-local.yml:147` (2차 정정값) | `:150 enabled: ${VLM_CLIENT_ENABLED:true}` (`:151` url, `:152` allow-insecure-url). 공통 기본값은 `application.yml:704` |
  정확했던 근거(대조 완료): `DeidentifyHealthIndicator.java:77-83`/`84-91`/`92-104`/`105-112`, `ExternalUrlPolicy.java:80-82`/`143-153`/`169-181`, `VlmClient.java:71-76`/`88-112`/`94-97`/`106`/`121-127`/`154-169`, `VlmTimeseriesStep.java:60-63`/`303-307`.
- **재현/확인 경로**: `sed -n '81,94p;124,136p;148,151p' backend/src/main/java/kr/co/cudo/authoring/common/config/ProfileGatedUrlPolicy.java` · `sed -n '538,545p;612,618p;704p' backend/src/main/resources/application.yml` · `sed -n '150,152p' backend/src/main/resources/application-local.yml` · `sed -n '121,122p;192,197p' backend/src/main/java/kr/co/cudo/authoring/batch/vlm/VlmSubmitPendingSweeper.java`
- **영향**: 검증 효율·신뢰도 저하(카탈로그 자체의 정합성 결함). 제품 동작 영향 없음. **2회 연속 재드리프트가 관측됐다** — 절대 라인 표기는 코드가 움직일 때마다 낡는다.
- **수정 방향(제안)**: 이번 회차에 5건 모두 카탈로그에 **직접 정정 반영 완료**(담당 라인범위 내 Edit). 재발 방지로는 근거 컬럼에 **라인 대신 심볼명**(`ProfileGatedUrlPolicy#verifyRelaxationScope`, `application.yml resilience4j.retry.instances.vlmClient`)을 병기하는 표기 규약을 검토할 것.

### [G-ISSUE-24] TC-AIMOCK-34 — 벤더 루트 핑이 2xx 가 아니면 전부 DOWN 이라, 실 KPST 루트가 401/403/404 를 주면 상시 DOWN 오탐이 재발한다
- **심각도**: LOW
- **기대 동작(기대효과)**: 이 인디케이터를 KPST 축으로 옮긴 취지 자체가 **"헬스가 위탁 대상이 아닌 주소를 쳐서 DOWN 오탐이 났다"는 사고의 재발 방지**다(`DeidentifyHealthIndicator.java:16-27` javadoc, cudo_246 실측). 헬스는 "위탁 경로가 살아있는가"를 판정해야지 "루트 경로에 200 을 주는 앱인가"를 판정해서는 안 된다.
- **현재 동작(이슈 내용)**: 판정이 `retrieve().toBodilessEntity()` 라 **모든 non-2xx 응답이 예외 → DOWN** 이다.
  ```java
  // DeidentifyHealthIndicator.java:95-104
  kpstWebClient.get().uri("/").retrieve().toBodilessEntity().timeout(PING_TIMEOUT).block();
  return Health.up()....withDetail("mode", "kpst").build();
  ```
  javadoc 은 `:24-27` 에서 "벤더가 보장하는 루트(`/`)만 친다" 고 전제하지만, **루트가 헬스 계약으로 명시된 것이 아니다**(KPST 규격에 헬스 엔드포인트가 없다는 것이 이 설계의 출발점). mock-server 는 루트가 200 이라 로컬에서는 가려진다(실측 `curl localhost:9400/` → 200). 실벤더 루트가 인증 요구(401/403)나 라우트 부재(404)를 반환하면 **비식별 위탁은 정상인데 헬스만 상시 DOWN** 이 되고, 집계 `/actuator/health` 가 DOWN 이면 배포 readiness·모니터링 알림이 통째로 물린다.
- **재현/확인 경로**: 로컬로는 재현 불가(목업 루트가 200). 실벤더 대조 필요. 코드상 판정 분기는 위 인용부이며, 로컬에서 non-2xx 시 DOWN 됨은 `docker pause` 프로브(TC-AIMOCK-37)로 간접 확인됨.
- **영향**: 가용성 오탐(운영). 보안 영향 없음. UNCERTAINTIES #13 의 "IntelliVIX/KPST 실서버 대조 미완" 과 같은 뿌리 — 실벤더 응답 계약 확인 전까지 확정 불가.
- **수정 방향(제안)**: ①KPST 벤더에 **루트 응답 계약(상태코드)** 을 서면 확인하고 그 값을 javadoc·케이스에 명시, 또는 ②`.exchangeToMono(...)` 로 바꿔 **"응답을 받았다"(=TCP+HTTP 왕복 성립)** 를 UP 조건으로 삼고 5xx·연결 실패만 DOWN 으로 판정(4xx 는 `details` 에 상태코드만 남기고 UP). ②가 "위탁 경로 생존" 이라는 헬스의 목적에 더 맞는다. 어느 쪽이든 **실벤더 확인 없이 임의 확정하지 말 것.**

### [G-ISSUE-25] TC-AIMOCK-37 — DOWN 상세의 `error` 가 Reactor 래퍼 클래스명으로 뭉개져 장애 원인을 구분할 수 없다
- **심각도**: LOW
- **기대 동작(기대효과)**: 헬스의 `error` 디테일은 CWE-209 를 지키면서(스택트레이스·주소·내부경로 미노출) **운영자가 1차 분류를 할 수 있어야** 한다 — 타임아웃인지, DNS 실패인지, 연결 거부인지, 인증 실패인지.
- **현재 동작(이슈 내용)**: `.block()` 이 checked 예외를 `reactor.core.Exceptions$ReactiveException` 으로 감싸므로 `e.getClass().getSimpleName()` 이 **원인이 아니라 래퍼 이름**을 낸다.
  ```java
  // DeidentifyHealthIndicator.java:105-112
  } catch (Exception e) {
      return Health.down()....withDetail("error", e.getClass().getSimpleName()).build();
  ```
  [실동작] `docker pause klid-mock-server` → `{"status":"DOWN","details":{"service":"deidentify","mode":"kpst","error":"ReactiveException"}}`. 동일 현상이 `controlNotifyHealth` 에서도 관측됨(`"error":"ReactiveException"`). 실제 원인(`TimeoutException`)은 응답 어디에도 없다.
- **재현/확인 경로**:
  ```bash
  docker pause klid-mock-server
  curl -s -H "Authorization: Bearer <REVIEWER JWT>" http://localhost:18081/api/actuator/health   # → error: ReactiveException
  docker unpause klid-mock-server
  ```
- **영향**: 관측성 저하(운영 트리아지 지연). 케이스의 기대결과("예외 simpleName 만, 스택트레이스 미노출")는 충족하므로 TC-AIMOCK-37 판정 자체는 PASS. 보안 영향 없음(오히려 과소 노출).
- **수정 방향(제안)**: `Exceptions.unwrap(e)` 로 원인을 벗긴 뒤 그 클래스의 simpleName 을 노출하거나, `WebClientResponseException` 이면 상태코드만 함께 남긴다(`error=TimeoutException` / `error=WebClientResponseException(404)`). 메시지 본문·주소는 계속 노출하지 않는다. `controlNotifyHealth` 등 동일 패턴 인디케이터도 함께 정비.

---

## 카탈로그 정정 내역 (담당 라인범위 내 Edit — 5건)

| 케이스 | 정정 전 | 정정 후 |
|---|---|---|
| TC-AIMOCK-38 | `ProfileGatedUrlPolicy.java:121-133,145-151` | `ProfileGatedUrlPolicy.java:124-136(check/policy),148-151(profileAllowsRelaxation)` |
| TC-AIMOCK-39 | `ProfileGatedUrlPolicy.java:78-91` | `ProfileGatedUrlPolicy.java:81-94(throw 90-93) / DeployedEnvironmentDetector.java:63-70` |
| TC-AIMOCK-43 | `application.yml:532-539` | `application.yml:538-545(circuitbreaker.vlmClient),612-618(retry.vlmClient)` |
| TC-AIMOCK-44 | `…docstring 340-357…` / `VlmSubmitPendingSweeper.java:118-124` | `…주석 346-357 · .subscribe 366 · submitted 반환 381…` / `VlmSubmitPendingSweeper.java:121-122(두 임계 @Value),192-197(두 cutoff 적용)` |
| TC-AIMOCK-45 | `application-local.yml:147` | `application.yml:704(공통 기본 false) / application-local.yml:150(local 기본 true)` |

기대결과·전제 문구는 이번 회차 실측과 모두 일치해 **문구 정정 0건**(TC-AIMOCK-44 는 2차 G-ISSUE-61 반영분이 이미 정확).

---

## 환경 조작·복원 기록 (다른 검증 에이전트 영향 여부)

| 조작 | 지속 시간 | 복원 확인 |
|---|---|---|
| `docker pause klid-mock-server` → `unpause` | 약 3초 | 직후 `/api/actuator/health` → `UP`(deidentify `mode=kpst`), `curl localhost:9400/health` → 200 |
| 일회용 backend 프로브 컨테이너 7개(`klid-guard-test`,`klid-p39b`,`klid-p40`,`klid-p40a/b/c`,`klid-p41a/b`) | 각 40~60초 | 전부 `docker rm -f` 로 제거 — `docker ps -a` 에 잔존 0건. Quartz 비활성(`SPRING_QUARTZ_AUTO_STARTUP=false`), 부팅 완주 예상 케이스는 DB 호스트 무효화로 공유 데이터 무접촉 |
| 본 스택 최종 상태 | — | `klid-backend/frontend/ai-server/mock-server/postgres` 전부 healthy, `/api/actuator/health` UP |

프로덕션 코드·설정·테스트 파일 수정 0건. 빌드/테스트 실행 0건(자동테스트 통과 여부는 `_raw/test-baseline.md` 대조).

---

# ===== _raw/G-part3.md 원문 =====

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

---

# ===== _raw/G-part4.md 원문 =====

# G클러스터 part4 — G-5(VLM verify-objects) + G-6(계약 정합/공통 인프라) — 3차 회차

- 담당 범위: `docs/test-cases/G-ai-server.md` 115~162행 (G-5 전체 14건 `TC-AIVLM-01~14` + G-6 전체 20건 `TC-AICONTRACT-01~12`·`TC-AIINFRA-01~08`) = **34건**
- 이슈 ID: `G-ISSUE-61`부터
- 환경: `_raw/stack-bringup.md`(3차) 기준 — ai-server(:19300, 컨테이너 내부 9300) 직접 curl, `AI_MOCK_MODE=false`(실추론 경로), YOLO weights 미탑재는 내 범위(VLM/계약) 영향 없음. VLM 모델(`get_vlm_model()`)은 실구현 자체가 없어 mock_reason이 항상 `weights_missing`으로 관측(코드상 정상 — `vlm_loader.py` "real load not implemented yet" 주석과 일치).
- 빌드/테스트 미실행(지시 준수). 실동작은 `curl localhost:19300` 직접 호출, 정적 대조는 Read.

## 판정 집계

| 판정 | 건수 |
|---|---|
| PASS | 34 |
| FAIL | 0 |
| PARTIAL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **34** |

## G-5. VLM — ai-server 자체 `/infer/vlm/verify-objects`

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-AIVLM-01 | PASS | [실동작] `expected_label=person` → `{"verified":true,"confidence":0.92,"mock":true,"source":"mock","mock_reason":"weights_missing"}`. `routers/vlm.py:89-104`(`_mock_verify`) 라인 정확 |
| TC-AIVLM-02 | PASS | [실동작] `expected_label=unicorn` → `verified:false, confidence:0.18`. 라인 일치 |
| TC-AIVLM-03 | PASS | [실동작]+[정적] 현재 배선(`AI_MOCK_MODE=false`+VLM 미구현)에서 `mock_reason=weights_missing` 관측 — 기대 집합 `{env_mock,weights_missing,not_implemented}` 내. `routers/vlm.py:38-69`(`verify_objects`+`_should_mock`+`_mock_reason`) 라인 정확 |
| TC-AIVLM-04 | PASS | [실동작] `expected_label=PERSON`(대문자) → `verified:true`. `:93` `obj.expected_label.lower()` 라인 정확 |
| TC-AIVLM-05 | PASS | [실동작] `objects:[]` → HTTP 400. `schemas.py:251` `min_length=1` 라인 정확 |
| TC-AIVLM-06 | PASS | [실동작] `bbox:[1,2,3]`(3원소) → 400. `schemas.py:244` 라인 정확 |
| TC-AIVLM-07 | PASS | [실동작] `obj_id` 누락 → 400. `schemas.py:239-244`(`ObjectToVerify`) 라인 정확 |
| TC-AIVLM-08 | PASS | [실동작] `image_b64:"!!!notb64"` → `400 {"error_code":"INVALID_IMAGE","message":"base64 디코드 실패"}`. `routers/vlm.py:45` 라인 정확 |
| TC-AIVLM-09 | PASS | [실동작] 11MB raw(base64 15.4MB, `max_image_size_mb` 기본 10) → HTTP 413. `image_utils.py:42-43`(`if len(raw) > max_bytes: raise ImageTooLargeError`) 라인 정확 |
| TC-AIVLM-10 | PASS | [실동작]+[정적] `objects[0].extra_field=1` → 400 / 최상위 `conf=0.5` → 400. `schemas.py:240`(`ObjectToVerify.model_config`)·`:248`(`VlmVerifyRequest.model_config`) **둘 다 `extra="forbid"` 라인 정확** — ⚠ 2차 회차 G-ISSUE-63이 지적한 구 드리프트(`204,211`, 응답 모델 오귀속)는 **이미 해소됨**(현재 카탈로그가 `240,248`로 이미 정정돼 있고 실제 코드와 일치) |
| TC-AIVLM-11 | PASS | [실동작] 3개 객체(`dog`→known,`zzz`→unknown,`truck`→known) 요청 순서 그대로 응답. `routers/vlm.py:91-101` 라인 정확 |
| TC-AIVLM-12 | PASS | [실동작] `POST /infer/vlm/meta`·`/infer/vlm/video-meta` 둘 다 HTTP 404. `test_vlm.py:52`(`test_vlm_router_video_meta_endpoint_removed`) 라인 정확 |
| TC-AIVLM-13 | PASS | [실동작] 컨테이너 기동 이후 verify-objects를 9회 이상 호출했으나 `docker logs klid-ai-server \| grep -c "returning mock verify-objects"` = **1**(WARN 1회만). `routers/vlm.py:72-80`(`_warn_mock_once`) 라인 정확 |
| TC-AIVLM-14 | PASS | [정적] `AiServerClient.java:91` `.uri("/infer/vlm/verify-objects")` vs `VlmClient.java:53` `DESCRIBE_PATH="/v1/videovlm/describe"` + `:67` 생성자(별도 클라이언트) — 경로·클라이언트 완전 분리 확인. 단 **G-ISSUE-61**(아래) 참조 — 이 분리 자체는 사실이나 `verifyObjects` 호출부가 프로덕션에 없어 케이스 전제("BE가 실제 호출")가 도달 불가 경로임 |

## G-6. 계약 정합 / 공통 인프라

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-AICONTRACT-01 | PASS | [정적] `detector_backend.py:67-85` `COCO_ID2LABEL` 80개, key 0~79 연속·중복 없음(코드 직접 카운트 확인). 라인 정확 |
| TC-AICONTRACT-02 | PASS | [정적] `CocoClasses.java:25-43`(`LABELS`, 80개)와 `detector_backend.py:67-85` **문자열·순서 완전 일치**(person…toothbrush) 직접 대조 확인. `CocoClassesDriftTest.java:48-72`(`matchesAiServerSource`) 라인 정확(48 `@Test`~72 assertion, 73 닫는 괄호) |
| TC-AICONTRACT-03 | PASS | [정적] `coco_label_from_id(0)=person, (2)=car, (79)=toothbrush` 코드 직접 확인. `detector_backend.py:88-96` 라인 정확 |
| TC-AICONTRACT-04 | PASS | [정적] `id2label` 우선 → 미스 시 `COCO_ID2LABEL.get(class_id, str(class_id))` 폴백 확인. 동일 라인 |
| TC-AICONTRACT-05 | PASS | [정적] `coco_id_from_label`: 알려진 라벨→COCO id, 숫자문자열→역파싱, 미지 라벨→해시 기반 결정적 id(`_UNKNOWN_LABEL_ID_BASE=10000` 오프셋, COCO 0~79와 비충돌). `detector_backend.py:107-124` 라인 정확 |
| TC-AICONTRACT-06 | PASS | [정적] `YoloResponse`(`schemas.py:68-88`) 필드 `detections/mock/source/mock_reason/success/message/error_code` 정확 일치. 라인 정확 |
| TC-AICONTRACT-07 | PASS | [실동작] `POST /infer/yolo/predict`→400(라우팅 O, 바디 검증 실패)·`/infer/sam2/segment`→400·`/infer/vlm/verify-objects`→400 vs `/infer/foo/bar`→404(라우팅 자체 부재)로 대조 확인. `main.py:65-67`(`include_router` 3건) 라인 정확 |
| TC-AICONTRACT-08 | PASS | [정적] `ai_mock_mode: bool = Field(default=False, ...)`. `config.py:26-33` 라인 정확 |
| TC-AICONTRACT-09 | PASS | [정적] `max_image_size_mb: int = Field(default=10, ge=1, le=100, ...)`. `config.py:47` 라인 정확 |
| TC-AICONTRACT-10 | PASS | [정적] `cors_origins_list()`가 콤마 분리+trim. `config.py:70-71` 라인 정확 |
| TC-AICONTRACT-11 | PASS | [정적] `test_config에_detector_backend_설정이_없음`이 `Settings.model_fields`에 `detector_backend`/`rtdetr_model_id` 부재·`resolved_detector_backend` 속성 부재를 단언. `test_yolo_dispatch.py:162` 라인 정확(함수 정의 그 줄) |
| TC-AICONTRACT-12 | PASS | [정적] `DetectionBoxNormalizer.normalizeBbox`: 좌표≠4/null/NaN·Infinity → `IllegalArgumentException`(all-or-nothing 거부), 정상 좌표는 0≤x≤bounds clamp, clamp 후 폭·높이≤0(퇴화)이면 `Optional.empty()`(해당 검출만 스킵) — ai-server는 정규화 책임이 없다는 케이스 전제와 일치. `DetectionBoxNormalizer.java:50-81` 라인 정확(normalizeBbox 50-69 + upperBound 71-77 + clamp 79-81 전체 포괄) |
| TC-AIINFRA-01 | PASS | [실동작] `GET /health` → `200 {"status":"ok"}`. `main.py:70-72` 라인 정확 |
| TC-AIINFRA-02 | PASS | [실동작] 임의 요청에 `x-request-id` 응답 헤더 항상 존재(위 모든 curl 응답에서 확인). `request_id.py:34-48` 라인 정확 |
| TC-AIINFRA-03 | PASS | [실동작] `X-Request-Id: abc-123` 요청 → 응답 헤더 동일값 `abc-123` 반사. `request_id.py:38-47` 라인 정확 |
| TC-AIINFRA-04 | PASS | [실동작] `X-Request-Id: abc;def`(비영숫자, `_is_safe_id`가 거부하는 문자) → 12자리 hex로 재생성(`1853754e8ab2`) 확인. (참고: curl 자체가 헤더값의 실제 `\r\n`을 전송 차단해 문자 그대로의 CRLF 재현은 도구 한계로 대체 문자셋으로 검증했으나, `_is_safe_id`가 영숫자+`-_`만 허용하므로 CRLF도 동일 분기로 거부됨을 코드로 확인) `request_id.py:39-41,51-54` 라인 정확 |
| TC-AIINFRA-05 | PASS | [실동작] 65자 id 요청 → 12자리 hex로 재생성 확인. `request_id.py:52-53`(`len(value) > 64: return False`) 라인 정확 |
| TC-AIINFRA-06 | PASS | [정적] `_unhandled` 핸들러가 `logger.exception`(서버 로그만)으로 기록하고 응답은 `{"error_code":"INTERNAL_ERROR","message":"서버 내부 오류"}`만 반환 — 예외 타입명·스택트레이스 응답 미노출. `exceptions.py:65-69` 라인 정확 |
| TC-AIINFRA-07 | PASS | [실동작]+[정적] 위 모든 400 응답(`INVALID_IMAGE`/`VALIDATION_ERROR` 등)이 `error_code`+`message`만 포함, 내부경로·스택 없음. `exceptions.py:29-31,51-59`(`_err`+`_validation`) 라인 정확 |
| TC-AIINFRA-08 | PASS | [정적] `ErrorResponse(model_config=ConfigDict(extra="forbid"))`. `schemas.py:23-29` 라인 정확 |

## 카탈로그 정정

**0건.** 담당 라인범위(115~162행) 34개 케이스의 근거 `file:line`을 전부 실측 대조했으며 전건 일치(라인 드리프트 없음). 2026-08-03 회차 최신화(변경이력 표 "정정 53건")가 이미 이 구간의 드리프트를 정리해둔 것으로 확인됨 — 특히 TC-AIVLM-10은 2차 검증(`G-ISSUE-63`)이 지적했던 `schemas.py:204,211`(응답 모델 오귀속) 오류가 현재 `240,248`로 이미 정정되어 코드와 정확히 일치.

## 이전 회차(2차, 2026-08-02) 이슈 대조 — 내 범위(G-5/G-6) 해당분

| 2차 이슈 | 내용 | 3차 상태 |
|---|---|---|
| G-ISSUE-62(2차) | `AiServerClient.verifyObjects` 프로덕션 호출부 0건(G-5 엔드포인트 전체가 도달 불가 표면), LOW | **미해소 — 이월**(아래 G-ISSUE-61로 3차 번호 재부여) |
| G-ISSUE-63(2차) 중 TC-AIVLM-10 드리프트분(`schemas.py:204,211`) | 근거 라인이 응답 모델(`ObjectVerification`)을 오귀속 | **✅ 해소** — 현재 `240,248`로 정정되어 코드와 일치 (나머지 TC-AIMOCK-43/45/46 드리프트는 G-10 소관, 내 범위 아님) |
| G-ISSUE-65(2차) TC-AIMOCK-40 | 외부 URL 대역 검사 첫 해석주소만 검사 | G-10 소관(TC-AIMOCK-40), 내 담당 라인범위(115~162행) 밖 — 확인 대상 아님 |
| G-ISSUE-09(2차) | TC-AIYOLO-15/27/45 근거 드리프트 | G-1/G-3 소관, 내 범위 밖 |

## 이슈 기록

### [G-ISSUE-61] TC-AIVLM-14 — `AiServerClient.verifyObjects` 프로덕션 호출부 0건(G-5 엔드포인트 전체가 도달 불가 표면) — 2차 G-ISSUE-62 이월
- **심각도**: LOW
- **기대 동작(기대효과)**: ai-server `/infer/vlm/verify-objects`는 "YOLO/SAM2 검출 라벨 정합성 검증" 목적으로 노출됐고, 카탈로그 §G-5 전제는 BE가 `AiServerClient.verifyObjects`로 이를 실제 호출하는 것이다. 노출된 추론 표면은 실사용되거나, 아니면 제거돼야 한다(OWASP API9:2023 Improper Inventory Management).
- **현재 동작(이슈 내용)**: 클라이언트 메서드는 여전히 정의만 있고 `src/main` 어디서도 호출되지 않는다.
  ```java
  // backend/.../common/client/AiServerClient.java:89-98
  public Mono<VlmVerifyResponse> verifyObjects(VlmVerifyRequest request) {
      return webClient.post().uri("/infer/vlm/verify-objects")...
  }
  ```
  `grep -rn "verifyObjects" backend/src/main backend/src/test` → `src/main` 정의부 1건뿐(호출 0건), `src/test`는 `AiInferenceDeidentReportGateTest.java:66` 주석 언급 1건뿐(실호출 아님). `grep -rn "infer/vlm" backend/src frontend/src` → BE 3건 전부 정의/주석/DTO 주석, FE 0건.
- **재현/확인 경로**: `grep -rn "verifyObjects" backend/src/main` (호출부 0건 재확인). `docker logs klid-ai-server 2>&1 | grep "verify-objects"`로 실제 호출 발신 IP를 보면 backend 컨테이너(172.20.0.5 등) 발신이 없음을 확인 가능(본 회차는 검증자 curl만 관측).
- **영향**: 기능 결함 아님(다른 파이프라인 동작에 영향 없음). ①인증 없는 추론 표면(ai-server는 무인증)이 사용처 없이 열려 있음 ②G-5 14개 케이스가 제품 동선에서 도달 불가능한 경로를 검증 중이라 검증 리소스 배분 왜곡. `AiInferenceDeidentReportGateTest.java:66` 주석은 "verifyObjects처럼 같은 이미지를 운반하는 다른 메서드"가 향후 배선 시 비식별 신고 게이트를 우회할 잠재 위험을 지적하는데 현재는 호출부가 없어 잠재 위험으로만 남음(CWE-359 배선 시 재확인 필요).
- **수정 방향(제안)**: 정책 결정 필요 — ①실사용 계획이 없으면 `AiServerClient.verifyObjects` + `ai-server/app/routers/vlm.py` 라우트 + 관련 스키마 제거(표면 축소) ②사용 계획이 있으면 배선 시 비식별 신고 게이트(`DeidentReportGate`)를 반드시 함께 적용. 어느 쪽이든 카탈로그 §G-5에 "현재 프로덕션 호출부 0건"을 명기해 다음 검증자가 도달 불가 경로임을 알게 한다. (2차 회차부터 2회 연속 관측 — 다음 회차에도 재확인 권장)

---

# ===== _raw/G-part5.md 원문 =====

# G-part5 — 외부 벤더 목업 계약 검증 (G-7 KPST · G-8 VLM · G-9 genai, 33건) — 3차 회차

- **대상**: `docs/test-cases/G-ai-server.md` §G-7(8) + §G-8(4) + §G-9(21) = **33건**(`TC-AIMOCK-01~33`)
- **검증일**: 2026-08-03(3차)
- **검증 스택**: `docker ps` → `klid-mock-server Up 3 hours (healthy)`(재빌드 후 컨테이너, `_raw/stack-bringup.md` §0 참조 — HEAD `e065da42` 코드와 일치)
- **검증 방식**: mock-server(`127.0.0.1:9400`) **실동작 호출 27건**(신규 프로젝트/잡 생성 포함) + 정적 대조 6건(env 변경 없이는 재현 불가한 fail-closed 케이스: 05, 22, 26, 28, 29 static + 09 콜백 발사는 로그로 실동작 확인)
- **회귀 전제 확인(Critical)**: `git log --oneline --since="2026-08-02" -- mock-server/` → **커밋 0건**. 즉 2차(2026-08-02) 검증 이후 mock-server 코드는 전혀 변경되지 않았다 — 이번 회차는 ①2차에서 PASS 였던 31건이 여전히 실동작으로 재현되는지 ②2차에서 지적한 결함(FAIL 1건 + 부수 이슈 6건)이 실제로 고쳐졌는지(코드 커밋 0건이므로 **고쳐지지 않았을 것으로 예상**되고, 실측으로 그 예상을 반증/확증)를 검증하는 데 집중했다.
- **런타임 실효 환경변수**(docker inspect 실측, 2차와 동일): `MOCK_OUTPUT_BASE=/app/storage/raw,/app/storage/deidentified` · `MOCK_INPUT_BASE=/app/storage` · `MOCK_CALLBACK_ALLOWED_HOSTS=klid-backend,localhost,127.0.0.1` · `MOCK_GENAI_INPUT_BASE=/app/storage` · `MOCK_GENAI_OUTPUT_BASE=/app/genai-out`
- ⚠ **환경 부작용 고지(2차와 동일 종류 재발)**: G-ISSUE-82(구 G-ISSUE-82) 반증용 VLM SSRF 콜백 테스트가 이번 회차에도 `POST /api/genai/_mock/reset` 을 트리거해 genai 인메모리 job 저장소가 재초기화됐다(17:34:52). 코드가 안 고쳐진 이상 검증할 때마다 재발한다 — 근본 수정 전까지는 **G/genai 관련 다른 클러스터 검증 시 이 부작용을 인지**할 것.

---

## 판정 요약

| 클러스터 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| G-7 KPST | 8 | 8 | 0 | 0 | 0 | 0 | 0 |
| G-8 VLM | 4 | 3 | 1 | 0 | 0 | 0 | 0 |
| G-9 genai | 21 | 21 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **33** | **32** | **1** | **0** | **0** | **0** | **0** |

> 2차 대비: TC-AIMOCK-07(당시 PARTIAL, 카탈로그 stale 기대결과)은 **2차~3차 사이 카탈로그가 이미 정정**돼 이번 회차엔 PASS. TC-AIMOCK-12(FAIL)는 **코드가 안 고쳐져 이번에도 FAIL** — 단 카탈로그 기대결과 자체가 구현을 베낀 문제였던 부분은 이번 회차에 정정(아래 "카탈로그 정정" 참조).

---

## G-7. KPST 비식별 벤더 목업 (8건, 전부 실동작 재확인)

새 프로젝트(`qa3-g5-p1`, `qa3-g5-p1b`, `qa3-g5-esc`, `qa3-g5-badinput`)를 실제로 생성해 확인.

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-AIMOCK-01 | PASS | [실동작] | `GET /` → `200` body `Connect`. 근거 `deid.py:119`(`return "Connect"`) 정확 |
| TC-AIMOCK-02 | PASS | [실동작] | `GET /health` → `200 {"status":"ok"}`. 근거 `main.py:104` 정확 |
| TC-AIMOCK-03 | PASS | [실동작] | `POST /project`(input_path=`/app/storage/raw/autolabel-test/`, files=`[af5780ea….mp4]`) → `retrieve_progress` 응답 `dsStatus[0].fileName="/app/storage/raw/autolabel-test/af5780ea….mp4"` = input_path+원본 basename(산출물명 아님). 정합 |
| TC-AIMOCK-04 | PASS | [실동작] | 완료 후 `af5780ea…-mask.mp4`(50,854B) 생성 확인 — `{stem}-mask{ext}`, 타임스탬프 세그먼트 없음. 근거 `deid_sim.py:223(MASK_SUFFIX),255(mask_name_from),269` 정확 |
| TC-AIMOCK-05 | PASS | [정적] | `deid_sim.py:1706-1714` — `if not output_base:` → WARN 1회 + `ProductionOutcome(written=[])`(실패 아닌 no-op). 런타임엔 base 설정돼 있어 실동작 재현 불가(환경 개조 미수행, VERIFY-PROMPT §10 예외 범위 밖) |
| TC-AIMOCK-06 | PASS | [실동작] | `export_path=/tmp/qa3-esc/`(output_base 밖) → 응답 `200 {"result":"success","prj_id":18}` 유지, 컨테이너 내 `/tmp/qa3-esc` **미생성**, 로그 `deid output dir rejected … reason=OUTPUT_DIR_REJECTED` |
| TC-AIMOCK-07 | **PASS**(2차 PARTIAL→해소) | [실동작] | `input_path=/etc/`, `files=["hosts.mp4"]` → `procState=99`, export 디렉터리 **완전히 빈 상태**(placeholder 파일 0건). 카탈로그 기대결과가 2차~3차 사이 "18바이트 placeholder" 구 정책에서 "산출 실패 종결"로 이미 정정돼 있어(현재 175행) 실동작과 일치 |
| TC-AIMOCK-08 | PASS | [실동작] | 기존 산출물(`af5780ea…-mask.mp4`, 50854B) 있는 동일 export_path 로 재실행(`prj_id:17`) → 파일 **크기 동일 유지**, 로그 `deid output exists — skip(no-overwrite)` |

### G-7 부수 관찰 — 코드 레벨, 2차 이후 미해결 재확인
- `.mock-tmp/` 빈 디렉터리가 산출 완료 후에도 export_path 에 잔존(TC-AIMOCK-04 확인 시 `qa3-g5-p1/` 하위에서도 동일 관측) → **G-ISSUE-85**(2차 G-ISSUE-88 승계, 코드 미변경)
- `GET /manual_deid_info` 가 `db_save==1` 만으로 필터, KPST §22.4 "프로젝트 상태=3(수동대상)" 축 미반영 — 코드 미변경으로 동일 → **G-ISSUE-84**(2차 G-ISSUE-86 승계)

---

## G-8. VLM 벤더 목업 (4건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-AIMOCK-09 | PASS | [실동작] | `POST /v1/videovlm/verify`(callback_url=`http://klid-backend:8080/...`) → `200 {"request_id":...,"status":"accepted"}`, 2초 후 실제 콜백 발사(로그로 확인, `describe` 도 동일 패턴 — 4단계 webhook 관측은 G-9 TC-AIMOCK-17 절 참조) |
| TC-AIMOCK-10 | PASS | [실동작] | `callback_url=http://127.0.0.1:9400/api/genai/_mock/reset`(허용 호스트 `127.0.0.1`) → **접수는 성공**(허용 호스트라 400 케이스 자체는 별도 외부호스트로 확인 필요) — 재확인을 위해 임의 외부호스트(`evil.example.com`류)로도 별도 호출해 `400 VALIDATION_ERROR` 확인(2차와 동일 코드, `url_guard.is_allowed_callback` 완전일치 로직 불변). 케이스가 요구하는 "허용 밖 호스트 → 400" 자체는 PASS. ⚠단 "허용 호스트"의 정의가 호스트만이라 `127.0.0.1`(자기 자신)도 포함돼 버리는 심층 결함은 별도 — 아래 G-ISSUE-82 |
| TC-AIMOCK-11 | PASS(카탈로그 정정 후) | [실동작] | `request_id="fail-qa3"` → 동기 `200 {"request_id":"fail-qa3","status":"accepted"}`(202 아님) 확인. 카탈로그가 2차 G-ISSUE-83 지적(동기 202는 오기) 이후에도 **미수정 상태였음을 이번 회차에 재발견** → 본 회차에 직접 정정(아래 "카탈로그 정정" 참조), 정정 후 실동작과 일치해 PASS |
| TC-AIMOCK-12 | **FAIL**(2차 G-ISSUE-84 승계, 코드 미변경) | [실동작] | `GET /v1/videovlm/status` → `200 {"status":"ok","service":"videovlm"}`. 벤더 IntelliVIX v2.0.1 §2.7 규격은 `{"status":"ready"}`/`{"status":"busy"}`(service 필드 없음). 카탈로그가 구현을 그대로 베껴 "기대결과"로 정본화하던 문제는 이번 회차에 벤더 규격 기준으로 정정(아래 참조)했으나, **코드 자체는 여전히 규격 위반** → FAIL 판정 유지 |

### G-8 부수 관찰 — 코드 레벨, 2차 이후 미해결 재확인
- VLM 요청 스키마가 벤더 §3.1/§3.2 조건부 필수(`media.path`(source_type=path 시 필수), `frame_policy.framerate`(Required=Y), `frame_policy.selected_frames`(mode=frame_selected 시 필수))를 강제하지 않음(`schemas/vlm.py` 전부 Optional, 코드 미변경 확인) → **G-ISSUE-83**(2차 G-ISSUE-85 승계)
- VLM 콜백 SSRF 가드가 genai 가드(호스트:포트+경로접두사+자기참조 차단, `genai_sim.is_allowed_url`)보다 약함(`url_guard.is_allowed_callback` 은 호스트 완전일치만) — 실제로 `callback_url=http://127.0.0.1:9400/api/genai/_mock/reset` 1회 호출로 **genai job 저장소가 재차 초기화**됨(재현: 17:34:52 로그 `[MOCK][GENAI] store reset`). genai 쪽은 동일 URL 을 자기참조로 **차단**함을 대조 확인(`{"code":"INVALID_PARAMETER",...,"목 자신은 금지"}`, 400) → 방어 비대칭 재확인 → **G-ISSUE-82**(2차 G-ISSUE-82 승계, 코드 미변경)

---

## G-9. 생성형 AI(genai) 증강 벤더 목업 (21건, 대부분 실동작 재확인)

⚠ 이번 회차 최초 시도 시 `request_channel`(필수 필드, `schemas/genai.py:114`)를 누락해 전건 400 `REQUIRED_FIELD_MISSING`을 받았다 — 이는 **카탈로그·코드 결함이 아니라 검증 테스트 페이로드 누락**이었음(테스트 픽스처 `test_genai_jobs.py:70` 대조로 확인·정정 후 재시도). 확증편향 반대 방향 오류(거짓 FAIL 자가발견)로 기록.

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-AIMOCK-13 | PASS | [실동작] | 정상 요청(T2I) → `202 {request_id,job_id,status:"RECEIVED",received_at}` |
| TC-AIMOCK-14 | PASS | [실동작] | `generation_mode=I2I`+`input_files=[]` → `400 REQUIRED_FIELD_MISSING`("input_files 는 I2I·I2V 에서 1건 이상 필요") |
| TC-AIMOCK-15 | PASS | [실동작] | `generation_mode=T2I`+input_files 미지정 → `202 RECEIVED` |
| TC-AIMOCK-16 | PASS | [실동작] | `sequence=[1,1]` 중복 → `400 INVALID_PARAMETER`("sequence 는 중복될 수 없습니다") |
| TC-AIMOCK-17 | PASS | [실동작] | `callback_url=http://klid-backend:8080/...` 지정 job 진행 시 로그에 **4단계 webhook 발사**(PREPROCESS→INFERENCE→POSTPROCESS→COMPLETED, 17:38:36~46) 확인, 각 최대 2회 재시도 후 give-up(실 backend 가 401/429 로 거부 — mock 발사 자체 확인이 목적이라 무관) |
| TC-AIMOCK-18 | PASS | [실동작] | RECEIVED 직후(비SUCCEEDED) `GET .../results` → `409 STATE_CONFLICT` |
| TC-AIMOCK-19 | PASS | [실동작] | T2I 완료(SUCCEEDED, 6초 소요) → `output_file_path` 실존 파일(21B placeholder, T2I=원본 없음) + `sha256sum` 완전 일치 |
| TC-AIMOCK-20 | PASS | [실동작] | RUNNING 중 cancel → `200 CANCELED`. SUCCEEDED 후 cancel → `409 STATE_CONFLICT`("이미 종료된 작업은 취소할 수 없습니다") |
| TC-AIMOCK-21 | PASS | [실동작] | `Idempotency-Key` 동일 키로 **본문이 다른**(request_id/evnt_type 상이) 2차 요청 → 완전 동일 `job_id` 반환(1차 요청 request_id 로 echo) |
| TC-AIMOCK-22 | PASS | [정적] | `genai_sim.py:555-559` `if not output_base: raise JobExecutionError(RESULT_SAVE_FAILED)` — 런타임 env 설정돼 있어 실동작 재현 불가 |
| TC-AIMOCK-23 | PASS | [실동작] | `file_path=/etc/passwd`(루트 밖 절대경로) → `400 INVALID_PARAMETER`("허용된 루트의 절대경로여야 합니다") |
| TC-AIMOCK-24 | PASS | [실동작] | `callback_url=http://localhost:9400/api/genai/_mock/reset`(자기참조) → `400 INVALID_PARAMETER`("목 자신은 금지") — VLM(TC-AIMOCK-10/G-ISSUE-82)과 달리 **자기참조를 실제로 차단**함을 대조 확인 |
| TC-AIMOCK-25 | PASS | [실동작] | 본문 1,300,224B(>1MiB) → `413 GA-MEDIA-001`("요청 본문 크기가 허용 한도를 초과") |
| TC-AIMOCK-26 | PASS | [정적] | `genai_sim.py:447-467(_open_source_nofollow, O_NOFOLLOW+fd기준 fstat), 620-635(_revalidate_source)` — TOCTOU 재검증 로직 확인, 테스트 `test_genai_security_hardening.py::F1` 커버. 실측 재현(심볼릭링크 교체)은 파괴적이라 미수행(2차와 동일 판단) |
| TC-AIMOCK-27 | PASS | [실동작] | RUNNING 중 cancel 후 `ls /app/genai-out/genai/{jobId}/` → **`No such file or directory`**(산출물·디렉터리 모두 정리됨) |
| TC-AIMOCK-28 | PASS | [정적+실동작] | `MOCK_GENAI_EVENT_TYPES` 미설정 상태 확인 → 미강제(실동작으로 `evnt_type=WINTER`/`NIGHT` 둘 다 202 확인, 설계대로) |
| TC-AIMOCK-29 | PASS | [정적] | `config.py:202-208(genai_max_jobs, default 1000)` — FIFO 만료 로직 정적 확인, `max_jobs` 낮춰 재현하려면 컨테이너 env 변경 필요(환경 개조 미수행) |
| TC-AIMOCK-30 | PASS | [실동작] | `GET /_mock/jobs` → jobs[]+active_tasks+queue 반환(BE 세션 이력 없음, 이번 회차 신규 잡만 관측 — 재빌드로 이력 리셋됨), `POST /_mock/jobs/{id}/status-sync` 정상 동작 |
| TC-AIMOCK-31 | PASS | [실동작] | `MOCK_GENAI_STATUS_SYNC_URL` 미설정 상태에서 실제 job 대상 status-sync 호출 → `{"sent":false,"target":null,"reason":"... 비활성"}` |
| TC-AIMOCK-32 | PASS | [실동작] | 이번 회차 모든 genai 호출(13건 이상) **무인증 헤더로 정상 처리**됨(202/400/409/413) |
| TC-AIMOCK-33 | PASS | [실동작] | `docker logs` 에 `[MOCK]` 접두 로그 다수 stdout 노출 확인(최근 60초간 10건) |

---

## 이슈

### [G-ISSUE-81] TC-AIMOCK-12 — `/v1/videovlm/status` 가 IntelliVIX v2.0.1 §2.7 규격과 불일치 (2차 G-ISSUE-84 승계, 코드 미수정)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 벤더 v2.0.1 §2.7(서버 상태 체크)은 `200 {"status":"ready"}`(처리 가능) / `200 {"status":"busy"}`(진행 중)만 규정하며 `service` 필드는 없다. 목업은 이 계약을 재현해야 향후 VLM 헬스 인디케이터를 로컬에서 검증할 수 있다.
- **현재 동작(이슈 내용)**: 코드가 2026-08-02(2차) 이후 전혀 변경되지 않아 동일하게 규격을 위반한다.
  ```python
  # mock-server/app/routers/vlm.py:242-244
  async def status_check() -> dict[str, str]:
      return {"status": "ok", "service": "videovlm"}
  ```
  실동작(2026-08-03 17:xx): `GET /v1/videovlm/status` → `200 {"status":"ok","service":"videovlm"}`.
- **재현/확인 경로**: `curl -s http://localhost:9400/v1/videovlm/status`
- **영향**: 기능 영향 현재 없음(BE `VlmClient` 가 이 EP 를 호출하지 않음, grep 확인). 다만 목업=계약 정본 전제가 이 EP 에서 깨져 있고, 카탈로그가 구현을 그대로 베껴 결함을 은폐하고 있었다(이번 회차에 카탈로그는 정정, 코드는 미정정 — 아래 "카탈로그 정정" 참조).
- **수정 방향(제안)**: `vlm.py:status_check` 를 `{"status":"ready"}`/`{"status":"busy"}` 로 교체(진행 중 콜백 유무로 busy 판정 가능, `vlm_sim` 확장). **본 회차에서도 수정하지 않음**(검증 전용, VERIFY-PROMPT §10).

### [G-ISSUE-82] G-8 — VLM 콜백 SSRF 가드가 genai 가드보다 약함(자기참조·임의포트 허용, 2차 G-ISSUE-82 승계, 코드 미수정)
- **심각도**: MEDIUM(목 서버가 루프백 전용 발행이라 원격 노출 없음, 노출 시 HIGH)
- **기대 동작(기대효과)**: 같은 서버의 genai 가드는 host:port allowlist+경로접두사+자기참조 차단을 구현하는데(`genai_sim.is_allowed_url`), VLM 은 호스트 완전일치만 검사(`url_guard.is_allowed_callback`)해 방어 비대칭이 있다. 무인증 서버이므로 동등한 강도가 필요하다.
- **현재 동작(이슈 내용)**: 코드 미변경 확인(`url_guard.py` 전체 대조 — 2차 인용과 동일).
  ```python
  # mock-server/app/services/url_guard.py:28-33
  def is_allowed_callback(url, allowed_hosts) -> bool:
      host = callback_host(url)
      if host is None: return False
      return host in {h.lower() for h in allowed_hosts}
  ```
  기본 allowlist 에 `127.0.0.1`이 있어 **목 서버 자기 자신**이 항상 허용된다. 재현(2026-08-03 17:34:50~52):
  ```
  [MOCK][VLM] verify accepted request_id=qa3-ssrf callback_url=http://127.0.0.1:9400/api/genai/_mock/reset
  [MOCK][GENAI] store reset          ← 다른 벤더(genai)의 job 저장소가 통째로 초기화됨
  [MOCK][VLM] callback sent url=http://127.0.0.1:9400/api/genai/_mock/reset status=200
  ```
  대조로 **동일 URL 을 genai `callback_url` 로 넣으면 차단**됨을 확인: `{"code":"INVALID_PARAMETER","message":"...목 자신은 금지"}` (400).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:9400/v1/videovlm/verify -H 'Content-Type: application/json' \
    -d '{"request_id":"x","event_type":"fall","media":{"type":"video","source_type":"path","path":"/x.mp4"},"callback_url":"http://127.0.0.1:9400/api/genai/_mock/reset"}'
  # 200 accepted → 2초 후 docker logs 에 "[MOCK][GENAI] store reset"
  ```
- **영향**: CWE-918(SSRF) + 무인증 상태변경 유발. 검증 세션 중에도 **다른 클러스터의 genai 관측 이력이 이 테스트 하나로 사라진다**(이번 회차에도 실제 발생, 위 "환경 부작용 고지" 참조) — 검증 신뢰성 자체에 영향.
- **수정 방향(제안)**: `vlm.py:_assert_allowed_callback` 이 genai 와 동일 판정기(`is_allowed_url` 을 벤더 중립 모듈로 승격)를 쓰도록 통합. 최소한 `_mock/*` 보조 EP 는 콜백 대상에서 무조건 배제. **본 회차에서도 수정하지 않음**.

### [G-ISSUE-83] G-8 — VLM 요청 스키마가 벤더 조건부 필수 필드를 검증하지 않음(2차 G-ISSUE-85 승계, 코드 미수정)
- **심각도**: LOW
- **기대 동작(기대효과)**: 벤더 §3.1/§3.2 조건부 필수(`media.path`(source_type=path 시), `frame_policy.framerate`(Required=Y), `frame_policy.selected_frames`(mode=frame_selected 시))를 목업도 강제해야 BE 리팩터 회귀를 로컬에서 잡는다.
- **현재 동작(이슈 내용)**: `schemas/vlm.py` 전부 Optional, 코드 미변경 확인(2차 인용 라인과 동일 — `framerate`/`selected_frames`/`path` 모두 `Optional[...] = Field(default=None, ...)`).
- **재현/확인 경로**: 2차 문서 기록과 동일(`media.path` 누락, `frame_policy.framerate` 누락, `mode=frame_selected`인데 `selected_frames` 누락 — 3건 모두 200). 이번 회차는 코드 diff 0건 확인으로 대체(실동작 재실행 생략, 근거: `git log` 무커밋).
- **영향**: 계약 검증 공백, BE 리팩터 회귀를 로컬·CI 어디서도 못 잡음.
- **수정 방향(제안)**: `schemas/vlm.py` 에 `model_validator(mode="after")` 로 조건부 필수 3종 400 거부 추가. **본 회차에서도 수정하지 않음**.

### [G-ISSUE-84] G-7 — `GET /manual_deid_info` 가 KPST "프로젝트 상태=3(수동대상)" 축을 반영하지 않음(2차 G-ISSUE-86 승계, 코드 미수정)
- **심각도**: LOW
- **기대 동작(기대효과)**: KPST 명세(`22-deid-solution-api.md` §22.3.8/§22.4)는 이 EP 를 "`db_save=1` **이며** 프로젝트 상태=수동대상(state=3)" 데이터셋으로 정의.
- **현재 동작(이슈 내용)**: `deid.py:_manual_targets`(2차와 동일 라인대) 가 `db_save==1` 만으로 필터, 진행중/완료 구분 없이 반환. 코드 미변경 확인.
- **영향**: 기능 영향 없음(수동 비식별 연계는 `22-deid-solution-api.md` §22.6 이 "후속·미구현" 명시, BE 호출부 0건). 향후 워크플로 연결 시 로컬 검증 공백.
- **수정 방향(제안)**: `_manual_targets` 에 상태=수동대상 조건 추가. **본 회차에서도 수정하지 않음**.

### [G-ISSUE-85] G-7 — 산출 완료 후 `.mock-tmp/` 빈 디렉터리가 export_path 에 잔존(2차 G-ISSUE-88 승계, 코드 미수정)
- **심각도**: LOW
- **기대 동작(기대효과)**: BE 는 완료 후 export 디렉터리를 폴백 스캔한다. 목이 만드는 임시 디렉터리는 완료 후 정리돼 있어야 스캔 대상이 깨끗하다.
- **현재 동작(이슈 내용)**: 이번 회차 신규 생성한 `qa3-g5-p1/` 에서도 동일 재현.
  ```
  $ docker exec klid-mock-server ls -la /app/storage/deidentified/videos/qa3-g5-p1/
  drwx------ 2 app app 4096 Aug  3 17:35 .mock-tmp     ← 빈 디렉터리, 잔존
  -rw-r--r-- 1 app app 50854 Aug  3 17:35 af5780ea…-mask.mp4
  ```
- **영향**: 현재 무해(BE 회수는 1차 파일명 경로가 맞아 폴백 스캔에 도달 안 함). export 폴더 청결성 저하.
- **수정 방향(제안)**: `produce_deid_outputs` 종료부에서 빈 임시 디렉터리 `rmdir`(genai `discard_results` 와 동일 패턴). **본 회차에서도 수정하지 않음**.

---

## 카탈로그 정정 (담당 라인범위 163~216행 내, 직접 Edit 완료)

| 정정 대상 | 정정 전 | 정정 후 | 근거 |
|---|---|---|---|
| TC-AIMOCK-11(186행) | 기대결과 "동기 **202** accepted, 콜백은 status=failed+error_code/message" | "동기 **200** accepted(202 아님 — 벤더 v2.0.1 §2.1/§2.5 및 TC-AIMOCK-09 와 동일 코드), 콜백은 `{"status":"failed","error":{"code":"INFERENCE_ERROR","message":...}}`(중첩 error 객체)" | 실동작 `HTTP:200` 확인 + `vlm.py:196-199,219-222`. 2차 G-ISSUE-83 이 이미 지적했으나 **미반영 상태로 방치**돼 있던 것을 이번 회차에 재발견·정정 |
| TC-AIMOCK-12(187행) | 기대결과 "200 {status:ok, service:videovlm}"(=구현을 그대로 베낀 것) | 벤더 v2.0.1 §2.7 규격(`ready`/`busy`, service 필드 없음)을 기대결과로 명시하고, 현재 코드가 이를 위반함(FAIL)을 케이스명에 명기 | G-ISSUE-84(2차)/G-ISSUE-81(본 회차) — 카탈로그가 결함을 은폐하던 확증편향 사례. 벤더 원문은 2차 검증자가 docx 추출로 확인한 내용을 승계(본 회차엔 docx 재추출 미실시, 2차 인용을 근거로 사용 — 필요시 원문 docx 재확인 권장) |
| 변경 이력 표(9행 뒤) | (round 2 까지) | 3차 회차 행 추가(정정 2건 요약 + 2차 이후 code 미변경으로 인한 재확인 결과 명시) | — |

**정정 건수: 2건**(TC 행) + 변경이력 1행 추가.

---

## 이전 회차(2차, 2026-08-02) 이슈 해소 여부

| 2차 이슈 | 내용 | 이번 회차 상태 |
|---|---|---|
| G-ISSUE-81 | TC-AIMOCK-07 카탈로그 stale(18바이트 placeholder) | **해소됨** — 카탈로그가 2차~3차 사이 이미 정정되어 있었음(확인만), 실동작도 정정된 기대결과와 일치 |
| G-ISSUE-82 | VLM 콜백 SSRF 가드 약함(자기참조 성립) | **미해결** — 코드 미변경, 이번 회차도 동일하게 재현(genai store reset 재발) → 본 문서 G-ISSUE-82 로 재기록 |
| G-ISSUE-83 | TC-AIMOCK-11 카탈로그 "동기 202" 오기 | **미해결 상태로 방치돼 있었음 → 이번 회차에 직접 정정 완료**(위 "카탈로그 정정" 참조) |
| G-ISSUE-84 | TC-AIMOCK-12 벤더 규격 불일치(status 응답) | **코드 미해결**(재확인), 카탈로그는 이번 회차에 벤더 규격 기준으로 정정 → 본 문서 G-ISSUE-81 로 재기록 |
| G-ISSUE-85 | VLM 스키마 조건부 필수 미검증 | **미해결** — 코드 미변경(diff 0건 확인) → 본 문서 G-ISSUE-83 로 재기록 |
| G-ISSUE-86 | manual_deid_info 상태축 미반영 | **미해결** — 코드 미변경 → 본 문서 G-ISSUE-84 로 재기록 |
| G-ISSUE-87 | 근거 file:line 드리프트 15건(본 스코프분) | **해소됨** — 2차~3차 사이 카탈로그 전수 라인 정정(163행 전체 회차)이 이미 적용되어 있었고, 이번 회차 샘플 재검증(01,02,03,04,05,06,07,08,10,12,17,22,29 등)에서 전부 실제 코드와 일치 확인 |
| G-ISSUE-88 | .mock-tmp 잔존 | **미해결** — 코드 미변경, 이번 회차 신규 생성 프로젝트에서도 재현 → 본 문서 G-ISSUE-85 로 재기록 |

**요약**: 2차 8건 중 카탈로그성 2건(81·87)은 해소, 코드성 5건(82·84·85·86·88)은 전부 코드 미변경으로 미해결 재확인, 카탈로그 방치 1건(83)은 이번 회차에 직접 정정.
