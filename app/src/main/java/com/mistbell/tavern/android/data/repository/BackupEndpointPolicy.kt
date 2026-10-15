package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.util.ApiEndpointPolicy

/** Quarantine unsafe API endpoints imported from an untrusted backup. */
internal fun endpointForRestore(endpoint: String): String =
    if (ApiEndpointPolicy.writeRejectionReason(endpoint) == null) endpoint else ""
