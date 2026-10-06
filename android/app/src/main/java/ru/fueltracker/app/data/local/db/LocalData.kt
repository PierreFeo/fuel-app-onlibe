package ru.fueltracker.app.data.local.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Данные в Room целиком: есть ли что-то и стереть всё (при выходе). Интерфейс — для тестов без Room. */
interface LocalData {

    /** На телефоне нет ни одной записи (в том числе удалённых, но не отправленных). */
    suspend fun isEmpty(): Boolean

    suspend fun clearAll()
}

@Singleton
class RoomLocalData @Inject constructor(
    private val db: AppDatabase,
) : LocalData {

    override suspend fun isEmpty(): Boolean = db.syncDao().totalCount() == 0

    // clearAllTables — блокирующий вызов, на главном потоке Room его запрещает
    override suspend fun clearAll() = withContext(Dispatchers.IO) { db.clearAllTables() }
}
