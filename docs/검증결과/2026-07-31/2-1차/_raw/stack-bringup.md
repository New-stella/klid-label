# 풀스택 기동 결과 (2-1차 사전 준비)

- 대상 커밋: ca3c712b (main, HEAD, working tree clean)
- 실행 일시: 2026-07-31 (KST 기준 오전, UTC 02:5x)

## 1. 빌드

```
docker compose -f docker-compose.yml -f docker-compose.local.yml build
```

- 최초 시도 2회는 `docker/dockerfile:1` syntax 이미지 resolve 시 `DeadlineExceeded`(레지스트리 네트워크 일시 장애)로 실패. `docker pull docker/dockerfile:1` 로 별도 확인 후 재시도하여 해결(코드/설정 문제 아님).
- 3차 시도(백그라운드 실행) 성공 — 4개 이미지(klid-backend, klid-ai-server, klid-mock-server, klid-frontend:dev) 모두 Built.
- 소요시간: 약 478초 (~8분)
- ai-server pip 의존성 리졸버 경고(`ERROR: pip's dependency resolver does not currently take into account...`) 1건 발생 — 빌드 자체는 성공(경고성, 결함 아님). 참고로 기록.
- `.env` 파일이 저장소에 없어(`.env.example`만 존재, gitignore 대상) 신규 생성 필요 — `CONTROL_DB_PASSWORD`/`PORTAL_DB_PASSWORD`(동일값)/`JWT_SECRET`/`STREAM_SIGN_SECRET`/`WEBHOOK_HMAC_SECRET_AUGMENT`는 `openssl rand -hex`로 생성, `WEBHOOK_TRUSTED_PROXY_CIDRS`/`WEBHOOK_VLM_ALLOWED_IP_CIDRS`는 `none`으로 명시(로컬 직접 노출 가정). 소스/설정 파일은 수정하지 않음(.env는 인프라 시크릿 파일이며 .gitignore 대상).

## 2. 기동

```
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d
```

- 소요시간: 약 17초 (이미지 빌드 완료 후 컨테이너 생성/기동만)
- 5개 서비스 모두 기동 확인: klid-postgres, klid-storage-init(1회성 init, Exited 정상), klid-mock-server, klid-ai-server, klid-backend, klid-frontend
- `docker compose ps` 기준 20~30초 내 전원 `healthy` 도달

| 서비스 | 상태 | 호스트 매핑 포트 |
|---|---|---|
| klid-postgres | healthy | 5432 |
| klid-mock-server (service: mock-server) | healthy | 127.0.0.1:9400 |
| klid-ai-server | healthy | 19300 (컨테이너 내부 9300, amd64 이미지를 arm64 host에서 에뮬레이션 실행 — 경고만, 정상 기동) |
| klid-backend | healthy | 18081 (컨테이너 내부 8080) |
| klid-frontend | healthy | 13000 (컨테이너 내부 5174) |

> 주의: 로컬 compose 는 호스트 포트를 문서 기본값(8080/9300/5173)이 아닌 위 매핑 포트로 노출한다(오버라이드 설정). 이후 단계 curl/브라우저 접근 시 위 포트 사용.

## 3. 헬스체크 실측

- mock-server: `GET http://localhost:9400/health` → `{"status":"ok"}` (200)
- mock-server: `GET http://localhost:9400/docs` → 200
- backend: `GET http://localhost:18081/api/actuator/health` → `{"status":"UP","groups":["liveness","readiness"]}` (200)
  - 최초 `/actuator/health`(context-path 미포함) 시도는 404 — `application.yml`의 `server.servlet.context-path: /api` 때문(정상 동작, 문서 경로 표기 시 유의)
- backend 로그: Flyway `Successfully applied 144 migrations to schema "public", now at version v146` 확인 — 마이그레이션 성공
- ai-server: `GET http://localhost:19300/docs` → 200
- frontend: `GET http://localhost:13000` → 200
- DB 스키마: `\dt public.*` 로 84개 테이블 확인(`ls_*`, `cm_code`, `flyway_schema_history` 등) — 문서상 우려된 `klid_at` 아닌 **`public` 스키마가 실제 사용됨을 재확인**(CLAUDE.md 서술과 실제 배포 형상 간 차이, 기존 알려진 사항)

## 4. 외부 연동 실배선 검증 (backend 컨테이너 실효 환경변수)

```
docker compose exec klid-backend env | grep -iE 'DEIDENTIFY|VLM|AUGMENT|CONTROL_NOTIFY|MOCK'
```

| 변수 | 실효값 | mock-server 지향 O/X |
|---|---|:---:|
| KPST_DEID_BASE_URL | http://klid-mock-server:9400 | O |
| DEIDENTIFY_MOCK_MODE | false | O (내부 self-fill 우회 없음 — 외부 mock-server 실경유 확인) |
| VLM_CLIENT_ENABLED | true | O |
| VLM_SERVICE_URL | http://klid-mock-server:9400 | O |
| VLM_ALLOW_INSECURE_URL | true | (local 프로파일 허용값, 정상) |
| AUGMENT_EXTERNAL_MODE | http | O |
| AUGMENT_API_BASE_URL | http://klid-mock-server:9400 | O |
| CONTROL_NOTIFY_ENABLED | true | O |
| CONTROL_NOTIFY_URL | http://klid-mock-server:9400 | O |
| WEBHOOK_CALLBACK_BASE_URL | http://klid-backend:8080/api | (콜백 수신 자기 주소, 정상) |
| WEBHOOK_HMAC_SECRET_AUGMENT | (32B hex, 신규 생성값) | 정상 설정됨 |
| WEBHOOK_VLM_ALLOWED_IP_CIDRS | none | 로컬 직접 노출 가정 명시값 |
| AI_MOCK_MODE(backend 컨테이너 내 노출값, ai-server 자체 설정 아님) | true | 참고용 — backend에는 무의미한 값이 상속되어 있으나 실제 ai-server 컨테이너의 AI_MOCK_MODE=false 로 별도 확인(§5) |

- **결함 후보 없음** — 모든 외부 연동 URL이 `klid-mock-server:9400`(컨테이너명)을 정상 지향, 내부 목모드 플래그(`DEIDENTIFY_MOCK_MODE`)도 `false`로 외부 무접촉 우회 없음 확인.
- 컨테이너 이름 해석: `docker compose exec klid-backend getent hosts klid-mock-server` → `172.20.0.3 klid-mock-server` (정상 DNS 해석 확인)
- mock-server 로그(`docker compose logs mock-server --tail 40`): 기동 로그 + `/health`, `/docs`, `/` 요청만 존재(헬스체크성). 백엔드로부터의 실제 인바운드(비식별/VLM/증강/통지) 호출은 아직 없음 — 파이프라인 미실행 상태이므로 정상(다음 단계 파이프라인 실구동에서 관찰 예정).

## 5. ai-server 실제 추론 모드 확인

```
docker compose exec klid-ai-server env | grep -iE 'AI_MOCK|AI_DEVICE|DETECTOR_BACKEND'
```
→ `AI_DEVICE=cpu`, `AI_MOCK_MODE=false`, `DETECTOR_BACKEND=yolo` — CPU 실추론 모드로 정상 오버라이드됨(문서 기대값과 일치).

## 6. 발견된 이슈 (결함 후보 — 수정하지 않고 기록만)

1. **호스트 포트 매핑이 문서(§3-1 절차서 기본값 8080/9300/5173)와 상이** — 실제는 18081/19300/13000. 로컬 override 설정 의도인지, 아니면 검증 문서/절차서 쪽 갱신이 필요한지 확인 필요(기능 결함은 아니며 문서 정합 이슈로 보고).
2. **backend 컨테이너에 `AI_MOCK_MODE` 등 ai-server 전용 환경변수가 상속되어 노출됨** — 실제 backend 로직에서 참조하지 않는 것으로 보이나(문서상 언급 없음), compose 환경변수 설계상 base .env 공유로 인한 노출이며 기능적 영향은 미확인. 참고 기록.
3. ai-server 이미지가 `linux/amd64`로 빌드되어 host(`linux/arm64`, Apple Silicon)에서 에뮬레이션 실행 경고 발생 — 기동/헬스체크는 정상이나 실추론 성능에 영향 가능성 있음(플랫폼 미스매치, 참고 기록).
4. 저장소에 `.env`가 없어 최초 기동 시 별도 생성이 필요했음(설계 의도상 정상 — `.gitignore` 대상, 결함 아님).

## 7. 최종 결론

**5개 서비스(klid-postgres, mock-server, klid-ai-server, klid-backend, klid-frontend) 모두 정상 기동(healthy) 확인.** 외부 연동(비식별/VLM/증강/관제통지) 실효 배선 전부 `klid-mock-server:9400` 정상 지향, 내부 목모드 우회 없음, DNS 해석 정상, DB 마이그레이션 144건 성공, 스키마 `public` 확인. 컨테이너를 내리지 않고 유지 — **다음 단계(파이프라인 실구동 및 케이스 검증) 진행 가능.**
