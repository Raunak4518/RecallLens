# Clothing and location lifecycle

PL044, PL062 and PL070 use different validation for clothing and GPS records.

Clothing evidence depends on oriented decoded pixels, face/person detectors, garment parser and color/ownership policy. Schema 9 adds one nullable encrypted pixel_revision column to person_garments. A changed source revision retains a fingerprint-bearing row privately; exact-revision search cannot read it. CachedGarmentAnalysis decodes the affected photo and validates the fingerprint through the existing source-checked inspectPhoto flow. Matching input and dependencies allow a transactional revision rebind without running the lazy detectors/parser. Missing fingerprints, changed pixels or dependencies take the normal inference path.

Rebinding checks the source revision before and after the update. An inaccessible source cannot be rebound, and deleting a row or removing scope prevents a late rebind from recreating it. Stale photo selections report unavailable without exposing cached evidence or processing an outdated selection. Person/color search still requires a current confirmed person region matching the evidence; inferred colors remain estimates. No suggestion becomes a Named confirmation through cache reuse.

Older schema-8 clothing rows migrate with a null fingerprint. They remain usable at their matching source/model revision, but cannot reuse across metadata changes until reprocessed. Existing Named proofs migrate independently. New-URI moves and copies remain separate work.

GPS depends on EXIF rather than decoded pixels. A location revision change therefore continues to invalidate that photo's coordinate record and triggers one EXIF reread when needed. Repeat searches reuse it. Location permission loss clears memory and encrypted coordinate state. No pixel fingerprint is used to carry GPS forward: an EXIF-only edit can change a nearby result while preserving identical image pixels.

The verification suite covers encrypted clothing storage, wrong fingerprint and dependency rejection, source changes during commit, deletion/scope removal and schema migration. ClothingWorkflowTest exercises the real-photo confirmed-person path, rename/move reuse, stale selection rejection, encrypted reopening and the visible estimated-color search group. PhotoLocationsTest covers per-photo EXIF reads after metadata changes, cached repeat searches, permission clearing and an actual EXIF-only location edit with unchanged pixel hashes.

Device timings are emulator acceptance durations; physical ARM latency, energy, peak memory and production release signing remain pending. Cloud approval and cloud-result continuity remain governed by exact artifact consent and are not migrated by these fingerprints.

The initial suite on Android 17 x86_64 emulator-5580 reported OK (9 tests), 59.451 seconds: seven executed successfully, with the opt-in clothing workflow and 5,000-photo location benchmark skipped. The explicit follow-up enabled clothingWorkflow and passed all three selected tests in 128.206 seconds: real clothing screen/rename/move reuse, schema-8 garment migration and an EXIF-only GPS edit. The GPS edit retained exactly the same decoded pixel hash and removed the edited photo from the nearby matches. Artifacts: app/build/garment-location-lifecycle-regression.txt and garment-location-final-regression.txt.

The final extended clothing workflow passed separately in 100.949 seconds. After the screen and rename/move checks, it replaced the photo with a solid red image: the old confirmed person reference disappeared, the analysis reported zero cached photos and one processed photo, and no stale face/clothing evidence remained. Artifact: app/build/garment-edited-image-regression.txt. The three runs provide ten distinct executed checks, with the extended workflow rerun adding the edit acceptance.

Debug build/lint passed in garment-metadata-build.txt and garment-location-final-build.txt. ARM64 APK/AAB and all ten static package checks passed in garment-metadata-release-build.txt and release-inspection/results.json. The full dependency checklist remains open for physical qualification, new-URI identity and cloud continuity.
