# G 클러스터 part2 (G-4~G-6) 2차 검증 결과

검증 대상: `docs/test-cases/G-ai-server.md` — G-4(28) + G-5(14) + G-6(20) = 62건. 취소선(폐기) 행 0건.
검증 환경: ai-server `localhost:19300`(컨테이너 `klid-ai-server`) — `AI_MOCK_MODE=false`, `AI_DEVICE=cpu`.
★ **환경 실측 갱신(중요)**: 이번 실행 시점에 컨테이너가 HF Hub 네트워크 접근이 가능해 SAM2 실가중치
(`facebook/sam2-hiera-tiny`)가 **정상 다운로드·로드**되고(`source=model`, `mock=false` 실측), 실제 SAM2
추론(`/infer/sam2/segment`, `/track`)이 살아있는 서버로 그대로 동작했다. UNCERTAINTIES.md #15("실모델
테스트 게이팅 미해소")는 **pytest `skipif`(sam2 패키지 설치 여부만 체크)에 대한 서술**이며, 이 라이브
컨테이너에서는 가중치 다운로드까지 자동 성공해 실모델 검증이 가능했다 — 케이스 설명을 업데이트할 필요.
"mock" 전제 케이스는 라이브 서버가 실모델 모드라 HTTP로 직접 재현 불가한 것들이 있어, 그 경우
`docker exec -e AI_MOCK_MODE=true <container> python3 <probe>.py`로 **실제 라우터 핸들러 함수(async
`segment()`/`track()`/`verify_objects()`)를 격리 프로세스에서 직접 호출**해 실동작을 확인했다(파일 변경
없음, 서빙 프로세스 비간섭 — `근거 확인`에 [실동작-격리]로 표기).

## 집계

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| G-4 | 28 | 28 | 0 | 0 | 0 | 0 | 0 |
| G-5 | 14 | 14 | 0 | 0 | 0 | 0 | 0 |
| G-6 | 20 | 18 | 0 | 2 | 0 | 0 | 0 |
| **합계** | **62** | **60** | **0** | **2** | **0** | **0** | **0** |

PARTIAL 2건은 카탈로그가 적은 문구 자체는 참(거짓 아님)이나, 적대적 검증 중 그 문구가 감추는 **인접
결함**(입력검증 누락으로 인한 500·SAM2 Track/VLM 응답의 mock 지표 유실)을 발견해 등급을 낮췄다. 상세는
이슈 참조.

## G-4 결과표 (SAM2 `/infer/sam2/segment`, `/track`)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---------|------|
| TC-AISAM2-01 | segment 포인트 → polygon | PASS | [실동작-격리] `AI_MOCK_MODE=true`로 `segment()` 직접 호출 → `mock=true,source=mock,mock_reason=env_mock,score=0.95,polygon 4점`. 라이브(실모델)에서는 points 요청 시 `mock=false,source=model` 실추론 확인(200, score=0.987) | routers/sam2.py:299-314 일치 |
| TC-AISAM2-02 | segment 박스 → polygon | PASS | [실동작-격리] mock 모드 box=[10,10,40,40] → polygon=box 4꼭짓점 그대로. [실동작] 라이브 실모델 box 요청도 200 polygon 반환(실제 SAM2 마스크 윤곽) | routers/sam2.py:303-304 |
| TC-AISAM2-03 | 프롬프트 미제공 중앙 폴백 | PASS | [실동작-격리] mock 모드 프롬프트 없음 → `[[20,20],[30,20],[30,30],[20,30]]`(50x50 이미지의 0.4~0.6 중앙 사각). [실동작] 라이브 실모델도 동일 분기(center=[[w/2,h/2]])로 실추론(200, score=0.987) | routers/sam2.py:309-310 |
| TC-AISAM2-04 | ★임계값/conf 스키마 부재 | PASS | [실동작] `points`+`conf_threshold` 동봉 → 400 `"Extra inputs are not permitted"` | schemas.py:148-155 |
| TC-AISAM2-05 | box 길이≠4 | PASS | [실동작] box=[1,2,3] → 400 `"List should have at least 4 items after validation, not 3"` | schemas.py:153-155 |
| TC-AISAM2-06 | image_b64 빈값/누락 | PASS | [실동작] `""` → 400 `"String should have at least 1 character"` | schemas.py:151 |
| TC-AISAM2-07 | invalid base64 → 400 | PASS | [실동작] `"!!!notbase64!!!"` → 400 `INVALID_IMAGE "base64 디코드 실패"` | image_utils.py:37-44 |
| TC-AISAM2-08 | 크기초과 → 413 | PASS | [실동작] 15MB base64 페이로드 → 413 `"이미지 크기 한도 초과 (max 10MB)"`. ⚠ 실배포 기본 한도는 10MB이며 카탈로그 전제 "1MB"는 테스트 전용 설정값(G-part1 TC-AIYOLO-15와 동일 패턴) | image_utils.py:42-43 |
| TC-AISAM2-09 | mask → contour polygon | PASS | [실동작-격리] `_mask_to_polygon`에 40x40 캔버스 내 20x20 정사각 마스크 투입 → 4점 폴리곤, area=361.0(최대 contour) | routers/sam2.py:134-163 |
| TC-AISAM2-10 | 빈/비정상 마스크 → None | PASS | [실동작-격리] `_best_mask_polygon(zeros, [0.9])` → `None` | routers/sam2.py:150-151 |
| TC-AISAM2-11 | contour 점<3 → skip None | PASS | [실동작-격리] 1픽셀 마스크(`_mask_to_polygon`) → `None`(contour pts<3) | routers/sam2.py:155-158 |
| TC-AISAM2-12 | ★score 0~1 clamp(음수→0) | PASS | [실동작-격리] `_best_mask_polygon(mask,[-5.0])` → `score=0.0`. 상한도 확인: `[5.0]` → `score=1.0` | routers/sam2.py:199-201 |
| TC-AISAM2-13 | segment 실추론 예외 → mock fallback | PASS | [실동작-격리] `model.set_image`가 강제 예외를 던지는 페이크 모델로 `_real_segment` 직접 호출 → `mock=true,source=mock,mock_reason=empty_mask,score=0.95` (스택트레이스는 서버 로그에만, 응답 본문엔 노출 안 됨) | routers/sam2.py:217-220 |
| TC-AISAM2-14 | segment 마스크 없음 → mock fallback | PASS | [실동작-격리] `predict()`가 전부-0 마스크를 반환하는 페이크 모델 → `mock=true,mock_reason=empty_mask` | routers/sam2.py:232-235 |
| TC-AISAM2-15 | track prev_polygon bbox → 다음 세그멘테이션 | PASS | [실동작] 라이브 실모델에 `/infer/sam2/track` box 프롬프트(prev_polygon bbox) 투입 → 200 `mock=false,source=model`, 실제 컨투어 폴리곤 반환 | routers/sam2.py:238-247 |
| TC-AISAM2-16 | track prev_polygon min_length=3 | PASS | [실동작] prev_polygon=2점 → 400 `"List should have at least 3 items after validation, not 2"` | schemas.py:177 |
| TC-AISAM2-17 | track 실추론 예외 → 이전폴리곤 fallback | PASS | [실동작-격리] 강제 예외 페이크 모델로 `_real_track` 호출 → `mock=true,mock_reason=empty_mask,score=0.5,polygon=prev_polygon` 그대로 반사 | routers/sam2.py:248-260 |
| TC-AISAM2-18 | track 마스크 없음 → 이전폴리곤 | PASS | [실동작-격리] 빈 마스크 페이크 모델 → `mock=true,polygon=prev_polygon` | routers/sam2.py:282-292 |
| TC-AISAM2-19 | track mock 동일 track_id 유지 | PASS | [실동작-격리] mock 모드 `track_id="trackXYZ"` → 응답 `track_id` 동일 반사, `polygon=prev_polygon` | routers/sam2.py:317-326 |
| TC-AISAM2-20 | segment/track mock 응답 mock=true source=mock | PASS | [실동작-격리] 위 01/19 결과 모두 `mock=True,source="mock"` 확인 | test_mock_indicator.py:33,49 |
| TC-AISAM2-21 | 로더 lazy: ultralytics import 안 함 | PASS | [정적] `sam2_loader.py`·`routers/sam2.py` 전문에 `"ultralytics"` 문자열 0건(Read 전수 확인) | test_sam2_meta.py:290 |
| TC-AISAM2-22 | AI_MOCK_MODE=true → None env_mock | PASS | [실동작-격리] `AI_MOCK_MODE=true` 격리 프로세스에서 `get_sam2_model()` → `None`, `get_sam2_mock_reason()` → `"env_mock"` | sam2_loader.py:32-37 |
| TC-AISAM2-23 | 로드 실패 → None reason=load_failed 전파 | PASS | [실동작-격리] `SAM2_MODEL_ID=nonexistent/...`(존재하지 않는 HF 모델 ID)로 로드 시도 → `KeyError` 캐치 후 `model=None,reason="load_failed"`(트레이스는 서버 로그에만) | sam2_loader.py:51-58 |
| TC-AISAM2-24 | ★미설치/로드실패인데 weights_missing 오표기 정정 | PASS | [실동작-격리] 위 23의 reason이 `"load_failed"`이지 `"weights_missing"`이 아님을 직접 확인 | routers/sam2.py:84-101 |
| TC-AISAM2-25 | from_pretrained에 ai_device 전달 | PASS | [실동작] 라이브 컨테이너 `AI_DEVICE=cpu`에서 `from_pretrained(model_id, device="cpu")` 호출로 실제 CPU 로드 성공(CUDA 기본값이었다면 AssertionError로 load_failed) — 코드(44-47) + 실동작 정합 | sam2_loader.py:44-47 |
| TC-AISAM2-26 | 정상 로드 시 싱글톤 | PASS | [실동작-격리] `get_sam2_model()` 2회 호출 → `동일 인스턴스 True`(id 동일) | sam2_loader.py:27-59 |
| TC-AISAM2-27 | (real) segment 포인트/박스 응답형식 | PASS | [실동작] 라이브 실가중치로 points/box 양쪽 `POST /infer/sam2/segment` → 200, `polygon`(4점 이상)+`score`(0~1) 필드 정상. 위 환경 실측 갱신 참조(게이팅 없음, 실제로 통과) | test_sam2_real.py:56,77 |
| TC-AISAM2-28 | (real) track prev_polygon 전파 | PASS | [실동작] 라이브 실가중치로 `/infer/sam2/track` box 프롬프트 → 200 실제 SAM2 컨투어 폴리곤(전파 확인) | test_sam2_real.py:94 |

## G-5 결과표 (VLM `/infer/vlm/verify-objects`)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---------|------|
| TC-AIVLM-01 | verify-objects known → verified=true conf 0.92 | PASS | [실동작] `expected_label="person"` → 200 `{verified:true,confidence:0.92,mock:true,source:"mock",mock_reason:"weights_missing"}` | routers/vlm.py:89-104 |
| TC-AIVLM-02 | unknown → verified=false conf 0.18 | PASS | [실동작] `"unicorn"` → `{verified:false,confidence:0.18}` | routers/vlm.py:93-100 |
| TC-AIVLM-03 | ★verify-objects 는 실 VLM 미구현 — 항상 mock | PASS | [실동작] `vlm_loader.get_vlm_model()`은 `AI_MOCK_MODE` 값과 무관하게 항상 `None`(코드: "real load not implemented yet") → mock_reason이 라이브(`weights_missing`)·격리 mock모드(`env_mock`) 양쪽 다 확인됨. 시계열(VlmClient→mock-server)은 별도 경로(G-10 실측, 이번 스코프 밖) | routers/vlm.py:38-69 |
| TC-AIVLM-04 | label 대소문자 무관 | PASS | [실동작] `"PERSON"` → `verified:true`(소문자 정규화 확인) | routers/vlm.py:93 |
| TC-AIVLM-05 | 빈 objects 배열 | PASS | [실동작] `objects:[]` → 400 `"List should have at least 1 item after validation, not 0"` | schemas.py:207 |
| TC-AIVLM-06 | objects[*].bbox 길이≠4 | PASS | [실동작] bbox=[1,2,3] → 400 | schemas.py:200 |
| TC-AIVLM-07 | 필수필드 누락 | PASS | [실동작] obj_id 누락 → 400 `"Field required"` | schemas.py:195-200 |
| TC-AIVLM-08 | invalid base64 → 400 | PASS | [실동작] `"###bad###"` → 400 `INVALID_IMAGE` | routers/vlm.py:45 |
| TC-AIVLM-09 | 크기초과 → 413 | PASS | [실동작] 15MB 페이로드 → 413(실배포 한도 10MB, TC-AISAM2-08과 동일 사유) | image_utils.py:42-43 |
| TC-AIVLM-10 | extra=forbid 추가필드 거부 | PASS | [실동작] `extra_field:1` 추가 → 400 `"Extra inputs are not permitted"` | schemas.py:204,211 |
| TC-AIVLM-11 | obj_id/expected_label 반사(순서 유지) | PASS | [실동작] 3개 객체(car/unicorn/dog) 순서대로 응답에 동일 순서 반영 확인 | routers/vlm.py:91-101 |
| TC-AIVLM-12 | 구 video-meta 엔드포인트 제거됨 | PASS | [실동작] `POST /infer/vlm/video-meta` → 404 | test_vlm.py:52 |
| TC-AIVLM-13 | mock WARN-once | PASS | [실동작] 컨테이너 가동 후 여러 차례 verify-objects 호출에도 `docker logs`상 `[VLM][MOCK]` WARN 라인 1건만 관측(프로세스 전역 플래그 가드 실증) | routers/vlm.py:72-80 |
| TC-AIVLM-14 (신규) | AiServerClient 단일 호출부 vs VlmClient 별개 | PASS | [정적] `AiServerClient.java:91` `uri("/infer/vlm/verify-objects")` vs `VlmClient.java:53` `DESCRIBE_PATH="/v1/videovlm/describe"`(생성자 라인 67 인근 별도 WebClient 빈) — 경로·클래스·베이스URL 전부 상이 확인. 단 `AiServerClient.verifyObjects()`는 BE 어느 Service/Controller에서도 호출부 0건(grep 확인) — 미배선 상태(참고, 본 TC 판정에는 영향 없음) | AiServerClient.java:91 / VlmClient.java:53,67 |

## G-6 결과표 (계약 정합 / 공통 인프라)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---------|------|
| TC-AICONTRACT-01 | ★COCO_ID2LABEL 80종·id 0~79 연속·중복 없음 | PASS | [정적] dict 80개 항목, key set={0..79}, value 80개 유일(파이썬으로 직접 검증) | detector_backend.py:67-85 |
| TC-AICONTRACT-02 | ★BE CocoClasses.LABELS ↔ ai-server COCO_ID2LABEL 정확 일치 | PASS | [정적] 두 목록을 직접 비교(파이썬 diff) → id 0~79 순서·문자열 완전 일치, mismatch 0건 | CocoClassesDriftTest.java:48-72 |
| TC-AICONTRACT-03 | coco_label_from_id 매핑 | PASS | [실동작-격리] `coco_label_from_id(0,None)="person"`, `(2,None)="car"`, `(79,None)="toothbrush"` | detector_backend.py:88-96 |
| TC-AICONTRACT-04 | id2label 우선, 미스 COCO fallback, 둘 다 미스 str(id) | PASS | [실동작-격리] `id2label={5:"custom_bus"}`일 때 5→"custom_bus"(오버라이드), 6→"train"(COCO fallback), `coco_label_from_id(100,None)="100"`(문자열화) | detector_backend.py:88-96 |
| TC-AICONTRACT-05 | coco_id_from_label 역매핑 안정성 | PASS | [실동작-격리] `"person"→0`, `"42"→42`(숫자문자열), 미지 라벨은 결정적 정수(재호출 동일값) + 0~79 비충돌 확인 | detector_backend.py:107-124 |
| TC-AICONTRACT-06 | 응답 스키마 계약 필드 불변 | **PARTIAL** | [실동작] YOLO predict 응답에 `detections/mock/source/mock_reason/success/message/error_code` 7필드 전부 확인(claim 그대로 참). 단 적대적 대조 결과 **동일 계약 원칙이 SAM2 Track·VLM verify-objects 에는 없음** — 두 응답 모두 `success/message/error_code` 자체가 스키마에 없고, BE 쪽은 한술 더 떠 `mock/source/mock_reason`까지 DTO에서 누락됨(→ G-ISSUE-22) | schemas.py:68-88(YOLO 한정 근거이므로 그 자체는 PASS, PARTIAL은 인접 발견 반영) |
| TC-AICONTRACT-07 | HTTP 경로 계약(prefix) | PASS | [실동작] `/infer/yolo/predict`·`/infer/sam2/segment`·`/infer/vlm/verify-objects` 3경로 전부 200/정상 응답 | main.py:62-64 |
| TC-AICONTRACT-08 | config 기본 ai_mock_mode=False | PASS | [정적] `Field(default=False, ...)` 확인 | config.py:26-33 |
| TC-AICONTRACT-09 | max_image_size_mb 범위(1~100) | PASS | [실동작-격리] `MAX_IMAGE_SIZE_MB=0` → `pydantic.ValidationError("Input should be greater than or equal to 1")`, `=200` → `("...less than or equal to 100")` | config.py:40 |
| TC-AICONTRACT-10 | cors_origins_list 콤마 파싱 | PASS | [실동작-격리] `"a,b, c"` → `["a","b","c"]` | config.py:63-64 |
| TC-AICONTRACT-11 | detector_backend 설정 없음(YOLOX 단일화) | PASS | [정적] `test_yolo_dispatch.py:162` `test_config에_detector_backend_설정이_없음` 확인 + `config.py` 전문에 해당 필드 부재 재확인 | test_yolo_dispatch.py:162 |
| TC-AICONTRACT-12 (신규) | ★좌표 정규화는 ai-server 책임 아님 — BE DetectionBoxNormalizer 전담 | PASS | [정적] `normalizeBbox`(clamp+유한성 가드+퇴화 스킵) 로직 확인 + `grep` 로 4개 호출부(`AutolabelOnlineService`·`YoloAutolabelStep`·`YoloLabelPersister`·`YoloTrackService`) 전부 실존 확인 | DetectionBoxNormalizer.java:50-81 |
| TC-AIINFRA-01 | /health → 200 {status:ok} | PASS | [실동작] `GET /health` → 200 `{"status":"ok"}` | main.py:67-69 |
| TC-AIINFRA-02 | X-Request-Id 응답 헤더 | PASS | [실동작] 헤더 미전송 시 12자리 hex 응답 헤더 자동 생성 확인(`b75ff6e6de15`) | request_id.py:34-48 |
| TC-AIINFRA-03 | 요청 X-Request-Id 있으면 반사 | PASS | [실동작] `X-Request-Id: my-custom-id-123` → 응답 헤더 동일 반사 | request_id.py:38-47 |
| TC-AIINFRA-04 | ★CRLF/unsafe id → 재생성(CWE-113) | PASS | [실동작] `"bad;id!@#space here"`(허용 안 되는 문자 포함) → 응답 헤더가 12자리 hex로 재생성됨(`9e2b2e736d3e`, 원본 값 미반사) | request_id.py:39-41,51-54 |
| TC-AIINFRA-05 | id 64자 초과/빈값 → 재생성 | PASS | [실동작] 65자 `"aaa...a"` → 12자리 hex로 재생성 | request_id.py:52-53 |
| TC-AIINFRA-06 | 미처리 예외 → 500(스택/경로 미노출) | **PARTIAL** | [실동작] `points=[[]]`(내부 좌표쌍 누락)로 SAM2 segment 요청 → 500 `{"error_code":"INTERNAL_ERROR","message":"서버 내부 오류"}`, `docker logs`에는 `IndexError: list index out of range`(sam2.py:306) 트레이스가 있으나 **HTTP 응답에는 미노출**(claim 자체는 참). 단 이 500을 유발한 원인이 **입력검증 누락**(points 스키마에 좌표쌍 최소 길이 제약 없음)임을 적대적 검증으로 확인해 등급 하향(→ G-ISSUE-21) | exceptions.py:65-69 |
| TC-AIINFRA-07 | 검증 에러에 스택/내부경로 미포함 | PASS | [실동작] 위 400 계열 응답 전부 `{error_code,message}` 2필드만, 스택/경로 없음 | exceptions.py:29-31,51-59 |
| TC-AIINFRA-08 | ErrorResponse extra=forbid | PASS | [정적] `ErrorResponse` 클래스 `extra="forbid"` 확인 + 모든 에러 응답이 실제로 2필드만 반환(실동작 상호 확증) | schemas.py:23-29 |

## 근거 드리프트

없음. 62건 전부 카탈로그가 인용한 `file:line`이 실제 코드 위치와 정확히 일치했다(정적 근거 확인분 전수 Read/Grep 대조).

## 이슈 상세

### [G-ISSUE-21] TC-AIINFRA-06 — SAM2 points 좌표쌍 길이 미검증 → 500(입력검증 누락, CWE-20)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 잘못된 형식의 `points` 입력(좌표쌍 요소 수 부족)은 스키마 검증 단계에서 깨끗한 400으로 거부되어야 한다. `box` 필드는 이미 `min_length=4, max_length=4`로 길이를 강제하는데(`schemas.py:153-155`), `points: list[list[float]] | None`은 바깥 리스트 길이만 암묵 검증될 뿐 **안쪽 각 좌표쌍이 정확히 2개 요소(x,y)를 가져야 한다는 제약이 없다**.
- **현재 동작(이슈 내용)**: `points=[[]]`(빈 내부 리스트) 또는 `points=[[1]]`(요소 1개)을 보내면 pydantic 검증은 통과하고, 이후 두 경로 중 하나에서 처리되지 않은 예외로 500이 발생한다.
  1. **실모델 경로**: `_real_segment`가 내부적으로 numpy 변환·predict를 시도하다 실패 → `except Exception` 가드가 이를 잡아 `_mock_segment(width, height, req, "empty_mask")`로 폴백을 시도하는데, 그 안에서 `cx, cy = req.points[0][0], req.points[0][1]`(`sam2.py:306`)이 다시 `req.points`(=`[[]]`)를 그대로 참조해 **IndexError**가 발생한다. 즉 "graceful fallback"으로 설계된 코드 경로 자체가 방어되지 않은 입력에 대해 2차로 크래시한다.
  2. 이 2차 예외는 `sam2.py`에 잡히지 않고 `exceptions.py:65-69`의 전역 `Exception` 핸들러까지 전파되어 500으로 응답된다(스택트레이스는 서버 로그에만 남고 HTTP 응답에는 노출되지 않음 — CWE-209는 해당 없음).
  - 실측 로그(`docker logs klid-ai-server`):
    ```
    File "/app/app/routers/sam2.py", line 220, in _real_segment
        return _mock_segment(width, height, req, "empty_mask")
    File "/app/app/routers/sam2.py", line 306, in _mock_segment
        cx, cy = req.points[0][0], req.points[0][1]
    IndexError: list index out of range
    ```
- **재현/확인 경로**:
  ```bash
  curl -s -w "\nHTTP:%{http_code}\n" -X POST http://localhost:19300/infer/sam2/segment \
    -H "Content-Type: application/json" \
    -d '{"image_b64":"<유효한 base64 JPEG>","points":[[]]}'
  # → 500 {"error_code":"INTERNAL_ERROR","message":"서버 내부 오류"}
  # points:[[1]] (좌표 1개만)도 동일하게 500 재현됨
  ```
- **영향**: 보안 정보유출은 없음(CWE-209 미해당, 메시지 일반화됨). 다만 CWE-20(Improper Input Validation) — 마땅히 400이어야 할 요청이 서버측 미처리 예외 경로를 타면서 ①불필요한 ERROR 레벨 로그 오염(정상 요청과 공격/오탐 구분이 로그 레벨만으론 어려워짐) ②"graceful mock fallback"이라는 설계 의도가 이 입력 한정으로 깨져 가용성 저하 가능성.
- **수정 방향(제안)**: `Sam2SegmentRequest.points`(및 `Sam2TrackRequest.prev_polygon`도 동일 패턴 점검)에 pydantic 필드 검증으로 각 내부 좌표가 정확히 2개 요소인지 강제(`box`가 이미 하는 것과 동일하게 예: `Annotated[list[float], Field(min_length=2, max_length=2)]` 원소 타입으로 재정의, 또는 `field_validator`로 `all(len(p) == 2 for p in points)` 단언). 아울러 `_mock_segment`의 fallback 분기 자체도 `req.points`가 비정상일 때를 대비해 방어적으로 작성하면 이중 안전망이 된다. ⚠ 구현하지 않음(검증 스코프).

### [G-ISSUE-22] TC-AICONTRACT-06 — SAM2 Track·VLM verify-objects, BE DTO가 ai-server의 mock 지표를 유실시킴 (설계 의도 무력화)

- **심각도**: HIGH
- **기대 동작(기대효과)**: ai-server는 SAM2 Track(`Sam2TrackResponse`, `schemas.py:180-188`)과 VLM verify-objects(`VlmVerifyResponse`, `schemas.py:219-225`) 응답에 **모두** `mock`/`source`/`mock_reason` 필드를 포함해 실제로 전송한다(본 검증 §G-4/G-5에서 실동작으로 확인 — 예: `{"track_id":"t1",...,"mock":false,"source":"model","mock_reason":null}`). SAM2 Segment 쪽 BE DTO(`Sam2Response.java`)는 이 지표를 그대로 받아 Javadoc에 명시된 대로 **"FE 가 'AI 모델 미로드 — 결과 신뢰 불가' 경고 + 자동 적용 차단을 수행"**하도록 설계돼 있다(CLAUDE.md: "SAM2 분할(...) mock 응답은 FE 자동적용 차단"). SAM2 Track도 동일한 신뢰성 보호가 있어야 정합적이다 — Track이 fallback되면 실제로는 "이전 폴리곤을 score=0.5로 그대로 반사"하는 가짜 추적 결과이기 때문이다(TC-AISAM2-17/18 실측).
- **현재 동작(이슈 내용)**: BE 클라이언트 DTO가 두 응답 모두에서 mock 지표 필드를 **아예 선언하지 않는다** — Jackson이 알 수 없는 JSON 필드를 조용히 무시하므로 컴파일·런타임 에러 없이 정보만 유실된다.
  - `backend/src/main/java/kr/co/cudo/authoring/common/client/dto/Sam2TrackResponse.java:14-18`
    ```java
    public record Sam2TrackResponse(
            @JsonProperty("track_id") String trackId,
            List<List<Double>> polygon,
            double score
    ) {}
    ```
    `mock`/`source`/`mock_reason` 필드 없음. 이 DTO를 소비하는 `Sam2TrackService.java:105-135`도 `aiRes.trackId()`/`aiRes.score()`/`aiRes.polygon()`만 읽고 mock 여부를 전혀 확인하지 않으며, 최종 FE 응답 DTO(`Sam2TrackResponseDto.TrackedItem`, `label/dto/Sam2TrackResponseDto.java:21-28`)에도 mock 필드가 없다 — **FE는 mock fallback(score=0.5, "이전 폴리곤 그대로")과 실제 저신뢰 AI 추적 결과를 구분할 방법이 전혀 없다.**
  - `backend/src/main/java/kr/co/cudo/authoring/common/client/dto/VlmVerifyResponse.java:15-22`도 동일하게 `results`만 있고 mock 지표 없음(다만 이 VLM verify-objects 자체가 BE 어디서도 호출되지 않는 미배선 상태라 — G-5 TC-AIVLM-14 비고 참조 — 실질 영향은 Track 대비 낮음).
- **재현/확인 경로**:
  ```bash
  # ai-server가 실제로 mock 지표를 보내는 것 확인 (mock 모드 격리 프로세스)
  docker exec -e PYTHONPATH=/app -e AI_MOCK_MODE=true -w /app klid-ai-server python3 -c "
  import asyncio, base64, io
  from app.config import reload_settings; reload_settings()
  from app.routers import sam2 as r
  from app.schemas import Sam2TrackRequest
  from PIL import Image
  img = Image.new('RGB',(50,50)); buf=io.BytesIO(); img.save(buf,format='JPEG')
  b64 = base64.b64encode(buf.getvalue()).decode()
  req = Sam2TrackRequest(track_id='t1', prev_image_b64=b64, next_image_b64=b64, prev_polygon=[[1,1],[2,2],[3,3]])
  print(asyncio.run(r.track(req)))
  "
  # → track_id='t1' polygon=... score=0.9 mock=True source='mock' mock_reason='env_mock'
  # 그러나 backend/.../dto/Sam2TrackResponse.java 는 이 mock/source/mock_reason 3필드를 애초에 매핑하지 않음(Read 확인)
  ```
- **영향**: 데이터 정합성/신뢰성 — SAM2 Track이 실패 시나리오(모델 예외·빈 마스크·env_mock)로 폴백해도 BE/FE 어느 계층도 이를 인지하지 못해, **가짜 추적 결과(이전 프레임 폴리곤 반복, score=0.5)가 정상 AI 추적 결과처럼 라벨 작업본에 병합될 위험**이 있다. 이는 CLAUDE.md에 명시된 "SFR-08-01(라벨링 정확도 향상) = VOS(추적+분할)" 기능의 신뢰성 전제를 훼손한다. 보안 카테고리는 아니며(CWE 해당 없음), 데이터 품질/기능 결함으로 분류.
- **수정 방향(제안)**: `Sam2TrackResponse.java`에 `mock`/`source`/`mockReason` 필드를 `Sam2Response.java`와 동일 패턴으로 추가하고, `Sam2TrackService`가 이를 읽어 `Sam2TrackResponseDto.TrackedItem`까지 전파(예: `mock` boolean 필드 추가)해 FE가 세그먼트와 동일하게 "AI 추적 결과 신뢰 불가" 경고·자동적용 차단을 수행하도록 배선. `VlmVerifyResponse.java`는 verify-objects가 실제 배선되는 시점에 함께 보완. ⚠ 구현하지 않음(검증 스코프).
