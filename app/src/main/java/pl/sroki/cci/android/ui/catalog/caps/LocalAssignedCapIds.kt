package pl.sroki.cci.android.ui.catalog.caps

import androidx.compose.runtime.compositionLocalOf

val LocalAssignedCapIds = compositionLocalOf<Set<Long>> { emptySet() }
