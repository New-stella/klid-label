# 11. AI 보조 · 오토라벨링

> 출처: R1 RQ-SFR-08-01/02, R2 KLID-AT-UC-004/005, CLAUDE.md, 코드(`batch/step`, `ai-server`, `common/util`)
> 관련: [07 배치 파이프라인](07-batch-pipeline.md) · [10 라벨링](10-labeling.md)

화면: 오토라벨 요약은 **영상 상세(SC-009)의 인라인 `AutoLabelTab`**으로 제공. (구 `SC-014` 오토라벨 요약 전용 페이지 `/auto/:videoId`는 진입점 없는 orphan으로 2026-06-17 deprecated·코드 제거 → [04 화면·IA](04-screens-ia.md))

## 11.1 ai-server (내부 추론 서버)

- Python FastAPI, **stateless GPU 추론 전용** (인증/DB 없음). 외부 아님 — 저작도구 내부 구성요소.
- 호출: `AiServerClient`(Resilience4j, 60s 타임아웃 + CircuitBreaker)
- 라우터: `yolo.py`(`/infer/yolo/predict`·`/track`), `sam2.py`(`/infer/sam2/segment`·`/track`), `vlm.py`(`/infer/vlm/verify-objects`)
- **다중 인스턴스 수평 확장 가능**

## 11.2 YOLO 오토라벨링

> **탐지 백엔드**: **YOLOX(ONNX Runtime, Apache-2.0) 단일 백엔드**. 구 ultralytics YOLOv8(AGPL-3.0)·RT-DETRv2(transformers) 백엔드는 제거됨(torch↔torchaudio ABI 불일치로 YOLOX 로 통합). HTTP 경로(`/infer/yolo/predict`·`/track`)·응답 스키마·배치 단계명(`YoloAutolabelStep`)은 불변(계약 호환). 트래킹은 ByteTrack(roboflow trackers).

- 배치 단계 `YoloAutolabelStep` — **원본 이미지에만** 객체 탐지
- 프리셋 필터(이벤트 유형별 라벨) 적용, `track_id` 부여
- 라벨 좌표는 동일 해상도이므로 **비식별본과 공유**(별도 실행 없음)
- 출처/신뢰도는 `LS_DATA_LBL`(`LBL_SRC_CD`·`CONF_SCORE`·`AUTO_LBL_YN`) — V6 에서 구 `LS_DATA_LBL_AI_INFO` 를 흡수했다
- **★검수 프레임 목록의 자동/수동 표시가 V6 부터 정확해졌다 (되돌리지 않는다)**: `GET /v1/reviews/{videoId}/frames`(검수 상세 화면 `ObjectAttributesPanel`)는 흡수 전 AI 정보를 **아예 조달하지 않아** 자동 라벨도 전부 `autoLblYn='N'`·신뢰도 공란으로 내려보냈다 — "그 라벨이 수동이어서"가 아니라 **값을 읽지 않아서** 나온 값이다. 흡수 후에는 라벨 행이 사실을 들고 있어 실제 값이 나가며, 그래서 검수 화면 표시가 **"수동 라벨" → "자동 라벨"** 로 바뀐다. **JSON 응답 스키마는 불변**(`LabelResponse.Item` 11필드)이고 값만 정확해진 것이므로 잠복 결함의 우발적 수정으로 보고 되돌리지 않는다.
  - **값이 바뀌지 않는 경로**(원래 AI 정보를 조달하던 곳): 라벨링 화면 조회·저장(`GET`/`PUT /v1/frames/{srcSn}/labels`) · 검수 승인 버전 스냅샷 · 회차 선택 작업본. 이 셋은 조달처만 바뀌었을 뿐 값이 같다
- **★검출 좌표 정규화는 배치·온라인 단일 규칙 (C-ISSUE-41, 2026-07-29)**: `common/util/DetectionBoxNormalizer` 한 곳에 두고 **YOLO 검출을 다루는 3경로 전부**가 **같은 함수를 호출**한다 — 배치(`YoloLabelPersister`) · 온라인 AI 탐지(`AutolabelOnlineService`) · 온디맨드 객체 추적(`YoloTrackService`). 셋 다 ai-server의 동일 모델(`/infer/yolo/track`)을 호출하므로 같은 경계 좌표가 오며, 한 곳이라도 규칙이 다르면 같은 응답이 경로에 따라 저장되거나 400 으로 폐기된다.
  - **이미지 경계 clamp** — `0 ≤ x ≤ imgWidth`, `0 ≤ y ≤ imgHeight`. 화면 경계에 걸친 객체(사람이 프레임 끝에 반쯤 걸림 등)는 CCTV 학습데이터의 **정상 다수 케이스**이고 모델이 경계를 조금 넘겨 출력하는 것도 정상이다. clamp 기준은 프레임 **실측** 해상도(`FrameBoundsResolver`, 캐시)이며 측정 실패 시 **상한만 생략**하고 하한(0)은 유지한다(fail-open — 원천 이미지가 없는 정상 작업을 막지 않는다).
  - **거부(400)는 형식 위반에만** — 좌표 개수 ≠ 4 · null 원소 · NaN/Infinity. 온라인은 이 경우에만 all-or-nothing 400 이다(`NaN < 0` 은 false 라 음수검사를 통과하므로 `isFinite` 가드는 필수 — 없으면 저장 후 좌표 역직렬화에서 500).
  - **clamp 후 퇴화(폭·높이 0 이하) 박스는 그 검출만 스킵** — 400 으로 올리면 같은 프레임의 정상 검출까지 폐기되고, 배치에서는 영상 1건의 오토라벨이 통째로 실패한다.
  - **배치는 형식 위반도 검출 단위 드롭** — 온라인은 all-or-nothing 400 이지만(사용자가 즉시 재시도 가능·외부 응답 불신 계약 우선), 배치는 300프레임 영상의 마지막 검출 1건 때문에 그 영상의 YOLO 단계 전체가 실패(작업 상태 FAILED)하고 앞서 저장된 라벨만 남는 부분 상태가 되면 안 된다. 드롭 건수는 `droppedDegenerate`/`droppedMalformed` 로 배치 요약 로그에 노출된다.
  - **SAM 프롬프트(`BbHint`)도 clamp 된 좌표를 싣는다** — `Sam2SegmentStep.buildJobs` 는 `(label, trackId)` 키로 DB BBOX 를 우선 등록한 뒤 `putIfAbsent` 로 hint 를 채우므로, DB BBOX 가 **없을 때**(퇴화로 bbox 스킵 · **폴리곤 전용 프리셋**)는 hint 가 곧 프롬프트가 된다. 미clamp 원본을 실으면 이미지 완전 밖 좌표가 SAM box 프롬프트로 나가고 그 산출 폴리곤이 `LS_DATA_LBL` 에 저장된다(학습데이터 오염). 정규화는 검출 루프 선두에서 1회 수행하고 bbox 저장·polygon hint 가 **같은 좌표를 공유**하며, 퇴화면 **둘 다** 스킵한다.
  - 구 동작(폐기): 온라인만 `좌표 < 0` 을 all-or-nothing 400 으로 거부하고 배치는 무검증 저장 → 실데이터에서 AI 탐지가 프레임 대부분 400 이었고 `LS_DATA_LBL` 에는 음수 좌표 라벨이 적재됐다(같은 응답에 대해 두 경로 정책이 갈림). 상한(`x2>width`) 미검증도 함께 해소.
- **★배치 자동 라벨 저장은 프레임 단위 일괄 저장 (B-ISSUE-42, 2026-07-29)**: YOLO(`YoloAutolabelStep`)·SAM2(`Sam2SegmentStep`) 모두 검출 루프 안에서 `save()` 를 개별 호출하던 것을 **프레임마다 `saveAll()` 2회**(라벨 → AI 메타)로 묶는다. 공용 헬퍼는 `batch/step/AutoLabelBatchPersister` 하나이며 `YoloLabelPersister` 는 **엔티티 생성(정규화 포함)까지만** 담당한다(`buildBbox`).
  - **V6 — PK 매칭 계약이 사라졌다.** 흡수 전에는 AI 메타가 별도 행이라 `DATA_LBL_SN` 이 대응 라벨의 PK 를 가리켜야 했고, 인덱스 대응이 어긋나면 **조용한 데이터 오염**이 됐다(그래서 크기 불일치 시 즉시 실패시켰다). 이제 값을 그 라벨 인스턴스에 직접 넣으므로 어긋날 대상 자체가 없다.
  - **드롭 시맨틱 불변** — 퇴화·형식위반 검출은 배치 목록에 담기지 않고 그 검출만 스킵되며, 나머지는 그대로 저장된다(위 C-ISSUE-41 규칙 유지).
  - ⚠ **성능 개선 폭의 한계(정직 표기)** — `LsDataLbl`/`LsDataLblAiInfo` 는 `GenerationType.IDENTITY` 이고 **PK 전략은 바꾸지 않았다**(사용자 확정 범위). Hibernate 는 IDENTITY 에서 JDBC 배치를 구조적으로 비활성화하므로 `hibernate.jdbc.batch_size` 는 여전히 이 엔티티들에 적용되지 않는다. 실익은 **왕복 횟수 감소와 저장 지점 단일화**이지 INSERT 문 묶음이 아니다. 실제 JDBC 배치는 시퀀스 PK 전환이 선행돼야 하나 `LBL_SN` 참조 모듈이 12개 이상(증강 라벨맵·해상도 파생·포털·품질검사·export 해시·버전 롤백의 LBL_SN 보존 복원 등)이라 별건이다.

### 온디맨드 YOLO 객체 추적 — `POST /v1/frames/{srcSn}/yolo-track` (인터랙티브)
- 배치 자동라벨링과 **별개의 온디맨드 경로** — 라벨러가 정렬된 프레임 시퀀스(`srcSn` 시작 + `nextSrcSns` 후속, 최대 50)를 지정하면 ai-server `/infer/yolo/track`을 프레임별 프록시하여 검출(`label`/`points[x1,y1,x2,y2]`/`score`/`track_id`)을 프레임별로 반환
- **순수 조회(DB 미저장)** — 결과는 FE가 받아 기존 `PUT /v1/frames/{srcSn}/labels`로 저장(배치 `YoloAutolabelStep`과 중복 저장 방지)
- 트래커 격리: `clip_id = {rawSn}:{요청 UUID}`(요청 단위 격리 — 동일 영상 동시 추적 간섭 방지), `frame_index` 0-base(첫 프레임 트래커 리셋)
- 방어: 본인 미배정 IDOR 차단(시작+모든 후속 프레임), path/body `srcSn` 불일치 400(CWE-345), 시퀀스 교차 영상(RAW_SN) 혼입 400, `nextSrcSns` 상한 50(CWE-770), 응답 좌표 검증(CWE-20). ai-server 연동 실패 502
- **응답 좌표는 위 11.2 의 `DetectionBoxNormalizer` 공용 규칙을 그대로 탄다** — 프레임마다 실측 해상도로 clamp, 퇴화 박스는 **그 검출만** 스킵, 형식 위반(개수 ≠ 4 · null · NaN/Infinity)만 400. 이 경로는 한 요청이 **최대 50프레임 시퀀스**라 검출 1건으로 400 을 내면 시퀀스 전체가 폐기되므로 폐기 범위 축소가 특히 중요하다(구 구현은 음수 즉시 400 + `isFinite` 가드 부재로 NaN 이 응답에 그대로 실렸다)
- REVIEWER/WORKER. 코드: `YoloTrackService`·`LabelController#yoloTrack` (LogiCraft `API-123`)

#### 라벨링 화면 배선 — "AI 자동 추적" 패널 (2026-08-18 신설, SCREEN-005·UC-034)

위 `POST /v1/frames/{srcSn}/yolo-track` 백엔드에 붙는 **화면(FE) 배선**이다. 트랙 편집 영역(우측 객체 목록 아래)에 `AutoTrackPanel` 로 배치되며, **내부 라벨링(`/label/:id`) 전용**이다 — 포털 라벨링(`/portal/label/:id`)은 오토라벨·추적 자체를 제공하지 않아(ADR-013) 진입점을 두지 않는다.

- **표기는 「AI 자동 추적」** — 선택 객체 하나를 따라가는 「AI 추적」(SAM2 추적(VOS)·§11.3)과 이름이 겹치지 않게 구분한다. 화면 문구에 내부 모델명(YOLO)은 노출하지 않는다. ⚠ **그 「AI 추적」의 버튼 표기도 2026-08-18 에 구 「자동추적」에서 「AI 추적」으로 바로잡았다** — 두 컨트롤이 같은 우측 패널에 함께 보이는데 구 표기는 이 구분 규칙을 스스로 어기고 있었다 → [10 §10.2.5](10-labeling.md).
- **시작 객체를 고르지 않는다** — 현재 프레임과 뒤따르는 프레임 구간만 있으면 실행된다(YOLO 검출을 프레임 구간 전체에 돌리는 것이지, 선택한 객체 하나를 전파하는 것이 아니다).
- **결과 적용은 2모드**이며 **기본값은 검토 후 수락**이다. 여러 객체를 여러 프레임에 걸쳐 한 번에 만들어 오검출 영향 범위가 단일 객체 추적보다 크므로, 검토 없이 바로 반영하는 "자동 반영"은 사용자가 옵션으로 켜는 쪽이다. **고른 방식은 그 실행에만 적용**되고 다음 실행은 다시 기본값(검토 후 수락)으로 돌아간다(오검출이 많은 영상에서 이전 선택이 남아 무심코 자동 반영되는 것을 막는다).
- **수락·제외의 단위는 트랙**이다(검출 낱개 단위가 아니다). 어느 방식이든 결과는 작업 중인 객체 목록(작업본)에만 올라가고, 확정은 라벨 저장(§10.2.0)으로 한다 — **저장 전에는 미확정**.
- **응답의 라벨 마스터 식별자(`labelId`)를 화면이 그대로 저장 payload 에 싣는다** — 검출 클래스명으로 마스터를 다시 찾지 않는다(판정은 서버가 하고 화면은 결과를 그대로 쓴다 — 화면이 다시 판정하면 같은 규칙이 두 곳에 생겨 한쪽이 낡는다). **마스터에 연결되지 않은 검출은 반영하지 않고**, 제외 건수와 사유를 안내에 남긴다.
- 이 패널은 §11.6 의 busy 배타 축에 **`AI_AUTO_TRACK`** 종류로 편입돼 있다(구 5종에서 6종으로 확장) → [10 §10.6](10-labeling.md#106-장시간-작업-중-편집-차단--진행-표시--취소-2026-07-31).
- 코드: `features/label/components/AutoTrackPanel.tsx`·`features/label/api/autoTrack.ts`·`features/label/hooks/useAutoTrack.ts`. @design SCREEN-005, API-123, UC-034.

#### ★추적 결과 ↔ 원본 검출 매칭은 위치(순서)가 아니라 식별로 한다 (2026-08-17, ai-server)

`ByteTrackTracker.update()`(위 YOLO 온라인/배치 추적이 공유하는 트래커)는 **반환 순서가 입력 순서와 다르다**(라이브러리 docstring 명시). 재정렬 규칙은 "고신뢰 매칭 → 저신뢰 매칭 → 미매칭 저신뢰 → 신규 트랙" 순이고 활성화 임계 미달 검출은 아예 빠지므로, 원본 순서와 `zip` 으로 짝지으면 **서로 다른 객체의 `track_id` 가 맞바뀐다**(실측: 입력 [A,B,C,D] → 출력 [A,C,B] 라 B 가 C 의 ID 를 받음).

- `_apply_bytetrack`(`ai-server/app/models/bytetrack_util.py`)은 이제 **①원본 인덱스 태그**(`sv.Detections.data` 에 실어 보낸 인덱스 — 트래커가 행을 재정렬·탈락시켜도 정확 복원) **②좌표+클래스 기하 매칭**(태그 유실 시 폴백, 허용오차 초과·클래스 불일치는 매칭 실패로 둠) 순으로 원본 검출을 특정하고, 어느 쪽으로도 특정되지 않으면 **그 검출만** `track_id` 를 부여하지 않는다(WARN, 조용한 오배정/손실 방지).
- ⚠ **이미 저장된 값은 정정하지 않는다** — 이 수정은 **앞으로 생성되는 추적 결과에만** 적용되고, 과거에 이 버그로 오배정된 `track_id`(과 그것을 이어받은 트랙 편집·병합 이력)는 소급 재계산하지 않는다.
- 회귀 가드: `ai-server/tests/test_bytetrack_alignment.py`.

## 11.3 SAM2 — VOS(추적) + 분할

> **분할 백엔드**: **Meta 공식 sam2(Apache-2.0)** `SAM2ImagePredictor`(HF `facebook/sam2-hiera-tiny`). 구 ultralytics SAM(AGPL-3.0)은 제거됨. ai-server에서 `set_image`+`predict`(point/box) → 마스크를 `cv2.findContours`로 외곽 폴리곤 변환. HTTP 경로(`/infer/sam2/segment`·`/track`)·polygon 응답 스키마 불변(계약 호환).

### 객체 자동 추적 (RQ-SFR-08-01, UC-004)
- 시작 프레임에서 객체를 박스/시드로 지정 → **SAM2 VOS**가 후속 프레임 위치(BBox)·경계(폴리곤) 자동 추적·갱신
- 추적 출력 형태는 선택 객체의 형태를 따른다 — **박스 객체는 박스로, 폴리곤 객체는 폴리곤으로** 후속 프레임에 반영된다(모달 형태 라디오와 무관)
- `POST /v1/frames/{srcSn}/sam2-track` (path/body srcSn 불일치 400, 본인 미배정 IDOR 차단)
- 자동 라벨 저장(`AUTO_LBL_YN='Y'`) + 트랙 보간으로 빈 프레임 보충
- **현 구현은 프레임별 bbox 전파 근사 추적**, 메모리 기반 고품질 VOS는 설계 타깃(planned)
- **mock 응답 프레임은 결과에서 제외 + 안내 메시지**(분할·오토라벨과 동일 규약, CWE-345): ai-server 가
  `mock=true`(`weights_missing`/`load_failed`/`env_mock`/`empty_mask`)로 응답하면 좌표가 **시드 폴리곤
  복사본**(`score` 0.9/0.5)이라 정상 결과와 구분되지 않으므로 BE 가 그 프레임을 `tracked` 에서 빼고
  `ApiResponse.message` 에 안내를 싣는다 — 전량 제외면 "AI 모델 미로드 — 결과 신뢰 불가", 일부만 제외면
  "일부 결과의 신뢰도를 보장할 수 없습니다.". 내부·포털 경로 공통이며 FE 는 이 message 로 경고를 띄운다
- **판정은 "긍정 증명" 기준 — fail-closed** (CWE-345/1287): 게이트가 `mock=true` 라는 **부정 신호만**
  확인하면 ai-server 가 mock 메타를 **생략한 응답**(`{"track_id":..,"polygon":..,"score":0.9}`)에서
  primitive `boolean` 기본값 `false` 로 채워져 "정상 응답"으로 오인된다. 그래서 실모델 결과로 신뢰하려면
  ai-server 가 `source="model"` 을 **명시**해야 하고, 필드 생략(`source=null`)·오타·미래 값은 모두
  신뢰하지 않는다. 판정 단일 원천은 `AiMockMeta.untrusted(mock, source)` 이며 SAM2 track/segment·YOLO
  DTO 가 `untrusted()` 로 노출한다 — **호출부는 `mock()` 을 직접 읽지 않는다**
- **드리프트 가드**: ai-server 계약 스냅샷(`backend/src/test/resources/contracts/ai-server-sam2-schema.json`)을
  BE 리소스로 동봉해 `Sam2MockMetaDriftTest` 가 **실행 환경과 무관하게 항상** 검증한다(ai-server 트리가
  함께 있으면 `schemas.py` 와 스냅샷을 교차검증 + `source` 기본값이 BE 신뢰 상수와 같은지 확인).
  ai-server 스키마를 바꾸면 이 스냅샷도 같은 커밋에서 갱신해야 한다
- **★배치 경로의 mock 게이트 — 배포 환경 fail-closed + dev 도 미저장 (NEW-H1)**: 온라인 경로와 달리
  배치는 사용자에게 안내할 화면이 없으므로 **저장 자체를 막는다**. `YoloAutolabelStep`·`Sam2SegmentStep`
  둘 다 `untrusted()`(+ null/결측 응답)를 확인해, **stg/prd 면 사유와 무관하게 첫 감지 즉시 스텝을
  FAILED**(all-or-nothing, REQUIRES_NEW 롤백)시킨다. 판정은 `DeployedEnvironmentDetector` 단일 원천이며
  추가 네트워크 호출이 없다.
  ⚠ **local/dev 동작은 두 스텝이 다르고, 그 차이가 의도된 것이다** — YOLO 는 진행(WARN 만)하지만
  **SAM2 는 폴리곤을 저장하지 않고 스킵**한다. ai-server 의 mock 형상이 대칭이 아니기 때문이다:
  YOLO 의 `weights_missing`/`load_failed` 는 **빈 detections** 라 dev 에서 진행해도 적재될 가짜 라벨이
  없지만, SAM2 `_mock_segment` 는 **사유와 무관하게 항상** 합성 사각 폴리곤(score 0.95)을 만든다.
  즉 SAM2 의 dev 스킵은 관대함을 줄인 것이 아니라 **YOLO 의 "빈 detections" 와 결과를 맞춘 것**이다.
  이 경로는 ai-server 기동 가드(`app/startup_guard.py`)로 대체되지 않는다 — `weights_missing`/
  `load_failed` 는 `AI_MOCK_MODE` 와 무관한 별도 사유라, SAM2 모델만 부분 배포 안 된 폐쇄망 형상에서는
  YOLO 가 실모델로 통과하고 이 단계만 mock 이 된다
- 코드: `Sam2TrackTool`, `Sam2TrackService`(mock 게이트), `Sam2SegmentStep`(배치 mock 게이트),
  `YoloAutolabelStep`(배치 mock 게이트), `DeployedEnvironmentDetector`, `AiServerClient`,
  `AiMockMeta`(신뢰 판정 단일 원천)

### 객체 외곽 경계 자동 밀착 (RQ-SFR-08-02, UC-005)
- 캔버스에서 클릭(포인트)/박스로 객체 지목(단축키 G) → SAM2 분할이 외곽 폴리곤+신뢰도 산출
- `POST /v1/frames/{srcSn}/sam2-segment` (points 또는 box 정확히 1개, @AssertTrue 배타 검증)
- 응답 폴리곤 좌표 검증(CWE-20) 후 Douglas-Peucker(`POLYGON_SIMPLIFY_TOLERANCE`) 단순화
- 이미지 상한 20MB(413), mock 응답은 자동 적용 차단
- **입력 방식(FE)**: 기본은 클릭으로 positive-point 를 누적한 뒤 Enter/더블클릭으로 1회 확정. **AI 분할 도구 활성 시 우측 객체 속성 패널의 "AI 분할 정밀도" 섹션**(경계 세밀함 슬라이더와 같은 섹션)에 있는 **"즉시 그리기"** 토글(기본 OFF)을 켜면 클릭마다 누적 점 전체로 즉시 분할해 **프리뷰 폴리곤**을 갱신(반복 정교화)하고, 확정 시 프리뷰를 커밋. 프리뷰 경로에도 mock(빈 폴리곤)/저신뢰 자동적용 차단이 동일 적용되며, 요청 세대 토큰·확정 큐잉으로 동일 프레임 in-flight 경합(마지막 클릭 누락·유령 프리뷰·조용한 소실)을 방지
- 코드: `Sam2SegmentService`(BE), `OverlayLayer`/`ObjectAttributePanel`/`CanvasShell`(FE 즉시 프리뷰·토글)

## 11.4 트랙 보간 (CVAT 포팅)

- `TrackInterpolationStep` + `batch/interpolation/TrackInterpolator` — 키프레임 기반 선형 보간(BBox)
- 빈 프레임을 `Map<frameNo, Bbox>`로 채움
- CVAT 알고리즘 Java 포팅 (`docs/analysis/portable-modules/01-track-interpolation.md`)
- MASK↔RLE↔Polygon 변환은 `common/util/MaskRleConverter` → [19](19-external-security-cvat.md#cvat-포팅)

## 11.5 정밀도 조절

YOLO conf/iou, SAM2 폴리곤 epsilon은 시스템 설정 화이트리스트로 조절 (구 `imgsz` 는 로더 640 고정이라 조정이 무효여서 2026-08-18 폐지) → [10 §10.5](10-labeling.md#정밀도-설정-rq-sfr-08-03).

> **슬라이더 초기값 조회**(2026-08-08) — 라벨링 화면은 저장된 기본값 두 개(`confThreshold`·`simplifyTolerance`)를 관리 영역 밖의 읽기 전용 경로 **`GET /v1/ai-defaults`**(검수자·작업자 공통)로 받는다. 값이 없거나 숫자로 해석되지 않는 항목은 응답에서 생략되고 화면이 자체 상수로 폴백한다. 쓰기 대응물은 없다(설정 변경은 검수자 전용 관리 경로). 상세 → [10 §10.5.1](10-labeling.md#1051-슬라이더-초기값-조회--get-v1ai-defaults-2026-08-08-신설).

> **per-실행 수동 조절**(2026-07-22) — 시스템 설정값을 **기본값**으로, 라벨러가 라벨링 화면에서 실행 직전 조절 가능(세션 한정). **AI 탐지**=인식 민감도(conf)+경계 세밀함(폴리곤 형태 한정), **AI 분할**=경계 세밀함만(SAM2 신뢰도 미수용). 미조절 시 요청 body에서 파라미터를 생략해 시스템 설정 기본값으로 동작(무회귀). 상세 → [10 §10.5](10-labeling.md#정밀도-설정-rq-sfr-08-03).

## 11.6 AI 작업 진행 중 화면 동작 — 편집 차단 · 진행 표시 · 취소 (2026-07-31, FE)

AI 탐지 · AI 분할 · AI 추적 · **AI 자동 추적**(2026-08-18 추가 — 위 11.2 온디맨드 자동 추적 화면 배선)은 라벨 저장/불러오기와 **하나의 배타 축(busy)** 을 공유한다. 하나가 도는 동안 다른 하나는 시작되지 않고, 그 사이 캔버스 편집은 **입력 단계에서** 차단된다. 규칙 전문(차단 진입점 표·접근성·범위) → [10 §10.6](10-labeling.md#106-장시간-작업-중-편집-차단--진행-표시--취소-2026-07-31).

| 항목 | 동작 |
|------|------|
| 차단 | 그리기·선택·이동·삭제 · 도구 전환 · 프레임 이동 · 저장/검수제출/다른 AI 실행 · 단축키 · 되돌리기 · 버전 롤백 · 비식별 신고 |
| 진행 표시 | **300ms 초과**부터 캔버스 오버레이(작업명 + 경과 초 + 취소). 짧은 작업은 표시하지 않는다 |
| 문구 | "AI 탐지 / AI 분할 / AI 추적 / AI 자동 추적 진행 중" — **모델명(YOLO/SAM/SAM2) 미노출**(단일 소스 `busyPolicy.ts`) |
| 취소 | 취소 버튼 클릭 · Enter/Space · ESC. **클라이언트 결과 폐기이며 ai-server 추론을 중단시키지 않는다** |
| 취소 후 응답 | 세대 토큰으로 폐기 — 같은 프레임이어도 라벨에 반영되지 않는다(AI 추적의 부분 도착분도 전량 폐기) |
| 프레임 스코프 | 작업은 시작한 프레임에 묶인다. 프레임/영상 전환 후 도착한 결과는 현재 화면 라벨을 바꾸지 않는다 |
| fail-safe | 최대 5분 초과 시 자동 해제(응답 누락으로 화면이 영구 잠기지 않게) |

- **즉시 그리기 예외**: AI 분할 클릭 누적은 오버레이가 뜨기 전(지연 창 <300ms)까지 허용되고 확정은 큐에 보존된다 — 작업이 끝나면 누적점 전체로 실행되므로 조작이 소실되지 않는다. 오버레이가 뜬 뒤에는 누적하지 않는다.
- **ESC 취소는 그 확정 큐도 함께 비운다** — 취소한 작업이 뒤늦게 발사되면 취소와 정반대 동작이 된다. 누적점은 보존한다.
- **범위 밖**: 크로스탭·다중 사용자 동시성(서버 낙관적 잠금 409 담당), 배치 파이프라인 진행 표시.
- 포털 라벨링(`/portal/label/:id`, 데이터마트 영상)은 AI 분할·추적을 제공하므로 같은 규칙이 적용된다. **포털 업로드 라벨링**(`/portal/uploads/*`, ADR-013 별도 경로)은 AI 진입점이 없어 AI busy 가 발생하지 않고 저장 진행 표시·취소만 동작한다.

## 11.7 관련 데이터 (DB)

`LS_DATA_LBL`(AI 출처·신뢰도·트랙ID — V6 흡수). → [18](18-database.md).
