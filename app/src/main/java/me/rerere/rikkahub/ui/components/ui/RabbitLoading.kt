package me.rerere.rikkahub.ui.components.ui

import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings

@Composable
fun RabbitLoadingIndicator(modifier: Modifier = Modifier) {
    val useAppIconStyleLoadingIndicator = LocalSettings.current.displaySetting.useAppIconStyleLoadingIndicator
    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()

    if (useAppIconStyleLoadingIndicator) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                ImageView(context).apply {
                    val drawable = AppCompatResources.getDrawable(context, R.drawable.rabbit) as? AnimatedVectorDrawable
                    setImageDrawable(drawable)
                    drawable?.setTint(primaryColor)
                }
            },
            update = { imageView ->
                (imageView.drawable as? AnimatedVectorDrawable)?.apply {
                    setTint(primaryColor)
                    // Chat state changes frequently while a reply streams. AndroidView calls
                    // update on every recomposition; restarting an AnimatedVectorDrawable here
                    // repeatedly resets its 3-second animation and can leave the icon looking
                    // like a static, solid blob. Start it only when it is not already running.
                    if (!isRunning) start()
                }
            }
        )
    } else {
        ContainedLoadingIndicator(
            modifier = modifier,
        )
    }
}
