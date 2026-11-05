package dev.distillpod.android.data

import dev.distillpod.android.sharing.PostAssets
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.*

/** Optional binary wrapper; old JSON backups remain readable. Total expanded data is capped at 32 MB. */
internal object PostBackupBundle {
    private const val LIMIT = 32 * 1024 * 1024
    fun references(tables: JSONObject): Set<String> {
        val posts = tables.optJSONArray("post_drafts") ?: return emptySet()
        return (0 until posts.length()).flatMap { i ->
            val result=SocialResult.parse(posts.getJSONObject(i).getString("content"))
            listOfNotNull(result.artifact,result.previous).map { it.image }.filter(String::isNotBlank)
        }.toSet()
    }
    fun pack(json: ByteArray, hashes: Set<String>, assets: PostAssets?): ByteArray {
        if(hashes.isEmpty()) return json
        require(hashes.size <= 100) { "This backup exceeds the infographic limit" }
        require(assets != null) { "Infographic files are unavailable for backup" }
        var total=json.size
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("library.json")); zip.write(json); zip.closeEntry()
            hashes.sorted().forEach { hash ->
                val bytes=assets.read(hash); total+=bytes.size
                require(total<=LIMIT) { "Library and infographics exceed the 32 MB backup limit" }
                zip.putNextEntry(ZipEntry("images/$hash.png")); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray().also { require(it.size<=LIMIT) }
    }
    fun unpack(bytes: ByteArray): Pair<ByteArray,Map<String,ByteArray>> {
        if(bytes.size<4 || bytes[0]!=80.toByte() || bytes[1]!=75.toByte()) return bytes to emptyMap()
        val entries=linkedMapOf<String,ByteArray>(); var total=0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while(true) {
                val entry=zip.nextEntry ?: break
                require(!entry.isDirectory && entries.size<101 && entry.name !in entries)
                require(entry.name=="library.json" || entry.name.matches(Regex("images/[a-f0-9]{64}\\.png"))) { "Unexpected backup entry" }
                val output=ByteArrayOutputStream(); val block=ByteArray(65536)
                while(true) { val n=zip.read(block); if(n<0) break; total+=n; require(total<=LIMIT) { "Expanded backup exceeds 32 MB" }; output.write(block,0,n) }
                val content=output.toByteArray()
                if(entry.name!="library.json") { PostAssets.validate(content); require(PostAssets.hash(content)==entry.name.removePrefix("images/").removeSuffix(".png")) { "Image integrity check failed" } }
                entries[entry.name]=content; zip.closeEntry()
            }
        }
        val json=entries.remove("library.json") ?: error("Backup is missing its library")
        return json to entries.mapKeys { it.key.removePrefix("images/").removeSuffix(".png") }
    }
}
