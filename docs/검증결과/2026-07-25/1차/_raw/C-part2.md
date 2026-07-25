# C-2 (전반). TC-LABEL-01 ~ 51 — 라벨 CRUD / full-replace / 마스터

> 대상: `docs/test-cases/C-marking-labeling.md` 45~97행 (TC-LABEL-01~37, 40~51 — 38·39 결번, 총 **49건**)
> 검증일: 2026-07-25 · 회차 1차 · 검증자: C-part2 에이전트

## 검증 환경 · 실동작 근거 확보 경로

- 스택: `klid-backend`(재빌드 후 healthy, `local`) / `klid-postgres` / `klid-ai-server` / `klid-mock-server:9400` / `klid-frontend` 전부 기동.
- 토큰: `POST /v1/dev/tokens` — REVIEWER(sub=1001) / WORKER(sub=2001) / PORTAL_USER.
- **쓰기 시험 대상(기존 검증 데이터 비파괴 선정)**:
  - `srcSn=1`(rawSn=4, WORKER 2001 배정, 작업락 RELEASED, **라벨 0건**) — CRUD·full-replace·검증 본진
  - `srcSn=350`(rawSn=12, **작업락 LOCKED**) — TC-12 전용(409 로 무변경)
  - `srcSn=359`(rawSn=13, **APPROVED**, 라벨 0건) — TC-31 전용
  - `srcSn=432`(rawSn=18, WORKER **미배정**) — IDOR 전용(읽기만)
  - `rawSn=26`(참조 완주 데이터)·`rawSn=11/13/14/17/19` 의 **라벨은 일절 건드리지 않음**
- **DB 변경 내역(복구 상태)**: ①`srcSn=1`·`srcSn=359` 라벨 생성/삭제 반복 → **최종 0건으로 원상 복구**(시험 전과 동일). ②`ls_data_lbl_hstry` 에 시험 저장이벤트 행이 남음(감사 이력이라 삭제하지 않음). ③`ls_label` 에 `TCVERIFY-A`(497), `TCVERIFY-RACE`(498), `TCVERIFY-A`(503) 3건 생성 → **전부 soft delete(`USE_YN='N'`) 처리**하여 활성 라벨 목록 미오염.

## 판정표

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-LABEL-01 | 프레임 라벨 조회 정상 | PASS | [실동작] `GET /v1/frames/350/labels`(WORKER) 200 — `siblings` 8건 + `hasLabel` + `frameImageType` + `lockSttsCd` 반환. TC-07 응답에 `labelName`/`color`(LS_LABEL enrich) + `confScore`/`lblSrcCd`(AI_INFO enrich) 확인. [정적] LabelService.java:182-200 | LabelServiceSiblingHasLabelTest(5) | enrich 2종 모두 실측 |
| TC-LABEL-02 | 조회 IDOR: 타 영상 프레임 | PASS | [실동작] WORKER→srcSn=432(rawSn=18 미배정) **403 FORBIDDEN** "본인에게 배정되지 않은 영상입니다." [정적] LabelAccessGuard.java:48-54 | FrameImageControllerTest 등 다수 | |
| TC-LABEL-03 | 조회: 미인증 | PASS | [실동작] 토큰 없이 GET → **401 UNAUTHORIZED**. [정적] LabelAccessGuard.java:40-42 | — | |
| TC-LABEL-04 | 조회: 프레임 미존재 | PASS | [실동작] srcSn=999999(REVIEWER) → **404** "프레임을 찾을 수 없습니다." [정적] LabelAccessGuard.java:43-44 | — | |
| TC-LABEL-05 | frameImageType RAW(REVIEWER+raw) | PASS | [실동작] `?raw=true`+REVIEWER → `"frameImageType":"RAW"`. [정적] LabelService.java:216-221 | — | |
| TC-LABEL-06 | frameImageType DEID(WORKER raw무시) | PASS | [실동작] `?raw=true`+WORKER → `"frameImageType":"DEID"` (강제). [정적] :216-221 | — | 원본 노출 차단 실측 |
| TC-LABEL-07 | bulkUpsert 신규 INSERT(수동) | PASS | [실동작] 3건 신규 저장 → 200, `autoLblYn:"N"`, `ls_data_lbl_hstry` **ADD_CNT=3**. [정적] :308-324 | FullReplaceIT `firstSaveAllAdded` | |
| TC-LABEL-08 | bulkUpsert 기존 UPDATE | PASS | [실동작] id 지정 좌표 변경 → 이력 `MDFCN_CNT=1`, `chg_dtl_cn` 에 `before {[[1,1],[20,20]]}` / `after {[[5,5],[25,25]]}` 스냅샷 기록. [정적] :288-307 | FullReplaceIT `updatedRecordsBeforeAndAfter` | |
| TC-LABEL-09 | full-replace: 빠진 라벨 삭제 | PASS | [실동작] 기존 3건 중 2건만 전송 → DB 잔존 2건, 이력 `DEL_CNT=1` + `kind:"DELETED"` before 스냅샷(`bus`) 보존. [정적] :328-352 | FullReplaceIT `omittedLabelIsPhysicallyDeleted` | 삭제 전 before 스냅샷 확인 |
| TC-LABEL-10 | full-replace: 빈 items=전량 삭제 | PASS | [실동작] `{"items":[]}` → 200, `select count(*) from ls_data_lbl where src_sn=1` **=0**. [정적] :334-352 | FullReplaceIT `allExistingDeleted` | |
| TC-LABEL-11 | 삭제 순서 FK고아 방지 | PASS | [실동작] AI_INFO 보유 라벨(lbl_sn=381) 삭제 → FK 위반 없이 200, `ls_data_lbl_ai_info` 잔존 **0건**. [정적] :346-351 (ATTR_VAL→AI_INFO→LBL) | FullReplaceIT `deletedLabelChildrenRemovedWithoutFkViolation`, `multiDeleteNoOrphans` | |
| TC-LABEL-12 | 작업락 시 저장 차단 | PASS | [실동작] srcSn=350(rawSn=12 `ls_auth_work_lock` LOCKED) PUT → **409 CONFLICT** "비식별 재처리 중인 영상은…". [정적] :242-245 | — | 락 실데이터 존재 확인 |
| TC-LABEL-13 | dedupById 동일 id 중복 | PASS | [실동작] 동일 id 2회(`[7,7]` 후 `[9,9]`) → DB `[[9.0,9.0],[29.0,29.0]]` last-value-wins. [정적] :424-442 | FullReplaceIT `duplicateIdDeduped` | |
| TC-LABEL-14 | 좌표 검증: 빈 points | PASS | [실동작] `points:[]` → **400** `items[0].points: must not be empty`. **주의**: DTO `@NotEmpty`(LabelItemDto:42) 가 먼저 잡아 서비스 :576-578 메시지("points 가 비어있습니다")는 도달 불가 | — | 결과 동일(400) |
| TC-LABEL-15 | 좌표 검증: [x,y] 형식 위반 | PASS | [실동작] `[[1,2,3],[4,5,6]]` → **400** "좌표는 [x, y] 형태여야 합니다." [정적] :588-591 | — | |
| TC-LABEL-16 | 좌표 검증: 음수 | PASS | [실동작] `[[-1,5],…]` → **400** "좌표는 0 이상이어야 합니다 (x=-1.0, y=5.0)". [정적] :592-597 | YoloTrackServiceTest·AutolabelOnlineServiceTest 음수 | |
| TC-LABEL-17 | 좌표 검증: 0 허용(경계) | PASS | [실동작] `[[0,0],[10,10]]` → 200 저장, DB `[[0.0,0.0],[10.0,10.0]]`. [정적] :592-597 | LabelServiceKeypointTest `v0_미표기_점_x_y_0_허용` | |
| TC-LABEL-18 | 신규 라벨 >1000점 차단(DoS) | PASS | [실동작] 1001점 신규 → **400** "라벨당 좌표 개수 초과 (최대 1000 점)"; **경계 1000점은 200 통과**(DB 1000점 그대로). [정적] :584-587 | PolygonCapTest `신규_초과_폴리곤…400_거부` | 경계 양측 확인 |
| TC-LABEL-19 | 기존 라벨 >1000점 simplify | PASS | [실동작] id 지정 1001점 → **200**(400 아님), DB 저장 점수 **51**(≤1000). [정적] :255, 722-732 capPoints/Douglas-Peucker | PolygonCapTest(3) | |
| TC-LABEL-20 | labelId 미존재 | PASS | [실동작] labelId=99999 → **404** "라벨 마스터를 찾을 수 없습니다: labelId=99999". [정적] :553-558 | — | |
| TC-LABEL-21 | labelId USE_YN='N' 사용중지 | PASS | [실동작] labelId=7(animal, `use_yn='N'`) → **409** "사용 중지된 라벨입니다: labelId=7". [정적] :559-562 | — | 부작용은 C-ISSUE-25 참조 |
| TC-LABEL-22 | Mass Assignment: autoLblYn 무시 | PASS | [실동작] 요청에 `"autoLblYn":"Y"` 포함 저장 → 응답 `autoLblYn:"N"`. **추가 반증**: `ls_data_lbl` 에 `auto_lbl_yn` **컬럼 자체가 없음**(실제 컬럼 10개 실측) — 값이 AI_INFO row 존재 여부에서 파생되어 구조적으로 바인딩 불가. [정적] :228-234 | — | 방어가 스펙보다 강함 |
| TC-LABEL-23 | provenance AUTO_YOLO→AI_INFO | PASS | [실동작] `source:AUTO_YOLO, confScore:0.87, algorithm:YOLO` → `ls_data_lbl_ai_info` 행 `lbl_src_cd=YOLO, conf_score=0.87000` 적재, 응답 `autoLblYn:"Y"`. [정적] :310-317 | — | |
| TC-LABEL-24 | provenance 화이트리스트 위반 | PASS | [실동작] `source:"HACK"` → **400** `source 는 MANUAL/AUTO_YOLO/AUTO_SAM2 중 하나`. [정적] LabelItemDto.java:44-45 | — | |
| TC-LABEL-25 | confScore 범위 초과 | PASS | [실동작] `confScore:1.5` → **400** `confScore 는 1.0 이하`. [정적] LabelItemDto.java:46-47 | — | |
| TC-LABEL-26 | items >500 상한(DoS) | PASS | [실동작] 501건 → **400** "한 번에 처리 가능한 라벨 수 초과 (최대 500)". [정적] LabelBulkUpsertRequest.java:15 | — | |
| TC-LABEL-27 | 무변경 재저장 이력 미발행(R7) | PASS | [실동작] 동일 3건 그대로 재전송 → 200, `ls_data_lbl_hstry` count **4→4 불변**. [정적] :301-307 | FullReplaceIT `noChangeNoHistory`, `noChangeResaveNoHistoryNoNotification` | |
| TC-LABEL-28 | R7 정규화 비교(5 vs 5.0) | PASS | [실동작] `[20,20]`→`[20.0,20.0]` 표현만 변경 재전송 → 이력 **4→4 불변**(무변경 판정). [정적] :470-508 | — | |
| TC-LABEL-29 | R7 레거시 3포맷 흡수 | PASS | [정적] :491-508 `LabelPointSerializer.fromJson` 3포맷 + raw 폴백 | FullReplaceIT `flatLegacyPointsNoChangeResave`, `objectArrayLegacyPointsNoChangeResave` | 레거시 데이터 부재로 실동작 미수행 |
| TC-LABEL-30 | R7 손상 JSON fail-safe | PARTIAL | [정적] :479-483 `normalizePoints` 양쪽 null → `false`(변경됨) fail-safe 확인. **전용 테스트 부재**(FullReplaceIT `corruptDiffJsonGracefulFallback` 은 이력 diff JSON 손상이지 `pointCn` 손상이 아님) | (간접만) | C-ISSUE-23 |
| TC-LABEL-31 | TASK_MODIFIED: APPROVED만 | PARTIAL | [실동작] APPROVED 영상(rawSn=13/srcSn=359) 라벨 편집 **200 허용** 확인. 그러나 `CONTROL_NOTIFY_ENABLED=false` 로 `ControlNotifyEventListener` 빈이 미생성(`@ConditionalOnProperty`)이라 **발행 자체를 런타임 관측 불가**. [정적] :365-371 | LabelServiceTaskModifiedGuardTest `검수완료_발행`(rawSn/srcSn/changeType 검증) | C-ISSUE-24(환경 제약) |
| TC-LABEL-32 | TASK_MODIFIED: 검수전 미발행 | PASS | [정적] :365-371 `isReviewApproved` 게이트 | LabelServiceTaskModifiedGuardTest `검수전_미발행`(ASSIGNED) + `상태없음_미발행` | |
| TC-LABEL-33 | 이력 조회 IDOR | PASS | [실동작] WORKER→`GET /v1/frames/432/label-history` **403**. [정적] :404-405 | LabelHistoryControllerTest `타인_프레임_히스토리_조회시_403` | |
| TC-LABEL-34 | 이력 페이지 상한 클램프 | PASS | [실동작] `?size=500` → 응답 `size=100`. [정적] :516-520 | — | |
| TC-LABEL-35 | 이력 임의 sort 무시(500 차단) | PASS | [실동작] `?sort=badfield,desc` → **200**(500 아님), `regDt DESC` 서버 고정 정렬로 최신 이벤트 반환. [정적] :510-520 | — | |
| TC-LABEL-36 | 동시 저장 델타 TOCTOU | 확인필요 | [실동작] **lost update 재현됨** — 백엔드 로그 `existing=0 saved=1 deleted=0`(A 저장) 직후 `existing=1 saved=1 **deleted=1**`(B 저장) → A 가 만든 라벨이 오류·경고 없이 물리 삭제. 병렬 동시 PUT(같은 순간)에선 양쪽 `deleted=0` 로 우연히 살아남음. [정적] :331-361 (`@Version`/비관락 없음, 주석이 "단일 WORKER 배정" 가정 명시) | — | **C-ISSUE-21** / UNCERTAINTIES #9 |
| TC-LABEL-37 | 삭제 감사 로깅 PII 미출력 | PASS | [실동작] 로그 실측 `[Label] bulkUpsert srcSn=1 actor=2001 existing=3 saved=0 deleted=3` — 카운트·식별자만, 좌표/라벨명/토큰 미출력. [정적] :359-361 | — | |
| TC-LABEL-40 | 마스터 생성 정상 | PASS | [실동작] `POST /v1/manage/labels`(REVIEWER) → **201**, `labelId=497` 반환. [정적] LabelMasterService.java:73-95 | LabelMasterServiceTest `create_정상` 외 | |
| TC-LABEL-41 | 마스터 근사중복(대소문+공백) | PASS | [실동작] 활성 `car` 존재 상태에서 `" Car "` 생성 → **409** "이미 사용 중인 라벨 이름입니다." [정적] :76-78 + LsLabelRepository `existsActiveByNormalizedName`(LOWER(TRIM)) | LabelMasterServiceTest `대소문자_근사중복`, `공백_근사중복`, `앞뒤공백_trim_저장` | |
| TC-LABEL-42 | 마스터 동시 생성 DB유니크 | PASS | [실동작] 동일 이름 **5병렬 POST → 201 1건 + 409 4건**. DB 인덱스 실존 확인: `uk_ls_label_nm_ci UNIQUE (lower(btrim(lbl_nm))) WHERE use_yn='Y'`(V120). [정적] :70-78 | — | 앱 선판정+DB유니크 이중가드 실증 |
| TC-LABEL-43 | dtctTypeCd allowlist 위반 | PASS | [실동작] `dtctTypeCd:"human"` → **400** "지원하지 않는 검출 클래스입니다." [정적] :126-135 `CocoClasses.isValid` | LabelMasterServiceTest `create_미지원_COCO_400` | |
| TC-LABEL-44 | dtctTypeCd 활성 중복 매핑 | PASS | [실동작] `person`(lbl_id=1 이 이미 보유) 재매핑 → **409** "이미 사용 중인 검출 클래스 매핑입니다." [정적] :81-83 | LabelMasterServiceTest `create_중복_COCO매핑_409`, `update_중복_COCO매핑_409` | |
| TC-LABEL-45 | dtctTypeCd 미매핑 해제 허용 | PASS | [실동작] `dtctTypeCd:""` 생성 → 201, 응답/DB `dtctTypeCd=null`. [정적] :127-129 | LabelMasterServiceTest `create_매핑없음_성공` | |
| TC-LABEL-46 | color 소문자 hex 거부 | PASS | [실동작] `#ffffff` → **400** "color 는 대문자 hex (#RRGGBB) 형식". [정적] LabelMasterRequest.java:33 | — | |
| TC-LABEL-47 | type allowlist | PASS | [실동작] `type:"FOO"` → **400** `BBOX/POLYGON/POINT/SKELETON 중 하나`. [정적] LabelMasterRequest.java:37 | LabelMasterServiceTest SKELETON/기존타입 회귀 | |
| TC-LABEL-48 | sortNo 음수 | PASS | [실동작] `sortNo:-1` → **400** "sortNo 는 0 이상". [정적] LabelMasterRequest.java:40 | — | |
| TC-LABEL-49 | 마스터 수정 미존재 | PASS | [실동작] `PUT /v1/manage/labels/999999` → **404** "라벨을 찾을 수 없습니다." [정적] :100-101 | LabelMasterServiceTest `update_없는라벨_NOT_FOUND` | |
| TC-LABEL-50 | 마스터 수정 근사중복(자기제외) | PASS | [실동작] 497→`"CAR"`(활성 `car` 충돌) **409**; 동일 건을 자기 이름 `TCVERIFY-A` 유지 수정은 **200** (자기제외 정상). [정적] :104-106 | LabelMasterServiceTest `update_이름중복_CONFLICT`, `update_자기자신_중복검사_제외` | 양방향 확인 |
| TC-LABEL-51 | 마스터 삭제 soft delete | PASS | [실동작] `DELETE /v1/manage/labels/497` → **204**, DB `use_yn='N'`(행 잔존=hard delete 아님). 추가: soft delete 후 **동일 이름 재생성 201 허용**(부분 유니크 `WHERE use_yn='Y'` 와 정합). [정적] :189-195 | LabelMasterServiceTest `delete_soft`, `create_soft_delete된_이름…` | |

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [C-ISSUE-21] TC-LABEL-36 — 라벨 full-replace 동시 저장 lost update (stale 작업본이 타인 라벨을 침묵 삭제)
- **심각도**: HIGH (데이터 소실 · 무경고)
- **기대 동작(기대효과)**: 두 사용자(또는 두 브라우저 탭)가 같은 프레임을 편집할 때, 나중 저장이 앞선 저장의 라벨을 소리 없이 지우면 안 된다. 최소한 충돌 감지(409/낙관적 락) 또는 병합 정책이 있어야 한다.
- **현재 동작**: `LabelService.bulkUpsert` 는 `existing = labelRepository.findBySrcSn(srcSn)`(:265) 를 읽고 요청의 non-null id 집합(`reqIds`)에 **없는 모든 기존 라벨을 물리 삭제**한다(:334-352). `@Version`·비관적 락·ETag 어느 것도 없고, 코드 주석(:331-333)이 방어를 "프레임은 단일 WORKER 배정이라 구조적으로 제한"이라는 **가정**에 의존한다고 명시한다.
  실측 로그(2026-07-25 11:05:54, srcSn=1):
  ```
  11:05:54.770 [Label] bulkUpsert srcSn=1 actor=2001 existing=0 saved=1 deleted=0   ← A 가 라벨 X 저장
  11:05:54.796 [Label] bulkUpsert srcSn=1 actor=2001 existing=1 saved=1 deleted=1   ← B(stale 작업본) 저장 → X 삭제
  ```
  B 는 A 의 저장을 모르는 상태(빈 작업본)에서 자기 라벨만 보냈고, 응답은 **200 정상**이었으며 A 의 라벨은 사라졌다. 사용자에게 어떤 경고도 없다.
  **가정이 깨지는 실제 경로(확인됨)**: ①`LabelAccessGuard` 는 REVIEWER 를 무조건 통과시키므로(LabelAccessGuard.java:45-47) REVIEWER 와 배정 WORKER 가 **동시에 같은 프레임을 편집 가능** — 실제로 REVIEWER 토큰과 WORKER 토큰으로 같은 srcSn 을 동시 PUT 해 둘 다 200 을 받았다. ②동일 WORKER 의 다중 탭. ③재배정 직후 이전 담당자의 열린 화면.
- **재현/확인 경로**:
  ```bash
  B=http://localhost:18081/api; WK=<WORKER 토큰>
  # 1) A 탭이 라벨 X 저장
  curl -X PUT $B/v1/frames/1/labels -H "Authorization: Bearer $WK" -H 'Content-Type: application/json' \
    -d '{"items":[{"id":null,"lblTypeCd":"BBOX","labelId":1,"label":"tabA-X","points":[[1,1],[11,11]]}]}'
  # 2) B 탭(A 저장 전에 화면을 열어둔 stale 작업본)이 자기 라벨만 저장
  curl -X PUT $B/v1/frames/1/labels -H "Authorization: Bearer $WK" -H 'Content-Type: application/json' \
    -d '{"items":[{"id":null,"lblTypeCd":"BBOX","labelId":2,"label":"tabB-Y","points":[[50,50],[60,60]]}]}'
  # 3) 확인 — tabA-X 는 사라져 있음
  docker exec -e PGPASSWORD=<pw> klid-postgres psql -U klid_user -d klid_system \
    -c "select lbl_sn,lbl_nm from ls_data_lbl where src_sn=1;"
  docker logs klid-backend --since 5m | grep '\[Label\] bulkUpsert'   # deleted=1 로 소실 확인
  ```
- **영향**: CWE-362(Race Condition) / lost update. 라벨 작업 결과가 통보 없이 사라진다. 삭제 자체는 `LS_DATA_LBL_HSTRY` 에 before 스냅샷으로 남으므로(:339-345) **사후 복구 근거는 존재**하나, 사용자·UI 는 소실을 인지하지 못한다. 검수 승인(APPROVED) 전 단계에서 발생하면 `LS_LABEL_VERSION` 스냅샷도 아직 없어 안전망이 이력뿐이다.
- **수정 방향(제안)**: (a) `LS_DATA_SRC` 또는 프레임 라벨 세트에 낙관적 버전(요청에 `baseVersion`/ETag 동반, 불일치 시 409)을 도입, 또는 (b) 프레임 단위 편집 세션 락(기존 `ls_auth_work_lock` 의 `DATA_SRC_SN` 컬럼이 이미 존재하나 미사용), 또는 (c) 최소 조치로 `deleted>0` 이면서 요청이 기존 id 를 하나도 포함하지 않는 저장을 경고·확인 요구로 승격. **UNCERTAINTIES #9 는 "기대 동작 미확정" 이므로 정책 확정이 선행되어야 함 — 본 보고서는 사실만 기록.**

### [C-ISSUE-22] 좌표 이미지 경계 초과 저장 무검증 (UNCERTAINTIES #10)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 프레임 이미지 해상도를 벗어난 좌표(예: 640×480 프레임에 x=999999)는 학습데이터로 무의미하므로 저장 시점에 거부되거나 클램프되어야 한다.
- **현재 동작**: `validatePoints`(LabelService.java:575-599)는 **빈 배열·[x,y] 형식·음수·개수 상한**만 검증하고 **상한 좌표(이미지 폭/높이) 검증이 전혀 없다**. 실측:
  ```
  PUT /v1/frames/1/labels {"points":[[999999,888888],[1000000,999000]]}
  → HTTP 200, DB point_cn = [[999999.0,888888.0],[1000000.0,999000.0]] 그대로 저장
  ```
  `LabelItemDto` javadoc(LabelItemDto.java:19)은 "좌표 검증은 Service 에서 — 음수/**이미지 경계 초과** 차단"이라고 적혀 있어 **주석과 구현이 불일치**한다.
- **재현/확인 경로**: 위 PUT 그대로. (본 시험에서 저장한 초과 좌표 라벨은 후속 정리 단계에서 삭제 완료 — 잔존 없음)
- **영향**: CWE-20(입력 검증). 캔버스 클램프가 유일한 방어선이라 API 직접 호출·FE 버그·오토라벨 좌표 변환 오류가 그대로 학습데이터·export JSON(COCO bbox)에 유입된다. 보안 침해보다는 **데이터 품질 오염**이 주 리스크.
- **수정 방향(제안)**: `LS_DATA_SRC` 에 프레임 해상도가 있으면 그 값으로 상한 검증, 없으면 영상 해상도(`LS_DATA_RAW`) 기준 검증. 정책이 "클램프 허용"이면 최소한 위 javadoc 문구를 실제 구현에 맞게 정정. **UNCERTAINTIES #10 미확정이므로 사실만 기록.**

### [C-ISSUE-23] TC-LABEL-30 — R7 손상 좌표 JSON fail-safe 전용 테스트 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: `pointCn` 이 파싱 불가한 손상값일 때 `pointsEqual` 이 '변경됨'으로 판정해 이력이 유실되지 않아야 하고, 그 동작이 회귀 테스트로 고정되어야 한다.
- **현재 동작**: 구현(LabelService.java:479-483)은 `normalizePoints` 가 3포맷 + raw 폴백 모두 실패하면 `null` → `false`(변경됨) 반환으로 fail-safe 가 **정상 구현**되어 있다. 그러나 `backend/src/test/**` 전수 grep 결과 `pointCn` 손상 케이스를 직접 겨냥한 테스트가 없다. 유사 이름의 `LabelServiceFullReplaceIntegrationTest:277 corruptDiffJsonGracefulFallback` 은 **이력 diff JSON(`CHG_DTL_CN`) 손상** 케이스로 대상이 다르다.
- **재현/확인 경로**: `grep -rn "pointsEqual\|normalizePoints" backend/src/test` → 0건.
- **영향**: 회귀 위험만. 현재 동작 결함 아님.
- **수정 방향(제안)**: `LabelServiceFullReplaceIntegrationTest` 에 `pointCn` 을 손상값(`"{{{"`)으로 덮어쓴 뒤 무변경 재저장 시 UPDATED 이력이 남는지 확인하는 케이스 1건 추가.

### [C-ISSUE-24] TC-LABEL-31 — TASK_MODIFIED 발행 런타임 관측 불가 (로컬 환경 제약)
- **심각도**: LOW (환경 제약 — 코드 결함 아님)
- **기대 동작(기대효과)**: APPROVED 영상의 라벨을 수정하면 `TaskModifiedEvent(LABEL_UPDATED)` 가 발행되고 디바운스를 거쳐 관제 outbound 통지로 이어져야 한다.
- **현재 동작**: 실동작으로 확인한 것은 **APPROVED 영상 라벨 편집이 차단되지 않고 200 으로 허용된다**는 사실까지다(rawSn=13/srcSn=359). 발행 여부는 `klid-backend` 컨테이너 env `CONTROL_NOTIFY_ENABLED=false` 이고 `ControlNotifyEventListener` 가 `@ConditionalOnProperty(name="authoring.control-notify.enabled", havingValue="true")`(ControlNotifyEventListener.java:21) 라 **리스너 빈 자체가 생성되지 않아** 로그·DB 어디에도 흔적이 남지 않는다. 발행 지점(LabelService.java:365-371)과 게이트(`isReviewApproved`)는 정적으로 정상이며, `LabelServiceTaskModifiedGuardTest:143 검수완료_발행` 이 rawSn/srcSn/changeType 을 단위 수준에서 검증한다.
- **재현/확인 경로**: `docker exec klid-backend env | grep CONTROL_NOTIFY_ENABLED` → `false`.
- **영향**: 검증 커버리지 공백. self-fill 결함 아님(값을 만들어낸 것이 아니라 기능이 설정으로 비활성).
- **수정 방향(제안)**: `CONTROL_NOTIFY_ENABLED=true` + 관제 inbound 를 mock-server 로 스텁한 별도 프로파일에서 재검증. (본 임무는 코드·설정 수정 금지라 미수행)

### [C-ISSUE-25] 사용 중 마스터 라벨을 soft delete 하면 해당 프레임의 라벨 저장이 전면 차단됨
- **심각도**: MEDIUM (참조 무결성 · 작업 차단 — 반증 시도 중 발견, 원 TC 목록 외)
- **기대 동작(기대효과)**: 마스터 라벨 soft delete 는 "신규 사용 중지"를 뜻해야 하며, 이미 그 라벨을 참조하는 기존 프레임의 편집·저장까지 막아서는 안 된다(막는다면 최소한 명시적 정책이어야 한다).
- **현재 동작**: `validateAndLoadLabels`(LabelService.java:541-564)는 요청 items 의 **모든** `labelId` 에 대해 `USE_YN='N'` 이면 409 를 던진다. 저장 계약이 **full-replace(프레임 전체 세트 전송)** 이므로, 프레임에 soft-delete 된 마스터를 참조하는 라벨이 1건이라도 있으면 그 프레임의 **모든 저장이 409 로 막힌다** — 그 라벨을 지우지 않는 한 다른 라벨 수정도 불가능하다. 실 데이터에 해당 상황이 이미 존재한다:
  ```
  ls_label:    lbl_id=248 'pose-skeleton'  use_yn='N'   (soft delete 됨)
  ls_data_lbl: lbl_sn=29  src_sn=11  lbl_type_cd=SKELETON  lbl_id=248  (참조 잔존)
  ```
  `USE_YN='N'` 라벨이 409 를 유발한다는 것은 TC-LABEL-21 에서 `labelId=7`(animal, `use_yn='N'`)로 **실동작 확증**했다.
- **재현/확인 경로**: (파괴 위험이 있어 srcSn=11 실저장은 **수행하지 않음** — 성공 시 rawSn=11 의 라벨 54건이 full-replace 로 손상될 수 있어 의도적으로 회피)
  ```sql
  -- 상황 확인 (읽기 전용)
  select l.lbl_sn, l.src_sn, l.lbl_id, m.lbl_nm, m.use_yn
    from ls_data_lbl l join ls_label m on m.lbl_id = l.lbl_id
   where m.use_yn = 'N';
  ```
  기전 확증(무해): `PUT /v1/frames/1/labels` 에 `labelId:7` 포함 → 409 "사용 중지된 라벨입니다: labelId=7".
- **영향**: 운영자가 사용 중인 라벨을 정리(soft delete)하는 순간 해당 라벨이 붙은 모든 프레임의 라벨링 작업이 잠긴다. 작업자는 원인을 알 수 없는 409 를 만난다.
- **수정 방향(제안)**: `USE_YN='N'` 검증을 **신규 부여(id==null)에만** 적용하고, 기존 라벨의 labelId 유지(변경 없음)는 통과시키는 방향. 또는 마스터 삭제 시 참조 건수를 사전 안내하고 대체 라벨 지정을 요구.

## UNCERTAINTIES 확인 결과

- **#9 동시저장 race (라벨 full-replace)**: **lost update 가 실제로 발생함을 실동작으로 재현**했다(C-ISSUE-21). `@Version`·비관락·ETag 전부 부재(LabelService.java:331-333 주석이 부재를 명시). "단일 WORKER 배정" 가정은 **깨진다** — `LabelAccessGuard` 가 REVIEWER 를 무조건 통과시키므로(LabelAccessGuard.java:45-47) REVIEWER+WORKER 동시 편집이 가능하고, 실제로 두 토큰으로 같은 프레임에 동시 PUT 해 둘 다 200 을 받았다. 다중 탭·재배정 직후도 동일. 다만 **삭제 before 스냅샷이 `LS_DATA_LBL_HSTRY` 에 남아 사후 복구 근거는 존재**한다. 기대 동작(충돌 시 409 냐 병합이냐 last-write-wins 수용이냐)이 미확정이라 판정은 `확인필요`.
- **#10 좌표 이미지 경계 초과**: **경계 초과 좌표가 검증 없이 그대로 저장됨을 실동작으로 확인**했다(C-ISSUE-22). `[[999999,888888],[1000000,999000]]` → HTTP 200 + DB 원값 저장. 저장 경로에는 음수·[x,y] 형식·개수 상한 검증만 존재하고 상한 좌표 검증은 코드에 없다. `LabelItemDto.java:19` javadoc 은 "이미지 경계 초과 차단"을 주장해 **문서-구현 불일치**가 함께 확인되었다. 정책 미확정으로 사실만 기록.

## 추가 확인 사항 (반증 시도 결과)

- **알려진 미해결 HIGH(대소문자 근사중복 → `findLabelIdByName` 2행 → 오토라벨 배치 크래시)**: **해소된 것으로 확인**. ①`findLabelIdByName` 은 코드베이스에 더 이상 존재하지 않으며 `findLabelIdByDtctType`(COCO 매핑 축, LabelMasterService.java:151-157)로 대체됨. ②이름 기반 잔여 경로(`findByLabelNmIgnoreCaseAndUseYn`, LsLabelRepository.java:67-70)는 `PageRequest.of(0,1)` 방어적 top-1 이라 다중 결과에도 예외를 던지지 않음. ③DB 에 부분 유니크 인덱스 2종 실존 확인:
  `uk_ls_label_nm_ci UNIQUE (lower(btrim(lbl_nm))) WHERE use_yn='Y'` (V120),
  `uk_ls_label_dtct_type UNIQUE (dtct_type_cd) WHERE use_yn='Y' AND dtct_type_cd IS NOT NULL` (V129).
  ④활성 중복 매핑 실측 0건(`group by dtct_type_cd having count(*)>1` → 0 rows). ⑤5병렬 동일 이름 생성에서 정확히 1건만 201(TC-42).
- **채널 격리**: PORTAL_USER 토큰으로 `GET /v1/frames/1/labels` → **403** (내부 채널 전용 확인). 반면 `GET /v1/manage/labels` 는 **200**(TC-53 설계대로).
- **Mass Assignment 추가 반증**: `ls_data_lbl` 실제 컬럼 = `lbl_sn, src_sn, lbl_type_cd, lbl_nm, point_cn, trck_id, reg_user_no, reg_dt, mdfcn_dt, lbl_id` — `auto_lbl_yn` 컬럼 자체가 없어 요청으로 오염시킬 대상이 구조적으로 존재하지 않음.
- **`clearAutomatically` 사용 여부(PII 방어 무력화 우려)**: full-replace 삭제 경로는 `attrValRepository.deleteByLblSnIn` → `aiInfoRepository.deleteByDataLblSnIn` → `labelRepository.deleteAllByIdInBatch`(LabelService.java:349-351) 를 사용하며, 삭제 후 재조회가 없어 stale 1차 캐시 리스크 없음. 실동작에서도 삭제 직후 응답·DB 가 일치했다.
- **근거 라인 드리프트**: 검증한 49건의 `근거(file:line)` 전부 실제 코드 위치와 일치(±2행 이내). **드리프트 0건**.
- **self-fill 결함**: 본 구간에서 외부 응답 없이 값을 자체 생성해 실측값처럼 보이게 한 지점 **0건**. `confScore=0.87`·`lbl_src_cd=YOLO` 는 요청 provenance 를 그대로 보존한 값이고, `autoLblYn` 은 AI_INFO row 존재에서 파생되는 정당한 계산값이다.

## 요약

- 총 **49건** / PASS **46** / FAIL **0** / PARTIAL **2**(TC-30, TC-31) / BLOCKED **0** / N/A **0** / 확인필요 **1**(TC-36)
- 근거 라인 드리프트: **0건** / self-fill 결함: **0건**
- 신규 이슈 **5건**: C-ISSUE-21(HIGH, lost update 재현) · C-ISSUE-22(MEDIUM, 좌표 경계 무검증) · C-ISSUE-23(LOW, 테스트 공백) · C-ISSUE-24(LOW, 환경 제약) · C-ISSUE-25(MEDIUM, soft delete 참조 차단 — TC 목록 외 반증 발견)
- 실동작 검증 비율: 49건 중 **44건이 실제 HTTP 요청 + DB/로그 조회**로 확인됨(TC-29/30/32 는 정적+기존 테스트, TC-31 은 부분 실동작)
