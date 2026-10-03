package com.craftmind.app.domain.planning

import com.craftmind.app.domain.model.BuildLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildPlanJsonParserTest {
    private val parser = BuildPlanJsonParser()

    @Test
    fun parsesExactVersionedPlanJson() {
        val result = parser.parse(validJson())

        assertTrue(result is BuildPlanParseResult.Parsed)
        val draft = (result as BuildPlanParseResult.Parsed).draft
        assertEquals(1, draft.schemaVersion)
        assertEquals("Stone hut", draft.title)
        assertEquals(1, draft.operations.size)
        assertEquals("minecraft:stone", draft.operations.single().block.identifier)
    }

    @Test
    fun rejectsMalformedAndWrappedOutputInsteadOfExtractingAPlan() {
        assertEquals(
            BuildPlanParseResult.Rejected(BuildPlanParseIssue.MALFORMED_JSON),
            parser.parse("Here is your plan: ${validJson()}"),
        )
        assertEquals(
            BuildPlanParseResult.Rejected(BuildPlanParseIssue.MALFORMED_JSON),
            parser.parse("```json\n${validJson()}\n```") ,
        )
        assertEquals(
            BuildPlanParseResult.Rejected(BuildPlanParseIssue.MALFORMED_JSON),
            parser.parse("{\"schemaVersion\":1,}"),
        )
    }

    @Test
    fun rejectsUnknownKeysAndUnsupportedSchemaVersions() {
        val withExtraKey = validJson().replace("\"title\":\"Stone hut\"", "\"title\":\"Stone hut\",\"extra\":true")
        assertEquals(
            BuildPlanParseResult.Rejected(BuildPlanParseIssue.INVALID_STRUCTURE),
            parser.parse(withExtraKey),
        )

        val unknownVersion = validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":9")
        assertEquals(
            BuildPlanParseResult.Rejected(BuildPlanParseIssue.UNSUPPORTED_SCHEMA_VERSION),
            parser.parse(unknownVersion),
        )
    }

    @Test
    fun enforcesResponseByteLimitUsingUtf8Size() {
        val oversizedUtf8 = "\"" + "€".repeat(BuildLimits.MAX_PLAN_RESPONSE_BYTES / 2) + "\""

        assertEquals(
            BuildPlanParseResult.Rejected(BuildPlanParseIssue.RESPONSE_TOO_LARGE),
            parser.parse(oversizedUtf8),
        )
    }

    private fun validJson() = """{"schemaVersion":1,"title":"Stone hut","style":"rustic","dimensions":{"width":1,"length":1,"height":1},"origin":{"x":0,"y":64,"z":0},"materials":[{"blockIdentifier":"minecraft:stone","count":1}],"steps":[{"id":"foundation","title":"Foundation","description":"Place the base"}],"operations":[{"sequence":0,"position":{"x":0,"y":0,"z":0},"block":{"identifier":"minecraft:stone","properties":[]},"stepId":"foundation","rotationDegrees":null,"dependsOnSequences":[]}],"estimatedOperationCount":1}"""

}
