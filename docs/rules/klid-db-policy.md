# klid — DB 정책·표준용어 규칙 (정본)

> **소유**: 프로젝트 `CLAUDE.md` 「DB 정책」 절에서 2026-09-17 에 **원문 그대로** 옮긴 정본이다(CLAUDE.md 가 150k자 한도에 걸려서).
> CLAUDE.md 에는 불변식 요약과 「읽어야 하는 신호」만 남았다. **수정은 이 파일에서 한다.**
>
> - 본문에서 「위/아래 ○○ 절」이라고 가리키는 곳은 대개 **CLAUDE.md 의 절**이다(이 파일 안에 없으면 CLAUDE.md 를 본다).
> - 온디맨드 파일이다 — 자동으로 실리지 않는다. 요약만 보고 판단하지 말고 필요한 부분을 여기서 확인한다.

- `klid_at` 스키마(PostgreSQL)에 저작도구 전용 테이블(LS_*) 운영 — 저작도구가 직접 소유·구성
- **★전 환경·전 채널 PostgreSQL 이다 — 포털향을 다른 RDB 로 가르지 않는다 (2026-08-28 사용자 확정, 구속)**: 관제향(control)과 **포털향(portal) DB 가 모두 PostgreSQL** 이며, *"포털향은 MariaDB 로 전환될 수 있다"* 는 검토는 **기각**됐다(관제향 PostgreSQL / 포털향 MariaDB 로 **DBMS 를 이기종으로 가르는** 안 미채택). ⚠ **기각된 축은 DBMS 이기종화이지 배포 분리가 아니다** — 저작도구는 **채널별로 별도 배포되고 각 배포본이 자기 별도 PostgreSQL DB 를 갖는다**(2026-08-31 확정 · 위 「포털 (외부 채널)」 절의 ★★ 블록 · `ADR-012`). 이 문장을 근거로 그 형상을 금지된 것으로 읽지 말 것.
  - **귀결 — 이기종 대응을 미리 넣지 않는다.** 포털 복제 upsert 의 `CAST(... AS jsonb)` · `ON CONFLICT ... DO NOTHING` 은 잠정 선택이 아니라 **확정 전제**이므로, 방언 중립(dialect-neutral)으로 되돌리거나 그것을 제약으로 새로 세우지 말 것. 복제본은 원본(control)과 **같은 문법으로 동형**을 유지한다. 판정 진실원은 `INT-009`. ⚠ **2026-08-31 보정** — 이 지침의 대상인 **포털 복제 자체가 폐기 확정**이라 철거와 함께 소멸한다(「포털 (외부 채널)」 절). **2026-08-31 철거 완료로 이 지침은 소멸**했다(대상 코드가 없다). **PostgreSQL 단일 형상 확정은 복제와 무관하게 유지**된다.
  - ⚠ **낡은 근거를 되살리지 말 것** — 코드 주석의 *"TO_CHAR 가 MariaDB 미지원이라"*(통계 일별·월별 그룹화) · *"H2(local) / MariaDB(dev/stg/prd) 모두 지원"*(배치 큐 잠금)은 **1차 MariaDB 시절 서술**이고 전부 폐기됐다. **local 도 Testcontainers PostgreSQL** 이다. 단 그 서술이 낳은 **동작(Java 측 키 생성 · 비관적 잠금 no-wait)은 그대로 둔다** — 근거만 무효이고 바꿔서 얻는 것이 없다.
  - ⚠ **v1(1차) 관련 MariaDB 기록은 정정 대상이 아니다** — `ADR-010`(MariaDB→PostgreSQL 전환 결정) · LogiCraft `legacy_artifact` 30건의 `legacy_dbms: MariaDB` · ERD 의 `legacy_source.legacy_dbms` 는 **v1 사실의 기록**이라 그대로 둔다.
- **★스키마 `klid_at` 은 이제 설정으로 실제 배선돼 있다 (2026-08-13 — 전 환경, 구속)**: 그 전까지 이 서술은 **설계 문서에만 있고 코드에는 없었다**(어디에도 스키마 지정이 없어 PostgreSQL 기본값 `public` 으로 떨어져 있었다 — 주석·javadoc 에만 존재하던 드리프트).
  - **배선 지점 4곳 + 값 1개**: 값은 `${DB_SCHEMA:klid_at}` 하나이고 네 지점이 **모두 그 값을 읽는다**(갈리면 JPA·네이티브 쿼리·Quartz 가 서로 다른 스키마를 본다). ① 커넥션 `spring.datasource.control.data-source-properties.currentSchema`(pgjdbc 접속 시작 파라미터 → search_path) ② Flyway `schemas`/`default-schema`/`create-schemas` ③ Quartz `org.quartz.jobStore.tablePrefix` ④ control EMF 의 `hibernate.default_schema`(`ControlDataSourceConfig`).
  - **★네이티브 쿼리 축이 핵심이다** — `hibernate.default_schema` 는 **JPA 매핑 SQL 만** 한정하고 `@Query(nativeQuery=true)` 의 비한정 테이블명·Quartz JobStore·Flyway 는 전부 **커넥션의 search_path** 를 따른다. ①이 빠지면 **테스트는 통과하는데 런타임에서 네이티브 쿼리만 깨진다**. JDBC URL 에 `?currentSchema=` 로 붙이지 않는 이유는 프로파일 yml 4곳 복제 + **테스트가 URL 을 Testcontainers 값으로 통째로 덮어써 그 파라미터가 사라지기** 때문이다(= 검증되지 않는 배선).
  - **portal 데이터소스는 대상이 아니다** — 별개 물리 DB 이고 복제본 스키마는 설치 단계(`17-load-portal-schema.sh`)가 `public` 에 로드한다. 테스트에서만 control 과 같은 DB 를 가리키므로 `src/test/resources/application-local.yml` 에서 테스트 한정으로 맞춘다.
  - **★마이그레이션 SQL 에 `public.` 리터럴을 박지 말 것** — 신규 DB 재적용이 조용히 어긋난다. 실측: `V62`/`V71` 의 stub 교정 가드가 `table_schema='public'` 이라 klid_at 에서는 **영원히 거짓**이 되어 교정이 건너뛰어지고 `V167` 이 `column m.file_fmt does not exist` 로 실패했고, `V63` 이 stub 을 `public` 에만 만들어 `V164` 가 `relation mng_clip_evnt_lst does not exist` 로 실패했다. 세 파일을 `current_schema()` + 비한정 식별자로 교정했다(대상 스키마와 조작 대상이 반드시 같아야 한다). **`search_path` 에 `public` 을 폴백으로 끼워 넣는 방식은 해결이 아니다** — 그러면 `V164` 는 통과해도 `V167` 이 klid_at 의 구 shape stub 을 집어 그대로 실패한다(두 순서 모두 실측).
  - ⚠ **기존 DB 는 배포 전에 스키마를 옮겨야 한다** — 옮기지 않고 배포하면 Flyway 가 klid_at 을 빈 스키마로 보고 V1 부터 전량 재적용해 **데이터는 `public` 에 남고 앱은 빈 klid_at 을 본다**(조용한 분기 — 오류가 아니다).
  - ⚠ **위 세 파일 교정으로 Flyway 체크섬이 바뀐다** — 이미 적용된 DB 는 기동이 **거부**된다(조용한 손상이 아니라 즉시 실패). 스키마 이관 런북에 체크섬 재정렬 1회를 포함할 것(정확한 값은 그 변경 커밋 메시지에 있다).
    - **★2026-08-13 스쿼시 이후 이 체크섬 재정렬은 무의미해졌다** — 기존 DB 는 이력 180행을 **통째로 베이스라인 1행으로 교체**하므로(런북 §2-5-2) 개별 행의 체크섬을 맞출 대상이 없다. 런북 §2-5-1 ③ 은 스쿼시 이전 배포본으로 이관하는 경우에만 해당한다.
    - 이 사고가 남긴 **규칙 자체는 그대로 유효**하며, 앞으로 조건부 마이그레이션을 쓸 때 참조하도록 `V1__baseline.sql` 헤더 「규칙 1·2」로 옮겨 적었다(스코프 없는 카탈로그 조회 금지 — `conrelid = to_regclass(...)` / `schemaname = current_schema()`). 원문 세 파일은 아카이브에 있다.
  - ⚠ **관제 계약면이 움직인다** — 데이터마트 뷰 4종(`V_COMPLETED_*`)이 `public` → `klid_at` 으로 옮겨간다. 그중 **규격상 관제가 SELECT 하는 것은 `V_COMPLETED_VIDEO` 하나**이며(아래 「데이터마트 적재용 View」 절), 그 하나가 스키마를 옮기는 것만으로도 **관제팀 협의 대상**이다.
- 관제서버 MNG_* 테이블 재사용 (READ 위주, JPA `ddl-auto=validate`)
- ⚠ **★`DE_IDENT_YN` 과 `DE_IDNTF_YN` 은 둘 다 맞다 — 전역 치환 금지 (2026-08-15 실측 확정)**: **테이블 컬럼은 `LS_DATA_RAW.DE_IDENT_YN`**(`@Column(name = "DE_IDENT_YN")`)이고, **관제 계약면인 뷰 출력명은 `DE_IDNTF_YN`** 이다 — `V_COMPLETED_VIDEO` 가 `m.de_ident_yn AS de_idntf_yn` 으로 **별칭을 단다**. 한쪽으로 통일하면 **엔티티 매핑이 깨지거나 관제 계약면이 바뀐다.** 같은 파일의 `DE_IDNTF_SRC_FILE_PATH_NM`·`DE_IDNTF_FILE_PATH_NM`·`DE_IDNTF_PJT_ID`·`DE_IDNTF_DATST_ID` 는 **애초에 별개 컬럼**이라 무관하다. (동명이표 주의 사례는 `NEXT_RTRY_DT` 와 같은 계열 — 위 「표준용어·표준도메인 준수」 절 참조)
- **관제 공유 클립 테이블 진실원·산출물 비대상**: UC-018 관제 학습용 적재가 READ하는 `MNG_CLIP_MASTER`·`MNG_CLIP_EVNT_LST` 실제 스키마(복합 PK, `FILE_PATH` 등 — DB 직접 조회 확정)는 LogiCraft **ERD-024**(관제 공유 클립 ERD)에 진실원으로 기록한다. 단 `MNG_*`는 공유(READ) 스키마라 **D8/D9 산출물 비대상**(cc-doc-gen `MNG_*` prefix 규칙으로 자동 제외 — "공유(READ)" 비고만). 적재 어댑터 매핑(`CLIP_ID→VMS_CLIP_ID`, `FILE_PATH→RAW_FILE_PATH_NM`, `VDO_LEN_SEC` ms→초, `EVNT_LST.EVNT_TYPE_CD/SHT_DT` 조인)은 ERD-024 description에 명세.
- Flyway 마이그레이션: LS_* 전용 테이블은 자체 관리, **MNG_* 공유 테이블 변경 시 관제서버팀 선승인 필수**
- 모든 마이그레이션 SQL은 PostgreSQL 표준 문법으로 작성 (MariaDB 고유 문법 금지)
- **★Flyway 스쿼시 완료 — 신규 마이그레이션은 `V7` 부터다 (2026-08-13, 구속 · 스쿼시 직후엔 `V3` 였고 그 뒤 V3~V6 이 쌓였다)**: 누적 180개(V0~V185)를 **`V1__baseline.sql`(스키마 전량 + 시드 19행)** 하나로 접었고 `V2` 는 `CM_CODE`→**`LS_COM_CD`** 개명이다(소유 접두 `LS_` + **표준용어 약어 교정** — 구 이름의 `CM`·`CODE` 는 공통·사업 표준단어 **어디에도 없고** 정본 CSV 대조상 공통=`COM`·코드=`CD` 다. 접두만 붙이면 비표준 물리명이 그대로 남아 한 번에 바로잡았다). 접은 시점 기준: 테이블 76 · 뷰 4 · 시퀀스 49 · `COMMENT ON` 187 · 시드 19행(`ls_system_config` 12 / `ls_com_cd` 5 / `qrtz_locks` 2 — **그 밖의 테이블에는 시드가 없다**).
  - ⚠ **위 수치는 「접은 시점」의 역사적 사실이며 지금 V1 을 적용한 결과가 아니다** — 그 뒤 V3·V4 가 죽은 테이블 7종을 지우면서 **V1 에서 정의·시드를 함께 덜어냈고**(기존 DB 는 그 행이 `BASELINE`·checksum NULL 이라 영향 없음), V6 이 `LS_DATA_LBL_AI_INFO` 를 흡수했다. **현재 형상 = 저작도구 소유 `LS_*` 69개 + Quartz 11 + 뷰 4 = 84**(2026-09-06 재실측 — 구 수치 62/73 은 그 뒤 `V27`~`V33` 이 더해져 낡았다. 같은 날 개발서버 실 DB 도 `ls_* 69` · `qrtz_* 11` 로 일치했다. ⚠ 그 서버는 Flyway 를 쓰므로 `flyway_schema_history` 가 하나 더 있어 표가 81개로 세어진다 — 온프렘은 Flyway 를 끄므로 그 표가 없다)(V1 시드 14행 — `ls_com_cd` 5행은 그 테이블과 함께 소멸). 확인: `grep -c "^CREATE TABLE klid_at.ls_" deploy/onprem/db/schema.sql` → 62.
    - ⚠ **구 수치 57 폐기(2026-08-30 실측)** — 그건 쓰던 시점의 값이고 그 뒤 `V14`(+2) · `V18`(+2) · `V21`(+1) 이 더해졌다. 이 개수는 **새 마이그레이션이 늘 때마다 움직이므로 외우지 말고 그때그때 재실측할 것.**
  - **왜 지금 접었나**: stg/prd/온프렘 **미배포**라 이력 수술 대상이 로컬·dev 둘뿐이었고, 온프렘은 Flyway 를 쓰지 않는다(`schema.sql` 로드). 누적 `CREATE TABLE` 102종 중 **25종이 나중에 DROP** 되는 순수 잔재라 신규 설치가 매번 만들었다 지우는 왕복을 하고 있었다.
  - **동일성은 기계로 증명했다** — 베이스라인은 손으로 쓴 것이 아니라 **180개를 클린 DB 에 전량 적용한 뒤 뜬 `pg_dump`** 다. 컬럼·제약·인덱스·뷰정의·시퀀스·COMMENT·행수 7축 + 덤프 블록 577개가 전부 일치하며, 유일한 차이는 개명분이다.
  - **★옛 180개 파일은 지우지 않았다** — `backend/src/test/resources/db-archive/migration/` 에 원문 보존한다(Flyway `locations=classpath:db/migration` 이 안 읽는 경로). **21개 테스트 클래스가 이 파일들을 직접 읽어 백필·DROP 순서·롤백 절차 주석을 검증**하고 있어 삭제하면 그 회귀 커버리지가 통째로 사라진다. 아카이브 디렉터리 basename 을 `migration` 으로 유지하는 것도 의도다 — 아키텍처 제거 가드(`*TableRemovalTest`)의 allowlist 필터가 부모 디렉터리명으로 판정한다.
  - **기존 DB(로컬·dev)는 앱 기동 전에 이력 180행 → 베이스라인 1행 이관이 필요**하다. 절차·검증 쿼리는 `deploy/onprem/docs/09-operations-runbook.md` §2-5-2. **`baseline-version: 0` 은 그대로 두되 근거가 바뀌었다**(구 근거 "V0 는 placeholder" 는 V0 소멸로 무효 — 지금은 "non-empty·무이력 DB 에서 V1 이 스킵되면 스키마가 통째로 안 생긴다"가 근거다).
  - ⚠ **`V1`·`V2` 는 내용을 수정하지 않는다**(체크섬 불일치 = 전 노드 기동 실패). 스키마 변경은 새 버전 파일로만 한다.
  - ⚠ **마이그레이션 파일에 `${...}` 를 쓰지 말 것 — 주석 안이라도 파싱이 실패한다**(Flyway placeholder). 실측: 헤더 주석의 `${DB_SCHEMA}` 하나로 `No value provided for placeholder` 가 나 베이스라인 전체가 적용되지 않았다.
- **표준용어·표준도메인 준수 (Critical)**: 새로 만드는 DB 컬럼·테이블은 **물리명 + 데이터 타입 + 크기(길이)** 모두 표준/산업 표준을 따른다.
  - **★우선순위 (2026-08-05 사용자 확정, 구속)**: **① 행안부 공통표준 → ② 사업 표준 → ③ (둘 다 없을 때만) 신규 등록.** 행안부가 **1순위**다. 같은 개념이 양쪽에 다른 약어로 있으면 행안부 것을 쓴다(예 재시도 — 행안부 `RTRY` ○ / 사업 `RTY` ✗). 사업표준은 행안부에 **없는 개념**을 채우는 보충이다.
    - ⚠ 실사고: 이 순서를 거꾸로(사업 우선) 적용해 `LS_DATA_INGEST.NEXT_RTRY_DT` 에서 **이미 맞던 `RTRY`(행안부)까지** 사업 약어 `RTY` 로 바꿔 `NXTM_RTY_DT` 가 됐다(V172). V175 는 **`RTY`→`RTRY` 만 되돌렸고** `NEXT`→`NXTM` 은 유지했다(`NEXT` 는 양쪽 사전 미등록). **확정 물리명 = `LS_DATA_INGEST.NXTM_RTRY_DT`**(차기 NXTM + 재시도 RTRY + 일시 DT, 전부 행안부 공통표준) — "V175 로 원래 이름 `NEXT_RTRY_DT` 로 복귀"가 **아니다**. ⚠ 동명이표 주의: `LS_CONTROL_NOTIFY_FALLBACK.NEXT_RTRY_DT` 는 V44 이래 무변경으로 공존하므로 **전역 치환 금지**. 또 같은 `LS_DATA_INGEST` 안에 `RTY_CNT`(기존)와 `NXTM_RTRY_DT`(신규)가 **의도적으로 일시 공존**한다(기존 RTY 자산 7컬럼 + 테이블명 `LS_BAT_RTY_WTNG` 통일은 별도 백로그 — 여기서 함께 바꾸지 말 것). **기존 컬럼이 이미 행안부 표준을 쓰고 있는지 먼저 확인**할 것.
    - ⚠ 도메인(타입·크기)도 같은 우선순위다 — 행안부에 그 **용어**가 등록돼 있으면 그 도메인을 쓴다(예 `DATST_NM` = 행안부 명V200, 사업 `DATA_SET_NM` 명V100 이 아니다).
  - **물리명**: program/gov·산업 표준용어에 등록된 단어의 약어 조합만 사용(임의 약어 생성 금지). 예 — 해상도=RESL, 배치=BAT, 재시도=**RTRY**(행안부), 변경=CHG, 수정=MDFCN
  - **타입·크기**: 표준용어에 연결된 **표준도메인**의 타입·길이를 그대로 채택(임의 크기 금지). 예 — 코드값 도메인은 `VARCHAR(20)`(`EVNT_TYPE_CD`=20이 표준, 32로 잡으면 드리프트 결함=감리 지적)
  - **확정 전 조회처 — 파일 정본이 유일한 판정 근거다** (레포에 커밋돼 있다):
    - `docs/LogiCraft-공공표준용어-2026.08.05 151557/` — `공통표준단어.csv`(3,284) · `공통표준도메인.csv`(123) · `공통표준용어.csv`(13,176) ← **1순위**
    - `docs/LogiCraft-사업용어-2026.08.05 151552/` — `사업표준단어.csv`(471) · `사업표준도메인.csv`(45) · `사업표준용어.csv`(1,373)
    - 인코딩 `utf-8-sig`(BOM), **디렉터리명에 공백**이 있어 shell glob 이 깨진다 → python `os.path.join` 또는 따옴표
    - ⚠⚠ **LogiCraft MCP 검색으로 "미등록"을 판정하지 마라** — `program_word_search` 는 `limit` 상한 200 + `offset` 없음이라 471건 중 **271건이 구조적으로 조회 불가**다(`count: 200` 은 캡에 걸린 것이지 전체가 아니다). 한글 키워드도 표기가 다르면 못 찾는다("형식"으로 찾으면 `FRM` 만 나오고 `FMT`(포맷)는 안 나온다). **"검색 0건 = 미등록" 추론이 이미 정상 컬럼 8건 이상을 "비표준"으로 오판시켰다.** MCP 는 개별 확인·등록에만 쓰고 판정은 **CSV grep** 으로 한다.
    - 표준에 없으면 소유권 규칙 따라 등록 후 사용 (→ 아래 소유권 주의)
  - ⚠ **LogiCraft 사전 수정 전 `createdBy` 확인 필수**: `createdBy: null` = **KLID-BM 표준용어정의서 2026-05-28 배포분**이라 우리 수정 대상이 아니다(사업 사전은 관제서버·포털 프로젝트가 공유한다). **CSV 에는 `createdBy` 컬럼이 없고** `출처`·`관리기관` 공란이 곧 그 배포분 표식이므로, 수정 직전에는 반드시 MCP 응답의 `createdBy` 를 다시 확인한다.
  - **3계층 강제 (재작업 반복 차단 — 수동 규칙·사후 QA 가 모두 놓쳤던 지점)**:
    1. **write 시점 훅(결정론적)**: `.claude/hooks/standard-term-guard.sh`(PostToolUse Edit|Write)가 마이그레이션 SQL(`db/migration/V*.sql`)·JPA 엔티티에 `CREATE TABLE`/`ADD COLUMN`/`@Column` 등 새 스키마 DDL 을 감지하면 표준용어·표준도메인 검증 리마인더를 자동 주입한다(비차단, 서브에이전트 컨텍스트에도 도달). 이 리마인더가 뜨면 커밋 전에 반드시 물리명+타입+크기를 검증·정정한다.
    2. **위임 프롬프트 규칙**: PM 이 DB 를 건드리는 Phase 를 `developer-*` 에이전트에 위임할 때, 위임 프롬프트에 **이 표준용어·표준도메인 제약 + 관련 확정 조회값(메모리)** 을 반드시 포함한다(서브에이전트는 메인 세션 메모리를 자동 상속하지 않으므로 명시 전달 필수).
    3. **QA 검사 명시**: DB 변경 Phase 의 `database-reviewer`/`design-verifier` 호출 프롬프트에 **"새 컬럼·테이블 물리명(표준단어 조합) + 타입·크기(표준도메인) 준수 여부"** 검사를 명시 항목으로 넣는다. 비표준 발견 = HIGH → DEV_FIX(표준어·표준크기로 정정).
