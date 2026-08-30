# 01. 사전 요구사항

## ★ 타깃 OS — 레드햇 엔터프라이즈 리눅스 8.9 (RHEL 8 계열, x86_64, glibc 2.28)

이 패키지는 **레드햇 엔터프라이즈 리눅스 8.9**(RHEL 8 계열, x86_64, glibc 2.28, dnf/rpm)를 대상으로 한다.
RHEL 8 은 8.0~8.10 전 버전이 glibc 2.28 로 고정이다. 시스템 의존성은 **RPM 기반**으로 수집·설치한다.

> ⚠ 구 서술 폐기(2026-08-28) — "타깃 OS = Rocky Linux 9 (RHEL 9 계열, glibc 2.34)".
> el9 RPM 은 RHEL 8 에 설치되지 않는다.

- pip wheel(torch **manylinux_2_28**)은 그대로 쓴다 — `manylinux_2_28` 태그가 곧
  **glibc 2.28 기준선**이라 RHEL 8.9 와 정확히 일치한다(재수집 불필요).
  ⚠ 구 서술 폐기(2026-08-30): "런타임(JRE17)·pip wheel …". **자바 런타임은 반입 대상이 아니다**
  (배포 형상이 외부 WAS 에 WAR 반입 — 아래 「대상 서버 요구사항」의 WAS 행 참고).
  ⚠ 구 근거 폐기: "glibc 2.34 호환이라 그대로 쓴다"(근거만 낡았고 결론은 그대로 참).
- **ffmpeg 는 대상 장비의 전제조건**이다 — **관제지원시스템 팀이 서버 A 에 설치**하며 우리 설치
  스크립트는 **자동 설치하지 않고 검증만** 한다(공동 배치 서버라 관제 설치본을 덮어쓰면 관제가 깨진다).
  예비물(RPM Fusion el8, 전이 의존 전량)은 매체에 동봉되어 있고 `install/install-ffmpeg.sh` 로
  **사람이** 설치한다 → `/usr/bin/{ffmpeg,ffprobe}`. 세 경로는 아래 「ffmpeg」 절 참고.
  ⚠ 구 동작 폐기(2026-08-30): "11단계가 번들 RPM 을 **자동으로** 오프라인 설치한다".
  ⚠ 구 방침 폐기(2026-08-28): "ffmpeg 는 **정적 바이너리**로 번들한다". 그 정적 빌드는 glibc 2.31
  기반이라 타깃(2.28)에서 `GLIBC_2.31 not found` 로 죽는다. 관제지원시스템과 같은 형상으로 맞췄다.
- **libGL(opencv)·glib2** 는 el8 AppStream 의 `mesa-libGL`/`libglvnd-glx`/`glib2` RPM 으로 제공된다.

## ★ 빌드머신 제약 — wheel/RPM 은 대상과 동일 OS/아키텍처에서 수집

폐쇄망 설치의 모든 의존성은 **빌드머신에서 수집**된다. 다음 산출물은 OS/아키텍처/glibc 에 강하게 묶인다.

- **pip wheel**: manylinux(glibc 버전 태그) + CPython ABI(cp311) 정합
- **시스템 RPM**(mesa-libGL 등): el8 정합 → **el8 컨테이너/머신에서 수집**
- **런타임 바이너리**(Python standalone): linux x86_64 빌드(OS 무관 tarball). **자바는 수집하지 않는다**
- **ffmpeg RPM**: el8 정합 → **el8 컨테이너/머신에서 수집**
  ⚠ 구 서술 폐기(2026-08-28): "ffmpeg 정적 바이너리는 OS 무관 — curl 만 있으면 mac 에서도 수집 가능".

따라서 **wheel·RPM·ffmpeg 수집 단계는 el8 (x86_64, glibc 2.28) 환경 + 인터넷**에서 수행해야 한다.
권장: `rockylinux/rockylinux:8` 컨테이너(또는 el8 머신)를 인터넷 가능 영역에 띄워 빌드머신으로 사용.
RPM 수집(50·55)과 node_modules populate 는 `dnf`/`npm` 이 없으면 **docker 로 el8 컨테이너를 자동
기동**하므로 mac 에서도 닫힌다. ⚠ 컨테이너는 반드시 `--platform linux/amd64` — Apple Silicon 에서
빠뜨리면 aarch64 RPM 을 받아 놓고 "성공"으로 끝나는 조용한 실패가 된다.
(jar/FE dist/런타임 등 OS 무관 산출물은 mac 등 다른 빌드머신에서 만들어도 된다 — 02 문서 구분표 참고.)

## 빌드머신 요구사항 (인터넷 필요)

> ★ **SAM2 모델을 반드시 함께 수집해야 한다** — 빌드머신에 `huggingface_hub` 가 설치돼 있어야 하며,
> 없으면 패키징이 중단된다(2026-08-19 기본값 반전). 예전에는 이 수집이 기본 생략이라 모델이 빠진
> 번들이 만들어졌고, 대상 서버는 오프라인이라 내려받지도 못해 **SAM2 분할·Track 이 mock 응답으로
> 동작**했다. 서버 기동·헬스체크·API 응답이 모두 정상이라 운영 중에는 드러나지 않는다.
> SAM2 를 쓰지 않는 납품이라면 `PREFETCH_HF=0` 으로 **명시적으로** 꺼야 한다.



| 항목 | 요구 | 확인 |
|------|------|------|
| OS/arch | wheel·RPM 수집은 **el8 x86_64**(또는 docker + `--platform linux/amd64`) | `uname -srm`, `cat /etc/os-release` |
| JDK | **17** (backend bootJar) | `java -version` |
| Node.js | **20** + npm (frontend build) | `node -v` |
| Python | **3.11** (pip download — cp311 wheel) | `python3.11 --version` |
| git | sam2 git 소스 clone | `git --version` |
| 기본 도구 | bash, curl, tar, xz | — |
| (el8) | dnf (+ `dnf-plugins-core` 의 `dnf download`, `createrepo_c`) | RPM 수집·로컬 저장소 생성용 |
| 디스크 | 약 10~15GB 여유 | torch CPU wheel·런타임이 큼 |

> JDK17/Node20/Python3.11 자체는 빌드머신에 미리 설치되어 있어야 한다(번들 대상 아님).
> 대상 서버 런타임 중 **Python 과 ffmpeg 만** package.sh 가 받아 번들한다. 웹 서버는 httpd RPM 으로 수집한다.
> ⚠ 구 서술 폐기(2026-08-30): "대상 서버 런타임(**JRE**/Python/ffmpeg)" — 자바는 대상 WAS 가 제공한다.
> RPM 수집(`ffmpeg`·`mesa-libGL` 등)은 **el8 컨테이너/머신**에서 해야 한다(`dnf download`).
> jar·FE dist·런타임 tarball 은 OS 무관이라 다른 빌드머신에서 만들어도 된다.
> ⚠ ffmpeg 는 2026-08-28 부터 RPM 이라 **OS 무관 목록에서 빠졌다**.

## 대상 서버 요구사항 (폐쇄망, 인터넷 불필요)

> ★ **대상 장비는 2대다** — 서버 A(`--role=app`: 프론트+백엔드)와 서버 B(`--role=ai`: ai-server).
> **둘 다 RHEL 8.9** 이며 매체는 하나다(설치 시 역할만 고른다). 아래 표에서 **[A]/[B]** 표기는
> 어느 서버의 요구인지를 뜻하고, 표기가 없으면 양쪽 공통이다.
> ⚠ 서버 B 에는 **파이썬조차 없다** — 런타임을 전부 반입한다.

| 항목 | 요구 |
|------|------|
| OS/arch | **레드햇 엔터프라이즈 리눅스 8.9** (RHEL 8 계열, x86_64, glibc 2.28) — **서버 2대 모두** |
| init | **systemd**. 유닛은 `httpd`·`postgresql-16`[A] / `klid-ai-server`[B] 뿐이다 — **백엔드는 유닛이 아니다**(외부 WAS 가 기동 주체) |
| **ffmpeg / ffprobe** | **[A] 전제조건 — 관제지원시스템 팀이 설치한다.** 우리는 설치하지 않고 검증만 하며, 없으면 **설치가 중단된다**. [B] 에는 필요 없다(ai-server 가 쓰지 않는다). 상세: [03-install.md](03-install.md) 「ffmpeg — 세 경로」 |
| **WAS** | **[A]** **Tomcat 10.1.x + Java 17 이 이미 설치·구동 중**이어야 한다(관제지원시스템 WAS 와 동일 사양, 실측 17.0.19). backend 는 `api.war` 로 이 WAS 에 반입한다 — **패키지는 자바 런타임을 반입하지 않는다**(@design DEPLOY-001 · RUNBOOK-001). WAS 설정 이관은 [10-was-settings.md](10-was-settings.md) |
| 권한 | 설치는 **root/sudo**. WAS 배포 디렉터리 쓰기 권한도 필요(사람이 복사) |
| CPU/GPU | **이번 반입은 CPU 전용이며 GPU 는 사용하지 않는다** (torch CPU 휠 + onnxruntime CPU). ⚠ **[B] 장비에는 GPU 가 있다** — "GPU 가 없다"가 아니라 "이번 반입이 GPU 를 쓰지 않는다"이다. 쓰려면 CUDA 휠·드라이버·`onnxruntime-gpu` 로 **재수집**해야 하며 이번 범위가 아니다. 적어 두지 않으면 나중에 "GPU 서버인데 왜 느린가"라는 형태로만 드러난다. ⚠ 구 서술 폐기(2026-08-30): "CPU only (GPU/CUDA **불필요**)" — 장비에 GPU 가 있는 것이 사실이므로 "불필요"는 틀린 서술이 됐다 |
| 메모리 | backend(JVM, MaxRAMPercentage 75%) + ai-server(torch CPU) 고려해 충분히(권장 ≥ 8GB) |
| 디스크 | 앱·런타임·모델 + 영상 저장소. 영상 규모에 비례(저장소 별도 산정) |
| PostgreSQL | **[A]** **16**. ① 번들 PG16 오프라인 설치(`USE_BUNDLED_POSTGRES=1`, 기본) **또는** ② 외부 기존 PG 사용(`=0`)+접속 정보(host/port/db/user/pw). 어느 쪽이든 **빈 DB 2개**(control=klid_system, portal)+사용자만 준비하면 됨. ⚠ **스키마는 설치 단계가 `db/schema.sql` 을 1회 로드해 만든다**(`16-load-schema.sh`) — 앱은 `spring.flyway.enabled=false` 로 마이그레이션을 돌리지 않고 매핑 검증만 한다. 구 서술 "스키마는 backend Flyway 가 자동 생성" 은 **폐기**(2026-08-19) — 설정 템플릿은 이전부터 `false` 였고, WAR 반입 형상에서는 DBA 가 선적용하므로 앱에 스키마 변경 권한이 없다 |
| **KPST 비식별 서버** | **폐쇄망에 별도 설치·접근 가능**해야 함(본 패키지 비포함, 외부 시스템). backend 가 폴링 연동(http 또는 https+사설CA). 비식별은 파이프라인 선두 필수 단계라 prd 에서 끄거나 mock 우회 불가 — 04-configuration.md B 절 참고 |
| 네트워크 | **[A]** 80/8080/5432 + KPST 비식별 포트(예 9201) 도달, **[A]→[B] 9300 도달**(서버가 나뉘므로 `AI_SERVER_URL` 의 기본값 `127.0.0.1:9300` 을 **반드시 서버 B 주소로 바꾼다**). 외부 인바운드는 [A] 의 80만 노출 권장 |

### 런타임 시스템 의존성(대상 서버 = RHEL 8.9)

ai-server/backend 가 런타임에 의존하는 것:

- **[A] backend: `ffmpeg`/`ffprobe`** → **install.sh 가 설치하지 않는다.** 관제지원시스템 팀이
  대상 장비에 설치하는 것이 원칙이고, 우리는 19단계에서 **검증만** 한다(없으면 설치 중단).
  관제가 설치하지 않는 것이 확인되면 매체의 예비물로 **사람이** 설치한다:
  `sudo ./scripts/install/install-ffmpeg.sh`
  ⚠ 구 서술 폐기(2026-08-30): "모두 패키지에 번들되어 install.sh 가 **오프라인 설치한다**" —
  ffmpeg 는 그 대상이 아니다.
  ⚠ 구 서술 폐기(2026-08-28): "정적 바이너리를 `/opt/klid/runtime/ffmpeg/bin/` 로 배치".
- **[B] ai-server: `libGL.so.1`**(opencv import) + glib2 → el8 RPM(`mesa-libGL`/`libglvnd-glx`/`glib2`,
  `syspkgs/rpm/`)을 **로컬 yum 저장소 방식으로 오프라인 설치**한다(11단계, 자동).
  ⚠ 이 둘을 "opencv 를 headless 판으로 내리면 필요 없다"며 빼지 말 것 — `supervision`·`trackers` 가
  `opencv-python`(GUI 판)을 **직접 의존해 되끌어오므로** `libGL.so.1` 의존이 그대로 남는다(실측).
- **[A] httpd·policycoreutils-python-utils** 도 같은 `syspkgs/rpm/` 저장소에서 설치한다(14단계).
  즉 **`syspkgs/rpm` 은 서버 2대 모두에 필요하다.**

> el8 base/AppStream 에 ffmpeg 가 없으므로 EPEL + RPM Fusion + PowerTools(CRB)에서 전이 의존성까지 전량 받아
> `syspkgs/ffmpeg/` 에 로컬 저장소로 **동봉**하고(설치는 수동), libGL/glib2 는 AppStream RPM 으로 처리한다.
> 번들이 비어 있으면 06-troubleshooting 참고.
>
> **라이선스** — 번들 ffmpeg 는 RPM Fusion 의 **GPLv3+** 빌드다. 매체에 담아 반입하는 것 자체가
> 재배포이므로 **대응 소스(SRPM)를 `syspkgs/ffmpeg-src/` 에 함께 반입**한다(설치 대상 아님).

### PostgreSQL 준비 — 두 경로

PG 는 **두 경로** 중 하나로 운영한다(설치 토글 `USE_BUNDLED_POSTGRES`):

1. **① 번들 PG16 오프라인 설치(기본, `USE_BUNDLED_POSTGRES=1`)** — 패키지에 PGDG PG16 RPM 을
   번들하고 `10-install-postgresql.sh` 가 오프라인 설치(initdb + `postgresql.conf`/`pg_hba.conf` +
   `postgresql-16` 서비스 기동)한다. 별도 PG 준비 불필요. (수집: `55-collect-postgresql.sh`)
2. **② 외부 기존 PG 사용(`USE_BUNDLED_POSTGRES=0`)** — 타깃에 이미 PostgreSQL 이 있으면 번들 PG 설치를
   건너뛰고, `backend.env` 의 `CONTROL_DB_*`/`PORTAL_DB_*` 가 그 PG 를 가리키게 둔다.

어느 경로든 사전요건은 동일하다:

- **빈 DB 2개** — control DB(`klid_system`)와 portal DB(`portal`), 앱 유저(`klid_user` 등)와 비밀번호.
- **스키마/테이블은 `db/schema.sql` 을 빈 control DB 에 1회 로드해 만든다** — **온프렘은 Flyway 를
  쓰지 않는다**(`spring.flyway.enabled=false`). 그 파일에
  `LS_*` 62개 · `QRTZ_*` 11개 · 뷰 4개 + 시드 66행이 들어 있고 `CREATE SCHEMA` 도 포함하므로,
  **사전요건은 빈 DB 와 앱 유저(DB OWNER)뿐**이고 `klid_at` 스키마를 미리 만들 필요는 없다.
- ⚠ **그 로드는 사람이 해야 한다** — 설치 단계 `16-load-schema.sh` 는 `SCHEMA_LOAD_RUN=1` 일 때만
  실제로 넣고 평시엔 수동 절차만 안내한다. **아무도 넣지 않으면 대신 만들어 주는 것이 없다.**
  구성별로 누가 언제 넣는지는 `03-install.md` 의 표가 정본이다.
- ⚠⚠ **★ 그런데 기동은 실패하지 않는다 — `ddl-auto=validate` 가 대신 막아 주지 않는다.**
  `application.yml` 에 `validate` 가 선언돼 있지만 이 저장소에서는 **실동작하지 않는다**:
  듀얼 데이터소스라 `JpaBuilderConfig` 가 `EntityManagerFactory` 를 직접 만드는데, 거기에 넘기는
  것은 `spring.jpa.properties.*` 뿐이라 `spring.jpa.hibernate.ddl-auto` 가 Hibernate 까지
  전달되지 않는다. 결과적으로 **테이블이 하나도 없어도 기동 로그는 깨끗하고**, 화면·배치가 그
  테이블을 처음 건드릴 때 `relation ... does not exist` 로 터진다.
  ⇒ **"기동됐으니 됐다"가 근거가 되지 않는다.** 아래 카운트 확인이 유일한 방어선이다.
  근거·상세는 `09-operations-runbook.md` §2-5-2 「왜 조용히 실패하나」.
  ```bash
  psql -h <HOST> -p <PORT> -U <APP_USER> -d klid_system \
       -c "select count(*) from information_schema.tables where table_schema='klid_at';"
  # 0 이면 로드가 안 된 것이다 — WAR 를 올리기 전에 db/schema.sql 을 먼저 넣는다.
  ```
- ⚠ 구 서술 폐기(2026-08-30) — *"아무도 넣지 않으면 backend 가 기동에 실패한다"*.
  기동은 성공한다. 그 기대에 기대면 **빈 스키마인 채로 운영에 넘어간다.**
- ⚠ 구 서술 "빈 DB 면 저작도구가 `MNG_*` 까지 전부 자동 생성" 은 **폐기**다 — `MNG_*` 공유 테이블은
  제거됐고 `db/schema.sql` 에 존재하지 않는다.
  → 03-install.md / 04-configuration.md DB 절 / 05-run-verify.md 참고.
