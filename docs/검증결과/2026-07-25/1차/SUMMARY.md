# 1차 검증 총괄 — 2026-07-25

> 범위: **A~E 5클러스터 878건** (F·G·H는 2차) · 기준: **실동작**(풀스택 + 목업서버 기동, 배치 파이프라인 실구동)
> 산출물: 클러스터별 `{A~E}-result.md` · 이슈 대장 `ISSUES.md`(115건) · 환경/실행 근거 `_raw/`

---

## 1. 집계

| 클러스터 | 카탈로그 | 검증 | PASS | PARTIAL | FAIL | BLOCKED | 확인필요 | 미검증 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 인증/권한/공통 | 156 | 156 | 149 | 7 | 0 | 0 | 0 | **0** |
| B 배치/비식별 | 219 | 219 | 205 | 12 | 0 | 1 | 1 | **0** |
| C 마킹/라벨링 | 214 | 214 | 209 | 4 | 0 | 0 | 1 | **0** |
| D 검수/버전/통지 | 134 | 134 | 100 | 29 | 5 | 0 | 0 | **0** |
| E 증강/해상도/export | 155 | 155 | 133 | 18 | 1 | 0 | 3 | **0** |
| **합계** | **878** | **878** | **796** | **70** | **6** | **1** | **5** | **0** |

**PASS율 90.7%** · 커버리지 878/878(누락 0)

이슈 **115건** — CRITICAL 2 / HIGH 26 / MEDIUM 44 / LOW 37

> **FAIL 6건이 적고 PARTIAL 70건이 많은 것이 이 회차의 성격이다.** 기능은 대체로 스펙대로 동작하는데 **방어가 불완전하거나 우회 가능한** 형태가 지배적이다. 정적 코드 대조만 했다면 대부분 PASS로 지나갔을 항목들이며, 실제로 A 클러스터는 정적 판정에서 전건 PASS였다가 반증 과정에서 CRITICAL이 나왔다.

### FAIL 6건
| ID | 케이스 |
|---|---|
| TC-NOTIFY-003 | TASK_COMPLETED 페이로드 실카운트 (→ 하드코딩 0/null) |
| TC-NOTIFY-008 | 디바운스 — 동일 rawSn 축적 후 1회 flush |
| TC-NOTIFY-014 | 다양한 변경경로 TASK_MODIFIED 발행 |
| TC-MARTVIEW-008 | V_COMPLETED_LABEL_CHANGE — 변경점만, 본문 미노출 |
| TC-ASSIGN-014 | 재배정 동시성 UK 충돌 |
| TC-AUG-053 | 동시/오배송 UNIQUE 흡수 |

---

## 2. 🔴 최우선 — 인증 우회 (CRITICAL, 실제 침투 확인)

**`A-ISSUE-13` / `E-ISSUE-01` — HMAC 웹훅 필터 우회**

```
POST /api/v1/%61ug/callback   → 서명·타임스탬프 없이 필터 스킵, 컨트롤러 도달
POST /api/v1/aug/callback     → 401 (정상 차단)
```

- **실제로 무인증 상태에서** PENDING 증강행 4건 상태 전이 + 신규 증강영상 `raw_sn=29·31` 생성 성공
- 원인: `HmacWebhookFilter.shouldNotFilter`가 **raw URI 문자열 정확일치**로 필터 적용을 판정하는데 Spring 라우팅은 **디코딩 경로**를 사용 (CWE-436 Interpretation Conflict)
- 변형 실험: trailing slash·`//`·`;param`·대문자는 전부 401, **퍼센트 인코딩만 관통**
- ~~`HmacWebhookFilter` **단위 테스트 0건**~~ → **정정(2026-07-25)**: 단위 테스트는 `src/test/java/kr/co/cudo/authoring/webhook/HmacWebhookFilterTest.java` 에 **17건 존재**한다(검증 시 `common/security/` 하위만 찾아 놓침). 다만 **경로 변형(우회) 케이스가 0건**이고 전부 `MockHttpServletRequest` 기반이라, 판정 방식을 바꾸면 필터가 무력화돼도 전건 GREEN 이 될 수 있는 **위양성 위험**이 있었다 — 결함 자체는 유효

**악화 요인** — `E-ISSUE-04`: `.env`의 빈 `WEBHOOK_HMAC_SECRET_AUGMENT=`가 yml 기본값을 덮어 **정상 콜백은 전건 401**. 결과적으로 **정규 경로는 죽고 우회 경로만 열린** 상태.

> 수정: 필터 매칭을 **디코딩·정규화된 경로**(Spring `HttpServletRequest#getServletPath` 또는 `PathPatternParser`) 기준으로 변경 + 우회 변형 회귀 테스트 추가. 2차에서 위 재현 경로를 그대로 돌려 401을 확인한다.

---

## 3. ★ 뿌리별 결함 그룹 — 개별 수정 금지, 묶어서 볼 것

개별 이슈로 접근하면 **일부만 고치거나 반대 방향으로 악화시킬 수 있다.**

### 3-1. 해상도 파생이 raw base에 생성 — 증상 12종
**뿌리**: `ResolutionReservationPersister:82-84`(비디오) · `ResolutionSnapshotService:177-178`+`resolveSafeFile`(프레임)이 출력 base를 **raw base로만 강제**. 주석에 의도가 명시돼 있다.

| 증상 | 위치 | 방향 |
|---|---|---|
| `B-61` 파생영상 스트리밍 전면 403 (15/16/18/19 전건) | `VideoStreamService:266` | fail-**closed** |
| `D-46` `V_COMPLETED_FRAME`이 원본을 비식별 경로로 노출 (rawSn=19 12/12행 동일) | 데이터마트 뷰 | fail-**open** |
| `E-41` `SRC`/`DE_IDNTF` 두 컬럼에 **같은 값** → 산출물 md5 동일, 비식별본 `anonymity="N"` 오표기 | `ResolutionPersistService:243-247` (`dst, dst`) | fail-**open** |
| **`E-22` `FrameSource:68-75`가 DEIDENTIFIED export에 rawBase 폴백을 상시 허용** — 주석에 "해상도 파생이 raw base에 기록하므로"라고 **우회 목적 명기**. 파생 하나를 살리려고 **전 영상**의 PII 격리가 fail-open | `FrameSource:68-75` | fail-**open** |

> **`E-22`가 가장 위험하다.** 국소적 우회가 전역 보안 경계를 무너뜨렸고, 그것이 주석으로 정당화돼 있어 리뷰에서 걸러지지 않았다.

**⚠ 수정 시 절대 주의**: `B-61`을 "raw base도 허용"으로 고치면 **`D-46`·`E-22` 쪽 PII 노출이 확대된다.** `VideoStreamService`는 손대지 않는 것이 정답.

**정공법(한 릴리스에 전부)**: ① 파생 산출물을 **deid base 하위로 이동** ② `SRC`/`DE_IDNTF` 컬럼 분리 ③ cleanup base 동반 이동 ④ `FrameSource` 폴백 제거 ⑤ 기존 파생 4건 백필

### 3-2. 상태머신 무검증 — 승인이 조용히 강등된다
| 증상 | 위치 |
|---|---|
| `B-03` 배치 재트리거 시 APPROVED → ASSIGNED 강등 | `transitionTo`/`changeStatus` 무검증 setter |
| `D-01` **신규 배정**이 APPROVED 영상을 무검증 강등 (실측: rawSn=4가 `V_COMPLETED_VIDEO`에서 사라지고 스냅샷만 남아 뷰/스냅샷 불일치) | `AssignmentService:75 markAssigned()` |

**재배정(reassign)에는 가드가 있는데 신규 배정에는 없다** — 전형적 비대칭 누락. 관제가 완료 통지를 받은 영상이 데이터마트에서 조용히 빠진다.

### 3-3. 외부 연동 미배선 — self-fill의 근원
| 증상 | 내용 |
|---|---|
| `ENV-01` 증강 | `ExternalAugmentClient`는 인터페이스뿐, 실구현은 `NoopExternalAugmentClient`(HTTP 호출 없이 `true` 반환). javadoc에 "Phase 2에서 추가 예정" = **미구현**. dev 모드에선 `DevAugmentCallbackSimulator`가 **스스로 SUCCESS를 만들어 자기 콜백으로 되쏨** |
| `B-21` VLM | `WebClientConfig`가 VLM URL에 **HTTPS-only + 사설IP 차단**을 강제 → 평문 HTTP인 mock-server 배선 시 **부팅 크래시**. 동일 목적의 `KpstWebClientConfig`는 http+사설IP 허용(정책 불일치) |
| `E-03` | `application-local.yml:95-105`의 `augment:` 블록이 **`kpst:` 하위에 오중첩** → dev 콜백 시뮬레이터 영구 비활성 |

### 3-4. 2노드 Active-Active 안전성
| 증상 | 내용 |
|---|---|
| `B-81` | Quartz `isClustered` **기본 false + 온프렘 템플릿도 false**. 런타임 증거: `qrtz_scheduler_state` 0행, `qrtz_fired_triggers.instance_name="NON_CLUSTERED"` |
| `B-82` | 폴링에 **원자적 클레임 없음** + `finishDownloadAndComplete` **멱등 가드 없음** → 두 노드가 같은 건을 집어 프레임 attach 이중 실행 |
| `D-02` | 동시 재배정 4병렬 **전부 200** (`@Version` 부재, UPDATE라 UK 위반 안 남) → 이력 4중복 |

> **문서 드리프트**: CLAUDE.md는 "Quartz 클러스터링 적용(락으로 잡 중복 방지)"이라 기술하나 코드는 비활성. D5 설계서("옵션 지원")가 코드 정본.

### 3-5. 롤백 경로 — 사실상 작동하지 않음
공통 뿌리: `replaceFrameLabels`가 라벨을 **delete + recreate**.

| 증상 | 내용 |
|---|---|
| `D-21` | `SAVE_REASON_ROLLBACK` 분기가 **프로덕션 도달 불가** (정상 스냅샷은 항상 `hash == sha256(payload)`라 언제나 "기존행 재활성"으로 분기). DB 전체 ROLLBACK 행 **0건** → 롤백 행위가 감사 추적에서 소실 |
| `D-22` | `lbl_sn` 재발급 → **좌표가 완전히 동일한** v1↔v3 diff가 `REMOVED×4 + ADDED×4`로 8건 오분류 → 관제 변경 카운트 왜곡 |
| `D-23` | `LS_DATA_LBL_AI_INFO`·`TRCK_ID` 미복원·고아화 (rawSn=26은 AI메타 131행 1:1 보유 → 롤백 시 전량 고아). 속성값 보유 프레임은 **FK 위반 500** 위험 |
| `D-26` | 비식별 신고 스냅샷은 `DATA_SRC_SN=NULL`이라 list·rollback **도달 불가**(404) = 복원 경로 부재. 해당 해시로 diff 호출 시 미처리 **500** |

---

## 4. ★ self-fill — 외부/원천 없이 값을 만들어내는 경로

> 사용자 확정 원칙: **로컬은 무조건 목업서버를 바라보고, 본 프로그램이 스스로 결과를 채우면 결함이다.**

| ID | 내용 | 심각도 |
|---|---|:--:|
| `D-41` | `ControlNotifyService:93-103`이 `TASK_COMPLETED`에 **`totalFrames=0, labeledFrames=0, reviewerName=null` 하드코딩**. 게다가 DTO에 영상메타(파일명·길이·채널)·결과요약 카운트 **필드 자체가 없어** 계약 절반이 미구현 | **CRITICAL** |
| `ENV-01` | 증강 결과를 외부 호출 없이 내부 시뮬레이터가 SUCCESS로 자체 생성 | HIGH |
| `E-42` | `time_of_day`/`season`을 원천 없이 `SHT_DT` 규칙으로 **생성**. **실측: rawSn=26은 18:00 촬영인데 `NGT`(야간)로 오분류**(7월 일몰 19:50). 진짜 값은 관제 `MNG_CLIP_EVNT_LST.HR_TYPE_CD`/`SESN_CD`에 **이미 있는데 엔티티에서 매핑을 명시적으로 생략**(참조 0건). export·뷰에 **MANUAL/DERIVED 구분자가 없어 관제가 추정값을 관측값으로 소비** | MEDIUM |

> `E-42`가 self-fill의 해악을 가장 잘 보여준다 — 원천이 존재하는데 추정으로 대체하고, 소비자는 그것이 추정인지 알 수 없다. 학습데이터 라벨로 쓰이면 그대로 오염된다.
> 대조적으로 `weather`·`event`·미보유 필드는 **null을 유지**해 정상이다. 코드가 일관되게 나쁜 게 아니라 **일부만 추정으로 메꿔서** 더 혼란스럽다.

---

## 5. ★ 테스트 위양성 — BE 3,013건 GREEN을 방어 근거로 쓸 수 없는 지점

baseline 자동테스트는 **BE 3,013 / FE 1,515 / ai-server 91 전부 GREEN(실패 0)**이었다. 그럼에도 아래는 통과하면서 아무것도 검증하지 못한다.

| # | 사례 | 왜 통과하나 |
|---|---|---|
| 1 | `A-24` 로그 마스킹 | `MaskingPatternLayout` 클래스 단위테스트는 통과하나 **local 로그 패턴이 `%mask`가 아닌 `%msg`**라 실제 파이프라인에 미연결 → 토큰·패스워드 평문 출력 |
| 2 | `D-21` 롤백 분기 | **40자 가짜 해시 픽스처**로 프로덕션 도달 불가 분기를 "검증" |
| 3 | `E-053` 멱등 흡수 | **Mockito 스텁**이라 PostgreSQL 트랜잭션 abort(25P02) 현실을 재현 못 함 |
| 4 | `E-032` dead-letter | `markDeadLetter()` **프로덕션 호출자 0건** — 도달 불가 |
| 5 | 스트리밍 파생 | 확정 **전**(`deIdntfYn='N'`, NOT_FOUND)만 검증해 확정 후 403을 통과시킴 |
| 6 | `ResolutionFileMaterializerTest` | **raw base를 기대값으로 고정**해 결함을 정상으로 고착 |

> **6번이 특히 위험하다** — 결함을 "기대 동작"으로 박아둔 테스트라, 코드를 올바르게 고치면 테스트가 깨지고 **테스트 쪽을 되돌릴 유인**이 생긴다. 3-1 수정 시 이 테스트를 함께 고쳐야 한다.

---

## 6. UNCERTAINTIES 확정 결과 (8건)

| # | 항목 | 확정 |
|---|---|---|
| 2 | 마킹단계 rawSn 비식별 신고 | **미구현**(컨트롤러 부재, 404) |
| 3 | TASK_COMPLETED 0/null | **결함 확정** → `D-41` CRITICAL |
| 7 | Logback 마스킹 레이아웃 | 클래스 **실존하나 local 미연결** → `A-24` |
| 8 | Quartz 클러스터링 | **비활성 확정**(런타임 증거) → `B-81` |
| 9 | 동시저장 race | **재현 성공** — REVIEWER가 가드를 무조건 통과해 동시 편집 가능 → `C-21` |
| 10 | 좌표 경계 초과 | **무검증 확정**(`[[999999,888888]]` 200 저장). javadoc은 "차단"이라 기술 → `C-22` |
| 21 | KpstDeidentTxService 원자성 | **결함 없음** (단일 `REQUIRES_NEW` 커밋 확인). 잔여는 폴링 대상 선점 1건 |
| 1 | 포털 SAM2 노출 | 내부 경로는 PORTAL 403 확인. **포털 전용 경로는 F 클러스터(2차) 범위** |

---

## 7. 환경 사실 (2차에도 유효)

- 풀스택 5서비스 기동 · backend **V130 재빌드 완료**(HEAD 반영)
- 외부 연동 목업 경유: **KPST만 O**, VLM·증강은 X (3-3 참조)
- `CONTROL_NOTIFY_ENABLED=false` → 통지 빈 7종 미등록, `/v1/tasks/**` 404. **활성화는 설정만으로 가능**(코드 수정 불요)
- 파이프라인 완주 참조 데이터 **`rawSn=26`**: 프레임 16 / 라벨 131 / APPROVED / export SUCCEEDED
- DB 실제 스키마는 `klid_at`이 아니라 **`public`**
- **`DB-ISSUE-01`**: `ls_data_raw` 참조 FK가 `ls_evnt_anno` 단 1건뿐 → 자식 테이블 고아 행 발생 확인

---

## 8. 수정 우선순위 (제안)

| 순위 | 대상 | 근거 |
|:--:|---|---|
| **1** | HMAC 우회(`A-13`/`E-01`) + `E-04` 시크릿 | 인증이 실제로 뚫렸고, 수정 범위가 국소적 |
| **2** | 해상도 파생 raw base 5단계 통합 수정(3-1) | PII 격리가 **전 영상** 범위로 fail-open(`E-22`) |
| **3** | 상태머신 무검증(3-2) | 승인이 조용히 강등돼 관제 데이터마트와 불일치 |
| **4** | `TASK_COMPLETED` 실카운트 + `D-43` 통지 유실 NPE | 관제 연동 계약 미구현 + 통지가 조용히 사라짐 |
| **5** | 롤백 경로(3-5) | 감사 추적 소실 + 고아·FK 위반 위험 |
| **6** | 외부 연동 실구현(3-3) | 2차 VLM·증강 실검증의 **전제 조건** |
| **7** | 2노드 안전성(3-4) | 배포 토폴로지 전제와 코드 불일치 |
| **8** | 테스트 위양성 6건(5장) | 수정의 회귀 안전망 자체가 비어 있음 |

---

## 9. 다음 회차

**2차 = A~E 재검증(이슈 해소 대조) + F·G·H 최초 검증**

- 재검증 시 `ISSUES.md`의 각 블록으로 **해소 여부를 1:1 대조**한다(그래서 6요소 상세를 남겼다)
- HMAC 우회는 **동일 재현 경로(`%61ug`)로 401 확인**이 수용 기준
- VLM·증강은 외부 연동 실구현 후에야 **실검증 가능** — 미수정 시 2차에서도 '미경유' 판정
- 실행: `/verify-tc {클러스터} 2차` 또는 `docs/test-cases/VERIFY-PROMPT.md`(다른 PC용)

---

## 10. 정직한 한계

- 이 검증은 **결함 발견 확률을 최대화한 것**이지 "남은 결함 0"을 뜻하지 않는다. PASS 796건 중 일부는 반증이 충분치 못했을 수 있다.
- F(포털)·G(ai-server)·H(FE/E2E) **약 460건이 미검증**이다.
- 런타임 판정 비중이 높아졌으나 **2노드 동시성·실벤더 계약·브라우저 실동작**은 이번 환경으로 재현 불가한 영역이 남아 있다(`BLOCKED` 1건 + 각 클러스터 사유 기록 참조).
