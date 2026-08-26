package kr.co.cudo.authoring.video.service.port;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LetterboxTransform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Java2D 기반 프레임 이미지 다운스케일 구현 — Phase 1 (RQ-SFR-06-03 v1.8/1.10).
 *
 * <p>BufferedImage 로 읽어 {@link Graphics2D} BILINEAR 보간으로 리스케일한 뒤 ImageIO 로
 * 저장한다. <b>종횡비를 보존</b>하며({@link LetterboxTransform}) 산출 캔버스는 실제 그려지는 크기와
 * 같아 <b>패딩이 없다</b>(@design ADR-018). 손상 이미지({@link ImageIO#read} null/예외)는 추상 메시지로
 * 변환하고, 출력 파일이 비정상(100바이트 미만)이면 실패로 간주한다. 경로는 로그에서 hash 마스킹한다
 * (CWE-209/PII).
 */
@Slf4j
@Component
public class Java2DImageResizer implements ImageResizer {

    /** 정상 산출로 인정하는 최소 출력 파일 크기(byte). 빈/깨진 출력 방어. */
    private static final long MIN_OUTPUT_BYTES = 100L;

    @Override
    public void resize(Path src, Path dst, int targetW, int targetH) {
        if (targetW <= 0 || targetH <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "타겟 해상도는 양수여야 합니다.");
        }
        BufferedImage source = readImage(src);
        // @design ADR-018 — targetW/targetH 는 고정 캔버스가 아니라 <b>크기 상한</b>이다. 짧은 변을
        //       상한의 짧은 값에 맞추고 긴 변이 상한을 넘을 때만 배율을 낮춘 뒤, 산출 캔버스를 그
        //       실제 크기로 만든다 → 패딩이 생기지 않는다(구 검정 레터박스 폐기).
        //       배율은 라벨 좌표 재계산과 <b>동일한 계산기</b>(LetterboxTransform)를 쓴다.
        LetterboxTransform box = LetterboxTransform.of(source.getWidth(), source.getHeight(), targetW, targetH);
        int outW = box.drawW();
        int outH = box.drawH();
        BufferedImage scaled = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            // 캔버스 전체가 원본 픽셀로 덮인다 — 채움색으로 칠하는 영역이 없다.
            g.drawImage(source, 0, 0, outW, outH, null);
        } finally {
            g.dispose();
        }

        String format = formatOf(dst);
        File dstFile = dst.toFile();
        try {
            // 파생 프레임 출력 디렉터리(raw/deid base 별개)가 없으면 생성 — 없으면 ImageIO.write 가
            // IOException(부모 경로 없음) → finalize 실패 → 예약행 삭제(이력 소멸). 멱등(있으면 no-op).
            if (dst.getParent() != null) {
                Files.createDirectories(dst.getParent());
            }
            boolean written = ImageIO.write(scaled, format, dstFile);
            if (!written) {
                throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 다운스케일에 실패했습니다.");
            }
            if (Files.size(dst) < MIN_OUTPUT_BYTES) {
                throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 다운스케일에 실패했습니다.");
            }
        } catch (IOException e) {
            log.error("[Video][Resolution] image write failed src={} dst={}", mask(src), mask(dst));
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 다운스케일에 실패했습니다.");
        }
    }

    @Override
    public int[] readDimensions(Path src) {
        BufferedImage img = readImage(src);
        return new int[]{img.getWidth(), img.getHeight()};
    }

    private BufferedImage readImage(Path src) {
        BufferedImage img;
        try {
            img = ImageIO.read(src.toFile());
        } catch (IOException e) {
            log.error("[Video][Resolution] image read failed src={}", mask(src));
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "프레임 이미지를 읽을 수 없습니다.");
        }
        if (img == null) {
            log.error("[Video][Resolution] unsupported or corrupt image src={}", mask(src));
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "프레임 이미지를 읽을 수 없습니다.");
        }
        return img;
    }

    /** 출력 확장자 기반 ImageIO 포맷 결정 — 미상이면 png 로 기본 처리. */
    private String formatOf(Path dst) {
        String name = dst.getFileName() != null ? dst.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "png";
        }
        String ext = name.substring(dot + 1);
        return switch (ext) {
            case "jpg", "jpeg" -> "jpg";
            case "bmp" -> "bmp";
            default -> "png";
        };
    }

    private String mask(Path p) {
        return p == null ? "null" : Integer.toHexString(p.toString().hashCode());
    }
}
