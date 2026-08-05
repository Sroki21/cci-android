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
import pl.sroki.cci.android.data.datasource.local.dao.CapCacheDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.dao.CountryFlagDao
import pl.sroki.cci.android.data.datasource.local.dao.PendingCapDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Singleton
    @Provides
    fun provideDatabase(@ApplicationContext context: Context): CciDatabase =
        Room.databaseBuilder(context, CciDatabase::class.java, "cci.db")
            .addMigrations(
                CciDatabase.MIGRATION_1_2,
                CciDatabase.MIGRATION_2_3,
                CciDatabase.MIGRATION_3_4,
                CciDatabase.MIGRATION_4_5,
                CciDatabase.MIGRATION_5_6,
                CciDatabase.MIGRATION_6_7,
                CciDatabase.MIGRATION_7_8,
                CciDatabase.MIGRATION_8_9
            )
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

    @Provides
    fun provideCapCacheDao(db: CciDatabase): CapCacheDao = db.capCacheDao()

    @Provides
    fun provideCountryFlagDao(db: CciDatabase): CountryFlagDao = db.countryFlagDao()
}
