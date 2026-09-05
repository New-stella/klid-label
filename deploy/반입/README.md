# 온프렘 반입 세트 — 2026-09-04 (JBoss EAP 판)

> ⚠ **2026-09-02 세트는 폐기입니다.** 그 매체는 대상 WAS 를 **Tomcat** 으로 전제하고 있었고,
> 현장 실측 결과 실제 WAS 는 **JBoss EAP 8.1** 이었습니다. 옛 매체로 배포하면 실패합니다
> (아래 「무엇이 바뀌었나」). **이 세트로 새로 설치하세요.**

이 폴더의 파일을 매체(외장 디스크·USB)에 그대로 복사해 현장으로 가져갑니다.

| 파일 | 크기 | 무엇 | 언제 쓰나 |
|---|---:|---|---|
| `01-CPU판-klid-onprem-jboss-20260904.tgz` | 1.4 GiB | 반입 매체 전량 | **항상 — 이것만 있으면 됩니다** |
| `02-GPU델타-1of2.tgz.part-aa` | 1.86 GiB | GPU 전환 델타 (앞 조각) | GPU 장비에 올릴 때만 |
| `02-GPU델타-2of2.tgz.part-ab` | 1.71 GiB | GPU 전환 델타 (뒤 조각) | 〃 |
| `03-소스-backend-20260904.tar.gz` | 7.0 MiB | 백엔드 소스 (2,304 파일) | 소스 인계·감리 |
| `04-소스-frontend-20260904.tar.gz` | 2.2 MiB | 프론트엔드 소스 (1,145 파일) | 〃 |
| `05-소스-ai-server-20260904.tar.gz` | 86 KiB | ai-server 소스 (52 파일) | 〃 |
| `SHA256SUMS` | — | 무결성 대조표 | 복사 직후·현장 도착 후 |

> **입구는 하나입니다.** 01 을 풀고 `site-install.sh` 를 서버마다 한 번씩 돌리면 끝납니다.
> 지금 배포가 실패해 있는 상태여도 그 스크립트가 잔재 마커를 치우고 새 WAR 로 다시 배포합니다.
> (매체를 옮기기 전에 급히 풀어야 할 때만 쓰는 우회로가 매체 안
> `onprem/scripts/klid-jboss-fix.sh` 에 따로 있습니다. 정식 설치에는 쓰지 않습니다.)

## 0. 옮기기 전과 후에 반드시

```bash
shasum -a 256 -c SHA256SUMS      # 리눅스는 sha256sum -c SHA256SUMS
```

**복사 직후 한 번, 현장에 도착해 한 번** 돌립니다. 매체 복사는 조용히 깨집니다.

---

## 1. ★ 설치 — 서버마다 이 한 줄

압축을 풀고 `onprem/` 안에서 실행합니다.

```bash
tar -x -z -f 01-CPU판-klid-onprem-jboss-20260904.tgz
cd onprem
```

### 현장 확정값 (2026-09-04)

| 항목 | 값 |
|---|---|
| **웹** | `klid-web-01` · `02` (2대) — 앞단 L2 있음 |
| **WAS** | `klid-ai-gen-was-01` ~ `04` (4대) — JBoss EAP 8.1 standalone + Java 17 |
| **AI** | `klid-ai-gpu-01` · `02` (2대) |
| `JBOSS_HOME` | `/GCLOUD/JBOSS/jboss-eap-8.1` |
| 웹 DocumentRoot | `/GCLOUD/WebApp/label-studio` ← **1차 도구가 있던 자리. 백업 후 교체** |
| 실행 계정 | WAS `jboss` · 웹 `apache` |
| NAS | `/nas-storage1` (**전체 공유**) |
| DB | `10.177.199.148:19999` · `10.177.199.149:19999` / **`klid_system_pg_prod`** |
| 스키마 | **`klid_at`** (기본값 — 인자로 줄 필요 없음) |
| WAS 포트 | **8080** |
| 콜백 경로 | **WAS 직행**(프록시 없음) → `--trusted-proxies=none` |
| ffmpeg | `/usr/local/bin/{ffmpeg,ffprobe}` (설치돼 있음) |

### ★ 서버마다 자기 스크립트 하나만 실행합니다

**① AI 서버 2대** — `klid-ai-gpu-01`, `02`

```bash
sudo ./scripts/install-ai.sh
```

> 설치 뒤 **이 서버 주소를 WAS 관리자 화면의 「연동 서버 주소」에 등록**해야 합니다.
> 2대면 **둘 다** — 한 대만 등록하면 나머지는 놀고 있는데 아무 오류도 나지 않습니다.

**② WAS 1번** — `klid-ai-gen-was-01` (DB 스키마 적재를 여기서만)

```bash
sudo ./scripts/install-was.sh --node=first \
     --storage=/nas-storage1/klid \
     --db-hosts=10.177.199.148:19999,10.177.199.149:19999 \
     --db-name=klid_system_pg_prod --db-user=postgres \
     --trusted-proxies=none
```

**③ WAS 2~4번** — 위와 같고 `--node=first` → **`--node=more`**

> `--node=more` 를 빠뜨리면 이미 적재된 스키마 위에 다시 적재를 시도합니다.

**④ 웹 2대** — `klid-web-01`, `02`

```bash
sudo ./scripts/install-web.sh --web-root=/GCLOUD/WebApp/label-studio
```

> **정적 자산만** 놓습니다. httpd 설정(프록시·LB)은 현장 것을 그대로 씁니다.
> ⚠ 그 경로의 **기존 내용(1차 저작도구)은 지우지 않고** `.bak.<시각>` 으로 옮겨 둡니다.
> 되돌리는 명령을 화면에 찍어 줍니다.

> **먼저 무엇을 할지만 보려면** 각 스크립트에 `--check` 를 붙입니다 — 아무것도 바꾸지 않습니다.
> **DB 비밀번호는 명령줄로 받지 않습니다** — 실행 중 화면에 안 찍히게 물어봅니다.
> **멱등합니다** — 다시 돌려도 안전하고, 이미 맞는 것은 건너뜁니다.

> ⚠ **`--restart` 는 붙이지 마세요(2026-09-04 현장).** WAS 가 systemd 밖에서 떠 있어
> (`jboss.service` 는 `inactive` 인데 프로세스는 8월 19일부터 살아 있음), 재기동을 걸면
> 두 번째 인스턴스가 뜨려다 포트를 못 잡습니다. 스크립트가 이 상태를 **감지하면 중단**합니다.
> 기동 주체를 systemd 로 일원화한 뒤에 쓰세요.

### 현장 상태를 먼저 보고 싶으면

```bash
sudo ./scripts/collect-site-info.sh > /tmp/site-info.txt   # 읽기 전용
```

---

## 2. 이 스크립트가 대신하는 것 — 손으로 하면 조용히 틀리는 자리

서버 1대를 손으로 설치하면서 실제로 네 가지가 났습니다. 전부 **오류를 내지 않고** 틀립니다.

| 났던 일 | 어떻게 드러났나 | 이제 |
|---|---|---|
| `standalone.conf` JAVA_OPTS 오타 5개 | 오류 없음. 값만 통째로 무효 | 스크립트가 마커 블록으로 넣고 `sh -n` 검증 |
| `/etc/klid` 에 실행 계정 **진입 권한 없음** | 파일 소유자는 맞는데 못 읽음 → WAS 는 뜨고 앱만 실패 | 디렉터리 그룹·750 을 맞추고 **실제로 읽어서** 판정 |
| WAR 이 구본인데 이름만 바꿔 재배포 | 로깅 충돌 그대로 재실패 | 배포 전 WAR 안 서술자 존재를 검사 |
| NAS 가 `/nas-storage1` 인데 기본값은 `/nas-storage` | 기동·조회 정상, 비식별·프레임추출만 실패 | 마운트에서 자동 탐지 + 쓰기 권한 실측 |

---

## 3. 무엇이 바뀌었나 (2026-09-02 판 대비)

- **대상 WAS 가 Tomcat → JBoss EAP 8.1** 로 정정됐습니다. 문서·설정 예시·설치 스크립트 전체가
  EAP 기준으로 바뀌었습니다(`CATALINA_OPTS`→`JAVA_OPTS`, `server.xml` 커넥터→undertow,
  `RemoteIpValve`→`proxy-address-forwarding`, `catalina.out`→`server.log`).
- **`api.war` 에 EAP 서술자 2개가 들어갔습니다.**
  - `WEB-INF/jboss-deployment-structure.xml` — **로그 마스킹을 지킵니다.** 없으면 배포가
    `LoggerFactory is not a Logback LoggerContext` 로 실패하고, 운 좋게 떠도 **개인정보가
    평문으로 찍힙니다**.
  - `WEB-INF/jboss-web.xml` — 컨텍스트를 `/api` 로 고정합니다. **파일명을 바꿔도 안전합니다.**
- **DB 스키마가 현행화됐습니다** — 옛 매체에는 `V27`~`V31` 이 빠져 있었습니다.
- **신규 스크립트 5종** — `site-install.sh`(통합) · `17-deploy-jboss.sh` · `18-jboss-settings.sh` ·
  `set-db-config.sh` · `collect-site-info.sh`
- WAR·프론트 정적 자산·파이썬 휠·모델·시스템 RPM의 **내용물 구성은 그대로**입니다
  (의존성 변경 없음 — 라이선스 인벤토리 371건 유효).

---

## 4. GPU 델타 — GPU 장비에 올릴 때만

조각 둘을 **순서대로 이어 붙여** 하나로 만든 뒤 풉니다.

```bash
cat 02-GPU델타-1of2.tgz.part-aa 02-GPU델타-2of2.tgz.part-ab > gpu-delta.tgz
tar -x -z -f gpu-delta.tgz
```

⚠ **쪼갠 이유는 전송 한도 때문이지 내용이 둘이어서가 아닙니다.** 반드시 이어 붙여야 열립니다.
CPU판을 먼저 설치한 **뒤에** 적용합니다 — 델타는 이름 그대로 차분이라 단독으로는 쓸 수 없습니다.
⚠ 이 델타는 **NVIDIA 독점 EULA 계통 15건**을 들이며 고지 전문 3건이 동봉되지 않습니다
(사유는 델타 안 `licenses/README.md`). 심의에 그 표를 함께 내야 합니다.

---

## 5. 소스 (03~05) — 설치에는 필요 없습니다

**설치는 01(CPU판)만으로 끝납니다.** 이 셋은 소스 인계·감리용 스냅샷입니다.

- git 추적 기준으로 담아 **`.env`·`.venv`·`node_modules`·빌드 산출물은 들어 있지 않습니다**
- 템플릿(`.env.example`)은 들어 있습니다
- ⚠ **의존성은 인터넷 없이 재현되지 않습니다.** 폐쇄망 재빌드에는 01 매체의 오프라인 자산이 필요합니다

---

## 6. 설치 후 반드시 확인 — 기동 성공은 판정이 아닙니다

폐쇄망에서 빠진 것은 대체로 **조용히** 드러납니다. 상세는 `onprem/docs/05-run-verify.md`.

- **대용량 업로드 1회** — WAS 설정 이관이 실제로 먹었는지 아는 **유일한** 수단
- **스키마 테이블 개수** — 스키마가 비어도 앱은 기동에 성공합니다.
  기대값은 매체에서 셉니다: `grep -c '^CREATE TABLE klid_at\.' onprem/db/schema.sql`
- **로그 마스킹** — 마스킹 대상이 지나가는 요청을 한 번 태우고 `server.log` 에서 가려졌는지 확인
- **AI 장비 목록에 장비가 보이고 「최근 점검」이 갱신되는지** — 보이는 것과 관측되는 것은 다릅니다.
  관측이 멈춰도 요청은 한 대로만 흘러 정상처럼 보입니다
- **AI 서버 실추론** — 모델이 없어도 정상 응답처럼 보입니다

⚠ **관리자 공유 패스워드 기본값은 평문 `admin` 입니다.** 널리 알려진 값이니 최초 관리자를 만든
직후 운영 화면에서 반드시 바꾸세요.
