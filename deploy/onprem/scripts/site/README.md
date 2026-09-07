# 현장 도구 모음 (`scripts/site/`)

배포·점검 때 **손으로 길게 치던 것들**을 인자 없이 부르는 형태로 모았다.
2026-09-07 현장에서 실제로 틀렸던 자리가 그대로 이 도구들의 기본 동작이 됐다.

**전부 `--check` 나 인자 없이 부르면 아무것도 바꾸지 않는다.**

| 도구 | 무엇을 대신하나 | 어디서 |
|---|---|---|
| `klid-status` | 지금 무엇이 어떤 판으로 떠 있는지 (읽기 전용) | 아무 장비 |
| `klid-db` | psql 접속·스키마·설정값 읽기 | DB 서버 |
| `klid-jwt` | 인계 토큰이 왜 거부되는지 판정 | WAS |
| `klid-war` | 향 판정 + WAR 교체 | WAS |
| `klid-web` | 정적자산 교체 (`klid-config.js` 보존) | 웹 |

---

## 회차 배포 순서

```bash
./scripts/site/klid-status                       # ① 지금 상태

./scripts/site/klid-db                           # ② DB 현황 (DB 서버에서)
./scripts/site/klid-db -f onprem/db/incremental/V33__*.sql   #    증분 적용

sudo ./scripts/site/klid-war --check             # ③ 향 확인
sudo ./scripts/site/klid-war                     #    WAR 교체 (WAS 대수만큼)

sudo ./scripts/site/klid-web --check             # ④ 정적자산 (웹 대수만큼)
sudo ./scripts/site/klid-web

./scripts/site/klid-status                       # ⑤ 다시 확인
```

---

## 각 도구가 막아 주는 것

### `klid-db`
`-d` 를 빠뜨려 엉뚱한 데이터베이스를 보거나, `-h` 를 빼서 유닉스 소켓을 찾거나,
`CONTROL_DB_HOST` 의 `host:port,host` 를 그대로 넘겨 접속이 실패하는 것.
**증분 SQL 에 `search_path` 를 자동으로 건다** — 안 걸면 `public` 에 빈 표가
조용히 생기고 앱은 영원히 그것을 못 본다.

### `klid-jwt`
인계 로그인이 안 될 때 **서버를 건드리지 않고** 사유를 가른다.
`alg` · 키 길이 · 서명 일치 · `iss` 허용 · `exp` 를 한 번에 본다.
⚠ **HS512 토큰은 `JWT_SECRET` 이 64바이트 미만이면 값이 맞아도 실패한다** —
설정 파일만 봐서는 알 수 없는 자리다.

```bash
# 토큰은 브라우저 콘솔에서:  copy(localStorage['klid-jwt-token'])
./scripts/site/klid-jwt
```

### `klid-war`
향(`/label-studio/api` vs `/label-studio`)을 **사람이 고르지 않는다** —
지금 배포된 WAR 에서 읽어 같은 향을 집는다. 틀린 향은 아무 신호가 없다
(정상 기동·화면 표시·API 만 전건 404).
**재기동하지 않는다** — `.dodeploy` 마커를 배포 스캐너가 집는다.

### `klid-web`
**`klid-config.js` 를 먼저 빼두고 나중에 되돌려 놓는다.** 새 `dist` 에 들어 있는
같은 이름 파일은 *빌드 시점 빈 값*이라, 그냥 덮으면 화면은 뜨는데 API 주소를 몰라
아무것도 안 나온다. 오류도 안 난다.
소유권·SELinux 문맥까지 정리하고, `--rollback` 으로 직전 백업으로 되돌린다.
**httpd 설정은 건드리지 않고 계정도 만들지 않는다.**

### `klid-status`
`-e` 를 쓰지 않는다 — **하나가 실패해도 끝까지 본다.** 점검 도구가 중간에 멈추면
그 뒤가 안 보이는데, 그게 오늘 설치 스크립트에서 실제로 일어난 일이다.

---

## 실행 계정 — **`klid` 를 만들지 않는다**

설치는 계정·그룹을 **만들지 않는다**(2026-09-07 확정). 이미 있는 계정을 쓴다.

| 역할 | 계정 |
|---|---|
| WAS | **`jboss`** — WAS 가 그 계정으로 돈다 |
| 웹 | **`apache`** — httpd 가 그 계정으로 돈다 |

**손으로 지정하지 않아도 된다** — 그 장비에서 실제로 도는 프로세스의 소유자를 보고 정한다.
못 찾으면 관례 이름(`jboss`·`apache`)을 쓰고, 그것도 없으면 **사유를 말하고 멈춘다**
(반쯤 설치된 상태로 끝나지 않는다).

명시하고 싶으면:

```bash
sudo env KLID_USER=apache KLID_GROUP=apache ./scripts/install-web.sh ...
sudo env KLID_USER=jboss  KLID_GROUP=jboss  ./scripts/install.sh --role=was ...
```

계정을 만들어도 되는 환경(그린필드)에서만 `KLID_ALLOW_USER_CREATE=1` 을 명시한다. 기본은 꺼져 있다.

### 소유자

**root 로 돌려도 만들어진 파일·폴더의 소유자는 서비스 계정**이다.
소유가 root 로 남으면 그 순간에는 오류가 없고, 나중에 WAS 가 로그를 못 쓰거나 httpd 가
정적자산을 못 읽는 형태로 **다른 자리에서** 터진다. 그래서 만든 직후에 넘긴다.
