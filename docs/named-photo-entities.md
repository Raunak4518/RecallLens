# Search for named things in photos

Open-ended descriptions and user-assigned names need different search paths. Whole-photo CLIP ranks photos for descriptions such as “a dog on a beach.” A name such as “my car” resolves to an entity in the user's library. A label like “car” never assigns ownership or links multiple cars. OpenAI also reports that its original CLIP performs poorly on some fine-grained distinctions, including car models. [CLIP paper](https://arxiv.org/abs/2103.00020)

## Working Android flow

1. Open **Name suggestions**. The app scans up to 24 accessible photos at a time and marks the regions detected by YOLOX-Nano for common objects and YuNet for faces. Select **Name this** to name the marked region or link it to a saved name. You can adjust the box, skip an item for the current review, or choose **Scan more photos**. Suggestions stay in memory. Confirmed regions are saved to the encrypted index and disappear from the review. The photo detail editor also supports subjects for which the detectors have no category. [YOLOX ONNX release](https://github.com/Megvii-BaseDetection/YOLOX/tree/main/demo/ONNXRuntime) · [YuNet model](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet)
2. To confirm the same subject from another photo, choose **Saved name** and select it. Reopen the editor to remove a named link from that photo.
3. In **Named** search, enter a name or join saved names with “and” or “with”. “My photo with red shirt and my BMW car” works once the photo has confirmed links for “Me”, “Red shirt” and “My BMW car”. A compound answer certifies that those links are present together. Search reports unsupported wording such as “in front of” or “wearing”.
4. To search by distance, confirm a gallery photo with embedded GPS as a **Place**, for example “Surat”. Try “near Surat”, “Me and my BMW car around Surat”, or “my BMW car in Surat” and allow access to photo locations. A result must have embedded coordinates within 30 km of at least one confirmed GPS reference for that place. This is a radius around the reference points. A city whose name was never saved needs a reference photo first. The app reads coordinates on demand from accessible MediaStore photos and keeps them in memory for the current query. [Android shared media documentation](https://developer.android.com/training/data-storage/shared/media#location-where-media-was-captured)
5. Name a photo of a college as an **Organization** and select **Set as my current college**. Do the same when switching to another college. “My college” and “my current college” follow the latest explicit selection. “My previous college” follows the most recently selected different college. The actual college names and photo links remain searchable. This history records changes made in the app. Study dates and photo capture dates have separate meanings.

SQLCipher stores names, aliases, image references, optional boxes and college selection history with the existing encrypted index. It migrates the existing OCR and CLIP tables in place. Reference photos stay with their original providers. The app validates source revisions for referenced photos and drops links to edited, deleted or inaccessible photos on reconciliation. An original-media provider without revision metadata requires a bounded pixel digest before a reference can be reused. Removing a link preserves the user-created name and selection history.

The detectors run through the existing ONNX Runtime. The staging tool pins model file hashes and bundles the original Apache-2.0 and MIT license texts. It uses the release's actual input contract, verified against cached photos. The baseline score cutoffs are 0.35 for YOLOX and 0.85 for YuNet. These are suggestion thresholds. No face or object identity is assigned by a score. A revision check before publishing and again on confirmation guards against a stale box. [YOLOX license](https://github.com/Megvii-BaseDetection/YOLOX/blob/0.1.1rc0/LICENSE) · [YuNet license](https://github.com/opencv/opencv_zoo/blob/main/models/face_detection_yunet/LICENSE)

## Ranked review for a saved identity

In **Name suggestions**, candidate regions are grouped as **Possible same subject** for comparison. These are model proposals, not confirmed identities. Uncheck regions that belong to a different subject, then select **Name N selected**. The name editor opens once for the selected group. The app preserves each selected region and checks every original again before saving the links to one entity in a single encrypted transaction. If a selected photo changed or lost access, the batch is rejected. Individual naming remains available from each suggestion.

The grouping policy separates kinds and detector labels, uses the minimum similarity from a candidate to every region already in a group, requires a margin over the next group, and limits a suggestion group to twelve photos. Face and object cutoffs are heuristic proposal thresholds, not calibrated identity probabilities. Ambiguous candidates remain separate; confirmation is still required. The candidate policy is exercised by `ReviewGroupsTest`.

On the x86_64 emulator, all ten `NamedSubjectsTest` cases passed. The batch case checks that an edited selection leaves the store untouched. It then confirms two valid regions, reopens the encrypted store, and verifies that their alias resolves to exactly those photos while an unchecked lookalike stays out.

Saving a new **Person** or **Object** opens the review and starts comparing accessible photos locally. An exact name or alias in **Named** search exposes **Review possible matches** for a saved identity. The review presents every confirmed photo first, followed by up to 3, 5 or 10 possible matches. The default is 5. Each photo appears once in the ranking and shows its highest-scoring region. **Confirm match** saves the same named identity against that region. **Skip** hides a candidate for the current review, and the next ranked photo can take its place.

To add several photos to an existing name, select the matching cards in **Possible matches** and tap **Confirm N selected**. Each card starts unchecked. Confirmation uses the same revision checks and transaction as group naming. If anything changed, review the refreshed matches before trying again. The saved-name stale-batch regression passed on the emulator and verified that existing links were preserved.

The face pipeline uses YuNet's five landmarks to align a face to the OpenCV SFace template. A marked reference must isolate one detected face. An unmarked portrait must contain exactly one detected face. The object pipeline uses normalized DINOv2 features for object regions and whole-photo comparisons. It blends 85% DINOv2 similarity with 15% hue-histogram similarity when both crops contain enough visible color. Faces are scored independently of the object pipeline. The highest similarity against the confirmed references gives the per-photo score. [OpenCV face alignment and SFace](https://docs.opencv.org/4.12.0/d0/dd4/tutorial_dnn_face.html) · [DINOv2 paper](https://arxiv.org/abs/2304.07193)

The scores are relative similarities and top K can include lookalikes. Named search uses encrypted confirmation records. Identity review caches face and object vectors, regions and previously tried reference crops in SQLCipher. The first review builds the relevant cache. A repeat review checks photo revisions and ranks saved features, opening inference sessions only for entries that need processing. Both reference and candidate photos are checked against current source revisions, and confirmation verifies the exact revision used to generate the candidate. Physical-device and large-library timings, persistent rejection, and calibration on broader data are open.

On the offline Android 17 emulator, the face reference was the NASA portrait of Eileen Collins. A different photograph taken during STS-63 scored 0.568 and a portrait of Susan Helms scored 0.147. The object reference was COIL-100 toy car 27 at 0°. The DINOv2-only Android run ranked the correct 120° view third. After adding hue similarity, that view ranked first. [Original astronaut portrait source](https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut) · [NASA STS-63 photograph](https://www.nasa.gov/image-article/eileen-collins-discovery-pilot/) · [NASA Helms portrait](https://commons.wikimedia.org/wiki/File:Helms_sj4.jpg) · [COIL-100](https://www.cs.columbia.edu/CAVE/software/softlib/coil-100.php)

| COIL-100 candidate | DINOv2 only | DINOv2 and hue |
| --- | ---: | ---: |
| Same car, 120° view | 0.685 | 0.729 |
| Different green car, object 8 | 0.708 | 0.640 |
| Different red car, object 23 | 0.712 | 0.624 |

A desktop check on eight cached toy cars compared four other angles per car against one reference. Appearance-only rank one was 26/32, and the blended score reached 32/32. Excluding car 27, the fixture used to diagnose the Android ranker, the counts were 22/28 and 28/28. The desktop check used whole-photo crops under the same turntable conditions. The measured results give evidence for this test set. Color changes and background variation require broader evaluation.

The emulator `IdentityMatchingTest` verifies that an untouched candidate stays out of Named search, confirmation adds precisely the accepted photo, and the requested limit applies only to the ranked candidates. The Android 17 x86_64 offline run passed 16 targeted tests. Debug lint reported zero errors and 21 warnings. The focused test assets are staged under `app/build/public-evaluation/identity`. The person test reads `face-reference.png`, `face-same.jpg` and `face-lookalike.jpg` from the three portrait sources above. The object test uses `object-reference.png`, `object-same.png`, `object-lookalike.png` and `object-other.png` from COIL-100 members `obj27__0.png`, `obj27__120.png`, `obj8__0.png` and `obj23__0.png`.

In the app UI, naming the detected face in the in-flight photo opened ranked review. The earlier portrait appeared first, ahead of the other astronaut. After confirming that suggestion, Named search returned exactly the two confirmed photos.

## Emulator verification

The offline Android 17 x86_64 run passed 12 targeted instrumentation tests. The named tests covered two confirmed views, an unconfirmed lookalike, aliases, region persistence, duplicate-name rejection, reopening the encrypted index, edited and revoked links, multiple kinds, and a college switch. The GPS test wrote real EXIF coordinates into four MediaStore fixtures. A 30 km query returned the two photos inside the radius, excluded the distant and untagged photos, and honored an additional confirmed-name filter. Existing OCR, CLIP, source lifecycle and encrypted-index migration tests passed in the same run.

In the debug app, four previously cached COIL-100 photos were loaded through the Android gallery. We confirmed 0° and 120° views of toy car 27 as “My car”. The Named search for “my car” showed exactly those two photos before and after an app force-stop. With the 240° view of the same car and a view of toy car 23 in the gallery, both unconfirmed photos stayed out. [COIL-100](https://www.cs.columbia.edu/CAVE/software/softlib/coil-100.php)

The debug APK, feasibility APK, test APK and lint tasks built with cached dependencies. Lint reported zero errors and 17 warnings. Android instrumentation classes for the feature are `NamedSubjectsTest` and `PhotoLocationsTest`. This advances PL064, PL065, PL070 and PL071. Physical-device tests, large-gallery timings and model-based identity matching remain open.

The model-generated suggestion update passed 14 targeted tests on the same offline Android 17 emulator. `RegionDetectionTest` verified a dog detection, two distinct face regions in one test image, a blank image yielding no results, and the confirmation boundary with two photos that both produced a dog suggestion. Only the confirmed one entered Named search. Editing the other photo invalidated its old box at confirmation. Regressions covered Named search, GPS, encrypted persistence, OCR and CLIP. The face test uses a NASA portrait of Eileen Collins distributed by scikit-image and duplicates it to test that two spatially separate detections survive. This is a workflow test. Detection accuracy and throughput across camera conditions and physical devices remain unmeasured. The updated build passed lint with zero errors and 18 warnings. [NASA fixture attribution via scikit-image](https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut)

The debug app generated face, dog, car and other review cards from the gallery fixtures while offline. Selecting the Face card opened the name editor with its box preset. Confirming it produced exactly one Named result. A new scan after force-stop hid the confirmed face while continuing to offer an unconfirmed dog.

## Further evaluation and implementation

1. Compare same-category regions with a separate image encoder when suggesting repeated instances. Multiple diverse views count as references for the same entity. Two indistinguishable objects may need a visible identifier, a distinctive mark or a manual decision.
2. Evaluate cutoffs on unseen objects and hard negatives. Show possible occurrences in review and verify that only confirmed links reach Named search. Consider a mask when background or overlapping objects changes a match.
3. Persist reference embeddings and model revisions through the encrypted index if repeated-instance suggestions qualify. Invalidate derived records when a source photo changes or becomes inaccessible.

| Query or feature | First implementation to evaluate |
| --- | --- |
| A dog on a beach, a kitchen, a red bicycle | Whole-photo CLIP for scene retrieval. Regions and color measurements can help distinguish an object's color from the background. |
| My car, my laptop | Resolve the saved name, then retrieve confirmed region links. Compare CLIP region embeddings with an instance retrieval encoder such as DINOv2 to generate review candidates. |
| My living room, the office | Enroll reference scenes from several angles. Evaluate scene matching separately from the whole-photo CLIP category search. |
| Photos of me, or photos where two named people appear | Evaluate a separate opt-in path with face detection, aligned crops and a face verification encoder. Require privacy and quality review before implementation. |

Named entity recognition could help extract names from complex natural-language queries later. The first version can resolve exact normalized names and aliases. Object detection locates things, segmentation can isolate them, and the confirmed database relationship supplies the meaning of “my.”

DINOv2 is an image feature candidate, and its authors evaluated instance retrieval on landmark benchmarks. Those results motivate a comparison on personal-object tasks. Face verification needs a model and evaluation protocol suited to the face domain. A candidate such as ArcFace needs its exact weights and usage terms reviewed separately. Evaluate both missed matches and wrong identities, including group photos, poor lighting and pose changes. [DINOv2 paper](https://arxiv.org/html/2304.07193) · [ArcFace paper](https://openaccess.thecvf.com/content_CVPR_2019/html/Deng_ArcFace_Additive_Angular_Margin_Loss_for_Deep_Face_Recognition_CVPR_2019_paper.html) · [NIST 1:N evaluation](https://pages.nist.gov/frvt/html/frvt1N.html)

VeRi contains cross-camera images of real vehicles and requires an application for non-commercial access. A local turntable benchmark can compare object encoders across view changes without treating studio results as a measurement of car matching in a personal gallery. [VeRi access and scope](https://vehiclereid.github.io/VeRi/)

## Object identity benchmark

We downloaded Columbia's COIL-100 archive for local evaluation. We used six distinct photographs per object across 100 physical objects, including eight toy cars selected by inspecting the images before scoring. The report includes the archive SHA-256 and exact object IDs. Test images and weights are kept in ignored files. [COIL-100 source and report](https://www.cs.columbia.edu/CAVE/software/softlib/coil-100.php)

The one-reference run enrolled the 0° view. The three-reference run enrolled 0°, 120° and 240°. Both used 60°, 180° and 300° as probes. Each model and reference count used a cutoff fit to the 99th percentile of different-object scores on 35 development identities. The same cutoff was applied to 65 held-out identities. All eight toy cars were held out. We ran the pinned quantized CLIP ViT-B/32 image encoder and the quantized DINOv2-small export on the same desktop CPU with two ONNX Runtime threads. [DINOv2-small export used](https://huggingface.co/Xenova/dinov2-small/tree/c2bb04a51fab207c420665f1946016107bffc701)

| Measure | CLIP, 1 ref | CLIP, 3 refs | DINOv2, 1 ref | DINOv2, 3 refs |
| --- | ---: | ---: | ---: | ---: |
| Correct identity ranked first, held out | 154 / 195 | 188 / 195 | 183 / 195 | 189 / 195 |
| Same object accepted, held out | 124 / 195 | 159 / 195 | 175 / 195 | 186 / 195 |
| Different object accepted, held out | 112 / 12,480 | 116 / 12,480 | 139 / 12,480 | 204 / 12,480 |
| Correct toy car ranked first | 17 / 24 | 24 / 24 | 22 / 24 | 23 / 24 |
| Same toy car accepted | 23 / 24 | 24 / 24 | 16 / 24 | 24 / 24 |
| Different toy car accepted | 85 / 168 | 78 / 168 | 38 / 168 | 81 / 168 |

Rank one gives one guess among enrolled identities. A named-object search can return multiple photos, so a useful cutoff must reject different objects as well as recover genuine matches. A broad cutoff performed poorly on the similar-car subset for both encoders. Three references improved ranking on the full held-out sample. DINOv2 improved one-reference ranking and recovered more same-object examples overall, at a higher overall false-accept count. These results support ranking candidates for review. Studio toy-car views leave real vehicles, laptops, rooms, backgrounds, and faces unmeasured.

The first automated candidate trial should use user-marked boxes and held-out views of similar objects photographed in different places. Compare a lightweight detector such as MediaPipe EfficientDet-Lite0 with the marked boxes to measure the detection misses separately. Compare CLIP and DINOv2 region features, fit category cutoffs with lookalikes, and measure false suggestions per named search. Keep confirmed links usable independently of the encoder. [MediaPipe Object Detector](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector)

| Pending work | Depends on | Completion evidence |
| --- | --- | --- |
| Add subject-specific detectors and identity suggestions | Confirmed links, licensed or consented labeled cases, measured per-model quality | Compare region features for objects and clothes, dedicated face verification for opted-in people, OCR/layout for documents, and perceptual matching for meme variants. Include different people in a group, different instances of the same product and examples absent from the gallery. |
| Add durable rejection and multi-item review for repeat detections | Candidate identity evaluation and repeatable hard-negative tests | Verify that rejected or untouched candidates stay out of confirmed search, including after a model update or photo edit. |
| Evaluate wearing and spatial relationships | Separately labeled regions and explicit relationship evidence | Measure cases with the shirt on someone else, the car behind a different person, overlapping objects and incorrect front/back orientation. |
| Search for ordinary place names and historical dates | Provenance for local place data and source capture dates, a user-facing editing workflow | Resolve ambiguous city names, account for missing metadata, and show which period or distance produced each result. |

```powershell
python tools/benchmark_object_identity.py --archive app/build/datasets/coil-100-legacy.zip --dino app/build/feasibility-models/dinov2-c2bb04a --near 6 8 15 23 27 69 76 91
```

The command runs with the already staged files and writes measured results to `app/build/datasets/local-object-identity-results.json`.
