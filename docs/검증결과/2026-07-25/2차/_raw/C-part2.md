# C 클러스터 part2 (TC-LABEL-01~73) 2차 검증 결과

- 검증 시각: 2026-07-31 03:25~03:45 KST · backend `localhost:18081`(이미지 HEAD `ca3c712b` 재빌드본, pipeline-drive §서두)
- 스키마 `public` · 인증 `POST /v1/dev/tokens`(REVIEWER 1001 / WORKER 2001 / PORTAL_USER 3001)
- **컨테이너 재기동·재빌드·빌드/테스트 실행 0건. 소스/설정 파일 수정 0건**(본 문서 1개만 신규 작성)
- **rawSn 126 라벨 무변경**(조회만) · **rawSn 133 신고 OPEN 유지**(resolve 안 함) · 파생 129/130/131 무접촉

## 검증용 샌드박스 (파괴적 조작 격리)

D/E 클러스터 참조 데이터를 건드리지 않기 위해 아래를 사용했다.

| 용도 | 대상 | 착수 상태 | 종료 상태 |
|---|---|---|---|
| 라벨 CRUD·full-replace·버전·좌표 검증 | **srcSn 105** (rawSn **138**, 미배정, 실이미지 1920×1080, 라벨 0건) | 라벨 0 / `LBL_VER=0` | **라벨 0 으로 원복**(`LBL_VER` 는 증가 잔존) |
| 온라인 오토라벨(실검출) | **srcSn 106** (rawSn 138) | 라벨 10 | **라벨 10 (무변경 — 온라인 경로는 미저장)** |
| bounds 실측 불가 분기 | **srcSn 30** (rawSn 20006, 이미지 파일 부재) | 라벨 0 | **라벨 0 으로 원복** |
| IDOR 대상 | srcSn 105/rawSn 138 (WORKER 2001 미배정) | — | — |

**잔존 부산물(의도·무해)**: ①`LS_LABEL` `lblId=37 'ZZTEST-C2'` **USE_YN='N'**(soft delete — 활성 목록 비노출) ②`LS_LABEL_ATTR` `atrbId=1`(labelId 1 에 생성 후 DELETE 호출로 비활성) ③`LS_DATA_LBL_HSTRY` srcSn=105 이벤트 28행 ④`LS_DATA_SRC(105).LBL_VER` 증가. **다른 클러스터 판정에 영향 없음**(활성 라벨 마스터 9종·rawSn 126/132/133/129~131 전부 불변).

## 집계

| 판정 | 건수 | 비고 |
|---|--:|---|
| PASS | **66** | 실동작 **60** / 정적+테스트대조 **6** |
| FAIL | 0 | |
| PARTIAL | 0 | |
| BLOCKED | 0 | |
| 확인필요 | 0 | |
| N/A | 0 | |
| **검증 대상 계** | **66** | |
| (집계 제외) 폐기 | 1 | `~~TC-LABEL-70~~` — 좌표정책 반전(6d1b3703)으로 폐기, 대체 TC-LABEL-137/138(범위 밖) |

- 대상 ID 실측: `01~37`(37건, 38·39 는 결번) + `40~55`(16건) + `60~73`(14건, 70 폐기) = 표 행 67 − 폐기 1 = **66건**
- 정적 판정 6건: **TC-LABEL-54 · 67 · 68 · 69 · 72 · 73** — 전부 "런타임 유발 조건을 이 환경에서 만들 수 없음"이 사유이며, 각각 대응 단위테스트가 존재해 커버 확인(사유는 결과표 비고에 개별 명시)
- 근거 드리프트 **4건**(전부 경미 — 아래 별도 절)

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 실측 | 판정 |
|---|---|---|---|
| **C-ISSUE-21** | full-replace 동시저장 lost update — stale 작업본이 타인 라벨을 **무경고 삭제**(200). REVIEWER 가 `LabelAccessGuard` 를 무조건 통과해 WORKER 와 동시 편집 가능 | ①`labelVersion` 첨부 시 stale 요청 **409**(`{"message":"다른 사용자가 먼저 저장했습니다…"}`, `[Label] stale label version rejected srcSn=105 requested=0 current=1`) ②**REVIEWER 토큰으로도 409** — 역할 우회 없음 ③프레임 행 비관적 락 + 스칼라 프로젝션 CAS(`LabelService:272-274`) ④무변경 저장은 버전 미증가(`labelVersion` 1→1 실측) ⑤FE 가 실제로 토큰을 실어 보냄(`frontend/src/features/label/hooks/useUpdateLabels.ts:55`, `api.ts:304`) | **해소** |
| **C-ISSUE-22** | 이미지 경계 초과 좌표 무검증 — `[[999999,888888]]` 이 200 으로 그대로 저장 | 신규 라벨 `x=5000`/`y=2000`/`1e18` 전부 **400** `"좌표가 이미지 경계를 벗어났습니다 (…이미지=1920x1080)"`. 경계값 `[[0,0],[1920,1080]]` 은 200(허용). 기존 라벨은 **좌표가 실제로 바뀔 때만** 강제(레거시 OOB 무변경 재전송 200 / 경계 밖으로 이동 400) — 프레임 영구 차단 회귀 없음 | **해소** |
| **C-ISSUE-23** | R7 손상 `pointCn` fail-safe 전용 회귀 테스트 부재 | 동작은 정상(손상값 `not-a-json{{` → '변경됨' 판정, UPDATED 이력 기록 실측). 그러나 `grep -rn "pointsEqual\|normalizePoints" backend/src/test` = **0건** 유지 | **미해소(이월, LOW)** |
| **C-ISSUE-24** | `CONTROL_NOTIFY_ENABLED=false` 라 TASK_MODIFIED 런타임 관측 불가 | 런타임 실효값 **true**. `LS_CONTROL_NOTIFY_FALLBACK` `queue_sn=5` `TASK_MODIFIED/126/SUCCEEDED/SUCCESS` 실재(라벨 수정 → export v2 → 통지) | **해소** |
| **C-ISSUE-25** | 마스터 soft delete 시 그 라벨을 참조하는 프레임의 **모든 저장이 409 로 영구 차단** | 3분기 전수 실측 — ⓐ기존 라벨이 비활성 labelId **유지** → **200** ⓑ**신규** 라벨에 비활성 labelId 부여 → **409** ⓒ**기존 라벨의 labelId 를 비활성으로 변경** → **409**(우회 차단) | **해소** |
| **C-ISSUE-41** | 실모델 YOLO 경계 음수 좌표로 온라인 오토라벨이 프레임 80% 400 거부(배치와 정책 불일치) | 온라인 BBOX 9건·POLYGON 9건 정상 반환(400 0건). 규칙이 `DetectionBoxNormalizer` 공용 단일 원천으로 통합돼 배치(`YoloLabelPersister`)와 동일 함수 사용 | **해소** |

## ★좌표 2축 실측 (★3 정합 확인)

| 입력 | ① 사용자 저장 경로 (`PUT /v1/frames/105/labels`) | ② AI 검출 응답 경로 (`POST /v1/frames/106/autolabel`) | ★3 정합 |
|---|---|---|:--:|
| 음수 `x=-1` | **400** `"좌표는 0 이상이어야 합니다 (x=-1.0, y=5.0)"` | **clamp → 0** (`DetectionBoxNormalizer:79-81`, 단위테스트 `경계밖_음수좌표는_0으로_clamp되어_반환된다`) | ✅ 의도된 비대칭 |
| 경계 초과 `x=5000` (이미지 1920) | **400** `"좌표가 이미지 경계를 벗어났습니다"` | **clamp → 1920** (`이미지_상한_초과좌표는_이미지_경계로_clamp된다`) | ✅ |
| 경계값 `x=1920, y=1080` | **200**(허용 — 우/하단 끝 정상 좌표) | clamp 무변형 | ✅ |
| 거대값 `1e18` | **400**(경계 초과 메시지) | clamp → 경계값 | ✅ |
| `NaN` | **400** `"요청 본문이 올바르지 않습니다."`(Jackson 역직렬화 단계) | **400 all-or-nothing** (`DetectionBoxNormalizer:54-58` 유한성 가드가 clamp **이전**) | ✅ |
| 좌표쌍 원소수 ≠ 2 | **400** `"좌표는 [x, y] 형태여야 합니다."` | 개수 ≠ 4 → **400 all-or-nothing**(`:51-53`) | ✅ |
| 퇴화(`x2<=x1`) / 순서역전 | **200 (검증 없음)** ← 아래 **C-ISSUE-21** | **해당 검출만 스킵**(`:65-67`, WARN) | ⚠ 축 밖 갭 |
| 소수점 `10.5, 10.25` | **200**(정밀도 보존 저장) | 그대로 | ✅ |
| bounds 실측 불가(이미지 파일 부재, srcSn 30) | **200 + WARN**(`[Label] frame image path unresolved — coordinate upper-bound check skipped srcSn=30`) — 상한만 skip, 하한·형식 유지 | 상한 없음 취급, 하한 0 clamp 유지 | ✅ fail-open 일치 |

> **결론**: ★3(사용자=400 거부 / AI=clamp)은 **양축 모두 정책대로 동작**한다. 통일 제안 없음. 마지막 행의 퇴화 박스 갭은 *경계(bounds)* 축이 아니라 *기하 유효성(arity/degeneracy)* 축이라 ★3 의 대상이 아니며, 별도 이슈로 기록한다.

## 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-LABEL-01 | 프레임 라벨 조회 정상 | PASS | [실동작] `GET /v1/frames/65,66,105/labels` → 200. `siblings`(3~6건, `hasLabel`) + AI enrich(`autoLblYn:"Y"`,`confScore:0.703`,`lblSrcCd:"YOLO"`) + 마스터 enrich(`labelName`,`color`) + `labelVersion:2` 전부 포함 | |
| TC-LABEL-02 | 조회 IDOR: 타 영상 프레임 | PASS | [실동작] WORKER(2001) → 미배정 rawSn 138 프레임 105 → **403** `"본인에게 배정되지 않은 영상입니다."` | `LabelAccessGuard:62-69` |
| TC-LABEL-03 | 조회: 미인증 | PASS | [실동작] 무토큰 → **401** `UNAUTHORIZED` | |
| TC-LABEL-04 | 조회: 프레임 미존재 | PASS | [실동작] srcSn 999999 → **404** `"프레임을 찾을 수 없습니다."` | null 방어는 `:54-58` 정적 |
| TC-LABEL-05 | frameImageType RAW(REVIEWER+raw) | PASS | [실동작] REVIEWER `?raw=true` → `"frameImageType":"RAW"`. 추가로 `GET /v1/frames/65/image?raw=true` 바이트 md5 `c155a39c…` = **원본** 프레임 파일 md5 일치 | |
| TC-LABEL-06 | frameImageType DEID(WORKER raw무시) | PASS | [실동작] WORKER `?raw=true` → `"DEID"`. 이미지 md5 `064376b2…` = **비식별** 프레임 파일 md5 일치(원본 아님) | CWE-359 방어 실증 |
| TC-LABEL-07 | bulkUpsert 신규 INSERT(수동) | PASS | [실동작] 3건 INSERT → `LS_DATA_LBL` 356/357/358, `LS_DATA_LBL_AI_INFO` 행 **미생성**(→ 응답 `autoLblYn:"N"`), 이력 `add_cnt=3` ADDED×3 | |
| TC-LABEL-08 | bulkUpsert 기존 UPDATE | PASS | [실동작] id=356 좌표 변경 → `lbl_hstry_sn=14` `mdfcn_cnt=1`, `before{[[10,10],[100,100]]}`/`after{[[11,11],[101,101]]}` 스냅샷 기록 | |
| TC-LABEL-09 | full-replace: 빠진 라벨 삭제 | PASS | [실동작] 3건 중 358 제외 저장 → 358 **실삭제**, `lbl_hstry_sn=15` `del_cnt=1` + `before` 스냅샷(`after:null`) | |
| TC-LABEL-10 | full-replace: 빈 items=전량 삭제 | PASS | [실동작] `{"items":[]}` → `select count(*) ... src_sn=105` = **0**, `del_cnt=2` DELETED×2 | 의도된 계약(컨트롤러 javadoc 명시) |
| TC-LABEL-11 | 삭제 순서 FK고아 방지 | PASS | [실동작] `ATTR_VAL`(atrbVlId 1)+`AI_INFO` 자식 보유한 lblSn 383 을 full-replace 삭제 → **200**, 세 테이블 모두 0행(FK 위반 500 없음) | `FK_LS_DATA_LBL_ATTR_LBL` 실존 확인 |
| TC-LABEL-12 | 작업락 시 저장 차단 | PASS | [실동작] 신고 OPEN(작업락) rawSn 133 의 srcSn 78 PUT → **409** `"비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."` | |
| TC-LABEL-13 | dedupById 동일 id 중복 | PASS | [실동작] 같은 id=356 을 2회(앞 `[[1,1],[2,2]]` / 뒤 `[[12,12],[102,102]]`) → DB `[[12.0,12.0],[102.0,102.0]]` = **last-value-wins**, 이력 UPDATED 1건 | |
| TC-LABEL-14 | 좌표 검증: 빈 points | PASS | [실동작] `points:[]` → **400** `"items[2].points: must not be empty"` — **DTO `@NotEmpty` 가 선발화**(케이스 정정 문구와 일치, 서비스 메시지 미도달) | |
| TC-LABEL-15 | 좌표 검증: [x,y] 형식 위반 | PASS | [실동작] `[[1,2,3]]` → **400** `"좌표는 [x, y] 형태여야 합니다."` | |
| TC-LABEL-16 | 좌표 검증: 음수 | PASS | [실동작] `x=-1` → **400** `"좌표는 0 이상이어야 합니다 (x=-1.0, y=5.0)"` | **사용자 경로 한정**(★3 ①). AI 경로 clamp 와 비대칭 = 정책 |
| TC-LABEL-17 | 좌표 검증: 0 허용(경계) | PASS | [실동작] `[[0,0],[10,10]]` → **200** 저장(lblSn 376) | |
| TC-LABEL-18 | 신규 라벨 >1000점 차단(DoS) | PASS | [실동작] 신규 POLYGON 1001점 → **400** `"라벨당 좌표 개수 초과 (최대 1000 점)"` / 경계 **1000점 → 200** 저장(`length(point_cn)=13781`) | CWE-770 |
| TC-LABEL-19 | 기존 라벨 >1000점 simplify | PASS | [실동작] 기존 lblSn 382 에 1001점 전송 → **200**, 저장 후 점수 **15점**(Douglas-Peucker) — 400 아님 | |
| TC-LABEL-20 | labelId 미존재 | PASS | [실동작] `labelId:999999` → **404** `"라벨 마스터를 찾을 수 없습니다: labelId=999999"` | |
| TC-LABEL-21 | labelId USE_YN='N' → 신규 부여에만 409 | PASS | [실동작] labelId 37 soft delete 후 3분기 전수: ⓐ기존 라벨 유지 **200** ⓑ신규 부여 **409** ⓒ기존 라벨 labelId 변경 **409** — 정정 문구·우회방지 그대로 | C-ISSUE-25 해소 확증 |
| TC-LABEL-22 | Mass Assignment: autoLblYn 무시 | PASS | [실동작] `source:"AUTO_YOLO"` + `autoLblYn:"N"` → 응답 **"Y"** / `source:"MANUAL"` + `autoLblYn:"Y"` → 응답 **"N"**. `ls_data_lbl` 에 컬럼 자체 부재(전 컬럼 조회 확인) — AI_INFO 행 존재로 파생 | CWE-915 |
| TC-LABEL-23 | provenance AUTO_YOLO→AI_INFO | PASS | [실동작] `AUTO_YOLO/conf 0.87/algorithm YOLO` → `LS_DATA_LBL_AI_INFO(383, lbl_src_cd=YOLO, conf_score=0.87000, auto_lbl_yn=Y)` 행 생성 | |
| TC-LABEL-24 | provenance 화이트리스트 위반 | PASS | [실동작] `source:"HACK"` → **400** `"items[2].source: source 는 MANUAL/AUTO_YOLO/AUTO_SAM2 중 하나여야 합니다."` | |
| TC-LABEL-25 | confScore 범위 초과 | PASS | [실동작] `confScore:1.5` → **400** `"confScore 는 1.0 이하여야 합니다."` | |
| TC-LABEL-26 | items >500 상한(DoS) | PASS | [실동작] 501건 → **400** `"items: 한 번에 처리 가능한 라벨 수 초과 (최대 500)"` | 근거 라인 드리프트(아래) |
| TC-LABEL-27 | 무변경 재저장 이력 미발행(R7) | PASS | [실동작] 동일 3건 재전송 → 200, `labelVersion` **1→1 미증가**, `LS_DATA_LBL_HSTRY` 신규 행 **0**, 통지 0 | |
| TC-LABEL-28 | R7 정규화 비교(5 vs 5.0) | PASS | [실동작] DB 저장값 `[[10.0,10.0],…]` 에 요청 `[[10,10],…]`(정수 표기) 재전송 → **무변경 판정**(이력·버전 불변) | |
| TC-LABEL-29 | R7 레거시 3포맷 흡수 | PASS | [실동작] `pointCn` 을 평탄 `[10,10,100,100]` · 객체배열 `[{"x":20,"y":20},…]` 로 직접 적재 후 정규 표현 재전송 → **이력 0건**(무변경). 정규 포맷은 TC-28 에서 확인 = 3포맷 전수 | |
| TC-LABEL-30 | R7 손상 JSON fail-safe | PASS | [실동작] `pointCn='not-a-json{{'` 라벨 재저장 → **UPDATED 이력 기록**(`before.pointCn="not-a-json{{"`) = '변경됨' fail-safe. **전용 회귀 테스트는 여전히 0건**(C-ISSUE-23 이월) | |
| TC-LABEL-31 | TASK_MODIFIED: APPROVED만 | PASS | [실동작] APPROVED rawSn 126 라벨 수정분에 대해 `LS_CONTROL_NOTIFY_FALLBACK queue_sn=5 TASK_MODIFIED/126/SUCCEEDED/SUCCESS` + payload `changed_items` 실재(직접 SELECT). export **v2** 재생성 후 발송(`reExport=true`, pipeline-drive §1 #17) | 126 무변경 원칙상 재유발 안 함 — 기존 산출물 실측으로 판정 |
| TC-LABEL-32 | TASK_MODIFIED: 검수전 미발행 | PASS | [실동작] 미승인 rawSn 138(`LS_RAW_DATA_STATUS` 행 없음)에 **저장 20회 이상** 수행 → notify 테이블에 rawSn 138 행 **0건** | |
| TC-LABEL-33 | 이력 조회 IDOR | PASS | [실동작] WORKER → 미배정 프레임 105 이력 → **403** | |
| TC-LABEL-34 | 이력 페이지 상한 클램프 | PASS | [실동작] `?size=500` → 응답 `"size":100`. 기본값 `?` 미지정 → `"size":20` | |
| TC-LABEL-35 | 이력 임의 sort 무시(500 차단) | PASS | [실동작] `?sort=badfield,desc&size=3` → **200**(500 아님), content 선두가 최신 이벤트(`lblHstrySn=27`) = 서버 고정 정렬 | |
| TC-LABEL-36 | 동시 저장 lost update 차단(정정) | PASS | [실동작] stale `labelVersion:0`(현재 1) → **409 CONFLICT**. **REVIEWER 토큰으로도 409**(역할 우회 없음). WARN `stale label version rejected srcSn=105 requested=0 current=1`. 미첨부 시 skip(하위호환)은 확정 정책이며 FE 는 실제로 첨부 | C-ISSUE-21 해소 |
| TC-LABEL-37 | 삭제 감사 로깅 PII 미출력 | PASS | [실동작] 로그 8건 전수 확인 — `existing=/saved=/deleted=/labelVersion=` 카운트·버전만, **좌표·라벨명·PII 0** | |
| TC-LABEL-40 | 마스터 생성 정상 | PASS | [실동작] `POST /v1/manage/labels` → **201** `{labelId:37, useYn:"Y"}` | |
| TC-LABEL-41 | 마스터 근사중복(대소문+공백) | PASS | [실동작] 활성 `ZZTEST-C2` 존재 상태에서 `" zztest-c2 "` → **409** `"이미 사용 중인 라벨 이름입니다."` | |
| TC-LABEL-42 | 마스터 동시 생성 DB유니크 | PASS | [실동작] `pg_indexes` 에 `uk_ls_label_nm_ci ... WHERE use_yn='Y'`(V120) 실재. 앱 선검사 우회 직접 INSERT `' PERSON '` → `duplicate key ... "uk_ls_label_nm_ci"` 거부. [정적] `GlobalExceptionHandler:151-155` 가 이 제약명만 409 로 매핑(그 외 fail-closed 500) | |
| TC-LABEL-43 | dtctTypeCd allowlist 위반 | PASS | [실동작] `dtctTypeCd:"human"` → **400** `"지원하지 않는 검출 클래스입니다."` | 근거 라인 드리프트(아래) |
| TC-LABEL-44 | dtctTypeCd 활성 중복 매핑 | PASS | [실동작] `person` 재매핑 → **409** `"이미 사용 중인 검출 클래스 매핑입니다."`. DB `uk_ls_label_dtct_type` 부분 유니크도 직접 INSERT 로 거부 확인 | |
| TC-LABEL-45 | dtctTypeCd 미매핑 해제 허용 | PASS | [실동작] `"airplane"` 설정 → 응답 `dtctTypeCd:"airplane"` → 공백 `"  "` 재전송 → 응답 **`dtctTypeCd:null`** 저장 | |
| TC-LABEL-46 | color 소문자 hex 거부 | PASS | [실동작] `"#aabbcc"` → **400** `"color 는 대문자 hex (#RRGGBB) 형식이어야 합니다."` | |
| TC-LABEL-47 | type allowlist | PASS | [실동작] `type:"FOO"` → **400** `"type 은 BBOX/POLYGON/POINT/SKELETON 중 하나여야 합니다."` | |
| TC-LABEL-48 | sortNo 음수 | PASS | [실동작] `sortNo:-1` → **400** `"sortNo 는 0 이상이어야 합니다."` | |
| TC-LABEL-49 | 마스터 수정 미존재 | PASS | [실동작] `PUT /v1/manage/labels/999999` → **404** `"라벨을 찾을 수 없습니다."` | |
| TC-LABEL-50 | 마스터 수정 근사중복(자기제외) | PASS | [실동작] labelId 37 을 `"CAR"` 로 개명 → **409**. 자기 이름 `"ZZTEST-C2"` 재전송 → **200**(자기제외 정상) | |
| TC-LABEL-51 | 마스터 삭제 soft delete | PASS | [실동작] `DELETE /v1/manage/labels/37` → **204**, DB `use_yn='N'` 행 **잔존**(hard delete 없음) | |
| TC-LABEL-52 | 관리 권한: WORKER POST 차단 | PASS | [실동작] WORKER `POST /v1/manage/labels` → **403** `"권한이 없습니다."` | |
| TC-LABEL-53 | 조회 권한: WORKER/PORTAL 허용 | PASS | [실동작] WORKER GET **200** / **PORTAL_USER GET 200**. 대조군 — PORTAL 토큰의 `GET /v1/frames/65/labels` 는 **403**(채널 격리 유지) | |
| TC-LABEL-54 | findLabelIdByDtctType null/blank | PASS | [정적] `LabelMasterService:151-157` early-return(repository 미호출). 내부 메서드라 HTTP 표면 없음 → 단위테스트 `findLabelIdByDtctType_null_빈문자열이면_repository_미호출_empty` 로 커버 확인 | 정적 사유: 외부 진입점에서 null/blank 라벨명이 발생하지 않음 |
| TC-LABEL-55 | findLabelIdByDtctType 활성 유니크 최대1 | PASS | [실동작] `uk_ls_label_dtct_type ... WHERE use_yn='Y' AND dtct_type_cd IS NOT NULL`(V129) 인덱스 실재 + 중복 활성 매핑 직접 INSERT 거부 → `Optional` 시그니처의 NonUniqueResult 구조적 불가. trim 매칭은 `LabelMasterServiceTest` 커버 | |
| TC-LABEL-60 | autolabel BBOX 정상 | PASS | [실동작] `POST /v1/frames/106/autolabel` → 200, `detectedCount:9`, **모든 항목 `lblSn:null`**, `LS_DATA_LBL(src_sn=106)` 건수 **10 → 10 무변경**(DB 미저장 = 2경로 분리 준수) | `savedCount` 는 문서화된 deprecated mirror |
| TC-LABEL-61 | autolabel IDOR | PASS | [실동작] WORKER → 미배정 105 → **403**, ai 미호출(로그 무발생) | |
| TC-LABEL-62 | autolabel 작업락 | PASS | [실동작] 잠긴 rawSn 133(srcSn 78) → **409** `"작업이 잠긴 영상입니다."` | 락 없는 신고건은 412(코드 순서) |
| TC-LABEL-63 | autolabel inFlight 중복 | PASS | [실동작] 동일 프레임 POLYGON 3병렬 → **200/409/409** `"이미 오토라벨링이 진행 중인 프레임입니다."`. 완료 후 재요청 200 = finally 락해제 | |
| TC-LABEL-64 | resolveDetectClasses 매핑 0건 게이팅 | PASS | [실동작] 동일 분기(`effectiveClasses.isEmpty()`, `:229-235`)를 `classes:["fire"]`(마스터에 COCO 매핑 없음)로 발화 → `detectedCount:0` + `"검출할 수 있는 라벨이 없습니다…"` + 로그 `no mapped detect classes … requested=1`, **ai 미호출**. '마스터 전체 미매핑' 변형은 마스터 파괴 없이 유발 불가 → 단위테스트 `매핑된_라벨이_하나도_없으면_ai_미호출_빈결과_안내메시지` 커버 | |
| TC-LABEL-65 | resolveDetectClasses 화이트리스트 교집합 | PASS | [실동작] `classes:["person","hack"]` → person 검출 2건만 반환(전체 9건 중), WARN `[Autolabel] drop unmapped detect class=hack` | |
| TC-LABEL-66 | resolveDetectClasses 우회 시도 | PASS | [실동작] 미매핑 `fire` 강제 요청 → 교집합 공집합 → **ai 미호출·0건·NO_MAPPED 안내**. FE 요청 불신 확인 | |
| TC-LABEL-67 | autolabel mock 응답 차단 | PASS | [정적] `AutolabelOnlineService:246-251` — `resp.mock()` 이면 좌표 미반환 + `detectedCount=0` + MOCK message. 단위테스트 `mock_응답이면_빈_결과와_안내메시지를_반환한다`·`AutolabelMockMessageWiringTest` 커버 | 정적 사유: 실행 중 ai-server env `AI_MOCK_MODE=false` — mock 응답을 유발할 수단 없음(설정 변경 금지) |
| TC-LABEL-68 | 검출 좌표 개수 ≠4 → all-or-nothing 400 | PASS | [정적] `DetectionBoxNormalizer:51-53` `IllegalArgumentException` → `AutolabelOnlineService:565-570` 이 400 으로 승격(부분 반환 없음). 단위테스트 `좌표개수가_4개가_아니면_거부한다`·`형식위반은_여전히_all_or_nothing…` 커버 | 정적 사유: 실모델 ai-server 응답에 형식 위반을 주입할 수단 없음 |
| TC-LABEL-69 | 검출 좌표 NaN/Infinity → 400 | PASS | [정적] `DetectionBoxNormalizer:54-58` — `Double.isFinite` 가드가 **clamp 이전**(`:59` 보다 앞). 단위테스트 `NaN_Infinity_좌표는_거부한다_역직렬화_500_회귀방지` 커버 | 동상 |
| ~~TC-LABEL-70~~ | ~~validateBbox 음수/순서위반 400~~ | — | **폐기(2026-07-30)** — 집계 제외. `AutolabelOnlineService.validateBbox` 부재 확인(grep 0건) | 대체 TC-LABEL-137/138(범위 밖) |
| TC-LABEL-71 | 검출 0건 SAM 스킵 | PASS | [실동작] srcSn 105(검출 0인 프레임) → 200 `detectedCount:0`, 로그 `[Autolabel] no detections srcSn=105 rawSn=138 shape=BBOX` — SAM 호출 없음, reCheckLock 통과 후 빈 결과 | |
| TC-LABEL-72 | POLYGON maxBoxes 상한 | PASS | [정적] `AutolabelOnlineService:296-301` `limit=min(detected,maxBoxes)` + `truncated` → `polygonTruncatedMessage`. 단위테스트 `박스_개수가_상한을_초과하면_상한까지만_처리하고_message로_고지한다` 커버 | 정적 사유: 설정 `autolabel.polygon.max-boxes=20` 인데 이 환경 최대 검출이 13건 → 초과 유발 불가(설정 변경 금지) |
| TC-LABEL-73 | POLYGON wall-clock 예산 소진 | PASS | [정적] `:303`(deadline) `:313-319`(잔여≤0 → truncated=true + break + WARN). 단위테스트 `폴리곤_예산소진시_잔여박스_잘라_message_고지`(package-private `polygonTotalBudget` 주입) 커버 | 정적 사유: 기본 예산 60s, 실측 9박스 처리 ~17s → 소진 유발 불가 |

## 근거 드리프트

카탈로그 `근거(file:line)` 와 HEAD `ca3c712b` 실측의 차이. **모두 경미**(동일 파일 내 소폭 이동/포괄 범위)로 판정에는 영향 없음.

| ID | 카탈로그 근거 | 실측 | 성격 |
|---|---|---|---|
| TC-LABEL-22 | `LabelService.java:133-148` | `:133-143` 은 `resolveAiInfoMap`, `:145-165` 는 `resolveLsLabelMap`. **실제 Mass Assignment 트러스트 경계**는 `LabelItemDto.java:43`(autoLblYn 응답전용) + `LabelService.java:350-366`(source→AUTO_LBL_YN 서버 파생) | 근거 위치 부정확 |
| TC-LABEL-26 | `LabelBulkUpsertRequest.java:26` | `@Size(max = 500 …)` 은 **`:24`**. `:26` 은 record 본문 여는 `) {` | 2줄 밀림 |
| TC-LABEL-43·45 | `LabelMasterService.java:122-134` | `validateAndNormalizeDtctType` 본문은 **`:126-135`**(`:118-125` 는 javadoc) | 1줄 밀림 |
| TC-LABEL-42 | `LabelMasterService.java:74-78` · `LsLabelRepository.java:37` | 두 근거 모두 **앱 선검사**만 가리킨다. 케이스의 기대결과("DB유니크 차단 → 409")를 실제로 만드는 지점은 인덱스 `uk_ls_label_nm_ci`(V120) + **`GlobalExceptionHandler.java:138-166, :178-181`**(제약명 판별 후 409, 그 외 fail-closed 500)이며 근거에 없음 | 근거 누락 |

## 이슈 상세

FAIL/PARTIAL/확인필요 **0건**. 아래 1건은 반증(적대 검증) 과정에서 발견한 **범위 외 결함 후보**다(1차 C-ISSUE-25 와 동일한 성격의 기록).

### [C-ISSUE-21] (범위 외 발견 — TC-LABEL-15/16/17 반증 중) 사용자 저장 경로에 **기하 유효성(점 개수·퇴화·순서역전) 검증이 없다**

- **심각도**: MEDIUM (데이터 품질 오염 — 학습데이터/export COCO JSON 로 그대로 전파)
- **기대 동작(기대효과)**: `LBL_TYPE_CD` 가 요구하는 최소 기하 조건을 저장 시점에 강제해야 한다. BBOX/TRACK 은 대각 2점(면적 > 0), POLYGON/SEGMENT 는 정점 ≥ 3. 저장된 라벨은 곧바로 `LabelToAnnotationMapper` 를 거쳐 `bbox=[x,y,w,h]` / `polygon` 으로 export 되므로 폭·높이 0 이나 정점 1개짜리 도형은 학습데이터로 의미가 없다.
- **현재 동작**: `LabelService.validatePoints`(`backend/.../label/service/LabelService.java:706-730`)는 **① 비어있음 ② 각 원소가 `[x,y]` 2튜플인가 ③ 점 개수 상한 1000 ④ 음수** 만 본다. **점 개수 하한도, 타입별 arity 도, 퇴화/순서역전도 검사하지 않는다.**
  ```java
  // LabelService.java:719-729 (발췌)
  for (List<Double> pair : points) {
      if (pair == null || pair.size() != 2) { ... 400 ... }   // 원소 내부만 검사
      double x = pair.get(0); double y = pair.get(1);
      if (x < 0 || y < 0) { ... 400 ... }
  }
  // → points.size() 자체에 대한 하한·타입별 제약 없음
  ```
  실동작(srcSn 105, REVIEWER, 전부 **HTTP 200 저장 성공**):

  | 입력 | 결과 |
  |---|---|
  | `{"lblTypeCd":"BBOX","points":[[1,1]]}` (1점) | **200** 저장 |
  | `{"lblTypeCd":"BBOX","points":[[1,1],[2,2],[3,3]]}` (3점) | **200** 저장 |
  | `{"lblTypeCd":"POLYGON","points":[[1,1]]}` (정점 1) | **200** 저장 |
  | `{"lblTypeCd":"BBOX","points":[[50,50],[50,50]]}` (면적 0) | **200** 저장 |
  | `{"lblTypeCd":"BBOX","points":[[50,50],[50,80]]}` (폭 0) | **200** 저장 |
  | `{"lblTypeCd":"BBOX","points":[[500,500],[100,100]]}` (x2<x1 역전) | **200** 저장, DB `[[500.0,500.0],[100.0,100.0]]` 확인 |

  **같은 시스템의 다른 경로는 전부 막는다** — 비대칭이 본 지적의 핵심이다:
  - AI 검출 응답: `DetectionBoxNormalizer:51-53`(개수 ≠ 4 → 400), `:65-67`(clamp 후 `x2<=x1 || y2<=y1` → 해당 검출 스킵)
  - SAM 폴리곤 응답: `AutolabelOnlineService.validatePolygonPoints:600-604`(정점 < 3 → INVALID_INPUT)
  - SKELETON: `LabelService.validateSkeletonPoints:792-825`(정확히 17점, 원소 3, v∈{0,1,2})
  즉 **외부(AI) 입력에는 기하 유효성 규칙이 있는데 사용자 입력에는 없다.**
- **재현/확인 경로**:
  ```bash
  B=http://localhost:18081/api; RT=<REVIEWER 토큰>
  curl -s -o /dev/null -w "%{http_code}\n" -X PUT $B/v1/frames/105/labels \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' \
    -d '{"items":[{"lblTypeCd":"BBOX","labelId":1,"label":"rev","points":[[500,500],[100,100]]}]}'
  # → 200
  ```
  ```sql
  select lbl_sn, lbl_type_cd, lbl_nm, point_cn from ls_data_lbl where src_sn = 105;
  -- 411|BBOX|rev|[[500.0,500.0],[100.0,100.0]]
  ```
  (본 시험에서 만든 라벨은 검증 종료 시 `{"items":[]}` 로 전량 삭제 — 잔존 없음)
- **영향**: CWE-20(입력 검증 부재). 보안 침해는 아니고 **데이터 품질**이 리스크다. ①`LabelToAnnotationMapper.toBbox`(`:83-99`)가 min/max 바운딩을 하므로 크래시는 없으나 **`bbox` 의 w/h 가 0** 인 annotation 이 export JSON 에 그대로 실린다(정점 1개 POLYGON 은 `polygon=[x,y]` 2원소로 나간다). ②관제/데이터마트로 나가는 학습데이터셋에 학습 불가 도형이 섞인다. ③FE 캔버스가 이런 도형을 되읽을 때 렌더 이상이 발생할 수 있다(미확인). ④API 직접 호출·FE 버그·타 도구 연동 시 방어선이 전무하다.
- **★3 확정 정책과의 관계 (명시)**: **본 지적은 ★3 의 2축(사용자=400 거부 / AI=clamp)을 통일하자는 요구가 아니다.** ★3 은 *경계(bounds) 초과 좌표를 고칠 것인가 거부할 것인가* 의 축이고, 본 건은 *도형이 도형인가(arity·degeneracy)* 라는 별개 축이다. 좌표값을 **조용히 고치자는 제안이 아니라** 사용자 경로의 기존 스탠스(거부)를 그대로 적용하자는 것이다. 실제로 이 환경의 경계 clamp/거부 동작은 정책대로 정상 작동함을 위 「★좌표 2축 실측」에 별도 기록했다.
- **수정 방향(제안)**: `validatePoints` 에 타입별 최소 조건을 추가 — BBOX/TRACK 은 `points.size() == 2` 이고 `x2 != x1 && y2 != y1`(또는 min/max 로 면적 > 0), POLYGON/SEGMENT 는 `points.size() >= 3`. 위반 시 기존 좌표 검증과 동일하게 400(`INVALID_INPUT`). 기존 라벨(`id != null`)에는 `MAX_POINTS`·bounds 와 동일하게 **좌표가 실제로 바뀔 때만** 강제해 레거시 데이터로 프레임 저장이 영구 차단되는 회귀를 피한다. ⚠ **구현하지 않는다.**

### [C-ISSUE-22] (이월 확인) TC-LABEL-30 — R7 손상 `pointCn` fail-safe 전용 회귀 테스트 여전히 부재

- **심각도**: LOW (동작 정상 — 회귀 위험만)
- **기대 동작(기대효과)**: `pointCn` 이 파싱 불가한 손상값일 때 `pointsEqual` 이 '변경됨'으로 판정해 이력이 유실되지 않아야 하고, 그 동작이 회귀 테스트로 고정되어야 한다.
- **현재 동작**: 구현은 정상이다 — `LabelService.normalizePoints:568-585` 가 3포맷 + raw 폴백 모두 실패하면 `null` 반환, `pointsEqual:556-559` 가 `false`(변경됨)로 fail-safe. **실동작으로 확증**했다(`pointCn='not-a-json{{'` 라벨 재저장 → `LS_DATA_LBL_HSTRY` UPDATED 1건, `before.pointCn="not-a-json{{"`). 그러나 테스트 자산은 1차와 동일하게 없다: `grep -rn "pointsEqual\|normalizePoints" backend/src/test` = **0건**. 유사명 `LabelServiceFullReplaceIntegrationTest`「손상된_diff_JSON이_있어도_조회는_200과_빈changes를_반환한다」는 **이력 diff JSON(`CHG_DTL_CN`)** 손상 케이스로 대상이 다르다.
- **재현/확인 경로**:
  ```bash
  grep -rn "pointsEqual\|normalizePoints" backend/src/test   # 0건
  ```
  ```sql
  INSERT INTO ls_data_lbl(src_sn,lbl_type_cd,lbl_nm,point_cn,reg_user_no,reg_dt,lbl_id)
    VALUES (105,'BBOX','corrupt','not-a-json{{',1001,now(),NULL);
  -- 이후 PUT /v1/frames/105/labels 로 정상 좌표 재전송 → UPDATED 이력 생성 확인
  ```
- **영향**: 현재 결함 아님. 향후 `normalizePoints` 리팩터가 손상값을 '무변경'으로 오판하면 이력이 조용히 유실되는데 이를 잡을 테스트가 없다.
- **수정 방향(제안)**: `LabelServiceFullReplaceIntegrationTest` 에 `pointCn` 을 손상값(`"{{{"`)으로 덮어쓴 뒤 무변경 재저장 시 UPDATED 이력이 남는지 확인하는 케이스 1건 추가. ⚠ **구현하지 않는다.**
