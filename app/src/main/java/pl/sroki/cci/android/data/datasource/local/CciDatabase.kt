package pl.sroki.cci.android.data.datasource.local

import androidx.room.Database
import androidx.room.RoomDatabase
import pl.sroki.cci.android.data.datasource.local.dao.BinderDao
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.dao.PendingCapDao
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition
import pl.sroki.cci.android.data.datasource.local.entity.PendingCap

@Database(
    entities = [PendingCap::class, Binder::class, BinderPage::class, CapPosition::class],
    version = 1,
    exportSchema = false
)
abstract class CciDatabase : RoomDatabase() {
    abstract fun pendingCapDao(): PendingCapDao
    abstract fun binderDao(): BinderDao
    abstract fun binderPageDao(): BinderPageDao
    abstract fun capPositionDao(): CapPositionDao
}
