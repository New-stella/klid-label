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
| `CONTROL_DB_HOST/PORT/NAME` | ★ | control DB(klid_system). prd 가 jdbc-url 조립 |
| `CONTROL_DB_USERNAME/PASSWORD` | ★ | control DB 자격 |
| `PORTAL_DB_HOST/PORT/NAME` | ★ | portal DB |
| `PORTAL_DB_USERNAME/PASSWORD` | ★ | portal DB 자격 |
| `JWT_SECRET` | ★ | HS256 검증 시크릿(≥32B). 미설정 시 부팅 실패 |
| `JWT_ISSUER` / `JWT_ALLOWED_ISSUERS` | · | 기본 `klid-auth` / `klid-auth,klid,klid-portal` |
| `STREAM_SIGN_SECRET` | ★ | 영상 스트림 서명 시크릿(JWT_SECRET 과 다른 ≥32B). 미설정 시 스트리밍 fail-closed |
| `STREAM_URL_TTL_SECONDS` | · | 기본 60 (5~600) |
| `ADMIN_CLAIM_PASSWORD_HASH` | ★ | 관리자 공유 패스워드 BCrypt 해시(cost≥12). 평문 금지 |

### prd 필수 webhook HMAC (★ 부팅 차단 주의)

| 변수 | 필수 | 의미 |
|------|:----:|------|
| `WEBHOOK_HMAC_SECRET_VLM` | ★(prd) | application-prd.yml 이 `${...:?}` 로 강제 — 미설정 시 BeanCreationException 부팅 실패 |
| `WEBHOOK_HMAC_SECRET_AUGMENT` | ★(prd) | 동일 |
| `WEBHOOK_HMAC_TIMESTAMP_WINDOW` | · | 기본 300 |

> 외부 VLM/증강 콜백을 받지 않더라도, prd 로 기동하려면 위 둘에 **임의의 강한 시크릿**을 채워야 부팅된다.
> 생성: `openssl rand -hex 32`.

### 저장소 / ai-server / CORS / FFmpeg

| 변수 | 필수 | 의미 / 기본 |
|------|:----:|-------------|
| `STORAGE_RAW_PATH` | ★ | 원본 영상 경로(기본 `/var/lib/klid/storage/raw`) |
| `STORAGE_DEIDENTIFIED_PATH` | ★ | 비식별 영상 경로 |
| `AI_SERVER_URL` | ★ | 기본 `http://127.0.0.1:9300` |
| `CORS_ALLOWED_ORIGINS` | · | 동일 출처면 비움. 다른 도메인 호출 시 allowlist |
| `FFMPEG_BIN`/`FFPROBE_BIN`/`FFMPEG_THREADS` | · | 기본 `ffmpeg`/`ffprobe`/2 |
| `BATCH_ENABLED`/`BATCH_INTERVAL_SEC` | · | 기본 true/60 |
| `TRAINING_SCAN_ENABLED` | · | 관제 학습용 픽업 스캔. 공유 DB 없으면 false 권장 |
| `JAVA_OPTS` | · | 기본 `-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Duser.timezone=Asia/Seoul` |

---

## B. 외부 연동 경로 vs 자족 경로 (★ 핵심 결정)

backend 는 4개 외부 시스템과 연동할 수 있다. 온프렘에 외부가 없으면 토글로 끈다.

| 외부 시스템 | 토글(env) | 외부 있을 때 | 외부 없을 때(자족) |
|-------------|-----------|--------------|--------------------|
| 관제 outbound 통지 | `CONTROL_NOTIFY_ENABLED` | `true` + `CONTROL_NOTIFY_URL` | `false` (기본) |
| 외부 VLM 시계열 | `VLM_CLIENT_ENABLED` | `true` + `VLM_SERVICE_URL`/`TOKEN` | `false` (기본) |
| 외부 증강 | (콜백 수신, HMAC 시크릿만) | 시크릿 세팅 | 시크릿만 채워 부팅 통과 |
| KPST 비식별(폴링) | `KPST_DEID_ENABLED` | `true` + base-url(+https면 CA) | (아래 ★ 주의) |

### ★ 비식별(KPST) — prd 자족 기동 주의

비식별은 파이프라인 **선두 필수 단계**라 자족 기동에 가장 민감하다. 정리:

- **경로 ① 외부 연동(prd, KPST 있음)**: `KPST_DEID_ENABLED=true`,
  `KPST_DEID_BASE_URL=https://IP:PORT` + `KPST_DEID_CA_CERT_PATH=/path/ca.crt`(https 면 필수, fail-closed),
  또는 내부망 평문 `http://IP:PORT`(CA 불요). 가장 권장.

- **경로 ② 외부 없음(자족)**: application 코드상 자족 비식별(`integration.deidentify.mock-mode=true`)은
  **application-local.yml 에만** 정의돼 있다. 그리고 `local` 프로파일은 `LocalProfileGuard` 가
  비-local(운영) 호스트에서 부팅을 거부한다. 즉 **prd 로는 외부 비식별 없이 "통과"시키는 설정 경로가
  현재 코드에 없다.** `KPST_DEID_ENABLED=false` 이고 mock 도 아니면 `DeidentifyStep` 이 설정오류로 거부한다.
  → 따라서 외부 비식별이 전혀 없는 폐쇄망이라면 다음 중 하나가 필요하다:
    1. KPST(또는 동등) 비식별 서버를 폐쇄망 내에 함께 두고 경로 ①로 연동,
    2. 또는 backend 코드에 "prd 에서도 mock/disable 비식별을 허용"하는 설정을 추가(앱 변경 = 본 패키지 범위 밖, 후속).

  > **TODO(확인필요)**: 운영 정책상 폐쇄망에 비식별 서버를 함께 둘지(①), 아니면 비식별 토글을 prd 에서
  > 허용하도록 앱을 보강할지(②) 결정 필요. 본 설치 패키지는 코드를 바꾸지 않으므로 단정하지 않는다.

### KPST 관련 env

| 변수 | 의미 |
|------|------|
| `KPST_DEID_ENABLED` | 기본 true. 폴링 위탁 on/off |
| `KPST_DEID_BASE_URL` | `https://...`(CA 필수) 또는 `http://...`(평문, 격리망) |
| `KPST_DEID_CA_CERT_PATH` | https 일 때 필수(미설정·읽기실패 시 부팅 fail-closed) |
| `KPST_DEID_CREATOR_ID`/`REQ_USER_ID`/`EXPORT_PATH_BASE` | 호출 기본값 |
| `KPST_DEID_POLL_*` | 폴링 주기/타임아웃 |

---

## C. ai-server.env 항목표

출처: `ai-server/app/config.py`.

| 변수 | 필수 | 의미 / 기본 |
|------|:----:|-------------|
| `AI_MOCK_MODE` | · | 기본 false(실제 추론). true=고정 mock |
| `AI_DEVICE` | ★ | `cpu` (GPU 없음) |
| `DETECTOR_BACKEND` | · | `yolox`(기본, onnx CPU) \| `rtdetr`(HF 모델 필요) |
| `RTDETR_MODEL_ID` | · | 기본 `PekingU/rtdetr_v2_r50vd` |
| `YOLOX_WEIGHTS_PATH` | · | 기본 `/opt/klid/ai/weights/yolox_s.onnx` |
| `SAM2_MODEL_ID` | · | 기본 `facebook/sam2-hiera-tiny`(SAM2 사용 시 HF 캐시 필요) |
| `VLM_MODEL_NAME` | · | 기본 `openai/clip-vit-base-patch32` |
| `HF_HOME` | · | 오프라인 HF 캐시(기본 `/opt/klid/ai/.hf-cache`) |
| `HF_HUB_OFFLINE`/`TRANSFORMERS_OFFLINE` | · | 폐쇄망 권장 1 (rtdetr/sam2 캐시 완비 시) |
| `MAX_IMAGE_SIZE_MB` | · | 기본 10 (1~100) |
| `CORS_ALLOW_ORIGINS` | · | 기본 `http://127.0.0.1:8080`(env.template, 동일 호스트 backend). 다른 호스트 분리 시 해당 주소로 조정 |

> 포트(9300)는 코드 고정(uvicorn `--port 9300`). config.py 에 PORT 설정 없음.

---

## D. DB 준비

- 스키마/LS_* 테이블은 backend 가 기동 시 **Flyway 로 자동 생성·검증**(`spring.flyway.enabled=true`,
  `ddl-auto=validate`). 별도 DDL 실행 불필요.
- DBA 가 준비할 것: control/portal **DB·앱 유저·비밀번호**(backend.env 와 일치).
- 관제 공유 테이블(`MNG_*`/`QRTZ_*`)은 validate 로 참조만 한다. 온프렘 자체 DB 면 사전 준비 필요.
- 자동 생성 보조: `sudo DB_INIT_RUN=1 PGUSER=postgres PGPASSWORD=... DB_APP_PASSWORD=... ./scripts/install/15-init-db.sh`

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
