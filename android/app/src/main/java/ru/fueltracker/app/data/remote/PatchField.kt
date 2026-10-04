package ru.fueltracker.app.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Поле PATCH-запроса, которое можно стереть (госномер, зимняя норма, пробег на конец и т. п.).
 * Контракт различает три случая:
 * - [Absent] — поле не отправляется, на сервере остаётся как было;
 * - `Present(value)` — новое значение;
 * - `Present(null)` — отправляется `null`, сервер стирает значение.
 *
 * Работает, только пока в [ApiJson] `encodeDefaults = false`: тогда поле со значением
 * по умолчанию [Absent] пропускается при сериализации.
 * Поля, которые стирать нельзя, в PATCH-запросах — обычные `T? = null` (null = не отправлять).
 */
@Serializable(with = PatchFieldSerializer::class)
sealed interface PatchField<out T : Any> {
    data object Absent : PatchField<Nothing>

    data class Present<T : Any>(val value: T?) : PatchField<T>
}

class PatchFieldSerializer<T : Any>(valueSerializer: KSerializer<T>) : KSerializer<PatchField<T>> {

    private val nullableSerializer = valueSerializer.nullable

    override val descriptor: SerialDescriptor = nullableSerializer.descriptor

    override fun serialize(encoder: Encoder, value: PatchField<T>) {
        when (value) {
            is PatchField.Present -> nullableSerializer.serialize(encoder, value.value)
            PatchField.Absent -> throw SerializationException(
                "PatchField.Absent нельзя сериализовать: нужен Json с encodeDefaults = false",
            )
        }
    }

    override fun deserialize(decoder: Decoder): PatchField<T> =
        PatchField.Present(nullableSerializer.deserialize(decoder))
}
