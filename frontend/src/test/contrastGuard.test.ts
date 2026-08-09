import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';

import resolveConfig from 'tailwindcss/resolveConfig';
import { describe, expect, it } from 'vitest';

// tailwind.config.js 는 타입 선언이 없는 plain JS(ESM default export)
// @ts-expect-error -- 설정 파일은 .js 라 타입 선언이 없음
import tailwindConfig from '../../tailwind.config.js';

import {
  compositeOver,
  contrastRatio,
  WCAG_AA_LARGE_TEXT_OR_ICON,
  WCAG_AA_NORMAL_TEXT,
} from './wcagContrast';

/**
 * 대비 회귀 가드 — 2026-08-08 팔레트 교체(DS-001 정본 값 채택)로 밝아진 의미색
 * (info/warning/danger/success)이 연한 배경(bg-{color}/N) 위 텍스트에서 AA(4.5:1)
 * 미달이 된 것을 교정한 지점들을 다시 검증한다.
 *
 * ⚠ 클래스 문자열 존재만 세는 가짜 가드가 아니다 — tailwind.config.js 를 실제로
 * resolveConfig 해 얻은 hex 값으로 WCAG 상대휘도 대비비를 계산한다. 토큰 값이
 * 바뀌거나(예: info-700 이 더 밝은 값으로 바뀌면) 소스의 클래스가 되돌려지면
 * (text-info-700 → text-info) 계산된 대비비가 낮아져 이 테스트가 실패한다.
 */

const fullConfig = resolveConfig(tailwindConfig as never);
const colors = fullConfig.theme.colors as Record<string, unknown>;
const asObj = (v: unknown): Record<string, string> => v as Record<string, string>;
const infoScale = asObj(colors.info);
const warningScale = asObj(colors.warning);
const dangerScale = asObj(colors.danger);
const successScale = asObj(colors.success);

type SemanticColor = 'info' | 'warning' | 'danger' | 'success';
const SCALES: Record<SemanticColor, Record<string, string>> = {
  info: infoScale,
  warning: warningScale,
  danger: dangerScale,
  success: successScale,
};

const repoRoot = path.resolve(__dirname, '../..');
const readSrc = (relPath: string): string => readFileSync(path.join(repoRoot, relPath), 'utf-8');

/** `text-{color}` 또는 `text-{color}-NNN` 토큰명을 해당 스케일의 hex 로 해석한다. */
function resolveColorToken(color: SemanticColor, token: string): string {
  const scale = SCALES[color];
  if (token === `text-${color}`) return scale.DEFAULT;
  const step = token.replace(`text-${color}-`, '');
  const hex = scale[step];
  if (!hex) throw new Error(`정의되지 않은 ${color} 스케일 단계: ${step}`);
  return hex;
}

/** `text-info` 또는 `text-info-NNN` 토큰명을 tailwind info 스케일 hex 로 해석한다. */
function resolveInfoToken(token: string): string {
  return resolveColorToken('info', token);
}

/**
 * 파일 내용에서 anchor 문자열 주변(앞뒤 400자)에 있는 text-{color}(-NNN)? 클래스
 * 토큰을 찾아 반환한다. JSX 는 className 이 텍스트보다 앞에 오거나(속성) 뒤에
 * 오는(cn() 삼항 표현식) 경우가 섞여 있어 양방향으로 탐색한다.
 */
function extractColorTextTokenNear(
  fileContent: string,
  anchor: string,
  color: SemanticColor,
): string {
  const anchorIdx = fileContent.indexOf(anchor);
  if (anchorIdx === -1) {
    throw new Error(`anchor 를 찾을 수 없음: "${anchor}"`);
  }
  const windowStart = Math.max(0, anchorIdx - 400);
  const windowEnd = Math.min(fileContent.length, anchorIdx + anchor.length + 400);
  const window = fileContent.slice(windowStart, windowEnd);
  const anchorOffsetInWindow = anchorIdx - windowStart;

  // window 안에 text-{color}(-NNN)? 토큰이 여러 개 있을 수 있다(예: 근처의 아이콘
  // className). anchor 와 "가장 가까운" 매치를 고른다 — 첫 매치를 그냥 쓰면
  // 엉뚱한(더 앞선) 클래스를 집어 오탐/누락이 난다.
  const matches = [...window.matchAll(new RegExp(`text-${color}(-\\d+)?\\b`, 'g'))];
  if (matches.length === 0) {
    throw new Error(`anchor "${anchor}" 주변에서 text-${color} 토큰을 찾을 수 없음`);
  }
  let closest = matches[0];
  let closestDist = Math.abs((closest.index ?? 0) - anchorOffsetInWindow);
  for (const m of matches) {
    const dist = Math.abs((m.index ?? 0) - anchorOffsetInWindow);
    if (dist < closestDist) {
      closest = m;
      closestDist = dist;
    }
  }
  return closest[0];
}

/** 기존 info 전용 이름 — 하위 호환(단순 위임). */
function extractInfoTextTokenNear(fileContent: string, anchor: string): string {
  return extractColorTextTokenNear(fileContent, anchor, 'info');
}

/** bg-info/10 을 흰 배경 위에 합성한 실제 표시 색 (info DEFAULT, 10% 불투명도). */
const bgInfo10OnWhite = compositeOver(infoScale.DEFAULT, 0.1, '#FFFFFF');
/** bg-warning/10 을 흰 배경 위에 합성한 실제 표시 색. */
const bgWarning10OnWhite = compositeOver(warningScale.DEFAULT, 0.1, '#FFFFFF');
/** bg-danger/10 을 흰 배경 위에 합성한 실제 표시 색. */
const bgDanger10OnWhite = compositeOver(dangerScale.DEFAULT, 0.1, '#FFFFFF');
/** bg-success/10 을 흰 배경 위에 합성한 실제 표시 색. */
const bgSuccess10OnWhite = compositeOver(successScale.DEFAULT, 0.1, '#FFFFFF');

interface Case {
  label: string;
  file: string;
  anchor: string;
}

// 이번에 text-info → text-info-700 로 교정한 14개 지점(10개 파일). 배경은 전부
// bg-info/10(연한 정보색 배지/배너) — 파일마다 실제 소스를 읽어 현재 적용된
// 클래스 토큰을 추출하고, 그 토큰의 실제 색으로 대비를 계산한다.
const CASES: Case[] = [
  {
    label: 'JobCard 해상도 파생 뱃지',
    file: 'src/features/augment/components/JobCard.tsx',
    anchor: 'job-card-resolution-',
  },
  {
    label: 'HistoryPanel 현재 버전 뱃지',
    file: 'src/features/version/components/HistoryPanel.tsx',
    anchor: '{isActive && (',
  },
  {
    label: 'IssueCard 이슈 유형 뱃지(반려 아님)',
    file: 'src/features/review/components/IssueCard.tsx',
    anchor: 'issueType === ISSUE_TYPE.REJECTION',
  },
  {
    label: 'IssueThreadPanel 이슈 유형 뱃지(반려 아님)',
    file: 'src/features/review/components/IssueThreadPanel.tsx',
    anchor: 'thread.issueTypeCd === ISSUE_TYPE.REJECTION',
  },
  {
    label: 'HealthStatusList 실시간 모니터링 뱃지',
    file: 'src/features/sysconfig/components/HealthStatusList.tsx',
    anchor: '실시간 모니터링',
  },
  {
    label: 'AssignModal 대상 영상 뱃지',
    file: 'src/features/task/components/AssignModal.tsx',
    anchor: 'previewIds.map((vid) => (',
  },
  {
    label: 'TaskBoardTable 증강 뱃지',
    file: 'src/features/task/components/TaskBoardTable.tsx',
    anchor: 'task-aug-badge-',
  },
  {
    label: 'AugmentResultPage 처리중 제목',
    file: 'src/pages/AugmentResultPage.tsx',
    anchor: '증강 처리 중입니다',
  },
  {
    label: 'AugmentResultPage 처리중 안내문',
    file: 'src/pages/AugmentResultPage.tsx',
    anchor: '처리 상태가 확인되면',
  },
  {
    label: 'AugmentRequestPage SFR-07 안내문',
    file: 'src/pages/AugmentRequestPage.tsx',
    anchor: '처리 요청은 검수 완료',
  },
  {
    label: 'AugmentRequestPage 선택된 처리 종류 뱃지',
    file: 'src/pages/AugmentRequestPage.tsx',
    anchor: '{selectedKind && (',
  },
  {
    label: 'AugmentRequestPage 해상도 파생 검수대기 뱃지',
    file: 'src/pages/AugmentRequestPage.tsx',
    anchor: '검수 대기 (영상 #',
  },
  {
    label: 'AugmentRequestPage 선택된 영상 뱃지',
    file: 'src/pages/AugmentRequestPage.tsx',
    anchor: '선택됨',
  },
  {
    label: 'TaskListPage 일괄 배정 선택 개수 뱃지',
    file: 'src/pages/TaskListPage.tsx',
    anchor: '개 선택됨',
  },
];

describe('대비 회귀 가드 — bg-info/10 위 텍스트는 AA(4.5:1) 이상', () => {
  it.each(CASES)('$label — 소스의 실제 클래스로 계산해도 AA를 만족한다', ({ file, anchor }) => {
    const content = readSrc(file);
    const token = extractInfoTextTokenNear(content, anchor);
    const hex = resolveInfoToken(token);
    const ratio = contrastRatio(hex, bgInfo10OnWhite);

    expect(ratio, `${file} 의 "${anchor}" 인근 클래스(${token})가 AA 미달`).toBeGreaterThanOrEqual(
      WCAG_AA_NORMAL_TEXT,
    );
  });

  it('info DEFAULT(교체 전 값)는 bg-info/10 위에서 AA에 미달한다 — 회귀 원인 문서화', () => {
    // 이 테스트 자체가 위 케이스들을 text-info(DEFAULT)로 되돌리면 실패해야 하는
    // 이유를 보여준다: DEFAULT 는 애초에 AA 를 만족하지 못한다.
    const ratio = contrastRatio(infoScale.DEFAULT, bgInfo10OnWhite);
    expect(ratio).toBeLessThan(WCAG_AA_NORMAL_TEXT);
  });

  it('info-700 은 bg-info/10 위에서 AA를 만족한다 — 토큰 자체의 적합성', () => {
    const ratio = contrastRatio(infoScale['700'], bgInfo10OnWhite);
    expect(ratio).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
  });
});

// 2026-08-08(2차) — 나머지 의미색(warning/danger/success)도 DS-001 정본 11단 스케일로
// 교체하며 같은 종류의 AA 미달이 발생했다(text-{color} DEFAULT + bg-{color}/N 동색 배경).
// text-{color}-700 으로 교정한 지점들을 info 와 동일한 방식으로 재검증한다.
const WARNING_CASES: Case[] = [
  {
    label: 'StatusBadge REVIEW_PENDING(검수요청)',
    file: 'src/components/common/StatusBadge.tsx',
    anchor: "REVIEW_PENDING: { label: '검수요청'",
  },
  {
    label: 'StatusBadge NEEDS_RECHECK(재검토 필요) — 회귀 원인 배지',
    file: 'src/components/common/StatusBadge.tsx',
    anchor: "label: '재검토 필요'",
  },
  {
    label: 'StageBadge 기본 톤(대기/그 외)',
    file: 'src/components/common/StageBadge.tsx',
    anchor: "return 'bg-warning/10",
  },
  {
    label: 'DevLoginPage DEV 빌드 전용 뱃지',
    file: 'src/features/auth/DevLoginPage.tsx',
    anchor: 'text-label font-medium text-warning-700',
  },
  {
    label: 'AugmentPromptFieldset 개인정보 안내',
    file: 'src/features/augment/components/AugmentPromptFieldset.tsx',
    anchor: '개인식별정보',
  },
  {
    label: 'AugmentProgressPanel 부분 취소 안내',
    file: 'src/features/augment/components/AugmentProgressPanel.tsx',
    anchor: "border-warning/40 bg-warning/10",
  },
  {
    label: 'Sam2TrackTool 추적 중 버튼',
    file: 'src/features/label/canvas/tools/Sam2TrackTool.tsx',
    anchor: "isPending ? 'bg-warning/10",
  },
  {
    label: 'DangerActions 운영도구 이관 안내',
    file: 'src/features/sysconfig/components/DangerActions.tsx',
    anchor: '위험 액션은 별도 운영 도구로',
  },
  {
    label: 'HealthStatusList 서비스 중단 배지',
    file: 'src/features/sysconfig/components/HealthStatusList.tsx',
    anchor: "OUT_OF_SERVICE') return 'bg-warning",
  },
  {
    label: 'AugmentRequestPage 처리종류 미선택 경고',
    file: 'src/pages/AugmentRequestPage.tsx',
    anchor: '처리 종류를 하나 선택하세요',
  },
  {
    label: 'ReviewMemoPanel 미저장 이슈 배지',
    file: 'src/features/review/components/ReviewMemoPanel.tsx',
    anchor: '(미저장)',
  },
];

const DANGER_CASES: Case[] = [
  {
    label: 'StatusBadge BATCH_FAILED',
    file: 'src/components/common/StatusBadge.tsx',
    anchor: "label: '배치 실패'",
  },
  {
    label: 'StatusBadge REJECTED',
    file: 'src/components/common/StatusBadge.tsx',
    anchor: "REJECTED: { label: '반려'",
  },
  {
    label: 'StageBadge 실패 톤',
    file: 'src/components/common/StageBadge.tsx',
    anchor: "return 'bg-danger/10",
  },
  {
    label: 'DevLoginPage 로그인 에러',
    file: 'src/features/auth/DevLoginPage.tsx',
    anchor: 'mb-4 rounded border border-danger/30',
  },
  {
    label: 'SessionIngressPage 세션 에러',
    file: 'src/features/auth/SessionIngressPage.tsx',
    anchor: 'max-w-sm rounded-lg border border-danger/30',
  },
  {
    label: 'AutolabelResultCard 파이프라인 실패 안내',
    file: 'src/features/dev/components/AutolabelResultCard.tsx',
    anchor: '파이프라인 실행에 실패했습니다',
  },
  {
    label: 'AiToolModal 후보 로드 실패',
    file: 'src/features/label/components/AiToolModal.tsx',
    anchor: '라벨 목록을 불러오지 못했습니다',
  },
  {
    label: 'LabelAttrDefPanel 삭제 버튼(hover 상태 포함)',
    file: 'src/features/label/components/LabelAttrDefPanel.tsx',
    anchor: '속성 삭제`}',
  },
  {
    label: 'LabelAttrFormModal 제출 에러',
    file: 'src/features/label/components/LabelAttrFormModal.tsx',
    anchor: 'rounded-md bg-danger/10 px-3 py-2 text-body-md',
  },
  {
    label: 'IssueCard 반려 유형 뱃지',
    file: 'src/features/review/components/IssueCard.tsx',
    anchor: 'issueType === ISSUE_TYPE.REJECTION',
  },
  {
    label: 'IssueThreadPanel 미해소 문의 카운트 뱃지',
    file: 'src/features/review/components/IssueThreadPanel.tsx',
    anchor: '미해소 문의',
  },
  {
    label: 'IssueThreadPanel 반려 유형 뱃지',
    file: 'src/features/review/components/IssueThreadPanel.tsx',
    anchor: 'thread.issueTypeCd === ISSUE_TYPE.REJECTION',
  },
  {
    label: 'IssueSidebar 로드 실패',
    file: 'src/features/review/components/IssueSidebar.tsx',
    anchor: '이슈를 불러오지 못했습니다',
  },
  {
    label: 'TusUploadPanel 업로드 에러',
    file: 'src/features/upload/components/TusUploadPanel.tsx',
    anchor: 'tus-error',
  },
  {
    label: 'DiffViewer REMOVED(삭제) 행',
    file: 'src/features/version/components/DiffViewer.tsx',
    anchor: "REMOVED: 'bg-danger/10",
  },
  {
    label: 'AugmentResultPage 실패 배너',
    file: 'src/pages/AugmentResultPage.tsx',
    anchor: '증강 처리 실패',
  },
  {
    label: 'AugmentRequestPage 해상도 파생 실패 뱃지',
    file: 'src/pages/AugmentRequestPage.tsx',
    anchor: "d.status === 'CREATED' ? (",
  },
  {
    label: 'PortalUploadPage 업로드 검증 오류 목록',
    file: 'src/pages/portal/PortalUploadPage.tsx',
    anchor: 'flex flex-col gap-1 rounded-md border border-danger/30 bg-danger/10',
  },
  {
    label: 'DevAutolabelTestPage 파이프라인 에러',
    file: 'src/pages/dev/DevAutolabelTestPage.tsx',
    anchor: 'autolabel-error',
  },
  {
    label: 'LabelMasterManagePage 삭제 버튼(hover 상태 포함)',
    file: 'src/pages/manage/LabelMasterManagePage.tsx',
    anchor: '삭제`}',
  },
  {
    label: 'PresetListPage 삭제 버튼(hover 상태 포함)',
    file: 'src/pages/manage/PresetListPage.tsx',
    anchor: 'aria-label="삭제"',
  },
  {
    label: 'LabelMasterFormModal 제출 에러',
    file: 'src/pages/manage/components/LabelMasterFormModal.tsx',
    anchor: 'rounded-md bg-danger/10 px-3 py-2 text-body-md',
  },
  {
    label: 'Field 필수 표시(*) — 공용 컴포넌트, 배경 미확정이라 -700 고정',
    file: 'src/components/common/Field.tsx',
    anchor: '(필수)',
  },
  {
    label: 'DateRangePicker 오류 문구',
    file: 'src/components/common/DateRangePicker.tsx',
    anchor: 'id={errorId} role="alert"',
  },
];

const SUCCESS_CASES: Case[] = [
  {
    label: 'StatusBadge APPROVED(승인)',
    file: 'src/components/common/StatusBadge.tsx',
    anchor: "APPROVED: { label: '승인'",
  },
  {
    label: 'StatusBadge COMPLETED(완료)',
    file: 'src/components/common/StatusBadge.tsx',
    anchor: "COMPLETED: { label: '완료'",
  },
  {
    label: 'StageBadge 완료 톤',
    file: 'src/components/common/StageBadge.tsx',
    anchor: "return 'bg-success/10",
  },
  {
    label: 'DecisionCard 채택됨',
    file: 'src/features/augment/components/DecisionCard.tsx',
    anchor: '채택됨',
  },
  {
    label: 'HistoryPanel 최신 커밋 뱃지',
    file: 'src/features/version/components/HistoryPanel.tsx',
    anchor: 'bg-success/10 px-2 py-0.5 text-label font-medium text-success-700',
  },
  {
    label: 'IssueCard 해소 상태 뱃지',
    file: 'src/features/review/components/IssueCard.tsx',
    anchor: 'issueStatus === ISSUE_STATUS.RESOLVED',
  },
  {
    label: 'IssueThreadPanel 해소 버튼(outline, hover 상태 포함)',
    file: 'src/features/review/components/IssueThreadPanel.tsx',
    anchor: 'resolve(thread.issueSn)',
  },
  {
    label: 'TusUploadPanel 인입 대기 완료 안내',
    file: 'src/features/upload/components/TusUploadPanel.tsx',
    anchor: 'tus-completed',
  },
  {
    label: 'AugmentRequestPage 해상도 파생 생성 완료 안내',
    file: 'src/pages/AugmentRequestPage.tsx',
    anchor: '파생영상 {createdDerivatives.length}건',
  },
  {
    label: 'AugmentRequestPage 선택된 영상 뱃지',
    file: 'src/pages/AugmentRequestPage.tsx',
    anchor: '#{selectedVideoId} 선택',
  },
  {
    label: 'AugmentResultPage 완료(결과 없음) 배너',
    file: 'src/pages/AugmentResultPage.tsx',
    anchor: '증강 처리 완료',
  },
  {
    label: 'NoticeListPage 발행 상태 뱃지',
    file: 'src/pages/NoticeListPage.tsx',
    anchor: 'n.pubStatus === NoticePubStatus.PUBLISHED',
  },
  {
    label: 'NoticeDetailPage 발행 상태 뱃지',
    file: 'src/pages/NoticeDetailPage.tsx',
    anchor: 'isPublished\n                    ? ',
  },
  {
    label: 'UserManagePage 활성 사용자 뱃지',
    file: 'src/pages/manage/UserManagePage.tsx',
    anchor: "u.active\n                ? 'inline-flex",
  },
  {
    label: 'HealthStatusList 정상 상태 배지',
    file: 'src/features/sysconfig/components/HealthStatusList.tsx',
    anchor: "status === 'UP') return 'bg-success",
  },
];

function describeColorGuard(
  color: SemanticColor,
  cases: Case[],
  scale: Record<string, string>,
  bgTintOnWhite: string,
): void {
  describe(`대비 회귀 가드 — bg-${color}/N 위 텍스트는 AA(4.5:1) 이상`, () => {
    it.each(cases)('$label — 소스의 실제 클래스로 계산해도 AA를 만족한다', ({ file, anchor }) => {
      const content = readSrc(file);
      const token = extractColorTextTokenNear(content, anchor, color);
      const hex = resolveColorToken(color, token);
      const ratio = contrastRatio(hex, bgTintOnWhite);

      expect(
        ratio,
        `${file} 의 "${anchor}" 인근 클래스(${token})가 AA 미달`,
      ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    });

    it(`${color} DEFAULT(교체 전 값)는 bg-${color}/10 위에서 AA에 미달한다 — 회귀 원인 문서화`, () => {
      const ratio = contrastRatio(scale.DEFAULT, bgTintOnWhite);
      expect(ratio).toBeLessThan(WCAG_AA_NORMAL_TEXT);
    });

    it(`${color}-700 은 bg-${color}/10 위에서 AA를 만족한다 — 토큰 자체의 적합성`, () => {
      const ratio = contrastRatio(scale['700'], bgTintOnWhite);
      expect(ratio).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    });
  });
}

describeColorGuard('warning', WARNING_CASES, warningScale, bgWarning10OnWhite);
describeColorGuard('danger', DANGER_CASES, dangerScale, bgDanger10OnWhite);
describeColorGuard('success', SUCCESS_CASES, successScale, bgSuccess10OnWhite);

// ─────────────────────────────────────────────────────────────────────────────
// 2026-08-09 — 중립색을 DS-001 tokens.colors.neutral(KRDS 11단)로 교체하며
// stock Tailwind gray 대비 **일부 조합이 어두워지지 않고 밝아졌다**. 아래는 그
// 계산 결과를 값으로 못 박은 것이다(산문 보고가 아니라 기계 대조).
//
// ⚠ 값은 KRDS 정본이라 우리가 조정할 대상이 아니다. AA 미달 조합은 **색을 바꿔서가
//   아니라 사용 조합을 바꿔서**(한 단계 진한 step 사용) 회피한다 — 의미색에서 이미
//   text-{color}-700 로 교정한 것과 같은 방식이다.
// ─────────────────────────────────────────────────────────────────────────────
describe('중립색(gray/neutral) 대비 — KRDS 정본 교체 결과 고정', () => {
  const gray = asObj(colors.gray);
  const WHITE = '#FFFFFF';

  it('본문_회색_gray_700_은_흰_배경과_연회색_배경_모두에서_AA를_만족한다', () => {
    expect(contrastRatio(gray['700'], WHITE)).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(gray['700'], gray['50'])).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(gray['700'], gray['100'])).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
  });

  it('gray_600_은_흰_배경_연회색_배경_표헤더_배경_모두에서_AA를_만족한다', () => {
    expect(contrastRatio(gray['600'], WHITE)).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(gray['600'], gray['50'])).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(gray['600'], gray['100'])).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    // secondary-50 = 표 헤더 배경 예정값(Phase 4 소관)
    expect(contrastRatio(gray['600'], asObj(colors.secondary)['50'])).toBeGreaterThanOrEqual(
      WCAG_AA_NORMAL_TEXT,
    );
    // primary-50 = 선택된 KPI 카드·선택된 행 표면(KpiCard selected, 목록 선택 행)
    expect(contrastRatio(gray['600'], asObj(colors.primary)['50'])).toBeGreaterThanOrEqual(
      WCAG_AA_NORMAL_TEXT,
    );
  });

  it('★gray_500_은_흰_배경에서만_턱걸이_통과하고_연회색_배경_위에서는_AA_미달이다', () => {
    // 알려진 한계 — 보조 텍스트로 가장 많이 쓰이는 단계(text-gray-500)라 영향 범위가 넓다.
    // 흰 배경 4.51 은 기준선 4.5 바로 위(경계)이고, gray-50/100/secondary-50/primary-50
    // 위에서는 미달. 회피는 그 조합을 gray-600 이상으로 올려서 한다(색값 조정 금지).
    expect(contrastRatio(gray['500'], WHITE)).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(gray['500'], WHITE)).toBeLessThan(4.7); // 경계임을 명시
    expect(contrastRatio(gray['500'], gray['50'])).toBeLessThan(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(gray['500'], gray['100'])).toBeLessThan(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(gray['500'], asObj(colors.secondary)['50'])).toBeLessThan(
      WCAG_AA_NORMAL_TEXT,
    );
    expect(contrastRatio(gray['500'], asObj(colors.primary)['50'])).toBeLessThan(
      WCAG_AA_NORMAL_TEXT,
    );
  });

  /**
   * DS-001 `do_rules`(v8) 를 **값으로** 고정한다 — 산문 규칙은 다음 사람이 읽지 않고 되돌린다.
   *
   *   "회색 표면(neutral 0~20단) 위의 보조 텍스트는 neutral 50단이 아니라 60단 이상을 쓴다 —
   *    50단은 흰 배경에서만 AA 를 통과하고(4.51) 회색 표면 위에선 미달이다(neutral 0 위 4.13,
   *    보조색 0 위 4.01). 60단은 세 배경 모두 통과한다(5.60~6.30)."
   *
   * 우리 토큰 매핑: neutral 50단 = gray-500 · 60단 = gray-600 · neutral 0~20단 = gray-50/100/200.
   */
  it('★DS_001_v8_보조텍스트_하한_60단_규칙이_값으로_성립한다', () => {
    const secondary50 = asObj(colors.secondary)['50'];
    const primary50 = asObj(colors.primary)['50'];

    // 규칙이 명시한 수치를 그대로 못박는다(소수 둘째 자리).
    expect(Number(contrastRatio(gray['500'], WHITE).toFixed(2))).toBe(4.51);
    expect(Number(contrastRatio(gray['500'], gray['50']).toFixed(2))).toBe(4.13);
    expect(Number(contrastRatio(gray['500'], secondary50).toFixed(2))).toBe(4.01);
    expect(Number(contrastRatio(gray['600'], secondary50).toFixed(2))).toBe(5.6);
    expect(Number(contrastRatio(gray['600'], WHITE).toFixed(2))).toBe(6.3);

    // 60단은 규칙이 말한 "세 배경" + 이번에 추가된 primary-50 까지 전부 통과한다.
    for (const bg of [WHITE, gray['50'], gray['100'], secondary50, primary50]) {
      expect(contrastRatio(gray['600'], bg)).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    }
  });

  it('연한_단계_300_400_은_본문용이_아니다_AA_미달_문서화', () => {
    // 플레이스홀더·비활성 등 비텍스트/보조 용도로만 쓰여야 한다.
    expect(contrastRatio(gray['400'], WHITE)).toBeLessThan(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(gray['300'], WHITE)).toBeLessThan(WCAG_AA_NORMAL_TEXT);
  });

  it('흰_글씨는_gray_500_이상_배경에서_AA를_만족한다', () => {
    for (const step of ['500', '600', '700', '800', '900', '950']) {
      expect(contrastRatio(WHITE, gray[step]), `white on gray-${step}`).toBeGreaterThanOrEqual(
        WCAG_AA_NORMAL_TEXT,
      );
    }
  });

  it('★연회색_배경별_최소_안전_전경_단계가_고정된다', () => {
    // 이번에 올린 조합의 근거값. ⚠ gray-200 배경은 **gray-600 으로도 부족**하다(4.10) —
    // "일괄 500→600" 으로 끝내면 안 되는 지점이 실제로 있었다(필름스트립 스켈레톤).
    expect(contrastRatio(gray['600'], gray['50'])).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(gray['600'], gray['100'])).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(gray['600'], gray['200'])).toBeLessThan(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(gray['700'], gray['200'])).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
  });
});

/**
 * 연한 배경 위 `text-gray-500` 조합 금지 — 소스 전수 스캔.
 *
 * 앵커 방식(파일·문구 지목)은 이번에 고친 지점만 지키고 **새로 생기는 조합은 못 잡는다**.
 * `text-gray-500` 은 보조 텍스트로 300곳 넘게 쓰여 재발이 쉬우므로, 규칙 자체를 기계로 건다.
 *
 * ⚠ 2026-08-09 — 스캔 축에 **`bg-secondary-50`(표 헤더 배경)** 을 추가했다. 그 전까지는
 *   `bg-gray-*` 축만 봤기 때문에, 표 헤더 배경이 회색에서 secondary-50 으로 바뀌면
 *   그 위 글자색 대비가 달라지는데도 이 가드가 **구조적으로 못 보는 상태**였다.
 *   (gray-500 on secondary-50 = 4.01:1 로 AA 미달이다.)
 *
 * ⚠ 2026-08-09(2차) — 축에 **`bg-primary-50`** 을 추가했다. 선택된 KPI 카드
 *   (`KpiCard` 의 `selected` 표면)·선택된 목록 행이 이 색인데 그 위 보조 텍스트가
 *   `text-gray-500`(4.01:1) 이라 AA 미달이었고, **브라우저 실측으로만 발견**됐다 —
 *   이 가드는 축에 없어서 못 봤다.
 *
 * ⚠ 2026-08-09(3차) — 축에 **`bg-bgLight`**(#FAFBFC, 소스 15곳)를 추가했다. 같은 실패가
 *   세 번째로 반복된 것이다: 배경 축을 **열거**로 관리하면 목록에 없는 표면은 구조적으로
 *   못 본다. 이번에도 `AugmentPromptSummary` 의 생성 조건 `dt`(gray-500 on bgLight = 4.35)가
 *   **브라우저 실측으로만** 잡혔다. `bgLight` 는 `gray` 스케일과 별개 별칭 토큰이라
 *   `bg-gray-*` 정규식에 걸리지 않는다.
 *
 * ⚠ 이 스캔이 못 보는 것:
 *  1. **부모 요소 배경 + 자식 요소 글자색**처럼 서로 다른 className 에 나뉜 조합.
 *     그 축은 아래 "부모→자식" 스캔이 별도로 맡는다(같은 파일 안에 한해서).
 *  2. **배경 축은 여전히 열거다** — 위 5종(`gray-50/100/200` · `secondary-50` · `primary-50` ·
 *     `bgLight`) 밖의 연한 표면(새 별칭 토큰, `bg-{color}/10` 틴트 등)은 못 본다.
 *     연한 배경 토큰을 새로 만들면 **반드시 이 정규식에 추가**할 것.
 *  3. **글자 축도 열거다** — `text-gray-NNN` 만 본다. `text-neutral`(별칭, = gray-700)·
 *     `text-neutral-NNN` 은 잡지 않는다. 현재 `bgLight` 위 나머지 14곳이 전부
 *     `text-neutral`(8.38:1)이라 안전하지만, 그건 **이 가드가 확인해 준 것이 아니다**.
 */
describe('연한 배경 위 본문 회색 조합 — 소스 전수 스캔', () => {
  const gray = asObj(colors.gray);
  const secondary = asObj(colors.secondary);
  const primary = asObj(colors.primary);
  const bgLight = asObj(colors.bgLight);
  /**
   * 배경 클래스 → 실제 hex. gray·secondary·primary·bgLight 네 축을 한 스캔에서 함께 판정한다.
   * ⚠ `bgLight` 는 단계 없는 별칭 토큰이라 `DEFAULT` 를 쓴다(step 은 빈 문자열로 들어온다).
   */
  const bgHex = (family: string, step: string): string =>
    family === 'bgLight'
      ? bgLight.DEFAULT
      : family === 'secondary'
        ? secondary[step]
        : family === 'primary'
          ? primary[step]
          : gray[step];
  /**
   * `bg-gray-{50,100,200}` + `bg-secondary-50` + `bg-primary-50` + `bg-bgLight` — variant 접두
   * (hover: / active: / disabled: …)를 1번 그룹, 배경 토큰 전체를 2번 그룹에 담는다.
   */
  const BG_LIGHT =
    /(?:^|[\s"'`([{])((?:[a-z-]+:)*)bg-(gray-(?:50|100|200)|secondary-50|primary-50|bgLight)\b/g;
  /** `text-gray-{N}` — 마찬가지로 variant 접두를 분리 포착. */
  const TEXT_GRAY = /(?:^|[\s"'`([{])((?:[a-z-]+:)*)text-gray-(\d{2,3})\b/g;
  /**
   * 판정 대상 글자 단계 — **본문 텍스트로 의도된 단계만**(500 이상).
   * gray-300/400 은 플레이스홀더·비활성·장식 아이콘 용도라 애초에 AA 대상이 아니고,
   * 이 저장소에서 그 용도로 이미 널리 쓰인다. 여기에 끌어들이면 이 가드가 잡아야 할
   * 본문 회귀가 장식 잡음에 묻힌다.
   */
  const BODY_TEXT_STEPS = new Set(['500', '600', '700', '800', '900', '950']);
  /** WCAG 1.4.3 은 **비활성 UI 요소**를 대비 요건에서 제외한다. placeholder 도 같은 축으로 둔다. */
  const EXEMPT_VARIANT = /(?:^|:)(disabled|placeholder)/;

  // 파일 수집은 이 저장소의 기존 소스 스캔 테스트와 동일한 방식을 쓴다
  // (deadBreakpoints / uiWordingGuard). node:fs 의 globSync 는 Node 22+ 전용이라 피한다.
  const files = readdirSync(path.join(repoRoot, 'src'), { recursive: true, encoding: 'utf-8' })
    .filter((f) => /\.(ts|tsx)$/.test(f))
    .map((f) => path.join('src', f));

  it('★같은_className_안의_본문회색_x_연한배경_조합이_전부_AA를_만족한다', () => {
    expect(files.length, '스캔 대상 파일 0건 — 파일 수집이 깨졌다').toBeGreaterThan(100);

    const violations: string[] = [];
    for (const rel of files) {
      readSrc(rel)
        .split('\n')
        .forEach((line, i) => {
          const bgs = [...line.matchAll(BG_LIGHT)];
          const texts = [...line.matchAll(TEXT_GRAY)];
          if (bgs.length === 0 || texts.length === 0) return;

          for (const bg of bgs) {
            for (const text of texts) {
              const [, textVariant, textStep] = text;
              const [, bgVariant, bgToken] = bg;
              const [bgFamily, bgStep] = bgToken.split('-');
              if (!BODY_TEXT_STEPS.has(textStep)) continue;
              if (EXEMPT_VARIANT.test(textVariant) || EXEMPT_VARIANT.test(bgVariant)) continue;

              const bg1 = bgHex(bgFamily, bgStep);
              const ratio = contrastRatio(gray[textStep], bg1);
              if (ratio >= WCAG_AA_NORMAL_TEXT) continue;

              // hover 로만 깔리는 배경인데 같은 줄에서 hover 글자색이 함께 진해지면
              // 실제로 렌더되는 상태 조합에는 미달이 없다.
              const neutralizedByHover =
                bgVariant !== '' &&
                texts.some(
                  (t) =>
                    t[1] !== '' &&
                    BODY_TEXT_STEPS.has(t[2]) &&
                    contrastRatio(gray[t[2]], bg1) >= WCAG_AA_NORMAL_TEXT,
                );
              if (neutralizedByHover) continue;

              violations.push(
                `${rel}:${i + 1} — ${textVariant}text-gray-${textStep} on ` +
                  `${bgVariant}bg-${bgToken} = ${ratio.toFixed(2)}:1`,
              );
            }
          }
        });
    }

    expect(
      violations,
      `연한 배경 위 본문 회색이 AA(4.5:1) 미달이다. 배경 gray-50/100 과 secondary-50 은 ` +
        `gray-600 이상, 배경 gray-200 은 gray-700 이상으로 올릴 것(색값은 KRDS 정본이라 ` +
        `조정 대상 아님):\n` +
        violations.join('\n'),
    ).toEqual([]);
  });

  it('★스캔이_bgLight_축을_실제로_판정한다_스캔범위_자기검증', () => {
    // 콘텐츠 표면 별칭(#FAFBFC). 이 축을 정규식에서 빼면(구 상태로 되돌리면) 매칭이 0건이 되어 실패한다.
    // ⚠ 배경 토큰과 글자 토큰을 **다른 줄**에 둔다 — 한 줄에 같이 쓰면 위 전수 스캔이
    //   이 파일 자신을 위반으로 집는다.
    const sampleBg = 'className="bg-bgLight"';
    expect([...sampleBg.matchAll(BG_LIGHT)].map((m) => m[2])).toEqual(['bgLight']);
    // 단계 없는 별칭이라 hex 해석이 DEFAULT 로 떨어지는지도 함께 고정한다.
    expect(bgHex('bgLight', '')).toBe('#FAFBFC');
    // 그리고 그 조합은 실제로 AA 미달이라 위 스캔이 위반으로 잡아야 한다(4.35:1).
    expect(Number(contrastRatio(gray['500'], bgLight.DEFAULT).toFixed(2))).toBe(4.35);
    expect(contrastRatio(gray['500'], bgLight.DEFAULT)).toBeLessThan(WCAG_AA_NORMAL_TEXT);
    // 60단은 통과한다 — 회피 방향(색값 조정이 아니라 단계 상향)이 성립함을 못박는다.
    expect(contrastRatio(gray['600'], bgLight.DEFAULT)).toBeGreaterThanOrEqual(
      WCAG_AA_NORMAL_TEXT,
    );
  });

  it('★스캔이_primary_50_축을_실제로_판정한다_스캔범위_자기검증', () => {
    // 선택된 KPI 카드 표면. 이 축을 정규식에서 빼면(구 상태로 되돌리면) 매칭이 0건이 되어 실패한다.
    const sampleBg = 'className="bg-primary-50"';
    expect([...sampleBg.matchAll(BG_LIGHT)].map((m) => m[2])).toEqual(['primary-50']);
    expect(contrastRatio(gray['500'], primary['50'])).toBeLessThan(WCAG_AA_NORMAL_TEXT);
  });

  it('★스캔이_secondary_50_축을_실제로_판정한다_스캔범위_자기검증', () => {
    // 이 가드가 "bg-secondary-50 축을 본다"는 사실 자체를 고정한다. 정규식에서
    // secondary 축을 빼면(구 상태로 되돌리면) 아래 매칭이 0건이 되어 실패한다.
    // ⚠ 배경 토큰과 글자 토큰을 **다른 줄**에 둔다 — 한 줄에 같이 쓰면 위 전수 스캔이
    //   이 파일 자신을 위반으로 집는다(실제로 한 번 잡혔다).
    const sampleBg = 'className="bg-secondary-50"';
    const matches = [...sampleBg.matchAll(BG_LIGHT)];
    expect(matches.map((m) => m[2])).toEqual(['secondary-50']);
    // 그리고 그 조합은 실제로 AA 미달이라 위 스캔이 위반으로 잡아야 한다.
    expect(contrastRatio(gray['500'], secondary['50'])).toBeLessThan(WCAG_AA_NORMAL_TEXT);
  });
});

/**
 * 연한 배경 위 `text-gray-500` 조합 금지 — **부모→자식(요소 경계를 넘는) 스캔**.
 *
 * 왜 별도로 필요한가: 위 전수 스캔은 **같은 줄(같은 className 문자열)** 안의 조합만 본다.
 * 그런데 실제 회귀는 대부분 배경과 글자색이 **다른 요소**에 있다 — 표 헤더는 `<tr>` 이
 * 배경(`bg-gray-50`)을 주고 `<th>` 가 글자색을 주며, 선택된 KPI 카드는 바깥 래퍼가
 * 조건부 배경(`selected && bg-primary-50`)을 주고 안쪽 `<p>` 가 글자색을 준다.
 * 2026-08-09 브라우저 실측에서 잡힌 회귀가 **전부 이 축**이었고, 같은 줄 스캔은 한 건도 잡지 못했다.
 *
 * ⚠ 위 설명에서 배경 토큰과 글자 토큰을 일부러 **다른 문장에 나눠** 적었다 — 한 줄에 같이 쓰면
 *   바로 위 "같은 className 전수 스캔"이 이 파일 자신을 위반으로 집는다(실제로 한 번 잡혔다).
 *
 * ── 어디까지 잡나 (능력) ────────────────────────────────────────────────
 *  · 같은 **파일** 안에서 JSX 여는 태그의 들여쓰기로 조상 체인을 만들고, 조상의
 *    **여러 줄 className 속성 구간까지** 읽어 배경을 찾는다(`cn(...)` 멀티라인 포함).
 *    ⚠ 조상의 속성 줄은 조상 태그보다 **더 깊이** 들여쓰기되므로, 단순 들여쓰기 역추적만
 *      하면 조상 자신의 배경을 놓친다 — 실제로 `KpiCard` 가 이 방식으로 새어 나갔다.
 *  · 가장 가까운 배경이 `bg-white` 면 안전으로 판정하고 더 올라가지 않는다.
 *  · `Card` / `Modal` / `Drawer` / `Popover` 는 자체적으로 흰 표면을 렌더하므로 흰 배경으로 본다.
 *  · `hover:` 로만 깔리는 배경이라도 **그 요소가 hover 에서 글자를 진하게 바꾸지 않으면** 위반이다
 *    (hover 는 실제로 렌더되는 상태다). 둘이 함께 바뀌면 조합이 성립하지 않으므로 통과시킨다.
 *
 * ── 어디부터 못 보나 (한계 — 이 목록이 이 가드의 정직성이다) ─────────────
 *  1. **파일을 넘는 조합**. 부모가 `<Panel className="bg-gray-50">{children}</Panel>` 로 배경을
 *     주고 자식 컴포넌트가 다른 파일에서 `text-gray-500` 을 쓰면 못 본다.
 *  2. **페이지 배경**. `AppLayout` 이 `bg-gray-50` 이라 흰 카드 밖의 텍스트는 전부 회색 표면
 *     위인데, 조상 체인이 파일 안에서 끝나므로 판정하지 못한다. (이 축은 실측으로만 잡힌다.)
 *  3. **분기 조건의 의미**. `selected ? 'text-primary-600' : 'text-gray-500'` 처럼 밝은 배경이
 *     켜지는 분기와 gray-500 이 쓰이는 분기가 **서로 배타적**인 경우를 구분하지 못한다
 *     → 아래 `EXEMPTIONS` 로 사유를 적어 예외 처리한다(무단 추가 금지).
 *  4. `style={{ backgroundColor }}` 등 **런타임 배경**.
 *  5. `.tsx` 만 본다(JSX 가 없는 `.ts` 는 대상 아님).
 *  6. **배경·글자 토큰이 둘 다 열거다.** 배경은 `gray-50/100/200` · `secondary-50` ·
 *     `primary-50` · `bgLight` 6종만, 글자는 `text-gray-NNN` 만 본다(`text-neutral` 별칭 제외).
 *     ⚠ 이 열거가 이 가드의 **반복 실패 지점**이다 — `secondary-50` · `primary-50` · `bgLight`
 *     세 축이 **차례로 목록 밖이라 못 보다가 브라우저 실측으로만 잡혔다**(2026-08-09, 3회).
 *     연한 배경 토큰을 새로 만들면 이 정규식에 **반드시** 추가할 것.
 *
 * → 1·2·4 는 구조적으로 정적 스캔의 밖이다. 그 축은 **브라우저 시각 회귀 검사의 몫**이며,
 *   이 가드가 초록이라고 "대비 회귀 없음"이 증명되지 않는다.
 */
describe('연한 배경 위 본문 회색 조합 — 부모→자식 스캔(요소 경계 넘음)', () => {
  const gray = asObj(colors.gray);
  const secondary = asObj(colors.secondary);
  const primary = asObj(colors.primary);
  const bgLight = asObj(colors.bgLight);

  const LIGHT_BG =
    /(?:^|[\s"'`([{])((?:[a-z-]+:)*)bg-(gray-(?:50|100|200)|secondary-50|primary-50|bgLight)\b/;
  const WHITE_BG = /(?:^|[\s"'`([{])((?:[a-z-]+:)*)bg-white\b/;
  /**
   * ★`text-gray-500` 만 보지 않는다 — 단계를 포착해 **실제 대비를 계산**한다.
   *
   * 500 만 보면 "일괄 500→600" 으로 고친 뒤 `bg-gray-200` 위 조합(gray-600 = 4.10 으로 여전히
   * 미달)이 그대로 남는데 가드는 초록이 된다. 실제로 이 가드를 500 전용으로 만들었을 때
   * `LabelingPage` 의 캔버스(gray-200) 회귀를 놓쳤다 — 그래서 단계 무관 계산으로 바꿨다.
   */
  const TEXT_GRAY = /(?:^|[\s"'`([{])((?:[a-z-]+:)*)text-gray-(\d{2,3})\b/g;
  /**
   * 판정 대상 글자 단계 — **본문 텍스트로 의도된 단계만**(500 이상).
   * 300/400 은 플레이스홀더·비활성·장식 용도라 AA(1.4.3) 대상이 아니다.
   */
  const BODY_TEXT_STEPS = new Set(['500', '600', '700', '800', '900', '950']);
  const OPEN_TAG = /^<([A-Za-z][\w.]*)/;
  /** 자체적으로 흰 표면을 렌더하는 공용 컴포넌트 — 그 자식은 흰 배경 위다. */
  const WHITE_SURFACE_COMPONENTS = new Set(['Card', 'Modal', 'Drawer', 'Popover']);
  const EXEMPT_VARIANT = /(?:^|:)(disabled|placeholder)/;

  /**
   * 분기 배타성(한계 3) 때문에 오탐이 나는 지점만 **사유와 함께** 예외 처리한다.
   * ⚠ 새로 추가할 때는 반드시 "왜 그 조합이 실제로 렌더되지 않는지"를 적을 것.
   *   사유 없이 추가하면 이 가드는 그 순간 장식이 된다.
   */
  const EXEMPTIONS: { file: string; text: string; reason: string }[] = [
    {
      file: 'src/features/augment/components/ProcessKindCard.tsx',
      text: "selected ? 'text-primary-600' : 'text-gray-500'",
      reason:
        '배경이 bg-primary-50 이 되는 selected 분기에서는 글자가 text-primary-600 이라 ' +
        'gray-500 과 primary-50 이 동시에 렌더되지 않는다(배타 분기).',
    },
  ];

  interface Tag {
    line: number;
    indent: number;
    /** 여는 태그의 속성 구간(여러 줄 className 포함). */
    span: number[];
    /**
     * 이 요소의 **자식 범위가 끝나는 줄**. 이게 없으면 닫힌 형제를 자손으로 오인한다 —
     * 실제로 `ProgressBar` 의 퍼센트 라벨(트랙 `bg-gray-200` 의 **형제**)을 자손으로 집었다.
     */
    scopeEnd: number;
    name: string;
  }

  const indentOf = (l: string): number => l.length - l.trimStart().length;

  /** JSX 여는 태그와 그 속성 구간·자식 범위를 수집한다. */
  function openTags(lines: string[]): Tag[] {
    const tags: Tag[] = [];
    lines.forEach((raw, i) => {
      const s = raw.trim();
      const m = OPEN_TAG.exec(s);
      if (!m) return;
      const indent = indentOf(raw);
      const span = [i];
      if (!s.includes('>')) {
        for (let k = i + 1; k < lines.length; k += 1) {
          if (lines[k].trim() === '') continue;
          if (indentOf(lines[k]) <= indent) {
            if (lines[k].trim() === '>' || lines[k].trim() === '/>') span.push(k);
            break;
          }
          span.push(k);
        }
      }
      const spanText = span.map((k) => lines[k]).join(' ');
      const lastSpan = span[span.length - 1];
      // 자기완결(한 줄에 닫히거나 self-closing)이면 자식이 없다.
      let scopeEnd = lastSpan;
      if (!(spanText.includes('/>') || spanText.includes('</'))) {
        for (let k = lastSpan + 1; k < lines.length; k += 1) {
          if (lines[k].trim() === '') continue;
          if (indentOf(lines[k]) <= indent) break; // 닫는 태그 위치 — 여기서 범위가 끝난다
          scopeEnd = k;
        }
      }
      tags.push({ line: i, indent, span, scopeEnd, name: m[1] });
    });
    return tags;
  }

  type Surface =
    | { kind: 'white' }
    | {
        kind: 'light';
        line: number;
        family: string;
        /** 단계 없는 별칭 토큰(`bgLight`)이면 빈 문자열. */
        step: string;
        variant: string;
        /** 원문 배경 토큰(`gray-50` · `bgLight` …) — 위반 메시지용. */
        bgToken: string;
      };

  /** 텍스트 줄을 감싸는 가장 가까운 배경 표면을 찾는다(없으면 null = 파일 밖). */
  function surfaceOf(lines: string[], tags: Tag[], textLine: number): Surface | null {
    // textLine 이하의 태그들 중, 들여쓰기가 점점 얕아지는 조상 체인만 남긴다.
    const chain: Tag[] = [];
    let cur = Number.MAX_SAFE_INTEGER;
    for (let i = tags.length - 1; i >= 0; i -= 1) {
      const t = tags[i];
      if (t.line > textLine) continue;
      // ★자식 범위 밖이면 조상이 아니라 이미 닫힌 형제다.
      if (textLine > t.scopeEnd && !t.span.includes(textLine)) continue;
      if (t.indent < cur || t.span.includes(textLine)) {
        chain.push(t);
        cur = Math.min(cur, t.indent);
      }
    }
    for (const t of chain) {
      if (WHITE_SURFACE_COMPONENTS.has(t.name)) return { kind: 'white' };
      const text = t.span.map((k) => lines[k]).join(' ');
      const light = LIGHT_BG.exec(text);
      if (light && !EXEMPT_VARIANT.test(light[1])) {
        // ⚠ `bgLight` 는 하이픈이 없는 별칭 토큰이라 split 결과가 1칸이다 — step 은 '' 로 둔다.
        const [family, step = ''] = light[2].split('-');
        return { kind: 'light', line: t.line, family, step, variant: light[1], bgToken: light[2] };
      }
      if (WHITE_BG.test(text)) return { kind: 'white' };
    }
    return null;
  }

  /** ⚠ `bgLight` 는 단계 없는 별칭 토큰이라 `DEFAULT` 를 쓴다(step 은 undefined 로 들어온다). */
  const bgHexOf = (family: string, step: string): string =>
    family === 'bgLight'
      ? bgLight.DEFAULT
      : family === 'secondary'
        ? secondary[step]
        : family === 'primary'
          ? primary[step]
          : gray[step];

  const files = readdirSync(path.join(repoRoot, 'src'), { recursive: true, encoding: 'utf-8' })
    .filter((f) => /\.tsx$/.test(f))
    .map((f) => path.join('src', f));

  it('★조상_요소의_연한_배경_위_text_gray_500_이_한_건도_없다', () => {
    expect(files.length, '스캔 대상 .tsx 0건 — 파일 수집이 깨졌다').toBeGreaterThan(100);

    const violations: string[] = [];
    for (const rel of files) {
      const lines = readSrc(rel).split('\n');
      const tags = openTags(lines);
      lines.forEach((line, i) => {
        const texts = [...line.matchAll(TEXT_GRAY)].filter(
          (m) => BODY_TEXT_STEPS.has(m[2]) && !EXEMPT_VARIANT.test(m[1]),
        );
        if (texts.length === 0) return;
        const surface = surfaceOf(lines, tags, i);
        if (!surface || surface.kind !== 'light') return;
        const bg = bgHexOf(surface.family, surface.step);

        for (const [, variant, step] of texts) {
          const ratio = contrastRatio(gray[step], bg);
          if (ratio >= WCAG_AA_NORMAL_TEXT) continue;

          // hover 로만 깔리는 배경인데, 그 자리에서 글자도 함께 진해져 AA 를 넘기면
          // "밝은 배경 + 옅은 글자" 조합은 실제로 렌더되지 않는다.
          if (surface.variant !== '') {
            const ctx = lines.slice(Math.max(0, i - 2), i + 3).join(' ');
            const neutralized = [...ctx.matchAll(TEXT_GRAY)].some(
              (m) =>
                m[1] !== '' &&
                BODY_TEXT_STEPS.has(m[2]) &&
                contrastRatio(gray[m[2]], bg) >= WCAG_AA_NORMAL_TEXT,
            );
            if (neutralized) continue;
          }

          if (EXEMPTIONS.some((e) => e.file === rel && line.includes(e.text))) continue;

          violations.push(
            `${rel}:${i + 1} — ${variant}text-gray-${step} on ${surface.variant}bg-` +
              `${surface.bgToken} (조상 L${surface.line + 1}) = ${ratio.toFixed(2)}:1`,
          );
        }
      });
    }

    expect(
      violations,
      `조상 요소의 연한 배경 위 보조 텍스트가 AA(4.5:1) 미달이다. DS-001 do_rules(v8) 대로 ` +
        `**60단 이상**(text-gray-600)으로 올릴 것. 단 배경이 gray-200 이면 60단으로도 부족하니 ` +
        `(4.10) text-gray-700 을 쓴다. 색값은 KRDS 정본이라 조정 대상이 아니다:\n` +
        violations.join('\n'),
    ).toEqual([]);
  });

  it('★스캔기_자체_검증_조상의_멀티라인_className_배경을_실제로_읽는다', () => {
    // KpiCard 가 새어 나갔던 그 형태 — 조상 태그(<Wrapper)의 배경이 태그 줄이 아니라
    // **더 깊이 들여쓴 cn(...) 속성 구간**에 있다. 이걸 못 읽으면 가드가 조용히 통과한다.
    const sample = [
      '    <Wrapper',
      '      className={cn(',
      "        'bg-white',",
      "        selected && 'bg-primary-50',",
      '      )}',
      '    >',
      '      <div className="flex-1">',
      '        <p className="text-gray-500">라벨</p>',
      '      </div>',
      '    </Wrapper>',
    ];
    const surface = surfaceOf(sample, openTags(sample), 7);
    expect(surface).not.toBeNull();
    expect(surface?.kind).toBe('light');
    expect(surface).toMatchObject({ family: 'primary', step: '50' });
  });

  it('★스캔기_자체_검증_조상의_bgLight_별칭_배경을_실제로_읽는다', () => {
    // AugmentPromptSummary 가 새어 나갔던 그 형태 — 조상 <section> 이 `bg-bgLight`(#FAFBFC)를
    // 주고 자식 <dt> 가 글자색을 준다. `bgLight` 는 하이픈 없는 별칭이라 `bg-gray-*` 정규식에
    // 걸리지 않아 이 축을 빼면(구 상태로 되돌리면) surface 가 null 이 되어 실패한다.
    const sample = [
      '    <section className="rounded border border-border bg-bgLight p-3">',
      '      <dl>',
      '        <dt className="text-sub text-gray-500">시간대</dt>',
      '      </dl>',
      '    </section>',
    ];
    const surface = surfaceOf(sample, openTags(sample), 2);
    expect(surface).toMatchObject({ kind: 'light', family: 'bgLight', bgToken: 'bgLight' });
    // 단계 없는 별칭이 DEFAULT hex 로 해석되는지 — 여기가 끊기면 대비 계산이 NaN 이 된다.
    expect(bgHexOf('bgLight', '')).toBe('#FAFBFC');
    expect(contrastRatio(gray['500'], bgHexOf('bgLight', ''))).toBeLessThan(WCAG_AA_NORMAL_TEXT);
  });

  it('★스캔기_자체_검증_흰_배경_조상은_안전으로_판정한다', () => {
    // 흰 카드 안의 text-gray-500 은 4.51 로 통과라 **건드리면 안 된다** — 과잉 검출 방지.
    const sample = [
      '    <div className="rounded-lg bg-white p-4">',
      '      <p className="text-gray-500">등록된 라벨이 없습니다.</p>',
      '    </div>',
    ];
    expect(surfaceOf(sample, openTags(sample), 1)?.kind).toBe('white');
  });

  it('★스캔기_자체_검증_이미_닫힌_형제의_배경을_상속하지_않는다', () => {
    // ProgressBar 형태 — 트랙(bg-gray-200)이 먼저 닫히고 퍼센트 라벨은 그 **형제**다.
    // 자식 범위를 추적하지 않으면 라벨을 트랙 안으로 오인해 오탐이 난다(실제로 났다).
    const sample = [
      '    <div className="flex items-center gap-2">',
      '      <div className="flex-1 bg-gray-200 rounded-full">',
      '        <div className="h-full" />',
      '      </div>',
      '      <span className="text-gray-600">50%</span>',
      '    </div>',
    ];
    const tags = openTags(sample);
    // 트랙 안쪽(L2)은 gray-200 표면이 맞다.
    expect(surfaceOf(sample, tags, 2)).toMatchObject({ kind: 'light', step: '200' });
    // 트랙이 닫힌 뒤의 형제(L4)는 그 배경을 물려받지 않는다.
    expect(surfaceOf(sample, tags, 4)).toBeNull();
  });

  it('★예외목록은_사유가_적힌_것만_허용한다', () => {
    for (const e of EXEMPTIONS) {
      expect(e.reason.length, `${e.file}: 예외 사유가 비었다`).toBeGreaterThan(30);
      expect(readSrc(e.file), `${e.file}: 예외 대상 코드가 이미 없다 — 예외를 지울 것`).toContain(
        e.text,
      );
    }
  });
});

/**
 * ★**진한** 배경 위 자식의 회색 강제 — 부모→자식 스캔(2026-08-09, 4차).
 *
 * 왜 새 축인가: 위 두 스캔의 배경 축은 **연한 표면만** 열거한다(gray-50/100/200 ·
 * secondary-50 · primary-50 · bgLight). 그래서 **선택 상태에서 배경이 진해지는**
 * 요소(`bg-primary`)는 축에 아예 없었고, 그 위에서 자식이 회색을 강제하는 조합을
 * **구조적으로 못 봤다**. 실제로 `LabelPanel` 의 선택된 라벨 트랙번호가 파란 배경
 * (#256EF4) 위 gray-600(#58616A) = **1.38:1** 로 렌더되고 있었다(AA 4.5 는 물론 UI
 * 요소 기준 3:1 에도 크게 미달). 이 파일의 반복 실패 패턴("배경 축을 열거로 관리하면
 * 목록에 없는 표면은 못 본다")이 **네 번째**로 재현된 것이다.
 *
 * ── 극성(polarity) 주의 ─────────────────────────────────────────────────
 * 연한 배경에서는 "글자가 어두울수록 통과"였지만 진한 배경에서는 **정답이 반대**(흰 글자).
 * 그래서 방향을 가정하지 않고 위 스캔들과 **같은 방식으로 실제 대비비를 계산**한다 —
 * `contrastRatio(전경, 배경)` 는 어느 쪽이 밝은지와 무관하므로 극성이 반대여도 그대로
 * 성립한다. 즉 새 규칙을 만든 게 아니라 배경 축만 넓힌 것이다.
 *
 * ── 오탐을 거르는 두 규칙 (이것이 없으면 가드가 못 쓴다) ──────────────────
 *  A. **조상 태그의 속성 구간 안**에 있는 회색은 건너뛴다. 그건 조상의 className **같은
 *     삼항식**이라 `active ? 'bg-primary-600 text-white' : 'bg-gray-100 text-gray-700'`
 *     처럼 회색이 **연한 배경 분기와 짝**인 경우다(배타 분기 — 동시에 렌더되지 않는다).
 *     실측 결과 이 저장소의 진한 배경 × 회색 조합 10건이 **전부** 이 형태였다.
 *  B. **같은 요소의 className 문맥(±2줄)에 그 진한 배경에서 AA 를 넘는 대안 전경색**
 *     (`text-white` 등)이 함께 있으면 배타 분기로 보고 건너뛴다. 자식이 부모 상태에 따라
 *     색을 바꾸는 정상 구현이 이 형태다.
 *
 * → 그래서 이 가드는 **"자식이 상태와 무관하게 회색을 박은 경우"만** 잡는다. 바로 그것이
 *   위 실사고의 형태이고, 되돌리면(조건부 색을 지우고 다시 회색 고정) 즉시 FAIL 한다.
 *
 * ── 못 보는 것 (정직성 목록) ────────────────────────────────────────────
 *  1. 배경 축이 **여전히 열거**다 — primary/accent/secondary/danger/gray-700+ 만 본다.
 *     새 진한 표면 토큰을 만들면 반드시 `DARK_BG` 에 추가할 것.
 *  2. 글자 축도 열거다 — `text-gray-NNN` 만. `text-neutral` 별칭은 안 본다.
 *  3. 파일을 넘는 조합·런타임 배경(`style`)·`.ts` 파일은 위 스캔들과 같은 이유로 밖이다.
 *  4. 규칙 A 는 **조상 삼항식 안의 진짜 위반**(예: `'bg-primary text-gray-600'` 을 한
 *     분기에 함께 쓴 경우)을 함께 놓친다 — 배타 분기와 구분할 정적 근거가 없어서다.
 */
describe('진한 배경 위 자식의 회색 강제 — 부모→자식 스캔(반대 극성 축)', () => {
  const gray = asObj(colors.gray);
  const WHITE = '#FFFFFF';

  /**
   * 진한 표면 토큰. ⚠ 대안 순서가 중요하다 — `primary-600` 이 `primary` 보다 앞에 와야
   * 하고, 바로 뒤에 `-`/`/` 가 오면(= `bg-primary-50`, `bg-danger/10` 같은 **연한** 표면)
   * 매칭에서 빼야 한다. 이 lookahead 가 없으면 10% 틴트를 진한 배경으로 오인한다.
   */
  const DARK_BG =
    /(?:^|[\s"'`([{])((?:[a-z-]+:)*)bg-(primary-(?:[5-9]00|950)|primary(?![-/])|accent(?![-/])|secondary-(?:500|600)|secondary(?![-/])|danger-(?:[5-9]00|950)|danger(?![-/])|gray-(?:[7-9]00|950))\b/;
  const ANY_BG = /(?:^|[\s"'`([{])((?:[a-z-]+:)*)bg-[a-zA-Z]/;
  const TEXT_GRAY = /(?:^|[\s"'`([{])((?:[a-z-]+:)*)text-gray-(\d{2,3})\b/g;
  const TEXT_WHITE = /(?:^|[\s"'`([{])((?:[a-z-]+:)*)text-white\b/;
  const OPEN_TAG = /^<([A-Za-z][\w.]*)/;
  const EXEMPT_VARIANT = /(?:^|:)(disabled|placeholder)/;

  /** `bg-{token}` 을 hex 로. 단계 없는 토큰(`primary`·`accent`)은 DEFAULT. */
  const darkHexOf = (token: string): string => {
    const dash = token.indexOf('-');
    const family = dash === -1 ? token : token.slice(0, dash);
    const step = dash === -1 ? '' : token.slice(dash + 1);
    const scale = asObj(colors[family]);
    const hex = step ? scale[step] : scale.DEFAULT;
    if (!hex) throw new Error(`진한 배경 토큰을 해석할 수 없음: bg-${token}`);
    return hex;
  };

  interface Tag {
    line: number;
    indent: number;
    span: number[];
    scopeEnd: number;
    name: string;
  }
  const indentOf = (l: string): number => l.length - l.trimStart().length;

  /** 위 부모→자식 스캔과 동일한 태그 수집기(속성 구간 + 자식 범위). */
  function openTags(lines: string[]): Tag[] {
    const tags: Tag[] = [];
    lines.forEach((raw, i) => {
      const s = raw.trim();
      const m = OPEN_TAG.exec(s);
      if (!m) return;
      const indent = indentOf(raw);
      const span = [i];
      if (!s.includes('>')) {
        for (let k = i + 1; k < lines.length; k += 1) {
          if (lines[k].trim() === '') continue;
          if (indentOf(lines[k]) <= indent) {
            if (lines[k].trim() === '>' || lines[k].trim() === '/>') span.push(k);
            break;
          }
          span.push(k);
        }
      }
      const spanText = span.map((k) => lines[k]).join(' ');
      const lastSpan = span[span.length - 1];
      let scopeEnd = lastSpan;
      if (!(spanText.includes('/>') || spanText.includes('</'))) {
        for (let k = lastSpan + 1; k < lines.length; k += 1) {
          if (lines[k].trim() === '') continue;
          if (indentOf(lines[k]) <= indent) break;
          scopeEnd = k;
        }
      }
      tags.push({ line: i, indent, span, scopeEnd, name: m[1] });
    });
    return tags;
  }

  interface DarkSurface {
    line: number;
    token: string;
    variant: string;
    /** 규칙 A — 텍스트가 이 조상의 속성 구간(같은 삼항식) 안에 있는가. */
    sameExpression: boolean;
  }

  /** 텍스트 줄을 감싸는 가장 가까운 배경이 **진한 표면**이면 그것을 돌려준다. */
  function darkSurfaceOf(lines: string[], tags: Tag[], textLine: number): DarkSurface | null {
    const chain: Tag[] = [];
    let cur = Number.MAX_SAFE_INTEGER;
    for (let i = tags.length - 1; i >= 0; i -= 1) {
      const t = tags[i];
      if (t.line > textLine) continue;
      if (textLine > t.scopeEnd && !t.span.includes(textLine)) continue;
      if (t.indent < cur || t.span.includes(textLine)) {
        chain.push(t);
        cur = Math.min(cur, t.indent);
      }
    }
    for (const t of chain) {
      const text = t.span.map((k) => lines[k]).join(' ');
      const dark = DARK_BG.exec(text);
      if (dark && !EXEMPT_VARIANT.test(dark[1])) {
        return {
          line: t.line,
          token: dark[2],
          variant: dark[1],
          sameExpression: t.span.includes(textLine),
        };
      }
      // 진한 배경이 아닌 다른 배경을 먼저 만나면 그 표면이 이긴다(더 올라가지 않는다).
      if (ANY_BG.test(text)) return null;
    }
    return null;
  }

  const files = readdirSync(path.join(repoRoot, 'src'), { recursive: true, encoding: 'utf-8' })
    .filter((f) => /\.tsx$/.test(f))
    .map((f) => path.join('src', f));

  it('★진한_배경_조상_아래에서_회색을_무조건_강제하는_자식이_한_건도_없다', () => {
    expect(files.length, '스캔 대상 .tsx 0건 — 파일 수집이 깨졌다').toBeGreaterThan(100);

    const violations: string[] = [];
    for (const rel of files) {
      const lines = readSrc(rel).split('\n');
      const tags = openTags(lines);
      lines.forEach((line, i) => {
        const texts = [...line.matchAll(TEXT_GRAY)].filter((m) => !EXEMPT_VARIANT.test(m[1]));
        if (texts.length === 0) return;
        const surface = darkSurfaceOf(lines, tags, i);
        if (!surface) return;
        // 규칙 A — 조상 자신의 className 삼항식 안이면 배타 분기일 수 있어 판정하지 않는다.
        if (surface.sameExpression) return;

        const bg = darkHexOf(surface.token);
        // 규칙 B — 같은 요소 문맥에 그 배경에서 AA 를 넘는 대안 전경색이 있으면 배타 분기다.
        const ctx = lines.slice(Math.max(0, i - 2), i + 3).join(' ');
        const hasSafeAlternative =
          TEXT_WHITE.test(ctx) && contrastRatio(WHITE, bg) >= WCAG_AA_NORMAL_TEXT;
        if (hasSafeAlternative) return;

        for (const [, variant, step] of texts) {
          const ratio = contrastRatio(gray[step], bg);
          if (ratio >= WCAG_AA_NORMAL_TEXT) continue;
          violations.push(
            `${rel}:${i + 1} — ${variant}text-gray-${step} on ${surface.variant}bg-` +
              `${surface.token} (조상 L${surface.line + 1}) = ${ratio.toFixed(2)}:1`,
          );
        }
      });
    }

    expect(
      violations,
      `진한 배경 위에서 자식이 회색을 강제해 AA(4.5:1) 미달이다. 부모가 상태에 따라 전경색을 ` +
        `바꾸는 요소라면 자식도 **같은 상태로 분기**시킬 것(선택 시 text-white, 비선택 시 ` +
        `text-gray-600). 색값은 KRDS 정본이라 조정 대상이 아니다:\n` +
        violations.join('\n'),
    ).toEqual([]);
  });

  it('★LabelPanel_선택된_라벨의_트랙번호가_파란_배경_위_회색이_아니다', () => {
    // 앵커 — 위 전수 스캔의 규칙 A/B 가 느슨해지더라도 이 지점만은 직접 고정한다.
    const src = readSrc('src/features/label/components/LabelPanel.tsx');
    const idx = src.indexOf('data-testid="label-track-id"');
    expect(idx, '트랙번호 span 의 testid 가 사라졌다 — 앵커를 갱신할 것').toBeGreaterThan(-1);
    const block = src.slice(idx, idx + 400);

    // 선택 분기가 흰 글자로 갈라져 있어야 한다. 조건부를 지우고 회색 고정으로 되돌리면 실패한다.
    expect(block, '선택 상태에서 트랙번호가 부모 전경색(흰색)을 따르지 않는다').toContain(
      'text-white',
    );
    expect(block, '트랙번호가 상태와 무관하게 회색으로 고정돼 있다').toMatch(
      /selectedId === item\.id \?\s*'text-white'\s*:\s*'text-gray-600'/,
    );
  });

  it('★회귀_원인_문서화_gray_600_은_bg_primary_위에서_AA는커녕_3대1도_못_넘는다', () => {
    const primary = asObj(colors.primary);
    // 실사고 값 — 파란 배경 위 회색 글씨.
    expect(Number(contrastRatio(gray['600'], primary.DEFAULT).toFixed(2))).toBe(1.38);
    expect(contrastRatio(gray['600'], primary.DEFAULT)).toBeLessThan(WCAG_AA_LARGE_TEXT_OR_ICON);
    // 교정 방향(흰 글자)은 AA 를 넘는다 — 다만 4.55 로 **경계**라 primary 값이 조금이라도
    // 밝아지면 흰 글자마저 미달이 된다. 그 경우 표면을 primary-600(6.83)으로 올려야 한다.
    expect(contrastRatio(WHITE, primary.DEFAULT)).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(WHITE, primary.DEFAULT)).toBeLessThan(4.7);
    expect(contrastRatio(WHITE, primary['600'])).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
  });

  it('★스캔기_자체_검증_연한_틴트를_진한_배경으로_오인하지_않는다', () => {
    // `bg-primary-50` · `bg-danger/10` 은 **연한** 표면이라 이 축의 대상이 아니다.
    // lookahead 가 빠지면 여기서 매칭이 생겨 실패한다(위 연한 배경 스캔과 이중 판정이 된다).
    expect(DARK_BG.test('className="bg-primary-50 p-2"')).toBe(false);
    expect(DARK_BG.test('className="hover:bg-danger/10 p-2"')).toBe(false);
    expect(DARK_BG.exec('className="bg-primary text-sm"')?.[2]).toBe('primary');
    expect(DARK_BG.exec('className="bg-primary-600"')?.[2]).toBe('primary-600');
    expect(darkHexOf('primary')).toBe('#256EF4');
    expect(darkHexOf('primary-600')).toBe('#0B50D0');
  });

  it('★스캔기_자체_검증_조상의_진한_배경을_읽고_배타분기만_면제한다', () => {
    // (1) 자식이 무조건 회색 — 이번에 고친 그 형태. 조상 span 밖이라 규칙 A 로 면제되지 않는다.
    const bad = [
      '  <button',
      '    className={cn(',
      "      'rounded px-2',",
      "      selected ? 'bg-primary text-white' : 'text-neutral',",
      '    )}',
      '  >',
      '    <span className="ml-1 text-gray-600">#42</span>',
      '  </button>',
    ];
    const s = darkSurfaceOf(bad, openTags(bad), 6);
    expect(s, '조상의 진한 배경을 못 읽었다').not.toBeNull();
    expect(s?.token).toBe('primary');
    expect(s?.sameExpression, '자식 줄은 조상 속성 구간 밖이다').toBe(false);

    // (2) 조상 삼항식 안의 회색 — 연한 배경 분기와 짝이라 면제 대상(규칙 A).
    const exclusive = [
      '  <button',
      '    className={cn(',
      "      active ? 'bg-primary-600 text-white' : 'bg-gray-100 text-gray-700',",
      '    )}',
      '  >',
    ];
    const s2 = darkSurfaceOf(exclusive, openTags(exclusive), 2);
    expect(s2?.sameExpression, '조상 자신의 속성 줄인데 면제되지 않았다').toBe(true);
  });
});
