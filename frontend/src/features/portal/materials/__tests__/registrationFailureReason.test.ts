/**
 * 회귀 가드 — 데이터셋 영상 등록 실패 사유 표기. [@design API-253] [@design API-262] [@design SCREEN-046]
 *
 * ★ 폴백을 실제로 타는 픽스처를 함께 둔다 — 배선만 하고 알려진 값만 흘리면 그 가지는 한 번도 실행되지 않고,
 *   폴백을 지워도 전건 초록이라 「폴백을 뒀다」는 주장과 증명이 갈린다.
 * ★ 값 축을 고정한다 — 형식·존재 검사만 두면 사유끼리 문구가 뒤바뀌어도 통과한다(이 저장소 실사고).
 */
import { describe, expect, it } from 'vitest';

import { registrationFailureNotice } from '../registrationFailureReason';
import { PortalDatasetRegistrationFailureReason } from '../types';

/** API-262·API-253 의 enum 12종 — 서버 계약과 우리 표가 어긋나면 여기서 갈린다. */
const CONTRACT_REASONS = [
  'CONTENT_MISSING',
  'SYMLINK_REJECTED',
  'NO_VIDEO',
  'VIDEO_FILENAME_MISSING',
  'AMBIGUOUS_VIDEO_KEY',
  'INVALID_VIDEO_KEY',
  'PAIR_MISMATCH',
  'DOCUMENT_UNREADABLE',
  'IMAGE_NOT_JPEG',
  'INVALID_VALUE',
  'DISABLED',
  'IO_ERROR',
] as const;

/** 같은 소재로는 같은 결과가 돌아오는 구조 불일치 계열. */
const SAME_RESULT_REASONS = [
  'CONTENT_MISSING',
  'SYMLINK_REJECTED',
  'NO_VIDEO',
  'AMBIGUOUS_VIDEO_KEY',
  'INVALID_VIDEO_KEY',
  'PAIR_MISMATCH',
  'DOCUMENT_UNREADABLE',
  'IMAGE_NOT_JPEG',
  'INVALID_VALUE',
] as const;

describe('데이터셋 영상 등록 실패 사유 표기', () => {
  it('우리_표의_값역이_계약_enum_12종과_같다', () => {
    expect([...Object.values(PortalDatasetRegistrationFailureReason)].sort()).toEqual(
      [...CONTRACT_REASONS].sort(),
    );
  });

  it('알려진_사유_전부에_문구가_있고_사유_코드를_담지_않는다', () => {
    for (const reason of CONTRACT_REASONS) {
      const notice = registrationFailureNotice(reason);
      expect(notice.title.trim(), `${reason} 의 제목이 비었다`).not.toBe('');
      expect(notice.description.trim(), `${reason} 의 설명이 비었다`).not.toBe('');
      expect(notice.title).not.toContain(reason);
      expect(notice.description).not.toContain(reason);
    }
  });

  it('사유마다_제목이_다르다_한_문구로_뭉개는_변이를_잡는다', () => {
    const titles = CONTRACT_REASONS.map((r) => registrationFailureNotice(r).title);
    expect(new Set(titles).size).toBe(titles.length);
  });

  it('★구조_불일치_계열은_다시_등록을_권하지_않고_같은_결과임을_말한다', () => {
    for (const reason of SAME_RESULT_REASONS) {
      const notice = registrationFailureNotice(reason);
      expect(notice.retryWorthwhile, reason).toBe(false);
      expect(notice.description, reason).toContain('같은 소재로는 같은 결과입니다');
    }
  });

  it('★영상_파일명_없음은_구조_계열이지만_저작도구_갱신_뒤_재등록을_안내한다', () => {
    // 2026-09-16 실사고의 사유 — 파서를 고쳐 배포한 뒤 사람이 한 번 다시 등록하면 풀린다.
    const notice = registrationFailureNotice(
      PortalDatasetRegistrationFailureReason.VIDEO_FILENAME_MISSING,
    );
    expect(notice.retryWorthwhile).toBe(false);
    expect(notice.description).toContain('저작도구가 갱신된 뒤에는 다시 등록해 볼 수 있습니다');
    expect(notice.description).not.toContain('같은 소재로는 같은 결과입니다');
  });

  it('등록_꺼짐은_운영자_설정이라_권하지_않고_입출력_오류는_일시_장애라_권한다', () => {
    const disabled = registrationFailureNotice(PortalDatasetRegistrationFailureReason.DISABLED);
    expect(disabled.retryWorthwhile).toBe(false);
    expect(disabled.description).toContain('운영자');
    expect(registrationFailureNotice(PortalDatasetRegistrationFailureReason.IO_ERROR).retryWorthwhile).toBe(
      true,
    );
  });

  it('★우리가_모르는_사유가_와도_빈칸이_되지_않고_코드를_찍지_않으며_다시_등록을_권한다', () => {
    const notice = registrationFailureNotice('QUOTA_EXCEEDED');
    expect(notice.title.trim()).not.toBe('');
    expect(notice.description.trim()).not.toBe('');
    expect(notice.retryWorthwhile).toBe(true);
    expect(notice.title).not.toContain('QUOTA_EXCEEDED');
    expect(notice.description).not.toContain('QUOTA_EXCEEDED');
  });

  it('사유가_비어_와도_빈칸이_되지_않는다', () => {
    for (const empty of [null, undefined]) {
      const notice = registrationFailureNotice(empty);
      expect(notice.title.trim()).not.toBe('');
      expect(notice.description.trim()).not.toBe('');
    }
  });
});
