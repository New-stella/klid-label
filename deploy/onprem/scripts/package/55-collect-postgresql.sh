#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 55-collect-postgresql.sh — [빌드머신] PostgreSQL 16 RPM 수집 (Rocky 9 / PGDG)
#
#   타깃 OS = Rocky Linux 9 (RHEL 9 계열, x86_64, glibc 2.34, dnf/rpm).
#
#   ★ 번들 PG 는 "옵션"이다. 타깃에 이미 PostgreSQL 이 있으면 외부 PG 를 쓰면 되고
#     (설치 단계에서 USE_BUNDLED_POSTGRES=0), 이 수집도 SKIP_POSTGRES=1 로 끌 수 있다.
#   ★ DB 스키마는 backend Flyway 가 자동 부트스트랩하므로(LS_*·MNG_*·QRTZ_* 를
#     CREATE TABLE IF NOT EXISTS), PG 사전요건은 "빈 DB 2개 + 접속 사용자"뿐이다.
#
#   수집물: PGDG 의 PG16 RPM (+전이 의존성) → syspkgs/postgresql/
#     postgresql16-server / postgresql16 / postgresql16-libs / postgresql16-contrib
#
#   ★ dnf/yum 환경에서만 수행한다. 비-RHEL 빌드머신(mac 등)은 graceful SKIP +
#     rockylinux:9 컨테이너 수집 안내를 남긴다(02-build-package.md 참고).
#
#   ⚠ PGDG repo RPM(...repo-latest.noarch.rpm)은 URL 이 가변(latest)이라 버전 고정
#     체크섬 핀이 불가하다. 이 스크립트는 repo RPM 을 설치해 PGDG repo 메타만 추가하고
#     그것으로 PG16 RPM 을 받는다. 받은 *.rpm 전송 무결성은 SHA256SUMS 로 검증한다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"

ONPREM="$(onprem_root)"
PG_OUT="${ONPREM}/syspkgs/postgresql"
ensure_dir "${PG_OUT}"

if [[ "${SKIP_POSTGRES:-0}" == "1" ]]; then
  info "[postgres] SKIP_POSTGRES=1 — PostgreSQL 16 RPM 수집 생략(외부 PG 사용 가정)"
  exit 0
fi

# ----------------------------------------------------------------------------
# PGDG PG16 RPM 수집 (dnf/yum 환경에서만)
# ----------------------------------------------------------------------------
collect_pg() {
  command -v dnf >/dev/null 2>&1 || return 1

  info "[postgres] PGDG repo 추가: ${PGDG_REPO_RPM_URL}"
  # repo RPM 은 PGDG repo 메타데이터만 추가한다(이미 설치돼 있으면 무해).
  dnf install -y "${PGDG_REPO_RPM_URL}" \
    || { warn "[postgres] PGDG repo RPM 설치 실패(네트워크/권한) — 수집 폴백/안내로 전환합니다."; return 1; }

  # 내장(AppStream) postgresql 모듈 비활성 — PGDG 패키지와 충돌 방지(미적용 시 구버전이 깔림).
  info "[postgres] 내장 postgresql 모듈 비활성화"
  dnf -qy module disable postgresql \
    || warn "[postgres] 'module disable postgresql' 실패(모듈 미존재일 수 있음) — 계속 진행."

  info "[postgres] PG16 RPM 수집(의존성 포함): ${POSTGRES_RPM_PKGS[*]}"
  # --resolve --alldeps: 전이 의존성까지. download 서브커맨드 자체가 다운로드만 수행.
  dnf download --resolve --alldeps --downloaddir "${PG_OUT}" "${POSTGRES_RPM_PKGS[@]}" \
    || { warn "[postgres] dnf download 실패 — 부분 수집을 성공으로 위장하지 않고 폴백/안내로 전환합니다."; return 1; }

  # M3: 단순 개수 비교 대신 핵심 패키지별 존재 확인(누락 패키지를 정확히 짚어 fail).
  local pkg
  for pkg in "${POSTGRES_RPM_PKGS[@]}"; do
    ls "${PG_OUT}"/${pkg}-*.rpm >/dev/null 2>&1 \
      || { warn "[postgres] 핵심 RPM 누락: ${pkg} — 불완전 세트로 간주, 폴백/안내로 전환합니다."; return 1; }
  done
  local count
  count="$(ls -1 "${PG_OUT}"/*.rpm 2>/dev/null | wc -l | tr -d ' ')"
  ok "[postgres] PG16 RPM 수집: ${PG_OUT}  (${count} 개, 핵심 패키지 ${#POSTGRES_RPM_PKGS[@]} 종 확인)"
  sha256_write "${PG_OUT}"
  return 0
}

if collect_pg; then
  :
else
  warn "[postgres] dnf 가 없는 빌드머신(예: macOS)이거나 수집 실패 — PG16 RPM 수집을 건너뜁니다(graceful SKIP)."
  warn "  타깃 Rocky 9 용 PG16 RPM 은 rockylinux:9 컨테이너에서 수집해야 합니다. 예:"
  warn "    docker run --rm -v \"\$PWD/../..:/work\" -w /work/deploy/onprem rockylinux:9 bash -lc '"
  warn "      dnf install -y ${PGDG_REPO_RPM_URL} && \\"
  warn "      dnf -qy module disable postgresql && \\"
  warn "      dnf download --resolve --alldeps --downloaddir syspkgs/postgresql ${POSTGRES_RPM_PKGS[*]}'"
  warn "  (타깃에 이미 PG 가 있으면 SKIP_POSTGRES=1 로 끄고 USE_BUNDLED_POSTGRES=0 으로 설치하세요.)"
  cat > "${PG_OUT}/README-collect-on-rocky9.txt" <<TXT
이 빌드머신에는 dnf 가 없어(또는 수집 실패) Rocky 9 용 PostgreSQL 16 RPM 을 수집하지 못했습니다.
타깃(Rocky Linux 9, x86_64)용 PG16 RPM 을 아래처럼 rockylinux:9 컨테이너에서 수집하세요:

  docker run --rm -v "\$PWD/../..:/work" -w /work/deploy/onprem rockylinux:9 bash -lc '
    dnf install -y ${PGDG_REPO_RPM_URL} && \\
    dnf -qy module disable postgresql && \\
    dnf download --resolve --alldeps --downloaddir syspkgs/postgresql ${POSTGRES_RPM_PKGS[*]} && \\
    ( cd syspkgs/postgresql && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )
  '

수집 대상(PostgreSQL ${POSTGRES_MAJOR}): ${POSTGRES_RPM_PKGS[*]}

번들 PG 가 불필요하면(타깃에 이미 PG 가 있으면):
  - 수집:  SKIP_POSTGRES=1 ./scripts/package.sh
  - 설치:  USE_BUNDLED_POSTGRES=0 ./scripts/install.sh
TXT
fi
