# 패치 회차 배포 가이드

이미 설치돼 돌고 있는 현장에 **새 판을 얹는** 절차다. **신규 설치용이 아니다** —
아직 서지 않은 장비가 있으면 `03-install.md` 로 간다.

설계 절차서는 `RUNBOOK-002`(패치 회차 배포 절차)이고, 이 문서는 그것을 이번 매체의
파일·명령으로 옮긴 것이다. 판본은 매체 루트 `VERSION.txt` 가 말한다.

---

## ★ 가장 먼저 — 순서가 정해져 있다

```
DB 증분  →  WAR(WAS 전 대수)  →  웹 정적파일
```

**새 WAR 는 새 표를 전제로 돈다.** DB 가 뒤처진 채 WAR 만 올라가면 **기동은 성공하고**
화면·배치가 그 표를 처음 건드릴 때 터진다. 조용한 실패다.

반대로 DB 를 먼저 올리는 것은 안전하다 — 옛 WAR 는 새 표를 안 볼 뿐이다.
그래서 **되돌릴 수 없는 쪽(WAR)을 뒤에 둔다.**

배포 스크립트가 이 순서를 강제한다. 미적용 증분이 하나라도 있으면 **WAR 를 올리지 않고 멈춘다.**

---

## 0. 이 매체에 든 것

```
onprem/
├── artifacts/backend/
│   ├── api.war          ← 웹이 /api 를 그대로 넘기는 형상 (passthrough)
│   ├── api-strip.war    ← 웹이 /api 를 걷어내는 형상 (strip)
│   ├── BUILD-INFO.txt   ← 두 향의 컨텍스트가 적혀 있다
│   └── SHA256SUMS       ← 두 WAR <모두> 들어 있다
├── artifacts/frontend/dist/{control,portal}/
├── db/incremental/      ← 이번 회차 증분 + 적용 규칙
├── db/schema.sql        ← 통합 정의(이번 회차엔 쓰지 않는다 · 새 장비용)
├── scripts/             ← 배포·적용 수단 전체
└── docs/                ← 운영 문서 전체
gpu/                     ← GPU 준비 상태 점검(읽기 전용) · 이 회차와 무관
VERSION.txt              ← 기준선·이번 판·증분 목록
SHA256SUMS.patch         ← 매체 전체 무결성
```

---

## 1. 회차 확인 — 무엇에서 무엇으로 가는가

```bash
cat VERSION.txt
```

`baseline_commit` 이 **현장에 지금 깔린 판**이다. 다르면 이 매체가 이 현장의 것이 아니다.
`db_increments_shipped` 가 **이 매체에 실린 증분 전부**이고, `db_increments_new` 는 그중 이번
회차에 새로 생긴 것이다. ★**매체에는 기준선 이후 증분이 누적으로 실린다** — 현장이 어디까지
적용했는지는 조립 시점에 알 수 없기 때문이다. 적용 수단이 이력을 보고 이미 적용된 것은
건너뛰므로 그대로 돌리면 된다.

> ⚠ 구 기재 폐기 — `db_increments` 라는 항목은 없어졌고, 그것이 「이번에 적용할 증분」과
> 같다는 서술도 더는 맞지 않는다. 실린 것과 적용될 것은 이제 다르다.

> 여러 회차를 건너뛰어 반영해도 절차는 같다 — 증분은 번호 오름차순으로 밀린 것을 한 번에
> 적용하고, WAR 와 정적파일은 최신 하나로 교체한다. 중간 판을 거칠 필요가 없다.

---

## 2. 배포 향 판정 — **추측하지 않는다**

WAR 가 두 벌인 이유는 웹 컨텍스트 향이 둘이고 **어느 쪽인지는 현장 httpd 설정이 정하기**
때문이다. 우리 저장소에 그 conf 원본이 없어 확정할 수 없으므로 두 벌을 다 실었다.

### ⚠⚠ 틀린 향을 올려도 **아무 신호가 없다**

WAS 는 정상 기동하고 화면도 뜨는데 **API 만 전건 404** 다. 기동 성공도 화면 표시도
이 축을 갈라 주지 못한다.

**판정법** — 브라우저로 다음을 연다(인증 없이 열려 있다):

```
https://<사이트>/label-studio/api/actuator/health/liveness
```

| 결과 | 지금 깔린 향 | 올릴 파일 |
|---|---|---|
| JSON 이 나온다 | passthrough | `api.war` |
| 404 다 | strip | `api-strip.war` |

고른 파일을 **`api.war` 라는 이름으로** 둔다(절차가 그 이름을 찾는다).

```bash
# strip 향이면 — 파일을 바꿔 끼운다
cd onprem/artifacts/backend
mv api.war api-passthrough.war && mv api-strip.war api.war
```

배포 스크립트는 올리기 전에 WAR 안에서 컨텍스트를 **읽어** 확인한다.

### ⚠ 웹 정적파일은 향이 아니라 **채널** 축이다

`dist/control` 과 `dist/portal` 은 **빌드 시점에 굳는다** — 설치 때 바꿀 수 없다.
다른 채널 것을 올리면 머리 영역이 겹치고 하위 화면이 뜨지 않는데, **배포는 오류 없이 끝난다.**

---

## 3. 얹기와 현황 확인

### 3-0. 매체를 덮는다

```bash
# 대상 서버(WAS) 에서 — 경로 구조가 같다
cp -R onprem/. /path/to/installed/onprem/
```

### 3-1. 현황부터 본다 (아무것도 바꾸지 않는다)

```bash
cd /path/to/installed/onprem
sudo ./scripts/deploy-update.sh --check
sudo ./scripts/apply-migrations.sh --status
```

`--status` 의 미적용 목록이 `VERSION.txt` 의 `db_increments_shipped` 안에 **전부 들어 있어야**
한다(같을 필요는 없다 — 매체는 누적이라 이미 적용된 것도 함께 실린다).
미적용인데 매체에 **없는** 증분이 하나라도 있으면 **진행하지 않는다** — 그 증분은 이 매체로는
적용할 수 없고, 그대로 두면 그 현장에만 끝내 서지 않는다.

> 매체의 `onprem/db/incremental/증분-대조-결과.txt` 에 실린 증분과 그것이 통합 스키마에
> 반영됐는지가 함께 적혀 있다. 내는 쪽에서 이미 확인한 것이지만 받는 쪽도 볼 수 있다.

### ⚠ `--check` 가 "배포된 WAR 를 찾지 못했습니다" 라고 하면

`JBOSS_HOME` 자동 탐지가 실패한 것이다. 그대로 진행하면 **기존 WAR 백업이 건너뛰어지고
`--rollback` 이 되돌릴 것을 갖지 못한다**(배포 자체는 성공하므로 조용하다).
경로를 직접 준다:

```bash
sudo ./scripts/deploy-update.sh --check --jboss-home=/GCLOUD/JBOSS/jboss-eap-8.1
```

---

## 4. DB 백업 — **스크립트가 뜨지 않는다**

운영 DB 백업은 용량·시점·보관 정책이 현장 소관이라 스크립트가 임의로 뜨지 않는다.
**현장 절차로 먼저 뜬다.** 배포 스크립트는 「떴는가」를 묻기만 한다.

증분은 **되돌아가지 않는다.** 이 백업이 유일한 안전망이다.

---

## 5. 증분 적용 — **첫 대에서 한 번만**

여러 WAS 가 **같은 DB** 를 본다. 증분은 DB 축이므로 한 번만 적용한다.

```bash
sudo ./scripts/apply-migrations.sh --dry-run   # 무엇이 돌지 먼저 본다
sudo ./scripts/apply-migrations.sh
```

한 파일 = 한 트랜잭션이다. 실패하면 그 파일이 통째로 되돌아가고 뒤 번호는 돌지 않는다.
여러 노드에서 동시에 돌려도 **한 번만** 적용된다.

적용 이력은 `klid_at.ls_schm_aplcn_hstry` 에 남는다. 다시 돌리면 *"적용할 것이 없습니다"* 다.

> ⚠ **`--mark-only` 는 신규 설치 직후 전용이다.** 운영 DB 에 쓰면 **적용하지 않은 것을
> 적용했다고 기록한다.** 이 회차에서는 쓸 일이 없다.

---

## 6. WAR 교체 — **WAS 전 대수에 반복**

```bash
sudo ./scripts/deploy-update.sh --restart
#   자동 탐지가 안 되면:  --jboss-home=/GCLOUD/JBOSS/jboss-eap-8.1
```

하는 일: 미적용 증분 확인 → 기존 WAR 백업 → 교체 → **실제 헬스 호출**.
증분이 남아 있으면 여기서 멈춘다(그것이 이 스크립트의 핵심이다).

### ⚠ 한 대만 바꾸고 끝내지 마라

WAS 는 **여러 대**이고 httpd 분산 묶음이 그 앞에 있다. 한 대만 새 판이면 요청이 어느 대로
가느냐에 따라 결과가 갈리는데, 그 상태는 **간헐적으로만** 드러나 원인을 찾기 어렵다.
DB 는 이미 적용됐으므로 5 는 다시 하지 않는다 — **6 만** 대수만큼 반복한다.

---

## 7. 웹 정적파일 교체

```bash
# 대상 서버(웹) 에서 — 채널에 맞는 것 하나
sudo cp -R onprem/artifacts/frontend/dist/control/. /path/to/docroot/label-studio/
```

포털 채널 장비에는 `dist/portal/` 을 올린다. **둘을 섞지 마라.**

### ⚠ `klid-config.js` 는 교체로 사라질 수 있다

화면이 읽는 런타임 설정이다. 정본은 `/etc/klid/frontend.env` 이고 생성기를 다시 돌리면
복원된다. 교체 뒤 **제자리에 있는지 확인한다.**

```bash
ls -l /path/to/docroot/label-studio/klid-config.js
# 없으면
sudo ./scripts/install/render-frontend-config.sh
```

> ★ 이 자리는 관리 주체가 다를 수 있다 — 우리가 직접 올릴지 산출물과 절차를 넘길지가
> 회차마다 같지 않다. 착수 전에 확인한다.

---

## 8. 확인 — **기동 성공은 근거가 되지 못한다**

향이 어긋나도, DB 가 뒤처져도, 채널이 섞여도, 일부 대수만 바뀌어도 기동은 통과한다.
아래를 **실제로 한 번 써 본다.**

```bash
# 1) 향 — JSON 이 나와야 한다
curl -s https://<사이트>/label-studio/api/actuator/health/liveness

# 2) 미적용 증분 0
sudo ./scripts/apply-migrations.sh --status

# 3) 다른 스키마에 빈 표가 생기지 않았는지
psql -c "SELECT table_schema, table_name FROM information_schema.tables
          WHERE table_name IN ('ls_portal_user_meta','ls_portal_user_evnt_anno');"
#    klid_at 만 나와야 한다. public 이 함께 나오면 search_path 가 어긋난 것이다.
```

4) **브라우저에서 화면이 뜨고 목록이 채워지는 것**까지 본다 — 헬스만 보고 끝내지 마라.
5) **이번 회차가 새로 만든 표를 쓰는 화면**을 연다. DB 가 뒤처졌다면 여기서 드러난다.
6) WAS **전 대수**가 같은 판인지 — 6 을 몇 대에 돌렸는지 세어 본다.
7) 기동 기록(`server.log`)의 ERROR — 연동 설정이 잘못되면 기동을 막는 대신 이 기록만 남는다.

---

## 8-1. 상태점검 옛 정의 걷어내기 — **계통 구분이 처음 닿는 회차에만**

계통 구분 이전 판이 돌던 현장에는 **옛 상태점검 정의가 저장소에 남아 계속 발화한다.** 정의는 산출물이
아니라 DB(`QRTZ_*`)에 있고 **이미 등록된 것은 새 배포가 덮어쓰지 않는다** — 그래서 WAR 를 바꿔도
사라지지 않고, 다음 회차에도 사라지지 않는다. **사람이 한 번 걷어내야 한다.**

> **급하지 않다.** 그 옛 정의는 계통을 알 수 없고, 계통을 모르면 **아무 일도 하지 않는다** — 장비를
> 읽지도 상태 창구를 부르지도 않는다. 중복 관측·중복 벤더 호출·연속 실패 계수 유실은 **일어나지 않는다.**
> 걷어내는 이유는 그 정의가 **주기마다 깨어나 아무 일 없이 경고만 남기기** 때문이다. 추론 기본 주기가
> 5초라 하루 1만 7천 번 남짓이고, **정작 봐야 할 경고가 그 안에 묻힌다.**

### 대상인가 판정

```bash
sudo ./scripts/deploy-update.sh --check     # 현장에 지금 깔린 판
```

`VERSION.txt` 의 `baseline_commit` 이 **계통 구분 이전**이고 이번 판이 이후면 대상이다. 아래 조회에
행이 나오면 대상이고, 0행이면 이미 끝났거나 애초에 해당 없다.

### ① 남았는지 본다 (읽기만)

```sql
SELECT job_name, job_group FROM klid_at.qrtz_job_details
 WHERE sched_name = 'KlidAuthoringScheduler' AND job_group = 'aiserver';
SELECT trigger_name, job_name, next_fire_time FROM klid_at.qrtz_triggers
 WHERE sched_name = 'KlidAuthoringScheduler' AND job_group = 'aiserver';
```

**남아야 하는 것** — `aiSrvrHealthPollJobInference` · `aiSrvrHealthPollJobTimeseries`
(트리거는 `aiSrvrHealthPollTriggerInference` · `aiSrvrHealthPollTriggerTimeseries`)

**걷어낼 것** — `aiSrvrHealthPollJob` · 트리거 `aiSrvrHealthPollTrigger` (이름에 계통이 없는 쪽)

### ② 지운다 — **애플리케이션을 세운 상태에서**

⚠ **WAS 가 떠 있는 동안 지우지 않는다.** 스케줄러가 메모리에 물고 있어 다시 쓰이거나 잠금과 부딪힌다.
WAR 교체(6단계)로 **전 대수가 내려간 창**에서 하고, 그 다음에 올린다.

⚠ **순서가 있다** — 자식 표부터 지운다. 거꾸로 하면 외래키에 걸린다.

```sql
BEGIN;
DELETE FROM klid_at.qrtz_simple_triggers
 WHERE sched_name='KlidAuthoringScheduler' AND trigger_group='aiserver'
   AND trigger_name='aiSrvrHealthPollTrigger';
DELETE FROM klid_at.qrtz_triggers
 WHERE sched_name='KlidAuthoringScheduler' AND trigger_group='aiserver'
   AND trigger_name='aiSrvrHealthPollTrigger';
DELETE FROM klid_at.qrtz_job_details
 WHERE sched_name='KlidAuthoringScheduler' AND job_group='aiserver'
   AND job_name='aiSrvrHealthPollJob';
-- ★ 커밋 전에 삭제 건수를 확인한다. 각 1행이어야 한다.
--   0행이면 이미 없는 것이고, 2행 이상이면 이름을 잘못 적은 것이니 ROLLBACK 한다.
COMMIT;
```

★ **이름을 그대로 쓴다.** `LIKE 'aiSrvrHealthPoll%'` 같은 패턴으로 지우지 말 것 — **새로 등록된
계통별 정의까지 함께 지워져** 상태점검이 통째로 멈춘다. 그러면 죽은 장비가 목록에 남고, 그 상태는
기동이 성공하므로 조용하다.

> ⚠ 노드가 비정상 종료된 적이 있으면 `qrtz_fired_triggers` 에 그 트리거의 실행 중 행이 남아 있을 수
> 있다. 외래키가 없어 위 삭제는 그대로 성공하지만, 남은 행은 스케줄러가 다음 기동에서 미스파이어
> 복구로 집는다. 지우기 전에 함께 본다:
> `SELECT trigger_name FROM klid_at.qrtz_fired_triggers WHERE sched_name='KlidAuthoringScheduler' AND trigger_name='aiSrvrHealthPollTrigger';`
> 행이 있으면 같은 조건으로 함께 지운다. **애플리케이션이 내려간 상태에서만** 한다.

### ③ 확인

WAS 를 올린 뒤 ①의 조회를 다시 돌려 **계통 이름이 붙은 둘만** 남았는지 본다. 그리고 기동 기록에서
계통을 모르는 정의를 건너뛰었다는 경고가 **더는 나오지 않는지** 본다.

> 되돌리기(9단계)를 하는 경우에는 **반대로** 이번 판이 등록한 계통별 정의를 지운다 — 되돌린 판에는
> 위 방어가 없어 **실제로 전 계통을 훑기 때문**이다. 그쪽은 9단계가 다룬다.

---

## 9. 되돌리기 — **비대칭이다**

```bash
sudo ./scripts/deploy-update.sh --rollback     # 직전 백업 WAR 로
```

⚠ **DB 는 되돌리지 않는다.** 증분은 앞으로만 간다. 되돌려야 하면 4 의 백업으로 복원한다.

옛 WAR + 새 표 조합은 대개 안전하다(옛 WAR 가 새 표를 안 볼 뿐이다). 그래서 WAR 만
되돌리는 것으로 급한 불은 끈다. 다만 **새 표에 이미 쌓인 데이터**는 옛 WAR 가 다루지
못하므로 그 판단은 사람이 한다.

---

## 10. 막혔을 때

| 증상 | 먼저 볼 것 |
|---|---|
| 화면은 뜨는데 **API 가 전건 404** | 향이 반대다 → §2 판정법 |
| 배포가 **증분 때문에 멈췄다** | 정상 동작이다 → §5 를 먼저 |
| 증분이 **해시 불일치로 멈췄다** | 이미 적용된 파일의 내용이 바뀌었다. 매체를 확인한다 |
| **현재 WAR 를 못 찾는다** | `--jboss-home=` 을 준다 → §3-1 |
| 기동은 되는데 **특정 화면만 오류** | DB 가 뒤처졌을 수 있다 → `--status` |
| **간헐적으로** 옛 화면이 보인다 | 일부 WAS 만 교체됐다 → §6 |
| `public` 에 **빈 표가 생겼다** | search_path 가 어긋났다 → `06-troubleshooting.md` |

상세는 `09-operations-runbook.md`.
