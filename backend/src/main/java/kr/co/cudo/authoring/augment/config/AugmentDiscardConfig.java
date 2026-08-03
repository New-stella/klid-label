package kr.co.cudo.authoring.augment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 증강 폐기(소프트 삭제 → 유예 → 실삭제) 설정 등록. 도메인 국소 {@code @EnableConfigurationProperties}
 * 로 전역 설정 파일을 건드리지 않는다({@code PortalUploadConfig} 동형).
 */
@Configuration
@EnableConfigurationProperties(AugmentDiscardProperties.class)
public class AugmentDiscardConfig {
}
