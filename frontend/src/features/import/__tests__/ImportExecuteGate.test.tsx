import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ImportExecuteSection } from '@/features/import/components/ImportExecuteSection';
import type { ImportScanResult, ImportWarning } from '@/features/import/types';
import { renderWithProviders } from '@/test/renderWithProviders';

/**
 * ★적재 가능 여부의 판정은 검사 응답의 `importable` 값 **하나**다.
 *
 * 검사 알림은 한 목록으로 오고 그 안에 적재를 막는 사유와 막지 않는 사유가 함께 담긴다. 화면이
 * 그 개수를 세어 판정하면 판정 지점이 둘이 되고, 두 값이 어긋날 때 어느 쪽이 진실인지 알 수 없다.
 *
 * ⚠ mutation 확인 절차: `ImportExecuteSection` 의 `const importable = scan.importable;` 을
 *   `scan.warnings.length === 0` 이나 `groupWarnings(...).blocking.length === 0` 으로 바꾸면
 *   아래 두 케이스가 **각각 반대 방향으로** 실패해야 한다(실제로 확인함).
 */

const BLOCKING: ImportWarning = {
  code: 'SCAN_LIMIT_EXCEEDED',
  message: '폴더의 파일 수가 한 번에 훑을 수 있는 상한을 넘어 읽지 않았다.',
};
const ADVISORY: ImportWarning = {
  code: 'UNPAIRED_IMAGE',
  message: '짝 문서가 없는 이미지가 있다.',
};

function scanWith(overrides: Partial<ImportScanResult>): ImportScanResult {
  return {
    frameCount: 120,
    declaredFrameCount: 120,
    labelCount: 480,
    videoFileName: '00000073.mp4',
    duplicate: null,
    unmappedCategories: [],
    warnings: [],
    importable: true,
    ...overrides,
  };
}

function renderSection(scan: ImportScanResult, acknowledged: boolean, onImport = vi.fn()) {
  renderWithProviders(
    <ImportExecuteSection
      scan={scan}
      deidentified={false}
      hasVideoPath={false}
      acknowledged={acknowledged}
      importing={false}
      result={null}
      errorMessage={null}
      onAcknowledgedChange={vi.fn()}
      onImport={onImport}
    />,
  );
  return screen.getByTestId('import-execute-button');
}

describe('적재 실행 — 판정은 적재 가능 여부 값 하나가 소유한다', () => {
  it('★막는_알림이_있어도_적재_가능_여부가_참이면_버튼이_활성이다', () => {
    // given: 적재를 막는 것으로 알려진 코드가 알림에 들어 있는데 서버는 적재 가능이라고 답했다
    const scan = scanWith({ warnings: [BLOCKING, ADVISORY], importable: true });

    // when: 알림을 확인했다고 표시한 상태로 렌더
    const button = renderSection(scan, true);

    // then: 활성 — 알림 개수·종류로 다시 판정하지 않는다
    expect(button).toBeEnabled();
  });

  it('★알림이_하나도_없어도_적재_가능_여부가_거짓이면_버튼이_비활성이다', () => {
    // given: 알림이 비어 있는데 서버는 적재할 수 없다고 답했다(대응 미확정·중복 등)
    const scan = scanWith({ warnings: [], importable: false });

    const button = renderSection(scan, true);

    expect(button).toBeDisabled();
    // 사유를 함께 알린다 — 비활성만 두면 왜 못 누르는지 알 수 없다
    expect(screen.getByTestId('import-not-importable-reason')).toBeInTheDocument();
  });

  it('알림이_있으면_확인_표시_전까지는_누를_수_없다_확인은_감사_기록이다', async () => {
    const user = userEvent.setup();
    const onImport = vi.fn();
    // given: 적재 가능하지만 알림이 남아 있고 아직 확인하지 않았다
    const button = renderSection(scanWith({ warnings: [ADVISORY] }), false, onImport);

    expect(button).toBeDisabled();
    await user.click(button);

    // then: 요청이 나가지 않는다
    expect(onImport).not.toHaveBeenCalled();
  });

  it('알림이_없으면_확인_칸_자체가_뜨지_않는다', () => {
    renderSection(scanWith({ warnings: [] }), false);
    expect(screen.queryByTestId('import-ack-checkbox')).not.toBeInTheDocument();
    expect(screen.getByTestId('import-execute-button')).toBeEnabled();
  });
});

describe('적재 결과 — 승인 보류와 그 보류를 푸는 길', () => {
  const RESULT = { rawSn: 42, trnsfSn: 7, frameCount: 120, labelCount: 480 };

  it('원본으로_가져오면_보류_안내와_막히는_범위를_함께_알린다', () => {
    renderWithProviders(
      <ImportExecuteSection
        scan={scanWith({})}
        deidentified={false}
        hasVideoPath
        acknowledged
        importing={false}
        result={RESULT}
        errorMessage={null}
        onAcknowledgedChange={vi.fn()}
        onImport={vi.fn()}
      />,
    );

    expect(screen.getByTestId('import-approval-hold')).toHaveTextContent('검수 승인');
    // 화면 전체가 잠긴 것으로 오해하지 않도록 열려 있는 것을 함께 적는다
    expect(screen.getByTestId('import-approval-hold')).toHaveTextContent('라벨 조회');
    // 영상 파일을 함께 준 경우의 갈래
    expect(screen.getByTestId('import-approval-hold-path')).toHaveTextContent('비식별 단계');
  });

  it('프레임만_가져오면_보류를_푸는_길이_외부_산출물_기록임을_알린다', () => {
    renderWithProviders(
      <ImportExecuteSection
        scan={scanWith({})}
        deidentified={false}
        hasVideoPath={false}
        acknowledged
        importing={false}
        result={RESULT}
        errorMessage={null}
        onAcknowledgedChange={vi.fn()}
        onImport={vi.fn()}
      />,
    );

    expect(screen.getByTestId('import-approval-hold-path')).toHaveTextContent('이관 이력');
  });

  it('비식별이_끝난_것으로_가져오면_보류_안내가_뜨지_않는다', () => {
    renderWithProviders(
      <ImportExecuteSection
        scan={scanWith({})}
        deidentified
        hasVideoPath={false}
        acknowledged
        importing={false}
        result={RESULT}
        errorMessage={null}
        onAcknowledgedChange={vi.fn()}
        onImport={vi.fn()}
      />,
    );

    expect(screen.queryByTestId('import-approval-hold')).not.toBeInTheDocument();
  });

  it('적재_실패_안내는_서버_메시지를_그대로_보여준다_409의_기존_영상_번호가_거기_있다', () => {
    renderWithProviders(
      <ImportExecuteSection
        scan={scanWith({})}
        deidentified={false}
        hasVideoPath={false}
        acknowledged
        importing={false}
        result={null}
        errorMessage="이미 가져온 산출물입니다. 기존 영상 번호: 42"
        onAcknowledgedChange={vi.fn()}
        onImport={vi.fn()}
      />,
    );

    expect(screen.getByTestId('import-execute-error')).toHaveTextContent('기존 영상 번호: 42');
  });
});
