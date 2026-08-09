import { readFileSync } from 'node:fs';
import path from 'node:path';

import resolveConfig from 'tailwindcss/resolveConfig';
import { describe, expect, it } from 'vitest';

// tailwind.config.js 는 타입 선언이 없는 plain JS(ESM default export)
// @ts-expect-error -- 설정 파일은 .js 라 타입 선언이 없음
import tailwindConfig from '../../tailwind.config.js';

import { compositeOver, contrastRatio, WCAG_AA_NORMAL_TEXT } from './wcagContrast';

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
