# D 클러스터 part1 — D-1. 검수 워크플로우 (TC-REVIEW) 전수 검증

> 회차: **2026-08-03 3차** · 범위: `docs/test-cases/D-review-version-notify.md` **D-1 절(16~68행) 48건**
> (TC-REVIEW-001~030 · 035~052. TC-REVIEW-031~034 는 D-2 절이라 본 파트 범위 밖)
> 담당 성격: ★ **상태전이 반증 구간** — 불법 전이·동시 전이·권한·라벨 게이트를 실제 API 로 반증 시도

## 0. 검증 환경·방법

- 스택: `_raw/stack-bringup.md` 실측 그대로(backend `localhost:18081/api`, PostgreSQL `public` 스키마, mock-server :9400 실배선). 재기동/재빌드 없이 그대로 사용.
- 토큰: `POST /v1/dev/tokens` 로 REVIEWER(1001)·REVIEWER(1002)·WORKER(2001)·WORKER(2002)·PORTAL_USER(3001) 발급.
- **rawSn=101(pipeline-drive 공용 데이터)은 조회 대조에만 사용**하고, 상태전이는 전부 별도 영상으로 수행했다:
  - **rawSn=115**(ASSIGNED·WORKER 2001 배정·라벨 5건·프레임 6장) — 정상 전이 풀사이클 주 대상
  - **rawSn=31**(IN_REVIEW·2001) — 반려 사이클(2회 반려 → parent 이슈 계층)
  - **rawSn=33**(ASSIGNED·2001·라벨 0건) — 라벨 게이트(035/036/038) + COMPLETED 점프(004)
  - **rawSn=110**(라벨 1건) — 스냅샷 실패 경로(017) 재현
  - **rawSn=7**(PENDING·2001) — 동시성 반증(이중 submit/start)
- 코드·설정·마이그레이션 파일은 **일절 수정하지 않았다**. 검증용 데이터 준비(상태 강제 세팅·라벨 payload 손상·배정 1행 추가·공백 CCTV 명 1행)는 DB UPDATE/INSERT 로만 수행하고 **전부 원복**했다(§5 참조).
- 빌드/테스트는 실행하지 않았다. 자동테스트는 파일·메서드명 Grep 대조만 했다.

### 판정 집계

| 판정 | 건수 |
|------|:--:|
| PASS | **47** |
| FAIL | 0 |
| PARTIAL | **1** (TC-REVIEW-017) |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **48** |

- 실동작 커버리지: 48건 중 **46건을 실제 HTTP 요청 + DB 상태 + 백엔드/목업 로그로 판정**. 2건(TC-REVIEW-028 쿼리카운트·TC-REVIEW-049 내부 정합성 위반)은 외부 입력으로 도달 불가해 정적+기존 자동테스트 대조.
- 신규 결함 2건(D-ISSUE-02·03) + 이월 미해소 1건(D-ISSUE-01).

---

## 1. 케이스별 판정

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-REVIEW-001 | PASS | [실동작] | `POST /v1/reviews/115/submit`(WORKER 2001, ASSIGNED v3) → 200 `dataSttsCd=PENDING` v4. `LS_TASK_EVENT_LOG` `evnt_id=180 SUBMIT actor_user_no=2001 rsn=NULL` INSERT 확인 |
| TC-REVIEW-002 | PASS | [실동작] | rawSn=31 REJECTED(v6) → `submit`(2001) → 200 PENDING v7 |
| TC-REVIEW-003 | PASS | [실동작] | rawSn=115 APPROVED(v8) → `submit`(2001) → 200 PENDING v9. `LS_DATA_RAW.raw_sn=115` 불변, `orgnl_raw_sn` NULL 유지(새 작업 ID 미발급·버전업 아님) |
| TC-REVIEW-004 | PASS | [실동작] | `LS_RAW_DATA_STATUS.data_stts_cd='COMPLETED'` 강제(33) 후 submit → **400** `허용되지 않은 상태 전이입니다 (COMPLETED → PENDING).`. 같은 상태에서 `start` 도 400. 배치완료 점프 차단 확인 후 APPROVED 로 원복 |
| TC-REVIEW-005 | PASS | [실동작] | 115 IN_REVIEW(v7)에서 submit → **400** `(IN_REVIEW → PENDING)` |
| TC-REVIEW-006 | PASS | [실동작] | WORKER 2002(115 미배정) submit → **403** `본인에게 배정되지 않은 영상입니다.`(`verifyAssignedWorker` 614-627). 추가: 존재하지 않는 rawSn 999999 도 404 가 아니라 **403** — 존재 오라클을 만들지 않음(설계상 안전) |
| TC-REVIEW-007 | PASS | [실동작] | REVIEWER 토큰 submit → **403** `권한이 없습니다.` ⚠ 차단 지점은 **컨트롤러 `@PreAuthorize("hasRole('WORKER')")`**(ReviewController.java:221)이고 서비스 `"WORKER 권한이 필요합니다."`(618-620)는 HTTP 로 도달하지 않는다 → **카탈로그 기대문구 정정함**. PORTAL_USER 도 403 |
| TC-REVIEW-008 | PASS | [실동작] | Authorization 헤더 없이 submit → **401** `인증이 필요합니다.` |
| TC-REVIEW-009 | PASS | [실동작] | REVIEWER `POST /v1/reviews/115/start`(PENDING v6) → 200 `IN_REVIEW` v7 |
| TC-REVIEW-010 | PASS | [실동작] | WORKER 로 start → **403**. PORTAL_USER 도 403 |
| TC-REVIEW-011 | PASS | [실동작] | 115(IN_REVIEW v7·라벨 5건) approve → 200 `APPROVED` v8. **부수효과 전량 실측**: ①`LS_LABEL_VERSION` 3행 신규(`save_reason_cd=APPROVED, actvtn_yn=Y, ver_no=1`, src 508/510/511 — 라벨 없는 프레임은 미생성) ②`LS_EVNT_ANNO_REVIEW` AUTO_GENERATED→`APPROVED rvw_id=1001` ③`LS_DATA_META_REVIEW(54)` PENDING→`APPROVED rvw_id=1001` ④`LS_DATASET_VIDEO_META` active 스냅샷 신규(`meta_snpsht_sn=40, active_yn=Y`)에 `EVNT_ANNO_CN` 동결 ⑤`LS_TASK_EVENT_LOG APPROVE actor=1001` ⑥`ReviewApprovedEvent`→`DatasetExportBridge`→export **SUCCEEDED**(`export_sn=62, ver=1, frame_cnt=12`) ⑦**export 성공 후** `POST /api/data-set/v2/jobs/115/notify-completed` → mock-server **202**. 백엔드 로그 순서: `approved`(28.370) → `export succeeded`(28.383) → `TASK_COMPLETED sent`(28.386) — **1차 D-ISSUE-02/44/61(export FAILED인데 통지) 재발 없음** |
| TC-REVIEW-012 | PASS | [실동작] | PENDING(115 v4)에서 approve → **400** `(PENDING → APPROVED)` |
| TC-REVIEW-013 | PASS | [실동작] | APPROVED(115 v8) 재승인 → **409** `이미 APPROVED 된 영상은 재검수 재제출(PENDING) 외 상태 변경이 불가합니다.` |
| TC-REVIEW-014 | PASS | [실동작] | WORKER approve → **403**. PORTAL_USER 403 / 미인증 401 |
| TC-REVIEW-015 | PASS | [실동작] | `PUT /v1/videos/115/event-annotation` 으로 `LS_EVNT_ANNO(4)`+`LS_EVNT_ANNO_REVIEW(4, AUTO_GENERATED)` 생성 후 approve → 로그 `[EvntAnno] auto-approved on video approval rawSn=115 rvwSn=4 actor=1001`, `rvw_stts_cd=APPROVED`. 같은 tx 의 materialize 가 `LS_DATASET_VIDEO_META.EVNT_ANNO_CN` 을 payload 원문으로 **동결**(승인 전에는 스냅샷 행 자체가 없었음 → 동결 시점이 승인 tx 내부임이 확정) |
| TC-REVIEW-016 | PASS | [실동작] | 승인 시 `LS_DATA_META_REVIEW(54, VLM)` PENDING→`APPROVED`, 로그 `[Meta] auto-approved timeseries meta ... count=1`. **BE 엔드포인트 존치 확인**: `POST /v1/meta/54/approve` → **409** `이미 검토 완료된 메타입니다. status=APPROVED`(=경로 살아있음). **FE 진입점 부재 확인**: `frontend/src` 전수 grep 결과 `/v1/meta/{}/approve\|reject` 호출부 0건, 남은 것은 주석뿐(`features/auto/api.ts:79`·`features/label/components/TimeseriesSidePanel.tsx:58`) → 2차 서술 그대로 유효. 근거 라인 드리프트 정정(MetaController 81-106 → 83-89·103-110) |
| TC-REVIEW-017 | PARTIAL | [실동작][정적] | 직렬화 실패·10MB 초과 경로는 구현·단위테스트로 충족(`ReviewService.java:496-501` + `VersionService.snapshotFrameOnApprove` try/catch 274-283, `ReviewServiceEventPublishTest` "M2_스냅샷_스킵_발생시_WARN_로깅_경로_타고_승인은_정상_성공"·"M2_스냅샷_스킵_없으면_WARN_미발생"). 그러나 **스냅샷 DTO 구성(`LabelResponse.of`, VersionService.java:264-265)이 try 밖**이라 좌표 JSON 손상 시 승인 전체가 **500 + 롤백** — 3차에서 실측 재현(§3 D-ISSUE-01) |
| TC-REVIEW-018 | PASS | [실동작] | 31 IN_REVIEW 에서 `reject{"reason":"QA3 D1 1차 반려 - 라벨 누락"}` → 200 REJECTED v6 + `LS_DATA_ISSUE(21, issue_type_cd=REJECTION)` INSERT + `LS_TASK_EVENT_LOG(194) REJECT actor=1001 rsn=<사유>` |
| TC-REVIEW-019 | PASS | [실동작] | `{"reason":"   "}` → **400** `reason: 반려 사유는 필수입니다.`(@NotBlank) / `{}` 동일 400 / 1001자 → 400 `반려 사유는 최대 1000자까지 입력 가능합니다.`(@Size). 근거 `review/dto/RejectRequest.java:9-13` 확인(9=record 선언, 10=@NotBlank, 11=@Size, 12=필드, 13=`) {`) — `augment/dto/RejectRequest` 아님이 `ReviewController.java:18,314` 로 재확인됨 |
| TC-REVIEW-020 | PASS | [실동작] | 재제출→재검수시작→2차 반려 후 `LS_DATA_ISSUE`: `(21, up=NULL, '…1차 반려')` / `(22, up=21, '…2차 반려')` — `UP_DATA_ISSUE_SN` 계층 연결 |
| TC-REVIEW-021 | PASS | [실동작] | 115 PENDING(v4) → `cancel-submit`(2001) → 200 `ASSIGNED` v5. `LS_TASK_EVENT_LOG(181) CANCEL_SUBMIT actor=2001 rsn=NULL`(PII 미포함) |
| TC-REVIEW-022 | PASS | [실동작] | IN_REVIEW(115 v7)에서 cancel-submit → **400** `(IN_REVIEW → ASSIGNED)` |
| TC-REVIEW-023 | PASS | [실동작] | APPROVED(115 v8)에서 cancel-submit → **409 CONFLICT** |
| TC-REVIEW-024 | PASS | [실동작] | WORKER 2002(미배정)로 cancel-submit → **403** `본인에게 배정되지 않은 영상입니다.` |
| TC-REVIEW-025 | PASS | [실동작] | `GET /v1/reviews/115` — 본인 배정 WORKER 2001 → **200**(전체 필드 정상). REVIEWER 는 배정 무관 200 |
| TC-REVIEW-026 | PASS | [실동작] | 같은 115 를 WORKER 2002(미배정) 조회 → **403**. PORTAL_USER → **403** `권한이 없습니다.`(requireAssignedOrReviewer 마지막 분기 649). 미인증 → 401 |
| TC-REVIEW-027 | PASS | [실동작] | `GET /v1/reviews/999999/frames` → **404** `영상을 찾을 수 없습니다.` (단건 상세는 `검수 대상 영상을 찾을 수 없습니다.` 404 — 메시지 분리 정상). `frames` 를 WORKER 로 호출 → 403 |
| TC-REVIEW-028 | PASS | [정적] | `ReviewService.listFrames`(335-372): 영상 존재확인 1 + `findByRawSnOrderByFrameNoAsc` 1 + `findBySrcSnIn` 1. `LabelResponse.Item.from(entity, null, null, objectMapper)` 로 aiInfo/lsLabel 을 null 전달해 지연로딩 유발 없음. **실동작 보조 확인**: `GET /v1/reviews/115/frames` 응답 프레임 6건이 `frameNo` 오름차순(0,1,2,90,91,92)이고 라벨이 프레임별로 정확히 분배(2/0/2/1/0/0). 기존 IT `ReviewFramesControllerTest#listFrames_프레임_N개_라벨_M개_단일_쿼리_확인_N1_회피` 존재. ⚠ 쿼리 카운트 실측은 타 검증 에이전트와 DB·백엔드를 공유해 격리 불가 |
| TC-REVIEW-029 | PASS | [실동작] | `GET /v1/reviews?size=100` → 200 total 30(APPROVED 26·PENDING 2·REJECTED 2). `?status=PENDING` → total 2(103·7) 전부 PENDING. WORKER → 403 / PORTAL_USER → 403 |
| TC-REVIEW-030 | PASS | [실동작] | `GET /v1/reviews/31/issues` → 200, 반려사유 2건 등록일 **역순**(22→21) + `upDataIssueSn` 노출. WORKER → 403 |
| TC-REVIEW-035 | PASS | [실동작] | 33(IN_REVIEW·라벨 0건) approve(바디 없음) → **409 `errorCode=REVIEW_NO_LABEL`** `라벨이 없는 영상입니다. …`. 직후 DB = **IN_REVIEW v5 유지**(전이 전 판정 확인) |
| TC-REVIEW-036 | PASS | [실동작] | 같은 33 에 `{"noLabelConfirmed":true}` → 200 APPROVED v6. `LS_TASK_EVENT_LOG(200) APPROVE rsn='라벨 없음 확인 승인(negative sample)'`(감사 사유 기록) |
| TC-REVIEW-037 | PASS | [실동작] | 115(라벨 5건)에 `{"noLabelConfirmed":true}` → **400** `라벨이 있는 영상입니다. 최신 상태를 다시 확인한 뒤 승인하세요.`(플래그 상시전송 차단) |
| TC-REVIEW-038 | PASS | [실동작] | 33(PENDING·라벨 0건) approve → **400** `(PENDING → APPROVED)` — 409 아님. 상태전이 게이트가 라벨 게이트보다 먼저(471-478) |
| TC-REVIEW-039 | PASS | [실동작] | 바디 미첨부 approve(115, 라벨 존재) → 200 정상 승인. `@RequestBody(required=false)`(289) 하위호환 |
| TC-REVIEW-040 | PASS | [실동작] | `?sort=filePath,desc` → **200** + 무정렬 호출과 **동일 순서**(기본 `UPD_DT DESC` 폴백). `?sort=rawFilePathNm,asc` 동일. 백엔드 WARN 실확인: `[Sort] unsupported sort key ignored key=filePath`. **400 아님** — UNCERTAINTIES ★2 정책대로이며 결함 아님. 추가 반증: 정렬항 5개 → 200 + `[Sort] too many sort orders — fell back to default sort` / `sort=updDt; DROP TABLE x--,desc` → 200 + 키 무시(쿼리 미도달) |
| TC-REVIEW-041 | PASS | [실동작] | `sort=submittedAt,asc` → 7,4,15,8,5,… (제출일 오름차순) / `sort=videoId,asc` → 4,5,7,8,9,15,… / `sort=status,asc` → APPROVED 군이 선두(906,905,903…). allowlist `{submittedAt,updDt→updDt / videoId→rawDataId / status→dataSttsCd}`(SortAllowlist.java:117-121) 로만 해석되고 그 밖 프로퍼티는 쿼리에 미도달 |
| TC-REVIEW-042 | PASS | [실동작] | `size=3` 로 page 0~10 전수 순회 ×2회: ①`sort=status,asc` → 수집 30·유니크 30·**중복 0**, 동순위 구간이 `RAW_DATA_ID DESC`(906,905,903,902,901,900…) ②기본정렬 → 수집 30·유니크 30·중복 0. 코드상 `rawDataId` 가 요청 정렬에 있으면 append 안 함(384-386) |
| TC-REVIEW-043 | PASS | [실동작] | `status=PROCESSING` / `ASSIGNED` / `FAILED` / `' OR 1=1--` → 전부 **200 + total 0**(400 아님). `status=` (빈값) → 전체 30. `status=' PENDING'`(선행공백)·`status=pending`(소문자) → 0건 — `ReviewSearchCondition`(40-42)이 trim·대소문자 정규화를 하지 않는 구 JPQL 계약 그대로 보존 |
| TC-REVIEW-044 | PASS | [실동작] | `status` 미지정 목록 total 30 = APPROVED 26+PENDING 2+REJECTED 2. DB 에 실재하는 `ASSIGNED` 15건·`FAILED` 32건이 **전부 미노출**(`buildWhere` 첫 줄 233-234 화이트리스트 IN). `q` 조합·정렬 조합 어느 경로로도 유출 없음 |
| TC-REVIEW-045 | PASS | [실동작] | `q=강남` → total 9 / 반환 9(일치). `q=CCTV` → 30/30. `q=cctv`(소문자) → 30 (Locale.ROOT lower 비교). **LIKE 이스케이프 반증**: `q=%` → 0건, `q=_` → 0건, `q=\` → 0건 (미이스케이프면 전건 매칭됐어야 함). `q` 101자 → **400**, 100자 → 200 |
| TC-REVIEW-046 | PASS | [실동작] | 115 에 더 최신 LABELER 배정(2002·`reg_dt=2026-08-04 02:00`)을 추가 → `q=최라벨`(구 작업자) 결과에서 **115 제외**(total 17→16), `q=정작업` 에 **115 포함**(5→6), 목록·상세의 표시 workerName 도 **정작업(2002)** 으로 동시 전환 — 검색과 표시가 동일한 최신 1건(REG_DT DESC → ASSIGNMENT_ID DESC) 기준. 검증 후 추가 배정행 삭제 |
| TC-REVIEW-047 | PASS | [실동작] | `CCTV_NM = "\t\n "`(탭/개행/공백)인 CCTV(`QA3-D1-BLANKCCTV`)를 rawSn=103 에 연결 → 표시 `cctvName='QA3-D1-BLANKCCTV'`(VMS ID 폴백), `q=BLANKCCTV` 로 **매칭 성립**(total 1). 역반증: 실명 CCTV 를 그 VMS ID(`CCTV-001`)로 검색 → **0건**(화면에 안 보이는 값으로는 매칭 안 됨). 검증 후 원복 |
| TC-REVIEW-048 | PASS | [실동작] | `GET /v1/reviews/summary` → `{total:30,pending:2,inReview:0,approved:26,rejected:2}` — **total = 4종 합**. `?status=PENDING` 전달해도 **동일**(무시) / `?sort=filePath,desc` 전달해도 **200**(400 아님) / `?q=강남` 반영 → `{total:9,pending:0,inReview:0,approved:8,rejected:1}` 로 목록 total 9 와 정합. WORKER → 403, q 101자 → 400 |
| TC-REVIEW-049 | PASS | [정적] | `ReviewQueryRepository.countByStatus`(148-190)가 `COUNT(*)` 와 버킷합을 대조해 불일치 시 `bucketCoverageViolation`(211-215) `IllegalStateException`(입력값 미노출). `ReviewSummaryResponse.of`(61-69) → `verifyMappedCoverage`(72-85)가 미매핑 상태에 건수가 있으면 fail-fast. 버킷·WHERE 모두 `ReviewRepository.REVIEW_STATUS_WHITELIST`(33-37) 단일 상수 파생. 단위테스트 `ReviewSummaryResponseTest`·`ReviewSummaryTest` 존재. ※ 내부 정합성 위반이라 외부 입력으로 도달 불가 |
| TC-REVIEW-050 | PASS | [실동작] | `size=101` → **400** `size 한도 초과 (max=100)` / `size=100` → 200 / `size=abc` → 400. **⚠ 기대결과(상한)는 충족하나 하한이 미검증** — `size=0`·`size=-1`·`page=-1` 이 **500** 이다(§3 D-ISSUE-03, 별건 신규 이슈로 분리) |
| TC-REVIEW-051 | PASS | [실동작] | PENDING(115 v9)에서 `cancel-submit`(WORKER)과 `start`(REVIEWER)를 동시 발사 → `start=200`(IN_REVIEW v10) / `cancel=409` `이미 검수가 시작되었거나 상태가 변경되었습니다.` — 정확히 한쪽만 성공. rawSn=7 로 **6라운드 반복**해도 전부 `C=409 / S=200` 로 결정적. ⚠ 이 케이스가 항상 409 인 이유는 cancelSubmit 이 명시 flush(431-436)로 늘 패자 쪽이기 때문이며, **패자가 `start`/`submit` 이면 500 이다**(§3 D-ISSUE-02) |
| TC-REVIEW-052 | PASS | [실동작] | `LS_EVNT_ANNO.ANNO_CN` 의 `caption.c1.cot` 를 3형태로 DB 에 직접 넣고 `GET /v1/videos/115/event-annotation` 왕복 — **배열** `["첫단계","둘째단계","셋째단계"]` → `{"1단계":…,"2단계":…,"3단계":…}` **200**(500 아님) / **객체** `{"1단계":"a","2단계":"b"}` → 키·순서 보존 / **스칼라** `"단일문자열"` → `{"1단계":"단일문자열"}`. 신규 저장(PUT)은 배열 입력도 객체형으로 굳음. `CotDeserializer`(120-142) |

---

## 2. 추가 반증 (케이스 밖 · 전부 실동작)

| 항목 | 결과 |
|---|---|
| 동시 **이중 approve**(REVIEWER 1001 vs 1002, 115 IN_REVIEW) | 정확히 한쪽 200 / 패자 **409** `다른 검수자가 먼저 처리했습니다.` `LS_LABEL_VERSION` 중복 적층 0, 이벤트 로그도 APPROVE 1건 — ★ "동시 검수 승인" 반증 통과 |
| 동시 **이중 submit**(WORKER 2001 ×2) | 한쪽 200 / 패자 **500** → **D-ISSUE-02** |
| 동시 **이중 start**(REVIEWER ×2) | 한쪽 200 / 패자 **500** → **D-ISSUE-02** |
| 미존재 rawSn 액션 | approve/start/reject → **404**(`검수 대상 영상을 찾을 수 없습니다.`) / submit → **403**(IDOR 가드 선행 = 존재 오라클 차단, 의도된 우선순위) |
| PORTAL_USER 전 액션 | list/detail/submit/approve 전부 **403** |
| `q=%00` | 200(예외 없음) |
| `page=99999` | 200 + 빈 content |
| 정렬 키 SQL 인젝션(`updDt; DROP TABLE x--`) | 200, 키 무시 + WARN. 쿼리 미도달(CWE-89 표면 없음) |
| 반려 사유 길이 경계 | 1000자 200 / 1001자 400 |
| `size=0` / `size=-1` / `page=-1` | **500** → **D-ISSUE-03**. 같은 입력에 `/v1/tasks/board`·`/v1/videos` 는 **200** (엔드포인트 간 비대칭) |

### 자동 테스트 인벤토리(대조만, 실행 안 함)
`review/ReviewControllerTest`(전이·IDOR·재검수 26 케이스) · `ReviewStateMachineTest` · `ReviewApproveLabelGateIT`(라벨 게이트 5) · `ReviewListWhitelistFilterIT`(화이트리스트 4) · `ReviewListFilterSortTest` · `ReviewSummaryTest`(10) · `ReviewSummaryResponseTest` · `ReviewCancelSubmitConcurrencyIT` · `ReviewFramesControllerTest`(N+1) · `ReviewServiceEventPublishTest`(승인 부수효과·M2 스킵 WARN).
**가드 부재 확인**: 낙관적 잠금 패자 응답코드(submit/startReview)와 `size=0`/`page=-1` 경계에 대한 테스트는 없다.

---

## 3. 이슈

### [D-ISSUE-01] TC-REVIEW-017 — 승인 스냅샷의 **DTO 구성 단계 실패는 프레임 스킵이 아니라 승인 전체 500 + 롤백** (1차 이월 · 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 승인은 라벨 본문 산출물의 문제로 막히면 안 된다. `VersionService.snapshotFrameOnApprove` 는 스냅샷 산출 실패를 **해당 프레임만 SKIPPED** 로 처리하고 승인은 성공시키며, `ReviewService` 가 `approved with snapshot skips` WARN(496-501)으로 운영자에게 가시화한다. 이래야 프레임 1건의 손상 데이터가 영상 전체의 검수 종결(=학습데이터 확정·관제 통지)을 막지 않는다.
- **현재 동작(이슈 내용)**: `try` 블록이 **직렬화 호출만** 감싸고 있고, 그 앞의 **DTO 구성이 try 밖**이다.
  ```java
  // VersionService.java:262-283
  private FrameSnapshotOutcome snapshotFrameOnApprove(...) {
      LabelResponse snapshot = LabelResponse.of(frame, siblings, labels, "DEID", null,
              aiInfoBySn, objectMapper);           // ← 264-265: try 밖. 여기서 던지면 승인 전체가 죽는다
      String payload;
      try {
          payload = serializeSnapshotWithSimplification(snapshot, frame.getSrcSn());
      } catch (CustomException e) { ... return SKIPPED; }
        catch (Exception e)       { ... return SKIPPED; }
  ```
  `LabelResponse.of` → `Item.from` → `parsePoints` → `LabelPointSerializer.fromJson`(common/util:  `throw new IllegalArgumentException("좌표 역직렬화 실패", e)`) 경로라, `LS_DATA_LBL.POINT_CN` 이 손상되면 `IllegalArgumentException` 이 `commitApproved` → `approve` 를 그대로 관통해 `GlobalExceptionHandler` 의 unhandled 분기로 떨어진다.
- **재현/확인 경로** (3차 실측 재현):
  ```sql
  UPDATE ls_data_lbl SET point_cn='[[1.0,1.0],[8.0' WHERE lbl_sn=1254;      -- rawSn=110 의 유일 라벨
  UPDATE ls_raw_data_status SET data_stts_cd='IN_REVIEW' WHERE raw_data_id=110;
  ```
  ```
  POST /api/v1/reviews/110/approve  (REVIEWER)
  → 500 {"errorCode":"INTERNAL_ERROR","message":"서버 내부 오류가 발생했습니다."}
  backend log: java.lang.IllegalArgumentException: 좌표 역직렬화 실패
  DB 재조회: ls_raw_data_status=IN_REVIEW(전이 안 됨), ls_label_version(raw 110) 0행 → 전체 롤백
  ```
  좌표를 정상값으로 되돌리면 같은 호출이 200 + 스냅샷 1행 생성으로 성공(대조군 확인).
- **영향**: 기능. 한 프레임의 손상 좌표 1건이 **영상 전체의 검수 종결을 영구 차단**한다(승인 불가 → 버전 스냅샷·export·관제 `TASK_COMPLETED` 통지까지 연쇄 중단). 운영자에겐 원인 불명의 500 만 보인다(클라이언트에 내부 정보는 노출되지 않아 CWE-209 는 아님).
- **수정 방향(제안)**: `LabelResponse.of(...)` 호출을 기존 `try` 블록 안으로 옮겨 같은 `catch (Exception)` 이 `SKIPPED` 를 반환하게 한다(`VersionService.java:264-283`). 회귀 가드로 "손상 `POINT_CN` 프레임이 섞인 영상의 승인은 200 + `skipped>0` + WARN" IT 를 추가한다. ⚠ **구현은 하지 않았다.**

### [D-ISSUE-02] TC-REVIEW-051 / TC-REVIEW-001 / TC-REVIEW-009 — `submit`·`startReview` 는 낙관적 잠금 패자가 409 가 아니라 **500** 이다 (전이 5경로 중 2경로 미배선)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 검수 상태 전이는 5경로(`submit`/`cancelSubmit`/`startReview`/`approve`/`reject`) 모두 `LS_RAW_DATA_STATUS.VER` 낙관적 잠금으로 보호된다. 경합에서 진 쪽은 **409 CONFLICT + "이미 …처리됐다"** 를 받아야 한다 — FE 가 "다른 사람이 먼저 처리함 → 새로고침 후 재시도" 를 안내할 수 있어야 하고, 정상적인 동시성 경합이 서버 장애 알림으로 오탐되면 안 된다(api-design.md: 409=충돌).
- **현재 동작(이슈 내용)**: 5경로 중 **3경로만** `flush()` + `OptimisticLockingFailureException` catch 가 배선돼 있다.
  ```java
  // cancelSubmit 431-436 / approve 487-492 / reject 591-596 — 배선 있음
  try { reviewRepository.flush(); }
  catch (OptimisticLockingFailureException e) { throw new CustomException(ErrorCode.CONFLICT, "…"); }

  // submit 394-406 — flush/catch 없음 (transitionTo 후 바로 이벤트로그 save → return)
  // startReview 444-452 — flush/catch 없음
  ```
  그 결과 커밋 시점에 `ObjectOptimisticLockingFailureException` 이 `GlobalExceptionHandler` 의 unhandled 분기로 떨어진다.
- **재현/확인 경로** (3차 실측):
  ```bash
  # rawSn=7 을 ASSIGNED 로 둔 뒤 동일 WORKER 로 submit 2발 동시
  curl -X POST .../v1/reviews/7/submit -H "Authorization: Bearer $WORKER" &
  curl -X POST .../v1/reviews/7/submit -H "Authorization: Bearer $WORKER" &
  → 하나 200 / 하나 500
  # rawSn=7 을 PENDING 으로 둔 뒤 start 2발 동시
  curl -X POST .../v1/reviews/7/start -H "Authorization: Bearer $REVIEWER" &  (×2)
  → 하나 200 / 하나 500
  ```
  ```
  backend log:
  org.hibernate.StaleStateException: Batch update returned unexpected row count from update [0]; expected: 1;
    statement: update LS_RAW_DATA_STATUS set DATA_STTS_CD=?,…,VER=? where RAW_DATA_ID=? and VER=?
  org.springframework.orm.ObjectOptimisticLockingFailureException  ← GlobalExceptionHandler "unhandled exception"
  ```
  대조군: 같은 조건의 `cancel-submit` vs `start` 는 6라운드 전부 `409 / 200` 로 결정적(TC-REVIEW-051).
- **영향**: 기능 + 운영. ①데이터 정합은 유지된다(잠금이 제 역할을 해 상태는 한 번만 전이됨 — 데이터 손상 없음) ②그러나 FE 는 "충돌"과 "서버 장애"를 구분할 수 없어 재시도 안내가 불가능하고 ③정상적인 동시 클릭·더블 서브밋이 500 으로 집계돼 알림/에러버짓을 오염시킨다 ④TC-REVIEW-051 의 "패자 409" 단언이 **경합 쌍이 바뀌면 성립하지 않는다**.
- **수정 방향(제안)**: `ReviewService.submit`(394-406)·`startReview`(444-452)에 나머지 3경로와 **동일한** `flush()` + `OptimisticLockingFailureException → CustomException(CONFLICT)` 블록을 넣는다(메시지는 `cancelSubmit` 의 `"이미 검수가 시작되었거나 상태가 변경되었습니다."` 계열로 통일). 5경로 공통 헬퍼로 추출하면 다음 경로 추가 시 누락이 재발하지 않는다. 회귀 가드는 기존 `ReviewCancelSubmitConcurrencyIT` 옆에 submit/start 동시 경합 IT 를 추가. ⚠ **구현은 하지 않았다.**

### [D-ISSUE-03] TC-REVIEW-050 — 검수목록 페이징 파라미터의 **하한 미검증**으로 `size=0`·`size=-1`·`page=-1` 이 500 (형제 목록과 비대칭)
- **심각도**: LOW
- **기대 동작(기대효과)**: 목록 API 의 잘못된 페이징 파라미터는 **400 INVALID_INPUT**(또는 형제 엔드포인트처럼 클램프 후 200)이어야 한다. `.claude/rules/api-design.md` 는 "입력값 검증 실패 = 400" 을 규정하고, 이 컨트롤러는 이미 상한(`size>100`)에 대해서만 400 을 낸다. 같은 축의 하한만 500 이 되면 계약이 갈라진다.
- **현재 동작(이슈 내용)**: 상한 가드만 있고 하한 가드가 없어 `PageRequest.of` 의 `IllegalArgumentException` 이 그대로 새어 나간다.
  ```java
  // ReviewController.java:92-102
  if (size > MAX_PAGE_SIZE) {                       // 상한만 검증
      throw new CustomException(ErrorCode.INVALID_INPUT, "size 한도 초과 (max=" + MAX_PAGE_SIZE + ")");
  }
  Pageable pageable = PageRequest.of(page, size,    // ← size<1 또는 page<0 이면 IllegalArgumentException
          SortAllowlist.resolveLenient(sort, SortAllowlist.REVIEW, DEFAULT_REVIEW_SORT));
  ```
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:18081/api/v1/reviews?size=0'   -H "Authorization: Bearer $REVIEWER"  # 500
  curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:18081/api/v1/reviews?size=-1'  -H "Authorization: Bearer $REVIEWER"  # 500
  curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:18081/api/v1/reviews?page=-1'  -H "Authorization: Bearer $REVIEWER"  # 500
  # 형제 목록 — 같은 입력에 200
  curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:18081/api/v1/tasks/board?size=0' -H "Authorization: Bearer $REVIEWER"  # 200
  curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:18081/api/v1/videos?size=0'      -H "Authorization: Bearer $REVIEWER"  # 200
  ```
  로그: `java.lang.IllegalArgumentException: Page size must not be less than one` / `Page index must not be less than zero` → `GlobalExceptionHandler "unhandled exception"`.
- **영향**: 기능/운영(보안 영향 없음 — 응답 본문에 내부 정보가 노출되지 않아 CWE-209 아님, 인증도 선행됨). 다만 **인증된 사용자가 URL 파라미터 하나로 500 을 임의 생성**할 수 있어 에러 로그·알림을 오염시킬 수 있고(CWE-20 입력 검증 부재), FE 가 `size=0` 이 담긴 URL 로 진입하면 검수목록이 통째로 죽는다. ⚠ 이 엔드포인트는 UNCERTAINTIES ★2 에서 "미등록 정렬 키는 200 으로 관용" 정책을 택한 곳이라, **페이징 하한만 500 인 것은 그 정책 취지와도 어긋난다.**
- **수정 방향(제안)**: `ReviewController.list`(92-94)의 가드를 `if (size < 1 || size > MAX_PAGE_SIZE || page < 0) → INVALID_INPUT` 로 넓히거나, 형제 엔드포인트와 동일하게 **클램프 후 200**(`size` 1..100, `page` max(0,page))으로 맞춘다. ★2 의 lenient 취지를 따르면 후자가 일관적이다. 어느 쪽이든 `/v1/tasks/board`·`/v1/videos` 와 정책을 맞추고 회귀 IT(`ListApiBackwardCompatibilityIT`)에 경계값 3건을 추가한다. ⚠ **구현은 하지 않았다.**

---

## 4. 카탈로그 정정 (D-1 절 16~68행, 총 **9건** — 프로덕션 코드 미수정)

| # | 케이스 | 종류 | 내용 |
|:--:|---|---|---|
| 1 | TC-REVIEW-007 | 기대결과+근거 정정 | 구 기대문구 `"WORKER 권한 필요"` **폐기**. 실제 차단은 컨트롤러 `@PreAuthorize("hasRole('WORKER')")` 이고 응답은 표준 `"권한이 없습니다."` 다. 근거를 `ReviewController.java:221 · ReviewService.java:618-620(HTTP 미도달)` 로 교체 |
| 2 | TC-REVIEW-016 | 근거 드리프트 | `MetaController.java:81-106` → `83-89(approve)·103-110(reject)` (81 은 409 응답 어노테이션 라인, 106 은 reject 파라미터 중간) |
| 3 | TC-REVIEW-017 | 기대결과 보강 | DTO 구성 단계는 스킵으로 흡수되지 않고 **500+롤백**임을 ⚠ 로 명시 + 근거에 `VersionService.java:264-283` 추가(D-ISSUE-01 미해소 표기) |
| 4 | TC-REVIEW-041 | 근거 정밀화 | `SortAllowlist.java:REVIEW` → `SortAllowlist.java:117-121(REVIEW)` |
| 5 | TC-REVIEW-043 | 근거 정밀화 | `ReviewSearchCondition.java:statusFilter` → `:40-42(statusFilter)` |
| 6 | TC-REVIEW-045 | 근거 드리프트 | `…,335-340` → `…,336-340(escapeLike)` (335 는 javadoc) |
| 7 | TC-REVIEW-048 | 근거 정밀화 | `ReviewSummaryResponse.java:of` → `:61-69(of)` |
| 8 | TC-REVIEW-049 | 근거 정밀화 | `ReviewSummaryResponse.java:verifyMappedCoverage` → `:72-85(verifyMappedCoverage)` |
| 9 | TC-REVIEW-050 · 051 | 기대결과 보강 | 050: 페이징 **하한 미검증(500)** 을 ⚠ 로 명시(D-ISSUE-03) / 051: 409 배선이 3경로뿐이고 `submit`·`startReview` 패자는 500 임을 ⚠ 로 명시(D-ISSUE-02) |

> **정확했던 근거(변경 없음) 39건** — `ReviewStateMachine.java:43-51,47,48,50,53-64,54-58,59-63` · `ReviewService.java:335-372,378-383,394-406,421-439,444-452,467-518,471-478,477-486,496-501,502-506,507-510,543-565,545-553,556-564,570-599,576-584,614-627,615-617,618-620,633-650,640-649,652-659` · `ReviewController.java:18,47,92-94,95-103,127-139,286-294,314` · `review/dto/RejectRequest.java:9-13` · `ReviewQueryRepository.java:109-129,186-215,229-250,233-234,243-248,261-284,304-325,331-333,360-388` · `ReviewRepository.java:33-37` · `EventAnnotationPayload.java:120-142`.
>
> ⚠ **병합 담당자 유의**: D-1 절의 정정 9건은 파일 상단 `## 변경 이력` 표(8~12행, 본 파트 담당 범위 밖)에 아직 반영하지 않았다 — D 클러스터 전체 파트 정정 건수를 합산해 4회차 행으로 추가할 것.

---

## 5. 검증 데이터 조작 내역 (프로덕션 코드·설정 무변경)

| 대상 | 조작 | 원복 |
|---|---|:--:|
| `ls_raw_data_status(33)` | COMPLETED 강제(TC-004) | ✅ APPROVED 로 원복 |
| `ls_data_lbl(1254)` | `point_cn` 손상(TC-017 재현) | ✅ `[[1.0,1.0],[8.0,8.0]]` 원복 |
| `ls_task_assignment` | 115 에 2002 LABELER 1행 추가(TC-046) | ✅ DELETE |
| `mng_resource_cctv` | `QA3-D1-BLANKCCTV`(공백 명) 1행 추가(TC-047) | ✅ DELETE |
| `ls_data_raw(103).vms_cctv_id` | 임시 교체(TC-047) | ✅ `CCTV-QA3` 원복 |
| `ls_evnt_anno(raw 115).anno_cn` | cot 3형태 주입(TC-052) | ✅ 객체형 정상값 원복 |
| `ls_raw_data_status(7)` | ASSIGNED/PENDING 반복 강제(동시성 반증) | ✅ PENDING(원 상태) |

**원복하지 않은 상태 변화(의도된 정상 전이 — 다른 에이전트 유의)**
- `rawSn=115` ASSIGNED → **APPROVED**(스냅샷 3행·export `export_sn=62` SUCCEEDED·관제 `notify-completed` 202 발생)
- `rawSn=33` ASSIGNED → **APPROVED**(negative sample 승인, 라벨 0건)
- `rawSn=31` IN_REVIEW → **REJECTED**(`LS_DATA_ISSUE` 21·22 생성)
- `rawSn=110` ASSIGNED → **APPROVED**(TC-017 대조군 승인, 스냅샷 1행)
> D-3(TC-ASSIGN) 담당은 위 4건이 더 이상 ASSIGNED 가 아님을 전제로 대상 영상을 고를 것. 남은 ASSIGNED 영상은 10·17·30·34·36·38·44·52·69·107·111·112 등이다.

---

## 6. 이전 회차 이슈 대조 (D-1 관련)

| 이슈 | 1차 상태 | 3차 결과 |
|---|---|---|
| **D-ISSUE-01** (TC-REVIEW-017, 스냅샷 DTO 구성 실패 → 승인 500+롤백) | FAIL/PARTIAL | **미해소 — 실측 재현.** 본 회차에도 동일 ID 로 이월 기록 |
| **D-ISSUE-02** (1차: TC-REVIEW-011, export FAILED인데 TASK_COMPLETED 발송) | CRITICAL | **✅ 해소 확인.** 3차 승인 실측에서 `export succeeded`(28.383) → `TASK_COMPLETED sent`(28.386) 순서로 **export 성공 후에만** 통지. `export_stts_cd=SUCCEEDED`. 2차 타겟 재검증(PASS) 결과가 전수 재확인됨. ⚠ 3차의 `D-ISSUE-02` 번호는 **신규 이슈(낙관적 잠금 500)** 에 재사용됐다 — 1차 번호와 혼동 주의 |
| **D-ISSUE-03** (1차: TC-REVIEW-034 `verify(null)` NPE 500) | 미해소 | **본 파트 범위 밖**(TC-REVIEW-034 는 D-2 절). 코드상 `ReviewStateMachine.java:59` `ALLOWED.get(from)` 가드는 여전히 없어 미해소로 보이나 판정은 D-2 담당이 수행. ⚠ 3차의 `D-ISSUE-03` 번호는 **신규 이슈(페이징 하한 500)** 에 재사용됐다 |
| UNCERTAINTIES ★2 (검수목록 미등록 정렬 키 lenient 200) | 확정 정책 | **정책대로 동작 확인**(TC-REVIEW-040) — 결함 아님, 재보고 안 함 |
| 2차 타겟 재검증 3건 중 "PARTIAL 뷰 배제"·"통지 인증헤더" | — | D-6/D-8 소관이라 본 파트에서 판정하지 않음 |
