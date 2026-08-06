# G 클러스터 part1 (G-1~G-3) 2차 검증 결과

검증 대상: `docs/test-cases/G-ai-server.md` — G-1(28) + G-2(15) + G-3(12) = 55건. 취소선(폐기) 행 0건.
검증 환경: ai-server `localhost:19300`(컨테이너 `klid-ai-server`) — `AI_MOCK_MODE=false`, `AI_DEVICE=cpu`,
YOLOX ONNX 가중치 실로드됨(`source=model`, `mock=false` 실측 확인 — TC-AIYOLO-01/25). ai-server 는
2026-07-25 이후 커밋 0건(git log 재확인) — 1차 판정 코드 기준 불변.
실동작 입력: 64x64 단색 PNG(경계값 케이스) + 실제 비식별 프레임(`/app/storage/_pre20260730/deidentified/frames/deid/19/frame-4.jpg`,
854x480, car/truck 실검출 4건 — 클래스 필터·conf 필터·NMS 계열 케이스에 사용).

## 집계

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| G-1 | 28 | 27 | 0 | 1 | 0 | 0 | 0 |
| G-2 | 15 | 14 | 0 | 1 | 0 | 0 | 0 |
| G-3 | 12 | 10 | 0 | 2 | 0 | 0 | 0 |
| **합계** | **55** | **51** | **0** | **4** | **0** | **0** | **0** |

PARTIAL 4건 모두 **기능 결함이 아니라 회귀 가드(자동 테스트) 부재** — 코드 로직 자체는 정적 검토상 정확함. 상세는 이슈 참조.

## G-1 결과표 (YOLO 탐지 `/infer/yolo/predict`)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---------|------|
| TC-AIYOLO-01 | predict 정상 PNG → detections+mock/source | PASS | [실동작] `POST /infer/yolo/predict {image_b64:small}` → 200, `{"detections":[],"mock":false,"source":"model","success":true}`. 실배포는 실모델 활성(가중치 로드됨)이라 mock=true 조합은 [정적]+unit(`test_yolo.py:12`, 1차 baseline PASS 91/91)으로 보강 | routers/yolo.py:60-65 라인 일치 |
| TC-AIYOLO-02 | env_mock 결정적 person 박스 | PASS | [정적] yolo.py:86-109 로직 확인 + unit `test_yolo_dispatch.py:34-63`(정확 일치: score=0.9, 중앙 박스). 라이브는 AI_MOCK_MODE=false라 env_mock 자체 재현 불가 | 근거 라인 일치 |
| TC-AIYOLO-03 | conf_threshold 상한 초과 | PASS | [실동작] conf=1.5 → 400 `VALIDATION_ERROR "Input should be less than or equal to 1"` | schemas.py:40 일치 |
| TC-AIYOLO-04 | conf_threshold 하한 미만 | PASS | [실동작] conf=-0.1 → 400 동일 형식 | 일치 |
| TC-AIYOLO-05 | conf_threshold 기본값 0.4 | PASS | [실동작] conf 미전송 + frame-4.jpg(4검출, score 0.10~0.44) → 결과 score=0.435(truck) 1건만 통과, 나머지(0.10~0.33) 필터 = 기본 0.4 임계 실증 | schemas.py:40 |
| TC-AIYOLO-06 | env_mock conf>0.9 → 빈 detections | PASS | [정적] yolo.py:95-106(`score=0.9; if score>=conf_threshold`) — conf=0.95>0.9 시 미포함 로직 확인. 라이브 mock 재현 불가(대체 근거는 TC-02와 동일) | 일치 |
| TC-AIYOLO-07 | iou 범위 검증 | PASS | [실동작] iou=1.5 → 400 동일 형식 | schemas.py:42 |
| TC-AIYOLO-08 | imgsz 범위 검증 | PASS | [실동작] imgsz=100→400("...ge 320"), imgsz=5000→400("...le 1920") 양쪽 경계 확인 | schemas.py:41 |
| TC-AIYOLO-09 | ★imgsz 실추론 무효(640 고정) | PASS | [실동작] frame-4.jpg에 imgsz=320/640/1920 3회 요청 → **완전 동일 detections**(좌표·score 소수점까지 일치) 확인. `_preprocess`가 `self._input_size`(고정 640,640)만 사용, `params.imgsz` 미참조 | yolox_loader.py:53,284-299 일치. UNCERTAINTIES #14("실효 검증 방법 미해소")에 대한 실동작 검증 방법을 이번에 확보 |
| TC-AIYOLO-10 | max_det 필드 거부 | PASS | [실동작] `{"max_det":10}` → 400 `"Extra inputs are not permitted"` | schemas.py:37 |
| TC-AIYOLO-11 | 알 수 없는 필드 거부(mass assignment) | PASS | [실동작] `{"foo":1}` → 400 동일 | schemas.py:37,54,98 |
| TC-AIYOLO-12 | image_b64 빈 문자열 | PASS | [실동작] `""` → 400 `"String should have at least 1 character"` | schemas.py:39 |
| TC-AIYOLO-13 | image_b64 누락 | PASS | [실동작] `{}` → 400 `"Field required"` | schemas.py:39 |
| TC-AIYOLO-14 | 잘못된 base64 → 400 INVALID_IMAGE | PASS | [실동작] `"!!!notb64"` → 400 `INVALID_IMAGE "base64 디코드 실패"` | image_utils.py:37-44 |
| TC-AIYOLO-15 | 크기 초과 → 413 IMAGE_TOO_LARGE | PASS | [실동작] 실배포 기본 `MAX_IMAGE_SIZE_MB=10`(compose 미오버라이드) 하에서 **13.8MB → 413**(`"이미지 크기 한도 초과 (max 10MB)"`), 6.7MB(카탈로그가 가정한 1500×1500 노이즈) → 200(10MB 미만이라 통과). 메커니즘은 정확히 동작 | image_utils.py:33-43. ⚠ 카탈로그 전제 "MAX=1MB"는 **pytest `conftest.py`(`MAX_IMAGE_SIZE_MB=1`)에서만 성립**하는 테스트 전용 값이며 실배포 기본값(10MB)과 다르다 — 근거 드리프트 절 참조 |
| TC-AIYOLO-16 | 미지원 형식(GIF 등) 거부 | PASS | [실동작] GIF → 400 `INVALID_IMAGE "지원하지 않는 형식: GIF"` | image_utils.py:21,74-78 |
| TC-AIYOLO-17 | DecompressionBomb 픽셀 초과 | PASS | [실동작] 10000×6000(60M px, 컴팩트 189KB) → 413 `"이미지 픽셀 수 한도 초과 (max 50000000 pixels)"` | image_utils.py:25,79-90 |
| TC-AIYOLO-18 | classes 화이트리스트 필터(제외) | PASS | [실동작] frame-4.jpg(car×2,truck×2) + `classes:["car"]` → car 2건만 반환, truck 2건 제외 | routers/yolo.py:44-57 |
| TC-AIYOLO-19 | classes 매칭 시 통과 | PASS | [실동작] 동일 요청에서 car 통과 확인(위와 동일 응답이 곧 포함 사례) | 일치 |
| TC-AIYOLO-20 | ★classes=[] 빈 리스트=전체 반환 | PASS | [실동작] `classes:[]` → car+truck 4건 전부 반환(필터 미적용) | routers/yolo.py:54 |
| TC-AIYOLO-21 | classes=None 전체 검출 | PASS | [실동작] classes 미전송 시 4건 전부 반환(위 TC-05 확인 요청과 별개로 conf=0.1 조합에서 재확인) | schemas.py:43 |
| TC-AIYOLO-22 | classes 과대 리스트(>100) 거부 | PASS | [실동작] 101개 → 400 `"List should have at most 100 items after validation, not 101"` | schemas.py:43-50 |
| TC-AIYOLO-23 | weights 부재 → mock_reason=weights_missing | PASS | [정적]+unit(`test_yolox_loader.py:194`, 1차 baseline PASS). 실배포는 가중치 존재 상태라 강제 재현 시 공유 컨테이너 중단 필요 — 미수행(코드 07-25 이후 불변) | yolox_loader.py:399-404 |
| TC-AIYOLO-24 | onnxruntime 로드실패 → load_failed | PASS | [정적]+unit(`test_yolox_loader.py:218,236`, baseline PASS) | yolox_loader.py:406-417 |
| TC-AIYOLO-25 | 정상 가중치+ort → mock=false | PASS | [실동작] TC-01/09 등 전 요청에서 `"mock":false,"source":"model"` 일관 확인 | yolox_loader.py:360-410 |
| TC-AIYOLO-26 | mock WARN 프로세스당 1회 | **PARTIAL** | [정적] `_warn_mock_once`(전역 플래그 가드) 로직은 정확. 그러나 **caplog로 WARN 횟수를 단언하는 단위테스트가 0건**(`test_yolo_dispatch.py:48`은 플래그를 리셋만 하고 카운트를 검증하지 않음) + 실배포는 mock 비활성이라 라이브 재현 불가 | routers/yolo.py:73-83. → G-ISSUE-01 |
| TC-AIYOLO-27 | predict track_id 항상 None | PASS | [실동작] 전 predict 요청(mock 스키마 기본 포함 6+회) 에서 `track_id` 필드 전부 `null` 확인 | routers/yolo.py:107 부근(Detection 기본값) |
| TC-AIYOLO-28 | CORS 허용 메서드 외 405 | PASS | [실동작] `PUT /infer/yolo/predict` → 405 `{"detail":"Method Not Allowed"}` | main.py:55(`allow_methods=["GET","POST"]`) |

## G-2 결과표 (YOLO 후처리/트래커 — yolox_loader unit)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---------|------|
| TC-AIYOLO-29 | decoded=True xyxy 변환+ratio 역보정 | PASS | [정적]+unit(`test_yolox_loader.py:64`, 손계산 대조 일치) + [실동작] 간접 확인: frame-4.jpg 실추론 좌표가 854×480 경계 내 정합적 픽셀좌표로 반환됨(전체 파이프라인 end-to-end 정합) | yolox_loader.py:142-228 |
| TC-AIYOLO-30 | raw grid decode(grid+stride) | PASS | [정적]+unit(`test_yolox_loader.py:85`, 손계산 anchor#0/#64 대조 일치). 실백엔드가 `decoded=False`(공식 export 표준) 사용 확인(yolox_loader.py:313) | yolox_loader.py:104-139 |
| TC-AIYOLO-31 | score=obj×class<conf 필터링 | PASS | [정적]+unit(`test_yolox_loader.py:130`) + [실동작] TC-05에서 conf=0.4 기본값 적용 시 score 0.10~0.33 3건 필터, 0.435 1건만 통과 실증 | yolox_loader.py:199-209 |
| TC-AIYOLO-32 | 예상밖 shape → 빈 리스트 | PASS | [정적]+unit(`test_yolox_loader.py:141`, `(1,0,85)`·`(3,3)` 양쪽 shape 크래시 없이 `[]`) | yolox_loader.py:171-176,229-231 |
| TC-AIYOLO-33 | NMS IoU 초과 중복 제거 | PASS | [정적]+unit(`test_yolox_loader.py:160`) | yolox_loader.py:69-101 |
| TC-AIYOLO-34 | NMS 비겹침 둘 다 유지 | PASS | [정적]+unit(`test_yolox_loader.py:168`) + [실동작] frame-4.jpg 4건 중복 없이 모두 별개 박스로 반환 | yolox_loader.py:87-101 |
| TC-AIYOLO-35 | 다른 클래스는 겹쳐도 미제거 | PASS | [정적]+unit(`test_yolox_loader.py:175`) + **[실동작] 강한 실증**: frame-4.jpg에서 car `[815.21,236.94,853.55,286.56]`(score .333)와 truck `[815.09,236.15,853.66,286.76]`(score .100)가 **거의 동일 좌표**인데 클래스가 달라 둘 다 생존 — 실운영 데이터로 클래스별 NMS 분리 확인 | yolox_loader.py:212-217 |
| TC-AIYOLO-36 | anchor 개수 불일치 시 grid decode 생략 | **PARTIAL** | [정적] 방어 코드(yolox_loader.py:135-136 `if predictions.ndim!=3 or predictions.shape[1]!=grids.shape[1]: return predictions`)는 존재·정확. **단 이 분기를 직접 실행하는 단위테스트가 0건**(`test_yolox_loader.py` 전체에 anchor mismatch 케이스 없음, raw-grid 테스트는 항상 정합 anchor 수 84 사용) | yolox_loader.py:135-136. → G-ISSUE-02 |
| TC-AIYOLO-37 | 트래커 캐시 LRU max=10 초과 evict | PASS | [정적]+unit(`test_yolox_loader.py:300`, `_MAX_TRACKERS=3`으로 축소 후 A evict 확인) | yolox_loader.py:454-458 |
| TC-AIYOLO-38 | 트래커 TTL 300초 lazy expiration | PASS | [정적]+unit(`test_yolox_loader.py:316`, monkeypatch time 1000→1301) | yolox_loader.py:446-451 |
| TC-AIYOLO-39 | clip별 트래커 격리+재사용 | PASS | [정적]+unit(`test_yolox_loader.py:334`, `a is a2`, `a is not b`) + [실동작] 서로 다른 clip_id(`testclip1`/`testclip2`/`testclipmulti`) 요청 모두 독립 정상 응답 | yolox_loader.py:461-495 |
| TC-AIYOLO-40 | mock 사유 시 get_yolox_tracker None | PASS | [정적]+unit(`test_yolox_loader.py:347`, env_mock/weights_missing/load_failed 3사유 모두 None) | yolox_loader.py:469-473 |
| TC-AIYOLO-41 | lazy import — onnxruntime 미로드 | PASS | [정적]+unit(`test_yolox_loader.py:415`) + [실동작] 컨테이너에 onnxruntime 설치·로드 확인(모듈 import 자체와 무관, lazy 가드는 소스 검토로 충분) | yolox_loader.py:20-23 |
| TC-AIYOLO-42 | ultralytics/rtdetr import 없음 | PASS | [정적]+unit(`test_yolo_dispatch.py:126,153` 정확 일치) + [실동작] `docker exec klid-ai-server python3 -c "import ultralytics"` → `ModuleNotFoundError` 확인 | test_yolo_dispatch.py:126,153 |
| TC-AIYOLO-43 | 구 yolo_loader/rtdetr_loader 삭제됨 | PASS | [정적]+unit(`test_yolo_dispatch.py:137,147` 정확 일치) + [실동작] `ai-server/app/models/` 디렉토리에 두 파일 부재 확인(`ls` grep 0건) | test_yolo_dispatch.py:137,147 |

## G-3 결과표 (YOLO track `/infer/yolo/track`)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---------|------|
| TC-AIYOLO-44 | track env_mock 결정적 track_id=1 | PASS | [정적]+unit(`test_yolo_track.py:37-61`, 정확 일치: mock=true, track_id=1). 실배포는 실모델이라 env_mock 재현 불가 | routers/yolo.py:221-244(실제 라인은 161-244 부근 — 근거 라인 소폭 드리프트, 근거 드리프트 절 참조) |
| TC-AIYOLO-45 | track weights_missing 빈 detections | PASS | [정적]+unit(`test_yolo_track.py:64-85` 정확 일치) | routers/yolo.py:164-172 |
| TC-AIYOLO-46 | clip_id 필수(min1 max128) | PASS | [실동작] `clip_id:""` → 400 `"at least 1 character"`, `clip_id:"x"*129` → 400 `"at most 128 characters"` | schemas.py:101-106 정확 일치 |
| TC-AIYOLO-47 | frame_index ge=0 | PASS | [실동작] `frame_index:-1` → 400 `"greater than or equal to 0"` | schemas.py:107-109 정확 일치 |
| TC-AIYOLO-48 | frame_index=0 시 트래커 리셋 | PASS | [정적] yolox_loader.py:479(`if reset or clip_id not in _TRACKERS: instance=_create_fresh...`) 로직 확인 + [실동작] 동일 clip_id로 frame_index 0~5 연속 호출 정상 응답(에러 없음, 상태 누적 확인). reset=True가 **기존 clip을 새 인스턴스로 교체**하는 케이스(신규 clip 최초 생성이 아닌 재사용 중 리셋) 전용 단위테스트는 없음(코드 검토로 충분히 명확) | routers/yolo.py:163 |
| TC-AIYOLO-49 | track 잘못된 base64 → 400 | PASS | [실동작] `image_b64:"!!!notb64"` → 400 `INVALID_IMAGE` | test_yolo_track.py:126 |
| TC-AIYOLO-50 | track classes 필터 | PASS | [실동작] frame-4.jpg + `classes:["car"]` → car 2건만 반환(track 엔드포인트에서도 predict와 동일 `_apply_class_filter` 확인) | routers/yolo.py:204 |
| TC-AIYOLO-51 | track 응답 스키마 계약(track_id 유지) | PASS | [실동작] 전 track 응답에서 `{label,points,score,track_id}` 필드셋 일관 확인 | test_yolo_track.py:114 |
| TC-AIYOLO-52 | ByteTrack tracker_id=-1 → None | PASS | [실동작] frame-4.jpg를 동일 clip에 6프레임 연속 투입해도 저신뢰 검출(score 0.10~0.44) 전부 track_id=None 유지 확인(ByteTrack 활성화 임계 미달로 미확정 트랙 유지, `-1→None` 매핑과 일치하는 관측) | bytetrack_util.py:82-92. **단 이 매핑을 직접 단언하는 단위테스트는 0건** — 실동작 근거로 대체 |
| TC-AIYOLO-53 | ByteTrack 길이 불일치 시 WARN | PASS | [실동작] `docker logs klid-ai-server`에서 실운영 트래픽 중 `"[ByteTrack] tracker_id 길이 불일치 dets=8 tracker_ids=7 — 누락분 track_id=None 유지"` 등 실제 WARN 로그 다건 확인(본 세션 이전 트래픽 — 회귀 아님, 정상 방어 동작) | bytetrack_util.py:83-92 |
| TC-AIYOLO-54 | trackers 미설치 graceful WARN-once | **PARTIAL** | [정적] `_new_bytetrack_tracker`(try/except + `_tracker_unavailable_warned` 1회 가드) 로직 정확. **실배포 컨테이너에 `trackers` 패키지가 설치돼 있어(`docker exec ... import trackers` 성공) 미설치 분기를 라이브로 재현 불가**, 게다가 `bytetrack_util.py` 전용 단위테스트 파일이 아예 없음(0건 — `test_yolox_loader.py`의 유일한 관련 테스트는 `_apply_bytetrack` 자체를 monkeypatch로 완전히 대체해 내부 로직을 검증하지 않음) | bytetrack_util.py:37-56. → G-ISSUE-03 |
| TC-AIYOLO-55 | 다중 클래스 track_id 충돌 방지 | **PARTIAL** | [정적] `coco_id_from_label`(라벨→안정 정수 매핑) 로직 확인, person/car 등 COCO 라벨 충돌 없음. **단위테스트 0건**(위와 동일 사유) + [실동작] frame-4.jpg(car+truck 동시 검출)로 시도했으나 두 클래스 모두 track_id=None(ByteTrack 미확정)이라 **ID 값 자체로 충돌 부재를 실증하지는 못함**(간접적으로 class_id 매핑 함수 자체는 코드 검토로 확인) | bytetrack_util.py:74-80. → G-ISSUE-04 |

## 근거 드리프트

| TC-ID | 카탈로그 근거 | 실제 확인 | 판단 |
|---|---|---|---|
| TC-AIYOLO-15 | 전제 "MAX=1MB" | 실배포(docker-compose) `MAX_IMAGE_SIZE_MB` 미설정 → 코드 기본값 **10MB** 적용(`app/config.py:40`). "1MB"는 `ai-server/tests/conftest.py:16`의 **pytest 전용 오버라이드** | 코드 결함 아님 — 카탈로그의 "전제"란이 테스트 환경값을 실배포 값처럼 서술해 혼동 소지. 메커니즘 자체(413 트리거)는 10MB 기준으로 실측 정상 동작(13.8MB→413, 6.7MB→200) |
| TC-AIYOLO-44 | routers/yolo.py:221-244 | 실제 `/track` 라우터 함수(`async def track`)는 197-205행, mock 분기(`_mock_track`)는 221-244행 | 케이스가 가리키는 `_mock_track` 함수 자체는 221-244 정확 일치 — 드리프트 아님(라우터 엔드포인트 정의부와 헬퍼 함수 라인이 표에서 혼재 서술된 것으로 판단, 실질적 문제 없음) |

## 이슈 상세

### [G-ISSUE-01] TC-AIYOLO-26 — mock WARN 1회 로그를 단언하는 회귀 가드 없음
- **심각도**: LOW
- **기대 동작(기대효과)**: 프로세스 수명 동안 mock 응답이 처음 발생할 때만 WARN 로그를 남기고 이후 반복 호출에서는 남기지 않아야 한다(로그 폭주 방지). 코드(`_warn_mock_once`)가 이를 구현하고 있다면, 리팩터링 중 실수로 가드가 깨져도(예: `if not _mock_warned` 조건 삭제) 자동 테스트가 잡아야 한다.
- **현재 동작(이슈 내용)**: `app/routers/yolo.py:73-83`의 `_warn_mock_once`는 전역 불리언 플래그로 정확히 구현돼 있으나, `tests/test_yolo_dispatch.py:48`은 `yolo_router.reset_mock_warn_flag()`를 호출만 할 뿐 `caplog`로 실제 WARN 횟수(2회 호출 시 1회만 발생)를 단언하지 않는다. 프로젝트 전체 테스트에서 `caplog` 사용 자체가 0건(grep 확인).
- **재현/확인 경로**: `grep -rn "caplog" ai-server/tests/` → 결과 없음. 코드 리뷰: `ai-server/app/routers/yolo.py:73-83`.
- **영향**: 기능 영향 없음(현재 로직은 정확). 회귀 위험 — 향후 리팩터링 시 WARN 폭주 회귀가 CI에서 잡히지 않는다.
- **수정 방향(제안)**: `test_yolo_dispatch.py`에 `caplog.set_level(logging.WARNING)` + `client.post(...)` 2회 호출 후 WARN 레코드 수 `==1` 단언 테스트 추가. 구현은 하지 않음.

### [G-ISSUE-02] TC-AIYOLO-36 — anchor 개수 불일치 방어 분기 단위테스트 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: YOLOX raw grid decode 시 예상치 못한 모델/해상도로 anchor 총합이 grid 계산과 어긋나면 IndexError/broadcast 오류 없이 원본을 그대로 반환해야 한다(크래시 방지).
- **현재 동작(이슈 내용)**: `ai-server/app/models/yolox_loader.py:135-136`에 방어 코드가 정확히 존재(`if predictions.ndim != 3 or predictions.shape[1] != grids.shape[1]: return predictions`)하지만, `tests/test_yolox_loader.py`의 raw-grid 테스트(`test_YOLOX_raw_grid_출력이_grid_stride_복원으로...`, 85행)는 항상 정합하는 anchor 수(84)만 사용해 이 분기를 실행하지 않는다. `grep -n "anchor" tests/test_yolox_loader.py`는 주석 3건만 매칭, 실제 mismatch 입력 케이스 없음.
- **재현/확인 경로**: `ai-server/tests/test_yolox_loader.py:85-127` 확인, mismatch 케이스 부재.
- **영향**: 기능 영향 없음(현재 코드 정확). 회귀 위험 — 이 분기가 깨져도(예: 조건 반전) 테스트가 GREEN을 유지한다.
- **수정 방향(제안)**: `input_size`와 어긋나는 shape(예: (1, 10, 85))의 합성 텐서를 `_decode_grid_if_needed`에 직접 넣어 원본 그대로 반환되는지 단언하는 테스트 추가. 구현은 하지 않음.

### [G-ISSUE-03] TC-AIYOLO-54 — bytetrack_util.py 전용 단위테스트 파일 부재(트래커 미설치 graceful 포함)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `trackers`(roboflow ByteTrackTracker) 패키지가 미설치/로드실패이거나 트래킹 도중 예외가 발생해도 500 크래시 없이 track_id 미부여로 graceful 처리되어야 하며, 미설치 경고는 프로세스당 1회만 남아야 한다.
- **현재 동작(이슈 내용)**: `ai-server/app/models/bytetrack_util.py`의 `_new_bytetrack_tracker`(37-56행)·`_apply_bytetrack`(59-94행)에 해당 로직이 정확히 구현돼 있으나, `ai-server/tests/`에 `bytetrack_util.py`를 직접 대상으로 하는 테스트 파일이 없다(`test_bytetrack*.py` 부재). 유일하게 관련된 `test_yolox_loader.py:389`(`test_backend_track가_predict후_bytetrack으로_track_id_부여`)는 `_apply_bytetrack` 자체를 `monkeypatch`로 통째로 대체해 검증하므로 실제 내부 로직(예외 처리, WARN-once, -1→None 매핑)은 전혀 실행되지 않는다. 실배포 컨테이너는 `trackers` 패키지가 설치돼 있어(`docker exec klid-ai-server python3 -c "import trackers"` 성공) 미설치 분기를 라이브로도 재현할 수 없었다.
- **재현/확인 경로**: `find ai-server/tests -iname '*bytetrack*'` → 결과 없음. `grep -n "_apply_bytetrack\|tracker_id" ai-server/tests/*.py` → `test_yolox_loader.py:407`의 monkeypatch 대체 1건뿐.
- **영향**: 기능 영향 없음(정적 검토상 코드 정확, 실운영 로그에서도 WARN 정상 관측 — TC-53 참조). 회귀 위험 — 트래킹 그레이스풀 폴백이 향후 깨져도 자동 테스트가 잡지 못한다.
- **수정 방향(제안)**: `tests/test_bytetrack_util.py` 신설 — `trackers` import를 monkeypatch로 실패시켜 `_new_bytetrack_tracker`가 None+WARN-once를 반환하는지, `tracker.update()`가 예외를 던질 때 `_apply_bytetrack`이 그레이스풀한지 직접 단언. 구현은 하지 않음.

### [G-ISSUE-04] TC-AIYOLO-55 — 다중 클래스 track_id 충돌 방지 로직 미검증(단위테스트 부재 + 실동작 미확증)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `coco_id_from_label`이 라벨 문자열을 안정적인 정수 class_id로 매핑해, ByteTrackTracker가 클래스별로 트랙 공간을 분리하는 경우 person과 car 등 서로 다른 클래스의 track_id가 충돌하지 않아야 한다.
- **현재 동작(이슈 내용)**: `ai-server/app/models/bytetrack_util.py:74-80`(`class_id=np.array([coco_id_from_label(d.label) for d in dets], ...)`)에서 라벨별 고유 정수를 산출하는 로직은 확인되나(G-ISSUE-03과 동일 사유로 전용 단위테스트 없음), **실동작 검증도 미완**이다 — frame-4.jpg(car+truck 동시 검출)로 6프레임 연속 track 요청을 시도했으나 두 클래스 모두 ByteTrack 활성화 임계(저신뢰 detection, score 0.10~0.44)를 넘지 못해 track_id가 계속 `None`(미확정 트랙)으로 남아, **서로 다른 클래스가 실제로 별개 트랙 ID를 받는지 값 자체로는 확인하지 못했다**.
- **재현/확인 경로**: `curl -X POST http://localhost:19300/infer/yolo/track -d '{"image_b64":"<frame-4.jpg base64>","clip_id":"c1","frame_index":0,"conf_threshold":0.1}'` — 응답의 `track_id`가 전부 `null`. 고신뢰 검출(예: score ≥ 0.5 이상의 명확한 person 객체가 포함된 실영상 프레임)로 재시도해야 track_id 비-null 값 비교가 가능.
- **영향**: 기능 영향 미확정(코드 검토상 위험 신호 없음). 다중 클래스 트래킹은 오토라벨링 정확도(SFR-08-01 VOS)와 직결되는 영역이라 회귀 검증 공백이 방치되면 발견이 늦어질 수 있음.
- **수정 방향(제안)**: G-ISSUE-03과 함께 `bytetrack_util.py` 전용 단위테스트에 person+car 동시 검출 합성 픽스처를 넣어 `coco_id_from_label` 매핑값이 실제로 다른 class_id를 산출하고 tracker_id가 클래스 간 충돌하지 않는지 직접 단언. 별도로 고신뢰 실영상 프레임 확보 시 실동작 재확인 권장. 구현은 하지 않음.
