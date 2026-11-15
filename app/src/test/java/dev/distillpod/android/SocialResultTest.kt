package dev.distillpod.android

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.SocialFixtures.CARD
import dev.distillpod.android.SocialFixtures.EXCERPT
import dev.distillpod.android.SocialFixtures.cardArtifact
import dev.distillpod.android.SocialFixtures.png
import dev.distillpod.android.data.*
import dev.distillpod.android.sharing.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class SocialResultTest {
    @org.junit.After fun resetProviderCache() {
        (androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply {isAccessible=true}.get(null) as MutableMap<*,*>).clear()
    }
    private fun db()=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()
    private suspend fun seed(db:AppDatabase) {
        db.library().putPodcast(PodcastEntity("p","https://example.org/feed","Show"))
        db.library().putEpisode(EpisodeEntity("e","p","guid","Episode","https://example.org/audio"))
        db.library().putDistillation(DistillationEntity("m","e",0,1000,EXCERPT,quote="Small steps",state="succeeded"))
    }
    private fun assets()=PostAssets(java.nio.file.Files.createTempDirectory("post-test").toFile())
    /** Writes a result directly, as an earlier app version or a finished generation would have. */
    private suspend fun store(db:AppDatabase,id:String,result:SocialResult) {
        val row=db.library().post(id)!!
        db.library().putPost(row.copy(content=result.json(),revision=row.revision+1))
    }
    private suspend fun ready(repo:SocialResultRepository,id:String,artifact:SocialArtifact)=
        assertTrue(repo.update(id,repo.get(id)!!.attempt) { it.copy(stage="ready",artifact=artifact,previous=it.artifact) })
    private val discussion=SocialArtifact(text="A thoughtful discussion.\n\nA second paragraph.",quote="Small steps")

    @Test fun `failed change and canceled stale completion retain the last version`()=runBlocking {
        val db=db()
        try {
            seed(db); val queued=mutableListOf<Pair<String,String>>(); val repo=SocialResultRepository(db) { id,a -> queued+=id to a }
            val id=repo.create("m"); val first=repo.get(id)!!
            assertEquals(SocialResult.CARD,first.format); assertEquals(listOf(id to first.attempt),queued)
            val original=cardArtifact()
            assertTrue(repo.update(id,first.attempt) { it.copy(stage="ready",artifact=original) })
            repo.change(id,"Make it shorter"); val second=repo.get(id)!!
            assertEquals(original,second.artifact); assertEquals("queued",second.stage)
            repo.cancel(id)
            assertFalse(repo.update(id,second.attempt) { it.copy(stage="ready",artifact=original.copy(text="Stale")) })
            assertEquals(original,repo.get(id)!!.artifact)
            repo.change(id,"Try again"); val third=repo.get(id)!!
            repo.update(id,third.attempt) { it.copy(stage="failed",error="No connection") }
            assertEquals(original,repo.get(id)!!.artifact)
            repo.editText(id,"An edited caption."); repo.undo(id)
            assertEquals(original,repo.get(id)!!.artifact)
            db.library().deleteDistillation("m")
            assertNull(db.library().post(id)!!.sourceMomentId)
            assertEquals(EXCERPT,repo.get(id)!!.source.excerpt)
            assertEquals(3,queued.size)
        } finally { db.close() }
    }

    @Test fun `results of every format round trip and earlier rows without a card still parse`() {
        val source=PostContent(show="Show",episode="Episode",excerpt=EXCERPT,quote="Small steps")
        val image="a".repeat(64)
        val card=SocialResult(source,"card","ready",artifact=cardArtifact(CARD.copy(style=CardContent.STYLE_IDEA,look=CardContent.LOOK_SIGNAL)),previous=discussion)
        val parsed=SocialResult.parse(card.json())
        assertEquals(card,parsed); assertEquals(CARD.copy(style=CardContent.STYLE_IDEA,look=CardContent.LOOK_SIGNAL),parsed.artifact!!.cardContent())
        assertEquals(card.artifact!!.text,parsed.shareText())
        val sources=JSONArray().put(JSONObject().put("id",1).put("title","One").put("url","https://example.org/one")).toString()
        val infographic=SocialResult(source,"infographic","ready",artifact=SocialArtifact(image=image,title="Idea",description="Described",sources=sources),
            research="Evidence",researchSources=sources)
        val earlierDiscussion=SocialResult(source,"discussion","ready",artifact=discussion)
        for (earlier in listOf(infographic,earlierDiscussion)) {
            // Rows written before cards existed have no card key at all.
            val raw=JSONObject(earlier.json()).apply { getJSONObject("artifact").remove("card"); getJSONObject("source").remove("artworkUrl") }.toString()
            val restored=SocialResult.parse(raw)
            assertEquals(earlier,restored); assertNull(restored.artifact!!.cardContent())
            assertEquals(earlier,SocialResult.parse(restored.json()))
        }
        assertEquals("Described",infographic.shareText())
        assertEquals("“Small steps”\n\nA thoughtful discussion.\n\nA second paragraph.\n\nShow · Episode · 0:00",earlierDiscussion.shareText())
        val legacy=SocialResult.parse(PostContent(body="An older draft").json())
        assertEquals("legacy",legacy.format); assertTrue(legacy.shareText().startsWith("An older draft")); assertFalse(legacy.shareText().contains("“”"))
        try { SocialResult.parse(JSONObject(card.json()).put("format","essay").toString()); fail("unknown format accepted") } catch(_:IllegalArgumentException) {}
    }

    @Test fun `card quotes must come from the excerpt and stored cards must be drawable`() {
        val source=PostContent(excerpt=EXCERPT)
        fun parse(card:CardContent)=SocialResult.parse(SocialResult(source,"card","ready",artifact=cardArtifact(card).copy(quote=CARD.quote)).json())
        parse(CARD)
        for (bad in listOf(CARD.copy(pull="Practice is effortless."),CARD.copy(highlight="tiny"),CARD.copy(look=CardContent.LOOK_COVER),CARD.copy(style="carousel"),CARD.copy(takeaways=CARD.takeaways.take(1)))) {
            try { parse(bad); fail("accepted $bad") } catch(_:IllegalArgumentException) {}
        }
        try { SocialResult.parse(SocialResult(source,"card","ready",artifact=cardArtifact(CARD.copy(quote="Practice is effortless."))).json()); fail() } catch(_:IllegalArgumentException) {}
    }

    @Test fun `source snapshot records only usable artwork and older snapshots parse`()=runBlocking {
        assertEquals("",PostContent.parse(JSONObject(PostContent(excerpt="A").json()).apply { remove("artworkUrl") }.toString()).artworkUrl)
        assertEquals("https://cdn.test/a.png?w=600",postArtworkUrl(" https://cdn.test/a.png?w=600 "))
        for (bad in listOf(null,"","ftp://cdn.test/a.png","javascript:alert(1)","https://user:pass@cdn.test/a.png","https://cdn.test/${"a".repeat(2100)}.png"))
            assertEquals("",postArtworkUrl(bad))
        try { PostContent(artworkUrl="file:///data/a.png").validated(); fail() } catch(_:IllegalArgumentException) {}
        val db=db()
        try {
            db.library().putPodcast(PodcastEntity("p","https://example.org/feed","Show",imageUrl="https://example.org/show.png"))
            db.library().putEpisode(EpisodeEntity("e","p","guid","Episode","https://example.org/audio"))
            db.library().putEpisode(EpisodeEntity("e2","p","guid2","Episode 2","https://example.org/audio2",imageUrl="https://example.org/episode.png"))
            db.library().putDistillation(DistillationEntity("m","e",0,1000,EXCERPT,state="succeeded"))
            db.library().putDistillation(DistillationEntity("m2","e2",0,1000,EXCERPT,state="succeeded"))
            val repo=SocialResultRepository(db) { _,_ -> }
            assertEquals("https://example.org/show.png",repo.get(repo.create("m"))!!.source.artworkUrl)
            assertEquals("https://example.org/episode.png",repo.get(repo.create("m2"))!!.source.artworkUrl)
        } finally { db.close() }
    }

    @Test fun `restyle writes the current card in place and refuses what it cannot draw`()=runBlocking {
        val db=db()
        try {
            seed(db); val repo=SocialResultRepository(db) { _,_ -> }
            val id=repo.create("m")
            try { repo.restyle(id,CardContent.STYLE_IDEA,CardContent.LOOK_EMBER); fail("restyled while generating") } catch(_:IllegalArgumentException) {}
            ready(repo,id,cardArtifact())
            repo.change(id,"Shorter"); ready(repo,id,cardArtifact(CARD.copy(headline="Repeat small steps")))
            val before=repo.get(id)!!
            repo.restyle(id,CardContent.STYLE_TAKEAWAYS,CardContent.LOOK_SIGNAL)
            val after=repo.get(id)!!
            assertEquals(CARD.copy(headline="Repeat small steps",style=CardContent.STYLE_TAKEAWAYS,look=CardContent.LOOK_SIGNAL),after.artifact!!.cardContent())
            assertEquals(before.previous,after.previous); assertEquals(before.artifact!!.text,after.artifact!!.text)
            try { repo.restyle(id,CardContent.STYLE_QUOTE,CardContent.LOOK_COVER); fail("cover look without artwork") } catch(_:IllegalArgumentException) {}
            try { repo.restyle(id,"carousel",CardContent.LOOK_EMBER); fail("unknown style") } catch(_:IllegalArgumentException) {}
            val withCover=CARD.copy(cover="b".repeat(64),tint=0xFF336699.toInt())
            repo.change(id,"Again"); ready(repo,id,cardArtifact(withCover))
            repo.restyle(id,CardContent.STYLE_IDEA,CardContent.LOOK_COVER)
            assertEquals(CardContent.LOOK_COVER,repo.get(id)!!.artifact!!.cardContent()!!.look)
            repo.change(id,"Once more"); ready(repo,id,discussion)
            try { repo.restyle(id,CardContent.STYLE_IDEA,CardContent.LOOK_EMBER); fail("restyled a text post") } catch(_:IllegalArgumentException) {}
        } finally { db.close() }
    }

    @Test fun `undo returns a retried earlier result to its earlier artifact`()=runBlocking {
        val db=db()
        try {
            seed(db); val a=assets(); val hash=a.put(png(1080,1350)); val repo=SocialResultRepository(db) { _,_ -> }
            val id=repo.create("m")
            val infographic=SocialArtifact(image=hash,title="Idea",description="Description and sources")
            store(db,id,repo.get(id)!!.copy(format="infographic",stage="failed",artifact=infographic,error="Interrupted"))
            try { repo.editText(id,"New text"); fail("edited an infographic") } catch(_:IllegalArgumentException) {}
            repo.change(id,""); assertEquals(SocialResult.CARD,repo.get(id)!!.format)
            ready(repo,id,cardArtifact())
            assertEquals(infographic,repo.get(id)!!.previous)
            repo.undo(id)
            val restored=repo.get(id)!!
            assertEquals(infographic,restored.artifact); assertEquals(SocialResult.CARD,restored.format)
            assertEquals("Description and sources",restored.shareText())
            assertEquals(restored,SocialResult.parse(restored.json()))
        } finally { db.close() }
    }

    @Test fun `caption edits keep the card and can be undone`()=runBlocking {
        val db=db()
        try {
            seed(db); val repo=SocialResultRepository(db) { _,_ -> }
            val id=repo.create("m"); val original=cardArtifact(); ready(repo,id,original)
            repo.editText(id,"My own caption.\n\n#habits")
            val edited=repo.get(id)!!
            assertEquals("My own caption.\n\n#habits",edited.artifact!!.text); assertEquals("My own caption.\n\n#habits",edited.shareText())
            assertEquals(original.card,edited.artifact!!.card); assertEquals(original,edited.previous)
            repo.undo(id); assertEquals(original,repo.get(id)!!.artifact)
        } finally { db.close() }
    }

    @Test fun `post assets accept infographics and covers only`() {
        val store=assets()
        for ((w,h) in listOf(1080 to 1350,CardContent.COVER_PX to CardContent.COVER_PX)) {
            val bytes=png(w,h); val hash=store.put(bytes)
            assertEquals(PostAssets.hash(bytes),hash); assertArrayEquals(bytes,store.read(hash)); assertEquals(hash,store.put(bytes))
        }
        for (bytes in listOf(png(1080,1080),png(CardContent.COVER_PX,CardContent.COVER_PX+1),png(CardContent.COVER_PX,CardContent.COVER_PX,Bitmap.CompressFormat.JPEG),ByteArray(0),"not an image".toByteArray())) {
            try { store.put(bytes); fail("accepted an invalid image") } catch(_:IllegalArgumentException) {}
        }
    }

    @Test fun `backup roundtrips card covers and infographic images and refuses a bundle missing referenced bytes`()=runBlocking {
        val source=db(); val target=db()
        try {
            seed(source); val a=assets(); val b=assets()
            val image=a.put(png(1080,1350)); val cover=a.put(png(CardContent.COVER_PX,CardContent.COVER_PX)); val oldCover=a.put(SocialFixtures.bitmap(CardContent.COVER_PX,CardContent.COVER_PX,0xFF2266AA.toInt()).let { bitmap ->
                java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() } })
            val repo=SocialResultRepository(source) { _,_ -> }
            val infographicId=repo.create("m")
            store(source,infographicId,repo.get(infographicId)!!.copy(format="infographic",stage="ready",artifact=SocialArtifact(image=image,title="Idea",description="Description")))
            val cardId=repo.create("m")
            ready(repo,cardId,cardArtifact(CARD.copy(cover=oldCover,tint=0xFF2266AA.toInt(),look=CardContent.LOOK_COVER)))
            repo.change(cardId,"Shorter"); ready(repo,cardId,cardArtifact(CARD.copy(cover=cover,tint=0xFFC0392B.toInt())))
            val bytes=LibraryBackup(source,a).export()
            assertEquals('P'.code.toByte(),bytes[0])
            val backup=LibraryBackup(target,b); val preview=backup.preview(bytes.inputStream())
            assertEquals(setOf(image,cover,oldCover),preview.assets.keys)
            val directory=java.nio.file.Files.createTempDirectory("post-history").toFile()
            val file=File(directory,"snapshot.distillpod").apply {writeBytes(bytes)}
            val history=File(directory,"history.zip")
            BackupArchive.write(history,listOf(BackupArchive.snapshot(file,preview)))
            val restored=BackupArchive.read(history,File(directory,"extracted")).single()
            backup.merge(backup.preview(restored.file.inputStream()))
            val targetRepo=SocialResultRepository(target) { _,_ -> }
            assertEquals(image,targetRepo.get(infographicId)!!.artifact!!.image)
            assertEquals(cover,targetRepo.get(cardId)!!.artifact!!.cardContent()!!.cover)
            assertEquals(oldCover,targetRepo.get(cardId)!!.previous!!.cardContent()!!.cover)
            for (hash in listOf(image,cover,oldCover)) assertArrayEquals(a.read(hash),b.read(hash))
            try { LibraryBackup(source).export(); fail("missing asset store accepted") } catch(_:IllegalArgumentException) {}
            val entries=PostBackupBundle.unpack(bytes)
            // A bundle whose library still names images it does not carry is refused, by this and earlier versions.
            try { backup.preview(entries.first.inputStream()); fail("missing images accepted") } catch(_:IllegalArgumentException) {}
        } finally {source.close();target.close()}
    }

    @Test fun `unreadable post images are left out of the backup and their posts restore without them`()=runBlocking {
        val source=db(); val target=db()
        try {
            seed(source); val a=assets(); val b=assets()
            val image=a.put(png(1080,1350)); val cover=a.put(png(CardContent.COVER_PX,CardContent.COVER_PX))
            val oldCover=a.put(SocialFixtures.bitmap(CardContent.COVER_PX,CardContent.COVER_PX,0xFF2266AA.toInt()).let { bitmap ->
                java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() } })
            val repo=SocialResultRepository(source) { _,_ -> }
            val infographicId=repo.create("m")
            store(source,infographicId,repo.get(infographicId)!!.copy(format="infographic",stage="ready",
                artifact=SocialArtifact(quote="Small steps",image=image,title="Idea",description="Description and sources")))
            val cardId=repo.create("m")
            ready(repo,cardId,cardArtifact(CARD.copy(cover=oldCover,tint=0xFF2266AA.toInt(),look=CardContent.LOOK_COVER)))
            repo.change(cardId,"Shorter"); ready(repo,cardId,cardArtifact(CARD.copy(cover=cover,tint=0xFFC0392B.toInt(),look=CardContent.LOOK_COVER)))
            val sourceBefore=source.library().observePosts().first().associate { it.id to it.content }
            // The current card's cover file is gone and the infographic file no longer matches its hash.
            assertTrue(a.file(cover).delete())
            a.file(image).writeBytes(png(1080,1350).also { it[it.size/2]=(it[it.size/2]+1).toByte() })

            val export=LibraryBackup(source,a).exportWithReport()
            assertEquals(2,export.skippedImages)
            assertEquals(listOf("Episode","Episode"),export.affectedPosts)
            val warning=export.warning!!
            assertTrue(warning,warning.contains("2 post images") && warning.contains("Episode"))
            assertFalse(warning,warning.contains("file provider"))
            // The library itself is never changed by a backup.
            assertEquals(sourceBefore,source.library().observePosts().first().associate { it.id to it.content })

            // Self-consistent: the library names only the images the bundle carries, so earlier versions restore it too.
            val (json,images)=PostBackupBundle.unpack(export.bytes)
            assertEquals(setOf(oldCover),images.keys)
            assertFalse(String(json).contains(cover)); assertFalse(String(json).contains(image))
            val backup=LibraryBackup(target,b); val preview=backup.preview(export.bytes.inputStream())
            assertEquals(setOf(oldCover),preview.assets.keys)
            backup.merge(preview)

            val targetRepo=SocialResultRepository(target) { _,_ -> }
            val card=targetRepo.get(cardId)!!
            val current=card.artifact!!.cardContent()!!
            assertEquals("",current.cover); assertEquals(0,current.tint); assertEquals(CardContent.LOOK_EMBER,current.look)
            assertEquals(CARD.headline,current.headline)
            assertEquals(oldCover,card.previous!!.cardContent()!!.cover)
            assertEquals(CardContent.LOOK_COVER,card.previous!!.cardContent()!!.look)
            assertArrayEquals(a.read(oldCover),b.read(oldCover))
            // The earlier infographic keeps its words as a text post.
            val infographic=targetRepo.get(infographicId)!!
            assertEquals("",infographic.artifact!!.image)
            assertEquals("Description and sources",infographic.artifact!!.text)
            assertEquals("Idea",infographic.artifact!!.title)
            assertEquals("Earlier text post",dev.distillpod.android.features.postKind(infographic))
            assertTrue(infographic.shareText().contains("Description and sources"))

            // Nothing unreadable: no report, and the export is the same as before.
            assertNull(LibraryBackup(target,b).exportWithReport().warning)
        } finally {source.close();target.close()}
    }

    @Test fun `earlier backups without covers restore unchanged`()=runBlocking {
        val source=db(); val target=db()
        try {
            seed(source); val repo=SocialResultRepository(source) { _,_ -> }
            val id=repo.create("m")
            store(source,id,repo.get(id)!!.copy(format="discussion",stage="ready",artifact=discussion))
            // An export from before cards: no card or artworkUrl keys in the stored post.
            val row=source.library().post(id)!!
            val old=JSONObject(row.content).apply { getJSONObject("artifact").remove("card"); getJSONObject("source").remove("artworkUrl") }.toString()
            source.library().putPost(row.copy(content=old))
            val bytes=LibraryBackup(source).export()
            assertEquals('{'.code.toByte(),bytes[0])
            val backup=LibraryBackup(target,assets()); backup.merge(backup.preview(bytes.inputStream()))
            assertEquals(old,target.library().post(id)!!.content)
            assertEquals(discussion,SocialResultRepository(target) { _,_ -> }.get(id)!!.artifact)
        } finally {source.close();target.close()}
    }

    @Test fun `restored active generation requires explicit retry`()=runBlocking {
        val source=db();val target=db()
        try {seed(source);val repo=SocialResultRepository(source) {_,_->};val id=repo.create("m")
            val backup=LibraryBackup(target);backup.merge(backup.preview(LibraryBackup(source).export().inputStream()))
            assertEquals("failed",SocialResultRepository(target) {_,_->}.get(id)!!.stage)
        } finally {source.close();target.close()}
    }

    @Test fun `share uses plain text or a readable image URI with source description`()=runBlocking {
        val context=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        // Robolectric gives each test a new data directory, while FileProvider caches roots statically.
        (androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply {isAccessible=true}.get(null) as MutableMap<*,*>).clear()
        val assets=assets();val hash=assets.put(png(1080,1350))
        val result=SocialResult(PostContent(excerpt="Small steps"),"infographic","ready",artifact=SocialArtifact(image=hash,description="Sources and accessible description"))
        PostShareController.shareResult(context,assets,result)
        val chooser=org.robolectric.Shadows.shadowOf(context).nextStartedActivity
        val intent=chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals("image/png",intent.type)
        assertEquals("Sources and accessible description",intent.getStringExtra(android.content.Intent.EXTRA_TEXT))
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri=intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)!!
        assertArrayEquals(assets.read(hash),context.contentResolver.openInputStream(uri)!!.use {it.readBytes()})
    }

    @Test fun `bundle refuses path traversal and duplicate entries`() {
        val bytes=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use {it.putNextEntry(java.util.zip.ZipEntry("../library.json"));it.write("{}".toByteArray());it.closeEntry()}
        try {PostBackupBundle.unpack(bytes.toByteArray());fail()}catch(_:IllegalArgumentException){}
    }
}
