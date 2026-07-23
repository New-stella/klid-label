package kr.co.cudo.authoring.dataset.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dataset.export.json.NiaAnnotationDoc;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.FrameContext;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.VideoExportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 학습데이터 파일 Writer(Phase 3) — 한 영상·한 {@link ExportKind}·한 버전에 대해
 * 프레임 이미지 복사 + 동일 이름 JSON 을 산출 디렉토리에 기록한다.
 *
 * <p>Phase 1({@link DatasetExportPathResolver}, CWE-22 가드) + Phase 2({@link NiaJsonBuilder}) 소비.
 * DB 조회·검수 승인 연동은 하지 않는다 — 입력은 이미 로드된 엔티티/컨텍스트(Phase 4 오케스트레이션 대상).
 *
 * <h3>동작</h3>
 * <ul>
 *   <li>{@code dir = pathResolver.resolve(rawSn, kind, version)} → {@code Files.createDirectories(dir)}.</li>
 *   <li>프레임마다 원천 이미지가 존재하면 {@code frame-{frameNo}.jpg} 복사 + {@code frame-{frameNo}.json} 기록(writtenCnt).</li>
 *   <li>원천 이미지 부재/이탈 프레임은 건너뛰고 집계(skippedCnt) — 부분성공.</li>
 * </ul>
 *
 * <h3>보안/견고성</h3>
 * <ul>
 *   <li><b>Path Traversal(CWE-22)</b>: 모든 쓰기 경로는 Phase 1 리졸버 산출 {@code dir} 하위로만 조합(직접 문자열 결합 없음).</li>
 *   <li><b>원자적 쓰기</b>: JSON 은 {@code .tmp} 로 기록 후 {@code Files.move}(ATOMIC_MOVE 시도→실패 시 REPLACE_EXISTING)로 교체(부분쓰기 방지).</li>
 *   <li><b>로그(CWE-117/209)</b>: 경로 원문/PII 미노출 — rawSn·frameNo·건수만 로깅.</li>
 * </ul>
 */
@Component
public class DatasetExportWriter {

    private static final Logger log = LoggerFactory.getLogger(DatasetExportWriter.class);

    private final DatasetExportPathResolver pathResolver;
    private final FrameSource frameSource;
    private final NiaJsonBuilder niaJsonBuilder;
    private final ObjectMapper objectMapper;

    public DatasetExportWriter(DatasetExportPathResolver pathResolver,
                               FrameSource frameSource,
                               NiaJsonBuilder niaJsonBuilder,
                               ObjectMapper objectMapper) {
        this.pathResolver = pathResolver;
        this.frameSource = frameSource;
        this.niaJsonBuilder = niaJsonBuilder;
        this.objectMapper = objectMapper;
    }

    /**
     * 한 영상·한 kind·한 버전의 프레임 이미지+JSON 페어를 산출 디렉토리에 기록한다.
     *
     * @param rawSn   영상 PK(=RAW_SN)
     * @param kind    산출 종류(원본/비식별)
     * @param version 산출 버전(≥1)
     * @param ctx     rawSn 단위 공통 컨텍스트(Phase 2 {@link NiaJsonBuilder#prepareContext} 산출)
     * @param frames  프레임 단위 입력(프레임 엔티티 + 라벨)
     * @return 산출 집계({@link ExportResult})
     * @throws CustomException 입력 검증 실패(INVALID_INPUT), 경로 이탈(FORBIDDEN), 파일 IO 실패(INTERNAL_ERROR)
     */
    public ExportResult write(long rawSn, ExportKind kind, int version,
                              VideoExportContext ctx, List<FrameContext> frames) {
        if (ctx == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "영상 컨텍스트가 null 입니다.");
        }

        // CWE-22: 산출 디렉토리는 Phase 1 리졸버(base 하위 검증)로만 계산.
        Path dir = pathResolver.resolve(rawSn, kind, version);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("[DatasetExport] createDirectories failed rawSn={} kind={} version={}",
                    rawSn, kind, version);
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "산출 디렉토리 생성에 실패했습니다.", e);
        }

        int written = 0;
        int skipped = 0;
        if (frames != null) {
            for (FrameContext frameCtx : frames) {
                if (frameCtx == null || frameCtx.frame() == null || frameCtx.frame().getFrameNo() == null) {
                    skipped++;
                    continue;
                }
                long frameNo = frameCtx.frame().getFrameNo();

                var image = frameSource.resolveImage(rawSn, kind, frameCtx.frame());
                if (image.isEmpty()) {
                    // 원천 이미지 부재 — 부분성공 유지, 경로 원문 미노출.
                    log.warn("[DatasetExport] frame image missing skipped rawSn={} kind={} frameNo={}",
                            rawSn, kind, frameNo);
                    skipped++;
                    continue;
                }

                NiaAnnotationDoc doc = niaJsonBuilder.build(ctx, frameCtx, kind);
                writeFrame(dir, frameNo, image.get(), doc, rawSn, kind);
                written++;
            }
        }

        log.info("[DatasetExport] frames written rawSn={} kind={} version={} written={} skipped={}",
                rawSn, kind, version, written, skipped);
        return new ExportResult(kind, version, written, skipped, dir);
    }

    /** 한 프레임의 이미지 복사 + JSON(원자적 교체) 기록. 경로는 모두 {@code dir} 하위. */
    private void writeFrame(Path dir, long frameNo, Path image, NiaAnnotationDoc doc,
                            long rawSn, ExportKind kind) {
        Path imageTarget = dir.resolve("frame-" + frameNo + ".jpg");
        Path jsonTarget = dir.resolve("frame-" + frameNo + ".json");
        Path jsonTmp = dir.resolve("frame-" + frameNo + ".json.tmp");
        try {
            Files.copy(image, imageTarget, StandardCopyOption.REPLACE_EXISTING);
            // 원자적 쓰기: tmp 기록 후 교체(부분쓰기 방지).
            // 산출 JSON 은 사람이 읽는 학습데이터 파일이므로 pretty(들여쓰기)로 저장한다.
            // writerWithDefaultPrettyPrinter() 는 호출 시점에만 파생되는 ObjectWriter 라
            // 주입된 전역 ObjectMapper(REST API 응답 직렬화 공유)에는 영향이 없다.
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonTmp.toFile(), doc);
            moveAtomic(jsonTmp, jsonTarget);
        } catch (IOException e) {
            cleanupQuietly(jsonTmp);
            log.error("[DatasetExport] frame write failed rawSn={} kind={} frameNo={}", rawSn, kind, frameNo);
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "프레임 파일 기록에 실패했습니다.", e);
        }
    }

    /**
     * ATOMIC_MOVE 우선, 파일시스템 미지원 시 REPLACE_EXISTING 로 폴백.
     * <p>catch 분기는 {@link AtomicMoveNotSupportedException} 을 던지는 크로스-디바이스/특정 FS 에서만
     * 도달하는 JDK 폴백이라 표준 로컬 파일시스템(테스트 @TempDir 포함) 에서는 재현되지 않는다.
     * 이 한 분기는 커버리지 미커버로 남는 것이 허용된다(Jimfs 등 인메모리 FS 도입은 스코프 과함).
     */
    private static void moveAtomic(Path src, Path dest) throws IOException {
        try {
            Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cleanupQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // best-effort cleanup — 상위 예외를 가리지 않는다.
        }
    }
}
