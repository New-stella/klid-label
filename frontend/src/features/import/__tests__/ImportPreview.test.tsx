import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { ImportPreviewPanel } from '@/features/import/components/ImportPreviewPanel';
import type { ImportScanResult } from '@/features/import/types';
import { groupWarnings } from '@/features/import/warningDisplay';

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

describe('미리보기 — 한 목록으로 온 알림을 보여주기 위해 두 묶음으로 가른다', () => {
  it('막는_알림과_알리기만_하는_알림이_구분되어_나온다', () => {
    render(
      <ImportPreviewPanel
        result={scanWith({
          warnings: [
            { code: 'NO_FRAME_FOUND', message: '폴더에서 프레임을 하나도 찾지 못했다.' },
            { code: 'SYMBOLIC_LINK_SKIPPED', message: '폴더 안의 바로가기를 건너뛰었다.' },
          ],
          importable: false,
        })}
      />,
    );

    expect(screen.getByTestId('import-blocking-warnings')).toHaveTextContent('프레임을 하나도');
    const advisory = screen.getByTestId('import-advisory-warnings');
    expect(advisory).toHaveTextContent('바로가기를 건너뛰었다');
    // 알리기만 하는 사항이 「차단」으로 읽히지 않도록 프레이밍한다
    expect(advisory).toHaveTextContent('적재를 막지 않습니다');
  });

  it('알림이_없으면_두_묶음_모두_뜨지_않는다', () => {
    render(<ImportPreviewPanel result={scanWith({ warnings: [] })} />);
    expect(screen.queryByTestId('import-blocking-warnings')).not.toBeInTheDocument();
    expect(screen.queryByTestId('import-advisory-warnings')).not.toBeInTheDocument();
  });

  it('선언_건수와_실제_파일_수가_다르면_둘_다_보여주고_실제_파일_기준임을_밝힌다', () => {
    render(<ImportPreviewPanel result={scanWith({ frameCount: 118, declaredFrameCount: 120 })} />);

    expect(screen.getByTestId('import-preview-frames')).toHaveTextContent('118 / 120');
    expect(screen.getByTestId('import-declared-mismatch')).toHaveTextContent('실제 파일');
  });

  it('이미_가져온_산출물이면_기존_영상_번호를_보여준다', () => {
    render(
      <ImportPreviewPanel result={scanWith({ duplicate: { rawSn: 42 }, importable: false })} />,
    );
    expect(screen.getByTestId('import-duplicate')).toHaveTextContent('42');
  });
});

describe('알림 묶기 — 판정이 아니라 표시용이다', () => {
  it('모르는_코드는_버리지_않고_알림_쪽에_담는다', () => {
    // 서버가 새 코드를 더해도 그 사실이 화면에서 사라지지 않아야 한다.
    const { blocking, advisory } = groupWarnings([
      { code: 'BRAND_NEW_CODE_FROM_SERVER', message: '아직 모르는 사유' },
    ]);
    expect(blocking).toHaveLength(0);
    expect(advisory).toHaveLength(1);
  });

  it('서버가_준_순서를_묶음_안에서_유지한다', () => {
    const { advisory } = groupWarnings([
      { code: 'UNPAIRED_IMAGE', message: '첫째' },
      { code: 'MISSING_IMAGE_FILE', message: '둘째' },
    ]);
    expect(advisory.map((w) => w.message)).toEqual(['첫째', '둘째']);
  });

  it('알림이_없거나_필드가_비어도_터지지_않는다', () => {
    expect(groupWarnings(undefined)).toEqual({ blocking: [], advisory: [] });
  });
});
