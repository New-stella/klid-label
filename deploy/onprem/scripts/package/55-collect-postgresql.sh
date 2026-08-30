#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 55-collect-postgresql.sh — [빌드머신] PostgreSQL 16 RPM 수집 (RHEL 8.9 / el8 / PGDG)
#
#   타깃 OS = 레드햇 엔터프라이즈 리눅스 8.9 (RHEL 8 계열, x86_64, glibc 2.28, dnf/rpm).
#   ⚠ 구 서술 폐기(2026-08-28) — "Rocky Linux 9 (RHEL 9 계열, glibc 2.34)".
#     PGDG 도 EL-9 → EL-8 리포로 바뀐다(versions.sh PGDG_REPO_RPM_URL). el9 PG RPM 은
#     RHEL 8 에 설치되지 않는다.
#
#   ★ 번들 PG 는 "옵션"이다. 타깃에 이미 PostgreSQL 이 있으면 외부 PG 를 쓰면 되고
#     (설치 단계에서 USE_BUNDLED_POSTGRES=0), 이 수집도 SKIP_POSTGRES=1 로 끌 수 있다.
#   ★ DB 스키마는 backend Flyway 가 자동 부트스트랩하므로(LS_*·MNG_*·QRTZ_* 를
#     CREATE TABLE IF NOT EXISTS), PG 사전요건은 "빈 DB 2개 + 접속 사용자"뿐이다.
#
#   수집물: PGDG 의 PG16 RPM (+전이 의존성) → syspkgs/postgresql/
#     postgresql16-server / postgresql16 / postgresql16-libs / postgresql16-contrib
#
#   ★ 수집 환경: dnf 가 있으면 네이티브로, 없으면(mac 등) docker 로 el8 컨테이너를 띄워 수집한다.
#     둘 다 없으면 graceful SKIP + 수동 수집 안내를 남긴다(02-build-package.md 참고).
#   ★ 컨테이너는 반드시 --platform linux/amd64 로 띄운다 — Apple Silicon 에서 생략하면
#     aarch64 RPM 을 받아 놓고 "성공"으로 끝나는 조용한 실패가 된다.
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
GPG_OUT="${ONPREM}/syspkgs/gpg"
ensure_dir "${PG_OUT}" "${GPG_OUT}"

if [[ "${SKIP_POSTGRES:-0}" == "1" ]]; then
  info "[postgres] SKIP_POSTGRES=1 — PostgreSQL 16 RPM 수집 생략(외부 PG 사용 가정)"
  exit 0
fi

# ----------------------------------------------------------------------------
# PGDG PG16 RPM 수집 (dnf/yum 환경에서만)
# ----------------------------------------------------------------------------
# 수집 명령 본문 — 네이티브 dnf / 컨테이너 양쪽이 같은 스크립트를 실행한다.
#   $1 = PG RPM 출력 디렉토리 (실행 환경 기준 경로)
# ★ --resolve --alldeps + createrepo_c 는 한 세트다(50-collect-syspkgs.sh 의 근거 주석 참조).
#   --alldeps 없이 받으면 타깃에 없는 의존이 누락되고, 로컬 저장소 없이 RPM 파일을 직접
#   dnf 에 넘기면 기반 패키지 충돌로 설치가 통째로 실패한다(2026-08-30 실측).
# ★ --archlist=x86_64,noarch 로 i686 멀티리브를 제외한다(타깃 x86_64 전용).
_pg_body() {
  cat <<'BODY'
set -euo pipefail
PG_OUT="$1"; GPG_OUT="$2"; shift 2
dnf install -y dnf-plugins-core createrepo_c >/dev/null
echo "[collect] PGDG repo 추가: ${PGDG_REPO_RPM_URL}"
dnf install -y "${PGDG_REPO_RPM_URL}" >/dev/null
# 내장(AppStream) postgresql 모듈 비활성 — PGDG 패키지와 충돌 방지(미적용 시 구버전이 깔림).
dnf -qy module disable postgresql >/dev/null 2>&1 || echo "WARN: module disable postgresql 실패(모듈 미존재일 수 있음)" >&2
echo "[collect] PG16 RPM 수집(전이 의존 포함) → ${PG_OUT}"
dnf download --resolve --alldeps --archlist=x86_64,noarch --downloaddir "${PG_OUT}" ${POSTGRES_RPM_PKGS[@]}
# 로컬 yum 저장소 메타데이터 — 타깃이 이 repodata 로 의존성을 스스로 해소한다.
echo "[collect] 로컬 저장소 메타데이터 생성(createrepo_c)"
createrepo_c --quiet "${PG_OUT}"
# PGDG GPG 공개키 반입 — 폐쇄망 타깃에 이 키가 없으면 gpgcheck=1 설치가 거부된다.
mkdir -p "${GPG_OUT}"
cp -f /etc/pki/rpm-gpg/RPM-GPG-KEY-* "${GPG_OUT}/" 2>/dev/null || true
ls -1 "${GPG_OUT}" | sed 's/^/  key: /'
BODY
}

_pg_env() {
  printf 'PGDG_REPO_RPM_URL=%q\n' "${PGDG_REPO_RPM_URL}"
  printf 'POSTGRES_RPM_PKGS=(%s)\n' "${POSTGRES_RPM_PKGS[*]}"
}

collect_pg_native() {
  command -v dnf >/dev/null 2>&1 || return 1
  info "[postgres] 네이티브 dnf 로 수집합니다(빌드머신이 el8 계열이라고 가정)."
  bash -c "$( _pg_env; _pg_body )" _ "${PG_OUT}" "${GPG_OUT}" \
    || { warn "[postgres] dnf 수집 실패 — 부분 수집을 성공으로 위장하지 않고 폴백/안내로 전환합니다."; return 1; }
  return 0
}

collect_pg_docker() {
  command -v docker >/dev/null 2>&1 || return 1
  docker info >/dev/null 2>&1 || { warn "[postgres] docker 데몬이 응답하지 않습니다 — 컨테이너 수집 불가."; return 1; }
  info "[postgres] dnf 가 없어 ${EL8_BUILDER_IMAGE} 컨테이너로 수집합니다(--platform ${EL8_BUILDER_PLATFORM})."
  docker run --rm \
    --platform "${EL8_BUILDER_PLATFORM}" \
    -v "${ONPREM}/syspkgs:/sys" \
    "${EL8_BUILDER_IMAGE}" \
    bash -c "$( _pg_env; _pg_body )" _ /sys/postgresql /sys/gpg \
    || { warn "[postgres] 컨테이너 수집 실패 — 부분 수집을 성공으로 위장하지 않고 폴백/안내로 전환합니다."; return 1; }
  return 0
}

collect_pg() {
  if collect_pg_native; then :
  elif collect_pg_docker; then :
  else return 1
  fi

  # M3: 단순 개수 비교 대신 핵심 패키지별 존재 확인(누락 패키지를 정확히 짚어 fail).
  local pkg
  for pkg in "${POSTGRES_RPM_PKGS[@]}"; do
    ls "${PG_OUT}"/${pkg}-*.rpm >/dev/null 2>&1 \
      || { warn "[postgres] 핵심 RPM 누락: ${pkg} — 불완전 세트로 간주, 폴백/안내로 전환합니다."; return 1; }
  done
  # 아키텍처 오염 검사 — --platform 미지정 등으로 타깃과 다른 아키텍처가 섞이면 fail.
  local bad
  bad="$(ls -1 "${PG_OUT}"/*.rpm 2>/dev/null | grep -cE '\.(aarch64|i686|armv7hl|ppc64le|s390x)\.rpm$' || true)"
  if [[ "${bad}" -gt 0 ]]; then
    warn "[postgres] 타깃(x86_64)과 다른 아키텍처 RPM ${bad} 개가 섞였습니다: ${PG_OUT}"
    warn "          컨테이너에 --platform ${EL8_BUILDER_PLATFORM} 를 지정했는지 확인하세요."
    return 1
  fi
  local count
  count="$(ls -1 "${PG_OUT}"/*.rpm 2>/dev/null | wc -l | tr -d ' ')"
  [[ -f "${PG_OUT}/repodata/repomd.xml" ]] \
    || { warn "[postgres] 로컬 저장소 메타데이터 누락: ${PG_OUT}/repodata/repomd.xml — 타깃에서 의존성 해소가 불가합니다."; return 1; }
  ok "[postgres] PG16 RPM 수집: ${PG_OUT}  (${count} 개, 핵심 패키지 ${#POSTGRES_RPM_PKGS[@]} 종 확인)"
  sha256_write "${PG_OUT}"
  return 0
}

if collect_pg; then
  :
else
  warn "[postgres] dnf/docker 부재이거나 수집 실패 — PG16 RPM 수집을 건너뜁니다(graceful SKIP)."
  warn "  타깃 RHEL 8.9 용 PG16 RPM 은 el8 컨테이너에서 수집해야 합니다. 예:"
  warn "    docker run --rm --platform ${EL8_BUILDER_PLATFORM} -v \"\$PWD/syspkgs:/sys\" ${EL8_BUILDER_IMAGE} bash -lc '"
  warn "      dnf install -y createrepo_c ${PGDG_REPO_RPM_URL} && \\"
  warn "      dnf -qy module disable postgresql && \\"
  warn "      dnf download --resolve --alldeps --archlist=x86_64,noarch --downloaddir /sys/postgresql ${POSTGRES_RPM_PKGS[*]} && \\"
  warn "      createrepo_c /sys/postgresql'"
  warn "  (타깃에 이미 PG 가 있으면 SKIP_POSTGRES=1 로 끄고 USE_BUNDLED_POSTGRES=0 으로 설치하세요.)"
  cat > "${PG_OUT}/README-collect-on-el8.txt" <<TXT
이 빌드머신에서 PostgreSQL 16 RPM 을 수집하지 못했습니다(dnf/docker 부재 또는 수집 실패).

타깃 OS = ${TARGET_OS_LABEL}
  ⚠ 구 서술 폐기(2026-08-28): "Rocky Linux 9 / el9". el9 PG RPM 은 RHEL 8 에 설치되지 않습니다.

타깃용 PG16 RPM 을 아래처럼 el8 컨테이너에서 수집하세요(deploy/onprem 에서 실행):

  docker run --rm --platform ${EL8_BUILDER_PLATFORM} -v "\$PWD/syspkgs:/sys" ${EL8_BUILDER_IMAGE} bash -lc '
    dnf install -y ${PGDG_REPO_RPM_URL} && \\
    dnf -qy module disable postgresql && \\
    dnf install -y createrepo_c &&
    dnf download --resolve --alldeps --archlist=x86_64,noarch --downloaddir /sys/postgresql ${POSTGRES_RPM_PKGS[*]} &&
    createrepo_c /sys/postgresql &&
    mkdir -p /sys/gpg && cp /etc/pki/rpm-gpg/RPM-GPG-KEY-* /sys/gpg/
  '

수집 후 체크섬을 기록하세요:
  ( cd syspkgs/postgresql && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )

수집 대상(PostgreSQL ${POSTGRES_MAJOR}): ${POSTGRES_RPM_PKGS[*]}

주의:
  - --platform ${EL8_BUILDER_PLATFORM} 를 반드시 지정하세요. Apple Silicon 에서 빠뜨리면
    aarch64 RPM 을 받아 놓고 "성공"으로 끝나는 조용한 실패가 됩니다.
  - --alldeps 와 createrepo_c 는 <한 세트>입니다. --alldeps 없이 받으면 타깃에 없는 의존이
    누락되고, createrepo_c 없이 RPM 파일을 직접 dnf 에 넘기면 기반 패키지 충돌로 설치가
    실패합니다(2026-08-30 실측).
  - syspkgs/gpg/RPM-GPG-KEY-* 도 함께 반입해야 타깃에서 서명 검증(gpgcheck=1)이 통과합니다.

번들 PG 가 불필요하면(타깃에 이미 PG 가 있으면):
  - 수집:  SKIP_POSTGRES=1 ./scripts/package.sh
  - 설치:  USE_BUNDLED_POSTGRES=0 ./scripts/install.sh
TXT
fi
