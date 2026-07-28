# 04. 환경설정

환경설정은 systemd `EnvironmentFile` 로 로드되는 두 파일이다.

- `/etc/klid/backend.env`  (템플릿: `config/backend/env.template`)
- `/etc/klid/ai-server.env` (템플릿: `config/ai-server/env.template`)

> 형식은 `KEY=VALUE`(한 줄 1개, 따옴표/셸확장 없음). 비밀번호 포함 → `chmod 640`, `root:klid`.

---

## A. backend.env 항목표

출처: `backend/src/main/resources/application.yml` + `application-prd.yml`(+ local).
★=필수, ·=선택/기본값 사용 가능.

### 기본 / DB / 보안

| 변수 | 필수 | 의미 / 기본 |
|------|:----:|-------------|
| `SPRING_PROFILES_ACTIVE` | ★ | 운영 권장 `prd`. local 은 LocalProfileGuard 가 비-local 호스트에서 거부 |
| `CONTROL_DB_HOST/PORT/NAME` | ★ | control DB(klid_system). prd 가 jdbc-url 조립. 스키마는 Flyway 자동 생성(D 절) |
| `CONTROL_DB_USERNAME/PASSWORD` | ★ | control DB 자격 |
| `PORTAL_DB_HOST/PORT/NAME` | ★ | portal DB |
| `PORTAL_DB_USERNAME/PASSWORD` | ★ | portal DB 자격 |
| `JWT_SECRET` | ★ | HS256 검증 시크릿(≥32B). 미설정 시 부팅 실패 |
| `JWT_ISSUER` / `JWT_ALLOWED_ISSUERS` | · | 기본 `klid-auth` / `klid-auth,klid,klid-portal` |
| `STREAM_SIGN_SECRET` | ★ | 영상 스트림 서명 시크릿(JWT_SECRET 과 다른 ≥32B). 미설정 시 스트리밍 fail-closed |
| `STREAM_URL_TTL_SECONDS` | · | 기본 60 (5~600) |
| `ADMIN_CLAIM_PASSWORD_HASH` | ★ | 관리자 공유 패스워드 BCrypt 해시(cost≥12). 평문 금지 |

### prd·stg 필수 webhook 콜백 보안 (★ 부팅 차단 주의)

| 변수 | 필수 | 의미 |
|------|:----:|------|
| `WEBHOOK_HMAC_SECRET_VLM` | · (미사용) | VLM describe 콜백은 벤더 v2.0.1 **무서명** 규격이라 HMAC 을 쓰지 않는다. 현재 코드에 **소비처가 없어** 설정해도 무시되고, 미설정이어도 부팅을 막지 않는다 |
| `WEBHOOK_HMAC_SECRET_AUGMENT` | ★(prd) | 미설정/빈 값이면 `BeanInitializationException` 으로 부팅 실패. 32B(256bit) 미만이거나 **리포에 커밋된 placeholder 값**이면 부팅 차단(공개 키 서명 위조 차단, DEV_FIX H-1) |
| `WEBHOOK_HMAC_TIMESTAMP_WINDOW` | · | 기본 300 |
| `WEBHOOK_TRUSTED_PROXY_CIDRS` | ★(prd·stg) | 신뢰 프록시 CIDR(CSV). 이 대역에서 온 요청의 `X-Forwarded-For` 만 해석. **직접 노출이면 `none` 명시**. 미설정 시 부팅 차단. **형식 오류(오타·호스트명)도 부팅 차단**(DEV_FIX N-4 — 조용히 무시하면 방어가 꺼진 채 기동) |
| `WEBHOOK_VLM_ALLOWED_IP_CIDRS` | ★(prd·stg) | VLM 무서명 콜백 허용 출처 CIDR(CSV). **미적용을 의도하면 `none` 명시**. 미설정 시 부팅 차단. 형식 오류도 부팅 차단 |

> 외부 증강 콜백을 받지 않더라도, prd 로 기동하려면 `WEBHOOK_HMAC_SECRET_AUGMENT` 에 **임의의 강한 시크릿**을 채워야 부팅된다.
> 생성: `openssl rand -hex 32`. 예시 문서의 값을 복사해 쓰지 말 것(공개 키 = 위조 가능).
>
> **stg 도 명시 필수(REDESIGN R-3)**: stg 는 온프렘 개발서버로 LB/Nginx 뒤에 배포되는데 과거에는 명시 강제가
> prd 에만 걸려 있어 stg 가 빈 값으로 조용히 기동했다(모든 콜백 IP 가 LB IP 로 수렴). 이제 stg 도 미설정 시 부팅이 차단된다.
>
> **`WEBHOOK_TRUSTED_PROXY_CIDRS` 를 비워 두면 안 되는 이유**: LB/Nginx 뒤 배포에서 미설정이면 모든 콜백의
> 클라이언트 IP 가 LB IP 하나로 수렴한다. 그 상태에서 누군가 인증 실패 5회를 유발하면 **정상 벤더 콜백까지
> 60초 동안 429** 가 되고, 공유 카운터를 통해 2노드 전체로 전파된다. 프록시가 없다면 `none` 을 명시해
> "XFF 무시" 를 의식적으로 선택한다.

### 저장소 / ai-server / CORS / FFmpeg

| 변수 | 필수 | 의미 / 기본 |
|------|:----:|-------------|
| `STORAGE_RAW_PATH` | ★ | 원본 영상·프레임 저장 베이스. **NAS 마운트 루트와 일치 필수**(기본 `/nas-storage`). v2 가 DB 절대경로를 이 베이스 기준 startsWith 가드로 검증해 서빙 — 관제 적재·v1 이관본(`/nas-storage/...`)이 이 베이스로 시작해야 정상 서빙(불일치 시 NOT_FOUND) |
| `STORAGE_DEIDENTIFIED_PATH` | ★ | 비식별 영상·프레임 저장 베이스(기본 `/nas-storage`). 위와 동일 — NAS 마운트 루트와 일치 |
| `STORAGE_RAW_MOUNT_ROOTS` | ★ | **산출물 쓰기 allowlist**(콤마 구분, 기본 `/nas-storage`). 검수 승인 산출물·비식별 영상은 `dirname(RAW_FILE_PATH_NM)/{RAW_SN}/` 에 생성되며, 그 base 가 이 목록 하위일 때만 허용된다(위반 시 폴백 없이 실패, CWE-22). `/` 등 파일시스템 루트를 넣으면 **기동 차단**. 실제 클립 서브트리를 확인해 최대한 좁게 지정 권장(예: `/nas-storage/data/clip/gov,/nas-storage/label-studio/src`) |
| `STORAGE_EXTERNAL_READ_ROOTS` | · | **외부 벤더 산출물 읽기 allowlist**(콤마 구분, 기본 빈 값). 생성형 AI(증강) 벤더가 결과 이미지를 자기 트리에 쓰고 그 절대경로를 콜백으로 주므로, 그 경로를 **쓰기 allowlist 에 넣지 않고** 이 읽기 축에만 추가한다(우리는 읽어서 파생 프레임으로 복사만 하며 이 트리에 쓰지 않는다 — 벤더 마운트는 `ro` 권장). 미설정이면 읽기 허용 범위 = `STORAGE_RAW_MOUNT_ROOTS` 와 동일이라 벤더 경로 콜백이 400 으로 거부되고 증강이 `PENDING` 에 머문다(fail-closed). `/` 지정 시 **기동 차단** |
| `AI_SERVER_URL` | ★ | 기본 `http://127.0.0.1:9300` |
| `CORS_ALLOWED_ORIGINS` | · | 동일 출처면 비움. 다른 도메인 호출 시 allowlist |
| `FFMPEG_BIN`/`FFPROBE_BIN`/`FFMPEG_THREADS` | · | 기본 `ffmpeg`/`ffprobe`/2 |
| `BATCH_ENABLED`/`BATCH_INTERVAL_SEC` | · | 기본 true/60 |
| `TRAINING_SCAN_ENABLED` | · | 관제 학습용 픽업 스캔. 공유 DB 없으면 false 권장 |
| `JAVA_OPTS` | · | 기본 `-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Duser.timezone=Asia/Seoul` |

> ⚠ **`STORAGE_RAW_PATH`/`STORAGE_DEIDENTIFIED_PATH` 변경 시**: 이 경로는 systemd 유닛의 `ReadWritePaths`(`ProtectSystem=full` 하 쓰기 허용 목록)에도 박힌다. env 만 바꾸면 쓰기가 차단되므로 **반드시 재설치(또는 유닛 갱신) 후 재로드**:
> ```
> sudo STORAGE_RAW_PATH=/새경로 ./scripts/install.sh   # 유닛 ReadWritePaths 재치환
> sudo systemctl daemon-reload && sudo systemctl restart klid-backend
> ```
> 또한 NAS 를 해당 경로로 **미리 마운트**해야 한다(설치는 부재해도 warn 후 계속되나, 서비스가 영상을 못 쓴다). DB 에 저장된 절대경로가 이 베이스로 시작해야 서빙된다(startsWith 가드).

---

## B. 외부 연동 경로 (★ 비식별은 KPST 동거 연동으로 확정)

backend 는 외부 시스템과 연동한다. **비식별(KPST)은 폐쇄망 동거 연동이 확정 정책**이고,
나머지(관제통지/VLM/증강)는 온프렘에 외부가 없으면 토글로 끈다.

| 외부 시스템 | 토글(env) | 설정 |
|-------------|-----------|------|
| **KPST 비식별(폴링)** | `KPST_DEID_ENABLED` | **항상 true(확정)** + base-url(+https면 CA). 끄거나 mock 우회 미지원 |
| 관제 outbound 통지 | `CONTROL_NOTIFY_ENABLED` | 외부 있으면 `true` + `CONTROL_NOTIFY_URL`, 없으면 `false`(기본) |
| 외부 VLM 시계열 | `VLM_CLIENT_ENABLED` | 외부 있으면 `true` + `VLM_SERVICE_URL`/`TOKEN`, 없으면 `false`(기본) |
| 외부 증강 | (콜백 수신, HMAC 시크릿만) | 콜백 안 받아도 HMAC 시크릿만 채워 부팅 통과 |

### ★ 비식별(KPST) — 동거 설치·연동 (확정)

비식별은 파이프라인 **선두 필수 단계**다. 운영 정책은 **① KPST 비식별 서버를 폐쇄망에 함께
설치하고 backend 가 폴링으로 연동**하는 것으로 확정됐다.

- **전제**: KPST 비식별 서버 자체는 **외부 시스템 — 본 설치 패키지에 포함되지 않는다.**
  고객이 폐쇄망에 KPST 를 별도 설치하고, 본 패키지는 backend ↔ KPST **연동 설정·절차만** 제공한다.
  (01-prerequisites.md "대상 서버 요구사항"에 KPST 접근 가능 전제가 추가돼 있다.)

- **연동 env**:

  | 변수 | 의미 / 기본 |
  |------|-------------|
  | `KPST_DEID_ENABLED` | **항상 `true`**(기본). 폴링 위탁 단일 경로 |
  | `KPST_DEID_BASE_URL` | 동거 KPST 주소. `https://IP:PORT`(CA 필수) **또는** `http://IP:PORT`(격리망 평문, CA 불요). 본 패키지 템플릿 기본값 `https://127.0.0.1:9201`(Spring 코드 기본은 `localhost`이나 `env.template`을 진실원으로 봄) → 실주소로 교체 |
  | `KPST_DEID_CA_CERT_PATH` | **https 일 때만 필수**. KPST 사설 CA(ca.crt) 경로. https 인데 비었거나 못 읽으면 **부팅 fail-closed**(CWE-295). http 면 비워둔다 |
  | `KPST_DEID_CREATOR_ID` | 기본 `authoring`. `/project` 호출 기본값 |
  | `KPST_DEID_REQ_USER_ID` | 기본 `authoring`. `/retrieve_progress` 호출 기본값 |
  | `KPST_DEID_EXPORT_PATH_BASE` | 기본 `/share/Deid-data/export/`. 비식별 결과 export 경로 베이스 |
  | `KPST_DEID_POLL_INTERVAL_SEC` | 기본 30. 폴링 주기(초) |
  | `KPST_DEID_POLL_MAX_ATTEMPTS` | 기본 240. 시도 횟수 타임아웃 |
  | `KPST_DEID_POLL_TIMEOUT_MINUTES` | 기본 180. 경과 시간 타임아웃(분) |
  | `DEIDENTIFY_API_URL` | 기본 `http://localhost:9200`. 실 비식별 호출엔 안 쓰이고 **헬스 인디케이터가 핑**하는 주소 — KPST 헬스 엔드포인트 또는 무해한 기본값으로 둔다 |

  > **https / http 분기**: base-url 스키마로 전송 방식이 자동 분기된다(KpstWebClientConfig).
  > `https://` → `KPST_DEID_CA_CERT_PATH` **필수**(미설정·읽기실패 시 부팅 실패). `http://`(격리망 평문) → CA **생략**.

- **⚠ 비식별 없이 부팅(mock/disable)은 prd 비권장·미지원**: prd 에는 mock/disable 우회 경로가 없다.
  `mock-mode=true` 자족 비식별은 application-local.yml 전용이고 `local` 프로파일은 `LocalProfileGuard`
  가 운영 호스트에서 거부한다. `KPST_DEID_ENABLED=false` 이고 mock 도 아니면 `DeidentifyStep` 이
  설정오류로 거부한다. **비식별을 끄면 PII 노출 + 마킹/프레임추출 차단**이므로 KPST 동거 연동을 반드시 갖춘다.

- **미연동 시 증상·진단**: 영상이 비식별 실패(`DE_IDENT_YN='F'`) 상태로 남고, 비식별 미완료라 마킹
  스트리밍/프레임추출이 차단된다. 부팅 거부·연결 실패 진단은
  [06-troubleshooting.md "비식별 설정오류 / KPST 연동"](06-troubleshooting.md) 참고.

---

## C. ai-server.env 항목표

출처: `ai-server/app/config.py`.

| 변수 | 필수 | 의미 / 기본 |
|------|:----:|-------------|
| `AI_MOCK_MODE` | · | 기본 false(실제 추론). true=고정 mock |
| `AI_DEVICE` | ★ | `cpu` (GPU 없음) |
| `YOLOX_WEIGHTS_PATH` | · | 기본 `/opt/klid/ai/weights/yolox_s.onnx` (탐지 YOLOX 단일 백엔드, ONNX Runtime) |
| `SAM2_MODEL_ID` | · | 기본 `facebook/sam2-hiera-tiny`(SAM2 사용 시 HF 캐시 필요) |
| `VLM_MODEL_NAME` | · | 기본 `openai/clip-vit-base-patch32` |
| `HF_HOME` | · | 오프라인 HF 캐시(기본 `/opt/klid/ai/.hf-cache`) |
| `HF_HUB_OFFLINE`/`TRANSFORMERS_OFFLINE` | · | 폐쇄망 권장 1 (SAM2 캐시 완비 시) |
| `MAX_IMAGE_SIZE_MB` | · | 기본 10 (1~100) |
| `CORS_ALLOW_ORIGINS` | · | 기본 `http://127.0.0.1:8080`(env.template, 동일 호스트 backend). 다른 호스트 분리 시 해당 주소로 조정 |

> 포트(9300)는 코드 고정(uvicorn `--port 9300`). config.py 에 PORT 설정 없음.

---

## D. DB 준비

### PostgreSQL 엔진 — 번들 vs 외부 (설치 토글)

| 토글(install.sh 인자) | 의미 |
|-----------------------|------|
| `USE_BUNDLED_POSTGRES=1` (기본) | 번들 PG16 을 오프라인 설치(`10-install-postgresql.sh`): RPM 설치 + initdb + `postgresql.conf`(`listen_addresses`/port) + `pg_hba.conf`(127.0.0.1/::1 scram-sha-256) + `postgresql-16` 기동 |
| `USE_BUNDLED_POSTGRES=0` | 외부(기존) PG 사용 — 번들 PG 설치 생략. `CONTROL_DB_*`/`PORTAL_DB_*` 가 그 PG 를 가리키게 둠 |

> 동일 호스트가 아닌 외부 PG 면 `pg_hba.conf`/`listen_addresses` 는 그 PG 운영 주체가 관리한다.
> 번들 PG 를 다른 대역에서 접속시키려면 `PG_HBA_EXTRA_CIDR=10.0.0.0/8 PG_LISTEN_ADDRESSES='*'` 등으로
> `10-install-postgresql.sh` 에 주입한다.

### 스키마는 Flyway 가 자동 부트스트랩 — 관제 스키마 사전 적재 불필요

- **테이블/스키마는 backend 가 기동 시 Flyway 로 자동 생성**한다(`spring.flyway.enabled=true`, prd 포함).
  V2 마이그레이션이 LS_*·**MNG_***·**QRTZ_*** 전 스키마를 `CREATE TABLE IF NOT EXISTS` 로 만든다.
  `ddl-auto=validate` 는 Flyway 가 만든 스키마를 검증만 한다. **별도 DDL 실행 불필요.**
- 따라서 **관제 없는 폐쇄망의 신규 빈 DB 면 저작도구가 MNG_*/QRTZ_* 까지 전부 자동 생성**한다 —
  관제 스키마를 사전 적재할 필요가 없다. 관제가 이미 채운 공유 테이블이 있는 경우에만 `IF NOT EXISTS`
  로 그대로 공유한다.
- DBA(또는 `15-init-db.sh`)가 준비할 것은 **빈 DB 2개 + 앱 유저·비밀번호**뿐(backend.env 와 일치):
  control(`klid_system`) / portal(`portal`).
- DB·유저 자동 생성 보조: `sudo DB_INIT_RUN=1 PGUSER=postgres PGPASSWORD=... DB_APP_PASSWORD=... ./scripts/install/15-init-db.sh`
  (번들 PG 를 같은 호스트에 설치했다면 `PGHOST=127.0.0.1`. 테이블은 만들지 않음 — backend Flyway 담당.)

## E. 시크릿 생성 치트시트

```bash
openssl rand -base64 48          # JWT_SECRET / STREAM_SIGN_SECRET
openssl rand -hex 32             # WEBHOOK_HMAC_SECRET_*
htpasswd -bnBC 12 "" '평문' | tr -d ':\n'   # ADMIN_CLAIM_PASSWORD_HASH (BCrypt)
```

## F. 프록시 설정

- 기본: `config/frontend/Caddyfile.template` → 설치 시 `/opt/klid/web/Caddyfile`. `:80` SPA + `/api/*`→127.0.0.1:8080.
  폐쇄망이라 Caddy 자동 HTTPS 는 끔(`auto_https off`). 외부 노출 시 사내 TLS 종단을 앞단에.
- 대안: `USE_NGINX=1` 설치 시 `config/frontend/nginx.conf.template`(proxy_pass 127.0.0.1:8080) 배치.

---

## G. 관제서버 미기동 브링업 (개발/임시)

관제서버가 아직 안 떠 토큰 인입(JWT)·학습용 영상 적재가 불가능한 초기 브링업 단계에서, dev 토글로
임시 로그인과 테스트 영상 업로드를 켜 파이프라인을 검증할 수 있다.

> ⚠ **dev 로그인은 인증 없이 임의 role 토큰을 발급한다.** 운영 정상화 후 반드시 아래 세 토글을 모두
> 미설정(OFF)으로 원복하고 backend 를 재기동한다. 온프렘 FE 번들은 dev 라우트를 dist 에 포함하되
> 실제 게이팅은 BE 런타임 토글이 결정하므로(BE off 면 `/v1/dev/*` 404), env 원복만으로 차단된다.

1. `/etc/klid/backend.env` 에 세 토글 설정 후 재기동:
   ```
   DEV_LOGIN_ENABLED=true
   DEV_UPLOAD_ENABLED=true
   SPRING_SERVLET_MULTIPART_ENABLED=true   # ★ 안 켜면 업로드 415/파싱불가
   ```
2. 브라우저에서 `/dev/login` 진입 → **REVIEWER** 토큰 발급.
3. `/dev/autolabel-test` 에서 테스트 영상 업로드 → 파이프라인(선두 비식별 → 마킹대기) 진행 확인.
4. **운영 정상화 후**: 위 세 토글을 backend.env 에서 미설정(삭제/주석)하고 재기동 → dev 경로 차단.
