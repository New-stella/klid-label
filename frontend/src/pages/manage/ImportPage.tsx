import { useState } from 'react';

import { Field, FieldLabel } from '@/components/common/Field';
import { PageHeader } from '@/components/common/PageHeader';
import { RadioGroup } from '@/components/common/RadioGroup';
import { ConfirmedMappingSection } from '@/features/import/components/ConfirmedMappingSection';
import { ImportExecuteSection } from '@/features/import/components/ImportExecuteSection';
import { ImportHistorySection } from '@/features/import/components/ImportHistorySection';
import { ImportPreviewPanel } from '@/features/import/components/ImportPreviewPanel';
import {
  ImportScanForm,
  SOURCE_DEIDENTIFIED,
  SOURCE_ORIGINAL,
} from '@/features/import/components/ImportScanForm';
import { MarkingImportSection } from '@/features/import/components/MarkingImportSection';
import { UnmappedCategorySection } from '@/features/import/components/UnmappedCategorySection';
import {
  DEFAULT_IMPORT_KIND,
  ImportKind,
  IMPORT_KIND_LABEL,
} from '@/features/import/importKind';
import { useCreateImportMappings } from '@/features/import/hooks/useImportMappings';
import { useCreateImport, useImportScan } from '@/features/import/hooks/useImportScan';
import type { ImportCreateResult, ImportScanResult } from '@/features/import/types';
import { useUiStore } from '@/stores/useUiStore';

/** 서버 오류에서 사용자에게 보여줄 문구를 꺼낸다 — 서버 메시지를 그대로 쓴다. */
function messageOf(e: unknown, fallback: string): string {
  return e instanceof Error && e.message ? e.message : fallback;
}

/**
 * SCREEN-039 산출물 가져오기 (ADMIN 전용, `/admin/imports`).
 *
 * ⚠ **구 서술 폐기(2026-08-28)** — *"REVIEWER 전용, `/manage/imports`"*. 서버가 적재 실행
 * (`POST /v1/imports`)과 분류 대응 저장·삭제(`/v1/import-mappings`)를 **관리자 전용**으로 좁히면서
 * 화면도 관리자 소속으로 옮겼다. 구 상태에서는 검수자가 이 화면을 열어 폴더 탐색·검사까지 정상
 * 진행한 뒤 **마지막 단계에서만 403** 을 받았고, 이 화면에는 역할 참조가 한 줄도 없어 버튼 비활성화도
 * 사유 안내도 없었다. 되돌리면 그 상태로 돌아간다.
 *
 * ★이 화면 자신은 여전히 역할을 읽지 않는다 — 인가는 라우트 가드(`AdminRoute`)와 서버가 소유한다.
 * 화면 안에서 역할을 다시 판정하지 말 것(판정이 두 곳으로 갈린다).
 *
 * 경로 입력 → 미리보기 확인 → 분류 대응 확정 → 적재의 흐름을 한 화면에서 순서대로 밟고, 아래에
 * 확정된 대응과 지금까지 가져온 내역을 함께 둔다.
 *
 * ★적재 가능 여부의 판정은 검사 응답의 `importable` 값 하나가 소유한다. 이 화면은 알림 개수를
 * 세거나 대응 미확정 건수를 따로 계산해 판정하지 않는다 — 생산자가 보장하는 조건을 소비자가 다시
 * 유도하면 드리프트가 난다.
 *
 * 대응을 확정하면 그 분류는 더 이상 처음 보는 분류가 아니므로 **검사를 다시 돌려야** 적재 가능
 * 여부가 갱신된다. 그래서 확정 직후 검사 결과를 비우고 다시 검사하도록 안내한다 — 낡은 검사
 * 결과를 그대로 두면 화면이 이미 해결된 차단 사유를 계속 보여준다.
 *
 * 보안: REVIEWER 검증은 라우터 가드 + BE `@PreAuthorize` 이중이며 1차 원천은 서버다.
 * 사용자가 입력한 경로의 허용 저장소 범위 판정도 서버가 소유한다.
 *
 * <h3>산출물 종류 — 화면 맨 위에서 갈래를 고른다</h3>
 * <p>받는 산출물은 두 종류다. <b>라벨링 완료</b>는 이미 라벨이 끝난 산출물을 받아 검수 대기로
 * 보내고(API-205·API-206), <b>이벤트 마킹</b>은 마킹만 끝난 영상 묶음을 받아 비식별부터 앞
 * 단계를 전부 밟는다(API-216·API-217·API-218). 두 갈래는 방향이 반대라 계약을 합치지 않으며
 * <b>화면에서만</b> 갈래를 고른다(ADR-053). 기본값은 라벨링 완료이므로 기존 사용자에게는
 * 아무것도 달라지지 않는다.
 *
 * <p>★<b>가져온 내역은 산출물 종류 바깥에 두어 항상 보인다.</b> 두 갈래 모두의 결과가 남는
 * 자리라, 갈래를 바꿨다고 방금 가져온 내역이 사라지면 안 된다(SCREEN-039).
 *
 * @design SCREEN-039
 * @design ADR-053
 * @design API-205 API-206 API-207 API-208 API-209 API-210 API-211 API-215
 * @design API-216 API-217 API-218
 */
export function ImportPage() {
  const pushToast = useUiStore((s) => s.pushToast);

  /**
   * 산출물 종류 — 화면 맨 위에서 고르는 갈래. 기본값은 라벨링 완료다.
   *
   * ★두 갈래는 **상태를 나눠 갖는다**. 아래 라벨링 완료 갈래의 입력·검사 결과와 이벤트 마킹
   * 갈래의 것이 서로 섞이지 않아야 하므로, 마킹 갈래의 상태는 그 구획이 스스로 들고 있다.
   */
  const [kind, setKind] = useState<ImportKind>(DEFAULT_IMPORT_KIND);

  const [folderPath, setFolderPath] = useState('');
  const [videoPath, setVideoPath] = useState('');
  const [source, setSource] = useState<string>(SOURCE_ORIGINAL);

  const [scan, setScan] = useState<ImportScanResult | null>(null);
  /** 검사 회차 — 검사할 때마다 늘어난다. 검사 결과에 딸린 하위 구획의 선택 상태를 초기화한다. */
  const [scanToken, setScanToken] = useState(0);
  const [acknowledged, setAcknowledged] = useState(false);
  const [importResult, setImportResult] = useState<ImportCreateResult | null>(null);
  const [importError, setImportError] = useState<string | null>(null);

  const deidentified = source === SOURCE_DEIDENTIFIED;

  const { mutate: runScan, isPending: scanning } = useImportScan({
    onSuccess: (result) => {
      setScan(result);
      setScanToken((n) => n + 1);
      setAcknowledged(false);
      setImportResult(null);
      setImportError(null);
    },
    onError: (e) => {
      setScan(null);
      pushToast({ variant: 'error', message: messageOf(e, '산출물을 검사하지 못했습니다.') });
    },
  });

  const { mutate: runImport, isPending: importing } = useCreateImport({
    onSuccess: (result) => {
      setImportError(null);
      setImportResult(result);
      pushToast({ variant: 'success', message: '산출물을 가져왔습니다.' });
    },
    onError: (e) => {
      setImportResult(null);
      setImportError(messageOf(e, '적재하지 못했습니다.'));
    },
  });

  const { mutate: confirmMappings, isPending: savingMappings } = useCreateImportMappings({
    onSuccess: (result) => {
      // 대응이 달라지면 앞서 받은 검사 결과의 차단 사유가 낡는다. 결과를 비워 다시 검사하게 한다.
      setScan(null);
      setImportResult(null);
      setImportError(null);
      pushToast({
        variant: 'success',
        message: `대응 ${result.created + result.updated}건을 확정했습니다. 다시 검사해 주세요.`,
      });
    },
    onError: (e) => pushToast({ variant: 'error', message: messageOf(e, '대응을 확정하지 못했습니다.') }),
  });

  return (
    <section className="flex flex-col gap-4" data-testid="import-page">
      <PageHeader
        title="산출물 가져오기"
        description="외부에서 받은 산출물 폴더를 검사하고 저작도구로 가져옵니다. 가져온 영상은 곧바로 검수 대기가 됩니다."
      />

      <Field className="gap-1.5 rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
        <FieldLabel className="text-label font-semibold text-gray-900">산출물 종류</FieldLabel>
        <RadioGroup
          name="import-kind"
          value={kind}
          onChange={(v) => setKind(v as ImportKind)}
          options={[
            { value: ImportKind.LABELED, label: IMPORT_KIND_LABEL[ImportKind.LABELED] },
            { value: ImportKind.MARKING, label: IMPORT_KIND_LABEL[ImportKind.MARKING] },
          ]}
        />
        <p className="text-caption text-gray-600">
          라벨링 완료는 이미 라벨이 끝난 산출물을 받아 검수 대기로 보내고, 이벤트 마킹은 마킹만
          끝난 영상 묶음을 받아 비식별부터 앞 단계를 전부 밟습니다.
        </p>
      </Field>

      {kind === ImportKind.MARKING && <MarkingImportSection />}

      {kind === ImportKind.LABELED && (
        <>
      <ImportScanForm
        folderPath={folderPath}
        videoPath={videoPath}
        source={source}
        scanning={scanning}
        onFolderPathChange={setFolderPath}
        onVideoPathChange={setVideoPath}
        onSourceChange={setSource}
        onScan={() =>
          runScan({
            folderPath: folderPath.trim(),
            ...(videoPath.trim() ? { videoPath: videoPath.trim() } : {}),
          })
        }
      />

      {scan && (
        <>
          <ImportPreviewPanel result={scan} />

          {/* 검사 회차가 바뀌면 선택 상태를 초기화한다(앞 회차의 선택이 남으면 다른 분류에 붙는다). */}
          <UnmappedCategorySection
            key={scanToken}
            categories={scan.unmappedCategories}
            saving={savingMappings}
            onConfirm={(items) => confirmMappings({ items })}
          />

          <ImportExecuteSection
            scan={scan}
            deidentified={deidentified}
            hasVideoPath={videoPath.trim().length > 0}
            acknowledged={acknowledged}
            importing={importing}
            result={importResult}
            errorMessage={importError}
            onAcknowledgedChange={setAcknowledged}
            onImport={() =>
              runImport({
                folderPath: folderPath.trim(),
                ...(videoPath.trim() ? { videoPath: videoPath.trim() } : {}),
                deidentified,
                acknowledgedWarnings: scan.warnings.map((w) => w.code),
              })
            }
          />
        </>
      )}

          {/* 분류 대응은 라벨링 완료 갈래에만 있는 축이다 — 마킹 문서에는 분류가 없다. */}
          <ConfirmedMappingSection />
        </>
      )}

      {/* ★두 갈래 모두의 결과가 남는 자리라 산출물 종류 **바깥**에 두어 항상 보인다 — 갈래를
          바꿨다고 방금 가져온 내역이 사라지면 안 된다(SCREEN-039). */}
      <ImportHistorySection />
    </section>
  );
}
