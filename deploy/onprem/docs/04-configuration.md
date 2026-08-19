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
| `ENV` | ★(stg·prd) | **배포 환경 표식**(`stg`/`prd`) — 프로파일과 독립된 축. 서버 잔존 `.env`·셸 환경이 `SPRING_PROFILES_ACTIVE` 를 `dev` 로 덮어도 이 값이 배포 표식이면 dev 편의 엔드포인트(`DevProfileGuard`)·Quartz 단일노드 허용(`QuartzClusteringGuard`)이 모두 **거부**된다. **배포 서버에서 비우면 이 방어축이 통째로 무력해진다** |
| `CONTROL_DB_HOST/PORT/NAME` | ★ | control DB(klid_system). prd 가 jdbc-url 조립. 스키마는 Flyway 자동 생성(D 절) |
| `CONTROL_DB_USERNAME/PASSWORD` | ★ | control DB 자격 |
| `DB_SCHEMA` | · | **저작도구 스키마. 기본 `klid_at`** — 보통 바꾸지 않는다. 커넥션 `currentSchema` / Flyway `schemas`·`default-schema` / Quartz `tablePrefix` / JPA `default_schema` 네 지점이 **이 값 하나**를 함께 읽는다. 설치 스크립트(`gen-schema-sql.sh`·`16-load-schema.sh`)도 **같은 변수명**을 쓴다 — 앱과 설치가 갈리면 "설치는 됐는데 앱이 빈 스키마를 본다"가 된다. **portal DB 는 대상 아님**(별개 물리 DB, `public` 유지) |
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

### 이중화(HA) — Quartz 클러스터링 (★ 부팅 차단 주의)

배포 토폴로지는 **주 서버 2노드 Active-Active** 다. Quartz 클러스터링을 켜지 않으면 두 노드가
`QRTZ_LOCKS` 로 조율되지 않아 **같은 트리거를 각각 발화**한다(관제 학습용 스캔 60s · KPST 폴링 30s ·
export sweep 600s). `@DisallowConcurrentExecution` 은 **스케줄러 인스턴스 내부에서만** 유효해 이를 막지 못한다.

| 변수 | 필수 | 의미 / 기본 |
|------|:----:|-------------|
| `QUARTZ_CLUSTERED` | ★(prd·stg) | `true` 고정. stg/prd 프로파일 기본값이 `true` 이며 **`false` 면 기동 거부**(`QuartzClusteringGuard`). 양 노드 동일 값 |
| `QUARTZ_CHECKIN_MS` | · | 클러스터 체크인 주기(ms, 기본 20000). 노드 장애 감지 지연과 직결 — 30초 이내 권장 |

> **1노드로만 운영하더라도 끄지 않는다** — 클러스터 모드는 단일 노드에서도 정상 동작한다(락 경합 상대가 없을 뿐).
> `SPRING_PROFILES_ACTIVE` 를 dev 로 낮춰 우회할 수도 없다: 가드가 `ENV=stg|prd` 표식을 독립 축으로 함께 본다
> (`DevProfileGuard` 와 동일 기준). **단 이 축은 `ENV` 가 실제로 주입돼야 작동한다** — `env.template` 이
> `ENV=prd` 를 제공하고 systemd 가 `EnvironmentFile=/etc/klid/backend.env` 로 프로세스 환경에 넣는다.
> stg 노드는 설치 후 `ENV=stg` 로 바꾼다. 비워 두면 프로파일 축만 남아 `dev` 로 뜬 배포 노드가 통과한다.

**★ 설치 전 체크리스트 — 노드 간 시계 동기(NTP)**

클러스터링은 노드 클럭이 맞는다는 전제 위에서 동작한다. 오차가 크면 misfire 오판·중복 발화·락 스톰이 발생한다.

- [ ] 두 노드 모두 `chronyd`(또는 `ntpd`) **활성**: `timedatectl` → `NTP service: active`
- [ ] 두 노드가 **동일 NTP 서버**를 바라봄: `chronyc sources`
- [ ] 시계 오차 **1초 이내**: `chronyc tracking` → `System time` offset 확인
- [ ] 두 노드 타임존 동일(`Asia/Seoul` — `JAVA_OPTS` 의 `-Duser.timezone` 과 일치)
- [ ] 두 노드 `QUARTZ_CLUSTERED=true` 동일 설정 + 동일 DB(`CONTROL_DB_*`) 를 바라봄
- [ ] 첫 기동은 **한 노드만** 먼저 올려 Flyway 마이그레이션 완료 확인 후 두 번째 노드 기동
- [ ] 기동 후 등록 노드 수 확인: `SELECT instance_name, last_checkin_time FROM qrtz_scheduler_state;` → 노드 수만큼 행

> QRTZ_* 테이블은 Flyway(V2)가 생성하고 클러스터 락 행은 V76 이 시딩한다(`SCHED_NAME='KlidAuthoringScheduler'`).
> 별도 사전 적재는 불필요하다.

### 저장소 / ai-server / CORS / FFmpeg

| 변수 | 필수 | 의미 / 기본 |
|------|:----:|-------------|
| `STORAGE_RAW_PATH` | ★ | 원본 영상·프레임 저장 베이스. **NAS 마운트 루트와 일치 필수**(기본 `/nas-storage`). v2 가 DB 절대경로를 이 베이스 기준 startsWith 가드로 검증해 서빙 — 관제 적재·v1 이관본(`/nas-storage/...`)이 이 베이스로 시작해야 정상 서빙(불일치 시 NOT_FOUND). **★ 운영 제약 — `{STORAGE_RAW_PATH}/data/upload/v2` 는 저작도구 내부 업로드 전용 디렉터리이며 관제가 클립을 놓아서는 안 된다**(아래 ⚠ 참조) |
| `STORAGE_DEIDENTIFIED_PATH` | ★ | 비식별 영상·프레임 저장 베이스(기본 `/nas-storage`). 위와 동일 — NAS 마운트 루트와 일치 |
| `STORAGE_RAW_MOUNT_ROOTS` | ★ | **산출물 쓰기 allowlist**(콤마 구분, 기본 `/nas-storage`). 검수 승인 산출물·비식별 영상은 `dirname(RAW_FILE_PATH_NM)/{RAW_SN}/` 에 생성되며, 그 base 가 이 목록 하위일 때만 허용된다(위반 시 폴백 없이 실패, CWE-22). `/` 등 파일시스템 루트를 넣으면 **기동 차단**. 실제 클립 서브트리를 확인해 최대한 좁게 지정 권장(예: `/nas-storage/data/clip/gov,/nas-storage/label-studio/src`). **★ 좁힐 때는 `STORAGE_RAW_PATH` 를 반드시 포함할 것 — 미포함 시 관리 화면 영상 업로드 기능이 비활성**(기동은 정상, `POST /v1/uploads` 등 TUS 엔드포인트가 503 + 기동 로그에 ERROR 1회). 업로드 파일은 `{STORAGE_RAW_PATH}/data/upload/v2/` 에 저장되는데 그 경로가 allowlist 밖이면 인입이 매 건 `REJECTED` 로 영구 종결되므로, 조용히 성공시키는 대신 기능을 닫는다 |
| `STORAGE_EXTERNAL_READ_ROOTS` | · | **외부 벤더 산출물 읽기 allowlist**(콤마 구분, 기본 빈 값). 생성형 AI(증강) 벤더가 결과 이미지를 자기 트리에 쓰고 그 절대경로를 콜백으로 주므로, 그 경로를 **쓰기 allowlist 에 넣지 않고** 이 읽기 축에만 추가한다(우리는 읽어서 파생 프레임으로 복사만 하며 이 트리에 쓰지 않는다 — 벤더 마운트는 `ro` 권장). 미설정이면 읽기 허용 범위 = `STORAGE_RAW_MOUNT_ROOTS` 와 동일이라 벤더 경로 콜백이 400 으로 거부되고 증강이 `PENDING` 에 머문다(fail-closed). `/` 지정 시 **기동 차단** |
| `AI_SERVER_URL` | ★ | 기본 `http://127.0.0.1:9300` |
| `CORS_ALLOWED_ORIGINS` | · | 동일 출처면 비움. 다른 도메인 호출 시 allowlist |
| `FFMPEG_BIN`/`FFPROBE_BIN`/`FFMPEG_THREADS` | · | 기본 `ffmpeg`/`ffprobe`/2 |
| `BATCH_ENABLED`/`BATCH_INTERVAL_SEC` | · | 기본 true/60 |
| `TRAINING_SCAN_ENABLED` | ★ | **인입 폴링**(`LS_DATA_INGEST` 픽업 → `LS_DATA_RAW` 적재). 기본 true — **반드시 켜 둘 것**. 적재 주체 반전 이후 관제 인입분과 관리 화면 자체 업로드분이 **모두** 이 잡을 통해서만 적재된다(구 안내 "공유 DB 없으면 false 권장"은 폐기). false 면 업로드는 200 을 받고도 인입 행이 영원히 `PENDING` 에 머물러 영상이 목록에 나타나지 않는다(기동 시 ERROR 로그 + 업로드 완료 응답 `X-Ingest-Status: PENDING_SCAN_DISABLED`) |
| `JAVA_OPTS` | · | 기본 `-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Duser.timezone=Asia/Seoul` |

> ⚠ **`{STORAGE_RAW_PATH}/data/upload/v2` 는 저작도구 내부 업로드 전용이다 — 관제가 이 디렉터리에 클립을 놓지 말 것.**
> 저작도구는 관제가 INSERT 한 인입 행(`LS_DATA_INGEST`)과 자기가 만든 인입 행을 **`RAW_FILE_PATH_NM` 의 부모 디렉터리가 이 경로인가**로만 구분한다(관제 행은 관제 NAS 경로를 가리킨다). 이 판별자는 위 운영 규약에만 기대며 코드로 강제되지 않는데, 소비자가 **되살리기**(취소한 클립 ID 재사용)와 **업로드 완료 시 기술메타 back-fill** 둘이라, 관제가 이 디렉터리에 클립을 놓으면 그 관제 인입 행이 저작도구의 쓰기 대상이 될 수 있다(관제 수신 원장 변조 — CWE-915). 클립은 반드시 이 디렉터리 **밖**에 둘 것.
>
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

## C-1. frontend 빌드 타임 변수 (VITE_*) — ★ 빌드 차단 주의

frontend 는 env 파일을 런타임에 읽지 않는다. **Vite 가 빌드 시점에 `import.meta.env.VITE_*` 를
정적 치환**하므로, 아래 값은 `dist` 를 만드는 순간 결정되며 **대상 서버에서 바꿀 수 없다**.
빌드 진입점은 서로 독립인 3곳이고, 셋 다 같은 값을 주입해야 한다.

| 진입점 | 주입 방법 |
|--------|-----------|
| 빌드머신 사전빌드 | `scripts/package/20-build-frontend.sh` 실행 시 환경변수로 주입 |
| 폐쇄망 대상서버 재빌드 | `scripts/install/build-from-source.sh` 실행 시 환경변수로 주입 |
| 컨테이너 이미지 | `frontend/Dockerfile` build stage `--build-arg` |

| 변수 | 필수 | 의미 / 기본 |
|------|:----:|-------------|
| `VITE_API_BASE_URL` | · | 기본 `/api/v1` (프록시가 backend 로 넘김) |
| `VITE_TOKEN_INGRESS` | · | 기본 `localStorage` (토큰 인계 채널). 값: `url`\|`cookie`\|`localStorage`\|`both`\|`all`. **기본값을 바꾸지 말 것** — `url`/`both`/`all` 은 JWT 를 URL 쿼리로 받는 채널을 열어 접근 로그·리퍼러 헤더·브라우저 히스토리에 토큰이 잔존한다(CWE-598). 관제/포털은 동일 origin 브라우저 저장소로 인계한다 |
| `VITE_CONTROL_LOGIN_URL` | ★ | **관제서버 로그인 페이지 절대 URL.** 예 `https://control.example.local/login` |
| `VITE_PORTAL_LOGIN_URL` | ★ | **포털 로그인 페이지 절대 URL.** 예 `https://portal.example.local/login` |
| `VITE_DEV_LOGIN_ENABLED` / `VITE_DEV_UPLOAD_ENABLED` | · | 온프렘 기본 true(FE 라우트만 포함). 실제 게이팅은 backend 토글 — H 절 참고 |

- **두 로그인 URL 은 `http://` 또는 `https://` 스킴을 포함한 완전한 URL**이어야 한다. 스킴이
  없으면 브라우저가 상대경로로 해석해 저작도구 자기 자신으로 되돌아온다.
- **미설정이면 빌드가 중단된다(fail-closed).** 저작도구는 자체 로그인 UI 가 없어 토큰 없음·만료
  (401) 시 상위 시스템으로 redirect 하는 것이 유일한 복귀 경로인데, 값이 비면 **에러 없이 아무
  반응도 없는 막다른 화면**이 되어 배포 후에야 드러난다. 산출물을 만드는 **3개 진입점 전부**가
  각각 독립으로 가드를 갖는다 — 한 곳만 막으면 나머지 경로로 빈 값이 빠져나간다.

  | 진입점 | 가드 |
  |--------|------|
  | `scripts/package/20-build-frontend.sh` | `require_upstream_login_urls`(`lib/common.sh`) |
  | `scripts/install/build-from-source.sh` | `require_upstream_login_urls`(`lib/common.sh`) |
  | `frontend/Dockerfile` 직접 `docker build` | build stage 의 `RUN test -n ...` (`npm run build` 직전) |

- 로컬/CI 에서 `frontend/` 디렉터리 안에서 직접 실행하는 `npm run build` 는 **의도적으로 가드
  대상이 아니다**(`.env.development` 의 빈 값이 정상 동작해야 하므로). 배포 산출물은 반드시 위
  3개 진입점 중 하나로 만든다.

```bash
# 예: 빌드머신 사전빌드
VITE_CONTROL_LOGIN_URL=https://control.example.local/login \
VITE_PORTAL_LOGIN_URL=https://portal.example.local/login \
  ./scripts/package/20-build-frontend.sh
```

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
  control(`klid_system`) / portal(`portal`). **스키마는 미리 만들지 않아도 된다** — Flyway
  (`create-schemas: true`) 또는 `db/schema.sql`(`CREATE SCHEMA` 포함)이 만든다. 앱 유저가 DB OWNER 면 충분.
- **★ 저작도구 객체는 `klid_at` 스키마에 생성된다**(`DB_SCHEMA`, 위 표). portal DB 는 대상이 아니며
  복제본 스키마는 `17-load-portal-schema.sh` 가 `public` 에 로드한다 — **두 DB 가 서로 다른 것이 정상**이다.
  psql 로 조회할 때는 `klid_at.` 로 한정하거나 `set search_path to klid_at;` 를 먼저 실행한다.
- **기존 DB(저작도구 객체가 `public` 에 있는 DB)는 배포 전에 스키마 이관이 필요**하다 →
  `09-operations-runbook.md` §2-5-1. 이관하지 않으면 앱이 빈 `klid_at` 을 보고 데이터는 `public` 에 남는다
  (오류가 아니라 조용한 분기). `16-load-schema.sh` 는 이 상태를 감지하면 로드를 거부한다.
- DB·유저 자동 생성 보조: `sudo DB_INIT_RUN=1 PGUSER=postgres PGPASSWORD=... DB_APP_PASSWORD=... ./scripts/install/15-init-db.sh`
  (번들 PG 를 같은 호스트에 설치했다면 `PGHOST=127.0.0.1`. 테이블은 만들지 않음 — backend Flyway 담당.)

## E. 시크릿 생성 치트시트

```bash
openssl rand -base64 48          # JWT_SECRET / STREAM_SIGN_SECRET
openssl rand -hex 32             # WEBHOOK_HMAC_SECRET_*
htpasswd -bnBC 12 "" '평문' | tr -d ':\n'   # ADMIN_CLAIM_PASSWORD_HASH (BCrypt)
```

## F. 프록시 설정

- 기본: `config/frontend/httpd-klid.conf.template` → 설치 시 `/etc/httpd/conf.d/klid-frontend.conf`. `:80` SPA + `/api/*`→127.0.0.1:8080(`BACKEND_ORIGIN` 으로 변경).
  폐쇄망이라 TLS 는 걸지 않는다. 외부 노출 시 사내 TLS 종단을 앞단에.
  ★ 프록시 응답 버퍼링을 끈다(`flushpackets=on`) — 기본값이면 스트리밍 응답이 클라이언트에 아무것도 가지 않다가 끊긴다.
- 대안: `USE_NGINX=1` 설치 시 `config/frontend/nginx.conf.template`(proxy_pass 127.0.0.1:8080) 배치.

---

## G. 최초 REVIEWER 만들기 (운영 부트스트랩)

신규 설치 직후에는 저작도구에 **역할을 가진 사용자가 한 명도 없다**. 최초 검수자(REVIEWER)는 아래
**정규 경로**로 만든다 — dev 토글(H 절)은 이 용도로 쓰지 않는다.

> 저작도구는 자체 로그인 UI 가 없다. 사용자는 **관제서버가 발급한 JWT** 를 인계받아 진입하고,
> 그 토큰에 `role` 클레임이 없으면 화면이 자동으로 `/role-claim`(권한 부여) 으로 안내한다.

1. `/etc/klid/backend.env` 에 **관리자 공유 패스워드 해시**를 설정하고 재기동한다(E 절 치트시트로 생성).
   ```
   ADMIN_CLAIM_PASSWORD_HASH=$2b$12$....   # BCrypt cost≥12. 평문이면 기동 실패
   ```
   미설정이면 이 API 는 **항상 401** 이라 아무도 역할을 받지 못한다.
2. 관제서버에 로그인한 뒤 저작도구로 진입한다(관제 토큰 인계). 역할이 없으므로 `/role-claim` 화면이
   뜬다.
3. **검수자 (REVIEWER)** 를 선택하고 1번의 **평문** 관리자 패스워드를 입력 → 제출. 성공하면 REVIEWER
   토큰이 즉시 발급되고 사용자 마스터 행(`LS_ACNT_USER`)도 이때 자동 등록된다(DBA 수동 INSERT 불필요).
4. 이후 사용자는 같은 화면에서 **작업자(WORKER)** 로 받거나, REVIEWER 가 `/manage` 사용자 관리에서
   부여한다.

> ⚠ **보안 — 인지·수용된 잔여 위험(2026-08-04 확정, 되돌리지 말 것).** 이 관리자 공유 패스워드를 아는
> 사람은 **누구나 REVIEWER**(사용자 관리·시스템 설정·검수 승인)가 된다. 즉 **이 패스워드의 관리
> 수준이 시스템 전체의 권한 경계**다 — 설치 담당자 외에 공유하지 말고, 유출 시 해시를 교체하고
> 재기동한다. "보안 강화" 명목으로 이 절차를 WORKER 전용으로 되돌리지 말 것(구 정책은 "기존 REVIEWER
> 가 부여한다"를 전제했는데 신규 설치에는 그 REVIEWER 가 없어 부트스트랩이 성립하지 않았고, 그래서
> 이 문서가 dev 로그인을 부트스트랩으로 안내하는 더 위험한 상태였다).

함께 유지되는 방어(하나라도 빼면 위 개방이 성립하지 않는다):

| 방어 | 내용 |
|------|------|
| 시도 횟수 제한 | 계정당 **5회/분** + 엔드포인트 전역 **50회/분**, 초과 시 429. 카운터는 DB 공유 저장소(`LS_AUTHRT_GRANT_ATMPT`)라 **2노드 합산**으로 강제된다 |
| 패스워드 비교 | BCrypt 상수시간 비교(타이밍 관측 차단). 평문은 저장·로그 어디에도 남지 않는다 |
| 대상 게이트 | **INTERNAL 채널 + role 미보유** 토큰만 허용. 이미 역할이 있으면 409 |
| 채널 격리 | `PORTAL_USER` 는 400 으로 거절 — 포털 계정이 내부 역할로 상승할 수 없다 |

---

## H. 관제서버 미기동 브링업 (개발/임시)

관제서버가 아직 안 떠 **토큰 인입(JWT) 자체가 불가능**한 초기 브링업 단계에서만, dev 토글로 임시
로그인과 테스트 영상 업로드를 켜 파이프라인을 검증한다. 관제서버가 떠 있으면 이 절은 건너뛰고
**G 절**을 쓴다.

> ⚠ **dev 로그인은 인증 없이 임의 role 토큰을 발급한다.** 운영 정상화 후 반드시 아래 세 토글을 모두
> 미설정(OFF)으로 원복하고 backend 를 재기동한다. 온프렘 FE 번들은 dev 라우트를 dist 에 포함하되
> 실제 게이팅은 BE 런타임 토글이 결정하므로(BE off 면 `/v1/dev/*` 404), env 원복만으로 차단된다.

1. `/etc/klid/backend.env` 에 세 토글 설정 후 재기동:
   ```
   DEV_LOGIN_ENABLED=true
   DEV_UPLOAD_ENABLED=true
   SPRING_SERVLET_MULTIPART_ENABLED=true   # ★ 안 켜면 업로드 415/파싱불가
   ```
2. 브라우저에서 `/dev/login` 진입 → 임시 토큰 발급(파이프라인 검증에 필요한 역할 선택).
   **이 토큰은 브링업 검증용이며 최초 REVIEWER 를 만드는 정규 절차가 아니다** — 정규 절차는 G 절.
3. `/dev/upload` 에서 테스트 영상 업로드 → 파이프라인(선두 비식별 → 마킹대기) 진행 확인.
4. **운영 정상화 후**: 위 세 토글을 backend.env 에서 미설정(삭제/주석)하고 재기동 → dev 경로 차단.
   이후 실제 사용자 권한은 G 절 경로로 부여한다.
