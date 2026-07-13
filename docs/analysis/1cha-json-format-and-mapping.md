# 1차 프레임 라벨 JSON 포맷 분석 & 2차 LS_* Import 매핑표

> 작성일: 2026-07-13
> 근거: 1차 실제 산출 파일 `교통사고 데이터셋 구축_개인정보/00000014/00000048.json` (프레임 1개 라벨링)
> 목적: 1차(1차 사업 산출) 프레임 JSON → 2차(본 저작도구) `LS_*` 스키마 적재 매핑 정의

---

## 0. 요약 (TL;DR)

- **1차 = 프레임마다 자기완결형 COCO 계열 JSON 1파일.** `info + dataset + video + image + annotations[] + categories[]`를 매 프레임에 통째로 중복 임베드.
- **2차 = 정규화 관계형 `LS_*` 테이블.** 영상=`LS_DATA_RAW`, 프레임=`LS_DATA_SRC`, 라벨=`LS_DATA_LBL`(+마스터 `LS_LABEL`), 시계열/텍스트 메타=`LS_DATA_META`. 교환 표면은 `V_COMPLETED_*` View.
- Import 시 **좌표 포맷 변환(필수)**, **라벨 어휘 매핑(필수)**, **영상 기술메타/프레임별 설명 저장위치 부재(스키마 갭)** 3건이 핵심 과제.

---

## 1. 1차 JSON 구조 (실측 복원)

파일 1개 = 프레임 1개. 최상위 8개 키.

```jsonc
{
  "info":    { year, version, description, date_created },
  "dataset": { identifier, name, src_path, label_path, total_count, origin_count, augmentation_count },
  "licences": [],
  "video":   { /* 영상/CCTV 메타 — 아래 표 */ },
  "image":   { /* 이 프레임 메타 — 아래 표 */ },
  "annotations": [ /* 객체 라벨 + 텍스트 메타(혼재) — 아래 표 */ ],
  "categories":  [ /* 라벨 마스터 자기기술 — 아래 표 */ ],
  "type": "instance"
}
```

### 1-1. `video` 블록 (실측값)

| 필드 | 실측 예시값 |
|---|---|
| id | `1561` |
| file_name | `org_MARKANY_20250913125738_2256.mp4` (MARKANY = 비식별 벤더 프리픽스) |
| date_created | `2025-09-13 12:57:38` |
| type / format | `mp4` / `h264` |
| filesize | `17` |
| location | `제주특별자치도` |
| license_id | `""` |
| length | `71255ms` (**밀리초 단위**) |
| fps | `25` |
| frames | `71` |
| aspect_ratio | `1.78` |
| width / height | `1920` / `1080` |
| resolution | `FHD` |
| bit_rate | `2050627` |
| pixel | `1920x1080` |
| weather | `""` |
| coordinates | `33.5099190, 126.5034070` |
| stdg_cd | `5011000000` (법정동코드) |
| data_source | `""` |
| cctv_name | `생_용담2동_용해로_교차로_1(용담해안도로 방향)` |
| cctv_height / cctv_azimuth | `null` / `null` |
| cctv_mng_no | `2256` |
| anonymity / pseudonymity | `N` / `N` |
| privacy_included | `Y` |
| ai_generated | `N` |
| event_name | `쓰러짐` |
| event_level1_name / level2 / level3 | `생활안전` / `쓰러짐` / `쓰러짐` |
| time_of_day | `DAY` |
| season | `FALL` |
| event_log | `제주특별자치도 교통사고` |

### 1-2. `image` 블록 (실측값)

| 필드 | 실측 예시값 |
|---|---|
| id | `186872` |
| augmentation_yn | `N` |
| file_name | `00000048.jpg` |
| width / height | `1920` / `1080` |
| date_captured | `2025-12-12 02:33:22` |
| license_id | `""` |
| video_id | `1561` |
| type | `jpg` |
| frame_num | `1184` |
| anonymity / pseudonymity | `N` / `N` |
| privacy_included | `Y` |
| **description** | `오토바이가 빗길 주행 중 넘어짐` (**프레임 단위 자연어 설명**) |

### 1-3. `annotations[]` 블록 (실측 — 객체 라벨 7건 + 텍스트 메타 3건 혼재)

**bbox 포맷 = `[x, y, width, height]` (flat, COCO 스타일)**

| id | category_id | bbox `[x,y,w,h]` |
|---|---|---|
| 419695 | person | `70.53, 309.42, 103.13, 274.89` |
| 419696 | car | `1809.74, 121.11, 110.1, 117.77` |
| 419697 | two_wheeler | `0.0, 345.85, 126.35, 253.28` |
| 419698 | two_wheeler | `79.4, 282.41, 114.83, 159.83` |
| 419699 | car | `712.05, 211.75, 325.2, 308.64` |
| 419702 | car | `1077.98, 197.22, 296.97, 234.73` |
| 419703 | car | `1157.61, 0.0, 183.6, 117.91` |

각 객체 annotation 공통: `track_id: ""`, `polygon: null`, `keypoints: null`, `text: null`
→ **이 프레임은 bbox만. 세그멘테이션/트래킹/키포인트 없음.**

텍스트형 pseudo-annotation 3건 (bbox=null, text만):

| category_id | text |
|---|---|
| image_description | `오토바이가 빗길 주행 중 넘어짐` |
| privacy_included | `Y` |
| de-identification | `N` |

### 1-4. `categories[]` 블록 (실측 — 라벨 마스터 자기기술)

| id | name | type | description |
|---|---|---|---|
| image_description | 이미지설명 | TEXT | `""` |
| privacy_included | 개인정보포함여부 | TEXT | `""` |
| de-identification | 비식별여부 | TEXT | `""` |
| person | 사람 | BBOX | 사람 |
| fallen_person | 쓰러진 사람 | BBOX | 쓰러진 사람 |
| car | 차량 | BBOX | car |
| two_wheeler | 이륜차 | BBOX | 오토바이, 자전거 모두 포함 |

각 category 공통: `supercategory: null|""`, `keypoints: null`, `skeleton: null`.
⚠️ `fallen_person`은 카테고리 정의만 있고 이 프레임 annotation엔 **미사용**(이벤트는 "쓰러짐"인데 실제 라벨은 person/car/two_wheeler).

---

## 2. 2차(우리) 대응 스키마

| 개념 | 테이블 | 키 필드 |
|---|---|---|
| 영상 | `LS_DATA_RAW` | RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, PRVC_YN, DE_IDENT_YN, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, ORGNL_RAW_SN, DATA_STTS_CD |
| 프레임 | `LS_DATA_SRC` | SRC_SN, RAW_SN, FRM_NO, SRC_FILE_PATH_NM, DE_IDNTF_SRC_FILE_PATH_NM, SHT_DT |
| 라벨(인스턴스) | `LS_DATA_LBL` | LBL_SN, SRC_SN, LBL_ID, LBL_TYPE_CD, LBL_NM, POINT_CN, TRCK_ID, REG_USER_NO |
| 라벨(마스터) | `LS_LABEL` | LBL_ID, LBL_NM, COLR_VL, LBL_TYPE_CD, SORT_SEQ |
| 라벨 속성 | `LS_LABEL_ATTR` / `LS_DATA_LBL_ATTR_VAL` | ATRB_ID / ATRB_VL_ID |
| 텍스트·시계열 메타 | `LS_DATA_META` | META_SN, **RAW_SN**, META_KEY, META_VL (UK: RAW_SN+META_KEY) |

- `LS_DATA_LBL.POINT_CN` 포맷 = **nested `[[x,y],...]`** (V66에서 flat/객체배열을 nested로 통일).
- `LBL_TYPE_CD` 허용값 = **BBOX / POLYGON / POINT** (TEXT 없음 — 텍스트는 META로).

---

## 3. Import 매핑표 (1차 → 2차)

### 3-1. `video` → `LS_DATA_RAW` (+ 보완 META)

| 1차 필드 | 2차 대상 | 변환 규칙 / 비고 |
|---|---|---|
| file_name | `LS_DATA_RAW.RAW_FILE_PATH_NM` | 경로 정책에 맞게 재구성 |
| length `71255ms` | `LS_DATA_RAW.VDO_LEN_SEC` | **ms → 초** (÷1000, 반올림/버림 정책 결정) |
| cctv_mng_no `2256` | `LS_DATA_RAW.VMS_CCTV_ID` | MNG_RESOURCE_CCTV FK 매칭 |
| video.id `1561` | `LS_DATA_RAW.VMS_CLIP_ID` | 클립 식별자 매칭(관제 MNG_CLIP_MASTER 정합 확인) |
| event_name `쓰러짐` / level1~3 | `LS_DATA_RAW.EVNT_TYPE_CD` | **코드 매핑 필요** (자연어 3계층 → MNG_EX_EVNT_TYPE 코드 1개). 무손실 보존하려면 level1~3 원문을 META로 병행 적재 |
| stdg_cd `5011000000` | `LS_DATA_RAW.LCLGV_CD` | 법정동코드 ↔ LCLGV 매핑 확인 |
| privacy_included `Y` | `LS_DATA_RAW.PRVC_YN` | Y/N 그대로 |
| anonymity / pseudonymity | `LS_DATA_RAW.PRVC_TYPE_CD` | 비식별 유형코드 규칙 결정 |
| de-identification(annotation) `N` | `LS_DATA_RAW.DE_IDENT_YN` | annotation 블록에서 추출 |
| date_created / SHT | `LS_DATA_RAW.SHT_DT` | 촬영일시 |
| **fps `25`** | ❌ RAW 컬럼 없음 | **META** (`META_KEY='video.fps'`) |
| **bit_rate `2050627`** | ❌ | **META** (`video.bit_rate`) |
| **format `h264`** | ❌ | **META** (`video.codec`) |
| **resolution/pixel/aspect_ratio** | ❌ | **META** (`video.resolution` 등) |
| **weather `""`** | ❌ | **META** (`video.weather`) |
| **time_of_day `DAY`** | ❌ | **META** (`video.time_of_day`) |
| **season `FALL`** | ❌ | **META** (`video.season`) |
| **coordinates** | ❌ | **META** (`video.coordinates`) 또는 CCTV 마스터 참조 |
| cctv_name | (MNG_RESOURCE_CCTV 파생) | 원문 보존 필요 시 META |
| ai_generated `N` | `ORGNL_RAW_SN` 유무로 대체 표현 | 증강본이면 ORGNL_RAW_SN 세팅 |

### 3-2. `image` → `LS_DATA_SRC`

| 1차 필드 | 2차 대상 | 변환 규칙 / 비고 |
|---|---|---|
| file_name `00000048.jpg` | `LS_DATA_SRC.SRC_FILE_PATH_NM` | 원본 프레임 경로. 비식별본은 `DE_IDNTF_SRC_FILE_PATH_NM` |
| frame_num `1184` | `LS_DATA_SRC.FRM_NO` | ⚠️ 파일명 순번(48) ≠ frame_num(1184) — **어느 값을 FRM_NO로 쓸지 결정** (UK: RAW_SN+FRM_NO 충돌 주의) |
| date_captured | `LS_DATA_SRC.SHT_DT` | |
| video_id `1561` | (RAW_SN 조인 키) | video.id → RAW_SN 룩업 |
| width/height | (RAW 공통) | SRC엔 별도 컬럼 없음 |
| **description `오토바이가 빗길…`** | ⚠️ **저장위치 없음** | LS_DATA_META는 RAW_SN 키 → **프레임 단위 설명 불가**. §4-2 갭 참조 |

### 3-3. `annotations[]`(객체) → `LS_DATA_LBL`

| 1차 필드 | 2차 대상 | 변환 규칙 / 비고 |
|---|---|---|
| category_id | `LS_DATA_LBL.LBL_ID` + `LBL_NM` | **라벨 어휘 매핑표(§3-5) 적용** |
| bbox `[x,y,w,h]` | `LS_DATA_LBL.POINT_CN` | **변환 필수**: `[x,y,w,h]` → nested 4점 `[[x,y],[x+w,y],[x+w,y+h],[x,y+h]]` (또는 [[x,y],[x+w,y+h]] 2점 규칙 — 팀 확정 필요) |
| (bbox 존재) | `LS_DATA_LBL.LBL_TYPE_CD='BBOX'` | polygon 있으면 POLYGON |
| track_id `""` | `LS_DATA_LBL.TRCK_ID` | 빈문자→NULL 정규화 |
| polygon/keypoints/skeleton | POINT_CN(POLYGON) / ❌ | **keypoints·skeleton 대응 없음** — 본 프레임 all null이라 무손실이나, 존재 데이터는 갭 |

### 3-4. `annotations[]`(텍스트) → `LS_DATA_RAW` 플래그 / `LS_DATA_META`

| 1차 category_id | 2차 대상 |
|---|---|
| image_description | (프레임 설명) → §4-2 갭 |
| privacy_included `Y` | `LS_DATA_RAW.PRVC_YN` |
| de-identification `N` | `LS_DATA_RAW.DE_IDENT_YN` |

### 3-5. 라벨 어휘 매핑표 (1차 category_id → 2차 LS_LABEL) ⚠️ 핵심

| 1차 category_id | 2차 LS_LABEL.LBL_NM | 상태 |
|---|---|---|
| person | `person` | ✅ 일치 |
| car | `car` | ✅ 일치 |
| **two_wheeler** | `bicycle` **또는** `motorbike` | ⚠️ **1차는 통합("오토바이·자전거 모두 포함"), 2차는 분리** — 자동 분할 불가. 기본 정책 필요(예: 통합→`motorbike` 매핑 or 신규 통합라벨 추가) |
| **fallen_person** | `fallen-person` | ⚠️ **표기 불일치**(underscore ↔ hyphen). 매핑 테이블로 흡수 |
| image_description | — | 라벨 아님 → META/RAW |
| privacy_included | — | 라벨 아님 → RAW.PRVC_YN |
| de-identification | — | 라벨 아님 → RAW.DE_IDENT_YN |

> 2차 LS_LABEL 시드 전체: person, car, bicycle, motorbike, bus, truck, animal, fire(POLYGON), smoke(POLYGON), water(POLYGON), fallen-person, vehicle-accident, object.

---

## 4. 스키마 갭 (Import 전 결정 필요)

1. **좌표 포맷 변환** — 1차 `[x,y,w,h]` ↔ 2차 nested `[[x,y],...]`. 4점 폴리곤화 vs 2점(좌상·우하) 규칙을 **BBOX 표현 규약으로 확정**. FE(konva) 렌더 규약과 일치시킬 것.
2. **프레임 단위 텍스트 설명(`image.description`) 저장위치 부재** — `LS_DATA_META`는 `RAW_SN`(영상) 키. 프레임별 설명을 받으려면 (a) SRC 단위 메타 테이블 신설, (b) LS_DATA_META에 SRC_SN 컬럼 추가, (c) 손실 허용 중 택1.
3. **영상 기술메타 손실 위험** — fps/bit_rate/format/resolution/weather/time_of_day/season/coordinates가 RAW 컬럼에 없음. 1차 재현·역호환 필요 시 **META로 보존**(권장: `video.*` 키 네이밍).
4. **라벨 어휘 불일치** — `two_wheeler` 분할 정책 + `fallen_person`/`fallen-person` 표기 통일. **라벨 매핑 테이블(1차코드→LBL_ID)** 을 import 어댑터에 상수로 둘지, DB 매핑 테이블로 둘지 결정.
5. **event 3계층 → 코드 1개 축약** — level1~3 자연어를 EVNT_TYPE_CD 하나로 축약 시 정보 손실. 원문 병행 META 보존 권장.
6. **length ms→초 반올림 정책**, **frame_num vs 파일순번 중 FRM_NO 선정**, **track_id 빈문자→NULL** 정규화 규칙 명문화.
7. **keypoints/skeleton** — 본 프레임 미사용이나 1차 스키마엔 존재. 향후 포즈 데이터 유입 시 2차 대응 없음(현재 갭, 필요 시 확장).

---

## 5. Import 파이프라인 스케치 (제안, 미확정)

```
1차 JSON 파일들 (프레임별)
  → [그룹핑] video.id 로 영상 단위 묶기
  → LS_DATA_RAW 1행 upsert (video 블록, 첫 프레임 기준) + 기술메타 → LS_DATA_META
  → 프레임별 LS_DATA_SRC upsert (image 블록)
  → annotations[] 순회:
       · 객체(bbox≠null) → 라벨어휘 매핑 → 좌표변환 → LS_DATA_LBL insert
       · 텍스트(text≠null) → RAW 플래그 / (image_description는 갭 결정 따름)
  → categories[] 는 LS_LABEL 마스터와 대조·검증만(자기기술은 재조립용, import 시 미저장)
```

> ⚠️ 이 스케치는 초안. 실제 구현 전 §4 갭 6~7건을 팀에서 확정해야 함. 특히 좌표 규약(1)·프레임 설명(2)·라벨 매핑(4)은 blocker.
