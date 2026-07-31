# AI기반 CCTV 어노테이션 포맷 및 데이터 구조

> **정본 스펙.** 원본 엑셀 `AI기반CCTV_어노테이션 포맷 및 데이터 구조_정리.xlsx`(시트 `데이터구조`, 2026-07-31 갱신판)를 그대로 옮긴 것이다.
> 엑셀은 diff 가 되지 않아 버전관리에서 제외하고 본 문서를 정본 사본으로 둔다. 원본이 갱신되면 이 문서도 같은 커밋에서 갱신한다.
>
> 발행처: 한국지능정보사회진흥원(NIA) AI 데이터 품질 관리 기준

## 개요

| 항목 | 값 |
|---|---|
| 블록 | `info` · `dataset` · `licences` · `video` · `image` · `annotations` · `event` · `categories` · `type` |
| 총 속성 | 101 (필수 75 / 선택 25) |
| 기반 | COCO Dataset 표준 + 사업 확장 |

### `video` 블록 항목출처 분포

`video` 는 영상 단위 메타로, 값을 누가 채우는지가 블록마다 다르다.

| 출처 | 건수 | 비고 |
|---|:--:|---|
| **관제서버** | 25 | 저작도구가 인입 테이블로 수신 |
| **수동입력** | 6 | `weather` · `anonymity` · `pseudonymity` · `privacy_included` · `time_of_day` · `season` |
| **VLM 출력** | 1 | `vd_description` |
| 미정의(`-`) | 1 | `license_id` |

### 항목출처 값의 의미

| 표기 | 의미 |
|---|---|
| `관제서버` | 관제서버가 제공 (저작도구는 수신만) |
| `수동입력` | 작업자가 화면에서 입력 |
| `VLM 출력` | 외부 VLM 서비스 산출 (미전달 시 수동입력 폴백) |
| `COCO_DATASET` | COCO 표준 정의 — 저작도구가 조립 |
| `라벨링공통항목` | 데이터셋 공통 규약 — 저작도구가 조립 |
| `이미지 프레임 기준 (라벨러 작업)` | 프레임 단위 라벨러 판단 |
| `-` | 미정의 (현재 빈값/상수) |

---

## 데이터 구조

### `info` — 프로젝트 정보

> Type `object` · 프로젝트에 대한 기본 정보와 설명 정보를 저장하는 객체

| 속성 | Type | 필수 | 설명 | 작성예시 | 항목출처 |
|---|---|:--:|---|---|---|
| `year` | number | 선택 | 만든연도 | 2025 | COCO_DATASET |
| `version` | string | 선택 | 버전 | 1.0 | COCO_DATASET |
| `description` | string | 선택 | 데이터셋 설명 | 지자체 CCTV 이상행동 데이터셋 | COCO_DATASET |
| `date_created` | string | 선택 | 데이터셋 생성일자 (YYYY-MM-DD) | 2025-03-28 | COCO_DATASET |

### `dataset` — 생성한 데이터셋 영상단위 정보

> Type `object` · 데이터셋의 원본 식별 및 데이터 경로를 저장하는 객체

| 속성 | Type | 필수 | 설명 | 작성예시 | 항목출처 |
|---|---|:--:|---|---|---|
| `identifier` | string | 필수 | 데이터셋 식별자(video_id) | AUD_01 (데이터유형_순번) | 라벨링공통항목 |
| `name` | string | 필수 | 데이터셋 이름 | 자율 주차를 위한 학습용 데이터셋 | 라벨링공통항목 |
| `src_path` | string | 필수 | 데이터셋 폴더 위치 | /dataSet/text/ | 라벨링공통항목 |
| `label_path` | string | 필수 | 데이터셋 레이블 폴더 위치 | /dataSet/text/ | 라벨링공통항목 |

### `licences`

| 속성 | Type | 필수 | 설명 | 작성예시 | 항목출처 |
|---|---|:--:|---|---|---|
| `id` | number | 선택 | 라이선스의 고유 ID | 1 | - |
| `name` | string | 선택 | 라이선스의 이름 또는 유형 | Private Use | - |
| `url` | string | 선택 | 라이선스 전문이 설명된 웹 주소 | http://example.com | - |

### `video`

| 속성 | Type | 필수 | 설명 | 작성예시 | 항목출처 |
|---|---|:--:|---|---|---|
| `id` | string | 필수 | ID | DSC_0001_개체번호 (분류_순번) | 관제서버 |
| `filename` | string | 필수 | 파일 이름 | DSC_0001 (분류_순번) | 관제서버 |
| `date_created` | string | 필수 | 촬영일시 (YYYY-MM-DD hh:mm:ss) | 2025-09-09 14:24:00 | 관제서버 |
| `type` | string | 필수 | 데이터 형식 | mp4, PNG, JPG | 관제서버 |
| `format` | string | 필수 | (코덱)포맷 | h.264/mpeg-4 | 관제서버 |
| `filesize` | number | 필수 | 크기 | 4800KB | 관제서버 |
| `location` | string | 필수 | 촬영 지역명 | 서울시 종로구 (동까지만 표기) | 관제서버 |
| `license_id` | string | 필수 | 라이선스 | - | - |
| `length` | string | 필수 | 영상길이 | 10M | 관제서버 |
| `fps` | string | 필수 | 프레임 재생속도 | 30 | 관제서버 |
| `frames` | number | 필수 | 총 프레임 수(FPS) | 60 | 관제서버 |
| `aspect_ratio` | string | 필수 | 종횡비 | 4:3 | 관제서버 |
| `width` | number | 필수 | 너비 | 4031 | 관제서버 |
| `height` | number | 필수 | 높이 | 3024 | 관제서버 |
| `resolution` | string | 필수 | 해상도 | FHD | 관제서버 |
| `bit` | string | 필수 | 비트값 | 24bit | 관제서버 |
| `pixel` | string | 필수 | 화소 | 4K | 관제서버 |
| `weather` | string | 필수 | 날씨정보 | 맑음/비/눈/안개 등 | 수동입력 |
| `coordinates` | string | 선택 | 좌표 | 37.575832, 126.973856 | 관제서버 |
| `og_cd` | string | 선택 | 기관코드 | 3000000 | 관제서버 |
| `cctv_name` | string | 선택 | CCTV 명 | 광화문4거리 3번CCTV | 관제서버 |
| `cctv_height` | number | 선택 | CCTV 높이 | 4.5 | 관제서버 |
| `cctv_azimuth` | number | 선택 | CCTV 방위각 | 175 | 관제서버 |
| `cctv_mng_no` | string | 선택 | CCTV 관리번호 | 25011 | 관제서버 |
| `anonymity` | string | 필수 | 익명여부 | Y / N | 수동입력 |
| `pseudonymity` | string | 필수 | 가명여부 | Y / N | 수동입력 |
| `privacy_included` | string | 필수 | 개인정보포함여부 | Y / N | 수동입력 |
| `event_id` | string | 필수 | 이벤트 분류 | ABA_0001 (분류_순번) | 관제서버 |
| `event_name` | string | 필수 | 이벤트명 | 특이 상황판별 | 관제서버 |
| `time_of_day` | string | 선택 | 시간대 | day/night | 수동입력 |
| `season` | string | 선택 | 계절 | spring/summer/autumn/winter | 수동입력 |
| `event_log` | string | 선택 | 관제일지 | 관제일지 | 관제서버 |
| `vd_description` | string | 필수 | 비디오 설명 | 장소, 날씨 , 상황, 환경, 심각성 | 이벤트에 대한 VLM이 출력하는 간단한 상황묘사 내용 |

### `image`

| 속성 | Type | 필수 | 설명 | 작성예시 | 항목출처 |
|---|---|:--:|---|---|---|
| `id` | number | 필수 | 이미지 고유 ID | 1 | COCO_DATASET |
| `file_name` | string | 필수 | 실제 이미지 파일명 | image.jpg | COCO_DATASET |
| `width` | number | 필수 | 이미지 가로 | 200 | COCO_DATASET |
| `height` | number | 필수 | 이미지 세로 | 200 | COCO_DATASET |
| `date_captured` | string | 필수 | 생성일시 (YYYY-MM-DD hh:mm:ss) | 2020-12-29 12:40:23 | COCO_DATASET |
| `license_id` | number | 필수 | 라이선스ID(licenses.id) | 1 | COCO_DATASET |
| `video_id` | string | 필수 | 추출 영상 ID(videos.id) | DSC_0001_개체번호 (분류_순번) | 자체 : 비디오 매핑을 위해 추가 |
| `type` | string | 필수 | 이미지 파일 확장자 | JPG, PNG 등 | 가이드라인_자율주행>라벨링 이미지 파일 공통참조항목 |
| `frame_num` | number | 필수 | 영상 내 프레임 순서 | 573 | 가이드라인_자율주행>라벨링 이미지 파일 공통참조항목 영상 내의 프레임 번호를 모를 경우 시퀀스하게 증가 |
| `anonymity` | string | 필수 | 익명여부 | Y / N | 이미지 프레임 기준 (라벨러 작업) |
| `pseudonymity` | string | 필수 | 가명여부 | Y / N | 이미지 프레임 기준 (라벨러 작업) |
| `privacy_included` | string | 필수 | 개인정보포함여부 | Y / N | 이미지 프레임 기준 (라벨러 작업) |
| `description` | string | 선택 | 이미지에 대한 설명 | 침수에 대한 이미지 | 기능 요구사항 SFR-09기반 변수명: COCO_DATASET 기반 |

### `annotations`

| 속성 | Type | 필수 | 설명 | 작성예시 | 항목출처 |
|---|---|:--:|---|---|---|
| `id` | number | 필수 | 어노테이션 고유 ID | 1001 | COCO_DATASET |
| `image_id` | number | 필수 | 이 객체가 속한 이미지 ID(images.id) | 101 | COCO_DATASET |
| `category_id` | string | 필수 | 이 객체의 클래스ID(categories.id) | 3 | COCO_DATASET |
| `track_id` | string | 선택 | 추적 ID | track-id-01 | 행위기반 학습을 위한 객체 추적 ID |
| `bbox` | number[] | 선택 | 바운딩 박스 정보[x, y, width, height] | [100, 200, 300, 400] | COCO_DATASET |
| `polygon` | number[][] | 선택 | 폴리곤 좌표 집합 | [[100, 150, 300, 350, 400, 350, 100,150]] | COCO_DATASET |
| `keypoints` | number[][] | 선택 | 키포인트 정보 [[x1 ,y1, v1], [x2, y2, v2], [x3, y3, v3]] | [ [15, 13, 0], [13, 11, 0], [16, 14, 0], [14, 12, 0], [11, 12, 0], [5, 11, 0] ] | COCO_DATASET |
| `text` | string | 선택 | 텍스트 정보 |  | 텍스트 라벨링 정보 (부가 메타) |

### `event` — VLM 이벤트 캡션·근거 어노테이션 (시계열 메타, 영상 단위)

> Type `object` · VLM 상황판별 결과. LS_DATA_META(영상 키) 적재. 전 항목 VLM 전달 수신

| 속성 | Type | 필수 | 설명 | 작성예시 | 항목출처 |
|---|---|:--:|---|---|---|
| `event_class` | string | 필수 | 이벤트 클래스(분류명) | 신체적 충돌을 동반한 싸움 | VLM 출력 |
| `question` | string | 필수 | 질문(이벤트 발생 여부·근거) | 영상에서 '신체적 충돌을 동반한 싸움' 이벤트가 발생했는지와, 이를 뒷받침하는 근거는 무엇인가? | VLM 출력 |
| `caption` | object | 필수 | 캡션 집합 (후보 c1~cn) |  | VLM 출력 + 미전달분 UI 수동입력 |
| `c1` | object | 필수 | 후보 캡션 1 |  | VLM 전달분 + 미전달분 수동입력 |
| `caption_text` | string | 필수 | 캡션 텍스트 | '신체적 충돌을 동반한 싸움' 상황으로 분류된 객체 상황 3개가 17.5초 동안 연속적으로 관찰되었음. | VLM 출력 / 미전달 시 수동입력 |
| `cot` | object | 필수 | 사고 과정(CoT) — 1·2·3단계 필수 |  | 단계별 상황 서술(3단계 필수) |
| `1단계` | string | 필수 | 1단계 상황 서술 | 도로에서 사람1이 사람2의 몸을 감싸는 동작을 하고 있음 | VLM 출력 / 미전달 시 수동입력 |
| `2단계` | string | 필수 | 2단계 상황 서술 | 사람1이 도로에서 사람2에게 발로 차는 동작을 하고 있음 | VLM 출력 / 미전달 시 수동입력 |
| `3단계` | string | 필수 | 3단계 상황 서술 | 도로에서 사람1이 뒤따르는 사람2에게 발을 돌려차고 있음 | VLM 출력 / 미전달 시 수동입력 |
| `c2` | object | 필수 | 후보 캡션 2 |  | VLM 전달분 + 미전달분 수동입력 |
| `caption_text` | string | 필수 | 캡션 텍스트 | '신체적 충돌을 동반한 싸움' 상황으로 분류된 객체 상황 3개가 17.2초 동안 연속적으로 관찰되었음. | VLM 출력 / 미전달 시 수동입력 |
| `cot` | object | 필수 | 사고 과정(CoT) — 1·2·3단계 필수 |  | 단계별 상황 서술(3단계 필수) |
| `1단계` | string | 필수 | 1단계 상황 서술 | 도로에서 사람2가 사람1에게 어깨를 잡히고 있음 | VLM 출력 / 미전달 시 수동입력 |
| `2단계` | string | 필수 | 2단계 상황 서술 | 사람2가 도로에서 사람1이 발로 차는 동작을 피하고 있음 | VLM 출력 / 미전달 시 수동입력 |
| `3단계` | string | 필수 | 3단계 상황 서술 | 도로에서 사람1이 뒤에서 이동하는 사람2에게 발을 돌려차고 있음 | VLM 출력 / 미전달 시 수동입력 |
| `answer` | string | 필수 | 답변 (이벤트 확인 결과) | '신체적 충돌을 동반한 싸움' 상황이 영상에서 확인됨. | VLM 출력 |
| `evidence` | object | 필수 | 근거 집합 (후보 c1~cn) |  | VLM 출력 |
| `c1` | object | 필수 | 후보 근거 1 |  | VLM 출력 |
| `evidence_text` | string | 필수 | 근거 텍스트 | 프레임 범위 393~918에서 객체 1,2번이 연속 추적됨. 프레임 ID 힌트: [393, 573, 847]. 시작/끝 프레임과 박스 연속성으로 이벤트 일관성이 확인됨. | VLM 출력 |
| `frame_id` | number[] | 필수 | 근거 프레임 ID 목록 | [393, 573, 847] | VLM 출력 |
| `obj_id` | string[] | 필수 | 객체 ID 목록 | ["1,2", "1,2", "1,2"] | VLM 출력 |
| `obj_bbox` | number[][] | 필수 | 객체 바운딩박스 목록 [x1,y1,x2,y2] | [[833,487,999,751], [906,381,1198,578], [687,431,829,695]] | VLM 출력 |
| `obj_label` | string[] | 필수 | 객체 라벨 목록 | ["human", "human", "human"] | VLM 출력 |
| `c2` | object | 필수 | 후보 근거 2 |  | VLM 출력 |
| `evidence_text` | string | 필수 | 근거 텍스트 | 프레임 범위 443~959에서 객체 1,2번이 연속 추적됨. 프레임 ID 힌트: [443, 642, 895]. 시작/끝 프레임과 박스 연속성으로 이벤트 일관성이 확인됨. | VLM 출력 |
| `frame_id` | number[] | 필수 | 근거 프레임 ID 목록 | [443, 642, 895] | VLM 출력 |
| `obj_id` | string[] | 필수 | 객체 ID 목록 | ["1,2", "1,2", "1,2"] | VLM 출력 |
| `obj_bbox` | number[][] | 필수 | 객체 바운딩박스 목록 [x1,y1,x2,y2] | [[923,451,1076,703], [523,711,1136,1068], [1147,518,1391,863]] | VLM 출력 |
| `obj_label` | string[] | 필수 | 객체 라벨 목록 | ["human", "human", "human"] | VLM 출력 |

### `categories`

| 속성 | Type | 필수 | 설명 | 작성예시 | 항목출처 |
|---|---|:--:|---|---|---|
| `id` | string | 필수 | 클래스 ID | 1 | COCO_DATASET |
| `name` | string | 필수 | 클래스 이름 | 도로 | COCO_DATASET |
| `type` | string | 필수 | 어노테이션 유형 | bbox / polygon / keypoints / text | COCO_DATASET |
| `supercategory` | string | 선택 | 상위분류명 |  | COCO_DATASET |
| `keypoints` | string[] | 선택 | 키포인트 라벨 | ["nose", "left_eye", "right_eye", "left_ear", "right_ear", "left_shoulder", "right_shoulder", "left_elbow", "right_elbow", "left_wrist", "right_wrist", "left_hip", "right_hip", "left_knee", "right_knee", "left_ankle", "right_ankle"] | COCO_DATASET |
| `skeleton` | number[][] | 선택 | 키포인트 연결 정보 | [ [15, 13], [13, 11], [16, 14], [14, 12], [11, 12], [5, 11], [6, 12], [5, 6], [5, 7], [6, 8], [7, 9], [8, 10], [1, 2], [0, 1], [0, 2], [1, 3], [2, 4], [3, 5], [4, 6] ] | COCO_DATASET |

### `type`

| 속성 | Type | 필수 | 설명 | 작성예시 | 항목출처 |
|---|---|:--:|---|---|---|
| `"instances"` |  |  |  |  | COCO_DATASET |


---

## 참고

- 예제 파일 — `docs/AI기반CCTV_어노테이션_예제_sample.json`
- 1차 산출물 대비 매핑 분석 — `docs/analysis/1cha-json-format-and-mapping.md`
- export 산출 규격 — LogiCraft `INTSPEC-004` (검수 승인 학습데이터 export)

### 표기 주의

- `작성예시` 는 스펙이 제시한 **형식**이다. 1차 실물 산출물과 다른 항목이 있다(예: `bit` 은 예시가 `24bit`(색심도)이나 1차 실물은 필드명 `bit_rate` 에 값 `2050627`(비트레이트)). 형식 판단은 본 스펙을 정본으로 한다.
- 들여쓰기된 속성은 상위 속성의 하위 키다(`event.caption.c1.cot.1단계` 등).
