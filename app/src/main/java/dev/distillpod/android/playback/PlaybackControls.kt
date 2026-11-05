package dev.distillpod.android.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import dev.distillpod.android.R

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlaybackControls {
    val back = SessionCommand("dev.distillpod.android.BACK_10_SECONDS", Bundle.EMPTY)
    val distill = SessionCommand("dev.distillpod.android.DISTILL_MOMENT", Bundle.EMPTY)
    val forward = SessionCommand("dev.distillpod.android.FORWARD_30_SECONDS", Bundle.EMPTY)
    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
        .add(back).add(distill).add(forward).build()
    val buttons = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10).setDisplayName("Back 10 seconds")
            .setSessionCommand(back).setSlots(CommandButton.SLOT_BACK).build(),
        CommandButton.Builder(CommandButton.ICON_UNDEFINED).setCustomIconResId(R.drawable.ic_media_distill)
            .setDisplayName("Distill last 60 seconds").setSessionCommand(distill).setSlots(CommandButton.SLOT_FORWARD).build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30).setDisplayName("Forward 30 seconds")
            .setSessionCommand(forward).setSlots(CommandButton.SLOT_OVERFLOW).build(),
    )
    val notificationCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
        .remove(Player.COMMAND_SEEK_TO_PREVIOUS).remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
}
