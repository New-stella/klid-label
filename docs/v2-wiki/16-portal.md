# 16. 포털 (외부 채널)

> 출처: CLAUDE.md(포털), R2 KLID-AT-ACT-003, ADR-013, 코드(`portal/`, `frontend portal/`)
> 관련: [03 인증·권한](03-auth-roles.md) · [04 화면·IA](04-screens-ia.md)

화면: `SC-028`(포털 홈, 데이터마트 영상 선택 `/portal`), `SC-029`(포털 라벨링 `/portal/label/:id`). 코드: `portal/`(7 파일).

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"`KLID-AT-SC-029`"* 는 낡은 식별자다. 코드의 1차 식별자는 `SCREEN-NNN`(축약 `SC-NNN`) 체계다. 다만 **`SC-029`는 확정이 아니라 정황적 추정**이다 — `PortalLabelingPage.tsx` 파일 자체에는 직접 태그가 없고, `FrameNavigator.tsx`의 교차참조 문구("SCREEN-005 §캔버스 상단 옵션바 / SCREEN-029 동일 배치")와 `04-screens-ia.md`(§4.3) 매핑표만으로 추정한 값이다. `SC-028`(포털 홈)은 `PortalHomePage.tsx`가 `@design SCREEN-028`을 직접 태그해 확정. 근거: `04-screens-ia.md`(§4.3) · `reports/wiki-align-20260819/facts/F3-frontend-screens.md` §해석-6·표 L190.

## 16.0 전달 방식 — 포털 화면 안에서 런타임 실행 (임베딩, INT-013)

포털 채널은 저작도구를 **별도 사이트로 띄우지 않는다.** 포털이 Host 가 되어 저작도구 프론트엔드를
자기 화면 안에서 **런타임으로 실행**한다(모듈 페더레이션). 관제 채널은 종전대로 저작도구가 자기 화면을
직접 띄운다 — **채널별 별도 배포라는 축은 유지되고**(ADR-012), 달라지는 것은 포털 채널의 전달·인계 방식이다.

**포털이 값을 정해 제시했고 저작도구가 맞춘다** (2026-08-26 포털 회신):

| 항목 | 값 |
|---|---|
| 원격 모듈명 | `authoring` |
| 노출 모듈 | `./PortalApp` (Host 가 `authoring/PortalApp` 으로 가져간다) |
| 마운트 경로 | `/workspace/authoring` |
| 번들 서빙 경로 | `/label-remote/` |
| 진입 파일 캐시 | `remoteEntry.js` 에 재검증 강제(no-cache) |

- **토큰 인계는 이 채널만 다르다** — Host 가 주입한 인계 창구로 토큰을 얻어 `x-access-token` 헤더로
  싣고 access token 을 Host 메모리에만 둔다. 상세와 근거는 [03 인증·권한 §3.1](03-auth-roles.md).
- **스타일 격리 책임은 저작도구에 있다** — 같은 문서에 마운트되므로 저작도구 번들이 빌드타임 접두어
  또는 범위 한정 스타일 계층으로 자기 스타일을 격리해 Host 전역 스타일을 침범하지 않게 한다.
  포털 디자인 스타일이 정본이고, 포털 채널 산출물은 **포털 주색(코발트 계열)** 으로 뜬다 — 저작도구
  자체 팔레트와 다른 값이며 채널별 산출이라 공존한다.
- **저작도구의 화면 가드가 포털 셸의 인증 분기와 겹치는 것은 무해로 허용**됐다(포털 회신) — 서버가
  최종 인가를 판정하고 가드가 Host 를 이탈시키지 않기 때문이다.
- **서버간 API(완료 저작결과 조회·일일 저작집계)는 이 연동점 범위가 아니다** — 별도 트랙이다.

> **★임베딩 진입 = 포털 권한 사용자 — 단 화면 축만 (2026-08-27 사용자 확정, 구속)**
> 포털 채널 빌드는 포털 권한 기준으로 화면·메뉴·기능을 게이팅한다. 그러나 **서버 인가 판정의
> 진실원은 토큰 클레임(`channel`·`role`)이며 진입 경로가 권한을 올리지 않는다** — 진입 경로는
> 클라이언트가 주장하는 값이라 위조 가능하다. **이 경계를 지우고 서버까지 진입 경로로 고정하지 말 것.**

> ⚠ **코드 미반영 (2026-08-27 실측)** — Module Federation 설정 자체가 없다. `vite.config.ts`·
> `package.json` 에 `federation`·`exposes`·`remoteEntry` 가 **각 0건**이고, 토큰 인계도 아직 관제
> 방식(브라우저 저장소 직접 읽기 + `Authorization: Bearer`)만 있다. 설계 선반영 상태다.

## 16.1 데이터 소스

```
관제서버 → 데이터마트 → 포털 DB 적재 (관제서버 책임)
        ↓
포털 라벨 화면은 저작도구 DB(control)에서 Load
```

> ⚠ **구 서술 폐기(2026-08-16 코드 실측)** — *"저작도구는 포털 DB에서 Load (PortalDataSourceConfig
> 듀얼 데이터소스)"* 는 **사실과 다르다.** `PortalLabelService` 는 `controlTransactionManager` 로
> 묶이고 그것이 쓰는 리포지토리(`LsDataLblRepository`·`LsDataSrcRepository`·`LsPortalUserLabelRepository`·
> `LsRawDataStatusRepository`·`VideoRepository`)는 **전부 control(저작도구) 데이터소스**다.
> `@PortalRepo` 를 쓰는 것은 **메타 복제 축 하나뿐**이고(`PortalDatasetVideoMetaRepository`·
> `PortalMetaReplicaWriter`·`MetaReplicationWorker`) 그 방향은 **저작도구 → 포털 DB 쓰기**다(단방향
> at-least-once 복제). 즉 포털 DB 는 저작도구가 **읽는 곳이 아니라 내보내는 곳**이다. 그 서술대로
> 이해하면 포털 화면의 조회 경로를 엉뚱한 데이터소스에서 찾게 된다.

- 관제서버가 제공한 데이터마트를 포털에 등록 → 포털 사용자가 영상 선택 → 기존 저장 라벨/메타 Load → 라벨링 화면 표시

## 16.1a 메타 복제 배치 (control → 포털 outbox, 2026-08-19 코드 실측 보강)

위 16.1의 "저작도구 → 포털 DB 쓰기(단방향 at-least-once 복제)"를 실제로 수행하는 배치 잡이다.
포털 DB 는 control(저작도구) DB 의 **읽기 전용 사본**을 갖고, 그 사본을 채우는 유일한 경로가 이 잡이다.

**트리거 — outbox 패턴**: 검수 승인 시 `DatasetVideoMetaSnapshotService.materialize` 가 `LS_DATASET_VIDEO_META`
스냅샷을 만들면서 같은 트랜잭션에서 `LS_META_REPL_OUTBOX`(`LsMetaReplOutbox`) 에 PENDING 행을 적재한다.
이 outbox 행이 **self-contained PAYLOAD**(비식별 메타 JSON) 를 담아, 복제 시점에 control 원본 테이블을
되읽지 않는다(표준 outbox 패턴 — `MetaReplicationWorker` 클래스 javadoc).

**주기 발화**: `MetaReplicationQuartzJob`(JOB_GROUP=`dataset`)이 `MetaReplicationJobConfig` 로 등록되며
**60초 간격**(`authoring.meta-replication.interval-sec`, 기본 60)·부트 30초 뒤 최초 발화. 게이트는
`authoring.meta-replication.enabled`(`matchIfMissing=true` — 운영 dev/stg/prd 는 기본 활성, 끄면 잡 자체가
등록 안 됨, test/local 격리용). `@DisallowConcurrentExecution` + Quartz 클러스터링(`QRTZ_LOCKS`, 2노드
Active-Active 중 1노드만 발화)이 겹치므로 outbox 행 단위 원자 클레임은 두지 않는다.

**실행**: `MetaReplicationWorker.replicatePending()` 이 PENDING outbox 를 `regDt` 오름차순으로 배치 폴링
(`authoring.meta-replication.batch-size`, 기본 100)하고, 건별로 트랜잭션 경계를 셋으로 나눈다 —
① control readOnly 로 outbox 조회 → ② `PortalMetaReplicaWriter.replicate`(`portalTransactionManager`)로
포털에 upsert → ③ `MetaReplicationOutboxService.markDone`(`controlTransactionManager`)로 완료 표기.
control 과 포털은 물리 분리된 DB 라 XA 를 쓰지 않는다 — 이 셋을 하나로 묶지 않는 이유다.

**at-least-once + 멱등**: 포털 upsert 성공 후 DONE 표기가(크래시 등으로) 실패해도 outbox 는 PENDING 에
남아 **다음 tick 이 재복제**한다. 재복제는 무해하다 — `PortalMetaReplicaWriter.replicate` 가
`deactivatePrevious` → `upsertSnapshot`(`ON CONFLICT DO NOTHING`) → (0건이면) `activateByHash` 순으로
"활성 1건" 불변식을 유지하는 last-writer-wins 멱등 upsert 이기 때문. 순서 역전(옛 스냅샷이 최신 뒤에
재전달되는 것)은 워커가 아니라 **발행 측**이 막는다 — `materialize` 가 같은 rawSn 의 기존 PENDING outbox 를
advisory lock 구간에서 `SUPERSEDED` 로 먼저 정리한 뒤 신규 outbox 를 넣으므로, 워커는 항상 최신 건만 본다.

**graceful skip(미프로비저닝 대응)**: `PortalMetaReplicaWriter.isReplicaAvailable()` 이 포털
`LS_DATASET_VIDEO_META` 테이블 존재 여부를 포털 DataSource 에 직결한 별도 probe(트랜잭션 미경유)로 확인한다
— 테이블 부재(PostgreSQL `42P01`)는 WARN + 메트릭만 남기고 조용히 skip(재시도/dead-letter 폭주 방지),
그 외 실장애(연결 불가·권한 등)는 ERROR 로 승격한다. 복제 실패는 검수 승인 자체(이미 커밋됨)에 영향을
주지 않는다(관심 분리).

근거: `MetaReplicationQuartzJob` · `MetaReplicationJobConfig` · `MetaReplicationWorker`(class javadoc,
`replicatePending`) · `PortalMetaReplicaWriter`(class javadoc, `replicate`, `isReplicaAvailable`) ·
`DatasetVideoMetaSnapshotService`(`materialize`) · `LsMetaReplOutboxRepository`(`supersedePending`).

## 16.1b 복제 정합 축과 회귀 가드 (★2026-08-26 신설 — 복제 결손 실사고 반영)

**복제가 성립하려면 네 단이 같은 컬럼 집합을 실어야 하고, 그 위에 스키마 축이 하나 더 있다.**
한 단만 빠져도 그 컬럼은 복제본에서 **영구히 빈다** — 나머지가 전부 맞아도 그렇다.

| # | 단 | 지점 |
|---|---|---|
| ① | payload 직렬화 | `DatasetVideoMetaSnapshotService.toPayload` |
| ② | 전송 계약 | `MetaReplicationPayload`(record 컴포넌트) |
| ③ | 복원 빌더 | `MetaReplicationWorker.toSnapshot` |
| ④ | 포털 INSERT | `PortalDatasetVideoMetaRepository.upsertSnapshot` |
| ⑤ | **복제본 물리 DDL** | `db/portal/V*.sql` · `deploy/onprem/db/portal-schema.sql` |

**실사고(2026-08-26)**: control 이 `V128` 로 `EVNT_ANNO_CN` 을 추가했을 때 ⑤만 뒤늦게 `db/portal/V4` 로
따라붙고 **①~④가 전부 빠져 있었다.** 그 결과 포털 복제본의 그 컬럼이 **항상 NULL** 이었고, 복제본을
read-only 로 소비하는 포털 채널은 그 값을 받지 못했다. ⚠ `V4` 의 추가 사유가 *"복제하려고"* 가 아니라
*"같은 엔티티를 쓰는 JPA 파생 조회가 42703 으로 깨지지 않게"* 였다는 점이 이 결손의 실체다 —
**DDL 을 맞췄다는 것이 복제가 된다는 뜻은 아니다.**

**회귀 가드 3종** — 각 축은 서로를 덮지 못하므로 셋이 모두 필요하다.

| 축 | 가드 | 무엇을 잡나 |
|---|---|---|
| payload(①②③) | `PortalMetaReplicaPayloadParityGuardTest` | 엔티티 컬럼 ↔ 계약 컴포넌트 ↔ 복원 커버리지 |
| SQL(④) | `PortalMetaReplicaColumnParityGuardTest` | control ↔ 포털 INSERT 컬럼 집합 동일성 |
| DDL(⑤) | `PortalMetaReplicaDdlParityGuardTest` | 엔티티 컬럼이 복제본 DDL 에 실재하는가 |

**셋이 서로를 못 덮는다는 것은 변이로 실증됐다** — ①~④를 전부 맞추고 ⑤만 미반영하면
payload 축·SQL 축 가드가 **전건 통과**하고 DDL 축 가드만 실패한다(운영에서는 42703).

> ★ **통합시험은 ⑤의 드리프트를 원리적으로 잡지 못한다.** 시험 인프라가 control·포털 두 데이터소스를
> **같은 물리 테이블**로 묶어 돌리므로(`PostgresContainerContextCustomizerFactory`), 포털 전용 DDL 이
> 아예 적용되지 않은 채로도 전 IT 가 green 이다. 실제로 그 드리프트가 오래 살아남은 이유가 이것이다.
> 이 축은 런타임 시험이 아니라 **산출물 파일 대조 가드로만** 닫힌다.

> ★ **복제본 스키마의 정본은 둘이다.** `db/portal/V*.sql`(프로비저닝 DDL)과
> `deploy/onprem/db/portal-schema.sql`(설치가 실제 로드하는 생성물, `gen-schema-sql.sh` 산출). 원천만
> 늘리고 **덤프 재생성을 잊으면 실제로 설치되는 포털 DB 만 옛 형상으로 남는다.** 가드가 두 산출물의
> 컬럼 집합 동일성까지 고정한다 — `db/portal` 에 새 버전을 더하면 반드시 덤프를 재생성할 것.

**판정 방향은 비대칭이다** — 엔티티에 있는데 DDL 에 없으면 **실패**(런타임에 깨진다). DDL 에만 있는
컬럼은 실패로 보지 않는다(레거시·향후 컬럼일 수 있다). 다만 그런 항목은 로그로 드러낸다.

⚠ **가드가 닫지 못하는 것**: 이 가드들은 「저장소의 DDL 산출물이 엔티티와 맞는가」까지만 본다.
**운영 포털 DB 에 그 DDL 이 실제로 적용됐는지는 저장소가 알 수 없다** — 미적용 환경은 가드 green 인 채
런타임 42703 으로 깨진다(위 16.1a 의 graceful skip 은 *테이블 부재*만 덮고 *컬럼 부재*는 덮지 않는다).

근거: `PortalMetaReplicaPayloadParityGuardTest` · `PortalMetaReplicaColumnParityGuardTest` ·
`PortalMetaReplicaDdlParityGuardTest` · `DatasetVideoMetaSnapshotServiceIT`(검수승인시 동결·발신함 payload 단언) ·
`MetaReplicationPayload`(class javadoc) · `db/portal/V4__add_evnt_anno_cn.sql`(추가 사유 주석).

## 16.2 저장 정책 (단방향)

- **저장 시 원본·데이터마트 미수정** — 사용자별 작업 데이터로 `LS_PORTAL_USER_LABEL`에 **별도 적재**
- 데이터마트에 정합/반영 안 됨 (단방향)
- 다운로드는 사용자 작업 데이터 기준 — **보존기간 안에서만**(2026-08-17, 아래 16.4a·16.6 참조)
- 기여도 점수 없음

> ⚠ **구 정책 폐기(2026-08-17, 사용자 확정)** — *"포털 데이터마트 다운로드는 포털 자체 책임 · 다운로드
> 기간 제한 미해소"*(구 V1.5)는 **뒤집혔다**. 저작도구가 데이터마트 작업 데이터 ZIP 다운로드를
> 직접 구현하고, "본인 데이터 기간 내" 제약은 **보존기간 만료 자동 삭제**로 실현된다 — 보존기간이
> 지나면 다운로드할 데이터 자체가 사라진다(별도의 다운로드 시점 검증 로직이 아니다). 상세는 16.4a·16.6.

## 16.3 포털 범위 (ADR-013)

| 기능 | 제공 |
|------|:----:|
| 데이터마트 영상 선택 | ✓ |
| 기존 라벨 확인·수정·저장 (BBOX/POLYGON 수동) | ✓ |
| 본인 데이터 다운로드 | ✓ — 업로드 자산 원본/export(F-7) + **데이터마트 작업 데이터 ZIP**(2026-08-17 신설, 16.4a) |
| **SAM2 인터랙티브 분할·자동추적** | ✗ (2026-08-02 제거 — ADR-013 정합) |
| **키포인트(SKELETON) 도구** | ✗ (2026-08-02 제거 — ADR-013 정합) |
| YOLO 파이프라인 오토라벨 | ✗ |
| **트랙 rename/머지 (Phase 10)** | ✗ (채널 인가 403 + 화면 이중 가드 — 아래 「Phase 10(축소)」 절. ⚠ 구 근거 「트랙 데이터모델 부재」는 **폐기**, 결론은 유지) |
| 외부 시계열 분석 서버 위탁 연동(호출·콜백) | ✗ |
| **시계열 메타·촬영환경·프레임 설명·개인정보 판정 표시·수정** | **○** (2026-08-26 확정 — 화면 미구현) |
| **이벤트 어노테이션 표시·수정** | **○** (2026-08-26 확정 — 화면 미구현) |
| **업로드 영상 이벤트구간 마킹** | **○** (2026-08-26 확정 — **업로드 자산 한정**. 자리: 업로드 라벨링 화면 `SC-034` 안. 데이터마트 로드분은 대상 아님) |
| **업로드 영상 AI 증강 연동 요청** | **○** (2026-08-26 확정 — **업로드 자산 한정**. 자리: 업로드 목록 `SC-033` 의 자산별 액션. 서버가 외부로 보내는 **비동기 위탁**이라 포털 사용자가 추론 엔드포인트를 직접 호출하지 않는다) |
| 버전관리·검수 | ✗ |
| 업로드 | ✗ (단, 포털 **본인 자산** 업로드는 별도 경로 — 아래 참조) |

> **★두 경로를 섞지 말 것** — 위 표에서 「업로드 영상」이 붙은 두 행은 **경로 B**(포털 사용자가 직접 올린 본인 자산)에만 해당하고,
> **경로 A**(데이터마트 로드분)에는 해당하지 않는다. 반대로 메타·이벤트 어노테이션 표시·수정은 경로 A 축이다.
> 마킹은 화면 안의 모드라 메뉴 노드를 늘리지 않고, 증강 요청은 목록의 자산별 액션이라 상태 배지·삭제와 같은 자리다.
> ⚠ **증강 요청 계약·파생물 적재 위치는 아직 정해지지 않았다** — 그래서 흐름 시퀀스([16.1a] 아님, `SEQ-019`)는 이 둘을 그리지 않고 범위 밖으로 밝혀 둔다.

> **★ 포털 SAM2 제거 (2026-08-02 확정, 구속)**: 구 "Phase 9 (ADR-013 override)"로 포털에 열려 있던
> SAM2 인터랙티브 분할·자동추적·키포인트를 **전면 제거**했다. ADR-013 정본이 "포털은 오토라벨링·SAM2·
> VLM·버전관리·검수 미제공"을 명시하는데 구현만 override 상태로 남아 정책과 코드가 어긋나 있었다.
> - **BE**: `PortalSam2Controller`·`PortalSam2Service` 삭제 → `POST /v1/portal/frames/{srcSn}/sam2-segment`·
>   `sam2-track` 은 **404**(핸들러 부재). `portalSam2` Bulkhead 빈·RateLimiter config 도 함께 제거.
> - **FE**: `PORTAL_HIDDEN_TOOLS = [SAM_SEGMENT, TRACK, KEYPOINT]` — 도구바 버튼·키보드 단축키
>   (G / Shift+T / K)·단축키 도움말이 모두 이 단일 소스로 게이팅된다. FE 게이팅은 **UX 편의이며
>   신뢰 경계가 아니다** — devtools 로 채널 상태를 조작해도 서버에 엔드포인트가 없어 무의미하다.
> - **키포인트(SKELETON)는 서버 기능까지 제거 (2026-08-03 보정)**: 위 FE 게이팅만으로는 `lblTypeCd='SKELETON'`
>   저장·조회 round-trip 이 서버에 그대로 살아 있었고(도구만 숨겨진 상태), 애초에 `lblTypeCd` **allowlist 가
>   없어** 16자 이하 임의 문자열이 그대로 `LBL_TYPE_CD` 에 적재됐다. 이제 `POST /v1/portal/user-labels` 는
>   **BBOX\|POLYGON 만** 허용하고(그 외 400) 조회 경로는 삼중값 SKELETON 을 파싱 실패로 스킵한다
>   (레거시 적재 row 도 예외 없이 무시 — 삭제 마이그레이션은 별건). 회귀 가드: `PortalKeypointRemovedTest`.
> - **FE 죽은 코드 정리 (2026-08-03)**: 삭제된 포털 SAM2 경로를 향하던 `portalMode` 분기
>   (`requestSam2Segment`/`requestSam2Track`/`sam2TrackAllChunks`/`useSam2Segment`/`useSam2Track`/
>   `Sam2TrackTool`/`CanvasShell`)를 제거해 내부 경로만 호출하도록 단순화했다(도달 불가 코드 + 현재
>   정책과 반대되는 주석 잔존 제거). 포털 라벨 저장 직렬화도 BBOX/POLYGON 외 형태를 전송 대상에서 제외한다.
> - **내부(INTERNAL) 채널은 무변경** — SAM2 분할/추적은 SFR-08-01(VOS) 핵심 기능이라 그대로 제공한다
>   (`/v1/frames/{id}/sam2-*`, PORTAL 토큰은 채널 격리로 403).
> - 회귀 가드: BE `PortalSam2RemovedTest`(404 + 핸들러 매핑 0건 + 내부 매핑 잔존), FE
>   `ToolBar.test.tsx`(구 `DarkToolbar.test.tsx`)·`useLabelingShortcuts.test.tsx`·`ShortcutCheatSheet.test.tsx`·
>   `LabelingPagePortalRestrictions.test.tsx`.

> **Phase 10(축소) — 포털 트랙 rename/머지 미제공**: **포털에서 트랙 번호 변경(연필) UI 를 숨긴다.**
> 이 결론을 받치는 근거는 **채널 인가 + 화면 이중 가드**다 — 내부 전용 `mergeTracks`
> (`POST /v1/videos/{rawSn}/tracks/merge`, `@PreAuthorize(REVIEWER,WORKER)` + `/v1/**`
> `CHANNEL_INTERNAL` 매처)는 PORTAL 채널이 **403** 이라 호출 자체가 성립하지 않고, 화면은 목록 패널의
> 버튼을 숨긴 뒤(`ObjectClassTree.tsx(portalMode)`) 콜백에서도 조기 return 한다
> (`LabelingPage.tsx(handleRenameTrack)` — 진입점이 늘어도 새지 않게). **이 축은 포털 저장소의 컬럼
> 유무와 무관하다.** `ADR-013` v10 개정에서도 트랙 rename/merge 는 **미제공 유지 대상**으로 명시됐다.
> 내부(REVIEWER/WORKER) rename/머지는 무변경.
>
> ⚠ **구 근거 2건 폐기(2026-08-26, CO-021) — 결론은 유지한다.** 이 절이 미제공 근거로 들던
> *"`LS_PORTAL_USER_LABEL` 에 trackId 컬럼 부재"* 와 *"`/v1/portal/frames/{srcSn}/labels` 로더가
> trackId 를 null 로 스트리핑"* 은 **더 이상 사실이 아니다.** 산출 구조 통일로 포털 저장소에
> `TRCK_ID`(와 `LBL_ID`)가 생겼고(`LsPortalUserLabel.java`), 로더 응답도 두 값을 실어 보낸다
> (`PortalFrameLabelsResponse.java`). ★**그 컬럼은 「편집」이 아니라 「보존」을 위한 것이다** —
> 데이터마트 원본에서 불러온 라벨을 포털 사용자가 저장할 때 원본이 갖고 있던 마스터·트랙 연결이
> **저장 왕복에서 끊기지 않게** 하고, 산출 어노테이션 문서의 `category_id`·`track_id` 를 채우기 위한
> 축이다. 화면은 그 값을 **표시하지 않고 그대로 되돌려 보낼 뿐**이다. **저장소에 값이 있다는 것과 그
> 값을 편집할 수 있다는 것은 다른 축이므로, 근거가 무너졌다고 결론을 뒤집지 말 것.**
> 남은 구 근거 *"SAM2 자동추적의 trackId 는 FE 세션 opaque 로 미저장"* 은 **그대로 유효**하다 —
> 포털에서 SAM2 자동추적 자체가 제거돼(위 절) 그 경로로 트랙이 생기지 않는다.

## 16.4 포털 라벨링 API (PORTAL_USER 전용 — PORTAL 채널 토큰만, R16)

> 채널 격리: `/v1/portal/**` 는 `ROLE_PORTAL_USER` + `CHANNEL_PORTAL` 동시 충족만 허용. 내부 전용
> API(`/v1/frames/**`)는 `CHANNEL_INTERNAL` 강제이므로 포털 토큰은 403 — 포털 라벨링은 아래 포털 전용
> 엔드포인트만 사용한다.

| Method · URL | 설명 | 응답 |
|---|---|---|
| `GET /v1/portal/datamart/videos?page=&size=` | **포털 홈 영상 목록**. 데이터마트 노출(검수 완료=APPROVED) 영상만 페이징. 응답 1행: `rawSn`/`title`(=VMS_CLIP_ID)/`eventName`/`frameCount`/`firstSrcSn`(라벨링 진입용 첫 프레임)/`approvedAt`. **프레임 0건 영상은 진입 불가하므로 제외**, 미승인 영상은 쿼리 게이트(`findAllWithReviewStatus(null, APPROVED, …)`)로 미포함 | 200(Page) / 403(INTERNAL 채널) / 401(토큰 미상) |
| `GET /v1/portal/frames/{srcSn}/labels` | 프레임 단위 라벨 Load. datamart 원본 + 본인 user-label 병합(본인 작업분 우선). `videoId`/`siblings`/`labels` 포함 | 200 / 404(프레임 없음) |
| `GET /v1/portal/frames/{srcSn}/image` | 프레임 **비식별** 이미지 바이너리. 데이터마트 노출(검수 완료=APPROVED) 영상만 | 200(image/*) / 403(미승인 영상) / 404(프레임·비식별파일 없음) |
| `GET /v1/portal/datamart/labels?rawSn=` | 데이터마트 원본 라벨 Load (페이징). 프레임 라벨 Load 와 **동일 게이트** — 데이터마트 노출(검수 완료=APPROVED) 영상만, 비식별 누락 신고 구간은 차단. **미존재 rawSn 도 403**(존재 여부 오라클 차단) | 200 / 400(rawSn 누락·형식 오류) / 401(토큰 미상) / 403(미승인·미존재 영상) / 412(비식별 신고 구간) |
| `GET /v1/portal/user-labels?rawSn=` | 본인 작업 라벨 조회 (IDOR — 본인만). 프레임/데이터마트 라벨 Load 와 **동일 게이트**(APPROVED + 비식별 신고 구간 차단) — 본인이 저장한 사본이라도 좌표는 원본과 같은 PII 위치정보라 한 경로만 열어두면 같은 데이터가 다른 URL 로 새어나간다 | 200 / 400(rawSn 누락) / 401(토큰 미상) / 403(미승인·미존재 영상) / 412(비식별 신고 구간) |
| `POST /v1/portal/user-labels` | 본인 작업 라벨 단건 저장. 원본 미수정 — `LS_PORTAL_USER_LABEL` 적재. 응답에 `points` 포함. **`lblTypeCd` allowlist = `BBOX`\|`POLYGON` 만**(그 외 400, fail-closed — 2026-08-03). 조회 경로와 **동일 게이트**(APPROVED 403 + 비식별 신고 구간 412). **좌표 개수 상한**: BBOX 정확히 2점 / POLYGON 3~200점(형제 `PortalUploadLabelService` 상수 재사용). **본문 크기 2층 방어**: ①`PortalLabelBodySizeFilter` 가 파싱 전에 `portal.upload.max-label-body-bytes`(기본 2MB) 초과 413 · `Content-Length` 부재(chunked) 411 ②`points` 문자열 길이 65,536자 상한(@Valid 400). **per-user 속도 제한**(`portalUserLabel` config, 300회/분) 초과 시 429 | 201 / 400(허용외 lblTypeCd·빈 좌표·좌표 개수 위반·points 길이 초과) / 403(미승인 영상) / 411(chunked) / 412(비식별 신고 구간) / 413(본문 초과) / 429(요청량 초과) |
| ~~`POST /v1/portal/frames/{srcSn}/sam2-segment`~~ | **제거됨 (2026-08-02)** — ADR-013 정합. 핸들러 부재 | 404 |
| ~~`POST /v1/portal/frames/{srcSn}/sam2-track`~~ | **제거됨 (2026-08-02)** — ADR-013 정합. 핸들러 부재 | 404 |

보안 가드: 프레임 이미지/라벨 모두 본인(`portalUserNo`=token sub)·검수 완료 영상으로 한정(CWE-639),
경로 순회 차단(CWE-22, `FrameImageService.resolveSafe` 재사용), 비식별본 고정(원본 폴백 금지).
파라미터 누락/형식 오류는 400(GlobalExceptionHandler — `MissingServletRequestParameter`/`MethodArgumentTypeMismatch`).

## 16.4a 데이터마트 작업 데이터 ZIP 다운로드 (2026-08-17 신설, API-203)

> ★확정(2026-08-17, 사용자 확정) — 구 V1.5 "포털 다운로드는 포털 자체 책임" 정책 폐기.
> `PortalDatamartDownloadController`(`download`) → `PortalDatamartDownloadTxService`(`plan`,
> DB 단계) → `PortalDatamartDownloadService`(`download`, 파일·ZIP 스트리밍 단계) 3빈 구성이며,
> **DB 트랜잭션과 파일 I/O 를 빈 자체로 분리**한다(영상이 포함되면 응답이 GB 급이라 커넥션을 쥔 채
> NAS I/O 를 하면 커넥션 기아가 난다 — 프레임 이미지 서빙과 동일 규약).

| Method · URL | 설명 | 응답 |
|---|---|---|
| `GET /v1/portal/datamart/videos/{rawSn}/download` | **작업 데이터 ZIP 다운로드**. 본인 저장 라벨 기준으로 **프레임마다** `{rawSn}/frames/{FRM_NO 4자리 zero-pad}.json`(그 프레임의 어노테이션 문서 — **NIA COCO 확장 최상위 9키** `info`·`dataset`·`licences`·`video`·`event`·`image`·`annotations`·`categories`·`type`) 과 `{rawSn}/frames/{FRM_NO 4자리 zero-pad}.jpg`(비식별 프레임 이미지) 를 **같은 자리에 이름만 다른 짝**으로 담고, `{rawSn}/video.{ext}`(비식별 영상, **있을 때만**) 를 더해 ZIP 하나로 스트리밍. 좌표는 `annotations` 항목의 `bbox`·`polygon`·`keypoints` 로 표현한다. **폐기 프레임과 비식별 이미지가 없는 프레임은 담기지 않는다**(검수 승인 학습데이터 산출물과 같은 규칙). **원본(비식별 이전) 영상·이미지는 어떤 경우에도 담기지 않는다**(원본 폴백 금지). 다른 사용자가 저장한 라벨은 포함되지 않는다(본인만 — 병합 판정은 **프레임 단위**). `Cache-Control: no-store` | 200(ZIP) / 401(토큰 미상) / 403(데이터마트 미노출 — 미승인·미존재 rawSn 동일 처리, 존재 여부 오라클 차단) / 410(본인 저장 라벨 0건 — 신규 미작업 또는 보존기간 만료 삭제) / 412(비식별 누락 신고 구간) / 429(요청량 초과) |

> ⚠ **구 서술 폐기(2026-08-26, CO-021)** — *"`{rawSn}/labels.json`(프레임별 라벨, 본인 저장분 우선)
> 한 개"* 는 **더 이상 사실이 아니다.** 영상 단위로 라벨을 한 문서에 모으고 좌표를 `points[[x,y]]` 로
> 싣던 자체 형태는 **두지 않는다** — 두 형태가 병존하면 같은 라벨의 좌표 표현이 갈리고(이 저장소가
> 반복해 겪은 「두 번째 진실원」), export JSON 이 **다시 읽혀 적재되는 왕복 자산**이라는 확정 근거를
> 포털 산출물만 스키마가 달라 충족하지 못했다. 이제 포털 ZIP 의 라벨 산출은 검수 승인 학습데이터와
> **같은 구조**라 그 재적재 경로에 그대로 들어간다. 파일명 규칙도 승인 산출물과 **같은 단일 지점**을
> 쓰며 포털 전용 규칙을 새로 만들지 않는다.
> 근거: `PortalDatamartDownloadService.java(resolveFrameEntries)` ·
> `PortalDatamartDownloadTxService.java(plan)` · `PortalNiaDocumentFactory.java(build)` ·
> `NiaAnnotationDoc.java` · `ExportFileNaming.java(jsonFileName, imageFileName)`.
>
> **이번 통일의 대상이 아닌 것(그대로 유지 — 되돌리지 말 것)**: ZIP 최상위 `{rawSn}/` 접두 ·
> 서버 생성 고정 파일명 · 프레임 이미지 `{rawSn}/frames/{FRM_NO}.jpg` · 비식별 영상 `{rawSn}/video.{ext}` ·
> 아래 게이트 3종 순서 · 프레임 단위 병합 규칙 · 이미지는 비식별벌만.

**판정 순서**(오라클 누출 방지 — `PortalDatamartDownloadTxService.plan` 이 고정 순서로 평가):
①인증(PORTAL_USER) → ②속도 제한(429, per-user 분당 3회 — `portalDatamartDownload` RateLimiter config,
형제 제한기와 동일하게 **노드별 in-memory** 라 2노드 Active-Active 배포에서 실질 한도는 2배) →
③데이터마트 노출(403) → ④비식별 누락 신고(412) → ⑤본인 저장 라벨 0건(410) → ⑥200 ZIP.
**③이 ④보다 먼저다** — 뒤집으면 데이터마트에 노출되지도 않은 영상의 신고 상태가 응답으로 새어나간다(CWE-209).

경로 판정기는 축마다 다르다 — 프레임 이미지는 `StorageSubtreePolicy.verifyDeidentifiedFile`
(`frames/deid/**`·`videos/**` 서브트리), 비식별 영상은 `VideoArtifactRootResolver.resolveRealPathUnder`
+ `readableDeidVideoBases`(co-locate 위치 포함). 어느 축이든 판정이 돌려준 **실경로**로만 열고
(`FrameImageService.openNoFollow`, `NOFOLLOW_LINKS`), 검증 실패 파일은 사유 코드만 로그로 남기고
조용히 빠진다(전건 거부 아님).

> ✅ **화면 배선 완료(2026-08-18).** 포털 홈(SCREEN-028) 영상 카드에 다운로드 버튼이 붙었다 —
> 아래 16.5 참조. 이 API 는 인증을 요구하므로 `<a href>` 직링크가 성립하지 않는다(401). 화면은
> `apiClient` 로 응답을 받아 브라우저 다운로드를 트리거한다(형제 경로인 포털 업로드 자산 다운로드와
> 동일 규약 — 공용 헬퍼 `lib/api/download.ts`).
>
> ⚠ **구 서술 폐기** — *"화면 배선은 이번 범위 밖 … 다운로드 버튼은 아직 없다"* 는 더 이상 사실이
> 아니다. 함께 폐기된 것: V1.5 "포털 다운로드는 포털 시스템 자체 책임" 정책을 고정하던 FE 회귀 가드
> 2건(`PortalHomePage.test.tsx`·`PortalLayout.test.tsx`) — **지우지 않고 반대 단언으로 뒤집었다**.

## 16.5 UI 특성

- 반응형 웹 (PC/태블릿/모바일), **WCAG 2.1 AA 준수** (NFR-006)
- PortalLayout (모바일 친화, LNB 없음)
- 포털 라벨링 화면은 LabelingPage 재사용 + `portalMode` 분기: 데이터 경로(라벨/이미지/저장)는 포털 전용 API.
  라벨 저장은 포털 user-label(`LS_PORTAL_USER_LABEL`) 단방향(내부 `/v1/frames/...` 미호출).
  **SAM2 분할/자동추적·키포인트 도구는 미노출**(2026-08-02 제거 — 도구바 버튼·단축키 G/Shift+T/K·
  단축키 도움말 모두 `PORTAL_HIDDEN_TOOLS` 단일 소스로 게이팅). YOLO 오토라벨·검수제출·히스토리·
  비식별 신고도 계속 미노출 (ADR-013). ⚠ **시계열 메타·촬영환경·프레임 설명·개인정보 판정·이벤트 어노테이션 패널이 미노출인 것은 근거가 다르다** — `ADR-013` v10 이 **제공으로 확정**했고 아직 만들지 않았을 뿐이다(포털 메타 API 0건). 그 미노출을 정책 근거로 인용하지 말 것. **Phase 10** — 트랙 rename/머지(연필 버튼)도
  미노출(채널 인가 403 + 화면 이중 가드 — §16.3 「Phase 10(축소)」 절. ⚠ 구 근거 「트랙 데이터모델
  부재」는 **폐기** — 포털 저장소에 `TRCK_ID` 가 생겼다. 그 컬럼은 편집이 아니라 **보존** 축이라
  결론은 유지)
- **포털 홈(PortalHomePage)**: `GET /v1/portal/datamart/videos` 로 데이터마트 노출 영상을 카드 목록(반응형 1~2열)으로
  렌더. 카드/"시작하기" 선택 시 `/portal/label/{firstSrcSn}` 으로 진입. 영상 0건이면 빈 상태 + "시작하기" `aria-disabled`
  (native disabled 미사용 — WCAG 2.1.1 키보드 포커스 순서 유지). 카드는 `<button>` 시맨틱으로 키보드 접근 가능
- **포털 홈 — 만료 예정일 + 작업 데이터 다운로드**(2026-08-18 신설, SCREEN-028): 카드에 본인 저장 라벨의
  만료 예정일을 `만료: yyyy-MM-dd` 로 **날짜까지만** 병기하고, 그 옆에 작업 데이터 다운로드 버튼을 둔다.
  - **다운로드 버튼은 카드 클릭 영역 *밖*에 둔다** — 안에 넣으면 ①버튼 안의 버튼이라 마크업이 성립하지
    않고 ②클릭이 위로 전파돼 내려받으려던 사용자가 라벨링 화면으로 끌려간다. 회귀 가드는 «클릭해도 안
    넘어간다»(동작)와 «카드의 자손이 아니다»(구조)를 **함께** 단언한다 — 동작만 보면 중첩으로 되돌려도
    통과할 수 있다.
  - **만료 값이 없으면** 표기는 **자리를 비운다**. `-`·`없음` 같은 문구를 지어내면 "만료가 정해졌는데
    표기만 빈 것"으로 읽힌다.
  - ★**만료 표기와 다운로드 가부는 근거가 다르다 — 만료 부재를 «저장 라벨 없음» 으로 단정하지 않는다**
    (2026-08-18 반전, 구 동작 «만료 없으면 비활성 + 사유 툴팁» 폐기). 서버가 만료를 비우는 이유는 **둘**
    이다(`PortalRetentionPolicy`): ①본인 저장 라벨이 없다 ②보존기간 설정이 없거나 비정상값이라 만료를
    **판정할 수 없다**. ②는 저장 라벨이 멀쩡히 있는 상태이고 서버도 정상 응답을 주므로, 구 동작은
    **정상 다운로드를 화면이 먼저 막고 거짓 사유까지 대는 것**이었다. 저장 라벨 유무를 알려주는 필드는
    목록 응답(`DatamartVideoResponse`)에 **없고** 화면이 추정할 근거도 없다 — 반면 서버는 저장 라벨 0건을
    **410 으로 구분해** 돌려주므로 막지 않고 그 판정을 그대로 안내한다. 비활성은 **동시 실행 방지**라는,
    화면이 실제로 아는 사실에만 쓴다.
  - **만료 임박 강조는 두지 않는다**(사양에 없는 것을 더하지 않는다).
  - ★**요청 제한시간은 공용 기본값(30초)을 쓰지 않는다**(2026-08-18). 이 응답은 비식별 영상이 포함되면
    GB 급이고 서버가 그래서 스트리밍으로 내려보내는데(`PortalDatamartDownloadService`), 브라우저 XHR 의
    `timeout` 은 **응답 완료까지의 총 경과 시간**이라 전송 시간이 그대로 잡힌다 — 30초로는 **구조적으로
    끊기고**, 끊긴 시점엔 서버가 이미 전량을 흘려보낸 뒤라 재시도할수록 같은 전송을 반복하고 버린다.
    전용 값은 `features/portal/api.ts(DATAMART_DOWNLOAD_TIMEOUT_MS)` = **30분**(실효 5Mbps 로 약 1.1GB).
    **무제한(0)은 쓰지 않는다** — 연결이 조용히 멈추면 버튼이 '내려받는 중…' 에 영구히 갇힌다. 유한한
    상한이 그 상태를 끝내 주는 최후 장치다. ⚠ **구 근거 폐기(2026-08-18) — "이 화면에는 취소 수단이
    없어(사양에 취소·진행률 UI 없음)"** 는 더 이상 사실이 아니다(아래 「다운로드 취소」 참조, 취소
    조작이 생겼다). **그렇다고 상한을 걷어내지 않는다** — 취소는 **사용자가 화면을 보고 있을 때만**
    동작하는 수동 장치라, 자리를 비운 사이 멈춘 전송을 끝내 주지는 못한다. 두 장치는 서로를 대체하지
    않는다.
  - ★**클라이언트 제한시간의 짝은 서버 쪽 비동기 응답 절대 제한시간이다**(2026-08-18) — 이 응답은
    `StreamingResponseBody` 로 내려가는데, `spring.mvc.async.request-timeout` 설정이 **어느
    프로파일에도 없으면 컨테이너 기본값 30초**가 적용된다. 이 값은 "다음 쓰기까지의 공백"이 아니라
    **비동기 처리 시작 이후의 절대 경과시간**이라 서버가 쉬지 않고 데이터를 쓰고 있어도 **리셋되지
    않는다**(격리 재현: 2초 간격 20청크를 연속으로 쓰는 중 t=30.1s 에 강제 종료 — 클라이언트는 16개만
    수신). 그래서 이 응답은 화면 쪽 제한시간을 아무리 늘려도 **사실상 전부 30초에 잘리고 있었다.**
    해소는 백엔드 공통 `application.yml`(전 프로파일 공통, 프로파일별 파일에서 덮어쓰지 않음)에
    `spring.mvc.async.request-timeout: 1800000`(30분 — 화면 쪽 상한과 근거·값이 같다)을 명시하는
    것이었다. 회귀 고정은 `AsyncRequestTimeoutConfigGuardTest`. **경로 중 가장 작은 상한이 실제
    상한**이라는 성질은 이 축에도 그대로 적용된다 — 앞단(프록시·게이트웨이)에 더 짧은 상한이 있으면
    이 값과 무관하게 거기서 끊긴다(그 정렬은 별도 과제).
  - **진행 표시는 버튼 하나로 끝낸다** — 누른 버튼이 '내려받는 중…' + `aria-busy` 가 되고 그 사이 다른
    영상 버튼도 잠긴다. 버튼 이름을 `aria-label` 이 정해 **바뀐 본문이 보조기술에 읽히지 않으므로**
    `aria-busy` 로 진행 사실을 전달한다. 별도 진행률 UI 는 사양에 없어 두지 않는다.
  - **실패 안내는 사유별로 갈린다** — 요청량 초과(429) / 비식별 재처리 대기(412) / 저장 라벨 없음(410) /
    접근 권한 없음(403) / **전송 중단**(상태코드 없음). 판정 단일 지점은
    `features/portal/downloadError.ts(datamartDownloadErrorMessage)` 이며 **상태코드**로 가른다: 이 요청은
    `responseType: 'blob'` 이라 실패 본문이 표준 응답 형태로 해석되지 않고, 그러면 클라이언트가 상태코드에서
    코드를 추론하는데 그 추론에 410·429 가 없어 **두 사유가 통째로 뭉개진다**. 마지막 하나(중단·네트워크
    단절·제한시간 초과)는 응답이 아예 없어 `status=0` 으로 올라오는데, 일반 실패로 두면 «잠시 후 다시 시도»
    가 뜬다 — **기다려도 달라지지 않는 상황에 재시도를 권하고** 그 재시도마다 GB 급 전송을 다시 유발한다.
    ⚠ axios 실패 코드(`ECONNABORTED` 등)로 더 잘게 가르지 않는다 — 공용 클라이언트가 `ApiError` 로 감싸며
    그 코드를 남기지 않아 남는 근거가 버전마다 달라지는 영문 메시지뿐이다. 서버 원문 메시지는 노출하지
    않는다(CWE-209).
  - ★**다운로드 취소**(2026-08-18 신설) — 진행 중인 버튼 옆에 취소 조작이 나타나고(진행 중이 아닐 때는
    없음 — 멈출 것이 없는데 떠 있으면 무엇을 멈추는지 알 수 없다), 누르면 `AbortController` 신호가
    실제 요청에 실려 전송을 중단시킨다(`downloadDatamartVideoData(rawSn, signal)`). **취소 버튼도 카드
    클릭 영역 밖**에 둔다(멈추려던 사용자가 화면을 떠나면 안 된다 — 위 다운로드 버튼과 동일 규칙).
    ★★**사용자 취소는 오류가 아니라 정상 종료다** — 취소하면 응답이 오지 않아 위 「전송 중단」과 같은
    `status=0` `ApiError` 로 올라오는데, 판정 단일 지점(`onDownload` 의 `controller.signal.aborted`)이
    **그보다 앞에서** 사용자 취소만 갈라내 **오류 안내를 띄우지 않는다**(안 가르면 스스로 멈춘
    사용자에게 «전송이 끊겼습니다 … 다시 시도»라는 거짓 안내가 뜬다). 판정 근거는 **오류 객체가
    아니라 화면이 쥔 중단 신호**다 — 공용 클라이언트가 `ApiError` 로 감싸며 취소 표식을 남기지 않아
    오류만으로는 취소와 회선 단절이 구분되지 않는다. ⚠ **취소하지 않은 전송 중단은 여전히 안내한다**
    (통합을 뒤집지 않는다 — 그렇지 않으면 진짜 장애가 아무 표시 없이 사라진다). 취소 후에는 버튼이
    다시 받을 수 있는 상태로 돌아온다.
- **포털 업로드 라벨링(PortalUploadLabelingPage, SCREEN-034) — 원본 파일 다운로드 제한시간·취소**
  (2026-08-18 신설): 원본 파일(최대 5GB)은 형제 경로(데이터마트 묶음)와 **같은 결함**을 겪고 있었다 —
  공용 기본 제한시간(30초)을 그대로 써 대용량이 구조적으로 끊겼다. 전용 상수
  `features/portal/uploads/api.ts(UPLOAD_FILE_DOWNLOAD_TIMEOUT_MS)` = **3시간**(5GB 를 실효 5Mbps 로
  받는 약 137분에 여유를 둔 값 — 상한이 데이터마트 묶음(30분·약 1.1GB)보다 크므로 더 짧을 수 없다)을
  덮어쓴다. **취소도 이 경로에 둔다**(`downloadUploadFile(uldSn, fallbackName, signal)`) — 진행 중에만
  «원본 다운로드 취소» 가 나타나고, 취소는 오류가 아니라 정상 종료라 실패 토스트를 띄우지 않으며
  (판정은 위와 동일하게 `controller.signal.aborted`), 취소 후 원본 다운로드·내보내기(JSON) 두 버튼이
  함께 다시 활성화된다. **라벨 내보내기(JSON)는 대상이 아니다** — 작아서 공용 기본값 안에 끝나고
  취소 버튼이 뜨기도 전에 완료된다(전용 제한시간·취소 둘 다 두지 않는 것이 사양).
- **포털 업로드 목록(PortalUploadPage)**: 각 자산에 만료 예정일을 **날짜까지만** 병기한다(SCREEN-033).
  처리 전·처리 중 자산은 삭제 대상이 아니라 만료가 **없으므로** 표기를 통째로 생략한다(홈과 동일 규칙).
  두 화면의 표기 판정은 `features/portal/expiry.ts(formatExpiryDate)` 한 곳이며, 시간대 변환을 하지
  않는다 — 서버 값은 오프셋 없는 로컬 일시라 `Date` 로 파싱하면 실행 환경에 따라 하루가 밀린다.

> **알려진 한계(인지·수용, 2026-08-18)**: 취소는 **클라이언트 중단**이다 — 서버가 이미 읽어 흘려보내기
> 시작한 응답의 서버측 처리를 되돌리지는 않는다. 또 **화면을 떠나는 것은 취소가 아니다** — SPA 라우팅으로
> 다른 경로로 이동해도 진행 중이던 요청은 살아 있고, 완료되면 브라우저 다운로드가 그대로 트리거된다
> («받던 것이 끝까지 받아진다»는 뜻이라 의도적으로 유지한다. 되돌리지 말 것).

## 16.6 보존기간 · 자동 삭제 (2026-08-17 신설, DFEAT-055)

> ★확정(2026-08-17, 사용자 확정) — "본인 데이터 기간 내" 제약은 **다운로드 시점 검증이 아니라
> 보존기간 만료 후 데이터 자체를 지우는 방식**으로 구현됐다. 판정 단일 지점은
> `PortalRetentionPolicy`, 삭제 배치는 `PortalRetentionSweepJob`(오케스트레이션) +
> `PortalRetentionSweepTxService`(트랜잭션 경계) 2빈 구성(자기호출로 인한 `@Scheduled` 프록시 우회
> 방지 — `PortalUploadSweepJob`과 동일 패턴).

### 축과 기준점

| 축 | 대상 | 기준점 | 설정 키 | 기본값 |
|---|---|---|---|---|
| 데이터마트 라벨 | 포털 사용자가 데이터마트 영상에 저장한 라벨(`LS_PORTAL_USER_LABEL`) | 그 (사용자, 영상) 저장 라벨의 `MAX(REG_DT)` | `portal.datamart.retention-days` | 7일 |
| 업로드 자산 — READY | 정상 처리 완료된 본인 업로드 자산 | 등록일과 그 자산 라벨 최종 저장일 중 **늦은 쪽**(재작업 시 기준점이 밀린다) | `portal.upload.retention-days` | 7일 |
| 업로드 자산 — FAILED | 처리 실패한 본인 업로드 자산 | FAILED 전이 시각(`MDFCN_DT`) | `portal.upload.failed-retention-days` | 1일 |
| 업로드 자산 — PROCESSING·UPLOADED | 처리 중인 자산 | — | — | **만료 없음**(삭제 후보 쿼리 자체가 상태 리터럴로 스코프돼 구조적으로 후보가 될 수 없다) |

- **하한 1, 상한 3650**(`ConfigKeys.NUMBER_RANGE`) — 0/음수는 저장 시점에 거부된다. 0 은 "오늘 것까지
  지운다", 음수는 미래 시각이 커트라인이 되어 전량이 대상이 된다. 복구 수단이 없는 파괴적 배치라
  값 자체를 입구에서 막는다.
- **FAILED 를 READY 와 별도 키로 둔 것은 의도**다 — 실패 자산은 사용자가 다시 올리면 되는 잔여물이라
  정상 자산과 같은 기간을 붙잡아 둘 이유가 없다.
- **설정이 없으면 폴백하지 않고 그 축을 건너뛴다**(다른 설정 소비자의 fail-safe 폴백 관례와 **다른**
  의도적 이탈) — 파괴적 기능이 fail-open 하면 "설정을 못 읽어 아무도 지시하지 않은 기본값으로 사용자
  데이터를 지운다"가 성립한다. 시드(`V11__seed_portal_retention_config.sql`, 7/7/1)가 필수인 이유다 —
  「폴백 금지」와 「시드」는 세트다.

### 방치된 업로드 자산 → FAILED 전이 (2026-08-18 신설)

> 위 표의 "PROCESSING·UPLOADED 는 만료 없음"은 **그 상태에 계속 머무는 한** 유효하다. 실제로는
> `PortalUploadSweepJob(failStuckUploads)`(30분 주기 스윕, 만료 TUS 세션 정리와 같은 잡)가 그 상태를
> 방치로 판정하면 **`FAILED` 로 강제 전이**시키고, 그러면 위 표의 FAILED 축(기본 1일)이 그 자산을
> 넘겨받아 지운다 — "PROCESSING·UPLOADED 는 영원히 안 지워진다"는 뜻이 아니다.

- **판정 축은 「총 처리 시간」이 아니라 「최종 변경 일시(`MDFCN_DT`) 무갱신 경과」다.** 프레임 추출
  러너(`PortalFrameExtractRunner`)가 하트비트(`PortalFrameExtractTxService.touchProcessing`)로 그
  값을 계속 밀어내므로(`LsPortalUldRepository.findStuck` 의 조건은 `mdfcnDt < cutoff`), 정상 진행 중인
  추출은 방치로 판정되지 않는다.
- 커트라인은 **설정값**(`portal.upload.stuck-timeout-minutes`, 기본 30분)이며 운영자가 조정할 수 있다.

> ⚠ **구 서술 폐기(2026-08-18 검증 정정)** — *"하트비트는 프레임 개수(N 프레임마다) 기준이라 대용량
> 영상의 프레임 추출이 몇 시간 걸려도 방치로 판정되지 않는다"* 는 **그 시점엔 거짓이었다.** 실측: 하트비트가
> 프레임 50장마다 한 번뿐이었는데 **프레임 1장의 추출 비용은 영상 내 위치에 선형 비례**해 그 간격에
> **상한이 없었다.** 그래서 정상 추출 중인 자산이 실패로 마감되고, 하루 뒤(실패 보존기간 경과)에는
> 원본까지 삭제될 수 있었다. 아래가 검증 후 고쳐진 실제 동작이다.

- **하트비트는 프레임 개수가 아니라 경과 시간 기준이다.** 마지막 갱신 이후 경과가 하트비트 간격을
  넘으면 그 시점에 `touchProcessing` 을 친다(`PortalFrameExtractRunner.heartbeatIntervalSec`).
- **그 간격은 상수가 아니라 방치 커트라인(`stuck-timeout-minutes`)에서 파생한다** — 커트라인의
  1/4 을 취하고 5초~60초로 자른다. 상수로 고정하면 운영자가 커트라인만 줄였을 때 하트비트가 그보다
  뜸해져 같은 결함이 되살아나기 때문이다.
- **프레임 추출 프로세스 자체에도 대기 상한이 생겼다**(`authoring.ffmpeg.frame-timeout-sec`, 설정
  가능·기본 600초). 전에는 프로세스가 끝날 때까지 무한 대기했다.
- ★**이 셋이 함께 유계를 이룬다** — `무갱신 최대 경과 ≤ 하트비트 간격 + 프레임 대기 상한 < 방치
  커트라인`. 이 부등식이 「장시간 추출이 방치로 판정되지 않는다」를 실제로 보장하는 근거이며, 하트비트가
  「있다」는 사실만으로는 보장되지 않았다(위 폐기된 구 서술이 바로 그 착오였다).
- ★**대가가 있다** — 프레임 한 장이 대기 상한을 넘기면 그 추출은 **실패로 끝난다.** 파괴적 결말이
  사라진 것이 아니라 **조용한 오판(방치로 오인돼 원본까지 삭제)이 진단 가능한 실패로 바뀐 것**이다.
  사유가 로그에 남고 대기 상한은 설정으로 늘릴 수 있으나, 늘릴 때는 위 부등식이 계속 성립하는지
  함께 봐야 한다 — 무작정 늘리면 유계가 깨진다.
- 프레임 한 장의 비용이 영상 내 위치에 비례하는 성질 자체는 **바꾸지 않았다**(프레임 번호의 의미가
  달라질 위험이 있어 보류). 그래서 총 추출 시간은 장시간 영상에서 여전히 길 수 있다 — **다만 이제는
  그 길이만으로 방치 판정을 받지 않는다.**
- **설정이 비었거나 해석 불가(숫자가 아님)면 기동 자체가 실패한다** — `stuck-timeout-minutes` 는
  타입이 있는 설정값이라 바인딩 시점에 걸러진다(`PortalStuckTimeoutBindingTest`). 오타 하나가 삭제
  기준을 조용히 바꾸는 대신 기동 단계에서 드러난다.
- **기동에는 성공했지만 값이 0 이하면, 그 회차의 전이를 건너뛴다**(기본값 폴백 없음, WARN 로그) —
  이 전이는 **삭제의 예고**다(FAILED 로 내려가면 실패 보존기간에 걸려 파일·행이 비가역으로 지워진다).
  `stuck-timeout-minutes=0` 을 그대로 적용하면 "0분간 갱신 없으면 실패"가 되어 **정상 처리 중인 자산
  전량**이 즉시 대상이 되므로, 위 「보존일수 하한」과 같은 취지로 값 자체를 신뢰하지 않고 건너뛴다.
  즉 **해석 불가(기동 실패)와 0 이하(런타임 스킵)는 서로 다른 갈래다** — 둘을 하나로 뭉뚱그리지 말 것.
- **화면에 보이는 것은 달라지지 않는다** — 만료 예정일 표시(아래 「만료 예정 시각 고지」)는 전이
  이전과 동일한 규약을 그대로 따른다(FAILED 전이 시각을 기준점으로 삼는 값이 새로 채워질 뿐이다).

### 정리 스윕과 보존기간 삭제 스윕은 서로 다른 토글로 켜진다 (2026-08-18 신설)

> ⚠ **구 상태 폐기** — 두 잡(`PortalUploadSweepJob`·`PortalRetentionSweepJob`)은 원래 **자기 전용
> 스케줄링 활성화 없이** 다른 기능이 켜 둔 `@EnableScheduling`(관제 통지·작업락 스윕)에 얹혀 돌고
> 있었다. 그래서 포털과 아무 상관 없는 기능(예: 관제 통지)을 끄면 만료 TUS 세션 정리·고착 자산
> 마감·보존기간 삭제가 **소리 없이 멈췄다** — 예외도 로그도 실패하는 테스트도 남지 않는 결함이었다.

- 이제 두 잡은 **각자 자기 키**로 스케줄링이 켜진다 — 업로드 정리 스윕은
  `portal.upload.sweep.enabled`(`PortalUploadSweepSchedulingConfig`), 보존기간 삭제 스윕은
  `portal.retention.sweep.enabled`(`PortalRetentionSweepSchedulingConfig`). 둘 다 기본값 `true`.
- **두 토글이 별개인 것은 의도다** — 보존기간 삭제는 사용자 데이터를 **비가역으로 지운다**. 정리
  성격의 업로드 스윕과 같은 스위치에 묶으면 한쪽을 끄려다 다른 쪽까지 끄게 되고, 그 잘못은 "지워지지
  않는다"(고아 누적)와 "지워진다"(데이터 소실) 어느 방향으로든 조용하다.
- 회귀 고정은 `PortalSweepSchedulingIT` — 무관 토글(관제 통지·작업락 스윕)을 전부 끈 상태에서도 포털
  두 잡이 실제로 스케줄에 등록되는지 확인한다(빈 존재만으로는 부족하다 — `@EnableScheduling` 이 없으면
  `@Scheduled` 자체가 무시되는 것이 바로 그 결함이었다).

### 삭제 절차

- **데이터마트 라벨**: 파일이 없어 (사용자, 영상) 그룹마다 **조건부 DELETE 1회**로 끝난다. 2노드
  Active-Active 에서 중복 실행돼도 두 번째 노드는 0행으로 멱등하다.
- **업로드 자산**: **파일 먼저, DB 나중** 순서로 삭제한다. 뒤집으면 DB 가 먼저 사라져 어느 파일을
  지워야 하는지 알 수 없어져 고아 파일이 영구히 남는다. 파일 경로 판정(`PortalStoragePathGuard`)이
  `OK` 가 아니면(서브트리 밖·해석 불가) 그 자산을 **통째로 건너뛴다** — 파일이 남았는데 DB 만 지우면
  더 나쁘므로 DB 행도 남기고 다음 회차에 재후보한다. **`LS_PORTAL_ULD_FRME`·`LS_PORTAL_ULD_LBL`은
  DB FK `ON DELETE CASCADE`** 로 자산 행 삭제 시 함께 정리된다.
- DB 삭제는 **조건부 UPDATE/DELETE**(스캔 시점과 같은 커트라인으로 재확인)로 2노드 동시 실행을
  멱등화한다 — 별도 분산 락을 새로 만들지 않는다.
- 삭제는 **비가역**이다 — 복구 API·배치는 없다.

### 만료 예정 시각 고지 — 조회 시점 파생값(AC-033)

- `GET /v1/portal/datamart/videos` 응답의 `myLabelExpiresAt`, `GET /v1/portal/uploads`·
  `GET /v1/portal/uploads/{uldSn}` 응답의 `expiresAt` 로 노출한다.
- **엔티티 컬럼이 아니라 조회 시점에 설정값으로 계산되는 파생값**이다 — 보존기간을 7일에서 14일로
  바꾸면 **이미 저장된 라벨의 만료 예정도 다음 조회부터 즉시** 달라진다(마이그레이션·백필 불필요).
  설정값 자체는 `SystemConfigService` 의 Caffeine 캐시(TTL 60s)를 타지만 설정 갱신 시 캐시를
  전체 비우므로 즉시 반영이 성립한다.
- **화면은 받은 값을 그대로 보여줄 뿐 따로 보관하거나 스스로 계산하지 않는다** — 파생값이라 화면이
  캐시하는 순간 설정 변경과 어긋난다.
- 저장 라벨/자산이 없거나 보존기간 설정이 없으면 그 필드만 `null`(목록 조회 자체는 정상 200 — 조회
  경로에서는 설정 부재 예외를 그대로 올리지 않는다. 삭제 배치는 반대로 그 회차를 건너뛴다 — 둘 다
  "설정이 없으면 아무 일도 일어나지 않는다"로 일관된다).

## 16.7 관련 데이터 (DB)

`LS_PORTAL_USER_LABEL` (V47) — `PORTAL_USER_NO`, 원본 `LS_DATA_LBL` 미수정. → [18](18-database.md).

**보존기간 설정 3키**(`LS_SYSTEM_CONFIG`, V11 시드) — `portal.datamart.retention-days`(7) ·
`portal.upload.retention-days`(7) · `portal.upload.failed-retention-days`(1). NUMBER 도메인,
허용 범위 [1, 3650].
