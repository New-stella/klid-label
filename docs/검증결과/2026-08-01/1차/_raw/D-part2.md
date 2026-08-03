# D 클러스터 part2 — D-3 검수 배정(TC-ASSIGN 26건) + D-7 관제 조회 API(TC-NOTIFY 8건)

> 검증일 2026-08-01 · 담당 범위 34건 · 이슈 ID `D-ISSUE-21~40`
> 코드 기준 워크스페이스 `qa-0801` (HEAD `56d30478`) · 실동작 기준 로컬 풀스택

## 0. 환경 실측 (판정 전제 — stack-bringup.md 대비 **정정**)

`stack-bringup.md` 말미의 "⚠ 스택 재빌드 보류(구버전 V146 컨테이너로 검증 진행)" 기록은 **이번 검증 시점에는 더 이상 유효하지 않다.**

| 항목 | 실측 |
|---|---|
| 컨테이너 생성시각 | `klid-backend`/`klid-frontend`/`klid-ai-server`/`klid-mock-server` 모두 **2026-08-01 23:10:06 재빌드** (`docker ps --format '{{.CreatedAt}}'`) |
| Flyway 최신 | **V158** `add ls data aug dscd lookup index` (구 기록의 V146 아님) |
| `ls_data_ingest` | 테이블 존재(0행) — V147/V148 적용됨 |
| backend health | `GET /api/actuator/health` → `{"status":"UP"}` |
| mock-server | `:9400/health` → `{"status":"ok"}` |
| DB | `docker exec klid-postgres psql -U klid_user -d klid_system`, 스키마 `public` |

→ **본 파트 34건은 전부 워크스페이스 코드와 동일 빌드 위에서 실동작 판정했다.** BLOCKED 0건.

토큰: `POST /v1/dev/tokens` 로 발급(`userNo` 지정 가능). 인가 역할의 실제 진실원은 `LS_USER_ROLE`
(`UserRoleResolver`)이며 실측 매핑은 1001/1002=REVIEWER · 2001/2002=WORKER · 3001=PORTAL_USER.

---

## 1. D-3. 검수 배정 (TC-ASSIGN) — 26건

| ID | 판정 | 근거 확인 | 실측 요약 |
|----|:--:|------|------|
| TC-ASSIGN-001 | PASS | [실동작] | `POST /v1/assignments {workerId:2001,rawDataIds:[43]}` → **201**. DB: `ls_task_assignment(47, raw 43, 2001, LABELER)` INSERT · `ls_raw_data_status(43)=ASSIGNED(ver 1)` · `ls_task_event_log(77, ASSIGN, actor 1001, subject 2001)` 3종 모두 적재 확인 |
| TC-ASSIGN-002 | PASS | [실동작] | WORKER(2001) 토큰으로 POST → **403** `FORBIDDEN "권한이 없습니다."` (컨트롤러 `@PreAuthorize("hasRole('REVIEWER')")` 1차 + `AssignmentService:675-677` 2차 이중 방어) |
| TC-ASSIGN-003 | PASS | [실동작]+[정적] | 토큰 없이 POST → **401** `UNAUTHORIZED`. 서비스 진입부 NPE 가드 실재: `AssignmentService.java:672-674` `if (actor == null) throw UNAUTHORIZED`. 단위 커버: `AssignmentServiceTest#requireReviewer_actor가_null이면_UNAUTHORIZED` |
| TC-ASSIGN-004 | PASS | [실동작] | `workerId=999999` → **400** `INVALID_INPUT "존재하지 않는 작업자입니다."` (`:76-78`) |
| TC-ASSIGN-005 | PASS | [실동작] | `reviewerId=999999` → **400** `INVALID_INPUT "존재하지 않는 검수자입니다."` (`:79-81`) |
| TC-ASSIGN-006 | PASS | [실동작] | raw43+2001 재배정 요청 → **409** `CONFLICT`. UK(RAW_DATA_ID,USER_NO,TASK_TYPE_CD) 충돌이 `DataIntegrityViolationException`→409 로 변환됨(`:101-103`). ⚠ 같은 catch 가 FK 위반도 삼킨다 → **D-ISSUE-21** |
| TC-ASSIGN-007 | PASS | [실동작] | `reviewerId:1001` 동봉 배정(raw44) → LABELER(49) + **REVIEWER(50) 2행** INSERT 확인 |
| TC-ASSIGN-008 | PASS | [실동작] | 같은 raw44 에 worker 2002 + 동일 reviewerId 1001 재요청 → **201**, DB 는 49/50/51 3행(REVIEWER 중복 INSERT 없음, worker 배정 보존). `assignReviewers` 사전 조회 skip 경로(`:206-219`) 동작 |
| TC-ASSIGN-009 | PASS | [실동작] | `PATCH /v1/assignments/47 {workerId:2002}` → **200**. `ls_task_assign_history(hstry_seq 8, authrt_seq 47, prev 2001→new 2002, chg_user 1001)` + `ls_task_event_log(86, REASSIGN, subject 2002, prev 2001)` + `ls_task_assignment(47).user_no=2002, ver=1` 3종 확인 |
| TC-ASSIGN-010 | PASS | [실동작] | APPROVED 영상(raw26)의 배정 29 재배정 → **409** `ASSIGNMENT_ALREADY_COMPLETED "완료된 작업은 재배정할 수 없습니다."` |
| TC-ASSIGN-011 | PASS | [실동작] | `PATCH /v1/assignments/999999` → **404** `NOT_FOUND "배정을 찾을 수 없습니다."` |
| TC-ASSIGN-012 | PASS | [실동작] | 현재 배정자와 동일 workerId → **400** `INVALID_INPUT "현재 배정된 작업자와 동일합니다."` |
| TC-ASSIGN-013 | PASS | [실동작] | raw44 배정 49(2001)를 2002 로 재배정 시도(2002 는 이미 raw44 LABELER) → **409** `CONFLICT "선택한 작업자는 이미 해당 영상에 배정되어 있습니다."` (사전 조회 차단, flush 도달 전) |
| TC-ASSIGN-014 | PASS | [실동작] | 동일 배정 47 에 **동시 PATCH ×4** → `200 ×1 / 409 ×3`(전부 `"다른 사용자가 먼저 재배정했습니다"`). DB: `ls_task_assign_history` **+1행만**(8→10, 총 2행) · `ls_task_event_log` **+1건만**(90) · `ver 1→2`. 패자 tx 전체 롤백 확인 |
| TC-ASSIGN-015 | PASS | [실동작] | WORKER 2002 → 배정47(현재 2001) 이력 조회 **403** `"본인 배정 이력만 조회할 수 있습니다."` / 본인(2001) 조회는 **200** (대조군) |
| TC-ASSIGN-016 | PASS | [실동작] | REVIEWER 이력 조회 → 3건이 `occurredAt` **오름차순**(23:55:22 ASSIGN → 23:55:53 REASSIGN → 23:56:47 REASSIGN), actor/subject/prev 3축 사용자명 모두 해석됨. N+1 회피: `userRepository.findByUserNoIn(Set)` 1회 batch(`:340-343`) |
| TC-ASSIGN-017 | PASS | [실동작] | `/0/history`·`/-1/history` → **400** `"잘못된 배정 ID 입니다."`(`:316-318`) · `/abc/history` → **400** `"파라미터 형식이 올바르지 않습니다: assignmentId"` (500 아님) |
| TC-ASSIGN-018 | PASS | [실동작] | WORKER 2001 이 `?workerId=2002` 로 목록 요청 → **200**, `totalElements=15`, 응답 workerId 집합 = `[2001]`. 파라미터가 403 이 아니라 **무시**되고 self 로 고정(`scopeForActor:475-477`) |
| TC-ASSIGN-019 | PASS | [실동작] | REVIEWER 전체 → total 26, workerIds `[2001,2002]`, taskTypeCd 전부 `LABELER` / `?workerId=2002` → total 11, workerIds `[2002]` |
| TC-ASSIGN-020 | PASS | [실동작] | PORTAL_USER 토큰 목록·이력 → **403** `"권한이 없습니다."` / 토큰 없음 → **401** |
| TC-ASSIGN-021 | PASS | [실동작] | 상태행이 없던 raw52 에 worker 2001·2002 **동시 배정** → `201 ×1 / 409 ×1`. DB 최종 assignment 1행 + `ls_raw_data_status(52)` 1행(ASSIGNED). PK 제약이 직렬화 지점으로 동작, 패자는 재시도 가능한 409. ⚠ 이때도 메시지가 "이미 동일 작업자에게 배정된 영상" 으로 오도 → **D-ISSUE-21** |
| TC-ASSIGN-022 | PASS | [실동작] | APPROVED raw48 배정 → **409** `ASSIGNMENT_ALREADY_COMPLETED "검수 완료된 영상은 배정할 수 없습니다."`, `ls_raw_data_status(48)` **APPROVED 유지**(ASSIGNED 강등 없음 — D-ISSUE-01 해소 확인) |
| TC-ASSIGN-023 | PASS | [실동작] | `rawDataIds=[46,48(APPROVED),47]` → **409**, 이후 `ls_task_assignment where raw_data_id in (46,47,48)` **0행**(부분성공 없음). 상태 조회는 `findByRawDataIdIn` 단일 IN 쿼리(`:149`) |
| TC-ASSIGN-024 | PASS | [실동작] | **경합 재현 성공**: raw43(IN_REVIEW) 에 `approve` 와 `assign(worker 2001)` 동시 발사 → approve **200**, assign **409** `CONFLICT "다른 사용자가 먼저 해당 영상의 상태를 변경했습니다"`(500 아님). DB: status `APPROVED(ver 4)`, 신규 assignment **미생성**. `OptimisticLockingFailureException` 국소 catch(`:104-113`) 실동작 확인 |
| TC-ASSIGN-025 | PASS | [실동작] | 2라운드 실측. ①raw55: reassign 선커밋 → 둘 다 200이고 approve 응답의 workerName 이 **재배정 후 작업자(정작업)** — 순서 역전 없음. ②raw66(reassign 0.15s 지연): approve **200** 선커밋 → reassign **409** `ASSIGNMENT_ALREADY_COMPLETED`, `user_no` 2001 **불변**. 잠금 원천 `LsRawDataStatusRepository.findByRawDataIdForShare` = `@Lock(PESSIMISTIC_READ)`(PG `FOR SHARE`), 호출부 `AssignmentService:235-240` |
| TC-ASSIGN-026 | PASS | [실동작] | raw43 을 submit→start 로 **IN_REVIEW** 전이 후 배정47 재배정 → **200 허용**, 상태 IN_REVIEW 유지. 카탈로그 기대결과("현행 **허용**")와 일치 → PASS. 단 **D-ISSUE-05(정책 미확정) 이월 유지** — 아래 §3 참조 |

**D-3 집계**: PASS 26 / FAIL 0 / PARTIAL 0 / BLOCKED 0

---

## 2. D-7. 관제 조회 API (TC-NOTIFY, 조회) — 8건

> ★ UNCERTAINTIES #4 **반전 항목**이라 IDOR 을 최우선으로 실동작 반증했다.
> 토글 실측: `CONTROL_NOTIFY_ENABLED=true` → 컨트롤러 활성 상태에서 검증.

| ID | 판정 | 근거 확인 | 실측 요약 |
|----|:--:|------|------|
| TC-NOTIFY-026 | PASS | [실동작] | `GET /v1/tasks/26/summary` — REVIEWER **200**, 배정 보유 WORKER(2001) **200**. 응답 `{rawSn:26,status:"COMPLETED",totalFrames:5,labeledFrames:0,totalLabels:0,totalMeta:1,reviewerName:"김검수",lastModifiedAt:"2026-08-01T14:44:49.535483Z"}` — 카운트·상태·검수자·최종수정일 전 필드 실측값(self-fill 아님, `resolveReviewerName` 이 승인 이벤트→사용자 마스터 조인) |
| TC-NOTIFY-027 | PASS | [실동작] | `GET /v1/tasks/4/labels` 응답 전문을 키워드 스캔(`filePath`/`path`/`deIdntf`/`srcFile`) → **0건**. 실제 아이템은 `{srcSn,frameNo,labels:[{lblSn,lblTypeCd,label,points}]}` 뿐. DTO `TaskLabelsResponse.LabelItem` record 4필드로 구조적 강제(CWE-359) |
| TC-NOTIFY-028 | PASS | [실동작] | ①기본 `size=20, number=0, totalElements=30, totalPages=2` ②`size=10000` → **size 100 클램프** ③`frameIds=1,2` → `totalElements=2`, srcSns `[1,2]`(페이징 **전** 쿼리 조건으로 적용) ④`frameIds` 101개 → **400** `INVALID_INPUT` / 100개 → **200**(경계 정확). ⑤**반증**: raw4 요청에 raw26 프레임(296,297)을 섞어도 `total=1, srcSns=[1]` — rawSn 조건이 함께 걸려 교차 영상 누출 없음 |
| TC-NOTIFY-029 | PASS | [실동작] | `GET /v1/tasks/4/meta` → `metaSn/metaKey/metaVal` 목록 + `page=0,size=20,totalElements=7,totalPages=1`. `size=10000` → **size 100 클램프**. 정렬은 `metaSn ASC` 고정(`cappedMeta:192-199`) |
| TC-NOTIFY-030 | PASS | [실동작] | rawSn=999999 로 summary/labels/meta 3경로 전부 **404** `NOT_FOUND "영상을 찾을 수 없습니다."`(`findRawOrThrow:225-228`). rawSn=0·-1 도 404(500 없음) |
| TC-NOTIFY-031 | PASS | [실동작]+[정적] | 토큰 없음 → 3경로 **401** / PORTAL_USER → 3경로 **403** `"권한이 없습니다."`(`@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` :67,86,115). 토글 off 시 404 절은 정적 확인 — `@ConditionalOnProperty(name="authoring.control-notify.enabled", havingValue="true")`(Controller:46) + `application.yml:378` 기본값 `${CONTROL_NOTIFY_ENABLED:false}` → 빈 미등록 → 404 |
| TC-NOTIFY-052 | PASS | [실동작] | **핵심 반증 항목.** WORKER 2001(raw5 배정 없음)이 `GET /v1/tasks/5/{summary,labels,meta}` 호출 → **3경로 전부 403** `FORBIDDEN "본인에게 배정되지 않은 영상입니다."`. 동일 rawSn 을 REVIEWER 로 호출 시 **200**(가드가 역할 분기로 정확히 동작). 추가 반증: WORKER 2001 이 raw65 에 **`TASK_TYPE_CD='REVIEWER'` 배정만** 보유한 케이스도 **403** — 가드가 `TASK_LABELER` 만 인정(`LabelAccessGuard:92-93`)해 타입 혼동 우회 불가. **UNCERTAINTIES #4 반전 = 실동작으로 확정** |
| TC-NOTIFY-053 | PASS | [실동작] | `DE_IDENT_YN='F'` 인 raw9 → `labels` **412** `PRECONDITION_FAILED "비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."`. **역할 무관**(REVIEWER·배정 WORKER 둘 다 412). **인가 우선순위 반증**: 미배정 WORKER 2001 → raw8(F, 2002 배정) 은 412 가 아니라 **403** — 게이트가 인가를 대체하지 않음. `summary`/`meta` 는 **200**(좌표 미포함이라 대상 아님, 설계대로). 대조군 raw4(`'Y'`) labels **200** |

**D-7 집계**: PASS 8 / FAIL 0 / PARTIAL 0 / BLOCKED 0

### D-7 추가 반증 (전부 안전)
- `?sort=evil`, `?sort=1;DROP TABLE` → **200**(정렬 파라미터를 읽지 않고 `capped()`/`cappedMeta()` 가 `frameNo`/`metaSn` 고정 Sort 를 새로 만든다 — CWE-89 표면 없음)
- `?page=-1` → 200(0페이지) · `?page=99999` → 200 + `content:[]`(500 없음)

---

## 3. 이슈

### [D-ISSUE-21] TC-ASSIGN-006 / TC-ASSIGN-021 — 배정 API 가 FK 위반·PK 충돌을 전부 "중복 배정 409" 로 오분류
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 존재하지 않는 영상(rawSn)에 대한 배정은 **404 NOT_FOUND**(또는 400 INVALID_INPUT)여야 한다. 이미 workerId/reviewerId 는 존재 검증 후 400 을 주므로(`:76-81`) rawDataIds 만 검증이 빠진 것은 계약 비대칭이다. 또 상태행 PK 충돌(동시 배정)은 "중복 배정" 과 원인이 달라 재시도 안내가 달라야 한다. 잘못된 메시지는 운영자가 존재하지 않는 중복 배정을 찾아 헤매게 만든다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:101-103` 의 `catch (DataIntegrityViolationException)` 가 **UK 충돌·FK 위반·PK 충돌을 구분 없이** 삼켜 동일 문구로 409 를 낸다.
  ```java
  } catch (DataIntegrityViolationException e) {
      log.warn("[Assignment] duplicate assignment detected workerId={}", req.workerId());
      throw new CustomException(ErrorCode.CONFLICT, "이미 동일 작업자에게 배정된 영상이 있습니다.");
  }
  ```
  실측 ①존재하지 않는 rawSn:
  ```
  POST /v1/assignments {"workerId":2001,"rawDataIds":[999999]}
  → 409 {"errorCode":"CONFLICT","message":"이미 동일 작업자에게 배정된 영상이 있습니다."}
  backend log: ERROR ... violates foreign key constraint "fk_ls_task_assignment_raw"
               WARN  [Assignment] duplicate assignment detected workerId=2001
  ```
  실측 ②동시 배정(raw52, 상태행 부재): 두 요청 중 하나가 `LS_RAW_DATA_STATUS` PK 충돌로 실패했는데 메시지는 동일하게 "이미 동일 작업자에게 배정된 영상". 부수적으로 매 발생마다 **ERROR 레벨 SQL 스택 로그**가 남아 정상 경합에도 오탐 알람이 발생한다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/assignments -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"workerId":2001,"rawDataIds":[999999]}'
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select count(*) from ls_data_raw where raw_sn=999999;"   -- 0
  ```
- **영향**: 기능/운영. API 계약 위반(`rules/api-design.md` — 리소스 없음=404), 오도성 에러 메시지, ERROR 로그 오탐. 데이터 정합은 tx 롤백으로 보존되어 보안 영향은 없음(CWE-209 는 경미 — 내부 정보를 노출하는 방향이 아니라 은폐하는 방향).
- **수정 방향(제안)**: `assign()` 진입부 `rejectApprovedTargets` 옆에 `videoRepository.findAllById(rawDataIds)` 로 **존재 검증(단일 IN 쿼리)** 을 추가해 미존재 시 404/400 을 먼저 던진다. 그리고 `catch (DataIntegrityViolationException)` 에서 `e.getMostSpecificCause().getMessage()` 의 제약명(`uk_...` vs `fk_...` vs `..._pkey`)으로 분기하거나, 상태행 upsert 를 `save` 대신 조건부 INSERT 로 분리해 UK 충돌만 "중복 배정" 문구를 쓰게 한다. ⚠ 구현하지 않음.

### [D-ISSUE-22] TC-ASSIGN-001 / TC-ASSIGN-007 / TC-ASSIGN-009 — 배정 대상 사용자의 **역할·활성 여부**를 검증하지 않아 PORTAL_USER·REVIEWER 를 LABELER 로 배정할 수 있다
- **심각도**: MEDIUM (CWE-863 Incorrect Authorization / 업무규칙 미집행)
- **기대 동작(기대효과)**: `CLAUDE.md` 작업 배정 규칙은 "**REVIEWER 가 WORKER 에게** 배정"이다. `LS_TASK_ASSIGNMENT.TASK_TYPE_CD='LABELER'` 행의 `USER_NO` 는 **WORKER 역할 + 활성(`USE_YN='Y'`) 사용자**여야 하고, `'REVIEWER'` 행은 REVIEWER 여야 한다. 역할 진실원(`LS_USER_ROLE`)이 이미 존재하므로 검증이 가능하다 — 실제로 작업자 선택 드롭다운을 채우는 `UserRepository.findAllWorkersWithTaskCount()` 는 `LsUserRole.roleCd='WORKER' AND u.useYn='Y'` 를 **이미 걸고 있다**. 즉 FE 목록은 필터링되는데 API 는 무검증인 전형적 비대칭이다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:76-81` 이 **존재 여부만** 본다.
  ```java
  if (userRepository.findByUserNo(req.workerId()).isEmpty()) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "존재하지 않는 작업자입니다.");
  }
  if (req.reviewerId() != null && userRepository.findByUserNo(req.reviewerId()).isEmpty()) { ... }
  ```
  `findByUserNo` 는 `USE_YN` 필터도, `LS_USER_ROLE` 조인도 없다. 재배정(`:255-257`)도 동일. 실측:
  ```
  POST /v1/assignments {"workerId":3001,"rawDataIds":[69]}  → 201  (3001 = PORTAL_USER)
  POST /v1/assignments {"workerId":1002,"rawDataIds":[69]}  → 201  (1002 = REVIEWER)
  POST /v1/assignments {"workerId":2001,"rawDataIds":[69],"reviewerId":2002} → 201 (2002 = WORKER 가 REVIEWER 행으로 INSERT)
  DB: ls_task_assignment(64,69,3001,LABELER) (65,69,1002,LABELER) (67,69,2002,REVIEWER)
  DB: ls_user_role → 1002=REVIEWER, 2001/2002=WORKER, 3001=PORTAL_USER
  ```
- **재현/확인 경로**: 위 curl 3줄 + `select a.*, r.role_cd from ls_task_assignment a join ls_user_role r on r.user_no=a.user_no where a.raw_data_id=69;`
- **영향**:
  - **데드락 워크플로**: PORTAL_USER 에게 배정된 영상은 그 사용자가 `/v1/reviews/{rawSn}/submit` 등 내부 API 를 호출할 수 없어(PORTAL 채널 403) **ASSIGNED 로 영구 정체**한다. 배정 취소 API 가 없어 재배정으로만 회수 가능.
  - **집계 오염**: 작업자별 배정 카운트·작업목록·이력에 비-WORKER 가 섞인다.
  - **직무분리(SoD)**: REVIEWER 를 LABELER 로 배정하면 라벨링·승인 동일인이 된다. 단 `LabelAccessGuard` 가 REVIEWER 에 전면 통과를 주는 현행 설계상 **새로운 권한 상승은 발생하지 않는다**(기존 정책의 귀결) — 그래서 HIGH 가 아니라 MEDIUM.
  - 비활성 사용자(`USE_YN='N'`) 배정도 동일 경로로 가능(시드에 비활성 사용자가 없어 실측은 미수행, 정적 확인).
- **수정 방향(제안)**: `assign`/`reassign` 의 사용자 검증을 존재 확인에서 **역할·활성 확인**으로 승격한다 — `LsUserRoleRepository.findByUserNo(workerId)` 가 `WORKER` 인지(+`MngAcctUser.useYn='Y'`) 검사하고 아니면 400. `reviewerId` 는 `REVIEWER` 로 동일 검사. 드롭다운 쿼리(`findAllWorkersWithTaskCount`)와 **같은 술어**를 공유하는 단일 판정 지점으로 추출해 FE/BE 드리프트를 막는다. ⚠ 구현하지 않음.

### [D-ISSUE-23] D-3 전 26건 — 카탈로그 근거 `file:line` 전면 드리프트
- **심각도**: LOW (카탈로그 정합성)
- **기대 동작(기대효과)**: 케이스의 `근거` 컬럼은 검증자가 바로 열어볼 수 있는 위치여야 한다. 어긋나면 검증자가 매번 재탐색해야 하고, 최악의 경우 **엉뚱한 코드를 근거로 PASS** 를 준다.
- **현재 동작(이슈 내용)**: D-3 의 `AssignmentService.java:*` 근거가 **26건 전부 어긋난다**(+14~+58행). 원인은 2026-07-31 이후 `rejectUnenrolledDerivatives`(현재 `:175-186`) 신설 + 재배정 게이트 주석 확장(커밋 `bd7ba946`·`284d81e2`·`31ecee47`).

  | TC | 카탈로그 | 실제(qa-0801 `56d30478`) |
  |---|---|---|
  | 001 | 57-114 | **71-129** |
  | 002 | 59 | **73**(호출) / **675-677**(판정) |
  | 003 | requireReviewer | **668-678** |
  | 004 | 62-64 | **76-78** |
  | 005 | 65-67 | **79-81** |
  | 006 | 86-88 | **101-103** |
  | 007 | 102-104,150-175 | **117-119, 195-220** |
  | 008 | 161-174 | **206-219** |
  | 009 | 177-244 | **222-302** |
  | 010·025·026 | 190-195 / 187-195 | **235-240** |
  | 011 | 182-183 | **227-228** |
  | 012 | 200-202 | **258-260** |
  | 013 | 204-217 | **265-275** |
  | 014 | 225-241 | **283-299** |
  | 015 | 264-270 | **322-328** |
  | 016 | 257-303 | **315-361** |
  | 017 | 258-260 | **316-318** |
  | 018·019 | 305- | **377-** |
  | 020 | 305- | **470-482**(`scopeForActor`) |
  | 021 | upsertDataStts | **663-666** |
  | 022 | 69,130-141 | **83, 145-156** |
  | 023 | 130-141 | **145-156** |
  | 024 | 89-98 | **104-113** |

  (대조: **D-7 의 `TaskQueryController`/`TaskQueryService`/`LabelAccessGuard` 근거 8건은 전부 정확**. `TaskQueryService.java:107-143`(TC-NOTIFY-027)만 실제 `107-137` + `234-245` 로 소폭 어긋남.)
- **재현/확인 경로**: `sed -n '71,129p' backend/src/main/java/kr/co/cudo/authoring/assignment/service/AssignmentService.java`
- **영향**: 검증 효율/정확도. 기능 영향 없음.
- **수정 방향(제안)**: `D-review-version-notify.md` D-3 표의 근거 컬럼을 위 대응표로 일괄 갱신한다. 근본적으로는 라인 번호 대신 **메서드명 앵커**(`AssignmentService#rejectApprovedTargets`)로 표기하면 드리프트가 사라진다. ⚠ 구현하지 않음.

### [D-ISSUE-24] TC-NOTIFY-028 — `frameIds` 상한 초과 400 응답에 컨트롤러 메서드명이 노출된다
- **심각도**: LOW (CWE-209, 경미)
- **기대 동작(기대효과)**: `rules/api-design.md`·`security.md` — 에러 응답에 내부 구현 정보(클래스·메서드·경로)를 담지 않는다. 사용자에게 보여줄 문구만 내려야 한다.
- **현재 동작(이슈 내용)**: `@Validated` + 파라미터 `@Size` 위반이 `ConstraintViolationException` 으로 전역 핸들러를 타면서 위반 경로가 그대로 실린다.
  ```
  GET /v1/tasks/4/labels?frameIds=<101개>
  → 400 {"errorCode":"INVALID_INPUT",
         "message":"getLabels.frameIds: frameIds 는 최대 100개까지 지정할 수 있습니다."}
  ```
  `getLabels.` 접두는 `TaskQueryController#getLabels` 메서드명이다(`TaskQueryController.java:87-92`).
- **재현/확인 경로**:
  ```bash
  IDS=$(python3 -c "print(','.join(str(i) for i in range(1,102)))")
  curl -s -G http://localhost:18081/api/v1/tasks/4/labels --data-urlencode "frameIds=$IDS" -H "Authorization: Bearer $REV"
  ```
- **영향**: 보안(정보 노출) — 실질 위험은 낮으나 Fortify/CodeQL 의 *System Information Leak* 룰에 걸릴 수 있는 형태. 같은 패턴이 `@Validated` 를 쓰는 다른 컨트롤러 전반에 있을 가능성이 높다(전역 이슈).
- **수정 방향(제안)**: `GlobalExceptionHandler` 의 `ConstraintViolationException` 핸들러에서 `propertyPath` 의 **마지막 노드(파라미터명)만** 남기거나 메시지만 취해 응답을 조립한다(`getLabels.frameIds` → `frameIds`). ⚠ 구현하지 않음. (A 클러스터 TC-EXC 담당과 중복 가능 — 병합 시 확인)

---

## 4. 이월 이슈 대조

| 이월 이슈 | 케이스 | 이번 회차 상태 |
|---|---|---|
| **D-ISSUE-05** IN_REVIEW 상태 재배정 허용 | TC-ASSIGN-026 | **미해소 이월.** 실동작 재확인 — raw43 을 `submit`→`start` 로 IN_REVIEW 전이시킨 뒤 `PATCH /v1/assignments/47 {workerId:2002}` → **200**, 상태 IN_REVIEW 유지. 가드(`:235-240`)는 `STTS_APPROVED` 만 차단한다. 부작용 실측: 재배정 후 승인 응답의 `workerName` 이 **제출자가 아니라 새 배정자**(정작업)로 표시되어 "누가 제출한 검수인지" 가 뒤바뀐다. 새 이슈 ID 를 부여하지 않고 D-ISSUE-05 로 이월 유지 — **정책 확정 필요**(IN_REVIEW 재배정을 허용할지, 허용 시 검수 제출 이력의 작업자 표시를 제출 시점으로 동결할지) |
| **D-ISSUE-01** APPROVED 무검증 ASSIGNED 강등 | TC-ASSIGN-022 | **해소 확인.** 409 + APPROVED 유지 실측 |
| **D-ISSUE-02** 동시 재배정 이력 중복 | TC-ASSIGN-014 | **해소 확인.** 4병렬 → 이력 1행 |
| **D-ISSUE-45** 관제 조회 무페이징 | TC-NOTIFY-028/029 | **해소 확인.** labels·meta 모두 기본 20 / 상한 100 클램프 실측 |
| **D-ISSUE-41** 검수자명 null 하드코딩 | TC-NOTIFY-026 | **해소 확인.** `reviewerName:"김검수"` 가 승인 이벤트 → 사용자 마스터 실조회로 채워짐 |
| **UNCERTAINTIES #4** 관제 조회 IDOR(반전) | TC-NOTIFY-052 | **반전 사실 실동작 확정.** 세 경로 모두 `verifyRawAccess` 배선, 미배정 WORKER 403. UNCERTAINTIES 원본을 "✅ 확정 — 실동작 검증 완료(2026-08-01)" 로 갱신 제안 |

---

## 5. 테스트 커버 대조 (`_raw/test-baseline.md` 기준 — 전건 통과, 실패 0)

| 범위 | 커버 테스트 |
|---|---|
| TC-ASSIGN-001/002/004/006/009/015/016/018 | `AssignmentControllerTest`(REVIEWER 배정 LABELER INSERT · WORKER 403 · 중복 409 · 재배정 이력 · IDOR 403/200 · 본인 배정만 반환) |
| TC-ASSIGN-003/007/010/016/017/020/022/023 | `AssignmentServiceTest`(actor null UNAUTHORIZED ×2 · reviewerId 반영 · 완료 배정 거부 · APPROVED 409 및 상태 유지 · 전체실패 · 이력 시간순 · PORTAL 403) |
| TC-ASSIGN-014/025 | `AssignmentReassignConcurrencyIT`(4병렬 1건만 200·이력 1행 / VER 매핑 / **공유잠금으로 승인 UPDATE 직렬화 H4-b**) |
| TC-ASSIGN-024 | `AssignmentOptimisticLockConflictIT`(assign 중 낙관적잠금 충돌 → 500 아닌 409) |
| TC-ASSIGN-005/008/011/012/013/019/021/026 | **전용 자동테스트 미확인** — 이번 회차 실동작으로만 검증됨(회귀 가드 부재, 신규 작성 후보) |
| TC-NOTIFY-026~031/052 | `TaskQueryControllerTest`(요약/라벨/메타 200 · 기본 20 · size 10000 클램프 · frameIds 필터 · 101개 400 · 404 · 401 · **배정되지 않은 WORKER 403** · 원본 경로 미포함) |
| TC-NOTIFY-026~030 | `TaskQueryServiceTest`(COUNT 집계 · 검수자명 실측/미지어냄 · frameIds 페이징 전 적용 · totalElements 정합 · 클램프 · 메타 페이징) |
| TC-NOTIFY-053 | `DeidentReportGateCoverageIT` 가 게이트 공유 호출부를 커버. 관제 라벨 경로 전용 단정은 미확인 → 신규 작성 후보 |

---

## 6. 검증 중 생성한 데이터 (기존 데이터 미변경, INSERT/상태전이만)

| 대상 | 내용 |
|---|---|
| `ls_task_assignment` | 47·49·50·51·54·59·62·64·65·66·67 신규(raw 43/44/52/55/66/69) |
| `ls_task_assign_history` | hstry_seq 8·10·14 등 재배정 이력 |
| `ls_task_event_log` | raw 43/44/52/55/66/69 의 ASSIGN·REASSIGN·검수 이벤트 |
| `ls_raw_data_status` | raw 43·52·55·66 신규 행 및 상태 전이(→APPROVED 등) |
| ⚠ 참고 | **D-ISSUE-22 반증용으로 raw69 에 비-WORKER 배정 3행(3001/1002 LABELER, 2002 REVIEWER)이 남아 있다.** 다른 클러스터 검증 시 이 행을 정상 시드로 오인하지 말 것 |
