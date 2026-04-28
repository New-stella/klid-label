import { useState } from 'react';
import { Download, RefreshCw, FileJson, Eye } from 'lucide-react';
import { useFetch, useMutation } from '../../api/queries';
import { api } from '../../api/client';
import type { Page, VideoDto } from '../../api/types';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { Pagination } from '../../components/ui/Pagination';
import { Skeleton } from '../../components/ui/Skeleton';
import { useToast } from '../../components/common/Toast';

type ExportFormat = 'COCO' | 'YOLO' | 'CVAT' | 'PASCAL_VOC';

interface ExportRequestBody {
  format: ExportFormat;
  videoIds: string[];
  saveNas: boolean;
  registerMart: boolean;
  options: {
    includeLabels: boolean;
    includeImages: boolean;
    nasPath: string;
    martName: string;
    martVersion: string;
    martDescription: string;
  };
}

interface ExportJobResult {
  jobId: string;
  format: string;
  videoCount: number;
  previewUrl: string;
}

const FORMAT_META: Record<
  ExportFormat,
  { label: string; description: string; extension: string }
> = {
  COCO: {
    label: 'COCO',
    description: 'JSON 포맷 — 바운딩박스·세그멘테이션 모두 지원. 객체 탐지 표준.',
    extension: '.json',
  },
  YOLO: {
    label: 'YOLO',
    description: 'TXT 포맷 — 정규화된 좌표. YOLO v5/v8 학습에 최적화.',
    extension: '.txt',
  },
  CVAT: {
    label: 'CVAT',
    description: 'XML 포맷 — CVAT 호환 어노테이션. 폴리곤·트랙 포함.',
    extension: '.xml',
  },
  PASCAL_VOC: {
    label: 'Pascal VOC',
    description: 'XML 포맷 — 이미지별 개별 XML. 전통 객체 탐지 포맷.',
    extension: '.xml',
  },
};

const FORMAT_PREVIEW: Record<ExportFormat, string> = {
  COCO: `{
  "info": { "version": "1.0", "description": "KLID Export" },
  "categories": [
    { "id": 1, "name": "person" },
    { "id": 2, "name": "vehicle" }
  ],
  "images": [
    { "id": 1, "file_name": "frame_0001.jpg", "width": 1920, "height": 1080 }
  ],
  "annotations": [
    { "id": 1, "image_id": 1, "category_id": 1,
      "bbox": [120, 80, 60, 140], "area": 8400, "iscrowd": 0 }
  ]
}`,
  YOLO: `# frame_0001.jpg
# format: class_id cx cy w h (normalized)
0 0.3125 0.2963 0.0313 0.1296
1 0.7500 0.5556 0.1563 0.2963

# frame_0002.jpg
0 0.2083 0.3704 0.0417 0.1481`,
  CVAT: `<?xml version="1.0" encoding="utf-8"?>
<annotations>
  <version>1.1</version>
  <meta>
    <task><name>KLID Export</name></task>
  </meta>
  <image id="1" name="frame_0001.jpg" width="1920" height="1080">
    <box label="person" occluded="0"
         xtl="120" ytl="80" xbr="180" ybr="220" />
  </image>
</annotations>`,
  PASCAL_VOC: `<?xml version="1.0"?>
<annotation>
  <filename>frame_0001.jpg</filename>
  <size><width>1920</width><height>1080</height><depth>3</depth></size>
  <object>
    <name>person</name>
    <difficult>0</difficult>
    <bndbox>
      <xmin>120</xmin><ymin>80</ymin>
      <xmax>180</xmax><ymax>220</ymax>
    </bndbox>
  </object>
</annotation>`,
};

const PAGE_SIZE = 15;

export function ExportPage() {
  const { showToast } = useToast();

  const [format, setFormat] = useState<ExportFormat>('COCO');
  const [page, setPage] = useState(0);
  const [selectedVideoIds, setSelectedVideoIds] = useState<Set<string>>(new Set());
  const [saveNas, setSaveNas] = useState(false);
  const [nasPath, setNasPath] = useState('/mnt/nas/export/');
  const [registerMart, setRegisterMart] = useState(false);
  const [martName, setMartName] = useState('');
  const [martVersion, setMartVersion] = useState('1.0.0');
  const [martDescription, setMartDescription] = useState('');
  const [includeLabels, setIncludeLabels] = useState(true);
  const [includeImages, setIncludeImages] = useState(true);

  const { data, isLoading } = useFetch<Page<VideoDto>>('/videos', {
    status: 'COMPLETED',
    page,
    size: PAGE_SIZE,
  });

  const { mutate, isLoading: isExporting } = useMutation<ExportRequestBody, ExportJobResult>(
    (body) => api.post<ExportJobResult>('/export', body),
  );

  const videos = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  const toggleVideo = (id: string) => {
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const toggleAll = () => {
    if (selectedVideoIds.size === videos.length) {
      setSelectedVideoIds(new Set());
    } else {
      setSelectedVideoIds(new Set(videos.map((v) => v.id)));
    }
  };

  const handleExport = async () => {
    if (selectedVideoIds.size === 0) {
      showToast('대상 영상을 선택해주세요.', 'error');
      return;
    }
    try {
      const result = await mutate({
        format,
        videoIds: Array.from(selectedVideoIds),
        saveNas,
        registerMart,
        options: {
          includeLabels,
          includeImages,
          nasPath,
          martName,
          martVersion,
          martDescription,
        },
      });
      showToast(`내보내기 작업 #${result.jobId} 시작됨`, 'success');
      setSelectedVideoIds(new Set());
    } catch {
      showToast('내보내기 요청 중 오류가 발생했습니다.', 'error');
    }
  };

  return (
    <div className="p-6 space-y-8">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-green-50">
          <Download size={20} className="text-green-600" />
        </div>
        <h1 className="text-xl font-bold text-gray-900">내보내기</h1>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left column: 3-step form */}
        <div className="lg:col-span-2 space-y-6">

          {/* Step 1: Format selection */}
          <section className="bg-white border border-gray-200 rounded-lg p-5 space-y-4">
            <div className="flex items-center gap-2">
              <span className="flex items-center justify-center w-6 h-6 rounded-full bg-primary-600 text-white text-xs font-bold shrink-0">
                1
              </span>
              <h2 className="text-sm font-semibold text-gray-800">형식 선택</h2>
            </div>

            <div className="grid grid-cols-2 gap-3">
              {(Object.keys(FORMAT_META) as ExportFormat[]).map((f) => {
                const meta = FORMAT_META[f];
                const selected = format === f;
                return (
                  <label
                    key={f}
                    className={[
                      'flex items-start gap-3 p-3 rounded-lg border-2 cursor-pointer transition-all',
                      selected
                        ? 'border-primary-500 bg-primary-50'
                        : 'border-gray-200 hover:border-gray-300 bg-white',
                    ].join(' ')}
                  >
                    <input
                      type="radio"
                      name="format"
                      value={f}
                      checked={selected}
                      onChange={() => setFormat(f)}
                      className="mt-0.5 w-4 h-4 text-primary-600 border-gray-300 focus:ring-primary-500"
                    />
                    <div>
                      <p className="text-sm font-semibold text-gray-800">
                        {meta.label}
                        <span className="ml-1 text-xs text-gray-400 font-normal">
                          {meta.extension}
                        </span>
                      </p>
                      <p className="text-xs text-gray-500 mt-0.5">{meta.description}</p>
                    </div>
                  </label>
                );
              })}
            </div>
          </section>

          {/* Step 2: Video selection */}
          <section className="bg-white border border-gray-200 rounded-lg p-5 space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="flex items-center justify-center w-6 h-6 rounded-full bg-primary-600 text-white text-xs font-bold shrink-0">
                  2
                </span>
                <h2 className="text-sm font-semibold text-gray-800">대상 영상</h2>
                {selectedVideoIds.size > 0 && (
                  <Badge tone="success" size="sm">
                    {selectedVideoIds.size}건 선택
                  </Badge>
                )}
              </div>
            </div>

            {isLoading ? (
              <div className="space-y-2">
                {Array.from({ length: 5 }, (_, i) => (
                  <Skeleton key={i} height="2.5rem" />
                ))}
              </div>
            ) : (
              <>
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-200">
                      <th className="text-left py-2 pr-3 w-10">
                        <input
                          type="checkbox"
                          checked={videos.length > 0 && selectedVideoIds.size === videos.length}
                          onChange={toggleAll}
                          className="w-4 h-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                          aria-label="전체 선택"
                        />
                      </th>
                      <th className="text-left py-2 font-medium text-gray-600">영상명</th>
                      <th className="text-left py-2 font-medium text-gray-600">이벤트</th>
                    </tr>
                  </thead>
                  <tbody>
                    {videos.map((video) => {
                      const checked = selectedVideoIds.has(video.id);
                      return (
                        <tr
                          key={video.id}
                          className="border-b border-gray-100 hover:bg-gray-50 cursor-pointer"
                          onClick={() => toggleVideo(video.id)}
                        >
                          <td className="py-2 pr-3">
                            <input
                              type="checkbox"
                              checked={checked}
                              onChange={() => toggleVideo(video.id)}
                              onClick={(e) => e.stopPropagation()}
                              className="w-4 h-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                            />
                          </td>
                          <td className="py-2 font-medium text-gray-800 max-w-48 truncate">
                            {video.cctvName}
                          </td>
                          <td className="py-2 text-gray-500">{video.eventType}</td>
                        </tr>
                      );
                    })}
                    {videos.length === 0 && (
                      <tr>
                        <td colSpan={3} className="py-10 text-center text-gray-400 text-xs">
                          완료된 영상이 없습니다.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>

                {totalPages > 1 && (
                  <Pagination page={page} totalPages={totalPages} onChange={setPage} />
                )}
              </>
            )}
          </section>

          {/* Step 3: Options */}
          <section className="bg-white border border-gray-200 rounded-lg p-5 space-y-5">
            <div className="flex items-center gap-2">
              <span className="flex items-center justify-center w-6 h-6 rounded-full bg-primary-600 text-white text-xs font-bold shrink-0">
                3
              </span>
              <h2 className="text-sm font-semibold text-gray-800">옵션</h2>
            </div>

            {/* Toggle options */}
            <div className="space-y-3">
              <label className="flex items-center gap-3 text-sm cursor-pointer">
                <input
                  type="checkbox"
                  checked={includeLabels}
                  onChange={(e) => setIncludeLabels(e.target.checked)}
                  className="w-4 h-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                <span className="text-gray-700 font-medium">라벨 포함</span>
                <span className="text-xs text-gray-400">어노테이션 파일 포함</span>
              </label>

              <label className="flex items-center gap-3 text-sm cursor-pointer">
                <input
                  type="checkbox"
                  checked={includeImages}
                  onChange={(e) => setIncludeImages(e.target.checked)}
                  className="w-4 h-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                <span className="text-gray-700 font-medium">이미지 포함</span>
                <span className="text-xs text-gray-400">프레임 이미지 파일 포함</span>
              </label>

              {/* NAS */}
              <label className="flex items-center gap-3 text-sm cursor-pointer">
                <input
                  type="checkbox"
                  checked={saveNas}
                  onChange={(e) => setSaveNas(e.target.checked)}
                  className="w-4 h-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                <span className="text-gray-700 font-medium">NAS 저장</span>
              </label>

              {saveNas && (
                <div className="ml-7">
                  <input
                    type="text"
                    value={nasPath}
                    onChange={(e) => setNasPath(e.target.value)}
                    placeholder="/mnt/nas/export/"
                    className="w-full text-xs border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500 font-mono"
                  />
                </div>
              )}

              {/* Mart */}
              <label className="flex items-center gap-3 text-sm cursor-pointer">
                <input
                  type="checkbox"
                  checked={registerMart}
                  onChange={(e) => setRegisterMart(e.target.checked)}
                  className="w-4 h-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                <span className="text-gray-700 font-medium">데이터마트 등록</span>
              </label>

              {registerMart && (
                <div className="ml-7 space-y-2">
                  <input
                    type="text"
                    value={martName}
                    onChange={(e) => setMartName(e.target.value)}
                    placeholder="데이터셋 이름"
                    className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
                  />
                  <input
                    type="text"
                    value={martVersion}
                    onChange={(e) => setMartVersion(e.target.value)}
                    placeholder="버전 (예: 1.0.0)"
                    className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
                  />
                  <textarea
                    value={martDescription}
                    onChange={(e) => setMartDescription(e.target.value)}
                    placeholder="데이터셋 설명"
                    rows={2}
                    className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 resize-none focus:outline-none focus:ring-2 focus:ring-primary-500"
                  />
                </div>
              )}
            </div>
          </section>
        </div>

        {/* Right column: Preview */}
        <div className="space-y-4">
          <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-3 sticky top-6">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 text-sm font-semibold text-gray-700">
                <Eye size={15} />
                <span>미리보기 — {FORMAT_META[format].label}</span>
              </div>
              <Badge tone="neutral" size="sm">{FORMAT_META[format].extension}</Badge>
            </div>

            <div className="flex items-center gap-1.5 text-xs text-gray-400">
              <FileJson size={13} />
              <span>샘플 출력 형식</span>
            </div>

            <pre className="bg-gray-50 rounded-lg p-3 text-xs text-gray-700 overflow-x-auto leading-relaxed max-h-80 overflow-y-auto scrollbar-thin">
              {FORMAT_PREVIEW[format]}
            </pre>

            <Button
              variant="secondary"
              size="sm"
              leftIcon={RefreshCw}
              className="w-full"
              onClick={() =>
                showToast(`${FORMAT_META[format].label} 미리보기 새로고침`, 'info')
              }
            >
              미리보기 새로고침
            </Button>

            <Button
              variant="primary"
              size="md"
              leftIcon={Download}
              loading={isExporting}
              className="w-full"
              onClick={() => { void handleExport(); }}
            >
              내보내기 실행
            </Button>

            <p className="text-xs text-gray-400 text-center">
              선택된 영상 {selectedVideoIds.size}건
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

export default ExportPage;
