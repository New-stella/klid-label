package kr.co.cudo.authoring.batch.reclaim;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 「처리 중」 고착 회수 설정 등록. 도메인 국소 {@code @EnableConfigurationProperties} 로
 * 전역 스캔 없이 이 패키지 설정만 바인딩한다({@code AugmentDiscardConfig} 와 동형).
 */
@Configuration
@EnableConfigurationProperties(ProcessingStaleReclaimProperties.class)
public class ProcessingStaleReclaimConfig {
}
