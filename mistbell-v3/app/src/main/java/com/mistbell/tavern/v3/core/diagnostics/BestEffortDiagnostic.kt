package com.mistbell.tavern.v3.core.diagnostics

/** Diagnostics, including detail construction, must never change the operation being observed. */
internal inline fun bestEffortDiagnostic(record: () -> Unit) {
    runCatching(record)
}
