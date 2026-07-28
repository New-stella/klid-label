# Phase 3 부분 재검증 — 해상도 파생 PII 격리 통합

> 대상: `E-ISSUE-21`·`E-ISSUE-22`·`E-ISSUE-41`·`D-ISSUE-46`·`B-ISSUE-61`(HIGH 5) · `E-ISSUE-23`·`E-ISSUE-24`·`E-ISSUE-26`·`E-ISSUE-29`(MEDIUM 4) — 9건
> 기준: **실동작** — 로컬 스택(5서비스) + 실HTTP + 실DB + 실파일. 정적 대조로 갈음하지 않음
> 일자: 2026-07-26 | 계획서가 "가장 위험"으로 표시한 Phase

## 1. 이 Phase 가 위험했던 이유

증상들의 방향이 **반대**였다. `B-61`(파생 스트리밍 403)은 fail-closed 이고 `D-46`·`E-22`·`E-41`은 fail-open(PII 노출)이다. 눈에 띄는 403 부터 "raw base 도 허용"으로 고치면 **PII 노출이 확대**된다.

정공법은 **파생 산출물을 deid base 로 옮기는 것** — 그러면 403 이 자동 해소되고 격리도 성립한다. 따라서 `VideoStreamService` 를 수정하지 않는 것이 수용 기준이었고, 전 라운드에서 지켜졌다(`git diff --name-only` 확인).

## 2. 백필 실측 — 전/후

참조: 기존 파생 4건 `rawSn 15·16·18·19`(부모 14·17, `RESL_720P`/`RESL_480P`, 각 12프레임).

| 항목 | 백필 전 | 백필 후 |
|---|---|---|
| 프레임 경로 | `raw/resolution/{n}/frames/` | **`deidentified/frames/deid/{n}/`** |
| `SRC = DEID` 동일값 | 각 12건 | **0건** (`SRC` null = 정책 A) |
| `rawSn=19` 스트리밍 | **403** | **206** |
| `rawSn=26` 스트리밍 | 206 | **206 유지** |
| `V_COMPLETED_FRAME` | 19 없음 | **19 복귀(12)** · 5·6·13 유지 |
| raw base `resolution/` 잔존 파일 | 52 | **0** |
| deid `videos/resolution` | 0 | **4** |
| 감사 위반 | 4건 × 12프레임 | **0건** |

- **재실행 멱등**: `migrated=[] skipped=[15,16,18,19,…] failures=[] auditViolations=[] auditTruncated=false`
- **인가**: WORKER **403** / 무인증 **401**
- **`rawSn=26` export**: v1·v2 `SUCCEEDED 32` — 폴백 제거가 정상 경로를 깨지 않음

## 3. 감사 방법 자체가 한 번 무효였다

1차 구현은 폴백 제거의 안전성을 **SQL 근사 쿼리**로 주장했다. 적대검증이 이를 반증했다 —
- 쿼리가 `STORAGE_DEIDENTIFIED_PATH` 를 **인자로 받지도 않았다**(어느 base 인가라는 핵심 축을 안 봄)
- `POSITION` **부분일치** vs 코드의 **선두 세그먼트** 판정
- 파일시스템(존재·정규파일·realpath) **미검증**

두 집합은 포함관계조차 성립하지 않아 **"`rawSn=26` 무영향"이 입증되지 않은 상태**였다. DEV_FIX 로 감사를 **코드와 동일한 판정기**(`StorageSubtreePolicy`)로 교체한 뒤 실측:

```
auditScannedFrames = 94 (전수)
rawSn=26  → 16프레임 전부 OK, 위반 0건
15/16/18/19 → 각 12건 OUTSIDE_BASE (계 48)  = 백필 대상과 정확히 일치
```

## 4. 계획서 대비 정정 3건 (PM 판단)

| # | 계획서 | 실제 / 정정 |
|---|---|---|
| 1 | 마이그레이션 `V131` | V131·V132 는 Phase 1·2 사용 → **V133** |
| 2 | 백필을 Flyway 마이그레이션으로 | **Flyway 는 파일을 못 옮긴다.** SQL 로 경로 문자열만 바꾸면 스트리밍·export 가 전부 404 = **`B-61` 재발**. V133=스키마·뷰만, 파일 이동=**별도 Java 배치**(`Copy→Verify→커밋→삭제`, 멱등) |
| 3 | 백필 실패 시 경로 NULL(fail-closed) | **raw 경로 유지 + 실패기록**(사용자 확정). NULL 이면 현재 서빙 중인 4건이 즉시 404 |

**신규 범위 1건** — 두 base 가 **같은 문자열인 prd**(`/nas-storage`)에서는 `startsWith(base)` 가 `frames/raw/` 도 통과시켜, 폴백 제거만으로 `E-22` 가 닫히지 않는다. **kind 별 subtree 접두 검사**를 추가했고, 계획서 파일 목록에 없던 `VideoResolutionService`·`ResolutionDerivativeService`·`ResolutionSnapshotService.resolveSafeSource` 도 인벤토리로 찾아냈다.

**범위 축소 1건** — `D-46` 게이트가 `DEID IS NULL` 까지 배제해 **증강 파생(WINTER/NIGHT/RAIN) 전량과 비식별 실패 영상**이 마트에서 사라졌다. `D-46` 의 결함 형태는 `SRC=DEID` 동일값이고 NULL 은 PII 노출이 아니라 결측이므로 게이트를 그 형태로 좁혔다. 실측상 이 축소가 **`rawSn 5·6·13`(APPROVED)의 소실을 막았다.**

## 5. 사용자 확정 정책 3건

| 정책 | 결정 | 구현 |
|---|---|---|
| `E-41` 파생 2벌 | **원본 없음으로 명시** | `SRC_FILE_PATH_NM=null` + ORIGINAL export 미생성 + PARTIAL 미강등 |
| 백필 실패 | **raw 유지 + 실패기록** | 경로 NULL 처리 안 함, 재실행 가능 |
| `D-46` | **게이트 + 제외 목록 산출** | `dryRun=true` 응답 `viewExcludedRawSns` |
| `E-26` 종횡비 | **레터박스 보존** | 균일 배율 `min(tw/sw, th/sh)` + 중앙 오프셋, 라벨 좌표에 오프셋 관통(BBOX/POLYGON/세그/SKELETON) |

## 6. 자동 테스트

| 시점 | 총 | 실패 |
|---|---:|---:|
| Phase 2 완료 | 3143 | 0 |
| Phase 3 구현 | 3170 | 0 |
| DEV_FIX 1차 | 3186 | 0 |
| DEV_FIX 2차 | 3200 | 0 |
| **캐시 수정(최종)** | **3204** | **0** |

전 구간 회귀 0. 스킵 5는 `MockServerLiveIntegrationIT`(판정 무관).

## 7. 캐시 stale HIGH 2건 — **유예 삭제로 해소** (사용자 결정: Redis 미도입)

최종 적대검증이 **BROKEN** 판정한 2건이다. **PII 노출·데이터 손실이 아니라 가용성**(백필 직후 최대 5분간 스트리밍 500)이었다.

### 해결 — 문제는 stale 경로가 아니라 "그 경로의 파일이 사라진 것"이었다

백필이 `copy → 커밋 → 구 파일 즉시 삭제` 였다. **삭제를 유예**하면 stale 을 보는 노드도 파일이 살아 있어 **정상 재생**된다(두 파일은 바이트 동일 사본). TTL 경과 후 자연히 새 경로로 수렴한다. **공유 캐시도, 노드 순차 재기동 같은 운영 절차도 불필요**해졌다.

| 항목 | 구현 |
|---|---|
| 대기열 | 파일시스템 마커 `{rawBase}/.pending-delete/{sha256}.pending` — **DB 스키마 변경 없음**(V134 불필요) |
| 유예 기간 | `authoring.resolution-backfill.stale-grace-minutes` 기본 **15분** |
| 유예 < 캐시 TTL | **기동 거부**(fail-closed) — WARN 아님 |
| 정리 보장 | **주기 잡**(`@Scheduled` fixedDelay 5분, 운영 기본 활성)이 1차 + **백필 재실행**이 2차. 기회적 정리 미채택(Phase 1·2 에서 "트래픽 없으면 영원히 안 돈다"가 두 번 결함이었다) |
| 삭제 조건 | ①유예 경과 ②DB 참조 0(RAW·PROC_LOG·**프레임 경로 2컬럼** 포함) ③저장소 내부·`frames/raw/**` 아님 ④정규파일 NOFOLLOW |
| 관측 | 응답 `staleDeletedCount`·`stalePendingCount` |
| HIGH-2 완화 | `migrateOne` 전체 완료 후 **재-evict**(커밋 시점 evict 이후 재설치분 제거) |

### 실측 (재배포 후)
```
기동 로그   : stale delete grace=15m (stream-meta ttl=5m)
grace=1m 주입: IllegalStateException … TTL 이상이어야 합니다 (grace=1m, ttl=5m) → 기동 거부
백필 재실행 : migrated=[] failures=[] auditViolations=[] auditTruncated=false
              staleDeleted=0 stalePending=0
스트리밍    : 15·16·18·19·26 전부 206
```
> 유예 삭제 **동작 자체**는 이전 백필(구 코드)이 구 파일을 이미 즉시 삭제해 로컬에 대기 대상이 없으므로, 실파일·실DB 로 `migrateOne` 전체를 태우는 IT 가 검증한다(구 파일 생존 + 바이트 동일 → 유예 후 삭제).

### 함께 정리한 부수 지적
- **거짓 서술 정정** — `StreamMetaCacheEvictor` 의 "동시 요청의 옛 값 재캐싱을 막는다"는 **사실이 아니었다.** 보증/미보증/노드 한계 3분류로 정정하고 `CacheConfig` 에도 2노드 한계와 유예 삭제 설계 근거를 남겼다.
- **부정 단언 뒤집기** — `verify(evictor, never())` → `times(1)` 의 근거("재구동·재위탁 시 경로 교체")가 코드로 반증됐다(`ApprovedRedeidentService:82-84` 가 `'Y'` 를 409 거부, 배치 재제출 경로 없음). evict 는 유지하되(미래 안전) **구 주석의 거짓 근거를 삭제**하고 "현재 도달 가능한 모든 흐름에서 no-op"임을 근거와 함께 명시. 원래 의도를 고정하는 테스트는 `ApprovedRedeidentServiceTest:129` 에 이미 존재.
- **테스트 사각** — 단위 2건이 트랜잭션 없이 호출돼 **즉시-evict 분기만** 타던 것을 실제 `TransactionSynchronizationManager` 로 커밋/롤백 콜백까지 검증하도록 보완. IT 도 `txService` 직접 호출 → **`migrateOne` 전체 통과**로 교체.
- **`required=false` 제거** — `DeidentifyStep` 의 evictor 만 optional 이라 빈이 사라져도 조용히 evict 를 건너뛰는 구조였다(테스트 편의가 프로덕션 fail-open 이 되는 패턴). 필수 주입으로 통일.

### 남는 사실 (해소되지 않음 — 무해)
- **2노드에서 TTL(≤5분) 동안 구 경로가 계속 서빙된다.** 파일이 살아 있고 바이트 동일이라 재생은 정상이나 "즉시 새 경로로 수렴"하지는 않는다. Redis 미도입 결정의 직접 귀결이다.
- **HIGH-2 완전 제거는 아니다.** 재-evict 로 창을 좁혔을 뿐, 커밋 전에 로드를 시작한 요청의 재설치 자체는 `@Cacheable(sync=true)` 없이 막지 못한다. 해당 애너테이션이 `VideoStreamService` 에 있는데 **이 Phase 는 그 파일 수정 금지**라 손대지 않았다. 결과는 위와 같은 무해 창.
- **유예 동안 저장소 사용량 2배**(구+신 사본 공존, 기본 15분).

### H-1. 캐시가 프로세스 로컬이라 2노드에서 peer 노드가 stale
`CacheConfig` 는 `SimpleCacheManager` + Caffeine 이고 공유 캐시(Redis 등)가 없다(전 코드 `redis` grep 0건). `evictAfterCommit` 은 **실행 노드에만** 적용된다. 배포는 주 서버 **2노드 Active-Active** 다.

```
노드B: GET /stream  → 구 경로가 노드B 캐시에 적재
노드A: POST /resolution-backfill → 노드A 로컬 evict + 구 파일 물리 삭제
노드B: GET /stream  → 삭제된 경로 → 500 (최대 TTL 5분)
```
**단일 노드 운영이면 실현 확률 0.** 다만 결함(공유 무효화 부재) 자체는 잔존한다.

### H-2. `@Cacheable` 의 get→load→put 이 비원자라 evict 후 stale 재설치
`sync` 미지정이라 **커밋 전에 DB 를 읽은 요청이 evict 이후에 put** 할 수 있다.
```
T0 요청R: resolveStreamMeta 가 구 경로 로드
T1 백필:  커밋 + evict (이 시점 캐시가 비어 있어 no-op)
T2 요청R: 구 경로 메타를 put        ← stale 재설치
T3 백필:  구 파일 삭제              → 이후 5분간 500
```
`StreamMetaCacheEvictor` javadoc 의 "동시 요청의 옛 값 재캐싱을 막는다"는 서술은 **사실이 아니다.**

### 부수 지적
- **부정 단언 뒤집기가 정당하지 않았다.** `verify(evictor, never())` → `times(1)` 의 근거("재구동·재위탁 시 경로 교체")가 코드로 반증됐다 — `ApprovedRedeidentService:82-84` 가 `'Y'` 를 409 로 거부하고 배치 재제출 경로도 없어, `applyBatchCompletion` 의 evict 는 **도달 가능한 모든 경로에서 캐시 미스(no-op)** 다. 즉 의도 고정 테스트를 코드에 맞춰 약화시킨 것이다.
- **신규 테스트가 타이밍·순서 안전속성을 검증하지 않는다.** 단위 2건은 트랜잭션 없이 호출돼 `evictAfterCommit` 의 **즉시 evict 분기만** 타므로 `evict` 로 바꿔도 GREEN 이고, IT 는 `migrateOne` 을 우회해 **"evict 가 구 파일 삭제보다 앞선다"는 핵심 주장이 무검증**이다.
- `DeidentifyStep` 의 evictor 만 `@Autowired(required=false)` — 빈이 사라져도 부팅 성공 + 조용히 evict 스킵. `KpstDeidentTxService` 는 필수 주입이라 불일치(현 프로덕션에서 null 이 되는 트리거는 미확인).
- `'F'` 전이 경로가 안전한 근거가 개발자 설명과 다르다. 실제 근거는 "`'Y'`+캐시 상태에서 도달 가능한 F 전이가 없음"이며, `ApprovedRedeidentService` 의 `'Y'` 거부 가드가 완화되는 순간 7개 사이트 전부 stale 창으로 전환된다.

## 8. 잔여 (후속)

| # | 심각도 | 내용 |
|:-:|:--:|---|
| ① | MEDIUM | **오프셋 미저장** — `LS_DATA_AUG_LBL_MAP` 에 `SCALE_X/Y` 만 기록. 부모 원본 치수 없이 역산 불가(반례: 1080×1920 과 1440×1920 이 같은 배율인데 오프셋 상이). 저장 좌표 자체는 정확, 영향은 추적성. 컬럼 신설은 표준용어 확정 선행 |
| ② | MEDIUM | **기존 파생 4건은 옛 공식(축별 강제 스케일=왜곡)으로 존치.** 레터박스는 신규 생성분에만 적용 — 재생성은 범위 밖 |
| ③ | MEDIUM | **파생 재비식별 봉쇄** — 정책 A + fail-closed 로 `DeidentFrameAttacher` 가 파생 프레임 재부착 차단. 트리거 경로 존재 미확인 |
| ④ | MEDIUM | `unresolvedPresetRawSns` **12건**(`LS_DATA_AUG` 행이 없는 고아 파생) — 발견·보고는 되나 영상 경로 자동 이관 불가, 수동 확인 필요 |
| ⑤ | LOW | 참조 확인이 **경로 문자열 완전일치** 전제 — 다른 표기로 저장된 행이 있으면 참조를 놓칠 수 있음 |
| ⑥ | LOW | 정책 A 의 **export 동작(ORIGINAL 스킵·PARTIAL 미강등)은 IT 검증만** — 라이브 재승인으로 재생성하지 않음 |
| ⑦ | LOW | `'F'` 전이 경로(`markDeidentified("F")` 계열)는 evict 미호출 — 경로 변경이 아닌 상태 전이라 범위 밖 |
| ⑧ | 후속 | `E-ISSUE-25`(`RESL_RESL_` 이중 접두 드리프트) 자체는 미수정. 단 **고쳐도 백필이 깨지지 않도록** 매칭을 드리프트 비의존으로 만들었고 테스트도 양쪽 형태를 단언 |

## 8. 이 Phase 의 과정 (재발 방지용)

구현 1 + QA 6종 + DEV_FIX 2 + 캐시수정 1 + 적대검증 3라운드 + 실HTTP/실DB 프로브 4회차.

| 라운드 | 결함 |
|---|---|
| 구현 | `.gitignore` 가 신규 통제 클래스를 무시(커밋 불가) · 심링크 우회 · 백필 원천 무검증 · **감사 쿼리가 코드와 비동치** · 백필 대상발견이 라벨축 INNER JOIN 으로 정상 파생 누락 · `E-26` 요구 회피 |
| DEV_FIX 1차 | 판정은 실경로·**사용은 lexical 경로**(TOCTOU) · 감사 20만행 조용한 절단 · 빈 문자열 비동치 잔존 · 프리셋 짝짓기가 드리프트 의존 · 영상 경로 키 충돌 |
| DEV_FIX 2차 | **캐시 무효화 누락** — 백필이 경로를 바꾸고 `stream-meta` 를 evict 하지 않아 백필 전 재생된 영상이 500 |

### 교훈

1. **뿌리를 고칠 때 그 뿌리가 닿는 지점을 전부 열거한다.** 이 Phase 에서만 **3회 위반**했다 — 형제 엔드포인트(Phase 2), 심링크 lexical 반환, 캐시 무효화. **문장으로 전달하는 것은 효과가 없었고, 인벤토리 표를 산출물로 요구하니 작동했다**(보안 담당이 놓친 `/v1/tasks/*`, evict 누락 2곳 추가 발견).
2. **검증 근거 자체가 틀릴 수 있다.** "감사 쿼리로 확인했다"는 근거가 코드와 비동치라 통째로 무효였다. 근거를 만드는 도구는 **판정 대상과 같은 판정기**여야 한다.
3. **실제 계층을 우회하는 테스트는 GREEN 이 거짓 신호다.** 이 캠페인에서 4연속 실증 — `.gitignore`(빌드 통과·저장소에 파일 없음), 필터 체인(Phase 2, 3130 GREEN 인데 실배포 뚫림), 심링크(lexical 검증), 캐시(DB 는 맞고 서빙은 구 경로).
4. **편의 장치가 fail-open 이 된다.** 테스트용 null 가드·가용성용 설정이 운영에서 통제를 조용히 끄는 패턴이 반복됐다.
5. **"고쳤다"와 "실제로 그렇게 됐다"는 다르다.** 백필은 코드가 완성된 뒤에도 **실행 전까지 파생 4건이 raw base 에 그대로 있었다.** 수용 기준이 완료된 사실을 요구하면 실행까지가 범위다.
