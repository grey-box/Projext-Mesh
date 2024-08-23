package com.greybox.projectmesh.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.greybox.projectmesh.db.entities.User
import com.greybox.projectmesh.db.entities.UserMessage
import kotlinx.coroutines.flow.Flow


@Dao
interface UserDao {
    @Query("SELECT * FROM user")
    fun getAll(): List<User>

    @Query("SELECT * FROM user WHERE uuid = :uuid")
    fun getID(uuid: String): User

    @Query("SELECT * from user")
    fun getAllFlow(): Flow<List<User>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun update(user: User)

    @Query("SELECT EXISTS(SELECT * FROM user WHERE uuid = :uuid)")
    fun hasWithID(uuid: String): Boolean
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun initSelf(user: User)

    //@Update(entity = User::class)
    @Query("UPDATE user SET name = :newName WHERE uuid = :uuid")
    fun updateName(uuid: String,newName: String)

    // Get all messages from said user.
    @Query("SELECT user.uuid as uuid, user.name as name, message.content as content, message.dateReceived as dateReceived FROM user JOIN message ON user.uuid = message.sender WHERE user.uuid = :id ORDER BY dateReceived ASC")
    fun messagesFromUser(id: String): Flow<List<UserMessage>>

    @Query("SELECT user.uuid as uuid, user.name as name, message.content as content, message.dateReceived as dateReceived FROM user JOIN message ON user.uuid = message.sender ORDER BY dateReceived ASC")
    fun messagesFromAllUsers(): Flow<List<UserMessage>>
}
