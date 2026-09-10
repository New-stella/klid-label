# 07. 제거 / 롤백

## 제거 (데이터 보존)

서비스 중지·비활성화 + 앱/런타임 제거. **데이터·환경설정·로그는 보존**한다.

> ⚠ **backend 는 이 스크립트가 내리지 못한다** (배포 형상 = 외부 WAS 에 WAR 반입).
> WAS 에 올린 `api.war` 의 언디플로이는 **사람이 WAS 쪽에서** 수행한다.
> `uninstall.sh` 는 `klid-backend` 유닛을 정지 시도하지만 WAR 형상에서는 그 유닛이 없어 무시된다.

```bash
cd deploy/onprem
sudo ./scripts/uninstall.sh
```

보존되는 것: `/etc/klid`(env), `/var/lib/klid`(런타임 데이터), `/var/log/klid`(로그), 그리고 **NAS 영상 저장소**(`STORAGE_RAW_PATH`, 기본 `/nas-storage`) — 제거 스크립트는 NAS 를 건드리지 않는다(마운트 해제·삭제는 운영자 수동).

> ★ **`/etc/klid/was.env` 는 별도 제거 대상이 아니다 — `/etc/klid` 안에 있어 함께 보존된다.**
> 그 파일에는 WAS 유닛명·`WAS_HOME`·배포 디렉터리·로그 경로 같은 **현장값**이 들어 있고
> 비밀값은 없다. 위에서 안내하는 **수동 WAS 언디플로이가 바로 그 값을 필요로 하므로**,
> 데이터 보존 제거에서 이 파일을 지우면 그다음 작업을 할 수 없게 된다.

## 완전 제거 (PURGE)

데이터/환경설정/로그 + `klid` 사용자까지 삭제. **복구 불가** — 영상/DB 외 데이터 손실 주의.

```bash
sudo PURGE=1 ./scripts/uninstall.sh
```

> ⚠ **PURGE 는 `/etc/klid/was.env` 도 지운다 — 순서에 함정이 있다.**
> 그 값이 없으면 `api.war` 를 **어느 WAS 의 어느 디렉터리에서** 걷어내야 하는지 알 수 없다.
> `uninstall.sh` 는 삭제 직전에 그 값을 화면에 출력하므로 **그때 받아 적거나**,
> 안전하게는 **WAS 언디플로이를 먼저 끝낸 뒤** PURGE 를 실행한다.

> PostgreSQL DB(klid_system/portal) 는 외부/별도 자원이라 uninstall 이 건드리지 않는다.
> DB 삭제가 필요하면 DBA 가 별도 수행한다.

## 롤백(이전 버전으로)

이 패키지는 단순 파일 배치 방식이라 버전 롤백은 "이전 패키지로 재설치"로 한다.

1. 현재 서비스 중지: `sudo systemctl stop httpd klid-ai-server` + **WAS 중지(backend)**
2. 이전 버전 `deploy/onprem/` 패키지로 `sudo ./scripts/install.sh` 재실행
   (env 는 보존되므로 그대로 사용, 필요 시 수정).
3. **이전 버전 WAR 를 WAS 배포 디렉터리로 다시 복사**(파일명은 현재 배포된 것과 같게 — 현장은
   `klid-at-api.war` 다. 컨텍스트는 WAR 안 `jboss-web.xml` 이 정하므로 이름은 무엇이든 된다).
   복사 후 **`touch <WAR>.dodeploy`** 로 재배포를 트리거하고, `<WAR>.deployed`(성공) 또는
   `<WAR>.failed`(실패, 사유 1줄)가 생기는지로 판정한다.
   ⚠ EAP 는 톰캣처럼 `webapps/api/` 로 풀어 두지 않는다 — 푼 사본은 `standalone/tmp/` 아래
   WAS 가 관리하는 캐시이며, 지워야 한다면 **WAS 정지 상태**에서 한다.
4. 기동·검증: 05-run-verify.md.

> ⚠ 구 절차 폐기(2026-08-30) — 3 단계 없이 `install.sh` 재실행만으로 backend 가 교체되던 것
> (베어메탈 형상). WAR 형상에서 `install.sh` 는 `/opt/klid/app/api.war` 를 갱신할 뿐이고,
> **WAS 에 올라간 것은 사람이 바꾸기 전까지 그대로다.**

> **DB 스키마는 온프렘에서 Flyway 가 관리하지 않는다** — 설치 시 `db/schema.sql` 을 1회 로드한 그 상태가
> 전부다. 따라서 **하위 버전으로 내릴 때 스키마를 되돌리는 것도 사람의 일이다**(자동으로 내려가지
> 않는다). 이전 WAR 가 현재 스키마와 맞는지 반드시 확인하고, 비호환이면 아래 절의 수동 SQL 또는
> DB 백업 복원이 필요하다.
>
> ⚠⚠ **기동 성공은 확인이 아니다.** `ddl-auto=validate` 가 선언돼 있으나 **실동작하지 않아**
> (`09-operations-runbook.md` §2-5-2 「왜 조용히 실패하나」) 스키마가 어긋나도 기동은 된다.
> 아래 각 절의 「실패 경로」 표대로 **런타임에 가서야** 드러난다.
>
> ⚠ **아래 절들의 `flyway_schema_history` 조작 단계는 온프렘 기본 형상에서 건너뛴다** — 그 테이블 자체가
> 없다(덤프에서 의도적으로 제외). **DDL 역적용(역개명·재생성)만 수행**하면 된다. 이력 행 삭제가 필요한
> 것은 Flyway 를 켠 개발·검증 DB 뿐이다.

### 알려진 비호환 — V162(`MNG_CLIP_SCHEDULE_QUE` → `LS_CLIP_SCHEDULE_QUE` 개명) 이후 버전에서 롤백

V162 가 적용된 DB 에 **V162 이전 jar** 를 올리면 구버전 엔티티(`@Table(name="MNG_CLIP_SCHEDULE_QUE")`)가
없는 테이블을 가리키게 되어 **그 큐를 쓰는 경로(배치 큐 폴링·인입)가 전부 실패**한다. 재설치(2단계) **전에**
`backend/src/test/resources/db-archive/migration/V162__rename_mng_clip_schedule_que_to_ls.sql` 상단 주석의
**rename-back SQL(FK DROP → 부속객체·테이블 역개명 → `flyway_schema_history` 에서 version='162' 삭제)** 을
DBA 가 수동 적용하라. 데이터 유실은 없다(RENAME 만 수행).

> ⚠ **온프렘에서는 rename-back 중 `flyway_schema_history` 삭제 단계만 건너뛴다** — 그 테이블이
> 없다. **FK DROP 과 역개명(DDL)은 그대로 수행**한다. 이력 행 삭제가 필요한 것은 Flyway 를 켠
> 개발·검증 DB 뿐이다.
> ⚠ 구 서술 폐기(2026-08-30) — *"`ddl-auto=validate` 검증에서 「테이블 없음」으로 걸려 2노드 모두
> **기동에 실패**한다"*. 그 검증은 실동작하지 않아 기동은 성공하고 런타임에 터진다.

> **경로 주의(2026-08-13 스쿼시)** — V162 를 포함한 구 마이그레이션 180개는 `db/migration` 에서
> `src/test/resources/db-archive/migration/` 으로 **옮겨져 원문 그대로 보존**된다(Flyway 는 이 경로를
> 읽지 않는다). 롤백 절차 주석은 그대로 있으므로 위 파일에서 확인하면 된다.

### 알려진 비호환 — V2(`CM_CODE` → `LS_COM_CD` 개명)·V4(그 테이블 제거) 이후 버전에서 롤백

`V2` 가 적용된 DB 에 스쿼시 이전 jar 를 올리면 구 마이그레이션이 `CM_CODE` 를 참조한다.
기동도 런타임도 깨지지 않지만(이 테이블에 JPA 매핑이 없다) 이력·마이그레이션 정합을 위해 되돌린다.

> **⚠ `V4` 까지 적용된 DB 에서는 아래 RENAME 이 통하지 않는다** — `V4` 가 `LS_COM_CD` 를 **DROP** 했으므로
> 역개명할 대상 테이블 자체가 없다(`relation "klid_at.ls_com_cd" does not exist`). 그 경우에는 **테이블을
> 먼저 재생성**한 뒤 이력을 되돌린다. 원문 위치·시드 조각 순서(5행이 두 파일에 나뉘어 있다)·주의사항은
> `backend/src/main/resources/db/migration/V4__drop_unused_tables_round2.sql` 헤더 「롤백 절차」에 있다.
> 그 헤더가 가리키는 아카이브 파일들은 **독자 번호 체계**라 현행 `V1~V4` 와 이름이 겹친다 — 번호가 아니라
> **파일명 전체**로 찾을 것.

```sql
-- V2 만 적용된 DB(= V4 이전 형상)에서만 그대로 성립한다.
ALTER TABLE klid_at.ls_com_cd RENAME TO cm_code;
ALTER TABLE klid_at.cm_code RENAME CONSTRAINT ls_com_cd_pkey TO cm_code_pkey;
```

`V4` 가 지운 나머지 3종(`LS_DATA_META_HSTRY`·`LS_DATA_RAW_HSTRY`·`LS_TASK_ASSIGN_HISTORY`)도 같은 절차로
재생성한다. 구버전 코드는 `LS_TASK_ASSIGN_HISTORY` 에 **쓰기**를 하므로(재배정) 이 테이블이 없으면
재배정이 실패한다 — 나머지 둘은 구버전에도 읽고 쓰는 경로가 없어 없어도 동작한다.

이어서 `flyway_schema_history` 를 구 배포본 기준으로 되돌린다(구 180행 이력이 필요하다 — 스쿼시 이관
직전에 뜬 백업 덤프에서 복원한다. 절차는 `09-operations-runbook.md` §2-5-2).

> ⚠ **이 이력 복원 단계는 온프렘 기본 형상에서 건너뛴다** — `flyway_schema_history` 가 없다.
> 위 테이블 재생성·역개명(DDL)까지만 수행한다. 이력 복원이 필요한 것은 Flyway 를 켠
> 개발·검증 DB 뿐이다.

> **가장 안전한 경로는 위 조각 맞추기가 아니라 백업 덤프 복원이다** — 스쿼시 이관 직전 덤프를 빈 DB 에
> 복원하고 구버전 jar 로 되돌리면 위 재생성·역개명이 모두 불필요하다.

### 알려진 비호환 — V5(배치 큐·메타복제 발신함 컬럼 11종 개명) 이후 버전에서 롤백

**V5 는 비하위호환 개명이다.** V5 가 적용된 DB 에 **V5 이전 jar** 를 올리면 구버전 엔티티가 옛 컬럼명
(`PAYLOAD`·`STATUS`·`RETRY_CNT`·`PROC_DT` / `JOB_TYPE`·`STATUS`·`RETRY_COUNT`·`REGISTERED_AT`·
`STARTED_AT`·`COMPLETED_AT`·`LAST_ERROR`)으로 매핑하므로 **두 테이블의 읽기·쓰기가 전부 깨진다.**
기동 자체는 되므로(이 환경은 `ddl-auto=validate` 가 실동작하지 않는다 —
`09-operations-runbook.md` §2-5-2 「왜 조용히 실패하나」) **런타임에 가서야 드러난다.**

| 실패 경로 | 증상 |
|---|---|
| **검수 승인** (`ReviewService.approve` → `DatasetVideoMetaSnapshotService.materialize` 의 outbox INSERT · `supersedePending`) | **같은 트랜잭션이라 승인 전체가 500** — 사용자 대면 실패 |
| **영상 인입** (`LabelingBatchQueueService.enqueue`) | 인입 트랜잭션 안에서 큐 INSERT 가 실패해 **인입째 롤백** |
| **배치 큐 폴링** (`BatchQuartzJob`) | 매 tick 실패 — 파이프라인이 진행되지 않는다 |
| **포털 메타 복제 워커** (`MetaReplicationWorker`) | 매 tick 실패 — 포털 복제본이 갱신되지 않는다 |

오류 메시지는 `ERROR: column "payload" does not exist` · `column "status" does not exist` 형태다.

**되돌리는 방법** — 재설치(위 2단계) **전에** DBA 가 수동 적용한다.

1. `backend/src/main/resources/db/migration/V5__rename_queue_outbox_columns_to_std.sql` 헤더의
   **「롤백 절차」** 절에 역방향 SQL 13줄이 그대로 있다(폭 확대 2줄 → 역개명 11줄, 순서까지 포함).
   그 순서대로 실행한다. **데이터 유실은 없다**(RENAME 과 폭 확대만 수행).
2. 이어서 Flyway 이력 행을 지운다 — 지우지 않으면 구버전 jar 가 **알 수 없는 버전 5 행**을 보고
   검증에서 걸린다.
   ⚠ **온프렘 기본 형상에서는 이 2번을 건너뛴다** — `flyway_schema_history` 가 없다. 1번(역개명
   SQL)만 수행하면 롤백이 끝난다. 아래 `DELETE` 가 필요한 것은 Flyway 를 켠 개발·검증 DB 뿐이다.

```sql
-- ⚠ Flyway 를 켠 개발·검증 DB 전용. 온프렘에는 이 테이블이 없다.
DELETE FROM klid_at.flyway_schema_history WHERE version = '5';
```

> 이 파일은 스쿼시 **이후** 버전이라 위 V162 절과 달리 `db-archive/` 가 아닌 **현행 배포
> 마이그레이션 디렉터리**(`backend/src/main/resources/db/migration/`)에 있다. 번호가 아니라
> 파일명 전체(`V5__rename_queue_outbox_columns_to_std.sql`)로 찾을 것 — 아카이브에도 같은 번호의
> 전혀 다른 파일이 있다.

> **전진(업그레이드) 방향에도 같은 비호환이 있다.** 구버전 jar 노드와 V5 가 적용된 스키마가 공존하는
> 창에서 위 4경로가 그대로 실패한다 — 배포 절차는 `09-operations-runbook.md` §4 「V5 배포 시 주의」 참조.

## 재설치 전 백업 권장

```bash
sudo cp -a /etc/klid /etc/klid.bak.$(date +%Y%m%d)
# DB 백업(예시): pg_dump -h <host> -U <user> klid_system > klid_system.sql
```
