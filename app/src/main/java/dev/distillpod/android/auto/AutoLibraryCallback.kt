package dev.distillpod.android.auto

import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.Player
import android.os.Bundle
import android.content.Intent
import dev.distillpod.android.playback.HeadphoneDistillButton
import dev.distillpod.android.playback.PlaybackControls
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.distillpod.android.data.AppDatabase
import dev.distillpod.android.playback.mediaItem
import kotlinx.coroutines.*
import java.util.concurrent.Executor

/** Library work never blocks the player's application looper. The owning service cancels requests. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AutoLibraryCallback(
    private val packageName: String,
    private val db: AppDatabase,
    private val ready: Deferred<Unit>,
    private val scope: CoroutineScope,
    private val playbackReady: Deferred<Unit> = ready,
    private val saveMoment: suspend (String,Long,String?) -> String = {_,_,_->throw IllegalStateException("Capture is unavailable")},
    private val prepareMoment: (String)->Unit = {},
    artwork: ((String?)->android.net.Uri)?=null,
    private val voiceEnabled: () -> Boolean = { false },
    private val openCarVoice: () -> Boolean = { false },
    private val onExternalPlaybackCommand: () -> Unit = {},
    private val headphoneDistillEnabled: () -> Boolean = { false },
    private val onHeadphoneCaptureFailure: () -> Unit = {},
    private val isTrustedMediaButtonCaller: (MediaSession.ControllerInfo) -> Boolean = { false },
    private val headphoneVoiceEnabled: () -> Boolean = { false },
    private val onHeadphoneVoice: () -> Unit = {},
    private val diagnostics: dev.distillpod.android.diagnostics.Diagnostics? = null,
    private val saveMomentWithSource: (suspend (String,Long,String?,String)->String)? = null,
): MediaLibrarySession.Callback {
    override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
        if (controller.packageName != packageName && playerCommand in setOf(Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) onExternalPlaybackCommand()
        return super.onPlayerCommandRequest(session, controller, playerCommand)
    }
    companion object { const val DISTILL="dev.distillpod.android.DISTILL_MOMENT" }
    private val catalog=AutoCatalog(db,artwork)
    private val playback=AutoPlayback(db)
    private val subscriptions=mutableSetOf<String>()
    private var captureBusy=false
    private val headphoneButton = HeadphoneDistillButton(report={ reason -> diagnostics?.event("headphone.previous",reason=reason,source="headphones") })
    private val headphoneNext = HeadphoneDistillButton(android.view.KeyEvent.KEYCODE_MEDIA_NEXT,report={reason -> diagnostics?.event("headphone.next",reason=reason,source="headphones")})
    private fun layout(label:String="Distill last 60 seconds",enabled:Boolean=true,includeVoice:Boolean=voiceEnabled())=listOf(
        PlaybackControls.buttons[0],
        CommandButton.Builder(CommandButton.ICON_UNDEFINED).setCustomIconResId(dev.distillpod.android.R.drawable.ic_media_distill)
            .setDisplayName(label).setEnabled(enabled).setSessionCommand(PlaybackControls.distill).setSlots(CommandButton.SLOT_FORWARD).build(),
        PlaybackControls.buttons[2]) + if (includeVoice) listOf(
            CommandButton.Builder(CommandButton.ICON_UNDEFINED).setCustomIconResId(dev.distillpod.android.R.drawable.ic_media_ask)
                .setDisplayName("Ask about this").setSessionCommand(dev.distillpod.android.voice.CarVoiceEntry.ASK)
                .setSlots(CommandButton.SLOT_OVERFLOW).build()) else emptyList()
    fun playbackLayout() = layout()
    private fun publishCarLayout(session:MediaSession,label:String,enabled:Boolean) {
        for(controller in session.connectedControllers)if(!session.isMediaNotificationController(controller)) {
            session.setCustomLayout(controller,layout(label,enabled))
            session.setMediaButtonPreferences(controller,layout(label,enabled))
        }
    }
    private fun <T> request(block: suspend () -> T): ListenableFuture<T> {
        val future=SettableFuture.create<T>()
        val job=scope.launch(Dispatchers.IO) {
            try { ready.await();future.set(block()) }
            catch(e: CancellationException) { future.cancel(false) }
            catch(e: Exception) { future.setException(e) }
        }
        future.addListener({if(future.isCancelled)job.cancel()},Executor { it.run() })
        job.invokeOnCompletion { if(it!=null)future.cancel(false) }
        return future
    }
    override fun onConnect(session: MediaSession,controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        if(controller.packageName!=packageName && !controller.isTrusted)return MediaSession.ConnectionResult.reject()
        val base=super.onConnect(session,controller)
        val notification=session.isMediaNotificationController(controller)
        val commands=base.availableSessionCommands.buildUpon().add(SessionCommand(DISTILL,Bundle.EMPTY))
        commands.add(PlaybackControls.back).add(PlaybackControls.forward)
        if (!notification && voiceEnabled()) commands.add(dev.distillpod.android.voice.CarVoiceEntry.ASK)
        if (controller.packageName == packageName) commands.add(dev.distillpod.android.voice.CarVoiceEntry.TOKEN)
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands.build())
            .setAvailablePlayerCommands(if(notification)PlaybackControls.notificationCommands else base.availablePlayerCommands)
            .setCustomLayout(layout(includeVoice=!notification && voiceEnabled()))
            .setMediaButtonPreferences(layout(includeVoice=!notification && voiceEnabled())).build()
    }
    override fun onGetLibraryRoot(session: MediaLibrarySession,browser: MediaSession.ControllerInfo,params: LibraryParams?) = request {
        LibraryResult.ofItem(catalog.item(AutoCatalog.ROOT)!!,params)
    }
    override fun onGetChildren(session: MediaLibrarySession,browser: MediaSession.ControllerInfo,parentId: String,page: Int,pageSize: Int,params: LibraryParams?) = request {
        if(catalog.item(parentId)?.mediaMetadata?.isBrowsable!=true || page<0 || pageSize<=0)
            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        else LibraryResult.ofItemList(if(pageSize>AutoCatalog.MAX_PAGE) catalog.legacyChildren(parentId) else catalog.children(parentId,page,pageSize),params)
    }
    override fun onGetItem(session: MediaLibrarySession,browser: MediaSession.ControllerInfo,mediaId: String) = request {
        catalog.item(mediaId)?.let { LibraryResult.ofItem(it,null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }
    override fun onSubscribe(session: MediaLibrarySession,browser: MediaSession.ControllerInfo,parentId:String,params:LibraryParams?) = request {
        if(catalog.item(parentId)?.mediaMetadata?.isBrowsable!=true)LibraryResult.ofError<Void>(SessionError.ERROR_BAD_VALUE)
        else { synchronized(subscriptions){subscriptions+=parentId};LibraryResult.ofVoid(params) }
    }
    override fun onUnsubscribe(session: MediaLibrarySession,browser: MediaSession.ControllerInfo,parentId:String)=request {
        withContext(Dispatchers.Main.immediate) {
            if(session.getSubscribedControllers(parentId).none {it!=browser})synchronized(subscriptions){subscriptions.remove(parentId)}
        }
        LibraryResult.ofVoid()
    }
    /** Called with coalesced database changes; the session routes notifications to subscribed controllers. */
    suspend fun libraryChanged(session:MediaLibrarySession) {
        val parents=synchronized(subscriptions){subscriptions.toList()}
        for(parent in parents) {
            val count=withContext(Dispatchers.IO){catalog.legacyChildren(parent).size}
            session.notifyChildrenChanged(parent,count,null)
        }
    }
    override fun onSearch(session:MediaLibrarySession,browser:MediaSession.ControllerInfo,query:String,params:LibraryParams?)=request {
        val count=playback.search(query,0,100).size
        withContext(Dispatchers.Main.immediate){session.notifySearchResultChanged(browser,query,count,params)}
        LibraryResult.ofVoid(params)
    }
    override fun onGetSearchResult(session:MediaLibrarySession,browser:MediaSession.ControllerInfo,query:String,page:Int,pageSize:Int,params:LibraryParams?)=request {
        LibraryResult.ofItemList(playback.search(query,page,pageSize).mapNotNull {catalog.item(it.mediaId)},params)
    }
    override fun onAddMediaItems(session: MediaSession,controller: MediaSession.ControllerInfo,mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> = request {
        playbackReady.await()
        if(controller.packageName==packageName && mediaItems.all {it.localConfiguration!=null})mediaItems
        else mediaItems.map { requested ->
            val id=requested.requestMetadata.searchQuery?.let {playback.voice(it)} ?: requested.mediaId
            val ep=db.library().episode(id) ?: throw IllegalArgumentException("Episode is unavailable")
            mediaItem(ep,db.library().podcast(ep.podcastId))
        }
    }
    override fun onSetMediaItems(session:MediaSession,controller:MediaSession.ControllerInfo,mediaItems:List<MediaItem>,startIndex:Int,startPositionMs:Long):ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val queue=(0 until session.player.mediaItemCount).map {session.player.getMediaItemAt(it).mediaId}
        val liveId=session.player.currentMediaItem?.mediaId
        val livePosition=session.player.currentPosition.coerceAtLeast(0)
        return request {
            playbackReady.await()
            if(controller.packageName==packageName && mediaItems.all {it.localConfiguration!=null})
                return@request MediaSession.MediaItemsWithStartPosition(mediaItems,startIndex,startPositionMs)
            require(mediaItems.isNotEmpty()) {"No episode was requested"}
            val item=mediaItems.getOrNull(startIndex.takeIf {it>=0}?:0)?:throw IllegalArgumentException("Episode index is unavailable")
            val id=if(item.requestMetadata.searchQuery!=null)playback.voice(item.requestMetadata.searchQuery!!) else item.mediaId
            require(!id.isNullOrBlank()) {"No matching episode"}
            val existing=queue.ifEmpty {db.library().queue().map {it.episodeId}}
            val selected=playback.select(id,existing,if(startPositionMs!=C.TIME_UNSET)startPositionMs else if(id==liveId)livePosition else null)
            MediaSession.MediaItemsWithStartPosition(selected.items,selected.index,selected.position)
        }
    }
    override fun onPlaybackResumption(session:MediaSession,controller:MediaSession.ControllerInfo):ListenableFuture<MediaSession.MediaItemsWithStartPosition> = request {
        playbackReady.await()
        val id=playback.voice("")?:throw IllegalArgumentException("Your library has no playable episodes")
        val selected=playback.select(id,db.library().queue().map {it.episodeId})
        MediaSession.MediaItemsWithStartPosition(selected.items,selected.index,selected.position)
    }
    override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
        diagnostics?.event("headphone.received",source="headphones")
        // Media3 1.5.1 marks legacy key-event callers untrusted without consulting Android.
        if (controllerInfo.packageName != packageName && !controllerInfo.isTrusted &&
            !isTrustedMediaButtonCaller(controllerInfo)) {diagnostics?.event("headphone.rejected",reason="untrusted",source="headphones");return false}
        if (headphoneNext.handle(intent, voiceEnabled() && headphoneVoiceEnabled(), onHeadphoneVoice)) return true
        return headphoneButton.handle(intent, headphoneDistillEnabled()) {
            val result = captureMoment(session,"headphones")
            result.addListener({
                if (!result.isCancelled && runCatching { result.get().resultCode }.getOrNull() !in
                    listOf(SessionResult.RESULT_SUCCESS, SessionError.INFO_CANCELLED)) {
                    scope.launch(Dispatchers.Main.immediate) { onHeadphoneCaptureFailure() }
                }
            }, Executor { it.run() })
        }
    }
    override fun onCustomCommand(session:MediaSession,controller:MediaSession.ControllerInfo,customCommand:SessionCommand,args:Bundle):ListenableFuture<SessionResult> {
        if (customCommand == dev.distillpod.android.voice.CarVoiceEntry.TOKEN) {
            val result = if (controller.packageName == packageName) SessionResult(SessionResult.RESULT_SUCCESS,
                Bundle().apply { putParcelable(dev.distillpod.android.voice.CarVoiceEntry.TOKEN_EXTRA, session.platformToken) })
                else SessionResult(SessionError.ERROR_PERMISSION_DENIED)
            return com.google.common.util.concurrent.Futures.immediateFuture(result)
        }
        if (customCommand == dev.distillpod.android.voice.CarVoiceEntry.ASK) {
            val result = if (voiceEnabled() && openCarVoice()) SessionResult(SessionResult.RESULT_SUCCESS)
                else SessionResult(SessionError(SessionError.ERROR_NOT_SUPPORTED,
                    "Open DistillPod from the car launcher. Voice needs a host that supports templated media apps."))
            return com.google.common.util.concurrent.Futures.immediateFuture(result)
        }
        if(customCommand==PlaybackControls.back || customCommand==PlaybackControls.forward) {
            if(!session.player.isCurrentMediaItemSeekable)return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            if(customCommand==PlaybackControls.back)session.player.seekBack() else session.player.seekForward()
            return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        if(customCommand.customAction!=DISTILL)return super.onCustomCommand(session,controller,customCommand,args)
        return captureMoment(session)
    }
    private fun captureMoment(session: MediaSession,source:String="car"): ListenableFuture<SessionResult> {
        diagnostics?.event("capture.requested",source=source)
        val player=session.player
        val item=player.currentMediaItem?:return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        if(captureBusy)return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionError.INFO_CANCELLED))
        val id=item.mediaId;val position=player.currentPosition.coerceAtLeast(0)
        if(position==0L)return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        val file=item.localConfiguration?.uri?.takeIf {it.scheme=="file"}?.path
        captureBusy=true;publishCarLayout(session,"Saving…",false)
        return request {
            try {
                val saved=saveMomentWithSource?.invoke(id,position,file,source) ?: saveMoment(id,position,file)
                diagnostics?.event("capture.accepted",saved,source=source)
                // Durable acceptance survives cancellation or process death before dispatch.
                runCatching { prepareMoment(saved) }
                withContext(Dispatchers.Main.immediate){publishCarLayout(session,"Saved",true)}
                SessionResult(SessionResult.RESULT_SUCCESS,Bundle().apply {putString("momentId",saved)})
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){diagnostics?.failure("capture",error,source=source);SessionResult(SessionError.ERROR_IO)}
            finally {scope.launch(Dispatchers.Main.immediate){captureBusy=false;delay(1500);publishCarLayout(session,"Distill",true)}}
        }
    }
}
