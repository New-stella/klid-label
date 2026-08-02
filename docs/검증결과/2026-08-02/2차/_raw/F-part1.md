# F 클러스터 part1 — F-1(채널·역할 게이팅) + F-2(데이터마트 Load) 검증 결과

> 대상: `docs/test-cases/F-portal.md` `## F-1`(11건) + `## F-2`(19건) = **30건**
> 회차: 2026-08-02 2차 · 검증 방식: **풀스택 실동작 우선**(backend `localhost:18081/api` · PostgreSQL `public` 스키마)
> 코드 기준: `/Users/chanki/Documents/workspace/klid-label-worktrees/qa-0801`
> 토큰: `POST /v1/dev/tokens`(dev 발급) + role/channel 불일치 조합은 `JWT_SECRET` 으로 직접 서명해 위조

## 0. 검증 환경 실측

| 항목 | 실측 |
|---|---|
| backend | `GET /api/actuator/health` → 200 (`klid-backend` Up 19h) |
| mock-server / ai-server / frontend / postgres | 전부 Up·200 |
| 스키마 | `public` (문서상 `klid_at` 아님 — 1차 stack-bringup 기록과 동일) |
| baseline | `_raw/test-baseline.md`(08-01): BE 4,755 / FE 1,951 / ai 91, **실패 0건** |
| 사용 계정 | PORTAL_USER `sub=3001`·`sub=3002`, WORKER `2001`, REVIEWER `1001` |
| 투입 테스트 데이터 | `ls_data_raw` 9500/9501/9502, `ls_data_src` 990001~990020, 라벨 123행 — **검증 후 전량 삭제(복원 확인 완료)**. 컨테이너 storage 에 만든 심링크/사본도 제거 |

### ⚠ 배포 이미지 ↔ qa-0801 워킹트리 차이 (판정 유효성 확인)

`klid-backend` 이미지 빌드 시각은 **2026-08-01 14:05**(HEAD `56d30478`)이고, qa-0801 워킹트리에는 **미커밋 수정 80파일**이 있다. 본 파트 30건의 판정 근거 파일이 그 차이에 걸리는지 전수 확인했다.

| 근거 파일 | 워킹트리 수정 | 판정 영향 |
|---|:--:|---|
| `common/security/SecurityConfig.java` | **없음** | F-1 전건(001~008) 실동작 = qa-0801 코드 그대로 |
| `portal/service/PortalLabelService.java` | **없음** | F-2 전건(020~038) + **F-ISSUE-01/02 모두 양쪽 동일** |
| `common/storage/StorageSubtreePolicy.java` | **없음** | TC-032/033/034 동일 |
| `video/service/DeidentReportGate.java` | **없음** | TC-035/036/038 동일 |
| `common/security/JwtAuthenticationFilter.java` | 있음(+16) | `exp` 클레임 **필수화**만 추가. 본 검증에 쓴 토큰은 전부 `exp` 보유 → 판정 불변 |
| `portal/controller/PortalLabelController.java` | 있음(+15) | `SortAllowlist` 배선 + javadoc 만. `requireActor`·게이트 로직 **불변** → TC-009/023/024 판정 불변 |
| `portal/controller/PortalUploadController.java` | 있음(+39) | 동일(정렬 allowlist 만). `requireActor` **불변** |

→ **30건 전건의 판정은 배포본·워킹트리 양쪽에서 동일하게 성립**한다.

부수 관찰(본 파트 케이스 아님): 배포 이미지는 정렬 allowlist 수정 이전이라 `GET /v1/portal/datamart/videos?sort=bogusKey,asc` → **500**(`INTERNAL_ERROR`), `?sort=rawFilePathNm,asc` → **200**(내부 경로 컬럼으로 정렬 성립)이다. 워킹트리에는 `SortAllowlist.apply(...)` 가 배선돼 있어 400 으로 바뀐다 = **A-ISSUE-61 은 코드상 해소, 배포 미반영**. `PortalLabelControllerTest:datamartVideos_unknownSortKey_returns400` 등 3개 테스트가 회귀를 고정한다.

---

## 1. F-1. 채널·역할 게이팅 / 인가 경계 (11건)

| ID | 판정 | 근거 확인 | 실측 |
|----|:--:|---|---|
| TC-PORTAL-001 | PASS | [실동작] | `GET /v1/portal/datamart/videos` (PORTAL_USER+PORTAL) → **200**, 12건 반환 |
| TC-PORTAL-002 | PASS | [실동작] | WORKER/INTERNAL → **403** `{"errorCode":"FORBIDDEN"}` |
| TC-PORTAL-003 | PASS | [실동작] | REVIEWER/INTERNAL → `GET /v1/portal/uploads` **403** |
| TC-PORTAL-004 | PASS | [실동작] | **위조 토큰**(`role=PORTAL_USER`,`channel=INTERNAL`, 정상 서명) → `/v1/portal/**` **403**, 역방향 `/v1/videos` 도 **403**. `SecurityConfig.java:134-135` `allOf(ROLE_PORTAL_USER, CHANNEL_PORTAL)` 두 조건 AND 실증 |
| TC-PORTAL-005 | PASS | [실동작] | PORTAL 토큰 → `POST /v1/frames/1/sam2-track` **403** (하위 catch-all `/v1/**` = `SecurityConfig.java:147-153`) |
| TC-PORTAL-006 | PASS | [실동작] | `PUT /v1/manage/labels/1` **403** |
| TC-PORTAL-007 | PASS | [실동작] | `GET /v1/notices` **403** (`SecurityConfig.java:132`) |
| TC-PORTAL-008 | PASS | [실동작] | `GET /v1/manage/labels` **200** (라벨 마스터 6+건, `SecurityConfig.java:126`) |
| TC-PORTAL-009 | PASS | [실동작] | `sub` 클레임 **없음** → 401 `"포털 토큰 미상"`, `sub:""` **빈문자열**도 401. 근거는 `PortalUploadController.java:146-151`(카탈로그 138-143 드리프트) |
| TC-PORTAL-010 | PASS | [정적] | `portalGuard.test.tsx:69-77` 존재 + **실제 라우터도 동일 조합** 사용(`router/index.tsx:154-164`) → 테스트 전용 wrapper 가 실배선과 어긋나지 않음 |
| TC-PORTAL-011 | PASS | [정적] | `portalGuard.test.tsx:79-91`(INTERNAL→/portal, /portal/label/:id 양쪽) + 91-99(REVIEWER 도 forbidden) |

### F-1 반증 시도 기록 (전건 통과)

1. **채널 우회 전수 스캔** — PORTAL 토큰으로 내부 14개 엔드포인트 호출:
   `/v1/event-types`·`/v1/tasks/board`·`/v1/reviews`·`/v1/videos`·`/v1/videos/4/stream`·`/v1/frames/1/image`·`/v1/frames/1/labels`·`/v1/system/configs`·`/v1/notices`·`/v1/labels/1/deident-report`·`/v1/deident-reports` → **전부 403**.
   200 인 것은 `/v1/me`(의도된 예외, 자기 클레임 반향만: `{"userId":"3001","role":"PORTAL_USER"}`)와 `/actuator/health`(permitAll) 뿐 — 업무 데이터 노출 없음.
2. **교차채널 권한 상승(CWE-269/863)** — PORTAL 토큰으로 `POST /v1/auth/role-claim {"role":"REVIEWER","adminPassword":"…"}` → **403** `"해당 역할은 자가 부여할 수 없습니다"`. `RoleClaimService.java:127` 의 `actor.channel() != Channel.INTERNAL` fail-closed 화이트리스트가 **패스워드 검증 이전에** 차단(타이밍 오라클도 없음).
3. **role=null 위조** — `channel=PORTAL` + `role` 클레임 부재 토큰은 `ROLE_PORTAL_USER` authority 미부여로 `/v1/portal/**` 403(위 allOf 두 조건 중 role 축).

---

## 2. F-2. 데이터마트 Load (19건)

| ID | 판정 | 근거 확인 | 실측 |
|----|:--:|---|---|
| TC-PORTAL-020 | PASS | [실동작] | 목록 12건 전부 `LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'`. DB 의 PENDING(7,31)·ASSIGNED(17,27,30,33)·REJECTED(5)·FAILED(6,35) 및 상태행 부재 영상 **전건 미포함**. `findAllWithReviewStatus(null, APPROVED, …)` INNER JOIN 게이트 실증 |
| TC-PORTAL-021 | **PARTIAL** | [실동작] | 프레임 0건 제외 자체는 동작(APPROVED 20건 중 프레임 보유 12건만 노출). 그러나 **`totalElements`/`totalPages` 가 오보되고 중간에 빈 페이지가 생긴다** — `size=5`: total=20/pages=4 인데 page2 는 **0건**, page3 은 2건. `size=100`: total=**12**. → **F-ISSUE-02** |
| TC-PORTAL-022 | PASS | [실동작] | Hibernate SQL 로그 실측 — 10행 페이지 1회 요청에 `select` **5회**(content / count / `LS_DATA_SRC` firstSrcSn IN / `LS_DATA_SRC` count IN / `LS_RAW_DATA_STATUS` findAllById IN). rawSn 수에 비례하는 반복 쿼리 없음 |
| TC-PORTAL-023 | PASS | [실동작] | 121건 보유 rawSn 에 `size=99999&page=-1` → **100건**, `size=100&page=1` → 21건, `size=0` → **1건**. clamp `min(max(size,1),100)`·`max(page,0)` 실증 |
| TC-PORTAL-024 | PASS | [실동작] | `rawSn` 누락 → **400** `INVALID_INPUT` `"필수 파라미터가 누락되었습니다: rawSn"`. (컨트롤러 `@RequestParam Long rawSn` 필수 바인딩이 1차 차단, 서비스 `PortalLabelService.java:102-104` 는 방어적 이중 가드) |
| TC-PORTAL-025 | PASS | [실동작] | srcSn=1 에 3001 이 user-label 저장 후 재조회 → **본인 user-label 7건만** 반환(datamart 원본 `person` 미포함) |
| TC-PORTAL-026 | PASS | [실동작] | 3002(해당 프레임 저장분 0) 가 srcSn=23 조회 → datamart 원본 `{"id":572,"label":"person","points":[[1,1],[5,5]]}` 반환. DB `ls_data_lbl.lbl_sn=572` 와 일치 |
| TC-PORTAL-027 | PASS | [실동작] | 미승인(PENDING) rawSn=7 프레임 → **403** `"데이터마트에 노출되지 않은 영상입니다"` |
| TC-PORTAL-028 | PASS | [실동작] | srcSn=99999999 → **404** `"프레임을 찾을 수 없습니다"` |
| TC-PORTAL-029 | PASS | [실동작] | srcSn=1 → **200**, `Content-Type: image/jpeg`(13,164B, 실제 JPEG), `X-Content-Type-Options: nosniff`, **`Cache-Control: no-store`**, `X-Frame-Options: DENY` |
| TC-PORTAL-030 | PASS | [실동작] | 미승인 프레임 이미지 → **403** |
| TC-PORTAL-031 | PASS | [실동작] | `DE_IDNTF_SRC_FILE_PATH_NM` NULL 인 프레임(srcSn=445, rawSn=902 APPROVED, 원본 경로는 존재) → **404** `"비식별 프레임이 존재하지 않습니다"`. **원본 폴백 없음** 확인 |
| TC-PORTAL-032 | PASS | [실동작] | deid 경로에 `…/frames/deid/4/../../../../raw/frames/raw/4/frame-0.jpg` 주입 → **403** `"허용되지 않은 이미지 경로입니다"`(`Verdict.OUTSIDE_BASE`) |
| TC-PORTAL-033 | PASS | [실동작] | ① deid **base 하위의 raw 서브트리**(`{deidBase}/frames/raw/4/frame-0.jpg`, 실파일 배치) → **403**(`OUTSIDE_DEID_SUBTREE`) ② **심링크 우회**(`{deidBase}/frames/deid/4/qa-evil.jpg → {rawBase}/frames/raw/4/frame-0.jpg`) → **403**. 실경로(`toRealPath`) 기준 서브트리 판정(CWE-59/22) 실증 |
| TC-PORTAL-034 | PASS | [실동작] | 존재하지 않는 파일 → **404** `"이미지 파일이 존재하지 않습니다"`, 응답 본문에 경로·스택 **미노출**. 디렉터리 경로 주입도 동일 404(`NOT_REGULAR_FILE`). 서버 로그도 `verdict=` 사유코드만 기록(CWE-209/117) |
| TC-PORTAL-035 | PASS | [실동작] | `DE_IDNTF_YN='F'` + `APPROVED` 인 rawSn=900 프레임(429) 라벨 → **412** `PRECONDITION_FAILED`. APPROVED 게이트만으로는 안 걸리는 경로임을 데이터로 확인(900 은 목록에도 노출됨) |
| TC-PORTAL-036 | PASS | [실동작] | 동일 프레임 이미지 → **412**, 응답 바이트 0(파일 미판독) |
| TC-PORTAL-037 | PASS | [실동작] | 응답 헤더 `Cache-Control: no-store` 단독(구 `private, max-age=300` 흔적 없음) |
| TC-PORTAL-038 | PASS | [실동작] | 부모 9500(`'F'`) / 파생 9501(`ORGNL_RAW_SN=9500`, `'Y'`) 구성 → 파생 프레임 라벨 **200**(`derived-label` 좌표 반환) · 파생 프레임 이미지 **200**(JPEG), 부모 프레임은 **412**. ★1 확정 정책의 귀결 그대로 — **결함 아님**. `DeidentReportGate.java:24-38` javadoc · `AiInferenceDeidentReportGateTest.java:339-351`(라인 정확 일치) |

---

## 3. 이슈

### [F-ISSUE-01] TC-PORTAL-020 / TC-PORTAL-027 / TC-PORTAL-035 (인접 갭: TC-PORTAL-023·024 엔드포인트) — `GET /v1/portal/datamart/labels` 에 APPROVED 게이트·비식별 신고 게이트가 **모두 부재**해 외부 채널이 내부 전 영상의 라벨 좌표를 열람

- **심각도**: **HIGH**
- **기대 동작(기대효과)**:
  포털은 **데이터마트 노출(검수 완료=APPROVED) 영상만** 접근할 수 있어야 하고(CLAUDE.md 포털 절 · `PortalLabelService.isExposedToDatamart`),
  비식별 누락 신고 구간(`DE_IDNTF_YN='F'`)에는 **라벨 좌표 조회를 차단**해야 한다(CLAUDE.md: *"라벨 좌표가 PII 위치 특정 정보이므로 스트리밍만 막는 것으로는 부족"*, 차단 범위 목록 ①).
  실제로 형제 엔드포인트 `GET /v1/portal/frames/{srcSn}/labels` 는 두 게이트를 모두 갖는다(403 / 412).
- **현재 동작(이슈 내용)**:
  `PortalLabelService.loadDatamartLabels` (`backend/src/main/java/kr/co/cudo/authoring/portal/service/PortalLabelService.java:100-116`) 에는 `isExposedToDatamart` 호출도, `accessGuard.requireNotUnderDeidentReport` 호출도 **없다**. rawSn 만 받아 `lblRepository.findAllByRawSn(rawSn)` 를 그대로 반환한다.

  ```java
  public List<DatamartLabelResponse> loadDatamartLabels(Long rawSn, int page, int size) {
      if (rawSn == null) { throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다."); }
      int clampedSize = Math.min(Math.max(size, 1), 100);
      int clampedPage = Math.max(page, 0);
      List<LsDataLbl> all = lblRepository.findAllByRawSn(rawSn);   // ← 게이트 0건
      ...
  }
  ```
  컨트롤러(`PortalLabelController.java:83-91`)도 `@PreAuthorize("hasRole('PORTAL_USER')")` + `requireActor` 만 있고 영상 단위 인가가 없다.

  **실측(투입 데이터 기준)**
  | 요청 | 대상 영상 상태 | 결과 |
  |---|---|---|
  | `GET /v1/portal/datamart/labels?rawSn=9502` | `PENDING`(미승인) | **200**, `LEAK-PENDING-LABEL` 포함 100건 + `pointsJson` 좌표 원문 |
  | `GET /v1/portal/datamart/labels?rawSn=9500` | `APPROVED` + `DE_IDNTF_YN='F'`(신고 구간) | **200**, `LEAK-REPORTED-LABEL` 좌표 반환 |
  | (대조) `GET /v1/portal/frames/990020/labels` | 동일 미승인 영상 | **403** |
  | (대조) `GET /v1/portal/frames/990010/labels` | 동일 신고 구간 영상 | **412** |

  즉 **같은 데이터에 대해 게이트가 걸린 경로와 안 걸린 경로가 공존**하며, 후자가 전자를 무력화한다. rawSn 은 1..N 연속 정수라 열거가 자명하다.
- **재현/확인 경로**:
  ```sql
  -- 미승인 영상 + 라벨 1건 만들기
  INSERT INTO ls_data_raw (raw_sn,vms_clip_id,vms_cctv_id,prvc_type_cd,prvc_yn,de_ident_yn,raw_file_path_nm,data_stts_cd,reg_dt)
    VALUES (9502,'QA-F1-PENDING','CCTV-QA','PRVC','Y','Y','/app/storage/raw/qa/p.mp4','PENDING',now());
  INSERT INTO ls_raw_data_status (raw_data_id,data_stts_cd,upd_dt) VALUES (9502,'PENDING',now());
  INSERT INTO ls_data_src (src_sn,raw_sn,frm_no,de_idntf_src_file_path_nm,reg_dt)
    VALUES (990020,9502,0,'/app/storage/deidentified/frames/deid/4/frame-0.jpg',now());
  INSERT INTO ls_data_lbl (src_sn,lbl_type_cd,lbl_nm,point_cn,reg_dt)
    VALUES (990020,'BBOX','LEAK','[[3.0,3.0],[7.0,7.0]]',now());
  ```
  ```bash
  TOK=$(curl -s -XPOST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL"}' | jq -r .data.token)
  curl -s -H "Authorization: Bearer $TOK" \
       "localhost:18081/api/v1/portal/datamart/labels?rawSn=9502"   # → 200 + 좌표
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOK" \
       "localhost:18081/api/v1/portal/frames/990020/labels"          # → 403 (대조)
  ```
- **영향**:
  - **CWE-862(Missing Authorization) / CWE-639(IDOR — rawSn 열거)**: 외부 채널(PORTAL_USER)이 검수 전·반려·실패 상태 영상의 라벨 본문(라벨명·BBOX/POLYGON 좌표·`trackId`)을 전건 덤프할 수 있다. 데이터마트에 미노출인 내부 작업 중 산출물이 외부로 샌다.
  - **CWE-359(Private Information Exposure)**: 비식별 누락 신고 구간의 라벨 좌표는 **마스킹 실패 PII 의 위치 좌표**다. CLAUDE.md 가 명시적으로 차단 대상으로 규정한 자산이 그대로 나간다. 게이트 해제(`resolve`) 대기 중에도 계속 열람 가능.
  - ⚠ 이는 ★1(파생 경유 열람) 확정 정책과 **무관한 별개 경로**다 — 파생 여부와 상관없이 **원본 rawSn 자체**가 뚫린다.
- **수정 방향(제안)**:
  `PortalLabelService.loadDatamartLabels` 진입부에 형제 메서드(`loadFrameLabels`:257-267)와 **동일 순서·동일 컴포넌트**로 두 게이트를 배선한다 — ①`requireActor(actor)`(시그니처에 `TokenClaims actor` 추가) ②`if (!isExposedToDatamart(rawSn)) throw FORBIDDEN` ③`accessGuard.requireNotUnderDeidentReport(rawSn)`. 판정을 국소 재구현하지 말 것(본 결함의 원인이 정확히 "게이트 복제 누락"이다).
  아울러 **회귀 가드**로 `PortalUserLabelServiceTest` 에 미승인/신고 구간 케이스를 추가하고, 카탈로그 F-2 에 이 엔드포인트의 게이트 케이스(TC-PORTAL-039/040 신규)를 등재할 것 — 현재 카탈로그가 `/datamart/labels` 의 게이트를 **한 건도 커버하지 않아** 1차·2차 모두 케이스 통과로는 잡히지 않았다.

---

### [F-ISSUE-02] TC-PORTAL-021 — 데이터마트 영상 목록의 `totalElements`/`totalPages` 오보 + 중간 빈 페이지로 뒷 페이지 영상 유실

- **심각도**: **MEDIUM**
- **기대 동작(기대효과)**: 프레임 0건 영상을 목록에서 제외하되(진입 불가하므로), 페이징 메타(`totalElements`/`totalPages`)와 실제 순회 결과가 일치해 포털 사용자가 **노출 대상 영상 전체를 빠짐없이** 볼 수 있어야 한다.
- **현재 동작(이슈 내용)**:
  `PortalLabelService.java:148-161` 이 **DB 페이지를 가져온 뒤 애플리케이션에서 필터**하고, 총건수는 필터 전 값을 그대로 넘긴다.
  ```java
  List<DatamartVideoResponse> content = rows.stream()
          .filter(r -> firstSrcSnByVideo.get(r.getRawSn()) != null)   // 페이지 내부에서만 제외
          .map(...).toList();
  // 제외로 인해 페이지 size 보다 적어질 수 있으나 totalElements 는 원본(게이트 후) 기준 유지.
  return new PageImpl<>(content, pageable, page.getTotalElements());
  ```
  실측(APPROVED 20건 중 프레임 보유 12건):
  | 요청 | totalElements | totalPages | 실제 content |
  |---|--:|--:|--:|
  | `?page=0&size=5` | 20 | 4 | 5 (94,81,80,906,905) |
  | `?page=1&size=5` | 20 | 4 | 5 (902,903,901,900,26) |
  | `?page=2&size=5` | 20 | 4 | **0** |
  | `?page=3&size=5` | 20 | 4 | 2 (18, 4) |
  | `?page=0&size=100` | **12** | 1 | 12 |

  `size=100` 에서 total 이 12 로 바뀌는 것은 `PageImpl` 이 `offset + pageSize > total` 일 때 총계를 `offset + content.size()` 로 재계산하기 때문이며, 그 결과 **같은 데이터셋의 총건수가 페이지 크기에 따라 20↔12 로 달라진다**.
- **재현/확인 경로**:
  ```bash
  for p in 0 1 2 3; do
    curl -s -H "Authorization: Bearer $TOK" \
      "localhost:18081/api/v1/portal/datamart/videos?page=$p&size=5" \
      | jq -c '{page:'"$p"',total:.data.totalElements,pages:.data.totalPages,n:(.data.content|length)}'
  done
  ```
- **영향**: 기능/데이터정합.
  ① 무한스크롤·"더 보기" 처럼 **빈 응답을 종료 신호로 쓰는 FE 구현은 page2 에서 멈춰 rawSn 18·4 를 영원히 못 본다**(영상 유실).
  ② 페이지네이터가 "총 20건"을 표시하는데 실제 순회 가능 건수는 12건이라 사용자 혼란.
  ③ 프레임 0건 영상이 늘어날수록 빈 페이지 구간이 커진다(현재도 APPROVED 20건 중 8건이 프레임 0건).
  보안 영향은 없다.
- **수정 방향(제안)**: 제외 조건을 **DB 쿼리로 내린다** — `findAllWithReviewStatus` 계열에 `EXISTS (select 1 from LsDataSrc s where s.rawSn = v.rawSn)` 조건을 추가해 페이징·카운트가 같은 술어를 쓰게 한다. 그러면 `totalElements` 가 12 로 일관되고 빈 페이지도 사라진다. 애플리케이션 필터를 유지해야 한다면 최소한 `totalElements` 를 필터 후 기준으로 별도 count 쿼리로 산출하고, 현재 주석("totalElements 는 원본 기준 유지")을 정책으로 확정할지 사용자 판단을 받을 것.

---

## 4. 근거(file:line) 드리프트 — 카탈로그 정합성 결함

> `SecurityConfig.java`(F-1 전건: 126·132·134-135·147-153)와 `AiInferenceDeidentReportGateTest.java:339-351` 은 **정확 일치**. 아래는 어긋난 항목.

| TC | 카탈로그 근거 | 실제 위치 |
|----|---|---|
| TC-PORTAL-009 | `PortalUploadController.java:138-143` | `PortalUploadController.java:146-151` |
| TC-PORTAL-020 | `PortalLabelService.java:130-161` | `:131-162` |
| TC-PORTAL-021 | `:147-157` | `:148-158` |
| TC-PORTAL-022 | `:143-145` | `:144-146` |
| TC-PORTAL-023 | `:104-108` | `:105-108` |
| TC-PORTAL-024 | `:101-103` | `:102-104` |
| TC-PORTAL-025 | `:284-292` | `:285-293` |
| TC-PORTAL-026 | `:293-300` | `:294-301` |
| TC-PORTAL-027 | `:256-259` | `:257-260` |
| TC-PORTAL-028 | `:250-251` | `:251-252` |
| TC-PORTAL-029 | `:414-460` | `:430-501` |
| TC-PORTAL-030 | `:420-423` | `:436-439` |
| TC-PORTAL-031 | `:430-434` | `:446-450` |
| TC-PORTAL-032/033 | `:438-439` | `:457-459` (+ base 세팅 `:85-97`) |
| TC-PORTAL-034 | `:440-443` | `:460-471` |
| TC-PORTAL-035 | `:261-266` | `:262-267` |
| TC-PORTAL-036 | `:425-427` | `:441-443` |
| TC-PORTAL-037 | `:452-456` | `:493-497` |
| TC-PORTAL-038 | `DeidentReportGate.java:23-37` | `:24-38` |

`PortalLabelService.java` 는 앞부분(~L108)이 **+1행**, `serveFrameImage` 구간이 **+16~+41행** 밀렸다(2026-07-30 이후 javadoc·게이트 주석 추가분).

---

## 5. 테스트 커버 대조 (`_raw/test-baseline.md` — 전건 통과 상태)

| TC | 커버 테스트 (파일:메서드) |
|----|---|
| 001~004 | `auth/SecurityConfigChannelTest.java:portalChannelAllowedOnPortal` / `internalChannelForbiddenOnPortal` / `portalChannelForbiddenOnInternalVideos` / `portalChannelForbiddenOnManage` |
| 005~007 | `portal/PortalLabelControllerTest.java:portalTokenBlockedFromInternalSam2AndAutolabel` / `portalUserBlockedFromInternalApi` / `versionApiHiddenFromPortalUser` |
| 009 | `portal/PortalUserLabelServiceTest.java:saveUserLabel_noToken_rejected` |
| 010·011 | `frontend/src/router/__tests__/portalGuard.test.tsx` (4 케이스) |
| 020·021 | `portal/PortalDatamartVideosServiceTest.java:listDatamartVideos_approvedOnly_enriched` / `_pendingExcludedByGate` / `_zeroFrameExcluded` ⚠ **페이징 메타 단언 없음** → F-ISSUE-02 미커버 |
| 023·024 | `portal/PortalUserLabelServiceTest.java:loadDatamartLabels_returnsDtoList` / `_nullRawSn_rejected` ⚠ **게이트 케이스 없음** → F-ISSUE-01 미커버 |
| 025~028 | `portal/PortalFrameLabelsServiceTest.java:loadFrameLabels_userLabel_takesPrecedence` / `_noUserLabel_returnsDatamart` / `_notApproved_forbidden` / `_frameMissing_notFound` |
| 029~034 | `portal/PortalFrameImageServiceTest.java:serveFrameImage_approvedVideo_returnsDeidImage` / `_absoluteDeidPath_underDeidentifiedDir_returns200` / `_rawFrameSubtree_forbidden` / `_symlinkToRawFrame_forbidden` / `_traversalOutsideBase_forbidden` / `_fileMissing_notFound` / `_notApproved_forbidden` / `_noDeidPath_notFound` |
| 036·037 | `portal/PortalFrameImageCacheControlTest.java:portalFrameImageIsNotCached` / `portalFrameImageBlockedUnderDeidentReport` |
| 038 | `label/service/AiInferenceDeidentReportGateTest.java:segmentNotBlockedByOriginReport` (동일 게이트 컴포넌트 공유) |
| 035 | 전용 단위 테스트 미발견(`DeidentReportGateCoverageIT` 가 게이트 배선 자체는 커버) — 실동작으로 412 확인 |

---

## 6. 참고 관찰 (이슈 미승격)

- **UNCERTAINTIES #12 (rate limit 부재) 미해소 유지 확인**: `PortalLabelController` 의 `/datamart/videos`·`/datamart/labels`·`/user-labels`·`/frames/{srcSn}/labels`·`/frames/{srcSn}/image` 5개 핸들러 어디에도 rate limit 어노테이션·필터가 없다(업로드·SAM2·TUS 에만 존재). 프레임 이미지 서빙은 `no-store` 라 매 요청 서버 I/O 가 발생하므로 열거·대량 다운로드 억제 수단이 없다. 07-30 판정 그대로 **확인필요**로 이월.
- **신고 구간 영상이 데이터마트 목록에는 노출된다**: rawSn=900(`'F'` + `APPROVED`)이 `/datamart/videos` 결과에 그대로 포함되고, 진입하면 프레임 라벨/이미지에서 412 를 만난다. 관제 뷰 쪽 확정 정책(신고 필터 미적용)과 방향은 같으므로 **결함으로 보고하지 않는다**. 다만 포털 FE 가 412 를 graceful 처리하는지는 H 클러스터에서 확인 대상.
- **`/v1/portal/auth/**` 는 `SecurityConfig.java:90` 에서 permitAll** — 본 파트 케이스 대상은 아니나 채널 게이트 밖에 있는 유일한 포털 경로이므로 A 클러스터와 교차 확인 권장.

---

## 7. 집계

| 판정 | F-1 | F-2 | 합계 |
|---|--:|--:|--:|
| PASS | 11 | 18 | **29** |
| PARTIAL | 0 | 1 | **1** |
| FAIL / BLOCKED / N/A / 확인필요 | 0 | 0 | **0** |
| **계** | **11** | **19** | **30** |

- 이슈 2건: **F-ISSUE-01(HIGH)** · **F-ISSUE-02(MEDIUM)**
- ⚠ F-ISSUE-01 은 **30개 케이스 중 어느 것으로도 잡히지 않는 커버리지 갭**에서 발견됐다(반증 과정에서 형제 엔드포인트 간 게이트 비대칭을 대조해 노출). 카탈로그 F-2 에 `/v1/portal/datamart/labels` 게이트 케이스 신규 등재가 필요하다.
- 근거 드리프트 **19건**(대부분 +1행, `serveFrameImage` 구간 +16~+41행).
