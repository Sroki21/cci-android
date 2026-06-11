package pl.sroki.cci.android.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import pl.sroki.cci.android.data.datasource.local.CciDatabase
import pl.sroki.cci.android.data.datasource.local.dao.BinderDao
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.dao.PendingCapDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Singleton
    @Provides
    fun provideDatabase(@ApplicationContext context: Context): CciDatabase =
        Room.databaseBuilder(context, CciDatabase::class.java, "cci.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun providePendingCapDao(db: CciDatabase): PendingCapDao = db.pendingCapDao()

    @Provides
    fun provideBinderDao(db: CciDatabase): BinderDao = db.binderDao()

    @Provides
    fun provideBinderPageDao(db: CciDatabase): BinderPageDao = db.binderPageDao()

    @Provides
    fun provideCapPositionDao(db: CciDatabase): CapPositionDao = db.capPositionDao()
}
