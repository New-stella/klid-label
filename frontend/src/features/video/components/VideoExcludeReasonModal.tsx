// 영상 제외 사유 입력 팝업. [@design SCREEN-008] [@design API-260] [@design ADR-069]
//
// 되돌릴 수 있는 작업이지만 **그 영상이 화면 목록에서 사라지는** 작업이라 확인 단계를 둔다.
//
// ★사유는 **필수**다 — 무엇이 왜 보이지 않게 됐는지가 남아 있어야 나중에 되돌릴지 판단할 근거가
//   생긴다. 비우거나 공백만이면 확인 버튼을 누를 수 없다(서버도 같은 제약을 건다).
//   ⚠ 판정은 `trim().length > 0` 까지만 한다 — 보이지 않는 문자 전수 판정을 화면이 흉내내면
//   서버(단일 진실원)와 두 판정이 갈려, 서버는 받는 값을 화면이 막거나 그 반대가 된다.
//
// ★★**길이 초과 오류 안내를 두지 않는다.** 서버는 초과분을 물리치지 않고 **잘라서 저장**하는
//   백스톱이라 길이 초과가 제외의 실패 사유가 아니다 — 없는 실패를 예고하면 사용자가 되지 않는
//   걱정을 한다. 대신 **상한을 실제로 강제하는 층이 이 화면**이라 입력 칸이 그 길이를 넘겨 쓰지
//   못하게 막는다(그래야 사용자가 쓴 글이 모르는 사이 잘려 저장되지 않는다).
//   ⚠ 같은 파일 계열의 건너뛰기 사유 팝업은 **서버가 거부**하는 축이라 문구가 다르다 —
//   그쪽 안내("요청 전체가 거부됩니다")를 베껴 오지 말 것.
//
// ★상한 숫자를 이 파일에 적지 않는다 — 창구 계약값(`EXCLUSION_REASON_MAX`)을 가리킨다.

import { useEffect, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Field, FieldDescription, FieldLabel } from '@/components/common/Field';
import { Modal } from '@/components/common/Modal';
import { Textarea } from '@/components/common/Textarea';

import { EXCLUSION_REASON_MAX } from '../types';

export interface VideoExcludeReasonModalProps {
  open: boolean;
  /** 대상 영상 이름 — 무엇을 감추는지 먼저 보인다. */
  videoName: string;
  loading?: boolean;
  onClose(): void;
  onConfirm(reason: string): void;
}

export function VideoExcludeReasonModal({
  open,
  videoName,
  loading,
  onClose,
  onConfirm,
}: VideoExcludeReasonModalProps) {
  const [reason, setReason] = useState('');

  // 닫혀도 마운트된 채 남으므로 다시 열릴 때 입력을 비운다 — 앞 영상의 사유가 다음 영상에
  // 그대로 실리면 이력에 사실이 아닌 말이 남는다.
  useEffect(() => {
    if (open) setReason('');
  }, [open, videoName]);

  const canSubmit = reason.trim().length > 0 && !loading;

  const handleClose = () => {
    if (loading) return;
    onClose();
  };

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="영상 제외"
      size="md"
      footer={
        <>
          <Button variant="secondary" size="sm" onClick={handleClose} disabled={loading}>
            취소
          </Button>
          <Button
            variant="danger"
            size="sm"
            onClick={() => onConfirm(reason.trim())}
            disabled={!canSubmit}
            loading={loading}
            data-testid="video-exclude-confirm"
          >
            제외
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4" data-testid="video-exclude-reason-modal">
        <div className="rounded-lg bg-gray-50 px-4 py-3">
          <p className="text-label font-semibold uppercase tracking-wide text-gray-600">
            대상 영상
          </p>
          <p className="mt-1 text-body-md text-gray-800">{videoName}</p>
        </div>

        <p className="text-body-md text-gray-700">
          이 영상을 목록에서 뺍니다. 행은 지워지지 않으며 언제든 복원할 수 있습니다.
        </p>

        <Field>
          {/* 글자 수는 라벨 **밖**의 형제로 둔다 — `<label>` 안에 넣으면 필드의 접근성 이름이
              타이핑할 때마다 바뀐다(건너뛰기 사유 팝업과 같은 규약). */}
          <div className="flex items-baseline justify-between gap-2">
            <FieldLabel required>제외 사유</FieldLabel>
            <span
              className="text-caption tabular-nums text-gray-600"
              data-testid="video-exclude-reason-count"
            >
              {reason.length} / {EXCLUSION_REASON_MAX}
            </span>
          </div>
          <Textarea
            className="min-h-[120px]"
            aria-required="true"
            maxLength={EXCLUSION_REASON_MAX}
            placeholder="예: 관제 인입 시험 데이터라 작업 대상이 아님"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            disabled={loading}
          />
          {/* Field 컨텍스트가 이 문단을 textarea 의 aria-describedby 로 이어 준다.
              ★개인정보 경고는 여기 둔다 — 입력하는 자리에서 보여야 효력이 있다. */}
          <FieldDescription>
            개인정보를 적지 마세요. 입력한 문구는 이력에 그대로 남습니다.
          </FieldDescription>
        </Field>
      </div>
    </Modal>
  );
}

export default VideoExcludeReasonModal;
