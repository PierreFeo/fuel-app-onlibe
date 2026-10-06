package ru.fueltracker.app.data.remote

/** Ответы сервера в точности по примерам из 04_API_CONTRACT.md. */
object Fixtures {

    val user = """{ "id": "0f9e8d7c-6b5a-4938-2716-05f4e3d2c1b0", "phone": "+79991234567", "name": null }"""

    val tokens = """
        {
          "access_token": "access.jwt", "refresh_token": "refresh.jwt",
          "token_type": "bearer", "expires_in_sec": 900,
          "user": $user,
          "is_new_user": true
        }
    """

    fun error(code: String, message: String, details: String = "{}") =
        """{ "error": { "code": "$code", "message": "$message", "details": $details } }"""
}
