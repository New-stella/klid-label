#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 10-install-postgresql.sh — [대상 서버 / Rocky Linux 9] 번들 PostgreSQL 16 오프라인 설치
#
#   ★ 옵션 단계다. install.sh 가 USE_BUNDLED_POSTGRES 가드 하에 호출한다.
#       USE_BUNDLED_POSTGRES=1 (기본) → 번들 PG16 RPM 을 오프라인 설치 + initdb + 서비스 기동.
#       USE_BUNDLED_POSTGRES=0        → 외부(기존) PG 사용. 전체 스킵.
#     번들 RPM(syspkgs/postgresql/*.rpm)이 없으면 안내 후 스킵한다.
#
#   ★ 역할 분담(중복 생성 금지):
#       10(이 스크립트) = PG16 RPM 설치 + initdb + postgresql.conf/pg_hba.conf + 서비스 기동.
#       15-init-db.sh   = control/portal DB · 앱 유저 생성(DB_INIT_RUN=1 시).
#     이 스크립트는 DB·유저를 만들지 않는다(15 의 책임). 테이블은 backend Flyway 가 자동 생성한다.
#
#   외부 네트워크 호출 없음. 모든 RPM 은 syspkgs/postgresql/ 번들에서 오프라인 설치한다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"
require_root

ONPREM="$(onprem_root)"
PG_RPM_DIR="${ONPREM}/syspkgs/postgresql"
# L3: PG_MAJOR 는 versions.sh 의 POSTGRES_MAJOR 단일 출처에서만 받는다(폴백 :-16 제거 — 이중정의 방지).
PG_MAJOR="${POSTGRES_MAJOR:?POSTGRES_MAJOR 미설정(versions.sh 확인)}"
PG_BIN="/usr/pgsql-${PG_MAJOR}/bin"
PG_SETUP="${PG_BIN}/postgresql-${PG_MAJOR}-setup"
PG_DATA="/var/lib/pgsql/${PG_MAJOR}/data"
PG_SERVICE="postgresql-${PG_MAJOR}"

# ---- 가드 ① 외부 PG 사용 시 전체 스킵 ----
if [[ "${USE_BUNDLED_POSTGRES:-1}" == "0" ]]; then
  info "[postgres] USE_BUNDLED_POSTGRES=0 — 외부(기존) PostgreSQL 사용. 번들 PG 설치를 건너뜁니다."
  info "           backend.env 의 CONTROL_DB_*/PORTAL_DB_* 가 외부 PG 를 가리키는지 확인하세요."
  exit 0
fi

# ---- 가드 ② 번들 RPM 부재 시 스킵 ----
shopt -s nullglob
pg_rpms=("${PG_RPM_DIR}"/*.rpm)
shopt -u nullglob
if [[ "${#pg_rpms[@]}" -eq 0 ]]; then
  warn "[postgres] 번들된 PG16 RPM 이 없습니다(${PG_RPM_DIR}/*.rpm)."
  warn "           - 타깃에 이미 PG 가 있으면 USE_BUNDLED_POSTGRES=0 으로 설치하세요."
  warn "           - 번들이 필요하면 빌드머신에서 55-collect-postgresql.sh 를 실행해 채우세요."
  exit 0
fi

# ---- 가드 ③ PG RPM 무결성 파일 강제(M1) ----
# 수동으로 채운 검증불가 RPM 설치 차단. install.sh 의 SHA256SUMS 부재 warn+통과 경로를 PG 에 한해 강제(die).
if [[ ! -f "${PG_RPM_DIR}/SHA256SUMS" ]]; then
  die "[postgres] 무결성 파일이 없습니다: ${PG_RPM_DIR}/SHA256SUMS
       → 번들 PG RPM 의 무결성을 검증할 수 없어 설치를 거부합니다(공급망 보호).
       → 빌드머신에서 55-collect-postgresql.sh 로 RPM+SHA256SUMS 를 함께 채우세요."
fi

# ---- 이미 설치돼 있으면(멱등) RPM 설치 생략 ----
if [[ -x "${PG_SETUP}" ]]; then
  info "[postgres] PG${PG_MAJOR} 가 이미 설치돼 있습니다(${PG_SETUP}) — RPM 설치 생략."
else
  info "[postgres] PG${PG_MAJOR} RPM 오프라인 설치: ${#pg_rpms[@]} 개"
  if command -v dnf >/dev/null 2>&1; then
    # --disablerepo='*' 로 외부 네트워크 미접근. 의존성은 번들 RPM 들로 로컬 해소.
    # --setopt=gpgcheck=0: 최소 Rocky 9 폐쇄망 이미지의 PGDG GPG 키 부재로 실패하지 않도록 비활성
    #   (무결성은 번들 SHA256SUMS 로 install.sh 가 이미 검증).
    # L2: dnf 실패 시 곧장 die 하지 않고 warn 후 rpm -Uvh 로 폴백, 그마저 실패하면 die(명시 분리).
    if ! dnf install -y --disablerepo='*' --setopt=gpgcheck=0 "${pg_rpms[@]}"; then
      warn "[postgres] dnf 설치 실패 — rpm -Uvh 폴백을 시도합니다(의존성 미해소 시 실패할 수 있음)."
      rpm -Uvh --replacepkgs "${pg_rpms[@]}" \
        || die "[postgres] PG${PG_MAJOR} RPM 설치 실패(dnf·rpm 모두) — 06-troubleshooting.md 'PostgreSQL 번들 설치' 참고."
    fi
  elif command -v rpm >/dev/null 2>&1; then
    rpm -Uvh --replacepkgs "${pg_rpms[@]}" \
      || die "[postgres] PG${PG_MAJOR} RPM 설치 실패(rpm) — dnf 로 의존성 해소가 필요할 수 있습니다."
  else
    die "[postgres] dnf/rpm 이 없어 PG${PG_MAJOR} 를 설치할 수 없습니다."
  fi
  [[ -x "${PG_SETUP}" ]] || die "[postgres] 설치 후에도 ${PG_SETUP} 가 없습니다 — RPM 세트가 불완전합니다."
fi

# ---- initdb (멱등 — 이미 초기화돼 있으면 skip) ----
if [[ -s "${PG_DATA}/PG_VERSION" ]]; then
  info "[postgres] 데이터 디렉토리가 이미 초기화됨(생략): ${PG_DATA}"
else
  info "[postgres] initdb 실행: ${PG_SETUP} initdb"
  "${PG_SETUP}" initdb \
    || die "[postgres] initdb 실패 — ${PG_DATA} 권한/공간을 확인하세요(06-troubleshooting.md)."
  ok "[postgres] initdb 완료: ${PG_DATA}"
fi

# ---- postgresql.conf: listen_addresses / port ----
# 기본 'localhost' — backend 가 동일 호스트면 충분하다. 다른 호스트면 PG_LISTEN_ADDRESSES 로 확장.
PG_LISTEN_ADDRESSES="${PG_LISTEN_ADDRESSES:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_CONF="${PG_DATA}/postgresql.conf"
if [[ -f "${PG_CONF}" ]]; then
  # 멱등: 우리가 관리하는 블록을 매번 새로 써넣는다(중복 라인 방지 위해 기존 마커 블록 제거 후 append).
  pg_marker_begin="# >>> klid-onprem managed (10-install-postgresql) >>>"
  pg_marker_end="# <<< klid-onprem managed (10-install-postgresql) <<<"
  tmp_conf="$(mktemp)"
  # 기존 managed 블록 제거
  awk -v b="${pg_marker_begin}" -v e="${pg_marker_end}" '
    $0==b {skip=1; next} $0==e {skip=0; next} skip!=1 {print}
  ' "${PG_CONF}" > "${tmp_conf}"
  {
    cat "${tmp_conf}"
    printf '%s\n' "${pg_marker_begin}"
    printf "listen_addresses = '%s'\n" "${PG_LISTEN_ADDRESSES}"
    printf 'port = %s\n' "${PG_PORT}"
    printf '%s\n' "${pg_marker_end}"
  } > "${PG_CONF}.new"
  install -o postgres -g postgres -m 0600 "${PG_CONF}.new" "${PG_CONF}"
  rm -f "${tmp_conf}" "${PG_CONF}.new"
  ok "[postgres] postgresql.conf 설정: listen_addresses='${PG_LISTEN_ADDRESSES}', port=${PG_PORT}"
else
  warn "[postgres] postgresql.conf 를 찾을 수 없습니다(${PG_CONF}) — initdb 결과를 확인하세요."
fi

# ---- pg_hba.conf: 로컬/접속 허용(scram-sha-256) ----
# backend 가 동일 호스트(127.0.0.1)에서 비밀번호로 접속하므로 host 127.0.0.1/::1 을 scram 으로 허용.
# 추가 대역이 필요하면 PG_HBA_EXTRA_CIDR(예: 10.0.0.0/8)로 1줄 더 허용한다.
PG_HBA="${PG_DATA}/pg_hba.conf"
if [[ -f "${PG_HBA}" ]]; then
  # M2: 기존 pg_hba 에 trust(인증 우회) 라인이 남아있으면 경고(우리 블록은 scram 으로 추가하지만 잔류 인지).
  if grep -nE '\btrust\b' "${PG_HBA}" >/dev/null 2>&1; then
    warn "[postgres] pg_hba.conf 에 'trust' 인증 라인이 잔류합니다 — 인증 우회 위험. 운영 전 점검하세요:"
    grep -nE '\btrust\b' "${PG_HBA}" >&2 || true
  fi
  hba_marker_begin="# >>> klid-onprem managed (10-install-postgresql) >>>"
  hba_marker_end="# <<< klid-onprem managed (10-install-postgresql) <<<"
  tmp_hba="$(mktemp)"
  awk -v b="${hba_marker_begin}" -v e="${hba_marker_end}" '
    $0==b {skip=1; next} $0==e {skip=0; next} skip!=1 {print}
  ' "${PG_HBA}" > "${tmp_hba}"
  {
    cat "${tmp_hba}"
    printf '%s\n' "${hba_marker_begin}"
    echo "# klid-label backend 접속 허용(동일 호스트). 비밀번호 인증(scram-sha-256)."
    echo "host    all    all    127.0.0.1/32    scram-sha-256"
    echo "host    all    all    ::1/128         scram-sha-256"
    if [[ -n "${PG_HBA_EXTRA_CIDR:-}" ]]; then
      printf 'host    all    all    %s    scram-sha-256\n' "${PG_HBA_EXTRA_CIDR}"
    fi
    printf '%s\n' "${hba_marker_end}"
  } > "${PG_HBA}.new"
  install -o postgres -g postgres -m 0600 "${PG_HBA}.new" "${PG_HBA}"
  rm -f "${tmp_hba}" "${PG_HBA}.new"
  ok "[postgres] pg_hba.conf 설정: 127.0.0.1/::1 scram-sha-256${PG_HBA_EXTRA_CIDR:+ + ${PG_HBA_EXTRA_CIDR}}"
else
  warn "[postgres] pg_hba.conf 를 찾을 수 없습니다(${PG_HBA}) — initdb 결과를 확인하세요."
fi

# ---- 서비스 활성화 + 기동 ----
if command -v systemctl >/dev/null 2>&1; then
  info "[postgres] 서비스 기동: systemctl enable --now ${PG_SERVICE}"
  systemctl enable --now "${PG_SERVICE}" \
    || die "[postgres] ${PG_SERVICE} 기동 실패 — 'journalctl -u ${PG_SERVICE}' 로 원인 확인."
  # conf/hba 변경이 이미 반영되도록(이미 떠 있던 경우) reload.
  systemctl reload "${PG_SERVICE}" 2>/dev/null \
    || warn "[postgres] ${PG_SERVICE} reload 실패 — conf/hba 미반영 가능, 수동 reload 필요(systemctl reload ${PG_SERVICE})."
  ok "[postgres] ${PG_SERVICE} 기동 완료(포트 ${PG_PORT})."
else
  warn "[postgres] systemctl 이 없습니다 — ${PG_SERVICE} 를 수동으로 기동하세요."
fi

# ---- systemd 기동순서 drop-in (H1) ----
# 번들 PG 의 유닛명은 postgresql-16.service 라, backend 유닛의 After=postgresql.service(이름 불일치)
# 만으로는 부팅 시 순서 보장이 안 된다. 번들 PG 사용 시에만 drop-in 으로 실제 유닛명 순서를 묶는다
# (외부 PG 면 이 스크립트는 앞에서 exit 0 되므로 미생성).
if command -v systemctl >/dev/null 2>&1; then
  PG_DROPIN_DIR="/etc/systemd/system/klid-backend.service.d"
  PG_DROPIN="${PG_DROPIN_DIR}/10-pg16-after.conf"
  ensure_dir "${PG_DROPIN_DIR}"
  {
    printf '[Unit]\n'
    printf 'After=%s.service\n' "${PG_SERVICE}"
    printf 'Wants=%s.service\n' "${PG_SERVICE}"
  } > "${PG_DROPIN}"
  systemctl daemon-reload \
    || warn "[postgres] systemctl daemon-reload 실패 — drop-in(${PG_DROPIN}) 반영을 위해 수동 reload 필요."
  ok "[postgres] 기동순서 drop-in 생성: ${PG_DROPIN} (After/Wants=${PG_SERVICE}.service)"
else
  warn "[postgres] systemctl 이 없어 기동순서 drop-in 을 생성하지 못했습니다 — backend 전 PG 기동을 수동 보장하세요."
fi

ok "[postgres] 번들 PG${PG_MAJOR} 설치 완료. DB/유저 생성은 15-init-db.sh 가 담당합니다(테이블은 backend Flyway)."
