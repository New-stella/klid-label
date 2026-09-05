# WAS 설정 이관 — WAR 반입 형상에서 반드시 옮겨야 하는 것

> 대상: 백엔드를 **외부 WAS 에 WAR 로 반입**하는 형상. [@design DEPLOY-001 · RUNBOOK-001]
> 실행 가능 JAR 형상(`java -jar`)에는 해당하지 않는다 — 그쪽은 아래 값이 애플리케이션 설정으로
> 내장 컨테이너에 그대로 걸린다.

## ★ 대상 WAS = JBoss EAP 8.1 (2026-09-04 현장 실측으로 정정)

```
호스트      klid-ai-gen-was-01
JBOSS_HOME  /GCLOUD/JBOSS/jboss-eap-8.1
모드        standalone   (-D[Standalone] · org.jboss.as.standalone)
Java        /usr/lib/jvm/java-17-openjdk
실행 계정   jboss
로그        <JBOSS_HOME>/standalone/log/server.log
```

⚠ **구 서술 폐기** — *"Tomcat 10.1.x + Java 17"*. 이 문서를 포함해 반입물 전체가 톰캣을 전제로
쓰여 있었으나 실제 장비는 JBoss EAP 8.1 이었다. 톰캣 전용 개념(`server.xml` 의 `<Connector>`,
`conf/Catalina/localhost/`, `bin/setenv.sh`, `CATALINA_OPTS`, `RemoteIpValve`, `catalina.out`)은
**EAP 에 존재하지 않는다.** 되살리지 말 것.

**WAR 자체는 바꿀 필요가 없었다.** EAP 8.x 는 Jakarta EE 10 이라 Spring Boot 3.3 의 `jakarta.*` 와
맞고, `api.war` 는 톰캣을 `WEB-INF/lib-provided/`(컨테이너가 읽지 않는 자리)에 두고 있어
컨테이너 중립이다.
⚠ **EAP 7.x 였다면 배포 자체가 불가능했다** — 그쪽은 Jakarta EE 8(`javax.*`)이다.
"JBoss 니까 되겠지"가 아니라 **판을 확인해야** 하는 이유다. 판정은 판번호보다 이쪽이 확실하다:

```bash
ls -d $JBOSS_HOME/modules/system/layers/base/jakarta/servlet/api/main   # 있으면 EE9+ → 가능
ls -d $JBOSS_HOME/modules/system/layers/base/javax/servlet/api/main     # 이것만 있으면 → 불가
```

## 왜 이 문서가 필요한가

애플리케이션 설정의 `server.*` 항목은 **내장 서버 전용**이다. WAR 를 외부 WAS 에 올리면
그 값들이 **적용되지 않는다.** 문제는 **적용되지 않아도 기동은 정상이고 일반 요청도 정상**이라,
배포 시점에는 아무 신호가 없다가 특정 기능에서만 깨진다는 점이다.

아래는 "옮기지 않으면 무엇이 깨지는가"를 기준으로 정리했다.

## 예시 파일 — `config/was/`

이 문서가 **무엇을 왜** 옮기는지의 정본이고, 그것을 **옮겨 적기 좋은 형태로 구체화한 예시**가
[`config/was/`](../config/was/) 에 있다. 값이 갈리면 이 문서가 이긴다.

| 예시 파일 | 대상 위치(예) | 이 문서의 어느 절 |
|---|---|---|
| [`config/was/standalone.conf.example`](../config/was/standalone.conf.example) | `<JBOSS_HOME>/bin/standalone.conf` | 설정 파일 위치·프로파일·JVM 옵션(→ [04-configuration.md](04-configuration.md)) |
| [`config/was/standalone-undertow.xml.example`](../config/was/standalone-undertow.xml.example) | `<JBOSS_HOME>/standalone/configuration/standalone.xml` | 1·2·4절 |
| [`config/was/README.md`](../config/was/README.md) | — | 쓰는 법·자리표시자·배포 위치 |

**WAR 안에 함께 들어가는 EAP 서술자가 둘** 있다(복사하는 것이 아니라 빌드 산출물에 포함):

| 파일 | 무엇 | 근거 |
|---|---|---|
| `WEB-INF/jboss-deployment-structure.xml` | 로깅 서브시스템 제외 → **로그 마스킹 보호** | 0절 |
| `WEB-INF/jboss-web.xml` | 컨텍스트를 **`/api` 로 고정**(파일명 무관) | 7절 |

> ⚠ **예시를 그대로 덮어쓰지 않는다.** 특히 `standalone.xml` 은 통째로 교체하면 같은 WAS 의 다른
> 애플리케이션이 죽는다 — **해당 속성만 병합**하고, 가능하면 `jboss-cli.sh` 로 적용한다
> (스키마 검증 + `standalone_xml_history/` 에 직전 판 보존).
> `<JBOSS_HOME>` 등 현장값은 설치 시 `/etc/klid/was.env` 에 적어 둔다
> (→ [09-operations-runbook.md](09-operations-runbook.md) §0-1).

---

## 0. ★★ 로그 마스킹 — 안 지키면 개인정보가 평문으로 찍힌다

**EAP 에서 새로 생긴 항목이다. 톰캣에는 없던 위험이다.**

EAP 는 자체 로깅 서브시스템(`org.jboss.logmanager`)으로 배포물의 로깅을 가로챈다. 그러면
`WEB-INF/classes/logback-spring.xml` 이 무시될 수 있는데, 그 파일은 단순 포맷 설정이 아니라
**민감정보 마스킹**을 거는 자리다.

**가로채기가 일어나도 서버는 정상 기동하고 로그도 나온다.** 개인정보가 평문으로 찍히는 것으로만
드러난다(CWE-359). 현장 실측으로 EAP 가 로깅 서브시스템을 켠 채 돌고 있음을 확인했다:

```
-Dlogging.configuration=file:<JBOSS_HOME>/standalone/configuration/logging.properties
```

**대응**: `api.war` 의 `WEB-INF/jboss-deployment-structure.xml` 이 `logging` 서브시스템을 제외한다.
이 파일은 빌드 산출물에 포함되므로 **현장에서 할 일은 없다.** 다만 **지우지 말 것** — "안 쓰는
설정처럼 보인다"는 이유로 정리하면 마스킹이 함께 사라진다.

**확인 방법**: 기동 후 로그에 마스킹이 실제로 걸리는지 본다. 마스킹 대상이 지나가는 요청을
한 번 태우고 `server.log` 에서 그 값이 가려져 있는지 눈으로 확인한다. **기동 성공은 판정이 아니다.**

## 1. 업로드 본문 한도 — 안 옮기면 대용량 업로드가 실패한다

영상 업로드는 큰 본문을 나눠 보낸다. WAS 기본 한도에 걸리면 요청이 잘린다.

| 애플리케이션 설정(내장 전용 — EAP 에서 무시됨) | EAP 에서 해야 할 것 |
|---|---|
| `server.tomcat.max-swallow-size: -1` | — (undertow 에 대응 개념이 없다) |
| `server.tomcat.max-http-form-post-size: -1` | undertow `http-listener` 의 **`max-post-size`** 를 올린다 |

```
/subsystem=undertow/server=default-server/http-listener=default:\
    write-attribute(name=max-post-size,value=1258291200)
```

값의 근거는 **앞단 httpd 의 `LimitRequestBody` 와 같은 값**이다
(`config/frontend/httpd-klid.conf.template` → 1258291200 = 1.2GB).

⚠ **앞단 웹 서버에도 같은 한도가 있다.** **둘 다** 통과해야 하고 **작은 쪽이 실제 상한**이다.
한쪽만 키우면 다른 쪽에서 잘린다.

⚠ **"무제한" 센티널(`0`)을 쓰지 않는다.** 해석이 판에 따라 갈릴 수 있어 명시 바이트가 안전하고
의도도 분명하다. 굳이 필요하면 먼저 확인할 것:
`/subsystem=undertow/server=default-server/http-listener=default:read-resource-description`

**확인 방법**: 기동 성공으로는 검증되지 않는다. **실제로 대용량 업로드를 한 번 수행**해야 한다.

## 2. 요청 스레드 예산 — 안 옮기면 상한이 어디인지 아무도 모른다

| 애플리케이션 설정(내장 전용 — EAP 에서 무시됨) | EAP 에서 해야 할 것 |
|---|---|
| `server.tomcat.threads.max` (기본 200) | **`io` 서브시스템 worker** 의 `task-max-threads` 를 명시 |

```
/subsystem=io/worker=default:write-attribute(name=task-max-threads,value=200)
```

이 수가 곧 "동시에 붙잡힐 수 있는 요청 스레드의 최대치"이고, 대용량 스트리밍 동시 실행 상한이
안전한지 판정하려면 이 예산이 보여야 한다. 기본값에 의존하면 예산이 어디에도 적혀 있지 않아,
되밀기가 요청 스레드를 잠식해도 한계를 아무도 모른 채 무응답이 된다.

⚠ **대응하는 자리가 커넥터가 아니라 별도 서브시스템이다.** undertow 안에서 찾으면 없다.

⚠⚠ **이 값은 WAS 전체에 걸린다** — 같은 worker 를 다른 애플리케이션도 쓴다. **현재 값을 먼저
확인하고, 줄이는 방향으로는 바꾸지 말 것.** EAP 기본값은 코어 수에 비례해 잡히므로 장비에 따라
200 보다 클 수 있고, 그때는 그대로 두는 것이 맞다.

## 3. 비동기 요청 타임아웃 — EAP 에서는 옮길 것이 없다

톰캣에서는 커넥터의 `asyncTimeout` 이었지만 **undertow 에는 대응하는 전역 설정이 없다.**

대신 애플리케이션 설정 **`spring.mvc.async.request-timeout`(1800000ms = 30분)** 이 이미 그 역할을
한다 — 이 키는 `server.*` 가 아니라서 **WAR 배포에서도 그대로 적용된다.**

⚠ 다만 실제 상한은 경로에 걸린 값 중 **가장 작은 것**이다. 앞단 httpd 의 프록시 타임아웃(300초)이
더 짧으므로 **현재 실효 상한은 그쪽**이다. 긴 내려받기를 늘리려면 httpd 쪽을 함께 본다
(`config/frontend/httpd-klid.conf.template`).

## 4. ★ 프록시 IP 치환 — 켜져 있으면 보안 방어가 무력해진다

**undertow `http-listener` 의 `proxy-address-forwarding` 이 `false` 여야 한다**(기본값도 `false`).

```
/subsystem=undertow/server=default-server/http-listener=default:\
    read-attribute(name=proxy-address-forwarding)
```

`true` 이면 `getRemoteAddr()` 이 요청 헤더(`X-Forwarded-For`)의 값으로 **무검증 치환**된다.
그러면 신뢰 프록시 대역 대조가 *공격자가 고른 값*을 대조하게 되어 무력화되고, 웹훅 실패
횟수 제한이 헤더 한 줄 회전으로 완전히 우회된다(CWE-348/CWE-307).

⚠⚠ **톰캣에서는 이것이 `RemoteIpValve` 라는 다른 이름이었다.** 옛 이름으로 찾아보고 "밸브가
없으니 안전하다"고 판단하면 이 속성을 놓친다. **반드시 위 속성명으로 확인할 것.**
같은 이유로 undertow 의 `proxy-peer-address` 계열 **필터**도 붙이지 않는다.

애플리케이션에는 같은 설정을 스프링 쪽에서 감지하면 **기동을 거부하는 가드**가 있다. 그러나
**외부 WAS 배포에서는 그 가드가 이것을 볼 수 없다** — WAS 자체 설정 파일은 스프링 프로퍼티가
아니기 때문이다. 그래서 이 형상에서는 **가드가 통과해도 안전이 보장되지 않는다.**
앱은 기동 시 이 사실을 경고 로그로 남긴다(`[ForwardedHeadersGuard] 외부 WAS 배포 감지 …`).

⇒ **배포 점검 항목**: WAS 설정에 그 속성이 켜져 있지 않음을 사람이 직접 확인한다.

클라이언트 IP 해석은 앱의 신뢰 프록시 대역 설정이 담당하므로 이 기능이 필요 없다.

## 5. 종료 유예 — WAS 소관으로 넘어간다

진행 중 응답을 끊지 않고 내리는 유예는 내장 서버에서는 애플리케이션 설정이었지만, 외부 WAS 에서는
WAS 의 종료 절차를 따른다. 배포 때마다 진행 중 요청이 끊기지 않도록 WAS 쪽 유예를 확인한다.

## 7. 컨텍스트 경로 — 파일명이 아니라 서술자가 정한다 (2026-09-04 변경)

`WEB-INF/jboss-web.xml` 의 `<context-root>/api</context-root>` 가 컨텍스트를 고정한다.
**EAP 에서는 WAR 파일명을 바꿔도 `/api` 가 유지된다.**

⚠ **구 규칙 폐기: "파일명이 곧 컨텍스트이므로 rename 금지".** 현장에서 같은 WAS 에
`label-studio.war` 가 함께 올라가 있어 우리 WAR 가 `klid-at-api.war` 로 바뀐 채 배포됐고,
그대로였다면 컨텍스트가 `/klid-at-api` 가 되어 프론트엔드·관제 호출이 전부 404 가 됐을 것이다.
**그 404 는 원인이 드러나지 않는다** — WAS 도 배포도 정상이고 로그도 조용하다.
이름 규칙에 기대는 대신 계약을 명시하는 쪽으로 바꿨다.

⚠ **EAP 밖의 컨테이너에서는 여전히 파일명이 컨텍스트를 정한다**(`jboss-web.xml` 은 EAP/WildFly
계열 전용). 그쪽에 배포한다면 `api.war` 이름을 유지해야 한다.

⚠ 컨텍스트를 바꾸려면 **세 곳을 함께** 바꾼다 — `jboss-web.xml` · 앞단 httpd 의 `/api` 프록시
(`config/frontend/httpd-klid.conf.template`) · 관제서버가 호출하는 저작도구 API 주소.

## 8. ⚠ 시크릿을 `-D` 로 넘기지 않는다

시스템 프로퍼티는 `ps -ef` 출력에 그대로 보여 **그 장비의 어느 계정이나 읽을 수 있다**(CWE-214).
DB 비밀번호·시크릿은 전부 `/etc/klid/application.properties` 에 두고, WAS 에는 그 파일의
**위치만** 넘긴다(`-Dspring.config.additional-location=file:/etc/klid/`).

> 현장 실측에서 WAS 기동 명령줄에 다른 시스템의 자격증명으로 보이는 `-D` 값이 노출돼 있었다.
> 우리가 넣은 것은 아니나, **같은 방식을 따라 하지 않는다.**

---

## 점검 체크리스트

- [ ] 현장값(`<WAS 유닛명>`·`<JBOSS_HOME>`·`<WAS_LOG_DIR>`·`<WAS 실행 계정>`)을 `/etc/klid/was.env` 에 적었다
- [ ] **EAP 판이 8.x 이상**임을 확인했다(`jakarta/servlet/api` 모듈 존재)
- [ ] `api.war` 를 `standalone/deployments/` 에 배포했다(컨텍스트 `/api` 는 `jboss-web.xml` 이 고정 — 7절)
- [ ] **`WEB-INF/jboss-web.xml` 이 WAR 안에 있다**(`unzip -l api.war | grep jboss-web`)
- [ ] `api.war.deployed` 마커가 생겼다(`.failed` 가 아니다)
- [ ] `JAVA_OPTS` 에 `-Dspring.config.additional-location=file:/etc/klid/` 와 `-Dspring.profiles.active=prd` 를 넣었다
- [ ] 그 옵션이 **실제로 적용됐는지** 확인했다(`ps -ef | grep '[j]boss' | tr ' ' '\n' | grep spring.config`)
- [ ] WAS 실행 계정(`jboss`)이 `/etc/klid/application.properties` 를 **읽을 수 있다**
      (`sudo -u jboss cat …` 로 **직접 읽어서** 확인 — 권한 표만 보지 않는다)
- [ ] `jboss` 가 `/var/lib/klid`·`/var/log/klid`·**NAS 저장소**에 쓸 수 있다
- [ ] httpd 실행 계정(`apache`)이 정적 dist 를 읽을 수 있다(SELinux 문맥 `httpd_sys_content_t` 포함)
- [ ] `max-post-size` 를 반영했다
- [ ] 앞단 웹 서버의 본문 한도와 어긋나지 않는다
- [ ] `task-max-threads` 를 명시했다(**기존 값을 줄이지 않았다**)
- [ ] **`proxy-address-forwarding` 이 `false` 다** (옛 `RemoteIpValve` 이름으로 찾지 않았다)
- [ ] **`WEB-INF/jboss-deployment-structure.xml` 이 WAR 안에 있다**(`unzip -l api.war | grep jboss-deployment`)
- [ ] **로그 마스킹이 실제로 걸리는지 `server.log` 에서 확인했다**
- [ ] **대용량 업로드를 실제로 1회 수행해 통과를 확인했다** (기동 성공으로는 검증되지 않는다)
