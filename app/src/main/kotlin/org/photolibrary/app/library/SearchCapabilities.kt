package org.photolibrary.app.library

internal data class SearchCapabilities(
    val profile: String,
    val semanticSearch: Boolean,
    val rememberedTextSearch: Boolean,
    val anonymousRegionReview: Boolean,
    val personIdentitySearch: Boolean,
    val exactObjectSearch: Boolean,
    val clothingSearch: Boolean,
)
