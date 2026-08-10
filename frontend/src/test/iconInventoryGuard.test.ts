// 아이콘 재유입 가드 — 아이콘 종수와 "같은 의미 = 같은 아이콘" 규칙이 조용히 무너지는 것을 막는다.
//
// ★ 왜 필요한가 — 이 드리프트는 리뷰로 못 막는다
//   아이콘은 파일 하나를 만들 때마다 한 개씩 는다. 새 화면을 만드는 사람은 앱 전체가 '반려'를
//   어떤 글리프로 쓰는지 모른 채 그 순간 그럴듯해 보이는 것을 고른다. 그렇게 '반려'가
//   XCircle·ArrowDown 으로, '대기'가 Clock·Hourglass 로, '완료'가 CheckCircle·CheckCircle2 로
//   갈렸다(실측). 한 파일만 보면 전부 자연스러워 코드 리뷰에서 걸리지 않는다.
//
// ★ 이 가드가 하는 일은 둘뿐이다
//   ① 종수 상한 — 지금보다 늘면 실패한다. 늘려야 할 정당한 이유가 있으면 상한을 올리되,
//      "이미 같은 의미의 아이콘이 있지 않은가"를 한 번 확인하게 만드는 것이 목적이다.
//   ② 폐기 목록 — 통일하면서 걷어낸 아이콘이 다시 들어오면 그 파일을 지목하며 실패한다.
//
// ⚠ 이 가드가 못 보는 것
//   - `import` 문만 본다. 동적 참조(`Icons[name]`)·재export·SVG 직접 삽입은 대상이 아니다.
//   - 종수만 세므로 "같은 의미에 다른 아이콘"이 **둘 다 이미 목록에 있는** 경우는 못 잡는다
//     (예: 경고 축 AlertCircle=입력 오류 / AlertTriangle=경고 는 의도된 분리라 통과한다).
//   - 아이콘이 **장식인지 정보인지**는 정적으로 판정할 수 없다. 그건 리뷰의 몫이다.
//
// ⚠ mutation 확인 절차: 아무 파일에 폐기 목록의 아이콘(예: `Hourglass`)을 import 하면 그 파일을
//   지목하며 FAIL 해야 하고, 새 아이콘을 하나 추가하면 종수 상한 테스트가 FAIL 해야 한다.
//   (실제로 확인함)

import fs from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

const SRC_DIR = path.resolve(__dirname, '..');

/** src 하위 비-테스트 .ts/.tsx 전량 — 아이콘은 상수 파일(.ts)에도 매핑으로 산다. */
function sourceFiles(): string[] {
  return fs
    .readdirSync(SRC_DIR, { recursive: true, encoding: 'utf-8' })
    .filter((f) => /\.tsx?$/.test(f))
    .filter((f) => !/(^|[\\/])__tests__[\\/]/.test(f) && !/\.test\.tsx?$/.test(f))
    .filter((f) => !f.startsWith('test' + path.sep) && !f.startsWith('test/'))
    .map((f) => path.join(SRC_DIR, f));
}

function relative(file: string): string {
  return path.relative(SRC_DIR, file).split(path.sep).join('/');
}

/** 파일의 lucide-react import 에서 아이콘 이름을 뽑는다(`X as Y` 는 원래 이름 기준). */
function lucideImports(src: string): string[] {
  const m = src.match(/import\s*\{([^}]*)\}\s*from\s*'lucide-react'/s);
  if (!m) return [];
  return m[1]
    .split(',')
    .map((s) => s.trim().split(/\s+as\s+/)[0].trim())
    .filter(Boolean);
}

function inventory(): Map<string, string[]> {
  const byIcon = new Map<string, string[]>();
  for (const file of sourceFiles()) {
    for (const name of lucideImports(fs.readFileSync(file, 'utf-8'))) {
      const files = byIcon.get(name) ?? [];
      files.push(relative(file));
      byIcon.set(name, files);
    }
  }
  return byIcon;
}

/**
 * 아이콘 종수 상한.
 *
 * 값을 올리기 전에 반드시 물을 것: **이미 같은 의미의 아이콘이 목록에 있지 않은가?**
 * (있으면 그것을 쓰고 상한은 그대로 둔다.)
 */
const MAX_DISTINCT_ICONS = 79;

/**
 * 통일하면서 걷어낸 아이콘 — 되살리면 같은 의미가 두 글리프로 다시 갈린다.
 * 값은 "무엇으로 대체했는가"이며, 되살릴 정당한 이유가 생기면 그 사유와 함께 목록에서 뺀다.
 */
const RETIRED_ICONS: Record<string, string> = {
  // 정렬 방향 — 표마다 다른 글리프가 뜨던 것을 공용 DataTable 기준으로 통일.
  ArrowUp: 'ChevronUp (정렬 오름차순)',
  ArrowDown: 'ChevronDown (정렬 내림차순) / XCircle (반려 KPI)',
  // 상태 축 — 같은 의미에 두 글리프가 공존하던 것.
  CheckCircle: 'CheckCircle2 (완료·승인)',
  Hourglass: 'Clock (대기)',
  // 행위 축.
  FileDown: 'Download (내려받기)',
  UploadCloud: 'Upload (올리기)',
  ClipboardList: 'ListTodo (작업 목록)',
  Tags: 'Tag (라벨·태그)',
  ShieldAlert: 'AlertTriangle (경고 박스)',
  // 장식이라 제거한 것 — 제목·라벨이 이미 말하고 있어 아이콘이 정보를 더하지 않았다.
  Save: '제거 (버튼 라벨 "저장"이 동작을 완전히 서술)',
  Settings: '제거 (제목 "시스템 설정" 옆 장식)',
  ListTree: '제거 (버튼 라벨 "속성" 옆 장식)',
  Wand2: '제거 (버튼 라벨 "처리 요청" 옆 장식)',
  ShieldCheck: '제거 (버튼 라벨 "재비식별" 옆 장식)',
  Activity: '제거 (제목 "처리현황" 옆 장식)',
  Image: '제거 (제목 "이미지 학습데이터" 옆 장식)',
  Film: '제거 (제목 "영상 학습데이터" 옆 장식) / Clock (처리 대기 KPI)',
  // 2026-08-10 배지·KPI 장식 아이콘 폐지로 사용처가 0 이 된 것들.
  // ⚠ 이 라운드에서 함께 걷어낸 다른 글리프(CheckCircle2·XCircle·Clock·Loader2·Search·Tag·
  //   AlertTriangle·ListTodo·Play·UserPlus·Check·X)는 **다른 화면에서 여전히 쓰이므로** 여기에
  //   넣지 않는다 — 폐기된 것은 "그 배지·카드에서의 쓰임"이지 글리프 자체가 아니다.
  ClipboardCheck: '제거 (상태 배지 "검수중"·검수 KPI 옆 장식 — 라벨 텍스트가 이미 서술)',
  Flame: '제거 (작업 KPI "검수요청" 옆 장식 — 라벨 텍스트가 이미 서술)',
};

describe('아이콘 — 종수·통일 재유입 가드', () => {
  it('아이콘_종수가_상한을_넘지_않는다', () => {
    const icons = [...inventory().keys()].sort();
    expect(
      icons.length,
      `아이콘 종수 ${icons.length}종 > 상한 ${MAX_DISTINCT_ICONS}종.\n` +
        `같은 의미의 아이콘이 이미 있는지 먼저 확인할 것. 현재 목록:\n${icons.join(', ')}`,
    ).toBeLessThanOrEqual(MAX_DISTINCT_ICONS);
  });

  it('통일하면서_걷어낸_아이콘이_다시_들어오지_않는다', () => {
    const byIcon = inventory();
    const offenders = Object.entries(RETIRED_ICONS)
      .filter(([icon]) => byIcon.has(icon))
      .map(([icon, replacement]) => `${icon} → ${replacement} (${byIcon.get(icon)!.join(', ')})`);

    expect(offenders).toEqual([]);
  });

  it('작업목록_표의_정렬_표식이_공용_DataTable_과_같은_아이콘이다', () => {
    // 같은 '정렬 방향'을 표마다 다른 글리프로 그리면 사용자가 매번 다시 읽어야 한다.
    const board = fs.readFileSync(
      path.join(SRC_DIR, 'features/task/components/TaskBoardTable.tsx'),
      'utf-8',
    );
    expect(board).toContain("direction === 'asc' ? ChevronUp");
    expect(board).toContain("direction === 'desc' ? ChevronDown");
  });
});
