package dev.distillpod.android.sync

import android.app.Activity
import android.content.Context
import androidx.credentials.*
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.work.*
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import dev.distillpod.android.AppGraph
import dev.distillpod.android.R
import dev.distillpod.android.appGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

/** Optional runtime: no Firebase app is created when a build has no cloud configuration. */
class SubscriptionSyncAccount(private val graph:AppGraph) {
    data class State(val configured:Boolean=false,val connected:Boolean=false,val uid:String="",val busy:Boolean=false,val email:String="",val message:String="")
    private val context get()=graph.context
    val store=SubscriptionSyncStore(graph.db)
    private val mutable=MutableStateFlow(State(configured=!SubscriptionCloudAccess.instrumentation && listOf(R.string.sync_api_key,R.string.sync_web_client_id,R.string.sync_app_id,R.string.sync_project_id).all {context.getString(it).isNotBlank()}))
    val state=mutable.asStateFlow()
    val pending=graph.db.sync().observeCount()
    val conflicts=graph.db.sync().observeConflicts()
    private val app by lazy {
        FirebaseApp.getApps(context).firstOrNull { it.name=="distillpod-sync" } ?: FirebaseApp.initializeApp(context,
            FirebaseOptions.Builder().setApplicationId(context.getString(R.string.sync_app_id)).setApiKey(context.getString(R.string.sync_api_key))
                .setProjectId(context.getString(R.string.sync_project_id)).build(),"distillpod-sync")
    }
    private val auth by lazy { FirebaseAuth.getInstance(app) }
    private val cloud by lazy { FirebaseSubscriptionCloud(auth,FirebaseFirestore.getInstance(app).apply {
        firestoreSettings=FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
    }) }
    private val coordinator by lazy { SubscriptionSyncCoordinator(graph.db,cloud) }
    private var scheduled:Job?=null
    private val connecting=Mutex()
    init {
        if(mutable.value.configured) {
            auth.addAuthStateListener { current -> mutable.update { it.copy(connected=current.currentUser!=null,uid=current.currentUser?.uid.orEmpty(),email=current.currentUser?.email.orEmpty()) } }
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("subscription-sync-recovery",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SubscriptionSyncWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
    suspend fun connect(activity:Activity,approvedFeedIds:Set<String>?=null) {
        check(mutable.value.configured) { "Subscription sync is not configured in this build." }
        if(!connecting.tryLock())return
        mutable.update { it.copy(busy=true,message="") }
        try {
            check(graph.db.sync().binding()!=null || approvedFeedIds!=null) { "Review the subscriptions to share first." }
            val option=GetSignInWithGoogleOption.Builder(context.getString(R.string.sync_web_client_id)).build()
            val credential=CredentialManager.create(activity).getCredential(activity,GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
            require(credential is CustomCredential && credential.type==GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Google sign-in did not return an account." }
            val token=GoogleIdTokenCredential.createFrom(credential.data).idToken
            withTimeout(45_000) {
            val user=auth.signInWithCredential(GoogleAuthProvider.getCredential(token,null)).await().user ?: error("Sign-in did not finish")
            val old=graph.db.sync().binding()
            if(old!=null && old.uid!=user.uid) {
                auth.signOut()
                error("This library belongs to another sync account. Sign in with its original account.")
            }
            val head=cloud.head()
            store.bind(head.owner,approvedFeedIds?:emptySet())
            }
            kick()
        } catch(timeout:TimeoutCancellationException) {
            mutable.update { it.copy(message="Connection took too long. Your local library is safe; try again when connected.") }
        } catch(cancelled:GetCredentialCancellationException) {
            mutable.update { it.copy(message="Sign-in cancelled. Your library stays on this phone.") }
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { mutable.update { it.copy(message="Sync could not connect. Check your connection and use this library's original Google account.") } }
        finally { mutable.update { it.copy(busy=false) };connecting.unlock() }
    }
    suspend fun approveFeeds(ids:Set<String>) {
        val binding=graph.db.sync().binding() ?: error("Connect your sync account first.")
        check(binding.enabled && auth.currentUser?.uid==binding.uid) { "Resume your original sync account first." }
        store.bind(dev.distillpod.core.sync.SyncOwner(binding.uid,binding.generation),ids);kick()
    }
    suspend fun pause() { store.pause();WorkManager.getInstance(context).cancelUniqueWork("subscription-sync") }
    suspend fun resume() {
        val binding=graph.db.sync().binding() ?: return
        check(auth.currentUser?.uid==binding.uid) { "Sign in with your sync account first." }
        store.bind(dev.distillpod.core.sync.SyncOwner(binding.uid,binding.generation));kick()
    }
    suspend fun disconnect() {
        pause();auth.signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
    fun kick() {
        if(!mutable.value.configured)return
        scheduled?.cancel()
        scheduled=graph.scope.launch {
            delay(300)
            WorkManager.getInstance(context).enqueueUniqueWork("subscription-sync",ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SubscriptionSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
    fun signedIn(uid:String):Boolean=mutable.value.configured && auth.currentUser?.uid==uid
    suspend fun syncOnce():Boolean {
        if(!mutable.value.configured)return true
        return coordinator.run()
    }
}
class SubscriptionSyncWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val graph=applicationContext.appGraph
        return try {
            val completed=withTimeout(60_000) { graph.syncAccount.syncOnce() }
            val b=graph.db.sync().binding()
            val signedIn=b?.let {graph.syncAccount.signedIn(it.uid)}==true
            if(b?.enabled==true && signedIn && graph.settings.flow.first().refresh) {
                graph.db.library().podcasts().filter { it.subscribed && it.refreshedAt==0L && it.error==null }.forEach { graph.jobs.refresh(it.id) }
            }
            val pending=b?.let {graph.db.sync().nextOperation(it.uid,it.generation)!=null}==true
            if(shouldRetrySubscriptionWork(completed,b,signedIn,pending)) {
                if(runAttemptCount<8)Result.retry() else Result.failure()
            } else Result.success()
        } catch(timeout:TimeoutCancellationException){if(runAttemptCount<8)Result.retry() else Result.failure()}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){if(runAttemptCount<8)Result.retry() else Result.failure()}
    }
}
