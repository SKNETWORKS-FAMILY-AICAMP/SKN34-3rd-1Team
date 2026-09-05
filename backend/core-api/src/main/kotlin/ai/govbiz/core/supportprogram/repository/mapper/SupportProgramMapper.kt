package ai.govbiz.core.supportprogram.repository.mapper

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 지원사업 카탈로그 MySQL SQL을 실행하는 MyBatis Mapper입니다. */
@Mapper
interface SupportProgramMapper {

    fun upsert(row: SupportProgramDbRow): Int

    fun markAllNotPresentBySourceCode(@Param("sourceCode") sourceCode: String): Int

    fun findBySourceAndProgramId(
        @Param("sourceCode") sourceCode: String,
        @Param("sourceProgramId") sourceProgramId: String,
    ): SupportProgramDbRow?

    fun findPresentBySourceCode(
        @Param("sourceCode") sourceCode: String,
    ): List<SupportProgramDbRow>
}
