# C 클러스터 part5 (C-5·C-6) 2차 검증 결과

> 대상: `docs/test-cases/C-marking-labeling.md` 의 **C-5 TC-TRACK(31건)** + **C-6 TC-PRESET(18건)** = **49건**
> 폐기(`~~취소선~~`) 행 **0건** — 두 섹션 모두 폐기 케이스가 없어 집계 제외분이 없다.
> 환경: 로컬 도커 스택(backend `:18081`, HEAD `ca3c712b` 이미지 — 소스 HEAD 대비 backend 커밋 11개 미반영 / ai-server `:19300` / postgres `:5432` schema `public`). backend **재기동하지 않음**.
> ⚠ 이슈 ID 는 지시대로 `C-ISSUE-81` 부터 부여했다. **1차(`docs/검증결과/2026-07-25/1차/ISSUES.md`)에 이미 `C-ISSUE-81`·`C-ISSUE-82` 가 존재하므로 번호가 충돌한다** — 마감 시 2차 ISSUES.md 로 합칠 때 재번호(예: `C2-ISSUE-81`) 필요.

## 집계

| 구분 | 검증 | PASS | PARTIAL | FAIL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| C-5 TC-TRACK | 31 | 30 | 1 | 0 | 0 | 0 | 0 |
| C-6 TC-PRESET | 18 | 17 | 1 | 0 | 0 | 0 | 0 |
| **합계** | **49** | **47** | **2** | **0** | **0** | **0** | **0** |

이슈 6건 — HIGH 1 / MEDIUM 3 / LOW 2.

### ★ 판정값만 보면 놓치는 것 (필독)

**전건 PASS/PARTIAL 인데 이 섹션에서 가장 심각한 결함(HIGH `C-ISSUE-81`)은 어떤 케이스도 판정하지 않는다.**
C-5 는 "YOLO 트랙 프록시" 를 표방하지만 **`trackId` 의 존재·안정성·정확성을 단언하는 케이스가 0건**이다(24~30 은 입력검증·좌표·교차영상만 본다). 실측 결과 그 `trackId` 는 **엉뚱한 검출에 붙는다**(§이슈 상세). 카탈로그 커버리지 갭이며, 다음 회차에 케이스 신설이 필요하다.

- self-fill 의심: **0건**. yolo-track 은 ai-server(`:19300`) 실추론 경유를 실측(`mock=false, source=model`, 원본 좌표 `-0.604` → BE `0.0` clamp 대조)했고, 프리셋은 외부 연동이 없는 내부 도메인이다.
- 검증 중 생성/변경한 데이터는 전부 원복했다(§검증 부작용).

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 실측 | 판정 |
|---|---|---|---|
| **C-ISSUE-81**(1차) | `MaskRleConverter`·`CoordinateTransformer` 경계/보안 가드 **단위테스트 커버리지 0%** (TC-TRACK-13/16/18/19/20/21/22) | `MaskRleConverterTest` 에 rleToMask 가드 **7건**(null·DoS상한·width≤0·height≤0·가드순서·길이합초과·경계동일) + `CoordinateTransformerTest` 에 null 가드 **4건**(rotate points/center/가드순서·scale·translate) 신설 확인 | **해소** |
| **C-ISSUE-82**(1차) | 동명이인 dead `common/util/TrackInterpolator` 잔존 | `grep -rn "TrackInterpolator" backend/src/main` → 프로덕션 참조 **0건 유지**(사용처는 `batch/interpolation/TrackInterpolator` 뿐). **범위가 더 넓다** — `MaskRleConverter`·`CoordinateTransformer`·`common/util/ShapeType`·`common/util/Keyframe` 도 전부 프로덕션 참조 0건 | **미해소(확대) → `C-ISSUE-84`** |
| B-ISSUE-61(B-part4) | `TRCK_ID` 가 1/298 로 사실상 전량 NULL → 보간 항상 no-op | 2차 재실측 **421행 중 `trck_id` 보유 1행**(값 `'0'`), 배치 로그 전건 `no interpolation candidates`. **근본 원인을 추가 규명**(§보간 실환경 도달성) | **미해소(원인 규명) → `C-ISSUE-81`·`C-ISSUE-82`** |

> ⚠ 카탈로그 TC-TRACK-13 비고 `※전용 단위테스트 여전히 부재(C-ISSUE-81)` 와 C-6 하단 불확실표의 `C-ISSUE-81 … 미해소` 는 **현 코드베이스와 어긋난다**(§근거 드리프트 D2).

## ★보간 실환경 도달성 (알고리즘 정확성 / 실환경 실행 여부 — 분리 기록)

지시에 따라 **①알고리즘 자체의 정확성**과 **②실환경에서 한 번이라도 도는가**를 분리해 기록한다.

### ① 알고리즘 정확성 — 정확함 (픽스처·손계산 기준)

| 항목 | 검증 방법 | 결과 |
|---|---|---|
| BBOX 선형 보간 | `Bbox.linearInterpolate`(`batch/interpolation/Bbox.java:32-42`) 손계산 — 키프레임 f=5 `(0,0,10,10)` / f=10 `(10,10,20,20)`, span=5 → f=6 `t=0.2` → left `0+10*0.2=2.0`, f=9 `t=0.8` → `8.0` | 코드와 일치 |
| propagate 정책 | `TrackInterpolator.java:57-81` — outside 즉시 break / 다음이 outside 면 현재만 저장 후 break / 마지막 이후 미채움 / span≤0 은 `continue` 후 LinkedHashMap 덮어쓰기 | CVAT 규칙과 일치 |
| 폴리쉐이프 | 대응쌍을 **키프레임 쌍당 1회만** 계산 후 재사용(`:126-135`), 중간 정점 수 = `max(n,m)` 불변식 | `PolyshapeMatcherTest` 17건 · `TrackInterpolatorPolyshapeTest` 13건이 폐/개곡선·방향반전·정점0/2개·NaN/Infinity·상한초과까지 커버 |
| 호출부 정렬 | 카탈로그가 지적하지 않은 반증 포인트 — `TrackInterpolator` 는 "정렬은 호출자 책임"이나 유일 호출부 `TrackInterpolationStep.interpolateTrack:346-349` 가 `srcSnToFrame` 기준 오름차순 정렬 후 전달 | 역순 입력 위험 **없음** |
| 부동소수 누적 오차 | `t` 를 매 프레임 `(f-k.frame)/span` 로 **재계산**(누적 가산 아님) → 오차 누적 구조가 아님 | 이상 없음 |

### ② 실환경 실행 여부 — **한 번도 돌지 않았다**

| 근거 | 실측 |
|---|---|
| `LS_DATA_LBL` 전수 | `select count(*), count(trck_id) from ls_data_lbl` → **421행 / trck_id 보유 1행**(값 `'0'`, distinct 1) |
| 배치 로그 | `docker logs klid-backend --since 24h \| grep Interpolation` → 관측 전건이 `[Batch][Interpolation] no interpolation candidates rawSn={143,145,146,147,148,149,152,153,154,156}`. `saved … interpolatedRows=` 로그 **0건** |
| 후보 조건 | `TrackInterpolationStep:174` `lblRepository.findAutoBboxWithTrackId(rawSn)` — `AUTO_LBL_YN='Y'` + BBOX/POLYGON + **`TRCK_ID IS NOT NULL`**. 트랙 1건당 최소 2 키프레임이 있어야 사이 프레임이 생성됨 |
| 결론 | `TRCK_ID` 가 사실상 부재하므로 후보가 0건 → **보간 산출물(`LBL_SRC_CD='INTERPOLATE'`) 0건**. 단위 테스트는 `Keyframe` 픽스처를 직접 만들므로 이 결손과 무관하게 전부 GREEN 이다(거짓 안심의 전형) |

### ③ 왜 `TRCK_ID` 가 안 생기는가 — 이번 회차에 근본 원인 규명

배치(`YoloAutolabelStep:208`)와 온디맨드 프록시(`YoloTrackService:120`) 모두 `/infer/yolo/track`(ByteTrack) 을 호출하고 응답 `track_id` 를 그대로 `TRCK_ID` 로 적재한다(`YoloLabelPersister.java:82-84`). 그런데 —

1. **ByteTrack 미확정 구간이 길다.** ai-server 직접 프로브(동일 이미지 3회, `clip_id=probe-c5`): `frame_index=0,1` → 전 검출 `track_id=null`, `frame_index=2` → 9건 중 **3건만** id 부여. 실영상 프레임 수가 3~12장인 현 데이터에서는 확정 전에 시퀀스가 끝난다.
2. **부여된 id 가 엉뚱한 검출에 붙는다** (→ HIGH `C-ISSUE-81`). ai-server 실로그에 `[ByteTrack] tracker_id 길이 불일치 dets=6 tracker_ids=5` 가 반복 관측됐고, 컨테이너 내 직접 프로브로 `ByteTrackTracker.update()` 가 **입력 순서를 재배열**함을 확증했다.
3. 결과적으로 실DB 에 남은 유일한 `TRCK_ID='0'` 1행조차 **그 값이 올바른 객체의 것이라는 보증이 없다**.

> **판정 원칙**: TC-TRACK-01~11(unit 계층)은 ①에 근거해 PASS 로 둔다. ②③ 은 케이스 단언 밖이므로 판정을 바꾸지 않고 이슈로 분리 기록한다. **픽스처 GREEN 을 근거로 "보간 기능 정상"이라고 읽으면 안 된다.**

## ★프리셋 단일 진실원 실측 (마스터 변경 → 기존 프리셋 반영 여부)

검증 시작 시점 `LS_LABEL_PRESET` **0행**이었으므로 전용 라벨 마스터 2건(labelId **40**/**41**)과 프리셋 6건을 새로 만들어 실측했다(타 에이전트 데이터 무손상, 검증 후 원복 — §검증 부작용).

| 순서 | 조작 | 관측 | 판정 |
|:--:|---|---|---|
| 1 | labelId 40=`ZZTEST-C5A`(BBOX), 41=`ZZTEST-C5B`(POLYGON) 생성 → 프리셋 `ZZTEST-P1`(40,41)·`ZZTEST-P3`(40) 생성 | 응답 `labelCodeOptions[].code=null`, DB `ls_label_preset_code.lbl_cd` **NULL** / `lbl_id`=40,41 → **코드 문자열 스냅샷 미저장** | 계약 준수 |
| 2 | **프리셋 생성 후** 마스터 40 을 `PUT /v1/manage/labels/40` 으로 이름 `ZZTEST-C5A`→`ZZTEST-C5A-RENAMED` + 형태 `BBOX`→`POLYGON` 변경 | **기존 프리셋 2건 모두** 재조회 시 `labelName=ZZTEST-C5A-RENAMED`, `labelType=POLYGON`, `bboxEnabled=false`/`polygonEnabled=true` 로 **즉시 반영**. `ls_label_preset_code` 행은 **무변경**(lbl_id 만 보유) | **★단일 진실원 성립** |
| 3 | 마스터 40 을 다시 `POLYGON`→`POINT` 로 변경 | 프리셋 응답 `labelType=POINT`, **두 토글 모두 false**(`LabelGeometry.POINT(false,false)`) | 형태 소유권=마스터 확인 |
| 4 | 마스터 41 을 `DELETE`(soft delete, `USE_YN='N'`) | 프리셋 응답 `linked=false`, 자동 생성/삭제 **없음**, 오류 없음. **단 `labelName`·`code` 가 모두 `null`** → `C-ISSUE-85` | 부분 성립 |
| 5 | 미연결 레거시 코드(`lbl_id=NULL, lbl_cd='ZZ-LEGACY-CODE'`) 1행 주입 후 조회 | `linked=false`, `labelName='ZZ-LEGACY-CODE'` 로 **오류 없이 노출** | 계약 준수 |
| 6 | 같은 labelId 2회 지정(`labelIds:[40,40]`, `[40,41,40]`) — create/update 양쪽 | **201/200 + DB 1행만 저장**. 애그리거트(`LsLabelPreset.replaceCodes:168-180`)가 `keyOf`(=`"ID:"+labelId`) 로 dedup. 부분 유니크 인덱스 `uk_ls_label_preset_code_lblid (preset_id, lbl_id) WHERE lbl_id IS NOT NULL` 실재(`\d ls_label_preset_code`) — 앱 dedup 1선 + DB 인덱스 최종 방어 | 불변식 성립 |
| 7 | 스냅샷 여부 역검증 | `ls_label_preset_code` 컬럼은 `cd_sn/preset_id/lbl_cd/sort_seq/lbl_id` 뿐 — **라벨명·형태 컬럼 없음**(구 `BBOX_ENABLED`/`POLYGON_ENABLED` 제거 확인) | 계약 준수 |

### DTCT_TYPE_CD 축 일원화 (4경로) — 드리프트 없음

| 경로 | 파일:라인 | 사용 함수 |
|---|---|---|
| 배치 오토라벨 | `batch/step/YoloAutolabelStep.java:277` | `findLabelIdByDtctType` |
| 온라인 AI 탐지 | `label/service/AutolabelOnlineService.java:334`, `:426` | `findLabelIdByDtctType` |
| SAM2 | `batch/step/Sam2SegmentStep.java:198` | `findLabelIdByDtctType` |
| 프리셋 토글 | `batch/policy/PresetLabelLookupService.java:43,57,109,124-136` | 키 = 마스터 `DTCT_TYPE_CD` 정규화(trim+소문자) |

`findLabelIdByName` 은 **프로덕션 코드에서 0건**(잔존은 `V120__label_name_ci_unique.sql:7` 주석 · `LabelMasterService.java:140` 대체 설명 주석 · 레거시 리포지토리 테스트 1건뿐). 미매핑 클래스 차단은 BE 가 강제 — `AutolabelOnlineService.resolveDetectClasses:457-471` 이 마스터 매핑 집합과의 **교집합만** ai-server 로 보내고, 교집합이 비면 호출 자체를 스킵한다(FE 요청 불신). DB 실측상 활성 라벨 9건 중 `dtct_type_cd` 매핑 6건 / 미매핑 3건(fire·smoke·water)으로 정책과 정합.

### 인가 (IDOR) 실측

| 요청 | WORKER 토큰 결과 |
|---|---|
| `GET /v1/manage/presets` | **403** |
| `POST /v1/manage/presets` | **403** (`FORBIDDEN`) |
| `PUT /v1/manage/labels/40` | **403** |

REVIEWER 전용(`@PreAuthorize("hasRole('REVIEWER')")` + `/v1/manage/**` 매처) 확인. 수평 상승 경로 없음.

## C-5 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|---|---|---|
| TC-TRACK-01 | BBOX 보간 정상 | PASS | [정적] `batch/interpolation/TrackInterpolator.java:76-79` + 손계산(t=0.2→2.0 / t=0.8→8.0) · `TrackInterpolatorTest.java:47` | — |
| TC-TRACK-02 | 빈 키프레임 | PASS | [정적] `:53-55` `return Map.of()` · `TrackInterpolatorTest.java:26` | — |
| TC-TRACK-03 | 단일 키프레임 | PASS | [정적] `:57-64`(i+1 미존재 → 그 프레임만) · `TrackInterpolatorTest.java:34` | — |
| TC-TRACK-04 | outside 마커 종료 | PASS | [정적] `:59-62` break(해당 frame 미포함) · `TrackInterpolatorTest.java:109` | 프로덕션은 `outside=false` 고정(`TrackInterpolationStep:370,394`) — 실환경 미도달 경로 |
| TC-TRACK-05 | 다음이 outside | PASS | [정적] `:66-70` · `TrackInterpolatorTest.java:123` | 동상 |
| TC-TRACK-06 | 마지막 이후 propagate 없음 | PASS | [정적] 루프 종료로 구현(코드 부재가 규칙) · `TrackInterpolatorTest.java:140` | 근거 드리프트 D1 — 카탈로그 `:35-44` 는 javadoc |
| TC-TRACK-07 | 동일 frame 중복 뒤값 우선 | PASS | [정적] `:71-75` span≤0 `continue` → 다음 iteration LinkedHashMap 덮어쓰기 · `TrackInterpolatorTest.java:157` | 역순(span<0)도 동일 경로 |
| TC-TRACK-08 | totalFrames 음수 | PASS | [정적] `:50-52` IAE · `TrackInterpolatorTest.java:181` | — |
| TC-TRACK-09 | keyframes null | PASS | [정적] `:49` `Objects.requireNonNull` → NPE · `TrackInterpolatorTest.java:174` | — |
| TC-TRACK-10 | 폴리쉐이프 보간 정점수 다름 | PASS | [정적] `:127-135` 쌍당 1회 match 후 재사용 · `TrackInterpolatorPolyshapeTest.java:65` · `PolyshapeMatcherTest.java:89,162,176,189` | 중간 정점수=max 불변식 3건 커버 |
| TC-TRACK-11 | 폴리쉐이프 closed/open | PASS | [정적] `PolyshapeMatcher.java:54-68`(closed→normalizeRing+matchClosed / open→앵커고정+reverse감지) · `TrackInterpolatorPolyshapeTest.java:79,92` | — |
| TC-TRACK-12 | rotate 중심 기준 | PASS | [정적] `common/util/CoordinateTransformer.java:34-38` 손계산 — (10,0), +90°, center(0,0) → `dx*cos-dy*sin=0`, `dy*cos+dx*sin=10` → (0,10) · `CoordinateTransformerTest.java:64` | ⚠ 이 유틸은 프로덕션 참조 0건(`C-ISSUE-84`) |
| TC-TRACK-13 | rotate points/center null | PASS | [정적] `:22-27` IAE 2종 · `CoordinateTransformerTest.java:81,90,98` | **근거 드리프트 D2** — 카탈로그의 "전용 단위테스트 여전히 부재" 는 무효(1차 C-ISSUE-81 해소) |
| TC-TRACK-14 | scale/translate | PASS | [정적] `:44-65` · `CoordinateTransformerTest.java:38,51,107,115` | ⚠ `C-ISSUE-84` |
| TC-TRACK-15 | maskToRle 첫픽셀 on | PASS | [정적] `MaskRleConverter.java:51-59` + 손계산 `[T,T,F]`→`[0,2,1]` · `MaskRleConverterTest.java:65` | — |
| TC-TRACK-16 | maskToRle 빈/null | PARTIAL | [정적] `:31-33` null·`length==0` → `int[0]` 정상. 그러나 **0폭 행**(`boolean[1][0]`)은 `:53` `flat[0]` 에서 `ArrayIndexOutOfBoundsException` | **`C-ISSUE-83`** |
| TC-TRACK-17 | maskToRle >1M 픽셀(DoS) | PASS | [정적] `:22,:37-39` IAE · `MaskRleConverterTest.java:191`(1001×1001) | — |
| TC-TRACK-18 | maskToRle 비직사각형 | PASS | [정적] `:44-47` row 폭 불일치 IAE | 전용 테스트 **없음**(커버리지 갭, 코드는 정확) |
| TC-TRACK-19 | rleToMask width/height≤0 | PASS | [정적] `:93-95` IAE · `MaskRleConverterTest.java:124,135` | — |
| TC-TRACK-20 | rleToMask >1M | PASS | [정적] `:88-92` 할당 **전** IAE · `MaskRleConverterTest.java:110` + 가드 순서 고정 `:146` | — |
| TC-TRACK-21 | rleToMask 길이합 초과 | PASS | [정적] `:102-104` + 손계산(`rle=[10,10]`, w5×h3=15 → idx15 도달 시 IAE) · `MaskRleConverterTest.java:162` + 경계동일 `:174` | — |
| TC-TRACK-22 | rleToMask null | PASS | [정적] `:85-87` IAE · `MaskRleConverterTest.java:101` | — |
| TC-TRACK-23 | round-trip mask→rle→mask | PASS | [정적] 손계산 4패턴(all-off `[15]` / all-on `[0,N]` / `[T,T,F]` / `[F,F,T]`) 전부 복원 · `MaskRleConverterTest.java:18` 랜덤 1000회 | — |
| TC-TRACK-24 | yolo-track path/body 불일치 | PASS | [실동작] `POST /v1/frames/93/yolo-track` body `srcSn=94` → **400** `INVALID_INPUT` "path 의 srcSn 과 body 의 srcSn 이 다릅니다." · `LabelController` yolo-track 핸들러 | — |
| TC-TRACK-25 | yolo-track nextSrcSns 50 초과 | PASS | [실동작] 51개 → **400** "nextSrcSns: size must be between 0 and 50" · `label/dto/YoloTrackRequest.java:23` | 50개 정확히는 검증 통과(서비스 진입 확인) |
| TC-TRACK-26 | yolo-track nextSrcSns 빈 | PASS | [실동작] `[]` → **400** "nextSrcSns: must not be empty" | — |
| TC-TRACK-27 | yolo-track 좌표 clamp | PASS | [실동작] ai-server 원응답 `car x1=-0.604` → BE 응답 **`0.0`**(400 아님), 나머지 좌표 전부 동일. `DetectionBoxNormalizer.java:59-68` | ★self-fill 아님 — ai 원응답과 BE 응답 1:1 대조 |
| TC-TRACK-28 | yolo-track NaN/Infinity 400 | PASS | [정적] `DetectionBoxNormalizer.java:54-58` `!Double.isFinite` → IAE → `YoloTrackService.java:169-172` 400 · `YoloTrackServiceTest.java:325` | 실AI 로 NaN 유도 불가 → 테스트 대조 |
| TC-TRACK-29 | yolo-track 퇴화 박스 검출 단위 스킵 | PASS | [정적] `DetectionBoxNormalizer.java:65-66` `Optional.empty()` → `YoloTrackService.java:173-177` WARN 후 `continue`(시퀀스 유지) · `YoloTrackServiceTest.java:304` | — |
| TC-TRACK-30 | yolo-track 교차 영상 혼입 차단 | PASS | [실동작] srcSn 93(raw 136) + nextSrcSns `[94,135(raw 157)]` → **400** "시퀀스 프레임이 시작 프레임과 다른 영상에 속합니다: srcSn=135" · `YoloTrackService.java:109-114` | 근거 드리프트 D3(±1행) |
| TC-TRACK-31 | 보간 진실원 단일 확인 | PASS | [정적] `grep -rn "TrackInterpolator" backend/src/main` → 프로덕션 사용은 `TrackInterpolationStep.java:13,72` 의 `batch.interpolation.TrackInterpolator` 뿐. `common/util/TrackInterpolator` 참조 **0건** | 기대결과(=dead code 잔존)와 일치. 자동 구조 단언 테스트는 **부재** → `C-ISSUE-84` |

## C-6 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|---|---|---|
| TC-PRESET-01 | 생성 정상(labelId 기반) | PASS | [실동작] `POST /v1/manage/presets {labelIds:[40,41]}` → **201**, 응답 `code=null`, DB `ls_label_preset_code.lbl_cd` **NULL** · `preset/service/PresetService.java:62-73,163-172` | 코드 문자열 미저장 확인 |
| TC-PRESET-02 | 생성 이벤트타입 무효 | PASS | [실동작] `eventTypeCd:"NOPE_XYZ"` → **400** "지원하지 않는 이벤트 타입입니다" · `PresetService.java:127-134` | 유효 categoryKey 9종은 `/v1/event-types` 실조회로 확인 |
| TC-PRESET-03 | 생성 이벤트타입 빈값 허용 | PASS | [실동작] `eventTypeCd:""` → **201**, 저장값 `null` 로 정규화 | — |
| TC-PRESET-04 | 생성 이름 중복 | PASS | [실동작] 동일 `name` 재생성 → **409** "이미 사용 중인 프리셋 이름입니다." · `:65-67` | — |
| TC-PRESET-05 | 생성 labelId 미존재/비활성 | PASS | [실동작] `labelIds:[999999]` → **400** / `labelIds:[37]`(`USE_YN='N'`) → **400** "존재하지 않거나 비활성 라벨입니다: labelId=…" · `:144-160` | 두 케이스 모두 실측 |
| TC-PRESET-06 | 생성 이벤트 유니크 경합 | PASS | [실동작] 프리셋1 에 `020001` 매핑 후 다른 프리셋을 같은 이벤트로 생성 → **409** "이미 다른 프리셋에 매핑된 이벤트입니다" · `:174-181`, DB `uk_ls_label_preset_evnt` 실재 | 예외→409 변환 경로가 **너무 넓다** → `C-ISSUE-86` |
| TC-PRESET-07 | 수정 미존재 | PASS | [실동작] `PUT /presets/999999` → **404** "프리셋을 찾을 수 없습니다." · `:79-80` | — |
| TC-PRESET-08 | 수정 이름 중복(자기제외) | PASS | [실동작] 타 프리셋명으로 수정 → **409** / 자기 이름 그대로 수정 → **200** · `:81-83` | 자기제외 동작 실측 |
| TC-PRESET-09 | 수정 이벤트 유니크 위반 | PASS | [실동작] 이미 매핑된 `020001` 로 수정 → **409** · `:88-93` `saveAndFlush` | — |
| TC-PRESET-10 | 삭제 멱등 | PASS | [실동작] `DELETE /presets/999999` → **204**(no-op) · `:98-103` | — |
| TC-PRESET-11 | 복제 이름 시퀀스 | PASS | [실동작] 1회차 `"ZZTEST-P1 (복사본)"`, 2회차 `"ZZTEST-P1 (복사본 2)"` · `:184-196` | 근거 드리프트 D4(±1행) |
| TC-PRESET-12 | 복제 이벤트 미상속 | PASS | [실동작] 원본 `eventTypeCd=020001` → 복제본 **`null`**, 코드 2건은 그대로 복사 · `:106-118` | — |
| TC-PRESET-13 | 복제 이름 50회 초과 실패 | PASS | [실동작] `(복사본)`~`(복사본 50)` 50건 선점 후 clone → **409** "복제 이름 생성에 실패했습니다." · `CLONE_SUFFIX_MAX=50`, `:186-195` | 자동 테스트 부재 — 본 회차 실동작으로 커버 |
| TC-PRESET-14 | 코드뷰 미연결(labelId null) | PASS | [실동작] `lbl_id=NULL, lbl_cd='ZZ-LEGACY-CODE'` 행 → `linked=false`, `labelName='ZZ-LEGACY-CODE'`, 오류·자동생성 없음 · `:239-244` | 근거 드리프트 D5 |
| TC-PRESET-15 | 코드뷰 마스터 미존재/soft delete | PARTIAL | [실동작] 마스터 41 soft delete 후 `linked=false`, 자동 생성/삭제 없음(단언 성립). **그러나 `labelName`·`code` 가 모두 `null`** 이라 어떤 라벨이었는지 식별 불가 + `labelCodes`(선언상 `string[]`)에 **null 원소** 유입 · `:240-244` | **`C-ISSUE-85`** |
| TC-PRESET-16 | 코드뷰 형태 마스터 파생 | PASS | [실동작] 마스터 40 을 BBOX→POLYGON→POINT 로 순차 변경 시 프리셋 응답 토글이 `(T,F)→(F,T)→(F,F)` 로 즉시 추종 · `:246-250` + `label/domain/LabelGeometry.java:25-28` | 프리셋 개별 토글 컬럼 부재(스키마 실측) |
| TC-PRESET-17 | 부분유니크 labelId 중복 방지 | PASS | [실동작] `\d ls_label_preset_code` → `uk_ls_label_preset_code_lblid UNIQUE (preset_id, lbl_id) WHERE lbl_id IS NOT NULL` 실재. `labelIds:[40,40]`·`[40,41,40]` 요청 시 DB **1행만** 저장(애그리거트 `LsLabelPreset.replaceCodes:168-180` dedup 1선) · IT `LsLabelPresetCodeLabelIdUniqueIT:67` | 인덱스 자체 거부는 IT 가 커버. 앱 dedup 때문에 API 경로로는 인덱스에 도달하지 않음(설계 의도) |
| TC-PRESET-18 | N+1 회피 배치 조회 | PASS | [실동작] 프리셋 55건 목록 1회 조회 전후 `pg_stat_user_tables.ls_label` 스캔 **delta=1** · [정적] `LsLabelPresetRepository.findAllWithCodes` fetch join + `PresetService.java:220-235` → `LabelMasterService.findActiveByIds:180-186`(`findByLabelIdInAndUseYn`) | `PresetServiceTest.java:349` 도 커버 |

## 근거 드리프트

| # | 케이스 | 카탈로그 근거/비고 | 실제 | 영향 |
|:--:|---|---|---|---|
| **D1** | TC-TRACK-06 | `TrackInterpolator.java:35-44` | 해당 구간은 **javadoc**(`<li>마지막 키프레임 이후 frame 은 propagate 하지 않음</li>`). 실제 규칙은 `:57-81` 루프가 마지막 키프레임 이후를 만들지 않음으로 성립 | 판정 무영향(문서 위치만) |
| **D2** | TC-TRACK-13 · C-6 §불확실표 | `※전용 단위테스트 여전히 부재(C-ISSUE-81)` / `C-ISSUE-81 … 미해소` | `CoordinateTransformerTest.java:81,90,98,107,115` + `MaskRleConverterTest.java:101,110,124,135,146,162,174` **신설 완료** → 1차 C-ISSUE-81 **해소** | **비고가 사실과 반대** — 카탈로그 정정 필요 |
| **D3** | TC-TRACK-30 | `YoloTrackService.java:110-115` | 실제 `:109-114` | ±1행 |
| **D4** | TC-PRESET-11 / 13 | `PresetService.java:183-195` / `:185-195` | 실제 `:184-196` / `:186-195` | ±1행 |
| **D5** | TC-PRESET-02/03 · 14 · 15 · 16 | `:126-134` / `:237-244` / `:238-244` / `:245-251` | 실제 `:127-134` / `:239-244` / `:240-244` / `:246-250` | ±1~2행 |

> D3~D5 는 판정에 영향 없는 ±1~2행 오프셋이다. **D2 만 실질 드리프트**(폐기된 사실을 미해소로 표기)이므로 카탈로그 갱신 대상이다.

## 검증 부작용 (원복 완료)

| 항목 | 조작 | 원복 |
|---|---|---|
| `LS_LABEL_PRESET` / `_CODE` | `ZZTEST-P1/P3/P17/P6…` 등 6건 + 복제이름 선점 50건 생성, 코드행 주입 1건 | **전량 삭제** — 검증 후 `select count(*)` = **0 / 0** (검증 시작 시점과 동일) |
| `LS_LABEL` | labelId **40**(`ZZTEST-C5A-RENAMED`, POINT) · **41**(`ZZTEST-C5B`, POLYGON) 신규 생성 | **`USE_YN='N'` soft delete**(하드 삭제는 FK·감사 이력상 미수행). 활성 라벨 9건은 **무변경** — C-part2 의 labelId 37 과 동일 패턴 |
| `LS_DATA_LBL` / `LS_DATA_SRC` / `LS_DATA_RAW` | **무변경**(yolo-track 은 `readOnly` 프록시라 DB 미기록) | — |
| rawSn 126·129·133 | **미접촉** | — |

---

## 이슈 상세

### [C-ISSUE-81] TC-TRACK-30(인접) — ByteTrack `track_id` 가 위치 기반 zip 으로 **엉뚱한 검출에 배정**된다

- **심각도**: HIGH
- **기대 동작(기대효과)**: `/infer/yolo/track` 이 돌려주는 `track_id` 는 **그 검출(bbox)의 객체 식별자**여야 한다. 이 값이 `LS_DATA_LBL.TRCK_ID` 로 적재되어 트랙 보간·트랙 편집·트랙 병합·검수 스냅샷의 객체 동일성 기준이 되므로, 잘못 붙으면 **서로 다른 객체가 같은 트랙으로 묶이고 같은 객체가 끊긴다**.
- **현재 동작(이슈 내용)**: `ai-server/app/models/bytetrack_util.py:_apply_bytetrack` 이 트래커 출력을 **입력 리스트와 위치(zip)로** 매핑한다.

  ```python
  tracked = tracker.update(sv_dets)
  tracker_ids = list(tracked.tracker_id) if tracked.tracker_id is not None else []
  if len(tracker_ids) != len(dets):
      logger.warning("[ByteTrack] tracker_id 길이 불일치 dets=%d tracker_ids=%d — 누락분 track_id=None 유지", ...)
  for det, tid in zip(dets, tracker_ids):          # ← 위치 기반. tracked 의 순서를 보지 않는다
      det.track_id = int(tid) if tid is not None and int(tid) >= 0 else None
  ```

  그런데 `ByteTrackTracker.update()` 는 **입력 순서를 보존하지 않는다.** 컨테이너 내 직접 프로브(`docker exec klid-ai-server python /tmp/bt_probe.py`, 입력 박스 순서 `[A(10,10), B(200,200), C(400,400)]`):

  ```
  step 0  ids=[-1,-1,-1]   out_xyxy=[[200..],[10..],[400..]]      # B,A,C 로 재배열
  step 1  ids=[ 0, 1,-1]   out_xyxy=[[10..],[400..],[200..]]      # A,C,B 로 재배열
  ```

  step 1 의 정답 매핑은 `A→0, C→1, B→None` 인데, 코드는 위치로 `dets[0]=A→0`(우연히 일치), **`dets[1]=B→1`(오배정 — 정답은 None)**, **`dets[2]=C→None`(정답은 1)** 을 부여한다.
  실환경 로그에도 같은 상황이 반복 관측된다: `docker logs klid-ai-server` → `WARNING:app.models.bytetrack_util:[ByteTrack] tracker_id 길이 불일치 dets=6 tracker_ids=5 — 누락분 track_id=None 유지`(3회). 이 WARN 은 "길이가 다르다"만 알릴 뿐 **순서가 다르다는 사실은 감지하지 못한다.**
  BE 는 이 값을 그대로 신뢰해 적재한다 — 배치 `batch/step/YoloAutolabelStep.java:295` → `batch/step/YoloLabelPersister.java:82-84`(`String.valueOf(trackId)` → `TRCK_ID`).
- **재현/확인 경로**:
  ```bash
  # 1) 재배열 확증 (ai-server 컨테이너 내부)
  docker exec klid-ai-server python - <<'PY'
  import numpy as np, supervision as sv
  from trackers import ByteTrackTracker
  t = ByteTrackTracker()
  boxes=[[10,10,50,50],[200,200,240,240],[400,400,440,440]]; confs=[0.9,0.15,0.85]
  for s in range(2):
      d=sv.Detections(xyxy=np.array(boxes,float),confidence=np.array(confs,float),class_id=np.array([0,0,0]))
      o=t.update(d); print(s, list(o.tracker_id), o.xyxy.tolist())
  PY
  # 2) 실경로 관측
  docker logs klid-ai-server 2>&1 | grep "길이 불일치"
  # 3) BE 경유 (REVIEWER 토큰)
  curl -s -X POST http://localhost:18081/api/v1/frames/94/yolo-track \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' \
    -d '{"srcSn":94,"nextSrcSns":[95,95,95]}'
  # → frameIndex 2·3 에서 9건 중 앞 3건에만 trackId 4,5,6 부여
  ```
- **영향**: 데이터 무결성(CWE-707 계열 — 부정확한 식별자 할당). 학습데이터의 객체 궤적이 조용히 뒤섞인다. `TrackMergeService`·`TrackEditService`·`TrackInterpolationStep` 이 모두 `TRCK_ID` 를 신뢰하므로 오염이 산출물(export JSON)까지 전파된다. 현재 실DB 에 `TRCK_ID` 보유 행이 1건뿐이라 **피해 규모는 작지만, `TRCK_ID` 가 정상화되는 순간 즉시 대량 오염으로 바뀐다**(즉 `C-ISSUE-82` 가 이 결함을 가리고 있다).
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `tracked` 가 돌려주는 `xyxy`(또는 detection index)로 **입력 detection 을 역매핑**한 뒤 `track_id` 를 부여한다(좌표 동등 비교 또는 `sv.Detections` 에 인덱스를 `data` 로 실어 왕복). 길이 불일치 WARN 은 유지하되 **순서 불일치도 감지**하는 단언을 추가한다. 역매핑이 불가능한 트래커 구현이면 `track_id` 를 아예 부여하지 않는 편(전량 None)이 오배정보다 안전하다.

---

### [C-ISSUE-82] TC-TRACK-01~11 — 트랙 보간이 실환경에서 **한 번도 실행되지 않는다**(`TRCK_ID` 사실상 전량 NULL, B-ISSUE-61 재확인)

- **심각도**: MEDIUM (기능 미작동은 HIGH 급이나, 1차 발견 이슈(B-ISSUE-61)의 2차 재확인 + 원인 규명이므로 중복 상향을 피함)
- **기대 동작(기대효과)**: CVAT 트랙 보간 포팅(SFR-08 / `docs/analysis/portable-modules/01`)의 목적은 **키프레임 사이 프레임을 자동으로 채워 라벨링 공수를 줄이는 것**이다. 배치 `INTERPOLATE` 단계가 실제로 `LBL_SRC_CD='INTERPOLATE'` 라벨을 생성해야 한다.
- **현재 동작(이슈 내용)**: 보간 후보 조건이 `TRCK_ID IS NOT NULL` 인데(`batch/step/TrackInterpolationStep.java:174` → `LsDataLblRepository.findAutoBboxWithTrackId`), 실DB 는 —

  ```
  select count(*) total, count(trck_id) with_track, count(distinct trck_id) d from ls_data_lbl;
   total | with_track | d
  -------+------------+---
     421 |          1 | 1        -- 유일한 값은 '0'
  ```

  그 결과 배치 로그가 전건 no-op 이다(`docker logs klid-backend --since 24h`):
  ```
  [Batch][Interpolation] no interpolation candidates rawSn=143
  [Batch][Interpolation] no interpolation candidates rawSn=145 / 146 / 147 / 148 / 149 / 152 / 153 / 154 / 156
  ```
  `[Batch][Interpolation] saved … interpolatedRows=` 로그는 **0건**. 즉 `TrackInterpolator`·`PolyshapeMatcher`(총 약 340줄)와 그 위의 `TrackMergeService` 재보간 원자성 배선까지 **전부 사문(死文)** 이다.
  원인은 상위 `track_id` 부재다 — ai-server 직접 프로브 결과 ByteTrack 은 `frame_index=0,1` 에서 전 검출 `track_id=null`, `frame_index=2` 에서야 9건 중 3건만 부여한다. 현 데이터의 영상당 프레임 수가 3~12장이라 확정 전에 시퀀스가 끝난다. **단위 테스트는 `Keyframe` 픽스처를 직접 생성하므로 이 결손을 구조적으로 감지하지 못한다**(`TrackInterpolatorTest` 10건 + `TrackInterpolatorPolyshapeTest` 13건 전부 GREEN).
- **재현/확인 경로**:
  ```sql
  select count(*), count(trck_id) from ls_data_lbl;
  select count(*) from ls_data_lbl l join ls_data_lbl_ai_info ai on ai.data_lbl_sn=l.lbl_sn
   where ai.lbl_src_cd='INTERPOLATE';   -- 0
  ```
  ```bash
  docker logs klid-backend --since 24h 2>&1 | grep "\[Batch\]\[Interpolation\]"
  ```
- **영향**: 요구 기능(트랙 보간) 미제공. 라벨링 공수 절감 효과 0. 나아가 **회귀 안전망이 실효 없음** — 보간 코드가 깨져도 실환경에서는 증상이 나타나지 않는다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①선행으로 `C-ISSUE-81`(track_id 오배정) 해소 ②ByteTrack 확정 지연(`track_activation_threshold`/`minimum_consecutive_frames`) 튜닝 또는 미확정 트랙에도 잠정 ID 를 부여하는 정책 결정 ③**보간 도달성 자체를 검증하는 통합 테스트**(실프레임 시퀀스 → `TRCK_ID` 부여 → `INTERPOLATE` 라벨 ≥1) 추가 — 픽스처 단위 테스트만으로는 이 결함을 영원히 못 잡는다 ④수동 라벨링에서 작업자가 트랙을 지정하는 동선이 있다면 그 경로로도 `TRCK_ID` 가 채워지는지 확인.

---

### [C-ISSUE-83] TC-TRACK-16 — `maskToRle` 가 0폭 행 mask 에서 `IllegalArgumentException` 이 아니라 `ArrayIndexOutOfBoundsException`

- **심각도**: LOW
- **기대 동작(기대효과)**: "빈 mask" 입력은 예외 없이 `int[0]` 을 돌려주거나, 최소한 이 클래스의 다른 방어 규약과 동일하게 **`IllegalArgumentException`(명시 예외)** 이어야 한다. `common.util` 규약상 이 유틸은 IAE 만 던지도록 설계돼 있다(`DetectionBoxNormalizer` 주석 참조).
- **현재 동작(이슈 내용)**: `backend/src/main/java/kr/co/cudo/authoring/common/util/MaskRleConverter.java:30-59`

  ```java
  public static int[] maskToRle(boolean[][] mask) {
      if (mask == null || mask.length == 0) { return new int[0]; }   // :31-33  ← 0폭 행은 걸리지 않음
      int h = mask.length;  int w = mask[0].length;                   // h=1, w=0
      long total = (long) h * w;                                      // 0
      ...
      boolean[] flat = new boolean[(int) total];                      // length 0
      ...
      if (flat[0]) {                                                  // :53  ← AIOOBE
  ```
  `mask = new boolean[1][0]`(또는 `new boolean[3][0]`)이면 `mask.length != 0` 이라 조기 반환 가드를 통과하고, `total=0` 이라 DoS 가드(`:37-39`)와 직사각형 가드(`:44-47`)도 통과한 뒤 `:53` 의 `flat[0]` 에서 `ArrayIndexOutOfBoundsException` 이 난다.
  `MaskRleConverterTest` 에 이 입력 형태의 테스트가 없어(빈 mask 테스트는 `new boolean[5][5]` = 값이 모두 false 인 5×5) 회귀로 잡히지 않는다.
- **재현/확인 경로**: `MaskRleConverter.maskToRle(new boolean[1][0])` → `ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0`. (정적 판독 — 이 유틸은 프로덕션 호출부가 없어 API 로는 재현 불가, `C-ISSUE-84` 참조.)
- **영향**: 현재 프로덕션 도달 경로가 **없어** 실피해 0. 다만 `C-ISSUE-84` 를 해소해 이 유틸을 실제로 배선하는 순간, 0폭 mask(모델이 폭 0 박스를 낸 경우 등)에서 예외 종류가 규약과 달라 상위 핸들러(IAE→400 매핑)를 빠져나가 **500** 이 된다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `:31-33` 가드를 `mask == null || mask.length == 0 || mask[0].length == 0` 로 확장하거나, `:53` 진입 전에 `if (total == 0) return new int[0];` 를 둔다. 대응 테스트(`maskToRle(new boolean[1][0])`) 추가.

---

### [C-ISSUE-84] TC-TRACK-31 — CVAT 포팅 유틸 5개 파일이 **전부 dead code**(1차 C-ISSUE-82 미해소 + 범위 확대)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` "CVAT 포팅 전략" 이 Phase 6 산출물로 명시한 **MASK↔RLE↔Polygon 변환**(portable-modules/02)과 **좌표 변환/회전 유틸**(portable-modules/06)은 제품 코드에서 실제로 쓰여야 한다. 또한 보간 진실원은 1개여야 한다.
- **현재 동작(이슈 내용)**: `backend/src/main` 전수 grep 결과, 아래 5개 파일은 **프로덕션 참조가 0건**이다(자기 파일 제외).

  | 파일 | 프로덕션 참조 | 비고 |
  |---|:--:|---|
  | `common/util/TrackInterpolator.java` | 0 | 1차 C-ISSUE-82 그대로. `batch/interpolation/TrackInterpolator`(POLYGON 완전 지원)와 **동명이인**이고 이쪽은 POLYGON 이 `UnsupportedOperationException`(`:37-40`) |
  | `common/util/MaskRleConverter.java` | 0 | portable-modules/02 포팅물 |
  | `common/util/CoordinateTransformer.java` | 0 | portable-modules/06 포팅물 |
  | `common/util/ShapeType.java` | 0 | dead `TrackInterpolator` 전용 |
  | `common/util/Keyframe.java` | 0 | dead `TrackInterpolator` 전용 (`batch/interpolation/Keyframe` 이 실사용본) |

  ```bash
  grep -rn "MaskRleConverter"     backend/src/main | grep -v util/MaskRleConverter.java      # 0건
  grep -rn "CoordinateTransformer" backend/src/main | grep -v util/CoordinateTransformer.java # 0건
  grep -rn "TrackInterpolator"    backend/src/main | grep -v "util/TrackInterpolator.java"
  #  → batch/step/TrackInterpolationStep.java:13,72 (batch.interpolation 쪽) 과 주석뿐
  ```
  이 5개 파일에 대응하는 단위 테스트는 **31건 존재하며 전부 GREEN** 이다(`TrackInterpolatorTest` 7 · `MaskRleConverterTest` 12 · `CoordinateTransformerTest` 10 등). 즉 **테스트 통계가 "구현·검증 완료" 처럼 보이지만 제품에는 배선되지 않았다.**
  또 TC-TRACK-31 이 요구하는 "보간 진실원 단일 확인"의 **자동 구조 단언 테스트가 없어**, 누군가 dead 쪽을 import 해도 CI 가 막지 못한다.
- **재현/확인 경로**: 위 grep 3종. 추가로 `grep -rn "common.util.ShapeType\|common.util.Keyframe" backend/src/main` → 0건.
- **영향**: ①요구 기능(RLE 변환·좌표 회전) **미배선** — SAM2 mask 산출물의 RLE 직렬화·회전 라벨 처리가 필요해지는 시점에 "이미 있다"고 오판할 위험 ②동명이인 클래스로 인한 **오import 위험**(POLYGON 미지원 쪽을 import 하면 런타임 `UnsupportedOperationException`) ③테스트 수·커버리지 지표 왜곡 ④유지보수 비용(사문 코드 5파일 + 테스트 31건).
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①`MaskRleConverter`·`CoordinateTransformer` 는 **배선 계획을 확정**(어느 경로에서 쓸지)하거나, 계획이 없으면 대응 테스트와 함께 삭제 ②`common/util/{TrackInterpolator,ShapeType,Keyframe}` 는 삭제(진실원은 `batch/interpolation`) ③삭제 대신 존치한다면 **구조 단언 테스트**(클래스패스 스캔으로 `common.util.TrackInterpolator` import 0건 단언)를 추가해 TC-TRACK-31 을 자동화.

---

### [C-ISSUE-85] TC-PRESET-15 — 마스터 soft delete 후 프리셋 코드가 **식별정보를 전부 잃는다**(`labelName`·`code` 모두 null, `string[]` 에 null 원소)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` 라벨 프리셋 절 — *"마스터에 매칭 안 되는 기존 코드(labelId null/비활성)는 오류 없이 **'미연결'**로 표시(자동 생성/삭제 없음)"*. FE 도 이를 전제로 미연결 칩에 *"legacy 라벨명 + '미연결' 배지(마스터에서 **재선택 유도**)"* 를 렌더한다(`frontend/src/features/preset/components/PresetCodeChip.tsx:18`). 재선택을 유도하려면 **어떤 라벨이었는지 보여야** 한다.
- **현재 동작(이슈 내용)**: `backend/src/main/java/kr/co/cudo/authoring/preset/service/PresetService.java:239-245`

  ```java
  private static PresetCodeView toCodeView(LsLabelPresetCode code, Map<Long, LabelMasterResponse> masters) {
      LabelMasterResponse master = code.getLabelId() == null ? null : masters.get(code.getLabelId());
      boolean linked = master != null;
      if (!linked) {
          // 미연결 — 오류 없이 legacy 코드로 노출.
          return new PresetCodeView(code.getLabelId(), code.getCode(), code.getCode(), null, false, false, false);
          //                                            ^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^ labelName 도 code 를 재사용
  ```
  그런데 **labelId 기반으로 생성된 행은 `LBL_CD` 를 저장하지 않는다**(`toSpecs:164-172` 가 `new LabelCodeSpec(id, null)`, DB 실측 `lbl_cd` NULL). 따라서 그 마스터가 나중에 soft delete 되면 `code`·`labelName` **둘 다 null** 이 된다.

  실측(마스터 41 을 `DELETE /v1/manage/labels/41` 후 프리셋 재조회):
  ```json
  "labelCodes": ["ZZTEST-C5A-RENAMED", null],
  "labelCodeOptions": [ {...}, {"labelId":41,"code":null,"labelName":null,"labelType":null,
                                "linked":false,"bboxEnabled":false,"polygonEnabled":false} ]
  ```
  FE 는 `<span>{code.labelName}</span>` 를 그대로 렌더하므로(`PresetCodeChip.tsx:31`) **이름 없는 '미연결' 칩**이 뜬다 — 운영자는 어떤 라벨을 재선택해야 하는지 알 수 없다. 또한 `labelCodes` 는 FE 타입 선언상 `string[]`(`frontend/src/features/preset/api.ts:33`)인데 **null 원소**가 실려 계약이 깨진다.
  참고: `labelId=null` 인 순수 레거시 행은 `lbl_cd` 가 있어 정상 표시된다(TC-PRESET-14 실측 `'ZZ-LEGACY-CODE'`) — **문제는 "연결됐다가 끊긴" 행에 한정**된다.
- **재현/확인 경로**:
  ```bash
  RT=<REVIEWER 토큰>
  curl -s -X POST http://localhost:18081/api/v1/manage/labels -H "Authorization: Bearer $RT" \
    -H 'Content-Type: application/json' -d '{"name":"TMP-X","color":"#AABBCC","type":"BBOX","sortNo":900}'   # → labelId
  curl -s -X POST http://localhost:18081/api/v1/manage/presets -H "Authorization: Bearer $RT" \
    -H 'Content-Type: application/json' -d '{"name":"TMP-P","labelIds":[<labelId>]}'
  curl -s -X DELETE http://localhost:18081/api/v1/manage/labels/<labelId> -H "Authorization: Bearer $RT"
  curl -s http://localhost:18081/api/v1/manage/presets -H "Authorization: Bearer $RT"
  # → labelCodes:[null], labelCodeOptions[0].labelName:null
  ```
- **영향**: 운영 복구 불능(어떤 라벨이 끊겼는지 화면·응답 어디에도 없음). `labelCodes: string[]` 계약 위반으로 FE 가 문자열 메서드를 호출하면 런타임 오류 가능(현재 `PresetCodeChip` 은 텍스트 렌더만 해서 빈칸으로 끝남). 보안 영향 없음.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: ①`toCodeView` 의 미연결 분기에서 `labelName` 폴백을 두어(`code != null ? code : "미연결 라벨 #" + labelId`) 최소한 labelId 를 노출 ②또는 `labelId` 연결 행에도 마스터 라벨명 스냅샷을 **표시 전용 폴백 컬럼**으로 남길지 정책 결정(단일 진실원 원칙과 상충하므로 "조회 실패 시 폴백" 용도로만) ③`labelCodes` 는 null 원소를 제외하거나 필드 자체를 deprecate(이미 `labelCodeOptions` 가 상위 호환) ④FE `PresetCodeChip` 에 이름 미상 시 `labelId` 표기 추가.

---

### [C-ISSUE-86] TC-PRESET-06/09(인접) — `DataIntegrityViolationException` 을 **무조건 "이미 다른 프리셋에 매핑된 이벤트입니다"** 로 변환

- **심각도**: LOW
- **기대 동작(기대효과)**: 409 응답 메시지는 실제로 위반된 제약을 반영해야 한다(이름 중복이면 이름 중복, 이벤트 중복이면 이벤트 중복).
- **현재 동작(이슈 내용)**: `preset/service/PresetService.java:174-181` · `:88-93`

  ```java
  private LsLabelPreset saveWithEventUniqueGuard(LsLabelPreset preset) {
      try { return presetRepository.saveAndFlush(preset); }
      catch (DataIntegrityViolationException e) {
          throw new CustomException(ErrorCode.CONFLICT, MSG_EVENT_CONFLICT, e);   // = "이미 다른 프리셋에 매핑된 이벤트입니다"
      }
  }
  ```
  `ls_label_preset` 에는 유니크 제약이 **2개**(`uk_ls_label_preset_evnt(evnt_type_cd)`, `uk_ls_label_preset_name(preset_nm)`)이고 `ls_label_preset_code` 에도 2개(`uk_ls_label_preset_code(preset_id,lbl_cd)`, `uk_ls_label_preset_code_lblid`)가 있는데, 어느 것이 깨져도 같은 문구가 나간다. 이름 중복은 `existsByPresetNm`(`:65-67`, `:81-83`)이 1선에서 잡지만 **그 검사와 insert 사이의 동시 요청(TOCTOU)** 은 DB 제약으로 떨어져 "이벤트" 메시지를 받는다. 운영자는 이름이 아니라 이벤트를 고치려다 계속 실패한다.
  ※ labelId 중복은 애그리거트가 dedup 하므로(§프리셋 단일 진실원 6번) API 경로로는 이 분기에 도달하지 않는다.
- **재현/확인 경로**: 동일 `name` 으로 `POST /v1/manage/presets` 를 동시 2건 발사(단일 요청 순차 실행에서는 `existsByPresetNm` 이 선행해 올바른 문구가 나온다 — 본 회차 실측 409 "이미 사용 중인 프리셋 이름입니다."). 정적으로는 위 catch 블록이 제약명을 보지 않음이 근거.
- **영향**: 오해를 유발하는 오류 메시지(사용성). 데이터 정합성·보안 영향 없음. 응답 코드(409)는 어느 경우든 옳다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `DataIntegrityViolationException` 의 root cause(`ConstraintViolationException#getConstraintName`)를 읽어 `uk_ls_label_preset_evnt` / `uk_ls_label_preset_name` 을 분기하고, 미상이면 중립 문구("프리셋 저장 중 제약 조건 위반")로 폴백한다. 제약명은 내부 스키마 정보이므로 **응답에 노출하지 말 것**(CWE-209).
