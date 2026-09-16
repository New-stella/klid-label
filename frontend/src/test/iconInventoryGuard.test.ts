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

/**
 * **우리가 쓴 소스만** — 부모 포털에서 통째로 들여온 부품 사본은 뺀다.
 *
 * ★ 왜 빼나 (2026-09-16)
 *   이 가드의 취지는 *"앱 전체가 '반려'를 어떤 글리프로 쓰는지 모른 채 그 순간 그럴듯해 보이는
 *   것을 고르는"* 드리프트를 막는 것이다. 그 전제는 **우리가 그 파일에서 글리프를 고른다**는
 *   것인데, 벤더 사본은 우리가 고르지 않았고 **고칠 수도 없다**(고치면 부모 포털과 갈려
 *   다음 동기화에서 되돌아온다 — 킷 배럴이 "원본을 고치지 않는다"로 못박은 규칙).
 *
 * ⚠ **실제로 겹친다 — 알고 받아들인 대가다.** 킷은 lucide 의 **새 이름**을 쓰고 우리는 옛
 *   이름을 쓴다: `CircleCheck`↔`CheckCircle` · `CircleX`↔`XCircle` ·
 *   `TriangleAlert`↔`AlertTriangle` · `LoaderCircle`↔`Loader2`. **같은 뜻에 두 글리프**이지만
 *   포털 채널 화면에서만 킷 쪽이 쓰이고 관제 화면은 종전 어휘 그대로라, 한 화면 안에서
 *   갈리지는 않는다. 통일하려면 벤더를 고치는 것이 아니라 **우리 쪽을 새 이름으로 옮기는**
 *   별도 라운드여야 한다.
 * ⚠ 이 예외는 **경로로만** 성립한다 — 우리가 쓴 화면이 이 폴더에 들어가는 일은 없다.
 */
const VENDORED_DIRS = ['components/portal/kit/', 'components/portal/authoring/'];

function sourceFiles(): string[] {
  return fs
    .readdirSync(SRC_DIR, { recursive: true, encoding: 'utf-8' })
    .filter((f) => /\.tsx?$/.test(f))
    .filter((f) => !/(^|[\\/])__tests__[\\/]/.test(f) && !/\.test\.tsx?$/.test(f))
    .filter((f) => !f.startsWith('test' + path.sep) && !f.startsWith('test/'))
    .filter((f) => {
      const rel = f.split(path.sep).join('/');
      return !VENDORED_DIRS.some((d) => rel.startsWith(d));
    })
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
    .map((s) => s.trim())
    // ★ `type LucideIcon` 같은 **타입 전용 이름은 글리프가 아니다.** 세면 종수가 실제보다
    //   부풀고, 더 나쁘게는 「아이콘을 하나 더 썼다」는 거짓 신호가 된다(2026-09-16 실측 —
    //   부품이 `import { type LucideIcon }` 을 쓰면서 종수가 1 늘어 상한에 걸렸다).
    .filter((s) => !/^type\s/.test(s))
    .map((s) => s.split(/\s+as\s+/)[0].trim())
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
 *
 * 79 → 80 (2026-08-26, `Shield`) — 비식별 이력 패널의 빈 상태 그림.
 *   · 시안이 방패 글리프를 규정한다(SCREEN-009 `④ 이력이 없는 경우` 의 `state-icon` 패스).
 *   · 대체 검토 — 목록의 79종에 **보호·비식별 축 글리프가 없다**. 공용 `EmptyState` 의 기본
 *     글리프 `Inbox` 는 "비어 있다" 만 말하고 이 영역이 개인정보 보호 축이라는 사실을 말하지
 *     않아 시안이 고른 의미를 잃는다. `Lock`(잠금)·`History`(이력)도 축이 다르다.
 *   · 방패 계열 중 `ShieldCheck`·`ShieldAlert` 는 폐기 목록에 있으나, 그 둘이 폐기된 이유는
 *     각각 "버튼 라벨 옆 장식"·"경고 축 중복"이라 **빈 상태 그림에는 해당하지 않는다**.
 *
 * 80 → 81 (2026-09-16, `ArrowUpRight`) — 「다른 면으로 건너뛰기」 표식.
 *   · 부모 포털 시안이 이 자리(건수 줄 오른쪽 바로가기)에 대각선 화살표를 규정한다.
 *   · 대체 검토 — 목록에 **「다른 화면으로 건너뛴다」 축 글리프가 없다.** `ChevronRight` 는
 *     펼치기·쪽 넘김·같은 화면 안 이동이고 `ArrowRight` 는 탭·단계 진행이라, 둘 다 「여기서
 *     나가 저기로 간다」를 말하지 않는다. 대각선은 그 뜻을 갖는 관례 글리프다.
 *   · ⚠ 같은 라운드에서 **한 건은 상한을 올리지 않고 되돌렸다** — `FileJson` 을 새로 들이려다
 *     기존 `FileBraces`(JSON 파일)가 같은 뜻으로 이미 있어 그것을 썼다. 이 가드가 노린 바로
 *     그 확인이 실제로 작동한 자리라 함께 적어 둔다.
 */
const MAX_DISTINCT_ICONS = 81;

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
