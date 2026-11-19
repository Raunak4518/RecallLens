package org.photolibrary.app.detection

import org.photolibrary.app.index.ModelRevision

internal object GarmentModelRevisions {
    fun faceDetector(artifactSha256: String) = ModelRevision(
        "yunet-face",
        "${validated(artifactSha256)}:640:score0.85:nms0.30",
        640,
    )

    fun personDetector(artifactSha256: String) = ModelRevision(
        "yolox-nano-person",
        "${validated(artifactSha256)}:416-pad114:score0.35:nms0.45",
        416,
    )

    fun upperClothes(artifactSha256: String) = ModelRevision(
        "mediapipe-clothes-upper-region",
        "${validated(artifactSha256)}:litert1.4.2:rgb-minus1to1-bilinear256:argmax0.60:upper154of256",
        256,
    )

    private fun validated(artifactSha256: String): String {
        require(artifactSha256.matches(Regex("[0-9a-f]{64}")))
        return artifactSha256
    }
}
