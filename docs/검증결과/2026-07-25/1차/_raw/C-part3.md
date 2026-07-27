# C-2 (후반) 검증 결과 (docs/test-cases/C-marking-labeling.md:98-143)

> 검증일 2026-07-25 · 로컬 풀스택 실동작 기준(klid-backend :18081, klid-postgres, klid-ai-server :19300, klid-mock-server :9400)
> DB 스키마는 `klid_at` 아닌 **`public`**.
> ✅ **환경 정정**: 착수 시 `klid-backend` 재빌드 완료 확인 — `/app/app.jar` Jul 25 01:59, 내장 마이그레이션 `V130__add_manual_env_privacy_meta` 포함, `flyway_schema_history` 최신 = **130**. B-part4 의 [B-ISSUE-62](stale jar) 는 **해소**되었고, 그로 인해 BLOCKED 였던 TC-DEID-035(=TC-LABEL-97)를 이번 회차에 **실동작으로 재검증**했다.
> ⚠ **본 검증이 남긴 상태 변경(전부 API 경유)**:
> - 라벨 마스터: `tcprobe`(493)·`tcmass`(494)·`tcrace`(495) 생성 후 **전부 soft delete(USE_YN='N')** 처리 — 활성 라벨 풀·COCO 매핑 allowlist 는 검증 전과 동일. 라벨 9(smoke)에 동일값 PUT 1회(값 불변).
> - 프레임 개인정보 메타: `src 369/370/371`(rawSn=7)에 Y/N/N 설정 → 신고로 NULL 리셋됨(검증 전 상태 = NULL 이므로 **원상복귀**).
> - 비식별 신고: **라벨 0건 영상 rawSn=7** 에만 2건(rprtSn 7·8) 등록 후 **둘 다 RESOLVED 처리** → `DE_IDENT_YN='Y'` 복원, 작업락 RELEASED. 라벨 파괴 **0건**(검증 데이터 rawSn=26 등 라벨 보유 영상은 신고 대상에서 제외).
> - `/app/storage/deidentified/videos/7/deidentified.mp4` mtime 을 `touch` 2회(외부 재비식별 시뮬레이션). 파일 내용 불변(24182B 동일).
> - 오토라벨 온라인 호출 다수 — **DB 미저장 경로**이므로 라벨 데이터 변화 0건(반증 확인함).

## C-2 (후반). TC-LABEL-52 ~ 109 — 권한 / 마스터 / 온라인 오토라벨 / 비식별신고

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-LABEL-52 | 관리 권한: WORKER POST 차단 | PASS | **[실동작]** WORKER POST/PUT/DELETE → 전부 **403**, PORTAL_USER POST → 403, 미인증 → 401, `PATCH` → 403, `X-HTTP-Method-Override: POST`+GET → **200 GET 그대로(생성 0건)**. 정적 `LabelMasterController.java:83,101,117` | LabelMasterControllerTest:149 | 우회 경로 4종 전부 차단. ★부가 발견: 토큰 `role=REVIEWER` 라도 **userNo=2001(DB상 WORKER)** 이면 403 — 역할이 토큰 클레임만이 아니라 서버측으로 재확인됨(권한 자가부여 방어) |
| TC-LABEL-53 | 조회 권한: WORKER/PORTAL 허용 | PASS | **[실동작]** WORKER GET 200, PORTAL_USER GET 200, 미인증 → **401**. `detect-candidates` 도 WORKER/PORTAL 200. 정적 `:57,69` | LabelMasterControllerTest:87,224 | **비활성(USE_YN='N') 라벨 미노출** 반증 확인 — animal·fallen-person·pose-skeleton·tcprobe·tcmass 응답 0건. 내부코드 누출 없음 |
| TC-LABEL-54 | findLabelIdByDtctType null/blank | PASS | **[정적]** `LabelMasterService.java:151-157` — null/blank 시 repository 미호출 `Optional.empty()`. **[실동작 등가]** 미매핑 요청 경로에서 labelId null 반환 확인 | LabelMasterServiceTest:303 | |
| TC-LABEL-55 | V129 유니크 최대1 | PASS | **[실동작]** `pg_indexes` 에 `uk_ls_label_dtct_type UNIQUE (dtct_type_cd) WHERE use_yn='Y' AND dtct_type_cd IS NOT NULL` 실존. `person`/`" person "` 중복 매핑 시도 → 둘 다 **409**. 정적 `LsLabelRepository.java:84` | LabelMasterServiceTest:271,283,311 | NonUniqueResult 재현 불가(활성 1건 강제됨) |
| TC-LABEL-40 | 마스터 생성 정상 | PASS | **[실동작]** REVIEWER POST → **201** `labelId=493` | LabelMasterServiceTest:51 | 범위 밖이나 전제로 실행 |
| TC-LABEL-41 | 마스터 근사중복(대소문+공백) | PASS | **[실동작]** `"  Car  "` → **409**, `"PERSON"` → **409**. 정적 `:76-78` | LabelMasterServiceTest:129,142 | |
| TC-LABEL-42 | 마스터 동시 생성 DB유니크 | PASS | **[실동작]** 동일명 5병렬 POST → **201×1 / 409×4**, DB 활성 행 **1건**. 정적 `:70-78` | LabelMasterServiceTest:99 | 앱 선판정+`uk_ls_label_nm_ci` 이중 방어 |
| TC-LABEL-43 | dtctTypeCd allowlist 위반 | PASS | **[실동작]** `"human"`→400, `"Person"`(대문자)→400, `"person' OR 1=1--"`→**400**(SQLi 차단). 정적 `:126-135` | LabelMasterServiceTest:338 | |
| TC-LABEL-44 | dtctTypeCd 활성 중복 매핑 | PASS | **[실동작]** `person`·`" person "` → 409. 정적 `:81-83` | LabelMasterServiceTest:349,374 | |
| TC-LABEL-45 | dtctTypeCd 미매핑 해제 허용 | PASS | **[실동작]** `""`·`"   "`·필드누락 → 200 + DB `dtct_type_cd IS NULL` 확인. `zebra` 매핑→재해제 왕복 확인. 정적 `:127-129` | LabelMasterServiceTest:361 | |
| TC-LABEL-46 | color 소문자 hex 거부 | PASS | **[실동작]** `#ffffff` → 400 "color 는 대문자 hex". 정적 `LabelMasterRequest.java:33` | LabelMasterControllerTest:174 | |
| TC-LABEL-47 | type allowlist | PASS | **[실동작]** `FOO` → 400. 정적 `:37` | LabelMasterControllerTest:134 | |
| TC-LABEL-48 | sortNo 음수 | PASS | **[실동작]** `-1` → 400. +name 65자 → 400(@Size). +Mass assignment 반증: `useYn:"N"`·`labelId:1`·`regId:"hacker"` 주입 → **전부 무시**(useYn=Y, 신규 id 발번). 정적 `:40` | — | `@JsonIgnoreProperties(ignoreUnknown=true)` + record 필드 화이트리스트 |
| TC-LABEL-49 | 마스터 수정 미존재 | PASS | **[실동작]** PUT/DELETE `id=999999` → **404**. 정적 `:100-101,191-192` | LabelMasterServiceTest:173 | |
| TC-LABEL-50 | 마스터 수정 근사중복(자기제외) | PASS | **[실동작]** 라벨8(fire)→`"CAR"` → **409**. 라벨9(smoke)→`" smoke "`(자기이름) → **200**(자기제외 정상). 정적 `:104-106` | LabelMasterServiceTest:227,239 | |
| TC-LABEL-51 | 마스터 삭제 soft delete | PASS | **[실동작]** DELETE 494/493 → **204**, DB 행 **잔존 + `use_yn='N'`**(hard delete 아님). 정적 `:189-195` | LabelMasterServiceTest:183 | |
| TC-LABEL-60 | autolabel BBOX 정상 | **PARTIAL** | **[실동작]** src447 → **200**, `lblSn:null` 좌표만. **미저장 반증 통과**: 호출 전후 `ls_data_lbl` count=8·`max(reg_dt)` 완전 동일(3회 반복). **그러나 rawSn=26 프레임 5건 중 4건(446/448/449/450)이 400** — 실모델 음수 좌표로 전량 거부 → **[C-ISSUE-41]** | AutolabelOnlineServiceTest:127,146 | 미저장 정책은 PASS, 정상 검출 성공률이 20%(1/5) |
| TC-LABEL-61 | autolabel IDOR | PASS | **[실동작]** WORKER 2002(미배정) → **403** "본인에게 배정되지 않은 영상입니다.", PORTAL → 403, 미인증 → 401, 미존재 프레임 → 404. 정적 `AutolabelOnlineService.java:185` | AutolabelControllerTest:121,128,136 | ai 호출 전 최우선 게이트 |
| TC-LABEL-62 | autolabel 작업락 | PASS | **[실동작]** 잠긴 rawSn=8(src 4)·rawSn=12(src 350) → **409** "작업이 잠긴 영상입니다.". 정적 `:189-191` | AutolabelOnlineServiceTest:200 | |
| TC-LABEL-63 | autolabel inFlight 중복 | PASS | **[실동작]** 동일 프레임 5병렬 → **200×1 / 409×4**, 직후 재요청 **200**(finally 락해제 확인). 정적 `:194-196,254-256` | AutolabelOnlineServiceTest:372,180 | |
| TC-LABEL-64 | resolveDetectClasses 매핑 0건 게이팅 | PASS | **[실동작 등가]** 요청이 전부 미매핑이면 동일 분기(`effectiveClasses.isEmpty()`) 진입 — ai **미호출**·0건 + `NO_MAPPED_CLASS_MESSAGE` 확인. 전 라벨 매핑 해제(마스터 6건 파괴)는 회피. 정적 `:205-212` | AutolabelOnlineServiceTest:471,460 | |
| TC-LABEL-65 | resolveDetectClasses 화이트리스트 교집합 | PASS | **[실동작]** `["person","zebra"]` → **person 3건만** 반환(전체호출 4건 = person3+bus1 대비 bus·zebra 제외). `["  person  "]` 도 trim 후 통과. 정적 `:413-427` | AutolabelOnlineServiceTest:404,446 | 매핑 allowlist 교집합만 ai 전달 실증 |
| TC-LABEL-66 | resolveDetectClasses 우회 시도 | PASS | **[실동작] 우회 4종 전부 차단** — `["zebra"]`(유효 COCO·미매핑)·`["dog","cat"]`·`["hack","<script>"]`·`["PERSON"]`(대소문자) 모두 **0건 + NO_MAPPED 메시지**, ai 미호출. 정적 `:408-427` | AutolabelOnlineServiceTest:460 | **FE 요청 불신 성립** — 미매핑 라벨이 검출된 사례 0건 |
| TC-LABEL-67 | autolabel mock 응답 차단 | PASS | **[정적]** `:221-228` — `resp.mock()` 시 좌표 미반환·빈 결과. live 는 ai-server 실모델(mock=false)이라 미발화 | AutolabelMockMessageWiringTest:34 / AutolabelOnlineServiceTest:323 | |
| TC-LABEL-68 | validateBbox 4좌표 아님 | PASS | **[정적]** `:499-503` | AutolabelOnlineServiceTest:290 | 외부 응답 주입 불가로 live 미실행 |
| TC-LABEL-69 | validateBbox NaN/Infinity | PASS | **[정적]** `:504-508` (`Double.isFinite`) | AutolabelOnlineServiceTest:257,268 | |
| TC-LABEL-70 | validateBbox 음수/순서위반 | PASS | **[실동작]** 실모델 음수 좌표로 **실제 400 발화**(`"YOLO 응답 좌표는 0 이상이어야 합니다."`) — 가드 자체는 스펙대로 동작. 정적 `:509-518` | AutolabelOnlineServiceTest:246,279 | 가드는 PASS 이나 **부작용이 [C-ISSUE-41]** |
| TC-LABEL-71 | 검출 0건 SAM 스킵 | PASS | **[실동작]** `["truck"]`·`["bicycle"]`(매핑됐으나 화면에 부재) → **200 / detectedCount=0 / message=null** — NO_MAPPED(0건 안내) 와 정상 0건이 구분됨. 정적 `:236-241` | AutolabelPolygonServiceTest:285 / OnlineServiceTest:345 | |
| TC-LABEL-72 | POLYGON maxBoxes 상한 | PASS | **[정적]** `:273-278`, 설정 `autolabel.polygon.max-boxes=20`. live 미발화 — 실데이터 최대 검출 **19건/프레임 < 20** | AutolabelPolygonServiceTest:217 | |
| TC-LABEL-73 | POLYGON wall-clock 예산 소진 | PASS | **[정적]** `:280-296`(기본 60s). live 폴리곤 4박스 소요 7.7s | AutolabelPolygonServiceTest:339 | |
| TC-LABEL-74 | POLYGON 박스별 실패 스킵 | PASS | **[정적]** `:298-325` | AutolabelPolygonServiceTest:234,253,268 | |
| TC-LABEL-75 | POLYGON bulkhead 429 즉시전파 | PASS | **[실동작]** POLYGON 6병렬 → **200×4 / 429×2**, 429 가 스킵으로 **흡수되지 않고** 응답으로 전파됨(부분 결과 아님). 정적 `:315-320` | AutolabelPolygonServiceTest:314 | |
| TC-LABEL-76 | POLYGON TOCTOU 중간 잠금 | PASS | **[정적]** `:286-289` + 배치 후 `reCheckLock` `:329`. live 재현은 배치 중 신고 = 라벨 파괴 필요로 회피 | AutolabelPolygonServiceTest:299 / OnlineServiceTest:164 | |
| TC-LABEL-77 | YOLO bulkhead 초과 429 | PASS | **[실동작]** 서로 다른 8프레임 동시 → **200×4 / 429×4** — `max-concurrent-calls: 4`(application.yml:349) 와 정확히 일치, `max-wait-duration:0` fail-fast. 정적 `:450-454` | — | |
| TC-LABEL-78 | ai-server 호출 실패 502 | PASS | **[정적]** `:455-460` — `EXTERNAL_API_ERROR`, 스택트레이스 미노출(CWE-209) | AutolabelOnlineServiceTest:305 | ai-server 중단은 타 검증 영향으로 미실행 |
| TC-LABEL-79 | AutolabelRequest conf 범위 | PASS | **[실동작]** 0.9 → 400, 0.1 → 400. 정적 `AutolabelRequest.java:39-40` | AutolabelRequestPrecisionValidationTest:37,45 | +`simplifyTolerance:60` → 400 |
| TC-LABEL-80 | classes 100개 초과 | PASS | **[실동작]** 101개 → 400, 원소 51자 → **400**(`classes[0]`). 정적 `:36-37` | AutolabelControllerTest:207 | +`shape:"CIRCLE"` → 400(역직렬화 차단) |
| TC-LABEL-90 | 신고 정상+전체 삭제 | PASS | **[실동작]**(라벨 0건 rawSn=7) → **201** `rprtSn=7`, `DE_IDENT_YN='Y'→'F'`, `LS_AUTH_WORK_LOCK` LOCKED(owner 1001) DB 확인. 라벨 스냅샷·삭제 경로는 라벨 보유 영상 파괴 회피로 정적 `:140-164` + IT | DeidentReportServiceTest:172,220,287 | B-part4 TC-DEID-034 와 **판정 일치** |
| TC-LABEL-91 | 신고 reason 누락 | PASS | **[실동작]** `""`·`"   "`·필드누락 → 전부 **400**. 정적 `:112-114` | ControllerTest:189 / ServiceTest:486 | B-part4 TC-DEID-030 일치 |
| TC-LABEL-92 | 신고 reason 1000자 초과 | PASS | **[실동작]** 1001자 → 400 "신고 사유는 1000자 이하". 정적 `DeidentReportRequest.java:19` | — | |
| TC-LABEL-93 | 신고 IDOR | PASS | **[실동작]** WORKER 2002(미배정) → 403, PORTAL → 403, 미인증 → 401. 정적 `:117` | ControllerTest:139 | B-part4 TC-DEID-031 일치 |
| TC-LABEL-94 | 신고 이미 잠금 | PASS | **[실동작]** rawSn=7 신고 직후 동일 영상 타 프레임(370) 재신고 → **409**. 정적 `:132-134` | ControllerTest:168 | B-part4 TC-DEID-033 일치 |
| TC-LABEL-95 | 신고 PII TOCTOU(FOR UPDATE) | PASS | **[정적]** `:127-128` `findByRawSnForUpdate` + 대칭 게이트 `ResolutionReservationPersister:63-74` | AugmentDeidentConcurrencyIT | B-part4 TC-DEID-032 일치 |
| TC-LABEL-96 | 신고 동시 락 유니크 | PASS | **[실동작]** 동일 영상 5병렬 신고 → **201×1 / 409×4**, DB 신고행 **1건**·작업락 **1건**. 정적 `:132-134,159-163` | ServiceTest:431 | B-part4 TC-DEID-036 일치(이번엔 병렬 실측) |
| TC-LABEL-97 | 신고 개인정보 3필드 리셋 | PASS | **[실동작] ★B-part4 BLOCKED 해소** — V130 적용된 재빌드 스택에서 재검증. `PUT /v1/frames/{369,370,371}/privacy-meta` 로 Y/N/N 설정 → 신고 후 3프레임 `anony_incl_yn·psdo_incl_yn·prvc_incl_yn` **전부 NULL**. 앱 로그 `privacyReset=3`. 정적 `:151-155` | ServiceTest:199 / ResetIT:67 | B-part4 TC-DEID-035(BLOCKED) → **PASS 로 승격** |
| TC-LABEL-98 | 신고 APPROVED→TASK_MODIFIED | PASS | **[정적]** `:144-149`(`snapshotted && isReviewApproved`). **[실동작 음성확인]** rawSn=7(work_stts=ASSIGNED, 라벨 0건) 신고 시 `labelsRemoved=false` 로 통지 **미발행** — 조건 게이트 동작 확인. APPROVED+라벨 보유 영상은 파괴 회피 | ServiceTest:391,413 | B-part4 TC-DEID-037 일치 |
| TC-LABEL-99 | 신고 라벨 0건 스킵 | PASS | **[실동작]** 로그 `labelsRemoved=false`, `LS_DATA_LBL_HSTRY`(rawSn=7) **0건**, `LS_LABEL_VERSION` 0건. 정적 `:140-149` | ServiceTest:261,354 | B-part4 TC-DEID-038 일치 |
| TC-LABEL-100 | resolve 미인증 | PASS | **[실동작]** 무토큰 → **401**. 정적 `:193-195` | ServiceTest:831 / ControllerTest:294 | B-part4 TC-DEID-039 일치 |
| TC-LABEL-101 | resolve 신고 미존재 | PASS | **[실동작]** `rprtSn=999999` → **404**. 정적 `:196-197` | ServiceTest:840 | B-part4 TC-DEID-040 일치 |
| TC-LABEL-102 | resolve IDOR | PASS | **[실동작]** WORKER 2002 가 rprtSn=2(rawSn=8, 2001 배정) resolve → **403**. WORKER 2001(본인배정) → 409(산출물 게이트 = 인가 통과), PORTAL → 403. 정적 `:200` | ControllerTest:241,273 | B-part4 TC-DEID-050 일치 |
| TC-LABEL-103 | resolve OPEN 아님 | PASS | **[실동작]** `rprtSn=1`(RESOLVED) → **409** "이미 처리된 신고입니다.". 정적 `:203-205` | ServiceTest:560 | B-part4 TC-DEID-041 일치 |
| TC-LABEL-104 | resolve 산출물 미검증 gate | PASS | **[실동작]** `rprtSn=2`(rawSn=8) → **409** + DB 재확인: report OPEN 유지·`DE_IDENT_YN='F'` 유지·락 LOCKED 유지(롤백 성립). 정적 `:207-211,332-376` | ServiceTest:666,691 | B-part4 TC-DEID-042/044 일치 |
| TC-LABEL-105 | resolve 시간조건(신고 이후 재비식별) | PASS | **[실동작] ★직접 A/B 대비** — rprtSn=7(신고 11:07:37), 비식별본 mtime `Jul 22 02:15` 상태 resolve → **409**. 동일 파일 `touch`(mtime→Jul 25 02:08 UTC = 11:08 KST) 후 재호출 → **200**. 옛 비식별본 배제가 실제로 작동. 정적 `:353-375` | ServiceTest:735,768,798 | B-part4 TC-DEID-045 일치(이번엔 통과/거부 양방 실측) |
| TC-LABEL-106 | resolve 정상+'F'→'Y' 복원 | PASS | **[실동작] ★B-part4 정적판정 → live 승격** — resolve 200 후 DB: `rprt_stts_cd=RESOLVED`(resolved_dt 기록), `LS_AUTH_WORK_LOCK` **RELEASED/`MANUAL_DEIDENT_DONE`**, `LS_DATA_RAW.DE_IDENT_YN='F'→'Y'`. 정적 `:213-231` | ServiceTest:544,604,622,709 | B-part4 TC-DEID-043 (정적) 과 **모순 없음, 강화** |
| TC-LABEL-107 | resolve 배치상태 역행 금지 | PASS | **[실동작]** 신고 전/신고 후/resolve 후 rawSn=7 `DATA_STTS_CD` = **COMPLETED 불변**. 정적 `:223-231` | ServiceTest:642 | B-part4 TC-DEID-046 일치 |
| TC-LABEL-108 | listReports status allowlist | PASS | **[실동작]** `status=X` → 400, `status=OPEN' OR 1=1--` → **400**(SQLi 차단), `status=resolved`(소문자) → 400. WORKER 목록 조회 → 403. 정적 `:260-273` + Controller `@Pattern` | ControllerTest:353,361 | B-part4 TC-DEID-048 일치 |
| TC-LABEL-109 | listReports 기본 OPEN | PASS | **[실동작]** status 미지정 → 200, OPEN 5건만 반환. `size=500` → 응답 `size:100` **클램프**. 정적 `:261-263` | ControllerTest:322 | 페이징 강제됨(전체조회 없음) |

---

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [C-ISSUE-41] TC-LABEL-60 / TC-LABEL-70 — 실모델 YOLO 가 반환하는 경계 음수 좌표 때문에 온라인 오토라벨이 프레임 대부분에서 400 으로 전량 거부됨 (배치 경로와 검증 정책 불일치)
- **심각도**: HIGH (핵심 기능 가용성 — 라벨링 화면 'AI 탐지' 버튼이 실데이터에서 80% 실패)
- **기대 동작(기대효과)**: 라벨링 화면에서 AI 탐지를 누르면 프레임의 검출 좌표가 반환되어 캔버스 작업본에 반영된다. 화면 경계에 걸친 객체(사람이 프레임 왼쪽 끝에 반쯤 걸림 등)는 CCTV 학습데이터에서 정상적인 다수 케이스이며, 배치 오토라벨 경로는 이를 정상 저장한다.
- **현재 동작(이슈 내용)**: 온라인 경로는 ai 응답 좌표를 **all-or-nothing** 으로 검증하며 **음수를 곧바로 전체 거부**한다.
  - `backend/.../label/service/AutolabelOnlineService.java:509-512`
    ```java
    if (v < 0) {
        throw new CustomException(ErrorCode.INVALID_INPUT,
                "YOLO 응답 좌표는 0 이상이어야 합니다.");
    }
    ```
    `:230-233` 에서 검출 전체를 순회하며 하나라도 위반이면 400 → **정상 검출분까지 전부 폐기**(부분 반환 금지가 설계 의도).
  - **실측(로컬 실모델 ai-server, rawSn=26)**: 5개 프레임 중 **4개(src 446/448/449/450)가 400**, 성공은 src 447 하나뿐.
  - **동일 모델·동일 프레임을 배치 경로는 그대로 저장**한다 — `LS_DATA_LBL` 에 음수 좌표 라벨이 실존:
    ```
    lbl_sn=205 src=446 bus    [[-1.5731448368773044, 2.556953126603844], [1261.30…, 707.91…]]
    lbl_sn=213 src=448 bus    [[11.71…, -2.573257235451649], [1241.05…, 611.50…]]
    lbl_sn=215 src=449 person [[1032.46…, -0.9496699098489216], [1279.68…, 719.26…]]
    ```
    rawSn 별 음수 좌표 라벨 수: 26→15건, 28→4건, 27→2건, 14~19→각 1건.
  - `backend/.../batch/step/YoloLabelPersister.java` 에는 음수·유한성 검증이 **없다**(길이 홀수만 상위 스텝에서 검증 — `YoloAutolabelStepTest:195`). 즉 **같은 외부 응답을 배치는 신뢰하고 온라인만 거부**하는 정책 분기가 존재한다.
- **재현/확인 경로**:
  ```bash
  RT=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
    -d '{"role":"WORKER","channel":"INTERNAL","userNo":"2001"}' -o /tmp/t.json; \
    grep -o '"token":"[^"]*"' /tmp/t.json | sed 's/"token":"//;s/"$//')
  for s in 446 447 448 449 450; do printf "$s "; curl -s -o /dev/null -w '%{http_code}\n' \
    -X POST http://localhost:18081/api/v1/frames/$s/autolabel \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' -d '{}'; done
  # -> 446:400 447:200 448:400 449:400 450:400
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT s.raw_sn, count(*) FROM public.ls_data_lbl l JOIN public.ls_data_src s ON s.src_sn=l.src_sn
     WHERE l.point_cn ~ '-[0-9]' GROUP BY 1 ORDER BY 1;"   -- 배치가 저장한 음수 좌표 라벨
  ```
- **영향**: SFR-08 라벨링 편의(AI 탐지)의 실사용 불가에 가까운 가용성 저하. 사용자에게 노출되는 메시지가 `"YOLO 응답 좌표는 0 이상이어야 합니다."` 라는 내부 검증 문구여서 원인 파악도 어렵다. 보안 방향은 fail-closed(과다 거부)이므로 데이터 유출·오염 위험은 없으나, **배치 DB 에는 이미 음수 좌표가 적재되어 있어 "온라인만 엄격" 이라는 비대칭이 검증 목적을 달성하지 못한다**(CWE-20 과잉 적용 + 정책 일관성 결함).
- **수정 방향(제안)**: ① 이미지 경계 기준 **clamp(0 ≤ x ≤ imgWidth, 0 ≤ y ≤ imgHeight)** 후 반환 — 모델의 경계 초과는 정상 출력이며 캔버스도 클램프 전제(`UNCERTAINTIES #10`)다. ② clamp 를 택하지 않는다면 최소한 **음수는 부분 스킵(해당 검출만 제외)** 으로 완화하고 all-or-nothing 은 NaN/Infinity·좌표 개수 위반에만 적용. ③ 어느 쪽이든 **배치(`YoloLabelPersister`)와 온라인(`AutolabelOnlineService.validateBbox`)의 좌표 정규화 규칙을 단일 유틸로 통일**해 비대칭을 제거. ④ 상한(x2>imgWidth) 미검증도 함께 정리(현재 `1279.68`, `721.30` 등 초과값이 무검증 통과).

---

## 부수 관찰 (결함 아님 / 참고)

- **역할 검증이 토큰 클레임 단독이 아님**: `role=REVIEWER` 토큰이라도 `userNo=2001`(DB상 WORKER)이면 `/v1/manage/labels` POST 가 **403**. `userNo=1001` 이어야 201. A 클러스터에서 지적된 "권한 자가부여" 유형의 우회가 이 구간에는 **없다**는 반증 근거.
- **`AutolabelResponse.savedCount`**: 미저장 경로인데 필드명이 `savedCount` 이고 `detectedCount` 와 동일값으로 노출된다(의도된 하위호환 — `AutolabelOnlineServiceTest:359`). FE 가 "저장됨"으로 오해할 여지가 있으나 계약상 명시됨.
- **`GET /v1/deident-reports?status=resolved`(소문자) → 400**: 서비스(`normalizeStatus:265`)는 `toUpperCase()` 로 관용하지만 컨트롤러 `@Pattern` 이 먼저 대문자만 허용해 거부한다. 방어 방향(엄격)이라 무해하나 서비스 javadoc 과 실동작이 다르다.
- **`X-HTTP-Method-Override` 미지원**: Spring Boot 3.x 기본값으로 `HiddenHttpMethodFilter`/override 필터가 비활성 — 메서드 우회 표면 없음.
- **PORTAL_USER 가 라벨 마스터·detect-candidates 조회 가능**: 설계상 허용(`LabelMasterController` javadoc). 응답에 `dtctTypeCd`(COCO 내부 매핑)가 포함되나 비활성 라벨은 제외되며 민감정보 아님.
- **`autolabel.polygon.max-boxes=20` vs 실데이터 최대 검출 19건/프레임**: TC-72 상한 분기가 운영 데이터에서 사실상 미발화. 상한이 유효한지(너무 높은지) 운영 판단 참고.

## 근거 라인 드리프트

**0건.** 본 구간 근거 `file:line` 45개 참조를 HEAD 소스와 전수 대조 — `LabelMasterController.java`, `LabelMasterService.java`, `LabelMasterRequest.java`, `LsLabelRepository.java`, `AutolabelOnlineService.java`, `AutolabelRequest.java`, `DeidentReportService.java`, `DeidentReportRequest.java` 전부 일치.
(참고: TC-LABEL-41 의 `:75-78` 은 실제 중복검사 라인이 `:76-78`, TC-LABEL-55 의 `:80-84` 는 javadoc 80-83 + 메서드 84 — 둘 다 범위 내라 드리프트로 계수하지 않음.)

## 요약

- 총 **45건** / PASS **44** / FAIL **0** / PARTIAL **1**(TC-LABEL-60) / BLOCKED **0** / N/A **0** / 확인필요 **0**
  - (표에는 전제로 실행한 범위 밖 TC-LABEL-40~51 을 함께 기록했으나 집계에서는 제외)
  - PASS 44 중 **실동작 직접 확인 35건**, 정적+기존 테스트 커버 9건(외부 응답 주입·라벨 파괴·상한 초과 검출·ai-server 중단이 필요해 live 미실행)
- 근거 라인 드리프트: **0건** / **self-fill 결함: 0건**
  - self-fill 반증: 오토라벨 좌표는 전부 실 ai-server(:19300) 응답이며 좌표값이 프레임마다 상이(하드코딩 아님). 비식별 resolve 는 **파일 mtime 이 실제로 갱신되기 전에는 200 을 절대 내주지 않음**(A/B 실측) — 외부 산출물 없이 값을 자체 생성하는 경로 미발견.
- **인가·게이팅 우회 경로 미발견**: 마스터 쓰기(WORKER/PORTAL/미인증/PATCH/method-override 5종), Mass assignment(useYn·labelId·regId), 오토라벨 IDOR·작업락·화이트리스트 우회 4종, 신고/resolve IDOR 3종, status SQLi — **전부 fail-closed 차단**.
- **온라인 오토라벨 저장 정책 준수 확인**: BBOX·POLYGON 양 경로 모두 호출 전후 `ls_data_lbl` count·`max(reg_dt)` 불변 → 자동 저장(기존 라벨 덮어씀·이력 누락) **없음**.
- 신규 이슈 **1건**: HIGH 1 — [C-ISSUE-41] 실모델 음수 좌표로 온라인 오토라벨 80% 400 + 배치/온라인 검증 정책 비대칭.
- **B-part4 와 중복된 신고 케이스 (14건, 판정 일치 여부)**:

| C-2 ID | B-part4 ID | B-part4 판정 | 이번 판정 | 일치 |
|---|---|:--:|:--:|:--:|
| TC-LABEL-90 | TC-DEID-034 | PASS | PASS | ✔ |
| TC-LABEL-91 | TC-DEID-030 | PASS | PASS | ✔ |
| TC-LABEL-93 | TC-DEID-031 | PASS | PASS | ✔ |
| TC-LABEL-94 | TC-DEID-033 | PASS | PASS | ✔ |
| TC-LABEL-95 | TC-DEID-032 | PASS(정적) | PASS(정적) | ✔ |
| TC-LABEL-96 | TC-DEID-036 | PASS | PASS(병렬 실측 보강) | ✔ |
| **TC-LABEL-97** | **TC-DEID-035** | **BLOCKED**(stale jar) | **PASS**(실동작) | ✔ 승격 — 환경 결함 해소로 모순 아님 |
| TC-LABEL-98 | TC-DEID-037 | PASS(정적) | PASS(정적+음성 실동작) | ✔ |
| TC-LABEL-99 | TC-DEID-038 | PASS | PASS | ✔ |
| TC-LABEL-100 | TC-DEID-039 | PASS | PASS | ✔ |
| TC-LABEL-101 | TC-DEID-040 | PASS | PASS | ✔ |
| TC-LABEL-102 | TC-DEID-050 | PASS | PASS | ✔ |
| TC-LABEL-103 | TC-DEID-041 | PASS | PASS | ✔ |
| TC-LABEL-104 | TC-DEID-042/044 | PASS | PASS | ✔ |
| TC-LABEL-105 | TC-DEID-045 | PASS | PASS(통과/거부 양방 실측) | ✔ |
| **TC-LABEL-106** | **TC-DEID-043** | **PASS(정적/테스트)** | **PASS(실동작)** | ✔ 강화 — 모순 없음 |
| TC-LABEL-107 | TC-DEID-046 | PASS | PASS | ✔ |
| TC-LABEL-108/109 | TC-DEID-048 | PASS | PASS | ✔ |

> **모순 0건.** B-part4 대비 달라진 두 건(TC-LABEL-97·106)은 모두 **판정 하향이 아니라 상향**이며, 원인은 ①백엔드 재빌드로 V130 반영(97) ②라벨 0건 영상 + 파일 mtime 갱신 시뮬레이션으로 성공 경로 실행 가능(106) — B-part4 가 기록한 제약이 이번 회차에 해소된 결과다.
