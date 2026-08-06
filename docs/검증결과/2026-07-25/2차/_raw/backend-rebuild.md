# backend HEAD 재빌드 기록 (2026-07-31)

## 1. 재빌드 전 상태 (HEAD·이미지·git status)
- HEAD: `ca3c712b` (Merge PR #67 feat(mock): 워터마크 인코딩 GPU(NVENC) 경로)
- `git status --short`: 클린 (`docs/검증결과/2026-07-25/2차/_raw/` untracked 디렉토리만 존재 — 본 산출물 위치)
- backend/ 최종 커밋: `95215bd8` (2026-07-30, PR #66 목업 산출물 UI + 외부연동 3곳 논블로킹화)
- 재빌드 전 기존 이미지: `klid-backend:latest` ID `5b1ccd1e693f` (구버전, 2026-07-30 20:22 KST 빌드 — HEAD 대비 11개 커밋 뒤처짐, PR #66/#67 미반영분)
- ⚠ 러닝 스택의 compose `config_files` 라벨이 `/Users/ck/orca/workspaces/klid-label/issue-0730/...`를 가리켰는데 해당 워크트리 디렉토리는 이미 삭제되어 존재하지 않았음. compose project 이름은 `klid-label`로 고정돼 있어(디렉토리 유래 아님) 본 워크스페이스(`verify-tc`)에서 `-p klid-label -f docker-compose.yml -f docker-compose.local.yml`로 동일 프로젝트를 정확히 타겟팅해 진행함.
- `.env`(gitignore 대상)가 verify-tc에 없어 현재 실행 중이던 `klid-backend` 컨테이너의 실 env 48개를 `docker inspect`로 추출해 재구성 — `docker compose config klid-backend` 출력이 기존 런타임 env와 완전히 일치함을 확인 후 진행(env drift 없음).

## 2. 빌드 결과 (성공/실패 · 소요시간 · 새 이미지 ID/시각)
- **성공**. `docker compose -p klid-label -f docker-compose.yml -f docker-compose.local.yml build klid-backend`
- Gradle 스테이지(`clean bootJar -x test`) 소요 약 41초, 전체 이미지 빌드(레이어 캐시 포함) 약 1~2분
- 새 이미지: `klid-backend:latest` ID `bdc64ea2ac26` (full: `sha256:bdc64ea2ac26e989472c1ca726641d8e06453a240bb36d12574a86a9c00d99fd`), 생성시각 `2026-07-31 02:42:35 +0900 KST`
- 호스트에서 동시에 별도 gradle 테스트(`cleanTest test --continue`, PID 76364/82176/77320)가 돌고 있었으나 Dockerfile 멀티스테이지 빌드는 호스트 `build/` 디렉토리와 격리되어 있어 충돌 없이 완주함(호스트 gradle 은 건드리지 않음)

## 3. 기동·헬스
- `docker compose -p klid-label -f docker-compose.yml -f docker-compose.local.yml up -d klid-backend` 실행
- ⚠ **의도치 않게 `klid-postgres` 컨테이너도 함께 Recreate 됨**(config-hash 변화로 compose 가 판단, 원인 미상 — 명시적으로 postgres 를 지정하지 않았음에도 recreate 대상에 포함). **볼륨은 named volume `klid-label_klid-pgdata` 를 그대로 재사용**했으므로 컨테이너 재생성 자체가 데이터 삭제로 이어지지 않음(§5 확인).
- `klid-backend` 컨테이너: 10초 만에 `healthy` 전환 (02:43:31 starting → 02:43:41 healthy)
- `curl -s localhost:18081/api/actuator/health` → `{"status":"UP","groups":["liveness","readiness"]}`
- 부팅 로그: 에러 없음. `AuthoringApplication` 26.6초 기동, Quartz 스케줄러 정상 기동, `DevSeedRunner` 시드 적용, `ActiveAugmentClientLogger` 가 `HttpExternalAugmentClient` 활성 확인
- 잔존 docker build 프로세스 없음(재검 완료)

## 4. Flyway (최종 version, 신규 적용 목록, 실패 유무)
- 최종 `version=146` (`add ls data raw child fk`), `success=t`
- 재빌드 전/후 flyway_schema_history 최신 5건 완전 동일 → **신규 마이그레이션 없음**(HEAD 소스에 v146 이후 신규 SQL 파일이 없었던 것으로 해석). 실패 마이그레이션 없음.

## 5. DB 데이터 보존 확인
- `public.ls_data_raw` 행수: 재빌드 전 **38건** → 재빌드 후 **38건** (동일, 유실 없음)
- postgres 컨테이너가 recreate 됐음에도 named volume(`klid-label_klid-pgdata`)이 유지되어 데이터 그대로 보존됨을 재확인함

## 6. 외부연동 배선 재확인
- `KPST_DEID_BASE_URL=http://klid-mock-server:9400`
- `VLM_SERVICE_URL=http://klid-mock-server:9400`
- `AUGMENT_API_BASE_URL=http://klid-mock-server:9400`, `AUGMENT_EXTERNAL_MODE=http`
- 부팅 로그 `[Augment] active ExternalAugmentClient=HttpExternalAugmentClient` 로 mode=http 실배선 확인
- 전부 재빌드 전 값과 동일 유지(§1의 env 재구성이 정확했음을 재확인)

## 7. 2차 검증에 대한 함의 (이제 어느 커밋 기준으로 판정되는가)
- `klid-backend:latest` 이미지가 이제 **HEAD `ca3c712b`** 소스로 재빌드되어 배포됨(구 이미지 대비 11개 커밋 반영 완료 — 경로순회/필터 fail-open/405 정규화 `476bc91a`, 파생영상 프레임 서빙 심링크·TOCTOU 하드닝 `46f47cee`, 배치 트랜잭션 경계 복원 `09376333`, 외부연동 논블로킹화 `862ca6d8` 등 포함)
- 2차 전수검증은 이 시점부터 **HEAD 기준**으로 판정 가능. 구버전 이미지로 인한 거짓 FAIL 우려 해소.
- 단, postgres 컨테이너가 재생성된 점은 절차 위반이었으나 named volume 재사용으로 실질 데이터 손실은 없었음(§5) — 향후 동일 작업 시 `up -d klid-backend`가 다른 서비스까지 recreate 대상에 포함시키지 않는지 `--no-recreate` 나 dry-run(`--dry-run`) 확인을 선행할 것을 권고.
