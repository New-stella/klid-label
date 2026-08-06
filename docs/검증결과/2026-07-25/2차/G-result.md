# G 클러스터 (ai-server + 외부 벤더 목업 계약) — 2차 검증 결과

> 163건 · 기준 실동작(ai-server 실모델 추론 + mock-server 실왕복) · 2026-07-31


---

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

---

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

---

# G 클러스터 part3 (G-7~G-10) 2차 검증 결과

> 대상: `docs/test-cases/G-ai-server.md` §G-7 KPST 비식별 목업(8) · §G-8 VLM 벤더 목업(4) ·
> §G-9 생성형 AI(genai) 증강 목업(21) · §G-10 BE↔외부 벤더 실배선 계약(13) = **46건**
> 폐기(`~~취소선~~`) 행 **0건**(해당 구간에 없음 → 집계 제외분 없음).
> 실행 환경: backend `localhost:18081`(HEAD `ca3c712b` 재빌드본, 02:42 KST) · mock-server `:9400` ·
> ai-server `:19300` · postgres(`public` 스키마).
> 본 에이전트 요청은 `g3v-*` 접두(project_name / request_id / Idempotency-Key)로 식별 가능하게 남겼다.
> 검증 중 **어떤 파일도 수정하지 않았고 빌드/테스트를 실행하지 않았다**(테스트는 파일:메서드명만 대조).

## 집계

| 판정 | 건수 | 비율 |
|------|---:|---:|
| PASS | 43 | 93.5% |
| PARTIAL | 3 | 6.5% |
| FAIL | 0 | 0% |
| BLOCKED | 0 | 0% |
| N/A | 0 | 0% |
| 확인필요 | 0 | 0% |
| **합계** | **46** | 100% |

| 절 | 건수 | PASS | PARTIAL | 실동작 검증 비율 |
|----|---:|---:|---:|---|
| G-7 (KPST 목업) | 8 | 6 | 2 | 7/8 실동작 |
| G-8 (VLM 목업) | 4 | 4 | 0 | 4/4 실동작 |
| G-9 (genai 목업) | 21 | 21 | 0 | 17/21 실동작 |
| G-10 (BE 실배선) | 13 | 12 | 1 | 4/13 실동작(나머지 정적+테스트 대조) |

**핵심 결론**
1. **외부연동 3종(KPST·VLM·genai)이 전부 mock-server 실왕복으로 동작하는 것을 실증**했다. 특히 1차에서
   미구현으로 판정됐던 **증강(genai) 위탁이 `HttpExternalAugmentClient` → mock `POST /api/genai/jobs`
   → webhook 4회 → 파생영상 생성까지 완주**한 로그가 남아 있다(§1차 이슈 대조).
2. **self-fill 경로는 발견되지 않았다.** 내부 목모드 플래그(`DEIDENTIFY_MOCK_MODE`,
   `authoring.augment.external.mode=noop`)는 모두 실효 off 이고, 1차 결함이던 자체 콜백 시뮬레이터
   (`DevAugmentCallbackSimulator`)는 코드베이스에서 제거됐다(grep 0건).
3. **위조 request_id 콜백은 VLM·genai 양쪽 모두 401 로 fail-closed**(실동작 확인).
4. 결함성 발견 3건은 전부 **문서/기대값 스테일 또는 목 서버 내부 상태 비대칭**이며 운영 경로 차단 결함은 없다.
   다만 **BE 가 벤더 `checksum` 을 선언만 하고 검증하지 않는** 무결성 갭(G-ISSUE-44)은 별도 조치 대상이다.

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 판정 | 2차 실측 | 결론 |
|------|------|------|------|
| **B-ISSUE-21** — VLM 외부 연동이 로컬 목업을 구조적으로 경유할 수 없어 실동작 검증 불가(WebClientConfig HTTPS-only 부팅 크래시) | 검증 불가 | `VLM_CLIENT_ENABLED=true` · `VLM_SERVICE_URL=http://klid-mock-server:9400` · `VLM_ALLOW_INSECURE_URL=true` 로 **기동 성공**, mock 로그에 `POST /v1/videovlm/describe` → `POST http://klid-backend:8080/api/v1/vlm/callback 200` 왕복 실증. 완화 경로는 `ProfileGatedUrlPolicy`(프로파일 allowlist + `ENV` 표식 + `@PostConstruct` assert)로 격리 | **✅ 해소** |
| **ENV-ISSUE-01 / E-ISSUE-02** — 증강 외부 연동 미구현(Noop 로그만, self-fill 의심) | 결함 확정 | `ActiveAugmentClientLogger` 부팅 로그 `active ExternalAugmentClient=HttpExternalAugmentClient`. 02:54:36~45 에 **backend→mock 실왕복 완주**(mock: `job accepted job_id=635750cf… request_id=AUG-fff3e013…-1 mode=I2I inputs=3`, webhook 4회 200 → BE `job succeeded outputCount=3` → `new video created rawSn=129 orgnlRawSn=126` → `AugmentFrameProducer ingested rawSn=129 frames=3 (external outputs)`) | **✅ 해소** — 산출물이 외부 응답 경로에서 옴(self-fill 아님) |
| **E-ISSUE-03** — `application-local.yml` 키 오중첩으로 dev 콜백 시뮬레이터 영구 비활성 | 결함 | 그 시뮬레이터(`DevAugmentCallbackSimulator`) 자체가 **삭제**됨(main 코드 grep 0건, 주석 참조만 잔존: `application-local.yml:49`). 오중첩 대상 키가 소멸 | **✅ 소멸(원인 제거)** — "고쳐짐"이 아니라 "그 경로가 없어짐" |
| 참고 — `WEBHOOK_HMAC_SECRET_AUGMENT`(1차 E-ISSUE-04) | 빈 값 → 401 | 값은 존재하나 `WebhookProtectedPaths.SIGNATURE_REQUIRED` 가 비어 **서명 필수 경로 0개**. genai 콜백은 벤더 계약상 무서명이고 대신 **발급 게이트(ledger)** 가 401 을 낸다(실동작 확인) | 무효화(구조 변경) |

---

## ★ 외부연동 계약 3자 대조표 (벤더규격 / mock-server / BE DTO)

### (A) genai 위탁 요청 — `POST /api/genai/jobs`

| 필드 | 명세서 v1.1 = mock `JobSubmitRequest`(schemas/genai.py:108-125) | BE `GenAiJobSubmitRequest`(dto:25-34) | 판정 |
|------|------|------|------|
| `request_id` | 필수 str(1~64) | ✅ 송신 | 일치 |
| `request_channel` | 필수 enum CONTROL/AUTHORING/PORTAL | ✅ `AUTHORING` 고정 | 일치 |
| `request_user_id` | 선택 str(64) | ✅ | 일치 |
| `evnt_type` | 필수 str(1~20) | ✅ | 일치 |
| `operation_type` | 필수 enum | ✅ `AUGMENT` 고정 | 일치 |
| `generation_mode` | 필수 enum | ✅ `I2I` 고정 | 일치 |
| `input_files[].sequence` / `.file_path` | 필수 | ✅ `GenAiInputFile(sequence,file_path)` | 일치 |
| `input_files[].checksum` | 선택 str(100) | **미송신(DTO 미선언)** | **갭(경미)** — 우리가 보내는 입력의 무결성 토큰 미제공 |
| `input_files[].source_file_id` | 선택 str(64) | **미송신** | 갭(경미) — 결과 짝짓기를 *순서*에만 의존하게 만드는 원인 |
| `prompt` | **필수** dict | ✅ `AugmentPrompts.of(augType)` | 일치 |
| `model_version_id` / `parameter_set_id` | 선택 | **미송신(DTO 미선언)** | 갭(경미, 현 스코프에서 불필요) |
| `callback_url` | 선택 str(500) | ✅ | 일치 |
| 헤더 `Idempotency-Key` | 선택(64) | ✅ `request_id` 와 동일 값 | 일치 |

### (B) genai 위탁 응답(202) — mock `JobAcceptedResponse` ↔ BE `GenAiJobAcceptedResponse`

| 필드 | mock | BE DTO | 판정 |
|------|------|------|------|
| `request_id` / `job_id` / `status` / `received_at` | 4필드 전부 | 4필드 전부 선언 + `request_id` echo·`status=RECEIVED`·`job_id` non-blank 검증(HttpExternalAugmentClient:164-181) | **완전 일치** |

### (C) genai 결과 webhook — mock `build_webhook_payload`(genai_sim.py:480-495) ↔ BE `GenAiCallbackRequest`

| 필드 | mock 송신 | BE DTO 선언 | 판정 |
|------|------|------|------|
| `request_id` / `job_id` / `status` / `progress` / `current_step` / `updated_at` | ✅ | ✅ (status 는 `RUNNING\|SUCCEEDED\|FAILED` 화이트리스트) | 일치 |
| `error_code` / `error_message` | FAILED 시 | ✅ | 일치 |
| `results[].generated_data_id` / `.media_type` / `.output_file_path` | ✅ | ✅ | 일치 |
| `results[].checksum` | ✅ sha256 실제 계산값(genai_sim.py:451) | **선언은 있으나 어디서도 읽지 않음**(grep: `GenAiCallbackService`·`AugmentResultService` 에 checksum 사용 0건) | **갭 → G-ISSUE-44** |
| `results[].media_metadata`(`mime_type`,`size_bytes`) | ✅ (genai_sim.py:452-455) | **미선언 → Jackson `ignoreUnknown` 으로 조용히 폐기** | **갭 → G-ISSUE-44** |

### (D) VLM describe — 요청/응답/콜백

| 방향 | mock 스키마 | BE DTO | 판정 |
|------|------|------|------|
| 요청 | `request_id`(선택) · `media{type,source_type,path,frame_policy{mode,framerate,selected_frames},duration_sec*}` · `callback_url`(HttpUrl 필수) | `VlmTimeseriesRequest{request_id, media{type,source_type,path,frame_policy{mode,framerate,selected_frames}}, callback_url}` | 일치 (`duration_sec` 는 **목 전용 확장**으로 BE 미송신 — 목이 명시) |
| 동기 응답 | `{request_id, status:"accepted"}` | `VlmTimeseriesResponse{request_id,status}` + echo/accepted 검증 | 일치 |
| 결과 콜백(성공) | `{request_id, status:"completed", results:[{start_sec,end_sec,description}]}` | `VlmResultRequest{request_id,status(completed\|failed),results[Segment{start_sec,end_sec,description}],error}` | **완전 일치** |
| 결과 콜백(실패) | `{request_id, status:"failed", error:{code,message}}` | `VlmError{code,message}` + `@AssertTrue` 상호조건 | **완전 일치** |

### (E) KPST 진행조회 — mock `retrieve_progress` ↔ BE `KpstProgressResponse`

| 필드 | mock 송신 | BE DTO | 판정 |
|------|------|------|------|
| `result` / `data.prjCount` / `data.prjStatus[]` | ✅ | ✅ | 일치 |
| `prjStatus[].prjId,prjName,progressRate,dsCount,dsStatus[]` | ✅ | ✅ | 일치 |
| `prjStatus[].prjState` | ✅ (2/3/5) | **미선언** | 의도된 무시 — BE 는 `dsStatus[].procState` 단일 축으로 판정(`KpstDeidentService:73-98` 명시). 도메인이 다른 두 코드를 섞지 않는 것이 옳음 |
| `prjStatus[].createTime/createId/exportPath` | ✅ | **미선언** | 무해(요청 시 우리가 보낸 값) |
| `dsStatus[].dsId,fileName,procState,progressRate,totalFrame,startTime,endTime` | ✅ | ✅ 전부(숫자는 boxed) | 일치 |
| 실패 sentinel `procState=99` | ✅ (경계 위반·원본 미열람 시) | `PROC_STATE_TERMINAL_FAILED = {3,4,99}` 로 **즉시 'F' 종결** | **양방향 정합** — 목의 fail-fast 가 BE 에서 실제로 효과를 낸다 |

---

## ★ self-fill 점검 결과

| 점검 축 | 결과 | 근거 |
|------|------|------|
| 내부 목모드로 외부 호출 우회하는 플래그가 **실효 true** 인가 | **없음** | `docker exec klid-backend env`: `DEIDENTIFY_MOCK_MODE=false` · `KPST_DEID_ENABLED=true` · `VLM_CLIENT_ENABLED=true` · `AUGMENT_EXTERNAL_MODE=http`. `NoopExternalAugmentClient` 는 `havingValue="noop"` 이라 미생성(부팅 로그가 `HttpExternalAugmentClient` 활성 확인) |
| 외부 응답 없이 값을 자체 생성하는 코드 | **없음(제거 확인)** | `DevAugmentCallbackSimulator` main 코드 grep 0건. 비식별 산출물은 mock 이 실제로 쓴 파일을 BE 가 회수, 증강 프레임은 `AugmentFrameProducer … (external outputs)` 로 외부 산출 경로에서 반입 |
| 외부 실패 시 조용히 성공 처리(fail-open) | **없음** | ①KPST: `procState∈{3,4,99}` → 즉시 `'F'` ②VLM: 신고 구간·경로 부재 시 **fail-closed 보류/예외** ③genai: `results` 없는 SUCCEEDED → `INVALID_INPUT`, 위탁건수≠수신건수 → `ERR_RESULT_COUNT_MISMATCH` 로 job FAILED |
| 위조/미발급 request_id 주입 | **401 차단(실동작)** | `POST /api/v1/genai/callback`(request_id=`g3v-forged-0001`) → **401 `발급되지 않은 request_id 입니다.`** / `POST /api/v1/vlm/callback`(`g3v-forged-vlm-0001`) → **401 동일** |
| **G-part2 HIGH(=목 폴백 결과가 실AI 결과와 구분 불가)의 G-10 확산 여부** | **G-10 연동에는 해당 없음** | `mock`/`source`/`mock_reason` 은 **우리 ai-server 자체 확장 필드**이고, G-10 이 다루는 외부 벤더(KPST·IntelliVIX·생성형AI)는 계약에 그런 필드가 아예 없다. 재확인 결과 `YoloResponse`(26-28행)·`Sam2Response`(21-23행)는 3필드를 **선언하고 있고**, 누락은 `Sam2TrackResponse`·`VlmVerifyResponse` 두 DTO에 한정된다 — **part2 소관, 본 회차 중복 보고하지 않음** |
| 다만 **선언은 있으나 사용하지 않는** 외부 무결성 값 | **1건 발견** | genai `results[].checksum` (→ G-ISSUE-44) |

---

## G-7 결과표 (KPST 비식별 벤더 목업 — 8건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-AIMOCK-01 | GET / → 200 "Connect" | PASS | [실동작] `curl :9400/` → `Connect`(200). [정적] `routers/deid.py:116-119` | — |
| TC-AIMOCK-02 | GET /health → 200 {status:ok} | PASS | [실동작] `{"status":"ok"}`. [정적] `app/main.py:103-105` | 근거 드리프트(카탈로그 `main.py:94-96` = 라우터 등록 구간) |
| TC-AIMOCK-03 | retrieve_progress `fileName` = 원본 입력파일 경로 | PASS | [실동작] `POST /project`(prj_id=5, name=`g3v-tc0304`, input_path=`/app/storage/raw/seed/`, files=[`sample-cctv-1080p.mp4`]) → `dsStatus[0].fileName` = **`/app/storage/raw/seed/sample-cctv-1080p.mp4`**(산출물명 아님). [정적] `deid.py:86-112` / `deid_sim.py:272-279` | 근거 드리프트 |
| TC-AIMOCK-04 | 산출물명 = `{원본stem}-mask{ext}` | PASS | [실동작] 완료 후 `export_path` 에 **`sample-cctv-1080p-mask.mp4`**(34,654,319B) 생성. mock 로그 `watermark burned … file=sample-cctv-1080p-mask.mp4` → `production completed prj_id=5 files=1`. 타임스탬프 세그먼트 없음. [정적] `deid_sim.py:220-269` | BE 실왕복분(rawSn=123)도 동일 규칙으로 `…/123/deid/sample-cctv-1080p-mask.mp4` 존재 |
| TC-AIMOCK-05 | MOCK_OUTPUT_BASE 미설정 → 산출물 미생성(fail-closed, WARN 1회) | **PARTIAL** | [정적] `deid_sim.py:1705-1713`(WARN 1회 + `ProductionOutcome(written=[])`). [테스트] `mock-server/tests/test_deid_output.py:354 test_output_base_미설정이면_파일이_생기지않는다_failclosed` | 기대결과 문언(미생성·WARN·응답 200)은 충족. **단 `failed=False` 로 반환돼 `procState=2`(완료)로 보고** — 형제 경로(`OUTPUT_DIR_REJECTED`→99)와 비대칭 → **G-ISSUE-41**. 현 배포는 값이 설정돼 있어 미도달 |
| TC-AIMOCK-06 | export_path 가 output_base 밖 → 파일 미생성 + WARN, 응답 200 유지 | PASS | [실동작] `export_path=/tmp/g3v-escape`(prj_id=7) → `POST /project` **200**, `/tmp/g3v-escape` **미생성**, WARN `deid output dir rejected export_path=/tmp/g3v-escape output_base=/app/storage/raw,/app/storage/deidentified` + `production failed reason=OUTPUT_DIR_REJECTED`, 이후 `procState=99`. [정적] `path_policy.py:54-82` / `deid_sim.py:1716-1725` | 근거 드리프트. 관측 불가하게 실패하지 않음 ✓ |
| TC-AIMOCK-07 | input_base 밖 원본은 복사하지 않고 **placeholder 로 대체** | **PARTIAL** | [실동작] `input_path=/etc/`,`files=[hostname]`(prj_id=8) → export 디렉터리 **빈 채로 생성**, placeholder 없음. WARN `deid source rejected(boundary) … 원본을 읽지 않고 산출 실패로 종결` + `production failed reason=OUTPUT_WRITE_FAILED` → `procState=99`. [정적] `deid_sim.py:334-353`(`_safe_source_path`) / `path_policy.py:133-145` / `deid_sim.py:212-218` 주석 "★ #3 — placeholder 산출물은 폐기됐다". [테스트] `test_deid_output.py:323 …placeholder를_쓰지않고_산출실패로_종결한다` | **보안 목적(임의 파일 노출·디스크 고갈 차단)은 달성**이며 현 동작이 더 엄격. 카탈로그 기대결과가 스테일 → **G-ISSUE-42** |
| TC-AIMOCK-08 | 기존 산출물 있으면 덮어쓰지 않음(O_EXCL, 멱등) | PASS | [실동작] 같은 `export_path` 로 `g3v-tc08-rerun`(prj_id=6) 재실행 → 산출물 **inode 245092 / mtime 1785434523 / size 34654319 전부 불변**, 로그 `deid output exists — skip(no-overwrite) file=sample-cctv-1080p-mask.mp4` + `production completed files=1`. [정적] `deid_sim.py:408-419`(`_create_exclusive`, `O_EXCL\|O_NOFOLLOW`) · `439-485`(`_copy_no_overwrite`) | 근거 드리프트 |

## G-8 결과표 (VLM 벤더 목업 — 4건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-AIMOCK-09 | verify·describe 즉시 accepted + 지연 콜백 | PASS | [실동작] `describe`(request_id=`g3v-tc09-ok-0001`) → **200 `{"request_id":"g3v-tc09-ok-0001","status":"accepted"}`**, 약 2초 후(`callback_delay_seconds=2.0`) mock 로그 `callback sent url=… request_id=g3v-tc09-ok-0001`. `verify`(`g3v-tc09b-verify-0001`)도 동일. [정적] `routers/vlm.py:180-237` | — |
| TC-AIMOCK-10 | callback_url 허용 호스트 밖 → 400, outbound 미발사 | PASS | [실동작] `callback_url=http://evil.example.com:8080/steal` → **400 `callback_url host is not allowed`**, WARN `callback_url rejected(not allowed host) request_id=g3v-tc10-ssrf-0001 host=evil.example.com`, **해당 request_id 의 콜백 발사 로그 0건**. [정적] `vlm.py:151-169` / `url_guard.py:28-33` | allowlist=`klid-backend,localhost,127.0.0.1` |
| TC-AIMOCK-11 | `fail` 접두 request_id → 동기 accepted, 콜백만 failed | PASS | [실동작] `fail-g3v-tc11-0001` → **동기 200 accepted**, 콜백 발사 확인(`callback sent … request_id=fail-g3v-tc11-0001`). 콜백 페이로드 `{status:"failed", error:{code:"INFERENCE_ERROR",…}}` 는 [정적] `vlm_sim.py:329-335,338-350` + [테스트] `tests/test_vlm.py:217 test_describe_실패트리거도_동기_accepted이고_failed콜백_발사` | **기대결과의 "동기 202"는 오기** — 실제·규격 모두 200(TC-AIMOCK-09 와 모순) → 드리프트 표 |
| TC-AIMOCK-12 | GET /v1/videovlm/status → 200 | PASS | [실동작] `{"status":"ok","service":"videovlm"}` 200. [정적] `vlm.py:241-244` | 근거 드리프트(카탈로그 228-231) |

## G-9 결과표 (생성형 AI 증강 벤더 목업 — 21건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-AIMOCK-13 | 유효 요청 → 202 RECEIVED | PASS | [실동작] `g3v-tc13-0001`(I2I, input 1건) → **202** `{request_id,job_id:7dd1b472…,status:"RECEIVED",received_at}`. [정적] `routers/augment.py:263-310` [테스트] `test_genai_jobs.py:100` | — |
| TC-AIMOCK-14 | I2I·I2V 인데 input_files 없음 → 400 REQUIRED_FIELD_MISSING | PASS | [실동작] **400** `{"error_code":"REQUIRED_FIELD_MISSING","message":"input_files 는 I2I·I2V 에서 1건 이상 필요합니다"}`. [정적] `augment.py:220-226` [테스트] `test_genai_jobs.py:113,123` | — |
| TC-AIMOCK-15 | T2I·T2V 는 input_files 없어도 202 | PASS | [실동작] `g3v-tc15`(T2I) → **202 RECEIVED**(job c30728cf…). [정적] `schemas/genai.py:75-87` [테스트] `test_genai_jobs.py:131` | — |
| TC-AIMOCK-16 | sequence 중복 → 400 INVALID_PARAMETER | PASS | [실동작] sequence `[1,1]` → **400 `input_files[].sequence 는 중복될 수 없습니다`**. [정적] `augment.py:228-232` [테스트] `test_genai_jobs.py:139` | — |
| TC-AIMOCK-17 | 단계별 webhook(10/50/90) + 완료(100) 에만 results | PASS | [실동작·BE 왕복] mock→BE 4회 200: BE 로그 `running … progress=10 step=PREPROCESS` → `progress=50 INFERENCE` → `progress=90 POSTPROCESS` → `job succeeded … outputCount=3`. mock 로그 `webhook sent url=http://klid-backend:8080/api/v1/genai/callback status=200 job_id=635750cf… attempt=1` ×4. [정적] `genai_sim.py:81-86,480-495,583-648` [테스트] `test_genai_webhook.py:98` | 진행 webhook 에 results 미포함(BE 가 RUNNING 분기에서 결과 처리 안 함) ✓ |
| TC-AIMOCK-18 | results 는 SUCCEEDED 에서만, 그 외 409 | PASS | [실동작] 신규 job `9dbd9b94…` 접수 직후 `GET …/results` → **409 `{"error_code":"STATE_CONFLICT","message":"결과는 SUCCEEDED 상태에서만 조회할 수 있습니다"}`**. 완료 후 동일 EP 는 200. [정적] `augment.py:334-349` [테스트] `test_genai_jobs.py:228` | — |
| TC-AIMOCK-19 | output_file_path 실재 + sha256 일치 | PASS | [실동작] 응답 `output_file_path=/app/genai-out/genai/7dd1b472…/001_frame-1_genai.jpg`, `checksum=cc5d12a2af114391d70fd2799cfd46ec065b7a1c57858b55039604a8dbdf8a9b`. 컨테이너 `sha256sum` 결과 **동일 해시**, 파일 260,360B 실존. [정적] `genai_sim.py:380-458` [테스트] `test_genai_jobs.py:191` | 이 checksum 을 **BE 는 검증하지 않는다** → G-ISSUE-44 |
| TC-AIMOCK-20 | cancel — 종결 상태는 409 | PASS | [실동작] SUCCEEDED job 취소 → **409 `이미 종료된 작업은 취소할 수 없습니다`**. RUNNING/RECEIVED 는 200 CANCELED(아래 27). [정적] `augment.py:358-386` [테스트] `test_genai_jobs.py:245,271` | — |
| TC-AIMOCK-21 | Idempotency-Key 동일 재요청 → 동일 job_id / 65자는 400 | PASS | [실동작] 같은 키 `g3v-idem-key-001` 로 **다른 request_id**(`g3v-tc21-different`)를 보내도 응답이 **최초 job 그대로**(`request_id=g3v-tc13-0001`, `job_id=7dd1b472…`, 동일 `received_at`). 65자 키 → **400 `Idempotency-Key 는 64자 이하여야 합니다`**. [정적] `augment.py:163-175,276-303` [테스트] `test_genai_jobs.py:312,329,337` | — |
| TC-AIMOCK-22 | OUTPUT_BASE 미설정 → FAILED(RESULT_SAVE_FAILED) | PASS | [정적] `genai_sim.py:396-400`(`raise JobExecutionError(RESULT_SAVE_FAILED)`) → `run_job` except → `_fail_job` → FAILED webhook. [테스트] `test_genai_webhook.py:360 test_HIGH3_출력base_미설정이면_FAILED_RESULT_SAVE_FAILED` | 런타임 env 는 `/app/genai-out` 설정돼 있어 실동작 재현 불가(컨테이너 재기동 금지) — **KPST 쪽 동일 상황(TC-05)과 달리 이쪽은 fail-closed 가 상태에도 반영됨** |
| TC-AIMOCK-23 | file_path 허용 루트 밖/상대경로/base 미설정 → 400 | PASS | [실동작] 3종 전부 **400 `input_files[].file_path 는 허용된 루트의 절대경로여야 합니다`**: ①`relative/x.jpg` ②`/etc/passwd` ③`/app/storage/../etc/passwd`(경로순회). [정적] `genai_sim.py:223-250` [테스트] `test_genai_webhook.py:313,327` · `test_genai_security_hardening.py:303,311` | — |
| TC-AIMOCK-24 | callback_url SSRF: host[:port] allowlist + 경로접두사 + 자기참조 차단 | PASS | [실동작] 3종 전부 **400 `callback_url 이 허용되지 않습니다(…목 자신은 금지)`**: ①`http://evil.example.com:8080/cb`(비허용 호스트) ②`http://localhost:9400/api/genai/_mock/reset`(**자기참조**) ③`http://klid-backend:9999/cb`(허용 호스트 + **비허용 포트**). WARN `callback_url rejected(url guard)` 기록. [정적] `genai_sim.py:161-220` [테스트] `test_genai_security_hardening.py:159-241` | 포트 구분 allowlist 동작 실증 |
| TC-AIMOCK-25 | 본문·prompt 크기 상한 초과 → 413/400 | PASS | [실동작] ①body 1.2MB(>1MiB) → **413 `GA-MEDIA-001` `요청 본문 크기가 허용 한도를 초과했습니다`** ②prompt 100KB(>64KiB, body 는 1MiB 미만) → **400 `INVALID_METADATA` `prompt 크기가 허용 한도를 초과했습니다`**. [정적] `augment.py:72-119,208-217` [테스트] `test_genai_security_hardening.py:242-303` | 두 상한이 각각 독립적으로 발화 확인 |
| TC-AIMOCK-26 | 처리 시점 재검증(TOCTOU) — 접수 후 심링크 치환 시 FAILED, 유출 없음 | PASS | [정적] `genai_sim.py:288-361`(`_open_source_nofollow` — `O_NOFOLLOW` + 열린 **fd 기준 fstat** 재확인) · `461-476`(`_revalidate_source` — 처리 시점 base 재검증 → `MODEL_EXECUTION_FAILED`). [테스트] `test_genai_security_hardening.py:98 test_F1_접수후_입력파일이_base밖_심볼릭링크로_바뀌면_유출되지_않고_FAILED` · `:129` · `:329(F7 크기 증가)` | 실동작 재현은 공유 볼륨에 심링크를 **직접 생성**해야 해서 미수행(파일 수정 금지 원칙 + 동시 구동 중인 다른 에이전트 파이프라인 오염 위험) |
| TC-AIMOCK-27 | 취소 확정 시 산출물 정리(고아 없음) | PASS | [실동작] RECEIVED 상태 job `9dbd9b94…` 취소 → **200 CANCELED**, `/app/genai-out/genai/` 에 **해당 job 디렉터리 미생성**(고아 0). [정적] `genai_sim.py:364-378`(`discard_results`) + `run_job` 의 `CancelledError`/종결 분기. [테스트] `test_genai_security_hardening.py:409 test_F11_취소된_작업의_산출물은_남지_않는다` | 산출물 **생성 이후** 취소 분기는 타이밍상 실동작 재현 불가 → 테스트 커버로 판정 |
| TC-AIMOCK-28 | genai_event_types 화이트리스트 밖 evnt_type 거부 | PASS | [실동작] 현 배포는 `MOCK_GENAI_EVENT_TYPES` **미설정** → 규격대로 검증 생략(임의 `evnt_type="WINTER"` 202 수락). 화이트리스트 분기는 [정적] `augment.py:178-184` + [테스트] `test_genai_jobs.py:397 test_허용목록_밖_evnt_type은_UNSUPPORTED_EVENT_TYPE` | 두 분기 모두 확인 |
| TC-AIMOCK-29 | 작업 저장소 상한 초과 시 FIFO 만료 | PASS | [정적] `config.py:192-199`(`genai_max_jobs` 기본 1000, FIFO 만료 명시). [테스트] `test_genai_security_hardening.py:270 test_F3_잡_수_상한을_넘으면_오래된_작업부터_만료된다` | 근거 드리프트(카탈로그 `config.py:179-186` = `genai_max_body_bytes`) |
| TC-AIMOCK-30 | 목 전용 보조 EP 정상 동작(명세서 밖) | PASS | [실동작] `GET /api/genai/_mock/jobs` → 200, 등록 job 전량 + `active_tasks`. `POST /_mock/jobs/{id}/status-sync` → 200(아래 31). [정적] `augment.py:389-441` [테스트] `test_genai_jobs.py:444` | — |
| TC-AIMOCK-31 | status-sync 는 수동 트리거, 대상 미설정 시 비활성 | PASS | [실동작] **`{"job_id":"7dd1b472…","sent":false,"target":null,"reason":"MOCK_GENAI_STATUS_SYNC_URL 미설정 … status-sync 비활성"}`**(200). 자동 발신 흔적 0건. [정적] `genai_sim.py:563-579` [테스트] `test_genai_webhook.py:183,203,228` | — |
| TC-AIMOCK-32 | 인증(401/403) 의도적 미구현 — 갭 아님 | PASS | [실동작] 본 회차 genai 요청 **전건이 무인증**이며 정상 202/400/409 응답. [정적] `augment.py:20`("인증 … 이번 스코프에서 의도적으로 미구현") · `schemas/genai.py:8-9` | 설계 의도대로 |
| TC-AIMOCK-33 | 방어 로그가 `docker logs` 로 관측 가능(stdout basicConfig) | PASS | [실동작] 본 회차에 유발한 WARN 이 전부 `docker logs klid-mock-server` 에 노출: `deid output dir rejected` · `deid source rejected(boundary)` · `callback_url rejected(not allowed host)` · `input path rejected(path guard)` · `[MOCK][GENAI] api error code=INVALID_PARAMETER`. [정적] `main.py:32-46` | 관측성 회귀 없음 |

## G-10 결과표 (BE ↔ 외부 벤더 실배선 계약 — 13건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-AIMOCK-34 | ★비식별 헬스체크는 벤더 루트(`/`)를 핑한다 | PASS | [실동작] mock 로그에 backend 컨테이너 IP(`172.18.0.5`)발 **`"GET / HTTP/1.1" 200 OK`** 가 주기적으로 기록(`/health` 는 `ControlNotifyHealthIndicator` 몫 — `ControlNotifyHealthIndicator.java:39`). BE `/actuator/health` → `"deidentifyHealth":{"status":"UP","details":{"service":"deidentify","mode":"kpst"}}`. [정적] `DeidentifyHealthIndicator.java:92-104` [테스트] `DeidentifyHealthIndicatorTest:88 실모드_핑은_벤더가_제공하는_루트경로로_요청한다` | 카탈로그 §G-6 주의문(두 `/health` 혼동 금지)과 정합 |
| TC-AIMOCK-35 | mock-mode=true → 외부 핑 없이 UP(mode=mock) | PASS | [정적] `DeidentifyHealthIndicator.java:77-83` [테스트] `DeidentifyHealthIndicatorTest:58 mock모드_활성시_외부핑없이_UP_mock` | 현 배포 `DEIDENTIFY_MOCK_MODE=false` 라 실동작 미재현(컨테이너 재기동 금지). **이 플래그가 실효 false 인 것이 대전제 #2 준수의 근거** |
| TC-AIMOCK-36 | 둘 다 off → DOWN(unconfigured, fail-closed) | PASS | [정적] `DeidentifyHealthIndicator.java:84-91` [테스트] `DeidentifyHealthIndicatorTest:121 mock도_KPST위탁도_아니면_핑없이_DOWN_설정오류` · `:184` | 동일(설정 변경 필요) |
| TC-AIMOCK-37 | 핑 예외 시 DOWN, 예외 simpleName 만 노출(CWE-209) | PASS | [정적] `DeidentifyHealthIndicator.java:105-112`(`e.getClass().getSimpleName()`) [테스트] `DeidentifyHealthIndicatorTest:104 실모드_서버타임아웃시_DOWN` | 스택트레이스·주소 미노출 확인 |
| TC-AIMOCK-38 | VlmUrlPolicy relaxed — local + 완화플래그 + 배포표식 없음일 때만 | PASS | [실동작] `VLM_ALLOW_INSECURE_URL=true`·local 프로파일에서 **평문 http + 사설 IP 목업으로 기동 성공 + 실왕복**(mock describe 200 ×N). [정적] `ProfileGatedUrlPolicy.java:121-133,145-151` / `ExternalUrlPolicy.java:80-82` [테스트] `VlmUrlPolicyTest:38,49` | — |
| TC-AIMOCK-39 | 허용 프로파일 밖/ENV=stg\|prd 면 기동 실패(fail-closed) | PASS | [정적] `ProfileGatedUrlPolicy.java:78-91`(`@PostConstruct` → `IllegalStateException`), allowlist `containsAll` + `ENV` 독립 축(`DEPLOYED_ENV_MARKERS`) [테스트] `VlmUrlPolicyBootGuardTest:22 prd_프로파일에서_완화_플래그가_켜져있으면_컨텍스트_기동이_실패한다` · `VlmUrlPolicyTest:78,97,136` | 혼합 프로파일·오타·미지정 자동 엄격 |
| TC-AIMOCK-40 | relaxed 에서도 링크로컬/메타데이터 대역 거부(CWE-918) | PASS | [정적] `ExternalUrlPolicy.java:143-153`(`isLinkLocalAddress()` + `169.254.` 접두) [테스트] `VlmUrlPolicyTest:160 링크로컬_메타데이터_대역은_완화_프로파일에서도_차단된다` | — |
| TC-AIMOCK-41 | relaxed 는 DNS 해석 실패 통과 / strict 는 거부 | PASS | [정적] `ExternalUrlPolicy.java:143-153`(`resolveQuietly` → null 이면 return) vs `169-181`(`UnknownHostException` → `IllegalStateException`) [테스트] `VlmUrlPolicyTest:178 해석되지_않는_컨테이너명은_완화_프로파일에서_계속_허용된다` | 컨테이너 서비스명 기동 보장 |
| TC-AIMOCK-42 | describe request_id echo 불일치/status≠accepted → EXTERNAL_API_ERROR | PASS | [정적] `VlmClient.java:154-169`(`validateResponse`) [테스트] `VlmClientTest:140 응답_request_id_echo_불일치_시_EXTERNAL_API_ERROR` · `:155 응답_status_accepted_아니면_EXTERNAL_API_ERROR` | ⚠ **동작 변경(Phase C-1)**: 논블로킹 제출로 바뀌어 이 예외는 스텝을 FAILED 시키지 않고 `VlmSubmitOutcomeRecorder.onSubmitFailed` → `SKIP_REASON_SUBMIT_FAILED` 감사행 + 재개(`VlmWithheldResumeRunner`)로 흐른다. 목이 echo 를 그대로 돌려주므로 실동작 주입은 불가 |
| TC-AIMOCK-43 | 4xx 비재시도(NonRetryableExternalException, 서킷 ignore) | PASS | [정적] `VlmClient.java:106,121-127` + `application.yml:500-507`(circuitbreaker `vlmClient.ignore-exceptions`) · `547-553`(retry `vlmClient.ignore-exceptions`) [테스트] `VlmClientTest:220 V1_400_형식오류는_재시도없이_1회요청_비재시도예외전파` · `:235(422)` | 근거 드리프트(카탈로그 `application.yml:512-520`) |
| TC-AIMOCK-44 | 타임아웃 2계층 — WebClient 10s 안쪽에 배치 블록 45s | **PARTIAL** | [정적] WebClient 10s 는 성립: `VlmClient.java:71-76,108`(`vlm.client.timeout-seconds:10`, `application.yml:633`). **그러나 45s 블록 계층은 존재하지 않는다** — `VlmTimeseriesStep` 은 Phase C-1 에서 논블로킹 제출(`subscribe`)로 전환됐고 `BLOCK_TIMEOUT`/`.block(...)` 이 전무하다(grep: `VlmTimeseriesStep`·`VlmClient` 에 0건, `45` 는 349행 **과거 서술 주석**뿐). 카탈로그 근거 `VlmTimeseriesStep.java:78` 은 현재 **javadoc 본문** | 실효 타임아웃 = **10s 단일** + retry 3회(exp backoff). UNCERTAINTIES **#13("블록 상한 45s ↔ 실효 10s")도 스테일** → **G-ISSUE-43** |
| TC-AIMOCK-45 | enabled=false(공통 기본) → SKIPPED / local 기본 true → 실 왕복 | PASS | [실동작] local: `VLM_CLIENT_ENABLED=true`(`application-local.yml:147`) → mock 로그 `POST /v1/videovlm/describe 200`(172.18.0.5) → `POST http://klid-backend:8080/api/v1/vlm/callback 200` **왕복 2회 실증**. [정적] 공통 `application.yml:626`(`${VLM_CLIENT_ENABLED:false}`) + `VlmClient.java:94-97` [테스트] `VlmClientTest:170 enabled_false_시_외부_호출_없이_SKIPPED_반환_NO_OP` · `VlmTimeseriesStepTest:97,332` | — |
| TC-AIMOCK-46 | 신고 구간(`DE_IDNTF_YN='F'`)이면 전송 직전 SKIPPED 보류 + 해소 시 재위탁 | PASS | [정적] `VlmTimeseriesStep.java:303-307`(경로 해석 **직전** `deidentReportGate.isUnderDeidentReport` → `recordVlmSkipped(SKIP_REASON_DEIDENT_REPORT)` + SKIPPED 반환, 외부 호출 0건) · `113`(재개 배선 키 상수) · `150-152`(`RESUMABLE_SKIP_REASONS`) · `VlmResumeBridge`/`VlmWithheldResumeRunner` 실재 [테스트] `VlmTimeseriesStepTest:113 비식별_신고_구간_영상은_외부_VLM_호출_0건이고_보류로_기록된다` · `:137` · `VlmWithheldResumeRunnerTest:67,105,143` | 근거 드리프트(카탈로그 `60-65,95,216-220`). 실동작 재현은 영상에 신고를 걸어야 해 다른 에이전트 파이프라인 오염 위험 → 미수행 |

---

## 근거 드리프트

카탈로그 `근거(file:line)` 가 현재 소스와 어긋난 항목(**총 10건**). 판정에는 영향 없으나 다음 최신화에서 정정 필요.

| TC | 카탈로그 근거 | 실제 위치 | 유형 |
|----|------|------|------|
| TC-AIMOCK-01 | `mock-server/app/routers/deid.py:101-105` | `deid.py:116-119`(`connect()`) | 행 이동 |
| TC-AIMOCK-02 | `mock-server/app/main.py:94-96` | `main.py:103-105`(`@app.get("/health")`) — 94-96 은 라우터 등록 | 행 이동 |
| TC-AIMOCK-03 | `deid.py:76-98` / `deid_sim.py:210-217` | `deid.py:86-112`(`_build_ds_status`) / `deid_sim.py:272-279`(`source_path_of`) | 행 이동 |
| TC-AIMOCK-04 | `deid_sim.py:162-165,193-207` | `deid_sim.py:220-269`(`MASK_SUFFIX`·`mask_name_from`) | 행 이동 |
| TC-AIMOCK-05 | `deid_sim.py:409-444` | `deid_sim.py:1705-1713` — 409-444 는 현재 `_create_exclusive`/`_copy_limited` | 대폭 이동(파일 1,700행+ 로 성장) |
| TC-AIMOCK-06 | `deid_sim.py:248-276,446-453` | `path_policy.py:54-82`(`resolve_output_dir`) + `deid_sim.py:1716-1725` | 모듈 분리(F-7 단일 원천화) |
| TC-AIMOCK-07 | `deid_sim.py:279-326,359-406` | `deid_sim.py:334-353`(`_safe_source_path`) + `path_policy.py:133-145` + `deid_sim.py:1560-1575` | 모듈 분리 + **기대값 자체 변경**(G-ISSUE-42) |
| TC-AIMOCK-08 | `deid_sim.py:328-346,368-373` | `deid_sim.py:408-419`(`_create_exclusive`) · `439-485`(`_copy_no_overwrite`) | 행 이동 |
| TC-AIMOCK-12 | `routers/vlm.py:228-231` | `vlm.py:241-244`(`status_check`) | 행 이동 |
| TC-AIMOCK-29 | `config.py:179-186` | `config.py:192-199`(`genai_max_jobs`) — 179-186 은 `genai_max_body_bytes` | **다른 설정을 가리킴** |
| TC-AIMOCK-43 | `application.yml:512-520` | `application.yml:500-507`(circuitbreaker) + `547-553`(retry) | 행 이동 |
| TC-AIMOCK-44 | `VlmTimeseriesStep.java:78` | 현재 **javadoc 본문**(코드 아님). 대응 코드 자체가 제거됨 | **근거 소멸**(G-ISSUE-43) |
| TC-AIMOCK-46 | `VlmTimeseriesStep.java:60-65,95,216-220` | `VlmTimeseriesStep.java:303-307`(게이트) · `113`(사유 상수) · `150-152` | 대폭 이동 |

기대값 자체가 어긋난 항목(근거 위치와 별개):

| TC | 카탈로그 기대값 | 실제 | 조치 |
|----|------|------|------|
| TC-AIMOCK-07 | "placeholder 18바이트로 대체" | placeholder 폐기 → **산출 실패(procState=99)** | G-ISSUE-42 |
| TC-AIMOCK-11 | "동기 **202** accepted" | 실제·규격 모두 **200**(TC-AIMOCK-09 는 200 으로 기술 — 카탈로그 내부 모순) | 기대값 200 으로 정정 |
| TC-AIMOCK-44 | "스텝은 45s 상한" | 45s 블록 계층 **부재**(논블로킹 전환) | G-ISSUE-43 (UNCERTAINTIES #13 도 동시 갱신) |

---

## 이슈 상세

### [G-ISSUE-41] TC-AIMOCK-05 — `MOCK_OUTPUT_BASE` 미설정 시 산출물 0건인데 목이 "완료(procState=2)"로 보고한다 (형제 경로와 비대칭)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 목이 "완료"를 보고하는 것은 **BE 가 회수 가능한 산출물이 최종 경로에 실재한다**는 뜻이어야 한다. `deid_sim.py` 스스로 이 원칙을 명문화하고 있다 — `OutputWriteResult` docstring(`deid_sim.py:376-393`)은 "아무 파일도 남기지 않는데 성공으로 보고돼 `production=SUCCEEDED`/`files=0`/`procState=2`(진행률 100) 라는 **거짓 완료**가 나왔다"를 폐쇄 대상 결함으로 기술하고, 형제 경로인 `OUTPUT_DIR_REJECTED`(export_path 경계 위반)는 `failed=True` → `procState=99` 로 보고한다.
- **현재 동작(이슈 내용)**: 허용 루트 자체가 **미설정**인 경우만 예외적으로 `failed=False` 로 빠져나간다.

  `mock-server/app/services/deid_sim.py:1705-1713`
  ```python
  if not output_base:
      global _base_unset_warned
      if not _base_unset_warned:
          logger.warning(
              "[MOCK][KPST] MOCK_OUTPUT_BASE 미설정 — 더미 비식별 출력 파일 미생성"
              "(fail-closed). e2e 시 BE 의 STORAGE_RAW_MOUNT_ROOTS 와 같은 값(콤마 구분)으로 설정하세요.")
          _base_unset_warned = True
      return ProductionOutcome(written=written)      # ← failed 기본값 False
  ```
  `run_production`(`deid_sim.py:1953-1964`)이 `outcome.failed` 만 보므로 `PRODUCTION_SUCCEEDED` → `proc_state_for` → **`procState=2`**. 바로 아래 형제 분기(`deid_sim.py:1716-1725`)는 같은 "쓸 수 없음"인데 `failed=True, reason="OUTPUT_DIR_REJECTED"` → **99**.
- **재현/확인 경로**(`MOCK_OUTPUT_BASE=""` 로 mock 을 기동한 환경에서):
  ```bash
  curl -s -X POST http://localhost:9400/project -H 'Content-Type: application/json' -d '{
    "project_name":"chk-nobase","creator":"qa","export_path":"/app/storage/raw/seed/x/deid",
    "input_path":"/app/storage/raw/seed/","files":["sample-cctv-1080p.mp4"],"is_img":0}'
  # 몇 초 뒤
  curl -s -X GET http://localhost:9400/retrieve_progress -H 'Content-Type: application/json' \
    -d '{"reqUserId":"qa","prjName":"chk-nobase"}'   # → dsStatus[].procState 관찰(현재 2 예상)
  ```
  대조군(현 배포에서 즉시 재현 가능): `export_path=/tmp/g3v-escape` → `procState=99`(본 회차 prj_id=7 실측).
- **영향**: 목이 완료를 보고하면 BE(`KpstDeidentService`)는 존재하지 않는 산출물을 회수하러 가 무결성 검증에서 탈락 → `DE_IDNTF_YN='F'` 로 종결하는데, 로그에는 "완료 후 회수 실패"만 남아 **원인(설정 누락)이 드러나지 않는다**. 게다가 `projectName`(=`raw{rawSn}`)이 이미 점유돼 재위탁이 409 로 막히는 조용한 고착이 된다 — 이 파일이 다른 경로들에서 제거하려 한 바로 그 실패 모드다. 스코프는 **목 서버 오설정 시에 한정**(운영 영향 없음), 다만 로컬·dev 검증 전체가 이 목을 경유하므로 디버깅 비용이 크다.
- **수정 방향(제안)**: `output_base` 미설정 분기도 `ProductionOutcome(written=[], failed=True, reason="OUTPUT_BASE_UNSET")` 로 통일한다. 단 **명시적 opt-out 인 `write_output_files=false`(`deid_sim.py:1931-1934`)와는 구분**해야 한다 — 그쪽은 "만들지 않기로 한 설정"이라 성공 no-op 이 옳고, 이쪽은 "설정 누락"이다. ⚠ **구현하지 않는다**(검증 회차 스코프 밖).

### [G-ISSUE-42] TC-AIMOCK-07 — 카탈로그 기대값(placeholder 대체)이 스테일: 현재는 산출 실패로 종결한다

- **심각도**: LOW (카탈로그 정합성 — 제품 결함 아님)
- **기대 동작(기대효과)**: 카탈로그 기대결과 = "target 파일이 원본 복사가 아닌 **18바이트 placeholder**".
- **현재 동작(이슈 내용)**: placeholder 산출은 **의도적으로 폐기**됐고 최종 경로를 선점하지 않은 채 산출 실패로 끝난다.

  `mock-server/app/services/deid_sim.py:212-218`
  ```
  ★ #3 — placeholder 산출물은 폐기됐다. 원본을 읽지 못하는 경우(부재/권한/허용 루트 밖/입력 마운트
    불일치) 구 구현은 18바이트 스텁을 최종 경로에 쓰고 완료(procState=2)로 보고했다. 그 파일은 BE
    무결성(≥512B + 컨테이너 시그니처)에서 탈락해 'F' 가 되는데, no-overwrite 라 그 이름은 이후 어떤
    재시도로도 대체되지 않는다(영구 고착). 지금은 산출 실패(procState=99) 로 종결한다 …
    유효 크기의 가짜 영상으로 대체하는 안은 채택하지 않았다: 읽지도 못한 원본을 '비식별 완료'로
    승인시키는 위장 산출물이 되기 때문이다(CWE-345).
  ```
  실측(본 회차 prj_id=8, `input_path=/etc/`): export 디렉터리는 **빈 채로 생성**, WARN 2줄 후 `production failed reason=OUTPUT_WRITE_FAILED` → `procState=99`.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:9400/project -H 'Content-Type: application/json' -d '{
    "project_name":"chk-inescape","creator":"qa","export_path":"/app/storage/raw/seed/chk/deid",
    "input_path":"/etc/","files":["hostname"],"is_img":0}'
  docker exec klid-mock-server ls -la /app/storage/raw/seed/chk/deid   # → 빈 디렉터리
  curl -s -X GET http://localhost:9400/retrieve_progress -H 'Content-Type: application/json' \
    -d '{"reqUserId":"qa","prjName":"chk-inescape"}'                   # → procState 99
  ```
- **영향**: TC 의 보안 목적(허용 루트 밖 임의 파일 노출·디스크 고갈 차단)은 **더 강하게** 충족된다. 다만 기대값 그대로 자동검증을 짜면 위양성 FAIL 이 난다.
- **수정 방향(제안)**: 카탈로그 기대결과를 "원본을 읽지 않고 **산출 실패(procState=99)** 로 종결, 최종 경로 미선점(placeholder 없음)"으로 정정. 회귀 가드는 `mock-server/tests/test_deid_output.py:323` 이 이미 보유. ⚠ **구현하지 않는다**.

### [G-ISSUE-43] TC-AIMOCK-44 — VLM 타임아웃 "2계층(10s/45s)" 전제가 소멸했다(논블로킹 전환). 카탈로그·UNCERTAINTIES #13 동시 스테일

- **심각도**: MEDIUM (문서·기대값 정합. 운영 동작은 개선된 상태)
- **기대 동작(기대효과)**: 카탈로그 = "WebClient 자체 10s(`vlm.client.timeout-seconds`) 안쪽에 배치 오케스트레이션 블록 45s(`BLOCK_TIMEOUT`)". UNCERTAINTIES #13 = "블록 상한 45s ↔ 실효 클라이언트 타임아웃 10s".
- **현재 동작(이슈 내용)**: **45s 블록 계층 자체가 없다.** `VlmTimeseriesStep` 은 Phase C-1 에서 논블로킹 제출로 전환됐다.

  `backend/.../batch/step/VlmTimeseriesStep.java:361-377` (발췌)
  ```java
  vlmClient.submitTimeseries(req)
      .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR, ...)))
      .subscribe(
          resp -> SubmitSignalDispatch.run(vlmSubmitScheduler, LOG_TAG, rawSn,
                  () -> outcomeRecorder.onAccepted(rawSn, requestId, resp)),
          err  -> SubmitSignalDispatch.run(vlmSubmitScheduler, LOG_TAG, rawSn,
                  () -> outcomeRecorder.onSubmitFailed(rawSn, markingSn, err)));
  ...
  return VlmTimeseriesResponse.submitted(requestId);   // ACK 도 기다리지 않는다
  ```
  검증: `grep -rn "BLOCK_TIMEOUT\|block(Duration"` → `VlmTimeseriesStep`·`VlmClient` **0건**(잔존은 `ControlNotifyClient.java:52` 의 별개 상수). `VlmTimeseriesStep.java` 안의 문자열 `45` 는 349행 **과거 동작을 설명하는 주석**뿐. 카탈로그가 가리키는 `VlmTimeseriesStep.java:78` 은 현재 javadoc 본문이다.
  따라서 실효 타임아웃은 **WebClient 10s 단일**(`VlmClient.java:71-76,108` + `application.yml:633 timeout-seconds: 10`) + `retry vlmClient`(max-attempts 3 · wait 1s · multiplier 2, `application.yml:547-553`)이며, 파이프라인 스레드는 **전혀 점유되지 않는다**.
- **재현/확인 경로**:
  ```bash
  cd backend && grep -rn "BLOCK_TIMEOUT\|\.block(" src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java \
      src/main/java/kr/co/cudo/authoring/common/client/VlmClient.java   # → 0건
  grep -n "timeout-seconds" src/main/resources/application.yml           # → 633: timeout-seconds: 10
  # 회귀 가드(실행하지 말 것 — 파일:메서드만 대조):
  # backend/src/test/.../VlmTimeseriesStepNonBlockingTest.java:106 ACK가_끝내_오지_않아도_제출호출이_파이프라인_스레드를_붙잡지_않는다
  ```
- **영향**: 문서 기준으로 "45s 안에 스텝이 실패한다"를 전제한 운영 판단·회귀 케이스가 어긋난다. 실패 경로도 바뀌었다 — 제출 실패는 **스텝 FAILED 가 아니라** `SKIP_REASON_SUBMIT_FAILED` 감사행 + `VlmSubmitPendingSweeper`/`VlmWithheldResumeRunner` 회수로 흐르므로, "45s 뒤 배치 FAILED"를 기다리는 관측은 영원히 오지 않는다.
- **수정 방향(제안)**: ①TC-AIMOCK-44 기대결과를 "실효 타임아웃 = WebClient 10s 단일 + retry 3회. 스텝은 ACK 를 기다리지 않으며(논블로킹) 미수신은 `SKIP_REASON_ACK_MISSING`/`SKIP_REASON_CALLBACK_MISSING` 로 회수" 로 교체하고 근거를 `VlmClient.java:71-76,108` / `VlmTimeseriesStep.java:361-381` 로 이동 ②UNCERTAINTIES #13 의 "블록 상한 45s" 서술 삭제(미확정은 IntelliVIX 실서버 대조만 남김). ⚠ **구현하지 않는다**.

### [G-ISSUE-44] (연관 TC-AIMOCK-19 / 3자 대조표 C) — BE 가 생성형 AI 산출물의 `checksum` 을 선언만 하고 검증하지 않는다 (`media_metadata` 는 아예 폐기)

- **심각도**: MEDIUM (CWE-345 데이터 진정성 검증 부재)
- **기대 동작(기대효과)**: 벤더가 `results[].checksum`(SHA-256)을 주는 이유는 **우리가 그 파일을 읽어 파생 프레임으로 반입하기 전에 무결성을 확인**하라는 것이다. 반입 경로는 NAS 공유 마운트라 전송 중단·부분 기록·교체가 실재하는 실패 모드다.
- **현재 동작(이슈 내용)**: 목(=명세서)은 실제 해시를 계산해 보낸다.

  `mock-server/app/services/genai_sim.py:446-457`
  ```python
  results.append({
      "generated_data_id": uuid.uuid4().hex,
      "media_type": media_type.value,
      "output_file_path": str(target),
      "checksum": _sha256_of(target),
      "media_metadata": {"mime_type": _MIME_BY_EXT.get(ext, "application/octet-stream"),
                         "size_bytes": target.stat().st_size},
  })
  ```
  BE `GenAiCallbackRequest.ResultItem`(`webhook/dto/GenAiCallbackRequest.java:96-117`)은 `checksum` 을 **선언만** 하고, `media_metadata` 는 **선언조차 없어** `@JsonIgnoreProperties(ignoreUnknown = true)` 로 조용히 버려진다. 소비 지점(`GenAiCallbackService.verifiedOutputPaths`, 214-237)은 `item.outputFilePath()` 만 쓴다:
  ```java
  for (GenAiCallbackRequest.ResultItem item : results) {
      artifactRootResolver.verifyExternalReadablePath(item.outputFilePath());  // 경로 경계만
      paths.add(item.outputFilePath());                                        // checksum 미사용
  }
  ```
  검증: `grep -n "checksum\|Checksum\|sha256\|mediaMetadata" GenAiCallbackService.java AugmentResultService.java` → **0건**.
- **재현/확인 경로**:
  ```bash
  cd backend && grep -rn "checksum" src/main/java/kr/co/cudo/authoring/webhook/ | grep -v "dto/"   # → 0건
  # 실동작 대조(본 회차 실측): mock 이 내려준 checksum 과 파일 해시는 일치하지만 BE 는 그 값을 읽지 않는다
  docker exec klid-mock-server sha256sum /app/genai-out/genai/<job_id>/001_frame-1_genai.jpg
  ```
- **영향**: 경로 경계(CWE-22)는 막지만 **내용 진정성은 확인하지 않는다**. 부분 기록·손상·교체된 산출물이 그대로 파생영상 프레임으로 확정되고(`AugmentFrameProducer`), 그 뒤에는 라벨링·검수·데이터마트까지 흘러간다. 같은 계약의 `input_files[].checksum`/`source_file_id` 도 **송신하지 않고 있어**(3자 대조표 A), 결과 짝짓기가 `LS_DATA_AUG_JOB_FILE` 의 **순서**에만 의존한다 — 순서가 어긋나면 다른 프레임에 남의 증강본이 붙는데(코드 주석도 이 위험을 명시), 그 방어가 "건수 일치" 하나뿐이다.
- **수정 방향(제안)**: ①`applySucceeded` 의 경로 되붙이기 직전에 `checksum` 이 존재하면 파일 해시와 대조하고 불일치 시 `ERR_RESULT_CHECKSUM_MISMATCH` 로 job FAILED(기존 `ERR_RESULT_COUNT_MISMATCH` 와 동일한 fail-closed 규약) ②`media_metadata` 를 DTO 에 선언해 최소한 `size_bytes` 를 실파일과 대조(선언 없이 버리면 벤더가 무엇을 보내는지 코드만 보고 알 수 없다) ③중기적으로 `input_files[].source_file_id` 를 송신하고 결과의 대응 필드로 짝짓기해 순서 의존을 제거. ⚠ **구현하지 않는다**(계약 변경은 벤더 협의 대상).
