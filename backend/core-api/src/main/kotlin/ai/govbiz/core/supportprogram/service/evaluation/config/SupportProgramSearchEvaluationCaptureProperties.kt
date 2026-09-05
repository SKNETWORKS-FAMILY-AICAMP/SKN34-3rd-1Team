package ai.govbiz.core.supportprogram.service.evaluation.config

import java.nio.file.Path
import org.springframework.boot.context.properties.ConfigurationProperties

/** 평가 전용 검색 실행 기록의 입력 질문 묶음과 출력 파일 위치입니다. */
@ConfigurationProperties(prefix = "app.support-program-search-capture")
data class SupportProgramSearchEvaluationCaptureProperties(
    val querySetPath: Path,
    val outputPath: Path,
    val acceptingOnly: Boolean = true,
) {
    init {
        require(querySetPath.toString().isNotBlank()) {
            "app.support-program-search-capture.query-set-path must not be blank"
        }
        require(outputPath.toString().isNotBlank()) {
            "app.support-program-search-capture.output-path must not be blank"
        }
        require(querySetPath.toAbsolutePath().normalize() != outputPath.toAbsolutePath().normalize()) {
            "app.support-program-search-capture.query-set-path and output-path must differ"
        }
    }
}
