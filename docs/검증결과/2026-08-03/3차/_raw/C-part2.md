# C 클러스터 part2 — C-2. TC-LABEL 앞 1/3 (파일 75~123행, TC-LABEL-01~37 · 40~47 = 45건)

> 검증일 2026-08-03(KST 2026-08-04 00:50~01:15) · 회차 3차 · 담당 범위: `docs/test-cases/C-marking-labeling.md` 75~123행
> 방법: **풀스택 실동작 우선** — backend `localhost:18081/api` 실호출 + PostgreSQL DB 실측 + mock-server(:9400) 인바운드 로그 + backend 로그.
> 환경 전제: `_raw/stack-bringup.md`(5컨테이너 healthy, Flyway v163, 외부연동 4종 mock-server 실배선) · `_raw/pipeline-drive.md`(rawSn=101 APPROVED/export SUCCEEDED — 조회·APPROVED 축에만 사용).

## 사용한 실데이터 (신규 생성분)

| 용도 | 값 |
|---|---|
| 미검수(ASSIGNED) 라벨 CRUD | rawSn=115 · srcSn=508/509/510 (+신규 511·512·513, frm_no 90~92) · WORKER=2001 배정 |
| 동시성/IDOR/타프레임 | rawSn=110 · srcSn=514(frm_no 95)·515(frm_no 96) — REVIEWER 토큰 |
| 검수완료(APPROVED) 통지 축 | rawSn=101 · srcSn=468 (기존 파이프라인 산출) |
| 토큰 | `POST /v1/dev/tokens` — ⚠ 요청 필드는 `sub` 이 아니라 **`userNo`** (`DevTokenRequest`). `sub` 로 보내면 조용히 역할 기본값(REVIEWER=1001/WORKER=2001/PORTAL=3001)으로 발급돼 IDOR 케이스가 **거짓 PASS** 난다 |
| 이미지 경계 | raw115/raw110 프레임 = **320x240** (`FrameBoundsResolver` 실측값) |

> ⚠ 검증 중 다른 병렬 에이전트가 rawSn=115 에 비식별 신고(`DE_IDNTF_YN='F'` + 작업락)를 걸어 중반부터 115 저장이 409 가 됐다. 이후 케이스는 rawSn=110 전용 프레임으로 이관해 수행했다(판정 영향 없음).

---

## 판정표

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-LABEL-01 | PASS | [실동작] `GET /v1/frames/468/labels` (WORKER 2001) → 200. `labelVersion:1`, `siblings` 10건(`hasLabel` 포함), items[0]에 마스터 enrich(`labelName:"person"`,`color:"#E74C3C"`) + AI_INFO enrich(`autoLblYn:"N"`,`confScore:null`,`lblSrcCd:null`). 근거 `LabelService.java:187-213` 정확 |
| TC-LABEL-02 | PASS | [실동작] WORKER **2002**(raw101 미배정) `GET`·`PUT` 모두 **403** `{"errorCode":"FORBIDDEN","message":"본인에게 배정되지 않은 영상입니다."}`. PORTAL_USER GET 도 403. 근거 `LabelAccessGuard.java:62-69` 정확 |
| TC-LABEL-03 | PASS | [실동작] Authorization 헤더 없이 GET → **401** `UNAUTHORIZED`. `LabelAccessGuard.java:48-50` 정확 |
| TC-LABEL-04 | PASS | [실동작] srcSn=99999999 → **404**, srcSn=0 → **404** (`"프레임을 찾을 수 없습니다."`). `LabelAccessGuard.java:54-58` 정확 |
| TC-LABEL-05 | PASS | [실동작] REVIEWER `?raw=true` → `"frameImageType":"RAW"`. `LabelService.java:229-234` 정확 |
| TC-LABEL-06 | PASS | [실동작] WORKER `?raw=true` → `"frameImageType":"DEID"`(무시 강제). 동일 근거 |
| TC-LABEL-07 | PASS | [실동작] `PUT /v1/frames/508/labels` id=null·source=MANUAL → lblSn=730 INSERT, `ls_data_lbl_ai_info` 행 **미생성** → 응답 `autoLblYn:"N"`, 이력 `kind:"ADDED"`(before=null), `lbl_ver` 0→1. `:359-365` 정확 |
| TC-LABEL-08 | PASS | [실동작] id=730 좌표 변경 → UPDATE + 이력 `kind:"UPDATED"` before `[[10,10],[100,100]]` / after `[[20,20],[120,120]]` 양쪽 스냅샷 기록. `:323-348` 정확 |
| TC-LABEL-09 | PASS | [실동작] srcSn=509 에 3건(731/732/733) 생성 후 731만 전송 → 732·733 **실삭제**, 이력에 DELETED 2건 + before 스냅샷(after=null). `:375-393` 정확 |
| TC-LABEL-10 | PASS | [실동작] `items:[]` → 프레임 라벨 0건, 이력 DELETED 기록, `lbl_ver` +1. 동일 근거 |
| TC-LABEL-11 | PASS | [실동작] 삭제 대상 라벨(731)에 `ls_data_lbl_attr_val`(실 FK) 1건 + 다른 라벨(732)에 `ls_data_lbl_ai_info` 1건을 심은 뒤 삭제 → **FK 위반 500 없이 200**, 자식 행 전부 동반 삭제(고아 0). `:390-392` 정확 |
| TC-LABEL-12 | PASS | [실동작] `ls_auth_work_lock` 에 `TARGET_RAW/115/LOCKED` INSERT → `PUT` **409** `"비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."`, 조회는 200 + `lockSttsCd:"LOCKED"`. 락 해제 후 저장 200. `:255-258` 정확. ⚠ **방어 심층 공백 발견 → C-ISSUE-22**(락은 6h 만료·sweep 회수, `DE_IDNTF_YN='F'` 는 남음 → 저장 재개방) |
| TC-LABEL-13 | PASS | [실동작] 같은 id=730 을 2개(label "first"/"last") 전송 → 최종 저장값 `last` + `[[7,7],[70,70]]`, 라벨 1건 유지. `:501-519` 정확 |
| TC-LABEL-14 | PASS | [실동작] `points:[]` → 400 `"items[0].points: must not be empty"`(DTO `@NotEmpty`), `points:null` 도 동일 메시지 → 카탈로그의 "서비스 메시지 도달 불가" 서술 실증. `LabelItemDto.java:42` 정확 |
| **TC-LABEL-15** | **PARTIAL** | [실동작] `pair size=3` → 400 / `pair=null` → 400 (기대 충족). **그러나 원소 null `[[null,5],[10,10]]` 은 여전히 500 `INTERNAL_ERROR`** — 1차 **C-ISSUE-21 미해소 이월**. 대조군 SKELETON(`:805-811`)은 400. → **C-ISSUE-21** |
| TC-LABEL-16 | PASS | [실동작] BBOX `x=-1` → 400 `"좌표는 0 이상이어야 합니다 (x=-1.0, y=5.0)"`. **SEGMENT·TRACK 타입도 동일 400** 확인(형태 전 분기 커버). `:725-728` 정확. ★3(사용자 저장=거부) 정합 |
| TC-LABEL-17 | PASS | [실동작] `[[0,0],[10,10]]` → 200 저장 성공(0 은 음수 아님) |
| TC-LABEL-18 | PASS | [실동작] 신규 POLYGON **1001점 → 400** `"라벨당 좌표 개수 초과 (최대 1000 점)"`, **1000점(경계) → 200** 저장 후 `json_array_length=1000` 보존. `:715-718` 정확 |
| TC-LABEL-19 | PASS | [실동작] 기존 라벨(id 지정) 1001점 → **400 아님, 200** + 저장값 `json_array_length=34`(Douglas-Peucker simplify). `:838, :907-912` 정확 |
| TC-LABEL-20 | PASS | [실동작] labelId=999999 → **404** `"라벨 마스터를 찾을 수 없습니다: labelId=999999"`. `:670-673` 정확 |
| TC-LABEL-21 | PASS | [실동작] ①신규(id=null)에 비활성 labelId=10 → **409** `"사용 중지된 라벨입니다"` ②DB로 심은 기존 라벨(lblSn=736, lblId=10)이 **같은 labelId 유지** 저장 → **200 통과**(C-ISSUE-25 수정 실증) ③기존 라벨의 labelId 를 다른 비활성(11)로 **변경** → 409. `:650-679, :685-695` 정확 |
| TC-LABEL-22 | PASS | [실동작] ①`ls_data_lbl` 에 `auto_lbl_yn` **컬럼 자체 없음**(DDL 실측: `lbl_sn/src_sn/lbl_type_cd/lbl_nm/point_cn/trck_id/reg_user_no/reg_dt/mdfcn_dt/lbl_id`) ②수동 신규에 `autoLblYn:"Y"` 전송 → 응답 `"N"` ③기존 AUTO 라벨을 `autoLblYn:"N"`+`source:"MANUAL"`+`confScore:0.01` 로 UPDATE → 응답 **`"Y"`/0.5/YOLO 유지**. 추가 반증: `trckId`,`lblSn`,`regUserNo`,`isAdmin` 미지 필드 주입 → 전부 무시(`trck_id=null`, `reg_user_no`=actor). `:133-143, :436` 정확 |
| TC-LABEL-23 | PASS | [실동작] `source:"AUTO_YOLO", confScore:0.87, algorithm:"YOLO"` → 응답 `autoLblYn:"Y"`, `ls_data_lbl_ai_info` 행 생성(`lbl_src_cd=YOLO, conf_score=0.87000, auto_lbl_yn=Y, data_raw_sn=115, data_src_sn=510, reg_id=2001`). `:351-358, :853-869` 정확 |
| TC-LABEL-24 | PASS | [실동작] `source:"HACK"` → 400 `@Pattern`. `algorithm:"EVIL"` → 400, `lblTypeCd:"FOO"` → 400. `LabelItemDto.java:44-45` 정확 |
| TC-LABEL-25 | PASS | [실동작] `confScore:1.5` → 400 `@DecimalMax`, `-0.1` → 400 `@DecimalMin`. `:46-47` 정확 |
| TC-LABEL-26 | PASS | [실동작] items **501건 → 400** `"한 번에 처리 가능한 라벨 수 초과 (최대 500)"`, **500건(경계) → 200**(DB 500행 확인), `items:null` → 400 `@NotNull`. `LabelBulkUpsertRequest.java:24` 정확 |
| TC-LABEL-27 | PASS | [실동작] 완전 동일 payload 재저장 → 이력 신규 0건 + `lbl_ver` 2 유지 + 통지 미발행. 반증: **좌표는 같고 라벨명만 변경** → UPDATED 발행 / **labelId 만 변경** → UPDATED 발행(무변경 판정이 과잉 흡수하지 않음). `:342-348, :397-399` 정확 |
| TC-LABEL-28 | PASS | [실동작] 저장값 `[[20.0,20.0],[120.0,120.0]]` 에 대해 `[[20,20],[120.000,120.0]]` 재전송 → 이력 미발행·버전 미증가. `:547-561` 정확 |
| TC-LABEL-29 | PASS | [실동작] DB에 레거시 2포맷 직접 심고(`[{"x":5,"y":6},{"x":50,"y":60}]` / `[7,8,70,80]`) 동일 좌표를 정규 포맷으로 재전송 → **UPDATED 이력 0건**(두 포맷 모두 흡수). `:568-585` 정확 |
| TC-LABEL-30 | PASS | [실동작] `point_cn='{not-json'` 손상값 라벨을 재저장 → **UPDATED 이력 기록**(before `"{not-json"` 보존) = '변경됨' fail-safe. `:556-559, :582-584` 정확. ※카탈로그 주석대로 **전용 회귀 테스트는 여전히 부재**(3차 재확인) → **C-ISSUE-23**(LOW, 이월) |
| TC-LABEL-31 | PASS | [실동작] APPROVED rawSn=101/srcSn=468 좌표 수정 → `ls_mon_noti_acml` 행 생성(`raw_sn=101, stts_cd=PENDING, export_rprcs_yn='Y', chg_dtl_cn={"frames":{"468":["LABEL_UPDATED"]}}`) → 60s 디바운스 flush 후 backend 로그 순서 실측: `flush rawSn=101 regen=true` → `export succeeded rawSn=101 **version=2** written=20` → `TASK_MODIFIED sent rawSn=101 reExport=true`. mock-server 인바운드 `POST /api/data-set/v2/jobs/101/notify-updated 202 Accepted`, `ls_dataset_export` 에 `export_ver_no=2` SUCCEEDED 추가. **export 성공 후 통지** 순서까지 정합. `:413-426` 정확 |
| TC-LABEL-32 | PASS | [실동작] ASSIGNED rawSn=115 저장 → `ls_mon_noti_acml` 행 **미생성**, mock-server `notify-updated` 카운트 불변(8→8). `:413` 정확 |
| TC-LABEL-33 | PASS | [실동작] WORKER 2002 `GET /v1/frames/468/label-history` → **403**. `:477` 정확 (엔드포인트는 `/label-history` — 카탈로그 표기는 근거 파일이라 영향 없음) |
| TC-LABEL-34 | PASS | [실동작] `size=500` → 응답 `size:100` 클램프. 반증: `size=0`·`size=-1` → **500 아님**, 기본 20 폴백. `page=999999` → 200 빈 content. `:593-594` 정확 |
| TC-LABEL-35 | PASS | [실동작] `?sort=badfield,desc` → **200**(500 아님), `?sort=regDt;DROP TABLE,desc` → 200. 응답 순서 `lblHstrySn` 내림차순(REG_DT DESC + tiebreaker). `:588-600` 정확 |
| TC-LABEL-36 | PASS | [실동작·동시성] ①stale `labelVersion:0` 첨부 → **409** `"다른 사용자가 먼저 저장했습니다…"` ②정확한 버전 첨부 → 200(+1) ③미첨부 → 200(하위호환 skip) ④음수 → 400 `@PositiveOrZero` ⑤**동일 baseVersion 으로 10병렬 PUT → 정확히 1건 200 / 9건 409**, 최종 `lbl_ver=2`·생존 라벨 1건. 락 획득 시점 CAS(스칼라 프로젝션)가 실경합에서 동작함을 실증. `:272-274, :457-465` 정확 |
| TC-LABEL-37 | PASS | [실동작] backend 로그 전량 확인 — `[Label] bulkUpsert srcSn=511 actor=2001 existing=1 saved=0 deleted=1 labelVersion=3->4` 형태로 **카운트+버전만**, 좌표·라벨명·PII 없음. 409 경로도 `stale label version rejected srcSn/requested/current` 만. `:408-409` 정확 |
| TC-LABEL-40 | PASS | [실동작] `POST /v1/manage/labels` REVIEWER → **201** `{labelId:30,...,useYn:"Y"}`. `LabelMasterService.java:74-95` 정확 |
| TC-LABEL-41 | PASS | [실동작] 활성 `"qa-c2p2-alpha"` 존재 시 `"  QA-C2P2-Alpha  "` → **409**. 반증: **soft-delete 된 동일 이름 재사용은 201 허용**(부분 유니크 `WHERE use_yn='Y'` 시맨틱 정합). `:76-78` 정확 |
| TC-LABEL-42 | PASS | [실동작·동시성] 동일 이름 8병렬 POST → **1건 201 / 7건 409**, DB 1행. 인덱스 실측 `uk_ls_label_nm_ci ON (lower(btrim(lbl_nm))) WHERE use_yn='Y'` 존재. `:74-78 · LsLabelRepository.java:37` 정확 |
| TC-LABEL-43 | PASS | [실동작] `dtctTypeCd:"human"` → 400 `"지원하지 않는 검출 클래스입니다."`. 반증: `"Person"`(대문자) → **400**(대소문자 구분 allowlist), `"  car  "` → trim 후 유효 판정되어 409(중복 매핑), 21자 → 400 `@Size(max=20)`. `:122-134` 정확 |
| TC-LABEL-44 | PASS | [실동작] 활성 `person` 매핑 존재 시 재매핑 → **409** `"이미 사용 중인 검출 클래스 매핑입니다."`. 인덱스 `uk_ls_label_dtct_type ... WHERE use_yn='Y' AND dtct_type_cd IS NOT NULL` 실측. `:81-82, :109-111` 정확 |
| TC-LABEL-45 | PASS | [실동작] `dtctTypeCd:"   "`(blank) → 201 + `dtctTypeCd:null` 저장. `:122-134` 정확 |
| TC-LABEL-46 | PASS | [실동작] `"#ffffff"` → 400 `"color 는 대문자 hex (#RRGGBB) 형식이어야 합니다."`. `LabelMasterRequest.java:33` 정확 |
| TC-LABEL-47 | PASS | [실동작] `type:"FOO"` → 400 `@Pattern(BBOX/POLYGON/POINT/SKELETON)`. `:37` 정확 |

### 집계 (45건)

| PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|:--:|:--:|:--:|:--:|:--:|:--:|
| **44** | 0 | **1** | 0 | 0 | 0 |

- 실동작 검증 45/45 (정적 전용 0건).
- 근거 `file:line` **드리프트 0건** — 45행 전량 실파일 대조 결과 3차 재확인분과 일치.

---

## 추가 반증 결과 (케이스 외 — 결함 아님으로 확인된 것)

| 반증 시도 | 결과 |
|---|---|
| full-replace 에 **타 프레임 라벨 id** 주입(IDOR/데이터 파괴) | 타 프레임 라벨(lblSn=1252/srcSn=515) **무손상 생존**, 현재 프레임 신규 ADDED 로 안전 처리 — 문서화된 설계와 일치 |
| Mass Assignment(`trckId`/`lblSn`/`regUserNo`/`isAdmin`) | 전부 무시 (`trck_id=null`, `reg_user_no`=토큰 actor) |
| 이력 API `size=0/-1`, `page=999999`, sort 인젝션 | 전부 200, 500·오라클 없음 |
| 무변경 판정의 과잉 흡수 (라벨명·labelId 만 변경) | 정상적으로 UPDATED 발행 — `snapshotsEqual` 이 좌표만 보지 않음 |
| APPROVED 통지가 export 보다 앞서는가 | export `version=2` 성공(01:03:23.739) → 통지(01:03:23.742) 순서 실측 정합 |

---

## 이전 회차 이슈 대조

| 이전 이슈 | 상태 | 근거 |
|---|:--:|---|
| **1차 C-ISSUE-21** (TC-LABEL-15, 좌표 원소 null → NPE 500) | **미해소 (이월)** | 3차 실동작 재현 — 동일 500 `INTERNAL_ERROR`. 자동테스트 baseline(backend 5,203건 전건 통과)에도 이 경로 회귀 테스트 없음 |
| 카탈로그 주석 C-ISSUE-23 (TC-LABEL-30 회귀 테스트 부재) | **미해소 (이월)** | `LabelServiceFullReplaceIntegrationTest` 의 "손상된_diff_JSON…" 은 **이력 조회 폴백** 테스트이고, `pointsEqual`/`normalizePoints` 손상값 fail-safe 전용 테스트는 여전히 없음 |
| 1차 C-ISSUE-25 (비활성 마스터가 프레임 저장 영구 차단) | **해소 확인** | TC-LABEL-21 ② 실동작 200 |
| UNCERTAINTIES #9 (동시 저장 Race) | **확정 재실증** | 10병렬 CAS 1/9 결과 |
| UNCERTAINTIES #10 / ★3 (좌표 경계) | **확정 재실증** | 사용자 저장 경로는 경계 초과 400 거부(클램프 아님) |

---

## 카탈로그 정정 (담당 라인범위 75~123행 내에서만 수행)

| 행 | 정정 내용 |
|---|---|
| TC-LABEL-15 (93행) | 입력/조건에 `pair=null` 추가 + 기대결과에 **"원소 null 은 여전히 500 — 1차 C-ISSUE-21 미해소 이월"** 명시(대조군 SKELETON `:805-811` 병기). 근거 라인 `719-722` → **`719-728`**(음수 검사까지 포함한 2-튜플 루프 전체가 판정 지점) |
| TC-LABEL-12 (90행) | 기대결과에 **"저장 경로에는 신고 게이트가 없고 작업락이 유일 방어 — 락 6h 만료 후 조회 412 ↔ 저장 200 비대칭(C-ISSUE-22)"** 경고 추가 |

**정정 2건.** 프로덕션 코드·타 라인범위 무수정.

---

# 이슈 대장

### [C-ISSUE-21] TC-LABEL-15 — 좌표 배열 **원소**가 null 이면 2-튜플 경로에서 언박싱 NPE → 500 (1차 미해소 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 좌표 **형식 위반은 전부 400(INVALID_INPUT)** 이어야 한다. SKELETON 경로는 같은 상황을 명시적으로 400 으로 막고 있고 그 주석이 계약을 선언한다(*"언박싱 전에 400 으로 fail-secure 거부하여 계약(형식 위반=400)과 일치시킨다"*). CWE-20 입력 방어선 + OWASP A10:2025(fail-secure) 요구.
- **현재 동작(이슈 내용)**: `pair.size() != 2` 만 검사한 뒤 곧바로 `Double → double` 언박싱한다. `pair.get(0)` 이 null 이면 NPE → `GlobalExceptionHandler` catch-all → **500**.

  `backend/src/main/java/kr/co/cudo/authoring/label/service/LabelService.java:719-728`
  ```java
  for (List<Double> pair : points) {
      if (pair == null || pair.size() != 2) {
          throw new CustomException(ErrorCode.INVALID_INPUT, "좌표는 [x, y] 형태여야 합니다.");
      }
      double x = pair.get(0);   // ← pair.get(0) == null 이면 언박싱 NPE
      double y = pair.get(1);
  ```
  대조군 `LabelService.java:805-811`(SKELETON)은 동일 상황을 400 으로 거부한다.

  **3차 실동작 재현 (2026-08-04 01:00 KST)**
  ```
  PUT /api/v1/frames/510/labels  (WORKER 2001, 배정 프레임)
  {"items":[{"lblTypeCd":"BBOX","labelId":1,"label":"x","points":[[null,5],[10,10]]}]}
  → HTTP 500 {"success":false,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}

  (대조) points:[null,[10,10]]          → 400 INVALID_INPUT "좌표는 [x, y] 형태여야 합니다."
  (대조) points:[[1,2,3],[4,5,6]]       → 400 INVALID_INPUT (동일 메시지)
  ```
- **재현/확인 경로**
  ```bash
  B=http://localhost:18081/api
  curl -s -X POST $B/v1/dev/tokens -H 'Content-Type: application/json' \
    -d '{"userNo":"2001","role":"WORKER","channel":"INTERNAL"}' -o /tmp/t.json
  T=$(python3 -c 'import json;print(json.load(open("/tmp/t.json"))["data"]["token"])')
  curl -i -X PUT $B/v1/frames/510/labels -H "Authorization: Bearer $T" \
    -H 'Content-Type: application/json' \
    -d '{"items":[{"lblTypeCd":"BBOX","labelId":1,"label":"x","points":[[null,5],[10,10]]}]}'
  # → HTTP/1.1 500, errorCode=INTERNAL_ERROR  (기대: 400 INVALID_INPUT)
  ```
- **영향**
  - **계약 위반(CWE-20 / CWE-248)**: 입력 형식 오류가 500 으로 나가 FE 가 "서버 장애"로 오분류 → 무의미한 재시도 유발.
  - **운영 알람 노이즈**: 매 요청마다 ERROR 레벨 스택트레이스가 로그에 남아 알림 대상이 된다.
  - 응답 본문에 스택트레이스는 없어 CWE-209 정보노출은 없음.
  - 유입 경로: FE 캔버스 부분 직렬화 버그, 외부 도구로 만든 라벨 임포트, SAM2/YOLO 결과 후처리 누락 등.
  - **자동테스트 공백**: backend 5,203건 전건 통과인데도 이 경로가 잡히지 않는다(회귀 가드 부재).
- **수정 방향(제안)**: `LabelService.validatePoints` 의 2-튜플 루프에서 `pair.get(0)`/`pair.get(1)` 을 `Double` 로 먼저 받아 null 검사 후 언박싱한다(SKELETON `validateSkeletonPoints:805-811` 과 동일 패턴). 메시지는 SKELETON 과 대칭으로 `"좌표에 null 원소가 있습니다."` 권장. 회귀 테스트는 `LabelControllerTest` 또는 `LabelSaveGuardsIT` 에 BBOX/POLYGON/SEGMENT/TRACK 4형태 × 신규/기존 2경로로 추가. ⚠ **구현은 하지 않음.**

---

### [C-ISSUE-22] TC-LABEL-12 (인접) — 신고 구간 라벨 **저장** 방어가 작업락 하나뿐이라, 락 6h 만료 후 "조회 412 ↔ 저장 200" 비대칭이 열린다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 비식별 누락 신고(`DE_IDNTF_YN='F'`) 구간에는 그 영상의 라벨을 **읽지도 쓰지도 못해야** 한다. `CLAUDE.md` 는 *"신고 구간 동안 라벨 조회를 차단한다(412) … 저장·수정은 기존 작업락으로 409 차단"* 이라고 두 축을 나눠 선언하는데, 이 분업이 성립하려면 **작업락의 수명이 신고 구간과 같아야** 한다.
- **현재 동작(이슈 내용)**: 두 수명이 다르다.
  - 신고 락은 **6시간 만료**로 생성된다 — `backend/src/main/java/kr/co/cudo/authoring/auth/entity/LsAuthWorkLock.java:84`
    ```java
    public static LsAuthWorkLock lockRawForRedeident(Long rawSn, String ownerId) {
        ...
        lock.expireDt = lock.lockDt.plusHours(6);
    ```
  - `WorkLockSweepJob` → `WorkLockService.sweepExpiredLocks()` 가 만료 락을 **자동 회수(RELEASED)** 한다.
  - 그러나 `DE_IDNTF_YN='F'` 는 **외부 솔루션 수동 재비식별 후 `resolve` 호출까지** 남는다(정책상 자동 재비식별 큐 없음 → 6시간 초과가 정상 동선).
  - `LabelService.bulkUpsert` 는 `accessGuard.requireNotUnderDeidentReport(...)` 를 **호출하지 않는다**(`LabelService.java:250-258` — 락 검사만). 반면 `getByFrame:193` / `getHistory:482` 는 호출한다.

  **3차 실동작 재현 (rawSn=110 에 `DE_IDNTF_YN='F'` + 작업락 0건 상태를 만들어 확인 → 즉시 원복)**
  ```
  DB: ls_data_raw.de_ident_yn='F' (raw_sn=110),  ls_auth_work_lock LOCKED 0건

  GET  /api/v1/frames/515/labels        → 412 PRECONDITION_FAILED
       {"message":"비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요."}
  PUT  /api/v1/frames/515/labels  {"items":[]}
                                        → 200 OK, labelVersion 5→6
  DB:  select count(*) from ls_data_lbl where src_sn=515;  → 0   (기존 라벨 전량 삭제됨)
  ```
- **재현/확인 경로**
  ```sql
  -- 운영 재현 조건: 신고 접수 후 6시간이 지나 sweep 이 락을 회수했고 아직 resolve 안 된 영상
  SELECT r.raw_sn, r.de_ident_yn,
         (SELECT count(*) FROM ls_auth_work_lock l
           WHERE l.data_raw_sn = r.raw_sn AND l.lck_stts_cd='LOCKED') AS locked
    FROM ls_data_raw r
   WHERE r.de_ident_yn = 'F';
  -- locked = 0 인 행이 이 창에 들어간 영상
  ```
  ```bash
  # 해당 rawSn 의 프레임에 대해
  curl -i -X GET $B/v1/frames/{srcSn}/labels -H "Authorization: Bearer $T"   # → 412
  curl -i -X PUT $B/v1/frames/{srcSn}/labels -H "Authorization: Bearer $T" \
       -H 'Content-Type: application/json' -d '{"items":[]}'                # → 200 (전량 삭제)
  ```
- **영향**
  - **데이터 유실(HIGH)**: 저장 계약이 full-replace 인데 **조회는 412 로 막혀 있어** 작업자/FE 가 현재 라벨 세트를 읽을 수 없다. 그 상태에서 저장이 통과하므로 **불완전한(또는 빈) 세트가 전량 교체로 커밋**되어 기존 라벨이 조용히 사라진다. 실동작에서 `items:[]` 로 전량 삭제가 실제로 성공했다.
  - **정책 우회(CWE-863 — Incorrect Authorization / OWASP A10:2025 fail-open)**: 신고 게이트의 목적(재비식별 완료 전 라벨 축 접근 차단)이 시간 경과만으로 반쯤 무력화된다. 게이트가 "조회만 막고 쓰기는 연다"는 상태는 어느 정책 문서에도 없다.
  - **PII 간접 노출**: 저장 응답(`LabelResponse`)에는 저장 결과 items 의 **좌표가 그대로 실린다**. 즉 412 로 막힌 좌표를 **PUT 으로 우회 열람**할 수 있다(무변경 재전송이면 부작용 없이 조회 효과만 얻음). 라벨 좌표는 CLAUDE.md 가 "PII 위치 특정 정보"로 규정한 대상이다(CWE-359).
  - **역할 무관**: WORKER(본인 배정)·REVIEWER 모두 해당.
- **수정 방향(제안)**: 아래 중 하나. 조합이 가장 안전하다.
  1. **(권장) `LabelService.bulkUpsert` 에도 게이트를 건다** — `accessGuard.verifyAndGet` 직후, 락 검사 앞뒤 어디든 `accessGuard.requireNotUnderDeidentReport(current.getRawSn())` 추가. 게이트는 이미 인가 이후 평가되는 프리컨디션이고 `DeidentReportGate` 단일 원천이라 판정 중복이 생기지 않는다. 단 **응답 코드 정합** 검토 필요 — 저장 경로의 기존 신고 차단은 409(작업락)였으므로, 412 로 바꾸면 FE 분기가 늘어난다. 락 있으면 409·없으면 412 로 갈리는 것도 상태 오라클이 되므로 **저장 경로는 412 로 통일**을 권장.
  2. 신고 락에 **만료를 두지 않는다**(`expireDt = null`) — 신고 락만 sweep 대상에서 제외. 다만 락 누수 시 영구 차단이 되므로 resolve 경로가 유일 해제라는 점을 확인해야 한다.
  3. `sweepExpiredLocks` 회수 시 `DE_IDNTF_YN='F'` 인 RAW 의 `REASON=REDEIDENT` 락은 **만료 연장**(재획득)한다.

  ⚠ 어느 안이든 **★1 확정 정책(게이트 판정 범위 = 자기 rawSn 행 하나, 조상/자손 전파 금지)** 을 건드리지 않는다 — 본 이슈는 전파 범위가 아니라 **동일 rawSn 내 읽기/쓰기 축 비대칭**이다. ⚠ **구현은 하지 않음.**

---

### [C-ISSUE-23] TC-LABEL-30 — `pointsEqual` 손상 JSON fail-safe 전용 회귀 테스트 부재 (이월, 동작은 정상)
- **심각도**: LOW
- **기대 동작(기대효과)**: `point_cn` 이 파싱 불가한 손상값일 때 `pointsEqual` 이 **'변경됨'(false)** 으로 처리해 이력을 남겨야 한다(이력 유실 방지 fail-safe). 이 동작이 반대로 회귀하면 손상 라벨의 수정 이력·`TASK_MODIFIED` 통지가 조용히 사라진다 — 관제 동기화 요구에 직결된다.
- **현재 동작(이슈 내용)**: 동작 자체는 정상이다. 3차 실동작에서 `point_cn='{not-json'` 라벨을 재저장하니 UPDATED 이력이 before `"{not-json"` 과 함께 기록됐다(`LabelService.java:556-559, :582-584`). 그러나 이 경로를 고정하는 **전용 테스트가 없다**.
  - `LabelServiceFullReplaceIntegrationTest:277` `"손상된_diff_JSON이_있어도_조회는_200과_빈changes를_반환한다"` 는 **`LS_DATA_LBL_HSTRY.CHG_DTL_CN` 조회 폴백** 테스트로, 대상 필드도 코드 경로도 다르다.
  - `LabelServiceFullReplaceIntegrationTest` 의 레거시 포맷 테스트 2건(평탄/객체배열)은 **정상 파싱 성공** 경로만 덮는다.
  - backend 자동테스트 5,203건 전건 통과 상태에서도 이 분기는 미커버.
- **재현/확인 경로**
  ```sql
  UPDATE ls_data_lbl SET point_cn = '{not-json' WHERE lbl_sn = {lblSn};
  ```
  ```bash
  curl -X PUT $B/v1/frames/{srcSn}/labels -H "Authorization: Bearer $T" \
    -H 'Content-Type: application/json' \
    -d '{"items":[{"id":{lblSn},"lblTypeCd":"BBOX","labelId":1,"label":"broken","points":[[1,1],[10,10]]}]}'
  # 기대: LS_DATA_LBL_HSTRY 에 UPDATED 1건 (현재 정상 동작)
  ```
- **영향**: 기능 영향 없음(현재 정상). 회귀 가드 부재로 향후 `normalizePoints` 리팩터링 시 fail-safe 가 조용히 반전될 수 있고, 그 결과는 **이력·관제 통지 유실**이라 사후 발견이 어렵다.
- **수정 방향(제안)**: `LabelServiceFullReplaceIntegrationTest` 에 `"손상된_point_cn_라벨_재저장시_변경됨으로_판정되어_UPDATED_이력이_남는다"` 추가. 대조군으로 `"레거시_평탄포맷은_무변경으로_판정된다"`(이미 존재)를 같은 클래스에 두어 fail-safe 와 흡수 로직의 경계를 고정한다. ⚠ **구현은 하지 않음.**
