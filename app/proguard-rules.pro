# Keep Room entities
-keep class com.readboy.control.db.** { *; }

# Keep Room database
-keep class * extends androidx.room.RoomDatabase { *; }

# Keep Gson serialization
-keep class com.readboy.control.network.CloudSyncEngine.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}