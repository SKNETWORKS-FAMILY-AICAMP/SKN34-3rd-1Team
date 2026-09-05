package ai.govbiz.core.supportprogram.domain

import ai.govbiz.core.supportprogram.helper.SupportProgramTestHelper.catalogProgram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SupportProgramIdentityTest {

    @Test
    fun createsAnUnambiguousProviderQualifiedIdEvenWhenTheRawIdContainsAColon() {
        val program = catalogProgram("PBLN:123").program.copy(sourceCode = "K_STARTUP")

        assertEquals("K_STARTUP:PBLN:123", program.sourceQualifiedId)
    }

    @Test
    fun rejectsSourceCodesThatCannotBeSeparatedFromTheRawId() {
        assertThrows(IllegalArgumentException::class.java) {
            catalogProgram("PBLN_123").program.copy(sourceCode = "OTHER:SOURCE")
        }
    }
}
