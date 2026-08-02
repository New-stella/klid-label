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
