package com.my.kizzy.gateway

import com.my.kizzy.gateway.entities.op.OpCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayPayloadLoggingTest {
    @Test fun identifyAndResumeNeverLogCredentialPayloads() {
        val sentinel = UUID.randomUUID().toString()
        val payload = """{"d":{"token":"$sentinel"}}"""
        listOf(OpCode.IDENTIFY, OpCode.RESUME).forEach { opcode ->
            val logged = gatewayPayloadForLog(opcode, payload)
            assertTrue(logged.contains("REDACTED"))
            assertFalse(logged.contains(sentinel))
            assertFalse(logged.contains(payload))
        }
    }

    @Test fun nonCredentialPayloadsRemainUsefulForDiagnostics() {
        val payload = """{"d":{"activities":[]}}"""
        assertEquals(payload, gatewayPayloadForLog(OpCode.PRESENCE_UPDATE, payload))
    }
}
