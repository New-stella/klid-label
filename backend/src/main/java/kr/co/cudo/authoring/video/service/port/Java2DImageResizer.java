package kr.co.cudo.authoring.video.service.port;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
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
 * <p>BufferedImage 로 읽어 {@link Graphics2D} BILINEAR 보간으로 다운스케일한 뒤 ImageIO 로
 * 저장한다. 손상 이미지({@link ImageIO#read} null/예외)는 추상 메시지로 변환하고, 출력 파일이
 * 비정상(100바이트 미만)이면 실패로 간주한다. 경로는 로그에서 hash 마스킹한다(CWE-209/PII).
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
        BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }

        String format = formatOf(dst);
        File dstFile = dst.toFile();
        try {
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
