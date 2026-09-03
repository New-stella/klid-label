/**
 * 포털 업로드 영상 **증강 요청 폼** — 목록의 「AI 증강 요청」이 여는 화면 안 창.
 *
 * <h3>왜 버튼 하나로 끝나지 않는가</h3>
 * 생성 조건 다섯 항목(시간대 · 계절 · 날씨 · 지형 · 심각도)을 **전부 골라야** 요청을 보낼 수 있다.
 * 하나라도 비면 위탁받는 쪽이 그 자리를 어떤 기본값으로 채울지 이쪽에서 알 수 없어 같은 요청의
 * 결과가 비결정적이 된다. 외부 계약 자체는 최소 한 항목만 요구하므로 **우리가 더 엄격한 쪽이며
 * 의도된 선택**이다.
 *
 * <h3>표시명은 우리말, 전송값은 코드</h3>
 * 값은 닫힌 목록에서만 고르고 직접 적어 넣을 수 없다 — 허용 코드 밖 값을 보낼 수단이 화면에
 * 없다. 라벨↔코드 대응의 단일 정의 지점은 `conditionOptions` 이며 이 컴포넌트는 그 표를 순회할
 * 뿐 목록을 복제하지 않는다.
 *
 * <h3>★두지 않는 입력 — 되살리지 말 것</h3>
 * **이벤트 유형 · 세부 유형 · 증강 종류를 고르는 입력칸을 두지 않는다.** 요청자가 고르지 않고
 * 서버가 중립 값을 고정으로 싣는다. 이 증강은 이미 이벤트가 담긴 프레임을 겨울·야간·우천 등으로
 * 바꾸는 것이라 무엇을 만들지 정할 자리가 없고, 우리 이벤트 체계는 넓은 데 비해 위탁받는 쪽의
 * 허용값은 좁아 대응되지 않는 영상에서는 요청자가 사실과 다른 값을 고를 수밖에 없었다.
 * ⚠ 이 축은 최근에 뒤집혔다. 그리고 BE 는 모르는 필드를 400 이 아니라 **조용히 무시**하므로,
 *   되살려도 아무 신호가 오지 않고 화면에만 걷어냈어야 할 입력이 되살아난다.
 *
 * 표면: 브라우저가 자체로 띄우는 창을 쓰지 않고 본문 위에 뜨는 별도 표면(공용 `Modal`)으로 둔다.
 *
 * 접근성(WCAG 2.1 AA): 모든 입력에 `label htmlFor` 연결, 필수는 `aria-required`, 폼 안내는
 * 상시 배너로 두고 서버가 돌려보낸 사유도 같은 자리에 띄운다.
 *
 * @design SCREEN-033
 * @design API-231
 * @design ADR-061
 */
import { useEffect, useState } from 'react';

import { Alert } from '@/components/common/Alert';
import { Button } from '@/components/common/Button';
import { FieldCounter } from '@/components/common/FieldCounter';
import { Modal } from '@/components/common/Modal';
import {
  PORTAL_AUGMENT_CONDITION_FIELDS,
  PORTAL_AUGMENT_CONDITION_KEYS,
  PORTAL_AUGMENT_PROMPT_MAX_LENGTH,
  createEmptyPortalAugmentCondition,
  missingPortalAugmentConditionLabels,
  toPortalAugmentGenerationCondition,
  type PortalAugmentConditionDraft,
} from '@/features/portal/augments/conditionOptions';
import { KRDS_FOCUS } from '@/lib/focusRing';

import type { RequestUploadAugmentBody } from '../api';

/** 폼 안에 **상시** 두는 안내. 서버가 같은 사유로 거부하므로 미리 알린다. */
export const AUGMENT_REQUEST_RULE_NOTICE =
  '다섯 항목을 모두 고르지 않았거나 목록에 없는 값이면 요청이 거부됩니다.';

const SELECT_CLASS = `rounded-md border border-gray-300 bg-white px-2.5 py-1.5 text-body-md ${KRDS_FOCUS}`;

export interface AugmentRequestModalProps {
  open: boolean;
  /** 요청 대상 영상 — 이 창에서 대상을 바꾸지 않는다. */
  targetName: string;
  /** 접수 창구가 돌려보낸 사유(있으면 안내 자리에 함께 띄운다). */
  errorMessage?: string | null;
  submitting?: boolean;
  onClose(): void;
  onSubmit(body: RequestUploadAugmentBody): void;
}

export function AugmentRequestModal({
  open,
  targetName,
  errorMessage,
  submitting,
  onClose,
  onSubmit,
}: AugmentRequestModalProps) {
  const [condition, setCondition] = useState<PortalAugmentConditionDraft>(
    createEmptyPortalAugmentCondition,
  );
  const [prompt, setPrompt] = useState('');

  // 창을 열 때마다 빈 폼에서 시작한다 — 취소는 "입력한 값을 버린다" 이고, 남겨 두면 앞선 요청의
  // 조건이 다음 대상 영상에 조용히 딸려 간다.
  useEffect(() => {
    if (!open) return;
    setCondition(createEmptyPortalAugmentCondition());
    setPrompt('');
  }, [open]);

  const missing = missingPortalAugmentConditionLabels(condition);
  const generationCondition = toPortalAugmentGenerationCondition(condition);

  const handleSubmit = () => {
    if (generationCondition === null || submitting) return;
    const trimmed = prompt.trim();
    // 비었으면 키 자체를 싣지 않는다 — 선택 항목에 빈 문자열을 지어 보내지 않는다.
    onSubmit(trimmed === '' ? { generationCondition } : { generationCondition, prompt: trimmed });
  };

  return (
    <Modal open={open} onClose={onClose} title="AI 증강 요청" size="md">
      <div className="flex flex-col gap-3">
        {/* 대상 영상 — 목록에서 요청을 누른 그 자산이며 여기서 바꾸지 않는다. */}
        <div className="flex flex-col gap-0.5">
          <span className="text-sub text-gray-500">대상 영상</span>
          {/* 사용자 파일명 — 텍스트 노드(자동 escape). */}
          <span data-testid="augment-request-target" className="text-body text-gray-800">
            {targetName}
          </span>
        </div>

        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          {PORTAL_AUGMENT_CONDITION_KEYS.map((key) => {
            const meta = PORTAL_AUGMENT_CONDITION_FIELDS[key];
            const selectId = `portal-augment-${key}`;
            return (
              <div key={key} className="flex flex-col gap-1">
                <label htmlFor={selectId} className="text-label font-medium text-gray-600">
                  {meta.label}
                  <span className="ml-0.5 text-danger" aria-hidden>
                    *
                  </span>
                  <span className="sr-only">(필수)</span>
                </label>
                <select
                  id={selectId}
                  value={condition[key]}
                  onChange={(e) =>
                    setCondition((prev) => ({ ...prev, [key]: e.target.value }))
                  }
                  required
                  aria-required="true"
                  className={SELECT_CLASS}
                >
                  <option value="">선택하세요</option>
                  {(meta.codes as readonly string[]).map((code) => (
                    <option key={code} value={code}>
                      {/* 보이는 것은 우리말, 전송되는 것은 코드다. */}
                      {(meta.codeLabel as Record<string, string>)[code]}
                    </option>
                  ))}
                </select>
              </div>
            );
          })}
        </div>

        <div className="flex flex-col gap-1">
          <div className="flex items-baseline justify-between gap-2">
            <label htmlFor="portal-augment-prompt" className="text-label font-medium text-gray-600">
              자유 지시문 (선택)
            </label>
            <FieldCounter current={prompt.length} max={PORTAL_AUGMENT_PROMPT_MAX_LENGTH} />
          </div>
          <textarea
            id="portal-augment-prompt"
            rows={3}
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            maxLength={PORTAL_AUGMENT_PROMPT_MAX_LENGTH}
            placeholder="원본 시점과 구조를 유지한 채 바꾸고 싶은 점을 적는다"
            className={`w-full rounded-md border border-gray-300 bg-white px-2.5 py-1.5 text-body-md ${KRDS_FOCUS}`}
          />
          <p className="text-caption text-gray-400">
            비워 두어도 요청할 수 있으며 생성 조건 다섯 항목을 대신하지 않습니다.
          </p>
        </div>

        {/* 상시 안내 + 서버가 돌려보낸 사유를 같은 자리에 둔다. */}
        <Alert
          variant={errorMessage ? 'error' : 'info'}
          title={AUGMENT_REQUEST_RULE_NOTICE}
          data-testid="augment-request-notice"
        >
          {errorMessage ?? undefined}
        </Alert>

        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose} disabled={submitting}>
            취소
          </Button>
          <Button
            variant="primary"
            onClick={handleSubmit}
            /* 다섯 항목을 모두 고르기 전에는 누를 수 없다 — 그럼에도 값이 새면 접수 창구가
               같은 사유로 거부하고 그 사유는 위 안내 자리에 뜬다(이중 방어). */
            disabled={missing.length > 0 || submitting}
            loading={submitting}
          >
            요청
          </Button>
        </div>
      </div>
    </Modal>
  );
}
