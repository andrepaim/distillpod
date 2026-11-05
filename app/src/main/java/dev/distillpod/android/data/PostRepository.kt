package dev.distillpod.android.data

import androidx.room.*

/** Retained draft storage from the existing schema work; no publication side effects. */
@Entity(tableName = "post_drafts", foreignKeys = [ForeignKey(entity = DistillationEntity::class,
    parentColumns = ["id"], childColumns = ["sourceMomentId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("sourceMomentId")])
data class PostDraftEntity(@PrimaryKey val id: String, val sourceMomentId: String?, val content: String,
    val revision: Int, val updatedAt: Long)
