package kr.co.cudo.authoring.video.service.port;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * java.nio 기반 영상 파일 복사 구현 — Phase 2 (해상도 파생영상).
 *
 * <p>{@link Files#copy}로 원본 비식별 비디오를 파생영상 경로로 복사한다. 경로는 로그에서 hash
 * 마스킹한다(CWE-209/PII — 경로 원문 미노출). 실패는 추상 메시지로 변환한다.
 */
@Slf4j
@Component
public class NioVideoFileCopier implements VideoFileCopier {

    @Override
    public boolean exists(Path src) {
        return src != null && Files.exists(src) && Files.isRegularFile(src);
    }

    @Override
    public void copy(Path src, Path dst) {
        try {
            if (dst.getParent() != null) {
                Files.createDirectories(dst.getParent());
            }
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("[Video][ResolutionDerivative] video copy failed src={} dst={}", mask(src), mask(dst));
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "파생영상 비디오 복사에 실패했습니다.");
        }
    }

    private String mask(Path p) {
        return p == null ? "null" : Integer.toHexString(p.toString().hashCode());
    }
}
