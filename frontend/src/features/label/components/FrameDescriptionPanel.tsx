// blocker#2 Phase 3 — 프레임 설명(NIA image.description) 입력 패널.
//
// 라벨링 RightPanel 에 삽입되는 접이식 설명 편집 패널.
// - useFrameDescription(srcSn) 로 기존 설명 로드 → textarea 바인딩
// - 수정 후 저장 버튼으로 useUpdateFrameDescription(srcSn) 호출
// - dirty 체크: 원본과 다를 때만 저장 활성
//
// 보안(저장형 XSS 방어): 설명은 textarea value 로만 바인딩 — React 기본 escape.
//   dangerouslySetInnerHTML 미사용. maxLength 1000(BE @Size 정합).
// a11y: <label htmlFor> ↔ textarea id 연결.

import { useEffect, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Textarea } from '@/components/common/Textarea';

import { useFrameDescription, useUpdateFrameDescription } from '../hooks/useFrameDescription';

import { MetaCharCount, MetaReadonlyField, MetaSection, NOT_FILLED_TEXT } from './MetaSection';

/**
 * 검수 화면의 라벨·보조 설명 — 화면정의서 SCREEN-019 의 「프레임 설명 검토」 note 원문.
 *
 * ★이 구역만 <b>제목 아래 설명 줄을 두지 않는다</b> — 대신 <b>값 아래</b> 보조 설명 한 줄을 두며,
 * 그 줄도 다른 구역의 설명문과 <b>같은 도움말 토글</b>을 탄다(사양 SCREEN-019 가 자리와 노출
 * 조건을 함께 규정한다). 게이트는 {@code MetaReadonlyField} 한 곳이 소유한다.
 *
 * ⚠ [폐기] 구 주석 — *"그 줄은 도움말과 무관하게 항상 보인다 … 검수 판단의 근거이기 때문이다"*.
 *   그것은 사양이 아니라 구현이 게이트를 빠뜨린 상태를 설명으로 굳힌 문장이었고, 신규 시험까지
 *   그 상태를 단언해 <b>비정합이 사양처럼 고정</b>돼 있었다. 되살리지 말 것.
 */
const READONLY_LABEL = '현재 프레임 설명';
const READONLY_HELP = '학습데이터 산출물의 이미지 설명 조달원입니다.';

export interface FrameDescriptionPanelProps {
  srcSn: number | undefined;
  /**
   * 읽기 전용 모드 — 입력 칸·저장 버튼을 <b>렌더하지 않고</b> 설명만 보여준다.
   *
   * ★기본값은 <b>편집 가능</b>이다(false). 기본값이 읽기 전용으로 새면 작업자가 프레임 설명을
   * 입력하지 못한다. 검수 화면만 명시적으로 켠다.
   */
  readOnly?: boolean;
}

const TEXTAREA_ID = 'frame-description-input';
const MAX_LEN = 1000;

export function FrameDescriptionPanel({
  srcSn,
  readOnly = false,
}: FrameDescriptionPanelProps) {
  const { data, isLoading } = useFrameDescription(srcSn);
  const update = useUpdateFrameDescription(srcSn);

  const [text, setText] = useState('');

  // 프레임 전환(data 변경) 시 로컬 입력 상태 동기화.
  useEffect(() => {
    setText(data?.description ?? '');
  }, [data?.description, srcSn]);

  const original = data?.description ?? '';
  const dirty = text !== original;
  const canSave = srcSn !== undefined && dirty && !update.isPending;

  const handleSave = () => {
    if (!canSave) return;
    // 빈 문자열은 설명 삭제(null) 로 전송 — BE 가 blank→null 정규화.
    const payload = text.trim() === '' ? null : text;
    update.mutate(payload);
  };

  // 읽기 전용(검수 화면) — 등록된 설명만 보여준다. 비활성 입력 칸을 두지 않는다.
  if (readOnly) {
    // ★검수 화면의 구역 이름은 라벨링과 **일부러 다르다** — 검수는 「…검토」로 끝난다
    //   (SCREEN-019). 두 이름을 같게 「통일」하면 확정된 사양을 되돌리는 것이다.
    return (
      <MetaSection title="프레임 설명 검토">
        <div data-testid="frame-description-readonly">
          {/* BE 원본값을 그대로 읽는다(편집 폼 상태가 아니다) — 읽기 전용에는 편집이 없다.
              ⚠ 구 라벨은 「설명」이었다 — 구역 제목이 이미 「프레임 설명 검토」라 그 안에서
                「설명」만 적으면 무엇의 설명인지가 라벨 자체로는 서지 않는다. */}
          <MetaReadonlyField
            label={READONLY_LABEL}
            value={data?.description ?? null}
            help={READONLY_HELP}
            emptyText={NOT_FILLED_TEXT}
          />
        </div>
      </MetaSection>
    );
  }

  return (
    <MetaSection title="프레임 설명">
      <label htmlFor={TEXTAREA_ID} className="sr-only">
        프레임 설명 입력
      </label>
      <Textarea
        id={TEXTAREA_ID}
        value={text}
        onChange={(e) => setText(e.target.value)}
        disabled={srcSn === undefined || isLoading || update.isPending}
        maxLength={MAX_LEN}
        aria-label="프레임 설명 입력"
        placeholder="이 프레임의 상황을 자연어로 설명하세요"
        className="min-h-[127px] resize-y text-body-md"
      />
      <MetaCharCount current={text.length} max={MAX_LEN} />

      {update.isError && (
        <p className="text-caption text-danger" role="alert">
          설명 저장에 실패했습니다. 다시 시도해 주세요.
        </p>
      )}

      <Button
        size="sm"
        fullWidth
        onClick={handleSave}
        disabled={!canSave}
        loading={update.isPending}
      >
        저장
      </Button>
    </MetaSection>
  );
}
