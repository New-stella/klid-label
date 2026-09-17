#!/usr/bin/env bash
# ============================================================================
# verify-deploy.sh — 배포 후 확인을 한 번에 돌린다
#
#   ★ 두 환경에서 같이 쓴다
#     현장(온프렘)  : WAS + systemd, 도커 없음      → 컨테이너 섹션은 자동으로 건너뛴다
#     개발서버(246) : 도커(JBoss·apache·postgres)    → 컨테이너 섹션까지 돈다
#     판정은 `command -v docker` 로 한다. 손으로 고르지 않는다.
#
#   왜 스크립트인가
#     손으로 한 줄씩 치면 따옴표 중첩과 포트를 반드시 틀린다.
#     2026-09-16 에 실제로 원격 한 줄 명령이 따옴표 중첩으로 깨졌다.
#
#   쓰는 법
#     bash verify-deploy.sh              # 전부
#     bash verify-deploy.sh A B D        # 고른 섹션만
#
#   섹션
#     A 배포 형상 (프로세스·산출물 시각·기동 로그)
#     B 엔드포인트 (FE / API / me)
#     C 연동 주소 (설정 축 + 배포 기본값 축 — 둘 다 본다)
#     D DB 스키마 판본 (Flyway)
#     E 기능 케이스 (토큰 발급 → 목록 창구)
#
#   환경변수 (기본값은 246. 현장에서는 BASE_URL·PSQL 만 맞추면 된다)
#     BASE_URL=http://localhost:8088     앱 진입 주소(앞단 웹서버 기준)
#     CTX=/label-studio                  배포 컨텍스트
#     PSQL="psql -U klid -d klid"        현장: 직접 psql / 246: 아래 기본값이 도커로 감싼다
#     JBOSS=klid-authoring-jboss  APACHE=apache  PG=postgis-klid   (246 전용)
#     WAS_LOG=/var/log/.../server.log    현장 WAS 로그 경로(있으면 A 에서 읽는다)
#
#   ★★ 포트 함정 (246)
#     apache 는 <컨테이너 안에서 80> 을 듣고 호스트 8088 로 매핑돼 있다.
#     컨테이너 안에서 8088 을 부르면 <전건 000> 이 나와 배포 실패로 오판한다(실측).
#     그래서 부르는 자리마다 포트를 달리한다.
# ============================================================================
set -uo pipefail   # ⚠ -e 를 쓰지 않는다 — 한 항목이 빨개도 나머지를 계속 봐야 한다

CTX="${CTX:-/label-studio}"
BASE_URL="${BASE_URL:-http://localhost:8088}"
API="${CTX}/api"
JBOSS="${JBOSS:-klid-authoring-jboss}"
APACHE="${APACHE:-apache}"
PG="${PG:-postgis-klid}"
DB="${DB:-klid}"
DBUSER="${DBUSER:-postgres}"
SCHEMA="${SCHEMA:-klid_at}"

HAS_DOCKER=0; command -v docker >/dev/null 2>&1 && docker ps >/dev/null 2>&1 && HAS_DOCKER=1

# psql 실행기 — 현장은 직접, 246 은 도커로 감싼다
if [[ -n "${PSQL:-}" ]]; then
  run_psql() { ${PSQL} "$@"; }
elif (( HAS_DOCKER )); then
  run_psql() { docker exec -i "${PG}" psql -U "${DBUSER}" -d "${DB}" "$@"; }
else
  run_psql() { psql -U "${DBUSER}" -d "${DB}" "$@"; }
fi

SECTIONS="${*:-A B C D E}"
want() { [[ " ${SECTIONS} " == *" $1 "* ]]; }
hr()   { printf '\n══ %s ══\n' "$1"; }
sub()  { printf -- '· %s\n' "$1"; }
skip() { printf -- '  (건너뜀 — %s)\n' "$1"; }

printf '대상: %s%s   도커: %s\n' "${BASE_URL}" "${CTX}" "$( ((HAS_DOCKER)) && echo 있음 || echo '없음(현장)' )"

# ---------------------------------------------------------------- A 배포 형상
if want A; then
hr "A. 배포 형상"
if (( HAS_DOCKER )); then
  sub "컨테이너"
  # ⚠ `docker compose ps --format` 은 파싱 실패한다. docker ps --filter 를 쓸 것.
  docker ps --filter "name=${JBOSS}" --filter "name=${APACHE}" --format '{{.Names}}|{{.Status}}|{{.Ports}}'

  sub "산출물 시각 — 이 값이 안 바뀌었으면 배포가 안 된 것이다"
  WARDIR="$(docker inspect "${JBOSS}" --format '{{range .Mounts}}{{.Source}}{{"\n"}}{{end}}' | grep -i 'jboss-eap' | head -1)"
  [[ -n "${WARDIR}" ]] && ls -l --time-style=long-iso "${WARDIR}/standalone/deployments/"*.war 2>/dev/null
  FEDIR="$(docker inspect "${APACHE}" --format '{{range .Mounts}}{{.Source}}{{"\n"}}{{end}}' | grep -i 'label-studio' | head -1)"
  [[ -n "${FEDIR}" ]] && ls -l --time-style=long-iso "${FEDIR}/index.html" 2>/dev/null

  sub "기동 로그 — context 는 반드시 ${API} 여야 한다"
  # ⚠ '/label-studio'(strip 향)로 뜨면 앞단 경유 API 가 전건 401/404 다. 아무 신호가 없다.
  # ⚠ tail 을 작게 잡으면 기동 후 시간이 지났을 때 그 줄이 밀려나 <빈 출력>이 된다(2026-09-16 실측:
  #   --tail 500 으로는 못 잡았는데 실제로는 정상 등록돼 있었다). 전체 로그에서 찾는다.
  docker logs "${JBOSS}" 2>&1 | grep -aE 'Registered web context|Deployed "|WFLYSRV0025' | tail -5 \
    || echo "  ⚠ context 줄을 찾지 못했다 — 기동이 아직 안 끝났거나 로그가 잘렸다"
  sub "기동 로그 — 오류"
  # ⚠ `grep -i ERROR` 를 쓰면 안 된다 — JSON 로그의 WARN 줄에 든 "observed an error" 를 집어
  #   <거짓 양성>을 낸다(실측). 레벨 필드로 좁힌다.
  docker logs --tail 3000 "${JBOSS}" 2>&1 | grep -a '"level":"ERROR"' | tail -5 || echo "  (ERROR 없음)"
else
  sub "WAS 프로세스"
  ps -ef | grep -iE 'jboss|wildfly|standalone' | grep -v grep | head -5 || echo "  (WAS 프로세스를 찾지 못함)"
  sub "배포된 WAR"
  find / -maxdepth 8 -name 'api*.war' -path '*deployments*' 2>/dev/null | head -5 \
    | while read -r w; do ls -l --time-style=long-iso "$w"; done
  sub "WAS 로그 (WAS_LOG 를 주면 읽는다)"
  if [[ -n "${WAS_LOG:-}" && -r "${WAS_LOG}" ]]; then
    grep -iE 'Registered web context|Deployed "|WFLYSRV0025' "${WAS_LOG}" | tail -5
    grep -i 'ERROR' "${WAS_LOG}" | tail -5 || echo "  (ERROR 없음)"
  else
    skip "WAS_LOG 미지정 또는 읽을 수 없음"
  fi
fi
fi

# -------------------------------------------------------------- B 엔드포인트
if want B; then
hr "B. 엔드포인트"
sub "앞단 경유 (${BASE_URL})"
curl -s -o /dev/null -w "  FE     = %{http_code}   (기대 200)\n"  "${BASE_URL}${CTX}/"
curl -s -o /dev/null -w "  health = %{http_code}   (기대 200)\n" "${BASE_URL}${API}/actuator/health"
curl -s -o /dev/null -w "  me     = %{http_code}   (기대 401 — 무토큰이어도 앱에 도달한다는 뜻)\n" "${BASE_URL}${API}/v1/me"
echo "  ⚠ me 가 404 면 앞단 라우팅 또는 WAR 컨텍스트가 어긋난 것이다(401 이라야 정상)."

if (( HAS_DOCKER )); then
  sub "컨테이너 안 (:80 — 여기서 8088 을 부르면 전건 000 이 나와 오판한다)"
  docker exec "${APACHE}" curl -s -o /dev/null -w "  FE     = %{http_code}\n"  "http://localhost${CTX}/"
  docker exec "${APACHE}" curl -s -o /dev/null -w "  health = %{http_code}\n" "http://localhost${API}/actuator/health"
  sub "WAS 직접 (앞단을 건너뛴다 — 여기만 200 이면 앞단 라우팅 문제다)"
  docker exec "${JBOSS}" curl -s -o /dev/null -w "  WAS    = %{http_code}\n" "http://localhost:18080${API}/actuator/health"
fi
fi

# ------------------------------------------------------------- C 연동 주소
if want C; then
hr "C. 연동 서버 주소"
cat <<'NOTE'
  판정 규칙: 설정에 행이 있으면 <설정 값>, 없으면 <배포 기본값>이 쓰인다.
  ★ 연동 주소 키는 일부러 시드하지 않는다 — <행이 없는 것이 정상>이다.
    그러므로 "행이 없다"만으로 장애라고 판단하지 말 것. 배포 기본값까지 함께 봐야 한다.
NOTE
sub "설정 축 (관리 화면에서 넣은 값)"
run_psql -P pager=off -c \
"SELECT stng_key, stng_value, mdfr_id, mdfcn_dt FROM ${SCHEMA}.ls_system_config
  WHERE stng_key IN ('kpst.deid.base-url','authoring.integration.ai-server.base-url',
                     'vlm.client.url','authoring.control-notify.url',
                     'authoring.control-account.url','authoring.augment.external.base-url')
  ORDER BY stng_key"

sub "배포 기본값 축 (환경변수)"
if (( HAS_DOCKER )); then
  docker exec "${JBOSS}" sh -c '
    for v in KPST_DEID_BASE_URL AI_SERVER_URL VLM_CLIENT_URL CONTROL_NOTIFY_URL CONTROL_ACCOUNT_URL AUGMENT_EXTERNAL_BASE_URL KLID_DEPLOY_FLAVOR CORS_ALLOWED_ORIGINS; do
      eval "printf \"  %-28s = [%s]\n\" \"$v\" \"\$$v\""
    done'
else
  for v in KPST_DEID_BASE_URL AI_SERVER_URL VLM_CLIENT_URL CONTROL_NOTIFY_URL CONTROL_ACCOUNT_URL AUGMENT_EXTERNAL_BASE_URL; do
    printf "  %-28s = [%s]\n" "$v" "$(grep -hs "^${v}=" /etc/klid/*.env /etc/klid/*.properties 2>/dev/null | head -1 | cut -d= -f2-)"
  done
  echo "  (현장은 /etc/klid/ 의 설정 파일에서 읽는다 — 경로가 다르면 직접 확인할 것)"
fi

cat <<'NOTE'

  ★★ 관제 통지 수신처와 관제 계정 창구는 <서로 다른 값>이다.
     관제는 계정 창구(/api/account/)와 데이터셋 창구(/api/data-set/)를 다른 WAS 에 둔다.
     하나를 둘에 같이 쓰면 세션 연장이 404 로 전부 실패하고, 누적 실패로 서킷이 열려
     로그아웃 중계까지 막힌다. <폴백하지 않는다> — 비어 있어도 통지 주소로 대신하지 않는다.
     (246 실측 예: 통지=http://eap8:18080 · 계정=http://eap7:28080 — 장비도 포트도 다르다)
NOTE
sub "관제 계정 창구 실왕복 — 401 JSON 이면 맞는 주소, 404 HTML 이면 틀린 주소"
ACCT="$(run_psql -tA -c "SELECT stng_value FROM ${SCHEMA}.ls_system_config WHERE stng_key='authoring.control-account.url'" 2>/dev/null | head -1)"
if [[ -z "${ACCT}" ]] && (( HAS_DOCKER )); then
  ACCT="$(docker exec "${JBOSS}" sh -c 'printf %s "$CONTROL_ACCOUNT_URL"' 2>/dev/null)"
fi
if [[ -n "${ACCT}" ]]; then
  echo "  주소: ${ACCT}"
  # ⚠⚠ 이 주소는 <앱이 부르는 주소>다. 도커 환경에서는 컨테이너 네트워크 이름이라
  #   호스트에서 부르면 이름이 안 풀려 status=000 이 난다(2026-09-16 실측).
  #   그걸 「주소가 틀렸다」로 오판하기 쉽다 — 반드시 <앱과 같은 자리>에서 부른다.
  if (( HAS_DOCKER )); then
    docker exec "${JBOSS}" curl -s -o /tmp/klid-acct.out -w "  status = %{http_code}\n" \
      -X POST "${ACCT}/api/account/auth/refresh" -H 'Content-Type: application/json' -d '{}' \
      || echo "  ⚠ 호출 실패 — 앱이 그 주소에 닿지 못한다(망 통제 또는 주소 오류)"
    echo "  응답 앞부분: $(docker exec "${JBOSS}" head -c 200 /tmp/klid-acct.out 2>/dev/null)"
  else
    curl -s -o /tmp/klid-acct.out -w "  status = %{http_code}\n" \
      -X POST "${ACCT}/api/account/auth/refresh" -H 'Content-Type: application/json' -d '{}' \
      || echo "  ⚠ 호출 실패 — 이 장비에서 그 주소에 닿지 못한다(망 통제일 수 있다)"
    echo "  응답 앞부분: $(head -c 200 /tmp/klid-acct.out 2>/dev/null)"
  fi
  echo "  판정: 401(JSON) = 맞는 주소 · 404(HTML) = 틀린 주소(통지 WAS 를 넣었을 때 나는 값) · 000 = 닿지 못함"
else
  echo "  ⚠ 관제 계정 창구 주소가 설정에도 환경변수에도 없다."
  echo "     관제 채널 배포본이면 세션 연장·로그아웃 중계가 동작하지 않는다(통지 주소로 대체되지 않는다)."
  echo "     설정 화면 → 연동 서버 주소 → 「관제 계정 창구」 에 넣는다."
fi
fi

# ------------------------------------------------------------- D Flyway 판본
if want D; then
hr "D. DB 스키마 판본"
cat <<'NOTE'
  ⚠ 반드시 <앱이 붙은 DB> 에서 본다. 246 에는 postgres 가 둘이고 한쪽은 앱이 안 쓰는 잔존 DB 다.
    그걸 모르고 조회해 "마이그레이션이 안 돌았다"고 오판한 적이 있다.
    앱이 붙은 DB 는 기동 로그의 `Database: jdbc:...` 줄이 정본이다. 스키마는 public 이 아니라 klid_at 이다.
NOTE
if (( HAS_DOCKER )); then
  sub "앱이 붙은 DB (기동 로그)"
  # ⚠ 위 A 섹션과 같은 이유로 tail 을 작게 잡으면 빈 출력이 된다. 전체 로그에서 찾는다.
  docker logs "${JBOSS}" 2>&1 | grep -a 'Database: jdbc' | tail -2 \
    || echo "  ⚠ 접속 DB 줄을 찾지 못했다 — 로그가 잘렸을 수 있다(기동 로그를 직접 확인할 것)"
fi
sub "적용된 마이그레이션 최근 10건"
run_psql -P pager=off -c \
"SELECT installed_rank, version, description, success, installed_on
   FROM ${SCHEMA}.flyway_schema_history ORDER BY installed_rank DESC LIMIT 10"
sub "실패한 마이그레이션 — 0 이 아니면 그 자체가 장애다"
run_psql -tA -c "SELECT count(*) FROM ${SCHEMA}.flyway_schema_history WHERE success = false"
sub "표·뷰 개수 — 빈 스키마인 채 기동에 성공하는 함정 방어"
# ⚠ ddl-auto=validate 는 이 앱의 EMF 구성에 도달하지 않는다. 스키마가 비어도 기동은 성공한다.
#   "떴으니 됐다"로 판단하면 빈 스키마인 채 운영에 넘어간다. 유일한 판정은 개수를 세는 것이다.
run_psql -tA -c \
"SELECT 'ls_*=' || count(*) FROM information_schema.tables
  WHERE table_schema='${SCHEMA}' AND table_name LIKE 'ls\_%'"
run_psql -tA -c \
"SELECT 'view=' || count(*) FROM information_schema.views WHERE table_schema='${SCHEMA}'"
fi

# ------------------------------------------------------------ E 기능 케이스
if want E; then
hr "E. 기능 케이스"
sub "개발용 토큰 발급 (값은 출력하지 않는다)"
curl -s -X POST "${BASE_URL}${API}/v1/dev/tokens" -H 'Content-Type: application/json' \
  -d '{"role":"REVIEWER","channel":"INTERNAL"}' -o /tmp/klid-tok.json
python3 - <<'PY'
import json
try:
    d = json.load(open('/tmp/klid-tok.json'))
    t = (d.get('data') or {}).get('token') or ''
    print(f"  토큰 발급 OK — 길이 {len(t)}" if t else f"  토큰 발급 실패: {str(d)[:160]}")
except Exception as e:
    print("  토큰 응답 파싱 실패:", e)
PY
TOKEN="$(python3 -c "import json;print((json.load(open('/tmp/klid-tok.json')).get('data') or {}).get('token',''))" 2>/dev/null)"

if [[ -z "${TOKEN}" ]]; then
  echo "  ⚠ 토큰이 없어 이하를 건너뛴다. (운영 배포본은 dev 토큰 창구가 닫혀 있는 것이 정상일 수 있다)"
else
  probe() {   # probe <이름> <경로>
    printf '  %-10s' "$1"
    curl -s -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}${API}$2" -o /tmp/klid-probe.json -w "= %{http_code}\n"
    python3 - <<'PY'
import json
try:
    d = json.load(open('/tmp/klid-probe.json')); c = d.get('data')
    if isinstance(c, dict):
        print("      응답 키:", sorted(k for k in c if k != 'content')[:12])
        it = c.get('content') or []
        if it and isinstance(it[0], dict):
            print("      첫 행 키:", sorted(it[0].keys())[:14])
    elif c is None:
        print("      errorCode:", d.get('errorCode'), "|", str(d.get('message'))[:80])
except Exception as e:
    print("      파싱 실패:", e)
PY
  }
  probe "영상목록"  "/v1/videos?page=0&size=3"
  probe "제외목록"  "/v1/videos?page=0&size=3&excludedOnly=true"
  probe "사용자"    "/v1/users?page=0&size=3"
  probe "작업목록"  "/v1/tasks/board?page=0&size=3"
  probe "검수목록"  "/v1/reviews?page=0&size=3"
fi

sub "비식별 실패 영상 재시작 — 읽기 전용 확인 (상세 진단은 verify-queries.sql 14절)"
# ⚠ 재시작 창구(POST)는 여기서 부르지 않는다 — 실제 외부 위탁이 나간다. DB 로만 본다.
#   재시작 형상 = de_ident_yn='F' 이고 data_stts_cd='PENDING'.
#   재시작 잠금 = ls_auth_work_lock 의 lck_id 가 'DEIDRETRY-' 로 시작하는 LOCKED 행.
run_psql -tA -c \
"SELECT '재시작 형상 영상=' || count(*) FROM ${SCHEMA}.ls_data_raw
  WHERE de_ident_yn = 'F' AND data_stts_cd = 'PENDING'"
run_psql -tA -c \
"SELECT '남은 재시작 잠금=' || count(*) FROM ${SCHEMA}.ls_auth_work_lock
  WHERE lck_target_cd = 'RAW' AND lck_stts_cd = 'LOCKED' AND lck_id LIKE 'DEIDRETRY-%'"
echo "  판정: 남은 잠금은 위탁이 진행 중인 영상에만 있어야 한다. 종결됐는데 남아 있으면 14-3 을 돌린다."
echo "        (새 코드 배포 전에는 잠금이 0 인 것이 정상이다)"
fi

printf '\n══ 끝 ══\n'
echo "SQL 확인은 같은 폴더의 verify-queries.sql 을 쓴다."
if (( HAS_DOCKER )); then
  echo "  docker exec -i ${PG} psql -U ${DBUSER} -d ${DB} -f - < verify-queries.sql"
else
  echo "  psql -U ${DBUSER} -d ${DB} -f verify-queries.sql"
fi
