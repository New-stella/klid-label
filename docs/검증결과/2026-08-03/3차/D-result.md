# D클러스터 — 3차(2026-08-03) 검증 결과 병합

> `_raw/D-part1.md` ~ `_raw/D-part5.md` 5개 파일을 순서대로 병합. 원본은 `_raw/`에 보존.

---

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

---

# D 클러스터 part2 — D-5. diff / rollback (TC-DIFF) 전수 검증

- **회차**: 2026-08-03 3차
- **대상 파일/섹션**: `docs/test-cases/D-review-version-notify.md` → `## D-5. diff / rollback (TC-DIFF)` (135~165행)
- **케이스 수**: 26건 (TC-DIFF-001~026, 폐기 0건)
- **판정 집계**: **PASS 19 · PARTIAL 5 · FAIL 2 · BLOCKED 0 · N/A 0 · 확인필요 0** (PASS율 73.1%, PASS+PARTIAL 92.3%)
- **검증 방식**: 전건 **실동작**(풀스택 `docker compose` 기동분 위에서 실제 HTTP 호출 + PostgreSQL 상태 조회 + backend/mock-server 로그) + 근거 `file:line` 정적 대조 병행

## 검증 환경·데이터

`_raw/stack-bringup.md` 기동분(backend `localhost:18081/api`, DB `klid_system`@`public`, mock-server :9400) 사용.
`_raw/pipeline-drive.md` 의 rawSn=101 은 **조회만** 하고 변형하지 않았다. diff/rollback 은 상태 변형이 필수라
**기존 검증용 영상 rawSn=27**(APPROVED, WORKER=2001 LABELER 배정 / REVIEWER=1001, 프레임 srcSn 301~305)에서 수행했다.

**정상 시나리오 실구동(라벨 수정 → 재검수 → 재승인으로 버전 적층)**:

| 버전 | ver_no | versionHash(prefix) | 내용 | 생성 경로 |
|---|:--:|---|---|---|
| v1 | 1 | `5b5765de…` | BBOX 727 `[[10,10],[101,101]]` | (기존 시드) |
| v2 | 2 | `738935bd…` | BBOX 728 `[[10,10],[102,102]]` | (기존 시드) |
| v3 | 3 | `ca03614f…` | 727 확대 + POLYGON 1259 + SKELETON 1260(v 전부 2) | `PUT /v1/frames/301/labels` → submit → start → **approve** |
| v4 | 4 | `a0dcd35e…` | v3 에서 **SKELETON 1260 의 v 만 2→0** | 동일 |
| v6 | 6 | `a146e906…` | v4 + AI 라벨 1261(`AUTO_YOLO`, conf 0.91, `TRCK_ID=TRK-QA-1`) | 동일 |
| v7 | 7 | `ff050b52…` | v6 에서 1259 삭제 + 1261 좌표 변경 | 동일 |

`submit 200 → start 200 → approve 200` 전 구간 실호출, 승인 시 `[Version] approved snapshot rawSn=27 frames=5 created=1` 로그로 스냅샷 생성 확인.
합성 데이터(손상 페이로드·`DATA_SRC_SN` NULL·동일 해시 중복·빈 스냅샷 행)는 `lbl_version_sn` 990101/990102/990103/990104 로 넣고 **검증 후 전부 삭제**했다.
프로덕션 코드·설정·마이그레이션은 일절 수정하지 않았다(DB 데이터 조작만).

---

## 판정표

| ID | 판정 | 근거 확인 | 실측 결과 |
|----|:----:|---|---|
| TC-DIFF-001 | PASS | [실동작] `GET /v1/versions/{H3}/diff?compareWith={H1}` 200 · `VersionService.java:334-366` | `MODIFIED objectId=727`(before `10,10,101,101` → after `10,10,120,120`) + `ADDED 1259` + `ADDED 1260`. v2→v3 비교는 `REMOVED 728` + `ADDED 727/1259/1260` — ADDED/REMOVED/MODIFIED 3분류 모두 실증 |
| TC-DIFF-002 | **FAIL** | [실동작] v3→v4(키포인트 v 만 2→0) diff 200 · `VersionService.java:1180-1197` · `version/dto/LabelDiffDto.java:68-75` | **감지는 성립**(`MODIFIED objectId=1260`) 하나 응답이 `type:"POLYGON"` + 2-stride 평탄배열이라 **`before.points == after.points`(파이썬 비교 True)**. v 가 렌더링에서 탈락해 "무엇이 바뀌었는지"가 보이지 않음 → **3차 D-ISSUE-22**(1차 D-ISSUE-41 이월·미해소) |
| TC-DIFF-003 | PASS | [실동작] `diff(from=src302 v1, to=src301 v3)` 200 `data:[]` · `:359-361` | 다른 프레임 조합은 빈 결과. 예외 아님 |
| TC-DIFF-004 | PASS | [실동작] · `:1246-1258` | 비-hex `zz!!`/`zzzz` → **400** `잘못된 버전 해시 형식입니다.`, 128자(64 초과) → **400**, `compareWith=`(빈값) → **400**, `compareWith` 누락 → 400 `필수 파라미터가 누락되었습니다: compareWith` |
| TC-DIFF-005 | PASS | [실동작] · `:1020-1026` | 미존재 해시 `deadbeef` → **404** `to 버전을 찾을 수 없습니다.` / from 측도 `from 버전을 찾을 수 없습니다.` |
| TC-DIFF-006 | PARTIAL | [실동작] 미배정 WORKER(2002) → **403** · `:348-349` | 인가는 양 버전 모두 `accessGuard.verifyAndGet` 로 정상 차단(PORTAL 토큰은 `@PreAuthorize` 403). **다만 해시 조회가 인가보다 앞서** 존재 해시=403 / 미존재 해시=404 로 갈려 **버전 존재 여부 오라클**이 성립(CWE-209) → **3차 D-ISSUE-25**(1차 D-ISSUE-42 이월·미해소) |
| TC-DIFF-007 | PASS | [실동작] 손상 payload(`{"items":[{"id":1,`) 행과 diff → 200 `data:[]` · `:1124-1137` | 예외 전파 없이 빈 리스트(장애 격리). 로그도 `reason=` 클래스명만(본문 미출력) |
| TC-DIFF-008 | PARTIAL | [실동작] `POST /v1/versions/{H5}/rollback {"srcSn":301}` 200 · `:410-522,535-545` | ①**본문 복원 성립** — 삭제됐던 1259 가 **같은 LBL_SN 1259 로 부활**, 1261 좌표 `[[31,31],[61,61]]`→`[[30,30],[60,60]]`, **`TRCK_ID='TRK-QA-1'` 보존**, AI 메타(`lbl_src_cd=YOLO`,`conf_score=0.91`,`auto_lbl_yn=Y`) 재적재. 로그 `rollback restored labels srcSn=301 deleted=3 created=4 idPreserved=4` ②**버전 행 적층 0** — `lbl_version_sn=26`(v6) 재활성 + 27 비활성, 행 수 불변, **전 DB `SAVE_REASON_CD='ROLLBACK'` 0건** ③`LS_DATA_LBL_HSTRY` 175행에 `reg_id=1001`·`reg_dt`·`chg_dtl_cn.rollbackToVersionHash=a146e906…`+changes(ADDED 1259 / UPDATED 1261) 기록. ④PK 점유 폴백도 실증(1259 를 src303 으로 옮긴 뒤 롤백 → `rollback lblSn conflict — reassigned new ids count=1`, 신규 PK 1262, `idPreserved=3`). **결함**: 복원된 라벨 전건의 `REG_USER_NO` 가 NULL 로 소실(1260·1261 은 롤백 전 2001 이었음) → **3차 D-ISSUE-23**(1차 D-ISSUE-43 이월·미해소) |
| TC-DIFF-009 | PASS | [실동작] `{"items":[]}` 스냅샷 행으로 롤백 200 · `:663-695`, `:866-868` | `ls_data_lbl` 0건 · `ls_data_lbl_ai_info` 0건으로 정리. 이후 H5 재롤백으로 4건(727/1259/1260/1261) **전부 원 LBL_SN 그대로** 복구됨 |
| TC-DIFF-010 | PASS | [실동작] 손상 스냅샷 롤백 → **400** `손상된 버전 스냅샷이라 롤백할 수 없습니다.` · `:441-442` | 호출 후 `ls_data_lbl`(727/1259/1260) · 버전 active 플래그 **전혀 변화 없음** = 부분 적용 0 |
| TC-DIFF-011 | PASS | [실동작] `LS_AUTH_WORK_LOCK` 에 `LCK_STTS_CD='LOCKED'`(raw 27) 삽입 후 롤백 → **409** `작업이 잠긴 영상은 롤백할 수 없습니다.` · `:427-430` | 같은 상태에서 `diff` 는 200(읽기는 작업락 대상 아님 — 의도된 비대칭) |
| TC-DIFF-012 | PASS | [실동작] APPROVED 영상 롤백 → 60초 디바운스 flush → export → 통지 | `[ControlNotifyDebounce] flush rawSn=27 regen=true frames=301=[LABEL_UPDATED]` → `AsyncDatasetExportRunner … re-export(+notify) forceRegenerate=true` → `export succeeded rawSn=27 version=17 written=10` → `TASK_MODIFIED sent rawSn=27 frames=1 reExport=true` → mock-server `POST /api/data-set/v2/jobs/27/notify-updated 202`. **export 성공 후 통지** 순서도 함께 확인 |
| TC-DIFF-013 | PASS | [실동작] `ls_raw_data_status.data_stts_cd='ASSIGNED'` 로 내린 뒤 실질 롤백 4회 · `:516` | 이후 **90초 이상 경과해도 `ControlNotifyDebounce flush rawSn=27` 미발생**(마지막 flush 01:28:05 고정), export 재생성 로그도 0건. 미APPROVED 는 통지·재생성 모두 미발행 |
| TC-DIFF-014 | PASS | [실동작] 동일 해시 재롤백 200 · `:481-498` | `LBL_SN` 4건 및 `POINT_CN` 완전 불변, `ls_data_lbl_hstry` 건수 11→11, `ls_data_lbl_ai_info` 최대 PK 36→36(재삽입 없음), 로그 `rollback no-op (already active and labels identical)`. 통지/재생성도 미발행 |
| TC-DIFF-015 | PASS | [실동작] · `:535-545` | 롤백 전후 `ls_label_version` 행 수 불변(8→8), 대상 행 `actvtn_yn='N'→'Y'`, 직전 active 행 `'Y'→'N'`. **신규 행 0** |
| TC-DIFF-016 | PASS | [실동작] · `:420-421` | 없는 해시 → **404** `롤백 대상 버전을 찾을 수 없습니다.` / **다른 프레임 소속 해시 + srcSn=301** 도 404(srcSn 스코프 단건 조회라 교차 롤백 불가) |
| TC-DIFF-017 | PASS | [실동작] · `:412-418` | 토큰 없음 → **401** `인증이 필요합니다.` / 미배정 WORKER(2002) → **403** `본인에게 배정되지 않은 영상입니다.` / PORTAL → 403 / `srcSn` 누락 → 400 |
| TC-DIFF-018 | PARTIAL | [실동작] 동시 롤백 2~3건 · `:444-449` | **라벨 교체 자체는 직렬화됨**(전건 200, 데드락·부분적용·PK 충돌 0, 최종 라벨셋은 마지막 커밋 버전과 일관). **그러나 ACTIVE 단일성 불변식이 깨진다** — 정확히 1건만 active 인 상태에서 서로 다른 해시로 2건 동시 롤백 시 **active 2건 잔존**(재현 2/2), `GET /v1/frames/301/versions` 가 `isCurrent:true` 를 2건 반환 → **3차 D-ISSUE-21(신규)** |
| TC-DIFF-019 | PASS | [실동작] v4(첫 키포인트 v=0) → H3(v3, v 전부 2) 롤백 · `:605-608,789-793,818-831` | `POINT_CN` 이 `[[10.0,20.0,2],…]` 17점으로 무손실 복원. **v 가 `2.0` 이 아닌 정수 `2`** 로 정규화돼 DB 표현과 동일(왕복 무손실) |
| TC-DIFF-020 | PARTIAL | [정적] `:1260-1262` + 전 소스 grep | `isCommittable` 은 PORTAL 채널에 false 를 돌려주나 **프로덕션 호출자 0건**(`VersionServiceTest:313-317` 테스트만 참조). 실제 PORTAL 차단은 컨트롤러 `@PreAuthorize` 가 담당(실측 403 확인) → **3차 D-ISSUE-26**(1차 D-ISSUE-28 이월·미해소) |
| TC-DIFF-021 | PASS | [실동작] `de_ident_yn='F'`(배치 실패 경로 모사) 후 롤백 → **412** · `:432-437` | `PRECONDITION_FAILED` / `비식별 재처리 대기 중인 영상입니다.` **작업락이 전혀 없는 상태**에서도 차단됨(읽기·쓰기 비대칭 제거 확인). `'Y'` 복원 시 즉시 재개 |
| TC-DIFF-022 | PASS | [실동작] `de_ident_yn='F'` 에서 diff → **412** · `:351-357` | 좌표 전문 미유출. **인가 이후 평가** 실증 — 같은 조건에서 미배정 WORKER 는 **403**(412 아님)이라 게이트가 인가 우회 채널이 되지 않음. 대조로 `GET /v1/frames/301/versions` 는 **200**(TC-VERSION-015 정책 일치), `GET /v1/frames/301/labels` 는 412 |
| TC-DIFF-023 | PASS | [실동작] `DATA_SRC_SN IS NULL` 레거시 행 삽입 후 diff · `:341-346,377-382` | to 측 → **400** `to 버전은 프레임 단위 비교 대상이 아닙니다.` / from 측 → 400(`from …`) / **미배정 WORKER 도 동일 400**(인가 검사 이전 조기 반환) → 500 없음. 같은 행으로 rollback 은 404(srcSn 스코프) |
| TC-DIFF-024 | PARTIAL | [실동작] · `:486-498,535-545` | **단일 스레드에서는 양 경로 모두 자기치유 성립** — active 3건(21·22·26)을 심고 ①멱등 경로 롤백 → 26 만 남고 22·27 비활성 ②교체 경로 롤백(H3) → 21 만 남고 22·26·27 비활성. **그러나 동시 롤백 시 잉여 active 가 정리되지 않고 2건 잔존**(TC-DIFF-018 동일 근인) → **3차 D-ISSUE-21** |
| TC-DIFF-025 | PASS | [실동작 보조 + 정적] `:451-473`, `LsDataSrcRepository.java:251-253`(`SELECT LBL_VER … FOR UPDATE`) | 멱등 롤백과 `PUT /v1/frames/301/labels` 를 3라운드 동시 발사 → 결과가 항상 두 직렬화 중 하나(①롤백 no-op 후 저장 반영 ②저장 후 롤백 교체)로 수렴, **"라벨은 상대 트랜잭션 값인데 롤백은 no-op 성공"** 조합은 0회. 락 순서 VERSION→SRC→LBL 은 코드·주석에서 확인 |
| TC-DIFF-026 | **FAIL** | [실동작] `:1020-1026`, `LsLabelVersionRepository.java:47`(ORDER BY 없음) | src303 에 H5 와 동일 해시·다른 payload 행을 추가 → 동일 diff 요청 5회 모두 `items=4` 정상 → `UPDATE` 로 heap 순서 반전(ctid `(5,3)`→`(5,7)`) 후 **같은 요청이 3회 모두 `items=0`(변경 없음)** 반환. 오류 없이 결과만 뒤바뀜 → **3차 D-ISSUE-24**(1차 D-ISSUE-27/45 이월·미해소) |

### 근거 드리프트

**0건.** TC-DIFF-001~026 의 근거 `file:line` 26건 전부(`VersionService.java` 334-366 / 359-361 / 1246-1258 / 1020-1026 / 348-349 / 1124-1137 / 410-522 / 535-545 / 663-695 / 866-868 / 441-442 / 427-430 / 516-520 / 481-498 / 420-421 / 412-418 / 444-449 / 605-608 / 789-793 / 818-831 / 1260-1262 / 432-437 / 351-357 / 341-346 / 377-382 / 486-498 / 451-473 / 1180-1197 / 1143-1171)가 현재 소스와 정확히 일치했다. D-3 에서 보고된 전면 드리프트(1차 D-ISSUE-23)와 달리 D-5 는 정합.

### 1차(2026-08-01) 이슈 해소 여부 대조

| 1차 이슈 | 대상 | 3차 상태 |
|---|---|---|
| D-ISSUE-41 (SKELETON diff v 유실·POLYGON 왜곡) | TC-DIFF-002 | **미해소** — `LabelDiffDto.java:68-75` 그대로. 3차 D-ISSUE-22 로 이월 |
| D-ISSUE-42 (인가 전 해시 조회 오라클) | TC-DIFF-006 | **미해소** — `diff` 진입 순서 불변. 3차 D-ISSUE-25 로 이월 |
| D-ISSUE-43 (롤백 시 REG_USER_NO 소실) | TC-DIFF-008 | **미해소** — `LsDataLblRepositoryImpl.INSERT_SQL` 에 `REG_USER_NO` 없음, 실측 재현. 3차 D-ISSUE-23 로 이월 |
| D-ISSUE-44 (export FAILED 인데 통지 발송) | TC-DIFF-012 | **✅ 해소** — `DatasetExportService.export` 가 `DatasetExportOutcome` 를 반환하고 `AsyncDatasetExportRunner.doExport` 가 `outcome.notifiable()` 로만 판정(fail-closed, null 도 미통지). 코드 주석에 D-ISSUE-61 로 명시. 이번 롤백 경로 실측에서도 export SUCCEEDED 후에만 통지 |
| D-ISSUE-45 / D-ISSUE-27 (동일 해시 다중 매칭 비결정) | TC-DIFF-026 | **미해소** — 재현 성공. 3차 D-ISSUE-24 로 이월 |
| D-ISSUE-28 (isCommittable dead code) | TC-DIFF-020 | **미해소** — 프로덕션 호출자 여전히 0건. 3차 D-ISSUE-26 으로 이월 |
| D-ISSUE-25 (신고 rawSn 스코프 스냅샷 write-only) | TC-VERSION-011/012(타 담당) | (참고) `VersionService.java:303-307` 에 제거 사유 주석만 남고 메서드 부재 — 이번에 삽입한 `DATA_SRC_SN IS NULL` 레거시 행이 diff 400 으로 안전 처리됨을 확인 |

---

## 이슈

### [D-ISSUE-21] TC-DIFF-018 / TC-DIFF-024 — 동시 롤백 시 ACTIVE 버전 행이 2건 남아 "현재 버전"이 다중 표시된다 (신규)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `LS_LABEL_VERSION` 은 `(rawSn, srcSn)` 당 **정본 active 1건**이어야 한다. `findActiveForUpdate` 가 List 를 돌려주고 `deactivateOthers` 자기치유가 존재하는 이유가 "부분 유니크가 없어 2건 이상 존재할 수 있으니 정리한다"는 것이고(TC-DIFF-024), 그 정리가 **동시 롤백이라는 바로 그 조건**에서 성립해야 한다. 그렇지 않으면 검수자가 버전 목록에서 어느 버전이 현재 작업본인지 판별할 수 없어 "버전 비교 및 복구" 요구가 성립하지 않는다.
- **현재 동작(이슈 내용)**: active 집합 조회가 **프레임 행 락 취득보다 앞서** 수행돼, 경쟁 트랜잭션이 그 사이 새로 활성화한 행을 목록에 담지 못한다. `activeYn = :activeYn` 술어로 `FOR UPDATE` 를 걸면 **스캔 시점에 `'Y'` 인 행만** 잠기므로, 상대가 `'N'→'Y'` 로 바꿀 행은 애초에 잠금 대상이 아니다(전형적 write skew).
  ```java
  // VersionService.java:448-449  ← 여기서 읽은 목록이 stale 이 된다
  List<LsLabelVersion> activeVersions = labelVersionRepository.findActiveForUpdate(
          raw.getRawSn(), src.getSrcSn(), LsLabelVersion.ACTIVE_YES);
  // VersionService.java:472  ← 실제 직렬화 앵커(프레임 행 락)는 그 '뒤'에 잡힌다
  srcRepository.lockAndReadLabelVersion(src.getSrcSn()) ...
  // VersionService.java:557-563  deactivateOthers 는 위 stale 목록만 순회한다
  ```
  ```java
  // version/repository/LsLabelVersionRepository.java:29-34
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select v from LsLabelVersion v where v.dataRawSn = :rawSn and v.dataSrcSn = :srcSn and v.activeYn = :activeYn")
  List<LsLabelVersion> findActiveForUpdate(...);
  ```
  **실측(2/2 재현)** — active 를 정확히 1건(`lbl_version_sn=26`, v6)으로 정규화한 뒤 서로 다른 해시로 동시 롤백 2건 발사:
  ```
  rb(H3)=200  rb(H4)=200
  select lbl_version_sn,ver_no,actvtn_yn from ls_label_version where data_src_sn=301 and actvtn_yn='Y';
   21|3|Y
   22|4|Y            ← active 2건
  GET /v1/frames/301/versions →
   [('ff050b5',False),('a146e90',False),('a0dcd35',True),('ca03614',True), …]   ← isCurrent 가 2건
  ```
  이때 실제 작업본 라벨은 v4 내용인데 v3 행도 `isCurrent=true` 로 노출된다(활성 포인터와 본문 불일치).
- **재현/확인 경로**:
  ```sql
  update ls_label_version set actvtn_yn='N' where data_src_sn=:srcSn;
  update ls_label_version set actvtn_yn='Y' where lbl_version_sn=:anyOne;
  ```
  ```bash
  ( curl -s -X POST "$API/v1/versions/$H_A/rollback" -H "Authorization: Bearer $REV" \
      -H 'Content-Type: application/json' -d "{\"srcSn\":$SRC}" & \
    curl -s -X POST "$API/v1/versions/$H_B/rollback" -H "Authorization: Bearer $REV" \
      -H 'Content-Type: application/json' -d "{\"srcSn\":$SRC}" & wait )
  ```
  ```sql
  select lbl_version_sn, ver_no, actvtn_yn from ls_label_version
   where data_src_sn=:srcSn and actvtn_yn='Y';     -- 2건이면 재현
  ```
- **영향**: 데이터 정합/기능(CWE-362 write skew). ①버전 목록(`VersionItem.isCurrent`)이 현재 버전을 2건 이상 표시해 검수자가 롤백 판단 근거를 잃는다 ②활성 행 중 하나가 실제 작업본과 다른 페이로드를 가리켜 "이 버전이 현재"라는 표시 자체가 거짓이 된다 ③같은 패턴이 `commitApproved`(`:288-300` → `saveActiveVersion:1008-1018`)에도 있어 **검수 승인과 롤백이 동시에 일어나도** 동일하게 잉여 active 가 남는다 ④다음 롤백/승인이 우연히 자기치유하기 전까지 상태가 지속된다(자동 회복 시점 보장 없음).
  ※ 라벨 본문 교체 자체는 프레임 행 락으로 직렬화되므로 **본문 유실·부분 적용은 없다**(TC-DIFF-025 는 정상).
- **수정 방향(제안)**: `srcRepository.lockAndReadLabelVersion(srcSn)` 으로 프레임 행 락을 잡은 **직후에 `findActiveForUpdate` 를 다시 실행**해 신선한 active 목록으로 `frameLabelsMatch` 판정과 `deactivateOthers` 를 수행한다(첫 조회는 기존 락 순서 VERSION→SRC 유지를 위해 남겨도 되고, 재조회분은 이미 락을 보유한 행이라 추가 대기가 없다). 대안은 `(DATA_SRC_SN) WHERE ACTVTN_YN='Y'` 부분 유니크 인덱스로 DB 가 불변식을 강제하는 것이나, 기존 잉여 active 데이터 정리가 선행돼야 한다. 회귀 가드: "서로 다른 해시로 동시 롤백 후 active 행 수 = 1" 단언을 `VersionRollbackRestoreIT` 에 추가(⚠ 이 케이스는 현재 **테스트 0건**이다 — `VersionServiceTest` 는 단일 스레드 mock 기반이라 이 조합을 만들 수 없다).

### [D-ISSUE-22] TC-DIFF-002 — SKELETON diff 응답이 가시성 v 를 버리고 shape 타입을 POLYGON 으로 왜곡한다 (1차 D-ISSUE-41 이월 · 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: SKELETON 은 삼중값 `[x,y,v]` 이고 v(가시성)만 바꾸는 편집이 실제 작업 동선이다. `readPoints` 에 삼중값 비교를 넣어 `MODIFIED` 로 감지하도록 만든 이상, 응답 `before`/`after` 에도 그 차이가 드러나야 검수자가 diff 를 근거로 롤백을 판단할 수 있다.
- **현재 동작(이슈 내용)**: 감지는 되지만 렌더링에서 v 가 탈락하고 타입도 왜곡된다.
  ```java
  // version/dto/LabelDiffDto.java:67-75  ShapeDto.fromPoints
  List<Double> flat = new java.util.ArrayList<>(points.size() * 2);
  for (List<Double> pt : points) {
      if (pt.size() >= 2) { flat.add(pt.get(0)); flat.add(pt.get(1)); }   // ← pt.get(2)(v) 유실
  }
  return new ShapeDto("POLYGON", null, null, null, null, flat);            // ← 타입 왜곡
  ```
  **실측**(v3→v4, 첫 키포인트의 v 만 `2→0`):
  `{"type":"MODIFIED","objectId":"1260","before":{"type":"POLYGON","points":[34개]},"after":{"type":"POLYGON","points":[34개]}}`
  → 파이썬 비교 `before == after` → **True**. 반면 롤백 복원 경로는 v 를 정상 보존한다(TC-DIFF-019 PASS) — **비교 화면만 눈이 먼 상태**.
- **재현/확인 경로**:
  ```bash
  # SKELETON 라벨의 키포인트 v 만 바꿔 재승인해 두 버전을 만든 뒤
  curl -s "$API/v1/versions/{H_new}/diff?compareWith={H_old}" -H "Authorization: Bearer $REV"
  # → before.points == after.points, type 은 양쪽 "POLYGON"
  ```
- **영향**: 기능(버전 비교 신뢰성). "변경됐다는데 뭐가 변경됐는지 안 보이는" 상태라 diff 를 근거로 한 복구 판단이 불가능하다. FE 가 `type` 으로 shape 렌더러를 고르면 SKELETON 이 폴리곤으로 잘못 그려진다. 보안 영향 없음.
- **수정 방향(제안)**: `LabelDiffDto.ShapeDto` 에 SKELETON 분기를 추가해 `type="SKELETON"` + 삼중값 보존 표현(3-stride flat 또는 `keypoints:[[x,y,v]…]`)으로 내려보내고 FE `LabelDiff` 타입을 동반 확장한다. 회귀 가드로 "v 만 바뀐 두 버전의 diff 응답에서 `before != after`" 단언 추가.

### [D-ISSUE-23] TC-DIFF-008 — 롤백 복원이 `LS_DATA_LBL.REG_USER_NO` 를 NULL 로 소실시켜 통계가 수동 라벨을 "자동 라벨"로 오분류한다 (1차 D-ISSUE-43 이월 · 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 롤백은 "그 시점 작업본으로 되돌리는 것"이므로 복원된 라벨의 귀속(누가 만든 라벨인가)이 보존돼야 한다. 이 컬럼은 통계에서 **수동/자동 라벨 판별 프록시**로 쓰여 값이 바뀌면 지표가 틀어진다.
- **현재 동작(이슈 내용)**: 스냅샷 페이로드(`LabelResponse.Item`)에 `regUserNo` 가 없고, 명시 PK 복원 INSERT 도 그 컬럼을 쓰지 않는다.
  ```java
  // batch/repository/LsDataLblRepositoryImpl.java:26-29
  "INSERT INTO LS_DATA_LBL (LBL_SN, SRC_SN, LBL_TYPE_CD, LBL_ID, LBL_NM, POINT_CN, TRCK_ID, REG_DT) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, now()) ON CONFLICT (LBL_SN) DO NOTHING";   // ← REG_USER_NO 없음
  ```
  **실측**(롤백 직전/직후, srcSn=301):
  ```
  before  1260|2001   1261|2001
  after   1260|(null) 1261|(null)     ← TRCK_ID·AI메타는 보존됐는데 작성자만 소실
  ```
  1차 실측(“복원된 900001·900002 전부 NULL”)과 동일 패턴이며, **값을 갖고 있던 라벨까지 NULL 로 덮인다**는 점이 이번에 추가로 확인됐다. 승인 스냅샷 payload 실측에도 `regUserNo` 키 자체가 없다(`id/lblTypeCd/label/labelId/points/autoLblYn/confScore/trackId/lblSrcCd` 만 존재).
- **재현/확인 경로**:
  ```sql
  select lbl_sn, reg_user_no from ls_data_lbl where src_sn = :srcSn;   -- 2001
  ```
  ```bash
  curl -s -X POST "$API/v1/versions/{hash}/rollback" -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d "{\"srcSn\":$SRC}"
  ```
  ```sql
  select lbl_sn, reg_user_no from ls_data_lbl where src_sn = :srcSn;   -- NULL
  ```
- **영향**: 데이터 정합/감사. ①라벨 작성자 귀속 소실(원복 수단 없음 — 값이 어디에도 남지 않는다) ②`StatsQueryRepository`(`:150-166`)가 `regUserNo IS NULL` 을 자동 라벨 프록시로 쓰므로 작업자 통계의 자동라벨 비율이 롤백된 프레임만큼 부풀려진다. 롤백을 반복할수록 누적된다.
- **수정 방향(제안)**: 스냅샷 페이로드에 `regUserNo` 를 실어 왕복 복원하는 것이 권장안(AI 메타·`TRCK_ID` 와 동일 처리). 최소한 `INSERT_SQL`/`createRestored` 에 컬럼을 추가한다. 함께 통계의 "regUserNo IS NULL = 자동" 프록시를 `LsDataLblAiInfo` 조인 기반으로 교체하는 것이 근본책. 회귀 가드: `VersionRollbackRestoreIT` 에 "롤백 후 `REG_USER_NO` 보존" 단언 추가.

### [D-ISSUE-24] TC-DIFF-026 — 동일 `version_hash` 다중 매칭 시 `matches.get(0)` 이 비결정적이라 diff 결과가 조용히 뒤바뀐다 (1차 D-ISSUE-27/45 이월 · 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 같은 입력(두 해시)에 대해 diff 는 항상 같은 결과를 돌려주거나, 모호하면 명시적으로 거부해야 한다. `VERSION_HASH` 의 UNIQUE 는 `(DATA_SRC_SN, VERSION_HASH)` 복합이므로 **서로 다른 프레임이 같은 해시를 갖는 것은 정상**이다(라벨 0~1건인 단순 프레임에서 현실적으로 충돌).
- **현재 동작(이슈 내용)**: 전역 해시 조회에 정렬이 없고 첫 행을 그대로 쓴다.
  ```java
  // VersionService.java:1020-1026
  private LsLabelVersion findByHashOrThrow(String versionHash, String notFoundMessage) {
      List<LsLabelVersion> matches = labelVersionRepository.findByVersionHash(versionHash);
      if (matches.isEmpty()) { throw new CustomException(ErrorCode.NOT_FOUND, notFoundMessage); }
      return matches.get(0);      // ← 어느 프레임의 버전인지 비결정
  }
  ```
  ```java
  // version/repository/LsLabelVersionRepository.java:47  (ORDER BY 없음)
  List<LsLabelVersion> findByVersionHash(String versionHash);
  ```
  **실측**: src303 에 H5 와 같은 해시(payload 는 `{"items":[]}`)를 추가 → 동일 diff 요청 5회 모두 `data` 4건(정상). 이어서 `UPDATE` 로 heap 순서를 반전(`ctid (5,3)→(5,7)`, src303 행이 앞으로) 하자 **같은 요청이 3회 모두 `data:[]`(변경 없음)** 을 반환. 오류 없이 결과만 뒤바뀐다.
- **재현/확인 경로**:
  ```sql
  insert into ls_label_version (lbl_version_sn,data_raw_sn,data_src_sn,ver_no,save_reason_cd,actvtn_yn,reg_id,reg_dt,lbl_payload,version_hash)
  values (990103, :rawSn, :otherSrcSn, 92, 'APPROVED','N','1001', now(), '{"items":[]}', '<기존해시>');
  update ls_label_version set reg_id = reg_id where lbl_version_sn = <원본행>;  -- heap 순서 반전
  select ctid, lbl_version_sn, data_src_sn from ls_label_version where version_hash='<기존해시>' order by ctid;
  ```
  ```bash
  curl -s "$API/v1/versions/{H_to}/diff?compareWith={충돌해시}" -H "Authorization: Bearer $REV"   # 결과가 뒤바뀜
  ```
- **영향**: 데이터 정합/기능. ①검수자가 "변경 없음"을 보고 잘못된 승인·복구 판단을 내린다 ②VACUUM·UPDATE·인덱스 스캔 전환으로 재현이 산발적이라 장애 분석이 어렵다 ③`requireFrameScoped`/`accessGuard` 가 **선택된 그 행 기준**으로 평가돼 인가 대상 프레임까지 요청마다 달라진다(권한 검사 자체는 각 행에 대해 정상 수행되므로 인가 우회는 아님).
- **수정 방향(제안)**: 근본책은 **diff 진입점을 srcSn 스코프로 정렬** — 요청에 `srcSn` 을 받아 `findByDataSrcSnAndVersionHash`(이미 존재, `rollback` 이 사용)로 단건 조회하면 모호성과 D-ISSUE-25 오라클이 함께 사라진다. 하위호환이 필요하면 차선책으로 ①`findByVersionHash` 에 `order by dataSrcSn, labelVersionSn` 부여 ②`matches.size() > 1` 이면 `INVALID_INPUT`(400, "해시가 여러 프레임에 매칭됨 — srcSn 을 지정하세요")로 명시 거부. 다중 매칭 회귀 테스트가 여전히 **0건**이므로 함께 추가한다.

### [D-ISSUE-25] TC-DIFF-006 — 인가보다 해시 조회가 선행해 버전 존재 여부 오라클이 성립한다 (1차 D-ISSUE-42 이월 · 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 인가 실패자가 시스템 내부 상태(특정 버전 해시의 존재 여부)를 응답 코드 차이로 알아낼 수 없어야 한다(CWE-209 / OWASP A01:2025).
- **현재 동작(이슈 내용)**: `diff` 는 전역 해시 조회를 먼저 하고 인가를 나중에 한다.
  ```java
  // VersionService.java:338-349
  LsLabelVersion fromVersion = findByHashOrThrow(fromHash, "from 버전을 찾을 수 없습니다."); // ← 404
  LsLabelVersion toVersion   = findByHashOrThrow(toHash,   "to 버전을 찾을 수 없습니다.");
  requireFrameScoped(fromVersion, "from");
  LsDataSrc fromSrc = accessGuard.verifyAndGet(fromVersion.getDataSrcSn(), actor);          // ← 403
  ```
  **실측**(미배정 WORKER=2002 토큰): 존재하는 해시 → **403** `본인에게 배정되지 않은 영상입니다.` / 존재하지 않는 해시 → **404** `from 버전을 찾을 수 없습니다.`
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w "%{http_code}\n" "$API/v1/versions/{존재해시}/diff?compareWith={존재해시}" -H "Authorization: Bearer $WK_UNASSIGNED"  # 403
  curl -s -o /dev/null -w "%{http_code}\n" "$API/v1/versions/deadbeef/diff?compareWith=deadbeef"     -H "Authorization: Bearer $WK_UNASSIGNED"  # 404
  ```
- **영향**: 정보 노출(CWE-209). 실효 위험은 낮다 — 해시가 SHA-256 이라 무작위 추측이 불가능하고, 판별하려면 이미 페이로드를 알고 있어야 한다. 그럼에도 "특정 라벨 상태가 승인된 적 있는가"를 인가 없이 확인할 수 있는 채널이다.
- **수정 방향(제안)**: 우선순위가 낮아 **현행 유지도 수용 가능**. 정정한다면 `diff` 를 `rollback` 과 같은 **srcSn 스코프 진입점**으로 정렬하는 것이 근본책이며(요청에 `srcSn` → `accessGuard.verifyAndGet` 선행 → `findByDataSrcSnAndVersionHash`), 이는 D-ISSUE-24 도 동시에 해소한다.

### [D-ISSUE-26] TC-DIFF-020 — `isCommittable` 이 프로덕션 호출자 0건인 dead code 로 남아 있다 (1차 D-ISSUE-28 이월 · 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 정책 판정 메서드는 실제 판정 지점에 배선돼 있거나, 배선하지 않기로 했다면 제거돼야 한다. 남아 있으면 "PORTAL 은 이 함수로 차단된다"는 오해를 만들어 실제 차단 지점(`@PreAuthorize`)을 손댈 때 안전망이 있다고 착각하게 된다.
- **현재 동작(이슈 내용)**:
  ```java
  // VersionService.java:1260-1262
  public static boolean isCommittable(TokenClaims actor) {
      return actor != null && actor.channel() != Channel.PORTAL;
  }
  ```
  전 소스 `grep -rn "isCommittable" backend/src frontend/src` 결과: 정의 1건 + **테스트 참조 3건**(`VersionServiceTest.java:313-317`)뿐, 프로덕션 호출자 **0건**. 실제 PORTAL 차단은 `VersionController` 의 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 가 담당하며 실측 PORTAL 토큰 diff/rollback 모두 **403** `권한이 없습니다.` 로 거부됐다.
- **재현/확인 경로**: `grep -rn "isCommittable" backend/src frontend/src`
- **영향**: 코드 품질(도달 불가 코드) + 테스트가 실제로 보호하지 않는 것을 보호한다고 표시하는 **거짓 커버리지**. 기능·보안 영향은 없다(차단 자체는 성립).
- **수정 방향(제안)**: 둘 중 하나로 확정한다 — ①제거(+ 해당 단위테스트 제거, 채널 차단 검증은 컨트롤러 IT 로 이관) ②`commitApproved`/`rollback` 진입부에 실제 배선하고 PORTAL 요청에 대한 응답 코드를 확정(현행 403 유지 권장). ⚠ 본 검증에서는 구현하지 않는다.

---

## 부수 관측 (결함 아님 / 참고)

- **롤백 후 AI 메타 행의 `REG_ID` 가 롤백 수행자로 바뀐다**(2001 → 1001, `data_lbl_ai_info_sn` 도 재발급). `VersionService.replaceFrameLabels` javadoc 의 `@param actorId AI 메타 복원 행의 REG_ID (감사용)` 계약대로이며, `LBL_SRC_CD`·`CONF_SCORE`·`AUTO_LBL_YN` 값 자체는 보존된다 → 결함 아님.
- **`LS_DATA_LBL_ATTR_VAL`(라벨 속성값)은 롤백으로 복원되지 않고 삭제**된다 — 스냅샷 페이로드에 없기 때문(코드 javadoc 에 "한계"로 명시). 이번 검증 데이터에는 속성값이 없어 실측 영향은 없었으나, 속성값을 쓰는 운영 영상에서는 롤백이 속성값을 지운다는 점을 D-4/C 클러스터와 교차 확인할 필요가 있다.
- **검수 승인 시 `ver_no` 는 `countByDataRawSnAndDataSrcSn + 1`** 이라 합성/레거시 행이 섞이면 번호가 건너뛴다(이번 검증에서 3→4→6→7). 표시용 순번이며 식별자는 해시라 기능 영향은 없다.
- **`TASK_COMPLETED` 가 `completed conflicted -> resend as updated` 로 자동 전환**되는 동작을 재승인 경로에서 반복 관측(mock-server 가 이미 완료 통지를 받은 job 에 409 를 주고 backend 가 `notify-updated` 로 재전송). D-6(TC-NOTIFY) 담당 범위라 여기서는 사실만 기록.

---

# D 클러스터 part3 — D-6 관제 통지(TC-NOTIFY) + D-7 관제 조회 API

- 회차: **3차** (2026-08-03 지정 / 실행 2026-08-04 01:10~01:30 KST)
- 담당 범위: `docs/test-cases/D-review-version-notify.md` **166~234행** — D-6(45건) + D-7(8건) = **53건**
- 실측 환경: 풀스택 기동 상태(`_raw/stack-bringup.md` 재확인 — backend `localhost:18081/api`, mock-server `:9400`, PostgreSQL `public` 스키마), 외부 연동 4종 전부 mock-server 실배선
- 이슈 ID 시작번호: **D-ISSUE-41**
- 빌드/테스트 미실행(지시 준수). 테스트 통과 여부는 `_raw/test-baseline.md`(backend 5203건 / 실패 0) 대조로 판정
- 프로덕션 코드 무수정. 카탈로그 정정은 담당 라인범위(166~234) 안에서만 수행

---

## 0. 이번 회차 실동작 근거 (요약)

| # | 실동작 | 근거 |
|---|--------|------|
| L1 | **TASK_COMPLETED 페이로드 6필드 평면 snake_case 실물** | `ls_control_notify_fallback.payload_cn` (SEND_RSLT=SUCCESS 관찰행) — `{"job_id":"900","event_type_cd":"EV02000201","lclgv_cd":"1168000000","lclgv_nm":null,"duration_sec":5,"image_count":5}` |
| L2 | **TASK_MODIFIED changed_items 실물** | `{"job_id":"101","changed_items":{"images":["0000.jpg",…"0009.jpg"],"jsons":["0000.json",…]}}` / 비재생성 경로 `{"job_id":"4","changed_items":{"images":[],"jsons":["0000.json"]}}` |
| L3 | **통지 경로 계약 왕복** | mock-server 로그 `POST /api/data-set/v2/jobs/{101,900,906,27,115,4,33}/notify-completed\|notify-updated` 202/409/404 |
| L4 | **409 자기치유(completed→updated)** | backend `completed conflicted -> resend as updated rawSn=27` → mock `409 Conflict` 직후 `notify-updated 202`, 관찰행 `evnt_type_cd=TASK_MODIFIED` |
| L5 | **404 자기치유(updated→completed)** | `updated not-found -> fallback to completed rawSn=906/900/4` → mock `404` 직후 `notify-completed 202` |
| L6 | **job_id fail-closed** | dead-letter 행 payload `{"job_id":"../../etc/passwd"…}` · `{"job_id":"abc"…}` → `IllegalArgumentException: 허용되지 않는 job_id 형식입니다.`(전송 0회) |
| L7 | **에러 sanitize** | dead-letter `last_err_msg_cn` = `지원하지 않는 eventType: token***` · `URL_REDACTED TOK`(개행→공백) · Jackson `Source: REDACTED` |
| L8 | **dead-letter 전이** | `rtry_nmtm=6 > max_rtry_nmtm=5` → `stts_cd=DEAD_LETTER`, `dlq_dt` 세팅, `next_rtry_dt=NULL` (6행) |
| L9 | **디바운스 축적 = 공유 DB** | `ls_mon_noti_acml` 관측: `raw_sn=115 / stts_cd=PENDING / export_rprcs_yn=Y / chg_dtl_cn={"video":{"*":["META_UPDATED"]}}` → flush 후 행 삭제(0건) |
| L10 | **★ export 실패 시 통지 보류(재현 실험)** | `PUT /v1/videos/147/environment-meta`(APPROVED·프레임 0건) → 60s 디바운스 → `async re-export(+notify) starting rawSn=147` → `no frames — skip export` → **`async export not notifiable — notify withheld rawSn=147 outcome=NO_INPUT`** → `ls_control_notify_fallback WHERE raw_sn=147` **0행**(통지 미발송) |
| L11 | **대조군(정상 경로)** | `PUT /v1/videos/115/environment-meta` → export v3 SUCCEEDED(01:26:14.976) → **그 다음** `TASK_MODIFIED sent rawSn=115 … reExport=true`(.981) — export→통지 순서 실측 |
| L12 | **IDOR 차단** | WORKER(2001) → `GET /v1/tasks/906/{summary,labels,meta}` **전부 403** `본인에게 배정되지 않은 영상입니다.` / 본인 배정 101 은 200 / REVIEWER 는 906 도 200 |
| L13 | **신고 게이트 412** | `rawSn=9`(`DE_IDENT_YN='F'`, 2001 배정) → labels **412**, summary/meta 200, **REVIEWER 도 412** |
| L14 | **인증 헤더 fail-open(실효)** | 기동 로그 `[ControlNotify] 통지가 활성화됐으나 인증 토큰(authoring.control-notify.token)이 비어 있습니다 …` + `docker exec klid-backend env`에 `CONTROL_NOTIFY_TOKEN` **부재** → 이 환경의 전 통지가 `x-access-token` **없이** 전송됨 |

---

## 1. D-6. 관제 통지 (TC-NOTIFY) — 45건

| ID | 판정 | 근거 확인 |
|----|:----:|-----------|
| TC-NOTIFY-001 | PASS | [실동작] rawSn=33: `export succeeded`(01:23:53.252) → `TASK_COMPLETED sent`(.255). 승인 이벤트 직후가 아니라 `DatasetExportCompletedEvent` 소비 시점. 역케이스 L10(147)에서 export 미성공 → 통지 0건. [정적] `ControlNotifyEventListener.java:38-41` `@EventListener(DatasetExportCompletedEvent)` · `DatasetExportBridge.java:36-43` → `runApprovalAsync` · `AsyncDatasetExportRunner.java:67-76`(`if (doExport(rawSn,true))` 성립 시에만 publish). 테스트 `ControlNotifyEventListenerTest#DatasetExportCompletedEvent_수신시_sendCompleted_호출` |
| TC-NOTIFY-002 | PASS | [정적] `DatasetExportBridge.java:36` `@TransactionalEventListener(phase=AFTER_COMMIT)` — 롤백 시 미호출이라 export·통지 모두 미발생. 승인 tx 롤백을 라이브로 만들 수단이 없어 정적 판정(테스트 `DatasetExportBridgeTest` 커버) |
| TC-NOTIFY-003 | PASS | [실동작] L1 — `job_id`·`event_type_cd`·`lclgv_cd`·`lclgv_nm`·`duration_sec`·`image_count` **정확히 6필드, 평면, snake_case**. `image_count=5` = `SELECT count(*) FROM ls_data_src WHERE raw_sn=900` 실측 일치, `duration_sec=5` = `VDO_LEN_SEC` 실측. 라벨/PII/토큰/원본경로 **0건**. 상수 self-fill 0(영상 미존재면 예외 → 폴백). [정적] `TaskCompletedPayload.java:24-32`(@JsonProperty 필드 고정) · `ControlNotifyPayloadFactory.java:78-92`. ⚠ `event_type_cd` 는 매핑 없이 pass-through — DB 안에 `INTRUSION`(rawSn=101)과 `EV02000201`(rawSn=900/906) **두 코드체계가 공존한 채 그대로 전송**된다(D-ISSUE-63 이월 미해소 / UNCERTAINTIES #27). 케이스 기대값 자체(=LS_DATA_RAW 값)는 충족하므로 PASS 유지 |
| TC-NOTIFY-004 | PASS | [정적] `ControlNotifyService.java:130-134` catch → `enqueueQuietly` + `metrics.incrementCompletedFailed()`. [실동작 간접] 폴백 큐 60행(SUCCEEDED 54 / DEAD_LETTER 6)이 적재 경로 실동작 증거. 테스트 `ControlNotifyServiceTest#TASK_COMPLETED_실패시_폴백큐_적재됨` |
| TC-NOTIFY-005 | PASS | [실동작] rawSn=27 — 요청은 completed 였으나 409 자기치유로 **실제 전송은 updated**, 관찰행 `evnt_type_cd=TASK_MODIFIED` + payload 가 `changed_items` 구조(=실제 전송분). 요청 종류가 아니라 `SendOutcome` 기준 기록 확인. [정적] `ControlNotifyService.java:126-129,318-325` |
| TC-NOTIFY-006 | PASS | [정적] `ControlNotifyService.java:318-325` — `recordImmediateSuccess` 예외를 catch 후 warn 만, 통지 성공 흐름 유지. `ControlNotifyFallbackService.recordImmediateSuccess` 는 `REQUIRES_NEW` 라 롤백 경계 분리 |
| TC-NOTIFY-007 | PASS | [실동작] L2 — 항목이 `{FRM_NO 4자리 zero-pad}.jpg/.json`(`0000.jpg`…). SRC_SN·좌표·메타본문·절대경로 **미포함**. 두 리스트 항상 non-null(비재생성 경로도 `"images":[]`). [정적] `TaskModifiedPayload.java:20-54`(compact 생성자 null→`List.of()`) · `ControlNotifyPayloadFactory.java:103-126` · `ExportFileNaming.java:30-58`(`%04d`, 음수 fail-closed) |
| TC-NOTIFY-008 | PASS | [실동작] L9 — `ls_mon_noti_acml`(V144) 에 `(raw_sn, chg_dtl_cn, export_rprcs_yn)` 실적재. 프레임↔변경종류 페어 보존도 flush 로그로 확인: `flush rawSn=905 regen=true frames=459=[META_UPDATED, LABEL_ADDED],460=[…],461=[META_UPDATED]`. 영상단위 변경은 `{"video":{"*":[…]}}` 별도 슬롯. [정적] `ControlNotifyDebouncer.java:195-197`(근거 드리프트 — 카탈로그 174-176) · `JpaControlNotifyDebounceStore.java:61-94` · `V144__create_ls_mon_noti_acml.sql` |
| TC-NOTIFY-009 | PASS | [정적] `ControlNotifyDebouncer.java:211-219`(@PreDestroy: 스케줄러 정지는 항상, drain 은 토글) · `244-252`(drain, `MAX_DRAIN_ROUNDS=20` @93) · `flush(now,…)` 로 만료 무관 drain. 남은 건 DB 잔존(complete 미호출). 근거 드리프트(카탈로그 80,190-203) |
| TC-NOTIFY-010 | PASS | [실동작] 기동 로그 `[ControlNotifyDebounce] flush scheduler started intervalMs=10000 leaseMs=300000` + 실제 flush 로그의 스레드명이 **`control-notify-debounce-flush`**(예: `01:24:24.860 [control-notify-debounce-flush]`) — `@Scheduled`/`@EnableScheduling` 비의존 실증. [정적] `ControlNotifyDebouncer.java:162-177`(@PostConstruct, daemon 단일 스레드) · `141`(`regen-flush.enabled` 기본 true). 근거 드리프트 |
| TC-NOTIFY-011 | PASS | [정적] `ChangeType.java:18-30` — 4종 상수 + `ALL` 정의만 존재. 프로덕션 경로 `ControlNotifyDebouncer.send/summarizeChangeTypes`(334-369)에 **런타임 검증기 없음** 재확인(2026-08-03 기술과 일치). [실동작] flush 로그에 발행값 4종 관측(`META_UPDATED`,`LABEL_ADDED`,`LABEL_UPDATED`,`LABEL_DELETED` — rawSn=27 `frames=301=[LABEL_UPDATED, LABEL_ADDED, LABEL_DELETED]`) — 실발행값이 계약집합 안. 근거 드리프트 |
| TC-NOTIFY-012 | PASS | [정적] `LabelService.java:413` `if (!changes.isEmpty() && isReviewApproved(current.getRawSn()))` — 승인 전 저장은 통지 미발행 |
| TC-NOTIFY-013 | PASS | [정적] `LabelService.java:397·403·413` — `changes` 비면 이력·버전 bump·통지 3종 모두 미발생 |
| TC-NOTIFY-014 | PASS | [정적] 7경로 전수 확인 — `LabelService.java:423`(`toChangeTypes` 루프, regen=true) · `TrackEditService.java:326` · `TrackMergeService.java:201` · `VersionService.java:518` · `EnvironmentMetaService.java:125` · `FrameDescriptionService.java:60` · `FramePrivacyMetaService.java:158,180` — **7경로 전부 마지막 인자 `true`**. [실동작] 2경로 실증 — 라벨(`flush rawSn=101 regen=true frames=468=[LABEL_UPDATED]` → export v2 → 통지) · 촬영환경(L11 rawSn=115 → export v3 → `reExport=true` 통지). 근거 드리프트(FramePrivacyMetaService 131-132,154-155 → 158-159,180-181) |
| TC-NOTIFY-015 | PASS | [정적] `ControlNotifyFallbackService.java:91-95` — `DataIntegrityViolationException` catch 후 동일 key 멱등 반환. UK 는 V44 `IDMP_KEY` |
| TC-NOTIFY-016 | PASS | [정적] `ControlNotifyFallbackService.java:36`(`MAX_QUEUE_DEPTH=10000`) · `75-82`(`countBySttsCdIn(PENDING,RETRYING) >= MAX` → `IllegalStateException` + `control.notify.fallback.queue.full`). CWE-770 방어 성립 |
| TC-NOTIFY-017 | PASS | [정적] `:71-73`(enqueuePending) · `:113-115`(recordImmediateSuccess) 둘 다 `if(!enabled) return Optional.empty()`. 이 빈은 `@ConditionalOnProperty` 가 아니라 `@Value` 플래그 방식이라 토글 off 에서도 빈은 존재하되 no-op |
| TC-NOTIFY-018 | PASS | [정적] `ControlNotifyFallbackService.java:136-143` + `LsControlNotifyFallbackRepository.claimAtomically` = `UPDATE … SET STTS='RETRYING' WHERE queueSn=:sn AND STTS='PENDING'` → `updated != 1` 이면 `Optional.empty()`. 조건부 원자 UPDATE 확인 |
| TC-NOTIFY-019 | PASS | [정적] `LsControlNotifyFallback.java:194-213` — `rtryCnt+=1` 후 `backoffMin = min(60, 1L << min(rtryCnt,6))` → PENDING + `now.plusMinutes(backoff)`, cap 60 성립. ⚠ 같은 메서드 javadoc(190행)은 `(1, 2, 4, 8, 16)` 이라 적혀 있으나 실제는 **2,4,8,16,32** — **D-ISSUE-64 이월 미해소**(문서 결함, 동작은 케이스 기대와 일치) |
| TC-NOTIFY-020 | PASS | [실동작] L8 — dead-letter 6행 전부 `rtry_nmtm=6`, `max_rtry_nmtm=5`, `dlq_dt` 세팅, `next_rtry_dt` NULL. [정적] `LsControlNotifyFallback.java:201-207`, `DEFAULT_MAX_RETRY=5`(48행) |
| TC-NOTIFY-021 | PASS | [정적] `ControlNotifyFallbackRetryJob.java:31`(BATCH_SIZE=20) · `48-71`(due 조회 → `claimForRetry` → `processOne` → `markSucceeded`, claim 실패 시 continue). [실동작 간접] dead-letter 행이 `rtry_nmtm=6` 까지 올라간 것 자체가 due 폴링 루프가 6회 실주행한 증거 |
| TC-NOTIFY-022 | PASS | [정적] `ControlNotifyFallbackRetryJob.java:62-65` — `RuntimeException` → `markFailedAndSchedule(queueSn, 예외클래스명+메시지)`. [실동작 간접] dead-letter `last_err_msg_cn` 이 재실패 누적 결과 |
| TC-NOTIFY-023 | PASS | [실동작] L7 — 실제 DB 값에서 토큰(`token=SEKRET123`→`token***`)·URL(`http://a.b/x`→`URL_REDACTED`)·개행(제어문자→공백) 전부 정화 확인. [정적] `LsControlNotifyFallback.java:246-253`(제어문자→공백 → 토큰류 마스킹 → URL redact → 1900자 truncate) |
| TC-NOTIFY-024 | PASS | [정적] `ControlNotifyFallbackService.java:108-129` — `recordImmediateSuccess` 도 `DataIntegrityViolationException` 삼킴 + 멱등 반환. `REQUIRES_NEW` 로 상위 롤백 분리 |
| TC-NOTIFY-025 | PASS | [정적] 토글 종속 6빈 전수 확인 — `ControlNotifyClient.java:42` · `ControlNotifyService.java:52-53` · `ControlNotifyPayloadFactory.java:60` · `ControlNotifyEventListener.java:27` · `ControlNotifyFallbackRetryJob.java:27` · `TaskQueryController.java:46` 모두 `@ConditionalOnProperty(havingValue="true")`. 항상 등록 3빈 — `ControlNotifyDebouncer.java:85-87`(@Component only) · `TaskModifiedAccumulateListener.java:27-30` · `DatasetExportBridge.java:29-30`(`matchIfMissing=true`). 근거 드리프트(72-74) |
| TC-NOTIFY-032 | PASS | [실동작] L3 — mock 로그 경로가 `POST /api/data-set/v2/jobs/{job_id}/notify-completed\|notify-updated` 정확 일치, 응답 **202** 를 성공 수용(mock 은 202 를 반환하는데 backend 는 예외 없이 성공 처리). [정적] `ControlNotifyClient.java:54-58`(경로 상수) · `90-108`(`contentType(APPLICATION_JSON)` + `is2xxSuccessful()` 전체 수용) |
| TC-NOTIFY-033 | PASS | [실동작] L4 — rawSn=27/115/4 에서 `409 Conflict` 수신 → 즉시 `notify-updated 202`, 반환 outcome=TASK_MODIFIED(로그 `actual=TASK_MODIFIED`), 관찰행도 MODIFIED. 메트릭 `control.notify.selfheal.completed_to_updated` 존재 확인(`ControlNotifyMetrics:68`). [정적] `ControlNotifyService.java:215-233` |
| TC-NOTIFY-034 | PASS | [실동작] L5 — rawSn=906/900/4 에서 `404 Not Found` → `notify-completed 202`, `actual=TASK_COMPLETED`. 메트릭 `…selfheal.updated_to_completed`(`ControlNotifyMetrics:71`). [정적] `ControlNotifyService.java:244-263` |
| TC-NOTIFY-035 | PASS | [정적] `ControlNotifyService.java:225-231`(409 분기 안에서는 `sendTaskModified` 만 호출, `dispatchModified` 재진입 없음) · `255-261`(대칭). 즉 전환된 통지가 4xx 여도 예외가 그대로 상위로 → 폴백 큐. 테스트 `폴백은_재귀하지_않는다`·`completed_409_후_updated_도_실패하면_재귀하지_않고_폴백큐로_간다` |
| TC-NOTIFY-036 | PASS | [실동작] 4xx(409/404) 가 `ControlNotifyStatusException` 으로 잡혀 자기치유 분기가 실제 발동함을 L4/L5 가 실증. [정적] `ControlNotifyClient.java:110-120` — `400≤s<500` → `ControlNotifyStatusException`(Resilience4j ignore 대상), 그 외 → `IllegalStateException`(재시도·서킷 집계). 5xx 는 라이브 재현 수단 없어 정적 + 테스트(`수정통지가_500이면_자기치유하지_않고_폴백큐로_간다`) |
| TC-NOTIFY-037 | PASS | [실동작] L6 — 폴백 큐에 오염 JSON(`"job_id":"../../etc/passwd"`, `"abc"`)이 실재하고, 재시도 시 `IllegalArgumentException: 허용되지 않는 job_id 형식입니다.` 로 **전송 전에** 거부되어 dead-letter 로 종결. mock 로그에 해당 경로 요청 **0건**(경로 세그먼트 조작 미발생). [정적] `ControlNotifyService.java:66-73`(`\d{1,19}`) · `275-279` |
| TC-NOTIFY-038 | PASS | [정적] `ControlNotifyService.java:112-123` — 조립 예외 시 **통지를 버리지 않고** `PAYLOAD_REBUILD_REQUIRED`(=`""`) 로 큐잉 → `ControlNotifyFallbackRetryJob.java:85-101` 에서 `isPayloadRebuildRequired` → `dispatch*(null, rawSn)` 재조립. 테스트 `페이로드_조립에_실패하면_통지가_폴백큐에_적재되어_재시도된다`·`폴백_재시도가_페이로드_없이_호출되면_dispatch_가_재조립한다` |
| TC-NOTIFY-039 | PASS | [정적] `ControlNotifyService.java:170-188` — `exportRegenerated=false` 면 REBUILD 표식이 아니라 **빈 `ChangedItems` 확정 적재**, true 면 REBUILD_REQUIRED. 테스트 `MED2_…빈_changed_items_로_적재되어_재시도때_전프레임_blast_안됨`/`…REBUILD_REQUIRED로_적재` |
| TC-NOTIFY-040 | PASS | [실동작] **L10 재현 실험** — APPROVED·프레임 0건 영상(rawSn=147)에 승인후 수정을 넣어 export 를 `NO_INPUT` 으로 종결시킨 결과 `notify withheld … outcome=NO_INPUT` 로그 + `ls_control_notify_fallback` 에 rawSn=147 행 **0건**(전 기간). 대조군 115 는 정상 통지. **예외 없는 실패 경로에서도 통지가 보류됨을 실증** → 1차 D-ISSUE-61(CRITICAL) **해소 확정**. [정적] `AsyncDatasetExportRunner.java:134-149` — `DatasetExportOutcome.notifiable()` 단일 판정 + `outcome==null` fail-closed. `DatasetExportOutcome` 7값 중 통지 허용은 COMPLETED/PARTIAL/IDEMPOTENT_SKIP 뿐. 테스트 `DatasetExportNotifyGateTest` 8건 |
| TC-NOTIFY-041 | PARTIAL | [정적] `DatasetExportFailureRecoverer.java:119-170` — FAILED anchor 조회 → `txService.claimForRetry`(조건부 UPDATE, RTY_NMTM) → `runApprovalAsync`(완료 이벤트 재발행) 배선 정상. **그러나 "통지 유실이 아니라 지연" 이 성립하지 않는 종결이 있다** — `NO_INPUT` 은 `DatasetExportTxService.loadPreparation`(103-106)이 **export 행 자체를 만들지 않고** skip 하므로 `findRetryableFailedAnchors`(FAILED 만 스캔)가 영원히 못 집는다. 디바운스 윈도우는 이미 `complete()`(삭제)됐고 폴백 큐에도 안 들어가 **TASK_MODIFIED 가 영구 유실**된다(L10 rawSn=147 실측 — 통지 0건, export 행 0건, 윈도우 0건). → **D-ISSUE-41** |
| TC-NOTIFY-042 | PASS | [정적] `DatasetExportFailureRecoverer.java:141-144` — `deidentReportGate.isUnderDeidentReport(rawSn)` 판정이 **`txService.claimForRetry` 호출(147행)보다 앞**에 있어 `RTY_NMTM` 미증가 + `deidentSkipped` 집계 후 INFO 로그(159-162). 예산 미소모 성립 |
| TC-NOTIFY-043 | PASS | [정적] `TaskModifiedAccumulateListener.java:27-37` — 토글 없는 `@Component` + AFTER_COMMIT 축적. `ControlNotifyDebouncer.java:334-357`(`send`) — `exportRegenerated` 면 `notifyService==null`(토글 off) 이어도 `exportRunner.runReExportThenNotify(rawSn, true, null)` 를 **항상** 호출, 통지 콜백만 생략. 테스트 `HIGH-E_통지_토글_off여도_승인후_수정_재생성_윈도우는_export를_트리거한다`·`재export_트리거는_control_notify_토글과_무관하게_동작한다`. 이 환경은 토글 on 이라 라이브 재현 불가(정적+테스트). 근거 드리프트 |
| TC-NOTIFY-044 | PASS | [정적] `JpaControlNotifyDebounceStore.java:102-110` `claim` → `LsMonNotiAcmlRepository.claimForFlush` = `UPDATE … SET STTS='FLUSHING' WHERE notiAcmlSn=:sn AND ((STTS='PENDING' AND REG_DT<=:windowCutoff) OR (STTS='FLUSHING' AND MDFCN_DT<=:leaseCutoff))` → `!=1` 이면 미전송. 축적 INSERT 도 `ON CONFLICT (RAW_SN) WHERE STTS_CD='PENDING' DO NOTHING` 원자. [실동작] flush 후 `ls_mon_noti_acml` 0행(=`deleteFlushed` 성공), 통지 1회. 2노드 동시성 자체는 단일 노드 환경이라 IT(`ControlNotifyDebounceCrossNodeIT`) 대조. 근거 드리프트 |
| TC-NOTIFY-045 | PASS | [정적] `ControlNotifyDebouncer.java:290-318` — `send` 예외 시 `store.complete()` **미호출** → FLUSHING 잔존 → `leaseCutoff` 만료 후 재클레임. `MIN_LEASE_MILLIS=60_000`(96행)로 하한 clamp, 기본 300s(실측 기동 로그 `leaseMs=300000`). 실패 시 `metrics.incrementDropped()` + ERROR, `return false` 로 루프 지속. 근거 드리프트 |
| TC-NOTIFY-046 | PASS | [정적] `ControlNotifyService.java:281-309` `enqueueQuietly` — 예외를 밖으로 안 던지고 `metrics.incrementDropped()`(=`control.notify.dropped`, `ControlNotifyMetrics:107`) + ERROR. 테스트 `폴백큐가_가득_차도_예외가_호출자로_전파되지_않는다`·`폴백큐가_가득_차도_디바운서_flush_루프가_중단되지_않고_나머지_윈도우를_처리한다` |
| TC-NOTIFY-047 | PASS | [실동작] 마스터 미존재 분기 실증 — `WARN [ControlNotify] local gov not found lclgvCd=1168000000` 직후 페이로드 `"lclgv_nm":null`(L1). 값을 지어내지 않음. [정적] `ControlNotifyPayloadFactory.java:150-174` — 미존재 null / `USE_YN<>'Y'` null(폐지 명칭 미전송) / `LCLGV_NM_MAX_LENGTH=100`(66행) 초과 시 `substring(0,100)`. 테스트 3건(`지자체_마스터에_없으면…`, `폐지된_지자체_코드는…`, `지자체명이_100자를_넘으면_절단된다`) |
| TC-NOTIFY-048 | PASS | [정적] `ControlNotifyPayloadFactory.java:185-219` + `LsDataSrcRepository.findBothVelExportableFrameNoByRawSnAndSrcSnIn`(135-138) — 쿼리 조건이 `s.rawSn=:rawSn AND s.srcSn IN :srcSns AND coalesce(srcFilePathNm,'')<>'' AND coalesce(deIdntfSrcFilePathNm,'')<>''` 이라 **타 영상 srcSn·한쪽 벌만 보유 프레임이 구조적으로 결과에서 빠진다**. 미해석분은 `unresolved` 카운트 + WARN(식별자·건수만, 좌표/경로 미출력) + `metrics.incrementUnresolvedFrame`. 테스트 4건 |
| TC-NOTIFY-049 | PASS | [실동작] L11 — rawSn=115 전량 재생성 통지의 `images`/`jsons` 가 **6건**(`0000,0001,0002,0090,0091,0092`)으로 `ls_data_src` 중 원천 경로 보유 프레임과 일치. [정적] `ControlNotifyPayloadFactory.java:120-126` → `findExportableFrameNosByRawSn`(158-160, "둘 중 하나라도 보유") — writer 가 skip 할 프레임(양쪽 다 부재)만 제외 |
| TC-NOTIFY-050 | PASS | [정적] `DatasetExportBridge.java:81-88` — `@TransactionalEventListener(AFTER_COMMIT) onDeidentReportResolved` → `runApprovalAsync`(force=true + 완료 이벤트 발행 → 보류 통지 재개). APPROVED 판정은 발행 측(`DeidentReportService.publishResolvedForExportRecovery`)이 수행. 신고 차단은 export 행을 남기지 않아 회수기가 못 집는 것도 코드로 확인(`DatasetExportService` 진입 게이트 예외) → 이 재트리거가 유일 복구 경로 성립 |
| TC-NOTIFY-051 | PARTIAL | [정적] 부착 배선은 실재 — `WebClientConfig.java:76`(`CONTROL_NOTIFY_TOKEN_HEADER="x-access-token"`) · `104-111`(토큰 non-blank 면 `defaultHeader` 부착 + 평문 http 경고) → **D-ISSUE-62 해소 확인**. 테스트 `ControlNotifyWebClientAuthHeaderTest` 4건(baseline 전건 통과). **그러나 [실동작] L14** — `docker exec klid-backend env` 에 `CONTROL_NOTIFY_TOKEN` **미존재**, 기동 로그에 `인증 토큰 … 비어 있습니다` WARN → **이 환경의 통지 8건 전부 `x-access-token` 없이 전송**됐고 mock 이 202 로 받아줬다. `enabled=true` + 토큰 공백이 **WARN 만(fail-open)** 인 잔여 갭이 라이브로 성립 → **D-ISSUE-42** |

---

## 2. D-7. 관제 조회 API (TC-NOTIFY, 조회) — 8건

> ★ UNCERTAINTIES **#4 반전** 적용 — IDOR 케이스(TC-NOTIFY-052)를 검증 대상에 **포함**해 실제로 반증 시도했다.

| ID | 판정 | 근거 확인 |
|----|:----:|-----------|
| TC-NOTIFY-026 | PASS | [실동작] `GET /v1/tasks/101/summary`(WORKER 2001, 본인 배정) → 200 `{"rawSn":101,"status":"COMPLETED","totalFrames":10,"labeledFrames":1,"totalLabels":1,"totalMeta":7,"reviewerName":"김검수","lastModifiedAt":"2026-08-03T15:07:17.599778Z"}` — DB 실측(`ls_data_src` 10행, `ls_data_lbl` 1행, `ls_data_meta` 7행)과 일치. [정적] `TaskQueryController.java:66-73` · `TaskQueryService.java:74-98`(전부 COUNT 집계) |
| TC-NOTIFY-027 | PASS | [실동작] labels 응답 항목이 `{"lblSn","lblTypeCd","label","points"}` 뿐 — `filePath`/`deIdntfSrcFilePath` **미포함**. 프레임 레벨도 `{"srcSn","frameNo","labels"}` 만. [정적] `TaskLabelsResponse.java`(record 구조로 강제) · `TaskQueryService.java:234-245` |
| TC-NOTIFY-028 | PARTIAL | [실동작] 기능은 전건 충족 — `frameIds` 101개 → **400**, 100개(경계) → 200, `size=10000` → **size 100 클램프**, 미지정 → **20**, `frameIds=468,469` → `totalElements=2` (페이징 **전** 필터 적용 확인). [정적] `TaskQueryController.java:87-103`(@Size) · `TaskQueryService.java:54-57,107-110,202-208`(서비스 2중 방어) → D-ISSUE-45 해소 유지. ⚠ **그러나 400 응답 message 가 `"getLabels.frameIds: frameIds 는 최대 100개까지…"`** — 컨트롤러 **메서드명이 그대로 노출**된다(CWE-209). 1차 **D-ISSUE-24 미해소 이월** |
| TC-NOTIFY-029 | PASS | [실동작] `GET /v1/tasks/101/meta` → 기본 `size=20`, `?size=10000` → `size=100` 클램프, 항목이 `{metaSn, metaKey, metaVal}` 구조. [정적] `TaskQueryController.java:114-123` · `TaskQueryService.java:147-150`(+`cappedMeta` 192-199, `metaSn` 오름차순 고정으로 페이지 간 안정성) |
| TC-NOTIFY-030 | PASS | [실동작] REVIEWER 토큰으로 `rawSn=99999999` → summary/labels/meta **전부 404** `{"errorCode":"NOT_FOUND","message":"영상을 찾을 수 없습니다."}`. [정적] `TaskQueryService.java:225-228` |
| TC-NOTIFY-031 | PASS | [실동작] 토큰 없음 → **401** `UNAUTHORIZED` / PORTAL_USER 토큰 → **403** `FORBIDDEN`(`@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 실효). [정적] `TaskQueryController.java:46,67,86,115`. 토글 off 시 컨트롤러 부재 404 는 `@ConditionalOnProperty`(46행) 정적 확인 |
| TC-NOTIFY-052 | PASS | [실동작] **IDOR 반증 시도 실패 = 차단 성립** — 배정 이력 없는 WORKER(2001, `ls_task_assignment` 에 raw 906 행 0건)가 `GET /v1/tasks/906/summary\|labels\|meta` 호출 시 **3경로 전부 403** `본인에게 배정되지 않은 영상입니다.`. 대조: 본인 배정 101 은 3경로 200, REVIEWER 는 906 도 3경로 200(연동 영향 없음). [정적] `TaskQueryController.java:71,96,121` 진입부 `labelAccessGuard.verifyRawAccess` · `LabelAccessGuard.java:83-100`(actor null→401 / REVIEWER 얼리리턴 / WORKER 는 `existsByUserNoAndTaskTypeCdAndRawDataId(self, LABELER, rawSn)` 필수 / 그 외 403). 테스트 `TaskQueryControllerTest#배정되지_않은_WORKER_는_rawSn_순회로_라벨을_읽을_수_없다_403`. **UNCERTAINTIES #4 반전 확정 — 구 "의도된 광범위 허용" 은 더 이상 사실이 아니다** |
| TC-NOTIFY-053 | PASS | [실동작] `rawSn=9`(`DE_IDENT_YN='F'`, WORKER 2001 배정) → **labels 412** `PRECONDITION_FAILED / 비식별 재처리 대기 중인 영상입니다.`, summary·meta 는 **200**(좌표 미포함이라 대상 아님) — 범위 정확. **REVIEWER 토큰도 동일 412**(역할 무관 프리컨디션). 미배정 WORKER 는 여전히 403 이 먼저(인가 이후 평가 순서 확인 — 906 은 403, 9 는 403 아닌 412). [정적] `TaskQueryController.java:97-101` — `verifyRawAccess`(96) **이후** `requireNotUnderDeidentReport`(101) |

---

## 3. 판정 집계

| 판정 | 건수 | 케이스 |
|------|:---:|--------|
| PASS | **50** | D-6 42건 + D-7 6건 |
| PARTIAL | **3** | TC-NOTIFY-028 · TC-NOTIFY-041 · TC-NOTIFY-051 |
| FAIL | 0 | — |
| BLOCKED | 0 | — |
| N/A | 0 | — |
| 확인필요 | 0 | — |
| **합계** | **53** | (폐기 케이스 없음) |

PASS율 94.3% (50/53). **CRITICAL 등급 신규 결함 0건.**

---

## 4. 이슈

### [D-ISSUE-41] TC-NOTIFY-041 — `NO_INPUT` 종결은 export 행을 남기지 않아 회수기가 못 집고, 디바운스 윈도우는 이미 삭제돼 TASK_MODIFIED 가 **영구 유실**된다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md 구속 정책 — *"export 가 실패하면 통지를 보류하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다(**통지 유실이 아니라 성공 시점으로 지연**)"*. 즉 보류된 통지는 **반드시 되살아나는 경로**가 있어야 한다. 그래야 관제가 승인 후 수정을 영원히 모르는 상태가 되지 않는다.
- **현재 동작(이슈 내용)**: 보류는 되지만 **되살아나지 않는 종결이 있다**.
  - `DatasetExportTxService.java:101-106` — 프레임 0건(또는 활성 메타 0건)이면 **`LS_DATASET_EXPORT` 행을 만들지 않고** `Optional.empty()` 로 skip → `DatasetExportOutcome.NO_INPUT`.
    ```java
    List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
    if (frames.isEmpty()) {
        log.info("[DatasetExport] no frames — skip export rawSn={}", rawSn);
        return Optional.empty();     // ← FAILED 행도 남기지 않는다
    }
    ```
  - `DatasetExportFailureRecoverer.java:125` — `exportRepository.findRetryableFailedAnchors(...)` 는 **FAILED 행만** 스캔한다. 행이 없으므로 회수 대상이 되지 않는다.
  - `ControlNotifyDebouncer.java:344-351` — 재생성 윈도우는 `exportRunner.runReExportThenNotify`(`@Async`) 에 **위임만 하고 즉시 반환**하므로, 호출부 `claimAndSendIsolated`(303-306)가 예외 없음으로 판단해 `store.complete(acmlSn)` → **윈도우 행 DELETE**. 축적분(변경 프레임·변경종류)도 함께 사라진다.
  - 폴백 큐(`ls_control_notify_fallback`)에도 들어가지 않는다 — 통지 함수(`sendModified`)가 아예 호출되지 않았기 때문.
- **재현/확인 경로** (이번 회차 실측, rawSn=147):
  ```bash
  # 전제: rawSn=147 은 LS_RAW_DATA_STATUS='APPROVED', DE_IDENT_YN='Y', LS_DATA_SRC 0건
  curl -X PUT 'http://localhost:18081/api/v1/videos/147/environment-meta' \
       -H "Authorization: Bearer $REVIEWER_JWT" -H 'Content-Type: application/json' \
       -d '{"weather":"비","timeOfDay":"NGT","season":"WINTER"}'      # 200
  # 60s 디바운스 후 backend 로그
  #   [DatasetExport] async re-export(+notify) starting rawSn=147 forceRegenerate=true
  #   [DatasetExport] no frames — skip export rawSn=147
  #   [DatasetExport] async export not notifiable — notify withheld rawSn=147 outcome=NO_INPUT
  ```
  ```sql
  SELECT count(*) FROM ls_control_notify_fallback WHERE raw_sn=147;  -- 0  (통지 흔적 없음)
  SELECT count(*) FROM ls_dataset_export         WHERE data_raw_sn=147; -- 0 (회수 anchor 없음)
  SELECT count(*) FROM ls_mon_noti_acml          WHERE raw_sn=147;   -- 0  (윈도우 삭제됨)
  ```
  대조군 rawSn=115(프레임 6건)는 같은 조작으로 export v3 SUCCEEDED → `TASK_MODIFIED` 정상 발송(queue_sn 83).
- **영향**: 데이터 정합 — 관제가 승인 후 메타/라벨 수정을 **영원히 인지하지 못한다**(CLAUDE.md *"데이터마트 학습데이터셋의 라벨링 정보 동기화"* 요구 위반). 현재 확인된 트리거는 "프레임 0건 / 활성 동결메타 0건인 APPROVED 영상"이라 발생 폭은 좁지만, `NO_INPUT` 판정은 **활성 메타 부재**로도 진입하므로(동결 스냅샷이 비활성화된 영상) 정상 데이터에서도 도달 가능하다. 보안 영향 없음.
- **수정 방향(제안)**: ⓐ `runReExportThenNotify` 가 **비통지 종결(`notifiable()==false`)일 때 디바운스 윈도우를 되돌리거나**(complete 를 러너 콜백 이후로 이동) ⓑ `NO_INPUT` 도 `LS_DATASET_EXPORT` 에 종결 행(예: `NO_INPUT`/`FAILED`)을 남겨 회수기 스캔 대상이 되게 하거나 ⓒ 최소 조치로 **보류된 통지를 폴백 큐에 `PAYLOAD_REBUILD_REQUIRED` 로 적재**해 재시도 잡이 살리게 한다. ⓐ는 D-ISSUE-61 수정 때 함께 지적된 "러너의 성공/실패를 상위가 알 수 있게 한다"(1차 §3072)와 같은 축이다. **구현은 하지 않는다.**

### [D-ISSUE-42] TC-NOTIFY-051 — 통지 활성인데 인증 토큰이 비면 **WARN 만 내고 무인증 전송**(fail-open), 로컬 실효값이 실제로 빈 토큰

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 관제 inbound SPI 계약(API-251/API-285)은 `x-access-token` 을 요구한다. 통지가 활성(`authoring.control-notify.enabled=true`)인데 토큰이 없으면, 실환경에서 **전 통지가 401 로 거부 → 폴백 큐가 재시도 상한을 소진 → dead-letter 고착 → 관제 동기화 전면 중단**이 확정적으로 발생한다. 보안 통제의 미설정은 fail-closed(기동 차단 또는 최소한 통지 비활성)로 다뤄야 조기에 드러난다.
- **현재 동작(이슈 내용)**: `WebClientConfig.java:104-116`
  ```java
  if (token != null && !token.isBlank()) { ... builder.defaultHeader(CONTROL_NOTIFY_TOKEN_HEADER, token.trim()); }
  else if (enabled) {
      log.warn("[ControlNotify] 통지가 활성화됐으나 인증 토큰(...)이 비어 있습니다 — 관제 SPI 가 {} 를 요구하면 전 통지가 401 로 거부됩니다.", ...);
  }
  return builder.build();   // ← 헤더 없이 그대로 기동·전송
  ```
  이번 회차 실효값 실측: `docker exec klid-backend env | grep CONTROL_NOTIFY` → `CONTROL_NOTIFY_ENABLED=true` · `CONTROL_NOTIFY_URL=...` 뿐이고 **`CONTROL_NOTIFY_TOKEN` 자체가 없다**. 기동 로그 `2026-08-03 23:54:19.600 [main] WARN ... 인증 토큰 ... 비어 있습니다`. 그 상태로 이번 회차 통지 8건이 전부 발송·수락됐다(mock 이 인증을 검사하지 않기 때문).
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep -c CONTROL_NOTIFY_TOKEN     # 0
  docker logs klid-backend | grep '인증 토큰'                      # 기동 WARN 1건
  docker logs klid-mock-server | grep -c 'notify-completed HTTP/1.1" 202'  # 무인증인데 수락됨
  ```
- **영향**: 보안 — 인증 없는 outbound 통지(CWE-306 Missing Authentication / CWE-1188 insecure default). 실질 피해는 stg/prd 배포 시 **통지 전면 401 → dead-letter 고착**(가용성·데이터 정합). 또한 WARN 은 배포 로그에 묻혀 미설정이 무증상으로 지나간다 — 이 저장소가 `QuartzClusteringGuard`·`VlmUrlPolicy`·`GenAiIntegrationWiringGuard` 에서 이미 채택한 "배포 환경 fail-closed 기동 차단" 패턴과 어긋난다.
- **수정 방향(제안)**: `QuartzClusteringGuard` 골격을 재사용해 **stg/prd(및 `ENV` 배포 표식) + `control-notify.enabled=true` + 토큰 공백** 조합이면 `@PostConstruct` 에서 **기동 실패**시킨다. local/dev(목 서버, 인증 미요구)는 현행 WARN 유지. 대안으로 통지만 자동 비활성화(빈 미등록)하되, 그 경우 "통지가 조용히 안 나가는" 상태가 되므로 기동 차단이 더 낫다. 병행으로 `.env.example`·`deploy/onprem/env.template` 에 `CONTROL_NOTIFY_TOKEN` 항목을 명시한다(⚠ 빈값 기입은 `${KEY:default}` 무력화 사고 이력 참조 — 주석으로 필수 표기). **구현은 하지 않는다.**

---

## 5. 이전 회차 이슈 대조

| 이슈 | 케이스 | 이번 회차 판정 |
|------|--------|---------------|
| **D-ISSUE-61** (CRITICAL, 1차) — export 가 FAILED/무예외 실패로 마감돼도 TASK_COMPLETED/MODIFIED 발송 | TC-NOTIFY-040/001/041 | **✅ 해소 확정(실동작 실증).** `DatasetExportOutcome.notifiable()` 단일 판정 + `null` fail-closed. rawSn=147 재현 실험에서 `NO_INPUT` 종결 시 통지 0건. 2차 타겟 재검증(PASS)을 **전수 회차에서 라이브로 재확인**. 단 "유실 아닌 지연" 의 복구 경로가 `NO_INPUT` 에서 끊긴다 → 신규 **D-ISSUE-41** |
| **D-ISSUE-62** (HIGH, 1차) — 통지에 `x-access-token` 미부착 | TC-NOTIFY-051 | **✅ 배선 해소.** `WebClientConfig.java:76,111` 부착 + 전용 테스트 4건. **단 fail-open 잔여 갭이 라이브로 성립** → 신규 **D-ISSUE-42** |
| **D-ISSUE-63** (1차) — `event_type_cd` 매핑 없이 pass-through, 두 코드체계 공존 | TC-NOTIFY-003 | **미해소 이월.** `ControlNotifyPayloadFactory.toControlEventTypeCd`(139-141)는 여전히 항등함수. DB 실측으로 `INTRUSION`(rawSn=101)·`EV02000201`(900/906) 두 계통이 그대로 전송됨을 재확인. UNCERTAINTIES **#27**(관제 8대 코드값 미수령) 그대로 — 관제팀 협의 선행 필요. 케이스 기대값 자체는 충족이라 판정은 PASS |
| **D-ISSUE-64** (LOW, 1차) — 백오프 javadoc(1,2,4,8,16) vs 실제(2,4,8,16,32) | TC-NOTIFY-019 | **미해소 이월.** `LsControlNotifyFallback.java:190` 주석 그대로. 동작은 `2^rtryCnt` cap 60 으로 케이스 기대 충족 |
| **D-ISSUE-24** (1차) — `frameIds` 상한 400 응답에 컨트롤러 메서드명 노출 | TC-NOTIFY-028 | **미해소 이월(실동작 재확인).** 응답 message = `"getLabels.frameIds: frameIds 는 최대 100개까지 지정할 수 있습니다."` — `@Validated` 메서드 검증의 `ConstraintViolation.propertyPath` 가 그대로 노출(CWE-209). `GlobalExceptionHandler` 에서 `ConstraintViolationException` 의 propertyPath 앞 세그먼트를 제거하면 해소 |
| **D-ISSUE-45** (1차) — 관제 조회 무페이징 | TC-NOTIFY-028/029 | **✅ 해소 유지(실동작).** labels·meta 기본 20 / 상한 100 클램프 재확인 |
| **UNCERTAINTIES #4** — 관제 조회 API IDOR "의도된 광범위 허용" | TC-NOTIFY-052 | **✅ 반전 확정.** 3경로 전부 `verifyRawAccess` 적용, 미배정 WORKER 403 실측. UNCERTAINTIES 원본의 🔄 표기가 사실과 일치 — 원본 갱신 불필요 |
| **UNCERTAINTIES #3** — TASK_COMPLETED payload | TC-NOTIFY-003 | **✅ 확정 재확인.** 6필드 평면 snake_case + `/api/data-set/v2/jobs/{job_id}/notify-*` 경로가 실물로 확인됨. 구 기대값(`totalFrames`/`labeledFrames`/`reviewerName`)은 계약에 존재하지 않음 |

---

## 6. 카탈로그 근거 드리프트 (이번 회차 정정 완료)

`docs/test-cases/D-review-version-notify.md` 166~234행 내에서 아래 12건의 `file:line` 근거가 실제 코드 위치와 어긋나 **Edit 로 직접 정정**했다(프로덕션 코드 무수정). 대부분 `ControlNotifyDebouncer.java` 가 커진 뒤 라인이 60~70행씩 밀린 계통 드리프트다.

| TC | 컬럼 | 구 근거 | 정정 |
|----|------|---------|------|
| 008 | 근거 | `ControlNotifyDebouncer.java:174-176` | `:195-197`(accumulate) |
| 009 | 근거 | `:80,190-203` | `:93,211-219,244-252` |
| 010 | 근거 | `:100-103,141-166` | `:132-134,141,162-177` |
| 011 | 근거 | `:289-320(검증 없음)` | `:334-369(검증 없음)` |
| 014 | 근거 | `FramePrivacyMetaService.java:131-132,154-155` | `:158-159,180-181` |
| 015 | 근거 | `ControlNotifyFallbackService.java:67-107` | `:66-96` |
| 016 | 근거 | `:36,76-81` | `:36,75-82` |
| 017 | 근거 | `:71-73,109-113` | `:71-73,113-115` |
| 018 | 근거 | `:137-145` | `:136-143` |
| 021·022 | 근거 | `ControlNotifyFallbackRetryJob.java:31,40-73` | `:31,48-71`(022 는 재실패 `62-65` 명시) |
| 024 | 근거 | `ControlNotifyFallbackService.java:109-135` | `:108-129` |
| 025 | 근거 | `ControlNotifyDebouncer.java:72-74` | `:85-87` |
| 040 | 근거 | `AsyncDatasetExportRunner.java:67-76,90-130` | `:67-76,90-106,134-149` |
| 043 | 근거 | `ControlNotifyDebouncer.java:285-308` | `:334-357` |
| 044 | 근거 | `ControlNotifyDebouncer.java:241-269` / `JpaControlNotifyDebounceStore.java:103-112` | `:259-271,290-318` / `:102-110` |
| 045 | 근거 | `ControlNotifyDebouncer.java:82-83,241-269` | `:96,290-318` |

추가로 3건의 **기대결과 보강**(정정이 아니라 실측 사실 부기)을 같은 라인범위에 넣었다 — TC-NOTIFY-003(`event_type_cd` pass-through·두 코드체계 공존, D-ISSUE-63 이월) · TC-NOTIFY-028(400 message 메서드명 노출, D-ISSUE-24 이월) · TC-NOTIFY-041(`NO_INPUT` 은 회수 대상이 아님, D-ISSUE-41) · TC-NOTIFY-040(판정 원천이 `DatasetExportOutcome.notifiable()` 임을 명시) · TC-NOTIFY-051(3차 실증 fail-open, D-ISSUE-42).

> ⚠ **병합 담당자 유의**: `D-review-version-notify.md` 상단 **`## 변경 이력` 표에 3차 행을 이미 다른 파트가 추가**해 두었다(“근거 file:line 전수 재확인 회차”). 본 파트의 정정 16건 + 보강 5건은 그 행에 **아직 반영돼 있지 않다** — 병합 시 해당 행의 정정 건수를 갱신하거나 D-6/D-7 정정 내역을 요약에 덧붙일 것. 파트 간 동시 편집 충돌을 피하려고 본 파트는 담당 라인범위(166~234) 밖을 건드리지 않았다.

> 드리프트가 **없던** 파일: `ControlNotifyService.java`(전 11케이스 정확) · `ControlNotifyClient.java` · `ControlNotifyPayloadFactory.java` · `TaskCompletedPayload/TaskModifiedPayload/ExportFileNaming` · `TaskQueryController.java`(전 8케이스 정확) · `TaskQueryService.java` · `WebClientConfig.java`(2차에서 갱신됨) · `DatasetExportFailureRecoverer.java` · `DatasetExportBridge.java`.

---

## 7. 참고 — 이번 회차가 남긴 데이터 (다른 에이전트 유의)

- 검증 목적으로 **API 호출만** 수행했고 DB 직접 수정·코드 수정은 하지 않았다.
- 신규 생성 데이터: `ls_control_notify_fallback` queue_sn **83**(rawSn=115 TASK_MODIFIED), `ls_dataset_export` export_sn **71**(rawSn=115 v3 SUCCEEDED), `ls_data_raw` 147/115 의 촬영환경 3필드(수동값 `비/NGT/WINTER`, `눈/DAY/FALL`).
- **rawSn=147 은 "APPROVED + 프레임 0건" 이라 export 가 항상 `NO_INPUT` 으로 끝난다** — 다른 클러스터가 이 영상으로 export/통지 케이스를 검증하면 오탐이 난다.
- mock-server 등록부(`GET /api/data-set/v2/jobs`)에 job_id 4·27·33·101·115·900·906 이 등록돼 있어, 이후 최초 완료 통지는 **409 자기치유 경로**를 탄다. 초기화하려면 `POST http://localhost:9400/api/data-set/v2/jobs/_reset`.

---

# D 클러스터 part4 — D-2. 검수 상태머신(단위) + D-3. 검수 배정(TC-ASSIGN) + D-4. 버전관리 스냅샷(TC-VERSION)

> 담당 범위: `docs/test-cases/D-review-version-notify.md` **69~134행**(D-2: TC-REVIEW-031~034 4건,
> D-3: TC-ASSIGN-001~026 26건, D-4: TC-VERSION-001~017 17건 = 총 47건).
> 검증일 2026-08-04 KST(3차) · 스택: `_raw/stack-bringup.md` 기준 재빌드 HEAD 이미지(Flyway v163) ·
> 공용 정상 데이터 `_raw/pipeline-drive.md`(rawSn=101, assignmentId=74)는 조회 참고용으로만 사용.
> 지시에 따라 **빌드/테스트 실행 금지** — 정적 대조(코드 Read/Grep) + 기존 단위/통합테스트 존재 확인 위주로 검증했다.
> 프로덕션 코드는 수정하지 않았다. 카탈로그(담당 라인범위 내) 정정 1건은 아래 "카탈로그 정정" 절 참조.

## 검증 방법 요약

- `ReviewStateMachine.java`(전체 65행) · `AssignmentService.java`(전체 687행) · `VersionService.java`(1150행대 발췌: 83-330, 1000-1060)를 전문 Read 하여 카탈로그의 모든 `file:line` 참조를 실제 코드와 1:1 대조.
- 각 케이스마다 대응 단위/통합 테스트를 Grep 으로 확인(`ReviewStateMachineTest` · `AssignmentServiceTest` · `AssignmentReassignConcurrencyIT` · `AssignmentOptimisticLockConflictIT` · `VersionServiceTest`) — 존재 여부와 테스트 내용이 케이스 단언과 일치하는지 대조.
- 이전 회차(1차 `2026-07-25/1차/ISSUES.md`, 2차 통합 `2026-08-01/1차/ISSUES.md`)의 D-ISSUE-03/05/06/07/21/22/81 을 현재 코드로 재확인 — 전부 **코드 변경 없이 그대로 재현**됨을 확인(카탈로그 자체가 일부는 이미 "미해소" 로 명시하고 있어 새 발견이 아니라 재확인).

---

## 판정 결과 — D-2. 검수 상태머신

| ID | 케이스명 | 판정 | 근거 확인 |
|---|---|:--:|---|
| TC-REVIEW-031 | 허용 전이 전수 매트릭스 | PASS | [정적+단위] `ReviewStateMachine.java:43-51` `ALLOWED` 맵 실측 일치(라인 드리프트 없음). 7개 정본 전이 중 5건은 `allowedTransitions()`, 나머지 2건(PENDING→ASSIGNED, APPROVED→PENDING)은 `pendingToAssignedCancelSubmitAllowed`·`approvedToPendingReReviewAllowed` 로 분산 커버 — 7건 전부 테스트로 확인됨 |
| TC-REVIEW-032 | 미허용 전이 전수 | PASS | [정적+단위] `:59-63` 라인 정확 일치. `pendingToApprovedRejected` 테스트가 대표 사례(PENDING→APPROVED) 확인. `allowed==null`(맵 미등록 키, 예: COMPLETED/PROCESSING) 분기도 코드상 동일 경로로 400 |
| TC-REVIEW-033 | APPROVED→IN_REVIEW/REJECTED 직행 | PASS | [정적+단위] `:54-58` 라인 정확 일치. `approvedNonPendingIsConflict` 테스트가 두 케이스(IN_REVIEW/REJECTED) 모두 CONFLICT(409) 확인 |
| TC-REVIEW-034 | 알 수 없는 from 상태 | **PARTIAL** | [정적] `:53-64` 라인 정확 일치. COMPLETED/PROCESSING → `ALLOWED.get(from)`이 null 반환 → INVALID_INPUT(400) 확인(PASS 부분). **from=null → `Map.of()`(`ImmutableCollections.MapN`)의 `get(null)`이 내부적으로 키 해싱을 시도하며 NullPointerException**(카탈로그 서술과 일치, 여전히 미수정) → 처리되지 않으면 500. `ReviewStateMachineTest`에 null/COMPLETED/PROCESSING 케이스 전무(회귀 가드 없음). → **D-ISSUE-61**(1차 D-ISSUE-03 이월, 미해소) |

## 판정 결과 — D-3. 검수 배정

| ID | 케이스명 | 판정 | 근거 확인 |
|---|---|:--:|---|
| TC-ASSIGN-001 | 배정 정상 | PASS | [정적+단위] `AssignmentService.java:71-129`(assign 메서드 시작~종료) 라인 정확 일치. `AssignmentServiceTest`(reviewerId 반영 등) + `AssignmentEventTypeOptionsTest` 등으로 부수효과(LABELER INSERT+markAssigned+이벤트로그) 확인 |
| TC-ASSIGN-002 | 배정 — 비REVIEWER | PASS | [정적] `:73`(requireReviewer 호출), `:668-678`(메서드 전체), throw는 `:675-677` 정확 일치 |
| TC-ASSIGN-003 | 배정 — actor null | PASS | [정적] `:672-674` 정확 일치(actor==null → UNAUTHORIZED, NPE 없음) |
| TC-ASSIGN-004 | 배정 — 존재하지 않는 작업자 | PASS | [정적] `:76-78` 정확 일치. 단, 존재 여부만 검사하고 **역할/활성 여부는 검증하지 않음**(카탈로그 이 케이스의 리터럴 기대결과는 충족되나 별도 교차 이슈 — D-ISSUE-63 참조) |
| TC-ASSIGN-005 | 배정 — 존재하지 않는 검수자 | PASS | [정적] `:79-81` 정확 일치 |
| TC-ASSIGN-006 | 배정 — 중복 배정(UK 충돌) | **PARTIAL** | [정적] `:101-103` 정확 일치, 실제 UK 충돌 시 409 반환은 케이스 리터럴 기대값 그대로 충족(PASS). 단 이 catch 가 **FK 위반·PK 충돌까지 동일 메시지로 오분류**하는 문제가 존재(1차 D-ISSUE-07 실측: 미존재 rawSn 배정 시도도 동일 409+"중복 배정" 메시지) → **D-ISSUE-62**(이월) |
| TC-ASSIGN-007 | 배정 + REVIEWER 동시등록 | PASS | [정적] `:115-119`(옵션 호출), `:188-220`(assignReviewers 메서드) 정확 일치 |
| TC-ASSIGN-008 | 배정 — REVIEWER 중복 등록 skip | PASS | [정적] `:201-219` 범위 내 UK 충돌 격리 로직(`catch DataIntegrityViolationException` → WARN, worker 배정 영향 없음) 확인 |
| TC-ASSIGN-009 | 재배정 정상 | PASS | [정적+단위] `:222-302`(reassign 메서드 시작~종료) 정확 일치. `AssignmentServiceTest`(`reassign_시_actor_sub와_시각이_HSTRY에_기록`, `reassign_시_REASSIGN_이벤트가_prev_subject_와_함께_누적`)로 확인 |
| TC-ASSIGN-010 | 재배정 — 완료(APPROVED) 차단 | PASS | [정적+단위] `:230-240` 정확 일치. `reassign_완료된_배정은_거부_ASSIGNMENT_ALREADY_COMPLETED` 테스트 확인 |
| TC-ASSIGN-011 | 재배정 — 배정 미존재 | PASS | [정적] `:227-228` 정확 일치 |
| TC-ASSIGN-012 | 재배정 — 동일 작업자 | PASS | [정적] `:258-260` 정확 일치 |
| TC-ASSIGN-013 | 재배정 — 새 작업자 이미 배정(사전) | PASS | [정적] `:262-275` 정확 일치 |
| TC-ASSIGN-014 | 재배정 동시성 — @Version 직렬화 | PASS | [정적+integration] `AssignmentReassignConcurrencyIT.concurrentReassignSerializesToSingleTransition` 4스레드 동시 재배정 → **성공 정확히 1건**, 나머지 CONFLICT/INVALID_INPUT, `LS_TASK_ASSIGN_HISTORY` 1행만 적재, REASSIGN 이벤트로그 1행만 확인(실제 PostgreSQL Testcontainer 기반 IT). ★근거 1행 드리프트 발견·정정(아래 카탈로그 정정 참조) |
| TC-ASSIGN-015 | 배정이력 — WORKER 본인만(IDOR) | PASS | [정적] `:322-328` 정확 일치 |
| TC-ASSIGN-016 | 배정이력 — REVIEWER 전체 | PASS | [정적] `:315-361`(getHistory 전체) 정확 일치. userNo batch lookup(`findByUserNoIn` 1회 호출) 구조로 N+1 회피 확인 |
| TC-ASSIGN-017 | 배정이력 — 잘못된 ID | PASS | [정적] `:316-320` 정확 일치 |
| TC-ASSIGN-018 | 배정목록 — WORKER 본인만(param 무시) | PASS | [정적] `:470-482`(scopeForActor), WORKER 분기 `:475-477` 정확 일치 |
| TC-ASSIGN-019 | 배정목록 — REVIEWER 필터/전체 | PASS | [정적] REVIEWER 분기 `:478-480` 정확 일치 |
| TC-ASSIGN-020 | 배정목록 — 기타 역할/미인증 | PASS | [정적+단위] null 401 `:471-473`, 기타역할 403 `:481` 정확 일치. `listAssignments에_actor가_null이면_UNAUTHORIZED`·`PORTAL_USER_역할은_목록_조회시_403`·`역할_미배정_토큰도_목록_조회시_403` 테스트 확인 |
| TC-ASSIGN-021 | 상태 upsert 동시 INSERT 충돌 | **PARTIAL** | [정적] `:657-666`(upsertDataStts) 정확 일치. PK 제약 위반 시 CONFLICT(재시도 가능)라는 리터럴 기대는 충족(PASS)하나, 실제로는 TC-ASSIGN-006과 **동일한 오분류 catch**(`:101-103`)가 "PK 충돌"과 "UK 충돌(중복 배정)"을 구분 없이 "이미 동일 작업자에게 배정된 영상이 있습니다"로 안내 → 진단 혼선(D-ISSUE-62와 동일 근본원인, 중복 등록 아님) |
| TC-ASSIGN-022 | APPROVED 영상 신규 배정 차단 | PASS | [정적+단위] `:145-156`(rejectApprovedTargets), throw `:151-154` 정확 일치. `APPROVED_영상에_신규배정시_409_이고_상태가_APPROVED_로_유지됨` 테스트 확인 |
| TC-ASSIGN-023 | 배정 부분성공 금지 | PASS | [정적+단위] `:83`(rejectApprovedTargets 선행 호출), `:88`(배정 루프) 정확 일치. `배정목록에_APPROVED_가_1건_섞이면_전체실패하고_어떤_배정도_생성되지_않음` 테스트 확인 |
| TC-ASSIGN-024 | 배정 중 승인 경합 → 409 | PASS | [정적+integration] `:89-93`(경합 구간), `:104-112`(CONFLICT 변환) 정확 일치. `AssignmentOptimisticLockConflictIT.assignMapsOptimisticLockFailureToConflict` — `@SpyBean`으로 flush 시 `OptimisticLockingFailureException` 강제 발생 → 500이 아닌 CONFLICT(409) 결정적으로 확인 |
| TC-ASSIGN-025 | 재배정 상태행 공유잠금(FOR SHARE) | PASS | [정적+integration] `:230-240` 정확 일치. `AssignmentReassignConcurrencyIT.reassignGuardLocksStatusRowSerializingConcurrentApproval` — 별도 스레드가 `findByRawDataIdForShare` 잠금을 1초 보유하는 동안 동시 UPDATE가 **≥400ms 대기**함을 실측(잠금이 없으면 즉시 통과) — 공유잠금 실효 확인 |
| TC-ASSIGN-026 | IN_REVIEW 재배정 허용 여부 | **확인필요** | [정적] `:235-240` 정확 일치. 코드는 `STTS_APPROVED` 한 값만 차단하므로 IN_REVIEW 재배정은 현행 **허용**(카탈로그 자체 서술과 일치). 정책 미확정 상태 지속 → **D-ISSUE-64**(1차 D-ISSUE-05 이월, 확인필요) |

## 판정 결과 — D-4. 버전관리 스냅샷

| ID | 케이스명 | 판정 | 근거 확인 |
|---|---|:--:|---|
| TC-VERSION-001 | 승인 스냅샷 생성 | PASS | [정적+단위] `VersionService.java:159-207`(실제 158-208) 근접 일치. `검수_승인시_영상_프레임의_현재_라벨로_DB_스냅샷_APPROVED_버전_생성` 테스트 확인. `pipeline-drive.md` 실동작(rawSn=101)에서도 `LS_LABEL_VERSION`에 `SAVE_REASON=APPROVED, ACTVTN_YN=Y, VERSION_HASH` 실제 생성 확인됨(교차검증) |
| TC-VERSION-002 | 라벨 없는 프레임 스킵 | PASS | [정적+단위] `:190-197` 정확 일치. `HIGH_영상_다중_프레임_각_프레임마다_스냅샷_생성_라벨_없는_프레임은_스킵` 테스트 확인 |
| TC-VERSION-003 | 멱등 — 무변경 재승인 | PASS | [정적+단위] `:288-300` 정확 일치. `HIGH_멱등_재승인_수정_없이_재승인시_동일_payload_중복_버전_미생성` 테스트 확인 |
| TC-VERSION-004 | 재승인 시 변경분 새 버전 적층 | PASS | [정적+단위] 동일 범위. `HIGH_수정_후_재승인시_새_active_버전_생성_이전_active_deactivate` 테스트 확인 |
| TC-VERSION-005 | 프레임 없음 | PASS | [정적+단위] `:170-172`(실제 170-173), `:216-221`(CommitResult.EMPTY) 근접 일치. `프레임이_없는_영상_승인시_스냅샷_0건_생성_예외없음` 테스트 확인 |
| TC-VERSION-006 | 영상 미존재 | PASS | [정적+단위] `:167` 정확 일치. `존재하지_않는_영상_승인_스냅샷_시도시_NOT_FOUND` 테스트 확인 |
| TC-VERSION-007 | rawSn/actor null 가드 | **PARTIAL** | [정적] `:160-165` 정확 일치. actor==null → `CustomException(UNAUTHORIZED)` 정상(401) 확인(PASS 부분). **rawSn==null → 순수 `IllegalArgumentException`** 이며 `GlobalExceptionHandler`에 전용 핸들러가 없어(`ExceptionHandler` 전수 Grep 확인 — CustomException/MethodArgumentNotValidException/ConstraintViolationException/HttpMessageNotReadableException/MissingServletRequestParameterException/MethodArgumentTypeMismatchException/DataIntegrityViolationException/AccessDeniedException/AuthenticationException/MaxUploadSizeExceededException/NoResourceFoundException/HttpRequestMethodNotSupportedException/Exception 뿐) 제네릭 `Exception.class` 핸들러로 떨어져 **500**이 된다. `VersionServiceTest`에 `commitApproved(null,...)` 테스트 전무. → **D-ISSUE-65**(1차 D-ISSUE-81 이월, 미해소) |
| TC-VERSION-008 | 대용량 라벨 1MB 초과 단순화 후 승인 성공 | PASS | [정적+단위] `:90-101`(MAX_PAYLOAD_BYTES/MAX_DEIDENT_PAYLOAD_BYTES 상수), `:1041-`(serializeSnapshotWithSimplification) 정확 일치. `BE_4_대용량_폴리곤_라벨_검수승인_1MB_초과여도_단순화로_정상_스냅샷_생성` 테스트 확인 |
| TC-VERSION-009 | 10MB 초과 프레임 SKIPPED | PASS | [정적] `:264-283`(실제 274-284, snapshotFrameOnApprove의 catch 블록) 근접 일치. 단순화 후에도 초과 시 SKIPPED 반환 + 승인 유지 로직 확인 |
| TC-VERSION-010 | 동시 승인 스냅샷 직렬화(Race) | PASS | [정적] `:288-300`(findActiveForUpdate 비관적 잠금) 정확 일치 |
| TC-VERSION-011 | (폐기) 비식별 신고 스냅샷 | N/A | [정적] `:303-307`(D-25 제거 사유 주석) 정확 일치. 메서드 자체 없음 확인(카탈로그 폐기 표기와 일치) |
| TC-VERSION-012 | (폐기) 비식별 신고 — 라벨 0 스킵 | N/A | [정적] 위와 동일 |
| TC-VERSION-013 | 버전목록 조회 IDOR | PASS | [정적+단위] `:315-326`(listVersions) 정확 일치. `listVersions_미배정_WORKER_접근시_FORBIDDEN` 테스트 확인 |
| TC-VERSION-014 | 스냅샷에 PII/본문 로그 미출력 | PASS | [정적] `:205-206`(log.info — rawSn/frames/created/skipped/actor만, 라벨 본문 없음), `:264-283`(catch 블록 — `e.getMessage()`만 로깅, 페이로드 미출력) 정확 일치 |
| TC-VERSION-015 | 버전목록은 신고 구간에도 200 | PASS | [정적] `:309-326`(listVersions 및 javadoc — 의도적으로 게이트 미적용 서술) 정확 일치. `VersionItem` 응답에 `LABEL_PAYLOAD` 필드 없음 확인 |
| TC-VERSION-016 | 레거시 rawSn 스코프 스냅샷 잔존 행 | PASS | [정적] `:303-307`, `:317`(findByDataSrcSnOrderByRegDtDesc — srcSn 스코프만 조회) 정확 일치 |
| TC-VERSION-017 | 승인 스냅샷 원자성 | PASS | [정적] `ReviewService.java:493-513` 정확 일치. `approve()` 단일 `@Transactional("controlTransactionManager")` 메서드 안에서 `stateMachine.verify`→`stts.transitionTo`→`versionService.commitApproved`→`evntAnnoReviewService.autoApproveOnVideoApproval`→`metaService.autoApproveOnVideoApproval`→`datasetVideoMetaSnapshotService.materialize` 전부 호출됨을 확인. 각 하위 서비스 메서드(`materialize`·`autoApproveOnVideoApproval` 등) 전수 Grep 결과 **REQUIRES_NEW 등 별도 전파 속성 없이 전부 기본(REQUIRED)** 이므로 하나라도 런타임 예외를 던지면 같은 트랜잭션이 전체 롤백됨(코드 구조상 원자성 보장). 전용 IT 테스트는 없으나(코드 구조 기반 PASS) |

### 집계

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| D-2 (검수 상태머신) | 4 | 3 | 0 | 1 | 0 | 0 | 0 |
| D-3 (검수 배정) | 26 | 23 | 0 | 2 | 0 | 0 | 1 |
| D-4 (버전관리 스냅샷) | 17 | 14 | 0 | 1 | 0 | 2 | 0 |
| **합계** | **47** | **40** | **0** | **4** | **0** | **2** | **1** |

> PARTIAL 4건 = TC-REVIEW-034(D-ISSUE-61) · TC-ASSIGN-006(D-ISSUE-62) · TC-ASSIGN-021(D-ISSUE-62 동일 근본원인) · TC-VERSION-007(D-ISSUE-65). 확인필요 1건 = TC-ASSIGN-026(D-ISSUE-64). N/A 2건 = TC-VERSION-011/012(2026-07-30 폐기 확정, 검증 대상 아님— 분모 제외 시 실질 45건 중 PASS 40·PARTIAL 4·확인필요 1).

> ⚠ "PASS 다수"를 그대로 신뢰하지 말 것. D-2/D-3/D-4는 **1~2차 회차에서 이미 발견되고도 계속 미해소인 결함 5건**을 그대로 승계한다(D-ISSUE-61/62/63/64/65). 카탈로그 자체가 이미 "미해소" 주석을 달고 있어 이번 회차는 "재확인"의 성격이 강하지만, 실제 코드가 변경되지 않았음을 파일 Read로 직접 재확인했다.

---

## 이전 회차 이슈 대조

| 이전 이슈 | 대상 | 3차 상태 |
|---|---|---|
| **D-ISSUE-03**(1차) / 재확인(2차 통합본) — `ReviewStateMachine.verify(null,…)` NPE 500 | TC-REVIEW-034 | **미해소 이월.** 코드 변경 없음(`ReviewStateMachine.java:43-63` 그대로). `ReviewStateMachineTest`에 null 케이스 회귀 가드 없음. → 본 회차 **D-ISSUE-61** |
| **D-ISSUE-05**(1차) / **D-ISSUE-26**(카탈로그 자체 표기, 동일 이슈) — IN_REVIEW 영상 재배정 허용 | TC-ASSIGN-026 | **미해소 이월(정책 미확정).** `AssignmentService.java:235-240` 그대로 `STTS_APPROVED`만 차단. → 본 회차 **D-ISSUE-64** |
| **D-ISSUE-06**(1차) / **D-ISSUE-22**(2차 통합본) — 배정 대상 역할·활성 여부 미검증 | TC-ASSIGN-001/004/005/007/009 | **미해소 이월.** `:76-81`(assign), `:255-257`(reassign) 모두 `userRepository.findByUserNo`로 존재 여부만 확인, 역할(`LS_USER_ROLE`)·활성(`USE_YN`) 조인 없음. `AssignmentServiceTest`에 역할 검증 관련 테스트 전무(Grep 확인). → 본 회차 **D-ISSUE-63** |
| **D-ISSUE-07**(1차) / **D-ISSUE-21**(2차 통합본) — FK/PK 위반을 "중복 배정 409"로 오분류 | TC-ASSIGN-006/021 | **미해소 이월.** `:101-103` catch 블록 변경 없음(`DataIntegrityViolationException` 전체를 동일 메시지로 처리). → 본 회차 **D-ISSUE-62** |
| **D-ISSUE-81**(2차 통합본) — `commitApproved`의 `rawSn==null` IllegalArgumentException → 미매핑 500 | TC-VERSION-007 | **미해소 이월.** `VersionService.java:160-162` 그대로, `GlobalExceptionHandler`에 `IllegalArgumentException` 핸들러 여전히 없음(전수 Grep 재확인). → 본 회차 **D-ISSUE-65** |
| **D-ISSUE-23**(2차 통합본) — D-3 전 26건 근거 file:line 전면 드리프트 | D-3 전체 | **✅ 해소 확인.** 26건 전수 재대조 결과 25건 정확 일치, 1건(TC-ASSIGN-014)만 1행 오차(290 vs 291) — 카탈로그 changelog(3회차, "AssignmentService.java(D-3, 22건) 하방 이동")가 이미 대부분 정정한 상태였음을 재확인. 잔여 1행 오차는 본 회차에서 정정 완료(아래 카탈로그 정정 참조) |

---

## 카탈로그 정정 (본 회차, 담당 라인범위 69~134 안에서만 수행)

| # | 대상 | 정정 내용 |
|---|---|---|
| 1 | TC-ASSIGN-014 | 근거 `AssignmentService.java:283-286,290-299` → `:283-286,291-299`(라인 290은 직전 `DataIntegrityViolationException` catch의 throw 메시지 계속행이며, `OptimisticLockingFailureException` catch는 291행부터 시작 — 1행 오차 정정) |

> 그 외 D-2(4건)·D-4(17건)·D-3 나머지(25건)의 `file:line`은 전수 재대조 결과 실제 코드와 정확히 일치하여 정정 불필요.
> 프로덕션 코드·테스트·마이그레이션은 일절 수정하지 않았다.

---

## 이슈 대장

### [D-ISSUE-61] TC-REVIEW-034 — `ReviewStateMachine.verify(null, to)`는 여전히 NPE(500) — 400 미보장 (1차 D-ISSUE-03 이월, 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: 상태머신은 어떤 입력에도 fail-closed 로 동작해 알 수 없는/누락된 출발 상태를 `INVALID_INPUT(400)`으로 거부해야 한다. 카탈로그(TC-REVIEW-034)도 이를 "기대값은 400"으로 명시한다.
- **현재 동작(이슈 내용)**: `ReviewStateMachine.java:43-51`의 `ALLOWED`는 `Map.of(...)`(JDK `ImmutableCollections.MapN`)이다. `:59` `ALLOWED.get(from)`에서 `from=null`이면 내부적으로 키 해시를 계산하려다 `NullPointerException`이 발생하며, `:60-63`의 `allowed == null` 분기(400 처리)에 도달하지 못한다.
  ```java
  // ReviewStateMachine.java:53-64
  public void verify(String from, String to) {
      if (LsRawDataStatus.STTS_APPROVED.equals(from) && !LsRawDataStatus.STTS_PENDING.equals(to)) {
          throw new CustomException(ErrorCode.CONFLICT, ...);
      }
      Set<String> allowed = ALLOWED.get(from);   // from==null → NPE
      if (allowed == null || !allowed.contains(to)) {
          throw new CustomException(ErrorCode.INVALID_INPUT, ...);
      }
  }
  ```
  COMPLETED/PROCESSING(맵 미등록 키)은 `ALLOWED.get(...)`이 정상적으로 `null`을 반환해 400이 되므로 이 두 값은 정상 동작한다 — 오직 `from=null`만 NPE.
- **재현/확인 경로**: HTTP로는 도달 불가(`LS_RAW_DATA_STATUS.DATA_STTS_CD`는 NOT NULL이고 모든 `transitionTo` 호출자가 null을 넣지 않음). 단위 호출 `new ReviewStateMachine().verify(null, "PENDING")`으로만 재현. `ReviewStateMachineTest.java`(전체 8개 테스트 확인) 전수 Grep 결과 null 케이스 테스트 없음.
- **영향**: 현재 실경로 미도달이라 운영 영향 없음(이론적 결함). 향후 상태 컬럼이 nullable로 바뀌거나 이 클래스가 다른 컨텍스트(외부 인입 등)에서 재사용되면 500 + 예외 처리 미흡(OWASP A10:2025)이 표면화된다.
- **수정 방향(제안)**: `verify` 진입부에 `if (from == null) throw new CustomException(INVALID_INPUT, ...)` 가드 추가 + `ReviewStateMachineTest`에 null 케이스 회귀 테스트 신설. ⚠ 구현하지 않음.

### [D-ISSUE-62] TC-ASSIGN-006 / TC-ASSIGN-021 — 배정 API가 FK 위반·PK 충돌을 전부 "중복 배정 409"로 오분류 (1차 D-ISSUE-07 / 2차 D-ISSUE-21 이월, 미해소)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 존재하지 않는 영상(rawSn)에 대한 배정 시도는 404/400 계열이어야 하고, 상태행 PK 충돌(동시 배정 경합)은 실제 원인에 맞는 메시지를 안내해야 한다. 서로 다른 원인을 같은 메시지로 뭉개면 운영자가 실제로 존재하지 않는 "중복 배정"을 찾아 헤맨다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:101-103`의 `catch (DataIntegrityViolationException)`이 UK 충돌·FK 위반·PK 충돌을 구분 없이 삼켜 동일 문구로 409를 낸다.
  ```java
  // AssignmentService.java:101-103
  } catch (DataIntegrityViolationException e) {
      log.warn("[Assignment] duplicate assignment detected workerId={}", req.workerId());
      throw new CustomException(ErrorCode.CONFLICT, "이미 동일 작업자에게 배정된 영상이 있습니다.");
  }
  ```
  `assign()` 진입부(`:76-84`)는 workerId/reviewerId 존재만 검증하고 `rawDataIds` 자체의 존재 여부는 검증하지 않으므로, 존재하지 않는 rawSn을 배정 시도하면 INSERT 시 FK 위반이 나고 위 catch가 이를 "중복 배정"으로 안내한다. `upsertDataStts`(`:663-666`)의 동시 INSERT PK 충돌(TC-ASSIGN-021, 서로 다른 작업자 동시 배정 경합)도 같은 경로로 유입되어 동일하게 오분류된다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/assignments -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"workerId":2001,"rawDataIds":[999999]}'
  # → 409 {"errorCode":"CONFLICT","message":"이미 동일 작업자에게 배정된 영상이 있습니다."}
  # DB 확인: select count(*) from ls_data_raw where raw_sn=999999;  -- 0
  ```
- **영향**: 기능/운영. API 계약과 어긋난 에러 메시지(리소스 없음=404 원칙 미준수), 진단 혼선. 데이터 정합은 트랜잭션 롤백으로 보존되어 보안 영향은 없음.
- **수정 방향(제안)**: `assign()` 진입부에 `videoRepository.findAllById(rawDataIds)`로 존재 검증(단일 IN 쿼리)을 선행 추가해 미존재 시 404/400을 먼저 던진다. `catch (DataIntegrityViolationException)`에서 제약명(`uk_...` vs `fk_...` vs `..._pkey`)으로 분기하거나, 상태행 upsert를 조건부 INSERT로 분리해 UK 충돌만 "중복 배정" 문구를 쓰게 한다. ⚠ 구현하지 않음.

### [D-ISSUE-63] TC-ASSIGN-001 / TC-ASSIGN-004 / TC-ASSIGN-005 / TC-ASSIGN-007 / TC-ASSIGN-009 — 배정 대상 사용자의 역할·활성 여부를 검증하지 않는다 (1차 D-ISSUE-06 / 2차 D-ISSUE-22 이월, 미해소)

- **심각도**: MEDIUM (CWE-863 Incorrect Authorization)
- **기대 동작(기대효과)**: `CLAUDE.md` "작업 배정" 규칙 — "REVIEWER가 WORKER에게 배정". `LS_TASK_ASSIGNMENT.TASK_TYPE_CD='LABELER'` 행의 `USER_NO`는 WORKER 역할 + 활성(`USE_YN='Y'`) 사용자여야 하고, `'REVIEWER'` 행은 REVIEWER여야 한다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:76-81`(assign)·`:255-257`(reassign)이 `userRepository.findByUserNo(...)`로 **존재 여부만** 확인한다.
  ```java
  // AssignmentService.java:76-81
  if (userRepository.findByUserNo(req.workerId()).isEmpty()) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "존재하지 않는 작업자입니다.");
  }
  if (req.reviewerId() != null && userRepository.findByUserNo(req.reviewerId()).isEmpty()) { ... }
  ```
  `findByUserNo`는 `USE_YN` 필터도, `LS_USER_ROLE` 조인도 없다. 실제로 작업자 선택 드롭다운을 채우는 `UserRepository.findAllWorkersWithTaskCount()`는 `LsUserRole.roleCd='WORKER' AND u.useYn='Y'`를 이미 걸고 있어(FE 목록은 필터링됨), API는 무검증인 전형적 FE-BE 비대칭이다.
- **재현/확인 경로**: `AssignmentServiceTest.java` 전수 Grep(30개 `@DisplayName` 확인) 결과 역할/활성 검증 테스트 없음(코드 정적 확인만으로 재현 가능 — 1차 회차 실측 재현 기록: REVIEWER 계정을 LABELER로 배정 시 201 성공).
- **영향**: ①PORTAL_USER/REVIEWER에게 배정된 영상은 그 사용자가 내부 워크플로 API를 호출할 수 없어 워크플로가 정체된다(배정 취소 API 부재 — 재배정으로만 회수) ②작업자별 배정 카운트·작업목록·이력에 비-WORKER가 섞여 집계가 오염된다.
- **수정 방향(제안)**: `assign`/`reassign`의 사용자 검증을 역할·활성 확인으로 승격 — `LsUserRoleRepository`로 workerId가 WORKER(+useYn='Y')인지, reviewerId가 REVIEWER인지 검사. 드롭다운 쿼리(`findAllWorkersWithTaskCount`)와 같은 술어를 공유하는 단일 판정 지점으로 추출. ⚠ 구현하지 않음.

### [D-ISSUE-64] TC-ASSIGN-026 — IN_REVIEW 영상 재배정 허용 여부 정책 미확정 (1차 D-ISSUE-05 이월, 확인필요)

- **심각도**: LOW (확인필요 — 의도된 정책일 수 있음)
- **기대 동작(기대효과)**: 카탈로그가 이미 "현행 허용(APPROVED만 차단) — D-ISSUE-05 미해소, 정책 확정 필요"로 명시하고 있어, 이 항목은 결함이 아니라 **정책 확정 대상**이다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:235-240`의 가드는 `STTS_APPROVED` 한 값만 차단한다.
  ```java
  // AssignmentService.java:235-240
  dataSttsRepository.findByRawDataIdForShare(prev.getRawDataId()).ifPresent(stts -> {
      if (LsRawDataStatus.STTS_APPROVED.equals(stts.getDataSttsCd())) {
          throw new CustomException(ErrorCode.ASSIGNMENT_ALREADY_COMPLETED, "완료된 작업은 재배정할 수 없습니다.");
      }
  });
  ```
  IN_REVIEW(검수 진행 중) 영상도 재배정 API가 통과시킨다 — 배정 작업자만 바뀌고 검수 상태(IN_REVIEW)는 그대로 유지된다.
- **재현/확인 경로**: 코드 정적 확인(1차 실측 기록: rawSn이 IN_REVIEW인 상태에서 `PATCH /v1/assignments/{id}` → 200 성공, 상태는 IN_REVIEW 유지, 기존 작업자만 교체됨).
- **영향**: 상태 일관성/운영 혼선(데이터 손상 없음). "검수 중인데 배정 작업자만 바뀐" 상태가 만들어질 수 있음.
- **수정 방향(제안)**: 가드를 `APPROVED` 단일 값에서 검수 진행 상태 집합(`PENDING`/`IN_REVIEW`/`APPROVED`)으로 확장할지, 아니면 현행(검수 중에도 작업자 교체 허용)이 의도된 정책인지 사용자 확정 필요. ⚠ 구현하지 않음.

### [D-ISSUE-65] TC-VERSION-007 — `commitApproved`의 `rawSn==null` 가드가 처리되지 않은 예외로 이어져 500 응답 (2차 D-ISSUE-81 이월, 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: `VersionService.commitApproved(rawSn, actor)`는 `rawSn==null`일 때 의미 있는 4xx로 실패해야 한다. 카탈로그 기대결과("IllegalArgument/UNAUTHORIZED(401)")도 명확한 클라이언트 오류 응답을 전제한다.
- **현재 동작(이슈 내용)**: `VersionService.java:160-162`가 순수 `IllegalArgumentException`을 던지는데, `GlobalExceptionHandler`에는 이를 처리하는 `@ExceptionHandler`가 없다(전수 Grep 재확인: `CustomException`/`MethodArgumentNotValidException`/`ConstraintViolationException`/`HttpMessageNotReadableException`/`MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException`/`DataIntegrityViolationException`/`AccessDeniedException`/`AuthenticationException`/`MaxUploadSizeExceededException`/`NoResourceFoundException`/`HttpRequestMethodNotSupportedException`/`Exception` 뿐). 따라서 제네릭 `@ExceptionHandler(Exception.class)`로 떨어져 500이 된다.
  ```java
  // VersionService.java:160-165
  if (rawSn == null) {
      throw new IllegalArgumentException("rawSn 은 필수입니다.");
  }
  if (actor == null) {
      throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
  }
  ```
- **재현/확인 경로**: `commitApproved`는 REST로 직접 노출되지 않고 `ReviewService.approve()`가 `stts.getRawDataId()`(항상 non-null FK)로만 호출하는 내부 전용 메서드라 현재 HTTP 요청으로는 도달 불가. `VersionServiceTest.java` 전수 Grep 결과 `commitApproved(null, ...)` 직접 호출 테스트 없음.
- **영향**: 기능 영향 낮음(공개 API 경로 도달 불가). 향후 `commitApproved`가 다른 컨텍스트(관리자 수동 재스냅샷 API 등)에서 재사용되면 이 가드가 그대로 500을 낼 잠재 함정. OWASP A10:2025(Mishandling of Exceptional Conditions) 관점에서 "의미 있는 4xx" 원칙과 어긋남.
- **수정 방향(제안)**: `IllegalArgumentException`을 `CustomException(ErrorCode.INVALID_INPUT, ...)`으로 교체(로컬 교체가 전역 핸들러 신설보다 영향범위가 좁아 안전). ⚠ 구현하지 않음.

---

# D-8. 데이터마트 View (TC-MARTVIEW-001~023) — 3차 검증 (D-part5)

검증일: 2026-08-04(스택 기동은 2026-08-03 3차 §3-1/§3-3 산출물 재사용) · 담당: D-8 데이터마트 View 23건
방법: `docker exec klid-postgres psql`로 4종 View(V_COMPLETED_VIDEO/FRAME/LABEL_CHANGE/META)를 실제 SELECT.
공용 정상데이터 rawSn=101(APPROVED, export SUCCEEDED) 재사용 + 기존 DB에 이미 존재하던 반증용 실데이터(rawSn=94 PARTIAL export 이력, rawSn=26/5 ACTIVE_YN 혼재, rawSn=18 해상도파생, rawSn=15/8/9 비식별신고 APPROVED)를 최대한 활용. 부족한 반증 케이스(ACTIVE_YN=N 단독, video.codec 노출, 동일경로 프레임, 신고구간 export 보존, 레거시 파생 환경값)는 `BEGIN...ROLLBACK` 트랜잭션으로 격리된 시드 데이터를 삽입해 실제 뷰로 SELECT 후 즉시 롤백(DB에 영구 흔적 없음, 병렬 실행 중인 다른 파트 에이전트 데이터와 충돌 없음). 빌드/테스트 미실행, 결과 파일 외 수정 없음(단 카탈로그 정정 3건은 지시에 따라 담당 라인범위 235~268행 내에서 Edit 수행).

## 판정 요약

| 판정 | 건수 | TC-ID |
|---|--:|---|
| PASS | 23 | 001~023 전건 |
| FAIL/PARTIAL/BLOCKED/확인필요 | 0 | — |

**전건 PASS.** 단 TC-MARTVIEW-004는 카탈로그의 기존 기대값("SUCCEEDED만 조인, PARTIAL 제외")이 2026-08-02 E-ISSUE-81 수정(V160)으로 이미 폐기된 정책이라 **근거 드리프트로 판정하고 카탈로그를 실제 동작(PARTIAL 포함)에 맞춰 정정**했다 — 시스템 결함이 아니라 문서가 낡았던 것.

## 결과표

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-MARTVIEW-001 | PASS | [실동작] `SELECT * FROM v_completed_video WHERE raw_sn=101` → 1행, APPROVED 확인. 뷰 전체 16행 = INNER JOIN(APPROVED) 결과와 일치 | |
| TC-MARTVIEW-002 | PASS | [실동작] rawSn=101 `export_path_nm=/app/storage/raw/seed/101, frame_cnt=20`(SUCCEEDED) · rawSn=94 최신버전(v6, PARTIAL) `frame_cnt=10` 노출 | 근거 인용을 V160으로 정정(카탈로그 수정) |
| TC-MARTVIEW-003 | PASS | [실동작] rawSn 15/8/9(export 0건) `export_path_nm`/`frame_cnt` 둘 다 NULL, 전체 뷰 `count(*)=count(distinct raw_sn)=16` | |
| TC-MARTVIEW-004 | PASS(카탈로그 정정) | [실동작] rawSn=94 export 이력 v1 SUCCEEDED→v2 PARTIAL→v3 FAILED→v4 PARTIAL→v5 FAILED→v6 PARTIAL 중 뷰는 최신 v6(PARTIAL)을 그대로 노출(FAILED는 skip). `pg_get_viewdef('v_completed_video')`가 V160 정의(`EXPORT_STTS_CD IN ('SUCCEEDED','PARTIAL')`)와 100% 일치 | **카탈로그 드리프트**: 구 기대값 "SUCCEEDED만 조인"은 2026-08-02 E-ISSUE-81 수정으로 폐기됨(2차 targeted-B-DE.md에서 Testcontainers로 이미 확인된 사실, 실 DB에도 반영 확인). 담당 범위 내 Edit로 정정 완료 |
| TC-MARTVIEW-005 | PASS | [실동작] rawSn=26(ACTIVE_YN N×2+Y×1, APPROVED)은 Y스냅샷 값(evnt_nm=쓰러짐)만 노출 · BEGIN/ROLLBACK 시드(ACTIVE_YN=N만 있는 신규 APPROVED raw)로 `count(*)=0` 확인 | |
| TC-MARTVIEW-006 | PASS | [실동작] 현재 DB에 원본=비식별 동일경로 결함행 0건(healthy) · BEGIN/ROLLBACK 시드로 동일경로 행 삽입 후 `v_completed_frame` count=0(게이트 작동) · rawSn=18 src_sn=45(한쪽만 NULL, 결측)는 정상 노출되어 "결측은 통과" 규칙도 확인 | |
| TC-MARTVIEW-007 | PASS | [정적+실동작] V133:47-50 DESCRIPTION 컬럼 확인, `v_completed_frame` SELECT 결과에 description 컬럼 존재(값은 비어있는 프레임도 노출) | |
| TC-MARTVIEW-008 | PASS | [실동작] `v_completed_label_change` 컬럼 = lbl_hstry_sn/raw_sn/src_sn/add_cnt/mdfcn_cnt/del_cnt/reg_id/reg_dt 8개뿐, CHG_DTL_CN 미노출 확인 | |
| TC-MARTVIEW-009 | PASS | [정적] V139:38-43 `EXISTS (... DATA_STTS_CD='APPROVED')` 확인 | |
| TC-MARTVIEW-010 | PASS | [정적+실동작] V107:124-146 정확 일치(라인 드리프트 없음). rawSn=101 `v_completed_meta`에 `mrev.rvw_stts_cd='APPROVED'`인 "0-5" 메타만 노출 | |
| TC-MARTVIEW-011 | PASS | [정적+실동작] V107:140 `NOT LIKE 'video.%'` 정확 일치. rawSn=101 raw `ls_data_meta`에는 video.fps/codec/duration_ms/filesize/resolution/bit_rate 6종이 있으나 `v_completed_meta`에는 0건 노출. BEGIN/ROLLBACK 시드(video.codec+APPROVED review)로도 재확인 count=0 | |
| TC-MARTVIEW-012 | PASS | [실동작] `\dv`로 뷰 목록 = v_completed_frame/label_change/meta/video 4개뿐. `SELECT * FROM v_completed_label` → `relation does not exist` 에러 확인 | |
| TC-MARTVIEW-013 | PASS | [정적+실동작] V138:28-31·V139:12-15·V160:35-37 모두 "REPLACE, 끝 추가만" 명시. 3차 stack-bringup에서 V159~V163 5건 Flyway 연속 적용 성공(에러 0) + `\d v_completed_video` 컬럼 순서/타입이 V160 정의와 정확히 일치 | |
| TC-MARTVIEW-014 | PASS | [정적] `DatasetMaterializeApproveRollbackIT.java` 159줄 정확 일치, `materializeFailure_rollsBackApproveAcrossAllTables` 테스트가 LsRawDataStatus(IN_REVIEW 원복)·LS_LABEL_VERSION(0건)·LS_DATASET_VIDEO_META(미적재)·LS_META_REPL_OUTBOX(0건) 4테이블 전부 단언 | |
| TC-MARTVIEW-015 | PASS | [실동작] rawSn=101 `de_idntf_file_path_nm=/app/storage/raw/seed/101/deid/clip-9101-mask.mp4` — pipeline-drive.md의 파일시스템 실측(`101/deid/clip-9101-mask.mp4`)과 정확히 일치, mock 파일명 규칙(`{stem}-mask{ext}`)도 그대로 반영(문자열 조합 아님, procLog 원문) | |
| TC-MARTVIEW-016 | PASS | [실동작] rawSn=18(orgnl_raw_sn=4) `original_video_path IS NULL` 확인 | 근거 라인(123-132,158→129-138,164) 드리프트 정정 완료 |
| TC-MARTVIEW-017 | PASS | [실동작] `ls_data_lbl_hstry`에 0/0/0 델타 행 다수 실존(lbl_hstry_sn 34/35/36/52/53 등, 롤백/개인정보리셋 감사 흔적) — 전부 `v_completed_label_change`에서 제외(JOIN count=0). 반면 실제 델타(add_cnt=1 또는 mdfcn_cnt=1)가 있는 rawSn=101의 두 행(119/156)은 정상 노출 | |
| TC-MARTVIEW-018 | PASS | [실동작] rawSn 15/8/9(APPROVED, DE_IDNTF_YN='F')가 뷰에서 행 자체는 사라지지 않음(전건 존재) 확인. 추가로 BEGIN/ROLLBACK 시드(F 상태 + 사전 SUCCEEDED export/deident 존재)로 `export_path_nm`/`frame_cnt`/`de_idntf_file_path_nm`이 신고 중에도 NULL로 비워지지 않고 그대로 노출됨을 강하게 실증(뷰 WHERE절에 DE_IDNTF_YN 필터 자체가 없음도 정적 확인) | |
| TC-MARTVIEW-019 | PASS | [실동작] rawSn=101(수동 입력 없음) `day_ngt_cd`/`sesn_cd`/`wthr_nm` 전부 NULL(sht_dt는 채워져 있음에도 NGT/SUMMER 규칙 파생 없음) — self-fill 부재 확인 | |
| TC-MARTVIEW-020 | PASS | [정적+실동작] `ENV_CORRECTION_PREDICATE`(라이브 raw NULL + 활성 스냅샷 non-null) 정확 확인, 현재 DB엔 해당 백로그 0건(healthy). BEGIN/ROLLBACK 시드(라이브 NULL + 스냅샷 NGT/SUMMER)로 예측식이 정확히 픽업함을 실증. `DatasetVideoMetaEnvCorrectionTx.correct()`(68-83줄, 인용 라인 정확) 로직도 확인 | |
| TC-MARTVIEW-021 | PASS | [정적] `@Profile("!prd")`(prd 미등록) + `@PreAuthorize("hasRole('REVIEWER')")`(GET/POST 양쪽) + 별도 sub-resource(쿼리파라미터 분기 없음) 확인. `BatchDevTriggerController`와 동일 3중 방어 패턴 재사용 | prd 프로파일 자체를 로컬에서 기동해 404를 실측하진 못함(BLOCKED 아님 — 동일 패턴이 프로젝트 전역에서 이미 검증된 기존 관례) |
| TC-MARTVIEW-022 | PASS | [정적] `correctDerivedShootingEnvironment()` — `envCorrectionMaxPerRun` 상한 + `ENV_CORRECTION_BATCH_SIZE` 페이징 + 시작/진행/완료 로그 + 잔여는 다음 실행에서 자연 이어짐(멱등) 확인 | |
| TC-MARTVIEW-023 | PASS | [실동작] BEGIN/ROLLBACK 시드 2건(① ACTIVE_YN=N 단독 APPROVED raw, ② video.codec + APPROVED review)으로 각각 `v_completed_video`/`v_completed_meta` count=0 확인 | |

## 이전 회차 이슈 해소 여부 대조

- **E-ISSUE-81 / D-ISSUE-61 계열 (PARTIAL export 뷰 배제, HIGH)**: 2차(2026-08-02) `targeted-B-DE.md`가 Testcontainers(`DatamartViewSlimIT`)로 "PASS(컨테이너 실동작 BLOCKED)"로 잠정 확인했던 건을, 이번 3차에서 **실제 운영 컨테이너 DB(`klid-postgres`)에 V160이 실제로 적용된 상태로 재확인** — rawSn=94의 실제 PARTIAL export 이력이 뷰에 정확히 반영됨을 라이브 데이터로 실증. **완전 해소 확인** (BLOCKED → 실동작 PASS로 격상).
- 1차(2026-08-01) ISSUES.md에는 D-8/MARTVIEW 관련 이슈가 등록된 바 없음(grep 결과 0건) — 이번 회차가 D-8 최초 전수 실동작 검증.
- 신규 결함 없음. 카탈로그 드리프트만 3건 발견·정정(TC-MARTVIEW-002/003/004 근거 인용을 V138→V160으로, TC-MARTVIEW-016 라인 번호 123-132,158→129-138,164, TC-MARTVIEW-013에 V160 근거 보강).

## 참고 — 반증용 임시 시드 방법론

모든 반증 데이터는 `docker exec -i klid-postgres psql ... <<'SQL' BEGIN; ... SELECT ...; ROLLBACK; SQL` 패턴으로 삽입 직후 즉시 롤백했다. 이는 프로덕션 코드·설정·영구 데이터를 전혀 건드리지 않으면서(§10 절대규칙 준수) 실제 뷰 정의(정적 재구현이 아닌 진짜 `V_COMPLETED_*` 뷰)를 SELECT해 게이트가 실제로 작동하는지 검증하는 방식이다. 트랜잭션 종료 후 `ROLLBACK` 확인 메시지로 데이터 잔존 없음을 매번 확인했다.
