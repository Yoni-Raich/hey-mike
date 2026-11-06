// SPDX-License-Identifier: Apache-2.0
package dev.heymike.connect

import android.os.Parcel
import android.os.Parcelable
import android.os.ParcelFileDescriptor
import android.os.BadParcelableException

class ConnectMessage(val json: String, val blobs: Array<ParcelFileDescriptor> = emptyArray()) : Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) { dest.writeString(json); dest.writeTypedArray(blobs, flags) }
    override fun describeContents() = if (blobs.isEmpty()) 0 else Parcelable.CONTENTS_FILE_DESCRIPTOR
    fun close() { blobs.forEach { runCatching { it.close() } } }
    companion object {
        @JvmField val CREATOR: Parcelable.Creator<ConnectMessage> = object : Parcelable.Creator<ConnectMessage> {
            override fun createFromParcel(source: Parcel): ConnectMessage {
                val json = source.readString().orEmpty()
                if (json.toByteArray().size > ConnectProtocol.MAX_JSON_BYTES) throw BadParcelableException("Connect message is too large")
                val count = source.readInt()
                // Check before allocating: even an unauthenticated caller reaches Parcelable decoding.
                if (count !in 0..4) throw BadParcelableException("Too many Connect binary parts")
                val parts = mutableListOf<ParcelFileDescriptor>()
                try {
                    repeat(count) { parts += source.readTypedObject(ParcelFileDescriptor.CREATOR) ?: throw BadParcelableException("Missing Connect binary part") }
                    return ConnectMessage(json, parts.toTypedArray())
                } catch (e: Exception) { parts.forEach { runCatching { it.close() } }; throw e }
            }
            override fun newArray(size: Int): Array<ConnectMessage?> = arrayOfNulls(size)
        }
    }
}
