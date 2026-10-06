package ru.fueltracker.app.data.repository

import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.api.ProfileApi
import ru.fueltracker.app.data.remote.apiCall
import ru.fueltracker.app.data.remote.dto.PasswordChangeRequest
import ru.fueltracker.app.data.remote.dto.UpdateMeRequest
import ru.fueltracker.app.data.remote.dto.UserDto
import ru.fueltracker.app.data.remote.map
import ru.fueltracker.app.domain.model.User
import javax.inject.Inject
import javax.inject.Singleton

interface ProfileRepository {

    /** PATCH /me; [name] — 1..100 символов. */
    suspend fun updateName(name: String): ApiResult<User>

    /** PUT /me/password; [currentPassword] — только при смене (пароль уже задан). */
    suspend fun changePassword(currentPassword: String?, newPassword: String): ApiResult<Unit>
}

@Singleton
class DefaultProfileRepository @Inject constructor(
    private val api: ProfileApi,
) : ProfileRepository {

    override suspend fun updateName(name: String): ApiResult<User> =
        apiCall { api.updateMe(UpdateMeRequest(name)) }.map { it.toDomain() }

    override suspend fun changePassword(currentPassword: String?, newPassword: String): ApiResult<Unit> =
        apiCall { api.changePassword(PasswordChangeRequest(currentPassword, newPassword)) }
}

private fun UserDto.toDomain() = User(id = id, phone = phone, name = name)
