import { useState } from 'react';

import { PageHeader } from '@/components/common/PageHeader';
import { ConfirmedMappingSection } from '@/features/import/components/ConfirmedMappingSection';
import { ImportExecuteSection } from '@/features/import/components/ImportExecuteSection';
import { ImportHistorySection } from '@/features/import/components/ImportHistorySection';
import { ImportPreviewPanel } from '@/features/import/components/ImportPreviewPanel';
import {
  ImportScanForm,
  SOURCE_DEIDENTIFIED,
  SOURCE_ORIGINAL,
} from '@/features/import/components/ImportScanForm';
import { UnmappedCategorySection } from '@/features/import/components/UnmappedCategorySection';
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
 * @design SCREEN-039
 * @design API-205 API-206 API-207 API-208 API-209 API-210 API-211 API-215
 */
export function ImportPage() {
  const pushToast = useUiStore((s) => s.pushToast);

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

      <ConfirmedMappingSection />

      <ImportHistorySection />
    </section>
  );
}
