package com.readboy.control.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MirrorControlItem::class, MirrorUserInfo::class, MirrorSwitchItem::class, MirrorMeta::class],
    version = 1,
    exportSchema = false
)
abstract class MirrorDatabase : RoomDatabase() {

    abstract fun controlListDao(): ControlListDao
    abstract fun userInfoDao(): UserInfoDao
    abstract fun switchDao(): SwitchDao
    abstract fun metaDao(): MetaDao

    companion object {
        @Volatile
        private var INSTANCE: MirrorDatabase? = null

        fun getInstance(context: Context): MirrorDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MirrorDatabase::class.java,
                    "zaralyn_control.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}