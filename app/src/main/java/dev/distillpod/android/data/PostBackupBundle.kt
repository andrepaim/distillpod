package dev.distillpod.android.data

import dev.distillpod.android.sharing.PostAssets
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.*

/** Optional binary wrapper; old JSON backups remain readable. Total expanded data is capped at 32 MB. */
internal object PostBackupBundle {
    private const val LIMIT = 32 * 1024 * 1024
    // Covers are small and shared by posts of one show; the byte limit is the real bound.
    private const val MAX_IMAGES = 500

    /** The post images a backup carries, and the posts whose unreadable images were left out. */
    class Images(val bytes: Map<String, ByteArray>, val skipped: Int, val affectedPosts: List<String>)

    /** Earlier infographic images and the covers of current and previous cards. */
    fun references(tables: JSONObject): Set<String> {
        val posts = tables.optJSONArray("post_drafts") ?: return emptySet()
        return (0 until posts.length()).flatMap { i ->
            val result=SocialResult.parse(posts.getJSONObject(i).getString("content"))
            listOfNotNull(result.artifact,result.previous).flatMap { listOf(it.image, it.cardContent()?.cover.orEmpty()) }.filter(String::isNotBlank)
        }.toSet()
    }

    /**
     * Reads every image the posts in [tables] reference. An image that is missing, damaged or no longer matches
     * its hash is left out and removed from the exported posts in [tables], so the backup stays self-consistent
     * and restorable by every version; the library itself is not changed. See [withoutImages].
     */
    fun collect(tables: JSONObject, assets: PostAssets?): Images {
        val hashes = references(tables)
        if(hashes.isEmpty()) return Images(emptyMap(), 0, emptyList())
        require(hashes.size <= MAX_IMAGES) { "This backup exceeds the post image limit" }
        require(assets != null) { "Post images are unavailable for backup" }
        val images = linkedMapOf<String,ByteArray>(); val unreadable = mutableSetOf<String>()
        var total = 0
        hashes.sorted().forEach { hash ->
            val bytes = try { assets.read(hash) } catch(_: Exception) { unreadable += hash; return@forEach }
            total += bytes.size
            require(total<=LIMIT) { "Library and post images exceed the 32 MB backup limit" }
            images[hash] = bytes
        }
        return Images(images, unreadable.size, if(unreadable.isEmpty()) emptyList() else withoutImages(tables, unreadable))
    }

    /**
     * Rewrites the exported posts that reference [unreadable] images: a card loses its cover and tint, and the
     * Cover look becomes Ember; an earlier infographic becomes a text post keeping its description. Returns one
     * label (the episode name) per changed post.
     */
    private fun withoutImages(tables: JSONObject, unreadable: Set<String>): List<String> {
        val posts = tables.getJSONArray("post_drafts")
        val affected = mutableListOf<String>()
        for(i in 0 until posts.length()) {
            val row = posts.getJSONObject(i)
            val result = SocialResult.parse(row.getString("content"))
            fun repaired(artifact: SocialArtifact?): SocialArtifact? {
                if(artifact == null) return null
                var changed = artifact
                if(artifact.image in unreadable) changed = changed.copy(image = "", text = artifact.text.ifBlank { artifact.description })
                val card = artifact.cardContent()
                if(card != null && card.cover in unreadable) changed = changed.copy(card = card.copy(cover = "", tint = 0,
                    look = if(card.look == CardContent.LOOK_COVER) CardContent.LOOK_EMBER else card.look).json().toString())
                return changed
            }
            val artifact = repaired(result.artifact); val previous = repaired(result.previous)
            if(artifact == result.artifact && previous == result.previous) continue
            // An infographic must carry its image; one that lost it is an earlier text post.
            val format = if(result.format == "infographic" && listOfNotNull(artifact, previous).any { it.image.isEmpty() }) "discussion" else result.format
            val content = result.copy(format = format, artifact = artifact, previous = previous).json()
            SocialResult.parse(content)
            row.put("content", content)
            affected += result.source.episode.ifBlank { result.source.show }.ifBlank { "Untitled post" }
        }
        return affected
    }

    fun pack(json: ByteArray, images: Map<String, ByteArray>): ByteArray {
        if(images.isEmpty()) return json
        var total=json.size
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("library.json")); zip.write(json); zip.closeEntry()
            images.toSortedMap().forEach { (hash, bytes) ->
                total+=bytes.size
                require(total<=LIMIT) { "Library and post images exceed the 32 MB backup limit" }
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
                require(!entry.isDirectory && entries.size<=MAX_IMAGES && entry.name !in entries)
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
