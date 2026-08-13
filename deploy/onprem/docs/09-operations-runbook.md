# 09. 운영 런북 (관리자 매뉴얼 — 장애 조치·프로세스 상태 확인)

> 이 문서는 **설치 완료 후 운영 단계**의 관리자용 조치 절차다. 설치 시점의 실패 대응은
> [06-troubleshooting.md](06-troubleshooting.md)(설치 트러블슈팅)를 보고, 여기서는 **가동 중 시스템의
> 프로세스 상태 확인**과 **장애 발생 시 조치사항**을 다룬다. 아키텍처 레벨의 장애 대응 원리
> (감지→차단→복구→통지·기록)는 `docs/design/D5-아키텍처설계서-Rev2.md §3 [가용성·장애복구]` 를 따른다.

## 대상 서비스·프로세스 요약

| 서비스(유닛) | 역할 | 포트 | 헬스 | 로그 |
|---|---|---|---|---|
| `postgresql-16` | 데이터베이스(번들 PG16) | 5432 | `systemctl is-active postgresql-16` | `journalctl -u postgresql-16` |
| `klid-ai-server` | 내부 추론 서버(YOLO/SAM2) | 9300 | `curl http://127.0.0.1:9300/health` | `journalctl -u klid-ai-server` |
| `klid-backend` | 애플리케이션(WAS)·배치 스케줄러 | 8080(`/api`) | `curl http://127.0.0.1:8080/api/actuator/health/liveness` | `journalctl -u klid-backend` |
| `klid-frontend` | 웹서버(Caddy·정적 서빙) | 80 | `curl -o /dev/null -w '%{http_code}' http://127.0.0.1/` | `journalctl -u klid-frontend` |

- 기동 의존 순서: **PostgreSQL → ai-server → backend → frontend** (유닛에 의존성 반영).
- 설치 경로: `/opt/klid/{app,ai,web,runtime}` · 환경설정 `/etc/klid/*.env` · 데이터 `/var/lib/klid` · 로그 `/var/log/klid`.

---

## 1. 프로세스 상태 확인 (일상 점검)

### 1-1. 서비스 상태 한눈에

```bash
systemctl status postgresql-16 klid-ai-server klid-backend klid-frontend --no-pager
# 각 유닛이 active (running) 인지 확인. 실패 시 Active: failed / activating 로 표시된다.
```

### 1-2. 헬스체크(스모크)

```bash
curl -fsS http://127.0.0.1:9300/health                              # ai-server → {"status":"ok"}
curl -fsS http://127.0.0.1:8080/api/actuator/health/liveness        # backend  → {"status":"UP"}
curl -fsS http://127.0.0.1:8080/api/actuator/health                 # backend  전체(readiness·연동 상태)
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1/        # frontend → 200
curl -fsS http://127.0.0.1/api/actuator/health/liveness             # 프론트→백엔드 프록시 경로
```

> `/api/actuator/health` 응답의 `components` 에 DB·연동별 HealthIndicator 상태가 포함된다.
> `DOWN` 컴포넌트가 있으면 해당 연동(2절)을 점검한다.

### 1-3. DB 접속·스키마 확인

```bash
systemctl is-active postgresql-16
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system -c '\conninfo'
# 저작도구 스키마 존재·객체 수 (기본 klid_at — DB_SCHEMA 로 변경 가능)
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select count(*) from information_schema.tables where table_schema='klid_at';"
# Flyway 마이그레이션 이력(스키마 정상 반영 여부) — Flyway 부트스트랩 구성일 때만 존재
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select version, description, success from klid_at.flyway_schema_history order by installed_rank desc limit 5;"
```

> **★ 저작도구 객체는 `klid_at` 스키마에 있다.** psql 기본 `search_path` 는 `"$user", public` 이라
> 스키마를 명시하지 않은 조회는 `relation ... does not exist` 로 실패한다. 이 문서의 모든 조회는
> `klid_at.` 로 한정하거나, 세션에서 `set search_path to klid_at;` 를 먼저 실행한다.
> `SPRING_FLYWAY_ENABLED=false`(온프렘 기본) 구성에서는 `flyway_schema_history` 자체가 없다 —
> 스키마는 설치 시 `db/schema.sql` 로드로 준비되며 앱은 `ddl-auto=validate` 로 검증만 한다.

> **Flyway 부트스트랩 모드(`SPRING_FLYWAY_ENABLED=true`) 첫 기동 주의** — 앱은 `baseline-on-migrate=true` +
> **`baseline-version=0`** 으로 동작한다. 관제/인프라가 `MNG_*`·`QRTZ_*` 를 앱보다 먼저 provisioning 한
> **비어있지 않은(non-empty)·flyway 이력 없는** DB 에 첫 기동해도 `V1` 부터 전부 적용되도록 보장하기 위함이다.
> (baseline-version 을 기본값 1 로 두면 baseline 이 `V1` 을 스킵 → pjt 기반 테이블 미생성 →
> `V5`/`V34` 에서 `relation ... does not exist` 로 마이그레이션 실패한다.) baseline 은 **이력 없는 DB 첫 기동
> 시에만** 발동하므로 이미 `flyway_schema_history` 가 있는 환경에는 영향이 없다.
> - 증상: 첫 기동 로그에 `Migration ... failed` + `relation "ls_pjt_..." does not exist`,
>   `flyway_schema_history` 에 `<< Flyway Baseline >>` row(version=1)만 있고 실제 `V1` SQL row 부재.
> - 이미 잘못된 baseline 이력으로 멈춘 DB 는 설정만으론 복구되지 않는다 → 해당 DB 의
>   `flyway_schema_history` 를 DROP 후 재기동(무이력 재적용)하거나 DBA 가 수동 정정한다.

### 1-4. 배치 파이프라인 상태 확인

배치는 스케줄러가 1건/분으로 유입한다. 적재·전이 상태를 DB로 확인한다.

```bash
# 상태별 영상 건수 (PENDING 적체/PROCESSING 정체/FAILED 누적 감시)
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select data_stts_cd, count(*) from klid_at.ls_raw_data_status group by data_stts_cd order by 2 desc;"

# 비식별 미완료('F')·대기 영상
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select raw_sn, de_idnt_yn, data_stts_cd from klid_at.ls_data_raw where de_idnt_yn <> 'Y' order by raw_sn desc limit 20;"
```

### 1-4-1. 이중화(2노드) 스케줄러 클러스터 상태

2노드 Active-Active 는 Quartz 클러스터링(`QUARTZ_CLUSTERED=true`)으로 트리거 중복 발화를 막는다.
클러스터가 성립하지 않으면 배치가 **노드마다 중복 실행**된다(로그·헬스는 정상으로 보인다).

```bash
# 등록된 스케줄러 노드 — 기동한 노드 수만큼 행이 있어야 한다(1노드면 1행)
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select instance_name, checkin_interval, to_timestamp(last_checkin_time/1000) as last_checkin from qrtz_scheduler_state;"

# 클러스터 락 행(V76 시드) — TRIGGER_ACCESS / STATE_ACCESS 2행
PGPASSWORD='<앱_비밀번호>' psql -h 127.0.0.1 -U klid_user -d klid_system \
  -c "select sched_name, lock_name from qrtz_locks;"

# 노드 간 시계 동기(NTP) — 클러스터링의 전제. 두 노드 모두 확인한다.
timedatectl            # NTP service: active
chronyc tracking       # System time offset 이 1초 이내인지
```

- 노드가 떠 있는데 `qrtz_scheduler_state` 에 행이 **0개** → 클러스터링이 꺼져 있다(설정 확인).
  stg/prd 는 `QUARTZ_CLUSTERED=false` 면 기동 자체가 거부되므로, 프로파일/`ENV` 표식부터 확인한다.
- `last_checkin` 이 `checkin_interval` 의 수 배 이상 정체된 행 → 죽은 노드의 잔여 행(다른 노드가 곧 회수).
- 시계 오차가 크면 misfire 오판·중복 발화가 생긴다 → NTP 부터 교정한다.

### 1-5. 자원 상태 (CPU/메모리/GPU/디스크)

```bash
systemctl status klid-backend --no-pager | grep -E 'Memory|Tasks'   # 프로세스 메모리
df -h /opt/klid /var/lib/pgsql /var/log/klid "$STORAGE_RAW_PATH"     # 디스크 여유(앱·DB·로그·저장소)
# 저장소 경로는 backend.env 의 STORAGE_RAW_PATH 설정값(기본 /nas-storage), DB 데이터는 /var/lib/pgsql/16/data
nvidia-smi                                                          # GPU 사용률(추론 서버, 해당 시)
```

---

## 2. 장애 유형별 조치사항

### 2-1. 서비스 다운 / 비정상 종료

**증상**: `systemctl status` 가 `failed`, 헬스체크 무응답.

```bash
# 1) 최근 로그로 원인 확인
journalctl -u klid-backend -n 200 --no-pager
# 2) 재기동
sudo systemctl restart klid-backend        # 또는 klid-ai-server / klid-frontend / postgresql-16
# 3) 반복 실패 시 환경설정·의존 서비스 확인
systemctl is-active postgresql-16 klid-ai-server   # backend 는 이 둘에 의존
cat /etc/klid/backend.env                          # DB 접속·JWT 시크릿·연동 주소 확인(비밀번호 노출 주의)
```

> backend 는 `Requires=klid-ai-server` + `After=postgresql`. **의존 서비스가 죽으면 backend 도 뜨지 않는다** —
> DB → ai-server → backend 순으로 살린다. 첫 기동은 Flyway 스키마 부트스트랩으로 다소 길다(TimeoutStartSec=180).

### 2-2. 배치 적체 / 정체 (파이프라인이 진행되지 않음)

**증상**: `ls_raw_data_status` 의 `PENDING`/`PROCESSING` 건수가 계속 증가.

- **원인 1 — 스케줄러 정지**: backend 재기동. 잡 상태는 PostgreSQL JobStore(`QRTZ_*`)에 영속되어 재기동 시 미완료 잡을 이어 처리한다.
- **원인 2 — 선두 비식별 실패로 정체**: 2-4 참조(비식별이 막히면 이후 단계로 진행 못 함).
- **원인 3 — 추론 서버 병목**: `nvidia-smi` GPU 포화 확인 → ai-server 인스턴스 수평 확장 검토(무상태라 증설 가능).

```bash
journalctl -u klid-backend --no-pager | grep -iE 'batch|schedul|retry|pipeline' | tail -50
```

### 2-3. 외부 연동 실패 / 서킷 브레이커 OPEN

**증상**: `/api/actuator/health` 의 연동 컴포넌트 `DOWN`, 로그에 서킷 OPEN·타임아웃.

- 저작도구는 원격·프로세스 경계 호출에 **타임아웃·재시도(3회·지수 백오프)·서킷 브레이커**를 적용한다
  (비식별·추론 60초, VLM 45초, 관제통지 10초). 서킷 OPEN 은 **장애 전파 차단**이 정상 동작한 것이다.
- **조치**: 상대 시스템(비식별·VLM·증강·관제) 자체 상태를 먼저 확인한다. 상대가 복구되면 서킷은 30초 후 half-open→close 로 자동 회복하고, 실패분은 재처리·재등록 큐로 자동 재시도된다.

```bash
# 연동 대상 도달 확인(설치값 IP/PORT 로 치환)
curl -fsS http://<대상_IP>:<PORT>/        # 또는 대상 제공 헬스 경로
journalctl -u klid-backend --no-pager | grep -iE 'circuit|timeout|resilience|controlnotify|vlm|kpst' | tail -50
```

- **관제 통지 실패**: 실패분은 재등록 큐(dead-letter)에 fallback 저장 후 스케줄 재시도(멱등·요청 ID)된다. 상대 복구만 확인하면 별도 수동 조치 불필요.

### 2-4. 비식별(KPST) 실패

**증상**: 영상이 계속 `DE_IDENT_YN='F'`, 이후 단계 진행 안 됨.

```bash
# https + 사설 CA 도달 확인
curl -fsS --cacert /etc/klid/kpst-ca.crt https://<KPST_IP>:<PORT>/
```

- **원본은 절대 삭제되지 않는다.** 실패 영상은 상태만 `F` 로 표시된다.
- 연동·주소·CA 를 점검(→ [06-troubleshooting.md](06-troubleshooting.md))하고, 필요 시 **외부 비식별 솔루션으로 수동 재비식별** 후 재적재 경로로 처리한다.

### 2-5. 데이터베이스 장애

> **DB 는 외부 인프라 제공(저작도구 미운영)** — DB 서버 장애·재기동·백업은 **DB 운영(인프라) 주체** 소관이다.
> 저작도구 측 조치는 **접속 확인 + backend 커넥션 풀 회복**이다. 아래 `systemctl restart postgresql-16` 은
> **번들 PG 로 단독 구성한 경우에만** 해당한다(외부 DB 는 인프라 주체에 장애 전파·복구 요청).

```bash
# 접속 확인(저작도구 측) — 외부 DB 주소로 치환
PGPASSWORD='<앱_비밀번호>' psql -h "$CONTROL_DB_HOST" -p "$CONTROL_DB_PORT" -U klid_user -d klid_system -c '\conninfo'

# --- 아래는 번들 PG 단독 구성 한정 ---
systemctl status postgresql-16 --no-pager
journalctl -u postgresql-16 -n 100 --no-pager
df -h /var/lib/pgsql/16/data   # 디스크 풀이 흔한 원인(번들 PG 데이터 디렉토리)
sudo systemctl restart postgresql-16
```

- DB 복구 후 backend 를 재기동해 커넥션 풀을 회복시킨다(외부/번들 공통).

### 2-5-1. 스키마 이관 (`public` → `klid_at`) — 기존 DB 1회 작업

> **대상**: 저작도구 객체가 `public` 에 있는 **기존 DB**. 신규 설치는 `db/schema.sql` 로드로 처음부터
> `klid_at` 에 만들어지므로 해당 없다. 설치 스크립트(`16-load-schema.sh`)는 이 상태를 감지하면
> **로드를 거부**한다 — 그대로 로드하면 빈 `klid_at` 이 생기고 데이터는 `public` 에 남기 때문이다.
>
> **이관하지 않고 신 버전을 올리면**: 앱이 `klid_at` 을 빈 스키마로 보고 Flyway 가 `V1` 부터 전량
> 재적용한다. 데이터는 `public` 에 남고 앱은 빈 `klid_at` 을 본다 — **오류가 아니라 조용한 분기**다.

**전제**: backend(2노드 모두) 정지. 작업 중 앱이 붙어 있으면 안 된다.

```bash
sudo systemctl stop klid-backend    # 2노드 모두

# ① 덤프 백업 (되돌릴 수 있는 지점 확보 — 생략 금지)
sudo -u postgres pg_dump -Fc -d klid_system -f /backup/klid_system.pre-klid_at.dump
```

**② 이동** — `ALTER ... SET SCHEMA`. 앱 유저(소유자)로 실행한다.

```sql
CREATE SCHEMA IF NOT EXISTS klid_at AUTHORIZATION klid_user;

-- 2-pass: 테이블·뷰를 먼저 옮기고(인덱스·제약·트리거·소유 시퀀스가 함께 따라온다),
--         그래도 남은 <독립> 시퀀스만 뒤이어 옮긴다.
--   ★ 시퀀스를 한 루프에서 같이 돌리면 안 된다 — IDENTITY 컬럼 시퀀스는 소유 테이블에 묶여 있어
--     개별 ALTER SEQUENCE 가 "cannot move an owned sequence into another schema" 로 실패한다.
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT c.relname, c.relkind FROM pg_class c
             JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r','v','m')
            ORDER BY c.relkind DESC          -- 뷰(v)보다 테이블(r)을 먼저
  LOOP
    EXECUTE format('ALTER %s public.%I SET SCHEMA klid_at',
                   CASE r.relkind WHEN 'r' THEN 'TABLE' WHEN 'v' THEN 'VIEW'
                                  ELSE 'MATERIALIZED VIEW' END, r.relname);
  END LOOP;

  FOR r IN SELECT c.relname FROM pg_class c
             JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind = 'S'
  LOOP
    EXECUTE format('ALTER SEQUENCE public.%I SET SCHEMA klid_at', r.relname);
  END LOOP;
END $$;

-- flyway_schema_history 는 위 루프(relkind='r')에 포함된다. 안전망으로 한 번 더 확인한다.
ALTER TABLE IF EXISTS public.flyway_schema_history SET SCHEMA klid_at;
```

> **실측 확인(PostgreSQL 16)**: 구 형상(마이그레이션 180건을 `public` 에 전량 적용)에 위 SQL 을 실행하면
> 테이블 77(= 저작도구 76 + `flyway_schema_history`) · 뷰 4 · 시퀀스 49 · 인덱스 212 가 전부 `klid_at`
> 으로 이동하고 `public` 은 0 이 된다. 제약도 함께 이동한다(FK 49 · PK 77 · UNIQUE 26 · CHECK 3,
> `public` 잔존 0). 이동 후 객체 집합은 **신규 설치(`db/schema.sql` 로드) 결과와 완전히 동일**하며
> 차이는 `flyway_schema_history` 하나뿐이다(덤프에서 의도적으로 제외한 테이블).

> ### ★★ 복사가 아니라 **이동**이다 — `public` 에 사본을 남기지 마라
>
> `CREATE TABLE klid_at.x AS SELECT * FROM public.x` 처럼 **복사**한 뒤 `public` 원본을 남겨 두면
> 마이그레이션이 **조용히 망가진다**. `V79`·`V110`·`V146` 은 제약 존재 여부를
> `SELECT ... FROM pg_constraint WHERE conname = '...'` 로 확인하는데, **`pg_constraint` 조회는
> `search_path` 를 타지 않는다**(카탈로그 전체를 본다). 즉 `public` 에 남은 사본의 동명 제약을 보고
> **"이미 있다"로 판정해 `klid_at` 의 FK·UNIQUE 를 통째로 건너뛴다.**
> **오류 없이 제약만 누락**되므로 마이그레이션은 성공으로 보이고, 결함은 한참 뒤 데이터 정합성
> 문제로 드러난다. 반드시 `SET SCHEMA` 로 **옮기고**, 사본을 만들었다면 `public` 쪽을 지워라.

> ### ★ `flyway_schema_history` 를 안 옮기면 기동이 실패한다
>
> 이력이 `public` 에 남으면 Flyway 는 `klid_at` 을 **이력 없는 DB** 로 보고 `V1` 부터 재적용한다.
> 그 재적용은 **`V34` 의 `DROP COLUMN PJT_ID`(IF EXISTS 없음)** 에서 확정 실패한다.
> (이력을 옮기지 않는 편이 "조용한 손상" 대신 "즉시 실패"라는 점은 다행이지만, 정상 경로가 아니다.)

**③ Flyway 체크섬 재정렬** — `V62`·`V63`·`V71` 이 스키마 중립(`current_schema()` 기반)으로 바뀌어
이미 적용된 DB 는 체크섬 불일치로 **기동이 거부**된다. 1회 정정한다.

```sql
UPDATE klid_at.flyway_schema_history SET checksum = -1105638733 WHERE version = '62';
UPDATE klid_at.flyway_schema_history SET checksum =  -706850408 WHERE version = '63';
UPDATE klid_at.flyway_schema_history SET checksum =  1678444126 WHERE version = '71';
```

> 구 값은 각각 `-712380907` / `399485742` / `971618886` 이다. 위 값과 다르면 **이미 정정됐거나
> 다른 버전**이니 그대로 두고 확인한다. `flyway repair` 도 동등한 수단이다.
> 온프렘 기본 구성(`SPRING_FLYWAY_ENABLED=false`)에는 이력 테이블 자체가 없으므로 ③은 해당 없다.

**④ 검증 후 기동**

```bash
sudo -u postgres psql -d klid_system -c \
  "select (select count(*) from information_schema.tables where table_schema='klid_at') as klid_at,
          (select count(*) from information_schema.tables where table_schema='public')  as public_left;"
# klid_at 에 테이블이 모이고 public_left 가 0 이어야 한다(사본이 남으면 위 ★★ 결함이 발생).

sudo systemctl start klid-backend
journalctl -u klid-backend -n 100 --no-pager | grep -iE 'flyway|schema|validat'
```

> **롤백**: ①에서 뜬 덤프를 빈 DB 에 `pg_restore` 하고 구 버전 jar 로 되돌린다.
>
> **⚠ 관제팀 협의 필요** — 데이터마트 뷰 4종(`V_COMPLETED_VIDEO`/`_FRAME`/`_LABEL_CHANGE`/`_META`)이
> `public` 에서 `klid_at` 으로 옮겨간다. 관제서버가 이 뷰를 직접 SELECT 하므로 **관제 측 조회도
> 함께 바뀌어야 한다.** 이관 시점을 관제팀과 맞추지 않으면 관제의 데이터마트 적재가 멈춘다.

### 2-6. 디스크 부족 (저장소·로그)

```bash
df -h /opt/klid /var/lib/pgsql /var/log/klid "$STORAGE_RAW_PATH"   # 앱·DB·로그·저장소
du -sh "$STORAGE_RAW_PATH"/* 2>/dev/null | sort -h | tail          # 저장소 상위 용량
journalctl --disk-usage                                           # journald 사용량
sudo journalctl --vacuum-time=14d                                 # 오래된 로그 정리(정책에 맞게)
```

- 영상/프레임 저장소(`STORAGE_RAW_PATH`·`STORAGE_DEIDENTIFIED_PATH` 설정값, 기본 `/nas-storage` 또는 마운트 NAS)는 용량 산정(이미지 10만 장·영상 5,000건 기준)을 초과하지 않도록 주기 점검한다. 앱 런타임 데이터 `/var/lib/klid`, DB 데이터 `/var/lib/pgsql/16/data` 는 별도 경로다.

---

## 3. 로그 확인 (공통)

```bash
# 실시간 추적
journalctl -u klid-backend   -f
journalctl -u klid-ai-server -f

# 특정 시간대 / 에러만
journalctl -u klid-backend --since '1 hour ago' -p err --no-pager

# 앱 파일 로그(파일 로깅 활성 시)
ls -al /var/log/klid
```

> 로그에는 민감정보 마스킹이 적용된다(개인정보·토큰·JWT 평문 미출력). 로그를 외부로 반출할 때도
> 마스킹 정책을 확인한다. 추적ID(`traceId`)로 하나의 요청 흐름을 로그·외부 호출에 걸쳐 추적할 수 있다.

---

## 4. 재시작 / 정지 / 설정 반영

```bash
# 단일 재시작
sudo systemctl restart klid-backend

# 전체 정지(역순 권장: frontend → backend → ai-server)
sudo systemctl stop klid-frontend klid-backend klid-ai-server

# 전체 기동(의존 순서)
sudo systemctl start klid-ai-server klid-backend klid-frontend

# 환경설정 변경 반영 — /etc/klid/*.env 수정 후 해당 서비스 재기동
sudo systemctl restart klid-backend        # 또는 klid-ai-server
```

### 4-1. 관리자 세션 토큰 비상 무효화

연동 서버 주소를 바꿀 때 쓰는 관리자 단기 유효창 토큰(`X-Admin-Session`)은 **무상태 서명 토큰**이라
개별 폐기 목록이 없다(설계 제약 — 공유 저장소·새 테이블을 두지 않기 위한 선택). 유출이 의심되면
**아래 둘 중 하나로 즉시 전량 무효화**한다. 어느 쪽이든 이미 발급된 토큰이 전부 검증에 실패한다.

| 수단 | 방법 | 영향 범위 |
|---|---|---|
| 토큰 페이로드 버전 상향 | `AdminSessionTokenService` 의 `PAYLOAD_VERSION` 을 올려 재배포 | 관리자 세션 토큰만 무효화 (로그인 세션 영향 없음) |
| JWT 서명 키 회전 | `/etc/klid/backend.env` 의 `JWT_SECRET` 교체 후 `sudo systemctl restart klid-backend` | 관리자 세션 토큰 + **관제/포털 인계 JWT 전부** 무효화 — 발급 주체와 협의 필요 |

> 토큰 유효기간이 기본 10분·상한 30분이라 **방치해도 그 시간 안에 자연 만료**된다. 위 조치는
> 그 시간을 기다릴 수 없을 때만 쓴다. 무효화 후에는 운영자가 관리자 패스워드로 창을 다시 연다.

---

## 5. 정기 점검 체크리스트 (권장 주기)

| 주기 | 점검 항목 | 명령/방법 |
|---|---|---|
| 일 | 4개 서비스 active·헬스 200/UP | 1-1·1-2 |
| 일 | 배치 상태별 건수(PENDING/FAILED 적체) | 1-4 |
| 일 | 디스크 여유(저장소·로그) | 1-5 |
| 주 | 스케줄러 클러스터 노드 수·노드 간 시계 동기(NTP) | 1-4-1 |
| 주 | 비식별 미완료('F') 잔량·조치 | 1-4·2-4 |
| 주 | 외부 연동 헬스(DOWN 컴포넌트) | 1-2·2-3 |
| 주 | 오래된 로그 정리 | 2-6 |
| 월 | GPU 사용률 추이·추론 병목 | 1-5·2-2 |
| 월 | DB 백업·복구 리허설 | [10-backup-dr.md](10-backup-dr.md) §5 |

> **백업/복구·DR**: PostgreSQL(`klid_system`·`portal`)·설정(`/etc/klid`)·저장소(`/nas-storage`)의 백업 대상·주기·복원·전체 서버 DR 절차는 [10-backup-dr.md](10-backup-dr.md)를 따른다. RPO/RTO 목표값은 운영 정책으로 확정한다.
