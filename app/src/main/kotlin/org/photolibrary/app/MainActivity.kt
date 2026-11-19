package org.photolibrary.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.photolibrary.app.library.GalleryAccess
import org.photolibrary.app.library.LibraryScreen
import org.photolibrary.app.library.LibraryViewModel
import org.photolibrary.app.library.SuggestionActions
import org.photolibrary.app.library.IdentityActions

class MainActivity : ComponentActivity() {
    private val library: LibraryViewModel by viewModels()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { library.refresh() }
    private val photoLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { library.refresh() }
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(library::addFolder) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme(
                    primary = Color(0xFFE2E2E2), onPrimary = Color(0xFF202020),
                    primaryContainer = Color(0xFF383838), onPrimaryContainer = Color(0xFFF4F4F4),
                    secondary = Color(0xFFCCCCCC), secondaryContainer = Color(0xFF353535),
                    onSecondaryContainer = Color(0xFFEAEAEA),
                    background = Color(0xFF151515), surface = Color(0xFF1B1B1B),
                    surfaceVariant = Color(0xFF303030),
                    surfaceContainerHighest = Color(0xFF353535),
                ) else lightColorScheme(
                    primary = Color(0xFF333333), onPrimary = Color.White,
                    primaryContainer = Color(0xFFE4E4E4), onPrimaryContainer = Color(0xFF202020),
                    secondary = Color(0xFF505050), secondaryContainer = Color(0xFFEAEAEA),
                    onSecondaryContainer = Color(0xFF292929),
                    background = Color(0xFFF8F8F8), surface = Color(0xFFFAFAFA),
                    surfaceVariant = Color(0xFFEAEAEA),
                    surfaceContainerHighest = Color(0xFFE7E7E7),
                ),
            ) {
                LibraryScreen(
                    state = library.state.collectAsStateWithLifecycle().value,
                    onAllowPhotos = { permissions.launch(GalleryAccess.permissions()) },
                    onChooseFolder = { folder.launch(null) },
                    onRefresh = library::recheckPhotos,
                    onPermissionSettings = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    },
                    onSearch = library::search,
                    onPauseIndexing = library::pauseIndexing,
                    onResumeIndexing = library::resumeIndexing,
                    onAnalyzeClothing = library::analyzeClothing,
                    onFindSimilar = library::findSimilar,
                    onFindCopies = library::findCopies,
                    onClearSimilar = library::clearSimilar,
                    onOpen = library::open,
                    onAllowPhotoLocations = { photoLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) },
                    onClose = library::close,
                    onRemove = library::remove,
                    onEditSubjects = library::editSubjects,
                    onDismissSubjects = library::dismissSubjects,
                    onSaveSubject = library::confirmSubject,
                    onSelectSelf = library::selectSelf,
                    onRemoveSubject = library::removeSubject,
                    onRemoveDatedAlias = library::removeDatedAlias,
                    suggestions = SuggestionActions(library::openReview, library::closeReview, library::scanMoreSuggestions,
                        library::chooseSuggestion, library::chooseSuggestionGroup, library::skipSuggestion),
                    identity = IdentityActions(library::openMatchingQuery, library::closeIdentity, library::scanIdentity,
                        library::limitIdentity, library::confirmIdentity, library::skipIdentity,
                        library::rejectIdentity, library::undoIdentityRejection, library::editIdentityGroup,
                        library::undoIdentityGroupEdit, library::confirmIdentities),
                    loadImage = library::loadImage,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        library.startObserving()
    }

    override fun onResume() {
        super.onResume()
        library.refresh()
    }

    override fun onStop() {
        library.stopObserving()
        super.onStop()
    }
}
