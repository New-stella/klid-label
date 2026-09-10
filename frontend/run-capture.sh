#!/usr/bin/env bash
# =============================================================================
# run-capture.sh — 단위시험 증적 촬영을 한 명령으로 돌린다 (사람이 직접 실행)
#
#   ./run-capture.sh                 전 케이스 촬영 + 증적 원장 생성
#   ./run-capture.sh 003-01 014-01   지정한 케이스만
#   ./run-capture.sh --list          촬영 가능한 케이스 목록
#   ./run-capture.sh --manifest-only 이미 찍힌 파일로 원장만 다시 만든다
#
# 설정은 이 폴더의 `capture.env` 에서 읽는다(없으면 `capture.env.example` 을 복사해 만든다).
# =============================================================================
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

RED=$'\033[31m'; GRN=$'\033[32m'; YLW=$'\033[33m'; OFF=$'\033[0m'
die() { echo "${RED}중단${OFF} $*" >&2; exit 1; }
ok()  { echo "${GRN}  OK${OFF} $*"; }
warn(){ echo "${YLW}  !!${OFF} $*"; }

echo "== 사전 점검"

# ① 설정 파일
if [[ -f capture.env ]]; then
  set -a; . ./capture.env; set +a
  ok "설정 capture.env 를 읽었다"
else
  warn "capture.env 가 없다 — 기본값(로컬 스택)으로 돈다. 다른 서버에서 찍으려면 만들어야 한다:"
  warn "   cp capture.env.example capture.env  &&  vi capture.env"
fi
APP="${CAPTURE_APP:-http://localhost:13000}"
OUT="${CAPTURE_OUT:-$HOME/Desktop/CBD-캡처루트-20260906/captures}"

# ② node
command -v node >/dev/null || die "node 가 없다."
ok "node $(node -v)"

# ③ playwright — 이 폴더 안에서만 resolve 된다
node -e "import('playwright').then(()=>process.exit(0),()=>process.exit(1))" \
  || die "playwright 를 못 찾는다. 이 폴더에서 'npm install' 을 먼저 돌린다."
ok "playwright 확인"
node -e "import('playwright').then(async p=>{const b=await p.chromium.launch();await b.close()})" 2>/dev/null \
  || die "chromium 이 없다. 'npx playwright install chromium' 을 먼저 돌린다."
ok "chromium 확인"

# ④ 서버가 실제로 응답하는가 — 안 하면 빈 화면을 조용히 찍게 된다
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "${APP}/api/actuator/health" ; true)
[[ "$code" == "200" ]] || die "서버 응답 없음 (${APP}/api/actuator/health → ${code}). 주소와 기동 상태를 확인한다."
ok "서버 200 — ${APP}"

# ⑤ dev 로그인 창구 — 이게 닫혀 있으면 어느 케이스도 진입하지 못한다
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "${APP}/dev/login" ; true)
[[ "$code" =~ ^(200|304)$ ]] || warn "dev 로그인 화면 응답 ${code} — 운영 배포에서는 닫혀 있다(그러면 촬영 불가)."

# ⑥ 포털 케이스가 이번 범위에 들면 포털 채널 앱까지 살아 있어야 한다
#    (포털 라우트는 관제 채널 번들에 아예 없다 — 여기서 안 막으면 촬영 도중에야 드러난다)
portal_in_scope=0
if [[ $# -eq 0 ]]; then
  portal_in_scope=1
else
  for a in "$@"; do [[ "$a" == 029-* || "$a" == 030-* || "$a" == --all ]] && portal_in_scope=1; done
fi
if [[ $portal_in_scope -eq 1 ]]; then
  if [[ -z "${CAPTURE_PORTAL_APP:-}" ]]; then
    warn "CAPTURE_PORTAL_APP 이 없다 — 포털 케이스(024·027)는 실패한다. 절차는 CAPTURE-README.md §3."
  else
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "${CAPTURE_PORTAL_APP}/" ; true)
    [[ "$code" =~ ^(200|304)$ ]] \
      || die "포털 채널 앱 응답 없음 (${CAPTURE_PORTAL_APP} → ${code}). 중계와 vite 를 먼저 띄운다 — CAPTURE-README.md §3."
    ok "포털 채널 앱 ${code} — ${CAPTURE_PORTAL_APP}"
  fi
fi

mkdir -p "$OUT"
ok "출력 폴더 ${OUT}"

echo
echo "== 촬영"
if [[ $# -eq 0 ]]; then set -- --all; fi
node capture-evidence.mjs "$@"
rc=$?

echo
if [[ $rc -eq 0 ]]; then
  echo "${GRN}== 전건 성공${OFF}"
else
  echo "${RED}== 실패가 있다${OFF} — 위의 '!!' 줄이 어느 케이스인지 말한다."
  echo "   실패한 케이스만 다시 돌리려면:  ./run-capture.sh <케이스ID>"
fi
exit $rc
