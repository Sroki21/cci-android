package pl.sroki.cci.android.model

import android.util.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

// API zwraca false (JSON boolean) dla niezalogowanych i dla kapsli nieposiadanych,
// lub Int (ID wpisu kolekcji) dla kapsli posiadanych przez zalogowanego użytkownika.
// false/0 → nie w kolekcji; każdy inny int → w kolekcji.
object IsInCollectionSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("IsInCollection", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonPrimitive) return false
        val boolValue = element.booleanOrNull
        if (boolValue != null) return boolValue
        val intValue = element.intOrNull
        if (intValue != null) {
            Log.d("CCI_COLLECTION", "isInCollection raw=$intValue inCollection=${intValue != 0}")
            return intValue != 0
        }
        return false
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}
