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
 * <h3>모양 — 포털 저작도구 화면이 정본</h3>
 * 포털 저장소의 창 · 조건 칸 부품으로 짓는다. 짜임은 포털 화면 스토리북
 * 「워크스페이스 / 저작도구 / 내 업로드 · AI 증강 요청 창」(KLID_Portal `AuthoringUploadsView`)과 같다 —
 * 안내 띠 → 대상 영상 → 조건 다섯 칸(두 줄씩) → 자유 지시문. 거부되면 창은 닫히지 않고 입력이 남으며,
 * 띠가 같은 제목 그대로 위험 띠로 바뀌고 사유가 붙는다.
 *
 * @design SCREEN-033
 * @design API-231
 * @design ADR-061
 */
import { useEffect, useState } from 'react';
import { Textarea } from 'krds-react';

import { Alert, Dropdown, FilterPanel, Modal } from '@portal/components/custom';
import {
  PORTAL_AUGMENT_CONDITION_FIELDS,
  PORTAL_AUGMENT_CONDITION_KEYS,
  PORTAL_AUGMENT_PROMPT_MAX_LENGTH,
  createEmptyPortalAugmentCondition,
  missingPortalAugmentConditionLabels,
  toPortalAugmentGenerationCondition,
  type PortalAugmentConditionDraft,
} from '@/features/portal/augments/conditionOptions';

import type { RequestUploadAugmentBody } from '../api';

/** 폼 안에 **상시** 두는 안내. 서버가 같은 사유로 거부하므로 미리 알린다. */
export const AUGMENT_REQUEST_RULE_NOTICE =
  '다섯 항목을 모두 고르지 않았거나 목록에 없는 값이면 요청이 거부됩니다.';

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

/** 다섯 칸을 두 줄씩 — 한 줄에 하나씩 세우면 창이 세로로 넘친다 */
const CONDITION_ROWS = [
  PORTAL_AUGMENT_CONDITION_KEYS.slice(0, 2),
  PORTAL_AUGMENT_CONDITION_KEYS.slice(2, 4),
  PORTAL_AUGMENT_CONDITION_KEYS.slice(4),
];

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

  // 창을 열 때마다 비운 채로 시작한다 — 앞 대상의 조건이 다른 영상에 묻어 나가지 않게.
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
    onSubmit(trimmed === '' ? { generationCondition } : { generationCondition, prompt: trimmed });
  };

  return (
    <Modal
      open={open}
      onOpenChange={(next: boolean) => {
        if (!next && !submitting) onClose();
      }}
      title="AI 증강 요청"
      sub={{ label: '취소', close: true, disabled: submitting }}
      main={{
        label: '요청',
        /* 다섯 항목을 모두 고르기 전에는 누를 수 없다 — 그럼에도 값이 새면 접수 창구가 같은 사유로
           거부하고 그 사유는 위 안내 띠에 뜬다(이중 방어). */
        disabled: missing.length > 0,
        busy: submitting,
        onClick: handleSubmit,
      }}
    >
      {errorMessage ? (
        <Alert tone="danger" title={AUGMENT_REQUEST_RULE_NOTICE}>
          {errorMessage}
        </Alert>
      ) : (
        <Alert tone="primary" live="none">
          {AUGMENT_REQUEST_RULE_NOTICE}
        </Alert>
      )}
      {/* 창 안이라 제 면을 벗는다(bare) — 창이 이미 면이다 */}
      <FilterPanel surface="bare" layout="stack" aria-label="AI 증강 조건">
        <FilterPanel.Field label="대상 영상">
          {/* 사용자 파일명 — 텍스트 노드(자동 escape). */}
          <p className="klid-authoring-augment-target" data-testid="augment-request-target">
            {targetName}
          </p>
        </FilterPanel.Field>
        {CONDITION_ROWS.map((row) => (
          <FilterPanel.Row key={row[0]}>
            {row.map((key) => {
              const meta = PORTAL_AUGMENT_CONDITION_FIELDS[key];
              return (
                <FilterPanel.Field key={key} label={meta.label} hint="필수">
                  <Dropdown
                    size="small"
                    aria-label={meta.label}
                    placeholder="선택해 주세요"
                    value={condition[key]}
                    onChange={(v: string) => setCondition((prev) => ({ ...prev, [key]: v }))}
                    /* 보이는 것은 우리말, 전송되는 것은 코드다. */
                    options={(meta.codes as readonly string[]).map((code) => ({
                      value: code,
                      label: (meta.codeLabel as Record<string, string>)[code],
                    }))}
                  />
                </FilterPanel.Field>
              );
            })}
          </FilterPanel.Row>
        ))}
        <FilterPanel.Field
          label="자유 지시문"
          desc="(비워 두어도 요청할 수 있으며 생성 조건 다섯 항목을 대신하지 않습니다.)"
        >
          <Textarea
            aria-label="자유 지시문"
            value={prompt}
            onChange={setPrompt}
            maxLength={PORTAL_AUGMENT_PROMPT_MAX_LENGTH}
            showCount
            countTotal={PORTAL_AUGMENT_PROMPT_MAX_LENGTH}
            placeholder="원본 시점과 구조를 유지한 채 바꾸고 싶은 점을 적어 주세요"
          />
        </FilterPanel.Field>
      </FilterPanel>
    </Modal>
  );
}
