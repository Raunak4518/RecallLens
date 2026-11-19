# Cached location lookup at 5,000 photos

The Android17 x86_64 emulator compared individual source-revision reads against the production batched location search on the same5,000 MediaStore references and SQLCipher coordinate rows. The photos are generated8x8 JPEGs. Cached coordinates alternate between a point near Surat and a point roughly one degree farther north. This measures cached provider/database lookup and distance filtering. It supplies no GPS extraction or real-photo location accuracy result.

One warm-up preceded three recorded comparisons. The first path alternated by run. Both paths validated revisions, read encrypted cached coordinates and filtered within30km of the same anchor. The production path takes a second gallery-revision snapshot before returning matches. Fixture insertion and deletion, initial EXIF extraction and app startup are excluded.

| Recorded run | Individual revision reads | Batched revision snapshots |
| --- | ---: | ---: |
| 1 | 82.128s | 2.087s |
| 2 | 68.920s | 1.901s |
| 3 | 74.101s | 1.892s |
| Median | 74.101s | 1.901s |

The median reduction is97.43%, approximately38.98 times faster. Every comparison returned the same2,500 matches. Zero EXIF reads were allowed by the fixture. Ordinary background MediaStore and gallery services were active, so use the repeated timings and report variation. These emulator results do not establish ARM battery or thermal performance.

The test is PhotoLocationsTest.compareFiveThousandCachedLocationsWithIndividualRevisionReads, enabled with the locationScale instrumentation argument. Its finally block deletes only its inserted photo URIs. The result is written before cleanup, so verify the final OK3 transcript before treating the whole acceptance run as passed.

Raw evidence is app/build/location-scale-results.json after the host runner retrieves it, files/location-scale-results.json inside the feasibility app, and app/build/location-scale-instrumentation.txt. The test creates its own temporary encrypted catalog and Keystore alias.
