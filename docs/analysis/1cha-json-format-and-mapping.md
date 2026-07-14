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

---

## 6. 필드 출처(Provenance) & NIA JSON Export 조달 전략 (2026-07-13 추가)

> 근거: 1차 DB 설계 wiki `docs/v1-wiki/15-database.md`(MySQL `KLID-AI-DB-001`) + 2차 MNG_* 공유 엔티티 실 컬럼.
> 목적: "2차 데이터로 NIA/1차 형식 JSON을 산출(export)할 수 있는가"에 답하기 위해 **각 필드의 원 출처**와 **2차 조달 경로**를 확정.

### 6-1. 1차는 메타를 전부 DB 컬럼으로 보유했다

1차 원천 테이블 **`LS_DATA_SRC`**(PK `DATA_RAW_SN`, "CCTV 클립 영상의 모든 메타데이터 관리")가 NIA `video` 블록을 거의 그대로 컬럼으로 가짐:

| NIA/1차 JSON | 1차 DB 컬럼 |
|---|---|
| file_name / type / format / codec | `RAW_FILE_NM` / `RAW_DATA_TYPE_CD` / `FILE_FMT` / `VDO_CDC` |
| frames / width / height / resolution | `FRM_CNT` / `WDTH` / `HGT` / `RSLTN` |
| date_created / location(+촬영인원) | `SHT_DT` / `SHT_LC`(+`SHT_PRSN`) |
| stdg_cd | `LCLGV_CD` |
| **coordinates** | **`WGS84_LAT`/`WGS84_LOT` decimal(10,7)** |
| **weather / time_of_day / season** | **`WTHR_CD` / `HR_TYPE_CD` / `SESN_CD`** (코드 컬럼) |
| event_name / levels | `EVNT_NM` / `EVNT_TYPE_CD`(+`EVNT_END_DT`) |
| cctv_mng_no / data_source | `VMS_CCTV_ID` / `CLCT_SRC` |
| anonymity / privacy / ai_generated | `DE_IDNTF_YN` / `PRVC_YN` / `AI_CRT_YN` |
| (AI 생성 프롬프트) | `PROMPT_CN` |
| fps | ❌ 원천 컬럼 없음 → `FPS_TYPE_CD`(`LS_PJT_STG_PRC`/`LS_PJT_DATA_STTS` 가공설정) |
| dataset(identifier/name/counts) | `LS_PJT`(PJT_ID/PJT_NM/GOAL_QTY/VER) + `LS_PJT_DATA_STATS` |
| categories | `LS_PJT_LBL`(LBL_NM/LBL_CLR/PRC_TYPE_CD/**UP_LBL_SN 계층**) |
| annotations(track/속성) | `LS_DATA_LBL_HSTRY`(TRCK_ID/`ATRB` JSON/ACTION_TYPE_CD) |

> ⚠️ v1은 MySQL·프로젝트 중심, v2는 PostgreSQL·영상 단위 → 스키마가 직접 매핑되지 않음. 위는 "1차엔 이 메타가 DB에 있었다"는 출처 확인용.

### 6-2. 2차 Export 조달 전략 — 갭은 3층으로 축소

앞 §4에서 "스키마 보강 6종"이라 했으나, **대부분은 MNG_* 조인으로 해결되어 2차 DB 보강조차 불필요**하다.

**① 🟢 MNG_* 조인으로 즉시 해결 (저장 불필요, export 쿼리에서 JOIN)**

| NIA 필드 | MNG_* 출처 |
|---|---|
| **coordinates** | `MNG_RESOURCE_CCTV.WGS84_LAT/WGS84_LOT` |
| cctv_name / resolution | `MNG_RESOURCE_CCTV.CCTV_NM` / `RESOLUTION` |
| location / stdg_cd | `MNG_CLIP_MASTER.LCLGV_CD` + `MNG_EX_LOCAL_GOV.SIDO_NM/SGG_NM` |
| file_name / length / format | `MNG_CLIP_MASTER.FILE_NM` / `VDO_LEN_SEC` / `FILE_FMT` |
| event_name / levels | `MNG_CLIP_EVNT_LST.EVNT_TYPE_CD` + `MNG_EX_EVNT_TYPE`(EVNT_CLS_CD/EVNT_CTGRY_CD 계층) |
| cctv_mng_no | `VMS_CCTV_ID` |

**② 🟡 ffprobe로만 조달 (MNG·1차원천에도 fps 컬럼 없음)**
- `fps` / `bit_rate` / `codec(VDO_CDC)` / `aspect_ratio` / `pixel` / `filesize` → 적재 시 ffprobe 1회 추출 → `LS_DATA_META`(`video.*`) 적재.

**③ 🟡 파생·수기 (자동 출처 없음)**
- `time_of_day` / `season` → `SHT_DT`에서 결정론적 파생(시각/월).
- `weather` → **자동 출처 전무** (1차 `LS_DATA_SRC.WTHR_CD` 있었으나 획득경로 불명, 본 프레임 샘플도 `""` 빈값) → 사실상 무시 or 수기.
- `image.description`(프레임별) → 작업자 수기 입력 (§4-2 갭 유지 — 저장위치 신설 필요).

### 6-3. 정정 이력 (supersession)

- §4-2 "coordinates 미보유 → CCTV 마스터엔 주소만" → **오류.** `MNG_RESOURCE_CCTV.WGS84_LAT/WGS84_LOT` 실재. **정정: MNG_* 조인으로 조달 가능.**
- §4 "weather/time_of_day/season = META 필요" → time_of_day·season은 `SHT_DT` 파생, weather만 무출처로 축소.
- 결론: **NIA JSON export의 진짜 blocker는 (a) ffprobe 기술스펙 5종 (b) 프레임별 description 2건으로 축소.** 나머지는 MNG_* 조인 + SHT_DT 파생으로 해결.

---

## 7. 권위 명세서(cudo Excel v2.0) 정합 + 조달 전략 확정 (2026-07-13)

> **정본(SoT)**: `AI기반CCTV_어노테이션 포맷 및 데이터 구조_v2.0_20251120.xlsx` (cudo 작성, NIA 가이드라인 맞춤화 + COCO_DATASET 기반).
> 시트: `데이터구조`(필드↔TABLE·COLUMN·cudo전달유무 매핑) + `구문규칙검사`(NIA 구문정확성 검증).
> 본 §7은 그 정본과 우리 실제 스키마의 차이를 반영하고 **조달 전략을 JOIN(조인)으로 확정**한다.

### 7-1. 명세서가 확정한 사실 (본 문서 분석과 일치)

- weather=`WTHR_CD`·time_of_day=`HR_TYPE_CD`·season=`SESN_CD` → **전부 "불가 · UI로 처리"**(자동 출처 없음, 수기). §6-2 ③과 일치.
- cctv_height/azimuth → 비고 **"cudo 자원관리 테이블(MNG_RESOURCE) 참조, key-value map으로 가져오기"** → **MNG_* 조인 명시**. §6-2 ①과 일치.
- coordinates=`WGS84_LAT/WGS84_LOT`, cctv_name=`CCTV_NM` → 조달 가능.

### 7-2. ⚠️ 명세서 `LS_DATA_RAW.COLUMN` ↔ 우리 실제 스키마 차이

명세서는 대부분을 `LS_DATA_RAW` 컬럼으로 매핑하나(=적재 모델), **아래 컬럼은 현재 우리 `LS_DATA_RAW`에 없음.** 우리는 **조인 전략을 택하므로 이 컬럼들을 신설하지 않고** 다른 소스에서 조달한다.

| 명세서 요구 컬럼 | 우리 실제 | 조인 전략에서의 조달 |
|---|---|---|
| `FILE_FMT`(type) | 없음 | `MNG_CLIP_MASTER.FILE_FMT` 조인 |
| `VDO_CDC`(format/codec)·`FILE_SZ`·`FPS`·`ASPRT_RT`·`BIT` | 없음 | **ffprobe** 추출 → `LS_DATA_META`(`video.*`) |
| `WDTH`·`HGT`·`RSLTN`(width/height/resolution) | 없음 | `MNG_RESOURCE_CCTV.RESOLUTION` 조인(+파싱) or ffprobe |
| `WTHR_CD`·`HR_TYPE_CD`·`SESN_CD` | 없음 | weather=UI 수기 / time_of_day·season=`SHT_DT` 파생 |
| `CCTV_NM`·`WGS84_LAT/LOT` | MNG_RESOURCE_CCTV 보유 | `MNG_RESOURCE_CCTV` 조인 |
| `EVNT_NM`·`MNTR_CN` | 없음(EVNT_TYPE_CD만) | `MNG_CLIP_EVNT_LST`+`MNG_EX_EVNT_TYPE` 조인 / event_log는 미보유 |
| `AI_CERT_YN`(ai_generated) | 없음 | `ORGNL_RAW_SN` 유무로 파생 |

**명칭 매핑(조인/직렬화 시 alias):** `DATA_RAW_SN`→`RAW_SN`, `RAW_FILE_NM`→`RAW_FILE_PATH_NM`, `VDO_LEN`→`VDO_LEN_SEC`, `DE_IDNTF_YN`→`DE_IDENT_YN`.

#### 7-2-1. 1차본 가이드라인 필드 → 2차 조달점 정합표 — "어디서 맞추나" (2026-07-14)

> 근거: `docs/데이터 품질 관리 가이드라인.xlsx`(**1차 산출물**, 시트 `사진2`, COCO_DATASET 기반 — §7 정본 v2.0 명세서와 동일 스펙군의 1차 사본).
> **성격**: 이 xlsx의 `TABLE`/`COLUMN` 열은 **1차 시스템 스키마 기준**이다. 2차(본 저작도구)와 테이블·컬럼이 다른 것은 **오류가 아니라 정상** — 1차와 2차는 별개 스키마(1차 MySQL·프로젝트 중심 ↔ 2차 PostgreSQL·영상 단위, §6-1)다.
> **목적**: 따라서 "원안이 틀렸다"가 아니라, **각 JSON 필드를 2차 어디서 맞추나(조달점)** 를 필드 의미(semantic) 기준으로 확정한다. 결론은 §7-3(JOIN)·§7-6(`LS_DATASET_VIDEO_META`)로 귀결되며, 아직 못 맞추는 것만 **OPEN**으로 남긴다.

| JSON 필드 | 1차 기준 출처(참고 — 2차와 상이 정상) | ✅ 2차 조달점 (어디서 맞추나) | 상태 |
|---|---|---|---|
| `length` | 1차 `LCNS` 표기 | `LS_DATA_RAW.VDO_LEN_SEC` (ms→초) | 확정 |
| `filename`·`date_created`·`stdg_cd` | 1차 `RAW_FILE_NM`·`SHT_DT`·`LCLGV_CD` | `LS_DATA_RAW.RAW_FILE_PATH_NM`·`SHT_DT`·`LCLGV_CD` | 확정 |
| `pseudonymity`·`anonymity`·`privacy_included` | 1차 개인정보 플래그(`PRVC_YN_DE_IDNTF_YN` 병기) | `LS_DATA_RAW.PRVC_YN`(개인정보)·`PRVC_TYPE_CD`(가명·익명 유형) | 확정 |
| `ai_generated` | 1차 `AI_CERT_YN` | `ORGNL_RAW_SN` 유무로 파생 | 확정 |
| `cctv_mng_no` | 1차 표기 혼재 | `LS_DATA_RAW.VMS_CCTV_ID` | 확정 |
| `type`(FILE_FMT)·`location` | 1차 `FILE_FMT`·지역 | `MNG_CLIP_MASTER` 조인(+`MNG_EX_LOCAL_GOV`) | 확정(조인) |
| `cctv_name`·`coordinates`·`cctv_azimuth`·`resolution`·`width`·`height` | 1차 `CCTV_NM`·`WGS84_LAT/LOT`·방위각·해상도 | `MNG_RESOURCE_CCTV` 조인(방위각·좌표=key-value, §7-1) | 확정(조인) |
| `event_name`·`event_level1~3` | 1차 `EVNT_NM`·`EVNT_TYPE_CD` | `MNG_CLIP_EVNT_LST`+`MNG_EX_EVNT_TYPE` 계층 조인 | 확정(조인) |
| `fps`·`format(codec)`·`bit_rate`·`aspect_ratio`·`pixel`·`filesize` | 1차 컬럼 표기(`VDO_LEN`·`ASPRT_RT` 등) | **ffprobe** 추출 → `LS_DATA_META(video.*)` | 확정(ffprobe) |
| `season`·`time_of_day` | 1차 `SESN_CD`·`HR_TYPE_CD` 코드 컬럼 | `SHT_DT` 파생(월/시각) → 표준약어 `SESN`·`DAY_NGT` | 확정(파생) |
| `dataset.*`(counts) | 1차 프로젝트 레벨 | `LS_PJT`·`LS_PJT_DATA_STATS` (영상단위 아님, §6-1) | 확정 |
| `weather` | 1차 `WTHR_CD` (획득경로 불명, 샘플도 빈값) | ❌ 자동 출처 없음 → UI 수기 | **OPEN** |
| `event_log` | 1차 `MNTR_CN` | ❌ 미보유 → 관제일지 소스 확인 필요 | **OPEN** |
| `image.description`(프레임별) | 1차 image/annotation 텍스트 | ❌ SRC(프레임) 단위 저장위치 없음 | **OPEN**(blocker #2) |

부수 관찰: 최상위 `type` 값이 1차본엔 `"instances"`(복수)이나 1차 실제 산출물(`00000048.json`)은 `"instance"`(단수, §1). 가이드라인 표기 ↔ 실제 출력 불일치이므로 export 직렬화 시 실측값 기준으로 확정.

> **"어디서 맞추나" 요약**: 대부분 **MNG_\* 조인 / ffprobe / SHT_DT 파생**으로 2차에서 맞춰진다. 아직 정합점을 못 정한 것은 **`weather`·`event_log`·프레임별 `description` 3건(OPEN)** 뿐. 1차의 `TABLE`/`COLUMN`은 1차 스키마라 참고만 하고, 판단 기준은 **필드 의미 → 2차 소스 매핑**이다.

### 7-3. 확정 전략 — JOIN (컬럼 신설 최소)

> **결정(2026-07-13, 사용자)**: 명세서의 "LS_DATA_RAW 적재(materialize)" 모델 대신 **조인 전략**을 채택. LS_DATA_RAW에 ~16컬럼을 신설하지 않고, export 시점에 MNG_* 조인 + ffprobe 메타 + SHT_DT 파생으로 조달한다.

**조달 계층 (JSON `video` 블록 기준):**

| 계층 | 필드 | 소스 |
|---|---|---|
| 🟢 우리 LS_DATA_RAW 기존 | id·filename·date_created·length·cctv_mng_no·stdg_cd·anonymity/pseudonymity/privacy_included·ai_generated | `RAW_SN`·`RAW_FILE_PATH_NM`·`SHT_DT`·`VDO_LEN_SEC`·`VMS_CCTV_ID`·`LCLGV_CD`·`PRVC_YN`/`DE_IDENT_YN`·`ORGNL_RAW_SN` |
| 🟢 MNG_* 조인 | type(FILE_FMT)·cctv_name·coordinates·resolution·location·event_name/levels | `MNG_CLIP_MASTER`·`MNG_RESOURCE_CCTV`·`MNG_CLIP_EVNT_LST`+`MNG_EX_EVNT_TYPE`·`MNG_EX_LOCAL_GOV` |
| 🟡 ffprobe → LS_DATA_META | fps·format(codec)·bit_rate·aspect_ratio·pixel·filesize | 적재 시 1회 추출, `video.*` 키 적재 |
| 🟡 SHT_DT 파생 | time_of_day·season | 촬영일시 시각/월 계산 |
| 🔴 UI 수기 | weather·image.description(프레임별) | 라벨/검수 화면 입력 (description은 SRC 단위 저장위치 신설 필요) |

**남은 blocker (조인 전략에서도):**
1. **ffprobe 메타 적재** — **신규 인프라 아님.** ffprobe 통합은 이미 있음(`VideoProbe` 포트 + `BrampVideoProbe`, 설정 `authoring.ffprobe.binary`). 현재 `-show_entries stream=width,height`(→`Dimensions`)만 추출하며 해상도 변경(SFR-07-02)에만 사용. **확장안**: `-show_entries`에 `stream=...,codec_name,r_frame_rate,bit_rate,duration format=size,bit_rate,format_name` 추가 + `Dimensions`→`VideoMeta` 확장 → **적재 시(TrainingVideoIngest/Deidentify 선두) 1회 호출해 `LS_DATA_META(video.*)` 적재.** 이걸로 fps·format(codec)·bit_rate·filesize·pixel·length 일괄 획득.
   - ⚠️ 프레임 추출기(`FfmpegFrameExtractor`)는 현재 probe 없이 **fps 30 고정 가정**(`NATIVE_VIDEO_FPS=30`, 결함 M-3). 실 probe 도입 시 **M-3(비-30fps 영상 마킹 시점 어긋남) 결함도 동시 해결**되는 보너스.
2. **프레임별 description** — LS_DATA_META는 RAW 키 → SRC(프레임) 단위 텍스트 저장위치 신설 필요.
3. **event_log(MNTR_CN)** — 미보유, 관제일지 소스 확인 필요.

> categories의 keypoints/skeleton/supercategory, annotations의 keypoints는 명세서엔 있으나 우리 라벨 모델 미지원(범위 밖 가능 — 본 사업 대상 라벨이 bbox/polygon 위주면 무해).

### 7-4. ⚠️ 포털향 예외 — JOIN 성립 안 함, 전용 메타 테이블(materialize) 필요 (2026-07-13 사용자 지적)

§7-3의 JOIN 전략은 **`LS_DATA_META`+`MNG_*`+`LS_DATA_RAW`가 모두 control DB(klid_at)에 있다는 전제**다. 그러나 **포털은 별도 물리 포털 DB**(`common.datasource.PortalDataSourceConfig`, `portalEntityManagerFactory`, `PORTAL_DB_*`)에서 Load하므로 **control DB 테이블을 크로스-DB JOIN할 수 없다.**

| 경로 | DB | 전략 |
|---|---|---|
| 내부 데이터마트 export (구현됨) | control DB | 🟢 JOIN — V_COMPLETED_* + MNG_* 조인 |
| **포털향 export (미구현/future)** | **포털 DB(분리)** | 🔴 **materialize — 저장된 전용 메타 테이블 필요** |

- 즉 포털이 NIA 메타를 소비하려면 video.* 메타(MNG 유래 cctv_name·coords 포함)를 **포털이 접근 가능한 저장 테이블로 적재(materialize)** 해야 한다. 이는 cudo 명세서(Excel v2.0)의 "LS_DATA_RAW 적재" 모델 방향과 맞닿는다.

> **📌 방향 수정(2026-07-13) — 이원화 → 일원화(단일 materialize)**: "내부=JOIN / 포털=materialize" 이원화보다, **내부·포털이 모두 단일 저장 메타 테이블을 읽는 일원화**로 간다(사용자 결정). 이유: ①로직 1벌이라 관리·정합 유리 ②**학습데이터는 snapshot(동결) 의미가 맞음** — JOIN(live)은 MNG_* 변경 시 과거 확정 데이터셋 메타까지 흔들리나, 구축 시점 snapshot은 데이터셋 무결성에 부합. **형태 확정 = A. 전용 컬럼형 테이블**(glossary 표준약어 컬럼, cudo Excel v2.0 방향 — 사용자 결정 2026-07-13). B(`LS_DATA_META` EAV 확장) 반려: EAV엔 표준화할 물리 컬럼이 없어 "컬럼 표준용어" 지시와 부딪히고, 포털/외부는 1영상=1행 컬럼형 소비를 기대. 비용: MNG 필드 snapshot 적재 단계 신설 + `V_COMPLETED_*` View를 그 테이블 기반으로 재구성(출력 컬럼 유지 시 관제 소비자 무영향). **이미 구현된 JOIN 경로(feat/nia-export 66cae8c)는 유효하며 일원화는 향후 리팩터 대상.** (미구현/backlog)

### 7-5. 🔒 제약 — 새 테이블/컬럼은 표준용어 준수 (구속 지시, 2026-07-13)

§7-4의 포털향 메타 테이블(또는 컬럼)을 설계·생성할 때는 **반드시 표준용어를 준수**한다:
- **LogiCraft program glossary** + **gov(공공) 표준용어**를 조회해 물리명(약어)·논리명을 정합시킨다(예: 좌표=`WGS84_LAT/LOT`, 코덱=`VDO_CDC`, 프레임레이트=표준약어 확인 후).
- 소유권 규칙 준수: 타인/공공 등록분 무단 수정 금지(본인 등록분만). 신규 용어는 등록 후 사용.
- 이 제약은 향후 이 프로젝트의 **모든 신규 LS_* 테이블/컬럼 생성**에 일반 적용된다.

### 7-6. ✅ 확정 설계 — 통합 메타 테이블 `LS_DATASET_VIDEO_META` (2026-07-13, /cc-design)

> 설계 산출물: `.claude-design.md`(전체 상세). 본 절은 §7-4 방향수정(일원화 materialize)의 **구체 설계 확정** 요약.

- **형태(확정)**: 영상 1건=1행 컬럼형 물리 테이블 `LS_DATASET_VIDEO_META`(약 37컬럼). `video.*` 블록을 APPROVED 시점에 flatten·동결. 소스 = LS_DATA_RAW + LS_DATA_META(video.* ffprobe, 66cae8c) + MNG_* 조인 + SHT_DT 파생.
- **물리 배치(확정, 대안 A)**: control DB(klid_at)를 **단일 진실원(SoT)**, 포털 DB는 **outbox 단방향 복제본**. 근거: control↔포털 물리 분리 + **XA/2PC 부재**로 dual-write(안 C) 불가 → `ReviewService.approve()` control-local 단일 트랜잭션에 materialize를 편승시켜 "동결=APPROVED 커밋" 원자성 확보. (이원화 안 B는 사용자 기피 일원화 위배로 반려.)
- **스냅샷 트리거(확정)**: `ReviewService.approve()` — `LS_LABEL_VERSION` 라벨 스냅샷과 **동일 트랜잭션**. 멱등키 `SNPSHT_HASH`(SHA-256), UK `(RAW_SN, SNPSHT_HASH)`, `ACTIVE_YN` append-only 이력. 재승인 시 신규 행 + `TASK_MODIFIED` 통지 연계.
- **View 재구성(확정)**: `V_COMPLETED_VIDEO`를 통합 테이블(ACTIVE_YN='Y') 기반으로 재정의(출력 컬럼 alias 보존 = 관제 소비자 무영향 + 신규 메타 컬럼 추가, MNG_* live JOIN 제거로 동결 무결성). `V_COMPLETED_META`는 VLM/시계열 KV만 유지(기술메타는 통합 테이블로 이동). FRAME/LABEL은 불변. 프레임별 description(blocker #2, SRC 단위)은 별개 후속.
- **표준용어(§7-5 준수, LogiCraft 실조회 2026-07-13)**: **전 컬럼 표준용어 기등록 — 🆕 신규 등록 0종.** program: VDO·FPS·CDC·BIT·RESL·SESN·DAY_NGT·WDTH·DURT·ASPRT(종횡) / gov: SZ·RT·LAT·LOT·HGT·WTHR·EXPLN. 프로젝트는 program glossary 우선(사업 표준)이라 **해상도=`RESL`**(1차 legacy `RSLTN` 아님), **계절=`SESN`**·시간대=`DAY_NGT`·화면비=`ASPRT_RT`(전부 기존 코드/1차 term과 정합, drift 아님). 표준용어 선행 부담 없음.
- **범위**: 설계 확정만. 표준용어 등록·DDL·materialize 어댑터·outbox 복제·View 재정의는 후속 구현(WBS Phase 1~4는 `.claude-design.md §2`).

---

## 8. ★최종본(v1.3) → 우리 프로젝트 Table/Column 매핑 (2026-07-14)

> **정본(SoT) 갱신**: 어노테이션 포맷 최종본 = **`docs/AI기반CCTV_어노테이션 포맷 및 데이터 구조_v1.3_20260303.xlsx`**(시트 `데이터구조` 1개, 사용자 확정 2026-07-14). 이전 §7 인용 `v2.0_20251120`은 **폐기(최종 아님)**. **핵심**: v1.3은 `TABLE`/`COLUMN` 열이 없는 **순수 포맷 스펙**(대신 `항목출처` prose) — 즉 **DB 매핑은 우리 몫**이며 아래가 그 매핑이다.
> **근거**: 실제 구현 코드 실측 — `LS_DATASET_VIDEO_META`(V97), `DatasetVideoMetaSnapshotService`, `DatasetMetaSourceRepository`(MNG_* 조인 쿼리), `LsDataSrc`(V103 FRM_EXPLN). 컬럼명은 추정 아닌 **코드 확인값**.

### 8-1. `video` 블록 → `LS_DATASET_VIDEO_META` (동결 저장) + 조달 소스

> 저장 = APPROVED 시점 동결 컬럼(`LS_DATASET_VIDEO_META.*`). 조달 = 그 값이 오는 실제 소스.

| v1.3 필드 | 저장 컬럼 | 조달 소스 | 상태 |
|---|---|---|---|
| id | (RAW_SN 식별, 직렬화 시 발번) | `LS_DATA_RAW.RAW_SN`(+`VMS_CLIP_ID`) | ✅ |
| filename | `RAW_FILE_PATH_NM` | `LS_DATA_RAW.RAW_FILE_PATH_NM` | ✅ |
| date_created | `SHT_DT` | `LS_DATA_RAW.SHT_DT` | ✅ |
| type(데이터형식) | `FILE_FMT` | `MNG_CLIP_MASTER.FILE_FMT` 조인 | ✅ |
| format(코덱) | `VDO_CDC` | `LS_DATA_META` `video.codec`(ffprobe) | ✅ |
| filesize | `FILE_SZ` | `LS_DATA_META` `video.filesize` | ✅ |
| length | `VDO_LEN_SEC` | `LS_DATA_RAW.VDO_LEN_SEC`(ms→초) | ✅ ※v1.3 예시 "10M" 표기는 무시(초 저장) |
| fps | `FPS` | `LS_DATA_META` `video.fps` | ✅ |
| aspect_ratio | `ASPRT_RT` | `RESL`(WxH) 파싱 파생(w/h) | ✅ |
| width | `VDO_WDTH` | `RESL` 파싱(`video.resolution`) | ✅ |
| height | `VDO_HGT` | `RESL` 파싱 | ✅ |
| resolution | `RESL` | `LS_DATA_META` `video.resolution` | ✅ |
| coordinates | `WGS84_LAT`·`WGS84_LOT` | `MNG_RESOURCE_CCTV.WGS84_LAT/LOT` 조인 | ✅ |
| location(촬영지역) | `SIDO_NM`·`SGG_NM`(+`LCLGV_CD`) | `MNG_EX_LOCAL_GOV` via `LCLGV_CD` | ✅ |
| cctv_name | `CCTV_NM` | `MNG_RESOURCE_CCTV.CCTV_NM` 조인 | ✅ |
| cctv_mng_no | `VMS_CCTV_ID` | `LS_DATA_RAW.VMS_CCTV_ID` | ✅ |
| anonymity·pseudonymity | `PRVC_TYPE_CD`(비식별 유형) | `LS_DATA_RAW.PRVC_TYPE_CD` | ✅ |
| privacy_included | `PRVC_YN` | `LS_DATA_RAW.PRVC_YN` | ✅ |
| event_id 🆕 | `EVNT_TYPE_CD` | `LS_DATA_RAW.EVNT_TYPE_CD` | ✅(코드로 대응) |
| event_name | `EVNT_NM` | `MNG_EX_EVNT_TYPE_MAP`(CD_TYPE='02' 카테고리명) 조인 | ✅ |
| time_of_day | `DAY_NGT_CD` | `SHT_DT` 파생(시각) | ✅ |
| season | `SESN_CD` | `SHT_DT` 파생(월) | ✅ |
| license_id | — (미저장) | COCO 라이선스, 미보유 → 상수/빈값 | 무해(선택) |
| **bit**(비트값) | `BIT_RT` | `LS_DATA_META` `video.bit_rate`(ffprobe) | ✅ **비트레이트로 확정**(사용자 2026-07-14). ⚠️v1.3 예시 `"24bit"`는 색심도 표기처럼 보이니 그 표기만 cudo 확인 권장 |
| **frames**(총프레임수) | — (신설 필요) | `VDO_LEN_SEC×FPS` 파생 또는 ffprobe `nb_frames`(현재 미적재) | 🟡 파생 필요 |
| **pixel**(화소) | — (신설 필요) | **`MNG_RESOURCE_CCTV.CCTV_PXL`**(CCTV화소 M) 조인 or `RESL` 대체 | 🟢 MNG_* 조달 |
| **weather**(v1.3=필수!) | `WTHR_NM` | **`MNG_CLIP_EVNT_LST.WTHR_CD`(날씨 코드) 컬럼 존재** — 단 1차 실측 빈값이라 미populate 가능성 → populate 시 조인, 아니면 UI 수기(fallback) | 🟢/🔴 **소스 존재, populate 확인 필요** |
| **og_cd**(기관코드) 🆕 | — (신설 필요) | **`MNG_RESOURCE_CCTV.OG_CD`(기관코드) — 정확 일치 실컬럼 확인** | 🟢 MNG_* 조달 확정 |
| cctv_height | — (신설 필요) | **`MNG_RESOURCE_CCTV.CCTV_HGT`(높이 m) 실컬럼 확인** | 🟢 MNG_* 조달 확정 |
| cctv_azimuth | — (신설 필요) | **`MNG_RESOURCE_CCTV.MAIN_SURV_PAN_ANG`(주감시방향값 도) 실컬럼 확인** | 🟢 MNG_* 조달 확정 |
| **event_log**(관제일지) | — (신설 필요) | **`MNG_CLIP_EVNT_LST.MNTR_CN`(관제 일지) — 정확 일치 실컬럼 확인** | 🟢 MNG_* 조달 확정 |

> 참고: 저장 테이블엔 v1.3에 없는 `ORGNL_RAW_SN`·`AI_CRT_YN`(증강 파생)·`DE_IDENT_YN`도 있음(export 스코프 밖이면 직렬화 시 생략).

> **⚠️ 조달원 DB 실측(2026-07-14, `klid_system` MNG_* INFORMATION_SCHEMA 조회)**: 우리 JPA 엔티티(`MngResourceCctv`·`MngClipEvntLst`)는 **필요 컬럼만 매핑한 부분 프로젝션**이라 그동안 안 보였을 뿐, 관제 실테이블엔 아래 컬럼이 **모두 실재**. 사용된 컬럼만 프로젝션·조인 쿼리에 추가하면 조달된다(관제 소유 READ). 확인된 실컬럼:
> - `MNG_RESOURCE_CCTV`: **`OG_CD`(기관코드)**·`OG_NM`(기관명)·**`CCTV_HGT`(높이 m)**·**`MAIN_SURV_PAN_ANG`(주감시방향값=방위각)**·`MAIN_SURV_TILT_ANG`(상하향)·**`CCTV_PXL`(화소)**·`CAM_RSLTN`(카메라해상도)·`CCTV_MNG_NO`(관리번호)·`SIDO_NM/SGG_NM/SIDO_CD/SGG_CD`(지자체)·`WGS84_LAT/LOT`.
> - `MNG_CLIP_EVNT_LST`: **`MNTR_CN`(관제 일지=event_log)**·**`WTHR_CD`(날씨)**·`SESN_CD`(계절)·`HR_TYPE_CD`(시간유형)·`EVNT_NM`·`EVNT_SRC`·`PRVC_TYPE_CD`·`SHT_EQMT/SHT_PRSN/SHT_LC`·`LCNS`.
> - `MNG_CLIP_MASTER`: `FILE_SZ`(파일크기=filesize 대안)·`FILE_FMT`·`VDO_LEN_SEC`·`CTGRY_NM`(카테고리명).
> - 방위각/높이 전수검색 결과: `MNG_RESOURCE_CCTV.CCTV_HGT`·`MAIN_SURV_PAN_ANG`(도)만 유효. **cctv_azimuth=`MAIN_SURV_PAN_ANG`**(주감시 좌우방향각)으로 매핑.

### 8-2. `image`(프레임) 블록 → `LS_DATA_SRC`

| v1.3 필드 | 우리 컬럼 | 비고 |
|---|---|---|
| id | `LS_DATA_SRC.SRC_SN` | |
| file_name | `SRC_FILE_PATH_NM`(원본) / `DE_IDNTF_SRC_FILE_PATH_NM`(비식별) | |
| date_captured | `SHT_DT` | |
| video_id | `RAW_SN`(조인키) | video_id → RAW_SN 룩업 |
| type(확장자) | `SRC_FILE_PATH_NM` 확장자 파생 | |
| frame_num | `VDO_FRM_NO`(디코더 0-base) 또는 `FRM_NO`(추출순번) | ⚠️ v1.3 "영상 내 프레임 순서"=`VDO_FRM_NO` 의미에 근접 — export 규약 확정 필요 |
| **description** | `FRM_EXPLN`(V103, VARCHAR 1000) | ✅ SFR-09 정식필드, blocker#2 구현완료 |
| width·height | — (영상 공통) | SRC 별도 컬럼 없음 → `LS_DATASET_VIDEO_META.VDO_WDTH/HGT` 참조 |
| license_id | — | COCO |
| **anonymity·pseudonymity·privacy_included**(프레임 단위) | — (미저장) | v1.3은 프레임 단위 개인정보 플래그 신설 → 현재 SRC에 없음 | 🟡 갭 |

### 8-3. `annotations` 블록 → `LS_DATA_LBL`

| v1.3 필드 | 우리 컬럼 | 변환/비고 |
|---|---|---|
| id | `LS_DATA_LBL.LBL_SN` | |
| image_id | `LS_DATA_LBL.SRC_SN` | 프레임 FK(정통 COCO — v1.3에서 정식화) |
| category_id | `LBL_ID`(+`LBL_NM`) | 라벨 어휘 매핑표 §3-5 적용 |
| track_id | `TRCK_ID` | 빈문자→NULL 정규화 |
| bbox `[x,y,w,h]` | `POINT_CN` + `LBL_TYPE_CD='BBOX'` | **좌표 변환 필수**(flat→nested, §4-1) |
| polygon | `POINT_CN` + `LBL_TYPE_CD='POLYGON'` | |
| keypoints | — (미지원) | `LBL_TYPE_CD`는 BBOX/POLYGON/POINT까지 | 🟡 갭 |
| text | — → `LS_DATA_META` | 라벨 아님 → 메타 |

### 8-4. `categories` 블록 → `LS_LABEL`(마스터)

| v1.3 필드 | 우리 컬럼 | 비고 |
|---|---|---|
| id | `LS_LABEL.LBL_ID` | |
| name | `LBL_NM` | |
| type(bbox/polygon/keypoints/text) | `LBL_TYPE_CD`(BBOX/POLYGON/POINT) | keypoints/text 미지원 |
| supercategory | — (미지원) | 🟡 갭 |
| keypoints·skeleton | — (미지원) | 🟡 갭(포즈 데이터 유입 시 확장) |

### 8-5. `info`·`dataset`·`licences`·`type`

| 블록 | 우리 대응 | 비고 |
|---|---|---|
| info(year/version/description/date_created) | 미저장 → export 시 상수 생성 | 데이터셋 생성 메타(COCO) |
| dataset(identifier/name/src_path/label_path) | `LS_PJT`/`LS_PJT_DATA_STATS` 개념(1차) | 2차는 영상 단위 — 프로젝트 카운트 개념 약함, export 조립 시 발번 |
| licences(id/name/url) | 미보유 | 상수/빈배열 |
| type | 상수 `"instances"` | v1.3 표기 준수 |

### 8-6. v1.3 갭 요약 (구현 대비) — DB 실측 반영(2026-07-14)

> **검증 근거**: ①1차 wiki DB(`LS_DATA_SRC`) — og_cd·event_log·bit **1차 원천엔 부재**(MNG_*/ffprobe 유래). ②**`klid_system` MNG_* 실스키마 조회** — 아래 컬럼 **전부 실재 확인**(우리 JPA 부분 프로젝션이라 코드엔 미노출).

현 구현(`LS_DATASET_VIDEO_META`)이 v1.3 대비 남은 조치 — **대부분 MNG_* 실컬럼으로 해소**:

1. ✅ **bit = 비트레이트 확정** — 우리 `BIT_RT`(ffprobe `video.bit_rate`)와 정합. v1.3 예시 `"24bit"` 표기만 cudo 확인 권장.
2. 🟢 **og_cd → `MNG_RESOURCE_CCTV.OG_CD`(기관코드) 정확 일치** — 프로젝션/조인 + 통합테이블 컬럼 신설만.
3. 🟢 **event_log → `MNG_CLIP_EVNT_LST.MNTR_CN`(관제 일지) 정확 일치** — 동상.
4. 🟢 **cctv_height → `CCTV_HGT` / cctv_azimuth → `MAIN_SURV_PAN_ANG` / pixel → `CCTV_PXL`** — 전부 `MNG_RESOURCE_CCTV` 실컬럼.
5. 🟢/🔴 **weather → `MNG_CLIP_EVNT_LST.WTHR_CD` 컬럼 존재** — 단 1차 실측 빈값이라 **populate 확인 필요**(populate면 조인, 아니면 사용자 지시대로 UI 수기 fallback).
6. 🟡 **frames** — `VDO_LEN_SEC×FPS` 파생 또는 ffprobe `nb_frames`(미적재).
7. 🟡 **프레임 단위 개인정보 플래그**(image.*) — SRC 미저장 / **keypoints/skeleton/supercategory** 라벨 모델 미지원([[keypoint-labeling-design]]).
8. ✅ **정합 확인**: image.description(=`FRM_EXPLN`), event_id(=`EVNT_TYPE_CD`)+event_name(=`EVNT_NM`), 좌표·해상도·시간/계절 파생.

> **결론(갱신)**: v1.3 매핑의 조달원이 **거의 전부 확정**됐다 — 기존 저장분(`LS_DATASET_VIDEO_META`/`LS_DATA_SRC`) + **MNG_* 실컬럼 조인**(og_cd·event_log·cctv_height·azimuth·pixel) + ffprobe(bit) + 파생. 진짜 미결은 **weather populate 여부**·frames 파생·프레임 개인정보/포즈 라벨 확장뿐. 구현 과제 = 통합테이블에 MNG 조인 컬럼 추가 + 스냅샷 어댑터/조인 쿼리 확장(관제 READ).
