package dev.distillpod.android.auto

import android.content.*
import android.database.Cursor
import android.graphics.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Only resized public cover images are exposed; no library, audio or transcript paths. */
class AutoArtwork(private val context:Context,private val http:OkHttpClient,private val scope:CoroutineScope,
    private val changed:()->Unit={}) {
    companion object {
        fun fileFor(context:Context,key:String):File? {
            if(key!="placeholder.jpg" && !key.matches(Regex("[a-f0-9]{64}\\.jpg")))return null
            return File(context.cacheDir,"auto-art/$key").takeIf {it.isFile && it.length()>0}
        }
    }
    private val active=ConcurrentHashMap.newKeySet<String>()
    private val permits=Semaphore(2)
    private val directory get()=File(context.cacheDir,"auto-art").apply {mkdirs()}
    private fun key(url:String)=MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)}+".jpg"
    fun file(key:String)=fileFor(context,key)
    private fun placeholder():File {
        val dest=File(directory,"placeholder.jpg")
        synchronized(AutoArtwork::class.java) {
            if(!dest.isFile) {
                val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(28,32,40))
                context.packageManager.getApplicationIcon(context.packageName).apply {setBounds(8,8,120,120);draw(canvas)}
                dest.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,80,it)};bitmap.recycle()
            }
        }
        return dest
    }
    fun uri(url:String?):Uri {
        val cache=url?.takeIf {it.startsWith("https://")||it.startsWith("http://")}?.let {file(key(it))}
        if(url!=null && cache==null && active.add(url))scope.launch(Dispatchers.IO) {
            try {warm(url)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){/* Placeholder stays usable. */}
            finally {active.remove(url)}
        }
        val chosen=cache?:placeholder()
        return Uri.Builder().scheme("content").authority(context.packageName+".auto.artwork").appendPath(chosen.name).build()
    }
    suspend fun warm(url:String)=withContext(Dispatchers.IO) {
        if(!url.startsWith("https://") && !url.startsWith("http://"))return@withContext
        permits.withPermit {
            val dest=File(directory,key(url));if(dest.isFile)return@withPermit
            http.newCall(Request.Builder().url(url).build()).execute().use {response->
                if(!response.isSuccessful)return@withPermit
                val body=response.body?:return@withPermit
                if(body.contentLength()>2*1024*1024)return@withPermit
                val bytes=body.byteStream().use {input->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(out.size()<=2*1024*1024) {
                        val count=input.read(buffer,0,minOf(buffer.size,2*1024*1024+1-out.size()))
                        if(count<0)break
                        out.write(buffer,0,count)
                    }
                    out.toByteArray()
                }
                if(bytes.size>2*1024*1024)return@withPermit
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth>32768 || bounds.outHeight>32768)return@withPermit
                val options=BitmapFactory.Options().apply {inSampleSize=maxOf(bounds.outWidth,bounds.outHeight).div(128).coerceAtLeast(1)}
                val source=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?:return@withPermit
                val bitmap=Bitmap.createScaledBitmap(source,128,128,true)
                val temp=File.createTempFile("cover-",".tmp",directory)
                try {
                    temp.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,80,it)}
                    if(!temp.renameTo(dest))return@withPermit
                } finally {temp.delete();if(bitmap!==source)bitmap.recycle();source.recycle()}
            }
            // Bound disk usage without affecting media downloads or other application caches.
            val files=directory.listFiles()?.filter {it.extension=="jpg" && it.name!="placeholder.jpg"}?.sortedBy {it.lastModified()}.orEmpty()
            var total=files.sumOf {it.length()}
            for(old in files) {if(total<=32*1024*1024)break;val length=old.length();if(old.delete())total-=length}
            changed()
        }
    }
}

class AutoArtworkProvider:ContentProvider() {
    override fun onCreate()=true
    override fun getType(uri:Uri)="image/jpeg"
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        if(mode!="r" || uri.pathSegments.size!=1)throw java.io.FileNotFoundException()
        val file=AutoArtwork.fileFor(requireNotNull(context),uri.lastPathSegment.orEmpty())?:throw java.io.FileNotFoundException()
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}
