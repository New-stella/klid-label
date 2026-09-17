#!/bin/bash
# standard-term-guard.sh - PostToolUse Hook (Edit|Write matcher, KLID 프로젝트 전용)
#
# 목적: DB 마이그레이션 SQL / JPA 엔티티에 새 컬럼·테이블 DDL 이 들어가면
#       "표준용어(물리명) + 표준도메인(타입·크기)" 검증을 잊지 않도록 write 시점에 넛지한다.
#       수동 규칙(CLAUDE.md/memory)도 사후 QA(database-reviewer)도 놓쳐서 재작업이 반복되던
#       표준용어 드리프트를, 항상 발동하는 결정론적 계층으로 차단(서브에이전트 컨텍스트에도 도달).
#
# 동작: 차단하지 않음(넛지). stderr=사람용 경고 + stdout JSON(additionalContext)=모델용 리마인더.
# Exit codes: 0 = always allow (nudge only)

INPUT=$(cat)
if [[ -z "$INPUT" ]]; then
    exit 0
fi

exec python3 - <<'PYEOF' "$INPUT"
import sys, json, os, re

raw = sys.argv[1] if len(sys.argv) > 1 else ''
try:
    d = json.loads(raw)
except Exception:
    sys.exit(0)

tool_name = d.get('tool_name', '')
ti = d.get('tool_input', {})
if tool_name not in ('Write', 'Edit') or not isinstance(ti, dict):
    sys.exit(0)

file_path = ti.get('file_path', ti.get('path', '')) or ''
if not file_path:
    sys.exit(0)

# 이번 변경으로 새로 들어간 내용 (Write=content, Edit=new_string)
new_text = ti.get('content')
if new_text is None:
    new_text = ti.get('new_string', '')
if not isinstance(new_text, str):
    new_text = str(new_text)

norm = file_path.replace('\\', '/')
basename = os.path.basename(norm)

# --- 대상 파일 판별 ---
is_migration = bool(re.search(r'/db/migration/V.*\.sql$', norm, re.I)) or \
               (basename.lower().endswith('.sql') and '/migration/' in norm.lower())
is_entity = norm.endswith('.java') and (
    '/entity/' in norm or
    re.search(r'@Entity\b|@Table\s*\(|@Column\s*\(', new_text)
)

if not (is_migration or is_entity):
    sys.exit(0)

# --- 새 컬럼/테이블 DDL 이 실제로 있는지 (없으면 조용히 통과) ---
ddl_signals = []
if is_migration:
    if re.search(r'\bCREATE\s+TABLE\b', new_text, re.I):        ddl_signals.append('CREATE TABLE')
    if re.search(r'\bADD\s+COLUMN\b', new_text, re.I):          ddl_signals.append('ADD COLUMN')
    if re.search(r'\bALTER\s+TABLE\b.*\bADD\b', new_text, re.I | re.S): ddl_signals.append('ALTER TABLE ADD')
    if re.search(r'\bRENAME\s+COLUMN\b', new_text, re.I):       ddl_signals.append('RENAME COLUMN')
    if re.search(r'\bCREATE\s+(OR\s+REPLACE\s+)?VIEW\b', new_text, re.I): ddl_signals.append('CREATE VIEW')
if is_entity:
    if re.search(r'@Column\s*\(', new_text):                    ddl_signals.append('@Column')
    if re.search(r'@Table\s*\(', new_text):                     ddl_signals.append('@Table')

if not ddl_signals:
    sys.exit(0)

kind = '마이그레이션 SQL' if is_migration else 'JPA 엔티티'
signals = ', '.join(ddl_signals)

# --- 사람용 경고 (stderr) ---
sys.stderr.write(f"[standard-term-guard] ⚠ {kind} 에 스키마 정의 감지: {basename} ({signals})\n")
sys.stderr.write("  새 컬럼/테이블은 물리명 + 타입 + 크기를 표준용어·표준도메인으로 검증해야 합니다(행안부 → 사업 순, docs/rules/klid-db-policy.md).\n")

# --- 모델용 리마인더 (stdout JSON: additionalContext) ---
reminder = (
    "🛑 표준용어 게이트 (KLID 감리 기준 §7-5) — 방금 " + kind + " 에 " + signals + " 를 작성했다. "
    "커밋/다음 단계 전에 이 파일의 새 컬럼·테이블에 대해 아래를 반드시 검증하고, 어긋나면 지금 정정하라:\n"
    "1) 물리명 = 표준단어 약어 조합인가? (임의 약어 금지)\n"
    "2) 타입 + 크기 = 그 용어의 표준도메인과 일치하는가? (예: 코드값=VARCHAR(20). 32 등 임의 크기 금지 — 드리프트 결함)\n"
    "우선순위: ① 행안부 공통표준 → ② 사업 표준 → ③ 둘 다 없을 때만 신규 등록. 같은 개념이 양쪽에 있으면 행안부 약어를 쓴다(예: 재시도 RTRY ○ / RTY ✗). 도메인(타입·크기)도 같은 순서다. "
    "검증처(판정은 CSV 로만, 레포 안 정본): `docs/LogiCraft-공공표준용어-2026.08.05 151557/`(공통표준단어·공통표준도메인·공통표준용어.csv) 먼저, "
    "`docs/LogiCraft-사업용어-2026.08.05 151552/`(사업표준단어·사업표준도메인·사업표준용어.csv) 다음. 디렉터리명에 공백이 있으니 따옴표로 감싼다(인코딩 utf-8-sig). "
    "조회: `grep -E \"^{한글단어},\" <csv>` 로 영문약어, 도메인 CSV 로 타입·길이. "
    "MCP program_word_search/gov_word_search 는 개별 확인용이다 — 검색 0건을 「미등록」 근거로 쓰지 말 것(조회 상한 때문에 누락된다). "
    "상세 규칙 정본: `docs/rules/klid-db-policy.md`. 메모리 [[columns-must-use-standard-glossary]]·[[standard-term-csv-is-the-only-source]] 참조. "
    "이 검증은 수동 규칙·사후 QA 가 놓쳐 재작업을 유발한 지점이므로 생략 금지."
)

out = {
    "hookSpecificOutput": {
        "hookEventName": "PostToolUse",
        "additionalContext": reminder
    }
}
print(json.dumps(out, ensure_ascii=False))
sys.exit(0)
PYEOF
