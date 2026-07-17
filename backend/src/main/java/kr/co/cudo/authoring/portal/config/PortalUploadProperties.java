package kr.co.cudo.authoring.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 포털 전용 업로드 정책 바인딩 (prefix {@code portal.upload}).
 * <p>
 * 이미지/영상 업로드의 크기·확장자·개수·프레임 상한을 한 곳에서 관리한다. 불변
 * record 로 바인딩(생성자 바인딩)하며 setter 를 두지 않는다.
 * <ul>
 *   <li>영상: {@code maxFileSizeBytes}, {@code allowedExtensions}</li>
 *   <li>이미지: {@code maxImageSizeBytes}, {@code allowedImageExtensions}, {@code maxImagesPerRequest}</li>
 *   <li>공통: {@code storagePath}, {@code maxFrames}(영상 프레임 추출 상한)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "portal.upload")
public record PortalUploadProperties(
        long maxFileSizeBytes,
        List<String> allowedExtensions,
        String storagePath,
        List<String> allowedImageExtensions,
        long maxImageSizeBytes,
        int maxImagesPerRequest,
        int maxFrames
) {
}
