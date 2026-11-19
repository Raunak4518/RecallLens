package org.photolibrary.app.subjects

import android.net.Uri

internal data class IdentityRejection(val uri: Uri, val revision: String, val regionKey: String)

internal fun PhotoRegion?.identityKey(): String =
    this?.let { listOf(it.left, it.top, it.right, it.bottom).joinToString(":") { edge -> edge.toBits().toString() } }.orEmpty()
