# 13. 버전관리

> 출처: R1 RQ-SFR-08-04/05, R2 KLID-AT-UC-007/008, CLAUDE.md(라벨링·버전관리), 코드(`version/`)
> 관련: [12 검수](12-review-assignment.md) · [15 관제서버 통지](15-control-notify.md) · [24 학습데이터 파일 산출](24-dataset-export.md)

> **레이어 구분**: 본 페이지의 `LS_LABEL_VERSION`(DB 라벨 스냅샷)과 별개로, 검수 승인 시 확정 라벨을 **디스크 물리 파일**(프레임 이미지 + NIA COCO JSON)로 산출하는 기능은 [24 학습데이터 파일 산출](24-dataset-export.md) 참고.

화면: **SC-005 라벨링 캔버스(`/label/:id`)의 히스토리 인라인 패널**(`HistoryPanel` — '변경 이력' / '버전' 탭 + diff + 롤백). 코드: `version/`.
> 구 전용 화면 **SC-010 라벨 이력(`/history/:videoId`, `HistoryPage`)은 2026-08-03 제거**됐다(유일 진입점이던 영상 상세의 '버전관리로 이동' 버튼 제거 + 인라인 패널이 동일 기능을 모두 제공) → [04 화면 IA](04-screens-ia.md).

## 13.1 DB 스냅샷 기반 (외부 VCS 미사용)

- 라벨 저장 시 라벨 **전체 스냅샷(JSON)** 을 `LS_LABEL_VERSION.LABEL_PAYLOAD`에 저장
- 버전 식별자 = 페이로드 해시 **`VERSION_HASH`(SHA-256)** — 동일 페이로드 재저장 시 동일 해시로 중복 식별(멱등)
- ADR-009: 외부 Git/Gitea 미사용. (단 코드에는 Gitea fallback 큐 `LS_GITEA_FALLBACK_QUEUE`/`GITEA_CMT_HASH` 흔적 존재 — R1 v1.2에서 **DB 스냅샷으로 확정**)

## 13.2 스냅샷 생성 시점

- **검수 승인(APPROVED) 시점에만** 스냅샷 생성 (`SAVE_REASON_CD='APPROVED'`)
- 라벨 임시저장 단계는 스냅샷 미생성 (`LS_DATA_LBL` upsert만)
- 저장 데이터: `{DATA_RAW_SN, DATA_SRC_SN, LABEL_PAYLOAD(JSON), VERSION_HASH, VER_NO(V90 rename, 구 VERSION_NO), SAVE_REASON_CD, ACTVTN_YN, REG_ID/REG_DT}`
- 변경이력은 `LS_DATA_LBL_HSTRY` 병행

## 13.3 diff (비교)

- 두 APPROVED 버전의 DB 스냅샷을 **앱에서 비교**해 라벨 단위 변경 목록 산출
- `GET /v1/frames/{srcSn}/versions` (이력) → `GET /v1/versions/{version}/diff?compareWith={fromHash}`
- 응답 라벨 diff 최대 500건 (OWASP API4)

### ★ 13.3.1 버전 ↔ 현재 작업본 diff (2026-08-05 신설)

**`GET /v1/versions/{version}/diff-with-working`** — 지정 버전 스냅샷(`from`)과 **현재 작업본**(`LS_DATA_LBL`, `to`)의 라벨 단위 diff. 응답은 기존 `/diff` 와 동일한 `LabelDiff[]`.

- **왜 신설했나**: 승인 버전이 1건뿐인 프레임은 `/diff?compareWith=` 로 **비교할 대상이 없어 변경 내역을 볼 수 없었다.** 이 경로는 버전 1건만 지정해 "승인 이후 지금까지 무엇이 바뀌었나"를 계산한다.
- **별도 sub-resource 인 이유**: `rules/api-design.md` 「같은 URL 에 쿼리 파라미터로 행위 분기 금지」 + 프로젝트 전례(`/v1/frames/{srcSn}/deid-image`). 기존 `/diff` 의 **URL·파라미터·응답 스키마·상태코드는 무변경**이다.
- **작업본 payload 는 승인 스냅샷과 동일 방식으로 생성**한다 — 같은 `LabelResponse.of(...)` 인자 + `LBL_SN` 오름차순 정렬 + 같은 직렬화 경로(`serializeSnapshotWithSimplification`). 하나라도 어긋나면 **수정이 없는데 diff 가 나오는 오탐**이 난다. **DB 에 저장하지 않는다**(읽기 전용, 새 버전 행 미적층).
  - ⚠ 1MB 초과 시 폴리곤 손실 단순화가 **양쪽 모두**에 적용된다. 재현하지 않으면 이미 단순화된 승인 스냅샷과 비교할 때 수정 0건인데 전량 MODIFIED 가 나기 때문. 대가로 작업본이 편집으로 1MB 경계를 넘나들면 편집하지 않은 폴리곤까지 MODIFIED 로 **과대보고**될 수 있다(인지·수용, 발생 시 WARN 관측).
- **보안 게이트는 기존 diff 와 동일**: 해시 형식 400 / 프레임 스코프(`DATA_SRC_SN` NULL) 400 / IDOR 403·404 / **비식별 신고 구간(`DE_IDNTF_YN='F'`) 412**. 게이트 순서는 `해시검증 → 버전조회 → 프레임스코프 → 인가 → 신고게이트 → 그 다음에야 라벨 읽기`이며 **순서 자체가 회귀 테스트로 고정**돼 있다(라벨을 먼저 읽고 게이트를 나중에 평가하면 게이트가 무의미해진다 — CWE-359).
- **손상 스냅샷은 빈 결과가 아니라 400 이다**: `computeLabelDiffs` 는 파싱 실패를 삼키고 빈 리스트를 돌려주는데(기존 `/diff` 의 장애격리 계약 — 유지), 이 경로의 빈 결과는 화면에서 **"변경 없음"** 으로 표시되므로 거짓말이 된다. 따라서 이 경로에서만 `items` 가 **명시적 배열일 때만** 통과시킨다(`{}` · `{"items":null}` · `{"items":"x"}` · 스칼라 root → 400). **`LABEL_PAYLOAD` 자체가 blank/null 인 것은 손상이 아니라 라벨 0건**이라 400 이 아니다.

### ★ 13.3.2 diff 비교축 — `trackId`·`labelId` 포함 (2026-08-05 계약 변경, 구 동작 폐기)

라벨 스냅샷 비교(`LabelSnapshot.equalsContent`)는 **`id`·`lblTypeCd`·`label`·`labelId`·`points`·`trackId`** 를 본다.

- **구 동작(폐기)**: `lblTypeCd`·`label`·`points` **3개만** 비교했다. 그래서 **트랙 병합**(`TrackMergeService.doMerge` → `reassignTrack`, 좌표·타입·라벨명·`LBL_SN` 전부 불변)처럼 **시스템 자신이 라벨 수정으로 인정해** `TaskModifiedEvent(LABEL_UPDATED)` 를 발행하고 export 를 `v{n+1}` 로 전량 재생성·관제 재통지까지 한 변경이 diff 에서는 **"변경 없음"** 으로 응답됐다. 승인 버전이 1건뿐인 프레임에서는 이것이 유일한 확인 수단이라 REVIEWER 가 "무수정"으로 오판할 수 있었다.
- **판정 축의 근거**: export 재생성·통지를 실제로 결정하는 `LabelContentHasher.appendLabels` 의 입력은 `lblSn · srcSn · labelId · lblTypeCd · labelNm · pointCn · trackId` 다. 확장 후 비교축은 **`srcSn`(프레임 스코프 자체)만 빼면 이와 합동**이라, "시스템은 수정으로 보는데 diff 는 아니라고 답하는" 모순이 축 단위로 사라진다.
- ⚠ **이 비교기는 `/diff` 와 `/diff-with-working` 이 공유**한다 → **기존 `/diff` 의 판정 결과도 함께 바뀐다**(좌표가 같아도 트랙·라벨 마스터 FK 가 다르면 이제 `MODIFIED`). 엔드포인트 **계약**(URL·파라미터·응답 스키마·상태코드)은 무변경.
- **AI 메타(`autoLblYn`/`confScore`/`lblSrcCd`)와 `labelName`/`color` 는 의도적으로 제외**한다 — ①좌표 변경 없는 출처·신뢰도 변동은 사람의 라벨 편집이 아니고(기존 `LBL_SN` 의 AI 메타만 바뀌는 사람의 편집 경로가 실재하지 않는다), export 해시 축에도 들어가지 않아 재생성·통지를 유발하지 않는다 ②`confScore` 부동소수를 비교축에 넣으면 잡음 diff 가 난다 ③`labelName`/`color` 는 마스터 조인값인데 이 경로의 `LabelResponse` 오버로드에서 항상 `null` 이라 비교 의미가 없다.
- **알려진 한계(인지·수용)**: ①`items[]` 원소에 `id` 가 없으면 조용히 skip 되어 그 라벨이 과대보고된다(앱이 쓴 payload 에는 `id` 가 항상 있어 도달성은 사실상 0) ②`MAX_LABELS=500` 절단 사실을 응답이 알리지 않는다(기존 계약 상속 — 이 경로는 "승인 이후 누적 편집"을 비교하므로 절단 가능성이 상대적으로 크다) ③롤백의 PK 충돌 폴백으로 `LBL_SN` 이 재발급되면 내용이 같아도 `REMOVED + ADDED` 쌍으로 보고된다(기존 성질).

### ★ 13.3.3 화면 동작 — 커밋 1건 선택 = 현재 작업본과 비교 (2026-08-05, 구 동작 폐기)

라벨링 화면(SC-005) 히스토리 인라인 패널 '버전' 탭:

| 조작 | 비교 대상 |
|---|---|
| 커밋 **1건 클릭** | 그 버전 ↔ **현재 작업본** (`/diff-with-working`) |
| 커밋 **2건 체크** | 두 버전 간 비교 (`/diff?compareWith=`) — 기존 그대로 |

- **구 동작(폐기)**: 1건 클릭 시 목록상 **직전 버전**(`list[idx + 1]`)과 비교했다. 그래서 버전이 1건뿐이면 비교 대상이 `undefined` 라 조회가 아예 실행되지 않았다.
- 비교 대상을 화면에 표시한다(`{shortHash} → 현재 작업본` / `{from} → {to}`).
- **변경 0건이면 빈 목록이 아니라 "변경 없음 / 이 버전 이후 변경된 라벨이 없습니다." 안내**를 띄운다. 렌더 분기 순서가 `미선택 → 로딩 → 에러 → 결과` 라 **조회 실패(412 등)가 "변경 없음"으로 표시되지 않는다.**
- 캐시 키는 `VERSION_KEYS.workingDiff` 로 기존 `VERSION_KEYS.diff` 와 분리하되 **`VERSION_KEYS.all` 하위**에 둔다 — 라벨 저장(`useUpdateLabels`)·롤백(`useRollback`)이 이미 `invalidateQueries({ queryKey: VERSION_KEYS.all })` 를 호출하므로 별도 무효화 배선 없이 자동 갱신된다.
- **프레임(`srcSn`) 전환 직후 stale 요청 차단**: 선택 리셋이 `useEffect`(커밋 이후)라 한 렌더 동안 이전 프레임의 hash 가 남아 **다른 프레임의 라벨 diff 가 잠깐 표시**됐다. 현재 목록에 실재하는 hash 만 조회하도록 가드하며, **단일 선택·두 버전 비교 두 축에 동일 적용**한다(판정은 공용 헬퍼 1곳).
- **프레임 스코프 강제**: `DATA_SRC_SN` 이 NULL 인 버전(구 비식별 신고 영상 스코프 스냅샷 — 신규 적재 중단, 기존 행만 잔존)은 프레임 단위 비교 대상이 아니므로 **400 으로 명시 거부**한다. 구현에 가드가 없어 `findById(null)` 에서 미처리 500 이 나던 결함을 수정했다(D-ISSUE-26) → [08](08-deidentification.md) 8.4

## 13.4 rollback (복구)

- `POST /v1/versions/{version}/rollback` (body `srcSn`) — **대상 스냅샷 행을 다시 active 로 전환**한다(새 버전 행 적층 없음). 구 `SAVE_REASON='ROLLBACK'` 코드는 폐기: 롤백 결과 페이로드는 대상 스냅샷 그 자체라 재계산 해시가 대상 행과 같고 `(DATA_SRC_SN, VERSION_HASH)` UNIQUE 로 적층이 불가능하다(도달 불가 분기)
- **라벨 본문을 작업본(`LS_DATA_LBL`)으로 실제 복원**한다 — `LBL_SN`·AI 메타(`AUTO_LBL_YN`/신뢰도/출처)·`TRCK_ID` 까지 **보존 복원**(PK 를 재발급하면 이후 diff 가 "전량 교체"로 오분류되므로 점유된 PK 만 신규 발급 폴백). 라벨링 캔버스(`GET /v1/frames/{srcSn}/labels`)가 롤백 결과를 즉시 반영
- **롤백 행위는 `LS_DATA_LBL_HSTRY` 에 기록**: 누가(actor)·언제(시각)·어느 버전으로(대상 `VERSION_HASH`)
- **멱등 롤백은 no-op**: 현재 active 가 이미 대상 스냅샷이면 라벨을 재작성하지 않고 이력·통지도 발행하지 않는다
- IDOR·비관적 잠금으로 소유 검증·동시성 제어. 작업락(비식별 재처리 중) 영상은 롤백 거부(409)
- **ACTIVE 버전은 프레임당 항상 1건** — 롤백·검수승인 스냅샷 모두 **프레임 행(`LS_DATA_SRC`) 락을 직렬화 앵커로 먼저 잡고, 활성 버전 목록은 그 락 확보 이후에 재조회**한 값으로만 판정·비활성화한다. ACTIVE 행 잠금만으로는 부족하다: PostgreSQL READ COMMITTED 의 `FOR UPDATE` 는 대기 후 술어를 잃은 행을 결과에서 **탈락**시킬 뿐, 그 사이 경쟁 트랜잭션이 **새로 ACTIVE 로 만든 행**을 결과에 넣어주지 않아 서로의 변경을 못 본 채 둘 다 커밋된다(write skew → ACTIVE 2건 잔존, D-ISSUE-21). 잠금 **순서** 규약(`LS_LABEL_VERSION` → `LS_DATA_SRC` → `LS_DATA_LBL`)은 그대로이며 조회 **시점**만 앵커 이후로 옮긴 것이다
- 복구로 라벨 변경 시 `TASK_MODIFIED` 통지 트리거 → [15](15-control-notify.md)

## 13.5 권한

- REVIEWER 전체 / WORKER 본인 배정 프레임 접근

## 13.6 관련 데이터 (DB)

`LS_LABEL_VERSION`(스냅샷·해시·active), `LS_DATA_LBL_HSTRY`(변경 이력), `LS_GITEA_FALLBACK_QUEUE`(통신 실패 재시도). → [18](18-database.md).

## 13.7 작성자 표시명 사번-이름 정정 (2026-08-05, 외부 FE 팀 고지 대상)

**결함**: 버전 목록·라벨 변경 이력 응답이 표시용 필드에 **사번(`REG_ID`)을 그대로** 내려보내 화면에 "2001"·"1001" 같은 내부 번호가 찍혔다. 특히 `GET /v1/frames/{srcSn}/versions` 의 `VersionItem.authorName` 은 **필드명은 "이름"인데 실제 값은 사번**이었다.

**수정 — 응답 계약 변경 3건**:

| 엔드포인트 | 변경 내용 |
|---|---|
| `GET /v1/frames/{srcSn}/versions` | ★**`authorName` 의 의미가 사번 → 실제 표시명으로 바뀜**(필드명 유지, 값 의미 변경 — Breaking). 사번은 신규 필드 **`authorNo`**(`string\|null`)로 분리 |
| `POST /v1/versions/{version}/rollback` | **`registeredUserName`**(`string\|null`) 신규 추가. `registeredUserNo` 는 하위호환으로 계속 사번을 담는다(값 의미 불변) |
| `GET /v1/frames/{srcSn}/label-history` (13.2·[10](10-labeling.md) `LabelHistoryPanel`) | **`actorName`**(`string\|null`) 신규 추가. `actor` 는 하위호환으로 계속 사번을 담는다(값 의미 불변) |

**공통 규칙**:
- 표시명 원천은 `LS_ACNT_USER.USER_NM`. 해석은 페이지의 사번을 모아 **`findByUserNoIn` 1회**로 조회하는 공용 헬퍼 `UserNameResolver`(`user/service/UserNameResolver.java`)가 담당 — N+1 금지.
- **비숫자 사번(`REG_ID` 는 VARCHAR) / 사용자 마스터 미존재(퇴사·계정 삭제) / `REG_ID` null(시스템 이력 행) → 예외가 아니라 이름 `null`**, 조회 자체는 계속 200을 반환한다(과거 작성자가 없어져도 이력·버전 조회는 죽지 않아야 한다).
- **FE 는 이름이 없으면 사번으로 폴백**해야 한다(빈칸 금지). 우리 FE 는 `frontend/src/lib/displayName.ts` 의 `resolveDisplayName(name, fallback)` 을 쓰고, 라벨 이력 패널은 사번·이름이 둘 다 없으면(시스템 이력 행) "시스템"으로 표시한다(`LabelHistoryPanel`).
- ⚠ `authorName` 은 **의미가 바뀐(사번→이름) 필드**라 외부 FE 팀이 구 계약(사번 그대로 표시)으로 소비 중이면 화면에 "2001" 이 사라지고 이름이 뜨는 것이 정상 동작이다 — 화면이 이름을 기대하지 않고 사번 그대로 재사용하고 있었다면 표시 로직 점검이 필요하다는 점을 고지할 것.
- ⚠ 이 판정과 동형인 사번→이름 해석이 이슈 스레드(`IssueThreadService.resolveNames`, [21](21-issue-channel.md))·검수 목록(`ReviewService.lookupUserNames`)에도 각각 별도 구현으로 존재한다(이번 변경으로 통합하지 않음 — 시그니처·입력 타입이 달라 회귀 위험이 중복 제거 이득보다 크다는 판단). 후속 수렴 과제로 남아 있다.
