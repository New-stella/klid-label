# G. ai-server (YOLO/SAM2/VLM) — 테스트 케이스

> 179 케이스 · 경로: `ai-server/`(YOLO/SAM2/VLM 추론) + `mock-server/`(외부 벤더 목업) + `backend/`(BE↔벤더 실배선 계약) · HTTP는 FastAPI TestClient(mock 기본, conftest `AI_MOCK_MODE=true`) · [← README](README.md) ※ 카운트 = `grep -cE '^\| ~*TC-'`(ID 취소선 폐기 행 포함, 2026-08-05 머지 회차 7 정정)

## 변경 이력

| 회차 | 일자 | 정정 | 신규 | 폐기 | 요약 |
|:--:|------|:--:|:--:|:--:|------|
| 1 | 2026-07-30 | 1건 | 48건 | 0건 | **신규 48 = 신설 섹션 G-7~G-10 전체 46건(`TC-AIMOCK-01~46`, 섹션 자체가 신설이라 행별 `(신규)` 태그 생략) + 기존 섹션 내 개별 추가 2건.** `ai-server/` 자체는 2026-07-25 이후 커밋 0건(재확인 완료, git log 실측) — G-1~G-4·G-6 기존 행은 내용 불변. 대신 ①BE 의 외부 VLM 실배선(`VlmClient`→mock-server, ai-server 경유 안 함) 확인으로 TC-AIVLM-03 근거 보강(정정 1건) ②`mock-server/`(genai 증강 목업 신설·KPST `retrieve_progress` 계약 확정·콜백 allowlist)와 `VlmUrlPolicy`/`DeidentifyHealthIndicator`(BE↔벤더 실배선) 를 다루는 신규 섹션 G-7~G-10 추가(46건) ③G-5/G-6 에 ai-server 자체 VLM 엔드포인트와 BE 외부 VLM 연동의 분리를 명시하는 신규 행 2건 추가 |
| 2 | 2026-08-03 | 53건 | 0건 | 0건 | **근거 `file:line` 전수 재확인 회차** — 163행 전수 대조, 정정 53건(라인 드리프트 51건 + 기대결과 변경 2건) / 폐기 0건 / UNRESOLVED 0건. ai-server↔mock-server 경로 오귀속 정정 1건(`TC-AIMOCK-29`: 접두사 없는 `config.py`가 ai-server 것으로 오독될 여지 → `mock-server/app/config.py`로 명시 + 라인 정정). 최대 드리프트는 `ai-server/app/routers/sam2.py`(SAM2 라우터 내부 함수 재배치로 15개 행이 엉뚱한 함수를 가리키고 있었음)와 `mock-server/app/services/{deid_sim,genai_sim}.py`(F-7/#3/#4/#5 보안 리팩터로 경로 판정 로직이 `path_policy.py`로 분리되고 파일 길이가 2~3배 증가). **동작 변경 2건**(TC-AIMOCK-07: KPST 원본 미독 시 "18바이트 placeholder 대체" → "산출 실패로 종결"·placeholder 완전 폐기 / TC-AIMOCK-44: VLM describe 위탁이 "배치 스레드 45초 블로킹"에서 "논블로킹 제출 + 별도 스위퍼 회수"(Phase C-1)로 전환)는 구 정책을 되살리지 않도록 상단 UNCERTAINTIES 절도 함께 정정. |
| 3 | 2026-08-03(3차) | 9건 | 9건 | 0건 | **전 구간(G-1~G-10, 163건) 재검증 회차 — 2차 HIGH 3건 전부 해소 확인.** ①TC-AIYOLO-49(YOLO track mock폴백 입력검증 스킵·conftest 강제설정 거짓통과, 2차 HIGH #4) — `_track_yolox` 선두 무조건 디코드로 해소, 회귀테스트 6종(`test_yolo_track_input_validation.py`) 신설 ②TC-AISAM2-13(SAM2 mock폴백 500, 2차 HIGH #5) — 좌표 스키마 레벨 400 차단(`_sanitize_*` 4종 헬퍼)으로 해소 ③TC-AISAM2-17(SAM2 track bbox try밖 500, 2차 HIGH #6) — `_polygon_bbox` 분리+`_prev_polygon_fallback` 로 해소, 적대 fuzz 18종 500 0건. **신규 MEDIUM 발견** — SSRF 대역 차단(`ExternalUrlPolicy`)이 IPv6 ULA(`fc00::/7`)·CGNAT(`100.64/10`) 대역을 놓쳐 relaxed/strict 양쪽 기동 통과(G-ISSUE-21, 실운영 이미지 프로브로 실증). **정정 9건**(TC-AIYOLO-27·49, TC-AIMOCK-11·12·38·39·43·44·45 — 근거 라인 드리프트 7건 + 기대결과 변경 2건, 동작 변경 아님) **신규 9건**(TC-AIYOLO-56~58, TC-AISAM2-29~34 — 2차 HIGH 하드닝 커밋 `dcdbb827`이 카탈로그에 미수록이던 계약을 케이스화: track 입력검증 대칭·검증→상태변경 순서·`clip_id` 패턴 비대칭·좌표 원소길이 강제·비유한좌표 거부·프롬프트 배열 상한·`track_id` 패턴·track 응답 polygon 하한 비대칭·mock 폴리곤 무클램프). 케이스 수 163→**172**. 판정: PASS 152·PARTIAL 4·FAIL 3·BLOCKED 4(전부 YOLOX ONNX 가중치 미배포 단일 사유)·N/A 0·확인필요 0. mock-server·ai-server 프로덕션 코드는 2차 이후 대부분 커밋 없어(mock-server) 또는 하드닝만 반영(ai-server) 기존 결함 다수가 코드 미수정으로 이월. |
| 4 | 2026-08-04 | 2건 | 4건 | 0건 | **G-ISSUE-21 / G-ISSUE-22(SSRF 대역 우회) 수정 반영 회차 — 코드 동작 변경.** `ExternalUrlPolicy` 의 대역 판정을 JDK 술어(`isSiteLocalAddress` 등)에서 **명시적 CIDR 바이트 비교**(`ReservedRange`)로 교체하고, 호스트 해석을 `getByName`(첫 주소만) → **`getAllByName`(전 주소)** 로 바꿨다. **정정 2건**(TC-AIMOCK-40 — 차단 집합이 링크로컬뿐 아니라 ULA·CGNAT·`fec0::/10` 까지 확장, 근거 라인 갱신 / TC-AIMOCK-41 — 근거 라인 갱신, "해석 실패는 relaxed 통과" 규약 자체는 불변) **신규 4건**(TC-AIMOCK-47 IPv6 ULA · 48 CGNAT · 49 다중 해석 주소 전수 검사 · 50 IPv4-mapped 언랩). 케이스 수 172→**176**. 회귀 가드는 `VlmUrlPolicyTest`(18건)·`AugmentUrlPolicyTest`(14건) 양쪽 대칭 — 판정기가 VLM/KPST/증강 3연동 공용이라 한쪽만 두면 비대칭 재발(DEV_FIX HIGH-1)을 못 잡는다. ⚠ relaxed(local/dev 완화)에서도 ULA·CGNAT 를 막는 것은 **의도된 강화**다 — 도커/사내 목업은 RFC1918 IPv4·loopback 만 쓰고 그 둘은 relaxed 에서 계속 허용되므로 개발 기동은 깨지지 않는다. |
| 5 | 2026-08-04(2차) | 6건 | 3건 | 0건 | **G-ISSUE-21/22 수정에 대한 리뷰 지적(HIGH 2 · MEDIUM 1) 보강 회차 — 코드 동작 변경.** ①**NAT64 well-known prefix `64:ff9b::/96` 언랩 추가**(RFC 6052) — `64:ff9b::a9fe:a9fe` 처럼 하위 32비트에 IPv4 를 임베드하면 `169.254.169.254`(AWS IMDS)·CGNAT·RFC1918 를 IPv6 표기로 위장해 IPv4 대역 규칙을 **통째로 우회**할 수 있었다. 언랩 함수를 `unwrapIpv4Mapped` → `unwrapEmbeddedIpv4` 로 확장(mapped·compatible·NAT64 3종). ②**IANA 특수목적 대역 4종 추가** — `192.0.0.0/24`(IETF 프로토콜 할당) · `198.18.0.0/15`(벤치마킹) · `224.0.0.0/4`+`ff00::/8`(멀티캐스트, v4/v6 대칭) · `240.0.0.0/4`(Class E, `255.255.255.255` 포함). 전부 relaxed 에서도 차단 — `UNSPECIFIED_V4`(0.0.0.0/8)와 같은 "과차단 방향이라 안전" 논리이며 도커/사내 목업이 쓰는 RFC1918·loopback 은 그대로 허용된다(실측: 배포 형상의 모든 base-url 이 localhost/127.0.0.1/컨테이너명이라 영향 0). ③**KPST 축 회귀 가드 신설** — 판정기가 3연동 공용인데 가드는 VLM·증강 2축에만 있었다(`KpstWebClientConfigTest` 7→14건). **신규 3건**(TC-AIMOCK-51 NAT64 언랩 · 52 IANA 특수목적 4종 · 53 KPST 축 대칭 가드) **정정 6건**(TC-AIMOCK-40·41·47·48·49·50 — 전부 근거 라인 드리프트, 규약 불변). 케이스 수 176→**179**. 회귀 가드 3축 대칭: `VlmUrlPolicyTest` 20건 · `AugmentUrlPolicyTest` 16건 · `KpstWebClientConfigTest` 14건. ⚠ **범위 밖(별도 이슈 이월)**: 부팅 시 1회 검증만 하고 매 요청 DNS 재해석은 재검증하지 않는 구조(DNS rebinding TOCTOU)는 이번 회차에서 다루지 않았다 — base-url 이 사용자 입력이 아니라 배포 설정값이라 현 설계가 방어 대상으로 선언하지 않은 범위이며, 코드 주석에 잔여위험으로 명시했다. |

## G-1. YOLO 탐지 (`/infer/yolo/predict`)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AIYOLO-01 | predict 정상 PNG → detections+mock/source | mock | 유효 base64 PNG | 200, detections[]+mock=true source="mock" success=true | integration | High | routers/yolo.py:60-65 |
| TC-AIYOLO-02 | predict env_mock 결정적 person 박스 | AI_MOCK_MODE=true | 64x64 PNG, conf 0.4 | detections 1건 person score=0.9 중앙, track_id=None | integration | High | routers/yolo.py:86-109 |
| TC-AIYOLO-03 | conf_threshold 상한 초과 검증오류 | mock | conf=1.5 | 400 VALIDATION_ERROR(ge0 le1) | unit | High | schemas.py:40 |
| TC-AIYOLO-04 | conf_threshold 하한 미만 | mock | conf=-0.1 | 400 | unit | High | schemas.py:40 |
| TC-AIYOLO-05 | conf_threshold 기본값 0.4 | mock | conf 미전송 | 정상(로그 conf=0.40) | unit | Med | schemas.py:40 |
| TC-AIYOLO-06 | env_mock conf>0.9면 빈 detections | env_mock | conf=0.95 | detections=[](0.9<0.95) | unit | High | routers/yolo.py:99-106 |
| TC-AIYOLO-07 | iou 범위 검증(0~1) | mock | iou=1.5/-0.1 | 400 | unit | High | schemas.py:42 |
| TC-AIYOLO-08 | imgsz 범위 검증(320~1920) | mock | 100/5000 | 400 | unit | Med | schemas.py:41 |
| TC-AIYOLO-09 | ★imgsz 실추론 무효(로더 640 고정) | 실모델 | imgsz=1920 | _input_size=(640,640)만, imgsz 무시 (→ UNCERTAINTIES #14) | unit | High | yolox_loader.py:53,284-299 |
| TC-AIYOLO-10 | ★max_det 파라미터 부재 | mock | body에 max_det | 400(extra=forbid) | unit | Med | schemas.py:37 |
| TC-AIYOLO-11 | 알 수 없는 추가 필드 거부(mass assignment 방어) | mock | {"foo":1} | 400 | security | High | schemas.py:37,54,98 |
| TC-AIYOLO-12 | image_b64 빈 문자열 | mock | "" | 400(min_length=1) | unit | High | schemas.py:39 |
| TC-AIYOLO-13 | image_b64 누락 | mock | 없음 | 400 | unit | High | schemas.py:39 |
| TC-AIYOLO-14 | 잘못된 base64 → 400 INVALID_IMAGE | mock | "!!!notb64" | 400 INVALID_IMAGE | integration | High | image_utils.py:37-44 |
| TC-AIYOLO-15 | 크기 초과 → 413 IMAGE_TOO_LARGE | MAX=1MB | 1500x1500(~6MB) | 413 | integration | High | image_utils.py:33-43 |
| TC-AIYOLO-16 | 미지원 형식(GIF/BMP/WEBP) 거부 | mock | GIF base64 | 400 INVALID_IMAGE | security | High | image_utils.py:21,74-78 |
| TC-AIYOLO-17 | DecompressionBomb 픽셀 초과 → 413 | mock | >50M px | 413 | security | Med | image_utils.py:25,79-90 |
| TC-AIYOLO-18 | classes 화이트리스트 필터 | env_mock(person) | classes=["car"] | detections=[](person 제외) | integration | High | routers/yolo.py:44-57 |
| TC-AIYOLO-19 | classes 매칭 시 통과 | env_mock | classes=["person"] | person 통과 | integration | High | routers/yolo.py:54-57 |
| TC-AIYOLO-20 | ★footgun: classes=[] 빈 리스트=전체 반환 | env_mock | classes=[] | 필터 미적용 | unit | High | routers/yolo.py:54 |
| TC-AIYOLO-21 | classes=None 전체 검출 | env_mock | 미전송 | 전체 | unit | Med | schemas.py:43 |
| TC-AIYOLO-22 | classes 과대 리스트(>100) 거부 | mock | 101개 | 400(max_length=100) | unit | Med | schemas.py:43-50 |
| TC-AIYOLO-23 | weights 부재 → mock_reason=weights_missing 빈 detections | AI_MOCK_MODE=false, 파일 없음 | 유효 PNG | mock=true reason=weights_missing detections=[](오염 방지) | integration | High | yolox_loader.py:399-404 |
| TC-AIYOLO-24 | onnxruntime 미설치/로드실패 → load_failed 빈 detections | ort import 실패 | 유효 PNG | mock=true reason=load_failed detections=[] | integration | High | yolox_loader.py:406-417 |
| TC-AIYOLO-25 | 정상 가중치+ort → 실백엔드 mock=false | weights+ort | 유효 PNG | source="model" mock=false | integration | High | yolox_loader.py:360-410 |
| TC-AIYOLO-26 | mock WARN 프로세스당 1회 | mock | 2회 호출 | WARN 1회 | unit | Low | routers/yolo.py:73-83 |
| TC-AIYOLO-27 | predict track_id 항상 None | env_mock | predict | track_id=None | unit | Med | routers/yolo.py:100-106(mock 미설정)+schemas.py:59(기본값None)+yolox_loader.py:225(실추론 명시 None) |
| TC-AIYOLO-28 | CORS 허용 메서드 외 405 | - | PUT predict | 405 | integration | Low | main.py:58 |

## G-2. YOLO 후처리/트래커 (yolox_loader — unit)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AIYOLO-29 | decoded=True xyxy 변환+ratio 역보정 | - | [cx,cy,w,h] 출력 | 정확 [x1,y1,x2,y2] 원본좌표 | unit | High | yolox_loader.py:142-228 |
| TC-AIYOLO-30 | raw grid decode(grid+stride) | - | decode_in_inference=False | grid+stride 복원 후 정확 박스 | unit | High | yolox_loader.py:104-139 |
| TC-AIYOLO-31 | score=obj×class<conf 필터링 | - | 저score 박스 | conf 미만 제거 | unit | High | yolox_loader.py:199-209 |
| TC-AIYOLO-32 | 예상밖 shape → 빈 리스트(크래시 금지) | - | shape(1,0)/이상 | [] | unit | High | yolox_loader.py:171-176,229-231 |
| TC-AIYOLO-33 | NMS IoU 초과 중복 제거·최고점수 유지 | - | 겹친 동일클래스 2박스 | 1박스 keep | unit | High | yolox_loader.py:69-101 |
| TC-AIYOLO-34 | NMS 비겹침 박스 둘 다 유지 | - | 분리 2박스 | 2박스 | unit | Med | yolox_loader.py:87-101 |
| TC-AIYOLO-35 | 다른 클래스는 겹쳐도 미제거(클래스별) | - | person+car | 둘 다 | unit | High | yolox_loader.py:212-217 |
| TC-AIYOLO-36 | anchor 개수 불일치 시 grid decode 생략 | - | grid 총합≠anchor | 원본 반환, IndexError 없음 | unit | Med | yolox_loader.py:135-136 |
| TC-AIYOLO-37 | 트래커 캐시 LRU max=10 초과 evict | - | 11개 clip_id | 최고참 제거 | unit | High | yolox_loader.py:454-458 |
| TC-AIYOLO-38 | 트래커 TTL 300초 lazy expiration | - | 301초 후 | 만료 제거 | unit | High | yolox_loader.py:446-451 |
| TC-AIYOLO-39 | clip별 트래커 격리+재사용 | - | 동일 clip 2회 | 동일 인스턴스 | unit | High | yolox_loader.py:461-495 |
| TC-AIYOLO-40 | mock 사유 시 get_yolox_tracker None | mock | tracker 요청 | None | unit | Med | yolox_loader.py:469-473 |
| TC-AIYOLO-41 | lazy import — onnxruntime 미로드 | - | 모듈 import | sys.modules에 없음 | unit | High | yolox_loader.py:20-23 |
| TC-AIYOLO-42 | 라이선스: ultralytics/rtdetr import 없음 | - | grep | 부재 | unit | Med | test_yolo_dispatch.py:126,153 |
| TC-AIYOLO-43 | 구 yolo_loader/rtdetr_loader 삭제됨 | - | import | ModuleNotFound | unit | Low | test_yolo_dispatch.py:137,147 |

## G-3. YOLO track (`/infer/yolo/track`)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AIYOLO-44 | track env_mock 결정적 track_id=1 | env_mock | clip_id, frame_index=0 | track_id=1 person mock=true | integration | High | routers/yolo.py:234-261 |
| TC-AIYOLO-45 | track weights_missing 빈 detections | 가중치 없음 | track | []mock=true reason=weights_missing | integration | High | routers/yolo.py:177-187,245-246 |
| TC-AIYOLO-46 | clip_id 필수(min1 max128) | mock | ""/129자 | 400 | unit | High | schemas.py:101-106 |
| TC-AIYOLO-47 | frame_index ge=0 | mock | -1 | 400 | unit | High | schemas.py:107-109 |
| TC-AIYOLO-48 | frame_index=0 시 트래커 리셋 | 실백엔드 | 0 | reset=True 새 핸들 | unit | High | routers/yolo.py:177 |
| TC-AIYOLO-49 (정정) | track 잘못된 base64 → 400 — **mock 사유와 무관** | env_mock / weights_missing / load_failed 전부 | invalid base64 | 400 INVALID_IMAGE. ⚠ 구 근거 `test_yolo_track.py:126` 만으로는 검증되지 않는다 — `conftest.py:17` 이 `AI_MOCK_MODE=true` 를 강제해 **env_mock 분기만** 타므로, 운영 형상(weights_missing)에서 검증이 통째로 스킵되던 2026-08-02 2차 HIGH(G-ISSUE-01)를 그 테스트가 통과시켰다(거짓 PASS). 판정 근거는 `_track_yolox` **선두 디코드**(프로덕션 코드)와 mock 사유를 monkeypatch 한 회귀테스트로 한다 | integration | High | routers/yolo.py:175 / test_yolo_track_input_validation.py:53,67,81,96 |
| TC-AIYOLO-50 | track classes 필터 | env_mock | ["car"] | person 제외 [] | integration | Med | routers/yolo.py:217 |
| TC-AIYOLO-51 | track 응답 스키마 계약(track_id 유지) | env_mock | track | YoloTrackResponse 필드 일치 | integration | Med | test_yolo_track.py:114 |
| TC-AIYOLO-52 | ByteTrack tracker_id=-1 → None | 실트래커 | 저신뢰 | track_id=None | unit | Med | bytetrack_util.py:82-92 |
| TC-AIYOLO-53 | ByteTrack 길이 불일치 시 누락분 None+WARN | 실트래커, dets 개수 ≠ tracker_id 개수 | track | 누락분 track_id=None + WARN(조용한 truncate 없음) | unit | Med | bytetrack_util.py:83-92 |
| TC-AIYOLO-54 | trackers 미설치 graceful WARN-once | 미설치 | track | 예외 미전파, WARN 1회 | unit | Med | bytetrack_util.py:37-56 |
| TC-AIYOLO-55 | 다중 클래스 track_id 충돌 방지 | - | person+car | coco_id_from_label 분리 | unit | Med | bytetrack_util.py:74-80 |
| TC-AIYOLO-56 (신규) | ★`/track` 과 `/predict` 는 같은 입력에 같은 상태코드를 낸다 — 형식·크기 게이트도 mock 사유 무관 | weights_missing / load_failed | GIF base64 · 10MB 초과 PNG | 두 엔드포인트 모두 GIF→400 INVALID_IMAGE, 초과→413 IMAGE_TOO_LARGE. 배포 형상(가중치 유무)에 따라 계약이 갈리지 않는다 | security | High | routers/yolo.py:175 / test_yolo_track_input_validation.py:81,96 |
| TC-AIYOLO-57 (신규) | ★입력 검증이 트래커 **상태 변경보다 먼저** 수행된다 | 실백엔드 | 진행 중 clip 에 깨진 base64 1회 | `get_yolox_tracker`(핸들 생성·`frame_index=0` 리셋·LRU/TTL 축출) 호출 전에 400 — 깨진 입력 한 번으로 진행 중 clip 의 track_id 연속성이 끊기거나 캐시(max 10)가 축출되지 않는다 | security | High | routers/yolo.py:169-177 / test_yolo_track_input_validation.py:182 |
| TC-AIYOLO-58 (신규) | ⚠`clip_id` 는 `track_id`(SAM2)와 달리 문자 패턴 제약이 없다 — 개행 포함 값이 200 으로 수락됨 | mock | `clip_id="a\nINJECT"` | **현행 동작 = 200 수락**(`schemas.py:101-106` 은 길이만 검사). `clip_id` 는 `routers/yolo.py:181-186,196-199`·`yolox_loader.py:451,458,484-489` 에서 로그 인자로 쓰이므로 로그 레벨을 INFO 로 올리면 CWE-117 이 활성화된다(현재는 앱 INFO 로그 미출력이라 미발현). SAM2 `TRACK_ID_PATTERN` 과의 비대칭 — 정책 확정 필요 | security | Med | schemas.py:101-106 vs schemas.py:166-168,205-211 |

## G-4. SAM2 (`/infer/sam2/segment`, `/track`)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AISAM2-01 | segment 포인트 → polygon | mock | points=[[x,y]] | 200 polygon 4점 score=0.95 mock=true | integration | High | routers/sam2.py:415-440 |
| TC-AISAM2-02 | segment 박스 → polygon | mock | box=[...] | polygon | integration | High | routers/sam2.py:426-429 |
| TC-AISAM2-03 | 프롬프트 미제공 중앙 폴백 | mock | 둘 다 없음 | 중앙 사각 폴리곤 | unit | Med | routers/sam2.py:434-436 |
| TC-AISAM2-04 | ★임계값/conf 스키마 부재 | mock | conf/threshold | 400(extra=forbid) | unit | Med | schemas.py:171-182 |
| TC-AISAM2-05 | box 길이≠4 | mock | [1,2,3] | 400 | unit | High | schemas.py:180-182 |
| TC-AISAM2-06 | image_b64 빈값/누락 | mock | "" | 400 | unit | High | schemas.py:174 |
| TC-AISAM2-07 | invalid base64 → 400 | mock | invalid | 400 INVALID_IMAGE | integration | High | image_utils.py:37-44 |
| TC-AISAM2-08 | 크기초과 → 413 | 1MB | 대용량 | 413 | integration | Med | image_utils.py:42-43 |
| TC-AISAM2-09 | mask → contour polygon | 실모델 | 사각 마스크 | 최대면적 contour | unit | High | routers/sam2.py:230-259 |
| TC-AISAM2-10 | 빈/비정상 마스크 → None | 실모델 | 빈 마스크 | None(mock fallback) | unit | High | routers/sam2.py:246-247 |
| TC-AISAM2-11 | contour 점<3 → skip None | 실모델 | pts<3 | None | unit | Med | routers/sam2.py:251-254 |
| TC-AISAM2-12 | ★score 0~1 clamp(음수→0) | 실모델 | 음수 score | max(0,min(s,1)) | unit | High | routers/sam2.py:280-297 |
| TC-AISAM2-13 | segment 실추론 예외 → mock fallback | 실모델 throw | segment | mock=true reason=empty_mask | unit | High | routers/sam2.py:313-316 |
| TC-AISAM2-14 | segment 마스크 없음 → mock fallback | 실모델 빈마스크 | segment | mock reason=empty_mask | unit | High | routers/sam2.py:328-331 |
| TC-AISAM2-15 | track prev_polygon bbox → 다음 세그멘테이션 | 실모델 | prev_polygon | bbox 프롬프트 predict | unit | High | routers/sam2.py:369-384 |
| TC-AISAM2-16 | track prev_polygon min_length=3 | mock | 2점 | 400 | unit | High | schemas.py:214-216 |
| TC-AISAM2-17 | track 실추론 예외 → 이전폴리곤 fallback | 실모델 throw | track | polygon=prev score=0.5 mock=true | unit | High | routers/sam2.py:379-384 |
| TC-AISAM2-18 | track 마스크 없음 → 이전폴리곤 | 실모델 빈마스크 | track | polygon=prev mock=true | unit | Med | routers/sam2.py:406-408 |
| TC-AISAM2-19 | track mock 동일 track_id 유지 | mock | track | track_id 반사 polygon=prev | integration | High | routers/sam2.py:443-455 |
| TC-AISAM2-20 | segment/track mock 응답 mock=true source=mock | mock | 요청 | mock=true source="mock" | integration | High | test_mock_indicator.py:33,49 |
| TC-AISAM2-21 | 로더 lazy: ultralytics import 안 함 | - | grep | 부재 | unit | Med | test_sam2_meta.py:290 |
| TC-AISAM2-22 | AI_MOCK_MODE=true → None env_mock | mock | 로드 | None reason=env_mock | unit | High | sam2_loader.py:32-37 |
| TC-AISAM2-23 | 로드 실패 → None reason=load_failed 전파 | throw | segment | reason=load_failed | unit | High | sam2_loader.py:51-58 |
| TC-AISAM2-24 | ★미설치/로드실패인데 weights_missing 오표기 정정 | 로드 실패 | mock_reason | load_failed(≠weights_missing) | unit | Med | routers/sam2.py:180-197 |
| TC-AISAM2-25 | from_pretrained에 ai_device 전달 | mock | 로드 | device 인자(cuda AssertionError 방지) | unit | Med | sam2_loader.py:44-47 |
| TC-AISAM2-26 | 정상 로드 시 싱글톤 | 실모델 | 2회 | 동일 인스턴스 | unit | Med | sam2_loader.py:27-59 |
| TC-AISAM2-27 | (real) segment 포인트/박스 응답형식 | 실 가중치 | 실추론 | polygon+score(게이트) | integration | Low | test_sam2_real.py:56,77 |
| TC-AISAM2-28 | (real) track prev_polygon 전파 | 실 SAM2 | 실추론 | polygon 전파 | integration | Low | test_sam2_real.py:94 |
| TC-AISAM2-29 (신규) | ★좌표 원소 개수를 스키마가 강제 — `points`/`prev_polygon` 원소는 정확히 2개 | - | `points=[[5]]` · `points=[[1,2,3]]` · `prev_polygon=[[1],[2],[3]]` | 전부 400 VALIDATION_ERROR. **fallback 이 재폭발해 500 이 되던 2026-08-02 2차 HIGH(G-ISSUE-41/42)의 근인을 앞단에서 제거한 계약**이므로 되돌리지 말 것 | security | High | schemas.py:153-155,175-179,214-216 / test_sam2_point_validation.py:59,69,79,95 |
| TC-AISAM2-30 (신규) | ★비유한 좌표(NaN/Infinity/1e400) 거부 | - | `box=[NaN,0,10,10]` · `points=[[Infinity,1]]` | 400 VALIDATION_ERROR "Input should be a finite number" — 통과하면 JSON 이 NaN 을 못 실어 200 응답의 `null` 좌표로 샌다 | security | High | schemas.py:148-151 / test_sam2_hardening.py:310,320,330,340 |
| TC-AISAM2-31 (신규) | ★프롬프트 배열 상한 — `points` ≤100, `prev_polygon` ≤1000 (BE `@Size` 와 일치, CWE-770) | - | 101개 / 1001개 | 400 VALIDATION_ERROR. 100개 경계값은 200 통과 | security | Med | schemas.py:157-161,175-179,214-216 / test_sam2_hardening.py:356,368,383 |
| TC-AISAM2-32 (신규) | ★`track_id` 문자 패턴·길이 제약(CWE-117 입력원 제거) | - | 개행 포함 · 65자 · `a/b` | 전부 400. 64자·`a.b:c-d` 는 200. 로그 출력 시점에도 `_safe()` 로 이중 방어 | security | High | schemas.py:163-168,205-211 / routers/sam2.py:38-46 / test_sam2_hardening.py:98,114,130,152,172 |
| TC-AISAM2-33 (신규) | ⚠`Sam2TrackResponse.polygon` 은 **하한이 없다**(빈 폴리곤 허용) — segment 응답(`min_length=1`)과 의도적 비대칭 | 이전 폴리곤 전체 해석 불가 | track fallback | 빈 `polygon` 200. 하한을 걸면 마지막 방어선(`_prev_polygon_fallback`)이 ValidationError=500 으로 터진다. 빈 값 차단은 BE `Sam2TrackService.validatePolygon` 책임 — **"일관성" 명목으로 통일하지 말 것** | unit | Med | schemas.py:222-228 (비대칭 근거는 segment 측 하한 테스트 test_sam2_hardening.py:399 / track 측 빈-폴리곤 허용 자체는 자동테스트 미커버 — 갭) |
| TC-AISAM2-34 (신규) | ⚠mock/fallback 폴리곤은 입력 좌표를 **clamp 없이 반사**한다 — 실모델 형상에서도 `empty_mask` fallback 경유로 도달 | 실모델 + 마스크 미검출 | 1x1 이미지 + `box=[10,10,60,60]` · `box=[0,0,0,0]` | **현행 동작** = 이미지 밖 폴리곤 `[[10,10],[60,10],[60,60],[10,60]]` / 영면적 `[[0,0]×4]` 를 200 으로 반환. `AI_MOCK_MODE` 없이도 재현되므로 "mock 전용 결함"이 아니다. BE(`Sam2SegmentService`)가 `untrusted()`+`validatePolygon` 으로 흡수하나 ai-server 직접 소비자에겐 방어 없음 — `CLAUDE.md` ★3(AI 검출 응답 = clamp)와 불일치, 정책 확정 필요 | unit | Low | routers/sam2.py:426-437 |

## G-5. VLM — ai-server 자체 `/infer/vlm/verify-objects` (객체 검증, 시계열 아님)

> ★ 중요 구분(2026-07-30 재확인): 이 절의 `/infer/vlm/verify-objects` 는 ai-server 가 직접 노출하는 **객체 단위 검증**(YOLO/SAM2 검출 결과의 라벨 정합성 확인) 엔드포인트로, `AiServerClient.verifyObjects`(`backend/.../common/client/AiServerClient.java:91`)만 호출한다. **영상 단위 시계열 메타(VLM describe)는 이 경로를 전혀 쓰지 않는다** — BE 는 `VlmClient`(`backend/.../common/client/VlmClient.java`)로 `vlm.client.url`(기본 `http://localhost:9400`, ai-server 의 9300 이 아니라 **mock-server**)를 직접 호출한다. 즉 시계열 통합은 ai-server 를 경유하지 않고 BE→mock-server 로 바로 나간다. 이 축의 계약 케이스는 아래 G-10 참조.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AIVLM-01 | verify-objects known → verified=true conf 0.92 | mock | expected_label="person" | verified=true 0.92 mock=true | integration | High | routers/vlm.py:89-104 |
| TC-AIVLM-02 | unknown → verified=false conf 0.18 | mock | "unicorn" | false 0.18 | integration | High | routers/vlm.py:93-100 |
| TC-AIVLM-03 | ★ai-server verify-objects 는 실 VLM 미구현 — 항상 mock 반환 (BE 외부 VLM 시계열 연동과 별개) | - | verify-objects | mock=true source=mock reason∈{env_mock,weights_missing,not_implemented} — **UNCERTAINTIES #13 은 이 엔드포인트에 한해서만 유효**. 영상 단위 외부 VLM 시계열 연동(`POST /v1/videovlm/describe`)은 실배선 확인됨(G-10 TC-AIMOCK-42~46) | integration | High | routers/vlm.py:38-69 |
| TC-AIVLM-04 | label 대소문자 무관 | mock | "PERSON" | verified=true | unit | Med | routers/vlm.py:93 |
| TC-AIVLM-05 | 빈 objects 배열 | mock | [] | 400(min_length=1) | unit | High | schemas.py:251 |
| TC-AIVLM-06 | objects[*].bbox 길이≠4 | mock | [1,2,3] | 400 | unit | High | schemas.py:244 |
| TC-AIVLM-07 | 필수필드 누락 | mock | obj_id 누락 | 400 | unit | High | schemas.py:239-244 |
| TC-AIVLM-08 | invalid base64 → 400 | mock | invalid | 400 INVALID_IMAGE | integration | High | routers/vlm.py:45 |
| TC-AIVLM-09 | 크기초과 → 413 | 1MB | 대용량 | 413 | integration | Med | image_utils.py:42-43 |
| TC-AIVLM-10 | extra=forbid 추가필드 거부 | mock | 추가 필드 | 400 | security | Med | schemas.py:240,248 |
| TC-AIVLM-11 | obj_id/expected_label 반사(순서 유지) | mock | 다수 objects | 입력 순서대로 | unit | Med | routers/vlm.py:91-101 |
| TC-AIVLM-12 | 구 video-meta 엔드포인트 제거됨 | - | 해당 경로 | 404 | integration | Low | test_vlm.py:52 |
| TC-AIVLM-13 | mock WARN-once | mock | 2회 | WARN 1회 | unit | Low | routers/vlm.py:72-80 |
| TC-AIVLM-14 (신규) | ai-server verify-objects 는 AiServerClient 단일 호출부만 사용, BE 의 VlmClient(외부 시계열)는 이 URL 을 참조하지 않는다 | - | 설정/코드 대조 | `AiServerClient.java:91` `uri("/infer/vlm/verify-objects")` vs `VlmClient.java:53` `DESCRIBE_PATH="/v1/videovlm/describe"` — 경로·베이스URL·클라이언트 클래스 전부 상이 | unit | Med | AiServerClient.java:91 / VlmClient.java:53,67 |

## G-6. 계약 정합 / 공통 인프라

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AICONTRACT-01 | ★COCO_ID2LABEL 80종·id 0~79 연속·중복 없음 | - | dict 검사 | 80개, key 0~79 | unit | High | detector_backend.py:67-85 |
| TC-AICONTRACT-02 | ★BE CocoClasses.LABELS ↔ ai-server COCO_ID2LABEL 정확 일치(드리프트) | BE·ai-server | 파싱 비교 | 완전 일치(파일 접근 불가 시 스킵) | integration | High | CocoClassesDriftTest.java:48-72 |
| TC-AICONTRACT-03 | coco_label_from_id 매핑 | - | 0→person,2→car,79→toothbrush | 정확 라벨 | unit | High | detector_backend.py:88-96 |
| TC-AICONTRACT-04 | id2label 우선, 미스 COCO fallback, 둘 다 미스 str(id) | - | 미지 100 | "100" | unit | Med | detector_backend.py:88-96 |
| TC-AICONTRACT-05 | coco_id_from_label 역매핑 안정성 | - | 라벨 | 결정적 정수, COCO 비충돌 | unit | Med | detector_backend.py:107-124 |
| TC-AICONTRACT-06 | 응답 스키마 계약 필드 불변 | - | predict | detections/mock/source/mock_reason/success/message/error_code | unit | High | schemas.py:68-88 |
| TC-AICONTRACT-07 | HTTP 경로 계약(prefix) | - | 라우팅 | /infer/yolo·sam2·vlm | integration | Med | main.py:65-67 |
| TC-AICONTRACT-08 | config 기본 ai_mock_mode=False | env 없음 | Settings | False | unit | High | config.py:26-33 |
| TC-AICONTRACT-09 | max_image_size_mb 범위(1~100) | - | 0/200 | ValidationError | unit | Low | config.py:47 |
| TC-AICONTRACT-10 | cors_origins_list 콤마 파싱 | - | "a,b, c" | ["a","b","c"] | unit | Low | config.py:70-71 |
| TC-AICONTRACT-11 | detector_backend 설정 없음(YOLOX 단일화) | - | grep | 부재 | unit | Low | test_yolo_dispatch.py:162 |
| TC-AICONTRACT-12 (신규) | ★좌표 정규화는 ai-server 책임이 아니다 — BE `DetectionBoxNormalizer` 가 경계 clamp·유한성 가드·퇴화 스킵을 전담 | - | ai-server 가 음수/경계초과 좌표를 그대로 응답해도 | ai-server 계약 위반 아님(정상 출력) — BE 가 `normalizeBbox`로 0≤x≤imgWidth clamp, NaN/Infinity 만 예외(all-or-nothing 거부), clamp 후 폭·높이≤0(퇴화)이면 해당 검출만 스킵. YOLO 온라인(`AutolabelOnlineService`)·배치(`YoloAutolabelStep`/`YoloLabelPersister`)·`YoloTrackService` 4경로가 동일 유틸 경유 | unit | High | DetectionBoxNormalizer.java:50-81 |
| TC-AIINFRA-01 | /health → 200 {status:ok} | - | GET /health | 200 ok | integration | High | main.py:70-72 |
| TC-AIINFRA-02 | X-Request-Id 응답 헤더 | - | 임의 요청 | 헤더 존재 | integration | Med | request_id.py:34-48 |
| TC-AIINFRA-03 | 요청 X-Request-Id 있으면 반사 | - | 안전한 id | 동일 반사 | integration | Med | request_id.py:38-47 |
| TC-AIINFRA-04 | ★CRLF/unsafe id → 재생성(CWE-113) | - | id에 `\r\n` | 12자 hex 재생성 | security | High | request_id.py:39-41,51-54 |
| TC-AIINFRA-05 | id 64자 초과/빈값 → 재생성 | - | 65자 | 재생성 | security | Med | request_id.py:52-53 |
| TC-AIINFRA-06 | 미처리 예외 → 500(스택/경로 미노출) | - | 내부 예외 | 500 "서버 내부 오류"만 | security | High | exceptions.py:65-69 |
| TC-AIINFRA-07 | 검증 에러에 스택/내부경로 미포함 | - | 400 | error_code+message만 | security | High | exceptions.py:29-31,51-59 |
| TC-AIINFRA-08 | ErrorResponse extra=forbid | - | - | 스키마 엄격 | unit | Low | schemas.py:23-29 |

> **주의**: `TC-AIINFRA-01`의 `/health`(200 status:ok)는 **ai-server 자체**(YOLO/SAM2/VLM 추론 프로세스)의 헬스체크다. **mock-server**(외부 벤더 목업, `:9400`)도 우연히 동일 경로 `/health`를 갖고 있으나(G-7 TC-AIMOCK-02) 이는 **KPST 비식별 벤더 계약이 아니다** — 실 KPST 벤더는 `/health`를 제공하지 않고 BE 는 루트(`/`)를 핑한다(G-10 TC-AIMOCK-34). 두 `/health`를 같은 것으로 혼동하지 말 것.

## G-7. KPST 비식별 벤더 목업 (`mock-server/app/routers/deid.py`)

> `mock-server`(FastAPI `:9400`)는 KPST 비식별화·IntelliVIX VLM·생성형 AI(증강) 3개 벤더를 흉내내는 목 서버다. 인증 없음(모든 벤더 공통, 스코프 내 의도적 미구현).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AIMOCK-01 | GET / → 200 "Connect"(KPST 헬스/연결 확인 규격) | - | GET / | 200, text="Connect" | integration | High | mock-server/app/routers/deid.py:116-119 |
| TC-AIMOCK-02 | GET /health → 200 {status:ok} — mock-server 자체 기능, KPST 벤더 계약 아님 | - | GET /health | 200 {"status":"ok"} | integration | Med | mock-server/app/main.py:103-105 |
| TC-AIMOCK-03 | retrieve_progress 응답 fileName = 원본 입력파일 경로(산출물명 아님, 실서버 계약) | POST /project 완료 | GET /retrieve_progress | dsStatus[].fileName = input_path + 원본 basename | integration | High | mock-server/app/routers/deid.py:86-98 / services/deid_sim.py:210-217,272-279 |
| TC-AIMOCK-04 | 실제 마스킹 산출물명 = {원본stem}-mask{ext}(타임스탬프 세그먼트 없음) | write_output_files=true | POST /project | export_path 하위에 `{stem}-mask{ext}` 파일 생성(예: 001.mp4→001-mask.mp4) | integration | High | mock-server/app/services/deid_sim.py:220-223,255-269 |
| TC-AIMOCK-05 | MOCK_OUTPUT_BASE 미설정 → 더미 산출물 생성 안 함(fail-closed, WARN 1회) | output_base="" | POST /project | 파일 미생성, 응답은 정상 200 유지 | security | High | mock-server/app/services/deid_sim.py:1706-1714 |
| TC-AIMOCK-06 | export_path 가 output_base(콤마구분 다중 허용) 밖 → 파일 미생성 + WARN | output_base 설정됨 | export_path=허용 루트 밖 | 파일 미생성, 응답 200 유지(관측 불가하게 실패하지 않음) | security | High | mock-server/app/services/deid_sim.py:1716-1725 / services/path_policy.py:54-82 |
| TC-AIMOCK-07 | input_base 밖 input_path 원본은 읽지 않고 산출 실패(procState=99)로 종결 — 구 18바이트 placeholder 대체 방식은 폐기됨 | input_base 설정됨 | input_path=허용 루트 밖 | target 파일이 아예 생성되지 않음(placeholder 없음), 산출 실패 + WARN 로그. ⚠ 구 기대결과("18바이트 placeholder 로 대체")는 2026-07-30 이후 코드에서 명시적으로 폐기됨(주석 "★ #3 — placeholder 산출물은 폐기됐다" / "⛔ 되돌리지 말 것" — 읽지 못한 원본을 '비식별 완료'로 승격시키는 위장 산출물(CWE-345) 방지) | security | High | mock-server/app/services/deid_sim.py:1560-1640(`_write_one_output` 원본 미독 분기) / services/path_policy.py:85-108,133-145(input_base 경계 판정) |
| TC-AIMOCK-08 | 기존 산출물 있으면 덮어쓰지 않음(O_EXCL no-overwrite, 멱등 재실행 안전) | 산출물 기존재 | POST /project 재실행 | 기존 파일 그대로 유지, 로그만 "skip(no-overwrite)" | unit | Med | mock-server/app/services/deid_sim.py:1554-1559,408-419,463-464 |

## G-8. VLM 벤더 목업 (`mock-server/app/routers/vlm.py`, IntelliVIX Video VLM API v2.0.1 정합)

> ai-server의 `/infer/vlm/verify-objects`(G-5)와는 별개다 — 이 절은 BE `VlmClient`가 실제로 호출하는 **외부 벤더 시계열 API 목업**이다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AIMOCK-09 | POST /v1/videovlm/verify·describe 는 즉시 accepted 응답 후 지연 콜백으로 결과 전달 | - | 유효 요청 | 동기 200 {request_id,status:"accepted"}, callback_delay_seconds 후 callback_url 로 결과 POST | integration | High | mock-server/app/routers/vlm.py:180-224 |
| TC-AIMOCK-10 | callback_url 허용 호스트 목록 밖 → 400(SSRF, CWE-918) | MOCK_CALLBACK_ALLOWED_HOSTS 기본값(`klid-backend,localhost,127.0.0.1`) | callback_url=임의외부호스트 | 400, outbound 미발사 | security | High | mock-server/app/routers/vlm.py:151-169 / services/url_guard.py:28-33 |
| TC-AIMOCK-11 | request_id "fail" 접두 또는 media.path 에 "fail" 포함 시 콜백만 failed(동기 응답은 규격대로 accepted 유지) | - | request_id="fail-x" | 동기 **200** accepted(202 아님 — 벤더 v2.0.1 §2.1/§2.5 및 TC-AIMOCK-09 와 동일 코드), 콜백은 `{"status":"failed","error":{"code":"INFERENCE_ERROR","message":...}}`(중첩 error 객체, 평면 error_code/message 아님) | integration | High | mock-server/app/routers/vlm.py:196-199,219-222 / services/vlm_sim.py:329-335 |
| TC-AIMOCK-12 | ★GET /v1/videovlm/status — 벤더 v2.0.1 §2.7 규격은 `{"status":"ready"}`(처리가능)/`{"status":"busy"}`(진행중)이나 목업은 `{"status":"ok","service":"videovlm"}`(규격 밖 어휘·service 필드)를 반환한다 — **미수정 결함**(G-ISSUE-84, 2026-08-02 발견) | - | GET | (기대: 200 `{"status":"ready"}` 또는 `{"status":"busy"}`, service 필드 없음) / (실제: 200 `{"status":"ok","service":"videovlm"}` — 계약 불일치) | integration | Low | mock-server/app/routers/vlm.py:241-244 |

## G-9. 생성형 AI(genai) 증강 벤더 목업 (`mock-server/app/routers/augment.py`, 「생성형 AI API 연동명세서 v1.1」 정합, 2026-07-27 신설)

> 증강(WINTER/NIGHT/RAIN 등) 외부 위탁 목업. `HttpExternalAugmentClient`(BE)가 `POST /api/genai/jobs`로 위탁하고 job_id 는 **외부(목) 발급**이다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AIMOCK-13 | POST /api/genai/jobs 유효 요청 → 202 RECEIVED | - | request_id/evnt_type/operation_type/generation_mode/prompt | 202, {request_id,job_id,status:RECEIVED,received_at} | integration | High | routers/augment.py:263-310 / test_genai_jobs.py:100 |
| TC-AIMOCK-14 | I2I·I2V 인데 input_files 없음 → 400 REQUIRED_FIELD_MISSING | - | generation_mode=I2I, input_files=[] | 400 | unit | High | routers/augment.py:220-226 / test_genai_jobs.py:113 |
| TC-AIMOCK-15 | T2I·T2V 는 input_files 없어도 202 | - | generation_mode=T2I | 202 | unit | Med | schemas/genai.py:75-87 / test_genai_jobs.py:131 |
| TC-AIMOCK-16 | input_files[].sequence 중복 → 400 INVALID_PARAMETER | - | sequence=[1,1] | 400 | unit | Med | routers/augment.py:228-232 / test_genai_jobs.py:139 |
| TC-AIMOCK-17 | 진행 단계별 webhook(10→PREPROCESS,50→INFERENCE,90→POSTPROCESS) 발사 + 완료(100) webhook 에만 results 포함 | callback_url 지정 | job 진행 | 단계마다 POST, 완료 payload 에 results[] | integration | High | services/genai_sim.py:93-98,639-654,770-783 / test_genai_webhook.py:98 |
| TC-AIMOCK-18 | GET jobs/{id}/results 는 SUCCEEDED 상태에서만, 그 외 409 STATE_CONFLICT | RUNNING 중 조회 | GET results | 409 | unit | High | routers/augment.py:334-349 / test_genai_jobs.py:228 |
| TC-AIMOCK-19 | 결과 output_file_path 는 실재 파일(원본 있으면 복사, 없으면 placeholder) | delay=0 | 완료 대기 | 파일 실존 + sha256 checksum 일치 | integration | High | services/genai_sim.py:439-444,492-520,605-616 / test_genai_jobs.py:191 |
| TC-AIMOCK-20 | POST cancel — RECEIVED·RUNNING 만 가능, 종결 상태는 409 STATE_CONFLICT | - | SUCCEEDED 후 cancel | 409 | unit | High | routers/augment.py:358-386 / test_genai_jobs.py:245,271 |
| TC-AIMOCK-21 | Idempotency-Key 동일 재요청 → 동일 job_id 반환(64자 초과는 400) | 헤더 지정 | 동일 키 2회 POST | 같은 job_id, 65자 키는 400 | integration | Med | routers/augment.py:163-175,276-303 / test_genai_jobs.py:312,329,337 |
| TC-AIMOCK-22 | MOCK_GENAI_OUTPUT_BASE 미설정 → FAILED(RESULT_SAVE_FAILED, fail-closed) | genai_output_base="" | 작업 진행 | webhook status=FAILED, error_code=RESULT_SAVE_FAILED | security | High | services/genai_sim.py:555-559 / test_genai_webhook.py:360-374(HIGH3) |
| TC-AIMOCK-23 | input_files[].file_path 가 허용 루트(genai_input_base) 밖/상대경로/base 미설정 → 400 INVALID_PARAMETER | - | file_path=상대경로 또는 루트밖 절대경로 | 400 | security | High | services/genai_sim.py:382-409 / test_genai_webhook.py:313-336(HIGH3), test_genai_security_hardening.py:303-327(F6) |
| TC-AIMOCK-24 | callback_url SSRF: host[:port] allowlist + 경로접두사 + 자기참조(목 서버 자신) 차단 | MOCK_GENAI_CALLBACK_ALLOW_HOSTS 설정 | callback_url=허용밖/자기자신 | 400 INVALID_PARAMETER, outbound 미발사 | security | High | services/genai_sim.py:297-325,328-374 / test_genai_webhook.py:376-427(HIGH4), test_genai_security_hardening.py:159-241(F2) |
| TC-AIMOCK-25 | 요청 본문·prompt 직렬화 크기 상한 초과 → 413/400(CWE-770) | genai_max_body_bytes/genai_max_prompt_bytes | 초과 payload | 413 GA-MEDIA-001 또는 400 INVALID_METADATA | security | Med | routers/augment.py:72-119,208-217 / test_genai_security_hardening.py:242-303(F3) |
| TC-AIMOCK-26 | 처리 시점 재검증(TOCTOU) — 접수 후 입력이 base 밖 심볼릭링크로 치환되면 FAILED, 유출 없음 | O_NOFOLLOW | 접수 후 파일 교체 | 콜백 FAILED(MODEL_EXECUTION_FAILED), 원본 미노출 | security | High | services/genai_sim.py:447-467,620-635 / test_genai_security_hardening.py:98-157(F1) |
| TC-AIMOCK-27 | 취소 확정 시 이미 생성된 산출물 정리(고아 파일 없음) | 진행 중 취소 | POST cancel | 산출물 파일·디렉터리 삭제됨 | unit | Med | services/genai_sim.py:523-536 / test_genai_security_hardening.py:409-424(F11) |
| TC-AIMOCK-28 | genai_event_types 화이트리스트 설정 시 목록 밖 evnt_type 거부 | 설정됨 | evnt_type=목록밖 | 400 UNSUPPORTED_EVENT_TYPE | unit | Med | routers/augment.py:178-184 / test_genai_jobs.py:397-413 |
| TC-AIMOCK-29 | 인메모리 작업 저장소 상한(genai_max_jobs) 초과 시 오래된 작업부터 만료(FIFO, CWE-770) | max_jobs 낮게 설정 | 상한 초과 등록 | 최고참 작업 만료 | unit | Med | mock-server/app/config.py:203-210 / test_genai_security_hardening.py:270-285(F3) |
| TC-AIMOCK-30 | 목 전용 보조 EP(`/api/genai/_mock/jobs`, `/_mock/reset`, `/_mock/jobs/{id}/status-sync`)는 명세서 밖 기능 | - | 호출 | 정상 동작(명세서 계약 아님, 테스트/운영 관측용) | unit | Low | routers/augment.py:389-441 |
| TC-AIMOCK-31 | ③상태 동기화(status-sync)는 자동 발신하지 않고 목 전용 수동 트리거로만 발신, 대상 미설정 시 비활성 | MOCK_GENAI_STATUS_SYNC_URL 미설정 | POST _mock/jobs/{id}/status-sync | {sent:false, target:null} | unit | Med | services/genai_sim.py:722-738 / test_genai_webhook.py:183-227 |
| TC-AIMOCK-32 | 인증(401 UNAUTHENTICATED/403 FORBIDDEN)은 이번 스코프에서 의도적으로 미구현 — 갭 아님 | - | 임의 클라이언트 호출 | 인증 없이 202 수락(설계 의도) | security | Low | routers/augment.py:20 / schemas/genai.py:8-9 |
| TC-AIMOCK-33 | mock-server 로거가 stdout 으로 강제 연결되어(basicConfig) 방어 로그(경계위반 WARN 등)가 `docker logs` 로 관측 가능 | - | 경계위반 유발 요청 | WARN 로그가 stdout 에 노출(관측성 회귀 없음) | unit | Low | main.py:32-48 |

## G-10. BE ↔ 외부 벤더 실배선 계약 (`VlmUrlPolicy`·`DeidentifyHealthIndicator`·`VlmClient`)

> mock-server 는 "무엇을 흉내내는가"의 계약이고, 이 절은 "BE 가 그 목업/실 벤더를 어떻게 신뢰·호출하는가"의 계약이다. 2026-07-25 이후 신설/재배선된 부분(Phase 7, 설정 정상화 커밋 a9740ab3/41b0504d)만 다룬다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-AIMOCK-34 | ★비식별 헬스체크는 벤더(KPST) 루트(`/`)를 핑한다 — `/health` 아님 | kpst.deid.enabled=true, mock-mode=false | actuator health | `kpstWebClient.get().uri("/")` 호출, 200 대이면 UP(mode=kpst) | integration | High | DeidentifyHealthIndicator.java:92-104 |
| TC-AIMOCK-35 | mock-mode=true → 외부 핑 없이 UP(mode=mock) | authoring.integration.deidentify.mock-mode=true | actuator health | UP, mode=mock, 외부 호출 0건 | unit | Med | DeidentifyHealthIndicator.java:77-83 |
| TC-AIMOCK-36 | kpst.deid.enabled=false 이고 mock-mode 도 아니면 DOWN(설정 오류, fail-closed) | 둘 다 off | actuator health | DOWN, mode=unconfigured, error=NoDeidentifyPathConfigured | unit | Med | DeidentifyHealthIndicator.java:84-91 |
| TC-AIMOCK-37 | kpstWebClient 호출 예외 시 DOWN(예외 클래스명만 노출, CWE-209) | kpst 서버 다운 | actuator health | DOWN, error=예외 simpleName만(스택트레이스 미노출) | security | Med | DeidentifyHealthIndicator.java:105-112 |
| TC-AIMOCK-38 | VlmUrlPolicy — allow-insecure-url=true + local/dev 프로파일 + ENV 미배포표식일 때만 평문 http·내부망 허용(relaxed) | vlm.client.allow-insecure-url=true, profile=local | 기동 | relaxed 정책 적용(ExternalUrlPolicy.internalNetwork) — mock-server(http, 사설IP) 접속 허용 | integration | High | ProfileGatedUrlPolicy.java:124-136(check/policy),148-151(profileAllowsRelaxation) / ExternalUrlPolicy.java:80-82 |
| TC-AIMOCK-39 | allow-insecure-url=true 인데 profile 이 local/dev 밖(stg/prd/미지정/혼합) 이거나 ENV=stg\|prd → 기동 자체 실패 | allow-insecure-url=true, profile=prd | @PostConstruct | IllegalStateException, 애플리케이션 기동 중단(fail-closed) | security | High | ProfileGatedUrlPolicy.java:81-94(throw 90-93) / DeployedEnvironmentDetector.java:63-70 |
| TC-AIMOCK-40 | relaxed 정책에서도 링크로컬/클라우드 메타데이터 대역(169.254.0.0/16, fe80::/10)은 호스트 해석 성공 시 거부 **(정정 — 차단 집합이 ULA·CGNAT·fec0::/10 까지 확장됨. G-ISSUE-21 해소)** | relaxed 적용 중 | vlm.client.url 호스트가 169.254.x / fe80:: 로 해석됨 | IllegalStateException(CWE-918), 메시지에 "링크로컬/클라우드 메타데이터" | security | High | ExternalUrlPolicy.java:205-217(판정 루프),236-252(ReservedRange 정의) / VlmUrlPolicyTest.java:legacyLinkLocalStillRejected · AugmentUrlPolicyTest.java:증강도_기존_링크로컬… |
| TC-AIMOCK-41 | relaxed 정책은 DNS 해석 실패를 통과(컨테이너 서비스명 등), strict 정책은 해석 실패를 거부 | - | host=klid-mock-server(도커 밖에서 미해석) | relaxed: 통과 / strict: IllegalStateException | unit | Med | ExternalUrlPolicy.java:158-173(resolveQuietly, 해석 실패 null→통과),185-193(strict 는 UnknownHostException 을 거부로) |
| TC-AIMOCK-47 | **★IPv6 ULA(`fc00::/7`)는 relaxed·strict 양쪽에서 거부 (신규 — G-ISSUE-21 해소)** — AWS IPv6 IMDS `fd00:ec2::254` 가 이 대역이며 링크로컬이 아니라 `isSiteLocalAddress()`(deprecated `fec0::/10` 만 판정)로는 잡히지 않았다 | relaxed(local+플래그) / strict(prd) | `http://[fd00:ec2::254]:9400`, `http://[fc00::1]:9400`, `http://[fec0::1]:9400` | 양 정책 모두 IllegalStateException(CWE-918). relaxed 메시지에 "IPv6 ULA(fc00::/7)" | security | High | ExternalUrlPolicy.java:351-374(classifyIpv6),242-243(ReservedRange.ULA_V6/SITE_LOCAL_V6) / VlmUrlPolicyTest.java:ipv6UlaRejectedInBothPolicies · AugmentUrlPolicyTest.java:증강도_IPv6_ULA… |
| TC-AIMOCK-48 | **★CGNAT(`100.64.0.0/10`)는 relaxed·strict 양쪽에서 거부 (신규 — G-ISSUE-21 해소)** — Alibaba Cloud 메타데이터 `100.100.100.200` 이 이 대역. 경계 밖(`100.63.x`·`100.128.x`)은 공인이라 계속 통과(과차단 회귀 방지) | relaxed / strict | `100.100.100.200`, `100.64.0.1`, `100.127.255.254` vs `100.63.255.254`, `100.128.0.1` | 앞 3건 IllegalStateException(메시지에 "CGNAT 공유(100.64.0.0/10)") / 뒤 2건 통과 | security | High | ExternalUrlPolicy.java:318-349(classifyIpv4 — `(o1 & 0xC0) == 64`) / VlmUrlPolicyTest.java:cgnatRangeRejectedInBothPolicies · AugmentUrlPolicyTest.java:증강도_CGNAT… |
| TC-AIMOCK-49 | **★호스트가 여러 주소로 해석되면 전 주소를 검사하고 하나라도 차단 대역이면 거부 (신규 — G-ISSUE-22 해소)** — `getByName`(첫 주소만) → `getAllByName`. 첫 주소가 공인이어도 뒤에 사설·IMDS 가 섞이면 거부. relaxed 는 RFC1918 은 계속 허용하되 IMDS·ULA 는 순서 무관 거부 | 판정은 해석과 분리된 `verifyResolvedAddresses` 가 담당(실 DNS 조작 없이 다중 A/AAAA 주입) | strict: (8.8.8.8, 10.0.0.5) / relaxed: (8.8.8.8, 169.254.169.254) · (8.8.8.8, fd00:ec2::254) · (8.8.8.8, 10.0.0.5) | strict 거부 / relaxed 앞 2건 거부·마지막 통과. 전부 공인이면 통과 | security | Med | ExternalUrlPolicy.java:167-173,185-193(getAllByName),195-217(전 주소 순회) / ProfileGatedUrlPolicy.java:134-143(프로파일별 판정 위임) / VlmUrlPolicyTest.java:anyResolvedAddressInReservedRangeRejected |
| TC-AIMOCK-50 | **★IPv4-mapped/compatible IPv6 는 언랩 후 IPv4 규칙 적용 (신규)** — `::ffff:10.0.0.5` 로 위장해 IPv4 사설 규칙을 우회하지 못한다. `::1`·`::` 는 언랩 대상이 아니라 IPv6 규칙(루프백/와일드카드)으로 판정 | strict | `https://[::ffff:10.0.0.5]`, `https://[::ffff:192.168.0.1]`, `https://[::1]:9400`, `https://0.0.0.0` | 전건 IllegalStateException | security | Med | ExternalUrlPolicy.java:287-307(unwrapEmbeddedIpv4),351-374 / VlmUrlPolicyTest.java:legacyLinkLocalStillRejected · AugmentUrlPolicyTest.java:증강도_기존_링크로컬… |
| TC-AIMOCK-51 | **★NAT64 well-known prefix(`64:ff9b::/96`)에 임베드된 IPv4 도 언랩 후 IPv4 규칙 적용 (신규 — 리뷰 HIGH 보강)** — RFC 6052 는 하위 32비트에 IPv4 를 담는다. 언랩하지 않으면 IPv6 표기로 IMDS·CGNAT·사설 규칙을 통째로 우회. NSP(사업자 임의 프리픽스)·RFC 8215 `64:ff9b:1::/48` 은 값 열거 불가라 비대상(주석 명시) | relaxed(local+플래그) / strict(prd) / KPST 내부망 | `[64:ff9b::a9fe:a9fe]`(=169.254.169.254 IMDS) · `[64:ff9b::6464:64c8]`(=100.100.100.200 CGNAT) · `[64:ff9b::a00:5]`(=10.0.0.5) vs `[64:ff9b::808:808]`(=8.8.8.8) · `[64:ff9c::a9fe:a9fe]`(프리픽스 불일치) | 앞 3건 IllegalStateException(언랩된 IPv4 대역 라벨 — "메타데이터"/"CGNAT"/"내부") / 뒤 2건 통과(NAT64 경유 공인 목적지는 정상, 프리픽스 불일치는 일반 IPv6) | security | High | ExternalUrlPolicy.java:267-269(NAT64_WELL_KNOWN_PREFIX),287-307(unwrapEmbeddedIpv4),309-316(hasPrefix) / VlmUrlPolicyTest.java:nat64WellKnownPrefixUnwrappedAndRejected · AugmentUrlPolicyTest.java:증강도_NAT64… · KpstWebClientConfigTest.java:kpstNat64WellKnownPrefixRejected |
| TC-AIMOCK-52 | **★IANA 특수목적 대역 4종은 relaxed·strict 양쪽에서 거부 (신규 — 리뷰 MEDIUM 보강)** — `192.0.0.0/24`(IETF 프로토콜 할당) · `198.18.0.0/15`(벤치마킹) · `224.0.0.0/4`·`ff00::/8`(멀티캐스트, v4/v6 대칭) · `240.0.0.0/4`(Class E, 브로드캐스트 포함). 방어심도 목적이며 `UNSPECIFIED_V4`(0.0.0.0/8)와 동일하게 "과차단 방향이라 안전" | relaxed / strict / KPST | `192.0.0.170`, `198.18.0.1`, `198.19.255.254`, `224.0.0.1`, `[ff02::1]`, `240.0.0.1`, `255.255.255.255` vs `192.0.1.1`, `198.17.255.254`, `198.20.0.1`, `223.255.255.254` | 앞 7건 IllegalStateException(라벨 "IETF 프로토콜 할당(192.0.0.0/24)"/"벤치마킹(198.18.0.0/15)"/"멀티캐스트(224.0.0.0/4 · ff00::/8)"/"예약(240.0.0.0/4)") / 뒤 4건 통과(경계 밖 공인 — 과차단 회귀 방지). 개발 목업이 쓰는 RFC1918·loopback 은 relaxed 에서 계속 허용 | security | Med | ExternalUrlPolicy.java:245-252(enum 4종+사유 주석),336-347(classifyIpv4 신규 분기),368-372(classifyIpv6 ff00::/8) / VlmUrlPolicyTest.java:ianaSpecialPurposeRangesRejected · AugmentUrlPolicyTest.java:증강도_IANA… · KpstWebClientConfigTest.java:kpstIanaSpecialPurposeRangesRejected |
| TC-AIMOCK-53 | **★KPST 축에도 동일 대역 회귀 가드 존재 (신규 — 리뷰 HIGH 보강, 3연동 대칭)** — 판정기(`ExternalUrlPolicy`)는 VLM/KPST/증강 공용인데 가드는 VLM·증강 2축에만 있어 KPST 만 조용히 갈라져도 잡히지 않았다. KPST 는 `internalNetwork`(relaxed) 정책이라 RFC1918·loopback·미해석 컨테이너명은 계속 허용되고 메타데이터 계열만 차단된다 | `kpst.deid.enabled=true`, 2개 빈(kpstDeidWebClient · kpstDeidProgressHttpClient) | ULA(`[fd00:ec2::254]`·`[fc00::1]`·`[fec0::1]`·`[fe80::1]`) / CGNAT(`100.100.100.200`·`100.64.0.1`·`100.127.255.254`) / NAT64·IPv4-mapped 위장 / IANA 4종 / 다중 해석 주소(공인+IMDS·공인+ULA·CGNAT+공인) vs `10.20.30.40`·`172.16.0.1`·`192.168.0.1`·`127.0.0.1`·미해석 컨테이너명·`100.63.255.254`·`100.128.0.1` | 앞 그룹 전건 IllegalStateException(**2개 빈 대칭**) / 뒤 그룹 전건 빈 생성 성공. TLS 자체 CA fail-closed(CWE-295)는 이 변경과 무관하게 불변 | security | High | KpstWebClientConfig.java:71-72(internalNetwork 위임),169-171(isHttps) / ExternalUrlPolicy.java:205-217 / KpstWebClientConfigTest.java:kpstIpv6UlaRejected·kpstCgnatRejected·kpstNat64WellKnownPrefixRejected·kpstIpv4MappedRejected·kpstIanaSpecialPurposeRangesRejected·kpstAnyResolvedAddressInReservedRangeRejected·kpstPrivateAndLoopbackStillAllowed |
| TC-AIMOCK-42 | VlmClient.submitTimeseries — POST /v1/videovlm/describe, 응답 request_id echo 불일치/status≠"accepted" → EXTERNAL_API_ERROR | vlm.client.enabled=true | mock 응답 조작 | CustomException(EXTERNAL_API_ERROR) | integration | High | VlmClient.java:88-112,154-169 |
| TC-AIMOCK-43 | 4xx 응답은 비재시도(NonRetryableExternalException, circuitbreaker ignore-exceptions) — 정상 4xx 가 서킷을 열지 않음 | - | mock 400/422 응답 | 재시도 없음, 서킷 failure 집계 제외 | unit | Med | VlmClient.java:106,121-127 / application.yml:538-545(circuitbreaker.vlmClient),612-618(retry.vlmClient) |
| TC-AIMOCK-44 | 타임아웃 — WebClient 자체 10s(vlm.client.timeout-seconds); 배치 오케스트레이션의 45s 블로킹 대기(BLOCK_TIMEOUT)는 Phase C-1 논블로킹 제출 전환으로 폐지됨 | 기본 설정 | describe 무응답 | WebClient 가 10s 시점에 타임아웃 예외를 내면 완료 핸들러(`VlmSubmitOutcomeRecorder.onSubmitFailed`)가 비동기로 기록한다. `doSubmit`(`VlmTimeseriesStep`)은 `.subscribe(...)` 만 하고 **즉시 반환**(블로킹 없음) — 구 `.block(45s)`/`ControlNotifyClient`류 `BLOCK_TIMEOUT` 은 VLM 경로에 더 이상 존재하지 않는다(grep 확인: `BLOCK_TIMEOUT` 은 `ControlNotifyClient` 전용 15s로만 남음). ACK·콜백이 모두 없는 무신호 건은 `VlmSubmitPendingSweeper` 가 별도 임계(`authoring.batch.vlm.submit-reclaim.stale-timeout-minutes` 기본 30분 / `callback-timeout-minutes` 기본 360분)로 회수한다 | unit | Med | VlmClient.java:71-76,108 / VlmTimeseriesStep.java:346-381(구 `.block(45s)` 폐지 주석 346-357 · `.subscribe` 366 · `submitted` 반환 381) / VlmSubmitPendingSweeper.java:121-122(두 임계 `@Value`),192-197(두 cutoff 적용) |
| TC-AIMOCK-45 | vlm.client.enabled=false(공통 기본값) → 외부 호출 0건 즉시 SKIPPED; local 은 기본 true 라 실제 mock-server 왕복 발생 | 공통 application.yml vs application-local.yml | describe 호출 | 공통기본: SKIPPED / local: 실 HTTP 왕복(describe→/v1/vlm/callback) | integration | High | VlmClient.java:94-97 / application.yml:704(공통 기본 false) / application-local.yml:150(local 기본 true) |
| TC-AIMOCK-46 | 비식별 신고 구간(DE_IDNTF_YN='F')이면 doSubmit 이 경로 해석 직전 SKIPPED(보류)로 게이트, 해제 시 재위탁 | DeidentReportGate 오픈 | VlmTimeseriesStep 실행 | 외부 호출 0건, LS_BATCH_PROC_LOG 에 사유 적재, 해소 시 VlmWithheldResumeRunner 가 재위탁 | integration | High | VlmTimeseriesStep.java:60-63,303-307 |

> **불확실 항목**: #13 은 부분 해소됨 — ai-server `verify-objects` 는 여전히 항상 mock(G-5 TC-AIVLM-03)이지만, **외부 VLM 시계열 연동(BE `VlmClient`→mock-server `/v1/videovlm/describe`, WebClient 10s 타임아웃, request_id echo 검증, 4xx 비재시도)은 이번 회차에 실배선 확인**됨(G-10 TC-AIMOCK-42~46). ⚠ 2026-08-03 재확인: 구 "45s/10s 2계층 타임아웃"(배치 오케스트레이션이 `.block(45s)`로 대기) 서술은 Phase C-1 논블로킹 제출 전환으로 폐기됨 — `VlmTimeseriesStep.doSubmit`은 이제 구독만 하고 즉시 반환하며, 무신호 건 회수는 `VlmSubmitPendingSweeper`의 별도 임계(기본 30분/360분)가 담당한다(TC-AIMOCK-44 정정 참조). IntelliVIX 실서버 v2.0.1 계약과의 완전 일치(콜백 스키마 세부·재시도 정책 등)는 여전히 벤더 실서버 대조가 필요해 미확정. #14(imgsz 무효 검증 방법)·#15(실모델 테스트 게이팅)는 ai-server 코드 미변경으로 그대로 미확정 유지.
