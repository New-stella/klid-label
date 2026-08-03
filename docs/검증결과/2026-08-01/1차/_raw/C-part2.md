# C 클러스터 검증 결과 — part2 (TC-LABEL 라벨 CRUD 기본: TC-LABEL-01~46)

- **담당 범위**: `docs/test-cases/C-marking-labeling.md` `## C-2. TC-LABEL` 섹션 앞부분 (파일 라인 73~120)
- **대상 케이스**: TC-LABEL-01 ~ 37, 40 ~ 46 = **44건** (표에 38·39 행은 존재하지 않음 — 카탈로그 번호 결번)
- **검증 커밋**: `56d30478` (V158) · 워크트리 `qa-0801`
- **검증 일시**: 2026-08-01 23:10~23:25 KST
- **환경**: backend `localhost:18081/api`(UP) · mock-server `:9400`(ok) · frontend `:13000`(200) · DB `klid-postgres/klid_system/public`
- **방식**: **실동작 우선** — 실 스택에 curl 요청 + DB 실측 + backend 컨테이너 로그 대조. 정적 대조로 근거 file:line 확인.

> ⚠ **동시 검증 간섭 기록**: 검증 도중(23:18:18) 다른 병렬 에이전트가 `raw_sn=27` 에 비식별 신고를 접수해
> 작업락 + `DE_IDENT_YN='F'` 가 걸렸다. 이후 테스트는 `raw_sn=34`(src 313/314, REVIEWER 토큰)로 이전해 수행했다.
> 판정에는 영향 없음(간섭 전 완료 케이스는 raw 27 기준, 이후는 raw 34 기준으로 각각 실측).

---

## 판정 요약

| 판정 | 건수 |
|------|-----:|
| PASS | 43 |
| PARTIAL | 1 |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **44** |

신규 이슈: **C-ISSUE-21** 1건(MEDIUM). 근거 드리프트: 3건.

---

## 케이스별 판정

| ID | 케이스명 | 판정 | 근거 확인 | 실측 근거 |
|---|---|:--:|---|---|
| TC-LABEL-01 | 프레임 라벨 조회 정상 | PASS | [실동작] | `GET /v1/frames/301/labels` (WORKER 2001, raw27) → 200. 응답 `{srcSn:301, videoId:27, frameImageType:"DEID", lockSttsCd:null, labelVersion:1, siblings:list[5], items:list[2]}`. siblings 는 `{srcSn,frameNo,hasLabel}` 형태로 반환(`hasLabel:true`). aiInfo enrich(`autoLblYn/confScore/lblSrcCd`)·lsLabel enrich(`labelName/color`) 필드 모두 응답에 존재. `labelVersion` 포함 확인. 정적: `LabelService.java:187-213` 일치 |
| TC-LABEL-02 | 조회 IDOR: 타 영상 프레임 | PASS | [실동작] | WORKER 2001 → `GET /v1/frames/75/labels`(raw17, 2002 배정) → **403** `{"errorCode":"FORBIDDEN","message":"본인에게 배정되지 않은 영상입니다."}`. 정적 `LabelAccessGuard.java:62-69` 일치. **추가 반증**: 같은 프레임에 `PUT`(쓰기)도 403 — 조회만 막고 저장이 새는 비대칭 없음 |
| TC-LABEL-03 | 조회: 미인증 | PASS | [실동작] | 토큰 없이 `GET /v1/frames/301/labels` → **401** `{"errorCode":"UNAUTHORIZED"}`. 정적 `LabelAccessGuard.java:48-50` 일치 |
| TC-LABEL-04 | 조회: 프레임 미존재 | PASS | [실동작] | `GET /v1/frames/999999/labels` → **404** `"프레임을 찾을 수 없습니다."`. `srcSn=abc`(비정수) → 400 `"파라미터 형식이 올바르지 않습니다: srcSn"`(500 아님). srcSn=null 방어는 `LabelAccessGuard.java:54-58` 정적 확인 |
| TC-LABEL-05 | frameImageType RAW(REVIEWER+raw) | PASS | [실동작] | REVIEWER + `?raw=true` → `frameImageType="RAW"`. REVIEWER + raw 미지정 → `"DEID"`. 정적 `LabelService.java:229-234` 일치 |
| TC-LABEL-06 | frameImageType DEID(WORKER raw무시) | PASS | [실동작] | WORKER + `?raw=true` → `frameImageType="DEID"` (강제). 역할 기반 분기가 쿼리파라미터를 신뢰하지 않음 |
| TC-LABEL-07 | bulkUpsert 신규 INSERT(수동) | PASS | [실동작] | `PUT /v1/frames/303/labels` `{lblTypeCd:BBOX,label:"사람",points:[[10,10],[50,50]],source:"MANUAL"}` → 200. DB `ls_data_lbl` lbl_sn=588, `reg_user_no=2001`, `ls_data_lbl_ai_info` 0건 → 응답 `autoLblYn="N"`. `ls_data_lbl_hstry` #37 = `[{"lblSn":588,"kind":"ADDED","after":{...}}]`. `lbl_ver` 0→1 |
| TC-LABEL-08 | bulkUpsert 기존 UPDATE | PASS | [실동작] | id=588 좌표 `[[10,10],[50,50]]`→`[[11,11],[51,51]]` → 이력 #38 `{"kind":"UPDATED","before":{"pointCn":"[[10.0,10.0],[50.0,50.0]]"},"after":{"pointCn":"[[11.0,11.0],[51.0,51.0]]"}}` — before 스냅샷이 `updateUserContent` **전** 값으로 정확히 캡처됨 |
| TC-LABEL-09 | full-replace: 빠진 라벨 삭제 | PASS | [실동작] | 기존 2건(588,589) 중 588만 전송 → DB 에 588만 잔존, 589 **실삭제**. 이력 #39 `[{"lblSn":589,"kind":"DELETED","before":{...},"after":null}]`. 589 의 `ls_data_lbl_ai_info` 자식도 0건으로 동반 삭제 |
| TC-LABEL-10 | full-replace: 빈 items=전량 삭제 | PASS | [실동작] | `{"items":[]}` → 200, `select count(*) from ls_data_lbl where src_sn=303` = **0**. 이력 #40 DELETED + before 스냅샷. `lbl_ver` 3→4 |
| TC-LABEL-11 | 삭제 순서 FK고아 방지 | PASS | [실동작]+[정적] | 삭제 대상 588 에 **실 FK 자식** `ls_data_lbl_attr_val`(atrb_vl_id=2, `fk_ls_data_lbl_attr_lbl`) 을 사전 INSERT 후 `items:[]` 저장 → **200**(FK 위반 500 없음), 자식 0건. 정적 `LabelService.java:390-392` = `attrValRepository.deleteByLblSnIn` → `aiInfoRepository.deleteByDataLblSnIn` → `labelRepository.deleteAllByIdInBatch` 순서 확인 |
| TC-LABEL-12 | 작업락 시 저장 차단 | PASS | [실동작] | `ls_auth_work_lock` 에 `(RAW, raw_sn=27, LOCKED)` INSERT → PUT → **409** `"비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."`. 락 상태에서 **조회는 200 + `lockSttsCd:"LOCKED"`**(FE 사전 인지용, 정책 일치). RELEASED 로 되돌리자 저장 200 복귀 |
| TC-LABEL-13 | dedupById 동일 id 중복 | PASS | [실동작] | 같은 id 를 `label:"first"` / `label:"last"` 로 2회 전송 → 응답 items **1건**, 값은 `label:"last", points:[[3,3],[4,4]]` = last-value-wins. DB 도 1행 |
| TC-LABEL-14 | 좌표 검증: 빈 points | PASS | [실동작] | `points:[]` → 400 `"items[0].points: must not be empty"` — 기대대로 **DTO `@NotEmpty`(LabelItemDto.java:42)가 먼저 발화**하고 서비스 메시지(`LabelService.java:707-709`)는 도달하지 않음 |
| TC-LABEL-15 | 좌표 검증: [x,y] 형식 위반 | **PARTIAL** | [실동작] | 명시 입력(`pair size≠2`, 예 `[[1,2,3]]`)은 **400** `"좌표는 [x, y] 형태여야 합니다."` 로 정상. **그러나 형식검증 방어가 불완전** — size==2 이면서 원소가 null 인 `[[null,5],[10,10]]` 은 **500 INTERNAL_ERROR**(NPE). SKELETON 경로는 같은 입력을 400 으로 거부하므로 경로 간 비대칭. → **C-ISSUE-21** |
| TC-LABEL-16 | 좌표 검증: 음수 | PASS | [실동작] | `[[-1,5],[10,10]]` → 400 `"좌표는 0 이상이어야 합니다 (x=-1.0, y=5.0)"`. ★3(UNCERTAINTIES) 대로 **사용자 저장 경로는 거부(클램프 아님)** — AI 검출 clamp 정책과의 비대칭은 확정 정책이라 결함 미보고 |
| TC-LABEL-17 | 좌표 검증: 0 허용(경계) | PASS | [실동작] | `[[0,0],[10,10]]` → **200** 저장 성공(`labelVersion` 증가). 0 은 음수가 아니므로 통과 |
| TC-LABEL-18 | 신규 라벨 >1000점 차단(DoS) | PASS | [실동작] | 신규(id=null) POLYGON 1001점 → **400** `"라벨당 좌표 개수 초과 (최대 1000 점)"`. **경계 1000점은 200 저장 성공**(DB `point_cn` 길이 11801) — off-by-one 없음 |
| TC-LABEL-19 | 기존 라벨 >1000점 simplify | PASS | [실동작] | 기존 id 지정 + 1001점 → **400 아님, 200**. 응답 `points` **141점**, DB `point_cn` 길이 1611 → `capPoints`(Douglas-Peucker, `LabelService.java:838`·`:907-912`) 로 ≤1000 축약 확인 |
| TC-LABEL-20 | labelId 미존재 | PASS | [실동작] | `labelId:999999` → **404** `"라벨 마스터를 찾을 수 없습니다: labelId=999999"` |
| TC-LABEL-21 | labelId USE_YN='N' → 신규 부여에만 409 | PASS | [실동작] | 4분기 전수 실측(C-ISSUE-25 수정 검증): ①신규(id=null)에 비활성 `labelId:10` → **409** `"사용 중지된 라벨입니다"` ②**기존 라벨이 같은 비활성 labelId(10) 유지 → 200 통과**(프레임 저장 영구차단 회귀 없음) ③기존 라벨의 labelId 를 다른 비활성(11)로 **변경** → 409 ④**우회 시도**: 타 프레임(314)에 다른 프레임 라벨 id(594)+비활성 labelId(10) 전송 → 409(신규 취급). "id 만 붙이면 통과" 우회 불성립 |
| TC-LABEL-22 | Mass Assignment: autoLblYn 무시 | PASS | [실동작]+[정적] | 수동 라벨 UPDATE 요청에 `"autoLblYn":"Y"` 첨부 → 응답 `autoLblYn="N"`, `ls_data_lbl_ai_info` 0건. 정적: `ls_data_lbl` 스키마에 `auto_lbl_yn` 컬럼 **자체가 없음**(psql `\d` 확인), `grep -rn 'autoLblYn()' src/main/java` → `LabelService` 에서 요청값을 **읽는 코드 0건**(version 롤백 경로만 별도 DTO 사용). 구조적으로 바인딩 불가 |
| TC-LABEL-23 | provenance AUTO_YOLO→AI_INFO | PASS | [실동작] | 신규 `{source:"AUTO_YOLO",confScore:0.87,algorithm:"YOLO"}` → 응답 `autoLblYn="Y", confScore=0.87, lblSrcCd="YOLO"`. DB `ls_data_lbl_ai_info` 행 생성: `data_lbl_sn=589, data_raw_sn=27, data_src_sn=303, lbl_src_cd=YOLO, conf_score=0.87000, auto_lbl_yn=Y, reg_id=2001` |
| TC-LABEL-24 | provenance 화이트리스트 위반 | PASS | [실동작] | `source:"HACK"` → 400 `"items[0].source: source 는 MANUAL/AUTO_YOLO/AUTO_SAM2 중 하나여야 합니다."`(`LabelItemDto.java:44-45`) |
| TC-LABEL-25 | confScore 범위 초과 | PASS | [실동작] | `confScore:1.5` → 400 `"items[0].confScore: confScore 는 1.0 이하여야 합니다."`(`@DecimalMax(1.0)`) |
| TC-LABEL-26 | items >500 상한(DoS) | PASS | [실동작] | items 501건 → 400 `"items: 한 번에 처리 가능한 라벨 수 초과 (최대 500)"`. 근거 라인 드리프트 있음(아래 참조) |
| TC-LABEL-27 | 무변경 재저장 이력 미발행(R7) | PASS | [실동작] | 동일 좌표·라벨명 재전송 → 응답 `labelVersion` 9→**9**(미증가), `max(lbl_hstry_sn)` 45→**45**(이력 미생성). 로그도 `labelVersion=9->9` |
| TC-LABEL-28 | R7 정규화 비교(5 vs 5.0) | PASS | [실동작] | 요청은 정수 `[[3,3],[4,4]]`, 저장값은 `"[[3.0,3.0],[4.0,4.0]]"` — 표현만 다른데 **무변경 판정**(이력·버전 불변). `pointsEqual` 수치 정규화 동작 확인 |
| TC-LABEL-29 | R7 레거시 3포맷 흡수 | PASS | [실동작] | DB 값을 직접 레거시 포맷으로 교체 후 정규 포맷 동일값 재저장: ①**평탄** `[3,3,4,4]` → 이력 45→45(무변경) ②**객체배열** `[{"x":3,"y":3},{"x":4,"y":4}]` → 이력 45→45(무변경). `LabelPointSerializer.fromJson` 3포맷 흡수 실증 |
| TC-LABEL-30 | R7 손상 JSON fail-safe | PASS | [실동작] | DB `point_cn='{corrupt'` 로 교체 후 재저장 → 200 + 이력 45→**46**(= '변경됨' 처리, 이력 유실 없음). ⚠ 카탈로그 주석대로 **전용 회귀 테스트는 여전히 부재**(C-ISSUE-23 이월) — `grep` 결과 `pointsEqual`/`normalizePoints` 손상값 회귀 테스트 0건 (`LabelServiceFullReplaceIntegrationTest` 의 "손상된_diff_JSON…" 은 이력 **조회** diff 파싱 테스트로 별건) |
| TC-LABEL-31 | TASK_MODIFIED: APPROVED만 | PASS | [실동작] | APPROVED 영상(raw26, src296)에 라벨 추가 → `ls_mon_noti_acml` #31 `raw_sn=26, stts_cd=PENDING, export_rprcs_yn='Y', chg_dtl_cn={"frames":{"296":["LABEL_ADDED"]}}`. 이어서 삭제 저장 → 같은 행이 `["LABEL_ADDED","LABEL_DELETED"]` 로 누적 = **변경 종류별 발행 + 디바운스 합류 + `exportRegenerated=true`** 3요소 모두 실증 |
| TC-LABEL-32 | TASK_MODIFIED: 검수전 미발행 | PASS | [실동작] | ASSIGNED 영상(raw27) 에 10+회 저장 수행 — `ls_mon_noti_acml` 에 raw27 행 **0건**(전체 테이블에 raw26 1행뿐) |
| TC-LABEL-33 | 이력 조회 IDOR | PASS | [실동작] | WORKER 2001 → `GET /v1/frames/75/label-history` → **403** `"본인에게 배정되지 않은 영상입니다."` |
| TC-LABEL-34 | 이력 페이지 상한 클램프 | PASS | [실동작] | `?size=500` → 응답 `size=100`(클램프). `?size=0` 도 500 없이 200(기본 20) |
| TC-LABEL-35 | 이력 임의 sort 무시(500 차단) | PASS | [실동작] | `?sort=badfield,desc` → **200**(PropertyReferenceException 500 없음), 서버 고정 정렬(첫 행 `lblHstrySn=46` = 최신). **SQLi 반증**: `?sort=regDt;DROP TABLE ls_data_lbl--` → 200 + `ls_data_lbl` 83행 그대로 |
| TC-LABEL-36 | 동시 저장 lost update 차단 | PASS | [실동작] | ①stale 버전 첨부(`labelVersion=현재-1`) → **409** `"다른 사용자가 먼저 저장했습니다…"` + 로그 `[Label] stale label version rejected srcSn=303 requested=9 current=10` ②일치 버전 → 200 ③**미첨부(null) → 200**(하위호환 유지) ④**실 병렬 race**: 동일 baseVersion=3 으로 2세션 동시 PUT → A=409 / B=200, DB 에 `sessionB` 만 생성되고 기존 라벨(597) **삭제되지 않음**, `lbl_ver`=4. 침묵 삭제 폐기 확인 ⑤`labelVersion:-1` → 400(`@PositiveOrZero`) |
| TC-LABEL-37 | 삭제 감사 로깅 PII 미출력 | PASS | [실동작] | 대량 삭제 시 로그: `[Label] bulkUpsert srcSn=303 actor=2001 existing=2 saved=1 deleted=1 labelVersion=2->3` — **카운트 + 버전 + actor 번호만**, 좌표·라벨명·PII 미출력. `LabelService.java:408-409` 일치 |
| TC-LABEL-40 | 마스터 생성 정상 | PASS | [실동작] | `POST /v1/manage/labels` (REVIEWER) → **201** `{labelId:21,name:"qa-c2-lbl",color:"#AABBCC",type:"BBOX",sortNo:900,useYn:"Y",dtctTypeCd:null}`. WORKER 로 동일 요청 → 403 |
| TC-LABEL-41 | 마스터 근사중복(대소문+공백) | PASS | [실동작] | 활성 `"qa-c2-lbl"` 존재 상태에서 `" Qa-C2-Lbl "` 생성 → **409** `"이미 사용 중인 라벨 이름입니다."`(LOWER(TRIM) 정규화 비교) |
| TC-LABEL-42 | 마스터 동시 생성 DB유니크 | PASS | [실동작] | 동일 이름 `qa-c2-race` 로 **6요청 병렬 발사** → 201 **정확히 1건**, 409 5건. DB 에 1행만. 인덱스 실재 확인: `uk_ls_label_nm_ci UNIQUE, btree (lower(TRIM(BOTH FROM lbl_nm))) WHERE use_yn = 'Y'`(V120) |
| TC-LABEL-43 | dtctTypeCd allowlist 위반 | PASS | [실동작] | `dtctTypeCd:"human"` → **400** `"지원하지 않는 검출 클래스입니다."`(`CocoClasses` 80종 allowlist) |
| TC-LABEL-44 | dtctTypeCd 활성 중복 매핑 | PASS | [실동작] | `person` 은 lbl_id=1 에 이미 매핑 → 신규 라벨에 `person` 매핑 시 **409** `"이미 사용 중인 검출 클래스 매핑입니다."`. DB 이중가드 실재: `uk_ls_label_dtct_type UNIQUE btree (dtct_type_cd) WHERE use_yn='Y' AND dtct_type_cd IS NOT NULL` |
| TC-LABEL-45 | dtctTypeCd 미매핑 해제 허용 | PASS | [실동작] | `dtctTypeCd:""`(blank) 로 수정 → 200, 응답 `dtctTypeCd:null` 저장. 생성 시 미지정(null)도 `dtctTypeCd:null` |
| TC-LABEL-46 | color 소문자 hex 거부 | PASS | [실동작] | `"#ffffff"` → 400 `"color: color 는 대문자 hex (#RRGGBB) 형식이어야 합니다."`(`LabelMasterRequest.java:33` `@Pattern("^#[0-9A-F]{6}$")`) |

---

## 추가 반증 시도 (케이스 표에 없는 경계·우회 — 전부 방어 확인)

| 반증 시나리오 | 결과 | 비고 |
|---|---|---|
| PUT(쓰기) IDOR — 미배정 프레임 저장 | 403 | 조회만 막고 쓰기가 새는 비대칭 없음 |
| PORTAL_USER 로 라벨 조회 | 403 | `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` |
| 타 프레임 라벨 id 를 실은 full-replace | 원 프레임 라벨 **무손상** | src314 에 id=594(src313 소유) 전송 → 새 라벨 596 생성, 594 는 src313 에 그대로 잔존. 삭제 델타가 `findBySrcSn(srcSn)` 로 한정됨을 실증 |
| 과대 좌표 `1e308` | 400 | 경계 상한 검증(`이미지=320x240`)이 실제 동작 — `FrameBoundsResolver` 활성 |
| `label` 81자 / `lblTypeCd:"MASK"` / `items:null` | 각 400 | DTO 검증 정상 |
| 이력 `page=-1`, `size=0` | 200 | 500 없음 |
| 이력 sort SQL 인젝션 | 200 + 테이블 무손상 | 서버 고정 정렬로 원천 차단 |
| **소프트삭제 마스터(use_yn='N') 참조 라벨 조회** | `labelName`/`color` 정상 반환 | `resolveLsLabelMap` 은 `findAllById`(use_yn 무필터). 라벨 CRUD 응답은 **역사적 라벨명 보존**이 맞고, "미연결 표시" 규칙은 `LS_LABEL_PRESET_CODE`(프리셋) 전용이므로 **정책 위반 아님**. C-ISSUE-25(soft delete=신규 사용중지)와도 정합 → 결함 미보고 |

---

## 테스트 커버 대조 (`_raw/test-baseline.md`: backend 4,755 tests / 실패 0 / skip 5)

| TC | 커버 테스트 (`backend/src/test/java/kr/co/cudo/authoring/label/…`) |
|---|---|
| 01·05·06 | `LabelControllerTest#LabelController_라벨_조회_GET_정상` · `…frameImageType_WORKER는_DEID_응답` · `…REVIEWER_raw_true_쿼리시_frameImageType_RAW_응답` · `…WORKER_raw_true_쿼리는_무시되고_frameImageType_DEID` |
| 02·33 | `LabelControllerTest#LabelController_본인_배정_아닌_프레임_편집시_403` · `LabelServiceFullReplaceIntegrationTest#타인_배정_프레임_저장은_403` · `LabelHistoryControllerTest#타인_프레임_히스토리_조회시_403` |
| 07·08 | `LabelHistoryControllerTest#라벨_신규저장시_ADDED_저장이벤트가_기록된다` · `LabelServiceFullReplaceIntegrationTest#수정_객체는_이전값과_새값이_모두_기록된다` |
| 09·10·11 | `LabelServiceFullReplaceIntegrationTest#저장시_요청에_빠진_라벨은_실제_삭제된다` · `#삭제된_라벨의_속성값과_AI정보도_함께_제거된다` · `#기존라벨_전체삭제시_모두_삭제되고_DELETED만_기록되고_delCnt_N` · `#다건_라벨_동시삭제시_delCnt와_DB_고아가_정확하다` |
| 13 | `LabelServiceFullReplaceIntegrationTest#중복된_id가_오면_last_value_wins로_한번만_처리된다` |
| 16·18·19 | `LabelControllerTest#…좌표_음수_입력시_INVALID_INPUT_400` · `LabelServicePolygonCapTest`(3건) |
| 20·21 | `LabelControllerTest#…존재하지_않는_labelId_시_NOT_FOUND_404` · `LabelSaveGuardsIT#사용중지된_마스터를_참조하는_기존라벨이_있어도_프레임_저장이_가능함` · `#사용중지된_마스터를_신규로_부여하면_409` |
| 22·23·24·25 | `LabelControllerTest#…오토_라벨_수정시_AUTO_LBL_YN은_Y_유지` · `#온라인_오토라벨_저장시_AUTO_LBL_YN_Y와_신뢰도가_보존된다` · `#confScore가_범위밖_1_5이면_400` · `#provenance는_민감필드_role등을_바인딩하지_않는다` |
| 27·28·29 | `LabelServiceFullReplaceIntegrationTest#무변경_재저장시_이력도_통지도_생기지_않는다` · `#평탄포맷_레거시라벨_무변경_재저장시_이력_통지_없음` · `#객체배열포맷_레거시라벨_무변경_재저장시_이력_통지_없음` |
| 31·32 | `LabelServiceTaskModifiedGuardTest`(6건 전부 — ADDED/DELETED/UPDATED/혼합/미검수 2종) |
| 34·35 | `LabelHistoryControllerTest#히스토리_조회_페이징_기본size_20_최대100` · `#히스토리조회_임의_sort파라미터는_무시되고_최신순_고정정렬된다` |
| 36 | `LabelSaveGuardsIT#두_작업자가_동시에_라벨_저장시_stale_버전은_409_이고_앞선_라벨이_삭제되지_않음` · `#락_대기중_타_트랜잭션이_커밋해도_stale_저장은_409_로_거부되고_앞선_라벨이_살아남음` · `#버전_미첨부_요청은_기존대로_저장되어_하위호환이_유지됨` |
| 40~46 | `LabelMasterServiceTest`(27건) · `LabelMasterControllerTest`(11건) · `LabelNameCiUniqueMigrationIT`(7건, V120 인덱스 실재·CI 중복 거부·soft delete 제외) |
| **미커버** | **TC-15 null 원소 형식위반(→C-ISSUE-21)** · TC-30 `pointsEqual` 손상값 fail-safe(C-ISSUE-23 이월) · TC-37 감사 로깅 PII 미출력(전용 테스트 0건, 실동작으로만 확인) |

baseline 대비 실패 0건 — 위 커버 테스트는 모두 통과 상태.

---

## 근거 드리프트 (카탈로그 정합성)

| TC | 카탈로그 근거 | 실제 위치 | 성격 |
|---|---|---|---|
| TC-LABEL-22 | `LabelService.java:133-148, :436` | `LabelItemDto.java:43`(응답 전용 선언) + `LabelService.java` 전체에 `item.autoLblYn()` 참조 **0건**(구조적 미바인딩) | 인용 라인(`resolveAiInfoMap`)은 응답 `autoLblYn` **파생 경로**일 뿐 방어 지점이 아님 — 방어 근거로는 부정확 |
| TC-LABEL-26 | `LabelBulkUpsertRequest.java:26` | `LabelBulkUpsertRequest.java:24` (`@Size(max = 500…)`) | 2줄 오프셋(26 은 레코드 닫는 괄호) |
| TC-LABEL-43·45 | `LabelMasterService.java:122-134` | `LabelMasterService.java:126-135` (`validateAndNormalizeDtctType`) | 소폭 오프셋(122-125 는 javadoc) |

그 외 TC-01~21·23~25·27~37·40~42·44·46 의 `file:line` 은 **실제 코드 위치와 일치**(드리프트 없음).

카탈로그 구조 관찰: 표에 **TC-LABEL-38·39 행이 존재하지 않는다**(37 → 40 로 점프). 폐기 취소선 표기도 없어 의도적 결번인지 누락인지 불명 — 카탈로그 유지보수 시 확인 권장.

---

## 이슈

### [C-ISSUE-21] TC-LABEL-15 — 좌표 배열 원소가 null 이면 형식 검증을 통과해 NPE 500 (2-튜플 경로에만 방어 부재)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 좌표 **형식 위반은 전부 400(INVALID_INPUT)** 이어야 한다. `LabelService.validateSkeletonPoints` 는 이 위험을 코드 주석으로 명시하고 있다 — *"JSON-valid 하지만 원소가 null 인 경우(`[[10,20,null],...]`)는 Double→double 언박싱 NPE(→GlobalExceptionHandler catch-all 500)를 유발한다. 언박싱 전에 400 으로 fail-secure 거부하여 계약(형식 위반=400)과 일치시킨다."* 즉 **의도된 계약은 400** 이며, 이는 CWE-20 입력 방어선 + OWASP A10:2025(Mishandling of Exceptional Conditions, fail-secure) 요구이기도 하다.
- **현재 동작(이슈 내용)**: SKELETON 경로에만 null 원소 가드가 있고, **BBOX/POLYGON/SEGMENT/TRACK 이 타는 2-튜플 경로에는 없다.** `pair.size() != 2` 만 검사한 뒤 곧바로 언박싱한다.

  `backend/src/main/java/kr/co/cudo/authoring/label/service/LabelService.java:719-728`
  ```java
  for (List<Double> pair : points) {
      if (pair == null || pair.size() != 2) {
          throw new CustomException(ErrorCode.INVALID_INPUT, "좌표는 [x, y] 형태여야 합니다.");
      }
      double x = pair.get(0);   // ← pair.get(0) == null 이면 언박싱 NPE
      double y = pair.get(1);
      if (x < 0 || y < 0) { ... }
  ```
  대조군 — SKELETON 경로 `LabelService.java:805-811` 는 동일 상황을 400 으로 거부한다.

  **실동작 근거**
  ```
  PUT /api/v1/frames/313/labels
  {"items":[{"lblTypeCd":"BBOX","label":"n","points":[[null,5],[10,10]]}]}
  → 500 {"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}

  (대조) lblTypeCd:"SKELETON" + [[10,20,null], …x17]
  → 400 {"errorCode":"INVALID_INPUT","message":"키포인트 좌표에 null 원소가 있습니다."}
  ```
  backend 컨테이너 로그:
  ```
  2026-08-01 23:22:27.292 ERROR k.c.c.a.c.e.GlobalExceptionHandler - [Exception] unhandled exception
  java.lang.NullPointerException: Cannot invoke "java.lang.Double.doubleValue()"
      because the return value of "java.util.List.get(int)" is null
  ```
  신규(`id=null`)·기존(`id` 지정) **양쪽 경로 모두 500**(기존 경로도 `validatePoints` 를 거친다).
- **재현/확인 경로**
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
    -H 'Content-Type: application/json' \
    -d '{"sub":"1001","role":"REVIEWER","channel":"INTERNAL"}' -o /tmp/t.json; \
    python3 -c "import json;print(json.load(open('/tmp/t.json'))['data']['token'])")

  # {srcSn} 은 배정/REVIEWER 접근 가능한 아무 프레임 (예: 313)
  curl -i -X PUT http://localhost:18081/api/v1/frames/313/labels \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"items":[{"lblTypeCd":"BBOX","label":"n","points":[[null,5],[10,10]]}]}'
  # → HTTP/1.1 500, errorCode=INTERNAL_ERROR  (기대: 400 INVALID_INPUT)
  ```
- **영향**
  - **계약 위반**: API 규약상 입력 형식 오류는 400 인데 500 이 나가 FE 가 "서버 장애"로 오분류한다(재시도 유발 가능).
  - **CWE-20**(Improper Input Validation) / **CWE-248**(Uncaught Exception) / OWASP **A10:2025** fail-secure 위반. `points` 는 인증된 내부 채널(REVIEWER/WORKER)에서만 오므로 외부 익명 DoS 는 아니나, 단순 요청으로 반복 500 + ERROR 스택 로그를 유발할 수 있어 **로그 오염·알람 노이즈**로 이어진다(운영 ERROR 레벨은 알림 대상).
  - 응답 본문에 스택트레이스는 노출되지 않아 CWE-209 정보노출은 없음(`GlobalExceptionHandler` 가 일반 메시지로 마스킹).
  - 유입 경로: FE 캔버스 버그·부분 직렬화·외부 도구로 만든 라벨 임포트 등에서 실제로 발생 가능.
- **수정 방향(제안)** — ⚠ 구현하지 않음
  `LabelService.validatePoints`(`LabelService.java:719-728`)의 2-튜플 루프에서 **언박싱 전에 null 원소를 400 으로 거부**한다. SKELETON 경로(`:805-811`)와 동일한 메시지 체계·동일한 fail-secure 순서를 쓴다.
  ```java
  Double xBox = pair.get(0);
  Double yBox = pair.get(1);
  if (xBox == null || yBox == null) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "좌표에 null 원소가 있습니다.");
  }
  double x = xBox; double y = yBox;
  ```
  더 근본적으로는 두 경로가 같은 실수를 반복하지 않도록 **null-safe 언박싱 헬퍼 1개로 통합**하는 편이 낫다(현재는 SKELETON 만 고쳐진 비대칭이 그대로 남아 있다).
  회귀 테스트 제안: `LabelControllerTest` 또는 `LabelSaveGuardsIT` 에
  `"좌표_배열_원소가_null이면_400"`(BBOX/POLYGON 각 1건, 신규·기존 id 양 분기) 추가.
  ※ `validateWithinBounds`(`:756-763`)는 이미 null 을 `continue` 로 흘리므로 추가 수정 불필요.

---

## 미해소 이월 확인

| 이슈 | 상태 | 확인 근거 |
|---|---|---|
| **C-ISSUE-23** (R7 손상 JSON 회귀 테스트 부재) | **미해소 이월** | `grep -rln "손상\|corrupt\|malformed" src/test/java` 결과 중 `pointsEqual`/`normalizePoints` 손상 `point_cn` 을 다루는 테스트 0건. `LabelServiceFullReplaceIntegrationTest#손상된_diff_JSON이_있어도_조회는_200과_빈changes를_반환한다` 는 **이력 조회 diff 파싱** 테스트로 별건. 동작 자체는 이번에 실동작으로 정상 확인(TC-LABEL-30 PASS)이나 회귀 가드는 여전히 없음 |

---

## 검증 중 생성/정리한 테스트 데이터

기존 데이터는 변경하지 않았고, 검증용으로 만든 것은 아래와 같이 정리했다.

| 항목 | 처리 |
|---|---|
| `ls_data_lbl` src 313·314 테스트 라벨(594·596·597·598) | API `items:[]` 로 전량 삭제 완료 |
| `ls_label` 마스터 21(`qa-c2-lbl`)·22(`qa-c2-race`) | API DELETE(soft delete, `use_yn='N'`) 완료 |
| `ls_auth_work_lock` `lck_id='qa-c2-lock-test'`(raw27) | `lck_stts_cd='RELEASED'`, `rmv_rsn='QA_C2_CLEANUP'` 로 해제 완료 |
| `ls_data_lbl_attr_val` atrb_vl_id=2 (TC-11 용) | 대상 라벨 삭제 시 애플리케이션이 동반 삭제 |
| src 303 잔존 라벨 592(`chg32`) | **잔존** — raw27 이 타 에이전트의 비식별 신고로 작업락 상태라 API 정리 불가. `lbl_id` 는 null 로 원복 |
| src 296(raw26, APPROVED) | 라벨 추가 후 `items:[]` 로 원상복구(라벨 0건). 단 `ls_mon_noti_acml` #31 및 export 재생성 트리거는 발생함 |
