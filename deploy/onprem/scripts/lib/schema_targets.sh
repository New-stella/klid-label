#!/usr/bin/env bash
# 증분 마이그레이션이 <무엇을 만드는가>를 뽑고, 통합 스키마에 그것이 있는지 대조한다.
#
# ★ 왜 있나 — 표 생성문만 보던 검사가 컬럼·제약 추가를 놓쳤다. 통합 스키마를 재생성하지 않고
#   매체를 조립해도 초록으로 통과했고, 그러면 <새로 세우는 장비에는 그 컬럼이 끝내 생기지 않는다>.
#   근거 결정: ADR-064 · 반입 명세 DEPLOY-001.
#
# ★★ 이 파일의 검사기는 <자기 자신을 먼저 시험한다>(schema_targets_selftest). 검사기가 아는 사실을
#   못 잡으면 0 건은 「깨끗하다」가 아니라 「검사기가 눈이 멀었다」이므로, 호출부는 반드시 자체
#   시험을 먼저 돌리고 실패하면 그 자리에서 멈춘다.
#
# ⚠ 이식성 — GNU 전용 문법을 쓰지 않는다. `grep -P`·`\b`·awk `gensub` 은 BSD(mac)에서 동작하지
#   않으며, 동작하지 않을 때 <오류가 아니라 0 건>으로 흘러 fail-open 이 된다(이 저장소의 실사고).

# 증분 SQL 한 파일에서 검사 대상을 뽑는다. 한 줄에 하나씩:
#   TABLE|<표>            CREATE TABLE [IF NOT EXISTS] <표>
#   COLUMN|<표>|<컬럼>    ALTER TABLE <표> ... ADD COLUMN [IF NOT EXISTS] <컬럼>
#   CONSTRAINT|<이름>     ADD CONSTRAINT <이름>          (그 파일에서 DROP 되지 않은 것)
#   REPLACED|<이름>       DROP 뒤 다시 ADD — <이름만으로는 새 정의인지 알 수 없다>
#   INDEX|<이름>          CREATE [UNIQUE] INDEX [IF NOT EXISTS] <이름>
schema_targets_of() {
  awk '
    # 줄 주석 제거 후 한 줄로 잇는다. 문장 경계는 세미콜론이다.
    { line = $0; sub(/--.*$/, "", line); buf = buf " " line }
    END {
      n = split(buf, stmt, ";")
      for (i = 1; i <= n; i++) {
        s = tolower(stmt[i])
        gsub(/[ \t\r\n]+/, " ", s)
        sub(/^ /, "", s)

        # CREATE TABLE
        if (match(s, /^create table( if not exists)? [a-z0-9_.]+/)) {
          t = substr(s, RSTART, RLENGTH); sub(/^.* /, "", t); sub(/^.*\./, "", t)
          if (t != "") print "TABLE|" t
        }

        # CREATE INDEX
        if (match(s, /^create( unique)? index( concurrently)?( if not exists)? [a-z0-9_.]+/)) {
          t = substr(s, RSTART, RLENGTH); sub(/^.* /, "", t); sub(/^.*\./, "", t)
          if (t != "") print "INDEX|" t
        }

        # 이 문장이 다루는 표 (ALTER TABLE [ONLY] <표>)
        tbl = ""
        if (match(s, /alter table (only )?[a-z0-9_.]+/)) {
          tbl = substr(s, RSTART, RLENGTH); sub(/^.* /, "", tbl); sub(/^.*\./, "", tbl)
        }

        # ADD COLUMN — 한 문장에 여러 개일 수 있어 훑는다.
        rest = s
        while (match(rest, /add column( if not exists)? [a-z0-9_]+/)) {
          seg = substr(rest, RSTART, RLENGTH); rest = substr(rest, RSTART + RLENGTH)
          c = seg; sub(/^.* /, "", c)
          if (tbl != "" && c != "") print "COLUMN|" tbl "|" c
        }

        # DROP / ADD CONSTRAINT — 같은 파일 안에서 짝이면 <교체>다.
        rest = s
        while (match(rest, /drop constraint( if exists)? [a-z0-9_]+/)) {
          seg = substr(rest, RSTART, RLENGTH); rest = substr(rest, RSTART + RLENGTH)
          c = seg; sub(/^.* /, "", c); if (c != "") dropped[c] = 1
        }
        rest = s
        while (match(rest, /add constraint [a-z0-9_]+/)) {
          seg = substr(rest, RSTART, RLENGTH); rest = substr(rest, RSTART + RLENGTH)
          c = seg; sub(/^.* /, "", c); if (c != "") added[c] = 1
        }
      }
      for (c in added) print (c in dropped) ? "REPLACED|" c : "CONSTRAINT|" c
    }
  ' "$1" | sort -u
}

# 통합 스키마에서 <표> 블록의 본문만 떼어 낸다(CREATE TABLE ... 여는 괄호부터 닫는 ");" 까지).
schema_block_of() {
  awk -v want="$2" '
    BEGIN { inblk = 0 }
    {
      l = tolower($0)
      if (!inblk && match(l, /^create table( if not exists)? [a-z0-9_.]+ *\(/)) {
        t = substr(l, RSTART, RLENGTH); sub(/ *\($/, "", t); sub(/^.* /, "", t); sub(/^.*\./, "", t)
        if (t == want) { inblk = 1; next }
      }
      if (inblk) { if (l ~ /^\);/) { inblk = 0 } else print l }
    }
  ' "$1"
}

# 대상 한 건이 통합 스키마에 있는지 판정한다. OK / MISSING / UNVERIFIABLE 을 표준출력으로 낸다.
schema_target_verdict() {
  local schema="$1" target="$2" kind name tbl col
  kind="${target%%|*}"
  case "${kind}" in
    TABLE)
      name="${target#TABLE|}"
      if schema_block_of "${schema}" "${name}" | grep -q .; then echo OK; else echo MISSING; fi ;;
    COLUMN)
      tbl="${target#COLUMN|}"; col="${tbl#*|}"; tbl="${tbl%%|*}"
      # 그 표의 블록 안에서 <컬럼 정의 줄>로 시작하는지 본다 — 전역 검색은 다른 표의 동명
      # 컬럼에 걸려 없는 것을 있다고 말한다.
      if schema_block_of "${schema}" "${tbl}" | grep -qE "^[[:space:]]*${col}[[:space:]]"; then
        echo OK; else echo MISSING; fi ;;
    CONSTRAINT|INDEX)
      name="${target#*|}"
      if grep -qiw "${name}" "${schema}"; then echo OK; else echo MISSING; fi ;;
    REPLACED)
      # 이름은 그대로 두고 정의만 바뀌므로 <이름 대조로는 새 정의인지 알 수 없다>.
      # 있다고 말하면 거짓이 되고 없다고 말해도 거짓이라, 사람에게 넘긴다.
      echo UNVERIFIABLE ;;
    *) echo UNVERIFIABLE ;;
  esac
}

# ★ 자체 양성 대조 — 검사기가 <아는 사실>을 실제로 잡는지 먼저 단언한다.
#   실패하면 0 건이 「깨끗하다」가 아니라 「눈이 멀었다」는 뜻이므로 호출부는 즉시 멈춰야 한다.
schema_targets_selftest() {
  local tmp expect got
  tmp="$(mktemp)"
  cat > "${tmp}" <<'SQL'
-- 주석에 CREATE TABLE ls_should_not_appear 가 있어도 잡히면 안 된다.
CREATE TABLE IF NOT EXISTS klid_at.ls_selftest (id bigint);
ALTER TABLE ls_marking
    ADD COLUMN IF NOT EXISTS vrfc_evnt_type_cd varchar(20);
ALTER TABLE ls_ai_srvr
    DROP CONSTRAINT IF EXISTS ck_relaxed;
ALTER TABLE ls_ai_srvr ADD CONSTRAINT ck_relaxed CHECK (id > 0);
ALTER TABLE ls_ai_srvr ADD CONSTRAINT ck_brand_new CHECK (id < 9);
CREATE UNIQUE INDEX IF NOT EXISTS ux_selftest ON klid_at.ls_selftest (id);
SQL
  expect="$(printf '%s\n' \
    'COLUMN|ls_marking|vrfc_evnt_type_cd' \
    'CONSTRAINT|ck_brand_new' \
    'INDEX|ux_selftest' \
    'REPLACED|ck_relaxed' \
    'TABLE|ls_selftest' | sort)"
  got="$(schema_targets_of "${tmp}")"
  rm -f "${tmp}"
  if [[ "${got}" != "${expect}" ]]; then
    printf '추출 축 실패 — 기대:\n%s\n실제:\n%s\n' "${expect}" "${got}" >&2
    return 1
  fi

  # ★★ 판정 축도 함께 시험한다 — 뽑기만 하고 <대조가 그것을 잡지 못하면> 결과는 여전히 0 건이다.
  #   반드시 걸려야 하는 것(통합 파일에 없는 컬럼)과 반드시 통과해야 하는 것(있는 컬럼)을 짝으로 둔다.
  #   ⚠ 걸려야 하는 쪽만 두면 「전부 MISSING 이라 잡힌 것」과 구분되지 않는다.
  local schema="$1"
  [[ -n "${schema}" && -f "${schema}" ]] || { printf '판정 축 시험에 통합 스키마 경로가 필요합니다\n' >&2; return 1; }

  # 반드시 잡혀야 한다 — 다른 표에는 있는 컬럼이라, 표 안에서 보지 않으면 <있다>고 잘못 말한다.
  if [[ "$(schema_target_verdict "${schema}" 'COLUMN|ls_marking|chck_dt')" != "MISSING" ]]; then
    printf '판정 축 실패 — 통합 스키마에 없는 컬럼을 잡지 못했습니다(다른 표의 동명 컬럼에 속은 것입니다).\n' >&2
    return 1
  fi
  # 반드시 통과해야 한다 — 실재하는 표·컬럼.
  if [[ "$(schema_target_verdict "${schema}" 'TABLE|ls_ai_srvr')" != "OK" \
     || "$(schema_target_verdict "${schema}" 'COLUMN|ls_ai_srvr|chck_dt')" != "OK" ]]; then
    printf '판정 축 실패 — 실재하는 표·컬럼을 없다고 말했습니다(음성 대조 실패).\n' >&2
    return 1
  fi
  return 0
}
