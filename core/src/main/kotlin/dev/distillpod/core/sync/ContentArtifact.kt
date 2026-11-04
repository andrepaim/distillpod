package dev.distillpod.core.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class ContentArtifact(val schema:Int,val hash:String,val expandedBytes:Long,val compressed:ByteArray)
object ContentArtifacts {
    const val MAX_EXPANDED=32*1024*1024
    const val MAX_COMPRESSED=8*1024*1024
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun encode(content:ByteArray):ContentArtifact {
        require(content.size in 1..MAX_EXPANDED)
        val output=ByteArrayOutputStream()
        GZIPOutputStream(output).use {it.write(content)}
        val compressed=output.toByteArray();require(compressed.size<=MAX_COMPRESSED)
        return ContentArtifact(1,hash(content),content.size.toLong(),compressed)
    }
    fun decode(artifact:ContentArtifact):ByteArray {
        require(artifact.schema==1 && artifact.expandedBytes in 1..MAX_EXPANDED.toLong())
        require(artifact.hash.matches(Regex("[a-f0-9]{64}")) && artifact.compressed.size in 1..MAX_COMPRESSED)
        val output=ByteArrayOutputStream(minOf(artifact.expandedBytes,65536).toInt())
        try {
            GZIPInputStream(ByteArrayInputStream(artifact.compressed)).use {input->
                val buffer=ByteArray(8192)
                while(true) {
                    val count=input.read(buffer);if(count<0)break
                    require(output.size().toLong()+count<=artifact.expandedBytes) {"Artifact exceeds its declared expansion budget"}
                    output.write(buffer,0,count)
                }
            }
        } catch(error:java.io.IOException){throw IllegalArgumentException("Invalid compressed artifact",error)}
        val bytes=output.toByteArray()
        require(bytes.size.toLong()==artifact.expandedBytes && hash(bytes)==artifact.hash) {"Artifact integrity check failed"}
        return bytes
    }
}
