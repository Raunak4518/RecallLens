# Query planning and result evidence

This change advances PL064 and PL065. A user can name a person Ronak and explicitly add Myself as an alias. The named catalog then resolves Me and Myself to that person, and “my photo”, “my photos” and “my image” use that same identity. Two conflicting self enrollments do not create an inferred self ID. Existing explicit labels retain their priority.

PhotoQueryPlan wraps the existing confirmed-name and nearby grammar. It exposes structured requirements for an unenrolled self, unknown name, missing place reference, unsupported person-to-clothing relation, spatial relation or other unsupported syntax. This lets the normal search screen tell the user which evidence is missing without accepting an incomplete conjunction.

PhotoResultGroups contains confirmed results, remembered-text results and related suggestions. Grouping removes inaccessible URIs and duplicate URIs. Named-only grouping returns confirmed results. Related face/object candidates never write to the named catalog. Geographic results enter confirmed grouping only after the nearby lookup returns actual matches.

## Integration

Call planPhotoQuery(query, namedLibrary, namedOnly) instead of calling searchNamed directly when building screen state. Its named field preserves existing location scheduling and subject review behavior. Store requirements with the screen state for actionable messages. For semantic content queries, canRankContents permits CLIP ranking and OCR/filename matching. After those asynchronous operations, call groupPhotoResults with the current accessible URI set. Put ranked identity suggestions in identitySuggestions only when the result is associated with the requested subject. Keep Named mode namedOnly true.

The parent owner integrates these fields in LibraryViewModel and the search screen. This domain change intentionally preserves the existing searchNamed return types so other callers and tests remain compatible. PhotoQueryPlanTest covers explicit self enrollment, intersections, absent names, unsupported relations, de-duplication, revocation and confirmed-only Named output. Device execution is owned by the parent.

## Person and clothing stage

The next relation implementation needs person detections with boxes, face-to-person association, garment masks or boxes assigned to that person, color attributes from the garment pixels, source/model revisions, and relation confidence. Store each association as a relation between region IDs with its provenance. Face identity resolves the person only from sufficient reference evidence. Clothing color alone cannot establish identity.

For the minimal validation cohort, use paired people wearing different shirts in one photo, swap their positions across another photo, and add same-color distractors. A successful “me wearing red” result requires the recognized person's region to own the red garment. Label the relationships before tuning. Evaluate relation accuracy and final retrieval separately. Existing whole-photo CLIP and name co-occurrence tests do not validate this capability.
