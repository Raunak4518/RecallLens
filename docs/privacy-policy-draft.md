# Photo Library privacy policy — publisher draft

Draft prepared 18 November 2026 from the current Android implementation. This document has not been published, accepted by a publisher or submitted to Google Play. It is not a declaration that the app has completed a legal or policy review.

## Publication inputs still required

The actual publisher/developer identity, a working privacy contact or inquiry mechanism, public policy URL, intended audience/territories and effective publication date have not been supplied. They must be established before this becomes a public policy. No company name, address, support email or hosting operator has been invented here. The publisher must confirm that the final text matches the exact distributed package and its enabled features.

## What this app does

Photo Library searches photos that you permit it to access on your Android device. Local processing is the normal workflow and does not require an app account or cloud subscription. The app uses downloaded/bundled model software on the device to read visible text, describe image content through search vectors, detect regions and suggest similar people or objects. You can confirm names and references; an appearance suggestion does not automatically become your confirmed label.

The current release work targets on-device use. There is no publisher-operated public cloud service established by this project. The app also contains a separately configured, optional cloud-processing workflow described below. Not configuring or approving that workflow leaves local search available.

## Photo access and local information

If you allow gallery access, the app reads the photo scope granted by Android, which may be all permitted images or a limited selection. You can also grant a folder and its subfolders. The app reads source identifiers, filenames and revision metadata to find permitted originals and avoid unnecessary repeat analysis. It may read photo pixels and derive recognized text/regions, visual and identity vectors, detection regions, confirmed names/aliases, reference areas, clothing-color estimates and processing status.

Nearby-photo search can read GPS coordinates already embedded in accessible photos after the relevant media-location permission is granted. It does not require live GPS tracking. Names and photos can contain sensitive information. Face-related vectors and user-provided labels are used locally for photo retrieval and review, not as proof of a person's identity or ownership of an object.

## Local storage and protection

Sensitive derived search records and configured cloud credentials are stored in the app's encrypted SQLCipher database. Android Keystore protects the database-key wrapping material. The app disables Android backup for its private state. These controls protect copied stored data under their documented Android/key assumptions; they do not prevent a running authorized app, a compromised device or someone controlling an unlocked device from seeing available information.

Local records generally remain while needed for the selected library. Source revisions and access are rechecked, and edits/deletion/revocation invalidate affected evidence. Temporarily unavailable authorized sources can retain hidden encrypted records for reconnection. User-created subject labels can remain after their last photo reference is removed. There is no fixed time-based expiry for the entire local index.

## Network activity and SDK diagnostics

The local image and query workflow does not submit the gallery, recognized text, embeddings or search query to the configured cloud worker. However, the app includes Google ML Kit OCR. Google documents on-device image/text processing while separately documenting SDK update and diagnostic/usage communications. Those diagnostics can include device/app information, per-installation identifiers, timing, configuration and feature-event metadata. They are distinct from uploading the image or recognized text. [ML Kit terms](https://developers.google.com/ml-kit/terms) · [ML Kit disclosure](https://developers.google.com/ml-kit/android-data-disclosure)

The app's finite emulator network audit observed no traffic during its measured local flows; that does not prove that no SDK traffic can ever occur. The publisher must review the precise SDK versions and disclosures in the distributed build before making any claim about analytics, identifiers or network activity.

## Optional approved cloud processing

If you configure a compatible worker, the app stores its address, credential and pinned worker key privately. A cloud request requires review and affirmative approval of a particular rendered copy, mode, task and destination. CLOUD_FULL sends the approved rendered visible image; CLOUD_REDACTED sends a rendered copy with the approved crop/masks. Local processing remains available. Detectors can miss sensitive content, so redaction reduces disclosure but cannot promise anonymity or complete removal of private details.

The app encrypts the request for the configured worker, and authenticates encrypted results. The worker decrypts the approved image to perform OCR; encryption does not hide that image from the chosen worker/operator. That operator and the network connection can also handle operational metadata, such as credential/caller identity, request sizes, times and job state. The operator's own retention, backups and data-handling practices must be checked before use.

The development service implements expiring jobs and deletion controls, but its retention settings are configurable. This policy does not promise a fixed number of hours or immediate deletion from backups for every possible compatible service. Cancelling or losing source approval triggers cleanup attempts; an unavailable service can leave cleanup pending. Forgetting a saved cloud connection removes its local configuration, not a guarantee of remote deletion. Local cloud-result text persists as revision-bound encrypted evidence until invalidated or app data is removed.

## Your controls and deletion

Use Android photo permissions to change the permitted gallery scope. Use Sources → Manage folders to remove a folder grant. These actions do not delete your original photos. Remove a saved reference through the naming editor to stop using that reference as a confirmed match. The current app does not provide a complete in-app reset of every subject label and derived record; Android's app-storage Clear storage operation removes the app's private state and requires setup/reindexing again. It does not erase photos held by other gallery/storage providers. Remote jobs and any data already approved for a third-party worker need that worker's deletion mechanism.

No local app account is created. If a future version adds account creation, this policy and the deletion workflow must be revised before that feature ships. The publisher must provide a real contact/inquiry route and explain any applicable rights/request handling before publication.

## Scope of this draft

This text covers the inspected implementation rather than an unimplemented hosting, advertising or paid-service business. The publisher must decide and disclose any actual additional data use, audience restrictions, legal obligations, SDK changes and service arrangements. A public policy must identify the actual publisher/contact, be accessible in the app and be hosted at a stable public URL. [Google Play User Data requirements](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
