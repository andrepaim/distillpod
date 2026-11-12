package dev.distillpod.android

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import dev.distillpod.android.sharing.*
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
        db.library().putDistillation(DistillationEntity("m","e",0,1000,"Small steps make practice easier.",quote="Small steps",state="succeeded"))
    }
    private fun assets()=PostAssets(java.nio.file.Files.createTempDirectory("post-test").toFile())
    private fun png():ByteArray {
        val image=Bitmap.createBitmap(1080,1350,Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it); image.recycle(); it.toByteArray() }
    }
    @Test fun `failed change and canceled stale completion retain the last version`()=runBlocking {
        val db=db()
        try {
            seed(db); val queued=mutableListOf<Pair<String,String>>(); val repo=SocialResultRepository(db) { id,a -> queued+=id to a }
            val id=repo.create("m","discussion"); val first=repo.get(id)!!
            val original=SocialArtifact(text="A thoughtful discussion.\n\nA second paragraph.",quote="Small steps")
            assertTrue(repo.update(id,first.attempt) { it.copy(stage="ready",artifact=original) })
            repo.change(id,"Make it shorter"); val second=repo.get(id)!!
            assertEquals(original,second.artifact)
            repo.cancel(id)
            assertFalse(repo.update(id,second.attempt) { it.copy(stage="ready",artifact=original.copy(text="Stale")) })
            assertEquals(original,repo.get(id)!!.artifact)
            repo.change(id,"Try again"); val third=repo.get(id)!!
            repo.update(id,third.attempt) { it.copy(stage="failed",error="No connection") }
            assertEquals(original,repo.get(id)!!.artifact)
            repo.editText(id,"An edited discussion."); repo.undo(id)
            assertEquals(original,repo.get(id)!!.artifact)
            db.library().deleteDistillation("m")
            assertNull(db.library().post(id)!!.sourceMomentId)
            assertEquals("Small steps make practice easier.",repo.get(id)!!.source.excerpt)
            assertEquals(3,queued.size)
        } finally { db.close() }
    }
    @Test fun `infographic backup roundtrips immutable assets and refuses missing bytes`()=runBlocking {
        val source=db(); val target=db()
        try {
            seed(source); val a=assets(); val b=assets(); val hash=a.put(png())
            val repo=SocialResultRepository(source) { _,_ -> }; val id=repo.create("m","infographic");val result=repo.get(id)!!
            repo.update(id,result.attempt) { it.copy(stage="ready",artifact=SocialArtifact(image=hash,title="Idea",description="Description")) }
            val bytes=LibraryBackup(source,a).export()
            assertEquals('P'.code.toByte(),bytes[0])
            val backup=LibraryBackup(target,b); val preview=backup.preview(bytes.inputStream())
            val directory=java.nio.file.Files.createTempDirectory("post-history").toFile()
            val file=File(directory,"snapshot.distillpod").apply {writeBytes(bytes)}
            val history=File(directory,"history.zip")
            BackupArchive.write(history,listOf(BackupArchive.snapshot(file,preview)))
            val restored=BackupArchive.read(history,File(directory,"extracted")).single()
            backup.merge(backup.preview(restored.file.inputStream()))
            assertEquals(hash,SocialResultRepository(target) { _,_ -> }.get(id)!!.artifact!!.image)
            assertArrayEquals(a.read(hash),b.read(hash))
            try { LibraryBackup(source).export(); fail("missing asset store accepted") } catch(_:IllegalArgumentException) {}
            val entries=PostBackupBundle.unpack(bytes)
            try { backup.preview(entries.first.inputStream()); fail("missing image accepted") } catch(_:IllegalArgumentException) {}
        } finally {source.close();target.close()}
    }
    @Test fun `restored active generation requires explicit retry`()=runBlocking {
        val source=db();val target=db()
        try {seed(source);val repo=SocialResultRepository(source) {_,_->};val id=repo.create("m","discussion")
            val backup=LibraryBackup(target);backup.merge(backup.preview(LibraryBackup(source).export().inputStream()))
            assertEquals("failed",SocialResultRepository(target) {_,_->}.get(id)!!.stage)
        } finally {source.close();target.close()}
    }
    @Test fun `discussion repairs invented quote once then fails without persisting`()=runBlocking {
        var calls=0
        val ai=object:AiGateway {override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit):AiText {calls++;return AiText("""{"quote":"Invented quote","discussion":"${"Discussion ".repeat(15)}\n\nConclusion."}""","test")}}
        val tools=object:SocialTools {override suspend fun research(question:String):WebEvidence=error("Must not research discussion")}
        try {SocialComposer(ai,tools).discussion(SocialResult(PostContent(excerpt="Actual quote"),"discussion"));fail()} catch(_:IllegalStateException) {}
        assertEquals(2,calls)
    }
    @Test fun `share uses plain text or a readable image URI with source description`()=runBlocking {
        val context=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        // Robolectric gives each test a new data directory, while FileProvider caches roots statically.
        (androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply {isAccessible=true}.get(null) as MutableMap<*,*>).clear()
        val assets=assets();val hash=assets.put(png())
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
    @Test fun `live evidence-rich brief renders readable sections without clipping`() {
        fun resource(name:String)=javaClass.classLoader!!.getResource(name)!!.readText()
        val sources=EvidenceSource.list(resource("social-learning-sources.json"))
        val brief=InfographicBrief.parse(resource("social-learning-brief.json"),sources)
        val image=InfographicRenderer.render(brief,resource("social-learning-illustration.json"),sources,"Public validation sample · Learning through retrieval")
        PostAssets.validate(image)
        File("/tmp/distillpod-social-infographic.png").writeBytes(image)
    }
    @Test fun `brief repair targets the previous overflow and preserves cited facts`()=runBlocking {
        val sources=listOf(EvidenceSource(1,"One","https://example.org/one"),EvidenceSource(2,"Two","https://example.org/two"))
        val good=JSONObject().put("title","Feedback").put("subtitle","Three ideas").put("relationship","comparison")
            .put("nodes",JSONArray((0..2).map {JSONObject().put("title","An idea").put("detail","A qualified claim.").put("sources",JSONArray().put(if(it==0) 1 else 2))}))
            .put("caveat","Context matters.").put("illustration","Three explanatory scenes.")
        val bad=JSONObject(good.toString());bad.getJSONArray("nodes").getJSONObject(1).put("detail","Overlong ".repeat(30))
        var calls=0
        val ai=object:AiGateway {override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit):AiText {
            calls++
            return when(calls) {
                1 -> AiText(bad.toString(),"fixture")
                2 -> {val repair=JSONObject(input);assertTrue(repair.getString("repairReason").contains("limit 150"));assertEquals(bad.toString(),repair.getString("invalidPreviousAnswer"));AiText(good.toString(),"fixture")}
                else -> AiText("{\"supported\":true}","fixture")
            }
        }}
        val tools=object:SocialTools {override suspend fun research(question:String):WebEvidence=error("Not used")}
        val (_,brief)=SocialComposer(ai,tools).brief(SocialResult(PostContent(excerpt="Feedback"),"infographic",research="Qualified evidence",researchSources=JSONArray(sources.map {it.json()}).toString()))
        assertEquals("A qualified claim.",brief.nodes[1].detail);assertEquals(3,calls)
    }
    @Test fun `brief review propagates service failures without sending a repair request`()=runBlocking {
        val sources=listOf(EvidenceSource(1,"One","https://example.org/one"),EvidenceSource(2,"Two","https://example.org/two"))
        val good=JSONObject().put("title","Feedback").put("subtitle","Three ideas").put("relationship","comparison")
            .put("nodes",JSONArray((0..2).map {JSONObject().put("title","An idea").put("detail","A qualified claim.").put("sources",JSONArray().put(if(it==0) 1 else 2))}))
            .put("caveat","Context matters.").put("illustration","Three explanatory scenes.")
        var calls=0
        val ai=object:AiGateway {override suspend fun complete(instructions:String,input:String,onDelta:(String)->Unit):AiText {
            calls++; if(calls==1) return AiText(good.toString(),"fixture"); throw AiServiceUnavailable(503)
        }}
        val tools=object:SocialTools {override suspend fun research(question:String):WebEvidence=error("Not used")}
        try { SocialComposer(ai,tools).brief(SocialResult(PostContent(excerpt="Feedback"),"infographic",research="Evidence",researchSources=JSONArray(sources.map {it.json()}).toString())); fail() }
        catch (_: AiServiceUnavailable) { }
        assertEquals(2,calls)
    }
    @Test fun `legacy text remains readable without an empty quote`() {
        val old=PostContent(body="An older draft")
        val result=SocialResult.parse(old.json())
        assertEquals("legacy",result.format); assertTrue(result.shareText().startsWith("An older draft")); assertFalse(result.shareText().contains("“”"))
    }
    @Test fun `brief rejects fabricated source IDs and invalid vector commands`() {
        val raw="""{"title":"Practice","subtitle":"Three related ideas","relationship":"comparison","nodes":[{"title":"One","detail":"Idea","sources":[1]},{"title":"Two","detail":"Idea","sources":[2]},{"title":"Three","detail":"Idea","sources":[99]}],"caveat":"Limits apply","illustration":"Concrete scenes"}"""
        try { InfographicBrief.parse(raw,listOf(EvidenceSource(1,"A","https://example.org/a"),EvidenceSource(2,"B","https://example.org/b")));fail() }catch(_:IllegalArgumentException){}
        try { VectorIllustrations.validate("""{"panels":[{"meaning":"A scene","shapes":[{"kind":"script"}]}]}""");fail() }catch(_:IllegalArgumentException){}
    }
    @Test fun `bundle refuses path traversal and duplicate entries`() {
        val bytes=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use {it.putNextEntry(java.util.zip.ZipEntry("../library.json"));it.write("{}".toByteArray());it.closeEntry()}
        try {PostBackupBundle.unpack(bytes.toByteArray());fail()}catch(_:IllegalArgumentException){}
    }
}
