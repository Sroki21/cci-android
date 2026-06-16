package pl.sroki.cci.android.data.datasource.remote.firestore

import com.google.firebase.firestore.DocumentSnapshot
import pl.sroki.cci.android.data.model.CapSnapshot

internal fun DocumentSnapshot.toCapPositionDocument(): CapPositionDocument? {
    val snapshot = getString("capImageUrl")?.let {
        CapSnapshot(
            name = getString("capName") ?: "",
            country = getString("capCountry") ?: "",
            imageUrl = it,
            createdAt = getString("capCreatedAt"),
            createdById = getLong("capCreatedById")?.toInt(),
            updatedAt = getString("capUpdatedAt")
        )
    }
    return CapPositionDocument(
        firestoreId = id,
        binderPageFirestoreId = getString("binderPageFirestoreId") ?: return null,
        position = (getLong("position") ?: return null).toInt(),
        capId = getLong("capId") ?: return null,
        snapshot = snapshot
    )
}
