# C-marking-labeling 검증 (Part 5) — C-6. TC-PRESET

- 대상: `docs/test-cases/C-marking-labeling.md` 323~347행 (TC-PRESET-01~18) — 18건
- 검증일: 2026-07-31 / 2-1차
- 스택: klid-backend(:18081/api) · klid-postgres(`klid_system`/`public`, 컨테이너명 `klid-postgres`)
- 이슈 ID 범위: C-ISSUE-91~100 (본 파트에서는 결함 미발견 — 아래 참고 참조)
- 패키지 이동(카탈로그 상단 안내 `label/service/PresetService` → `preset/service/PresetService`) — 실측 확인: 소스는 이미 `backend/src/main/java/kr/co/cudo/authoring/preset/{controller,service,dto,entity,repository}/*` 로 이동 완료 상태, 근거 `file:line` 표기도 카탈로그·코드 모두 `preset/service/PresetService.java` 기준으로 일치.

## 방법

- REVIEWER 토큰 발급: `POST /v1/dev/tokens {"userNo":1001,"role":"REVIEWER","channel":"INTERNAL"}` (userNo=1001 은 DB 시드 `ls_user_role`에 REVIEWER 로 등록된 계정 — INTERNAL 채널은 JWT `role` 클레임이 아니라 `UserRoleResolver.resolve(userNo)` 로 실인가 판정됨을 실측 확인. 임의 userNo 로 토큰만 발급하면 `ls_user_role` 미등록으로 403 발생 — 최초 시도에서 직접 재현).
- 전 케이스 `curl -X POST/PUT/DELETE http://localhost:18081/api/v1/manage/presets[...]` 실호출 + 응답 원문/HTTP status 확인.
- TC-14/15(미연결 코드뷰)는 API 로는 생성 불가한 레거시 데이터 형상이라 `ls_label_preset_code` 에 raw SQL 로 `lbl_id=NULL`(레거시 코드) 및 `lbl_id=6`(soft-delete 대상) 행을 임시 INSERT 후 `GET /v1/manage/presets` 응답으로 확인.
- TC-17 은 API(부분유니크 위반은 클라이언트 경로로 트리거 불가) 대신 raw SQL 중복 INSERT 로 DB 제약 자체를 실증.
- TC-18(N+1)은 `docker compose logs klid-backend` 로 실제 실행된 Hibernate SQL을 확인 — 56개 프리셋을 반환하는 단일 `GET` 요청에서 `LS_LABEL ... WHERE LBL_ID IN (...)` 쿼리가 **정확히 1회**만 실행됐음을 로그로 직접 확인(스레드 `http-nio-8080-exec-4`, 동일 트랜잭션 내 findAllWithCodes 직후 1회).
- 검증 후 생성한 테스트 프리셋 전량(`preset_nm LIKE '검증%'`, 55건) DELETE + `ls_label.lbl_id=6` 의 `use_yn` 을 `Y` 로 원복 완료(실데이터 잔존 없음, 재확인 완료).

## 결과

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-PRESET-01 | 생성 정상(labelId 기반) | PASS | [실동작] `POST /manage/presets {labelIds:[1,2]}` → 201, `labelCodeOptions[].code:null`(코드 문자열 미저장), `labelName`="person"/"car", `labelType`="BBOX" 마스터 join 파생 확인 | `PresetServiceTest#create_정상_케이스는_eventTypeCd가_전달되어_저장된다` | |
| TC-PRESET-02 | 생성 이벤트타입 무효 | PASS | [실동작] `eventTypeCd:"NOT_A_REAL_EVENT"` → **400** `INVALID_INPUT` "지원하지 않는 이벤트 타입입니다" | `PresetServiceTest#프리셋_저장시_유효_categoryKey면_통과_미유효면_400` | 유효 categoryKey 목록은 `GET /v1/event-types` 로 실측(9종: 010001/010002/020001/020002/020005/030001/050001/050002/050007) |
| TC-PRESET-03 | 생성 이벤트타입 빈값 허용 | PASS | [실동작] `eventTypeCd:""` → 201, 응답 `eventTypeCd:null`(정규화) | `PresetServiceTest#eventTypeCd_빈_문자열은_null_로_정규화되어_저장된다` | |
| TC-PRESET-04 | 생성 이름 중복 | PASS | [실동작] 동일 이름 2회 POST → 1차 201, 2차 **409** `CONFLICT` "이미 사용 중인 프리셋 이름입니다." | `PresetServiceTest#동일_이름_프리셋은_CONFLICT_변경_없음` | |
| TC-PRESET-05 | 생성 labelId 미존재/비활성 | PASS | [실동작] `labelIds:[999999]` → **400** `INVALID_INPUT` "존재하지 않거나 비활성 라벨입니다: labelId=999999" | `PresetServiceTest#마스터에_없는_labelId로_코드추가시_400` | |
| TC-PRESET-06 | 생성 이벤트 유니크 경합 | PASS | [실동작] `eventTypeCd:"010002"` 로 2개 프리셋 순차 생성 → 1차 201, 2차 **409** `CONFLICT` "이미 다른 프리셋에 매핑된 이벤트입니다"(DB `uk_ls_label_preset_evnt` 위반→`saveWithEventUniqueGuard`가 CONFLICT 변환, PresetService.java:174-181) | `PresetServiceTest#동일_이벤트가_다른_프리셋에_이미_매핑되어_있으면_CONFLICT` | 순차 요청으로 제약 위반 확인(진짜 동시 레이스는 미재현, DB UNIQUE 제약이 원자적으로 보장하므로 동시 요청도 동일 결과) |
| TC-PRESET-07 | 수정 미존재 | PASS | [실동작] `PUT /manage/presets/999999` → **404** `NOT_FOUND` "프리셋을 찾을 수 없습니다." | `PresetServiceTest#update_존재하지않는_프리셋이면_NOT_FOUND` | |
| TC-PRESET-08 | 수정 이름 중복(자기제외) | PASS | [실동작] 프리셋A를 프리셋B 이름으로 PUT → **409** `CONFLICT`. 이어서 프리셋A를 **자기 자신의 기존 이름**(변경 없음)으로 PUT → **200** 정상 통과(자기제외 로직 정합, `existsByPresetNmAndPresetIdNot` 확인) | (단위테스트에 자기제외 케이스 직접 커버 없음 — 본 회차 실동작으로 확증) | |
| TC-PRESET-09 | 수정 이벤트 유니크 위반 | PASS | [실동작] 이미 다른 프리셋에 매핑된 `eventTypeCd:"010002"` 로 PUT → **409** `CONFLICT` "이미 다른 프리셋에 매핑된 이벤트입니다" | `PresetServiceTest#update_시_이벤트_UNIQUE_위반은_CONFLICT_로_변환` | |
| TC-PRESET-10 | 삭제 멱등 | PASS | [실동작] `DELETE /manage/presets/999999` → **204**(no-op, 오류 없음) | (단위테스트 미확인 — 서비스 코드 `existsById` 가드로 자명) | |
| TC-PRESET-11 | 복제 이름 시퀀스 | PASS | [실동작] 동일 프리셋 연속 clone → 1차 "{name} (복사본)", 2차 "{name} (복사본 2)" 정확히 시퀀스 부여 확인 | (단위테스트 미확인 — 실동작으로 확증) | |
| TC-PRESET-12 | 복제 이벤트 미상속 | PASS | [실동작] 원본 `eventTypeCd:"010002"` 프리셋 복제 → 복제본 `eventTypeCd:null`(충돌 회피), 코드(labelId=1)는 그대로 복사 | `PresetServiceTest#clone_은_eventTypeCd를_상속하지_않고_코드를_복사한다` | |
| TC-PRESET-13 | 복제 이름 50회 초과 실패 | PASS | [실동작] 동일 프리셋 51회 연속 clone 실행 → 1~50회 전부 200("복사본 2"~"복사본 50"), **51번째 409** `CONFLICT` "복제 이름 생성에 실패했습니다."(`CLONE_SUFFIX_MAX=50` 정합, PresetService.java:184-196) | (단위테스트에 50회 루프 없음 — 본 회차 실동작으로 직접 확증) | |
| TC-PRESET-14 | 코드뷰 미연결(labelId null) | PASS | [실동작] `ls_label_preset_code` 에 `lbl_id=NULL, lbl_cd='LEGACY_OLD_CODE'` 행 직접 INSERT 후 `GET /manage/presets` 조회 → 응답 `{"labelId":null,"code":"LEGACY_OLD_CODE","labelName":"LEGACY_OLD_CODE","labelType":null,"linked":false,"bboxEnabled":false,"polygonEnabled":false}` — 오류 없이 legacy 코드 노출 확인(PresetService.java:239-244) | `PresetServiceTest#labelId가_null인_미연결코드는_linked_false와_legacy명으로_노출된다` | 1차 검증(07-25)은 시드 DB에 labelId=null 행이 없어 정적 판정에 그쳤으나, 본 회차는 실데이터로 실동작 재현·확증(개선) |
| TC-PRESET-15 | 코드뷰 마스터 미존재/soft delete | PASS | [실동작] `ls_label.lbl_id=6(truck)` 을 `use_yn='N'` 으로 임시 변경 + 해당 labelId 연결 코드 행 INSERT 후 조회 → 응답 `{"labelId":6,"code":null,"labelName":null,"labelType":null,"linked":false,"bboxEnabled":false,"polygonEnabled":false}` — 자동 생성/삭제 없이 linked:false로만 노출 확인. **검증 후 `use_yn='Y'` 로 원복 완료**(재확인: `select use_yn from ls_label where lbl_id=6` → Y) | `PresetServiceTest#list_labelId있지만_soft_delete된_마스터는_linked_false로_노출된다` | |
| TC-PRESET-16 | 코드뷰 형태 마스터 파생 | PASS | [실동작] `labelIds:[7]`(fire, `LBL_TYPE_CD='POLYGON'`)로 생성 → 응답 `labelType:"POLYGON", bboxEnabled:false, polygonEnabled:true`. BBOX 라벨(person/car)은 앞선 TC-01/04 등에서 `bboxEnabled:true, polygonEnabled:false` 로 이미 실측 — 두 형태 모두 마스터 파생 확인(프리셋 개별 토글 불가, PresetService.java:245-251) | `PresetServiceTest#마스터_형태_BBOX면_프리셋응답_토글이_bbox만_활성이다` 등 3종 | |
| TC-PRESET-17 | 부분유니크 labelId 중복 방지 | PASS | [실동작] DB 인덱스 실측 `uk_ls_label_preset_code_lblid = CREATE UNIQUE INDEX ... ON ls_label_preset_code (preset_id, lbl_id) WHERE lbl_id IS NOT NULL` 확인 + 동일 (preset_id=1, lbl_id=1) 중복 raw INSERT 시도 → **`ERROR: duplicate key value violates unique constraint "uk_ls_label_preset_code_lblid"`** 실제 발생(트랜잭션 자동 rollback, 데이터 변경 없음) | `LsLabelPresetCodeLabelIdUniqueIT#동일_프리셋에_같은_labelId_코드_중복INSERT는_부분유니크인덱스로_거부된다` | |
| TC-PRESET-18 | N+1 회피 배치 조회 | PASS | [실동작] 56개 프리셋(라벨 4종 중복 분산: labelId 1/2/6/7)을 반환하는 `GET /manage/presets` 단일 요청의 backend 로그를 직접 확인 — `findAllWithCodes()`(LS_LABEL_PRESET ⋈ LS_LABEL_PRESET_CODE) 쿼리 1회 직후 `SELECT ... FROM LS_LABEL WHERE LBL_ID IN (?, ?, ?, ?) AND USE_YN=?` 쿼리가 **정확히 1회**만 실행(스레드 `http-nio-8080-exec-4`, 프리셋별 반복 조회 0건) | `PresetServiceTest#프리셋_목록_조회시_코드_라벨_join이_N플러스1을_유발하지_않는다` | |

## 결함 (C-ISSUE-91~100)

**결함 없음.** C-6(TC-PRESET) 18건 전건 실동작으로 반증 시도했으나 기대결과와 어긋나는 동작을 발견하지 못했다. 이전 회차(2026-07-25 1차)도 전건 PASS였고, 그 사이 코드 변경은 패키지 이동(`label/service` → `preset/service`)뿐이라 회귀 없음을 확인했다. C-ISSUE-91~100 번호는 배정되었으나 대응하는 결함이 없어 본 파트에서는 사용하지 않는다.

### 참고 — 이번 회차에 실동작으로 보강된 점 (결함 아님, 개선 기록)
- TC-14(미연결 코드뷰)는 1차 회차에서 시드 DB에 해당 형상의 데이터가 없어 **정적 판정**에 그쳤으나, 본 회차는 raw SQL로 legacy 형상(`lbl_id=NULL`)을 직접 재현해 **실동작으로 확증**했다(판정 결과는 동일하게 PASS).
- TC-08(수정 이름 중복 자기제외)은 기존 자동테스트에 자기제외 케이스(자기 이름 그대로 PUT → 통과)가 직접 커버되어 있지 않다 — 기능은 실동작으로 정상 확인되었으나, 회귀 방지를 위해 `PresetServiceTest`에 "update_자기_이름으로_수정시_통과"류 단위테스트 추가를 권장(LOW, 코드 수정은 본 검증 범위 밖이라 제안만 기록).
