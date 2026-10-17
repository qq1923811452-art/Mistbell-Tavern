package com.mistbell.tavern.v3.feature.embedding

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuEvidenceStatusPolicyTest {
    @Test
    fun verifiesSevenSuccessfulCasesOnlyWhenHashesAndMappingsMatch() {
        assertTrue(NpuEvidenceStatusPolicy.shouldVerify(true, true, cases(), mappings(true), REQUIRED))
    }

    @Test
    fun rejectsModelOrApkHashMismatch() {
        assertFalse(NpuEvidenceStatusPolicy.shouldVerify(false, true, cases(), mappings(true), REQUIRED))
        assertFalse(NpuEvidenceStatusPolicy.shouldVerify(true, false, cases(), mappings(true), REQUIRED))
    }

    @Test
    fun rejectsFailedCaseOrMissingMapping() {
        val failed = cases().apply {
            put(6, JSONObject().put("result", "failed").put("dimensions", 768))
        }
        assertFalse(NpuEvidenceStatusPolicy.shouldVerify(true, true, failed, mappings(true), REQUIRED))
        assertFalse(NpuEvidenceStatusPolicy.shouldVerify(true, true, cases(), mappings(false), REQUIRED))
        val wrongLengths = cases().apply { put(0, JSONObject().put("inputChars", 2).put("result", "success").put("dimensions", 768)) }
        assertFalse(NpuEvidenceStatusPolicy.shouldVerify(true, true, wrongLengths, mappings(true), REQUIRED))
    }

    private fun cases(): JSONArray = JSONArray().apply {
        listOf(1, 32, 74, 128, 256, 512, 1024).forEach { chars -> put(JSONObject().put("inputChars", chars).put("result", "success").put("dimensions", 768)) }
    }

    private fun mappings(value: Boolean): JSONObject = JSONObject().apply {
        REQUIRED.forEach { put(it, value) }
    }

    companion object {
        private val REQUIRED = listOf("libQnnHtp.so", "libQnnHtpV75Stub.so", "libcdsprpc.so")
    }
}
