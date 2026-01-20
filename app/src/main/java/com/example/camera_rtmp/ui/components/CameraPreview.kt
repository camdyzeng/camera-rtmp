package com.example.camera_rtmp.ui.components

import android.view.SurfaceHolder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.pedro.library.view.OpenGlView

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onSurfaceCreated: (OpenGlView, SurfaceHolder) -> Unit,
    onSurfaceChanged: (SurfaceHolder, Int, Int) -> Unit = { _, _, _ -> },
    onSurfaceDestroyed: () -> Unit = {}
) {
    val context = LocalContext.current
    
    val openGlView = remember {
        OpenGlView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    onSurfaceCreated(this@apply, holder)
                }
                
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    onSurfaceChanged(holder, width, height)
                }
                
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    onSurfaceDestroyed()
                }
            })
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            onSurfaceDestroyed()
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { openGlView },
            modifier = Modifier.fillMaxSize()
        )
    }
}
