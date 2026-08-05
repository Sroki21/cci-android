package pl.sroki.cci.android.data

import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.local.entity.CapCache
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition
import pl.sroki.cci.android.model.binder.BinderPageView
import pl.sroki.cci.android.model.binder.BinderView
import pl.sroki.cci.android.model.binder.CachedCap
import pl.sroki.cci.android.model.binder.CapSlot

/**
 * Jedyne miejsce, które zna oba kształty: encje Room i modele domenowe. Repozytoria mapują
 * tutaj na granicy, dzięki czemu UI nie importuje niczego z `datasource.local.entity` —
 * zmiana schematu bazy nie pociąga za sobą zmian w ekranach.
 */
internal fun Binder.toView() = BinderView(id = id, name = name)

internal fun BinderPage.toView() = BinderPageView(
    id = id,
    binderId = binderId,
    pageNumber = pageNumber
)

internal fun CapPosition.toSlot() = CapSlot(
    id = id,
    binderPageId = binderPageId,
    position = position,
    capId = capId
)

internal fun CapCache.toCachedCap() = CachedCap(
    capId = capId,
    name = name,
    country = country,
    imageUrl = imageUrl,
    createdAt = createdAt,
    createdById = createdById,
    updatedAt = updatedAt,
    catalogStatus = catalogStatus,
    selectedProducerId = selectedProducerId,
    producer = producer
)
