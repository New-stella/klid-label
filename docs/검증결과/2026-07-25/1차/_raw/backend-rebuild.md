# backend 재빌드 결과 — 2026-07-25 1차

## 재빌드 전 상태
- jar 빌드시각(컨테이너 내부 `/app/app.jar`): **2026-07-23 17:10** (mtime)
- 이미지: `klid-backend:latest` ImageID=`sha256:0e481547a749d...` Created=`2026-07-23T17:10:42Z`
- 컨테이너(`klid-backend`) 자체는 `2026-07-25 01:35:54Z`에 재생성(Recreate)된 이력이 있었으나, **이미지 ID가 그대로**여서 재생성 시점과 무관하게 낡은 이미지를 재사용하고 있었음(재기동 ≠ 재빌드였다는 근거)
- Flyway 최대 버전: **V129** (`129 | ls label add dtct type | t | 2026-07-23 23:29:43`)
- git HEAD: `27b6bb0d feat(label): 촬영환경·개인정보 메타 수동입력 + 검수 승인 export 전량 재생성`
  - 직전: `d251627d fix(export)...`, `194d0dab chore(seed)...`
- git status: 워크스페이스 다수 `??`(미추적) — 전부 `docs/` 하위 문서/스프레드시트/설계산출물이며 `backend/`, `ai-server/`, `frontend/` 소스 트리는 **깨끗함**(추적된 변경 없음, `.claude-plan.md`만 M). 즉 빌드에 영향 주는 미커밋 소스 변경 없음 — HEAD 그대로 빌드됨.
- 소스에 V130 마이그레이션(`V130__add_manual_env_privacy_meta.sql`) 존재 확인, `resetPrivacyMetaByRawSn`(`LsDataSrcRepository`/`DeidentReportService`)도 소스에 존재 확인 — 즉 실행 중이던 jar(V129 고정)와 현재 소스 사이 갭이 사실로 확인됨.

## 빌드
- 명령: `cd backend && ./gradlew clean bootJar -x test` (호스트, 백그라운드 실행 후 폴링)
- 소요: 약 20초 내외(호스트 Gradle daemon 기준 3s 리포트, Docker 멀티스테이지 빌드 내부 재실행은 20s)
- 결과: **성공** (`BUILD SUCCESSFUL`, 5 actionable tasks)
- 산출물: `backend/build/libs/klid-la-authoring-0.1.0-SNAPSHOT.jar` (2026-07-25 10:58 생성, 86,569,686 bytes)
- Docker 이미지 빌드(`docker compose -f docker-compose.yml -f docker-compose.local.yml build klid-backend`)도 정상: `./gradlew --no-daemon clean bootJar -x test` → `BUILD SUCCESSFUL in 20s`, 이미지 `klid-backend:latest` 재생성 완료
- 실패 없음 — 에러 원문 발췌 불필요

## 재기동 후 검증

| 항목 | 이전 | 이후 | 판정 |
|------|------|------|:--:|
| jar 빌드시각(컨테이너 내부) | 2026-07-23 17:10 | 2026-07-25 01:59(UTC, 컨테이너 파일 mtime = KST 10:59) | PASS |
| 이미지 ID | sha256:0e481547a749d... (Created 2026-07-23T17:10:42Z) | sha256:ac93492631f2... (Created 2026-07-25T01:59:38Z) | PASS |
| Flyway 최대 버전 | V129 | **V130** (`130 \| add manual env privacy meta \| t \| 2026-07-25 10:59:47`) | PASS |
| `resetPrivacyMetaByRawSn` 관련 엔드포인트 존재 | 부재(V129 jar) | `POST /v1/deident-reports/{rprtSn}/resolve` 라우트 확인 — 인증 없이 호출 시 `401`(= 라우트 존재, 인증 게이트만 작동. 404 아님) → `DeidentReportService.resolveManually()` → `srcRepository.resetPrivacyMetaByRawSn(rawSn)` 호출 경로 소스상 확인 | PASS |
| health(liveness/readiness) | - | `/actuator/health/liveness`=`UP`, `/actuator/health/readiness`=`UP`, 컨테이너 `docker inspect` Health.Status=`healthy` | PASS |
| health(root aggregate) | 미측정(비교불가) | `/actuator/health`(무인증) = `{"status":"DOWN","groups":["liveness","readiness"]}` | **주의(아래 특기사항 참조)** |

- 재기동 후 다른 컨테이너(`klid-postgres`, `klid-frontend`, `klid-ai-server`, `klid-mock-server`)는 `--no-deps` 옵션으로 **건드리지 않음** — `docker ps` 확인상 생성시각 그대로 유지됨.

## 데이터 보존

- **결론: 유실 있음 — 치명적, 단 재빌드/재기동 작업 자체가 원인이 아님(아래 근거)**
- 작업 착수 직후(재빌드 이전) 1차 조회: `ls_data_raw` **23건**, `raw_sn` = 4~22, 23(FAILED), 24(FAILED), 25(MARKING_READY), 26(COMPLETED)
- 재기동 이후(현재) 조회: `ls_data_raw` **20건**, `raw_sn` = 4~22, 26 — **23·24·25 세 건이 사라짐**
- 원인 분석:
  - 본 작업에서 실행한 명령은 ①호스트 Gradle 빌드 ②Docker 이미지 빌드(멀티스테이지, 런타임 미기동) ③`docker compose up -d --no-deps klid-backend`(백엔드 컨테이너만 재생성) ④Flyway 자동 마이그레이션(V130, `ALTER TABLE ... ADD COLUMN` 뿐 DELETE 없음, 원문 확인됨) 뿐이며, `docker compose down -v`/볼륨 삭제/수동 DELETE는 **일절 실행하지 않음**
  - `postgres` 로그(`log_statement=none`이라 성공한 DML은 기록 안 되나, 동시간대 다른 커넥션들의 SELECT 문 다수 포착됨 — `01:37~01:47 UTC`(=KST 10:37~10:47) 구간에 `raw_sn`, `ls_marking`, `ls_deident_proc_log`, `ls_data_meta_review` 등을 조회하는 **다른 세션의 동시 접속 흔적**이 다수 확인됨 → 동일 공용 docker 환경에서 **다른 검증 세션/에이전트가 동시에 이 DB를 사용 중**이었음이 확인됨
  - 자식 테이블 잔존 확인: `raw_sn IN (23,24,25)` 기준으로 `ls_marking`(2건), `ls_deident_proc_log`(3건), `ls_raw_data_status`(2건), `ls_task_assignment`(2건)가 **여전히 남아있음**(고아 FK). 이는 애플리케이션의 정상 삭제 플로우(코드베이스에 `ls_data_raw` 삭제 API/카스케이드 자체가 존재하지 않음 — 전수 grep 결과 `ls_data_raw` 대상 delete 로직 없음)로는 설명되지 않고, **`ls_data_raw`에서만 직접 DELETE(수동 SQL 등)가 실행되고 자식 테이블은 정리되지 않은 부분 삭제** 정황
  - → 본 작업(재빌드/재기동)이 원인이라는 근거는 없음(코드·마이그레이션·명령 이력상 DELETE 경로 전무). 다만 **동일 환경을 공유하는 다른 세션/작업에 의한 데이터 유실 가능성이 매우 높음** — 이후 클러스터 검증에서 rawSn 23/24/25를 근거로 삼던 케이스가 있다면 그 결과가 깨질 수 있음.

## 외부연동 배선 재확인
- `klid-backend` 실효 환경변수: `KPST_DEID_BASE_URL=http://klid-mock-server:9400`, `KPST_DEID_ENABLED=true`, `DEIDENTIFY_MOCK_MODE=false` → **KPST 연동은 mock-server(:9400)를 향함 — 정상(재기동 후에도 유지됨)**
- `AI_SERVER_URL=http://klid-ai-server:9300`, `VLM_CLIENT_ENABLED=false` 도 이전과 동일하게 유지

## 특기사항 / 발견된 문제
1. **[치명적] 데이터 유실 — raw_sn 23, 24, 25 소실**: 위 "데이터 보존" 섹션 참조. 사용자 확인 및 후속 조치 필요(다른 세션/에이전트의 동시 작업 여부 확인, 혹은 의도된 정리였는지 파악). 이후 검증 클러스터에서 이 3건을 전제로 한 케이스는 재확인 필요.
2. **루트 `/actuator/health` 가 DOWN 표시**: `liveness`/`readiness` 개별 그룹은 `UP`이고 도커 헬스체크(해당 그룹 기준)도 `healthy`로 통과하지만, 그룹 미지정 루트 엔드포인트는 `DOWN`으로 응답함. `show-details: when-authorized`라 무인증 상태에서 개별 인디케이터(`db`, `aiServer`, `deidentify`, `controlNotify` 등) 상세를 확인하지 못함. 재빌드 이전 상태와 비교 기준이 없어 이번 재빌드로 인한 회귀인지 기존부터의 동작인지 판단 불가 — **회귀 여부 판정 보류**, 별도 인증된 호출로 상세 확인 권장.
3. 빌드/마이그레이션/도커 이미지 교체 자체는 모두 정상 완료되었고, HEAD 반영은 V130 마이그레이션 적용 + `resetPrivacyMetaByRawSn` 라우팅 확인으로 실측 검증됨.
