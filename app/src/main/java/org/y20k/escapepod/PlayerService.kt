/*
 * PlayerService.kt
 * Implements the PlayerService class
 * PodcastPlayerActivity is Escapepod's foreground service that plays podcast audio and handles playback controls
 *
 * This file is part of
 * ESCAPEPOD - Free and Open Podcast App
 *
 * Copyright (c) 2018-19 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 */


package org.y20k.escapepod

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.ResultReceiver
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import kotlinx.coroutines.*
import org.y20k.escapepod.collection.CollectionProvider
import org.y20k.escapepod.core.Collection
import org.y20k.escapepod.core.Episode
import org.y20k.escapepod.extensions.isActive
import org.y20k.escapepod.helpers.*
import org.y20k.escapepod.ui.PlayerState
import java.util.*
import kotlin.coroutines.CoroutineContext


/*
 * PlayerService class
 */
class PlayerService(): MediaBrowserServiceCompat(), Player.EventListener, CoroutineScope {


    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(PlayerService::class.java)


    /* Main class variables */
    private var collection: Collection = Collection()
    private var collectionProvider: CollectionProvider = CollectionProvider()
    private var episode: Episode = Episode()
    private var isForegroundService: Boolean = false
    private lateinit var player: SimpleExoPlayer
    private lateinit var playerState: PlayerState
    private lateinit var backgroundJob: Job
    private lateinit var packageValidator: PackageValidator
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaController: MediaControllerCompat
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var userAgent: String
    private lateinit var collectionChangedReceiver: BroadcastReceiver
    private lateinit var becomingNoisyReceiver: BecomingNoisyReceiver
    private lateinit var sleepTimer: CountDownTimer
    private var sleepTimerTimeRemaining: Long = 0L


    /* Overrides coroutineContext variable */
    override val coroutineContext: CoroutineContext get() = backgroundJob + Dispatchers.Main


    /* Overrides onCreate */
    override fun onCreate() {
        super.onCreate()

        // initialize background job
        backgroundJob = Job()

        // set user agent
        userAgent = Util.getUserAgent(this, Keys.APPLICATION_NAME)

        // get the package validator // todo can be local?
        packageValidator = PackageValidator(this, R.xml.allowed_media_browser_callers)

        // fetch the player state
        playerState = PreferencesHelper.loadPlayerState(this)

        // create player
        player = createPlayer()

        // create a new MediaSession
        mediaSession = createMediaSession()
        sessionToken = mediaSession.sessionToken

        // because ExoPlayer will manage the MediaSession, add the service as a callback for state changes
        mediaController = MediaControllerCompat(this, mediaSession).also {
            it.registerCallback(MediaControllerCallback())
        }

        // initialize notification helper and notification manager
        notificationHelper = NotificationHelper(this)
        notificationManager = NotificationManagerCompat.from(this)

        // create and register collection changed receiver
        collectionChangedReceiver = createCollectionChangedReceiver()
        LocalBroadcastManager.getInstance(application).registerReceiver(collectionChangedReceiver, IntentFilter(Keys.ACTION_COLLECTION_CHANGED))

        // initialize listener for unplugging of headphones
        becomingNoisyReceiver = BecomingNoisyReceiver(this, mediaSession.sessionToken)

        // load collection
        loadCollection(this)
    }


    /* Overrides onStartCommand */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return Service.START_STICKY
    }


    /* Overrides onTaskRemoved */
    override fun onTaskRemoved(rootIntent: Intent) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }


    /* Overrides onDestroy */
    override fun onDestroy() {
        // stop playback
        stopPlayback()
        // release media session
        mediaSession.run {
            isActive = false
            release()
        }
        // unregister headphone un-plug listener
        becomingNoisyReceiver.unregister()
        // cancel background job
        backgroundJob.cancel()
        // release player
        player.removeAnalyticsListener(analyticsListener)
        player.release()
    }


    /* Overrides onGetRoot */ // todo: implement a hierarchical structure -> https://github.com/googlesamples/android-UniversalMusicPlayer/blob/47da058112cee0b70442bcd0370c1e46e830c66b/media/src/main/java/com/example/android/uamp/media/library/BrowseTree.kt
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // Credit: https://github.com/googlesamples/android-UniversalMusicPlayer (->  MusicService)
        // LogHelper.d(TAG, "OnGetRoot: clientPackageName=$clientPackageName; clientUid=$clientUid ; rootHints=$rootHints")
        // to ensure you are not allowing any arbitrary app to browse your app's contents, you need to check the origin
        if (!packageValidator.isKnownCaller(clientPackageName, clientUid)) {
            // request comes from an untrusted package
            LogHelper.i(TAG, "OnGetRoot: Browsing NOT ALLOWED for unknown caller. "
                    + "Returning empty browser root so all apps can use MediaController."
                    + clientPackageName)
            return BrowserRoot(Keys.MEDIA_ID_EMPTY_ROOT, null)
        } else {
            // content style extras: see https://developer.android.com/training/cars/media#apply_content_style
            val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
            val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
            val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
            val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
            val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 2
            val extras = Bundle()
            extras.putBoolean(CONTENT_STYLE_SUPPORTED, true)
            extras.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            extras.putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
            return BrowserRoot(Keys.MEDIA_ID_ROOT, extras)
        }
    }


    /* Overrides onLoadChildren */
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (!collectionProvider.isInitialized()) {
            // use result.detach to allow calling result.sendResult from another thread:
            result.detach()
            collectionProvider.retrieveMedia(collection, object: CollectionProvider.CollectionProviderCallback {
                override fun onEpisodeListReady(success: Boolean) {
                    if (success) {
                        loadChildren(parentId, result)
                    }
                }
            })
        } else {
            // if music catalog is already loaded/cached, load them into result immediately
            loadChildren(parentId, result)
        }
    }


    /* Overrides onPlayerStateChanged (Player.EventListener) */
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        when (playWhenReady) {
            // CASE: playWhenReady = true
            true -> {
                if (playbackState == Player.STATE_READY) {
                    // active playback: update media session and save state
                    handlePlaybackChange(PlaybackStateCompat.STATE_PLAYING)
                } else if (playbackState == Player.STATE_ENDED) {
                    // playback reached end
                    handlePlaybackEnded()
                } else {
                    // not playing because the player is buffering, stopped or failed - check playbackState and player.getPlaybackError for details)
                }
            }
            // CASE: playWhenReady = false
            false -> {
                if (playbackState == Player.STATE_READY) {
                    // paused by app: update media session and save state
                    handlePlaybackChange(PlaybackStateCompat.STATE_PAUSED)
                } else if (playbackState == Player.STATE_ENDED) {
                    // paused by app: update media session and save state
                    handlePlaybackChange(PlaybackStateCompat.STATE_STOPPED)
                }
                // stop sleep timer - if running
                cancelSleepTimer()
            }
        }
    }


    /* Updates media session and save state */
    private fun handlePlaybackChange(playbackState: Int) {
        // update playback position
        episode.playbackPosition = player.contentPosition
        // save collection state and player state
        collection = CollectionHelper.savePlaybackState(this, collection, episode, playbackState)
        updatePlayerState(episode, playbackState)
        // update media session
        mediaSession.setPlaybackState(createPlaybackState(playbackState, episode.playbackPosition))
        mediaSession.isActive = playbackState != PlaybackStateCompat.STATE_STOPPED
    }


    /* End of episode: stop playback or start episode from up-next queue */
    private fun handlePlaybackEnded() {
        // update playback position
        episode.playbackPosition = episode.duration
        // CASE: Up next episode NOT available
        if (playerState.upNextEpisodeMediaId.isEmpty() || playerState.upNextEpisodeMediaId == episode.getMediaId()) {
            // clear up-next id
            playerState.upNextEpisodeMediaId = String()
            // stop playback
            stopPlayback()
        // CASE: Up next episode available
        } else {
            // get up next episode and set metadata
            episode = CollectionHelper.getEpisode(collection, playerState.upNextEpisodeMediaId)
            mediaSession.setMetadata(CollectionHelper.buildEpisodeMediaMetadata(this, episode))
            // clear up next media id
            playerState.upNextEpisodeMediaId = String()
            // start playback
            startPlayback()
        }
    }


    /* Creates a new MediaSession */
    private fun createMediaSession(): MediaSessionCompat {
        val initialPlaybackState: Int = PreferencesHelper.loadPlayerPlaybackState(this)
        val sessionActivityPendingIntent =
                packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                    PendingIntent.getActivity(this, 0, sessionIntent, 0)
                }
        return MediaSessionCompat(this, TAG)
                .apply {
                    setSessionActivity(sessionActivityPendingIntent)
                    setCallback(mediaSessionCallback)
                    setPlaybackState(createPlaybackState(initialPlaybackState, 0))
                }
    }


    /* Creates a simple exo player */
    private fun createPlayer(): SimpleExoPlayer {
        if (this::player.isInitialized) {
            player.removeAnalyticsListener(analyticsListener)
            player.release()
        }
        val player = ExoPlayerFactory.newSimpleInstance(this).apply { addListener(this@PlayerService) }
        val audioAttributes: AudioAttributes = AudioAttributes.Builder()
                .setContentType(C.CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build()
        player.setAudioAttributes(audioAttributes, true)
        player.addAnalyticsListener(analyticsListener)
        player.seekTo(playerState.playbackPosition)
        return player
    }


    /* Prepares player with media source created from current episode */
    private fun preparePlayer() {
        // todo only prepare if not already prepared
        // create MediaSource
        val mediaSource: MediaSource = ProgressiveMediaSource.Factory(DefaultDataSourceFactory(this, userAgent)).createMediaSource(Uri.parse(episode.audio))
        // prepare player with source
        player.prepare(mediaSource)
        // set player position
        playerState.playbackPosition = episode.playbackPosition
        player.seekTo(playerState.playbackPosition)
        player.playbackParameters = PlaybackParameters(playerState.playbackSpeed)
    }


    /* Start playback with current episode */
    private fun startPlayback() {
        LogHelper.d(TAG, "Starting Playback. Position: ${episode.playbackPosition}. Duration: ${episode.duration}")
        // sanity check
        if (episode.audio.isEmpty()) {
            LogHelper.e(TAG, "Unable to start playback. No episode has been selected.")
            return
        }
        // reset playback position if necessary
        if (episode.isFinished()) {
            episode.playbackPosition = 0L
        }
        // check if episode is in up-next queue
        if (playerState.upNextEpisodeMediaId == episode.getMediaId()){
            playerState.upNextEpisodeMediaId = String()
        }
        // prepare player
        preparePlayer()
        // start playback
        player.playWhenReady = true
    }


    /* Stop playback */
    private fun stopPlayback() {
        LogHelper.d(TAG, "Stopping Playback")
        // update playback position
        episode.playbackPosition = player.contentPosition
        // pause playback
        player.playWhenReady = false
    }


    /* Creates playback state - actions for playback state to be used in media session callback */
    private fun createPlaybackState(state: Int, position: Long): PlaybackStateCompat {
        val skipActions: Long = PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or  PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        when(state) {
            PlaybackStateCompat.STATE_PLAYING -> {
                return PlaybackStateCompat.Builder()
                        .setState(PlaybackStateCompat.STATE_PLAYING, position, 1f)
                        .setActions(PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or skipActions)
                        .build()
            }
            PlaybackStateCompat.STATE_PAUSED -> {
                return PlaybackStateCompat.Builder()
                        .setState(PlaybackStateCompat.STATE_PAUSED, position, 0f)
                        .setActions(PlaybackStateCompat.ACTION_PLAY)
                        .build()
            }
            else -> {
                return PlaybackStateCompat.Builder()
                        .setState(PlaybackStateCompat.STATE_STOPPED, position, 0f)
                        .setActions(PlaybackStateCompat.ACTION_PLAY)
                        .build()
            }
        }
    }


    /* Starts sleep timer / adds default duration to running sleeptimer */
    private fun startSleepTimer() {
        // stop running timer
        if (sleepTimerTimeRemaining > 0L && this::sleepTimer.isInitialized) {
            sleepTimer.cancel()
        }
        // initialize timer
        sleepTimer = object:CountDownTimer (Keys.SLEEP_TIMER_DURATION + sleepTimerTimeRemaining, Keys.SLEEP_TIMER_INTERVAL) {
            override fun onFinish() {
                LogHelper.v(TAG, "Sleep timer finished. Sweet dreams.")
                // reset time remaining
                sleepTimerTimeRemaining = 0L
                // stop playback
                stopPlayback()
            }
            override fun onTick(millisUntilFinished: Long) {
                sleepTimerTimeRemaining = millisUntilFinished
            }
        }
        // start timer
        sleepTimer.start()
    }


    /* Cancels sleep timer */
    private fun cancelSleepTimer() {
        if (this::sleepTimer.isInitialized) {
            sleepTimerTimeRemaining = 0L
            sleepTimer.cancel()
        }
    }


    /* Updates / increases the playback speed */
    private fun updatePlaybackSpeed(currentSpeed: Float = 1f): Float {
        var newSpeed: Float = 1f
        // circle through the speed presets
        val iterator = Keys.PLAYBACK_SPEEDS.iterator()
        while (iterator.hasNext()) {
            // found current speed in array
            if (iterator.next() == currentSpeed) {
                if (iterator.hasNext()) {
                    newSpeed = iterator.next()
                }
                break
            }
        }
        // apply new speed
        setPlaybackSpeed(newSpeed)
        return newSpeed
    }


    /* Sets playback speed */
    private fun setPlaybackSpeed(speed: Float = 1f) {
        // update playback parameters - speed up playback
        player.playbackParameters = PlaybackParameters(speed)
        // save speed
        playerState.playbackSpeed = speed
        PreferencesHelper.savePlayerPlaybackSpeed(this, speed)
    }



    /* Loads media items into result - assumes that collectionProvider is initialized */
    private fun loadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val mediaItems = ArrayList<MediaBrowserCompat.MediaItem>()
        when (parentId) {
            Keys.MEDIA_ID_ROOT -> {
                collectionProvider.episodeListByDate.forEach { item ->
                    mediaItems.add(item)
                }
            }
            Keys.MEDIA_ID_EMPTY_ROOT -> {
                // do nothing
            }
            else -> {
                // log error
                LogHelper.w(TAG, "Skipping unmatched parentId: $parentId")
            }
        }
        result.sendResult(mediaItems)
    }


    /* Creates the collectionChangedReceiver - handles Keys.ACTION_COLLECTION_CHANGED */
    private fun createCollectionChangedReceiver(): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.hasExtra(Keys.EXTRA_LAST_UPDATE_COLLECTION)) {
                    val lastUpdate: Date = DateTimeHelper.convertFromRfc2822(intent.getStringExtra(Keys.EXTRA_LAST_UPDATE_COLLECTION))
                    // check if reload is necessary
                    if (lastUpdate.after(collection.lastUpdate)) {
                        LogHelper.i(TAG, "PlayerService - reload collection after broadcast received.") // todo remove
                        loadCollection(context)
                    }
                }
            }
        }
    }


    /* Reads podcast collection from storage using GSON */
    private fun loadCollection(context: Context) {
        LogHelper.v(TAG, "Loading podcast collection from storage")
        launch {
            // load collection on background thread
            val deferred: Deferred<Collection> = async(Dispatchers.Default) { FileHelper.readCollectionSuspended(context) }
            // wait for result and update collection
            collection = deferred.await()
        }
    }


    /* Updates and saves the state of the player ui */
    private fun updatePlayerState (episode: Episode, playbackState: Int) {
        playerState.episodeMediaId = episode.getMediaId()
        playerState.playbackPosition = episode.playbackPosition
        playerState.episodeDuration = episode.duration
        playerState.playbackState = playbackState
        PreferencesHelper.savePlayerState(this, playerState)
    }



    /*
     * Custom AnalyticsListener that enables AudioFX equalizer integration
     */
    private var analyticsListener = object: AnalyticsListener {
        override fun onAudioSessionId(eventTime: AnalyticsListener.EventTime?, audioSessionId: Int) {
            super.onAudioSessionId(eventTime, audioSessionId)
            // integrate with system equalizer (AudioFX)
            val intent: Intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            sendBroadcast(intent)
        }
    }


    /*
     * Callback: Defines callbacks for active media session
     */
    private var mediaSessionCallback = object: MediaSessionCompat.Callback() {
        override fun onPlay() {
            startPlayback()
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            // get episode, set metadata and start playback
            episode = CollectionHelper.getEpisode(collection, mediaId ?: String())
            mediaSession.setMetadata(CollectionHelper.buildEpisodeMediaMetadata(this@PlayerService, episode))
            startPlayback()
        }

        override fun onPause() {
            stopPlayback()
        }

        override fun onStop() {
            stopPlayback()
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (query.isNullOrEmpty()) {
                // user provided generic string e.g. 'Play music'
                val mediaId = collectionProvider.getNewestEpisode().mediaId
                onPlayFromMediaId(mediaId, null)
            } else {
                // try to match podcast name and voice query - and start newest episode of that podcast
                collectionProvider.episodeListByDate.forEach { mediaItem ->
                    // get podcast name (here -> subtitle)
                    val podcastName: String = mediaItem.description.subtitle.toString().toLowerCase(Locale.getDefault())
                    // FIRST: try to match the whole query
                    if (podcastName == query) {
                        // start playback of newest podcast episode
                        onPlayFromMediaId(mediaItem.description.mediaId, null)
                        return
                    }
                    // SECOND: try to match parts of the query
                    val words: List<String> = query.split(" ")
                    words.forEach { word ->
                        if (podcastName.contains(word)) {
                            // start playback of newest podcast episode
                            onPlayFromMediaId(mediaItem.description.mediaId, null)
                            return
                        }
                    }
                }
            }
        }


        override fun onFastForward() {
            LogHelper.d(TAG, "Skipping forward")
            // update position
            var position: Long = player.currentPosition + Keys.SKIP_FORWARD_TIME_SPAN
            if (position > episode.duration) {
                position =  episode.duration
            }
            player.seekTo(position)
        }

        override fun onRewind() {
            LogHelper.d(TAG, "Skipping back")
            var position: Long = player.currentPosition - Keys.SKIP_BACK_TIME_SPAN
            if (position < 0L) {
                position = 0L
            }
            player.seekTo(position)
        }

        override fun onSkipToPrevious() {
            // note: rewind is the new skip to previous ^o^
            onRewind()
        }

        override fun onSkipToNext() {
            // note: fast forward is the new skip to next ^o^
            onFastForward()
        }

        override fun onSeekTo(posistion: Long) {
            episode.playbackPosition = posistion
            player.seekTo(posistion)
        }

        override fun onCommand(command: String?, extras: Bundle?, cb: ResultReceiver?) {
            when (command) {
                Keys.CMD_RELOAD_PLAYER_STATE -> {
                    playerState = PreferencesHelper.loadPlayerState(this@PlayerService)
                }
                Keys.CMD_REQUEST_PERIODIC_PROGRESS_UPDATE -> {
                    if (cb != null) {
                        val playbackProgressBundle: Bundle = bundleOf(Keys.RESULT_DATA_PLAYBACK_PROGRESS to player.currentPosition)
                        if (sleepTimerTimeRemaining > 0L) {
                            playbackProgressBundle.putLong(Keys.RESULT_DATA_SLEEP_TIMER_REMAINING, sleepTimerTimeRemaining)
                        }
                        cb.send(Keys.RESULT_CODE_PERIODIC_PROGRESS_UPDATE, playbackProgressBundle)
                    }
                }
                Keys.CMD_START_SLEEP_TIMER -> {
                    startSleepTimer()
                }
                Keys.CMD_CANCEL_SLEEP_TIMER -> {
                    cancelSleepTimer()
                }
                Keys.CMD_CHANGE_PLAYBACK_SPEED -> {
                    if (cb != null) {
                        // change player speed
                        val newPlaybackSpeed: Float = updatePlaybackSpeed(playerState.playbackSpeed)
                        // send back new playback speed
                        val playbackSpeedBundle: Bundle = bundleOf(Keys.RESULT_DATA_PLAYBACK_SPEED to newPlaybackSpeed)
                        cb.send(Keys.RESULT_CODE_PLAYBACK_SPEED, playbackSpeedBundle)
                    }
                }
                Keys.CMD_RESET_PLAYBACK_SPEED -> {
                    if (cb != null) {
                        // change player speed
                        setPlaybackSpeed(1f)
                        // send back new playback speed
                        val playbackSpeedBundle: Bundle = bundleOf(Keys.RESULT_DATA_PLAYBACK_SPEED to 1f)
                        cb.send(Keys.RESULT_CODE_PLAYBACK_SPEED, playbackSpeedBundle)
                    }
                }
            }
        }

    }
    /*
     * End of callback
     */


    /*
     * Inner class: Class to receive callbacks about state changes to the MediaSessionCompat - handles notification
     * Source: https://github.com/googlesamples/android-UniversalMusicPlayer/blob/master/common/src/main/java/com/example/android/uamp/media/MusicService.kt
     */
    private inner class MediaControllerCallback : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            LogHelper.d(TAG, "onMetadataChanged called") // todo remove
            mediaController.playbackState?.let { updateNotification(it) }
        }

        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            LogHelper.d(TAG, "onPlaybackStateChanged called") // todo remove
            state?.let { updateNotification(it) }
        }

        private fun updateNotification(state: PlaybackStateCompat) {
            LogHelper.d(TAG, "updateNotification called") // todo remove

            // skip building a notification when state is "none" and metadata is null
            // val notification = if (mediaController.metadata != null && state.state != PlaybackStateCompat.STATE_NONE) {
            val notification = if (state.state != PlaybackStateCompat.STATE_NONE) {
                notificationHelper.buildNotification(mediaSession.sessionToken, episode)
            } else {
                null
            }

            when (state.isActive) {
                // CASE: Playback has started
                true -> {
                    // start listening for unplugging of headphone
                    becomingNoisyReceiver.register()

                    /**
                     * This may look strange, but the documentation for [Service.startForeground]
                     * notes that "calling this method does *not* put the service in the started
                     * state itself, even though the name sounds like it."
                     */
                    if (notification != null) {
                        notificationManager.notify(Keys.NOTIFICATION_NOW_PLAYING_ID, notification)
                        if (!isForegroundService) {
                            ContextCompat.startForegroundService(applicationContext, Intent(applicationContext, this@PlayerService.javaClass))
                            startForeground(Keys.NOTIFICATION_NOW_PLAYING_ID, notification)
                            isForegroundService = true
                        }
                    }
                }
                // CASE: Playback has stopped
                false -> {
                    // stop listening for unplugging of headphone
                    becomingNoisyReceiver.unregister()

                    if (isForegroundService) {
                        stopForeground(false)
                        isForegroundService = false

                        // If playback has ended, also stop the service.
                        if (state.state == PlaybackStateCompat.STATE_NONE) {
                            stopSelf()
                        }

                        if (notification != null) {
                            notificationManager.notify(Keys.NOTIFICATION_NOW_PLAYING_ID, notification)
                        } else {
                            // removeNowPlayingNotification
                            stopForeground(true)
                        }
                    }

                }
            }
        }
    }


    /*
     * Inner class: listening for when headphones are unplugged ("ACTION_AUDIO_BECOMING_NOISY")
     */
    private inner class BecomingNoisyReceiver(private val context: Context, sessionToken: MediaSessionCompat.Token): BroadcastReceiver() {

        private val noisyIntentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        private val controller = MediaControllerCompat(context, sessionToken)

        private var registered = false

        fun register() {
            if (!registered) {
                context.registerReceiver(this, noisyIntentFilter)
                registered = true
            }
        }

        fun unregister() {
            if (registered) {
                context.unregisterReceiver(this)
                registered = false
            }
        }

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                // stop playback
                stopPlayback()
            }
        }
    }
    /*
     * End of inner class
     */

}