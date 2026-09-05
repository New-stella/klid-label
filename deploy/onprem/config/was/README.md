# WAS 설정 예시 (WAR 반입 형상 — JBoss EAP)

배포 형상은 **외부 WAS(JBoss EAP 8.1, standalone) 에 `api.war` 반입**이다(@design DEPLOY-001 · RUNBOOK-001).
WAS 자체는 **설치 대상이 아니라 전제**다 — 고객 장비에 이미 있는 것을 쓰고, 이 패키지는 설치하지 않는다.

이 디렉터리는 **그 WAS 에 무엇을 어떻게 넣어야 하는지의 예시**다. 규정 내용(무엇을 왜 옮기는가)의
정본은 [`docs/10-was-settings.md`](../../docs/10-was-settings.md) 이며, 여기 파일들은 그것을
**옮겨 적기 좋은 형태로 구체화한 사본**이다. 값이 갈리면 그 문서가 이긴다.

> ## ⚠ 대상 WAS 가 톰캣이 아니라 JBoss EAP 다 (2026-09-04 현장 실측으로 정정)
>
> 그전까지 이 디렉터리와 문서 전체가 **Tomcat 10.1.x** 를 전제로 쓰여 있었다. 실제 장비를
> 확인한 결과 **JBoss EAP 8.1** 이었다.
>
> ```
> 호스트      klid-ai-gen-was-01
> JBOSS_HOME  /GCLOUD/JBOSS/jboss-eap-8.1
> 모드        standalone   (-D[Standalone] · org.jboss.as.standalone)
> Java        /usr/lib/jvm/java-17-openjdk
> 실행 계정   jboss
> ```
>
> **WAR 자체는 바꿀 필요가 없다.** EAP 8.x 는 Jakarta EE 10 이라 Spring Boot 3.3 의 `jakarta.*`
> 네임스페이스와 맞고, `api.war` 는 톰캣을 `WEB-INF/lib-provided/`(컨테이너가 읽지 않는 자리)에
> 두고 있어 컨테이너 중립이다.
> ⚠ 만약 EAP **7.x** 였다면 그쪽은 Jakarta EE 8(`javax.*`)이라 **배포 자체가 불가능**했다.
> 판을 확인하지 않고 "JBoss 니까 되겠지"로 넘어가면 안 되는 이유다.
>
> **폐기된 파일 3종** — 되살리지 말 것. EAP 에는 그 파일도 그 개념도 없다.
>
> | 폐기 | 대체 |
> |---|---|
> | `server-connector.xml.example` (`conf/server.xml` 의 `<Connector>`) | `standalone-undertow.xml.example` |
> | `setenv.sh.example` (`bin/setenv.sh` · `CATALINA_OPTS`) | `standalone.conf.example` (`JAVA_OPTS`) |
> | `context-api.xml.example` (`conf/Catalina/localhost/api.xml`) | **대체 없음** — 아래 「컨텍스트」 참조 |

## 파일

| 파일 | 대상 위치(예) | 무엇 |
|---|---|---|
| `standalone.conf.example` | `<JBOSS_HOME>/bin/standalone.conf` | `JAVA_OPTS` — 설정 파일 위치·프로파일·JVM 옵션 |
| `standalone-undertow.xml.example` | `<JBOSS_HOME>/standalone/configuration/standalone.xml` | 업로드 본문 한도·스레드 예산·프록시 IP 치환 차단 |

WAR 안에 함께 들어가는 배포 서술자도 있다(여기서 복사하는 것이 아니라 빌드 산출물에 포함된다):

| 파일 | 위치 | 무엇 |
|---|---|---|
| `jboss-deployment-structure.xml` | `api.war` 의 `WEB-INF/` | **로그 마스킹을 지키는 설정** — 아래 「조용히 깨지는 것」 참조 |
| `jboss-web.xml` | `api.war` 의 `WEB-INF/` | **컨텍스트를 `/api` 로 고정** — 파일명이 바뀌어도 안전 |

## 배포 위치와 컨텍스트

```bash
# 무결성 먼저
cd <매체>/onprem/artifacts/backend && sha256sum -c SHA256SUMS

# 배포 (WAS 실행 계정으로)
cp api.war <JBOSS_HOME>/standalone/deployments/
touch <JBOSS_HOME>/standalone/deployments/api.war.dodeploy

# 결과 — .deployed 가 생기면 성공, .failed 면 사유가 server.log 에 있다
ls -l <JBOSS_HOME>/standalone/deployments/api.war.*
```

**컨텍스트는 `WEB-INF/jboss-web.xml` 이 `/api` 로 고정한다**(2026-09-04 신설). 그래서 EAP 에서는
**파일명을 바꿔도 컨텍스트가 흔들리지 않는다.**

> ⚠ **구 규칙 폐기(2026-09-04): "파일명이 컨텍스트를 정하니 rename 금지".**
> 실제로 현장에서 같은 WAS 에 `label-studio.war` 가 함께 올라가 있어 우리 WAR 가
> `klid-at-api.war` 로 바뀐 채 배포됐고, 그러면 컨텍스트가 `/klid-at-api` 가 되어
> 프론트엔드·관제의 모든 호출이 404 가 된다. **그 404 는 원인이 드러나지 않는다** —
> WAS 도 배포도 정상이고 로그도 조용하다. 이름 규칙에 기대는 대신 계약을 명시하는 쪽으로 바꿨다.

⚠ **EAP 밖의 컨테이너에서는 여전히 파일명이 컨텍스트를 정한다** — `jboss-web.xml` 은 EAP/WildFly
계열에서만 읽히기 때문이다. 그쪽에 배포한다면 `api.war` 이름을 유지해야 한다.
스프링의 `server.servlet.context-path` 는 내장 서버 전용이라 어느 쪽에서도 적용되지 않는다.

## 쓰는 법

1. **현장값을 먼저 정한다.** 아래 자리표시자는 **우리가 모르는 값**이다 — 지어내지 말고
   WAS 운영 주체에게 확인한다. 확인한 값은 `/etc/klid/was.env` 에 적어 둔다
   (→ [`docs/09-operations-runbook.md`](../../docs/09-operations-runbook.md) §0-1).

   | 자리표시자 | 뜻 | 확인 방법 |
   |---|---|---|
   | `<WAS 유닛명>` | `systemctl` 로 다루는 WAS 서비스 유닛명 | `systemctl list-units --type=service \| grep -iE 'jboss\|eap'` |
   | `<JBOSS_HOME>` | EAP 설치 루트 | `ps -ef \| grep '[j]boss' \| tr ' ' '\n' \| grep jboss.home.dir` |
   | `<WAS_LOG_DIR>` | `<JBOSS_HOME>/standalone/log` | 위와 같은 방법으로 `jboss.server.base.dir` 확인 |
   | `<WAS 실행 계정>` | WAS 프로세스의 OS 계정 — **확정: `jboss`** | `ps -ef \| grep '[j]boss'` 첫 열 |
   | `<웹 실행 계정>` | httpd 프로세스의 OS 계정 — **확정: `apache`** | `ps -ef \| grep '[h]ttpd'` 첫 열 |

2. **예시를 그대로 덮어쓰지 않는다.** WAS 는 다른 애플리케이션도 함께 돌고 있을 수 있다.
   기존 파일이 있으면 **해당 항목만 병합**한다 — 특히 `standalone.xml` 은 통째로 교체하면
   다른 서비스가 죽는다. **가능하면 `jboss-cli.sh` 로 적용한다**(스키마 검증 + 이력 보존).

3. **반영 확인은 기동 성공이 아니다.** `server.*` 설정이 WAR 배포에서 무시되는 것은
   **오류를 내지 않는다** — 기동도 일반 요청도 정상이다. **대용량 업로드를 실제로 1회 수행**해
   통과를 확인해야 이 단계가 끝난다(정본 문서의 점검 체크리스트 마지막 항목).

## 지키지 않으면 조용히 깨지는 것 4가지

- **`WEB-INF/jboss-deployment-structure.xml` 을 지우지 않는다.** EAP 는 자체 로깅
  서브시스템으로 배포물의 로깅을 가로챈다. 그러면 `logback-spring.xml` 이 무시될 수 있는데
  그 파일이 **민감정보 마스킹**을 거는 자리다. 가로채기가 일어나도 **서버는 정상 기동하고
  로그도 나온다** — 개인정보가 평문으로 찍히는 것으로만 드러난다(CWE-359).
  현장 실측으로 EAP 가 `-Dlogging.configuration=…/logging.properties` 로 기동 중임을 확인했다.
- **`WEB-INF/jboss-web.xml` 을 지우지 않는다.** 이 파일이 컨텍스트를 `/api` 로 고정한다.
  지우면 컨텍스트가 다시 **파일명**을 따라가고, 현장에서 파일명이 바뀌는 일이 실제로 있었다
  (`klid-at-api.war` → `/klid-at-api`). 그러면 프론트엔드·관제의 모든 호출이 404 가 된다.
- **`proxy-address-forwarding` 을 켜지 않는다.** 켜면 신뢰 프록시 대조와 시도 횟수 제한이
  헤더 한 줄로 우회된다(CWE-348/CWE-307). 앱 쪽 가드는 **WAS 설정 파일을 볼 수 없어**
  이 경우를 잡지 못한다 — 사람이 확인해야 하는 항목이다.
  ⚠ **톰캣에서는 이것이 `RemoteIpValve` 라는 다른 이름이었다.** 옛 이름으로 찾아보고
  "없으니 안전하다"고 판단하면 놓친다.
- **비밀값을 이 디렉터리의 파일에 적지 않고, `-D` 로도 넘기지 않는다.** 시스템 프로퍼티는
  `ps -ef` 에 그대로 보여 그 장비의 어느 계정이나 읽을 수 있다(CWE-214). DB 비밀번호·시크릿은
  전부 `/etc/klid/application.properties` 에 있고, WAS 는 그 파일의 **위치만** 알면 된다.
