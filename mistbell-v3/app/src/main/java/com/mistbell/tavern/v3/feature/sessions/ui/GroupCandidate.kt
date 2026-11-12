package com.mistbell.tavern.v3.feature.sessions.ui

/** A character that can be picked as a group participant. */
data class GroupCandidate(
    val id: String,
    val name: String,
    /** Private avatar file name; `null` renders the gradient-initial fallback. */
    val avatarFileName: String? = null,
)
