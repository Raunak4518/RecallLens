package org.photolibrary.app.privacy

import org.photolibrary.app.PhotoLibraryApplication

internal val PhotoLibraryApplication.photoExport get() = PhotoExport(library, LocalSensitiveRegions(regionModels)::detect)
internal val PhotoLibraryApplication.cloudSettings get() = CloudConfiguration(storage)
