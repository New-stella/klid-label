import { useRef, useState, useCallback, DragEvent, ChangeEvent } from 'react';
import { Upload, CheckCircle } from 'lucide-react';
import { api } from '../../api/client';
import { useToast } from '../common/Toast';
import { ProgressBar } from '../ui/ProgressBar';

interface UploadDropzoneProps {
  onUploadComplete?: () => void;
}

export function UploadDropzone({ onUploadComplete }: UploadDropzoneProps) {
  const { showToast } = useToast();
  const inputRef = useRef<HTMLInputElement>(null);

  const [isDragging, setIsDragging] = useState(false);
  const [uploadingFile, setUploadingFile] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [done, setDone] = useState(false);

  const simulateUpload = useCallback(
    async (fileName: string) => {
      setUploadingFile(fileName);
      setProgress(0);
      setDone(false);

      // Animate progress 0→100 over 2 seconds (100ms intervals)
      const intervalMs = 100;
      const totalMs = 2000;
      const steps = totalMs / intervalMs;
      let step = 0;

      await new Promise<void>((resolve) => {
        const timer = setInterval(() => {
          step++;
          const nextProgress = Math.min(Math.round((step / steps) * 100), 99);
          setProgress(nextProgress);
          if (step >= steps) {
            clearInterval(timer);
            resolve();
          }
        }, intervalMs);
      });

      // Call mock API (no actual file transfer)
      try {
        await api.post('/portal/upload', { fileName });
        setProgress(100);
        setDone(true);
        showToast(`업로드 완료: ${fileName}`, 'success');
        onUploadComplete?.();
      } catch {
        showToast('업로드 실패', 'error');
      } finally {
        setTimeout(() => {
          setUploadingFile(null);
          setProgress(0);
          setDone(false);
        }, 2000);
      }
    },
    [showToast, onUploadComplete],
  );

  const handleFiles = useCallback(
    (files: FileList | null) => {
      if (!files || files.length === 0) return;
      const file = files[0];
      simulateUpload(file.name);
    },
    [simulateUpload],
  );

  const handleDragOver = useCallback((e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragging(true);
  }, []);

  const handleDragLeave = useCallback((e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragging(false);
  }, []);

  const handleDrop = useCallback(
    (e: DragEvent<HTMLDivElement>) => {
      e.preventDefault();
      setIsDragging(false);
      handleFiles(e.dataTransfer.files);
    },
    [handleFiles],
  );

  const handleInputChange = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      handleFiles(e.target.files);
      // Reset input so same file can be re-selected
      e.target.value = '';
    },
    [handleFiles],
  );

  const handleClick = useCallback(() => {
    if (!uploadingFile) {
      inputRef.current?.click();
    }
  }, [uploadingFile]);

  const isUploading = uploadingFile !== null;

  return (
    <div className="w-full">
      <div
        role="button"
        tabIndex={0}
        aria-label="파일 업로드 영역. 클릭하거나 파일을 드래그하세요"
        onClick={handleClick}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') handleClick();
        }}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        className={[
          'relative w-full min-h-[120px] rounded-xl border-2 border-dashed',
          'flex flex-col items-center justify-center gap-2',
          'transition-all duration-200 select-none',
          isUploading
            ? 'cursor-not-allowed'
            : 'cursor-pointer hover:border-orange-400 hover:bg-orange-50',
          isDragging
            ? 'border-orange-500 bg-orange-50'
            : 'border-gray-300 bg-gray-50',
        ].join(' ')}
      >
        <input
          ref={inputRef}
          type="file"
          accept="video/*,image/*"
          multiple
          className="hidden"
          onChange={handleInputChange}
        />

        {isUploading ? (
          <div className="w-full px-6 py-2 flex flex-col items-center gap-3">
            {done ? (
              <CheckCircle size={28} className="text-green-500" />
            ) : (
              <Upload size={28} className="text-orange-400 animate-pulse" />
            )}
            <p className="text-sm font-medium text-gray-700 truncate max-w-full px-2">
              {uploadingFile}
            </p>
            <div className="w-full">
              <ProgressBar value={progress} tone="primary" size="md" showLabel />
            </div>
          </div>
        ) : (
          <>
            <Upload
              size={32}
              className={isDragging ? 'text-orange-500' : 'text-gray-400'}
            />
            <div className="text-center">
              <p className={['text-sm font-medium', isDragging ? 'text-orange-600' : 'text-gray-600'].join(' ')}>
                {isDragging ? '여기에 놓으세요' : '클릭하거나 파일을 드래그하세요'}
              </p>
              <p className="text-xs text-gray-400 mt-1">
                지원 형식: MP4, AVI, JPG, PNG / 최대 500MB
              </p>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

export default UploadDropzone;
